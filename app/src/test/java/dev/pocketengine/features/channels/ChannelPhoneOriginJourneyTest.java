package dev.pocketengine.features.channels;

import android.app.NotificationManager;
import android.content.Intent;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.phone.PhoneAccessibilityService;
import dev.pocketengine.ui.ComposeActivity;
import dev.pocketengine.ui.PocketWorldController;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real authenticated ingress, native notification/controller, original SQLite phone lease and outbox.
 * No model, screen action, telephone number or real platform account is contacted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class ChannelPhoneOriginJourneyTest {
    private EngineApplication app;private ChannelsModule channels;private String profile;private int port;
    private static final String SECRET="channel-phone-fixture-only-secret-32-bytes";
    @Before public void setup()throws Exception {
        java.security.Security.addProvider(new java.security.Provider("PhoneOriginFixture","1","Synthetic keystore"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});
        app=(EngineApplication)RuntimeEnvironment.getApplication();channels=(ChannelsModule)app.features.module("channels");
        app.settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:9/v1").put("model","fixture-no-call").put("sendScreenshots",false).put("maxSteps",5));
        profile=channels.handle("profile.save",new JSONObject().put("provider","webhook").put("name","phone fixture").put("secret",SECRET).put("enabled",true).put("autoReply",false)).getString("id");
        port=channels.handle("server.start",new JSONObject().put("port",0)).getInt("port");
        String binding=post("bind","owner","dm","hello");channels.handle("owner.bind",new JSONObject().put("profileId",profile).put("verifiedEventId",binding).put("confirmed",true));
        channels.handle("owner.tasks.set",ChannelTaskHostTest.taskPermission(channels,profile,true));
        Field singleton=PhoneAccessibilityService.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,Robolectric.buildService(PhoneAccessibilityService.class).create().get());
    }
    @After public void close()throws Exception {if(app!=null){app.stop(null);app.features.close();Field workers=EngineApplication.class.getDeclaredField("capabilityWorkers");workers.setAccessible(true);((java.util.concurrent.ExecutorService)workers.get(app)).shutdownNow();}Field singleton=PhoneAccessibilityService.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);java.security.Security.removeProvider("PhoneOriginFixture");}
    @Test public void notificationOnlyLocatesAuthenticatedEventAndControllerKeepsOriginalPhoneResult()throws Exception {
        String event=post("phone","owner","dm","/phone 读取合成测试页面");idle();
        android.app.Notification notification=Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getAllNotifications().stream().filter(n->"owner_requests".equals(n.getChannelId())).findFirst().orElseThrow();
        Intent notificationIntent=Shadows.shadowOf(notification.contentIntent).getSavedIntent();assertEquals(event,notificationIntent.getStringExtra("channelEventId"));assertEquals(0,app.tasks.list().length());
        try(ActivityController<ComposeActivity> activity=Robolectric.buildActivity(ComposeActivity.class,notificationIntent).create().start().resume()) {
            activity.get().setContentView(new android.view.View(activity.get()));
            Field field=ComposeActivity.class.getDeclaredField("controller");field.setAccessible(true);PocketWorldController controller=(PocketWorldController)field.get(activity.get());
            await(()->controller.getState().getSuggestedInput().equals("读取合成测试页面"));assertEquals(0,app.tasks.list().length());
            controller.startPhone("读取合成测试页面","main_screen");await(()->{if(controller.getState().getError()!=null)fail(controller.getState().getError());return app.activeId()!=null;});String id=app.activeId();JSONObject task=app.tasks.get(id,false);
            assertEquals("phone",task.getString("kind"));assertEquals("main_screen",task.getString("executionMode"));assertNotNull("locally approved phone task must retain original channel source",task.optJSONObject("origin"));assertEquals(event,task.getJSONObject("origin").getString("eventId"));assertEquals("channel",task.getJSONObject("origin").getString("kind"));
            assertThrows(SecurityException.class,()->app.tasks.configureExecutionScope(id,1,0,40,48));
            await(()->!controller.getState().getPhoneSubmitting());controller.startPhone("读取合成测试页面","main_screen");idle();assertEquals(1,app.tasks.list().length());assertEquals(1,app.tasks.get(id,false).getInt("coordinatorEpoch"));
            // The original run journal result, not a fabricated platform ACK; no phone service worker is started by this fixture.
            app.tasks.endAttempt(id,task.getString("runId"),"needs_confirmation","合成原手机结果待主人核对",new JSONObject());app.pushTask(id);
            await(()->channels.snapshot().getJSONArray("outbox").toString().contains("合成原手机结果"));JSONObject link=channels.snapshot().getJSONArray("taskRequests").getJSONObject(0);assertEquals(id,link.getString("taskId"));assertEquals(event,link.getString("eventId"));
            JSONObject out=channels.snapshot().getJSONArray("outbox").getJSONObject(channels.snapshot().getJSONArray("outbox").length()-1);assertEquals(event,out.getString("eventId"));assertEquals("private",out.getJSONObject("route").getString("spaceId"));
        }
    }
    @Test public void nativeStartRequiresConsentAndRejectsGroupStrangerRevokedAndCancelledSources()throws Exception {
        String event=post("phone","owner","dm","/phone 不执行真实操作");JSONObject review=app.prepareChannelPhone(event);String request=review.getString("requestId");
        assertThrows(SecurityException.class,()->app.tasks.registerChannelPhoneTask(channels.phoneTaskRequest(request),"main_screen",5,null,()->channels.checkHostTaskAuthority(request)));
        JSONObject input=new JSONObject().put("goal",review.getString("goal")).put("executionMode","main_screen").put("channelRequestId",request).put("screenConsent",false);
        assertThrows(Exception.class,()->app.dispatchOwnerAction("startRun",input));assertEquals(0,app.tasks.list().length());
        String group=post("group","owner","group","/phone 私人材料"),stranger=post("stranger","other","dm","/phone 私人材料");assertThrows(SecurityException.class,()->app.prepareChannelPhone(group));assertThrows(SecurityException.class,()->app.prepareChannelPhone(stranger));
        app.cancelChannelPhone(request);assertThrows(SecurityException.class,()->app.dispatchOwnerAction("startRun",input.put("screenConsent",true)));assertEquals(0,app.tasks.list().length());
        String remoteCancelled=post("cancel-before-consent","owner","dm","/phone 等待确认");JSONObject cancelled=app.prepareChannelPhone(remoteCancelled);post("cancel-before-consent-command","owner","dm","/task-cancel "+cancelled.getString("requestId"));
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("startRun",new JSONObject().put("goal",cancelled.getString("goal")).put("executionMode","main_screen").put("channelRequestId",cancelled.getString("requestId")).put("screenConsent",true)));assertEquals(0,app.tasks.list().length());
        String another=post("second","owner","dm","/phone 原认证目标");JSONObject second=app.prepareChannelPhone(another);channels.handle("owner.tasks.set",ChannelTaskHostTest.taskPermission(channels,profile,false));assertThrows(SecurityException.class,()->app.dispatchOwnerAction("startRun",new JSONObject().put("goal",second.getString("goal")).put("executionMode","main_screen").put("channelRequestId",second.getString("requestId")).put("screenConsent",true)));assertEquals(0,app.tasks.list().length());
    }
    @Test public void samePhoneLeaseReceivesChannelStopAndRevocationFencesLatePrivateResult()throws Exception {
        String event=post("phone-stop","owner","dm","/phone 原手机控制");JSONObject review=app.prepareChannelPhone(event);
        String id=app.dispatchOwnerAction("startRun",new JSONObject().put("goal",review.getString("goal")).put("executionMode","main_screen").put("screenConsent",true).put("channelRequestId",review.getString("requestId"))).getString("taskId");
        dev.pocketengine.core.RunControl original=app.control(id);assertNotNull(original);
        await(()->channels.snapshot().getJSONArray("taskRequests").getJSONObject(0).optString("admission").equals("admitted"));
        post("cancel","owner","dm","/task-cancel "+review.getString("requestId"));assertTrue(original.isCancelled());assertTrue(app.tasks.get(id,false).getBoolean("cancelRequested"));
        channels.handle("owner.tasks.set",ChannelTaskHostTest.taskPermission(channels,profile,false));
        JSONObject current=app.tasks.get(id,false);app.tasks.endAttempt(id,current.getString("runId"),"needs_confirmation","private-fixture-must-not-leak",new JSONObject());app.pushTask(id);idle();
        assertFalse(channels.snapshot().getJSONArray("outbox").toString().contains("private-fixture-must-not-leak"));assertEquals(1,app.tasks.list().length());
        app.released(id,original);assertNull(app.activeId());assertThrows(SecurityException.class,()->app.dispatchOwnerAction("startRun",new JSONObject().put("goal",review.getString("goal")).put("executionMode","main_screen").put("screenConsent",true).put("channelRequestId",review.getString("requestId"))));assertEquals(1,app.tasks.list().length());
    }
    @Test public void cancelledNativeDraftSurvivesActivityRestoreWithoutPrivateFallback()throws Exception {
        String event=post("cancel-draft","owner","dm","/phone 取消原草稿");Intent intent=new Intent(app,ComposeActivity.class).putExtra("channelEventId",event).putExtra("automationGoal","forged private fallback");android.os.Bundle saved=new android.os.Bundle();
        try(ActivityController<ComposeActivity> first=Robolectric.buildActivity(ComposeActivity.class,intent).create().start().resume()) {
            first.get().setContentView(new android.view.View(first.get()));Field field=ComposeActivity.class.getDeclaredField("controller");field.setAccessible(true);PocketWorldController owner=(PocketWorldController)field.get(first.get());
            await(()->owner.getState().getSuggestedInput().equals("取消原草稿"));owner.cancelChannelPhone();
            await(()->channels.snapshot().getJSONArray("taskRequests").getJSONObject(0).getString("admission").equals("cancelled"));first.saveInstanceState(saved);assertEquals(event,saved.getString("channelPhoneEventId"));assertFalse(intent.hasExtra("automationGoal"));
        }
        try(ActivityController<ComposeActivity> restored=Robolectric.buildActivity(ComposeActivity.class,new Intent(app,ComposeActivity.class)).create(saved).start().resume()) {
            restored.get().setContentView(new android.view.View(restored.get()));Field field=ComposeActivity.class.getDeclaredField("controller");field.setAccessible(true);PocketWorldController owner=(PocketWorldController)field.get(restored.get());
            await(()->owner.getState().getNotice()!=null&&owner.getState().getNotice().contains("已处理或取消"));owner.startPhone("取消原草稿","main_screen");idle();assertEquals(0,app.tasks.list().length());assertNotNull(owner.getState().getError());
        }
    }
    private interface Check {boolean get()throws Exception;}
    private void await(Check check)throws Exception {long end=System.nanoTime()+8_000_000_000L;while(!check.get()){idle();if(System.nanoTime()>end)fail("native fixture timeout");Thread.sleep(15);}}
    private static void idle(){Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(10));}
    private String post(String message,String sender,String kind,String text)throws Exception {
        byte[] bytes=ChannelSecurityTest.message(message,sender,kind,kind.equals("group")?"room":"").put("text",text).toString().getBytes(StandardCharsets.UTF_8);
        try(Socket socket=new Socket("127.0.0.1",port)){socket.setSoTimeout(5000);socket.getOutputStream().write(("POST /events/"+profile+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+SECRET+"\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(response,response.startsWith("HTTP/1.1 200"));return new JSONObject(response.substring(response.indexOf("\r\n\r\n")+4)).getString("eventId");}
    }
}
