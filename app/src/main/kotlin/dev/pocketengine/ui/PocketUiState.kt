package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject

internal data class UiMessage(val role: String, val content: String, val at: Long, val taskId: String? = null, val id: String? = null, val submission: JSONObject? = null, val attachmentCount: Int = 0, val attachments: List<UiChatAttachment> = emptyList(), val sourceRevision: Long = 1, val attachmentFiles: List<JSONObject> = emptyList(), val memoryReference: JSONObject? = null)
internal data class UiAttachmentOpen(val checking: Boolean = false, val requested: Boolean = false, val error: String? = null)
internal data class UiConversation(val id: String, val title: String, val updatedAt: Long)
internal data class UiProject(val id: String, val name: String)
internal data class UiFile(val path: String, val version: Int)
internal data class UiFileRevision(val version: Int, val at: Long, val characters: Int, val deleted: Boolean)
internal data class UiLaunchApp(val packageName: String, val label: String)
internal data class UiTask(
    val id: String,
    val goal: String,
    val status: String,
    val summary: String,
    val executionMode: String,
    val updatedAt: Long,
    val raw: JSONObject
)
internal data class PocketUiState(
    val modelName: String = "",
    val baseUrl: String = "",
    val hasApiKey: Boolean = false,
    val screenshots: Boolean = true,
    val localCaptureAllowed: Boolean = true,
    val localOcrAllowed: Boolean = true,
    val supportsImageInput: Boolean = false,
    val modelProvider: String = "openai_chat",
    val modelRoute: String = "api_key",
    val modelCatalogEnabled: Boolean = false,
    val maxOutputTokens: Int = 4096,
    val cleartextConsent: String = "",
    val accessibilityEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val phoneReadiness: PhoneReadinessState = PhoneReadinessState(),
    val activeTaskId: String? = null,
    val tasks: List<UiTask> = emptyList(),
    val conversations: List<UiConversation> = emptyList(),
    val conversationId: String? = null,
    val messages: List<UiMessage> = emptyList(),
    val modelDrafts: Map<String, UiModelDraft> = emptyMap(),
    val chatBusy: Boolean = false,
    val chatSubmitting: Boolean = false,
    val chatActiveTurn: JSONObject? = null,
    val chatSendMode: String = "queue",
    val chatReceipts: Map<String, JSONObject> = emptyMap(),
    val selectedChatAttachments: List<UiChatAttachment> = emptyList(),
    val attachmentOpens: Map<String, UiAttachmentOpen> = emptyMap(),
    val chatAttachmentPickerOpen: Boolean = false,
    val chatAttachmentModel: JSONObject = JSONObject(),
    val phoneSubmitting: Boolean = false,
    val acceptedInputRevision: Int = 0,
    val acceptedInput: String = "",
    val suggestedInputRevision: Int = 0,
    val suggestedInput: String = "",
    val suggestedPhoneTask: Boolean? = null,
    val savedFeedbackRevision: Int = 0,
    val savedFeedbackTaskId: String? = null,
    val savedFeedback: String = "",
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val speechAvailable: Boolean = false,
    val projects: List<UiProject> = emptyList(),
    val projectId: String? = null,
    val files: List<UiFile> = emptyList(),
    val filePath: String? = null,
    val fileContent: String = "",
    val fileBaseline: String = "",
    val fileVersion: Int = 0,
    val fileLoading: Boolean = false,
    val fileSaving: Boolean = false,
    val fileReady: Boolean = false,
    val fileError: String? = null,
    val fileHistory: List<UiFileRevision> = emptyList(),
    val revisionContent: String? = null,
    val revisionNumber: Int? = null,
    val revisionLoading: Boolean = false,
    val lastSavedVersion: Int? = null,
    val workspaceArtifactView: UiWorkspaceArtifactView? = null,
    val workspaceArtifactEditorOrigin: UiArtifactEditorOrigin? = null,
    val workspaceArtifactsReadAt: Long = 0,
    val projectTaskReturn: UiProjectTaskReturn? = null,
    val requestedProjectId: String? = null,
    val requestedProjectRevision: Int = 0,
    val features: JSONObject = JSONObject(),
    val ownerSnapshots: Map<String, JSONObject> = emptyMap(),
    val ownerResponses: Map<String, Map<String, JSONObject>> = emptyMap(),
    val ownerPending: Set<String> = emptySet(),
    val ownerErrors: Map<String, String> = emptyMap(),
    val ownerSource: JSONObject? = null,
    val memoryRefreshRevision: Long = 0,
    val moduleResults: Map<String, JSONObject> = emptyMap(),
    val moduleResultInputs: Map<String, JSONObject> = emptyMap(),
    val teamOwnerReceipts: Map<String, JSONObject> = emptyMap(),
    val groupOwnerReceipts: Map<String, JSONObject> = emptyMap(),
    val moduleBusy: Set<String> = emptySet(),
    val moduleErrors: Map<String, String> = emptyMap(),
    val taskActionErrors: Map<String, String> = emptyMap(),
    val backgroundSetupOpen: Boolean = false,
    val requestedPhoneMode: String? = null,
    val requestedPhoneModeRevision: Int = 0,
    val inspectionTaskId: String? = null,
    val inspectionObservation: JSONObject? = null,
    val inspectionBusy: Boolean = false,
    val inspectionMessage: String? = null,
    val inspectionError: String? = null,
    val inspectionManualAllowed: Boolean = false,
    val launchApps: List<UiLaunchApp> = emptyList(),
    val attachmentImporting: Boolean = false,
    val exportPending: Boolean = false,
    val approvalRequests: Set<String> = emptySet(),
    val remoteApprovalRequests: Set<String> = emptySet(),
    val requestedTaskId: String? = null,
    val requestedTaskRevision: Int = 0,
    val requestedConnectionSection: String? = null,
    val requestedConnectionRevision: Int = 0,
    val requestedCommitmentId: String? = null,
    val requestedFollowUpClaimId: String? = null,
    val requestedCommitmentRevision: Int = 0,
    val teamRunId: String? = null,
    val teamAssignmentFocus: JSONObject? = null,
    val teamRuns: Map<String, JSONObject> = emptyMap(),
    val teamMemberProbes: Map<String, JSONObject> = emptyMap(),
    val setupCommands: List<String> = emptyList(),
    val error: String? = null,
    val notice: String? = null,
    val modelTest: String? = null,
    val modelTestTaskId: String? = null,
    val modelSaving: Boolean = false,
    val modelTesting: Boolean = false,
    val modelSavedRevision: Int = 0,
    val voiceInput: JSONObject = JSONObject(),
    val systemSpeech: JSONObject = JSONObject()
) {
    fun feature(id: String): JSONObject = features.optJSONObject(id) ?: JSONObject()
    fun ownerSnapshot(domain: String): JSONObject = ownerSnapshots[domain] ?: JSONObject()
    fun ownerResponses(domain: String): Map<String, JSONObject> = ownerResponses[domain] ?: emptyMap()
    fun ownerPending(domain: String): Set<String> = ownerPending.filter { it.startsWith("$domain.") }.map { it.substringAfter('.') }.toSet()
}
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun taskFromJson(value: JSONObject): UiTask = UiTask(
    value.optString("id"), value.optString("goal"), value.optString("status"), value.optString("summary"),
    value.optString("executionMode", "background"), value.optLong("updatedAt"), JSONObject(value.toString())
)
internal fun taskStatus(status: String): String = when (status) {
    "queued" -> "等待执行"
    "preparing" -> "准备中"
    "running" -> "执行中"
    "pausing" -> "正在暂停"
    "paused" -> "已暂停"
    "stopping", "cancelling" -> "正在停止"
    "verifying" -> "正在核对"
    "needs_confirmation" -> "请确认结果"
    "awaiting_user", "waiting_user" -> "等待你处理"
    "waiting_approval", "awaiting_approval" -> "等待操作授权"
    "interrupted" -> "已中断，等待恢复"
    "completed" -> "已完成"
    "failed" -> "未完成"
    "cancelled", "canceled", "stopped" -> "已停止"
    "waiting_permission", "awaiting_permission" -> "等待授权"
    "reconciling", "reconciliation_required", "unknown" -> "等待核对动作"
    else -> status.ifBlank { "待处理" }
}
internal fun unresolvedOperations(task: UiTask): List<JSONObject> {
    val operations = task.raw.optJSONArray("operations")
        ?: task.raw.optJSONObject("checkpoint")?.optJSONArray("operations")
        ?: task.raw.optJSONObject("recovery")?.optJSONArray("operations")
    val selected = operations.objects().filter {
        val nativeSkill = it.optJSONObject("arguments")?.optJSONObject("nativeSkill")
        val unclaimedSkill = it.optString("tool") == "skills_execute" && nativeSkill?.optString("format") == "pocketworld.native-skill-operation.v1" && !nativeSkill.has("claimedAt")
        !unclaimedSkill && (it.optString("state", it.optString("status")) in setOf("prepared", "unknown", "dispatching", "uncertain", "reconciliation_required", "pending_reconciliation") || it.optBoolean("requiresReconciliation"))
    }
    // A bounded window cannot prove that no pending operation exists outside it.
    return if (selected.isEmpty() && task.raw.optBoolean("historyHasUnconfirmedOperations", task.raw.optBoolean("hasUnresolvedOperations"))) listOf(JSONObject().put("summary", "原任务还有未展开的在途或待核对操作；请查看原操作记录").put("requiresReconciliation", true)) else selected
}

