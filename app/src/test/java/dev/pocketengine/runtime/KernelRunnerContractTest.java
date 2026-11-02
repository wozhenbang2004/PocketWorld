package dev.pocketengine.runtime;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.*;
import dev.pocketengine.runtime.policy.RunOutcomeTools;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original Runner/Gateway/journal integration, using local HTTP and a recorded native phone port. */
public final class KernelRunnerContractTest {
    private static JSONObject reply(JSONObject message)throws Exception{return new JSONObject().put("model","fixture-model").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop").put("message",message)));}
    private static JSONObject answer(String text)throws Exception{return reply(new JSONObject().put("role","assistant").put("content",text));}
    private static JSONObject call(String name,JSONObject args)throws Exception{return reply(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","call-"+UUID.randomUUID()).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString())))));}
    @FunctionalInterface interface Responder {JSONObject response(JSONObject request,int index)throws Exception;}
    static final class Wire implements AutoCloseable {final HttpServer server;final List<JSONObject> sent=new ArrayList<>();Wire(JSONObject... replies)throws Exception{this((request,index)->replies[Math.min(index,replies.length-1)]);}Wire(Responder responder)throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{try{JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));int index; synchronized(sent){index=sent.size();sent.add(request);}byte[] bytes=responder.response(request,index).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();}ModelConfig config(){String url="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";return new ModelConfig(url,"","fixture-model",false,8,10,"openai_chat",512).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",url));}public void close(){server.stop(0);}}
    static final class Phone implements PhonePort {int reads,writes;String label="搜索";public boolean isConnected(){return true;}public JSONObject observe(boolean pixels,RunControl control)throws Exception{control.check();reads++;return new JSONObject().put("ok",true).put("observationId","o").put("packageName","app.example").put("nodes",new JSONArray().put(new JSONObject().put("id","n").put("text",label).put("clickable",true)));}public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{control.check();writes++;return new JSONObject().put("ok",true).put("accepted",true);}public void clearReferences(){}}
    static final class Journal implements RunJournal {JSONArray history=new JSONArray();JSONObject observation=new JSONObject();int steps,begins,finishes;final List<InferenceReceipt> modelReceipts=new ArrayList<>();public JSONObject restore()throws Exception{return new JSONObject().put("messages",new JSONArray(history.toString())).put("steps",steps).put("observation",observation);}public void checkpoint(JSONArray messages,int steps,JSONObject observation)throws Exception{history=new JSONArray(messages.toString());this.steps=steps;this.observation=new JSONObject(observation.toString());}public String beginOperation(String name,JSONObject args){return "op-"+(++begins);}public void finishOperation(String id,JSONObject receipt){finishes++;}public InferenceCapture.Target inferenceTarget(InferenceReceipt.Purpose purpose,String memberId)throws Exception{return InferenceCapture.observe(RunJournal.super.inferenceTarget(purpose,memberId),new InferenceCapture.Sink(){public void started(InferenceReceipt receipt){}public void finished(InferenceReceipt receipt){modelReceipts.add(receipt);}});}}
    private static RunControl control(){RunControl c=new RunControl();c.bindHostRun("task","run",1);return c;}
    private static RunOutcome run(Wire wire,Phone phone,Journal journal,String goal,AgentRunner.OwnerInputs inputs){return new AgentRunner(phone,null,selected->null,null,inputs).run("task",goal,wire.config(),control(),event->{},journal);}
    @Test public void discussingInstructionsDoesNotEvenObserveThePhone()throws Exception {Phone phone=new Phone();Journal journal=new Journal();try(Wire wire=new Wire(answer("这是操作步骤说明"))){RunOutcome outcome=run(wire,phone,journal,"告诉我如何转账",null);assertEquals("needs_confirmation",outcome.status);assertEquals(0,phone.reads);assertEquals(0,phone.writes);assertEquals(0,journal.begins);assertEquals(1,wire.sent.size());}}
    @Test public void paymentCommitmentStopsBeforeOriginalOperationPreparation()throws Exception {Phone phone=new Phone();phone.label="确认支付 ¥12.00";Journal journal=new Journal();try(Wire wire=new Wire(call("phone_click_node",new JSONObject().put("observationId","o").put("nodeId","n")))){RunOutcome outcome=run(wire,phone,journal,"帮我完成这次付款",null);assertEquals("awaiting_user",outcome.status);assertEquals(0,phone.writes);assertEquals(0,journal.begins);assertEquals("not_dispatched",outcome.evidence.getJSONObject("nativePaymentBarrier").getString("effectStatus"));}}
    @Test public void ordinaryActionUsesTheSameJournalOnce()throws Exception {Phone phone=new Phone();Journal journal=new Journal();try(Wire wire=new Wire(call("phone_click_node",new JSONObject().put("observationId","o").put("nodeId","n")),answer("已点击搜索，等待你核对"))){RunOutcome outcome=run(wire,phone,journal,"帮我点击搜索",null);assertEquals("needs_confirmation",outcome.status);assertEquals(1,phone.writes);assertEquals(1,journal.begins);assertEquals(1,journal.finishes);}}
    @Test public void ownerInputAtNotDoneBoundaryWinsAndIsNotLost()throws Exception {Phone phone=new Phone();Journal journal=new Journal();String messageId=UUID.randomUUID().toString();boolean[] delivered={false};AgentRunner.OwnerInputs inputs=(included,boundary,close,control)->{if(boundary&&close&&!delivered[0]){delivered[0]=true;return new JSONArray().put(new JSONObject().put("messageId",messageId).put("mode","queue").put("content","只解释可用资料"));}return new JSONArray();};JSONObject blocked=new JSONObject().put("kind","missing_information").put("reason","缺少资料").put("performed","尚未操作").put("remaining","等待补充").put("recoverable",true);try(Wire wire=new Wire(call(RunOutcomeTools.END,blocked),answer("已采用主人新补充"))){RunOutcome outcome=run(wire,phone,journal,"查一下资料",inputs);assertEquals(outcome.summary+" "+outcome.evidence,"needs_confirmation",outcome.status);assertTrue(delivered[0]);assertFalse(outcome.evidence.has("blocked"));assertTrue(journal.history.toString().contains(messageId));assertEquals(0,phone.writes);}}
    @Test public void ordinaryQuestionProducesExactNativeReplyContract()throws Exception {Phone phone=new Phone();Journal journal=new Journal();try(Wire wire=new Wire(call(RunOutcomeTools.ASK,new JSONObject().put("question","要查询哪个日期？")))){RunOutcome outcome=run(wire,phone,journal,"查询日程",null);assertEquals("awaiting_user",outcome.status);JSONObject question=outcome.evidence.getJSONObject("ownerQuestion");assertEquals("answer",question.getString("replyKind"));assertFalse(question.getBoolean("approval"));assertFalse(question.getBoolean("takeover"));assertEquals("run",question.getString("runId"));assertTrue(question.getLong("waitRevision")>0);assertTrue(question.getString("requestId").matches("[a-f0-9-]{36}"));}}
    @Test public void exhaustedOperationBudgetOffersOnlyTerminalToolsAndCanRecordNotDone()throws Exception {
        Phone phone=new Phone();Journal journal=new Journal();JSONObject blocked=new JSONObject().put("kind","environment").put("reason","操作步数已用完").put("performed","已点击搜索").put("remaining","没有提交后续动作").put("recoverable",false);
        try(Wire wire=new Wire(call("phone_click_node",new JSONObject().put("observationId","o").put("nodeId","n")),call(RunOutcomeTools.END,blocked))){RunOutcome outcome=new AgentRunner(phone,null,selected->null).run("task","帮我点击搜索",wire.config().withStepCeiling(1),control(),event->{},journal);assertEquals(outcome.summary+" "+outcome.evidence,"failed",outcome.status);assertEquals(1,phone.writes);assertEquals(1,journal.begins);assertEquals(1,journal.steps);assertEquals(2,wire.sent.size());JSONArray offered=wire.sent.get(1).getJSONArray("tools");assertEquals(2,offered.length());for(int i=0;i<offered.length();i++)assertTrue(RunOutcomeTools.terminal(offered.getJSONObject(i).getJSONObject("function").getString("name")));assertFalse(outcome.evidence.getJSONObject("blocked").getBoolean("automaticRetryAuthorized"));assertTrue(outcome.summary.contains("没有提交后续动作"));}
    }
    @Test public void hiddenOrdinaryCallAfterBudgetCannotStartAnotherOperation()throws Exception {
        Phone phone=new Phone();Journal journal=new Journal();JSONObject args=new JSONObject().put("observationId","o").put("nodeId","n");
        JSONObject first=call("phone_click_node",args),hidden=call("phone_click_node",args);
        String hiddenId=hidden.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0).getString("id");
        try(Wire wire=new Wire(first,hidden)){
            RunOutcome outcome=new AgentRunner(phone,null,selected->null).run("task","帮我点击搜索",wire.config().withStepCeiling(1),control(),event->{},journal);
            // The terminal-only second request makes this an unoffered-tool PROTOCOL failure
            // at the original Gateway, before the Runner could adopt it or issue a tool receipt.
            assertEquals(outcome.summary+" "+outcome.evidence,"failed",outcome.status);
            assertEquals(1,phone.writes);assertEquals(1,journal.begins);assertEquals(1,journal.finishes);assertEquals(1,journal.steps);assertEquals(2,wire.sent.size());
            JSONArray offered=wire.sent.get(1).getJSONArray("tools");assertEquals(2,offered.length());
            for(int i=0;i<offered.length();i++)assertTrue(RunOutcomeTools.terminal(offered.getJSONObject(i).getJSONObject("function").getString("name")));
            assertEquals(2,journal.modelReceipts.size());assertEquals(InferenceReceipt.Outcome.COMPLETED,journal.modelReceipts.get(0).outcome);assertEquals(InferenceReceipt.Outcome.FAILED,journal.modelReceipts.get(1).outcome);
            JSONObject failure=journal.modelReceipts.get(1).toJson().getJSONObject("failure");assertEquals("PROTOCOL",failure.getString("kind"));assertFalse(failure.getBoolean("automaticRetry"));
            assertFalse(journal.history.toString().contains(hiddenId));assertFalse(journal.history.toString().contains("step_limit_not_dispatched"));
        }
    }
    @Test public void offeredBatchTailAfterBudgetRetainsNotDispatchedReceiptWithoutAnotherOperation()throws Exception {
        Phone phone=new Phone();Journal journal=new Journal();JSONObject args=new JSONObject().put("observationId","o").put("nodeId","n");
        JSONObject first=call("phone_click_node",args).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0);
        JSONObject tail=call("phone_click_node",args).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0);
        String tailId=tail.getString("id");JSONObject batch=reply(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(first).put(tail)));
        try(Wire wire=new Wire(batch)){
            RunOutcome outcome=new AgentRunner(phone,null,selected->null).run("task","帮我点击搜索",wire.config().withStepCeiling(1),control(),event->{},journal);
            assertEquals(outcome.summary+" "+outcome.evidence,"needs_confirmation",outcome.status);
            assertEquals(1,phone.writes);assertEquals(1,journal.begins);assertEquals(1,journal.finishes);assertEquals(1,journal.steps);assertEquals(1,wire.sent.size());
            assertEquals(1,journal.modelReceipts.size());assertEquals(InferenceReceipt.Outcome.COMPLETED,journal.modelReceipts.get(0).outcome);
            boolean offered=false;JSONArray tools=wire.sent.get(0).getJSONArray("tools");for(int i=0;i<tools.length();i++)if("phone_click_node".equals(tools.getJSONObject(i).getJSONObject("function").getString("name")))offered=true;assertTrue(offered);
            int pairedCalls=0,pairedResults=0;JSONObject stopped=null;
            for(int i=0;i<journal.history.length();i++){
                JSONObject row=journal.history.getJSONObject(i);JSONArray calls=row.optJSONArray("tool_calls");
                for(int j=0;calls!=null&&j<calls.length();j++)if(tailId.equals(calls.getJSONObject(j).getString("id")))pairedCalls++;
                if("tool".equals(row.optString("role"))&&tailId.equals(row.optString("tool_call_id"))){pairedResults++;stopped=new JSONObject(row.getString("content"));}
            }
            assertEquals(1,pairedCalls);assertEquals(1,pairedResults);assertNotNull(stopped);
            assertEquals("step_limit_not_dispatched",stopped.getString("code"));assertFalse(stopped.getBoolean("accepted"));assertEquals("not_dispatched",stopped.getString("effectStatus"));
        }
    }
}
