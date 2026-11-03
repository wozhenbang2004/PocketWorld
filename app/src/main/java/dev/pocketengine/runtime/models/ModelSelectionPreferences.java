package dev.pocketengine.runtime.models;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Owner presentation metadata only. A saved name never authorizes a model, account or request. */
public final class ModelSelectionPreferences {
    public static final int MAX_ENTRIES = 512;
    private static final int MAX_BYTES = 512 * 1024;
    public record Key(String connectionId, String modelId) {
        public Key {
            if (connectionId == null || !connectionId.matches("[A-Za-z0-9_.:-]{1,208}")) throw new IllegalArgumentException("invalid_model_connection_reference");
            modelId = ModelBinding.modelId(modelId);
        }
    }
    public record Entry(Key key, boolean favorite, long lastUsedAt) {
        public Entry { if (key == null || lastUsedAt < 0) throw new IllegalArgumentException("invalid_model_preference"); }
        JSONObject json() throws Exception { return new JSONObject().put("connectionId", key.connectionId()).put("modelId", key.modelId()).put("favorite", favorite).put("lastUsedAt", lastUsedAt); }
    }
    private static final Object PROCESS_WRITE_LOCK = new Object();
    private final Path file;
    private boolean observedFile;
    private Map<Key, Entry> entries = new LinkedHashMap<>();
    private boolean readable = true;

    public ModelSelectionPreferences(Path file) {
        this.file = file.toAbsolutePath();
        try { load(); } catch (Exception invalid) { readable = false; }
    }
    public synchronized boolean readable() { return readable; }
    /** Refresh an existing surface after another service instance committed presentation metadata. */
    public synchronized void refresh() {
        synchronized(PROCESS_WRITE_LOCK){try{load();readable=true;}catch(Exception unavailable){readable=false;}}
    }
    public synchronized List<Entry> entries() { return List.copyOf(entries.values()); }
    public synchronized Entry get(Key key) { return entries.getOrDefault(key, new Entry(key, false, 0)); }
    /** One idempotent metadata commit; the caller separately validates every live model reference. */
    public synchronized void favorites(List<Key> keys, boolean favorite) throws Exception {
        writeTransaction(() -> {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_ENTRIES) throw new IllegalArgumentException("invalid_model_selection_batch");
        Map<Key, Entry> next = new LinkedHashMap<>(entries);
        for (Key key : keys) {
            if (key == null) throw new IllegalArgumentException("invalid_model_selection_batch");
            Entry old = next.getOrDefault(key, new Entry(key, false, 0));
            if (!favorite && old.lastUsedAt() == 0) next.remove(key);
            else next.put(key, new Entry(key, favorite, old.lastUsedAt()));
        }
        trimRecent(next);
        if (next.equals(entries)) return;
        save(next); entries = next;
        });
    }
    /** Called only after the original successful inference receipt has been persisted. */
    public synchronized void used(Key key, long at) throws Exception {
        writeTransaction(() -> {
        if (at < 1) throw new IllegalArgumentException("invalid_model_use_time");
        Entry old = get(key);
        if (at <= old.lastUsedAt()) return;
        Map<Key, Entry> next = new LinkedHashMap<>(entries);
        next.put(key, new Entry(key, old.favorite(), at)); trimRecent(next); save(next); entries = next;
        });
    }
    /** Favorites first, then actual successful use; stable identity resolves ties. */
    public synchronized List<Key> sort(List<Key> keys) {
        List<Key> result = new ArrayList<>(keys);
        result.sort(Comparator.<Key, Boolean>comparing(k -> get(k).favorite()).reversed()
                .thenComparing(Comparator.comparingLong((Key k) -> get(k).lastUsedAt()).reversed())
                .thenComparing(Key::modelId, String.CASE_INSENSITIVE_ORDER).thenComparing(Key::connectionId).thenComparing(Key::modelId));
        return result;
    }
    @FunctionalInterface private interface Write { void run() throws Exception; }
    private void writeTransaction(Write action)throws Exception {
        requireReadable();
        synchronized(PROCESS_WRITE_LOCK){
            Files.createDirectories(file.getParent());
            Path lockPath=file.resolveSibling(file.getFileName().toString()+".lock");
            try(FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                java.nio.channels.FileLock ignored=channel.lock()){
                // Read under both locks: stale Activity/service caches must not erase a favorite
                // or the newer successful-use time written by another owner of this file.
                try{load();}catch(Exception unavailable){readable=false;throw unavailable;}
                action.run();
            }
        }
    }
    private void requireReadable() { if (!readable) throw new IllegalStateException("模型收藏记录暂不可读取，原文件已保留；仍可选择现有模型。"); }
    private static void trimRecent(Map<Key, Entry> next) {
        if (next.size() <= MAX_ENTRIES) return;
        List<Entry> removable = new ArrayList<>();for(Entry entry:next.values())if(!entry.favorite())removable.add(entry);removable.sort(Comparator.comparingLong(Entry::lastUsedAt));
        for (Entry row : removable) { if (next.size() <= MAX_ENTRIES) break; next.remove(row.key()); }
        if (next.size() > MAX_ENTRIES) throw new IllegalStateException("最多收藏512个模型，请先取消部分收藏。");
    }
    private void load() throws Exception {
        if (!Files.exists(file)) {
            if(Files.notExists(file)&&!observedFile){entries=new LinkedHashMap<>();return;}
            throw new IOException("model_preferences_original_file_unavailable");
        }
        if (Files.size(file) > MAX_BYTES) throw new IOException("model_preferences_too_large");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        JSONTokener parser = new JSONTokener(text); Object value = parser.nextValue();
        if (!(value instanceof JSONObject root) || parser.nextClean() != 0 || !"pocketworld.model-preferences.v1".equals(root.getString("format"))) throw new IOException("invalid_model_preferences");
        JSONArray rows = root.getJSONArray("entries"); if (rows.length() > MAX_ENTRIES) throw new IOException("model_preferences_too_large");
        Map<Key, Entry> loaded = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); Object favorite = row.get("favorite"), at = row.get("lastUsedAt");
            if (!(favorite instanceof Boolean) || !(at instanceof Integer || at instanceof Long)) throw new IOException("invalid_model_preferences");
            Key key = new Key(row.getString("connectionId"), row.getString("modelId"));
            if (loaded.put(key, new Entry(key, (Boolean) favorite, ((Number) at).longValue())) != null) throw new IOException("duplicate_model_preference");
        }
        entries = loaded; observedFile=true;
    }
    private void save(Map<Key, Entry> next) throws Exception {
        JSONArray rows = new JSONArray(); for (Entry row : next.values()) rows.put(row.json());
        byte[] bytes = new JSONObject().put("format", "pocketworld.model-preferences.v1").put("entries", rows).toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("model_preferences_too_large");
        Files.createDirectories(file.getParent()); Path temporary = Files.createTempFile(file.getParent(), "model-preferences-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); observedFile=true;
        } finally { Files.deleteIfExists(temporary); }
    }
}
