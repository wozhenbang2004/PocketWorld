package dev.pocketengine.store;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.AgentRunner;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.runtime.usage.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original Runner + native TaskStore + synthetic HTTP. No live provider/device claims. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class ContinuousExecutionJourneyTest {
    private static final class Fixture implements AutoCloseable {
        final List<JSONObject> streamEvents=new ArrayList<>();final TaskStore tasks;final SQLiteDatabase db;final String id,run;final int epoch;final RunControl control=new RunControl();final RunJournal journal;
        Fixture(boolean scope,int models)throws Exception {
            Application app=RuntimeEnvironment.getApplication();app.deleteDatabase("pocket-world.db");tasks=new TaskStore(app);
            id=tasks.createCapabilityTask("原任务目标","scope-fixture").getString("id");
            if(scope)tasks.configureExecutionScope(id,0,0,12,models);
            JSONObject task=tasks.startAttempt(id,"capabilities",12);run=task.getString("runId");epoch=task.getInt("coordinatorEpoch");control.bindHostRun(id,run,epoch);journal=tasks.journal(id,run,epoch,control);
            var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
        }
        JSONObject scope()throws Exception{return tasks.get(id,false).getJSONObject("executionScope");}
        InferenceCapture capture()throws Exception{return new InferenceCapture(new ModelConfig("https://fixture.example","test-key","fixture-model",false,12,5),control,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));}
        public void close(){control.cancel();db.close();}
    }
    private static final class Server implements AutoCloseable {
        final HttpServer http;final AtomicInteger posts=new AtomicInteger();final List<Integer> status;final List<String> bodies;final List<String> requests=new ArrayList<>();
        Server(List<Integer> status,List<String> bodies)throws Exception {this.status=status;this.bodies=bodies;http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/",exchange->{try{
            int index=posts.getAndIncrement();synchronized(requests){requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));}
            int code=this.status.get(Math.min(index,this.status.size()-1));if(code==0)return;String body=this.bodies.get(Math.min(index,this.bodies.size()-1));byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",body.startsWith("data:")?"text/event-stream":"application/json");if(code==429)exchange.getResponseHeaders().set("Retry-After","0");
            exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});http.start();}
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+http.getAddress().getPort(),"test-key","fixture-model",false,12,5,"openai_chat",1000);}
        public void close(){http.stop(0);}
    }
    private static String answer(String content)throws Exception{return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content",content)))).put("usage",new JSONObject().put("prompt_tokens",20).put("completion_tokens",4).put("total_tokens",24)).toString();}
    private static String toolAnswer()throws Exception{return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","tool_calls").put("message",new JSONObject().put("role","assistant").put("content","").put("tool_calls",new JSONArray().put(new JSONObject().put("id","effect1").put("type","function").put("function",new JSONObject().put("name","fixture_effect").put("arguments","{}"))))))).toString();}
    @FunctionalInterface private interface DuringWait {void run()throws Exception;}
    private RunOutcome run(Fixture f,Server server,AtomicInteger effects,boolean cancelOnWait)throws Exception {return run(f,server,effects,cancelOnWait,()->{});}
    private RunOutcome run(Fixture f,Server server,AtomicInteger effects,boolean cancelOnWait,DuringWait duringWait)throws Exception {
        f.tasks.authorizeTools(f.id,f.run,new JSONArray().put("fixture_effect"));
        CapabilityPort port=new CapabilityPort(){
            public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","fixture_effect").put("description","fixture").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()))));}
            public boolean supports(String name){return "fixture_effect".equals(name);}public boolean sideEffecting(String name){return true;}
            public JSONObject execute(String name,JSONObject args,RunControl original)throws Exception{effects.incrementAndGet();return new JSONObject().put("ok",true).put("status","completed");}
        };
        ContextBudgetPolicy.Source budget=config->ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(32768,config.maxOutputTokens,512,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,System.currentTimeMillis());
        return new AgentRunner(null,port,budget).run(f.id,"原任务目标",server.config(),f.control,event->{try{if("model_stream".equals(event.optString("type")))f.streamEvents.add(event);else f.tasks.event(f.id,f.run,f.epoch,event);if("model_recovery".equals(event.optString("type"))){duringWait.run();if(cancelOnWait)f.control.cancel();}}catch(Exception failed){throw new IllegalStateException(failed);}},f.journal);
    }
    @Test public void explicitScopeOutlivesLegacyTenMinutesWithoutSilentlyUpgradingLegacy()throws Exception {
        try(Fixture scoped=new Fixture(true,20)){assertEquals(Long.MAX_VALUE,scoped.tasks.taskExecutionDeadline(scoped.id,scoped.run,scoped.epoch));assertEquals("until_complete_or_stopped_or_budget",scoped.scope().getString("lifetime"));}
        long before=System.currentTimeMillis();try(Fixture legacy=new Fixture(false,20)){long expiry=legacy.tasks.taskExecutionDeadline(legacy.id,legacy.run,legacy.epoch);assertTrue(expiry>=before+600000);assertTrue(expiry<System.currentTimeMillis()+601000);assertFalse(legacy.tasks.get(legacy.id,false).has("executionScope"));}
    }
    @Test public void ownerRenewalIsCasAndDoesNotResetUsedBudgetOrStartANewRun()throws Exception {
        try(Fixture f=new Fixture(true,3)){InferenceCapture first=f.capture();first.dispatch();first.finish(InferenceReceipt.Outcome.FAILED);
            long deadline=System.currentTimeMillis()+7_200_000;f.tasks.configureExecutionScope(f.id,1,deadline,12,4);
            assertEquals(1,f.scope().getInt("modelCallsUsed"));assertEquals(2,f.scope().getLong("revision"));assertEquals(deadline,f.tasks.taskExecutionDeadline(f.id,f.run,f.epoch));
            assertThrows(SecurityException.class,()->f.tasks.configureExecutionScope(f.id,1,0,12,4));assertEquals(f.run,f.tasks.get(f.id,false).getString("runId"));
            f.tasks.endAttempt(f.id,f.run,"paused","owner",new JSONObject());JSONObject next=f.tasks.startAttempt(f.id,"capabilities",40);assertEquals(1,f.scope().getInt("modelCallsUsed"));assertEquals(12,next.getInt("stepCeiling"));assertThrows(SecurityException.class,()->f.tasks.taskExecutionDeadline(f.id,f.run,f.epoch));}
    }
    @Test public void physicalFailedCallConsumesBudgetAndCannotBeRefundedByContinuation()throws Exception {
        try(Fixture f=new Fixture(true,1)){InferenceCapture first=f.capture();first.dispatch();first.finish(InferenceReceipt.Outcome.FAILED);assertThrows(SecurityException.class,()->f.capture().dispatch());assertEquals(1,f.tasks.inferenceUsage(f.id).getInt("attempts"));f.tasks.endAttempt(f.id,f.run,"failed","network",new JSONObject());assertThrows(IllegalStateException.class,()->f.tasks.startAttempt(f.id,"capabilities",40));assertEquals(1,f.scope().getInt("modelCallsUsed"));}
    }
    @Test public void exactToolSetCannotGrowDuringContinuation()throws Exception {
        try(Fixture f=new Fixture(true,20)){f.tasks.authorizeTools(f.id,f.run,new JSONArray().put("fixture_read"));f.tasks.authorizeTools(f.id,f.run,new JSONArray().put("fixture_read").put("fixture_extension"));assertFalse(f.scope().getBoolean("toolsPinned"));InferenceCapture initial=f.capture();initial.dispatch();initial.finish(InferenceReceipt.Outcome.COMPLETED);assertTrue(f.scope().getBoolean("toolsPinned"));f.tasks.authorizeTools(f.id,f.run,new JSONArray().put("fixture_read").put("fixture_extension").put("fixture_pay"));assertThrows(SecurityException.class,()->f.tasks.checkCapability(f.id,f.run,f.epoch,"fixture_pay",f.control));assertEquals(2,f.scope().getJSONArray("tools").length());}
    }
    @Test public void retry429Then503RecoversWithThreeDistinctChargedAttempts()throws Exception {
        try(Fixture f=new Fixture(true,10);Server server=new Server(List.of(429,503,200),List.of("{\"error\":{\"code\":\"rate_limit_exceeded\"}}","{}",answer("完成原答复")))){
            RunOutcome result=run(f,server,new AtomicInteger(),false);assertEquals(result.summary,"needs_confirmation",result.status);assertEquals(3,server.posts.get());JSONObject usage=f.tasks.inferenceUsage(f.id);assertEquals(3,usage.getInt("attempts"));assertEquals(2,usage.getInt("failed"));assertEquals(1,usage.getInt("completed"));assertEquals(3,f.scope().getInt("modelCallsUsed"));Set<String> ids=new HashSet<>();JSONArray receipts=usage.getJSONArray("receipts");for(int i=0;i<receipts.length();i++)ids.add(receipts.getJSONObject(i).getString("callId"));assertEquals(3,ids.size());assertEquals(0,f.tasks.get(f.id,false).getJSONArray("operations").length());}
    }
    @Test public void eofDraftIsArchivedAndNotInsertedIntoAssistantHistory()throws Exception {
        String cut="data: {\"id\":\"cut-reply\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"中断但已显示的内容\"},\"finish_reason\":null}]}\n\n";
        try(Fixture f=new Fixture(true,10);Server server=new Server(List.of(200,200),List.of(cut,answer("最终答复")))){
            RunOutcome result=run(f,server,new AtomicInteger(),false);assertEquals(result.summary,"failed",result.status);assertTrue(result.summary,result.summary.contains("明确继续"));assertEquals(1,server.posts.get());String history=f.journal.restore().getJSONArray("messages").toString();assertFalse(history.contains("中断但已显示的内容"));assertFalse(history.contains("最终答复"));assertEquals("PARTIAL",f.tasks.inferenceUsage(f.id).getJSONArray("logicalRequests").getJSONObject(0).getString("state"));assertTrue(f.tasks.get(f.id,false).getJSONArray("events").toString().contains("中断但已显示的内容"));assertFalse(f.tasks.get(f.id,false).getJSONArray("events").toString().contains("model_stream"));assertTrue(f.streamEvents.toString().contains("中断但已显示的内容"));assertEquals(1,f.tasks.inferenceUsage(f.id).getInt("failed"));}
    }
    @Test public void completedToolIsNeverReplayedWhenFollowingInferenceFails()throws Exception {
        try(Fixture f=new Fixture(true,10);Server server=new Server(List.of(200,503,200),List.of(toolAnswer(),"{}",answer("已完成一次动作")))){
            AtomicInteger effects=new AtomicInteger();RunOutcome result=run(f,server,effects,false);assertEquals(result.summary,"needs_confirmation",result.status);assertEquals(1,effects.get());assertEquals(3,server.posts.get());assertEquals(1,f.tasks.get(f.id,false).getJSONArray("operations").length());}
    }
    @Test public void cancelDuringBackoffNeverSendsAnotherHttpRequest()throws Exception {
        try(Fixture f=new Fixture(true,10);Server server=new Server(List.of(503),List.of("{}"))){RunOutcome result=run(f,server,new AtomicInteger(),true);assertEquals("cancelled",result.status);assertEquals(1,server.posts.get());assertEquals(1,f.tasks.inferenceUsage(f.id).getInt("attempts"));}
    }
    @Test public void authRefusalAndMalformedTerminalsAreNotRetried()throws Exception {
        for(int code:List.of(401,403,400))try(Fixture f=new Fixture(true,10);Server server=new Server(List.of(code),List.of("{}"))){run(f,server,new AtomicInteger(),false);assertEquals(1,server.posts.get());}
        try(Fixture f=new Fixture(true,10);Server server=new Server(List.of(200),List.of(answer("拒绝").replace("\"stop\"","\"content_filter\"")))){run(f,server,new AtomicInteger(),false);assertEquals(1,server.posts.get());}
    }
    @Test public void unresolvedToolBlocksInferenceInsteadOfReplayingOrRefunding()throws Exception {
        try(Fixture f=new Fixture(true,10);Server server=new Server(List.of(200),List.of(answer("不可到达")))){f.tasks.authorizeTools(f.id,f.run,new JSONArray().put("fixture_effect"));String op=f.journal.beginOperation("fixture_effect",new JSONObject());f.journal.finishOperation(op,new JSONObject().put("accepted",JSONObject.NULL).put("status","unknown"));run(f,server,new AtomicInteger(),false);assertEquals(0,server.posts.get());assertTrue(f.tasks.hasUnresolved(f.id));assertEquals(1,f.scope().getInt("toolsUsed"));}
    }
    @Test public void absentDeadlinePolicyCannotMasqueradeAsUnlimited()throws Exception {try(Fixture f=new Fixture(true,10)){JSONObject scope=f.scope();scope.remove("deadlineAt");assertThrows(Exception.class,()->TaskExecutionScope.effectiveDeadline(scope));scope.put("deadlineAt",0).put("lifetime","deadline");assertThrows(SecurityException.class,()->TaskExecutionScope.effectiveDeadline(scope));}}
    @Test public void nativeScopeRenewalChangesLiveCeilingButNeverAddsSpentUnits()throws Exception {try(Fixture f=new Fixture(true,20)){f.tasks.authorizeTools(f.id,f.run,new JSONArray().put("fixture_effect"));String op=f.journal.beginOperation("fixture_effect",new JSONObject());f.journal.finishOperation(op,new JSONObject().put("ok",true));f.tasks.configureExecutionScope(f.id,1,0,30,30);assertEquals(29,f.journal.executionStepCeiling(12));assertEquals(1,f.scope().getInt("toolsUsed"));assertEquals(29,f.tasks.executionScopeRemaining(f.id,f.run,f.epoch).getInt("toolsRemaining"));assertEquals(f.run,f.tasks.get(f.id,false).getString("runId"));}}
    @Test public void freshNetworkDropIsRecoveredWithoutTransportRetryOrRefund()throws Exception {try(Fixture f=new Fixture(true,10);Server server=new Server(List.of(0,200),List.of("",answer("已恢复")))){run(f,server,new AtomicInteger(),false);assertEquals(2,server.posts.get());assertEquals(2,f.scope().getInt("modelCallsUsed"));}}
    @Test public void integerOverflowCannotCreateWiderScope()throws Exception {assertThrows(SecurityException.class,()->TaskExecutionScope.exactInt(new JSONObject().put("toolLimit",4294967297L),"toolLimit",1,400));assertThrows(SecurityException.class,()->TaskExecutionScope.exactInt(new JSONObject().put("toolLimit","12"),"toolLimit",1,400));}

    @Test public void deadlineExpiringDuringBackoffBlocksNextPhysicalCallAndPreservesFailedReceipt()throws Exception {try(Fixture f=new Fixture(true,10);Server server=new Server(List.of(503,200),List.of("{}",answer("不可到达")))){run(f,server,new AtomicInteger(),false,()->f.tasks.configureExecutionScope(f.id,1,System.currentTimeMillis()+100,12,10));assertEquals(1,server.posts.get());assertEquals(1,f.scope().getInt("modelCallsUsed"));assertEquals(1,f.tasks.inferenceUsage(f.id).getInt("failed"));}}
    @Test public void taskRetryWaitBudgetCannotBeRefilledWithANewRunOrNativeRenewal()throws Exception {try(Fixture f=new Fixture(true,20)){for(int i=0;i<6;i++){InferenceCapture failed=f.capture();failed.dispatch();failed.finish(InferenceReceipt.Outcome.FAILED);assertTrue(f.journal.reserveModelRecovery(failed.callId,500));assertFalse(f.journal.reserveModelRecovery(failed.callId,500));}f.tasks.configureExecutionScope(f.id,1,0,20,30);f.tasks.endAttempt(f.id,f.run,"paused","owner",new JSONObject());JSONObject task=f.tasks.startAttempt(f.id,"capabilities",20);RunControl next=new RunControl();next.bindHostRun(f.id,task.getString("runId"),task.getInt("coordinatorEpoch"));RunJournal journal=f.tasks.journal(f.id,task.getString("runId"),task.getInt("coordinatorEpoch"),next);InferenceCapture failed=new InferenceCapture(new ModelConfig("https://fixture.example","test","model",false,20,10),next,journal.inferenceTarget(InferenceReceipt.Purpose.MAIN,""));failed.dispatch();failed.finish(InferenceReceipt.Outcome.FAILED);assertFalse(journal.reserveModelRecovery(failed.callId,500));assertEquals(7,f.scope().getInt("modelCallsUsed"));next.cancel();}}

    @Test public void originalSourceRevocationDuringWaitCancelsInsteadOfResendingStaleWire()throws Exception {try(Fixture f=new Fixture(true,10);Server server=new Server(List.of(503,200),List.of("{}",answer("不可到达")))){java.util.concurrent.atomic.AtomicBoolean sourceCurrent=new java.util.concurrent.atomic.AtomicBoolean(true);f.control.setAuthorityCheck(()->{if(!sourceCurrent.get())throw new SecurityException("original source forgotten");});RunOutcome result=run(f,server,new AtomicInteger(),false,()->sourceCurrent.set(false));assertEquals("cancelled",result.status);assertEquals(1,server.posts.get());assertEquals(1,f.scope().getInt("modelCallsUsed"));}}

    @Test public void recoveredZeroCallRunCannotReopenInitialNativeToolCollection()throws Exception {try(Fixture f=new Fixture(true,10)){f.tasks.authorizeTools(f.id,f.run,new JSONArray().put("fixture_read"));f.tasks.endAttempt(f.id,f.run,"paused","owner",new JSONObject());JSONObject next=f.tasks.startAttempt(f.id,"capabilities",12);f.tasks.authorizeTools(f.id,next.getString("runId"),new JSONArray().put("fixture_read").put("fixture_pay"));assertTrue(f.scope().getBoolean("toolsPinned"));assertEquals(1,f.scope().getJSONArray("tools").length());}}
    @Test public void preparedEffectAndOwnerRenewalCannotExpandInitialToolList()throws Exception {try(Fixture f=new Fixture(true,10)){f.tasks.authorizeTools(f.id,f.run,new JSONArray().put("fixture_read"));String operation=f.journal.beginOperation("fixture_read",new JSONObject());assertTrue(f.scope().getBoolean("toolsPinned"));f.tasks.configureExecutionScope(f.id,1,0,20,20);f.tasks.authorizeTools(f.id,f.run,new JSONArray().put("fixture_read").put("fixture_pay"));assertEquals(1,f.scope().getJSONArray("tools").length());assertTrue(f.tasks.hasUnresolved(f.id));assertEquals(1,f.scope().getInt("toolsUsed"));}}


    @Test public void unknown429NeverUsesRetryAfterAsRateLimitClassification()throws Exception {
        try(Fixture f=new Fixture(true,10);Server server=new Server(List.of(429,200),List.of("{}",answer("不可到达")))){RunOutcome result=run(f,server,new AtomicInteger(),false);assertEquals("failed",result.status);assertEquals(1,server.posts.get());assertEquals(1,f.scope().getInt("modelCallsUsed"));}
    }
    @Test public void incompleteUndeliveredPrefixCanRecoverWithoutSplicingVisibleText()throws Exception {
        String cut="data: {\"id\":\"cut-reply\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"test-\"},\"finish_reason\":null}]}\n\n";
        try(Fixture f=new Fixture(true,10);Server server=new Server(List.of(200,200),List.of(cut,answer("最终答复")))){RunOutcome result=run(f,server,new AtomicInteger(),false);assertEquals(result.summary,"needs_confirmation",result.status);assertEquals(2,server.posts.get());JSONArray receipts=f.tasks.inferenceUsage(f.id).getJSONArray("receipts");String failedCall="";for(int i=0;i<receipts.length();i++)if("FAILED".equals(receipts.getJSONObject(i).getString("outcome")))failedCall=receipts.getJSONObject(i).getString("callId");assertFalse(failedCall.isEmpty());for(JSONObject event:f.streamEvents){JSONObject stream=event.getJSONObject("stream");if(failedCall.equals(stream.getString("requestId")))assertNotEquals("text_delta",stream.getString("kind"));}assertEquals(1,f.tasks.inferenceUsage(f.id).getJSONArray("logicalRequests").length());assertTrue(f.journal.restore().getJSONArray("messages").toString().contains("最终答复"));}
    }
}
