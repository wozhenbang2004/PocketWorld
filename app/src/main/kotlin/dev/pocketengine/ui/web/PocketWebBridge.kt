package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import dev.pocketengine.core.ModelConfig
import dev.pocketengine.core.RuntimeDiagnostics
import dev.pocketengine.features.devices.NativeDeviceWorkspaceHost
import dev.pocketengine.runtime.models.ModelBinding
import dev.pocketengine.runtime.models.ReasoningOptions
import dev.pocketengine.store.TaskExecutionScope
import dev.pocketengine.ui.ChatInputRules
import dev.pocketengine.ui.MediaOwnerUi
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.accountConnectionsFailure
import dev.pocketengine.ui.UiTask
import dev.pocketengine.ui.isPhoneTask
import dev.pocketengine.ui.isDeviceWorkspaceTask
import dev.pocketengine.ui.objects
import dev.pocketengine.ui.pendingCapabilityApprovals
import dev.pocketengine.ui.unresolvedOperations
import dev.pocketengine.ui.workspaceTaskLinks
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A one-use reference to the exact task that was displayed, never a Web-created approval. */
internal class PocketWebTaskReviews(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private data class Reviewed(val task: UiTask, val identity: String, val actions: Set<String>, val expires: Long, val positiveApprovalIds: Set<String>)
    private val entries = linkedMapOf<String, Reviewed>()

    fun create(task: UiTask, actions: Set<String>, positiveApprovalIds: Set<String> = emptySet()): String {
        entries.entries.removeAll { it.value.expires <= now() }
        while (entries.size >= 24) entries.remove(entries.keys.first())
        val copy = task.copy(raw = JSONObject(task.raw.toString()))
        val handle = UUID.randomUUID().toString()
        entries[handle] = Reviewed(copy, identity(copy), actions.toSet(), now() + 120_000L, positiveApprovalIds.toSet())
        return handle
    }

    fun take(handle: String, action: String, current: List<UiTask>, positiveApprovalId: String? = null): UiTask {
        val reviewed = entries.remove(handle) ?: throw IllegalStateException("原审阅已关闭或已使用，请重新核对原任务")
        check(now() < reviewed.expires) { "原审阅已过期，请重新核对原任务" }
        check(action in reviewed.actions) { "这项操作不在原审阅范围内" }
        if (positiveApprovalId != null) check(positiveApprovalId in reviewed.positiveApprovalIds) { "尚未读取这项操作的完整范围，请重新核对" }
        val actual = current.firstOrNull { it.id == reviewed.task.id }
        check(actual != null && identity(actual) == reviewed.identity) { "原任务已变化，未沿用旧审阅执行；请重新核对" }
        return reviewed.task
    }

    fun clear() = entries.clear()

    private fun identity(task: UiTask): String = digest(listOf(task.id, task.goal, task.status, task.summary, task.executionMode, task.updatedAt.toString(), task.raw.toString()).joinToString("\u0000"))
}

private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Display eligibility only. The consumed native review is rechecked by the original SQL admission. */
internal object PocketWebProjectContinuation {
    private val ended = setOf("paused", "interrupted", "awaiting_user", "needs_confirmation", "failed", "completed")
    private val dedicated = setOf("sourceLearningJob", "teamAssignment", "teamWriteScope", "teamReservationPool", "teamProjectRoundOrigin", "evaluation", "ownerLocalTerminal", "ownerGit", "ownerGitImport", "ownerGitPublication", "ownerBackupUpload", "ownerMcpDiscovery", "ownerStorageCleanup", "ownerArchivePhase", "workProfileRef", "workProfileIntent")

    fun view(task: UiTask, active: Boolean, now: Long = System.currentTimeMillis()): JSONObject? {
        val raw = task.raw
        if (raw.optString("kind") != "assistant" || raw.optString("actorId") != "owner" || raw.optString("spaceId") != "private" ||
            raw.has("origin") && !raw.isNull("origin") || raw.optJSONObject("developmentContext") == null) return null
        val conversation = raw.opt("conversationId") as? String ?: ""
        val view = JSONObject().put("feedbackRequired", true).put("conversationId", conversation)
        val reason = when {
            dedicated.any(raw::has) || isDeviceWorkspaceTask(task) || MediaOwnerUi.isTask(raw) ||
                raw.optString("feature") == "attachments" && raw.optString("command") in setOf("createDocument", "transformImage", "trimMedia") -> "这项专用任务须回原入口处理，不能在此续接。"
            active || task.status !in ended || raw.optBoolean("cancelRequested") -> "原任务当前不能继续；已停止或取消的任务不会重新启动。"
            conversation.isBlank() -> "原项目对话身份缺失，不能改送到另一份对话。"
            raw.optString("id") != task.id || (raw.opt("runId") as? String).isNullOrBlank() || exactPositive(raw.opt("coordinatorEpoch")) == null -> "原任务执行身份缺失，请重新读取原记录。"
            raw.optString("executionMode") != "capabilities" -> "这不是原私人项目能力任务，请回原入口核对。"
            PocketWebOwnerQuestions.blocksGenericResume(raw) -> "请先回答原任务的问题，再核对是否继续。"
            raw.optJSONArray("approvals").objects().any { it.optString("state", it.optString("status")) == "pending" } || unresolvedOperations(task).isNotEmpty() -> "请先处理原任务的审批或未决操作；草稿仍保留。"
            else -> try {
                val scope = raw.optJSONObject("executionScope") ?: throw IllegalStateException("原任务范围缺失，不能自动创建新的额度。")
                when {
                    scope.optString("taskId") != task.id -> "原任务范围身份不符，请重新核对。"
                    TaskExecutionScope.effectiveDeadline(scope) <= now -> "原任务范围已到期，本次不能续期；草稿仍保留。"
                    scope.getInt("toolsUsed") >= scope.getInt("toolLimit") || scope.getInt("modelCallsUsed") >= scope.getInt("modelCallLimit") -> "原任务调用额度已用尽，本次不能增加额度；草稿仍保留。"
                    else -> null
                }
            } catch (failure: Exception) { failure.message ?: "原任务范围尚不可核对；没有重新授权。" }
        }
        if (reason != null) view.put("reason", reason)
        return view
    }

    fun input(task: UiTask, feedback: String): JSONObject {
        val text = feedback.trim()
        require(text.isNotEmpty() && text.length <= 8000) { "请填写 1–8000 字的本次修改要求" }
        return JSONObject().put("taskId", task.id).put("reviewedTask", JSONObject(task.raw.toString())).put("feedback", text)
    }

    fun matchesReceipt(task: UiTask, value: JSONObject): Boolean = (task.raw.opt("conversationId") as? String)?.isNotBlank() == true && value.opt("continued") == true &&
        value.opt("taskId") == task.id && value.opt("conversationId") == task.raw.opt("conversationId") &&
        (value.opt("runId") as? String)?.let { it.isNotBlank() && it != task.raw.optString("runId") } == true &&
        exactPositive(value.opt("epoch"))?.let { it > (exactPositive(task.raw.opt("coordinatorEpoch")) ?: Long.MAX_VALUE) } == true

    private fun exactPositive(value: Any?): Long? = if (value is Int || value is Long) (value as Number).toLong().takeIf { it > 0 && it <= Int.MAX_VALUE } else null
}

/**
 * Narrow, versioned commands for the app's single trusted local WebView.
 * The host must reject every other navigation/resource origin before adding this interface.
 * A successful command receipt means accepted by the existing controller, not a completed task.
 */
