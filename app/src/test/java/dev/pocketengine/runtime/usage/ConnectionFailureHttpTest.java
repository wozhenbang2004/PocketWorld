package dev.pocketengine.runtime.usage;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.ToolOutputCapture;
import dev.pocketengine.runtime.ModelGateway;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Current Gateway and real loopback sockets, not live provider/credential/device acceptance. */
public final class ConnectionFailureHttpTest {
    private static final class Sink implements InferenceCapture.Sink {
        final List<InferenceReceipt> started=new ArrayList<>(),finished=new ArrayList<>();
        public void started(InferenceReceipt receipt){started.add(receipt);}public void finished(InferenceReceipt receipt){finished.add(receipt);}
        InferenceCapture.Target target(){return new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),this);}
    }
    private static final class Server implements AutoCloseable {
        final HttpServer http;final AtomicInteger calls=new AtomicInteger();volatile int status=429;volatile String retry="120",contentType="application/json",body="{\"error\":\"fixture-private-key body must not be persisted\"}";
        Server()throws Exception {http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/",exchange->{calls.incrementAndGet();try{exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type",contentType);if(retry!=null)exchange.getResponseHeaders().set("Retry-After",retry);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception ignored){}finally{exchange.close();}});http.start();}
        ModelConfig config(){return config("fixture-private-key");}
        ModelConfig config(String key){return new ModelConfig("http://127.0.0.1:"+http.getAddress().getPort(),key,"fixture-model",false,4,10,"openai_chat",512);}
        @Override public void close(){http.stop(0);}
    }
    private JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","PRIVATE_OWNER_PROMPT"));}
    @Test public void bufferedAndStreamHttpFailuresKeepActualStatusAndRetrySourceWithNoReplayOrBody()throws Exception {
        for(boolean stream:new boolean[]{false,true})for(int status:new int[]{401,403,404,429,503})try(Server server=new Server()) {
            server.status=status;Sink sink=new Sink();RunControl control=new RunControl();
            assertThrows(ModelGateway.GatewayException.class,()->{if(stream)ModelGateway.completeStreaming(server.config(),messages(),null,control,event->{},sink.target());else ModelGateway.complete(server.config(),messages(),null,control,sink.target());});
            assertEquals(1,server.calls.get());assertEquals(1,sink.started.size());assertEquals(1,sink.finished.size());JSONObject receipt=sink.finished.get(0).toJson();assertEquals("FAILED",receipt.getString("outcome"));assertEquals(sink.started.get(0).callId,receipt.getString("callId"));
            JSONObject failure=receipt.getJSONObject("failure");assertEquals(status,failure.getInt("httpStatus"));assertEquals("DELTA_SECONDS_LOCAL_RECEIPT",failure.getJSONObject("retry").getString("basis"));assertEquals(120,failure.getJSONObject("retry").getLong("delaySeconds"));assertFalse(receipt.toString().contains("fixture-private-key"));assertFalse(receipt.toString().contains("PRIVATE_OWNER_PROMPT"));assertFalse(control.isCancelled());
        }
        try(Server server=new Server()) {
            Sink sink=new Sink();assertThrows(ModelGateway.GatewayException.class,()->ModelGateway.complete(server.config("120"),messages(),null,new RunControl(),sink.target()));
            JSONObject retry=sink.finished.get(0).toJson().getJSONObject("failure").getJSONObject("retry");assertEquals("INVALID",retry.getString("status"));assertTrue(retry.isNull("delaySeconds"));assertEquals(1,server.calls.get());
        }
    }
    @Test public void realHttpDateHeaderProducesAnEstimateNotAHealthOrQuotaReset()throws Exception {
        try(Server server=new Server()) {
            server.status=503;server.retry=DateTimeFormatter.ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'",java.util.Locale.US).format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(60));Sink sink=new Sink();long before=System.currentTimeMillis();assertThrows(ModelGateway.GatewayException.class,()->ModelGateway.complete(server.config(),messages(),null,new RunControl(),sink.target()));long after=System.currentTimeMillis();
            JSONObject failure=sink.finished.get(0).toJson().getJSONObject("failure"),retry=failure.getJSONObject("retry");assertEquals("VALID",retry.getString("status"));assertTrue(retry.getLong("estimatedNotBefore")>=before+58000);assertTrue(retry.getLong("estimatedNotBefore")<=after+62000);assertFalse(retry.getBoolean("healthVerified"));assertFalse(retry.getBoolean("quotaResetKnown"));assertEquals(1,server.calls.get());
        }
    }
    @Test public void partialSseNeverBecomesCompletedDecisionOrReplayedRequestAndMissingUsageStaysUnknown()throws Exception {
        try(Server server=new Server()) {
            server.status=200;server.retry=null;server.contentType="text/event-stream";server.body="data: {\"id\":\"partial\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"VISIBLE_PARTIAL_ONLY\"},\"finish_reason\":null}]}\n\n";
            Sink sink=new Sink();List<ModelGateway.StreamEvent> events=new ArrayList<>();assertThrows(ModelGateway.GatewayException.class,()->ModelGateway.completeStreaming(server.config(),messages(),null,new RunControl(),events::add,sink.target()));
            assertEquals(1,server.calls.get());assertEquals(1,sink.finished.size());JSONObject receipt=sink.finished.get(0).toJson();assertEquals("FAILED",receipt.getString("outcome"));assertTrue(receipt.getJSONObject("failure").getBoolean("partialTextReceived"));assertEquals(200,receipt.getJSONObject("failure").getInt("httpStatus"));assertTrue(receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").isNull("value"));assertFalse(events.stream().anyMatch(e->e.kind==ModelGateway.StreamKind.COMPLETED));assertFalse(receipt.toString().contains("VISIBLE_PARTIAL_ONLY"));
        }
    }
    @Test public void ownerCancelAfterTextKeepsCancelledReceiptWithoutPretendingProviderDidNotProcessIt()throws Exception {
        try(Server server=new Server()) {
            server.status=200;server.retry=null;server.contentType="text/event-stream";server.body="data: {\"id\":\"partial\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n";
            Sink sink=new Sink();RunControl control=new RunControl();assertThrows(InterruptedException.class,()->ModelGateway.completeStreaming(server.config(),messages(),null,control,event->{if(event.kind==ModelGateway.StreamKind.TEXT_DELTA)control.cancel();},sink.target()));
            JSONObject receipt=sink.finished.get(0).toJson();assertEquals("CANCELLED",receipt.getString("outcome"));assertTrue(receipt.getJSONObject("failure").getBoolean("partialTextReceived"));assertFalse(receipt.getJSONObject("failure").getBoolean("providerProcessingKnown"));assertFalse(receipt.getJSONObject("failure").getBoolean("chargeKnown"));assertEquals(1,server.calls.get());
        }
    }
    @Test public void exactNativeOutputSourceRefusalEscapesGatewayBeforeWireWithoutBeingTurnedIntoOwnerCancel()throws Exception {
        try(Server server=new Server()) {
            Sink sink=new Sink();RunControl control=new RunControl();ToolOutputCapture.OutputSourceUnavailable original=new ToolOutputCapture.OutputSourceUnavailable(null);
            InferenceCapture.Target target=InferenceCapture.withAdmission(sink.target(),()->{throw original;});Exception seen=assertThrows(ToolOutputCapture.OutputSourceUnavailable.class,()->ModelGateway.completeStreaming(server.config(),messages(),null,control,event->{},target));
            assertSame(original,seen);assertEquals(0,server.calls.get());assertTrue(sink.started.isEmpty());assertTrue(sink.finished.isEmpty());assertFalse(control.isCancelled());
        }
    }
}
