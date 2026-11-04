package dev.pocketengine.runtime;

import dev.pocketengine.core.*;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Runner/Gateway/loopback transport; mailbox, journal and effect executor are
 * explicit native fixtures. SQLite acceptance is covered by the owner-input journey. */
public final class OwnerSteeringRunnerTest {
    private enum Hold { HEADERS, STREAM, JSON_BODY, TOOL, NONE }
    private static final String INPUT="NEW-ORIGINAL-OWNER-INPUT",PREFIX="OLD-PROVISIONAL-TEXT";
    private static JSONObject call(String id)throws Exception{return new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name","fixture_operation").put("arguments","{}"));}
    private static JSONObject reply(JSONObject message)throws Exception{return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).put("usage",new JSONObject().put("prompt_tokens",11).put("completion_tokens",7).put("total_tokens",18));}
    private static String delta(JSONObject value,String finish)throws Exception{return "data: "+new JSONObject().put("id","fixture-response").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",value).put("finish_reason",finish==null?JSONObject.NULL:finish)))+"\r\n\r\n";}

    private static final class Fixture implements AutoCloseable,RunJournal,AgentRunner.OwnerInputs {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final RunControl control=new RunControl();final Hold hold;
        final CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1),releaseFirst=new CountDownLatch(1),visible=new CountDownLatch(1);
        final CountDownLatch toolEntered=new CountDownLatch(1),releaseTool=new CountDownLatch(1);
        final List<JSONObject> wire=new CopyOnWriteArrayList<>(),mailbox=new CopyOnWriteArrayList<>(),events=new CopyOnWriteArrayList<>();
        final List<InferenceReceipt> started=new CopyOnWriteArrayList<>(),finished=new CopyOnWriteArrayList<>();
        final AtomicInteger calls=new AtomicInteger(),effects=new AtomicInteger(),toolCancels=new AtomicInteger(),begun=new AtomicInteger(),ended=new AtomicInteger();
        final List<AgentRunner.OwnerReply> replies=new CopyOnWriteArrayList<>();
        volatile JSONArray history=new JSONArray();volatile int steps;volatile JSONObject originalReceipt;volatile boolean unknown;
        Fixture(Hold hold)throws Exception{
            this.hold=hold;control.restrictModelConcurrency(1);control.bindHostRun("original-task","original-run",3);
            server.createContext("/",exchange->{try{
                int turn=calls.getAndIncrement();wire.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));if(turn==1)second.countDown();
                JSONObject message=new JSONObject().put("role","assistant").put("content",turn==0?"OLD-ANSWER":"REPLANNED-ANSWER");
                if(turn==0&&hold==Hold.TOOL)message.remove("content");
                if(turn==0&&hold==Hold.TOOL)message.put("tool_calls",new JSONArray().put(call("effect-one")).put(call("effect-two")));
                byte[] body=reply(message).toString().getBytes(StandardCharsets.UTF_8);
                if(turn==0&&hold==Hold.HEADERS){first.countDown();await(releaseFirst);}
                if(turn==0&&hold==Hold.STREAM){
                    byte[] prefix=delta(new JSONObject().put("role","assistant").put("content",PREFIX),null).getBytes(StandardCharsets.UTF_8);
                    byte[] tail=(delta(new JSONObject().put("tool_calls",new JSONArray().put(call("stale-effect").put("index",0))),"tool_calls")+"data: [DONE]\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,prefix.length+tail.length);
                    exchange.getResponseBody().write(prefix);exchange.getResponseBody().flush();first.countDown();await(releaseFirst);exchange.getResponseBody().write(tail);
                }else{
                    exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                    if(turn==0&&hold==Hold.JSON_BODY){exchange.getResponseBody().write(body,0,1);exchange.getResponseBody().flush();first.countDown();await(releaseFirst);exchange.getResponseBody().write(body,1,body.length-1);}
                    else exchange.getResponseBody().write(body);
                }
            }finally{exchange.close();}});server.start();
        }
        private static void await(CountDownLatch gate)throws Exception{if(!gate.await(10,TimeUnit.SECONDS))throw new AssertionError("Fixture release was not reached");}
        ModelConfig config(){String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";return new ModelConfig(endpoint,"","fixture-model",false,8,10,"openai_chat",512).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));}
        JSONObject accept(String text,String mode)throws Exception{
            JSONObject row=new JSONObject().put("messageId",UUID.randomUUID().toString()).put("content",text).put("mode",mode);
            Runnable wake;
            synchronized(mailbox){mailbox.add(row);wake="steer".equals(mode)?control.prepareModelReplan("original-task","original-run",3):null;}
            if(wake!=null)wake.run();return row;
        }
        public JSONArray poll(JSONArray included,boolean boundary,boolean close,RunControl original)throws Exception{
            assertSame(control,original);Set<String> ids=new HashSet<>();for(int i=0;i<included.length();i++)ids.add(included.getString(i));JSONArray result=new JSONArray();
            synchronized(mailbox){for(JSONObject row:mailbox)if(!ids.contains(row.getString("messageId"))&&(boundary||"steer".equals(row.getString("mode"))))result.put(new JSONObject(row.toString()));}return result;
        }
        public void completed(AgentRunner.OwnerReply reply,RunControl original){assertSame(control,original);assertTrue(reply.matchesOriginal(original));replies.add(reply);}
        public JSONObject restore()throws Exception{return new JSONObject().put("messages",new JSONArray(history.toString())).put("steps",steps);}
        public void checkpoint(JSONArray value,int count,JSONObject observation)throws Exception{history=new JSONArray(value.toString());steps=count;}
        public String beginOperation(String tool,JSONObject arguments){assertEquals("fixture_operation",tool);begun.incrementAndGet();return "original-operation";}
        public void finishOperation(String operation,JSONObject receipt)throws Exception{assertEquals("original-operation",operation);ended.incrementAndGet();originalReceipt=new JSONObject(receipt.toString());}
        public InferenceCapture.Target inferenceTarget(InferenceReceipt.Purpose purpose,String member){return new InferenceCapture.Target(new InferenceReceipt.Context("original-task","original-run",3,purpose,member),new InferenceCapture.Sink(){public void started(InferenceReceipt receipt){started.add(receipt);}public void finished(InferenceReceipt receipt){finished.add(receipt);}});}
        Future<RunOutcome> run(){return run(null);}
        Future<RunOutcome> run(AgentRunner.RequestProjection projection){
            CapabilityPort port=new CapabilityPort(){
                public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","fixture_operation").put("description","Original authorized fixture operation").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()))));}
                public boolean supports(String name){return "fixture_operation".equals(name);}
                public boolean sideEffecting(String name){return true;}
                public JSONObject execute(String name,JSONObject arguments,RunControl original)throws Exception{
                    assertSame(control,original);effects.incrementAndGet();Runnable cancelled=toolCancels::incrementAndGet;original.onCancel(cancelled);
                    try{toolEntered.countDown();await(releaseTool);original.check();return new JSONObject().put("ok",!unknown).put("accepted",unknown?JSONObject.NULL:true).put("receipt","ORIGINAL-EFFECT-RECEIPT");}
                    finally{original.removeCancelAction(cancelled);}
                }
            };
            ContextBudgetPolicy.Source budget=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(32768,512,512,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            return worker.submit(()->new AgentRunner(null,port,budget,projection,this).run("original-task","请执行一次已授权的测试操作并报告结果",config(),control,event->{events.add(event);if(event.toString().contains(PREFIX))visible.countDown();},this));
        }
        void originalIdentity(){assertFalse(control.isCancelled());assertTrue(control.matchesHostRun("original-task","original-run",3));assertEquals(1,control.modelConcurrencyLimit());for(InferenceReceipt receipt:finished){assertEquals("original-task",receipt.context.taskId);assertEquals("original-run",receipt.context.runId);assertEquals(3,receipt.context.epoch);}}
        public void close()throws Exception{releaseFirst.countDown();releaseTool.countDown();control.cancel();server.stop(0);worker.shutdownNow();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));}
    }

    @Test public void steeringDuringHeldHeadersReplansBeforeTheOldServerReplies()throws Exception{heldModelReplans(Hold.HEADERS);}
    @Test public void steeringDuringVisibleStreamDropsItsStaleToolDecisionAndKeepsUsage()throws Exception{heldModelReplans(Hold.STREAM);}
    @Test public void steeringDuringBufferedJsonBodyAlsoReplansWithoutWaitingForEof()throws Exception{heldModelReplans(Hold.JSON_BODY);}
    private void heldModelReplans(Hold hold)throws Exception{
        try(Fixture f=new Fixture(hold)){
            Future<RunOutcome> running=f.run();assertTrue(f.first.await(3,TimeUnit.SECONDS));if(hold==Hold.STREAM)assertTrue(f.visible.await(2,TimeUnit.SECONDS));
            JSONObject accepted=f.accept(INPUT,"steer");assertTrue("Replan must begin before old response release",f.second.await(2,TimeUnit.SECONDS));
            RunOutcome outcome=running.get(3,TimeUnit.SECONDS);assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(1,f.releaseFirst.getCount());
            assertEquals(2,f.calls.get());assertEquals(2,f.started.size());assertEquals(2,f.finished.size());assertEquals(InferenceReceipt.Outcome.CANCELLED,f.finished.get(0).outcome);assertEquals(InferenceReceipt.Outcome.COMPLETED,f.finished.get(1).outcome);
            assertEquals(0,f.effects.get());assertEquals(0,f.begun.get());assertEquals(0,f.steps);assertEquals(1,f.replies.size());
            assertTrue(f.wire.get(1).toString().contains(INPUT));assertFalse(f.wire.get(1).toString().contains("stale-effect"));assertFalse(f.history.toString().contains(PREFIX));
            assertTrue(f.history.toString().contains(accepted.getString("messageId")));assertEquals(1,f.mailbox.size());f.originalIdentity();
        }
    }
    @Test public void ordinaryQueueWaitsForTheCompletedTurnAndDoesNotCancelItsTransport()throws Exception{
        try(Fixture f=new Fixture(Hold.HEADERS)){
            Future<RunOutcome> running=f.run();assertTrue(f.first.await(3,TimeUnit.SECONDS));f.accept(INPUT,"queue");
            assertThrows(TimeoutException.class,()->running.get(150,TimeUnit.MILLISECONDS));assertEquals(1,f.calls.get());assertEquals(0,f.control.modelReplanRevision());
            f.releaseFirst.countDown();assertEquals("needs_confirmation",running.get(3,TimeUnit.SECONDS).status);
            assertEquals(2,f.replies.size());assertTrue(f.history.toString().contains("OLD-ANSWER"));assertTrue(f.wire.get(1).toString().contains(INPUT));
            assertTrue(f.finished.stream().allMatch(receipt->receipt.outcome==InferenceReceipt.Outcome.COMPLETED));f.originalIdentity();
        }
    }
    @Test public void stopStillCancelsTheOriginalRunWithoutReplanning()throws Exception{
        try(Fixture f=new Fixture(Hold.HEADERS)){
            Future<RunOutcome> running=f.run();assertTrue(f.first.await(3,TimeUnit.SECONDS));f.control.cancel();
            assertEquals("cancelled",running.get(2,TimeUnit.SECONDS).status);assertEquals(1,f.calls.get());assertTrue(f.control.isCancelled());assertEquals(0,f.replies.size());assertEquals(InferenceReceipt.Outcome.CANCELLED,f.finished.get(0).outcome);
        }
    }
    @Test public void steeringDuringAnEffectWaitsForItsOriginalReceiptAndNeverReplaysIt()throws Exception{
        try(Fixture f=new Fixture(Hold.TOOL)){
            Future<RunOutcome> running=f.run();assertTrue(f.toolEntered.await(3,TimeUnit.SECONDS));f.accept(INPUT,"steer");
            assertThrows(TimeoutException.class,()->running.get(150,TimeUnit.MILLISECONDS));assertEquals(0,f.toolCancels.get());assertEquals(1,f.calls.get());
            f.releaseTool.countDown();assertEquals("needs_confirmation",running.get(3,TimeUnit.SECONDS).status);
            assertEquals(1,f.effects.get());assertEquals(1,f.begun.get());assertEquals(1,f.ended.get());assertEquals(1,f.steps);assertEquals("ORIGINAL-EFFECT-RECEIPT",f.originalReceipt.getString("receipt"));
            assertTrue(f.wire.get(1).toString().contains("ORIGINAL-EFFECT-RECEIPT"));assertTrue(f.wire.get(1).toString().contains("owner_steered"));assertTrue(f.wire.get(1).toString().contains(INPUT));f.originalIdentity();
        }
    }
    @Test public void unknownEffectKeepsSteerInputAndStopsForReconciliationWithoutReplay()throws Exception{
        try(Fixture f=new Fixture(Hold.TOOL)){
            f.unknown=true;Future<RunOutcome> running=f.run();assertTrue(f.toolEntered.await(3,TimeUnit.SECONDS));JSONObject accepted=f.accept(INPUT,"steer");f.releaseTool.countDown();
            RunOutcome outcome=running.get(3,TimeUnit.SECONDS);assertEquals("awaiting_user",outcome.status);assertTrue(outcome.evidence.optBoolean("requiresReconciliation"));
            assertEquals(1,f.effects.get());assertEquals(1,f.calls.get());assertEquals(1,f.ended.get());assertTrue(f.originalReceipt.isNull("accepted"));assertEquals(accepted.getString("messageId"),f.mailbox.get(0).getString("messageId"));f.originalIdentity();
        }
    }
    @Test public void acceptanceAfterPollBeforeGatewayRegistrationDiscardsTheUnsentProjection()throws Exception{
        try(Fixture f=new Fixture(Hold.NONE)){
            AtomicBoolean inject=new AtomicBoolean(true);
            Future<RunOutcome> running=f.run((history,config,original)->{if(inject.getAndSet(false))f.accept(INPUT,"steer");return history;});
            assertEquals("needs_confirmation",running.get(3,TimeUnit.SECONDS).status);assertEquals(1,f.calls.get());assertEquals(1,f.started.size());assertTrue(f.wire.get(0).toString().contains(INPUT));assertTrue(f.history.toString().contains(INPUT));f.originalIdentity();
        }
    }
}
