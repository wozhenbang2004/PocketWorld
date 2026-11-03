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
                try(var page=d.research.openBudget(d.coordinator,d.teamRun.get())){page.setForeground(true);JSONObject observed=page.wakePolicyStatus();assertEquals("completed",observed.getJSONArray("intents").getJSONObject(0).getString("status"));assertEquals(1,observed.getJSONObject("nativeClaims").getInt("total"));assertFalse(observed.getBoolean("automaticRetry"));}

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

}
