package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.*;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.*;
import dev.pocketengine.features.local.preview.ProjectPreviewBundle;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** One existing factory, real SQLite/Runner/host JNI/Vite. The model is a loopback
 * fixture; no Android provider, browser JS, ARM or real model is claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectLiveRunnerJourneyTest {
    private ProjectManagedRunnerJourneyTest factory;private LocalModule local;private TaskStore tasks;private ManagedProjectRuntime runtime;private RunControl original;private JSONObject task;private String taskId,checkout,project,run;private int epoch;private Path active;private ProjectCapabilityPort port;private RuntimeExecutionRef serviceRef;private String startupAck,serviceId;private int delegatedPreparedChecks;private String originalBudget,originalTests;
    @SuppressWarnings("unchecked") private <T>T field(String name)throws Exception {Field field=ProjectManagedRunnerJourneyTest.class.getDeclaredField(name);field.setAccessible(true);return (T)field.get(factory);}
    private Object call(String name,Class<?>[] types,Object... values)throws Exception {Method method=ProjectManagedRunnerJourneyTest.class.getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(factory,values);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw (Error)failure.getCause();}}
    @Before public void create()throws Exception {
        factory=new ProjectManagedRunnerJourneyTest();factory.create();local=field("local");tasks=field("tasks");runtime=field("runtime");call("selectedProject",new Class<?>[]{String.class},"Actual Vite live-edit original task");
        JSONArray files=ProjectTemplates.files("web-live");for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);call("file",new Class<?>[]{String.class,String.class},file.getString("path"),file.getString("content"));}
        Path cache=Path.of(Objects.requireNonNull(System.getProperty("c2.npmCache"))).toRealPath();call("file",new Class<?>[]{String.class,String.class},".npmrc","cache="+cache+"\noffline=true\n");
        call("startTask",new Class<?>[]{String.class},"Use the explicit existing-source dev-watch approval, keep the original job live during SHA edits, repair actual errors in this same task, stop/drain then build the corresponding fixed result.");task=(JSONObject)call("startFixtureAttempt",new Class<?>[0]);taskId=field("taskId");checkout=field("checkout");project=field("project");active=(Path)call("active",new Class<?>[0]);original=((AtomicReference<RunControl>)field("live")).get();run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");
        var binding=new ModelCapabilityAdapters.Binding(taskId,run,epoch,original,true,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);JSONArray names=new JSONArray(),tools=port.tools();for(int i=0;i<tools.length();i++)names.put(tools.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(taskId,run,names);
        originalBudget=Files.readString(active.resolve("src/budget.js"));originalTests=Files.readString(active.resolve("test/budget.test.js"));
    }
    @After public void close()throws Exception {try{if(port!=null)port.close();}finally{if(factory!=null)factory.close();}}
    private JSONObject binding(String operation)throws Exception{return new JSONObject().put("taskId",taskId).put("runId",run).put("coordinatorEpoch",epoch).put("operationId",operation);}
    private JSONObject serviceRow()throws Exception {JSONArray rows=tasks.get(taskId,false).getJSONArray("operations");for(int i=0;i<rows.length();i++)if(serviceId.equals(rows.getJSONObject(i).optString("operationId")))return rows.getJSONObject(i);throw new AssertionError("original service missing");}
    private JSONObject serviceActual()throws Exception {return runtime.query(serviceRef).json();}
    private void assertSameLiveService()throws Exception {JSONObject actual=serviceActual();assertEquals("running",actual.getString("status"));assertEquals(startupAck,actual.getString("launchAckIdentity"));assertTrue(actual.getBoolean("ownedWorkerLive"));assertFalse(actual.getBoolean("processExited"));assertFalse(actual.getBoolean("resourcesReleased"));assertEquals(1,countLaunches());assertFalse(original.isCancelled());}
    private int countLaunches()throws Exception {int count=0;JSONArray rows=tasks.get(taskId,false).getJSONArray("operations");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!"project_execute".equals(row.optString("tool")))continue;JSONObject args=row.getJSONObject("arguments"),plan=args.getJSONObject("plan"),step=ProjectBuildPlan.step(plan,args.getString("stepId"));if("owned_service".equals(step.getJSONObject("payload").optString("runMode")))count++;}return count;}
    private String get(ProjectDevSession.Observed observed,String resource,int expected)throws Exception {assertTrue(observed.allows(observed.baseUrl+resource));HttpURLConnection http=(HttpURLConnection)new URL(observed.baseUrl+resource).openConnection();http.setConnectTimeout(1500);http.setReadTimeout(4000);http.setInstanceFollowRedirects(false);try{assertEquals(resource,expected,http.getResponseCode());InputStream input=expected>=400?http.getErrorStream():http.getInputStream();byte[] data=input==null?new byte[0]:input.readNBytes(256*1024);assertTrue(data.length<256*1024);return new String(data,StandardCharsets.UTF_8);}finally{http.disconnect();}}
    private ProjectDevSession.Observed page()throws Exception {long until=System.nanoTime()+8_000_000_000L;Exception last=null;do{try{return local.projectLivePreviewForHost(binding(serviceId),serviceId,new RunControl());}catch(IllegalStateException|IOException unavailable){last=unavailable;Thread.sleep(30);}}while(System.nanoTime()<until);throw new AssertionError("actual original HTTP/WS was not observed",last);}
    private RunJournal tapped(RunJournal nativeJournal){return (RunJournal)java.lang.reflect.Proxy.newProxyInstance(RunJournal.class.getClassLoader(),new Class<?>[]{RunJournal.class},(proxy,method,args)->{
        try{
            Object result=method.invoke(nativeJournal,args);
            if(method.getName().equals("beginOperation")&&args[0].equals("project_execute")){
                JSONObject input=(JSONObject)args[1],plan=input.getJSONObject("plan"),step=ProjectBuildPlan.step(plan,input.getString("stepId"));
                if(step.getJSONObject("payload").has("liveEditServiceExecutionId")){
                    assertThrows("prepared live edit still blocks model restore",IllegalStateException.class,nativeJournal::restore);
                    runtime.requireLivePreview(serviceRef,original); // its supervisor alone may retain ownership during the approved pending file CAS
                    try(var lease=runtime.beginLiveEdit(serviceRef,original)){lease.check();}
                    delegatedPreparedChecks++;
                }
            }
            return result;
        }catch(InvocationTargetException failure){throw failure.getCause();}
    });}
    @Test public void actualOriginalRunnerKeepsOneNativeServiceLiveAcrossCasErrorsAndRepairThenDrainsAndBuilds()throws Exception {
        try(Model model=new Model()){
            RunJournal nativeJournal=tasks.journal(taskId,run,epoch,original);CapabilityPort traced=(CapabilityPort)java.lang.reflect.Proxy.newProxyInstance(CapabilityPort.class.getClassLoader(),new Class<?>[]{CapabilityPort.class},(proxy,method,arguments)->{try{return method.invoke(port,arguments);}catch(InvocationTargetException failed){System.out.println("C13_ACTUAL_PORT_FAILURE method="+method.getName());failed.getCause().printStackTrace(System.out);throw failed.getCause();}});RunOutcome outcome=new AgentRunner(null,traced).run(taskId,task.getString("goal"),model.config(),original,event->{try{tasks.event(taskId,run,epoch,event);}catch(Exception failure){throw new IllegalStateException(failure);}},tapped(nativeJournal));
            System.out.println("C13_ACTUAL stage="+model.stage+" status="+outcome.status+" modelFailure="+model.failure+" evidence="+outcome.evidence);
            if(model.failure!=null)throw new AssertionError("actual fixture model failed at "+model.stage,model.failure);
            assertTrue(outcome.summary,model.finished);assertEquals(36,model.stage);assertEquals(2,delegatedPreparedChecks);assertEquals(1,countLaunches());assertTrue(model.sawActualDevError);assertTrue(model.sawNonzeroTests);assertTrue(model.sawRepairedTests);assertTrue(model.sawBusiness45);assertFalse(original.isCancelled());assertFalse(tasks.hasUnresolved(taskId));
            JSONObject exits=nativeJournal.projectServiceExitEvidence(new JSONArray().put(serviceId));assertNotNull(exits);assertTrue(exits.getBoolean("allReleased"));assertTrue(serviceActual().getBoolean("resourcesReleased"));assertTrue(serviceActual().getBoolean("processExited"));assertTrue(serviceActual().getBoolean("processGroupAbsent"));assertEquals(2,serviceActual().getInt("sourceGeneration"));assertEquals(startupAck,serviceActual().getString("launchAckIdentity"));
            JSONObject preview=model.preview;assertNotNull(preview);ProjectPreviewBundle.Verified fixed=local.projectPreviewForOwner(preview.getString("previewId"),checkout);assertEquals(4,fixed.manifest().length());for(int i=0;i<fixed.manifest().length();i++){String path=fixed.manifest().getJSONObject(i).getString("path");assertArrayEquals(Files.readAllBytes(active.resolve("dist").resolve(path)),fixed.resource(fixed.origin+path).bytes());}
            JSONArray rows=tasks.get(taskId,false).getJSONArray("operations");int edits=0;String approvalId=serviceRow().getJSONObject("receipt").getJSONObject("projectExecution").getString("approvalId");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i),receipt=row.optJSONObject("receipt"),route=receipt==null?null:receipt.optJSONObject("projectExecution");if(route!=null&&route.has("liveEditServiceExecutionId")){assertEquals("receipt",row.getString("state"));assertEquals(serviceId,route.getString("liveEditServiceExecutionId"));assertEquals(approvalId,route.getString("approvalId"));assertEquals("local_edit",receipt.getJSONObject("executionReceipt").getString("kind"));edits++;}}assertEquals(2,edits);
            assertNull(((AtomicReference<Exception>)field("callbackFailure")).get());tasks.endAttempt(taskId,run,outcome.status,outcome.summary,outcome.evidence);assertFalse(tasks.hasUnresolved(taskId));
            System.out.println("C13_ACTUAL originalRunner_SQL_nativeSupervisor=true; officialNpmCi_ViteRealBuild=true; oneOriginalNativeServiceRef=true; realHttpChallenge_WebSocket=true; approvedExistingSourceAtomicShaCasWhileSameJobLive=true; preparedEditStillBlocksInference=true; ordinaryWriteConfigurationForeignEpochUnknownDenied=true; actual500AndViteStderrInSameTaskNextModel=true; sameServiceBusiness40to45=true; realNonzeroTestThenFixtureDirectedRepair=true; explicitStopRealExitGroupLease=true; fixedFourResourceResult=true; Android_ARM_provider_browserDOM=false");
        }
    }
    private final class Model implements AutoCloseable {
        final ServerSocket server=new ServerSocket();final Thread thread;volatile Throwable failure;int stage,calls;String emitted="";JSONObject inspection,plan,read,preview;boolean finished,sawActualDevError,sawNonzeroTests,sawRepairedTests,sawBusiness45;ProjectDevSession.Observed observed;
        Model()throws Exception {server.bind(new InetSocketAddress("127.0.0.1",0));thread=new Thread(this::serve,"c13-existing-loopback-model-fixture");thread.setDaemon(true);thread.start();}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+server.getLocalPort()+"/v1","fixture-not-real-key","fixture-live-project",false,40,30).withStepCeiling(120);}
        JSONObject plan(String intent)throws Exception{return new JSONObject().put("intent",intent).put("ecosystem","node").put("expectedSourceDigest",inspection.getString("sourceDigest"));}
        JSONObject edit(String path,String content,boolean liveEdit)throws Exception {JSONObject args=plan("edit").put("path",path).put("content",content).put("expectedHash",read.getString("sha256"));if(liveEdit)args.put("liveEditServiceExecutionId",serviceId);return args;}
        void denyUnsafe(JSONObject livePlan)throws Exception {
            assertSameLiveService();JSONObject args=new JSONObject().put("plan",livePlan).put("stepId","edit");assertTrue(tasks.projectLiveEditAllowed(taskId,run,epoch,args));
            assertThrows(IllegalStateException.class,()->local.handle("writeFile",new JSONObject().put("checkoutId",checkout).put("path","src/budget.js").put("expectedHash",read.getString("sha256")).put("content","unapproved raw write")));
            JSONObject bad=ProjectSources.copy(livePlan),core=ProjectSources.copy(bad);for(String name:new String[]{"planDigest","planId"})core.remove(name);JSONObject payload=core.getJSONArray("steps").getJSONObject(0).getJSONObject("payload");payload.put("path","tools/dev.mjs");String digest=ProjectSources.digest(core);core.put("planDigest",digest).put("planId",digest);assertThrows(SecurityException.class,()->tasks.projectLiveEditAllowed(taskId,run,epoch,new JSONObject().put("plan",core).put("stepId","edit")));
            assertThrows(SecurityException.class,()->tasks.projectLiveEditAllowed(taskId,run,epoch+1,args));
            // Atomic SQL negative only; a real worker must never see this invented unknown as a genuine authority withdrawal.
            synchronized(tasks){Field field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);var db=(android.database.sqlite.SQLiteDatabase)field.get(tasks);String unknown=UUID.randomUUID().toString();var value=new android.content.ContentValues();value.put("operation_id",unknown);value.put("task_id",taskId);value.put("run_id",run);value.put("tool","unrelated_real_uncertainty_contract");value.put("arguments","{}");value.put("state","unknown");value.put("created_at",System.currentTimeMillis());db.insertOrThrow("operations",null,value);try{assertThrows(IllegalStateException.class,()->tasks.journal(taskId,run,epoch,original).restore());assertThrows(IllegalStateException.class,()->tasks.journal(taskId,run,epoch,original).beginOperation("project_execute",args));}finally{db.delete("operations","operation_id=?",new String[]{unknown});}}
        }
        JSONObject next(JSONObject request)throws Exception {
            JSONArray history=request.getJSONArray("messages");JSONObject last=null;for(int i=0;i<history.length();i++)if("tool".equals(history.getJSONObject(i).optString("role")))last=history.getJSONObject(i);
            if(last!=null&&emitted.equals(last.optString("tool_call_id"))){
                JSONObject result=new JSONObject(last.getString("content"));Files.writeString(Path.of(System.getProperty("java.io.tmpdir")).resolve("c13-actual-model-tool-"+stage+".json"),result.toString(2));if(stage==1&&!result.has("plan")){System.out.println("C13_ACTUAL_PLAN_FAILURE "+result);throw new AssertionError("original project_plan receipt has no executable plan: "+result);}assertNotEquals("approval_required",result.optString("code"));
                if(result.has("ecosystems"))inspection=result;if(result.has("updatedSourceDigest")&&"completed".equals(result.optString("status"))){String actual=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");assertEquals("native save result must match actual current bytes",actual,result.getString("updatedSourceDigest"));inspection.put("sourceDigest",actual);}if(result.has("plan")){plan=result.getJSONObject("plan");if(stage!=12&&stage!=18)call("approveControlledOwnerPlan",new Class<?>[]{JSONObject.class},plan);else if(stage==12)denyUnsafe(plan);}
                if(result.has("sha256")&&result.has("content"))read=result;
                if(stage==2||stage==4||stage==6||stage==31||stage==33){assertEquals(result.toString(),0,result.getInt("exitCode"));if(stage==31)sawRepairedTests=true;}
                if(stage==9){assertEquals("project_running",result.getString("code"));serviceId=result.getString("operationId");JSONObject row=serviceRow(),args=row.getJSONObject("arguments"),launchPlan=args.getJSONObject("plan");serviceRef=RuntimeExecutionRef.fromHost(ProjectDevelopment.exact(launchPlan,ProjectBuildPlan.step(launchPlan,args.getString("stepId"))),row.getJSONObject("receipt").getJSONObject("projectExecution"));startupAck=result.getString("launchAckIdentity");assertFalse(result.getBoolean("serviceReady"));assertFalse(result.getBoolean("receiptReceived"));assertSameLiveService();observed=page();}
                if(stage==10){assertTrue(result.getBoolean("httpObserved"));assertTrue(result.getBoolean("websocketObserved"));observed=page();for(String path:new String[]{"index.html","src/main.js","src/model.js","src/view.js","src/budget.js","src/style.css"})get(observed,path,200);assertEquals("40.00",new JSONObject(get(observed,"api/budget?costs=20,20",200)).getString("total"));assertFalse(observed.allows(observed.baseUrl+"%2e%2e/.pocketworld/x"));assertFalse(observed.allows("http://example.com/"));get(observed,".pocketworld/dev-"+serviceId+".json",403);}
                if(stage==13){assertEquals(0,result.getInt("exitCode"));assertSameLiveService();String error=get(observed,"src/budget.js",500);assertTrue(error.contains("budget.js"));tasks.recordDevelopmentLivePreviewConsole(taskId,observed,"RENDER_ERROR","Actual fixture HTTP module request returned 500: "+error.substring(0,Math.min(1000,error.length())),"src/budget.js",1);Thread.sleep(250);}
                if(stage==14){StringBuilder log=new StringBuilder();JSONArray chunks=result.getJSONArray("chunks");for(int i=0;i<chunks.length();i++)log.append(new String(Base64.getDecoder().decode(chunks.getJSONObject(i).getString("dataBase64")),StandardCharsets.UTF_8));assertTrue(log.toString(),log.toString().contains("budget.js"));assertTrue(log.toString(),log.toString().contains("Failed to parse")||log.toString().contains("syntax"));sawActualDevError=true;}
                if(stage==15)assertTrue(result.toString(),result.getJSONArray("entries").toString().contains("500"));
                if(stage==19){assertEquals(0,result.getInt("exitCode"));assertSameLiveService();assertEquals(2,serviceActual().getInt("sourceGeneration"));}
                if(stage==20){observed=page();long until=System.nanoTime()+5_000_000_000L;String total="";do{try{total=new JSONObject(get(observed,"api/budget?costs=20,20",200)).getString("total");}catch(AssertionError stillInvalidating){System.out.println("C13_OBSERVATION original business module still returning its previous error during watcher invalidation");}if(total.equals("45.00"))break;Thread.sleep(30);}while(System.nanoTime()<until);assertEquals("45.00",total);sawBusiness45=true;assertSameLiveService();}
                if(stage==21){assertEquals("project_service_observation",result.getString("code"));assertEquals(2,result.getInt("sourceGeneration"));assertNotEquals(result.getString("sourceDigest"),result.getString("activeSourceDigest"));}
                if(stage==22){assertEquals("project_input_observation",result.getString("code"));assertTrue(result.getBoolean("timedOut"));assertFalse(result.getBoolean("available"));assertFalse(result.getBoolean("consumed"));assertSameLiveService();}
                if(stage==23){assertEquals("project_stop_confirmed",result.getString("code"));assertTrue(result.getBoolean("resourcesReleased"));assertFalse(original.isCancelled());assertThrows(SecurityException.class,()->local.requireProjectLivePreviewForOwner(observed));}
                if(stage==26){assertNotEquals(result.toString(),0,result.getInt("exitCode"));assertTrue(result.toString(),result.getString("stdout").contains("45.00")||result.getString("stderr").contains("45.00"));sawNonzeroTests=true;}
                if(stage==35){preview=result;assertEquals(4,result.getInt("resourceCount"));}
                stage++;emitted="";
            }
            String name;JSONObject args=new JSONObject();switch(stage){
                case 0,7,17,24,34:name="project_inspect";break;
                case 1:name="project_plan";args=plan("dependencies");break;
                case 3,25,30:name="project_plan";args=plan("test");break;
                case 5,32:name="project_plan";args=plan("build");break;
                case 8:name="project_plan";args=plan("run").put("runMode","owned_service").put("devWatch",true).put("liveEditPaths",new JSONArray().put("src/main.js").put("src/budget.js").put("src/style.css"));break;
                case 2,4,6,9,13,19,26,29,31,33:name="project_execute";args.put("plan",plan).put("stepId",plan.getJSONArray("steps").getJSONObject(0).getString("id"));break;
                case 10,20:name="project_live_preview";args.put("executionId",serviceId);break;
                case 11,16:name="project_read_file";args.put("path","src/budget.js");break;
                case 12:name="project_plan";args=edit("src/budget.js","export function budgetTotal( { broken syntax\n",true);break;
                case 14:name="project_log";args.put("executionId",serviceId).put("cursor",0);break;
                case 15:name="project_preview_log";break;
                case 18:name="project_plan";args=edit("src/budget.js",originalBudget.replace("cents / 100","(cents + 500) / 100"),true);break;
                case 21:name="project_status";args.put("executionId",serviceId);break;
                case 22:name="project_wait_input";args.put("executionId",serviceId).put("maxSeconds",1);break;
                case 23:name="project_stop";args.put("executionId",serviceId);break;
                case 27:name="project_read_file";args.put("path","test/budget.test.js");break;
                case 28:name="project_plan";args=edit("test/budget.test.js",originalTests.replace("'45.00'","'50.00'").replace("'40.00'","'45.00'").replace("'0.30'","'5.30'"),false);break;
                case 35:name="project_preview";args.put("entryPath","dist/index.html").put("expectedSourceDigest",inspection.getString("sourceDigest"));break;
                default:finished=true;return new JSONObject().put("content","服务仍在运行，已边运行边修复并取得45.00业务响应；这句话是fixture模型旧叙述，最终必须按原native退出/进程组/租约证据纠正，固定成果另存。");
            }
            if(stage==1)System.out.println("C13_ACTUAL_PLAN_ARGUMENTS "+args);emitted="c13-native-live-"+stage+"-"+(++calls);return new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",emitted).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()))));
        }
        void serve(){while(!server.isClosed())try(Socket socket=server.accept()){socket.setSoTimeout(30000);InputStream input=new BufferedInputStream(socket.getInputStream());line(input);int length=0;for(String header;!(header=line(input)).isEmpty();)if(header.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());if(length<1||length>2*1024*1024)throw new IllegalArgumentException("fixture model request bound");JSONObject message=next(new JSONObject(new String(input.readNBytes(length),StandardCharsets.UTF_8))).put("role","assistant");byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();}catch(Throwable error){if(!server.isClosed()){failure=error;try{server.close();}catch(Exception ignored){}}}}
        String line(InputStream input)throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(int value;(value=input.read())>=0;){if(value=='\n')return bytes.toString(StandardCharsets.US_ASCII).replace("\r","");if(bytes.size()>8192)throw new IllegalArgumentException("fixture model header bound");bytes.write(value);}throw new EOFException();}
        public void close()throws Exception {server.close();thread.join(2000);if(failure!=null)throw new AssertionError("real original-loop fixture model",failure);}
    }
}
