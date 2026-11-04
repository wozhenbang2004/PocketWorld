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

    @Test public void queuedOldPhoneControlsRejectTheReplacementAttempt()throws Exception {
        prepare();String oldRun=run;int oldEpoch=epoch;RunControl old=original;
        app.tasks.endAttempt(id,run,"paused","original ended",new JSONObject());install(app.tasks.startAttempt(id,"background",6));
        assertThrows(SecurityException.class,()->app.pauseOriginalPhoneRun(id,oldRun,oldEpoch));
        assertThrows(SecurityException.class,()->app.stopOriginalPhoneRun(id,oldRun,oldEpoch));
        assertThrows(SecurityException.class,()->app.resumeOriginalPhoneRun(id,oldRun,oldEpoch));
        assertFalse(original.isCancelled());assertFalse(original.isPaused());assertFalse(old.isCancelled());
        assertEquals("preparing",app.tasks.get(id,false).getString("status"));
    }
    @Test public void pauseWaitingForTasksDoesNotHoldApplicationMonitorAndCannotPauseLaterRun()throws Exception {
        prepare();String oldRun=run;int oldEpoch=epoch;RunControl old=original;
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        Thread request=new Thread(()->{try{app.pauseOriginalPhoneRun(id,oldRun,oldEpoch);}catch(Throwable error){failure.set(error);}},"original-pause-request");request.setDaemon(true);
        var probe=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"application-monitor-probe");t.setDaemon(true);return t;});
        try{
            synchronized(app.tasks){
                request.start();long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                while(request.getState()!=Thread.State.BLOCKED&&request.isAlive()&&System.nanoTime()<until)Thread.sleep(2);
                assertEquals("pause must reach the real TaskStore monitor",Thread.State.BLOCKED,request.getState());
                assertTrue(probe.submit(()->{synchronized(app){return true;}}).get(2,java.util.concurrent.TimeUnit.SECONDS));
                app.tasks.endAttempt(id,run,"paused","original ended while waiting",new JSONObject());install(app.tasks.startAttempt(id,"background",6));
            }
            request.join(2000);assertFalse(request.isAlive());assertTrue(String.valueOf(failure.get()),failure.get() instanceof SecurityException);
            assertFalse(old.isPaused());assertFalse(original.isPaused());assertEquals("preparing",app.tasks.get(id,false).getString("status"));
        }finally{probe.shutdownNow();}
    }
    @Test public void oldServiceDestructionCannotStopOrReleaseTheReplacementRun()throws Exception {
        prepare();RunControl old=original;String oldRun=run;int oldEpoch=epoch;
        app.tasks.endAttempt(id,oldRun,"paused","old service ended",new JSONObject());JSONObject next=app.tasks.startAttempt(id,"background",6);
        RunControl replacement=new RunControl();replacement.bindHostRun(id,next.getString("runId"),next.getInt("coordinatorEpoch"));
        set(app,"activeControl",replacement); // Leave the Service bound to the old captured identity.
        service.onDestroy();service=null;
        assertTrue(old.isCancelled());assertFalse(replacement.isCancelled());assertSame(replacement,app.control(id));assertEquals(id,app.activeId());
        assertEquals(next.getString("runId"),app.tasks.get(id,false).getString("runId"));assertEquals("preparing",app.tasks.get(id,false).getString("status"));
    }
    @Test public void delayedContinueWakeCannotTouchTheSameTasksNewRun()throws Exception {
        prepare();Intent oldWake=new Intent(app,PhoneRunService.class).setAction(PhoneRunService.CONTINUE).putExtra("taskId",id).putExtra("phoneRunId",run).putExtra("phoneEpoch",epoch);
        app.tasks.endAttempt(id,run,"paused","old attempt ended",new JSONObject());install(app.tasks.startAttempt(id,"background",6));set(service,"waitingForUser",true);
        service.onStartCommand(oldWake,0,20);
        assertFalse(original.isPaused());assertFalse(original.isCancelled());assertEquals("preparing",app.tasks.get(id,false).getString("status"));
    }
    @Test public void resumeRetainsTheOriginalBudgetAndWakeIdentity()throws Exception {
        prepare();app.tasks.initializeExecutionScope(id,6);JSONObject before=app.tasks.get(id,false).getJSONObject("executionScope");
        app.pauseOriginalPhoneRun(id,run,epoch);JSONObject resumed=app.resumeOriginalPhoneRun(id,run,epoch);
        JSONObject after=app.tasks.get(id,false).getJSONObject("executionScope");assertEquals(before.toString(),after.toString());assertFalse(resumed.getBoolean("budgetIncreased"));
        Intent wake=Shadows.shadowOf(app).getNextStartedService();assertNotNull(wake);assertEquals(run,wake.getStringExtra("phoneRunId"));assertEquals(epoch,wake.getIntExtra("phoneEpoch",-1));
    }
    @Test public void coldContinuationUsesRemainingScopeAndRejectsStaleFeedbackAtomically()throws Exception {
        prepare();app.tasks.initializeExecutionScope(id,6);String oldRun=run;int oldEpoch=epoch;
        JSONObject before=app.tasks.get(id,false).getJSONObject("executionScope");app.tasks.endAttempt(id,run,"paused","owner paused",new JSONObject());
        JSONObject continued=app.tasks.startOriginalPhoneAttempt(id,run,epoch,"background",40);
        assertNotEquals(oldRun,continued.getString("runId"));assertEquals(oldEpoch+1,continued.getInt("coordinatorEpoch"));
        JSONObject after=continued.getJSONObject("executionScope");assertEquals(before.getInt("toolLimit"),after.getInt("toolLimit"));assertEquals(before.getInt("toolsUsed"),after.getInt("toolsUsed"));assertEquals(before.getInt("modelCallLimit"),after.getInt("modelCallLimit"));assertEquals(before.getInt("modelCallsUsed"),after.getInt("modelCallsUsed"));
        assertThrows(SecurityException.class,()->app.tasks.startOriginalPhoneAttempt(id,oldRun,oldEpoch,"background",40,null,null,"stale feedback"));
        assertFalse(app.tasks.get(id,false).optString("feedback").contains("stale feedback"));
        install(continued);
    }

}
