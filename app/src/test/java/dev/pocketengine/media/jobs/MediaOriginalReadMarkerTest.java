package dev.pocketengine.media.jobs;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.testutil.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;

/** Actual provider ACK and its original durable receipt, not a fabricated job marker/grant. */
public final class MediaOriginalReadMarkerTest {
    @Test public void confirmedOriginalReadWindowIsOnceAcrossColdReceiptAndCannotChangeSource()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger posts=new AtomicInteger();AtomicReference<String> job=new AtomicReference<>();server.createContext("/prompt",exchange->{posts.incrementAndGet();JSONObject body=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));job.set(body.getString("prompt_id"));byte[] reply=new JSONObject().put("prompt_id",job.get()).toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();});server.start();
        try{Path journal=Files.createTempDirectory("c2-read-marker-").resolve("receipt.json");String operation=UUID.randomUUID().toString(),task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();JSONObject config=new JSONObject().put("provider","comfyui").put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("revision","pinned");JSONObject graph=new JSONObject().put("6",new JSONObject().put("class_type","CLIPTextEncode").put("inputs",new JSONObject().put("text","fixture")));var sink=(MediaJobs.ArtifactSink)(p,s,c)->{throw new AssertionError("not downloading");};var jobs=new MediaJobs(new MediaJobJournal(journal.toFile()),sink,r->{});JSONObject actual=jobs.execute("submitWorkflow",new JSONObject().put("_operationId",operation).put("_taskId",task).put("_runId",run).put("workflow",graph),config,"",new RunControl());assertTrue(actual.getBoolean("providerJobIdConfirmed"));assertEquals(job.get(),actual.getString("providerJobId"));assertTrue(jobs.markOriginalReadStarted(operation,task,run,"pinned",System.currentTimeMillis()+200000));var cold=new MediaJobs(new MediaJobJournal(journal.toFile()),sink,r->{});assertFalse(cold.markOriginalReadStarted(operation,task,run,"pinned",System.currentTimeMillis()+250000));assertThrows(Exception.class,()->cold.markOriginalReadStarted(operation,UUID.randomUUID().toString(),run,"pinned",System.currentTimeMillis()+100000));assertThrows(Exception.class,()->cold.markOriginalReadStarted(operation,task,run,"new-config",System.currentTimeMillis()+100000));cold.finishOriginalReadContinuation(operation,"owner_stopped",2);assertEquals("ended",cold.get(operation).getString("originalReadContinuation"));assertEquals(2,cold.get(operation).getInt("readContinuationReads"));assertFalse(cold.get(operation).toString().contains("_readContinuationStartedAt"));assertEquals(1,posts.get());
        }finally{server.stop(0);}
    }
}
