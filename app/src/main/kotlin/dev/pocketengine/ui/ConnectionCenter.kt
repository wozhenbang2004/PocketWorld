package dev.pocketengine.ui

import org.json.JSONObject

internal fun qqGatewayConnection(channels: JSONObject, profileId: String): JSONObject? = channels.optJSONObject("qq")?.optJSONArray("connections").objects().find { it.optString("profileId") == profileId }

internal fun qqGatewayCommand(channels: JSONObject, profile: JSONObject): String? {
    if (profile.optString("provider") != "qq" || channels.optJSONObject("capabilities")?.optBoolean("qqGateway") != true) return null
    if (qqGatewayConnection(channels, profile.optString("id"))?.opt("monitorRunning") == true) return "qq.stop"
    return if (profile.optBoolean("enabled")) "qq.start" else null
}

internal fun qqGatewayStatus(connection: JSONObject?): String = when {
    connection == null -> "尚未开始接收"
    connection.opt("monitorRunning") != true -> if (connection.optString("status") == "authorization_required") "需要核对平台凭据与权限" else "接收已停止"
    else -> when (connection.optString("status")) { "listening" -> "官方网关已认证，正在接收"; "starting" -> "正在连接，尚未确认在线"; "retry_wait" -> "连接中断，等待恢复原会话"; "authorization_required" -> "需要核对平台凭据与权限"; else -> "正在核对接收状态" }
}
