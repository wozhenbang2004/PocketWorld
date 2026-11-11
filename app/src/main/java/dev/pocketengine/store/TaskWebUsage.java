package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.CancellationSignal;
import android.os.SystemClock;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.web.WebSourceLedger;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Base64;
import org.json.JSONObject;

/** Date-bounded read of committed web receipts. Does not maintain a second usage ledger. */
final class TaskWebUsage {
    private TaskWebUsage() {}
    private static final String[] METRICS = {"searchRequests", "fetchRequests", "pagesRead", "sourceRefs", "hostedModelRequests", "hostedInputTokens", "hostedOutputTokens", "failedCalls"};

    static JSONObject read(SQLiteDatabase db, long from, long to, String zone, String cursor, RunControl control) throws Exception {
        if (from < 0 || to <= from || to - from > 370L * 86400000 || zone == null || zone.length() > 100 || cursor == null || cursor.length() > 4096 || control == null || control.hasHostRun())
            throw new IllegalArgumentException("联网用量日期范围无效");
        ZoneId.of(zone);
        long last = 0, upper;
        if (cursor.isEmpty()) {
            try (Cursor row = db.rawQuery("SELECT COALESCE(MAX(rowid),0) FROM task_events", null)) { row.moveToFirst(); upper = row.getLong(0); }
        } else {
            JSONObject saved = decode(cursor);
            if (saved.optInt("v") != 1 || saved.optLong("from") != from || saved.optLong("to") != to || !zone.equals(saved.optString("zone")))
                throw new IllegalArgumentException("联网用量日期已变化，请重新读取");
            last = saved.getLong("last"); upper = saved.getLong("upper");
            if (last < 0 || upper < last) throw new IllegalArgumentException("联网用量读取位置无效");
        }
        JSONObject counts = new JSONObject(); for (String metric : METRICS) counts.put(metric, 0L);
        int scanned = 0, receipts = 0, unavailable = 0, unknownUsage = 0, unknownOutcome = 0;
        long bytes = 0, started = SystemClock.elapsedRealtime(); boolean more = false;
        CancellationSignal signal = new CancellationSignal(); Runnable cancel = signal::cancel; control.onCancel(cancel);
        String sql = "SELECT e.rowid,e.body_bytes,CASE WHEN e.body_bytes BETWEEN 0 AND 262144 AND length(CAST(e.event_json AS BLOB))<=262144 THEN e.event_json END,CASE WHEN length(CAST(t.ui_header AS BLOB))<=32768 THEN t.ui_header END " +
                "FROM task_events e JOIN tasks t ON t.task_id=e.task_id WHERE e.event_type=? AND e.rowid>? AND e.rowid<=? ORDER BY e.rowid LIMIT 201";
        try (Cursor rows = db.rawQuery(sql, new String[]{WebSourceLedger.EVENT, Long.toString(last), Long.toString(upper)}, signal)) {
            while (rows.moveToNext()) {
                control.check();
                if (scanned >= 200 || bytes >= 4L * 1024 * 1024 || SystemClock.elapsedRealtime() - started >= 500) { more = true; break; }
                scanned++; last = rows.getLong(0);
                if (rows.isNull(2) || rows.isNull(3)) { unavailable++; continue; }
                bytes += Math.max(0, rows.getLong(1));
                JSONObject task = new JSONObject(rows.getString(3));
                if (!"owner".equals(task.optString("actorId")) || !"private".equals(task.optString("spaceId"))) continue;
                JSONObject event = new JSONObject(rows.getString(2));
                long at = event.optLong("observedAt", -1);
                if (at < 0) { unavailable++; continue; }
                if (at < from || at >= to) continue;
                JSONObject value = event.optJSONObject("counters");
                if (value == null) { unavailable++; continue; }
                receipts++;
                for (String metric : METRICS) {
                    String nativeKey = metric.equals("fetchRequests") ? "readRequests" : metric;
                    long amount = value.optLong(nativeKey, 0);
                    if (metric.equals("sourceRefs")) amount = event.optJSONArray("sources") == null ? 0 : event.getJSONArray("sources").length();
                    counts.put(metric, Math.addExact(counts.getLong(metric), Math.max(0, amount)));
                }
                if (value.optLong("hostedModelRequests") > 0 && !value.optBoolean("usageWasProviderReported")) unknownUsage++;
                if (!value.has("failedCalls")) unknownOutcome++;
            }
        } finally { control.removeCancelAction(cancel); }
        control.check();
        String next = more ? encode(new JSONObject().put("v", 1).put("from", from).put("to", to).put("zone", zone).put("last", last).put("upper", upper)) : "";
        return new JSONObject().put("metrics", counts).put("nextCursor", next).put("hasMore", more)
                .put("fromInclusive", from).put("toExclusive", to).put("zoneId", zone).put("observedAt", System.currentTimeMillis())
                .put("receipts", receipts).put("unavailableReceipts", unavailable).put("unknownProviderUsage", unknownUsage).put("unknownOutcomes", unknownOutcome)
                .put("rowsScanned", scanned).put("bodyBytesRead", bytes).put("scope", "saved_private_work_receipts")
                .put("providerFees", JSONObject.NULL).put("executionAuthorized", false);
    }

    private static String encode(JSONObject value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8)); }
    private static JSONObject decode(String value) throws Exception {
        try { return new JSONObject(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)); }
        catch (Exception failure) { throw new IllegalArgumentException("联网用量位置不可读，请重新读取", failure); }
    }
}
