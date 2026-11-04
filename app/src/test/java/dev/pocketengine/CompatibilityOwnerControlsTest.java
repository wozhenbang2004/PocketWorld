package dev.pocketengine;

import static org.junit.Assert.*;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.SettingsStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Shipped compatibility Activity, real shared SettingsStore and original app/SQLite control paths. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=EngineApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class CompatibilityOwnerControlsTest {
    private EngineApplication app;
    private ActivityController<MainActivity> lifecycle;
    private MainActivity activity;
    private static Object field(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void field(Object target,String name,Object value)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private ExecutorService worker(String name)throws Exception {return (ExecutorService)field(activity,name);}
    private void handle(JSONObject request)throws Exception {Method m=MainActivity.class.getDeclaredMethod("handle",JSONObject.class);m.setAccessible(true);m.invoke(activity,request);}
    private static JSONObject configuration(String model)throws Exception {return new JSONObject().put("baseUrl","https://example.test/v1").put("model",model).put("clearApiKey",true).put("sendScreenshots",false);}
    private void save(String model,long revision)throws Exception {handle(new JSONObject().put("action","saveConfig").put("modelSelectionRevision",revision).put("config",configuration(model)));}
    private static void drain(ExecutorService worker)throws Exception {worker.submit(()->{}).get(3,TimeUnit.SECONDS);}
    private static final class Gate implements AutoCloseable {
        private final CountDownLatch release=new CountDownLatch(1);
        Gate(ExecutorService worker)throws Exception {CountDownLatch entered=new CountDownLatch(1);worker.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});assertTrue(entered.await(3,TimeUnit.SECONDS));}
        @Override public void close(){release.countDown();}
    }
    @Before public void prepare()throws Exception {
        app=(EngineApplication)RuntimeEnvironment.getApplication();app.settings.save(configuration("original-model"));
        lifecycle=Robolectric.buildActivity(MainActivity.class).create();activity=lifecycle.get();
    }
    @After public void close()throws Exception {
        if(lifecycle!=null)lifecycle.destroy();
        if(app!=null){if(app.activeId()!=null)app.stop(app.activeId());if(app.features!=null)app.features.close();}
    }
    private RunControl install(JSONObject task)throws Exception {
        RunControl control=new RunControl();control.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));
        field(app,"activeTaskId",task.getString("id"));field(app,"activeControl",control);field(app,"activeMode","background");return control;
    }
    @Test public void queuedCompatibilityControlsCannotFollowAReplacementOfTheSameTask()throws Exception {
        ExecutorService controls=worker("controls");
        for(String action:new String[]{"pauseRun","resumeRun","stopRun"}){
            JSONObject task=app.tasks.startAttempt(app.tasks.create("原兼容页任务","background").getString("id"),"background",6);
            String id=task.getString("id"),run=task.getString("runId");RunControl original=install(task);if(action.equals("resumeRun"))original.pause();RunControl replacement;
            try(Gate gate=new Gate(controls)){
                handle(new JSONObject().put("action",action).put("taskId",id).put("runId",run).put("epoch",task.getInt("coordinatorEpoch")));
                app.tasks.endAttempt(id,run,"paused","original attempt ended",new JSONObject());replacement=install(app.tasks.startAttempt(id,"background",6));
            }
            drain(controls);assertFalse(replacement.isCancelled());assertFalse(replacement.isPaused());assertFalse(original.isCancelled());
            assertEquals(action.equals("resumeRun"),original.isPaused());assertEquals("preparing",app.tasks.get(id,false).getString("status"));
            app.tasks.endAttempt(id,app.tasks.get(id,false).getString("runId"),"cancelled","fixture cleanup",new JSONObject());field(app,"activeTaskId",null);field(app,"activeControl",null);
        }
    }
    @Test public void compatibilityControlsRequireIdentityAndReachOnlyTheOriginalNativeRun()throws Exception {
        JSONObject task=app.tasks.startAttempt(app.tasks.create("原控制回执","background").getString("id"),"background",6);RunControl original=install(task);ExecutorService controls=worker("controls");
        handle(new JSONObject().put("action","stopRun"));drain(controls);assertFalse(original.isCancelled());
        JSONObject request=new JSONObject().put("action","pauseRun").put("taskId",task.getString("id")).put("runId",task.getString("runId")).put("epoch",task.getInt("coordinatorEpoch"));
        handle(request);drain(controls);assertTrue(original.isPaused());assertEquals("pausing",app.tasks.get(task.getString("id"),false).getString("status"));
        request.put("action","stopRun");handle(request);drain(controls);assertTrue(original.isCancelled());assertEquals("stopping",app.tasks.get(task.getString("id"),false).getString("status"));
    }
    @Test public void queuedCompatibilitySaveReservesTheSharedSelectionBeforeIoStarts()throws Exception {
        ExecutorService io=worker("io");long revision=app.settings.modelSelection().status().revision();
        try(Gate gate=new Gate(io)){
            save("edited-model",revision);assertTrue(app.settings.modelSelection().status().pending());
            assertThrows(IllegalStateException.class,app.settings::get);assertThrows(IllegalStateException.class,new SettingsStore(app)::get);
            save("duplicate-write",revision);
        }
        drain(io);assertFalse(app.settings.modelSelection().status().pending());assertEquals("edited-model",app.settings.get().model);
    }
    @Test public void compatibilitySaveRechecksTheSelectionAfterItLeavesTheQueue()throws Exception {
        ExecutorService io=worker("io");long revision=app.settings.modelSelection().status().revision();
        try(Gate gate=new Gate(io)){
            save("stale-draft",revision);
            // A confirmed store change outside this picker still invalidates its queued draft.
            app.settings.save(configuration("newer-confirmed-selection"));
        }
        drain(io);assertFalse(app.settings.modelSelection().status().pending());assertEquals("newer-confirmed-selection",app.settings.get().model);
        save("late-stale-draft",revision);drain(io);assertEquals("newer-confirmed-selection",app.settings.get().model);
    }
    @Test public void destroyingTheCompatibilityActivityReleasesOnlyItsUnstartedSave()throws Exception {
        ExecutorService io=worker("io");long revision=app.settings.modelSelection().status().revision();
        try(Gate gate=new Gate(io)){
            save("must-not-be-written",revision);assertTrue(app.settings.modelSelection().status().pending());
            lifecycle.destroy();lifecycle=null;assertFalse(app.settings.modelSelection().status().pending());
        }
        assertTrue(io.awaitTermination(3,TimeUnit.SECONDS));assertEquals("original-model",app.settings.get().model);
    }
}
