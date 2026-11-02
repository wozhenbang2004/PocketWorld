package dev.pocketengine.runtime.eval;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.store.TaskStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import static dev.pocketengine.runtime.eval.TaskEvaluation.*;

/** Loopback fixture only. The real SQL/RunControl/Gateway/Runner path is not replaced. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE,application=Application.class) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ProductionTaskEvaluationTest {
 private static final class Fixture implements AutoCloseable {
  final TaskStore tasks;final HttpServer server;final AtomicInteger posts=new AtomicInteger();
  String taskId;volatile JSONArray firstCalls;
  Fixture()throws Exception {Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{int request=posts.incrementAndGet();exchange.getRequestBody().readAllBytes();JSONObject message=new JSONObject().put("role","assistant");if(request==1&&firstCalls!=null)message.put("content",JSONObject.NULL).put("tool_calls",new JSONArray(firstCalls.toString()));else message.put("content","PW-EVAL_SUCCESS material-572");byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).put("usage",new JSONObject().put("prompt_tokens",2).put("completion_tokens",1).put("total_tokens",3)).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();}
  JSONObject run(boolean achieved,Set<String> environment)throws Exception {return run(achieved,environment,Partition.TUNE,"marked-read");}
  JSONObject run(boolean achieved,Set<String> environment,Partition partition,String caseId)throws Exception {List<Case> cases=List.of(new Case("marked-read","marked-family",Partition.TUNE,"PW-EVAL-READ",Expected.VERIFIED_SUCCESS,Set.of("fixture_page"),List.of("material-572")),new Case("held-read","held-family",Partition.HOLDOUT,"PW-EVAL-HELD",Expected.VERIFIED_SUCCESS,Set.of("fixture_page"),List.of("material-572")));Case c=cases.stream().filter(candidate->candidate.id().equals(caseId)).findFirst().orElseThrow();JSONObject task=tasks.create(c.marker()+" 读取测试页，不执行写入");String id=task.getString("id");taskId=id;JSONObject attempt=tasks.startAttempt(id,"main_screen",4);RunControl original=new RunControl();original.bindHostRun(id,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"));PhonePort phone=new PhonePort(){public boolean isConnected(){return true;}public void clearReferences(){}public JSONObject observe(boolean image,RunControl control)throws Exception{control.check();return new JSONObject().put("ok",true).put("observationId",UUID.randomUUID().toString()).put("packageName","dev.pocketengine.eval.fixture").put("text","material-572").put("nodes",new JSONArray());}public JSONObject execute(String tool,JSONObject args,RunControl control){throw new AssertionError("read-only evaluation may not dispatch a device effect");}};
   ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort(),"fixture-only-not-a-real-key","fixture-model",false,4,10);
   return ProductionTaskEvaluation.runPrepared(cases,partition,caseId,environment,tasks,id,config,original,new AgentRunner(phone),saved->{JSONArray events=saved.getJSONArray("events");List<String> evidence=new ArrayList<>();for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);if(event.optString("type").equals("observation")&&event.optJSONObject("receipt")!=null)evidence.add(event.getJSONObject("receipt").optString("text"));}return new CheckedEffects(!evidence.isEmpty(),achieved,true,evidence,"verified");});
  }
  SQLiteDatabase database()throws Exception {var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
  public void close()throws Exception {server.stop(0);database().close();}
 }
 @Test public void productionOriginalTaskHasIndependentReadAndSamePhysicalUsage()throws Exception {try(Fixture f=new Fixture()){JSONObject result=f.run(true,Set.of("fixture_page"));assertTrue(result.toString(),result.getBoolean("passed"));assertEquals("tune",result.getString("partition"));assertEquals("verified_success",result.getString("classification"));assertEquals(1,f.posts.get());assertEquals(1,result.getJSONObject("usage").getInt("attempts"));assertEquals("needs_confirmation",result.getString("productionStatus"));}}
 @Test public void productionModelFalseSuccessIsSeparateFromActualGoalEvidence()throws Exception {try(Fixture f=new Fixture()){JSONObject result=f.run(false,Set.of("fixture_page"));assertEquals("false_success",result.getString("classification"));assertFalse(result.getBoolean("passed"));assertEquals(1,f.posts.get());}}
 @Test public void missingEnvironmentSkipsWithoutAnyModelOrToolDispatch()throws Exception {try(Fixture f=new Fixture()){JSONObject result=f.run(false,Set.of());assertEquals("skipped",result.getString("classification"));assertFalse(result.getBoolean("passed"));assertEquals(0,f.posts.get());}}
 @Test public void holdoutSelectionUsesTheSameOriginalTaskRunnerAndUsage()throws Exception {try(Fixture f=new Fixture()){JSONObject result=f.run(true,Set.of("fixture_page"),Partition.HOLDOUT,"held-read");assertEquals("holdout",result.getString("partition"));assertEquals("held-read",result.getString("caseId"));assertEquals(f.taskId,result.getString("taskId"));assertTrue(result.toString(),result.getBoolean("passed"));assertEquals(1,f.posts.get());assertEquals(1,f.tasks.inferenceUsage(f.taskId).getInt("attempts"));}}
 @Test public void originalSqlOperationMatchesRunnerEventAndTraceWithoutTrustingReceiptIds()throws Exception {
  try(Fixture f=new Fixture()){
   String fake="60000000-0000-0000-0000-000000000006";f.firstCalls=new JSONArray();
   for(String tool:List.of("trace_fixture","trace_read","trace_gated"))f.firstCalls.put(new JSONObject().put("id","provider-call-"+tool).put("type","function").put("function",new JSONObject().put("name",tool).put("arguments","{}")));
   String id=f.tasks.createCapabilityTask("PW-EVAL-TRACE 执行一次已明确授权的本地fixture工具，不涉及真实设备或外部操作","").getString("id");JSONObject attempt=f.tasks.startAttempt(id,"capabilities",4);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl original=new RunControl();original.bindHostRun(id,run,epoch);f.tasks.authorizeTools(id,run,new JSONArray().put("trace_fixture").put("trace_read").put("trace_gated"));
   java.util.concurrent.atomic.AtomicReference<String> dispatchOperation=new java.util.concurrent.atomic.AtomicReference<>();AtomicInteger effects=new AtomicInteger(),reads=new AtomicInteger(),gates=new AtomicInteger();
   CapabilityPort port=new CapabilityPort(){
    public JSONArray tools()throws Exception {JSONArray tools=new JSONArray();for(String name:List.of("trace_fixture","trace_read","trace_gated"))tools.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description","Isolated test fixture; no real external effects").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false))));return tools;}
    public boolean supports(String name){return Set.of("trace_fixture","trace_read","trace_gated").contains(name);}
    public boolean sideEffecting(String name){return Set.of("trace_fixture","trace_gated").contains(name);}
    public JSONObject beforeTool(String name,JSONObject arguments,RunControl control)throws Exception {control.check();if(!"trace_gated".equals(name))return null;gates.incrementAndGet();return new JSONObject().put("ok",false).put("accepted",false).put("code","fixture_not_dispatched").put("nativeOperationId",fake).put("operationId",fake);}
    public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception {control.check();if(name.equals("trace_fixture")){effects.incrementAndGet();dispatchOperation.set(args.getString("_hostOperationId"));}else{assertEquals("trace_read",name);reads.incrementAndGet();assertFalse(args.has("_hostOperationId"));}return new JSONObject().put("ok",true).put("accepted",true).put("code","fixture_receipt").put("operationId",fake).put("nativeOperationId",fake).put("_hostOperationId",fake).put("message","TRACE_FIXTURE_BODY_MUST_NOT_PERSIST");}
   };
   var trace=new dev.pocketengine.runtime.usage.TaskDetailedTrace(java.nio.file.Files.createTempDirectory("native-operation-trace"),true);trace.configure(true,1);var session=trace.start(trace.ticket(),id,run,epoch);List<JSONObject> events=new ArrayList<>();SQLiteDatabase db=f.database();
   EventSink persisted=event->{try{
    if(event.has("nativeOperationId")){assertFalse("The native event must follow the original SQL transaction",db.inTransaction());try(var row=db.rawQuery("SELECT state FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{id,run,event.getString("nativeOperationId")})){assertTrue("The native event must follow the original SQL receipt commit",row.moveToFirst());assertEquals("receipt",row.getString(0));}}
    f.tasks.event(id,run,epoch,event);events.add(new JSONObject(event.toString()));
   }catch(Exception failure){throw new IllegalStateException("Original trace fixture event did not persist",failure);}};
   ModelConfig config=new ModelConfig("http://127.0.0.1:"+f.server.getAddress().getPort(),"fixture-only-not-a-real-key","fixture-model",false,4,10);
   RunOutcome outcome;try{outcome=new AgentRunner(null,port,selected->null).run(id,f.tasks.get(id,false).getString("goal"),config,original,session.events(persisted),f.tasks.journal(id,run,epoch,original));f.tasks.endAttempt(id,run,outcome.status,outcome.summary,outcome.evidence);}finally{session.close();}
   assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(1,effects.get());assertEquals(1,reads.get());assertEquals(1,gates.get());assertEquals(2,f.posts.get());String operation;
   try(var row=db.rawQuery("SELECT operation_id,tool,state FROM operations WHERE task_id=? AND run_id=?",new String[]{id,run})){assertTrue(row.moveToFirst());operation=row.getString(0);assertEquals("trace_fixture",row.getString(1));assertEquals("receipt",row.getString(2));assertFalse(row.moveToNext());}
   assertEquals(operation,dispatchOperation.get());assertNotEquals(fake,operation);boolean nativeEvent=false,readEvent=false,notDispatched=false;
   for(JSONObject event:events)if(event.optString("type").equals("tool")){
    if(event.optString("tool").equals("trace_fixture")&&event.optString("status").equals("completed")){nativeEvent=true;assertEquals(operation,event.getString("nativeOperationId"));assertEquals(fake,event.getJSONObject("receipt").getString("nativeOperationId"));}
    if(event.optString("tool").equals("trace_read")&&event.optString("status").equals("completed")){readEvent=true;assertFalse(event.has("nativeOperationId"));}
    if(event.optString("tool").equals("trace_gated")&&event.optString("status").equals("failed")){notDispatched=true;assertFalse(event.has("nativeOperationId"));assertEquals(false,event.getJSONObject("receipt").getBoolean("accepted"));assertEquals(fake,event.getJSONObject("receipt").getString("nativeOperationId"));}
   }
   assertTrue(nativeEvent);assertTrue(readEvent);assertTrue(notDispatched);JSONObject archive=trace.read(run);assertEquals(id,archive.getString("taskId"));assertEquals(run,archive.getString("runId"));assertEquals(epoch,archive.getInt("epoch"));boolean nativeSpan=false,readSpan=false,notDispatchedSpan=false;JSONArray spans=archive.getJSONArray("spans");
   for(int i=0;i<spans.length();i++){JSONObject span=spans.getJSONObject(i);if(span.getString("name").equals("tool.trace_fixture")){nativeSpan=true;assertEquals(operation,span.getString("correlation"));}if(span.getString("name").equals("tool.trace_read")){readSpan=true;assertEquals("",span.getString("correlation"));}if(span.getString("name").equals("tool.trace_gated")){notDispatchedSpan=true;assertEquals("",span.getString("correlation"));}}
   assertTrue(nativeSpan);assertTrue(readSpan);assertTrue(notDispatchedSpan);assertFalse(archive.toString().contains(fake));assertFalse(archive.toString().contains("provider-call-"));assertFalse(archive.toString().contains("TRACE_FIXTURE_BODY_MUST_NOT_PERSIST"));
  }
 }
 @Test public void unselectedTuneCaseCannotDispatchFromTheHoldoutEntry()throws Exception {try(Fixture f=new Fixture()){IllegalArgumentException rejected=assertThrows(IllegalArgumentException.class,()->f.run(true,Set.of("fixture_page"),Partition.HOLDOUT,"marked-read"));assertEquals("evaluation_case_not_in_selected_partition",rejected.getMessage());assertEquals(0,f.posts.get());assertEquals(0,f.tasks.inferenceUsage(f.taskId).getInt("attempts"));assertEquals(0,f.tasks.get(f.taskId,false).getJSONArray("operations").length());}}
}
