package dev.pocketengine.features.updates;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** A release is trusted only after verifying the exact payload bytes with the build-pinned key. */
public final class UpdateManifest {
    public static final long MAX_APK_BYTES = 512L * 1024 * 1024;
    public static final int MAX_ENVELOPE_BYTES = 96 * 1024;
    public final String packageName, versionName, notes;
    public final long versionCode, expiresAt;
    public final int minSdk;
    public final Artifact apk;
    public final List<Delta> deltas;

    public static final class Artifact {
        public final String url, sha256;
        public final long bytes;
        Artifact(JSONObject object, long maximum) throws Exception {
            url = https(object.getString("url"));
            sha256 = digest(object.getString("sha256"));
            bytes = integer(object, "bytes", 1, maximum);
        }
    }

    public static final class Delta {
        public final Artifact artifact;
        public final long baseVersionCode;
        public final String baseSha256;
        Delta(JSONObject object) throws Exception {
            if (!"bsdiff43-gzip-v1".equals(object.getString("format"))) throw new IllegalArgumentException("不支持的差分格式");
            baseVersionCode = integer(object, "baseVersionCode", 1, 2_100_000_000L);
            baseSha256 = digest(object.getString("baseSha256"));
            artifact = new Artifact(object, 256L * 1024 * 1024);
        }
    }

    private UpdateManifest(JSONObject object, String expectedPackage, long now) throws Exception {
        if (integer(object, "schema", 1, 1) != 1) throw new IllegalArgumentException("更新清单格式不支持");
        packageName = object.getString("packageName");
        if (!expectedPackage.equals(packageName)) throw new SecurityException("更新清单的应用不匹配");
        versionCode = integer(object, "versionCode", 1, 2_100_000_000L);
        versionName = bounded(object.getString("versionName"), 80);
        notes = bounded(object.optString("notes", ""), 4000);
        minSdk = (int) integer(object, "minSdk", 30, 100);
        long issuedAt = integer(object, "issuedAt", 1, Long.MAX_VALUE);
        expiresAt = integer(object, "expiresAt", 1, Long.MAX_VALUE);
        if (issuedAt > now + 300_000L || expiresAt <= now || expiresAt <= issuedAt
                || expiresAt - issuedAt > 7L * 24 * 60 * 60 * 1000) throw new SecurityException("更新清单已过期或设备时间不正确");
        apk = new Artifact(object.getJSONObject("apk"), MAX_APK_BYTES);
        JSONArray list = object.optJSONArray("deltas");
        ArrayList<Delta> parsed = new ArrayList<>();
        if (list != null) {
            if (list.length() > 12) throw new IllegalArgumentException("更新清单过大");
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                // Newer servers can advertise another format; this client safely uses the full APK.
                if ("bsdiff43-gzip-v1".equals(item.optString("format"))) parsed.add(new Delta(item));
            }
        }
        deltas = java.util.Collections.unmodifiableList(parsed);
    }

    public static UpdateManifest verify(byte[] envelope, String publicKeyBase64, String packageName, long now) throws Exception {
        if (envelope.length == 0 || envelope.length > MAX_ENVELOPE_BYTES) throw new SecurityException("更新清单大小无效");
        JSONObject wrapper = new JSONObject(new String(envelope, StandardCharsets.UTF_8));
        byte[] payload = Base64.getDecoder().decode(wrapper.getString("payload"));
        byte[] signature = Base64.getDecoder().decode(wrapper.getString("signature"));
        byte[] encodedKey = Base64.getDecoder().decode(publicKeyBase64);
        if (payload.length > 64 * 1024 || signature.length > 1024 || encodedKey.length > 4096) throw new SecurityException("更新签名大小无效");
        var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encodedKey));
        if (!(key instanceof java.security.interfaces.RSAPublicKey) || ((java.security.interfaces.RSAPublicKey) key).getModulus().bitLength() < 2048)
            throw new SecurityException("更新签名密钥强度不足");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key); verifier.update(payload);
        if (!verifier.verify(signature)) throw new SecurityException("更新清单签名不正确，已停止");
        return new UpdateManifest(new JSONObject(new String(payload, StandardCharsets.UTF_8)), packageName, now);
    }

    public Delta chooseDelta(long installedCode, String installedSha256) {
        for (Delta delta : deltas) if (delta.baseVersionCode == installedCode && delta.baseSha256.equals(installedSha256)
                && delta.artifact.bytes < apk.bytes) return delta;
        return null;
    }

    static String https(String value) throws Exception {
        URI uri = new URI(value);
        if (value.length() > 4096 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) throw new SecurityException("更新地址必须是可信 HTTPS 地址");
        return value;
    }
    static String digest(String value) {
        if (!value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("更新摘要格式无效");
        return value;
    }
    static String bounded(String value, int maximum) {
        if (value.length() > maximum || value.indexOf('\0') >= 0) throw new IllegalArgumentException("更新说明过长");
        return value;
    }
    private static long integer(JSONObject object, String key, long min, long max) throws Exception {
        Object raw = object.get(key);
        if (!(raw instanceof Integer || raw instanceof Long)) throw new IllegalArgumentException("更新清单数字无效");
        long value = ((Number) raw).longValue();
        if (value < min || value > max) throw new IllegalArgumentException("更新清单数字超出范围");
        return value;
    }
}
