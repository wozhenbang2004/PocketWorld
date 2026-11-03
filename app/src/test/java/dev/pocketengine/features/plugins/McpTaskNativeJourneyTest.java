package dev.pocketengine.features.plugins;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.HostToolSupervisor;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.NativeOwnerWorkflows;
import dev.pocketengine.features.memory.ToolOutputStore;
import dev.pocketengine.features.plugins.mcp.McpJson;
import dev.pocketengine.features.plugins.mcp.McpTaskCoordinator;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreMcpTasks;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Key;
import java.security.KeyStoreSpi;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Actual HostToolSupervisor -> PluginsModule -> HTTP -> original TaskStore/native SQLite.
 * The server and AndroidKeyStore key are synthetic; no real account is contacted or authorized.
 * Owner recovery uses the production output capture and never starts another Runner. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, manifest=Config.NONE, application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class McpTaskNativeJourneyTest {
    private static final String PROVIDER = "SyntheticMcpTaskKeyStore";
    private static final String TOKEN = "synthetic-mcp-task-token";
    private static final String REMOTE = "original-remote-task-1";
    private static final String CREATED = "2026-11-01T00:00:00Z";
    private static final String UPDATED = "2026-11-01T00:00:01Z";
    private static final String NEEDLE = "MCP_ASYNC_ORIGINAL_RESULT_6417";

    private static String id() { return UUID.randomUUID().toString(); }

    @Before public void before() {
        Provider provider = new Provider(PROVIDER, 1.0, "Synthetic test key, not hardware-backed Android") {};
        provider.put("KeyStore.AndroidKeyStore", FixtureKeyStore.class.getName());
        Security.addProvider(provider);
        RuntimeEnvironment.getApplication().getSharedPreferences("feature_secrets", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void after() { Security.removeProvider(PROVIDER); }

    private static final class Peer implements AutoCloseable {
        final McpFixture wire = new McpFixture();
        volatile boolean complete, dropCancelAck, dropCreationAck, wrongHandle;
        volatile String creationUpdatedAt = CREATED, queryCreatedAt = CREATED, queryUpdatedAt = UPDATED;
        volatile CountDownLatch queryEntered, queryRelease, callEntered, callRelease;

        Peer() throws Exception {
            wire.tasksSupport = true;
            wire.definitions = new JSONArray().put(McpFixture.tool("lookup", new JSONObject().put("type", "object")
                    .put("properties", new JSONObject().put("q", new JSONObject().put("type", "string")))
                    .put("required", new JSONArray().put("q")).put("additionalProperties", false))
                    .put("execution", new JSONObject().put("taskSupport", "required")));
            wire.handler = exchange -> {
                try { handle(exchange); }
                catch (AssertionError failed) { wire.failures.add(failed); exchange.error(500, -32603); }
            };
        }

        void handle(McpFixture.Exchange exchange) throws Exception {
            String method = exchange.method();
            if (method.equals("tools/call") || method.startsWith("tasks/")) {
                assertEquals(McpData.VERSION, exchange.requestHeaders.get("mcp-protocol-version"));
                assertEquals(method, exchange.requestHeaders.get("mcp-method"));
                assertEquals("Bearer " + TOKEN, exchange.requestHeaders.get("authorization"));
                JSONObject params = exchange.body.getJSONObject("params");
                assertNotNull(params.getJSONObject("_meta").getJSONObject("io.modelcontextprotocol/clientCapabilities")
                        .getJSONObject("extensions").getJSONObject(McpTaskCoordinator.EXTENSION));
                assertEquals(method.equals("tools/call") ? "lookup" : REMOTE, exchange.requestHeaders.get("mcp-name"));
                if (method.startsWith("tasks/")) assertEquals(REMOTE, params.getString("taskId"));
            }
            if (method.equals("tools/call")) {
                if (callEntered != null) callEntered.countDown();
                if (callRelease != null && !callRelease.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture call was not released");
                if (!dropCreationAck) exchange.json(task(true));
            } else if (method.equals("tasks/get")) {
                if (queryEntered != null) queryEntered.countDown();
                if (queryRelease != null && !queryRelease.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture query was not released");
                exchange.json(task(false));
            } else if (method.equals("tasks/cancel")) {
                // An absent ACK is a real closed HTTP connection, never a fake successful cancellation.
                if (!dropCancelAck) exchange.json(new JSONObject().put("resultType", "complete"));
            } else wire.standard(exchange);
        }

        JSONObject task(boolean creation) throws Exception {
            JSONObject result = new JSONObject().put("resultType", creation ? "task" : "complete")
                    .put("taskId", !creation && wrongHandle ? "different-remote-task" : REMOTE)
                    .put("status", !creation && complete ? "completed" : "working")
                    .put("createdAt", creation ? CREATED : queryCreatedAt)
                    .put("lastUpdatedAt", creation ? creationUpdatedAt : complete ? queryUpdatedAt : CREATED)
                    .put("ttlMs", JSONObject.NULL).put("pollIntervalMs", 0);
            if (!creation && complete) result.put("result", new JSONObject().put("resultType", "complete").put("isError", true)
                    .put("content", new JSONArray().put(new JSONObject().put("type", "text")
                            .put("text", "A".repeat(25000) + NEEDLE + "Z".repeat(35000) + TOKEN)))
                    .put("structuredContent", new JSONObject().put("detail", "S".repeat(40000)).put("token", "business-result-token")
                            .put("authorization", new JSONObject().put("state", "owner_reviewed").put("apiKey", "business-key-id").put("credentialEcho", TOKEN))
                            .put("private", new JSONObject().put("token", new JSONArray().put("word").put(17)))));
            return result;
        }

        @Override public void close() throws Exception {
            if (queryRelease != null) queryRelease.countDown();
            if (callRelease != null) callRelease.countDown();
            wire.close();
        }
    }

    private static final class Domain implements AutoCloseable {
        final Context android = RuntimeEnvironment.getApplication();
        final Peer peer;
        TaskStore tasks;
        SQLiteDatabase db;
        FeatureContext context;
        FeatureHost features;
        PluginsModule plugins;
        JSONObject installed;

        Domain(Peer peer) throws Exception {
            this.peer = peer;
            android.deleteDatabase("pocket-world.db");
            File declarations = new File(android.getFilesDir(), "plugins/manifests.json");
            if (declarations.exists()) assertTrue(declarations.delete());
            open(); install();
        }

        void open() throws Exception {
            tasks = new TaskStore(android);
            var field = TaskStore.class.getDeclaredField("db"); field.setAccessible(true); db = (SQLiteDatabase) field.get(tasks);
            context = new FeatureContext(android, new SettingsStore(android), new SecretVault(android), event -> {});
            plugins = new PluginsModule(context); features = new FeatureHost(event -> {}, tasks, () -> false); features.register(plugins);
        }

        void install() throws Exception {
            JSONObject request = new JSONObject().put("id", "async-native").put("name", "原MCP异步测试服务")
                    .put("endpoint", peer.wire.endpoint()).put("token", TOKEN).put("protocolVersion", McpData.VERSION);
            JSONObject preview = plugins.handle("discoverMcp", request);
            installed = plugins.handle("installMcp", new JSONObject().put("previewId", preview.getString("previewId"))
                    .put("expectedInstallationId", preview.getString("expectedInstallationId")).put("tools", new JSONArray().put("lookup")));
            NativeOwnerWorkflows owner = new NativeOwnerWorkflows(features, context, tasks, new MemorySourceBridge(features, tasks));
            owner.mcp("setToolGrantReviewed", new JSONObject().put("id", installed.getString("id"))
                    .put("expectedInstallationId", installed.getString("installationId")).put("expectedGrantsDigest", installed.getString("grantsDigest"))
                    .put("tool", "lookup").put("granted", true));
            owner.mcp("setEnabledReviewed", new JSONObject().put("id", installed.getString("id"))
                    .put("expectedInstallationId", installed.getString("installationId")).put("enabled", true));
        }

        void reopen() throws Exception { features.close(); db.close(); open(); }

        JSONObject receipt(Attempt attempt) throws Exception { return tasks.mcpTaskReceiptForOwner(attempt.task, attempt.operation); }
        JSONObject inner(Attempt attempt) throws Exception { return receipt(attempt).getJSONObject(TaskStoreMcpTasks.KEY).getJSONObject("receipt"); }
        JSONObject query(Attempt attempt) throws Exception { return observe(attempt, false, new RunControl(), id()); }
        JSONObject observe(Attempt attempt, boolean cancel, RunControl control, String request) throws Exception {
            return plugins.reconcileMcpTaskForOwner(tasks, attempt.task, attempt.operation, cancel, control, request);
        }
        void awaitPoll(Attempt attempt) throws Exception {
            long remaining = inner(attempt).optLong("nextPollAt") - System.currentTimeMillis();
            assertTrue("Fixture poll interval must remain bounded", remaining < 2000);
            if (remaining > 0) Thread.sleep(remaining + 5);
        }
        String state(Attempt attempt) {
            try (Cursor row = db.rawQuery("SELECT state FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",
                    new String[]{attempt.task, attempt.run, attempt.operation})) { assertTrue(row.moveToFirst()); return row.getString(0); }
        }
        long outputs() { try (Cursor row = db.rawQuery("SELECT count(*) FROM task_output_sources", null)) { assertTrue(row.moveToFirst()); return row.getLong(0); } }
        String retained(JSONObject ref) throws Exception {
            String output = ToolOutputStore.outputId(ref.getString("taskId"), ref.getString("runId"), ref.getString("sourceId"));
            StringBuilder body = new StringBuilder();
            try (Cursor rows = db.rawQuery("SELECT body FROM task_output_chunks WHERE output_id=? ORDER BY chunk_index", new String[]{output})) {
                while (rows.moveToNext()) body.append(rows.getString(0));
            }
            return body.toString();
        }
        void revokeCredential() throws Exception {
            JSONObject raw = new JSONObject(new String(Files.readAllBytes(new File(android.getFilesDir(), "plugins/manifests.json").toPath()), StandardCharsets.UTF_8));
            context.secrets.remove(raw.getJSONArray("plugins").getJSONObject(0).getString("credentialRef"));
        }
        @Override public void close() { try { features.close(); } finally { db.close(); } }
    }

    private static final class Attempt implements AutoCloseable {
        final Domain domain;
        final String task, run, tool, operation;
        final int epoch;
        final RunControl control = new RunControl();
        final HostToolSupervisor host;
        final RunJournal journal;
        final JSONObject args = new JSONObject().put("q", "one exact approved request");

        Attempt(Domain domain) throws Exception {
            this.domain = domain;
            task = domain.tasks.createCapabilityTask("原MCP异步任务完整宿主链", "").getString("id");
            domain.tasks.initializeExecutionScope(task, 8);
            JSONObject attempt = domain.tasks.startAttempt(task, "capabilities", 8);
            run = attempt.getString("runId"); epoch = attempt.getInt("coordinatorEpoch"); control.bindHostRun(task, run, epoch);
            host = new HostToolSupervisor(domain.features, domain.tasks, task, run, epoch, true,
                    domain.tasks.toolOutputFactory(task, run, epoch, control, text -> text));
            host.tools(); tool = domain.plugins.toolRegistry().getJSONObject(0).getString("name");
            domain.tasks.authorizeTools(task, run, new JSONArray().put(tool));
            // The original host creates and consumes the real exact-argument owner approval.
            JSONObject waiting = host.execute(tool, args, control);
            assertEquals("approval_required", waiting.getString("code"));
            assertEquals(0, domain.peer.wire.count("tools/call"));
            domain.tasks.decideCapabilityApproval(task, waiting.getString("approvalId"), true);
            journal = domain.tasks.journal(task, run, epoch, control); operation = journal.beginOperation(tool, args);
        }

        JSONObject dispatch() throws Exception {
            JSONObject result = host.execute(tool, new JSONObject(args.toString()).put("_hostOperationId", operation), control);
            journal.finishOperation(operation, result); return result;
        }
        @Override public void close() { control.cancel(); host.close(); }
    }

    private static void assertCompleteRetainedResult(String retained, JSONObject ref) throws Exception {
        assertTrue(ref.getBoolean("retainedNativeDataComplete"));
        assertTrue(ref.has("upstreamTruncated")); assertTrue(ref.isNull("upstreamTruncated"));
        assertTrue(retained.length() > 100000); assertFalse(retained.contains(TOKEN));
        JSONObject saved = new JSONObject(retained), result = saved.getJSONObject("result");
        assertEquals("pocketworld.mcp-returned-data.v1", saved.getString("format"));
        assertEquals("supported_text_and_structured_content_fields", saved.getString("sourceCoverage"));
        assertFalse(saved.getBoolean("upstreamTruncationKnown")); assertFalse(saved.getBoolean("unsupportedContentOmitted"));
        assertTrue(result.getBoolean("isError")); assertEquals(0, result.getJSONArray("omittedContent").length());
        JSONArray content = result.getJSONArray("content"); assertEquals(1, content.length());
        assertEquals("text", content.getJSONObject(0).getString("type"));
        assertEquals("A".repeat(25000) + NEEDLE + "Z".repeat(35000) + "[凭据已隐藏]", content.getJSONObject(0).getString("text"));
        JSONObject structured = result.getJSONObject("structuredContent");
        assertEquals("S".repeat(40000), structured.getString("detail")); assertEquals("business-result-token", structured.getString("token"));
        assertEquals("owner_reviewed", structured.getJSONObject("authorization").getString("state"));
        assertEquals("business-key-id", structured.getJSONObject("authorization").getString("apiKey"));
        assertEquals("[凭据已隐藏]", structured.getJSONObject("authorization").getString("credentialEcho"));
        assertEquals("word", structured.getJSONObject("private").getJSONArray("token").getString(0));
        assertEquals(17, structured.getJSONObject("private").getJSONArray("token").getInt(1));
        byte[] bytes = retained.getBytes(StandardCharsets.UTF_8);
        JSONObject range = ref.getJSONObject("retainedRange");
        assertEquals(0, range.getInt("offset")); assertEquals("UTF-16", range.getString("unit"));
        assertEquals(retained.length(), range.getInt("chars")); assertEquals(bytes.length, range.getInt("utf8Bytes"));
        StringBuilder digest = new StringBuilder("sha256:");
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format(Locale.ROOT, "%02x", value & 255));
        assertEquals(digest.toString(), ref.getString("revision"));
    }

    @Test public void originalHostTaskColdRecoveryCapturesLongResultWithoutSchemaRediscoveryOrReplay() throws Exception {
        try (Peer peer = new Peer(); Domain domain = new Domain(peer); Attempt attempt = new Attempt(domain)) {
            assertEquals("unknown", attempt.dispatch().getString("status"));
            assertEquals("unknown", domain.state(attempt)); assertTrue(domain.tasks.hasUnresolved(attempt.task));
            assertEquals(1, peer.wire.count("tools/call")); assertEquals(0, domain.outputs());
            assertEquals(1, domain.tasks.get(attempt.task, false).getJSONObject("executionScope").getInt("toolsUsed"));
            assertThrows(IllegalStateException.class, () -> domain.tasks.startAttempt(attempt.task, "capabilities", 1));
            int originalLists = peer.wire.count("tools/list");
            // The original task remains queryable even if a new invocation would fail its schema pin.
            peer.wire.definitions = new JSONArray().put(McpFixture.tool("lookup", new JSONObject().put("type", "object")
                    .put("properties", new JSONObject().put("changed", new JSONObject().put("type", "integer")))));
            attempt.control.cancel(); domain.reopen(); peer.complete = true; domain.awaitPoll(attempt);
            JSONObject completed = domain.query(attempt);
            assertEquals("response_received", completed.getString("status")); assertFalse(completed.getBoolean("verified"));
            assertTrue(domain.inner(attempt).getBoolean("resolved")); assertTrue(domain.inner(attempt).getJSONObject("toolResult").getBoolean("isError"));
            assertEquals("receipt", domain.state(attempt)); assertFalse(domain.tasks.hasUnresolved(attempt.task));
            assertEquals(1, peer.wire.count("tools/call")); assertEquals(originalLists, peer.wire.count("tools/list"));
            assertEquals(1, peer.wire.count("tasks/get")); assertEquals(1, domain.outputs());
            JSONObject capture = completed.getJSONObject("outputCapture");
            // All native returned bytes are retained; an RPC completion cannot prove that the
            // remote server did not truncate before returning them. Keep that distinct unknown.
            assertEquals("partial", capture.getString("status"));
            assertEquals("native_output_upstream_completeness_unknown", capture.getString("reason"));
            assertEquals(1, capture.getJSONArray("outputRefs").length());
            JSONObject ref = capture.getJSONArray("outputRefs").getJSONObject(0);
            assertEquals(attempt.task, ref.getString("taskId")); assertEquals(attempt.run, ref.getString("runId"));
            assertEquals(attempt.operation, ref.getString("operationId")); assertEquals("MCP_RESULT_JSON", ref.getString("captureKind"));
            assertCompleteRetainedResult(domain.retained(ref), ref);
            assertFalse(domain.tasks.get(attempt.task, false).toString().contains(TOKEN));
            int requests = peer.wire.received.size(); JSONObject duplicate = domain.query(attempt);
            assertFalse(duplicate.getBoolean("networkRequested")); assertEquals(requests, peer.wire.received.size());
            assertEquals(McpJson.digest(capture), McpJson.digest(duplicate.getJSONObject("outputCapture")));
            assertEquals(1, domain.tasks.get(attempt.task, false).getJSONArray("operations").length()); assertEquals(1, domain.tasks.list().length());
            assertTrue(peer.wire.failures.toString(), peer.wire.failures.isEmpty());
        }
    }

    @Test public void withdrawnCredentialAndReplacementInstallationCannotClaimOriginalRemoteTask() throws Exception {
        try (Peer peer = new Peer(); Domain domain = new Domain(peer); Attempt attempt = new Attempt(domain)) {
            attempt.dispatch(); domain.revokeCredential(); int requests = peer.wire.received.size();
            assertThrows(SecurityException.class, () -> domain.query(attempt)); assertEquals(requests, peer.wire.received.size());
            String originalInstallation = domain.installed.getString("installationId"); domain.install();
            assertNotEquals(originalInstallation, domain.installed.getString("installationId")); requests = peer.wire.received.size();
            assertThrows(SecurityException.class, () -> domain.observe(attempt, true, new RunControl(), id()));
            assertEquals(requests, peer.wire.received.size()); assertEquals(1, peer.wire.count("tools/call"));
            assertEquals("unknown", domain.state(attempt)); assertEquals(REMOTE, domain.inner(attempt).getString("remoteTaskId"));
        }
    }

    @Test public void lostCancellationAckIsDurableOnceOnlyAndRemoteCompletionIsStillObserved() throws Exception {
        try (Peer peer = new Peer(); Domain domain = new Domain(peer); Attempt attempt = new Attempt(domain)) {
            attempt.dispatch(); peer.dropCancelAck = true;
            assertThrows(Exception.class, () -> domain.observe(attempt, true, new RunControl(), id()));
            assertEquals(1, peer.wire.count("tasks/cancel")); assertTrue(domain.inner(attempt).getBoolean("cancelRequested"));
            assertFalse(domain.inner(attempt).getBoolean("cancelAcknowledged")); assertFalse(domain.inner(attempt).optBoolean("resolved"));
            domain.reopen(); String duplicateRequest = id();
            domain.observe(attempt, true, new RunControl(), duplicateRequest);
            domain.observe(attempt, true, new RunControl(), duplicateRequest);
            assertEquals(1, peer.wire.count("tasks/cancel")); assertEquals("unknown", domain.state(attempt));
            peer.complete = true; domain.awaitPoll(attempt); JSONObject completed = domain.query(attempt);
            assertEquals("response_received", completed.getString("status")); assertTrue(completed.getBoolean("cancelRequested"));
            assertFalse(completed.getBoolean("cancelAcknowledged")); assertEquals("completed", domain.inner(attempt).getString("status"));
            assertTrue(domain.inner(attempt).getJSONObject("toolResult").getBoolean("isError")); assertEquals(1, peer.wire.count("tools/call"));
        }
    }

    @Test public void duplicateOwnerQueriesAndWrongRemoteHandleCannotReplaceTheOriginalReceipt() throws Exception {
        try (Peer peer = new Peer(); Domain domain = new Domain(peer); Attempt attempt = new Attempt(domain)) {
            attempt.dispatch(); domain.awaitPoll(attempt); peer.wrongHandle = true;
            peer.queryEntered = new CountDownLatch(1); peer.queryRelease = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor(); String request = id();
            try {
                Future<JSONObject> pending = executor.submit(() -> domain.observe(attempt, false, new RunControl(), request));
                assertTrue("Real tasks/get must enter the server", peer.queryEntered.await(3, TimeUnit.SECONDS));
                assertThrows(SecurityException.class, () -> domain.observe(attempt, false, new RunControl(), request));
                assertThrows(SecurityException.class, () -> domain.observe(attempt, false, new RunControl(), id()));
                peer.queryRelease.countDown(); assertThrows(ExecutionException.class, () -> pending.get(4, TimeUnit.SECONDS));
                assertEquals(1, peer.wire.count("tasks/get")); assertEquals(REMOTE, domain.inner(attempt).getString("remoteTaskId"));
                assertEquals("unknown", domain.state(attempt)); assertFalse(domain.inner(attempt).optBoolean("resolved"));
                peer.wrongHandle = false; peer.complete = true; domain.awaitPoll(attempt); domain.query(attempt);
                assertEquals("receipt", domain.state(attempt)); assertEquals(1, peer.wire.count("tools/call"));
            } finally { peer.queryRelease.countDown(); executor.shutdownNow(); }
        }
    }

    @Test public void cancelledOriginalRunAndLostCreationCallbackStayUnknownAfterColdReopen() throws Exception {
        try (Peer peer = new Peer(); Domain domain = new Domain(peer); Attempt attempt = new Attempt(domain)) {
            peer.callEntered = new CountDownLatch(1); peer.callRelease = new CountDownLatch(1); peer.dropCreationAck = true;
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<JSONObject> pending = executor.submit(attempt::dispatch);
                assertTrue("Original tools/call must reach the server before cancellation", peer.callEntered.await(3, TimeUnit.SECONDS));
                domain.tasks.requestCancel(attempt.task, attempt.run); attempt.control.cancel(); peer.callRelease.countDown();
                assertThrows(ExecutionException.class, () -> pending.get(4, TimeUnit.SECONDS));
                assertEquals(1, peer.wire.count("tools/call")); assertEquals("unknown", domain.state(attempt));
                assertFalse(domain.inner(attempt).has("remoteTaskId")); assertEquals(0, domain.outputs());
                domain.reopen(); int requests = peer.wire.received.size();
                assertFalse(domain.query(attempt).getBoolean("networkRequested"));
                JSONObject cancelled = domain.observe(attempt, true, new RunControl(), id());
                assertFalse(cancelled.getBoolean("cancelAcknowledged")); assertEquals(requests, peer.wire.received.size());
                assertEquals("unknown", domain.state(attempt)); assertEquals(1, domain.tasks.get(attempt.task, false).getJSONArray("operations").length());
            } finally { peer.callRelease.countDown(); executor.shutdownNow(); }
        }
    }

    @Test public void staleCompletedTaskCannotCaptureAnotherGenerationBeforeReceiptValidation() throws Exception {
        for (String changed : new String[]{"createdAt", "lastUpdatedAt"}) {
            try (Peer peer = new Peer(); Domain domain = new Domain(peer); Attempt attempt = new Attempt(domain)) {
                peer.creationUpdatedAt = UPDATED;
                assertEquals("unknown", attempt.dispatch().getString("status"));
                assertEquals(0, domain.outputs());
                String originalReceipt = McpJson.digest(domain.receipt(attempt));
                int originalLists = peer.wire.count("tools/list");
                attempt.control.cancel(); domain.reopen(); domain.awaitPoll(attempt); peer.complete = true;
                // Both responses are internally chronological. Only the durable original row
                // can reject a reused task ID or a callback older than its last observation.
                if (changed.equals("createdAt")) peer.queryCreatedAt = "2026-11-01T00:00:00.500Z";
                else peer.queryUpdatedAt = CREATED;
                assertThrows(Exception.class, () -> domain.query(attempt));
                assertEquals(changed + " must be fenced before any native output write", 0, domain.outputs());
                assertEquals(originalReceipt, McpJson.digest(domain.receipt(attempt)));
                assertEquals("unknown", domain.state(attempt));
                assertEquals(1, peer.wire.count("tasks/get")); assertEquals(1, peer.wire.count("tools/call"));
                assertEquals(originalLists, peer.wire.count("tools/list"));
                // A later exact observation may still retain the real result of this operation.
                peer.queryCreatedAt = CREATED; peer.queryUpdatedAt = UPDATED;
                assertEquals("response_received", domain.query(attempt).getString("status"));
                assertEquals(1, domain.outputs()); assertEquals(1, peer.wire.count("tools/call"));
                assertTrue(peer.wire.failures.toString(), peer.wire.failures.isEmpty());
            }
        }
    }

    @Test public void cancelledDeliveryKeepsTheCommittedOriginalCaptureThroughColdRecovery() throws Exception {
        try (Peer peer = new Peer(); Domain domain = new Domain(peer); Attempt attempt = new Attempt(domain)) {
            assertEquals("unknown", attempt.dispatch().getString("status"));
            domain.awaitPoll(attempt); peer.complete = true;
            RunControl observation = new RunControl();
            observation.setAuthorityCheck(() -> {
                if (domain.outputs() > 0) throw new SecurityException("Synthetic source withdrawal after the original bytes commit");
            });
            assertThrows(InterruptedException.class, () -> domain.observe(attempt, false, observation, id()));
            assertTrue(observation.isCancelled()); assertEquals(1, domain.outputs());
            assertTrue(domain.inner(attempt).getBoolean("resolved")); assertEquals("receipt", domain.state(attempt));
            JSONObject original = domain.receipt(attempt);
            assertEquals("response_received", original.getString("status")); assertFalse(original.getBoolean("verified"));
            JSONObject capture = original.getJSONObject("outputCapture"); assertEquals("partial", capture.getString("status"));
            assertEquals("native_output_upstream_completeness_unknown", capture.getString("reason"));
            assertEquals(1, capture.getJSONArray("outputRefs").length());
            JSONObject ref = capture.getJSONArray("outputRefs").getJSONObject(0);
            assertEquals(attempt.task, ref.getString("taskId")); assertEquals(attempt.run, ref.getString("runId"));
            assertEquals(attempt.operation, ref.getString("operationId"));
            String originalCapture = McpJson.digest(capture);
            domain.reopen(); int requests = peer.wire.received.size();
            JSONObject recovered = domain.query(attempt);
            assertFalse(recovered.getBoolean("networkRequested")); assertEquals(requests, peer.wire.received.size());
            assertEquals(originalCapture, McpJson.digest(recovered.getJSONObject("outputCapture")));
            assertCompleteRetainedResult(domain.retained(ref), ref);
            assertEquals(1, domain.outputs()); assertEquals(1, peer.wire.count("tasks/get")); assertEquals(1, peer.wire.count("tools/call"));
            assertTrue(peer.wire.failures.toString(), peer.wire.failures.isEmpty());
        }
    }

    @Test public void changedServerRevisionRefusesHistoricalTaskQueryBeforeContactingAnotherTask() throws Exception {
        try (Peer peer = new Peer(); Domain domain = new Domain(peer); Attempt attempt = new Attempt(domain)) {
            attempt.dispatch(); domain.awaitPoll(attempt); peer.wire.serverVersion = "changed-server-version";
            assertThrows(Exception.class, () -> domain.query(attempt));
            assertEquals(0, peer.wire.count("tasks/get")); assertEquals(1, peer.wire.count("tools/call"));
            assertEquals("unknown", domain.state(attempt)); assertEquals(REMOTE, domain.inner(attempt).getString("remoteTaskId"));
        }
    }

    public static final class FixtureKeyStore extends KeyStoreSpi {
        private static final Key KEY = new SecretKeySpec(new byte[16], "AES");
        public Key engineGetKey(String alias, char[] password) { return KEY; }
        public Certificate[] engineGetCertificateChain(String alias) { return null; }
        public Certificate engineGetCertificate(String alias) { return null; }
        public Date engineGetCreationDate(String alias) { return new Date(0); }
        public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) { }
        public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) { }
        public void engineSetCertificateEntry(String alias, Certificate certificate) { }
        public void engineDeleteEntry(String alias) { }
        public Enumeration<String> engineAliases() { return Collections.emptyEnumeration(); }
        public boolean engineContainsAlias(String alias) { return true; }
        public int engineSize() { return 1; }
        public boolean engineIsKeyEntry(String alias) { return true; }
        public boolean engineIsCertificateEntry(String alias) { return false; }
        public String engineGetCertificateAlias(Certificate certificate) { return null; }
        public void engineStore(OutputStream output, char[] password) { }
        public void engineLoad(InputStream input, char[] password) { }
    }
}
