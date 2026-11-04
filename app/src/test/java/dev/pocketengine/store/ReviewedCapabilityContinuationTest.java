package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import java.lang.reflect.Method;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The production SQL admission must retain the reviewed task, attempt, feedback and cumulative budget. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ReviewedCapabilityContinuationTest {
    private TaskStore store(){return new TaskStore(RuntimeEnvironment.getApplication());}
    private JSONObject start(TaskStore store,int budget)throws Exception{
        String id=store.createCapabilityTask("Continue the reviewed original task","").getString("id");
        store.initializeExecutionScope(id,budget);return store.startAttempt(id,"capabilities",budget);
    }
    private void end(TaskStore store,JSONObject task)throws Exception{
        store.endAttempt(task.getString("id"),task.getString("runId"),"paused","Original worker settled",new JSONObject());
    }
    private JSONObject resume(TaskStore store,JSONObject reviewed,RunControl control,String feedback)throws Exception{
        return store.startOriginalCapabilityAttempt(reviewed.getString("id"),reviewed.getString("runId"),reviewed.getInt("coordinatorEpoch"),40,control,feedback);
    }
    private String operation(TaskStore store,JSONObject task,boolean known)throws Exception{
        String id=task.getString("id"),run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");
        RunControl original=new RunControl();original.bindHostRun(id,run,epoch);
        store.authorizeTools(id,run,new JSONArray().put("workspace_write"));
        RunJournal journal=store.journal(id,run,epoch,original);
        String operation=journal.beginOperation("workspace_write",new JSONObject().put("path","original.txt"));
        if(known)journal.finishOperation(operation,new JSONObject().put("accepted",true).put("ok",true));
        return operation;
    }
    private JSONObject channelStart(TaskStore store,int budget)throws Exception{
        String id=UUID.randomUUID().toString();JSONObject origin=new JSONObject().put("kind","channel")
                .put("requestId",UUID.randomUUID().toString()).put("eventId",UUID.randomUUID().toString()).put("profileId","fixture-profile")
                .put("messageId",UUID.randomUUID().toString()).put("senderId","bound-owner").put("authenticatedBy","trusted_relay")
                .put("bindingEpoch",UUID.randomUUID().toString()).put("taskGrantEpoch",UUID.randomUUID().toString()).put("ownerId","owner").put("spaceId","private");
        store.registerChannelTask(id,"Keep the original channel allowance",origin);return store.startAttempt(id,"capabilities",budget);
    }
    private JSONObject grant(TaskStore store,JSONObject task)throws Exception{
        Method grant=TaskStore.class.getDeclaredMethod("grant",String.class);grant.setAccessible(true);return (JSONObject)grant.invoke(store,task.getString("runId"));
    }

    @Test public void exactContinuationKeepsUsedBudgetAndOriginalReceiptsAndIsSingleUse()throws Exception{
        TaskStore store=store();JSONObject first=start(store,4);String id=first.getString("id");
        String operation=operation(store,first,true);end(store,first);
        JSONObject scope=store.get(id,false).getJSONObject("executionScope");assertEquals(1,scope.getInt("toolsUsed"));
        RunControl nextControl=new RunControl();JSONObject next=resume(store,first,nextControl,"Keep the first result");
        assertEquals(id,next.getString("id"));assertNotEquals(first.getString("runId"),next.getString("runId"));
        assertEquals(first.getInt("coordinatorEpoch")+1,next.getInt("coordinatorEpoch"));
        assertTrue(nextControl.matchesHostRun(id,next.getString("runId"),next.getInt("coordinatorEpoch")));
        JSONObject after=store.get(id,false).getJSONObject("executionScope");
        for(String field:new String[]{"revision","toolLimit","toolsUsed","modelCallLimit","modelCallsUsed","deadlineAt"})assertEquals(field,scope.get(field),after.get(field));
        assertEquals("Keep the first result",store.get(id,false).getString("feedback"));
        JSONObject kept=store.get(id,false).getJSONArray("operations").getJSONObject(0);
        assertEquals(operation,kept.getString("operationId"));assertEquals("receipt",kept.getString("state"));
        assertEquals(next.getString("runId"),store.requireCapabilityAttempt(id,nextControl).getString("runId"));
        String before=store.get(id,false).toString();
        assertThrows(SecurityException.class,()->resume(store,first,new RunControl(),"Duplicate stale feedback"));
        assertEquals(before,store.get(id,false).toString());assertEquals(1,store.list().length());
    }

    @Test public void delayedReviewCannotResumeAReplacementOrAppendItsFeedback()throws Exception{
        TaskStore store=store();JSONObject first=start(store,4);end(store,first);
        JSONObject successor=store.startAttempt(first.getString("id"),"capabilities",4);end(store,successor);
        String before=store.get(first.getString("id"),false).toString();RunControl rejected=new RunControl();
        assertThrows(SecurityException.class,()->resume(store,first,rejected,"Old window's feedback"));
        assertEquals(before,store.get(first.getString("id"),false).toString());assertFalse(rejected.hasHostRun());
    }

    @Test public void aPausedPresentationDoesNotAdmitAnotherAttemptWhileTheWorkerStillOwnsItsRun()throws Exception{
        TaskStore store=store();JSONObject first=start(store,4);String id=first.getString("id");
        store.status(id,"paused","The original worker has not settled yet");String before=store.get(id,false).toString();
        assertThrows(SecurityException.class,()->resume(store,first,new RunControl(),"Cannot race the old worker"));
        assertEquals(before,store.get(id,false).toString());
    }

    @Test public void unresolvedEffectIsNeverReplayedByAReviewedContinuation()throws Exception{
        TaskStore store=store();JSONObject first=start(store,4);String id=first.getString("id");
        String operation=operation(store,first,false);end(store,first);String before=store.get(id,false).toString();
        assertThrows(SecurityException.class,()->resume(store,first,new RunControl(),"Do not resend"));
        assertTrue(store.hasUnresolved(id));assertEquals(before,store.get(id,false).toString());
        assertEquals(operation,store.get(id,false).getJSONArray("operations").getJSONObject(0).getString("operationId"));
    }

    @Test public void exhaustedScopeRollsBackFeedbackInsteadOfRefillingTheBudget()throws Exception{
        TaskStore store=store();JSONObject first=start(store,1);String id=first.getString("id");
        operation(store,first,true);end(store,first);String before=store.get(id,false).toString();RunControl rejected=new RunControl();
        assertThrows(IllegalStateException.class,()->resume(store,first,rejected,"This feedback must roll back"));
        assertEquals(before,store.get(id,false).toString());assertFalse(rejected.hasHostRun());
        assertEquals(1,store.get(id,false).getJSONObject("executionScope").getInt("toolsUsed"));
    }

    @Test public void legacyTaskNeedsAnExplicitScopeAndWrongEpochOrStoppedRunCannotResume()throws Exception{
        TaskStore store=store();String legacy=store.createCapabilityTask("No implicit scope migration","").getString("id");
        JSONObject original=store.startAttempt(legacy,"capabilities",4);end(store,original);String unchanged=store.get(legacy,false).toString();
        assertThrows(SecurityException.class,()->resume(store,original,new RunControl(),"Do not add implicit budget"));
        assertEquals(unchanged,store.get(legacy,false).toString());
        JSONObject first=start(store,4);end(store,first);String id=first.getString("id");
        assertThrows(SecurityException.class,()->store.startOriginalCapabilityAttempt(id,first.getString("runId"),first.getInt("coordinatorEpoch")+1,40,new RunControl(),"Wrong epoch"));
        store.requestCancel(id,first.getString("runId"));String stopped=store.get(id,false).toString();
        assertThrows(SecurityException.class,()->resume(store,first,new RunControl(),"Do not undo the stop"));
        assertEquals(stopped,store.get(id,false).toString());
    }

    @Test public void reviewedChannelContinuationRetainsItsOriginalBudgetAndExpiryWithoutAPrivateScope()throws Exception{
        TaskStore store=store();JSONObject first=channelStart(store,4);operation(store,first,true);end(store,first);
        JSONObject originalGrant=grant(store,first);assertEquals(3,originalGrant.getInt("remainingOperations"));
        JSONObject next=resume(store,first,new RunControl(),"Continue inside the original allowance");JSONObject nextGrant=grant(store,next);
        for(String field:new String[]{"principalId","spaceId","mode","stepCeiling","remainingOperations","expiresAt"})assertEquals(field,originalGrant.get(field),nextGrant.get(field));
        assertEquals(4,next.getInt("stepCeiling"));assertFalse(store.get(first.getString("id"),false).has("executionScope"));
        assertEquals(first.getJSONObject("origin").toString(),next.getJSONObject("origin").toString());
    }

    @Test public void reviewedChannelContinuationCannotRefillAnExhaustedOriginalGrant()throws Exception{
        TaskStore store=store();JSONObject first=channelStart(store,1);operation(store,first,true);end(store,first);
        assertEquals(0,grant(store,first).getInt("remainingOperations"));String before=store.get(first.getString("id"),false).toString();
        assertThrows(SecurityException.class,()->resume(store,first,new RunControl(),"No refill"));
        assertEquals(before,store.get(first.getString("id"),false).toString());
    }
}
