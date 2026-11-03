package dev.pocketengine.media.jobs;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.media.MediaTransport;
import dev.pocketengine.testutil.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual loopback HTTP protocol fixtures, not a live ComfyUI/Kokoro service or model-quality test. */
public final class MediaJobsTest {
    private static final byte[] PNG = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=");
    private HttpServer server, other;
    private File journalFile, artifactsDir;
    private MediaJobs jobs;
    private JSONObject config;
    private final AtomicReference<JSONObject> posted = new AtomicReference<>();
    private final AtomicReference<String> state = new AtomicReference<>("queued"), actualId = new AtomicReference<>();
    private final AtomicInteger submissions = new AtomicInteger(), cancellations = new AtomicInteger(), globalInterrupts = new AtomicInteger(), downloads = new AtomicInteger(), imports = new AtomicInteger();
    private final AtomicInteger submitStatus = new AtomicInteger(200), downloadStatus = new AtomicInteger(200);
    private final AtomicBoolean lostAck = new AtomicBoolean(), oldServerId = new AtomicBoolean(), targetedCancel = new AtomicBoolean(), revokeDuringDownload = new AtomicBoolean(), revoked = new AtomicBoolean();
    private final AtomicReference<JSONArray> outputs = new AtomicReference<>(new JSONArray());
    private final AtomicReference<String> downloadAuth = new AtomicReference<>(), queueDelete = new AtomicReference<>();
    private final Map<String, JSONObject> imported = new LinkedHashMap<>();

