package dev.pocketengine.features.local.project;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.TermuxBridge;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
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
import static org.robolectric.Shadows.shadowOf;

/** Real TaskStore, native file CAS and model capability/owner approval path; no Termux process. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ProjectPartialReversalHostTest {
    private Context android;private LocalModule local;private WorkspaceModule workspace;private TaskStore tasks;private RunControl original;private RunJournal journal;
    private String taskId,runId,projectId,checkout;private int epoch;
    private final ArrayList<JSONObject> approvals=new ArrayList<>();private final AtomicReference<RunControl> current=new AtomicReference<>();private TaskStoreProjectHost host;
    @Before public void start()throws Exception {
        android=RuntimeEnvironment.getApplication();PackageInfo pkg=new PackageInfo();pkg.packageName="com.termux";pkg.versionName="isolated-transport-fixture";shadowOf(android.getPackageManager()).installPackage(pkg);shadowOf((Application)android).grantPermissions(TermuxBridge.PERMISSION);android.getSharedPreferences("local_development",0).edit().putBoolean("termuxEnabled",true).commit();
        FeatureContext context=new FeatureContext(android,null,null,event->{});workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace);
        projectId=workspace.handle("create",new JSONObject().put("name","Partial reversal fixture")).getString("id");
        workspace.handle("write",new JSONObject().put("projectId",projectId).put("path","Main.java").put("expectedVersion",0).put("content","public class Main { public static void main(String[] args) { System.out.println(\"ACTUAL_A26D_COMPILED_OUTPUT\"); } }\n"));
        checkout=local.handle("prepare",new JSONObject().put("projectId",projectId)).getString("id");tasks=new TaskStore(android);taskId=tasks.createCapabilityTask("Apply and conditionally reverse exact file changes","").getString("id");JSONObject attempt=tasks.startAttempt(taskId,"capabilities",30);runId=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");original=new RunControl();original.bindHostRun(taskId,runId,epoch);current.set(original);tasks.authorizeTools(taskId,runId,new JSONArray().put(TaskStoreProjectHost.TOOL));journal=tasks.journal(taskId,runId,epoch,original);
        tasks.bindDevelopmentContext(taskId,runId,epoch,original,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",projectId).put("checkoutId",checkout).put("runtime","termux").put("initialSourceDigest",local.handle("project.inspect",new JSONObject().put("checkoutId",checkout)).getString("sourceDigest")).put("clientMessageId",java.util.UUID.randomUUID().toString()));
        host=new TaskStoreProjectHost(tasks,local,id->taskId.equals(id)?current.get():null,approvals::add);local.setProjectHost(host);
    }
    @After public void stop()throws Exception {if(local!=null)local.close();if(tasks!=null){var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}}
    private ProjectCapabilityPort port()throws Exception {
        ModelCapabilityAdapters.Binding binding=new ModelCapabilityAdapters.Binding(taskId,runId,epoch,original,true,projectId);
        ProjectCapabilityPort port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,approvals::add),original,null,local,tasks,checkout);
        JSONArray names=new JSONArray(),tools=port.tools();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(taskId,runId,names);return port;
    }
    private Path root()throws Exception {var method=LocalModule.class.getDeclaredMethod("content",String.class);method.setAccessible(true);return ((java.io.File)method.invoke(local,checkout)).toPath();}
    private JSONObject row(String id)throws Exception {JSONArray rows=tasks.get(taskId,false).getJSONArray("operations");for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("operationId")))return rows.getJSONObject(i);throw new AssertionError("missing original operation");}
    private JSONObject propose(ProjectCapabilityPort port,String intent,String operation)throws Exception {
        JSONObject args=new JSONObject().put("intent",intent).put("expectedSourceDigest",port.execute("project_inspect",new JSONObject(),original).getString("sourceDigest"));
        if(operation!=null)args.put("revertOperationId",operation);else {JSONArray edits=new JSONArray();for(String path:new String[]{"a.txt","b.txt","c.txt"})edits.put(new JSONObject().put("path",path).put("expectedHash",ProjectSources.sha256("before".getBytes(StandardCharsets.UTF_8))).put("replacements",new JSONArray().put(new JSONObject().put("oldText","before").put("newText","after"))));args.put("edits",edits);}
        return port.execute("project_plan",args,original);
    }
    private JSONObject args(JSONObject proposal,String step)throws Exception {return new JSONObject().put("planRef",proposal.getString("planRef")).put("planDigest",proposal.getString("planDigest")).put("stepId",step);}
    private JSONObject approved(ProjectCapabilityPort port,JSONObject proposal,String step)throws Exception {
        JSONObject args=args(proposal,step);String pending=journal.beginOperation("project_execute",args);JSONObject denied=port.execute("project_execute",new JSONObject(args.toString()).put("_hostOperationId",pending),original);journal.finishOperation(pending,denied);assertEquals("approval_required",denied.getString("code"));
        // The same native approval carries the immutable subset review, not model supplied files.
        if(step.equals("revert_operation")){JSONObject approval=tasks.get(taskId,false).getJSONArray("approvals").getJSONObject(tasks.get(taskId,false).getJSONArray("approvals").length()-1);JSONObject review=approval.getJSONObject("capability").getJSONObject("nativeProjectPlan").getJSONObject("step").getJSONObject("request");assertTrue(review.has("revertsOperationId"));assertEquals(1,review.getJSONArray("files").length());assertEquals("a.txt",review.getJSONArray("files").getJSONObject(0).getString("path"));assertEquals(2,review.getJSONObject("reversalScope").getJSONArray("excludedFiles").length());}
        tasks.decideCapabilityApproval(taskId,denied.getString("approvalId"),true);
        String operation=journal.beginOperation("project_execute",args);JSONObject result=port.execute("project_execute",new JSONObject(args.toString()).put("_hostOperationId",operation),original);if(!step.equals("revert_operation"))journal.finishOperation(operation,result);return new JSONObject().put("operationId",operation).put("result",result).put("arguments",args);
    }
    private JSONObject partial(ProjectCapabilityPort port)throws Exception {
        Path root=root();for(String path:new String[]{"a.txt","b.txt","c.txt"})Files.writeString(root.resolve(path),"before");JSONObject proposal=propose(port,"patch",null);
        // Inject a real concurrent owner write after A's native commit, before B's CAS.
        java.util.concurrent.atomic.AtomicBoolean changed=new java.util.concurrent.atomic.AtomicBoolean();
        original.setAuthorityCheck(()->{if(Files.readString(root.resolve("a.txt")).equals("after")&&changed.compareAndSet(false,true))Files.writeString(root.resolve("b.txt"),"owner-b");});
        JSONObject executed=approved(port,proposal,"patch");original.setAuthorityCheck(()->{});JSONObject receipt=executed.getJSONObject("result");assertEquals("failed",receipt.getString("status"));assertEquals("applied",receipt.getJSONArray("files").getJSONObject(0).getString("state"));assertEquals("failed",receipt.getJSONArray("files").getJSONObject(1).getString("state"));assertEquals("skipped",receipt.getJSONArray("files").getJSONObject(2).getString("state"));assertEquals("receipt",row(executed.getString("operationId")).getString("state"));return executed;
    }
    @Test public void partialNativePatchReversesOnlyAppliedThroughOriginalApprovalAndJournal()throws Exception {
        {ProjectCapabilityPort port=port();JSONObject first=partial(port),proposal=propose(port,"revert_operation",first.getString("operationId"));assertTrue(proposal.toString(),proposal.has("planRef"));
            JSONObject stored=tasks.projectArguments(taskId,runId,epoch,args(proposal,"revert_operation")).getJSONObject("plan");JSONArray files=ProjectBuildPlan.step(stored,"revert_operation").getJSONObject("payload").getJSONArray("files");assertEquals(1,files.length());assertEquals("a.txt",files.getJSONObject(0).getString("path"));
            JSONObject undone=approved(port,proposal,"revert_operation");assertEquals("completed",undone.getJSONObject("result").getString("status"));assertEquals("before",Files.readString(root().resolve("a.txt")));assertEquals("owner-b",Files.readString(root().resolve("b.txt")));assertEquals("before",Files.readString(root().resolve("c.txt")));assertEquals("receipt",row(undone.getString("operationId")).getString("state"));
            assertEquals(2,undone.getJSONObject("result").getJSONObject("reversalScope").getJSONArray("excludedFiles").length());
            assertEquals(first.getString("operationId"),undone.getJSONObject("result").getString("revertsOperationId"));
            assertEquals("project_plan_rejected",propose(port,"revert_operation",first.getString("operationId")).getString("code"));
            JSONObject liveQuery=port.execute("project_status",new JSONObject().put("executionId",undone.getString("operationId")),original);assertEquals("completed",liveQuery.getString("status"));
            // Simulate loss of model delivery and reopen the real journal before querying the original operation.
            port.close();var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();tasks=new TaskStore(android);journal=tasks.journal(taskId,runId,epoch,original);host=new TaskStoreProjectHost(tasks,local,id->taskId.equals(id)?current.get():null,approvals::add);local.setProjectHost(host);
            JSONObject restoredBinding=new JSONObject().put("taskId",taskId).put("runId",runId).put("coordinatorEpoch",epoch).put("operationId",undone.getString("operationId"));JSONObject replay=local.handle("project.poll",tasks.originalProjectRequestForHost(restoredBinding));assertEquals("completed",replay.getString("status"));assertEquals(2,replay.getJSONObject("reversalScope").getJSONArray("excludedFiles").length());assertEquals("before",Files.readString(root().resolve("a.txt")));
            assertEquals("receipt",row(undone.getString("operationId")).getString("state"));
        }
    }
    @Test public void newerOwnerBytesRejectSubsetBeforeAnyReversal()throws Exception {
        {ProjectCapabilityPort port=port();JSONObject first=partial(port);Files.writeString(root().resolve("a.txt"),"owner-a");JSONObject proposal=propose(port,"revert_operation",first.getString("operationId"));assertEquals("project_plan_rejected",proposal.getString("code"));assertEquals("owner-a",Files.readString(root().resolve("a.txt")));assertEquals("owner-b",Files.readString(root().resolve("b.txt")));}
    }
    @Test public void zeroAppliedHasNoReversePlanOrWrite()throws Exception {
        ProjectCapabilityPort port=port();Path root=root();for(String path:new String[]{"a.txt","b.txt","c.txt"})Files.writeString(root.resolve(path),"before");JSONObject proposal=propose(port,"patch",null);
        java.util.concurrent.atomic.AtomicBoolean changed=new java.util.concurrent.atomic.AtomicBoolean();original.setAuthorityCheck(()->{try(var paths=Files.list(root)){if(paths.anyMatch(p->p.getFileName().toString().startsWith("pending-"))&&changed.compareAndSet(false,true))Files.writeString(root.resolve("a.txt"),"owner-a");}});
        JSONObject first=approved(port,proposal,"patch");original.setAuthorityCheck(()->{});assertEquals(0,first.getJSONObject("result").getInt("appliedFiles"));JSONObject reverse=propose(port,"revert_operation",first.getString("operationId"));assertEquals("project_plan_rejected",reverse.getString("code"));assertTrue(reverse.getString("message").contains("没有确证applied"));assertEquals("owner-a",Files.readString(root.resolve("a.txt")));assertEquals("before",Files.readString(root.resolve("b.txt")));
    }
    @Test public void uncertainOriginalJournalRemainsUnknownAndNeverAuthorizesReversal()throws Exception {
        ProjectCapabilityPort port=port();JSONObject first=partial(port);String id=first.getString("operationId");
        // Crash-recovery fixture: retain original native evidence but mark delivery unresolved in the actual SQL row.
        var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);var db=(android.database.sqlite.SQLiteDatabase)field.get(tasks);android.content.ContentValues values=new android.content.ContentValues();values.put("state","unknown");db.update("operations",values,"task_id=? AND operation_id=?",new String[]{taskId,id});String before=row(id).getJSONObject("receipt").toString();
        JSONObject rejected=propose(port,"revert_operation",id);assertEquals("project_plan_rejected",rejected.getString("code"));assertEquals("unknown",row(id).getString("state"));assertEquals(before,row(id).getJSONObject("receipt").toString());assertEquals("after",Files.readString(root().resolve("a.txt")));assertEquals("owner-b",Files.readString(root().resolve("b.txt")));
    }

}
