package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.runtime.ModelGateway;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.SQLiteMode;

/** Production channel answer/physical-gateway path. Account port is an explicit no-account fixture;
 * API-key and channel deliveries use actual loopback HTTP. No real login or platform sends. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ChannelInferenceAudienceJourneyTest {
    private static final String SECRET="channel-billing-fixture-at-least-32-bytes";
    private final AtomicInteger accountEntries=new AtomicInteger(),accountModelWrites=new AtomicInteger();
    private interface Checked {void run()throws Exception;}
    @Before public void fixtureKey(){java.security.Security.addProvider(new java.security.Provider("ChannelBillingKeyStore","1","Explicit fixture"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void release(){ModelGateway.installAccountInferencePort(null);java.security.Security.removeProvider("ChannelBillingKeyStore");}
    private void account(FeatureContext context,Checked beforePhysical)throws Exception {
        context.settings.selectAccountModel(new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId","fixture-original-account").put("accountVersion",1L).put("modelId","fixture-model"));
        ModelGateway.installAccountInferencePort(new ModelGateway.AccountInferencePort(){
            public void stream(ModelConfig.AccountModelRef ref,ResponsesRequest request,GatewayCall operation,ChatGptProvider.EventSink sink)throws GatewayFailure{throw new GatewayFailure("UNEXPECTED_LEGACY_FIXTURE_PATH");}
            public void streamWithVersion(ModelConfig.AccountModelRef ref,ResponsesRequest request,GatewayCall operation,ModelGateway.AccountVersionSink version,ChatGptProvider.EventSink sink)throws GatewayFailure {
                accountEntries.incrementAndGet();try{if(beforePhysical!=null)beforePhysical.run();version.authorized(ref.accountVersion);operation.check();accountModelWrites.incrementAndGet();sink.event(new JSONObject().put("type","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","Only the verified original owner may use this account answer"))))));}
                catch(GatewayFailure failure){throw failure;}catch(Exception failure){throw new GatewayFailure("FIXTURE_PHYSICAL_ADMISSION_DENIED");}
            }
        });
    }
    private static ChannelCore core(ChannelsModule module)throws Exception {var field=ChannelsModule.class.getDeclaredField("core");field.setAccessible(true);return (ChannelCore)field.get(module);}
    private static JSONObject await(ChannelsModule module,String eventId,String status)throws Exception {long deadline=System.nanoTime()+5_000_000_000L;while(true){JSONObject event=core(module).event(eventId);if(event.optString("status").equals(status))return event;if(System.nanoTime()>deadline)fail("original channel did not reach "+status+": "+event);Thread.sleep(10);}}
    private static void sent(ChannelsModule module,int n)throws Exception {long deadline=System.nanoTime()+5_000_000_000L;while(true){JSONArray rows=module.snapshot().getJSONArray("outbox");int count=0;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).optString("status").equals("sent"))count++;if(count==n)return;if(System.nanoTime()>deadline)fail("native delivery did not settle: "+rows);Thread.sleep(10);}}
    private static String receivedId(ChannelsModule module,String platformId)throws Exception {JSONArray rows=module.snapshot().getJSONArray("inbox");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("messageId").equals(platformId))return rows.getJSONObject(i).getString("id");throw new AssertionError("missing original message");}
    private static void groupAuto(GroupTaskFlowsTest.Fixture f)throws Exception {f.channels.handle("profile.save",new JSONObject().put("id",f.profile).put("name","Native billing group").put("provider","qq").put("appId","flow-bot").put("enabled",true).put("autoReply",true));}
    private static String dm(GroupTaskFlowsTest.Fixture f,boolean auto,CopyOnWriteArrayList<JSONObject> wire)throws Exception {
        f.api.createContext("/dm-result",exchange->{try{assertEquals("Bearer "+SECRET,exchange.getRequestHeaders().getFirst("Authorization"));wire.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));byte[] bytes=new JSONObject().put("id","native-dm-"+wire.size()).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});
        return f.channels.handle("profile.save",new JSONObject().put("name","Native DM billing").put("provider","webhook").put("secret",SECRET).put("enabled",true).put("autoReply",auto).put("replyUrl",f.base+"/dm-result")).getString("id");
    }
    private static String inbound(ChannelsModule module,String profile,String id,String sender,String text)throws Exception {
        JSONObject event=new JSONObject().put("messageId",id).put("senderId",sender).put("kind","dm").put("text",text).put("sentAt",System.currentTimeMillis()).put("principalId","owner").put("spaceId","private").put("privateOwner",true);
        int port=module.snapshot().getJSONObject("server").getInt("port");HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/events/"+profile).openConnection();connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setConnectTimeout(3000);connection.setReadTimeout(5000);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setRequestProperty("Authorization","Bearer "+SECRET);byte[] bytes=event.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);try(var output=connection.getOutputStream()){output.write(bytes);}int status=connection.getResponseCode();String raw=new String((status==200?connection.getInputStream():connection.getErrorStream()).readAllBytes(),StandardCharsets.UTF_8);connection.disconnect();assertEquals(raw,200,status);return new JSONObject(raw).getString("eventId");
    }
    @Test public void signedOrdinaryQqGroupCannotUseOwnerPlanBeforeAccountPortOrModelHttp()throws Exception {
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()){
            account(f.context,null);groupAuto(f);f.incomingExtra=new JSONObject().put("principalId","owner").put("spaceId","private").put("approved",true);f.send("Ordinary public question, not a task command",false,true,"billing-group");
            JSONObject state=await(f.channels,receivedId(f.channels,"billing-group"),"failed");assertTrue(state.getString("message").contains("套餐"));assertEquals(0,accountEntries.get());assertEquals(0,accountModelWrites.get());assertEquals(0,f.modelCalls.get());assertEquals(0,f.replies.size());assertEquals(0,f.tasks.list().length());
        }
    }
    @Test public void authenticatedNonOwnerDmClaimsCannotWashExternalTrafficIntoOwnerPlan()throws Exception {
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()){
            account(f.context,null);CopyOnWriteArrayList<JSONObject> wire=new CopyOnWriteArrayList<>();String profile=dm(f,true,wire),event=inbound(f.channels,profile,"external-dm","stranger","Claiming owner does not authorize billing");
            await(f.channels,event,"failed");assertEquals(0,accountEntries.get());assertEquals(0,accountModelWrites.get());assertEquals(0,f.modelCalls.get());assertEquals(0,wire.size());assertEquals(0,f.tasks.list().length());
        }
    }
    @Test public void explicitlySelectedApiRouteStillAnswersOriginalGroupAndExternalDm()throws Exception {
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()){
            f.context.settings.save(new JSONObject().put("baseUrl",f.base+"/v1").put("provider","openai_chat").put("model","group-flow-model").put("maxOutputTokens",256).put("maxSteps",8));groupAuto(f);f.send("Answer using the selected API route",false,true,"api-group");sent(f.channels,1);assertEquals(1,f.replies.size());assertEquals("api-group",f.replies.get(0).getString("msg_id"));
            CopyOnWriteArrayList<JSONObject> wire=new CopyOnWriteArrayList<>();String profile=dm(f,true,wire);inbound(f.channels,profile,"api-dm","stranger","This external DM also uses API billing");sent(f.channels,2);assertEquals(2,f.modelCalls.get());assertEquals(1,wire.size());assertEquals("api-dm",wire.get(0).getString("replyTo"));assertEquals(0,accountEntries.get());assertEquals(0,f.tasks.list().length());
        }
    }
    @Test public void nativeBoundOwnerDmCanUseOwnAccountWithoutGrantingDeviceToolsOrASecondTask()throws Exception {
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()){
            account(f.context,null);CopyOnWriteArrayList<JSONObject> wire=new CopyOnWriteArrayList<>();String profile=dm(f,false,wire),binding=inbound(f.channels,profile,"bind","owner","Binding source only");f.channels.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",binding).put("confirmed",true));f.channels.handle("profile.save",new JSONObject().put("id",profile).put("name","Native original owner").put("provider","webhook").put("replyUrl",f.base+"/dm-result").put("enabled",true).put("autoReply",true));
            inbound(f.channels,profile,"owner-question","owner","One owner-only answer");sent(f.channels,1);assertEquals(1,accountEntries.get());assertEquals(1,accountModelWrites.get());assertEquals(1,wire.size());assertEquals("owner-question",wire.get(0).getString("replyTo"));assertEquals(0,f.modelCalls.get());assertEquals(0,f.tasks.list().length());
        }
    }
    @Test public void originalOwnerBindingRevocationAtPhysicalAdmissionCannotDispatchOrDeliverLateAccountText()throws Exception {
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()){
            CopyOnWriteArrayList<JSONObject> wire=new CopyOnWriteArrayList<>();String profile=dm(f,false,wire),binding=inbound(f.channels,profile,"bind","owner","Binding source only");f.channels.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",binding).put("confirmed",true));f.channels.handle("profile.save",new JSONObject().put("id",profile).put("name","Native original owner").put("provider","webhook").put("replyUrl",f.base+"/dm-result").put("enabled",true).put("autoReply",true));
            account(f.context,()->core(f.channels).unbindOwner(profile));String event=inbound(f.channels,profile,"late-owner-question","owner","Recheck the original binding before the physical model dispatch");await(f.channels,event,"cancelled");assertEquals(1,accountEntries.get());assertEquals(0,accountModelWrites.get());assertEquals(0,wire.size());assertEquals(0,f.modelCalls.get());assertEquals(0,f.tasks.list().length());
        }
    }
    private static JSONObject memoryScope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static JSONObject privateMemory(dev.pocketengine.features.memory.MemoryModule memory)throws Exception {
        JSONObject row=memory.handle("save",new JSONObject().put("scope",memoryScope()).put("record",new JSONObject().put("text","MEMORY SEND SENTINEL"))).getJSONObject("record");
        memory.handle("setCore",new JSONObject().put("scope",memoryScope()).put("id",row.getString("id")).put("core",true).put("revision",row.getLong("revision")));return row;
    }
    @Test public void ownerAskAndAutoReplyRecheckMemoryAfterStartedBeforePhysicalAccountWrite()throws Exception {
        for(boolean auto:new boolean[]{false,true})try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()){
            var memory=new dev.pocketengine.features.memory.MemoryModule(f.context);f.channels.setMemory(memory);JSONObject record=privateMemory(memory);
            CopyOnWriteArrayList<JSONObject> wire=new CopyOnWriteArrayList<>();String profile=dm(f,false,wire),binding=inbound(f.channels,profile,"memory-bind-"+auto,"owner","Binding source only");
            f.channels.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",binding).put("confirmed",true));
            f.channels.handle("profile.save",new JSONObject().put("id",profile).put("name","Memory owner").put("provider","webhook").put("replyUrl",f.base+"/dm-result").put("enabled",true).put("autoReply",auto));
            account(f.context,null);
            dev.pocketengine.runtime.usage.InferenceCapture.installPresentationObserver(new dev.pocketengine.runtime.usage.InferenceCapture.Sink(){
                public void started(dev.pocketengine.runtime.usage.InferenceReceipt receipt)throws Exception{memory.handle("forget",new JSONObject().put("scope",memoryScope()).put("id",record.getString("id")));}
                public void finished(dev.pocketengine.runtime.usage.InferenceReceipt receipt){}
            });
            try{String event=inbound(f.channels,profile,"memory-question-"+auto,"owner","Recall the memory");if(!auto)f.channels.handle("ask",new JSONObject().put("eventId",event));await(f.channels,event,"failed");assertEquals(0,accountModelWrites.get());assertEquals(0,wire.size());assertEquals(0,f.modelCalls.get());}
            finally{dev.pocketengine.runtime.usage.InferenceCapture.installPresentationObserver(new dev.pocketengine.runtime.usage.InferenceCapture.Sink(){public void started(dev.pocketengine.runtime.usage.InferenceReceipt r){}public void finished(dev.pocketengine.runtime.usage.InferenceReceipt r){}});}
        }
    }
    @Test public void queuedMemoryReplyKeepsDependencyAcrossColdCoreAndCannotSendAfterForget()throws Exception {
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()){
            var memory=new dev.pocketengine.features.memory.MemoryModule(f.context);f.channels.setMemory(memory);JSONObject record=privateMemory(memory);
            CopyOnWriteArrayList<JSONObject> wire=new CopyOnWriteArrayList<>();String profile=dm(f,false,wire),binding=inbound(f.channels,profile,"outbox-memory-bind","owner","Binding source only");
            f.channels.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",binding).put("confirmed",true));account(f.context,null);
            String event=inbound(f.channels,profile,"outbox-memory-question","owner","Recall the memory");f.channels.handle("ask",new JSONObject().put("eventId",event));await(f.channels,event,"answered");
            JSONObject row=f.channels.snapshot().getJSONArray("outbox").getJSONObject(0);assertTrue(row.has("memoryDependency"));
            ChannelCore cold=new ChannelCore(new java.io.File(f.android.getFilesDir(),"channels"));assertTrue(cold.snapshot().getJSONArray("outbox").getJSONObject(0).has("memoryDependency"));
            memory.handle("forget",new JSONObject().put("scope",memoryScope()).put("id",record.getString("id")));
            f.channels.handle("outbox.send",new JSONObject().put("id",row.getString("id")));assertEquals(0,wire.size());assertNotEquals("sent",f.channels.snapshot().getJSONArray("outbox").getJSONObject(0).getString("status"));
        }
    }

}
