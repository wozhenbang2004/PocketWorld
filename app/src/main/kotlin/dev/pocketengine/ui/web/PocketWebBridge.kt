package dev.pocketengine.ui.web

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import dev.pocketengine.EngineApplication.OwnerWorkControlRejected
import dev.pocketengine.core.ModelConfig
import dev.pocketengine.core.RuntimeDiagnostics
import dev.pocketengine.features.attachments.OriginalReplyDocument
import dev.pocketengine.features.devices.DirectedPhoneTarget
import dev.pocketengine.features.devices.DirectedWorkspaceTarget
import dev.pocketengine.features.devices.NativeDeviceWorkspaceHost
import dev.pocketengine.runtime.models.ModelBinding
import dev.pocketengine.runtime.models.ReasoningOptions
import dev.pocketengine.store.OwnerPhoneControlScope
import dev.pocketengine.store.OwnerWorkTask
import dev.pocketengine.store.TaskExecutionScope
import dev.pocketengine.store.TaskStore
import dev.pocketengine.features.teams.TeamCoordinator
import dev.pocketengine.ui.AttachmentMessageInput
import dev.pocketengine.ui.AttachmentResultRun
import dev.pocketengine.ui.ChatInputRules
import dev.pocketengine.ui.MediaOwnerUi
import dev.pocketengine.ui.PhoneCapabilityState
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.accountConnectionsFailure
import dev.pocketengine.ui.backgroundConnectionState
import dev.pocketengine.ui.UiChatAttachment
import dev.pocketengine.ui.UiMessage
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
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** A one-use reference to the exact task that was displayed, never a Web-created approval. */
internal class PocketWebTaskReviews(private val now: () -> Long = SystemClock::elapsedRealtime) {
    data class TeamReport(val proof: TeamCoordinator.OwnerWorkReport, val current: () -> Boolean)
    private data class Reviewed(val task: UiTask, val identity: String, val actions: Set<String>, val expires: Long,
                                val positiveApprovalIds: Set<String>, val report: TeamReport?,
                                val coldQuestion: TaskStore.ColdOwnerQuestionReview?)
    private val entries = linkedMapOf<String, Reviewed>()

    fun create(task: UiTask, actions: Set<String>, positiveApprovalIds: Set<String> = emptySet(), bindingTask: UiTask = task,
               report: TeamReport? = null, coldQuestion: TaskStore.ColdOwnerQuestionReview? = null): String {
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
        entries[handle] = Reviewed(copy, identity(if (bindingTask === task) copy else bindingTask), actions.toSet(), now() + 120_000L, positiveApprovalIds.toSet(), report, coldQuestion)
        return handle
    }

    fun take(handle: String, action: String, current: List<UiTask>, positiveApprovalId: String? = null): UiTask {
        val reviewed = entries.remove(handle) ?: throw IllegalStateException("原审阅已关闭或已使用，请重新核对原任务")
        check(now() < reviewed.expires) { "原审阅已过期，请重新核对原任务" }
        check(action in reviewed.actions && reviewed.report == null) { "这项操作不在原审阅范围内" }
        if (positiveApprovalId != null) check(positiveApprovalId in reviewed.positiveApprovalIds) { "尚未读取这项操作的完整范围，请重新核对" }
        val actual = current.firstOrNull { it.id == reviewed.task.id }
        check(actual != null && identity(actual) == reviewed.identity) { "原任务已变化，未沿用旧审阅执行；请重新核对" }
        return reviewed.task
    }

    fun takeColdQuestion(handle: String, action: String, current: List<UiTask>): Pair<UiTask, TaskStore.ColdOwnerQuestionReview> {
        val reviewed = entries[handle] ?: error("请先重新核对原问题")
        val cold = reviewed.coldQuestion ?: error("原问题缺少当前核对")
        check(action in PocketWebOwnerQuestions.coldCommands && cold.saved() == (action == PocketWebOwnerQuestions.CONTINUE_COLD))
        return take(handle, action, current) to cold
    }

    fun teamReportParent(handle: String, taskId: String, current: List<UiTask>): UiTask {
        val review = entries[handle] ?: error("请先读取这件原工作的结果核对")
        check(review.report == null && now() < review.expires && review.task.id == taskId && "tasks.confirm" in review.actions &&
            current.singleOrNull { it.id == taskId }?.let(::identity) == review.identity) { "原工作核对已变化，请重新读取" }
        return review.task
    }

    fun selectTeamReport(original: String, taskId: String, current: List<UiTask>, report: TeamReport): String {
        val task = teamReportParent(original, taskId, current)
        check(report.current() && report.proof.origin().taskId() == task.id)
        entries.entries.removeAll { it.value.task.id == taskId && "tasks.confirm" in it.value.actions }
        return create(task, setOf("tasks.confirm"), report = report)
    }

