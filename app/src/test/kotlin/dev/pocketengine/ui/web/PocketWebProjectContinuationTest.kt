package dev.pocketengine.ui.web

import dev.pocketengine.ui.UiTask
import dev.pocketengine.ui.taskFromJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PocketWebProjectContinuationTest {
    private fun task(status: String = "completed"): UiTask = taskFromJson(JSONObject()
        .put("id", "project-task").put("kind", "assistant").put("actorId", "owner").put("spaceId", "private")
        .put("executionMode", "capabilities").put("status", status).put("goal", "调整原项目")
        .put("runId", "old-run").put("coordinatorEpoch", 4).put("conversationId", "original-conversation")
        .put("developmentContext", JSONObject().put("projectId", "project").put("checkoutId", "checkout"))
        .put("operations", JSONArray()).put("approvals", JSONArray())
        .put("executionScope", JSONObject().put("format", "pocketworld.task-execution-scope.v1").put("taskId", "project-task")
            .put("principalId", "owner").put("spaceId", "private").put("state", "active").put("revision", 3)
            .put("deadlineAt", 0).put("lifetime", "until_complete_or_stopped_or_budget")
            .put("toolLimit", 20).put("toolsUsed", 7).put("modelCallLimit", 15).put("modelCallsUsed", 4)))

    @Test fun allSixOriginalStatusesOfferOnlyNativeReviewMetadata() {
        for (status in listOf("paused", "interrupted", "awaiting_user", "needs_confirmation", "failed", "completed")) {
            val original = task(status); val view = PocketWebProjectContinuation.view(original, false, 100)!!
            assertEquals(true, view.opt("feedbackRequired")); assertEquals("original-conversation", view.getString("conversationId"))
            assertFalse(status, view.has("reason")); assertFalse(view.has("reviewedTask")); assertFalse(view.has("runId"))
        }
    }

    @Test fun cancelledStoppedAndActiveTasksDoNotOfferContinuation() {
        for (status in listOf("cancelled", "canceled", "stopped", "running", "queued")) assertTrue(status, PocketWebProjectContinuation.view(task(status), false)!!.has("reason"))
        assertTrue(PocketWebProjectContinuation.view(task(), true)!!.has("reason"))
        assertTrue(PocketWebProjectContinuation.view(task().also { it.raw.put("cancelRequested", true) }, false)!!.has("reason"))
    }

    @Test fun expiredMissingExhaustedAndWrongTaskScopeShowConcreteRefusal() {
        val missing = task().also { it.raw.remove("executionScope") }; assertTrue(PocketWebProjectContinuation.view(missing, false)!!.getString("reason").contains("范围"))
        val expired = task().also { it.raw.getJSONObject("executionScope").put("deadlineAt", 50).put("lifetime", "deadline") }
        assertTrue(PocketWebProjectContinuation.view(expired, false, 100)!!.getString("reason").contains("到期"))
        for ((used, limit) in listOf("toolsUsed" to "toolLimit", "modelCallsUsed" to "modelCallLimit")) {
            val value = task(); val scope = value.raw.getJSONObject("executionScope"); scope.put(used, scope.getInt(limit))
            assertTrue(PocketWebProjectContinuation.view(value, false)!!.getString("reason").contains("额度"))
        }
        assertTrue(PocketWebProjectContinuation.view(task().also { it.raw.getJSONObject("executionScope").put("taskId", "another") }, false)!!.has("reason"))
    }

    @Test fun dedicatedSourcesAndIncompleteIdentityNeverBorrowTheOrdinaryBranch() {
        for (key in listOf("teamReservationPool", "ownerGit", "workProfileRef", "workProfileIntent")) {
            assertTrue(key, PocketWebProjectContinuation.view(task().also { it.raw.put(key, JSONObject()) }, false)!!.has("reason"))
        }
        assertNull(PocketWebProjectContinuation.view(task().also { it.raw.put("origin", JSONObject().put("kind", "group")) }, false))
        for (change in listOf("runId" to "", "conversationId" to "", "coordinatorEpoch" to "4")) {
            assertTrue(PocketWebProjectContinuation.view(task().also { it.raw.put(change.first, change.second) }, false)!!.has("reason"))
        }
    }

    @Test fun anUnconfirmedChargedReadIsNotReclassifiedAsASideEffect() {
        val original = task(); original.raw.getJSONArray("operations").put(JSONObject().put("tool", "project_read_file").put("state", "read_unconfirmed"))
        assertFalse(PocketWebProjectContinuation.view(original, false)!!.has("reason"))
        original.raw.getJSONArray("operations").put(JSONObject().put("tool", "project_execute").put("state", "unknown"))
        assertTrue(PocketWebProjectContinuation.view(original, false)!!.has("reason"))
    }

    @Test fun nativeInputFreezesOriginalRawAndNeverCopiesComposerOrWebIdentity() {
        val original = task(); val input = PocketWebProjectContinuation.input(original, "  调整按钮间距  ")
        assertEquals(setOf("taskId", "reviewedTask", "feedback"), input.keys().asSequence().toSet())
        assertEquals("调整按钮间距", input.getString("feedback"))
        original.raw.getJSONObject("executionScope").put("toolsUsed", 19)
        assertEquals(7, input.getJSONObject("reviewedTask").getJSONObject("executionScope").getInt("toolsUsed"))
        for (feedback in listOf("", " \n ", "字".repeat(8001))) assertThrows(IllegalArgumentException::class.java) { PocketWebProjectContinuation.input(original, feedback) }
    }

    @Test fun receiptRequiresExactOriginalTaskConversationAndARealNewAttempt() {
        val original = task(); val receipt = JSONObject().put("taskId", original.id).put("conversationId", "original-conversation").put("continued", true).put("runId", "new-run").put("epoch", 5)
        assertTrue(PocketWebProjectContinuation.matchesReceipt(original, receipt))
        for ((field, value) in listOf("taskId" to "other", "conversationId" to "other", "continued" to "true", "runId" to "old-run", "epoch" to "5", "epoch" to 4)) {
            assertFalse(field, PocketWebProjectContinuation.matchesReceipt(original, JSONObject(receipt.toString()).put(field, value)))
        }
        assertFalse(PocketWebProjectContinuation.matchesReceipt(original, JSONObject().put("accepted", true)))
    }
}
