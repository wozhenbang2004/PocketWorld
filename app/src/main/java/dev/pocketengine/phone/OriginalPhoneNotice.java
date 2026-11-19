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

    /** Presentation-only. A compact native header must not borrow the private identity of another
     * local/source/child run. Older full rows use the same original private-source predicate. */
    public static boolean privatePresentation(JSONObject original) {
        if (original == null || !"phone".equals(original.optString("kind"))) return false;
        Object marked = original.opt("originalNoticePrivateLocal");
        return marked instanceof Boolean flag ? flag : dev.pocketengine.store.PhoneActionRecovery.local(original);
    }

    /** Neither the detailed shade nor its public substitute receives the goal, summary, answer,
     * app name or pixels. A service record does not prove that an action reached the screen. */
    public static void presentPrivately(android.content.Context context, String channel, int icon,
            android.app.Notification.Builder builder, JSONObject original, boolean held, boolean cancelled, boolean unconfirmedWait, int ordinal) {
        String title = switch (original.optString("executionMode")) {
            case "main_screen" -> "本机主屏任务"; case "background" -> "本机独立屏任务"; default -> "本机手机任务";
        };
        if (ordinal > 0 && "background".equals(original.optString("executionMode"))) title += " · 通知 " + ordinal;
        String text;
        if (!held || unconfirmedWait) text = "点开原任务核对当前状态。";
        else if (cancelled || original.optBoolean("cancelRequested")) text = "已请求停手；在途操作及清理情况请在原任务核对。";
        else text = switch (original.optString("status")) {
            case "preparing" -> "本轮正在准备，尚不代表已取得屏幕。";
            case "running" -> "原任务已进入执行阶段。点开可核对实际进展。";
            case "pausing" -> "已请求暂停，等待原执行确认。";
            case "paused" -> "原任务记录为已暂停。";
            case "awaiting_user" -> "这轮在等你。点开原任务查看需要处理的内容。";
            case "reconciling", "needs_confirmation" -> "原操作或结果需要你核对。";
            case "completed" -> "本轮已收尾，结果在原任务中。";
            case "stopped", "cancelled" -> "原记录已标记停止，清理情况请在任务中核对。";
            case "interrupted", "failed" -> "这轮没有完成。点开可核对最后的记录。";
            default -> "点开原任务核对当前状态。";
        };
        android.app.Notification cover = new android.app.Notification.Builder(context,channel).setSmallIcon(icon)
                .setContentTitle("PocketWorld 手机任务").setContentText("详情留在本机原任务中。")
                .setLocalOnly(true).setShowWhen(false).build();
        builder.setContentTitle(title).setContentText(text).setLocalOnly(true)
                .setVisibility(android.app.Notification.VISIBILITY_PRIVATE).setPublicVersion(cover);
        if (android.os.Build.VERSION.SDK_INT >= 29) builder.setAllowSystemGeneratedContextualActions(false);
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
