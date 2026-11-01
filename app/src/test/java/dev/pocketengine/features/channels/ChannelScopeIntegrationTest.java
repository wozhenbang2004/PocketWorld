package dev.pocketengine.features.channels;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.memory.MemoryModule;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Native routing-to-model privacy check. Authenticated provider events and model replies are test fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ChannelScopeIntegrationTest {
    @Test public void nativeOwnerBindingSharesPrivateRecallOnlyWithTheVerifiedDm()throws Exception {
        Context android=RuntimeEnvironment.getApplication();CopyOnWriteArrayList<JSONObject> modelRequests=new CopyOnWriteArrayList<>(),events=new CopyOnWriteArrayList<>();
        CountDownLatch bindDone=new CountDownLatch(1),answersEnded=new CountDownLatch(3);HttpServer model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/",exchange->{try{
            modelRequests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] body="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"测试模型已返回；没有外部投递凭据。\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }catch(Exception failure){throw new java.io.IOException("测试模型端点处理失败",failure);}finally{exchange.close();}});model.start();
        try{
            android.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+model.getAddress().getPort()).putString("model","scope-test-model").commit();
            FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{events.add(event);JSONObject payload=event.optJSONObject("payload");if(payload!=null&&payload.optString("type").equals("answer_failed"))answersEnded.countDown();});
            MemoryModule memory=new MemoryModule(context);memory.handle("save",new JSONObject().put("record",new JSONObject().put("layer","preference").put("text","仅主人私聊可召回的咖啡偏好：无糖冷萃").put("source","测试内明确保存的私有记录")));
            // Fixtures cross the real transport authenticator and canonicalizer before reaching the
            // same private file that the native ChannelsModule restores. No provider account is used.
            ChannelCore ingress=new ChannelCore(new File(android.getFilesDir(),"channels"));String profile=ingress.saveProfile(new JSONObject().put("name","测试认证入口").put("provider","webhook").put("enabled",true)).getString("id");
            JSONObject binding=authenticated(ingress,profile,"bind","verified-owner","dm","");
            JSONObject own=authenticated(ingress,profile,"owner-question","verified-owner","dm","");
            JSONObject group=authenticated(ingress,profile,"group-question","verified-owner","group","room-1");
            JSONObject stranger=authenticated(ingress,profile,"stranger-question","external-user","dm","");
            ChannelsModule channels=new ChannelsModule(context);channels.setMemory(memory);AtomicInteger reconciliations=new AtomicInteger();
            channels.setMemorySynchronizer(scope->{assertEquals("owner",scope.getString("ownerId"));assertEquals("private",scope.getString("spaceId"));reconciliations.incrementAndGet();});
            try(FeatureHost host=new FeatureHost(event->{events.add(event);if(event.optString("requestId").equals("local-bind")){assertTrue(event.optBoolean("ok"));bindDone.countDown();}})){
                host.register(memory);host.register(channels);
                host.request(new JSONObject().put("feature","channels").put("command","owner.bind").put("requestId","local-bind").put("payload",new JSONObject().put("profileId",profile).put("verifiedEventId",binding.getString("id")).put("confirmed",true)));
                assertTrue(bindDone.await(3,TimeUnit.SECONDS));
                JSONObject privateRoute=channels.handle("event.get",new JSONObject().put("id",own.getString("id"))).getJSONObject("currentRoute");
                JSONObject groupRoute=channels.handle("event.get",new JSONObject().put("id",group.getString("id"))).getJSONObject("currentRoute");
                assertEquals("private",privateRoute.getString("spaceId"));assertTrue(privateRoute.getBoolean("privateOwner"));assertFalse(groupRoute.getBoolean("privateOwner"));assertNotEquals("private",groupRoute.getString("spaceId"));
                for(JSONObject event:new JSONObject[]{own,group,stranger})host.request(new JSONObject().put("feature","channels").put("command","ask").put("requestId",event.getString("id")).put("payload",new JSONObject().put("eventId",event.getString("id"))));
                assertTrue(answersEnded.await(6,TimeUnit.SECONDS));assertEquals(3,modelRequests.size());assertEquals(1,reconciliations.get());
                for(JSONObject request:modelRequests){String text=request.toString();boolean ownerQuestion=text.contains("咖啡问题-owner-question");assertEquals(ownerQuestion,text.contains("仅主人私聊可召回的咖啡偏好"));assertFalse(request.has("tools"));}
                // The test deliberately supplies no channel credential. A real model response alone
                // must not fabricate a sent draft, delivery receipt or successful external action.
                assertEquals(0,channels.snapshot().getJSONArray("outbox").length());
                assertTrue(events.stream().noneMatch(event->{JSONObject payload=event.optJSONObject("payload");return payload!=null&&payload.optString("type").equals("delivery_updated");}));
                assertEquals(1,new MemoryModule(context).handle("list",new JSONObject()).getJSONArray("records").length());
            }
        }finally{model.stop(0);}
    }
    @Test public void failedPrivateSourceReconciliationStopsTheModelRequest()throws Exception {
        Context android=RuntimeEnvironment.getApplication();CopyOnWriteArrayList<JSONObject> requests=new CopyOnWriteArrayList<>();CountDownLatch failed=new CountDownLatch(1);AtomicInteger reconciliations=new AtomicInteger();
        HttpServer model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);model.createContext("/",exchange->{requests.add(new JSONObject());exchange.sendResponseHeaders(500,0);exchange.close();});model.start();
        try{
            android.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+model.getAddress().getPort()).putString("model","source-failure-test").commit();
            FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{JSONObject payload=event.optJSONObject("payload");if(payload!=null&&payload.optString("type").equals("answer_failed"))failed.countDown();});
            MemoryModule memory=new MemoryModule(context);ChannelCore ingress=new ChannelCore(new File(android.getFilesDir(),"channels"));String profile=ingress.saveProfile(new JSONObject().put("name","来源对账失败测试入口").put("provider","webhook").put("enabled",true)).getString("id");
            JSONObject binding=authenticated(ingress,profile,"source-bind","verified-owner","dm",""),question=authenticated(ingress,profile,"source-question","verified-owner","dm","");ChannelsModule channels=new ChannelsModule(context);channels.setMemory(memory);
            channels.setMemorySynchronizer(scope->{assertEquals("owner",scope.getString("ownerId"));assertEquals("private",scope.getString("spaceId"));reconciliations.incrementAndGet();throw new java.io.IOException("fixture source persistence unavailable");});
            try{channels.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",binding.getString("id")).put("confirmed",true));channels.handle("ask",new JSONObject().put("eventId",question.getString("id")));assertTrue(failed.await(5,TimeUnit.SECONDS));assertEquals(1,reconciliations.get());assertEquals(0,requests.size());assertEquals(0,channels.snapshot().getJSONArray("outbox").length());assertEquals("failed",channels.handle("event.get",new JSONObject().put("id",question.getString("id"))).getString("status"));}
            finally{channels.close();}
        }finally{model.stop(0);}
    }
    @Test public void explicitCoreSurvivesLongOwnerQueriesAndRefreshesAfterCorrection()throws Exception {
        Context android=RuntimeEnvironment.getApplication();CopyOnWriteArrayList<JSONObject> requests=new CopyOnWriteArrayList<>();CountDownLatch firstFinished=new CountDownLatch(1),allFinished=new CountDownLatch(2);
        HttpServer model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/",exchange->{try{
            requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] response="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"来自测试端点的真实HTTP响应，不表示渠道已投递。\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        }finally{exchange.close();}});model.start();
        try{
            android.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+model.getAddress().getPort()).putString("model","core-scope-test").commit();
            FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{JSONObject payload=event.optJSONObject("payload");if(payload!=null&&payload.optString("type").equals("answer_failed")){firstFinished.countDown();allFinished.countDown();}});
            MemoryModule memory=new MemoryModule(context);JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");String oldCore="首版核心偏好：只喝无糖冷萃咖啡",newCore="修订核心偏好：改喝不加糖的热茶";
            JSONObject saved=memory.handle("save",new JSONObject().put("scope",scope).put("record",new JSONObject().put("layer","preference").put("text",oldCore).put("source","本机明确保存"))).getJSONObject("record");
            JSONObject selected=memory.handle("setCore",new JSONObject().put("scope",scope).put("id",saved.getString("id")).put("revision",saved.getInt("revision")).put("core",true)).getJSONObject("record");
            String firstQuery="第一轮：请检查这段工程日志。"+"构建完成后的调用顺序和文件布局需要逐段核对。".repeat(45),secondQuery="第二轮：继续检查另一段工程日志。"+"接口返回和项目目录之间的关联需要逐段核对。".repeat(45);
            assertTrue(firstQuery.length()>500);assertTrue(secondQuery.length()>500);
            ChannelCore ingress=new ChannelCore(new File(android.getFilesDir(),"channels"));String profile=ingress.saveProfile(new JSONObject().put("name","核心上下文测试入口").put("provider","webhook").put("enabled",true)).getString("id");
            JSONObject binding=authenticated(ingress,profile,"core-bind","verified-owner","dm",""),first=authenticated(ingress,profile,"long-first","verified-owner","dm","",firstQuery),second=authenticated(ingress,profile,"long-second","verified-owner","dm","",secondQuery);
            ChannelsModule channels=new ChannelsModule(context);channels.setMemory(memory);
            try{
                channels.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",binding.getString("id")).put("confirmed",true));
                channels.handle("ask",new JSONObject().put("eventId",first.getString("id")));assertTrue(firstFinished.await(5,TimeUnit.SECONDS));assertEquals(1,requests.size());
                JSONObject reference=referenceContaining(requests.get(0),oldCore);assertNotNull("long query must retain explicit core",reference);assertEquals("user",reference.getString("role"));assertTrue(reference.getString("content").contains("\"selection\":\"core\""));assertEquals(firstQuery,lastMessage(requests.get(0)).getString("content"));
                assertNoSystemMemory(requests.get(0),oldCore);
                memory.handle("save",new JSONObject().put("scope",scope).put("record",new JSONObject(selected.toString()).put("text",newCore)));
                channels.handle("ask",new JSONObject().put("eventId",second.getString("id")));assertTrue(allFinished.await(5,TimeUnit.SECONDS));assertEquals(2,requests.size());
                JSONObject refreshed=referenceContaining(requests.get(1),newCore);assertNotNull(refreshed);assertEquals("user",refreshed.getString("role"));assertNull("no prior snapshot may be cached",referenceContaining(requests.get(1),oldCore));assertNoSystemMemory(requests.get(1),newCore);
                assertEquals(secondQuery,lastMessage(requests.get(1)).getString("content"));assertEquals(0,channels.snapshot().getJSONArray("outbox").length());
            }finally{channels.close();}
        }finally{model.stop(0);}
    }
    private static JSONObject referenceContaining(JSONObject request,String text)throws Exception {JSONArray messages=request.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);if(message.optString("content").contains(text))return message;}return null;}
    private static JSONObject lastMessage(JSONObject request)throws Exception {JSONArray messages=request.getJSONArray("messages");return messages.getJSONObject(messages.length()-1);}
    private static void assertNoSystemMemory(JSONObject request,String text)throws Exception {JSONArray messages=request.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);if(message.optString("role").equals("system"))assertFalse(message.optString("content").contains(text));}}
    private static JSONObject authenticated(ChannelCore core,String profile,String id,String sender,String kind,String room)throws Exception{
        return authenticated(core,profile,id,sender,kind,room,"咖啡问题-"+id);
    }
    private static JSONObject authenticated(ChannelCore core,String profile,String id,String sender,String kind,String room,String text)throws Exception{
        String fixtureSecret="only-test-fixture-relay-secret-0123456789";Map<String,String> headers=new HashMap<>();headers.put("authorization","Bearer "+fixtureSecret);ChannelProtocol.authenticateRelay(fixtureSecret,headers);
        JSONObject provider=new JSONObject().put("messageId",id).put("senderId",sender).put("kind",kind).put("roomId",room).put("text",text).put("sentAt",System.currentTimeMillis())
                .put("principalId","owner").put("spaceId","private").put("role","owner");
        return core.accept(profile,ChannelProtocol.canonicalRelay(provider),"trusted_relay").getJSONObject("event");
    }
}
