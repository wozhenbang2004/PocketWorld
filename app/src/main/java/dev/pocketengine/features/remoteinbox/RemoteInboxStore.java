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
    // Leave room below the 512 KiB file cap for read/archive/suppression and sync metadata.
    private static final int EVENT_WINDOW_BYTES = 448 * 1024;
    private static final String KEY_ALIAS = "pocketworld.remote.inbox.token.v1";
    private static RemoteInboxStore instance;
    private final AtomicFile journal;
    private final AtomicFile settings;
    private long openRevision = 0;
    private long selectedSequence = 0;
    private String selectionError = "";
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> observers = new java.util.concurrent.CopyOnWriteArrayList<>();
    public void observe(Runnable observer) { observers.addIfAbsent(observer); }
    public void unobserve(Runnable observer) { observers.remove(observer); }
    private void announce() { for (Runnable observer : observers) observer.run(); }

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
    public synchronized boolean matchesConfiguration(String rawUrl, String token) throws Exception {
        String url = endpoint(rawUrl);
        if (token == null || token.length() < 32 || token.length() > 2048 || token.chars().anyMatch(ch -> ch <= 32 || ch > 126))
            throw new IllegalArgumentException("令牌至少 32 位，必须是无空格的 ASCII 字符");
        JSONObject existing = load(settings);
        return url.equals(existing.optString("endpoint")) && token.equals(existing.has("token") ? decrypt(existing.getString("token")) : "");
    }
    public synchronized void configure(String rawUrl, String token) throws Exception {
        boolean same = matchesConfiguration(rawUrl, token);
        String url = endpoint(rawUrl);
        JSONObject existing = load(settings);
        String configId = same && !existing.optString("configId").isEmpty()
            ? existing.getString("configId") : java.util.UUID.randomUUID().toString();
        JSONObject next = new JSONObject().put("endpoint", url).put("token", encrypt(token))
            .put("configId", configId).put("live", same && existing.optBoolean("live"));
        if (same && existing.has("push")) next.put("push", existing.getString("push"));
        // First commit the account identity. If a crash intervenes, the journal's
        // mismatched identity causes a safe cursor reset, not cross-account replay.
        write(settings, next);
        if (!same) {
            write(journal, new JSONObject().put("configId", configId).put("cursor", 0).put("events", new JSONArray()));
            selectedSequence = 0; selectionError = "";
        }
        announce();
    }
    public synchronized void disconnect() throws Exception {
        write(settings, new JSONObject());
        selectedSequence = 0; selectionError = "";
        write(journal, new JSONObject().put("cursor", 0).put("events", new JSONArray()));
        announce();
    }
    public synchronized boolean configured() throws Exception {
        JSONObject cfg = load(settings);
        return cfg.has("endpoint") && cfg.has("token");
    }
    /** Capture identity, endpoint and credential together; never mix two connections during a pull. */
    synchronized JSONObject connection() throws Exception {
        JSONObject config = load(settings);
        return new JSONObject().put("configId", config.getString("configId"))
            .put("endpoint", config.getString("endpoint")).put("bearer", decrypt(config.getString("token")));
    }
    public synchronized String url() throws Exception { return load(settings).getString("endpoint"); }
    public synchronized String bearer() throws Exception { return decrypt(load(settings).getString("token")); }
    public synchronized boolean live() throws Exception { return load(settings).optBoolean("live"); }
    public synchronized void live(boolean enabled) throws Exception {
        JSONObject config = load(settings);
        if (!config.has("token")) throw new IllegalStateException("尚未配置远程通知");
        config.put("live", enabled);
        write(settings, config);
        announce();
    }
    /** SDK endpoint, auth secret and registration token never enter the public snapshot. */
    synchronized JSONObject pushConfiguration() throws Exception {
        JSONObject config = load(settings);
        return config.has("push") ? new JSONObject(decrypt(config.getString("push"))) : new JSONObject();
    }
    synchronized boolean savePushConfiguration(String identity, JSONObject push) throws Exception {
        JSONObject config = load(settings);
        if (!identity.equals(config.optString("configId")) || identity.isEmpty()) return false;
        if (push.length() == 0) config.remove("push");
        else config.put("push", encrypt(push.toString()));
        write(settings, config);
        announce();
        return true;
    }
    public synchronized String configId() throws Exception { return load(settings).optString("configId"); }
    private JSONObject activeJournal() throws Exception {
        JSONObject saved = load(journal);
        String configuredIdentity = configId();
        if (configuredIdentity.isEmpty() || !configuredIdentity.equals(saved.optString("configId")))
            return new JSONObject().put("configId", configuredIdentity).put("cursor", 0).put("events", new JSONArray());
        return saved;
    }
    public synchronized long cursor() throws Exception { return activeJournal().optLong("cursor", 0); }
    public synchronized void signalOpen() { selectedSequence = 0; selectionError = ""; openRevision++; announce(); }
    /** A system notification selects its original saved item, never a URL or executable action. */
    public synchronized void signalOpen(String identity, long seq, String sourceKey) {
        selectedSequence = 0; selectionError = "";
        try { requireEvent(identity, seq, sourceKey); selectedSequence = seq; }
        catch (Exception unavailable) { selectionError = "这条通知已过期、已移出保留范围，或来自之前的连接。已打开当前通知中心，没有打开外部链接。"; }
        openRevision++; announce();
    }
    public synchronized JSONObject snapshot() throws Exception {
        JSONObject state = activeJournal();
        JSONObject config = load(settings);
        JSONArray all = state.optJSONArray("events");
        int unread = 0, archived = 0;
        if (all != null) for (int i = 0; i < all.length(); i++) {
            JSONObject event = all.getJSONObject(i);
            if (event.optBoolean("archived")) archived++;
            else if (!event.optBoolean("read")) unread++;
        }
        return new JSONObject().put("configured", config.has("token")).put("endpoint", config.optString("endpoint"))
            .put("live", config.optBoolean("live")).put("openRevision", openRevision)
            .put("configId", config.optString("configId")).put("selectedSeq", selectedSequence)
            .put("selectionError", selectionError).put("archived", archived)
            .put("lastSync", state.optJSONObject("lastSync") == null ? JSONObject.NULL : state.getJSONObject("lastSync"))
            .put("cursor", state.optLong("cursor", 0)).put("unread", unread)
            .put("events", all == null ? new JSONArray() : all);
    }
    /** Batch is validated in its entirety before one atomic journal write and cursor advance. */
    public synchronized List<JSONObject> ingest(JSONArray events, String expectedConfigId) throws Exception {
        if (expectedConfigId == null || expectedConfigId.isEmpty() || !expectedConfigId.equals(configId()))
            throw new SecurityException("远程通知连接已改变，本次旧请求已丢弃");
        JSONObject state = activeJournal();
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
            Object rawSeq = remote.opt("seq");
            if (!(rawSeq instanceof Integer || rawSeq instanceof Long)) throw new SecurityException("远程通知序号无效");
            long seq = ((Number) rawSeq).longValue();
            if (seq > 9007199254740991L || seq <= 0 || seq <= highest && seq > cursor) throw new SecurityException("远程通知序号不递增");
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
                .put("created_at", bounded(remote.optString("created_at"), 40)).put("read", false).put("alerted", false);
            if (keys.add(sourceKey)) admitted.add(notice);
            highest = seq;
        }
        JSONArray next = new JSONArray();
        int keepFrom = Math.max(0, current.length() + admitted.size() - MAX_EVENTS);
        for (int i = Math.max(0, keepFrom); i < current.length(); i++) next.put(current.getJSONObject(i));
        int skipNew = Math.max(0, keepFrom - current.length());
        for (int i = skipNew; i < admitted.size(); i++) next.put(admitted.get(i));
        JSONObject updated = state.put("configId", expectedConfigId).put("cursor", highest).put("events", new JSONArray());
        int bytes = updated.toString().getBytes(StandardCharsets.UTF_8).length;
        int[] sizes = new int[next.length()];
        for (int i = 0; i < next.length(); i++) {
            sizes[i] = next.getJSONObject(i).toString().getBytes(StandardCharsets.UTF_8).length;
            if (sizes[i] > EVENT_WINDOW_BYTES) throw new SecurityException("单条通知超出本机保留预算");
            bytes += sizes[i] + (i == 0 ? 0 : 1); // JSON commas; the empty [] is already counted.
        }
        int first = 0;
        while (bytes > EVENT_WINDOW_BYTES && first < next.length()) {
            bytes -= sizes[first++];
            if (first < next.length()) bytes--;
        }
        JSONArray retained = new JSONArray();
        for (int i = first; i < next.length(); i++) retained.put(next.getJSONObject(i));
        write(journal, updated.put("events", retained));
        if (!admitted.isEmpty()) announce();
        return admitted;
    }
    public synchronized JSONObject find(long seq) throws Exception {
        JSONArray entries = activeJournal().optJSONArray("events");
        if (entries == null) throw new IllegalArgumentException("通知已不在保留范围");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject notice = entries.getJSONObject(i);
            if (notice.optLong("seq") == seq) return new JSONObject(notice.toString());
        }
        throw new IllegalArgumentException("通知已不在保留范围");
    }
    public synchronized JSONObject requireEvent(String identity, long seq, String sourceKey) throws Exception {
        if (identity == null || identity.isEmpty() || !identity.equals(configId()))
            throw new SecurityException("通知连接已变化，请重新读取列表");
        JSONObject event = find(seq);
        if (sourceKey == null || !sourceKey.equals(event.getString("source_key")))
            throw new SecurityException("原通知身份已变化，请重新读取列表");
        return event;
    }
    /** Read/archive are reversible local attention choices, not task or remote-source mutations. */
    public synchronized JSONArray updateAttention(String identity, JSONArray selected, String field, boolean value) throws Exception {
        if (!field.equals("read") && !field.equals("archived")) throw new IllegalArgumentException("通知状态无效");
        if (selected == null || selected.length() < 1 || selected.length() > MAX_EVENTS)
            throw new IllegalArgumentException("请选择保留范围内的通知");
        if (identity == null || identity.isEmpty() || !identity.equals(configId()))
            throw new SecurityException("通知连接已变化，请重新读取列表");
        JSONObject state = activeJournal(); JSONArray entries = state.getJSONArray("events");
        java.util.HashMap<Long, JSONObject> originals = new java.util.HashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject event = entries.getJSONObject(i); originals.put(event.getLong("seq"), event);
        }
        java.util.HashSet<Long> ids = new java.util.HashSet<>();
        for (int i = 0; i < selected.length(); i++) {
            JSONObject item = selected.getJSONObject(i); Object raw = item.opt("seq");
            if (item.length() != 2 || !(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() <= 0 || ((Number) raw).longValue() > 9007199254740991L)
                throw new IllegalArgumentException("通知序号无效");
            long seq = ((Number) raw).longValue(); JSONObject original = originals.get(seq);
            if (original == null || !original.getString("source_key").equals(item.getString("source_key")))
                throw new SecurityException("原通知已变化或不在保留范围，请重新读取列表");
            ids.add(seq);
        }
        JSONArray touched = new JSONArray();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject notice = entries.getJSONObject(i);
            if (ids.contains(notice.getLong("seq"))) {
                notice.put(field, value);
                // A manual read/archive suppresses later alerts, including after undo. This is
                // distinct from "alerted": no claim is made about Android delivery or reading.
                if (value) notice.put("alertSuppressed", true);
                touched.put(notice);
            }
        }
        write(journal, state); announce(); return touched;
    }
    /** Only the current connection can publish its bounded sync observation. No credentials or payloads. */
    public synchronized void recordSync(String identity, long startedAt, String status) throws Exception {
        if (identity.isEmpty() || !identity.equals(configId())) return;
        if (!java.util.Set.of("synced", "more_pending", "credentials_required", "connection_failed", "waiting_network").contains(status))
            throw new IllegalArgumentException("同步状态无效");
        JSONObject state = activeJournal(), before = state.optJSONObject("lastSync");
        JSONObject result = new JSONObject().put("startedAt", startedAt).put("finishedAt", System.currentTimeMillis()).put("status", status);
        long lastSuccess = status.equals("synced") || status.equals("more_pending") ? System.currentTimeMillis() : before == null ? 0 : before.optLong("lastSuccessAt");
        result.put("lastSuccessAt", lastSuccess); state.put("lastSync", result); write(journal, state); announce();
    }
    public synchronized void read(long seq) throws Exception {
        JSONObject state = activeJournal();
        JSONArray entries = state.optJSONArray("events");
        if (entries == null) throw new IllegalArgumentException("没有可读取的通知");
        boolean found = false;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject notice = entries.getJSONObject(i);
            if (notice.optLong("seq") == seq) { notice.put("read", true); found = true; }
        }
        if (!found) throw new IllegalArgumentException("通知已不在保留范围");
        write(journal, state);
        announce();
    }
    public synchronized List<JSONObject> pendingAlerts() throws Exception {
        JSONArray entries = activeJournal().optJSONArray("events");
        List<JSONObject> pending = new ArrayList<>();
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            JSONObject event = entries.getJSONObject(i);
            if (!event.optBoolean("alerted") && !event.optBoolean("alertSuppressed") && !event.optBoolean("read") && !event.optBoolean("archived")) pending.add(new JSONObject(event.toString()));
        }
        return pending;
    }
    public synchronized void alertsPosted(List<JSONObject> posted, String expectedConfigId) throws Exception {
        if (!expectedConfigId.equals(configId())) throw new SecurityException("远程通知连接已改变");
        java.util.HashMap<Long, String> accepted = new java.util.HashMap<>();
        for (JSONObject event : posted) accepted.put(event.getLong("seq"), event.getString("source_key"));
        JSONObject state = activeJournal(); JSONArray entries = state.getJSONArray("events");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject event = entries.getJSONObject(i);
            if (event.getString("source_key").equals(accepted.get(event.getLong("seq")))) event.put("alerted", true);
        }
        write(journal, state);
    }
    public synchronized void alertPosted(long seq, String expectedConfigId) throws Exception {
        if (!expectedConfigId.equals(configId())) throw new SecurityException("远程通知连接已改变");
        JSONObject state = activeJournal();
        JSONArray entries = state.getJSONArray("events");
        for (int i = 0; i < entries.length(); i++) if (entries.getJSONObject(i).optLong("seq") == seq) {
            entries.getJSONObject(i).put("alerted", true);
            write(journal, state);
            return;
        }
    }
    private static String bounded(String raw, int max) {
        if (raw == null || raw.length() > max || raw.indexOf('\u0000') >= 0) throw new SecurityException("通知文本长度无效");
        return raw;
    }
}
