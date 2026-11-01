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
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.store.SecretVault;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real loopback HTTP plus actual PluginsModule/PluginPackages/PluginRules and private JSON file I/O. */
public final class PluginCatalogTest {
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static final class AndroidFiles extends ContextWrapper {
        private final File root;
        private final Map<String, Object> values = new HashMap<>();
        AndroidFiles(File root) { super(null); this.root = root; }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return root; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            Object editor = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                if (method.getName().equals("commit")) return true;
                if (method.getName().equals("apply")) return null;
                if (method.getName().equals("remove")) values.remove((String) args[0]);
                if (method.getName().startsWith("put")) values.put((String) args[0], args[1]);
                return proxy;
            });
            return (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                if (method.getName().equals("edit")) return editor;
                if (method.getName().equals("contains")) return values.containsKey((String) args[0]);
                if (method.getName().startsWith("get") && args != null && args.length == 2) return values.getOrDefault((String) args[0], args[1]);
                throw new AssertionError("unused preference operation: " + method.getName());
            });
        }
    }
    private interface Reply { void send(HttpExchange exchange) throws Exception; }
    private static final class Fixture implements AutoCloseable {
        final File root = Files.createTempDirectory("catalog-fixture-").toFile();
        final PluginsModule plugins;
        final HttpServer server;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final PluginCatalog catalog;
        final AtomicLong clock = new AtomicLong();
        final AtomicInteger packageGets = new AtomicInteger(), catalogGets = new AtomicInteger();
        volatile byte[] index, payload;
        volatile Reply indexReply;
        String revision;
        Fixture() throws Exception {
            AndroidFiles context = new AndroidFiles(root);
            plugins = new PluginsModule(new FeatureContext(context, null, new SecretVault(context), event -> {}));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/catalog.json", exchange -> {
                catalogGets.incrementAndGet();
                try { if (indexReply != null) indexReply.send(exchange); else response(exchange, 200, index); }
                catch (Exception failure) { exchange.close(); }
            });
            server.createContext("/plugin.pwp.json", exchange -> { packageGets.incrementAndGet(); response(exchange, 200, payload); });
            server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().add("Location", url("/plugin.pwp.json")); response(exchange, 302, new byte[0]); });
            server.start();
            catalog = new PluginCatalog(plugins, new File(root, "catalog/sources.json"), 35, clock::get);
            configure("absent", true, null);
            set(manifest("release-A", "echo"));
        }
        String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        JSONArray config(boolean enabled, String pin) throws Exception {
            JSONObject value = new JSONObject().put("id", "personal").put("label", "我的插件目录").put("catalogUrl", url("/catalog.json")).put("enabled", enabled);
            if (pin != null) value.put("pinnedCatalogSha256", pin);
            return new JSONArray().put(value);
        }
        void configure(String expected, boolean enabled, String pin) throws Exception { revision = catalog.configure(config(enabled, pin), expected).getString("revision"); }
        JSONObject manifest(String version, String kind) throws Exception {
            JSONObject tool = new JSONObject().put("name", "show").put("kind", kind).put("effect", "write");
            if (kind.equals("http") || kind.equals("mcp")) tool.put("endpoint", "https://tools.example/execute");
            if (kind.equals("mcp")) tool.put("protocolVersion", "2026-07-28").put("descriptorDigest", "a".repeat(64)).put("serverDigest", "b".repeat(64));
            return PluginRules.manifest(new JSONObject().put("id", "custom").put("name", "自定义插件").put("version", version).put("tools", new JSONArray().put(tool)));
        }
        JSONObject set(JSONObject manifest) throws Exception {
            // Author bytes via W07's actual export, not an imitation package encoder.
            PluginsModule author = new PluginsModule(new FeatureContext(new AndroidFiles(new File(root, "author")), null, new SecretVault(new AndroidFiles(root)), event -> {}));
            JSONObject installed;
            if ("mcp".equals(manifest.getJSONArray("tools").getJSONObject(0).getString("kind"))) {
                // A historical, disabled installation fixture; never bypass the live MCP discovery/install commands.
                installed = new JSONObject().put("id", "custom").put("manifest", manifest).put("installationId", "historical-mcp-fixture")
                        .put("enabled", false).put("grants", new JSONArray());
                JsonFiles.write(new File(root, "author/plugins/manifests.json"), new JSONObject().put("plugins", new JSONArray().put(installed)));
            } else {
                installed = author.handle("install", new JSONObject().put("manifest", manifest));
            }
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            new PluginPackages(author).exportPackage("custom", installed.getString("installationId"), new JSONObject().put("label", "SDK作者目录"), stream, new RunControl());
            payload = stream.toByteArray();
            author.close();
            JSONObject summary = new JSONObject();
            JSONObject tool = manifest.getJSONArray("tools").getJSONObject(0);
            for (String key : new String[]{"name", "kind", "effect", "endpoint", "protocolVersion"}) if (tool.has(key)) summary.put(key, tool.get(key));
            PluginPackages.Inspection inspection = new PluginPackages(plugins).inspect(new java.io.ByteArrayInputStream(payload), new RunControl());
            JSONObject entry = new JSONObject().put("id", "custom").put("version", manifest.getString("version")).put("packageUrl", url("/plugin.pwp.json"))
                    .put("packageSha256", CatalogJson.hash(payload)).put("declarationDigest", inspection.declarationDigest).put("byteSize", payload.length)
                    .put("compatibility", new JSONObject().put("packageFormat", PluginPackages.FORMAT).put("minAndroidApi", 30))
                    .put("capabilities", new JSONArray().put(summary));
            index(entry); return entry;
        }
        void index(JSONObject entry) throws Exception { index = utf8(new JSONObject().put("format", PluginCatalog.FORMAT).put("entries", new JSONArray().put(entry)).toString()); }
        PluginCatalog.Snapshot refresh() throws Exception { return catalog.refresh("personal", new RunControl()); }
        PluginCatalog.Review prepare(PluginCatalog.Snapshot snapshot, String version) throws Exception { return catalog.prepare(snapshot, "custom", version, new RunControl()); }
        @Override public void close() throws Exception { catalog.close(); plugins.close(); server.stop(0); workers.shutdownNow(); }
    }
    private static void response(HttpExchange exchange, int status, byte[] bytes) throws java.io.IOException {
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (java.io.OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    @Test public void actualHttpReviewInstallsThroughActualCasDisabledEmptyGrantsAndHistory() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject old = f.plugins.handle("install", new JSONObject().put("manifest", f.manifest("older", "echo")));
            f.plugins.handle("enable", new JSONObject().put("id", "custom").put("enabled", true));
            f.plugins.handle("grant", new JSONObject().put("id", "custom").put("grants", new JSONArray().put("tool:show")));
            PluginCatalog.Snapshot snapshot = f.refresh();
            JSONObject row = f.catalog.candidates(snapshot, new RunControl()).getJSONArray("entries").getJSONObject(0);
            assertEquals("older", row.getString("currentVersion")); assertFalse(row.getBoolean("capabilitiesVerified"));
            PluginCatalog.Review review = f.prepare(snapshot, "release-A");
            assertTrue(review.view().getBoolean("capabilitiesVerified"));
            assertEquals("older", review.view().getJSONObject("packageReview").getJSONObject("preview").getString("currentVersion"));
            JSONObject receipt = f.catalog.installReviewed(review, review.reviewDigest, new RunControl());
            assertEquals("installed_disabled", receipt.getString("status"));
            assertFalse(receipt.getJSONObject("plugin").getBoolean("enabled"));
            assertEquals(0, f.plugins.toolRegistry().length());
            assertNotEquals(old.getString("installationId"), receipt.getJSONObject("plugin").getString("installationId"));
            assertEquals(1, f.plugins.handle("history", new JSONObject().put("id", "custom")).getJSONArray("versions").length());
            assertEquals(1, f.packageGets.get()); // installation reuses the exact verified bytes, never a second mutable fetch
            assertThrows(IllegalStateException.class, () -> f.catalog.installReviewed(review, review.reviewDigest, new RunControl()));
        }
    }
    @Test public void digestBytesIdentityAndCapabilityClaimsCannotLie() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject entry = f.set(f.manifest("release-A", "http"));
            f.payload = Arrays.copyOf(f.payload, f.payload.length + 1);
            PluginCatalog.Snapshot badBytes = f.refresh();
            assertThrows(SecurityException.class, () -> f.prepare(badBytes, "release-A"));
            entry = f.set(f.manifest("release-A", "http")); entry.getJSONArray("capabilities").getJSONObject(0).put("effect", "read"); f.index(entry);
            PluginCatalog.Snapshot badEffect = f.refresh(); assertThrows(SecurityException.class, () -> f.prepare(badEffect, "release-A"));
            entry = f.set(f.manifest("release-A", "http")); entry.put("declarationDigest", "e".repeat(64)); f.index(entry);
            PluginCatalog.Snapshot badDeclaration = f.refresh(); assertThrows(SecurityException.class, () -> f.prepare(badDeclaration, "release-A"));
            entry = f.set(f.manifest("release-A", "echo")); entry.put("version", "other"); f.index(entry);
            PluginCatalog.Snapshot badVersion = f.refresh(); assertThrows(SecurityException.class, () -> f.prepare(badVersion, "other"));
            assertEquals(0, f.plugins.snapshot().getJSONArray("plugins").length());
        }
    }
    @Test public void opaqueVersionsSameLabelChangesAndDefensiveViewsUseActualDiff() throws Exception {
        try (Fixture f = new Fixture()) {
            f.plugins.handle("install", new JSONObject().put("manifest", f.manifest("release-A", "echo")));
            f.set(f.manifest("release-A", "http")); PluginCatalog.Snapshot snapshot = f.refresh();
            assertEquals("same_version_label_content_unchecked", f.catalog.candidates(snapshot, new RunControl()).getJSONArray("entries").getJSONObject(0).getString("comparison"));
            snapshot.view().getJSONArray("entries").getJSONObject(0).put("packageSha256", "a".repeat(64));
            PluginCatalog.Review review = f.prepare(snapshot, "release-A");
            assertFalse(review.view().getJSONObject("packageReview").getJSONObject("preview").getBoolean("sameManifest"));
            assertEquals(1, review.view().getJSONObject("packageReview").getJSONObject("preview").getJSONArray("changedTools").length());
            review.view().getJSONObject("entry").put("version", "forged");
            assertThrows(SecurityException.class, () -> f.catalog.installReviewed(review, "f".repeat(64), new RunControl()));
            assertEquals("installed_disabled", f.catalog.installReviewed(review, review.reviewDigest, new RunControl()).getString("status"));
        }
    }
    @Test public void incompatibilityBlocksBeforePackageDownloadAndUnknownCatalogVersionsAreExplicit() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject entry = f.set(f.manifest("release-A", "echo")); entry.getJSONObject("compatibility").put("minAndroidApi", 36); f.index(entry);
            PluginCatalog.Snapshot snapshot = f.refresh();
            assertFalse(f.catalog.candidates(snapshot, new RunControl()).getJSONArray("entries").getJSONObject(0).getJSONObject("compatibilityResult").getBoolean("compatible"));
            assertThrows(IllegalStateException.class, () -> f.prepare(snapshot, "release-A")); assertEquals(0, f.packageGets.get());
            entry.getJSONObject("compatibility").put("minAndroidApi", 30).put("packageFormat", "future.v2"); f.index(entry);
            PluginCatalog.Snapshot future = f.refresh(); assertThrows(IllegalStateException.class, () -> f.prepare(future, "release-A"));
            f.index = utf8("{\"format\":\"future.catalog.v2\",\"entries\":[]}"); assertThrows(IllegalArgumentException.class, f::refresh);
        }
    }
    @Test public void pinConfigurationCasPersistenceDisableAndAbaInvalidateOldReview() throws Exception {
        try (Fixture f = new Fixture()) {
            PluginCatalog.Review review = f.prepare(f.refresh(), "release-A"); String original = f.revision;
            f.configure(f.revision, false, null); f.configure(f.revision, true, null);
            assertThrows(IllegalStateException.class, () -> f.catalog.configure(f.config(true, null), original));
            assertThrows(IllegalStateException.class, () -> f.catalog.installReviewed(review, review.reviewDigest, new RunControl()));
            f.configure(f.revision, true, "a".repeat(64)); assertThrows(SecurityException.class, f::refresh);
            f.configure(f.revision, true, CatalogJson.hash(f.index)); assertNotNull(f.refresh());
            try (PluginCatalog reopened = new PluginCatalog(f.plugins, new File(f.root, "catalog/sources.json"), 35)) {
                assertEquals(f.revision, reopened.sources().getString("revision"));
                assertEquals("personal", reopened.sources().getJSONArray("sources").getJSONObject(0).getString("id"));
            }
            f.configure(f.revision, false, null); assertThrows(IllegalStateException.class, f::refresh);
        }
    }
    @Test public void concurrentRefreshCannotPublishOlderResultOrKeepOldApproval() throws Exception {
        try (Fixture f = new Fixture()) {
            PluginCatalog.Review review = f.prepare(f.refresh(), "release-A");
            CountDownLatch firstRead = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger count = new AtomicInteger();
            byte[] oldIndex = f.index;
            f.indexReply = exchange -> {
                if (count.incrementAndGet() == 1) { firstRead.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture timeout"); response(exchange, 200, oldIndex); }
                else response(exchange, 200, f.index);
            };
            Future<PluginCatalog.Snapshot> slow = f.workers.submit(f::refresh);
            assertTrue(firstRead.await(3, TimeUnit.SECONDS));
            f.set(f.manifest("release-B", "echo")); PluginCatalog.Snapshot latest = f.refresh();
            release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> slow.get(3, TimeUnit.SECONDS));
            assertEquals("release-B", f.catalog.candidates(latest, new RunControl()).getJSONArray("entries").getJSONObject(0).getString("version"));
            assertThrows(IllegalStateException.class, () -> f.catalog.installReviewed(review, review.reviewDigest, new RunControl()));
        }
    }
    @Test public void actualCurrentInstallationCasAndExpiryStopPreparedUpdates() throws Exception {
        try (Fixture f = new Fixture()) {
            PluginCatalog.Review absent = f.prepare(f.refresh(), "release-A");
            f.plugins.handle("install", new JSONObject().put("manifest", f.manifest("external", "echo")));
            assertThrows(IllegalStateException.class, () -> f.catalog.installReviewed(absent, absent.reviewDigest, new RunControl()));
            PluginCatalog.Review expiring = f.prepare(f.refresh(), "release-A"); f.clock.addAndGet(TimeUnit.MINUTES.toNanos(10));
            assertThrows(IllegalStateException.class, () -> f.catalog.validateReview(expiring, new RunControl()));
            assertEquals("external", f.plugins.snapshot().getJSONArray("plugins").getJSONObject(0).getJSONObject("manifest").getString("version"));
        }
    }
    @Test public void mcpHistoricalPackageOnlyReturnsFreshRediscoveryWithoutInstalling() throws Exception {
        try (Fixture f = new Fixture()) {
            f.set(f.manifest("mcp-current", "mcp")); PluginCatalog.Review review = f.prepare(f.refresh(), "mcp-current");
            JSONObject receipt = f.catalog.installReviewed(review, review.reviewDigest, new RunControl());
            assertEquals("rediscovery_required", receipt.getString("status")); assertFalse(receipt.getBoolean("installed"));
            assertEquals(0, f.plugins.snapshot().getJSONArray("plugins").length());
            assertThrows(IllegalStateException.class, () -> f.catalog.installReviewed(review, review.reviewDigest, new RunControl()));
        }
    }
    @Test public void canceledDownloadAndCanceledReviewCannotInstallAndPostCommitReceiptSurvivesCancel() throws Exception {
        try (Fixture f = new Fixture()) {
            RunControl already = new RunControl(); already.cancel();
            assertThrows(InterruptedException.class, () -> f.catalog.refresh("personal", already)); assertEquals(0, f.catalogGets.get());
            PluginCatalog.Review review = f.prepare(f.refresh(), "release-A");
            assertThrows(InterruptedException.class, () -> f.catalog.installReviewed(review, review.reviewDigest, already));
            assertEquals(0, f.plugins.snapshot().getJSONArray("plugins").length());
        }
        try (Fixture f = new Fixture()) {
            RunControl control = new RunControl();
            EngineModule wrapsActual = new EngineModule() {
                public String id() { return "plugins"; }
                public JSONObject snapshot() throws Exception { return f.plugins.snapshot(); }
                public JSONObject handle(String command, JSONObject input) throws Exception {
                    JSONObject result = f.plugins.handle(command, input);
                    if (command.equals("install")) control.cancel(); return result;
                }
            };
            try (PluginCatalog catalog = new PluginCatalog(wrapsActual, new File(f.root, "post/sources.json"), 35)) {
                catalog.configure(f.config(true, null), "absent");
                PluginCatalog.Review review = catalog.prepare(catalog.refresh("personal", new RunControl()), "custom", "release-A", new RunControl());
                assertEquals("installed_disabled", catalog.installReviewed(review, review.reviewDigest, control).getString("status"));
                assertTrue(control.isCancelled());
            }
        }
    }
    @Test public void unknownReceiptConsumesReviewAndNeverReplaysTheActualInstall() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger installs = new AtomicInteger();
            EngineModule uncertain = new EngineModule() {
                public String id() { return "plugins"; }
                public JSONObject snapshot() throws Exception { return f.plugins.snapshot(); }
                public JSONObject handle(String command, JSONObject input) throws Exception {
                    JSONObject actual = f.plugins.handle(command, input);
                    if (command.equals("install")) { installs.incrementAndGet(); throw new java.io.IOException("lost local receipt after commit"); }
                    return actual;
                }
            };
            try (PluginCatalog catalog = new PluginCatalog(uncertain, new File(f.root, "unknown/sources.json"), 35)) {
                catalog.configure(f.config(true, null), "absent");
                PluginCatalog.Review review = catalog.prepare(catalog.refresh("personal", new RunControl()), "custom", "release-A", new RunControl());
                JSONObject receipt = catalog.installReviewed(review, review.reviewDigest, new RunControl());
                assertEquals("unknown", receipt.getString("status")); assertTrue(receipt.isNull("installed")); assertFalse(receipt.getBoolean("replayAllowed"));
                assertThrows(IllegalStateException.class, () -> catalog.installReviewed(review, review.reviewDigest, new RunControl()));
                assertEquals(1, installs.get()); assertEquals(1, f.plugins.snapshot().getJSONArray("plugins").length());
            }
        }
    }
    @Test public void strictCatalogRejectsDuplicateKeysUnknownFieldsCrossOriginAndOversize() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject entry = f.set(f.manifest("release-A", "echo"));
            entry.put("packageUrl", "https://other.example/plugin.pwp.json"); f.index(entry); assertThrows(SecurityException.class, f::refresh);
            entry = f.set(f.manifest("release-A", "echo")); entry.put("grants", new JSONArray()); f.index(entry); assertThrows(IllegalArgumentException.class, f::refresh);
            entry = f.set(f.manifest("release-A", "echo")); f.index = utf8(new JSONObject().put("format", PluginCatalog.FORMAT).put("entries", new JSONArray().put(entry).put(entry)).toString()); assertThrows(IllegalArgumentException.class, f::refresh);
            f.index = utf8("{\"format\":\"" + PluginCatalog.FORMAT + "\",\"format\":\"other\",\"entries\":[]}"); assertThrows(IllegalArgumentException.class, f::refresh);
            f.index = new byte[PluginCatalog.MAX_CATALOG_BYTES + 1]; assertThrows(IllegalArgumentException.class, f::refresh);
            assertEquals(0, f.packageGets.get());
        }
    }
    @Test public void redirectsAreNotFollowedAndFailedRefreshInvalidatesApproval() throws Exception {
        try (Fixture f = new Fixture()) {
            PluginCatalog.Review review = f.prepare(f.refresh(), "release-A");
            f.indexReply = exchange -> { exchange.getResponseHeaders().add("Location", f.url("/plugin.pwp.json")); response(exchange, 302, new byte[0]); };
            assertThrows(IllegalStateException.class, f::refresh);
            assertEquals(1, f.packageGets.get());
            assertThrows(IllegalStateException.class, () -> f.catalog.installReviewed(review, review.reviewDigest, new RunControl()));
        }
    }
    @Test public void strictUtf8AndIntegersDoNotSilentlyDowngradeOrRoundMetadata() throws Exception {
        for (byte[] bad : new byte[][]{new byte[]{'{', '"', (byte) 0xc0, (byte) 0xaf}, utf8("{\"x\":1.5}"), utf8("{\"x\":1e2}"), utf8("{\"x\":01}"), utf8("{\"x\":null}"), utf8("{\"x\":\"\\uD800\"}"), utf8("{\"x\":1,}"), utf8("{x:1}"), utf8("{\"x\":1} trailing")})
            assertThrows(Exception.class, () -> CatalogJson.read(bad));
        assertEquals(Integer.MAX_VALUE, CatalogJson.integer(CatalogJson.read(utf8("{\"api\":2147483647}")), "api", 1, Integer.MAX_VALUE));
    }
    @Test public void cancellationInterruptsAnActualPendingHttpReadWithoutInstalling() throws Exception {
        try (Fixture f = new Fixture()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            f.indexReply = exchange -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); response(exchange, 200, f.index); };
            RunControl control = new RunControl();
            Future<PluginCatalog.Snapshot> pending = f.workers.submit(() -> f.catalog.refresh("personal", control));
            assertTrue(entered.await(3, TimeUnit.SECONDS)); control.cancel();
            try { assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            assertEquals(0, f.packageGets.get()); assertEquals(0, f.plugins.snapshot().getJSONArray("plugins").length());
        }
    }
    @Test public void twoPreparedUpdatesCannotBothCommitAgainstTheSameInstallation() throws Exception {
        try (Fixture f = new Fixture()) {
            PluginCatalog.Snapshot snapshot = f.refresh();
            PluginCatalog.Review a = f.prepare(snapshot, "release-A"), b = f.prepare(snapshot, "release-A");
            CountDownLatch start = new CountDownLatch(1);
            Future<String> one = f.workers.submit(() -> { start.await(); try { return f.catalog.installReviewed(a, a.reviewDigest, new RunControl()).getString("status"); } catch (IllegalStateException stale) { return "stale"; } });
            Future<String> two = f.workers.submit(() -> { start.await(); try { return f.catalog.installReviewed(b, b.reviewDigest, new RunControl()).getString("status"); } catch (IllegalStateException stale) { return "stale"; } });
            start.countDown(); String first = one.get(3, TimeUnit.SECONDS), second = two.get(3, TimeUnit.SECONDS);
            assertTrue((first.equals("installed_disabled") && second.equals("stale")) || (second.equals("installed_disabled") && first.equals("stale")));
            assertEquals(1, f.plugins.snapshot().getJSONArray("plugins").length());
            assertEquals(0, f.plugins.handle("history", new JSONObject().put("id", "custom")).getJSONArray("versions").length());
        }
    }
    @Test public void changedServerBytesAfterReviewCannotChangeTheInstalledDeclaration() throws Exception {
        try (Fixture f = new Fixture()) {
            PluginCatalog.Review review = f.prepare(f.refresh(), "release-A");
            f.set(f.manifest("changed-on-server", "http"));
            JSONObject receipt = f.catalog.installReviewed(review, review.reviewDigest, new RunControl());
            assertEquals("release-A", receipt.getJSONObject("plugin").getJSONObject("manifest").getString("version"));
            assertEquals("echo", receipt.getJSONObject("plugin").getJSONObject("manifest").getJSONArray("tools").getJSONObject(0).getString("kind"));
            assertEquals(1, f.packageGets.get());
        }
    }
}
