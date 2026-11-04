package dev.pocketengine.runtime.providers;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.providers.bedrock.BedrockEventStream;
import dev.pocketengine.runtime.usage.ProviderUsage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual binary decoder and original accumulator. No Gateway, SQLite or AWS execution claim. */
public final class BedrockStreamIntegrationTest {
    private static final String PROTOCOL="bedrock_anthropic";
    private static final String METRICS="amazon-bedrock-invocationMetrics";
    private static JSONArray tools()throws Exception {
        return new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject()
                .put("name","inspect").put("parameters",new JSONObject().put("type","object"))));
    }
    private static JSONObject event(String type)throws Exception {return new JSONObject().put("type",type);}
    private static JSONObject start()throws Exception {
        return event("message_start").put("message",new JSONObject().put("id","message-one").put("type","message")
                .put("role","assistant").put("content",new JSONArray()).put("usage",new JSONObject().put("input_tokens",3)
                        .put("output_tokens",1).put("cache_read_input_tokens",100).put("cache_creation_input_tokens",20)));
    }
    private static JSONObject block(int index,JSONObject content)throws Exception {return event("content_block_start").put("index",index).put("content_block",content);}
    private static JSONObject delta(int index,JSONObject change)throws Exception {return event("content_block_delta").put("index",index).put("delta",change);}
    private static JSONObject stopBlock(int index)throws Exception {return event("content_block_stop").put("index",index);}
    private static JSONObject finish(String reason)throws Exception {
        return event("message_delta").put("delta",new JSONObject().put("stop_reason",reason)).put("usage",new JSONObject().put("output_tokens",6));
    }
    private static byte[] textStream(JSONObject stop)throws Exception {
        return events(start(),block(0,new JSONObject().put("type","text").put("text","")),
                delta(0,new JSONObject().put("type","text_delta").put("text","visible answer")),stopBlock(0),finish("end_turn"),stop);
    }
    private static byte[] toolStream(String name,String arguments,String reason)throws Exception {
        return events(start(),block(0,new JSONObject().put("type","tool_use").put("id","tool-one").put("name",name).put("input",new JSONObject())),
                delta(0,new JSONObject().put("type","input_json_delta").put("partial_json",arguments)),stopBlock(0),finish(reason),event("message_stop"));
    }
    private static JSONObject read(InputStream input,RunControl control,ProviderUsage.Collector usage,ApiTurnStream.TextSink sink)throws Exception {
        return ApiTurnStream.readBedrock(input,ProviderAdapters.selected(PROTOCOL),tools(),control,sink,usage);
    }
    private static JSONObject read(byte[] bytes,ProviderUsage.Collector usage)throws Exception {
        return read(new ByteArrayInputStream(bytes),new RunControl(),usage,text->{});
    }
    @Test public void messageStopWaitsForCleanEofBeforeCompletingOrPromotingUsage()throws Exception {
        ProviderUsage.Collector usage=new ProviderUsage.Collector(PROTOCOL);RunControl control=new RunControl();
        GateEof input=new GateEof(textStream(event("message_stop")));ExecutorService executor=Executors.newSingleThreadExecutor();
        StringBuilder visible=new StringBuilder();
        try{
            Future<JSONObject> pending=executor.submit(()->read(input,control,usage,visible::append));
            assertTrue(input.atEof.await(2,TimeUnit.SECONDS));assertFalse(pending.isDone());
            assertEquals("visible answer",visible.toString());assertEquals("end_turn",usage.toJson().getString("finishReason"));
            assertEquals(ProviderUsage.Stage.PARTIAL,usage.get(ProviderUsage.Counter.INPUT).stage);
            input.release.countDown();JSONObject result=pending.get(2,TimeUnit.SECONDS);
            assertEquals("visible answer",result.getString("content"));assertEquals(PROTOCOL,result.getJSONObject("provider_data").getString("provider"));
            assertEquals(ProviderUsage.Stage.FINAL,usage.get(ProviderUsage.Counter.INPUT).stage);
            assertEquals(6,usage.get(ProviderUsage.Counter.OUTPUT).value);assertNull(usage.get(ProviderUsage.Counter.TOTAL));
        }finally{input.release.countDown();executor.shutdownNow();executor.awaitTermination(2,TimeUnit.SECONDS);}
    }
    @Test public void cancellingAtTheTransportBoundaryKeepsActualPartialCountersAndNoResult()throws Exception {
        ProviderUsage.Collector usage=new ProviderUsage.Collector(PROTOCOL);RunControl control=new RunControl();
        GateEof input=new GateEof(toolStream("inspect","{}","tool_use"));ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            Future<JSONObject> pending=executor.submit(()->read(input,control,usage,text->fail("Tool arguments became visible text")));
            assertTrue(input.atEof.await(2,TimeUnit.SECONDS));assertFalse(pending.isDone());control.cancel();input.release.countDown();
            ExecutionException failure=assertThrows(ExecutionException.class,()->pending.get(2,TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertEquals(3,usage.get(ProviderUsage.Counter.INPUT).value);assertEquals(6,usage.get(ProviderUsage.Counter.OUTPUT).value);
            assertEquals(ProviderUsage.Stage.PARTIAL,usage.get(ProviderUsage.Counter.OUTPUT).stage);
        }finally{input.release.countDown();executor.shutdownNow();executor.awaitTermination(2,TimeUnit.SECONDS);}
    }
    @Test public void splitBinaryFramesUseTheOriginalToolAccumulatorAndOpaqueHistory()throws Exception {
        byte[] bytes=events(start(),block(0,new JSONObject().put("type","thinking").put("thinking","")),
                delta(0,new JSONObject().put("type","thinking_delta").put("thinking","private reasoning")),
                delta(0,new JSONObject().put("type","signature_delta").put("signature","native-signature")),stopBlock(0),
                block(1,new JSONObject().put("type","tool_use").put("id","tool-one").put("name","inspect").put("input",new JSONObject())),
                delta(1,new JSONObject().put("type","input_json_delta").put("partial_json","{\"city\":")),
                delta(1,new JSONObject().put("type","input_json_delta").put("partial_json","\"北京\"}")),stopBlock(1),finish("tool_use"),event("message_stop"));
        InputStream fragmented=new FilterInputStream(new ByteArrayInputStream(bytes)){
            @Override public int read(byte[] into,int offset,int length)throws IOException{return super.read(into,offset,Math.min(1,length));}
        };
        ProviderUsage.Collector usage=new ProviderUsage.Collector(PROTOCOL);
        JSONObject result=read(fragmented,new RunControl(),usage,text->fail("Reasoning/tool input became answer text"));
        JSONObject call=result.getJSONArray("tool_calls").getJSONObject(0);
        assertEquals("tool-one",call.getString("id"));assertEquals("北京",new JSONObject(call.getJSONObject("function").getString("arguments")).getString("city"));
        assertEquals("native-signature",result.getJSONObject("provider_data").getJSONArray("items").getJSONObject(0).getString("signature"));
        assertEquals(PROTOCOL,usage.toJson().getString("protocol"));assertEquals(ProviderUsage.Stage.FINAL,usage.get(ProviderUsage.Counter.OUTPUT).stage);
    }
    @Test public void legalInvocationMetadataIsIgnoredWithoutOverwritingUsageOrInventingTotals()throws Exception {
        JSONObject metrics=new JSONObject().put(METRICS,new JSONObject().put("inputTokenCount",999).put("outputTokenCount",888));
        for(byte[] bytes:new byte[][]{
                join(textStream(event("message_stop")),events(metrics)),
                textStream(event("message_stop").put(METRICS,metrics.getJSONObject(METRICS))),
                join(textStream(event("message_stop").put(METRICS,metrics.getJSONObject(METRICS))),events(metrics,metrics))}){
            ProviderUsage.Collector usage=new ProviderUsage.Collector(PROTOCOL);JSONObject result=read(bytes,usage);
            assertEquals("visible answer",result.getString("content"));assertFalse(result.toString().contains(METRICS));
            assertEquals(3,usage.get(ProviderUsage.Counter.INPUT).value);assertEquals(6,usage.get(ProviderUsage.Counter.OUTPUT).value);
            assertNull(usage.get(ProviderUsage.Counter.TOTAL));assertTrue(usage.toJson().isNull("actualCost"));
        }
    }
    @Test public void unknownMetadataWrongShapeAndAnyTypedPostTerminalEventAreRejected()throws Exception {
        byte[] complete=textStream(event("message_stop"));
        for(JSONObject trailer:new JSONObject[]{new JSONObject().put("unknown",new JSONObject()),new JSONObject().put(METRICS,new JSONArray()),
                new JSONObject().put(METRICS,new JSONObject()).put("extra",true),event("message_stop"),event("ping"),
                event("message_delta").put("delta",new JSONObject()).put("usage",new JSONObject().put("output_tokens",999))})
            assertRejected(join(complete,events(trailer)));
        assertRejected(join(events(new JSONObject().put(METRICS,new JSONObject())),complete));
        assertRejected(textStream(event("message_stop").put(METRICS,new JSONArray())));
    }
    @Test public void lateChecksumTruncationAndServiceExceptionsCannotReleaseTheCandidateDecision()throws Exception {
        byte[] complete=toolStream("inspect","{}","tool_use");
        byte[] trailer=events(new JSONObject().put(METRICS,new JSONObject()));
        byte[] badCrc=trailer.clone();badCrc[badCrc.length-1]^=1;
        for(byte[] late:new byte[][]{badCrc,Arrays.copyOf(trailer,trailer.length-1),new byte[]{0,0,0},
                wireFrame("exception",":exception-type","modelStreamErrorException","{}".getBytes(StandardCharsets.UTF_8))}){
            ProviderUsage.Collector usage=new ProviderUsage.Collector(PROTOCOL);
            assertThrows(BedrockEventStream.Failure.class,()->read(join(complete,late),usage));
            assertEquals(3,usage.get(ProviderUsage.Counter.INPUT).value);assertEquals(6,usage.get(ProviderUsage.Counter.OUTPUT).value);
            assertEquals(ProviderUsage.Stage.PARTIAL,usage.get(ProviderUsage.Counter.OUTPUT).stage);
        }
    }
    @Test public void incompleteWrongIdentityOrUnofferedToolCallsNeverBecomeDecisions()throws Exception {
        assertRejected(events(start(),block(0,new JSONObject().put("type","text").put("text","temporary")),finish("end_turn"),event("message_stop")));
        assertRejected(events(start(),block(0,new JSONObject().put("type","text").put("text","temporary")),stopBlock(0),finish("end_turn")));
        assertRejected(toolStream("not_offered","{}","tool_use"));assertRejected(toolStream("inspect","{}","end_turn"));
        for(String reason:new String[]{"max_tokens","refusal","future_unknown"})assertRejected(toolStream("inspect","{}",reason));
        assertRejected(events(start(),block(0,new JSONObject().put("type","tool_use").put("id","same").put("name","inspect").put("input",new JSONObject())),stopBlock(0),
                block(1,new JSONObject().put("type","tool_use").put("id","same").put("name","inspect").put("input",new JSONObject())),stopBlock(1),finish("tool_use"),event("message_stop")));
    }
    @Test public void argumentsRetainStrictJsonAndUnicodeValidationAfterBinaryDecoding()throws Exception {
        for(String arguments:new String[]{"{\"half\":","[]","null","{} trailing","{\"x\":1,\"x\":2}","{\"x\":\"\\ud800\"}"})
            assertRejected(toolStream("inspect",arguments,"tool_use"));
        assertRejected(chunk("{\"type\":\"message_start\",\"type\":\"message_stop\"}".getBytes(StandardCharsets.UTF_8)));
        assertRejected(chunk(new byte[]{(byte)0xc3,(byte)0x28}));
    }
    @Test public void messageDeltaUsageIsCumulativeAndCleanEofDoesNotFillMissingCacheValues()throws Exception {
        JSONObject initial=start();initial.getJSONObject("message").getJSONObject("usage").remove("cache_creation_input_tokens");
        byte[] bytes=events(initial,block(0,new JSONObject().put("type","text").put("text","answer")),stopBlock(0),
                event("message_delta").put("delta",new JSONObject()).put("usage",new JSONObject().put("output_tokens",4)),
                finish("end_turn"),event("message_stop"));
        ProviderUsage.Collector usage=new ProviderUsage.Collector(PROTOCOL);read(bytes,usage);
        assertEquals(6,usage.get(ProviderUsage.Counter.OUTPUT).value);assertEquals(3,usage.get(ProviderUsage.Counter.INPUT).value);
        assertEquals(ProviderUsage.Stage.FINAL,usage.get(ProviderUsage.Counter.OUTPUT).stage);
        assertNull(usage.get(ProviderUsage.Counter.CACHE_WRITE));assertNull(usage.get(ProviderUsage.Counter.TOTAL));
    }
    private static void assertRejected(byte[] bytes)throws Exception {
        ProviderUsage.Collector usage=new ProviderUsage.Collector(PROTOCOL);
        assertThrows(Exception.class,()->read(bytes,usage));
        if(usage.get(ProviderUsage.Counter.INPUT)!=null)assertEquals(ProviderUsage.Stage.PARTIAL,usage.get(ProviderUsage.Counter.INPUT).stage);
    }
    private static byte[] events(JSONObject... events)throws Exception {
        ByteArrayOutputStream all=new ByteArrayOutputStream();
        for(JSONObject event:events)all.write(chunk(event.toString().getBytes(StandardCharsets.UTF_8)));
        return all.toByteArray();
    }
    private static byte[] chunk(byte[] inner)throws Exception {
        byte[] envelope=new JSONObject().put("bytes",Base64.getEncoder().encodeToString(inner)).toString().getBytes(StandardCharsets.UTF_8);
        return wireFrame("event",":event-type","chunk",envelope);
    }
    /** Fixture encoder only: the production SDK-derived decoder reads these actual framed bytes. */
    private static byte[] wireFrame(String messageType,String typeHeader,String eventType,byte[] body)throws Exception {
        ByteArrayOutputStream headerBytes=new ByteArrayOutputStream();DataOutputStream headers=new DataOutputStream(headerBytes);
        header(headers,":message-type",messageType);header(headers,typeHeader,eventType);header(headers,":content-type","application/json");
        byte[] encodedHeaders=headerBytes.toByteArray();int total=16+encodedHeaders.length+body.length;
        ByteBuffer message=ByteBuffer.allocate(total).putInt(total).putInt(encodedHeaders.length);
        CRC32 prelude=new CRC32();prelude.update(message.array(),0,8);message.putInt((int)prelude.getValue()).put(encodedHeaders).put(body);
        CRC32 crc=new CRC32();crc.update(message.array(),0,total-4);message.putInt((int)crc.getValue());return message.array();
    }
    private static void header(DataOutputStream into,String name,String value)throws Exception {
        byte[] key=name.getBytes(StandardCharsets.UTF_8),text=value.getBytes(StandardCharsets.UTF_8);
        into.writeByte(key.length);into.write(key);into.writeByte(7);into.writeShort(text.length);into.write(text);
    }
    private static byte[] join(byte[]... pieces)throws IOException {
        ByteArrayOutputStream all=new ByteArrayOutputStream();for(byte[] piece:pieces)all.write(piece);return all.toByteArray();
    }
    private static final class GateEof extends InputStream {
        final CountDownLatch atEof=new CountDownLatch(1),release=new CountDownLatch(1);
        private final ByteArrayInputStream source;
        GateEof(byte[] bytes){source=new ByteArrayInputStream(bytes);}
        @Override public int read()throws IOException {int next=source.read();if(next<0)gate();return next;}
        @Override public int read(byte[] into,int offset,int length)throws IOException {if(length==0)return 0;int count=source.read(into,offset,length);if(count<0)gate();return count;}
        private void gate()throws IOException {
            atEof.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new IOException("Fixture EOF was not released");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Fixture reader interrupted",interrupted);}
        }
    }
}
