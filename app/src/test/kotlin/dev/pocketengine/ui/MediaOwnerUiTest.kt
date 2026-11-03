package dev.pocketengine.ui

import dev.pocketengine.store.TaskStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaOwnerUiTest {
    @Test fun preparationRequiresExactCurrentRequestInputAndConfiguration() {
        val args = JSONObject().put("configurationRevision", "route-v1").put("prompt", "exact owner input")
        val receipt = JSONObject().put("status", "awaiting_owner_submission").put("dispatched", false).put("taskId", "original-task").put("submissionCommand", "generateImage").put("configurationRevision", "route-v1").put("requestHash", TaskStore.capabilityFingerprint(args))
        val metadata = JSONObject().put("_requestId", "owner-request-1")
        assertEquals("original-task", MediaOwnerUi.prepared(receipt, metadata, "owner-request-1", "generateImage", args)!!.getString("taskId"))
        assertNull(MediaOwnerUi.prepared(receipt, metadata, null, "generateImage", args))
        assertNull(MediaOwnerUi.prepared(receipt, metadata, "old-owner-request", "generateImage", args))
        assertNull(MediaOwnerUi.prepared(receipt, metadata, "owner-request-1", "synthesizeSpeech", args))
        assertNull(MediaOwnerUi.prepared(receipt, metadata, "owner-request-1", "generateImage", JSONObject(args.toString()).put("prompt", "edited input")))
        assertNull(MediaOwnerUi.prepared(receipt, metadata, "owner-request-1", "generateImage", JSONObject(args.toString()).put("configurationRevision", "replacement-route")))
        assertNull(MediaOwnerUi.prepared(JSONObject(receipt.toString()).put("dispatched", true), metadata, "owner-request-1", "generateImage", args))
    }
    @Test fun originalMediaTaskDoesNotBecomeGenericAssistantResume() {
        val task = JSONObject().put("id", "task").put("coordinatorEpoch", 2).put("kind", "assistant").put("events", JSONArray().put(JSONObject().put("type", "media_owner_prepared")))
        assertTrue(MediaOwnerUi.isTask(task)); assertNull(MediaOwnerUi.operation(task))
        val op = JSONObject().put("operationId", "op").put("tool", "media_workflow").put("runId", "original-run").put("arguments", JSONObject().put("configurationRevision", "original-config"))
        task.put("operations", JSONArray().put(op)); val binding = MediaOwnerUi.binding(task, MediaOwnerUi.operation(task)!!)
        assertEquals("op", binding.getString("operationId")); assertEquals("original-run", binding.getString("runId")); assertEquals(2, binding.getInt("epoch")); assertEquals("original-config", binding.getString("configurationRevision"))
        assertFalse(MediaOwnerUi.isTask(JSONObject().put("kind", "assistant")))
    }
    @Test fun oneGetConfirmationRequiresThisRequestsExactOriginalOperationAndDigests() {
        val binding = JSONObject().put("operationId", "op").put("taskId", "task").put("runId", "run").put("configurationRevision", "original").put("epoch", 1)
        val review = JSONObject(binding.toString()).put("reviewId", "single-use").put("expiresAt", System.currentTimeMillis() + 300000).put("providerResponseSha256", "a".repeat(64)).put("resultUrlSha256", "b".repeat(64)).put("method", "GET").put("maxAttempts", 1).put("newGeneration", false).put("redirects", false)
        val result = JSONObject(binding.toString()).put("resultDownloadReview", review).put("hostEpoch", 1)
        val metadata = JSONObject().put("_requestId", "request")
        val frozen = MediaOwnerUi.originalDownloadReview(result, metadata, "request", binding)!!
        assertEquals("single-use", frozen.getString("reviewId"))
        frozen.put("resultUrlSha256", "changed")
        assertEquals("b".repeat(64), review.getString("resultUrlSha256"))
        assertNull(MediaOwnerUi.originalDownloadReview(result, metadata, "stale", binding))
        assertNull(MediaOwnerUi.originalDownloadReview(result, metadata, null, binding))
        assertNull(MediaOwnerUi.originalDownloadReview(result, JSONObject(metadata.toString()).put("_failed", true), "request", binding))
        for (field in listOf("operationId", "taskId", "runId", "configurationRevision")) {
            assertNull(MediaOwnerUi.originalDownloadReview(result, metadata, "request", JSONObject(binding.toString()).put(field, "other")))
        }
        for (field in listOf("providerResponseSha256", "resultUrlSha256")) {
            val changed = JSONObject(result.toString()); changed.getJSONObject("resultDownloadReview").put(field, "short")
            assertNull(MediaOwnerUi.originalDownloadReview(changed, metadata, "request", binding))
        }
        assertNull(MediaOwnerUi.originalDownloadReview(result, metadata, "request", JSONObject(binding.toString()).put("epoch", 2)))
        assertNull(MediaOwnerUi.originalDownloadReview(JSONObject(result.toString()).put("hostEpoch", 2), metadata, "request", binding))
        val expired = JSONObject(result.toString()); expired.getJSONObject("resultDownloadReview").put("expiresAt", 1)
        assertNull(MediaOwnerUi.originalDownloadReview(expired, metadata, "request", binding))
    }

}
