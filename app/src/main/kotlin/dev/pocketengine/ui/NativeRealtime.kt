package dev.pocketengine.ui

import org.json.JSONObject

internal fun videoOriginLabel(video: JSONObject): String = if (video.optString("origin") == "server_declared_agent") "远端 AI 服务视频（房间服务端标记，非真人验证）" else "远端视频 · 真人、AI 或录制来源未核验"

internal fun realtimeLabel(value: JSONObject): String {
    val session = value.optJSONObject("session") ?: JSONObject()
    if (value.optBoolean("accepting")) return if (value.optString("invitationState") in setOf("ended", "cancelled")) "正在释放已取消的连接" else "正在接受并连接，随时可取消"
    if (value.optString("invitationState") == "awaiting_owner") return "等待你接受或拒绝"
    if (session.optString("transport") == "openai_realtime_ga_websocket" && session.optString("state") == "connected") return "自然语音已连接 · 可以插话"
    return when (session.optString("state", "idle")) {
        "connecting" -> "正在加入实际房间"
        "connected" -> if (session.optBoolean("remoteAudioSubscribed") || (session.optJSONArray("remoteVideos")?.length() ?: 0) > 0) "媒体已订阅 · 远端身份见下方来源" else "已加入房间，等待对端媒体"
        "reconnecting" -> "网络中断，正在恢复当前通话"
        "disconnecting" -> "正在关闭媒体会话"
        "disconnected" -> "通话已断开"
        "failed" -> "通话未完成，请检查服务与权限"
        else -> if (value.optBoolean("naturalVoiceConfigured")) "自然语音服务已配置，尚未开始采集" else if (value.optBoolean("providerConfigured")) "服务已配置，尚未发起通话" else if (value.optBoolean("configured")) "尚未加入房间" else "尚未配置通话服务"
    }
}
