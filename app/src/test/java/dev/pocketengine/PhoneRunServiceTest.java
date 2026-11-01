package dev.pocketengine;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Looper;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.core.PhonePort;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=EngineApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PhoneRunServiceTest {
    @Test public void revokedNotificationRuntimePermissionBlocksBackgroundBeforeTheWorkerIsAdmitted()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager notifications=app.getSystemService(NotificationManager.class);shadowOf(notifications).setNotificationsEnabled(true);
        Field serviceInstance=dev.pocketengine.phone.PhoneAccessibilityService.class.getDeclaredField("instance");serviceInstance.setAccessible(true);serviceInstance.set(null,null);
        String id=app.tasks.create("授权撤回后不派发后台执行","background").getString("id");JSONObject attempt=app.tasks.startAttempt(id,"background",4);
        RunControl original=new RunControl();original.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));
        set(app,"activeTaskId",id);set(app,"activeControl",original);set(app,"activeMode","background");
        set(app,"activeConfig",new ModelConfig("https://model.invalid","fixture-key","fixture-model",false,4,5));
        ServiceController<PhoneRunService> controller=Robolectric.buildService(PhoneRunService.class).create();
        // This retained placeholder is intentionally unable to execute. The permission gate must
        // settle awaiting_user before admitting even a worker, rather than failing later in run().
        PhonePort held=new PhonePort(){public boolean isConnected(){return true;}public JSONObject observe(boolean image,RunControl c){throw new AssertionError("permission-denied worker observed a screen");}public JSONObject execute(String tool,JSONObject args,RunControl c){throw new AssertionError("permission-denied worker dispatched an action");}public void clearReferences(){}};
        set(controller.get(),"heldPort",held);
        try {
            controller.get().onStartCommand(new Intent(app,PhoneRunService.class).setAction(PhoneRunService.START).putExtra("taskId",id),0,1);
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(750));
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while(app.activeId()!=null&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(2);}
            assertNull(app.activeId());assertEquals("awaiting_user",app.tasks.get(id,false).getString("status"));
            assertEquals("停止入口不可用，请开启任务通知或重新启用手机操作服务",app.tasks.get(id,false).getString("summary"));
        } finally {controller.destroy();}
    }
    @Test public void taskAdmissionStaysClosedUntilServiceOwnershipIsAlsoReleased() throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        String id=app.tasks.create("检查收尾状态").getString("id");
        ServiceController<PhoneRunService> controller=Robolectric.buildService(PhoneRunService.class).create();
        PhoneRunService service=controller.get();
        set(app,"activeTaskId",id);set(app,"activeControl",new RunControl());set(service,"runningId",id);
        Method finish=PhoneRunService.class.getDeclaredMethod("finish",String.class,RunOutcome.class,int.class);finish.setAccessible(true);
        finish.invoke(service,id,new RunOutcome("needs_confirmation","请检查结果",new JSONObject()),1);
        assertEquals(id,app.activeId()); // Another start cannot occupy the app while the service still looks busy.
        assertEquals(id,get(service,"runningId"));
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(app.activeId());assertNull(get(service,"runningId"));
        controller.destroy();
    }
    @Test public void destructionBeforeDelayedWorkerStartReleasesTheTaskSlot() throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        String id=app.tasks.create("等待启动的任务").getString("id");
        RunControl control=new RunControl();
        ServiceController<PhoneRunService> controller=Robolectric.buildService(PhoneRunService.class).create();
        set(app,"activeTaskId",id);set(app,"activeControl",control);set(controller.get(),"runningId",id);
        controller.destroy();
        assertTrue(control.isCancelled());assertNull(app.activeId());
        assertEquals("interrupted",app.tasks.get(id,false).getString("status"));
    }
    @Test public void lateOldServiceCompletionCannotFinishOrReleaseAResumedAttempt()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        String id=app.tasks.create("继续同一个任务").getString("id");JSONObject old=app.tasks.startAttempt(id,"main_screen",20);
        RunControl oldControl=new RunControl();set(app,"activeTaskId",id);set(app,"activeControl",oldControl);
        ServiceController<PhoneRunService> controller=Robolectric.buildService(PhoneRunService.class).create();PhoneRunService service=controller.get();
        set(service,"runningId",id);set(service,"runningRunId",old.getString("runId"));controller.destroy();
        JSONObject resumed=app.tasks.startAttempt(id,"main_screen",20);RunControl newControl=new RunControl();
        set(app,"activeTaskId",id);set(app,"activeControl",newControl);
        Method finish=PhoneRunService.class.getDeclaredMethod("finishAttempt",String.class,String.class,RunControl.class,RunOutcome.class,int.class);finish.setAccessible(true);
        finish.invoke(service,id,old.getString("runId"),oldControl,new RunOutcome("cancelled","old completion",new JSONObject()),1);
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(newControl,app.control(id));assertFalse(newControl.isCancelled());
        assertEquals(resumed.getString("runId"),app.tasks.get(id,false).getString("runId"));
        assertEquals("preparing",app.tasks.get(id,false).getString("status"));
    }
    @Test public void aPreviousTakeoverTimeoutCannotCancelTheLaterWaitOfTheSameTask()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();String id=app.tasks.create("等待机主登录","background").getString("id");JSONObject attempt=app.tasks.startAttempt(id,"background",20);
        RunControl control=new RunControl();set(app,"activeTaskId",id);set(app,"activeControl",control);
        ServiceController<PhoneRunService> controller=Robolectric.buildService(PhoneRunService.class).create();PhoneRunService service=controller.get();
        set(service,"runningId",id);set(service,"runningRunId",attempt.getString("runId"));set(service,"ownerControl",control);
        PhonePort port=new PhonePort(){public boolean isConnected(){return true;}public JSONObject observe(boolean image,RunControl c){return new JSONObject();}public JSONObject execute(String tool,JSONObject args,RunControl c){return new JSONObject();}public void clearReferences(){}};
        Method hold=PhoneRunService.class.getDeclaredMethod("holdForOwner",String.class,PhonePort.class,RunControl.class,RunOutcome.class);hold.setAccessible(true);
        hold.invoke(service,id,port,control,new RunOutcome("awaiting_user","等待登录",new JSONObject()));
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(4));
        hold.invoke(service,id,port,control,new RunOutcome("awaiting_user","等待新选择",new JSONObject()));
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(61));
        assertFalse(control.isCancelled());assertSame(control,app.control(id));
        controller.destroy();
    }
    private static void set(Object target,String field,Object value)throws Exception {Field f=target.getClass().getDeclaredField(field);f.setAccessible(true);f.set(target,value);}
    private static Object get(Object target,String field)throws Exception {Field f=target.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(target);}
}
