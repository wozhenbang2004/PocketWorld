package dev.pocketengine.features.automation;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class AutomationConditionsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final AtomicBoolean network=new AtomicBoolean(true),charging=new AtomicBoolean(true);
    private final AtomicInteger battery=new AtomicInteger(80);
    private final AutomationConditions.Source source=now->new AutomationConditions.Snapshot(now,network.get()?"validated":"disconnected","system_validated",charging.get(),battery.get(),"system_battery_broadcast");
    private JSONObject wanted()throws Exception{return new JSONObject().put("requiresNetwork",true).put("requiresCharging",true).put("minBatteryPercent",50);}
    private JSONObject rule()throws Exception{return new JSONObject().put("title","条件任务").put("kind","phone_goal").put("goal","核对实际目标").put("schedule",new JSONObject().put("type","once").put("at",2000)).put("policy",wanted());}
    private JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private JSONObject promise(CommitmentBook book)throws Exception{return book.create(scope(),new JSONObject().put("kind","explicit_user").put("title","核对结果").put("dueAt",2000).put("sourceRef",new JSONObject(scope().toString()).put("kind","user_action").put("id","owner-request")),1000);}
    private JSONObject policy()throws Exception{return wanted().put("enabled",true).put("zoneId","UTC");}

    @Test public void oldDefaultsDoNotReadUnavailableConditions()throws Exception {
        AtomicInteger reads=new AtomicInteger();AutomationConditions.Source unavailable=now->{reads.incrementAndGet();throw new IllegalStateException();};
        assertTrue(AutomationConditions.evaluate(new JSONObject(),unavailable,2000).getBoolean("allowed"));assertEquals(0,reads.get());
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"legacy.json"),null,unavailable);JSONObject input=rule();input.remove("policy");book.save(input,1000);
        assertEquals(1,book.claimDue(2000,10).length());assertEquals(0,reads.get());
    }
    @Test public void everyRequiredConditionIsCheckedAndUnknownCannotBecomeOnline()throws Exception {
        assertTrue(AutomationConditions.evaluate(wanted(),source,2000).getBoolean("allowed"));
        network.set(false);charging.set(false);battery.set(49);JSONObject decision=AutomationConditions.evaluate(wanted(),source,2000);
        assertFalse(decision.getBoolean("allowed"));assertEquals(3,decision.getJSONArray("blockers").length());assertFalse(decision.getBoolean("executionAuthorized"));
        for(AutomationConditions.Source invalid:new AutomationConditions.Source[]{null,now->null,now->{throw new Exception("private exception");},now->new AutomationConditions.Snapshot(1,"validated","",true,100,""),now->new AutomationConditions.Snapshot(now+1,"validated","",true,100,"")}){
            JSONObject blocked=AutomationConditions.evaluate(wanted(),invalid,60000);assertFalse(blocked.getBoolean("allowed"));assertEquals("unavailable",blocked.getJSONObject("snapshot").getString("networkState"));assertFalse(blocked.toString().contains("private exception"));
        }
        charging.set(true);network.set(true);battery.set(50);assertTrue(AutomationConditions.evaluate(wanted(),source,2000).getBoolean("allowed"));
    }
    @Test public void blockedClaimDelaysSameOccurrenceWithoutConsumingBudget()throws Exception {
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"rules.json"),null,source);network.set(false);JSONObject saved=book.save(rule().put("policy",wanted().put("maxOccurrences",1)),1000);
        assertEquals(0,book.claimDue(2000,10).length());assertEquals(0,book.receipts().length());assertEquals(0,book.rules().getJSONObject(0).getLong("claimedCount"));assertEquals(2000+AutomationConditions.RECHECK_MILLIS,book.nextAt());
        network.set(true);book.recheckConditions(3000);JSONObject receipt=book.claimDue(3000,10).getJSONObject(0);assertEquals(2000,receipt.getLong("occurrenceAt"));assertEquals(saved.getString("id"),receipt.getString("ruleId"));assertEquals(1,book.rules().getJSONObject(0).getLong("claimedCount"));
    }
    @Test public void dispatchRechecksThenDefersSameClaimAndExplicitGoalAdmissionRechecksAgain()throws Exception {
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"dispatch.json"),null,source);book.save(rule(),1000);JSONObject receipt=book.claimDue(2000,10).getJSONObject(0);String id=receipt.getString("id");AtomicInteger effects=new AtomicInteger();
        charging.set(false);JSONObject blocked=book.dispatchReceipt(id,value->{effects.incrementAndGet();return new JSONObject().put("status","awaiting_user").put("message","通知提交");},2100);
        assertEquals("deferred",blocked.getString("status"));assertEquals(0,effects.get());charging.set(true);book.recheckConditions(3000);assertEquals(id,book.claimDue(3000,10).getJSONObject(0).getString("id"));
        book.dispatchReceipt(id,value->{effects.incrementAndGet();return new JSONObject().put("status","awaiting_user").put("message","等待机主");},3001);assertEquals(1,effects.get());
        battery.set(10);assertThrows(IllegalStateException.class,()->book.taskRequest(id,3100));battery.set(80);assertFalse(book.taskRequest(id,3200).getBoolean("executionAuthorized"));
    }
    @Test public void conditionRetryNeverExtendsExpiryOrReplaysUnknownEffect()throws Exception {
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"expiry.json"),null,source);network.set(false);book.save(rule().put("policy",wanted().put("expiresAt",4000)),1000);
        assertEquals(0,book.claimDue(2000,10).length());assertEquals(4000,book.nextAt());network.set(true);book.recheckConditions(4000);assertEquals(0,book.claimDue(4000,10).length());assertEquals("expired",book.receipts().getJSONObject(0).getString("status"));
        AutomationBook unknown=new AutomationBook(new File(temp.getRoot(),"unknown.json"),null,source);unknown.save(rule(),1000);String id=unknown.claimDue(2000,10).getJSONObject(0).getString("id");unknown.recoverInterrupted(2100);charging.set(false);unknown.recheckConditions(3000);charging.set(true);unknown.recheckConditions(4000);assertEquals(0,unknown.claimDue(4000,10).length());assertEquals("delivery_unknown",unknown.receipts().getJSONObject(0).getString("status"));
    }
    @Test public void conditionRecoveryNeverRevivesAnOldPausedOrEditedClaim()throws Exception {
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"revoked.json"),null,source);JSONObject saved=book.save(rule(),1000);String id=book.claimDue(2000,10).getJSONObject(0).getString("id");
        charging.set(false);book.dispatchReceipt(id,value->{throw new AssertionError("blocked effect");},2100);book.setEnabled(saved.getString("id"),false,2200);
        charging.set(true);book.recheckConditions(2300);assertEquals(0,book.claimDue(2300,10).length());assertEquals("cancelled",book.receipts().getJSONObject(0).getString("status"));assertThrows(IllegalStateException.class,()->book.taskRequest(id,2300));
        saved=book.save(rule().put("id",saved.getString("id")).put("enabled",true).put("schedule",new JSONObject().put("type","once").put("at",3000)),2400);
        assertEquals(1,book.claimDue(3000,10).length());assertEquals("cancelled",book.receipts().getJSONObject(0).getString("status"));assertThrows(IllegalStateException.class,()->book.taskRequest(id,3100));
    }
    @Test public void clockRollbackCannotAdvanceRuleClaimsDeferredClaimsOrFinalDispatch()throws Exception {
        File ruleFile=new File(temp.getRoot(),"clock-rule.json");AutomationBook blocked=new AutomationBook(ruleFile,null,source);network.set(false);blocked.save(rule(),1000);assertEquals(0,blocked.claimDue(2000,10).length());
        network.set(true);blocked.recheckConditions(1500);assertEquals(2000,blocked.nextAt());assertEquals(0,blocked.claimDue(1500,10).length());
        // A v1 recovery could already have persisted nextAt before the original occurrence.
        JSONObject legacy=new JSONObject(Files.readString(ruleFile.toPath()));legacy.getJSONArray("rules").getJSONObject(0).put("nextAt",1500);Files.writeString(ruleFile.toPath(),legacy.toString());
        assertEquals(0,blocked.claimDue(1500,10).length());assertEquals(2000,blocked.nextAt());assertEquals(1,blocked.claimDue(2000,10).length());
        AutomationBook deferred=new AutomationBook(new File(temp.getRoot(),"clock-deferred.json"),null,source);deferred.save(rule().put("policy",wanted().put("maxOccurrences",1).put("expiresAt",5000)),1000);String id=deferred.claimDue(2000,10).getJSONObject(0).getString("id");AtomicInteger effects=new AtomicInteger();
        network.set(false);assertEquals("deferred",deferred.dispatchReceipt(id,value->{throw new AssertionError("offline effect");},2100).getString("status"));network.set(true);deferred.recheckConditions(1500);
        assertEquals(2000,deferred.nextAt());assertEquals(0,deferred.claimDue(1500,10).length());assertEquals("deferred",deferred.receipts().getJSONObject(0).getString("status"));
        assertEquals(id,deferred.claimDue(2000,10).getJSONObject(0).getString("id"));
        JSONObject early=deferred.dispatchReceipt(id,value->{effects.incrementAndGet();return new JSONObject().put("status","awaiting_user").put("message","early");},1500);assertEquals("deferred",early.getString("status"));assertEquals(2000,early.getLong("dispatchAfter"));assertEquals(0,effects.get());
        assertEquals(id,deferred.claimDue(2000,10).getJSONObject(0).getString("id"));deferred.dispatchReceipt(id,value->{effects.incrementAndGet();return new JSONObject().put("status","awaiting_user").put("message","due");},2000);
        assertEquals(1,effects.get());assertEquals(1,deferred.rules().getJSONObject(0).getLong("claimedCount"));assertEquals(5000,deferred.receipts().getJSONObject(0).getLong("validUntil"));
    }
    @Test public void followUpClaimAndBeginRecheckLiveSourceAndKeepUnknownBarrier()throws Exception {
        CommitmentBook book=new CommitmentBook(new File(temp.getRoot(),"commitments.json"),null,source);book.setFollowUpPolicy(scope(),0,policy(),1000);JSONObject item=promise(book);
        String id=item.getString("id"),key=item.getString("candidateKey");network.set(false);assertFalse(book.claimFollowUp(scope(),id,1,key,1000,3000).getBoolean("claimed"));network.set(true);
        String claim=book.claimFollowUp(scope(),id,1,key,1000,3000).getJSONObject("receipt").getString("claimId");charging.set(false);assertFalse(book.beginFollowUpDelivery(scope(),claim,3100).getBoolean("dispatchGranted"));
        charging.set(true);String next=book.claimFollowUp(scope(),id,1,key,1000,4100).getJSONObject("receipt").getString("claimId");assertTrue(book.beginFollowUpDelivery(scope(),next,4200).getBoolean("dispatchGranted"));network.set(false);
        assertEquals("delivery_unknown",book.evaluateFollowUp(scope(),id,1,key,5000).getString("reason"));
    }
    @Test public void conditionsThatCannotBeMetBeforeExpiryStaySilentAndBadSettingsDoNotMutate()throws Exception {
        File file=new File(temp.getRoot(),"bounded.json");CommitmentBook book=new CommitmentBook(file,null,source);book.setFollowUpPolicy(scope(),0,policy(),1000);
        JSONObject item=book.create(scope(),new JSONObject().put("kind","explicit_user").put("title","限时").put("dueAt",2000).put("expiresAt",4000).put("sourceRef",new JSONObject(scope().toString()).put("kind","user_action").put("id","source")),1000);
        network.set(false);assertEquals("expires_before_eligible",book.evaluateFollowUp(scope(),item.getString("id"),1,item.getString("candidateKey"),3000).getString("reason"));byte[] prior=Files.readAllBytes(file.toPath());
        for(JSONObject bad:new JSONObject[]{policy().put("requiresNetwork","true"),policy().put("minBatteryPercent",-1),policy().put("minBatteryPercent",101),policy().put("minBatteryPercent",50.5)})assertThrows(IllegalArgumentException.class,()->book.setFollowUpPolicy(scope(),1,bad,3000));
        assertArrayEquals(prior,Files.readAllBytes(file.toPath()));
    }
}
