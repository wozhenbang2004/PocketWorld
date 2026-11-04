package dev.pocketengine.store;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ExecutionPlanning;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The real original SQL scope, operations and approval transaction; no external effects. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TaskExecutionPlanningNativeTest {
    private TaskStore store;private String id,run;private int epoch;
    private RunControl control;private RunJournal journal;private ExecutionPlanning planning;
    private static SQLiteDatabase database(TaskStore store)throws Exception{Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(store);}
    @Before public void prepare()throws Exception{
        store=new TaskStore(RuntimeEnvironment.getApplication());id=store.createCapabilityTask("Inspect then review the exact plan","").getString("id");
        store.configureExecutionScope(id,0,0,16,16);
        JSONObject entered=store.enterExecutionPlanning(id,"",0,1,()->true);
        assertFalse(entered.getBoolean("executionStarted"));assertEquals("",entered.getString("runId"));
        JSONObject attempt=store.startAttempt(id,"capabilities",16);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");
        control=new RunControl();control.bindHostRun(id,run,epoch);
        store.authorizeTools(id,run,new JSONArray().put("memory_search").put("workspace_write").put("ssh_read_file"));
        store.status(id,"running","Controlled native planning test");journal=store.journal(id,run,epoch,control);planning=journal.planning();assertNotNull(planning);
    }
    @After public void close()throws Exception{if(control!=null)control.cancel();if(store!=null&&database(store).isOpen())database(store).close();}
    private JSONObject scope()throws Exception{return store.get(id,false).getJSONObject("executionScope");}
    private JSONObject plan(String title)throws Exception{return new JSONObject().put("format","pocketworld.execution-plan.v1").put("goal","Inspect original evidence before any approved changes").put("steps",new JSONArray().put(new JSONObject().put("id","inspect").put("title",title).put("detail","Read the original source, then ask for the exact plan revision.")));}
    private JSONObject submit(String title)throws Exception{return planning.submitPlan(planning.capture(),plan(title),"fixture-plan-"+title);}
    private JSONObject decide(JSONObject view,boolean approved,java.util.function.BooleanSupplier window)throws Exception{return store.decideExecutionPlanApproval(id,view.getString("approvalId"),approved,run,epoch,view.getLong("scopeRevision"),view.getLong("planRevision"),view.getString("planArtifactRef"),view.getString("planHash"),window);}

    @Test public void aNewOrdinaryTaskCanInitializeItsOriginalScopeWithoutPlanning()throws Exception{
        String ordinary=store.createCapabilityTask("Ordinary task without a plan","").getString("id");
        assertFalse(store.get(ordinary,false).has("executionScope"));
        JSONObject initialized=store.initializeExecutionScope(ordinary,8).getJSONObject("executionScope");
        assertEquals(1,initialized.getLong("revision"));assertEquals(8,initialized.getInt("toolLimit"));
        assertEquals(0,initialized.getInt("toolsUsed"));assertFalse(initialized.has("executionPhase"));
        assertFalse(TaskExecutionScope.planning(initialized));
    }

    @Test public void readsSpendTheOriginalBudgetAndNeverPrepareAnEffect()throws Exception{
        JSONObject args=new JSONObject().put("query","original evidence");var read=planning.beginRead(planning.capture(),"memory_search",args,"fixture-read",ExecutionPlanning.Access.READ);
        assertEquals(1,scope().getInt("toolsUsed"));assertEquals("read_pending",store.get(id,false).getJSONArray("operations").getJSONObject(0).getString("state"));assertFalse(store.hasUnresolved(id));
        JSONObject actual=new JSONObject(args.toString()).put("_hostOperationId",read.operationId);
        store.requireExecutionPlanningAccess(id,run,epoch,"memory_search",actual,control,ExecutionPlanning.Access.READ);
        assertThrows(SecurityException.class,()->store.requireExecutionPlanningAccess(id,run,epoch,"workspace_write",actual,control,ExecutionPlanning.Access.DENY));
        assertThrows(SecurityException.class,()->journal.beginOperation("workspace_write",new JSONObject().put("path","blocked.txt").put("content","never dispatched")));
        planning.finishRead(read,new JSONObject().put("ok",true).put("matches",new JSONArray()),ExecutionPlanning.ReadOutcome.RECEIPT);
        assertEquals("read_receipt",store.get(id,false).getJSONArray("operations").getJSONObject(0).getString("state"));assertEquals(1,scope().getInt("toolsUsed"));
    }

    @Test public void aJsonReadRowCannotRemintTheConsumedNativeClaim()throws Exception{
        JSONObject args=new JSONObject().put("path","original.txt");var read=planning.beginRead(planning.capture(),"ssh_read_file",args,"fixture-read",ExecutionPlanning.Access.READ);
        assertTrue(store.requireExecutionPlanningRead(id,run,epoch,read.operationId,"ssh_read_file",args,control));
        planning.finishRead(read,new JSONObject().put("ok",true),ExecutionPlanning.ReadOutcome.RECEIPT);
        ContentValues forged=new ContentValues();forged.put("state","read_pending");database(store).update("operations",forged,"operation_id=?",new String[]{read.operationId});
        assertThrows(SecurityException.class,()->store.requireExecutionPlanningRead(id,run,epoch,read.operationId,"ssh_read_file",args,control));
        assertEquals(1,scope().getInt("toolsUsed"));
    }

    @Test public void matchingJsonIdentityCannotBorrowAnotherNativeControl()throws Exception{
        JSONObject args=new JSONObject().put("query","evidence");var read=planning.beginRead(planning.capture(),"memory_search",args,"fixture-read",ExecutionPlanning.Access.READ);
        RunControl replacement=new RunControl();replacement.bindHostRun(id,run,epoch);
        assertThrows(SecurityException.class,()->store.requireExecutionPlanningRead(id,run,epoch,read.operationId,"memory_search",args,replacement));
        planning.finishRead(read,new JSONObject().put("ok",false),ExecutionPlanning.ReadOutcome.FAILED);
        assertEquals(1,scope().getInt("toolsUsed"));
    }

    @Test public void changedPlanInvalidatesTheExactOldReviewWithoutRefillingBudget()throws Exception{
        JSONObject first=submit("First reviewed draft"),second=submit("Changed draft");
        assertEquals(first.getLong("planRevision")+1,second.getLong("planRevision"));assertNotEquals(first.getString("planArtifactRef"),second.getString("planArtifactRef"));
        assertThrows(SecurityException.class,()->decide(first,true,()->true));
        assertThrows(SecurityException.class,()->store.decideCapabilityApproval(id,second.getString("approvalId"),true,run,epoch));
        JSONObject body=store.readTaskExecutionPlanApproval(id,second.getString("approvalId"));assertEquals("Changed draft",body.getJSONObject("plan").getJSONArray("steps").getJSONObject(0).getString("title"));
        int spent=scope().getInt("toolsUsed");JSONArray tools=new JSONArray(scope().getJSONArray("tools").toString());
        JSONObject accepted=decide(second,true,()->true);assertFalse(accepted.getBoolean("executionStarted"));assertFalse(accepted.getBoolean("newGrant"));
        assertEquals("executing",scope().getString("executionPhase"));assertEquals(spent,scope().getInt("toolsUsed"));assertEquals(16,scope().getInt("toolLimit"));assertEquals(tools.toString(),scope().getJSONArray("tools").toString());
    }

    @Test public void closingTheWindowAfterSqlWriteRollsBackTheWholeDecision()throws Exception{
        JSONObject pending=submit("Window-sensitive draft");String before=store.get(id,false).toString();AtomicInteger checks=new AtomicInteger();
        assertThrows(SecurityException.class,()->decide(pending,true,()->checks.incrementAndGet()<3));
        assertEquals(3,checks.get());assertEquals(before,store.get(id,false).toString());assertEquals("planning",scope().getString("executionPhase"));
    }

    @Test public void unreadableOriginalBodyCannotBeApprovedButCanBeDenied()throws Exception{
        JSONObject pending=submit("Original complete draft");JSONObject corrupt=new JSONObject().put("plan",plan("Replaced body")).put("planRevision",pending.getLong("planRevision")).put("planHash",pending.getString("planHash")).put("sourceTaskId",id).put("sourceRunId",run).put("sourceEpoch",epoch);
        ContentValues value=new ContentValues();value.put("arguments",corrupt.toString());database(store).update("operations",value,"operation_id=?",new String[]{pending.getString("planArtifactRef")});
        assertThrows(SecurityException.class,()->store.readTaskExecutionPlanApproval(id,pending.getString("approvalId")));
        assertThrows(SecurityException.class,()->decide(pending,true,()->true));
        JSONObject denied=decide(pending,false,()->true);assertEquals("denied",denied.getJSONArray("approvals").getJSONObject(0).getString("state"));assertEquals("planning",scope().getString("executionPhase"));
    }

    @Test public void scopeAndTokenBudgetEditsInvalidateAPlanWithoutRestoringSpentUnits()throws Exception{
        JSONObject first=submit("Budget-pinned draft");decide(first,true,()->true);int used=scope().getInt("toolsUsed");long revision=scope().getLong("revision");
        store.configureExecutionScope(id,revision,0,16,16);assertEquals("planning",scope().getString("executionPhase"));assertFalse(scope().has("planApproval"));assertEquals(used,scope().getInt("toolsUsed"));
        JSONObject second=submit("Token-pinned draft");decide(second,true,()->true);long old=scope().getLong("revision");
        JSONObject token=store.ownerTokenBudgetState(id,new JSONArray()).getJSONObject("task").getJSONObject("budget");store.setTaskTokenBudgetForOwner(id,token.getLong("revision"),true,10000,()->true);
        assertTrue(scope().getLong("revision")>old);assertEquals("planning",scope().getString("executionPhase"));assertEquals(used+1,scope().getInt("toolsUsed"));
    }

    @Test public void coldRecoveryKeepsPlanningAndTheSpentUnconfirmedRead()throws Exception{
        planning.beginRead(planning.capture(),"memory_search",new JSONObject().put("query","pending original read"),"fixture-pending",ExecutionPlanning.Access.READ);
        database(store).close();store=new TaskStore(RuntimeEnvironment.getApplication());
        JSONObject restored=store.get(id,false);assertEquals("planning",restored.getJSONObject("executionScope").getString("executionPhase"));assertEquals(1,restored.getJSONObject("executionScope").getInt("toolsUsed"));
        assertEquals("read_unconfirmed",restored.getJSONArray("operations").getJSONObject(0).getString("state"));assertEquals("awaiting_user",restored.getString("status"));assertFalse(store.hasUnresolved(id));
    }

    @Test public void modelPhaseRequestsAndFinalTextCannotApproveExecution()throws Exception{
        JSONObject request=planning.requestPhase(planning.capture(),"executing","Please review first","fixture-exit");
        assertTrue(request.getBoolean("requestOnly"));assertFalse(request.getBoolean("executionStarted"));assertEquals("planning",scope().getString("executionPhase"));assertEquals(1,scope().getInt("toolsUsed"));
        store.endAttempt(id,run,"completed","Model says it finished",new JSONObject());
        assertEquals("awaiting_user",store.get(id,false).getString("status"));assertEquals("planning",scope().getString("executionPhase"));
    }
}