internal class PocketWebBridge(
    private val controller: PocketWorldController,
    private val emit: (JSONObject) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val presentation = PocketWebPresentation(controller.webPresentationActivity()) { publishState() }
    private val background = PocketWebBackground(controller, ::captureWindow, ::acceptsWindow) { publishState() }
    private val remoteInbox = PocketWebRemoteInbox(controller) { publishState() }
    private val snapshotObserver = PocketWebSnapshotObserver { publishState() }
    private var started = false
    @Volatile private var closed = false
    private var revision = 0L
    private var lastState: String? = null
    private val choreographer by lazy { Choreographer.getInstance() }
    private var statePublicationScheduled = false
    private val statePublication = Choreographer.FrameCallback {
        statePublicationScheduled = false
        publishStateNow()
    }
    private var section: String? = null
    private var taskPage: String? = null
    private var taskViewId: String? = null
    private var taskReturnSection: String? = null
    private var workspaceResumeRevision = 0L
    private var taskReviewGeneration = 0L
    private val commandFence = PocketWebCommandFence()
    private val reviews = PocketWebTaskReviews()
    private val taskActions = PocketWebTaskActions(controller, reviews, ::canUseTaskControls) { work -> main.post(work) }
    private val taskHistory = PocketWebTaskHistory()
    private val automation = PocketWebAutomation(controller)
    private val mcpImports = PocketWebMcp(controller)
    private val sourceReader = PocketWebSources(controller)
    private val messageQuotes = PocketWebMessageQuotes(controller)
    private val inference = PocketWebInference(controller)
    private val trajectory = PocketWebTrace(controller)
    private val skillDrafts = PocketWebSkillDrafts(controller)
    private val memoryDrafts = PocketWebMemoryDrafts(controller)
    private val knowledge = PocketWebKnowledge(controller)
    private val personal = PocketWebPersonal(controller)
    private val memoryHistory = PocketWebMemoryHistory(controller)
    private val conversationOrganization = PocketWebConversationOrganization(controller)
    private val taskCapabilities = PocketWebTaskCapabilities(controller, { publishState() }, { id -> taskViewId == id })
    private val projectRuntime = PocketWebProjectRuntime(controller)
    private val attachmentPages = PocketWebAttachments(controller, ::captureWindow, ::acceptsWindow) { publishState() }
    private val modelDiagnostics = PocketWebModelDiagnostics(controller, ::captureWindow, ::acceptsWindow, { !closed && documentId != null && section == "models" }) { publishState() }
    private val developerLogs = PocketWebDeveloperLogs(controller, controller.webPresentationActivity(), ::captureWindow, ::acceptsWindow) { !closed && documentId != null }
    private val experience = PocketWebExperience(controller)
    private val routeReferences = PocketWebRouteReferences(controller)
    private val dataPages = PocketWebData(controller)
    private val remoteTasks = PocketWebRemoteTasks(controller, ::captureWindow, ::acceptsWindow, { taskViewId }) { publishState() }
    private val connectionPages = PocketWebConnections(controller, remoteTasks.inputPages)
    private val teamPages = PocketWebTeams(controller)
    private val teamEvidence = PocketWebTeamEvidence(controller)
    private val groupPages = PocketWebGroups(controller)
    private val mediaPages = PocketWebMedia(controller)
    private val realtimePages = PocketWebRealtime(controller)
    private val proxyPages = PocketWebProxy(controller)
    private val planPages = PocketWebPlans(controller)
    private val commitmentPages = PocketWebCommitments(controller)
    private val tokenBudgets = PocketWebTokenBudgets(controller, controller.nativeTokenBudgetHost()) {
        !closed && (section == "models" || taskViewId != null) && controller.webPrivateOwnerVisible()
    }
    private val profilePages = PocketWebProfiles(controller) { !closed && documentId != null && section == "workProfiles" && controller.webPrivateOwnerVisible() }
    private val localPages = PocketWebLocal(controller) { taskViewId }
    private val setupPages = PocketWebSetup(controller) { !closed && documentId != null && section == "setup" && taskViewId == null }
    private var projectPreview: PocketWebProjectPreview? = null
    private var projectLivePreview: PocketWebProjectLivePreview? = null
    private val phoneViewer = PocketWebPhoneViewer(controller::webPrivateOwnerVisible,
        { PocketWebWindowTicket(commandFence.ticket(), controller.webPhoneViewerWindowTicket()) },
        { ticket -> ticket.accepts(commandFence.ticket(), controller.webPhoneViewerWindowTicket()) },
        { id -> controller.state.tasks.firstOrNull { it.id == id } }, controller::readPhonePreview)
    private val commandReceipts = linkedMapOf<String, Pair<String, JSONObject>>()
    private val pendingCommands = mutableMapOf<String, String>()
    @Volatile private var documentId: String? = null
    private val phoneComposer = PocketWebPhoneComposer(controller, { closed }, ::captureWindow, ::acceptsWindow,
        { id, value, error -> complete(id, value, error) }, { publishState() })
    private val voiceInput = PocketWebVoiceInput(controller, phoneComposer)
    private var deviceWindow: NativeDeviceWorkspaceHost.Window? = null
    private val devicePages = PocketWebDevices(controller, { deviceWindow }, { !closed && section == "devices" && controller.webPrivateOwnerVisible() })
    private val pluginPages = PocketWebPlugins(controller, controller.webPluginFeatureHost(), controller.webPresentationActivity())
    private val domainSnapshots = mutableMapOf<String, JSONObject>()
    private val domainErrors = mutableMapOf<String, String>()
    @Volatile private var domainGeneration = 0L
    private val modelReviews = PocketWebModelReviews()
    @Volatile private var modelSelectionBusy = false
    private var observedModelRevision = -1L
    private val accountWorkspace = PocketWebAccounts(controller.nativeAccountWorkflows(), controller.nativeModelSelection(), { !closed && section == "accounts" && controller.webPrivateOwnerVisible() })
    private val executionReviews = PocketWebExecutionReviews(controller::webWindowTicket)

    private val composerModels = PocketWebComposerModels(controller, { !closed && (section == null || section == "models") && taskViewId == null && controller.webPrivateOwnerVisible() })
    private val aiSources = PocketWebAiSources(controller) { !closed && section == "models" && controller.webPrivateOwnerVisible() }
    private val aiGroups = PocketWebAiGroups(controller) { !closed && (section == null || section == "models") && controller.webPrivateOwnerVisible() }
    private val conversationModels = PocketWebConversationModels(controller) { !closed && section == null && taskViewId == null && controller.webPrivateOwnerVisible() }

    companion object {
        private val ownerDomains = setOf("memoryReview", "taskMemory", "mcp", "accounts", "learning", "groups", "channelMedia")
        private val connectionSections = setOf("setup", "profile", "devices", "models", "accounts", "remote", "ssh", "local", "memory", "memoryReview", "automation", "commitments", "channels", "skills", "learning", "plugins", "pluginCatalog", "mcp", "teams", "groups", "media", "realtime", "proxy", "workProfiles")
        val commands: Set<String> = linkedSetOf(
            "app.ready", "app.refresh", "notice.dismiss", "notice.task.close", "error.dismiss", "execution.read", "execution.review", "execution.confirm",
            "composer.edit", "composer.send", "composer.mode", "composer.intent", "composer.reading", "composer.query", "composer.retry", "composer.returnPending",
            "chat.cancel", "ownerWork.input", "ownerWork.control", "message.origin", "conversation.new", "conversation.select", "conversation.reader.close", "conversation.reader.earlier", "conversation.reader.refresh", "conversation.reader.position", "conversation.reader.select", "conversation.reader.attachment",
            "attachments.pick", "attachments.openPicker", "attachments.closePicker", "attachments.toggle", "attachments.remove", "attachments.open", "sources.open", "sources.read", "sources.close",
            "quickVoice.start", "quickVoice.save", "quickVoice.close",
            "skillDraft.open", "skillDraft.read", "skillDraft.source", "skillDraft.confirm", "skillDraft.cancel", "skillDraft.query", "skillDraft.close", "voice.start", "voice.stop", "voice.review", "voice.accept", "voice.discard", "speech.speak", "speech.stop", "speech.taskAnnouncements", "speech.stopTaskAnnouncement",
            "tasks.refresh", "tasks.open", "tasks.close", "tasks.stop", "tasks.review", "tasks.confirm", "tasks.approve", "tasks.resolve", "tasks.resume", "tasks.scope", "tasks.plan.enter", "tasks.answerQuestion", "tasks.answerQuestionChoice", "tasks.deleteScreenshot", "privacy.screenshots.read", "privacy.screenshots.save",
            "projects.refresh", "projects.select", "projects.create", "projects.open", "projects.newFile", "projects.edit", "projects.save", "projects.close", "projects.history", "projects.revision", "projects.useRevision", "projects.retry",
            "settings.reviewModelTransport", "settings.saveLocalObservation", "settings.openSystem", "settings.openBrowser", "settings.openAppUpdates",
            "connections.enter", "connections.leave", "models.snapshot", "models.review", "models.select", "models.connection.add", "models.connection.edit", "models.connection.test", "models.connection.select", "devices.snapshot", "history.read", "usage.read", "usage.task", "usage.close", "usage.reestimate", "trace.read", "trace.detail", "trace.close",
            "phone.prepare", "phone.start", "phone.query", "phone.draft.edit", "phone.pause", "phone.resume", "phone.stop", "phone.deadline", "background.refresh", "background.discover", "background.cancel", "background.close", "background.pair", "background.connect", "background.mainScreen", "background.selectMainScreen",
            "queued.open", "queued.refresh", "queued.close", "queued.edit", "queued.change", "queued.save", "queued.editor.close",
            "accounts.beginLogin", "accounts.cancelLogin", "accounts.openAuthorization", "accounts.select", "accounts.refresh", "accounts.models", "accounts.retryPlanUsage", "accounts.model.review", "accounts.model.select", "accounts.logout.review", "accounts.logout.confirm", "accounts.setEnabled", "accounts.refreshAuthorization", "accounts.reauthorize", "accounts.acknowledgePlanWelcome", "accounts.manageUsage",
            "memory.list", "memory.search", "memoryReview.list", "memoryReview.getCandidate", "memoryReview.capacity", "memoryReview.history", "memoryReview.openSource",
            "memoryReview.candidates", "mcp.snapshot", "mcp.pickConfig", "mcp.cancel", "mcp.discoverImport", "mcp.reviewImport", "mcp.installImport", "mcp.reviewEnabled", "mcp.confirmEnabled", "mcp.read", "mcp.reviewGrant", "mcp.confirmGrant", "learning.snapshot", "groups.snapshot",
            "remote.probe", "remote.environments", "local.termuxProbe", "local.inspectRuntime", "local.readReceipts", "local.prepareCheckout", "local.describeCheckout", "local.terminal.review", "local.terminal.confirm", "local.terminal.query", "local.terminal.stop", "local.terminal.open", "local.terminal.read", "local.terminal.input", "local.terminal.inputReceipt", "local.terminal.close", "automation.receipts", "automation.list", "automation.reviewSchedule", "automation.reviewRule", "automation.confirmReview", "automation.prepareOccurrence", "channels.inspect", "skills.read", "plugins.history", "proxy.status", "proxy.stop"
        ) + PocketWebSetup.COMMANDS + PocketWebModelConnectionCommands.COMMANDS + PocketWebAiSources.COMMANDS + PocketWebAiGroups.COMMANDS + PocketWebConversationModels.COMMANDS + PocketWebPermissionPolicy.COMMANDS + PocketWebData.COMMANDS + PocketWebConnections.COMMANDS + PocketWebMemoryDrafts.COMMANDS + setOf("conversation.drafts") + PocketWebKnowledge.COMMANDS + PocketWebPersonal.COMMANDS + PocketWebMessageQuotes.COMMANDS + PocketWebChannels.COMMANDS + PocketWebExperience.COMMANDS + PocketWebRouteReferences.COMMANDS + PocketWebSkills.COMMANDS + PocketWebPresentation.COMMANDS + PocketWebTeams.COMMANDS + PocketWebGroups.COMMANDS + PocketWebMedia.COMMANDS + PocketWebComposerModels.COMMANDS + PocketWebDevices.COMMANDS + PocketWebPlugins.COMMANDS + PocketWebRealtime.COMMANDS + PocketWebProxy.COMMANDS + PocketWebPlans.COMMANDS + PocketWebCommitments.COMMANDS + PocketWebTokenBudgets.COMMANDS + PocketWebProfiles.COMMANDS + PocketWebProjectPreview.COMMANDS + PocketWebPhoneViewer.COMMANDS + PocketWebBackground.COMMANDS + PocketWebMemoryHistory.COMMANDS + PocketWebModelDiagnostics.COMMANDS + PocketWebDeveloperLogs.COMMANDS + PocketWebAttachments.COMMANDS + PocketWebRemoteTasks.COMMANDS + PocketWebTeamEvidence.COMMANDS + PocketWebConversationOrganization.COMMANDS + PocketWebTaskCapabilities.COMMANDS + PocketWebProjectRuntime.COMMANDS + PocketWebProjectLivePreview.COMMANDS + PocketWebRemoteInbox.COMMANDS
    }

    /** Equivalent to the former Activity saveable presentation state, never an execution grant. */
    fun savePresentationState(): JSONObject = phoneComposer.savePresentation()

    fun restorePresentationState(saved: JSONObject?) {
        check(!started) { "Restore presentation before the Web document starts" }
        phoneComposer.restorePresentation(saved)
    }

    fun start() {
        if (closed || started) return
        check(Looper.myLooper() == Looper.getMainLooper())
        started = true
        presentation.start()
        snapshotObserver.start()
        publishState(true)
    }

    fun attachProjectPreview(container: android.widget.FrameLayout) {
        check(projectPreview == null && !closed)
        projectPreview = PocketWebProjectPreview(controller, container, ::captureWindow, ::acceptsWindow) { publishState() }
        projectLivePreview = PocketWebProjectLivePreview(controller, container, ::captureWindow, ::acceptsWindow) { publishState() }
        remoteTasks.attachPreview(container)
    }
    fun foreground(value: Boolean) { projectPreview?.foreground(value); projectLivePreview?.foreground(value); phoneViewer.foreground(value); attachmentPages.foreground(value); remoteTasks.foreground(value); taskCapabilities.foreground(value) }
    fun resource(uri: android.net.Uri, method: String, headers: Map<String, String>): android.webkit.WebResourceResponse? = attachmentPages.response(uri, method, headers)

    fun close() {
        if (closed) return
        closed = true
        started = false
        cancelStatePublication()
        presentation.close()
        background.clear()
        remoteInbox.close()
        modelDiagnostics.close()
        developerLogs.close()
        attachmentPages.close(); remoteTasks.close()
        projectLivePreview?.close(); projectLivePreview = null
        projectPreview?.close(); projectPreview = null; phoneViewer.clear()
        snapshotObserver.close()
        reviews.clear()
        taskHistory.clear()
        phoneComposer.clearReviews()
        voiceInput.clear()
        modelReviews.clear(); automation.clear(); mcpImports.clear(); executionReviews.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); trajectory.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); personal.clear(); memoryHistory.clear(); conversationOrganization.clear(); taskCapabilities.clear(); projectRuntime.clear(); projectLivePreview?.clear(); modelDiagnostics.clear(); attachmentPages.clear(); remoteTasks.clear(); teamEvidence.clear(); experience.clear(); routeReferences.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); devicePages.clear(); pluginPages.clear(); localPages.clear(); setupPages.clear(); accountWorkspace.close(); composerModels.close(); aiSources.close(); conversationModels.close(); controller.webChannels.clear(); controller.webSkills.clear()
        section?.takeIf { it == "archives" || it == "storage" }?.let(dataPages::close)
        section?.takeIf { it in ownerDomains }?.let(controller::leaveOwnerDomain)
        deviceWindow?.close(); deviceWindow = null
        section = null; taskReturnSection = null; taskViewId = null
        taskPage?.let(controller::leaveGroupTask); taskPage = null
        controller.closeNativeInferenceHistory()
        controller.closeQueuedOwnerInputs()
        controller.persistOwnerComposer()
        pendingCommands.clear(); commandReceipts.clear()
    }

    internal fun captureWindow(): PocketWebWindowTicket = PocketWebWindowTicket(commandFence.ticket(), controller.webWindowTicket())
    internal fun acceptsWindow(ticket: PocketWebWindowTicket): Boolean =
        ticket.accepts(commandFence.ticket(), controller.webWindowTicket())

    fun postMessage(raw: String, window: PocketWebWindowTicket, onStaleWindow: () -> Unit) {
        if (closed) return
        // The existing workspace backend remains the authority on individual file size limits.
        if (raw.length > 16 * 1024 * 1024) return
        main.post { if (!closed && started) { if (acceptsWindow(window)) process(raw) else onStaleWindow() } }
    }

    /** Synchronously invalidate queued commands and short-lived owner review handles. */
    fun invalidatePresentation(documentReplaced: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        cancelStatePublication()
        commandFence.invalidate()
        phoneViewer.clear()
        background.clear()
        projectPreview?.invalidatePresentation(documentReplaced)
        taskReviewGeneration++
        domainGeneration++
        taskHistory.clear()
        reviews.clear(); phoneComposer.clearReviews(); voiceInput.clear(); modelReviews.clear(); automation.clear(); mcpImports.clear(); executionReviews.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); trajectory.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); memoryHistory.clear(); conversationOrganization.clear(); if (documentReplaced) taskCapabilities.clear() else taskCapabilities.background(); projectRuntime.clear(); projectLivePreview?.clear(); modelDiagnostics.clear(); attachmentPages.clear(); remoteTasks.clear(); teamEvidence.clear(); experience.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); devicePages.clear(); pluginPages.clear(); localPages.clear(); setupPages.clear(); accountWorkspace.invalidate(); composerModels.clear(); aiSources.clear(); conversationModels.clear()
        if (documentReplaced) personal.clear() else personal.background()
        developerLogs.clear()
        if (documentReplaced) routeReferences.clear() else routeReferences.background()
        if (documentReplaced) { controller.webChannels.clear(); controller.webSkills.clear() } else { controller.webChannels.background(); controller.webSkills.background() }
        if (documentReplaced) { documentId = null; taskReturnSection = null }
    }

    private fun publishState(force: Boolean = false) {
        if (closed || !started) return
        // Commands and draft persistence still run immediately. Only their display
        // projection is coalesced, before building or serializing the full snapshot.
        if (force) {
            cancelStatePublication()
            publishStateNow(true)
        } else if (!statePublicationScheduled) {
            statePublicationScheduled = true
            choreographer.postFrameCallback(statePublication)
        }
    }

    private fun cancelStatePublication() {
        if (!statePublicationScheduled) return
        choreographer.removeFrameCallback(statePublication)
        statePublicationScheduled = false
    }

    private fun publishStateNow(force: Boolean = false) {
        if (closed || !started) return
        var projection: JSONObject? = null
        snapshotObserver.observeReads {
            controller.modelSelectionRevision
            projection = PocketWebState.snapshot(controller)
            projection?.getJSONObject("state")?.put("projectPreview", projectPreview?.snapshot() ?: JSONObject.NULL)
            projection?.getJSONObject("state")?.put("projectLivePreview", projectLivePreview?.snapshot() ?: JSONObject.NULL)
            projection?.getJSONObject("state")?.put("appearance", presentation.snapshot())
            projection?.getJSONObject("state")?.put("backgroundOwner", background.snapshot())
            projection?.getJSONObject("state")?.put("remoteInbox", remoteInbox.snapshot())
            projection?.getJSONObject("state")?.put("modelDiagnostics", modelDiagnostics.snapshot())
            projection?.getJSONObject("state")?.put("attachmentWorkspace", attachmentPages.snapshot())
            projection?.getJSONObject("state")?.put("taskCapabilityExport", taskCapabilities.exportSnapshot() ?: JSONObject.NULL)
            projection?.getJSONObject("state")?.put("systemSpeech", presentation.speechInfo())
            projection?.getJSONObject("state")?.put("routeReference", routeReferences.snapshot())
            projection?.getJSONObject("state")?.put("skillsWorkspace", controller.webSkills.snapshot())
            projection?.getJSONObject("state")?.put("mcpImport", PocketWebState.publicValue(mcpImports.snapshot()))
            projection?.getJSONObject("state")?.put("ownerArchives", dataPages.archivesSnapshot())
            projection?.getJSONObject("state")?.put("projectStorage", dataPages.storageSnapshot())
        }
        val snapshot = projection ?: return
        val projectedState = snapshot.getJSONObject("state")
        projectedState.put("modelSelectionBusy", modelSelectionBusy || controller.modelSelectionPending())
        projectedState.put("modelSelectionRevision", controller.modelSelectionRevision)
        projectedState.put("conversationModel", runCatching { controller.conversationModelPreference() }.getOrElse { JSONObject().put("available", false).put("message", it.message ?: "原对话模型偏好尚未准备") })
        if (!controller.modelSelectionPending() && observedModelRevision != controller.modelSelectionRevision) {
            observedModelRevision = controller.modelSelectionRevision
            val needsRead = domainSnapshots.containsKey("models") || section == "models"
            domainSnapshots.remove("models"); modelReviews.clear()
            if (needsRead) main.post { if (!closed && !controller.modelSelectionPending()) readModelSnapshot() }
        }
        domainSnapshots.forEach { (domain, value) ->
            val public = PocketWebState.publicValue(value)
            projectedState.getJSONObject("ownerSnapshots").put(domain, public)
            projectedState.getJSONObject("features").put(domain, public)
            if (domain == "models") projectedState.put("modelConnections", public)
            if (domain == "devices") projectedState.put("deviceWorkspace", public)
        }
        domainErrors.forEach { (domain, error) -> projectedState.getJSONObject("ownerErrors").put(domain, error) }
        snapshot.put("capabilities", JSONObject().put("commands", JSONArray(commands.toList())))
            .put("view", JSONObject().put("section", section ?: JSONObject.NULL).put("resumeRevision", workspaceResumeRevision).put("taskId", taskViewId ?: JSONObject.NULL))
            .put("phoneDraft", phoneComposer.snapshot())
        val serialized = snapshot.toString()
        if (!force && serialized == lastState) return
        lastState = serialized
        emit(JSONObject().put("v", 1).put("kind", "state").put("revision", ++revision).put("snapshot", snapshot))
    }

    private fun process(raw: String) {
        var id = ""
        var ownsRequest = false
        try {
            val request = JSONObject(raw)
            require(request.opt("v") == 1) { "不支持的界面协议版本" }
            id = requiredString(request, "id")
            require(id.length <= 120 && id.matches(Regex("[A-Za-z0-9_.:-]+"))) { "请求身份无效" }
            val action = requiredString(request, "action")
            require(action in commands) { "这项操作没有已接入的宿主能力" }
            require(action == "app.ready" || documentId != null && request.optString("documentId") == documentId) { "原界面文档已变化，请重新打开当前工作" }
            require(controller.webUiCanInteract()) { "原界面已不在前台，未继续这项操作" }
            val args = if (request.has("args")) request.getJSONObject("args") else JSONObject()
            val identity = digest(action + "\u0000" + args.toString())
            commandReceipts[id]?.let { prior ->
                require(prior.first == identity) { "同一请求身份不能替换操作内容" }
                emit(JSONObject(prior.second.toString())); return
            }
            pendingCommands[id]?.let { prior ->
                require(prior == identity) { "同一请求身份不能替换操作内容" }
                return
            }
            pendingCommands[id] = identity
            ownsRequest = true
            if (action in PocketWebSetup.COMMANDS) {
                setupPages.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }
                return
            }
            if (action in PocketWebRemoteInbox.COMMANDS) {
                remoteInbox.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }
                return
            }
            if (action in PocketWebPhoneViewer.COMMANDS) {
                phoneViewer.dispatch(action, args) { result, error, code ->
                    complete(id, result, error, code.ifEmpty { "preview_unavailable" }, retainValue = action != "phone.viewer.frame")
                }
                return
            }
            if (action in PocketWebProjectLivePreview.COMMANDS) {
                if (action == "projectLive.open") require(requiredString(args, "taskId") == taskViewId) { "请先打开这份原项目任务" }
                (projectLivePreview ?: error("原手机项目预览宿主尚未接入")).dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebProjectPreview.COMMANDS) {
                (projectPreview ?: error("原项目预览宿主尚未接入")).dispatch(action, args) { result, error -> complete(id, result, error); publishState() }
                return
            }
            if (action in PocketWebDeveloperLogs.COMMANDS) {
                developerLogs.dispatch(action, args) { result, error ->
                    complete(id, result, error, retainValue = action != "developerLogs.page")
                }
                return
            }
            if (action in PocketWebModelDiagnostics.COMMANDS) {
                requireSection("models"); modelDiagnostics.dispatch(action, args) { result, error -> complete(id, result, error, retainValue = action != "modelDiagnostics.logs"); publishState() }; return
            }
            if (action in PocketWebTaskCapabilities.COMMANDS) {
                if (action == "taskCapabilities.open") {
                    val selected = requireTask(requiredString(args, "taskId"))
                    require(selected.id == taskViewId && selected.raw.optString("actorId") == "owner" && selected.raw.optString("spaceId") == "private") { "请先打开这份原私人任务" }
                }
                taskCapabilities.dispatch(action, args) { result, error -> complete(id, result, error, retainValue = action != "taskCapabilities.artifact"); publishState() }; return
            }
            if (action in PocketWebProjectRuntime.COMMANDS) {
                require(requiredString(args, "taskId") == taskViewId) { "请先打开这份原项目任务" }
                projectRuntime.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebConversationOrganization.COMMANDS) {
                conversationOrganization.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebRemoteTasks.COMMANDS) {
                remoteTasks.dispatch(action, args) { result, error -> complete(id, result, error, retainValue = action != "remoteTask.clipboard"); publishState() }; return
            }
            if (action in PocketWebTeamEvidence.COMMANDS) {
                teamEvidence.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebAttachments.COMMANDS) {
                attachmentPages.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebMemoryHistory.COMMANDS) {
                memoryHistory.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebBackground.COMMANDS) {
                background.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }
                return
            }
            if (action == "quickVoice.save") {
                checkedPayload(args, setOf("handoffId", "inputId", "text"))
                controller.saveQuickPanelVoice(requiredString(args, "handoffId"), requiredString(args, "inputId"), requiredString(args, "text")) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action == "history.read") {
                checkedPayload(args, setOf("taskId", "section", "cursor"))
                val taskId = if (args.has("taskId")) requiredString(args, "taskId") else null
                val selectedSection = args.optString("section", if (taskId == null) "tasks" else "events")
                require(if (taskId == null) selectedSection == "tasks" else selectedSection in setOf("events", "operations")) { "原历史读取范围无效" }
                if (taskId != null) {
                    val selected = requireTask(taskId)
                    require(selected.raw.optString("taskProjection") != "source_unavailable" && !selected.raw.optBoolean("sourceCurrentReadRequired")) { "原任务来源暂不可核对，请重新读取任务" }
                }
                val window = captureWindow()
                controller.readTaskAudit(taskId, selectedSection, args.optString("cursor")) { result, error ->
                    if (closed) return@readTaskAudit
                    if (!acceptsWindow(window)) complete(id, null, "原记录窗口已关闭，请重新读取")
                    else if (error != null || result == null) complete(id, null, error ?: "原记录未读取")
                    else try {
                        if (taskId == null) taskHistory.remember(result)
                        complete(id, PocketWebState.publicValue(result) as JSONObject, null)
                    } catch (failure: Exception) { complete(id, null, failure.message ?: "原记录不可核对") }
                }
                return
            }
            if (action in PocketWebTokenBudgets.COMMANDS) {
                require(section == "models" || taskViewId != null) { "请从模型连接或原任务详情读取预算" }
                if (action == "tokenBudgets.open") {
                    val selected = args.optString("taskId")
                    require(if (selected.isEmpty()) section == "models" else taskViewId == selected) { "预算必须属于当前原任务窗口" }
                }
                tokenBudgets.dispatch(action, args) { result, error, code ->
                    complete(id, result, error, code); publishState()
                }
                return
            }
            if (action == "tasks.open") {
                checkedPayload(args, setOf("id", "requestNavigation"))
                val taskId = requiredString(args, "id")
                val channelUsageVisible = section == "channels" && controller.webPrivateOwnerVisible() &&
                    controller.state.feature("channels").optJSONArray("inbox").objects().any { event ->
                        val reference = event.optJSONObject("latestInferenceReference")
                        reference?.optString("taskId") == taskId && reference.optString("purpose") == "CHANNEL_CHAT" &&
                            reference.optBoolean("accountingOnly") && !reference.optBoolean("executionAuthorized", true)
                    }
                val phoneResultVisible = canReadTaskFromVisiblePhoneResult(
                    taskId, section, taskViewId, controller.webPrivateOwnerVisible(), controller.state.conversationId,
                    controller.state.messages.asSequence().map { it.phoneResult },
                )
                val taskResultVisible = canReadTaskFromVisibleTaskResult(
                    taskId, section, taskViewId, controller.webPrivateOwnerVisible(), controller.state.conversationId,
                    controller.state.messages.asSequence().map { it.taskResult },
                )
                val preview = projectPreview?.snapshot()
                val previewTaskVisible = controller.webPrivateOwnerVisible() && preview?.optString("taskId") == taskId &&
                    preview?.optJSONObject("metadata")?.optString("projectId") == controller.state.projectId
                require(controller.state.tasks.any { it.id == taskId } || taskHistory.contains(taskId) || channelUsageVisible || phoneResultVisible || taskResultVisible || previewTaskVisible) { "请先重新读取这个原任务的历史窗口" }
                val requestNavigation = if (args.has("requestNavigation")) requiredBoolean(args, "requestNavigation") else true
                openOriginalTask(id, taskId, requestNavigation)
                return
            }
            if (action == "trace.read") {
                checkedPayload(args, setOf("taskId", "runId", "cursor", "limit"))
                val taskId = requiredString(args, "taskId")
                require(taskViewId == taskId) { "请先打开这个原任务的执行过程" }
                trajectory.read(taskId,
                    if (args.has("runId")) requiredString(args, "runId", true) else "",
                    if (args.has("cursor")) requiredString(args, "cursor", true) else "",
                    if (args.has("limit")) requiredInt(args, "limit") else 100) { result, error -> complete(id, result, error) }
                return
            }
            if (action == "trace.detail") {
                checkedPayload(args, setOf("taskId", "runId", "epoch", "nodeId"))
                val taskId = requiredString(args, "taskId")
                require(taskViewId == taskId) { "请先打开这个原任务的执行过程" }
                trajectory.detail(taskId, requiredString(args, "runId"), requiredLong(args, "epoch"),
                    requiredString(args, "nodeId")) { result, error -> complete(id, result, error) }
                return
            }
            if (action == "trace.close") { checkedPayload(args, emptySet()); trajectory.clear(); complete(id, JSONObject().put("closed", true), null); return }
            if (action == "usage.task") { checkedPayload(args, setOf("taskId", "cursor")); inference.read(requiredString(args, "taskId"), requiredInt(args, "cursor")) { result, error -> complete(id, result, error) }; return }
            if (action == "usage.read") {
                checkedPayload(args, setOf("cursor"))
                controller.openNativeInferenceHistory()
                val window = captureWindow()
                controller.readNativeInferenceHistory(args.optString("cursor")) { result, error ->
                    if (closed) return@readNativeInferenceHistory
                    if (!acceptsWindow(window)) complete(id, null, "原调用记录窗口已关闭，请重新读取")
                    else if (error != null || result == null) complete(id, null, error ?: "原调用记录未读取")
                    else try {
                        taskHistory.remember(result)
                        complete(id, PocketWebState.publicValue(result) as JSONObject, null)
                    } catch (failure: Exception) { complete(id, null, failure.message ?: "原调用记录不可核对") }
                }
                return
            }
            if (action == "usage.reestimate") {
                controller.reestimateInference(requiredString(args, "taskId"), requiredString(args, "callId")) { result, error -> complete(id, result, error) }
                return
            }
            if (action == "chat.cancel") {
                val expected = JSONObject().put("conversationId", requiredString(args, "conversationId"))
                    .put("taskId", requiredString(args, "taskId")).put("runId", requiredString(args, "runId"))
                    .put("epoch", requiredInt(args, "epoch"))
                controller.cancelChatFromWeb(expected) { result, error -> complete(id, result, error) }
                return
            }
            if (action == "ownerWork.input") {
                checkedPayload(args, setOf("taskId", "runId", "epoch", "clientMessageId", "message", "mode"))
                require(section == null && taskViewId == null && controller.webPrivateOwnerVisible()) { "请回到管家输入框补充原任务" }
                controller.sendOwnerWorkInput(args, requiredString(args, "clientMessageId"), requiredString(args, "message", true), requiredString(args, "mode")) { result, error ->
                    complete(id, result, error, "outcome_unknown"); publishState()
                }
                return
            }
            if (action == "ownerWork.control") {
                checkedPayload(args, setOf("taskId", "runId", "epoch", "action"))
                require(controller.webPrivateOwnerVisible() && section == null && (taskViewId == null || taskViewId == requiredString(args, "taskId"))) { "原任务控制窗口已关闭" }
                controller.controlOwnerWork(args, requiredString(args, "action")) { result, error ->
                    complete(id, result, error, "outcome_unknown"); publishState()
                }
                return
            }
            if (action == "message.origin") {
                checkedPayload(args, setOf("messageId", "sourceMessageId", "offset"))
                require(section == null && taskViewId == null && controller.webPrivateOwnerVisible()) { "请回到原答复读取原请求" }
                controller.readMessageOrigin(requiredString(args, "messageId"), requiredString(args, "sourceMessageId"), requiredInt(args, "offset")) { result, error -> complete(id, result, error) }
                return
            }
            if (action in PocketWebOwnerQuestions.commands) {
                val field = if (action == PocketWebOwnerQuestions.CHOOSE) "optionId" else "text"
                checkedPayload(args, setOf("reviewHandle", field))
                val task = reviews.take(requiredString(args, "reviewHandle"), action, controller.state.tasks)
                require(canUseTaskControls(task) && !PocketWebTaskActions.specialOwnerTask(task)) { "原问题审核窗口已关闭" }
                PocketWebOwnerQuestions.dispatch(controller, task, action, args) { result, error ->
                    complete(id, result, error, "outcome_unknown"); publishState()
                }
                return
            }
            if (action in PocketWebTaskActions.DECISIONS) {
                when (val decision = taskActions.decide(action, args, taskViewId)) {
                    PocketWebTaskActions.Decision.Accepted -> { complete(id, accepted(), null); publishState() }
                    is PocketWebTaskActions.Decision.Plan -> submitExecutionPlanDecision(id, decision.action, decision.task, decision.input)
                    is PocketWebTaskActions.Decision.Continuation -> submitProjectContinuation(id, decision.task, decision.input)
                }
                return
            }
            if (action == "tasks.review") {
                val taskId = requiredString(args, "id")
                require(taskViewId == taskId && controller.webUiCanInteract()) { "请重新打开这个原任务的审阅窗口" }
                requireTask(taskId)
                val generation = taskReviewGeneration
                val document = documentId
                val navigation = Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
                taskActions.read(taskId, { closed }, {
                    generation == taskReviewGeneration && document == documentId &&
                        navigation == Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
                }) { result, error ->
                    complete(id, result, error)
                    if (error == null) publishState()
                }
                return
            }
            if (action == "models.snapshot") { readModelSnapshot(id); return }
            if (action == "conversation.drafts") { checkedPayload(args, setOf("taskId")); controller.readConversationDrafts(requiredString(args, "taskId")) { result, error -> complete(id, result, error) }; return }
            if (action in PocketWebRouteReferences.COMMANDS) {
                routeReferences.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebSkills.COMMANDS) {
                requireSection("skills"); controller.webSkills.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebChannels.COMMANDS) {
                requireSection("channels"); controller.webChannels.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebExperience.COMMANDS) {
                requireSection("learning"); experience.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebPersonal.COMMANDS) {
                if (action != "profile.identity") requireSection("profile")
                // Profile/memory reads do not leave source bodies in generic replay receipts.
                personal.dispatch(action, args) { result, error -> complete(id, result, error, retainValue = action == "profile.save"); publishState() }; return
            }
            if (action in PocketWebKnowledge.COMMANDS) {
                if (action == "knowledge.close") check(section in setOf("memory", "memoryReview", "learning")) else if (action.startsWith("knowledge.")) check(section in setOf("memory", "memoryReview")) { "请先打开记忆管理页" } else requireSection("learning")
                knowledge.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebMemoryDrafts.COMMANDS) { memoryDrafts.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return }
            if (action in PocketWebSkillDrafts.COMMANDS) { skillDrafts.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return }
            if (action in PocketWebLocal.COMMANDS) {
                if (action.startsWith("local.execution.")) {
                    if (action != "local.execution.close") require(taskViewId != null && controller.webPrivateOwnerVisible()) { "请先打开原私人项目任务" }
                    if (action == "local.execution.open") require(requiredString(args, "taskId") == taskViewId) { "原项目任务已切换" }
                } else requireSection("local")
                localPages.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action == "groups.openTask") {
                requireSection("groups")
                openOriginalTask(id, groupPages.taskForNavigation(args), true, "groups")
                return
            }
            if (action == "devices.openTask") {
                requireSection("devices")
                openOriginalTask(id, devicePages.taskForNavigation(args), true, "devices")
                return
            }
            if (action in PocketWebDevices.COMMANDS) {
                requireSection("devices")
                devicePages.dispatch(action, args) { result, error ->
                    if (!action.startsWith("devices.desktop.")) {
                        result?.optJSONObject("snapshot")?.let { domainSnapshots["devices"] = JSONObject(it.toString()) }
                        if (error == null) domainErrors.remove("devices") else domainErrors["devices"] = error
                    }
                    complete(id, result, error, "outcome_unknown")
                    if (!action.startsWith("devices.desktop.")) publishState()
                }
                return
            }
            if (action in PocketWebPlugins.COMMANDS) {
                require(section in setOf("plugins", "pluginCatalog")) { "原插件窗口已关闭，请重新进入" }
                pluginPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }
                return
            }
            if (action in PocketWebAiSources.COMMANDS) {
                aiSources.dispatch(action, args) { result, error, code -> complete(id, result, error, code); publishState() }; return
            }
            if (action in PocketWebAiGroups.COMMANDS) {
                aiGroups.dispatch(action, args) { result, error, code -> complete(id, result, error, code); publishState() }; return
            }
            if (action in PocketWebPermissionPolicy.COMMANDS) {
                check(section == null && taskViewId == null) { "请回到私人对话设置执行权限" }
                complete(id, PocketWebPermissionPolicy.dispatch(controller, action, args), null); publishState(); return
            }
            if (action in PocketWebConversationModels.COMMANDS) {
                conversationModels.dispatch(action, args) { result, error, code -> complete(id, result, error, code); publishState() }; return
            }
            if (action in PocketWebComposerModels.COMMANDS) {
                if (action.startsWith("models.conversation.")) check(section == null && taskViewId == null) { "请回到原私人对话选择模型" }

                composerModels.dispatch(action, args) { result, error, code -> complete(id, result, error, code); publishState() }; return
            }
            if (action in PocketWebRealtime.COMMANDS) {
                requireSection("realtime"); realtimePages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }; return
            }
            if (action in PocketWebProxy.COMMANDS) {
                requireSection("proxy"); proxyPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }; return
            }
            if (action in PocketWebProfiles.COMMANDS) {
                requireSection("workProfiles"); profilePages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }; return
            }
            if (action in PocketWebPlans.COMMANDS) {
                requireSection("commitments"); planPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }; return
            }
            if (action in PocketWebCommitments.COMMANDS) {
                requireSection("commitments"); commitmentPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }; return
            }
            if (action in PocketWebMedia.COMMANDS) {
                requireSection("media"); mediaPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }; return
            }
            if (action in PocketWebGroups.COMMANDS) {
                requireSection("groups"); groupPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }; return
            }
            if (action in PocketWebTeams.COMMANDS) {
                requireSection("teams"); teamPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }; return
            }
            if (action in PocketWebConnections.COMMANDS) {
                requireSection(action.substringBefore('.'))
                connectionPages.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebData.COMMANDS) {
                val domain = action.substringBefore('.')
                val result = when (action.substringAfter('.')) {
                    "open" -> {
                        checkedPayload(args, emptySet())
                        if (section != domain) { leaveConnection(); section = domain }
                        dataPages.open(domain); JSONObject().put("status", "requested")
                    }
                    "close" -> { checkedPayload(args, emptySet()); if (section == domain) leaveConnection(); JSONObject().put("closed", true) }
                    else -> { requireSection(domain); dataPages.dispatch(action, args) }
                }
                complete(id, result, null); publishState(); return
            }
            if (action in setOf("accounts.models", "accounts.model.review", "accounts.logout.review", "accounts.model.select", "accounts.logout.confirm")) { accountCommand(id, action, args); return }
            if (action in PocketWebMessageQuotes.COMMANDS) {
                messageQuotes.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action == "sources.open") {
                checkedPayload(args, setOf("messageId", "taskId"))
                sourceReader.open(args.optString("messageId").takeIf { it.isNotBlank() }, args.optString("taskId").takeIf { it.isNotBlank() }) { result, error -> complete(id, result, error) }; return
            }
            if (action == "sources.read") {
                checkedPayload(args, setOf("windowId", "sourceHandle", "cursor", "offset"))
                sourceReader.read(requiredString(args, "windowId"), args.optString("sourceHandle").takeIf { it.isNotBlank() }, requiredInt(args, "cursor"), requiredInt(args, "offset")) { result, error -> complete(id, result, error) }; return
            }
            if (action == "execution.read") {
                checkedPayload(args, emptySet()); controller.readOwnerExecutionPolicy { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action == "privacy.screenshots.read") {
                checkedPayload(args, emptySet()); controller.readTaskScreenshotPolicy { value, error -> complete(id, value, error); publishState() }; return
            }
            if (action == "privacy.screenshots.save") {
                checkedPayload(args, setOf("revision", "retentionMillis"))
                val revision = args.get("revision"); val duration = args.get("retentionMillis")
                require((revision is Int || revision is Long) && (revision as Number).toLong() > 0 && (duration is Int || duration is Long)) { "请重新读取原截图策略" }
                controller.changeTaskScreenshotPolicy((revision as Number).toLong(), (duration as Number).toLong()) { value, error -> complete(id, value, error, "outcome_unknown"); publishState() }; return
            }
            if (action == "tasks.deleteScreenshot") {
                checkedPayload(args, setOf("reviewHandle"))
                val task = reviews.take(requiredString(args, "reviewHandle"), action, controller.state.tasks)
                val screenshot = task.raw.getJSONObject("evidence").getJSONObject("screenshot")
                controller.deleteTaskScreenshot(task, screenshot.getString("imageId")) { value, error -> complete(id, value?.let { PocketWebState.publicValue(it) as JSONObject }, error, "outcome_unknown"); publishState() }; return
            }
            if (action == "execution.confirm") {
                checkedPayload(args, setOf("reviewHandle"))
                val review = executionReviews.consume(requiredString(args, "reviewHandle"))
                controller.changeOwnerExecution(review.revision, review.enabled) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }; return
            }
            if (action in PocketWebPresentation.COMMANDS) {
                check(controller.webPrivateOwnerVisible()) { "请在前台私人设置中操作外观与系统朗读" }
                presentation.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action == "speech.taskAnnouncements") {
                checkedPayload(args, setOf("enabled"))
                controller.saveTaskAnnouncements(requiredBoolean(args, "enabled")) { result, error -> complete(id, result, error); publishState() }
                return
            }
            if (action == "models.review") { reviewModel(id, requiredString(args, "modelId")); return }
            if (action == "models.select") { selectModel(id, args); return }
            if (action in PocketWebAccountCommands.COMMANDS) { accountOwnerCommand(id, action, args); return }
            if (action in PocketWebModelConnectionCommands.COMMANDS) { modelConnection(id, action, args); return }
            if (action == "phone.prepare") { phoneComposer.prepare(id, args); return }
            if (action == "phone.start") { phoneComposer.start(id, args); return }
            if (action == "phone.query") { phoneComposer.query(id, args); return }
            if (action in setOf("phone.pause", "phone.resume", "phone.stop")) {
                val task = requireTask(requiredString(args, "taskId"))
                require(isPhoneTask(task) && canUseTaskControls(task) && task.raw.optString("runId") == requiredString(args, "runId") && task.raw.optInt("coordinatorEpoch", -1) == requiredInt(args, "epoch")) { "原手机任务或执行代次已变化，请刷新核对" }
                if (action == "phone.resume") require(task.status == "paused" && PocketWebTaskActions.unresolvedForReview(task).isEmpty() && pendingCapabilityApprovals(task).isEmpty()) { "请先核对原任务的暂停、未决动作与授权" }
                controller.controlOriginalPhone(task, action.substringAfter('.')) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }
                return
            }
            val result = dispatch(action, args)
            complete(id, result ?: accepted(), null)
            publishState(action == "app.ready")
        } catch (failure: Exception) {
            RuntimeDiagnostics.event("message_admission", if (ownsRequest) "failed" else "rejected",
                if (ownsRequest) "WEB_COMMAND_DISPATCH_FAILED" else "WEB_COMMAND_REJECTED", null, failure)
            if (id.isNotEmpty()) {
                if (ownsRequest) complete(id, null, failure.message ?: "操作未接受")
                else emit(JSONObject().put("v", 1).put("kind", "result").put("id", id).put("ok", false)
                    .put("error", JSONObject().put("code", "invalid_request").put("message", failure.message ?: "请求未接受")))
            }
        }
    }

    private fun complete(id: String, value: JSONObject?, error: String?, errorCode: String = "not_accepted", retainValue: Boolean = true) {
        if (closed) return
        val identity = pendingCommands.remove(id) ?: return
        val response = JSONObject().put("v", 1).put("kind", "result").put("id", id).put("ok", error == null)
        if (error == null) response.put("value", value ?: JSONObject()) else response.put("error", JSONObject().put("code", errorCode).put("message", error))
        // Transient reads are not durable command receipts. Keep only a small replay rejection.
        val replay = if (retainValue || error != null) response else JSONObject().put("v", 1).put("kind", "result").put("id", id).put("ok", false)
            .put("error", JSONObject().put("code", "preview_expired").put("message", "原读取结果已过期，请重新读取"))
        commandReceipts[id] = identity to replay
        while (commandReceipts.size > 128) commandReceipts.remove(commandReceipts.keys.first())
        emit(response)
    }

    /** Keep the original foreground review valid through the existing native owner queue. */
    private fun submitExecutionPlanDecision(id: String, action: String, task: UiTask, input: JSONObject) {
        val window = captureWindow()
        val generation = taskReviewGeneration
        val document = documentId
        val navigation = Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
        val current = {
            !closed && taskViewId == task.id && generation == taskReviewGeneration && document != null && document == documentId &&
                acceptsWindow(window) && controller.webPrivateOwnerVisible() &&
                navigation == Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
        }
        require(current()) { "原计划审阅窗口已关闭，请重新读取" }
        controller.requestWebOwnerActionReceipt(action, input, current) { result, error ->
            if (closed) return@requestWebOwnerActionReceipt
            if (!current()) {
                val saved = result?.optBoolean("approvalDecisionSaved") == true || action == "enterTaskPlanning" &&
                    (result?.optJSONObject("executionScope") ?: result?.optJSONObject("task")?.optJSONObject("executionScope"))?.optString("executionPhase") == "planning"
                if (saved && result != null) complete(id, (PocketWebState.publicValue(result) as JSONObject).put("presentationCurrent", false), null)
                else complete(id, null, "原计划审阅窗口已变化，请核对原任务回执；没有重复提交", "outcome_unknown")
            }
            else if (error != null || result == null) complete(id, null, error ?: "原计划决定尚未确认，请核对原任务", "outcome_unknown")
            else complete(id, PocketWebState.publicValue(result) as JSONObject, null)
            publishState()
        }
    }

    private fun submitProjectContinuation(id: String, task: UiTask, input: JSONObject) {
        val window = captureWindow()
        val generation = taskReviewGeneration
        val document = documentId
        val navigation = Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
        val current = {
            !closed && taskViewId == task.id && generation == taskReviewGeneration && document != null && document == documentId &&
                acceptsWindow(window) && controller.webPrivateOwnerVisible() &&
                navigation == Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
        }
        require(current()) { "原项目审阅窗口已关闭，请重新核对" }
        controller.requestWebOwnerActionReceipt("continueDevelopmentTask", input, current) { result, error ->
            if (closed) return@requestWebOwnerActionReceipt
            when {
                !current() -> complete(id, null, "原项目审阅窗口已变化，请核对原任务状态；草稿保留，没有再次提交", "outcome_unknown")
                error != null || result == null -> complete(id, null, error ?: "尚未确认原项目任务续接，请先核对原任务", "outcome_unknown")
                !PocketWebProjectContinuation.matchesReceipt(task, result) -> complete(id, null, "续接回执与原任务不符，请核对原记录；没有再次提交", "outcome_unknown")
                else -> complete(id, PocketWebState.publicValue(result) as JSONObject, null)
            }
            publishState()
        }
    }

    private fun dispatch(action: String, args: JSONObject): JSONObject? {
        when (action) {
            "execution.review" -> {
                checkedPayload(args, setOf("enabled")); check(!controller.ownerExecutionSaving) { "请先等待原运行开关回执" }
                val policy = controller.ownerExecutionPolicy
                check(policy.optString("format") == "pocketworld.owner-execution-policy.v1" && policy.optLong("revision") > 0) { "请先读取原运行状态" }
                return executionReviews.create(policy, requiredBoolean(args, "enabled"))
            }
            "app.ready" -> {
                val nextDocument = requiredString(args, "documentId")
                require(nextDocument.length <= 120)
                if (nextDocument != documentId) {
                    documentId = nextDocument; background.clear(); remoteInbox.clear(); phoneViewer.clear(); phoneComposer.documentChanged(); taskHistory.clear(); controller.webChannels.clear(); controller.webSkills.clear(); reviews.clear(); phoneComposer.clearReviews(); voiceInput.clear(); modelReviews.clear(); automation.clear(); executionReviews.clear(); mcpImports.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); trajectory.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); personal.clear(); memoryHistory.clear(); conversationOrganization.clear(); taskCapabilities.clear(); projectRuntime.clear(); projectLivePreview?.clear(); modelDiagnostics.clear(); attachmentPages.clear(); remoteTasks.clear(); teamEvidence.clear(); experience.clear(); routeReferences.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); devicePages.clear(); pluginPages.clear(); localPages.clear(); setupPages.clear(); accountWorkspace.invalidate(); composerModels.clear(); aiSources.clear(); conversationModels.clear()
                    controller.voiceInputTypeChanged(phoneComposer.intent)
                }
                return JSONObject().put("ready", true).put("source", "library-web")
            }

            "app.refresh", "tasks.refresh" -> controller.refresh()
            "notice.dismiss" -> controller.dismissNotice()
            "notice.task.close" -> controller.dismissOriginalTaskNotice()
            "error.dismiss" -> controller.dismissError()
            "composer.edit", "composer.send", "composer.mode" -> {
                val key = requiredString(args, "key"); val epoch = requiredInt(args, "epoch")
                require(controller.composerDraft?.key == key && controller.composerViewEpoch == epoch) { "原输入窗口已变化，草稿仍保留，请回原输入核对" }
                when (action) {
                    "composer.edit" -> controller.editComposerText(key, epoch, requiredString(args, "text", true))
                    "composer.mode" -> { val mode = requiredString(args, "mode"); require(mode in setOf("queue", "steer")); controller.setChatSendMode(mode, key, epoch) }
                    else -> {
                        val text = requiredString(args, "text", true)
                        require(controller.composerDraft?.text == text && ChatInputRules.canSend(controller.state, false, text)) { "原输入尚不可发送，请核对保留草稿与原任务" }
                        controller.sendChat(text, key, epoch)
                    }
                }
            }
            "composer.reading" -> controller.saveComposerReading(requiredString(args, "key"), requiredInt(args, "index"), requiredInt(args, "offset"), args.optString("anchor"), args.optString("observed"), args.optBoolean("unread"))
            "composer.intent" -> {
                phoneComposer.changeIntent(args)
                controller.dismissError()
                voiceInput.clear()
                controller.voiceInputTypeChanged(phoneComposer.intent)
            }
            "composer.query", "composer.retry", "composer.returnPending" -> {
                val client = requiredString(args, "clientMessageId")
                require(controller.composerPending.any { it.clientId == client } || controller.state.chatReceipts.containsKey(client)) { "原输入回执身份不可核对" }
                when (action) { "composer.query" -> controller.queryChatSubmission(client); "composer.retry" -> controller.retryLocallyUnsent(client); else -> controller.returnToPendingComposer(client) }
            }
            "conversation.new" -> { leaveTask(); leaveConnection(); reviews.clear(); phoneComposer.clearReviews(); controller.newConversation() }
            "conversation.select" -> { leaveTask(); leaveConnection(); reviews.clear(); phoneComposer.clearReviews(); controller.openConversation(requiredString(args, "id")) }
            "conversation.reader.close" -> { requireConversationReader(args, closing = true); controller.closeConversationReader() }
            "conversation.reader.earlier" -> { requireConversationReader(args); controller.loadEarlierConversationReader() }
            "conversation.reader.refresh" -> { requireConversationReader(args); controller.refreshConversationReader() }
            "conversation.reader.position" -> {
                val page = requireConversationReader(args)
                val index = requiredInt(args, "index"); val offset = requiredInt(args, "offset")
                require(index >= 0 && index < page.messages.size && offset >= 0) { "原阅读位置不可核对" }
                controller.saveConversationReading(page.window, index, offset)
            }
            "conversation.reader.select" -> {
                val page = requireConversationReader(args)
                require(!controller.state.chatSubmitting) { "请等待原输入回执后再切换对话" }
                controller.switchFromConversationReader(page.window)
            }
            "conversation.reader.attachment" -> {
                val page = requireConversationReader(args)
                val message = requiredString(args, "messageId")
                val file = page.messages.firstOrNull { it.id == message }?.attachments?.firstOrNull { it.id == requiredString(args, "id") && it.sha256 == requiredString(args, "sha256") }
                    ?: error("原附件不属于这份阅读窗口")
                controller.openReaderAttachment(page.window, message, file)
            }
            "attachments.pick" -> { requireComposer(args, optional = true); controller.pickAttachments() }
            "attachments.openPicker" -> { requireComposer(args, optional = true); controller.openChatAttachmentPicker() }
            "attachments.closePicker" -> controller.closeChatAttachmentPicker()
            "attachments.remove" -> { requireComposer(args); controller.removeChatAttachment(requiredString(args, "id"), requiredString(args, "key"), requiredInt(args, "epoch")) }
            "attachments.toggle" -> {
                requireComposer(args, optional = true)
                val id = requiredString(args, "id"); val sha = requiredString(args, "sha256")
                val selected = controller.state.feature("attachments").optJSONArray("attachments").objects().firstOrNull { it.optString("id") == id && it.optString("sha256") == sha }
                    ?: error("附件原件已变化，请重新选择")
                controller.toggleChatAttachment(selected)
            }
            "quickVoice.start" -> { checkedPayload(args, setOf("handoffId")); controller.startQuickPanelVoice(requiredString(args, "handoffId")) }
            "quickVoice.close" -> { checkedPayload(args, emptySet()); controller.closeQuickPanelVoice() }
            in PocketWebVoiceInput.COMMANDS -> return voiceInput.dispatch(action, args)
            "speech.speak" -> {
                val row = controller.state.messages.firstOrNull { it.id == requiredString(args, "messageId") && it.role == "assistant" } ?: error("原回复已变化")
                controller.speak(row.content)
            }
            "speech.stop" -> controller.stopSpeaking()
            "sources.close" -> { checkedPayload(args, setOf("windowId")); sourceReader.close(requiredString(args, "windowId")) }
            "speech.stopTaskAnnouncement" -> { checkedPayload(args, setOf("readoutId")); controller.stopTaskAnnouncement(requiredString(args, "readoutId")) }
            "attachments.open" -> {
                checkedPayload(args, setOf("messageId", "id", "sha256"))
                val message = controller.state.messages.firstOrNull { it.id == requiredString(args, "messageId") } ?: error("原消息已不在当前窗口")
                val file = message.attachments.firstOrNull { it.id == requiredString(args, "id") && it.sha256 == requiredString(args, "sha256") } ?: error("原附件或内容版本已变化，请刷新后核对")
                controller.openConversationAttachment(message.id ?: error("原消息身份缺失"), file)
            }
            "tasks.close" -> restoreTaskWorkspace()
            "tasks.stop" -> {
                checkedPayload(args, setOf("id", "runId", "epoch"))
                val task = requireTask(requiredString(args, "id"))
                // Preserve the execution displayed when Stop was clicked. Looking up
                // by task id alone would silently retarget a newly resumed run.
                // An unadmitted preparing/queued task has epoch=0 and may omit runId.
                val run = requiredString(args, "runId", empty = true)
                val epoch = requiredInt(args, "epoch")
                require(epoch >= 0 && (run.isNotBlank() || epoch == 0) && task.raw.optString("runId") == run &&
                    requiredInt(task.raw, "coordinatorEpoch") == epoch) { "原任务执行已变化，请刷新后核对要停止的执行" }
                require(canUseTaskControls(task) && PocketWebTaskActions.canStopDirectly(task) && (!PocketWebTaskActions.runtimeSetupTask(task) || controller.webPrivateOwnerVisible())) { "请回这项任务的原能力审核窗口操作" }
                controller.stopReviewedTask(task)
            }
            "projects.refresh" -> { controller.state.projectId?.let(controller::selectProject) ?: controller.refresh() }
            "projects.select" -> { leaveTask(); leaveConnection(); reviews.clear(); controller.selectProject(requiredString(args, "id")) }
            "projects.create" -> controller.createProject(requiredString(args, "name"))
            "projects.open", "projects.newFile" -> {
                require(controller.state.projectId == requiredString(args, "projectId")) { "原项目已变化" }
                val path = checkedPath(args)
                if (action == "projects.open") controller.openFile(path) else controller.newFile(path)
            }
            "projects.edit", "projects.save" -> {
                requireFile(args)
                if (action == "projects.save") require(!controller.state.fileSaving) { "正在保存原文件，请等待回执" }
                controller.editFile(requiredString(args, "content", true))
                if (action == "projects.save") controller.saveFile()
            }
            "projects.close" -> { requireFile(args); controller.closeFile() }
            "projects.history" -> { requireFile(args); controller.fileHistory() }
            "projects.revision" -> { requireFile(args); controller.previewRevision(requiredInt(args, "revision")) }
            "projects.useRevision" -> { requireFile(args); require(controller.state.revisionNumber == requiredInt(args, "revision")); controller.useRevision() }
            "projects.retry" -> { require(controller.state.projectId == requiredString(args, "projectId") && controller.state.filePath == checkedPath(args)) { "原文件已变化" }; controller.retryFile() }
            "settings.reviewModelTransport" -> {
                val target = ModelConfig.cleartextConsentFor(requiredString(args, "provider"), requiredString(args, "url"))
                return JSONObject().put("required", !target.isNullOrEmpty()).put("consentTarget", target ?: JSONObject.NULL)
                    .put("displayTarget", target?.substringAfter('|') ?: JSONObject.NULL)
            }
            "settings.openBrowser" -> {
                checkedPayload(args, setOf("purpose"))
                when (requiredString(args, "purpose")) {
                    "research" -> controller.openBrowserResearch()
                    "browse" -> controller.openBrowser()
                    else -> error("请选择网页研究工作区或内置浏览器")
                }
            }
            "settings.openAppUpdates" -> {
                checkedPayload(args, emptySet())
                check(controller.webPrivateOwnerVisible()) { "请在前台私有设置中打开应用更新" }
                val activity = controller.webPresentationActivity()
                activity.startActivity(android.content.Intent(activity, dev.pocketengine.features.updates.AppUpdateActivity::class.java))
            }
            "settings.saveLocalObservation" -> controller.saveLocalObservation(requiredBoolean(args, "capture"), requiredBoolean(args, "ocr"))
            "settings.openSystem" -> when (requiredString(args, "section")) {
                "accessibility" -> controller.openAccessibility(); "notifications" -> controller.openNotifications()
                "wireless" -> controller.openWirelessSettings(); "background" -> controller.openBackgroundSetup()
                else -> error("没有已接入的系统设置入口")
            }
            "connections.enter" -> {
                val next = requiredString(args, "section"); require(next in connectionSections) { "未知能力页面" }
                taskReturnSection = null
                enterConnection(next)
            }
            "connections.leave" -> {
                checkedPayload(args, setOf("section"))
                val previous = requiredString(args, "section")
                if (taskReturnSection == previous) taskReturnSection = null
                if (section == previous) { leaveConnection(); reviews.clear() }
            }
            "usage.close" -> controller.closeNativeInferenceHistory()
            "phone.draft.edit" -> phoneComposer.editDraft(args)
            "phone.deadline" -> controller.selectNewTaskDeadline(requiredInt(args, "minutes"))
            "background.refresh" -> controller.refreshBackgroundSetup()
            "background.discover" -> {
                require(controller.state.activeTaskId == null && !controller.state.feature("display").optBoolean("taskOwned")) { "原手机任务仍占用执行环境，未重新发现连接" }
                controller.display("adb.discover")
            }
            "background.cancel" -> controller.display("adb.cancel")
            "background.close" -> { background.clear(); controller.closeBackgroundSetup() }
            "background.pair" -> controller.pairWireless(requiredString(args, "host"), requiredInt(args, "port"), requiredString(args, if (args.has("pairingCode")) "pairingCode" else "code"))
            "background.connect" -> controller.connectWireless(requiredString(args, "host"), requiredInt(args, "port"), if (args.has("pairingPort")) requiredInt(args, "pairingPort") else null)
            "background.mainScreen", "background.selectMainScreen" -> controller.selectMainScreenCompatibility()
            "queued.open" -> {
                require(section == null && taskViewId == null && controller.webPrivateOwnerVisible() && controller.queuedInputsPage?.saving != true) { "请回到原对话管理排队消息" }
                val conversation = requiredString(args, "conversationId")
                require(conversation == controller.state.conversationId) { "原对话已变化，未打开其他对话的队列" }
                controller.openQueuedOwnerInputs(requiredString(args, "taskId"), conversation)
            }
            "queued.refresh" -> { requireQueuedWindow(args); controller.refreshQueuedOwnerInputs() }
            "queued.close" -> { requireQueuedWindow(args, closing = true); controller.closeQueuedOwnerInputs() }
            "queued.edit" -> { requireQueuedWindow(args); controller.openQueuedOwnerInputEditor(requiredString(args, "messageId"), requiredBoolean(args, "withdrawn")) }
            "queued.change" -> { requireQueuedWindow(args, editor = true); if (args.has("text")) controller.editQueuedOwnerInputText(requiredString(args, "text", true)); if (args.has("mode")) controller.editQueuedOwnerInputMode(requiredString(args, "mode")) }
            "queued.save" -> { requireQueuedWindow(args, editor = true); controller.saveQueuedOwnerInput() }
            "queued.editor.close" -> { requireQueuedWindow(args, editor = true, closing = true); controller.closeQueuedOwnerInputEditor() }
            "accounts.openAuthorization" -> { requireSection("accounts"); controller.openAccountAuthorization(requiredString(args, "sessionId")) }
            "accounts.manageUsage" -> { requireSection("accounts"); checkedPayload(args, emptySet()); controller.openChatGptUsage() }
            "memory.list", "memory.search" -> { requireSection("memory"); return accepted(controller.requestFeature("memory", action.substringAfter('.'), checkedPayload(args, setOf("query", "cursor", "layer", "limit")))) }
            "memoryReview.list", "memoryReview.candidates", "memoryReview.getCandidate", "memoryReview.capacity", "memoryReview.history", "memoryReview.openSource" -> {
                requireSection("memoryReview")
                val command = when (action) { "memoryReview.candidates" -> "listCandidates"; "memoryReview.capacity" -> "candidateCapacity"; "memoryReview.history" -> "listCandidateHistory"; else -> action.substringAfter('.') }
                return accepted(controller.requestOwnerDomain("memoryReview", command, checkedPayload(args, setOf("id", "revision", "cursor", "sourceRef", "layer"))))
            }
            in PocketWebMcp.COMMANDS -> {
                requireSection("mcp")
                return mcpImports.dispatch(action, args)
            }
            "learning.snapshot" -> { requireSection("learning"); return accepted(controller.requestOwnerDomain("learning", "snapshot")) }
            "remote.probe", "remote.environments" -> {
                requireSection("remote"); checkedPayload(args, setOf("profileId"))
                val id = requiredString(args, "profileId")
                require(controller.state.feature("remote").optJSONArray("profiles").objects().any { it.optString("id") == id }) { "原电脑连接已变化，请刷新后重新核对" }
                return accepted(controller.requestFeature("remote", action.substringAfter('.'), JSONObject().put("profileId", id)))
            }
            "local.termuxProbe" -> { requireSection("local"); checkedPayload(args, emptySet()); return accepted(controller.requestFeature("local", "termux.probe")) }
            "local.inspectRuntime", "local.readReceipts" -> {
                requireSection("local"); checkedPayload(args, emptySet())
                return accepted(controller.requestFeature("local", if (action == "local.inspectRuntime") "probe" else "listJobs"))
            }
            in PocketWebAutomation.COMMANDS -> {
                if (action == "automation.receipts") require(section in setOf("automation", "commitments")) { "原提醒窗口已关闭" }
                else requireSection("automation")
                return automation.dispatch(action, args)
            }

            "channels.inspect" -> { requireSection("channels"); checkedPayload(args, emptySet()); return accepted(controller.requestFeature("channels", "inspect")) }
            "skills.read" -> {
                requireSection("skills"); checkedPayload(args, setOf("id", "path", "offset", "length"))
                val id = requiredString(args, "id")
                require(controller.state.feature("skills").optJSONArray("skills").objects().any { it.optString("id") == id }) { "原技能不在当前读取窗口" }
                require(!args.has("path") || requiredString(args, "path") == "SKILL.md") { "当前仅接入原技能说明读取" }
                val offset = if (args.has("offset")) requiredInt(args, "offset") else 0
                val length = if (args.has("length")) requiredInt(args, "length") else 8000
                require(offset >= 0 && length in 1..8000) { "技能说明读取范围无效" }
                return accepted(controller.requestFeature("skills", "read", JSONObject().put("id", id).put("path", "SKILL.md").put("offset", offset).put("length", length)))
            }
            else -> error("这项操作没有已接入的宿主实现")
        }
        return null
    }

    private fun accountCommand(id: String, action: String, args: JSONObject) {
        requireSection("accounts")
        val write = action in setOf("accounts.model.select", "accounts.logout.confirm")
        if (write) {
            requireModelSelectionIdle()
            checkedPayload(args, if (action == "accounts.model.select") setOf("reviewHandle", "options") else setOf("reviewHandle"))
            val completed: (Result<JSONObject>) -> Unit = { result ->
                if (!closed && section == "accounts") controller.requestOwnerDomain("accounts", "snapshot")
                result.fold({ value -> complete(id, JSONObject().put("status", "saved").put("receipt", PocketWebState.publicValue(value)), null) },
                    { complete(id, null, "原账号操作结果待核对：" + (it.message ?: "请刷新原账号"), "outcome_unknown") })
                publishState()
            }
            if (action == "accounts.model.select") {
                require(!args.has("options") || args.opt("options") is JSONObject) { "模型选项必须是原审核中的选项对象" }
                val selection = accountWorkspace.takeModelSelection(requiredString(args, "reviewHandle"), args.optJSONObject("options") ?: JSONObject())
                controller.runModelSelectionWrite(selection.selectionRevision, { accountWorkspace.selectModel(selection) }, completed)
            } else {
                val original = accountWorkspace.takeLogout(requiredString(args, "reviewHandle"))
                controller.runModelSelectionWrite(original.selectionRevision, { accountWorkspace.logout(original) }, completed)
            }
            return
        }
        checkedPayload(args, if (action == "accounts.model.review") setOf("accountId", "modelId") else setOf("accountId", "expectedVersion"))
        val account = requiredString(args, "accountId")
        val version = if (action == "accounts.model.review") 0L else args.get("expectedVersion").also { require((it is Int || it is Long) && (it as Number).toLong() >= 0) { "请刷新原账号版本" } }.let { (it as Number).toLong() }
        val model = if (action == "accounts.model.review") requiredString(args, "modelId") else ""
        controller.runModelConnectionWork(Runnable {
            val result = runCatching { when (action) {
                "accounts.models" -> accountWorkspace.readModels(account, version)
                "accounts.model.review" -> accountWorkspace.reviewModel(account, model)
                else -> accountWorkspace.reviewLogout(account, version)
            } }
            main.post {
                if (closed) return@post
                result.fold({ value ->
                    if (accountWorkspace.projectionCurrent(value)) {
                        complete(id, PocketWebState.publicValue(value) as JSONObject, null)
                        if (action == "accounts.models") controller.requestOwnerDomain("accounts", "snapshot")
                    }
                    else complete(id, null, "原账号或模型选择已变化，请重新读取后核对")
                }, { complete(id, null, it.message ?: "原账号记录暂不可读取") })
                publishState()
            }
        })
    }

    private fun readModelSnapshot(requestId: String? = null) {
        val service = controller.nativeModelConnections()
        if (service == null) {
            domainErrors["models"] = "本机模型连接目录尚未准备，原模型设置保留"
            requestId?.let { complete(it, null, domainErrors["models"]) }; publishState(); return
        }
        val generation = domainGeneration
        try {
            controller.runModelConnectionWork(Runnable {
                val result = runCatching { currentModelSnapshot(service) }
                main.post {
                    if (closed) return@post
                    if (generation != domainGeneration) { requestId?.let { complete(it, null, "原能力窗口已变化，未把迟回结果接到其他页面") }; return@post }
                    result.fold({ read ->
                        val (snapshot, observed) = read
                        val current = service.modelSelection().status()
                        if (current.pending() || observed.pending() || current.revision() != observed.revision()) {
                            requestId?.let { complete(it, null, "模型设置已变化，请等待保存回执后重新读取") }; return@fold
                        }
                        domainSnapshots["models"] = JSONObject(snapshot.toString()); domainErrors.remove("models")
                        requestId?.let { complete(it, PocketWebState.publicValue(snapshot) as JSONObject, null) }
                    }, { error -> domainErrors["models"] = error.message ?: "本机模型目录暂不可读取"; requestId?.let { complete(it, null, domainErrors["models"]) } })
                    publishState()
                }
            })
        } catch (failure: Exception) {
            domainErrors["models"] = failure.message ?: "本机模型目录读取未接受"
            requestId?.let { complete(it, null, domainErrors["models"]) }; publishState()
        }
    }

    private fun currentModelSnapshot(service: dev.pocketengine.runtime.models.ModelConnectionService): Pair<JSONObject, dev.pocketengine.runtime.models.ModelSelectionCoordinator.Status> {
        val projection = service.modelSelection().readStable { service.connections() }
        return projection.value() to dev.pocketengine.runtime.models.ModelSelectionCoordinator.Status(projection.revision(), false)
    }

    private fun modelConnection(requestId: String, action: String, args: JSONObject) {
        requireSection("models")
        requireModelSelectionIdle()
        val command = PocketWebModelConnectionCommands.prepare(action, args)
        val completed: (Result<JSONObject>) -> Unit = { result ->
            if (!closed && action != "models.connection.test") {
                domainSnapshots.remove("models")
                // A mutation result proves that operation, not the currently selected model.
                readModelSnapshot()
            }
            result.fold({ snapshot ->
                if (action.startsWith("models.connections.")) complete(requestId, PocketWebState.publicValue(snapshot) as JSONObject, null)
                else complete(requestId, JSONObject().put("status", if (action == "models.connection.test") "tested" else "saved").put("snapshot", PocketWebState.publicValue(snapshot)), null)
            }, { error -> complete(requestId, null, "原连接操作结果待核对：" + (error.message ?: "请刷新原连接记录"), "outcome_unknown") })
            publishState()
        }
        when (command) {
            is PocketWebModelConnectionCommands.Select -> {
                val service = controller.nativeModelConnections() ?: error("本机模型连接尚未准备")
                controller.runModelSelectionWrite({ requireModelSelectionIdle(ownMutation = true); service.selectConnection(command.id, command.expectedRevision) }, completed)
            }
            is PocketWebModelConnectionCommands.Test -> {
                val generation = domainGeneration; val document = documentId; val window = captureWindow()
                controller.testApiConnection(command.id, command.expectedRevision, command.input, command.editorId, command.draftRevision, {
                    !closed && section == "models" && document != null && document == documentId && generation == domainGeneration &&
                        acceptsWindow(window) && controller.webPrivateOwnerVisible()
                }, completed)
            }
            is PocketWebModelConnectionCommands.Save ->
                controller.saveApiConnection(command.adding, command.id, command.expectedRevision, command.name, command.input, completed, command.useForNewTasks)
        }
    }

    private fun accountOwnerCommand(requestId: String, action: String, args: JSONObject) {
        requireSection("accounts")
        val command = PocketWebAccountCommands.prepare(action, args)
        val generation = domainGeneration; val document = documentId; val owner = controller.webWindowTicket()
        controller.requestOwnerDomainReceipt("accounts", command.command(), command.payload()) { result, error ->
            if (closed) return@requestOwnerDomainReceipt
            if (generation != domainGeneration || document != documentId || section != "accounts" || owner != controller.webWindowTicket() || !controller.webPrivateOwnerVisible())
                complete(requestId, null, "原账号页面已变化，请重新读取授权状态；没有自动重试", "outcome_unknown")
            else if (error != null) {
                val message = if (error.matches(Regex("[A-Z][A-Z0-9_]+"))) accountConnectionsFailure(error) else error
                complete(requestId, null, message, "native_failure")
            } else {
                val receipt = result ?: JSONObject()
                complete(requestId, JSONObject().put("status", "completed").put("action", action).put("command", command.command())
                    .put("receipt", PocketWebState.publicValue(receipt)), null)
                // Only the matching completed beginLogin may open this still-current native session.
                // The controller independently checks session identity and pending status again.
                if (command.command() == "beginLogin" && receipt.optString("status") == "pending" && receipt.optString("sessionId").isNotBlank())
                    controller.openAccountAuthorization(receipt.getString("sessionId"))
            }
            publishState()
        }
    }

    private fun requireModelSelectionIdle(ownMutation: Boolean = false) {
        val state = controller.state
        check(!closed && controller.webUiCanInteract()) { "原模型界面已不在前台" }
        check(!state.chatSubmitting && !state.phoneSubmitting && !state.modelSaving && !state.modelTesting && (ownMutation || !controller.modelSelectionPending())) { "请先等待原提交和模型设置回执，再切换后续任务的模型" }
    }

    private fun reviewModel(requestId: String, model: String) {
        requireModelSelectionIdle()
        check(!modelSelectionBusy) { "正在核对原模型选择，请等待回执" }
        val service = controller.nativeModelConnections() ?: error("本机模型连接目录尚未准备")
        val generation = domainGeneration; val document = documentId
        controller.runModelConnectionWork(Runnable {
            val result = runCatching { currentModelSnapshot(service) }
            main.post {
                if (closed) return@post
                try {
                    check(generation == domainGeneration && document == documentId) { "原模型窗口已变化，请重新选择" }
                    requireModelSelectionIdle()
                    val (snapshot, selection) = result.getOrThrow()
                    check(!selection.pending() && service.modelSelection().status() == selection) { "模型设置已在另一界面变化，请重新核对" }
                    val created = modelReviews.create(snapshot, model, generation, document, selection.revision())
                    val review = created.review
                    domainSnapshots["models"] = JSONObject(snapshot.toString()); domainErrors.remove("models")
                    complete(requestId, JSONObject().put("reviewHandle", created.handle).put("modelId", model).put("outputTokens", review.output)
                        .put("reasoning", review.reasoning.toJson()).put("imageTokenReserve", review.imageReserve).put("expiresInMs", 120000), null)
                } catch (failure: Exception) { complete(requestId, null, failure.message ?: "原模型选择暂不可核对") }
                publishState()
            }
        })
    }

    private fun selectModel(requestId: String, args: JSONObject) {
        val review = modelReviews.take(requiredString(args, "reviewHandle"))
        requireModelSelectionIdle()
        check(!modelSelectionBusy && SystemClock.elapsedRealtime() < review.expires && review.generation == domainGeneration && review.document == documentId) { "原模型窗口已变化或审阅已过期，请重新核对" }
        val output = if (args.has("outputTokens")) requiredInt(args, "outputTokens") else review.output
        require(output in 128..32768) { "每次输出上限应为 128–32768" }
        val options = if (args.has("reasoning")) ReasoningOptions.fromJson(args.getJSONObject("reasoning")) else review.reasoning
        val reserve = if (args.has("imageTokenReserve")) requiredInt(args, "imageTokenReserve") else review.imageReserve
        require(reserve >= 0) { "图像预留预算不能为负数" }
        val service = controller.nativeModelConnections() ?: error("本机模型连接目录尚未准备")
        modelSelectionBusy = true; publishState()
        try {
            controller.runModelSelectionWrite(review.selectionRevision, {
                    requireModelSelectionIdle(ownMutation = true)
                    check(review.generation == domainGeneration && review.document == documentId && SystemClock.elapsedRealtime() < review.expires) { "原模型窗口已关闭或审阅已过期" }
                    val current = service.connections()
                    check(ModelBinding.fromJson(current.getJSONObject("binding")) == review.binding && PocketWebModelReviews.identity(current, review.model) == review.identity) { "原连接、模型能力或选择参数已变化，请刷新后重新核对" }
                    service.select(review.binding, review.model, output, options, reserve)
                }) { result ->
                    modelSelectionBusy = false
                    if (closed) return@runModelSelectionWrite
                    domainSnapshots.remove("models"); readModelSnapshot()
                    result.fold({ snapshot ->
                        domainErrors.remove("models"); controller.modelConnectionChanged()
                        complete(requestId, JSONObject().put("accepted", true).put("modelId", review.model).put("snapshot", PocketWebState.publicValue(snapshot)), null)
                    }, { failure -> complete(requestId, null, failure.message ?: "原模型选择未被接受") })
                    publishState()
                }
        } catch (failure: Exception) {
            modelSelectionBusy = false; publishState(); throw failure
        }
    }

    private fun openOriginalTask(requestId: String, taskId: String, requestNavigation: Boolean, returnSection: String? = section ?: taskReturnSection) {
        require(returnSection == null || returnSection in connectionSections) { "原工作页面已变化" }
        leaveTask(); leaveConnection(); reviews.clear()
        taskReturnSection = returnSection
        taskViewId = taskId
        publishState()
        val window = captureWindow()
        controller.readTaskForOpen(taskId, { !closed && acceptsWindow(window) }) { task, error ->
            if (closed) return@readTaskForOpen
            if (!acceptsWindow(window)) complete(requestId, null, "原任务窗口已关闭，请重新打开")
            else if (error != null || task == null) {
                if (requestNavigation) runCatching { restoreTaskWorkspace() }
                complete(requestId, null, error ?: "原任务暂不可读取")
                publishState()
            } else try {
                if (isGroup(task)) taskPage = controller.enterGroupTask(task.id)
                controller.completeWebTaskOpen(task.id, requestNavigation)
                complete(requestId, JSONObject().put("taskId", task.id).put("readOnly", true).put("executionAuthorized", false), null)
                publishState()
            } catch (failure: Exception) {
                if (requestNavigation) runCatching { restoreTaskWorkspace() }
                complete(requestId, null, failure.message ?: "原任务窗口不可用")
                publishState()
            }
        }
    }
    private fun restoreTaskWorkspace() {
        val original = taskReturnSection; taskReturnSection = null
        leaveTask(); reviews.clear()
        if (original != null) {
            workspaceResumeRevision++
            enterConnection(original)
        }
    }
    private fun enterConnection(next: String) {
        leaveTask(); leaveConnection(); reviews.clear(); section = next
        // Reuse the original owner entry after a task sheet; no previous review survives it.
        controller.prepareConnectionEntry()
        if (next == "memory") controller.enterOwnerDomain("memoryReview") else if (next in ownerDomains) controller.enterOwnerDomain(next) else controller.refresh()
        if (next == "models") readModelSnapshot()
        when (next) {
            "memory" -> controller.requestFeature("memory", "list")
            "automation" -> { controller.requestFeature("automation", "list"); controller.requestFeature("automation", "receipts") }
            "commitments" -> controller.requestFeature("automation", "receipts")
            "channels" -> controller.requestFeature("channels", "inspect")
        }
        if (next == "devices") {
            deviceWindow = (controller.nativeDeviceWorkspace() ?: error("本机设备工作区尚未准备")).openOwnerWindow()
            domainSnapshots.remove("devices"); domainErrors.remove("devices")
        }
    }
    private fun isGroup(task: UiTask) = task.raw.optJSONObject("origin")?.optString("kind") == "group"
    private fun canUseTaskControls(task: UiTask) = task.raw.optJSONObject("origin")?.optString("kind") != "paired_device" && (!isGroup(task) || taskPage != null && controller.groupTaskReady)
    private fun requireTask(id: String): UiTask = controller.state.tasks.firstOrNull { it.id == id } ?: error("原任务不在当前读取窗口，请刷新后重新打开")
    private fun leaveTask() { phoneViewer.clear(); localPages.clear(); setupPages.clear(); tokenBudgets.clear(); profilePages.clear(); composerModels.clear(); taskViewId = null; controller.closeWebTaskView(); inference.clear(); trajectory.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); personal.clear(); memoryHistory.clear(); conversationOrganization.clear(); taskCapabilities.clear(); projectRuntime.clear(); projectLivePreview?.clear(); modelDiagnostics.clear(); attachmentPages.clear(); remoteTasks.clear(); teamEvidence.clear(); experience.clear(); routeReferences.clear(); commandFence.invalidate(); taskReviewGeneration++; taskPage?.let(controller::leaveGroupTask); taskPage = null }
    private fun leaveConnection() { phoneViewer.clear(); controller.webChannels.clear(); controller.webSkills.clear(); commandFence.invalidate(); ++domainGeneration; modelReviews.clear(); automation.clear(); mcpImports.clear(); executionReviews.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); trajectory.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); personal.clear(); memoryHistory.clear(); conversationOrganization.clear(); taskCapabilities.clear(); projectRuntime.clear(); projectLivePreview?.clear(); modelDiagnostics.clear(); attachmentPages.clear(); remoteTasks.clear(); teamEvidence.clear(); experience.clear(); routeReferences.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); devicePages.clear(); pluginPages.clear(); localPages.clear(); setupPages.clear(); accountWorkspace.invalidate(); composerModels.clear(); aiSources.clear(); conversationModels.clear(); deviceWindow?.close(); deviceWindow = null; section?.let { if (it == "memory") controller.leaveOwnerDomain("memoryReview") else if (it in ownerDomains) controller.leaveOwnerDomain(it) }; section?.takeIf { it == "archives" || it == "storage" }?.let(dataPages::close); section = null }
    private fun requireSection(expected: String) { require(section == expected) { "原能力窗口已关闭，请重新进入" } }
    private fun requireComposer(args: JSONObject, optional: Boolean = false) {
        val draft = controller.composerDraft ?: error("原输入尚未恢复")
        if (!optional || args.has("key") || args.has("epoch")) require(requiredString(args, "key") == draft.key && requiredInt(args, "epoch") == controller.composerViewEpoch) { "原输入窗口已变化" }
    }
    private fun requireFile(args: JSONObject) {
        val state = controller.state
        require(state.projectId == requiredString(args, "projectId") && state.filePath == checkedPath(args) && state.fileVersion == requiredInt(args, "version")) { "原项目文件或版本已变化，编辑草稿仍保留，请重新核对" }
    }
    private fun requireConversationReader(args: JSONObject, closing: Boolean = false): dev.pocketengine.ui.ConversationReadPage {
        val page = controller.conversationReader ?: error("原历史阅读窗口已关闭")
        require(page.window == requiredLong(args, "window") && page.conversation == requiredString(args, "conversationId") && (closing || controller.canReadConversationWindow(page))) { "原历史阅读窗口已变化，请重新打开" }
        return page
    }
    /** A delayed click can never edit or close the next queue/editor window. */
    private fun requireQueuedWindow(args: JSONObject, editor: Boolean = false, closing: Boolean = false) {
        val page = controller.queuedInputsPage ?: error("原排队消息窗口已关闭")
        require(page.window == requiredLong(args, "window") && page.task == requiredString(args, "taskId") && page.conversation == requiredString(args, "conversationId")) { "原排队消息窗口已变化，请重新打开" }
        require(!page.saving) { "请等待原修改回执" }
        if (!closing) require(section == null && taskViewId == null && controller.webPrivateOwnerVisible() && controller.queuedInputWindowCurrent && page.conversation == controller.state.conversationId) { "原对话窗口已变化，请重新打开排队消息" }
        if (editor) {
            val edit = page.editor ?: error("原消息编辑窗口已关闭")
            require(edit.message == requiredString(args, "messageId") && edit.queueRevision == requiredLong(args, "queueRevision") && edit.messageRevision == requiredLong(args, "messageRevision")) { "原消息版本已变化，请重新读取后修改" }
        }
    }
    private fun checkedPath(args: JSONObject): String = requiredString(args, "path").also { path -> require(!path.startsWith('/') && !path.contains('\u0000') && path.split('/').none { it == ".." }) { "文件路径不属于当前项目" } }
    private fun accepted(requestId: String? = null): JSONObject = JSONObject().put("accepted", true).also { if (requestId != null) it.put("requestId", requestId) }
    private fun checkedPayload(args: JSONObject, allowed: Set<String>): JSONObject {
        require(args.keys().asSequence().all { it in allowed }) { "操作包含未接入的参数" }
        return JSONObject(args.toString())
    }
    private fun requiredString(args: JSONObject, name: String, empty: Boolean = false): String {
        val value = args.opt(name); require(value is String && (empty || value.isNotBlank())) { "缺少有效参数 $name" }; return value
    }
    private fun requiredInt(args: JSONObject, name: String): Int {
        val value = args.opt(name); require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "缺少有效整数参数 $name" }; return value.toInt()
    }
    private fun requiredLong(args: JSONObject, name: String): Long {
        val value = args.opt(name)
        require(value is Number && value.toDouble().isFinite() &&
            value.toDouble() in -9007199254740991.0..9007199254740991.0 &&
            value.toDouble() == value.toLong().toDouble()) { "缺少有效整数参数 $name" }
        return value.toLong()
    }
    private fun requiredBoolean(args: JSONObject, name: String): Boolean {
        val value = args.opt(name); require(value is Boolean) { "缺少有效开关参数 $name" }; return value
    }
}

