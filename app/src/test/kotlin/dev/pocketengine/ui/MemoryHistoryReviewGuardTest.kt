package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Display consistency fixtures only; flags here never issue native read authority. */
class MemoryHistoryReviewGuardTest {
    private fun header() = JSONObject().put("ownerId", "owner").put("spaceId", "private").put("id", "record").put("revision", 7).put("textPreview", "保留的旧事摘录").put("memoryTextIsOriginalEvidence", false).put("temporalStatus", "historical")
    private fun page() = JSONObject().put("format", "pocketworld.owner-memory-history-page.v1").put("query", "旧事").put("sourceCurrentChecked", true).put("activeRecall", false).put("originalEvidenceRead", false).put("archiveOnlyActivated", false).put("memberReadAuthorized", false).put("executionAuthorized", false).put("scope", JSONObject().put("ownerId", "owner").put("spaceId", "private")).put("resultVersion", "a".repeat(64)).put("matches", JSONArray().put(header()))
    private fun envelope(value: JSONObject, id: String = "request") = JSONObject().put("requestId", id).put("ok", true).put("result", value)
    @Test fun onlyTheExactRequestAndStillDisplayedQueryMayPopulateThePage() {
        val reply = envelope(page())
        assertNotNull(MemoryHistoryReviewGuard.page("request", " 旧事 ", reply))
        assertNull(MemoryHistoryReviewGuard.page("replaced", "旧事", reply)); assertNull(MemoryHistoryReviewGuard.page("request", "新问题", reply)); assertNull(MemoryHistoryReviewGuard.page(null, "旧事", reply))
    }
    @Test fun unknownArchiveOrAnotherScopeCannotBeRenderedAsAnActiveOrProvenHistoryPage() {
        for (key in listOf("activeRecall", "originalEvidenceRead", "archiveOnlyActivated", "memberReadAuthorized", "executionAuthorized")) assertNull(key, MemoryHistoryReviewGuard.page("request", "旧事", envelope(page().put(key, true))))
        assertNull(MemoryHistoryReviewGuard.page("request", "旧事", envelope(page().put("scope", JSONObject().put("ownerId", "owner").put("spaceId", "project:other")))))
        val bad = page(); bad.getJSONArray("matches").getJSONObject(0).put("textPreview", "\uD800"); assertNull(MemoryHistoryReviewGuard.page("request", "旧事", envelope(bad)))
    }
    @Test fun aReadMustKeepTheExactRecordRevisionTemporalMeaningAndStoredTextIdentity() {
        fun read() = JSONObject().put("sourceCurrentChecked", true).put("originalEvidenceRead", false).put("activeRecall", false).put("executionAuthorized", false).put("record", header().put("readOnlyHistory", true).put("text", "仅保存的记忆，不是原件"))
        assertNotNull(MemoryHistoryReviewGuard.record("request", header(), envelope(read())))
        val changed = read(); changed.getJSONObject("record").put("revision", 8); assertNull(MemoryHistoryReviewGuard.record("request", header(), envelope(changed)))
        val promoted = read(); promoted.getJSONObject("record").put("temporalStatus", "current"); assertNull(MemoryHistoryReviewGuard.record("request", header(), envelope(promoted)))
        assertNull(MemoryHistoryReviewGuard.record("request", header(), envelope(read().put("originalEvidenceRead", true))))
    }
    @Test fun projectRenderingRequiresTheExactDisplayedNativeScopeAndNeverUsesPrivateFallback() {
        val selected = JSONObject().put("ownerId", "owner").put("spaceId", "project:11111111-1111-1111-1111-111111111111")
        val nativePage = page().put("scope", JSONObject(selected.toString()))
        assertNotNull(MemoryHistoryReviewGuard.page("request", "旧事", envelope(nativePage), selected))
        assertNull(MemoryHistoryReviewGuard.page("request", "旧事", envelope(nativePage)))
        assertNull(MemoryHistoryReviewGuard.page("request", "旧事", envelope(page()), selected))
        assertNull(MemoryHistoryReviewGuard.page("request", "旧事", envelope(nativePage), JSONObject().put("ownerId", "owner").put("spaceId", "group:one")))
        val read = JSONObject().put("sourceCurrentChecked", true).put("originalEvidenceRead", false).put("activeRecall", false).put("executionAuthorized", false).put("record", header().put("readOnlyHistory", true).put("spaceId", selected.getString("spaceId")))
        assertNotNull(MemoryHistoryReviewGuard.record("request", header(), envelope(read), selected))
        assertNull(MemoryHistoryReviewGuard.record("request", header(), envelope(read)))
    }
    @Test fun acceptedRenderingCopiesDoNotMutateTheNativeReceiptOrInventAnOwnerSubject() {
        val source = page(); val copy = MemoryHistoryReviewGuard.page("request", "旧事", envelope(source))!!
        assertFalse(copy.getJSONArray("matches").getJSONObject(0).has("subjectId")); copy.getJSONArray("matches").getJSONObject(0).put("textPreview", "UI local edit")
        assertEquals("保留的旧事摘录", source.getJSONArray("matches").getJSONObject(0).getString("textPreview"))
    }
}
