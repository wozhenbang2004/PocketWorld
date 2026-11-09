package dev.pocketengine.features.remoteinbox;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private, bounded event journal. Remote content is always untrusted data, never an execution command. */
public final class RemoteInboxStore {
    private static final int MAX_EVENTS = 300;
    private static final String KEY_ALIAS = "pocketworld.remote.inbox.token.v1";
    private static RemoteInboxStore instance;
    private final AtomicFile journal;
    private final AtomicFile settings;
    private long openRevision = 0;

    private RemoteInboxStore(Context context) {
        File directory = new File(context.getNoBackupFilesDir(), "remote-inbox");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("通知目录不可用");
        journal = new AtomicFile(new File(directory, "events.json"));
        settings = new AtomicFile(new File(directory, "config.json"));
    }
    public static synchronized RemoteInboxStore get(Context context) {
        if (instance == null) instance = new RemoteInboxStore(context.getApplicationContext());
        return instance;
    }

    private static JSONObject load(AtomicFile file) throws Exception {
        if (!file.getBaseFile().exists()) return new JSONObject();
        try (FileInputStream input = file.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (bytes.size() + read > 512 * 1024) throw new IllegalStateException("通知文件异常过大");
                bytes.write(buffer, 0, read);
            }
            return new JSONObject(bytes.toString(StandardCharsets.UTF_8));
        }
    }
    private static void write(AtomicFile file, JSONObject value) throws Exception {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 512 * 1024) throw new IllegalStateException("通知文件超出配额");
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(bytes);
            stream.flush();
            file.finishWrite(stream);
        } catch (Exception failure) {
            file.failWrite(stream);
            throw failure;
        }
    }
    static String endpoint(String raw) {
        if (raw == null || raw.length() > 512) throw new IllegalArgumentException("推送地址过长");
        try {
            URI uri = new URI(raw.trim()).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() > 0 && uri.getPort() != 443
                || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())))
                throw new IllegalArgumentException("仅接受 HTTPS 通知服务根地址");
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".localhost") || host.matches("(?i)(127|0)\\..*")
                || host.matches("(?i)10\\..*") || host.matches("(?i)192\\.168\\..*") || host.matches("(?i)172\\.(1[6-9]|2[0-9]|3[01])\\..*"))
                throw new IllegalArgumentException("通知服务不能指向本地内网");
            return "https://" + (host.indexOf(':') >= 0 ? "[" + host + "]" : host);
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("无效的通知服务地址");
        }
    }
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key present = store.getKey(KEY_ALIAS, null);
        if (present instanceof SecretKey) return (SecretKey) present;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
    private static String encrypt(String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new SecureRandom());
        byte[] nonce = cipher.getIV(), encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, packed, 0, nonce.length);
        System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
        return Base64.encodeToString(packed, Base64.NO_WRAP);
    }
    private static String decrypt(String secret) throws Exception {
        byte[] packed = Base64.decode(secret, Base64.DEFAULT);
        if (packed.length < 29) throw new SecurityException("通知凭据已损坏");
        byte[] nonce = java.util.Arrays.copyOfRange(packed, 0, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
        return new String(cipher.doFinal(packed, 12, packed.length - 12), StandardCharsets.UTF_8);
    }
    public synchronized void configure(String rawUrl, String token) throws Exception {
        String url = endpoint(rawUrl);
        if (token == null || token.length() < 32 || token.length() > 2048 || token.chars().anyMatch(ch -> ch <= 32 || ch > 126))
            throw new IllegalArgumentException("令牌至少 32 位，必须是无空格的 ASCII 字符");
        JSONObject existing = load(settings);
        boolean same = url.equals(existing.optString("endpoint")) && token.equals(existing.has("token") ? decrypt(existing.getString("token")) : "");
        JSONObject next = new JSONObject().put("endpoint", url).put("token", encrypt(token)).put("live", same && existing.optBoolean("live"));
        if (!same) write(journal, new JSONObject().put("cursor", 0).put("events", new JSONArray()));
        write(settings, next);
    }
    public synchronized void disconnect() throws Exception {
        write(settings, new JSONObject());
        write(journal, new JSONObject().put("cursor", 0).put("events", new JSONArray()));
    }
    public synchronized boolean configured() throws Exception {
        JSONObject cfg = load(settings);
        return cfg.has("endpoint") && cfg.has("token");
    }
    public synchronized String url() throws Exception { return load(settings).getString("endpoint"); }
    public synchronized String bearer() throws Exception { return decrypt(load(settings).getString("token")); }
    public synchronized boolean live() throws Exception { return load(settings).optBoolean("live"); }
    public synchronized void live(boolean enabled) throws Exception {
        JSONObject config = load(settings);
        if (!config.has("token")) throw new IllegalStateException("尚未配置远程通知");
        config.put("live", enabled);
        write(settings, config);
    }
    public synchronized long cursor() throws Exception { return load(journal).optLong("cursor", 0); }
    public synchronized void signalOpen() { openRevision++; }
    public synchronized JSONObject snapshot() throws Exception {
        JSONObject state = load(journal);
        JSONObject config = load(settings);
        JSONArray all = state.optJSONArray("events");
        int unread = 0;
        if (all != null) for (int i = 0; i < all.length(); i++) if (!all.getJSONObject(i).optBoolean("read")) unread++;
        return new JSONObject().put("configured", config.has("token")).put("endpoint", config.optString("endpoint"))
            .put("live", config.optBoolean("live")).put("openRevision", openRevision)
            .put("cursor", state.optLong("cursor", 0)).put("unread", unread)
            .put("events", all == null ? new JSONArray() : all);
    }
    /** Batch is validated in its entirety before one atomic journal write and cursor advance. */
    public synchronized List<JSONObject> ingest(JSONArray events) throws Exception {
        JSONObject state = load(journal);
        JSONArray current = state.optJSONArray("events");
        if (current == null) current = new JSONArray();
        if (events.length() > 100) throw new SecurityException("一次同步结果超过限制");
        long cursor = state.optLong("cursor", 0);
        ArrayList<JSONObject> admitted = new ArrayList<>();
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        for (int i = 0; i < current.length(); i++) keys.add(current.getJSONObject(i).optString("source_key"));
        long highest = cursor;
        for (int i = 0; i < events.length(); i++) {
            JSONObject remote = events.getJSONObject(i);
            long seq = remote.getLong("seq");
            if (seq <= 0 || seq <= highest && seq > cursor) throw new SecurityException("远程通知序号不递增");
            if (seq <= cursor) continue;
            String sourceKey = bounded(remote.getString("source_key"), 180);
            String kind = bounded(remote.getString("kind"), 40);
            if (!kind.matches("github_issue|github_pr|agent_task|service") || !sourceKey.matches("[A-Za-z0-9:._/#-]+"))
                throw new SecurityException("远程通知类型无效");
            String title = bounded(remote.getString("title"), 180), body = bounded(remote.optString("body"), 600);
            String origin = bounded(remote.getString("origin"), 120), link = remote.getString("url");
            try {
                URI uri = new URI(link);
                if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getHost() == null || kind.startsWith("github_") && !"github.com".equalsIgnoreCase(uri.getHost()))
                    throw new SecurityException("通知链接不可信");
            } catch (java.net.URISyntaxException error) { throw new SecurityException("通知链接不可信"); }
            if (title.isEmpty() || origin.isEmpty()) throw new SecurityException("通知内容无效");
            JSONObject notice = new JSONObject().put("seq", seq).put("source_key", sourceKey).put("kind", kind)
                .put("title", title).put("body", body).put("origin", origin).put("url", link)
                .put("priority", "attention".equals(remote.optString("priority")) ? "attention" : "normal")
                .put("created_at", bounded(remote.optString("created_at"), 40)).put("read", false);
            if (keys.add(sourceKey)) admitted.add(notice);
            highest = seq;
        }
        JSONArray next = new JSONArray();
        int keepFrom = Math.max(0, current.length() + admitted.size() - MAX_EVENTS);
        for (int i = Math.max(0, keepFrom); i < current.length(); i++) next.put(current.getJSONObject(i));
        int skipNew = Math.max(0, keepFrom - current.length());
        for (int i = skipNew; i < admitted.size(); i++) next.put(admitted.get(i));
        write(journal, new JSONObject().put("cursor", highest).put("events", next));
        return admitted;
    }
    public synchronized JSONObject find(long seq) throws Exception {
        JSONArray entries = load(journal).optJSONArray("events");
        if (entries == null) throw new IllegalArgumentException("通知已不在保留范围");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject notice = entries.getJSONObject(i);
            if (notice.optLong("seq") == seq) return new JSONObject(notice.toString());
        }
        throw new IllegalArgumentException("通知已不在保留范围");
    }
    public synchronized void read(long seq) throws Exception {
        JSONObject state = load(journal);
        JSONArray entries = state.optJSONArray("events");
        if (entries == null) throw new IllegalArgumentException("没有可读取的通知");
        boolean found = false;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject notice = entries.getJSONObject(i);
            if (notice.optLong("seq") == seq) { notice.put("read", true); found = true; }
        }
        if (!found) throw new IllegalArgumentException("通知已不在保留范围");
        write(journal, state);
    }
    private static String bounded(String raw, int max) {
        if (raw == null || raw.length() > max || raw.indexOf('\u0000') >= 0) throw new SecurityException("通知文本长度无效");
        return raw;
    }
}
