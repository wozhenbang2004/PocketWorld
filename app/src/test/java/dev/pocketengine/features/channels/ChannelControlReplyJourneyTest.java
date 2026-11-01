package dev.pocketengine.features.channels;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.SQLiteMode;

/** Original authenticated transport, durable outbox and actual loopback wire. No real accounts.
 * Group case uses the original SQL/group host but must fail before admission/model/file read;
 * DM status uses an explicit HostTaskPort receipt fixture, never a fabricated execution grant. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ChannelControlReplyJourneyTest {
    private static final String SECRET="native-control-fixture-secret-at-least-32-bytes";
    @Before public void keyFixture(){java.security.Security.addProvider(new java.security.Provider("ChannelControlJourneyKeyStore","1","Explicit host fixture"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void removeKeyFixture(){java.security.Security.removeProvider("ChannelControlJourneyKeyStore");}
    private interface Condition {boolean done()throws Exception;}
    private static void await(Condition condition)throws Exception {long until=System.nanoTime()+5_000_000_000L;while(!condition.done()){if(System.nanoTime()>until)fail("original channel did not reach expected receipt within fixture deadline");Thread.sleep(10);}}
    @Test public void authenticatedGroupAttachmentFailureReturnsOneFixedReplyWithoutAdmittingOrReplayingTheTask()throws Exception {
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()){
            f.enable("group.files");f.incomingExtra=new JSONObject().put("attachments",new JSONArray().put(new JSONObject().put("url",f.base+"/qq-attachment.txt").put("content_type","text/plain").put("size",f.attachment.length+5)));
            f.send("/file --share Read the explicitly selected attachment",true,true,"failed-file-original");
            await(()->f.replies.size()==1&&f.channels.snapshot().getJSONArray("outbox").getJSONObject(0).optString("status").equals("sent"));assertEquals(1,f.downloads.get());assertEquals(0,f.modelCalls.get());assertEquals(0,f.tasks.list().length());
            JSONObject reply=f.replies.get(0);assertEquals("failed-file-original",reply.getString("msg_id"));assertTrue(reply.getString("content").contains("可能已被宿主接收"));assertFalse(reply.getString("content").contains("大小与平台原记录"));assertFalse(reply.toString().contains("PRIVATE OWNER FILE SENTINEL"));
            JSONArray outbox=f.channels.snapshot().getJSONArray("outbox");assertEquals(1,outbox.length());assertEquals("group_intake_unconfirmed",outbox.getJSONObject(0).getString("nativeControlCode"));assertEquals("sent",outbox.getJSONObject(0).getString("status"));
            f.send("/file --share Read the explicitly selected attachment",true,true,"failed-file-original");assertEquals(1,f.downloads.get());assertEquals(1,f.replies.size());assertEquals(0,f.tasks.list().length());
            f.restart();f.send("/file --share Read the explicitly selected attachment",true,true,"failed-file-original");assertEquals(1,f.downloads.get());assertEquals(1,f.replies.size());assertEquals(0,f.modelCalls.get());assertEquals(0,f.tasks.list().length());
        }
    }
    @Test public void ownerStatusQueriesOriginalTaskUsesNewCommandReplyAndLostAckRestartDoesNotResendOrReadmit()throws Exception {
        Context app=RuntimeEnvironment.getApplication();FeatureContext context=new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{});
        CopyOnWriteArrayList<JSONObject> delivered=new CopyOnWriteArrayList<>();AtomicInteger admissions=new AtomicInteger(),inspections=new AtomicInteger();AtomicReference<HostTaskPort.Request> original=new AtomicReference<>();
        HttpServer receiver=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        receiver.createContext("/reply",exchange->{try{assertEquals("Bearer "+SECRET,exchange.getRequestHeaders().getFirst("Authorization"));JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));delivered.add(body);
            // Application ACK is deliberately incomplete after recording accepted delivery.
            byte[] bytes=body.getString("text").startsWith("原任务 ")?"{".getBytes(StandardCharsets.UTF_8):new JSONObject().put("id","loopback-receipt-"+delivered.size()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});receiver.start();
        HostTaskPort host=new HostTaskPort(){public TaskState admit(Request r,Authority a)throws Exception{a.check();admissions.incrementAndGet();original.set(r);return new TaskState(r.taskId,r.taskId,1,1,"awaiting_user","Original private task requires exact owner approval");}public TaskState inspect(Request r,Authority a)throws Exception{a.check();inspections.incrementAndGet();assertEquals(original.get().taskId,r.taskId);return new TaskState(r.taskId,r.taskId,1,2,"running","PRIVATE-NOT-FOR-STATUS-RAW-DETAIL");}public TaskState cancel(Request r,Authority a){throw new AssertionError("status must not cancel or execute");}};
        ChannelsModule module=new ChannelsModule(context);String profile;JSONObject status;
        try{
            module.setHostTaskPort(host);profile=module.handle("profile.save",new JSONObject().put("name","Original status fixture").put("provider","webhook").put("secret",SECRET).put("enabled",true).put("autoReply",false).put("replyUrl","http://127.0.0.1:"+receiver.getAddress().getPort()+"/reply")).getString("id");int port=module.handle("server.start",new JSONObject().put("port",0)).getInt("port");
            String bind=post(port,profile,message("bind","Binding only"));module.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",bind).put("confirmed",true));module.handle("owner.tasks.set",ChannelTaskHostTest.taskPermission(module,profile,true));
            post(port,profile,message("original-task","/task Inspect original authorized task"));assertEquals(1,admissions.get());assertEquals(1,delivered.size());
            status=message("current-status-command","/task-status "+original.get().requestId);post(port,profile,status);
            ChannelsModule first=module;await(()->{JSONArray rows=first.snapshot().getJSONArray("outbox");for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).optString("nativeControlCode").equals("owner_task_status"))return rows.getJSONObject(i).getString("status").equals("unknown");return false;});
            assertEquals(2,delivered.size());JSONObject actual=delivered.get(1);assertEquals("current-status-command",actual.getString("replyTo"));assertEquals("owner",actual.getString("senderId"));assertTrue(actual.getString("text").contains("running"));assertFalse(actual.toString().contains("PRIVATE-NOT-FOR-STATUS-RAW-DETAIL"));assertEquals(1,inspections.get());
            module.close();module=new ChannelsModule(context);module.setHostTaskPort(host);int reopened=module.handle("server.start",new JSONObject().put("port",0)).getInt("port");post(reopened,profile,status);
            assertEquals(1,admissions.get());assertEquals(1,inspections.get());assertEquals(2,delivered.size());assertEquals(1,module.snapshot().getJSONArray("taskRequests").length());assertEquals(2,module.snapshot().getJSONArray("outbox").length());
        }finally{module.close();receiver.stop(0);}
    }
    @Test public void missingOriginalPublicSearchProducesNativeFailureAndOneBoundedNoticeWithoutAnyModel()throws Exception {
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()){
            f.enable("public.search");f.send("/search Compare only through the requested search capability",false,true,"unavailable-search");
            await(()->f.replies.size()==1&&f.channels.snapshot().getJSONArray("outbox").getJSONObject(0).optString("status").equals("sent"));assertEquals(0,f.modelCalls.get());assertEquals(0,f.downloads.get());assertEquals(1,f.tasks.list().length());assertEquals("failed",f.task().getString("status"));
            JSONObject row=f.channels.snapshot().getJSONArray("outbox").getJSONObject(0);assertEquals("group_capability_failed",row.getString("nativeControlCode"));assertEquals("unavailable-search",f.replies.get(0).getString("msg_id"));assertFalse(f.replies.get(0).getString("content").contains("策略"));assertTrue(row.has("nativeControlGroupScope"));
            f.send("/search Compare only through the requested search capability",false,true,"unavailable-search");assertEquals(1,f.tasks.list().length());assertEquals(1,f.replies.size());assertEquals(0,f.modelCalls.get());
        }
    }
    @Test public void ownerGroupSendPolicyRevocationDuringTokenRequestBlocksOriginalFeedbackMessage()throws Exception {
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        try(GroupTaskFlowsTest.Fixture f=new GroupTaskFlowsTest.Fixture()){
            f.api.createContext("/app/getAppAccessToken",exchange->{try{entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));byte[] bytes=new JSONObject().put("access_token","real-fixture-token").put("expires_in",7200).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});
            f.enable("group.files");f.incomingExtra=new JSONObject().put("attachments",new JSONArray().put(new JSONObject().put("url",f.base+"/qq-attachment.txt").put("content_type","text/plain").put("size",f.attachment.length+5)));
            f.send("/file This share flag cannot authorize sending --share",true,true,"revoke-before-message");assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            JSONObject grant=f.group.getJSONArray("grants").getJSONObject(0);grant.put("allowGroupAudience",false);
            f.group=f.groups.ownerCommand("policy.set",new JSONObject().put("id",f.groupId).put("revision",f.group.getLong("revision")).put("enabled",true).put("visibility","members").put("quota",f.group.getJSONObject("quota")).put("grants",new JSONArray().put(grant)));release.countDown();
            await(()->{JSONArray rows=f.channels.snapshot().getJSONArray("outbox");return rows.length()==1&&rows.getJSONObject(0).optString("status").equals("unknown");});
            assertEquals(0,f.replies.size());assertEquals(0,f.modelCalls.get());assertEquals(0,f.tasks.list().length());String id=f.channels.snapshot().getJSONArray("outbox").getJSONObject(0).getString("id");assertThrows(SecurityException.class,()->f.channels.handle("outbox.send",new JSONObject().put("id",id)));
        }finally{release.countDown();}
    }
    private static JSONObject message(String id,String text)throws Exception {return new JSONObject().put("messageId",id).put("senderId","owner").put("kind","dm").put("text",text).put("sentAt",System.currentTimeMillis());}
    private static String post(int port,String profile,JSONObject message)throws Exception {
        byte[] body=message.toString().getBytes(StandardCharsets.UTF_8);try(Socket socket=new Socket("127.0.0.1",port)){socket.setSoTimeout(5000);socket.getOutputStream().write(("POST /events/"+profile+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+SECRET+"\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(response,response.startsWith("HTTP/1.1 200"));JSONObject value=new JSONObject(response.substring(response.indexOf("\r\n\r\n")+4));return value.optString("eventId");}
    }
}
