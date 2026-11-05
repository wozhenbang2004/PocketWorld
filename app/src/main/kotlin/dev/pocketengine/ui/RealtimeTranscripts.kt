package dev.pocketengine.ui

import org.json.JSONObject

internal fun realtimeReviewCurrent(realtime: JSONObject, review: JSONObject): Boolean {
    if (review.optString("sessionId") != realtime.optString("sessionId") || review.optLong("expiresAt") <= System.currentTimeMillis()) return false
    val transcript = realtime.optJSONObject("transcription") ?: return false
    if ((!transcript.optBoolean("active") && !transcript.optBoolean("retained")) || !transcript.optBoolean("commitAvailable")) return false
    if (transcript.has("reviewReady") && !transcript.optBoolean("reviewReady")) return false
    val rows = transcript.optJSONArray("turns") ?: return false
    for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        if (row.optString("id") == review.optString("turnId")) return row.optBoolean("canReview") && row.optBoolean("isFinal") && row.optLong("revision") == review.optLong("revision") && row.optString("text") == review.optString("text")
    }
    return false
}

internal fun realtimeTurnLabel(turn: JSONObject): String = when (turn.optString("commitState")) {
    "committing" -> "正在保存已确认的任务，不是执行任务"
    "committed" -> "已保存到任务"
    "unknown" -> "保存结果未确认，请只核对原提交"
    else -> when {
        turn.optString("phase") == "incomplete" -> "未完整接收，不能提交"
        !turn.optBoolean("isFinal") -> "临时转录，不会执行"
        turn.optString("sourceKind") != "reported_local_microphone" -> "远端最终字幕，只供查看"
        else -> "最终转录，仍需你明确复核"
    }
}
