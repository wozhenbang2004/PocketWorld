package dev.pocketengine.ui.groups

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GroupTaskPresentationTest {
    private val run = "12345678-1234-1234-1234-123456789abc"
    private val originalRun = "87654321-4321-4321-4321-cba987654321"
    private fun reference() = JSONObject().put("requestId", "request-1").put("taskId", "task-1").put("spaceId", "group:one")
        .put("audience", "group").put("visibility", "members")
    private fun task(revision: Long = 8) = JSONObject().put("taskId", "task-1").put("revision", revision).put("runId", run).put("coordinatorEpoch", 2).put("status", "paused")
    private fun presentation() = JSONObject().put("requestId", "request-1").put("taskId", "task-1").put("spaceId", "group:one")
        .put("taskRevision", 8L).put("runId", run).put("coordinatorEpoch", 2).put("status", "paused").put("observedAt", 1L)
    private fun output() = JSONObject().put("outputId", "output-1").put("artifactId", "artifact:one").put("spaceId", "group:one")
        .put("audience", "group").put("mimeType", "image/png").put("bytes", 8000L)

    @Test fun presentationCannotAttachAcrossTaskSpaceVersionOrAttempt() {
        assertNotNull(GroupTaskPresentation.read(reference(), presentation(), task()))
        for ((key, value) in listOf("taskId" to "task-else", "spaceId" to "group:else", "taskRevision" to 7L, "runId" to originalRun, "coordinatorEpoch" to 1, "status" to "running")) {
            assertNull("Mismatched $key", GroupTaskPresentation.read(reference(), presentation().put(key, value), task()))
        }
    }

    @Test fun explicitNativeApprovalDenialIsNotInferredFromGoalOrSubmission() {
        val value = presentation().put("approval", JSONObject().put("state", "denied").put("message", "主人拒绝本次请求"))
        val parsed = GroupTaskPresentation.read(reference().put("goal", "假称审批已通过"), value, task())!!
        assertEquals("denied", parsed.approvalState)
        assertEquals("主人拒绝本次请求", parsed.approvalMessage)
        assertFalse(parsed.canReviewContinue)
        assertEquals("", GroupTaskPresentation.read(reference(), presentation(), task())!!.approvalState)
    }

    @Test fun malformedProgressNeverCreatesInventedCompletionAndOnlyBooleanOffersReview() {
        val value = presentation().put("progress", JSONObject().put("completedSteps", 9).put("totalSteps", 2))
            .put("recovery", JSONObject().put("canReviewContinue", "true"))
        val parsed = GroupTaskPresentation.read(reference(), value, task())!!
        assertNull(parsed.completedSteps); assertNull(parsed.totalSteps); assertFalse(parsed.canReviewContinue)
        value.getJSONObject("progress").put("completedSteps", 1)
        value.getJSONObject("recovery").put("canReviewContinue", true)
        val valid = GroupTaskPresentation.read(reference(), value, task())!!
        assertEquals(1L, valid.completedSteps); assertEquals(2L, valid.totalSteps); assertTrue(valid.canReviewContinue)
    }

    @Test fun restoredTaskKeepsOriginalArtifactRunWithoutRebindingItToCurrentAttempt() {
        val value = presentation().put("artifactHints", JSONArray().put(JSONObject().put("outputId", "output-1")
            .put("sourceJobId", "job-1").put("sourceOperationId", "operation-1").put("sourceTaskRunId", originalRun).put("sourceCoordinatorEpoch", 1)))
        val selection = groupArtifactSelection(reference(), task(), output(), GroupTaskPresentation.read(reference(), value, task()), null)
        assertEquals(run, selection.taskRunId); assertEquals(2, selection.coordinatorEpoch)
        assertEquals(originalRun, selection.sourceTaskRunId); assertEquals(1, selection.sourceCoordinatorEpoch)
        assertEquals("job-1", selection.sourceJobId); assertEquals("operation-1", selection.sourceOperationId)
        val withoutEvidence = groupArtifactSelection(reference(), task(), output(), null, null)
        assertNull(withoutEvidence.sourceTaskRunId); assertNull(withoutEvidence.sourceCoordinatorEpoch)
    }

    @Test fun artifactSelectionRejectsCrossSpacePrivateAndNonOpaqueReferences() {
        assertThrows(IllegalStateException::class.java) { groupArtifactSelection(reference(), task(), output().put("spaceId", "private"), null, null) }
        assertThrows(IllegalStateException::class.java) { groupArtifactSelection(reference(), task().put("resultPrivate", true), output(), null, null) }
        assertThrows(IllegalStateException::class.java) { groupArtifactSelection(reference(), task(), output().put("artifactId", "https://example.invalid/file"), null, null) }
    }

    @Test fun deliveryResponseIsBoundToExactTaskAndOutputAndAcceptanceIsNotDelivery() {
        val id = GroupUiState.deliveryId("request-1", "output-1", "group")
        fun receipt() = JSONObject().put("deliveryId", id).put("requestId", "request-1").put("taskId", "task-1").put("state", "accepted")
        assertEquals("accepted", GroupUiState.exactDelivery("request-1", "task-1", id, receipt()).getString("state"))
        for ((key, value) in listOf("requestId" to "request-2", "taskId" to "task-2", "deliveryId" to GroupUiState.deliveryId("request-1", "output-2", "group"))) {
            assertThrows(IllegalStateException::class.java) { GroupUiState.exactDelivery("request-1", "task-1", id, receipt().put(key, value)) }
        }
        assertThrows(IllegalStateException::class.java) { GroupUiState.exactDelivery("request-1", "task-1", id, receipt().put("state", "delivered")) }
    }

    @Test fun lateTaskReceiptCannotReplaceAReadNewerStateOrTurnItUnknown() {
        val current = JSONObject().put("task", task(8))
        assertFalse(GroupUiState.newerTask(current, JSONObject().put("task", task(7))))
        assertFalse(GroupUiState.newerTask(current, JSONObject().put("known", false)))
        assertTrue(GroupUiState.newerTask(current, JSONObject().put("task", task(9))))
    }
}
