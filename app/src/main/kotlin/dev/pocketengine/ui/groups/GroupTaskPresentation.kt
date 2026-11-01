package dev.pocketengine.ui.groups

import org.json.JSONObject

/** Navigation hints from an already-read native receipt. The host re-reads and authorizes. */
data class GroupTaskReviewSelection(
    val requestId: String,
    val taskId: String,
    val spaceId: String,
    val taskRevision: Long,
    val runId: String,
    val coordinatorEpoch: Int,
    val intent: String
)

/** Opaque artifact identity only. A selection never supplies a URL, file path or permission. */
data class GroupArtifactSelection(
    val requestId: String,
    val taskId: String,
    val spaceId: String,
    val outputId: String,
    val artifactId: String,
    val audience: String,
    val visibility: String,
    val mimeType: String,
    val bytes: Long,
    val taskRunId: String,
    val coordinatorEpoch: Int,
    val receiptRevision: Long,
    val sourceJobId: String? = null,
    val sourceOperationId: String? = null,
    val sourceTaskRunId: String? = null,
    val sourceCoordinatorEpoch: Int? = null,
    val deliveryId: String? = null
)

internal data class GroupArtifactHint(val sourceJobId: String?, val sourceOperationId: String?, val sourceTaskRunId: String?, val sourceCoordinatorEpoch: Int?, val displayName: String?)

/** Optional TaskStore projection supplied by the native host, never persisted by this leaf. */
internal data class GroupTaskPresentation(
    val requestId: String,
    val taskId: String,
    val spaceId: String,
    val taskRevision: Long,
    val runId: String,
    val coordinatorEpoch: Int,
    val status: String,
    val observedAt: Long,
    val phase: String,
    val progressMessage: String,
    val completedSteps: Long?,
    val totalSteps: Long?,
    val approvalState: String,
    val approvalMessage: String,
    val recoveryMessage: String,
    val canReviewContinue: Boolean,
    internal val artifactHints: Map<String, GroupArtifactHint>
) {
    companion object {
        val statuses = setOf("preparing", "queued", "running", "paused", "stopping", "awaiting_user", "needs_confirmation",
            "completed", "failed", "cancelled", "interrupted", "reconciling")
        private val approvals = setOf("not_required", "required", "pending", "approved", "denied", "revoked")
        private fun integer(value: JSONObject, key: String): Long? = value.opt(key).let {
            if (it is Int || it is Long) (it as Number).toLong() else null
        }
        private fun text(value: JSONObject?, key: String, limit: Int = 3000): String =
            (value?.opt(key) as? String)?.take(limit).orEmpty()
        private fun id(value: JSONObject, key: String): String = (value.opt(key) as? String).orEmpty().also {
            require(it.matches(Regex("[A-Za-z0-9_:.@+\\-]{1,240}"))) { "原任务展示身份不完整" }
        }

        /** Invalid or stale optional presentation is ignored; actual TaskReceipt remains visible. */
        fun read(reference: JSONObject?, value: JSONObject?, task: JSONObject? = null): GroupTaskPresentation? {
            if (reference == null || value == null) return null
            return runCatching {
                val requestId = id(value, "requestId"); val taskId = id(value, "taskId"); val spaceId = id(value, "spaceId")
                require(requestId == reference.opt("requestId") && taskId == reference.opt("taskId") && spaceId == reference.opt("spaceId"))
                val revision = integer(value, "taskRevision") ?: error("缺少原任务版本")
                val epoch = integer(value, "coordinatorEpoch") ?: error("缺少原任务代次")
                val run = text(value, "runId", 240); val status = text(value, "status", 40)
                val observed = integer(value, "observedAt") ?: error("缺少原任务读取时间")
                require(revision > 0 && epoch in 0L..Int.MAX_VALUE.toLong() && observed > 0 && status in statuses)
                require(run.isEmpty() && epoch == 0L || run.matches(Regex("[a-f0-9-]{36}")) && epoch > 0)
                if (task != null) require(taskId == task.opt("taskId") && revision == integer(task, "revision") &&
                    run == task.opt("runId") && epoch == integer(task, "coordinatorEpoch") && status == task.opt("status"))
                val progress = value.optJSONObject("progress"); val approval = value.optJSONObject("approval")
                val recovery = value.optJSONObject("recovery")
                val completed = progress?.let { integer(it, "completedSteps") }
                val total = progress?.let { integer(it, "totalSteps") }
                val validCounts = completed != null && total != null && completed >= 0 && total > 0 && completed <= total
                val hints = GroupUiState.rows(value.optJSONArray("artifactHints")).mapNotNull { hint ->
                    val outputId = text(hint, "outputId", 240)
                    val sourceEpoch = integer(hint, "sourceCoordinatorEpoch")?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
                    if (outputId.isBlank()) null else outputId to GroupArtifactHint(
                        text(hint, "sourceJobId", 240).takeIf(String::isNotBlank), text(hint, "sourceOperationId", 240).takeIf(String::isNotBlank),
                        text(hint, "sourceTaskRunId", 240).takeIf(String::isNotBlank), sourceEpoch, text(hint, "displayName", 200).takeIf(String::isNotBlank))
                }.toMap()
                GroupTaskPresentation(requestId, taskId, spaceId, revision, run, epoch.toInt(), status, observed,
                    text(progress, "phase", 120), text(progress, "message"), if (validCounts) completed else null,
                    if (validCounts) total else null, text(approval, "state", 40).takeIf { it in approvals }.orEmpty(),
                    text(approval, "message"), text(recovery, "message"), recovery?.opt("canReviewContinue") == true, hints)
            }.getOrNull()
        }
    }
}

internal fun GroupTaskPresentation.reviewSelection(intent: String) = GroupTaskReviewSelection(
    requestId, taskId, spaceId, taskRevision, runId, coordinatorEpoch, intent
)

internal fun groupArtifactSelection(reference: JSONObject, task: JSONObject, output: JSONObject,
    presentation: GroupTaskPresentation?, deliveryId: String?): GroupArtifactSelection {
    check(task.opt("taskId") == reference.opt("taskId") && task.opt("resultPrivate") != true) { "此结果当前不可查看" }
    val artifact = output.optString("artifactId")
    check(artifact.matches(Regex("[A-Za-z0-9_:.@+\\-]{1,240}")) && output.optString("mimeType") != "text/plain") { "产物缺少可核对的附件身份" }
    check(output.opt("spaceId") == reference.opt("spaceId") && output.opt("audience") == reference.opt("audience")) { "产物空间或受众不匹配" }
    val revision = GroupUiState.revision(task); check(revision > 0) { "请先读取实际任务版本" }
    val hint = presentation?.artifactHints?.get(output.getString("outputId"))
    return GroupArtifactSelection(reference.getString("requestId"), task.getString("taskId"), reference.getString("spaceId"),
        output.getString("outputId"), artifact, reference.getString("audience"), reference.getString("visibility"),
        output.getString("mimeType"), output.getLong("bytes"), task.optString("runId"), task.optInt("coordinatorEpoch"),
        revision, hint?.sourceJobId, hint?.sourceOperationId, hint?.sourceTaskRunId, hint?.sourceCoordinatorEpoch, deliveryId)
}
