package dev.pocketengine.features.local.project;

import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** App-private recovery cache, separate from active files, saved versions and TaskStore. */
public final class ProjectFileDraftStore {
    public static final int MAX_DIRTY_FILES = 32;
    public static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_RECORD_BYTES = 12 * 1024 * 1024;
    private final File root;
    private final Executor writer;
    private final Map<String, ProjectFileDraft> retained = new LinkedHashMap<>();
    private final Map<String, Long> persisted = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();
    private final java.util.Set<String> writing = new java.util.HashSet<>();
    private final java.util.Set<String> releaseWhenWritten = new java.util.HashSet<>();
    private final Object diskWrites = new Object();

    public ProjectFileDraftStore(File appPrivateDirectory, Executor writer) {
        this.root = appPrivateDirectory; this.writer = writer;
    }

    /** Call from the existing UI IO context. A damaged record is never replaced with an empty draft. */
    public synchronized ProjectFileDraft open(String project, String checkout, String path, boolean fresh) throws IOException {
        String key = key(project, checkout, path);
        ProjectFileDraft cached = retained.get(key); if (cached != null) return cached;
        File file = file(key);
        ProjectFileDraft draft;
        try { draft = Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ? ProjectFileDraft.restore(read(file), project, checkout, path) : ProjectFileDraft.empty(project, checkout, path, fresh); }
        catch (Exception damaged) { throw new IOException("原文件草稿读取失败，已保留缓存；未用空白或当前文件替代：" + damaged.getMessage(), damaged); }
        retained.put(key, draft); persisted.put(key, draft.revision); return draft;
    }

    /** Immediate in-memory retention; coalesced private-cache IO never performs a workspace save. */
    public synchronized void retain(ProjectFileDraft draft) {
        String key = key(draft.projectId, draft.checkoutId, draft.path);
        ProjectFileDraft old = retained.get(key);
        if (old != null && old != draft && old.revision >= draft.revision) throw new IllegalStateException("旧编辑器不能覆盖较新的原文件草稿");
        long bytes = draft.dirty() ? memoryBytes(draft) : 0; int count = draft.dirty() ? 1 : 0;
        for (Map.Entry<String, ProjectFileDraft> entry : retained.entrySet()) if (!entry.getKey().equals(key) && entry.getValue().dirty()) { bytes += memoryBytes(entry.getValue()); count++; }
        if (count > MAX_DIRTY_FILES || bytes > MAX_CACHE_BYTES) throw new IllegalStateException("当前项目文件草稿已达32份/32MiB，请先明确保存或放弃其他草稿；原文字未替换");
        retained.put(key, draft);
        releaseWhenWritten.remove(key);
        queue(key);
    }
    public synchronized void release(ProjectFileDraft draft) {
        String key = key(draft.projectId, draft.checkoutId, draft.path);
        if (retained.get(key) != draft || draft.dirty()) return;
        if (writing.contains(key)) releaseWhenWritten.add(key); else retained.remove(key);
    }
    public synchronized void retry(ProjectFileDraft draft) {
        String key = key(draft.projectId, draft.checkoutId, draft.path);
        if (retained.get(key) != draft) throw new IllegalStateException("草稿已被另一个原文件编辑器更新");
        queue(key);
    }
    public synchronized String persistenceError(ProjectFileDraft draft) { return errors.get(key(draft.projectId, draft.checkoutId, draft.path)); }
    public synchronized boolean durable(ProjectFileDraft draft) { return persisted.getOrDefault(key(draft.projectId, draft.checkoutId, draft.path), -1L) >= draft.revision; }

    /** Bounded recovery discovery. Run on IO, not during Compose rendering. */
    public synchronized List<String> dirtyPaths(String project, String checkout) throws IOException {
        ProjectFileDraft.identity(project); ProjectFileDraft.identity(checkout);
        java.util.TreeSet<String> paths = new java.util.TreeSet<>();
        File[] files = records();
        for (File file : files) {
            JSONObject row = read(file);
            if (project.equals(row.optString("projectId")) && checkout.equals(row.optString("checkoutId"))) {
                ProjectFileDraft draft;
                try { draft = ProjectFileDraft.restore(row, project, checkout, row.getString("path")); }
                catch (Exception damaged) { throw new IOException("保留的项目草稿损坏，未自动清除：" + file.getName(), damaged); }
                if (draft.dirty()) paths.add(draft.path);
            }
        }
        for (ProjectFileDraft draft : retained.values()) if (draft.projectId.equals(project) && draft.checkoutId.equals(checkout)) {
            if (draft.dirty()) paths.add(draft.path); else paths.remove(draft.path);
        }
        return new ArrayList<>(paths);
    }

