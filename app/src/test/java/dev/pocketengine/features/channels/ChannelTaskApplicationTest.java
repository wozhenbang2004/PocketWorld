package dev.pocketengine.features.channels;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Full native Application entry, existing runner, SQLite and HTTP. No real model/channel account. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class ChannelTaskApplicationTest {
    @Test public void applicationWiresAuthenticatedDmThroughNativeApprovalToActualFileAndChannelReceipt()throws Exception{
        java.security.Security.addProvider(new java.security.Provider("ChannelApplicationKeyStore","1","Synthetic account fixture"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();String secret="application-fixture-relay-at-least-32-bytes";
        AtomicInteger calls=new AtomicInteger();CopyOnWriteArrayList<JSONObject> deliveries=new CopyOnWriteArrayList<>();
        ChannelServer receiver=new ChannelServer((profile,headers,bytes)->{assertEquals("Bearer "+secret,headers.get("authorization"));deliveries.add(new JSONObject(new String(bytes,StandardCharsets.UTF_8)));return new JSONObject().put("id","application-receipt-"+deliveries.size());});
        HttpServer model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try{
            ChannelsModule channels=(ChannelsModule)app.features.module("channels");assertTrue("Application must inject the real coordinator",channels.snapshot().getJSONObject("capabilities").getBoolean("ownerTaskHostConfigured"));
            EngineModule workspace=app.features.module("workspace");String project=workspace.handle("create",new JSONObject().put("name","Application渠道测试")).getString("id");JSONObject args=new JSONObject().put("projectId",project).put("path","application.txt").put("content","从认证私聊进入唯一宿主").put("expectedVersion",0);
            model.createContext("/",exchange->{
                int number=calls.incrementAndGet();JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertTrue(request.getJSONArray("tools").toString().contains("workspace_write"));
                JSONObject message=number<=2?new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","application-call-"+number).put("type","function").put("function",new JSONObject().put("name","workspace_write").put("arguments",args.toString())))):new JSONObject().put("role","assistant").put("content","实际文件保存完毕，等待主人核对。");
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message))).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });model.start();app.settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+model.getAddress().getPort()+"/v1").put("model","synthetic").put("sendScreenshots",false).put("maxSteps",8));
            String profile=channels.handle("profile.save",new JSONObject().put("name","合成绑定").put("provider","webhook").put("secret",secret).put("enabled",true).put("autoReply",false).put("replyUrl","http://127.0.0.1:"+receiver.start(0)+"/events/result")).getString("id");int ingress=channels.handle("server.start",new JSONObject().put("port",0)).getInt("port");
            String binding=post(ingress,profile,secret,"bind","绑定依据");channels.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",binding).put("confirmed",true));
            post(ingress,profile,secret,"disabled","/task 默认不可执行");assertEquals(0,app.tasks.list().length());
            channels.handle("owner.tasks.set",ChannelTaskHostTest.taskPermission(channels,profile,true));post(ingress,profile,secret,"write","/task 保存项目文件");
            String task=channels.snapshot().getJSONArray("taskRequests").getJSONObject(0).getString("taskId");await(()->app.tasks.get(task,false).optString("status").equals("awaiting_user")&&!active(app,task));
            JSONObject waiting=app.tasks.get(task,false);assertEquals(1,waiting.getInt("coordinatorEpoch"));assertEquals(0,workspace.handle("list",new JSONObject().put("projectId",project)).getJSONArray("files").length());
            String approval=waiting.getJSONArray("approvals").getJSONObject(0).getString("id");app.dispatchOwnerAction("approveCapability",new JSONObject().put("taskId",task).put("approvalId",approval).put("approved",true));
            await(()->app.tasks.get(task,false).optString("status").equals("needs_confirmation")&&!active(app,task));await(()->deliveries.toString().contains("实际文件保存完毕"));
            JSONObject done=app.tasks.get(task,false);assertEquals(2,done.getInt("coordinatorEpoch"));assertEquals(3,calls.get());assertEquals(1,app.tasks.list().length());assertEquals("channel",done.getJSONObject("origin").getString("kind"));assertEquals("从认证私聊进入唯一宿主",workspace.handle("read",args).getString("content"));assertEquals(1,workspace.handle("history",args).getJSONArray("history").length());
            app.dispatchOwnerAction("confirmResult",new JSONObject().put("taskId",task).put("resultReviewDigest",app.tasks.get(task,false).getString("resultReviewDigest")).put("completed",true));await(()->deliveries.toString().contains("已由主人确认完成"));assertEquals("completed",app.tasks.get(task,false).getString("status"));
            post(ingress,profile,secret,"read-only","/task 只读取并说明，仍由主人核对");
            JSONArray requests=channels.snapshot().getJSONArray("taskRequests");String readOnly=requests.getJSONObject(requests.length()-1).getString("taskId");
            await(()->!active(app,readOnly)&&app.tasks.get(readOnly,false).optString("status").equals("needs_confirmation"));
            assertEquals("Channel receipts must not upgrade model claims to verified completion", "needs_confirmation", app.tasks.get(readOnly,false).getString("status"));
            assertEquals(1,app.tasks.get(readOnly,false).getInt("coordinatorEpoch"));
            await(()->{
                JSONArray links=channels.snapshot().getJSONArray("taskRequests");JSONObject link=links.getJSONObject(links.length()-1);
                if(link.optLong("lastUpdateRevision")!=app.tasks.get(readOnly,false).getLong("channelStateRevision"))return false;
                for(int i=0;i<channels.snapshot().getJSONArray("outbox").length();i++){
                    JSONObject row=channels.snapshot().getJSONArray("outbox").getJSONObject(i);
                    if(row.optString("id").equals(link.optString("lastOutboxId")))return row.optString("status").equals("sent");
                }return false;
            });
            JSONArray outbox=channels.snapshot().getJSONArray("outbox");assertTrue(outbox.length()>=3);for(int i=0;i<outbox.length();i++){assertEquals("sent",outbox.getJSONObject(i).getString("status"));assertTrue(outbox.getJSONObject(i).getString("receiptId").startsWith("application-receipt-"));}
        }finally{
            model.stop(0);receiver.close();app.features.close();java.lang.reflect.Field workers=EngineApplication.class.getDeclaredField("capabilityWorkers");workers.setAccessible(true);((java.util.concurrent.ExecutorService)workers.get(app)).shutdownNow();java.security.Security.removeProvider("ChannelApplicationKeyStore");
        }
    }
    private interface Condition {boolean get()throws Exception;}
    private static void await(Condition condition)throws Exception{long until=System.nanoTime()+10_000_000_000L;while(!condition.get()){if(System.nanoTime()>until)fail("native lifecycle did not reach the expected state within the fixture deadline");Thread.sleep(20);}}
    private static boolean active(EngineApplication app,String id)throws Exception{java.lang.reflect.Field controls=EngineApplication.class.getDeclaredField("capabilityControls");controls.setAccessible(true);return ((java.util.Map<?,?>)controls.get(app)).containsKey(id);}
    private static String post(int port,String profile,String secret,String message,String text)throws Exception{
        byte[] body=ChannelSecurityTest.message(message,"owner","dm","").put("text",text).toString().getBytes(StandardCharsets.UTF_8);try(Socket socket=new Socket("127.0.0.1",port)){socket.setSoTimeout(5000);socket.getOutputStream().write(("POST /events/"+profile+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+secret+"\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(response,response.startsWith("HTTP/1.1 200"));return new JSONObject(response.substring(response.indexOf("\r\n\r\n")+4)).getString("eventId");}
    }
}
