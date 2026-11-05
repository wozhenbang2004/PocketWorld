package dev.pocketengine.features.local.project;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Native SQL and private-checkout references; no live provider or Android UI claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ProjectApprovalContextTest {
    private TaskStore tasks; private LocalModule local; private ProjectCapabilityPort port;
    private RunControl control; private String id,run,project,checkout; private int epoch;
    @Before public void create()throws Exception {
        var android=RuntimeEnvironment.getApplication();var context=new FeatureContext(android,null,null,event->{});
        var workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace);tasks=new TaskStore(android);
        project=workspace.handle("create",new JSONObject().put("name","Original approved proposal context")).getString("id");
        checkout=local.handle("prepare",new JSONObject().put("projectId",project)).getString("id");
        id=tasks.createCapabilityTask("Create only approved.txt and read it back","").getString("id");
        tasks.initializeExecutionScope(id,20);start(true);
    }
    private void start(boolean bind)throws Exception {
        JSONObject task=tasks.startAttempt(id,"capabilities",20);run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");
        control=new RunControl();control.bindHostRun(id,run,epoch);
        if(bind)tasks.bindDevelopmentContext(id,run,epoch,control,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime","termux").put("initialSourceDigest",ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest")).put("clientMessageId",UUID.randomUUID().toString()));
        var binding=new ModelCapabilityAdapters.Binding(id,run,epoch,control,true,project);
        port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),control,null,local,tasks,checkout,"termux");
        JSONArray names=new JSONArray(),tools=port.tools();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(id,run,names);
    }
    private JSONObject approvedInPriorRun()throws Exception {
        JSONObject inspected=port.execute("project_inspect",new JSONObject(),control);
        JSONObject plan=port.execute("project_plan",new JSONObject().put("intent","edit").put("path","approved.txt").put("content","EXACT-CONTENT").put("expectedHash","absent").put("expectedSourceDigest",inspected.getString("sourceDigest")),control);
        JSONObject args=new JSONObject().put("planRef",plan.getString("planRef")).put("planDigest",plan.getString("planDigest")).put("stepId","edit");
        RunJournal journal=tasks.journal(id,run,epoch,control);String operation=journal.beginOperation("project_execute",args);
        JSONObject receipt=port.execute("project_execute",new JSONObject(args.toString()).put("_hostOperationId",operation),control);
        assertEquals("not_dispatched",receipt.getString("status"));assertFalse(receipt.getBoolean("accepted"));journal.finishOperation(operation,receipt);
        tasks.endAttempt(id,run,"awaiting_user","Exact native owner decision fixture",new JSONObject());tasks.decideCapabilityApproval(id,receipt.getString("approvalId"),true);
        port.close();start(false);return args;
    }
    @Test public void freshTurnExposesReboundApprovedReferenceWithoutChangingOldHistoryOrGrant()throws Exception {
        JSONObject old=approvedInPriorRun();int before=tasks.get(id,false).getJSONArray("operations").length();
        JSONObject turn=port.prepareTurn("Continue original task",0,0,control);
        JSONObject projectView=turn.getJSONObject("reference").getJSONObject("developmentProject");
        JSONArray proposals=projectView.getJSONArray("approvedPlanProposals");assertEquals(1,proposals.length());JSONObject current=proposals.getJSONObject(0);
        assertNotEquals(old.getString("planRef"),current.getString("planRef"));assertEquals(old.getString("planDigest"),current.getString("planDigest"));
        assertEquals(run,projectView.getString("runId"));assertEquals(epoch,projectView.getInt("coordinatorEpoch"));assertFalse(current.getBoolean("executionAuthorized"));
        assertTrue(turn.getJSONObject("reference").getString("context").contains(current.getString("planRef")));
        assertThrows(SecurityException.class,()->port.validate("project_execute",old));
        JSONObject args=new JSONObject().put("planRef",current.getString("planRef")).put("planDigest",current.getString("planDigest")).put("stepId",current.getString("stepId"));
        port.validate("project_execute",args);assertEquals(before,tasks.get(id,false).getJSONArray("operations").length());assertFalse(tasks.hasUnresolved(id));
    }
    @Test public void currentProposalDoesNotAuthorizeChangedSourceAndCancelledControlCannotReadContext()throws Exception {
        JSONObject old=approvedInPriorRun();JSONObject proposal=tasks.approvedProjectPlanProposals(id,run,epoch,control).getJSONObject(0);
        local.handle("writeFile",new JSONObject().put("checkoutId",checkout).put("path","owner-later.txt").put("content","owner change").put("expectedHash","absent"));
        JSONObject args=new JSONObject().put("planRef",proposal.getString("planRef")).put("planDigest",proposal.getString("planDigest")).put("stepId","edit");
        assertThrows(SecurityException.class,()->port.validate("project_execute",args));assertThrows(SecurityException.class,()->port.validate("project_execute",old));
        control.cancel();assertThrows(Exception.class,()->port.contextSnapshot("Current project"));
    }
    @After public void close()throws Exception {
        try{if(port!=null)port.close();if(local!=null)local.close();}finally{if(tasks!=null){var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);((SQLiteDatabase)f.get(tasks)).close();}}
    }
}
