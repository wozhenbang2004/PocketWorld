package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.RunControl;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** Observation of one original Runner tool attempt. It never admits or replays a tool. */
public final class ToolTraceCapture {
    public static final String TYPE = "tool_trace";
    /** Observation failures are caught here and never become original execution failures. */
    public interface Observer { void record(JSONObject event) throws Exception; }
    private final String id = UUID.randomUUID().toString();
    private final JSONObject identity;
    private final Observer sink;
    private final long startedAt = System.currentTimeMillis(), startNanos = System.nanoTime();
    private boolean entered, finished;

    public ToolTraceCapture(String task, RunControl control, String parentId, String parentCallId,
                            String providerCallId, String tool, String memberId, String turnId,
                            Object input, Observer sink) throws Exception {
        this(task, matchingRun(task, control), matchingEpoch(task, control), parentId, parentCallId, providerCallId,
                tool, memberId, turnId, input, "native_runner", sink);
    }
    public ToolTraceCapture(String task, RunControl control, String parentId, String parentCallId,
                            String providerCallId, String tool, String memberId, String turnId,
                            Object input, JSONObject schema, int stepIndex, Observer sink) throws Exception {
        this(task, matchingRun(task, control), matchingEpoch(task, control), parentId, parentCallId, providerCallId,
                tool, memberId, turnId, input, "native_runner", schema, stepIndex, sink);
    }

    /** The original coordinator supplies its typed native identity; this overload grants no authority. */
    public ToolTraceCapture(String task, String run, int epoch, String parentId, String parentCallId,
                            String providerCallId, String tool, String memberId, String turnId,
                            Object input, String source, Observer sink) throws Exception {
        this(task, run, epoch, parentId, parentCallId, providerCallId, tool, memberId, turnId, input, source, null, -1, sink);
    }
    public ToolTraceCapture(String task, String run, int epoch, String parentId, String parentCallId,
                            String providerCallId, String tool, String memberId, String turnId,
                            Object input, String source, JSONObject schema, int stepIndex, Observer sink) throws Exception {
        this.sink = sink;
        identity = new JSONObject().put("type", TYPE).put("traceVersion", 1).put("callId", id)
                .put("taskId", task).put("runId", run).put("coordinatorEpoch", epoch)
                .put("parentId", parentId == null ? JSONObject.NULL : parentId)
                .put("parentCallId", parentCallId == null ? "" : parentCallId)
                .put("providerToolCallId", providerCallId).put("tool", tool)
                .put("memberId", memberId == null ? "" : memberId).put("turnId", turnId == null ? "" : turnId)
                .put("startedAt", startedAt).put("source", source).put("evidenceOnly", true)
                .put("timingMeaning", "native_tool_handling_interval_including_checks_and_receipt_persistence");
        // Privacy projection runs inside record's observation-only failure boundary.
        if (input != null) identity.put("input", input);
        if (schema != null) identity.put("schema", new JSONObject(schema.toString())).put("schemaSource", "native_selected_tool_definition")
                .put("schemaSanitized", true);
        if (stepIndex >= 0) identity.put("stepIndex", stepIndex).put("stepId", "step:" + id);
        record(new JSONObject(identity.toString()).put("stage", "started").put("outcome", "STARTED")
                .put("status", "running").put("finishedAt", JSONObject.NULL).put("elapsedNanos", JSONObject.NULL)
                .put("dispatchEntered", false));
    }

    public String id() { return id; }

    /** The existing invocation branch calls this immediately before its original implementation. */
    public void dispatchEntered() { entered = true; }

    /** Link the old presentation event to this observation without changing its native operation carrier. */
    public JSONObject link(JSONObject event) {
        if (!"tool".equals(event.optString("type"))) return event;
        try { return new JSONObject(event.toString()).put("traceCallId", id); }
        catch (Exception unavailable) { return event; }
    }

