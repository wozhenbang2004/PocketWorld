package dev.pocketengine.runtime;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.ContextWindow;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Gateway HTTP/Runner with a native journal fixture; the original SQL V2 journey is separate. */
public final class RecoveryProjectionRunnerTest {
    @Test public void twoRestoresUseBoundedTransientFactsAndPreserveLegacyOwnerTextSourceMetadataAndToolResults()throws Exception{
        List<JSONObject> requests=java.util.Collections.synchronizedList(new ArrayList<>());ExecutorService threads=Executors.newCachedThreadPool();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(threads);
        server.createContext("/",exchange->{try{
            requests.add(new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] body=new JSONObject().put("id","fixture-response").put("object","chat.completion").put("model","recovery-fixture").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("message",new JSONObject().put("role","assistant").put("content","已查看实际记录，不会重放旧调用。")).put("finish_reason","stop"))).put("usage",new JSONObject().put("prompt_tokens",13).put("completion_tokens",5).put("total_tokens",18)).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }catch(Exception failed){try{exchange.sendResponseHeaders(500,0);}catch(Exception ignored){}}finally{exchange.close();}});server.start();
        try{
            ModelConfig config=new ModelConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture-credential","recovery-fixture",false,4,10,"openai_chat",1000);
            ContextBudgetPolicy.Source budgets=selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1);
            String legacy="宿主保存的实际动作与机主核对结果（只作历史数据，不得自动重放；先重新观察）：\n这也可能是机主旧原话，不能凭前缀删除";
            JSONArray original=new JSONArray().put(new JSONObject().put("role","system").put("content","Native original policy")).put(new JSONObject().put("role","user").put("content","OWNER GOAL"))
                    .put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","old-call").put("type","function").put("function",new JSONObject().put("name","native_old_tool").put("arguments","{}")))))
                    .put(new JSONObject().put("role","tool").put("tool_call_id","old-call").put("content",new JSONObject().put("ok",true).put("artifactId","ACTUAL-OLD-ARTIFACT").toString()))
                    .put(new JSONObject().put("role","user").put("content",legacy).put("ownerMessageId","11111111-1111-1111-1111-111111111111").put("ownerMessageRevision",1).put("ownerInputMode","queue").put("ownerSourceRefs",new JSONArray()));
            JSONArray sql=new JSONArray();for(int i=0;i<9;i++)sql.put(RecoveryProjectionTest.operation(i));String fullAudit=sql.toString();
            final JSONObject[] checkpoint={new JSONObject().put("messages",original).put("steps",0)};
            RunJournal journal=new RunJournal(){
                public JSONObject restore()throws Exception{return new JSONObject(checkpoint[0].toString()).put("operationResults",sql);}
                public void checkpoint(JSONArray messages,int steps,JSONObject observation)throws Exception{checkpoint[0]=new JSONObject().put("messages",new JSONArray(messages.toString())).put("steps",steps).put("observation",observation);}
                public String beginOperation(String name,JSONObject args){throw new AssertionError("No effect allowed");}public void finishOperation(String op,JSONObject receipt){throw new AssertionError("No effect allowed");}
            };
            CapabilityPort port=new CapabilityPort(){
                public JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","native_old_tool").put("description","Original retained read helper").put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false))));}
                public boolean supports(String name){return name.equals("native_old_tool");}public boolean sideEffecting(String name){return false;}public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError("Historical call must not replay");}
            };
            for(int attempt=0;attempt<2;attempt++){
                RunOutcome outcome=new AgentRunner(null,port,budgets).run("recovery-task","OWNER GOAL",config,new RunControl(),event->{},journal);assertEquals(outcome.summary,"needs_confirmation",outcome.status);
                String saved=checkpoint[0].getJSONArray("messages").toString();assertFalse(saved.contains("<native_recovery_facts>"));assertFalse(saved.contains("PRIVATE-CLAIM"));assertTrue(saved.contains(legacy.substring(0,30)));assertTrue(saved.contains("ACTUAL-OLD-ARTIFACT"));assertTrue(saved.contains("ownerMessageRevision"));assertTrue(saved.contains("ownerSourceRefs"));assertTrue(saved.getBytes(StandardCharsets.UTF_8).length<20000);
            }
            assertEquals(2,requests.size());for(JSONObject request:requests){String wire=request.getJSONArray("messages").toString();assertFalse(wire.contains("PRIVATE-CLAIM"));assertFalse(wire.contains("PRIVATE-CONTEXT"));assertFalse(wire.contains("ownerSourceRefs"));assertTrue(wire.contains("pocketworld.native-recovery-facts.v1"));assertEquals(1,wire.split("<native_recovery_facts>",-1).length-1);assertTrue(wire.contains("ACTUAL-OLD-ARTIFACT"));}
            assertEquals(fullAudit,sql.toString());assertEquals(9,sql.length());
        }finally{server.stop(0);threads.shutdownNow();assertTrue(threads.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
