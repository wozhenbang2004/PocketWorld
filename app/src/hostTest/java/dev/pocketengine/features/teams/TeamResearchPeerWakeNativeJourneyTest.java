package dev.pocketengine.features.teams;

import android.app.Application;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original native peer read receipt -> producer Runner/port cleanup -> original Coordinator -> same child/pool. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TeamResearchPeerWakeNativeJourneyTest {
    interface AtReceipt {void change(TeamResearchParentNativeJourneyTest.Domain d,TeamResearchHost.Page page)throws Exception;}
    static void journey(boolean peer,boolean starts,AtReceipt change,String reason)throws Exception {
        try(var d=new TeamResearchParentNativeJourneyTest.Domain(false)){
            d.wakeScenario=true;d.wakePeerScenario=true;d.wakeExpected=starts;ExecutorService io=Executors.newSingleThreadExecutor();try{Future<RunOutcome> running=io.submit(d::runParent);TeamResearchWakeNativeJourneyTest.awaitIdle(d,running);JSONObject before=TeamResearchWakeNativeJourneyTest.child(d),budget=d.tasks.teamBudgetSnapshot(d.teamRun.get());int aCalls=d.aCalls.get();
                try(var page=d.research.openBudget(d.coordinator,d.teamRun.get())){page.setForeground(true);var review=page.reviewWakePolicy(true,peer,1,2,System.currentTimeMillis()+120000,0);page.approveWakePolicy(review,review.view().getString("digest"));assertEquals(before.getString("runId"),TeamResearchWakeNativeJourneyTest.child(d).getString("runId"));assertEquals(aCalls,d.aCalls.get());
                    d.releaseWakeSender.countDown();assertTrue(d.peerSent.await(25,TimeUnit.SECONDS));assertTrue(d.research.hasActiveWork(d.teamRun.get()));assertEquals("producer is still in the original actual request; no early wake",before.getString("runId"),TeamResearchWakeNativeJourneyTest.child(d).getString("runId"));JSONObject queued=page.wakePolicyStatus();assertEquals(0,queued.getJSONObject("nativeClaims").getInt("total"));assertEquals(1,queued.getJSONArray("intents").length());assertEquals("DIRECTED_PEER",queued.getJSONArray("intents").getJSONObject(0).getJSONObject("binding").getString("sourceKind"));
                    change.change(d,page);d.releasePeerFinal.countDown();assertNotNull(running.get(35,TimeUnit.SECONDS));JSONObject after=TeamResearchWakeNativeJourneyTest.child(d);JSONObject observed=page.wakePolicyStatus();assertEquals(starts?1:0,observed.getJSONObject("nativeClaims").getInt("total"));assertEquals(aCalls+(starts?1:0),d.aCalls.get());assertEquals(before.getInt("coordinatorEpoch")+(starts?1:0),after.getInt("coordinatorEpoch"));assertEquals(before.getString("id"),after.getString("id"));assertEquals(1,observed.getJSONArray("intents").length());if(starts){assertNotEquals(before.getString("runId"),after.getString("runId"));assertEquals("completed",observed.getJSONArray("intents").getJSONObject(0).getString("status"));assertEquals("consumed",d.state().getJSONArray("mailbox").getJSONObject(0).getString("status"));}else{assertEquals(before.getString("runId"),after.getString("runId"));assertNotEquals("consumed",d.state().getJSONArray("mailbox").getJSONObject(0).getString("status"));if(reason!=null)assertEquals(reason,observed.getJSONArray("intents").getJSONObject(0).getString("reason"));}
                    assertEquals(budget.getInt("modelRequestLimit"),d.tasks.teamBudgetSnapshot(d.teamRun.get()).getInt("modelRequestLimit"));assertEquals(budget.getInt("toolOperationLimit"),d.tasks.teamBudgetSnapshot(d.teamRun.get()).getInt("toolOperationLimit"));assertEquals(0,d.sourceBodyReads.get());
                }
            }finally{d.releaseWakeSender.countDown();d.releasePeerFinal.countDown();io.shutdownNow();}
        }
    }
    @Test public void existingAuthorizedControlPolicyNeverSilentlyEnablesPeerWake()throws Exception {journey(false,false,(d,p)->{},"source_not_allowed");}
    @Test public void explicitPeerPolicyWaitsForActualProducerCleanupThenClaimsExactlyOneOriginalRun()throws Exception {journey(true,true,(d,p)->{},null);}
    @Test public void policyWithdrawnAfterActualPeerReceiptCannotStartWhenProducerFinallyReleases()throws Exception {journey(true,false,(d,p)->TeamResearchWakeNativeJourneyTest.save(p,false,System.currentTimeMillis()+120000),"policy_disabled");}
    @Test public void peerCannotResumeAnExplicitlyOwnerPausedRecipient()throws Exception {journey(true,false,(d,p)->d.coordinator.pause(d.teamRun.get(),"a"),"stale_target_identity_run_epoch");}
    @Test public void nativeOriginalParentStopAfterPeerReceiptCannotCreateAReplacementRun()throws Exception {journey(true,false,(d,p)->d.original.cancel(),null);}
    @Test public void expiredOriginalParentScopeAfterPeerReceiptCannotBeRenewedByPeerText()throws Exception {journey(true,false,(d,p)->TeamResearchWakeNativeJourneyTest.narrowTeam(d,t->{try{t.getJSONObject("teamResearchParentScope").put("expiresAt",System.currentTimeMillis()-1);}catch(Exception e){throw new AssertionError(e);}}),"owner_stopped");}

}
