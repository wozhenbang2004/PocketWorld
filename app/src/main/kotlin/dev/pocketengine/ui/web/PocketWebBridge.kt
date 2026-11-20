package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import dev.pocketengine.EngineApplication.OwnerWorkControlRejected
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
import dev.pocketengine.ui.UiTaskResult
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

    fun create(task: UiTask, actions: Set<String>, positiveApprovalIds: Set<String> = emptySet(), bindingTask: UiTask = task): String {
        if (bindingTask !== task) {
            require(task.raw.optString("taskProjection") == "approval_only" && actions.all { it == "tasks.approve" } &&
                task.id == bindingTask.id && task.status == bindingTask.status && task.updatedAt == bindingTask.updatedAt &&
                task.raw.getString("runId") == bindingTask.raw.getString("runId") &&
                task.raw.getInt("coordinatorEpoch") == bindingTask.raw.getInt("coordinatorEpoch")) { "原审批投影与任务窗口不一致" }
        }
        entries.entries.removeAll { it.value.expires <= now() }
        while (entries.size >= 24) entries.remove(entries.keys.first())
        val copy = task.copy(raw = JSONObject(task.raw.toString()))
        val handle = UUID.randomUUID().toString()
        entries[handle] = Reviewed(copy, identity(if (bindingTask === task) copy else bindingTask), actions.toSet(), now() + 120_000L, positiveApprovalIds.toSet())
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
    private val phoneObservationExpiry = PocketWebPhoneObservationExpiry(main) { original, rows ->
        if (!closed && started && original == revision) {
            // A subsequent genuine observation may look identical after the old fact was retired.
            lastState = null
            if (controller.webPrivateOwnerVisible()) emit(JSONObject().put("v",1).put("kind","privatePhoneObservationExpired").put("revision",original).put("sources",rows))
        }
    }
    private val choreographer by lazy { Choreographer.getInstance() }
    private var statePublicationScheduled = false
    private val statePublication = Choreographer.FrameCallback {
        statePublicationScheduled = false
        publishStateNow()
    }
    private var section: String? = null
    private var taskPage: String? = null
    // A task-list stub or proposed ID is not permission to expose that owner's detailed project requirements.
    private data class GoalWindow(val task:String,val run:String,val epoch:Int,val project:String,val checkout:String,val ticket:PocketWebWindowTicket)
    private var projectGoal:GoalWindow?=null
    private fun canReadProjectGoal(row:UiTask):Boolean {
        val pin=projectGoal ?: return false
        if (row.id!=pin.task || taskViewId!=pin.task || section!=null || !controller.webPrivateOwnerVisible() || !acceptsWindow(pin.ticket)) return false
        val raw=row.raw; val d=raw.optJSONObject("developmentContext") ?: return false
        return raw.optString("kind")=="assistant" && raw.optString("actorId")=="owner" && raw.optString("spaceId")=="private" &&
            !raw.has("origin") && raw.optString("runId")==pin.run && raw.optInt("coordinatorEpoch")==pin.epoch &&
            d.optString("projectId")==pin.project && d.optString("checkoutId")==pin.checkout
    }
    @Volatile private var taskViewId: String? = null
        set(value) { if (field != value) fileRemainder.clear(); field = value }
    private val fileRemainder=PocketWebTeamFileRemainder<dev.pocketengine.features.teams.TaskStoreTeamWriteHost.RemainderReview>(
        controller::runTeamPhoneWork, { main.post(it) }, { it.view() }, { publishState() })

    private data class PauseNoteReview(val handle: String, val task: String, val run: String, val epoch: Int, val pause: Long, val scope: Long,
                                       val window: PocketWebWindowTicket, val expires: Long)
    private var pauseNoteReview: PauseNoteReview? = null
    @Volatile private var pauseReadSerial = 0L
    private var taskReturnSection: String? = null
    private var workspaceResumeRevision = 0L
    private var taskReviewGeneration = 0L
    private val commandFence = PocketWebCommandFence()
    private val reviews = PocketWebTaskReviews()
    private val taskActions = PocketWebTaskActions(controller, reviews, ::canUseTaskControls) { work -> main.post(work) }
    private val taskHistory = PocketWebTaskHistory()
    private val workSearch = PocketWebWorkSearch(controller, ::captureWindow, ::acceptsWindow, taskHistory::remember)
    private val formDrafts = PocketWebFormDrafts(controller.webPresentationActivity().applicationContext)
    private val automation = PocketWebAutomation(controller)
    private val mcpImports = PocketWebMcp(controller)
    private val sourceReader = PocketWebSources(controller)
    private val messageQuotes = PocketWebMessageQuotes(controller)
    private val inference = PocketWebInference(controller)
    private val trajectory = PocketWebTrace(controller)
    private val skillDrafts = PocketWebSkillDrafts(controller)
    private val memoryDrafts = PocketWebMemoryDrafts(controller)
    private val personal = PocketWebPersonal(controller)
    private val memoryHistory = PocketWebMemoryHistory(controller)
    private val conversationOrganization = PocketWebConversationOrganization(controller)
    private val taskCapabilities = PocketWebTaskCapabilities(controller, { publishState() }, { id -> taskViewId == id })
    private val deviceTaskScope = PocketWebDeviceTaskScope(controller, { id -> taskViewId == id }, { !closed && documentId != null && section == "devices" && taskViewId == null })
    private val phoneControl = PocketWebPhoneControl(controller, { id -> taskViewId == id }, { !closed && documentId != null && section == "devices" && taskViewId == null })
    private val projectRuntime = PocketWebProjectRuntime(controller)
    private val attachmentPages = PocketWebAttachments(controller, ::captureWindow, ::acceptsWindow) { publishState() }
    private val modelDiagnostics = PocketWebModelDiagnostics(controller, ::captureWindow, ::acceptsWindow, { !closed && documentId != null && section == "models" }) { publishState() }
    private val developerLogs = PocketWebDeveloperLogs(controller, controller.webPresentationActivity(), ::captureWindow, ::acceptsWindow) { !closed && documentId != null }
    private val experience = PocketWebExperience(controller)
    private val routeReferences = PocketWebRouteReferences(controller)
    private val dataPages = PocketWebData(controller)
    private val remoteTasks = PocketWebRemoteTasks(controller, ::captureWindow, ::acceptsWindow, { taskViewId }) { publishState() }
    private val connectionPages = PocketWebConnections(controller, remoteTasks.inputPages)
    private val privateHostFinal = PocketWebPrivateHostFinal(controller,::captureWindow,::acceptsWindow)
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
    private val profilePages = PocketWebProfiles(controller, { !closed && documentId != null && section == "workProfiles" && controller.webPrivateOwnerVisible() })
    private val projectDraftDifference=PocketWebProjectDraftDifference(controller.nativeButlerHost(),{controller.nativeButlerHost().tasks},controller::runPluginCatalogWork,::captureWindow,::acceptsWindow,
        { controller.nativeButlerHost().features.module("local") as? dev.pocketengine.features.local.LocalModule ?: error("原本机项目宿主不可用") },
        { value -> leaveTask();controller.openedDraftWorkCopy(value);publishState() }) {
        taskViewId.takeIf{!closed&&documentId!=null&&section==null&&controller.state.filePath==null&&controller.webPrivateOwnerVisible()}
    }
    private val firstNodeWebOutput=PocketWebFirstNodeWebOutput({controller.nativeButlerHost().tasks},
        {controller.nativeButlerHost().features.module("local") as? dev.pocketengine.features.local.LocalModule ?: error("原本机项目宿主不可用")},
        controller::runPluginCatalogWork,::captureWindow,::acceptsWindow) {
        taskViewId.takeIf{!closed&&documentId!=null&&section==null&&controller.state.filePath==null&&controller.webPrivateOwnerVisible()}
    }
    private val projectWorkPages = PocketWebProjectWork(controller, { documentId }) {
        !closed && documentId != null && section == null && taskViewId == null && controller.state.filePath == null &&
            projectPreview?.snapshot()?.optBoolean("visible") != true && projectLivePreview?.snapshot()?.optBoolean("mounted") != true
    }
    private val localSourceIntake = PocketWebLocalSourceIntake(controller.projectPackages, {
        !closed && documentId != null && section == null && taskViewId == null &&
            controller.webPrivateOwnerVisible() && controller.state.filePath == null
    }, { controller.state.projectId })
    private val projectGitImport = PocketWebProjectGitImport(controller.projectGitImports) {
        !closed && documentId != null && section == null && taskViewId == null && controller.webPrivateOwnerVisible() && controller.state.filePath == null
    }
    private val taskPackagePreparation=PocketWebTaskPackagePreparation(controller)
    private val removedProjectFiles=PocketWebRemovedProjectFiles(controller,{ project -> !closed && documentId!=null && section==null && taskViewId==null &&
        controller.webPrivateOwnerVisible() && controller.state.projectId==project && controller.state.filePath==null },::captureWindow,::acceptsWindow)
    private val projectDevelopment = PocketWebProjectDevelopment(controller, { !closed && documentId != null && section == null && taskViewId == null && controller.webPrivateOwnerVisible() }, ::captureWindow, ::acceptsWindow)
    private val knowledge = PocketWebKnowledge(controller, projectDevelopment::conventionDraft)
    private val butlerPreparations = PocketWebButlerPreparations(controller, { !closed && documentId != null && controller.webPrivateOwnerVisible() }, ::captureWindow, ::acceptsWindow)
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
        { PocketWebWindowTicket(commandFence.ticket(), controller.webPhoneViewerWindowTicket()) },
        { ticket -> ticket.accepts(commandFence.ticket(), controller.webPhoneViewerWindowTicket()) },
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
        // Their result contains the local projection; user selection and business state are unchanged.
        // Account catalog reads and conversation reviews may renew a grant and
        // deliberately retain the normal state publication path.
        private val localModelPresentationCommands = setOf(
            "models.api.open", "models.api.review", "models.api.close",
            "models.accounts.open", "models.accounts.review", "models.accounts.close",
            "models.groups.snapshot", "models.conversation.snapshot", "models.conversation.close"
        )
        val commands: Set<String> = PocketWebLocal.PROJECT_OBSERVATION_COMMANDS + PocketWebLocalSourceIntake.COMMANDS + PocketWebFirstNodeWebOutput.COMMANDS + linkedSetOf(
            "conversation.captureHostGoal", "conversation.openHostGoal", "conversation.readPrivateHostFinal",
            "conversation.projectConvention.read", "conversation.projectConvention.stageFeedback",
            "app.ready", "app.refresh", "notice.dismiss", "notice.task.close", "phone.notice.refresh", "phone.notice.close", "error.dismiss", "execution.read", "execution.review", "execution.confirm",
            "composer.edit", "composer.send", "composer.mode", "composer.intent", "composer.reading", "composer.query", "composer.retry", "composer.returnPending",
            "chat.cancel", "ownerWork.input", "ownerWork.control", "message.origin", "conversation.new", "conversation.select", "conversation.directory", "conversation.reader.open", "conversation.reader.close", "conversation.reader.earlier", "conversation.reader.refresh", "conversation.reader.position", "conversation.reader.select", "conversation.reader.attachment",
            "ownerWork.supplement.read", "ownerWork.supplement.edit", "ownerWork.supplement.send", "ownerWork.supplement.query",
            "attachments.pick", "attachments.openPicker", "attachments.closePicker", "attachments.toggle", "attachments.remove", "attachments.open", "sources.open", "sources.read", "sources.close",
            "phone.request.original",
            "phone.resultImage.held", "phone.result.original", "phone.resultNotice.read", "phone.resultNotice.set", "phone.activity.original", "phone.draft.original",
            "quickVoice.start", "quickVoice.save", "quickVoice.close", "quickVoice.return",
            "skillDraft.open", "skillDraft.read", "skillDraft.source", "skillDraft.confirm", "skillDraft.cancel", "skillDraft.query", "skillDraft.close", "voice.start", "voice.stop", "voice.review", "voice.accept", "voice.discard", "speech.speak", "speech.stop", "speech.taskAnnouncements", "speech.stopTaskAnnouncement",
            PocketWebProjectReviewReturn.DIRECTORY, PocketWebProjectReviewReturn.ORIGINAL, "tasks.refresh", "tasks.open", "tasks.close", "tasks.readOriginalTeamFileDiff", "tasks.recoverTeamFile", "tasks.teamRemainder.read", "tasks.teamRemainder.close", "tasks.teamRemainder.continue", "tasks.stop", "tasks.review", "tasks.confirm", "tasks.approve", "tasks.resolve", "tasks.resume", "tasks.scope", "tasks.plan.enter", "tasks.answerQuestion", "tasks.answerQuestionChoice", "tasks.deleteScreenshot", "privacy.screenshots.read", "privacy.screenshots.save",
            "projects.refresh", "projects.select", "projects.create", "projects.open", "projects.newFile", "projects.edit", "projects.save", "projects.close", "projects.history", "projects.revision", "projects.useRevision", "projects.retry",
            PocketWebTaskPackagePreparation.PREPARE, PocketWebTaskPackagePreparation.CLOSE,
            "projects.historyComparison",
            "projects.removedFiles.list", "projects.removedFiles.history", "projects.removedFiles.text", "projects.removedFiles.close",
            "settings.reviewModelTransport", "settings.saveLocalObservation", "settings.openSystem", "settings.openBrowser", "settings.openAppUpdates",
            "webSearch.snapshot", "webSearch.save",
            "connections.enter", "connections.leave", "models.snapshot", "models.review", "models.select", "models.connection.add", "models.connection.edit", "models.connection.test", "models.connection.select", "devices.snapshot", "history.read", "usage.read", "usage.task", "usage.trends", "usage.trends.close", "usage.close", "usage.reestimate", "trace.read", "trace.detail", "trace.close",
            "phone.prepare", "phone.review.settings", "phone.start", "phone.query", "phone.draft.recentStarts", "phone.draft.retireUncreated", "phone.draft.edit", "phone.draft.fromMessage", "phone.pauseNote.read", "phone.pauseNote.continue", "phone.ownerStep.review", "phone.ownerStep.leave", "phone.pause", "phone.resume", "phone.stop", "phone.deadline", "background.refresh", "background.discover", "background.cancel", "background.close", "background.pair", "background.connect", "background.mainScreen", "background.selectMainScreen",
            "queued.open", "queued.refresh", "queued.close", "queued.edit", "queued.change", "queued.save", "queued.editor.close",
            "accounts.beginLogin", "accounts.cancelLogin", "accounts.openAuthorization", "accounts.select", "accounts.refresh", "accounts.models", "accounts.retryPlanUsage", "accounts.model.review", "accounts.model.select", "accounts.logout.review", "accounts.logout.confirm", "accounts.setEnabled", "accounts.refreshAuthorization", "accounts.reauthorize", "accounts.acknowledgePlanWelcome", "accounts.manageUsage",
            "memoryReview.list", "memoryReview.getCandidate", "memoryReview.capacity", "memoryReview.history", "memoryReview.openSource",
            "memoryReview.candidates", "mcp.snapshot", "mcp.pickConfig", "mcp.cancel", "mcp.discoverImport", "mcp.discoverDirect", "mcp.reviewImport", "mcp.installImport", "mcp.reviewEnabled", "mcp.confirmEnabled", "mcp.read", "mcp.reviewGrant", "mcp.confirmGrant", "learning.snapshot", "groups.snapshot",
            "remote.probe", "remote.environments", "local.termuxProbe", "local.inspectRuntime", "local.readReceipts", "local.prepareCheckout", "local.describeCheckout", "local.terminal.review", "local.terminal.confirm", "local.terminal.query", "local.terminal.stop", "local.terminal.open", "local.terminal.read", "local.terminal.input", "local.terminal.inputReceipt", "local.terminal.close", "automation.receipts", "automation.list", "automation.reviewSchedule", "automation.reviewRule", "automation.confirmReview", "automation.prepareOccurrence", "channels.inspect", "skills.read", "plugins.history", "proxy.status", "proxy.stop"
        ) + PocketWebSetup.COMMANDS + PocketWebModelConnectionCommands.COMMANDS + PocketWebAiSources.COMMANDS + PocketWebAiGroups.COMMANDS + PocketWebConversationModels.COMMANDS + PocketWebPermissionPolicy.COMMANDS + PocketWebData.COMMANDS + PocketWebConnections.COMMANDS + PocketWebMemoryDrafts.COMMANDS + setOf("conversation.drafts", "composer.firstMemory.save", "composer.oneLearning.save") + PocketWebKnowledge.COMMANDS + PocketWebPersonal.COMMANDS + PocketWebMessageQuotes.COMMANDS + PocketWebChannels.COMMANDS + PocketWebExperience.COMMANDS + PocketWebRouteReferences.COMMANDS + PocketWebSkills.COMMANDS + PocketWebPresentation.COMMANDS + PocketWebTeams.COMMANDS + PocketWebGroups.COMMANDS + PocketWebMedia.COMMANDS + PocketWebComposerModels.COMMANDS + PocketWebDevices.COMMANDS + PocketWebPlugins.COMMANDS + PocketWebRealtime.COMMANDS + PocketWebProxy.COMMANDS + PocketWebPlans.COMMANDS + PocketWebCommitments.COMMANDS + PocketWebTokenBudgets.COMMANDS + PocketWebProfiles.COMMANDS + PocketWebProjectDraftDifference.COMMANDS + PocketWebProjectWork.COMMANDS + PocketWebProjectPreview.COMMANDS + PocketWebPhoneViewer.COMMANDS + PocketWebBackground.COMMANDS + PocketWebMemoryHistory.COMMANDS + PocketWebModelDiagnostics.COMMANDS + PocketWebDeveloperLogs.COMMANDS + PocketWebAttachments.COMMANDS + PocketWebRemoteTasks.COMMANDS + PocketWebTeamEvidence.COMMANDS + PocketWebConversationOrganization.COMMANDS + PocketWebTaskCapabilities.COMMANDS + PocketWebPhoneControl.COMMANDS + PocketWebDeviceTaskScope.COMMANDS + PocketWebProjectRuntime.COMMANDS + PocketWebProjectLivePreview.COMMANDS + PocketWebRemoteInbox.COMMANDS + PocketWebProjectDevelopment.COMMANDS + dev.pocketengine.ui.ProjectFormalDrafts.COMMANDS + dev.pocketengine.ui.ProjectFormalFileExport.COMMANDS + PocketWebProjectGitImport.COMMANDS + PocketWebButlerPreparations.COMMANDS + PocketWebWorkSearch.COMMANDS + PocketWebFormDrafts.COMMANDS
    }

    /** Equivalent to the former Activity saveable presentation state, never an execution grant. */
    fun savePresentationState(): JSONObject = phoneComposer.savePresentation().put("projectDevelopment", projectDevelopment.savePresentation())

    fun restorePresentationState(saved: JSONObject?) {
        check(!started) { "Restore presentation before the Web document starts" }
        phoneComposer.restorePresentation(saved)
        projectDevelopment.restorePresentation(saved?.optJSONArray("projectDevelopment"))
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
    fun foreground(value: Boolean) { if (!value) { projectDraftDifference.clearWorkCopy();firstNodeWebOutput.clear();projectGoal=null; projectWorkPages.clear() }; projectPreview?.foreground(value); projectLivePreview?.foreground(value); phoneViewer.foreground(value); attachmentPages.foreground(value); remoteTasks.foreground(value); taskCapabilities.foreground(value); deviceTaskScope.foreground(value); phoneControl.foreground(value) }
    fun resource(uri: android.net.Uri, method: String, headers: Map<String, String>): android.webkit.WebResourceResponse? = attachmentPages.response(uri, method, headers)

    fun close() {
        if (closed) return
        closed = true
        controller.forgetHeldPhoneImage()
        started = false
        cancelStatePublication()
        presentation.close()
        projectDraftDifference.clearWorkCopy();firstNodeWebOutput.clear()
        localSourceIntake.close()
        projectGitImport.close()
        background.clear()
        remoteInbox.close()
        modelDiagnostics.close()
        developerLogs.close()
        attachmentPages.close(); remoteTasks.close()
        projectLivePreview?.close(); projectLivePreview = null
        projectPreview?.close(); projectPreview = null; phoneViewer.clear()
        snapshotObserver.close()
        reviews.clear()
        taskPackagePreparation.clear()
        taskHistory.clear(); workSearch.clear()
        phoneComposer.clearReviews()
        voiceInput.clear()
        modelReviews.clear(); automation.clear(); mcpImports.clear(); executionReviews.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); trajectory.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); personal.clear(); memoryHistory.clear(); conversationOrganization.clear(); taskCapabilities.clear(); deviceTaskScope.clear(); phoneControl.clear(); projectRuntime.clear(); projectLivePreview?.clear(); modelDiagnostics.clear(); attachmentPages.clear(); remoteTasks.clear(); teamEvidence.clear(); experience.clear(); routeReferences.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); projectWorkPages.clear(); devicePages.clear(); pluginPages.clear(); localPages.clear(); projectDevelopment.clear(); butlerPreparations.clear(); setupPages.clear(); accountWorkspace.close(); composerModels.close(); aiSources.close(); conversationModels.close(); controller.webChannels.clear(); controller.webSkills.clear()
        section?.takeIf { it == "archives" || it == "storage" }?.let(dataPages::close)
        section?.let { if (it == "memory") controller.leaveOwnerDomain("memoryReview") else if (it in ownerDomains) controller.leaveOwnerDomain(it) }
        deviceWindow?.close(); deviceWindow = null
        section = null; taskReturnSection = null; taskViewId = null
        taskPage?.let(controller::leaveGroupTask); taskPage = null
        controller.closeNativeInferenceHistory()
        removedProjectFiles.clear()
        controller.closeQueuedOwnerInputs()
        controller.persistOwnerComposer()
        fileRemainder.clear(); pendingCommands.clear(); commandReceipts.clear()
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
        controller.forgetHeldPhoneImage()
        cancelStatePublication()
        projectGoal=null; commandFence.invalidate()
        if (documentReplaced) controller.forgetShownPhoneActivity()
        if (documentReplaced) localSourceIntake.close() else localSourceIntake.pause()
        projectGitImport.close()
        phoneViewer.clear()
        background.clear()
        projectPreview?.invalidatePresentation(documentReplaced)
        taskReviewGeneration++
        domainGeneration++
        taskPackagePreparation.clear()
        taskHistory.clear(); workSearch.clear()
        reviews.clear(); phoneComposer.clearReviews(); voiceInput.clear(); modelReviews.clear(); automation.clear(); mcpImports.clear(); executionReviews.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); trajectory.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); memoryHistory.clear(); conversationOrganization.clear(); if (documentReplaced) taskCapabilities.clear() else taskCapabilities.background(); deviceTaskScope.clear(); phoneControl.clear(); projectRuntime.clear(); projectLivePreview?.clear(); modelDiagnostics.clear(); attachmentPages.clear(); remoteTasks.clear(); teamEvidence.clear(); experience.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); projectWorkPages.clear(); devicePages.clear(preserveImageChoice = !documentReplaced); pluginPages.clear(); localPages.clear(); projectDevelopment.invalidateDraftReturn(); projectDevelopment.clear(); butlerPreparations.clear(); setupPages.clear(); accountWorkspace.invalidate(); composerModels.clear(); aiSources.clear(); conversationModels.clear()
        if (documentReplaced) personal.clear() else personal.background()
        developerLogs.clear()
        if (documentReplaced) routeReferences.clear() else routeReferences.background()
        if (documentReplaced) { controller.webChannels.clear(); controller.webSkills.clear() } else { controller.webChannels.background(); controller.webSkills.background() }
        if (documentReplaced) {
            // A replacement document did not choose this project namespace. Normal backgrounding is not an exit.
            if (section == "memory" || section == "memoryReview") { controller.leaveOwnerDomain("memoryReview"); section = null }
            documentId = null; taskReturnSection = null
        }
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
        phoneObservationExpiry.clear()
        if (!statePublicationScheduled) return
        choreographer.removeFrameCallback(statePublication)
        statePublicationScheduled = false
    }

    private fun publishStateNow(force: Boolean = false) {
        if (closed || !started) return
        var projection: JSONObject? = null
        snapshotObserver.observeReads {
            controller.modelSelectionRevision
            projection = PocketWebState.snapshot(controller, ::canReadProjectGoal)
            projection?.getJSONObject("state")?.put("projectPreview", projectPreview?.snapshot() ?: JSONObject.NULL)
            projection?.getJSONObject("state")?.put("localSourceIntake", localSourceIntake.snapshot() ?: JSONObject.NULL)
            projection?.getJSONObject("state")?.put("projectGitImport", projectGitImport.snapshot() ?: JSONObject.NULL)
            projection?.getJSONObject("state")?.put("projectLivePreview", projectLivePreview?.snapshot() ?: JSONObject.NULL)
            projection?.getJSONObject("state")?.put("appearance", presentation.snapshot())
            projection?.getJSONObject("state")?.put("backgroundOwner", background.snapshot())
            projection?.getJSONObject("state")?.put("remoteInbox", remoteInbox.snapshot())
            projection?.getJSONObject("state")?.put("modelDiagnostics", modelDiagnostics.snapshot())
            projection?.getJSONObject("state")?.put("attachmentWorkspace", attachmentPages.snapshot())
            projection?.getJSONObject("state")?.put("taskCapabilityExport", taskCapabilities.exportSnapshot() ?: JSONObject.NULL)
            if (section == "teams" && controller.webPrivateOwnerVisible()) projection?.getJSONObject("state")?.put("teamFinalPackageExport", teamPages.finalPackageExportSnapshot() ?: JSONObject.NULL)
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
            val public = PocketWebState.publicValue(value, omitProjectContract = true)
            projectedState.getJSONObject("ownerSnapshots").put(domain, public)
            projectedState.getJSONObject("features").put(domain, public)
            if (domain == "models") projectedState.put("modelConnections", public)
            if (domain == "devices") projectedState.put("deviceWorkspace", public)
        }
        domainErrors.forEach { (domain, error) -> projectedState.getJSONObject("ownerErrors").put(domain, error) }
        snapshot.put("capabilities", JSONObject().put("commands", JSONArray(commands.toList())))
            .put("view", JSONObject().put("section", section ?: JSONObject.NULL).put("resumeRevision", workspaceResumeRevision).put("taskId", taskViewId ?: JSONObject.NULL).also { view ->
                if (section == null) taskViewId?.let { task -> controller.heldPrivatePhoneImage(task)?.let { view.put("heldPrivatePhoneImage", it.descriptor()) } }
            })
            .put("phoneDraft", phoneComposer.snapshot())
        val bounds = phoneObservationExpiry.take(snapshot)
        val serialized = snapshot.toString()
        val unchanged = !force && serialized == lastState
        if (!unchanged) {
            lastState = serialized
            emit(JSONObject().put("v", 1).put("kind", "state").put("revision", ++revision).put("snapshot", snapshot))
        }
        // Even if two original observations have the same display label, use the last native deadline.
        phoneObservationExpiry.bind(revision, if (controller.webPrivateOwnerVisible()) bounds else emptyList())
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
            retiredWorkspaceCleanup(action, args)?.let { result ->
                complete(id, result, null)
                return
            }
            if(action==PocketWebTaskPackagePreparation.PREPARE || action==PocketWebTaskPackagePreparation.CLOSE){
                val task=requiredString(args,"taskId");val ticket=captureWindow();val document=documentId
                val current={ !closed && document!=null && documentId==document && section==null && taskViewId==task && acceptsWindow(ticket) && controller.webPrivateOwnerVisible() }
                require(current()){ "请在这份原私人工作中准备源码" }
                taskPackagePreparation.dispatch(action,args,current){r,e->complete(id,r,e,retainValue=false)};return
            }
            if (action in PocketWebRemovedProjectFiles.COMMANDS){
                removedProjectFiles.dispatch(action,args){r,e->complete(id,r,e,retainValue=false)};return
            }
            if (action == "projects.historyComparison") {
                checkedPayload(args, setOf("projectId","path","version","versions"));requireFile(args)
                require(section == null && taskViewId == null && controller.webPrivateOwnerVisible()) { "请回到这个正式项目文件再比较" }
                complete(id, dev.pocketengine.ui.originalHistoryComparison(controller.state.fileHistory,requiredString(args,"projectId"),checkedPath(args),requiredInt(args,"version"),requiredString(args,"versions")),null,retainValue=false)
                return
            }
            if (action in PocketWebWorkSearch.COMMANDS) {
                val conversationResult = if (action == "tasks.resultFiles") visibleChatTaskResult(args) else null
                if (action in setOf("ownerWork.report.read", "tasks.projectPackages", TaskProjectPackageFiles.COMMAND, "tasks.resultFiles") && conversationResult == null) require(section == null && taskViewId == requiredString(args, "taskId") && controller.webPrivateOwnerVisible()) { "请在原工作详情中读取这份成果" }
                workSearch.dispatch(action, args, conversationResult) { result, error ->
                    if (conversationResult != null && runCatching { visibleChatTaskResult(args) }.getOrNull() != conversationResult)
                        complete(id, null, "原对话消息已变化，请重新打开成果")
                    else if (action == TaskProjectPackageFiles.COMMAND && (section != null || taskViewId != args.optString("taskId") || !controller.webPrivateOwnerVisible()))
                        complete(id, null, "原工作已关闭，没有带出包内文件", retainValue = false)
                    else complete(id, result, error, retainValue = action != "tasks.resultFiles" && action != TaskProjectPackageFiles.COMMAND)
                }
                return
            }
            if (action in PocketWebFormDrafts.COMMANDS) {
                check(controller.webPrivateOwnerVisible()) { "请回到私人工作区保存这份草稿" }
                val window = captureWindow()
                controller.runPluginCatalogWork(Runnable {
                    val result = runCatching {
                        check(acceptsWindow(window) && controller.webPrivateOwnerVisible()) { "原草稿窗口已关闭" }
                        formDrafts.dispatch(action, args)
                    }
                    main.post {
                        result.fold({ value -> complete(id, value, null) }, { complete(id, null, it.message ?: "草稿尚未保存，请重试") })
                    }
                })
                return
            }
            if (action == "webSearch.snapshot" || action == "webSearch.save") {
                check(controller.webPrivateOwnerVisible()) { "仅能在本机前台私人界面管理联网搜索配置" }
                val app = controller.webPresentationActivity().application as dev.pocketengine.EngineApplication
                val result = if (action == "webSearch.snapshot") {
                    checkedPayload(args, emptySet())
                    app.webSearchSettingsForOwner()
                } else {
                    checkedPayload(args, setOf("provider", "baseUrl", "apiKey", "clearApiKey", "providerHostedEnabled", "groupSearchEnabled", "expectedRevision"))
                    app.saveWebSearchSettingsForOwner(args)
                }
                complete(id, result, null); publishState(); return
            }
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
            if (action in PocketWebLocalSourceIntake.COMMANDS) {
                complete(id, localSourceIntake.dispatch(action, args), null, retainValue = false)
                publishState(); return
            }
            if (action in PocketWebProjectGitImport.COMMANDS) {
                complete(id, projectGitImport.dispatch(action, args), null, retainValue = false)
                publishState(); return
            }
            if (action in dev.pocketengine.ui.ProjectFormalDrafts.COMMANDS) {
                val ticket=captureWindow()
                fun original()=!closed && section==null && taskViewId==null && controller.webPrivateOwnerVisible() && acceptsWindow(ticket)
                check(original()) { "请在原私人项目中操作本机保留稿" }
                controller.formalFileDrafts.dispatch(action,args,::original){result,error->complete(id,result,error,retainValue=false);publishState()};return
            }
            if (action in dev.pocketengine.ui.ProjectFormalFileExport.COMMANDS) {
                require(section==null&&taskViewId==null&&controller.webPrivateOwnerVisible()) { "请返回原私人项目文件" }
                requireFile(args)
                controller.formalFileExports.dispatch(action,args){result,error->complete(id,result,error,retainValue=false);publishState()};return
            }
            if(action=="projects.development.taskPackageSource" || action=="projects.development.resolveTaskPackageSource"){
                projectDevelopment.dispatch(action,args){r,e->complete(id,r,e,retainValue=false)};return
            }
            if (action in PocketWebProjectDevelopment.COMMANDS) {
                projectDevelopment.dispatch(action, args) { result, error -> complete(id, result, error, retainValue = action !in PocketWebCheckoutFiles.COMMANDS && action !in setOf("projects.development.readHostDiff", "projects.development.read", "projects.development.reviewDraftBuild", "projects.development.review", "projects.development.start", "projects.development.query")); publishState() }; return
            }
            if (action in PocketWebButlerPreparations.COMMANDS) {
                butlerPreparations.dispatch(action, args, section == null && taskViewId == null) { result, error -> complete(id, result, error, "outcome_unknown", retainValue=action !in setOf("butler.preparation.project.savedRevisions","butler.preparation.project.followup") && !(action=="butler.preparation.project.review" && args.has("ownerRevision"))); publishState() }; return
            }
            if(action in PocketWebFirstNodeWebOutput.COMMANDS){
                firstNodeWebOutput.dispatch(action,args){r,e->complete(id,r,e,retainValue=false);publishState()};return
            }
            if (action in PocketWebProjectLivePreview.COMMANDS) {
                if (action == "projectLive.open") require(requiredString(args, "taskId") == taskViewId) { "请先打开这份原项目任务" }
                (projectLivePreview ?: error("原手机项目预览宿主尚未接入")).dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebProjectPreview.COMMANDS) {
                if (action in setOf("projects.preview", "projects.package.preview.open", "projects.preview.return") ||
                    action == "projects.preview.viewport" && args.optBoolean("visible")) projectWorkPages.clear()
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
            if (action in PocketWebPhoneControl.COMMANDS) {
                if (action == "phoneControl.prepare") requireSection("devices")
                if (action == "phoneControl.open") {
                    val selected = requireTask(requiredString(args, "taskId"))
                    require(selected.id == taskViewId && selected.raw.optString("actorId") == "owner" && selected.raw.optString("spaceId") == "private") { "请先打开原私人手控任务" }
                }
                phoneControl.dispatch(action, args) { result, error ->
                    if (action == "phoneControl.prepare" && error == null && result?.optBoolean("prepared") == true)
                        openOriginalTask(id, result.getString("taskId"), true, "devices")
                    else { complete(id, result, error, retainValue = false); publishState() }
                }; return
            }
            if (action in PocketWebDeviceTaskScope.COMMANDS) {
                if (action == "deviceTaskScope.prepare") requireSection("devices")
                if (action == "deviceTaskScope.open") {
                    val selected = requireTask(requiredString(args, "taskId"))
                    require(selected.id == taskViewId && selected.raw.optString("actorId") == "owner" && selected.raw.optString("spaceId") == "private") { "请先打开这份原私人任务" }
                }
                val input = when {
                    action != "deviceTaskScope.prepare" -> args
                    args.has("directPhone") && args.has("directWorkspace") -> error("不能为同一目标同时指定两类设备")
                    args.has("directPhone") -> JSONObject(args.toString()).put("directPhone", devicePages.originalPhoneForPreparation(args.getJSONObject("directPhone")))
                    args.has("directWorkspace") -> JSONObject(args.toString()).put("directWorkspace", devicePages.originalWorkspaceForPreparation(args.getJSONObject("directWorkspace")))
                    else -> args
                }
                deviceTaskScope.dispatch(action, input) { result, error ->
                    if (action == "deviceTaskScope.prepare" && error == null && result?.optBoolean("prepared") == true)
                        openOriginalTask(id, result.getString("taskId"), true, "devices")
                    else { complete(id, result, error); publishState() }
                }; return
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
                remoteTasks.dispatch(action, args) { result, error -> complete(id, result, error, retainValue = action != "remoteTask.clipboard" && action != "remoteTask.originalLinuxHostStatus" && action !in PocketWebRemotePickup.COMMANDS && action !in PocketWebRemotePhoneDrafts.COMMANDS); publishState() }; return
            }
            if (action in PocketWebTeamEvidence.COMMANDS) {
                teamEvidence.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebAttachments.COMMANDS) {
                val conversationResult = if (action == "attachmentWorkspace.open") visibleChatTaskResult(args) else null
                if (action == "attachmentWorkspace.open" && args.optBoolean("openOriginal") && conversationResult == null) {
                    require(section == null && taskViewId == requiredString(args, "taskId")) { "请从原工作打开这份成果" }
                }
                val attachmentArgs = if (conversationResult == null) args else JSONObject(args.toString()).apply {
                    remove("resultMessageId"); remove("conversationId")
                }
                attachmentPages.dispatch(action, attachmentArgs, conversationResult) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action == "conversation.projectConvention.read") {
                require(args.length()==1 && args.opt("messageId") is String)
                val message=requiredString(args,"messageId");val origin=captureWindow()
                fun visible()=section==null && taskViewId==null && !closed && acceptsWindow(origin) && controller.webPrivateOwnerVisible()
                check(visible()){"请返回原私人对话查看项目交办依据"}
                controller.requestProvidedProjectConvention(message,::visible){value,error->complete(id,value,error,retainValue=false)}
                return
            }
            if (action == "conversation.projectConvention.stageFeedback") {
                checkedPayload(args,setOf("messageId","key","epoch","revision","sourceRevision","answerAt","feedback"));val origin=captureWindow()
                fun visible()=section==null && taskViewId==null && !closed && !phoneComposer.intent && acceptsWindow(origin) && controller.webPrivateOwnerVisible()
                check(visible()) { "请回到原私人对话的普通输入" }
                fun integer(name:String):Long{val v=args.opt(name);require((v is Int||v is Long)&& (v as Number).toLong()>=0);return (v as Number).toLong()}
                controller.stageProjectAnswerAdjustment(requiredString(args,"messageId"),requiredString(args,"key"),requiredInt(args,"epoch"),
                    integer("revision"),integer("sourceRevision"),integer("answerAt"),requiredString(args,"feedback"),::visible){value,error->
                    complete(id,value,error,retainValue=false,replayHint="请核对原对话草稿；没有重新写入或发送");publishState()
                }
                return
            }
            if (action in PocketWebMemoryHistory.COMMANDS) {
                if (action == "memoryHistory.open") {
                    if (args.optString("view") == "answer") require(section == null && taskViewId == null && controller.webPrivateOwnerVisible()) { "请在原私聊回答下核对本次提供的记忆" }
                    else require(section == "memory" && taskViewId == null) { "请从原记忆管理页选择要查阅的范围" }
                }
                memoryHistory.dispatch(action, args) { result, error -> completePrivateMemoryReply(id, action, result, error); publishState() }; return
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
                require(if (taskId == null) selectedSection in setOf("tasks", "attention", "taskUpdates") else selectedSection in setOf("events", "operations")) { "原历史读取范围无效" }
                if (selectedSection in setOf("attention", "taskUpdates")) check(controller.webPrivateOwnerVisible()) { "请从本机主人界面读取待处理任务" }
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
                checkedPayload(args, setOf("id", "requestNavigation", "readerWindow", "readerConversationId", "readerMessageId", "searchQuery", "expectedRunId", "expectedEpoch", "expectedProjectId", "expectedCheckoutId", "expectedExternalKind"))
                val taskId = requiredString(args, "id")
                val fromReader = args.has("readerWindow") || args.has("readerConversationId") || args.has("readerMessageId")
                require(args.has("expectedRunId") == args.has("expectedEpoch")) { "原整理记录身份不完整" }
                val expectedRunId = if (args.has("expectedRunId")) requiredString(args, "expectedRunId") else null
                if (args.has("expectedExternalKind")) {
                    val kind = requiredString(args, "expectedExternalKind")
                    require(kind in setOf("remote", "teams", "ssh") && expectedRunId == taskId && requiredInt(args, "expectedEpoch") == 0 &&
                        !fromReader && !args.has("searchQuery") && !args.has("expectedProjectId") && !args.has("expectedCheckoutId") &&
                        args.has("requestNavigation") && !requiredBoolean(args, "requestNavigation") && controller.webPrivateOwnerVisible()) { "原本机工作身份无效" }
                    openOriginalTask(id, taskId, false, expectedRunId = expectedRunId, expectedEpoch = 0,
                        sourceWindowCurrent = { controller.webPrivateOwnerVisible() },
                        checkReaderInput = { task ->
                            val raw = task.raw
                            val refs = raw.optJSONObject("externalRefs")
                            require(raw.optString("id") == taskId && raw.optString("operationId") == taskId &&
                                raw.optString("actorId") == "owner" && raw.optString("spaceId") == "private" &&
                                raw.isNull("origin") && raw.isNull("parentTaskId") && raw.optInt("coordinatorEpoch", -1) == 0 &&
                                raw.optString("kind") == kind && raw.optString("feature") == kind &&
                                raw.optBoolean("contextAvailable", true) && !raw.optBoolean("metadataLimited") &&
                                !raw.optBoolean("sourceCurrentReadRequired") && raw.optString("taskProjection") != "source_unavailable" &&
                                (kind != "remote" || raw.optString("command") == "submitJob" && !refs?.optString("profileId").isNullOrBlank()) &&
                                (kind != "ssh" || refs?.optJSONObject("sshNativeRequest")?.optString("format") == "pocketworld.ssh.native-request.v1")) {
                                "原远端或协作记录当前不可核对，没有打开其他运行。"
                            }
                        })
                    return
                }
                val expectedEpoch = if (expectedRunId != null) requiredInt(args, "expectedEpoch").also { require(it > 0) { "原整理回合无效" } } else null
                val projectScope = ProjectTaskReadScope.read(args)
                if (args.has("searchQuery") || expectedRunId != null || projectScope != null) {
                    if (projectScope != null) require(projectReadCurrent(projectScope)) { "请返回原项目工作页后再打开" }
                    require(!fromReader && controller.webPrivateOwnerVisible()) { "请回到私人工作区打开原工作" }
                    val requestNavigation = if (args.has("requestNavigation")) requiredBoolean(args, "requestNavigation") else true
                    val lookup = JSONObject().put("taskId", taskId).put("query", if (args.has("searchQuery")) requiredString(args, "searchQuery", true) else "")
                    projectScope?.addTo(lookup)
                    if (expectedRunId != null) lookup.put("expectedRunId", expectedRunId).put("expectedEpoch", expectedEpoch)
                    workSearch.dispatch("work.search.task", lookup) { result, error ->
                        if (error != null || result == null) complete(id, null, error ?: "原工作暂不可核对")
                        else try {
                            require(taskHistory.contains(taskId) && result.optString("taskId") == taskId) { "原工作读取身份已变化" }
                            openOriginalTask(id, taskId, requestNavigation, keepConversationReader = expectedRunId != null && !requestNavigation, expectedRunId = expectedRunId, expectedEpoch = expectedEpoch, projectScope = projectScope)
                        } catch (failure: Exception) { complete(id, null, failure.message ?: "原工作暂不可打开") }
                    }
                    return
                }
                if (fromReader) {
                    val reader = controller.conversationReader ?: error("原历史阅读窗口已关闭")
                    require(section == null && (taskViewId == null || taskViewId == taskId) && controller.canReadConversationWindow(reader) &&
                        reader.window == requiredLong(args, "readerWindow") && reader.conversation == requiredString(args, "readerConversationId")) { "原历史阅读窗口已变化，请重新打开" }
                    val row = reader.messages.firstOrNull { it.id == requiredString(args, "readerMessageId") }
                        ?: error("原消息已不在这份阅读窗口")
                    if (row.role == "user") {
                        val input = row.readerInputWork ?: error("这条原输入没有可核对的工作回执")
                        require(taskId == input.taskId && args.opt("requestNavigation") == false) { "工作不属于这条原输入" }
                        openOriginalTask(id, taskId, false, keepConversationReader = true, checkReaderInput = { task ->
                            val current = controller.conversationReader
                            require(controller.canReadConversationWindow(reader) && current?.messages?.any {
                                it.id == row.id && it.sourceRevision == row.sourceRevision && it.readerInputWork == input
                            } == true) { "原消息已变化，请返回历史重新查看" }
                            require(!isGroup(task) && task.id == input.taskId && task.raw.opt("id") == input.taskId &&
                                task.raw.opt("conversationId") == reader.conversation &&
                                task.raw.opt("actorId") == "owner" && task.raw.opt("spaceId") == "private") { "未找到属于这条输入的私人工作" }
                            require(task.raw.opt("runId") == input.runId && task.raw.optInt("coordinatorEpoch") == input.epoch) {
                                "这项工作已换到另一轮，未打开其他执行记录"
                            }
                        })
                        return
                    }
                    if (row.hostGoalTaskId != null) {
                        require(taskId==row.hostGoalTaskId && args.opt("requestNavigation")==false)
                        val current={ controller.canReadConversationWindow(reader) && controller.conversationReader?.messages?.any {
                            it.id==row.id && it.sourceRevision==row.sourceRevision && it.hostGoalTaskId==taskId
                        }==true }
                        openOriginalTask(id,taskId,false,keepConversationReader=true,sourceWindowCurrent=current,
                            checkReaderInput={ check(current()); dev.pocketengine.ui.PrivateHostReceipt.verify(it,reader.conversation,taskId) })
                        return
                    }
                    require(row.role == "assistant" && (row.sourceRevision > 0 || row.phoneResult != null || row.taskResult != null) &&
                        (row.taskId == taskId || row.phoneResult?.taskId == taskId || row.taskResult?.taskId == taskId)) { "任务不属于这条原回复" }
                }
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
                require(fromReader || controller.state.tasks.any { it.id == taskId } || taskHistory.contains(taskId) || channelUsageVisible || phoneResultVisible || taskResultVisible || previewTaskVisible) { "请先重新读取这个原任务的历史窗口" }
                val requestNavigation = if (args.has("requestNavigation")) requiredBoolean(args, "requestNavigation") else true
                openOriginalTask(id, taskId, requestNavigation, keepConversationReader = fromReader && !requestNavigation)
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
            if (action == "usage.trends.close") { checkedPayload(args, emptySet()); inference.clearTrends(); complete(id, JSONObject().put("closed", true), null); return }
            if (action == "usage.trends") {
                checkedPayload(args, setOf("fromInclusive", "toExclusive", "zoneId"))
                val window = captureWindow()
                inference.trends(requiredLong(args, "fromInclusive"), requiredLong(args, "toExclusive"), requiredString(args, "zoneId"),
                    { !closed && acceptsWindow(window) && controller.webPrivateOwnerVisible() }) { result, error -> complete(id, result, error) }
                return
            }
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
            if (action == "conversation.directory") {
                checkedPayload(args, setOf("cursor"))
                require(section == null && taskViewId == null && controller.webPrivateOwnerVisible()) { "请回到私人工作台查找对话" }
                controller.readConversationDirectory(requiredString(args, "cursor", true)) { result, error -> complete(id, result, error) }
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
            if (action.startsWith("ownerWork.supplement.")) {
                val identityFields=setOf("taskId","runId","epoch","conversationId")
                val operation=action.removePrefix("ownerWork.supplement.")
                val fields=when(operation){
                    "read"->identityFields
                    "edit"->identityFields+setOf("key","revision","text","mode")
                    "send"->identityFields+setOf("key","revision","clientMessageId")
                    "query"->identityFields+setOf("key","clientMessageId")
                    else->error("工作补充动作不可用")
                }
                checkedPayload(args,fields)
                val expected=JSONObject().put("taskId",requiredString(args,"taskId")).put("runId",requiredString(args,"runId"))
                    .put("epoch",requiredInt(args,"epoch")).put("conversationId",requiredString(args,"conversationId"))
                require(section==null&&taskViewId==expected.getString("taskId")&&controller.webPrivateOwnerVisible()) { "请在原工作详情中核对这份补充" }
                val done:(JSONObject?,String?)->Unit={result,error->complete(id,result,error,if(operation=="send")"outcome_unknown" else "operation_failed");publishState()}
                when(operation){
                    "read"->controller.readOwnerWorkSupplement(expected,done)
                    "edit"->controller.editOwnerWorkSupplement(expected,requiredString(args,"key"),requiredLong(args,"revision"),requiredString(args,"text",true),requiredString(args,"mode"),done)
                    "send"->controller.sendOwnerWorkSupplement(expected,requiredString(args,"key"),requiredLong(args,"revision"),requiredString(args,"clientMessageId"),done)
                    "query"->controller.queryOwnerWorkSupplement(expected,requiredString(args,"key"),requiredString(args,"clientMessageId"),done)
                }
                return
            }
            if (action == "ownerWork.control") {
                try {
                    checkedPayload(args, setOf("taskId", "runId", "epoch", "action"))
                    require(controller.webPrivateOwnerVisible() && section == null && (taskViewId == null || taskViewId == requiredString(args, "taskId"))) { "原任务控制窗口已关闭" }
                    controller.controlOwnerWork(args, requiredString(args, "action")) { result ->
                        result.onSuccess { complete(id, it, null) }.onFailure { failure ->
                            if (failure is OwnerWorkControlRejected) {
                                completeOwnerWorkControlRejection(id, failure.message)
                            } else {
                                complete(id, null, "这次操作的回执尚未确认，请核对原任务。", "outcome_unknown")
                            }
                        }
                        publishState()
                    }
                } catch (reason: Exception) {
                    // The controller has not queued the native control when it throws here.
                    completeOwnerWorkControlRejection(id, reason.message)
                    publishState()
                }
                return
            }
            if (action == "phone.request.original") {
                checkedPayload(args, setOf("taskId","runId","epoch","offset","reviewDigest"))
                val task=requiredString(args,"taskId");val window=captureWindow();val document=documentId
                val current={ !closed && section==null && taskViewId==task && document!=null && document==documentId && acceptsWindow(window) }
                require(current() && controller.webPrivateOwnerVisible()) { "请在原手机工作中核对原交代" }
                controller.readPhoneTaskRequest(task,requiredString(args,"runId",true),requiredInt(args,"epoch"),requiredInt(args,"offset"),requiredString(args,"reviewDigest",true),current) {
                    value,error -> complete(id,value,error,retainValue=false)
                };return
            }
            if (action == "phone.result.original") {
                checkedPayload(args, setOf("conversationId", "messageId", "sourceRevision", "taskId", "runId", "epoch", "deliveryId", "offset", "expectedDigest", "readerWindow"))
                val ticket = captureWindow()
                val document = documentId
                val current = { !closed && document != null && document == documentId && acceptsWindow(ticket) && section == null && taskViewId == null }
                require(current() && controller.webPrivateOwnerVisible()) { "请回到这条手机回执的原私人对话" }
                controller.readPhoneOriginal(requiredString(args, "conversationId"), requiredString(args, "messageId"), requiredLong(args, "sourceRevision"),
                    requiredString(args, "taskId"), requiredString(args, "runId"), requiredInt(args, "epoch"), requiredString(args, "deliveryId"),
                    requiredInt(args, "offset"), requiredString(args, "expectedDigest", true), requiredLong(args, "readerWindow"), current) { result, error -> complete(id, result, error, retainValue = false) }
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
                    is PocketWebTaskActions.Decision.PhoneOutcome -> {
                        val window=captureWindow();val doc=documentId;val privateRevision=controller.conversationSourceRevision
                        val current={ !closed && doc!=null && doc==documentId && acceptsWindow(window) && taskViewId==decision.task.id && section==null && controller.conversationSourceRevision==privateRevision }
                        controller.recordReviewedPhoneOutcome(decision.task,decision.operation,decision.outcome,decision.pin,current) { receipt,error ->
                            complete(id,receipt,error,"outcome_unknown",retainValue=false);publishState()
                        }
                    }
                }
                return
            }
            if(action=="tasks.readOriginalTeamFileDiff"){
                checkedPayload(args,setOf("taskId","runId","epoch","approvalId","proof","offset","diffHash"))
                val task=requiredString(args,"taskId");val window=captureWindow();val generation=taskReviewGeneration
                fun original()= !closed && acceptsWindow(window) && taskViewId==task && section==null &&
                    controller.webPrivateOwnerVisible() && controller.webUiCanInteract() && generation==taskReviewGeneration
                require(original()){ "请先打开原私人任务的这笔审批" }
                controller.readWebOriginalTeamFileDiff(JSONObject(args.toString()),::original){result,error->
                    if(!original())complete(id,null,"原任务审批阅读已关闭",retainValue=false)
                    else complete(id,result,error,retainValue=false)
                }
                return
            }
            if (action == "tasks.review") {
                checkedPayload(args, setOf("id", "approvalId"))
                val taskId = requiredString(args, "id")
                val approvalId = if (args.has("approvalId")) requiredString(args, "approvalId").also { require(it.length <= 128) } else null
                require(taskViewId == taskId && controller.webUiCanInteract()) { "请重新打开这个原任务的审阅窗口" }
                requireTask(taskId)
                val generation = taskReviewGeneration
                val document = documentId
                val navigation = Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
                taskActions.read(taskId, approvalId, { closed }, {
                    taskViewId == taskId && controller.webUiCanInteract() && generation == taskReviewGeneration && document == documentId &&
                        navigation == Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
                }) { result, error ->
                    complete(id, result, error, retainValue = result?.has("capabilityApprovalRead") != true)
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
            if (action == "composer.oneLearning.save") {
                require(section == null && taskViewId == null && controller.webPrivateOwnerVisible()) { "请回到原私人对话" }
                checkedPayload(args, setOf("key", "epoch", "revision", "excluded", "protectDefaults"))
                val protect = if (args.has("protectDefaults")) requiredBoolean(args, "protectDefaults") else null
                val epoch = args.opt("epoch"); val revision = args.opt("revision"); val excluded = args.opt("excluded")
                require(epoch is Int && epoch >= 0 && (revision is Int || revision is Long) && (revision as Number).toLong() > 0 && excluded is Boolean)
                controller.setOneMessageLearning(requiredString(args, "key"), epoch, (revision as Number).toLong(), excluded, protect) { value, error -> complete(id, value, error); publishState() }
                return
            }
            if (action == "composer.firstMemory.save") {
                require(section == null && taskViewId == null && controller.webPrivateOwnerVisible()) { "请回到新的私人对话" }
                checkedPayload(args, setOf("key", "epoch", "revision", "paused"))
                val epoch = args.opt("epoch"); val revision = args.opt("revision"); val paused = args.opt("paused")
                require(epoch is Int && epoch >= 0 && (revision is Int || revision is Long) && (revision as Number).toLong() > 0 && paused is Boolean)
                controller.setFirstMessageMemory(requiredString(args, "key"), epoch, (revision as Number).toLong(), paused) { value, error -> complete(id, value, error); publishState() }
                return
            }
            if (action in PocketWebPersonal.COMMANDS) {
                if ((action == "profile.conversation.read" || action == "profile.conversation.save") && section == null) {
                    // The primary composer exposes only its current conversation's memory switch, not memory bodies.
                    require(taskViewId == null && controller.webPrivateOwnerVisible()) { "请回到原私人对话设置记忆" }
                    val conversation = requiredString(args, "conversationId")
                    require(conversation == controller.state.conversationId) { "请返回原私人对话设置记忆" }
                } else if (action != "profile.identity") requireSection("profile")
                // Only this original, still-visible owner editor receives the full immediate save result.
                val savedWindow = if (action == "profile.save") captureWindow() else null
                personal.dispatch(action, args) { result, error ->
                    if (savedWindow == null) complete(id, result, error, retainValue = false)
                    else completeProfileSave(id, args.opt("expectedRevision"), savedWindow, result, error)
                    publishState()
                }; return
            }
            if (action in PocketWebKnowledge.COMMANDS) {
                if (action == "knowledge.close") check(section in setOf("memory", "memoryReview", "learning")) else if (action.startsWith("knowledge.")) check(section in setOf("memory", "memoryReview")) { "请先打开记忆管理页" } else requireSection("learning")
                knowledge.dispatch(action, args) { result, error ->
                    if (action.startsWith("knowledge.")) completePrivateMemoryReply(id, action, result, error)
                    else complete(id, result, error) // Separate learning-job commands keep their original receipt contract.
                    publishState()
                }; return
            }
            if (action in PocketWebMemoryDrafts.COMMANDS) {
                // Review/open/query carry live private text; even a mutation reply can contain the old record.
                // The original operation journal, not the Web request-ID cache, owns that outcome.
                val savedQuote = action == "memoryDraft.query" && args.opt("view") == "saved_quote"
                val originalTask = if (savedQuote || action == "memoryDraft.query" && args.opt("view") == "saved_record") requiredString(args, "taskId") else null
                val originalWindow = if (originalTask == null) null else captureWindow()
                fun originalAnswerVisible() = originalTask != null && !closed && documentId != null && section == null &&
                    (if (taskViewId != null) !savedQuote && taskViewId == originalTask else controller.state.messages.any { it.role == "assistant" && it.taskId == originalTask })
                if (originalWindow != null) check(originalAnswerVisible()) { "请回到原私人回答查看这条记忆" }
                memoryDrafts.dispatch(action, args, section == null && taskViewId == null) { result, error ->
                    if (originalWindow != null && (!acceptsWindow(originalWindow) || !originalAnswerVisible()))
                        completePrivateMemoryReply(id, action, null, "原回答窗口已变化，没有交付记忆正文")
                    else completePrivateMemoryReply(id, action, result, error)
                    publishState()
                }; return
            }
            if (action in PocketWebSkillDrafts.COMMANDS) { skillDrafts.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return }
            if (action in PocketWebLocal.COMMANDS) {
                if (action.startsWith("local.execution.")) {
                    if (action != "local.execution.close") require(taskViewId != null && controller.webPrivateOwnerVisible()) { "请先打开原私人项目任务" }
                    if (action == "local.execution.observe" || action == "local.execution.open") require(requiredString(args, "taskId") == taskViewId) { "原项目任务已切换" }
                } else requireSection("local")
                localPages.dispatch(action, args) { result, error -> complete(id, result, error, retainValue = action !in PocketWebLocal.PROJECT_OBSERVATION_COMMANDS); publishState() }; return
            }
            if (action == "groups.openTask") {
                requireSection("groups")
                openOriginalTask(id, groupPages.taskForNavigation(args), true, "groups")
                return
            }
            if (action.startsWith("tasks.teamRemainder.")) {
                val task=requiredString(args,"taskId")
                val ticket=captureWindow();val document=documentId
                val current={ !closed && document!=null && document==documentId && acceptsWindow(ticket) && section==null && taskViewId==task && controller.webPrivateOwnerVisible() }
                require(current()) { "请在原私人整合任务核对剩余文件" }
                val teams=controller.webPluginFeatureHost().module("teams") as? dev.pocketengine.features.teams.TeamsModule ?: error("原文件团队宿主不可用")
                val reply:(JSONObject?,String?)->Unit={ value,message -> complete(id,value,message,retainValue=false) }
                when(action){
                    "tasks.teamRemainder.read" -> {
                        checkedPayload(args,setOf("taskId","runId","epoch"));val run=requiredString(args,"runId");val epoch=requiredInt(args,"epoch")
                        fileRemainder.read(task,current,{ teams.reviewOwnerRemaining(task,run,epoch) { current() } },reply)
                    }
                    "tasks.teamRemainder.close" -> {
                        checkedPayload(args,setOf("taskId","reviewHandle"));fileRemainder.dismiss(task,requiredString(args,"reviewHandle"))
                        reply(JSONObject().put("closed",true),null)
                    }
                    "tasks.teamRemainder.continue" -> {
                        checkedPayload(args,setOf("taskId","reviewHandle","confirmed"));require(requiredBoolean(args,"confirmed"))
                        fileRemainder.submit(task,requiredString(args,"reviewHandle"),current,{ review,started,ended -> teams.continueOwnerRemaining(review,{ started(it) },{ ended(it) }) },reply)
                    }
                    else -> error("无此原文件操作")
                }
                return
            }
            if (action == PocketWebProjectReviewReturn.DIRECTORY || action == PocketWebProjectReviewReturn.ORIGINAL) {
                val parent=requiredString(args,"taskId");val window=captureWindow()
                fun current() = !closed && acceptsWindow(window) && taskViewId==parent && controller.webPrivateOwnerVisible()
                check(current()) { "请先打开这次原项目任务" }
                controller.runTeamPhoneWork {
                    val result=runCatching {
                        check(current());val read=dev.pocketengine.core.RunControl()
                        try{PocketWebProjectReviewReturn.read(action,args,controller.nativeTokenBudgetHost().tasks,read){
                            controller.webPluginFeatureHost().module("teams") as? dev.pocketengine.features.teams.TeamsModule ?: error("原团队宿主尚未接入")
                        }}finally{read.cancel()}
                    }
                    main.post {
                        if (!current()) complete(id,null,"原项目任务页面已关闭，没有改到别的原件")
                        else result.fold({ complete(id,it,null,retainValue=false) },{ complete(id,null,it.message?:"原项目协作审阅暂不可读") })
                    }
                }
                return
            }
            if (action == "tasks.recoverTeamFile") {
                checkedPayload(args,setOf("taskId","runId","epoch","operationId"))
                val task=requiredString(args,"taskId");val run=requiredString(args,"runId");val epoch=requiredInt(args,"epoch");val operation=requiredString(args,"operationId")
                val ticket=captureWindow();val document=documentId
                val current={ !closed && document!=null && document==documentId && acceptsWindow(ticket) && section==null && taskViewId==task && controller.webPrivateOwnerVisible() }
                require(current()) { "请回到原私人整合任务核对文件" }
                controller.runTeamPhoneWork {
                    val result=runCatching {
                        check(current()) { "原任务窗口已关闭" }
                        val teams=controller.webPluginFeatureHost().module("teams") as? dev.pocketengine.features.teams.TeamsModule ?: error("原文件团队宿主不可用")
                        teams.recoverOwnerOriginalFile(task,run,epoch,operation) { current() }
                    }
                    main.post {
                        if(!current())complete(id,null,"原窗口已关闭，请回原任务核对；不会重新写入文件",retainValue=false)
                        else result.fold({ complete(id,it,null,retainValue=false);publishState() },{ complete(id,null,it.message?:"原文件回执未能核实",retainValue=false) })
                    }
                };return
            }
            if(action=="teams.openWorkFileDecision"){
                checkedPayload(args,setOf("parentTaskId","parentRunId","parentEpoch","teamRunId","conversationId","pendingFileOperation"))
                val conversation=requiredString(args,"conversationId");val window=captureWindow()
                fun current()= !closed && acceptsWindow(window) && section==null && taskViewId==null &&
                    controller.webPrivateOwnerVisible() && controller.state.conversationId==conversation
                require(current()) { "请回到发起原工作的私人对话" }
                controller.readWebOriginalFileDecision(JSONObject(args.toString())) { mark,error ->
                    if(!current()||error!=null||mark==null)complete(id,null,error?:"原对话或文件待办已变化")
                    else openOriginalTask(id,mark.getString("taskId"),false,returnSection=null,expectedRunId=mark.getString("runId"),expectedEpoch=mark.getInt("epoch"),originalFileApproval=mark.getString("approvalId"),
                        sourceWindowCurrent=::current,checkReaderInput={ task ->
                            require(dev.pocketengine.store.TeamOriginalFileDecision.stillPending(task.raw,mark,System.currentTimeMillis())) { "这笔文件决定已处理、过期或暂不可完整读取，没有打开别的请求" }
                        })
                }
                return
            }
            if (action == "teams.openDeliveryWorker") {
                requireSection("teams")
                val handle=requiredString(args,"viewHandle");val file=args.optString("fileHandle"); val source=teamPages.taskForDeliveryNavigation(args)
                openOriginalTask(id,source.getString("taskId"),true,"teams",expectedRunId=source.getString("runId"),expectedEpoch=source.getInt("epoch"),
                    sourceWindowCurrent={ section=="teams" && taskViewId==null && controller.webPrivateOwnerVisible() && teamPages.retainsDeliverySource(handle,file) },
                    checkReaderInput={ task -> require(dev.pocketengine.features.teams.TeamDeliveryWorkers.sameAttempt(source,task.raw)) { "原执行或冻结计划已改变，没有打开替代任务" } })
                return
            }
            if (action == "teams.openResearchTask") {
                requireSection("teams")
                val destination = teamPages.taskForResearchNavigation(args)
                val output = destination.output
                if (output == null) openOriginalTask(id, destination.id, true, "teams")
                else {
                    val handle = requiredString(args, "viewHandle")
                    openOriginalTask(id, destination.id, true, "teams", expectedRunId = output.childRun, expectedEpoch = output.epoch,
                        sourceWindowCurrent = { section == "teams" && taskViewId == null && controller.webPrivateOwnerVisible() },
                        checkReaderInput = { task ->
                            require(teamPages.retainsRunReading(handle) && task.id == output.taskId && task.status == "completed" && task.raw.optString("actorId") == "owner" &&
                                task.raw.optString("spaceId") == "private" && task.raw.opt("contextAvailable") != false &&
                                output.matches(task.raw.optJSONObject("teamResearchOutputReference"))) { "当前原任务无法核对这份产出，没有打开其他执行" }
                        })
                }
                return
            }
            if (action == "devices.returnToReceivedPhoneGoal") {
                requireSection("devices")
                val originalId = devicePages.receivedTaskForReturn(args)
                val current = { section == "devices" && runCatching { devicePages.receivedTaskForReturn(args) == originalId }.getOrDefault(false) }
                controller.readReceivedPhoneTaskForReturn(originalId, current, { raw -> devicePages.checkReceivedTaskForReturn(args, raw); Unit }) { task, failure ->
                    if (failure != null || task == null) complete(id, null, failure ?: "原接纳工作不可读取")
                    else try {
                        val receipt = devicePages.checkReceivedTaskForReturn(args, task.raw)
                        complete(id, JSONObject().put("value",receipt).put("snapshot",JSONObject.NULL), null)
                    } catch (changed: Exception) { complete(id, null, changed.message ?: "原接纳身份已变化") }
                    publishState()
                }
                return
            }
            if (action == "devices.openCheckedLocalFile") {
                requireSection("devices")
                devicePages.dispatch(action,args) { result,error ->
                    if(error!=null){complete(id,null,error,retainValue=false);return@dispatch}
                    try{
                        val handle=args.getString("viewHandle")
                        val value=requireNotNull(result).getJSONObject("value")
                        require(value.getString("kind")=="internalFileHandoff")
                        val handoff=devicePages.checkedFileForNavigation(handle,value.getString("handoff"))
                        val file=handoff.selected
                        val sourceCurrent={runCatching { devicePages.checkedFileForNavigation(handle,handoff.key)===handoff }.getOrDefault(false)}
                        openOriginalTask(id,file.task,true,"devices",sourceWindowCurrent=sourceCurrent,checkedDeviceFile=file,checkReaderInput={ task ->
                            require(sourceCurrent() && task.id==file.task && task.raw.opt("actorId")=="owner" && task.raw.opt("spaceId")=="private" && !task.raw.has("origin"))
                                { "原设备窗口或私人工作已变化，未打开文件" }
                        })
                    }catch(failure:Exception){complete(id,null,failure.message?:"原文件窗口已变化",retainValue=false)}
                };return
            }
            if (action == "devices.openTask") {
                requireSection("devices")
                val taskId = devicePages.taskForNavigation(args)
                if (args.has("archiveHandle") || args.has("sourceViewHandle")) {
                    val sourceCurrent = { runCatching { devicePages.taskForNavigation(args) == taskId }.getOrDefault(false) }
                    openOriginalTask(id, taskId, true, "devices", sourceWindowCurrent = sourceCurrent, checkReaderInput = { source ->
                        require(sourceCurrent() && source.id == taskId && source.raw.optString("actorId") == "owner" && source.raw.optString("spaceId") == "private" && !source.raw.has("origin")) { "原工作来源已变化，没有打开其他任务" }
                    })
                } else openOriginalTask(id, taskId, true, "devices")
                return
            }
            if (action in PocketWebDevices.COMMANDS) {
                requireSection("devices")
                devicePages.dispatch(action, args) { result, error ->
                    if (!action.startsWith("devices.desktop.")) {
                        result?.optJSONObject("snapshot")?.let { domainSnapshots["devices"] = JSONObject(it.toString()) }
                        if (error == null) domainErrors.remove("devices") else domainErrors["devices"] = error
                    }
                    // Only the first delivery belongs to the owner's original text/read window.
                    // The cache still keeps the exact command fingerprint and a non-replay receipt.
                    val value=result?.optJSONObject("value")
                    val privateText=when(action){
                        "devices.queryPhoneGoalResult" -> !value?.optJSONObject("result")?.optString("summary").isNullOrEmpty()
                        "devices.readLocalReviewedPhoneGoal" -> value?.has("text")==true
                        "devices.reviewResult" -> value?.optJSONObject("view")?.let { it.has("summary") || it.has("evidence") }==true
                        "devices.readPendingGoalInput" -> value?.optJSONObject("input")!=null
                        "devices.reviewGoal" -> value?.optJSONObject("view")?.has("goal")==true
                        "devices.readReviewedPhoneInbox","devices.readReceivedPhoneGoals","devices.readPhoneGoalArchive","devices.readWaitingPhoneGoals","devices.findWaitingPhoneGoals" ->
                            (value?.optJSONArray("rows")?.length()?:0)>0 || !value?.optString("searchText").isNullOrEmpty()
                        else -> false
                    }
                    val onceInvite=action=="devices.createInvitation" && error==null
                    complete(id,result,error,"outcome_unknown",retainValue=!privateText && !onceInvite && action!="devices.checkLocalExportedFile" && action!="devices.readSavedPeerScope" && action!="devices.readOwnerModelRules",
                        replayHint=if(onceInvite)"这次邀请已显示；不会从回执重新显示令牌、二维码或生成另一份邀请。"
                            else if(action=="devices.readOwnerModelRules")"本机规则的查看窗口已结束，请重新打开。没有执行任何更改。"
                            else if(action=="devices.readSavedPeerScope")"原授权显示窗口已结束；重新读取只核对本机保存的单据，不联系伙伴。"
                            else if(privateText)"原文字读取或审阅已结束，请回到原工作重新核对；没有重放正文或执行操作。" else null)
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
                aiGroups.dispatch(action, args) { result, error, code ->
                    complete(id, result, error, code)
                    if (action !in localModelPresentationCommands) publishState()
                }; return
            }
            if (action in PocketWebPermissionPolicy.COMMANDS) {
                check(section == null && taskViewId == null) { "请回到私人对话设置执行权限" }
                complete(id, PocketWebPermissionPolicy.dispatch(controller, action, args), null); publishState(); return
            }
            if (action in PocketWebConversationModels.COMMANDS) {
                conversationModels.dispatch(action, args) { result, error, code ->
                    complete(id, result, error, code)
                    if (action !in localModelPresentationCommands) publishState()
                }; return
            }
            if (action in PocketWebComposerModels.COMMANDS) {
                if (action.startsWith("models.conversation.")) check(section == null && taskViewId == null) { "请回到原私人对话选择模型" }

                composerModels.dispatch(action, args) { result, error, code ->
                    complete(id, result, error, code)
                    if (action !in localModelPresentationCommands) publishState()
                }; return
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
            if(action in PocketWebProjectDraftDifference.COMMANDS){
                projectDraftDifference.dispatch(action,args){result,error->complete(id,result,error,retainValue=false)};return
            }
            if (action in PocketWebProjectWork.COMMANDS) {
                if (action == "projectWork.open") {
                    checkedPayload(args, setOf("projectId"))
                    require(controller.webPrivateOwnerVisible() && taskViewId == null && controller.state.filePath == null &&
                        requiredString(args, "projectId") == controller.state.projectId) { "请先打开这份原私人项目" }
                    // Project navigation may return from preparation while a secondary section is still retained.
                    if (section != null) leaveConnection()
                }
                projectWorkPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }; return
            }
            if (action in PocketWebPlans.COMMANDS) {
                requireSection("commitments"); planPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }; return
            }
            if (action in PocketWebCommitments.COMMANDS) {
                if (action == "commitments.snapshot" && section == "profile") check(controller.webPrivateOwnerVisible()) else requireSection("commitments")
                commitmentPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }; return
            }
            if (action in PocketWebMedia.COMMANDS) {
                requireSection("media"); mediaPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown", retainValue = action != "media.readSpeechInput"); publishState() }; return
            }
            if (action in PocketWebGroups.COMMANDS) {
                requireSection("groups"); groupPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }; return
            }
            if (action == "teams.attention") {
                check(controller.webPrivateOwnerVisible()) { "请从本机主人界面读取团队待处理" }
                val window = captureWindow()
                teamPages.dispatch(action, args) { result, error ->
                    if (!closed && acceptsWindow(window)) complete(id, result, error, retainValue = !args.has("parentTaskId"))
                    else complete(id, null, "原通知窗口已变化，请重新读取团队待处理")
                    publishState()
                }
                return
            }
            if (action in PocketWebTeams.COMMANDS) {
                requireSection("teams"); teamPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown", retainValue = action !in setOf("teams.readFinalPackageFiles", "teams.chooseFinalPackageTarget", "teams.saveFinalPackageTarget", "teams.discardFinalPackageTarget")); if (action != "teams.readFinalPackageFiles") publishState() }; return
            }
            if (action == "conversation.readPrivateHostFinal") {
                privateHostFinal.read(args,{
                    !closed && documentId!=null && section==null && taskViewId==null &&
                        controller.state.filePath==null && controller.webPrivateOwnerVisible()
                }) { result,error -> complete(id,result,error,retainValue=false);publishState() }
                return
            }
            if (action == "conversation.openHostGoal") {
                require(args.keys().asSequence().toSet()==setOf("messageId","taskId","conversationId"))
                val task=requiredString(args,"taskId"); val message=requiredString(args,"messageId"); val conversation=requiredString(args,"conversationId")
                val at=controller.webWindowTicket()
                val current={ section==null && taskViewId==null && controller.state.filePath==null && controller.webPrivateOwnerVisible() &&
                    controller.webWindowTicket()==at && controller.state.conversationId==conversation && controller.state.messages.any { it.id==message && it.hostGoalTaskId==task } }
                check(current()) { "请回到带出这项主机工作的原私人对话" }
                openOriginalTask(id,task,false,sourceWindowCurrent=current,checkReaderInput={ check(current()); dev.pocketengine.ui.PrivateHostReceipt.verify(it,conversation,task) }); return
            }
            if (action == "conversation.captureHostGoal") {
                check(section == null && taskViewId == null && controller.state.filePath == null && controller.webPrivateOwnerVisible()) { "请返回原私人对话的输入框" }
                requireComposer(args)
                complete(id,connectionPages.hostGoalOrigins.capture(args),null,retainValue=false); return
            }
            if (action in PocketWebConnections.COMMANDS) {
                requireSection(action.substringBefore('.'))
                if (action == "remote.workspaceJobs") { connectionPages.cloudJobs.read(args) { row, error -> complete(id, row, error, retainValue = false) }; return }
                if (action == "remote.openWorkspaceJob") {
                    val origin = connectionPages.cloudJobs.opener(args)
                    openOriginalTask(id, args.getString("taskId"), true, sourceWindowCurrent = { section == "remote" && origin.current() }, checkReaderInput = { original -> origin.verify(original.raw) })
                    return
                }
                if (action == "ssh.workHistory") { connectionPages.workHistory.read(args) { row, error -> complete(id, row, error) }; return }
                if (action == "ssh.openOriginalWork") {
                    val origin = connectionPages.workHistory.opener(args)
                    openOriginalTask(id, args.getString("taskId"), true, sourceWindowCurrent = { section == "ssh" && origin.current() }, checkReaderInput = { original -> origin.verify(original.raw) })
                    return
                }
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
                if (action == "messageQuote.open" || action == "messageQuote.stage" || action == "messageQuote.pin" || action.startsWith("messageShelf.") && action != "messageShelf.close")
                    require(section == null && taskViewId == null && controller.webPrivateOwnerVisible()) { "请回到原私人对话选择引用" }
                messageQuotes.dispatch(action, args) { result, error -> val local = action == "messageQuote.pin" || action.startsWith("messageShelf.")
                    complete(id, result, error, retainValue = action != "messageQuote.open" && !local, replayHint = if (local) "请重新打开本对话备忘核对；没有重放上次内容或操作" else null); publishState() }; return
            }
            if (action == "sources.open") {
                val keySet = args.keys().asSequence().toSet()
                val history = setOf("readerWindow", "readerConversationId", "readerSourceRevision")
                if (keySet.any { it in history }) {
                    require(keySet == history + "messageId" && section == null && taskViewId == null && controller.webPrivateOwnerVisible()) { "请从原私人历史回答打开出处" }
                    val page = controller.conversationReader ?: error("原历史阅读窗口已关闭")
                    val revision = requiredLong(args, "readerSourceRevision")
                    require(page.window == requiredLong(args, "readerWindow") && page.conversation == requiredString(args, "readerConversationId") && revision > 0) { "原历史回答已变化" }
                    val stamp = captureWindow()
                    sourceReader.openHistory(page, requiredString(args, "messageId"), revision,
                        { !closed && taskViewId == null && section == null && acceptsWindow(stamp) }) { result, error -> complete(id, result, error, retainValue = false) }
                } else {
                    checkedPayload(args, setOf("messageId", "taskId"))
                    sourceReader.open(args.optString("messageId").takeIf { it.isNotBlank() }, args.optString("taskId").takeIf { it.isNotBlank() }) { result, error -> complete(id, result, error) }
                }
                return
            }
            if (action == "sources.read") {
                checkedPayload(args, setOf("windowId", "sourceHandle", "cursor", "offset"))
                val windowId = requiredString(args, "windowId"); val history = sourceReader.historyWindow(windowId)
                sourceReader.read(windowId, args.optString("sourceHandle").takeIf { it.isNotBlank() }, requiredInt(args, "cursor"), requiredInt(args, "offset")) { result, error -> complete(id, result, error, retainValue = !history) }; return
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
            if (action == "settings.canvas") {
                // Pure window presentation is available to every foreground workspace.
                // The document/window checks above still apply; no private data or preference is accessed.
                presentation.dispatch(action, args) { result, error -> complete(id, result, error) }; return
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
            if (action == "phone.draft.fromMessage") {
                check(section == null && taskViewId == null) { "请回到原对话准备手机目标" }
                val result = phoneComposer.stageOriginalMessage(args)
                controller.dismissError()
                voiceInput.clear()
                controller.voiceInputTypeChanged(phoneComposer.intent)
                complete(id, result, null); publishState(); return
            }
            if (action == "phone.prepare") { phoneComposer.prepare(id, args); return }
            if (action == "phone.review.settings") { complete(id, phoneComposer.openReviewedSettings(args), null); return }
            if (action == "phone.start") { phoneComposer.start(id, args); return }
            if (action == "phone.query") { phoneComposer.query(id, args); return }
            if (action == "phone.draft.recentStarts") { phoneComposer.recentDraft(id,args); return }
            if (action == "phone.resultImage.held") {
                checkedPayload(args, setOf("taskId","runId","epoch","resultRevision","resultImageDigest","imageId","imageSha256"))
                val target = requiredString(args,"taskId"); val origin = captureWindow(); val doc = documentId
                fun visible() = !closed && documentId == doc && acceptsWindow(origin) && section == null && taskViewId == target && controller.webPrivateOwnerVisible()
                controller.readHeldPrivatePhoneImage(target,args,::visible) { result,error ->
                    // Original pixels must never become a cached bridge receipt or be replayed after navigation.
                    val current = visible()
                    complete(id, if (current) result else null, if (current) error else "原任务页面已关闭。", retainValue=false)
                }
                return
            }
            if (action == "phone.draft.original") {
                val doc=documentId; val page=commandFence.ticket(); val owner=controller.displayedPhoneWindowVersion()
                fun current() = !closed && doc!=null && documentId==doc && commandFence.accepts(page) &&
                    section==null && taskViewId==null && controller.displayedPhoneWindowAlive(owner)
                phoneComposer.openFirstDraftRun(id,args,::current);return
            }
            if (action == "phone.draft.retireUncreated") { phoneComposer.query(id,args,true); return }
            if (action == "phone.ownerStep.review" || action == "phone.ownerStep.leave") {
                val leaving = action == "phone.ownerStep.leave"
                checkedPayload(args, if (leaving) setOf("taskId","runId","epoch","operationId","confirmed") else setOf("taskId","runId","epoch","operationId"))
                val target = requiredString(args,"taskId"); val run = requiredString(args,"runId"); val epoch = requiredInt(args,"epoch")
                val operation = requiredString(args,"operationId"); val task = requireTask(target); val window = captureWindow()
                fun current() = !closed && acceptsWindow(window) && taskViewId == target && controller.webPrivateOwnerVisible()
                require((!leaving || requiredBoolean(args,"confirmed")) && current() && epoch > 0 &&
                    task.raw.optString("runId") == run && task.raw.optInt("coordinatorEpoch",-1) == epoch && task.status == "awaiting_user") { "请从原主屏任务核对此步骤" }
                controller.mainPhoneOwnerStep(task, run, epoch, operation, leaving, ::current) { result, error ->
                    complete(id, result, error, retainValue = false)
                }
                return
            }
            if (action == "phone.pauseNote.read" || action == "phone.pauseNote.continue") {
                val sending = action.endsWith(".continue")
                checkedPayload(args, if (sending) setOf("taskId", "runId", "epoch", "reviewHandle", "note", "confirmed") else setOf("taskId", "runId", "epoch"))
                val target = requiredString(args, "taskId"); val run = requiredString(args, "runId"); val epoch = requiredInt(args, "epoch")
                val task = requireTask(target); val window = captureWindow()
                fun current() = !closed && acceptsWindow(window) && taskViewId == target && controller.webPrivateOwnerVisible()
                require(current() && isPhoneTask(task) && task.status == "paused" && task.raw.optString("kind") == "phone" && task.raw.optString("actorId") == "owner" &&
                    task.raw.optString("spaceId") == "private" && task.raw.isNull("origin") && task.raw.isNull("parentTaskId") && task.raw.isNull("ownerPhoneControl") &&
                    task.raw.isNull("ownerDeviceWork") && task.raw.optString("runId") == run && task.raw.optInt("coordinatorEpoch", -1) == epoch &&
                    PocketWebTaskActions.unresolvedForReview(task).isEmpty() && pendingCapabilityApprovals(task).isEmpty()) { "请在原私人本机任务处理本次暂停和未决事项" }
                val serial = ++pauseReadSerial
                val reviewed = pauseNoteReview; pauseNoteReview = null
                val text = if (sending) {
                    val exact = requiredString(args, "reviewHandle")
                    require(requiredBoolean(args, "confirmed") && reviewed != null && reviewed.handle == exact && reviewed.task == target && reviewed.run == run &&
                        reviewed.epoch == epoch && acceptsWindow(reviewed.window) && reviewed.expires > android.os.SystemClock.elapsedRealtime()) { "请重新核对同一次暂停；补充没有自动重发" }
                    requiredString(args, "note").trim().also { require(it.isNotEmpty() && it.length <= 8000 && '\u0000' !in it) { "补充须为 1 至 8000 字的文字" } }
                } else null
                controller.privatePhonePauseNote(task, reviewed?.pause ?: -1L, reviewed?.scope ?: -1L, text, { current() && pauseReadSerial == serial }) { result, error ->
                    if (!current() || serial != pauseReadSerial || error != null || result == null) complete(id, null, error ?: "原暂停读取窗口已变化，请重新核对", retainValue = false)
                    else if (!sending) {
                        val ticket = java.util.UUID.randomUUID().toString()
                        pauseNoteReview = PauseNoteReview(ticket, target, run, epoch, result.getLong("pauseRevision"), result.getLong("scopeRevision"), window, android.os.SystemClock.elapsedRealtime() + 120_000)
                        complete(id, result.put("reviewHandle", ticket), null, retainValue = false)
                    } else complete(id, result, null, retainValue = false)
                    publishState()
                }
                return
            }
            if (action == "phone.activity.original") {
                checkedPayload(args, setOf("taskId","runId","epoch","activityHandle"))
                val page=commandFence.ticket();val document=documentId;val owner=controller.displayedPhoneWindowVersion()
                fun current() = !closed && document!=null && documentId==document && commandFence.accepts(page) &&
                    section==null && taskViewId==null && controller.displayedPhoneWindowAlive(owner)
                controller.openShownPhoneActivity(requiredString(args,"taskId"),requiredString(args,"runId"),requiredInt(args,"epoch"),
                    requiredString(args,"activityHandle"),::current)
                // Admitted a historical read only. Opening the task still requires the original runs row.
                complete(id,JSONObject().put("reading",true),null,retainValue=false);return
            }
            if (action == "phone.resultNotice.read" || action == "phone.resultNotice.set") {
                val change = action.endsWith(".set")
                checkedPayload(args, if (change) setOf("taskId", "runId", "epoch", "watching") else setOf("taskId", "runId", "epoch"))
                val task = requireTask(requiredString(args,"taskId")); val run = requiredString(args,"runId"); val epoch = requiredInt(args,"epoch")
                require(controller.webPrivateOwnerVisible() && dev.pocketengine.store.PhoneActionRecovery.local(task.raw) && task.raw.optString("runId") == run &&
                    task.raw.optInt("coordinatorEpoch",-1) == epoch) { "原本机手机工作已变化，没有改其他轮的提醒" }
                val page = commandFence.ticket(); val owner = controller.displayedPhoneWindowVersion()
                fun current() = !closed && commandFence.accepts(page) && controller.displayedPhoneWindowAlive(owner)
                controller.privatePhoneResultWatch(task, if (change) requiredBoolean(args,"watching") else null, ::current) { result,error ->
                    complete(id,result,error,if (change) "outcome_unknown" else "not_accepted",retainValue = false)
                }
                return
            }
            if (action in setOf("phone.pause", "phone.resume", "phone.stop")) {
                val task = requireTask(requiredString(args, "taskId"))
                require(isPhoneTask(task) && canUseTaskControls(task) && task.raw.optString("runId") == requiredString(args, "runId") && task.raw.optInt("coordinatorEpoch", -1) == requiredInt(args, "epoch")) { "原手机任务或执行代次已变化，请刷新核对" }
                if (action == "phone.resume") require(task.status == "paused" && PocketWebTaskActions.unresolvedForReview(task).isEmpty() && pendingCapabilityApprovals(task).isEmpty()) { "请先核对原任务的暂停、未决动作与授权" }
                if (action == "phone.resume" && dev.pocketengine.store.PhoneActionRecovery.local(task.raw)) {
                    checkedPayload(args, setOf("taskId", "runId", "epoch", "pauseRevision", "scopeRevision", "displayHandle"))
                    val pause = requiredLong(args, "pauseRevision"); val scope = requiredLong(args, "scopeRevision"); val handle = requiredString(args, "displayHandle")
                    require(pause > 0 && scope > 0 && handle.length == 36 && controller.webPrivateOwnerVisible()) { "请展开原私人任务核对这一暂停" }
                    val page = commandFence.ticket(); val owner = controller.displayedPhoneWindowVersion()
                    // The guard passed into the SQL/control commit only reads volatile page/owner identity.
                    fun current() = !closed && commandFence.accepts(page) && controller.displayedPhoneWindowAlive(owner)
                    controller.resumeDisplayedPhone(task, pause, scope, handle, ::current) { result, error ->
                        complete(id, result, error, "outcome_unknown", retainValue = false); publishState()
                    }
                } else controller.controlOriginalPhone(task, action.substringAfter('.')) { result, error ->
                    val code=if(action=="phone.stop" && error!=null && result?.optString("stopInput")=="not_submitted") "not_accepted" else "outcome_unknown"
                    complete(id,result,error,code);publishState()
                }
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

    /** The first reply still reaches its private owner. Replay must not bypass today's native scope, revision or foreground lease. */
    private fun completePrivateMemoryReply(id: String, action: String, value: JSONObject?, error: String?) {
        val hint = when (action) {
            "memoryDraft.close", "memoryHistory.close", "knowledge.close" -> null // No record, source, selector or authority in a close ACK.
            "memoryDraft.confirm", "memoryDraft.cancel", "memoryDraft.undo" -> "此请求不会重复执行，请在原提案查询实际结果。"
            "knowledge.namespace" -> "原范围不会自动重新打开。请明确选择当前要查看的记忆范围。"
            "knowledge.confirm", "knowledge.project.propose", "knowledge.project.note.confirm", "knowledge.project.draft.confirm", "knowledge.consolidation.start", "knowledge.consolidation.stop",
            "memoryHistory.confirm", "taskMemory.confirm" -> "此请求不会重复执行，请在原记忆或任务核对实际结果。"
            else -> "原读取或审阅结果不能重放，请在原范围重新核对。"
        }
        complete(id, value, error, retainValue = hint == null, replayHint = hint)
    }
    private fun completeOwnerWorkControlRejection(id: String, message: String?) {
        complete(id, JSONObject().put("status", "rejected").put("message", message?.take(300) ?: "这次操作未接受，请重新打开任务。"), null)
    }

    private fun completeProfileSave(id: String, expected: Any?, window: PocketWebWindowTicket, result: JSONObject?, failure: String?) {
        val confirmed = ConfirmedProfileSave.fromNative(expected, result, failure)
        val original = !closed && acceptsWindow(window) && section == "profile" && taskViewId == null && controller.webPrivateOwnerVisible()
        val hint = "请回个人中心核对原保存；没有重复更改设置。"
        val message = if (confirmed != null) null else if (original) failure ?: hint else hint
        complete(id, if (original && confirmed != null) result else confirmed?.summary(), message,
            retainValue = false, replayHint = hint, profileSave = confirmed)
    }
    private fun complete(id: String, value: JSONObject?, error: String?, errorCode: String = "not_accepted", retainValue: Boolean = true, replayHint: String? = null, profileSave: ConfirmedProfileSave? = null) {
        if (closed) return
        val identity = pendingCommands.remove(id) ?: return
        val response = JSONObject().put("v", 1).put("kind", "result").put("id", id).put("ok", error == null)
        if (error == null) response.put("value", value ?: JSONObject()) else response.put("error", JSONObject().put("code", errorCode).put("message", error))
        // Transient reads are not durable command receipts. Keep only a small replay rejection.
        val replay = if (profileSave != null && error == null) JSONObject().put("v", 1).put("kind", "result").put("id", id).put("ok", true).put("value", profileSave.summary())
        else if (retainValue || error != null && replayHint == null) response else JSONObject().put("v", 1).put("kind", "result").put("id", id).put("ok", false)
            .put("error", JSONObject().put("code", if (replayHint == null) "preview_expired" else "original_receipt_required").put("message", replayHint ?: "原读取结果已过期，请重新读取"))
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

    private fun primaryDisplayComposerWindow(): () -> Boolean {
        require(section == null && taskViewId == null && controller.conversationReader == null && controller.state.filePath == null &&
            !phoneComposer.intent && !controller.hasChannelPhoneOrigin && controller.webPrivateOwnerVisible()) { "请从原私人输入发送主屏读数请求" }
        val document = requireNotNull(documentId)
        val ticket = commandFence.ticket()
        val epoch = controller.composerViewEpoch
        return { !closed && documentId == document && taskViewId == null && controller.conversationReader == null &&
            commandFence.accepts(ticket) && controller.composerViewEpoch == epoch }
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
                    documentId = nextDocument; background.clear(); remoteInbox.clear(); phoneViewer.clear(); phoneComposer.documentChanged(); taskHistory.clear(); controller.webChannels.clear(); controller.webSkills.clear(); reviews.clear(); phoneComposer.clearReviews(); voiceInput.clear(); modelReviews.clear(); automation.clear(); executionReviews.clear(); mcpImports.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); trajectory.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); personal.clear(); memoryHistory.clear(); conversationOrganization.clear(); taskCapabilities.clear(); deviceTaskScope.clear(); phoneControl.clear(); projectRuntime.clear(); projectLivePreview?.clear(); modelDiagnostics.clear(); attachmentPages.clear(); remoteTasks.clear(); teamEvidence.clear(); experience.clear(); routeReferences.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); projectWorkPages.clear(); devicePages.clear(); pluginPages.clear(); localPages.clear(); projectDevelopment.invalidateDraftReturn(); projectDevelopment.clear(); butlerPreparations.clear(); setupPages.clear(); accountWorkspace.invalidate(); composerModels.clear(); aiSources.clear(); conversationModels.clear()
                    controller.voiceInputTypeChanged(phoneComposer.intent)
                }
                return JSONObject().put("ready", true).put("source", "library-web")
            }

            "app.refresh", "tasks.refresh" -> controller.refresh()
            "notice.dismiss" -> controller.dismissNotice()
            "phone.notice.refresh","phone.notice.close" -> {
                checkedPayload(args,setOf("noticeHandle"));val token=requiredString(args,"noticeHandle")
                if(action=="phone.notice.refresh")controller.refreshPhoneNotification(token) else controller.dismissPhoneNotification(token)
            }
            "notice.task.close" -> controller.dismissOriginalTaskNotice()
            "error.dismiss" -> controller.dismissError()
            "composer.edit", "composer.send", "composer.mode" -> {
                val key = requiredString(args, "key"); val epoch = requiredInt(args, "epoch")
                require(controller.composerDraft?.key == key && controller.composerViewEpoch == epoch) { "原输入窗口已变化，草稿仍保留，请回原输入核对" }
                when (action) {
                    "composer.edit" -> {
                        val text = requiredString(args, "text", true)
                        if (args.has("expectedRevision")) {
                            val revision = args.opt("expectedRevision")
                            require(revision is Number && revision.toDouble() == revision.toLong().toDouble()
                                && controller.composerDraft?.revision == revision.toLong()) { "原输入已变化，协作目标没有覆盖当前草稿，请回原草稿核对" }
                            require(text.length <= dev.pocketengine.features.assistant.OwnerComposerState.MAX_TEXT) { "管家原草稿与协作目标合并后过长，请先整理原输入；两份内容仍保留" }
                        }
                        controller.editComposerText(key, epoch, text)
                        if (args.has("expectedRevision")) {
                            require(controller.composerDraft?.key == key && controller.composerViewEpoch == epoch
                                && controller.composerDraft?.text == text) { "协作目标尚未加入管家输入，请核对保留的原草稿" }
                        }
                    }
                    "composer.mode" -> { val mode = requiredString(args, "mode"); require(mode in setOf("queue", "steer")); controller.setChatSendMode(mode, key, epoch) }
                    else -> {
                        val text = requiredString(args, "text", true)
                        require(controller.composerDraft?.text == text && ChatInputRules.canSend(controller.state, false, text)) { "原输入尚不可发送，请核对保留草稿与原任务" }
                        val localRevision=if(args.has("primaryDisplayRevision"))requiredLong(args,"primaryDisplayRevision").also { require(it>0) } else null
                        controller.sendChat(text,key,epoch,localRevision,localRevision?.let { primaryDisplayComposerWindow() })
                    }
                }
            }
            "composer.reading" -> controller.saveComposerReading(requiredString(args, "key"), requiredInt(args, "index"), requiredInt(args, "offset"), args.optString("anchor"), args.optString("observed"), args.optBoolean("unread"))
            "composer.intent" -> {
                // A result reader can switch only its exact live private composer to chat. No send, phone start or voice handoff.
                if (args.has("phoneResultDiscussion")) {
                    checkedPayload(args,setOf("phone","phoneResultDiscussion"))
                    val q=args.getJSONObject("phoneResultDiscussion")
                    checkedPayload(q,setOf("conversation","message","delivery","draftEpoch","draftRevision","phoneEpoch","phoneRevision"))
                    val draft=controller.composerDraft ?: error("原私聊草稿尚未读取")
                    val conversation=requiredString(q,"conversation")
                    val messageId=requiredString(q,"message")
                    val message=controller.state.messages.singleOrNull { it.id==messageId } ?: error("原回执已离开当前对话")
                    val local=phoneComposer.snapshot()
                    check(!requiredBoolean(args,"phone") && section==null && taskViewId==null && controller.conversationReader==null &&
                        controller.webPrivateOwnerVisible() && !controller.hasChannelPhoneOrigin && phoneComposer.intent &&
                        conversation==controller.state.conversationId && draft.conversationId==conversation && draft.key=="conversation:"+conversation &&
                        controller.composerViewEpoch==requiredInt(q,"draftEpoch") && draft.revision==requiredLong(q,"draftRevision") &&
                        local.getInt("epoch")==requiredInt(q,"phoneEpoch") && local.getLong("revision")==requiredLong(q,"phoneRevision") &&
                        local.optString("sourceState") in setOf("none","current") && !local.optBoolean("firstCheckOnly") &&
                        local.optJSONObject("startReceipt")?.optString("status") !in setOf("pending","unknown") &&
                        !controller.composerSaving && !controller.state.chatSubmitting && !controller.state.phoneSubmitting &&
                        message.role=="assistant" && message.content.isNotBlank() && message.sourceRevision==1L && message.ownerOriginalLocator?.let {
                            it.optString("kind")=="owner_native_original" && it.optString("conversationId")==conversation && it.optString("recordId")==messageId && it.optLong("recordVersion",-1L)==message.sourceRevision
                        }==true &&
                        message.phoneResult?.let { it.deliveryId==requiredString(q,"delivery") && it.taskId==message.taskId && it.summaryChars>0 }==true
                    ) { "原手机回执或输入已变化，没有切换输入或启动任务" }
                    controller.switchIdleReceiptInput { phoneComposer.changeIntent(args) }
                } else {
                    phoneComposer.changeIntent(args)
                    controller.dismissError()
                    voiceInput.clear()
                    controller.voiceInputTypeChanged(phoneComposer.intent)
                }
            }
            "composer.query", "composer.retry", "composer.returnPending" -> {
                val client = requiredString(args, "clientMessageId")
                require(controller.composerPending.any { it.clientId == client } || controller.state.chatReceipts.containsKey(client)) { "原输入回执身份不可核对" }
                when (action) {
                    "composer.query" -> controller.queryChatSubmission(client)
                    "composer.retry" -> controller.retryLocallyUnsent(client,
                        if (controller.composerPending.any { it.clientId == client && it.primaryDisplayOnly }) primaryDisplayComposerWindow() else null)
                    else -> controller.returnToPendingComposer(client)
                }
            }
            "conversation.new" -> { leaveTask(); leaveConnection(); reviews.clear(); phoneComposer.clearReviews(); controller.newConversation() }
            "conversation.select" -> { leaveTask(); leaveConnection(); reviews.clear(); phoneComposer.clearReviews(); controller.openConversation(requiredString(args, "id")) }
            "conversation.reader.open" -> {
                require(section == null && taskViewId == null && controller.webPrivateOwnerVisible()) { "请回到原对话查看历史" }
                val conversation = requiredString(args, "conversationId")
                require(conversation == controller.state.conversationId) { "原对话已切换，请重新打开历史" }
                controller.openConversationReader(conversation)
            }
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
            "quickVoice.return" -> { checkedPayload(args, setOf("handoffId")); controller.returnQuickPanelVoice(requiredString(args, "handoffId")) }
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
                projectWorkPages.clear()
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
                val display = controller.state.feature("display")
                require(!display.optBoolean("taskOwned") && (display.has("taskOwned") || controller.state.activeTaskId == null)) { "原手机任务仍占用执行环境，未重新发现连接" }
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
                if (action in setOf("automation.list", "automation.receipts") && section == "profile") check(controller.webPrivateOwnerVisible())
                else if (action == "automation.receipts") require(section in setOf("automation", "commitments")) { "原提醒窗口已关闭" }
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
            }, { error ->
                val notApplied = error is dev.pocketengine.store.SettingsStore.ApiConnectionSaveNotApplied
                complete(requestId, null, (if (notApplied) "服务连接没有保存：" else "原连接操作结果待核对：") + (error.message ?: "请刷新原连接记录"), if (notApplied) "not_sent" else "outcome_unknown")
            })
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
            is PocketWebModelConnectionCommands.Save -> {
                val reference = command.draftReference
                if (reference == null) {
                    controller.saveApiConnection(command.adding, command.id, command.expectedRevision, command.name, command.input, completed, command.useForNewTasks)
                } else {
                    val window = captureWindow(); val document = documentId; val generation = domainGeneration
                    controller.runPluginCatalogWork(Runnable {
                        val reservation = runCatching { formDrafts.beginModelSave(reference, command.id, command.expectedRevision) }
                        main.post {
                            reservation.fold({ ticket ->
                                val notSent: (Exception) -> Unit = { failure ->
                                    val deliver = {
                                        complete(requestId, null, failure.message ?: "保存没有发出", "not_sent")
                                        publishState()
                                    }
                                    try {
                                        controller.runPluginCatalogWork(Runnable {
                                            runCatching { formDrafts.finishModelSave(ticket, null, true) }
                                            main.post { deliver() }
                                        })
                                    } catch (_: java.util.concurrent.RejectedExecutionException) { deliver() }
                                }
                                if (closed || document != documentId || generation != domainGeneration || section != "models" ||
                                    !acceptsWindow(window) || !controller.webPrivateOwnerVisible()) {
                                    notSent(IllegalStateException("原服务编辑窗口已关闭，保存没有发出"))
                                } else {
                                    try {
                                        requireModelSelectionIdle()
                                        controller.saveApiConnection(command.adding, command.id, command.expectedRevision, command.name, command.input,
                                            completed, command.useForNewTasks,
                                            { result -> formDrafts.finishModelSave(ticket, result.getOrNull(), result.exceptionOrNull() is dev.pocketengine.store.SettingsStore.ApiConnectionSaveNotApplied) })
                                    } catch (failure: Exception) { notSent(failure) }
                                }
                            }, { failure -> complete(requestId, null, failure.message ?: "服务草稿尚未核对", if (failure is PocketWebFormDrafts.ModelSaveAlreadyAccepted) "outcome_unknown" else "not_accepted") })
                        }
                    })
                }
            }
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

    private fun projectReadCurrent(scope: ProjectTaskReadScope) = controller.webPrivateOwnerVisible() && section == null &&
        controller.state.projectId == scope.projectId && controller.state.filePath == null

    private fun openOriginalTask(requestId: String, taskId: String, requestNavigation: Boolean, returnSection: String? = section ?: taskReturnSection, keepConversationReader: Boolean = false, expectedRunId: String? = null, expectedEpoch: Int? = null, projectScope: ProjectTaskReadScope? = null, sourceWindowCurrent: (() -> Boolean)? = null, checkedDeviceFile: dev.pocketengine.ui.ReceivedFileSelection? = null, originalFileApproval: String? = null, checkReaderInput: ((UiTask) -> Unit)? = null) {
        if (projectScope != null) require(projectReadCurrent(projectScope)) { "原项目页面已切换，未打开其他工作" }
        require(returnSection == null || returnSection in connectionSections) { "原工作页面已变化" }
        var entered = false
        fun enterWindow() {
            leaveTask(); leaveConnection(); reviews.clear()
            taskReturnSection = returnSection
            taskViewId = taskId
            entered = true
        }
        // A recorded input ACK does not prove its task is still on the same run.
        // Keep the reader and draft unchanged until the original is checked.
        if (checkReaderInput == null) { enterWindow(); publishState() }
        val window = captureWindow()
        val bindImage = controller.bindPhoneImageAfterOpen()
        fun currentSource()= !closed && acceptsWindow(window) && (projectScope == null || projectReadCurrent(projectScope)) && (sourceWindowCurrent?.invoke() != false)
        fun readOriginal(done: (UiTask?,String?) -> Unit) {
            if(originalFileApproval==null)controller.readTaskForOpen(taskId,::currentSource,done)
            else controller.readTaskForActionReview(taskId,originalFileApproval,::currentSource,done)
        }
        readOriginal { task, error ->
            if (closed) return@readOriginal
            if (!acceptsWindow(window)) complete(requestId, null, "原任务窗口已关闭，请重新打开")
            else if (error != null || task == null) {
                if ((requestNavigation || projectScope != null) && (sourceWindowCurrent == null || entered)) runCatching { restoreTaskWorkspace() }
                complete(requestId, null, error ?: "原任务暂不可读取")
                publishState()
            } else try {
                if (projectScope != null) {
                    require(projectReadCurrent(projectScope)) { "原项目页面已切换，未打开其他工作" }
                    projectScope.requireOriginal(taskId, task.raw)
                }
                if (expectedRunId != null) require(task.raw.optString("runId") == expectedRunId && task.raw.optInt("coordinatorEpoch") == expectedEpoch) { "原整理回合已变化，未打开其他执行记录" }
                checkReaderInput?.let { check -> check(task); enterWindow() }
                if (isGroup(task)) taskPage = controller.enterGroupTask(task.id)
                controller.completeWebTaskOpen(task.id, requestNavigation, keepConversationReader)
                bindImage(task)
                checkedDeviceFile?.let { file ->
                    require(entered && task.id==file.task && taskViewId==file.task && section==null)
                    val taskWindow=captureWindow()
                    attachmentPages.openOriginalFromDevice(file) { !closed && acceptsWindow(taskWindow) && section==null && taskViewId==file.task && controller.webPrivateOwnerVisible() }
                }
                val raw=task.raw; val dev=raw.optJSONObject("developmentContext")
                projectGoal=if (checkedDeviceFile==null && dev!=null && raw.optString("kind")=="assistant" && raw.optString("actorId")=="owner" &&
                    raw.optString("spaceId")=="private" && !raw.has("origin") && controller.webPrivateOwnerVisible() &&
                    raw.optString("runId").isNotBlank() && raw.optInt("coordinatorEpoch")>0 && dev.optString("projectId").isNotBlank() &&
                    dev.optString("checkoutId").isNotBlank()) GoalWindow(task.id,raw.getString("runId"),raw.getInt("coordinatorEpoch"),
                    dev.getString("projectId"),dev.getString("checkoutId"),captureWindow()) else null
                complete(requestId, JSONObject().put("taskId", task.id).put("readOnly", true).put("executionAuthorized", false), null, retainValue = checkedDeviceFile==null)
                publishState()
            } catch (failure: Exception) {
                if ((requestNavigation || projectScope != null || checkReaderInput != null && entered) && (sourceWindowCurrent == null || entered)) runCatching { restoreTaskWorkspace() }
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
    private fun visibleChatTaskResult(args: JSONObject): UiTaskResult? {
        if (!args.has("resultMessageId") && !args.has("conversationId")) return null
        val taskId = requiredString(args, "taskId")
        val conversation = requiredString(args, "conversationId")
        val messageId = requiredString(args, "resultMessageId")
        require(section == null && taskViewId == null && controller.webPrivateOwnerVisible() &&
            controller.state.conversationId == conversation) { "请回到这条结果所在的原对话" }
        val message = controller.state.messages.singleOrNull { it.id == messageId && it.role == "assistant" }
            ?: error("原对话中未找到这条已保存的结果")
        val result = message.taskResult ?: error("原消息没有这件工作的完成回执")
        require(message.taskId == taskId && result.taskId == taskId && result.status == "completed" && result.noticeKind == null) { "原消息没有这件工作的完成回执" }
        return result
    }
    private fun requireTask(id: String): UiTask = controller.state.tasks.firstOrNull { it.id == id } ?: error("原任务不在当前读取窗口，请刷新后重新打开")
    private fun leaveTask() { projectDraftDifference.clearWorkCopy();firstNodeWebOutput.clear();projectGoal=null; phoneViewer.clear(); localPages.clear(); projectDevelopment.clear(); butlerPreparations.clear(); setupPages.clear(); tokenBudgets.clear(); profilePages.clear(); projectWorkPages.clear(); composerModels.clear(); taskViewId = null; controller.closeWebTaskView(); inference.clear(); trajectory.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); personal.clear(); memoryHistory.clear(); conversationOrganization.clear(); taskCapabilities.clear(); deviceTaskScope.clear(); phoneControl.clear(); projectRuntime.clear(); projectLivePreview?.clear(); modelDiagnostics.clear(); attachmentPages.clear(); remoteTasks.clear(); teamEvidence.clear(); experience.clear(); routeReferences.clear(); commandFence.invalidate(); taskReviewGeneration++; taskPage?.let(controller::leaveGroupTask); taskPage = null }
    private fun leaveConnection() { localSourceIntake.close(); projectGitImport.close(); phoneViewer.clear(); controller.webChannels.clear(); controller.webSkills.clear(); commandFence.invalidate(); ++domainGeneration; modelReviews.clear(); automation.clear(); mcpImports.clear(); executionReviews.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); trajectory.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); personal.clear(); memoryHistory.clear(); conversationOrganization.clear(); taskCapabilities.clear(); deviceTaskScope.clear(); phoneControl.clear(); projectRuntime.clear(); projectLivePreview?.clear(); modelDiagnostics.clear(); attachmentPages.clear(); remoteTasks.clear(); teamEvidence.clear(); experience.clear(); routeReferences.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); projectWorkPages.clear(); devicePages.clear(preserveModelPlace=true); pluginPages.clear(); localPages.clear(); projectDevelopment.clear(); butlerPreparations.clear(); setupPages.clear(); accountWorkspace.invalidate(); composerModels.clear(); aiSources.clear(); conversationModels.clear(); deviceWindow?.close(); deviceWindow = null; section?.let { if (it == "memory") controller.leaveOwnerDomain("memoryReview") else if (it in ownerDomains) controller.leaveOwnerDomain(it) }; section?.takeIf { it == "archives" || it == "storage" }?.let(dataPages::close); section = null }
    /**
     * Navigation already disposes these workspace handles in leaveConnection().
     * React unmount cleanup may arrive after that transition. Acknowledge only
     * these disposal commands without dispatching them into the next workspace.
     */
    private fun retiredWorkspaceCleanup(action: String, args: JSONObject): JSONObject? {
        val expectedSection = when (action) {
            "skills.close" -> "skills"
            "devices.discard" -> "devices"
            "plugins.close" -> "plugins"
            "profiles.discard" -> "workProfiles"
            "channels.close" -> "channels"
            else -> return null
        }
        val stillOpen = if (action == "plugins.close") section == "plugins" || section == "pluginCatalog"
            else section == expectedSection
        if (stillOpen) return null
        val allowed = when (action) {
            "skills.close" -> setOf("handle")
            "devices.discard" -> setOf("viewHandle")
            "plugins.close" -> setOf("pageRequestId")
            "profiles.discard" -> setOf("viewHandle", "reviewHandle", "choicesHandle", "preflightHandle")
            else -> emptySet()
        }
        checkedPayload(args, allowed)
        args.keys().asSequence().forEach { requiredString(args, it) }
        if (action == "plugins.close") requiredString(args, "pageRequestId")
        return JSONObject().put("closed", false).put("alreadyClosed", true)
    }

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

