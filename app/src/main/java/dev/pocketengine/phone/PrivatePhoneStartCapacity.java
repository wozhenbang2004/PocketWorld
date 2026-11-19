package dev.pocketengine.phone;

import dev.pocketengine.core.ExecutionResources.Availability;
import org.json.JSONException;
import org.json.JSONObject;

/** Point-in-time evidence for a private start review, not a lease, task or permission. */
public final class PrivatePhoneStartCapacity {
    private PrivatePhoneStartCapacity() {}

    public static JSONObject read(String mode, boolean mainController, int retainedBackground, Availability leases) throws JSONException {
        JSONObject view = new JSONObject().put("mode", mode);
        if (!("background".equals(mode) || "main_screen".equals(mode)) || leases == null ||
                leases.backgroundLimit() <= 0 || leases.backgroundUsed() < 0 || retainedBackground < 0)
            return result(view, "unknown", "unavailable", "暂不能核实本机执行位置，请稍后重新核对。");
        if (mainController || leases.mainScreenBusy())
            return result(view, "blocked", "main_busy", "本机主执行通道正在被占用；不会抢占原任务。");
        if (leases.browserBusy())
            return result(view, "blocked", "browser_busy", "浏览器正在占用执行通道；请等它释放后重新核对。");
        if ("main_screen".equals(mode) && (retainedBackground > 0 || leases.backgroundUsed() > 0))
            return result(view, "blocked", "background_busy", "已有后台独立屏占用执行通道。主屏须等它们全部释放。");
        if (retainedBackground != leases.backgroundUsed() || leases.backgroundUsed() > leases.backgroundLimit())
            return result(view, "unknown", "changing", "本机执行位置正在交接，当前空位尚不能核实。");
        view.put("backgroundUsed", leases.backgroundUsed()).put("backgroundLimit", leases.backgroundLimit());
        if ("background".equals(mode)) {
            if (leases.backgroundUsed() == leases.backgroundLimit())
                return result(view, "blocked", "full", "后台独立屏已全部占用，请等空位释放后重新核对。");
            return result(view, "ready", "available", "核对时已有 " + leases.backgroundUsed() + " / " +
                    leases.backgroundLimit() + " 个独立屏被占用。提交时再核对，不预留空位。");
        }
        return result(view, "ready", "available", "核对时主屏执行通道空闲。提交时再核对，不预留主屏。");
    }

    private static JSONObject result(JSONObject view, String state, String reason, String message) throws JSONException {
        return view.put("state", state).put("reason", reason).put("message", message);
    }

    public static boolean unchangedReady(String mode, JSONObject shown, JSONObject now) {
        if (shown == null || now == null || !("background".equals(mode) || "main_screen".equals(mode))) return false;
        int used = shown.optInt("backgroundUsed", -1), limit = shown.optInt("backgroundLimit", -1);
        return mode.equals(shown.optString("mode")) && mode.equals(now.optString("mode")) &&
                "ready".equals(shown.optString("state")) && "ready".equals(now.optString("state")) &&
                "available".equals(shown.optString("reason")) && "available".equals(now.optString("reason")) &&
                used >= 0 && used < limit && used == now.optInt("backgroundUsed", -1) && limit == now.optInt("backgroundLimit", -1);
    }
}
