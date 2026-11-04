package dev.pocketengine;

import android.content.Intent;
import android.os.Looper;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
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
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=CapabilityApprovalContinuationNativeTest.PresentationBoundary.class)
public final class CapabilityApprovalContinuationNativeTest {
    /** Suppress startup I/O and control only the presentation boundary after the real SQL decision. */
    @Implements(EngineApplication.class)
    public static final class PresentationBoundary extends org.robolectric.shadows.ShadowApplication {
        static Runnable afterPush;
        @Implementation protected void onCreate(){}
        @Implementation protected void pushTask(String id){Runnable once=afterPush;afterPush=null;if(once!=null)once.run();}
    }
    private static final class Queue extends AbstractExecutorService {
        final List<Runnable> pending=new ArrayList<>();boolean closed;
        @Override public void execute(Runnable run){if(closed)throw new java.util.concurrent.RejectedExecutionException();pending.add(run);}
        @Override public void shutdown(){closed=true;}
        @Override public List<Runnable> shutdownNow(){closed=true;var result=new ArrayList<>(pending);pending.clear();return result;}
        @Override public boolean isShutdown(){return closed;}
        @Override public boolean isTerminated(){return closed&&pending.isEmpty();}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
        void pickUp(){assertEquals(1,pending.size());pending.remove(0).run();}
    }
    private EngineApplication app;private Queue queue;private final List<JSONObject> events=new ArrayList<>();
    private static void set(EngineApplication app,String name,Object value)throws Exception{Field field=EngineApplication.class.getDeclaredField(name);field.setAccessible(true);field.set(app,value);}
    private static Object get(EngineApplication app,String name)throws Exception{Field field=EngineApplication.class.getDeclaredField(name);field.setAccessible(true);return field.get(app);}
    @Before public void prepare()throws Exception{
        PresentationBoundary.afterPush=null;app=(EngineApplication)RuntimeEnvironment.getApplication();
        app.tasks=new TaskStore(app);app.settings=new SettingsStore(app);app.features=new FeatureHost(event->{},app.tasks,()->false);
        app.addObserver(events::add);
        ((ExecutorService)get(app,"capabilityWorkers")).shutdownNow();queue=new Queue();set(app,"capabilityWorkers",queue);
    }
    @After public void close()throws Exception{PresentationBoundary.afterPush=null;queue.shutdownNow();app.features.close();shadowOf(Looper.getMainLooper()).idle();Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(app.tasks)).close();}
    private JSONObject start(boolean phone)throws Exception{
        String id=(phone?app.tasks.create("isolated original phone approval","background"):app.tasks.createCapabilityTask("isolated original assistant approval","")).getString("id");
        return app.tasks.startAttempt(id,phone?"background":"capabilities",4);
    }
    private String pending(JSONObject run)throws Exception{
        return app.tasks.requireCapabilityApproval(run.getString("id"),run.getString("runId"),"workspace_write",new JSONObject().put("path","reviewed.txt").put("content","exact bytes"),new JSONObject().put("feature","workspace").put("command","write")).getString("approvalId");
    }
    private JSONObject decide(JSONObject run,String approval)throws Exception{return app.dispatchOwnerAction("approveCapability",new JSONObject().put("taskId",run.getString("id")).put("approvalId",approval).put("approved",true));}
    private void waitForDecision(JSONObject run)throws Exception{app.tasks.endAttempt(run.getString("id"),run.getString("runId"),"awaiting_user","original decision pending",new JSONObject());}

    @Test public void staleDecisionCannotQueueAContinuationOrWriteFeedbackIntoTheSuccessor()throws Exception{
        JSONObject original=start(false);String approval=pending(original);waitForDecision(original);
        JSONObject successor=app.tasks.startAttempt(original.getString("id"),"capabilities",4);waitForDecision(successor);
        assertThrows(SecurityException.class,()->decide(original,approval));
        JSONObject actual=app.tasks.get(original.getString("id"),false);
        assertEquals(successor.getString("runId"),actual.getString("runId"));assertEquals("",actual.getString("feedback"));assertTrue(queue.pending.isEmpty());
        assertEquals("pending",actual.getJSONArray("approvals").getJSONObject(0).getString("state"));
    }

    @Test public void savedDecisionCannotResumeThePhoneControlInstalledBeforeContinuation()throws Exception{
        JSONObject original=start(true);String id=original.getString("id"),approval=pending(original);app.tasks.status(id,"paused","original phone is paused for review");
        RunControl old=new RunControl();old.bindHostRun(id,original.getString("runId"),original.getInt("coordinatorEpoch"));old.pause();
        set(app,"activeTaskId",id);set(app,"activeControl",old);
        RunControl successor=new RunControl();JSONObject[] next=new JSONObject[1];
        PresentationBoundary.afterPush=()->{try{
            app.tasks.endAttempt(id,original.getString("runId"),"awaiting_user","original phone run ended after the decision was saved",new JSONObject());
            next[0]=app.tasks.startAttempt(id,"background",4);successor.bindHostRun(id,next[0].getString("runId"),next[0].getInt("coordinatorEpoch"));successor.pause();
            set(app,"activeControl",successor);
        }catch(Exception failure){throw new AssertionError(failure);}};
        JSONObject receipt=decide(original,approval);
        assertTrue("late old decision resumed the replacement phone controller",successor.isPaused());assertTrue(old.isPaused());
        assertEquals(next[0].getString("runId"),app.tasks.get(id,false).getString("runId"));
        assertEquals("",app.tasks.get(id,false).getString("feedback"));
        assertTrue(receipt.getBoolean("approvalDecisionSaved"));assertFalse(receipt.getBoolean("continuedOriginalRun"));
        assertFalse(receipt.getBoolean("continuationScheduled"));assertEquals(original.getString("runId"),receipt.getString("approvalRunId"));
        assertEquals(original.getInt("coordinatorEpoch"),receipt.getInt("approvalEpoch"));
        JSONObject saved=app.tasks.get(id,false).getJSONArray("approvals").getJSONObject(0);assertEquals("approved",saved.getString("state"));assertEquals(original.getString("runId"),saved.getString("runId"));
        assertNull(shadowOf(app).getNextStartedService());
    }

    @Test public void queuedAssistantContinuationCannotStartAnotherRunAfterTheApprovedRunChanges()throws Exception{
        JSONObject original=start(false);String id=original.getString("id"),approval=pending(original);waitForDecision(original);
        JSONObject receipt=decide(original,approval);assertEquals(1,queue.pending.size());
        JSONObject successor=app.tasks.startAttempt(id,"capabilities",4);waitForDecision(successor);
        String before=app.tasks.get(id,false).toString();queue.pickUp();shadowOf(Looper.getMainLooper()).idle();
        JSONObject after=app.tasks.get(id,false);
        assertEquals("late queued approval continuation created a fresh run",successor.getString("runId"),after.getString("runId"));
        assertEquals(successor.getInt("coordinatorEpoch"),after.getInt("coordinatorEpoch"));assertEquals(new JSONObject(before).getString("status"),after.getString("status"));
        assertEquals(0,after.getJSONArray("operations").length());assertFalse(app.tasks.hasUnresolved(id));
        assertTrue(receipt.getBoolean("approvalDecisionSaved"));assertTrue(receipt.getBoolean("continuationScheduled"));assertFalse(receipt.getBoolean("continuedOriginalRun"));
        assertTrue(events.stream().anyMatch(event->event.optString("type").equals("error")&&event.optString("message").startsWith("审批已保存，但未继续执行")));
    }

    @Test public void currentPhoneDecisionContinuesOnlyItsCapturedOriginalControl()throws Exception{
        JSONObject original=start(true);String id=original.getString("id"),approval=pending(original);app.tasks.status(id,"paused","original phone is paused for review");
        RunControl owner=new RunControl();owner.bindHostRun(id,original.getString("runId"),original.getInt("coordinatorEpoch"));owner.pause();
        set(app,"activeTaskId",id);set(app,"activeControl",owner);
        JSONObject receipt=decide(original,approval);
        assertFalse(owner.isPaused());assertFalse(owner.isCancelled());assertEquals(original.getString("runId"),app.tasks.get(id,false).getString("runId"));
        Intent continued=shadowOf(app).getNextStartedService();assertNotNull(continued);assertEquals(dev.pocketengine.PhoneRunService.CONTINUE,continued.getAction());
    }

    @Test public void aSavedDecisionDoesNotClearOrContinueAnUnresolvedOriginalEffect()throws Exception{
        JSONObject original=start(false);String id=original.getString("id"),approval=pending(original);
        app.tasks.authorizeTools(id,original.getString("runId"),new org.json.JSONArray().put("workspace_write"));
        RunControl owner=new RunControl();owner.bindHostRun(id,original.getString("runId"),original.getInt("coordinatorEpoch"));
        String operation=app.tasks.journal(id,original.getString("runId"),original.getInt("coordinatorEpoch"),owner).beginOperation("workspace_write",new JSONObject().put("path","unresolved.txt"));
        JSONObject receipt=decide(original,approval);
        assertTrue(receipt.getBoolean("approvalDecisionSaved"));assertFalse(receipt.getBoolean("continuationScheduled"));assertFalse(receipt.getBoolean("continuedOriginalRun"));
        assertTrue(queue.pending.isEmpty());assertTrue(app.tasks.hasUnresolved(id));
        JSONObject retained=app.tasks.get(id,false);assertEquals(original.getString("runId"),retained.getString("runId"));assertEquals(operation,retained.getJSONArray("operations").getJSONObject(0).getString("operationId"));
        assertEquals("prepared",retained.getJSONArray("operations").getJSONObject(0).getString("state"));assertEquals("approved",retained.getJSONArray("approvals").getJSONObject(0).getString("state"));
    }
}
