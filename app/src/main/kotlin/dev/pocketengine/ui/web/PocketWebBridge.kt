package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import dev.pocketengine.core.ModelConfig
import dev.pocketengine.features.devices.NativeDeviceWorkspaceHost
import dev.pocketengine.runtime.models.ModelBinding
import dev.pocketengine.runtime.models.ReasoningOptions
import dev.pocketengine.ui.ChatInputRules
import dev.pocketengine.ui.MediaOwnerUi
import dev.pocketengine.ui.PocketWorldController
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
    private data class Reviewed(val task: UiTask, val identity: String, val actions: Set<String>, val expires: Long)
    private val entries = linkedMapOf<String, Reviewed>()

    fun create(task: UiTask, actions: Set<String>): String {
        entries.entries.removeAll { it.value.expires <= now() }
        while (entries.size >= 24) entries.remove(entries.keys.first())
        val copy = task.copy(raw = JSONObject(task.raw.toString()))
        val handle = UUID.randomUUID().toString()
        entries[handle] = Reviewed(copy, identity(copy), actions.toSet(), now() + 120_000L)
        return handle
    }

    fun take(handle: String, action: String, current: List<UiTask>): UiTask {
        val reviewed = entries.remove(handle) ?: throw IllegalStateException("原审阅已关闭或已使用，请重新核对原任务")
        check(now() < reviewed.expires) { "原审阅已过期，请重新核对原任务" }
        check(action in reviewed.actions) { "这项操作不在原审阅范围内" }
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
    private val applyScheduled = AtomicBoolean(false)
    private val observer = SnapshotStateObserver { callback -> main.post { if (started && !closed) callback() } }
    private var releaseGlobalObserver: (() -> Unit)? = null
    private var started = false
    @Volatile private var closed = false
    private var revision = 0L
    private var lastState: String? = null
    private var section: String? = null
    private var taskPage: String? = null
    private val reviews = PocketWebTaskReviews()
    private val commandReceipts = linkedMapOf<String, Pair<String, JSONObject>>()
    private val pendingCommands = mutableMapOf<String, String>()
    private data class PhoneReview(val goal: String, val mode: String, val tools: Int, val models: Int, val minutes: Int, val viewEpoch: Int, val expires: Long)
    private val phoneReviews = mutableMapOf<String, PhoneReview>()
    @Volatile private var documentId: String? = null
    private var presentationEpoch = 0
    private var phoneDraft = ""
    private var phoneDraftRevision = 0L
    private var phoneIntent = false
    private var phoneExecutionMode = "background"
    private var acceptedPhoneRevision = 0L
    private data class VoiceReview(val inputId: String, val phone: Boolean, val recovered: Boolean, val key: String, val draftRevision: Long, val epoch: Int, val phoneRevision: Long, val expires: Long)
    private val voiceReviews = mutableMapOf<String, VoiceReview>()
    private var deviceWindow: NativeDeviceWorkspaceHost.Window? = null
    private val domainSnapshots = mutableMapOf<String, JSONObject>()
    private val domainErrors = mutableMapOf<String, String>()
    @Volatile private var domainGeneration = 0L
    private data class ModelReview(val binding: ModelBinding, val model: String, val identity: String, val output: Int, val reasoning: ReasoningOptions, val imageReserve: Int, val generation: Long, val document: String?, val expires: Long)
    private val modelReviews = mutableMapOf<String, ModelReview>()
    @Volatile private var modelSelectionBusy = false

    companion object {
        private val ownerDomains = setOf("memoryReview", "taskMemory", "mcp", "accounts", "learning", "groups", "channelMedia")
        private val connectionSections = setOf("devices", "models", "accounts", "remote", "ssh", "local", "memory", "memoryReview", "automation", "commitments", "channels", "skills", "learning", "plugins", "pluginCatalog", "mcp", "teams", "groups", "media", "realtime", "proxy")
        val commands: Set<String> = linkedSetOf(
            "app.ready", "app.refresh", "notice.dismiss", "error.dismiss",
            "composer.edit", "composer.send", "composer.mode", "composer.intent", "composer.reading", "composer.query", "composer.retry", "composer.returnPending",
            "chat.cancel", "conversation.new", "conversation.select", "conversation.reader.close", "conversation.reader.earlier", "conversation.reader.refresh",
            "attachments.pick", "attachments.openPicker", "attachments.closePicker", "attachments.toggle", "attachments.remove",
            "voice.start", "voice.stop", "voice.review", "voice.accept", "voice.discard", "speech.speak", "speech.stop",
            "tasks.refresh", "tasks.open", "tasks.close", "tasks.stop", "tasks.review", "tasks.confirm", "tasks.approve", "tasks.resolve", "tasks.resume", "tasks.scope",
            "projects.refresh", "projects.select", "projects.create", "projects.open", "projects.newFile", "projects.edit", "projects.save", "projects.close", "projects.history", "projects.revision", "projects.useRevision", "projects.retry",
            "settings.saveModel", "settings.testModel", "settings.reviewModelTransport", "settings.useApiKeyModel", "settings.saveLocalObservation", "settings.openSystem",
            "connections.enter", "connections.leave", "models.snapshot", "models.review", "models.select", "devices.snapshot", "history.read", "usage.read", "usage.close", "usage.reestimate",
            "phone.prepare", "phone.start", "phone.draft.edit", "phone.pause", "phone.resume", "phone.stop", "phone.deadline", "background.refresh", "background.discover", "background.cancel", "background.close", "background.pair", "background.connect", "background.mainScreen", "background.selectMainScreen",
            "queued.open", "queued.refresh", "queued.close", "queued.edit", "queued.change", "queued.save", "queued.editor.close",
            "accounts.beginLogin", "accounts.cancelLogin", "accounts.openAuthorization", "accounts.select", "accounts.refresh", "accounts.models", "accounts.retryPlanUsage",
            "memory.list", "memory.search", "memoryReview.list", "memoryReview.getCandidate", "memoryReview.capacity", "memoryReview.history", "memoryReview.openSource",
            "memoryReview.candidates", "mcp.snapshot", "mcp.pickConfig", "mcp.cancel", "learning.snapshot", "groups.snapshot",
            "remote.probe", "remote.environments", "local.termuxProbe", "automation.receipts", "channels.inspect", "skills.read", "plugins.history", "proxy.status", "proxy.stop"
        )
    }

    /** Equivalent to the former Activity saveable presentation state, never an execution grant. */
    fun savePresentationState(): JSONObject = JSONObject().put("phoneDraft", phoneDraft)
        .put("phoneDraftRevision", phoneDraftRevision).put("phoneIntent", phoneIntent).put("phoneExecutionMode", phoneExecutionMode)

    fun restorePresentationState(saved: JSONObject?) {
        check(!started) { "Restore presentation before the Web document starts" }
        if (saved == null) return
        val text = saved.optString("phoneDraft")
        if (text.length <= 12000) phoneDraft = text
        phoneDraftRevision = saved.optLong("phoneDraftRevision").coerceAtLeast(0)
        phoneIntent = saved.optBoolean("phoneIntent")
        phoneExecutionMode = saved.optString("phoneExecutionMode").takeIf { it in setOf("background", "main_screen") } ?: "background"
    }

    fun start() {
        if (closed || started) return
        check(Looper.myLooper() == Looper.getMainLooper())
        started = true
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

    fun close() {
        if (closed) return
        closed = true
        started = false
        releaseGlobalObserver?.invoke(); releaseGlobalObserver = null
        observer.stop(); observer.clear()
        reviews.clear()
        phoneReviews.clear()
        voiceReviews.clear()
        modelReviews.clear()
        section?.takeIf { it in ownerDomains }?.let(controller::leaveOwnerDomain)
        deviceWindow?.close(); deviceWindow = null
        section = null
        taskPage?.let(controller::leaveGroupTask); taskPage = null
        controller.closeNativeInferenceHistory()
        controller.closeQueuedOwnerInputs()
        controller.persistOwnerComposer()
        pendingCommands.clear(); commandReceipts.clear()
    }

    @JavascriptInterface
    fun postMessage(raw: String) {
        if (closed) return
        // The existing workspace backend remains the authority on individual file size limits.
        if (raw.length > 16 * 1024 * 1024) return
        main.post { if (!closed && started) process(raw) }
    }

    private fun publishState(force: Boolean = false) {
        if (closed || !started) return
        var projection: JSONObject? = null
        observer.observeReads(this, { _: PocketWebBridge -> publishState() }) {
            projection = PocketWebState.snapshot(controller)
            val accepted = controller.phoneDraftAcceptedRevision
            if (accepted > acceptedPhoneRevision) {
                acceptedPhoneRevision = accepted
                if (phoneDraft.trim() == controller.phoneDraftAcceptedText) { phoneDraft = ""; phoneDraftRevision++ }
            }
        }
        val snapshot = projection ?: return
        val projectedState = snapshot.getJSONObject("state")
        projectedState.put("modelSelectionBusy", modelSelectionBusy)
        domainSnapshots.forEach { (domain, value) ->
            val public = PocketWebState.publicValue(value)
            projectedState.getJSONObject("ownerSnapshots").put(domain, public)
            projectedState.getJSONObject("features").put(domain, public)
            if (domain == "models") projectedState.put("modelConnections", public)
            if (domain == "devices") projectedState.put("deviceWorkspace", public)
        }
        domainErrors.forEach { (domain, error) -> projectedState.getJSONObject("ownerErrors").put(domain, error) }
        snapshot.put("capabilities", JSONObject().put("commands", JSONArray(commands.toList())))
            .put("view", JSONObject().put("section", section ?: JSONObject.NULL))
            .put("phoneDraft", JSONObject().put("text", phoneDraft).put("revision", phoneDraftRevision)
                .put("epoch", presentationEpoch).put("phone", phoneIntent).put("mode", phoneExecutionMode))
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
            require(action == "app.ready" || controller.webUiCanInteract()) { "原界面已不在前台，未继续这项操作" }
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
            if (action == "history.read") {
                val taskId = args.optString("taskId").takeIf { it.isNotBlank() }
                controller.readTaskAudit(taskId, args.optString("section", "events"), args.optString("cursor")) { result, error -> complete(id, result, error) }
                return
            }
            if (action == "usage.read") {
                controller.openNativeInferenceHistory()
                controller.readNativeInferenceHistory(args.optString("cursor")) { result, error -> complete(id, result, error) }
                return
            }
            if (action == "usage.reestimate") {
                controller.reestimateInference(requiredString(args, "taskId"), requiredString(args, "callId")) { result, error -> complete(id, result, error) }
                return
            }
            if (action == "models.snapshot") { readModelSnapshot(id); return }
            if (action == "models.review") { reviewModel(id, requiredString(args, "modelId")); return }
            if (action == "models.select") { selectModel(id, args); return }
            if (action == "devices.snapshot") { requireSection("devices"); readDeviceSnapshot(id); return }
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

    private fun complete(id: String, value: JSONObject?, error: String?) {
        if (closed) return
        val identity = pendingCommands.remove(id) ?: return
        val response = JSONObject().put("v", 1).put("kind", "result").put("id", id).put("ok", error == null)
        if (error == null) response.put("value", value ?: JSONObject()) else response.put("error", JSONObject().put("code", "not_accepted").put("message", error))
        commandReceipts[id] = identity to response
        while (commandReceipts.size > 128) commandReceipts.remove(commandReceipts.keys.first())
        emit(response)
    }

    private fun dispatch(action: String, args: JSONObject): JSONObject? {
        when (action) {
            "app.ready" -> {
                val nextDocument = requiredString(args, "documentId")
                require(nextDocument.length <= 120)
                if (nextDocument != documentId) {
                    documentId = nextDocument; presentationEpoch++; reviews.clear(); phoneReviews.clear(); voiceReviews.clear(); modelReviews.clear()
                    controller.voiceInputTypeChanged(phoneIntent)
                }
                return JSONObject().put("ready", true).put("source", "library-web")
            }
            "app.refresh", "tasks.refresh" -> controller.refresh()
            "notice.dismiss" -> controller.dismissNotice()
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
            "chat.cancel" -> controller.cancelChat()
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
            "tasks.open" -> {
                val task = requireTask(requiredString(args, "id")); leaveTask(); leaveConnection(); reviews.clear()
                if (task.raw.optJSONObject("origin")?.optString("kind") == "group") taskPage = controller.enterGroupTask(task.id)
                controller.focusTask(task.id)
            }
            "tasks.close" -> { leaveTask(); reviews.clear() }
            "tasks.stop" -> {
                val task = requireTask(requiredString(args, "id"))
                require(canUseTaskControls(task) && !specialOwnerTask(task) && (isPhoneTask(task) || task.raw.optString("kind") == "assistant")) { "请回这项任务的原能力审核窗口操作" }
                controller.stopReviewedTask(task)
            }
            "tasks.review" -> return reviewTask(requireTask(requiredString(args, "id")))
            "tasks.confirm", "tasks.approve", "tasks.resolve", "tasks.resume", "tasks.scope" -> {
                val task = reviews.take(requiredString(args, "reviewHandle"), action, controller.state.tasks)
                require(canUseTaskControls(task)) { "原审核窗口已关闭" }
                when (action) {
                    "tasks.confirm" -> controller.confirmReviewedTask(task, requiredBoolean(args, "completed"), if (isGroup(task)) "" else args.optString("feedback"))
                    "tasks.approve" -> {
                        val approval = requiredString(args, "approvalId")
                        require(pendingCapabilityApprovals(task).any { it.optString("approvalId", it.optString("id")) == approval }) { "这项授权不在原审阅中" }
                        controller.approveCapability(task, approval, requiredBoolean(args, "approved"))
                    }
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
            "settings.saveModel", "settings.testModel" -> {
                require(!controller.state.modelSaving && !controller.state.modelTesting) { "请等待原模型设置请求的回执" }
                val url = requiredString(args, "url"); val model = requiredString(args, "model")
                val key = args.optString("apiKey"); val screenshots = requiredBoolean(args, "screenshots")
                val provider = args.optString("provider", controller.state.modelProvider)
                val tokens = if (args.has("maxOutputTokens")) requiredInt(args, "maxOutputTokens") else controller.state.maxOutputTokens
                val consent = args.optString("cleartextConsent", controller.state.cleartextConsent)
                if (action == "settings.saveModel") controller.saveModel(url, model, key, screenshots, provider, tokens, consent, args.optBoolean("supportsImageInput"))
                else controller.testModel(url, model, key, screenshots, provider, tokens, consent)
            }
            "settings.useApiKeyModel" -> controller.useApiKeyModel()
            "settings.saveLocalObservation" -> controller.saveLocalObservation(requiredBoolean(args, "capture"), requiredBoolean(args, "ocr"))
            "settings.openSystem" -> when (requiredString(args, "section")) {
                "accessibility" -> controller.openAccessibility(); "notifications" -> controller.openNotifications()
                "wireless" -> controller.openWirelessSettings(); "background" -> controller.openBackgroundSetup()
                else -> error("没有已接入的系统设置入口")
            }
            "connections.enter" -> {
                val next = requiredString(args, "section"); require(next in connectionSections) { "未知能力页面" }
                leaveTask(); leaveConnection(); reviews.clear(); section = next
                // The Web page already owns its route. Prepare the host entry here.
                controller.prepareConnectionEntry()
                if (next in ownerDomains) controller.enterOwnerDomain(next) else controller.refresh()
                if (next == "models") readModelSnapshot()
                when (next) {
                    "memory" -> controller.requestFeature("memory", "list")
                    "automation", "commitments" -> controller.requestFeature("automation", "receipts")
                    "channels" -> controller.requestFeature("channels", "inspect")
                }
                if (next == "devices") {
                    deviceWindow = (controller.nativeDeviceWorkspace() ?: error("本机设备工作区尚未准备")).openOwnerWindow()
                    readDeviceSnapshot()
                }
            }
            "connections.leave" -> { leaveConnection(); reviews.clear() }
            "usage.close" -> controller.closeNativeInferenceHistory()
            "phone.prepare" -> {
                val goal = requiredString(args, "goal"); val mode = requiredString(args, "mode")
                require(goal.trim().length <= 12000 && mode in setOf("background", "main_screen"))
                require(!controller.state.chatBusy && !controller.state.phoneSubmitting && controller.state.activeTaskId == null) { "请先处理当前工作；没有创建第二个手机任务" }
                phoneReviews.clear()
                val handle = UUID.randomUUID().toString()
                val review = PhoneReview(goal, mode, controller.newTaskToolBudget, controller.newTaskModelBudget, controller.newTaskScopeMinutes, controller.composerViewEpoch, SystemClock.elapsedRealtime() + 120_000L)
                phoneReviews[handle] = review
                return JSONObject().put("reviewHandle", handle).put("goal", goal).put("mode", mode)
                    .put("toolLimit", review.tools).put("modelCallLimit", review.models).put("scopeMinutes", review.minutes)
                    .put("expiresInMs", 120000).put("message", if (mode == "background") "在后台独立屏处理这个目标；连接不可用时等待，不切换到手机主屏。" else "在手机主屏处理这个目标，会使用当前屏幕。")
            }
            "phone.draft.edit" -> {
                require(requiredInt(args, "epoch") == presentationEpoch) { "原手机输入窗口已变化，草稿仍保留" }
                val text = requiredString(args, "text", true); require(text.length <= 12000)
                phoneDraft = text; phoneDraftRevision++; phoneReviews.clear()
            }
            "phone.start" -> {
                val review = phoneReviews.remove(requiredString(args, "reviewHandle")) ?: error("原手机目标审阅已关闭或已使用")
                require(SystemClock.elapsedRealtime() < review.expires && review.viewEpoch == controller.composerViewEpoch && review.tools == controller.newTaskToolBudget && review.models == controller.newTaskModelBudget && review.minutes == controller.newTaskScopeMinutes) { "原目标窗口或执行范围已变化，请重新核对" }
                require(!controller.state.chatBusy && !controller.state.phoneSubmitting && controller.state.activeTaskId == null) { "已有原工作正在处理，未重复开始" }
                controller.startPhone(review.goal, review.mode)
            }
            "phone.pause", "phone.resume", "phone.stop" -> {
                val task = requireTask(requiredString(args, "taskId"))
                require(task.id == controller.state.activeTaskId && isPhoneTask(task) && canUseTaskControls(task)) { "原手机任务已变化" }
                when (action) { "phone.pause" -> controller.pausePhone(); "phone.stop" -> controller.stopPhone(); else -> { require(task.status == "paused" && unresolvedForReview(task).isEmpty() && pendingCapabilityApprovals(task).isEmpty()); controller.continuePhone() } }
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
            "accounts.beginLogin", "accounts.cancelLogin", "accounts.select", "accounts.refresh", "accounts.models", "accounts.retryPlanUsage" -> {
                requireSection("accounts")
                val command = when (action) { "accounts.refresh" -> "snapshot"; else -> action.substringAfter('.') }
                return accepted(controller.requestOwnerDomain("accounts", command, checkedPayload(args, when (command) {
                    "beginLogin", "snapshot" -> emptySet(); "cancelLogin" -> setOf("sessionId"); "select", "models", "retryPlanUsage" -> setOf("accountId", "expectedVersion"); else -> emptySet()
                })))
            }
            "memory.list", "memory.search" -> { requireSection("memory"); return accepted(controller.requestFeature("memory", action.substringAfter('.'), checkedPayload(args, setOf("query", "cursor", "layer", "limit")))) }
            "memoryReview.list", "memoryReview.candidates", "memoryReview.getCandidate", "memoryReview.capacity", "memoryReview.history", "memoryReview.openSource" -> {
                requireSection("memoryReview")
                val command = when (action) { "memoryReview.candidates" -> "listCandidates"; "memoryReview.capacity" -> "candidateCapacity"; "memoryReview.history" -> "listCandidateHistory"; else -> action.substringAfter('.') }
                return accepted(controller.requestOwnerDomain("memoryReview", command, checkedPayload(args, setOf("id", "revision", "cursor", "sourceRef", "layer"))))
            }
            "mcp.snapshot" -> { requireSection("mcp"); return accepted(controller.requestOwnerDomain("mcp", "snapshot")) }
            "mcp.pickConfig" -> { requireSection("mcp"); return accepted(controller.pickMcpConfig()) }
            "mcp.cancel" -> { requireSection("mcp"); return accepted(controller.requestOwnerDomain("mcp", "cancel", checkedPayload(args, setOf("operationId")))) }
            "learning.snapshot" -> { requireSection("learning"); return accepted(controller.requestOwnerDomain("learning", "snapshot")) }
            "groups.snapshot" -> { requireSection("groups"); return accepted(controller.requestOwnerDomain("groups", "snapshot")) }
            "remote.probe", "remote.environments" -> {
                requireSection("remote"); checkedPayload(args, setOf("profileId"))
                val id = requiredString(args, "profileId")
                require(controller.state.feature("remote").optJSONArray("profiles").objects().any { it.optString("id") == id }) { "原电脑连接已变化，请刷新后重新核对" }
                return accepted(controller.requestFeature("remote", action.substringAfter('.'), JSONObject().put("profileId", id)))
            }
            "local.termuxProbe" -> { requireSection("local"); checkedPayload(args, emptySet()); return accepted(controller.requestFeature("local", "termux.probe")) }
            "automation.receipts" -> { require(section in setOf("automation", "commitments")) { "原提醒窗口已关闭" }; checkedPayload(args, emptySet()); return accepted(controller.requestFeature("automation", "receipts")) }
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
            "plugins.history" -> {
                requireSection("plugins"); checkedPayload(args, setOf("id"))
                val id = requiredString(args, "id")
                require(controller.state.feature("plugins").optJSONArray("plugins").objects().any { it.optString("id") == id }) { "原插件不在当前读取窗口" }
                return accepted(controller.requestFeature("plugins", "history", JSONObject().put("id", id)))
            }
            "proxy.status", "proxy.stop" -> { requireSection("proxy"); checkedPayload(args, emptySet()); return accepted(controller.requestFeature("proxy", action.substringAfter('.'))) }
            else -> error("这项操作没有已接入的宿主实现")
        }
        return null
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
                val result = runCatching { service.connections() }
                main.post {
                    if (closed) return@post
                    if (generation != domainGeneration) { requestId?.let { complete(it, null, "原能力窗口已变化，未把迟回结果接到其他页面") }; return@post }
                    result.fold({ snapshot ->
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

    private fun requireModelSelectionIdle() {
        val state = controller.state
        check(!closed && controller.webUiCanInteract()) { "原模型界面已不在前台" }
        check(state.activeTaskId == null && !state.chatBusy && !state.chatSubmitting && !state.phoneSubmitting && !state.modelSaving && !state.modelTesting) { "请先等待当前工作与模型设置回执，再切换主模型" }
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
            val result = runCatching { service.connections() }
            main.post {
                if (closed) return@post
                try {
                    check(generation == domainGeneration && document == documentId) { "原模型窗口已变化，请重新选择" }
                    requireModelSelectionIdle()
                    val snapshot = result.getOrThrow()
                    val binding = ModelBinding.fromJson(snapshot.getJSONObject("binding"))
                    val sameModel = snapshot.getJSONObject("api").optString("model") == model
                    val reasoning = if (sameModel) ReasoningOptions.fromJson(snapshot.getJSONObject("reasoning")) else ReasoningOptions.DEFAULT
                    val review = ModelReview(binding, model, modelIdentity(snapshot, model), snapshot.getJSONObject("api").optInt("maxOutputTokens", 4096), reasoning,
                        if (sameModel) snapshot.optInt("imageTokenReserve") else 0, generation, document, SystemClock.elapsedRealtime() + 120_000L)
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
            controller.runModelConnectionWork(Runnable {
                val result = runCatching {
                    requireModelSelectionIdle()
                    check(review.generation == domainGeneration && review.document == documentId && SystemClock.elapsedRealtime() < review.expires) { "原模型窗口已关闭或审阅已过期" }
                    val current = service.connections()
                    check(ModelBinding.fromJson(current.getJSONObject("binding")) == review.binding && modelIdentity(current, review.model) == review.identity) { "原连接、模型能力或选择参数已变化，请刷新后重新核对" }
                    service.select(review.binding, review.model, output, options, reserve)
                }
                main.post {
                    if (closed) return@post
                    modelSelectionBusy = false
                    result.fold({ snapshot ->
                        if (review.generation == domainGeneration && review.document == documentId) domainSnapshots["models"] = JSONObject(snapshot.toString())
                        domainErrors.remove("models"); controller.modelConnectionChanged()
                        complete(requestId, JSONObject().put("accepted", true).put("modelId", review.model).put("snapshot", PocketWebState.publicValue(snapshot)), null)
                    }, { failure -> complete(requestId, null, failure.message ?: "原模型选择未被接受") })
                    publishState()
                }
            })
        } catch (failure: Exception) {
            modelSelectionBusy = false; publishState(); throw failure
        }
    }

    private fun readDeviceSnapshot(requestId: String? = null) {
        val window = deviceWindow ?: error("原设备窗口已关闭，请重新进入")
        val generation = domainGeneration
        val ids = (controller.state.tasks.filter(::isDeviceWorkspaceTask).map { it.id } +
            workspaceTaskLinks(domainSnapshots["devices"]).map { it.optString("parentTaskId") }).filter { it.isNotBlank() }.distinct().toTypedArray()
        try {
            controller.runDeviceWorkspaceWork(Runnable {
                val result = runCatching { window.snapshot(ids) }
                main.post {
                    if (closed) return@post
                    if (generation != domainGeneration || deviceWindow !== window) { requestId?.let { complete(it, null, "原设备窗口已关闭，未接收迟回结果") }; return@post }
                    result.fold({ snapshot ->
                        domainSnapshots["devices"] = JSONObject(snapshot.toString()); domainErrors.remove("devices")
                        requestId?.let { complete(it, PocketWebState.publicValue(snapshot) as JSONObject, null) }
                    }, { error -> domainErrors["devices"] = error.message ?: "设备状态暂不可读取"; requestId?.let { complete(it, null, domainErrors["devices"]) } })
                    publishState()
                }
            })
        } catch (failure: Exception) {
            domainErrors["devices"] = failure.message ?: "本机设备读取未接受"
            requestId?.let { complete(it, null, domainErrors["devices"]) }; publishState()
        }
    }

    private fun reviewTask(task: UiTask): JSONObject {
        val allowed = linkedMapOf<String, String>()
        val special = specialOwnerTask(task)
        val unknown = unresolvedForReview(task)
        val approvals = pendingCapabilityApprovals(task)
        if (canUseTaskControls(task) && !special) {
            if (task.status == "needs_confirmation" && task.id != controller.state.activeTaskId) allowed["tasks.confirm"] = "核对结果"
            if (approvals.isNotEmpty()) allowed["tasks.approve"] = "审阅操作授权"
            if (unknown.any { it.optString("operationId", it.optString("id")).isNotBlank() }) allowed["tasks.resolve"] = "核对原操作"
            if ((isPhoneTask(task) || task.raw.optString("kind") == "assistant") && task.id != controller.state.activeTaskId && task.status in setOf("paused", "awaiting_user", "needs_confirmation", "interrupted") && !task.raw.optBoolean("cancelRequested") && unknown.isEmpty() && approvals.isEmpty()) allowed["tasks.resume"] = "确认继续原任务"
            if (!isGroup(task) && task.raw.optJSONObject("executionScope") != null && task.status !in setOf("completed", "cancelled", "canceled", "failed", "interrupted", "reconciling")) allowed["tasks.scope"] = "调整原任务范围"
        }
        val handle = reviews.create(task, allowed.keys)
        return JSONObject().put("reviewHandle", handle).put("expiresInMs", 120_000)
            .put("task", PocketWebState.task(task, images = true))
            .put("actions", JSONArray().also { out -> allowed.forEach { (action, label) -> out.put(JSONObject().put("action", action).put("label", label)) } })
            .put("approvals", PocketWebState.publicValue(JSONArray(approvals)))
            .put("unresolvedOperations", PocketWebState.publicValue(JSONArray(unknown)))
    }

    private fun unresolvedForReview(task: UiTask): List<JSONObject> = unresolvedOperations(task).filterNot { it.optString("tool") in setOf("device_invoke_tool", "device_delegate_goal", "device_operation_get") }
    private fun specialOwnerTask(task: UiTask) = MediaOwnerUi.isTask(task.raw) || task.raw.optJSONObject("sourceLearningJob") != null || task.raw.optJSONObject("nativeEventTypeFlags")?.optBoolean("memory_owner_consolidation") == true || task.raw.optJSONArray("events").objects().any { it.optString("type") == "memory_owner_consolidation" }
    private fun isGroup(task: UiTask) = task.raw.optJSONObject("origin")?.optString("kind") == "group"
    private fun canUseTaskControls(task: UiTask) = task.raw.optJSONObject("origin")?.optString("kind") != "paired_device" && (!isGroup(task) || taskPage != null && controller.groupTaskReady)
    private fun requireTask(id: String): UiTask = controller.state.tasks.firstOrNull { it.id == id } ?: error("原任务不在当前读取窗口，请刷新后重新打开")
    private fun leaveTask() { taskPage?.let(controller::leaveGroupTask); taskPage = null }
    private fun leaveConnection() { ++domainGeneration; modelReviews.clear(); deviceWindow?.close(); deviceWindow = null; section?.takeIf { it in ownerDomains }?.let(controller::leaveOwnerDomain); section = null }
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
