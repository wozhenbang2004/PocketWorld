package dev.pocketengine.features.automation;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class AutomationGoalStateTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private JSONObject rule()throws Exception{return new JSONObject().put("title","核对").put("kind","phone_goal").put("goal","核对真实结果").put("schedule",new JSONObject().put("type","once").put("at",2000));}
    private JSONObject task(JSONObject receipt)throws Exception {
        return new JSONObject().put("taskId","original-task").put("occurrenceId",receipt.getString("id")).put("ruleId",receipt.getString("ruleId"))
                .put("ruleRevision",receipt.getInt("revision")).put("actorId","owner").put("spaceId","private").put("goal",receipt.getString("goal"))
                .put("status","running").put("authorizationValid",true).put("resultVerified",false);
    }
    @Test public void notificationAcceptanceAndPlainCompletedNeverEstablishGoalSuccess()throws Exception {
        File file=new File(folder.getRoot(),"rules.json");AtomicReference<JSONObject> authority=new AtomicReference<>();AutomationBook book=new AutomationBook(file,id->authority.get());
        book.save(rule(),1000);JSONObject receipt=book.claimDue(2000,10).getJSONObject(0);String id=receipt.getString("id");
        book.delivered(id,"awaiting_user","只是通知提交",2100);assertEquals("awaiting_user",book.goalState(id).getString("state"));
        authority.set(task(receipt));assertEquals("running",book.goalState(id).getString("state"));
        authority.set(task(receipt).put("status","completed"));assertEquals("unverified_result",book.goalState(id).getString("state"));
        authority.set(task(receipt).put("status","completed").put("resultVerified",true));assertEquals("unverified_result",book.goalState(id).getString("state"));
        authority.get().put("evidenceRef",new JSONObject().put("kind","task_result").put("id","verified-original-goal"));
        byte[] before=Files.readAllBytes(file.toPath());JSONObject result=book.goalState(id);
        assertEquals("succeeded",result.getString("state"));assertEquals("original-task",result.getString("taskId"));assertFalse(result.getBoolean("executionAuthorized"));
        assertArrayEquals(before,Files.readAllBytes(file.toPath()));assertEquals("awaiting_user",book.receipts().getJSONObject(0).getString("status"));
        assertEquals("succeeded",book.receipts().getJSONObject(0).getJSONObject("goalState").getString("state"));
    }
    @Test public void exactScopeOccurrenceRuleAndGoalBindingRejectCrossTaskEvidence()throws Exception {
        File file=new File(folder.getRoot(),"rules.json");AtomicReference<JSONObject> authority=new AtomicReference<>();AutomationBook book=new AutomationBook(file,id->authority.get());
        book.save(rule(),1000);JSONObject receipt=book.claimDue(2000,10).getJSONObject(0);String id=receipt.getString("id");
        for(String field:new String[]{"occurrenceId","ruleId","actorId","spaceId","goal","ruleRevision"}){
            authority.set(task(receipt).put(field,field.equals("ruleRevision")?2:"other"));assertEquals(field,"evidence_mismatch",book.goalState(id).getString("state"));
        }
        authority.set(task(receipt).put("authorizationValid",false));assertEquals("awaiting_authorization",book.goalState(id).getString("state"));
        authority.set(task(receipt).put("authorizationValid","true"));assertEquals("awaiting_authorization",book.goalState(id).getString("state"));
        authority.set(task(receipt).put("status","reconciling"));assertEquals("reconciling",book.goalState(id).getString("state"));
    }
    @Test public void missingOrFailedHostLookupIsExplicitAndNeverWritesTaskState()throws Exception {
        File file=new File(folder.getRoot(),"rules.json");AutomationBook book=new AutomationBook(file);book.save(rule(),1000);String id=book.claimDue(2000,10).getJSONObject(0).getString("id");
        assertEquals("host_evidence_unavailable",book.goalState(id).getString("state"));byte[] before=Files.readAllBytes(file.toPath());
        AutomationBook failing=new AutomationBook(file,occurrence->{throw new IllegalStateException("private native diagnostic");});
        assertEquals("host_evidence_unavailable",failing.goalState(id).getString("state"));assertArrayEquals(before,Files.readAllBytes(file.toPath()));
        assertFalse(failing.goalState(id).toString().contains("private native diagnostic"));
    }
}
