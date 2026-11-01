package dev.pocketengine.phone;

import static org.junit.Assert.*;
import android.os.Looper;
import android.widget.Button;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
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

/** Real native overlay Button listeners and original App/TaskStore, no replacement control store. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=EngineApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PhoneOwnerControlsTest {
    private EngineApplication app;
    private PhoneAccessibilityService service;
    private String id,run;
    private int epoch;
    private RunControl original;
    private static void set(Object target,String name,Object value)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static Object get(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void prepare(boolean paused)throws Exception {
        app=(EngineApplication)RuntimeEnvironment.getApplication();JSONObject task=app.tasks.startAttempt(app.tasks.create("暂停后继续原目标","main_screen").getString("id"),"main_screen",6);
        id=task.getString("id");run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");original=new RunControl();original.bindHostRun(id,run,epoch);
        set(app,"activeTaskId",id);set(app,"activeControl",original);set(app,"activeMode","main_screen");
        service=Robolectric.buildService(PhoneAccessibilityService.class).create().get();Method connect=PhoneAccessibilityService.class.getDeclaredMethod("onServiceConnected");connect.setAccessible(true);connect.invoke(service);
        if(paused)original.pause();service.showControls(id,"暂停后继续原目标",original);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull("native overlay unavailable in fixture",get(service,"controls"));
    }
    @After public void close()throws Exception {
        if(app!=null){if(id!=null)app.stop(id);if(app.features!=null)app.features.close();}
        if(service!=null)service.onDestroy();Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void nativePauseUpdatesTheOriginalTaskRecord()throws Exception {
        prepare(false);((Button)get(service,"pause")).performClick();
        assertTrue(original.isPaused());assertEquals("pausing",app.tasks.get(id,false).getString("status"));
        assertEquals(run,app.tasks.get(id,false).getString("runId"));assertSame(original,app.control(id));
    }
    @Test public void nativeContinueCannotUnpauseUnknownAndMustLeaveOwnerReconciliationUsable()throws Exception {
        prepare(false);RunJournal journal=app.tasks.journal(id,run,epoch,original);
        String op=journal.beginOperation("phone_tap",new JSONObject().put("observationId","fixture").put("x",200).put("y",300));
        journal.finishOperation(op,new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("error","action_unknown"));
        original.pause();app.tasks.status(id,"reconciling","核对原动作");app.pushTask(id);Shadows.shadowOf(Looper.getMainLooper()).idle();
        ((Button)get(service,"pause")).performClick();
        assertTrue("native continue must preserve paused unknown barrier",original.isPaused());assertTrue(app.tasks.hasUnresolved(id));
        app.dispatchOwnerAction("resolveOperation",new JSONObject().put("taskId",id).put("operationId",op).put("outcome","occurred"));
        assertFalse(app.tasks.hasUnresolved(id));assertEquals(run,app.tasks.get(id,false).getString("runId"));
    }
    @Test public void originalTaskPauseEventsRefreshNativeButtonAndRepeatedMountsDoNotLeakObservers()throws Exception {
        prepare(false);int count=((java.util.Collection<?>)get(app,"observers")).size();
        app.pause();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("继续",((Button)get(service,"pause")).getText().toString());
        for(int i=0;i<8;i++)service.showControls(id,"原目标",original);
        assertEquals(count,((java.util.Collection<?>)get(app,"observers")).size());
        service.hideControls();assertEquals(count-1,((java.util.Collection<?>)get(app,"observers")).size());
        app.pushTask(id);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(get(service,"controls"));
    }
    @Test public void staleNativeListenersAndLateShowCannotAffectOrRemoveTheNewOriginalRun()throws Exception {
        prepare(false);Button stale=(Button)get(service,"pause");RunControl old=original;
        app.tasks.endAttempt(id,run,"cancelled","fixture old owner released",new JSONObject());JSONObject next=app.tasks.startAttempt(id,"main_screen",6);run=next.getString("runId");epoch=next.getInt("coordinatorEpoch");
        original=new RunControl();original.bindHostRun(id,run,epoch);set(app,"activeControl",original);service.showControls(id,"新原任务",original);Button current=(Button)get(service,"pause");
        stale.performClick();assertFalse(original.isPaused());assertEquals("preparing",app.tasks.get(id,false).getString("status"));
        service.showControls(id,"迟到旧任务",old);assertSame(current,get(service,"pause"));assertSame(original,app.control(id));
    }
    @Test public void nativeStopUsesOriginalHostRecordAndCancellationRemovesItsObserver()throws Exception {
        prepare(false);int count=((java.util.Collection<?>)get(app,"observers")).size();
        android.widget.LinearLayout panel=(android.widget.LinearLayout)get(service,"controls"),buttons=(android.widget.LinearLayout)panel.getChildAt(1);
        ((Button)buttons.getChildAt(1)).performClick();assertTrue(original.isCancelled());assertEquals("stopping",app.tasks.get(id,false).getString("status"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(get(service,"controls"));assertEquals(count-1,((java.util.Collection<?>)get(app,"observers")).size());
    }
    @Test public void terminalReceiptBeforeServiceReleaseIsPreservedAgainstLateNativeButtons()throws Exception {
        prepare(false);Button latePause=(Button)get(service,"pause");android.widget.LinearLayout panel=(android.widget.LinearLayout)get(service,"controls");
        Button lateStop=(Button)((android.widget.LinearLayout)panel.getChildAt(1)).getChildAt(1);
        app.tasks.endAttempt(id,run,"needs_confirmation","平台回执已结束，待主人验收",new JSONObject());
        latePause.performClick();assertFalse(original.isPaused());assertEquals("needs_confirmation",app.tasks.get(id,false).getString("status"));
        lateStop.performClick();assertTrue(original.isCancelled());assertEquals("needs_confirmation",app.tasks.get(id,false).getString("status"));
    }
}