    fun takeTeamReport(handle: String, taskId: String?, current: List<UiTask>): TeamReport {
        val entry = entries[handle] ?: error("原汇总核对已关闭，请重新核对")
        val report = entry.report ?: error("本次决定缺少已经读取的原汇总")
        entries.remove(handle)
        check(taskId == entry.task.id && now() < entry.expires && report.current() &&
            current.singleOrNull { it.id == taskId }?.let(::identity) == entry.identity) { "原汇总核对已变化，未改为普通验收" }
        return report.copy(current = { now() < entry.expires && report.current() })
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
        val context = raw.optJSONObject("developmentContext")
        if (raw.optString("kind") != "assistant" || raw.optString("actorId") != "owner" || raw.optString("spaceId") != "private" ||
            raw.has("origin") && !raw.isNull("origin") || context == null) return null
        val conversation = raw.opt("conversationId") as? String ?: ""
        val view = JSONObject().put("feedbackRequired", true).put("conversationId", conversation)
        val fixedDraft = raw.optJSONObject("originalFixedDraftFollowup")?.takeIf {
            val epoch = exactPositive(it.opt("epoch"))
            raw.opt("taskProjection") == "detail_window" && raw.opt("taskCoreReadLimited") == false &&
                raw.opt("hasUnresolvedOperations") == false && context.opt("draftOnly") == true &&
                it.opt("taskId") == task.id && it.opt("runId") == raw.opt("runId") &&
                epoch != null && epoch == exactPositive(raw.opt("coordinatorEpoch")) &&
                it.opt("draftId") == raw.opt("projectEditDraftId") &&
                it.opt("projectId") == context.opt("projectId") && it.opt("checkoutId") == context.opt("checkoutId")
        }
        val pin = context.optJSONObject("ownerDraftFirstBuild")
        val heldDependency = raw.optJSONObject("originalFirstBuildContinuationHold")?.takeIf {
            val epoch = exactPositive(it.opt("epoch"))
            val source = it.opt("sourceDigest") as? String
            val project = it.opt("projectId") as? String
            val checkout = it.opt("checkoutId") as? String
            val operation = it.opt("operationId") as? String
            task.status == "awaiting_user" && raw.opt("status") == task.status && raw.opt("cancelRequested") == false &&
                raw.opt("taskProjection") == "detail_window" && raw.opt("taskCoreReadLimited") == false &&
                raw.opt("hasUnresolvedOperations") == false && !raw.has("originalFirstBuildNext") &&
                context.opt("runtime") == "managed_process_v1" && pin?.opt("existingCheckoutBuild") == true &&
                it.opt("format") == "pocketworld.owner-node-build-continuation-hold.v1" && it.opt("reason") == "missing_original_end_witness" &&
                it.opt("taskId") == task.id && it.opt("runId") == raw.opt("runId") && epoch != null && epoch == exactPositive(raw.opt("coordinatorEpoch")) &&
                !project.isNullOrBlank() && project == context.opt("projectId") && project == pin?.opt("projectId") &&
                !checkout.isNullOrBlank() && checkout == context.opt("checkoutId") && checkout == pin?.opt("checkoutId") &&
                source?.matches(Regex("[a-f0-9]{64}")) == true && source == context.opt("initialSourceDigest") && source == pin?.opt("sourceDigest") &&
                operation?.matches(Regex("[A-Za-z0-9_-]{1,80}")) == true && it.opt("dependencyExitCode") == 0 && it.opt("buildStarted") == false
        }
        val reason = when {
            dedicated.any(raw::has) || isDeviceWorkspaceTask(task) || MediaOwnerUi.isTask(raw) ||
                raw.optString("feature") == "attachments" && raw.optString("command") in setOf("createDocument", "transformImage", "trimMedia") -> "这项专用任务须回原入口处理，不能在此续接。"
            active || task.status !in ended || raw.optBoolean("cancelRequested") -> "原任务当前不能继续；已停止或取消的任务不会重新启动。"
            conversation.isBlank() -> "原项目对话身份缺失，不能改送到另一份对话。"
            raw.optString("id") != task.id || (raw.opt("runId") as? String).isNullOrBlank() || exactPositive(raw.opt("coordinatorEpoch")) == null -> "原任务执行身份缺失，请重新读取原记录。"
            raw.optString("executionMode") != "capabilities" -> "这不是原私人项目能力任务，请回原入口核对。"
            PocketWebOwnerQuestions.blocksGenericResume(raw) -> "请先回答原任务的问题，再核对是否继续。"
            raw.optJSONArray("approvals").objects().any { it.optString("state", it.optString("status")) == "pending" } || unresolvedOperations(task).isNotEmpty() -> "请先处理原任务的审批或未决操作；草稿仍保留。"
            heldDependency != null -> "原依赖准备已有退出回执，但原执行记录不足以继续这一轮。不会重新准备依赖或构建。"
            fixedDraft != null -> {
                view.put("fixedDraft", fixedDraft)
                "这项工作已保存固定改稿。可编辑另存，或回原对话交代新的修改目标；不会另起一轮替换原稿。"
            }
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
    private var publishedMainPausePageReturn: PocketWorldController.MainPausePageReturn? = null
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
    private data class RetainedDraftSource(val draft: String, val run: String, val epoch: Int, val workIdentity: String)
    private data class OpenedRetainedDraftCopy(val project: String, val checkout: String, val sourceTask: String,
        val request: Int, val selection: Long, val document: String, val original: RetainedDraftSource?)
    @Volatile private var openedRetainedDraftCopy: OpenedRetainedDraftCopy? = null
    private fun localDraftCopy() = controller.nativeButlerHost().features.module("local") as? dev.pocketengine.features.local.LocalModule
        ?: error("原本机项目宿主不可用")
    private fun openDraftCopy(copy: JSONObject, explicitlyRetained: Boolean) {
        val sourceTask = taskViewId
        val document = documentId
        openedRetainedDraftCopy = null
        leaveTask()
        controller.openedDraftWorkCopy(copy)
        val state = controller.state
        val project = copy.getString("projectId")
        val checkout = copy.getString("checkoutId")
        if (sourceTask != null && copy.opt("taskId") == sourceTask && document != null && taskReturnSection == null &&
            copy.opt("status") == "ready" && copy.optInt("ordinal") in 1..8 &&
            state.requestedProjectId == project && controller.originalProjectEditorCurrent(project, checkout)) {
            val original = if (explicitlyRetained && copy.opt("found") == true && copy.opt("recyclable") == false &&
                copy.opt("executionAuthorized") == false && copy.optInt("epoch") > 0) {
                val draft = copy.optString("draftId"); val run = copy.optString("runId"); val work = copy.optString("workIdentity")
                if (draft.matches(Regex("[a-f0-9]{64}")) && run.isNotBlank() && work.matches(Regex("[a-f0-9]{64}")))
                    RetainedDraftSource(draft, run, copy.getInt("epoch"), work) else null
            } else null
            openedRetainedDraftCopy = OpenedRetainedDraftCopy(project, checkout, sourceTask, state.requestedProjectRevision,
                controller.projectWorkbench.navigationRevision(), document, original)
        }
        publishState()
    }

    private fun currentRetainedDraftCopy(open: OpenedRetainedDraftCopy): Boolean {
        val state = controller.state
        // The Web drawer may close after native selection. Match the actual project request, not its old task window.
        return openedRetainedDraftCopy === open && !closed && documentId == open.document && section == null &&
            taskViewId == null && controller.webPrivateOwnerVisible() && state.filePath == null &&
            state.requestedProjectId == open.project && state.requestedProjectRevision == open.request &&
            controller.projectWorkbench.navigationRevision() == open.selection &&
            controller.originalProjectEditorCurrent(open.project, open.checkout)
    }
    private fun retainedDraftCopyOpenForWeb(): JSONObject? {
        val open = openedRetainedDraftCopy ?: return null
        if (!currentRetainedDraftCopy(open)) {
            openedRetainedDraftCopy = null
            return null
        }
        return JSONObject().put("projectId", open.project).put("checkoutId", open.checkout)
            .put("sourceTaskId", open.sourceTask).put("requestRevision", open.request)
            .also { if (open.original != null) it.put("canReadOriginalDraft", true) }
    }
    private fun captureRetainedDraftOriginal(project: String, checkout: String): RetainedDraftOriginalRead? {
        val open = openedRetainedDraftCopy ?: return null
        val original = open.original ?: return null
        fun current() = currentRetainedDraftCopy(open)
        if (open.project != project || open.checkout != checkout || !current()) return null
        return RetainedDraftOriginalRead(::current) { live ->
            val local = localDraftCopy()
            fun publication(): JSONObject {
                check(live()) { "原工作副本页面已离开" }
                return local.retainedDraftOriginForOpenCopy(project, checkout, original.workIdentity).also {
                    check(it.opt("taskId") == open.sourceTask && it.opt("draftId") == original.draft &&
                        it.opt("runId") == original.run && it.optInt("epoch") == original.epoch) { "原改稿来源已变化" }
                }
            }
            val origin = publication()
            val fixed = controller.nativeButlerHost().tasks.readRetainedCopyOriginalForOwner(origin) { live() }
            check(dev.pocketengine.features.local.project.ProjectSources.canonical(origin) ==
                dev.pocketengine.features.local.project.ProjectSources.canonical(publication()) && live()) { "原工作副本页面已变化" }
            retainedOriginalComparison(fixed,project,checkout)
                .put("requestRevision",open.request).put("sourceTaskId",open.sourceTask)
        }
    }

    private fun canReadProjectGoal(row:UiTask):Boolean {
        val pin=projectGoal ?: return false
        if (row.id!=pin.task || taskViewId!=pin.task || section!=null || !controller.webPrivateOwnerVisible() || !acceptsWindow(pin.ticket)) return false
        val raw=row.raw; val d=raw.optJSONObject("developmentContext") ?: return false
        return raw.optString("kind")=="assistant" && raw.optString("actorId")=="owner" && raw.optString("spaceId")=="private" &&
            !raw.has("origin") && raw.optString("runId")==pin.run && raw.optInt("coordinatorEpoch")==pin.epoch &&
            d.optString("projectId")==pin.project && d.optString("checkoutId")==pin.checkout
    }
    private fun canReadOriginalViteEditorResume(row: UiTask): Boolean {
        val raw = row.raw
        return section == null && taskViewId == row.id && controller.webPrivateOwnerVisible() &&
            raw.opt("id") == row.id && raw.opt("spaceId") == "private" && raw.opt("actorId") == "owner" &&
            raw.opt("nativeExecutionMode") == "OWNER_DIRECT_VITE_BUILD" && raw.isNull("origin") &&
            raw.opt("taskProjection") == "detail_window" && raw.opt("contextAvailable") != false &&
            raw.opt("sourceCurrentReadRequired") != true
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
    @Volatile private var teamReportReviewGeneration = 0L
    @Volatile private var teamReportSelectionGeneration = 0L
    private val commandFence = PocketWebCommandFence()
    private val reviews = PocketWebTaskReviews()
    private val taskActions = PocketWebTaskActions(controller, reviews, ::canUseTaskControls) { work -> main.post(work) }
    private val taskHistory = PocketWebTaskHistory()
    private val workSearch = PocketWebWorkSearch(controller, ::captureWindow, ::acceptsWindow, taskHistory::remember)
    private data class ReportDraftOffer(val handle:String,val task:String,val document:String,val window:PocketWebWindowTicket,
                                        val proof:dev.pocketengine.store.TaskStore.OwnerWorkReportQuote)
    private var reportDraftOffer:ReportDraftOffer?=null
    private data class ReportDraftHandoff(val handle:String,val offer:ReportDraftOffer,val draft:String,
                                          val closedWindow:PocketWebWindowTicket?=null)
    private var reportDraftHandoff:ReportDraftHandoff?=null
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
    private val taskCapabilities = PocketWebTaskCapabilities(controller, { publishState() }, { id -> taskViewId == id }, ::originalScriptHistoryDocument)
    private val deviceTaskScope = PocketWebDeviceTaskScope(controller, { id -> taskViewId == id }, { !closed && documentId != null && section == "devices" && taskViewId == null })
    private val receivedTextResult = PocketWebReceivedTextResult(controller,::captureWindow,::acceptsWindow) {
        taskViewId.takeIf { !closed && documentId!=null && section==null && controller.webPrivateOwnerVisible() &&
            controller.conversationReader==null && controller.queuedInputsPage==null && !controller.attachmentOutcomes.state.open &&
            !controller.state.chatAttachmentPickerOpen && controller.state.filePath==null }
    }
    private val sentPhoneResult = PocketWebSentPhoneResult(controller,::captureWindow,::acceptsWindow) {
        taskViewId.takeIf { !closed && documentId!=null && section==null && controller.webPrivateOwnerVisible() &&
            controller.conversationReader==null && controller.queuedInputsPage==null && !controller.attachmentOutcomes.state.open &&
            !controller.state.chatAttachmentPickerOpen && controller.state.filePath==null }
    }
    private val phoneControl = PocketWebPhoneControl(controller, { id -> taskViewId == id }, { !closed && documentId != null && section == "devices" && taskViewId == null })
    private val projectRuntime = PocketWebProjectRuntime(controller)
    private val attachmentPages = PocketWebAttachments(controller, ::captureWindow, ::acceptsWindow, { task ->
        !closed && documentId != null && section == null && taskReturnSection == null && taskViewId == task
    }) { publishState() }
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
        ::localDraftCopy, ::openDraftCopy) {
        taskViewId.takeIf{!closed&&documentId!=null&&section==null&&controller.state.filePath==null&&controller.webPrivateOwnerVisible()}
    }
    private val firstNodeWebOutput=PocketWebFirstNodeWebOutput({controller.nativeButlerHost().tasks},
        {controller.nativeButlerHost().features.module("local") as? dev.pocketengine.features.local.LocalModule ?: error("原本机项目宿主不可用")},
        controller::runPluginCatalogWork,::captureWindow,::acceptsWindow) {
        taskViewId.takeIf{!closed&&documentId!=null&&section==null&&controller.state.filePath==null&&controller.webPrivateOwnerVisible()}
    }
    private val nodeCompilerOutput=PocketWebNodeCompilerOutput({controller.nativeButlerHost().tasks},
        {controller.nativeButlerHost().features.module("local") as? dev.pocketengine.features.local.LocalModule ?: error("原本机项目宿主不可用")},
        controller::runPluginCatalogWork,::captureWindow,::acceptsWindow,
        { task, run, epoch, operation, current ->
            controller.nativeButlerHost().features.checkDirectNodeReceiptForOwner(task,run,epoch,operation,java.util.function.BooleanSupplier { current() })
        }, { task, run, epoch, operation, current ->
            controller.nativeButlerHost().features.checkDirectNodeDependencyReceiptForOwner(task,run,epoch,operation,java.util.function.BooleanSupplier { current() })
        }) {
        taskViewId.takeIf{!closed&&documentId!=null&&section==null&&controller.state.filePath==null&&controller.webPrivateOwnerVisible()}
    }
    private val pythonOutput=PocketWebPythonOutput(controller::localTerminalHost,controller::runPluginCatalogWork,::captureWindow,::acceptsWindow) {
        taskViewId.takeIf{!closed&&documentId!=null&&section==null&&controller.state.filePath==null&&
            controller.conversationReader==null&&!attachmentPages.hasOpenWorkspace&&controller.webPrivateOwnerVisible()}
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
    private val projectDevelopment = PocketWebProjectDevelopment(controller, { !closed && documentId != null && section == null && taskViewId == null && controller.webPrivateOwnerVisible() },
        ::captureWindow, ::acceptsWindow, ::captureRetainedDraftOriginal, ::captureSuppliedViteLockRoute)
    private val knowledge = PocketWebKnowledge(controller, projectDevelopment::conventionDraft)
    private val butlerPreparations = PocketWebButlerPreparations(controller, { !closed && documentId != null && controller.webPrivateOwnerVisible() }, ::captureWindow, ::acceptsWindow)
    private val localPages = PocketWebLocal(controller) { taskViewId }
    private val setupPages = PocketWebSetup(controller) { !closed && documentId != null && section == "setup" && taskViewId == null }
    private var projectPreview: PocketWebProjectPreview? = null
    private var projectLivePreview: PocketWebProjectLivePreview? = null
    private val phoneViewer = PocketWebPhoneViewer(controller::webPrivateOwnerVisible,
        { PocketWebWindowTicket(commandFence.ticket(), controller.webPhoneViewerWindowTicket()) },
        { ticket -> ticket.accepts(commandFence.ticket(), controller.webPhoneViewerWindowTicket()) },
        { id -> controller.state.tasks.firstOrNull { it.id == id } }, controller::readPhonePreview,
        originalActivity = { task, run, epoch, handle ->
            val document = documentId
            val ticket = commandFence.ticket()
            fun current() = !closed && document != null && documentId == document && commandFence.accepts(ticket) &&
                section == null && taskViewId == null && taskReturnSection == null
            controller.retainOriginalChatPhonePreview(task, run, epoch, handle, ::current)
        })
    private val commandReceipts = linkedMapOf<String, Pair<String, JSONObject>>()
    private val pendingCommands = mutableMapOf<String, String>()
    @Volatile private var documentId: String? = null
    private data class SavedLocalReturn(val handle:String,val window:PocketWebWindowTicket,val key:String,val conversation:String,val message:String,val revision:Long)
    @Volatile private var savedLocalReturn: SavedLocalReturn? = null
    private val phoneComposer = PocketWebPhoneComposer(controller, { closed }, ::captureWindow, ::acceptsWindow,
        { PocketWebWindowTicket(commandFence.ticket(), controller.webPhoneViewerWindowTicket()) },
        { ticket -> ticket.accepts(commandFence.ticket(), controller.webPhoneViewerWindowTicket()) },
        { id, value, error -> complete(id, value, error) }, { publishState() })
    private val voiceInput = PocketWebVoiceInput(controller, phoneComposer)
    private var deviceWindow: NativeDeviceWorkspaceHost.Window? = null
    private val devicePages = PocketWebDevices(controller, { deviceWindow }, { !closed && section == "devices" && controller.webPrivateOwnerVisible() })
    private val readingOriginalWindowsText = AtomicBoolean(false)
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
        val commands: Set<String> = PocketWebOwnerQuestions.coldCommands + PocketWebLocal.PROJECT_OBSERVATION_COMMANDS + PocketWebLocalSourceIntake.COMMANDS + PocketWebFirstNodeWebOutput.COMMANDS  + PocketWebNodeCompilerOutput.COMMANDS + PocketWebPythonOutput.COMMANDS + linkedSetOf(
            "conversation.captureHostGoal", "conversation.openHostGoal", "conversation.readPrivateHostFinal", "conversation.saveFirstHostRequest", "conversation.selectSavedHost",
            "conversation.projectConvention.read", "conversation.projectConvention.stageFeedback",
            "app.ready", "app.refresh", "notice.dismiss", "notice.task.close", "phone.notice.refresh", "phone.notice.close", "error.dismiss", "execution.read", "execution.review", "execution.confirm",
            "composer.edit", "composer.send", "composer.mode", "composer.intent", "composer.reading", "composer.query", "composer.retry", "composer.returnPending", "composer.releasePreparedFirst", "composer.readSavedFirst", "composer.team.replace",
            "chat.cancel", "ownerWork.input", "ownerWork.control", "message.origin", "conversation.new", "conversation.select", "conversation.directory", "conversation.reader.open", "conversation.reader.close", "conversation.reader.earlier", "conversation.reader.refresh", "conversation.reader.position", "conversation.reader.select", "conversation.reader.attachment",
            "ownerWork.teamReport.read", "ownerWork.supplement.read", "ownerWork.supplement.edit", "ownerWork.supplement.send", "ownerWork.supplement.query", "ownerWork.report.history", "ownerWork.report.followUp.prepare", "ownerWork.report.followUp.resume", "ownerWork.report.followUp.discardEmpty",
            "attachments.pick", "attachments.openPicker", "attachments.closePicker", "attachments.toggle", "attachments.remove", "attachments.open", "sources.open", "sources.read", "sources.close",
            "phone.request.original", "phone.result.taskOriginal",
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
            "phone.prepare", "phone.review.settings", "phone.start", "phone.query", "phone.draft.recentStarts", "phone.draft.retireUncreated", "phone.draft.edit", "phone.draft.fromMessage", "phone.draft.saveOriginal", "phone.pauseNote.read", "phone.pauseNote.continue", "phone.pause.leavePage", "phone.ownerStep.review", "phone.ownerStep.leave", "phone.pause", "phone.resume", "phone.stop", "phone.deadline", "background.refresh", "background.discover", "background.cancel", "background.close", "background.pair", "background.connect", "background.mainScreen", "background.selectMainScreen",
            "queued.open", "queued.refresh", "queued.close", "queued.edit", "queued.change", "queued.save", "queued.editor.close",
            "accounts.beginLogin", "accounts.cancelLogin", "accounts.openAuthorization", "accounts.select", "accounts.refresh", "accounts.models", "accounts.retryPlanUsage", "accounts.model.review", "accounts.model.select", "accounts.logout.review", "accounts.logout.confirm", "accounts.setEnabled", "accounts.refreshAuthorization", "accounts.reauthorize", "accounts.acknowledgePlanWelcome", "accounts.manageUsage",
            "memoryReview.list", "memoryReview.getCandidate", "memoryReview.capacity", "memoryReview.history", "memoryReview.openSource",
            "memoryReview.candidates", "mcp.snapshot", "mcp.pickConfig", "mcp.cancel", "mcp.discoverImport", "mcp.discoverDirect", "mcp.reviewImport", "mcp.installImport", "mcp.reviewEnabled", "mcp.confirmEnabled", "mcp.read", "mcp.reviewGrant", "mcp.confirmGrant", "learning.snapshot", "groups.snapshot",
            "remote.probe", "remote.environments", "local.termuxProbe", "local.inspectRuntime", "local.readReceipts", "local.prepareCheckout", "local.describeCheckout", "local.terminal.review", "local.terminal.confirm", "local.terminal.query", "local.terminal.stop", "local.terminal.open", "local.terminal.read", "local.terminal.input", "local.terminal.inputReceipt", "local.terminal.close", "automation.receipts", "automation.list", "automation.reviewSchedule", "automation.reviewRule", "automation.confirmReview", "automation.prepareOccurrence", "channels.inspect", "skills.read", "plugins.history", "proxy.status", "proxy.stop"
        ) + PocketWebSetup.COMMANDS + PocketWebModelConnectionCommands.COMMANDS + PocketWebAiSources.COMMANDS + PocketWebAiGroups.COMMANDS + PocketWebConversationModels.COMMANDS + PocketWebPermissionPolicy.COMMANDS + PocketWebData.COMMANDS + PocketWebConnections.COMMANDS + PocketWebMemoryDrafts.COMMANDS + setOf("conversation.drafts", "composer.firstMemory.save", "composer.oneLearning.save") + PocketWebKnowledge.COMMANDS + PocketWebPersonal.COMMANDS + PocketWebMessageQuotes.COMMANDS + PocketWebChannels.COMMANDS + PocketWebExperience.COMMANDS + PocketWebRouteReferences.COMMANDS + PocketWebSkills.COMMANDS + PocketWebPresentation.COMMANDS + PocketWebTeams.COMMANDS + PocketWebGroups.COMMANDS + PocketWebMedia.COMMANDS + PocketWebComposerModels.COMMANDS + PocketWebDevices.COMMANDS + PocketWebReceivedTextResult.COMMANDS + PocketWebPlugins.COMMANDS + PocketWebRealtime.COMMANDS + PocketWebProxy.COMMANDS + PocketWebPlans.COMMANDS + PocketWebCommitments.COMMANDS + PocketWebTokenBudgets.COMMANDS + PocketWebProfiles.COMMANDS + PocketWebProjectDraftDifference.COMMANDS + PocketWebProjectWork.COMMANDS + PocketWebProjectPreview.COMMANDS + PocketWebPhoneViewer.COMMANDS + PocketWebBackground.COMMANDS + PocketWebMemoryHistory.COMMANDS + PocketWebModelDiagnostics.COMMANDS + PocketWebDeveloperLogs.COMMANDS + PocketWebAttachments.COMMANDS + PocketWebRemoteTasks.COMMANDS + PocketWebTeamEvidence.COMMANDS + PocketWebConversationOrganization.COMMANDS + PocketWebTaskCapabilities.COMMANDS + PocketWebPhoneControl.COMMANDS + PocketWebDeviceTaskScope.COMMANDS + PocketWebProjectRuntime.COMMANDS + PocketWebProjectLivePreview.COMMANDS + PocketWebRemoteInbox.COMMANDS + PocketWebProjectDevelopment.COMMANDS + dev.pocketengine.ui.ProjectFormalDrafts.COMMANDS + dev.pocketengine.ui.ProjectFormalFileExport.COMMANDS + PocketWebProjectGitImport.COMMANDS + PocketWebButlerPreparations.COMMANDS + PocketWebWorkSearch.COMMANDS + PocketWebFormDrafts.COMMANDS
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
    fun foreground(value: Boolean) { if (!value) { retireReplyPicker();receivedTextResult.clear();sentPhoneResult.clear();reportDraftOffer=null;reportDraftHandoff=null;connectionPages.hostGoalOrigins.closeSaved(); projectDraftDifference.clearWorkCopy();firstNodeWebOutput.clear();nodeCompilerOutput.clear();pythonOutput.clear();projectGoal=null; projectWorkPages.clear() }; projectPreview?.foreground(value); projectLivePreview?.foreground(value); phoneViewer.foreground(value); attachmentPages.foreground(value); remoteTasks.foreground(value); taskCapabilities.foreground(value); deviceTaskScope.foreground(value); phoneControl.foreground(value) }
    fun resource(uri: android.net.Uri, method: String, headers: Map<String, String>): android.webkit.WebResourceResponse? = attachmentPages.response(uri, method, headers)

    fun close() {
        if (closed) return
        closed = true
        retireReplyPicker()
        controller.closeOwnerWorkRequestHistory()
        controller.forgetHeldPhoneImage()
        started = false
        cancelStatePublication()
        presentation.close()
        projectDraftDifference.clearWorkCopy();firstNodeWebOutput.clear();nodeCompilerOutput.clear();pythonOutput.clear()
        localSourceIntake.close();controller.projectFileImports.retireSuppliedLock()
        projectGitImport.close()
        background.clear()
        remoteInbox.close()
        modelDiagnostics.close()
        developerLogs.close()
        attachmentPages.close(); remoteTasks.close()
        projectLivePreview?.close(); projectLivePreview = null
        projectPreview?.close(); projectPreview = null; phoneViewer.clear(); receivedTextResult.clear(); sentPhoneResult.clear()
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

    private fun ordinaryDetachedHistoryEpoch(conversation:String):Int? {
        if(closed||documentId==null||section!=null||taskViewId!=null||!controller.webPrivateOwnerVisible()||
            controller.hasChannelPhoneOrigin||phoneComposer.intent||controller.state.filePath!=null||
            controller.state.chatAttachmentPickerOpen||controller.queuedInputsPage!=null||controller.attachmentOutcomes.state.open||
            conversation!=controller.state.conversationId)return null
        val draft=controller.composerDraft?:return null
        return controller.composerViewEpoch.takeIf { draft.key=="conversation:$conversation"&&draft.conversationId==conversation }
    }

    private fun stageDetachedHistoryPosition(page:dev.pocketengine.ui.ConversationReadPage,args:JSONObject):Boolean {
        val epoch=ordinaryDetachedHistoryEpoch(page.conversation)?:return false
        if(page.detachedHistoryEpoch!=epoch)return false
        val place=args.optJSONObject("detachedPosition")?:return false
        // A painted index is a hint. The current same native row must still match its ID and revision.
        return runCatching {
            require(place.keys().asSequence().toSet()==setOf("index","offset","messageId","sourceRevision"))
            val index=requiredInt(place,"index");val offset=requiredInt(place,"offset")
            if(args.has("index")||args.has("offset"))require(index==requiredInt(args,"index")&&offset==requiredInt(args,"offset"))
            controller.rememberDetachedHistoryPosition(page.window,index,offset,requiredString(place,"messageId"),requiredLong(place,"sourceRevision"))
        }.getOrDefault(false)
    }

    /** Keep only picker intent across the expected OS pause, never the old Web window or review. */
    private fun captureSuppliedViteLockRoute(project:String): () -> Boolean {
        val document=requireNotNull(documentId)
        val navigation=controller.projectWorkbench.navigationRevision()
        val state=controller.state
        val original=Triple(state.requestedProjectRevision,state.requestedTaskRevision,state.requestedConnectionRevision)
        return {
            val now=controller.state
            !closed&&documentId==document&&section==null&&taskViewId==null&&now.filePath==null&&now.projectId==project&&
                navigation==controller.projectWorkbench.navigationRevision()&&
                original==Triple(now.requestedProjectRevision,now.requestedTaskRevision,now.requestedConnectionRevision)
        }
    }
    private fun freshLibraryEntry(): (() -> Boolean)? {
        val document = documentId ?: return null
        val ticket = captureWindow()
        val current = {
            !closed && documentId == document && acceptsWindow(ticket) && section == null && taskViewId == null &&
                controller.webPrivateOwnerVisible() && controller.conversationReader == null &&
                !controller.state.chatAttachmentPickerOpen && controller.state.filePath == null
        }
        return current.takeIf { it() }
    }

    fun postMessage(raw: String, window: PocketWebWindowTicket, onStaleWindow: () -> Unit) {
        if (closed) return
        // The existing workspace backend remains the authority on individual file size limits.
        if (raw.length > 16 * 1024 * 1024) return
        main.post { if (!closed && started) { if (acceptsWindow(window)) process(raw) else onStaleWindow() } }
    }

    /** Synchronously invalidate queued commands and short-lived owner review handles. */
    fun invalidatePresentation(documentReplaced: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        retireReplyPicker()
        savedLocalReturn = null
        openedRetainedDraftCopy = null
        controller.closeOwnerWorkRequestHistory()
        controller.forgetHeldPhoneImage()
        cancelStatePublication()
        projectGoal=null; pythonOutput.clear(); commandFence.invalidate()
        if (documentReplaced) controller.forgetShownPhoneActivity()
        if (documentReplaced) {localSourceIntake.close();controller.projectFileImports.retireSuppliedLock()} else localSourceIntake.pause()
        projectGitImport.close()
        phoneViewer.clear()
        receivedTextResult.clear(); sentPhoneResult.clear()
        background.clear()
        projectPreview?.invalidatePresentation(documentReplaced)
        taskReviewGeneration++
        domainGeneration++
        taskPackagePreparation.clear()
        taskHistory.clear(); workSearch.clear()
        reviews.clear(); phoneComposer.clearReviews(); voiceInput.clear(); modelReviews.clear(); automation.clear(); mcpImports.clear(); executionReviews.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); trajectory.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); memoryHistory.clear(); conversationOrganization.clear(); if (documentReplaced) taskCapabilities.clear() else taskCapabilities.background(); deviceTaskScope.clear(); phoneControl.clear(); projectRuntime.clear(); projectLivePreview?.clear(); modelDiagnostics.clear(); attachmentPages.clear(keepDraftPresentation = !documentReplaced); remoteTasks.clear(); teamEvidence.clear(); experience.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); projectWorkPages.clear(); devicePages.clear(preserveImageChoice = !documentReplaced); pluginPages.clear(); localPages.clear(); projectDevelopment.invalidateDraftReturn(); projectDevelopment.clear(); butlerPreparations.clear(); setupPages.clear(); accountWorkspace.invalidate(); composerModels.clear(); aiSources.clear(); conversationModels.clear()
        if (documentReplaced) personal.clear() else personal.background()
        developerLogs.clear()
        if (documentReplaced) routeReferences.clear() else routeReferences.background()
        if (documentReplaced) { controller.webChannels.clear(); controller.webSkills.clear() } else { controller.webChannels.background(); controller.webSkills.background() }
        if (documentReplaced) {
            controller.projectPackages.retireDirectViteExport()
            // A replacement document did not choose this project namespace. Normal backgrounding is not an exit.
            if (section == "memory" || section == "memoryReview") { controller.leaveOwnerDomain("memoryReview"); section = null }
            if (documentId != null) controller.clearReminderNotice()
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

    private fun mainPausePageWindow(id: String) = !closed && started && documentId != null && section == null &&
        taskViewId == id && projectPreview?.snapshot()?.optBoolean("visible") != true &&
        projectLivePreview?.snapshot()?.optBoolean("mounted") != true && !attachmentPages.hasOpenWorkspace
    private fun mainPausePageReturn() = taskViewId?.takeIf(::mainPausePageWindow)?.let(controller::mainPausePageReturn)

    /** The host's existing Stop-surface pass notices the native pause button settling after a task snapshot.
     * This adds no timer, platform read or task work. Emit only a changed, exact presentation. */
    internal fun refreshMainPausePageReturn() {
        if (publishedMainPausePageReturn != mainPausePageReturn()) publishState()
    }

    private fun publishStateNow(force: Boolean = false) {
        if (closed || !started) return
        var projection: JSONObject? = null
        snapshotObserver.observeReads {
            controller.modelSelectionRevision
            val exactPicker=currentReplyPicker()
            projection = PocketWebState.snapshot(controller, ::canReadProjectGoal, ::canReadOriginalViteEditorResume) {
                liveReplyDocuments(it,inOwnReader=controller.attachmentOutcomes.state.messageReplyDocumentOnly,allowSummary=true).orEmpty()
                    .map{original->PocketWebState.ReplyDocument(original.file,original.checkFirst)}
            }
            exactPicker?.let {
                projection?.getJSONObject("state")?.put("originalReplyAttachmentPicker",
                    JSONObject().put("requestId",it.request).put("draftKey",it.key).put("draftEpoch",it.epoch).put("sourceWindow",it.sourceWindow)
                        .put("file",PocketWebState.attachment(it.file)).put("checking",replyPickerChecking!=null))
            }
            if(section==null&&taskViewId==null) controller.adoptedFormalComparisonForWeb()?.let {
                projection?.getJSONObject("state")?.put("adoptedFormalComparison",it)
            }
            val state = controller.state
            state.conversationId?.takeIf { ordinaryDetachedHistoryEpoch(it)!=null&&controller.savedDetachedHistory(it) }?.let {
                projection?.getJSONObject("state")?.put("detachedHistoryResumeAvailable",true)
            }
            state.requestedReminderNotice?.takeIf { state.requestedConnectionSection == "automation" && state.requestedConnectionRevision == it.connectionRevision }?.let {
                projection?.getJSONObject("state")?.put("requestedReminderNotice", JSONObject()
                    .put("id", it.id).put("ruleId", it.ruleId).put("revision", it.revision).put("occurrenceAt", it.occurrenceAt)
                    .put("connectionRevision", it.connectionRevision))
            }
            retainedDraftCopyOpenForWeb()?.let { projection?.getJSONObject("state")?.put("openedRetainedDraftCopy", it) }
            projection?.getJSONObject("state")?.put("projectPreview", projectPreview?.snapshot() ?: JSONObject.NULL)
            projection?.getJSONObject("state")?.put("directVitePackageExport", controller.projectPackages.directViteExportSnapshot() ?: JSONObject.NULL)
            projection?.getJSONObject("state")?.put("localSourceIntake", localSourceIntake.snapshot() ?: JSONObject.NULL)
            projection?.getJSONObject("state")?.put("suppliedViteLock", controller.projectFileImports.suppliedLockSnapshot() ?: JSONObject.NULL)
            projection?.getJSONObject("state")?.put("projectGitImport", projectGitImport.snapshot() ?: JSONObject.NULL)
            projection?.getJSONObject("state")?.put("projectLivePreview", projectLivePreview?.snapshot() ?: JSONObject.NULL)
            projection?.getJSONObject("state")?.put("appearance", presentation.snapshot())
            projection?.getJSONObject("state")?.put("backgroundOwner", background.snapshot())
            projection?.getJSONObject("state")?.put("remoteInbox", remoteInbox.snapshot())
            projection?.getJSONObject("state")?.put("modelDiagnostics", modelDiagnostics.snapshot())
            projection?.getJSONObject("state")?.put("attachmentWorkspace", attachmentPages.snapshot(
                draftPresentation = documentId != null && section == null && taskViewId == null))
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
        publishedMainPausePageReturn = mainPausePageReturn()
        snapshot.put("capabilities", JSONObject().put("commands", JSONArray(commands.toList())))
            .put("view", JSONObject().put("section", section ?: JSONObject.NULL).put("resumeRevision", workspaceResumeRevision).put("taskId", taskViewId ?: JSONObject.NULL).also { view ->
                if (section == null) taskViewId?.let { task -> controller.heldPrivatePhoneImage(task)?.let { view.put("heldPrivatePhoneImage", it.descriptor()) } }
                publishedMainPausePageReturn?.let { view.put("mainPausePageReturn", it.view()) }
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
            if (args.has(PocketWebSentPhoneResult.ARG)) {
                if(action=="devices.queryPhoneGoalResult"){
                    sentPhoneResult.read(args) { value,error ->
                        complete(id,value,error,retainValue=false,replayHint="请在原工作手动读取这次原手机结果")
                    }
                    return
                }
                if(action=="devices.discard"){
                    complete(id,sentPhoneResult.close(args),null,retainValue=false)
                    return
                }
                error("原手机成果参数仅用于同一份原工作读取")
            }
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
            if (action == "composer.team.replace") {
                replaceOriginalComposerTeam(id, args)
                return
            }
            if (action in PocketWebWorkSearch.COMMANDS) {
                fun ownsSavedBuildShelf()=section==null&&taskViewId==null&&controller.webPrivateOwnerVisible()&&
                    controller.state.projectId==args.opt("projectId")&&controller.state.filePath==null
                if(action==PocketWebWorkSearch.SAVED_BUILD_WEB)require(ownsSavedBuildShelf()){ "请从该私人项目工作台查看已保存网页" }
                val chatSearch = if (action in setOf("work.search", "work.search.read") && args.has("conversationScope")) {
                    val scope = args.getJSONObject("conversationScope")
                    require(scope.keys().asSequence().toSet() == setOf("conversationId", "composerEpoch"))
                    val conversation = requiredString(scope, "conversationId")
                    val epoch = requiredInt(scope, "composerEpoch")
                    require(conversation.length <= 240 && epoch >= 0)
                    val ownerDocument = requireNotNull(documentId)
                    val ownerWindow = captureWindow()
                    // The scan may wait on the original DB monitor. Recheck plain
                    // presentation/navigation epochs there, without synchronizing the display.
                    val foreground = controller.displayedPhoneWindowVersion()
                    val reader = controller.conversationReaderRevision
                    fun scanCurrent() = !closed && documentId == ownerDocument && commandFence.accepts(ownerWindow.bridge) &&
                        taskViewId == null && controller.displayedPhoneWindowAlive(foreground) &&
                        controller.composerViewEpochForScopedRead == epoch && controller.conversationReaderRevision == reader
                    fun current() = scanCurrent() && acceptsWindow(ownerWindow) && section == null &&
                        controller.webPrivateOwnerVisible() && controller.conversationReader == null &&
                        !controller.hasChannelPhoneOrigin && !phoneComposer.intent && controller.state.filePath == null &&
                        !controller.state.chatAttachmentPickerOpen && controller.queuedInputsPage == null &&
                        !controller.attachmentOutcomes.state.open && controller.state.conversationId == conversation &&
                        controller.composerViewEpoch == epoch && controller.composerDraft?.let {
                            it.key == "conversation:$conversation" && it.conversationId == conversation
                        } == true
                    check(current()) { "请回到当前原私聊查找本机原文" }
                    PocketWebWorkSearch.CurrentConversation(conversation, epoch, ::current, ::scanCurrent)
                } else null
                val conversationResult = if (action == "tasks.resultFiles") visibleChatTaskResult(args) else null
                if (action in setOf("ownerWork.report.read", "tasks.projectPackages", TaskProjectPackageFiles.COMMAND, "tasks.resultFiles") && conversationResult == null) require(section == null && taskViewId == requiredString(args, "taskId") && controller.webPrivateOwnerVisible()) { "请在原工作详情中读取这份成果" }
                val offerTask=if(action=="ownerWork.report.read"&&args.opt("independentFollowUp")==true) taskViewId else null
                val offerDocument=documentId
                val offerWindow=captureWindow()
                if(offerTask!=null)reportDraftOffer=null
                fun canPresentOffer()=offerTask!=null&&offerDocument!=null&&documentId==offerDocument&&
                    acceptsWindow(offerWindow)&&section==null&&taskViewId==offerTask&&taskReturnSection==null&&controller.webPrivateOwnerVisible()
                var offer:ReportDraftOffer?=null
                val receiveProof:((dev.pocketengine.store.TaskStore.OwnerWorkReportQuote)->Unit)?=
                    if(offerTask!=null&&offerDocument!=null) { proof ->
                        if(canPresentOffer())offer=ReportDraftOffer(UUID.randomUUID().toString(),offerTask,offerDocument,offerWindow,proof)
                    } else null
                workSearch.dispatch(action, args, conversationResult, receiveProof, chatSearch) { result, error ->
                    if(offerTask!=null) {
                        if(!canPresentOffer())complete(id,null,"原汇报窗口已变化",retainValue=false)
                        else {
                            if(error==null&&result!=null)offer?.let { reportDraftOffer=it;result.put("followUpHandle",it.handle) }
                            complete(id,result,error,retainValue=false)
                        }
                        return@dispatch
                    }
                    if (chatSearch != null) {
                        val current = chatSearch.current()
                        complete(id, if (current) result else null, if (current) error else "原私聊已变化，未返回查询内容",
                            retainValue = false, replayHint = "请从当前原私聊重新查找")
                    }
                    else if (conversationResult != null && runCatching { visibleChatTaskResult(args) }.getOrNull() != conversationResult)
                        complete(id, null, "原对话消息已变化，请重新打开成果")
                    else if(action==PocketWebWorkSearch.SAVED_BUILD_WEB&&!ownsSavedBuildShelf())
                        complete(id,null,"原项目窗口已变化，没有返回旧清单",retainValue=false)
                    else if (action == TaskProjectPackageFiles.COMMAND && (section != null || taskViewId != args.optString("taskId") || !controller.webPrivateOwnerVisible()))
                        complete(id, null, "原工作已关闭，没有带出包内文件", retainValue = false)
                    else complete(id, result, error, retainValue = action !in setOf("tasks.resultFiles", TaskProjectPackageFiles.COMMAND, PocketWebWorkSearch.SAVED_BUILD_WEB))
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
                val originalChat = action == "phone.viewer.open" && args.has("originalActivityHandle")
                phoneViewer.dispatch(action, args) { result, error, code ->
                    complete(id, result, error, code.ifEmpty { "preview_unavailable" }, retainValue = action != "phone.viewer.frame" && !originalChat,
                        replayHint = if (originalChat) "请回原对话重新打开这轮独立屏" else null)
                }
                return
            }
            if (action in PocketWebLocalSourceIntake.COMMANDS) {
                val openedSource: ((JSONObject?, String?) -> Unit)? =
                    if (action == PocketWebLocalSourceIntake.OPEN_PROJECT && args.has("sourceFiles")) { value, error ->
                        complete(id, value, error, retainValue = false); publishState()
                    } else null
                val value = localSourceIntake.dispatch(action, args, openedSource)
                if (openedSource == null) complete(id, value, null, retainValue = false)
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
            if(action=="projects.development.taskPackageSource" || action=="projects.development.resolveTaskPackageSource" ||
                action=="projects.development.inspect" && args.has("readOriginalDraft")){
                projectDevelopment.dispatch(action,args){r,e->complete(id,r,e,retainValue=false)};return
            }
            if (action=="tasks.close" && args.has("dependencyComparison")) {
                complete(id,closeForNodeDependencyComparison(args),null,retainValue=false); publishState(); return
            }
            if (action in PocketWebProjectDevelopment.COMMANDS) {
                val previous=if (action=="projects.development.localBuild.review" && args.has("dependencyComparisonHandle"))
                    takeNodeDependencyComparison(args) else null
                projectDevelopment.dispatch(action, args, previous) { result, error -> complete(id, result, error, retainValue = action !in PocketWebCheckoutFiles.COMMANDS && action !in PocketWebProjectDevelopment.LOCK_COMMANDS && action !in setOf("projects.development.readHostDiff", "projects.development.read", "projects.development.reviewDraftBuild", "projects.development.localBuild.review", "projects.development.localDependencies.confirm", "projects.development.review", "projects.development.start", "projects.development.query")); publishState() }; return
            }
            if (action in PocketWebButlerPreparations.COMMANDS) {
                butlerPreparations.dispatch(action, args, section == null && taskViewId == null) { result, error -> complete(id, result, error, "outcome_unknown", retainValue=action !in setOf("butler.preparation.project.savedRevisions","butler.preparation.project.followup") && !(action=="butler.preparation.project.review" && args.has("ownerRevision"))); publishState() }; return
            }
            if(action in PocketWebNodeCompilerOutput.COMMANDS){
                nodeCompilerOutput.dispatch(action,args){r,e->complete(id,r,e,retainValue=false)};return
            }
            if(action in PocketWebPythonOutput.COMMANDS){
                pythonOutput.dispatch(action,args){r,e->complete(id,r?.let{PocketWebState.publicValue(it) as JSONObject},e,retainValue=false)};return
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
                val directExport = action == PocketWebProjectPreview.EXPORT_LOCAL_WEB
                val exportDocument = if (directExport) documentId else null
                val localTask = if (action == PocketWebProjectPreview.OPEN_LOCAL_WEB || action == PocketWebProjectPreview.PREVIEW_LOCAL_BUILD || directExport)
                    requiredString(args, "taskId") else null
                fun localWorkCurrent() = localTask != null && section == null && taskViewId == localTask && controller.webPrivateOwnerVisible() &&
                    (!directExport || !closed && exportDocument != null && exportDocument == documentId && controller.state.filePath == null &&
                        controller.conversationReader == null && !controller.attachmentOutcomes.state.open)
                check(localTask == null || localWorkCurrent()) { "请先打开这份原本机构建" }
                (projectPreview ?: error("原项目预览宿主尚未接入")).dispatch(action, args, ::localWorkCurrent) { result, error ->
                    complete(id, result, error, retainValue = localTask == null && result?.opt("localWebWork") != true)
                    publishState()
                }
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
                    if (action == "phoneControl.prepare" && error == null && result?.optBoolean("prepared") == true) {
                        val taskId = result.getString("taskId")
                        openOriginalTask(id, taskId, true, "devices", preparedPhoneReceipt = result,
                            sourceWindowCurrent = { section == "devices" && taskViewId == null && controller.webPrivateOwnerVisible() }, checkReaderInput = { source ->
                                OwnerPhoneControlScope.require(source.raw)
                                require(source.id == taskId && result.opt("taskStarted") == false &&
                                    OwnerPhoneControlScope.target(source.raw) == result.optJSONObject("manualPhoneTarget")?.let { DirectedPhoneTarget.read(it) }) {
                                    "原手控准备或手机身份已变化，没有重新创建或启动"
                                }
                            })
                    } else { complete(id, result, error, retainValue = false); publishState() }
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
                    else { complete(id, result, error, retainValue = action != "deviceTaskScope.readPhoneDispatchFact"); publishState() }
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
            if(action=="attachments.openPicker"&&args.has("replyDocument")){
                openReplyPicker(id,args);return
            }
            if(action=="attachments.toggle"&&replyPicker!=null){
                toggleReplyPicker(id,args);return
            }
            if(action in setOf("attachments.openPicker","attachments.pick","attachments.toggle")){
                check(replyPicker==null&&replyPickerOpening==null){"请先关闭这份原答复的附件选择"}
            }
            if (action == "attachments.open" || action == "conversation.reader.attachment") {
                val source = checkedMessageAttachmentSource(args)
                require(source.has("window") == (action == "conversation.reader.attachment")) { "原消息窗口身份不匹配" }
                val file = visibleMessageAttachment(source) ?: error("原消息附件或版本已变化，请在原位置核对")
                val ticket = captureWindow()
                val document = documentId
                val reader = controller.conversationReaderRevision
                fun current() = !closed && documentId == document && controller.conversationReaderRevision == reader &&
                    acceptsWindow(ticket) && visibleMessageAttachment(source) == file
                controller.openMessageAttachment(file, ::current) { status ->
                    complete(id, source.put("status", status), null, retainValue = false)
                }
                return
            }
            if (action in PocketWebAttachments.COMMANDS) {
                val draftCommand = args.opt("command") as? String
                if (action == "attachmentWorkspace.action" && draftCommand != null && draftCommand in PocketWebAttachments.PRIVATE_DRAFT_COMMANDS) {
                    // Warm Library actions and the expressly selected PDF page require a fresh owner ticket.
                    val owner = if (draftCommand == "drafts.list" && args.has("reenterVisit") || draftCommand == "drafts.resumeSaved" ||
                        draftCommand == "drafts.retainPdfPage" || draftCommand == "drafts.checkPdfPage") freshLibraryEntry() else null
                    attachmentPages.dispatchStandaloneDraft(args, owner) { result, error ->
                        complete(id, result, error, retainValue = false,
                            replayHint = "请回本机文档草稿核对同一版本；不会重复保存。")
                        publishState()
                    }
                    return
                }
                // Only a no-locator open starts a Library visit. An explicit query may use the same owner check,
                // but does not start or restore a draft lease.
                val libraryEntry = if (action == "attachmentWorkspace.open" && args.length() == 0 ||
                    action == "attachmentWorkspace.action" && draftCommand == "queryDocument") freshLibraryEntry() else null
                if (action == "attachmentWorkspace.open" && (args.has("messagePdf") || args.has("messageInputDocument"))) {
                    val key=if(args.has("messagePdf"))"messagePdf" else "messageInputDocument"
                    val kind=if(key=="messagePdf")AttachmentMessageInput.PDF else AttachmentMessageInput.WORD_OR_TEXT
                    checkedPayload(args,setOf(key))
                    val source=checkedMessageAttachmentSource(args.getJSONObject(key))
                    val unavailable=if(kind==AttachmentMessageInput.PDF)"原消息 PDF 或版本已变化，请返回原消息"
                        else "提问所附原件或原答复已变化，请返回原消息"
                    val original=visibleMessageAttachmentSelection(source,localInput=kind)?:error(unavailable)
                    val originalDocument=documentId?:error("原消息页面已关闭")
                    // Unlike the generic ticket, this existing navigation-only ticket never follows a sibling Phone execution.
                    val ticket=PocketWebWindowTicket(commandFence.ticket(),controller.webPhoneViewerWindowTicket())
                    val reader=controller.conversationReaderRevision
                    val sourceRevision=controller.messageAttachmentSourceRevision
                    val draft=controller.composerDraft?:error("原对话已关闭")
                    val composerKey=draft.key
                    val composerEpoch=controller.composerViewEpoch
                    fun current()=!closed&&documentId==originalDocument&&ticket.accepts(commandFence.ticket(),controller.webPhoneViewerWindowTicket())&&
                        controller.conversationReaderRevision==reader&&controller.messageAttachmentSourceRevision==sourceRevision&&
                        controller.composerViewEpoch==composerEpoch&&controller.composerDraft?.key==composerKey&&
                        visibleMessageAttachmentSelection(source,localInput=kind,inOwnReader=true)==original
                    complete(id,attachmentPages.openMessageInput(original.file,kind,ticket,::current),null,retainValue=false)
                    publishState();return
                }
                if (action == "attachmentWorkspace.open" && args.has("replyDocument")) {
                    checkedPayload(args,setOf("replyDocument"))
                    val source=checkedMessageAttachmentSource(args.getJSONObject("replyDocument"))
                    check(!source.has("taskId")&&!source.has("window")){"请从当前原答复打开文档"}
                    val message=requiredString(source,"messageId")
                    val revision=requiredLong(source,"sourceRevision")
                    val fileId=requiredString(source,"id");val sha=requiredString(source,"sha256")
                    val conversation=requiredString(source,"conversationId")
                    fun selected(allowOwn:Boolean,allowSummary:Boolean)=controller.state.messages.singleOrNull{it.id==message&&it.sourceRevision==revision}
                        ?.let{liveReplyDocuments(it,inOwnReader=allowOwn,allowSummary=allowSummary)}
                        ?.singleOrNull{it.target.conversation()==conversation&&it.file.id==fileId&&it.file.sha256==sha}
                    val original=selected(false,true)?:error("原答复文档已变化；请回原工作核对")
                    val originalDocument=documentId?:error("原答复页面已关闭")
                    val ticket=PocketWebWindowTicket(commandFence.ticket(),controller.webPhoneViewerWindowTicket())
                    val reader=controller.conversationReaderRevision
                    val sourceRevision=controller.messageAttachmentSourceRevision
                    val composerKey=controller.composerDraft?.key?:error("原对话已关闭")
                    val composerEpoch=controller.composerViewEpoch
                    fun current()=!closed&&documentId==originalDocument&&ticket.accepts(commandFence.ticket(),controller.webPhoneViewerWindowTicket())&&
                        controller.conversationReaderRevision==reader&&controller.messageAttachmentSourceRevision==sourceRevision&&
                        controller.composerViewEpoch==composerEpoch&&controller.composerDraft?.key==composerKey&&
                        original.matches(selected(true,original.checkFirst))
                    complete(id,attachmentPages.openReplyDocument(original.file,original.target,ticket,::current),null,retainValue=false)
                    publishState();return
                }
                if (action == "attachmentWorkspace.open" && args.has("messageAttachment")) {
                    checkedPayload(args, setOf("messageAttachment"))
                    val file = visibleMessageAttachment(checkedMessageAttachmentSource(args.getJSONObject("messageAttachment")))
                        ?: error("原消息附件或版本已变化，请在原位置核对")
                    val fileArgs = JSONObject().put("fileId", file.id).put("sha256", file.sha256)
                    attachmentPages.dispatch(action, fileArgs) { result, error -> complete(id, result, error); publishState() }
                    return
                }
                val reportRun = if (action == "attachmentWorkspace.open" && (args.has("expectedRunId") || args.has("expectedEpoch")))
                    visibleReportFileRun(args) else null
                val conversationResult = if (action == "attachmentWorkspace.open" && reportRun == null) visibleChatTaskResult(args) else null
                val resultRun = reportRun ?: conversationResult?.let { AttachmentResultRun(it.taskId, it.runId, it.epoch) }
                val readerCurrent: (() -> Boolean)? = if (reportRun != null || conversationResult != null && args.has("resultReaderWindow")) {
                    val ticket = captureWindow()
                    val current: () -> Boolean = { !closed && acceptsWindow(ticket) &&
                        (if (reportRun != null) currentReportFileRun(reportRun)
                        else runCatching { visibleChatTaskResult(args) }.getOrNull() == conversationResult) }
                    current
                } else null
                if (action == "attachmentWorkspace.open" && args.optBoolean("openOriginal") && resultRun == null) {
                    require(section == null && taskViewId == requiredString(args, "taskId")) { "请从原工作打开这份成果" }
                }
                val attachmentArgs = if (reportRun != null) JSONObject().put("taskId", reportRun.taskId)
                else if (conversationResult == null) args else JSONObject(args.toString()).apply {
                    remove("resultMessageId"); remove("conversationId"); remove("resultReaderWindow"); remove("resultSourceRevision")
                }
                val readerScoped = readerCurrent != null || attachmentPages.hasScopedResult
                attachmentPages.dispatch(action, attachmentArgs, resultRun, readerCurrent, restoreWork = { entry, checked, dismiss ->
                    fun current() = section == null && taskViewId == null && checked()
                    check(current()) { "已有其他工作窗口，没有替换；可以关闭文件后回原入口核对" }
                    openOriginalTask(id, entry.run.taskId, requestNavigation = true, expectedRunId = entry.run.runId,
                        expectedEpoch = entry.run.epoch, sourceWindowCurrent = ::current, checkReaderInput = { task ->
                            check(current() && entry.matchesTask(task.raw)) { "原工作轮次已变化，未打开其他工作" }
                            dismiss()
                        })
                }, draftLibraryEntry = libraryEntry) { result, error ->
                    complete(id, result, error, retainValue = !readerScoped); publishState()
                }; return
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
                var memoryArgs = args
                var historyAnswer: (() -> JSONObject)? = null
                if (action == "memoryHistory.open") {
                    if (args.optString("view") == "answer") {
                        require(section == null && taskViewId == null && controller.webPrivateOwnerVisible()) { "请在原私聊回答下核对本次提供的记忆" }
                        val history = setOf("readerWindow", "readerConversationId", "readerSourceRevision")
                        if (history.any { args.has(it) }) {
                            checkedPayload(args, history + setOf("view", "messageId"))
                            val message = requiredString(args, "messageId")
                            val window = captureWindow()
                            historyAnswer = memoryHistory.historyAnswer(requiredLong(args, "readerWindow"), requiredString(args, "readerConversationId"),
                                message, requiredLong(args, "readerSourceRevision")) {
                                !closed && section == null && taskViewId == null && acceptsWindow(window)
                            }
                            memoryArgs = JSONObject().put("view", "answer").put("messageId", message)
                        }
                    } else require(section == "memory" && taskViewId == null) { "请从原记忆管理页选择要查阅的范围" }
                }
                memoryHistory.dispatch(action, memoryArgs, historyAnswer) { result, error -> completePrivateMemoryReply(id, action, result, error); publishState() }; return
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
            if (action == "tasks.open" && args.has("commitmentViewHandle")) {
                checkedPayload(args, setOf("commitmentViewHandle"))
                val handle = requiredString(args, "commitmentViewHandle")
                val window = captureWindow()
                fun current() = !closed && acceptsWindow(window) && section == "commitments" &&
                    taskViewId == null && taskReturnSection == null && controller.webPrivateOwnerVisible()
                check(current()) { "请在原私人单项委托中打开关联工作" }
                commitmentPages.resolveLinkedWork(handle, ::current) { original, error ->
                    if (closed) return@resolveLinkedWork
                    if (original == null || error != null || !current()) {
                        complete(id, null, error ?: "原委托已离开，未打开其他工作"); return@resolveLinkedWork
                    }
                    val verify: (JSONObject) -> Unit = { raw ->
                        check(current() && commitmentPages.holdsLinkedWork(handle, original)) { "原委托阅读已变化，未打开其他工作" }
                        commitmentPages.requireLinkedWork(original, raw)
                    }
                    openOriginalTask(id, original.link.taskId, true, "commitments",
                        expectedRunId = original.runId, expectedEpoch = original.epoch, sourceWindowCurrent = ::current,
                        verifySourceBeforeMerge = verify, checkReaderInput = { verify(it.raw) })
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
                checkedPayload(args, setOf("id", "requestNavigation", "readerWindow", "readerConversationId", "readerMessageId", "searchQuery", "expectedRunId", "expectedEpoch", "expectedProjectId", "expectedCheckoutId", "expectedExternalKind", "teamPlanView"))
                val taskId = requiredString(args, "id")
                val fromReader = args.has("readerWindow") || args.has("readerConversationId") || args.has("readerMessageId")
                require(args.has("expectedRunId") == args.has("expectedEpoch")) { "原整理记录身份不完整" }
                val expectedRunId = if (args.has("expectedRunId")) requiredString(args, "expectedRunId") else null
                if (args.has("expectedExternalKind")) {
                    val kind = requiredString(args, "expectedExternalKind")
                    require(kind in setOf("remote", "teams", "ssh") && expectedRunId == taskId && requiredInt(args, "expectedEpoch") == 0 &&
                        !fromReader && !args.has("searchQuery") && !args.has("expectedProjectId") && !args.has("expectedCheckoutId") &&
                        !args.has("teamPlanView") && args.has("requestNavigation") && !requiredBoolean(args, "requestNavigation") && controller.webPrivateOwnerVisible()) { "原本机工作身份无效" }
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
                if (args.has("teamPlanView")) {
                    fun sameTeamWindow() = section == "teams" && taskViewId == null && controller.conversationReader == null &&
                        controller.webPrivateOwnerVisible()
                    require(args.length() == 4 && expectedRunId != null && expectedEpoch != null && sameTeamWindow()) {
                        "请从本次原团队的同一分工打开"
                    }
                    val verify = teamPages.planWorkOpenVerifier(requiredString(args, "teamPlanView"), taskId, expectedRunId, expectedEpoch)
                    openOriginalTask(id, taskId, true, returnSection = "teams", expectedRunId = expectedRunId, expectedEpoch = expectedEpoch,
                        sourceWindowCurrent = ::sameTeamWindow, verifySourceBeforeMerge = verify, checkReaderInput = { verify(it.raw) })
                    return
                }
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
                    if (row.role == "user" && row.hostGoalTaskId == null) {
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
                    preview?.opt("localWebWork") != true &&
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
                checkedPayload(args, setOf("taskId", "runId", "epoch", "clientMessageId", "message", "mode", "key", "draftEpoch", "revision", "expectedAttachments"))
                val draft = controller.composerDraft ?: error("原草稿尚未读取，未发送")
                require(documentId != null && !phoneComposer.intent && !controller.hasChannelPhoneOrigin &&
                    requiredString(args, "key") == draft.key && requiredInt(args, "draftEpoch") == controller.composerViewEpoch) {
                    "原私人输入窗口已变化，未提交补充"
                }
                requireAttachmentDraft(args)
                controller.sendOwnerWorkInput(args, requiredString(args, "clientMessageId"), requiredString(args, "message", true),
                    requiredString(args, "mode"), args.getJSONArray("expectedAttachments")) { result, error ->
                    complete(id, result, error, "outcome_unknown"); publishState()
                }
                return
            }
            if (action.startsWith("ownerWork.supplement.")) {
                val identityFields=setOf("taskId","runId","epoch","conversationId")
                val operation=action.removePrefix("ownerWork.supplement.")
                val copy=operation=="edit"&&args.has("copyToCurrent")
                val fields=when(operation){
                    "read"->identityFields
                    "edit"->identityFields+(if(copy)setOf("key","revision","copyToCurrent") else setOf("key","revision","text","mode"))
                    "send"->identityFields+setOf("key","revision","clientMessageId")
                    "query"->identityFields+setOf("key","clientMessageId")
                    else->error("工作补充动作不可用")
                }
                checkedPayload(args,fields)
                val expected=JSONObject().put("taskId",requiredString(args,"taskId")).put("runId",requiredString(args,"runId"))
                    .put("epoch",requiredInt(args,"epoch")).put("conversationId",requiredString(args,"conversationId"))
                require(section==null&&taskViewId==expected.getString("taskId")&&controller.webPrivateOwnerVisible()) { "请在原工作详情中核对这份补充" }
                if(copy){
                    val target=args.getJSONObject("copyToCurrent")
                    checkedPayload(target,setOf("runId","epoch","revision"))
                    require(taskReturnSection==null&&controller.conversationReader==null) { "请在原普通工作核对当前轮草稿" }
                    val doc=requireNotNull(documentId);val task=expected.getString("taskId")
                    val fence=commandFence.ticket();val owner=controller.displayedPhoneWindowVersion()
                    val reader=controller.conversationReaderRevision
                    // Safe on the worker: do not call the display synchronizer under the task monitor.
                    fun current()=!closed&&documentId==doc&&commandFence.accepts(fence)&&taskViewId==task&&
                        controller.displayedPhoneWindowAlive(owner)&&controller.conversationReaderRevision==reader
                    controller.copyOwnerWorkSupplement(expected,requiredString(args,"key"),requiredLong(args,"revision"),
                        requiredString(target,"runId"),requiredInt(target,"epoch"),requiredLong(target,"revision"),::current){ result,error ->
                        val shown=current()&&section==null&&controller.webPrivateOwnerVisible()
                        complete(id,if(shown)result else null,if(shown)error else "原窗口已关闭，请在原工作核对草稿",
                            "operation_failed",retainValue=false,replayHint="请从原工作核对复制的草稿；没有发送补充")
                        publishState()
                    }
                    return
                }
                val done:(JSONObject?,String?)->Unit={result,error->complete(id,result,error,if(operation=="send")"outcome_unknown" else "operation_failed");publishState()}
                when(operation){
                    "read"->controller.readOwnerWorkSupplement(expected,done)
                    "edit"->controller.editOwnerWorkSupplement(expected,requiredString(args,"key"),requiredLong(args,"revision"),requiredString(args,"text",true),requiredString(args,"mode"),done)
                    "send"->controller.sendOwnerWorkSupplement(expected,requiredString(args,"key"),requiredLong(args,"revision"),requiredString(args,"clientMessageId"),done)
                    "query"->controller.queryOwnerWorkSupplement(expected,requiredString(args,"key"),requiredString(args,"clientMessageId"),done)
                }
                return
            }
            if (action == "conversation.reader.open" && args.has("resume")) {
                require(args.keys().asSequence().toSet()==setOf("conversationId","resume")&&requiredBoolean(args,"resume"))
                val conversation=requiredString(args,"conversationId")
                val epoch=ordinaryDetachedHistoryEpoch(conversation)?:error("请回到原私聊浏览上次位置")
                val window=captureWindow();val document=requireNotNull(documentId);val foreground=controller.displayedPhoneWindowVersion()
                fun bodyCurrent()=!closed&&documentId==document&&commandFence.accepts(window.bridge)&&taskViewId==null&&
                    controller.displayedPhoneWindowAlive(foreground)&&controller.composerViewEpochForScopedRead==epoch
                fun current()=bodyCurrent()&&acceptsWindow(window)&&ordinaryDetachedHistoryEpoch(conversation)==epoch
                require(current()&&controller.conversationReader==null&&controller.savedDetachedHistory(conversation)) {
                    "原阅读位置暂不可核对，可以浏览最新记录"
                }
                controller.resumeDetachedHistory(conversation,epoch,::current,::bodyCurrent){value,error->
                    complete(id,value,error,retainValue=false,replayHint="请从同一原私聊重新核对阅读位置");publishState()
                }
                return
            }
            if (action == "conversation.reader.open" && args.has("searchLocator")) {
                require(args.keys().asSequence().toSet()==setOf("conversationId","searchLocator","requestId"))
                val conversation=requiredString(args,"conversationId");val request=requiredString(args,"requestId")
                require(conversation.length<=240&&runCatching { UUID.fromString(request).toString()==request }.getOrDefault(false))
                val locator=JSONObject(args.getJSONObject("searchLocator").toString());val ticket=captureWindow();val document=documentId
                fun current()=!closed&&document!=null&&documentId==document&&acceptsWindow(ticket)&&section==null&&taskViewId==null&&
                    controller.webPrivateOwnerVisible()&&!controller.hasChannelPhoneOrigin&&!phoneComposer.intent&&controller.state.filePath==null&&
                    !controller.state.chatAttachmentPickerOpen&&controller.queuedInputsPage==null&&!controller.attachmentOutcomes.state.open&&
                    controller.state.conversationId==conversation
                require(current()) { "请从当前原私聊定位搜索消息" }
                controller.openOriginalSearchHistory(conversation,locator,request,::current){value,error->
                    complete(id,value,error,retainValue=false,replayHint="请从原搜索消息重新定位");publishState()
                }
                return
            }
            if (action == "conversation.reader.open" && args.has("taskId")) {
                checkedPayload(args,setOf("taskId","runId","epoch"))
                val task=requiredString(args,"taskId");val ticket=captureWindow();val document=documentId
                fun current()=!closed&&document!=null&&documentId==document&&acceptsWindow(ticket)&&section==null&&taskViewId==task&&controller.webPrivateOwnerVisible()
                require(current()) { "请在当前原工作下查看原请求" }
                controller.openOwnerWorkRequestHistory(task,requiredString(args,"runId"),requiredInt(args,"epoch"),::current){value,error->
                    complete(id,value,error,retainValue=false,replayHint="请在原工作重新打开原请求");publishState()
                }
                return
            }
            if(action=="ownerWork.report.followUp.prepare") {
                checkedPayload(args,setOf("handle"))
                val offer=reportDraftOffer ?: error("请重新打开原汇报")
                fun current()=!closed&&documentId==offer.document&&acceptsWindow(offer.window)&&section==null&&
                    taskViewId==offer.task&&taskReturnSection==null&&!phoneComposer.intent&&controller.webPrivateOwnerVisible()
                require(offer.handle==requiredString(args,"handle")&&current()) { "请在这份原汇报中另写追问" }
                reportDraftOffer=null
                controller.prepareIndependentReportFollowUp(offer.task,offer.proof,::current){value,error->
                    val key=controller.currentIndependentReportDraftKey()
                    if(value?.opt("prepared")==true&&error==null&&current()&&key!=null){
                        val handoff=ReportDraftHandoff(UUID.randomUUID().toString(),offer,key)
                        reportDraftHandoff=handoff
                        value.put("handoff",handoff.handle)
                    }
                    complete(id,value,error,retainValue=false,replayHint="请核对导航里的未发追问");publishState()
                }
                return
            }
            if(action=="ownerWork.report.followUp.discardEmpty") {
                checkedPayload(args,setOf("key","epoch","revision"))
                fun current()=!closed&&documentId!=null&&section==null&&taskViewId==null&&!phoneComposer.intent&&controller.webPrivateOwnerVisible()
                controller.discardEmptyIndependentReportDraft(requiredString(args,"key"),requiredInt(args,"epoch"),
                    requiredLong(args,"revision"),::current){value,error->
                    complete(id,value,error,retainValue=false,replayHint="请核对当前草稿");publishState()
                }
                return
            }
            if(action=="ownerWork.report.followUp.resume") {
                checkedPayload(args,setOf("handoff"))
                val window=captureWindow();val doc=documentId
                val handoff=if(args.has("handoff"))reportDraftHandoff?.takeIf {
                    it.handle==requiredString(args,"handoff")&&it.closedWindow==window&&
                        it.offer.document==doc&&controller.currentIndependentReportDraftKey()==it.draft
                } ?: error("原追问的返回已结束，请从导航继续") else null
                fun current()=!closed&&doc!=null&&documentId==doc&&acceptsWindow(window)&&section==null&&taskViewId==null&&
                    !phoneComposer.intent&&controller.webPrivateOwnerVisible()&&
                    (handoff==null||controller.currentIndependentReportDraftKey()==handoff.draft)
                require(current()) { "请回到私人对话再继续这份草稿" }
                reportDraftHandoff=null
                controller.resumeIndependentReportFollowUp(::current){value,error->
                    complete(id,value,error,retainValue=false,replayHint="请核对当前追问草稿");publishState()
                }
                return
            }
            if (action == "ownerWork.report.history") {
                checkedPayload(args,setOf("taskId","runId","epoch","reportMessageId"))
                val task=requiredString(args,"taskId");val ticket=captureWindow();val document=documentId
                fun current()=!closed&&document!=null&&documentId==document&&acceptsWindow(ticket)&&section==null&&taskViewId==task
                controller.openOwnerReportHistory(task,requiredString(args,"runId"),requiredInt(args,"epoch"),requiredString(args,"reportMessageId"),::current){value,error->
                    complete(id,value,error,retainValue=false,replayHint="请从原工作重新定位这份汇报");publishState()
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
            if (action == "phone.result.taskOriginal") {
                val publishedMessage = args.has("publishedMessage")
                val fields = setOf("taskId", "runId", "epoch", "expectedDigest")
                checkedPayload(args, fields + if (publishedMessage) "publishedMessage" else "offset")
                if (publishedMessage) require(requiredBoolean(args, "publishedMessage"))
                val task = requiredString(args, "taskId"); val window = captureWindow(); val document = documentId
                val current = { !closed && section == null && taskViewId == task && document != null && document == documentId && acceptsWindow(window) }
                require(current() && controller.webPrivateOwnerVisible()) { "请在原手机工作中查看这轮回执" }
                controller.readPhoneTaskOriginalResult(task, requiredString(args, "runId"), requiredInt(args, "epoch"),
                    if (publishedMessage) 0 else requiredInt(args, "offset"),
                    requiredString(args, "expectedDigest", empty = !publishedMessage), publishedMessage, current) {
                    value, error -> complete(id, value, error, retainValue = false)
                }; return
            }
            if (action == "ownerWork.teamReport.read") {
                readOriginalChatTeamReport(id, args)
                return
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
            if (action in PocketWebOwnerQuestions.coldCommands) {
                val save = action == PocketWebOwnerQuestions.SAVE_COLD
                checkedPayload(args, setOf("reviewHandle", if (save) "optionId" else "confirmed"))
                val option = if (save) requiredString(args, "optionId") else null
                if (!save) require(requiredBoolean(args, "confirmed")) { "请单独确认用原回答继续这件工作" }
                val selected = taskViewId ?: error("原任务窗口已关闭")
                val current = coldOwnerQuestionWindow(selected, sourceStrict = false)
                require(current()) { "请在原私人工作核对这个问题" }
                val (task, review) = reviews.takeColdQuestion(requiredString(args, "reviewHandle"), action, controller.state.tasks)
                require(task.id == selected && canUseTaskControls(task) && PocketWebOwnerQuestions.isOrdinaryOwnerWorkWait(task.raw))
                if (option != null) PocketWebOwnerQuestions.prepare(task.raw, PocketWebOwnerQuestions.CHOOSE, JSONObject().put("optionId", option))
                controller.submitColdOwnerQuestion(task, review, option, current) { result, error ->
                    if (!current() || error != null || result == null)
                        complete(id, null, error ?: "原问题窗口已变化，请重新核对", "outcome_unknown", retainValue = false)
                    else runCatching { if (option == null) result else PocketWebOwnerQuestions.coldSaveReceipt(task.raw, option, result) }
                        .fold({ complete(id, it, null, retainValue = false) },
                            { complete(id, null, "原问题回执未确认，请重新核对", "outcome_unknown", retainValue = false) })
                    publishState()
                }
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
            if (action == "tasks.confirm" && args.has("usingTeamReport")) {
                submitOwnerWorkTeamReport(id, args)
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
                checkedPayload(args, setOf("id", "approvalId", "teamReport"))
                val taskId = requiredString(args, "id")
                if (args.has("teamReport")) {
                    require(!args.has("approvalId"))
                    readOwnerWorkTeamReport(id, taskId, args.getJSONObject("teamReport"))
                    return
                }
                val approvalId = if (args.has("approvalId")) requiredString(args, "approvalId").also { require(it.length <= 128) } else null
                require(taskViewId == taskId && controller.webUiCanInteract()) { "请重新打开这个原任务的审阅窗口" }
                requireTask(taskId)
                teamReportReviewGeneration++
                val generation = taskReviewGeneration
                val reportSelection = teamReportSelectionGeneration
                val document = documentId
                val navigation = Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
                taskActions.read(taskId, approvalId, { closed }, {
                    taskViewId == taskId && controller.webUiCanInteract() && generation == taskReviewGeneration &&
                        reportSelection == teamReportSelectionGeneration && document == documentId &&
                        navigation == Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
                }, coldOwnerQuestionWindow(taskId)) { result, error ->
                    val specialQuestion = result?.optJSONArray("actions").objects().any { it.optString("action") in PocketWebOwnerQuestions.coldCommands }
                    complete(id, result, error, retainValue = !specialQuestion && result?.has("capabilityApprovalRead") != true && result?.has("savedTeamDefaultModel") != true)
                    if (error == null) publishState()
                }
                return
            }
            if (action == "models.snapshot") {
                when {
                    args.has("originalHomeInput") -> readOriginalHomeModelInput(id, args)
                    args.has("originalConversationInput") -> readOriginalConversationModelInput(id, args)
                    args.has("originalPhoneInput") -> readOriginalPhoneModelInput(id, args)
                    else -> readModelSnapshot(id)
                }
                return
            }
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
            if (action == "composer.readSavedFirst") {
                checkedPayload(args,setOf("clientMessageId","key","epoch","revision"))
                val ticket=captureWindow();val document=documentId
                fun current()=!closed && document!=null && documentId==document && acceptsWindow(ticket) &&
                    section==null && taskViewId==null && !phoneComposer.intent && controller.conversationReader==null && controller.webPrivateOwnerVisible()
                controller.readSavedFirstChatSource(requiredString(args,"clientMessageId"),requiredString(args,"key"),
                    requiredInt(args,"epoch"),requiredLong(args,"revision"),::current){value,error->
                    if(current())complete(id,value,error,retainValue=false,replayHint="请返回原输入重新核对保存记录")
                    else complete(id,null,"原阅读窗口已关闭，没有打开其他内容。",retainValue=false)
                    publishState()
                }
                return
            }
            if (action == "composer.releasePreparedFirst") {
                checkedPayload(args,setOf("clientMessageId","key","epoch","revision"))
                val ticket=captureWindow();val document=documentId
                fun current()=!closed && document!=null && documentId==document && acceptsWindow(ticket) &&
                    section==null && taskViewId==null && !phoneComposer.intent && controller.conversationReader==null && controller.webPrivateOwnerVisible()
                controller.releasePreparedFirstChat(requiredString(args,"clientMessageId"),requiredString(args,"key"),
                    requiredInt(args,"epoch"),requiredLong(args,"revision"),::current){value,error->
                    if(current())complete(id,value,error,retainValue=false,replayHint="请核对当前原稿，没有自动发送")
                    else complete(id,null,"原输入窗口已关闭，请在原草稿核对。",retainValue=false)
                    publishState()
                }
                return
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
                if (action == "memoryDraft.open" && (args.has("messageId") || args.has("sourceRevision"))) {
                    checkedPayload(args, setOf("messageId", "sourceRevision"))
                    val message = requiredString(args, "messageId")
                    require(args.opt("sourceRevision") == 1) { "请从原版本回答打开记忆提案" }
                    val conversation = controller.state.conversationId ?: error("请先打开原私人对话")
                    val document = documentId; val window = captureWindow(); val reader = controller.conversationReaderRevision
                    val channel = controller.phoneOriginRevision
                    fun displayedReply() = controller.state.messages.singleOrNull {
                        it.role == "assistant" && it.id == message && it.sourceRevision == 1L &&
                            it.replyContext?.originalMemoryDraftRecorded == true
                    }?.replyContext
                    val shown = displayedReply() ?: error("这条回答没有可核对的原记忆提案")
                    val reply = shown.replyId ?: error("未能核对原私人回答")
                    fun current(): Boolean {
                        val state = controller.state
                        return !closed && document != null && documentId == document && acceptsWindow(window) &&
                            controller.webPrivateOwnerVisible() && section == null && taskViewId == null && taskReturnSection == null &&
                            state.conversationId == conversation && state.filePath == null && !phoneComposer.intent &&
                            !controller.hasChannelPhoneOrigin && controller.phoneOriginRevision == channel &&
                            controller.conversationReader == null && controller.conversationReaderRevision == reader &&
                            controller.queuedInputsPage == null && !state.chatAttachmentPickerOpen && !controller.attachmentOutcomes.state.open &&
                            controller.composerDraft?.let { it.key == "conversation:$conversation" && it.conversationId == conversation } == true &&
                            displayedReply() == shown
                    }
                    check(current()) { "请回到这条回答所在的原私人对话" }
                    val source = PocketWebMemoryDrafts.OriginalChatReply(::current) {
                        controller.nativeButlerHost().tasks.originalReplyMemoryDraftForOwner(
                            conversation, message, 1L, shown.taskId, shown.runId, shown.epoch, reply)
                    }
                    memoryDrafts.dispatch(action, args, true, source) { result, error ->
                        if (document == documentId) {
                            if (current()) completePrivateMemoryReply(id, action, result, error)
                            else completePrivateMemoryReply(id, action, null, "原私人回答窗口已变化，没有打开其他提案")
                        }
                        publishState()
                    }; return
                }
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
            if (action == "devices.queryHistory" && args.has("originalWindowsText")) {
                readOriginalWindowsText(id, args)
                return
            }
            if (action in PocketWebReceivedTextResult.COMMANDS) {
                receivedTextResult.dispatch(action,args) { value,error ->
                    complete(id,value,error,retainValue=false)
                    publishState()
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
                if (args.has("savedPeerWorkHandle")) {
                    val selected=devicePages.savedPeerWorkForNavigation(args)
                    val sourceCurrent={
                        section=="devices" && taskViewId==null &&
                            runCatching { devicePages.savedPeerWorkForNavigation(args,verifyIdentity=false)==selected }.getOrDefault(false)
                    }
                    val verifySource:(JSONObject)->Unit={ raw ->
                        require(sourceCurrent() && devicePages.savedPeerWorkForNavigation(args)==selected &&
                            dev.pocketengine.store.TaskStoreDeviceTasks.matchesSavedPeerWorkspaceWork(raw,selected.task,selected.run,selected.epoch,selected.peer)) { "原设备或这份原工作已变化，未打开其他记录" }
                    }
                    openOriginalTask(id,selected.task,true,"devices",expectedRunId=selected.run,expectedEpoch=selected.epoch,
                        sourceWindowCurrent=sourceCurrent,verifySourceBeforeMerge=verifySource,checkReaderInput={ verifySource(it.raw) })
                    return
                }
                val taskId = devicePages.taskForNavigation(args)
                if (args.has("savedDeviceChoice")) {
                    val deviceRevision = controller.deviceWorkspaceRevision
                    val sourceCurrent = { section == "devices" && taskViewId == null && controller.deviceWorkspaceRevision == deviceRevision &&
                        runCatching { devicePages.taskForNavigation(args) == taskId }.getOrDefault(false) }
                    openOriginalTask(id, taskId, true, "devices", sourceWindowCurrent = sourceCurrent,
                        checkReaderInput = { source ->
                            require(sourceCurrent()) { "原设备工作窗口已变化" }
                            devicePages.checkSavedDeviceChoice(args, source.raw)
                        })
                } else if (args.has("manualPhoneTarget")) {
                    val sourceCurrent = { section == "devices" && taskViewId == null && runCatching { devicePages.taskForNavigation(args) == taskId }.getOrDefault(false) }
                    openOriginalTask(id, taskId, true, "devices", sourceWindowCurrent = sourceCurrent,
                        checkReaderInput = { source -> devicePages.checkManualPreparationForNavigation(args, source.raw) })
                } else if (args.has("archiveHandle") || args.has("sourceViewHandle")) {
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
                        "devices.readReviewedPhoneReceipt", "devices.readSavedPeerWork" -> true // Native page selections are never replayed.
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
                if (action == "plans.read" && args.has("originalStep")) {
                    checkedPayload(args, setOf("originalStep"))
                    require(args.opt("originalStep") == true)
                    val task = requireNotNull(taskViewId)
                    val doc = requireNotNull(documentId)
                    val window = captureWindow()
                    val foreground = controller.displayedPhoneWindowVersion()
                    fun bodyCurrent() = !closed && documentId == doc && commandFence.accepts(window.bridge) &&
                        taskViewId == task && controller.displayedPhoneWindowAlive(foreground)
                    fun shown(): TaskStore.OriginalPrivatePlanStep? {
                        val state = controller.state
                        if (!bodyCurrent() || !acceptsWindow(window) || section != null || taskReturnSection != null ||
                            !controller.webPrivateOwnerVisible() || controller.conversationReader != null ||
                            controller.queuedInputsPage != null || state.filePath != null || state.chatAttachmentPickerOpen ||
                            controller.attachmentOutcomes.state.open) return null
                        val raw = state.tasks.singleOrNull { it.id == task }?.raw ?: return null
                        if (raw.opt("taskProjection") != "detail_window" || raw.opt("auditBodyIncluded") != true ||
                            raw.opt("taskCoreReadLimited") != false) return null
                        return TaskStore.originalPrivatePlanStep(raw)?.takeIf { it.taskId() == task }
                    }
                    val original = shown() ?: error("请在原私人工作中核对所属目标")
                    controller.runPluginCatalogWork(Runnable {
                        val result = runCatching { controller.nativeButlerHost().readOriginalPlanStepContext(original) { bodyCurrent() } }.getOrNull()
                        main.post {
                            if (documentId != doc) return@post
                            if (shown() != original || result == null) complete(id, null, "暂未核对到这一步所属的原目标。", retainValue = false)
                            else complete(id, PocketWebState.publicValue(result) as JSONObject, null, retainValue = false)
                        }
                    })
                    return
                }
                val originalReminderRead = action == "plans.reviewSchedule" && args.has("messageId")
                val originalReminderConfirm = action == "plans.confirm" && args.has("originalChatReminder")
                if (originalReminderRead || originalReminderConfirm) {
                    val document = documentId
                    val bridge = commandFence.ticket()
                    val owner = controller.displayedPhoneWindowVersion()
                    val reader = controller.conversationReaderRevision
                    val channel = controller.phoneOriginRevision
                    val composer = controller.composerViewEpoch
                    fun current(): Boolean {
                        val shown = controller.state
                        val conversation = shown.conversationId ?: return false
                        return !closed && document != null && documentId == document && commandFence.accepts(bridge) &&
                            controller.displayedPhoneWindowAlive(owner) && controller.webPrivateOwnerVisible() &&
                            section == null && taskViewId == null && taskReturnSection == null && shown.filePath == null &&
                            !phoneComposer.intent && !controller.hasChannelPhoneOrigin && controller.phoneOriginRevision == channel &&
                            controller.conversationReader == null && controller.conversationReaderRevision == reader &&
                            controller.queuedInputsPage == null && !shown.chatAttachmentPickerOpen && !controller.attachmentOutcomes.state.open &&
                            controller.composerViewEpoch == composer &&
                            controller.composerDraft?.let { it.key == "conversation:$conversation" && it.conversationId == conversation } == true
                    }
                    check(current()) { "请在原私人对话核对这条回复的提醒" }
                    val replay = "请重新核对原回复的提醒；没有重新保存"
                    val done: (JSONObject?, String?) -> Unit = { value, error ->
                        if (document == documentId) {
                            if (current()) complete(id, value, error, "outcome_unknown", retainValue = false, replayHint = replay)
                            else complete(id, null, "原私人提醒窗口已变化，请重新核对原回复", retainValue = false, replayHint = replay)
                        }
                    }
                    if (originalReminderRead) planPages.readOriginalReminder(args, ::current, done)
                    else planPages.confirmOriginalReminder(args, ::current, done)
                    return
                }
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
                requireSection("teams")
                val restoringTeamReading = action == "teams.snapshot" && (args.has(PocketWebTeams.RESTORE_MEMBER_RESULT_RUN) || args.has(PocketWebTeams.RESTORE_PLAN_WORK))
                teamPages.dispatch(action, args) { result, error ->
                    val currentTeamSourceRead = action == "teams.readRun" && error == null && result?.optJSONObject("reading")?.let {
                        it.opt(PocketWebTeams.CURRENT_FAILED_AGGREGATE_SOURCE) == true ||
                            it.opt(PocketWebTeams.CURRENT_COMPLETED_TEMPORARY_ANSWERS_SOURCE) == true ||
                            it.opt(PocketWebTeams.CURRENT_FIRST_LOCAL_FROZEN_REVIEW_SOURCE) == true
                    } == true
                    val originalReviewerFindingsRead = action == "teams.reviewFrozenTextApproval" && error == null &&
                        result?.optJSONObject("originalReviewerFindings") != null
                    complete(id, result, error, "outcome_unknown", retainValue = !restoringTeamReading && !currentTeamSourceRead && !originalReviewerFindingsRead &&
                        action !in setOf("teams.readFinalPackageFiles", "teams.chooseFinalPackageTarget", "teams.saveFinalPackageTarget", "teams.discardFinalPackageTarget",
                            PocketWebTeams.REVIEW_INDEPENDENT_FILES, PocketWebTeams.START_INDEPENDENT_FILES))
                    if (action != "teams.readFinalPackageFiles") publishState()
                }
                return
            }
            if (action == "conversation.readPrivateHostFinal") {
                val reader=if(args.has("readerWindow"))requiredLong(args,"readerWindow").also{ require(it>0) } else null
                val revision=if(reader!=null)requiredLong(args,"sourceRevision") else 1L
                val document=documentId
                privateHostFinal.read(args,reader,revision,{
                    !closed && document!=null && documentId==document && section==null && taskViewId==null &&
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
            if(action=="conversation.saveFirstHostRequest"){
                require(args.keys().asSequence().toSet()==setOf("key","epoch","revision"))
                requireComposer(args)
                val ticket=captureWindow();val originalDocument=documentId
                fun shown()=!closed&&originalDocument!=null&&documentId==originalDocument&&acceptsWindow(ticket)&&section==null&&taskViewId==null&&
                    controller.state.filePath==null&&controller.webPrivateOwnerVisible()&&!phoneComposer.intent
                require(shown()){"请在原私人文字输入框保存"}
                val key=requiredString(args,"key")
                savedLocalReturn=null
                controller.saveFirstHostRequest(key,requiredLong(args,"revision"),requiredInt(args,"epoch"),::shown){ result,error->
                    // The controller returns saved_local only after checking the durable original.
                    if(error==null&&result!=null&&result.opt("status")=="saved_local"&&result.opt("selectedOriginal")==false&&shown()){
                        val original=SavedLocalReturn(UUID.randomUUID().toString(),ticket,key,result.getString("conversationId"),result.getString("messageId"),result.getLong("revision"))
                        savedLocalReturn=original;result.put("viewSavedHandle",original.handle)
                    }
                    complete(id,result,error,retainValue=false);publishState()
                }
                return
            }
            if(action=="conversation.select"&&args.has("viewSavedHandle")){
                require(args.keys().asSequence().toSet()==setOf("viewSavedHandle","key","epoch","revision"))
                val original=savedLocalReturn ?: error("这次原话回执已离开，当前草稿保留")
                require(original.handle==requiredString(args,"viewSavedHandle")&&original.key==requiredString(args,"key"))
                val sourceRevision=controller.conversationSourceRevision
                val unavailable="未能核对刚才保存的原话，当前草稿保留。"
                fun current()=savedLocalReturn===original&&acceptsWindow(original.window)&&!phoneComposer.intent&&
                    controller.state.filePath==null&&!controller.state.listening&&!controller.state.chatAttachmentPickerOpen&&
                    controller.conversationSourceRevision==sourceRevision&&runCatching{
                        requireComposer(args);requireAttachmentDraft(args)
                    }.isSuccess
                require(current()){unavailable}
                controller.runPluginCatalogWork(Runnable{
                    val verified=runCatching{
                        check(current())
                        val assistant=controller.nativeButlerHost().features.module("assistant") as? dev.pocketengine.features.assistant.AssistantModule ?: error(unavailable)
                        assistant.reviewSavedFirstHostSource(original.conversation,original.message,original.revision)
                    }.isSuccess
                    main.post{
                        if(!verified||!current()){complete(id,null,unavailable,retainValue=false);return@post}
                        savedLocalReturn=null
                        leaveTask(); leaveConnection(); reviews.clear(); phoneComposer.clearReviews()
                        controller.openConversation(original.conversation)
                        val opened=controller.state.conversationId==original.conversation
                        complete(id,if(opened)JSONObject().put("opened",true).put("conversationId",original.conversation)
                            .put("messageId",original.message).put("revision",original.revision) else null,if(opened)null else unavailable,retainValue=false)
                        publishState()
                    }
                });return
            }
            if(action=="conversation.selectSavedHost"){
                require(args.keys().asSequence().toSet()==setOf("conversationId","messageId","revision","profileId","profileBinding"))
                val conversation=requiredString(args,"conversationId");val message=requiredString(args,"messageId")
                val revision=requiredLong(args,"revision");val profile=requiredString(args,"profileId");val binding=requiredString(args,"profileBinding")
                require(profile.matches(Regex("[A-Za-z0-9_-]{1,80}"))&&binding.matches(Regex("[a-f0-9]{64}")))
                val ticket=captureWindow();val document=documentId
                fun sameSource()=!closed&&document!=null&&documentId==document&&taskViewId==null&&controller.state.filePath==null&&
                    controller.webPrivateOwnerVisible()&&controller.state.conversationId==conversation&&controller.state.messages.any {
                        it.id==message&&it.sourceRevision==revision&&it.firstHostRequestLocal&&it.hostGoalTaskId==null
                    }
                fun inChat()=sameSource()&&section==null&&acceptsWindow(ticket)&&!phoneComposer.intent
                require(inChat()){ "请在本机保存的原话选择主机" }
                controller.runPluginCatalogWork(Runnable {
                    val selected=runCatching {
                        connectionPages.hostGoalOrigins.captureSaved(conversation,message,revision,profile,binding,::inChat){
                            sameSource()&&(section==null||section=="remote")
                        }
                    }
                    main.post {
                        if(!inChat())complete(id,null,"原话窗口已变化，未联系主机",retainValue=false)
                        else selected.fold({complete(id,it,null,retainValue=false)},{complete(id,null,it.message?:"无法核对原话和所选主机",retainValue=false)})
                    }
                });return
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
                connectionPages.dispatch(action, args) { result, error ->
                    complete(id,result,error,retainValue=result?.opt("savedOriginalHostReview")!=true); publishState()
                }; return
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
            if (action == PocketWebMessageQuotes.READ_DRAFT_SOURCE) {
                val window = captureWindow(); val document = documentId
                val replay = "请在原私人草稿重新核对这段引用"
                fun currentDocument() = !closed && document != null && document == documentId
                fun current() = currentDocument() && acceptsWindow(window) && section == null && taskViewId == null &&
                    !phoneComposer.intent && !controller.hasChannelPhoneOrigin && controller.webPrivateOwnerVisible() &&
                    controller.conversationReader == null && controller.queuedInputsPage == null &&
                    !controller.state.chatAttachmentPickerOpen && !controller.attachmentOutcomes.state.open
                fun unavailable() {
                    if (currentDocument()) complete(id, null, "这段已附原话暂不可核对，未显示其他内容", retainValue = false, replayHint = replay)
                    else pendingCommands.remove(id)
                }
                if (!current()) { unavailable(); return }
                try {
                    messageQuotes.readDraftSource(args, ::current) { value, error ->
                        if (current() && value != null && error == null)
                            complete(id, value, null, retainValue = false, replayHint = replay)
                        else unavailable()
                    }
                } catch (_: Exception) { unavailable() }
                return
            }
            if (action in PocketWebMessageQuotes.COMMANDS) {
                val documentCopy = action in PocketWebMessageQuotes.DOCUMENT_COPY_COMMANDS
                if (documentCopy || action == "messageQuote.open" || action == "messageQuote.stage" || action == "messageQuote.pin" ||
                    action.startsWith("messageShelf.") && action != "messageShelf.close")
                    require(section == null && taskViewId == null && controller.webPrivateOwnerVisible()) { "请回到原私人对话选择原文" }
                val documentWindow: (() -> Boolean)? = if (documentCopy) {
                    val doc = documentId; val page = commandFence.ticket(); val resume = workspaceResumeRevision
                    val conversation = controller.state.conversationId
                    ({
                        !closed && doc != null && documentId == doc && commandFence.accepts(page) && workspaceResumeRevision == resume &&
                            !conversation.isNullOrBlank() && controller.state.conversationId == conversation && section == null && taskViewId == null &&
                            controller.webPrivateOwnerVisible() && controller.conversationReader == null && controller.queuedInputsPage == null &&
                            !controller.attachmentOutcomes.state.open && !controller.state.chatAttachmentPickerOpen && controller.state.filePath == null
                    }).also { check(it()) { "请回到当前私人对话，再保留原文" } }
                } else null
                if (action == PocketWebMessageQuotes.OPEN_SAVED_DOCUMENT) {
                    val conversation = controller.state.conversationId
                    val resume = workspaceResumeRevision
                    val draftKey = controller.composerDraft?.key ?: error("原私人草稿窗口尚未就绪")
                    val draftEpoch = controller.composerViewEpoch
                    check(!phoneComposer.intent && !controller.hasChannelPhoneOrigin && documentWindow?.invoke() == true)
                    val pin = messageQuotes.takeSavedDocument(args)
                    // The quote was retired above. Take a fresh ordinary Library ticket, not its old selection ticket.
                    val library = freshLibraryEntry() ?: error("原私人文件库窗口尚未就绪")
                    val landing = attachmentPages.openQuotedDraft(pin) {
                        library() && controller.state.conversationId == conversation && resume == workspaceResumeRevision &&
                            controller.composerDraft?.key == draftKey && controller.composerViewEpoch == draftEpoch &&
                            controller.queuedInputsPage == null && !phoneComposer.intent && !controller.hasChannelPhoneOrigin
                    }
                    complete(id, landing.put("quoteWindowId", requiredString(args,"windowId")), null, retainValue = false,
                        replayHint = "原草稿已保留，请在本机文档草稿核对；没有重放打开动作。")
                    publishState(); return
                }
                messageQuotes.dispatch(action, args, documentWindow) { result, error ->
                    val local = action == "messageQuote.pin" || action.startsWith("messageShelf.")
                    val hint = when {
                        documentCopy -> "请在本机文档草稿核对；没有重放上次内容或保存动作。"
                        local -> "请重新打开本对话备忘核对；没有重放上次内容或操作"
                        else -> null
                    }
                    complete(id, result, error, retainValue = action != "messageQuote.open" && !local && !documentCopy, replayHint = hint)
                    publishState()
                }; return
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
            if(action=="phone.draft.saveOriginal"||action=="conversation.select"&&args.has("viewSavedPhoneHandle")){
                val document=documentId
                val ticket=captureWindow()
                fun shown()=!closed&&document!=null&&documentId==document&&acceptsWindow(ticket)&&section==null&&taskViewId==null&&
                    controller.state.filePath==null&&controller.webPrivateOwnerVisible()
                if(action=="phone.draft.saveOriginal")
                    phoneComposer.saveFirstOriginal(args,::shown){ result,error -> complete(id,result,error,retainValue=false) }
                else phoneComposer.selectSavedFirstOriginal(args,::shown,{ conversation ->
                    if(!shown()) false
                    else {
                        reviews.clear()
                        controller.openConversation(conversation)
                        controller.state.conversationId==conversation
                    }
                }){ result,error -> complete(id,result,error,retainValue=false) }
                return
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
            if (action == "phone.pause.leavePage") {
                require(args.keys().asSequence().toSet() == setOf("taskId", "runId", "epoch", "pauseRevision", "scopeRevision", "displayHandle"))
                val target = PocketWorldController.MainPausePageReturn(requiredString(args, "taskId"), requiredString(args, "runId"),
                    requiredInt(args, "epoch"), requiredLong(args, "pauseRevision"), requiredLong(args, "scopeRevision"), requiredString(args, "displayHandle"))
                val window = captureWindow(); val document = documentId; val resume = workspaceResumeRevision
                fun current() = mainPausePageWindow(target.taskId) && documentId == document &&
                    resume == workspaceResumeRevision && acceptsWindow(window)
                require(target == publishedMainPausePageReturn && current()) { "请在原工作重新核对这次主屏暂停，未切换或继续。" }
                complete(id, controller.leaveOriginalMainPausedPage(target, ::current), null,
                    retainValue = false, replayHint = "请回这次原暂停手动核对页面切换")
                return
            }
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

    /** The Work variant of the existing receipt read accepts no generic history or stop handle. */
    private fun readOriginalWindowsText(id: String, args: JSONObject) {
        checkedPayload(args, setOf("originalWindowsText", "taskId", "runId", "epoch", "operationId"))
        require(requiredBoolean(args, "originalWindowsText"))
        val task = requiredString(args, "taskId"); val run = requiredString(args, "runId")
        val epoch = requiredInt(args, "epoch"); val operation = requiredString(args, "operationId")
        val original = requireTask(task).raw
        val target = requireNotNull(DirectedWorkspaceTarget.fromTask(original)) { "原工作未固定电脑。" }
        val window = captureWindow(); val document = requireNotNull(documentId)
        val closedMessage = "原工作窗口或执行已变化，未显示原文本。"
        fun current(): Boolean {
            if (closed || documentId != document || section != null || taskViewId != task || !acceptsWindow(window) || !controller.webPrivateOwnerVisible()) return false
            val raw = controller.state.tasks.firstOrNull { it.id == task }?.raw ?: return false
            return raw.opt("id") == task && raw.opt("runId") == run && epoch > 0 && raw.optInt("coordinatorEpoch") == epoch &&
                raw.opt("actorId") == "owner" && raw.opt("spaceId") == "private" && !raw.has("origin") && raw.opt("contextAvailable") != false &&
                !raw.optBoolean("contextWithdrawn") && !raw.optBoolean("sourceCurrentReadRequired") && raw.opt("taskProjection") != "source_unavailable" &&
                runCatching { DirectedWorkspaceTarget.fromTask(raw) == target }.getOrDefault(false)
        }
        require(current()) { closedMessage }
        check(readingOriginalWindowsText.compareAndSet(false, true)) { "正在核对另一份原文本，请等这次回执。" }
        try {
            controller.runDeviceWorkspaceWork(Runnable {
                val result = runCatching {
                    check(current()) { closedMessage }
                    val host = controller.nativeDeviceWorkspace() ?: error("本机设备回执暂不可用。")
                    host.openOwnerWindow().use { it.readOriginalWindowsText(task, run, epoch, operation) { check(current()) { closedMessage } } }
                }
                main.post {
                    readingOriginalWindowsText.set(false)
                    if (!current()) complete(id, null, closedMessage, retainValue = false)
                    else result.fold({ complete(id, it, null, retainValue = false) },
                        { complete(id, null, it.message ?: "没有取得可核对的原文本。", retainValue = false) })
                }
            })
        } catch (failure: Exception) {
            readingOriginalWindowsText.set(false)
            throw failure
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

    private fun replaceOriginalComposerTeam(id:String, args:JSONObject) {
        checkedPayload(args,setOf("key","epoch","revision","fromTeamId","teamId"))
        val key=requiredString(args,"key"); val epoch=requiredInt(args,"epoch"); val revision=requiredLong(args,"revision")
        val source=requiredString(args,"fromTeamId"); val target=requiredString(args,"teamId")
        val uuid=Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        require(source!=target&&uuid.matches(source)&&uuid.matches(target)&&epoch>=0&&revision>0) { "原编组或草稿身份无效" }
        val window=captureWindow(); val document=documentId
        fun current()=!closed&&document!=null&&documentId==document&&acceptsWindow(window)&&section==null&&taskViewId==null&&
            !phoneComposer.intent&&controller.webPrivateOwnerVisible()
        check(current()&&controller.matchesComposerTeamReplacement(key,epoch,revision,source)) { "原草稿或编组已变化，未更换" }
        val module=controller.nativeButlerHost().features.module("teams") as? dev.pocketengine.features.teams.TeamsModule
            ?:error("原编组不可读取，未更换")
        val recheck="请在原草稿核对当前编组；没有发送，也没有重试。"
        controller.runPluginCatalogWork {
            val selected=runCatching { module.ownerDraftRoster(target) }
            main.post {
                if(!current()||!controller.matchesComposerTeamReplacement(key,epoch,revision,source)) {
                    complete(id,null,"原草稿或编组已变化，未更换",retainValue=false,replayHint=recheck); return@post
                }
                val roster=selected.getOrElse { failure ->
                    complete(id,null,failure.message?:"所选编组不可核对，未更换",retainValue=false,replayHint=recheck)
                    return@post
                }
                try {
                    controller.replaceComposerTeam(key,epoch,revision,source,roster,::current) { result,error ->
                        complete(id,result,error,"outcome_unknown",retainValue=false,replayHint=recheck)
                        publishState()
                    }
                    publishState()
                } catch(_:Exception) { complete(id,null,recheck,"outcome_unknown",retainValue=false,replayHint=recheck) }
            }
        }
    }

    private fun ownerTeamResultWindow(task: String): () -> Boolean {
        require(!closed && documentId != null && section == null && taskViewId == task &&
            controller.webPrivateOwnerVisible() && controller.conversationReader == null) { "请在原私人工作窗口核对这份汇总" }
        val bridge = commandFence.ticket()
        val owner = controller.displayedPhoneWindowVersion()
        val selection = teamReportReviewGeneration
        // The stable foreground generation has no controller/store callback while the original SQL write is held.
        return { !closed && commandFence.accepts(bridge) && controller.displayedPhoneWindowAlive(owner) &&
            taskViewId == task && teamReportReviewGeneration == selection && controller.conversationReader == null }
    }

    private fun readOwnerWorkTeamReport(id: String, taskId: String, selection: JSONObject) {
        checkedPayload(selection, setOf("sourceReviewHandle", "teamRunId", "generation", "revision"))
        val original = requiredString(selection, "sourceReviewHandle")
        val team = requiredString(selection, "teamRunId")
        val generation = requiredInt(selection, "generation")
        val revision = requiredLong(selection, "revision")
        require(!closed && documentId != null && section == null && taskViewId == taskId &&
            controller.webPrivateOwnerVisible() && controller.conversationReader == null) { "请在原私人工作窗口核对这份汇总" }
        val task = reviews.teamReportParent(original, taskId, controller.state.tasks)
        val raw = task.raw
        val candidate = raw.optJSONArray("ownerWorkTeams").objects().singleOrNull { it.opt("teamRunId") == team }?.optJSONObject("reportForReview")
        require(raw.opt("taskProjection") == "detail_window" && raw.opt("taskCoreReadLimited") == false &&
            raw.opt("contextAvailable") == true && raw.opt("hasUnresolvedOperations") == false &&
            pendingCapabilityApprovals(task).isEmpty() && candidate != null && candidate.opt("teamRunId") == team &&
            candidate.getInt("generation") == generation && candidate.getLong("revision") == revision) { "这份汇总不在当前原工作核对中" }
        teamReportSelectionGeneration++
        teamReportReviewGeneration++
        val current = ownerTeamResultWindow(taskId)
        val owner = controller.nativeTokenBudgetHost()
        controller.runTeamPhoneWork {
            val read = runCatching { owner.readOwnerWorkTeamReport(taskId, raw.getString("runId"), raw.getInt("coordinatorEpoch"),
                raw.getString("resultReviewDigest"), team, generation, revision) { current() } }
            main.post {
                val value = read.mapCatching { exact ->
                    check(current() && section == null && controller.webPrivateOwnerVisible()) { "原汇总审阅窗口已关闭" }
                    val view = exact.proof.evidence().put("contract", exact.proof.origin().contract()).put("body", exact.body())
                    val handle = reviews.selectTeamReport(original, taskId, controller.state.tasks, PocketWebTaskReviews.TeamReport(exact.proof, current))
                    JSONObject().put("reviewHandle", handle).put("expiresInMs", 120_000).put("teamReport", view)
                }
                value.fold({ complete(id, it, null, retainValue = false) },
                    { complete(id, null, it.message ?: "未读取原团队汇总", retainValue = false) })
            }
        }
    }

    private fun submitOwnerWorkTeamReport(id: String, args: JSONObject) {
        checkedPayload(args, setOf("reviewHandle", "usingTeamReport", "completed", "feedback"))
        require(requiredBoolean(args, "usingTeamReport"))
        val done = requiredBoolean(args, "completed")
        val feedback = requiredString(args, "feedback", empty = true).also { require(it.length <= 8000) }
        val task = taskViewId ?: error("原工作核对已关闭")
        val currentWindow = ownerTeamResultWindow(task)
        val review = reviews.takeTeamReport(requiredString(args, "reviewHandle"), task, controller.state.tasks)
        val owner = controller.nativeTokenBudgetHost()
        controller.runTeamPhoneWork {
            val result = runCatching { owner.confirmOwnerWorkTeamReport(review.proof, done, feedback) { currentWindow() && review.current() } }
            main.post {
                if (!currentWindow() || section != null || !controller.webPrivateOwnerVisible())
                    complete(id, null, "原窗口已变化，请只核对原任务记录；没有重新提交", "outcome_unknown", retainValue = false)
                else result.fold({ complete(id, it, null, retainValue = false, replayHint = "请在原工作核对已经记录的决定") },
                    { complete(id, null, it.message ?: "决定尚未确认，请核对原工作", "outcome_unknown", retainValue = false) })
                publishState()
            }
        }
    }

    private fun coldOwnerQuestionWindow(task: String, sourceStrict: Boolean = true): () -> Boolean {
        val ticket = captureWindow()
        val generation = taskReviewGeneration
        val document = documentId
        val revision = controller.conversationSourceRevision
        val navigation = Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision)
        return { !closed && document != null && document == documentId && acceptsWindow(ticket) && taskViewId == task &&
            section == null && taskReturnSection == null && controller.webPrivateOwnerVisible() &&
            controller.conversationReader == null && !controller.hasChannelPhoneOrigin &&
            (!sourceStrict || revision == controller.conversationSourceRevision) && generation == taskReviewGeneration &&
            navigation == Triple(controller.state.requestedTaskRevision, controller.state.requestedProjectRevision, controller.state.requestedConnectionRevision) }
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
                    retireReplyPicker()
                    controller.projectPackages.retireDirectViteExport()
                    controller.projectFileImports.retireSuppliedLock()
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
                        val ordinary=section==null && taskViewId==null && documentId!=null && !phoneComposer.intent &&
                            controller.conversationReader==null && controller.webPrivateOwnerVisible()
                        val expectedDraftRevision=if(localRevision==null&&args.has("expectedDraftRevision"))
                            requiredLong(args,"expectedDraftRevision").also { require(it>0) { "原草稿版本无效；没有发送。" } } else null
                        val expectedForegroundTurn=if(localRevision==null&&args.has("expectedForegroundTurn")) {
                            val shown=args.getJSONObject("expectedForegroundTurn")
                            if(shown.length()==0) shown else {
                                checkedPayload(shown,setOf("taskId","runId","epoch"))
                                val turnEpoch=requiredInt(shown,"epoch").also { require(it>0) { "原回合版本无效；没有发送。" } }
                                JSONObject().put("taskId",requiredString(shown,"taskId")).put("runId",requiredString(shown,"runId")).put("epoch",turnEpoch)
                            }
                        } else null
                        controller.sendChat(text,key,epoch,localRevision,localRevision?.let { primaryDisplayComposerWindow() },
                            originalOrdinaryComposer=ordinary,expectedDraftRevision=expectedDraftRevision,expectedForegroundTurn=expectedForegroundTurn)
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
                val epoch=if(args.has("rememberDetachedPosition")){
                    checkedPayload(args,setOf("conversationId","rememberDetachedPosition"))
                    require(requiredBoolean(args,"rememberDetachedPosition"))
                    ordinaryDetachedHistoryEpoch(conversation)
                }else null
                controller.openConversationReader(conversation,epoch)
            }
            "conversation.reader.close" -> {
                val page=requireConversationReader(args,closing=true)
                if(stageDetachedHistoryPosition(page,args))
                    controller.closeOrdinaryDetachedHistory(controller.conversationReader?:page){publishState()}
                else controller.closeConversationReader()
            }
            "conversation.reader.earlier" -> { requireConversationReader(args, earlier = true); controller.loadEarlierConversationReader() }
            "conversation.reader.refresh" -> { requireConversationReader(args); controller.refreshConversationReader() }
            "conversation.reader.position" -> {
                val page = requireConversationReader(args)
                val index = requiredInt(args, "index"); val offset = requiredInt(args, "offset")
                require(index >= 0 && index < page.messages.size && offset >= 0) { "原阅读位置不可核对" }
                controller.saveConversationReading(page.window, index, offset)
                stageDetachedHistoryPosition(page,args)
            }
            "conversation.reader.select" -> {
                val page = requireConversationReader(args)
                require(!controller.state.chatSubmitting) { "请等待原输入回执后再切换对话" }
                controller.switchFromConversationReader(page.window)
            }
            "attachments.pick" -> { requireComposer(args, optional = true); controller.pickAttachments() }
            "attachments.openPicker" -> {
                val exact = args.has("revision")
                requireComposer(args, optional = !exact)
                if (exact) {
                    checkedPayload(args, setOf("key", "epoch", "revision"))
                    requireAttachmentDraft(args)
                    require(!controller.state.chatAttachmentPickerOpen) { "请先关闭原附件选择" }
                }
                val list = controller.openChatAttachmentPicker()
                if (exact) return JSONObject().put("requestId", list ?: error(controller.state.error ?: "原附件选择未打开，请核对输入和模型设置"))
            }
            "attachments.closePicker" -> {
                val request = if (args.has("pickerRequestId")) {
                    checkedPayload(args, setOf("pickerRequestId")); requiredString(args, "pickerRequestId")
                } else null
                val exact=replyPicker
                if(exact!=null){check(request==exact.request){"原附件选择编号已变化"};retireReplyPicker()}
                else controller.closeChatAttachmentPicker(request)
            }
            "attachments.remove" -> { requireComposer(args); controller.removeChatAttachment(requiredString(args, "id"), requiredString(args, "key"), requiredInt(args, "epoch")) }
            "attachments.toggle" -> {
                val focused = args.has("pickerRequestId")
                requireComposer(args, optional = !focused)
                val id = requiredString(args, "id"); val sha = requiredString(args, "sha256")
                val inventory = if (focused) {
                    checkedPayload(args, setOf("key", "epoch", "revision", "id", "sha256", "pickerRequestId", "selected"))
                    requireAttachmentDraft(args)
                    val request = requiredString(args, "pickerRequestId")
                    check(request == controller.chatAttachmentPickerRequest() && "attachments.list" !in controller.state.moduleBusy &&
                        controller.state.moduleResultInputs["attachments.list"]?.optString("_requestId") == request) { "原附件选择清单已变化" }
                    controller.state.moduleResults["attachments.list"] ?: error("未读取这份原附件")
                } else controller.state.feature("attachments")
                val selected = inventory.optJSONArray("attachments").objects().firstOrNull { it.optString("id") == id && it.optString("sha256") == sha }
                    ?: error("附件原件已变化，请重新选择")
                if (focused) {
                    check(!selected.optBoolean("outputSourceRetired")) { "原文件已撤回，没有加入草稿" }
                    val held = controller.state.selectedChatAttachments.firstOrNull { it.id == id }
                    check(held == null || held.sha256 == sha) { "草稿选的是另一版本，没有替换" }
                    if (requiredBoolean(args, "selected") == (held != null)) return accepted()
                }
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
            "tasks.close" -> {
                if(args.has("reportHandoff")) {
                    checkedPayload(args,setOf("reportHandoff"))
                    val pending=reportDraftHandoff ?: error("原汇报已关闭，请从导航继续未发追问")
                    val offer=pending.offer
                    require(pending.handle==requiredString(args,"reportHandoff")&&pending.closedWindow==null&&
                        documentId==offer.document&&acceptsWindow(offer.window)&&section==null&&taskReturnSection==null&&
                        taskViewId==offer.task&&controller.webPrivateOwnerVisible()&&
                        offer.proof.matchesCurrentDisplay(controller.state.tasks.singleOrNull { it.id==offer.task }?.raw)&&
                        controller.currentIndependentReportDraftKey()==pending.draft) { "原汇报已变化，请从导航继续未发追问" }
                    restoreTaskWorkspace()
                    reportDraftHandoff=pending.copy(closedWindow=captureWindow())
                    return JSONObject().put("closed",true)
                } else restoreTaskWorkspace()
            }
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
                require(canUseTaskControls(task) && PocketWebTaskActions.canStopDirectly(task) && (!(PocketWebTaskActions.runtimeSetupTask(task)||task.raw.has("ownerDirectNodeBuild")) || controller.webPrivateOwnerVisible())) { "请回这项任务的原能力审核窗口操作" }
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
                "wirelessAboutDevice" -> {
                    checkedPayload(args, setOf("section"))
                    val shown = controller.state
                    val display = shown.feature("display")
                    val adb = display.optJSONObject("adb")
                    val pairing = shown.feature("phone_setup").optJSONObject("pairing")
                    val connection = backgroundConnectionState(display, shown.activeTaskId, shown.moduleBusy)
                    val activity = controller.webPresentationActivity()
                    check(!closed && documentId != null && section == null && taskViewId == null &&
                        controller.webPrivateOwnerVisible() && !controller.hasChannelPhoneOrigin &&
                        controller.conversationReader == null && !controller.attachmentOutcomes.state.open &&
                        shown.filePath == null && shown.backgroundSetupOpen && !shown.phoneSubmitting &&
                        Build.VERSION.SDK_INT >= 34 && activity.hasWindowFocus() &&
                        connection.backend == "wireless_adb" && connection.canPair &&
                        display.opt("supportedAndroid") == true && display.opt("taskOwned") == false &&
                        display.opt("configured") == false && display.opt("helperConnected") == false &&
                        adb?.let { it.opt("phase") == "idle" && it.opt("identityPresent") == false &&
                            it.opt("helperAuthenticated") == false } == true &&
                        pairing?.let { it.opt("active") == false && it.optJSONObject("autoCapture")?.opt("active") == false } == true) {
                        "请返回尚未配对的本机无线准备页。"
                    }
                    // Open only Android's system handler. No setting read/write, wireless
                    // setup flag, discovery, or task is started by an About Device visit.
                    val about = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                    val unavailable = "系统未提供可打开的「关于本机」入口。可以从系统设置查找；若系统或管理员不允许，请保持未连接。"
                    try {
                        val target = activity.packageManager.resolveActivity(about,
                            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo ?: error(unavailable)
                        activity.startActivity(about.setComponent(ComponentName(target.packageName, target.name)))
                    } catch (_: ActivityNotFoundException) { error(unavailable) }
                    catch (_: SecurityException) { error(unavailable) }
                    return JSONObject().put("status", "settings_opened")
                }
                "accessibilityAppInfo" -> {
                    checkedPayload(args, setOf("section"))
                    val shown = controller.state
                    val display = shown.feature("display")
                    val preparing = section == "setup" || (section == null && shown.backgroundSetupOpen &&
                        display.opt("taskOwned") == false)
                    val readiness = shown.phoneReadiness
                    val service = readiness.groups.singleOrNull { it.id == "mainObservation" }
                        ?.checks?.singleOrNull { it.id == "serviceConnected" }
                    val activity = controller.webPresentationActivity()
                    check(!closed && documentId != null && preparing && taskViewId == null &&
                        controller.webPrivateOwnerVisible() && !controller.hasChannelPhoneOrigin &&
                        controller.conversationReader == null && !controller.attachmentOutcomes.state.open && shown.filePath == null &&
                        Build.VERSION.SDK_INT >= 34 && display.opt("supportedAndroid") == true &&
                        activity.hasWindowFocus() && readiness.checkedAtUptimeMs >= 0 &&
                        service?.let { it.state == PhoneCapabilityState.BLOCKED && it.code == "accessibility_disabled" } == true) {
                        "请在当前手机准备页核对界面读取权限后，再打开应用信息。"
                    }
                    // A disabled service does not prove that Android restricted the setting.
                    // The stock intent names only our app; the owner decides in Android.
                    try {
                        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", activity.packageName, null)))
                    } catch (_: ActivityNotFoundException) {
                        error("Android 未提供应用信息入口。可在系统设置中查看；如果系统不允许，手机操作无法继续。")
                    } catch (_: SecurityException) {
                        error("系统不允许打开该设置。不会改动权限，手机操作暂不能继续。")
                    }
                    return JSONObject().put("status", "settings_opened")
                }
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

    /** Navigation evidence only. No model catalogue, selection, or return handle survives this reply. */
    private fun readOriginalHomeModelInput(id: String, args: JSONObject) {
        checkedPayload(args, setOf("originalHomeInput"))
        val expected = checkedPayload(args.getJSONObject("originalHomeInput"), setOf("key", "epoch", "revision", "section"))
        val source = if (expected.has("section")) requiredString(expected, "section").also {
            require(it == "accounts") { "原账号设置窗口尚未核对。" }
        } else "models"
        val key = requiredString(expected, "key")
        val epoch = requiredInt(expected, "epoch")
        val revision = requiredLong(expected, "revision")
        val window = captureWindow()
        val document = documentId
        val draft = controller.composerDraft
        val state = controller.state
        check(document != null && document == documentId && acceptsWindow(window) && section == source &&
            taskViewId == null && controller.conversationReader == null && controller.webPrivateOwnerVisible() &&
            state.filePath == null && state.conversationId == null && !state.chatSubmitting && !state.phoneSubmitting &&
            (source != "accounts" || controller.queuedInputsPage == null && !controller.attachmentOutcomes.state.open &&
                !state.chatAttachmentPickerOpen && !state.attachmentImporting && !state.fileLoading && !state.fileSaving) &&
            !phoneComposer.intent && !controller.hasChannelPhoneOrigin && key.startsWith("new:") &&
            draft != null && draft.key == key && draft.conversationId.isEmpty() && draft.revision == revision &&
            controller.composerViewEpoch == epoch) { "原输入尚未核对，未打开另一份草稿。" }
        complete(id, JSONObject().put("originalHomeInputMatched", true).put("key", key)
            .put("epoch", epoch).put("revision", revision), null, retainValue = false)
    }

    /** A new foreground token can re-attest the unchanged active private composer, never an earlier send or model lease. */
    private fun readOriginalConversationModelInput(id: String, args: JSONObject) {
        checkedPayload(args, setOf("originalConversationInput"))
        val expected = checkedPayload(args.getJSONObject("originalConversationInput"),
            setOf("conversationId", "key", "epoch", "revision", "resumeRevision", "section"))
        val conversation = requiredString(expected, "conversationId")
        val key = requiredString(expected, "key")
        val epoch = requiredInt(expected, "epoch")
        val revision = requiredLong(expected, "revision")
        val resume = requiredLong(expected, "resumeRevision")
        val source = requiredString(expected, "section")
        require(conversation.length <= 240 && key == "conversation:$conversation" && epoch >= 0 && revision > 0 &&
            resume >= 0 && (source == "models" || source == "accounts"))
        val window = captureWindow()
        val draft = controller.composerDraft
        val state = controller.state
        check(documentId != null && acceptsWindow(window) && section == source && workspaceResumeRevision == resume &&
            taskViewId == null && controller.conversationReader == null && controller.queuedInputsPage == null &&
            controller.webPrivateOwnerVisible() && !controller.attachmentOutcomes.state.open && !state.chatAttachmentPickerOpen &&
            !state.attachmentImporting && state.filePath == null && !state.fileLoading && !state.fileSaving &&
            !state.chatSubmitting && !state.phoneSubmitting && !phoneComposer.intent && !controller.hasChannelPhoneOrigin &&
            state.conversationId == conversation && draft != null && draft.conversationId == conversation &&
            draft.key == key && draft.revision == revision && controller.composerViewEpoch == epoch) {
            "暂未核对到原输入，未打开其他对话。"
        }
        complete(id, expected.put("originalConversationInputMatched", true), null, retainValue = false)
    }

    /** Explicit return from the existing API model choice: identity and local configuration only. */
    private fun readOriginalPhoneModelInput(id: String, args: JSONObject) {
        checkedPayload(args, setOf("originalPhoneInput"))
        val expected = checkedPayload(args.getJSONObject("originalPhoneInput"), setOf("conversationId", "key", "epoch", "revision",
            "phoneEpoch", "phoneRevision", "sourceEditTicket", "resumeRevision", "preferenceRevision", "selectionRevision", "mode"))
        // An absent mode stays the old main-screen proof. Only the independent-screen opt-in is accepted.
        val background = expected.has("mode")
        require(!background || expected.opt("mode") == "background") { "请回到原独立屏目标核对" }
        val conversation = requiredString(expected, "conversationId")
        val key = requiredString(expected, "key")
        val epoch = requiredInt(expected, "epoch")
        val revision = requiredLong(expected, "revision")
        val phoneEpoch = requiredInt(expected, "phoneEpoch")
        val phoneRevision = requiredLong(expected, "phoneRevision")
        val sourceEditTicket = requiredLong(expected, "sourceEditTicket")
        val resume = requiredLong(expected, "resumeRevision")
        val preference = requiredLong(expected, "preferenceRevision")
        val selection = requiredLong(expected, "selectionRevision")
        require(conversation.length <= 240 && key == "conversation:$conversation" && epoch >= 0 && revision > 0 &&
            phoneEpoch >= 0 && phoneRevision > 0 && sourceEditTicket > 0 && resume >= 0 && preference >= 0 && selection > 0)
        val unavailable = "还没核对到同一原手机目标与保存的模型。没有提交任务。"
        val window = captureWindow()
        val document = documentId
        val generation = domainGeneration
        fun originalPhoneModeCurrent() = if (background) phoneComposer.originalBackgroundModelInputCurrent(phoneEpoch, phoneRevision, sourceEditTicket)
            else phoneComposer.originalMainModelInputCurrent(phoneEpoch, phoneRevision, sourceEditTicket)
        fun current(): Boolean {
            val state = controller.state
            val draft = controller.composerDraft
            if (closed || document == null || documentId != document || generation != domainGeneration || !acceptsWindow(window) ||
                section != "models" || workspaceResumeRevision != resume || taskViewId != null ||
                !controller.webPrivateOwnerVisible() || controller.hasChannelPhoneOrigin || controller.conversationReader != null ||
                controller.queuedInputsPage != null || controller.attachmentOutcomes.state.open || state.chatAttachmentPickerOpen ||
                state.attachmentImporting || state.filePath != null || state.fileLoading || state.fileSaving ||
                state.chatSubmitting || state.phoneSubmitting || state.conversationId != conversation || draft == null ||
                draft.key != key || draft.conversationId != conversation || draft.revision != revision ||
                controller.composerViewEpoch != epoch || !originalPhoneModeCurrent()) return false
            val own = controller.conversationModelPreference()
            val model = controller.nativeModelSelection().status()
            return !model.pending() && model.revision() == selection && own.opt("scopeKey") == "conversation:$conversation" &&
                own.opt("inherited") == true && own.isNull("target") && own.optLong("revision", -1) == preference
        }
        check(current()) { unavailable }
        val service = controller.nativeModelConnections() ?: error(unavailable)
        controller.runModelConnectionWork(Runnable {
            // Same local reader as the ordinary settings snapshot, no model/catalog/account request.
            val result = runCatching {
                check(!closed && acceptsWindow(window))
                val (snapshot, observed) = currentModelSnapshot(service)
                check(observed.revision() == selection && snapshot.opt("activeRoute") == "api_key" &&
                    snapshot.opt("selectionUnavailable") != true)
                val active = snapshot.getJSONObject("nativeSelection")
                val binding = active.getJSONObject("capabilityBinding")
                val model = active.getString("model")
                val sourceId = binding.getString("connectionId")
                val rows = snapshot.getJSONObject("connections").getJSONArray("connections")
                val source = (0 until rows.length()).map { rows.getJSONObject(it) }.singleOrNull { it.opt("id") == sourceId }
                check(model.isNotBlank() && active.opt("modelRoute") == "api_key" &&
                    (active.optJSONObject("modelRoutePlan")?.optLong("groupRevision") ?: 0L) == 0L &&
                    source != null && source.opt("enabled") == true && source.opt("credentialStatus") != "expired" &&
                    ModelBinding.fromJson(source.getJSONObject("binding")) == ModelBinding.fromJson(binding))
                JSONObject().put("model", model).put("modelRoute", "api_key").put("capabilityBinding", binding)
            }
            main.post {
                val proof = result.getOrNull()
                if (proof == null || !runCatching { current() }.getOrDefault(false)) {
                    complete(id, null, unavailable)
                } else complete(id, expected.put("originalPhoneInputMatched", true).put("activeRoute", "api_key")
                    .put("nativeSelection", PocketWebState.publicValue(proof)), null, retainValue = false)
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
                        requestId?.let { complete(it, (PocketWebState.publicValue(snapshot) as JSONObject)
                            .put("modelSelectionRevision", observed.revision()), null) }
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

    private fun openOriginalTask(requestId: String, taskId: String, requestNavigation: Boolean, returnSection: String? = section ?: taskReturnSection, keepConversationReader: Boolean = false, expectedRunId: String? = null, expectedEpoch: Int? = null, projectScope: ProjectTaskReadScope? = null, sourceWindowCurrent: (() -> Boolean)? = null, checkedDeviceFile: dev.pocketengine.ui.ReceivedFileSelection? = null, originalFileApproval: String? = null, preparedPhoneReceipt: JSONObject? = null, verifySourceBeforeMerge: ((JSONObject) -> Unit)? = null, checkReaderInput: ((UiTask) -> Unit)? = null) {
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
            if(verifySourceBeforeMerge!=null)controller.readTaskForOpen(taskId,::currentSource,verifySourceBeforeMerge,done)
            else if(originalFileApproval==null)controller.readTaskForOpen(taskId,::currentSource,done)
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
                val opened = JSONObject().put("taskId", task.id).put("readOnly", true).put("executionAuthorized", false)
                // The read-only open must preserve the otherwise lost native save acknowledgment.
                if (preparedPhoneReceipt != null) opened.put("prepared", true).put("taskStarted", false)
                    .put("manualPhoneTarget", preparedPhoneReceipt.optJSONObject("manualPhoneTarget"))
                complete(requestId, opened, null, retainValue = checkedDeviceFile==null && preparedPhoneReceipt==null)
                publishState()
            } catch (failure: Exception) {
                if ((requestNavigation || projectScope != null || checkReaderInput != null && entered) && (sourceWindowCurrent == null || entered)) runCatching { restoreTaskWorkspace() }
                complete(requestId, null, failure.message ?: "原任务窗口不可用")
                publishState()
            }
        }
    }
    private data class DependencyComparison(val work:PriorNodeDependencyWork,val handle:String,
                                            val window:PocketWebWindowTicket,val until:Long)
    private var dependencyComparison:DependencyComparison?=null

    private fun closeForNodeDependencyComparison(args:JSONObject):JSONObject {
        checkedPayload(args,setOf("dependencyComparison"))
        val expected=args.getJSONObject("dependencyComparison")
        checkedPayload(expected,setOf("taskId","runId","epoch","dependencyOperationId"))
        val task=requiredString(expected,"taskId")
        require(task.matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) &&
            task==requiredString(expected,"runId") && task==requiredString(expected,"dependencyOperationId") &&
            requiredInt(expected,"epoch")==1 && section==null && taskReturnSection==null && taskViewId==task &&
            documentId!=null && !closed && controller.webPrivateOwnerVisible() && controller.state.filePath==null) {
            "请从原项目打开这次依赖准备"
        }
        val raw=controller.state.tasks.singleOrNull { it.id==task }?.raw ?: error("原依赖准备已离开")
        val scope=raw.getJSONObject("ownerDirectNodeDependencies")
        val context=raw.getJSONObject("developmentContext")
        val project=scope.getString("projectId"); val checkout=scope.getString("checkoutId")
        val operations=raw.getJSONArray("operations")
        val original=(0 until operations.length()).map(operations::getJSONObject).singleOrNull {
            it.opt("tool")=="project_execute" && it.opt("operationId")==task && it.opt("runId")==task
        } ?: error("未核对到这次原依赖准备的操作")
        val receipt=original.getJSONObject("receipt")
        val exit=receipt.getJSONObject("executionReceipt")
        check(raw.opt("id")==task && raw.opt("runId")==task && raw.opt("coordinatorEpoch")==1 &&
            raw.opt("command")=="project.nodeDependencies.owner" && raw.opt("nativeExecutionMode")=="OWNER_DIRECT_NODE_DEPENDENCIES" &&
            raw.opt("actorId")=="owner" && raw.opt("spaceId")=="private" && raw.opt("kind")=="tool" &&
            raw.opt("status")=="completed" && raw.opt("cancelRequested")==false && raw.opt("hasUnresolvedOperations")==false &&
            raw.opt("taskProjection")=="detail_window" && raw.opt("taskCoreReadLimited")==false &&
            raw.opt("auditBodyIncluded")==true && raw.opt("contextAvailable")!=false && raw.opt("contextWithdrawn")!=true &&
            raw.opt("sourceCurrentReadRequired")!=true &&
            scope.opt("format")==dev.pocketengine.features.local.project.OwnerDirectNodeDependencies.FORMAT &&
            scope.opt("requestId")==task && scope.opt("runId")==task &&
            scope.opt("epoch")==1 && scope.opt("dependencyOperationId")==task && project==controller.state.projectId &&
            project==context.opt("projectId") && checkout==context.opt("checkoutId") && checkout.isNotBlank() &&
            original.opt("state")=="receipt" && receipt.opt("code")=="project_process_receipt" &&
            receipt.opt("status")=="completed" && receipt.opt("executionStatus")=="completed" && receipt.opt("accepted")==true &&
            exit.opt("exitCode")==0 && exit.opt("receiptReceived")==true &&
            exit.opt("resourcesReleased")==true && exit.opt("processGroupAbsent")==true) {
            "未核对到这次原副本的成功准备，没有读取其他记录"
        }
        restoreTaskWorkspace()
        val handoff=DependencyComparison(PriorNodeDependencyWork(task,project,checkout),
            java.util.UUID.randomUUID().toString(),captureWindow(),SystemClock.elapsedRealtime()+120_000L)
        dependencyComparison=handoff
        return JSONObject().put("closed",true).put("dependencyComparisonHandle",handoff.handle)
            .put("taskId",task).put("projectId",project).put("checkoutId",checkout)
    }

    private fun takeNodeDependencyComparison(args:JSONObject):PriorNodeDependencyWork {
        val held=dependencyComparison
        require(held!=null && held.handle==requiredString(args,"dependencyComparisonHandle") &&
            SystemClock.elapsedRealtime()<held.until && acceptsWindow(held.window) && section==null && taskViewId==null &&
            documentId!=null && !closed && controller.webPrivateOwnerVisible() && controller.state.filePath==null &&
            held.work.project==controller.state.projectId && held.work.checkout==requiredString(args,"checkoutId")) {
            "原依赖准备或副本已变化，未换用其他记录"
        }
        dependencyComparison=null
        return held.work
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
    private fun checkedMessageAttachmentSource(args: JSONObject): JSONObject {
        val source = checkedPayload(args, setOf("conversationId", "window", "taskId", "messageId", "sourceRevision", "id", "sha256"))
        requiredString(source, "conversationId"); requiredString(source, "messageId"); requiredString(source, "id")
        require(requiredLong(source, "sourceRevision") >= 0 && requiredString(source, "sha256").matches(Regex("[0-9a-f]{64}"))) { "原附件版本无效" }
        if (source.has("window")) require(requiredLong(source, "window") > 0) { "原历史阅读窗口无效" }
        if (source.has("taskId")) {
            requiredString(source, "taskId")
            require(!source.has("window")) { "原附件不能同时使用两个窗口" }
        }
        return source
    }
    private data class ConsumedAttachmentAnswer(val locator:PhoneDraftMessagePin,val task:String,val run:String,val epoch:Int,val reply:String)
    private data class VisibleMessageAttachment(val file:UiChatAttachment,val original:PhoneDraftMessagePin?,val answer:ConsumedAttachmentAnswer?)
    /** A saved answer consumed this exact question; that does not prove the model read the file body. */
    private fun consumedAttachmentAnswer(rows:List<UiMessage>,input:UiMessage,original:PhoneDraftMessagePin):ConsumedAttachmentAnswer? {
        if(input.attachmentCount!=1||input.attachments.size!=1||input.hostGoalTaskId!=null||input.content.isEmpty())return null
        val receipt=input.submission?:return null
        if(receipt.opt("status")!="consumed"||receipt.opt("queueState")!="materialized"||receipt.opt("reconciliationRequired")!=false||
            receipt.opt("messageId")!=original.message||receipt.opt("conversationId")!=original.conversation)return null
        fun number(key:String):Long? = when(val n=receipt.opt(key)){is Int->n.toLong();is Long->n;else->null}
        if(number("ownerMessageRevision")!=original.version)return null
        val replyMessage=receipt.opt("replyMessageId") as? String?:return null
        val answer=rows.singleOrNull{it.id==replyMessage}?:return null
        if(answer.role!="assistant"||answer.sourceRevision!=1L||answer.content.isEmpty()||answer.phoneResult!=null||
            answer.taskResult!=null||answer.hostGoalTaskId!=null||answer.originalHostFinal!=null)return null
        val reply=answer.replyContext?:return null
        val replyId=reply.replyId?:return null
        if(reply.deliveryId!=null||reply.replyToMessageIds.singleOrNull()!=original.message||
            receipt.opt("taskId")!=reply.taskId||receipt.opt("runId")!=reply.runId||receipt.opt("consumedRunId")!=reply.runId||
            number("epoch")!=reply.epoch.toLong()||number("consumedEpoch")!=reply.epoch.toLong())return null
        val pin=PhoneDraftMessagePin.from(answer.ownerOriginalLocator)?.takeIf {
            it.generation==original.generation&&it.conversation==original.conversation&&it.message==replyMessage&&it.version==1L
        }?:return null
        return ConsumedAttachmentAnswer(pin,reply.taskId,reply.runId,reply.epoch,replyId)
    }
    private fun visibleMessageAttachment(source: JSONObject): UiChatAttachment? = visibleMessageAttachmentSelection(source)?.file
    private fun visibleMessageAttachmentSelection(source:JSONObject,localInput:AttachmentMessageInput?=null,inOwnReader:Boolean=false):VisibleMessageAttachment? {
        val task = source.optString("taskId").takeIf { it.isNotEmpty() }
        val files=controller.attachmentOutcomes.state
        if (section != null || taskViewId != task || files.open&&!(localInput!=null&&inOwnReader&&files.messageInput==localInput) ||
            !controller.webPrivateOwnerVisible()) return null
        val conversation = source.getString("conversationId")
        if(localInput!=null&&(task!=null||source.has("window")||controller.state.filePath!=null||
            controller.queuedInputsPage!=null||phoneComposer.intent||controller.hasChannelPhoneOrigin||controller.state.chatAttachmentPickerOpen||
            controller.composerDraft?.let{it.key=="conversation:$conversation"&&it.conversationId==conversation}!=true))return null
        val page = controller.conversationReader
        val rows = if (source.has("window")) {
            if (page == null || page.window != source.getLong("window") || page.conversation != conversation || !controller.canReadConversationWindow(page)) return null
            page.messages
        } else {
            if ((task == null && page != null) || controller.state.conversationId != conversation) return null
            controller.state.messages
        }
        val row = rows.singleOrNull { it.id == source.getString("messageId") && it.sourceRevision == source.getLong("sourceRevision") } ?: return null
        if (task != null && (row.role != "assistant" || (row.taskId?.takeIf(String::isNotEmpty) ?: row.submission?.optString("taskId")) != task)) return null
        val file=row.attachments.singleOrNull { it.id == source.getString("id") && it.sha256 == source.getString("sha256") }?:return null
        val original = if (localInput!=null) {
            PhoneDraftMessagePin.from(row.ownerOriginalLocator)?.takeIf {
                it.conversation == conversation && it.message == row.id && it.version == row.sourceRevision
            } ?: return null
        } else null
        if(localInput!=null){
            if(row.role!="user"||row.sourceRevision<=0||!localInput.supports(file.mimeType,file.name)||file.size<=0||
                row.attachments.count{it.id==file.id}!=1||
                !inOwnReader&&row.attachmentFilesSourceRevision!=controller.messageAttachmentSourceRevision)return null
            val projected=row.attachmentFiles.filter{it.opt("id")==file.id}
            // A conversation refresh can briefly replace the metadata parallel to the exact
            // stored message pin. Never enter on absence; a scoped, SHA-checked reader already held may wait for it.
            if(!inOwnReader||projected.isNotEmpty()){
                val ready=projected.singleOrNull()?:return null
                if(ready.opt("role")!="source"||ready.opt("availability")!="available"||ready.opt("contentReadConfirmed")!=false||
                    ready.opt("sha256")!=file.sha256||ready.opt("mimeType")!=file.mimeType||ready.optLong("size",-1L)!=file.size||
                    !localInput.supports(file.mimeType,ready.opt("name") as? String?:""))return null
            }
        }
        val answer=if(localInput==AttachmentMessageInput.WORD_OR_TEXT){
            consumedAttachmentAnswer(rows,row,checkNotNull(original))?:return null
        }else null
        return VisibleMessageAttachment(file,original,answer)
    }
    private data class ReplyPicker(val request:String,val key:String,val epoch:Int,val sourceWindow:String,val file:UiChatAttachment,
        val target:OriginalReplyDocument.Target,val current:()->Boolean)
    @Volatile private var replyPicker:ReplyPicker?=null
    @Volatile private var replyPickerOpening:Any?=null
    @Volatile private var replyPickerChecking:Any?=null
    private fun retireReplyPicker(){
        val original=replyPicker
        replyPicker=null;replyPickerOpening=null;replyPickerChecking=null
        if(original!=null&&controller.chatAttachmentPickerRequest()==original.request)
            controller.closeChatAttachmentPicker(original.request)
    }
    private fun currentReplyPicker():ReplyPicker?{
        val original=replyPicker?:return null
        if(controller.chatAttachmentPickerRequest()==original.request&&original.current())return original
        retireReplyPicker();return null
    }
    private fun openReplyPicker(id:String,args:JSONObject){
        checkedPayload(args,setOf("key","epoch","revision","replyDocument"))
        requireComposer(args);requireAttachmentDraft(args)
        check(replyPicker==null&&replyPickerOpening==null&&!controller.state.chatAttachmentPickerOpen){"请先关闭原附件选择"}
        val source=checkedPayload(args.getJSONObject("replyDocument"),setOf("windowId","frameId","viewerId"))
        val sourceWindow=requiredString(source,"windowId")
        val original=attachmentPages.replyAttachment(sourceWindow,requiredString(source,"frameId"),
            requiredString(source,"viewerId"))?:error("请从已核对的原答复文档选择附件")
        val target=original.target;val file=original.file
        val document=documentId?:error("原私聊页面已关闭")
        val key=requiredString(args,"key");val epoch=requiredInt(args,"epoch");val revision=requiredLong(args,"revision")
        check(key=="conversation:${target.conversation()}"){"只能选择到原话题草稿"}
        val ticket=PocketWebWindowTicket(commandFence.ticket(),controller.webPhoneViewerWindowTicket())
        val history=controller.conversationReaderRevision;val sourceRevision=controller.messageAttachmentSourceRevision
        fun pinned(picking:Boolean,allowSummary:Boolean)=controller.state.messages
            .singleOrNull{it.id==target.message()&&it.sourceRevision==target.revision()}
            ?.let{liveReplyDocuments(it,inOwnReader=!picking,inOwnPicker=picking,allowSummary=allowSummary)}
            ?.singleOrNull{it.target==target&&it.file==file}
        // Only a real verified mounted reader can hand this exact source to the picker.
        val allowSummary=pinned(false,true)?.checkFirst?:error("原答复文档已变化，未打开附件选择")
        fun sameDraft(picking:Boolean):Boolean {
            val s=controller.state;val draft=controller.composerDraft
            if(closed||documentId!=document||!ticket.accepts(commandFence.ticket(),controller.webPhoneViewerWindowTicket())||
                controller.conversationReaderRevision!=history||controller.messageAttachmentSourceRevision!=sourceRevision||
                draft==null||draft.key!=key||draft.conversationId!=target.conversation()||controller.composerViewEpoch!=epoch||
                s.chatAttachmentPickerOpen!=picking||s.chatBusy||s.chatSubmitting||s.phoneSubmitting||
                controller.composerPending.any{it.draftKey==key})return false
            return pinned(picking,allowSummary)!=null
        }
        val attempt=Any();replyPickerOpening=attempt
        fun beforeClose()=replyPickerOpening===attempt&&controller.composerDraft?.revision==revision&&sameDraft(false)&&original.owns()
        controller.verifyOriginalReplyAttachment(target,::beforeClose){row,error->
            try{
                check(row!=null&&error==null&&beforeClose()&&ChatInputRules.attachment(row)==file){
                    error?:"原答复文档或草稿已变化，未打开附件选择"
                }
                val request=controller.openOriginalReplyAttachmentPicker {
                    beforeClose()&&original.close()
                }?:error("原草稿或模型配置暂不可核对，未打开附件选择")
                replyPicker=ReplyPicker(request,key,epoch,sourceWindow,file,target){ sameDraft(true) }
                complete(id,JSONObject().put("opened",true).put("requestId",request),null,retainValue=false)
            }catch(failure:Exception){complete(id,null,failure.message?:"原附件选择未确认",retainValue=false)}
            finally { if(replyPickerOpening===attempt)replyPickerOpening=null;publishState() }
        }
    }
    private fun toggleReplyPicker(id:String,args:JSONObject){
        checkedPayload(args,setOf("key","epoch","revision","pickerRequestId","id","sha256","selected"))
        requireComposer(args);requireAttachmentDraft(args)
        val original=currentReplyPicker()?:error("这份原答复附件选择已关闭")
        val requested=requiredBoolean(args,"selected")
        check(requiredString(args,"pickerRequestId")==original.request&&requiredString(args,"id")==original.file.id&&
            requiredString(args,"sha256")==original.file.sha256&&replyPickerChecking==null){"只可选择这份已核对的原答复文档"}
        val revision=requiredLong(args,"revision");val checking=Any();replyPickerChecking=checking
        fun current()=replyPicker===original&&replyPickerChecking===checking&&
            controller.chatAttachmentPickerRequest()==original.request&&controller.composerDraft?.revision==revision&&original.current()
        controller.verifyOriginalReplyAttachment(original.target,::current){row,error->
            try{
                check(row!=null&&error==null&&current()&&ChatInputRules.attachment(row)==original.file){error?:"原附件已变化，草稿保留"}
                requireComposer(args);requireAttachmentDraft(args)
                val already=controller.state.selectedChatAttachments.singleOrNull{it.id==original.file.id}
                check(already==null||already.sha256==original.file.sha256){"草稿选的是另一版本，没有替换"}
                if(requested!=(already!=null))controller.toggleChatAttachment(row)
                check(controller.state.selectedChatAttachments.any{it.id==original.file.id&&it.sha256==original.file.sha256}==requested){
                    controller.state.error?:"没有确认这次选择，草稿保留"
                }
                complete(id,accepted(),null,retainValue=false)
            }catch(failure:Exception){complete(id,null,failure.message?:"未确认选择这份原件",retainValue=false)}
            finally { if(replyPickerChecking===checking)replyPickerChecking=null;publishState() }
        }
        publishState()
    }
    private data class LiveReplyDocument(val file:UiChatAttachment,val target:OriginalReplyDocument.Target,val checkFirst:Boolean) {
        fun matches(other:LiveReplyDocument?)=other!=null&&target==other.target&&file==other.file
    }
    /** Only the already displayed ordinary private native reply and sole original submission.
     * This projection does not fetch Work/Audit history or verify files in the background. */
    private fun liveReplyDocuments(row:UiMessage,inOwnReader:Boolean=false,inOwnPicker:Boolean=false,allowSummary:Boolean=false):List<LiveReplyDocument>? {
        val reply=row.replyContext?:return null
        val files=reply.originalGeneratedDocuments
        if(files.isEmpty()||files.size>2||files.any{file->file.size<=0||
                file.mimeType !in setOf("application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document")})return null
        val nativeReply=reply.replyId?:return null
        val inputId=reply.replyToMessageIds.singleOrNull()?:return null
        val source=PhoneDraftMessagePin.from(row.ownerOriginalLocator)?:return null
        val state=controller.state
        val composer=controller.composerDraft
        val workspace=controller.attachmentOutcomes.state
        if(section!=null||taskViewId!=null||!controller.webPrivateOwnerVisible()||controller.conversationReader!=null||
            controller.queuedInputsPage!=null||state.filePath!=null||state.chatAttachmentPickerOpen&&!inOwnPicker||
            phoneComposer.intent||controller.hasChannelPhoneOrigin||workspace.open&&!(inOwnReader&&workspace.messageReplyDocumentOnly)||
            composer?.key!="conversation:${source.conversation}"||composer?.conversationId!=source.conversation||
            row.role!="assistant"||row.phoneResult!=null||row.taskResult!=null||row.originalHostFinal!=null||
            state.conversationId!=source.conversation||row.id!=source.message||row.sourceRevision!=1L||source.version!=row.sourceRevision||
            row.taskId!=reply.taskId||reply.deliveryId!=null)return null
        val input=state.messages.singleOrNull{it.id==inputId&&it.role=="user"}?:return null
        val accepted=input.submission?:return null
        if(accepted.opt("status")!="consumed"||accepted.opt("queueState")!="materialized"||
            accepted.opt("replyMessageId")!=source.message||accepted.opt("taskId")!=reply.taskId||
            accepted.opt("runId")!=reply.runId||accepted.opt("consumedRunId")!=reply.runId||
            OriginalReplyDocument.integer(accepted.opt("epoch"))!=reply.epoch.toLong()||
            OriginalReplyDocument.integer(accepted.opt("consumedEpoch"))!=reply.epoch.toLong()||
            OriginalReplyDocument.integer(accepted.opt("ownerMessageRevision"))!=input.sourceRevision)return null
        val task=state.tasks.singleOrNull{it.id==reply.taskId}?:return null
        val audit=task.raw
        if(task.status !in setOf("completed","needs_confirmation")||!OwnerWorkTask.isButlerSource(audit)||
            audit.opt("conversationId")!=source.conversation||audit.opt("runId")!=reply.runId||
            OriginalReplyDocument.integer(audit.opt("coordinatorEpoch"))!=reply.epoch.toLong()||
            audit.optBoolean("cancelRequested"))return null
        val kind=audit.opt("taskProjection");val unresolved=audit.opt("hasUnresolvedOperations")
        val loadedProof=(inOwnReader||inOwnPicker||kind=="detail_window"&&audit.opt("taskCoreReadLimited")==false)&&unresolved==false
        // The cold header omits operation settlement. Its exact match is a prompt to
        // check this one canonical reply, never proof that the original is readable.
        val checkableSummary=allowSummary&&kind=="summary"&&audit.opt("auditBodyIncluded")==false&&audit.opt("id")==reply.taskId&&
            (!audit.has("metadataLimited")||audit.opt("metadataLimited")==false)&&
            (!audit.has("cancelRequested")||audit.opt("cancelRequested")==false)&&
            (!audit.has("hasUnresolvedOperations")||unresolved==false)
        if(!loadedProof&&!checkableSummary)return null
        return files.map{file->LiveReplyDocument(file,OriginalReplyDocument.Target(source.conversation,source.generation,source.message,source.version,
            source.fingerprint,nativeReply,inputId,reply.taskId,reply.runId,reply.epoch,file.id,file.sha256),!loadedProof)}
    }
    /** Native loaded Chat row and its already projected Team link, not a Web-supplied task or Team target. */
    private fun originalChatTeamDelivery(conversation: String, message: String, revision: Long): TaskStore.OwnerWorkDeliveryRef? {
        val shown = controller.state
        if (section != null || taskViewId != null || taskReturnSection != null || !controller.webPrivateOwnerVisible() ||
            phoneComposer.intent || controller.hasChannelPhoneOrigin || controller.conversationReader != null ||
            controller.queuedInputsPage != null || shown.filePath != null || shown.chatAttachmentPickerOpen ||
            controller.attachmentOutcomes.state.open || shown.conversationId != conversation ||
            controller.composerDraft?.let { it.key == "conversation:$conversation" && it.conversationId == conversation } != true) return null
        val reply = shown.messages.singleOrNull { it.id == message } ?: return null
        val result = reply.taskResult ?: return null
        if (reply.role != "assistant" || reply.sourceRevision != revision || reply.taskId != result.taskId ||
            result.kind != "owner_work" || result.noticeKind != null || result.status !in setOf("needs_confirmation", "completed")) return null
        val work = shown.tasks.singleOrNull { it.id == result.taskId } ?: return null
        val raw = work.raw
        if (!OwnerWorkTask.isOwnerWork(raw) || raw.opt("actorId") != "owner" || raw.opt("spaceId") != "private" ||
            raw.opt("contextAvailable") != true || raw.opt("cancelRequested") != false || raw.opt("conversationId") != conversation ||
            work.status !in setOf("needs_confirmation", "completed") || raw.opt("status") != work.status ||
            raw.opt("runId") != result.runId || raw.optInt("coordinatorEpoch") != result.epoch ||
            raw.optBoolean("ownerWorkTeamsUnavailable")) return null
        val team = raw.optJSONArray("ownerWorkTeams").objects().singleOrNull() ?: return null
        if (team.opt("originalChatTeamReportDeliveryId") != result.deliveryId || team.opt("active") != false ||
            team.opt("status") != "completed" || team.opt("phase") != "completed" || team.opt("conversationId") != conversation ||
            team.opt("parentTaskId") != result.taskId || team.opt("parentRunId") != result.runId ||
            team.optInt("parentEpoch") != result.epoch) return null
        return TaskStore.OwnerWorkDeliveryRef(result.taskId, result.runId, result.epoch, result.deliveryId)
    }

    private fun readOriginalChatTeamReport(id: String, args: JSONObject) {
        checkedPayload(args, setOf("conversationId", "messageId", "sourceRevision"))
        val conversation = requiredString(args, "conversationId").also { require(it.length <= 240) }
        val message = requiredString(args, "messageId").also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
        val revision = requiredLong(args, "sourceRevision").also { require(it > 0) }
        val delivery = originalChatTeamDelivery(conversation, message, revision) ?: error("请回到有这份汇总的原私人对话")
        val document = requireNotNull(documentId)
        val ticket = commandFence.ticket()
        val foreground = controller.displayedPhoneWindowVersion()
        val composerEpoch = controller.composerViewEpochForScopedRead
        val sources = controller.conversationSourceRevision
        // Worker/SQL only probes the captured window fence; the loaded row is read on main.
        fun current() = !closed && documentId == document && commandFence.accepts(ticket) &&
            controller.displayedPhoneWindowAlive(foreground) && controller.composerViewEpochForScopedRead == composerEpoch &&
            controller.conversationSourceRevision == sources && taskViewId == null
        val owner = controller.nativeTokenBudgetHost()
        controller.runTeamPhoneWork {
            val result = runCatching { owner.readOriginalChatTeamReport(conversation, message, revision, delivery, ::current) }
            main.post {
                if (documentId != document) return@post
                val exact = result.mapCatching {
                    check(current() && originalChatTeamDelivery(conversation, message, revision) == delivery) {
                        "原消息或汇总已变化，未打开其他版本"
                    }
                    it
                }
                exact.fold({ complete(id, it, null, retainValue = false) },
                    { complete(id, null, it.message ?: "未读取原团队汇总，可查看原工作", retainValue = false) })
            }
        }
    }

    private fun visibleChatTaskResult(args: JSONObject): UiTaskResult? {
        val history = args.has("resultReaderWindow")
        require(history == args.has("resultSourceRevision")) { "原历史窗口和原消息版本须一起核对" }
        if (!args.has("resultMessageId") && !args.has("conversationId") && !history) return null
        val taskId = requiredString(args, "taskId")
        val conversation = requiredString(args, "conversationId")
        val messageId = requiredString(args, "resultMessageId")
        require(section == null && taskViewId == null && controller.webPrivateOwnerVisible()) { "请回到这条结果所在的原对话" }
        val reader = controller.conversationReader
        val rows = if (history) {
            require(reader != null && reader.window == requiredLong(args, "resultReaderWindow") && reader.conversation == conversation &&
                reader.initialized && !reader.loading && reader.error == null && !reader.limited && controller.canReadConversationWindow(reader)) { "原历史阅读窗口已变化，请重新查看这条回复" }
            reader.messages
        } else {
            require(reader == null && controller.state.conversationId == conversation) { "请回到这条结果所在的原对话" }
            controller.state.messages
        }
        val message = rows.singleOrNull { it.id == messageId && it.role == "assistant" }
            ?: error("原对话中未找到这条已保存的结果")
        val result = message.taskResult ?: error("原消息没有这件工作的完成回执")
        require(message.taskId == taskId && result.taskId == taskId && result.status == "completed" && result.noticeKind == null) { "原消息没有这件工作的完成回执" }
        if (history) require(result.kind == "owner_work" && message.sourceRevision > 0 &&
            message.sourceRevision == requiredLong(args, "resultSourceRevision")) { "原历史消息或其成果版本已变化" }
        return result
    }
    private fun visibleReportFileRun(args: JSONObject): AttachmentResultRun {
        checkedPayload(args, setOf("taskId", "resultMessageId", "conversationId", "expectedRunId", "expectedEpoch"))
        val id = requiredString(args, "taskId")
        val run = requiredString(args, "expectedRunId")
        val epoch = requiredInt(args, "expectedEpoch")
        val message = requiredString(args, "resultMessageId")
        val conversation = requiredString(args, "conversationId")
        require(section == null && taskViewId == id && controller.conversationReader == null && controller.webPrivateOwnerVisible()) {
            "请从这份原汇报打开本轮文件"
        }
        require(epoch > 0 && id.length <= 80 && run.length <= 80 && message.length <= 512 && conversation.length <= 240)
        val task = requireTask(id).raw
        val report = AttachmentResultRun.Report.current(task)
        require(report != null && report.messageId == message && report.conversationId == conversation) { "原汇报已变化，没有打开其他文件" }
        return AttachmentResultRun(id, run, epoch, report).also {
            check(it.matchesCompletedResult(task)) { "原汇报或本轮文件来源已变化，没有打开其他成果" }
        }
    }
    private fun currentReportFileRun(run: AttachmentResultRun): Boolean =
        section == null && taskViewId == run.taskId && controller.conversationReader == null && controller.webPrivateOwnerVisible() &&
            controller.state.tasks.singleOrNull { it.id == run.taskId }?.let { run.matchesCompletedResult(it.raw) } == true

    /** Current native original Work for historical receipt display only; this does not read a task or open a file. */
    private fun originalScriptHistoryDocument(task: String, run: String, epoch: Int): String? {
        val document = documentId ?: return null
        if (closed || section != null || taskViewId != task || !controller.webPrivateOwnerVisible()) return null
        val raw = controller.state.tasks.singleOrNull { it.id == task }?.raw ?: return null
        return document.takeIf {
            raw.opt("id") == task && raw.opt("runId") == run && raw.opt("coordinatorEpoch") == epoch &&
                raw.opt("taskProjection") == "detail_window" && raw.opt("taskCoreReadLimited") == false &&
                raw.opt("auditBodyIncluded") == true && raw.opt("contextAvailable") == true &&
                raw.opt("contextWithdrawn") != true && raw.opt("sourceCurrentReadRequired") != true &&
                raw.opt("kind") == "assistant" && raw.opt("actorId") == "owner" && raw.opt("spaceId") == "private" &&
                raw.opt("executionRole") == "owner_work" &&
                listOf("origin", "developmentContext", "teamRunId", "memberId", "parentTaskId", "ownerPhoneControl", "ownerDeviceWork")
                    .all(raw::isNull)
        }
    }

    private fun requireTask(id: String): UiTask = controller.state.tasks.firstOrNull { it.id == id } ?: error("原任务不在当前读取窗口，请刷新后重新打开")
    private fun leaveTask() { controller.projectPackages.retireDirectViteExport(); pythonOutput.clear();receivedTextResult.clear();sentPhoneResult.clear();reportDraftOffer=null;reportDraftHandoff=null;projectPreview?.retireLocalWork();projectDraftDifference.clearWorkCopy();firstNodeWebOutput.clear();nodeCompilerOutput.clear();dependencyComparison=null;projectGoal=null; phoneViewer.clear(); localPages.clear(); projectDevelopment.clear(); butlerPreparations.clear(); setupPages.clear(); tokenBudgets.clear(); profilePages.clear(); projectWorkPages.clear(); composerModels.clear(); taskViewId = null; controller.closeWebTaskView(); inference.clear(); trajectory.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); personal.clear(); memoryHistory.clear(); conversationOrganization.clear(); taskCapabilities.leave(); deviceTaskScope.clear(); phoneControl.clear(); projectRuntime.clear(); projectLivePreview?.clear(); modelDiagnostics.clear(); attachmentPages.clear(); remoteTasks.clear(); teamEvidence.clear(); experience.clear(); routeReferences.clear(); commandFence.invalidate(); taskReviewGeneration++; taskPage?.let(controller::leaveGroupTask); taskPage = null }
    private fun leaveConnection() {
        openedRetainedDraftCopy = null
        controller.projectFileImports.retireSuppliedLock()
        if(section=="remote")connectionPages.hostGoalOrigins.closeSaved(); localSourceIntake.close(); projectGitImport.close(); phoneViewer.clear(); controller.webChannels.clear(); controller.webSkills.clear(); commandFence.invalidate(); ++domainGeneration; modelReviews.clear(); automation.clear(); mcpImports.clear(); executionReviews.clear(); sourceReader.clear(); messageQuotes.clear(); inference.clear(); trajectory.clear(); skillDrafts.clear(); memoryDrafts.clear(); knowledge.clear(); personal.clear(); memoryHistory.clear(); conversationOrganization.clear(); taskCapabilities.leave(); deviceTaskScope.clear(); phoneControl.clear(); projectRuntime.clear(); projectLivePreview?.clear(); modelDiagnostics.clear(); attachmentPages.clear(); remoteTasks.clear(); teamEvidence.clear(); experience.clear(); routeReferences.clear(); connectionPages.clear(); teamPages.clear(); groupPages.clear(); mediaPages.clear(); realtimePages.clear(); proxyPages.clear(); planPages.clear(); commitmentPages.clear(); tokenBudgets.clear(); profilePages.clear(); projectWorkPages.clear(); devicePages.clear(preserveModelPlace=true); pluginPages.clear(); localPages.clear(); projectDevelopment.clear(); butlerPreparations.clear(); setupPages.clear(); accountWorkspace.invalidate(); composerModels.clear(); aiSources.clear(); conversationModels.clear(); deviceWindow?.close(); deviceWindow = null; section?.let { if (it == "memory") controller.leaveOwnerDomain("memoryReview") else if (it in ownerDomains) controller.leaveOwnerDomain(it) }; section?.takeIf { it == "archives" || it == "storage" }?.let(dataPages::close); section = null
    }
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
    private fun requireAttachmentDraft(args: JSONObject) {
        check(section == null && taskViewId == null && controller.webPrivateOwnerVisible() && controller.conversationReader == null &&
            !controller.state.chatBusy && !controller.state.chatSubmitting && !controller.state.phoneSubmitting &&
            controller.composerDraft?.let { draft -> draft.revision == requiredLong(args, "revision") &&
                draft.conversationId == controller.state.conversationId.orEmpty() && controller.composerPending.none { it.draftKey == draft.key } } == true) {
            "原私人草稿已变化，没有把文件加入其他输入"
        }
    }
    private fun requireComposer(args: JSONObject, optional: Boolean = false) {
        val draft = controller.composerDraft ?: error("原输入尚未恢复")
        if (!optional || args.has("key") || args.has("epoch")) require(requiredString(args, "key") == draft.key && requiredInt(args, "epoch") == controller.composerViewEpoch) { "原输入窗口已变化" }
    }
    private fun requireFile(args: JSONObject) {
        val state = controller.state
        require(state.projectId == requiredString(args, "projectId") && state.filePath == checkedPath(args) && state.fileVersion == requiredInt(args, "version")) { "原项目文件或版本已变化，编辑草稿仍保留，请重新核对" }
    }
    private fun requireConversationReader(args: JSONObject, closing: Boolean = false, earlier: Boolean = false): dev.pocketengine.ui.ConversationReadPage {
        val page = controller.conversationReader ?: error("原历史阅读窗口已关闭")
        require(page.window == requiredLong(args, "window") && page.conversation == requiredString(args, "conversationId") &&
            (closing || controller.canReadConversationWindow(page) ||
                earlier && section == null && taskViewId == null && !phoneComposer.intent && controller.canPageSearchConversationWindow(page))) { "原历史阅读窗口已变化，请重新打开" }
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

