package dev.pocketengine.features.local.project;

import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
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

/** Actual TaskStore/ProjectDevelopment/Termux bridge. Android transport is a declared fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TaskStoreProjectHostTest {
    private Context android;private LocalModule local;private WorkspaceModule workspace;private TaskStore tasks;private RunControl original;private RunJournal journal;
    private String taskId,runId,projectId,checkout;private int epoch;private Path termuxHome;
    private final ArrayList<JSONObject> approvals=new ArrayList<>();private final AtomicReference<RunControl> current=new AtomicReference<>();private TaskStoreProjectHost host;
    @Before public void start()throws Exception {
        android=RuntimeEnvironment.getApplication();PackageInfo pkg=new PackageInfo();pkg.packageName="com.termux";pkg.versionName="isolated-transport-fixture";shadowOf(android.getPackageManager()).installPackage(pkg);shadowOf((Application)android).grantPermissions(TermuxBridge.PERMISSION);android.getSharedPreferences("local_development",0).edit().putBoolean("termuxEnabled",true).commit();
        FeatureContext context=new FeatureContext(android,null,null,event->{});workspace=new WorkspaceModule(context);local=new LocalModule(context,workspace);termuxHome=Files.createTempDirectory("a26d-termux-namespace-");
        projectId=workspace.handle("create",new JSONObject().put("name","Actual Java build fixture")).getString("id");
        workspace.handle("write",new JSONObject().put("projectId",projectId).put("path","Main.java").put("expectedVersion",0).put("content","public class Main { public static void main(String[] args) { System.out.println(\"ACTUAL_A26D_COMPILED_OUTPUT\"); } }\n"));
        checkout=local.handle("prepare",new JSONObject().put("projectId",projectId)).getString("id");tasks=new TaskStore(android);taskId=tasks.createCapabilityTask("Build and run this exact project in the original task","").getString("id");JSONObject attempt=tasks.startAttempt(taskId,"capabilities",30);runId=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");original=new RunControl();original.bindHostRun(taskId,runId,epoch);current.set(original);tasks.authorizeTools(taskId,runId,new JSONArray().put(TaskStoreProjectHost.TOOL));journal=tasks.journal(taskId,runId,epoch,original);
        host=new TaskStoreProjectHost(tasks,local,id->taskId.equals(id)?current.get():null,approvals::add);local.setProjectHost(host);
    }
    @After public void stop()throws Exception {try{if(local!=null)local.close();if(tasks!=null){java.lang.reflect.Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((android.database.sqlite.SQLiteDatabase)field.get(tasks)).close();}}finally{if(termuxHome!=null)try(var paths=Files.walk(termuxHome)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}}
    private JSONObject plan(String intent)throws Exception {String digest=local.handle("project.inspect",new JSONObject().put("checkoutId",checkout)).getString("sourceDigest");return local.handle("project.plan",new JSONObject().put("checkoutId",checkout).put("expectedSourceDigest",digest).put("intent",intent).put("ecosystem","java"));}
    private JSONObject binding(String operation)throws Exception{return new JSONObject().put("taskId",taskId).put("runId",runId).put("coordinatorEpoch",epoch).put("operationId",operation);}
    private JSONObject input(JSONObject plan,String step,String operation)throws Exception{return new JSONObject().put("plan",plan).put("stepId",step).put("binding",binding(operation));}
    private JSONObject row(String id)throws Exception {JSONArray rows=tasks.get(taskId,false).getJSONArray("operations");for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).getString("operationId")))return rows.getJSONObject(i);throw new AssertionError("Original operation missing");}
    private JSONObject beginApproved(JSONObject plan,String step)throws Exception {
        JSONObject args=new JSONObject().put("plan",plan).put("stepId",step);String pending=journal.beginOperation(TaskStoreProjectHost.TOOL,args);JSONObject denied=local.handle("project.execute",input(plan,step,pending));assertEquals("approval_required",denied.getString("code"));assertFalse(denied.getBoolean("accepted"));assertNull(shadowOf((Application)android).getNextStartedService());journal.finishOperation(pending,denied);tasks.decideCapabilityApproval(taskId,denied.getString("approvalId"),true);
        String operation=journal.beginOperation(TaskStoreProjectHost.TOOL,args);JSONObject result=local.handle("project.execute",input(plan,step,operation));journal.finishOperation(operation,result);assertEquals("unknown",row(operation).getString("state"));assertTrue(tasks.hasUnresolved(taskId));assertEquals(runId,result.getJSONObject("binding").getString("runId"));assertEquals(operation,result.getString("operationId"));
        return new JSONObject().put("operationId",operation).put("result",result).put("input",input(plan,step,operation));
    }
    private JSONObject executeCaptured(Intent actual)throws Exception {
        assertNotNull(actual);assertEquals("com.termux.RUN_COMMAND",actual.getAction());assertEquals("com.termux",actual.getComponent().getPackageName());assertTrue(actual.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND",false));
        String home="/data/data/com.termux/files/home",prefix="/data/data/com.termux/files/usr/bin/",jdk="/usr/lib/jvm/java-17-openjdk-amd64/bin/";
        String stdin=actual.getStringExtra("com.termux.RUN_COMMAND_STDIN");String executable=actual.getStringExtra("com.termux.RUN_COMMAND_PATH");String[] args=actual.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS");
        // Explicit fixture namespace translation. The generated source guard and compiler
        // argv execute unchanged in the desktop process; this is NOT Android Termux proof.
        String mapped=executable.equals(prefix+"sh")?"/bin/sh":executable.replace(prefix,jdk);ArrayList<String> command=new ArrayList<>();command.add(mapped);if(args!=null)java.util.Collections.addAll(command,args);
        Process process=new ProcessBuilder(command).directory(Path.of(actual.getStringExtra("com.termux.RUN_COMMAND_WORKDIR").replace(home,termuxHome.toString())).toFile()).start();
        try(var writer=process.getOutputStream()){if(stdin!=null)writer.write(stdin.replace(home,termuxHome.toString()).replace(prefix,jdk).getBytes(StandardCharsets.UTF_8));}
        String stdout=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8),stderr=new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
        PendingIntent pending=actual.getParcelableExtra("com.termux.RUN_COMMAND_PENDING_INTENT");Intent callback=new Intent(shadowOf(pending).getSavedIntent());Bundle output=new Bundle();output.putInt("err",-1);output.putInt("exitCode",process.exitValue());output.putString("stdout",stdout);output.putString("stderr",stderr);callback.putExtra("result",output);JSONObject decoded=TermuxBridge.receive(android,callback);assertNotNull(decoded);return decoded;
    }
    @Test public void originalTaskActuallyStagesCompilesRunsAndKeepsPendingUntilReceipt()throws Exception {
        JSONObject prepare=plan("prepare"),staged=beginApproved(prepare,"prepare");String stageId=staged.getString("operationId");
        Intent stage=shadowOf((Application)android).getNextStartedService();assertNotNull(stage);local.handle("project.execute",staged.getJSONObject("input"));assertNull(shadowOf((Application)android).getNextStartedService());assertEquals("completed",executeCaptured(stage).getString("status"));
        assertEquals("completed",local.handle("project.poll",staged.getJSONObject("input")).getString("status"));assertFalse(tasks.hasUnresolved(taskId));assertEquals("receipt",row(stageId).getString("state"));
        JSONObject build=plan("build"),compiled=beginApproved(build,"build");JSONObject compiler=executeCaptured(shadowOf((Application)android).getNextStartedService());assertEquals(compiler.optString("stderr"),0,compiler.getInt("exitCode"));JSONObject built=local.handle("project.poll",compiled.getJSONObject("input"));assertTrue(built.getBoolean("sourceVerifiedBeforeExec"));assertFalse(tasks.hasUnresolved(taskId));
        String digest=build.getString("sourceDigest");assertTrue(Files.isRegularFile(termuxHome.resolve("pocketworld/"+checkout+"/revisions/"+digest+"/build/classes/Main.class")));
        JSONObject run=plan("run"),running=beginApproved(run,"run");assertEquals(0,executeCaptured(shadowOf((Application)android).getNextStartedService()).getInt("exitCode"));JSONObject done=local.handle("project.poll",running.getJSONObject("input"));assertEquals("ACTUAL_A26D_COMPILED_OUTPUT\n",done.getString("stdout"));assertTrue(done.getBoolean("sourceVerifiedBeforeExec"));assertEquals(taskId,done.getJSONObject("binding").getString("taskId"));assertEquals(runId,done.getJSONObject("binding").getString("runId"));assertFalse(tasks.hasUnresolved(taskId));assertEquals("preparing",tasks.get(taskId,false).getString("status"));
        System.out.println("PROJECT_HOST actualDesktopJavac=true; actualCompiledProgramRan=true; sameTaskAndRun=true; pendingUntilCallback=true; AndroidTermuxDeviceVerified=false");
    }
    @Test public void revokedToolNeverStartsAService()throws Exception {
        JSONObject plan=plan("prepare");String operation=journal.beginOperation(TaskStoreProjectHost.TOOL,new JSONObject().put("plan",plan).put("stepId","prepare"));tasks.authorizeTools(taskId,runId,new JSONArray());assertThrows(SecurityException.class,()->local.handle("project.execute",input(plan,"prepare",operation)));assertNull(shadowOf((Application)android).getNextStartedService());
    }
    @Test public void aRealCallbackBeforeJournalDeliveryCannotBeDowngradedOrLoseItsRoute()throws Exception {
        JSONObject plan=plan("prepare"),started=beginApproved(plan,"prepare");String operation=started.getString("operationId");JSONObject actual=executeCaptured(shadowOf((Application)android).getNextStartedService());host.recordBackendReceipt(actual);
        assertEquals("receipt",row(operation).getString("state"));journal.finishOperation(operation,started.getJSONObject("result"));JSONObject retained=row(operation);assertEquals("receipt",retained.getString("state"));assertEquals("completed",retained.getJSONObject("receipt").getString("executionStatus"));assertEquals(operation,retained.getJSONObject("receipt").getJSONObject("projectExecution").getString("operationId"));assertFalse(tasks.hasUnresolved(taskId));
    }
    @Test public void modelPortReadsEditsAndReviewsTheBoundRealProjectThroughOriginalApproval()throws Exception {
        ModelCapabilityAdapters.Binding binding=new ModelCapabilityAdapters.Binding(taskId,runId,epoch,original,true,projectId);
        ProjectCapabilityPort port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,approvals::add),original,null,local,tasks,checkout);JSONArray names=new JSONArray(),tools=port.tools();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(taskId,runId,names);
        assertFalse("The matching F1 Termux runtime declares no COLLECT support",port.supports("project_collect_plan"));assertFalse(port.supports("project_generated_artifact"));
        JSONObject read=port.execute("project_read_file",new JSONObject().put("path","Main.java"),original),inspection=port.execute("project_inspect",new JSONObject(),original);
        String revised="public class Main { public static void main(String[] args) { System.out.println(\"REVISED_BY_ORIGINAL_TASK\"); } }\n";
        JSONObject proposed=port.execute("project_plan",new JSONObject().put("intent","edit").put("expectedSourceDigest",inspection.getString("sourceDigest")).put("path","Main.java").put("content",revised).put("expectedHash",read.getString("sha256")),original).getJSONObject("plan");
        JSONObject args=new JSONObject().put("plan",proposed).put("stepId","edit");String first=journal.beginOperation(TaskStoreProjectHost.TOOL,args);JSONObject pending=port.execute(TaskStoreProjectHost.TOOL,new JSONObject(args.toString()).put("_hostOperationId",first),original);assertEquals("approval_required",pending.getString("code"));assertEquals(read.getString("sha256"),local.handle("readFile",new JSONObject().put("checkoutId",checkout).put("path","Main.java")).getString("sha256"));journal.finishOperation(first,pending);tasks.decideCapabilityApproval(taskId,pending.getString("approvalId"),true);
        String operation=journal.beginOperation(TaskStoreProjectHost.TOOL,args);JSONObject edited=port.execute(TaskStoreProjectHost.TOOL,new JSONObject(args.toString()).put("_hostOperationId",operation),original);assertEquals("completed",edited.getString("status"));journal.finishOperation(operation,edited);assertFalse(tasks.hasUnresolved(taskId));
        assertEquals(revised,port.execute("project_read_file",new JSONObject().put("path","Main.java"),original).getString("content"));assertEquals(1,port.execute("project_changes",new JSONObject(),original).getInt("changedCount"));assertTrue(port.execute("project_review_file",new JSONObject().put("path","Main.java"),original).getString("diff").contains("REVISED_BY_ORIGINAL_TASK"));assertFalse(workspace.handle("read",new JSONObject().put("projectId",projectId).put("path","Main.java")).getString("content").contains("REVISED_BY_ORIGINAL_TASK"));
        RunControl impostor=new RunControl();impostor.bindHostRun(taskId,runId,epoch);assertThrows(SecurityException.class,()->port.execute("project_inspect",new JSONObject(),impostor));assertThrows(IllegalArgumentException.class,()->port.validate("project_plan",new JSONObject().put("taskId",taskId)));
        assertNull(shadowOf((Application)android).getNextStartedService());assertEquals("preparing",tasks.get(taskId,false).getString("status"));port.close();
    }
}
