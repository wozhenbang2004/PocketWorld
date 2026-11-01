package dev.pocketengine.store;

import android.app.Application;
import android.content.Context;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TaskRecoveryTest {
    private Context context(){return RuntimeEnvironment.getApplication();}
    private RunJournal journal(TaskStore store,JSONObject task,RunControl control)throws Exception{return store.journal(task.getString("id"),task.getString("runId"),task.getInt("coordinatorEpoch"),control);}
    @Test public void restartPreservesContextAndContinuesTheSameTaskWithANewEpoch()throws Exception{
        TaskStore first=new TaskStore(context());String id=first.create("继续原项目","background").getString("id");
        JSONObject attempt=first.startAttempt(id,"background",20);RunJournal old=journal(first,attempt,new RunControl());
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","已找到项目，待检查结果"));
        old.checkpoint(messages,3,new JSONObject().put("packageName","example.app"));first.feedback(id,"保留现有文件");
        TaskStore resumed=new TaskStore(context());assertEquals("paused",resumed.get(id,false).getString("status"));
        JSONObject next=resumed.startAttempt(id,"background",20);assertEquals(attempt.getInt("coordinatorEpoch")+1,next.getInt("coordinatorEpoch"));
        JSONObject saved=journal(resumed,next,new RunControl()).restore();
        assertEquals(3,saved.getInt("steps"));assertEquals(messages.toString(),saved.getJSONArray("messages").toString());assertEquals("保留现有文件",saved.getString("feedback"));
        assertThrows(SecurityException.class,()->old.checkpoint(new JSONArray(),0,new JSONObject()));
        assertEquals(1,resumed.list().length());
    }
    @Test public void unknownOperationRequiresReconciliationAndItsOutcomeSurvivesResume()throws Exception{
        TaskStore store=new TaskStore(context());String id=store.create("发送一次消息","main_screen").getString("id");
        JSONObject attempt=store.startAttempt(id,"main_screen",20);RunJournal journal=journal(store,attempt,new RunControl());
        String op=journal.beginOperation("phone_type",new JSONObject().put("text","hello"));
        TaskStore restarted=new TaskStore(context());assertEquals("reconciling",restarted.get(id,false).getString("status"));
        assertThrows(IllegalStateException.class,()->restarted.startAttempt(id,"main_screen",20));
        restarted.resolveOperation(id,op,"occurred");JSONObject next=restarted.startAttempt(id,"main_screen",20);
        JSONArray ledger=journal(restarted,next,new RunControl()).restore().getJSONArray("operationResults");
        assertEquals("occurred",ledger.getJSONObject(0).getJSONObject("receipt").getString("outcome"));
        assertEquals("resolved",ledger.getJSONObject(0).getString("state"));
    }
    @Test public void lateKnownReceiptIsSavedAfterStopButNoNewOperationIsAuthorized()throws Exception{
        TaskStore store=new TaskStore(context());String id=store.create("检查动作").getString("id");JSONObject attempt=store.startAttempt(id,"main_screen",20);
        RunControl control=new RunControl();RunJournal journal=journal(store,attempt,control);String op=journal.beginOperation("phone_tap",new JSONObject());
        store.requestCancel(id,attempt.getString("runId"));control.cancel();
        journal.finishOperation(op,new JSONObject().put("ok",true).put("accepted",true));
        assertFalse(store.hasUnresolved(id));assertThrows(InterruptedException.class,()->journal.beginOperation("phone_tap",new JSONObject()));
        store.endAttempt(id,attempt.getString("runId"),"cancelled","已停止",new JSONObject());
        assertEquals("receipt",store.get(id,false).getJSONArray("operations").getJSONObject(0).getString("state"));
    }
    @Test public void unknownReceiptFencesFurtherDispatchAndFinishesAsReconciling()throws Exception{
        TaskStore store=new TaskStore(context());String id=store.create("只提交一次").getString("id");JSONObject attempt=store.startAttempt(id,"main_screen",20);
        RunJournal journal=journal(store,attempt,new RunControl());String op=journal.beginOperation("phone_tap",new JSONObject());
        journal.finishOperation(op,new JSONObject().put("code","action_unknown").put("accepted",JSONObject.NULL));
        assertThrows(IllegalStateException.class,()->journal.beginOperation("phone_tap",new JSONObject()));
        store.endAttempt(id,attempt.getString("runId"),"needs_confirmation","检查结果",new JSONObject());
        assertEquals("reconciling",store.get(id,false).getString("status"));
    }
    @Test public void phoneResourceAndActionCapabilitiesAreHostEnforced()throws Exception{
        TaskStore store=new TaskStore(context());String a=store.create("A").getString("id"),b=store.create("B").getString("id");
        JSONObject attempt=store.startAttempt(a,"main_screen",1);RunJournal journal=journal(store,attempt,new RunControl());
        assertThrows(IllegalStateException.class,()->store.startAttempt(b,"main_screen",20));
        assertThrows(SecurityException.class,()->journal.beginOperation("shell",new JSONObject()));
        String op=journal.beginOperation("phone_back",new JSONObject());journal.finishOperation(op,new JSONObject().put("ok",true));
        assertThrows(IllegalStateException.class,()->journal.beginOperation("phone_back",new JSONObject()));
        store.endAttempt(a,attempt.getString("runId"),"cancelled","stop",new JSONObject());assertNotNull(store.startAttempt(b,"main_screen",20));
    }
    @Test public void capabilityApprovalSurvivesResumeButCannotChangeArgumentsOrRunTwice()throws Exception{
        TaskStore store=new TaskStore(context());String id=store.createCapabilityTask("保存文件","conversation").getString("id");JSONObject first=store.startAttempt(id,"capabilities",20);
        JSONObject args=new JSONObject().put("path","a.txt").put("content","x".repeat(16000));JSONObject pending=store.requireCapabilityApproval(id,first.getString("runId"),"workspace_write",args);
        assertEquals(16000,store.get(id,false).getJSONArray("approvals").getJSONObject(0).getJSONObject("arguments").getString("content").length());
        store.endAttempt(id,first.getString("runId"),"awaiting_user","需要审批",new JSONObject());store.decideCapabilityApproval(id,pending.getString("approvalId"),true);
        JSONObject next=store.startAttempt(id,"capabilities",20);JSONObject reordered=new JSONObject().put("content",args.getString("content")).put("path","a.txt");
        assertTrue(store.requireCapabilityApproval(id,next.getString("runId"),"workspace_write",reordered).getBoolean("approved"));
        assertFalse(store.requireCapabilityApproval(id,next.getString("runId"),"workspace_write",reordered).getBoolean("approved"));
        assertFalse(store.requireCapabilityApproval(id,next.getString("runId"),"workspace_write",new JSONObject().put("path","different.txt").put("content","x")).getBoolean("approved"));
    }
    @Test public void aFinishedCapabilityRunCannotUseItsPreviouslyGrantedTools()throws Exception{
        TaskStore store=new TaskStore(context());String id=store.createCapabilityTask("读取项目","").getString("id");JSONObject task=store.startAttempt(id,"capabilities",20);
        store.authorizeTools(id,task.getString("runId"),new JSONArray().put("workspace_read"));RunControl control=new RunControl();
        store.checkCapability(id,task.getString("runId"),task.getInt("coordinatorEpoch"),"workspace_read",control);
        store.endAttempt(id,task.getString("runId"),"completed","已回复",new JSONObject());
        assertThrows(SecurityException.class,()->store.checkCapability(id,task.getString("runId"),task.getInt("coordinatorEpoch"),"workspace_read",control));
    }
    @Test public void sshUncertaintySurvivesRestartAndBlocksAccountAliasesUntilOwnerReconciles()throws Exception{
        TaskStore store=new TaskStore(context());String id="ssh-original";JSONObject input=new JSONObject().put("profileId","first").put("_resourceId","same-account");
        store.beginExternal("ssh","exec",id,input);store.updateExternal(id,new JSONObject().put("runId",id).put("status","running"));
        TaskStore restarted=new TaskStore(context());assertEquals("reconciling",restarted.get(id,false).getString("status"));
        JSONObject alias=new JSONObject().put("profileId","alias").put("_resourceId","same-account");
        assertThrows(IllegalStateException.class,()->restarted.beginExternal("ssh","writeFile","blocked",alias));
        assertNotNull(restarted.beginExternal("ssh","readFile","inspection",alias));
        restarted.updateExternal("inspection",new JSONObject().put("status","completed"));
        restarted.reconcileExternalTask(id,"occurred","已检查远端进程结束和文件状态");
        restarted.updateExternal(id,new JSONObject().put("runId",id).put("status","unknown"));
        assertEquals("needs_confirmation",restarted.get(id,false).getString("status"));
        assertNotNull(restarted.beginExternal("ssh","exec","next",alias));
    }
    @Test public void lateSshRunningDoesNotClearUnknownAndOldTerminalRunCannotDispatch()throws Exception{
        TaskStore store=new TaskStore(context());String id="ssh-unknown";store.beginExternal("ssh","openPty",id,new JSONObject().put("_resourceId","account"));
        store.updateExternal(id,new JSONObject().put("runId",id).put("status","unknown"));
        store.updateExternal(id,new JSONObject().put("runId",id).put("status","running"));assertEquals("reconciling",store.get(id,false).getString("status"));
        assertThrows(SecurityException.class,()->store.requireExternalRun(id,id,"ssh",true));
        assertThrows(SecurityException.class,()->store.requireExternalRun(id,"old-run","ssh",false));
        assertNotNull(store.requireExternalRun(id,id,"ssh",false));
    }
    @Test public void externalTeamChildrenKeepOneHostIdentityAndNeverRebindAnExistingOperation()throws Exception{
        TaskStore store=new TaskStore(context());String parent=java.util.UUID.randomUUID().toString(),operation=java.util.UUID.randomUUID().toString();store.beginExternal("teams","start",parent,new JSONObject().put("goal","研究项目"));
        JSONObject request=new JSONObject().put("runId",parent).put("hostTaskId",parent).put("memberId","researcher").put("round",1).put("generation",1).put("operationId",operation);
        JSONObject binding=new JSONObject().put("worker","codex_app_server").put("readOnly",true).put("profileId","computer").put("environmentId","workspace");
        assertFalse(store.prepareTeamChild(request,binding).getBoolean("knownOperation"));assertTrue(store.prepareTeamChild(request,binding).getBoolean("knownOperation"));
        assertEquals(parent,store.get(operation,false).getString("parentTaskId"));assertEquals(operation,store.get(parent,false).getJSONArray("relatedTasks").getJSONObject(0).getString("taskId"));
        assertThrows(SecurityException.class,()->store.prepareTeamChild(request,new JSONObject(binding.toString()).put("profileId","another-computer")));
        store.updateExternal(operation,new JSONObject().put("status","awaiting_approval").put("pendingApprovals",new JSONArray().put(new JSONObject().put("requestId","actual-request"))));
        assertEquals("queued",store.get(operation,false).getString("status")); // Identity-less positive notices are not job receipts.
        store.updateExternal(operation,new JSONObject().put("operationId",operation).put("id","actual-job").put("profileId","computer").put("serviceBase","https://computer.example").put("status","awaiting_approval").put("pendingApprovals",new JSONArray().put(new JSONObject().put("requestId","actual-request"))));
        assertEquals("awaiting_user",store.get(operation,false).getString("status"));
        store.requestCancel(parent,parent);request.put("operationId",java.util.UUID.randomUUID().toString());assertThrows(SecurityException.class,()->store.prepareTeamChild(request,binding));
    }
}
