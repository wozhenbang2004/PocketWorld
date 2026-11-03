package dev.pocketengine.features.evaluation;

import static org.junit.Assert.*;
import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;

/** Actual SQL, Runner, Gateway HTTP, synthetic file effects and independent probes.
 * The loopback provider is deliberately synthetic, not a real-model quality benchmark. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public final class EvaluationNativeJourneyTest {
    private TaskStore tasks;private Path root;private HttpServer server;private ModelConfig selected;
    private NativeEvaluationHost host;private NativeEvaluationHost.Page page;private JSONObject study,lastTask;
    private final AtomicInteger requests=new AtomicInteger();private RunControl retained;
    private NativeEvaluationHost.Completion delayed;private String behavior="success";private boolean queued,rejectQueue;
    // Android35's declared java.base omits Files.readString/writeString. Keep their
    // strict UTF8 behavior and ordinary CREATE/TRUNCATE write semantics in this fixture.
    private static String readUtf8File(Path path)throws java.io.IOException{
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
    }
    private static void writeUtf8File(Path path,String text)throws java.io.IOException{
        ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
        byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);Files.write(path,bytes);
    }
    @Before public void open()throws Exception{
        Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);root=Files.createTempDirectory("native-evaluation");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{
            try{requests.incrementAndGet();JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                JSONObject response=respond(request);byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }finally{exchange.close();}});server.start();
        String url="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";selected=new ModelConfig(url,"synthetic-key","evaluation-fixture",false,6,10,"openai_chat",512).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",url));
        host=new NativeEvaluationHost(tasks,root,new NativeEvaluationHost.Models(){public ModelConfig selected(){return selected;}public ModelConfig current(ModelConfig captured){return selected;}},(task,model,control,completion)->{
            retained=control;lastTask=tasks.get(task,false);if(rejectQueue)throw new IllegalStateException("native queue unavailable");if(queued){delayed=completion;return;}
            Exception failure=null;try{
                tasks.warmToolOutputSources(task,control);JSONObject attempt=tasks.requireCapabilityAttempt(task,control);CapabilityPort port=host.capability(task,control);
                ContextBudgetPolicy.Source budget=c->ContextBudgetPolicy.RouteBudget.forRoute(c,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis());
                RunJournal journal=tasks.journal(task,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),control);
                RunOutcome result=new AgentRunner(null,port,budget).run(task,attempt.getString("goal"),model,control,e->tasks.event(task,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),e),journal);
                tasks.finishCapabilityAttempt(task,attempt.getString("runId"),attempt.getInt("coordinatorEpoch"),control,result);
            }catch(Exception unavailable){failure=unavailable;tasks.endAttempt(task,lastTask.getString("runId"),control.isCancelled()?"cancelled":"failed","fixture execution unavailable",new JSONObject());}
            finally{completion.finished(failure);}
        });page=host.openPage();study=page.command("create",new JSONObject()).getJSONObject("study");
    }
    @After public void close()throws Exception{
        if(page!=null)page.close();if(server!=null)server.stop(0);if(tasks!=null)database().close();
    }
    private SQLiteDatabase database()throws Exception{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(tasks);}
    private JSONObject review(String caseId,String strategy)throws Exception{return page.command("review",new JSONObject().put("studyId",study.getString("id")).put("caseId",caseId).put("strategy",strategy));}
    private JSONObject start(JSONObject review)throws Exception{return page.command("start",new JSONObject().put("reviewId",review.getString("reviewId")).put("confirmed",true));}
    private JSONObject trial(String caseId,String strategy)throws Exception{JSONObject result=start(review(caseId,strategy));study=result.getJSONObject("study");return tasks.get(result.getString("startedTaskId"),false);}
    private JSONObject probe(JSONObject task)throws Exception{return task.getJSONObject("evaluation").getJSONObject("probe");}
    private JSONObject respond(JSONObject request)throws Exception{
        JSONArray tools=request.optJSONArray("tools");for(int i=0;tools!=null&&i<tools.length();i++){String name=tools.getJSONObject(i).getJSONObject("function").getString("name");assertTrue("unexpected real-app tool: "+name,name.startsWith("evaluation_")||name.startsWith("run_"));}
        JSONArray messages=request.getJSONArray("messages");int count=0;JSONObject source=null;
        for(int i=0;i<messages.length();i++){JSONObject message=messages.getJSONObject(i);if("tool".equals(message.optString("role"))){count++;JSONObject value=new JSONObject(message.getString("content"));if(value.has("sourceSha256"))source=value;}}
        JSONObject message=new JSONObject().put("role","assistant");
        if("handover".equals(behavior)&&count==0)return response(message.put("tool_calls",call("run_ask_user",new JSONObject().put("question","请核对合成任务前提"))));
        if(count==0)return response(message.put("tool_calls",call(SyntheticEvaluationPort.READ,new JSONObject())));
        if(count==1){
            if("source-change".equals(behavior))writeUtf8File(SyntheticEvaluationPort.directory(root,lastTask.getString("id")).resolve("source.json"),"{\"changed\":true}");
            if("unknown-write".equals(behavior))writeUtf8File(SyntheticEvaluationPort.directory(root,lastTask.getString("id")).resolve("result.json.pending"),"uncertain original write");
            assertNotNull("actual source tool output must reach provider",source);JSONObject expected=EvaluationDataset.expected(source.getString("sourceId"));
            JSONObject args=new JSONObject().put("sourceId",source.getString("sourceId")).put("sourceRevision",source.getLong("sourceRevision")).put("sourceSha256",source.getString("sourceSha256"))
                    .put("count",expected.getInt("count")).put("totalCents",behavior.startsWith("wrong")?expected.getLong("totalCents")+1:expected.getLong("totalCents"));
            return response(message.put("tool_calls",call(SyntheticEvaluationPort.WRITE,args)));
        }
        if(count==2)return response(message.put("tool_calls",call(SyntheticEvaluationPort.REPORT,new JSONObject().put("completed",!"wrong-honest".equals(behavior)))));
        return response(message.put("content","Synthetic trial finished; independent native probe decides actual success."));
    }
    private JSONArray call(String tool,JSONObject args)throws Exception{return new JSONArray().put(new JSONObject().put("id","call-"+requests.get()).put("type","function").put("function",new JSONObject().put("name",tool).put("arguments",args.toString())));}
    private JSONObject response(JSONObject message)throws Exception{return new JSONObject().put("id","response-"+requests.get()).put("model","evaluation-fixture").put("choices",new JSONArray().put(new JSONObject().put("finish_reason",message.has("tool_calls")?"tool_calls":"stop").put("message",message))).put("usage",new JSONObject().put("prompt_tokens",100).put("completion_tokens",20).put("total_tokens",120));}

    @Test public void ownerTrialTraversesOriginalRunnerSqlHttpAndIndependentFileProbe()throws Exception{
        JSONObject review=review("tune-a","baseline"),started=start(review),task=tasks.get(started.getString("startedTaskId"),false),probe=probe(task);
        assertEquals("success",probe.getString("classification"));assertTrue(probe.getBoolean("grounded"));assertTrue(probe.getBoolean("effectMatches"));assertEquals(4,requests.get());
        assertEquals(4,task.getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals(2,task.getJSONArray("operations").length());
        assertEquals(task.getString("runId"),probe.getString("runId"));assertEquals(task.getInt("coordinatorEpoch"),probe.getInt("epoch"));assertFalse(probe.getBoolean("modelSelfReportIsProof"));
        assertTrue(start(review).getBoolean("knownEvaluation"));assertEquals("duplicate confirmation cannot replay",4,requests.get());
        page.command("cancel",new JSONObject().put("taskId",task.getString("id")));assertEquals("success",probe(tasks.get(task.getString("id"),false)).getString("classification"));assertFalse(tasks.get(task.getString("id"),false).getBoolean("cancelRequested"));
        assertThrows(SecurityException.class,()->tasks.startAttempt(task.getString("id"),"capabilities",6));
    }
    @Test public void modelClaimIsScoredSeparatelyFromFalseSuccessAndHonestFailure()throws Exception{
        behavior="wrong-claim";JSONObject falseSuccess=trial("tune-a","baseline");assertEquals("false_success",probe(falseSuccess).getString("classification"));assertFalse(probe(falseSuccess).getBoolean("effectMatches"));
        Path file=SyntheticEvaluationPort.directory(root,falseSuccess.getString("id")).resolve("result.json");JSONObject changed=new JSONObject(readUtf8File(file)).put("totalCents",EvaluationDataset.expected("tune-a").getLong("totalCents"));writeUtf8File(file,changed.toString());
        page.command("probe",new JSONObject().put("taskId",falseSuccess.getString("id")));JSONObject altered=probe(tasks.get(falseSuccess.getString("id"),false));assertTrue(altered.getBoolean("effectMatches"));assertEquals("harness_error",altered.getString("classification"));assertFalse(altered.getBoolean("passed"));
        behavior="wrong-honest";JSONObject honest=trial("tune-a","verify-first");assertEquals("actual_failure",probe(honest).getString("classification"));assertFalse(probe(honest).getBoolean("reportedComplete"));
    }
    @Test public void changedSourceRevokesPreparedModelResponseAndCannotBeRegradedAsSuccess()throws Exception{
        behavior="source-change";JSONObject task=trial("tune-b","baseline");assertEquals("source_changed",probe(task).getString("classification"));assertFalse(probe(task).getBoolean("passed"));
        assertFalse(Files.exists(SyntheticEvaluationPort.directory(root,task.getString("id")).resolve("result.json")));assertTrue(requests.get()<=2);
    }
    @Test public void uncertainWriteStopsWithoutReportOrReplayAndRemainsUnknownOnReadback()throws Exception{
        behavior="unknown-write";JSONObject task=trial("tune-a","baseline");assertEquals("unknown_effect",probe(task).getString("classification"));assertTrue(tasks.hasUnresolved(task.getString("id")));assertEquals(2,requests.get());
        page.command("probe",new JSONObject().put("taskId",task.getString("id")));assertEquals(2,requests.get());assertEquals("unknown_effect",probe(tasks.get(task.getString("id"),false)).getString("classification"));
        behavior="success";JSONObject written=trial("tune-b","baseline");String id=written.getString("id"),operation=written.getJSONArray("operations").getJSONObject(0).getString("operationId");
        tasks.journal(id,written.getString("runId"),written.getInt("coordinatorEpoch"),retained).finishOperation(operation,new JSONObject().put("ok",false).put("accepted",JSONObject.NULL).put("status","unknown").put("code","evaluation_write_unknown"));
        // Persist a contradictory legacy/readback row label: the body must remain authoritative.
        android.content.ContentValues row=new android.content.ContentValues();row.put("state","receipt");assertEquals(1,database().update("operations",row,"task_id=? AND operation_id=?",new String[]{id,operation}));
        assertTrue(Files.exists(SyntheticEvaluationPort.directory(root,id).resolve("result.json")));page.command("probe",new JSONObject().put("taskId",id));JSONObject ambiguous=probe(tasks.get(id,false));assertTrue(ambiguous.getBoolean("effectMatches"));assertEquals("unknown_effect",ambiguous.getString("classification"));assertFalse(ambiguous.getBoolean("passed"));assertEquals(6,requests.get());
    }
    @Test public void originalCancellationFencesQueuedWorkerAndLateCallback()throws Exception{
        queued=true;JSONObject started=start(review("tune-a","baseline"));String id=started.getString("startedTaskId");page.command("cancel",new JSONObject().put("taskId",id));assertTrue(retained.isCancelled());
        assertThrows(Exception.class,()->host.capability(id,retained));delayed.finished(new InterruptedException("cancelled"));JSONObject task=tasks.get(id,false);assertEquals("cancelled",probe(task).getString("classification"));assertEquals(0,requests.get());
        JSONObject old=new JSONObject(probe(task).toString()).put("epoch",task.getInt("coordinatorEpoch")+1);assertThrows(SecurityException.class,()->tasks.recordEvaluationProbe(id,task.getString("runId"),task.getInt("coordinatorEpoch"),old));
    }
    @Test public void closedOwnerReviewAndChangedModelCannotLaunchPaidRequest()throws Exception{
        JSONObject reviewed=review("tune-a","baseline");selected=selected.withModel("other-model");assertThrows(SecurityException.class,()->start(reviewed));assertEquals(0,requests.get());
        page.close();assertThrows(SecurityException.class,()->start(reviewed));assertEquals(0,tasks.evaluationTasksForOwner(study.getString("id")).length());
    }
    @Test public void tuneAndHoldoutAreBoundedAndFrozenWithoutHoldoutFeedback()throws Exception{
        assertThrows(SecurityException.class,()->review("holdout-a","baseline"));
        for(String id:EvaluationDataset.TUNE)for(String strategy:EvaluationDataset.STRATEGIES)trial(id,strategy);
        JSONObject before=host.snapshot(study.getString("id")).getJSONObject("tuning");assertEquals(4,before.getInt("completed"));
        JSONObject frozen=page.command("freeze",new JSONObject().put("studyId",study.getString("id")).put("revision",study.getLong("revision")).put("strategy","baseline"));study=frozen.getJSONObject("study");
        assertThrows(SecurityException.class,()->review("tune-a","verify-first"));assertThrows(SecurityException.class,()->review("holdout-a","verify-first"));
        behavior="wrong-claim";trial("holdout-a","baseline");trial("holdout-b","baseline");
        JSONObject after=host.snapshot(study.getString("id")).getJSONObject("tuning");assertEquals(before.toString(),after.toString());assertFalse(after.getBoolean("holdoutUsedForTuning"));assertEquals(6,tasks.evaluationTasksForOwner(study.getString("id")).length());assertEquals(24,requests.get());
    }
    @Test public void originalQuestionIsHandoverAndUnavailableQueueIsSkipped()throws Exception{
        behavior="handover";JSONObject task=trial("tune-a","baseline");assertEquals("handover",probe(task).getString("classification"));
        rejectQueue=true;int before=requests.get();assertThrows(IllegalStateException.class,()->trial("tune-b","baseline"));JSONArray rows=tasks.evaluationTasksForOwner(study.getString("id"));JSONObject skipped=rows.getJSONObject(rows.length()-1);assertEquals("skipped",probe(skipped).getString("classification"));assertEquals(before,requests.get());
        page.command("probe",new JSONObject().put("taskId",skipped.getString("id")));assertEquals("skipped",probe(tasks.get(skipped.getString("id"),false)).getString("classification"));assertEquals(before,requests.get());
    }
    @Test public void sameVersionRegressionComparisonPreservesSourceAndOriginalTaskArchive()throws Exception{
        JSONObject first=trial("tune-a","baseline");String originalStudy=study.getString("id");study=page.command("create",new JSONObject()).getJSONObject("study");behavior="wrong-claim";trial("tune-a","baseline");
        JSONObject diff=page.command("compare",new JSONObject().put("beforeStudyId",originalStudy).put("afterStudyId",study.getString("id")));assertEquals(1,diff.getInt("matchedCases"));assertTrue(diff.getJSONArray("changes").getJSONObject(0).getBoolean("regression"));assertFalse(diff.getBoolean("tuningFeedback"));
        assertEquals("success",probe(tasks.get(first.getString("id"),false)).getString("classification"));
    }
    @Test public void syntheticPortRejectsForgedOperationAndRealAppTools()throws Exception{
        queued=true;JSONObject started=start(review("tune-a","baseline"));String id=started.getString("startedTaskId");CapabilityPort port=host.capability(id,retained);
        assertFalse(port.supports("phone_click_node"));assertThrows(SecurityException.class,()->port.execute("phone_click_node",new JSONObject(),retained));
        JSONObject args=new JSONObject().put("completed",true).put("_hostOperationId",java.util.UUID.randomUUID().toString());assertThrows(SecurityException.class,()->port.execute(SyntheticEvaluationPort.REPORT,args,retained));assertEquals(0,tasks.get(id,false).getJSONArray("operations").length());
        JSONObject wrong=new JSONObject().put("sourceId","other-source").put("sourceRevision",1).put("sourceSha256","0".repeat(64)).put("count",0).put("totalCents",0);var journal=tasks.journal(id,lastTask.getString("runId"),lastTask.getInt("coordinatorEpoch"),retained);String operation=journal.beginOperation(SyntheticEvaluationPort.WRITE,wrong);JSONObject refused=port.execute(SyntheticEvaluationPort.WRITE,new JSONObject(wrong.toString()).put("_hostOperationId",operation),retained);assertFalse(refused.getBoolean("accepted"));journal.finishOperation(operation,refused);port.close();delayed.finished(null);assertFalse(probe(tasks.get(id,false)).getBoolean("unknownOperation"));assertEquals("actual_failure",probe(tasks.get(id,false)).getString("classification"));
    }

    @Test public void nativeControllerUsesActualApplicationWorkerAndArchivesAfterPageLeaves()throws Exception{
        EngineApplication app=new EngineApplication();var attach=Application.class.getDeclaredMethod("attach",android.content.Context.class);attach.setAccessible(true);attach.invoke(app,RuntimeEnvironment.getApplication());
        app.tasks=tasks;app.settings=new dev.pocketengine.store.SettingsStore(app);app.features=new dev.pocketengine.features.FeatureHost(event->{},tasks,()->false);
        app.settings.save(new JSONObject().put("baseUrl",selected.baseUrl).put("provider",selected.provider).put("apiKey","").put("model",selected.model).put("sendScreenshots",false).put("maxSteps",6).put("maxOutputTokens",512).put("requestTimeoutSeconds",10).put("cleartextConsent",selected.cleartextConsent));
        dev.pocketengine.ui.PocketWorldController controller=new dev.pocketengine.ui.PocketWorldController(new dev.pocketengine.ui.ComposeActivity(),app);
        try{
            controller.openNativeEvaluation();JSONObject created=controllerCommand(controller,"create",new JSONObject());String studyId=created.getJSONObject("study").getString("id");
            JSONObject reviewed=controllerCommand(controller,"review",new JSONObject().put("studyId",studyId).put("caseId","tune-a").put("strategy","baseline"));
            JSONObject admitted=controllerCommand(controller,"start",new JSONObject().put("reviewId",reviewed.getString("reviewId")).put("confirmed",true));String id=admitted.getString("startedTaskId");controller.closeNativeEvaluation();
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);JSONObject actual=tasks.get(id,false);
            while(actual.getJSONObject("evaluation").optJSONObject("probe")==null&&System.nanoTime()<end){Thread.sleep(20);actual=tasks.get(id,false);}
            assertEquals("actual App executor and completion callback", "success",probe(actual).getString("classification"));assertEquals(4,requests.get());assertEquals(4,actual.getJSONObject("executionScope").getInt("modelCallsUsed"));assertEquals("exact_main",actual.getJSONObject("evaluation").getJSONObject("model").getString("executionRoute"));
            controller.openNativeEvaluation();JSONObject reopened=controllerCommand(controller,"snapshot",new JSONObject().put("studyId",studyId));assertEquals(id,reopened.getJSONArray("tasks").getJSONObject(0).getString("taskId"));assertFalse(reopened.getJSONArray("tasks").getJSONObject(0).getBoolean("running"));
        }finally{controller.detach();app.onTerminate();}
    }
    private JSONObject controllerCommand(dev.pocketengine.ui.PocketWorldController controller,String name,JSONObject input)throws Exception{
        java.util.concurrent.atomic.AtomicReference<JSONObject> result=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.atomic.AtomicReference<String> failure=new java.util.concurrent.atomic.AtomicReference<>();
        kotlin.jvm.functions.Function2<JSONObject,String,kotlin.Unit> done=(value,error)->{result.set(value);failure.set(error);return kotlin.Unit.INSTANCE;};
        var command=java.util.Arrays.stream(controller.getClass().getDeclaredMethods()).filter(method->method.getName().startsWith("evaluationCommand")).findFirst().orElseThrow();command.invoke(controller,name,input,done);
        var field=controller.getClass().getDeclaredField("io");field.setAccessible(true);((java.util.concurrent.ExecutorService)field.get(controller)).submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(failure.get());assertNotNull("native Controller callback",result.get());return result.get();
    }
}
