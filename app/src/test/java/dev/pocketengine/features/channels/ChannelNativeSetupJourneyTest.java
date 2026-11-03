package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Original transient login, official-wire fixture, encrypted vault and original binding book.
 * No actual WeChat login, user account, QR scan or platform message is performed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ChannelNativeSetupJourneyTest {
    @Before public void key(){java.security.Security.addProvider(new java.security.Provider("ChannelSetupKeyStore","1","Explicit fixture"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void release(){java.security.Security.removeProvider("ChannelSetupKeyStore");}
    private static final class Fixture implements AutoCloseable {
        final FeatureContext context=new FeatureContext(RuntimeEnvironment.getApplication(),new SettingsStore(RuntimeEnvironment.getApplication()),new SecretVault(RuntimeEnvironment.getApplication()),ignored->{});
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final AtomicInteger starts=new AtomicInteger(),polls=new AtomicInteger(),updates=new AtomicInteger();final AtomicReference<String> loginState=new AtomicReference<>("wait");
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),pollEntered=new CountDownLatch(1),pollRelease=new CountDownLatch(1);volatile boolean holdStart,holdPoll;final String base;ChannelsModule module;
        Fixture()throws Exception {
            server.createContext("/",exchange->{try{String path=exchange.getRequestURI().getPath();exchange.getRequestBody().readAllBytes();JSONObject response;
                if(path.equals("/ilink/bot/get_bot_qrcode")){assertNull(exchange.getRequestHeaders().getFirst("Authorization"));starts.incrementAndGet();entered.countDown();if(holdStart)assertTrue(release.await(5,TimeUnit.SECONDS));response=new JSONObject().put("qrcode","fixture-native-code").put("qrcode_img_content","https://weixin.qq.com/fixture-only-local-qr");}
                else if(path.equals("/ilink/bot/get_qrcode_status")){assertNull(exchange.getRequestHeaders().getFirst("Authorization"));polls.incrementAndGet();pollEntered.countDown();if(holdPoll)assertTrue(pollRelease.await(5,TimeUnit.SECONDS));response=new JSONObject().put("status",loginState.get());if(loginState.get().equals("confirmed"))response.put("bot_token","fixture-only-native-bot-token").put("ilink_bot_id","fixture-bot@im.bot").put("baseurl",WeixinClient.DEFAULT_BASE);}
                else if(path.equals("/ilink/bot/getupdates")){assertEquals("Bearer fixture-only-native-bot-token",exchange.getRequestHeaders().getFirst("Authorization"));int n=updates.incrementAndGet();response=new JSONObject().put("ret",0).put("get_updates_buf","cursor-"+n).put("msgs",new JSONArray().put(new JSONObject().put("message_id",String.valueOf(n)).put("from_user_id","actual-native-peer@im.wechat").put("message_type",1).put("message_state",2).put("create_time_ms",System.currentTimeMillis()).put("context_token","fixture-context-"+n).put("item_list",new JSONArray().put(new JSONObject().put("type",1).put("text_item",new JSONObject().put("text","Native binding source "+n))))));}
                else throw new AssertionError("unexpected setup fixture route "+path);
                byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}});server.start();base="http://127.0.0.1:"+server.getAddress().getPort();open();
        }
        void open()throws Exception {module=new ChannelsModule(context,new ChannelHttp(base),new QqGatewayClient(true),new WeixinClient(base));}
        JSONObject call(String command,String id)throws Exception {return module.handle("weixin.login."+command,new JSONObject().put("loginId",id).put("name","Native original login"));}
        String complete(String id)throws Exception {call("start",id);loginState.set("confirmed");return call("poll",id).getJSONObject("profile").getString("id");}
        @Override public void close(){release.countDown();pollRelease.countDown();module.close();server.stop(0);}
    }
    @Test public void nativeOriginalLoginCanBeInspectedAndConfirmedWithoutStartingReceiversBindingOrRepeatingCreation()throws Exception {
        try(Fixture f=new Fixture()){
            String id=UUID.randomUUID().toString();JSONObject started=f.call("start",id);assertEquals(id,started.getString("loginId"));assertEquals("awaiting_scan",started.getString("status"));assertTrue(started.has("qrcodeContent"));assertEquals(1,f.starts.get());
            assertEquals(started.getString("qrcodeContent"),f.call("inspect",id).getString("qrcodeContent"));f.call("start",id);assertEquals(1,f.starts.get());assertEquals(0,f.polls.get());assertThrows(SecurityException.class,()->f.module.handle("weixin.login.start",new JSONObject().put("loginId",id).put("name","Another configuration")));
            assertEquals("wait",f.call("poll",id).getString("status"));f.loginState.set("confirmed");JSONObject confirmed=f.call("poll",id);String profile=confirmed.getJSONObject("profile").getString("id");assertFalse(confirmed.getBoolean("ownerBound"));assertFalse(confirmed.toString().contains("fixture-only-native-bot-token"));assertFalse(confirmed.getJSONObject("profile").getBoolean("autoReply"));assertEquals(0,f.module.snapshot().getJSONArray("bindings").length());
            JSONObject known=f.call("inspect",id);assertEquals("confirmed",known.getString("status"));assertEquals(profile,known.getJSONObject("profile").getString("id"));f.call("poll",id);assertEquals(2,f.polls.get());assertEquals(1,f.starts.get());assertFalse(f.call("cancel",id).getBoolean("cancelled"));assertEquals(1,f.module.snapshot().getJSONArray("profiles").length());assertEquals(0,f.module.snapshot().getJSONObject("weixin").getJSONArray("connections").length());
        }
    }
    @Test public void cancellingAnInFlightOriginalStartFencesItsResponseAndNeverSavesAnAccount()throws Exception {
        try(Fixture f=new Fixture()){
            f.holdStart=true;String id=UUID.randomUUID().toString();ExecutorService worker=Executors.newSingleThreadExecutor();try{
                Future<JSONObject> future=worker.submit(()->f.call("start",id));assertTrue(f.entered.await(5,TimeUnit.SECONDS));assertEquals("starting",f.call("inspect",id).getString("status"));JSONObject cancelled=f.call("cancel",id);assertTrue(cancelled.getBoolean("localStartFence"));assertEquals("cancelled",cancelled.getString("status"));f.release.countDown();assertThrows(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS));
                assertEquals("cancelled",f.call("inspect",id).getString("status"));assertEquals("cancelled",f.call("start",id).getString("status"));assertEquals(1,f.starts.get());assertEquals(0,f.module.snapshot().getJSONArray("profiles").length());
            }finally{f.release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }
    @Test public void cancellationOvertakingQueuedStartBlocksThatExactIdButDoesNotCancelAnExplicitNewLogin()throws Exception {
        try(Fixture f=new Fixture()){
            String closed=UUID.randomUUID().toString(),fresh=UUID.randomUUID().toString();assertTrue(f.call("cancel",closed).getBoolean("localStartFence"));assertEquals("cancelled",f.call("start",closed).getString("status"));assertEquals(0,f.starts.get());
            assertEquals("awaiting_scan",f.call("start",fresh).getString("status"));f.call("cancel",closed);assertEquals("awaiting_scan",f.call("inspect",fresh).getString("status"));assertEquals(1,f.starts.get());
        }
    }
    @Test public void lostTransientLoginAfterModuleRecreationIsOnlyUnknownAndDoesNotCreateOrPollAnotherChallenge()throws Exception {
        try(Fixture f=new Fixture()){
            String id=UUID.randomUUID().toString();f.call("start",id);f.module.close();f.open();assertEquals("unknown",f.call("inspect",id).getString("status"));assertEquals("unknown",f.call("poll",id).getString("status"));assertEquals(1,f.starts.get());assertEquals(0,f.polls.get());assertEquals(0,f.module.snapshot().getJSONArray("profiles").length());
        }
    }
    @Test public void bindingRequiresOriginalCurrentAuthenticatedDmNotOldTransportAndDoesNotEnableTasks()throws Exception {
        try(Fixture f=new Fixture()){
            String profile=f.complete(UUID.randomUUID().toString());f.module.handle("weixin.poll",new JSONObject().put("id",profile));JSONObject first=f.module.snapshot().getJSONArray("inbox").getJSONObject(0);
            f.module.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",first.getString("id")).put("confirmed",true));assertFalse(f.module.snapshot().getJSONArray("bindings").getJSONObject(0).getBoolean("ownerTasksEnabled"));
            JSONObject saved=f.module.snapshot().getJSONArray("profiles").getJSONObject(0);saved.put("name","new transport revision");f.module.handle("profile.save",saved);
            assertThrows(SecurityException.class,()->f.module.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",first.getString("id")).put("confirmed",true)));
            f.module.handle("weixin.poll",new JSONObject().put("id",profile));JSONObject second=f.module.snapshot().getJSONArray("inbox").getJSONObject(1);assertEquals("weixin_session",second.getString("authenticatedBy"));f.module.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",second.getString("id")).put("confirmed",true));assertEquals("actual-native-peer@im.wechat",f.module.snapshot().getJSONArray("bindings").getJSONObject(0).getString("senderId"));assertFalse(f.module.snapshot().getJSONArray("bindings").getJSONObject(0).getBoolean("ownerTasksEnabled"));
        }
    }
    @Test public void staleProfileSaveAndRemovalCannotTouchTheNewCredentialOrConfiguration()throws Exception {
        try(Fixture f=new Fixture()){
            String id=f.complete(UUID.randomUUID().toString());JSONObject old=f.module.snapshot().getJSONArray("profiles").getJSONObject(0);
            JSONObject changed=f.module.handle("profile.save",new JSONObject(old.toString()).put("expectedTransportEpoch",old.getString("transportEpoch")).put("name","newer native configuration"));
            String credential=f.context.secrets.get("channels."+id+".secret");
            assertThrows(SecurityException.class,()->f.module.handle("profile.save",new JSONObject(old.toString()).put("expectedTransportEpoch",old.getString("transportEpoch")).put("secret","stale-fixture-secret")));
            assertThrows(SecurityException.class,()->f.module.handle("profile.remove",new JSONObject().put("id",id).put("expectedTransportEpoch",old.getString("transportEpoch"))));
            assertEquals(credential,f.context.secrets.get("channels."+id+".secret"));
            JSONObject actual=f.module.snapshot().getJSONArray("profiles").getJSONObject(0);assertEquals(changed.getString("transportEpoch"),actual.getString("transportEpoch"));assertEquals("newer native configuration",actual.getString("name"));
        }
    }
    @Test public void invalidRetargetingIsRejectedBeforeChangingTheOriginalVaultSlot()throws Exception {
        try(Fixture f=new Fixture()){
            String id=f.complete(UUID.randomUUID().toString());JSONObject original=f.module.snapshot().getJSONArray("profiles").getJSONObject(0);
            String credential=f.context.secrets.get("channels."+id+".secret");
            assertThrows(IllegalArgumentException.class,()->f.module.handle("profile.save",new JSONObject(original.toString()).put("expectedTransportEpoch",original.getString("transportEpoch")).put("appId","different-bot").put("secret","must-not-be-written")));
            assertEquals(credential,f.context.secrets.get("channels."+id+".secret"));assertEquals(original.getString("transportEpoch"),f.module.snapshot().getJSONArray("profiles").getJSONObject(0).getString("transportEpoch"));
        }
    }
    @Test public void anOldOwnerReviewCannotUnbindTheNewOwnerOrResetItsGrant()throws Exception {
        try(Fixture f=new Fixture()){
            String id=f.complete(UUID.randomUUID().toString());f.module.handle("weixin.poll",new JSONObject().put("id",id));JSONObject snapshot=f.module.snapshot(),profile=snapshot.getJSONArray("profiles").getJSONObject(0),event=snapshot.getJSONArray("inbox").getJSONObject(0);
            JSONObject binding=f.module.handle("owner.bind",new JSONObject().put("profileId",id).put("verifiedEventId",event.getString("id")).put("confirmed",true));
            JSONObject old=new JSONObject().put("profileId",id).put("expectedTransportEpoch",profile.getString("transportEpoch")).put("expectedBindingEpoch",binding.getString("bindingEpoch")).put("expectedTaskGrantEpoch",binding.getString("taskGrantEpoch"));
            JSONObject next=f.module.handle("owner.bind",new JSONObject(old.toString()).put("verifiedEventId",event.getString("id")).put("confirmed",true));
            assertThrows(SecurityException.class,()->f.module.handle("owner.unbind",old));
            assertThrows(SecurityException.class,()->f.module.handle("owner.tasks.set",new JSONObject(old.toString()).put("confirmed",true).put("enabled",true)));
            JSONObject actual=f.module.snapshot().getJSONArray("bindings").getJSONObject(0);assertEquals(next.getString("bindingEpoch"),actual.getString("bindingEpoch"));assertFalse(actual.getBoolean("ownerTasksEnabled"));
        }
    }
    @Test public void officialConfirmationCapturedBeforeALocalEditCannotOverwriteTheNewConfiguration()throws Exception {
        try(Fixture f=new Fixture()){
            String id=f.complete(UUID.randomUUID().toString()),login=UUID.randomUUID().toString();f.call("start",login);
            JSONObject original=f.module.snapshot().getJSONArray("profiles").getJSONObject(0);
            JSONObject changed=f.module.handle("profile.save",new JSONObject(original.toString()).put("expectedTransportEpoch",original.getString("transportEpoch")).put("name","edited after requesting QR"));
            assertThrows(SecurityException.class,()->f.call("poll",login));
            assertEquals("failed",f.call("inspect",login).getString("status"));int polls=f.polls.get();assertEquals("failed",f.call("poll",login).getString("status"));assertEquals(polls,f.polls.get());
            JSONObject actual=f.module.snapshot().getJSONArray("profiles").getJSONObject(0);assertEquals(id,actual.getString("id"));assertEquals(changed.getString("transportEpoch"),actual.getString("transportEpoch"));assertEquals("edited after requesting QR",actual.getString("name"));
        }
    }
    @Test public void configurationCanChangeWhileOfficialPollIsBlockedAndLateReceiptCannotReplaceIt()throws Exception {
        try(Fixture f=new Fixture()){
            String login=UUID.randomUUID().toString();f.call("start",login);f.loginState.set("confirmed");f.holdPoll=true;
            ExecutorService worker=Executors.newSingleThreadExecutor();try{
                Future<JSONObject> pending=worker.submit(()->f.call("poll",login));assertTrue(f.pollEntered.await(5,TimeUnit.SECONDS));
                JSONObject created=f.module.handle("profile.save",new JSONObject().put("name","new native owner configuration").put("provider","weixin").put("appId","fixture-bot@im.bot").put("secret","newer-native-fixture-credential").put("enabled",true));
                f.pollRelease.countDown();assertThrows(ExecutionException.class,()->pending.get(5,TimeUnit.SECONDS));
                assertEquals("failed",f.call("inspect",login).getString("status"));JSONObject actual=f.module.snapshot().getJSONArray("profiles").getJSONObject(0);
                assertEquals(created.getString("id"),actual.getString("id"));assertEquals(created.getString("transportEpoch"),actual.getString("transportEpoch"));assertEquals("newer-native-fixture-credential",f.context.secrets.get("channels."+actual.getString("id")+".secret"));
            }finally{f.pollRelease.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }
}
