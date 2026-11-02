package dev.pocketengine.features.teams;

import static org.junit.Assert.*;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.project.*;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.local.runtime.RuntimeTool;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.testutil.HttpServer;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Depends on genuinely completed cold-recovery files, not a fabricated package/round grant. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=TeamBudgetPersistentApplication.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class TeamScopedRoundContinuationNativeTest {
    private static SQLiteDatabase sql(TaskStore tasks)throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    private static JSONObject grant(TaskStore tasks,String run)throws Exception{try(Cursor rows=sql(tasks).rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(rows.moveToFirst());return new JSONObject(rows.getString(0));}}
    private static String fingerprint(Object value)throws Exception{return TeamWorkFiles.hash(TeamWorkFiles.canonical(value));}
    private static JSONObject text(String value)throws Exception{return new JSONObject().put("role","assistant").put("content",value);}
    private static JSONObject tool(String name,JSONObject args)throws Exception{return new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id",UUID.randomUUID().toString()).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));}
    private static RuntimeTool nativeTool(String name,String location,List<String> probe)throws Exception {
        File file=new File(location).getCanonicalFile();MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var in=new BufferedInputStream(new FileInputStream(file))){byte[] buffer=new byte[32768];for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}
        StringBuilder hash=new StringBuilder();for(byte value:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",value&255));return new RuntimeTool(name,file,hash.toString(),"explicit-native-team-fixture","host-tools-not-redistributed",List.of(),probe);
    }
    private static boolean currentRead(JSONObject task,String name)throws Exception{JSONArray ops=task.getJSONArray("operations");for(int i=0;i<ops.length();i++){JSONObject op=ops.getJSONObject(i);if(task.getString("runId").equals(op.optString("runId"))&&name.equals(op.optString("tool"))&&"receipt".equals(op.optString("state")))return true;}return false;}

    private static void initialCopyToolDeclarationDenials(TaskStore tasks,String task,String run,JSONArray tools)throws Exception {
        SQLiteDatabase db=sql(tasks);for(String negative:new String[]{"unknown_copy","another_operation","wrong_checkout","forged_review","old_scope_revision"})synchronized(tasks){db.execSQL("SAVEPOINT c6_first_tools_negative");try{JSONObject changed=tasks.get(task,false);
            if(negative.equals("unknown_copy")){ContentValues row=new ContentValues();row.put("state","unknown");db.update("operations",row,"task_id=?",new String[]{task});}
            else if(negative.equals("another_operation")){ContentValues row=new ContentValues();row.put("operation_id",UUID.randomUUID().toString());row.put("task_id",task);row.put("run_id",run);row.put("tool","project_execute");row.put("arguments","{}");row.put("state","unknown");row.put("receipt","{}");row.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,row);}
            else if(negative.equals("old_scope_revision")){ContentValues row=new ContentValues();row.put("grant_json",grant(tasks,run).put("executionScopeRevision",0).toString());db.update("runs",row,"run_id=?",new String[]{run});}
            else {if(negative.equals("wrong_checkout"))changed.getJSONObject("developmentContext").put("checkoutId",UUID.randomUUID().toString());else changed.getJSONObject("teamProjectRoundOrigin").put("nativeReviewDigest","0".repeat(64));ContentValues row=new ContentValues();row.put("document",changed.toString());db.update("tasks",row,"task_id=?",new String[]{task});}
            assertThrows(negative,SecurityException.class,()->tasks.authorizeTools(task,run,tools));
        }finally{db.execSQL("ROLLBACK TO SAVEPOINT c6_first_tools_negative");db.execSQL("RELEASE SAVEPOINT c6_first_tools_negative");}}
    }
    private static void coldRoundSourceIdentityDenials(TaskStore tasks,TeamProjectRoundHost rounds,TeamCoordinator coordinator,String team)throws Exception {
        SQLiteDatabase db=sql(tasks);for(String negative:new String[]{"identity_revision","dependency_changed","foreign_child"})synchronized(tasks){db.execSQL("SAVEPOINT c6_cold_source_negative");try{JSONObject changed=tasks.get(team,false),item=changed.getJSONObject("teamWorkPlan").getJSONArray("items").getJSONObject(0);String id=team;
            if(negative.equals("identity_revision"))item.put("ownerGeneration",item.getInt("ownerGeneration")+1);
            else if(negative.equals("dependency_changed"))item.put("dependsOn",new JSONArray().put(item.getString("workItemId")));
            else {id=item.getString("assignmentId");changed=tasks.get(id,false);changed.getJSONObject("origin").put("teamRunId",UUID.randomUUID().toString());}
            ContentValues row=new ContentValues();row.put("document",changed.toString());db.update("tasks",row,"task_id=?",new String[]{id});try(TeamProjectRoundHost.Page page=rounds.open(coordinator,team)){page.setForeground(true);assertThrows(negative,SecurityException.class,()->page.review("不得用派生显示投影掩盖实际分工/依赖变化",12,12));}
        }finally{db.execSQL("ROLLBACK TO SAVEPOINT c6_cold_source_negative");db.execSQL("RELEASE SAVEPOINT c6_cold_source_negative");}}
    }
    @Test public void nativeScopedParentCrossRunReadsOnceAndRejectsForgedLineageOldScopeAndExhaustedTotal()throws Exception {
        Path root=TeamBudgetPersistentApplication.ownedRoot();assertTrue(new JSONObject(Files.readString(root.resolve("current-cold-recovery-result.json"))).getBoolean("completed"));JSONObject locator=new JSONObject(Files.readString(root.resolve("native-seed-locators.json")));
        TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());Map<String,RuntimeTool> tools=new LinkedHashMap<>();tools.put("node",nativeTool("node",System.getProperty("a25.node"),List.of("--version")));tools.put("python3",nativeTool("python3",System.getProperty("a25.python"),List.of("--version")));tools.put("sh",nativeTool("sh","/bin/sh",List.of("-c","printf team-native-ready")));
        var pack=new dev.pocketengine.features.local.runtime.RuntimeEnvironment.Package(locator.getJSONObject("runtimePackage"));ManagedProjectRuntime runtime=ManagedProjectRuntime.create(Path.of(locator.getString("runtimeAndCoordinatorDirectory")).resolve("runtime").toFile(),pack,tools);
        SettingsStore settings=new SettingsStore(RuntimeEnvironment.getApplication());FeatureContext context=new FeatureContext(RuntimeEnvironment.getApplication(),settings,new SecretVault(RuntimeEnvironment.getApplication()),event->{});WorkspaceModule workspace=new WorkspaceModule(context);LocalModule local=new LocalModule(context,workspace,runtime);TeamWorkFiles.Workspace files=new TeamWorkspaceAccess(workspace);TaskStoreTeamWriteHost host=new TaskStoreTeamWriteHost(tasks,files,changed->{});host.setProjectProduction(new TeamProjectWorkspaces(tasks,local,files,changed->{}));
        AtomicReference<Throwable> failure=new AtomicReference<>();ConcurrentMap<String,RunControl> controls=new ConcurrentHashMap<>();AtomicReference<String> taskId=new AtomicReference<>();AtomicInteger physical=new AtomicInteger(),originalReadDispatches=new AtomicInteger();AtomicBoolean resumed=new AtomicBoolean();
        TaskStoreProjectHost nativeProjects=new TaskStoreProjectHost(tasks,local,id->controls.containsKey(id)?controls.get(id):host.currentControl(id),event->{});local.setProjectHost(nativeProjects);local.setProjectReceiptSink(receipt->{try{nativeProjects.recordBackendReceipt(receipt);}catch(Throwable problem){failure.compareAndSet(null,problem);}});
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));physical.incrementAndGet();JSONObject current=tasks.get(taskId.get(),false),answer;String read=resumed.get()?"project_read_file":"project_inspect";
            if(!currentRead(current,read))answer=tool(read,resumed.get()?new JSONObject().put("path","a.js"):new JSONObject());else answer=text("已按本次原生只读授权读取新独立工作副本；没有编辑、命令或旧grant复活，等待主人核对。");
            byte[] body=new JSONObject().put("model",request.getString("model")).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",answer).put("finish_reason",answer.has("tool_calls")?"tool_calls":"stop"))).put("usage",new JSONObject().put("prompt_tokens",12).put("completion_tokens",5).put("total_tokens",17)).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }catch(Throwable problem){failure.compareAndSet(null,problem);try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
        ModelConfig selected=settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("apiKey","").put("clearApiKey",true).put("model","scoped_round_parent").put("sendScreenshots",false).put("maxSteps",12).put("requestTimeoutSeconds",60).put("provider","openai_chat").put("maxOutputTokens",512));
        TeamCoordinator coordinator=new TeamCoordinator(Path.of(locator.getString("runtimeAndCoordinatorDirectory")).resolve("runs.json").toFile(),new TeamCoordinator.ToolGateway(){public JSONObject complete(ModelConfig c,JSONArray h,JSONArray offered,RunControl live){throw new AssertionError("no old discussion replay");}},event->{try{if(event.optJSONObject("run")!=null)tasks.updateExternal(locator.getString("teamRunId"),event.getJSONObject("run"));}catch(Throwable problem){failure.compareAndSet(null,problem);}});coordinator.setAssignmentFactory(host);coordinator.setBudgetHost(host);
        ExecutorService continuations=Executors.newSingleThreadExecutor();ConcurrentMap<String,RunOutcome> outcomes=new ConcurrentHashMap<>();
        AssistantModule assistant=new AssistantModule(context,null,(conversation,goal,history,config,live)->{
            try {
            String task=taskId.get();JSONObject actual=tasks.requireCapabilityAttempt(task,live),development=actual.getJSONObject("developmentContext");String run=actual.getString("runId");int epoch=actual.getInt("coordinatorEpoch");ModelCapabilityAdapters.Binding binding=new ModelCapabilityAdapters.Binding(task,run,epoch,live,true,development.getString("projectId"));ProjectCapabilityPort project=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),live,null,local,tasks,development.getString("checkoutId"),development.getString("runtime"));
            CapabilityPort observed=new CapabilityPort(){public JSONArray tools()throws Exception{return project.tools();}public boolean supports(String name){return project.supports(name);}public boolean sideEffecting(String name){return project.sideEffecting(name);}public void validate(String name,JSONObject args)throws Exception{project.validate(name,args);}public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{originalReadDispatches.incrementAndGet();return project.execute(name,args,original);}public JSONObject beforeTool(String name,JSONObject args,RunControl original)throws Exception{return project.beforeTool(name,args,original);}public void afterTool(String name,JSONObject args,JSONObject result,RunControl original)throws Exception{project.afterTool(name,args,result,original);}public void close(){project.close();}};
            CapabilityPort metered=TeamBudgetPort.forRoundParent(observed,tasks,task,run,epoch,live);JSONArray offered=metered.tools(),names=new JSONArray();for(int i=0;i<offered.length();i++)names.put(offered.getJSONObject(i).getJSONObject("function").getString("name"));initialCopyToolDeclarationDenials(tasks,task,run,names);tasks.authorizeTools(task,run,names);assertTrue(tasks.get(task,false).getJSONObject("executionScope").getBoolean("toolsPinned"));RunJournal journal=tasks.journal(task,run,epoch,live); // original Runner atomically adopts the native goal/mailbox; no fixture checkpoint bypass
            try{RunOutcome outcome=new AgentRunner(null,metered).run(task,goal,config.withStepCeiling(actual.getInt("stepCeiling")),live,event->{},journal);tasks.endAttempt(task,run,outcome.status,outcome.summary,outcome.evidence);outcomes.put(run,outcome);return new JSONObject().put("content",outcome.summary).put("taskId",task).put("status",outcome.status);}finally{controls.remove(task,live);}
            }catch(Exception|Error originalFailure){failure.compareAndSet(null,originalFailure);throw originalFailure;}
        },tasks);
        ProjectOwnerWorkflows owner=new ProjectOwnerWorkflows(local,tasks,assistant);
        assistant.setNativeInputHost(new AssistantModule.NativeInputHost(){public JSONObject start(String conversation,String goal,JSONObject message,ModelConfig config,RunControl live)throws Exception {
            try {
            String id=tasks.createCapabilityTask(goal,conversation).getString("id");tasks.initializeExecutionScope(id,3); // explicit total3: native copy + one read in each run, stricter than pool12
            JSONObject actual=tasks.startAttempt(id,"capabilities",config.maxSteps);String run=actual.getString("runId");int epoch=actual.getInt("coordinatorEpoch");live.bindHostRun(id,run,epoch);taskId.set(id);assertNull(controls.putIfAbsent(id,live));owner.bindNewAttempt(actual,live,config);tasks.openOwnerInputMailbox(id,run,epoch,conversation,live);tasks.appendOwnerGoalInput(id,run,epoch,conversation,message,"queue",live);return new JSONObject().put("taskId",id).put("runId",run).put("epoch",epoch);
            }catch(Exception|Error originalFailure){failure.compareAndSet(null,originalFailure);throw originalFailure;}
        }public JSONObject append(String conversation,JSONObject message,String mode,JSONObject expected,RunControl live){throw new AssertionError("no generic owner resume or unreviewed append");}});
        owner.setRoundContinuationExecutor(original->{String task=original.taskId();RunControl live=new RunControl();assertNull(controls.putIfAbsent(task,live));JSONObject admitted;try{admitted=original.startOriginalAttempt(live);}catch(Exception rejected){controls.remove(task,live);throw rejected;}continuations.execute(()->{try{RunOutcome outcome=runContinuation(tasks,local,task,original.model(),live,originalReadDispatches);outcomes.put(admitted.getString("runId"),outcome);}catch(Throwable problem){failure.compareAndSet(null,problem);}finally{controls.remove(task,live);}});return admitted;});
        TeamProjectRoundHost rounds=new TeamProjectRoundHost(tasks,local,files,owner,settings::get,changed->{});ContextBudgetPolicy.installHostSource(c->ContextBudgetPolicy.RouteBudget.forRoute(c,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis()));
        try {
            coldRoundSourceIdentityDenials(tasks,rounds,coordinator,locator.getString("teamRunId"));
            try(TeamProjectRoundHost.Page page=rounds.open(coordinator,locator.getString("teamRunId"))){page.setForeground(true);TeamProjectRoundHost.Review review=page.review("从准确V2成果新建独立副本，只读核对a.js，不执行项目代码",12,12);page.start(review,review.view().getString("digest"));}
            JSONObject first=settled(tasks,taskId,controls,failure);assertEquals("needs_confirmation",first.getString("status"));assertTrue(first.has("executionScope"));String task=taskId.get(),anchor=first.getJSONObject("teamReservationPool").getString("parentRunId");int anchorEpoch=first.getInt("coordinatorEpoch");JSONObject originalPool=new JSONObject(first.getJSONObject("teamReservationPool").toString()),scope=first.getJSONObject("executionScope"),permission=grant(tasks,anchor);long oldRevision=scope.getLong("revision");
            // The original native input host explicitly chose a stricter task total3,
            // independently of the reviewed round pool12. No scope revision is rewritten.
            int toolsUsed=scope.getInt("toolsUsed");assertEquals("native copy plus first actual project read are both counted",2,toolsUsed);assertEquals(3,scope.getInt("toolLimit"));assertEquals(toolsUsed,TeamParentBudget.snapshot(originalPool).getInt("toolOperationsReservedOrUsed"));assertTrue(permission.getInt("stepCeiling")<=3);int modelsBefore=scope.getInt("modelCallsUsed"),httpBefore=physical.get(),readBefore=originalReadDispatches.get();
            resumed.set(true);try(TeamProjectRoundHost.ContinuationPage page=rounds.openContinuation(task)){page.setForeground(true);var exact=page.review("只在原余额内精确读取a.js，保留所有旧调用/工具账");page.start(exact,exact.view().getString("digest"));}
            JSONObject second=settled(tasks,taskId,controls,failure);assertTrue("one actual scoped read then a safe stop, not an unearned final business completion",second.getString("status").matches("needs_confirmation|awaiting_user"));assertNotEquals(anchor,second.getString("runId"));assertTrue(tasks.teamParentExecutionCurrent(task,anchor,anchorEpoch));assertEquals(readBefore+1,originalReadDispatches.get());JSONObject after=second.getJSONObject("executionScope"),pool=second.getJSONObject("teamReservationPool");assertEquals(toolsUsed+1,after.getInt("toolsUsed"));assertEquals(after.getInt("toolLimit"),after.getInt("toolsUsed"));assertEquals(modelsBefore+physical.get()-httpBefore,after.getInt("modelCallsUsed"));assertEquals(anchor,pool.getString("parentRunId"));
            JSONArray oldRows=originalPool.getJSONArray("reservations"),newRows=pool.getJSONArray("reservations");for(int i=0;i<oldRows.length();i++)assertEquals(TeamWorkFiles.canonical(oldRows.getJSONObject(i)),TeamWorkFiles.canonical(newRows.getJSONObject(i)));
            String beforeNegative=fingerprint(pool),currentRun=second.getString("runId");int currentEpoch=second.getInt("coordinatorEpoch");
            JSONObject development=second.getJSONObject("developmentContext");LocalModule.TeamProjectSource fixed=local.captureTeamProjectSourceForHost(development.getString("checkoutId"),ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",development.getString("checkoutId")))).getString("sourceDigest"));TaskStore.TeamRoundContinuationWindow exactWindow=tasks.openTeamRoundContinuationWindow(task);exactWindow.setForeground(true);
            // Explicit corrupt-data negatives in an isolated SQL savepoint. They never issue
            // a grant or execute a tool, and every modified byte is rolled back.
            synchronized(tasks){SQLiteDatabase database=sql(tasks);database.execSQL("SAVEPOINT c6_bad_lineage_negative");try{JSONObject bad=tasks.get(task,false);bad.getJSONObject("teamRoundBudgetContinuation").put("reviewDigest","0".repeat(64));ContentValues row=new ContentValues();row.put("document",bad.toString());database.update("tasks",row,"task_id=?",new String[]{task});assertFalse(tasks.teamParentExecutionCurrent(task,anchor,anchorEpoch));}finally{database.execSQL("ROLLBACK TO SAVEPOINT c6_bad_lineage_negative");database.execSQL("RELEASE SAVEPOINT c6_bad_lineage_negative");}
                database.execSQL("SAVEPOINT c6_old_scope_negative");try{JSONObject bad=grant(tasks,currentRun).put("executionScopeRevision",oldRevision-1);ContentValues row=new ContentValues();row.put("grant_json",bad.toString());database.update("runs",row,"run_id=?",new String[]{currentRun});assertThrows(SecurityException.class,()->tasks.prepareTeamRoundContinuation(exactWindow,fixed));}finally{database.execSQL("ROLLBACK TO SAVEPOINT c6_old_scope_negative");database.execSQL("RELEASE SAVEPOINT c6_old_scope_negative");}}
            assertTrue(tasks.teamParentExecutionCurrent(task,anchor,anchorEpoch));assertEquals(0,after.getInt("toolLimit")-after.getInt("toolsUsed"));exactWindow.close();try(TeamProjectRoundHost.ContinuationPage exhausted=rounds.openContinuation(task)){exhausted.setForeground(true);assertThrows(IllegalStateException.class,()->exhausted.review("不得从新run洗掉已用总额"));}assertEquals(currentRun,tasks.get(task,false).getString("runId"));assertEquals(beforeNegative,fingerprint(tasks.get(task,false).getJSONObject("teamReservationPool")));assertNull(failure.get());
            Files.writeString(root.resolve("current-scoped-round-continuation-result.json"),new JSONObject().put("scopeBoundaryProofCompleted",true).put("finalBusinessCompletionProven",false).put("actualScopedParent",true).put("crossRunReadDispatches",1).put("originalAnchorUnchanged",true).put("forgedLineageRejected",true).put("oldScopeRejected",true).put("scopeTotalExhaustedRefusedNewRun",true).put("taskId",task).put("currentRunId",currentRun).put("executionScope",after).toString());
        } finally {
            assistant.close();continuations.shutdownNow();assertTrue(continuations.awaitTermination(10,TimeUnit.SECONDS));var turnField=AssistantModule.class.getDeclaredField("turns");turnField.setAccessible(true);assertTrue(((ExecutorService)turnField.get(assistant)).awaitTermination(10,TimeUnit.SECONDS));coordinator.close();for(String name:new String[]{"coordinator","members"}){var field=TeamCoordinator.class.getDeclaredField(name);field.setAccessible(true);assertTrue(((ExecutorService)field.get(coordinator)).awaitTermination(10,TimeUnit.SECONDS));}host.close();local.close();server.stop(0);ContextBudgetPolicy.installHostSource(c->null);sql(tasks).close();
        }
    }
    private static JSONObject settled(TaskStore tasks,AtomicReference<String> id,ConcurrentMap<String,RunControl> controls,AtomicReference<Throwable> failure)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);JSONObject current=null;do{if(failure.get()!=null)throw new AssertionError(failure.get());if(id.get()!=null){current=tasks.get(id.get(),false);if(!controls.containsKey(id.get())&&current.optString("status").matches("needs_confirmation|awaiting_user|failed"))return current;}Thread.sleep(20);}while(System.nanoTime()<until);throw new AssertionError("original scoped parent did not settle "+(current==null?"not_started":current.optString("status")));}
    private static RunOutcome runContinuation(TaskStore tasks,LocalModule local,String task,ModelConfig config,RunControl live,AtomicInteger reads)throws Exception {
        JSONObject actual=tasks.requireCapabilityAttempt(task,live),development=actual.getJSONObject("developmentContext");String run=actual.getString("runId");int epoch=actual.getInt("coordinatorEpoch");ModelCapabilityAdapters.Binding binding=new ModelCapabilityAdapters.Binding(task,run,epoch,live,true,development.getString("projectId"));ProjectCapabilityPort project=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),live,null,local,tasks,development.getString("checkoutId"),development.getString("runtime"));CapabilityPort counted=new CapabilityPort(){public JSONArray tools()throws Exception{return project.tools();}public boolean supports(String n){return project.supports(n);}public boolean sideEffecting(String n){return project.sideEffecting(n);}public void validate(String n,JSONObject a)throws Exception{project.validate(n,a);}public JSONObject beforeTool(String n,JSONObject a,RunControl c)throws Exception{return project.beforeTool(n,a,c);}public JSONObject execute(String n,JSONObject a,RunControl c)throws Exception{reads.incrementAndGet();return project.execute(n,a,c);}public void afterTool(String n,JSONObject a,JSONObject r,RunControl c)throws Exception{project.afterTool(n,a,r,c);}public void close(){project.close();}};CapabilityPort metered=TeamBudgetPort.forRoundParent(counted,tasks,task,run,epoch,live);JSONArray offered=metered.tools(),names=new JSONArray();for(int i=0;i<offered.length();i++)names.put(offered.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(task,run,names);RunOutcome outcome=new AgentRunner(null,metered).run(task,actual.getString("goal"),config.withStepCeiling(actual.getInt("stepCeiling")),live,event->{},tasks.journal(task,run,epoch,live));tasks.endAttempt(task,run,outcome.status,outcome.summary,outcome.evidence);return outcome;
    }
}
