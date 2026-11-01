package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.preview.ProjectPreviewBundle;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.workspace.WorkspaceModule;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** New post-recovery proof: actual original Runner/SQLite/JNI/npm/esbuild/catalog. The HTTP
 * model and explicit owner decisions are fixtures, not a real provider or phone ARM/DOM test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectPackageRunnerDeliveryJourneyTest {
    private ProjectManagedRunnerJourneyTest factory;private LocalModule local;private WorkspaceModule workspace;private TaskStore tasks;
    private String taskId,project,checkout;private final List<String> runs=new ArrayList<>();private final List<ProjectCapabilityPort> ports=new ArrayList<>();private Model activeModel;private RunOutcome lastOutcome;
    @Before public void create()throws Exception {
        factory=new ProjectManagedRunnerJourneyTest();factory.create();call("selectedProject",new Class<?>[]{String.class},"新F010正式Web成果续作");
        JSONArray files=ProjectTemplates.files("web");for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);String body=f.getString("content");if(f.getString("path").equals("index.html"))body=body.replace("    <footer>","    <p id=\"amount\">40.00</p>\n    <footer>");call("file",new Class<?>[]{String.class,String.class},f.getString("path"),body);}
        Path cache=Path.of(Objects.requireNonNull(System.getProperty("c2.npmCache"),"actual verified official esbuild cache required")).toRealPath();
        call("file",new Class<?>[]{String.class,String.class},".npmrc","cache="+cache+"\noffline=true\n");
        call("startTask",new Class<?>[]{String.class},"Install actual locked dependencies, test/build, freeze original full project bytes, reopen that result, continue same task from 40.00 to 45.00, then freeze a different immutable version. Do not export automatically.");
        local=field("local");workspace=field("workspace");tasks=field("tasks");taskId=field("taskId");project=field("project");checkout=field("checkout");Files.createDirectories(active().resolve("raw/empty"));Files.write(active().resolve("raw/source.bin"),new byte[]{0,1,-1,42});Files.writeString(active().resolve(".env"),"example-private-not-exported");
    }
    @After public void close()throws Exception {try{if(activeModel!=null)diagnostic(activeModel,lastOutcome,"after");for(ProjectCapabilityPort port:ports)port.close();}finally{if(factory!=null)factory.close();}}
    @SuppressWarnings("unchecked") private <T>T field(String name)throws Exception {var f=ProjectManagedRunnerJourneyTest.class.getDeclaredField(name);f.setAccessible(true);return (T)f.get(factory);}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception {var m=ProjectManagedRunnerJourneyTest.class.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(factory,args);}catch(InvocationTargetException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();if(failed.getCause() instanceof Error)throw (Error)failed.getCause();throw failed;}}
    private Path active(){return RuntimeEnvironment.getApplication().getFilesDir().toPath().resolve("local-development/checkouts/"+checkout+"/files");}
    private void runWithOriginalOwnerDecisions(Model model)throws Exception {
        for(int attempt=0;attempt<16;attempt++){
            JSONObject task=tasks.startAttempt(taskId,"capabilities",120);String run=task.getString("runId");int epoch=task.getInt("coordinatorEpoch");runs.add(run);RunControl original=new RunControl();original.bindHostRun(taskId,run,epoch);AtomicReference<RunControl> live=field("live");live.set(original);
            if(attempt==0)tasks.bindDevelopmentContext(taskId,run,epoch,original,new JSONObject().put("format","pocketworld.development-context.v1").put("projectId",project).put("checkoutId",checkout).put("runtime",ManagedProjectRuntime.RUNTIME_ID).put("initialSourceDigest",ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest")).put("clientMessageId",UUID.randomUUID().toString()));
            var binding=new ModelCapabilityAdapters.Binding(taskId,run,epoch,original,true,project);ProjectCapabilityPort port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);ports.add(port);JSONArray tools=new JSONArray();for(int i=0;i<port.tools().length();i++)tools.put(port.tools().getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(taskId,run,tools);
            RunOutcome outcome=new AgentRunner(null,port).run(taskId,task.getString("goal"),model.config(),original,event->{try{tasks.event(taskId,run,epoch,event);}catch(Exception failed){throw new IllegalStateException(failed);}},tasks.journal(taskId,run,epoch,original));
            lastOutcome=outcome;String observed=diagnostic(model,outcome,"attempt-"+attempt);
            System.out.println("NEW_F010_RUNNER attempt="+attempt+" stage="+model.stage+" status="+outcome.status+" summary="+safeSummary(outcome.summary));
            tasks.endAttempt(taskId,run,outcome.status,outcome.summary,outcome.evidence);port.close();assertFalse("unknown must never be auto-replayed; "+observed,tasks.hasUnresolved(taskId));
            boolean pending=false;JSONArray approvals=tasks.get(taskId,false).getJSONArray("approvals");for(int i=0;i<approvals.length();i++){JSONObject a=approvals.getJSONObject(i);if("pending".equals(a.getString("state"))){tasks.decideCapabilityApproval(taskId,a.getString("id"),true);pending=true;}}
            if(!pending){assertTrue(observed,model.finished);assertEquals(observed,"needs_confirmation",outcome.status);return;}assertEquals(observed,"awaiting_user",outcome.status);
        }fail("owner-approved original run continuation did not converge; "+diagnostic(model,lastOutcome,"limit"));
    }
    /** Fixture-only diagnostic projection: no request bodies, arguments, command/env values,
     * credentials, stdout or raw events. Original SQL remains authoritative and unchanged. */
    private String diagnostic(Model model,RunOutcome outcome,String phase) {
        try {
            JSONObject view=new JSONObject().put("phase",phase).put("taskId",taskId==null?JSONObject.NULL:taskId)
                .put("stage",model.stage).put("lastTool",model.emittedTool).put("actualHttpRequests",model.requests.size())
                .put("emittedCalls",model.calls).put("originalRuns",runs.size()).put("approvedReferenceMigrations",model.refMigrations)
                .put("fixtureModelFinished",model.finished).put("modelFailure",model.failure==null?JSONObject.NULL:safeSummary(model.failure.toString()));
            if(outcome!=null)view.put("outcomeStatus",outcome.status).put("safeOutcomeSummary",safeSummary(outcome.summary));
            JSONArray operations=new JSONArray(),events=new JSONArray();
            if(tasks!=null&&taskId!=null){
                synchronized(tasks){
                    JSONObject current=tasks.get(taskId,false);view.put("taskStatus",current.optString("status")).put("runId",current.optString("runId")).put("coordinatorEpoch",current.optInt("coordinatorEpoch"));
                    var dbField=TaskStore.class.getDeclaredField("db");dbField.setAccessible(true);var db=(android.database.sqlite.SQLiteDatabase)dbField.get(tasks);
                    try(var rows=db.rawQuery("SELECT operation_id,run_id,tool,state,receipt FROM operations WHERE task_id=? ORDER BY created_at DESC,operation_id DESC LIMIT 64",new String[]{taskId})){
                        while(rows.moveToNext()){
                            JSONObject row=new JSONObject().put("operationId",rows.getString(0)).put("runId",rows.getString(1)).put("tool",rows.getString(2)).put("state",rows.getString(3));
                            if(!rows.isNull(4))try{row.put("nativeReceiptStatusOnly",safeNativeFields(new JSONObject(rows.getString(4)),0));}catch(Exception malformed){row.put("receiptParseError",malformed.getClass().getSimpleName());}operations.put(row);
                        }
                    }
                    JSONArray originalEvents=current.optJSONArray("events");if(originalEvents!=null)for(int i=Math.max(0,originalEvents.length()-16);i<originalEvents.length();i++){JSONObject event=originalEvents.optJSONObject(i);if(event!=null)events.put(safeNativeFields(event,0));}
                }
            }
            view.put("originalOperationsNewestFirst",operations).put("originalEventStatusOnly",events);
            String directory=System.getProperty("c2.evidenceDirectory");if(directory!=null)try{Path target=Path.of(directory);Files.createDirectories(target);Files.writeString(target.resolve("f010-"+runs.size()+"-"+phase+".json"),view.toString(2),StandardCharsets.UTF_8);}catch(IOException unavailable){view.put("evidenceWriteFailure",unavailable.getClass().getSimpleName());}
            String value=view.toString();System.out.println("NEW_F010_DIAGNOSTIC "+value);return value;
        }catch(Exception unavailable){String value="{\"diagnosticUnavailable\":\""+unavailable.getClass().getSimpleName()+"\",\"stage\":"+model.stage+",\"actualHttpRequests\":"+model.requests.size()+"}";System.out.println("NEW_F010_DIAGNOSTIC "+value);return value;}
    }
    private static JSONObject safeNativeFields(JSONObject input,int depth)throws Exception {
        JSONObject result=new JSONObject();for(String key:new String[]{"type","tool","status","state","code","operationId","executionId","taskId","runId","coordinatorEpoch","backend","exitCode","receiptReceived","accepted","ok","processExited","processLaunched","processGroupAbsent","resourcesReleased","nativeFileOperation","nativeOperationCode","nativePublicationEntered","artifactReady"}){
            Object value=input.opt(key);if(value instanceof Boolean||value instanceof Number)result.put(key,value);else if(value instanceof String)result.put(key,safeSummary((String)value).substring(0,Math.min(160,safeSummary((String)value).length())));
        }
        if(depth<2)for(String key:new String[]{"nativeReceipt","projectExecution","executionRef","data","receipt"}){JSONObject nested=input.optJSONObject(key);if(nested!=null)result.put(key,safeNativeFields(nested,depth+1));}return result;
    }
    private static String safeSummary(String value){if(value==null)return "";String text=value.replace("fixture-not-a-real-key","[fixture-key-redacted]").replaceAll("(?i)bearer\\s+\\S+","Bearer [redacted]").replaceAll("(?i)(api[_-]?key|token|password|secret)\\s*[:=]\\s*\\S+","$1=[redacted]");return text.length()>1200?text.substring(0,1200)+"…":text;}
    @Test public void actualBundlerApprovedAcrossRunsFreezesTwoVersionsThenColdImportsOriginalAndContinues()throws Exception {
        try(Model model=new Model()){
            runWithOriginalOwnerDecisions(model);assertEquals(30,model.stage);assertTrue(model.refMigrations>=8);assertTrue(runs.size()>1);assertEquals(2,model.packages.size());assertEquals(3,model.previews.size());assertTrue(model.actualDependency&&model.actualTests&&model.actualBuilds);AtomicReference<Exception> failures=field("callbackFailure");assertNull(failures.get());
            JSONObject first=model.packages.get(0),second=model.packages.get(1);assertNotEquals(first.getString("artifactId"),second.getString("artifactId"));assertNotEquals(first.getString("archiveSha256"),second.getString("archiveSha256"));
            ProjectPreviewBundle.Verified before=local.projectPreviewForOwner(model.previews.get(0).getString("previewId"),checkout),after=local.projectPreviewForOwner(model.previews.get(1).getString("previewId"),checkout),reopened=local.projectPreviewForOwner(model.previews.get(2).getString("previewId"),checkout);assertAmount(before,"40.00");assertAmount(after,"45.00");assertAmount(reopened,"40.00");assertEquals(before.artifactSha256,reopened.artifactSha256);assertEquals(4,before.manifest().length());
            WorkspaceModule cold=new WorkspaceModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}));JSONObject firstEvidence=tasks.projectPackageEvidenceForOwner(taskId,model.packageOperations.get(0));ProjectPackageDelivery.requireArtifact(firstEvidence,cold.projectPackageMetadata(first.getString("artifactId"),first.getString("archiveSha256")));
            ByteArrayOutputStream exported=new ByteArrayOutputStream();assertEquals(first.getLong("archiveBytes"),local.exportProjectPackageForOwner(first.getString("artifactId"),first.getString("archiveSha256"),exported));assertEquals(first.getString("archiveSha256"),ProjectSources.sha256(exported.toByteArray()));
            JSONObject staged=local.stageProjectPackageForOwner(new ByteArrayInputStream(exported.toByteArray()),UUID.randomUUID().toString()),importPlan=local.planProjectPackageImportForOwner(staged.getString("selectionId"),staged.getString("sha256"),"从40.00完整原件继续",null).getJSONObject("plan");JSONObject imported=local.importProjectPackageForOwner(importPlan);assertFalse(imported.getBoolean("taskOrGrantRestored"));assertNotEquals(checkout,imported.getString("id"));assertNotEquals(project,imported.getString("projectId"));
            Path newRoot=active().getParent().getParent().resolve(imported.getString("id")).resolve("files");assertArrayEquals(new byte[]{0,1,-1,42},Files.readAllBytes(newRoot.resolve("raw/source.bin")));assertTrue(Files.isDirectory(newRoot.resolve("raw/empty")));assertFalse(Files.exists(newRoot.resolve("node_modules")));assertFalse(Files.exists(newRoot.resolve(".npmrc")));assertFalse(Files.exists(newRoot.resolve(".env")));assertTrue(Files.readString(newRoot.resolve("index.html")).contains("40.00"));assertTrue(Files.readString(newRoot.resolve("dist/index.html")).contains("40.00"));
            // A new LocalModule reopens persisted original package/import metadata, not a cached live object.
            try(LocalModule reopenedLocal=new LocalModule(new FeatureContext(RuntimeEnvironment.getApplication(),null,null,event->{}),cold,field("runtime"))){JSONObject source=reopenedLocal.handle("readFile",new JSONObject().put("checkoutId",imported.getString("id")).put("path","index.html"));reopenedLocal.handle("writeFile",new JSONObject().put("checkoutId",imported.getString("id")).put("path","index.html").put("expectedHash",source.getString("sha256")).put("content",source.getString("content").replace("40.00","50.00")));assertThrows(Exception.class,()->reopenedLocal.handle("writeFile",new JSONObject().put("checkoutId",imported.getString("id")).put("path","index.html").put("expectedHash",source.getString("sha256")).put("content","stale")));JSONArray changes=reopenedLocal.handle("changes",new JSONObject().put("checkoutId",imported.getString("id"))).getJSONArray("files");for(int i=0;i<changes.length();i++)assertNotEquals("excluded generated paths must not become phantom deletions","deleted",changes.getJSONObject(i).getString("change"));}
            assertTrue(Files.readString(active().resolve("index.html")).contains("45.00"));assertAmount(local.projectPreviewForOwner(model.previews.get(0).getString("previewId"),checkout),"40.00");assertEquals(2,cold.handle("listArtifacts",new JSONObject().put("projectId",project)).getJSONArray("artifacts").length());
            assertNativeSuccessAudit();
            System.out.println("NEW_F010_COMPLETE actual original Runner/SQLite/host JNI + official locked esbuild npm ci + real tests/build -> actual owner decision/startAttempt NEW run and migrated ref -> native non-process immutable package -> model original-op package preview 40.00 -> same-task SHA-CAS edit/test/build -> package45.00 -> reopen40.00 -> actual export hash/bytes -> cold original-codec import into NEW project/checkout preserving binary/empty dirs -> explicit owner CAS50.00/stale reject; old source45 and artifact40 unchanged. No Android/SAF/DOM or real provider proof.");
        }
    }
    private void assertNativeSuccessAudit()throws Exception {
        java.util.Map<String,String> expected=java.util.Map.of("project_plan","project_plan_ready","project_package","project_package_observed","project_package_preview","project_package_preview_observed");
        java.util.Map<String,Integer> counts=new java.util.HashMap<>();int approvals=0;JSONArray events=tasks.get(taskId,false).getJSONArray("events");
        for(int i=0;i<events.length();i++){
            JSONObject row=events.getJSONObject(i);if(!"tool".equals(row.optString("type")))continue;String tool=row.optString("tool"),status=row.optString("status");JSONObject receipt=row.optJSONObject("receipt");if(receipt==null)continue;
            if(expected.containsKey(tool)&&java.util.Set.of("completed","failed").contains(status)){
                assertEquals("Native observation audit tool="+tool+" code="+receipt.optString("code"),"completed",status);
                assertEquals(expected.get(tool),receipt.getString("code"));assertTrue(receipt.getBoolean("ok"));assertTrue(receipt.getBoolean("accepted"));counts.merge(tool,1,Integer::sum);
            }
            if("project_execute".equals(tool)&&"approval_required".equals(receipt.optString("code"))){assertFalse(receipt.getBoolean("accepted"));assertNotEquals("A plan view never grants execution","completed",status);approvals++;}
        }
        for(String tool:expected.keySet())assertTrue("Missing native completed audit for "+tool+" counts="+counts,counts.getOrDefault(tool,0)>0);
        assertTrue("Original rejected-before-dispatch approvals must remain distinct",approvals>0);
        System.out.println("C2_NATIVE_TOOL_VIEW_AUDIT actual3 native success kinds completed="+counts+"; original execution approvals still not completed="+approvals+"; original immutable plan digest checked at every plan observation.");
    }
    private static void assertAmount(ProjectPreviewBundle.Verified view,String amount)throws Exception {String html=new String(view.resource(view.entryUrl).bytes(),StandardCharsets.UTF_8);assertTrue(html,html.contains("id=\"amount\">"+amount));for(int i=0;i<view.manifest().length();i++){String path=view.manifest().getJSONObject(i).getString("path");assertNotNull(view.resource(view.origin+path));}}
    private final class Model implements AutoCloseable {
        final ServerSocket server=new ServerSocket();final Thread worker;final List<JSONObject> requests=new CopyOnWriteArrayList<>(),packages=new ArrayList<>(),previews=new ArrayList<>();final List<String> packageOperations=new ArrayList<>();volatile Throwable failure;volatile int stage;int calls,refMigrations;String emitted="",emittedTool="";boolean refreshResult,waitingApproval,finished,actualDependency,actualTests,actualBuilds;JSONObject inspection,plan,read;
        Model()throws Exception{server.bind(new InetSocketAddress("127.0.0.1",0));worker=new Thread(this::serve,"new-f010-fixture-model");worker.setDaemon(true);worker.start();activeModel=this;}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getLocalPort()+"/v1","fixture-not-a-real-key","new-f010-package-model",false,40,30).withStepCeiling(120);}
        JSONObject next(JSONObject request)throws Exception {
            JSONArray messages=request.getJSONArray("messages");JSONObject last=null;for(int i=messages.length()-1;i>=0;i--)if("tool".equals(messages.getJSONObject(i).optString("role"))){last=messages.getJSONObject(i);break;}
            if(last!=null&&emitted.equals(last.optString("tool_call_id"))){JSONObject result=new JSONObject(last.getString("content"));emitted="";if("approval_required".equals(result.optString("code"))){waitingApproval=true;refreshResult=true;return emit("project_inspect",new JSONObject());}
                if(refreshResult){JSONArray refs=result.getJSONArray("approvedPlanProposals");JSONObject match=null;String step=plan.getJSONArray("steps").getJSONObject(0).getString("id");for(int i=0;i<refs.length();i++){JSONObject candidate=refs.getJSONObject(i);if(step.equals(candidate.getString("stepId"))&&result.getString("sourceDigest").equals(candidate.getString("sourceDigest"))){assertNull("unique original approved proposal",match);match=candidate;}}assertNotNull("original new run must expose approved proposal",match);assertNotEquals("old run ref must not be reused",plan.getString("planRef"),match.getString("planRef"));plan.put("planRef",match.getString("planRef")).put("planDigest",match.getString("planDigest"));inspection=result;refMigrations++;refreshResult=false;waitingApproval=false;
                }else{accept(result);stage++;}}
            if(waitingApproval){refreshResult=true;return emit("project_inspect",new JSONObject());}
            JSONObject args=new JSONObject();String tool;switch(stage){
                case 0,3,6,9,15,18,21,24:tool="project_inspect";break;
                case 1:tool="project_plan";args=plan("dependencies");break;
                case 4,19:tool="project_plan";args=plan("test");break;
                case 7,22:tool="project_plan";args=plan("build");break;
                case 10,25:tool="project_plan";args=plan("freeze_package").put("packageMode","source_project").put("includeGeneratedRoots",true);break;
                case 2,5,8,11,17,20,23,26:tool="project_execute";args.put("planRef",plan.getString("planRef")).put("planDigest",plan.getString("planDigest")).put("stepId",plan.getJSONArray("steps").getJSONObject(0).getString("id"));break;
                case 12,27:tool="project_package";args.put("executionId",packageOperations.get(packageOperations.size()-1));break;
                case 13,28,29:tool="project_package_preview";args.put("executionId",packageOperations.get(stage==28?1:0)).put("entryPath","dist/index.html");break;
                case 14:tool="project_read_file";args.put("path","index.html");break;
                case 16:tool="project_plan";args=plan("edit").put("path","index.html").put("expectedHash",read.getString("sha256")).put("content",read.getString("content").replace("40.00","45.00"));break;
                default:finished=true;return new JSONObject().put("content","Two original immutable package versions are available. The earlier 40.00 bytes were reopened after the same-task 45.00 change. No export or Android verification is claimed by the model.");
            }return emit(tool,args);
        }
        private JSONObject plan(String intent)throws Exception{return new JSONObject().put("intent",intent).put("ecosystem","node").put("expectedSourceDigest",inspection.getString("sourceDigest"));}
        private JSONObject emit(String tool,JSONObject args)throws Exception{emitted="new-f010-"+stage+"-"+(++calls);emittedTool=tool;return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",emitted).put("type","function").put("function",new JSONObject().put("name",tool).put("arguments",args.toString()))));}
        private void accept(JSONObject result)throws Exception {
            assertFalse("stage="+stage+" real tool="+emittedTool+" result="+result,result.has("ok")&&!result.getBoolean("ok"));assertNotEquals("stage="+stage+" "+result,"unknown",result.optString("status"));assertNotEquals("stage="+stage+" "+result,"failed",result.optString("status"));
            if(result.has("ecosystems"))inspection=result;if("project_plan".equals(emittedTool)){assertTrue(result.toString(),result.has("planRef"));plan=result;
                assertTrue(result.getBoolean("ok"));assertEquals("project_plan_ready",result.getString("code"));assertFalse("Plan view never authorizes execution",result.getBoolean("executionAuthorized"));assertFalse("Plan creation is not queryComplete",result.has("queryComplete"));
                JSONObject current=tasks.get(taskId,false),full=tasks.projectArguments(taskId,current.getString("runId"),current.getInt("coordinatorEpoch"),new JSONObject().put("planRef",result.getString("planRef")).put("planDigest",result.getString("planDigest")).put("stepId",result.getJSONArray("steps").getJSONObject(0).getString("id"))).getJSONObject("plan");
                assertEquals(result.getString("planDigest"),ProjectBuildPlan.verified(full).getString("planDigest"));assertFalse(full.has("ok"));assertFalse(full.has("accepted"));assertFalse(full.has("queryComplete"));
                if(result.has("plan")){JSONObject inline=result.getJSONObject("plan");assertEquals(full.getString("planDigest"),ProjectBuildPlan.verified(inline).getString("planDigest"));assertFalse(inline.has("ok"));assertFalse(inline.has("accepted"));}
                if(stage==10||stage==25){assertFalse("freeze must not transport inline full plan",result.has("plan"));assertTrue(result.getJSONObject("packageSelection").getBoolean("fullManifestInOriginalPlan"));}}
            if(stage==2){assertTrue(Files.isRegularFile(active().resolve("node_modules/esbuild/package.json")));assertEquals(0,result.getInt("exitCode"));actualDependency=true;}
            if(stage==5||stage==20){assertEquals(0,result.getInt("exitCode"));assertTrue(result.getString("stdout"),result.getString("stdout").contains("toggle and filters describe the same selected item"));actualTests=true;}
            if(stage==8||stage==23){assertEquals(0,result.getInt("exitCode"));assertTrue(Files.readString(active().resolve("dist/index.html")).contains(stage==8?"40.00":"45.00"));actualBuilds=true;}
            if(stage==11||stage==26){assertTrue(result.isNull("processExitCode"));assertTrue(result.getBoolean("nativeFileOperation"));String op=result.getJSONObject("projectExecution").getString("operationId");boolean originalFileReceipt=false;JSONArray rows=tasks.get(taskId,false).getJSONArray("operations");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(op.equals(row.getString("operationId"))){assertEquals("receipt",row.getString("state"));assertEquals("project_file_receipt",row.getJSONObject("receipt").getString("code"));originalFileReceipt=true;}}assertTrue(originalFileReceipt);packageOperations.add(op);}
            if(stage==12||stage==27)packages.add(result);if(stage==13||stage==28||stage==29){previews.add(result);ProjectPreviewBundle.Verified view=local.projectPreviewForOwner(result.getString("previewId"),checkout);assertAmount(view,stage==28?"45.00":"40.00");}
            if(stage==14)read=result;
        }
        private void serve(){while(!server.isClosed())try(Socket socket=server.accept()){socket.setSoTimeout(30000);InputStream input=new BufferedInputStream(socket.getInputStream());line(input);int length=0;for(String header;!(header=line(input)).isEmpty();)if(header.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());if(length<1||length>2*1024*1024)throw new IOException("fixture request bound");JSONObject request=new JSONObject(new String(input.readNBytes(length),StandardCharsets.UTF_8));requests.add(request);JSONObject message=next(request).put("role","assistant");byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();}catch(Throwable failed){if(!server.isClosed()){failure=failed;try{server.close();}catch(Exception ignored){}}}}
        private String line(InputStream input)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();for(int b;(b=input.read())!=-1;){if(b=='\n')return out.toString(StandardCharsets.US_ASCII).replace("\r","");if(out.size()>8192)throw new IOException("header bound");out.write(b);}throw new EOFException("fixture EOF");}
        public void close()throws Exception{server.close();worker.join(2000);if(failure!=null)throw new AssertionError("new F010 fixture stage="+stage,failure);}
    }
}
