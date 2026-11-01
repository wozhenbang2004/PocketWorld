package dev.pocketengine.features.channels;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Looper;
import android.os.PowerManager;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Actual Android Service/notification/lease plus real loopback QQ socket. Not device power evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ChannelReceiveServiceTest {
    private Context context;
    private TaskStore ownedTasks;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS,Manifest.permission.WAKE_LOCK,Manifest.permission.FOREGROUND_SERVICE,Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE);java.security.Security.addProvider(new java.security.Provider("ChannelServiceFixtureKeyStore","1","Synthetic receiver fixture"){{put("KeyStore.AndroidKeyStore",ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}
    @After public void cleanup(){if(ownedTasks!=null)((android.database.sqlite.SQLiteDatabase)ReflectionHelpers.getField(ownedTasks,"db")).close();java.security.Security.removeProvider("ChannelServiceFixtureKeyStore");FixtureService.module=null;}
    @Test public void actualForegroundServiceOwnsVisibleSocketAndStopReleasesWithoutTouchingExistingTask()throws Exception {
        CountDownLatch finish=new CountDownLatch(1);AtomicInteger network=new AtomicInteger();
        try(QqGatewayFixture gateway=new QqGatewayFixture(peer->{peer.send(new JSONObject().put("op",10).put("d",new JSONObject().put("heartbeat_interval",45000)));assertEquals(2,peer.read().getInt("op"));peer.send(QqGatewaySessionTest.ready(1));peer.send(QqGatewaySessionTest.message(2,"service-message","只接收，不创建任务"));finish.await(6,TimeUnit.SECONDS);});Endpoint endpoint=new Endpoint(gateway.url(),network)){
            try(ChannelsModule module=module(endpoint.base())){
                FixtureService.module=module;String profile=profile(module),session=module.prepareBackgroundReceiving(new JSONArray().put(profile),true).getString("sessionId");TaskStore tasks=new TaskStore(context);ownedTasks=tasks;String task=tasks.create("已有手机任务不属于接收服务").getString("id");String before=tasks.get(task,false).toString();
                try(ServiceFixture controller=new ServiceFixture()){
                    FixtureService service=controller.get();assertEquals(ChannelReceiveService.START_NOT_STICKY,service.onStartCommand(new Intent(ChannelReceiveService.START).putExtra("sessionId",session),0,1));
                    await(()->module.backgroundState().getInt("authenticatedReceivers")==1&&module.snapshot().getJSONArray("inbox").length()==1);
                    assertTrue(module.backgroundState().getBoolean("foregroundActive"));assertTrue(module.backgroundState().getBoolean("foregroundServicePresent"));assertTrue(network.get()>=2);
                    Notification notification=Shadows.shadowOf(service).getLastForegroundNotification();assertNotNull(notification);assertTrue((notification.flags&Notification.FLAG_ONGOING_EVENT)!=0);assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,service.getForegroundServiceType());assertEquals("停止接收",notification.actions[0].title.toString());
                    Intent stop=Shadows.shadowOf(notification.actions[0].actionIntent).getSavedIntent();assertEquals(session,stop.getStringExtra("sessionId"));assertNotNull(stop.getData());PowerManager.WakeLock wake=ShadowPowerManager.getLatestWakeLock();assertTrue(wake.isHeld());assertFalse(Shadows.shadowOf(wake).isReferenceCounted());
                    service.onStartCommand(new Intent(ChannelReceiveService.STOP).putExtra("sessionId",java.util.UUID.randomUUID().toString()),0,2);assertTrue(module.backgroundState().getBoolean("foregroundActive"));
                    service.onStartCommand(stop,0,3);await(()->!module.backgroundState().getBoolean("foregroundServicePresent"));assertEquals("stopped",module.backgroundState().getString("status"));assertFalse(wake.isHeld());assertTrue(Shadows.shadowOf(service).isForegroundStopped());assertTrue(Shadows.shadowOf(service).isStoppedBySelf());assertEquals(before,tasks.get(task,false).toString());assertEquals(0,module.snapshot().getJSONArray("taskRequests").length());
                }finish.countDown();gateway.awaitFinished();
            }
        }finally{finish.countDown();}
    }
    @Test public void stopBeforeServiceDeliveryAndNullRestartNeverStartProviderRequests()throws Exception {
        AtomicInteger network=new AtomicInteger();try(Endpoint endpoint=new Endpoint("ws://127.0.0.1:1/websocket",network);ChannelsModule module=module(endpoint.base())){
            FixtureService.module=module;String session=module.prepareBackgroundReceiving(new JSONArray().put(profile(module)),true).getString("sessionId");module.stopBackgroundReceiving(session);
            try(ServiceFixture controller=new ServiceFixture()){
                FixtureService service=controller.get();assertEquals(ChannelReceiveService.START_NOT_STICKY,service.onStartCommand(new Intent(ChannelReceiveService.START).putExtra("sessionId",session),0,1));assertEquals(ChannelReceiveService.START_NOT_STICKY,service.onStartCommand(null,0,2));assertEquals(0,network.get());assertNull(Shadows.shadowOf(service).getLastForegroundNotification());assertFalse(module.backgroundState().getBoolean("foregroundServicePresent"));
            }
        }
    }
    @Test public void weixinUsesSameForegroundLifetimeAndNotificationRevocationCancelsBlockedPoll()throws Exception {
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger requests=new AtomicInteger();HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/",exchange->{try{
            assertEquals("/ilink/bot/getupdates",exchange.getRequestURI().getPath());assertEquals("Bearer wx-fixture-secret",exchange.getRequestHeaders().getFirst("Authorization"));exchange.getRequestBody().readAllBytes();int call=requests.incrementAndGet();
            if(call>1){blocked.countDown();release.await(5,TimeUnit.SECONDS);return;}
            JSONObject message=new JSONObject().put("message_id",1).put("message_type",1).put("message_state",2).put("from_user_id","human@im.wechat").put("create_time_ms",System.currentTimeMillis()).put("context_token","private-wx-context").put("item_list",new JSONArray().put(new JSONObject().put("type",1).put("text_item",new JSONObject().put("text","后台文字接收夹具"))));
            byte[] bytes=new JSONObject().put("ret",0).put("msgs",new JSONArray().put(message)).put("get_updates_buf","cursor-1").toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception failure){throw new java.io.IOException(failure);}finally{exchange.close();}});http.start();
        try(ChannelsModule module=module("http://127.0.0.1:"+http.getAddress().getPort())){
            String profile=module.handle("profile.save",new JSONObject().put("name","synthetic iLink").put("provider","weixin").put("appId","bot@im.bot").put("secret","wx-fixture-secret").put("enabled",true)).getString("id");FixtureService.module=module;String session=module.prepareBackgroundReceiving(new JSONArray().put(profile),true).getString("sessionId");
            try(ServiceFixture controller=new ServiceFixture()){
                FixtureService service=controller.get();service.onStartCommand(new Intent(ChannelReceiveService.START).putExtra("sessionId",session),0,1);await(()->module.backgroundState().getInt("authenticatedReceivers")==1&&module.snapshot().getJSONArray("inbox").length()==1);assertTrue(blocked.await(3,TimeUnit.SECONDS));PowerManager.WakeLock wake=ShadowPowerManager.getLatestWakeLock();assertTrue(wake.isHeld());
                Shadows.shadowOf(context.getSystemService(NotificationManager.class)).setNotificationsEnabled(false);await(()->!module.backgroundState().getBoolean("foregroundServicePresent"));assertEquals("blocked",module.backgroundState().getString("status"));assertEquals(0,module.backgroundState().getInt("runningReceivers"));assertFalse(wake.isHeld());assertFalse(module.snapshot().toString().contains("private-wx-context"));assertEquals(0,module.snapshot().getJSONArray("taskRequests").length());
            }
        }finally{release.countDown();http.stop(0);}
    }
    @Test public void foregroundPromotionFailureDoesNotStartReceiversAndKeepsExplicitFailureState()throws Exception {
        AtomicInteger network=new AtomicInteger();try(Endpoint endpoint=new Endpoint("ws://127.0.0.1:1/websocket",network);ChannelsModule module=module(endpoint.base())){
            FixtureService.module=module;String session=module.prepareBackgroundReceiving(new JSONArray().put(profile(module)),true).getString("sessionId");
            try(ServiceFixture controller=new ServiceFixture()){
                FixtureService service=controller.get();Shadows.shadowOf(service).setThrowInStartForeground(new SecurityException("synthetic denied FGS"));service.onStartCommand(new Intent(ChannelReceiveService.START).putExtra("sessionId",session),0,1);await(()->Shadows.shadowOf(service).isStoppedBySelf());assertEquals(0,network.get());assertEquals("blocked",module.backgroundState().getString("status"));assertFalse(module.backgroundState().getBoolean("foregroundServicePresent"));
            }
        }
    }
    @Test public void visibleActivityConfirmationAndNotificationPermissionAreRequiredBeforeReservation()throws Exception {
        AtomicInteger network=new AtomicInteger();try(Endpoint endpoint=new Endpoint("ws://127.0.0.1:1/websocket",network);ChannelsModule module=module(endpoint.base());ActivityController<Activity> activity=Robolectric.buildActivity(Activity.class).setup().visible().windowFocusChanged(true)){
            installServiceMetadata();
            JSONArray selected=new JSONArray().put(profile(module));assertThrows(SecurityException.class,()->ChannelReceiveService.startFromVisibleActivity(activity.get(),module,selected,false));assertEquals("stopped",module.backgroundState().getString("status"));
            Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);assertThrows(SecurityException.class,()->ChannelReceiveService.startFromVisibleActivity(activity.get(),module,selected,true));assertEquals("stopped",module.backgroundState().getString("status"));
            Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);JSONObject result=ChannelReceiveService.startFromVisibleActivity(activity.get(),module,selected,true);assertFalse(result.getBoolean("foregroundEstablished"));Intent pending=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();assertEquals(ChannelReceiveService.START,pending.getAction());assertEquals(result.getString("sessionId"),pending.getStringExtra("sessionId"));assertEquals(0,network.get());module.stopBackgroundReceiving(result.getString("sessionId"));
        }
    }
    @Test public void nativeConfirmationDialogCanStartWhileResumedButStoppedActivityCannot()throws Exception {
        AtomicInteger network=new AtomicInteger();try(Endpoint endpoint=new Endpoint("ws://127.0.0.1:1/websocket",network);ChannelsModule module=module(endpoint.base());ActivityController<androidx.activity.ComponentActivity> activity=Robolectric.buildActivity(androidx.activity.ComponentActivity.class).setup().visible().windowFocusChanged(false)){
            installServiceMetadata();JSONArray selected=new JSONArray().put(profile(module));assertFalse(activity.get().hasWindowFocus());assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED,activity.get().getLifecycle().getCurrentState());
            JSONObject ticket=ChannelReceiveService.startFromVisibleActivity(activity.get(),module,selected,true);module.stopBackgroundReceiving(ticket.getString("sessionId"));activity.pause().stop();
            assertThrows(SecurityException.class,()->ChannelReceiveService.startFromVisibleActivity(activity.get(),module,selected,true));assertEquals("stopped",module.backgroundState().getString("status"));assertEquals(0,network.get());
        }
    }
    private void installServiceMetadata(){ServiceInfo info=new ServiceInfo();info.name=ChannelReceiveService.class.getName();info.packageName=context.getPackageName();info.applicationInfo=context.getApplicationInfo();info.enabled=true;info.exported=false;ReflectionHelpers.setField(info,"mForegroundServiceType",ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);Shadows.shadowOf(context.getPackageManager()).addOrUpdateService(info);}
    private static final class ServiceFixture implements AutoCloseable {final ServiceController<FixtureService> controller=Robolectric.buildService(FixtureService.class).create();FixtureService get(){return controller.get();}public void close(){controller.destroy();}}
    public static final class FixtureService extends ChannelReceiveService {static ChannelsModule module;@Override protected ChannelsModule resolveChannels(){return module;}}
    private ChannelsModule module(String endpoint)throws Exception {return new ChannelsModule(new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{}),new ChannelHttp(endpoint),new QqGatewayClient(true),new WeixinClient(endpoint));}
    private String profile(ChannelsModule module)throws Exception{return module.handle("profile.save",new JSONObject().put("name","synthetic QQ").put("provider","qq").put("appId","12345").put("secret","fixture-secret").put("enabled",true)).getString("id");}
    private static void await(QqGatewayTransportTest.Check condition)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25));if(condition.get())return;Thread.sleep(10);}assertTrue("Timed out awaiting service fixture",condition.get());}
    private static final class Endpoint implements AutoCloseable {
        final HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        Endpoint(String gateway,AtomicInteger requests)throws Exception {http.createContext("/",exchange->{try{requests.incrementAndGet();exchange.getRequestBody().readAllBytes();JSONObject response=exchange.getRequestURI().getPath().equals("/gateway")?new JSONObject().put("url",gateway):new JSONObject().put("access_token","fixture-token").put("expires_in",7200);byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception failed){throw new java.io.IOException(failed);}finally{exchange.close();}});http.start();}
        String base(){return "http://127.0.0.1:"+http.getAddress().getPort();}public void close(){http.stop(0);}
    }
}
