package dev.pocketengine.features.automation;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class CommitmentPlanPolicyTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private File file(){return new File(folder.getRoot(),"commitments.json");}
    private long at(String value){return Instant.parse(value).toEpochMilli();}
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject source()throws Exception{return new JSONObject(scope().toString()).put("kind","user_action").put("id","explicit-owner-request");}
    private JSONObject input(long due)throws Exception{return new JSONObject().put("kind","explicit_user").put("title","核对结果").put("dueAt",due).put("sourceRef",source());}
    private JSONObject settings(int max)throws Exception{return new JSONObject().put("enabled",true).put("zoneId","Asia/Shanghai").put("maxNotificationsPerDay",max);}
    private JSONObject evaluate(CommitmentBook book,JSONObject record,long now)throws Exception{return book.evaluateFollowUp(scope(),record.getString("id"),record.getLong("revision"),record.getString("candidateKey"),now);}
    private JSONObject claim(CommitmentBook book,JSONObject record,long now)throws Exception{return book.claimFollowUp(scope(),record.getString("id"),record.getLong("revision"),record.getString("candidateKey"),1000,now);}
    private String token(JSONObject claim)throws Exception{return claim.getJSONObject("receipt").getString("claimId");}
    private JSONObject planInput()throws Exception {
        return new JSONObject().put("kind","explicit_user").put("title","核对并交付").put("sourceRef",source())
                .put("steps",new JSONArray().put(new JSONObject().put("title","先核对原始结果").put("dueAt",2000).put("taskId","task-one"))
                        .put(new JSONObject().put("title","再核对交付结果").put("dueAt",3000).put("taskId","task-two")));
    }
    private CommitmentBook verifiedBook()throws Exception {
        return new CommitmentBook(file(),(identity,record,kind,id)->new JSONObject(identity.toString()).put("kind",kind).put("id",id)
                .put("commitmentId",record.getString("id")).put("commitmentRevision",record.getLong("revision"))
                .put("taskId",record.getString("taskId")).put("outcome","completed").put("recordedAt",4000));
    }
    private JSONObject evidence(String id)throws Exception{return new JSONObject().put("kind","task_result").put("id",id);}

    @Test public void expiryStopsCandidatesAndAlreadyReservedDeliveryWithoutCompletingPromise()throws Exception {
        CommitmentBook book=new CommitmentBook(file());book.setFollowUpPolicy(scope(),0,settings(5),1000);
        JSONObject record=book.create(scope(),input(2000).put("expiresAt",4000),1000);String claim=token(claim(book,record,3500));
        assertEquals("expired",evaluate(book,record,4000).getString("reason"));assertEquals(0,book.due(scope(),4000,10).getJSONArray("commitments").length());
        assertFalse(book.beginFollowUpDelivery(scope(),claim,4000).getBoolean("dispatchGranted"));
        JSONObject expired=book.get(scope(),record.getString("id"),4000);assertEquals("pending",expired.getString("status"));assertEquals("expired",expired.getString("lifecycleState"));assertTrue(expired.isNull("followUpAt"));
        assertThrows(IllegalArgumentException.class,()->book.snooze(scope(),record.getString("id"),1,5000,3500));
        assertEquals(1,new CommitmentBook(file()).proactivityState(scope(),5000).getInt("expired"));
    }
    @Test public void quietWindowCannotReviveAnItemAfterItsExpiry()throws Exception {
        CommitmentBook book=new CommitmentBook(file());long due=at("2026-10-30T14:00:00Z");
        book.setFollowUpPolicy(scope(),0,settings(5).put("quietHours",new JSONObject().put("start","22:00").put("end","07:00")),1000);
        JSONObject record=book.create(scope(),input(due).put("expiresAt",due+60000),1000);
        JSONObject decision=evaluate(book,record,due);assertEquals("expires_before_eligible",decision.getString("reason"));assertTrue(decision.isNull("nextEligibleAt"));assertFalse(claim(book,record,due).getBoolean("claimed"));
    }
    @Test public void localDayBudgetCountsReservationsAndUnknownAttemptsAndResetsAtZonedMidnight()throws Exception {
        CommitmentBook book=new CommitmentBook(file());book.setFollowUpPolicy(scope(),0,settings(1),1000);
        JSONObject first=book.create(scope(),input(2000),1000),second=book.create(scope(),input(2000),1000);long now=at("2026-10-30T15:00:00Z"),midnight=at("2026-10-30T16:00:00Z");
        String claim=token(claim(book,first,now));JSONObject reserved=evaluate(book,second,now);
        assertEquals("daily_budget_exhausted",reserved.getString("reason"));assertEquals(now+1000,reserved.getLong("nextEligibleAt"));
        assertTrue(book.beginFollowUpDelivery(scope(),claim,now+1).getBoolean("dispatchGranted"));
        JSONObject decision=evaluate(new CommitmentBook(file()),second,now+2);assertEquals(midnight,decision.getLong("nextEligibleAt"));assertFalse(decision.getBoolean("eligible"));
        assertTrue(evaluate(new CommitmentBook(file()),second,midnight).getBoolean("eligible"));
        assertEquals("delivery_unknown",evaluate(book,first,midnight).getString("reason"));
        assertEquals(1,book.proactivityState(scope(),midnight).getInt("unknownDeliveries"));
    }
    @Test public void budgetAtBeginRechecksOtherCandidatesAndPolicyChangesCannotReuseOldReservation()throws Exception {
        CommitmentBook book=new CommitmentBook(file());book.setFollowUpPolicy(scope(),0,settings(2),1000);
        JSONObject first=book.create(scope(),input(2000),1000),second=book.create(scope(),input(2000),1000);
        String one=token(claim(book,first,3000)),two=token(claim(book,second,3000));assertTrue(book.beginFollowUpDelivery(scope(),one,3100).getBoolean("dispatchGranted"));
        book.setFollowUpPolicy(scope(),1,settings(1),3200);assertEquals("policy_changed",book.beginFollowUpDelivery(scope(),two,3300).getString("reason"));
        assertFalse(claim(book,second,3400).getBoolean("claimed"));assertEquals("daily_budget_exhausted",evaluate(book,second,3400).getString("reason"));
        assertEquals("delivery_unknown",book.getFollowUpReceipt(scope(),one).getString("status"));
    }
    @Test public void concurrentCandidatesCannotOversubscribeDailyBudget()throws Exception {
        CommitmentBook book=new CommitmentBook(file());book.setFollowUpPolicy(scope(),0,settings(2),1000);List<JSONObject> records=new ArrayList<>();
        for(int i=0;i<8;i++)records.add(book.create(scope(),input(2000),1000));
        CountDownLatch start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(8);
        try{List<Future<JSONObject>> results=new ArrayList<>();for(JSONObject record:records)results.add(pool.submit(()->{start.await();return claim(new CommitmentBook(file()),record,3000);}));
            start.countDown();int claimed=0;for(Future<JSONObject> future:results)if(future.get(5,TimeUnit.SECONDS).getBoolean("claimed"))claimed++;assertEquals(2,claimed);
        }finally{pool.shutdownNow();}
    }
    @Test public void zeroBudgetAndExpiredUnknownAreVisibleWithoutRetrying()throws Exception {
        CommitmentBook book=new CommitmentBook(file());book.setFollowUpPolicy(scope(),0,settings(0),1000);JSONObject record=book.create(scope(),input(2000).put("expiresAt",5000),1000);
        assertEquals("daily_budget_exhausted",evaluate(book,record,3000).getString("reason"));assertTrue(evaluate(book,record,3000).isNull("nextEligibleAt"));
        book.setFollowUpPolicy(scope(),1,settings(1),2000);String claim=token(claim(book,record,3000));book.beginFollowUpDelivery(scope(),claim,3100);
        JSONObject state=new CommitmentBook(file()).proactivityState(scope(),6000);assertEquals(1,state.getInt("expired"));assertEquals(1,state.getInt("unknownDeliveries"));
        assertFalse(claim(book,record,6000).getBoolean("claimed"));
    }
    @Test public void orderedPlanUsesExistingEvidenceBeforeUnblockingNextCommitment()throws Exception {
        CommitmentBook book=verifiedBook();book.setFollowUpPolicy(scope(),0,settings(5),1000);JSONObject plan=book.createPlan(scope(),planInput(),1000);JSONArray steps=plan.getJSONArray("steps");
        JSONObject first=steps.getJSONObject(0),second=steps.getJSONObject(1);assertEquals("dependency_pending",second.getString("blockedBy"));
        assertEquals(1,book.due(scope(),4000,10).getJSONArray("commitments").length());assertEquals(first.getString("id"),plan.getJSONObject("nextStep").getString("id"));
        assertEquals("dependency_pending",evaluate(book,second,4000).getString("reason"));assertFalse(claim(book,second,4000).getBoolean("claimed"));
        book.onTaskCompleted(scope(),first.getString("id"),1,"real-result-one","task-one",evidence("real-result-one"),4000);
        JSONObject next=book.getPlan(scope(),plan.getString("id"),4100);assertEquals(1,next.getInt("completedSteps"));assertEquals(second.getString("id"),next.getJSONObject("nextStep").getString("id"));assertTrue(evaluate(book,second,4100).getBoolean("eligible"));
        book.onTaskCompleted(scope(),second.getString("id"),1,"real-result-two","task-two",evidence("real-result-two"),4200);
        JSONObject completed=new CommitmentBook(file()).getPlan(scope(),plan.getString("id"),4300);assertEquals("completed",completed.getString("state"));assertTrue(completed.isNull("nextStep"));assertFalse(completed.getBoolean("executionAuthorized"));
        assertEquals(0,book.due(scope(),4300,10).getJSONArray("commitments").length());
    }
    @Test public void noResolverCannotCompletePlanFromTaskNameOrUnverifiedEvent()throws Exception {
        CommitmentBook book=new CommitmentBook(file());JSONObject plan=book.createPlan(scope(),planInput(),1000),first=plan.getJSONArray("steps").getJSONObject(0);
        assertThrows(IllegalStateException.class,()->book.onTaskCompleted(scope(),first.getString("id"),1,"just-an-event","task-one",evidence("just-an-event"),4000));
        assertEquals("pending",book.getPlan(scope(),plan.getString("id"),5000).getString("state"));assertEquals(0,book.getPlan(scope(),plan.getString("id"),5000).getInt("completedSteps"));
    }
    @Test public void planCancellationUsesAggregateRevisionAndInvalidatesEveryOpenClaimAtomically()throws Exception {
        CommitmentBook book=new CommitmentBook(file());book.setFollowUpPolicy(scope(),0,settings(5),1000);JSONObject plan=book.createPlan(scope(),planInput(),1000),first=plan.getJSONArray("steps").getJSONObject(0);
        String claim=token(claim(book,first,4000));book.update(scope(),first.getString("id"),1,new JSONObject().put("title","已修改的第一步"),4100);
        byte[] before=Files.readAllBytes(file().toPath());assertThrows(IllegalStateException.class,()->book.cancelPlan(scope(),plan.getString("id"),plan.getLong("revision"),4200));assertArrayEquals(before,Files.readAllBytes(file().toPath()));
        JSONObject current=book.getPlan(scope(),plan.getString("id"),4200),cancelled=book.cancelPlan(scope(),plan.getString("id"),current.getLong("revision"),4300);
        assertEquals("cancelled",cancelled.getString("state"));assertEquals(0,book.due(scope(),4400,10).getJSONArray("commitments").length());assertFalse(book.beginFollowUpDelivery(scope(),claim,4400).getBoolean("dispatchGranted"));
        for(int i=0;i<cancelled.getJSONArray("steps").length();i++)assertEquals("cancelled",cancelled.getJSONArray("steps").getJSONObject(i).getString("status"));
    }
    @Test public void expiredOrCancelledPredecessorBlocksLaterStepsAndDoesNotClaimCompletion()throws Exception {
        CommitmentBook book=new CommitmentBook(file());book.setFollowUpPolicy(scope(),0,settings(5),1000);JSONObject input=planInput();input.getJSONArray("steps").getJSONObject(0).put("expiresAt",3500);
        JSONObject plan=book.createPlan(scope(),input,1000),second=plan.getJSONArray("steps").getJSONObject(1);
        assertEquals("dependency_expired",evaluate(book,second,4000).getString("reason"));JSONObject view=book.getPlan(scope(),plan.getString("id"),4000);
        assertEquals("blocked",view.getString("state"));assertTrue(view.isNull("nextStep"));assertEquals(0,view.getInt("completedSteps"));assertEquals(1,book.proactivityState(scope(),4000).getInt("blockedSteps"));
    }
    @Test public void planScopeAndFailedCreationPreserveExistingState()throws Exception {
        CommitmentBook book=new CommitmentBook(file());JSONObject plan=book.createPlan(scope(),planInput(),1000);byte[] before=Files.readAllBytes(file().toPath());
        JSONObject other=new JSONObject().put("ownerId","other").put("spaceId","private");assertThrows(IllegalArgumentException.class,()->book.getPlan(other,plan.getString("id"),4000));assertEquals(0,book.listPlans(other,4000).length());
        JSONObject invalid=planInput();invalid.getJSONArray("steps").getJSONObject(1).put("dueAt",1000);
        assertThrows(IllegalArgumentException.class,()->book.createPlan(scope(),invalid,2000));assertArrayEquals(before,Files.readAllBytes(file().toPath()));
        assertEquals(2,book.list(scope(),4000).length());
    }
    @Test public void proactivityReadsNeverWriteAndPendingDoesNotMeanBackgroundExecution()throws Exception {
        CommitmentBook book=new CommitmentBook(file());book.createPlan(scope(),planInput(),1000);byte[] before=Files.readAllBytes(file().toPath());
        JSONObject state=book.proactivityState(scope(),4000);assertEquals(0,state.getInt("eligibleNow"));assertEquals(1,state.getInt("blockedSteps"));assertEquals("disabled",state.getJSONArray("decisions").getJSONObject(0).getString("reason"));
        assertFalse(state.getBoolean("executionAuthorized"));assertArrayEquals(before,Files.readAllBytes(file().toPath()));
    }
}
