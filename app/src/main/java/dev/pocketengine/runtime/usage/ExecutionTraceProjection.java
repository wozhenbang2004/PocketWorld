package dev.pocketengine.runtime.usage;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only nodes from original native receipts and already-sanitized task events. */
public final class ExecutionTraceProjection {
    private ExecutionTraceProjection() { }

    public static JSONObject model(JSONObject original, long sequence) throws Exception {
        JSONObject validated = InferenceProjection.fromEvents(new JSONArray().put(original));
        JSONArray receipts = validated.getJSONArray("receipts");
        if (receipts.length() != 1) return null;
        JSONObject receipt = receipts.getJSONObject(0), context = receipt.getJSONObject("context"), route = receipt.getJSONObject("route");
        JSONObject callSite = context.optJSONObject("callSite");
        String call = receipt.getString("callId"), outcome = receipt.getString("outcome");
        String model = route.isNull("responseModel") ? route.optString("requestedModel") : route.optString("responseModel", route.optString("requestedModel"));
        JSONObject node = base("model:" + call, "model", model.isEmpty() ? "model.request" : model, sequence)
                .put("role", "request").put("requestId", "model:" + call)
                .put("callId", call).put("taskId", context.optString("taskId")).put("runId", context.optString("runId"))
                .put("epoch", context.optInt("epoch")).put("memberId", context.optString("memberId"))
                .put("turnId", callSite == null ? "" : callSite.optString("turnId"))
                .put("purpose", context.optString("purpose")).put("status", status(outcome))
                .put("model", model).put("route", new JSONObject(route.toString()))
                .put("usage", new JSONObject(receipt.getJSONObject("usage").toString()))
                .put("logicalRequestId", route.optString("logicalRequestId")).put("attemptNo", route.optInt("attemptNo", 0))
                .put("source", "native_inference_receipt").put("legacy", false);
        if (callSite != null) node.put("callSite", new JSONObject(callSite.toString())).put("turnIndex", callSite.optInt("round", -1) + ("EXTERNAL_TASK_RUN".equals(context.optString("bindingKind"))?0:1));
        if (route.optJSONObject("requestOptions") != null) node.put("options", new JSONObject(route.getJSONObject("requestOptions").toString()))
                .put("optionsSource", route.optString("requestOptionsSource"));
        if ("provider_text_delta".equals(receipt.optString("timingEvidence")) && !receipt.isNull("firstVisibleTextElapsedNanos")) {
            try {
                long first = new BigDecimal(receipt.get("firstVisibleTextElapsedNanos").toString()).longValueExact();
                if (first >= 0) node.put("firstVisibleTextAt", receipt.opt("firstVisibleTextAt"))
                        .put("firstVisibleTextElapsedNanos", Long.toString(first)).put("firstVisibleTextMs", first / 1_000_000.0)
                        .put("timingEvidence", "provider_text_delta");
            } catch (ArithmeticException | NumberFormatException invalid) { /* Missing timing stays unknown. */ }
        }
        if (receipt.optJSONObject("failure") != null) node.put("error", new JSONObject(receipt.getJSONObject("failure").toString()));
        else if (!receipt.isNull("errorCode") && receipt.has("errorCode")) node.put("error", new JSONObject().put("code", receipt.getString("errorCode")));
        timing(node, receipt, !"STARTED".equals(outcome));
        if (node.has("firstVisibleTextElapsedNanos") && !receipt.isNull("lastVisibleTextElapsedNanos")) try {
            long first = Long.parseLong(node.getString("firstVisibleTextElapsedNanos"));
            long last = new BigDecimal(receipt.get("lastVisibleTextElapsedNanos").toString()).longValueExact();
            Long total = elapsedNanos(receipt);
            if (last >= first && total != null && last <= total) node.put("lastVisibleTextAt", receipt.opt("lastVisibleTextAt"))
                    .put("lastVisibleTextElapsedNanos", Long.toString(last)).put("generationDurationMs", (last - first) / 1_000_000.0)
                    .put("generationTimingMeaning", "client_first_to_last_visible_text_delta");
        } catch (ArithmeticException | NumberFormatException invalid) { /* Unknown remains unknown. */ }
        return node;
    }

