package dev.pocketengine.ui.memory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Protocol fixtures for display fences; not proof of a real host request/model use. */
class TaskMemoryEvidenceGuardTest {
    private fun row() = JSONObject().put("id", "record-a").put("revision", 3L).put("ownerId", "owner").put("spaceId", "private")
        .put("selection", "retrieved").put("status", "current").put("text", "旧地点是杭州").put("sourceRefs", JSONArray())
    private fun receipt() = JSONObject().put("receiptId", "receipt-a").put("taskId", "task-a").put("runId", "run-a")
        .put("coordinatorEpoch", 5L).put("ownerId", "owner").put("spaceId", "private").put("phase", "provided_to_request").put("requestId","request-a").put("answerId","answer-a")
        .put("status", "current").put("executionAuthorized", false).put("modelUseVerified", false).put("records", JSONArray().put(row()))
    private fun parse(value: JSONObject) = TaskMemoryEvidenceGuard.receipt("task-a", "run-a", value)
    @Test fun merelyReadingPreparedSnapshotNeverPromotesItToProvided() {
        val parsed=parse(receipt().put("phase","prepared"));assertEquals("prepared",parsed.getString("phase"))
        assertEquals(false,parsed.getBoolean("modelUseVerified"));assertEquals(false,parsed.getBoolean("executionAuthorized"))
        assertThrows(IllegalArgumentException::class.java){parse(receipt().put("phase","attached_to_task_context"))}
        val unavailable=receipt().put("phase","unavailable").put("status","unavailable").put("records",JSONArray());unavailable.remove("receiptId")
        assertEquals("unavailable",parse(unavailable).getString("phase"))
    }
    @Test fun mismatchedTaskRunSpaceAndClaimedAuthorityAreRejected() {
        assertThrows(IllegalArgumentException::class.java){parse(receipt().put("taskId","another-task"))}
        assertThrows(IllegalArgumentException::class.java){parse(receipt().put("runId","another-run"))}
        assertThrows(IllegalArgumentException::class.java){parse(receipt().put("executionAuthorized",true))}
        val other=receipt();other.getJSONArray("records").getJSONObject(0).put("spaceId","another-space")
        assertThrows(IllegalArgumentException::class.java){parse(other)}
    }
    @Test fun expiredChangedForgottenAndUnavailableRowsNeverExposeRetainedBody() {
        for(state in listOf("record_changed","record_removed","superseded","expired","source_changed","source_deleted","source_unavailable")){
            val value=receipt();value.getJSONArray("records").getJSONObject(0).put("status",state)
            assertFalse(parse(value).getJSONArray("records").getJSONObject(0).has("text"))
        }
        assertFalse(parse(receipt().put("status","unavailable")).getJSONArray("records").getJSONObject(0).has("text"))
        assertTrue(parse(receipt().put("status","invalidated")).getJSONArray("records").getJSONObject(0).has("text"))
    }
    @Test fun exactDisplayedBodyAndRevisionAreRequiredForCorrection() {
        val proof=parse(receipt());val selected=proof.getJSONArray("records").getJSONObject(0)
        val payload=TaskMemoryEvidenceGuard.correction(proof,selected,"目前地点是苏州")
        assertEquals(3L,payload.getLong("expectedRevision"));assertEquals("receipt-a",payload.getString("receiptId"))
        assertThrows(IllegalArgumentException::class.java){TaskMemoryEvidenceGuard.correction(proof,selected,selected.getString("text"))}
        assertThrows(IllegalArgumentException::class.java){TaskMemoryEvidenceGuard.reviewed(proof,JSONObject(selected.toString()).put("text","未显示的变化"))}
        val invalid=receipt();invalid.getJSONArray("records").getJSONObject(0).put("revision",3.5)
        assertThrows(IllegalArgumentException::class.java){parse(invalid)}
    }
    @Test fun replacementUsesTwoDifferentCurrentSameSpaceFormalVersions() {
        val proof=parse(receipt());val selected=proof.getJSONArray("records").getJSONObject(0)
        val kept=JSONObject(selected.toString()).put("id","kept-b").put("revision",7L).put("active",true).put("text","目前地点是苏州")
        val payload=TaskMemoryEvidenceGuard.replacement(proof,selected,kept)
        assertEquals(7L,payload.getLong("keptRevision"));assertEquals(3L,payload.getLong("replacedRevision"))
        assertThrows(IllegalArgumentException::class.java){TaskMemoryEvidenceGuard.replacement(proof,selected,JSONObject(kept.toString()).put("spaceId","other"))}
        assertThrows(IllegalArgumentException::class.java){TaskMemoryEvidenceGuard.replacement(proof,selected,JSONObject(kept.toString()).put("active",false))}
        assertThrows(IllegalArgumentException::class.java){TaskMemoryEvidenceGuard.replacement(proof,selected,JSONObject(kept.toString()).put("id",selected.getString("id")))}
    }
    @Test fun sourceIdentityAndScopeAreNotInferredFromProse() {
        val proof=parse(receipt());val ref=JSONObject().put("kind","task_evidence").put("sourceId","task:original:verified_result").put("revision",2L).put("ownerId","owner").put("spaceId","private").put("state","current")
        assertEquals(ref.toString(),TaskMemoryEvidenceGuard.source(proof,ref).toString())
        assertThrows(IllegalArgumentException::class.java){TaskMemoryEvidenceGuard.source(proof,JSONObject(ref.toString()).put("ownerId","someone-else"))}
        assertThrows(IllegalArgumentException::class.java){TaskMemoryEvidenceGuard.source(proof,JSONObject(ref.toString()).put("kind","url"))}
    }
    @Test fun writeSuccessDoesNotImplyPreviousContextWasRevoked() {
        val proof=parse(receipt());val selected=proof.getJSONArray("records").getJSONObject(0)
        val payload=TaskMemoryEvidenceGuard.reviewed(proof,selected)
        val result=TaskMemoryEvidenceGuard.target(proof).put("deleted",1)
        assertTrue(TaskMemoryEvidenceGuard.feedback(result,proof,"forgetTaskMemoryReviewed",payload).contains("尚未确认旧上下文清理"))
        result.put("contextInvalidated",true).put("futureRecallBlocked",true).put("revocationPending",true)
        assertTrue(TaskMemoryEvidenceGuard.feedback(result,proof,"forgetTaskMemoryReviewed",payload).contains("仍待核对"))
        result.put("revocationPending",false)
        assertTrue(TaskMemoryEvidenceGuard.feedback(result,proof,"forgetTaskMemoryReviewed",payload).contains("后续不会再召回"))
        assertThrows(IllegalArgumentException::class.java){TaskMemoryEvidenceGuard.feedback(JSONObject(result.toString()).put("runId","old-run"),proof,"forgetTaskMemoryReviewed",payload)}
    }
    @Test fun lateOrMalformedEnvelopeCannotConfirmAnotherAction() {
        val envelope=JSONObject().put("requestId","new").put("ok",true).put("result",JSONObject())
        assertNull(TaskMemoryEvidenceGuard.exactEnvelope("old",envelope));assertNull(TaskMemoryEvidenceGuard.exactEnvelope(null,envelope))
        assertNull(TaskMemoryEvidenceGuard.exactEnvelope("new",JSONObject(envelope.toString()).put("ok","true")))
        assertNotNull(TaskMemoryEvidenceGuard.exactEnvelope("new",envelope))
    }
    @Test fun sourceVersionSurvivesLongToIntegerJsonRoundTripWithoutAcceptingFractions() {
        val native=JSONObject().put("kind","task_evidence").put("sourceId","task:original:verified_result").put("revision",2L).put("ownerId","owner").put("spaceId","private")
        val transported=JSONObject(native.toString())
        assertTrue(TaskMemoryEvidenceGuard.sameSource(transported,native))
        assertFalse(TaskMemoryEvidenceGuard.sameSource(transported,JSONObject(native.toString()).put("revision",2.5)))
        assertFalse(TaskMemoryEvidenceGuard.sameSource(transported,JSONObject(native.toString()).put("sourceId","another")))
        assertThrows(IllegalArgumentException::class.java){TaskMemoryEvidenceGuard.receipt("task-a","run-a",receipt(),"wrong-request","answer-a")}
    }
}
