package dev.pocketengine.ui

import org.json.JSONObject
import kotlin.math.min
import kotlin.math.roundToInt

internal data class FramePoint(val x: Int, val y: Int)

/** Fit the image into its viewport; letterbox taps never become device coordinates. */

internal fun mapFramePoint(x: Float, y: Float, viewportWidth: Int, viewportHeight: Int, imageWidth: Int, imageHeight: Int): FramePoint? {
    if (viewportWidth <= 0 || viewportHeight <= 0 || imageWidth <= 0 || imageHeight <= 0 || !x.isFinite() || !y.isFinite()) return null
    val scale = min(viewportWidth.toDouble() / imageWidth, viewportHeight.toDouble() / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (viewportWidth - width) / 2
    val top = (viewportHeight - height) / 2
    if (x < left || y < top || x > left + width || y > top + height) return null
    return FramePoint(((x - left) / width * 1000).roundToInt().coerceIn(0, 1000), ((y - top) / height * 1000).roundToInt().coerceIn(0, 1000))
}

/** Visible state is only a projection. The original host still checks task/run/display/session and journals every input. */

internal data class PhoneInspectionPresentation(val location: String, val frameFresh: Boolean, val manual: Boolean, val canTakeOver: Boolean, val message: String)

internal fun phoneInspectionPresentation(task: UiTask?, observation: JSONObject?, allowed: Boolean, busy: Boolean, unresolved: Boolean, activeTaskId: String?, nowUptime: Long): PhoneInspectionPresentation {
    val background = task?.executionMode == "background"
    val handoffState = observation?.optJSONObject("ownerHandoff")?.optString("state")
    val location = when (handoffState) { "main" -> "主屏 · 主人接管（助手暂停）"; "unknown" -> "应用位置待核对"; else -> when (task?.executionMode) { "background" -> "后台独立屏"; "main_screen" -> "手机主屏"; else -> "执行位置待核对" } }
    val at = observation?.optLong("observedAtUptimeMs", -1) ?: -1
    val age = nowUptime - at
    val bound = handoffState !in setOf("main", "unknown") && background && observation != null && observation.optBoolean("ok", true) && observation.optString("observationId").isNotBlank() && observation.optInt("displayId", -1) > 0 && observation.optString("sessionId").isNotBlank() && observation.optString("executionMode") == "background"
    val fresh = bound && at >= 0 && age in 0..120_000
    val manual = fresh && allowed && !busy && !unresolved && activeTaskId == task?.id && task?.status in setOf("paused", "awaiting_user")
    val canTakeOver = handoffState !in setOf("main", "unknown") && background && !busy && !unresolved && (activeTaskId == null || activeTaskId == task?.id) && task?.status in setOf("preparing", "running", "pausing", "paused", "awaiting_user", "interrupted", "needs_confirmation")
    val message = when {
        handoffState == "main" -> "应用由主人在主屏操作 · 助手未读取或控制主屏"
        handoffState == "unknown" -> "移屏结果待核对 · 不重发，不继续助手"
        unresolved -> "动作结果待核对 · 先不要重复操作"
        busy -> "本次请求处理中 · 旧快照仅供查看"
        !background -> if (task?.executionMode == "main_screen") "此任务使用主屏，请直接在手机处理；未切到独立屏" else "请回原任务核对执行位置"
        !bound -> "尚未取得可核对的原画面 · 不可点按"
        !fresh -> "原快照已过期或时间不可核对 · 请刷新"
        manual -> "已暂停助手 · 你可操作这份原画面"
        else -> "最近观察快照 · 仅查看，不是实时视频"
    }
    return PhoneInspectionPresentation(location, fresh, manual, canTakeOver, message)
}
