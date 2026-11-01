package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Actual UI merge helper and native poll state fence; no external account or UI/device assertion. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ChannelLoginStateJourneyTest {
    private static JSONObject row(String id,String status)throws Exception{return new JSONObject().put("loginId",id).put("status",status).put("qrcodeContent","fixture-qr");}
    @Test public void failedNativeAttemptCannotBeRevivedByCachedStartInspectOrPoll()throws Exception {
        String id=UUID.randomUUID().toString();JSONObject current=ChannelLoginState.merge(row(id,"awaiting_scan"),row(id,"failed"),id);
        for(String status:new String[]{"awaiting_scan","starting","wait","confirmed"})current=ChannelLoginState.merge(current,row(id,status),id);
        assertEquals("failed",current.getString("status"));assertFalse(current.has("qrcodeContent"));
    }
    @Test public void missingOriginalProcessIsQueryOnlyAndNewStartIsNotRacedByAutomaticInspect()throws Exception {
        String id=UUID.randomUUID().toString();assertFalse(ChannelLoginState.mayInspect(null,true));assertTrue(ChannelLoginState.mayInspect(null,false));
        JSONObject current=ChannelLoginState.merge(null,row(id,"unknown"),id);assertTrue(ChannelLoginState.mayInspect(current,true));
        current=ChannelLoginState.merge(current,row(id,"awaiting_scan"),id);current=ChannelLoginState.merge(current,row(id,"confirmed"),id);
        assertEquals("unknown",current.getString("status"));assertFalse(current.has("qrcodeContent"));
        String fresh=UUID.randomUUID().toString();assertEquals("awaiting_scan",ChannelLoginState.merge(null,row(fresh,"awaiting_scan"),fresh).getString("status"));
    }
    @Test public void terminalOriginalReceiptAndForeignLoginAreDefensiveAndCannotReplaceEachOther()throws Exception {
        String id=UUID.randomUUID().toString();JSONObject original=row(id,"confirmed").put("profile",new JSONObject().put("id","original-profile"));
        JSONObject merged=ChannelLoginState.merge(original,row(id,"wait"),id);assertEquals("confirmed",merged.getString("status"));assertFalse(merged.has("qrcodeContent"));assertTrue(original.has("qrcodeContent"));
        JSONObject foreign=ChannelLoginState.merge(merged,row(UUID.randomUUID().toString(),"confirmed"),id);assertEquals("original-profile",foreign.getJSONObject("profile").getString("id"));foreign.put("status","tampered");assertEquals("confirmed",merged.getString("status"));
    }
    @Test public void cancelBetweenPollWireReceiptAndNativeStateCommitCannotWriteBackAWaitStatus()throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<Thread> polling=new AtomicReference<>();ExecutorService worker=Executors.newSingleThreadExecutor();ChannelsModule module=null;
        try {
            server.createContext("/",exchange->{try{exchange.getRequestBody().readAllBytes();JSONObject response;if(exchange.getRequestURI().getPath().endsWith("get_bot_qrcode"))response=new JSONObject().put("qrcode","fixture-code").put("qrcode_img_content","fixture-original-qr");else{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));response=new JSONObject().put("status","wait");}byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();FeatureContext context=new FeatureContext(RuntimeEnvironment.getApplication(),new SettingsStore(RuntimeEnvironment.getApplication()),new SecretVault(RuntimeEnvironment.getApplication()),ignored->{});module=new ChannelsModule(context,new ChannelHttp(base),new QqGatewayClient(true),new WeixinClient(base));final ChannelsModule original=module;String id=UUID.randomUUID().toString();JSONObject input=new JSONObject().put("loginId",id).put("name","Original fenced poll");module.handle("weixin.login.start",input);
            java.lang.reflect.Field adapterField=ChannelsModule.class.getDeclaredField("weixin");adapterField.setAccessible(true);Object adapter=adapterField.get(module);java.lang.reflect.Field loginsField=WeixinAdapter.class.getDeclaredField("logins");loginsField.setAccessible(true);Object login=((Map<?,?>)loginsField.get(adapter)).get(id);assertNotNull(login);
            Future<JSONObject> result=worker.submit(()->{polling.set(Thread.currentThread());return original.handle("weixin.login.poll",input);});assertTrue(entered.await(5,TimeUnit.SECONDS));
            synchronized(login){release.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!result.isDone()&&polling.get().getState()!=Thread.State.BLOCKED&&System.nanoTime()<deadline)Thread.sleep(2);assertFalse("poll committed outside original login lock",result.isDone());assertEquals(Thread.State.BLOCKED,polling.get().getState());assertEquals("cancelled",module.handle("weixin.login.cancel",input).getString("status"));}
            assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS));JSONObject current=module.handle("weixin.login.inspect",input);assertEquals("cancelled",current.getString("status"));assertFalse(current.has("qrcodeContent"));
        }finally{release.countDown();if(module!=null)module.close();server.stop(0);worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
