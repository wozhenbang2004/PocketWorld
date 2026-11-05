package dev.pocketengine.ui

import org.json.JSONObject

internal fun deviceConnectionDetail(peer: JSONObject): String {
    val connection = peer.optJSONObject("connection") ?: return "连接状态暂不可用。"
    return when (connection.optString("state")) {
        "ONLINE", "DEGRADED" -> when (connection.optString("path")) {
            "RELAY_WSS" -> "当前通过出站 WSS 中继连接。任务仍按各自的授权范围执行。"
            "DIRECT_TLS" -> "当前通过直连 TLS 通道连接。任务仍按各自的授权范围执行。"
            else -> "已建立加密连接，传输路径尚未核对。任务仍按各自的授权范围执行。"
        }
        "CONNECTING" -> "仍在连接或验证身份，尚未确认可以发出业务请求。"
        "INCOMPATIBLE" -> "原生传输报告协议不兼容，请核对两端版本后再连接。"
        "REVOKED" -> "原连接不再受信任，重新启用接收不会恢复旧授权。"
        else -> "设备离线，已接受的任务可能仍在进行。"
    }
}
