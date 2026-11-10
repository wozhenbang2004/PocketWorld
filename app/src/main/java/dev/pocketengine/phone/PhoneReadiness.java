package dev.pocketengine.phone;

import org.json.JSONArray;
import org.json.JSONObject;

/** Readiness from native facts only. Inspecting it never grants permission, captures a screen or starts a task. */
public final class PhoneReadiness {
    public static final String TASK_CHANNEL = "phone_execution";
    private PhoneReadiness() { }

    public static JSONObject evaluate(JSONObject facts) throws Exception {
        JSONObject main = group(facts, new String[][]{
                {"serviceConnected"},
                {"windowContent", "window_content_permission_missing", "accessibility", "请允许系统界面节点读取"},
                {"interactiveWindows", "interactive_windows_permission_missing", "accessibility", "请恢复窗口读取权限"},
                {"deviceUnlocked", "screen_locked", "unlock", "请手动解锁手机"},
                {"screenInteractive", "screen_not_interactive", "wake_screen", "请保持屏幕亮起"},
                {"mainDisplayPresent", "main_display_unavailable", "retry", "手机主屏暂不可读取"}
        });
        JSONObject screenshot = copy(main);
        add(screenshot, check(facts, "screenshotCapability", "screenshot_permission_missing", "accessibility", "请允许系统截图读取"));
        JSONObject preparation = copy(main);
        add(preparation, check(facts, "gestureCapability", "gesture_permission_missing", "accessibility", "请恢复手机操作权限"));
        JSONObject execution = copy(preparation);
        JSONObject notifications = notifications(facts, false), notificationStart = notifications(facts, true);
        JSONObject stop = alternative(facts, "ownerControlsVisible", notifications);
        add(execution, new JSONObject().put("id", "stopControl").put("state", stop.getString("state"))
                .put("code", "ready".equals(stop.getString("state")) ? "" : "stop_control_unavailable")
                .put("action", "notifications").put("message", "ready".equals(stop.getString("state")) ? "" : "请开启任务通知或恢复原屏停止控件"));
        JSONObject background = group(facts, new String[][]{
                {"backgroundSupported", "background_android_unsupported", "main_screen_choice", "独立后台屏需要 Android 14 或以上"},
                {"backgroundConnected", "background_helper_unavailable", "background_setup", "请连接独立后台助手"},
                {"serviceConnected"},
                {"windowContent", "window_content_permission_missing", "accessibility", "请允许系统界面节点读取"},
                {"interactiveWindows", "interactive_windows_permission_missing", "accessibility", "请恢复窗口读取权限"},
                {"deviceUnlocked", "screen_locked", "unlock", "请手动解锁手机"},
                {"screenInteractive", "screen_not_interactive", "wake_screen", "请保持屏幕亮起"}
        });
        add(background, check(facts, "backgroundClean", "background_reconciliation_required", "background_setup", "原后台会话释放或连接结果待核对"));
        JSONObject backgroundExecution = copy(background);
        append(background, notificationStart);
        append(backgroundExecution, notifications);
        add(backgroundExecution, check(facts, "backgroundSessionReady", "background_session_not_ready", "background_setup", "独立后台屏尚未准备完成"));
        return new JSONObject().put("observedOnly", true).put("deviceVerified", false)
                .put("checkedAtUptimeMs", facts.optLong("checkedAtUptimeMs", -1))
                .put("mainServiceSessionId", facts.opt("mainServiceSessionId") == null ? JSONObject.NULL : facts.opt("mainServiceSessionId"))
                .put("mainDisplayId", 0).put("mainObservation", main).put("mainScreenshot", screenshot)
                .put("mainPreparation", preparation).put("mainExecution", execution).put("taskNotifications", notifications).put("taskNotificationsForStart", notificationStart)
                .put("stopControl", stop).put("backgroundPreparation", background).put("backgroundExecution", backgroundExecution)
                .put("backgroundInput",group(facts,new String[][]{{"backgroundInput","background_input_unavailable","background_setup","后台输入注入尚未证实可用"}}))
                .put("backgroundCapture",group(facts,new String[][]{{"backgroundCapture","background_capture_unavailable","background_setup","后台真实取帧尚未证实可用"}}))
                .put("backgroundIme",group(facts,new String[][]{{"backgroundIme","background_ime_unavailable","background_setup","独立输入法隔离尚未证实可用；不会使用主屏输入法回落"}}))
                .put("backgroundSessionId", facts.opt("backgroundSessionId") == null ? JSONObject.NULL : facts.opt("backgroundSessionId"))
                .put("backgroundDisplayId", facts.optInt("backgroundDisplayId", -1)).put("mainScreenFallback", false);
    }

