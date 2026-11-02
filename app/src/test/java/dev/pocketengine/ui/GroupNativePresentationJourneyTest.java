package dev.pocketengine.ui;

import static org.junit.Assert.*;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.channels.ChannelsModule;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** Actual App/Activity/Controller, authenticated loopback relay and SQLite; model/platform HTTP are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public class GroupNativePresentationJourneyTest {
    @Before public void keys(){new GroupNativeApplicationControllerTest().keys();}
    private static JSONObject j(Object... pairs)throws Exception{JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    @Test public void originalTaskProgressAndCompletionReachNativeOwnerReceiptsWithoutCreatingTaskOrResending() throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        AtomicInteger models=new AtomicInteger(),sent=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/v1/chat/completions",exchange->{
            try {
                models.incrementAndGet();exchange.getRequestBody().readAllBytes();entered.countDown();
                if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("fixture model latch timed out");
                byte[] bytes=j("choices",new JSONArray().put(j("message",j("role","assistant","content","Fixture public answer from original runner"),"finish_reason","stop"))).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failed){throw new IllegalStateException(failed);}finally{exchange.close();}
        });
        http.createContext("/reply",exchange->{try{exchange.getRequestBody().readAllBytes();sent.incrementAndGet();byte[] bytes=j("id","original-fixture-message").toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception failed){throw new IllegalStateException(failed);}finally{exchange.close();}});
        http.start();String base="http://127.0.0.1:"+http.getAddress().getPort(),secret="0123456789abcdef0123456789abcdef";ActivityController<ComposeActivity> activity=null;
        try {
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl",base+"/v1").putString("provider","openai_chat").putString("model","explicit-fixture-model").putInt("maxOutputTokens",256).putInt("maxSteps",8).putBoolean("sendScreenshots",false).commit();
            ChannelsModule channels=(ChannelsModule)app.features.module("channels");
            String profile=channels.handle("profile.save",j("name","Projection fixture relay","provider","webhook","enabled",true,"replyUrl",base+"/reply","secret",secret)).getString("id");channels.handle("server.start",j("port",0));
            activity=Robolectric.buildActivity(ComposeActivity.class).create().start().resume();java.lang.reflect.Field field=ComposeActivity.class.getDeclaredField("controller");field.setAccessible(true);PocketWorldController controller=(PocketWorldController)field.get(activity.get());controller.enterOwnerDomain("groups");
            JSONObject group=GroupNativeApplicationControllerTest.request(controller,"group.create",j("name","Original task projection","provider","webhook","accountId",profile,"roomId","projection-room"));
            group=GroupNativeApplicationControllerTest.request(controller,"member.set",j("id",group.getString("id"),"revision",group.getLong("revision"),"senderId","projection-member","displayName","Fixture member","role","member","enabled",true));
            JSONObject quota=group.getJSONObject("quota").put("dailyTokens",120000).put("maxTokensPerTask",60000);
            GroupNativeApplicationControllerTest.request(controller,"policy.set",j("id",group.getString("id"),"revision",group.getLong("revision"),"enabled",true,"visibility","members","quota",quota,"grants",new JSONArray().put(j("capability","public.answer","resource","public","allowGroupAudience",true,"expiresAt",System.currentTimeMillis()+3600000))));
            byte[] body=j("messageId","original-projection-input","senderId","projection-member","roomId","projection-room","kind","group","text","/task --tokens=60000 Explain this public fact","sentAt",System.currentTimeMillis()).toString().getBytes(StandardCharsets.UTF_8);
            int port=channels.snapshot().getJSONObject("server").getInt("port");HttpURLConnection inbound=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/events/"+profile).openConnection();inbound.setRequestMethod("POST");inbound.setDoOutput(true);inbound.setRequestProperty("Authorization","Bearer "+secret);inbound.setRequestProperty("Content-Type","application/json");inbound.setFixedLengthStreamingMode(body.length);try(var output=inbound.getOutputStream()){output.write(body);}assertEquals(200,inbound.getResponseCode());inbound.getInputStream().close();inbound.disconnect();
            assertTrue(entered.await(5,TimeUnit.SECONDS));JSONObject task=app.tasks.list().getJSONObject(0);String id=task.getString("id"),requestId=task.getJSONObject("origin").getString("requestId");
            JSONObject running=GroupNativeApplicationControllerTest.request(controller,"task.inspect",j("requestId",requestId)),projection=running.getJSONObject("presentation");
            assertEquals("running",projection.getString("status"));assertEquals(id,projection.getString("taskId"));assertEquals(running.getJSONObject("task").getLong("revision"),projection.getLong("taskRevision"));assertEquals("planning",projection.getJSONObject("progress").getString("phase"));assertFalse(projection.getJSONObject("progress").has("totalSteps"));assertEquals(0,sent.get());
            release.countDown();GroupNativeApplicationControllerTest.await(()->app.tasks.get(id,false).optString("status").equals("completed")&&sent.get()==1);
            JSONObject done=GroupNativeApplicationControllerTest.request(controller,"task.inspect",j("requestId",requestId));assertEquals("completed",done.getJSONObject("presentation").getString("status"));assertEquals(done.getJSONObject("task").getLong("revision"),done.getJSONObject("presentation").getLong("taskRevision"));
            String inspectId=controller.getState().ownerResponses("groups").get("task.inspect").getString("requestId");JSONObject snapshot=GroupNativeApplicationControllerTest.request(controller,"snapshot",new JSONObject());assertEquals(id,snapshot.getJSONObject("taskPresentations").getJSONObject(requestId).getString("taskId"));assertTrue(controller.getState().getGroupOwnerReceipts().containsKey(inspectId));
            assertEquals(1,models.get());assertEquals(1,sent.get());assertEquals(1,app.tasks.list().length());
        } finally {release.countDown();if(activity!=null)activity.pause().stop().destroy();app.features.close();http.stop(0);java.security.Security.removeProvider("GroupMountedKeyStore");}
    }
}
