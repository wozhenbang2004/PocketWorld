package dev.pocketengine.media.jobs;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.media.MediaTransport;
import dev.pocketengine.testutil.HttpServer;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real receipt journal and loopback HTTP; the artifact seam retains original files across reopen. */
public final class MediaJobsRecoveryBudgetTest {
    private static final int ONE_OUTPUT = MediaTransport.MAX_MEDIA_BYTES;
    private static final long TOTAL = 40L * 1024 * 1024;
    private static final byte[] PNG = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=");
    private final AtomicInteger posts = new AtomicInteger(), downloads = new AtomicInteger(), imports = new AtomicInteger(), recoveries = new AtomicInteger();
    private final Map<String, JSONObject> originals = new LinkedHashMap<>();
    private File root, journalFile;
    private HttpServer server;
    private MediaJobJournal journal;
    private MediaJobs jobs;
    private JSONObject config, originalIdentity;
    private JSONArray outputs;
    private volatile int responseBytes = PNG.length;
    private boolean crashAfterImport, failLookup;

    private static final class ImportCommittedBeforeReceipt extends Error {}

    @Before public void prepare() throws Exception {
        root = Files.createTempDirectory("media-recovery-budget-").toFile();
        journalFile = new File(root, "receipts.json");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxy/prompt", exchange -> {
            posts.incrementAndGet();
            JSONObject request = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = new JSONObject().put("prompt_id", request.getString("prompt_id")).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length); exchange.getResponseBody().write(reply); exchange.close();
        });
        server.createContext("/proxy/view", exchange -> {
            downloads.incrementAndGet();
            int size = responseBytes;
            exchange.sendResponseHeaders(200, size);
            exchange.getResponseBody().write(PNG);
            byte[] padding = new byte[32768];
            for (int left = size - PNG.length; left > 0; left -= Math.min(left, padding.length)) {
                exchange.getResponseBody().write(padding, 0, Math.min(left, padding.length));
            }
            exchange.close();
        });
        server.start();
        config = new JSONObject().put("provider", "comfyui").put("revision", "original-config")
                .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy");
        reopen();
        jobs.execute("submitWorkflow", request(), config, "fixture-key", new RunControl());
        outputs = new JSONArray();
        for (String name : new String[]{"first.png", "second.png", "third.png"}) {
            outputs.put(new JSONObject().put("nodeId", "9").put("outputKind", "images").put("filename", name).put("subfolder", "").put("type", "output"));
        }
        JSONObject record = journal.get("original-operation");
        record.put("status", "response_received").put("providerState", "completed").put("outputs", outputs)
                .put("_outputsHash", MediaJobJournal.hash(outputs));
        journal.put(record);
        originalIdentity = identity(record);
    }

    @After public void close() throws Exception {
        if (server != null) server.stop(0);
        if (root != null) try (var paths = Files.walk(root.toPath())) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private void reopen() throws Exception {
        journal = new MediaJobJournal(journalFile);
        jobs = new MediaJobs(journal, new MediaJobs.ArtifactSink() {
            @Override public JSONObject save(MediaTransport.Payload payload, JSONObject source, RunControl control) throws Exception {
                control.check();
                String key = source.getString("artifactKey");
                assertFalse("recovery must not reimport the original", originals.containsKey(key));
                File file = new File(root, "original-" + key);
                Files.write(file.toPath(), payload.bytes);
                JSONObject artifact = descriptor(file, source);
                originals.put(key, artifact); imports.incrementAndGet();
                if (crashAfterImport) { crashAfterImport = false; throw new ImportCommittedBeforeReceipt(); }
                return artifact;
            }
            @Override public JSONObject find(JSONObject source, RunControl control) throws Exception {
                control.check();
                if (failLookup && source.getString("artifactKey").equals(MediaJobJournal.hash(outputs.getJSONObject(2)))) throw new java.io.IOException("fixture lookup failed");
                JSONObject original = originals.get(source.getString("artifactKey"));
                if (original != null) {
                    assertEquals(MediaJobJournal.hash(original.getJSONObject("source")), MediaJobJournal.hash(source));
                    assertEquals(original.getLong("size"), new File(root, original.getString("id")).length());
                    recoveries.incrementAndGet();
                }
                return original;
            }
        }, receipt -> {});
    }

    private JSONObject request() throws Exception {
        return new JSONObject().put("_operationId", "original-operation").put("_taskId", "original-task").put("_runId", "original-run")
                .put("workflow", new JSONObject().put("6", new JSONObject().put("class_type", "CLIPTextEncode")
                        .put("inputs", new JSONObject().put("text", "fixture"))));
    }
    private JSONObject fetch() throws Exception {
        return jobs.execute("fetchResults", new JSONObject().put("operationId", "original-operation"), config, "fixture-key", new RunControl());
    }
    private JSONObject source(int index) throws Exception {
        JSONObject record = journal.get("original-operation");
        return new JSONObject().put("kind", "generated_media").put("operationId", record.getString("operationId"))
                .put("taskId", record.getString("taskId")).put("runId", record.getString("runId")).put("provider", record.getString("provider"))
                .put("providerJobId", record.getString("providerJobId")).put("artifactKey", MediaJobJournal.hash(outputs.getJSONObject(index)))
                .put("serviceOrigin", "127.0.0.1").put("configurationRevision", record.getString("configurationRevision"))
                .put("requestIdentity", record.getString("_requestHash"));
    }
    private JSONObject descriptor(File file, JSONObject source) throws Exception {
        return new JSONObject().put("id", file.getName()).put("mimeType", "image/png").put("size", file.length()).put("source", source);
    }
    /** Sparse, signature-bearing original fixtures avoid retaining 40 MiB of repeated padding per test. */
    private JSONObject original(int index, int size, boolean recorded) throws Exception {
        JSONObject source = source(index); String key = source.getString("artifactKey");
        File file = new File(root, "original-" + key);
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) { output.write(PNG); output.setLength(size); }
        JSONObject artifact = descriptor(file, source); originals.put(key, artifact);
        if (recorded) {
            JSONObject record = journal.get("original-operation");
            record.getJSONArray("attachments").put(artifact);
            if (record.getJSONArray("attachments").length() == 1) record.put("attachment", artifact);
            journal.put(record);
        }
        return artifact;
    }
    private static JSONObject identity(JSONObject record) throws Exception {
        JSONObject identity = new JSONObject();
        for (String key : new String[]{"operationId", "taskId", "runId", "provider", "providerJobId", "configurationRevision", "_configurationHash", "_requestHash", "_clientId", "_outputsHash"}) identity.put(key, record.get(key));
        return identity;
    }
    private void unchangedIdentity() throws Exception {
        assertEquals(MediaJobJournal.hash(originalIdentity), MediaJobJournal.hash(identity(journal.get("original-operation"))));
        assertTrue(jobs.execute("submitWorkflow", request(), config, "fixture-key", new RunControl()).getBoolean("knownOperation"));
        assertEquals("no recovery may replay the generation POST", 1, posts.get());
    }
    private static long bytes(JSONObject receipt) throws Exception {
        long result = 0; JSONArray attachments = receipt.getJSONArray("attachments");
        for (int i = 0; i < attachments.length(); i++) result += attachments.getJSONObject(i).getLong("size");
        return result;
    }

    @Test public void committedImportRecoversOnceAndExhaustedBudgetBlocksTheNextGetAcrossRestarts() throws Exception {
        JSONObject first = original(0, ONE_OUTPUT, true);
        responseBytes = ONE_OUTPUT; crashAfterImport = true;
        assertThrows(ImportCommittedBeforeReceipt.class, this::fetch);
        assertEquals(1, journal.get("original-operation").getJSONArray("attachments").length());
        assertEquals(2, originals.size()); assertEquals(1, imports.get()); assertEquals(1, downloads.get());
        reopen(); responseBytes = PNG.length;
        JSONObject recovered = fetch();
        assertEquals("partial", recovered.getString("resultStatus")); assertEquals(TOTAL, bytes(recovered));
        assertEquals(first.getString("id"), recovered.getJSONArray("attachments").getJSONObject(0).getString("id"));
        assertEquals(2, recovered.getJSONArray("attachments").length()); assertEquals(1, recoveries.get());
        reopen(); assertEquals("partial", fetch().getString("resultStatus"));
        assertEquals(1, recoveries.get()); assertEquals(1, downloads.get()); assertEquals(1, imports.get());
        for (JSONObject artifact : originals.values()) assertEquals(ONE_OUTPUT, new File(root, artifact.getString("id")).length());
        unchangedIdentity();
    }

    @Test public void recoveredOriginalAndNewOutputOneByteOverRemainingBudgetNeverImportsTheNewOutput() throws Exception {
        original(0, ONE_OUTPUT, true); original(1, ONE_OUTPUT - PNG.length + 1, false);
        JSONObject partial = fetch();
        assertEquals("partial", partial.getString("resultStatus")); assertEquals(2, partial.getJSONArray("attachments").length());
        assertEquals(TOTAL - PNG.length + 1, bytes(partial)); assertEquals(1, downloads.get()); assertEquals(0, imports.get());
        reopen(); assertEquals("partial", fetch().getString("resultStatus"));
        assertEquals(1, recoveries.get()); assertEquals(0, imports.get()); assertEquals(2, originals.size()); unchangedIdentity();
    }

    @Test public void recoveredOriginalAndNewOutputAtExactBoundaryCompleteWithoutDoubleChargingOnReopen() throws Exception {
        original(0, ONE_OUTPUT, true); original(1, ONE_OUTPUT - PNG.length, false);
        JSONObject complete = fetch();
        assertEquals("saved", complete.getString("resultStatus")); assertEquals(3, complete.getJSONArray("attachments").length());
        assertEquals(TOTAL, bytes(complete)); assertEquals(1, downloads.get()); assertEquals(1, imports.get()); assertEquals(1, recoveries.get());
        reopen(); JSONObject again = fetch(); assertEquals("saved", again.getString("resultStatus")); assertEquals(TOTAL, bytes(again));
        assertEquals(1, downloads.get()); assertEquals(1, imports.get()); assertEquals(1, recoveries.get()); unchangedIdentity();
    }

    @Test public void recoveredLastOriginalAtExactBoundaryCompletesWithoutAnyDownload() throws Exception {
        outputs.remove(2);
        JSONObject record = journal.get("original-operation");
        record.put("outputs", outputs).put("_outputsHash", MediaJobJournal.hash(outputs)); journal.put(record);
        originalIdentity = identity(record);
        original(0, ONE_OUTPUT, true); original(1, ONE_OUTPUT, false);
        assertEquals("saved", fetch().getString("resultStatus"));
        reopen(); JSONObject again = fetch(); assertEquals("saved", again.getString("resultStatus"));
        assertEquals(TOTAL, bytes(again)); assertEquals(1, recoveries.get());
        assertEquals(0, downloads.get()); assertEquals(0, imports.get()); unchangedIdentity();
    }

    @Test public void recoveredOriginalOverTheLimitRemainsInATruthfulPartialReceiptWithoutDeletingAnything() throws Exception {
        original(0, ONE_OUTPUT, true); original(1, ONE_OUTPUT, true); JSONObject orphan = original(2, PNG.length, false);
        JSONObject partial = fetch();
        assertEquals("partial", partial.getString("resultStatus")); assertEquals(3, partial.getJSONArray("attachments").length());
        assertEquals(orphan.getString("id"), partial.getJSONArray("attachments").getJSONObject(2).getString("id"));
        assertEquals(TOTAL + PNG.length, bytes(partial)); assertEquals(0, downloads.get()); assertEquals(0, imports.get());
        reopen(); assertEquals("partial", fetch().getString("resultStatus")); assertEquals(1, recoveries.get());
        assertEquals(3, originals.size()); assertEquals(PNG.length, new File(root, orphan.getString("id")).length()); unchangedIdentity();
    }

    @Test public void lookupFailureKeepsTheRecoveredReceiptAndLaterRetryCountsItOnlyOnce() throws Exception {
        original(0, ONE_OUTPUT, true); original(1, ONE_OUTPUT - PNG.length, false); failLookup = true;
        JSONObject partial = fetch();
        assertEquals("partial", partial.getString("resultStatus")); assertEquals(2, partial.getJSONArray("attachments").length());
        assertEquals(0, downloads.get()); assertEquals(0, imports.get());
        reopen(); failLookup = false; JSONObject complete = fetch();
        assertEquals("saved", complete.getString("resultStatus")); assertEquals(TOTAL, bytes(complete));
        assertEquals(1, recoveries.get()); assertEquals(1, downloads.get()); assertEquals(1, imports.get()); unchangedIdentity();
    }
}
