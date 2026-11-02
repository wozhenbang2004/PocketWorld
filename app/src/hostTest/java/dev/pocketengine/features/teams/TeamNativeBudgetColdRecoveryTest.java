package dev.pocketengine.features.teams;

import static org.junit.Assert.*;
import android.database.Cursor;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.ProjectPackageDelivery;
import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.local.project.TaskStoreProjectHost;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.local.runtime.RuntimeTool;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Second JVM only: real saved SQLite/catalog/checkout state, never rehydrated diagnostic JSON.
 * It proves the previously failed97 pool can finish after explicit native review, not a fresh
 * larger-budget replay of commands. The seed and this current-code result remain separate. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=TeamBudgetPersistentApplication.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamNativeBudgetColdRecoveryTest {
    private static SQLiteDatabase sql(TaskStore tasks)throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    private static String hash(Object value)throws Exception{return TeamWorkFiles.hash(TeamWorkFiles.canonical(value));}
    private static JSONObject grant(TaskStore tasks,String run)throws Exception{try(Cursor rows=sql(tasks).rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(rows.moveToFirst());return new JSONObject(rows.getString(0));}}
    private static String fileHash(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var in=new BufferedInputStream(new FileInputStream(file))){byte[] bytes=new byte[32768];for(int n;(n=in.read(bytes))!=-1;)digest.update(bytes,0,n);}
        StringBuilder result=new StringBuilder();for(byte value:digest.digest())result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();
    }
    private static RuntimeTool nativeTool(String name,String location,List<String> probe)throws Exception{File file=new File(location).getCanonicalFile();return new RuntimeTool(name,file,fileHash(file),"explicit-native-team-fixture","host-tools-not-redistributed",List.of(),probe);}
    private static JSONObject text(String value)throws Exception{return new JSONObject().put("role","assistant").put("content",value);}
    private static JSONObject packageRead(String operation)throws Exception{return new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id",UUID.randomUUID().toString()).put("type","function").put("function",new JSONObject().put("name","project_package").put("arguments",new JSONObject().put("executionId",operation).toString()))));}
    private static void originalOperationsAreRetained(TaskStore tasks,JSONObject expected)throws Exception {
        Set<String> found=new HashSet<>();JSONArray all=tasks.list();for(int i=0;i<all.length();i++){JSONArray operations=tasks.get(all.getJSONObject(i).getString("id"),false).getJSONArray("operations");for(int n=0;n<operations.length();n++){JSONObject operation=operations.getJSONObject(n);String id=operation.getString("operationId");if(expected.has(id)){assertEquals("an old receipt must never be rewritten",expected.getString(id),hash(operation));found.add(id);}}}
        assertEquals(expected.length(),found.size());
    }
    private static Set<String> originalExecuteOperations(TaskStore tasks)throws Exception {
        Set<String> ids=new HashSet<>();JSONArray all=tasks.list();for(int i=0;i<all.length();i++){JSONArray operations=tasks.get(all.getJSONObject(i).getString("id"),false).getJSONArray("operations");for(int n=0;n<operations.length();n++){JSONObject operation=operations.getJSONObject(n);if("project_execute".equals(operation.optString("tool")))ids.add(operation.getString("operationId"));}}return ids;
    }

    /** Isolated corrupt-data negatives never issue a grant or dispatch; each is fully rolled back. */
    private static void settledAuthorReadDenials(TaskStore tasks,TaskStoreTeamWriteHost.WorkPlanPage page,String team)throws Exception {
        JSONObject saved=tasks.get(team,false),item=saved.getJSONObject("teamWorkPlan").getJSONArray("items").getJSONObject(0);String child=item.getString("assignmentId"),run=tasks.get(child,false).getString("runId");SQLiteDatabase database=sql(tasks);
        for(String negative:new String[]{"wrong_generation","foreign_origin","active_run","unknown_effect"})synchronized(tasks){database.execSQL("SAVEPOINT c6_settled_read_negative");try {
            if(negative.equals("wrong_generation")){JSONObject bad=tasks.get(team,false);bad.getJSONObject("teamWorkPlan").getJSONArray("items").getJSONObject(0).put("ownerGeneration",item.getInt("ownerGeneration")+1);ContentValues row=new ContentValues();row.put("document",bad.toString());database.update("tasks",row,"task_id=?",new String[]{team});}
            else if(negative.equals("foreign_origin")){JSONObject bad=tasks.get(child,false);bad.getJSONObject("origin").put("teamRunId",UUID.randomUUID().toString());ContentValues row=new ContentValues();row.put("document",bad.toString());database.update("tasks",row,"task_id=?",new String[]{child});}
            else if(negative.equals("active_run")){ContentValues row=new ContentValues();row.put("status","running");database.update("runs",row,"run_id=?",new String[]{run});}
            else {ContentValues row=new ContentValues();row.put("operation_id",UUID.randomUUID().toString());row.put("task_id",child);row.put("run_id",run);row.put("tool","team_write_file");row.put("arguments",new JSONObject().put("syntheticNoDispatch",true).toString());row.put("state","unknown");row.put("receipt",new JSONObject().put("status","unknown").put("syntheticNoDispatch",true).toString());row.put("created_at",System.currentTimeMillis());database.insertOrThrow("operations",null,row);}
            assertThrows(negative,SecurityException.class,page::integrationBudgetStatus);
        }finally{database.execSQL("ROLLBACK TO SAVEPOINT c6_settled_read_negative");database.execSQL("RELEASE SAVEPOINT c6_settled_read_negative");}}
        page.integrationBudgetStatus();
    }
    private static void integratorScopeSqlDenials(TaskStore tasks,TaskStoreTeamWriteHost.WorkPlanPage page,TaskStore.TeamIntegratorScopeReview review,String digest,String team,String child)throws Exception {
        SQLiteDatabase database=sql(tasks);for(String negative:new String[]{"old_scope_revision","changed_member","changed_templates","unknown_effect"})synchronized(tasks){database.execSQL("SAVEPOINT c6_integrator_scope_negative");try{
            JSONObject changed=tasks.get(negative.equals("old_scope_revision")?team:child,false);String task=changed.getString("id");
            if(negative.equals("old_scope_revision"))changed.put("teamIntegratorScopeRevision",changed.optLong("teamIntegratorScopeRevision")+1);
            else if(negative.equals("changed_member"))changed.getJSONObject("teamAssignment").put("memberId","not_integrator");
            else if(negative.equals("changed_templates"))changed.getJSONObject("teamProject").getJSONObject("selection").getJSONArray("templates").getJSONObject(0).getJSONObject("command").put("argv",new JSONArray().put("changed-template"));
            else {ContentValues row=new ContentValues();row.put("operation_id",UUID.randomUUID().toString());row.put("task_id",child);row.put("run_id",changed.getString("runId"));row.put("tool","project_execute");row.put("arguments","{}");row.put("state","unknown");row.put("receipt","{}");row.put("created_at",System.currentTimeMillis());database.insertOrThrow("operations",null,row);}
            if(!negative.equals("unknown_effect")){ContentValues row=new ContentValues();row.put("document",changed.toString());database.update("tasks",row,"task_id=?",new String[]{task});}
            assertThrows(negative,SecurityException.class,()->page.approveIntegratorScope(review,digest));
        }finally{database.execSQL("ROLLBACK TO SAVEPOINT c6_integrator_scope_negative");database.execSQL("RELEASE SAVEPOINT c6_integrator_scope_negative");}}
    }
    @Test public void originalFailed97PoolIsNativelyReviewedColdThenOnlyPackageReadAndFinalReplyRun()throws Exception {
        assertFalse("seed-only cleanup hooks must not run in current recovery",Boolean.getBoolean("a25.preserveNativeBudgetSeed"));
        Path root=TeamBudgetPersistentApplication.ownedRoot();JSONObject locator=new JSONObject(Files.readString(root.resolve("native-seed-locators.json")));
        assertTrue(locator.getBoolean("ready"));assertFalse(locator.getBoolean("diagnosticJsonRestoresAuthority"));
        assertTrue(Files.isRegularFile(RuntimeEnvironment.getApplication().getDatabasePath("pocket-world.db").toPath()));
        String team=locator.getString("teamRunId"),parent=locator.getString("parentTaskId"),parentRun=locator.getString("parentRunId"),child=locator.getString("integrationTaskId"),freeze=locator.getString("freezeOperationId");
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());JSONObject beforePool=new JSONObject(tasks.get(parent,false).getJSONObject("teamReservationPool").toString()),beforeBudget=TeamParentBudget.snapshot(beforePool),beforeGrant=grant(tasks,parentRun);
        JSONArray originalPrefix=new JSONArray();for(int i=0;i<beforePool.getJSONArray("reservations").length()-2;i++)originalPrefix.put(beforePool.getJSONArray("reservations").getJSONObject(i));assertEquals("the unchanged original97 prefix stays pinned",locator.getString("reservationFingerprint"),hash(originalPrefix));
        assertEquals(99,beforeBudget.getInt("modelRequestsReservedOrUsed"));assertEquals(105,beforeBudget.getInt("modelRequestLimit"));assertEquals("failed",tasks.get(child,false).getString("status"));
        assertEquals("a real waiting parent survives restart without reviving a Control", "awaiting_user",tasks.get(parent,false).getString("status"));
        assertTrue("use only the already committed explicit bounded scope3",beforeGrant.getLong("expiresAt")>System.currentTimeMillis());
        assertFalse(tasks.hasUnresolved(parent));assertFalse(tasks.hasUnresolved(child));originalOperationsAreRetained(tasks,locator.getJSONObject("originalOperationFingerprints"));Set<String> executions=originalExecuteOperations(tasks);
        // Previous first cold attempt committed the canonical owner scope revision1, then
        // its TEST-ONLY tools-array assertion failed before any HTTP. This is actual saved
        // state, not a repeated seed or a grant reconstructed from locator JSON.
        JSONObject priorScope=tasks.get(parent,false).getJSONObject("executionScope");assertEquals(3,priorScope.getLong("revision"));assertEquals(99,priorScope.getInt("modelCallsUsed"));assertEquals(84,priorScope.getInt("toolsUsed"));

        Map<String,RuntimeTool> tools=new LinkedHashMap<>();tools.put("node",nativeTool("node",System.getProperty("a25.node"),List.of("--version")));tools.put("python3",nativeTool("python3",System.getProperty("a25.python"),List.of("--version")));tools.put("sh",nativeTool("sh","/bin/sh",List.of("-c","printf team-native-ready")));
        var pack=new dev.pocketengine.features.local.runtime.RuntimeEnvironment.Package(locator.getJSONObject("runtimePackage"));
        ManagedProjectRuntime runtime=ManagedProjectRuntime.create(Path.of(locator.getString("runtimeAndCoordinatorDirectory")).resolve("runtime").toFile(),pack,tools);
        FeatureContext feature=new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{});WorkspaceModule workspace=new WorkspaceModule(feature);LocalModule local=new LocalModule(feature,workspace,runtime);TeamWorkFiles.Workspace files=new TeamWorkspaceAccess(workspace);
        TaskStoreTeamWriteHost host=new TaskStoreTeamWriteHost(tasks,files,changed->{});host.setProjectProduction(new TeamProjectWorkspaces(tasks,local,files,changed->{}));
        AtomicReference<Throwable> failure=new AtomicReference<>();TaskStoreProjectHost projectHost=new TaskStoreProjectHost(tasks,local,host::currentControl,event->{});local.setProjectHost(projectHost);local.setProjectReceiptSink(receipt->{try{projectHost.recordBackendReceipt(receipt);}catch(Throwable problem){failure.compareAndSet(null,problem);}});
        JSONObject oldModel=locator.getJSONObject("model");int port=URI.create(oldModel.getString("baseUrl")).getPort();AtomicInteger actualMain=new AtomicInteger(),actualSummary=new AtomicInteger(),responses=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/",exchange->{try {
            JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)),answer;
            JSONArray messages=request.getJSONArray("messages");String system=messages.getJSONObject(0).optString("content");
            if(system.startsWith("整理下面已结算的历史数据供同一个任务继续。")) {
                actualSummary.incrementAndGet();assertTrue(request.optJSONArray("tools")==null||request.getJSONArray("tools").length()==0);
                String provided=messages.getJSONObject(1).getString("content");assertTrue(provided.startsWith("<settled_history_data>\n"));
                answer=text("旧已结算历史只作低信任索引，原完整证据仍可回读；准确本轮CAS、同版测试/构建和原freeze_package已在同任务原SQL保存。只读取原包凭证并完成答复，不重做命令、修改或冻结，不授执行权。");
            } else {
                actualMain.incrementAndGet();JSONObject current=tasks.get(child,false);assertEquals("team_integration",current.getJSONObject("origin").getString("kind"));
                boolean read=false;JSONArray operations=current.getJSONArray("operations");for(int i=0;i<operations.length();i++){JSONObject operation=operations.getJSONObject(i);if(current.getString("runId").equals(operation.optString("runId"))&&"project_package".equals(operation.optString("tool"))&&"receipt".equals(operation.optString("state")))read=true;}
                answer=read?text("原V2准确组合检查、构建与原包保存均有原生凭证；本次仅读回原包并完成交付，未重新执行旧命令或再次冻结，父活跃副本未覆盖。"):packageRead(freeze);
            }
            byte[] body=new JSONObject().put("model",request.getString("model")).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",answer).put("finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).put("usage",new JSONObject().put("prompt_tokens",12).put("completion_tokens",5).put("total_tokens",17)).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);responses.incrementAndGet();
        }catch(Throwable problem){failure.compareAndSet(null,problem);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
        ModelConfig selected=new ModelConfig(oldModel.getString("baseUrl"),"controlled-team-only","author-a",false,40,60,"openai_chat",512);
        TeamCoordinator coordinator=new TeamCoordinator(Path.of(locator.getString("runtimeAndCoordinatorDirectory")).resolve("runs.json").toFile(),new TeamCoordinator.ToolGateway(){
            public JSONObject complete(ModelConfig config,JSONArray history,JSONArray offered,RunControl control){throw new AssertionError("cold budget review must not schedule a discussion");}
        },event->{try{if(event.optJSONObject("run")!=null)tasks.updateExternal(team,event.getJSONObject("run"));}catch(Throwable problem){failure.compareAndSet(null,problem);}});
        coordinator.setAssignmentFactory(host);coordinator.setBudgetHost(host);
        ContextBudgetPolicy.installHostSource(config->ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis()));
        JSONObject completed=null;
        try(TaskStoreTeamWriteHost.WorkPlanPage page=host.newWorkPlanPage(coordinator,team,()->selected);TaskStoreTeamWriteHost.WorkPlanPage foreign=host.newWorkPlanPage(coordinator,team,()->selected)) {
            page.setForeground(true);foreign.setForeground(true);
            // The previous exact method really proved the expired denial and committed the
            // canonical owner renewal before discovering the settled-author read bug. Reuse
            // those original SQL facts; do not replay that owner action or extend time again.
            JSONObject expiredProof=new JSONObject(Files.readString(root.resolve("expired-original-parent-denial.json")));assertTrue(expiredProof.getBoolean("denied"));assertEquals(0,expiredProof.getInt("actualHttpCalls"));assertTrue(expiredProof.getLong("oldExpiresAt")<System.currentTimeMillis());
            // The preceding real attempt committed canonical scope3, then its test
            // incorrectly read the API view as an event. Reuse that exact owner decision.
            JSONObject renewalEvent;boolean originalScope2Expired=false;try(Cursor events=sql(tasks).rawQuery("SELECT event_json FROM task_events WHERE task_id=? AND event_type='owner_execution_scope' ORDER BY event_order DESC",new String[]{parent})){assertTrue(events.moveToFirst());renewalEvent=new JSONObject(events.getString(0));do{JSONObject e=new JSONObject(events.getString(0));if(e.optLong("scopeRevision")==2&&e.getLong("deadlineAt")<System.currentTimeMillis())originalScope2Expired=true;}while(events.moveToNext());}assertTrue("scope2 genuine expiry is not rewritten",originalScope2Expired);assertEquals(3,renewalEvent.getLong("scopeRevision"));
            JSONObject renewedScope=tasks.get(parent,false).getJSONObject("executionScope"),reviewedParentGrant=grant(tasks,parentRun);long ownerReviewedDeadline=renewedScope.getLong("deadlineAt");assertEquals(3,renewedScope.getLong("revision"));assertEquals(ownerReviewedDeadline,reviewedParentGrant.getLong("expiresAt"));assertEquals(99,renewedScope.getInt("modelCallsUsed"));assertEquals(84,renewedScope.getInt("toolsUsed"));assertFalse(renewalEvent.getBoolean("executionStarted"));assertFalse(renewalEvent.getBoolean("effectsApproved"));
            assertFalse("the original grant tools field remains absent, not invented empty",reviewedParentGrant.has("tools"));assertEquals(hash(beforePool),hash(tasks.get(parent,false).getJSONObject("teamReservationPool")));
            assertThrows("live-only execution getter remains denied for the settled parent",SecurityException.class,()->tasks.executionScopeRemaining(parent,parentRun,locator.getInt("parentEpoch")));
            JSONObject current=page.integrationBudgetStatus();assertEquals(99,current.getJSONObject("parentBudget").getInt("modelRequestsReservedOrUsed"));assertTrue(current.getJSONObject("parentExecutionScope").getBoolean("scopePresent"));assertFalse(current.getJSONObject("parentExecutionScope").getBoolean("authorizesExecution"));
            settledAuthorReadDenials(tasks,page,team);
            JSONObject firstItem=tasks.get(team,false).getJSONObject("teamWorkPlan").getJSONArray("items").getJSONObject(0);SecurityException expiredWrite=assertThrows(SecurityException.class,()->page.reviewContinuation(firstItem.getString("workItemId"),firstItem.getInt("itemRevision"),firstItem.getInt("ownerGeneration")));assertTrue("the ORIGINAL author write expiry must remain enforced",expiredWrite.getMessage().contains("旧工作项"));
            //105 was really approved in the preceding cold attempt. Never approve a
            //second increase or rewrite that proof just because the test restarted.
            int explicitNewLimit=105;page.synchronizeIntegrationBudget();assertEquals(explicitNewLimit,coordinator.get(team).getInt("callLimit"));
            //A's real native review/negative cases already passed before epoch13's
            //10-second per-request fixture deadline failed. Reuse the actual saved grant;
            //never re-approve source, deadlines or budget to hide that real FAILED call.
            JSONObject savedScope=tasks.get(team,false).getJSONObject("teamIntegratorProjectScope");assertEquals(1,savedScope.getLong("revision"));assertEquals(3,savedScope.getLong("parentScopeRevision"));assertTrue(savedScope.getLong("deadlineAt")>System.currentTimeMillis());long explicitIntegratorDeadline=savedScope.getLong("deadlineAt");
            assertFalse(tasks.hasUnresolved(child));assertEquals(executions,originalExecuteOperations(tasks));
            assertThrows("author write expiry remains denied",SecurityException.class,()->page.reviewContinuation(firstItem.getString("workItemId"),firstItem.getInt("itemRevision"),firstItem.getInt("ownerGeneration")));
            assertEquals("explicit native current config captures product-default bounded timeout before continuing",60,selected.requestTimeoutSeconds);
            String reviewedConfigDigest=dev.pocketengine.runtime.models.ModelRoutePlan.configurationDigest(selected.withModel("integrator"));
            String reviewId=tasks.get(team,false).getJSONObject("teamOutputReview").getString("reviewId");completed=host.integrate(team,reviewId,selected.withModel("integrator"));coordinator.recordIntegration(team,completed);
            try(Cursor logical=sql(tasks).rawQuery("SELECT route_snapshot FROM inference_requests WHERE task_id=? AND run_id=? ORDER BY created_at_ms DESC LIMIT 1",new String[]{child,tasks.get(child,false).getString("runId")})){assertTrue(logical.moveToFirst());JSONObject route=new JSONObject(logical.getString(0));assertEquals(reviewedConfigDigest,route.getJSONArray("orderedCandidates").getJSONObject(0).getString("configurationDigest"));}
            JSONObject actualChildGrant=grant(tasks,tasks.get(child,false).getString("runId"));assertEquals(explicitIntegratorDeadline,actualChildGrant.getLong("expiresAt"));assertEquals(savedScope.getString("scopeHash"),actualChildGrant.getString("teamIntegratorScopeHash"));
            assertNull(failure.get());assertEquals("completed",completed.getString("status"));assertTrue(completed.getJSONObject("projectDelivery").getBoolean("complete"));
            assertNotEquals(locator.getString("integrationRunId"),tasks.get(child,false).getString("runId"));assertEquals(executions,originalExecuteOperations(tasks));originalOperationsAreRetained(tasks,locator.getJSONObject("originalOperationFingerprints"));
            JSONObject selectedPackage=tasks.teamProjectPackageForOwner(team).view().getJSONObject("evidence"),artifact=selectedPackage.getJSONObject("packageArtifact"),oldReference=locator.getJSONObject("packageReference");
            ProjectPackageDelivery.Reference actual=ProjectPackageDelivery.requireArtifact(selectedPackage,local.handle("project.package.get",new JSONObject().put("artifactId",artifact.getString("id")).put("sha256",artifact.getString("sha256"))));assertEquals(oldReference.getString("artifactId"),actual.artifactId);assertEquals(oldReference.getString("archiveSha256"),actual.archiveSha256);
            JSONObject finalPool=tasks.get(parent,false).getJSONObject("teamReservationPool");JSONArray oldRows=beforePool.getJSONArray("reservations"),newRows=finalPool.getJSONArray("reservations");for(int i=0;i<oldRows.length();i++)assertEquals("old used/reserved/unknown never resets",TeamWorkFiles.canonical(oldRows.getJSONObject(i)),TeamWorkFiles.canonical(newRows.getJSONObject(i)));
            int requests=actualMain.get()+actualSummary.get();assertEquals(requests,responses.get());assertTrue(requests>0);assertEquals(99+requests,TeamParentBudget.snapshot(finalPool).getInt("modelRequestsReservedOrUsed"));JSONObject finalScope=tasks.get(parent,false).getJSONObject("executionScope");assertEquals(99+requests,finalScope.getInt("modelCallsUsed"));assertEquals(TeamParentBudget.snapshot(finalPool).getInt("toolOperationsReservedOrUsed"),finalScope.getInt("toolsUsed"));assertFalse(tasks.hasUnresolved(child));assertFalse(tasks.hasUnresolved(parent));
            JSONObject result=new JSONObject().put("format","pocketworld.synthetic-native-budget-recovery.v1").put("completed",true).put("originalCallsRetained",97).put("retainedAdditionalFailedAttemptCalls",2).put("callsBeforeCurrentNativeContinuation",99).put("fixtureRequestTimeoutSeconds",60).put("additionalMainCalls",actualMain.get()).put("additionalSummaryCalls",actualSummary.get()).put("additionalCommandOrFreezeOperations",0).put("oldPackageUnchanged",true).put("samePool",true).put("actualScopedParentUpgradeRetained97",true).put("expiredOriginalScopeDenied",true).put("separateNativeOwnerScopeRevision",renewedScope.getLong("revision")).put("ownerExplicitNewBoundedDeadline",ownerReviewedDeadline).put("physicalMainAndSummaryDebitedOnce",true).put("budgetApprovalStartedExecution",false).put("newDelivery",completed).put("finalParentBudget",TeamParentBudget.snapshot(finalPool));Files.writeString(root.resolve("current-cold-recovery-result.json"),result.toString());
        } finally {
            coordinator.close();for(String name:new String[]{"coordinator","members"}){var field=TeamCoordinator.class.getDeclaredField(name);field.setAccessible(true);assertTrue(((ExecutorService)field.get(coordinator)).awaitTermination(10,TimeUnit.SECONDS));}
            host.close();local.close();server.stop(0);ContextBudgetPolicy.installHostSource(config->null);sql(tasks).close();
            // Original files are deliberately retained, including on failure. No unknown is replayed.
        }
    }
}
