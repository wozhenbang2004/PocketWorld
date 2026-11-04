package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneResultProjectionTest {
    private val task = "11111111-1111-4111-8111-111111111111"
    private val run = "22222222-2222-4222-8222-222222222222"
    private val laterRun = "33333333-3333-4333-8333-333333333333"

    private fun message(status: String = "failed", runId: String = run, epoch: Int = 1): JSONObject = JSONObject()
        .put("id", "original-message").put("revision", 3).put("role", "assistant").put("content", "原轮次的完整正文")
        .put("taskId", task).put("nativeRunId", runId).put("nativeEpoch", epoch)
        .put("nativeDeliveryId", "phone_handoff:$task:$runId:$epoch")
        .put("nativeStatus", status).put("status", status).put("taskEndedAt", 1_790_000_000_000L)
        .put("summaryTruncated", false).put("summaryChars", "原轮次的完整正文".length)
        .put("taskReference", JSONObject().put("taskId", task).put("runId", runId).put("epoch", epoch))

    @Test fun eachEndedRunKeepsItsOwnStatusAndIdentity() {
        val first = requireNotNull(UiPhoneResult.fromNativeMessage(message()))
        val second = requireNotNull(UiPhoneResult.fromNativeMessage(message("completed", laterRun, 2)))
        assertEquals(task, first.taskId); assertEquals(task, second.taskId)
        assertEquals(run, first.runId); assertEquals(1, first.epoch); assertEquals("failed", first.status)
        assertEquals(laterRun, second.runId); assertEquals(2, second.epoch); assertEquals("completed", second.status)
        assertNotEquals(first.deliveryId, second.deliveryId)
        for (status in listOf("completed", "needs_confirmation", "awaiting_user", "failed", "cancelled", "interrupted", "reconciling", "paused")) {
            assertEquals(status, UiPhoneResult.fromNativeMessage(message(status))?.status)
        }
    }

    @Test fun eitherNativeStatusNameIsAcceptedButConflictingOrNonterminalValuesAreNot() {
        val statusOnly = message().also { it.remove("nativeStatus") }
        val nativeOnly = message().also { it.remove("status") }
        assertEquals("failed", UiPhoneResult.fromNativeMessage(statusOnly)?.status)
        assertEquals("failed", UiPhoneResult.fromNativeMessage(nativeOnly)?.status)
        assertNull(UiPhoneResult.fromNativeMessage(message().put("status", "completed")))
        assertNull(UiPhoneResult.fromNativeMessage(message().put("nativeStatus", JSONObject.NULL)))
        for (status in listOf("running", "preparing", "stopping", "", "FAILED")) {
            assertNull(status, UiPhoneResult.fromNativeMessage(message(status)))
        }
    }

    @Test fun deliveryAndReferenceMustMatchAllThreeOriginalIdentityParts() {
        for (key in listOf("taskId", "runId", "epoch")) {
            val row = message()
            row.getJSONObject("taskReference").put(key, if (key == "epoch") 2 else "different-original")
            assertNull(key, UiPhoneResult.fromNativeMessage(row))
        }
        assertNull(UiPhoneResult.fromNativeMessage(message().put("nativeDeliveryId", "phone_handoff:$task:$run:2")))
        assertNull(UiPhoneResult.fromNativeMessage(message().put("taskId", "")))
        assertNull(UiPhoneResult.fromNativeMessage(message().put("nativeRunId", " ")))
    }

    @Test fun incompleteMetadataDoesNotHideOrRewriteTheOriginalBody() {
        for (key in listOf("taskId", "nativeRunId", "nativeEpoch", "nativeDeliveryId", "taskReference", "taskEndedAt", "summaryTruncated", "summaryChars")) {
            val row = message(); row.remove(key)
            val before = row.toString()
            assertNull(key, UiPhoneResult.fromNativeMessage(row))
            assertEquals(before, row.toString())
            assertEquals("原轮次的完整正文", row.getString("content"))
            assertEquals("original-message", row.getString("id")); assertEquals(3, row.getInt("revision"))
        }
        val noStatus = message().also { it.remove("status"); it.remove("nativeStatus") }
        assertNull(UiPhoneResult.fromNativeMessage(noStatus))
    }

    @Test fun numericStringsFractionsOverflowAndInvalidBooleanAreNotCoerced() {
        for (value in listOf<Any>("1", 1.5, 1.0, 0, -1, Int.MAX_VALUE.toLong() + 1, JSONObject.NULL)) {
            assertNull("nativeEpoch=$value", UiPhoneResult.fromNativeMessage(message().put("nativeEpoch", value)))
        }
        assertNull(UiPhoneResult.fromNativeMessage(message().also { it.getJSONObject("taskReference").put("epoch", "1") }))
        for (value in listOf<Any>("1790000000000", 1.5, 0, -1, JSONObject.NULL)) {
            assertNull("taskEndedAt=$value", UiPhoneResult.fromNativeMessage(message().put("taskEndedAt", value)))
        }
        for (value in listOf<Any>("9", 1.5, -1, Int.MAX_VALUE.toLong() + 1, JSONObject.NULL)) {
            assertNull("summaryChars=$value", UiPhoneResult.fromNativeMessage(message().put("summaryChars", value)))
        }
        assertNull(UiPhoneResult.fromNativeMessage(message().put("summaryTruncated", "false")))
        assertNotNull(UiPhoneResult.fromNativeMessage(message().put("nativeEpoch", 1L)))
    }

    @Test fun userImportedAndOrdinaryMessagesCannotSupplyAResultBadge() {
        assertNull(UiPhoneResult.fromNativeMessage(message().put("role", "user")))
        assertNull(UiPhoneResult.fromNativeMessage(message().put("importAssignedIdentity", true)))
        assertNull(UiPhoneResult.fromNativeMessage(JSONObject().put("role", "assistant").put("content", "普通回复")
            .put("phoneResult", requireNotNull(UiPhoneResult.fromNativeMessage(message())).toJson())))
    }

    @Test fun projectedValueDoesNotRetainMutableMessageOrPublicJson() {
        val row = message()
        val projected = requireNotNull(UiPhoneResult.fromNativeMessage(row))
        row.put("nativeRunId", laterRun).put("nativeStatus", "completed").put("summaryChars", 999)
        row.getJSONObject("taskReference").put("epoch", 2)
        projected.toJson().put("status", "running").put("runId", laterRun)
        assertEquals(run, projected.runId); assertEquals("failed", projected.status); assertEquals("原轮次的完整正文".length, projected.summaryChars)
        assertEquals("failed", projected.toJson().getString("status"))
    }

    @Test fun publicProjectionRetainsTruncationFactsWithoutPublishingAuthorityOrRawEvidence() {
        val row = message().put("summaryTruncated", true).put("summaryChars", 25_000)
            .put("executionAuthorized", true).put("resultVerified", true)
            .put("taskResultEvidence", JSONObject().put("rawToolBody", "must stay native"))
        val result = requireNotNull(UiPhoneResult.fromNativeMessage(row)).toJson()
        assertEquals(setOf("taskId", "runId", "epoch", "deliveryId", "status", "endedAt", "summaryTruncated", "summaryChars"), result.keySet())
        assertEquals(1_790_000_000_000L, result.getLong("endedAt"))
        assertTrue(result.getBoolean("summaryTruncated")); assertEquals(25_000, result.getInt("summaryChars"))
        assertFalse(result.toString().contains("must stay native"))
    }
}
