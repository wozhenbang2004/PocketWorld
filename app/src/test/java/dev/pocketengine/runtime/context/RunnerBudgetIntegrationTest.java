package dev.pocketengine.runtime.context;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.CapabilityPort;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.runtime.AgentRunner;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real AgentRunner + real Gateway/stream parser + loopback transport; fixture journal, not SQLite/device. */
public final class RunnerBudgetIntegrationTest {
    @Test public void bothMountedProjectionsKeepFreshToolsContextFeedbackAndCompleteReceipts() throws Exception {
        Journal journal=new Journal();AtomicInteger turns=new AtomicInteger(),observations=new AtomicInteger();
        List<JSONObject> events=new ArrayList<>();
        try (Wire wire=new Wire((request,n)-> n<=4?json(call("wait-"+n,"phone_wait","{\"ms\":0}")):json(answer("核对实际结果")))) {
            PhonePort phone=new PhonePort() {
                public boolean isConnected(){return true;}
                public void clearReferences(){ }
                public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError("wait is handled by the existing runner");}
                public JSONObject observe(boolean image,RunControl control)throws Exception {
                    control.check();assertFalse(image);int n=observations.incrementAndGet();
                    if(n==3)journal.feedback="不要发送或删除；保留机主补充要求";
                    return new JSONObject().put("ok",true).put("observationId","obs-"+n)
                            .put("nodes",new JSONArray().put(new JSONObject().put("id","node-"+n).put("text","same-host-screen-"+"n".repeat(10000))));
                }
            };
            CapabilityPort capabilities=new CapabilityPort() {
                public JSONArray tools(){throw new AssertionError("prepareTurn must own each current snapshot");}
                public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception {
                    control.check();int n=turns.incrementAndGet();
                    return new JSONObject().put("tools",RunnerBudgetIntegrationTest.tools("revision_"+n,"tool-schema-"+n))
                            .put("reference",new JSONObject().put("context","ephemeral-reference-"+n+"r".repeat(8000)));
                }
                public boolean supports(String name){return false;}
                public boolean sideEffecting(String name){return true;}
                public JSONObject execute(String name,JSONObject args,RunControl control){throw new AssertionError("not requested");}
            };
            ModelConfig config=config(wire,8);
            RunOutcome result=new AgentRunner(phone,capabilities,known(config,30000)).run("budget-phone","读取当前现场，不要发送或删除",config,new RunControl(),events::add,journal);
            assertEquals(result.summary+" evidence="+result.evidence,"needs_confirmation",result.status);assertEquals(5,turns.get());assertEquals(5,wire.requests.size());
            assertEquals(0,journal.begins.get());
            for(int n=0;n<wire.requests.size();n++) {
                JSONObject request=wire.requests.get(n);
                assertTrue(request.getBoolean("stream"));assertEquals(config.maxOutputTokens,request.getInt("max_tokens"));
                assertTrue(request.getJSONArray("tools").toString().contains("tool-schema-"+(n+1)));
                String messages=request.getJSONArray("messages").toString();
                assertTrue(messages.contains("ephemeral-reference-"+(n+1)));
                assertTrue(messages.contains("读取当前现场"));
                if(n>=2)assertTrue(messages.contains("保留机主补充要求"));
                if(n>0)assertFalse(messages.contains("obs-1\\\""));
                assertPaired(request.getJSONArray("messages"));
                assertTrue(new JSONObject().put("messages",request.getJSONArray("messages")).put("tools",request.getJSONArray("tools"))
                        .toString().getBytes(StandardCharsets.UTF_8).length<=30000);
            }
            String persisted=journal.saved.toString();
            assertFalse(persisted.contains("ephemeral-reference-"));assertFalse(persisted.contains("tool-schema-"));
            assertTrue(persisted.contains("不要发送或删除"));assertTrue(persisted.contains("保留机主补充要求"));
            assertFalse(persisted.contains("obs-1\\\""));assertPaired(journal.saved.getJSONArray("messages"));
            for(int n=1;n<=4;n++)assertTrue(persisted.contains("wait-"+n));
            assertTrue(events.stream().anyMatch(e->e.optString("type").equals("context_budget")&&e.optJSONObject("receipt")!=null
                    &&e.optJSONObject("receipt").optJSONArray("removed")!=null&&e.optJSONObject("receipt").optJSONArray("removed").length()>0));
            assertTrue(events.stream().anyMatch(e->e.optString("type").equals("model_stream")&&"completed".equals(e.optJSONObject("stream").optString("kind"))));
            for(JSONObject event:events)if(event.optString("type").equals("model_stream"))assertFalse(event.getJSONObject("stream").getBoolean("taskCompleted"));
        }
    }

    @Test public void freshReferenceOrSchemaOverflowStopsBeforeAChargedCallWithoutPersistingIt() throws Exception {
        for(boolean hugeSchema:new boolean[]{false,true})try(Wire wire=new Wire((request,n)->json(answer("must not call")))) {
            Journal journal=new Journal();AtomicInteger executions=new AtomicInteger();
            CapabilityPort port=new TestPort(false,executions) {
                @Override public JSONObject prepareTurn(String query,int round,int steps,RunControl control)throws Exception {
                    return new JSONObject().put("tools",RunnerBudgetIntegrationTest.tools("read_fixture",hugeSchema?"x".repeat(20000):"small"))
                            .put("reference",new JSONObject().put("context",hugeSchema?"":"ephemeral-do-not-persist-"+"x".repeat(20000)));
                }
            };
            ModelConfig config=config(wire,4);
            RunOutcome result=new AgentRunner(null,port,known(config,6000)).run("budget-reference","保留原目标",config,new RunControl(),e->{},journal);
            assertEquals("awaiting_user",result.status);assertEquals(0,wire.requests.size());assertEquals(0,executions.get());
            assertEquals("budget_exceeded",result.evidence.getJSONObject("contextBudget").getString("status"));
            assertEquals("request",result.evidence.getJSONObject("contextBudget").getString("boundary"));
            assertFalse(journal.saved.toString().contains("ephemeral-do-not-persist"));assertTrue(journal.saved.toString().contains("保留原目标"));
        }
    }

    @Test public void oversizedActualEffectReceiptIsNotTrimmedOrRetriedAndOriginalLedgerKeepsIt() throws Exception {
        AtomicInteger effects=new AtomicInteger();Journal journal=new Journal();
        try(Wire wire=new Wire((request,n)->json(call("effect-once","effect_fixture","{}")))) {
            TestPort port=new TestPort(true,effects) {
                @Override public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception {
                    control.check();effects.incrementAndGet();assertEquals("operation-1",args.getString("_hostOperationId"));
                    return new JSONObject().put("ok",true).put("accepted",true).put("actualResult","retained-in-original-ledger-"+"x".repeat(14000));
                }
            };
            ModelConfig config=config(wire,4);
            RunOutcome result=new AgentRunner(null,port,known(config,6000)).run("budget-effect","执行已明确授权的一次动作",config,new RunControl(),e->{},journal);
            assertEquals("awaiting_user",result.status);assertEquals(1,effects.get());assertEquals(1,wire.requests.size());
            assertEquals(1,journal.begins.get());assertEquals(1,journal.finishes.get());
            assertTrue(journal.actualReceipts.toString().contains("retained-in-original-ledger"));
            assertEquals("checkpoint",result.evidence.getJSONObject("contextBudget").getString("boundary"));
            assertEquals("budget_exceeded",result.evidence.getJSONObject("contextBudget").getString("status"));
            assertFalse(result.evidence.getJSONObject("contextBudget").toString().contains("retained-in-original-ledger"));
            assertPaired(journal.saved.getJSONArray("messages"));
        }
    }

    @Test public void unfinishedStreamWithPartialToolArgumentsCannotDispatchAnyNativeEffect() throws Exception {
        AtomicInteger effects=new AtomicInteger();Journal journal=new Journal();List<JSONObject> events=new ArrayList<>();
        String incomplete="data: {\"object\":\"chat.completion.chunk\",\"id\":\"partial-response\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"临时文字\",\"tool_calls\":[{\"index\":0,\"id\":\"partial\",\"type\":\"function\",\"function\":{\"name\":\"effect_fixture\",\"arguments\":\"{\\\"x\\\":\"}}]},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n";
        try(Wire wire=new Wire((request,n)->new Response("text/event-stream",incomplete))) {
            ModelConfig config=config(wire,4);
            RunOutcome result=new AgentRunner(null,new TestPort(true,effects),known(config,10000)).run("bad-stream","不要猜测执行",config,new RunControl(),events::add,journal);
            assertEquals("failed",result.status);assertEquals(0,effects.get());assertEquals(0,journal.begins.get());assertEquals(1,wire.requests.size());
            assertTrue(events.stream().anyMatch(e->isDraftDiscard(e,"failed")));
            assertFalse(journal.saved.toString().contains("partial"));
        }
    }

    @Test public void cancellationAfterVisibleDraftClearsItThroughTheSameLiveControlAndNeverDispatches() throws Exception {
        AtomicInteger effects=new AtomicInteger();Journal journal=new Journal();List<JSONObject> events=new ArrayList<>();RunControl control=new RunControl();
        try(Wire wire=new Wire((request,n)->new Response("text/event-stream",completeSse("cancel-me")))) {
            ModelConfig config=config(wire,4);
            RunOutcome result=new AgentRunner(null,new TestPort(true,effects),known(config,10000)).run("cancel-stream","等我核对",config,control,event->{
                events.add(event);JSONObject stream=event.optJSONObject("stream");
                if(stream!=null&&stream.optString("kind").equals("text_delta"))control.cancel();
            },journal);
            assertEquals(result.summary+" events="+events,"cancelled",result.status);assertEquals(0,effects.get());assertEquals(0,journal.begins.get());assertEquals(1,wire.requests.size());
            assertTrue(events.stream().anyMatch(e->isDraftDiscard(e,"cancelled")));
        }
    }

    @Test public void pauseGenerationDiscardsTheCanonicalDecisionAndReplansWithoutExecutingIt() throws Exception {
        AtomicInteger effects=new AtomicInteger();Journal journal=new Journal();List<JSONObject> events=new ArrayList<>();RunControl control=new RunControl();
        try(Wire wire=new Wire((request,n)->n==1?new Response("text/event-stream",completeSse("stale-decision")):json(answer("已按新代次核对")))) {
            ModelConfig config=config(wire,4);
            RunOutcome result=new AgentRunner(null,new TestPort(true,effects),known(config,10000)).run("pause-stream","核对后再做",config,control,event->{
                events.add(event);JSONObject stream=event.optJSONObject("stream");
                if(stream!=null&&stream.optString("kind").equals("text_delta")&&control.pauseRevision()==0){control.pause();control.resume();}
            },journal);
            assertEquals(result.summary+" events="+events,"needs_confirmation",result.status);assertEquals(0,effects.get());assertEquals(0,journal.begins.get());assertEquals(2,wire.requests.size());
            assertTrue(events.stream().anyMatch(e->isDraftDiscard(e,"cancelled")));
            assertFalse(journal.saved.toString().contains("stale-decision"));
        }
    }

    @Test public void restoredUnknownReceiptAndOwnerReconciliationRemainProtectedBeforeFirstRequest() throws Exception {
        Journal journal=new Journal();JSONArray restored=new JSONArray().put(new JSONObject().put("role","user").put("content","原目标"))
                .put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(function("old-read","effect_fixture","{}"))));
        journal.saved=new JSONObject().put("messages",restored).put("steps",1);
        journal.feedback="之前动作已核对，不要重发";
        journal.actualReceipts.put(new JSONObject().put("operationId","prior-operation").put("outcome","occurred").put("source","owner_reconciliation"));
        AtomicInteger effects=new AtomicInteger();
        try(Wire wire=new Wire((request,n)->json(answer("核对当前结果")))) {
            ModelConfig config=config(wire,4);
            RunOutcome result=new AgentRunner(null,new TestPort(true,effects),known(config,10000)).run("restored-budget","原目标",config,new RunControl(),e->{},journal);
            assertEquals("needs_confirmation",result.status);assertEquals(0,effects.get());assertEquals(0,journal.begins.get());
            String history=wire.requests.get(0).getJSONArray("messages").toString();
            assertTrue(history.contains("not_replayed"));assertTrue(history.contains("owner_reconciliation"));assertTrue(history.contains("不要重发"));
            assertPaired(wire.requests.get(0).getJSONArray("messages"));
        }
    }

    private static boolean isDraftDiscard(JSONObject event,String kind) {
        JSONObject stream=event.optJSONObject("stream");return event.optString("type").equals("model_stream")&&stream!=null
                &&stream.optString("kind").equals(kind)&&stream.optBoolean("discardDraft")&&!stream.optBoolean("taskCompleted");
    }
    private static ModelConfig config(Wire wire,int steps){return new ModelConfig(wire.url(),"fixture-secret","fixture-model",false,steps,10,"openai_chat",1000);}
    private static ContextBudgetPolicy.Source known(ModelConfig config,long byteCeiling) {
        ContextBudgetPolicy.RouteBudget known=ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(1000000,1000,1000,byteCeiling,64,0),
                ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1000);return selected->known;
    }
    private static JSONArray tools(String name,String description)throws Exception {
        return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name",name)
                .put("description",description).put("parameters",new JSONObject().put("type","object"))));
    }
    private static JSONObject answer(String text)throws Exception{return new JSONObject().put("role","assistant").put("content",text);}
    private static JSONObject function(String id,String name,String args)throws Exception {
        return new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args));
    }
    private static JSONObject call(String id,String name,String args)throws Exception {
        return new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(function(id,name,args)));
    }
    private static Response json(JSONObject assistant)throws Exception {
        return new Response("application/json",new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",assistant.has("tool_calls")?"tool_calls":"stop").put("message",assistant))).toString());
    }
    private static String completeSse(String id) throws Exception {
        JSONObject delta=new JSONObject().put("role","assistant").put("content","临时可见文字").put("tool_calls",new JSONArray()
                .put(new JSONObject().put("index",0).put("id",id).put("type","function").put("function",new JSONObject().put("name","effect_fixture").put("arguments","{}"))));
        JSONObject first=new JSONObject().put("object","chat.completion.chunk").put("id","response-one").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",JSONObject.NULL)));
        JSONObject last=new JSONObject().put("object","chat.completion.chunk").put("id","response-one").put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",new JSONObject()).put("finish_reason","tool_calls")));
        return "data: "+first+"\n\ndata: "+last+"\n\ndata: [DONE]\n\n";
    }
    private static void assertPaired(JSONArray messages)throws Exception {
        java.util.Set<String> pending=new java.util.HashSet<>();
        for(int n=0;n<messages.length();n++) {
            JSONObject row=messages.getJSONObject(n);JSONArray calls=row.optJSONArray("tool_calls");
            if(calls!=null){assertTrue(pending.isEmpty());for(int i=0;i<calls.length();i++)assertTrue(pending.add(calls.getJSONObject(i).getString("id")));}
            else if(row.optString("role").equals("tool"))assertTrue(pending.remove(row.getString("tool_call_id")));
            else assertTrue(pending.isEmpty());
        }
        assertTrue(pending.isEmpty());
    }
    private static class TestPort implements CapabilityPort {
        final boolean effect;final AtomicInteger executions;
        TestPort(boolean effect,AtomicInteger executions){this.effect=effect;this.executions=executions;}
        public JSONArray tools()throws Exception{return RunnerBudgetIntegrationTest.tools(effect?"effect_fixture":"read_fixture","actual-current-tool");}
        public boolean supports(String name){return name.equals(effect?"effect_fixture":"read_fixture");}
        public boolean sideEffecting(String name){return effect;}
        public JSONObject execute(String name,JSONObject args,RunControl control)throws Exception{control.check();executions.incrementAndGet();return new JSONObject().put("ok",true).put("accepted",true);}
    }
    private static final class Journal implements RunJournal {
        JSONObject saved=new JSONObject();String feedback="";JSONArray actualReceipts=new JSONArray();
        final AtomicInteger begins=new AtomicInteger(),finishes=new AtomicInteger();
        public JSONObject restore()throws Exception{return new JSONObject(saved.toString()).put("feedback",feedback).put("operationResults",actualReceipts);}
        public void checkpoint(JSONArray messages,int steps,JSONObject observation)throws Exception {
            assertPaired(messages);saved=new JSONObject().put("messages",new JSONArray(messages.toString())).put("steps",steps).put("observation",new JSONObject(observation.toString()));
            assertTrue(saved.toString().getBytes(StandardCharsets.UTF_8).length<=ContextBudgetPolicy.CHECKPOINT_BYTES);
        }
        public String beginOperation(String name,JSONObject args){return "operation-"+begins.incrementAndGet();}
        public void finishOperation(String id,JSONObject receipt)throws Exception{finishes.incrementAndGet();actualReceipts.put(new JSONObject().put("operationId",id).put("receipt",new JSONObject(receipt.toString())));}
    }
    private record Response(String contentType,String body) { }
    private interface Reply { Response answer(JSONObject request,int n)throws Exception; }
    private static final class Wire implements AutoCloseable {
        final HttpServer server;final List<JSONObject> requests=Collections.synchronizedList(new ArrayList<>());
        Wire(Reply reply)throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/chat/completions",exchange->{try {
                JSONObject request=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));requests.add(request);
                Response response=reply.answer(request,requests.size());byte[] bytes=response.body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",response.contentType);exchange.sendResponseHeaders(200,bytes.length);
                exchange.getResponseBody().write(bytes);
            }catch(Exception failure){try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){} }
            finally{exchange.close();}});server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";}
        public void close(){server.stop(0);}
    }
}
