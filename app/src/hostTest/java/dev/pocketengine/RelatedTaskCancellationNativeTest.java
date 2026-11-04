package dev.pocketengine;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual capability cancellation entry, invoked reflectively; only startup/presentation are shadowed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=RelatedTaskCancellationNativeTest.PresentationBoundary.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class RelatedTaskCancellationNativeTest {
    @Implements(EngineApplication.class)
    public static final class PresentationBoundary extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected void onCreate(){}
        @Implementation protected void pushTask(String id){}
    }
    private EngineApplication app;private JSONObject parent,successor;private RunControl original;
    private final List<String> stopped=new CopyOnWriteArrayList<>();private String successorChild;
    @Before @SuppressWarnings("unchecked") public void prepare()throws Exception{
        app=(EngineApplication)RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");app.tasks=new TaskStore(app);dev.pocketengine.testutil.ReadyApplicationFixture.complete(app);app.features=new FeatureHost(ignored->{},app.tasks,()->false);
        app.features.register(new EngineModule(){public String id(){return "teams";}public JSONObject snapshot(){return new JSONObject();}public JSONObject handle(String command,JSONObject input)throws Exception{assertEquals("cancel",command);String run=input.getString("runId");stopped.add(run);return new JSONObject().put("id",run).put("status","cancelled");}});
        parent=app.tasks.startAttempt(app.tasks.createCapabilityTask("original owner parent","").getString("id"),"capabilities",8);
        original=new RunControl();original.bindHostRun(parent.getString("id"),parent.getString("runId"),parent.getInt("coordinatorEpoch"));
        Field controls=EngineApplication.class.getDeclaredField("capabilityControls");controls.setAccessible(true);((ConcurrentMap<String,RunControl>)controls.get(app)).put(parent.getString("id"),original);
        successorChild=UUID.randomUUID().toString();
    }
    @After public void close()throws Exception{app.features.close();Field workers=EngineApplication.class.getDeclaredField("capabilityWorkers");workers.setAccessible(true);((java.util.concurrent.ExecutorService)workers.get(app)).shutdownNow();Field db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(app.tasks)).close();}
    private void replaceParent()throws Exception{
        // Controlled SQL admission at the real cancellation boundary, not another model/UI runner.
        app.tasks.endAttempt(parent.getString("id"),parent.getString("runId"),"cancelled","fixture original settled",new JSONObject());
        successor=app.tasks.startAttempt(parent.getString("id"),"capabilities",8);
        app.tasks.beginRelatedExternal(successor.getString("id"),successor.getString("runId"),"teams","start",successorChild,new JSONObject().put("goal","successor-only child"));
    }
    private void stop()throws Exception{
        // This is the production entry bound to the native Chat stop port. Owner UI has a separate reviewed tuple path.
        var stop=EngineApplication.class.getDeclaredMethod("cancelCapabilityAttempt",RunControl.class);stop.setAccessible(true);
        try{stop.invoke(app,original);}catch(java.lang.reflect.InvocationTargetException failed){if(failed.getCause() instanceof Exception error)throw error;if(failed.getCause() instanceof Error error)throw error;throw failed;}
    }
    private void drain()throws Exception{var done=new CompletableFuture<Void>();app.features.nativeOwnerCleanup(()->done.complete(null));done.get(5,TimeUnit.SECONDS);}
    private void assertSuccessorUntouched()throws Exception{
        assertNotNull(successor);assertNotEquals(parent.getString("runId"),successor.getString("runId"));assertTrue(original.isCancelled());
        assertEquals(successor.getString("runId"),app.tasks.get(parent.getString("id"),false).getString("runId"));
        assertFalse("Old native finally must not select the successor's child",app.tasks.get(successorChild,false).optBoolean("cancelRequested"));
        assertEquals("queued",app.tasks.get(successorChild,false).getString("status"));assertTrue(stopped.isEmpty());
    }
    @Test public void delayedCapabilityFinallyCannotChooseSuccessorParentChildren()throws Exception{
        app.setOriginalDeviceChildStops((task,control)->{assertSame(original,control);assertTrue(control.isCancelled());replaceParent();});
        stop();drain();assertSuccessorUntouched();
    }
    @Test public void failedDownstreamQueueStillCannotChooseSuccessorParentChildren()throws Exception{
        app.setOriginalDeviceChildStops((task,control)->{replaceParent();throw new java.util.concurrent.RejectedExecutionException("fixture original queue unavailable");});
        try{stop();fail("Expected the existing unknown child-queue result");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("不能视为远端已停止"));}
        drain();assertSuccessorUntouched();
    }
    @Test public void aReentrantOriginalControlCallbackCannotRetargetCleanup()throws Exception{
        original.onCancel(()->{try{replaceParent();}catch(Exception failure){throw new AssertionError(failure);}});
        app.setOriginalDeviceChildStops((task,control)->assertSame(original,control));stop();drain();assertSuccessorUntouched();
    }
    @Test public void currentNativeStopStillCancelsItsCapturedOriginalChild()throws Exception{
        String child=UUID.randomUUID().toString();app.tasks.beginRelatedExternal(parent.getString("id"),parent.getString("runId"),"teams","start",child,new JSONObject().put("goal","original child"));
        app.setOriginalDeviceChildStops((task,control)->{assertSame(original,control);assertTrue(control.isCancelled());assertTrue(app.tasks.get(task,false).getBoolean("cancelRequested"));});
        app.dispatchOwnerAction("stopTask",new JSONObject().put("taskId",parent.getString("id")).put("runId",parent.getString("runId")).put("epoch",parent.getInt("coordinatorEpoch")));drain();assertEquals(List.of(child),stopped);assertTrue(app.tasks.get(child,false).getBoolean("cancelRequested"));
    }
}
