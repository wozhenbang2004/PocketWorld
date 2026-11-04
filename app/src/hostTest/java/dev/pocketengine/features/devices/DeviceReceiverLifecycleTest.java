package dev.pocketengine.features.devices;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.PhoneRunService;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import dev.pocketengine.store.TaskStoreDeviceTrust;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowApplication;
import static org.junit.Assert.*;

/** Original Android Service lifecycle with real original SQL and bound synthetic mTLS sockets.
 * App startup alone is isolated to avoid unrelated account/UI startup. The task/control resource
 * is an explicit host fixture, NOT an AgentRunner or a physical Android/other-network AC. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=DeviceReceiverLifecycleTest.NoUnrelatedStartup.class)
public final class DeviceReceiverLifecycleTest {
    @Implements(EngineApplication.class) public static class NoUnrelatedStartup extends ShadowApplication {@Implementation protected void onCreate(){}}
    private EngineApplication app;private TaskStore tasks;private SQLiteDatabase sql;private ServiceController<PhoneRunService> controller;private PhoneRunService service;private DeviceReceiverServiceResource receiver;
    @Before public void open()throws Exception{
        app=(EngineApplication)RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);sql=(SQLiteDatabase)get(tasks,"db");TaskStoreDeviceTasks.createSchema(sql);TaskStoreDeviceTrust.createSchema(sql);app.tasks=tasks;dev.pocketengine.testutil.ReadyApplicationFixture.complete(app);app.features=new FeatureHost(event->{});Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);controller=Robolectric.buildService(PhoneRunService.class).create();service=controller.get();
    }
    @After public void close()throws Exception{
        if(receiver!=null)receiver.close();if(service!=null){RunControl original=(RunControl)get(service,"ownerControl");if(original!=null)original.cancel();set(service,"runningId",null);set(service,"runningRunId",null);set(app,"activeTaskId",null);set(app,"activeControl",null);controller.destroy();}if(app!=null&&app.features!=null)app.features.close();if(sql!=null&&sql.isOpen())sql.close();Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static Object get(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void set(Object object,String name,Object value)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private void call(String name,Class<?>[] types,Object... args)throws Exception{Method method=PhoneRunService.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(service,args);}
    private long count(String table){try(Cursor row=sql.rawQuery("SELECT COUNT(*) FROM "+table,null)){row.moveToFirst();return row.getLong(0);}}
    private record NativeTask(String id,String run,int epoch,RunControl original){ }
    private NativeTask task()throws Exception{
        JSONObject task=tasks.create("Explicit native service ownership fixture","main_screen");String id=task.getString("id");tasks.configureExecutionScope(id,0,System.currentTimeMillis()+300000,20,20);task=tasks.startAttempt(id,"main_screen",20);RunControl original=new RunControl();original.bindHostRun(id,task.getString("runId"),task.getInt("coordinatorEpoch"));tasks.status(id,"running","original task fixture");set(app,"activeTaskId",id);set(app,"activeControl",original);set(service,"runningId",id);set(service,"runningRunId",task.getString("runId"));set(service,"runningEpoch",task.getInt("coordinatorEpoch"));set(service,"ownerControl",original);set(service,"lastStartId",10);service.startForeground(100,new Notification.Builder(service,"phone_tasks").setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Original task fixture").build());return new NativeTask(id,task.getString("runId"),task.getInt("coordinatorEpoch"),original);
    }
    private static SSLContext syntheticMutualContext()throws Exception{
        Method make=DeviceIngressNativeTest.class.getDeclaredMethod("certificate");make.setAccessible(true);Object first=make.invoke(null),second=make.invoke(null);Method context=DeviceIngressNativeTest.class.getDeclaredMethod("context",first.getClass(),first.getClass());context.setAccessible(true);return (SSLContext)context.invoke(null,first,second);
    }
    private DeviceReceiverServiceResource enable(int startId)throws Exception{
        SSLContext context=syntheticMutualContext();AtomicBoolean enabled=new AtomicBoolean(true);receiver=new DeviceReceiverServiceResource(new DeviceReceiverServiceResource.Backend(){private SSLServerSocket control,bulk;public void start()throws Exception{control=(SSLServerSocket)context.getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress());bulk=(SSLServerSocket)context.getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress());control.setNeedClientAuth(true);bulk.setNeedClientAuth(true);}public boolean isListening(){return control!=null&&bulk!=null&&control.isBound()&&bulk.isBound()&&!control.isClosed()&&!bulk.isClosed();}public void close()throws Exception{if(control!=null)control.close();if(bulk!=null)bulk.close();}},new DeviceReceiverServiceResource.OwnerPolicy(){public void requireEnabledCurrent(){if(!enabled.get())throw new SecurityException("native fixture owner disabled");}public void disableByOwner(){enabled.set(false);}});set(app,"deviceReceiverResource",receiver);service.onStartCommand(new Intent(app,PhoneRunService.class).setAction(PhoneRunService.START_DEVICE_RECEIVER).putExtra("deviceReceiverResourceId",receiver.resourceId()),0,startId);await(()->receiver.snapshot().optBoolean("listening"));return receiver;
    }
    private interface Condition{boolean ready()throws Exception;}
    private static void await(Condition condition)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(!condition.ready()){Shadows.shadowOf(Looper.getMainLooper()).idle();if(System.nanoTime()>end)fail("native asynchronous lifecycle did not settle");Thread.sleep(5);}Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void receiverEnableDisableDoesNotStealOrStopOriginalTaskResource()throws Exception{
        NativeTask original=task();DeviceReceiverServiceResource same=enable(20);assertEquals(original.id,get(service,"runningId"));assertSame(original.original,get(service,"ownerControl"));assertEquals(100,Shadows.shadowOf(service).getLastForegroundNotificationId());assertNotNull(Shadows.shadowOf(app.getSystemService(NotificationManager.class)).getNotification(101));assertEquals(1,count("runs"));assertEquals(0,count("operations"));
        service.onStartCommand(new Intent(app,PhoneRunService.class).setAction(PhoneRunService.STOP_DEVICE_RECEIVER).putExtra("deviceReceiverResourceId",same.resourceId()),0,30);await(()->same.stopped()&&get(service,"receiverOnly")==null);assertFalse(original.original.isCancelled());assertEquals("running",tasks.get(original.id,false).getString("status"));assertEquals(original.id,get(service,"runningId"));assertFalse(Shadows.shadowOf(service).isForegroundStopped());assertEquals(100,Shadows.shadowOf(service).getLastForegroundNotificationId());assertEquals(30,get(service,"latestServiceStartId"));assertEquals(0,count("operations"));
    }
    @Test public void originalTaskEndDowngradesToReceiver101AndStaleSettlementCannotStopNewResource()throws Exception{
        NativeTask first=task();enable(40);call("finishAttempt",new Class<?>[]{String.class,String.class,RunControl.class,RunOutcome.class,int.class},first.id,first.run,first.original,new RunOutcome("completed","original task fixture complete",new JSONObject()),10);await(()->get(service,"runningId")==null&&app.activeId()==null);assertTrue(receiver.snapshot().getBoolean("listening"));assertEquals(101,Shadows.shadowOf(service).getLastForegroundNotificationId());assertFalse(Shadows.shadowOf(service).isForegroundStopped());assertFalse(Shadows.shadowOf(service).isStoppedBySelf());assertEquals("completed",tasks.get(first.id,false).getString("status"));
        NativeTask next=task();set(service,"latestServiceStartId",90);call("settleForegroundOwnership",new Class<?>[]{int.class},10);assertEquals(next.id,get(service,"runningId"));assertSame(next.original,get(service,"ownerControl"));assertEquals(100,Shadows.shadowOf(service).getLastForegroundNotificationId());assertFalse(next.original.isCancelled());assertTrue(receiver.snapshot().getBoolean("listening"));assertFalse(Shadows.shadowOf(service).isForegroundStopped());assertEquals(0,count("operations"));
    }
}
