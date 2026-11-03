package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import android.app.Application;
import static org.junit.Assert.*;

/** Actual original parent Runner -> queued native parent message -> policy -> same research Runner/SQL/pool. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TeamResearchWakeNativeJourneyTest {
    static JSONObject child(TeamResearchParentNativeJourneyTest.Domain d)throws Exception {
        JSONArray tasks=d.tasks.list();for(int i=0;i<tasks.length();i++){JSONObject task=tasks.getJSONObject(i),who=task.optJSONObject("teamResearch");if(who!=null&&"a".equals(who.optString("memberId")))return task;}throw new AssertionError("no original research A checkpoint");
    }
    static void awaitIdle(TeamResearchParentNativeJourneyTest.Domain d,Future<RunOutcome> running)throws Exception {
        if(d.wakeIdle.await(32,TimeUnit.SECONDS))return;
        String state=d.teamRun.get()==null?"no team":d.state().optString("status")+" members="+d.state().getJSONArray("members");
        throw new AssertionError("idle not reached stage="+d.stage+" http="+d.http.get()+" failure="+d.failure.get()+" futureDone="+running.isDone()+" state="+state+(running.isDone()?" outcome="+running.get():""));
    }
    @Test public void queuedParentMessageWithoutOwnerPolicyStaysDataAndNeverCreatesAnotherResearchRun()throws Exception {
        try(var d=new TeamResearchParentNativeJourneyTest.Domain(false)){
            d.wakeScenario=true;ExecutorService io=Executors.newSingleThreadExecutor();try{Future<RunOutcome> running=io.submit(d::runParent);awaitIdle(d,running);JSONObject before=child(d);int calls=d.memberCalls.get();d.releaseWakeSender.countDown();RunOutcome done=running.get(25,TimeUnit.SECONDS);assertNotNull(done);assertEquals(before.getString("runId"),child(d).getString("runId"));assertEquals(before.getInt("coordinatorEpoch"),child(d).getInt("coordinatorEpoch"));assertEquals(calls,d.memberCalls.get());assertFalse(d.tasks.get(d.teamRun.get(),false).has(TeamWakeQueue.KEY));assertNotNull(d.wakeReceipt);assertFalse(d.wakeReceipt.optJSONObject("messageReceipt").optBoolean("automaticWake"));}finally{d.releaseWakeSender.countDown();io.shutdownNow();}
        }
    }
    @Test public void explicitNativeOwnerPolicyStartsExactlyOneSameCheckpointAttemptWithOriginalBudget()throws Exception {
        try(var d=new TeamResearchParentNativeJourneyTest.Domain(false)){
            d.wakeScenario=true;d.wakeExpected=true;d.wakeDuplicate=true;ExecutorService io=Executors.newSingleThreadExecutor();try{Future<RunOutcome> running=io.submit(d::runParent);awaitIdle(d,running);JSONObject before=child(d),budget=d.tasks.teamBudgetSnapshot(d.teamRun.get());int calls=d.memberCalls.get();
                try(var page=d.research.openBudget(d.coordinator,d.teamRun.get())){page.setForeground(true);JSONObject old=page.wakePolicyStatus();TaskStore.TeamWakePolicyReview review=page.reviewWakePolicy(true,1,2,System.currentTimeMillis()+120000,old.optLong("policyRevision"));JSONObject saved=page.approveWakePolicy(review,review.view().getString("digest"));assertTrue(saved.getBoolean("saved"));assertFalse(saved.getBoolean("automaticallyStarted"));assertEquals(before.getString("runId"),child(d).getString("runId"));assertEquals(calls,d.memberCalls.get());assertEquals(budget.getInt("modelRequestLimit"),d.tasks.teamBudgetSnapshot(d.teamRun.get()).getInt("modelRequestLimit"));}
                d.releaseWakeSender.countDown();RunOutcome done=running.get(30,TimeUnit.SECONDS);assertNotNull(done);JSONObject after=child(d);assertEquals(before.getString("id"),after.getString("id"));assertEquals(before.getInt("coordinatorEpoch")+1,after.getInt("coordinatorEpoch"));assertNotEquals(before.getString("runId"),after.getString("runId"));assertEquals(calls+1,d.memberCalls.get());assertEquals(budget.getInt("modelRequestLimit"),d.tasks.teamBudgetSnapshot(d.teamRun.get()).getInt("modelRequestLimit"));assertEquals(budget.getInt("toolOperationLimit"),d.tasks.teamBudgetSnapshot(d.teamRun.get()).getInt("toolOperationLimit"));
                JSONObject state=d.tasks.get(d.teamRun.get(),false).getJSONObject(TeamWakeQueue.KEY);assertEquals(1,state.getJSONObject("nativeClaims").getInt("total"));assertEquals(1,state.getJSONArray("intents").length());assertNotNull(d.wakeDuplicateReceipt);assertTrue(d.wakeDuplicateReceipt.getJSONObject("messageReceipt").getBoolean("duplicate"));assertFalse(d.wakeDuplicateReceipt.getJSONObject("messageReceipt").getBoolean("automaticWake"));assertTrue(after.getJSONObject("teamResearchNativeRun").has("teamWakeClaim"));assertNotNull(d.wakeReceipt);assertEquals("queued_not_completion",d.wakeReceipt.getJSONObject("messageReceipt").getString("wakeScheduling"));assertFalse(d.wakeReceipt.getJSONObject("messageReceipt").getBoolean("budgetIncreased"));
                try(var page=d.research.openBudget(d.coordinator,d.teamRun.get())){page.setForeground(true);JSONObject observed=page.wakePolicyStatus();assertEquals("completed",observed.getJSONArray("intents").getJSONObject(0).getString("status"));assertEquals(1,observed.getJSONObject("nativeClaims").getInt("total"));assertFalse(observed.getBoolean("automaticRetry"));assertEquals(0,observed.getJSONArray("wakeAttention").length());assertEquals(0,attentionEvents(d.tasks,d.parent).length());}

            }finally{d.releaseWakeSender.countDown();io.shutdownNow();}
        }
    }
    interface OwnerChange {void apply(TeamResearchParentNativeJourneyTest.Domain d,TeamResearchHost.Page page)throws Exception;}
    static void unchangedAfter(OwnerChange change,String reason,boolean sqlMessage)throws Exception {
        try(var d=new TeamResearchParentNativeJourneyTest.Domain(false)){
            d.wakeScenario=true;ExecutorService io=Executors.newSingleThreadExecutor();try{Future<RunOutcome> running=io.submit(d::runParent);awaitIdle(d,running);JSONObject before=child(d),budget=d.tasks.teamBudgetSnapshot(d.teamRun.get());int calls=d.memberCalls.get();
                try(var page=d.research.openBudget(d.coordinator,d.teamRun.get())){page.setForeground(true);change.apply(d,page);}
                d.releaseWakeSender.countDown();assertNotNull(running.get(30,TimeUnit.SECONDS));assertEquals(before.getString("runId"),child(d).getString("runId"));assertEquals(calls,d.memberCalls.get());assertEquals(budget.getInt("modelRequestLimit"),d.tasks.teamBudgetSnapshot(d.teamRun.get()).getInt("modelRequestLimit"));assertEquals(budget.getInt("toolOperationLimit"),d.tasks.teamBudgetSnapshot(d.teamRun.get()).getInt("toolOperationLimit"));
                JSONArray rows=child(d).optJSONArray("teamConversationInputs");assertEquals(sqlMessage?1:0,rows==null?0:rows.length());if(sqlMessage)assertEquals("queued",rows.getJSONObject(0).getString("state"));
                if(reason!=null)try(var page=d.research.openBudget(d.coordinator,d.teamRun.get())){page.setForeground(true);JSONObject observed=page.wakePolicyStatus();assertEquals(0,observed.getJSONObject("nativeClaims").getInt("total"));assertEquals(reason,observed.getJSONArray("intents").getJSONObject(0).getString("reason"));}
            }finally{d.releaseWakeSender.countDown();io.shutdownNow();}
        }
    }
    static void save(TeamResearchHost.Page page,boolean enabled,long expires)throws Exception {JSONObject state=page.wakePolicyStatus();var review=page.reviewWakePolicy(enabled,1,2,expires,state.optLong("policyRevision"));page.approveWakePolicy(review,review.view().getString("digest"));}
    static void narrowTeam(TeamResearchParentNativeJourneyTest.Domain d,java.util.function.Consumer<JSONObject> change)throws Exception {synchronized(d.tasks){var read=TaskStore.class.getDeclaredMethod("required",String.class);read.setAccessible(true);JSONObject team=(JSONObject)read.invoke(d.tasks,d.teamRun.get());change.accept(team);var write=TaskStore.class.getDeclaredMethod("write",JSONObject.class);write.setAccessible(true);write.invoke(d.tasks,team);}}
    @Test public void withdrawnOwnerPolicyKeepsOriginalMessageAndNeverAddsRunOrBudget()throws Exception {
        unchangedAfter((d,page)->{save(page,true,System.currentTimeMillis()+120000);save(page,false,System.currentTimeMillis()+120000);},"policy_disabled",true);
    }
    @Test public void expiredOwnerPolicyKeepsOriginalMessageAndNeverRefreshesPermission()throws Exception {
        unchangedAfter((d,page)->{long expiry=System.currentTimeMillis()+1500;save(page,true,expiry);Thread.sleep(1600);assertTrue(System.currentTimeMillis()>expiry);},"expired",true);
    }
    @Test public void nativeOwnerPauseCannotBeOverriddenByAnOtherwiseApprovedParentMessage()throws Exception {
        unchangedAfter((d,page)->{save(page,true,System.currentTimeMillis()+120000);d.coordinator.pause(d.teamRun.get(),"a");},"owner_paused",true);
    }
    @Test public void nativePolicyReviewCannotCrossPageForegroundGenerationOrOverwriteANewerPolicy()throws Exception {
        unchangedAfter((d,page)->{
            page.setForeground(false);assertThrows(SecurityException.class,()->page.reviewWakePolicy(true,1,2,System.currentTimeMillis()+120000,0));page.setForeground(true);
            try(var other=d.research.openBudget(d.coordinator,d.teamRun.get())){other.setForeground(true);var old=page.reviewWakePolicy(true,1,2,System.currentTimeMillis()+120000,0);assertThrows(SecurityException.class,()->other.approveWakePolicy(old,old.view().getString("digest")));page.setForeground(false);page.setForeground(true);assertThrows(SecurityException.class,()->page.approveWakePolicy(old,old.view().getString("digest")));
                var stale=page.reviewWakePolicy(true,1,2,System.currentTimeMillis()+120000,0);save(other,false,System.currentTimeMillis()+120000);assertThrows(SecurityException.class,()->page.approveWakePolicy(stale,stale.view().getString("digest")));}
        },"policy_disabled",true);
    }
    @Test public void expiredOriginalResearchScopeStillAllowsNarrowingPolicyButCannotEnableOrSend()throws Exception {
        unchangedAfter((d,page)->{save(page,true,System.currentTimeMillis()+120000);narrowTeam(d,t->{try{t.getJSONObject("teamResearchParentScope").put("expiresAt",System.currentTimeMillis()-1);}catch(Exception e){throw new AssertionError(e);}});assertThrows(SecurityException.class,()->page.reviewWakePolicy(true,1,2,System.currentTimeMillis()+120000,1));save(page,false,System.currentTimeMillis()+120000);assertFalse(page.wakePolicyStatus().getJSONObject("policy").getBoolean("enabled"));},null,false);
    }
    @Test public void corruptOriginalWakeStateRollsBackTheSameNativeResearchMessageTransaction()throws Exception {
        unchangedAfter((d,page)->{save(page,true,System.currentTimeMillis()+120000);narrowTeam(d,t->{try{t.getJSONObject(TeamWakeQueue.KEY).put("stateHash","0".repeat(64));}catch(Exception e){throw new AssertionError(e);}});},null,false);
    }

    private static android.database.sqlite.SQLiteDatabase sql(TaskStore tasks)throws Exception {var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (android.database.sqlite.SQLiteDatabase)field.get(tasks);}
    private static JSONArray attentionEvents(TaskStore tasks,String parent)throws Exception {
        JSONArray events=new JSONArray();try(var rows=sql(tasks).rawQuery("SELECT event_json FROM task_events WHERE task_id=? AND event_type='team_wake_attention' ORDER BY event_order",new String[]{parent})){while(rows.moveToNext())events.put(new JSONObject(rows.getString(0)));}return events;
    }
    private static void assertOriginalAttention(JSONObject observed,JSONObject child,String expected)throws Exception {
        assertEquals(1,observed.getJSONArray("wakeAttention").length());JSONObject attention=observed.getJSONArray("wakeAttention").getJSONObject(0),intent=observed.getJSONArray("intents").getJSONObject(0),attempt=intent.getJSONObject("originalAttempt");
        assertEquals(expected,intent.getString("status"));assertTrue(intent.getBoolean("escalationRecorded"));assertEquals(expected,attention.getString("status"));assertEquals("a",attention.getString("memberId"));
        assertEquals(intent.getString("batchId"),attention.getString("escalationId"));assertEquals(attempt.getString("operationId"),attention.getString("originalOperationId"));assertEquals(child.getString("id"),attention.getString("childTaskId"));assertEquals(child.getString("runId"),attention.getString("childRunId"));assertEquals(child.getInt("coordinatorEpoch"),attention.getInt("childEpoch"));
        assertEquals(1,attention.getJSONArray("intentIds").length());assertEquals(intent.getString("id"),attention.getJSONArray("intentIds").getString(0));assertTrue(attention.getBoolean("queryOnly"));assertFalse(attention.getBoolean("automaticRetry"));assertFalse(attention.has("resourcesReleased"));assertFalse(attention.toString().contains("WAKE_UNKNOWN_PRIVATE_TEXT"));assertFalse(attention.toString().contains("Native queued parent public fact"));
    }
    /** Exact prior-version observation after a real native wake; no grant/run/event is fabricated. */
    private static void retainLegacyObservedFailureWithoutAttention(TeamResearchParentNativeJourneyTest.Domain d,JSONObject child)throws Exception {
        synchronized(d.tasks){var read=TaskStore.class.getDeclaredMethod("required",String.class);read.setAccessible(true);JSONObject team=(JSONObject)read.invoke(d.tasks,d.teamRun.get()),state=team.getJSONObject(TeamWakeQueue.KEY),scope=state.getJSONObject("scope"),counts=state.getJSONObject("nativeClaims"),intent=state.getJSONArray("intents").getJSONObject(0),attempt=intent.getJSONObject("originalAttempt");
            java.util.Map<Integer,Integer> rounds=new java.util.HashMap<>();JSONObject recorded=counts.getJSONObject("rounds");for(var keys=recorded.keys();keys.hasNext();){String key=keys.next();rounds.put(Integer.parseInt(key),recorded.getInt(key));}
            String status;try(var row=sql(d.tasks).rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{child.getString("id"),child.getString("runId"),Integer.toString(child.getInt("coordinatorEpoch"))})){assertTrue(row.moveToFirst());status=row.getString(0);}assertFalse(status.matches("running|completed|cancelled|interrupted|unknown"));assertFalse(d.tasks.hasUnresolved(child.getString("id")));
            TeamWakeQueue.Scope originalScope=new TeamWakeQueue.Scope(scope.getString("teamRunId"),scope.getString("parentTaskId"),scope.getString("parentRunId"),scope.getInt("parentEpoch"),scope.getString("parentBudgetRef"));TeamWakeQueue.NativeClaims claims=new TeamWakeQueue.NativeClaims(counts.getInt("total"),rounds,counts.getString("digest"));TeamWakeQueue.OriginalAttempt original=new TeamWakeQueue.OriginalAttempt(attempt.getString("operationId"),attempt.getString("childTaskId"),attempt.getString("childRunId"),attempt.getInt("childEpoch"));
            String receipt=TaskStore.capabilityFingerprint(new JSONObject().put("originalResearchRunId",child.getString("runId")).put("epoch",child.getInt("coordinatorEpoch")).put("status",status).put("outcome",TeamWakeQueue.Outcome.FAILED.name()));
            JSONObject legacy=TeamWakeQueue.observeOriginal(team,originalScope,original,TeamWakeQueue.Outcome.FAILED,receipt,claims).mergeInto(team);var write=TaskStore.class.getDeclaredMethod("write",JSONObject.class);write.setAccessible(true);write.invoke(d.tasks,legacy);assertFalse(legacy.getJSONObject(TeamWakeQueue.KEY).getJSONArray("intents").getJSONObject(0).getBoolean("escalationRecorded"));assertEquals(0,attentionEvents(d.tasks,d.parent).length());
        }
    }
    private static void unsuccessfulWakeAttentionOnce(boolean unknown,boolean legacyObserved)throws Exception {
        try(var d=new TeamResearchParentNativeJourneyTest.Domain(unknown)){
            d.wakeScenario=true;d.wakeExpected=true;d.wakeUnfinished=!unknown;d.wakeUnknown=unknown;ExecutorService io=Executors.newSingleThreadExecutor();
            try{Future<RunOutcome> running=io.submit(d::runParent);awaitIdle(d,running);JSONObject before=child(d),limits=d.tasks.teamBudgetSnapshot(d.teamRun.get());int calls=d.memberCalls.get();
                try(var page=d.research.openBudget(d.coordinator,d.teamRun.get())){page.setForeground(true);save(page,true,System.currentTimeMillis()+120000);assertEquals(0,page.wakePolicyStatus().getJSONArray("wakeAttention").length());
                    d.releaseWakeSender.countDown();assertTrue(d.wakeExecutionEntered.await(25,TimeUnit.SECONDS));JSONObject active=page.wakePolicyStatus();assertEquals("claimed",active.getJSONArray("intents").getJSONObject(0).getString("status"));assertEquals(0,active.getJSONArray("wakeAttention").length());assertEquals(0,attentionEvents(d.tasks,d.parent).length());assertFalse(d.coordinator.parentExecutorState(d.teamRun.get()).getBoolean("resourcesReleased"));
                    d.releaseWakeExecution.countDown();assertNotNull(running.get(35,TimeUnit.SECONDS));JSONObject after=child(d);assertEquals(before.getString("id"),after.getString("id"));assertNotEquals(before.getString("runId"),after.getString("runId"));assertEquals(before.getInt("coordinatorEpoch")+1,after.getInt("coordinatorEpoch"));assertEquals(calls+1,d.memberCalls.get());
                    if(legacyObserved){assertFalse(unknown);retainLegacyObservedFailureWithoutAttention(d,after);}
                    String expected=unknown?"unknown":"failed",inputs=TeamWorkFiles.canonical(after.getJSONArray("teamConversationInputs")),budget=TeamWorkFiles.canonical(d.tasks.teamBudgetSnapshot(d.teamRun.get()));JSONObject observed=page.wakePolicyStatus();assertOriginalAttention(observed,after,expected);assertEquals(1,observed.getJSONObject("nativeClaims").getInt("total"));
                    JSONArray events=attentionEvents(d.tasks,d.parent);assertEquals(1,events.length());JSONObject event=events.getJSONObject(0),attention=observed.getJSONArray("wakeAttention").getJSONObject(0);assertEquals(d.teamRun.get(),event.getString("teamRunId"));assertEquals(expected,event.getString("outcome"));for(String key:new String[]{"escalationId","memberId","childTaskId","childRunId","originalOperationId"})assertEquals(attention.getString(key),event.getString(key));assertEquals(attention.getInt("childEpoch"),event.getInt("childEpoch"));assertTrue(event.getBoolean("queryOnly"));assertFalse(event.getBoolean("automaticRetry"));assertFalse(event.toString().contains("WAKE_UNKNOWN_PRIVATE_TEXT"));
                    for(int read=0;read<3;read++)assertOriginalAttention(page.wakePolicyStatus(),after,expected);assertEquals(1,attentionEvents(d.tasks,d.parent).length());assertEquals(after.getString("runId"),child(d).getString("runId"));assertEquals(after.getInt("coordinatorEpoch"),child(d).getInt("coordinatorEpoch"));assertEquals(inputs,TeamWorkFiles.canonical(child(d).getJSONArray("teamConversationInputs")));assertEquals(budget,TeamWorkFiles.canonical(d.tasks.teamBudgetSnapshot(d.teamRun.get())));assertEquals(calls+1,d.memberCalls.get());
                    assertEquals(limits.getInt("modelRequestLimit"),d.tasks.teamBudgetSnapshot(d.teamRun.get()).getInt("modelRequestLimit"));assertEquals(limits.getInt("toolOperationLimit"),d.tasks.teamBudgetSnapshot(d.teamRun.get()).getInt("toolOperationLimit"));
                    // Reopen only the native store after original work is settled. This proves durable
                    // batch dedup/readback, not full process restart or automatic cold continuation.
                    TaskStore reopened=new TaskStore(org.robolectric.RuntimeEnvironment.getApplication());try{TeamResearchHost readback=new TeamResearchHost(reopened,d.materials);try(var restored=readback.openBudget(d.coordinator,d.teamRun.get())){restored.setForeground(true);assertOriginalAttention(restored.wakePolicyStatus(),after,expected);assertOriginalAttention(restored.wakePolicyStatus(),after,expected);assertEquals(1,attentionEvents(reopened,d.parent).length());assertEquals(after.getString("runId"),reopened.get(after.getString("id"),false).getString("runId"));assertEquals(budget,TeamWorkFiles.canonical(reopened.teamBudgetSnapshot(d.teamRun.get())));assertEquals(calls+1,d.memberCalls.get());}}finally{sql(reopened).close();}
                }
            }finally{d.releaseWakeSender.countDown();d.releaseWakeExecution.countDown();io.shutdownNow();}
        }
    }
    @Test public void unfinishedOriginalResearchWakeAddsOneParentAttentionWithoutRetryAcrossStoreReopen()throws Exception {unsuccessfulWakeAttentionOnce(false,false);}
    @Test public void unknownOriginalResearchWakeAddsOneQueryOnlyParentAttentionWithoutRetryAcrossStoreReopen()throws Exception {unsuccessfulWakeAttentionOnce(true,false);}
    @Test public void legacyObservedNativeFailureBackfillsOneAttentionOnlyAfterOriginalReceiptRecheck()throws Exception {unsuccessfulWakeAttentionOnce(false,true);}

}
