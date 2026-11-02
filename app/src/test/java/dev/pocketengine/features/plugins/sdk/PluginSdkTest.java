package dev.pocketengine.features.plugins.sdk;

import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.plugins.PluginRevisions;
import dev.pocketengine.features.plugins.distribution.SdkPackageCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PluginSdkTest {
    private final PluginSdk sdk = PluginSdk.offline();
    private static ByteArrayInputStream input(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    private static JSONObject source() throws Exception { return new JSONObject().put("label", "Local test author"); }
    private static JSONObject echo() throws Exception { return PluginSdk.template("echo", "custom.echo", "Custom", "opaque dev 1", null); }
    private PluginSdk.Prepared prepare(JSONObject manifest) throws Exception { return sdk.prepare(input(manifest.toString()), source(), new RunControl()); }
    private static JSONObject args() throws Exception { return new JSONObject().put("text", " actual local text "); }
    private static void fails(Class<? extends Throwable> type, Checked action) throws Exception {
        try { action.run(); fail("Expected " + type.getSimpleName()); } catch (Throwable value) { if (!type.isInstance(value)) throw value; }
    }
    private interface Checked { void run() throws Exception; }

    @Test public void packRoundTripUsesActualW07AndDistinctDigests() throws Exception {
        PluginSdk.Prepared prepared = prepare(echo());
        PluginSdk.Prepared reread = sdk.inspect(prepared.openPackage(), new RunControl());
        assertEquals(prepared.receipt().getString("packageSha256"), reread.receipt().getString("packageSha256"));
        assertNotEquals(prepared.receipt().getString("packageSha256"), prepared.receipt().getString("declarationDigest"));
        assertEquals(prepared.bytes().length, prepared.receipt().getInt("byteSize"));
        assertFalse(prepared.receipt().getBoolean("installed"));
        assertFalse(prepared.receipt().getBoolean("installedStateCompared"));
        byte[] altered = prepared.bytes(); altered[0] = 'x';
        assertEquals('{', prepared.bytes()[0]);
    }

    @Test public void packageUnknownFieldsNeverDisappearDuringNormalization() throws Exception {
        JSONObject manifest = echo().put("token", "never-pack");
        fails(IllegalArgumentException.class, () -> prepare(manifest));
        JSONObject next = echo(); next.getJSONArray("tools").getJSONObject(0).put("script", "arbitrary-code");
        fails(IllegalArgumentException.class, () -> prepare(next));
        fails(IllegalArgumentException.class, () -> sdk.prepare(input(echo().toString()), source().put("grants", new JSONArray()), new RunControl()));
    }

    @Test public void strictDuplicateJsonAndMalformedUtf8AreRejected() throws Exception {
        fails(IllegalArgumentException.class, () -> sdk.prepare(input("{\"id\":\"a\",\"id\":\"b\"}"), source(), new RunControl()));
        fails(java.nio.charset.CharacterCodingException.class, () -> sdk.prepare(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28}), source(), new RunControl()));
        fails(IllegalArgumentException.class, () -> sdk.prepare(input("{id:'x'}"), source(), new RunControl()));
    }

    @Test public void packageBoundsAndCancellationAreReal() throws Exception {
        fails(IllegalArgumentException.class, () -> sdk.inspect(new ByteArrayInputStream(new byte[262145]), new RunControl()));
        RunControl cancelled = new RunControl(); cancelled.cancel();
        fails(InterruptedException.class, () -> sdk.prepare(input(echo().toString()), source(), cancelled));
        PluginSdk.Prepared prepared = prepare(echo());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fails(InterruptedException.class, () -> sdk.write(prepared, out, cancelled));
        assertEquals(0, out.size());
    }

    @Test public void writerReportsPendingDestinationCloseAndExactBytes() throws Exception {
        PluginSdk.Prepared prepared = prepare(echo()); ByteArrayOutputStream out = new ByteArrayOutputStream();
        JSONObject receipt = sdk.write(prepared, out, new RunControl());
        assertTrue(receipt.getBoolean("requiresDestinationClose")); assertArrayEquals(prepared.bytes(), out.toByteArray());
        fails(java.io.IOException.class, () -> sdk.write(prepared, new java.io.OutputStream() { public void write(int value) throws java.io.IOException { throw new java.io.IOException("full"); } }, new RunControl()));
    }

    @Test public void nativePrepareComparesActualInstallationWithoutInstalling() throws Exception {
        JSONObject previous = echo(); AtomicInteger previews = new AtomicInteger(), writes = new AtomicInteger();
        JSONObject row = new JSONObject().put("id", "custom.echo").put("installationId", "actual-old")
                .put("manifest", previous).put("enabled", true).put("grants", new JSONArray().put("tool:echo"));
        EngineModule host = new EngineModule() {
            public String id() { return "plugins"; }
            public JSONObject snapshot() throws Exception { return new JSONObject().put("plugins", new JSONArray().put(row)); }
            public JSONObject handle(String command, JSONObject request) throws Exception {
                if (!"previewInstall".equals(command)) { writes.incrementAndGet(); throw new AssertionError("Unexpected mutation"); }
                previews.incrementAndGet(); return PluginRevisions.preview(row, request.getJSONObject("manifest"));
            }
        };
        PluginSdk.Prepared prepared = new PluginSdk(host).prepare(input(echo().put("version", "next").toString()), source(), new RunControl());
        assertTrue(prepared.receipt().getBoolean("installedStateCompared"));
        assertEquals("actual-old", prepared.inspection().review().getJSONObject("preview").getString("expectedInstallationId"));
        assertTrue(prepared.inspection().review().getJSONObject("preview").getBoolean("isUpdate"));
        assertEquals(1, previews.get()); assertEquals(0, writes.get()); assertTrue(row.getBoolean("enabled"));
    }

    @Test public void realArgumentDiagnosticsHandleValidInvalidAndExtraFields() throws Exception {
        PluginSdk.Prepared prepared = prepare(echo());
        assertTrue(sdk.diagnose(prepared, "echo", args()).getBoolean("valid"));
        assertFalse(sdk.diagnose(prepared, "echo", new JSONObject().put("text", 42)).getBoolean("valid"));
        assertFalse(sdk.diagnose(prepared, "echo", args().put("extra", true)).getBoolean("valid"));
        fails(IllegalArgumentException.class, () -> sdk.reviewDebug(prepared, "echo", new JSONObject(), "op-1"));
    }

    @Test public void catalogEntryExactlyProjectsNormalizedToolsAndOpaqueVersion() throws Exception {
        JSONObject manifest = echo();
        manifest.getJSONArray("tools").put(PluginSdk.template("http", "http.example", "http", "1", "https://example.test/tools").getJSONArray("tools").getJSONObject(0));
        PluginSdk.Prepared prepared = prepare(manifest);
        JSONObject entry = prepared.catalogEntry("https://example.test/custom.pwp.json", Integer.MAX_VALUE);
        assertEquals("opaque dev 1", entry.getString("version")); assertEquals(2, entry.getJSONArray("capabilities").length());
        assertEquals("echo", entry.getJSONArray("capabilities").getJSONObject(0).getString("name"));
        assertEquals("https://example.test/tools", entry.getJSONArray("capabilities").getJSONObject(1).getString("endpoint"));
        assertEquals(prepared.bytes().length, entry.getInt("byteSize"));
        assertEquals(prepared.receipt().getString("declarationDigest"), entry.getString("declarationDigest"));
        fails(IllegalArgumentException.class, () -> prepared.catalogEntry("https://user:secret@example.test/p", 30));
        fails(IllegalArgumentException.class, () -> prepared.catalogEntry("https://example.test/p?token=secret", 30));
        fails(IllegalArgumentException.class, () -> prepared.catalogEntry("https://example.test/p", 0));
    }

    @Test public void echoDebugRequiresExactOwnerReviewAndIsSingleUse() throws Exception {
        PluginSdk.Prepared prepared = prepare(echo()); JSONObject arguments = args();
        PluginSdk.DebugReview review = sdk.reviewDebug(prepared, "echo", arguments, "exact-op");
        arguments.put("text", "changed after review"); review.review().getJSONObject("arguments").put("text", "also changed");
        fails(SecurityException.class, () -> sdk.debugReviewed(prepared, review, "wrong", new RunControl()));
        JSONObject result = sdk.debugReviewed(prepared, review, review.digest, new RunControl());
        assertEquals("local_echo", result.getString("status")); assertEquals(" actual local text ", result.getJSONObject("output").getString("text"));
        fails(IllegalStateException.class, () -> sdk.debugReviewed(prepared, review, review.digest, new RunControl()));
    }

    @Test public void packageOrInputsChangesInvalidateDebugDigest() throws Exception {
        PluginSdk.Prepared a = prepare(echo()), b = prepare(echo().put("version", "other"));
        PluginSdk.DebugReview one = sdk.reviewDebug(a, "echo", args(), "one");
        PluginSdk.DebugReview two = sdk.reviewDebug(a, "echo", args().put("text", "different"), "one");
        assertNotEquals(one.digest, two.digest);
        fails(SecurityException.class, () -> sdk.debugReviewed(b, one, one.digest, new RunControl()));
        fails(IllegalArgumentException.class, () -> sdk.reviewDebug(a, "echo", args(), "bad\noperation"));
    }

    @Test public void preCancelledDebugIsNotDispatchedAndReviewIsConsumed() throws Exception {
        PluginSdk.Prepared prepared = prepare(echo()); PluginSdk.DebugReview review = sdk.reviewDebug(prepared, "echo", args(), "cancelled-op");
        RunControl control = new RunControl(); control.cancel();
        assertEquals("not_dispatched", sdk.debugReviewed(prepared, review, review.digest, control).getString("status"));
        fails(IllegalStateException.class, () -> sdk.debugReviewed(prepared, review, review.digest, new RunControl()));
    }

    @Test public void httpDebugNeverUsesRemoteOrHostnameAliases() throws Exception {
        for (String endpoint : new String[]{"https://example.test/tools", "http://localhost:8765/tools"}) {
            PluginSdk.Prepared prepared = prepare(PluginSdk.template("http", "remote", "Remote", "1", endpoint));
            fails(SecurityException.class, () -> sdk.reviewDebug(prepared, "normalize_text", args(), "blocked"));
        }
    }

    @Test public void actualHttpClientSendsExactOperationAndReturnsUntrustedData() throws Exception {
        AtomicReference<JSONObject> received = new AtomicReference<>(); AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tools", exchange -> {
            try {
                calls.incrementAndGet(); received.set(new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] output = "{\"text\":\"real returned value\",\"token\":\"must scrub\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, output.length); exchange.getResponseBody().write(output);
            } catch (Exception failure) { throw new RuntimeException(failure); } finally { exchange.close(); }
        }); server.start();
        try {
            PluginSdk.Prepared prepared = prepare(PluginSdk.template("http", "local", "Local", "1", "http://127.0.0.1:" + server.getAddress().getPort() + "/tools"));
            PluginSdk.DebugReview review = sdk.reviewDebug(prepared, "normalize_text", args(), "stable-http-op");
            JSONObject result = sdk.debugReviewed(prepared, review, review.digest, new RunControl());
            assertEquals("returned", result.getString("status")); assertTrue(result.getBoolean("untrusted"));
            assertFalse(result.getBoolean("replayAllowed")); assertFalse(result.getJSONObject("output").has("token"));
            assertEquals("stable-http-op", received.get().getString("operationId"));
            assertEquals(args().getString("text"), received.get().getJSONObject("arguments").getString("text")); assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test public void cancellationAfterDispatchIsUnknownAndNoReplayOccurs() throws Exception {
        CountDownLatch dispatched = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tools", exchange -> { calls.incrementAndGet(); dispatched.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } finally { exchange.close(); } });
        server.start();
        try {
            PluginSdk.Prepared prepared = prepare(PluginSdk.template("http", "cancel", "Cancel", "1", "http://127.0.0.1:" + server.getAddress().getPort() + "/tools"));
            PluginSdk.DebugReview review = sdk.reviewDebug(prepared, "normalize_text", args(), "uncertain-op"); RunControl control = new RunControl();
            AtomicReference<JSONObject> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> { try { result.set(sdk.debugReviewed(prepared, review, review.digest, control)); } catch (Throwable error) { failure.set(error); } }); worker.start();
            assertTrue(dispatched.await(5, TimeUnit.SECONDS)); control.cancel(); release.countDown(); worker.join(6000);
            assertFalse(worker.isAlive()); assertNull(failure.get()); assertEquals("unknown", result.get().getString("status")); assertEquals(1, calls.get());
            fails(IllegalStateException.class, () -> sdk.debugReviewed(prepared, review, review.digest, new RunControl())); assertEquals(1, calls.get());
        } finally { release.countDown(); server.stop(0); }
    }

    @Test public void mcpDeclarationIsPortableButDebugDoesNotPretendDiscovery() throws Exception {
        JSONObject tool = new JSONObject().put("name", "actual_discovered_tool").put("kind", "mcp").put("endpoint", "https://example.test/mcp")
                .put("protocolVersion", "2026-07-28").put("descriptorDigest", "a".repeat(64)).put("serverDigest", "b".repeat(64))
                .put("schema", new JSONObject().put("type", "object").put("title", "Discovery fixture"));
        PluginSdk.Prepared prepared = prepare(new JSONObject().put("id", "mcp.fixture").put("name", "MCP fixture declaration").put("version", "1").put("tools", new JSONArray().put(tool)));
        assertTrue(prepared.inspection().review().getBoolean("requiresMcpRediscovery"));
        assertFalse(sdk.diagnose(prepared, "actual_discovered_tool", new JSONObject()).getBoolean("supported"));
        fails(IllegalArgumentException.class, () -> sdk.reviewDebug(prepared, "actual_discovered_tool", new JSONObject(), "mcp-no-run"));
        assertEquals("write", prepared.catalogEntry("https://example.test/pkg", 30).getJSONArray("capabilities").getJSONObject(0).getString("effect"));
    }

    @Test public void changedPackageBytesAreDetectedByRealReader() throws Exception {
        PluginSdk.Prepared prepared = prepare(echo());
        String raw = new String(prepared.bytes(), StandardCharsets.UTF_8);
        fails(SecurityException.class, () -> sdk.inspect(input(raw.replace("opaque dev 1", "opaque dev 2")), new RunControl()));
    }

    @Test public void phoneSaveExampleOnlyReturnsSavedAfterSuccessfulClose() throws Exception {
        EngineModule host = new EngineModule() {
            public String id() { return "plugins"; }
            public JSONObject snapshot() throws Exception { return new JSONObject().put("plugins", new JSONArray()); }
            public JSONObject handle(String command, JSONObject request) throws Exception {
                if (!"previewInstall".equals(command)) throw new SecurityException("Read only fixture");
                return PluginRevisions.preview(null, request.getJSONObject("manifest"));
            }
        };
        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        JSONObject receipt = PluginDocuments.packToOwnerDocument(host, input(echo().toString()), source(), saved, new RunControl());
        assertTrue(receipt.getBoolean("saved")); assertFalse(receipt.has("requiresDestinationClose"));
        assertEquals(receipt.getString("packageSha256"), sdk.inspect(new ByteArrayInputStream(saved.toByteArray()), new RunControl()).receipt().getString("packageSha256"));
        fails(java.io.IOException.class, () -> PluginDocuments.packToOwnerDocument(host, input(echo().toString()), source(), new ByteArrayOutputStream() {
            @Override public void close() throws java.io.IOException { throw new java.io.IOException("document provider close failed"); }
        }, new RunControl()));
    }
}
