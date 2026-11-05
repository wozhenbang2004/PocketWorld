package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.memory.TaskMemoryTrace;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native Runner observations. Only original message locators are retained, never another transcript. */
public final class ExecutionTraceCapture {
    public static final String REQUEST = "execution_trace_request", MESSAGE = "execution_trace_message", SOURCES="execution_trace_sources";
    private final String task;
    private final RunControl control;
    private final ToolTraceCapture.Observer sink;
    private final Set<String> observedMessages = new HashSet<>(), observedRequests = new HashSet<>();
    private String initialGoalFingerprint = "";

    public ExecutionTraceCapture(String task, RunControl control, ToolTraceCapture.Observer sink) {
        this.task = task; this.control = control; this.sink = sink;
    }
    /** Runner calls at the actual fresh-goal construction, never by guessing a later role=user row. */
    public void initialGoal(JSONObject originalMessage) {
        try { initialGoalFingerprint=reference(originalMessage).getString("fingerprint"); } catch (Exception ignored) { }
    }

    /** A physical STARTED observation follows the original source/admission and accounting commit. */
    public void request(String call, InferenceReceipt.CallSite site, JSONArray originalMessages, int steps) {
        if (!observedRequests.add(call)) return;
        try {
            JSONObject request = event(REQUEST, call, site, steps).put("role", "request");
            JSONArray refs = new JSONArray();
            for (int i = 0; originalMessages != null && i < originalMessages.length(); i++) {
                JSONObject message = originalMessages.optJSONObject(i); if (message == null) continue;
                JSONObject ref = reference(message); refs.put(ref);
                String role = message.optString("role");
                if (!Set.of("system", "user").contains(role) || !observedMessages.add(ref.getString("fingerprint"))) continue;
                boolean initialGoal = "user".equals(role) && initialGoalFingerprint.equals(ref.getString("fingerprint"));
                boolean owner = "user".equals(role) && (!message.optString("ownerMessageId").isEmpty() || initialGoal);
                if (initialGoal) ref.put("inputSource", "native_initial_task_goal");
                record(event(MESSAGE, call, site, steps).put("role", owner ? "input" : "context")
                        .put("name", owner ? "用户输入" : "system".equals(role) ? "系统上下文" : "上下文观察")
                        .put("sourceRef", ref).put("requestId", "model:" + call));
            }
            request.put("sourceRefs", refs); record(request);
        } catch (Exception unavailable) { /* Observation cannot change an already admitted request. */ }
    }

    /** Called only after the original accepted assistant message was checkpointed. */
    public void assistant(String call, InferenceReceipt.CallSite site, JSONObject originalMessage, int steps) {
        try {
            record(event(MESSAGE, call, site, steps).put("role", "assistant").put("name", "助手回复")
                    .put("sourceRef", reference(originalMessage)).put("requestId", "model:" + call)
                    .put("parentId", "model:" + call));
        } catch (Exception unavailable) { /* Original checkpoint remains authoritative. */ }
    }

    private JSONObject event(String type, String call, InferenceReceipt.CallSite site, int steps) throws Exception {
        RunControl.HostRunIdentity run = control == null ? null : control.hostRunIdentity();
        if (run == null || !task.equals(run.taskId()) || !call.matches("[a-fA-F0-9-]{36}"))
            throw new SecurityException("original_trace_run_required");
        JSONObject value = new JSONObject().put("type", type).put("traceVersion", 1).put("source", "native_runner")
                .put("taskId", task).put("runId", run.runId()).put("coordinatorEpoch", run.epoch())
                .put("callId", call).put("timestamp", System.currentTimeMillis()).put("evidenceOnly", true)
                .put("stepIndex", Math.max(0, steps)).put("stepId", "step:" + call + ":" + Math.max(0, steps));
        if (site != null) value.put("turnId", site.turnId).put("turnIndex", site.round + 1)
                .put("callSite", site.json());
        return value;
    }

    private void record(JSONObject event) { try { sink.record(event); } catch (Exception ignored) { } }

    /** Only body-free native locators bypass the generic event's 200-item presentation limit. */
    public static void preserveRequestReferences(JSONObject original, JSONObject sanitized) throws Exception {
        if (!REQUEST.equals(original.optString("type")) || original.optInt("traceVersion") != 1
                || !original.optBoolean("evidenceOnly") || !Set.of("native_runner","native_coordinator").contains(original.optString("source"))
                || !original.optString("callId").matches("[a-fA-F0-9-]{36}")) return;
        JSONArray source = original.optJSONArray("sourceRefs"), retained = new JSONArray();
        int bytes = 2, total = source == null ? 0 : source.length();
        for (int i = 0; source != null && i < source.length(); i++) {
            JSONObject ref = source.optJSONObject(i);
            if (ref == null || !"checkpoint_message".equals(ref.optString("kind"))
                    || !ref.optString("fingerprint").matches("[a-f0-9]{64}")
                    || !Set.of("system", "user", "assistant", "tool").contains(ref.optString("messageRole"))) continue;
            JSONObject safe = new JSONObject().put("kind", "checkpoint_message").put("fingerprint", ref.getString("fingerprint"))
                    .put("messageRole", ref.getString("messageRole"));
            if (ref.optString("callId").matches("[a-fA-F0-9-]{36}")) safe.put("callId", ref.getString("callId"));
            int size = safe.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
            if (bytes + size > 196608) break;
            retained.put(safe); bytes += size;
        }
        sanitized.put("sourceRefs", retained).put("contextRefCount", total).put("contextRefsRetained", retained.length())
                .put("contextRefsLimited", retained.length() != total);
    }

    /** Stable original-message match across non-body checkpoint normalization. */
    public static JSONObject reference(JSONObject message) throws Exception {
        JSONObject selected = new JSONObject();
        for (String key : new String[]{"role", "content", "tool_calls", "tool_call_id", "ownerMessageId", "ownerMessageRevision", "nativeTraceCallId"})
            if (message.has(key)) selected.put(key, message.get(key));
        JSONObject ref = new JSONObject().put("kind", "checkpoint_message").put("fingerprint", TaskMemoryTrace.rawFingerprint(selected))
                .put("messageRole", message.optString("role"));
        for (String key : new String[]{"ownerMessageId", "ownerMessageRevision"}) if (message.has(key)) ref.put(key, message.get(key));
        JSONObject provider = message.optJSONObject("provider_data");
        if (message.optString("nativeTraceCallId").matches("[a-fA-F0-9-]{36}")) ref.put("callId",message.getString("nativeTraceCallId"));
        else if (provider != null && provider.optString("originCallId").matches("[a-fA-F0-9-]{36}")) ref.put("callId", provider.getString("originCallId"));
        return ref;
    }
}
