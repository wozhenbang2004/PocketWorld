package dev.pocketengine.phone;

import org.json.JSONObject;

/** A refreshed question may decorate an existing stop notification, never choose its target. */
public final class OriginalPhoneNotice {
    private OriginalPhoneNotice() {}

    public static boolean sameAwaiting(JSONObject original, JSONObject refreshed) {
        if (original == null || refreshed == null || !"phone".equals(original.opt("kind")) ||
                !"awaiting_user".equals(original.opt("status")) || !"awaiting_user".equals(refreshed.opt("status")) ||
                refreshed.optBoolean("taskCoreReadLimited") || refreshed.optBoolean("sourceCurrentReadRequired") ||
                "source_unavailable".equals(refreshed.opt("taskProjection"))) return false;
        for (String key : new String[]{"id", "runId", "actorId", "spaceId", "executionMode", "kind"}) {
            Object before = original.opt(key);
            if (!(before instanceof String value) || value.isBlank() || !value.equals(refreshed.opt(key))) return false;
        }
        int epoch = epoch(original);
        if (epoch < 1 || epoch != epoch(refreshed)) return false;
        JSONObject before = original.optJSONObject("origin"), after = refreshed.optJSONObject("origin");
        if (before == null || after == null) return original.isNull("origin") && refreshed.isNull("origin");
        return !before.optString("kind").isBlank() && before.toString().equals(after.toString());
    }

    private static int epoch(JSONObject row) {
        Object raw = row.opt("coordinatorEpoch");
        if (!(raw instanceof Integer || raw instanceof Long)) return -1;
        long value = ((Number)raw).longValue();
        return value > 0 && value <= Integer.MAX_VALUE ? (int)value : -1;
    }

    /** Deliberately no old goal, summary, question, reply token, screen or resume data. */
    public static JSONObject readingOnly(JSONObject original) {
        JSONObject safe = new JSONObject();
        try {
            for (String key : new String[]{"id","runId","coordinatorEpoch","kind","status","executionMode","actorId","spaceId"})
                if (original.has(key)) safe.put(key, original.get(key));
            JSONObject origin = original.optJSONObject("origin");
            if (origin != null) safe.put("origin", new JSONObject().put("kind", origin.optString("kind")));
            safe.put("goal", "原手机任务").put("summary", "无法核实这次等待的原问题。打开原轮记录，不会转到其他执行。");
        } catch (org.json.JSONException unavailable) {
            // No mutable argument or guessed identity can become an actionable notification.
            return new JSONObject();
        }
        return safe;
    }
}
