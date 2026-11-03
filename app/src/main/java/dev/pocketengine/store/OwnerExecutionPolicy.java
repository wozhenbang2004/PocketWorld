package dev.pocketengine.store;

import org.json.JSONObject;

/** Durable owner stop policy stored only in the original TaskStore meta row. No task or grant. */
public final class OwnerExecutionPolicy {
    private OwnerExecutionPolicy() { }
    public static JSONObject initial() throws Exception {
        return new JSONObject().put("format", "pocketworld.owner-execution-policy.v1")
                .put("revision", 1L).put("enabled", true).put("changedAt", 0L)
                .put("resumeReplaysTasks", false).put("cleanupConfirmed", false);
    }
    public static JSONObject validate(JSONObject value) throws Exception {
        if (value == null || !"pocketworld.owner-execution-policy.v1".equals(value.optString("format"))
                || !(value.opt("enabled") instanceof Boolean) || !(value.opt("revision") instanceof Number)
                || !(value.opt("changedAt") instanceof Number)) throw new SecurityException("总停用状态不可核对，未准入执行");
        exact(value, "revision", 1); exact(value, "changedAt", 0);
        if (!Boolean.FALSE.equals(value.opt("resumeReplaysTasks"))) throw new SecurityException("总恢复不得自动重放任务");
        return new JSONObject(value.toString());
    }
    public static JSONObject change(JSONObject current, long expectedRevision, boolean enabled, long now) throws Exception {
        JSONObject next = validate(current);
        if (expectedRevision != next.getLong("revision")) throw new SecurityException("运行开关已变化，请重新核对");
        if (now < 0) throw new IllegalArgumentException("Invalid policy clock");
        if (next.getBoolean("enabled") == enabled) return next;
        return next.put("revision", Math.addExact(expectedRevision, 1L)).put("enabled", enabled)
                .put("changedAt", Math.max(now, next.getLong("changedAt")))
                .put("cleanupConfirmed", false).put("resumeReplaysTasks", false);
    }
    public static long requireEnabled(JSONObject current) throws Exception {
        JSONObject state = validate(current);
        if (!state.getBoolean("enabled")) throw new SecurityException("PocketWorld 已停用，请在本机设置明确恢复；没有启动任务");
        return state.getLong("revision");
    }
    public static void requireRevision(JSONObject current, long admittedRevision) throws Exception {
        if (requireEnabled(current) != admittedRevision) throw new SecurityException("总停用已撤销原执行准入；不会自动恢复旧任务");
    }
    private static void exact(JSONObject value, String key, long minimum) throws Exception {
        Object raw = value.get(key);
        if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() < minimum)
            throw new SecurityException("Invalid execution policy " + key);
    }
}