    /** The query selects one native start and its matching terminal observation, never tool JSON identities. */
    public static JSONObject tool(JSONObject start, JSONObject terminal, long sequence) throws Exception {
        if (!validTool(start)) return null;
        if (terminal != null && (!validTool(terminal) || !sameTool(start, terminal))) return null;
        JSONObject event = terminal == null ? start : terminal;
        String call = start.getString("callId"), parent = start.optString("parentId");
        if (!parent.matches("(?:model|tool):[a-fA-F0-9-]{36}")) parent = "";
        JSONObject node = base("tool:" + call, "tool", start.optString("tool", "tool.call"), sequence)
                .put("role", parent.startsWith("tool:") ? "subtool" : "tool")
                .put("requestId", start.optString("parentCallId").isEmpty() ? "" : "model:" + start.optString("parentCallId"))
                .put("callId", call).put("parentId", parent.isEmpty() ? JSONObject.NULL : parent)
                .put("parentCallId", start.optString("parentCallId")).put("providerToolCallId", start.optString("providerToolCallId"))
                .put("taskId", start.optString("taskId")).put("runId", start.optString("runId"))
                .put("epoch", start.optInt("coordinatorEpoch")).put("memberId", start.optString("memberId"))
                .put("turnId", start.optString("turnId")).put("status", status(event.optString("outcome", "STARTED")))
                .put("source", "native_tool_trace").put("legacy", false).put("dispatchEntered", event.optBoolean("dispatchEntered"))
                .put("timingMeaning", "native_tool_handling_interval_including_checks_and_receipt_persistence");
        for (String field : new String[]{"input", "output", "schema", "schemaSource", "schemaSanitized", "stepId", "stepIndex", "checkpointId", "discussionRound", "error", "operationId", "effectPending", "completionMeaning", "contentLimited"})
            if (event.has(field)) node.put(field, event.get(field));
        JSONObject output = event.optJSONObject("output");
        if (output != null && !node.has("error") && !Set.of("completed", "running").contains(node.getString("status"))) {
            JSONObject error = new JSONObject();
            for (String field : new String[]{"code", "message", "error", "requiresReconciliation"}) if (output.has(field)) error.put(field, output.get(field));
            if (error.length() > 0) node.put("error", error);
        }
        timing(node, event, terminal != null && "finished".equals(event.optString("stage")));
        return node;
    }

    /** Older events are instants. A receipt timestamp is never a measured execution interval. */
    public static JSONObject event(JSONObject event, long sequence, String provenRun, int epoch) throws Exception {
        String type = event.optString("type", "event");
        boolean tool = "tool".equals(type);
        boolean nativeMessage = ExecutionTraceCapture.MESSAGE.equals(type) && event.optInt("traceVersion") == 1
                && event.optBoolean("evidenceOnly") && Set.of("native_runner","native_coordinator").contains(event.optString("source"))
                && Set.of("input", "context", "assistant").contains(event.optString("role"));
        JSONObject node = base("event:" + sequence, tool ? "tool" : "event", nativeMessage ? event.optString("name", type) : event.optString("tool", type), sequence)
                .put("role", nativeMessage ? event.getString("role") : tool ? "tool" : "event")
                .put("taskId", event.optString("taskId")).put("runId", provenRun).put("epoch", epoch)
                .put("status", tool ? status(event.optString("status", "observed")) : "observed").put("sourceStatus", event.optString("status"))
                .put("source", "task_event").put("eventType", type).put("legacy", tool).put("timingKnown", false)
                .put("startedAt", positive(event.optLong("timestamp", event.optLong("at", 0))))
                .put("finishedAt", JSONObject.NULL).put("elapsedNanos", JSONObject.NULL).put("durationMs", JSONObject.NULL);
        for (String field : new String[]{"message", "memberId", "turnId"}) if (event.has(field)) node.put(field, event.get(field));
        if (nativeMessage) for (String field : new String[]{"turnIndex", "stepId", "stepIndex", "requestId", "sourceRef", "callId", "parentId", "callSite", "checkpointId", "discussionRound", "executionTurnId"})
            if (event.has(field)) node.put(field, event.get(field));
        if (event.has("receipt")) node.put("output", event.get("receipt"));
        if (event.has("nativeOperationId")) node.put("operationId", event.get("nativeOperationId"));
        if (provenRun.isEmpty()) node.put("runBinding", "unbound_legacy_task_event");
        else node.put("runBinding", "native_event_or_operation");
        return node;
    }

