package dev.pocketengine.ui

import dev.pocketengine.runtime.models.ModelBinding
import org.json.JSONObject

/** One native editor's original object, not permission to save it or a live route supplier. */
internal class ModelPriceEditTarget private constructor(
    val binding: ModelBinding,
    val model: String,
    val ownerRevision: Long,
    private val quoteJson: String?,
) {
    fun quote(): JSONObject? = quoteJson?.let(::JSONObject)
    fun current(snapshot: JSONObject): Boolean = try {
        binding == ModelBinding.fromJson(snapshot.getJSONObject("binding")) &&
            model == snapshot.getString("model") && ownerRevision == nativeRevision(snapshot)
    } catch (_: Exception) { false }

    companion object {
        fun capture(snapshot: JSONObject): ModelPriceEditTarget? = try {
            val revision = nativeRevision(snapshot) ?: -1L
            val model = snapshot.getString("model")
            if (revision < 0 || model.isEmpty()) null else ModelPriceEditTarget(
                ModelBinding.fromJson(snapshot.getJSONObject("binding")),
                model, revision, snapshot.optJSONObject("quote")?.toString(),
            )
        } catch (_: Exception) { null }
        private fun nativeRevision(snapshot: JSONObject): Long? {
            val raw = snapshot.opt("ownerRevision")
            return if (raw is Int || raw is Long) (raw as Number).toLong().takeIf { it >= 0 } else null
        }
    }
}
