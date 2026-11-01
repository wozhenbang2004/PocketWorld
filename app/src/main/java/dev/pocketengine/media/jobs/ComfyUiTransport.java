package dev.pocketengine.media.jobs;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.media.MediaTransport;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** ComfyUI's public HTTP contract, independently implemented from provider route behavior. */
public final class ComfyUiTransport {
    public static final int MAX_OUTPUTS = 8;
    private ComfyUiTransport() {}

    public static JSONObject workflow(JSONObject original) throws Exception {
        if (original == null || original.length() < 1 || original.length() > 256
                || original.toString().getBytes(StandardCharsets.UTF_8).length > 256 * 1024) {
            throw new IllegalArgumentException("ComfyUI需1–256个节点且不超过256KiB的API格式工作流");
        }
        JSONObject result = new JSONObject(original.toString());
        Iterator<String> ids = result.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("ComfyUI节点标识无效");
            JSONObject node = result.getJSONObject(id);
            String type = node.getString("class_type");
            if (type.isBlank() || type.length() > 160 || type.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException("ComfyUI节点类型无效");
            node.getJSONObject("inputs");
        }
        MediaJobJournal.hash(result); // Also enforce a bounded JSON depth before any request.
        return result;
    }

    public static String submit(String base, String key, JSONObject workflow, JSONObject receipt, RunControl control) throws Exception {
        JSONObject marker = new JSONObject().put("operationId", receipt.getString("operationId"))
                .put("requestHash", receipt.getString("_requestHash"));
        JSONObject body = new JSONObject().put("prompt", workflow).put("prompt_id", receipt.getString("providerJobId"))
                .put("client_id", receipt.getString("_clientId")).put("extra_data", new JSONObject().put("pocketworld", marker));
        JSONObject reply = json(base, key, "POST", "/prompt", null, body, control);
        String id = reply.getString("prompt_id");
        jobId(id);
        return id;
    }

    public static final class Observation {
        public final String id, state;
        public final JSONArray outputs;
        Observation(String id, String state, JSONArray outputs) { this.id = id; this.state = state; this.outputs = outputs; }
    }

