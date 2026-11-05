package dev.pocketengine.ui.web

import dev.pocketengine.ui.UiTask
import dev.pocketengine.ui.objects
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The projected attention state cannot create authority beyond the original native review. */
class PocketWebTaskActionsTest {
    private fun task(tool: String = "project_execute"): UiTask {
        val raw = JSONObject().put("id", "original-task").put("kind", "assistant")
            .put("executionMode", "capabilities")
            .put("actorId", "owner").put("spaceId", "private").put("status", "completed")
            .put("runId", "original-run").put("coordinatorEpoch", 7)
            .put("operations", JSONArray().put(JSONObject().put("operationId", "original-operation")
                .put("runId", "original-run").put("tool", tool).put("state", "unknown")
                .put("receipt", JSONObject().put("accepted", JSONObject.NULL))))
        return UiTask("original-task", "核对原任务", "completed", "保留原结果", "capabilities", 1L, raw)
    }

    private fun actions(view: JSONObject) = view.getJSONArray("actions").objects().map { it.getString("action") }.toSet()

    @Test fun terminalTaskKeepsTheOriginalUnknownOperationReview() {
        val original = task()
        val reviews = PocketWebTaskReviews { 1_000L }
        val view = PocketWebTaskActions.review(original, JSONArray(), reviews, null, true)
        assertEquals(setOf("tasks.resolve"), actions(view))
        assertEquals("original-operation", view.getJSONArray("unresolvedOperations").getJSONObject(0).getString("operationId"))
        val handle = view.getString("reviewHandle")
        assertEquals(original.id, reviews.take(handle, "tasks.resolve", listOf(original)).id)
        assertThrows(IllegalStateException::class.java) { reviews.take(handle, "tasks.resolve", listOf(original)) }
    }

    @Test fun unknownOutsideTheReadablePageDoesNotInventAnOperationDecision() {
        val original = task().apply {
            raw.put("operations", JSONArray()).put("historyHasUnconfirmedOperations", true)
        }
        val reviews = PocketWebTaskReviews { 1_000L }
        val view = PocketWebTaskActions.review(original, JSONArray(), reviews, null, true)
        assertFalse(actions(view).contains("tasks.resolve"))
        assertEquals(1, view.getJSONArray("unresolvedOperations").length())
        assertTrue(view.getJSONArray("unresolvedOperations").getJSONObject(0).optBoolean("requiresReconciliation"))
        assertThrows(IllegalStateException::class.java) { reviews.take(view.getString("reviewHandle"), "tasks.resolve", listOf(original)) }
    }

    @Test fun deviceUnknownOperationsKeepTheirDedicatedReconciliationPath() {
        for (tool in listOf("device_invoke_tool", "device_delegate_goal", "device_operation_get")) {
            val original = task(tool)
            val reviews = PocketWebTaskReviews { 1_000L }
            val view = PocketWebTaskActions.review(original, JSONArray(), reviews, null, true)
            assertFalse(tool, actions(view).contains("tasks.resolve"))
            assertEquals(tool, 0, view.getJSONArray("unresolvedOperations").length())
            assertThrows(IllegalStateException::class.java) { reviews.take(view.getString("reviewHandle"), "tasks.resolve", listOf(original)) }
        }
    }

    @Test fun dedicatedTasksAndUnavailableControlWindowsCannotGainGenericActions() {
        val dedicated = task().apply { raw.put("ownerGit", JSONObject()) }
        val ordinary = task()
        val reviews = PocketWebTaskReviews { 1_000L }
        for ((original, controls) in listOf(dedicated to true, ordinary to false)) {
            val view = PocketWebTaskActions.review(original, JSONArray(), reviews, null, controls)
            assertTrue(actions(view).isEmpty())
            assertThrows(IllegalStateException::class.java) { reviews.take(view.getString("reviewHandle"), "tasks.resolve", listOf(original)) }
        }
    }
}
