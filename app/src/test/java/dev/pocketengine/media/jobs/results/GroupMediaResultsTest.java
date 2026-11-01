package dev.pocketengine.media.jobs.results;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.attachments.AttachmentBook;
import dev.pocketengine.media.jobs.MediaJobJournal;
import dev.pocketengine.media.jobs.MediaJobs;
import dev.pocketengine.testutil.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.pocketengine.media.jobs.results.GroupResultAccess.*;

/** Existing B4 HTTP/journal/book + native-root port fixtures; not the latest root group bridge or live QQ. */
public final class GroupMediaResultsTest {
    private static final String TASK = "11111111-1111-4111-8111-111111111111";
    private static final String RUN = "22222222-2222-4222-8222-222222222222", RESUMED = "33333333-3333-4333-8333-333333333333";
    private static final String REQUEST = "request-original", SPACE = "group:fixture", OUTPUT = "output-original", ARTIFACT = "artifact-group", OP = "media-original";
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJu0AAAAASUVORK5CYII=");
    private HttpServer server;
    private File root, journalFile;
    private JSONObject config;
    private volatile JSONObject posted;
    private MediaJobs jobs;
    private NativePort nativePort;
    private GroupMediaResults results;
    private final AtomicInteger posts = new AtomicInteger(), queries = new AtomicInteger(), downloads = new AtomicInteger();
    private final AtomicBoolean dropAck = new AtomicBoolean(), remoteComplete = new AtomicBoolean();