    /** Overview pages never deliver old transcript/tool bodies outside the current source gate. */
    public static JSONObject compact(JSONObject original) throws Exception {
        JSONObject node = new JSONObject(original.toString());
        for (String field : new String[]{"message", "input", "output", "schema", "raw", "preview", "sourceRefs", "sourcePlan"}) node.remove(field);
        JSONObject error = node.optJSONObject("error");
        if (error != null) {
            node.put("hasError", error.length() > 0);
            JSONObject safe = new JSONObject();
            for (String field : new String[]{"code", "kind", "retryable", "requiresReconciliation"}) if (error.has(field)) safe.put(field, error.get(field));
            node.put("error", safe);
        }
        return node.put("detailAvailable", true).put("bodyIncluded", false);
    }

    public static Long elapsedNanos(JSONObject value) {
        Object raw = value.opt("elapsedNanos");
        if (!(raw instanceof Number) && !(raw instanceof String)) return null;
        try { long number = new BigDecimal(raw.toString()).longValueExact(); return number >= 0 ? number : null; }
        catch (ArithmeticException | NumberFormatException invalid) { return null; }
    }

    private static void timing(JSONObject node, JSONObject event, boolean terminal) throws Exception {
        long start = event.optLong("startedAt", 0), end = event.optLong("finishedAt", 0);
        Long elapsed = terminal && end > 0 ? elapsedNanos(event) : null;
        boolean known = start > 0 && elapsed != null;
        node.put("startedAt", positive(start)).put("finishedAt", terminal ? positive(end) : JSONObject.NULL)
                .put("elapsedNanos", known ? Long.toString(elapsed) : JSONObject.NULL)
                .put("durationMs", known ? elapsed / 1_000_000.0 : JSONObject.NULL).put("timingKnown", known);
        if (terminal && end > 0 && start > 0 && end < start) node.put("wallClockAdjusted", true);
    }
    private static JSONObject base(String id, String kind, String name, long sequence) throws Exception {
        return new JSONObject().put("id", id).put("kind", kind).put("name", name).put("sequence", sequence)
                .put("parentId", JSONObject.NULL).put("parentVisible", false).put("readOnly", true).put("executionAuthorized", false);
    }
    private static Object positive(long value) { return value > 0 ? value : JSONObject.NULL; }
    private static String status(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        if (Set.of("started", "running").contains(value)) return "running";
        return Set.of("completed", "failed", "cancelled", "rejected", "awaiting_user", "needs_confirmation", "observed").contains(value) ? value : "unknown";
    }
    private static boolean validTool(JSONObject event) {
        return ToolTraceCapture.TYPE.equals(event.optString("type")) && event.optInt("traceVersion") == 1 && event.optBoolean("evidenceOnly")
                && event.optString("callId").matches("[a-fA-F0-9-]{36}") && Set.of("native_runner", "native_coordinator").contains(event.optString("source"))
                && Set.of("started", "finished").contains(event.optString("stage"));
    }
    private static boolean sameTool(JSONObject first, JSONObject second) {
        for (String key : new String[]{"callId", "taskId", "runId", "parentId", "parentCallId", "providerToolCallId", "tool", "memberId", "turnId"})
            if (!first.optString(key).equals(second.optString(key))) return false;
        return first.optInt("coordinatorEpoch") == second.optInt("coordinatorEpoch") && first.optLong("startedAt") == second.optLong("startedAt");
    }
}
