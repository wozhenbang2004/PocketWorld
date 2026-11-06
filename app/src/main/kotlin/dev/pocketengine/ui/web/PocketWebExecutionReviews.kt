package dev.pocketengine.ui.web

import android.os.SystemClock
import java.util.UUID
import org.json.JSONObject

/**
 * One-use reviews of the original owner execution policy and native window.
 * This presentation state never changes policy or replaces the controller's native admission.
 */
internal class PocketWebExecutionReviews(
    private val currentWindow: () -> Pair<Triple<Long, Int, Long>, Long>,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    data class Selection(val revision: Long, val enabled: Boolean)
    private data class Review(val selection: Selection, val expires: Long, val window: Pair<Triple<Long, Int, Long>, Long>)
    private val reviews = mutableMapOf<String, Review>()

    fun clear() = reviews.clear()

    fun create(policy: JSONObject, enabled: Boolean): JSONObject {
        val handle = UUID.randomUUID().toString()
        reviews.clear()
        reviews[handle] = Review(Selection(policy.getLong("revision"), enabled), now() + 120_000, currentWindow())
        return JSONObject(policy.toString()).put("reviewHandle", handle).put("nextEnabled", enabled)
    }

    fun consume(handle: String): Selection {
        val review = reviews.remove(handle) ?: error("原运行开关审阅已关闭或已使用")
        check(now() < review.expires) { "原运行开关审阅已过期" }
        check(currentWindow() == review.window) { "原运行开关窗口已变化，请重新核对" }
        return review.selection
    }
}
