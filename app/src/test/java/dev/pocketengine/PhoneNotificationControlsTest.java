package dev.pocketengine;

import static org.junit.Assert.*;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Looper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.phone.PhoneNotificationControls;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Actual native notification/PendingIntent identity, Service command and original App/TaskStore. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=EngineApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PhoneNotificationControlsTest {
    private EngineApplication app;private PhoneRunService service;private String id,run;private int epoch;private RunControl original;
    private static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private void prepare()throws Exception {
        app=(EngineApplication)RuntimeEnvironment.getApplication();JSONObject task=app.tasks.startAttempt(app.tasks.create("后台原任务控制","background").getString("id"),"background",6);
        id=task.getString("id");service=Robolectric.buildService(PhoneRunService.class).create().get();install(task);
    }
    private void install(JSONObject task)throws Exception {
        run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");original=new RunControl();original.bindHostRun(id,run,epoch);
        set(app,"activeTaskId",id);set(app,"activeControl",original);set(app,"activeMode","background");
        set(service,"runningId",id);set(service,"runningRunId",run);set(service,"runningEpoch",epoch);set(service,"ownerControl",original);
    }
    private Notification notification()throws Exception {Method m=PhoneRunService.class.getDeclaredMethod("notification",JSONObject.class);m.setAccessible(true);return (Notification)m.invoke(service,app.tasks.get(id,false));}
    private static PendingIntent stop(Notification n){for(Notification.Action a:n.actions)if("停止".contentEquals(a.title))return a.actionIntent;throw new AssertionError("native stop action absent");}
    private static PendingIntent action(Notification n,String title){for(Notification.Action a:n.actions)if(title.contentEquals(a.title))return a.actionIntent;throw new AssertionError("native action absent: "+title);}
    private void deliver(PendingIntent action)throws Exception {action.send();Intent command=Shadows.shadowOf(app).getNextStartedService();assertNotNull(command);service.onStartCommand(command,0,10);}
    @After public void close()throws Exception {if(app!=null&&app.activeId()!=null)app.stop(app.activeId());if(service!=null)service.onDestroy();if(app!=null&&app.features!=null)app.features.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void previousRunNotificationCannotStopTheNewAttemptOfTheSameTask()throws Exception {
        prepare();PendingIntent old=stop(notification());app.tasks.endAttempt(id,run,"cancelled","fixture previous attempt ended",new JSONObject());
        install(app.tasks.startAttempt(id,"background",6));PendingIntent current=stop(notification());deliver(old);
        assertFalse("old native notification must not cancel the new run",original.isCancelled());assertEquals("preparing",app.tasks.get(id,false).getString("status"));assertEquals(run,app.tasks.get(id,false).getString("runId"));
        deliver(current);assertTrue(original.isCancelled());assertEquals("stopping",app.tasks.get(id,false).getString("status"));
    }
    @Test public void currentNativeNotificationStopsAndRecordsOnlyItsOriginalAttempt()throws Exception {
        prepare();deliver(stop(notification()));assertTrue(original.isCancelled());assertEquals("stopping",app.tasks.get(id,false).getString("status"));assertEquals(run,app.tasks.get(id,false).getString("runId"));
    }
    @Test public void nativePauseAndContinueUseOriginalHostStateAndRequestOnlyItsContinueWake()throws Exception {
        prepare();deliver(action(notification(),"暂停"));assertTrue(original.isPaused());assertEquals("pausing",app.tasks.get(id,false).getString("status"));
        deliver(action(notification(),"继续原任务"));assertFalse(original.isPaused());assertEquals("running",app.tasks.get(id,false).getString("status"));
        Intent wake=Shadows.shadowOf(app).getNextStartedService();assertNotNull(wake);assertEquals(PhoneRunService.CONTINUE,wake.getAction());assertEquals(id,wake.getStringExtra("taskId"));assertEquals(run,app.tasks.get(id,false).getString("runId"));
    }
    @Test public void staleSameRunContinueCannotBypassUnknownAndReviewOpensOnlyTheOriginalReadOnlyTask()throws Exception {
        prepare();original.pause();PendingIntent oldContinue=action(notification(),"继续原任务");original.resume();RunJournal journal=app.tasks.journal(id,run,epoch,original);
        String operation=journal.beginOperation("phone_tap",new JSONObject().put("observationId","fixture").put("x",200).put("y",300));journal.finishOperation(operation,new JSONObject().put("accepted",JSONObject.NULL).put("error","action_unknown"));original.pause();app.tasks.status(id,"reconciling","请核对原动作");
        deliver(oldContinue);assertTrue(original.isPaused());assertTrue(app.tasks.hasUnresolved(id));assertNull(Shadows.shadowOf(app).getNextStartedService());
        action(notification(),"核对原动作").send();Intent review=Shadows.shadowOf(app).getNextStartedActivity();assertNotNull(review);java.util.concurrent.atomic.AtomicReference<String> focused=new java.util.concurrent.atomic.AtomicReference<>();
        assertTrue(PhoneNotificationControls.openRequestedTask(app,review,focused::set));assertEquals(id,focused.get());assertTrue(original.isPaused());assertTrue(app.tasks.hasUnresolved(id));assertFalse(PhoneNotificationControls.openRequestedTask(app,review,focused::set));
        app.dispatchOwnerAction("resolveOperation",new JSONObject().put("taskId",id).put("operationId",operation).put("outcome","occurred"));deliver(action(notification(),"继续原任务"));assertFalse(original.isPaused());assertEquals(run,app.tasks.get(id,false).getString("runId"));
    }
    @Test public void missingOrWrongNativeRunIdentityCannotControlOrNavigateAnotherAttempt()throws Exception {
        prepare();service.onStartCommand(new Intent(app,PhoneRunService.class).setAction(PhoneRunService.STOP).putExtra("taskId",id),0,2);assertFalse(original.isCancelled());
        JSONObject task=app.tasks.get(id,false);Intent wrong=PhoneNotificationControls.command(app,PhoneRunService.STOP,task).putExtra("phoneEpoch",epoch+1);service.onStartCommand(wrong,0,3);assertFalse(original.isCancelled());
        java.util.concurrent.atomic.AtomicReference<String> focused=new java.util.concurrent.atomic.AtomicReference<>();Intent review=PhoneNotificationControls.open(app,task).putExtra("phoneRunId","another-run");assertTrue(PhoneNotificationControls.openRequestedTask(app,review,focused::set));assertNull(focused.get());assertEquals("preparing",app.tasks.get(id,false).getString("status"));
    }
    @Test public void lateOldTaskEventsCannotReplaceTheCurrentRunNotification()throws Exception {
        prepare();JSONObject previous=app.tasks.get(id,false);app.tasks.endAttempt(id,run,"cancelled","old",new JSONObject());install(app.tasks.startAttempt(id,"background",6));app.pushTask(id);Shadows.shadowOf(Looper.getMainLooper()).idle();
        android.app.NotificationManager manager=app.getSystemService(android.app.NotificationManager.class);Notification current=Shadows.shadowOf(manager).getNotification(100);assertNotNull(current);
        app.publish(new JSONObject().put("type","task").put("task",previous));Shadows.shadowOf(Looper.getMainLooper()).idle();assertSame(current,Shadows.shadowOf(manager).getNotification(100));
    }
    @Test public void lateNativeStopPreservesTheCommittedTerminalTaskReceipt()throws Exception {
        prepare();PendingIntent current=stop(notification());app.tasks.endAttempt(id,run,"needs_confirmation","待主人验收",new JSONObject());deliver(current);assertTrue(original.isCancelled());assertEquals("needs_confirmation",app.tasks.get(id,false).getString("status"));
    }
    @Test public void exactNativeEmergencyStopStillCancelsIfTheOriginalDatabaseReadFails()throws Exception {
        prepare();PendingIntent current=stop(notification());Field field=app.tasks.getClass().getDeclaredField("db");field.setAccessible(true);android.database.sqlite.SQLiteDatabase database=(android.database.sqlite.SQLiteDatabase)field.get(app.tasks);String path=database.getPath();
        database.close();try{deliver(current);assertTrue(original.isCancelled());}
        finally{field.set(app.tasks,android.database.sqlite.SQLiteDatabase.openDatabase(path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE));}
    }
    @Test public void rebuildingPausedNotificationPreservesEmergencyStopWhenDatabaseReadFails()throws Exception {
        prepare();original.pause();JSONObject retained=app.tasks.get(id,false);
        Field field=app.tasks.getClass().getDeclaredField("db");field.setAccessible(true);
        android.database.sqlite.SQLiteDatabase database=(android.database.sqlite.SQLiteDatabase)field.get(app.tasks);String path=database.getPath();
        database.close();try {
            Method method=PhoneRunService.class.getDeclaredMethod("notification",JSONObject.class);method.setAccessible(true);
            Notification rebuilt=(Notification)method.invoke(service,retained);
            deliver(stop(rebuilt));assertTrue("rebuilt notification must retain original emergency stop",original.isCancelled());
        } finally {field.set(app.tasks,android.database.sqlite.SQLiteDatabase.openDatabase(path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE));}
    }

}
