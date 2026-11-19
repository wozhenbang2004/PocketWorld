package dev.pocketengine.ui.web

import org.json.JSONObject

/** A confirmed write identity only. No profile, changed value, avatar, prose or replayable arguments. */
internal class ConfirmedProfileSave private constructor(private val revision: Long) {
    fun summary(): JSONObject = JSONObject().put("format", "pocketworld.profile-save-receipt.v1")
        .put("saved", true).put("persisted", true).put("profileRevision", revision).put("profileIncluded", false)

    companion object {
        private fun exact(value: Any?): Long? = when (value) { is Int -> value.toLong(); is Long -> value; else -> null }
        fun fromNative(expected: Any?, result: JSONObject?, failure: String?): ConfirmedProfileSave? {
            val original = exact(expected) ?: return null
            if (failure != null || original !in 1 until Long.MAX_VALUE || result?.opt("saved") != true || result.opt("persisted") != true) return null
            val saved = exact(result.optJSONObject("profile")?.opt("revision")) ?: return null
            return if (saved == original + 1) ConfirmedProfileSave(saved) else null
        }
    }
}
