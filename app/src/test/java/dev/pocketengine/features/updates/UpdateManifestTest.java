package dev.pocketengine.features.updates;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateManifestTest {
    private static KeyPair key;
    private static final long NOW = 1_800_000_000_000L;
    private static final String HASH = "a".repeat(64);
    @BeforeClass public static void keys() throws Exception { KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key = generator.generateKeyPair(); }
    private JSONObject payload() throws Exception {
        return new JSONObject().put("schema", 1).put("packageName", "dev.pocketengine").put("versionCode", 4).put("versionName", "0.3.1")
                .put("minSdk", 30).put("issuedAt", NOW - 1000).put("expiresAt", NOW + 86400000)
                .put("apk", artifact("https://private.example/app.apk", 1000));
    }
    private JSONObject artifact(String url, long bytes) throws Exception { return new JSONObject().put("url", url).put("sha256", HASH).put("bytes", bytes); }
    private byte[] sign(JSONObject payload) throws Exception {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8); Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.getPrivate()); signature.update(bytes);
        return new JSONObject().put("payload", Base64.getEncoder().encodeToString(bytes)).put("signature", Base64.getEncoder().encodeToString(signature.sign())).toString().getBytes(StandardCharsets.UTF_8);
    }
    private UpdateManifest verify(byte[] envelope) throws Exception { return UpdateManifest.verify(envelope, Base64.getEncoder().encodeToString(key.getPublic().getEncoded()), "dev.pocketengine", NOW); }
    private void rejects(JSONObject payload) throws Exception { assertThrows(Exception.class, () -> verify(sign(payload))); }
    @Test public void signedReleasePasses() throws Exception { UpdateManifest release = verify(sign(payload())); assertEquals(4, release.versionCode); assertEquals(HASH, release.apk.sha256); }
    @Test public void tamperingFails() throws Exception {
        JSONObject envelope = new JSONObject(new String(sign(payload()), StandardCharsets.UTF_8));
        envelope.put("payload", Base64.getEncoder().encodeToString(payload().put("versionCode", 999).toString().getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class, () -> verify(envelope.toString().getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void wrongKeyFails() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); KeyPair other = generator.generateKeyPair();
        assertThrows(Exception.class, () -> UpdateManifest.verify(sign(payload()), Base64.getEncoder().encodeToString(other.getPublic().getEncoded()), "dev.pocketengine", NOW));
    }
    @Test public void wrongPackageFails() throws Exception { rejects(payload().put("packageName", "other.application")); }
    @Test public void expiredAndFutureAndOverlongValidityFail() throws Exception {
        rejects(payload().put("expiresAt", NOW - 1)); rejects(payload().put("issuedAt", NOW + 400000)); rejects(payload().put("expiresAt", NOW + 8L * 86400000));
    }
    @Test public void nonCanonicalNumbersAndHugeApkFail() throws Exception {
        rejects(payload().put("versionCode", "4")); rejects(payload().put("versionCode", 4.5)); rejects(payload().put("versionCode", -1));
        rejects(payload().put("apk", artifact("https://private.example/app.apk", UpdateManifest.MAX_APK_BYTES + 1)));
    }
    @Test public void cleartextAndEmbeddedCredentialsFail() throws Exception {
        for (String url : new String[]{"http://private.example/a", "https://user:secret@private.example/a", "file:///tmp/a", "https://private.example/a#fragment"}) rejects(payload().put("apk", artifact(url, 1000)));
    }
    @Test public void deltaRequiresExactBaseAndSavings() throws Exception {
        JSONObject delta = artifact("https://private.example/app.patch.gz", 100).put("format", "bsdiff43-gzip-v1").put("baseVersionCode", 3).put("baseSha256", HASH);
        UpdateManifest release = verify(sign(payload().put("deltas", new JSONArray().put(delta))));
        assertNotNull(release.chooseDelta(3, HASH)); assertNull(release.chooseDelta(2, HASH)); assertNull(release.chooseDelta(3, "b".repeat(64)));
        delta.put("bytes", 1000); assertNull(verify(sign(payload().put("deltas", new JSONArray().put(delta)))).chooseDelta(3, HASH));
    }
    @Test public void unsupportedDeltaUsesFullApk() throws Exception {
        UpdateManifest release = verify(sign(payload().put("deltas", new JSONArray().put(new JSONObject().put("format", "future-format")))));
        assertEquals(0, release.deltas.size()); assertEquals(1000, release.apk.bytes);
    }
    @Test public void oversizedEnvelopeFailsBeforeParsing() { assertThrows(Exception.class, () -> verify(new byte[UpdateManifest.MAX_ENVELOPE_BYTES + 1])); }
}