    @Before public void prepare() throws Exception {
        File root = Files.createTempDirectory("b4-media-jobs-").toFile();
        journalFile = new File(root, "receipts.json"); artifactsDir = new File(root, "artifacts"); artifactsDir.mkdirs();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxy/prompt", exchange -> {
            submissions.incrementAndGet();
            JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            posted.set(body);
            actualId.set(oldServerId.get() ? UUID.randomUUID().toString() : body.getString("prompt_id"));
            if (lostAck.get()) { exchange.close(); return; }
            respond(exchange, submitStatus.get(), new JSONObject().put("prompt_id", actualId.get()).put("number", 4).put("node_errors", new JSONObject()));
        });
        server.createContext("/proxy/history", exchange -> {
            JSONObject history = new JSONObject();
            String path = exchange.getRequestURI().getPath();
            if (state.get().matches("completed|failed|cancelled") && (path.equals("/proxy/history") || path.equals("/proxy/history/" + actualId.get()))) history.put(actualId.get(), history());
            respond(exchange, 200, history);
        });
        server.createContext("/proxy/queue", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) {
                cancellations.incrementAndGet();
                queueDelete.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                state.set("missing"); respond(exchange, 200, null); return;
            }
            JSONArray running = new JSONArray(), pending = new JSONArray();
            if (state.get().equals("queued") && posted.get() != null) pending.put(queueItem());
            if (state.get().equals("running") && posted.get() != null) running.put(queueItem());
            respond(exchange, 200, new JSONObject().put("queue_running", running).put("queue_pending", pending));
        });
        server.createContext("/proxy/view", exchange -> {
            downloads.incrementAndGet(); downloadAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (revokeDuringDownload.get()) revoked.set(true);
            if (downloadStatus.get() == 302) exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + other.getAddress().getPort() + "/stolen");
            int status = downloadStatus.get();
            exchange.sendResponseHeaders(status, status == 200 ? PNG.length : 0);
            if (status == 200) exchange.getResponseBody().write(PNG);
            exchange.close();
        });
        server.createContext("/proxy/api/jobs/", exchange -> {
            cancellations.incrementAndGet();
            targetedCancel.set(exchange.getRequestURI().getPath().equals("/proxy/api/jobs/" + actualId.get() + "/cancel"));
            respond(exchange, 200, new JSONObject().put("cancelled", true));
        });
        server.createContext("/proxy/interrupt", exchange -> { globalInterrupts.incrementAndGet(); respond(exchange, 200, null); });
        server.start();
        config = new JSONObject().put("provider", "comfyui").put("baseUrl", base()).put("revision", "config-1");
        jobs = reopened();
    }
    @After public void close() { if (server != null) server.stop(0); if (other != null) other.stop(0); }
    private String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy"; }
    private MediaJobs reopened() throws Exception {
        return new MediaJobs(new MediaJobJournal(journalFile), (payload, source, control) -> {
            control.check();
            String key = source.getString("operationId") + "/" + source.getString("artifactKey");
            if (imported.containsKey(key)) return imported.get(key);
            String id = UUID.randomUUID().toString();
            Files.write(new File(artifactsDir, id).toPath(), payload.bytes);
            JSONObject artifact = new JSONObject().put("id", id).put("mimeType", payload.mimeType).put("size", payload.bytes.length)
                    .put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(payload.bytes))).put("source", source);
            imported.put(key, artifact); imports.incrementAndGet(); return artifact;
        }, receipt -> {});
    }
    private JSONObject workflow(String text) throws Exception { return new JSONObject().put("6", new JSONObject().put("class_type", "CLIPTextEncode").put("inputs", new JSONObject().put("text", text))); }
    private JSONObject request(String operation) throws Exception { return new JSONObject().put("_operationId", operation).put("_taskId", "task-1").put("_runId", "run-1").put("workflow", workflow("private prompt")); }
    private JSONObject ref(String operation) throws Exception { return new JSONObject().put("operationId", operation).put("taskId", "task-1").put("runId", "run-1"); }
    private JSONObject execute(String command, JSONObject input) throws Exception { return jobs.execute(command, input, config, "private-key", new RunControl()); }
    private JSONObject submit(String operation) throws Exception { return execute("submitWorkflow", request(operation)); }
    private JSONArray queueItem() throws Exception {
        JSONObject body = posted.get(), extra = new JSONObject(body.getJSONObject("extra_data").toString()).put("client_id", body.getString("client_id"));
        return new JSONArray().put(4).put(actualId.get()).put(body.getJSONObject("prompt")).put(extra).put(new JSONArray());
    }
    private JSONObject history() throws Exception {
        String message = state.get().equals("cancelled") ? "execution_interrupted" : state.get().equals("failed") ? "execution_error" : "execution_success";
        JSONObject status = new JSONObject().put("completed", state.get().equals("completed")).put("status_str", state.get().equals("completed") ? "success" : "error")
                .put("messages", new JSONArray().put(new JSONArray().put(message).put(new JSONObject())));
        return new JSONObject().put("prompt", queueItem()).put("status", status).put("outputs", new JSONObject().put("9", new JSONObject().put("images", outputs.get())));
    }
    private void complete(String... filenames) throws Exception {
        JSONArray files = new JSONArray();
        for (String name : filenames) files.put(new JSONObject().put("filename", name).put("subfolder", "results 中文").put("type", "output"));
        outputs.set(files); state.set("completed");
    }
    private static void respond(HttpServer.Exchange exchange, int status, JSONObject body) throws Exception {
        byte[] bytes = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static String hex(byte[] digest) { StringBuilder s = new StringBuilder(); for (byte b : digest) s.append(String.format("%02x", b & 255)); return s.toString(); }

    @Test public void submitsRealApiGraphAndReturnsAnAsyncIdentityWithoutPolling() throws Exception {
        JSONObject result = submit("op-1");
        assertEquals("queued", result.getString("status")); assertEquals(actualId.get(), result.getString("providerJobId"));
        assertTrue(result.getBoolean("providerJobIdConfirmed")); assertFalse(result.getBoolean("verified"));
        assertEquals("private prompt", posted.get().getJSONObject("prompt").getJSONObject("6").getJSONObject("inputs").getString("text"));
        assertEquals("op-1", posted.get().getJSONObject("extra_data").getJSONObject("pocketworld").getString("operationId"));
        assertEquals(1, submissions.get()); assertEquals(0, downloads.get());
        String saved = Files.readString(journalFile.toPath()); assertFalse(saved.contains("private prompt")); assertFalse(saved.contains("private-key")); assertFalse(result.toString().contains("_requestHash"));
    }

    @Test public void duplicateAndRestartNeverResubmitAndChangedInputsOrRunsAreRejected() throws Exception {
        submit("op-1"); jobs = reopened();
        assertTrue(submit("op-1").getBoolean("knownOperation"));
        assertThrows(SecurityException.class, () -> execute("submitWorkflow", request("op-1").put("workflow", workflow("changed"))));
        assertThrows(SecurityException.class, () -> execute("submitWorkflow", request("op-1").put("_runId", "new-run")));
        config.put("revision", "changed"); assertThrows(SecurityException.class, () -> submit("op-1"));
        assertEquals(1, submissions.get());
    }

    @Test public void lostAckRecoversAnOlderServersMintedIdThroughExactMarkersWithoutPostRetry() throws Exception {
        oldServerId.set(true); lostAck.set(true);
        JSONObject result = submit("op-1"); assertEquals("unknown", result.getString("status")); assertFalse(result.getBoolean("providerJobIdConfirmed"));
        jobs = reopened(); JSONObject found = execute("reconcileOperation", ref("op-1"));
        assertEquals("queued", found.getString("status")); assertEquals(actualId.get(), found.getString("providerJobId")); assertEquals(1, submissions.get());
    }

    @Test public void lostAckAlsoRecoversCompletedHistoryWithAnOlderMintedId() throws Exception {
        oldServerId.set(true); lostAck.set(true); submit("op-1"); complete("image.png");
        JSONObject found = execute("reconcileOperation", ref("op-1"));
        assertEquals("response_received", found.getString("status")); assertEquals(actualId.get(), found.getString("providerJobId")); assertEquals(1, submissions.get());
    }

    @Test public void completeOutputsAreDownloadedAsActualBytesAndRepeatedFetchDoesNotDuplicateImports() throws Exception {
        submit("op-1"); complete("image.png");
        JSONObject result = execute("fetchResults", ref("op-1"));
        assertEquals("response_received", result.getString("status")); assertEquals("saved", result.getString("resultStatus"));
        JSONObject attachment = result.getJSONArray("attachments").getJSONObject(0);
        assertArrayEquals(PNG, Files.readAllBytes(new File(artifactsDir, attachment.getString("id")).toPath()));
        assertEquals("Bearer private-key", downloadAuth.get());
        jobs = reopened(); assertEquals(attachment.getString("id"), execute("fetchResults", ref("op-1")).getJSONArray("attachments").getJSONObject(0).getString("id"));
        assertEquals(1, downloads.get()); assertEquals(1, imports.get()); assertEquals(1, submissions.get());
    }

    @Test public void downloadRedirectDoesNotLeakCredentialsOrReachAnotherServer() throws Exception {
        AtomicInteger reached = new AtomicInteger(); other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/stolen", exchange -> { reached.incrementAndGet(); respond(exchange, 200, null); }); other.start();
        submit("op-1"); complete("image.png"); downloadStatus.set(302);
        JSONObject result = execute("fetchResults", ref("op-1"));
        assertEquals("response_received", result.getString("status")); assertEquals("unavailable", result.getString("resultStatus"));
        assertEquals(0, reached.get()); assertEquals(0, imports.get()); assertEquals(1, submissions.get());
    }

    @Test public void missingArtifactKeepsGenerationReceiptAndCanRetryOnlyItsGet() throws Exception {
        submit("op-1"); complete("image.png"); downloadStatus.set(410);
        JSONObject missing = execute("fetchResults", ref("op-1")); assertEquals(410, missing.getInt("resultHttpStatus")); assertEquals("response_received", missing.getString("status"));
        downloadStatus.set(200); assertEquals("saved", execute("fetchResults", ref("op-1")).getString("resultStatus")); assertEquals(1, submissions.get()); assertEquals(2, downloads.get());
    }

    @Test public void changedConfigurationAndTaskBindingsCannotSendQueriesOrRepeatGeneration() throws Exception {
        submit("op-1");
        assertThrows(SecurityException.class, () -> execute("getJob", ref("op-1").put("runId", "new-run")));
        assertThrows(SecurityException.class, () -> execute("getJob", ref("op-1").put("_operationId", "new-operation")));
        assertThrows(SecurityException.class, () -> execute("getJob", ref("op-1").put("providerJobId", UUID.randomUUID().toString())));
        config.put("revision", "config-2"); assertThrows(SecurityException.class, () -> execute("getJob", ref("op-1")));
        assertThrows(SecurityException.class, () -> execute("submitWorkflow", request("op-2").put("_configurationRevision", "config-1")));
        assertEquals(1, submissions.get());
    }

    @Test public void endpointReplacementIsFencedEvenIfACallerReusesARevisionLabel() throws Exception {
        submit("op-1"); config.put("baseUrl", base() + "/other-tenant");
        assertThrows(SecurityException.class, () -> execute("getJob", ref("op-1")));
        assertThrows(SecurityException.class, () -> submit("op-1")); assertEquals(1, submissions.get());
    }

    @Test public void partialImportPersistsEachArtifactAndOnlyRetriesMissingGets() throws Exception {
        server.createContext("/proxy/view", exchange -> {
            downloads.incrementAndGet();
            boolean missing = exchange.getRequestURI().getQuery().contains("second.png") && downloadStatus.get() == 404;
            exchange.sendResponseHeaders(missing ? 404 : 200, missing ? 0 : PNG.length);
            if (!missing) exchange.getResponseBody().write(PNG); exchange.close();
        });
        submit("op-1"); complete("first.png", "second.png"); downloadStatus.set(404);
        JSONObject partial = execute("fetchResults", ref("op-1")); assertEquals("partial", partial.getString("resultStatus")); assertEquals(1, imports.get());
        String firstId = partial.getJSONArray("attachments").getJSONObject(0).getString("id");
        jobs = reopened(); downloadStatus.set(200);
        JSONObject result = execute("fetchResults", ref("op-1")); assertEquals("saved", result.getString("resultStatus"));
        assertEquals(firstId, result.getJSONArray("attachments").getJSONObject(0).getString("id")); assertEquals(2, imports.get()); assertEquals(3, downloads.get()); assertEquals(1, submissions.get());
    }

    @Test public void compatibleImageUsesSameReceiptLifecycleAndNeverRepeatsALostResponse() throws Exception {
        AtomicInteger requests = new AtomicInteger(); AtomicReference<String> received = new AtomicReference<>();
        server.createContext("/proxy/v1/images/generations", exchange -> {
            requests.incrementAndGet(); received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (lostAck.get()) { exchange.close(); return; }
            respond(exchange, 200, new JSONObject().put("data", new JSONArray().put(new JSONObject().put("b64_json", java.util.Base64.getEncoder().encodeToString(PNG)))));
        });
        config.put("provider", "openai_compatible").put("baseUrl", base() + "/v1").put("imageModel", "real-configured-id");
        JSONObject input = request("image-op").put("prompt", "blue cup"); input.remove("workflow");
        JSONObject reply = execute("generateImage", input); assertEquals("response_received", reply.getString("status")); assertEquals(1, imports.get());
        assertEquals("blue cup", new JSONObject(received.get()).getString("prompt")); assertEquals("real-configured-id", new JSONObject(received.get()).getString("model"));
        jobs = reopened(); assertTrue(execute("generateImage", input).getBoolean("knownOperation")); assertEquals(1, requests.get());
        lostAck.set(true); JSONObject lost = execute("generateImage", new JSONObject(input.toString()).put("_operationId", "lost-image"));
        assertEquals("unknown", lost.getString("status")); assertFalse(execute("getJob", ref("lost-image")).getBoolean("querySupported"));
        assertEquals(2, requests.get());
    }

    @Test public void compatibleTtsRequestsCompleteWaveAndStoresActualReturnedAudio() throws Exception {
        byte[] wave = java.nio.ByteBuffer.allocate(46).order(java.nio.ByteOrder.LITTLE_ENDIAN).put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(38)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000)
                .putShort((short) 2).putShort((short) 16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(2).putShort((short) 0).array();
        AtomicReference<JSONObject> body = new AtomicReference<>();
        server.createContext("/proxy/v1/audio/speech", exchange -> {
            body.set(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(200, wave.length); exchange.getResponseBody().write(wave); exchange.close();
        });
        config.put("provider", "openai_compatible").put("baseUrl", base() + "/v1").put("ttsModel", "kokoro").put("voice", "configured-voice");
        JSONObject result = execute("synthesizeSpeech", request("speech-op").put("text", "你好"));
        assertFalse(body.get().getBoolean("stream")); assertEquals("wav", body.get().getString("response_format")); assertEquals("configured-voice", body.get().getString("voice"));
        JSONObject attachment = result.getJSONArray("attachments").getJSONObject(0); assertEquals("audio/wav", attachment.getString("mimeType"));
        assertArrayEquals(wave, Files.readAllBytes(new File(artifactsDir, attachment.getString("id")).toPath()));
    }

    @Test public void unavailableAtomicCancelApiDoesNotFallBackToAnyOtherPost() throws Exception {
        server.createContext("/proxy/api/jobs/", exchange -> { cancellations.incrementAndGet(); respond(exchange, 404, null); });
        config.put("comfyJobsApi", true); submit("op-1"); state.set("running");
        JSONObject result = execute("cancelJob", ref("op-1")); assertEquals("unconfirmed", result.getString("cancelStatus")); assertEquals(404, result.getInt("cancelHttpStatus"));
        execute("cancelJob", ref("op-1")); assertEquals(1, cancellations.get()); assertNull(queueDelete.get()); assertEquals(0, globalInterrupts.get());
    }

    @Test public void pendingCancellationTargetsOnlyOriginalPromptAndNeverClaimsConfirmedTermination() throws Exception {
        submit("op-1"); JSONObject result = execute("cancelJob", ref("op-1"));
        assertEquals("stopping", result.getString("status")); assertEquals("requested", result.getString("cancelStatus"));
        JSONObject body = new JSONObject(queueDelete.get()); assertEquals(1, body.length()); assertEquals(actualId.get(), body.getJSONArray("delete").getString(0));
        assertEquals("unknown", execute("getJob", ref("op-1")).getString("status"));
        assertEquals(1, cancellations.get()); assertEquals(0, globalInterrupts.get());
    }

    @Test public void olderRunningServerDoesNotReceiveAnyInterrupt() throws Exception {
        submit("op-1"); state.set("running");
        JSONObject result = execute("cancelJob", ref("op-1")); assertEquals("running", result.getString("status")); assertEquals("unsupported_running", result.getString("cancelStatus"));
        assertEquals(0, cancellations.get()); assertEquals(0, globalInterrupts.get());
    }

    @Test public void configuredJobsApiUsesExactAtomicCancelThenWaitsForHistoryEvidence() throws Exception {
        config.put("comfyJobsApi", true); submit("op-1"); state.set("running");
        JSONObject result = execute("cancelJob", ref("op-1")); assertTrue(targetedCancel.get()); assertEquals("stopping", result.getString("status"));
        state.set("cancelled"); assertEquals("cancelled", execute("getJob", ref("op-1")).getString("status")); assertEquals(1, cancellations.get()); assertEquals(0, globalInterrupts.get());
    }

    @Test public void lostCancelAckIsReconciledWithoutRepeatingThePost() throws Exception {
        server.createContext("/proxy/queue", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) { cancellations.incrementAndGet(); exchange.close(); }
            else respond(exchange, 200, new JSONObject().put("queue_running", new JSONArray()).put("queue_pending", new JSONArray().put(queueItem())));
        });
        submit("op-1"); assertEquals("unconfirmed", execute("cancelJob", ref("op-1")).getString("cancelStatus"));
        assertEquals("query_only_after_request", execute("cancelJob", ref("op-1")).getString("cancelStatus")); assertEquals(1, cancellations.get());
    }

    @Test public void submissionServerErrorIsUnknownAndValidationRejectionIsFailed() throws Exception {
        submitStatus.set(500); assertEquals("unknown", submit("op-1").getString("status"));
        assertTrue(submit("op-1").getBoolean("knownOperation")); assertEquals(1, submissions.get());
        submitStatus.set(400); assertEquals("failed", submit("op-2").getString("status")); assertEquals(2, submissions.get());
    }

    @Test public void missingHistoryDoesNotProveFailureOrNonExecution() throws Exception {
        submit("op-1"); state.set("missing"); JSONObject result = execute("getJob", ref("op-1"));
        assertEquals("unknown", result.getString("status")); assertFalse(result.getBoolean("verified")); assertFalse(result.getBoolean("expirySupported")); assertEquals(1, submissions.get());
    }

    @Test public void traversalAndTooManyOutputsAreRejectedBeforeAnyViewDownload() throws Exception {
        submit("op-1"); complete("../private.png"); assertEquals("unknown", execute("fetchResults", ref("op-1")).getString("status")); assertEquals(0, downloads.get());
        complete("1.png", "2.png", "3.png", "4.png", "5.png", "6.png", "7.png", "8.png", "9.png");
        assertEquals("unknown", execute("fetchResults", ref("op-1")).getString("status")); assertEquals(0, downloads.get()); assertEquals(1, submissions.get());
    }

    @Test public void cancelledControlAndRevokedAuthorityCannotSubmitOrImportAfterRevocation() throws Exception {
        RunControl stopped = new RunControl(); stopped.cancel();
        assertThrows(InterruptedException.class, () -> jobs.execute("submitWorkflow", request("op-1"), config, "key", stopped)); assertEquals(0, submissions.get());
        submit("op-2"); complete("image.png"); revokeDuringDownload.set(true);
        RunControl live = new RunControl(); live.setAuthorityCheck(() -> { if (revoked.get()) throw new SecurityException("revoked"); });
        JSONObject result = jobs.execute("fetchResults", ref("op-2"), config, "private-key", live);
        assertEquals("unavailable", result.getString("resultStatus")); assertEquals(0, imports.get()); assertTrue(live.isCancelled());
    }

    @Test public void interruptedSubmissionRecordRecoversToUnknownWithoutResubmitting() throws Exception {
        submit("op-1"); JSONObject saved = new JSONObject(Files.readString(journalFile.toPath()));
        saved.getJSONObject("receipts").getJSONObject("op-1").put("status", "submitting"); Files.writeString(journalFile.toPath(), saved.toString());
        jobs = reopened(); assertEquals("unknown", jobs.get("op-1").getString("status")); assertTrue(submit("op-1").getBoolean("knownOperation")); assertEquals(1, submissions.get());
    }

    @Test public void malformedJournalFailsClosedAndReceiptCapacityBlocksBeforeNetwork() throws Exception {
        Files.writeString(journalFile.toPath(), "broken"); assertThrows(Exception.class, () -> reopened());
        JSONObject receipts = new JSONObject(); for (int i = 0; i < MediaJobJournal.MAX_RECEIPTS; i++) receipts.put("old-" + i, new JSONObject().put("operationId", "old-" + i).put("status", "unknown"));
        Files.writeString(journalFile.toPath(), new JSONObject().put("version", 1).put("receipts", receipts).toString()); jobs = reopened();
        byte[] before = Files.readAllBytes(journalFile.toPath());
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> submit("new-op"));
        assertEquals("CapacityExceededException", failure.getClass().getSimpleName());
        assertEquals("new-op", failure.getClass().getField("operationId").get(failure));
        assertEquals(0, submissions.get()); assertArrayEquals(before, Files.readAllBytes(journalFile.toPath()));
        assertEquals(MediaJobJournal.MAX_RECEIPTS, jobs.list().length());
        MediaJobJournal journal = new MediaJobJournal(journalFile);
        journal.put(journal.get("old-0").put("message", "original reconciliation remains available"));
        assertEquals(MediaJobJournal.MAX_RECEIPTS, new MediaJobJournal(journalFile).list().length());
        assertEquals("original reconciliation remains available", new MediaJobJournal(journalFile).get("old-0").getString("message"));
    }

    @Test public void ordinaryJournalWriteFailureIsNotAConfirmedCapacityRefusal() throws Exception {
        assertTrue(journalFile.mkdir()); File unrelated = new File(journalFile, "unrelated"); Files.writeString(unrelated.toPath(), "preserve");
        Exception failure = assertThrows(Exception.class, () -> submit("new-op"));
        assertNotEquals("CapacityExceededException", failure.getClass().getSimpleName()); assertEquals(0, submissions.get());
        assertEquals("preserve", Files.readString(unrelated.toPath()));
    }

    @Test public void hashesIgnoreObjectKeyOrderAndWorkflowDepthIsBounded() throws Exception {
        assertEquals(MediaJobJournal.hash(new JSONObject("{\"a\":1,\"b\":2}")), MediaJobJournal.hash(new JSONObject("{\"b\":2,\"a\":1}")));
        JSONObject deep = new JSONObject(), root = deep; for (int i = 0; i < 40; i++) { JSONObject next = new JSONObject(); deep.put("n", next); deep = next; }
        assertThrows(IllegalArgumentException.class, () -> MediaJobJournal.hash(root)); assertEquals(0, submissions.get());
    }
}
