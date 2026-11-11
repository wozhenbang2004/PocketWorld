package dev.pocketengine.store;

import android.os.SystemClock;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner navigation over the original, source-filtered task headers. No new index or authority. */
final class TaskOwnerSearch {
    private TaskOwnerSearch() {}

    static JSONObject search(TaskStore store, SQLiteDatabase db, String query, String cursor, int limit, String filter) throws Exception {
        if (query == null || query.length() > 500 || query.indexOf('\0') >= 0 || cursor == null ||
                cursor.length() > 4096 || limit < 1 || limit > 40 || filter == null || !Set.of("all", "attention", "running", "ended").contains(filter))
            throw new IllegalArgumentException("工作查找范围无效");
        String normalized = normalize(query), signature = digest(normalized), position = "";
        if (!cursor.isEmpty()) {
            JSONObject saved = decode(cursor);
            if (saved.length() != 4 || saved.optInt("v") != 2 || !signature.equals(saved.optString("query")) || !filter.equals(saved.optString("filter")))
                throw new IllegalArgumentException("查询或筛选已变化，请重新查找工作");
            position = saved.getString("position");
            if (position.isEmpty()) throw new IllegalArgumentException("工作查找位置为空，请重新查找");
        }
        JSONArray matches = new JSONArray(), unavailableSources = new JSONArray();
        int scanned = 0;
        long started = SystemClock.elapsedRealtime();
        boolean more;
        do {
            JSONObject page = store.taskSummaryPage(position, Math.min(40, 200 - scanned));
            JSONArray tasks = page.getJSONArray("tasks");
            more = page.optBoolean("hasMore");
            scanned += tasks.length() + page.optInt("accountingRecordsExcluded");
            boolean stoppedWithinPage = false;
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.getJSONObject(i);
                if (!"owner".equals(task.optString("actorId")) || !"private".equals(task.optString("spaceId"))) continue;
                // A withdrawn source can stop matching the query or state. Let
                // the same read retire a stale live title without returning it.
                if ("source_unavailable".equals(task.optString("taskProjection")) || task.optBoolean("sourceCurrentReadRequired"))
                    unavailableSources.put(task.getString("id"));
                String visibleText = task.optString("goal") + "\n" + task.optString("summary");
                if (!normalized.isEmpty() && !normalize(visibleText).contains(normalized)) continue;
                boolean reconciliation = reconciliationRequired(db, task);
                String group = collectionGroup(task, reconciliation);
                if (!"all".equals(filter) && !filter.equals(group)) continue;
                task.put("workCollectionGroup", group).put("workCollectionReconciliation", reconciliation)
                        .put("workCollectionIdentity", new JSONObject().put("taskId", task.getString("id"))
                                .put("runId", task.optString("runId")).put("epoch", task.optLong("coordinatorEpoch"))
                                .put("updatedAt", task.getLong("updatedAt")).put("status", task.optString("status"))
                                .put("contextAvailable", !Boolean.FALSE.equals(task.opt("contextAvailable"))));
                matches.put(task);
                if (matches.length() == limit) {
                    // Resume at the last returned original header, not at the end of the
                    // larger page, so matches beyond this row remain reachable.
                    position = encode(new JSONObject().put("v", 1).put("at", task.getLong("updatedAt")).put("id", task.getString("id")));
                    more = more || i + 1 < tasks.length();
                    stoppedWithinPage = true;
                    break;
                }
            }
            if (!stoppedWithinPage) position = page.optString("nextCursor");
        } while (more && matches.length() < limit && scanned < 200 && SystemClock.elapsedRealtime() - started < 500);
        return new JSONObject().put("tasks", matches).put("auditBodyIncluded", false)
                .put("sourceUnavailableTaskIds", unavailableSources)
                .put("query", query).put("filter", filter).put("hasMore", more).put("rowsScanned", scanned)
                .put("nextCursor", more ? encode(new JSONObject().put("v", 2).put("query", signature).put("filter", filter).put("position", position)) : "")
                .put("coverage", "original_private_task_titles_and_summaries")
                .put("executionAuthorized", false);
    }

    /** Keep collection membership aligned with work-collection.ts. These are
     * source-filtered native facts; opening the task still owns every decision. */
    private static String collectionGroup(JSONObject task, boolean unresolved) {
        String status = task.optString("status");
        boolean ended = Set.of("completed", "failed", "cancelled", "canceled", "stopped").contains(status);
        boolean active = Set.of("queued", "preparing", "running", "verifying", "pausing", "stopping", "cancelling").contains(status);
        boolean available = !Boolean.FALSE.equals(task.opt("contextAvailable"));
        JSONObject work = "owner_work".equals(task.optString("executionRole")) ? task.optJSONObject("ownerWork") : null;
        JSONObject result = task.optJSONObject("resultHandling");
        if (Boolean.TRUE.equals(task.opt("nativeResultHandling")) && result != null
                && "pocketworld.owner-result-handling.v1".equals(result.optString("format"))
                && task.optString("id").equals(result.optString("taskId")) && !task.optString("runId").isEmpty()
                && task.optString("runId").equals(result.optString("runId"))
                && task.optLong("coordinatorEpoch") > 0 && task.optLong("coordinatorEpoch") == result.optLong("epoch")) work = result;
        JSONObject handling = work == null ? null : work.optJSONObject("handling");
        String handled = handling == null ? "" : handling.optString("state");
        boolean resultAvailable = work != null && Boolean.TRUE.equals(work.opt("resultAvailable"));
        JSONArray deliveries = work == null ? null : work.optJSONArray("deliveries");
        for (int i = 0; deliveries != null && i < deliveries.length(); i++) {
            JSONObject entry = deliveries.optJSONObject(i);
            if (entry != null && Boolean.TRUE.equals(entry.opt("available")) && entry.optString("noticeKind").isEmpty()) resultAvailable = true;
        }
        boolean handlingAttention = available && ("needs_review".equals(handled) || "unavailable".equals(handled) && resultAvailable
                && !task.optBoolean("cancelRequested") && !Set.of("cancelled", "canceled", "stopped").contains(status));
        boolean decision = Set.of("needs_confirmation", "awaiting_user", "waiting_user", "waiting_approval", "awaiting_approval",
                "waiting_permission", "awaiting_permission", "interrupted", "paused", "reconciling", "reconciliation_required", "unknown").contains(status);
        JSONObject controls = task.optJSONObject("controls");
        boolean resumableFailure = "failed".equals(status) && "owner_work".equals(task.optString("executionRole"))
                && controls != null && Boolean.TRUE.equals(controls.opt("canContinue"));
        boolean pendingApproval = false;
        JSONArray approvals = task.optJSONArray("approvals");
        for (int i = 0; !ended && approvals != null && i < approvals.length(); i++) {
            JSONObject approval = approvals.optJSONObject(i);
            if (approval != null && Set.of("pending", "waiting").contains(approval.optString("state", approval.optString("status")))) pendingApproval = true;
        }
        if (unresolved || decision || pendingApproval || resumableFailure || handlingAttention || !active && !ended) return "attention";
        if (active || available && Set.of("pending", "running").contains(handled)) return "running";
        return "ended";
    }

    /** The existing notification flag includes prepared calls. Only a prepared call
     * in this exact live run is ordinary progress; old-run or unknown calls remain
     * attention. This indexed existence read never materializes operation bodies. */
    private static boolean reconciliationRequired(SQLiteDatabase db, JSONObject task) {
        boolean unconfirmed = task.opt("historyHasUnconfirmedOperations") instanceof Boolean
                ? task.optBoolean("historyHasUnconfirmedOperations") : task.optBoolean("hasUnresolvedOperations");
        if (!unconfirmed) return false;
        if (!Set.of("preparing", "running", "verifying", "pausing", "stopping", "cancelling").contains(task.optString("status"))
                || task.optString("runId").isEmpty() || task.optLong("coordinatorEpoch") < 1) return true;
        try (Cursor row = db.rawQuery("SELECT 1 FROM operations o WHERE o.task_id=? AND o.state IN "
                + "('prepared','unknown','remote_job_prepared','remote_job_sending','remote_job_unknown','remote_approval_sending',"
                + "'remote_approval_unknown','remote_question_prepared','remote_question_sending','remote_question_unknown') "
                + "AND (o.state<>'prepared' OR o.run_id<>? OR NOT EXISTS "
                + "(SELECT 1 FROM runs r WHERE r.run_id=o.run_id AND r.task_id=o.task_id AND r.epoch=? AND r.status='running')) LIMIT 1",
                new String[]{task.optString("id"), task.optString("runId"), Long.toString(task.optLong("coordinatorEpoch"))})) {
            return row.moveToFirst();
        }
    }

    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim(); }
    private static String digest(String value) throws Exception { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static String encode(JSONObject value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8)); }
    private static JSONObject decode(String value) throws Exception {
        try { return new JSONObject(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)); }
        catch (Exception bad) { throw new IllegalArgumentException("工作查找位置已失效，请重新查找", bad); }
    }
}