    public void finish(JSONObject receipt, String operation, Exception failure, RunControl control) {
        if (finished) return;
        finished = true;
        try {
            String outcome = outcome(receipt, failure, control != null && control.isCancelled(), entered);
            JSONObject event = new JSONObject(identity.toString()).put("stage", "finished")
                    .put("outcome", outcome).put("status", outcome.toLowerCase(java.util.Locale.ROOT))
                    .put("finishedAt", System.currentTimeMillis())
                    .put("elapsedNanos", Long.toString(Math.max(0L, System.nanoTime() - startNanos)))
                    .put("dispatchEntered", entered);
            if (receipt != null) event.put("output", receipt);
            if (operation != null) event.put("operationId", operation);
            // A real receipt and a later persistence/checkpoint failure can coexist.
            // Preserve both observations without replacing the receipt's native outcome.
            if (failure != null) event.put("error", new JSONObject()
                    .put("kind", failure.getClass().getSimpleName())
                    .put("receiptAvailable", receipt != null));
            if (receipt != null) {
                String state = receipt.optString("status", receipt.optString("state"));
                if (Set.of("project_running", "project_stopping").contains(receipt.optString("code")) || Set.of("accepted", "running", "pending", "queued", "stopping").contains(state))
                    event.put("effectPending", true).put("completionMeaning", "invocation_returned_before_effect_completion");
            }
            record(event);
        } catch (Exception unavailable) {
            // This derived observation must never replace a committed receipt or become execution authority.
        }
    }

    private void record(JSONObject event) {
        try { sink.record(ToolTracePrivacy.tool(event)); }
        catch (Exception unavailable) { /* Original execution and its durable operation journal remain authoritative. */ }
    }

    /** Retain the call/timing even when original safe tool bodies exceed a diagnostic page. */
    public static JSONObject bounded(JSONObject sanitized) throws Exception {
        JSONObject result = new JSONObject(sanitized.toString());
        for (String field : new String[]{"input", "output", "schema"}) {
            Object value = result.opt(field); if (value == null) continue;
            int bytes = value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > ("schema".equals(field) ? 131072 : 24576)) result.put(field, new JSONObject().put("available", false).put("reason", "original_safe_body_exceeds_trace_budget")
                    .put("sourceBytes", bytes)).put("contentLimited", true);
        }
        return result;
    }
    /** Snapshot only the definition that was actually offered to this native request. */
    public static JSONObject schema(org.json.JSONArray tools, String name) throws Exception {
        JSONObject found = null;
        for (int i = 0; tools != null && i < tools.length(); i++) {
            JSONObject definition = tools.optJSONObject(i), function = definition == null ? null : definition.optJSONObject("function");
            if (function == null || !name.equals(function.optString("name"))) continue;
            if (found != null) return null; // Ambiguous definitions do not become historical proof.
            found = new JSONObject(definition.toString());
        }
        return found;
    }
    private static String matchingRun(String task, RunControl control) {
        RunControl.HostRunIdentity run = control == null ? null : control.hostRunIdentity(); return run != null && task.equals(run.taskId()) ? run.runId() : "";
    }
    private static int matchingEpoch(String task, RunControl control) {
        RunControl.HostRunIdentity run = control == null ? null : control.hostRunIdentity(); return run != null && task.equals(run.taskId()) ? run.epoch() : 0;
    }

    private static String outcome(JSONObject receipt, Exception failure, boolean cancelled, boolean entered) {
        if (receipt != null) {
            dev.pocketengine.core.ToolOutcome canonical = dev.pocketengine.core.ToolOutcome.classify(receipt);
            if (canonical.requiresReconciliation()) return "UNKNOWN";
            String status = receipt.optString("status", receipt.optString("state"));
            if (Set.of("cancelled", "canceled", "stopped").contains(status)) return "CANCELLED";
            if (canonical.requiresUser()) return "AWAITING_USER";
            if (canonical.correctable()) return Boolean.FALSE.equals(receipt.opt("accepted"))
                    || Boolean.FALSE.equals(receipt.opt("nativeDispatched")) ? "REJECTED" : "FAILED";
            return "COMPLETED";
        }
        if (entered) return "UNKNOWN";
        if (cancelled || failure instanceof InterruptedException) return "CANCELLED";
        return failure == null ? "UNKNOWN" : "FAILED";
    }
}
