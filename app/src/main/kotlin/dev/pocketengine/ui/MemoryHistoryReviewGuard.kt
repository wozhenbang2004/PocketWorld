package dev.pocketengine.ui

import org.json.JSONObject

/** Rendering consistency only. Native owner/Book/Bridge, not these JSON markers, authorize reads. */
internal object MemoryHistoryReviewGuard {
    @JvmStatic fun page(requestId: String?, query: String, envelope: JSONObject?): JSONObject? = runCatching {
        val value = MemoryLearningReviewGuard.result(requestId, envelope) ?: return null
        require(value.optString("format") == "pocketworld.owner-memory-history-page.v1" && value.optString("query") == query.trim())
        require(value.optBoolean("sourceCurrentChecked") && !value.optBoolean("activeRecall", true) && !value.optBoolean("originalEvidenceRead", true) && !value.optBoolean("archiveOnlyActivated", true) && !value.optBoolean("memberReadAuthorized", true) && !value.optBoolean("executionAuthorized", true))
        require(value.optJSONObject("scope")?.let { it.optString("ownerId") == "owner" && it.optString("spaceId") == "private" } == true)
        require(value.optString("resultVersion").matches(Regex("[a-f0-9]{64}")))
        val rows = value.getJSONArray("matches"); val ids = mutableSetOf<String>()
        require(rows.length() <= 20)
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            require(row.optString("id").isNotBlank() && ids.add(row.getString("id")) && MemoryLearningReviewGuard.revision(row) > 0)
            require(!row.optBoolean("memoryTextIsOriginalEvidence", true) && row.getString("textPreview").length <= 280)
            val preview = row.getString("textPreview"); var at = 0
            while (at < preview.length) {
                val char = preview[at]
                if (Character.isHighSurrogate(char)) { require(at + 1 < preview.length && Character.isLowSurrogate(preview[at + 1])); at += 2 }
                else { require(!Character.isLowSurrogate(char)); at++ }
            }
            require(row.optString("temporalStatus") in setOf("historical", "current", "pending_conflict", "untyped_time_unknown"))
        }
        JSONObject(value.toString())
    }.getOrNull()
    @JvmStatic fun record(requestId: String?, header: JSONObject?, envelope: JSONObject?): JSONObject? = runCatching {
        if (header == null) return null
        val value = MemoryLearningReviewGuard.result(requestId, envelope) ?: return null
        require(value.optBoolean("sourceCurrentChecked") && !value.optBoolean("originalEvidenceRead", true) && !value.optBoolean("activeRecall", true) && !value.optBoolean("executionAuthorized", true))
        val row = value.getJSONObject("record")
        require(row.optString("id") == header.optString("id") && MemoryLearningReviewGuard.revision(row) == MemoryLearningReviewGuard.revision(header))
        require(row.optString("temporalStatus") == header.optString("temporalStatus") && row.optBoolean("readOnlyHistory") && !row.optBoolean("memoryTextIsOriginalEvidence", true))
        JSONObject(row.toString())
    }.getOrNull()
}
