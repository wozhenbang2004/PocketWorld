package dev.pocketengine.features.plugins.catalog;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.plugins.PluginRules;
import dev.pocketengine.features.plugins.PluginsModule;
import dev.pocketengine.features.plugins.distribution.PluginPackages;
import dev.pocketengine.features.plugins.sdk.js.JsPluginPackage;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.store.SecretVault;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PluginCatalogSessionTest {
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static final class AndroidFiles extends ContextWrapper {
        final File root;
        AndroidFiles(File root) { super(null); this.root = root; }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return root; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            Map<String, Object> values = new HashMap<>();
            Object editor = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                if (method.getName().equals("commit")) return true;
                if (method.getName().equals("apply")) return null;
                if (method.getName().equals("remove")) values.remove((String) args[0]);
                if (method.getName().startsWith("put")) values.put((String) args[0], args[1]); return proxy;
            });
            return (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                if (method.getName().equals("edit")) return editor;
                if (method.getName().equals("contains")) return values.containsKey((String) args[0]);
                if (method.getName().startsWith("get") && args != null && args.length == 2) return values.getOrDefault((String) args[0], args[1]);
                throw new AssertionError(method.getName());
            });
        }
    }
    interface Reply { void respond(HttpExchange exchange) throws Exception; }
    public static final class Fixture implements AutoCloseable {
        public final File root = Files.createTempDirectory("catalog-session-").toFile();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService workers = Executors.newCachedThreadPool();
        final Map<String, byte[]> responses = new ConcurrentHashMap<>();
        final Map<String, Reply> replies = new ConcurrentHashMap<>();
        final AtomicInteger gets = new AtomicInteger();
        final AtomicBoolean allowed = new AtomicBoolean(true);
        public final Object owner = new Object();
        final PluginsModule plugins;
        final PluginCatalog catalog;
        final ExecutablePluginCatalog js;
        public PluginCatalogSession session;
        public Fixture() throws Exception {
            AndroidFiles context = new AndroidFiles(root); plugins = new PluginsModule(new FeatureContext(context, null, new SecretVault(context), event -> {}));
            catalog = new PluginCatalog(plugins, new File(root, "catalog/sources.json"), 35); js = new ExecutablePluginCatalog(catalog, new File(root, "catalog/js.json"));
            server.setExecutor(workers); server.createContext("/", exchange -> {
                gets.incrementAndGet(); String path = exchange.getRequestURI().getPath();
                try { Reply special = replies.get(path); if (special != null) special.respond(exchange); else response(exchange, responses.get(path)); }
                catch (Exception error) { exchange.close(); }
            }); server.start();
            JSONArray sources = new JSONArray();
            for (String id : new String[]{"declarations", "scripts"}) sources.put(new JSONObject().put("id", id).put("label", id).put("catalogUrl", url("/" + id)).put("enabled", true));
            catalog.configure(sources, "absent"); session = session(catalog, plugins);
            setDeclaration("same"); setJs("one", "throw new Error('initialization must never run in review');\r\ndefineExtension({tools:[]});\n");
        }
        String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        PluginCatalogSession session(PluginCatalog catalog, EngineModule plugins) { return new PluginCatalogSession(catalog, js, plugins, owner, UUID.randomUUID().toString(), () -> { if (!allowed.get()) throw new SecurityException("owner revoked"); }); }
        static void response(HttpExchange exchange, byte[] bytes) throws Exception { if (bytes == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; } exchange.sendResponseHeaders(200, bytes.length); try (java.io.OutputStream out = exchange.getResponseBody()) { out.write(bytes); } }
        void setDeclaration(String version) throws Exception {
            JSONObject manifest = PluginRules.manifest(new JSONObject().put("id", "decl").put("name", "声明工具").put("version", version).put("tools", new JSONArray().put(new JSONObject().put("name", "show").put("kind", "echo"))));
            AndroidFiles authorContext = new AndroidFiles(new File(root, "author")); PluginsModule author = new PluginsModule(new FeatureContext(authorContext, null, new SecretVault(authorContext), event -> {}));
            JSONObject row = author.handle("install", new JSONObject().put("manifest", manifest)); ByteArrayOutputStream out = new ByteArrayOutputStream();
            new PluginPackages(author).exportPackage("decl", row.getString("installationId"), new JSONObject().put("label", "作者"), out, new RunControl()); author.close();
            byte[] payload = out.toByteArray(); responses.put("/decl.pwp.json", payload);
            PluginPackages.Inspection inspection = new PluginPackages(plugins).inspect(new ByteArrayInputStream(payload), new RunControl());
            JSONObject entry = new JSONObject().put("id", "decl").put("version", version).put("packageUrl", url("/decl.pwp.json")).put("packageSha256", CatalogJson.hash(payload))
                    .put("declarationDigest", inspection.declarationDigest).put("byteSize", payload.length).put("compatibility", new JSONObject().put("packageFormat", PluginPackages.FORMAT).put("minAndroidApi", 30))
                    .put("capabilities", new JSONArray().put(new JSONObject().put("name", "show").put("kind", "echo").put("effect", "read")));
            responses.put("/declarations", bytes(new JSONObject().put("format", PluginCatalog.FORMAT).put("entries", new JSONArray().put(entry)).toString()));
        }
        JsPluginPackage setJs(String version, String source) throws Exception {
            JsPluginPackage pkg = JsPluginPackage.author(new ByteArrayInputStream(bytes(source)), "script", "我的扩展", version, new RunControl()); responses.put("/script.pwjs.json", pkg.packageBytes());
            JSONObject entry = new JSONObject().put("id", pkg.id).put("version", pkg.version).put("packageUrl", url("/script.pwjs.json")).put("packageSha256", pkg.packageSha256).put("sourceSha256", pkg.sourceSha256).put("byteSize", pkg.byteSize);
            responses.put("/scripts", bytes(new JSONObject().put("format", ExecutablePluginCatalog.FORMAT).put("entries", new JSONArray().put(entry)).toString())); return pkg;
        }
        public PluginCatalogSession.View request(String command, JSONObject payload) throws Exception { return session.begin(owner, UUID.randomUUID().toString(), command, payload).execute(); }
        public PluginCatalogSession.View prepareDeclaration() throws Exception { request("refresh", new JSONObject().put("sourceId", "declarations")); return request("prepare", new JSONObject().put("sourceId", "declarations").put("id", "decl").put("version", "same")); }
        PluginCatalogSession.View prepareJs() throws Exception { request("jsRefresh", new JSONObject().put("sourceId", "scripts")); return request("jsPrepare", new JSONObject().put("sourceId", "scripts").put("id", "script").put("version", "one")); }
        @Override public void close() { session.close(); js.close(); catalog.close(); plugins.close(); server.stop(0); workers.shutdownNow(); }
    }

    @Test public void ownerAndOpaqueReviewCannotBeForgedByJsonOrAnotherSession() throws Exception {
        try (Fixture f = new Fixture()) {
            assertThrows(SecurityException.class, () -> f.session.begin(new Object(), "x", "read", new JSONObject()));
            assertThrows(SecurityException.class, () -> f.session.begin(f.owner, "x", "submit", new JSONObject().put("approved", true).put("reviewDigest", "a".repeat(64))));
            PluginCatalogSession.View review = f.prepareDeclaration(); assertNotNull(review.approval);
            try (PluginCatalogSession other = f.session(f.catalog, f.plugins)) { assertThrows(SecurityException.class, () -> other.submit(f.owner, "other", review.approval)); }
            assertThrows(SecurityException.class, () -> f.session.changeJs(f.owner, "wrong-kind", review.approval, "approve"));
            assertEquals(0, f.plugins.snapshot().getJSONArray("plugins").length());
        }
    }
    @Test public void actualHttpFlowInstallsDisabledAndNativeRequestsAreOneShot() throws Exception {
        try (Fixture f = new Fixture()) {
            PluginCatalogSession.View prepared = f.prepareDeclaration();
            PluginCatalogSession.Request submit = f.session.submit(f.owner, "install-once", prepared.approval);
            assertTrue(f.session.view(f.owner).data().getBoolean("busy")); assertNull(f.session.view(f.owner).approval);
            assertThrows(IllegalStateException.class, () -> f.session.begin(f.owner, "racing-read", "read", new JSONObject()));
            PluginCatalogSession.View result = submit.execute(); assertEquals("installed_disabled", result.data().getJSONObject("result").getString("status"));
            assertEquals("install-once", result.data().getString("requestId")); assertFalse(result.data().getBoolean("busy"));
            assertEquals(0, f.plugins.toolRegistry().length()); assertNotNull(submit.receipt());
            assertThrows(IllegalStateException.class, submit::execute); assertThrows(SecurityException.class, () -> f.session.submit(f.owner, "again", prepared.approval));
        }
    }
    @Test public void selectionChangeAndRequestReplacementInvalidateOldApprovalAndLateReply() throws Exception {
        try (Fixture f = new Fixture()) {
            PluginCatalogSession.View old = f.prepareDeclaration(); f.request("read", new JSONObject());
            assertThrows(SecurityException.class, () -> f.session.submit(f.owner, "stale", old.approval));
            PluginCatalogSession.Request first = f.session.begin(f.owner, "first", "read", new JSONObject());
            PluginCatalogSession.Request second = f.session.begin(f.owner, "second", "read", new JSONObject());
            PluginCatalogSession.View latest = second.execute(), late = first.execute();
            assertTrue(f.session.accepts(f.owner, latest)); assertFalse(f.session.accepts(f.owner, late));
            assertEquals("first", late.data().getString("requestId")); assertEquals("superseded", late.data().getString("status"));
            assertEquals("failed", f.request("prepare", new JSONObject().put("sourceId", "scripts").put("id", "decl").put("version", "same")).data().getString("status"));
        }
    }
    @Test public void liveOwnerRevocationAndExecutorRejectionDoNotDispatch() throws Exception {
        try (Fixture f = new Fixture()) {
            PluginCatalogSession.Request request = f.session.begin(f.owner, "queued", "refresh", new JSONObject().put("sourceId", "declarations")); int gets = f.gets.get();
            f.allowed.set(false); assertEquals("cancelled", request.execute().data().getString("status")); assertEquals(gets, f.gets.get());
            f.allowed.set(true); PluginCatalogSession.Request rejected = f.session.begin(f.owner, "rejected", "read", new JSONObject());
            assertFalse(rejected.rejected().data().getBoolean("busy")); assertThrows(IllegalStateException.class, rejected::execute);
            assertFalse(f.request("read", new JSONObject()).data().getBoolean("busy"));
        }
    }
    @Test public void pendingReadCancellationClearsApprovalWithoutReusingOldControl() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            f.replies.put("/declarations", exchange -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); Fixture.response(exchange, f.responses.get("/declarations")); });
            PluginCatalogSession.Request request = f.session.begin(f.owner, "network", "refresh", new JSONObject().put("sourceId", "declarations")); Future<PluginCatalogSession.View> pending = f.workers.submit(request::execute);
            assertTrue(entered.await(3, TimeUnit.SECONDS)); f.session.cancel(f.owner);
            try { assertEquals("cancelled", pending.get(3, TimeUnit.SECONDS).data().getString("status")); } finally { release.countDown(); }
            f.replies.remove("/declarations"); assertNotNull(f.prepareDeclaration().approval);
        }
    }
    @Test public void defensiveViewCannotReplaceSourceOrEnableApproval() throws Exception {
        try (Fixture f = new Fixture()) {
            PluginCatalogSession.View view = f.prepareDeclaration(); view.data().getJSONObject("review").put("reviewDigest", "forged");
            view.data().put("busy", true).put("unresolved", true).getJSONObject("review").getJSONObject("entry").put("version", "forged");
            PluginCatalogSession.View result = f.session.submit(f.owner, "true-review", view.approval).execute();
            assertEquals("same", result.data().getJSONObject("result").getJSONObject("plugin").getJSONObject("manifest").getString("version"));
        }
    }
    @Test public void closedPageRejectsLateViewButRetainsTheActualCommittedReceipt() throws Exception {
        try (Fixture f = new Fixture()) {
            final PluginCatalogSession[] holder = new PluginCatalogSession[1];
            EngineModule wrapped = new EngineModule() { public String id() { return "plugins"; } public JSONObject snapshot() throws Exception { return f.plugins.snapshot(); }
                public JSONObject handle(String command, JSONObject input) throws Exception { JSONObject result = f.plugins.handle(command, input); if (command.equals("install")) holder[0].close(); return result; } };
            try (PluginCatalog separate = new PluginCatalog(wrapped, new File(f.root, "catalog/sources.json"), 35)) {
                f.session.close(); f.session = holder[0] = f.session(separate, wrapped);
                PluginCatalogSession.View view = f.prepareDeclaration(); PluginCatalogSession.Request submit = f.session.submit(f.owner, "closed-after-commit", view.approval);
                PluginCatalogSession.View late = submit.execute(); assertFalse(f.session.accepts(f.owner, late));
                assertEquals("installed_disabled", submit.receipt().getString("status")); assertEquals(1, f.plugins.snapshot().getJSONArray("plugins").length());
            }
        }
    }
    @Test public void unknownReceiptRemainsBlockedAndQueryOnlyReadsActualState() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger installs = new AtomicInteger();
            EngineModule wrapped = new EngineModule() { public String id() { return "plugins"; } public JSONObject snapshot() throws Exception { return f.plugins.snapshot(); }
                public JSONObject handle(String command, JSONObject input) throws Exception { JSONObject result = f.plugins.handle(command, input); if (command.equals("install")) { installs.incrementAndGet(); throw new java.io.IOException("lost after commit"); } return result; } };
            try (PluginCatalog separate = new PluginCatalog(wrapped, new File(f.root, "catalog/sources.json"), 35)) {
                f.session.close(); f.session = f.session(separate, wrapped); PluginCatalogSession.View view = f.prepareDeclaration();
                PluginCatalogSession.View result = f.session.submit(f.owner, "unknown", view.approval).execute(); assertTrue(result.data().getBoolean("unresolved"));
                assertThrows(IllegalStateException.class, () -> f.session.begin(f.owner, "retry", "prepare", new JSONObject()));
                PluginCatalogSession.View read = f.request("queryInstalled", new JSONObject()); assertEquals(1, read.data().getJSONObject("installed").getJSONArray("plugins").length());
                assertTrue(read.data().getBoolean("unresolved")); assertNull(read.approval); assertEquals(1, installs.get());
            }
        }
    }
    @Test public void jsFlowSeparatesStaticInstallApprovalAndEnableWithoutExecutingInitialization() throws Exception {
        try (Fixture f = new Fixture()) {
            PluginCatalogSession.View view = f.prepareJs(); assertEquals("js_install", view.approval.kind); assertFalse(view.data().getJSONObject("review").getBoolean("executed"));
            f.session.submit(f.owner, "js-install", view.approval).execute(); assertEquals(0, f.js.approvedSources(new RunControl()).size());
            PluginCatalogSession.View installed = f.request("jsReviewInstalled", new JSONObject().put("id", "script"));
            assertEquals("js_permission", installed.approval.kind);
            assertEquals("failed", f.session.changeJs(f.owner, "early-enable", installed.approval, "enable").execute().data().getString("status"));
            installed = f.request("jsReviewInstalled", new JSONObject().put("id", "script")); f.session.changeJs(f.owner, "approve-source", installed.approval, "approve").execute();
            assertEquals(0, f.js.approvedSources(new RunControl()).size());
            installed = f.request("jsReviewInstalled", new JSONObject().put("id", "script")); f.session.changeJs(f.owner, "enable-source", installed.approval, "enable").execute();
            assertEquals(1, f.js.approvedSources(new RunControl()).size()); assertEquals(0, f.plugins.snapshot().getJSONArray("plugins").length());
        }
    }
    @Test public void mcpReceiptStaysAnUntrustedRediscoveryHintAndDoesNotInstallOrContactService() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject tool = new JSONObject().put("name", "remote").put("kind", "mcp").put("effect", "write").put("endpoint", "https://no-network.example/mcp")
                    .put("protocolVersion", "2026-07-28").put("serverDigest", "a".repeat(64)).put("descriptorDigest", "b".repeat(64));
            JSONObject manifest = PluginRules.manifest(new JSONObject().put("id", "decl").put("name", "历史MCP声明").put("version", "same").put("tools", new JSONArray().put(tool)));
            AndroidFiles context = new AndroidFiles(new File(f.root, "historical"));
            JsonFiles.write(new File(context.root, "plugins/manifests.json"), new JSONObject().put("plugins", new JSONArray().put(new JSONObject().put("id", "decl").put("manifest", manifest).put("installationId", "historical").put("enabled", false).put("grants", new JSONArray()))));
            PluginsModule author = new PluginsModule(new FeatureContext(context, null, new SecretVault(context), event -> {})); ByteArrayOutputStream out = new ByteArrayOutputStream();
            new PluginPackages(author).exportPackage("decl", "historical", new JSONObject().put("label", "历史声明"), out, new RunControl()); author.close(); byte[] payload = out.toByteArray();
            PluginPackages.Inspection inspection = new PluginPackages(f.plugins).inspect(new ByteArrayInputStream(payload), new RunControl());
            JSONObject summary = new JSONObject(); for (String key : new String[]{"name", "kind", "effect", "endpoint", "protocolVersion"}) summary.put(key, manifest.getJSONArray("tools").getJSONObject(0).get(key));
            JSONObject entry = new JSONObject().put("id", "decl").put("version", "same").put("packageUrl", f.url("/decl.pwp.json")).put("packageSha256", CatalogJson.hash(payload)).put("declarationDigest", inspection.declarationDigest)
                    .put("byteSize", payload.length).put("compatibility", new JSONObject().put("packageFormat", PluginPackages.FORMAT).put("minAndroidApi", 30)).put("capabilities", new JSONArray().put(summary));
            f.responses.put("/decl.pwp.json", payload); f.responses.put("/declarations", bytes(new JSONObject().put("format", PluginCatalog.FORMAT).put("entries", new JSONArray().put(entry)).toString()));
            PluginCatalogSession.View prepared = f.prepareDeclaration(); PluginCatalogSession.View result = f.session.submit(f.owner, "mcp-hint", prepared.approval).execute();
            assertEquals("rediscovery_required", result.data().getJSONObject("result").getString("status")); assertFalse(result.data().getJSONObject("result").getBoolean("installed"));
            assertTrue(result.data().getJSONObject("result").getJSONArray("discovery").getJSONObject(0).getBoolean("untrusted")); assertEquals(0, f.plugins.snapshot().getJSONArray("plugins").length());
        }
    }
    @Test public void waitingForJsSnapshotMustNotHoldSessionPublishLockOrBlockCancellation() throws Exception {
        try (Fixture f = new Fixture()) {
            PluginCatalogSession.Request request = f.session.begin(f.owner, "lock-order", "read", new JSONObject());
            java.util.concurrent.atomic.AtomicReference<Thread> reader = new java.util.concurrent.atomic.AtomicReference<>();
            Future<PluginCatalogSession.View> reading;
            synchronized (f.js) {
                reading = f.workers.submit(() -> { reader.set(Thread.currentThread()); return request.execute(); });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); boolean waitingOnOurMonitor = false;
                while (System.nanoTime() < deadline) {
                    Thread thread = reader.get();
                    java.lang.management.ThreadInfo info = thread == null ? null : java.lang.management.ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId());
                    if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockOwnerId() == Thread.currentThread().getId()) { waitingOnOurMonitor = true; break; }
                    Thread.sleep(5);
                }
                assertTrue("read reached the actual JS monitor", waitingOnOurMonitor);
                Future<PluginCatalogSession.View> cancelling = f.workers.submit(() -> f.session.cancel(f.owner));
                // The old Session->Executable publish ordering times out here while this thread owns JS.
                assertNotNull(cancelling.get(400, TimeUnit.MILLISECONDS));
            }
            assertEquals("cancelled", reading.get(3, TimeUnit.SECONDS).data().getString("status"));
        }
    }
}
