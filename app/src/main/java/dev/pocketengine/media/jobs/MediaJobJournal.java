package dev.pocketengine.media.jobs;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable provider receipts only. This is not a second Task/Run authority or a work queue. */
public final class MediaJobJournal {
    public static final int MAX_RECEIPTS = 256;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private final File file;
    private JSONObject records;

    public MediaJobJournal(File file) throws Exception {
        this.file = file;
        if (!file.exists()) { records = new JSONObject(); return; }
        if (!file.isFile() || file.length() > MAX_FILE_BYTES) throw new IllegalStateException("媒体回执文件无法安全读取");
        JSONObject saved = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        if (saved.getInt("version") != 1) throw new IllegalStateException("媒体回执版本不受支持");
        records = saved.getJSONObject("receipts");
        if (records.length() > MAX_RECEIPTS) throw new IllegalStateException("媒体回执数量超过上限");
        Iterator<String> ids = records.keys();
        boolean recovered = false;
        while (ids.hasNext()) {
            String id = ids.next();
            identity(id);
            JSONObject record = records.getJSONObject(id);
            if (!id.equals(record.getString("operationId"))) throw new IllegalStateException("媒体回执身份不一致");
            if (record.optString("status").equals("submitting")) {
                record.put("status", "unknown").put("message", "上次提交回执不完整，只能核对原操作，未重新生成");
                recovered = true;
            }
        }
        if (recovered) write(records);
    }

    public synchronized JSONObject get(String operation) throws Exception {
        identity(operation);
        JSONObject record = records.optJSONObject(operation);
        return record == null ? null : copy(record);
    }

    public synchronized void put(JSONObject record) throws Exception {
        String id = record.getString("operationId");
        identity(id);
        if (!records.has(id) && records.length() >= MAX_RECEIPTS) throw new IllegalStateException("媒体回执已达256个，未派发新生成");
        JSONObject next = copy(records);
        next.put(id, copy(record));
        write(next);
        records = next;
    }

    public synchronized JSONArray list() throws Exception {
        JSONArray result = new JSONArray();
        Iterator<String> ids = records.keys();
        while (ids.hasNext()) result.put(visible(records.getJSONObject(ids.next())));
        return result;
    }

    public static JSONObject visible(JSONObject record) throws Exception {
        JSONObject result = copy(record);
        ArrayList<String> remove = new ArrayList<>();
        Iterator<String> keys = result.keys();
        while (keys.hasNext()) { String key = keys.next(); if (key.startsWith("_")) remove.add(key); }
        for (String key : remove) result.remove(key);
        return result;
    }

    public static void identity(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,100}")) throw new SecurityException("媒体操作身份无效");
    }

    public static String hash(Object value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical(value, 0).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    private static String canonical(Object value, int depth) throws Exception {
        if (depth > 32) throw new IllegalArgumentException("媒体JSON嵌套超过上限");
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder result = new StringBuilder("{");
            for (String key : keys) {
                if (result.length() > 1) result.append(',');
                result.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key), depth + 1));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) { if (i > 0) result.append(','); result.append(canonical(array.get(i), depth + 1)); }
            return result.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }

    private void write(JSONObject next) throws Exception {
        byte[] bytes = new JSONObject().put("version", 1).put("receipts", next).toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalStateException("媒体回执文件超过大小上限");
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("无法创建媒体回执目录");
        File temporary = File.createTempFile("media-receipts-", ".pending", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); output.getFD().sync(); }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
}
