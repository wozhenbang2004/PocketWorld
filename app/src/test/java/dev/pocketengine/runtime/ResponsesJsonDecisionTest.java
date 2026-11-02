package dev.pocketengine.runtime;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Production transport and receipts against actual local HTTP; no live provider claim. */
public final class ResponsesJsonDecisionTest {
    private static final String KEY="fixture-responses-key";
    private static JSONArray messages()throws Exception {
        return new JSONArray().put(new JSONObject().put("role","user").put("content","核对当前状态"));
    }
    private static JSONArray tools()throws Exception {
        return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject()
                .put("name","inspect").put("parameters",new JSONObject().put("type","object"))));
    }
    private static JSONObject call(String id,String name,String arguments)throws Exception {
        return new JSONObject().put("type","function_call").put("id","item-"+id).put("call_id",id)
                .put("name",name).put("arguments",arguments).put("status","completed");
    }
    private static JSONObject response(JSONArray output)throws Exception {
        return new JSONObject().put("id","response-fixture").put("status","completed").put("output",output)
                .put("usage",new JSONObject().put("input_tokens",11).put("output_tokens",7).put("total_tokens",18));
    }
    private static JSONObject text(String value)throws Exception {
        return new JSONObject().put("type","message").put("id","message-fixture").put("status","completed")
                .put("role","assistant").put("content",new JSONArray().put(new JSONObject()
                        .put("type","output_text").put("text",value)));
    }
    private static final class Server implements AutoCloseable {
        final HttpServer http;final AtomicInteger requests=new AtomicInteger();volatile String body,type="application/json";
        volatile JSONObject received;volatile Throwable failure;
        Server(String body)throws Exception {
            this.body=body;http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            http.createContext("/v1/responses",exchange->{
                try{
                    requests.incrementAndGet();received=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    byte[] bytes=this.body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type);
                    exchange.sendResponseHeaders(200,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
                }catch(Exception error){failure=error;}finally{exchange.close();}
            });http.start();
        }
        ModelConfig config(){return new ModelConfig("http://127.0.0.1:"+http.getAddress().getPort()+"/v1",KEY,
                "exact-responses-model",false,4,10,"openai_responses",512);}
        public void close(){http.stop(0);}
    }
    private static void rejectedInBothJsonModes(JSONObject wire)throws Exception {
        rejectedInBothJsonModes(wire.toString());
    }
    private static void rejectedInBothJsonModes(String body)throws Exception {
        try(Server server=new Server(body)){
            for(boolean streaming:new boolean[]{false,true}){
                List<ModelGateway.StreamEvent> events=new ArrayList<>();
                try{
                    if(streaming)ModelGateway.completeStreaming(server.config(),messages(),tools(),new RunControl(),events::add);
                    else ModelGateway.complete(server.config(),messages(),tools(),new RunControl());
                    fail("Unverified Responses decision accepted in "+(streaming?"stream JSON negotiation":"buffered JSON"));
                }catch(ModelGateway.GatewayException expected){
                    assertFalse(expected.getMessage().contains(KEY));
                    if(streaming){
                        assertFalse(events.stream().anyMatch(event->event.kind==ModelGateway.StreamKind.COMPLETED));
                        assertEquals(ModelGateway.StreamKind.FAILED,events.get(events.size()-1).kind);
                    }
                }
            }
            assertEquals("Neither JSON mode may retry",2,server.requests.get());assertNull(server.failure);
        }
    }
    @Test public void unofferedFunctionCannotLeaveEitherJsonBoundary()throws Exception {
        rejectedInBothJsonModes(response(new JSONArray().put(call("call-one","not_offered","{}"))));
    }
    @Test public void duplicateCallIdentityCannotLeaveEitherJsonBoundary()throws Exception {
        rejectedInBothJsonModes(response(new JSONArray().put(call("same-call","inspect","{}"))
                .put(call("same-call","inspect","{}"))));
    }
    @Test public void nonObjectFunctionArgumentsCannotLeaveEitherJsonBoundary()throws Exception {
        for(String arguments:new String[]{"[]","null","{\"open\":", "{} trailing"})
            rejectedInBothJsonModes(response(new JSONArray().put(call("call-one","inspect",arguments))));
    }
    @Test public void namespacedFunctionCannotMasqueradeAsAnOfferedHostFunction()throws Exception {
        rejectedInBothJsonModes(response(new JSONArray().put(call("call-one","inspect","{}").put("namespace","other"))));
    }
    @Test public void missingTerminalStatusCannotBeAssumedCompleted()throws Exception {
        JSONObject wire=response(new JSONArray().put(text("partial")));wire.remove("status");rejectedInBothJsonModes(wire);
    }
    @Test public void completedEnvelopeCannotReleaseAnInProgressMessageInJsonOrSse()throws Exception {
        JSONObject wire=response(new JSONArray().put(text("partial").put("status","in_progress")));
        rejectedInBothJsonModes(wire);
        try(Server server=new Server("event: response.created\ndata: "+new JSONObject().put("type","response.created")
                .put("response",new JSONObject().put("id","response-fixture").put("status","in_progress"))+"\n\n"
                +"event: response.completed\ndata: "+new JSONObject().put("type","response.completed").put("response",wire)+"\n\n")){
            server.type="text/event-stream";List<ModelGateway.StreamEvent> events=new ArrayList<>();
            try{ModelGateway.completeStreaming(server.config(),messages(),tools(),new RunControl(),events::add);fail("Incomplete item released by completed envelope");}
            catch(ModelGateway.GatewayException expected){}
            assertEquals(1,server.requests.get());
            assertFalse(events.stream().anyMatch(event->event.kind==ModelGateway.StreamKind.COMPLETED));
        }
    }
    @Test public void malformedUnicodeAndTrailingDocumentsCannotBecomeDecisions()throws Exception {
        rejectedInBothJsonModes(response(new JSONArray().put(call("call-one","inspect","{\"text\":\"\\ud800\"}"))));
        rejectedInBothJsonModes(response(new JSONArray().put(text("valid first object")))+" {}");
    }
    @Test public void rejectedBufferedDecisionPreservesActualUsageAsAFailedReceipt()throws Exception {
        try(Server server=new Server(response(new JSONArray().put(call("call-one","not_offered","{}"))).toString())){
            List<InferenceReceipt> started=new ArrayList<>(),finished=new ArrayList<>();
            InferenceCapture.Target capture=new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){
                public void started(InferenceReceipt receipt){started.add(receipt);}
                public void finished(InferenceReceipt receipt){finished.add(receipt);}
            });
            try{ModelGateway.complete(server.config(),messages(),tools(),new RunControl(),capture);fail("Rejected tool became completed");}
            catch(ModelGateway.GatewayException expected){}
            assertEquals(1,server.requests.get());assertEquals(1,started.size());assertEquals(1,finished.size());
            InferenceReceipt receipt=finished.get(0);assertEquals(InferenceReceipt.Outcome.FAILED,receipt.outcome);
            JSONObject counters=receipt.toJson().getJSONObject("usage").getJSONObject("counters");
            assertEquals(11,counters.getJSONObject("INPUT").getLong("value"));
            assertEquals(7,counters.getJSONObject("OUTPUT").getLong("value"));
        }
    }
    @Test public void verifiedJsonCallContinuesWithTheSameNativeHistoryIntoSse()throws Exception {
        JSONObject reasoning=new JSONObject().put("type","reasoning").put("id","reasoning-fixture")
                .put("summary",new JSONArray()).put("encrypted_content","opaque-fixture");
        JSONObject nativeCall=call("call-one","inspect","{}");
        try(Server server=new Server(response(new JSONArray().put(reasoning).put(nativeCall)).toString())){
            JSONArray history=messages();JSONObject first=ModelGateway.complete(server.config(),history,tools(),new RunControl());
            assertEquals("call-one",first.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
            assertEquals("",first.getString("content"));
            history.put(first).put(new JSONObject().put("role","tool").put("tool_call_id","call-one").put("content","{\"actual\":true}"));
            JSONObject completed=response(new JSONArray().put(text("实际核对完成")));
            server.body="event: response.created\ndata: "+new JSONObject().put("type","response.created").put("sequence_number",0)
                    .put("response",new JSONObject().put("id","response-fixture").put("status","in_progress"))+"\n\n"
                    +"event: response.completed\ndata: "+new JSONObject().put("type","response.completed").put("sequence_number",1)
                    .put("response",completed)+"\n\n";
            server.type="text/event-stream";List<ModelGateway.StreamEvent> events=new ArrayList<>();
            JSONObject second=ModelGateway.completeStreaming(server.config(),history,tools(),new RunControl(),events::add);
            assertEquals("实际核对完成",second.getString("content"));
            assertEquals(ModelGateway.StreamKind.COMPLETED,events.get(events.size()-1).kind);
            JSONArray input=server.received.getJSONArray("input");assertEquals("opaque-fixture",input.getJSONObject(1).getString("encrypted_content"));
            assertEquals("item-call-one",input.getJSONObject(2).getString("id"));assertEquals("call-one",input.getJSONObject(3).getString("call_id"));
            assertFalse(server.received.getBoolean("store"));assertEquals(2,server.requests.get());assertNull(server.failure);
        }
    }
}
