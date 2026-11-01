package dev.pocketengine.features.automation;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Host-effect fixtures only: no notification, model, task runner or Android scheduler. */
public class CommitmentFollowUpTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private File file(){return new File(folder.getRoot(),"commitments.json");}
    private JSONObject scope()throws Exception {return scope("owner","private");}
    private JSONObject scope(String owner,String space)throws Exception {return new JSONObject().put("ownerId",owner).put("spaceId",space);}
    private JSONObject settings()throws Exception {return new JSONObject().put("enabled",true).put("zoneId","UTC");}
    private JSONObject quiet(String zone,String start,String end)throws Exception {return settings().put("zoneId",zone).put("quietHours",new JSONObject().put("start",start).put("end",end));}
    private long at(String instant){return Instant.parse(instant).toEpochMilli();}
    private JSONObject create(CommitmentBook book)throws Exception{return create(book,scope(),2000);}
    private JSONObject create(CommitmentBook book,JSONObject identity,long due)throws Exception {
        return book.create(identity,new JSONObject().put("kind","explicit_user").put("title","核对真实结果").put("dueAt",due)
                .put("sourceRef",new JSONObject(identity.toString()).put("kind","user_action").put("id","user-request")),1000);
    }
    private void enable(CommitmentBook book)throws Exception {book.setFollowUpPolicy(scope(),0,settings(),1000);}
    private JSONObject evaluate(CommitmentBook book,JSONObject record,long now)throws Exception {
        return book.evaluateFollowUp(scope(),record.getString("id"),record.getLong("revision"),record.getString("candidateKey"),now);
    }
    private JSONObject claim(CommitmentBook book,JSONObject record,long now)throws Exception {
        return book.claimFollowUp(scope(),record.getString("id"),record.getLong("revision"),record.getString("candidateKey"),1000,now);
    }
    private String token(JSONObject claim)throws Exception{return claim.getJSONObject("receipt").getString("claimId");}
    private void reason(CommitmentBook book,JSONObject record,long now,String reason)throws Exception {
        JSONObject decision=evaluate(book,record,now);assertFalse(decision.toString(),decision.getBoolean("eligible"));assertEquals(reason,decision.getString("reason"));
    }

    @Test public void defaultsAreDisabledAndEvaluationIsReadOnlyIncludingLegacyFile()throws Exception {
        CommitmentBook book=new CommitmentBook(file());assertFalse(book.getFollowUpPolicy(scope()).getBoolean("enabled"));assertFalse(file().exists());
        JSONObject record=create(book);byte[] old=Files.readAllBytes(file().toPath());
        assertFalse(new JSONObject(new String(old,StandardCharsets.UTF_8)).has("followUpReceipts"));
        JSONObject decision=evaluate(book,record,3000);assertEquals("disabled",decision.getString("reason"));assertEquals("silent",decision.getString("decision"));
        assertTrue(decision.isNull("nextEligibleAt"));assertFalse(decision.getBoolean("executionAuthorized"));assertTrue(decision.getBoolean("requiresHostAdmission"));
        assertFalse(claim(book,record,3000).getBoolean("claimed"));assertArrayEquals(old,Files.readAllBytes(file().toPath()));
        enable(book);assertEquals(1,book.get(scope(),record.getString("id"),3000).getLong("revision"));
        assertTrue(evaluate(new CommitmentBook(file()),record,3000).getBoolean("eligible"));
        assertFalse(new CommitmentBook(file()).getFollowUpPolicy(scope("owner","group")).getBoolean("enabled"));
    }
    @Test public void explicitPolicyIsRevisionedAndValidatedWithoutSilentCoercion()throws Exception {
        CommitmentBook book=new CommitmentBook(file());enable(book);byte[] before=Files.readAllBytes(file().toPath());
        assertThrows(IllegalStateException.class,()->book.setFollowUpPolicy(scope(),0,settings(),2000));
        for(JSONObject invalid:new JSONObject[]{settings().put("enabled","true"),settings().put("zoneId","Unknown/Zone"),
                settings().put("cooldownMillis",1.5),settings().put("cooldownMillis",-1),settings().put("cooldownMillis",Long.MAX_VALUE),
                settings().put("pausedUntil",2000),settings().put("executionAuthorized",true),quiet("UTC","8:00","09:00"),quiet("UTC","08:00","08:00")})
            assertThrows(RuntimeException.class,()->book.setFollowUpPolicy(scope(),1,invalid,2000));
        assertArrayEquals(before,Files.readAllBytes(file().toPath()));
        JSONObject view=book.getFollowUpPolicy(scope());view.put("enabled",false);assertTrue(book.getFollowUpPolicy(scope()).getBoolean("enabled"));
        book.setFollowUpPolicy(scope(),1,settings().put("enabled",false),2000);assertFalse(new CommitmentBook(file()).getFollowUpPolicy(scope()).getBoolean("enabled"));
    }
    @Test public void crossMidnightQuietHoursUseExplicitZoneAndExactBoundaries()throws Exception {
        CommitmentBook book=new CommitmentBook(file());JSONObject record=create(book);book.setFollowUpPolicy(scope(),0,quiet("Asia/Shanghai","22:00","07:00"),1000);
        long start=at("2026-10-30T14:00:00Z"),end=at("2026-10-30T23:00:00Z");
        assertTrue(evaluate(book,record,start-1).getBoolean("eligible"));
        for(long time:new long[]{start,at("2026-10-30T17:00:00Z"),end-1}){
            reason(book,record,time,"quiet_hours");assertEquals(end,evaluate(book,record,time).getLong("nextEligibleAt"));
        }
        assertTrue(evaluate(book,record,end).getBoolean("eligible"));
    }
    @Test public void sameDayWindowAndFutureDueReturnActualNextEligibleInstant()throws Exception {
        CommitmentBook book=new CommitmentBook(file());long due=at("2026-10-30T12:30:00Z"),end=at("2026-10-30T13:00:00Z");
        JSONObject record=create(book,scope(),due);book.setFollowUpPolicy(scope(),0,quiet("UTC","12:00","13:00"),1000);
        JSONObject early=evaluate(book,record,due-7200000);assertFalse(early.getBoolean("eligible"));assertEquals(end,early.getLong("nextEligibleAt"));
        reason(book,record,due,"quiet_hours");assertTrue(evaluate(book,record,end).getBoolean("eligible"));
    }
    @Test public void springGapEndResolvesForwardAndFallOverlapStaysSilentThroughBothOffsets()throws Exception {
        CommitmentBook book=new CommitmentBook(file());JSONObject record=create(book);
        book.setFollowUpPolicy(scope(),0,quiet("America/New_York","22:00","02:30"),1000);
        long spring=at("2026-03-08T07:15:00Z");reason(book,record,spring,"quiet_hours");
        assertEquals(at("2026-03-08T07:30:00Z"),evaluate(book,record,spring).getLong("nextEligibleAt"));
        assertTrue(evaluate(book,record,at("2026-03-08T07:30:00Z")).getBoolean("eligible"));
        book.setFollowUpPolicy(scope(),1,quiet("America/New_York","00:30","01:30"),2000);
        for(long time:new long[]{at("2026-11-01T05:15:00Z"),at("2026-11-01T05:45:00Z"),at("2026-11-01T06:15:00Z")}){
            reason(book,record,time,"quiet_hours");assertEquals(at("2026-11-01T06:30:00Z"),evaluate(book,record,time).getLong("nextEligibleAt"));
        }
        assertTrue(evaluate(book,record,at("2026-11-01T06:30:00Z")).getBoolean("eligible"));
    }
    @Test public void userDisableAndPauseArePersistentAndQuietHoursApplyAtPauseEnd()throws Exception {
        CommitmentBook book=new CommitmentBook(file());JSONObject record=create(book);long pause=at("2026-10-30T23:00:00Z");
        book.setFollowUpPolicy(scope(),0,quiet("UTC","22:00","07:00").put("pausedUntil",pause),1000);
        JSONObject decision=evaluate(book,record,at("2026-10-30T12:00:00Z"));assertEquals(at("2026-10-31T07:00:00Z"),decision.getLong("nextEligibleAt"));
        book.setFollowUpPolicy(scope(),1,settings().put("pausedUntil",10000),2000);reason(book,record,3000,"paused");
        assertEquals(10000,evaluate(new CommitmentBook(file()),record,3000).getLong("nextEligibleAt"));
        assertTrue(evaluate(book,record,10000).getBoolean("eligible"));
        book.setFollowUpPolicy(scope(),2,settings().put("enabled",false),3000);reason(new CommitmentBook(file()),record,4000,"disabled");
    }
    @Test public void everyEndpointAndPolicyIsBoundToExactOwnerAndSpace()throws Exception {
        CommitmentBook book=new CommitmentBook(file());JSONObject record=create(book);enable(book);String claim=token(claim(book,record,3000));
        for(JSONObject other:new JSONObject[]{scope("other","private"),scope("owner","group")}){
            assertFalse(book.getFollowUpPolicy(other).getBoolean("enabled"));
            assertThrows(IllegalArgumentException.class,()->book.evaluateFollowUp(other,record.getString("id"),1,record.getString("candidateKey"),3000));
            assertThrows(IllegalArgumentException.class,()->book.claimFollowUp(other,record.getString("id"),1,record.getString("candidateKey"),1000,3000));
            assertThrows(IllegalArgumentException.class,()->book.beginFollowUpDelivery(other,claim,3100));
            assertThrows(IllegalArgumentException.class,()->book.getFollowUpReceipt(other,claim));
            assertThrows(IllegalArgumentException.class,()->book.recordFollowUpDelivery(other,claim,"notified",3200));
            book.setFollowUpPolicy(other,0,settings(),1000);
        }
        assertEquals(1,book.getFollowUpPolicy(scope()).getLong("revision"));assertTrue(book.beginFollowUpDelivery(scope(),claim,3100).getBoolean("dispatchGranted"));
    }
    @Test public void simultaneousInstancesAndEntryPointsClaimAndBeginOnlyOnce()throws Exception {
        CommitmentBook book=new CommitmentBook(file());JSONObject record=create(book);enable(book);
        ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<JSONObject>> futures=new ArrayList<>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{start.await();return claim(new CommitmentBook(file()),record,3000);}));
            start.countDown();int wins=0;String claimId=null;
            for(Future<JSONObject> future:futures){JSONObject result=future.get(5,TimeUnit.SECONDS);if(result.getBoolean("claimed")){wins++;claimId=token(result);}}
            assertEquals(1,wins);final String token=claimId;futures.clear();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->new CommitmentBook(file()).beginFollowUpDelivery(scope(),token,3100)));
            wins=0;for(Future<JSONObject> future:futures)if(future.get(5,TimeUnit.SECONDS).getBoolean("dispatchGranted"))wins++;
            assertEquals(1,wins);reason(new CommitmentBook(file()),record,900000,"delivery_unknown");
        }finally{pool.shutdownNow();}
    }
    @Test public void unusedLeaseExpiresSafelyAndOldTokenCannotSendOrRecordOutcome()throws Exception {
        CommitmentBook book=new CommitmentBook(file());JSONObject record=create(book);enable(book);String old=token(claim(book,record,3000));
        reason(book,record,3999,"claimed");assertFalse(book.beginFollowUpDelivery(scope(),old,4000).getBoolean("dispatchGranted"));
        assertEquals("lease_expired",book.beginFollowUpDelivery(scope(),old,4000).getString("reason"));
        String replacement=token(claim(new CommitmentBook(file()),record,4000));assertNotEquals(old,replacement);
        assertThrows(IllegalArgumentException.class,()->book.beginFollowUpDelivery(scope(),old,4001));
        assertThrows(IllegalArgumentException.class,()->book.recordFollowUpDelivery(scope(),old,"notified",4001));
        assertThrows(IllegalStateException.class,()->book.recordFollowUpDelivery(scope(),replacement,"notified",4001));
        assertTrue(book.beginFollowUpDelivery(scope(),replacement,4001).getBoolean("dispatchGranted"));
        assertFalse(claim(book,record,900000).getBoolean("claimed"));
    }
    @Test public void staleEditSnoozeCancelAndCompletionRejectOldCandidatesAndReservations()throws Exception {
        CommitmentBook book=new CommitmentBook(file(),(identity,record,kind,id)->new JSONObject(identity.toString()).put("kind",kind).put("id",id)
                .put("commitmentId",record.getString("id")).put("commitmentRevision",record.getLong("revision")).put("outcome","completed").put("recordedAt",3000));
        enable(book);
        for(String action:new String[]{"edit","snooze","cancel","complete"}){
            JSONObject record=create(book);String id=record.getString("id"),claim=token(claim(book,record,3000));
            if(action.equals("edit"))book.update(scope(),id,1,new JSONObject().put("title","新承诺说明"),3100);
            if(action.equals("snooze"))book.snooze(scope(),id,1,9000,3100);
            if(action.equals("cancel"))book.cancel(scope(),id,1,3100);
            if(action.equals("complete"))book.complete(scope(),id,1,new JSONObject().put("kind","owner_confirmation").put("id","real-host-fixture"),3100);
            assertFalse(book.beginFollowUpDelivery(scope(),claim,3200).getBoolean("dispatchGranted"));
            assertFalse(claim(book,record,3200).getBoolean("claimed"));
            reason(book,record,3200,action.equals("cancel")?"cancelled":action.equals("complete")?"completed":"stale_candidate");
        }
    }
    @Test public void policyEditsFenceReservationsAndBeginRechecksTime()throws Exception {
        CommitmentBook book=new CommitmentBook(file());long boundary=at("2026-10-30T22:00:00Z");JSONObject record=create(book);
        book.setFollowUpPolicy(scope(),0,quiet("UTC","22:00","07:00"),1000);
        String claim=token(claim(book,record,boundary-500));
        assertEquals("quiet_hours",book.beginFollowUpDelivery(scope(),claim,boundary).getString("reason"));
        book.setFollowUpPolicy(scope(),1,settings().put("enabled",false),2000);
        assertEquals("policy_changed",book.beginFollowUpDelivery(scope(),claim,boundary).getString("reason"));
        book.setFollowUpPolicy(scope(),2,settings(),3000);
        String fresh=token(claim(book,record,boundary));assertNotEquals(claim,fresh);assertTrue(book.beginFollowUpDelivery(scope(),fresh,boundary).getBoolean("dispatchGranted"));
    }
    @Test public void unknownSurvivesRestartAndCannotBeBypassedByRevisionOrLeaseExpiry()throws Exception {
        CommitmentBook book=new CommitmentBook(file());JSONObject record=create(book);enable(book);String claim=token(claim(book,record,3000));
        assertTrue(book.beginFollowUpDelivery(scope(),claim,3100).getBoolean("dispatchGranted"));
        CommitmentBook reopened=new CommitmentBook(file());reason(reopened,record,999999,"delivery_unknown");
        JSONObject revised=reopened.update(scope(),record.getString("id"),1,new JSONObject().put("title","只改标题"),4000);
        reason(reopened,revised,999999,"delivery_unknown");assertFalse(claim(reopened,revised,999999).getBoolean("claimed"));
        byte[] before=Files.readAllBytes(file().toPath());reopened.recordFollowUpDelivery(scope(),claim,"delivery_unknown",5000);
        assertArrayEquals(before,Files.readAllBytes(file().toPath()));
        reopened.recordFollowUpDelivery(scope(),claim,"not_sent",5100);assertTrue(evaluate(reopened,revised,999999).getBoolean("eligible"));
    }
    @Test public void knownReceiptsConsumeCandidateAcrossRestartAndConflictingResultIsRejected()throws Exception {
        CommitmentBook book=new CommitmentBook(file());enable(book);
        for(String outcome:new String[]{"notified","not_sent"}){
            JSONObject record=create(book);String claim=token(claim(book,record,3000));book.beginFollowUpDelivery(scope(),claim,3100);
            book.recordFollowUpDelivery(scope(),claim,outcome,3200);CommitmentBook reopened=new CommitmentBook(file());
            reason(reopened,record,900000,outcome.equals("notified")?"already_notified":"not_sent");assertFalse(claim(reopened,record,900000).getBoolean("claimed"));
            byte[] before=Files.readAllBytes(file().toPath());reopened.recordFollowUpDelivery(scope(),claim,outcome,900001);assertArrayEquals(before,Files.readAllBytes(file().toPath()));
            assertThrows(IllegalStateException.class,()->reopened.recordFollowUpDelivery(scope(),claim,"delivery_unknown",900001));
        }
    }
    @Test public void actualResultCanBeRecordedAfterCancellationWithoutRevivingPromise()throws Exception {
        CommitmentBook book=new CommitmentBook(file());enable(book);JSONObject record=create(book);String claim=token(claim(book,record,3000));
        book.beginFollowUpDelivery(scope(),claim,3100);book.cancel(scope(),record.getString("id"),1,3200);
        assertEquals("notified",book.recordFollowUpDelivery(scope(),claim,"notified",3300).getString("status"));
        assertEquals("cancelled",book.get(scope(),record.getString("id"),3400).getString("status"));
    }
    @Test public void scopeCooldownIncludesUnknownAttemptsAndSerializesDifferentCandidateClaims()throws Exception {
        CommitmentBook book=new CommitmentBook(file());book.setFollowUpPolicy(scope(),0,settings().put("cooldownMillis",5000),1000);
        JSONObject one=create(book),two=create(book);String claim=token(claim(book,one,3000));reason(book,two,3000,"scope_reserved");
        assertFalse(claim(book,two,3000).getBoolean("claimed"));book.beginFollowUpDelivery(scope(),claim,3100);
        reason(book,two,4000,"cooldown");assertEquals(8100,evaluate(book,two,4000).getLong("nextEligibleAt"));
        assertTrue(evaluate(book,two,8100).getBoolean("eligible"));
        JSONObject group=scope("owner","group");book.setFollowUpPolicy(group,0,settings().put("cooldownMillis",5000),1000);
        JSONObject separate=create(book,group,2000);assertTrue(book.claimFollowUp(group,separate.getString("id"),1,separate.getString("candidateKey"),1000,4000).getBoolean("claimed"));
        reason(book,one,8100,"delivery_unknown");
    }
    @Test public void failedPolicyClaimBeginAndReceiptWritesNeverLeakInMemorySuccess()throws Exception {
        CommitmentBook book=new CommitmentBook(file());enable(book);JSONObject record=create(book);byte[] before=Files.readAllBytes(file().toPath());
        File blocked=new File(file().getPath()+".new");assertTrue(blocked.mkdir());
        assertThrows(IOException.class,()->book.setFollowUpPolicy(scope(),1,settings().put("enabled",false),2000));
        assertThrows(IOException.class,()->claim(book,record,3000));assertArrayEquals(before,Files.readAllBytes(file().toPath()));
        assertTrue(evaluate(new CommitmentBook(file()),record,3000).getBoolean("eligible"));assertTrue(blocked.delete());
        String claim=token(claim(book,record,3000));before=Files.readAllBytes(file().toPath());assertTrue(blocked.mkdir());
        assertThrows(IOException.class,()->book.beginFollowUpDelivery(scope(),claim,3100));assertArrayEquals(before,Files.readAllBytes(file().toPath()));
        assertEquals("reserved",new CommitmentBook(file()).getFollowUpReceipt(scope(),claim).getString("status"));assertTrue(blocked.delete());
        assertTrue(book.beginFollowUpDelivery(scope(),claim,3100).getBoolean("dispatchGranted"));before=Files.readAllBytes(file().toPath());assertTrue(blocked.mkdir());
        assertThrows(IOException.class,()->book.recordFollowUpDelivery(scope(),claim,"notified",3200));assertArrayEquals(before,Files.readAllBytes(file().toPath()));
        reason(new CommitmentBook(file()),record,900000,"delivery_unknown");assertTrue(blocked.delete());
        assertEquals("notified",book.recordFollowUpDelivery(scope(),claim,"notified",3300).getString("status"));
    }
    @Test public void additiveV1MigrationRetainsCommitmentsAndLegacyReminderFileUntouched()throws Exception {
        File legacy=new File(folder.getRoot(),"rules.json");byte[] reminder="legacy reminder bytes owned by AutomationBook".getBytes(StandardCharsets.UTF_8);Files.write(legacy.toPath(),reminder);
        CommitmentBook old=new CommitmentBook(file());JSONObject record=create(old);JSONObject oldState=new JSONObject(new String(Files.readAllBytes(file().toPath()),java.nio.charset.StandardCharsets.UTF_8));
        oldState.put("futureHostMetadata",new JSONObject().put("retain",true));Files.write(file().toPath(),oldState.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CommitmentBook upgraded=new CommitmentBook(file());enable(upgraded);String claim=token(claim(upgraded,record,3000));upgraded.beginFollowUpDelivery(scope(),claim,3100);
        JSONObject disk=new JSONObject(new String(Files.readAllBytes(file().toPath()),java.nio.charset.StandardCharsets.UTF_8));assertEquals(1,disk.getInt("version"));assertTrue(disk.getJSONObject("futureHostMetadata").getBoolean("retain"));
        assertEquals(1,disk.getJSONArray("commitments").length());assertEquals(1,disk.getJSONArray("followUpPolicies").length());assertEquals(1,disk.getJSONArray("followUpReceipts").length());
        assertEquals(record.getString("title"),upgraded.get(scope(),record.getString("id"),4000).getString("title"));assertArrayEquals(reminder,Files.readAllBytes(legacy.toPath()));
    }
    @Test public void unknownRecoveryCanDiscoverClaimIdWithoutAnExternalLogAndNeverWrites()throws Exception {
        CommitmentBook book=new CommitmentBook(file());enable(book);JSONObject record=create(book);String claim=token(claim(book,record,3000));
        book.beginFollowUpDelivery(scope(),claim,3100);JSONObject later=book.update(scope(),record.getString("id"),1,new JSONObject().put("title","新修订"),4000);
        byte[] before=Files.readAllBytes(file().toPath());CommitmentBook restarted=new CommitmentBook(file());
        JSONObject recovered=restarted.listFollowUpReceipts(scope(),record.getString("id")).getJSONObject(0);
        assertEquals(claim,recovered.getString("claimId"));assertEquals("delivery_unknown",recovered.getString("status"));assertEquals(1,recovered.getLong("commitmentRevision"));
        recovered.put("status","not_sent");assertEquals("delivery_unknown",restarted.getFollowUpReceipt(scope(),claim).getString("status"));
        assertThrows(IllegalArgumentException.class,()->restarted.listFollowUpReceipts(scope("owner","group"),record.getString("id")));
        assertThrows(IllegalArgumentException.class,()->restarted.listFollowUpReceipts(scope("other","private"),record.getString("id")));
        reason(restarted,later,900000,"delivery_unknown");assertArrayEquals(before,Files.readAllBytes(file().toPath()));
        restarted.recordFollowUpDelivery(scope(),claim,"notified",5000);assertTrue(evaluate(restarted,later,900000).getBoolean("eligible"));
    }

}
