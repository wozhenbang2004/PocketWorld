package dev.pocketengine.ui

import org.json.JSONObject

internal data class RemoteApprovalTarget(val profileId: String, val jobId: String, val approvals: List<JSONObject>)

internal fun remoteApprovalTarget(task: UiTask): RemoteApprovalTarget? {
    if (task.raw.optString("feature") != "remote") return null
    val evidence = task.raw.optJSONObject("evidence") ?: JSONObject()
    val profile = task.raw.optJSONObject("externalRefs")?.optString("profileId").orEmpty().ifBlank { evidence.optString("profileId") }
    val job = task.raw.optString("externalId").ifBlank { evidence.optString("id") }
    if (profile.isBlank() || job.isBlank()) return null
    val approvals = (task.raw.optJSONArray("pendingApprovals") ?: evidence.optJSONArray("pendingApprovals")).objects()
    return RemoteApprovalTarget(profile, job, approvals)
}

internal fun remoteOwnerReviewMatches(snapshot: JSONObject, taskId: String): Boolean = snapshot.optString("taskId") == taskId && snapshot.optString("windowId").isNotBlank() && snapshot.optString("reviewId").isNotBlank() && snapshot.opt("fresh") == true

internal fun remoteApprovalClaim(task: JSONObject?, requestId: String): JSONObject? = task?.optJSONArray("operations").objects().find { it.optString("tool") == "remote_approval" && it.optString("runId") == task?.optString("runId") && it.optJSONObject("arguments")?.optString("requestId") == requestId }

/** Read policy for this page only; never a task admission, write retry, or remote liveness claim. */

internal fun remoteReadShouldPoll(snapshot: JSONObject): Boolean {
    if (snapshot.optString("queryStatus") in setOf("unconfirmed", "changed", "paused")) return false
    if (snapshot.optString("eventReadStatus") == "not_available") return false // Keep the original cursor/cache; manual reconnect may query again.
    if (snapshot.optString("eventReadStatus") == "confirmed" && snapshot.optJSONObject("eventRead")?.optBoolean("hasMore") == true) return true // Original fixed-head read only, including terminal jobs.
    val task = snapshot.optJSONObject("task")
    if (task?.optBoolean("cancelRequested") == true || task?.optString("status") in setOf("cancelled", "completed", "stopping")) return false
    return snapshot.optJSONObject("source")?.optString("executorStatus") in setOf("queued", "running", "stopping", "awaiting_approval", "awaiting_input", "unknown")
}
