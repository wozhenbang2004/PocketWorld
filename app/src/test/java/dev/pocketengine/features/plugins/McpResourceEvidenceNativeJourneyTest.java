package dev.pocketengine.features.plugins;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.NativeOwnerWorkflows;
import dev.pocketengine.features.attachments.AttachmentRepository;
import dev.pocketengine.features.attachments.AttachmentsModule;
import dev.pocketengine.features.attachments.documents.DocumentReader;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.features.memory.TaskSavedEvidence;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

/** Real owner HTTP read -> original AttachmentBook -> original TaskStore SQLite -> saved-source
 * read. Only the remote server and AndroidKeyStore key are synthetic; no model or account is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, manifest=Config.NONE, application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class McpResourceEvidenceNativeJourneyTest {
    private static final String PROVIDER = "SyntheticMcpResourceEvidenceKeyStore";
    private static final String TOKEN = "synthetic-mcp-resource-evidence-token";
    private static final String URI = "memo://fixture/private/original";
    private static final String TEMPLATE = "memo://fixture/private/{id}{?q}";
    private static final String NEEDLE = "RETAINED_MCP_RESOURCE_AFTER_128_KIB_7192";
    private static final String SUBSCRIPTION = "io.modelcontextprotocol/subscriptionId";
    private static final String BODY = "Original private resource\n" + "a".repeat(139000) + NEEDLE
            + "z".repeat(12000) + "\nEchoed credential: " + TOKEN;

    private static String id() { return UUID.randomUUID().toString(); }

    @Before public void before() {
        Provider provider = new Provider(PROVIDER, 1.0, "Synthetic test key, not hardware-backed Android") {};
        provider.put("KeyStore.AndroidKeyStore", FixtureKeyStore.class.getName());
        Security.addProvider(provider);
        RuntimeEnvironment.getApplication().getSharedPreferences("feature_secrets", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void after() { Security.removeProvider(PROVIDER); }

    private static final class Fixture implements AutoCloseable {
        final Context android = RuntimeEnvironment.getApplication();
        final McpFixture server = new McpFixture();
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        TaskStore tasks;
        SQLiteDatabase db;
        FeatureContext context;
        FeatureHost host;
        PluginsModule plugins;
        AttachmentRepository repo;
        MemorySourceBridge bridge;
        NativeOwnerWorkflows owner;
        JSONObject installation;
        final String task;
        String run;
        int epoch;
        final boolean templatesOnly;
        final String protocol;
        volatile String templateDescription = "original template", readUri = URI;
        volatile CountDownLatch readEntered, readRelease;

        Fixture() throws Exception { this(false, McpData.VERSION); }
        Fixture(boolean templatesOnly, String protocol) throws Exception {
            this.templatesOnly = templatesOnly; this.protocol = protocol; server.modern = McpData.VERSION.equals(protocol);
            android.deleteDatabase("pocket-world.db");
            File manifests = new File(android.getFilesDir(), "plugins/manifests.json");
            if (manifests.exists()) assertTrue(manifests.delete());
            server.handler = exchange -> {
                try { respond(exchange); }
                catch (AssertionError failed) { server.failures.add(failed); exchange.error(500, -32603); }
            };
            open();
            // Synthetic credential setup uses the native module. The public workflow never accepts
            // credential-bearing JSON; all actual resource reads and saves below use that workflow.
            JSONObject preview = plugins.handle("discoverMcp", new JSONObject().put("id", "retained-native")
                    .put("name", "原资源保存测试服务").put("endpoint", server.endpoint()).put("token", TOKEN)
                    .put("protocolVersion", protocol).put("_operationId", id()));
            installation = owner.mcp("installMcp", new JSONObject().put("previewId", preview.getString("previewId"))
                    .put("expectedInstallationId", preview.getString("expectedInstallationId")).put("tools", new JSONArray()));
            task = tasks.createCapabilityTask("明确保存所选MCP资源到原私人任务", "resource-evidence-native-fixture").getString("id");
            tasks.initializeExecutionScope(task, 8);
            JSONObject attempt = tasks.startAttempt(task, "capabilities", 8);
            run = attempt.getString("runId"); epoch = attempt.getInt("coordinatorEpoch");
            assertEquals(0, repo.book.list().length());
        }

        void open() throws Exception {
            tasks = new TaskStore(android);
            var field = TaskStore.class.getDeclaredField("db"); field.setAccessible(true); db = (SQLiteDatabase) field.get(tasks);
            context = new FeatureContext(android, new SettingsStore(android), new SecretVault(android), event -> {});
            plugins = new PluginsModule(context); host = new FeatureHost(event -> {}, tasks, () -> false); host.register(plugins);
            repo = AttachmentRepository.get(android);
            AttachmentsModule attachments = new AttachmentsModule(context, new DocumentReader(null, null)); host.register(attachments);
            bridge = new MemorySourceBridge(host, tasks); bridge.bindAttachments(attachments, repo.book);
            owner = new NativeOwnerWorkflows(host, context, tasks, bridge);
        }

        void cold() throws Exception {
            host.close(); db.close();
            // Recreate the same original repository from its files, not a replacement Book or ledger.
            var field = AttachmentRepository.class.getDeclaredField("INSTANCES"); field.setAccessible(true);
            synchronized (AttachmentRepository.class) { ((Map<?, ?>) field.get(null)).remove(android.getFilesDir().getAbsolutePath()); }
            open(); refresh();
        }

        void respond(McpFixture.Exchange exchange) throws Exception {
            switch (exchange.method()) {
                case "server/discover": case "initialize": {
                    JSONObject discovery = server.discovery();
                    discovery.getJSONObject("capabilities").remove("tools");
                    discovery.getJSONObject("capabilities").put("resources", new JSONObject().put("subscribe", true).put("listChanged", true));
                    if (!server.modern) { discovery.remove("resultType"); exchange.headers.put("MCP-Session-Id", server.session); }
                    exchange.json(discovery); break;
                }
                case "resources/list":
                    assertEquals("Bearer " + TOKEN, exchange.requestHeaders.get("authorization"));
                    exchange.json(complete(new JSONObject().put("resources", templatesOnly ? new JSONArray() : new JSONArray().put(new JSONObject()
                            .put("uri", URI).put("name", "private original").put("mimeType", "text/plain"))))); break;
                case "resources/templates/list":
                    assertTrue(templatesOnly);
                    exchange.json(complete(new JSONObject().put("resourceTemplates", new JSONArray().put(new JSONObject()
                            .put("uriTemplate", TEMPLATE).put("name", "private parameterized original").put("description", templateDescription).put("mimeType", "text/plain"))))); break;
                case "resources/read": {
                    if (server.modern) { assertEquals("resources/read", exchange.requestHeaders.get("mcp-method")); assertEquals(readUri, exchange.requestHeaders.get("mcp-name")); }
                    assertEquals(protocol, exchange.requestHeaders.get("mcp-protocol-version"));
                    assertEquals("Bearer " + TOKEN, exchange.requestHeaders.get("authorization"));
                    assertEquals(readUri, exchange.body.getJSONObject("params").getString("uri"));
                    CountDownLatch entered = readEntered, release = readRelease;
                    if (entered != null) { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); }
                    exchange.json(complete(new JSONObject().put("contents", new JSONArray().put(new JSONObject()
                            .put("uri", readUri).put("mimeType", "text/plain").put("text", BODY))))); break;
                }
                case "subscriptions/listen": {
                    JSONObject params = exchange.body.getJSONObject("params"), requested = params.getJSONObject("notifications");
                    assertEquals(URI, requested.getJSONArray("resourceSubscriptions").getString(0));
                    Object request = exchange.body.get("id");
                    JSONObject ack = notification("notifications/subscriptions/acknowledged", request, new JSONObject().put("notifications", requested));
                    JSONObject update = notification("notifications/resources/updated", request, new JSONObject().put("uri", URI));
                    exchange.raw(200, "text/event-stream", "data: " + ack + "\n\ndata: " + update + "\n\n"); break;
                }
                case "tools/list": case "tools/call": throw new AssertionError("A resource-only server cannot grant or execute tools");
                default: server.standard(exchange);
            }
        }

        JSONObject complete(JSONObject result) throws Exception { if (server.modern) result.put("resultType", "complete"); return result; }

        JSONObject notification(String method, Object request, JSONObject values) throws Exception {
            return new JSONObject().put("jsonrpc", "2.0").put("method", method)
                    .put("params", new JSONObject(values.toString()).put("_meta", new JSONObject().put(SUBSCRIPTION, request)));
        }

        void refresh() throws Exception { installation = owner.mcpSnapshot().getJSONArray("plugins").getJSONObject(0); }
        JSONObject args() throws Exception {
            return new JSONObject().put("id", installation.getString("id")).put("expectedInstallationId", installation.getString("installationId"))
                    .put("expectedStateRevision", installation.getLong("stateRevision")).put("_operationId", id());
        }
        JSONObject target() throws Exception { return tasks.ownerMcpResourceTarget(task); }
        JSONObject snapshot(RunControl control) throws Exception {
            JSONObject resource = owner.mcp("listMcpResources", args()).getJSONArray("resources").getJSONObject(0);
            return owner.mcp("getMcpResource", args().put("uri", URI).put("descriptorDigest", resource.getString("descriptorDigest")), control);
        }
        JSONObject templatePayload() throws Exception {
            JSONObject row = owner.mcp("listMcpResourceTemplates", args()).getJSONArray("resourceTemplates").getJSONObject(0);
            JSONObject preview = McpResourceTemplateData.preview(row.getJSONObject("definition"), new JSONObject().put("id", "chosen/本机").put("q", "~* %2F"));
            readUri = preview.getString("uri");
            return args().put("uri", readUri).put("descriptorDigest", row.getString("descriptorDigest"))
                    .put("templateSelection", new JSONObject().put("uriTemplate", TEMPLATE).put("arguments", preview.getJSONObject("resourceTemplate").getJSONObject("arguments")));
        }
        JSONObject savePayload(JSONObject snapshot, JSONObject target) throws Exception {
            return new JSONObject().put("snapshotId", snapshot.getString("snapshotId")).put("taskId", target.getString("taskId"))
                    .put("expectedTaskPin", target.getString("targetPin")).put("_operationId", id());
        }
        JSONObject save(JSONObject snapshot, JSONObject target) throws Exception { return owner.mcp("saveMcpResource", savePayload(snapshot, target)); }
        void enabled(boolean enabled) throws Exception { owner.mcp("setEnabledReviewed", args().put("enabled", enabled)); refresh(); }
        void nextRun() throws Exception {
            tasks.endAttempt(task, run, "needs_confirmation", "原任务等待主人继续", new JSONObject());
            JSONObject next = tasks.startAttempt(task, "capabilities", 8);
            run = next.getString("runId"); epoch = next.getInt("coordinatorEpoch");
        }
        long events(String type) {
            try (Cursor rows = db.rawQuery("SELECT count(*) FROM task_events WHERE task_id=?" + (type == null ? "" : " AND event_type=?"),
                    type == null ? new String[]{task} : new String[]{task, type})) { assertTrue(rows.moveToFirst()); return rows.getLong(0); }
        }
        JSONObject savedEvent() throws Exception {
            try (Cursor rows = db.rawQuery("SELECT event_json,body_bytes FROM task_events WHERE task_id=? AND event_type='mcp_resource_retained' ORDER BY event_order DESC LIMIT 1", new String[]{task})) {
                assertTrue(rows.moveToFirst()); assertTrue(rows.getLong(1) < 131072); return new JSONObject(rows.getString(0));
            }
        }
        void failIndex() {
            db.execSQL("CREATE TRIGGER fixture_reject_mcp_resource_evidence BEFORE INSERT ON task_events "
                    + "WHEN NEW.event_type='mcp_resource_retained' BEGIN SELECT RAISE(ABORT,'synthetic evidence index failure'); END");
        }
        void allowIndex() { db.execSQL("DROP TRIGGER fixture_reject_mcp_resource_evidence"); }
        String retained(JSONObject attachment) throws Exception {
            return new String(repo.book.readVerifiedSnapshot(attachment.getString("id"), attachment.getString("sha256"), new RunControl()), StandardCharsets.UTF_8);
        }
        <T> T io(Callable<T> action) throws Exception {
            try { return worker.submit(action).get(10, TimeUnit.SECONDS); }
            catch (ExecutionException failure) {
                if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
                if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
                throw failure;
            }
        }
        String read(SourceRef ref) throws Exception {
            return io(() -> { bridge.prepareSourceHead(ref.kind, ref.sourceId, new RunControl());
                var source = bridge.read(ref); assertEquals(ref, source.ref); return source.text; });
        }
        void noExecution() throws Exception {
            assertEquals(0, server.count("tools/list")); assertEquals(0, server.count("tools/call"));
            assertEquals(0, plugins.toolRegistry().length()); assertEquals(0, installation.getJSONArray("grants").length());
            try (Cursor rows = db.rawQuery("SELECT count(*) FROM operations", null)) { assertTrue(rows.moveToFirst()); assertEquals(0, rows.getLong(0)); }
            assertTrue(server.failures.toString(), server.failures.isEmpty());
        }
        @Override public void close() throws Exception { worker.shutdownNow(); try { host.close(); } finally { db.close(); server.close(); } }
    }

    @Test public void ownerSavesFullPrivateOriginalOnceAndColdReadsItsExactHistoricalRange() throws Exception {
        try (Fixture fixture = new Fixture()) {
            RunControl download = new RunControl(); JSONObject snapshot = fixture.snapshot(download), target = fixture.target();
            assertFalse(snapshot.getBoolean("executionAuthorized")); assertTrue(snapshot.getString("snapshotId").length() > 0);
            assertFalse(snapshot.toString().contains(TOKEN)); download.cancel();
            assertThrows(SecurityException.class, () -> fixture.owner.mcp("saveMcpResource", fixture.savePayload(snapshot, target)
                    .put("contents", new JSONArray().put(new JSONObject().put("text", "forged replacement")))));
            JSONObject saved = fixture.save(snapshot, target), attachment = saved.getJSONObject("attachment");
            assertTrue(saved.getBoolean("linked")); assertFalse(saved.getBoolean("duplicate")); assertFalse(saved.getBoolean("remoteReadRepeated"));
            assertFalse(saved.getBoolean("executionAuthorized")); assertFalse(saved.getBoolean("automaticConversationInjection"));
            assertEquals(1, fixture.repo.book.list().length()); assertEquals(1, fixture.events(McpResourceEvidence.EVENT_TYPE));
            assertEquals(1, fixture.tasks.ownerAttachmentReferences(fixture.task).length());
            assertFalse(fixture.repo.book.get(attachment.getString("id")).getJSONObject("source").has("uri"));
            String original = fixture.retained(attachment); JSONObject retained = new JSONObject(original);
            assertTrue(original.getBytes(StandardCharsets.UTF_8).length > 131072); assertTrue(original.contains(NEEDLE));
            assertFalse(original.contains(TOKEN)); assertFalse(original.contains("Bearer ")); assertFalse(original.contains(fixture.server.endpoint()));
            assertEquals(URI, retained.getString("uri")); assertEquals(URI, retained.getJSONArray("contents").getJSONObject(0).getString("uri"));
            assertEquals(snapshot.getString("contentDigest"), retained.getString("contentDigest"));
            assertEquals(McpData.digest(retained.getJSONArray("contents")), retained.getString("contentDigest"));
            JSONObject event = fixture.savedEvent(); assertFalse(event.has("text")); assertFalse(event.has("contents"));
            assertFalse(event.toString().contains(NEEDLE)); assertFalse(event.toString().contains(TOKEN)); assertFalse(event.toString().contains(fixture.server.endpoint()));
            long events = fixture.events(null); int requests = fixture.server.received.size();
            JSONObject duplicate = fixture.save(snapshot, target);
            assertTrue(duplicate.getBoolean("linked")); assertTrue(duplicate.getBoolean("duplicate"));
            assertEquals(attachment.toString(), duplicate.getJSONObject("attachment").toString());
            assertEquals(saved.getJSONObject("sourceRef").toString(), duplicate.getJSONObject("sourceRef").toString());
            assertEquals(events, fixture.events(null)); assertEquals(1, fixture.repo.book.list().length()); assertEquals(requests, fixture.server.received.size());
            int offset = original.indexOf(NEEDLE) - 12; assertTrue(offset > 131072);
            SourceRef selected = fixture.tasks.selectSavedMemorySource(fixture.task, fixture.run, saved.getLong("eventSeq"), offset, NEEDLE.length() + 24);
            String firstRun = fixture.run; fixture.nextRun(); fixture.enabled(true); fixture.enabled(false); fixture.cold();
            assertEquals(firstRun, TaskSavedEvidence.address(selected.sourceId).runId); assertNotEquals(firstRun, fixture.run);
            requests = fixture.server.received.size(); assertTrue(fixture.read(selected).contains(NEEDLE));
            assertEquals(requests, fixture.server.received.size()); assertEquals(1, fixture.server.count("resources/read"));
            JSONObject sources = fixture.tasks.savedMemorySources(fixture.task, 0, 20);
            assertEquals(1, sources.getJSONArray("entries").length()); assertFalse(sources.toString().contains(NEEDLE));
            assertTrue(download.isCancelled()); assertEquals(1, fixture.tasks.list().length()); fixture.noExecution();
        }
    }

    @Test public void observedSourceRevisionAndDisableRejectOldSnapshotsBeforeWritingAnOriginal() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.enabled(true); JSONObject target = fixture.target(), old = fixture.snapshot(new RunControl());
            long revision = fixture.installation.getLong("stateRevision");
            JSONObject update = fixture.owner.mcp("listenMcpUpdates", fixture.args().put("filter", new JSONObject()
                    .put("resourceSubscriptions", new JSONArray().put(URI))));
            assertTrue(update.getBoolean("sourceInvalidated")); assertTrue(update.getLong("currentStateRevision") > revision);
            int requests = fixture.server.received.size(); assertThrows(SecurityException.class, () -> fixture.save(old, target));
            assertEquals(requests, fixture.server.received.size()); assertEquals(0, fixture.repo.book.list().length());
            fixture.refresh(); JSONObject beforeDisable = fixture.snapshot(new RunControl()); fixture.enabled(false);
            requests = fixture.server.received.size(); assertThrows(SecurityException.class, () -> fixture.save(beforeDisable, target));
            assertEquals(requests, fixture.server.received.size()); assertEquals(0, fixture.repo.book.list().length());
            assertEquals(0, fixture.events(McpResourceEvidence.EVENT_TYPE));
            // A new explicit owner read at the new revision is independent of execution grants.
            JSONObject fresh = fixture.snapshot(new RunControl()), saved = fixture.save(fresh, target);
            assertTrue(saved.getBoolean("linked")); assertEquals(1, fixture.repo.book.list().length());
            assertEquals(3, fixture.server.count("resources/read")); fixture.noExecution();
        }
    }

    @Test public void cancelledSaveAndChangedTargetEpochCannotPublishUnderAnOldChoice() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JSONObject snapshot = fixture.snapshot(new RunControl()), target = fixture.target();
            RunControl cancelled = new RunControl(); cancelled.cancel(); int requests = fixture.server.received.size();
            assertThrows(InterruptedException.class, () -> fixture.owner.mcp("saveMcpResource", fixture.savePayload(snapshot, target), cancelled));
            assertEquals(0, fixture.repo.book.list().length()); assertEquals(0, fixture.events(McpResourceEvidence.EVENT_TYPE));
            fixture.nextRun(); assertNotEquals(target.getString("targetPin"), fixture.target().getString("targetPin"));
            assertThrows(SecurityException.class, () -> fixture.save(snapshot, target));
            assertEquals(0, fixture.repo.book.list().length()); assertEquals(0, fixture.tasks.ownerAttachmentReferences(fixture.task).length());
            assertEquals(0, fixture.events(McpResourceEvidence.EVENT_TYPE)); assertEquals(requests, fixture.server.received.size()); fixture.noExecution();
        }
    }

    @Test public void failedOriginalSqlLinkRetainsOneFileAndRetryLinksWithoutAnotherHttpRead() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JSONObject snapshot = fixture.snapshot(new RunControl()), target = fixture.target(); fixture.failIndex();
            JSONObject partial = fixture.save(snapshot, target), attachment = partial.getJSONObject("attachment");
            assertFalse(partial.getBoolean("linked")); assertFalse(partial.has("sourceRef"));
            assertEquals("original_saved_link_incomplete", partial.getString("linkState"));
            assertEquals(1, fixture.repo.book.list().length()); assertTrue(fixture.retained(attachment).contains(NEEDLE));
            assertEquals(0, fixture.events(McpResourceEvidence.EVENT_TYPE)); assertEquals(0, fixture.events("owner_attachment_selected"));
            assertEquals(0, fixture.tasks.ownerAttachmentReferences(fixture.task).length());
            int requests = fixture.server.received.size(); fixture.allowIndex(); JSONObject linked = fixture.save(snapshot, target);
            assertTrue(linked.getBoolean("linked")); assertFalse(linked.getBoolean("duplicate"));
            assertEquals(attachment.toString(), linked.getJSONObject("attachment").toString());
            assertEquals(1, fixture.repo.book.list().length()); assertEquals(1, fixture.events(McpResourceEvidence.EVENT_TYPE));
            assertEquals(1, fixture.events("owner_attachment_selected")); assertEquals(1, fixture.tasks.ownerAttachmentReferences(fixture.task).length());
            assertEquals(requests, fixture.server.received.size()); assertEquals(1, fixture.server.count("resources/read"));
            SourceRef ref = SourceRef.fromJson(linked.getJSONObject("sourceRef")); fixture.cold(); assertFalse(fixture.read(ref).isEmpty()); fixture.noExecution();
        }
    }

    @Test public void coldRecoveryOfPartialSaveKeepsTheOriginalButCannotRestoreOldSnapshotAuthority() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JSONObject snapshot = fixture.snapshot(new RunControl()), target = fixture.target(); fixture.failIndex();
            JSONObject partial = fixture.save(snapshot, target), attachment = partial.getJSONObject("attachment");
            assertFalse(partial.getBoolean("linked")); String original = fixture.retained(attachment);
            fixture.allowIndex(); fixture.cold(); int requests = fixture.server.received.size();
            assertThrows(SecurityException.class, () -> fixture.save(snapshot, target));
            assertEquals(original, fixture.retained(attachment)); assertEquals(1, fixture.repo.book.list().length());
            assertEquals(0, fixture.tasks.ownerAttachmentReferences(fixture.task).length());
            assertEquals(0, fixture.events(McpResourceEvidence.EVENT_TYPE));
            assertEquals(0, fixture.tasks.savedMemorySources(fixture.task, 0, 20).getJSONArray("entries").length());
            assertEquals(requests, fixture.server.received.size()); assertEquals(1, fixture.server.count("resources/read"));
            assertEquals(1, fixture.tasks.list().length()); fixture.noExecution();
        }
    }

    @Test public void modernTemplateOnlySourceSavesOneHistoricalOriginalThroughPartialLinkAndColdBook() throws Exception {
        templateOnlyHistoricalSource(McpData.VERSION);
    }

    @Test public void legacyTemplateOnlySourceSavesOneHistoricalOriginalThroughPartialLinkAndColdBook() throws Exception {
        templateOnlyHistoricalSource(McpData.LEGACY);
    }

    private void templateOnlyHistoricalSource(String protocol) throws Exception {
        try (Fixture fixture = new Fixture(true, protocol)) {
            assertEquals(0, fixture.owner.mcp("listMcpResources", fixture.args()).getJSONArray("resources").length());
            RunControl download = new RunControl(); JSONObject payload = fixture.templatePayload();
            JSONObject snapshot = fixture.owner.mcp("getMcpResource", payload, download), target = fixture.target(); download.cancel();
            assertFalse(snapshot.getBoolean("executionAuthorized")); assertEquals(TEMPLATE, snapshot.getJSONObject("resourceTemplate").getString("uriTemplate"));
            assertEquals("chosen/本机", snapshot.getJSONObject("resourceTemplate").getJSONObject("arguments").getString("id"));
            assertFalse(snapshot.toString().contains(TOKEN));
            fixture.failIndex(); JSONObject partial = fixture.save(snapshot, target); assertFalse(partial.getBoolean("linked"));
            JSONObject attachment = partial.getJSONObject("attachment"); String bytes = fixture.retained(attachment);
            JSONObject retained = new JSONObject(bytes); assertEquals(snapshot.getJSONObject("resourceTemplate").toString(), retained.getJSONObject("resourceTemplate").toString());
            assertEquals(1, fixture.repo.book.list().length()); fixture.allowIndex();
            JSONObject saved = fixture.save(snapshot, target), duplicate = fixture.save(snapshot, target);
            assertTrue(saved.getBoolean("linked")); assertTrue(duplicate.getBoolean("duplicate"));
            assertEquals(attachment.toString(), saved.getJSONObject("attachment").toString()); assertEquals(1, fixture.events(McpResourceEvidence.EVENT_TYPE));
            JSONObject event = fixture.savedEvent(); assertFalse(event.getJSONObject("resourceTemplate").has("arguments"));
            JSONObject publicSource = fixture.repo.book.get(attachment.getString("id")).getJSONObject("source");
            assertFalse(publicSource.has("uri")); assertFalse(publicSource.has("resourceTemplate")); assertTrue(publicSource.has("resourceTemplateDigest"));
            JSONObject forged = new JSONObject(event.toString()); forged.getJSONObject("resourceTemplate").put("argumentsDigest", McpData.digest("replacement"));
            assertThrows(SecurityException.class, () -> fixture.io(() -> McpResourceEvidence.readRetainedText(fixture.repo.book, forged, new RunControl())));
            int offset = bytes.indexOf(NEEDLE) - 12;
            SourceRef selected = fixture.tasks.selectSavedMemorySource(fixture.task, fixture.run, saved.getLong("eventSeq"), offset, NEEDLE.length() + 24);
            fixture.nextRun(); fixture.enabled(true); fixture.enabled(false); fixture.cold();
            int requests = fixture.server.received.size(); assertTrue(fixture.read(selected).contains(NEEDLE));
            assertEquals(requests, fixture.server.received.size()); assertEquals(1, fixture.server.count("resources/read"));
            assertEquals(1, fixture.tasks.list().length()); assertEquals(1, fixture.repo.book.list().length()); fixture.noExecution();
        }
    }

    @Test public void templateDescriptorRevisionCancellationAndLateReplyCannotCreateOrSaveOldSourceAuthority() throws Exception {
        try (Fixture fixture = new Fixture(true, McpData.VERSION)) {
            JSONObject payload = fixture.templatePayload(); fixture.templateDescription = "changed before read";
            assertThrows(SecurityException.class, () -> fixture.owner.mcp("getMcpResource", payload)); assertEquals(0, fixture.server.count("resources/read"));
            fixture.templateDescription = "original template"; RunControl stopped = new RunControl(); stopped.cancel();
            int requests = fixture.server.received.size();
            assertThrows(InterruptedException.class, () -> fixture.owner.mcp("getMcpResource", payload, stopped)); assertEquals(requests, fixture.server.received.size());
            JSONObject snapshot = fixture.owner.mcp("getMcpResource", payload), target = fixture.target(); fixture.enabled(true);
            assertThrows(SecurityException.class, () -> fixture.save(snapshot, target)); assertEquals(0, fixture.repo.book.list().length());
            requests = fixture.server.received.size();
            assertThrows(SecurityException.class, () -> fixture.owner.mcp("getMcpResource", payload)); assertEquals(requests, fixture.server.received.size());
            JSONObject fresh = fixture.templatePayload(); fixture.readEntered = new CountDownLatch(1); fixture.readRelease = new CountDownLatch(1);
            RunControl original = new RunControl(); var pending = fixture.worker.submit(() -> fixture.owner.mcp("getMcpResource", fresh, original));
            try {
                assertTrue(fixture.readEntered.await(3, TimeUnit.SECONDS)); fixture.enabled(false); fixture.readRelease.countDown();
                assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS)); assertTrue(original.isCancelled());
            } finally { fixture.readRelease.countDown(); }
            assertEquals(0, fixture.repo.book.list().length()); assertEquals(0, fixture.events(McpResourceEvidence.EVENT_TYPE));
            assertEquals(2, fixture.server.count("resources/read")); fixture.noExecution();
        }
    }

    @Test public void templateReadsShareTheOriginalFourSnapshotWindowAndDiscardNeverRestoresSaveAuthority() throws Exception {
        try (Fixture fixture = new Fixture(true, McpData.VERSION)) {
            JSONArray opened = new JSONArray();
            for (int i = 0; i < 4; i++) opened.put(fixture.owner.mcp("getMcpResource", fixture.templatePayload()));
            assertThrows(IllegalStateException.class, () -> fixture.owner.mcp("getMcpResource", fixture.templatePayload()));
            JSONObject first = opened.getJSONObject(0), target = fixture.target();
            assertTrue(fixture.owner.mcp("discardMcpResourceSnapshot", new JSONObject().put("snapshotId", first.getString("snapshotId"))).getBoolean("discarded"));
            assertThrows(SecurityException.class, () -> fixture.save(first, target));
            JSONObject replacement = fixture.owner.mcp("getMcpResource", fixture.templatePayload());
            assertNotEquals(first.getString("snapshotId"), replacement.getString("snapshotId"));
            JSONObject saved = fixture.save(replacement, target); assertTrue(saved.getBoolean("linked"));
            fixture.owner.mcp("discardMcpResourceSnapshot", new JSONObject().put("snapshotId", replacement.getString("snapshotId")));
            assertThrows(SecurityException.class, () -> fixture.save(replacement, target));
            assertEquals(1, fixture.repo.book.list().length()); assertEquals(1, fixture.events(McpResourceEvidence.EVENT_TYPE));
            assertTrue(fixture.retained(saved.getJSONObject("attachment")).contains(NEEDLE));
            assertEquals(6, fixture.server.count("resources/read")); fixture.noExecution();
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
