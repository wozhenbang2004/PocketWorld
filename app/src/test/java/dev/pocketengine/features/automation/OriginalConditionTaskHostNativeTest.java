package dev.pocketengine.features.automation;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.browser.ConditionPublicReadFixture;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.AutomationPlanHost;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
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
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original TaskStore/AgentRunner/physical local model HTTP + original public-body parser.
 * GET bytes are a typed native fixture; no real public fetch or phone execution is claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OriginalConditionTaskHostNativeTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final String SOURCE="https://public.example/price";
    private static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();final TaskStore tasks;final SQLiteDatabase db;final AutomationPlanHost plans;final AutomationModule module;final AutomationBook book;final OriginalConditionTaskHost nativeHost;
        final long now=System.currentTimeMillis();final JSONObject rule;JSONObject occurrence,request;volatile JSONObject nativeReceipt;String id,run,planId;int epoch,reads,admissions,starts;RunControl original;boolean losePlanAck;
        Fixture()throws Exception{
            app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);var f=TaskStore.class.getDeclaredField("db");f.setAccessible(true);db=(SQLiteDatabase)f.get(tasks);plans=new AutomationPlanHost(tasks);
            module=new AutomationModule(new FeatureContext(app,new SettingsStore(app),new SecretVault(app),event->{}),plans::resolveEvidence,plans);book=new AutomationBook(new File(app.getFilesDir(),"automation/rules.json"),plans);
            rule=book.save(new JSONObject().put("title","固定价格条件").put("kind","reminder").put("schedule",new JSONObject().put("type","once").put("at",now-1)).put("semanticCondition",new JSONObject().put("goal","价格低于10").put("sourceKey",SOURCE).put("sourceRevision",1)),now-2);
            book.approveSemanticReadForOwner(rule.getString("id"),rule.getLong("revision"),true,now-2);
            nativeHost=new OriginalConditionTaskHost(tasks,module,(task,current)->{starts++;original=new RunControl();JSONObject attempt=tasks.startAutomationConditionAttempt(task,current,original);id=task;run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");original.setAuthorityCheck(()->tasks.checkAutomationConditionRun(id,run,epoch,original));},prepared->{
                admissions++;JSONObject current=module.planTaskRequest(scope(),prepared.getString("planId"),prepared.getString("commitmentId"),prepared.getString("occurrenceId")),task=tasks.createReminderTask(current,current.getString("executionMode"));
                if(losePlanAck)throw new java.io.IOException("controlled acknowledgement loss after original task creation");
                tasks.bindCommitment(module.bindPlanTask(scope(),prepared.getString("planId"),prepared.getString("commitmentId"),prepared.getString("occurrenceId")));return new JSONObject().put("task",task);
            });plans.installConditionTaskPort(nativeHost);
        }
        void bindManualPlan()throws Exception{
            JSONObject plan=plans.handle(module,"createPlan",new JSONObject().put("plan",new JSONObject().put("kind","explicit_user").put("title","原已批目标").put("goal","在条件满足后按原授权继续").put("steps",new JSONArray().put(new JSONObject().put("title","原步骤").put("goal","原已批准手机目标").put("dueAt",now-60000))))).getJSONObject("plan");planId=plan.getString("id");
            plan=module.approvePlanExecution(scope(),planId,plan.getLong("revision"),new JSONObject().put("mode","manual").put("maxTaskAdmissions",1).put("modelObservationConsent",true));
            module.bindSemanticPlanForOwner(rule.getString("id"),rule.getLong("revision"),planId,plan.getLong("revision"),plan.getJSONArray("steps").getJSONObject(0).getString("id"));
            plan=module.handle("getPlan",new JSONObject().put("scope",scope()).put("id",planId)).getJSONObject("plan");module.startPlanExecution(scope(),planId,plan.getLong("revision"));assertEquals(0,module.readyPlanTasks(scope(),planId,20).getJSONArray("candidates").length());
        }
        void start()throws Exception{
            occurrence=book.claimDue(System.currentTimeMillis(),20).getJSONObject(0);id=nativeHost.ensureOriginalReadOnlyTask(occurrence);occurrence=module.bindSemanticCheck(occurrence.getString("id"),id);request=module.semanticCheckRequest(occurrence.getString("id"));nativeHost.startOriginalReadOnlyTask(occurrence);
        }
        RunOutcome run(boolean requestRead,int sourceStatus)throws Exception{
            AtomicInteger calls=new AtomicInteger(),delivered=new AtomicInteger();AtomicReference<Throwable> wireFailure=new AtomicReference<>();HttpServer model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var worker=Executors.newSingleThreadExecutor();
            model.createContext("/",exchange->{try{JSONObject actualRequest=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));int n=calls.incrementAndGet();
                try{JSONArray tools=actualRequest.optJSONArray("tools");boolean sourceOffered=false;for(int i=0;tools!=null&&i<tools.length();i++){String name=tools.getJSONObject(i).getJSONObject("function").getString("name");assertTrue("private or effecting tool reached condition model: "+name,java.util.Set.of(AutomationConditionReadPort.TOOL,"run_handle_obstacle","run_end_not_done","run_ask_user").contains(name));sourceOffered|=AutomationConditionReadPort.TOOL.equals(name);}if(n==1)assertTrue(sourceOffered);
                    if(n>1){assertNotNull("second model call preceded native read receipt",nativeReceipt);JSONObject sourceMessage=null;JSONArray messages=actualRequest.getJSONArray("messages");for(int i=0;i<messages.length();i++){JSONObject candidate=messages.getJSONObject(i);if("tool".equals(candidate.optString("role"))&&candidate.opt("content") instanceof String){JSONObject content=new JSONObject(candidate.getString("content"));if(content.optJSONObject("http")!=null&&SOURCE.equals(content.getJSONObject("http").optString("requestedUrl")))sourceMessage=content;}}
                        assertNotNull("actual model HTTP body omitted original GET receipt",sourceMessage);assertEquals(sourceStatus,sourceMessage.getJSONObject("http").getInt("statusCode"));assertEquals(nativeReceipt.getBoolean("ok"),sourceMessage.getBoolean("ok"));if(sourceStatus==200){assertEquals("PRICE=5",sourceMessage.getString("text"));assertEquals(nativeReceipt.getString("observationId"),sourceMessage.getString("observationId"));assertEquals(PublicConditionObservation.sha256("PRICE=5"),sourceMessage.getJSONObject("http").getString("bodySha256"));}else assertEquals("http_status",sourceMessage.getString("code"));delivered.incrementAndGet();}
                }catch(Throwable failed){wireFailure.compareAndSet(null,failed);}
                JSONObject message=new JSONObject().put("role","assistant");boolean tool=requestRead&&n==1;
                if(tool)message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","condition-read").put("type","function").put("function",new JSONObject().put("name",AutomationConditionReadPort.TOOL).put("arguments","{}"))));else message.put("content","{\"met\":true,\"evidence\":\"PRICE=5\"}");
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",tool?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});model.start();
            try{
                AutomationConditionReadPort port=new AutomationConditionReadPort(tasks,request,id,run,epoch,original,()->module.requireSemanticCheckCurrent(request),(args,control,gate)->{reads++;PublicConditionObservation actual=PublicConditionObservation.fromNativeRead(ConditionPublicReadFixture.consume(args,control,gate,sourceStatus,"PRICE=5"),control,gate);nativeReceipt=actual.receipt();return actual;});
                assertFalse(port.supports("phone_tap"));assertFalse(port.sideEffecting(AutomationConditionReadPort.TOOL));assertTrue(port.requiresOperation(AutomationConditionReadPort.TOOL));assertThrows(SecurityException.class,()->port.validate(AutomationConditionReadPort.TOOL,new JSONObject().put("url","https://other.example")));assertThrows(SecurityException.class,()->port.validate(AutomationConditionReadPort.TOOL,new JSONObject().put("_hostOperationId",java.util.UUID.randomUUID().toString())));assertThrows(SecurityException.class,()->port.execute(AutomationConditionReadPort.TOOL,new JSONObject(),original));assertEquals(0,reads);
                ModelConfig config=new ModelConfig("http://127.0.0.1:"+model.getAddress().getPort(),"fixture-key","condition-fixture",false,3,5);
                RunOutcome outcome=worker.submit(()->new AgentRunner(null,port).run(id,tasks.get(id,false).getString("goal"),config,original,event->{try{tasks.event(id,run,epoch,event);}catch(Exception failed){throw new IllegalStateException(failed);}},tasks.journal(id,run,epoch,original))).get(20,TimeUnit.SECONDS);
                tasks.finishCapabilityAttempt(id,run,epoch,original,outcome);if(wireFailure.get()!=null)throw new AssertionError("original model HTTP request violated native condition source boundary",wireFailure.get());assertEquals("original Runner outcome: "+outcome.status+" / "+outcome.summary,requestRead?2:1,calls.get());assertEquals(requestRead?1:0,delivered.get());if(requestRead){JSONArray operations=tasks.get(id,false).getJSONArray("operations");assertEquals(1,operations.length());assertEquals(AutomationConditionReadPort.TOOL,operations.getJSONObject(0).getString("tool"));assertEquals("receipt",operations.getJSONObject(0).getString("state"));assertEquals(run,operations.getJSONObject(0).getString("runId"));}return outcome;
            }finally{model.stop(0);worker.shutdownNow();worker.awaitTermination(2,TimeUnit.SECONDS);}
        }
        public void close(){db.close();}
    }
    @Test public void actualOriginalRunnerWitnessReleasesOneOriginalApprovedPlanAdmission()throws Exception{try(Fixture f=new Fixture()){f.bindManualPlan();f.start();f.run(true,200);AutomationConditionOutcome actual=f.nativeHost.readOriginalOutcome(f.occurrence);assertTrue(actual.known);assertTrue(actual.met);assertEquals(f.run,actual.json().getJSONObject("witness").getString("runId"));assertEquals(1,f.reads);
        f.plans.onTaskChanged(f.module,f.id);assertEquals(1,f.admissions);JSONObject row=f.book.semanticOccurrenceForHost(f.occurrence.getString("id"));assertEquals("condition_action_registered",row.getString("status"));JSONObject original=f.tasks.get(row.getString("conditionActionTaskId"),false);assertEquals(f.planId,original.getJSONObject("origin").getString("planId"));assertEquals("",original.optString("runId")); // Admission fixture did not execute a phone.
        f.plans.onTaskChanged(f.module,f.id);assertEquals(1,f.admissions);assertEquals(1,f.starts);assertThrows(Exception.class,()->f.tasks.startAttempt(f.id,"capabilities",3));
    }}
    @Test public void modelBooleanWithoutActualReadAndHttpFailureAreErrorsNotFalse()throws Exception{for(boolean missing:new boolean[]{true,false})try(Fixture f=new Fixture()){f.start();RunOutcome declared=f.run(!missing,500);AutomationConditionOutcome actual=f.nativeHost.readOriginalOutcome(f.occurrence);assertFalse(actual.known);
        JSONObject settled=f.tasks.get(f.id,false),audit=settled.getJSONObject("evidence").getJSONObject("conditionCheck");assertEquals("failed",settled.getString("status"));assertTrue(settled.getString("summary").contains("条件未确定"));assertTrue(settled.getString("summary").contains("未触发后续计划动作"));assertFalse(settled.getString("summary").contains("\"met\":true"));assertFalse(audit.getBoolean("known"));assertTrue(audit.isNull("met"));assertFalse(audit.getBoolean("actionTriggered"));assertEquals("needs_confirmation",audit.getString("modelDeclaredStatus"));assertEquals(declared.summary,audit.getString("modelDeclaredSummary"));assertTrue(new JSONObject(settled.getJSONObject("evidence").getString("modelSummary")).getBoolean("met"));
        f.book.completeSemanticCheck(f.occurrence.getString("id"),actual,System.currentTimeMillis());assertFalse(f.book.semanticRuleForHost(f.rule.getString("id")).has("lastKnownConditionMet"));assertEquals(0,f.admissions);
    }}
    @Test public void lostPlanAckQueriesSameTaskWithoutAnotherAdmissionOrGrant()throws Exception{try(Fixture f=new Fixture()){f.bindManualPlan();f.start();f.run(true,200);f.losePlanAck=true;assertThrows(Exception.class,()->f.plans.onTaskChanged(f.module,f.id));JSONObject row=f.book.semanticOccurrenceForHost(f.occurrence.getString("id"));assertEquals("condition_action_unknown",row.getString("status"));JSONObject actual=f.nativeHost.reconcileApprovedOriginalPlan(row);assertNotNull(actual);assertEquals(1,f.admissions);f.module.recordSemanticPlanAction(row.getString("id"),actual.getString("taskId"),false);assertEquals("condition_action_registered",f.book.semanticOccurrenceForHost(row.getString("id")).getString("status"));assertEquals(1,f.admissions);}}
    @Test public void fixedSourceConsentScopeAndPhysicalMainBudgetCannotExpand()throws Exception{try(Fixture f=new Fixture()){f.start();assertThrows(SecurityException.class,()->f.tasks.authorizeTools(f.id,f.run,new JSONArray().put("phone_tap")));assertThrows(SecurityException.class,()->f.tasks.startAttempt(f.id,"capabilities",20));
        for(int i=0;i<3;i++){var capture=new InferenceCapture(new ModelConfig("https://fixture.invalid","fixture","fixture",false,3,5),f.original,f.tasks.journal(f.id,f.run,f.epoch,f.original).inferenceTarget(InferenceReceipt.Purpose.MAIN,""));capture.dispatch();capture.finish(InferenceReceipt.Outcome.COMPLETED);}
        var fourth=new InferenceCapture(new ModelConfig("https://fixture.invalid","fixture","fixture",false,3,5),f.original,f.tasks.journal(f.id,f.run,f.epoch,f.original).inferenceTarget(InferenceReceipt.Purpose.MAIN,""));assertThrows(Exception.class,fourth::dispatch);
        f.book.setEnabled(f.rule.getString("id"),false,System.currentTimeMillis());assertThrows(SecurityException.class,()->f.module.requireSemanticCheckCurrent(f.request));assertEquals(0,f.reads);assertEquals(0,f.admissions);
    }}
}
