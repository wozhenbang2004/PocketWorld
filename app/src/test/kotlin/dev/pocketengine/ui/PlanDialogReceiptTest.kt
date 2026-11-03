package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlanDialogReceiptTest {
    private fun request(command: String = "editStep") = PlanDialogRequest("request-1", command, "plan-1", 7)
    private fun receipt(id: String = "request-1") = JSONObject().put("_requestId", id)
    private fun result(command: String = "editStep", id: String = "plan-1", revision: Any = 8) =
        JSONObject().put("command", command).put("plan", JSONObject().put("id", id).put("revision", revision))

    @Test fun failedEditAndApprovalReleasePendingWithoutClosingTheirDraft() {
        for (command in listOf("editStep", "approve")) {
            val pending = request(command)
            val failure = receipt().put("_failed", true)
            val before = failure.toString()
            for (returned in listOf(null, result(command))) {
                val consumed = consumePlanDialogReceipt(pending, failure, returned)
                assertNull(consumed.pending)
                assertFalse("$command must retain the local draft even beside a stale result", consumed.close)
            }
            assertEquals("Consumption must not rewrite the failure/error source", before, failure.toString())
        }
    }

    @Test fun unrelatedOrMissingReceiptsPreservePending() {
        val pending = request()
        for (acknowledgement in listOf(null, JSONObject(), receipt("other-request"))) {
            val consumed = consumePlanDialogReceipt(pending, acknowledgement, result())
            assertSame(pending, consumed.pending)
            assertFalse(consumed.close)
        }
        assertFalse(consumePlanDialogReceipt(null, receipt(), result()).close)
    }

    @Test fun onlyTheSubmittedCommandAndOriginalPlanCanClose() {
        for (returned in listOf(result("snapshot"), result("approve"), result(id = "other-plan"), result(id = ""))) {
            val consumed = consumePlanDialogReceipt(request(), receipt(), returned)
            assertNull(consumed.pending)
            assertFalse(consumed.close)
        }
        assertFalse(consumePlanDialogReceipt(request("snapshot"), receipt(), result("snapshot")).close)
    }

    @Test fun matchingReceiptWithoutAValidPlanResultKeepsTheDraft() {
        val invalid = listOf(null, JSONObject(), JSONObject().put("command", "editStep"),
            JSONObject().put("command", "editStep").put("plan", "unavailable"),
            JSONObject().put("command", "editStep").put("plan", JSONObject().put("id", "plan-1")))
        for (returned in invalid) {
            val consumed = consumePlanDialogReceipt(request(), receipt(), returned)
            assertNull(consumed.pending)
            assertFalse(consumed.close)
        }
    }

    @Test fun unchangedOlderOrMalformedRevisionIsNotSuccess() {
        for (revision in listOf<Any>(7, 6L, "8", 8.0, true, JSONObject.NULL)) {
            val consumed = consumePlanDialogReceipt(request(), receipt(), result(revision = revision))
            assertNull(consumed.pending)
            assertFalse("Invalid returned revision $revision", consumed.close)
        }
    }

    @Test fun malformedFailureMarkersCannotBecomeSuccess() {
        for (failed in listOf<Any>("false", "true", 0, JSONObject.NULL)) {
            val consumed = consumePlanDialogReceipt(request(), receipt().put("_failed", failed), result())
            assertNull(consumed.pending)
            assertFalse(consumed.close)
        }
    }

    @Test fun matchingSuccessfulEditAndApprovalCloseAndReleasePending() {
        for (command in listOf("editStep", "approve")) {
            for (acknowledgement in listOf(receipt(), receipt().put("_failed", false))) {
                for (revision in listOf<Any>(8, 12L)) {
                    val consumed = consumePlanDialogReceipt(request(command), acknowledgement, result(command, revision = revision))
                    assertNull(consumed.pending)
                    assertTrue(consumed.close)
                }
            }
        }
    }

    @Test fun lateFailureCannotConsumeANewerAttempt() {
        val newer = request().copy(requestId = "request-2")
        val consumed = consumePlanDialogReceipt(newer, receipt().put("_failed", true), null)
        assertSame(newer, consumed.pending)
        assertFalse(consumed.close)
        assertTrue(consumePlanDialogReceipt(newer, receipt("request-2"), result()).close)
    }
}
