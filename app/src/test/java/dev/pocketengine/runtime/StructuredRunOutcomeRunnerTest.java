package dev.pocketengine.runtime;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.policy.RunOutcomeTools;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual original Runner and HTTP gateway. Declarations are never native effect/approval proofs. */
public final class StructuredRunOutcomeRunnerTest {
    private static JSONObject call(String id,String name,JSONObject args)throws Exception{return new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()));}
    private static JSONObject calls(JSONObject... calls)throws Exception{JSONArray rows=new JSONArray();for(JSONObject call:calls)rows.put(call);return new JSONObject().put("role","assistant").put("tool_calls",rows);}
    private static JSONObject blocked(boolean recoverable)throws Exception{return new JSONObject().put("kind","environment").put("reason","original runtime unavailable").put("performed","read original receipts").put("remaining","no business effect done").put("recoverable",recoverable);}
    private static final class Fixture implements AutoCloseable,RunJournal {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);final AtomicInteger requests=new AtomicInteger();
        final List<JSONObject> replies=new ArrayList<>(),events=new ArrayList<>();final RunControl original=new RunControl();
        JSONArray raw=new JSONArray();JSONObject observed=new JSONObject();int steps,effects,reads,observations,operations,approvalChecks;boolean alias,unknown,answerQueued,inputDelivered;
        final PhonePort phone=new PhonePort(){
            public boolean isConnected(){return true;}
            public void clearReferences(){}
            public JSONObject observe(boolean pixels,RunControl control)throws Exception{assertSame(original,control);control.check();observations++;return new JSONObject().put("ok",true).put("observationId","current-screen").put("nodes",new JSONArray());}
            public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError("No phone effect belongs to outcome declarations");}
        };
        final CapabilityPort port=new CapabilityPort(){
            public JSONArray tools()throws Exception{JSONArray rows=new JSONArray();for(String name:alias?new String[]{RunOutcomeTools.ASK}:new String[]{"fixture_write","fixture_read"})rows.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name).put("description","native fixture").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()))));return rows;}
            public boolean supports(String name){return alias?RunOutcomeTools.ASK.equals(name):name.equals("fixture_write")||name.equals("fixture_read");}
            public boolean sideEffecting(String name){return name.equals("fixture_write");}
            public JSONObject beforeTool(String name,JSONObject args,RunControl control)throws Exception{assertSame(original,control);if(sideEffecting(name)){approvalChecks++;return new JSONObject().put("ok",false).put("accepted",false).put("code","original_approval_required");}return null;}
            public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{assertSame(original,control);control.check();if(sideEffecting(name))effects++;else reads++;JSONObject receipt=new JSONObject().put("ok",true);if(unknown)receipt.put("accepted",JSONObject.NULL);return receipt;}
        };
        final AgentRunner.OwnerInputs inputs=(included,boundary,close,control)->{
            assertSame(original,control);if(answerQueued&&!inputDelivered&&requests.get()>0&&boundary&&close){inputDelivered=true;return new JSONArray().put(new JSONObject().put("messageId","30000000-0000-0000-0000-000000000003").put("mode","queue").put("ownerMessageRevision",1).put("content","请执行原范围操作"));}return new JSONArray();
        };
        Fixture()throws Exception{
            original.bindHostRun("original-task","original-run",1);
            server.createContext("/",exchange->{try{
                exchange.getRequestBody().readAllBytes();int turn=requests.getAndIncrement();JSONObject message=turn<replies.size()?replies.get(turn):new JSONObject().put("role","assistant").put("content","原任务说明已结束，外部执行未获证明。");byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",message.has("tool_calls")?"tool_calls":"stop").put("message",message))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.start();
        }
        RunOutcome run(){String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";ModelConfig config=new ModelConfig(endpoint,"private-key-never-store","fixture-model",false,8,10,"openai_chat",512).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));return new AgentRunner(phone,port,selected->null,null,inputs).run("original-task","请完成原目标",config,original,event->events.add(event),this);}
        public JSONObject restore()throws Exception{return new JSONObject().put("messages",raw).put("steps",steps).put("observation",observed);}
        public void checkpoint(JSONArray history,int count,JSONObject observation)throws Exception{raw=new JSONArray(history.toString());steps=count;observed=new JSONObject(observation.toString());}
        public String beginOperation(String tool,JSONObject args){operations++;return java.util.UUID.randomUUID().toString();}
        public void finishOperation(String operation,JSONObject receipt){}
        public void close(){server.stop(0);}
    }
    @Test public void declaredObstacleIsOnlyAdviceNotAnEffectOrResolvedState()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.replies.add(calls(call("obstacle",RunOutcomeTools.OBSTACLE,new JSONObject().put("kind","captcha").put("detail","real page requires owner"))));RunOutcome outcome=fixture.run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(2,fixture.requests.get());assertEquals(0,fixture.effects);assertEquals(0,fixture.operations);assertEquals("No extra observation is triggered by an internal advice declaration",2,fixture.observations);assertTrue(fixture.raw.toString().contains("\\\"obstacleResolved\\\":false"));assertTrue(fixture.raw.toString().contains("\\\"effectsDispatched\\\":false"));}
    }
    @Test public void recoverableEndIsExplicitlyNotDoneAndCannotAuthorizeARetry()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.replies.add(calls(call("end",RunOutcomeTools.END,blocked(true))));RunOutcome outcome=fixture.run();assertEquals("awaiting_user",outcome.status);JSONObject declaration=outcome.evidence.getJSONObject("blocked");assertFalse(declaration.getBoolean("businessVerified"));assertFalse(declaration.getBoolean("automaticRetryAuthorized"));assertEquals("model_declared_not_native_effect_proof",declaration.getString("source"));assertTrue(outcome.summary.contains("本次未完成"));assertEquals(1,fixture.requests.get());assertEquals(0,fixture.effects);assertEquals(0,fixture.operations);}
    }
    @Test public void unrecoverableEndIsFailedNotGoalCompletion()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.replies.add(calls(call("end",RunOutcomeTools.END,blocked(false))));RunOutcome outcome=fixture.run();assertEquals("failed",outcome.status);assertTrue(outcome.summary.contains("未做"));assertFalse(outcome.evidence.has("nativeFinalAnswer"));assertEquals(1,fixture.requests.get());}
    }
    @Test public void terminalDeclarationSettlesEveryUnsentTailWithoutDispatchingIt()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.replies.add(calls(call("end",RunOutcomeTools.END,blocked(true)),call("unsent-write","fixture_write",new JSONObject()),call("unsent-read","fixture_read",new JSONObject())));fixture.run();String raw=fixture.raw.toString();assertTrue(raw.contains("\"tool_call_id\":\"unsent-write\""));assertTrue(raw.contains("\"tool_call_id\":\"unsent-read\""));assertEquals(2,raw.split("terminal_not_dispatched",-1).length-1);assertEquals(0,fixture.effects);assertEquals(0,fixture.reads);assertEquals(0,fixture.approvalChecks);assertEquals(0,fixture.operations);}
    }
    @Test public void askWaitsForAnAnswerOnTheOriginalIdentityAndIsNotAnApprovalOrTakeover()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.replies.add(calls(call("ask",RunOutcomeTools.ASK,new JSONObject().put("question","请问提醒在几点？"))));RunOutcome outcome=fixture.run();assertEquals("awaiting_user",outcome.status);JSONObject question=outcome.evidence.getJSONObject("ownerQuestion");assertEquals("original-task",question.getString("taskId"));assertEquals("original-run",question.getString("runId"));assertEquals(1,question.getInt("coordinatorEpoch"));assertEquals("answer",question.getString("replyKind"));assertFalse(question.getBoolean("approval"));assertFalse(question.getBoolean("takeover"));assertTrue(question.getString("requestId").matches("[a-f0-9-]{36}"));assertEquals(0,fixture.operations);assertEquals(1,fixture.requests.get());}
    }
    @Test public void structuredQuestionPersistsChoicesOnOnlyItsOriginalWait()throws Exception{
        try(Fixture fixture=new Fixture()){
            JSONObject args=new JSONObject().put("question","先做哪一项？").put("options",new JSONArray().put(new JSONObject().put("id","read").put("label","先读取原资料")).put(new JSONObject().put("id","wait").put("label","稍后再做")));
            fixture.replies.add(calls(call("ask",RunOutcomeTools.ASK,args)));RunOutcome outcome=fixture.run();
            assertEquals("awaiting_user",outcome.status);JSONObject question=outcome.evidence.getJSONObject("ownerQuestion");
            assertEquals("original-task",question.getString("taskId"));assertEquals("original-run",question.getString("runId"));assertEquals(2,question.getJSONArray("options").length());
            assertEquals("read",question.getJSONArray("options").getJSONObject(0).getString("id"));assertFalse(question.getBoolean("approval"));assertFalse(question.getBoolean("takeover"));assertEquals(0,fixture.operations);assertEquals(1,fixture.requests.get());
        }
    }
    @Test public void aNativeQueuedAnswerContinuesTheOriginalTaskButStillCannotGrantAnEffect()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.answerQueued=true;fixture.replies.add(calls(call("ask",RunOutcomeTools.ASK,new JSONObject().put("question","请补充条件"))));fixture.replies.add(calls(call("write","fixture_write",new JSONObject())));RunOutcome outcome=fixture.run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertTrue(fixture.inputDelivered);assertEquals(3,fixture.requests.get());assertEquals(1,fixture.approvalChecks);assertEquals(0,fixture.effects);assertEquals(0,fixture.operations);assertTrue(fixture.raw.toString().contains("original_approval_required"));assertTrue(fixture.raw.toString().contains("30000000-0000-0000-0000-000000000003"));}
    }
    @Test public void originalUnknownReceiptStopsBeforeAnyLaterAskOrEndDecision()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.unknown=true;fixture.replies.add(calls(call("read","fixture_read",new JSONObject())));fixture.replies.add(calls(call("ask",RunOutcomeTools.ASK,new JSONObject().put("question","should never be dispatched"))));RunOutcome outcome=fixture.run();assertEquals("awaiting_user",outcome.status);assertTrue(outcome.evidence.optBoolean("requiresReconciliation"));assertFalse(outcome.evidence.has("ownerQuestion"));assertEquals(1,fixture.requests.get());assertEquals(1,fixture.reads);}
    }
    @Test public void pluginCannotClaimTheSameNameAsANativeOutcomeHandler()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.alias=true;RunOutcome outcome=fixture.run();assertEquals("failed",outcome.status);assertEquals(0,fixture.requests.get());assertEquals(0,fixture.effects);assertEquals(0,fixture.operations);}
    }
    @Test public void malformedFieldsDoNotEndTheRunOrAcquireAnExecutionIdentity()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.replies.add(calls(call("invalid-end",RunOutcomeTools.END,blocked(true).put("operationId","forged-operation"))));RunOutcome outcome=fixture.run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertFalse(outcome.evidence.has("blocked"));assertTrue(fixture.raw.toString().contains("invalid_arguments"));assertEquals(0,fixture.operations);assertEquals(0,fixture.effects);}
    }
    @Test public void modelDeclarationSecretIsNotStoredInOutcomeOrRawHistory()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.replies.add(calls(call("end",RunOutcomeTools.END,blocked(true).put("reason","private-key-never-store"))));RunOutcome outcome=fixture.run();assertFalse(outcome.evidence.toString().contains("private-key-never-store"));assertFalse(outcome.summary.contains("private-key-never-store"));assertFalse(fixture.raw.toString().contains("private-key-never-store"));}
    }
}
