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

    private fun runtimeSetup(status: String = "running"): UiTask = task("native_runtime_prepare").let { original ->
        original.raw.put("kind", "tool").put("feature", "local").put("command", "runtime.setup.owner")
            .put("status", status).put("nativeExecutionMode", "OWNER_RUNTIME_SETUP")
            .put("ownerRuntimeSetup", JSONObject().put("requestId", original.id).put("modelDispatchAllowed", false))
        original.copy(status = status)
    }

    @Test fun originalPrivateRuntimeSetupCanBeStoppedFromItsTaskWindow() {
        val original = runtimeSetup()
        assertTrue(PocketWebTaskActions.runtimeSetupTask(original))
        assertTrue(PocketWebTaskActions.specialOwnerTask(original))
        assertTrue(PocketWebTaskActions.canStopDirectly(original))
        assertEquals("tool", original.raw.getString("kind"))
        assertEquals("original-run", original.raw.getString("runId"))
        assertEquals(7, original.raw.getInt("coordinatorEpoch"))
    }

    @Test fun runtimeSetupUnknownStaysVisibleWithoutGenericResolutionOrResumeAuthority() {
        val original = runtimeSetup("interrupted")
        val reviews = PocketWebTaskReviews { 1_000L }
        val view = PocketWebTaskActions.review(original, JSONArray(), reviews, null, true)
        assertTrue(actions(view).isEmpty())
        assertEquals("original-operation", view.getJSONArray("unresolvedOperations").getJSONObject(0).getString("operationId"))
        assertEquals("native_runtime_prepare", view.getJSONArray("unresolvedOperations").getJSONObject(0).getString("tool"))
        for (action in listOf("tasks.resolve", "tasks.resume", "tasks.scope", "tasks.approve")) {
            val fresh = PocketWebTaskActions.review(original, JSONArray(), reviews, null, true)
            assertThrows(IllegalStateException::class.java) {
                reviews.take(fresh.getString("reviewHandle"), action, listOf(original))
            }
        }
        assertTrue(PocketWebTaskActions.canStopDirectly(original))
    }

    @Test fun runtimeMarkerCannotMakeForeignOrMalformedTasksDirectlyStoppable() {
        val invalid = listOf<Pair<String, (JSONObject) -> Unit>>(
            "foreign actor" to { it.put("actorId", "someone-else") },
            "shared space" to { it.put("spaceId", "shared") },
            "assistant kind" to { it.put("kind", "assistant") },
            "phone kind" to { it.put("kind", "phone") },
            "missing kind" to { it.remove("kind") },
            "group origin" to { it.put("origin", JSONObject().put("kind", "group")) },
            "paired-device origin" to { it.put("origin", JSONObject().put("kind", "paired_device")) },
            "untyped origin" to { it.put("origin", JSONObject()) },
            "boolean marker" to { it.put("ownerRuntimeSetup", true) },
            "string marker" to { it.put("ownerRuntimeSetup", "true") },
            "null marker" to { it.put("ownerRuntimeSetup", JSONObject.NULL) },
        )
        for ((label, mutate) in invalid) {
            val original = runtimeSetup().apply { mutate(raw) }
            assertFalse(label, PocketWebTaskActions.runtimeSetupTask(original))
            assertFalse(label, PocketWebTaskActions.canStopDirectly(original))
        }
    }

    @Test fun runtimeStopExceptionKeepsOrdinaryAndOtherDedicatedTaskRules() {
        val ordinary = task().copy(status = "running")
        val phone = task().let { original -> original.raw.put("kind", "phone"); original.copy(status = "running", executionMode = "background") }
        assertTrue(PocketWebTaskActions.canStopDirectly(ordinary))
        assertTrue(PocketWebTaskActions.canStopDirectly(phone))
        assertFalse(PocketWebTaskActions.runtimeSetupTask(ordinary))
        for (original in listOf(
            task().apply { raw.put("executionRole", "owner_work") },
            task().apply { raw.put("ownerLocalTerminal", JSONObject()) },
            task().apply { raw.put("kind", "tool") },
        )) assertFalse(PocketWebTaskActions.canStopDirectly(original))
    }
}