/** Summary refreshes must not discard the action receipts fetched for an open task. */
internal fun mergeTaskSummary(incoming: UiTask, previous: UiTask?): UiTask {
    if (previous == null) return incoming
    val remoteEpoch = incoming.raw.optString("feature") == "remote" && previous.raw.optString("feature") == "remote" && incoming.raw.has("coordinatorEpoch") && previous.raw.has("coordinatorEpoch")
    if (remoteEpoch && incoming.raw.optInt("coordinatorEpoch") < previous.raw.optInt("coordinatorEpoch")) return previous
    val newerRemoteEpoch = remoteEpoch && incoming.raw.optInt("coordinatorEpoch") > previous.raw.optInt("coordinatorEpoch")
    if (!newerRemoteEpoch && incoming.updatedAt < previous.updatedAt) return previous
    val sameRun = incoming.raw.optString("runId") == previous.raw.optString("runId") && incoming.raw.optInt("coordinatorEpoch") == previous.raw.optInt("coordinatorEpoch")
    if (sameRun && incoming.raw.optString("taskProjection") == "summary" && incoming.updatedAt == previous.updatedAt && previous.raw.optString("taskProjection") == "detail_window") {
        val retained = JSONObject(previous.raw.toString())
        incoming.raw.keys().forEach { key -> if (key !in setOf("events", "taskProjection", "auditBodyIncluded")) retained.put(key, incoming.raw.get(key)) }
        // The original native version has not advanced. Keep only this already bounded detail
        // window; any newer task state must be re-read, and no retained body authorizes an action.
        return incoming.copy(raw = retained)
    }
    if (sameRun && !incoming.raw.has("operations") && previous.raw.has("operations")) incoming.raw.put("operations", previous.raw.getJSONArray("operations"))
    val evidence = incoming.raw.optJSONObject("evidence")
    val oldEvidence = previous.raw.optJSONObject("evidence")
    if (evidence != null && oldEvidence != null && oldEvidence.has("imageDataUrl") && evidence.optString("imageFile").isNotBlank() && evidence.optString("imageFile") == oldEvidence.optString("imageFile")) {
        evidence.put("imageDataUrl", oldEvidence.getString("imageDataUrl"))
    }
    return incoming
}