    private void queue(String key) {
        if (!writing.add(key)) return;
        try { writer.execute(() -> flush(key)); }
        catch (RuntimeException rejected) { writing.remove(key); errors.put(key, "草稿仅留在当前会话，尚未写入手机缓存：" + rejected.getMessage()); }
    }
    private void flush(String key) {
        for (;;) {
            ProjectFileDraft snapshot;
            synchronized (this) { snapshot = retained.get(key); }
            try {
              synchronized (diskWrites) {
                if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建项目草稿缓存目录");
                if (snapshot.dirty()) {
                    byte[] bytes = encodePrivateDraft(snapshot.toJson());
                    if (bytes.length > MAX_RECORD_BYTES) throw new IOException("这份草稿缓存超过12MiB；文字仍留在当前会话");
                    long total = bytes.length; int count = 1;
                    for (File existing : records()) if (!existing.equals(file(key))) { total += existing.length(); count++; }
                    if (count > MAX_DIRTY_FILES || total > MAX_CACHE_BYTES) throw new IOException("手机项目草稿缓存已达32份/32MiB；请明确保存或放弃其他草稿后重试，不会自动删稿");
                    atomic(file(key), bytes);
                } else Files.deleteIfExists(file(key).toPath());
              }
                synchronized (this) {
                    persisted.put(key, snapshot.revision); errors.remove(key);
                    if (retained.get(key) == snapshot) { writing.remove(key); if (releaseWhenWritten.remove(key) && !snapshot.dirty()) retained.remove(key); return; }
                }
            } catch (Exception failure) {
                synchronized (this) {
                    errors.put(key, "草稿尚未写入手机缓存，文字仍在当前会话：" + failure.getMessage());
                    if (retained.get(key) == snapshot) { writing.remove(key); return; }
                }
            }
        }
    }
    /** Keep org.json responsible for JSON structure/escaping. At the UTF-8 boundary only,
     * escape isolated UTF-16 code units the current writer otherwise sends raw; paired
     * surrogates, ordinary text, existing escape sequences and valid v1 bytes are unchanged. */
    private static byte[] encodePrivateDraft(JSONObject record) {
        String json = record.toString(); StringBuilder escaped = null;
        for (int i = 0; i < json.length(); i++) {
            char unit = json.charAt(i);
            if (Character.isHighSurrogate(unit) && i + 1 < json.length() && Character.isLowSurrogate(json.charAt(i + 1))) {
                if (escaped != null) escaped.append(unit).append(json.charAt(i + 1)); i++; continue;
            }
            if (Character.isSurrogate(unit)) {
                if (escaped == null) { escaped = new StringBuilder(json.length() + 16); escaped.append(json, 0, i); }
                escaped.append("\\u"); String hex = Integer.toHexString(unit);
                for (int pad = hex.length(); pad < 4; pad++) escaped.append('0'); escaped.append(hex);
            } else if (escaped != null) escaped.append(unit);
        }
        return (escaped == null ? json : escaped.toString()).getBytes(StandardCharsets.UTF_8);
    }
    private static long memoryBytes(ProjectFileDraft value) { return (long)value.text.getBytes(StandardCharsets.UTF_8).length + value.baseContent.getBytes(StandardCharsets.UTF_8).length; }
    private File[] records() throws IOException {
        if (!root.exists()) return new File[0];
        File[] result = root.listFiles((dir, name) -> name.matches("[a-f0-9]{64}\\.draft\\.json"));
        if (result == null) throw new IOException("无法读取项目草稿缓存目录");
        if (result.length > MAX_DIRTY_FILES) throw new IOException("项目草稿缓存超限，未自动删除文件");
        return result;
    }
    private JSONObject read(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile() || file.length() > MAX_RECORD_BYTES) throw new IOException("项目草稿不是可读取的有界普通文件");
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length > MAX_RECORD_BYTES) throw new IOException("项目草稿读取超限");
        try { return new JSONObject(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()); }
        catch (Exception bad) { throw new IOException("项目草稿缓存格式损坏，未清除", bad); }
    }
    private File file(String key) { return new File(root, key + ".draft.json"); }
    private static String key(String project, String checkout, String path) {
        return ProjectFileDraft.digest(ProjectFileDraft.identity(project) + "\n" + ProjectFileDraft.identity(checkout) + "\n" + ProjectFileDraft.path(path));
    }
    static void atomic(File target, byte[] bytes) throws IOException {
        File pending = File.createTempFile("draft-", ".pending", target.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(pending)) { out.write(bytes); out.getFD().sync(); }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending.toPath()); }
    }
}
