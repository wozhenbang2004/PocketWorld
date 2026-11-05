package dev.pocketengine;

import static org.junit.Assert.*;

import android.os.Looper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.media.MediaController;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import dev.pocketengine.ui.ComposeActivity;
import dev.pocketengine.ui.PocketWorldController;
import dev.pocketengine.ui.UiTask;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Retained business regressions through the one Web frontend's original Controller/native services. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,application=EngineApplication.class,shadows=ReadyApplicationFixture.NoUnrelatedStartup.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class CompatibilityOwnerControlsTest {
    private EngineApplication app;
    private PocketWorldController controller;
    private MediaController media;
    private String connectionId;
    private long connectionRevision;
    private static Object field(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void field(Object target,String name,Object value)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private ExecutorService worker(String name)throws Exception {return (ExecutorService)field(controller,name);}
    private static Method method(String name,int parameters) {return Arrays.stream(PocketWorldController.class.getDeclaredMethods()).filter(value->value.getName().startsWith(name)&&!value.getName().contains("$default")&&value.getParameterCount()==parameters).findFirst().orElseThrow();}
    private static JSONObject configuration(String model)throws Exception {return new JSONObject().put("provider","openai_chat").put("baseUrl","https://example.test/v1").put("model",model).put("clearApiKey",true).put("sendScreenshots",false);}
    private void save(String model,long revision)throws Exception {
        try {method("saveApiConnection",6).invoke(controller,false,connectionId,revision,"Original connection",configuration(model),(Function1<Object,Unit>)result->Unit.INSTANCE);}
        catch(InvocationTargetException rejected){if(!(rejected.getCause() instanceof RuntimeException))throw rejected;}
    }
    private void control(JSONObject raw,String action)throws Exception {
        UiTask task=new UiTask(raw.optString("id"),raw.optString("goal"),raw.optString("status"),raw.optString("summary"),raw.optString("executionMode","background"),raw.optLong("updatedAt"),new JSONObject(raw.toString()));
        method("controlOriginalPhone",4).invoke(controller,task,action,"",(Function2<JSONObject,String,Unit>)(receipt,failure)->Unit.INSTANCE);
    }
    private static void drain(ExecutorService worker)throws Exception {worker.submit(()->{}).get(3,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static final class Gate implements AutoCloseable {
        private final CountDownLatch release=new CountDownLatch(1);
        Gate(ExecutorService worker)throws Exception {CountDownLatch entered=new CountDownLatch(1);worker.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});assertTrue(entered.await(3,TimeUnit.SECONDS));}
        @Override public void close(){release.countDown();}
    }
    @Before public void prepare()throws Exception {
        java.security.Security.addProvider(new java.security.Provider("UnifiedOwnerControlKeyStore","1","Test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});
        app=(EngineApplication)RuntimeEnvironment.getApplication();ReadyApplicationFixture.initializeHost(app);
        app.modelConnections.addAndSelectConnection("Original connection",configuration("original-model"));
        connectionId=app.settings.apiConnectionsSnapshot().getString("selectedConnectionId");
        var rows=app.settings.apiConnectionsSnapshot().getJSONArray("connections");
        for(int i=0;i<rows.length();i++)if(connectionId.equals(rows.getJSONObject(i).getString("id")))connectionRevision=rows.getJSONObject(i).getLong("revision");
        assertTrue(connectionRevision>0);
        ComposeActivity activity=Robolectric.buildActivity(ComposeActivity.class).get();media=new MediaController(activity,app::publish);field(activity,"media",media);
        controller=new PocketWorldController(activity,app);
    }
    @After public void close()throws Exception {
        try {if(controller!=null)controller.detach();if(media!=null)media.close();if(app!=null)ReadyApplicationFixture.closeHost(app);}
        finally {java.security.Security.removeProvider("UnifiedOwnerControlKeyStore");}
    }
    private RunControl install(JSONObject task)throws Exception {
        RunControl control=new RunControl();control.bindHostRun(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"));
        field(app,"activeTaskId",task.getString("id"));field(app,"activeControl",control);field(app,"activeMode","background");return control;
    }
    @Test public void queuedUnifiedControlsCannotFollowAReplacementOfTheSameTask()throws Exception {
        ExecutorService controls=worker("controls");
        for(String action:new String[]{"pause","resume","stop"}){
            JSONObject task=app.tasks.startAttempt(app.tasks.create("原兼容页任务","background").getString("id"),"background",6);
            String id=task.getString("id"),run=task.getString("runId");RunControl original=install(task);if(action.equals("resume"))original.pause();RunControl replacement;
            try(Gate gate=new Gate(controls)){
                control(task,action);app.tasks.endAttempt(id,run,"paused","original attempt ended",new JSONObject());replacement=install(app.tasks.startAttempt(id,"background",6));
            }
            drain(controls);assertFalse(replacement.isCancelled());assertFalse(replacement.isPaused());assertFalse(original.isCancelled());
            assertEquals(action.equals("resume"),original.isPaused());assertEquals("preparing",app.tasks.get(id,false).getString("status"));
            app.tasks.endAttempt(id,app.tasks.get(id,false).getString("runId"),"cancelled","fixture cleanup",new JSONObject());field(app,"activeTaskId",null);field(app,"activeControl",null);
        }
    }
    @Test public void unifiedControlsRequireIdentityAndReachOnlyTheOriginalNativeRun()throws Exception {
        JSONObject task=app.tasks.startAttempt(app.tasks.create("原控制回执","background").getString("id"),"background",6);RunControl original=install(task);ExecutorService controls=worker("controls");
        control(new JSONObject().put("id",task.getString("id")),"stop");drain(controls);assertFalse(original.isCancelled());
        control(task,"pause");drain(controls);assertTrue(original.isPaused());assertEquals("pausing",app.tasks.get(task.getString("id"),false).getString("status"));
        control(task,"stop");drain(controls);assertTrue(original.isCancelled());assertEquals("stopping",app.tasks.get(task.getString("id"),false).getString("status"));
    }
    @Test public void queuedUnifiedSaveReservesTheSharedSelectionBeforeIoStarts()throws Exception {
        ExecutorService io=worker("io");
        try(Gate gate=new Gate(io)){
            save("edited-model",connectionRevision);assertTrue(app.settings.modelSelection().status().pending());
            assertThrows(IllegalStateException.class,app.settings::get);assertThrows(IllegalStateException.class,new SettingsStore(app)::get);
            save("duplicate-write",connectionRevision);
        }
        drain(io);assertFalse(app.settings.modelSelection().status().pending());assertEquals("edited-model",app.settings.get().model);
    }
    @Test public void unifiedSaveRechecksTheOriginalConnectionAfterItLeavesTheQueue()throws Exception {
        ExecutorService io=worker("io");
        try(Gate gate=new Gate(io)){
            save("stale-draft",connectionRevision);
            // The original native profile CAS replaces the retired global-form revision.
            app.settings.saveApiConnection(connectionId,connectionRevision,"Original connection",configuration("newer-confirmed-selection"));
        }
        drain(io);assertFalse(app.settings.modelSelection().status().pending());assertEquals("newer-confirmed-selection",app.settings.get().model);
        save("late-stale-draft",connectionRevision);drain(io);assertEquals("newer-confirmed-selection",app.settings.get().model);
    }
    @Test public void closingTheUnifiedControllerReleasesOnlyItsUnstartedSave()throws Exception {
        ExecutorService io=worker("io");
        try(Gate gate=new Gate(io)){
            save("must-not-be-written",connectionRevision);assertTrue(app.settings.modelSelection().status().pending());
            controller.detach();controller=null;assertFalse(app.settings.modelSelection().status().pending());
        }
        assertTrue(io.awaitTermination(3,TimeUnit.SECONDS));assertEquals("original-model",app.settings.get().model);
    }
}
