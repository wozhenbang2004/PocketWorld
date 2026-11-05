package dev.pocketengine.ui

import dev.pocketengine.store.TaskStore
import org.json.JSONObject

internal object MediaOwnerUi {
    fun isTask(task: JSONObject): Boolean = task.optJSONObject("nativeEventTypeFlags")?.optBoolean("media_owner_prepared") == true || task.optJSONArray("events").objects().any { it.optString("type") == "media_owner_prepared" } || task.optString("feature")=="media" && operation(task) != null
    fun operation(task: JSONObject): JSONObject? = task.optJSONArray("operations").objects().singleOrNull { it.optString("tool") in setOf("media_workflow", "media_generate_image", "media_generate_video", "media_edit_image", "media_synthesize_speech") }
    fun prepared(receipt: JSONObject?, metadata: JSONObject?, requestId: String?, command: String, arguments: JSONObject): JSONObject? {
        if (requestId.isNullOrBlank() || metadata?.optString("_requestId") != requestId || receipt == null) return null
        if (receipt.optString("status") != "awaiting_owner_submission" || receipt.opt("dispatched") != false || receipt.optString("taskId").isBlank()) return null
        if (receipt.optString("submissionCommand") != command || receipt.optString("configurationRevision") != arguments.optString("configurationRevision")) return null
        if (receipt.optString("requestHash") != TaskStore.capabilityFingerprint(arguments)) return null
        return JSONObject(arguments.toString()).put("taskId", receipt.getString("taskId"))
    }
    fun originalDownloadReview(receipt: JSONObject?, metadata: JSONObject?, requestId: String?, binding: JSONObject): JSONObject? {
        if (requestId.isNullOrBlank() || metadata?.optString("_requestId") != requestId || metadata.optBoolean("_failed") || receipt == null) return null
        val review = receipt.optJSONObject("resultDownloadReview") ?: return null
        for (field in listOf("operationId", "taskId", "runId", "configurationRevision")) {
            if (binding.optString(field).isBlank() || review.optString(field) != binding.optString(field) || receipt.optString(field) != binding.optString(field)) return null
        }
        if (binding.optInt("epoch") < 1 || review.optInt("epoch", -1) != binding.optInt("epoch") || receipt.optInt("hostEpoch", -1) != binding.optInt("epoch")) return null
        if (review.optString("reviewId").isBlank() || review.optLong("expiresAt") <= System.currentTimeMillis() || review.optString("method") != "GET" || review.optInt("maxAttempts") != 1 || review.opt("newGeneration") != false || review.opt("redirects") != false) return null
        if (!review.optString("providerResponseSha256").matches(Regex("[a-f0-9]{64}")) || !review.optString("resultUrlSha256").matches(Regex("[a-f0-9]{64}"))) return null
        return JSONObject(review.toString())
    }
    fun binding(task: JSONObject, operation: JSONObject): JSONObject = JSONObject().put("operationId", operation.getString("operationId"))
        .put("taskId", task.getString("id")).put("runId", operation.getString("runId")).put("epoch", task.getInt("coordinatorEpoch"))
        .put("configurationRevision", operation.getJSONObject("arguments").getString("configurationRevision"))
}
