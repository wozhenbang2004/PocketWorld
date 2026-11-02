package dev.pocketengine.runtime.usage;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.providers.ApiTurnStream;
import dev.pocketengine.runtime.providers.ProviderAdapters;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real loopback transport plus native counters. No live provider or Android billing claim. */
public final class InferenceUsageTest {
    private static JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","private fixture prompt"));}
    private static String frame(JSONObject value){return "data: "+value+"\n\n";}
    private static JSONObject chunk(String text,String finish)throws Exception{
        return new JSONObject().put("id","shared-provider-id").put("object","chat.completion.chunk").put("choices",new JSONArray()
                .put(new JSONObject().put("index",0).put("delta",new JSONObject().put("content",text))
                        .put("finish_reason",finish==null?JSONObject.NULL:finish)));
    }
    private static JSONObject usage(long input,long output)throws Exception{
        return new JSONObject().put("prompt_tokens",input).put("completion_tokens",output).put("total_tokens",input+output)
                .put("prompt_tokens_details",new JSONObject().put("cached_tokens",7))
                .put("completion_tokens_details",new JSONObject().put("reasoning_tokens",2));
    }
    private static String chatBody()throws Exception{
        JSONObject report=new JSONObject().put("id","shared-provider-id").put("object","chat.completion.chunk")
                .put("choices",new JSONArray()).put("usage",usage(10,3));
        return frame(chunk("visible fixture answer","stop"))+frame(report)+frame(report)+"data: [DONE]\n\n";
    }
    private static JSONObject count(InferenceReceipt receipt,String counter)throws Exception{
        return receipt.toJson().getJSONObject("usage").getJSONObject("counters").getJSONObject(counter);
    }
    private static final class Captured implements InferenceCapture.Sink {
        final List<InferenceReceipt> started=new ArrayList<>(),finished=new ArrayList<>();
        public synchronized void started(InferenceReceipt value){started.add(value);}
        public synchronized void finished(InferenceReceipt value){finished.add(value);}
        InferenceCapture.Target target(){return new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),this);}
    }
    private static final class Server implements AutoCloseable {
        final HttpServer http;final AtomicInteger requests=new AtomicInteger();
        volatile String body,contentType="text/event-stream";volatile JSONObject sent;
        Server(String body)throws Exception{this.body=body;http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            http.createContext("/",exchange->{requests.incrementAndGet();try{
                sent=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                byte[] bytes=this.body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",contentType);
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception ignored){}finally{exchange.close();}});http.start();}
        ModelConfig config(String protocol){return new ModelConfig("http://127.0.0.1:"+http.getAddress().getPort(),"fixture-key","fixture-model",false,4,10,protocol,512);}
        public void close(){http.stop(0);}
    }
    @Test public void chatUsageOnlySnapshotsDoNotDoubleCountOrEnterHistory()throws Exception{
        try(Server server=new Server(chatBody())){
            Captured capture=new Captured();List<ModelGateway.StreamEvent> events=new ArrayList<>();JSONArray history=messages();String before=history.toString();
            JSONObject result=ModelGateway.completeStreaming(server.config("openai_chat"),history,null,new RunControl(),events::add,capture.target());
            assertEquals("visible fixture answer",result.getString("content"));assertFalse(result.has("usage"));assertEquals(before,history.toString());
            assertTrue(server.sent.getJSONObject("stream_options").getBoolean("include_usage"));
            assertEquals(1,capture.started.size());assertEquals(1,capture.finished.size());InferenceReceipt receipt=capture.finished.get(0);
            assertEquals(InferenceReceipt.Outcome.COMPLETED,receipt.outcome);assertEquals(10,count(receipt,"INPUT").getLong("value"));
            assertEquals(3,count(receipt,"OUTPUT").getLong("value"));assertEquals(7,count(receipt,"CACHE_READ").getLong("value"));
            assertEquals(2,count(receipt,"REASONING").getLong("value"));assertEquals(receipt.callId,events.get(0).requestId);
            String encoded=receipt.toJson().toString();assertFalse(encoded.contains("private fixture prompt"));assertFalse(encoded.contains("visible fixture answer"));assertFalse(encoded.contains("fixture-key"));
        }
    }
    @Test public void repeatedProviderIdsAreStillTwoActualCallsAndJsonNegotiationIsOne()throws Exception{
        try(Server server=new Server(chatBody())){
            Captured captured=new Captured();ModelConfig config=server.config("openai_chat");
            ModelGateway.completeStreaming(config,messages(),null,new RunControl(),event->{},captured.target());
            server.contentType="application/json";server.body=new JSONObject().put("id","shared-provider-id").put("choices",new JSONArray()
                    .put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content","answer"))))
                    .put("usage",usage(20,4)).toString();
            ModelGateway.completeStreaming(config,messages(),null,new RunControl(),event->{},captured.target());
            assertEquals(2,server.requests.get());assertEquals(2,captured.finished.size());assertNotEquals(captured.finished.get(0).callId,captured.finished.get(1).callId);
            assertEquals(20,count(captured.finished.get(1),"INPUT").getLong("value"));
        }
    }
    @Test public void ordinaryJsonAlsoCapturesUsageWithoutChangingAssistant()throws Exception{
        try(Server server=new Server(new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content","ok"))))
                .put("usage",usage(40,5)).toString())){
            Captured captured=new Captured();JSONObject answer=ModelGateway.complete(server.config("openai_chat"),messages(),null,new RunControl(),captured.target());
            assertFalse(answer.has("usage"));assertEquals(40,count(captured.finished.get(0),"INPUT").getLong("value"));
        }
    }
    @Test public void anthropicStartAndCumulativeDeltaMergeWithoutAddingCacheTwice()throws Exception{
        String body=frame(new JSONObject().put("type","message_start").put("message",new JSONObject().put("id","a").put("type","message").put("role","assistant").put("content",new JSONArray())
                .put("usage",new JSONObject().put("input_tokens",3).put("output_tokens",1).put("cache_read_input_tokens",100).put("cache_creation_input_tokens",20))))
                +frame(new JSONObject().put("type","content_block_start").put("index",0).put("content_block",new JSONObject().put("type","text").put("text","answer")))
                +frame(new JSONObject().put("type","content_block_stop").put("index",0))
                +frame(new JSONObject().put("type","message_delta").put("delta",new JSONObject()).put("usage",new JSONObject().put("output_tokens",4)))
                +frame(new JSONObject().put("type","message_delta").put("delta",new JSONObject().put("stop_reason","end_turn")).put("usage",new JSONObject().put("output_tokens",6)
                .put("output_tokens_details",new JSONObject().put("thinking_tokens",5))))
                +frame(new JSONObject().put("type","message_stop"));
        ProviderUsage.Collector collector=read("anthropic",body);
        assertEquals(3,collector.get(ProviderUsage.Counter.INPUT).value);assertEquals(6,collector.get(ProviderUsage.Counter.OUTPUT).value);
        assertEquals(100,collector.get(ProviderUsage.Counter.CACHE_READ).value);assertFalse(collector.toJson().getBoolean("inputIncludesCacheRead"));
        assertEquals(5,collector.get(ProviderUsage.Counter.REASONING).value);assertTrue(collector.toJson().getBoolean("outputIncludesReasoning"));
        assertNull(collector.get(ProviderUsage.Counter.TOTAL));assertEquals(ProviderUsage.Stage.FINAL,collector.get(ProviderUsage.Counter.INPUT).stage);
    }
    @Test public void geminiCandidatesAndThoughtsRemainDistinctAndMissingIsUnknown()throws Exception{
        JSONObject raw=new JSONObject().put("candidates",new JSONArray().put(new JSONObject().put("finishReason","STOP").put("content",new JSONObject()
                .put("role","model").put("parts",new JSONArray().put(new JSONObject().put("text","answer"))))))
                .put("usageMetadata",new JSONObject().put("promptTokenCount",11).put("candidatesTokenCount",3).put("thoughtsTokenCount",5).put("totalTokenCount",19));
        ProviderUsage.Collector collector=read("gemini",frame(raw));
        assertEquals(3,collector.get(ProviderUsage.Counter.OUTPUT).value);assertEquals(5,collector.get(ProviderUsage.Counter.REASONING).value);
        assertEquals(19,collector.get(ProviderUsage.Counter.TOTAL).value);assertFalse(collector.toJson().getBoolean("outputIncludesReasoning"));
        assertTrue(collector.toJson().getJSONObject("counters").getJSONObject("CACHE_READ").isNull("value"));
    }
    @Test public void failedResponsesRetainRealCountersButNeverReleaseAssistant()throws Exception{
        for(String terminal:new String[]{"response.incomplete","response.failed","response.cancelled"}){
            ProviderUsage.Collector collector=new ProviderUsage.Collector("openai_responses");
            String body=frame(new JSONObject().put("type","response.created").put("sequence_number",0).put("response",new JSONObject().put("id","r").put("status","in_progress")))
                    +frame(new JSONObject().put("type",terminal).put("sequence_number",1).put("response",new JSONObject().put("id","r")
                    .put("usage",new JSONObject().put("input_tokens",80).put("output_tokens",10))));
            try{read("openai_responses",body,collector);fail();}catch(ModelGateway.GatewayException expected){}
            assertEquals(80,collector.get(ProviderUsage.Counter.INPUT).value);assertEquals(10,collector.get(ProviderUsage.Counter.OUTPUT).value);
        }
    }
    @Test public void malformedCountersCannotBecomeZeroOrArithmeticOverflow()throws Exception{
        ProviderUsage.Collector collector=new ProviderUsage.Collector("openai_responses");
        collector.observe(new JSONObject().put("usage",new JSONObject().put("input_tokens","123").put("output_tokens",-1)
                .put("total_tokens",new java.math.BigInteger("9223372036854775808"))),ProviderUsage.Stage.FINAL);
        for(ProviderUsage.Counter counter:new ProviderUsage.Counter[]{ProviderUsage.Counter.INPUT,ProviderUsage.Counter.OUTPUT,ProviderUsage.Counter.TOTAL})assertNull(collector.get(counter));
        assertEquals(3,collector.toJson().getJSONArray("invalidFields").length());
    }
    @Test public void registrationFailurePreventsNetworkAndFinalPersistenceFailureNeverReturnsTools()throws Exception{
        try(Server server=new Server(chatBody())){
            InferenceCapture.Target noStart=new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){
                public void started(InferenceReceipt receipt)throws Exception{throw new java.io.IOException("storage");}public void finished(InferenceReceipt receipt){fail();}});
            try{ModelGateway.completeStreaming(server.config("openai_chat"),messages(),null,new RunControl(),event->{},noStart);fail();}catch(ModelGateway.GatewayException expected){}
            assertEquals(0,server.requests.get());List<ModelGateway.StreamEvent> events=new ArrayList<>();
            InferenceCapture.Target noFinish=new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){
                public void started(InferenceReceipt receipt){}public void finished(InferenceReceipt receipt)throws Exception{throw new java.io.IOException("storage");}});
            try{ModelGateway.completeStreaming(server.config("openai_chat"),messages(),null,new RunControl(),events::add,noFinish);fail();}catch(ModelGateway.GatewayException expected){}
            assertEquals(1,server.requests.get());assertEquals(ModelGateway.StreamKind.FAILED,events.get(events.size()-1).kind);
            assertFalse(events.stream().anyMatch(event->event.kind==ModelGateway.StreamKind.COMPLETED));
        }
    }
    @Test public void cancelledStreamKeepsAlreadyReportedPartialUsage()throws Exception{
        String prefix=frame(new JSONObject().put("id","shared-provider-id").put("object","chat.completion.chunk")
                .put("choices",new JSONArray()).put("usage",usage(20,1)));
        try(Server server=new Server(prefix+frame(chunk("visible",null)))){
            RunControl control=new RunControl();Captured captured=new Captured();
            try{ModelGateway.completeStreaming(server.config("openai_chat"),messages(),null,control,event->{if(event.kind==ModelGateway.StreamKind.TEXT_DELTA)control.cancel();},captured.target());fail();}
            catch(InterruptedException expected){}
            assertEquals(1,captured.finished.size());assertEquals(InferenceReceipt.Outcome.CANCELLED,captured.finished.get(0).outcome);
            assertEquals(20,count(captured.finished.get(0),"INPUT").getLong("value"));
            assertEquals("PARTIAL",count(captured.finished.get(0),"INPUT").getString("stage"));
        }
    }
    @Test public void exactNativeTaskContextCannotBeAttachedToAnotherHostRun()throws Exception{
        RunControl control=new RunControl();control.bindHostRun("task","run",1);Captured capture=new Captured();
        InferenceCapture.Target target=new InferenceCapture.Target(new InferenceReceipt.Context("task","other-run",1,InferenceReceipt.Purpose.MAIN,""),capture);
        try(Server server=new Server(chatBody())){
            try{ModelGateway.completeStreaming(server.config("openai_chat"),messages(),null,control,event->{},target);fail();}catch(SecurityException expected){}
            assertEquals(0,server.requests.get());assertTrue(capture.started.isEmpty());
        }
    }
    @Test public void projectionDeduplicatesHistoryWithoutCountingCacheAndReasoningAgain()throws Exception{
        Captured captured=new Captured();
        try(Server server=new Server(chatBody())){
            ModelGateway.completeStreaming(server.config("openai_chat"),messages(),null,new RunControl(),event->{},captured.target());
            InferenceReceipt started=captured.started.get(0),finished=captured.finished.get(0);
            JSONArray replay=new JSONArray().put(started.toJson()).put(finished.toJson()).put(finished.toJson()).put(started.toJson());
            String before=replay.toString();JSONObject projection=InferenceProjection.fromEvents(replay);
            assertEquals(before,replay.toString());assertEquals(1,projection.getInt("attempts"));assertEquals(1,projection.getInt("completed"));
            JSONObject counters=projection.getJSONArray("groups").getJSONObject(0).getJSONObject("counters");
            assertEquals(10,counters.getJSONObject("INPUT").getLong("knownSubtotal"));
            assertEquals(3,counters.getJSONObject("OUTPUT").getLong("knownSubtotal"));
            assertEquals(13,counters.getJSONObject("TOTAL").getLong("knownSubtotal"));
            assertTrue(counters.getJSONObject("CACHE_WRITE").isNull("knownSubtotal"));
            assertEquals(1,counters.getJSONObject("CACHE_WRITE").getInt("unknownCalls"));
            assertTrue(projection.isNull("actualCost"));
        }
    }
    @Test public void conflictingHistoricalReceiptCannotSilentlyIncreaseUsage()throws Exception{
        Captured captured=new Captured();
        try(Server server=new Server(chatBody())){
            ModelGateway.completeStreaming(server.config("openai_chat"),messages(),null,new RunControl(),event->{},captured.target());
            JSONObject original=captured.finished.get(0).toJson(),changed=new JSONObject(original.toString());
            changed.getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").put("value",999);
            JSONObject projection=InferenceProjection.fromEvents(new JSONArray().put(original).put(changed));
            assertEquals(1,projection.getInt("attempts"));assertEquals(1,projection.getInt("conflictingCalls"));
            assertEquals(0,projection.getJSONArray("groups").length());assertTrue(projection.getJSONArray("receipts").getJSONObject(0).getBoolean("projectionConflict"));
        }
    }
    @Test public void aCredentialPastedIntoAModelLabelCannotEnterAccounting()throws Exception{
        Captured captured=new Captured();String key="fixture-api-secret-987";
        InferenceCapture capture=new InferenceCapture(new ModelConfig("https://fixture.example",key,"prefix-"+key,false,4,10),new RunControl(),captured.target());
        capture.dispatch();capture.usage.observe(new JSONObject().put("model",key).put("usage",usage(1,2)),ProviderUsage.Stage.FINAL);capture.finish(InferenceReceipt.Outcome.COMPLETED);
        JSONObject receipt=captured.finished.get(0).toJson();assertFalse(receipt.toString().contains(key));assertTrue(receipt.getJSONObject("route").isNull("requestedModel"));
        assertTrue(receipt.getJSONObject("route").isNull("responseModel"));assertTrue(receipt.getJSONObject("route").getBoolean("identityRedacted"));
    }
    private static ProviderUsage.Collector read(String protocol,String body)throws Exception{
        ProviderUsage.Collector collector=new ProviderUsage.Collector(protocol);read(protocol,body,collector);return collector;
    }
    private static void read(String protocol,String body,ProviderUsage.Collector collector)throws Exception{
        ApiTurnStream.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),protocol,ProviderAdapters.selected(protocol),null,new RunControl(),delta->{},collector);
    }
}
