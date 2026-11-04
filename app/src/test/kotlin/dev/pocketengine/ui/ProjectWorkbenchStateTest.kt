package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProjectWorkbenchStateTest {
    @Test fun legacyContinuationPresentationNeverRevivesStoppedOrCancelledTasks() {
        for (status in listOf("cancelled", "canceled", "stopped", "waiting_permission", "awaiting_permission"))
            assertFalse(status, ProjectWorkbenchGuard.canContinue(task().copy(status = status)))
        for (status in listOf("completed", "failed", "paused", "interrupted", "needs_confirmation", "awaiting_user"))
            assertTrue(status, ProjectWorkbenchGuard.canContinue(task().copy(status = status)))
        assertFalse(ProjectWorkbenchGuard.canContinue(task().also { it.raw.put("cancelRequested", true) }))
    }
    private fun task(id: String = "original-task", at: Long = 1): UiTask = taskFromJson(JSONObject()
        .put("id", id).put("kind", "assistant").put("actorId", "owner").put("spaceId", "private")
        .put("status", "failed").put("goal", "修复这个网页").put("updatedAt", at).put("runId", "run-one")
        .put("developmentContext", JSONObject().put("projectId", "project").put("checkoutId", "checkout").put("runtime", "managed_process_v1"))
        .put("operations", JSONArray()))

    private fun preview(id: String = "a".repeat(64)) = JSONObject().put("previewId", id).put("projectId", "project").put("checkoutId", "checkout").put("sourceDigest", "b".repeat(64))

    private fun operation(taskId: String = "original-task", id: String = "operation-one", intent: String = "build"): JSONObject = JSONObject()
        .put("operationId", id).put("runId", "run-one").put("tool", "project_execute").put("state", "receipt")
        .put("arguments", JSONObject().put("stepId", intent))
        .put("receipt", JSONObject().put("binding", JSONObject().put("taskId", taskId).put("runId", "run-one").put("operationId", id))
            .put("checkoutId", "checkout").put("projectId", "project").put("sourceDigest", "c".repeat(64)).put("intent", intent)
            .put("status", "failed").put("receiptReceived", true).put("exitCode", 1).put("stderr", "SyntaxError: actual source line").put("stdout", ""))

    @Test fun aNewerOrRunningTaskCannotTakeOverAnExplicitOriginalSelection() {
        val original = task()
        val newer = task("another-task", 30).copy(status = "running")
        val candidates = ProjectWorkbenchGuard.projectTasks(listOf(original, newer), "checkout", "project")
        assertEquals("another-task", ProjectWorkbenchGuard.selectedTask(candidates, null)?.id)
        assertSame(original, ProjectWorkbenchGuard.selectedTask(candidates, original.id))
        assertNull("A removed task must not silently become another task", ProjectWorkbenchGuard.selectedTask(candidates, "removed"))
        val crossProject = task("wrong-project").also { it.raw.getJSONObject("developmentContext").put("projectId", "other") }
        val remoteOrigin = task("remote-origin").also { it.raw.put("origin", JSONObject().put("kind", "channel")) }
        assertEquals(listOf(original), ProjectWorkbenchGuard.projectTasks(listOf(crossProject, remoteOrigin, original), "checkout", "project"))
    }

    @Test fun continuationDraftIsAcknowledgedOnlyByItsExactOriginalTaskAndRequest() {
        val result = JSONObject().put("taskId", "original-task").put("continued", true)
        val input = JSONObject().put("taskId", "original-task").put("_requestId", "request-one")
        assertSame(result, ProjectWorkbenchGuard.ownerReceipt(result, input, "request-one", "original-task"))
        assertNull(ProjectWorkbenchGuard.ownerReceipt(result, input, "late-request", "original-task"))
        assertNull(ProjectWorkbenchGuard.ownerReceipt(result, input, "request-one", "another-task"))
        input.put("_failed", true)
        assertNull("A stale success cannot clear a failed request's draft", ProjectWorkbenchGuard.ownerReceipt(result, input, "request-one", "original-task"))
        assertTrue(ProjectWorkbenchGuard.failedRequest(input, "request-one", "original-task"))
        assertFalse(ProjectWorkbenchGuard.failedRequest(input, "request-one", "another-task"))
    }

    @Test fun unconfirmedNativeAdmissionIsNotConfusedWithAQueuedTaskOrAnotherClient() {
        val pending = JSONObject().put("status", "unconfirmed").put("clientMessageId", "client-one").put("messageId", "original-message")
        val input = JSONObject().put("_requestId", "request-one")
        assertSame(pending, ProjectWorkbenchGuard.unconfirmedSubmission(pending, input, "request-one", "client-one"))
        assertNull(ProjectWorkbenchGuard.ownerReceipt(pending, input, "request-one"))
        assertNull(ProjectWorkbenchGuard.unconfirmedSubmission(pending, input, "another-request", "client-one"))
        assertNull(ProjectWorkbenchGuard.unconfirmedSubmission(pending, input, "request-one", "another-client"))
        assertNull(ProjectWorkbenchGuard.unconfirmedSubmission(JSONObject(pending.toString()).put("taskId", "known-task").put("status", "queued"), input, "request-one", "client-one"))
    }

    @Test fun cachedPreviewNeedsItsExactOriginalTaskLedgerEvenForTheSameCheckout() {
        val original = task()
        val bytes = preview()
        original.raw.put("developmentPreviews", JSONArray().put(preview()))
        assertTrue(ProjectWorkbenchGuard.previewBelongsToTask(original.raw, bytes))
        assertFalse(ProjectWorkbenchGuard.previewBelongsToTask(task("another-task").raw, bytes))
        for (field in listOf("previewId", "sourceDigest", "checkoutId", "projectId")) {
            val changed = JSONObject(bytes.toString()).put(field, "different")
            assertFalse(field, ProjectWorkbenchGuard.previewBelongsToTask(original.raw, changed))
        }
        original.raw.put("actorId", "remote")
        assertFalse(ProjectWorkbenchGuard.previewBelongsToTask(original.raw, bytes))
    }

    @Test fun observedBytesNeverTurnAnUnknownTotalOrFullDownloadIntoExecutionReadiness() {
        val unknown = ProjectPreparationProgress.from(JSONObject().put("phase", "download_runtime").put("completedBytes", 4096).put("resumableDownload", true))!!
        assertNull(unknown.fraction); assertEquals(4096L, unknown.completedBytes); assertTrue(unknown.resumableDownload)
        val full = ProjectPreparationProgress.from(JSONObject().put("phase", "verifying_download").put("completedBytes", 4096).put("totalBytes", 4096))!!
        assertEquals(1f, full.fraction!!, 0f); assertEquals("verifying_download", full.phase)
        for (total in listOf(0, -1, "unknown")) assertNull(ProjectPreparationProgress.from(JSONObject().put("phase", "downloading").put("completedBytes", 4096).put("totalBytes", total))!!.fraction)
        assertNull(ProjectPreparationProgress.from(JSONObject().put("phase", "downloading").put("completedBytes", 4097).put("totalBytes", 4096))!!.fraction)
        assertNull(ProjectPreparationProgress.from(JSONObject().put("phase", "downloading").put("completedBytes", "4096"))!!.completedBytes)
    }

    @Test fun v21OriginalReceiptUsesItsActualFlatPreparationFieldsAndError() {
        val receipt = JSONObject().put("preparationPhase", "interrupted").put("preparationError", "runtime_download_interrupted")
            .put("completedBytes", 4096L).put("totalBytes", 8192L)
        val progress = ProjectPreparationProgress.fromReceipt(receipt)!!
        assertEquals("interrupted", progress.phase); assertEquals("runtime_download_interrupted", progress.error)
        assertEquals(4096L, progress.completedBytes); assertEquals(8192L, progress.totalBytes)
        assertFalse("An absent original-job resume flag is not invented", progress.resumableDownload)
        assertNull(ProjectPreparationProgress.fromReceipt(JSONObject().put("preparationPhase", "")))
    }

    @Test fun v21ToolCountsAndUnknownPhasesAreNotPresentedAsDownloadedBytes() {
        val tools = ProjectPreparationProgress.from(JSONObject().put("phase", "probing_tools").put("completedBytes", 2).put("totalBytes", 8))!!
        assertFalse(tools.byteProgress); assertEquals(0.25f, tools.fraction!!, 0f)
        val download = ProjectPreparationProgress.from(JSONObject().put("phase", "downloading").put("completedBytes", 4096).put("totalBytes", 8192))!!
        assertTrue(download.byteProgress); assertEquals(0.5f, download.fraction!!, 0f)
        val unknown = ProjectPreparationProgress.from(JSONObject().put("phase", "future_native_phase").put("completedBytes", 2).put("totalBytes", 8))!!
        assertFalse(unknown.byteProgress); assertNull("Unknown phase remains indeterminate", unknown.fraction)
    }

    @Test fun repairingUsesOnlyConfirmedFailureOfTheExactNativeOperation() {
        val original = task()
        val failed = operation()
        original.raw.put("operations", JSONArray().put(failed))
        assertTrue(ProjectWorkbenchGuard.failureDraft(original)!!.contains("SyntaxError: actual source line"))
        assertNull(ProjectWorkbenchGuard.failureDraft(original, "another-operation"))
        for (field in listOf("taskId", "runId", "operationId")) {
            val wrong = JSONObject(failed.toString())
            wrong.getJSONObject("receipt").getJSONObject("binding").put(field, "different")
            assertNull(field, ProjectWorkbenchGuard.executionReceipt(original, wrong))
        }
        for (receipt in listOf(JSONObject(failed.getJSONObject("receipt").toString()).put("status", "unknown"), JSONObject(failed.getJSONObject("receipt").toString()).put("cancelRequested", true), JSONObject(failed.getJSONObject("receipt").toString()).put("receiptReceived", false))) {
            original.raw.put("operations", JSONArray().put(JSONObject(failed.toString()).put("receipt", receipt)))
            assertNull(ProjectWorkbenchGuard.failureDraft(original))
        }
    }

    @Test fun newPreparationCannotPresentAnOlderFailureAsItsCurrentProgress() {
        val original = task()
        val old = operation(intent = "prepare")
        val pending = operation(id = "next-prepare", intent = "prepare").also { it.remove("receipt"); it.put("state", "prepared") }
        original.raw.put("operations", JSONArray().put(old))
        assertNotNull(ProjectWorkbenchGuard.preparationReceipt(original))
        original.raw.put("operations", JSONArray().put(old).put(pending))
        assertNull(ProjectWorkbenchGuard.preparationReceipt(original))
        assertFalse(ProjectWorkbenchGuard.canContinue(original))
        original.raw.put("operations", JSONArray())
        assertTrue(ProjectWorkbenchGuard.canContinue(original))
        assertTrue(ProjectWorkbenchGuard.canContinue(original.copy(status = "paused")))
        assertFalse(ProjectWorkbenchGuard.canContinue(original.copy(status = "running")))
    }
}
