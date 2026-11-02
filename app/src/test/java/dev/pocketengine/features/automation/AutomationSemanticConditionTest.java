package dev.pocketengine.features.automation;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class AutomationSemanticConditionTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private JSONObject rule()throws Exception{return new JSONObject().put("title","满足条件才提醒").put("schedule",new JSONObject().put("type","interval").put("anchorAt",100000).put("intervalMillis",60000)).put("semanticCondition",new JSONObject().put("goal","核对公开价格低于阈值").put("sourceKey","public:price").put("sourceRevision",1));}
    private AutomationConditionOutcome outcome(String task,String answer)throws Exception{return AutomationConditionOutcome.checked(task,"public:price",1,new JSONObject().put("taskId",task).put("runId",UUID.randomUUID().toString()).put("epoch",1).put("observationId","read:1").put("sha256","1".repeat(64)),answer);}
    private JSONObject check(AutomationBook book,long now,String answer)throws Exception{JSONObject row=book.claimDue(now,10).getJSONObject(0);assertEquals("condition_pending",row.getString("status"));String task=UUID.randomUUID().toString();book.bindSemanticCheck(row.getString("id"),task,now);return book.completeSemanticCheck(row.getString("id"),outcome(task,answer),now);}
    @Test public void falseTrueTrueAndErrorRespectThePersistedRisingEdge()throws Exception{
        File file=new File(temp.getRoot(),"rules.json");AutomationBook book=new AutomationBook(file);book.save(rule(),99999);AtomicInteger notifications=new AtomicInteger();
        assertEquals("condition_not_met",check(book,100000,"{\"met\":false}").getString("status"));
        JSONObject rise=check(book,160000,"{\"met\":true}");assertEquals("dispatching",rise.getString("status"));book.dispatchReceipt(rise.getString("id"),row->{notifications.incrementAndGet();return new JSONObject().put("status","notified").put("message","原提醒投递");},160000);
        book=new AutomationBook(file);assertEquals("condition_check_failed",check(book,220000,"{\"met\":\"false\"}").getString("status"));assertTrue(book.rules().getJSONObject(0).getBoolean("lastKnownConditionMet"));
        assertEquals("condition_unchanged",check(book,280000,"{\"met\":true}").getString("status"));assertEquals(1,notifications.get());
        assertEquals("condition_not_met",check(book,340000,"{\"met\":false}").getString("status"));assertEquals("dispatching",check(book,400000,"{\"met\":true}").getString("status"));
    }
    @Test public void restartKeepsUnknownOriginalCheckAndNeverBeginsASecondOne()throws Exception{
        File file=new File(temp.getRoot(),"restart.json");AutomationBook book=new AutomationBook(file);book.save(rule().put("policy",new JSONObject().put("overlap","allow")),99999);JSONObject row=book.claimDue(100000,10).getJSONObject(0);String task=UUID.randomUUID().toString();book.bindSemanticCheck(row.getString("id"),task,100000);
        AutomationBook restored=new AutomationBook(file);restored.recoverInterrupted(100001);assertEquals("condition_check_unknown",restored.pendingSemanticChecks(20).getJSONObject(0).getString("status"));assertEquals(0,restored.claimDue(160000,10).length());assertThrows(SecurityException.class,()->restored.bindSemanticCheck(row.getString("id"),UUID.randomUUID().toString(),160000));
    }
    @Test public void changedSourceRevisionRefusesOldOutcomeAndModelCannotSupplyWitness()throws Exception{
        AutomationBook book=new AutomationBook(new File(temp.getRoot(),"revision.json"));JSONObject saved=book.save(rule(),99999),row=book.claimDue(100000,10).getJSONObject(0);String task=UUID.randomUUID().toString();book.bindSemanticCheck(row.getString("id"),task,100000);JSONObject update=rule().put("id",saved.getString("id")).put("revision",saved.getInt("revision"));update.getJSONObject("semanticCondition").put("sourceRevision",2);book.save(update,100001);
        assertNotEquals("dispatching",book.completeSemanticCheck(row.getString("id"),outcome(task,"{\"met\":true}"),100001).getString("status"));assertFalse(book.rules().getJSONObject(0).has("lastKnownConditionMet"));
        assertThrows(SecurityException.class,()->AutomationConditionOutcome.checked(task,"public:price",1,new JSONObject().put("taskId",task),"{\"met\":true}"));
    }
    @Test public void approvedPlanEdgePersistsAnUnknownAdmissionAndOnlyKeepsOneOriginalTask()throws Exception{
        File file=new File(temp.getRoot(),"plan-edge.json");AutomationBook book=new AutomationBook(file);book.save(rule(),99999);JSONObject row=book.claimDue(100000,10).getJSONObject(0);String check=UUID.randomUUID().toString();book.bindSemanticCheck(row.getString("id"),check,100000);
        assertEquals("condition_action_pending",book.completeSemanticCheck(row.getString("id"),outcome(check,"{\"met\":true}"),100000,true).getString("status"));
        AutomationBook restored=new AutomationBook(file);restored.recoverInterrupted(100001);assertEquals("condition_action_unknown",restored.pendingSemanticChecks(20).getJSONObject(0).getString("status"));assertEquals(0,restored.claimDue(160000,10).length());
        String original=UUID.randomUUID().toString();assertEquals("condition_action_registered",restored.recordSemanticPlanAction(row.getString("id"),original,false,100002).getString("status"));assertEquals(original,restored.recordSemanticPlanAction(row.getString("id"),original,false,100003).getString("conditionActionTaskId"));assertThrows(SecurityException.class,()->restored.recordSemanticPlanAction(row.getString("id"),UUID.randomUUID().toString(),false,100004));assertEquals(0,restored.pendingSemanticChecks(20).length());
    }

}