    @Before public void start() throws Exception {
        root = Files.createTempDirectory("b4-group-result-").toFile(); journalFile = new File(root, "media-journal.json");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxy/prompt", exchange -> {
            posts.incrementAndGet(); posted = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (dropAck.get()) { exchange.close(); return; }
            respond(exchange, new JSONObject().put("prompt_id", posted.getString("prompt_id")));
        });
        server.createContext("/proxy/audio/speech", exchange -> { posts.incrementAndGet(); exchange.getRequestBody().readAllBytes(); exchange.close(); });
        server.createContext("/proxy/history", exchange -> {
            queries.incrementAndGet(); JSONObject history = new JSONObject();
            if (remoteComplete.get() && posted != null) history.put(posted.getString("prompt_id"), new JSONObject().put("prompt", queueItem())
                    .put("status", new JSONObject().put("completed", true).put("status_str", "success").put("messages", new JSONArray()))
                    .put("outputs", new JSONObject().put("save", new JSONObject().put("images", new JSONArray().put(new JSONObject().put("filename", "fixture.png").put("subfolder", "").put("type", "output"))))));
            respond(exchange, history);
        });
        server.createContext("/proxy/queue", exchange -> {
            queries.incrementAndGet(); respond(exchange, new JSONObject().put("queue_running", new JSONArray())
                    .put("queue_pending", posted != null && !remoteComplete.get() ? new JSONArray().put(queueItem()) : new JSONArray()));
        });
        server.createContext("/proxy/view", exchange -> { downloads.incrementAndGet(); exchange.sendResponseHeaders(200, PNG.length); exchange.getResponseBody().write(PNG); exchange.close(); });
        server.start();
        config = new JSONObject().put("provider", "comfyui").put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy").put("revision", "fixture-original-service");
        reopen();
    }
    @After public void stop() { if (server != null) server.stop(0); }
    private static void respond(HttpServer.Exchange exchange, JSONObject object) throws Exception {
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    private JSONArray queueItem() throws Exception {
        JSONObject extra = new JSONObject(posted.getJSONObject("extra_data").toString()).put("client_id", posted.getString("client_id"));
        return new JSONArray().put(1).put(posted.getString("prompt_id")).put(posted.getJSONObject("prompt")).put(extra);
    }
    private void reopen() throws Exception {
        nativePort = new NativePort();
        AttachmentBook book = new AttachmentBook(new File(root, "original-private-media"));
        jobs = new MediaJobs(new MediaJobJournal(journalFile), (payload, source, control) -> {
            JSONObject original = book.importBytes(new ByteArrayInputStream(payload.bytes), "fixture.png", payload.mimeType, source);
            // A native scoped-copy truth fixture, not the unread latest root implementation.
            Files.write(nativePort.blob().toPath(), payload.bytes);
            JSONObject recorded = new JSONObject().put("origin", original).put("source", source);
            Files.writeString(nativePort.metadataFile().toPath(), recorded.toString());
            return original;
        }, receipt -> {});
        results = new GroupMediaResults(nativePort);
    }
    private Selection jobSelection() { return new Selection(REQUEST, TASK, SPACE, "", "", "group", "members", OP, RUN); }
    private Selection fileSelection() { return new Selection(REQUEST, TASK, SPACE, OUTPUT, ARTIFACT, "group", "members", nativePort.media ? OP : "file-operation", RUN); }
    private JSONObject submit() throws Exception {
        nativePort.approval = "approved"; nativePort.taskState = "running"; nativePort.saveTask();
        JSONObject graph = new JSONObject().put("save", new JSONObject().put("class_type", "FixtureSave").put("inputs", new JSONObject()));
        return jobs.execute("submitWorkflow", new JSONObject().put("workflow", graph).put("_operationId", OP).put("_taskId", TASK).put("_runId", RUN), config, "fixture-credential", new RunControl());
    }
    private void ready() throws Exception { submit(); remoteComplete.set(true); results.refreshOriginalMedia(jobSelection(), Refresh.ORIGINAL_ARTIFACTS, new RunControl()); }
    private static String digest(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return value.toString();
    }

    /** Native-only test authority and persisted fixture outbox/metadata, never supplied by a model or selection. */
    private final class NativePort implements GroupResultAccess {
        String taskState = "needs_confirmation", approval = "pending", taskRun = RUN;
        int taskEpoch = 1, opened, receiptReads, refreshes;
        boolean media = true, revoked, revokeOnRead, cancelOnRead, closed, noReceipt, deliveryUnavailable;
        JSONObject receiptOverride;
        Artifact artifactOverride;
        Delivery deliveryOverride;
        NativePort() throws Exception {
            File task = new File(root, "fixture-native-task.json");
            if (task.exists()) { JSONObject value = new JSONObject(Files.readString(task.toPath())); taskState = value.getString("state"); approval = value.getString("approval"); taskRun = value.getString("run"); taskEpoch = value.getInt("epoch"); }
        }
        File blob() { return new File(root, "fixture-group-artifact.blob"); }
        File metadataFile() { return new File(root, "fixture-group-artifact.json"); }
        void saveTask() throws Exception { Files.writeString(new File(root, "fixture-native-task.json").toPath(), new JSONObject().put("state", taskState).put("approval", approval).put("run", taskRun).put("epoch", taskEpoch).toString()); }
        void saveDelivery(String state, String uploadState, String messageId, boolean ack, String error) throws Exception {
            Files.writeString(new File(root, "fixture-native-delivery.json").toPath(), new JSONObject().put("state", state).put("upload", uploadState).put("messageId", messageId).put("ack", ack).put("error", error).toString());
        }
        @Override public Context inspect(Selection selected, RunControl control) throws Exception {
            control.check(); if (revoked) throw new SecurityException("fixture current reader revoked");
            JSONObject record = null; try { record = jobs.get(OP); } catch (IllegalArgumentException absent) { }
            boolean exists = metadataFile().exists();
            Selection actual = new Selection(REQUEST, TASK, SPACE, exists ? OUTPUT : "", exists ? ARTIFACT : "", "group", "members", "", "");
            return new Context(actual, taskRun, taskEpoch, 7, taskState, approval, media ? OP : "file-operation", RUN, 1,
                    media && record != null ? record.optString("providerJobId") : "", media && record != null && record.optBoolean("providerJobIdConfirmed"), media);
        }
        @Override public void checkRead(Context context, RunControl control) throws Exception {
            control.check(); if (revoked || !context.actual.taskId.equals(TASK) || !context.actual.spaceId.equals(SPACE)
                    || !context.actual.audience.equals("group") || context.sourceCoordinatorEpoch != 1 || !context.sourceTaskRunId.equals(RUN)) throw new SecurityException("fixture native source proof mismatch");
        }
        @Override public JSONObject mediaReceipt(Context context, RunControl control) throws Exception {
            checkRead(context, control); receiptReads++; if (noReceipt) return null; if (receiptOverride != null) return receiptOverride;
            try { return jobs.get(context.sourceOperationId); } catch (IllegalArgumentException absent) { return null; }
        }
        @Override public Artifact artifact(Context context, RunControl control) throws Exception {
            checkRead(context, control); if (artifactOverride != null) return artifactOverride;
            if (!metadataFile().isFile() || !blob().isFile()) return null;
            JSONObject value = new JSONObject(Files.readString(metadataFile().toPath())), original = value.getJSONObject("origin"), source = value.getJSONObject("source");
            return new Artifact(ARTIFACT, SPACE, TASK, OUTPUT, "group", original.getString("mimeType"), original.getLong("size"), original.getString("sha256"),
                    media ? original.getString("id") : "", media ? source.getString("operationId") : "file-operation", RUN, media ? source.optString("providerJobId") : "");
        }
        @Override public InputStream openArtifact(Context context, Artifact artifact, RunControl control) throws Exception {
            checkRead(context, control); opened++; closed = false;
            return new FilterInputStream(Files.newInputStream(blob().toPath())) {
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int n = super.read(bytes, offset, length); if (revokeOnRead) revoked = true; if (cancelOnRead) control.cancel(); return n;
                }
                @Override public void close() throws IOException { closed = true; super.close(); }
            };
        }
        @Override public Delivery delivery(Context context, RunControl control) throws Exception {
            checkRead(context, control); if (deliveryUnavailable) throw new IOException("fixture outbox temporarily unavailable");
            if (deliveryOverride != null) return deliveryOverride;
            File file = new File(root, "fixture-native-delivery.json"); if (!file.isFile()) return null;
            JSONObject v = new JSONObject(Files.readString(file.toPath()));
            return new Delivery("delivery-original", TASK, SPACE, OUTPUT, ARTIFACT, "group", v.getString("state"), v.getString("upload"), v.getString("messageId"), v.getBoolean("ack"), true, v.getString("error"));
        }
        @Override public void refreshOriginalMedia(Context context, Refresh refresh, RunControl control) throws Exception {
            checkRead(context, control); refreshes++;
            jobs.execute(refresh == Refresh.ORIGINAL_ARTIFACTS ? "fetchResults" : "getJob", new JSONObject().put("operationId", context.sourceOperationId)
                    .put("_taskId", context.actual.taskId).put("_runId", context.sourceTaskRunId), config, "fixture-credential", control);
        }
    }

    @Test public void approvalUnknownRestartOriginalQueryArtifactAndNativeDeliveryStayDistinct() throws Exception {
        JSONObject waiting = results.inspect(jobSelection(), new RunControl());
        assertEquals("awaiting_approval", waiting.getString("mediaState")); assertEquals("pending", waiting.getString("approvalState"));
        assertFalse(results.refreshOriginalMedia(jobSelection(), Refresh.ORIGINAL_ARTIFACTS, new RunControl()).getBoolean("refreshPerformed"));
        assertEquals(0, posts.get());
        dropAck.set(true); assertEquals("unknown", submit().getString("status")); assertEquals(1, posts.get());
        nativePort.taskRun = RESUMED; nativePort.taskEpoch = 2; nativePort.taskState = "reconciling"; nativePort.saveTask(); reopen();
        JSONObject unknown = results.inspect(jobSelection(), new RunControl());
        assertEquals("unknown", unknown.getString("mediaState")); assertEquals(RESUMED, unknown.getString("taskRunId")); assertEquals(RUN, unknown.getString("sourceTaskRunId"));
        remoteComplete.set(true);
        JSONObject provider = results.refreshOriginalMedia(jobSelection(), Refresh.PROVIDER_STATUS, new RunControl());
        assertEquals("response_received", provider.getString("mediaState")); assertFalse(provider.getBoolean("artifactReady")); assertEquals(0, downloads.get());
        JSONObject saved = results.refreshOriginalMedia(jobSelection(), Refresh.ORIGINAL_ARTIFACTS, new RunControl());
        assertTrue(saved.getBoolean("artifactReady")); assertEquals("none", saved.getJSONObject("delivery").getString("state"));
        assertEquals(1, downloads.get()); assertEquals(1, posts.get()); assertEquals(RUN, jobs.get(OP).getString("runId"));
        GroupMediaResults.OpenedArtifact opened = results.readArtifact(fileSelection(), new RunControl());
        assertArrayEquals(PNG, opened.copyBytes()); assertTrue(opened.metadata().getBoolean("artifactContentVerified")); assertFalse(opened.metadata().getBoolean("deliveryConfirmed"));
        nativePort.saveDelivery("unknown", "uploaded", "", false, "send_unknown"); reopen();
        JSONObject uploaded = results.inspect(fileSelection(), new RunControl()).getJSONObject("delivery");
        assertEquals("uploaded", uploaded.getString("uploadState")); assertEquals("unknown", uploaded.getString("state")); assertFalse(uploaded.getBoolean("messageAcknowledged"));
        assertTrue(uploaded.getJSONObject("error").getBoolean("queryOnly"));
        nativePort.saveDelivery("delivered", "uploaded", "fixture-platform-message", true, ""); reopen();
        JSONObject delivered = results.inspect(fileSelection(), new RunControl()).getJSONObject("delivery");
        assertEquals("delivered", delivered.getString("state")); assertTrue(delivered.getBoolean("messageAcknowledged")); assertFalse(delivered.getBoolean("readConfirmed"));
        assertEquals(1, posts.get()); assertTrue(queries.get() > 0);
    }

    @Test public void crossSpaceAudienceForgedArtifactAndChangedSourceRunCannotOpen() throws Exception {
        ready();
        Selection otherSpace = new Selection(REQUEST, TASK, "group:other", OUTPUT, ARTIFACT, "group", "members", OP, RUN);
        assertThrows(SecurityException.class, () -> results.readArtifact(otherSpace, new RunControl()));
        Selection wrongAudience = new Selection(REQUEST, TASK, SPACE, OUTPUT, ARTIFACT, "requester", "members", OP, RUN);
        assertThrows(SecurityException.class, () -> results.readArtifact(wrongAudience, new RunControl()));
        Selection claimedArtifact = new Selection(REQUEST, TASK, SPACE, OUTPUT, "model-claimed-artifact", "group", "members", OP, RUN);
        assertThrows(SecurityException.class, () -> results.readArtifact(claimedArtifact, new RunControl()));
        nativePort.receiptOverride = jobs.get(OP).put("runId", RESUMED);
        assertThrows(SecurityException.class, () -> results.readArtifact(fileSelection(), new RunControl()));
        assertEquals(0, nativePort.opened); assertEquals(1, posts.get());
    }

    @Test public void tamperedTruncatedAndRevokedReadsCloseWithoutReturningPartialBytes() throws Exception {
        ready(); byte[] changed = PNG.clone(); changed[12] ^= 1; Files.write(nativePort.blob().toPath(), changed);
        assertThrows(SecurityException.class, () -> results.readArtifact(fileSelection(), new RunControl())); assertTrue(nativePort.closed);
        Files.write(nativePort.blob().toPath(), new byte[]{1, 2});
        assertThrows(IOException.class, () -> results.readArtifact(fileSelection(), new RunControl())); assertTrue(nativePort.closed);
        Files.write(nativePort.blob().toPath(), PNG); nativePort.revokeOnRead = true;
        assertThrows(SecurityException.class, () -> results.readArtifact(fileSelection(), new RunControl())); assertTrue(nativePort.closed);
        nativePort.revoked = false; nativePort.revokeOnRead = false; nativePort.cancelOnRead = true;
        assertThrows(InterruptedException.class, () -> results.readArtifact(fileSelection(), new RunControl())); assertTrue(nativePort.closed);
        assertEquals(1, posts.get());
    }

    @Test public void ordinaryTaskFileIsReadableWithoutFabricatingAnyMediaJob() throws Exception {
        nativePort.media = false; nativePort.taskState = "completed"; nativePort.approval = "not_required";
        byte[] file = "native scoped task file fixture".getBytes(StandardCharsets.UTF_8); Files.write(nativePort.blob().toPath(), file);
        JSONObject original = new JSONObject().put("id", "native-file").put("mimeType", "application/octet-stream").put("size", file.length).put("sha256", digest(file));
        Files.writeString(nativePort.metadataFile().toPath(), new JSONObject().put("origin", original).put("source", new JSONObject()).toString());
        assertEquals("not_media", results.inspect(fileSelection(), new RunControl()).getString("mediaState"));
        assertArrayEquals(file, results.readArtifact(fileSelection(), new RunControl()).copyBytes());
        assertFalse(results.refreshOriginalMedia(fileSelection(), Refresh.ORIGINAL_ARTIFACTS, new RunControl()).getBoolean("refreshPerformed"));
        assertEquals(0, nativePort.receiptReads); assertEquals(0, nativePort.refreshes); assertEquals(0, posts.get());
    }

    @Test public void missingArtifactAndUnavailableDeliveryKeepOriginalEvidenceAndNeverResubmit() throws Exception {
        ready(); Files.delete(nativePort.blob().toPath()); nativePort.deliveryUnavailable = true;
        JSONObject missing = results.inspect(fileSelection(), new RunControl());
        assertFalse(missing.getBoolean("artifactReady")); assertEquals("missing", missing.getString("artifactState"));
        assertEquals("unknown", missing.getJSONObject("delivery").getString("state")); assertFalse(missing.getBoolean("verified"));
        assertThrows(IOException.class, () -> results.readArtifact(fileSelection(), new RunControl()));
        Files.write(nativePort.blob().toPath(), PNG); nativePort.noReceipt = true;
        assertEquals("receipt_unavailable", results.inspect(fileSelection(), new RunControl()).getString("mediaState"));
        assertArrayEquals(PNG, results.readArtifact(fileSelection(), new RunControl()).copyBytes());
        assertEquals(1, posts.get());
    }

    @Test public void uploadAcknowledgmentCannotBecomeDeliveryAndWrongDeliveryArtifactIsRejected() throws Exception {
        ready();
        assertThrows(IllegalArgumentException.class, () -> new Delivery("delivery-original", TASK, SPACE, OUTPUT, ARTIFACT, "group", "delivered", "uploaded", "", false, true, ""));
        nativePort.saveDelivery("accepted", "uploaded", "", false, "send_unknown");
        JSONObject accepted = results.inspect(fileSelection(), new RunControl()).getJSONObject("delivery");
        assertEquals("accepted", accepted.getString("state")); assertFalse(accepted.getBoolean("messageAcknowledged"));
        nativePort.deliveryOverride = new Delivery("delivery-other", TASK, SPACE, OUTPUT, "artifact-other", "group", "delivered", "uploaded", "fixture-msg", true, true, "");
        assertThrows(SecurityException.class, () -> results.inspect(fileSelection(), new RunControl())); assertEquals(1, posts.get());
    }

    @Test public void lostSynchronousSpeechResponseHasNoInventedQueryOrSecondSynthesis() throws Exception {
        config.put("provider", "openai_compatible").put("ttsModel", "fixture-only-model").put("voice", "fixture-only-voice");
        JSONObject reply = jobs.execute("synthesizeSpeech", new JSONObject().put("text", "中文语音协议夹具").put("_operationId", OP).put("_taskId", TASK).put("_runId", RUN), config, "fixture-credential", new RunControl());
        assertEquals("unknown", reply.getString("status")); reopen();
        JSONObject view = results.refreshOriginalMedia(jobSelection(), Refresh.PROVIDER_STATUS, new RunControl());
        assertFalse(view.getBoolean("refreshPerformed")); assertFalse(view.getBoolean("providerQuerySupported"));
        assertEquals("unknown", view.getString("mediaState")); assertEquals(1, posts.get()); assertEquals(0, queries.get());
    }
}