    /** Read the original identity only. An empty history never means that submission failed. */
    public static Observation query(String base, String key, JSONObject receipt, RunControl control) throws Exception {
        String candidate = receipt.getString("providerJobId");
        jobId(candidate);
        boolean confirmed = receipt.optBoolean("providerJobIdConfirmed");
        JSONObject history = json(base, key, "GET", "/history/" + candidate, null, null, control);
        JSONObject entry = history.optJSONObject(candidate);
        if (entry != null && (confirmed || matches(entry.optJSONArray("prompt"), receipt))) return fromHistory(candidate, entry);
        JSONObject queue = json(base, key, "GET", "/queue", null, null, control);
        Observation queued = null;
        for (String list : new String[]{"queue_running", "queue_pending"}) {
            JSONArray items = queue.getJSONArray(list);
            for (int i = 0; i < items.length(); i++) {
                JSONArray item = items.getJSONArray(i);
                if (item.length() < 4) throw new IllegalStateException("ComfyUI队列回执格式无效");
                String id = item.getString(1);
                boolean match = confirmed ? candidate.equals(id) : matches(item, receipt);
                if (match) {
                    jobId(id);
                    if (queued != null) throw new IllegalStateException("ComfyUI原操作出现多个队列记录，需人工核对");
                    queued = new Observation(id, list.equals("queue_running") ? "running" : "queued", new JSONArray());
                }
            }
        }
        if (queued != null) return queued;
        // The job can finish between the history and queue reads. Re-read the exact id once.
        history = json(base, key, "GET", "/history/" + candidate, null, null, control);
        entry = history.optJSONObject(candidate);
        if (entry != null && (confirmed || matches(entry.optJSONArray("prompt"), receipt))) return fromHistory(candidate, entry);
        if (!confirmed) {
            JSONObject recent = json(base, key, "GET", "/history", "max_items=64", null, control);
            Observation found = null;
            Iterator<String> ids = recent.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                JSONObject item = recent.getJSONObject(id);
                if (!matches(item.optJSONArray("prompt"), receipt)) continue;
                jobId(id);
                if (found != null) throw new IllegalStateException("ComfyUI原操作出现多个历史记录，需人工核对");
                found = fromHistory(id, item);
            }
            if (found != null) return found;
        }
        return new Observation(candidate, "unknown", new JSONArray());
    }

    private static boolean matches(JSONArray prompt, JSONObject receipt) throws Exception {
        if (prompt == null || prompt.length() < 4) return false;
        JSONObject extra = prompt.optJSONObject(3);
        if (extra == null || !receipt.getString("_clientId").equals(extra.optString("client_id"))) return false;
        JSONObject marker = extra.optJSONObject("pocketworld");
        return marker != null && receipt.getString("operationId").equals(marker.optString("operationId"))
                && receipt.getString("_requestHash").equals(marker.optString("requestHash"));
    }

    private static Observation fromHistory(String id, JSONObject entry) throws Exception {
        JSONObject status = entry.optJSONObject("status");
        String state = "unknown";
        if (status != null) {
            JSONArray messages = status.optJSONArray("messages");
            if (messages != null) for (int i = 0; i < messages.length(); i++) {
                JSONArray message = messages.optJSONArray(i);
                String type = message == null ? "" : message.optString(0);
                if (type.equals("execution_interrupted")) state = "cancelled";
                else if (type.equals("execution_error") && !state.equals("cancelled")) state = "failed";
            }
            if (state.equals("unknown")) {
                if (status.optString("status_str").equals("error")) state = "failed";
                else if (status.optBoolean("completed") && status.optString("status_str").equals("success")) state = "completed";
            }
        }
        return new Observation(id, state, outputs(entry.optJSONObject("outputs")));
    }

    private static JSONArray outputs(JSONObject nodes) throws Exception {
        JSONArray result = new JSONArray();
        if (nodes == null) return result;
        ArrayList<String> ids = new ArrayList<>();
        Iterator<String> keys = nodes.keys();
        while (keys.hasNext()) ids.add(keys.next());
        Collections.sort(ids);
        for (String id : ids) {
            JSONObject node = nodes.getJSONObject(id);
            for (String kind : new String[]{"images", "audio", "gifs"}) {
                JSONArray files = node.optJSONArray(kind);
                if (files == null) continue;
                for (int i = 0; i < files.length(); i++) {
                    if (result.length() >= MAX_OUTPUTS) throw new IllegalStateException("ComfyUI输出超过8个上限，未自动下载");
                    JSONObject file = files.getJSONObject(i);
                    String filename = file.getString("filename"), folder = file.optString("subfolder"), type = file.optString("type", "output");
                    outputPath(filename, folder, type);
                    result.put(new JSONObject().put("nodeId", id).put("outputKind", kind).put("filename", filename)
                            .put("subfolder", folder).put("type", type));
                }
            }
        }
        return result;
    }

    public static URI outputUri(String base, JSONObject output) throws Exception {
        String name = output.getString("filename"), folder = output.getString("subfolder"), type = output.getString("type");
        outputPath(name, folder, type);
        return MediaTransport.route(base, "/view", "filename=" + encode(name) + "&subfolder=" + encode(folder) + "&type=" + encode(type));
    }

    private static void outputPath(String filename, String folder, String type) {
        if (filename.isEmpty() || filename.length() > 240 || filename.contains("/") || filename.contains("\\")
                || filename.contains("..") || filename.contains(":") || filename.chars().anyMatch(c -> c < 32 || c == 127)
                || folder.length() > 512 || folder.startsWith("/") || folder.contains("\\") || folder.contains(":")
                || folder.chars().anyMatch(c -> c < 32 || c == 127) || !type.matches("output|temp")) {
            throw new SecurityException("ComfyUI产物路径不在允许的生成输出范围");
        }
        for (String part : folder.split("/", -1)) if (part.equals(".") || part.equals("..")) throw new SecurityException("ComfyUI产物路径越界");
    }

    public static boolean cancel(String base, String key, String id, boolean jobsApi, RunControl control) throws Exception {
        jobId(id);
        if (jobsApi) return json(base, key, "POST", "/api/jobs/" + id + "/cancel", null, new JSONObject(), control).getBoolean("cancelled");
        MediaTransport.exchange(MediaTransport.route(base, "/queue", null), key, "POST",
                new JSONObject().put("delete", new JSONArray().put(id)), MediaTransport.MAX_JSON_BYTES, control);
        return true; // Queue acceptance only; not proof that a racing running job was cancelled.
    }

    private static JSONObject json(String base, String key, String method, String route, String query, JSONObject body, RunControl control) throws Exception {
        return new JSONObject(new String(MediaTransport.exchange(MediaTransport.route(base, route, query), key, method,
                body, MediaTransport.MAX_JSON_BYTES, control), StandardCharsets.UTF_8));
    }
    private static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
    private static void jobId(String id) {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new SecurityException("ComfyUI任务标识无效");
    }
}