internal fun executionLocation(mode: String): String = when (mode) {
    "background" -> "后台独立屏"
    "main_screen" -> "手机主屏"
    "remote" -> "电脑环境"
    "analysis" -> "协作分析"
    "tool" -> "扩展工具"
    "capabilities" -> "助手工具任务"
    else -> "执行位置待确认"
}

internal fun isPhoneTask(task: UiTask): Boolean = task.raw.optString("kind", "phone") == "phone" && task.executionMode in setOf("background", "main_screen")

internal fun pendingCapabilityApprovals(task: UiTask): List<JSONObject> = task.raw.optJSONArray("approvals").objects().filter { it.optString("state", it.optString("status")) == "pending" && it.optLong("expiresAt", Long.MAX_VALUE) > System.currentTimeMillis() }

internal fun ownerConfirmedExternalNotOccurred(result: JSONObject, tasks: List<UiTask>): Boolean {
    val taskId = result.optString("hostTaskId")
    if (taskId.isBlank()) return false
    return tasks.find { it.id == taskId }?.raw?.optJSONObject("evidence")?.optJSONObject("ownerReconciliation")?.optString("outcome") == "not_occurred"
}

/** The immutable native origin identifies the entry point, not a new execution permission. */
internal fun taskOriginLabel(task: UiTask): String? = when (task.raw.optJSONObject("origin")?.optString("kind")) {
    "channel" -> "主人私聊"
    "automation" -> "提醒来源"
    "owner_reviewed_realtime" -> "语音复核"
    else -> null
}
