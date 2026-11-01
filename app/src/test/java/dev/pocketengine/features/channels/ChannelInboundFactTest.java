package dev.pocketengine.features.channels;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Typed producer evidence only. C4 owns the original waiting-task SQL consumer/authorization. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ChannelInboundFactTest {
    @Before public void vault(){java.security.Security.addProvider(new java.security.Provider("InboundFactFixtureVault","1","Test-only AndroidKeyStore SPI"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void release(){java.security.Security.removeProvider("InboundFactFixtureVault");}
    private static Context context()throws Exception {Context original=RuntimeEnvironment.getApplication();java.io.File directory=Files.createTempDirectory(original.getFilesDir().toPath(),"native-inbound-fact-").toFile();return new ContextWrapper(original){public java.io.File getFilesDir(){return directory;}public Context getApplicationContext(){return this;}};}
    private static ChannelCore core(ChannelsModule module)throws Exception {var field=ChannelsModule.class.getDeclaredField("core");field.setAccessible(true);return(ChannelCore)field.get(module);}
    private static JSONObject message(String id,String text)throws Exception{return new JSONObject().put("messageId",id).put("senderId","same-owner").put("kind","dm").put("text",text).put("sentAt",System.currentTimeMillis());}
    @Test public void actualAuthenticatedAcceptEmitsPrivateBodyFreeFactOutsideCoreLockAndColdReadUsesOnlyOriginalLedger()throws Exception {
        Context android=context();FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),ignored->{});String secret="controlled-inbound-secret-more-than-32-characters";ChannelsModule module=new ChannelsModule(context);String event;CopyOnWriteArrayList<ChannelsModule.InboundFact> observed=new CopyOnWriteArrayList<>();
        try{String profile=module.handle("profile.save",new JSONObject().put("provider","webhook").put("name","Controlled original source").put("secret",secret).put("enabled",true)).getString("id");ChannelCore core=core(module);module.setInboundObserver(fact->{assertFalse(Thread.holdsLock(core));fact.checkCurrent();observed.add(fact);});int port=module.handle("server.start",new JSONObject().put("port",0)).getInt("port");
            JSONObject response=ChannelHttp.post("http://127.0.0.1:"+port+"/events/"+profile,"Bearer "+secret,message("native-message","private actual body never exposed in metadata"));event=response.getString("eventId");assertEquals(1,observed.size());ChannelsModule.InboundFact fact=observed.get(0);JSONObject metadata=fact.metadata();assertEquals(event,metadata.getString("eventId"));assertTrue(metadata.isNull("sourceTaskId"));assertEquals("unassociated_original_inbound",metadata.getString("sourceTaskBasis"));assertTrue(metadata.getString("payloadSha256").matches("[a-f0-9]{64}"));assertFalse(metadata.toString().contains(secret));assertFalse(metadata.toString().contains("private actual body"));assertFalse(metadata.has("text"));metadata.put("profileId","forged-view");fact.checkCurrent();assertEquals(profile,fact.metadata().getString("profileId"));
            assertEquals(fact.metadata().toString(),module.originalInboundFact(event).metadata().toString());assertThrows(SecurityException.class,()->module.originalInboundFact(UUID.randomUUID().toString()));ChannelHttp.post("http://127.0.0.1:"+port+"/events/"+profile,"Bearer "+secret,message("native-message","private actual body never exposed in metadata"));assertEquals(1,observed.size());
        }finally{module.close();}
        ChannelsModule restored=new ChannelsModule(context);try{ChannelsModule.InboundFact fact=restored.originalInboundFact(event);fact.checkCurrent();assertEquals(event,fact.metadata().getString("eventId"));JSONObject profile=restored.snapshot().getJSONArray("profiles").getJSONObject(0);profile.put("enabled",false);restored.handle("profile.save",profile);assertThrows(SecurityException.class,fact::checkCurrent);}finally{restored.close();}
    }
    @Test public void taskAssociationOnlyComesFromOriginalNativeAdmissionAndBindingOrTransportChangesRevokeTheFact()throws Exception {
        Context android=context();FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),ignored->{});ChannelsModule module=new ChannelsModule(context);AtomicInteger admitted=new AtomicInteger();CopyOnWriteArrayList<ChannelsModule.InboundFact> facts=new CopyOnWriteArrayList<>();String secret="fixture-inbound-native-task-secret-32-characters";
        try{String profile=module.handle("profile.save",new JSONObject().put("provider","webhook").put("name","Controlled original host").put("secret",secret).put("enabled",true)).getString("id");ChannelCore core=core(module);String binding=core.accept(profile,message("binding","Own account"),"trusted_relay").getJSONObject("event").getString("id");module.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",binding).put("confirmed",true));module.handle("owner.tasks.set",ChannelTaskHostTest.taskPermission(module,profile,true));module.setInboundObserver(fact->{assertFalse(Thread.holdsLock(core));facts.add(fact);});module.setHostTaskPort(new HostTaskPort(){public TaskState admit(Request request,Authority authority)throws Exception{authority.check();admitted.incrementAndGet();return new TaskState(request.taskId,UUID.randomUUID().toString(),1,1,"awaiting_user","Original host waiting");}public TaskState inspect(Request request,Authority authority){return null;}public TaskState cancel(Request request,Authority authority){throw new AssertionError("Not used");}});
            int port=module.handle("server.start",new JSONObject().put("port",0)).getInt("port");JSONObject response=ChannelHttp.post("http://127.0.0.1:"+port+"/events/"+profile,"Bearer "+secret,message("task-origin","/task exact controlled native task").put("sourceTaskId","model-cannot-bind-this"));String event=response.getString("eventId");assertEquals(1,admitted.get());assertTrue(facts.size()>=2);ChannelsModule.InboundFact original=facts.get(0),linked=module.originalInboundFact(event);assertTrue(original.metadata().isNull("sourceTaskId"));assertFalse(linked.metadata().isNull("sourceTaskId"));assertEquals("original_channel_task_request",linked.metadata().getString("sourceTaskBasis"));assertEquals(module.snapshot().getJSONArray("taskRequests").getJSONObject(0).getString("taskId"),linked.metadata().getString("sourceTaskId"));assertNotEquals("model-cannot-bind-this",linked.metadata().getString("sourceTaskId"));assertEquals(original.metadata().getString("payloadSha256"),linked.metadata().getString("payloadSha256"));assertThrows(SecurityException.class,original::checkCurrent);linked.checkCurrent();
            JSONObject edited=core.profile(profile);edited.put("autoReply",false);module.handle("profile.save",edited);assertThrows(SecurityException.class,linked::checkCurrent);String requestId=module.snapshot().getJSONArray("taskRequests").getJSONObject(0).getString("requestId");assertThrows(SecurityException.class,()->module.checkHostTaskAuthority(requestId));
        }finally{module.close();}
    }
    @Test public void boundedOriginalWindowOnlyReturnsExactSourceFactsAndMissingCursorIsAnExplicitGap()throws Exception {
        Context android=context();FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),ignored->{});ChannelsModule module=new ChannelsModule(context);
        try{
            String profile=module.handle("profile.save",new JSONObject().put("provider","webhook").put("name","Original bounded source").put("secret","bounded-original-native-secret-more-than-32").put("enabled",true)).getString("id");ChannelCore core=core(module);
            String first=core.accept(profile,message("anchor","Original owner source"),"trusted_relay").getJSONObject("event").getString("id");ChannelsModule.InboundFact anchor=module.originalInboundFact(first);
            String other=core.accept(profile,message("other-source","Other conversation body").put("senderId","other-actor"),"trusted_relay").getJSONObject("event").getString("id");
            String next=core.accept(profile,message("next-source","Actual next body"),"trusted_relay").getJSONObject("event").getString("id");
            ChannelsModule.InboundPage skipped=module.nextInboundFact(anchor,"",1);assertEquals(1,skipped.metadata().getInt("scannedRows"));assertTrue(skipped.facts().isEmpty());assertEquals(other,skipped.metadata().getString("nextEventId"));assertTrue(skipped.metadata().getBoolean("hasMore"));assertTrue(skipped.metadata().getBoolean("localWindowOnly"));
            ChannelsModule.InboundPage page=module.nextInboundFact(anchor,skipped.metadata().getString("nextEventId"),100);assertEquals(1,page.facts().size());assertEquals(next,page.facts().get(0).metadata().getString("eventId"));assertFalse(page.metadata().getBoolean("hasMore"));assertFalse(page.metadata().getBoolean("gap"));assertFalse(page.metadata().toString().contains("Actual next body"));page.metadata().put("nextEventId","forged");page.checkCurrent();assertEquals(next,page.metadata().getString("nextEventId"));assertThrows(UnsupportedOperationException.class,()->page.facts().clear());assertThrows(IllegalArgumentException.class,()->module.nextInboundFact(anchor,"",101));
            ChannelsModule.InboundPage gap=module.nextInboundFact(anchor,UUID.randomUUID().toString(),100);assertEquals("retention_gap",gap.metadata().getString("status"));assertTrue(gap.metadata().getBoolean("gap"));assertTrue(gap.facts().isEmpty());assertEquals(0,gap.metadata().getInt("scannedRows"));
            for(int i=0;i<201;i++)core.accept(profile,message("trim-"+i,"Bounded unrelated original row").put("senderId","other-actor"),"trusted_relay");
            assertThrows(SecurityException.class,anchor::checkCurrent);ChannelsModule.InboundPage lostAnchor=module.nextInboundFact(anchor,"",100);assertEquals("retention_gap",lostAnchor.metadata().getString("status"));assertTrue(lostAnchor.facts().isEmpty());assertEquals(first,lostAnchor.metadata().getString("nextEventId"));assertEquals(0,lostAnchor.metadata().getInt("scannedRows"));lostAnchor.checkCurrent();
            JSONObject edited=core.profile(profile);edited.put("autoReply",false);module.handle("profile.save",edited);assertThrows(SecurityException.class,page::checkCurrent);assertThrows(SecurityException.class,()->module.nextInboundFact(anchor,"",100));
        }finally{module.close();}
    }

}
