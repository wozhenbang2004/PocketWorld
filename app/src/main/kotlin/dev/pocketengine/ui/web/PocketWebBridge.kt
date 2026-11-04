package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.ModelConfig
import dev.pocketengine.features.devices.NativeDeviceWorkspaceHost
import dev.pocketengine.runtime.models.ModelBinding
import dev.pocketengine.runtime.models.ReasoningOptions
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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val applyScheduled = AtomicBoolean(false)
    private val observer = SnapshotStateObserver { callback -> main.post { if (started && !closed) callback() } }
    private var releaseGlobalObserver: (() -> Unit)? = null
    private var started = false
    @Volatile private var closed = false
    private var revision = 0L
    private var lastState: String? = null
    private var section: String? = null
    private var taskPage: String? = null
    private var taskViewId: String? = null
    private var taskReturnSection: String? = null
    private var workspaceResumeRevision = 0L
    private var taskReviewGeneration = 0L
    private val commandFence = PocketWebCommandFence()
    private val reviews = PocketWebTaskReviews()
    private val taskHistory = PocketWebTaskHistory()
    private val reminderReviews = PocketWebReminders()
    private val mcpImports = PocketWebMcp(controller)
    private val sourceReader = PocketWebSources(controller)
    private val messageQuotes = PocketWebMessageQuotes(controller)
    private val inference = PocketWebInference(controller)
    private val skillDrafts = PocketWebSkillDrafts(controller)
    private val memoryDrafts = PocketWebMemoryDrafts(controller)
    private val knowledge = PocketWebKnowledge(controller)
    private val experience = PocketWebExperience(controller)
    private val routeReferences = PocketWebRouteReferences(controller)
    private val dataPages = PocketWebData(controller)
    private val connectionPages = PocketWebConnections(controller)
    private val teamPages = PocketWebTeams(controller)
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
    private val localPages = PocketWebLocal(controller)
    private val commandReceipts = linkedMapOf<String, Pair<String, JSONObject>>()
    private val pendingCommands = mutableMapOf<String, String>()
    private data class PhoneReview(val goal: String, val mode: String, val tools: Int, val models: Int, val minutes: Int,
        val viewEpoch: Int, val expires: Long, val window: PocketWebWindowTicket, val composerKey: String,
        val conversation: String, val draftEpoch: Int, val draftRevision: Long,
        val linked: EngineApplication.ConversationPhoneStartReview?)
    private val phoneReviews = mutableMapOf<String, PhoneReview>()
    @Volatile private var documentId: String? = null
    @Volatile private var presentationEpoch = 0
    private var phoneDraft = ""
    @Volatile private var phoneDraftRevision = 0L
    private var phoneIntent = false
    private var phoneExecutionMode = "background"
    private var phoneStartReceipt: JSONObject? = null
    private data class VoiceReview(val inputId: String, val phone: Boolean, val recovered: Boolean, val key: String, val draftRevision: Long, val epoch: Int, val phoneRevision: Long, val expires: Long)
    private val voiceReviews = mutableMapOf<String, VoiceReview>()
    private var deviceWindow: NativeDeviceWorkspaceHost.Window? = null
    private val devicePages = PocketWebDevices(controller, { deviceWindow }, { !closed && section == "devices" && controller.webPrivateOwnerVisible() })
    private val pluginPages = PocketWebPlugins(controller, controller.webPluginFeatureHost())
    private val domainSnapshots = mutableMapOf<String, JSONObject>()
    private val domainErrors = mutableMapOf<String, String>()
    @Volatile private var domainGeneration = 0L
    private data class ModelReview(val binding: ModelBinding, val model: String, val identity: String, val output: Int, val reasoning: ReasoningOptions, val imageReserve: Int, val generation: Long, val document: String?, val expires: Long, val selectionRevision: Long)
    private val modelReviews = mutableMapOf<String, ModelReview>()
    @Volatile private var modelSelectionBusy = false
    private var observedModelRevision = -1L
    private val accountWorkspace = PocketWebAccounts(controller.nativeAccountWorkflows(), controller.nativeModelSelection(), { !closed && section == "accounts" && controller.webPrivateOwnerVisible() })
    private data class ExecutionReview(val revision: Long, val enabled: Boolean, val expires: Long, val window: Pair<Triple<Long, Int, Long>, Long>)
    private val executionReviews = mutableMapOf<String, ExecutionReview>()

    private val composerModels = PocketWebComposerModels(controller, { !closed && section == null && taskViewId == null && controller.webPrivateOwnerVisible() })

    companion object {
        private val ownerDomains = setOf("memoryReview", "taskMemory", "mcp", "accounts", "learning", "groups", "channelMedia")
        private val connectionSections = setOf("devices", "models", "accounts", "remote", "ssh", "local", "memory", "memoryReview", "automation", "commitments", "channels", "skills", "learning", "plugins", "pluginCatalog", "mcp", "teams", "groups", "media", "realtime", "proxy", "workProfiles")
        val commands: Set<String> = linkedSetOf(
            "app.ready", "app.refresh", "notice.dismiss", "notice.task.close", "error.dismiss", "execution.read", "execution.review", "execution.confirm",
            "composer.edit", "composer.send", "composer.mode", "composer.intent", "composer.reading", "composer.query", "composer.retry", "composer.returnPending",
            "chat.cancel", "conversation.new", "conversation.select", "conversation.reader.close", "conversation.reader.earlier", "conversation.reader.refresh",
            "attachments.pick", "attachments.openPicker", "attachments.closePicker", "attachments.toggle", "attachments.remove", "attachments.open", "sources.open", "sources.read", "sources.close",
            "quickVoice.start", "quickVoice.save", "quickVoice.close",
            "skillDraft.open", "skillDraft.read", "skillDraft.source", "skillDraft.confirm", "skillDraft.cancel", "skillDraft.query", "skillDraft.close", "voice.start", "voice.stop", "voice.review", "voice.accept", "voice.discard", "speech.speak", "speech.stop", "speech.taskAnnouncements", "speech.stopTaskAnnouncement",
            "tasks.refresh", "tasks.open", "tasks.close", "tasks.stop", "tasks.review", "tasks.confirm", "tasks.approve", "tasks.resolve", "tasks.resume", "tasks.scope", "tasks.plan.enter", "tasks.answerQuestion", "tasks.answerQuestionChoice", "tasks.deleteScreenshot", "privacy.screenshots.read", "privacy.screenshots.save",
            "projects.refresh", "projects.select", "projects.create", "projects.open", "projects.newFile", "projects.edit", "projects.save", "projects.close", "projects.history", "projects.revision", "projects.useRevision", "projects.retry",
            "settings.reviewModelTransport", "settings.saveLocalObservation", "settings.openSystem", "settings.openBrowser",
            "connections.enter", "connections.leave", "models.snapshot", "models.review", "models.select", "models.connection.add", "models.connection.edit", "models.connection.test", "models.connection.select", "devices.snapshot", "history.read", "usage.read", "usage.task", "usage.close", "usage.reestimate",
            "phone.prepare", "phone.start", "phone.query", "phone.draft.edit", "phone.pause", "phone.resume", "phone.stop", "phone.deadline", "background.refresh", "background.discover", "background.cancel", "background.close", "background.pair", "background.connect", "background.mainScreen", "background.selectMainScreen",
            "queued.open", "queued.refresh", "queued.close", "queued.edit", "queued.change", "queued.save", "queued.editor.close",
            "accounts.beginLogin", "accounts.cancelLogin", "accounts.openAuthorization", "accounts.select", "accounts.refresh", "accounts.models", "accounts.retryPlanUsage", "accounts.model.review", "accounts.model.select", "accounts.logout.review", "accounts.logout.confirm", "accounts.setEnabled", "accounts.refreshAuthorization", "accounts.reauthorize", "accounts.acknowledgePlanWelcome", "accounts.manageUsage",
            "memory.list", "memory.search", "memoryReview.list", "memoryReview.getCandidate", "memoryReview.capacity", "memoryReview.history", "memoryReview.openSource",
            "memoryReview.candidates", "mcp.snapshot", "mcp.pickConfig", "mcp.cancel", "mcp.discoverImport", "mcp.reviewImport", "mcp.installImport", "mcp.reviewEnabled", "mcp.confirmEnabled", "mcp.read", "mcp.reviewGrant", "mcp.confirmGrant", "learning.snapshot", "groups.snapshot",
            "remote.probe", "remote.environments", "local.termuxProbe", "local.inspectRuntime", "local.readReceipts", "local.prepareCheckout", "local.describeCheckout", "local.terminal.review", "local.terminal.confirm", "local.terminal.query", "local.terminal.stop", "local.terminal.open", "local.terminal.read", "local.terminal.input", "local.terminal.inputReceipt", "local.terminal.close", "automation.receipts", "automation.list", "automation.reviewSchedule", "automation.reviewRule", "automation.confirmReview", "automation.prepareOccurrence", "channels.inspect", "skills.read", "plugins.history", "proxy.status", "proxy.stop"
        ) + PocketWebData.COMMANDS + PocketWebConnections.COMMANDS + PocketWebMemoryDrafts.COMMANDS + setOf("conversation.drafts") + PocketWebKnowledge.COMMANDS + PocketWebMessageQuotes.COMMANDS + PocketWebChannels.COMMANDS + PocketWebExperience.COMMANDS + PocketWebRouteReferences.COMMANDS + PocketWebSkills.COMMANDS + PocketWebPresentation.COMMANDS + PocketWebTeams.COMMANDS + PocketWebGroups.COMMANDS + PocketWebMedia.COMMANDS + PocketWebComposerModels.COMMANDS + PocketWebDevices.COMMANDS + PocketWebPlugins.COMMANDS + PocketWebRealtime.COMMANDS + PocketWebProxy.COMMANDS + PocketWebPlans.COMMANDS + PocketWebCommitments.COMMANDS + PocketWebTokenBudgets.COMMANDS + PocketWebProfiles.COMMANDS
    }

    /** Equivalent to the former Activity saveable presentation state, never an execution grant. */
    fun savePresentationState(): JSONObject = JSONObject().put("phoneDraft", phoneDraft)
        .put("phoneDraftRevision", phoneDraftRevision).put("phoneIntent", phoneIntent).put("phoneExecutionMode", phoneExecutionMode)
        .put("phoneStartReceipt", phoneStartReceipt)

    fun restorePresentationState(saved: JSONObject?) {
        check(!started) { "Restore presentation before the Web document starts" }
        if (saved == null) return
        val text = saved.optString("phoneDraft")
        if (text.length <= 12000) phoneDraft = text
        phoneDraftRevision = saved.optLong("phoneDraftRevision").coerceAtLeast(0)
        phoneIntent = saved.optBoolean("phoneIntent")
        phoneExecutionMode = saved.optString("phoneExecutionMode").takeIf { it in setOf("background", "main_screen") } ?: "background"
        phoneStartReceipt = saved.optJSONObject("phoneStartReceipt")?.let { JSONObject(it.toString()) }
        // Restoring a view cannot prove whether its previous worker completed.
        if (phoneStartReceipt?.optString("status") == "pending") phoneStartReceipt?.put("status", "unknown")
    }

    fun start() {
        if (closed || started) return
        check(Looper.myLooper() == Looper.getMainLooper())
        started = true
        presentation.start()
        observer.start()
        // The Web host creates no AndroidComposeView, so it owns global state notifications.
        val handle = Snapshot.registerGlobalWriteObserver {
            if (applyScheduled.compareAndSet(false, true)) main.post {
                applyScheduled.set(false)
                if (started && !closed) Snapshot.sendApplyNotifications()
            }
        }
        releaseGlobalObserver = { handle.dispose() }
        publishState(true)
    }

    /** Presentation switching must not discard a command or a model selection awaiting a receipt. */
    fun canSwitchPresentation(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return !closed && dev.pocketengine.ui.nativeWorkspaceExitAllowed(false, pendingCommands.isNotEmpty() || modelSelectionBusy || controller.modelSelectionPending())
    }

    fun close() {
        if (closed) return
        closed = true
        started = false
        presentation.close()
        releaseGlobalObserver?.invoke(); releaseGlobalObserver = null
        observer.stop(); observer.clear()
        reviews.clear()
        taskHistory.clear()
        phoneReviews.clear()
        voiceReviews.clear()
        modelReviews.clear(); reminderReviews.clear(); mcpImports.clear(); executionReviews.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); experience.clear(); routeReferences.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); devicePages.clear(); pluginPages.clear(); localPages.clear(); accountWorkspace.close(); composerModels.close(); controller.webChannels.clear(); controller.webSkills.clear()
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
        commandFence.invalidate()
        taskReviewGeneration++
        domainGeneration++
        taskHistory.clear()
        reviews.clear(); phoneReviews.clear(); voiceReviews.clear(); modelReviews.clear(); reminderReviews.clear(); mcpImports.clear(); executionReviews.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); experience.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); devicePages.clear(); pluginPages.clear(); localPages.clear(); accountWorkspace.invalidate(); composerModels.clear()
        if (documentReplaced) routeReferences.clear() else routeReferences.background()
        if (documentReplaced) { controller.webChannels.clear(); controller.webSkills.clear() } else { controller.webChannels.background(); controller.webSkills.background() }
        if (documentReplaced) { documentId = null; taskReturnSection = null }
    }

    private fun publishState(force: Boolean = false) {
        if (closed || !started) return
        var projection: JSONObject? = null
        observer.observeReads(this, { _: PocketWebBridge -> publishState() }) {
            controller.modelSelectionRevision
            projection = PocketWebState.snapshot(controller)
            projection?.getJSONObject("state")?.put("appearance", presentation.snapshot())
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
            .put("phoneDraft", JSONObject().put("text", phoneDraft).put("revision", phoneDraftRevision)
                .put("epoch", presentationEpoch).put("phone", phoneIntent).put("mode", phoneExecutionMode).put("startReceipt", phoneStartReceipt))
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
                require(controller.state.tasks.any { it.id == taskId } || taskHistory.contains(taskId) || channelUsageVisible || phoneResultVisible) { "请先重新读取这个原任务的历史窗口" }
                val requestNavigation = if (args.has("requestNavigation")) requiredBoolean(args, "requestNavigation") else true
                openOriginalTask(id, taskId, requestNavigation)
                return
            }
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
            if (action in PocketWebOwnerQuestions.commands) {
                val field = if (action == PocketWebOwnerQuestions.CHOOSE) "optionId" else "text"
                checkedPayload(args, setOf("reviewHandle", field))
                val task = reviews.take(requiredString(args, "reviewHandle"), action, controller.state.tasks)
                require(canUseTaskControls(task) && !specialOwnerTask(task)) { "原问题审核窗口已关闭" }
                PocketWebOwnerQuestions.dispatch(controller, task, action, args) { result, error ->
                    complete(id, result, error, "outcome_unknown"); publishState()
                }
                return
            }
            if (action == "tasks.plan.enter") {
                checkedPayload(args, setOf("reviewHandle"))
                val task = reviews.take(requiredString(args, "reviewHandle"), action, controller.state.tasks)
                require(canUseTaskControls(task) && !specialOwnerTask(task)) { "原任务规划窗口已关闭" }
                submitExecutionPlanDecision(id, "enterTaskPlanning", task, PocketWebApprovals.planningInput(task))
                return
            }
            if (action == "tasks.approve") {
                val approved = requiredBoolean(args, "approved")
                val approvalId = requiredString(args, "approvalId")
                val task = reviews.take(requiredString(args, "reviewHandle"), action, controller.state.tasks, approvalId.takeIf { approved })
                require(canUseTaskControls(task)) { "原审核窗口已关闭" }
                val original = pendingCapabilityApprovals(task).firstOrNull { it.optString("approvalId", it.optString("id")) == approvalId }
                    ?: error("这项授权不在原审阅中")
                PocketWebApprovals.requireDecision(original, args)
                if (PocketWebApprovals.isExecutionPlan(original)) {
                    checkedPayload(args, setOf("reviewHandle", "approvalId", "approved", "scopeReviewed", "planRevision", "planArtifactRef", "planHash"))
                    require(!specialOwnerTask(task)) { "请回原专用任务处理计划" }
                    submitExecutionPlanDecision(id, "approveCapability", task, PocketWebApprovals.executionDecisionInput(task, original, approved))
                } else {
                    controller.approveCapability(task, approvalId, approved)
                    complete(id, accepted(), null); publishState()
                }
                return
            }
            if (action == "tasks.review") {
                val taskId = requiredString(args, "id")
                requireTask(taskId)
                val generation = taskReviewGeneration
                val document = documentId
                val navigation = Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
                // A tasks.open acceptance only schedules getTask. Read the actual
                // result/evidence before issuing the handle that can confirm it.
                controller.readTaskForReview(taskId) { task, error ->
                    if (closed) return@readTaskForReview
                    val currentNavigation = Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
                    if (generation != taskReviewGeneration || document != documentId || navigation != currentNavigation) {
                        complete(id, null, "原任务窗口已关闭，请重新打开后核对")
                    } else if (error != null || task == null) {
                        complete(id, null, error ?: "任务结果尚未读取，请重试")
                    } else {
                        val captured = task.copy(raw = JSONObject(task.raw.toString()))
                        fun finish(approvals: JSONArray) {
                            val latestNavigation = Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
                            if (generation != taskReviewGeneration || document != documentId || navigation != latestNavigation) {
                                complete(id, null, "原任务窗口已关闭，请重新打开后核对")
                            } else {
                                complete(id, reviewTask(captured, approvals), null); publishState()
                            }
                        }
                        try {
                            if (pendingCapabilityApprovals(captured).any(PocketWebApprovals::needsOriginalPlan)) {
                                controller.runEcosystemControlWork(Runnable {
                                    val result = runCatching { PocketWebApprovals.read(captured, controller) }
                                    main.post {
                                        if (!closed) result.fold({ approvals ->
                                            try { finish(approvals) } catch (failure: Exception) { complete(id, null, failure.message ?: "任务结果暂时无法核对") }
                                        }, { failure -> complete(id, null, failure.message ?: "原计划暂时无法读取") })
                                    }
                                })
                            } else finish(PocketWebApprovals.read(captured, controller))
                        } catch (failure: Exception) { complete(id, null, failure.message ?: "任务结果暂时无法核对") }
                    }
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
            if (action in PocketWebKnowledge.COMMANDS) {
                if (action == "knowledge.close") check(section in setOf("memory", "memoryReview", "learning")) else if (action.startsWith("knowledge.")) check(section in setOf("memory", "memoryReview")) { "请先打开记忆管理页" } else requireSection("learning")
                knowledge.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return
            }
            if (action in PocketWebMemoryDrafts.COMMANDS) { memoryDrafts.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return }
            if (action in PocketWebSkillDrafts.COMMANDS) { skillDrafts.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return }
            if (action in PocketWebLocal.COMMANDS) { requireSection("local"); localPages.dispatch(action, args) { result, error -> complete(id, result, error); publishState() }; return }
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
                    result?.optJSONObject("snapshot")?.let { domainSnapshots["devices"] = JSONObject(it.toString()) }
                    if (error == null) domainErrors.remove("devices") else domainErrors["devices"] = error
                    complete(id, result, error, "outcome_unknown"); publishState()
                }
                return
            }
            if (action in PocketWebPlugins.COMMANDS) {
                require(section in setOf("plugins", "pluginCatalog")) { "原插件窗口已关闭，请重新进入" }
                pluginPages.dispatch(action, args) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }
                return
            }
            if (action in PocketWebComposerModels.COMMANDS) {
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
                val review = executionReviews.remove(requiredString(args, "reviewHandle")) ?: error("原运行开关审阅已关闭或已使用")
                check(SystemClock.elapsedRealtime() < review.expires) { "原运行开关审阅已过期" }
                check(controller.webWindowTicket() == review.window) { "原运行开关窗口已变化，请重新核对" }
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
            if (action in setOf("models.connection.add", "models.connection.edit", "models.connection.test", "models.connection.select")) { modelConnection(id, action, args); return }
            if (action == "phone.start") { startPhone(id, args); return }
            if (action == "phone.query") { queryPhone(id, args); return }
            if (action in setOf("phone.pause", "phone.resume", "phone.stop")) {
                val task = requireTask(requiredString(args, "taskId"))
                require(isPhoneTask(task) && canUseTaskControls(task) && task.raw.optString("runId") == requiredString(args, "runId") && task.raw.optInt("coordinatorEpoch", -1) == requiredInt(args, "epoch")) { "原手机任务或执行代次已变化，请刷新核对" }
                if (action == "phone.resume") require(task.status == "paused" && unresolvedForReview(task).isEmpty() && pendingCapabilityApprovals(task).isEmpty()) { "请先核对原任务的暂停、未决动作与授权" }
                controller.controlOriginalPhone(task, action.substringAfter('.')) { result, error -> complete(id, result, error, "outcome_unknown"); publishState() }
                return
            }
            val result = dispatch(action, args)
            complete(id, result ?: accepted(), null)
            publishState(action == "app.ready")
        } catch (failure: Exception) {
            if (id.isNotEmpty()) {
                if (ownsRequest) complete(id, null, failure.message ?: "操作未接受")
                else emit(JSONObject().put("v", 1).put("kind", "result").put("id", id).put("ok", false)
                    .put("error", JSONObject().put("code", "invalid_request").put("message", failure.message ?: "请求未接受")))
            }
        }
    }

    private fun complete(id: String, value: JSONObject?, error: String?, errorCode: String = "not_accepted") {
        if (closed) return
        val identity = pendingCommands.remove(id) ?: return
        val response = JSONObject().put("v", 1).put("kind", "result").put("id", id).put("ok", error == null)
        if (error == null) response.put("value", value ?: JSONObject()) else response.put("error", JSONObject().put("code", errorCode).put("message", error))
        commandReceipts[id] = identity to response
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

    private fun phoneReviewCurrent(review: PhoneReview): Boolean = !closed && controller.webPrivateOwnerVisible() &&
        SystemClock.elapsedRealtime() < review.expires && acceptsWindow(review.window) &&
        review.viewEpoch == controller.composerViewEpoch && review.composerKey == controller.composerDraft?.key.orEmpty() &&
        review.conversation == controller.state.conversationId.orEmpty() && review.draftEpoch == presentationEpoch && review.draftRevision == phoneDraftRevision &&
        review.tools == controller.newTaskToolBudget && review.models == controller.newTaskModelBudget && review.minutes == controller.newTaskScopeMinutes

    private fun phoneReceiptIdentity(handle: String, review: PhoneReview): JSONObject = JSONObject()
        .put("reviewHandle", handle).put("goal", review.goal).put("mode", review.mode)
        .put("draftEpoch", review.draftEpoch).put("draftRevision", review.draftRevision)
        .put("composerKey", review.composerKey).put("composerEpoch", review.viewEpoch).put("draftConversationId", review.conversation)
        .also { identity -> review.linked?.view()?.let { identity.put("requestId", it.getString("requestId")).put("conversationId", it.getString("conversationId")) } }

    private fun consumePhoneDraft(receipt: JSONObject) {
        if (receipt.optString("status") == "accepted" && receipt.optLong("draftRevision", -1) == phoneDraftRevision &&
            receipt.optInt("draftEpoch", -1) == presentationEpoch && receipt.optInt("composerEpoch", -1) == controller.composerViewEpoch &&
            receipt.optString("composerKey") == controller.composerDraft?.key.orEmpty() &&
            receipt.optString("draftConversationId") == controller.state.conversationId.orEmpty() && phoneDraft.trim() == receipt.optString("goal").trim()) {
            phoneDraft = ""; phoneDraftRevision++
        }
    }

    private fun recordPhoneReceipt(original: JSONObject, result: JSONObject?, error: String?): JSONObject {
        val receipt = JSONObject(original.toString())
        val linked = original.has("requestId")
        val task = result?.optString("taskId")?.takeIf { it.isNotBlank() }
        val same = !linked || result != null && result.optString("requestId") == original.getString("requestId") &&
            result.optString("conversationId") == original.getString("conversationId") && task == original.getString("requestId")
        val rejected = linked && error != null && result != null && result.optString("status") == "rejected" && !result.has("taskId") &&
            result.optString("requestId") == original.getString("requestId") && result.optString("conversationId") == original.getString("conversationId")
        val accepted = error == null && task != null && same && (!linked || result?.optBoolean("accepted") == true)
        if (same && task != null) receipt.put("taskId", task)
        if (same && result != null) {
            for (key in listOf("runId", "epoch", "admitted")) if (result.has(key)) receipt.put(key, result.get(key))
            if (result.has("status")) receipt.put("taskStatus", result.get("status"))
        }
        receipt.put("status", if (rejected) "rejected" else if (accepted) "accepted" else "unknown")
            .put("message", if (rejected) error + "。尚未创建手机任务，草稿仍保留；请明确重新审阅。" else if (accepted && linked) {
                if (result?.optBoolean("admitted") == true) "手机任务已获准执行，结果会回到原对话。" else "手机任务已保存到原对话；尚未确认获准执行，请查看原任务。"
            } else if (accepted) "原手机任务已创建。" else "启动结果待核对。请查询原请求，避免再次提交同一目标。")
        phoneStartReceipt = receipt
        consumePhoneDraft(receipt)
        return receipt
    }

    private fun queryPhone(id: String, args: JSONObject) {
        checkedPayload(args, setOf("requestId", "conversationId"))
        val original = phoneStartReceipt?.let { JSONObject(it.toString()) } ?: error("没有可查询的原手机请求")
        require(original.optString("requestId") == requiredString(args, "requestId") && original.optString("conversationId") == requiredString(args, "conversationId")) { "请查询原手机启动回执中的请求" }
        controller.queryConversationPhoneStart(original.getString("requestId"), original.getString("conversationId")) { result, error ->
            // A late read remains about its original request and must not replace a newer receipt.
            if (phoneStartReceipt?.optString("requestId") == original.getString("requestId")) {
                complete(id, recordPhoneReceipt(original, result, error), null); publishState()
            } else complete(id, original, null)
        }
    }

    private fun startPhone(id: String, args: JSONObject) {
        checkedPayload(args, setOf("reviewHandle"))
        val handle = requiredString(args, "reviewHandle")
        val review = phoneReviews[handle] ?: error("原手机目标审阅已关闭或已使用")
        require(phoneReviewCurrent(review)) { "原对话、目标窗口或执行范围已变化，请重新核对" }
        require(!controller.state.phoneSubmitting && controller.state.activeTaskId == null) { "已有原工作正在处理，未重复开始" }
        val original = phoneReceiptIdentity(handle, review)
        val completed: (JSONObject?, String?) -> Unit = { result, error ->
            complete(id, recordPhoneReceipt(original, result, error), null)
            publishState()
        }
        val admitted = review.linked?.let { controller.startConversationPhone(it, { phoneReviewCurrent(review) }, completed) }
            ?: controller.startPhone(review.goal, review.mode, completed)
        if (admitted) {
            phoneReviews.remove(handle)
            phoneStartReceipt = original.put("status", "pending").put("message", "正在等待原手机任务的启动回执。")
        } else {
            complete(id, JSONObject().put("status", "rejected").put("message", controller.state.error ?: controller.state.notice ?: "这次任务尚未接纳，草稿仍保留。"), null)
        }
        publishState()
    }

    private fun dispatch(action: String, args: JSONObject): JSONObject? {
        when (action) {
            "execution.review" -> {
                checkedPayload(args, setOf("enabled")); check(!controller.ownerExecutionSaving) { "请先等待原运行开关回执" }
                val policy = controller.ownerExecutionPolicy
                check(policy.optString("format") == "pocketworld.owner-execution-policy.v1" && policy.optLong("revision") > 0) { "请先读取原运行状态" }
                val enabled = requiredBoolean(args, "enabled"); val handle = UUID.randomUUID().toString()
                executionReviews.clear(); executionReviews[handle] = ExecutionReview(policy.getLong("revision"), enabled, SystemClock.elapsedRealtime() + 120_000, controller.webWindowTicket())
                return JSONObject(policy.toString()).put("reviewHandle", handle).put("nextEnabled", enabled)
            }
            "app.ready" -> {
                val nextDocument = requiredString(args, "documentId")
                require(nextDocument.length <= 120)
                if (nextDocument != documentId) {
                    documentId = nextDocument; presentationEpoch++; taskHistory.clear(); controller.webChannels.clear(); controller.webSkills.clear(); reviews.clear(); phoneReviews.clear(); voiceReviews.clear(); modelReviews.clear(); reminderReviews.clear(); executionReviews.clear(); mcpImports.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); experience.clear(); routeReferences.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); devicePages.clear(); pluginPages.clear(); localPages.clear(); accountWorkspace.invalidate(); composerModels.clear()
                    controller.voiceInputTypeChanged(phoneIntent)
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
                        val text = requiredString(args, "text")
                        require(controller.composerDraft?.text == text && ChatInputRules.canSend(controller.state, false, text)) { "原输入尚不可发送，请核对保留草稿与原任务" }
                        controller.sendChat(text, key, epoch)
                    }
                }
            }
            "composer.reading" -> controller.saveComposerReading(requiredString(args, "key"), requiredInt(args, "index"), requiredInt(args, "offset"), args.optString("anchor"), args.optString("observed"), args.optBoolean("unread"))
            "composer.intent" -> {
                phoneIntent = requiredBoolean(args, "phone")
                if (args.has("mode")) phoneExecutionMode = requiredString(args, "mode").also { require(it in setOf("background", "main_screen")) }
                phoneReviews.clear(); voiceReviews.clear(); controller.voiceInputTypeChanged(phoneIntent)
            }
            "composer.query", "composer.retry", "composer.returnPending" -> {
                val client = requiredString(args, "clientMessageId")
                require(controller.composerPending.any { it.clientId == client } || controller.state.chatReceipts.containsKey(client)) { "原输入回执身份不可核对" }
                when (action) { "composer.query" -> controller.queryChatSubmission(client); "composer.retry" -> controller.retryLocallyUnsent(client); else -> controller.returnToPendingComposer(client) }
            }
            "conversation.new" -> { leaveTask(); leaveConnection(); reviews.clear(); phoneReviews.clear(); controller.newConversation() }
            "conversation.select" -> { leaveTask(); leaveConnection(); reviews.clear(); phoneReviews.clear(); controller.openConversation(requiredString(args, "id")) }
            "conversation.reader.close" -> controller.closeConversationReader()
            "conversation.reader.earlier" -> controller.loadEarlierConversationReader()
            "conversation.reader.refresh" -> controller.refreshConversationReader()
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
            "voice.start" -> { require(args.optBoolean("phone") == phoneIntent) { "原输入方式已变化" }; controller.startSystemVoiceInput(phoneIntent) }
            "voice.stop" -> controller.stopListening()
            "voice.review" -> {
                val phone = requiredBoolean(args, "phone"); require(phone == phoneIntent) { "原输入方式已变化" }
                val draft = controller.composerDraft
                val current = controller.state.voiceInput
                val live = controller.canReviewVoiceDraft(phone)
                val recovered = !phone && !live && !draft?.voiceText.isNullOrBlank()
                require(live || recovered) { "没有属于原输入的可核对听写文字" }
                val input = if (recovered) draft!!.voiceInputId else current.optString("inputId")
                val text = if (recovered) draft!!.voiceText else current.optString("finalText")
                require(input.isNotBlank() && text.isNotBlank()) { "原听写文字尚未准备" }
                voiceReviews.clear(); val handle = UUID.randomUUID().toString()
                voiceReviews[handle] = VoiceReview(input, phone, recovered, draft?.key ?: "", draft?.revision ?: 0L, controller.composerViewEpoch, phoneDraftRevision, SystemClock.elapsedRealtime() + 120_000)
                return JSONObject().put("reviewHandle", handle).put("inputId", input).put("text", text)
                    .put("phone", phone).put("recovered", recovered).put("expiresInMs", 120000)
            }
            "voice.accept", "voice.discard" -> {
                val reviewed = voiceReviews.remove(requiredString(args, "reviewHandle")) ?: error("原听写审阅已关闭或已使用")
                require(SystemClock.elapsedRealtime() < reviewed.expires && reviewed.phone == phoneIntent && reviewed.epoch == controller.composerViewEpoch) { "原听写输入窗口已变化" }
                val draft = controller.composerDraft
                if (reviewed.phone) require(phoneDraftRevision == reviewed.phoneRevision) { "手机目标草稿已变化，请重新核对听写" }
                else require(draft != null && draft.key == reviewed.key && draft.revision == reviewed.draftRevision) { "原私聊草稿已变化，请重新核对听写" }
                if (reviewed.recovered) {
                    require(draft?.voiceInputId == reviewed.inputId) { "原保留听写已变化" }
                    if (action == "voice.discard") controller.discardRecoveredComposerVoice(reviewed.key, reviewed.draftRevision)
                    else controller.useRecoveredComposerVoice(reviewed.key, reviewed.draftRevision, requiredString(args, "text"))
                } else {
                    require(controller.state.voiceInput.optString("inputId") == reviewed.inputId && controller.canReviewVoiceDraft(reviewed.phone)) { "原听写结果已变化" }
                    if (action == "voice.discard") controller.discardVoiceDraft(reviewed.inputId)
                    else {
                        val accepted = controller.useVoiceDraft(reviewed.inputId, requiredString(args, "text"), reviewed.phone) ?: error("听写文字未被原输入接受")
                        if (reviewed.phone) { phoneDraft = if (phoneDraft.isBlank()) accepted else phoneDraft + "\n" + accepted; phoneDraftRevision++; phoneReviews.clear() }
                        else {
                            val original = controller.composerDraft ?: error("原私聊草稿已关闭")
                            controller.editComposerText(reviewed.key, reviewed.epoch, if (original.text.isBlank()) accepted else original.text + "\n" + accepted)
                        }
                    }
                }
            }
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
                val task = requireTask(requiredString(args, "id"))
                require(canUseTaskControls(task) && !specialOwnerTask(task) && (isPhoneTask(task) || task.raw.optString("kind") == "assistant")) { "请回这项任务的原能力审核窗口操作" }
                controller.stopReviewedTask(task)
            }
            "tasks.confirm", "tasks.resolve", "tasks.resume", "tasks.scope" -> {
                val task = reviews.take(requiredString(args, "reviewHandle"), action, controller.state.tasks)
                require(canUseTaskControls(task)) { "原审核窗口已关闭" }
                when (action) {
                    "tasks.confirm" -> controller.confirmReviewedTask(task, requiredBoolean(args, "completed"), if (isGroup(task)) "" else args.optString("feedback"))
                    "tasks.resolve" -> {
                        val operation = requiredString(args, "operationId"); val outcome = requiredString(args, "outcome")
                        require(outcome in setOf("occurred", "not_occurred"))
                        require(unresolvedForReview(task).any { it.optString("operationId", it.optString("id")) == operation }) { "这项原操作不在审阅中" }
                        controller.resolveReviewedTask(task, operation, outcome)
                    }
                    "tasks.resume" -> { require(requiredBoolean(args, "confirmed")) { "请先核对继续原任务的范围" }; controller.resumeTask(task, if (isGroup(task)) "" else args.optString("feedback")) }
                    else -> controller.configureExecutionScope(task, requiredInt(args, "minutes"), requiredInt(args, "tools"), requiredInt(args, "models"))
                }
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
            "phone.prepare" -> {
                checkedPayload(args, setOf("goal", "mode", "epoch"))
                require(controller.webPrivateOwnerVisible() && requiredInt(args, "epoch") == presentationEpoch) { "原手机输入窗口已变化，请重新核对保留草稿" }
                val goal = requiredString(args, "goal"); val mode = requiredString(args, "mode")
                require(goal.trim().isNotEmpty() && goal.trim().length <= 4000 && mode in setOf("background", "main_screen")) { "请填写 1–4000 字的手机任务目标，并选择执行位置" }
                require(phoneDraft.trim() == goal.trim() && phoneIntent && phoneExecutionMode == mode) { "原手机草稿或执行位置已变化，请重新核对" }
                require(!controller.state.phoneSubmitting && controller.state.activeTaskId == null) { "请先处理当前工作；没有创建第二个手机任务" }
                require(phoneStartReceipt?.optString("status") !in setOf("pending", "unknown") || phoneStartReceipt?.has("requestId") != true && phoneStartReceipt?.optString("goal")?.trim() != goal.trim()) { "原手机请求的启动结果待核对，请先查询原请求。草稿仍保留。" }
                phoneReviews.clear()
                val handle = UUID.randomUUID().toString()
                val conversation = controller.state.conversationId.orEmpty()
                val linked = if (conversation.isNotBlank() && !controller.hasChannelPhoneOrigin) controller.prepareConversationPhoneStart(conversation, goal, mode) else null
                val shown = linked?.view()
                val review = PhoneReview(goal, mode, shown?.getInt("toolLimit") ?: controller.newTaskToolBudget,
                    shown?.getInt("modelCallLimit") ?: controller.newTaskModelBudget, shown?.getInt("scopeMinutes") ?: controller.newTaskScopeMinutes,
                    controller.composerViewEpoch, SystemClock.elapsedRealtime() + 120_000L, captureWindow(),
                    controller.composerDraft?.key.orEmpty(), conversation, presentationEpoch, phoneDraftRevision, linked)
                phoneReviews[handle] = review
                return (shown ?: JSONObject()).put("reviewHandle", handle).put("goal", goal).put("mode", mode)
                    .put("toolLimit", review.tools).put("modelCallLimit", review.models).put("scopeMinutes", review.minutes)
                    .put("expiresInMs", 120000).put("screenDisclosure", shown?.optString("screenDisclosure") ?: "执行时，屏幕与可见文字会发送到本次配置的模型。")
                    .put("message", if (mode == "background") "在后台独立屏处理这个目标；连接不可用时等待，不切换到手机主屏。" else "在手机主屏处理这个目标，会使用当前屏幕。")
            }
            "phone.draft.edit" -> {
                require(requiredInt(args, "epoch") == presentationEpoch) { "原手机输入窗口已变化，草稿仍保留" }
                val text = requiredString(args, "text", true); require(text.length <= 12000)
                phoneDraft = text; phoneDraftRevision++; phoneReviews.clear()
            }
            "phone.deadline" -> controller.selectNewTaskDeadline(requiredInt(args, "minutes"))
            "background.refresh" -> controller.refreshBackgroundSetup()
            "background.discover" -> {
                require(controller.state.activeTaskId == null && !controller.state.feature("display").optBoolean("taskOwned")) { "原手机任务仍占用执行环境，未重新发现连接" }
                controller.display("adb.discover")
            }
            "background.cancel" -> controller.display("adb.cancel")
            "background.close" -> controller.closeBackgroundSetup()
            "background.pair" -> controller.pairWireless(requiredString(args, "host"), requiredInt(args, "port"), requiredString(args, if (args.has("pairingCode")) "pairingCode" else "code"))
            "background.connect" -> controller.connectWireless(requiredString(args, "host"), requiredInt(args, "port"), if (args.has("pairingPort")) requiredInt(args, "pairingPort") else null)
            "background.mainScreen", "background.selectMainScreen" -> controller.selectMainScreenCompatibility()
            "queued.open" -> controller.openQueuedOwnerInputs(requiredString(args, "taskId"), requiredString(args, "conversationId"))
            "queued.refresh" -> controller.refreshQueuedOwnerInputs()
            "queued.close" -> controller.closeQueuedOwnerInputs()
            "queued.edit" -> controller.openQueuedOwnerInputEditor(requiredString(args, "messageId"), args.optBoolean("withdrawn"))
            "queued.change" -> { if (args.has("text")) controller.editQueuedOwnerInputText(requiredString(args, "text", true)); if (args.has("mode")) controller.editQueuedOwnerInputMode(requiredString(args, "mode")) }
            "queued.save" -> controller.saveQueuedOwnerInput()
            "queued.editor.close" -> controller.closeQueuedOwnerInputEditor()
            "accounts.openAuthorization" -> { requireSection("accounts"); controller.openAccountAuthorization(requiredString(args, "sessionId")) }
            "accounts.manageUsage" -> { requireSection("accounts"); checkedPayload(args, emptySet()); controller.openChatGptUsage() }
            "memory.list", "memory.search" -> { requireSection("memory"); return accepted(controller.requestFeature("memory", action.substringAfter('.'), checkedPayload(args, setOf("query", "cursor", "layer", "limit")))) }
            "memoryReview.list", "memoryReview.candidates", "memoryReview.getCandidate", "memoryReview.capacity", "memoryReview.history", "memoryReview.openSource" -> {
                requireSection("memoryReview")
                val command = when (action) { "memoryReview.candidates" -> "listCandidates"; "memoryReview.capacity" -> "candidateCapacity"; "memoryReview.history" -> "listCandidateHistory"; else -> action.substringAfter('.') }
                return accepted(controller.requestOwnerDomain("memoryReview", command, checkedPayload(args, setOf("id", "revision", "cursor", "sourceRef", "layer"))))
            }
            "mcp.snapshot" -> { requireSection("mcp"); return accepted(controller.requestOwnerDomain("mcp", "snapshot")) }
            "mcp.pickConfig" -> { requireSection("mcp"); return accepted(controller.pickMcpConfig()) }
            "mcp.cancel" -> { requireSection("mcp"); return accepted(controller.requestOwnerDomain("mcp", "cancel", checkedPayload(args, setOf("operationId")))) }
            "mcp.discoverImport" -> { requireSection("mcp"); checkedPayload(args, setOf("importRequestId")); return mcpImports.discover(requiredString(args, "importRequestId")) }
            "mcp.reviewImport" -> { requireSection("mcp"); checkedPayload(args, emptySet()); return mcpImports.review() }
            "mcp.installImport" -> { requireSection("mcp"); checkedPayload(args, setOf("reviewHandle", "tools")); return mcpImports.install(requiredString(args, "reviewHandle"), args.getJSONArray("tools")) }
            "mcp.reviewEnabled" -> { requireSection("mcp"); checkedPayload(args, setOf("id", "installationId", "enabled")); return mcpImports.reviewEnabled(requiredString(args, "id"), requiredString(args, "installationId"), requiredBoolean(args, "enabled")) }
            "mcp.confirmEnabled" -> { requireSection("mcp"); checkedPayload(args, setOf("reviewHandle")); return mcpImports.confirmEnabled(requiredString(args, "reviewHandle")) }
            "mcp.read" -> { requireSection("mcp"); checkedPayload(args, setOf("id", "installationId")); return PocketWebState.publicValue(mcpImports.read(requiredString(args, "id"), requiredString(args, "installationId"))) as JSONObject }
            "mcp.reviewGrant" -> { requireSection("mcp"); checkedPayload(args, setOf("id", "installationId", "grantsDigest", "tool", "granted")); return PocketWebState.publicValue(mcpImports.reviewGrant(requiredString(args, "id"), requiredString(args, "installationId"), requiredString(args, "grantsDigest"), requiredString(args, "tool"), requiredBoolean(args, "granted"))) as JSONObject }
            "mcp.confirmGrant" -> { requireSection("mcp"); checkedPayload(args, setOf("reviewHandle")); return mcpImports.confirmGrant(requiredString(args, "reviewHandle")) }
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
            "automation.receipts" -> { require(section in setOf("automation", "commitments")) { "原提醒窗口已关闭" }; checkedPayload(args, emptySet()); return accepted(controller.requestFeature("automation", "receipts")) }
            "automation.list" -> { requireSection("automation"); checkedPayload(args, emptySet()); return accepted(controller.requestFeature("automation", "list")) }
            "automation.reviewSchedule", "automation.reviewRule" -> {
                requireSection("automation")
                require(controller.state.moduleBusy.none { it in setOf("automation.save", "automation.setEnabled", "automation.delete", "automation.list") }) { "请先等待原提醒操作回执" }
                require(controller.state.moduleErrors["automation.list"] == null) { "原提醒清单未读成功，请刷新后核对" }
                val rules = controller.state.feature("automation").optJSONArray("rules") ?: error("请先读取提醒清单")
                return if (action == "automation.reviewSchedule") reminderReviews.reviewSchedule(args, rules) else reminderReviews.reviewRule(args, rules)
            }
            "automation.confirmReview" -> {
                requireSection("automation"); checkedPayload(args, setOf("reviewHandle"))
                require(controller.state.moduleBusy.none { it in setOf("automation.save", "automation.setEnabled", "automation.delete", "automation.list") }) { "请先等待原提醒操作回执" }
                require(controller.state.moduleErrors["automation.list"] == null) { "原提醒清单未读成功，请刷新后核对" }
                val reviewed = reminderReviews.consume(requiredString(args, "reviewHandle"), controller.state.feature("automation").optJSONArray("rules") ?: error("原提醒清单不可用"))
                val command = reviewed.getString("command")
                return accepted(controller.requestFeature("automation", command, reviewed.getJSONObject("input"))).put("command", command)
            }
            "automation.prepareOccurrence" -> {
                requireSection("automation"); checkedPayload(args, setOf("occurrenceId"))
                require("automation.receipts" !in controller.state.moduleBusy) { "请先等待原发生记录读取" }
                val id = requiredString(args, "occurrenceId")
                val receipts = controller.state.moduleResults["automation.receipts"]?.optJSONArray("receipts") ?: error("请先读取原发生记录")
                val receipt = (0 until receipts.length()).mapNotNull { receipts.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
                require(receipt != null && receipt.optString("kind") == "phone_goal" && receipt.optString("status") in setOf("awaiting_user", "blocked_notifications")) { "原发生状态已变化，请刷新核对" }
                controller.prepareReminderOccurrence(id)
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
        val testing = action == "models.connection.test"
        checkedPayload(args, setOf("id", "expectedRevision", "name", "url", "model", "apiKey", "bedrockRegion", "awsSession", "screenshots", "supportsImageInput", "provider", "maxOutputTokens", "cleartextConsent", "compatibility") +
            if (testing) setOf("editorId", "draftRevision") else emptySet())
        val adding = action == "models.connection.add" || action == "models.connection.test" && args.optString("id").isEmpty()
        val id = if (adding) "" else requiredString(args, "id")
        val expected = if (adding) 0L else requiredInt(args, "expectedRevision").also { require(it > 0) { "请重新读取原连接修订" } }.toLong()
        val completed: (Result<JSONObject>) -> Unit = { result ->
            if (!closed && action != "models.connection.test") {
                domainSnapshots.remove("models")
                // A mutation result proves that operation, not the currently selected model.
                readModelSnapshot()
            }
            result.fold({ snapshot ->
                complete(requestId, JSONObject().put("status", if (action == "models.connection.test") "tested" else "saved").put("snapshot", PocketWebState.publicValue(snapshot)), null)
            }, { error -> complete(requestId, null, "原连接操作结果待核对：" + (error.message ?: "请刷新原连接记录"), "outcome_unknown") })
            publishState()
        }
        if (action == "models.connection.select") {
            val service = controller.nativeModelConnections() ?: error("本机模型连接尚未准备")
            controller.runModelSelectionWrite({ requireModelSelectionIdle(ownMutation = true); service.selectConnection(id, expected) }, completed)
            return
        }
        val input = JSONObject().put("baseUrl", requiredString(args, "url")).put("model", requiredString(args, "model"))
            .put("apiKey", args.optString("apiKey")).put("sendScreenshots", requiredBoolean(args, "screenshots"))
            .put("supportsImageInput", requiredBoolean(args, "supportsImageInput")).put("provider", requiredString(args, "provider"))
            .put("maxOutputTokens", requiredInt(args, "maxOutputTokens")).put("cleartextConsent", args.optString("cleartextConsent"))
        if (args.has("bedrockRegion")) input.put("bedrockRegion", requiredString(args, "bedrockRegion"))
        if (args.has("awsSession")) input.put("awsSession", checkedPayload(args.getJSONObject("awsSession"), setOf("accessKeyId", "secretAccessKey", "sessionToken", "expiresAt")))
        if (args.has("compatibility")) input.put("compatibility", if (args.isNull("compatibility")) JSONObject.NULL else checkedProviderCompatibility(args.getJSONObject("compatibility")))
        if (testing) {
            val editorId = requiredString(args, "editorId")
            val draftRevision = requiredInt(args, "draftRevision").also { require(it >= 0) { "草稿修订无效，请重新打开连接" } }.toLong()
            val generation = domainGeneration; val document = documentId; val window = captureWindow()
            controller.testApiConnection(id, expected, input, editorId, draftRevision, {
                !closed && section == "models" && document != null && document == documentId && generation == domainGeneration &&
                    acceptsWindow(window) && controller.webPrivateOwnerVisible()
            }, completed)
        } else controller.saveApiConnection(adding, id, expected, args.optString("name", input.getString("model")), input, completed)
    }

    private fun checkedProviderCompatibility(options: JSONObject): JSONObject {
        val values = mapOf(
            "tokenField" to setOf("NATIVE", "MAX_TOKENS", "MAX_COMPLETION_TOKENS"),
            "streamUsage" to setOf("NATIVE", "OMIT"), "toolWireShape" to setOf("NATIVE", "OPENAI_FUNCTION", "ANTHROPIC_INPUT_SCHEMA"),
            "schemaSubset" to setOf("NATIVE", "CORE_JSON"), "cacheMode" to setOf("OFF", "EPHEMERAL"),
            "cacheSupport" to setOf("UNKNOWN", "SUPPORTED", "UNSUPPORTED"))
        val checked = checkedPayload(options, values.keys + setOf("maxTools", "maxRequestBytes"))
        for ((key, allowed) in values) if (checked.has(key)) require(requiredString(checked, key) in allowed) { "兼容或缓存选项不在当前支持范围" }
        if (checked.has("maxTools")) require(requiredInt(checked, "maxTools") in 1..512) { "工具数量上限应为 1–512" }
        if (checked.has("maxRequestBytes")) require(requiredInt(checked, "maxRequestBytes") in 256..25_165_824) { "请求大小上限应为 256–25165824 字节" }
        return checked
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

    private fun modelRow(snapshot: JSONObject, model: String): JSONObject = snapshot.optJSONArray("models").objects()
        .firstOrNull { it.optString("modelId") == model } ?: error("原模型已不在当前目录，请刷新后重新核对")

    private fun modelIdentity(snapshot: JSONObject, model: String): String = digest(JSONObject()
        .put("binding", snapshot.getJSONObject("binding")).put("api", snapshot.getJSONObject("api"))
        .put("activeRoute", snapshot.optString("activeRoute")).put("reasoning", snapshot.getJSONObject("reasoning"))
        .put("imageTokenReserve", snapshot.optInt("imageTokenReserve")).put("row", modelRow(snapshot, model)).toString())

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
                    val binding = ModelBinding.fromJson(snapshot.getJSONObject("binding"))
                    val sameModel = snapshot.getJSONObject("api").optString("model") == model
                    val reasoning = if (sameModel) ReasoningOptions.fromJson(snapshot.getJSONObject("reasoning")) else ReasoningOptions.DEFAULT
                    val review = ModelReview(binding, model, modelIdentity(snapshot, model), snapshot.getJSONObject("api").optInt("maxOutputTokens", 4096), reasoning,
                        if (sameModel) snapshot.optInt("imageTokenReserve") else 0, generation, document, SystemClock.elapsedRealtime() + 120_000L, selection.revision())
                    modelReviews.clear(); val handle = UUID.randomUUID().toString(); modelReviews[handle] = review
                    domainSnapshots["models"] = JSONObject(snapshot.toString()); domainErrors.remove("models")
                    complete(requestId, JSONObject().put("reviewHandle", handle).put("modelId", model).put("outputTokens", review.output)
                        .put("reasoning", reasoning.toJson()).put("imageTokenReserve", review.imageReserve).put("expiresInMs", 120000), null)
                } catch (failure: Exception) { complete(requestId, null, failure.message ?: "原模型选择暂不可核对") }
                publishState()
            }
        })
    }

    private fun selectModel(requestId: String, args: JSONObject) {
        val review = modelReviews.remove(requiredString(args, "reviewHandle")) ?: error("原模型审阅已关闭或已使用，请重新选择")
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
                    check(ModelBinding.fromJson(current.getJSONObject("binding")) == review.binding && modelIdentity(current, review.model) == review.identity) { "原连接、模型能力或选择参数已变化，请刷新后重新核对" }
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

    private fun reviewTask(task: UiTask, approvalViews: JSONArray): JSONObject {
        val allowed = linkedMapOf<String, String>()
        val special = specialOwnerTask(task)
        val unknown = unresolvedForReview(task)
        val approvals = pendingCapabilityApprovals(task)
        // Derive this view from the original private native task, never Web-provided identity.
        val question = PocketWebOwnerQuestions.view(task.raw)
        val screenshot = task.raw.optJSONObject("evidence")?.optJSONObject("screenshot")
        if (task.raw.optString("actorId") == "owner" && task.raw.optString("spaceId") == "private" &&
            task.raw.optJSONObject("origin")?.optString("kind") !in setOf("group", "paired_device") &&
            screenshot?.optString("imageId")?.isNotBlank() == true && screenshot.optString("state") != "deleted") allowed["tasks.deleteScreenshot"] = "删除这张本机截图"
        if (canUseTaskControls(task) && !special) {
            PocketWebOwnerQuestions.actions(question).forEach { allowed[it] = if (it == PocketWebOwnerQuestions.CHOOSE) "选择原问题答案" else "回答原问题" }
            if (task.status == "needs_confirmation" && task.id != controller.state.activeTaskId) allowed["tasks.confirm"] = "核对结果"
            if (approvals.isNotEmpty()) allowed["tasks.approve"] = "审阅操作授权"
            if (unknown.any { it.optString("operationId", it.optString("id")).isNotBlank() }) allowed["tasks.resolve"] = "核对原操作"
            if (!PocketWebOwnerQuestions.blocksGenericResume(task.raw) && (isPhoneTask(task) || task.raw.optString("kind") == "assistant") && task.id != controller.state.activeTaskId && task.status in setOf("paused", "awaiting_user", "needs_confirmation", "interrupted") && !task.raw.optBoolean("cancelRequested") && unknown.isEmpty() && approvals.isEmpty()) allowed["tasks.resume"] = "确认继续原任务"
            if (!isGroup(task) && task.raw.optJSONObject("executionScope") != null && task.status !in setOf("completed", "cancelled", "canceled", "failed", "interrupted", "reconciling")) allowed["tasks.scope"] = "调整原任务范围"
            if (PocketWebApprovals.ordinaryPlanningTask(task) && task.raw.getJSONObject("executionScope").optString("executionPhase", "executing") == "executing" &&
                task.status in setOf("queued", "paused", "awaiting_user", "needs_confirmation") && unknown.isEmpty() && approvals.isEmpty()) {
                allowed["tasks.plan.enter"] = "进入只读规划"
            }
        }
        val positiveApprovalIds = approvalViews.objects().filter { it.optJSONObject("webReview")?.optBoolean("canApprove") == true }
            .map { it.optString("approvalId", it.optString("id")) }.filter { it.isNotBlank() }.toSet()
        val handle = reviews.create(task, allowed.keys, positiveApprovalIds)
        return JSONObject().put("reviewHandle", handle).put("expiresInMs", 120_000)
            .put("task", PocketWebState.task(task, images = true))
            .put("ownerQuestion", question ?: JSONObject.NULL)
            .put("actions", JSONArray().also { out -> allowed.forEach { (action, label) -> out.put(JSONObject().put("action", action).put("label", label)) } })
            .put("approvals", PocketWebState.publicValue(approvalViews))
            .put("unresolvedOperations", PocketWebState.publicValue(JSONArray(unknown)))
    }

    private fun unresolvedForReview(task: UiTask): List<JSONObject> = unresolvedOperations(task).filterNot { it.optString("tool") in setOf("device_invoke_tool", "device_delegate_goal", "device_operation_get") }
    private fun specialOwnerTask(task: UiTask) = setOf("ownerLocalTerminal", "ownerBackupUpload", "ownerStorageCleanup", "ownerArchivePhase", "ownerGit", "ownerGitImport", "ownerGitPublication", "ownerMcpDiscovery").any(task.raw::has) || MediaOwnerUi.isTask(task.raw) || task.raw.optJSONObject("sourceLearningJob") != null || task.raw.optJSONObject("nativeEventTypeFlags")?.optBoolean("memory_owner_consolidation") == true || task.raw.optJSONArray("events").objects().any { it.optString("type") == "memory_owner_consolidation" }
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
    private fun leaveTask() { tokenBudgets.clear(); profilePages.clear(); composerModels.clear(); taskViewId = null; controller.closeWebTaskView(); inference.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); experience.clear(); routeReferences.clear(); commandFence.invalidate(); taskReviewGeneration++; taskPage?.let(controller::leaveGroupTask); taskPage = null }
    private fun leaveConnection() { controller.webChannels.clear(); controller.webSkills.clear(); commandFence.invalidate(); ++domainGeneration; modelReviews.clear(); reminderReviews.clear(); mcpImports.clear(); executionReviews.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); experience.clear(); routeReferences.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); devicePages.clear(); pluginPages.clear(); localPages.clear(); accountWorkspace.invalidate(); composerModels.clear(); deviceWindow?.close(); deviceWindow = null; section?.let { if (it == "memory") controller.leaveOwnerDomain("memoryReview") else if (it in ownerDomains) controller.leaveOwnerDomain(it) }; section?.takeIf { it == "archives" || it == "storage" }?.let(dataPages::close); section = null }
    private fun requireSection(expected: String) { require(section == expected) { "原能力窗口已关闭，请重新进入" } }
    private fun requireComposer(args: JSONObject, optional: Boolean = false) {
        val draft = controller.composerDraft ?: error("原输入尚未恢复")
        if (!optional || args.has("key") || args.has("epoch")) require(requiredString(args, "key") == draft.key && requiredInt(args, "epoch") == controller.composerViewEpoch) { "原输入窗口已变化" }
    }
    private fun requireFile(args: JSONObject) {
        val state = controller.state
        require(state.projectId == requiredString(args, "projectId") && state.filePath == checkedPath(args) && state.fileVersion == requiredInt(args, "version")) { "原项目文件或版本已变化，编辑草稿仍保留，请重新核对" }
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
    private fun requiredBoolean(args: JSONObject, name: String): Boolean {
        val value = args.opt(name); require(value is Boolean) { "缺少有效开关参数 $name" }; return value
    }
}
