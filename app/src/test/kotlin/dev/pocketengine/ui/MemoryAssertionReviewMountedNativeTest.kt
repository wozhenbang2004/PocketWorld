package dev.pocketengine.ui

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Existing Web knowledge handles -> real native candidate, identity donor and SQLite revisions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MemoryAssertionReviewMountedNativeTest {
    private fun read(f: OwnerMemoryWebFixture, candidate: JSONObject) = f.request("knowledge.get", JSONObject().put("id", candidate.getString("id")).put("candidate", true))
    private fun input(read: JSONObject, subject: JSONObject, kind: String = "inferred") = JSONObject().put("readHandle", read.getString("readHandle"))
        .put("subject", subject).put("relationTarget", JSONObject.NULL).put("kind", kind).put("predicate", "旅行计划").put("object", "远航").put("condition", "")
        .put("eventTime", "1970-01-02").put("validFrom", "").put("validTo", "").put("reviewAfter", "")
    private fun confirm(review: JSONObject) = JSONObject().put("reviewHandle", review.getString("reviewHandle"))

    @Test fun unknownSubjectRequiresExplicitChoiceAndAPlanRemainsUnrealizedAfterSeparateAcceptance() {
        OwnerMemoryWebFixture().use { f ->
            val pending = MemoryAssertionReviewNativeTest.stage(f.native, "同名人物的远航计划，尚未实现")
            val selected = read(f, pending)
            val prepared = f.request("knowledge.assertion.prepare", JSONObject().put("readHandle", selected.getString("readHandle")).put("query", ""))
            assertFalse(prepared.getBoolean("formalMemorySaved")); assertFalse(prepared.getBoolean("sourceProofIssued")); assertFalse(prepared.getBoolean("executionAuthorized"))
            f.rejected("knowledge.assertion.review", input(selected, JSONObject().put("mode", "")))
            assertFalse(MemoryAssertionReviewNativeTest.candidate(f.native, pending).has("assertion"))
            val review = f.request("knowledge.assertion.review", input(selected, JSONObject().put("mode", "new").put("type", "person").put("label", "同名人物"), "imagined"))
            assertFalse(MemoryAssertionReviewNativeTest.candidate(f.native, pending).has("assertion"))
            val first = f.start("knowledge.confirm", confirm(review)); f.rejected("knowledge.confirm", confirm(review))
            val result = f.finish(first).also { assertNull(it.error) }.value!!; val reviewed = result.getJSONObject("candidate")
            assertEquals(pending.getLong("revision") + 1, reviewed.getLong("revision")); assertEquals("pending", reviewed.getString("status"))
            assertEquals("imagined", reviewed.getJSONObject("assertion").getString("kind")); assertNotEquals("owner", reviewed.getJSONObject("assertion").getString("subjectId"))
            f.rejected("knowledge.confirm", confirm(review)); assertEquals(reviewed.getLong("revision"), MemoryAssertionReviewNativeTest.candidate(f.native, reviewed).getLong("revision"))
            val current = read(f, reviewed)
            val adoption = f.request("knowledge.review", JSONObject().put("readHandle", current.getString("readHandle")).put("operation", "accept"))
            assertEquals("pending", MemoryAssertionReviewNativeTest.candidate(f.native, reviewed).getString("status"))
            val saved = f.request("knowledge.confirm", confirm(adoption)).getJSONObject("record")
            val cognition = f.request("memoryHistory.cognition", JSONObject().put("sessionHandle", f.session()).put("subjectId", saved.getJSONObject("assertion").getString("subjectId")))
            assertEquals(0, cognition.getJSONArray("current").length()); assertEquals("imagined_not_real_world_fact", cognition.getJSONArray("history").getJSONObject(0).getString("assertionStatus"))
        }
    }
    @Test fun anExactDonorRemainsReadableAndCancelledOrCrossScopeReviewHandlesCannotResend() {
        OwnerMemoryWebFixture().use { f ->
            val donor = MemoryAssertionReviewNativeTest.person(f.native, "研发的小林喜欢咖啡", "小林", "咖啡")
            val pending = MemoryAssertionReviewNativeTest.stage(f.native, "研发的小林负责新项目"); val project = f.native.create("另一个记忆范围")
            val selected = read(f, pending)
            val prepared = f.request("knowledge.assertion.prepare", JSONObject().put("readHandle", selected.getString("readHandle")).put("query", "小林"))
            val identities = prepared.getJSONArray("identities")
            val identity = (0 until identities.length()).map { identities.getJSONObject(it) }.single { it.optString("id") == donor.getJSONObject("assertion").getString("subjectId") }
            val args = input(selected, JSONObject().put("mode", "existing").put("choiceHandle", identity.getString("choiceHandle")).put("label", "小林")).put("predicate", "负责项目").put("object", "新项目")
            val cancelled = f.request("knowledge.assertion.review", args)
            f.request("knowledge.close", JSONObject().put("handle", cancelled.getString("reviewHandle"))); f.rejected("knowledge.confirm", confirm(cancelled))
            assertFalse(MemoryAssertionReviewNativeTest.candidate(f.native, pending).has("assertion"))
            val review = f.request("knowledge.assertion.review", args)
            val reviewed = f.request("knowledge.confirm", confirm(review)).getJSONObject("candidate")
            assertEquals(1, reviewed.getJSONArray("recordRefs").length())
            val exact = reviewed.getJSONArray("recordRefs").getJSONObject(0); assertEquals(donor.getString("id"), exact.getString("id")); assertEquals(donor.getLong("revision"), exact.getLong("revision"))
            val updated = read(f, reviewed)
            val parent = f.request("knowledge.parent", JSONObject().put("readHandle", updated.getString("readHandle")).put("index", 0)).getJSONObject("record")
            assertEquals(donor.getString("id"), parent.getString("id")); assertEquals(donor.getLong("revision"), parent.getLong("revision")); assertEquals(donor.getString("text"), parent.getString("text"))
            val stale = f.request("knowledge.assertion.review", input(updated, JSONObject().put("mode", "candidate").put("label", "小林")))
            f.native.choose(project); f.rejected("knowledge.confirm", confirm(stale)); f.native.choose(null); f.rejected("knowledge.confirm", confirm(stale)); f.rejected("knowledge.confirm", confirm(cancelled))
            assertEquals(reviewed.getLong("revision"), MemoryAssertionReviewNativeTest.candidate(f.native, reviewed).getLong("revision"))
        }
    }
}
