package dev.pocketengine.media.jobs;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.testutil.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual loopback HTTP upgrade + RFC6455 server frames; not a real ComfyUI/model acceptance test. */
public final class ComfyUiProgressObserverTest {
    interface Script { void send(Fixture fixture, OutputStream output) throws Exception; }
    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger submissions = new AtomicInteger(), sockets = new AtomicInteger(), cancels = new AtomicInteger();
        final AtomicReference<JSONObject> submitted = new AtomicReference<>();
        final AtomicReference<String> auth = new AtomicReference<>(), query = new AtomicReference<>();
        final CountDownLatch connected = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile Script script = (f, out) -> f.ack(out, f.clientId());
        volatile String redirect;
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/proxy/prompt", exchange -> {
                submissions.incrementAndGet(); JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); submitted.set(body);
                byte[] response = new JSONObject().put("prompt_id", body.getString("prompt_id")).toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
            });
            server.createContext("/proxy/queue", exchange -> { if (exchange.getRequestMethod().equals("POST")) cancels.incrementAndGet(); exchange.sendResponseHeaders(200, 0); exchange.close(); });
            server.createContext("/proxy/ws", exchange -> {
                sockets.incrementAndGet(); auth.set(exchange.getRequestHeaders().getFirst("Authorization")); query.set(exchange.getRequestURI().getRawQuery());
                if (redirect != null) { exchange.getResponseHeaders().add("Location", redirect); exchange.sendResponseHeaders(302, 0); exchange.close(); connected.countDown(); return; }
                String key = exchange.getRequestHeaders().getFirst("Sec-WebSocket-Key");
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                OutputStream output = exchange.getResponseBody();
                output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.flush();
                connected.countDown(); script.send(this, output); release.await(3, TimeUnit.SECONDS); exchange.close();
            });
            server.start();
        }
        String base() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy"; }
        String clientId() { return submitted.get().optString("client_id"); }
        String job() { return submitted.get().optString("prompt_id"); }
        JSONObject config() throws Exception { return new JSONObject().put("provider", "comfyui").put("baseUrl", base()).put("revision", "config-1"); }
        JSONObject request() throws Exception { return new JSONObject().put("_operationId", "op-1").put("_taskId", "task-1").put("_runId", "run-1").put("workflow", new JSONObject().put("6", new JSONObject().put("class_type", "CLIPTextEncode").put("inputs", new JSONObject().put("text", "labelled fixture")))); }
        JSONObject binding(String revision) throws Exception { return new JSONObject().put("operationId", "op-1").put("_taskId", "task-1").put("_runId", "run-1").put("_epoch", 1).put("_configurationRevision", revision).put("providerJobId", job()).put("maxDurationMs", 1000); }
        void ack(OutputStream out, String sid) throws Exception { event(out, "status", new JSONObject().put("sid", sid).put("status", new JSONObject())); }
        void progress(OutputStream out, String prompt, int value, int max) throws Exception { event(out, "progress", new JSONObject().put("prompt_id", prompt).put("node", "6").put("value", value).put("max", max)); }
        void terminal(OutputStream out) throws Exception { event(out, "executing", new JSONObject().put("prompt_id", job()).put("node", JSONObject.NULL)); }
        void event(OutputStream out, String type, JSONObject data) throws Exception { frame(out, 1, new JSONObject().put("type", type).put("data", data).toString().getBytes(StandardCharsets.UTF_8)); }
        static void frame(OutputStream out, int opcode, byte[] bytes) throws Exception {
            out.write(0x80 | opcode);
            if (bytes.length <= 125) out.write(bytes.length);
            else if (bytes.length <= 65535) { out.write(126); out.write(bytes.length >>> 8); out.write(bytes.length); }
            else { out.write(127); for (int n = 7; n >= 0; n--) out.write((int) (((long) bytes.length >>> (n * 8)) & 255)); }
            out.write(bytes); out.flush();
        }
        public void close() { release.countDown(); server.stop(0); }
    }

    private Fixture fixture;
    private MediaJobJournal journal;
    private MediaJobs jobs;
    private JSONObject config;
    @Before public void setup() throws Exception {
        fixture = new Fixture(); config = fixture.config(); journal = new MediaJobJournal(Files.createTempDirectory("b4-progress-").resolve("receipts.json").toFile());
        jobs = new MediaJobs(journal, (p, s, c) -> { throw new AssertionError("Progress cannot import an artifact"); }, event -> {});
        jobs.execute("submitWorkflow", fixture.request(), config, "fixture-key", new RunControl());
    }
    @After public void close() { if (fixture != null) fixture.close(); }
    static RunControl control() { RunControl control = new RunControl(); control.bindHostRun("task-1", "run-1", 1); return control; }
    private JSONObject observe(RunControl control, List<JSONObject> events) throws Exception { return jobs.observeProgress(fixture.binding("config-1"), config, "fixture-key", control, events::add); }

    @Test public void realUpgradeAdmitsOnlyExactPromptAfterExactClientHandshake() throws Exception {
        fixture.script = (f, out) -> {
            f.ack(out, f.clientId());
            f.event(out, "executing", new JSONObject().put("node", "old-node"));
            f.event(out, "progress", new JSONObject().put("value", 99).put("max", 100));
            f.progress(out, UUID.randomUUID().toString(), 1, 10);
            f.progress(out, f.job(), 2, 10); f.terminal(out);
        };
        List<JSONObject> events = new ArrayList<>(); JSONObject result = observe(control(), events);
        assertEquals("provider_terminal_observed", result.getString("endReason")); assertEquals(2, events.size());
        assertEquals("clientId=" + fixture.clientId(), fixture.query.get()); assertEquals("Bearer fixture-key", fixture.auth.get());
        assertEquals(2, result.getLong("ignoredUnboundEvents")); assertEquals(1, result.getLong("ignoredForeignEvents"));
        assertEquals(2, events.get(0).getInt("value")); assertEquals("provider_node_only", events.get(0).getString("progressScope"));
        assertEquals("executor_finished_observed", events.get(1).getString("providerPhase")); assertTrue(events.get(1).getBoolean("historyReconciliationRequired"));
        for (int i = 0; i < events.size(); i++) { JSONObject event = events.get(i); assertEquals(i + 1, event.getLong("sequence")); assertEquals(fixture.job(), event.getString("providerJobId")); assertFalse(event.has("status")); assertFalse(event.getBoolean("verified")); assertFalse(event.toString().contains("fixture-key")); assertFalse(event.toString().contains(fixture.clientId())); }
        assertEquals(1, fixture.submissions.get()); assertEquals(0, fixture.cancels.get());
    }

    @Test public void wrongClientSidCannotAttachOtherwiseMatchingProgress() throws Exception {
        fixture.script = (f, out) -> { f.ack(out, UUID.randomUUID().toString()); f.progress(out, f.job(), 5, 10); };
        List<JSONObject> events = new ArrayList<>(); assertEquals("client_binding_mismatch", observe(control(), events).getString("endReason")); assertTrue(events.isEmpty());
    }
    @Test public void unboundOldServerEventsNeverBecomeJobProgress() throws Exception {
        fixture.script = (f, out) -> { f.ack(out, f.clientId()); f.event(out, "executing", new JSONObject().put("node", JSONObject.NULL)); f.event(out, "progress", new JSONObject().put("value", 100).put("max", 100)); };
        List<JSONObject> events = new ArrayList<>(); JSONObject result = jobs.observeProgress(fixture.binding("config-1").put("maxDurationMs", 250), config, "fixture-key", control(), events::add);
        assertEquals("observation_expired", result.getString("endReason")); assertFalse(result.getBoolean("providerTerminalObserved")); assertTrue(events.isEmpty()); assertEquals(2, result.getLong("ignoredUnboundEvents")); assertEquals(1, fixture.sockets.get());
    }
    @Test public void cancellationClosesOnlyObserverAndRejectsQueuedLateEvents() throws Exception {
        fixture.script = (f, out) -> { f.ack(out, f.clientId()); f.progress(out, f.job(), 1, 10); f.progress(out, f.job(), 2, 10); f.terminal(out); };
        RunControl control = control(); List<JSONObject> events = new ArrayList<>();
        JSONObject result = jobs.observeProgress(fixture.binding("config-1"), config, "fixture-key", control, event -> { events.add(event); control.cancel(); });
        assertEquals("observation_cancelled", result.getString("endReason")); assertEquals(1, events.size()); assertEquals(1, fixture.submissions.get()); assertEquals(0, fixture.cancels.get());
        assertEquals("queued", jobs.get("op-1").getString("status"));
    }
    @Test public void liveAuthorityRevocationStopsFurtherReporting() throws Exception {
        fixture.script = (f, out) -> { f.ack(out, f.clientId()); f.progress(out, f.job(), 1, 10); f.progress(out, f.job(), 2, 10); };
        java.util.concurrent.atomic.AtomicBoolean revoked = new java.util.concurrent.atomic.AtomicBoolean(); RunControl control = control();
        control.setAuthorityCheck(() -> { if (revoked.get()) throw new SecurityException("fixture revoked"); }); List<JSONObject> events = new ArrayList<>();
        JSONObject result = jobs.observeProgress(fixture.binding("config-1"), config, "fixture-key", control, event -> { events.add(event); revoked.set(true); });
        assertEquals(1, events.size()); assertEquals("observation_cancelled", result.getString("endReason")); assertEquals(0, fixture.cancels.get());
    }
    @Test public void originalRunConfigProviderAndClientBindingsAreCheckedBeforeNetwork() throws Exception {
        JSONObject input = fixture.binding("config-1"); RunControl wrong = new RunControl(); wrong.bindHostRun("task-1", "run-1", 2);
        assertThrows(SecurityException.class, () -> jobs.observeProgress(input, config, "fixture-key", wrong, e -> {}));
        assertThrows(SecurityException.class, () -> jobs.observeProgress(new JSONObject(input.toString()).put("providerJobId", UUID.randomUUID().toString()), config, "fixture-key", control(), e -> {}));
        assertThrows(SecurityException.class, () -> jobs.observeProgress(new JSONObject(input.toString()).put("clientId", UUID.randomUUID().toString()), config, "fixture-key", control(), e -> {}));
        assertThrows(SecurityException.class, () -> jobs.observeProgress(new JSONObject(input.toString()).put("_configurationRevision", "changed"), config, "fixture-key", control(), e -> {}));
        JSONObject changed = new JSONObject(config.toString()).put("baseUrl", fixture.base() + "/replacement");
        assertThrows(SecurityException.class, () -> jobs.observeProgress(input, changed, "fixture-key", control(), e -> {}));
        assertEquals(0, fixture.sockets.get());
    }
    @Test public void unconfirmedProviderIdRequiresReconciliationBeforeObservation() throws Exception {
        JSONObject record = journal.get("op-1"); record.put("providerJobIdConfirmed", false); journal.put(record);
        assertThrows(IllegalStateException.class, () -> observe(control(), new ArrayList<>())); assertEquals(0, fixture.sockets.get());
    }
    @Test public void competingObserverCannotReplaceTheOriginalClientSocket() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(); RunControl control = control();
        try { Future<JSONObject> first = worker.submit(() -> observe(control, new ArrayList<>())); assertTrue(fixture.connected.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> observe(control(), new ArrayList<>())); assertEquals(1, fixture.sockets.get());
            control.cancel(); assertEquals("observation_cancelled", first.get(2, TimeUnit.SECONDS).getString("endReason"));
        } finally { control.cancel(); worker.shutdownNow(); }
    }
    @Test public void binaryPreviewsAreIgnoredAndOversizedOrInvalidProgressEndsObservation() throws Exception {
        fixture.script = (f, out) -> { f.ack(out, f.clientId()); Fixture.frame(out, 2, new byte[]{1, 2, 3}); f.progress(out, f.job(), 2, 1); };
        List<JSONObject> events = new ArrayList<>(); JSONObject result = observe(control(), events);
        assertEquals("message_not_supported", result.getString("endReason")); assertEquals(1, result.getLong("ignoredBinaryPreviews")); assertTrue(events.isEmpty());
    }
    @Test public void oversizedMessageAndRedirectNeverReconnectOrForwardCredential() throws Exception {
        fixture.script = (f, out) -> { f.ack(out, f.clientId()); Fixture.frame(out, 1, new byte[65537]); };
        assertEquals("message_limit", observe(control(), new ArrayList<>()).getString("endReason")); assertEquals(1, fixture.sockets.get());
        AtomicInteger reached = new AtomicInteger(); HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try { other.createContext("/stolen", ex -> { reached.incrementAndGet(); ex.sendResponseHeaders(200, 0); ex.close(); }); other.start(); fixture.redirect = "http://127.0.0.1:" + other.getAddress().getPort() + "/stolen";
            assertEquals("transport_unconfirmed", observe(control(), new ArrayList<>()).getString("endReason")); assertEquals(0, reached.get()); assertEquals(2, fixture.sockets.get());
        } finally { other.stop(0); }
    }
}
