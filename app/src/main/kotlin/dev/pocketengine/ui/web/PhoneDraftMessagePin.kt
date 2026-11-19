package dev.pocketengine.ui.web

import org.json.JSONObject

/** Local presentation provenance only. No body or permission. SQLite verifies it again before first admission. */
internal data class PhoneDraftMessagePin private constructor(val generation: String, val conversation: String, val message: String, val version: Long, val fingerprint: String) {
    fun locator(): JSONObject = JSONObject().put("kind","owner_native_original").put("generation",generation).put("conversationId",conversation)
        .put("recordId",message).put("recordVersion",version).put("fingerprint",fingerprint)
    companion object {
        fun from(value: JSONObject?): PhoneDraftMessagePin? {
            if (value == null || value.opt("kind") != "owner_native_original") return null
            fun id(key: String) = (value.opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= 240 }
            val rev = when(val v=value.opt("recordVersion")) { is Int -> v.toLong(); is Long -> v; else -> return null }
            if(rev <= 0) return null
            val sha=(value.opt("fingerprint") as? String)?.takeIf { it.matches(Regex("sha256:[a-f0-9]{64}")) } ?: return null
            return PhoneDraftMessagePin(id("generation")?:return null,id("conversationId")?:return null,id("recordId")?:return null,rev,sha)
        }
    }
}
