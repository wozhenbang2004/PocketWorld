package dev.pocketengine.runtime;

import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.context.CompactionJournal;
import dev.pocketengine.runtime.context.CompactionPlan;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import dev.pocketengine.runtime.context.SummaryDraft;
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

/** Actual original Runner/Gateway request projection. Journal/executor are explicit native fixtures. */
public final class CommittedReadBatchRunnerTest {
    private static final class Fixture implements AutoCloseable,RunJournal {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final AtomicInteger requests=new AtomicInteger();final List<JSONObject> wire=new ArrayList<>(),events=new ArrayList<>();
        JSONArray raw=new JSONArray();JSONObject observation=new JSONObject();int steps,reads;boolean failed,unknown;long tokens=13500;
        final CompactionJournal compaction=new CompactionJournal(){
            public FrozenPrefix freeze(CompactionPlan cut,RunControl original){throw new AssertionError("Duplicate read optimization must not make a SUMMARY/model request");}
            public PublishReceipt publish(FrozenPrefix frozen,SummaryDraft draft,RunControl original){throw new AssertionError("No summary publication belongs to this fixture");}
            public boolean current(FrozenPrefix frozen,RunControl original){return false;}
        };
        public CompactionJournal compaction(){return compaction;}
        public JSONObject restore()throws Exception{return new JSONObject().put("messages",raw).put("observation",observation).put("steps",steps);}
        public void checkpoint(JSONArray history,int count,JSONObject latest)throws Exception{raw=new JSONArray(history.toString());steps=count;observation=new JSONObject(latest.toString());}
        public String beginOperation(String tool,JSONObject args){throw new AssertionError("No effect operation belongs to these reads");}
        public void finishOperation(String id,JSONObject receipt){throw new AssertionError("No effect operation belongs to these reads");}
        Fixture()throws Exception{
            server.createContext("/",exchange->{try{
                wire.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));int turn=requests.getAndIncrement();JSONObject message=new JSONObject().put("role","assistant");
                if(turn<2)message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","read-"+turn).put("type","function").put("function",new JSONObject().put("name","browser_task_read_public").put("arguments","{}"))));else message.put("content","已收到实际只读资料；未执行外部写入。");
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",turn<2?"tool_calls":"stop").put("message",message))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}});server.start();
        }
        RunOutcome run(){
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";ModelConfig config=new ModelConfig(endpoint,"","fixture-model",false,8,10,"openai_chat",512).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_chat",endpoint));RunControl original=new RunControl();original.bindHostRun("original-task","original-run",1);
            CapabilityPort port=new CapabilityPort(){
                public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","browser_task_read_public").put("description","actual fixture read").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()))));}
                public boolean supports(String name){return "browser_task_read_public".equals(name);}
                public boolean sideEffecting(String name){return false;}
                public JSONObject execute(String name,JSONObject args,RunControl live)throws Exception{assertSame(original,live);live.check();reads++;JSONObject receipt=new JSONObject().put("ok",!failed||reads!=1).put("data","x".repeat(6000));if(unknown)receipt.put("accepted",JSONObject.NULL);return receipt;}
            };
            return new AgentRunner(null,port,selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(tokens,512,128,100000,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1)).run("original-task","只读查询原资料，不要修改",config,original,event->events.add(event),this);
        }
        public void close(){server.stop(0);}
    }
    @Test public void realCommittedDuplicateReadBatchShrinksOnlyTheRequestAndNeedsNoExtraInference()throws Exception{
        try(Fixture fixture=new Fixture()){
            RunOutcome outcome=fixture.run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(2,fixture.reads);assertEquals(3,fixture.requests.get());
            String finalWire=fixture.wire.get(2).getJSONArray("messages").toString(),raw=fixture.raw.toString();assertFalse(finalWire.contains("\"id\":\"read-0\""));assertFalse(finalWire.contains("\"tool_call_id\":\"read-0\""));assertTrue(finalWire.contains("\"id\":\"read-1\""));assertTrue(finalWire.contains("\"tool_call_id\":\"read-1\""));assertTrue(finalWire.contains("只读查询原资料"));assertTrue(raw.contains("\"id\":\"read-0\""));assertTrue(raw.contains("\"id\":\"read-1\""));assertTrue(fixture.events.stream().anyMatch(event->event.optJSONObject("receipt")!=null&&event.optJSONObject("receipt").optBoolean("toolResultsAutomaticallyEvicted")));
        }
    }
    @Test public void originalFailedReadReceiptRemainsInTheNextRequest()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.failed=true;fixture.tokens=100000;RunOutcome outcome=fixture.run();assertEquals(outcome.summary,"needs_confirmation",outcome.status);assertEquals(2,fixture.reads);assertEquals(3,fixture.requests.get());String finalWire=fixture.wire.get(2).getJSONArray("messages").toString();assertTrue(finalWire.contains("\"id\":\"read-0\""));assertTrue(finalWire.contains("\\\"ok\\\":false"));assertTrue(finalWire.contains("\"id\":\"read-1\""));assertTrue(fixture.events.stream().noneMatch(event->event.optJSONObject("receipt")!=null&&event.optJSONObject("receipt").optBoolean("toolResultsAutomaticallyEvicted")));}
    }
    @Test public void originalUnknownReadStopsWithoutAutomaticRetryOrAnotherModelRequest()throws Exception{
        try(Fixture fixture=new Fixture()){fixture.unknown=true;RunOutcome outcome=fixture.run();assertEquals("awaiting_user",outcome.status);assertTrue(outcome.evidence.optBoolean("requiresReconciliation"));assertEquals(1,fixture.reads);assertEquals(1,fixture.requests.get());assertTrue(fixture.raw.toString().contains("\\\"accepted\\\":null"));}
    }
}
