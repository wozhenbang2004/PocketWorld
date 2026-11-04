package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelConnectionTestIdentityTest {
    private val editor = "cdb9664e-3c35-4b5b-a2be-84b1e8bdfb01"
    private fun input() = JSONObject().put("baseUrl", "https://fixture.test/v1").put("model", "model-a").put("apiKey", "private-fixture-key")
    private fun capture(input: JSONObject = input()) = ModelConnectionTestIdentity.capture("original-connection", 7, editor, 3, input)
    private fun reference() = JSONObject().put("taskId", "a56a6ddb-3de4-4b00-9aa9-9cd3eff3cf91")
        .put("runId", "d7432e34-8f42-4be3-833d-3c42d52129ad").put("epoch", 1).put("purpose", "CONNECTION_TEST")
        .put("accountingOnly", true).put("executionAuthorized", false)
    private fun stored(revision: Int = 7, enabled: Boolean = true) = JSONObject().put("connections", JSONArray()
        .put(JSONObject().put("id", "original-connection").put("revision", revision).put("enabled", enabled)))
    private fun usage(): JSONObject {
        val receipt = JSONObject().put("callId", "native-physical-call").put("outcome", "COMPLETED").put("context", reference())
            .put("route", JSONObject().put("protocol", "anthropic").put("requestedModel", "model-a")
                .put("providerProjection", JSONObject().put("hintPrepared", false).put("reason", "no_stable_system_or_tools")))
            .put("usage", JSONObject().put("actualCost", JSONObject.NULL).put("actualCostSource", "unknown").put("counters", JSONObject()
                .put("CACHE_READ", JSONObject().put("value", JSONObject.NULL).put("source", "unknown"))
                .put("CACHE_WRITE", JSONObject().put("value", 12).put("source", "provider_reported"))))
        return JSONObject().put("receipts", JSONArray().put(receipt)).put("unfinished", 0).put("logicalRequests", JSONArray().put(JSONObject().put("state", "SUCCEEDED")))
    }

    @Test fun canonicalDraftFingerprintChangesWithSecretsWithoutPublishingThem() {
        val first = capture()
        val reordered = capture(JSONObject().put("apiKey", "private-fixture-key").put("model", "model-a").put("baseUrl", "https://fixture.test/v1"))
        assertEquals(first.draftFingerprint, reordered.draftFingerprint); assertNotEquals(first.testId, reordered.testId)
        for (change in listOf(input().put("apiKey", "replacement"), input().put("model", "model-b"), input().put("compatibility", JSONObject().put("cacheMode", "OFF"))))
            assertNotEquals(first.draftFingerprint, capture(change).draftFingerprint)
        val receipt = first.receipt(true, "completed", reference()).toString()
        assertFalse(receipt.contains("private-fixture-key")); assertFalse(receipt.contains("apiKey")); assertFalse(receipt.contains("fixture.test"))
        assertTrue(first.draftFingerprint.matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun savedRevisionGateRejectsQueuedEditsDisabledMissingAndDuplicateOriginalRows() {
        val identity = capture(); assertTrue(identity.matchesStoredRevision(stored()))
        assertFalse(identity.matchesStoredRevision(stored(8))); assertFalse(identity.matchesStoredRevision(stored(enabled = false)))
        assertFalse(identity.matchesStoredRevision(JSONObject().put("connections", JSONArray())))
        val duplicates = stored(); duplicates.getJSONArray("connections").put(stored().getJSONArray("connections").getJSONObject(0))
        assertFalse(identity.matchesStoredRevision(duplicates))
        assertTrue(ModelConnectionTestIdentity.capture("", 0, editor, 0, input()).matchesStoredRevision(JSONObject()))
    }

    @Test fun resultRequiresTheActualOriginalAccountingReferenceAndCannotGrantExecution() {
        val identity = capture()
        assertEquals("CONNECTION_TEST", identity.receipt(true, "completed", reference()).getJSONObject("inferenceReference").getString("purpose"))
        assertThrows(IllegalArgumentException::class.java) { identity.receipt(true, "completed", null) }
        assertThrows(IllegalArgumentException::class.java) { identity.receipt(true, "completed", reference().put("purpose", "MAIN")) }
        assertThrows(IllegalArgumentException::class.java) { identity.receipt(true, "completed", reference().put("executionAuthorized", true)) }
        assertFalse(identity.receipt(false, "unknown", null).getBoolean("presentationCurrent"))
    }

    @Test fun feedbackReadsActualOriginalReceiptsAndPreservesUnknownCacheAndMoney() {
        val feedback = ModelConnectionTestIdentity.feedback(reference(), usage())
        assertTrue(feedback.getBoolean("available")); assertFalse(feedback.getBoolean("outcomeUnknown"))
        val call = feedback.getJSONArray("calls").getJSONObject(0)
        assertEquals("native-physical-call", call.getString("callId")); assertTrue(call.isNull("actualCost"))
        assertTrue(call.getJSONObject("cacheCounters").getJSONObject("CACHE_READ").isNull("value"))
        assertEquals(12, call.getJSONObject("cacheCounters").getJSONObject("CACHE_WRITE").getInt("value"))
        assertEquals("no_stable_system_or_tools", call.getJSONObject("providerProjection").getString("reason"))
    }

    @Test fun mismatchedTaskRunEpochAndPurposeCannotBeReattachedToTheCurrentTest() {
        for ((field, replacement) in listOf("taskId" to "another-task", "runId" to "another-run", "epoch" to 2, "purpose" to "MAIN")) {
            val changed = usage(); changed.getJSONArray("receipts").getJSONObject(0).getJSONObject("context").put(field, replacement)
            assertThrows(IllegalArgumentException::class.java) { ModelConnectionTestIdentity.feedback(reference(), changed) }
        }
    }

    @Test fun unfinishedUnknownPartialAndConflictingEvidenceNeverBecomesACompletedFreeCall() {
        for (status in listOf("UNKNOWN", "PARTIAL")) {
            val unknown = usage().put("logicalRequests", JSONArray().put(JSONObject().put("state", status)))
            assertTrue(ModelConnectionTestIdentity.feedback(reference(), unknown).getBoolean("outcomeUnknown"))
        }
        assertTrue(ModelConnectionTestIdentity.feedback(reference(), usage().put("unfinished", 1)).getBoolean("outcomeUnknown"))
        for (bad in listOf(usage().put("conflictingCalls", 1), usage().put("invalidReceipts", 1), usage().put("receipts", JSONArray()))) {
            val feedback = ModelConnectionTestIdentity.feedback(reference(), bad)
            assertFalse(feedback.getBoolean("available")); assertTrue(feedback.isNull("actualCost")); assertFalse(feedback.getBoolean("cacheHitKnown"))
        }
    }
}
