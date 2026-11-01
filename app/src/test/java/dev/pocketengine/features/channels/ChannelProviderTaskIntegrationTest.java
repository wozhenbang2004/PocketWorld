package dev.pocketengine.features.channels;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.ChannelTaskCoordinator;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Both actual provider adapters, native identity, one SQLite store and the production AgentRunner.
 * The QQ/iLink/model endpoints and keystore are explicit local fixtures, not real accounts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ChannelProviderTaskIntegrationTest {
    @Before public void keyFixture(){java.security.Security.addProvider(new java.security.Provider("ChannelProviderKeyStore","1","Synthetic provider fixture"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void removeKeyFixture(){java.security.Security.removeProvider("ChannelProviderKeyStore");}

    @Test public void qqAndWeixinEnterSameNativeTaskRunnerAndReturnExactProviderReceipts()throws Exception {
        CountDownLatch qqTask=new CountDownLatch(1),finish=new CountDownLatch(1);AtomicInteger polls=new AtomicInteger(),modelCalls=new AtomicInteger();CopyOnWriteArrayList<JSONObject> qqReplies=new CopyOnWriteArrayList<>(),wxReplies=new CopyOnWriteArrayList<>();
        HttpServer api=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);HttpServer model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try(QqGatewayFixture gateway=new QqGatewayFixture(peer->{
            peer.send(new JSONObject().put("op",10).put("d",new JSONObject().put("heartbeat_interval",45000)));assertEquals(2,peer.read().getInt("op"));peer.send(QqGatewaySessionTest.ready(1));peer.send(QqGatewaySessionTest.message(2,"qq-bind","绑定依据"));
            assertTrue(qqTask.await(6,TimeUnit.SECONDS));peer.send(QqGatewaySessionTest.message(3,"qq-task","/task 请读取并说明QQ结果"));peer.send(QqGatewaySessionTest.message(4,"qq-task","/task 请读取并说明QQ结果"));
            // The reader remains live while the native runner executes. No public callback is used.
            while(!finish.await(100,TimeUnit.MILLISECONDS)){/* This fixture responds to server-requested heartbeats only; the task finishes inside one second. */}
        })){
            api.createContext("/",exchange->{try{
                String route=exchange.getRequestURI().getPath();byte[] request=exchange.getRequestBody().readAllBytes();JSONObject body=request.length==0?new JSONObject():new JSONObject(new String(request,StandardCharsets.UTF_8)),response;
                if(route.equals("/app/getAppAccessToken"))response=new JSONObject().put("access_token","qq-fixture-token").put("expires_in",7200);
                else if(route.equals("/gateway")){assertEquals("QQBot qq-fixture-token",exchange.getRequestHeaders().getFirst("Authorization"));response=new JSONObject().put("url",gateway.url());}
                else if(route.equals("/v2/users/fixture-owner/messages")){assertEquals("QQBot qq-fixture-token",exchange.getRequestHeaders().getFirst("Authorization"));qqReplies.add(body);response=new JSONObject().put("id","qq-receipt-"+qqReplies.size());}
                else if(route.equals("/ilink/bot/getupdates")){
                    assertEquals("Bearer wx-fixture-token",exchange.getRequestHeaders().getFirst("Authorization"));int round=polls.incrementAndGet();assertEquals(round==1?"":"cursor-1",body.getString("get_updates_buf"));
                    response=new JSONObject().put("ret",0).put("msgs",new JSONArray().put(weixinMessage(round,round==1?"绑定依据":"/task 请读取并说明微信结果"))).put("get_updates_buf","cursor-"+round);
                }else if(route.equals("/ilink/bot/sendmessage")){
                    assertEquals("Bearer wx-fixture-token",exchange.getRequestHeaders().getFirst("Authorization"));JSONObject message=body.getJSONObject("msg");assertEquals("wx-human@im.wechat",message.getString("to_user_id"));assertEquals("wx-context-2",message.getString("context_token"));wxReplies.add(message);response=new JSONObject().put("ret",0);
                }else throw new AssertionError("Unexpected provider fixture route "+route);
                byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable failure){throw new java.io.IOException("Local provider fixture failed",failure);}finally{exchange.close();}});api.start();
            model.createContext("/",exchange->{try{
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertTrue(request.getJSONArray("messages").toString().contains("请读取并说明"));JSONArray tools=request.optJSONArray("tools");if(tools!=null)assertFalse(tools.toString().contains("phone_tap"));modelCalls.incrementAndGet();
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content","合成模型已处理，请机主核对实际结果。")))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}});model.start();
            String endpoint="http://127.0.0.1:"+api.getAddress().getPort();Context android=RuntimeEnvironment.getApplication();FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});TaskStore tasks=new TaskStore(android);FeatureHost features=new FeatureHost(event->{},tasks,()->false);
            try(ChannelsModule channels=new ChannelsModule(context,new ChannelHttp(endpoint),new QqGatewayClient(true),new WeixinClient(endpoint))){
                LinkedBlockingQueue<Runnable> work=new LinkedBlockingQueue<>(16);ModelConfig config=new ModelConfig("http://127.0.0.1:"+model.getAddress().getPort()+"/v1","","synthetic",false,8,10);
                java.util.concurrent.ConcurrentHashMap<String,RunControl> controls=new java.util.concurrent.ConcurrentHashMap<>();ChannelTaskCoordinator[] coordinator=new ChannelTaskCoordinator[1];
                coordinator[0]=new ChannelTaskCoordinator(tasks,features,channels,command->{if(!work.offer(command))throw new java.util.concurrent.RejectedExecutionException("fixture full");},controls,()->config,(id,initial,settings,control)->{
                    coordinator[0].protect(id,control);JSONObject attempt=tasks.requireCapabilityAttempt(id,control);HostToolSupervisor port=new HostToolSupervisor(features,tasks,id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),true);
                    JSONArray names=new JSONArray(),definitions=port.tools();for(int i=0;i<definitions.length();i++)names.put(definitions.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(id,attempt.getString("runId"),names);
                    RunJournal journal=tasks.journal(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),control);RunOutcome result=new AgentRunner(null,port).run(id,attempt.getString("goal"),settings,control,event->{try{tasks.event(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),event);}catch(Exception failed){throw new IllegalStateException(failed);}},journal);
                    tasks.endAttempt(id,attempt.getString("runId"),result.status,result.summary,result.evidence);return new JSONObject().put("status",result.status);
                },id->{},message->{});channels.setHostTaskPort(coordinator[0]);
                String qq=channels.handle("profile.save",new JSONObject().put("name","QQ官方Bot fixture").put("provider","qq").put("appId","12345").put("secret","qq-fixture-secret").put("enabled",true)).getString("id");channels.handle("qq.start",new JSONObject().put("id",qq));
                try{QqGatewayTransportTest.await(()->channels.snapshot().getJSONArray("inbox").length()==1);}catch(AssertionError failed){gateway.assertHealthy();throw new AssertionError("QQ inbox missing: "+channels.snapshot(),failed);}
                bindAndEnable(channels,qq);qqTask.countDown();
                try{QqGatewayTransportTest.await(()->!work.isEmpty());}catch(AssertionError failed){gateway.assertHealthy();throw new AssertionError("QQ task missing: "+channels.snapshot(),failed);}Runnable first=work.poll(2,TimeUnit.SECONDS);assertNotNull(first);first.run();assertEquals("one persisted task",1,tasks.list().length());assertEquals("one actual model HTTP request; task="+tasks.list(),1,modelCalls.get());
                assertEquals("needs_confirmation",tasks.list().getJSONObject(0).getString("status"));assertEquals(0,work.size());assertTrue(qqReplies.toString().contains("合成模型已处理"));for(JSONObject reply:qqReplies)assertEquals("qq-task",reply.getString("msg_id"));
                String wx=channels.handle("profile.save",new JSONObject().put("name","微信iLinkBot fixture").put("provider","weixin").put("appId","wx-bot@im.bot").put("secret","wx-fixture-token").put("enabled",true)).getString("id");channels.handle("weixin.poll",new JSONObject().put("id",wx));bindAndEnable(channels,wx);channels.handle("weixin.poll",new JSONObject().put("id",wx));Runnable second=work.poll(2,TimeUnit.SECONDS);assertNotNull(second);second.run();
                assertEquals(2,tasks.list().length());assertEquals(2,modelCalls.get());assertTrue(wxReplies.toString().contains("合成模型已处理"));assertTrue(controls.isEmpty());
                JSONArray links=channels.snapshot().getJSONArray("taskRequests");assertEquals(2,links.length());assertNotEquals(links.getJSONObject(0).getString("taskId"),links.getJSONObject(1).getString("taskId"));
                for(int i=0;i<links.length();i++){JSONObject task=tasks.get(links.getJSONObject(i).getString("taskId"),false);assertEquals("channel",task.getJSONObject("origin").getString("kind"));assertEquals("private",task.getJSONObject("origin").getString("spaceId"));assertEquals("needs_confirmation",task.getString("status"));assertEquals(1,task.getInt("coordinatorEpoch"));}
                JSONArray outbox=channels.snapshot().getJSONArray("outbox");for(int i=0;i<outbox.length();i++){JSONObject row=outbox.getJSONObject(i);assertEquals("sent",row.getString("status"));assertFalse(row.getString("receiptId").isEmpty());}
                for(JSONObject reply:wxReplies)assertTrue(outbox.toString().contains(reply.getString("client_id")));assertFalse(channels.snapshot().toString().contains("wx-context-2"));assertFalse(channels.snapshot().toString().contains("qq-fixture-token"));
            }finally{features.close();}
            finish.countDown();gateway.awaitFinished();
        }finally{finish.countDown();qqTask.countDown();api.stop(0);model.stop(0);}
    }
    private static void bindAndEnable(ChannelsModule channels,String profile)throws Exception {
        JSONArray inbox=channels.snapshot().getJSONArray("inbox");String event=null;for(int i=0;i<inbox.length();i++)if(profile.equals(inbox.getJSONObject(i).optString("profileId")))event=inbox.getJSONObject(i).getString("id");assertNotNull(event);
        channels.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",event).put("confirmed",true));channels.handle("owner.tasks.set",ChannelTaskHostTest.taskPermission(channels,profile,true));
    }
    private static JSONObject weixinMessage(int id,String text)throws Exception{return new JSONObject().put("message_id",id).put("message_type",1).put("message_state",2).put("from_user_id","wx-human@im.wechat").put("create_time_ms",System.currentTimeMillis()).put("context_token","wx-context-"+id).put("item_list",new JSONArray().put(new JSONObject().put("type",1).put("text_item",new JSONObject().put("text",text))));}
}
