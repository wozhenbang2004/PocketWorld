package dev.pocketengine.features.local.project;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.*;
import dev.pocketengine.features.capabilities.*;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

/** Original SQL, exact approval, real host JNI process and original log pages.
 * No physical Android, UI pixel, energy or device performance claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class ProjectExecutionContinuityJourneyTest {
    private ProjectManagedRunnerJourneyTest fixture;private TaskStore tasks;private LocalModule local;private ProjectCapabilityPort port;private RunControl original;private RunJournal journal;private TaskStoreProjectHost host;private String task,run,checkout;private int epoch;
    private Object invoke(String method,Class<?>[] types,Object...args)throws Exception{var target=fixture.getClass().getDeclaredMethod(method,types);target.setAccessible(true);try{return target.invoke(fixture,args);}catch(InvocationTargetException failed){if(failed.getCause() instanceof Exception)throw (Exception)failed.getCause();throw failed;}}
    @SuppressWarnings("unchecked")private <T>T field(String name)throws Exception{var target=fixture.getClass().getDeclaredField(name);target.setAccessible(true);return (T)target.get(fixture);}
    @Before public void setup()throws Exception{
        fixture=new ProjectManagedRunnerJourneyTest();fixture.createTerminalFixture();invoke("selectedProject",new Class<?>[]{String.class},"Original execution continuity");invoke("file",new Class<?>[]{String.class,String.class},"README.md","Own bounded original process fixture.\n");invoke("startTask",new Class<?>[]{String.class},"Keep one approved execution and its original deadline, log and stop");
        JSONObject started=(JSONObject)invoke("startFixtureAttempt",new Class<?>[0]);tasks=field("tasks");local=field("local");host=field("host");task=field("taskId");checkout=field("checkout");String project=field("project");run=started.getString("runId");epoch=started.getInt("coordinatorEpoch");original=this.<AtomicReference<RunControl>>field("live").get();
        var binding=new ModelCapabilityAdapters.Binding(task,run,epoch,original,true,project);port=new ProjectCapabilityPort(binding,new TaskStoreCapabilityAuthority(tasks,binding,event->{}),original,null,local,tasks,checkout,ManagedProjectRuntime.RUNTIME_ID);JSONArray names=new JSONArray(),schemas=port.tools();for(int i=0;i<schemas.length();i++)names.put(schemas.getJSONObject(i).getJSONObject("function").getString("name"));tasks.authorizeTools(task,run,names);journal=tasks.journal(task,run,epoch,original);
    }
    @After public void close()throws Exception{try{if(port!=null)port.close();}finally{if(fixture!=null)fixture.close();}}
    private JSONObject tool(String name,JSONObject args)throws Exception{String op=port.sideEffecting(name)?journal.beginOperation(name,args):null;JSONObject input=new JSONObject(args.toString());if(op!=null)input.put("_hostOperationId",op);JSONObject actual=port.execute(name,input,original);if(op!=null)journal.finishOperation(op,actual);return actual;}
    private JSONObject reviewed(String program,long wait,long total)throws Exception{
        String source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");
        JSONObject ref=tool("project_plan",new JSONObject().put("intent","command").put("executable","node").put("argv",new JSONArray().put("-e").put(program)).put("runMode","owned_service").put("foregroundWaitMillis",wait).put("timeoutMillis",total).put("expectedSourceDigest",source));
        JSONObject args=new JSONObject().put("planRef",ref.getString("planRef")).put("planDigest",ref.getString("planDigest")).put("stepId","command"),pending=tool("project_execute",args);assertEquals(pending.toString(),"approval_required",pending.getString("code"));tasks.decideCapabilityApproval(task,pending.getString("approvalId"),true);return args;
    }
    private long eventCount()throws Exception{var db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);try(Cursor rows=((SQLiteDatabase)db.get(tasks)).rawQuery("SELECT count(*) FROM task_events WHERE task_id=?",new String[]{task})){assertTrue(rows.moveToFirst());return rows.getLong(0);}}
    private int dispatchedExecutions()throws Exception{int count=0;JSONArray rows=tasks.get(task,false).getJSONArray("operations");for(int i=0;i<rows.length();i++)if("project_execute".equals(rows.getJSONObject(i).optString("tool"))&&rows.getJSONObject(i).optJSONObject("receipt")!=null&&rows.getJSONObject(i).getJSONObject("receipt").has("projectExecution"))count++;return count;}
    @Test public void commandExitSevenDuringShortWaitIsFailureNotBackgroundSuccess()throws Exception{
        JSONObject args=reviewed("process.stdout.write('ORIGINAL-EXIT-SEVEN');setTimeout(()=>process.exit(7),100)",1500,5000),ended=tool("project_execute",args);
        assertEquals(ended.toString(),"failed",ended.getString("status"));assertEquals(7,ended.getInt("exitCode"));assertTrue(ended.getBoolean("receiptReceived"));assertTrue(ended.getBoolean("resourcesReleased"));assertEquals(1,dispatchedExecutions());assertEquals(run,tasks.get(task,false).getString("runId"));
        String execution=ended.getString("operationId");try(var viewer=host.ownerTerminals().openObservation(task,execution,new RunControl())){assertTrue(viewer.metadata().getBoolean("observingOnly"));JSONObject page=viewer.read(0);assertTrue(page.getBoolean("terminal"));assertEquals("exited",page.getJSONObject("activity").getString("state"));}
        tasks.endAttempt(task,run,"needs_confirmation","Original exit7 remains an observed failure",new JSONObject());JSONObject next=tasks.startAttempt(task,"capabilities",120);assertNotEquals(run,next.getString("runId"));
        try(var old=host.ownerTerminals().openObservation(task,execution,new RunControl())){assertEquals(run,old.metadata().getString("runId"));assertEquals(epoch,old.metadata().getInt("epoch"));assertTrue(old.read(0).getBoolean("terminal"));assertThrows(SecurityException.class,old::stop);}
        String another=tasks.createCapabilityTask("Different private task","").getString("id");assertThrows(SecurityException.class,()->host.ownerTerminals().openObservation(another,execution,new RunControl()));assertEquals(1,dispatchedExecutions());
    }
    @Test public void sameExecutionMovesToBackgroundAndReadOnlyViewCannotInputStopOrReplay()throws Exception{
        JSONObject launch=tool("project_execute",reviewed("require('fs').mkdirSync('dist',{recursive:true});require('fs').writeFileSync('dist/index.html','<h1>ORIGINAL-PRIVATE-RESULT</h1>');process.stdout.write('ORIGINAL-READY');setInterval(()=>{},1000)",1000,10000));assertEquals(launch.toString(),"project_running",launch.getString("code"));String execution=launch.getString("operationId");assertEquals(1,dispatchedExecutions());
        var observation=new RunControl();var viewer=host.ownerTerminals().openObservation(task,execution,observation);JSONObject page=viewer.read(0);StringBuilder bytes=new StringBuilder();for(int i=0;i<page.getJSONArray("chunks").length();i++)bytes.append(new String(Base64.getDecoder().decode(page.getJSONArray("chunks").getJSONObject(i).getString("dataBase64")),StandardCharsets.UTF_8));assertTrue(bytes.toString(),bytes.toString().contains("ORIGINAL-READY"));
        assertThrows(SecurityException.class,()->viewer.input(new JSONObject().put("action","text").put("text","must-not-send\n")));assertThrows(SecurityException.class,viewer::stop);long cursor=page.getLong("nextCursor"),events=eventCount();for(int i=0;i<5;i++)viewer.read(cursor);assertEquals("Monotonic UI activity must not append original task events on each poll",events,eventCount());
        Thread.sleep(2100);page=viewer.read(cursor);assertEquals("inferred_idle",page.getJSONObject("activity").getString("state"));assertEquals("unknown",page.getJSONObject("activity").getString("waitingForInput"));JSONObject window=page.getJSONObject("activity").getJSONObject("observationWindow");assertEquals("background_observation",window.getString("phase"));assertEquals(10000,window.getLong("totalTimeoutMillis"));assertFalse(window.getBoolean("deadlineExtended"));
        viewer.close();assertTrue(observation.isCancelled());assertFalse(original.isCancelled());assertThrows(IllegalStateException.class,()->viewer.read(cursor));
        try(var reopened=host.ownerTerminals().openObservation(task,execution,new RunControl())){assertEquals(execution,reopened.metadata().getString("executionId"));assertFalse(reopened.read(cursor).getBoolean("resourcesReleased"));}
        JSONObject stopped=tool("project_stop",new JSONObject().put("executionId",execution));assertEquals(stopped.toString(),"project_stop_confirmed",stopped.getString("code"));assertTrue(stopped.getBoolean("resourcesReleased"));assertEquals(1,dispatchedExecutions());assertEquals(run,tasks.get(task,false).getString("runId"));assertFalse(original.isCancelled());
        String source=ProjectSources.describe(local.handle("export",new JSONObject().put("checkoutId",checkout))).getString("sourceDigest");JSONObject frozen=tool("project_plan",new JSONObject().put("intent","freeze_package").put("packageMode","web_output").put("entryPath","dist/index.html").put("expectedSourceDigest",source));
        JSONObject save=new JSONObject().put("planRef",frozen.getString("planRef")).put("planDigest",frozen.getString("planDigest")).put("stepId","freeze_package"),review=tool("project_execute",save);assertEquals("approval_required",review.getString("code"));tasks.decideCapabilityApproval(task,review.getString("approvalId"),true);JSONObject saved=tool("project_execute",save);assertTrue(saved.toString(),saved.getBoolean("artifactReady"));assertFalse(saved.getBoolean("automaticallyExported"));
        JSONObject artifact=tool("project_package",new JSONObject().put("executionId",saved.getString("operationId"))),packageEvidence=tasks.projectPackageEvidence(task,run,epoch,original,"project_package",saved.getString("operationId"));assertTrue(artifact.getLong("archiveBytes")>0);assertEquals(task,artifact.getString("taskId"));assertEquals(run,packageEvidence.getString("runId"));assertEquals(saved.getString("operationId"),artifact.getString("operationId"));assertNotEquals(execution,artifact.getString("operationId"));assertFalse(artifact.getBoolean("businessVerified"));
    }
    @Test public void originalCancelDuringForegroundWaitDrainsOnlyTheOriginalExecution()throws Exception{
        JSONObject args=reviewed("setInterval(()=>{},1000)",3000,6000);var worker=Executors.newSingleThreadExecutor();Future<JSONObject> waiting=worker.submit(()->tool("project_execute",args));String execution=null;
        try{
            long until=System.nanoTime()+3_000_000_000L;while(execution==null&&System.nanoTime()<until){JSONArray rows=tasks.get(task,false).getJSONArray("operations");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if("project_running".equals(row.optString("state")))execution=row.getString("operationId");}if(execution==null)Thread.sleep(20);}
            assertNotNull("Original native start must be observed before the cancel case",execution);assertFalse(waiting.isDone());original.cancel();assertThrows(ExecutionException.class,()->waiting.get(3,TimeUnit.SECONDS));
            port.close();assertEquals(1,dispatchedExecutions());assertNotNull(journal.projectServiceExitEvidence(new JSONArray().put(execution)));assertFalse(tasks.hasUnresolved(task));
            try(var viewer=host.ownerTerminals().openObservation(task,execution,new RunControl())){assertTrue(viewer.read(0).getBoolean("resourcesReleased"));assertTrue(viewer.metadata().getBoolean("observingOnly"));assertThrows(SecurityException.class,viewer::stop);}
        }finally{original.cancel();worker.shutdownNow();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));}
    }
    @Test public void oldExecutionBeyondRecentWindowReopensFromLimitedOriginalAuditMetadata()throws Exception{
        JSONObject ended=tool("project_execute",reviewed("process.stdout.write('ORIGINAL-HISTORICAL-LOG',()=>process.exit(7))",1500,5000));
        assertEquals(ended.toString(),"failed",ended.getString("status"));assertEquals(7,ended.getInt("exitCode"));String execution=ended.getString("operationId");
        tasks.endAttempt(task,run,"needs_confirmation","Keep the exact failed execution",new JSONObject());JSONObject next=tasks.startAttempt(task,"capabilities",120);assertNotEquals(run,next.getString("runId"));
        RunControl nextControl=new RunControl();nextControl.bindHostRun(task,next.getString("runId"),next.getInt("coordinatorEpoch"));this.<AtomicReference<RunControl>>field("live").set(nextControl);
        var database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)database.get(tasks);
        // Only pagination fixtures. They carry no dispatch/approval/runtime binding or process proof.
        for(int i=0;i<26;i++){android.content.ContentValues row=new android.content.ContentValues();row.put("operation_id",java.util.UUID.randomUUID().toString());row.put("task_id",task);row.put("run_id",next.getString("runId"));row.put("tool","audit_fixture");row.put("arguments",new JSONObject().put("fixture","history pagination only").toString());row.put("state","receipt");row.put("created_at",System.currentTimeMillis()+i);db.insertOrThrow("operations",null,row);}
        JSONArray recent=tasks.getForUi(task,false).getJSONArray("operations");assertEquals(24,recent.length());for(int i=0;i<recent.length();i++)assertNotEquals(execution,recent.getJSONObject(i).getString("operationId"));
        int before=dispatchedExecutions();long rows;try(Cursor count=db.rawQuery("SELECT COUNT(*) FROM operations WHERE task_id=?",new String[]{task})){assertTrue(count.moveToFirst());rows=count.getLong(0);}
        String cursor="";JSONObject found=null;int pages=0;
        do{JSONObject page=tasks.taskHistoryPage(task,"operations",cursor,24,1024);assertFalse(page.getBoolean("executionAuthorized"));pages++;JSONArray entries=page.getJSONArray("entries");for(int i=0;i<entries.length();i++)if(execution.equals(entries.getJSONObject(i).optString("operationId")))found=entries.getJSONObject(i);cursor=page.getString("nextCursor");}while(found==null&&!cursor.isEmpty());
        assertNotNull(found);assertTrue(pages>1);assertEquals("read_limited",found.getString("status"));assertFalse(found.has("receipt"));assertEquals(run,found.getString("runId"));assertEquals("project_execute",found.getString("tool"));
        RunControl observation=new RunControl();try(var viewer=host.ownerTerminals().openObservation(task,found.getString("operationId"),observation)){
            assertEquals(run,viewer.metadata().getString("runId"));assertEquals(epoch,viewer.metadata().getInt("epoch"));assertTrue(viewer.metadata().getBoolean("observingOnly"));JSONObject page=viewer.read(0);StringBuilder output=new StringBuilder();JSONArray chunks=page.getJSONArray("chunks");for(int i=0;i<chunks.length();i++)output.append(new String(Base64.getDecoder().decode(chunks.getJSONObject(i).getString("dataBase64")),StandardCharsets.UTF_8));assertTrue(output.toString().contains("ORIGINAL-HISTORICAL-LOG"));assertTrue(page.getBoolean("terminal"));assertThrows(SecurityException.class,viewer::stop);assertThrows(SecurityException.class,()->viewer.input(new JSONObject().put("action","text").put("text","not-sent")));
        }
        assertTrue(observation.isCancelled());assertFalse(original.isCancelled());assertFalse(nextControl.isCancelled());assertEquals(before,dispatchedExecutions());assertEquals(next.getString("runId"),tasks.get(task,false).getString("runId"));assertEquals(next.getInt("coordinatorEpoch"),tasks.get(task,false).getInt("coordinatorEpoch"));
        try(Cursor count=db.rawQuery("SELECT COUNT(*) FROM operations WHERE task_id=?",new String[]{task})){assertTrue(count.moveToFirst());assertEquals(rows,count.getLong(0));}
    }
    private static void excerpt(JSONObject result,String key,String exact)throws Exception{
        String text=result.getString(key);assertTrue(text.startsWith(key.equals("stdout")?"OUT-HEAD":"ERR-HEAD"));assertTrue(text.endsWith(key.equals("stdout")?"OUT-TAIL":"ERR-TAIL"));JSONObject omission=result.getJSONObject("displayOmissions").getJSONObject(key);
        assertEquals(exact.getBytes(StandardCharsets.UTF_8).length,omission.getInt("sourceUtf8Bytes"));assertEquals(omission.getInt("sourceUtf8Bytes")-omission.getInt("headUtf8Bytes")-omission.getInt("tailUtf8Bytes"),omission.getInt("omittedUtf8Bytes"));assertTrue(omission.getInt("omittedUtf8Bytes")>0);assertFalse(omission.getBoolean("nativeOutputChanged"));
    }
    @Test public void actualLongUnicodeFailureKeepsBothEndsOnModelWireAndExactOriginalLogPages()throws Exception{
        String stdout="OUT-HEAD"+"界".repeat(8000)+"OUT-TAIL",stderr="ERR-HEAD"+"🙂".repeat(7000)+"ERR-TAIL";
        JSONObject ended=tool("project_execute",reviewed("process.stdout.write('OUT-HEAD'+('界'.repeat(8000))+'OUT-TAIL',()=>process.stderr.write('ERR-HEAD'+('🙂'.repeat(7000))+'ERR-TAIL',()=>process.exit(7)))",1500,5000));
        assertEquals(ended.toString(),"failed",ended.getString("status"));assertEquals(7,ended.getInt("exitCode"));assertTrue(ended.getBoolean("resourcesReleased"));assertTrue(ended.getBoolean("displayTruncated"));assertFalse(ended.getBoolean("outputTruncated"));assertFalse(ended.getBoolean("summaryOutputTruncated"));assertTrue(ended.toString().getBytes(StandardCharsets.UTF_8).length<=30000);excerpt(ended,"stdout",stdout);excerpt(ended,"stderr",stderr);String execution=ended.getString("operationId");
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(),err=new java.io.ByteArrayOutputStream();try(var viewer=host.ownerTerminals().openObservation(task,execution,new RunControl())){long cursor=0;JSONObject page;do{page=viewer.read(cursor);JSONArray chunks=page.getJSONArray("chunks");for(int i=0;i<chunks.length();i++){JSONObject chunk=chunks.getJSONObject(i);(chunk.getInt("stream")==1?out:err).write(Base64.getDecoder().decode(chunk.getString("dataBase64")));}cursor=page.getLong("nextCursor");}while(page.getBoolean("hasMore"));}assertEquals(stdout,out.toString(StandardCharsets.UTF_8.name()));assertEquals(stderr,err.toString(StandardCharsets.UTF_8.name()));
        var server=dev.pocketengine.testutil.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);var requests=new java.util.concurrent.CopyOnWriteArrayList<JSONObject>();
        server.createContext("/",exchange->{try{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);JSONObject message=new JSONObject().put("role","assistant");if(requests.size()==1)message.put("content","").put("tool_calls",new JSONArray().put(new JSONObject().put("id","observe-original-long-output").put("type","function").put("function",new JSONObject().put("name","project_status").put("arguments",new JSONObject().put("executionId",execution).toString()))));else message.put("content","原命令退出7，按同一原执行核对；没有重派。");byte[] body=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",requests.size()==1?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});server.start();
        try{
            ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-original-output-key","fixture-output-model",false,4,20,"openai_chat",1000);new dev.pocketengine.runtime.AgentRunner(null,port).run(task,"核对这个原执行的失败日志",config,original,event->{},journal);
            assertEquals(2,requests.size());JSONObject observed=null;JSONArray messages=requests.get(1).getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);if("tool".equals(message.optString("role"))&&"observe-original-long-output".equals(message.optString("tool_call_id")))observed=new JSONObject(message.getString("content"));}
            assertNotNull(observed);assertEquals(execution,observed.getString("operationId"));assertEquals("failed",observed.getString("status"));assertEquals(7,observed.getInt("exitCode"));assertFalse(observed.getBoolean("replayAllowed"));excerpt(observed,"stdout",stdout);excerpt(observed,"stderr",stderr);assertEquals(1,dispatchedExecutions());assertFalse(original.isCancelled());
        }finally{server.stop(0);}
    }
}
