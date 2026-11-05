package dev.pocketengine.core;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class RuntimeDiagnosticsTest {
    @Test public void privateInputsAndExceptionMessagesNeverEnterLogcatOrSnapshots() throws Exception {
        List<String> logcat = new ArrayList<>();
        RuntimeDiagnostics.Recorder log = new RuntimeDiagnostics.Recorder(null, logcat::add);
        String secret = "Bearer sk-private-token https://callback/?code=private-auth-code prompt-secret";
        String task = UUID.randomUUID().toString(), run = UUID.randomUUID().toString();
        RunControl control = RunControl.forOriginalRecovery(task, run, 7);
        IllegalStateException failure = new IllegalStateException(secret, new java.io.IOException(secret));
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("dev.pocketengine.runtime.ModelGateway", "complete", "/private/secret.java", 42)});
        log.record("model_request", "failed", secret, control, secret, UUID.randomUUID().toString(), 120, failure);
        String encoded = log.snapshot().toString() + logcat;
        for (String value : new String[]{"sk-private-token", "private-auth-code", "prompt-secret", "/private/secret.java", "callback"}) assertFalse(encoded.contains(value));
        JSONObject row = log.snapshot().getJSONArray("events").getJSONObject(0);
        assertEquals("UNSPECIFIED", row.getString("code"));
        assertEquals(task, row.getString("taskId")); assertEquals(run, row.getString("runId")); assertEquals(7, row.getInt("epoch"));
        assertEquals(32, row.getString("referenceHash").length());
        assertEquals("java.io.IOException", row.getJSONArray("exceptionTypes").getString(1));
        assertEquals("dev.pocketengine.runtime.ModelGateway.complete:42", row.getJSONArray("frames").getString(0));
        assertEquals(1, logcat.size());
    }

    @Test public void concurrentWritersRetainOnlyTheBoundedNewestEvents() throws Exception {
        RuntimeDiagnostics.Recorder log = new RuntimeDiagnostics.Recorder(null, null);
        var workers = Executors.newFixedThreadPool(4); CountDownLatch done = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) workers.execute(() -> {
            try { for (int n = 0; n < 200; n++) log.record("tool_execution", "succeeded", "OK", null, null, UUID.randomUUID().toString(), n, null); }
            finally { done.countDown(); }
        });
        try { assertTrue(done.await(5, TimeUnit.SECONDS)); } finally { workers.shutdownNow(); }
        JSONArray rows = log.snapshot().getJSONArray("events"); assertEquals(RuntimeDiagnostics.MAX_EVENTS, rows.length());
        var unique = new java.util.HashSet<String>();
        for (int i = 0; i < rows.length(); i++) assertTrue(unique.add(rows.getJSONObject(i).getString("eventId")));
    }

    @Test public void rotationAndRestartRemainBoundedAndRejectInjectedStoredFields() throws Exception {
        File directory = Files.createTempDirectory("runtime-log-test").toFile();
        RuntimeDiagnostics.Recorder log = new RuntimeDiagnostics.Recorder(directory, null);
        Exception failure = new IllegalStateException("never persist this");
        StackTraceElement[] frames = new StackTraceElement[8];
        for (int i = 0; i < frames.length; i++) frames[i] = new StackTraceElement("dev.pocketengine." + "LongClass".repeat(20), "failure", "ignored", i);
        failure.setStackTrace(frames);
        for (int i = 0; i < 500; i++) log.record("model_request", "failed", "PROVIDER_UNAVAILABLE", null, null, UUID.randomUUID().toString(), i, failure);
        log.close(); assertTrue(log.awaitWrites(10000));
        File current = new File(directory, "runtime.jsonl"), previous = new File(directory, "runtime.previous.jsonl");
        assertTrue(current.isFile()); assertTrue(previous.isFile());
        assertTrue(current.length() <= RuntimeDiagnostics.FILE_CAP_BYTES); assertTrue(previous.length() <= RuntimeDiagnostics.FILE_CAP_BYTES);
        JSONObject last = new JSONObject(Files.readAllLines(current.toPath(), StandardCharsets.UTF_8).get(0));
        last.put("message", "must-not-be-restored");
        Files.write(current.toPath(), (last + "\n").getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        RuntimeDiagnostics.Recorder restored = new RuntimeDiagnostics.Recorder(directory, null);
        restored.close(); assertTrue(restored.awaitWrites(10000));
        JSONObject snapshot = restored.snapshot();
        assertTrue(snapshot.getInt("retainedEvents") > 0); assertTrue(snapshot.getInt("retainedEvents") <= RuntimeDiagnostics.MAX_EVENTS);
        assertFalse(snapshot.toString().contains("must-not-be-restored")); assertTrue(snapshot.getLong("unreadableRecords") > 0);
    }

    @Test public void unavailableDiskAndFailingOutputDoNotReplaceBusinessFailures() throws Exception {
        File notDirectory = Files.createTempFile("runtime-log-no-directory", ".tmp").toFile();
        RuntimeDiagnostics.Recorder log = new RuntimeDiagnostics.Recorder(notDirectory, line -> { throw new IllegalStateException("sink failed"); });
        log.record("login", "failed", "LOGIN_FAILED", null, null, UUID.randomUUID().toString(), 0, new IllegalArgumentException("original"));
        log.close(); assertTrue(log.awaitWrites(5000));
        assertEquals(1, log.snapshot().getInt("retainedEvents")); assertTrue(log.snapshot().getLong("fileWriteFailures") > 0);
    }

    @Test public void oversizedOldFilesAreDiscardedWithoutRestoringOrRetainingTheirBytes() throws Exception {
        File directory = Files.createTempDirectory("runtime-log-oversized").toFile();
        for (String name : new String[]{"runtime.jsonl", "runtime.previous.jsonl"})
            Files.write(new File(directory, name).toPath(), new byte[RuntimeDiagnostics.FILE_CAP_BYTES + 1]);
        RuntimeDiagnostics.Recorder log = new RuntimeDiagnostics.Recorder(directory, null);
        log.record("bootstrap", "succeeded", "OK", null, null, UUID.randomUUID().toString(), 1, null);
        log.close(); assertTrue(log.awaitWrites(5000));
        assertEquals(2, log.snapshot().getLong("unreadableRecords")); assertEquals(1, log.snapshot().getInt("retainedEvents"));
        long retained = new File(directory, "runtime.jsonl").length() + new File(directory, "runtime.previous.jsonl").length();
        assertTrue(retained <= 2L * RuntimeDiagnostics.FILE_CAP_BYTES);
    }

    @Test public void spansHaveOneTerminalReceiptAndPreserveOperationIdentity() throws Exception {
        RuntimeDiagnostics.install(null, null);
        try (RuntimeDiagnostics.Span span = RuntimeDiagnostics.begin("account_refresh", null, "account-private-name")) {
            span.failure("PROVIDER_UNAVAILABLE", new java.io.IOException("secret response")); span.success();
        }
        JSONArray rows = RuntimeDiagnostics.snapshot().getJSONArray("events");
        assertEquals(2, rows.length());
        assertEquals(rows.getJSONObject(0).getString("operationId"), rows.getJSONObject(1).getString("operationId"));
        assertEquals("failed", rows.getJSONObject(1).getString("outcome"));
        assertTrue(rows.getJSONObject(1).getLong("durationMs") >= 0);
    }
}