    /** Fresh native admission check; task-owned controls and background sessions are created after preparation. */
    public static void requireStart(JSONObject readiness, String mode) throws Exception {
        String group;
        if ("main_screen".equals(mode)) group = "mainPreparation";
        else if ("background".equals(mode)) group = "backgroundPreparation";
        else throw new IllegalArgumentException("请选择主屏或独立后台屏模式");
        JSONObject preparation = readiness == null ? null : readiness.optJSONObject(group);
        if (preparation != null && preparation.optBoolean("ready") && "ready".equals(preparation.optString("state"))) return;
        JSONArray checks = preparation == null ? null : preparation.optJSONArray("checks");
        if (checks != null) for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            if (!"ready".equals(check.optString("state"))) {
                String message = check.optString("message");
                if (!message.isEmpty()) throw new IllegalStateException(message);
            }
        }
        throw new IllegalStateException("手机操作条件暂不可核对，请重新检查");
    }

    private static JSONObject notifications(JSONObject facts, boolean beforeStart) throws Exception {
        JSONObject out = group(facts, new String[][]{
                {"notificationsAllowed", "notifications_blocked", "notifications", "请允许任务通知，以便随时停止"},
                {"notificationPermission", "notification_permission_missing", "notifications", "请允许系统通知权限"},
                {"notificationsNotPaused", "notifications_paused", "notifications", "系统暂时隐藏了任务通知"}
        });
        String channel = facts.optString("taskChannelState", "unknown");
        String state = "enabled".equals(channel) || beforeStart && "not_created".equals(channel) ? "ready"
                : "disabled".equals(channel) || "not_created".equals(channel) ? "blocked" : "unknown";
        add(out, new JSONObject().put("id", "taskChannel").put("state", state)
                .put("code", "ready".equals(state) ? "" : "not_created".equals(channel) ? "task_channel_not_created" : "task_channel_blocked")
                .put("action", "notifications").put("message", "ready".equals(state) ? "" : "请开启手机任务通知类别"));
        return out;
    }
    private static JSONObject alternative(JSONObject facts, String key, JSONObject other) throws Exception {
        String first = state(facts.opt(key));
        String second = other.getString("state");
        String state = "ready".equals(first) || "ready".equals(second) ? "ready"
                : "unknown".equals(first) || "unknown".equals(second) ? "unknown" : "blocked";
        return new JSONObject().put("state", state).put("ready", "ready".equals(state))
                .put("nativeControlsVisible", facts.has(key) ? facts.opt(key) : JSONObject.NULL)
                .put("taskNotificationsEnabled", other.getBoolean("ready"));
    }
    private static JSONObject group(JSONObject facts, String[][] requirements) throws Exception {
        JSONObject out = new JSONObject().put("checks", new JSONArray()).put("ready", true).put("state", "ready");
        for (String[] row : requirements) add(out, "serviceConnected".equals(row[0]) ? serviceConnection(facts) : check(facts, row[0], row[1], row[2], row[3]));
        return out;
    }
    private static JSONObject serviceConnection(JSONObject facts) throws Exception {
        Object enabled = facts.opt("serviceEnabled"), connected = facts.opt("serviceConnected");
        String state, code, action, message;
        // Revocation can precede the old service instance's disconnect callback.
        if (Boolean.FALSE.equals(enabled)) {
            state = "blocked"; code = "accessibility_disabled"; action = "accessibility";
            message = "请先在系统无障碍设置中开启“口袋世界 手机操作”服务";
        } else if (Boolean.TRUE.equals(connected)) {
            state = "ready"; code = ""; action = ""; message = "";
        } else if (Boolean.TRUE.equals(enabled) && Boolean.FALSE.equals(connected)) {
            state = "blocked"; code = "accessibility_not_connected"; action = "accessibility";
            message = "系统已开启手机操作无障碍服务，但服务尚未连接；请稍后重试，若仍未连接，请在系统无障碍设置中关闭后重新开启该服务";
        } else {
            state = "unknown"; code = "accessibility_state_unknown"; action = "accessibility";
            message = "手机操作无障碍服务状态暂不可核对，请检查系统无障碍设置后重试";
        }
        return new JSONObject().put("id", "serviceConnected").put("state", state).put("code", code).put("action", action).put("message", message);
    }
    private static JSONObject check(JSONObject facts, String key, String code, String action, String message) throws Exception {
        String state = state(facts.opt(key));
        return new JSONObject().put("id", key).put("state", state).put("code", "ready".equals(state) ? "" : code)
                .put("action", "ready".equals(state) ? "" : action)
                .put("message", "ready".equals(state) ? "" : "unknown".equals(state) ? "权限状态暂不可核对，请重新检查" : message);
    }
    private static String state(Object value) { return Boolean.TRUE.equals(value) ? "ready" : Boolean.FALSE.equals(value) ? "blocked" : "unknown"; }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static void append(JSONObject out, JSONObject other) throws Exception {
        JSONArray checks = other.getJSONArray("checks"); for (int i = 0; i < checks.length(); i++) add(out, copy(checks.getJSONObject(i)));
    }
    private static void add(JSONObject out, JSONObject check) throws Exception {
        out.getJSONArray("checks").put(check);
        String current = out.getString("state"), next = check.getString("state");
        if ("blocked".equals(next) || "unknown".equals(next) && "ready".equals(current)) out.put("state", next);
        out.put("ready", "ready".equals(out.getString("state")));
    }
}
