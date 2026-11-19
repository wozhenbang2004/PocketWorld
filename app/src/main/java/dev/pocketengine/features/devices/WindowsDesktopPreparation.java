package dev.pocketengine.features.devices;

import org.json.JSONObject;

final class WindowsDesktopPreparation {
    private WindowsDesktopPreparation() {}
    /** Viewing is not desktop.act: a held input lease or missing semantic driver
     * alone does not prove video is unavailable. This never probes a device. */
    static JSONObject from(String deviceId, String instanceId, JSONObject facts, boolean sameChannel) throws Exception {
        if (facts == null || !"DeviceDescription".equals(facts.opt("messageType"))
                || !Boolean.FALSE.equals(facts.opt("executionAuthorized")) || !deviceId.equals(facts.opt("deviceId"))
                || instanceId.isEmpty() || !instanceId.equals(facts.opt("instanceId"))) return null;
        JSONObject meta = facts.optJSONObject("metadata");
        if (meta == null || !deviceId.equals(meta.opt("deviceId")) || !instanceId.equals(meta.opt("instanceId"))
                || !"lightweight_companion".equals(meta.opt("kind")) || meta.optJSONObject("platform") == null
                || !"windows".equals(meta.getJSONObject("platform").opt("platform"))) return null;
        JSONObject desktop = meta.optJSONObject("desktop");
        JSONObject health = desktop == null ? null : desktop.optJSONObject("health");
        JSONObject media = desktop == null ? null : desktop.optJSONObject("streaming");
        String reason = desktop == null ? "" : desktop.optString("reason");
        String detail;
        boolean candidate = false;
        if (health != null && Boolean.FALSE.equals(health.opt("available"))) {
            detail = switch (health.optString("reason")) {
                case "locked_secure_or_inaccessible_desktop", "locked_or_secure_desktop" ->
                        "电脑无法读取普通桌面。请回到已解锁的 Windows 桌面；安全桌面不能共享。";
                case "session_0_or_unknown", "interactive_session_disconnected", "session_state_unknown" ->
                        "没有核实到正在使用的 Windows 桌面。请在已登录的交互会话中运行伴侣；SSH 终端不是桌面。";
                default -> "电脑没有核实到可观看的交互桌面。请在电脑上检查会话和主显示器。";
            };
        } else if (media != null && Boolean.TRUE.equals(media.opt("available"))) {
            candidate = true;
            detail = "电脑报告视频组件可用。连接后收到实际画面才算开始观看。";
        } else if ("desktop_explicitly_disabled".equals(reason)) {
            detail = "电脑上的伴侣未启用桌面。文件和命令连接不能代替桌面会话。";
        } else if ("prepared_stream_dependencies_missing".equals(media == null ? "" : media.optString("reason"))) {
            detail = "电脑上的伴侣缺少视频组件。请在电脑上核对现有伴侣；更新手机 APK 不会补齐它。";
        } else if (reason.startsWith("pinned_driver_") || reason.startsWith("pinned_agent_desktop_")) {
            detail = "电脑报告桌面驱动配置不完整。请在电脑上检查现有伴侣的安装和配置。";
        } else if (media == null) {
            detail = "这台伴侣没有报告可用的桌面视频入口。请在电脑上核对版本和桌面配置。";
        } else {
            detail = "电脑上的伴侣未报告视频可用。请在电脑上核对桌面与视频组件。";
        }
        JSONObject out = new JSONObject().put("capturedAt", facts.getLong("capturedAt")).put("validUntil", facts.getLong("validUntil"))
                .put("currentChannel", sameChannel).put("mediaCandidate", candidate).put("detail", detail);
        if (health != null && Boolean.TRUE.equals(health.opt("available")) && health.optLong("sessionId") > 0)
            out.put("sessionId", health.getLong("sessionId"));
        return out;
    }
}
