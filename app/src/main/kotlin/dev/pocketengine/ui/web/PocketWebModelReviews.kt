package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.runtime.models.ModelBinding
import dev.pocketengine.runtime.models.ReasoningOptions
import dev.pocketengine.ui.objects
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Frozen model choices, not native write admission. The bridge retains callback and window fences. */
internal class PocketWebModelReviews {
    data class Review(
        val binding: ModelBinding,
        val model: String,
        val identity: String,
        val output: Int,
        val reasoning: ReasoningOptions,
        val imageReserve: Int,
        val generation: Long,
        val document: String?,
        val expires: Long,
        val selectionRevision: Long
    )
    data class Created(val handle: String, val review: Review)
    private val reviews = mutableMapOf<String, Review>()

    fun clear() = reviews.clear()

    fun create(snapshot: JSONObject, model: String, generation: Long, document: String?, selectionRevision: Long): Created {
        val binding = ModelBinding.fromJson(snapshot.getJSONObject("binding"))
        val sameModel = snapshot.getJSONObject("api").optString("model") == model
        val reasoning = if (sameModel) ReasoningOptions.fromJson(snapshot.getJSONObject("reasoning")) else ReasoningOptions.DEFAULT
        val review = Review(binding, model, identity(snapshot, model), snapshot.getJSONObject("api").optInt("maxOutputTokens", 4096), reasoning,
            if (sameModel) snapshot.optInt("imageTokenReserve") else 0, generation, document, SystemClock.elapsedRealtime() + 120_000L, selectionRevision)
        reviews.clear()
        val handle = UUID.randomUUID().toString()
        reviews[handle] = review
        return Created(handle, review)
    }

    // Consumption intentionally precedes all caller-side idle, expiry and generation checks.
    fun take(handle: String): Review = reviews.remove(handle) ?: error("原模型审阅已关闭或已使用，请重新选择")

    companion object {
        private fun modelRow(snapshot: JSONObject, model: String): JSONObject = snapshot.optJSONArray("models").objects()
            .firstOrNull { it.optString("modelId") == model } ?: error("原模型已不在当前目录，请刷新后重新核对")

        fun identity(snapshot: JSONObject, model: String): String {
            val value = JSONObject().put("binding", snapshot.getJSONObject("binding")).put("api", snapshot.getJSONObject("api"))
                .put("activeRoute", snapshot.optString("activeRoute")).put("reasoning", snapshot.getJSONObject("reasoning"))
                .put("imageTokenReserve", snapshot.optInt("imageTokenReserve")).put("row", modelRow(snapshot, model)).toString()
            return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
