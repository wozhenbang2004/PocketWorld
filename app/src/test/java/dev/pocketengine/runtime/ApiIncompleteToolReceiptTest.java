package dev.pocketengine.runtime;

import dev.pocketengine.core.*;
import dev.pocketengine.runtime.providers.*;
import dev.pocketengine.runtime.usage.*;
import dev.pocketengine.testutil.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** The real API-key Responses parser, including failed ModelGateway receipt capture. */
public final class ApiIncompleteToolReceiptTest {
    private static JSONArray tools()throws Exception{return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","inspect").put("parameters",new JSONObject().put("type","object"))));}
    private static String event(String type,JSONObject value)throws Exception{return "event: "+type+"\ndata: "+new JSONObject(value.toString()).put("type",type)+"\n\n";}
    private static JSONObject call(String args,String status)throws Exception{return new JSONObject().put("type","function_call").put("id","item").put("call_id","call").put("name","inspect").put("arguments",args).put("status",status);}
    private static String prefix(String arguments,String itemStatus,boolean doneEvent)throws Exception{
        String wire=event("response.created",new JSONObject().put("response",new JSONObject().put("id","response").put("status","in_progress")))
            +event("response.output_item.added",new JSONObject().put("output_index",0).put("item",call("","in_progress")))
            +event("response.function_call_arguments.delta",new JSONObject().put("output_index",0).put("item_id","item").put("delta",arguments));
        if(doneEvent)wire+=event("response.function_call_arguments.done",new JSONObject().put("output_index",0).put("item_id","item").put("arguments",arguments));
        return wire+event("response.output_item.done",new JSONObject().put("output_index",0).put("item",call(arguments,itemStatus)));
    }
    private static String terminal(String status,JSONObject item)throws Exception{return event("response."+status,new JSONObject().put("response",new JSONObject().put("id","response").put("status",status).put("output",new JSONArray().put(item)).put("usage",new JSONObject().put("input_tokens",4).put("output_tokens",6).put("total_tokens",10))));}
    private static JSONObject read(String wire,ProviderUsage.Collector usage)throws Exception{return ApiTurnStream.read(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)),"openai_responses",ProviderAdapters.selected("openai_responses"),tools(),new RunControl(),delta->{},usage);}
    @Test public void partialArgumentsDoNotHideIncompleteOrFailedTerminalUsage()throws Exception {
        for(boolean argsDone:new boolean[]{false,true})for(String status:new String[]{"incomplete","failed","cancelled"}){
            String wire=prefix("{\"q\":","incomplete",argsDone)+terminal(status,call("{\"q\":","incomplete"));ProviderUsage.Collector usage=new ProviderUsage.Collector("openai_responses");
            assertThrows(ModelGateway.GatewayException.class,()->read(wire,usage));assertNotNull("authoritative failed usage must be retained",usage.get(ProviderUsage.Counter.INPUT));assertEquals(4,usage.get(ProviderUsage.Counter.INPUT).value);assertEquals(6,usage.get(ProviderUsage.Counter.OUTPUT).value);assertEquals(status,usage.toJson().getString("finishReason"));
        }
    }
    @Test public void incompleteItemCannotBeChangedIntoCompletedExecutableTools()throws Exception {
        String incomplete=prefix("{}","incomplete",true)+terminal("completed",call("{}","incomplete"));assertThrows(ModelGateway.GatewayException.class,()->read(incomplete,new ProviderUsage.Collector("openai_responses")));
        String wire=prefix("{}","incomplete",true)+terminal("completed",call("{}","completed"));String changed=wire;assertThrows(ModelGateway.GatewayException.class,()->read(changed,new ProviderUsage.Collector("openai_responses")));
        String valid=prefix("{}","completed",true)+terminal("completed",call("{}","completed"));assertEquals("call",read(valid,new ProviderUsage.Collector("openai_responses")).getJSONArray("tool_calls").getJSONObject(0).getString("id"));
    }
    @Test public void malformedCompletedCallsAndUnterminatedStreamsStillFailClosed()throws Exception {
        ProviderUsage.Collector usage=new ProviderUsage.Collector("openai_responses");String malformed=prefix("{\"q\":","completed",true)+terminal("completed",call("{\"q\":","completed"));assertThrows(ModelGateway.GatewayException.class,()->read(malformed,usage));assertNull(usage.get(ProviderUsage.Counter.INPUT));
        String cut=prefix("{\"q\":","incomplete",true);assertThrows(ModelGateway.GatewayException.class,()->read(cut,new ProviderUsage.Collector("openai_responses")));
        // Identity check has a separate explicit frame so JSONObject key order cannot affect the case.
        String wrong=prefix("{}","incomplete",true)+event("response.incomplete",new JSONObject().put("response",new JSONObject().put("id","other").put("status","incomplete").put("usage",new JSONObject().put("input_tokens",99))));ProviderUsage.Collector wrongUsage=new ProviderUsage.Collector("openai_responses");assertThrows(ModelGateway.GatewayException.class,()->read(wrong,wrongUsage));assertNull(wrongUsage.get(ProviderUsage.Counter.INPUT));
    }
    private static String argumentsDoneOnly(String arguments)throws Exception {
        String wire=prefix(arguments,"incomplete",true);
        return wire.substring(0,wire.lastIndexOf("event: response.output_item.done"));
    }
    private static List<String> nonRetryableCuts()throws Exception {
        return List.of(prefix("{\"q\":","incomplete",false),prefix("{}","incomplete",true),
                argumentsDoneOnly("{\"q\":"),argumentsDoneOnly("{]"),argumentsDoneOnly("[]"));
    }
    @Test public void unfinishedToolEofDoesNotBecomeRetryableTransportFailure()throws Exception {
        for(String wire:nonRetryableCuts()){
            ModelGateway.GatewayException failure=assertThrows(ModelGateway.GatewayException.class,
                    ()->read(wire,new ProviderUsage.Collector("openai_responses")));
            assertEquals("A known unfinished call cannot authorize a new POST",-1,
                    new SafeModelRecovery().delay(failure,0,System.currentTimeMillis()));
        }
    }
    @Test public void ordinaryEofKeepsItsExistingRecoveryClassification()throws Exception {
        for(String wire:List.of(event("response.created",new JSONObject().put("response",
                new JSONObject().put("id","response").put("status","in_progress"))),argumentsDoneOnly("{}"))){
            ModelGateway.GatewayException failure=assertThrows(ModelGateway.GatewayException.class,
                    ()->read(wire,new ProviderUsage.Collector("openai_responses")));
            assertEquals(InferenceFailure.Kind.INCOMPLETE_STREAM,failure.observation().kind);
            assertTrue(new SafeModelRecovery().delay(failure,0,System.currentTimeMillis())>=0);
        }
    }
    @Test public void loopbackUnfinishedToolEofNeverReplaysAnAuthorizedLogicalRequest()throws Exception {
        for(String wire:nonRetryableCuts()){
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);int[] requests={0},retryReservations={0};
            server.createContext("/",exchange->{try{requests[0]++;exchange.getRequestBody().readAllBytes();byte[] body=wire.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});server.start();
            try{
                String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
                ModelConfig config=new ModelConfig(endpoint,"fixture-key","fixture-model",false,4,10,"openai_responses",512).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_responses",endpoint));
                List<InferenceReceipt> receipts=new ArrayList<>();List<ModelGateway.StreamEvent> events=new ArrayList<>();
                InferenceCapture.Target target=new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt value){}public void finished(InferenceReceipt value){receipts.add(value);}});
                // Exercise the existing multi-attempt gateway with an explicit native test authority.
                target=InferenceCapture.withLogicalAuthority(target,new LogicalInferenceAuthority(){
                    public JSONObject begin(String id,String digest,JSONObject route,long deadline)throws Exception{return new JSONObject().put("deadlineAt",deadline).put("control",new JSONObject().put("maxAttempts",3));}
                    public boolean matchesHistoryOrigin(String id,ModelConfig candidate,JSONObject data){return false;}
                    public boolean reserveRetry(String id,String call,long wait){retryReservations[0]++;return true;}
                    public void reserve(String id,String call,int attempt,String candidate,String reason){}
                    public void commit(String id,String call,long sequence){}
                    public void attemptEnded(String id,String call,boolean dispatched,String state){}
                    public void finish(String id,String state,String completion,String stream){}
                });
                InferenceCapture.Target original=target;
                ModelGateway.GatewayException failure=assertThrows(ModelGateway.GatewayException.class,()->ModelGateway.completeStreaming(config,new JSONArray().put(new JSONObject().put("role","user").put("content","inspect")),tools(),new RunControl(),events::add,original));
                assertEquals("No second inference POST after a known unfinished call",1,requests[0]);
                assertEquals(0,retryReservations[0]);assertEquals(InferenceFailure.Kind.PROTOCOL,failure.observation().kind);
                assertEquals(1,receipts.size());assertEquals(InferenceReceipt.Outcome.FAILED,receipts.get(0).outcome);
                assertFalse(events.stream().anyMatch(e->e.kind==ModelGateway.StreamKind.COMPLETED));
            }finally{server.stop(0);}
        }
    }
    @Test public void loopbackGatewayFailedReceiptRetainsReportedTokensWithoutCompleting()throws Exception {
        String wire=prefix("{\"q\":","incomplete",true)+terminal("incomplete",call("{\"q\":","incomplete"));
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);int[] requests={0};server.createContext("/",exchange->{try{requests[0]++;exchange.getRequestBody().readAllBytes();byte[] body=wire.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});server.start();
        try{
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";ModelConfig config=new ModelConfig(endpoint,"fixture-key","fixture-model",false,4,10,"openai_responses",512).withCleartextConsent(ModelConfig.cleartextConsentFor("openai_responses",endpoint));
            List<InferenceReceipt> receipts=new ArrayList<>();List<ModelGateway.StreamEvent> events=new ArrayList<>();InferenceCapture.Target target=new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt value){}public void finished(InferenceReceipt value){receipts.add(value);}});
            assertThrows(ModelGateway.GatewayException.class,()->ModelGateway.completeStreaming(config,new JSONArray().put(new JSONObject().put("role","user").put("content","inspect")),tools(),new RunControl(),events::add,target));
            assertEquals(1,requests[0]);assertEquals(1,receipts.size());assertEquals(InferenceReceipt.Outcome.FAILED,receipts.get(0).outcome);assertFalse(events.stream().anyMatch(e->e.kind==ModelGateway.StreamKind.COMPLETED));
            JSONObject counts=receipts.get(0).toJson().getJSONObject("usage").getJSONObject("counters");assertEquals(4,counts.getJSONObject("INPUT").getLong("value"));assertEquals(6,counts.getJSONObject("OUTPUT").getLong("value"));
        }finally{server.stop(0);}
    }
}
