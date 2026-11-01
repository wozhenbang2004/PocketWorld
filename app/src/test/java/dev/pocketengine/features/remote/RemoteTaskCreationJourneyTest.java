package dev.pocketengine.features.remote;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.RemoteOwnerWorkflows;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import android.app.Application;
import static org.junit.Assert.*;

/** Real SQLite/FeatureHost/RemoteModule/owner-window chain, with an explicitly controlled non-CLI server. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteTaskCreationJourneyTest {
    @Before public void syntheticKeys(){java.security.Security.addProvider(new java.security.Provider("RemoteCreationFixtureKeyStore","1","Explicit test-only key; not Android hardware verification"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});}

    static final class Parent {
        final String id,run;final int epoch;final RunControl control=new RunControl();final RunJournal journal;final RemoteTaskCapabilities port;final JSONObject arguments;
        Parent(RemoteOwnerWorkflowIntegrationTest.Fixture f)throws Exception {
            f.remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","fixture computer").put("baseUrl",f.server.base()).put("token","fixture-only-token-longer-than-32-characters"));
            JSONObject task=f.tasks.createCapabilityTask("在电脑修复构建",UUID.randomUUID().toString());id=task.getString("id");task=f.tasks.startAttempt(id,"capabilities",20);run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);
            f.tasks.authorizeTools(id,run,new JSONArray().put(RemoteTaskCapabilities.START).put(RemoteTaskCapabilities.READ));f.tasks.status(id,"running","fixture original task");journal=f.tasks.journal(id,run,epoch,control);
            CapabilityPort empty=new CapabilityPort(){public JSONArray tools(){return new JSONArray();}public boolean supports(String name){return false;}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl original){throw new AssertionError("unexpected delegate");}};
            port=new RemoteTaskCapabilities(empty,f.tasks,f.host,f.remote,id,run,epoch,control);
            String binding=f.remote.snapshot().getJSONArray("profiles").getJSONObject(0).getString("bindingHash");
            arguments=new JSONObject().put("profileId","computer").put("bindingHash",binding).put("environmentId","workspace").put("environmentBinding","b".repeat(64)).put("worker","claude").put("prompt","修复构建并提供diff").put("readOnly",false);
        }
        JSONObject submit(RemoteOwnerWorkflowIntegrationTest.Fixture f)throws Exception {
            String first=journal.beginOperation(RemoteTaskCapabilities.START,arguments);JSONObject approval=port.execute(RemoteTaskCapabilities.START,new JSONObject(arguments.toString()).put("_hostOperationId",first),control);assertEquals("approval_required",approval.getString("code"));assertFalse(approval.getBoolean("dispatched"));assertEquals(1,f.server.submits.get());journal.finishOperation(first,approval);f.tasks.decideCapabilityApproval(id,approval.getString("approvalId"),true);
            String actual=journal.beginOperation(RemoteTaskCapabilities.START,arguments);JSONObject submitted=port.execute(RemoteTaskCapabilities.START,new JSONObject(arguments.toString()).put("_hostOperationId",actual),control);journal.finishOperation(actual,submitted);assertNotEquals(actual,submitted.getString("hostTaskId"));assertEquals(actual,submitted.getString("parentOperationId"));return submitted;
        }
    }
    private static JSONObject action(String child,JSONObject snapshot)throws Exception{return new JSONObject().put("taskId",child).put("windowId",snapshot.getString("windowId")).put("reviewId",snapshot.getString("reviewId"));}
    private static JSONObject open(RemoteOwnerWorkflowIntegrationTest.Fixture f,String child)throws Exception{return f.workflow.handle("open",new JSONObject().put("taskId",child).put("windowId",UUID.randomUUID().toString())).getJSONObject("snapshot");}
    @Test public void firstCreateApprovalActualResultThenContinueRemainOnOneOriginalRemoteTask()throws Exception {try(var f=new RemoteOwnerWorkflowIntegrationTest.Fixture("claude","running")){
        Parent parent=new Parent(f);JSONObject submitted=parent.submit(f);String child=submitted.getString("hostTaskId"),job=submitted.getString("id");assertEquals(2,f.server.submits.get());f.server.approval(job);
        JSONObject snapshot=open(f,child);f.workflow.handle("approve",action(child,snapshot).put("requestId","request-one").put("decision","accept"));assertEquals(1,f.server.decisions.get());
        f.server.jobs.get(job).put("status","completed").put("output","fixture build failed then fixed; actual server output").put("diff","--- a/main.py\n+++ b/main.py\n+import fixed\n").put("controls",new JSONObject().put("threadResume",true));
        snapshot=f.workflow.handle("refresh",action(child,snapshot)).getJSONObject("snapshot");assertEquals("driver_report",snapshot.getJSONObject("result").getString("kind"));assertTrue(snapshot.getJSONObject("result").getString("diff").contains("import fixed"));assertTrue(snapshot.getBoolean("canContinue"));
        String oldRun=f.tasks.get(child,false).getString("runId");f.workflow.handle("continue",action(child,snapshot).put("prompt","继续修正原成果"));JSONObject continued=f.tasks.get(child,false);assertEquals(child,continued.getString("id"));assertNotEquals(oldRun,continued.getString("runId"));assertEquals(3,f.server.submits.get());assertEquals(1,f.tasks.get(parent.id,false).getJSONArray("relatedTasks").length());
    }}
    @Test public void originalChildRemainsReviewableAfterLegitimateParentNewRun()throws Exception {try(var f=new RemoteOwnerWorkflowIntegrationTest.Fixture("claude","running")){
        Parent parent=new Parent(f);JSONObject submitted=parent.submit(f);String child=submitted.getString("hostTaskId");f.tasks.endAttempt(parent.id,parent.run,"awaiting_user","等待电脑",new JSONObject());JSONObject next=f.tasks.startAttempt(parent.id,"capabilities",2);assertNotEquals(parent.run,next.getString("runId"));assertEquals(parent.run,f.tasks.requirePrivateRemoteChild(child).getString("parentRunId"));f.server.approval(submitted.getString("id"));JSONObject snapshot=open(f,child);f.workflow.handle("approve",action(child,snapshot).put("requestId","request-one").put("decision","accept"));assertEquals(1,f.server.decisions.get());
    }}
    @Test public void missingOldParentRunAndMissingRelatedLinkAreRefused()throws Exception {try(var f=new RemoteOwnerWorkflowIntegrationTest.Fixture("claude","running")){
        Parent parent=new Parent(f);String child=parent.submit(f).getString("hostTaskId");JSONObject original=f.tasks.get(child,false),forged=new JSONObject(original.toString()).put("parentRunId",UUID.randomUUID().toString());write(child,forged);assertThrows(SecurityException.class,()->f.tasks.requirePrivateRemoteChild(child));write(child,original);JSONObject parentDoc=f.tasks.get(parent.id,false);parentDoc.put("relatedTasks",new JSONArray());write(parent.id,parentDoc);assertThrows(SecurityException.class,()->f.tasks.requirePrivateRemoteChild(child));assertEquals(0,f.server.decisions.get());
    }}
    @Test public void malformedTeamWithoutTeamRunIdAndChannelAncestorNeverDowngradeToPrivate()throws Exception {try(var f=new RemoteOwnerWorkflowIntegrationTest.Fixture("claude","running")){
        Parent parent=new Parent(f);JSONObject submitted=parent.submit(f);String child=submitted.getString("hostTaskId");JSONObject original=f.tasks.get(child,false);write(child,new JSONObject(original.toString()).put("memberId","member-one"));f.server.approval(submitted.getString("id"));JSONObject snapshot=open(f,child);assertThrows(SecurityException.class,()->f.workflow.handle("approve",action(child,snapshot).put("requestId","request-one").put("decision","accept")));assertEquals(0,f.server.decisions.get());write(child,original);
        JSONObject parentDoc=f.tasks.get(parent.id,false);parentDoc.put("origin",new JSONObject().put("kind","channel"));write(parent.id,parentDoc);assertThrows(SecurityException.class,()->f.tasks.requirePrivateRemoteChild(child));assertThrows(SecurityException.class,()->open(f,child));assertEquals(0,f.server.decisions.get());
    }}
    @Test public void lostFirstCreateAckOnlyQueriesOriginalChildAndSettlesParentDelegationNotBusinessSuccess()throws Exception {try(var f=new RemoteOwnerWorkflowIntegrationTest.Fixture("claude","running")){
        Parent parent=new Parent(f);f.server.dropSubmit=true;JSONObject unknown=parent.submit(f);String child=unknown.getString("hostTaskId");assertEquals("unknown",unknown.getString("status"));assertTrue(f.tasks.hasUnresolved(parent.id));f.tasks.endAttempt(parent.id,parent.run,"awaiting_user","派发待核对",new JSONObject());
        JSONObject reconciled=open(f,child);assertEquals(child,reconciled.getString("taskId"));assertEquals(2,f.server.submits.get());assertFalse(f.tasks.hasUnresolved(parent.id));assertEquals("awaiting_user",f.tasks.get(parent.id,false).getString("status"));
        JSONObject parentReceipt=null;JSONArray operations=f.tasks.get(parent.id,false).getJSONArray("operations");for(int i=0;i<operations.length();i++)if(operations.getJSONObject(i).getString("operationId").equals(unknown.getString("parentOperationId")))parentReceipt=operations.getJSONObject(i).getJSONObject("receipt");assertNotNull(parentReceipt);assertTrue(parentReceipt.getBoolean("reconciledAcceptance"));assertFalse(parentReceipt.getBoolean("remoteEffectsVerified"));assertEquals(1,f.tasks.get(parent.id,false).getJSONArray("relatedTasks").length());
    }}
    @Test public void changedCredentialGenerationAfterApprovalRefusesOldSelectionBeforeAnotherPost()throws Exception {try(var f=new RemoteOwnerWorkflowIntegrationTest.Fixture("claude","running")){
        Parent parent=new Parent(f);String operation=parent.journal.beginOperation(RemoteTaskCapabilities.START,parent.arguments);JSONObject approval=parent.port.execute(RemoteTaskCapabilities.START,new JSONObject(parent.arguments.toString()).put("_hostOperationId",operation),parent.control);parent.journal.finishOperation(operation,approval);f.tasks.decideCapabilityApproval(parent.id,approval.getString("approvalId"),true);
        f.remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","changed credential").put("baseUrl",f.server.base()).put("token","fixture-only-replacement-token-longer-than-32-characters"));String next=parent.journal.beginOperation(RemoteTaskCapabilities.START,parent.arguments);
        assertThrows(SecurityException.class,()->parent.port.execute(RemoteTaskCapabilities.START,new JSONObject(parent.arguments.toString()).put("_hostOperationId",next),parent.control));assertEquals(1,f.server.submits.get());assertEquals(0,f.tasks.get(parent.id,false).optJSONArray("relatedTasks")==null?0:f.tasks.get(parent.id,false).getJSONArray("relatedTasks").length());
    }}
    private static void write(String id,JSONObject document)throws Exception {try(SQLiteDatabase db=SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("pocket-world.db").getPath(),null,SQLiteDatabase.OPEN_READWRITE)){ContentValues value=new ContentValues();value.put("document",document.toString());assertEquals(1,db.update("tasks",value,"task_id=?",new String[]{id}));}}
}
