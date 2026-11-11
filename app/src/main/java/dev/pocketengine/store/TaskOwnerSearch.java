package dev.pocketengine.store;

import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Base64;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner navigation over the original, source-filtered task headers. No new index or authority. */
final class TaskOwnerSearch {
    private TaskOwnerSearch() {}

    static JSONObject search(TaskStore store, String query, String cursor, int limit) throws Exception {
        if (query == null || query.length() > 500 || query.indexOf('\0') >= 0 || cursor == null ||
                cursor.length() > 4096 || limit < 1 || limit > 40) throw new IllegalArgumentException("工作查找范围无效");
        String normalized = normalize(query), signature = digest(normalized), position = "";
        if (!cursor.isEmpty()) {
            JSONObject saved = decode(cursor);
            if (saved.optInt("v") != 1 || !signature.equals(saved.optString("query")))
                throw new IllegalArgumentException("查询已变化，请重新查找工作");
            position = saved.getString("position");
        }
        JSONArray matches = new JSONArray();
        int scanned = 0;
        long started = SystemClock.elapsedRealtime();
        boolean more;
        do {
            JSONObject page = store.taskSummaryPage(position, 40);
            JSONArray tasks = page.getJSONArray("tasks");
            more = page.optBoolean("hasMore");
            scanned += tasks.length() + page.optInt("accountingRecordsExcluded");
            boolean stoppedWithinPage = false;
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.getJSONObject(i);
                if (!"owner".equals(task.optString("actorId")) || !"private".equals(task.optString("spaceId"))) continue;
                String visibleText = task.optString("goal") + "\n" + task.optString("summary");
                if (!normalized.isEmpty() && !normalize(visibleText).contains(normalized)) continue;
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
                .put("hasMore", more).put("rowsScanned", scanned)
                .put("nextCursor", more ? encode(new JSONObject().put("v", 1).put("query", signature).put("position", position)) : "")
                .put("coverage", "original_private_task_titles_and_summaries")
                .put("executionAuthorized", false);
    }

    private static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim(); }
    private static String digest(String value) throws Exception { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static String encode(JSONObject value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8)); }
    private static JSONObject decode(String value) throws Exception {
        try { return new JSONObject(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)); }
        catch (Exception bad) { throw new IllegalArgumentException("工作查找位置已失效，请重新查找", bad); }
    }
}
