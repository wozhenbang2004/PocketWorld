package dev.pocketengine.store;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reading projection over source-checked native work. It owns no execution or result receipt. */
final class OwnerWorkDirectory {
    private OwnerWorkDirectory() {}

    static String normalizeQuery(String query) {
        if (query == null || query.length() > 500 || query.indexOf('\0') >= 0)
            throw new IllegalArgumentException("工作查找内容须为不超过500字的文字");
        return normalize(query);
    }

    static boolean matches(JSONObject work, String normalized) {
        if (normalized.isEmpty()) return true;
        if (!Boolean.TRUE.equals(work.opt("contextAvailable"))) return false;
        // The native contract keeps the full goal when a bounded UI header shortens
        // its preview. It is covered by the same source check, never a result body.
        JSONObject metadata = OwnerWorkTask.isOwnerWork(work) ? work.optJSONObject("ownerWork") : null;
        JSONObject contract = metadata == null ? work.optJSONObject("contract") : metadata.optJSONObject("contract");
        String goal = contract != null && contract.opt("goal") instanceof String ? contract.optString("goal") : work.optString("goal");
        return normalize(work.optString("title") + "\n" + goal).contains(normalized);
    }

    static boolean matchesFilter(JSONObject row, String filter) {
        return "all".equals(filter) || "attention".equals(filter) && row.optBoolean("needsAttention")
                || "active".equals(filter) && row.optBoolean("active")
                || "open".equals(filter) && (row.optBoolean("needsAttention") || row.optBoolean("active"));
    }

    static void projectState(JSONObject row, JSONObject work, boolean unresolved) throws Exception {
        String status = row.getString("status");
        boolean available = Boolean.TRUE.equals(row.opt("contextAvailable"));
        boolean cancelled = row.optBoolean("cancelRequested") || "cancelled".equals(status);
        JSONObject metadata = OwnerWorkTask.isOwnerWork(work) ? work.optJSONObject("ownerWork") : null;
        JSONObject handling = metadata == null ? work.optJSONObject("handling") : metadata.optJSONObject("handling");
        String handled = handling == null ? "none" : handling.optString("state");
        if (!Set.of("none", "pending", "running", "reported", "needs_review", "unavailable").contains(handled)) handled = "unknown";
        Object deadline = handling == null ? null : handling.opt("deadlineAt");
        boolean deadlineKnown = (deadline instanceof Integer || deadline instanceof Long) && ((Number) deadline).longValue() >= 0;
        boolean handlingInProgress = "pending".equals(handled) || "running".equals(handled);
        boolean handlingUncertain = handlingInProgress && !deadlineKnown;
        boolean handlingExpired = handlingInProgress && deadlineKnown && ((Number) deadline).longValue() > 0
                && ((Number) deadline).longValue() <= System.currentTimeMillis();
        if (handlingUncertain) handled = "unknown";
        else if (handlingExpired) handled = "unavailable";
        JSONArray children = metadata == null ? null : metadata.optJSONArray("relatedPendingChildren");
        if (children == null && handling != null) children = handling.optJSONArray("pendingChildren");
        int pending = children == null ? 0 : children.length();
        boolean relatedUnavailable = metadata != null && metadata.optBoolean("relatedSourcesUnavailable");
        boolean childAttention = false;
        for (int i = 0; children != null && i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            if (child == null || child.optBoolean("hasUnresolvedOperations")
                    || child.optString("status").matches("awaiting_user|waiting_user|waiting_approval|awaiting_approval|waiting_permission|needs_confirmation|reconciling|interrupted|paused|pausing|failed")
                    || child.optString("executionStatus").matches("unknown|not_found|awaiting_approval|awaiting_input|paused|blocked")
                    || "native_result_source_unavailable".equals(child.optString("reason"))) childAttention = true;
        }
        boolean handlingAttention = available && (relatedUnavailable || childAttention || cancelled && pending > 0
                || !cancelled && ("needs_review".equals(handled) || "unavailable".equals(handled) || "unknown".equals(handled)));
        boolean attention = unresolved || status.matches("awaiting_user|waiting_user|waiting_approval|awaiting_approval|waiting_permission|needs_confirmation|reconciling|interrupted|paused|pausing|failed") || handlingAttention;
        // A terminal model turn can leave exact child work or result handling alive.
        // Unknown effects, withdrawn sources and cancellation never infer automatic progress.
        boolean continuing = available && !cancelled && !unresolved && !relatedUnavailable && !childAttention
                && (pending > 0 || status.matches("completed|failed") && ("pending".equals(handled) || "running".equals(handled)));
        row.put("pendingChildCount", available ? pending : JSONObject.NULL)
                .put("relatedStateUnavailable", relatedUnavailable || !available)
                .put("handlingState", available ? handled : "unavailable")
                .put("handlingRequiresOriginalRead", available && handlingUncertain)
                .put("needsAttention", attention)
                .put("active", status.matches("queued|preparing|running|stopping") || continuing)
                .put("hasUnresolvedOperations", unresolved).put("executionAuthorized", false);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }
}
