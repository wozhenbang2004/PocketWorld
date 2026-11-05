package dev.pocketengine.ui

import org.json.JSONObject

internal fun voiceTurnCanStart(realtime: JSONObject, roomBusy: Boolean): Boolean {
    val turn = realtime.optJSONObject("voiceTurn") ?: return false
    return !roomBusy && !turn.optBoolean("active") && !turn.optBoolean("resourceReleaseUnknown") && !realtime.optBoolean("audioLeaseHeld") && realtime.optBoolean("foregroundOwner") && realtime.optBoolean("captureCoordinatorReady") && realtime.optBoolean("microphonePermission") && turn.optBoolean("portReady") && !(realtime.optJSONObject("session")?.optBoolean("resourceReleaseUnknown") ?: false)
}

internal fun voiceTurnLabel(turn: JSONObject): String = when (turn.optString("status", "idle")) {
    "acquiring" -> "正在准备这一轮"
    "listening" -> "正在听这一句"
    "thinking" -> "正在等待模型回复"
    "speaking" -> "正在朗读回复"
    "stopping" -> "正在停止并释放音频"
    "complete" -> "这一轮已结束 · 麦克风已停止"
    "cancelled" -> "这一轮已停止"
    "failed" -> "这一轮未完成"
    else -> "尚未开始，不在录音"
}
