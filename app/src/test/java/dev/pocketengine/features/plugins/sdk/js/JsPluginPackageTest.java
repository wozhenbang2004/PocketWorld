package dev.pocketengine.features.plugins.sdk.js;

import dev.pocketengine.core.RunControl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsPluginPackageTest {
    private static ByteArrayInputStream bytes(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    private static JsPluginPackage author(String source) throws Exception { return JsPluginPackage.author(bytes(source), "custom.js", "JS example", "opaque release A", new RunControl()); }
    private static JsPluginPackage read(String json) throws Exception { return JsPluginPackage.read(bytes(json), new RunControl()); }
    private static String raw(JsPluginPackage artifact) { return new String(artifact.packageBytes(), StandardCharsets.UTF_8); }
    private static void fails(Class<? extends Throwable> type, Checked action) throws Exception {
        try { action.run(); fail("Expected " + type.getSimpleName()); } catch (Throwable value) { if (!type.isInstance(value)) throw value; }
    }
    private interface Checked { void run() throws Exception; }
    private static String sha(byte[] bytes) throws Exception { StringBuilder out = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format("%02x", b & 255)); return out.toString(); }

    @Test public void authorAndReadKeepExactSourceAndBothIndependentHashes() throws Exception {
        String source = "\ufeff// literal BOM, CRLF and Unicode stay exact\r\ndefineExtension({tools: []}); // 🚀\r\n";
        JsPluginPackage artifact = author(source), again = JsPluginPackage.read(new ByteArrayInputStream(artifact.packageBytes()), new RunControl());
        assertEquals(source, again.sourceDisplay()); assertArrayEquals(source.getBytes(StandardCharsets.UTF_8), again.exactSourceBytes());
        assertEquals(sha(source.getBytes(StandardCharsets.UTF_8)), artifact.sourceSha256);
        assertEquals(sha(artifact.packageBytes()), artifact.packageSha256);
        assertNotEquals(artifact.sourceSha256, artifact.packageSha256); assertEquals("opaque release A", artifact.version);
    }

    @Test public void decoderNeverExecutesInitializesDescribesOrApprovesSource() throws Exception {
        String source = "throw new Error('THIS MUST NEVER RUN DURING SDK VALIDATION');\nwhile (true) {}";
        JsPluginPackage artifact = author(source); JSONObject receipt = artifact.receipt();
        assertTrue(receipt.getBoolean("staticOnly")); assertFalse(receipt.getBoolean("initialized")); assertFalse(receipt.getBoolean("described"));
        assertFalse(receipt.getBoolean("approved")); assertFalse(receipt.getBoolean("enabled")); assertFalse(receipt.getBoolean("installed"));
        assertEquals(source, JsPluginPackage.read(new ByteArrayInputStream(artifact.packageBytes()), new RunControl()).sourceDisplay());
        // Syntax/defineExtension/tool availability are deliberately not asserted by a static container parser.
        assertEquals("not valid JavaScript {", author("not valid JavaScript {").sourceDisplay());
    }

    @Test public void byteArraysAreDefensiveAndDigestStable() throws Exception {
        JsPluginPackage artifact = author("defineExtension({tools:[]});");
        byte[] source = artifact.exactSourceBytes(), pkg = artifact.packageBytes(); source[0] = 0; pkg[0] = 0;
        assertEquals('d', artifact.exactSourceBytes()[0]); assertEquals('{', artifact.packageBytes()[0]);
        assertEquals(sha(artifact.packageBytes()), artifact.packageSha256);
    }

    @Test public void rawPackageWhitespaceChangesPackageHashOnly() throws Exception {
        JsPluginPackage a = author("// source\r\n"), b = read(" \n" + raw(a) + "\r\n");
        assertNotEquals(a.packageSha256, b.packageSha256); assertEquals(a.sourceSha256, b.sourceSha256);
        assertArrayEquals(a.exactSourceBytes(), b.exactSourceBytes());
    }

    @Test public void alteredSourceAndWrongDigestAreRejected() throws Exception {
        JsPluginPackage artifact = author("// exact original");
        fails(SecurityException.class, () -> read(raw(artifact).replace("exact original", "altered source")));
        JSONObject changed = new JSONObject(raw(artifact)).put("sourceSha256", "A".repeat(64));
        fails(SecurityException.class, () -> read(changed.toString()));
    }

    @Test public void unknownRuntimeInstallGrantStateFieldsAreRejected() throws Exception {
        JsPluginPackage artifact = author("// inert");
        for (String field : new String[]{"enabled", "approved", "installationId", "approvalRevision", "state", "grants", "token", "hookTimeoutMs", "tools"}) {
            JSONObject altered = new JSONObject(raw(artifact)).put(field, "not-allowed");
            fails(IllegalArgumentException.class, () -> read(altered.toString()));
        }
    }

    @Test public void duplicateUnknownMissingAndNonStringFieldsFailStrictly() throws Exception {
        String original = raw(author("// inert"));
        fails(IllegalArgumentException.class, () -> read(original.replaceFirst("\\{", "{\"id\":\"duplicate\",")));
        fails(IllegalArgumentException.class, () -> read("{\"format\":\"pocketworld.executable-js.v1\"}"));
        fails(IllegalArgumentException.class, () -> read(original.replace("\"version\":\"opaque release A\"", "\"version\":123")));
        fails(IllegalArgumentException.class, () -> read(original.substring(0, original.length() - 1) + ",}"));
        fails(IllegalArgumentException.class, () -> read(original + "{}"));
        fails(IllegalArgumentException.class, () -> read(original.replace("pocketworld.executable-js.v1", "pocketworld.plugin-declaration.v1")));
    }

    @Test public void malformedUtf8LoneSurrogateAndPermissiveJsonAreRejected() throws Exception {
        fails(java.nio.charset.CharacterCodingException.class, () -> JsPluginPackage.author(new ByteArrayInputStream(new byte[]{(byte)0xc0, (byte)0xaf}), "id", "Name", "1", new RunControl()));
        fails(java.nio.charset.CharacterCodingException.class, () -> JsPluginPackage.read(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28}), new RunControl()));
        fails(IllegalArgumentException.class, () -> read(raw(author("x")).replace("\"source\":\"x\"", "\"source\":\"\\ud800\"")));
        fails(IllegalArgumentException.class, () -> read("{id:'not strict json'}"));
        fails(IllegalArgumentException.class, () -> read("\ufeff" + raw(author("x"))));
    }

    @Test public void sourceLimitIsBytesAndWorstCaseEscapingFitsOneMiB() throws Exception {
        byte[] maximum = "🚀".repeat(32768).getBytes(StandardCharsets.UTF_8);
        assertEquals(131072, maximum.length);
        assertEquals(maximum.length, JsPluginPackage.author(new ByteArrayInputStream(maximum), "max", "Max", "1", new RunControl()).exactSourceBytes().length);
        JsPluginPackage escaped = author("\u0000".repeat(131072));
        assertTrue(escaped.byteSize > 262144); assertTrue(escaped.byteSize < JsPluginPackage.MAX_PACKAGE_BYTES);
        fails(IllegalArgumentException.class, () -> author("x".repeat(131073)));
        fails(IllegalArgumentException.class, () -> author(""));
        fails(IllegalArgumentException.class, () -> JsPluginPackage.read(new ByteArrayInputStream(new byte[1048577]), new RunControl()));
    }

    @Test public void metadataIsBoundedAndNotSilentlyRewritten() throws Exception {
        for (String id : new String[]{"../id", " id", "1id", "a".repeat(81)}) fails(IllegalArgumentException.class,
                () -> JsPluginPackage.author(bytes("x"), id, "Name", "1", new RunControl()));
        fails(IllegalArgumentException.class, () -> JsPluginPackage.author(bytes("x"), "id", "Name", "v".repeat(33), new RunControl()));
        fails(IllegalArgumentException.class, () -> JsPluginPackage.author(bytes("x"), "id", "Name\nInjected", "1", new RunControl()));
        assertEquals("release+custom", JsPluginPackage.author(bytes("x"), "id", "Name", "release+custom", new RunControl()).version);
    }

    @Test public void cancellationBeforeReadAndWriteDoesNoOutput() throws Exception {
        RunControl control = new RunControl(); control.cancel(); AtomicInteger reads = new AtomicInteger();
        InputStream input = new InputStream() { public int read() { reads.incrementAndGet(); return -1; } };
        fails(InterruptedException.class, () -> JsPluginPackage.read(input, control)); assertEquals(0, reads.get());
        ByteArrayOutputStream output = new ByteArrayOutputStream(); JsPluginPackage artifact = author("x");
        fails(InterruptedException.class, () -> artifact.write(output, control)); assertEquals(0, output.size());
    }

    @Test public void cancellationDuringReadStopsBeforeArtifactExists() throws Exception {
        RunControl control = new RunControl(); byte[] content = raw(author("x")).getBytes(StandardCharsets.UTF_8);
        InputStream input = new ByteArrayInputStream(content) { @Override public synchronized int read(byte[] target, int off, int len) {
            int count = super.read(target, off, Math.min(16, len)); control.cancel(); return count;
        }};
        fails(InterruptedException.class, () -> JsPluginPackage.read(input, control));
    }

    @Test public void streamSaveRequiresDestinationCloseAndPhoneCloseFailureIsNotSuccess() throws Exception {
        JsPluginPackage artifact = author("// exact"); ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(artifact.write(output, new RunControl()).getBoolean("requiresDestinationClose")); assertArrayEquals(artifact.packageBytes(), output.toByteArray());
        ByteArrayOutputStream phoneOutput = new ByteArrayOutputStream();
        JSONObject saved = JsPluginDocuments.packToOwnerDocument(bytes("// exact"), "phone", "Phone", "1", phoneOutput, new RunControl());
        assertTrue(saved.getBoolean("saved")); assertFalse(saved.has("requiresDestinationClose"));
        fails(java.io.IOException.class, () -> JsPluginDocuments.packToOwnerDocument(bytes("// exact"), "phone", "Phone", "1", new ByteArrayOutputStream() {
            @Override public void close() throws java.io.IOException { throw new java.io.IOException("provider failed"); }
        }, new RunControl()));
    }

    @Test public void zeroProgressInputFailsInsteadOfSpinning() throws Exception {
        InputStream input = new InputStream() {
            public int read() { return 0; }
            public int read(byte[] out, int offset, int length) { return 0; }
        };
        fails(IllegalArgumentException.class, () -> JsPluginPackage.read(input, new RunControl()));
    }
}
