package dev.pocketengine.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.Build
import android.Manifest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pocketengine.EngineApplication
import dev.pocketengine.MainActivity
import dev.pocketengine.core.ModelConfig
import dev.pocketengine.runtime.ModelGateway
import java.util.UUID
import java.io.File
import android.net.Uri
import dev.pocketengine.features.attachments.AttachmentsModule
import dev.pocketengine.features.attachments.OwnerAttachmentImport
import dev.pocketengine.media.RealtimeSessionModule
import dev.pocketengine.features.assistant.OwnerComposerState
import dev.pocketengine.features.assistant.OwnerComposerStore
import java.util.concurrent.Executors
import org.json.JSONObject

/** One native owner UI for the existing engine; backend events remain the source of truth. */
internal class PocketWorldController(private val activity: ComposeActivity, private val app: EngineApplication) {
    private var webWindowEpoch = 0L
    @Volatile private var phoneViewerWindowEpoch = 0L
    private var phoneWindowIdentity: Triple<String, String?, Int?>? = null
    private fun syncPhoneWindowIdentity(value: PocketUiState) {
        val task = value.activeTaskId?.let { id -> value.tasks.find { it.id == id } }
        val identity = value.activeTaskId?.let { id ->
            Triple(id, task?.raw?.optString("runId")?.takeIf { it.isNotBlank() },
                task?.raw?.takeIf { it.has("coordinatorEpoch") }?.optInt("coordinatorEpoch"))
        }
        if (identity != phoneWindowIdentity) { webWindowEpoch++; phoneWindowIdentity = identity }
    }
    private var stateBacking by mutableStateOf(PocketUiState())
    var state: PocketUiState
        get() = stateBacking
        private set(value) {
            val previous = stateBacking
            syncPhoneWindowIdentity(value)
            if (previous.conversationId != value.conversationId || previous.projectId != value.projectId ||
                previous.filePath != value.filePath || previous.workspaceArtifactEditorOrigin != value.workspaceArtifactEditorOrigin ||
                previous.workspaceArtifactView?.source != value.workspaceArtifactView?.source ||
                previous.workspaceArtifactView?.fromTaskId != value.workspaceArtifactView?.fromTaskId ||
                previous.requestedTaskRevision != value.requestedTaskRevision ||
                previous.requestedProjectRevision != value.requestedProjectRevision ||
                previous.requestedConnectionRevision != value.requestedConnectionRevision) { webWindowEpoch++; phoneViewerWindowEpoch++ }
            stateBacking = value
        }
    var deviceWorkspaceRevision by mutableStateOf(0L)
        private set
    internal var remoteAttentionNotice by mutableStateOf<JSONObject?>(null)
        private set
    internal fun showRemoteAttention(locator: JSONObject) { remoteAttentionNotice=JSONObject(locator.toString()); if(locator.optJSONObject("original")?.optString("fingerprint")!=locator.optJSONObject("current")?.optString("fingerprint"))state=state.copy(notice="原通知对应旧状态；已打开原任务，问题和审批将重新查询，不响应旧请求。"); focusTask(locator.getString("taskId")) }
    internal fun consumeRemoteAttention(taskId: String) { if(remoteAttentionNotice?.optString("taskId")==taskId)remoteAttentionNotice=null }
    var originalTaskNotice by mutableStateOf<JSONObject?>(null)
        private set
    internal fun showOriginalTaskNotice(original: JSONObject) {
        originalTaskNotice = JSONObject().put("taskId", original.getString("id"))
            .put("runId", original.optString("noticeOriginalRunId")).put("operationId", original.optString("noticeOriginalOperationId"))
            .put("expired", original.optBoolean("expired")).put("content", original.optJSONObject("noticeContent"))
        focusTask(original.getString("id"))
    }
    internal fun showTaskNoticeWarning(message: String) { state = state.copy(notice = message) }
    fun dismissOriginalTaskNotice() { originalTaskNotice = null }
    private val io = Executors.newSingleThreadExecutor()
    internal val projectWorkbench: ProjectWorkbenchSession by lazy {
        androidx.lifecycle.ViewModelProvider(activity, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = ProjectWorkbenchSession(app) as T
        })[ProjectWorkbenchSession::class.java].also { it.attachOwnerUi() }
    }
    private val controls = Executors.newSingleThreadExecutor()
    private val realtimeIo = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(8))
    private val phonePreviewIo = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(1))
    private val main = Handler(Looper.getMainLooper())
    private val voiceContextEpoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var voiceForeground = false
    private val voiceInput = dev.pocketengine.media.voice.input.VoiceDraftInput(::voiceInputChanged)
    internal var taskAnnouncementState by mutableStateOf(JSONObject().put("enabled", false))
        private set
    internal var taskAnnouncementSaving by mutableStateOf(false)
        private set
    internal var ownerExecutionPolicy by mutableStateOf(JSONObject())
        private set
    internal var ownerExecutionSaving by mutableStateOf(false)
        private set
    internal var taskScreenshotPolicy by mutableStateOf(JSONObject())
        private set
    internal var taskScreenshotSaving by mutableStateOf(false)
        private set
    private val taskAnnouncements: dev.pocketengine.media.voice.tasks.OwnerTaskAnnouncements by lazy { dev.pocketengine.media.voice.tasks.OwnerTaskAnnouncements(
        { taskId -> app.tasks.getForUi(taskId, false) },
        { _ -> voiceForeground && privateWorkspaceView() && app.executionAllowed() && activity.media.taskAnnouncementsAllowed() },
        activity.media.taskAnnouncementAudio()
    ) { main.post { if (!closed) taskAnnouncementState = taskAnnouncements.snapshot() } } }
    private fun refreshTaskAnnouncementPreference() {
        val preference = runCatching { app.settings.taskAnnouncementsEnabled() }
        taskAnnouncements.setEnabled(preference.getOrDefault(false))
        taskAnnouncementState = taskAnnouncements.snapshot().put("preferenceKnown", preference.isSuccess)
        preference.exceptionOrNull()?.let { state = state.copy(error = "状态播报设置待核对：" + message(it)) }
    }
    internal fun saveTaskAnnouncements(enabled: Boolean, done: (JSONObject?, String?) -> Unit) {
        check(privateWorkspaceView() && voiceForeground) { "请在前台私人设置中选择状态播报" }
        check(!taskAnnouncementSaving) { "请先等待原状态播报设置回执" }
        taskAnnouncementSaving = true
        try { io.execute {
            val result = runCatching { check(privateWorkspaceView() && voiceForeground); app.settings.saveTaskAnnouncementsEnabled(enabled) }
            main.post {
                taskAnnouncementSaving = false
                if (!closed) refreshTaskAnnouncementPreference()
                done(if (result.isSuccess) JSONObject().put("enabled", enabled).put("saved", true) else null, result.exceptionOrNull()?.let(::message))
            }
        } } catch (error: Exception) { taskAnnouncementSaving = false; done(null, message(error)) }
    }
    internal fun stopTaskAnnouncement(readoutId: String) { taskAnnouncements.stop(readoutId) }
    internal fun readTaskScreenshotPolicy(done: (JSONObject?, String?) -> Unit = { _, _ -> }) {
        check(!closed && privateWorkspaceView()) { "请回私人设置读取截图留存" }
        try { controls.execute {
            val result = runCatching { app.taskScreenshotPolicy() }
            main.post { if (!closed) result.getOrNull()?.let { taskScreenshotPolicy = JSONObject(it.toString()) }; result.fold({ done(it, null) }, { done(null, message(it)) }) }
        } } catch (failure: Exception) { done(null, message(failure)) }
    }
    internal fun changeTaskScreenshotPolicy(revision: Long, retentionMillis: Long, done: (JSONObject?, String?) -> Unit) {
        check(!closed && privateWorkspaceView() && voiceForeground && !taskScreenshotSaving) { "请在原私人设置核对截图留存，并等待原设置回执" }
        val window = webWindowTicket(); taskScreenshotSaving = true
        try { controls.execute {
            val result = runCatching {
                check(!closed && voiceForeground && privateWorkspaceView() && webWindowTicket() == window) { "原截图设置窗口已变化" }
                app.changeTaskScreenshotRetention(revision, retentionMillis)
            }
            val actual = runCatching { app.taskScreenshotPolicy() }.getOrNull()
            main.post { taskScreenshotSaving = false; if (!closed) actual?.let { taskScreenshotPolicy = JSONObject(it.toString()) }; result.fold({ done(it, null) }, { state = state.copy(error = message(it)); done(null, message(it)) }) }
        } } catch (failure: Exception) { taskScreenshotSaving = false; done(null, message(failure)) }
    }
    internal fun deleteTaskScreenshot(task: UiTask, imageId: String, done: (JSONObject?, String?) -> Unit) {
        check(webPrivateOwnerVisible()) { "请在原私人任务核对截图" }
        val window = webWindowTicket(); val run = task.raw.getString("runId"); val epoch = task.raw.getInt("coordinatorEpoch")
        try { controls.execute {
            val result = runCatching {
                check(webPrivateOwnerVisible() && webWindowTicket() == window) { "原截图审阅窗口已变化" }
                app.deleteTaskScreenshot(task.id, run, epoch, imageId)
            }
            main.post { result.fold({ done(it, null) }, { done(null, message(it)) }) }
        } } catch (failure: Exception) { done(null, message(failure)) }
    }
    internal fun readOwnerExecutionPolicy(done: (JSONObject?, String?) -> Unit = { _, _ -> }) {
        check(!closed && privateWorkspaceView()) { "请回到本机私人设置读取运行状态" }
        try { controls.execute {
            val result = runCatching { app.ownerExecutionState() }
            main.post { if (!closed) result.getOrNull()?.let { ownerExecutionPolicy = JSONObject(it.toString()) }; result.fold({ done(it, null) }, { done(null, message(it)) }) }
        } } catch (failure: Exception) { done(null, message(failure)) }
    }
    internal fun changeOwnerExecution(revision: Long, enabled: Boolean, done: (JSONObject?, String?) -> Unit) {
        check(!closed && privateWorkspaceView() && voiceForeground && !ownerExecutionSaving) { "请在原私人设置核对运行开关，并等待原操作回执" }
        val window = webWindowTicket()
        ownerExecutionSaving = true
        try { controls.execute {
            val result = runCatching {
                check(!closed && voiceForeground && privateWorkspaceView() && webWindowTicket() == window) { "原运行开关审阅已关闭" }
                app.changeOwnerExecutionEnabled(revision, enabled)
            }
            val observed = result.getOrNull() ?: runCatching { app.ownerExecutionState() }.getOrNull()
            main.post {
                ownerExecutionSaving = false
                if (!closed) { observed?.let { ownerExecutionPolicy = JSONObject(it.toString()) }; result.exceptionOrNull()?.let { state = state.copy(error = "原运行开关结果待核对：" + message(it)) } }
                result.fold({ done(it, null) }, { done(null, message(it)) })
            }
        } } catch (failure: Exception) { ownerExecutionSaving = false; done(null, message(failure)) }
    }
    internal val projectPackages = ProjectPackageCoordinator(activity, app, io, main, ::privateWorkspaceView) { checkout ->
        closeProjectPreview()
        closeProjectLivePreview()
        projectWorkbench.selectCheckout(checkout.getString("projectId"), checkout.getString("id"), replace = true)
        projectWorkbench.selectView(checkout.getString("projectId"), 1)
        selectProject(checkout.getString("projectId"))
        developmentCheckoutId = checkout.getString("id"); developmentNavigationRevision++
    }
    internal val ownerArchives = OwnerBackupCoordinator(activity, app, io, main, ::privateWorkspaceView, { checkout ->
        closeProjectPreview(); closeProjectLivePreview()
        projectWorkbench.selectCheckout(checkout.getString("projectId"), checkout.getString("id"), replace = true)
        projectWorkbench.selectView(checkout.getString("projectId"), 1)
        selectProject(checkout.getString("projectId")); developmentCheckoutId = checkout.getString("id"); developmentNavigationRevision++
    }, { file -> attachmentOutcomes.openFile(file) })
    internal val projectFileImports = ProjectFileImportCoordinator(activity, app, io, main, ::privateWorkspaceView, ::refresh)
    internal val skillDocuments = SkillDocumentIntents()
    internal val projectStorage = ProjectStorageCoordinator(app, io, main, ::privateWorkspaceView) { id -> ownerArchives.openCache(id) }
    internal fun exportStorageDiagnostics() {
        check(webPrivateOwnerVisible()) { "请在原私人页面核对诊断导出" }
        val ticket = projectStorage.prepareDiagnosticExport() ?: error("原诊断快照未审阅或导出窗口已变化")
        try { activity.createStorageDiagnostic(ticket) }
        catch (failure: RuntimeException) { projectStorage.diagnosticPickerUnavailable(ticket); throw failure }
    }
    internal val projectGitImports = ProjectGitImportCoordinator(app, io, main, ::privateWorkspaceView, { state.requestedProjectRevision.toString() + ":" + projectWorkbench.navigationRevision() }) { checkout ->
        closeProjectPreview(); closeProjectLivePreview()
        projectWorkbench.selectCheckout(checkout.getString("projectId"), checkout.getString("id"), replace = true)
        projectWorkbench.selectView(checkout.getString("projectId"), 1)
        selectProject(checkout.getString("projectId")); developmentCheckoutId = checkout.getString("id"); developmentNavigationRevision++
    }
    internal val projectGit = ProjectGitCoordinator(app, io, main, ::privateWorkspaceView) { checkout ->
        closeProjectPreview(); closeProjectLivePreview()
        projectWorkbench.selectCheckout(checkout.getString("projectId"), checkout.getString("id"), replace = true)
        projectWorkbench.selectView(checkout.getString("projectId"), 0)
        selectProject(checkout.getString("projectId")); developmentCheckoutId = checkout.getString("id"); developmentNavigationRevision++
        refresh()
    }
    internal val attachmentOutcomes = AttachmentOutcomeCoordinator(activity,app,io,main,::outcomeOwnerWindow,::focusTask,::releaseOutcomeWindow).also { flow -> app.teamWorkspaceOutcomes?.let { flow.bindTeamOutcomes(it,::openTeamAssignment) } }
    fun openTeamArtifact(selected:dev.pocketengine.features.attachments.TeamWorkspaceOutcomeHost.Selection) = attachmentOutcomes.openTeamArtifact(selected)
    fun openTeamProjectPackage(runId: String) {
        if (closed || groupTaskPage != null || projectPackages.state.busy) return
        val navigation = projectWorkbench.navigationRevision(); val projectRequest = state.requestedProjectRevision; val selectedOwnerDomain = ownerDomain; val domainEpoch = ownerEpochs["teams"] ?: 0
        try { io.execute {
            val result = runCatching { app.tasks.teamProjectPackageForOwner(runId) }
            main.post {
                if (closed || groupTaskPage != null || ownerDomain != selectedOwnerDomain || domainEpoch != (ownerEpochs["teams"] ?: 0) || navigation != projectWorkbench.navigationRevision() || projectRequest != state.requestedProjectRevision) return@post
                result.fold({ selected ->
                    runCatching {
                        val project = selected.view().getJSONObject("evidence").getString("projectId")
                        val intendedRevision = state.requestedProjectRevision + 1
                        // Busy/pending-operation guard precedes all identity/navigation changes.
                        projectPackages.queueFromTeam(selected) { !closed && privateWorkspaceView() && projectWorkbench.navigationRevision() == navigation && state.requestedProjectRevision == intendedRevision && state.projectId == project }
                        if (ownerDomain == "teams") leaveOwnerDomain("teams")
                        selectProject(project)
                        state = state.copy(requestedProjectId = project, requestedProjectRevision = intendedRevision)
                    }.onFailure { state = state.copy(error = message(it)) }
                }, { state = state.copy(error = message(it)) })
            }
        } } catch (error: Exception) { state = state.copy(error = message(error)) }
    }

    private fun openTeamAssignment(locator:JSONObject) { state=state.copy(teamRunId=locator.getString("teamRunId"),teamAssignmentFocus=JSONObject(locator.toString()));feature("teams","get",JSONObject().put("runId",locator.getString("teamRunId"))) }
    fun openTeamChild(task: UiTask) {
        // A locator opens the original team's reviewed controls; it never calls generic resume.
        val locator = teamChildLocator(task) ?: run { state = state.copy(error = "原团队来源身份不完整，请从原团队记录核对；未继续任务"); return }
        openTeamAssignment(locator)
    }
    fun openTaskOutcomes(taskId:String) = attachmentOutcomes.openTask(taskId)
    fun openGroupArtifact(selection:dev.pocketengine.ui.groups.GroupArtifactSelection) = attachmentOutcomes.openGroup(selection)
    fun openGroupMediaOutcome(task:UiTask,operation:String) = attachmentOutcomes.openGroupOperation(task,operation)
    fun openAttachmentOutcome(meta:JSONObject) = attachmentOutcomes.openFile(meta)
    private fun resolveOriginalMessageAttachments(conversation:String){
        if(conversation.isBlank())return
        io.execute{try{val actual=app.ownerConversationAttachments(conversation);main.post{if(!closed&&state.conversationId==conversation)state=state.copy(messages=state.messages.map{message->val row=message.id?.let(actual::optJSONObject);if(row!=null&&row.optLong("revision")==message.sourceRevision)message.copy(attachmentFiles=row.optJSONArray("files").objects())else message})}}
            catch(_:Exception){/* Retain count/unavailable state; no name is borrowed from the current draft. */}}
    }

    private fun outcomeOwnerWindow():String = groupTaskWindow ?: groupOwnerWindow ?: app.groupOwnerWorkflows.open(activity).also { groupOwnerWindow=it }
    private fun releaseOutcomeWindow(){if(groupTaskPage==null&&ownerDomain!="groups"){groupOwnerWindow?.let{app.groupOwnerWorkflows.close(it)};groupOwnerWindow=null}}
    private val webChatCancelCallbacks = mutableMapOf<String, (JSONObject?, String?) -> Unit>()
    private val webFeatureReceipts = mutableMapOf<String, (JSONObject?, String?) -> Unit>()
    private val webOwnerReceipts = mutableMapOf<String, MutableList<(JSONObject?, String?) -> Unit>>()
    private fun completeWebOwnerReceipt(id: String, result: JSONObject?, error: String?) { webOwnerReceipts.remove(id)?.forEach { callback -> runCatching { callback(result?.let { JSONObject(it.toString()) }, error) } } }
    private val webChatCancelBindings = mutableMapOf<String, String>()
    private val pending = mutableMapOf<String, Pair<String, JSONObject>>()
    private val taskReviewReads = mutableMapOf<String, (UiTask?, String?) -> Unit>()
    private var wirelessSettingsLaunched = false
    private val phoneSetupViewRevision = java.util.concurrent.atomic.AtomicLong()
    private val phoneOwnerAppsRequest = java.util.concurrent.atomic.AtomicLong()
    private val phoneOwnerAppPicker = dev.pocketengine.phone.setup.OwnerPhoneApps(app)
    internal var phoneOwnerApps by mutableStateOf<List<dev.pocketengine.phone.setup.OwnerPhoneApps.Selection>>(emptyList())
        private set
    internal var phoneOwnerAppsComplete by mutableStateOf(true)
        private set
    internal var phoneOwnerAppsLoading by mutableStateOf(false)
        private set
    internal var phoneOwnerPolicyBusy by mutableStateOf(false)
        private set
    internal var phoneOwnerPolicyMessage by mutableStateOf<String?>(null)
        private set
    internal var phonePracticeEvidence by mutableStateOf<JSONObject?>(null)
        private set
    internal var phonePracticeReading by mutableStateOf(false)
        private set
    internal var phonePracticeReadError by mutableStateOf<String?>(null)
        private set
    @Volatile private var closed = false
    internal var modelSelectionRevision by mutableStateOf(0L)
        private set
    private var modelSelectionSubscription: AutoCloseable? = null
    private val modelMutations = java.util.concurrent.ConcurrentHashMap.newKeySet<dev.pocketengine.runtime.models.ModelSelectionCoordinator.Mutation>()
    private val ownerComposerStore = OwnerComposerStore(app.filesDir)
    private var ownerComposer: OwnerComposerState? = null
    private var compatibilityChat: dev.pocketengine.features.assistant.CompatibilityChatHandoff.Review? = null
    var compatibilityChatRevision by mutableStateOf(0)
        private set
    private var compatibilityDialog = false
    private var compatibilitySaving = false
    var composerDraft by mutableStateOf<OwnerComposerState.Draft?>(null)
        private set
    var composerPending by mutableStateOf<List<OwnerComposerState.Pending>>(emptyList())
        private set
    var composerSaving by mutableStateOf(false)
        private set
    var phoneDraftAcceptedRevision by mutableStateOf(0L)
        private set
    var phoneDraftAcceptedText = ""
        private set
    private var voiceComposerKey: String? = null
    private val composerSave = Runnable { persistOwnerComposer() }
    private var chatViewEpoch by mutableStateOf(0)
    val composerViewEpoch: Int get() = chatViewEpoch
    internal fun webWindowTicket(): Pair<Triple<Long, Int, Long>, Long> {
        // Snapshot immutable identity even if a JSONObject was mutated in place.
        syncPhoneWindowIdentity(state)
        return Pair(Triple(webWindowEpoch, chatViewEpoch, projectWorkbench.navigationRevision()), artifactContinuationGeneration)
    }
    /** Viewing a selected phone follows owner navigation, not the fallback active task of a sibling run. */
    internal fun webPhoneViewerWindowTicket(): Pair<Triple<Long, Int, Long>, Long> =
        Pair(Triple(phoneViewerWindowEpoch, chatViewEpoch, projectWorkbench.navigationRevision()), artifactContinuationGeneration)
    internal fun webPresentationActivity(): ComposeActivity = activity
    internal fun webPluginFeatureHost() = app.features
    internal fun readOriginalDeviceTask(id: String): JSONObject = app.tasks.get(id, false)
    internal fun controlOriginalDeviceTask(id: String, run: String, epoch: Int, action: String): JSONObject =
        app.controlDeviceExecution(id, run, epoch, action)
    internal fun reviewPendingDeviceGoalCancellation(id: String) = app.reviewPendingDeviceGoalCancellation(id)
    internal fun confirmPendingDeviceGoalCancellation(review: dev.pocketengine.store.TaskStoreDeviceTasks.PendingGoalCancellation) =
        app.confirmPendingDeviceGoalCancellation(review)
    internal fun webUiCanInteract(): Boolean = !closed && voiceForeground && !activity.isFinishing && !activity.isDestroyed
    internal fun webPrivateOwnerVisible(): Boolean = webUiCanInteract() && privateWorkspaceView()
    /** Preview reads never enter the model/action queue or hold the application monitor during helper I/O. */
    internal fun readPhonePreview(input: JSONObject, current: () -> Boolean, done: (JSONObject?, String?, String) -> Unit) {
        check(webPrivateOwnerVisible() && current()) { "请在原私人窗口查看手机画面" }
        val owner = webPhoneViewerWindowTicket()
        val request = JSONObject(input.toString())
        fun valid() = webPrivateOwnerVisible() && webPhoneViewerWindowTicket() == owner && current()
        try {
            phonePreviewIo.execute {
                val result = runCatching {
                    check(valid()) { "原手机画面窗口已关闭" }
                    app.dispatchReviewedOwnerAction("readPhonePreview", request, java.util.function.BooleanSupplier { valid() })
                }
                main.post {
                    if (!valid()) done(null, "原手机画面窗口已变化", "phone_viewer_stale")
                    else result.fold({ done(it, null, "") }, { failure ->
                        val code = when (failure) {
                            is dev.pocketengine.phone.display.PhoneLivePreview.NotReady -> "phone_viewer_starting"
                            is SecurityException -> "phone_viewer_stale"
                            else -> "preview_unavailable"
                        }
                        done(null, message(failure), code)
                    })
                }
            }
        } catch (failure: Exception) { done(null, message(failure), "preview_unavailable") }
    }
    internal fun phoneRouteReferenceHost() = app.nativeOwnerWorkflows.phoneRouteReferencesForOwner()
    internal fun routeReferenceActivity(): androidx.activity.ComponentActivity { check(webPrivateOwnerVisible()); return activity }
    internal fun conversationSkillDraftHost() = app.features.conversationSkillDraftsForOwner()
    internal fun conversationMemoryDraftHost() = app.features.conversationMemoryDraftsForOwner()
    internal fun readOriginalMemorySource(reference: JSONObject): JSONObject { check(webPrivateOwnerVisible()); return app.nativeOwnerWorkflows.source(reference) }
    internal fun readConversationDrafts(taskId: String, done: (JSONObject?, String?) -> Unit) {
        check(webPrivateOwnerVisible()) { "请回到原私人回答查看提案" }
        val owner = webWindowTicket()
        io.execute {
            val result = runCatching { check(webPrivateOwnerVisible() && webWindowTicket() == owner); app.tasks.listConversationDraftsForOwner(taskId) }
            main.post { if (!webPrivateOwnerVisible() || webWindowTicket() != owner) done(null, "原回答窗口已变化，请重新打开"); else result.fold({ done(it, null) }, { done(null, message(it)) }) }
        }
    }
    internal fun localTerminalHost() = app.features
    internal fun describeLocalCheckout(checkout: String): JSONObject = dev.pocketengine.features.local.project.ProjectSources.describe(app.features.module("local").handle("export", JSONObject().put("checkoutId", checkout)))
    private var attachmentComposerKey: String? = null
    private var attachmentComposerEpoch = -1
    private data class OriginalSourceStage(val key:String,val revision:Long,val viewEpoch:Int,val conversation:String)
    private val originalSourceStages=mutableMapOf<String,OriginalSourceStage>()
    private var submittingChatClient:String?=null
    private data class LocalUnsent(val payload:String,val key:String,val revision:Long)
    /** Deliberately not serialized: a cold unknown/not_found never grants permission to send. */
    private val locallyUnsent=mutableMapOf<String,LocalUnsent>()
    private var latestChatGet: String? = null
    var projectPreview by mutableStateOf<dev.pocketengine.features.local.preview.ProjectPreviewBundle.Verified?>(null)
        private set
    var projectPreviewTaskId by mutableStateOf<String?>(null)
        private set
    private var projectPreviewRequest = 0
    var developmentCheckoutId by mutableStateOf<String?>(null)
        private set
    private var developmentOpenRequest: String? = null
    var developmentNavigationRevision by mutableStateOf(0)
        private set
    fun openDevelopmentProject(projectId: String) { developmentOpenRequest = requestOwnerAction("openDevelopmentProject", JSONObject().put("projectId", projectId)) }
    fun returnToDevelopmentProject(projectId: String) { openDevelopmentProject(projectId); developmentNavigationRevision++ }
    fun createDevelopmentProject(name: String, template: String) { developmentOpenRequest = requestOwnerAction("createDevelopmentProject", JSONObject().put("name", name).put("template", template)) }
    fun openProjectPreview(taskId: String, checkoutId: String, previewId: String) {
        val request = ++projectPreviewRequest
        val navigationRevision = projectWorkbench.navigationRevision()
        io.execute {
            try {
                val task = app.tasks.get(taskId, false) ?: error("原项目任务不存在")
                require(task.optJSONObject("developmentContext")?.optString("checkoutId") == checkoutId) { "预览不属于原任务项目" }
                val local = app.features.module("local") as? dev.pocketengine.features.local.LocalModule ?: error("项目模块不可用")
                val preview = local.projectPreviewForOwner(previewId, checkoutId)
                require(ProjectWorkbenchGuard.previewBelongsToTask(task, preview.metadata())) { "预览未登记在原项目任务中，请重新核对" }
                main.post { if (!closed && request == projectPreviewRequest) { projectPreview = preview; projectPreviewTaskId = taskId; enterProjectPreviewIfSelected(task, checkoutId, taskId, navigationRevision) } }
            } catch (failure: Exception) { main.post { if (!closed && request == projectPreviewRequest) state = state.copy(error = message(failure)) } }
        }
    }
    private fun enterProjectPreviewIfSelected(task: JSONObject, checkoutId: String, taskId: String, navigationRevision: Long) {
        val project = task.optJSONObject("developmentContext")?.optString("projectId") ?: return
        if (state.projectId == project && projectWorkbench.checkout(project) == checkoutId) {
            if (projectWorkbench.navigationRevision() != navigationRevision) {
                state = state.copy(notice = "预览已就绪，可切到「预览」查看。")
                return
            }
            projectWorkbench.selectTask(checkoutId, taskId)
            projectWorkbench.selectView(project, ProjectWorkbenchFacet.Preview.index)
            getTask(taskId)
        }
    }
    fun closeProjectPreview() { ++projectPreviewRequest; projectPreview = null; projectPreviewTaskId = null }
    internal data class RegisteredProjectPreview(val taskId: String, val conversationId: String,
        val bundle: dev.pocketengine.features.local.preview.ProjectPreviewBundle.Verified,
        val packageOperationId: String? = null, val selectionAvailable: Boolean = false,
        val selectionUnavailableReason: String? = null)
    internal data class PreviewDraftTarget(val key: String, val revision: Long, val epoch: Int, val conversationId: String,
        val ownerWindow: Pair<Triple<Long, Int, Long>, Long>)

    /** Read an already registered original preview. Opening the page never builds or starts a task. */
    internal fun openRegisteredProjectPreview(project: String, ownerCurrent: () -> Boolean, done: (RegisteredProjectPreview?, String?) -> Unit) {
        check(webPrivateOwnerVisible() && ownerCurrent() && state.projectId == project) { "请从当前私人项目打开原预览" }
        val candidates = state.tasks.filter { it.raw.optJSONObject("developmentContext")?.optString("projectId") == project &&
            it.raw.optString("kind") == "assistant" && it.raw.optString("actorId") == "owner" && it.raw.optString("spaceId") == "private" && it.raw.optJSONObject("origin") == null }
            .sortedWith(compareByDescending<UiTask> { it.status in ProjectWorkbenchGuard.activeStatuses }.thenByDescending { it.updatedAt }).map { it.id }
        require(candidates.isNotEmpty()) { "当前项目没有可读取的已登记预览；请先查看原项目任务，没有自动构建或新建任务" }
        val request = ++projectPreviewRequest
        try { io.execute {
            val result = runCatching {
                check(webPrivateOwnerVisible() && ownerCurrent()) { "原项目预览窗口已关闭" }
                val local = app.features.module("local") as? dev.pocketengine.features.local.LocalModule ?: error("原项目模块不可用")
                var selected: RegisteredProjectPreview? = null
                var lastFailure: Exception? = null
                search@ for (id in candidates) {
                    val task = app.tasks.get(id, false) ?: continue
                    if (task.optJSONObject("developmentContext")?.optString("projectId") != project) continue
                    for (record in task.optJSONArray("developmentPreviews").objects().asReversed()) {
                        try {
                            val source = app.tasks.ownerPreviewSource(id, record.getString("previewId"))
                            val bundle = local.projectPreviewForOwner(source.getString("previewId"), source.getString("checkoutId"))
                            local.requireProjectPreviewCurrent(bundle)
                            val after = app.tasks.ownerPreviewSource(id, bundle.identity)
                            check(dev.pocketengine.store.TaskStore.capabilityFingerprint(source) == dev.pocketengine.store.TaskStore.capabilityFingerprint(after)) { "原项目预览登记已变化" }
                            selected = RegisteredProjectPreview(id, source.getString("conversationId"), bundle, selectionAvailable = true)
                            break@search
                        } catch (failure: Exception) { lastFailure = failure }
                    }
                }
                selected ?: throw IllegalStateException("没有仍可核对的原项目预览；请查看原任务重新生成后再打开", lastFailure)
            }
            main.post {
                if (closed || request != projectPreviewRequest || !ownerCurrent() || !webPrivateOwnerVisible() || state.projectId != project) {
                    done(null, "原项目窗口已变化，未把迟到预览打开到另一页面")
                } else result.fold({ selected ->
                    projectPreview = selected.bundle; projectPreviewTaskId = selected.taskId; done(selected, null)
                }, { done(null, message(it as? Exception ?: Exception(it))) })
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { done(null, "原项目页面已关闭") }
    }

    /** Metadata only. The caller explicitly navigates through the existing project selector next. */
    internal fun inspectProjectPackagePreview(task: String, operation: String, ownerCurrent: () -> Boolean, done: (JSONObject?, String?) -> Unit) {
        check(webPrivateOwnerVisible() && ownerCurrent()) { "请从原私人任务打开固定项目包" }
        val request = ++projectPreviewRequest
        try { io.execute {
            val result = runCatching {
                check(webPrivateOwnerVisible() && ownerCurrent()) { "原项目包读取窗口已关闭" }
                val source = app.tasks.ownerPackagePreviewSource(task, operation)
                val local = app.features.module("local") as? dev.pocketengine.features.local.LocalModule ?: error("原项目模块不可用")
                val choices = local.inspectProjectPackagePreviewForOwner(source)
                requireSamePackagePreviewSource(source, app.tasks.ownerPackagePreviewSource(task, operation))
                choices
            }
            main.post {
                if (closed || request != projectPreviewRequest || !ownerCurrent() || !webPrivateOwnerVisible()) { done(null, "原项目包窗口已变化，未打开迟到的成果"); return@post }
                result.fold({ choices ->
                    if (state.projects.none { it.id == choices.getString("projectId") }) done(null, "原私人项目已不在当前项目列表，没有自动创建项目或副本")
                    else done(choices, null)
                }, { done(null, message(it as? Exception ?: Exception(it))) })
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { done(null, "原项目页面已关闭") }
    }

    /** Exact original receipt and chosen archive member; this never prepares a checkout. */
    internal fun openRegisteredProjectPackagePreview(task: String, operation: String, entry: String,
        ownerCurrent: () -> Boolean, done: (RegisteredProjectPreview?, String?) -> Unit) {
        check(webPrivateOwnerVisible() && ownerCurrent()) { "请从原私人项目打开固定包预览" }
        val project = state.projectId ?: error("请先选择原项目")
        require(state.projects.any { it.id == project }) { "原私人项目不在当前列表" }
        val request = ++projectPreviewRequest
        try { io.execute {
            val result = runCatching {
                check(webPrivateOwnerVisible() && ownerCurrent()) { "原项目包窗口已关闭" }
                val source = app.tasks.ownerPackagePreviewSource(task, operation)
                check(source.getString("projectId") == project) { "请先明确选择这个原任务的项目" }
                val local = app.features.module("local") as? dev.pocketengine.features.local.LocalModule ?: error("原项目模块不可用")
                val bundle = local.openProjectPackagePreviewForOwner(source, entry)
                val current = runCatching { local.requireProjectPackagePreviewCurrent(bundle, source) }
                requireSamePackagePreviewSource(source, app.tasks.ownerPackagePreviewSource(task, operation))
                RegisteredProjectPreview(task, source.getString("conversationId"), bundle, operation, current.isSuccess,
                    current.exceptionOrNull()?.let { message(it as? Exception ?: Exception(it)) })
            }
            main.post {
                if (closed || request != projectPreviewRequest || !ownerCurrent() || !webPrivateOwnerVisible() || state.projectId != project || state.projects.none { it.id == project }) {
                    done(null, "原项目或窗口已变化，未把迟到成果打开到另一页面")
                } else result.fold({ selected -> projectPreview = selected.bundle; projectPreviewTaskId = selected.taskId; done(selected, null) },
                    { done(null, message(it as? Exception ?: Exception(it))) })
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { done(null, "原项目页面已关闭") }
    }

    /** Archive/source IO stays on the original worker and outside every TaskStore monitor. */
    internal fun checkProjectPreviewSelection(original: RegisteredProjectPreview, ownerCurrent: () -> Boolean, done: (Boolean, String?) -> Unit) {
        val request = projectPreviewRequest
        fun current() = !closed && ownerCurrent() && webPrivateOwnerVisible() && request == projectPreviewRequest &&
            projectPreview === original.bundle && projectPreviewTaskId == original.taskId
        check(current()) { "原预览选择窗口已变化" }
        try { io.execute {
            val result = runCatching {
                check(current()) { "原预览已关闭" }
                val local = app.features.module("local") as? dev.pocketengine.features.local.LocalModule ?: error("原项目模块不可用")
                val operation = original.packageOperationId
                val before = if (operation == null) app.tasks.ownerPreviewSource(original.taskId, original.bundle.identity) else app.tasks.ownerPackagePreviewSource(original.taskId, operation)
                if (operation == null) local.requireProjectPreviewCurrent(original.bundle) else local.requireProjectPackagePreviewCurrent(original.bundle, before)
                val after = if (operation == null) app.tasks.ownerPreviewSource(original.taskId, original.bundle.identity) else app.tasks.ownerPackagePreviewSource(original.taskId, operation)
                requireSamePackagePreviewSource(before, after)
            }
            main.post {
                if (!current()) done(false, "原预览窗口已变化，未启用迟到的选择")
                else done(result.isSuccess, result.exceptionOrNull()?.let { message(it as? Exception ?: Exception(it)) })
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { done(false, "原项目页面已关闭") }
    }
    private fun requireSamePackagePreviewSource(before: JSONObject, after: JSONObject) {
        check(dev.pocketengine.store.TaskStore.capabilityFingerprint(before) == dev.pocketengine.store.TaskStore.capabilityFingerprint(after)) { "原预览来源登记已变化，请重新核对" }
    }

    internal fun returnToProjectPreviewConversation(conversation: String) {
        check(webPrivateOwnerVisible() && !state.chatSubmitting && conversation.isNotBlank()) { "请先核对原输入回执，再返回项目对话" }
        if (state.conversationId != conversation || composerDraft?.conversationId != conversation) openConversation(conversation)
        check(state.conversationId == conversation && composerDraft?.conversationId == conversation) { "原项目对话草稿尚未就绪" }
    }
    internal fun captureProjectPreviewDraft(conversation: String): PreviewDraftTarget {
        returnToProjectPreviewConversation(conversation)
        val draft = ownerComposer?.active() ?: error("原项目对话草稿尚未读取")
        return PreviewDraftTarget(draft.key, draft.revision, chatViewEpoch, conversation, webWindowTicket())
    }
    private fun previewDraftCurrent(target: PreviewDraftTarget): Boolean {
        val draft = ownerComposer?.active() ?: return false
        return webPrivateOwnerVisible() && !state.chatSubmitting && webWindowTicket() == target.ownerWindow &&
            draft.key == target.key && draft.revision == target.revision && chatViewEpoch == target.epoch &&
            draft.conversationId == target.conversationId && state.conversationId == target.conversationId
    }
    internal fun stageProjectPreviewSelection(task: String, target: PreviewDraftTarget,
        selection: dev.pocketengine.features.local.preview.ProjectPreviewDocument.Selection, ownerCurrent: () -> Boolean,
        packageOperationId: String? = null,
        done: (JSONObject?, String?) -> Unit) {
        check(ownerCurrent() && previewDraftCurrent(target)) { "原草稿已变化，未加入迟到的元素选择" }
        selection.requireCurrent()
        try { io.execute {
            val result = runCatching {
                check(ownerCurrent() && previewDraftCurrent(target)) { "原选择窗口已变化" }
                if (packageOperationId == null) app.ownerPreviewReferences.register(task, selection)
                else app.ownerPreviewReferences.register(task, packageOperationId, selection)
            }
            main.post {
                if (!ownerCurrent() || !previewDraftCurrent(target)) { done(null, "原草稿或选择窗口已变化，未覆盖输入；请明确重新选择"); return@post }
                result.fold({ reference ->
                    try {
                        selection.requireCurrent()
                        check(reference.getString("taskId") == task && reference.getString("conversationId") == target.conversationId)
                        val model = ownerComposer ?: error("原草稿已关闭")
                        val refs = model.active().previewRefs().put(reference)
                        model.editPreviewRefs(target.key, target.revision, dev.pocketengine.features.local.preview.PreviewElementRef.checked(refs))
                        publishComposer(); scheduleComposerSave()
                        state = state.copy(notice = "元素引用已加入原项目对话草稿，尚未发送；发送时会再次核对来源")
                        done(JSONObject().put("draftStaged", true).put("referenceId", reference.getString("referenceId")).put("modelCalled", false).put("executionAuthorized", false), null)
                    } catch (failure: Exception) { done(null, message(failure)) }
                }, { done(JSONObject().put("selectionAvailable", false), message(it as? Exception ?: Exception(it))) })
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { done(null, "原页面已关闭，未加入引用") }
    }
    internal fun removeComposerPreview(index: Int, key: String, viewEpoch: Int, revision: Long) {
        check(webPrivateOwnerVisible() && !state.chatSubmitting) { "原输入正在提交或不在前台" }
        val model = ownerComposer ?: error("原草稿未读取"); val draft = model.active()
        check(draft.key == key && chatViewEpoch == viewEpoch && draft.revision == revision && draft.conversationId == state.conversationId.orEmpty()) { "原草稿版本已变化，未删除其他引用" }
        val refs = draft.previewRefs(); require(index in 0 until refs.length()) { "原元素引用已变化" }
        val next = org.json.JSONArray(); for (i in 0 until refs.length()) if (i != index) next.put(refs.getJSONObject(i))
        model.editPreviewRefs(key, revision, next); publishComposer(); scheduleComposerSave()
    }
    internal fun composerPreviewRefsAvailable(): Boolean {
        val draft = composerDraft ?: return true
        return runCatching { draft.previewRefs().let { it.length() == 0 || app.ownerPreviewReferences?.available(draft.conversationId, it) == true } }.getOrDefault(false)
    }
    var projectLivePreview by mutableStateOf<dev.pocketengine.features.local.runtime.ProjectDevSession.Observed?>(null)
        private set
    var projectLivePreviewTaskId by mutableStateOf<String?>(null)
        private set
    var projectLivePreviewChecking by mutableStateOf(false)
        private set
    private var livePreviewRequest = 0
    private var livePreviewObservation: dev.pocketengine.core.RunControl? = null
    private var livePreviewCurrentCheck = false
    private var livePreviewForeground = false
    internal fun projectLivePreviewForeground(visible: Boolean) {
        livePreviewForeground = visible
        if (!visible) closeProjectLivePreview()
    }
    fun openProjectLivePreview(taskId: String, checkoutId: String, executionId: String) {
        if (!livePreviewForeground || !privateWorkspaceView()) return
        closeProjectLivePreview()
        val request = ++livePreviewRequest
        val navigationRevision = projectWorkbench.navigationRevision()
        val observation = dev.pocketengine.core.RunControl().also { livePreviewObservation = it }
        projectLivePreviewChecking = true
        io.execute {
            try {
                val task = app.tasks.get(taskId, false) ?: error("原开发任务不存在")
                require(task.optString("actorId") == "owner" && task.optString("spaceId") == "private" && task.optJSONObject("origin") == null && task.optJSONObject("developmentContext")?.optString("checkoutId") == checkoutId) { "活预览不是原私人项目任务" }
                val local = app.features.module("local") as? dev.pocketengine.features.local.LocalModule ?: error("原项目模块不可用")
                val binding = JSONObject().put("taskId", taskId).put("runId", task.getString("runId")).put("coordinatorEpoch", task.getInt("coordinatorEpoch")).put("operationId", executionId)
                val observed = local.projectLivePreviewForHost(binding, executionId, observation)
                main.post { if (!closed && livePreviewForeground && request == livePreviewRequest && privateWorkspaceView()) { projectLivePreview = observed; projectLivePreviewTaskId = taskId; projectLivePreviewChecking = false; enterProjectPreviewIfSelected(task, checkoutId, taskId, navigationRevision) } }
            } catch (failure: Exception) { main.post { if (!closed && request == livePreviewRequest) { projectLivePreviewChecking = false; state = state.copy(error = "活预览未打开：" + message(failure)) } } }
        }
    }
    fun closeProjectLivePreview() { ++livePreviewRequest; livePreviewObservation?.cancel(); livePreviewObservation = null; projectLivePreview = null; projectLivePreviewTaskId = null; projectLivePreviewChecking = false }
    fun checkProjectLivePreview(observed: dev.pocketengine.features.local.runtime.ProjectDevSession.Observed) {
        if (closed || projectLivePreview !== observed || livePreviewCurrentCheck) return
        livePreviewCurrentCheck = true
        io.execute {
            var failure: Exception? = null
            try { (app.features.module("local") as dev.pocketengine.features.local.LocalModule).requireProjectLivePreviewForOwner(observed) } catch (error: Exception) { failure = error }
            main.post { livePreviewCurrentCheck = false; if (!closed && projectLivePreview === observed && failure != null) { closeProjectLivePreview(); state = state.copy(notice = "原服务已停止或当前性未知，活页面已关闭；文件、固定成果和原日志保留") } }
        }
    }
    fun recordProjectLivePreviewConsole(observed: dev.pocketengine.features.local.runtime.ProjectDevSession.Observed, taskId: String, level: String, text: String, source: String, line: Int) {
        if (closed || projectLivePreview !== observed || projectLivePreviewTaskId != taskId) return
        val localSource = if (source.startsWith(observed.baseUrl)) source.removePrefix(observed.baseUrl).take(240) else "project"
        io.execute { try { app.tasks.recordDevelopmentLivePreviewConsole(taskId, observed, level, text, localSource, line); main.post { if (!closed) getTask(taskId) } } catch (_: Exception) { /* The original run may already have ended; no second task or page is created. */ } }
    }
    fun sendProjectLiveInput(checkoutId: String, task: UiTask, text: String, retryOriginal: Boolean = false) {
        if (!privateWorkspaceView()) return
        val session = projectWorkbench.agent(checkoutId)
        if (session.liveInputSaving.value || session.liveInputRequest.value != null) return
        try {
            val record = session.liveInput.value?.let { pending ->
                check(retryOriginal) { "已有原输入待核，先查原回执；没有换消息身份重发" }
                dev.pocketengine.features.local.project.ProjectLiveInput.verified(pending)
            } ?: dev.pocketengine.features.local.project.ProjectLiveInput.prepare(task.raw, checkoutId, text)
            require(record.getJSONObject("target").getString("taskId") == task.id) { "原补充属于另一个项目任务，请回原任务核对" }
            session.liveInput.value = record; session.liveInputSaving.value = true; session.liveInputError.value = null
            io.execute {
                try {
                    check(app.features.module("assistant")?.snapshot()?.optBoolean("sendAcknowledgements") == true) { "原会话原生输入宿主尚未准备，未发送或另开任务" }
                    projectWorkbench.persistLiveInput(checkoutId, record)
                    main.post {
                        session.liveInputSaving.value = false
                        if (!closed && privateWorkspaceView() && session.liveInput.value?.optString("identity") == record.getString("identity")) {
                            session.liveInputRequest.value = requestFeature("assistant", "send", dev.pocketengine.features.local.project.ProjectLiveInput.payload(record))
                        }
                    }
                } catch (failure: Exception) { main.post { session.liveInputSaving.value = false; session.liveInputError.value = message(failure) } }
            }
        } catch (failure: Exception) { session.liveInputError.value = message(failure) }
    }
    fun queryProjectLiveInput(checkoutId: String) {
        val session = projectWorkbench.agent(checkoutId); val pending = session.liveInput.value ?: return
        if (session.liveInputRequest.value != null || session.liveInputSaving.value || !privateWorkspaceView()) return
        session.liveInputRequest.value = requestFeature("assistant", "getSubmission", JSONObject().put("clientMessageId", pending.getString("clientMessageId")))
    }
    fun acceptProjectLiveInputReceipt(checkoutId: String, receipt: JSONObject): Boolean {
        val session = projectWorkbench.agent(checkoutId); val record = session.liveInput.value ?: return false
        if (!dev.pocketengine.features.local.project.ProjectLiveInput.acknowledged(record, receipt)) return false
        val target = record.getJSONObject("target").getString("taskId")
        if (session.feedback[target]?.trim() == record.getString("message")) session.feedback.remove(target)
        session.liveInput.value = null; session.liveInputRequest.value = null; session.liveInputError.value = null
        io.execute { try { projectWorkbench.persistLiveInput(checkoutId, null) } catch (_: Exception) { main.post { if (!closed) state = state.copy(notice = "补充已取得原回执；本机待核标记清理未确认，重开后只查询原输入，不自动重发") } } }
        getTask(target); return true
    }
    fun newRemotePreviewPage(taskId:String):dev.pocketengine.features.ssh.SshPreviewWorkflows.Page = app.newRemotePreviewPage(taskId)
    fun newPrivateSshDoctorPage(): dev.pocketengine.features.ssh.SshOwnerWorkflows.DoctorPage = app.newSshDoctorPage()
    fun recordProjectPreviewConsole(preview: dev.pocketengine.features.local.preview.ProjectPreviewBundle.Verified, taskId: String, level: String, text: String, source: String, line: Int) {
        if (closed || projectPreview !== preview || projectPreviewTaskId != taskId) return
        val localSource = if (source.startsWith(preview.origin)) source.removePrefix(preview.origin) else "project"
        io.execute {
            try { app.tasks.recordDevelopmentPreviewConsole(taskId, preview.checkoutId, preview.identity, preview.sourceDigest, level, text, localSource, line); main.post { if (!closed) getTask(taskId) } }
            catch (_: Exception) { main.post { if (!closed) state = state.copy(notice = "预览信息未能写回原任务，请保留当前错误再核对") } }
        }
    }
    private fun composerFailure(error:Throwable)=message(error as? Exception ?: Exception(error))
    private fun composerAttachments(files: List<UiChatAttachment>) = org.json.JSONArray().also { array -> files.forEach { array.put(JSONObject().put("id",it.id).put("sha256",it.sha256).put("name",it.name).put("mimeType",it.mimeType).put("size",it.size)) } }
    private fun publishComposer(select: Boolean = false) {
        val model=ownerComposer ?: return;val draft=model.active();composerDraft=draft;composerPending=model.pending()
        state=state.copy(selectedChatAttachments=draft.attachments().objects().mapNotNull(ChatInputRules::attachment),chatSendMode=draft.mode,
            conversationId=if(select)draft.conversationId.takeIf(String::isNotBlank) else state.conversationId)
    }
    private fun loadOwnerComposer() {
        ownerComposerStore.load().whenComplete { restored,error -> main.post {
            if(closed)return@post
            if(error!=null){state=state.copy(error="原私聊草稿暂不可读取，未清空或覆盖："+composerFailure(error));return@post}
            ownerComposer=restored;publishComposer(true)
            applyCompatibilityChat()
            state.conversationId?.let{feature("assistant","get",JSONObject().put("conversationId",it))}
            // Recovery is query-only. No captured run/model/grant is used to send or resume anything.
            composerPending.forEach { item ->
                if(state.chatReceipts[item.clientId]==null)state=state.copy(chatReceipts=state.chatReceipts+(item.clientId to JSONObject().put("clientMessageId",item.clientId).put("status","unconfirmed")))
                queryChatSubmission(item.clientId)
            }
        } }
    }
    private fun scheduleComposerSave() { composerSaving=true;main.removeCallbacks(composerSave);main.postDelayed(composerSave,250) }
    fun persistOwnerComposer() {
        main.removeCallbacks(composerSave);val model=ownerComposer ?: return;val version=model.revision();composerSaving=true
        ownerComposerStore.save(model).whenComplete { _,error -> main.post {
            if(closed)return@post
            if(error!=null){state=state.copy(error="草稿仍在本页，尚未保存；没有自动发送："+composerFailure(error));return@post}
            if(ownerComposer?.revision()==version)composerSaving=false
        } }
    }
    fun editComposerText(key:String,viewEpoch:Int,text: String) {
        val model=ownerComposer ?: return;val draft=model.active()
        if(draft.key!=key||chatViewEpoch!=viewEpoch)return
        try{model.edit(draft.key,draft.revision,text,draft.mode,draft.attachments());publishComposer();scheduleComposerSave();voiceDraftTextChanged(text)}
        catch(error:Exception){state=state.copy(error=message(error))}
    }
    private fun editComposerSelection(mode: String,files: List<UiChatAttachment>) {
        val model=ownerComposer ?: run{state=state.copy(error="请等待原私聊草稿读取完成");return};val draft=model.active()
        try{model.edit(draft.key,draft.revision,draft.text,mode,composerAttachments(files));publishComposer();scheduleComposerSave()}
        catch(error:Exception){state=state.copy(error=message(error))}
    }
    /** Stage only a native read/selection receipt into the captured original draft, never a model JSON. */
    fun stageOwnerOriginalReference(payload:JSONObject):String {
        val model=ownerComposer ?: throw IllegalStateException("原私聊草稿尚未读取，未引用到其他输入")
        val draft=model.active();if(draft.conversationId!=state.conversationId.orEmpty()||state.chatSubmitting)throw IllegalStateException("原草稿正在切换/提交，请先核对原输入")
        val target=OriginalSourceStage(draft.key,draft.revision,chatViewEpoch,draft.conversationId)
        val request=requestOwnerDomain("taskMemory","selectOwnerOriginalReference",payload)
        if(!originalSourceStages.containsKey(request))originalSourceStages[request]=target
        return request
    }
    internal fun stageOwnerOriginalReferenceReceipt(payload: JSONObject, done: (JSONObject?, String?) -> Unit): String {
        check(webPrivateOwnerVisible()) { "请回到原私人消息页面" }
        check(webOwnerReceipts.size < 32) { "请先等待已有操作回执" }
        val owner = webWindowTicket()
        val original = stageOwnerOriginalReference(JSONObject(payload.toString()))
        awaitWebOwnerReceipt("taskMemory", "selectOwnerOriginalReference", original, owner, done)
        return original
    }
    fun removeComposerSource(index:Int,key:String,viewEpoch:Int){
        val model=ownerComposer?:return;val draft=model.active();if(draft.key!=key||viewEpoch!=chatViewEpoch)return
        try{val refs=draft.sourceRefs();if(index !in 0 until refs.length())return;val next=org.json.JSONArray();for(i in 0 until refs.length())if(i!=index)next.put(refs.getJSONObject(i));model.editSources(key,draft.revision,next);publishComposer();scheduleComposerSave()}
        catch(error:Exception){state=state.copy(error=message(error))}
    }
    fun saveComposerReading(key:String,index:Int,offset:Int,anchor:String,observed:String,unread:Boolean) {
        val model=ownerComposer ?: return
        if(model.draft(key)==null)return
        try{model.reading(key,index,offset,anchor,observed,unread);scheduleComposerSave()}
        catch(error:Exception){state=state.copy(error=message(error))}
    }
    fun returnToPendingComposer(client:String) {
        if(state.chatSubmitting)return
        val model=ownerComposer ?: return
        try{voiceInputModeChanged();model.selectPending(client);chatViewEpoch++;publishComposer(true);state=state.copy(messages=emptyList(),chatActiveTurn=null,foregroundTurn=null,chatBusy=false);refreshCurrentConversationTurn();voiceInputViewChanged();persistOwnerComposer();state.conversationId?.let{feature("assistant","get",JSONObject().put("conversationId",it))}}
        catch(error:Exception){state=state.copy(error=message(error))}
    }
    fun useRecoveredComposerVoice(key:String,revision:Long,text:String) {
        val model=ownerComposer ?: return;if(model.activeKey()!=key)return
        try{val id=model.active().voiceInputId;model.useVoice(key,revision,text);if(voiceInput.snapshot().optString("inputId")==id&&!voiceInput.active())voiceInput.discardDraft(id);publishComposer();persistOwnerComposer()}
        catch(error:Exception){state=state.copy(error=message(error))}
    }
    fun discardRecoveredComposerVoice(key:String,revision:Long) {
        val model=ownerComposer ?: return;if(model.activeKey()!=key)return
        try{val id=model.active().voiceInputId;model.discardVoice(key,revision);if(voiceInput.snapshot().optString("inputId")==id&&!voiceInput.active())voiceInput.discardDraft(id);publishComposer();persistOwnerComposer()}
        catch(error:Exception){state=state.copy(error=message(error))}
    }
    fun setChatSendMode(mode:String,key:String,viewEpoch:Int) { if(composerDraft?.key==key&&chatViewEpoch==viewEpoch&&mode in setOf("queue","steer")) editComposerSelection(mode,state.selectedChatAttachments) }
    fun openChatAttachmentPicker() {
        if(composerDraft==null){state=state.copy(error="请等待原私聊草稿读取完成");return}
        attachmentComposerKey=composerDraft?.key;attachmentComposerEpoch=chatViewEpoch
        val model=try { state.chatActiveTurn?.let { app.chatInputModel(it) } ?: app.settings.get().publicJson() } catch (_:Exception) { JSONObject() }
        state=state.copy(chatAttachmentPickerOpen=true,chatAttachmentModel=model)
        feature("attachments","list")
    }
    fun closeChatAttachmentPicker() { state=state.copy(chatAttachmentPickerOpen=false) }
    fun toggleChatAttachment(meta:JSONObject) {
        if(attachmentComposerKey!=composerDraft?.key||attachmentComposerEpoch!=chatViewEpoch){state=state.copy(error="附件选择的原草稿已变化，请回原输入重新选择");return}
        val selected=ChatInputRules.attachment(meta) ?: run { state=state.copy(error="附件元数据不可核对，请刷新附件库");return }
        if(state.selectedChatAttachments.any { it.id==selected.id }) editComposerSelection(state.chatSendMode,state.selectedChatAttachments.filterNot{it.id==selected.id})
        else if(state.selectedChatAttachments.size>=4) state=state.copy(error="每条消息最多选择4个附件")
        else editComposerSelection(state.chatSendMode,state.selectedChatAttachments+selected)
    }
    fun removeChatAttachment(id:String,key:String,viewEpoch:Int) { if(composerDraft?.key==key&&chatViewEpoch==viewEpoch)editComposerSelection(state.chatSendMode,state.selectedChatAttachments.filterNot{it.id==id}) }
    fun openConversationAttachment(messageId: String, reference: UiChatAttachment) {
        val conversation = state.conversationId
        openPrivateAttachment(reference) { state.conversationId == conversation && state.messages.any { it.id == messageId && it.attachments.any { file -> attachmentOpenKey(file) == attachmentOpenKey(reference) } } }
    }
    fun openSelectedChatAttachment(reference: UiChatAttachment) {
        val conversation = state.conversationId
        openPrivateAttachment(reference) { state.conversationId == conversation && state.selectedChatAttachments.any { attachmentOpenKey(it) == attachmentOpenKey(reference) } }
    }
    fun openTaskResultAttachment(taskId: String, reference: UiChatAttachment) {
        openPrivateAttachment(reference) { state.tasks.find { it.id == taskId }?.let(::taskResultAttachments)?.any { attachmentOpenKey(it) == attachmentOpenKey(reference) } == true }
    }
    private fun openPrivateAttachment(reference: UiChatAttachment, stillVisible: () -> Boolean) {
        val key = attachmentOpenKey(reference)
        if (closed || groupTaskPage != null || ownerDomain in setOf("groups", "teams") || !stillVisible() || state.attachmentOpens[key]?.checking == true) return
        val domain = ownerDomain
        state = state.copy(attachmentOpens = state.attachmentOpens + (key to UiAttachmentOpen(checking = true)))
        io.execute {
            try {
                val repository = dev.pocketengine.features.attachments.AttachmentRepository.get(app)
                val intent = synchronized(repository.book) {
                    val meta = repository.book.get(reference.id)
                    check(meta.optString("principalId") == "owner" && meta.optString("spaceId") == "private" && meta.optString("sha256") == reference.sha256 && meta.optString("mimeType") == reference.mimeType && meta.optLong("size", -1) == reference.size && reference.size in 0..dev.pocketengine.features.attachments.AttachmentBook.MAX_FILE_BYTES) { "原附件已变化或不可用，请保留原引用并重新核对" }
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val discard = object : java.io.OutputStream() { override fun write(value: Int) {} ; override fun write(bytes: ByteArray, offset: Int, length: Int) {} }
                    val count = repository.book.exportTo(reference.id, java.security.DigestOutputStream(discard, digest))
                    check(count == reference.size && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == reference.sha256) { "原附件字节摘要不匹配，未打开其他内容" }
                    repository.viewIntent(reference.id)
                }
                main.post {
                    if (closed) return@post
                    try {
                        check(ownerDomain == domain && groupTaskPage == null && stillVisible()) { "已离开原私人入口，请返回原位置重新打开" }
                        check(repository.book.get(reference.id).optString("sha256") == reference.sha256) { "原附件已不可用" }
                        activity.startActivity(intent)
                        state = state.copy(attachmentOpens = state.attachmentOpens + (key to UiAttachmentOpen(requested = true)))
                    } catch (failure: Exception) { state = state.copy(attachmentOpens = state.attachmentOpens + (key to UiAttachmentOpen(error = "附件未打开：" + message(failure)))) }
                }
            } catch (failure: Exception) { main.post { if (!closed) state = state.copy(attachmentOpens = state.attachmentOpens + (key to UiAttachmentOpen(error = "附件未打开：" + message(failure)))) } }
        }
    }
    private var queuedInputsBacking by mutableStateOf<QueuedOwnerInputsPage?>(null)
    var queuedInputsPage: QueuedOwnerInputsPage?
        get() = queuedInputsBacking
        private set(value) {
            fun identity(page: QueuedOwnerInputsPage?) = page?.editor?.let {
                Pair(Triple(it.message, it.queueRevision, it.messageRevision), it.withdrawn)
            }
            if (queuedInputsBacking?.window != value?.window || identity(queuedInputsBacking) != identity(value)) { webWindowEpoch++; phoneViewerWindowEpoch++ }
            queuedInputsBacking = value
        }
    private var queuedInputWindow = 0L
    private var queuedInputRead = 0L
    private fun queuedInputCurrent(page:QueuedOwnerInputsPage):Boolean =
        !closed && privateWorkspaceView() && page.window == queuedInputsPage?.window && page.viewEpoch == chatViewEpoch
    val queuedInputWindowCurrent:Boolean get()=queuedInputsPage?.let(::queuedInputCurrent)==true
    fun openQueuedOwnerInputs(task:String,conversation:String) {
        if(task.isBlank()||conversation.isBlank()||!privateWorkspaceView())return
        queuedInputsPage=QueuedOwnerInputsPage(++queuedInputWindow,task,conversation,chatViewEpoch)
        refreshQueuedOwnerInputs()
    }
    fun closeQueuedOwnerInputs() { queuedInputWindow++;queuedInputRead++;queuedInputsPage=null }
    fun refreshQueuedOwnerInputs() {
        val page=queuedInputsPage?:return;if(!queuedInputCurrent(page)||page.saving)return
        val request=++queuedInputRead;queuedInputsPage=page.readingList()
        io.execute {
            val result=runCatching { app.queuedChatInputs(page.task,page.conversation) }
            main.post {
                val actual=queuedInputsPage?:return@post
                if(!queuedInputCurrent(page)||request!=queuedInputRead)return@post
                result.fold({ native ->
                    if(native.optString("taskId")!=page.task||native.optString("conversationId")!=page.conversation){queuedInputsPage=actual.copy(reading=false,error="输入列表身份不一致，请重新打开");return@fold}
                    queuedInputsPage=actual.copy(reading=false,items=native.optJSONArray("items").objects(),taskStatus=native.optString("taskStatus"))
                },{ failure -> queuedInputsPage=actual.copy(reading=false,error="输入列表暂不可读取："+message(failure)) })
            }
        }
    }
    fun openQueuedOwnerInputEditor(messageId:String,withdrawn:Boolean) {
        val page=queuedInputsPage?:return;if(!queuedInputCurrent(page)||page.saving||page.editor!=null||page.readingMessage!=null)return
        if(page.items.none{it.optString("messageId")==messageId&&it.optBoolean("editable")})return
        val request=++queuedInputRead;queuedInputsPage=page.copy(reading=false,readingMessage=messageId,error=null,notice=null)
        io.execute {
            val result=runCatching { app.queuedChatInput(page.task,page.conversation,messageId) }
            main.post {
                val actual=queuedInputsPage?:return@post
                if(!queuedInputCurrent(page)||request!=queuedInputRead||actual.readingMessage!=messageId)return@post
                result.fold({ native ->
                    if(native.optString("taskId")!=page.task||native.optString("conversationId")!=page.conversation||native.optString("messageId")!=messageId||!native.optBoolean("editable")||native.optLong("queueRevision")<1||native.optLong("ownerMessageRevision")<1){queuedInputsPage=actual.copy(readingMessage=null,error="这条输入已被接手或已变化，请刷新后核对");return@fold}
                    val text=native.getString("content");val mode=native.getString("mode")
                    queuedInputsPage=actual.copy(readingMessage=null,items=actual.items.map{if(it.optString("messageId")==messageId)JSONObject(native.toString()) else it},editor=QueuedOwnerInputEditor(messageId,native.getLong("queueRevision"),native.getLong("ownerMessageRevision"),text,mode,text,mode,native.optInt("attachmentCount"),native.optInt("sourceReferenceCount"),withdrawn))
                },{ failure -> queuedInputsPage=actual.copy(readingMessage=null,error=message(failure)) })
            }
        }
    }
    fun closeQueuedOwnerInputEditor() { val page=queuedInputsPage?:return;if(!page.saving)queuedInputsPage=page.copy(editor=null,error=null) }
    fun editQueuedOwnerInputText(text:String) { val page=queuedInputsPage?:return;val edit=page.editor?:return;if(!page.saving&&!edit.withdrawn&&text.length<=12000)queuedInputsPage=page.copy(editor=edit.copy(text=text),error=null) }
    fun editQueuedOwnerInputMode(mode:String) { val page=queuedInputsPage?:return;val edit=page.editor?:return;if(!page.saving&&!edit.withdrawn&&mode in setOf("queue","steer"))queuedInputsPage=page.copy(editor=edit.copy(mode=mode),error=null) }
    fun saveQueuedOwnerInput() {
        val page=queuedInputsPage?:return;val edit=page.editor?:return
        if(!queuedInputCurrent(page)||page.saving)return
        val text=if(edit.withdrawn)edit.baseline else edit.text;val mode=if(edit.withdrawn)edit.originalMode else edit.mode
        if(text.length>12000||!edit.withdrawn&&text.isBlank()&&edit.attachmentCount==0)return
        queuedInputsPage=page.copy(saving=true,error=null,notice=null)
        io.execute {
            val result=runCatching { app.mutateQueuedChatInput(page.task,page.conversation,edit.message,edit.queueRevision,edit.messageRevision,text,mode,edit.withdrawn) }
            main.post {
                val actual=queuedInputsPage?:return@post
                if(!queuedInputCurrent(page)||actual.editor?.message!=edit.message||actual.editor?.queueRevision!=edit.queueRevision)return@post
                result.fold({ native ->
                    if(native.optString("taskId")!=page.task||native.optString("conversationId")!=page.conversation||native.optString("messageId")!=edit.message||native.optLong("queueRevision")<=edit.queueRevision){queuedInputsPage=actual.copy(saving=false,error="保存回执身份未能确认，请刷新核对；没有自动重试");return@fold}
                    queuedInputsPage=actual.copy(saving=false,editor=null,notice=if(edit.withdrawn)"这条输入已撤回" else "修改已保存，按所选时机加入任务")
                    refreshQueuedOwnerInputs()
                    if(state.conversationId==page.conversation)feature("assistant","get",JSONObject().put("conversationId",page.conversation))
                    val client=native.optString("clientMessageId");if(client.isNotBlank())queryChatSubmission(client)
                },{ failure -> queuedInputsPage=actual.copy(saving=false,error="修改尚未确认，请刷新核对；没有自动重试："+message(failure)) })
            }
        }
    }
    fun queryChatSubmission(client:String) { if(client.isNotBlank()) feature("assistant","getSubmission",JSONObject().put("clientMessageId",client)) }
    private fun receiveChatAcknowledgement(result:JSONObject,expectedClient:String?,query:Boolean=false) {
        val client=result.optString("clientMessageId");if(client.isBlank()||expectedClient!=null&&client!=expectedClient)return
        val original=ownerComposer?.pending(client)
        if(original!=null&&!ChatInputRules.receiptMatchesOriginal(result,original.conversationId,original.originalTarget())){
            val own=submittingChatClient==client;if(own)submittingChatClient=null
            state=state.copy(chatSubmitting=if(own)false else state.chatSubmitting,notice="原输入回执的任务或对话不一致；草稿与原身份仍保留，请查询原回执")
            return
        }
        val receipt=ChatInputRules.mergeReceipt(state.chatReceipts[client],result) ?: return
        val ownsPending=submittingChatClient==client;if(ownsPending)submittingChatClient=null
        val allReceipts=state.chatReceipts+(client to receipt)
        val retainedReceipts=allReceipts.filterValues{it.optString("status")=="unconfirmed"}+allReceipts.filterValues{it.optString("status")!="unconfirmed"}.entries.toList().takeLast(64).associate{it.toPair()}
        state=state.copy(chatReceipts=retainedReceipts,chatSubmitting=if(ownsPending)false else state.chatSubmitting)
        if(receipt.optString("status") in setOf("accepted","queued","consumed","withdrawn")) {
            locallyUnsent.remove(client)
            try {
                val model=ownerComposer;val oldKey=model?.activeKey();val ack=model?.acknowledge(client,receipt.getString("conversationId"))
                if(ack?.matched==true){publishComposer(oldKey==ack.previousKey);if(ack.migrated&&oldKey==ack.previousKey){chatViewEpoch++;voiceInputViewChanged()};scheduleComposerSave()}
            }catch(error:Exception){state=state.copy(notice=message(error))}
            refreshCurrentConversationTurn()
            if(state.conversationId==receipt.optString("conversationId"))feature("assistant","get",JSONObject().put("conversationId",state.conversationId))
        } else if(receipt.optString("status")=="rejected") {
            locallyUnsent.remove(client)
            try {
                if(ownerComposer?.rejectNotAdmitted(client,receipt.getString("conversationId"))==true){publishComposer();scheduleComposerSave()}
                refreshCurrentConversationTurn()
                state=state.copy(notice="原任务已关闭输入，本条确定未入队；草稿已保留，请在原任务结束后明确重新发送")
            }catch(error:Exception){state=state.copy(notice=message(error))}
        } else state=state.copy(notice="输入登记尚未确认；请查询原回执或核对任务，不要重复发送同一输入")
    }

    private var pluginCatalogPage: dev.pocketengine.features.NativePluginHost.Page? = null
    var taskMcpPreparation by mutableStateOf<JSONObject?>(null)
        private set
    fun skillExecutionHost(): dev.pocketengine.features.skills.NativeSkillExecutionHost = app.skillExecutionHost ?: throw IllegalStateException("技能执行宿主不可用")
    fun taskCapabilitySnapshot(taskId: String): JSONObject = app.taskCapabilitySnapshot(taskId)
    fun pluginExecutionHost(): dev.pocketengine.features.NativePluginHost = app.pluginHost ?: throw IllegalStateException("JS 扩展宿主不可用")
    fun readTaskSkillArtifact(taskId: String, operationId: String, artifactId: String, sha256: String): ByteArray = app.tasks.readSkillArtifact(taskId, operationId, artifactId, sha256)
    fun runEcosystemControlWork(work: Runnable) { if (closed) throw java.util.concurrent.RejectedExecutionException(); controls.execute(work) }
    fun readTeamParentPlanApproval(taskId: String, approvalId: String): JSONObject {
        check(!closed)
        return app.tasks.teamParentPlanForOwner(taskId, approvalId)
    }
    fun readTaskExecutionPlanApproval(taskId: String, approvalId: String): JSONObject {
        check(!closed)
        return app.readTaskExecutionPlanApproval(taskId, approvalId)
    }
    fun openTeamResearchBudgetPage(runId: String): dev.pocketengine.features.teams.TeamResearchHost.Page {
        check(!closed)
        return app.openTeamResearchBudgetPage(runId)
    }
    fun openTeamMemberMaterialsPage(runId: String, memberId: String): dev.pocketengine.features.teams.TeamMemberEvidenceHost.Page {
        check(!closed)
        return app.openTeamMemberMaterialsPage(runId, memberId)
    }
    fun openTeamProjectRoundContinuationPage(taskId: String): dev.pocketengine.features.teams.TeamProjectRoundHost.ContinuationPage {
        check(!closed)
        return app.openTeamProjectRoundContinuationPage(taskId)
    }
    fun openTeamProjectRoundPage(runId: String): dev.pocketengine.features.teams.TeamProjectRoundHost.Page {
        check(!closed)
        return app.openTeamProjectRoundPage(runId)
    }
    fun openTeamFailedProjectRoundPage(runId: String, itemId: String): dev.pocketengine.features.teams.TeamProjectRoundHost.Page {
        check(!closed)
        return app.openTeamFailedProjectRoundPage(runId, itemId)
    }
    fun openTeamWorkPlanPage(runId: String): dev.pocketengine.features.teams.TaskStoreTeamWriteHost.WorkPlanPage {
        check(!closed)
        return (app.features.module("teams") as? dev.pocketengine.features.teams.TeamsModule
            ?: throw IllegalStateException("原团队工作项宿主不可用")).newWorkPlanPage(runId)
    }
    fun openTeamDraftScopePage(runId: String): dev.pocketengine.features.teams.TaskStoreTeamWriteHost.DraftScopePage {
        check(!closed)
        return (app.features.module("teams") as? dev.pocketengine.features.teams.TeamsModule
            ?: throw IllegalStateException("原文件团队宿主不可用")).newDraftScopePage(runId)
    }
    fun openOwnerEditScopePage(taskId: String): dev.pocketengine.features.local.project.OwnerProjectEditScope.Page {
        check(!closed)
        return app.openOwnerEditScopePage(taskId)
    }
    fun openTeamRemoteRevisionPage(runId: String, assignmentId: String): dev.pocketengine.features.teams.TaskStoreTeamWriteHost.RemoteRevisionPage {
        check(!closed)
        return (app.features.module("teams") as? dev.pocketengine.features.teams.TeamsModule
            ?: throw IllegalStateException("原团队远端成果宿主不可用")).newRemoteRevisionPage(runId, assignmentId)
    }
    fun openTeamPhoneIntegratorPage(runId: String): dev.pocketengine.features.teams.TaskStoreTeamWriteHost.PhoneIntegratorPage {
        check(!closed)
        return (app.features.module("teams") as? dev.pocketengine.features.teams.TeamsModule
            ?: throw IllegalStateException("原团队手机整合者不可用")).newPhoneIntegratorPage(runId)
    }
    fun openTeamRemoteScopePage(runId: String, assignmentId: String): dev.pocketengine.features.teams.TaskStoreTeamWriteHost.RemoteScopePage {
        check(!closed)
        return (app.features.module("teams") as? dev.pocketengine.features.teams.TeamsModule
            ?: throw IllegalStateException("原团队远端宿主不可用")).newRemoteScopePage(runId, assignmentId)
    }
    fun openTaskMcpPreparation(taskId: String, pluginId: String, tool: String) { openConnection("mcp"); taskMcpPreparation = JSONObject().put("taskId",taskId).put("pluginId",pluginId).put("tool",tool) }
    var pluginRuntime by mutableStateOf(JSONObject())
        private set
    fun openPluginCatalogPage(): dev.pocketengine.features.NativePluginHost.Page {
        pluginCatalogPage?.close()
        return (app.pluginHost ?: throw IllegalStateException("插件目录宿主不可用")).newOwnerPage().also { pluginCatalogPage = it }
    }
    fun closePluginCatalogPage(page: dev.pocketengine.features.NativePluginHost.Page) { page.close(); if (pluginCatalogPage === page) pluginCatalogPage = null }
    fun runPluginCatalogWork(work: Runnable) { if (closed) throw java.util.concurrent.RejectedExecutionException(); io.execute(work) }
    /** Read-only native source navigation; callers use the existing bounded IO worker. */
    fun readFinalAnswerSources(request: JSONObject, control: dev.pocketengine.core.RunControl): JSONObject {
        if (closed) throw java.util.concurrent.RejectedExecutionException()
        control.check()
        return app.nativeOwnerWorkflows.answerSources(request, control)
    }
    fun preparePluginRuntime() {
        if (closed || pluginRuntime.optString("status") == "preparing") return
        pluginRuntime = JSONObject().put("status", "preparing").put("message", "正在核对已安装WebView和隔离能力。")
        try { io.execute {
            var failure: Exception? = null
            try { app.pluginHost.warm() } catch (error: Exception) { failure = error }
            val snapshot = try { app.pluginHost.runtimeSnapshot() } catch (_: Exception) { JSONObject().put("status", "unavailable").put("message", "本机JS沙箱未准备成功，没有改用其他解释器。") }
            main.post { if (!closed) { pluginRuntime = snapshot; if (failure != null) state = state.copy(notice = snapshot.optString("message")) } }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { pluginRuntime = JSONObject().put("status", "unavailable").put("message", "工作队列不可用，本次准备未开始。") }
    }

    private var fileRequestId: String? = null
    private var workspaceArtifactReadRequest: String? = null
    private var workspaceArtifactDiffRequest: String? = null
    private var workspaceArtifactsRequest: String? = null
    private var historyRequestId: String? = null
    private var revisionRequestId: String? = null
    @Volatile private var inspectionEpoch = 0
    internal var phoneInlinePreview by mutableStateOf<dev.pocketengine.phone.observation.PhoneObservationPreview.Identity?>(null)
        private set
    @Volatile private var phoneInlineWindow: android.view.View? = null
    private var inspectionRefreshPending = false
    private val teamStartInputs = mutableMapOf<String, String>()
    private var teamReceiptSequence = 0L
    private val observer = EngineApplication.Observer { event ->
        if (!closed) main.post { if (!closed) receive(JSONObject(event.toString())) }
    }
    private data class OwnerRequest(val id: String, val domain: String, val command: String, val payload: JSONObject, val epoch: Int, val call: dev.pocketengine.features.proxy.accounts.GatewayCall, val control: dev.pocketengine.core.RunControl = dev.pocketengine.core.RunControl())
    private val ownerRequests = mutableMapOf<String, OwnerRequest>()
    private val ownerEpochs = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var ownerDomain: String? = null
        set(value) { webWindowEpoch++; phoneViewerWindowEpoch++; field = value; taskAnnouncements.setForeground(voiceForeground && privateWorkspaceView()) }
    /** In-process owner choice only. Metadata in snapshots is never deserialized into this token. */
    private sealed class MemoryReviewScope {
        data object Private : MemoryReviewScope()
        data object Changing : MemoryReviewScope()
        data class Unavailable(val reason: String) : MemoryReviewScope()
        data class Project(val native: dev.pocketengine.features.memory.ProjectMemoryNamespaces, val selected: dev.pocketengine.features.memory.ProjectMemoryNamespaces.Selection, val label: String) : MemoryReviewScope()
    }
    private var memoryReviewScope: MemoryReviewScope = MemoryReviewScope.Private

    private var groupOwnerWindow: String? = null
    private var groupTaskWindow: String? = null
    private var groupTaskPage: String? = null
        set(value) { webWindowEpoch++; phoneViewerWindowEpoch++; field = value; taskAnnouncements.setForeground(voiceForeground && privateWorkspaceView()) }
    private var groupTaskId: String? = null
    var groupTaskReady by mutableStateOf(false)
        private set
    fun enterGroupTask(taskId: String): String {
        closeNativeEvaluation()
        projectGit.deactivate()
        projectFileImports.deactivate()
        projectPackages.leavePrivate()
        closeWorkspaceArtifact(); state = state.copy(workspaceArtifactEditorOrigin = null, projectTaskReturn = null)
        groupTaskWindow?.let { app.groupOwnerWorkflows?.close(it) }
        groupTaskId = taskId; groupTaskPage = UUID.randomUUID().toString(); groupTaskWindow = null
        refreshGroupTaskWindow(); return groupTaskPage!!
    }
    fun refreshGroupTaskWindow() {
        if (groupTaskPage == null || closed) return
        try { groupTaskWindow = (app.groupOwnerWorkflows ?: throw IllegalStateException("原生群审核宿主尚未就绪")).open(activity); groupTaskReady = true }
        catch (error: Exception) { groupTaskReady = false; state = state.copy(error = message(error)) }
    }
    fun leaveGroupTask(page: String) {
        if (groupTaskPage != page) return
        groupTaskWindow?.let { app.groupOwnerWorkflows?.close(it) }; groupTaskWindow = null; groupTaskPage = null; groupTaskId = null; groupTaskReady = false
    }
    private fun groupForeground(visible: Boolean) {
        if (!visible) {
            groupTaskWindow?.let { app.groupOwnerWorkflows?.close(it) }; groupOwnerWindow?.let { app.groupOwnerWorkflows?.close(it) }
            groupTaskWindow = null; groupOwnerWindow = null; groupTaskReady = false
            ownerRequests.entries.filter { it.value.domain == "groups" }.toList().forEach { (key, request) ->
                request.control.cancel(); request.call.cancel(); ownerRequests.remove(key)
                val receipt = JSONObject().put("requestId", request.id).put("ok", false).put("message", "页面已离开前台；请重读原状态，未自动重试")
                state = state.copy(ownerPending = state.ownerPending - key, ownerResponses = state.ownerResponses + ("groups" to (state.ownerResponses("groups") + (request.command to receipt))), groupOwnerReceipts = retainedGroupReceipts(request.id, receipt))
            }
        } else if (groupTaskPage != null) refreshGroupTaskWindow()
        else if (ownerDomain == "groups") {
            try { groupOwnerWindow = app.groupOwnerWorkflows.open(activity); requestOwnerDomain("groups", "snapshot") }
            catch (error: Exception) { state = state.copy(ownerErrors = state.ownerErrors + ("groups" to message(error))) }
        }
    }
    private var remoteOwnerWindow: String? = null
    private var remoteOwnerTask: String? = null
    private var channelMediaWindow: dev.pocketengine.features.channels.ChannelsModule.OwnerMediaWindow? = null
    @Volatile private var channelMediaReview: dev.pocketengine.features.channels.ChannelsModule.OwnerMediaReview? = null
    internal val webSkills by lazy { dev.pocketengine.ui.web.PocketWebSkills(this, app.features.module("skills") as? dev.pocketengine.features.skills.SkillsModule ?: error("原技能模块不可用"), app.tasks, activity) }
    internal val webChannels by lazy { dev.pocketengine.ui.web.PocketWebChannels(this, nativeChannelModule(), activity) }
    private fun nativeChannelModule() = app.features.module("channels") as? dev.pocketengine.features.channels.ChannelsModule ?: throw IllegalStateException("原渠道模块不可用")
    private var remoteOwnerReceiptSequence = 0L
    private var configPick: OwnerRequest? = null
    private var authorizationOpening: String? = null
    private val accountPoll = object : Runnable {
        override fun run() {
            if (!closed && ownerDomain == "accounts") {
                val login = state.ownerSnapshot("accounts").optJSONObject("login")
                if (login?.optString("status") in setOf("pending", "exchanging")) requestOwnerDomain("accounts", "snapshot")
                main.postDelayed(this, 1500)
            }
        }
    }
    private fun retainedGroupReceipts(id: String, receipt: JSONObject): Map<String, JSONObject> = (state.groupOwnerReceipts + (id to receipt)).entries.toList().takeLast(64).associate { it.toPair() }
    fun nativeDeviceWorkspace(): dev.pocketengine.features.devices.NativeDeviceWorkspaceHost? = app.deviceWorkspaceHost
    fun nativeDeviceTaskScope(): dev.pocketengine.features.devices.NativeDeviceTaskScopeHost? = app.deviceTaskScopeHost
    // Existing I/O queue for pairing/metadata waits. Urgent original stop never waits behind it.
    fun runDeviceWorkspaceWork(work: Runnable) { check(!closed); io.execute(work) }
    fun runDeviceWorkspaceControlWork(work: Runnable) { check(!closed); controls.execute(work) }
    fun deviceTaskWorkspaceChanged(id: String) { if (!closed) io.execute { app.pushTask(id) }; deviceWorkspaceChanged() }
    fun deviceWorkspaceChanged() { if (!closed) app.publish(JSONObject().put("type", "device_workspace_changed")) }
    fun nativeWorkProfiles(): dev.pocketengine.features.controlplane.NativeWorkProfileHost = app.nativeWorkProfiles()
    fun runWorkProfileWork(work: Runnable) { check(!closed); io.execute(work) }
    fun runWorkProfileControl(work: Runnable) { check(!closed); controls.execute(work) }
    fun startOwnerWorkProfile(goal: String, selected: dev.pocketengine.features.controlplane.NativeWorkProfileHost.Selection, control: dev.pocketengine.core.RunControl, complete: (Result<JSONObject>) -> Unit) {
        check(!closed)
        app.runOwnerWorkProfileAsync(goal, "", null, null, selected, control) { value, failure ->
            complete(if (failure != null) Result.failure(failure) else if (value != null) Result.success(value) else Result.failure(IllegalStateException("原工作配置任务回执缺失")))
        }
    }
    fun reviewWorkProfileProject(): dev.pocketengine.features.local.project.ProjectOwnerWorkflows.ProfileProjectSelection {
        check(!closed && privateWorkspaceView()) { "请先在原私人项目工作台选择项目副本" }
        val project = state.projectId ?: throw IllegalStateException("请先打开原项目工作台，再回到工作配置选择这份副本")
        val checkout = projectWorkbench.checkout(project) ?: throw IllegalStateException("请选择原项目的真实副本")
        return app.reviewOwnerWorkProfileProject(project, checkout, projectWorkbench.agent(checkout).runtimeId.value)
    }
    fun startOwnerWorkProfileProject(goal: String, selected: dev.pocketengine.features.controlplane.NativeWorkProfileHost.Selection,
        control: dev.pocketengine.core.RunControl, project: dev.pocketengine.features.local.project.ProjectOwnerWorkflows.ProfileProjectSelection,
        complete: (Result<JSONObject>) -> Unit) {
        check(!closed)
        app.runOwnerWorkProfileAsync(goal, "", null, null, selected, control, project) { value, failure ->
            complete(if (failure != null) Result.failure(failure) else if (value != null) Result.success(value) else Result.failure(IllegalStateException("原项目工作配置任务回执缺失")))
        }
    }
    fun reviewOriginalTaskWorkProfile(page: dev.pocketengine.features.controlplane.NativeWorkProfileHost.Page, id: String, run: String, epoch: Int, control: dev.pocketengine.core.RunControl): dev.pocketengine.features.controlplane.NativeWorkProfileHost.Review = page.reviewForTask(app.tasks, id, run, epoch, control)
    fun reviewOriginalTaskWorkProfileProject(page: dev.pocketengine.features.controlplane.NativeWorkProfileHost.Page, id: String, run: String, epoch: Int, control: dev.pocketengine.core.RunControl, project: dev.pocketengine.features.local.project.ProjectOwnerWorkflows.ProfileProjectSelection): dev.pocketengine.features.controlplane.NativeWorkProfileHost.Review = page.reviewForTask(app.tasks, id, run, epoch, control, project)
    fun originalTaskWorkProfileSnapshot(id: String, run: String, epoch: Int): JSONObject = if (run.isEmpty()) app.tasks.pendingWorkProfileTaskSnapshot(id) else app.tasks.workProfileRunSnapshot(id, run, epoch)
    fun continueOwnerWorkProfile(id: String, selected: dev.pocketengine.features.controlplane.NativeWorkProfileHost.Selection, control: dev.pocketengine.core.RunControl, complete: (Result<JSONObject>) -> Unit) {
        check(!closed)
        app.continueOwnerWorkProfileAsync(id, selected, control) { value, failure ->
            complete(if (failure != null) Result.failure(failure) else if (value != null) Result.success(value) else Result.failure(IllegalStateException("原工作配置任务回执缺失")))
        }
    }
    fun continueOwnerWorkProfileProject(id: String, selected: dev.pocketengine.features.controlplane.NativeWorkProfileHost.Selection,
        control: dev.pocketengine.core.RunControl, project: dev.pocketengine.features.local.project.ProjectOwnerWorkflows.ProfileProjectSelection,
        complete: (Result<JSONObject>) -> Unit) {
        check(!closed)
        app.continueOwnerWorkProfileAsync(id, selected, control, project) { value, failure ->
            complete(if (failure != null) Result.failure(failure) else if (value != null) Result.success(value) else Result.failure(IllegalStateException("原项目工作配置继续回执缺失")))
        }
    }
    internal fun nativeTokenBudgetHost(): dev.pocketengine.EngineApplication = app
    fun nativeModelConnections(): dev.pocketengine.runtime.models.ModelConnectionService? = app.modelConnections
    internal fun nativeAccountWorkflows(): dev.pocketengine.features.NativeOwnerWorkflows = app.nativeOwnerWorkflows
    internal fun nativeModelSelection(): dev.pocketengine.runtime.models.ModelSelectionCoordinator = app.settings.modelSelection()
    fun runModelConnectionWork(work: Runnable) { check(!closed); io.execute(work) }
    fun modelConnectionChanged() { if (!closed) try { io.execute { if (!modelSelectionPending()) app.sendState() } } catch (_: java.util.concurrent.RejectedExecutionException) { } }
    internal fun modelSelectionPending(): Boolean = app.settings.modelSelection().status().pending()
    private fun reserveModelMutation(expectedRevision: Long? = null): dev.pocketengine.runtime.models.ModelSelectionCoordinator.Mutation {
        check(!closed) { "原设置窗口已关闭" }
        val coordinator = app.settings.modelSelection()
        return (if (expectedRevision == null) coordinator.beginMutation() else coordinator.beginMutation(expectedRevision)).also { modelMutations.add(it) }
    }
    internal fun runModelSelectionWrite(work: () -> JSONObject, completed: (Result<JSONObject>) -> Unit) = runModelSelectionWrite(null, work, completed)
    internal fun runModelSelectionWrite(expectedRevision: Long?, work: () -> JSONObject, completed: (Result<JSONObject>) -> Unit) {
        val mutation = reserveModelMutation(expectedRevision)
        try {
            io.execute {
                val result = runCatching { mutation.run { check(!closed) { "原设置窗口已关闭，未执行排队中的保存" }; work() } }
                modelMutations.remove(mutation)
                main.post { completed(result) }
            }
        } catch (failure: Exception) { mutation.close(); modelMutations.remove(mutation); throw failure }
    }
    private fun unavailableMemoryScope(reason: String) = JSONObject().put("available", false).put("namespaceStatus", "unavailable").put("message", reason)
    private fun memoryReviewSnapshot(scope: MemoryReviewScope): JSONObject = when (scope) {
        MemoryReviewScope.Private -> app.memoryOwnerSnapshot().put("namespaceStatus", "private").put("scopeName", "主人全局 · 私人")
        is MemoryReviewScope.Project -> scope.native.handleForOwner(scope.selected, "snapshot", JSONObject()).put("namespaceStatus", "project").put("scopeName", scope.label)
        MemoryReviewScope.Changing -> throw SecurityException("正在核对原生记忆范围，尚未授权读取；没有回退全局空间")
        is MemoryReviewScope.Unavailable -> throw SecurityException(scope.reason)
    }
    private fun memoryReviewCommand(scope: MemoryReviewScope, command: String, payload: JSONObject, original: dev.pocketengine.core.RunControl): JSONObject {
        if (command == "namespaceChoices") return JSONObject().put("choices", app.nativeOwnerWorkflows.projectMemoryForOwner().choicesForOwner())
        if (command == "snapshot") return memoryReviewSnapshot(scope)
        return when (scope) {
            MemoryReviewScope.Private -> when (command) {
                "openSource" -> app.nativeOwnerWorkflows.source(payload)
                "proposeConsolidation" -> app.proposeMemoryConsolidation(payload.getJSONArray("selections"), original)
                "projectFileChoices", "proposeProjectFile" -> throw SecurityException("请先明确选择原生项目记忆范围")
                else -> app.nativeOwnerWorkflows.memory(command, payload)
            }
            is MemoryReviewScope.Project -> when (command) {
                "openSource" -> scope.native.readFileSourceForOwner(scope.selected, dev.pocketengine.features.memory.SourceRef.fromJson(payload))
                "projectFileChoices" -> JSONObject().put("files", scope.native.fileChoicesForOwner(scope.selected))
                "proposeProjectFile" -> {
                    require(payload.keys().asSequence().toSet() == setOf("path", "version", "revisionId", "sha256", "offset", "chars")) { "项目原文只接受所选文件版本和范围；namespace不是JSON权限" }
                    original.check()
                    scope.native.proposeFileForOwner(scope.selected, payload.getString("path"), payload.getInt("version"), payload.getString("revisionId"), payload.getString("sha256"), payload.getInt("offset"), payload.getInt("chars"))
                }
                else -> scope.native.handleForOwner(scope.selected, command, payload)
            }
            MemoryReviewScope.Changing -> throw SecurityException("原生记忆范围尚在核对，未读取或写入任何空间")
            is MemoryReviewScope.Unavailable -> throw SecurityException(scope.reason)
        }
    }
    /** Existing owner backup sheet consumes this exact live management selection, not JSON. */
    fun openProjectMemoryArchive() {
        check(!closed && ownerDomain == "memoryReview" && privateWorkspaceView()) { "请在本机私人记忆审核页显选项目" }
        val scope = memoryReviewScope as? MemoryReviewScope.Project ?: throw SecurityException("当前没有原生项目记忆选择；未回退主人全局")
        ownerArchives.openProjectMemory(scope.selected, scope.label)
    }
    /** Explicit native owner selection. Old requests/rows are retired before changing the token. */
    @JvmOverloads
    fun selectMemoryNamespace(choice: JSONObject?, done: ((JSONObject?, String?) -> Unit)? = null) {
        check(!closed && ownerDomain == "memoryReview") { "请先打开原生记忆审核页" }
        check(memoryReviewScope != MemoryReviewScope.Changing) { "正在核对原生范围，请等待真实回执" }
        val selected = choice?.let { JSONObject(it.toString()) }
        if (selected != null) require(selected.opt("available") == true) { "此项目尚未支持主人记忆授权" }
        leaveOwnerDomain("memoryReview"); ownerDomain = "memoryReview"
        memoryReviewScope = MemoryReviewScope.Changing
        val epoch = ownerEpochs["memoryReview"] ?: 0
        val pending = "memoryReview.selectNamespace"
        state = state.copy(ownerSnapshots = state.ownerSnapshots + ("memoryReview" to JSONObject().put("available", false).put("namespaceStatus", "changing")), ownerPending = state.ownerPending + pending)
        try { io.execute {
            var target: MemoryReviewScope = MemoryReviewScope.Private
            var snapshot: JSONObject? = null
            var failure: Exception? = null
            try {
                check(!closed && epoch == (ownerEpochs["memoryReview"] ?: 0)) { "原记忆页面已关闭" }
                if (selected != null) {
                    val native = app.nativeOwnerWorkflows.projectMemoryForOwner()
                    val token = native.selectForOwner(selected.getString("checkoutId"), selected.getString("projectId"), selected.getString("revision"))
                    target = MemoryReviewScope.Project(native, token, selected.optString("name", "项目"))
                }
                snapshot = memoryReviewSnapshot(target)
            } catch (error: Exception) { failure = error }
            val completed = target; val actual = snapshot; val error = failure
            main.post {
                if (closed || ownerDomain != "memoryReview" || epoch != (ownerEpochs["memoryReview"] ?: 0)) {
                    if (completed is MemoryReviewScope.Project) completed.native.revokeForOwner(completed.selected)
                    done?.invoke(null, "原记忆范围窗口已关闭"); return@post
                }
                if (error != null && completed is MemoryReviewScope.Project) completed.native.revokeForOwner(completed.selected)
                memoryReviewScope = if (error == null) completed else MemoryReviewScope.Unavailable(message(error))
                done?.invoke(if (error == null) actual else null, error?.let(::message))
                state = state.copy(ownerPending = state.ownerPending - pending,
                    ownerSnapshots = state.ownerSnapshots + ("memoryReview" to (actual ?: unavailableMemoryScope(message(error ?: IllegalStateException("范围回执缺失"))))),
                    ownerErrors = if (error == null) state.ownerErrors - "memoryReview" else state.ownerErrors + ("memoryReview" to message(error)))
            }
        } } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            memoryReviewScope = MemoryReviewScope.Unavailable("页面已关闭，请重新选择记忆范围")
            done?.invoke(null, "页面已关闭，请重新选择记忆范围")
            state = state.copy(ownerPending = state.ownerPending - pending, ownerSnapshots = state.ownerSnapshots + ("memoryReview" to unavailableMemoryScope("页面已关闭，请重新选择记忆范围")))
        }
    }
    /** Conservative presentation switch gate; voice authority must remain reachable until released. */
    internal fun voicePresentationBusy(): Boolean = try {
        val snapshot = voiceInput.snapshot()
        nativeVoiceBusy(voiceInput.active(), voiceInput.leaseHeld(), realtimeOwnsAudio(),
            snapshot.optBoolean("reviewAvailable"), snapshot.optBoolean("staged"),
            snapshot.optBoolean("resourceReleaseUnknown"), composerDraft?.voiceText?.isNotEmpty() == true)
    } catch (_: Exception) { true }
    /** Close presentation-scoped owner windows without stopping or retrying engine tasks. */
    internal fun closeNativeWorkspacePresentation() {
        groupTaskPage?.let(::leaveGroupTask)
        ownerDomain?.let(::leaveOwnerDomain)
        closeNativeEvaluation()
        closeNativeInferenceHistory()
        closeQueuedOwnerInputs()
        persistOwnerComposer()
    }
    fun enterOwnerDomain(domain: String) {
        if (domain !in setOf("memoryReview", "taskMemory", "mcp", "accounts", "learning", "groups", "channelMedia")) return
        if (domain == "groups") { closeNativeEvaluation();projectGit.deactivate();projectFileImports.deactivate();projectPackages.leavePrivate(); closeWorkspaceArtifact(); state = state.copy(workspaceArtifactEditorOrigin = null, projectTaskReturn = null) }
        ownerDomain = domain
        if (domain == "channelMedia") { channelMediaWindow?.close(); channelMediaReview = null; channelMediaWindow = nativeChannelModule().openOwnerMediaWindow(activity) }
        if (domain == "groups") try { groupOwnerWindow = app.groupOwnerWorkflows.open(activity) } catch (error: Exception) { state = state.copy(ownerErrors = state.ownerErrors + (domain to message(error))) }
        if (domain != "taskMemory") requestOwnerDomain(domain, "snapshot")
        if (domain == "accounts") { main.removeCallbacks(accountPoll); main.postDelayed(accountPoll, 1500) }
    }
    fun leaveOwnerDomain(domain: String) {
        if (domain == "memoryReview") { ownerArchives.leaveMemoryReview(); (memoryReviewScope as? MemoryReviewScope.Project)?.let { it.native.revokeForOwner(it.selected) }; memoryReviewScope = MemoryReviewScope.Private }
        if (domain == "channelMedia") { channelMediaWindow?.close(); channelMediaWindow = null; channelMediaReview = null }
        if (domain == "groups") { groupOwnerWindow?.let { app.groupOwnerWorkflows?.close(it) }; groupOwnerWindow = null }
        if (domain == "remoteTask") { remoteOwnerWindow?.let { app.remoteOwnerWorkflows?.close(it) }; remoteOwnerWindow = null; remoteOwnerTask = null }
        ownerEpochs[domain] = (ownerEpochs[domain] ?: 0) + 1
        if (ownerDomain == domain) ownerDomain = null
        if (domain == "accounts") { main.removeCallbacks(accountPoll); authorizationOpening = null }
        val abandoned = ownerRequests.values.filter { it.domain == domain }
        abandoned.forEach { request ->
            cancelOwnerControl(request); request.call.cancel(); originalSourceStages.remove(request.id); ownerRequests.remove("$domain.${request.command}"); completeWebOwnerReceipt(request.id, null, "原页面已关闭，请核对原回执")
            if (domain == "mcp" && request.command in setOf("discoverMcp", "discoverMcpAuthorized")) cleanOwnerResult("mcp", "cancel", JSONObject().put("operationId", request.payload.optString("_operationId")))
        }
        if (domain == "mcp") listOf("discoverMcp", "discoverMcpAuthorized").forEach { discovery -> state.ownerResponses(domain)[discovery]?.optJSONObject("result")?.optString("previewId")?.takeIf { it.isNotBlank() }?.let { cleanOwnerResult(domain, "discardMcpPreview", JSONObject().put("previewId", it)) } }
        if (domain == "mcp") state.ownerResponses(domain)["prepareLocalMcpDiscovery"]?.optJSONObject("result")?.optString("reviewId")?.takeIf { it.isNotBlank() }?.let { cleanOwnerResult(domain, "discardLocalMcpReview", JSONObject().put("reviewId", it)) }
        if (domain == "mcp") state.ownerResponses(domain)["prepareLocalMcpInstall"]?.optJSONObject("result")?.optString("reviewId")?.takeIf { it.isNotBlank() }?.let { cleanOwnerResult(domain, "discardLocalMcpInstallReview", JSONObject().put("reviewId", it)) }
        if (domain == "mcp") state.ownerResponses(domain)["getMcpResource"]?.optJSONObject("result")?.optString("snapshotId")?.takeIf { it.isNotBlank() }?.let { cleanOwnerResult(domain, "discardMcpResourceSnapshot", JSONObject().put("snapshotId", it)) }
        if (configPick?.domain == domain) { configPick?.call?.close(); configPick = null }
        state = state.copy(ownerSnapshots = if (domain == "memoryReview") state.ownerSnapshots - domain else state.ownerSnapshots, ownerPending = state.ownerPending.filterNot { it.startsWith("$domain.") }.toSet(), ownerResponses = state.ownerResponses - domain, ownerErrors = state.ownerErrors - domain, groupOwnerReceipts = if (domain == "groups") emptyMap() else state.groupOwnerReceipts, ownerSource = null)
    }
    private fun cleanOwnerResult(domain: String, command: String, payload: JSONObject) {
        try { app.features.nativeOwnerCleanup { try {
            if (domain == "mcp") app.nativeOwnerWorkflows.mcp(command, payload)
            else dev.pocketengine.features.proxy.accounts.GatewayCall(5000).use { app.nativeOwnerWorkflows.account(command, payload, it) }
        } catch (_: Exception) { /* An expired/already consumed preview or login needs no retry. */ } } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
    /** Groups retain their own original Activity window; this does not admit private owner actions. */
    internal fun webGroupOwnerTicket(): String? {
        if (closed || !webUiCanInteract() || ownerDomain != "groups" || groupTaskPage != null) return null
        val window = groupOwnerWindow ?: return null
        return runCatching {
            app.groupOwnerWorkflows.checkWindow(window)
            "$window:${ownerEpochs["groups"] ?: 0}:${webWindowTicket()}"
        }.getOrNull()
    }
    internal fun requestGroupOwnerReceipt(command: String, payload: JSONObject, done: (JSONObject?, String?) -> Unit): String {
        val window = webGroupOwnerTicket() ?: error("请回到前台原群管理窗口")
        check(webOwnerReceipts.size < 32) { "请先等待已有原群操作回执" }
        val original = requestOwnerDomain("groups", command, JSONObject(payload.toString()))
        val callback: (JSONObject?, String?) -> Unit = { result, error ->
            if (webGroupOwnerTicket() != window) done(null, "原群窗口已关闭或更换，请在原记录核对，不要重复提交")
            else done(result, error)
        }
        val finished = state.ownerResponses("groups")[command]?.takeIf { it.opt("requestId") == original && it.opt("ok") is Boolean }
        if (finished != null) callback(if (finished.getBoolean("ok")) finished.optJSONObject("result") ?: JSONObject() else null,
            if (finished.getBoolean("ok")) null else finished.optString("error", finished.optString("message", "原群操作未确认")))
        else webOwnerReceipts.getOrPut(original) { mutableListOf() }.add(callback)
        return original
    }
    /** The existing owner request remains the authority; this callback returns its final envelope. */
    internal fun requestOwnerDomainReceipt(domain: String, command: String, payload: JSONObject, done: (JSONObject?, String?) -> Unit): String {
        check(webPrivateOwnerVisible()) { "请回到原私人管理页面" }
        check(webOwnerReceipts.size < 32) { "请先等待已有操作回执" }
        val owner = webWindowTicket()
        val original = requestOwnerDomain(domain, command, JSONObject(payload.toString()))
        awaitWebOwnerReceipt(domain, command, original, owner, done)
        return original
    }
    private fun awaitWebOwnerReceipt(domain: String, command: String, original: String, owner: Any, done: (JSONObject?, String?) -> Unit) {
        val callback: (JSONObject?, String?) -> Unit = { result, error ->
            if (!webPrivateOwnerVisible() || webWindowTicket() != owner) done(null, "原页面已变化，请核对原回执，不要重复提交") else done(result, error)
        }
        val finished = state.ownerResponses(domain)[command]?.takeIf { it.optString("requestId") == original }
        if (finished != null) callback(if (finished.optBoolean("ok")) finished.optJSONObject("result") ?: JSONObject() else null, if (finished.optBoolean("ok")) null else finished.optString("error", finished.optString("message", "原操作未确认")))
        else webOwnerReceipts.getOrPut(original) { mutableListOf() }.add(callback)
    }
    fun requestOwnerDomain(domain: String, command: String, payload: JSONObject = JSONObject()): String {
        if (domain == "mcp" && command == "discardMcpResourceSnapshot") {
            cleanOwnerResult(domain, command, payload)
            return "native-resource-window-closed"
        }
        if (domain == "mcp" && command == "cancel") {
            val operation = payload.optString("operationId")
            ownerRequests.values.filter { it.domain == "mcp" && it.payload.optString("_operationId").isNotBlank() && it.payload.optString("_operationId") == operation }.forEach { cancelOwnerControl(it); it.call.cancel() }
        }
        if (domain == "groups" && command == "remoteInputs.leave") {
            groupOwnerWindow?.let { app.groupOwnerWorkflows.leaveRemoteInputs(it, payload.optString("pageId")) }
            return "native-remote-input-page-closed"
        }
        if (domain == "groups" && command == "completion.leave") {
            groupOwnerWindow?.let { app.groupOwnerWorkflows.leaveCompletions(it) }
            return "native-completion-page-revoked"
        }
        if (domain == "groups" && command == "material.leave") {
            groupOwnerWindow?.let { app.groupOwnerWorkflows.leaveMaterials(it) }
            return "native-material-page-revoked"
        }
        val capturedMemoryScope = memoryReviewScope
        if (domain == "memoryReview" && command != "namespaceChoices" && (capturedMemoryScope is MemoryReviewScope.Changing || capturedMemoryScope is MemoryReviewScope.Unavailable)) throw SecurityException("当前记忆范围尚未确认，请明确重新选择；没有回退全局空间")
        val key = "$domain.$command"
        if (domain == "groups" && groupOwnerWindow == null) try { groupOwnerWindow = app.groupOwnerWorkflows.open(activity) } catch (_: Exception) { /* Worker returns a real denied-window receipt. */ }
        if (domain == "learning" && command == "cancelLearning") return cancelLearning(payload.optString("requestId"))
        if (domain == "memoryReview" && command == "cancelConsolidation") return cancelConsolidation(payload.optString("requestId"))
        if (domain == "memoryReview" && command == "proposeConsolidation" && payload.keys().asSequence().toSet() != setOf("selections")) throw SecurityException("记忆归纳只接受所选记录ID与版本，身份和模型由本机提供")
        if (domain == "learning" && command == "proposeLearning" && payload.keys().asSequence().toSet() != setOf("sourceRef")) throw SecurityException("来源提炼只接受已核对的来源版本，任务、模型和身份由本机提供")
        if (domain == "accounts" && (command in setOf("beginLogin", "cancelLogin", "logout") || command in setOf("setEnabled", "setAppLearningPolicy") && !payload.optBoolean("enabled"))) authorizationOpening = null
        ownerRequests[key]?.let { previous ->
            if (previous.payload.toString() == payload.toString()) return previous.id
            if (command !in setOf("snapshot", "list", "search", "get", "listCandidates", "getCandidate", "candidateCapacity", "listCandidateHistory", "namespaceChoices", "projectFileChoices", "cognitionProjection", "searchHistory", "readHistoryRecord", "readAssertionEvidence", "models", "openSource", "prepareLearningSource", "discoverMcp", "discoverMcpAuthorized", "searchTaskMemoryTasks", "readTaskMemoryTrace", "readTaskMemoryTraceOperation", "readTaskNativeOutputAudit", "readLearnedMethodApplications", "listSavedMemorySources", "selectSavedMemorySource", "getConversationHistoryReadScope", "readTaskMemoryReceipt", "readTaskMemorySource", "listTaskMemoryReplacements", "getLearningJob", "listLearningJobs", "searchOwnerOriginals", "readOwnerOriginalSearchHit", "selectOwnerOriginalReference")) throw IllegalStateException("这项操作仍在处理中，请等待真实回执")
            previous.control.cancel(); previous.call.cancel(); originalSourceStages.remove(previous.id); completeWebOwnerReceipt(previous.id, null, "原读取已被更新的读取替代")
            if (domain == "mcp" && command in setOf("discoverMcp", "discoverMcpAuthorized")) cleanOwnerResult(domain, "cancel", JSONObject().put("operationId", previous.payload.optString("_operationId")))
        }
        val capturedGroupWindow = if (domain == "groups") groupOwnerWindow else null
        val capturedChannelWindow = if (domain == "channelMedia") channelMediaWindow else null
        val capturedChannelReview = if (domain == "channelMedia" && command == "freezeMedia") channelMediaReview else null
        val request = OwnerRequest(UUID.randomUUID().toString(), domain, command, JSONObject(payload.toString()), ownerEpochs[domain] ?: 0, dev.pocketengine.features.proxy.accounts.GatewayCall(60000))
        val modelMutation = if (domain == "accounts" && command == "selectModel") reserveModelMutation() else null
        ownerRequests[key] = request
        state = state.copy(ownerPending = state.ownerPending + key, ownerErrors = state.ownerErrors - domain)
        val worker = if (command in setOf("cancel", "cancelLogin", "logout", "stopLearningJob", "cancelLocalMcpDiscovery", "cancelMcpTask", "discardLocalMcpReview", "discardLocalMcpInstallReview") || command == "setEnabled" && !payload.optBoolean("enabled")) controls else io
        try { worker.execute {
            var result: JSONObject? = null
            var snapshot: JSONObject? = null
            var failure: Exception? = null
            try {
                request.call.check()
                result = when (domain) {
                    "taskMemory" -> app.nativeOwnerWorkflows.taskMemory(command, request.payload, request.control)
                    "memoryReview" -> memoryReviewCommand(capturedMemoryScope, command, request.payload, request.control)
                    "mcp" -> app.nativeOwnerWorkflows.mcp(command, request.payload, request.control)
                    "learning" -> when (command) {
                        "getAppLearningPolicy", "setAppLearningPolicy" -> app.appLearningCommand(command, request.payload, request.control)
                        "proposeLearning" -> app.proposeSourceMemoryLearning(dev.pocketengine.features.memory.SourceRef.fromJson(request.payload.getJSONObject("sourceRef")), request.control)
                        "createLearningJob", "addLearningJobSource", "getLearningJob", "listLearningJobs", "runLearningJob", "skipLearningJobBatch", "stopLearningJob" -> app.sourceLearningJobCommand(command, request.payload, request.control)
                        else -> app.nativeOwnerWorkflows.learning(command, request.payload, request.control)
                    }
                    "groups" -> app.groupOwnerWorkflows.call(capturedGroupWindow ?: throw SecurityException("请重新打开当前原生群审核页面")) { app.groupOwnerRequest(command, request.payload) }
                    "remoteTask" -> (app.remoteOwnerWorkflows ?: throw IllegalStateException("原生电脑任务宿主未就绪")).handle(activity, command, request.payload)
                    "channelMedia" -> when (command) {
                        "snapshot" -> JSONObject().put("modelCalled", false).put("remoteUploaded", false).put("messageSent", false)
                        "reviewMedia" -> {
                            require(request.payload.keys().asSequence().toSet() == setOf("eventId", "itemIndex"))
                            val review = nativeChannelModule().reviewOwnerMedia(capturedChannelWindow ?: throw SecurityException("请重开当前媒体页"), request.payload.getString("eventId"), request.payload.getInt("itemIndex"))
                            channelMediaReview = review
                            review.metadata()
                        }
                        "freezeMedia" -> {
                            require(request.payload.keys().asSequence().toSet() == setOf("digest"))
                            nativeChannelModule().freezeOwnerMedia(capturedChannelReview ?: throw SecurityException("请先核对这份原媒体"), request.payload.getString("digest"), request.control)
                        }
                        else -> throw IllegalArgumentException("未知原生媒体操作")
                    }
                    "accounts" -> if (command == "selectModel") modelMutation!!.run { request.call.check(); app.nativeOwnerWorkflows.selectModel(request.payload, request.call) } else app.nativeOwnerWorkflows.account(command, request.payload, request.call)
                    else -> throw IllegalArgumentException("未知原生管理页面")
                }
            } catch (error: Exception) { failure = error }
            finally { modelMutation?.close(); if (modelMutation != null) modelMutations.remove(modelMutation) }
            if (failure == null && domain != "accounts" && command !in setOf("snapshot", "list", "search", "get", "listCandidates", "getCandidate", "candidateCapacity", "listCandidateHistory", "cognitionProjection", "searchHistory", "readHistoryRecord", "readAssertionEvidence", "openSource", "readTaskMemoryTrace", "readTaskMemoryTraceOperation", "readTaskNativeOutputAudit", "readLearnedMethodApplications", "listSavedMemorySources", "selectSavedMemorySource", "getConversationHistoryReadScope", "discoverMcp", "discoverMcpAuthorized", "discardMcpPreview", "cancel")) {
                try { app.features.sendState() } catch (_: Exception) { /* The direct receipt remains authoritative. */ }
            }
            try {
                snapshot = when (domain) {
                    "memoryReview" -> memoryReviewSnapshot(capturedMemoryScope)
                    "mcp" -> app.nativeOwnerWorkflows.mcpSnapshot()
                    "accounts" -> app.nativeOwnerWorkflows.accountSnapshot()
                    "learning" -> app.nativeOwnerWorkflows.learningSnapshot()
                    "groups" -> app.groupOwnerWorkflows.call(capturedGroupWindow ?: throw SecurityException("原生群窗口已关闭")) { app.groupOwnerSnapshot() }
                    "remoteTask" -> result?.optJSONObject("snapshot")
                    else -> null
                }
            } catch (error: Exception) { if (result == null && failure == null) failure = error }
            request.call.close(); request.control.cancel()
            val completed = result; val currentSnapshot = snapshot; val error = failure
            if (domain == "accounts" && (currentSnapshot?.optString("mainModelRoute") == "account" || error == null && command == "selectModel")) app.sendState()
            main.post {
                val sourceStage=originalSourceStages.remove(request.id)
                if (closed || request.epoch != (ownerEpochs[domain] ?: 0) || ownerRequests[key]?.id != request.id) {
                    completeWebOwnerReceipt(request.id, null, "原管理窗口已变化，请重新查询原回执")
                    if (command in setOf("discoverMcp", "discoverMcpAuthorized") && completed != null) cleanOwnerResult(domain, "discardMcpPreview", JSONObject().put("previewId", completed.optString("previewId")))
                    if (domain == "mcp" && command == "prepareLocalMcpDiscovery" && completed != null) completed.optString("reviewId").takeIf { it.isNotBlank() }?.let { cleanOwnerResult(domain, "discardLocalMcpReview", JSONObject().put("reviewId", it)) }
                    if (domain == "mcp" && command == "prepareLocalMcpInstall" && completed != null) completed.optString("reviewId").takeIf { it.isNotBlank() }?.let { cleanOwnerResult(domain, "discardLocalMcpInstallReview", JSONObject().put("reviewId", it)) }
                    if (domain == "mcp" && command == "getMcpResource" && completed != null) completed.optString("snapshotId").takeIf { it.isNotBlank() }?.let { cleanOwnerResult(domain, "discardMcpResourceSnapshot", JSONObject().put("snapshotId", it)) }
                    return@post
                }
                ownerRequests.remove(key)
                val envelope = JSONObject().put("requestId", request.id).put("ok", error == null)
                if (domain == "remoteTask") envelope.put("sequence", ++remoteOwnerReceiptSequence)
                listOf("accountId", "expectedVersion", "taskId", "windowId", "reviewId").forEach { field -> if (request.payload.has(field)) envelope.put(field, request.payload.get(field)) }
                if (error == null) envelope.put("result", completed ?: JSONObject()) else { envelope.put("error", ownerFailure(error)); if (domain == "groups") envelope.put("message", ownerFailure(error)) }
                if(domain=="taskMemory"&&command=="selectOwnerOriginalReference"&&error==null)try{
                    val target=sourceStage?:throw IllegalStateException("没有原草稿选择接收方，未自动引用")
                    val model=ownerComposer?:throw IllegalStateException("原草稿已关闭")
                    val draft=model.active();if(draft.key!=target.key||draft.revision!=target.revision||chatViewEpoch!=target.viewEpoch||draft.conversationId!=target.conversation||state.conversationId.orEmpty()!=target.conversation)throw IllegalStateException("原草稿已变化，未将迟回原文引用加入其他输入；请明确重新选择")
                    val receipt=completed?:throw IllegalStateException("缺少原生选择回执")
                    require(receipt.opt("selectionValidated")==true&&receipt.opt("bodyIncluded")==false&&receipt.opt("modelCalled")==false&&receipt.opt("executionAuthorized")==false)
                    val refs=draft.sourceRefs();refs.put(receipt.getJSONObject("ownerSourceRef"))
                    model.editSources(target.key,target.revision,dev.pocketengine.features.assistant.OwnerSourceReferences.checked(refs));publishComposer();scheduleComposerSave()
                    receipt.put("draftStaged",true);state=state.copy(notice="原文精确范围已加入原私聊草稿，尚未发送；发送时会再次核对来源")
                }catch(staging:Exception){envelope.put("ok",false).put("error",message(staging));state=state.copy(error=message(staging))}
                if (domain == "memoryReview" && error != null && command == "snapshot") {
                    if (capturedMemoryScope is MemoryReviewScope.Project && !capturedMemoryScope.selected.metadata().optBoolean("selectionCurrent")) memoryReviewScope = MemoryReviewScope.Unavailable(message(error))
                    state = state.copy(ownerSnapshots = state.ownerSnapshots + (domain to unavailableMemoryScope(message(error))))
                }
                val responses = state.ownerResponses(domain) + (command to envelope)
                if (domain == "groups" && command == "material.return.copy" && error == null && completed != null) {
                    try {
                        app.groupOwnerWorkflows.checkWindow(capturedGroupWindow ?: throw SecurityException("原材料页面已经关闭"))
                        check(completed.optString("deliveryMode") == "manual" && completed.optString("status") == "manual_draft" && !completed.optBoolean("deliveryConfirmed")) { "不是已确认的人工交还草稿" }
                        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("人工交还草稿（未确认投递）", completed.getString("text")))
                        completed.put("copiedLocally", true).put("deliveryConfirmed", false)
                    } catch (copyError: Exception) { envelope.put("ok", false).put("message", message(copyError)); envelope.remove("result") }
                }
                state = state.copy(ownerResponses = state.ownerResponses + (domain to responses), ownerPending = state.ownerPending - key, groupOwnerReceipts = if (domain == "groups") retainedGroupReceipts(request.id, envelope) else state.groupOwnerReceipts,
                    ownerSnapshots = if (currentSnapshot != null && (domain != "accounts" || currentSnapshot.optLong("nativeSnapshotRevision") >= state.ownerSnapshot(domain).optLong("nativeSnapshotRevision"))) state.ownerSnapshots + (domain to currentSnapshot) else state.ownerSnapshots,
                    ownerErrors = if (error != null && command in setOf("snapshot", "openSource", "selectModel")) state.ownerErrors + (domain to ownerFailure(error)) else state.ownerErrors - domain)
                if (domain == "remoteTask" && error != null) state = state.copy(ownerSnapshots = state.ownerSnapshots + (domain to JSONObject(state.ownerSnapshot(domain).toString()).put("fresh", false).put("canContinue", false)))
                if (command == "openSource" && error == null) state = state.copy(ownerSource = completed)
                if (command == "selectModel" && error == null) state = state.copy(notice = completed?.optString("message"))
                completeWebOwnerReceipt(request.id, if (envelope.optBoolean("ok")) envelope.optJSONObject("result") ?: JSONObject() else null, if (envelope.optBoolean("ok")) null else envelope.optString("error", envelope.optString("message", "原操作未确认")))
            }
        } } catch (error: java.util.concurrent.RejectedExecutionException) {
            modelMutation?.close(); if (modelMutation != null) modelMutations.remove(modelMutation)
            request.call.close(); request.control.cancel(); originalSourceStages.remove(request.id); ownerRequests.remove(key)
            val envelope = JSONObject().put("requestId", request.id).put("ok", false).put("error", "页面已关闭，请重新打开后重试")
            state = state.copy(ownerPending = state.ownerPending - key, ownerResponses = state.ownerResponses + (domain to (state.ownerResponses(domain) + (command to envelope))))
        }
        return request.id
    }
    fun openCurrentChannelMedia() {
        try { val file = nativeChannelModule().ownerMediaForViewing(channelMediaReview ?: throw SecurityException("请重新核对这份原件")); openAttachmentOutcome(file) }
        catch (error: Exception) { state = state.copy(error = message(error)) }
    }
    /** Local stored metadata only; does not probe a CLI, query a remote job or select an authority. */
    fun remoteManagedSessions(): JSONObject {
        if (closed || !privateWorkspaceView()) throw SecurityException("请回到私人电脑页读取原任务清单")
        return (app.remoteOwnerWorkflows ?: throw IllegalStateException("原电脑任务宿主未就绪")).managedSessions(activity)
    }
    fun remoteManagedSessionTask(task: String, identity: String): String {
        if (closed || !privateWorkspaceView()) throw SecurityException("请回到私人电脑页打开原任务")
        return (app.remoteOwnerWorkflows ?: throw IllegalStateException("原电脑任务宿主未就绪")).managedSessionTask(activity, task, identity)
    }
    fun enterRemoteTask(taskId: String): String {
        ownerDomain?.let(::leaveOwnerDomain)
        ownerDomain = "remoteTask"; remoteOwnerWindow = UUID.randomUUID().toString(); remoteOwnerTask = taskId
        state = state.copy(ownerSnapshots = state.ownerSnapshots - "remoteTask", ownerResponses = state.ownerResponses - "remoteTask", ownerErrors = state.ownerErrors - "remoteTask")
        requestRemoteTask("open")
        return remoteOwnerWindow!!
    }
    fun enterRemoteProject(request: JSONObject): String {
        ownerDomain?.let(::leaveOwnerDomain)
        ownerDomain = "remoteTask"; remoteOwnerWindow = UUID.randomUUID().toString(); remoteOwnerTask = UUID.randomUUID().toString()
        state = state.copy(ownerSnapshots = state.ownerSnapshots - "remoteTask", ownerResponses = state.ownerResponses - "remoteTask", ownerErrors = state.ownerErrors - "remoteTask")
        requestRemoteTask("project.open", JSONObject().put("request", JSONObject(request.toString())))
        return remoteOwnerWindow!!
    }
    fun enterRemoteInputs(request: JSONObject? = null, taskId: String = ""): String = enterRemoteInputWindow(request,taskId,false)
    fun enterRemoteContinuationInputs(taskId: String): String = enterRemoteInputWindow(null,taskId,true)
    private fun enterRemoteInputWindow(request: JSONObject?,taskId: String,continuation: Boolean): String {
        ownerDomain?.let(::leaveOwnerDomain)
        ownerDomain = "remoteTask"; remoteOwnerWindow = UUID.randomUUID().toString(); remoteOwnerTask = taskId.ifBlank { UUID.randomUUID().toString() }
        state = state.copy(ownerSnapshots = state.ownerSnapshots - "remoteTask", ownerResponses = state.ownerResponses - "remoteTask", ownerErrors = state.ownerErrors - "remoteTask")
        val payload = JSONObject(); if (request != null) payload.put("request", JSONObject(request.toString()))
        requestRemoteTask(if(continuation)"openContinuationInputs" else "openInputs", payload)
        return remoteOwnerWindow!!
    }
    fun leaveRemoteTask(windowId: String) {
        if (remoteOwnerWindow == windowId) leaveOwnerDomain("remoteTask") else app.remoteOwnerWorkflows?.close(windowId)
    }
    fun requestRemoteTask(command: String, payload: JSONObject = JSONObject()): String {
        val window = remoteOwnerWindow ?: throw IllegalStateException("请重新打开原电脑任务审阅页")
        val task = remoteOwnerTask ?: throw IllegalStateException("没有原任务身份")
        return requestOwnerDomain("remoteTask", command, JSONObject(payload.toString()).put("windowId", window).put("taskId", task))
    }
    /** Explicit native UI read only; text never enters owner event/log/response maps. */
    fun readRemoteDraftClipboard(snapshot: JSONObject, draft: String, revision: Long, mode: String, focused: android.view.View): String {
        val window = remoteOwnerWindow ?: throw dev.pocketengine.features.remote.RemoteDraftClipboard.targetFailure()
        val task = remoteOwnerTask ?: throw dev.pocketengine.features.remote.RemoteDraftClipboard.targetFailure()
        if (snapshot.optString("windowId") != window || snapshot.optString("taskId") != task) throw dev.pocketengine.features.remote.RemoteDraftClipboard.targetFailure()
        val host = app.remoteOwnerWorkflows ?: throw dev.pocketengine.features.remote.RemoteDraftClipboard.targetFailure()
        val target = JSONObject().put("windowId", window).put("taskId", task).put("reviewId", snapshot.getString("reviewId"))
            .put("draftId", draft).put("draftRevision", revision).put("mode", mode)
        return host.clipboardDraftText(activity, target) { approval -> dev.pocketengine.features.remote.RemoteDraftClipboard.read(activity, focused, approval) }
    }
    private fun cancelOwnerControl(request: OwnerRequest) {
        if (request.domain == "memoryReview" && request.command == "proposeConsolidation" || request.domain == "learning" && request.command in setOf("proposeLearning", "runLearningJob")) {
            try { app.cancelMemoryConsolidation(request.control) } catch (error: Exception) { state = state.copy(error = "记忆提炼取消回执未完整保存；请核对原任务：" + message(error)) }
        } else request.control.cancel()
    }
    fun cancelConsolidation(requestId: String): String {
        val cancellation = UUID.randomUUID().toString()
        val entry = ownerRequests.entries.find { it.value.domain == "memoryReview" && it.value.command == "proposeConsolidation" && it.value.id == requestId }
        val envelope = JSONObject().put("requestId", cancellation)
        try {
            val result = if (entry == null) JSONObject().put("cancelled", false).put("outcome", "not_active") else app.cancelMemoryConsolidation(entry.value.control)
            result.put("requestId", requestId)
            if (result.optBoolean("cancelled") && entry != null) {
                entry.value.call.cancel(); ownerRequests.remove(entry.key)
                val stopped = JSONObject().put("requestId", requestId).put("ok", false).put("error", "已停止本次提炼，不会自动重试；候选仍须重新读取并由你审阅")
                state = state.copy(ownerPending = state.ownerPending - entry.key, ownerResponses = state.ownerResponses + ("memoryReview" to (state.ownerResponses("memoryReview") + ("proposeConsolidation" to stopped))))
                completeWebOwnerReceipt(requestId, null, stopped.getString("error"))
            }
            envelope.put("ok", true).put("result", result)
        } catch (error: Exception) { envelope.put("ok", false).put("error", "取消回执未确认，请核对原请求；未自动重试：" + message(error)) }
        state = state.copy(ownerResponses = state.ownerResponses + ("memoryReview" to (state.ownerResponses("memoryReview") + ("cancelConsolidation" to envelope))))
        return cancellation
    }
    fun cancelLearning(requestId: String): String {
        val cancellation = UUID.randomUUID().toString()
        val entry = ownerRequests.entries.find { it.value.domain == "learning" && it.value.id == requestId }
        val receipt = JSONObject().put("requestId", cancellation)
        try {
            val result = if (entry == null) JSONObject().put("cancelled", false).put("outcome", "not_active")
                else if (entry.value.command in setOf("proposeLearning", "runLearningJob")) app.cancelMemoryConsolidation(entry.value.control)
                else { entry.value.control.cancel(); JSONObject().put("cancelled", true).put("outcome", "stopped") }
            result.put("requestId", requestId)
            if (result.optBoolean("cancelled") && entry != null) {
                entry.value.call.cancel(); ownerRequests.remove(entry.key)
                val stopped = JSONObject().put("requestId", requestId).put("ok", false).put("error", "本次提炼已停止；已有候选仍按原记录审阅，不会自动重试")
                state = state.copy(ownerPending = state.ownerPending - entry.key, ownerResponses = state.ownerResponses + ("learning" to (state.ownerResponses("learning") + (entry.value.command to stopped))))
                completeWebOwnerReceipt(requestId, null, stopped.getString("error"))
            }
            receipt.put("ok", true).put("result", result)
        } catch (error: Exception) { receipt.put("ok", false).put("error", "停止回执未确认，请核对原任务与候选：" + message(error)) }
        state = state.copy(ownerResponses = state.ownerResponses + ("learning" to (state.ownerResponses("learning") + ("cancelLearning" to receipt))))
        return cancellation
    }
    private fun ownerFailure(error: Exception): String = if (error is dev.pocketengine.features.proxy.accounts.GatewayFailure) error.code else message(error)
    fun openOwnerSource(reference: JSONObject) { state = state.copy(ownerSource = null); requestOwnerDomain("memoryReview", "openSource", reference) }
    fun dismissOwnerSource() {
        ownerRequests.remove("memoryReview.openSource")?.call?.cancel()
        state = state.copy(ownerSource = null, ownerPending = state.ownerPending - "memoryReview.openSource")
    }
    fun selectAccountModel(reference: JSONObject) { requestOwnerDomain("accounts", "selectModel", reference) }
    fun openAccountAuthorization(sessionId: String) {
        if (authorizationOpening != null || ownerDomain != "accounts" || ownerRequests.containsKey("accounts.cancelLogin") || ownerRequests.containsKey("accounts.beginLogin")) return
        val opening = UUID.randomUUID().toString()
        authorizationOpening = opening
        val epoch = ownerEpochs["accounts"] ?: 0
        io.execute {
            try {
                val url = app.nativeOwnerWorkflows.authorizationUrl(sessionId)
                main.post {
                    if (closed || ownerDomain != "accounts" || epoch != (ownerEpochs["accounts"] ?: 0) || authorizationOpening != opening || ownerRequests.containsKey("accounts.cancelLogin")) return@post
                    val login = state.ownerSnapshot("accounts").optJSONObject("login")
                    if (login?.optString("sessionId") != sessionId || login.optString("status") != "pending") { authorizationOpening = null; return@post }
                    try {
                        dev.pocketengine.features.proxy.accounts.ChatGptSignInService.start(activity, sessionId)
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                    }
                    catch (_: Exception) { authorizationOpening = null; state = state.copy(ownerErrors = state.ownerErrors + ("accounts" to "无法打开系统浏览器，请取消本次授权后重试")) }
                }
            } catch (error: Exception) { main.post { if (!closed && epoch == (ownerEpochs["accounts"] ?: 0) && authorizationOpening == opening) { authorizationOpening = null; state = state.copy(ownerErrors = state.ownerErrors + ("accounts" to ownerFailure(error))) } } }
        }
    }
    fun pickMcpConfig(): String {
        configPick?.let { return it.id }
        val request = OwnerRequest(UUID.randomUUID().toString(), "mcp", "pickConfig", JSONObject(), ownerEpochs["mcp"] ?: 0, dev.pocketengine.features.proxy.accounts.GatewayCall(60000))
        configPick = request
        state = state.copy(ownerPending = state.ownerPending + "mcp.pickConfig", ownerErrors = state.ownerErrors - "mcp")
        activity.pickMcpConfig(request.id)
        return request.id
    }
    fun completeMcpConfig(requestId: String?, uri: Uri?) {
        val request = configPick?.takeIf { it.id == requestId } ?: return
        io.execute {
            val response = JSONObject().put("requestId", request.id)
            try {
                if (uri == null) throw IllegalStateException("已取消选择配置，未导入任何内容")
                if (uri.scheme != "content") throw SecurityException("只接受系统文档选择器授权的文件")
                val bytes = app.contentResolver.openInputStream(uri)?.use { stream ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (output.size() <= 65536) { val count = stream.read(buffer, 0, minOf(buffer.size, 65537 - output.size())); if (count < 0) break; output.write(buffer, 0, count) }
                    output.toByteArray()
                } ?: throw IllegalStateException("无法读取所选文件")
                if (bytes.size > 65536) throw IllegalArgumentException("MCP配置不能超过64KB")
                val config = dev.pocketengine.features.NativeOwnerWorkflows.parseMcpConfig(String(bytes, Charsets.UTF_8))
                response.put("ok", true).put("result", JSONObject().put("config", config))
            } catch (error: Exception) { response.put("ok", false).put("error", if (error is org.json.JSONException) "配置格式无效；仅支持不含凭据的id、name、endpoint、protocolVersion JSON对象" else ownerFailure(error)) }
            request.call.close()
            main.post {
                if (closed || configPick?.id != request.id || request.epoch != (ownerEpochs["mcp"] ?: 0)) return@post
                configPick = null
                state = state.copy(ownerPending = state.ownerPending - "mcp.pickConfig", ownerResponses = state.ownerResponses + ("mcp" to (state.ownerResponses("mcp") + ("pickConfig" to response))), ownerErrors = state.ownerErrors - "mcp")
            }
        }
    }

    fun attach() {
        app.addObserver(observer)
        refreshTaskAnnouncementPreference()
        readOwnerExecutionPolicy()
        readTaskScreenshotPolicy()
        modelSelectionSubscription?.close()
        modelSelectionSubscription = app.settings.modelSelection().observe {
            main.post {
                if (!closed) {
                    val status = app.settings.modelSelection().status()
                    modelSelectionRevision = status.revision()
                    if (!status.pending()) modelConnectionChanged()
                }
            }
        }
        modelSelectionRevision = app.settings.modelSelection().status().revision()
        loadOwnerComposer(); refresh()
    }
    /** Native owner action; the host resolves the existing original control, never payload authority. */

    fun detach() { skillDocuments.close();projectFileImports.detach();synchronized(pendingProjectMemoryStarts) { projectMemoryStartClosed=true;pendingProjectMemoryStarts.values.forEach { it.consent.closeIfUnbound() };pendingProjectMemoryStarts.clear() };projectGitImports.detach();projectStorage.detach();ownerArchives.detach();projectGit.close();closeNativeEvaluation();closeNativeInferenceHistory();closeConversationReader();projectPackages.detach();persistOwnerComposer();closeProjectPreview();closeProjectLivePreview(); voiceForeground=false;quickPanelVoiceSession?.close();quickPanelVoiceSession=null;taskAnnouncements.close();voiceContextEpoch.incrementAndGet();voiceInput.close();attachmentOutcomes.detach();groupTaskPage?.let(::leaveGroupTask); groupOwnerWindow?.let { app.groupOwnerWorkflows?.close(it) }; groupOwnerWindow = null; state = state.copy(modelDrafts = emptyMap()); pluginCatalogPage?.close(); pluginCatalogPage = null; ownerDomain?.let(::leaveOwnerDomain); ownerRequests.values.forEach { cancelOwnerControl(it); it.call.cancel() }; main.removeCallbacks(accountPoll); closed = true; modelSelectionSubscription?.close(); modelSelectionSubscription = null; modelMutations.forEach { it.close() }; modelMutations.clear(); app.removeObserver(observer); io.shutdownNow(); controls.shutdownNow(); realtimeIo.shutdownNow(); phonePreviewIo.shutdownNow(); pending.clear(); webFeatureReceipts.clear(); webOwnerReceipts.clear(); webChatCancelCallbacks.clear(); webChatCancelBindings.clear(); ownerWorkInputCallbacks.clear(); closeTaskReviewReads() }
    fun refresh() { authorizationOpening = null; ownerDomain?.let { requestOwnerDomain(it, "snapshot") }; native("getState"); native("getFeatures"); native("rescheduleAutomation"); feature("assistant", "list") }
    fun suggestInput(text: String, phoneTask: Boolean? = null) {
        if (text.isNotBlank()) state = state.copy(suggestedInputRevision = state.suggestedInputRevision + 1, suggestedInput = text, suggestedPhoneTask = phoneTask)
    }
    fun prepareReminderOccurrence(occurrenceId: String) {
        io.execute {
            try {
                val module = app.features.module("automation") ?: throw IllegalStateException("提醒模块暂不可用")
                val receipt = module.handle("receipts", JSONObject()).optJSONArray("receipts").objects().firstOrNull { it.optString("id") == occurrenceId } ?: throw IllegalStateException("提醒已删除或不在当前记录中")
                if (receipt.optString("kind") != "phone_goal") {
                    main.post { if (!closed) state = state.copy(notice = receipt.optString("title", "提醒已打开"), requestedConnectionSection = "automation", requestedConnectionRevision = state.requestedConnectionRevision + 1) }
                } else {
                    val result = app.dispatchOwnerAction("prepareReminderTask", JSONObject().put("occurrenceId", occurrenceId).put("executionMode", "background"))
                    val task = result.getJSONObject("task")
                    main.post { if (!closed) { mergeTask(task); state = state.copy(requestedTaskId = task.getString("id"), requestedTaskRevision = state.requestedTaskRevision + 1, notice = "提醒目标已保存在原任务中，确认执行位置后由你继续") } }
                }
            } catch (error: Exception) { main.post { if (!closed) state = state.copy(error = message(error)) } }
        }
    }
    private var quickPanelVoiceSession: dev.pocketengine.phone.QuickPanelVoiceHandoff.Session? = null
    var quickPanelVoice by mutableStateOf<JSONObject?>(null)
        private set
    private var quickPanelVoiceGeneration = 0L
    internal fun openQuickPanelVoice(token: String) {
        val generation = ++quickPanelVoiceGeneration
        io.execute {
            val loaded = runCatching {
                val session = dev.pocketengine.phone.QuickPanelVoiceHandoff.claim(token)
                try { Triple(session, session.snapshot(), app.tasks.getForUi(session.snapshot().getString("taskId"), false)) }
                catch (failure: Exception) { session.close(); throw failure }
            }
            main.post {
                loaded.fold({ (session, original, task) ->
                    if (closed || generation != quickPanelVoiceGeneration || !privateWorkspaceView() || quickPanelVoiceSession != null || voiceInput.active() || voiceInput.leaseHeld() || voiceInput.snapshot().optBoolean("reviewAvailable")) {
                        session.close(); state = state.copy(error = "请先处理当前语音输入，再从原浮层重新发起；原说明仍保留")
                    } else {
                        mergeTask(task); focusTask(original.getString("taskId")); voiceContextEpoch.incrementAndGet()
                        quickPanelVoiceSession = session; quickPanelVoice = original.put("saving", false).put("closed", false)
                    }
                }, { if (!closed) state = state.copy(error = message(it)) })
            }
        }
    }
    private fun quickPanelVoiceAuthority(session: dev.pocketengine.phone.QuickPanelVoiceHandoff.Session): dev.pocketengine.media.voice.input.VoiceDraftInput.Authority {
        val owner = voiceOwnerAuthority(); val original = session.snapshot()
        return dev.pocketengine.media.voice.input.VoiceDraftInput.Authority {
            owner.check(); session.checkActive(); check(quickPanelVoiceSession === session && privateWorkspaceView()) { "原浮层听写窗口已关闭" }
            val current = state.tasks.firstOrNull { it.id == original.getString("taskId") } ?: error("原任务尚未读取")
            check(current.raw.optString("runId") == original.getString("runId") && current.raw.optInt("coordinatorEpoch") == original.getInt("epoch") && !current.raw.optBoolean("cancelRequested") && current.status in setOf("preparing", "running", "pausing", "paused", "awaiting_user")) { "原任务已变化，没有把听写交给新任务" }
        }
    }
    internal fun startQuickPanelVoice(handoffId: String) {
        try {
            val session = quickPanelVoiceSession ?: error("请从原浮层重新发起听写")
            check(quickPanelVoice?.optString("handoffId") == handoffId && quickPanelVoice?.optBoolean("saveAttempted") != true)
            beginSystemVoiceInput(session.voiceTarget(chatViewEpoch.toLong()), quickPanelVoiceAuthority(session), null)
        } catch (failure: Exception) { state = state.copy(error = message(failure)) }
    }
    internal fun saveQuickPanelVoice(handoffId: String, inputId: String, text: String, done: (JSONObject?, String?) -> Unit) {
        val session = quickPanelVoiceSession ?: throw IllegalStateException("原浮层听写交接已关闭")
        check(quickPanelVoice?.optString("handoffId") == handoffId && quickPanelVoice?.optBoolean("saveAttempted") != true)
        quickPanelVoiceAuthority(session).check()
        check(inputId == voiceInput.snapshot().optString("inputId") && voiceInput.canReview(session.voiceTarget(chatViewEpoch.toLong()))) { "请先核对本次最终听写文字" }
        require(text.isNotBlank() && text.length <= 4000) { "请核对1至4000字的听写文字" }
        quickPanelVoice = JSONObject(quickPanelVoice!!.toString()).put("saving", true).put("saveAttempted", true)
        io.execute {
            val saved = runCatching { session.appendReviewed(text) }
            main.post {
                if (closed || quickPanelVoiceSession !== session) return@post
                saved.fold({ receipt ->
                    voiceInput.discardDraft(inputId)
                    quickPanelVoice = JSONObject(quickPanelVoice!!.toString()).put("saving", false).put("receipt", receipt)
                    state = state.copy(notice = if (receipt.optBoolean("requiresEditingBeforeSend")) "听写已追加到原浮层草稿；超过1500字，发送前请回浮层编辑。" else "听写已追加到原浮层草稿，尚未发送或继续任务。")
                    done(receipt, null)
                }, { failure ->
                    quickPanelVoice = JSONObject(quickPanelVoice!!.toString()).put("saving", false).put("message", "原草稿保存尚未确认；请返回原浮层核对，未重复追加。")
                    done(null, message(failure))
                })
            }
        }
    }
    internal fun closeQuickPanelVoice() {
        ++quickPanelVoiceGeneration
        val id = voiceInput.snapshot().optString("inputId")
        val original = voiceInput.snapshot().optJSONObject("target")?.optString("conversationId").orEmpty().startsWith("quick-panel:")
        if (original) { if (voiceInput.active()) stopListening() else if (id.isNotBlank()) voiceInput.discardDraft(id) }
        quickPanelVoiceSession?.close(); quickPanelVoiceSession = null; quickPanelVoice = null
    }
    private fun invalidateQuickPanelVoice() {
        if (quickPanelVoiceSession == null) return
        if (voiceInput.active()) stopListening()
        quickPanelVoiceSession?.close(); quickPanelVoiceSession = null
        quickPanelVoice = quickPanelVoice?.let { JSONObject(it.toString()).put("closed", true).put("saving", false).put("message", "原前台听写窗口已关闭，请从原浮层重新打开。原说明保留，尚未发送。") }
    }
    private fun voiceInputChanged() { main.post { if(!closed) {
        val actual=voiceInput.snapshot();state=state.copy(voiceInput=actual)
        val key=voiceComposerKey;val model=ownerComposer;val target=actual.optJSONObject("target")
        if(key!=null&&model!=null&&target!=null&&!target.optBoolean("phoneInput")&&actual.optBoolean("reviewAvailable")&&!actual.optBoolean("resourceReleaseUnknown"))try{
            val prior=model.draft(key)
            if(prior!=null&&prior.conversationId==target.optString("conversationId")){model.retainVoice(key,actual.getString("inputId"),actual.getString("finalText"));if(model.activeKey()==key)publishComposer();persistOwnerComposer()}
        }catch(error:Exception){state=state.copy(error=message(error))}
    } } }
    private fun voiceTarget(phone:Boolean):dev.pocketengine.media.voice.input.VoiceDraftInput.Target {
        val run=if(phone)null else state.chatActiveTurn?.let { JSONObject().put("taskId",it.optString("taskId")).put("runId",it.optString("runId")).put("epoch",it.optInt("epoch")) }
        return dev.pocketengine.media.voice.input.VoiceDraftInput.Target(if(phone)"" else state.conversationId.orEmpty(),chatViewEpoch.toLong(),phone,run)
    }
    private fun voiceOwnerAuthority():dev.pocketengine.media.voice.input.VoiceDraftInput.Authority {
        val captured=voiceContextEpoch.get()
        return dev.pocketengine.media.voice.input.VoiceDraftInput.Authority { check(!closed&&voiceForeground&&captured==voiceContextEpoch.get()&&!activity.isFinishing&&!activity.isDestroyed) { "原生语音输入窗口已变化，请重新核对" } }
    }
    fun voiceInputModeChanged() { voiceContextEpoch.incrementAndGet();if(voiceInput.active())stopListening();invalidateQuickPanelVoice() }
    fun voiceInputTypeChanged(phone:Boolean) { voiceInputModeChanged();chatViewEpoch++;voiceInput.inputChanged(voiceTarget(phone)) }
    private fun voiceInputViewChanged() { voiceInput.inputChanged(voiceTarget(voiceInput.snapshot().optJSONObject("target")?.optBoolean("phoneInput") ?: false)) }
    fun startListening() { startSystemVoiceInput(false) }
    fun startSystemVoiceInput(phone:Boolean) {
        try {
            check(quickPanelVoiceSession == null) { "请先处理原浮层的听写窗口" }
            beginSystemVoiceInput(voiceTarget(phone), voiceOwnerAuthority(), if(phone)null else ownerComposer?.activeKey())
        } catch(error:Exception){state=state.copy(error=message(error))}
    }
    private fun beginSystemVoiceInput(target: dev.pocketengine.media.voice.input.VoiceDraftInput.Target, authority: dev.pocketengine.media.voice.input.VoiceDraftInput.Authority, captureComposer: String?) {
        authority.check();check(!state.chatSubmitting&&!state.phoneSubmitting) { "原输入正在登记，请等原回执后再听写" };check(!realtimeOwnsAudio()) { "实时语音正在使用音频，请先结束后再系统听写" }
        val info=activity.media.systemSpeechInfo();state=state.copy(systemSpeech=info)
        check(info.optBoolean("recognitionAvailable")) { "这台设备没有可用系统语音服务。请继续文字输入，没有改用云服务" }
        if(!info.optBoolean("microphoneGranted")){activity.media.requestMicrophoneForDraft();state=state.copy(notice="请在系统窗口授权；授权后再明确点击系统听写，当前没有录音");return}
            val media=activity.media
            voiceInput.start(target,authority,object:dev.pocketengine.media.voice.input.VoiceDraftInput.Audio {
                override fun acquire(owner:String)=media.acquireRealtimeAudio(owner)
                override fun listen(owner:String)=media.listenRealtimeTurn(owner)
                override fun stop(owner:String)=media.stopRealtimeTurn(owner)
                override fun release(owner:String,confirmed:Boolean):java.util.concurrent.CompletableFuture<Void> {
                    val result=java.util.concurrent.CompletableFuture<Void>()
                    // Cleanup runs even after this Controller detaches; a view close must not orphan audio.
                    main.post { try { media.releaseRealtimeAudio(owner,confirmed);check(confirmed&&!media.audioReleaseUnknown()&&!media.ownsRealtimeAudio(owner)) { "系统音频释放未确认" };result.complete(null) }catch(error:Exception){result.completeExceptionally(error)} }
                    return result
                }
                override fun releaseUnknown()=media.audioReleaseUnknown()
            })
        voiceComposerKey=captureComposer
    }
    fun stopListening() { if(voiceInput.active())voiceInput.cancel(voiceInput.snapshot().optString("inputId")) else activity.media.stopListening() }
    fun canReviewVoiceDraft(phone:Boolean)=voiceInput.canReview(voiceTarget(phone))
    fun useVoiceDraft(inputId:String,text:String,phone:Boolean):String? = try {
        val accepted=voiceInput.acceptDraft(inputId,text,voiceTarget(phone),voiceOwnerAuthority()).getString("text")
        if(!phone)ownerComposer?.let{model->val prior=model.active();if(prior.voiceInputId==inputId){model.discardVoice(prior.key,prior.revision);publishComposer();persistOwnerComposer()}}
        accepted
    } catch(error:Exception){state=state.copy(error=message(error));null}
    fun discardVoiceDraft(inputId:String) { try{voiceInput.discardDraft(inputId);ownerComposer?.let{model->val key=voiceComposerKey;val prior=key?.let(model::draft);if(prior!=null&&prior.voiceInputId==inputId){model.discardVoice(prior.key,prior.revision);if(model.activeKey()==key)publishComposer();persistOwnerComposer()}}}catch(error:Exception){state=state.copy(error=message(error))} }
    fun voiceDraftTextChanged(text:String) { val current=voiceInput.snapshot();if(text.isBlank()&&current.optBoolean("staged"))discardVoiceDraft(current.optString("inputId")) }
    fun speak(text: String) { if (voiceInput.active()||voiceInput.leaseHeld()||realtimeOwnsAudio()) state = state.copy(error = "语音正在使用音频，请先结束后再朗读") else activity.media.speak(text) }
    fun stopSpeaking() { activity.media.stopSpeaking() }
    fun realtimeForeground(visible: Boolean) {
        if (!visible) closeNativeEvaluation()
        voiceForeground=visible;if(!visible)voiceInputModeChanged()
        if (visible) refreshTaskAnnouncementPreference()
        taskAnnouncements.setForeground(visible && privateWorkspaceView())
        (app.features.module("channels") as? dev.pocketengine.features.channels.ChannelsModule)?.setNativeOwnerForeground(activity, visible)
        if (!visible && channelMediaWindow != null) leaveOwnerDomain("channelMedia")
        groupForeground(visible)
        val module = app.features.module("realtime") as? RealtimeSessionModule ?: return
        if (visible) module.setForeground(activity, activity.media) else module.clearForeground(activity)
    }
    fun realtimePermissionsChanged() { (app.features.module("realtime") as? RealtimeSessionModule)?.checkCapturePermissions() }
    fun realtimeVideoBinding(sessionId: String, trackSid: String): dev.pocketengine.media.RealtimeMediaSession.VideoBinding {
        check(Looper.myLooper() == Looper.getMainLooper()) { "视频只能绑定当前原生视图" }
        return (app.features.module("realtime") as? RealtimeSessionModule ?: error("实时媒体模块不可用")).bindVideo(activity, sessionId, trackSid)
    }
    fun requestRealtime(command: String, payload: JSONObject = JSONObject()): String = enqueueRealtime(command, payload, null, -1, null)
    internal fun requestWebRealtimeReceipt(command: String, payload: JSONObject, expectedConfigurationRevision: Long,
        current: () -> Boolean, done: (JSONObject?, String?) -> Unit): String {
        check(webPrivateOwnerVisible() && current()) { "原通话窗口已关闭，请重新核对" }
        val owner = webWindowTicket()
        return enqueueRealtime(command, payload, { webPrivateOwnerVisible() && webWindowTicket() == owner && current() }, expectedConfigurationRevision, done)
    }
    private fun enqueueRealtime(command: String, payload: JSONObject, current: (() -> Boolean)?, expectedConfigurationRevision: Long,
        done: ((JSONObject?, String?) -> Unit)?): String {
        val key = "realtime.$command"
        if((voiceInput.active()||voiceInput.leaseHeld())&&command in setOf("prepareNaturalVoice","prepareCall","prepareProviderCall","acceptInvitation","startVoiceTurn","readVoiceTask")) {
            val reason = "请先结束系统听写并等待音频释放，再开始另一项语音"
            state=state.copy(moduleErrors=state.moduleErrors+(key to reason)); done?.invoke(null, reason); return UUID.randomUUID().toString()
        }
        pending.entries.firstOrNull { it.value.first == key }?.let { done?.invoke(null, "这项原通话操作仍在处理，请核对当前状态，未重复提交"); return it.key }
        val requestId = UUID.randomUUID().toString()
        // Credentials stay only in this bounded worker closure, never UI metadata or generic events.
        val input = JSONObject(payload.toString())
        val metadata = JSONObject().put("_requestId", requestId)
        listOf("sessionId", "invitationId", "voiceTurnId", "turnId", "revision", "reviewId", "contentHash", "commitId", "microphone", "camera", "enabled", "muted", "bindingId", "viewToken", "taskId", "readoutId").forEach { if (input.has(it)) metadata.put(it, input.get(it)) }
        pending[requestId] = key to metadata
        state = state.copy(moduleBusy = state.moduleBusy + key, moduleErrors = state.moduleErrors - key)
        val urgent = command in setOf("disconnect", "rejectInvitation", "stopVoiceTurn", "interruptOutput", "cancelVoiceTask", "snapshot") || command == "mute" && input.optBoolean("muted") || command == "setCamera" && !input.optBoolean("enabled")
        try { (if (urgent) controls else realtimeIo).execute {
            var answer: JSONObject? = null; var currentSnapshot: JSONObject? = null; var failure: Exception? = null
            val module = app.features.module("realtime") as? RealtimeSessionModule
            try {
                check(!closed && (current == null || current())) { "原通话窗口已关闭，请重新核对" }
                val original = module ?: error("实时媒体模块不可用")
                answer = if (current == null) original.handleOwner(activity, command, input)
                    else original.handleReviewedOwner(activity, command, input, expectedConfigurationRevision, java.util.function.BooleanSupplier(current))
            }
            catch (error: Exception) { failure = error }
            try { currentSnapshot = module?.snapshot() } catch (_: Exception) { }
            // No provider credential is retained after the native call returns.
            input.remove("participantToken"); input.remove("authenticationToken")
            val result = answer; val snapshot = currentSnapshot; val failed = failure
            main.post {
                if (closed || !pending.containsKey(requestId)) { done?.invoke(null, "原通话页面已关闭，请重新读取实际状态"); return@post }
                snapshot?.let(::receiveRealtimeSnapshot)
                val expectedSession = metadata.optString("sessionId")
                if (expectedSession.isNotBlank() && expectedSession != state.feature("realtime").optString("sessionId")) {
                    pending.remove(requestId); state = state.copy(moduleBusy = state.moduleBusy - key); done?.invoke(null, "原通话会话已经变化，请核对原记录"); return@post
                }
                val event = JSONObject().put("feature", "realtime").put("command", command).put("requestId", requestId).put("ok", failed == null)
                if (failed == null) event.put("result", result ?: JSONObject()) else event.put("message", message(failed))
                featureResult(event)
                if (current != null && !current()) done?.invoke(null, "原通话窗口已变化，请重新读取实际状态；没有重放")
                else done?.invoke(if (failed == null) result ?: JSONObject() else null, failed?.let(::message))
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            input.remove("participantToken"); input.remove("authenticationToken"); pending.remove(requestId)
            state = state.copy(moduleBusy = state.moduleBusy - key, moduleErrors = state.moduleErrors + (key to "通话操作尚未发送，请稍后重新核对"))
            done?.invoke(null, "通话操作尚未发送，请稍后重新核对")
        }
        return requestId
    }
    internal fun requestWebProxyReceipt(command: String, payload: JSONObject, expectedSelectionRevision: Long, expectedSessionId: String,
        current: () -> Boolean, done: (JSONObject?, String?) -> Unit) {
        check(webPrivateOwnerVisible() && current()) { "原本机服务窗口已关闭，请重新核对" }
        val owner = webWindowTicket(); val input = JSONObject(payload.toString())
        val key = "proxy.$command"
        check(key !in state.moduleBusy) { "原本机服务操作仍在处理，请等待真实回执" }
        state = state.copy(moduleBusy = state.moduleBusy + key, moduleErrors = state.moduleErrors - key)
        try { (if (command == "stop") controls else io).execute {
            val result = runCatching {
                val module = app.features.module("proxy") as? dev.pocketengine.features.proxy.ProxyModule ?: error("本机模型服务尚未接入")
                module.handleReviewedOwner(command, input, expectedSelectionRevision, expectedSessionId,
                    java.util.function.BooleanSupplier { webPrivateOwnerVisible() && webWindowTicket() == owner && current() })
            }
            input.remove("token")
            main.post {
                if (closed) { done(null, "原本机服务页面已关闭，请重新读取实际状态"); return@post }
                state = state.copy(moduleBusy = state.moduleBusy - key)
                result.getOrNull()?.let { value -> state = state.copy(features = JSONObject(state.features.toString()).put("proxy", value)) }
                if (!webPrivateOwnerVisible() || webWindowTicket() != owner || !current()) done(null, "原本机服务窗口已变化，请重新核对实际状态；没有重放")
                else result.fold({ done(it, null) }, { done(null, message(it)) })
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            input.remove("token"); state = state.copy(moduleBusy = state.moduleBusy - key)
            done(null, "工作队列不可用，本机服务操作未开始")
        }
    }
    private fun receiveRealtimeSnapshot(incoming: JSONObject) {
        val previous = state.feature("realtime")
        val merged = mergeRealtimeSnapshot(previous, incoming)
        val changedSession = merged.optString("sessionId") != previous.optString("sessionId")
        state = state.copy(features = JSONObject(state.features.toString()).put("realtime", merged), moduleErrors = if (changedSession) state.moduleErrors.filterKeys { !it.startsWith("realtime.") } else state.moduleErrors)
    }
    private fun realtimeOwnsAudio(): Boolean = try {
        val snapshot = app.features.module("realtime")?.snapshot()
        snapshot?.optBoolean("audioLeaseHeld") == true || snapshot?.optJSONObject("voiceTurn")?.optBoolean("active") == true || snapshot?.optJSONObject("session")?.optString("state") in setOf("connecting", "connected", "reconnecting", "disconnecting")
    } catch (_: Exception) { true }
    fun openBrowser() { feature("browser", "open") }
    fun openBrowserResearch() { native("openBrowserResearch") }
    fun openBrowserResearch(taskId: String) { native("openBrowserResearch", JSONObject().put("taskId", taskId)) }
    fun pickAttachments(projectId: String? = null) { activity.pickAttachments(null, projectId) }
    internal fun attachmentImportContextUnavailable() { state = state.copy(error = "原文件选择上下文无法恢复；不会改用当前任务或项目，也不会自动重新选择。请先核对附件库和原入口") }
    internal fun attachmentImportSelectionEnded(cancelled: Boolean) { state = state.copy(notice = if (cancelled) "已取消文件选择，本次未开始导入" else null, error = if (cancelled) null else "文件选择已返回，但没有可接收的内容；本次未开始导入") }
    internal fun attachmentImportPickerStatus(status: AttachmentImportPicker.Launch) {
        state = when (status) {
            AttachmentImportPicker.Launch.REQUESTED -> state.copy(notice = null, error = null)
            AttachmentImportPicker.Launch.BUSY -> state.copy(error = "上一次文件选择或导入尚未结束，请先完成或取消原选择")
            AttachmentImportPicker.Launch.NOT_STARTED -> state.copy(error = "未找到可用的文件选择器，本次未开始导入")
            AttachmentImportPicker.Launch.UNKNOWN -> state.copy(error = "原文件选择是否打开尚未确认；请先返回原选择完成或取消，不会自动重开")
        }
    }
    fun importAttachments(intent: Intent, taskId: String?, projectId: String?) = importAttachments(intent, OwnerAttachmentImport.Target(taskId, projectId))
    fun importAttachments(intent: Intent, original: OwnerAttachmentImport.Target?) {
        if (closed) return
        val received = Intent(intent)
        state = state.copy(attachmentImporting = true, notice = null, error = null)
        try { io.execute {
            try {
                val module = app.features.module("attachments") as? AttachmentsModule ?: throw IllegalStateException("附件能力尚未接入")
                val receipt = OwnerAttachmentImport.receive(module, received, original) { task, file ->
                    app.selectOutcomeForTask(task, file.getString("id"), file.getString("sha256"), dev.pocketengine.core.RunControl())
                }
                main.post {
                    if (!closed) {
                        state = state.copy(attachmentImporting = false, notice = receipt.notice, error = receipt.error)
                        attachmentOutcomes.imported()
                    }
                }
                // A display-refresh failure cannot turn already saved bytes into an import failure.
                runCatching { app.features.sendState() }
            } catch (error: Exception) { main.post { if (!closed) state = state.copy(attachmentImporting = false, error = "附件接收结果未完整确认，可能已有文件保留在附件库；未自动重试，请先核对附件库和原任务") } }
        } } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            state = state.copy(attachmentImporting = false, error = "本次附件尚未开始接收，请返回原入口重新选择")
        }
    }
    fun exportProject() { if (!state.exportPending && "workspace.prepareExport" !in state.moduleBusy) state.projectId?.let { feature("workspace", "prepareExport", JSONObject().put("projectId", it)) } }
    fun exportFile() { if (state.exportPending || "workspace.prepareExport" in state.moduleBusy) return; val project = state.projectId ?: return; val path = state.filePath ?: return; feature("workspace", "prepareExport", JSONObject().put("projectId", project).put("path", path).put("version", state.fileVersion)) }
    fun exportArtifact(id: String) { if (!state.exportPending && "workspace.prepareExport" !in state.moduleBusy) feature("workspace", "prepareExport", JSONObject().put("artifactId", id)) }
    fun createArtifact() { val project = state.projectId ?: return; val path = state.filePath ?: return; if (state.fileVersion > 0 && state.fileContent == state.fileBaseline) feature("workspace", "createArtifact", JSONObject().put("projectId", project).put("path", path).put("version", state.fileVersion)) }
    private fun privateWorkspaceView() = !closed && groupTaskPage == null && ownerDomain !in setOf("groups", "teams")
    internal fun originalProjectEditorCurrent(project:String,checkout:String):Boolean = privateWorkspaceView() && state.projectId == project && projectWorkbench.checkout(project) == checkout
    private fun currentWorkspaceArtifact(source: UiWorkspaceArtifact, fromTaskId: String?): Boolean =
        privateWorkspaceView() && workspaceArtifacts(state).contains(source) && (fromTaskId == null || source.taskId == fromTaskId && state.tasks.any { it.id == fromTaskId && it.raw.optJSONObject("origin")?.optString("kind") != "group" })
    fun refreshWorkspaceArtifacts() {
        if (privateWorkspaceView()) workspaceArtifactsRequest = feature("workspace", "listArtifacts")
    }
    fun openWorkspaceArtifact(source: UiWorkspaceArtifact, fromTaskId: String? = null) {
        if (!privateWorkspaceView()) return
        workspaceArtifactReadRequest = null; workspaceArtifactDiffRequest = null
        state = state.copy(workspaceArtifactView = UiWorkspaceArtifactView(source, fromTaskId))
        if (!currentWorkspaceArtifact(source, fromTaskId)) {
            state = state.copy(workspaceArtifactView = state.workspaceArtifactView!!.copy(loading = false, error = "原成果来源已不可核对，请返回原入口刷新；未打开替代内容")); return
        }
        workspaceArtifactReadRequest = feature("workspace", "readArtifact", JSONObject().put("artifactId", source.id))
    }
    fun closeWorkspaceArtifact() {
        workspaceArtifactReadRequest = null; workspaceArtifactDiffRequest = null
        state = state.copy(workspaceArtifactView = null)
    }
    fun compareWorkspaceArtifact() {
        val view = state.workspaceArtifactView ?: return
        if (view.content == null || !currentWorkspaceArtifact(view.source, view.fromTaskId)) return
        state = state.copy(workspaceArtifactView = view.copy(diffLoading = true, diffError = null))
        workspaceArtifactDiffRequest = feature("workspace", "diffArtifact", JSONObject().put("artifactId", view.source.id))
    }
    private var exactArtifactContinuation: dev.pocketengine.features.workspace.WorkspaceArtifactContinuation? = null
    private var artifactContinuationGeneration = 0L
    fun editWorkspaceArtifactSource() {
        val view = state.workspaceArtifactView ?: return
        if (view.content == null || !currentWorkspaceArtifact(view.source, view.fromTaskId)) return
        if (state.fileSaving || state.fileLoading || state.filePath != null && state.fileContent != state.fileBaseline) {
            state = state.copy(workspaceArtifactView = view.copy(error = "现有编辑区还有未保存内容或正在读写，请先保留原草稿；不会用历史成果覆盖它")); return
        }
        val request = ++artifactContinuationGeneration
        state = state.copy(workspaceArtifactView = view.copy(loading = true, error = null))
        io.execute {
            try {
                val workspace = app.features.module("workspace") as dev.pocketengine.features.workspace.WorkspaceModule
                val selected = workspace.continueArtifactForHost(view.source.id, view.source.sha256)
                val historical = selected.originalText()
                val current = selected.currentText()
                check(historical == view.content) { "原历史成果完整字节与已选版本不同；没有换用当前文件" }
                main.post {
                    if (!closed && request == artifactContinuationGeneration && currentWorkspaceArtifact(view.source, view.fromTaskId) && state.workspaceArtifactView?.source == view.source) {
                        if (state.fileSaving || state.fileLoading || state.filePath != null && state.fileContent != state.fileBaseline) {
                            state = state.copy(workspaceArtifactView = view.copy(loading = false, error = "编辑区已出现新稿，原稿和这份成果均保留；请先处理当前稿"))
                        } else {
                            closeWorkspaceArtifact(); selectProject(view.source.projectId); invalidateFileRequests()
                            exactArtifactContinuation = selected
                            state = state.copy(filePath = view.source.path, fileContent = historical, fileBaseline = current, fileVersion = selected.expected.version,
                                fileLoading = false, fileSaving = false, fileReady = true, fileError = null, fileHistory = emptyList(), revisionContent = null, revisionNumber = null,
                                revisionLoading = false, lastSavedVersion = null, workspaceArtifactEditorOrigin = UiArtifactEditorOrigin(view.source, view.fromTaskId),
                                notice = "已把所选历史成果的原字节放入编辑区。当前文件仅用作保存冲突基线；明确保存才创建新版本，原成果保留。")
                        }
                    }
                }
            } catch (failure: Exception) { main.post { if (!closed && request == artifactContinuationGeneration && state.workspaceArtifactView?.source == view.source) state = state.copy(workspaceArtifactView = view.copy(loading = false, error = message(failure))) } }
        }
    }
    fun focusWorkspaceArtifactProject() {
        val view = state.workspaceArtifactView ?: return
        if (view.content == null || !currentWorkspaceArtifact(view.source, view.fromTaskId)) return
        if (state.fileSaving || state.filePath != null && state.fileContent != state.fileBaseline) {
            state = state.copy(workspaceArtifactView = view.copy(error = "请先保留当前文件草稿，再查看其他项目；不会自动丢弃修改")); return
        }
        closeWorkspaceArtifact(); selectProject(view.source.projectId)
        state = state.copy(projectTaskReturn = view.fromTaskId?.let { UiProjectTaskReturn(view.source.projectId, it) }, requestedProjectId = view.source.projectId, requestedProjectRevision = state.requestedProjectRevision + 1)
    }
    fun returnFromWorkspaceProject() {
        val source = state.projectTaskReturn ?: return
        if (privateWorkspaceView() && source.projectId == state.projectId && state.tasks.any { it.id == source.taskId && it.raw.optJSONObject("origin")?.optString("kind") != "group" }) focusTask(source.taskId)
    }
    fun consumeWorkspaceProjectReturn() { state = state.copy(projectTaskReturn = null) }
    fun exportRestored() { state = state.copy(exportPending = true) }
    fun completeExport(uri: Uri?, temporaryPath: String?) {
        io.execute {
            var temporary: File? = null
            try {
                val exportFile = temporaryPath?.let(::File) ?: throw IllegalStateException("导出内容已失效，请重新选择导出")
                temporary = exportFile
                if (exportFile.canonicalFile.parentFile != app.cacheDir.canonicalFile || !exportFile.name.startsWith("owner-export-") || !exportFile.isFile) throw IllegalStateException("导出内容不可用，请重新导出")
                if (uri == null) { main.post { if (!closed) state = state.copy(exportPending = false, notice = "已取消保存文件") }; return@execute }
                if (uri.scheme != "content") throw SecurityException("只可保存到系统文档选择器授权的位置")
                WorkspaceExportOutput.write(exportFile) { app.contentResolver.openOutputStream(uri, "wt") }
                main.post { if (!closed) state = state.copy(exportPending = false, notice = "文件已保存到你选择的位置") }
            } catch (error: Exception) { main.post { if (!closed) state = state.copy(exportPending = false, error = message(error)) } }
            finally { temporary?.delete() }
        }
    }
    fun startChannelReceiving(review: ChannelReceivingReview, confirmed: Boolean): String = channelReceivingAction("start", JSONObject().put("profileIds", org.json.JSONArray(review.profileIds)).put("reviewFingerprint", review.fingerprint)) { module ->
        if (!confirmed) throw SecurityException("请先明确确认所选连接和接收耗电限制")
        val actual = reviewChannelReceiving(module.snapshot(), review.profileIds)
        if (actual.fingerprint != review.fingerprint) throw SecurityException("连接配置已改变，请重新选择并核对")
        module.startReviewedBackgroundReceiving(activity, org.json.JSONArray(review.reviewedProfiles), true)
    }
    fun stopChannelReceiving(expectedSessionId: String): String = channelReceivingAction("stop", JSONObject().put("sessionId", expectedSessionId)) { module ->
        dev.pocketengine.features.channels.ChannelReceiveService.stopFromOwnerUi(activity, module, expectedSessionId)
    }
    private fun channelReceivingAction(command: String, input: JSONObject, action: (dev.pocketengine.features.channels.ChannelsModule) -> JSONObject): String {
        check(!closed && Looper.myLooper() == Looper.getMainLooper()) { "请在可见原生页面操作接收" }
        val id = UUID.randomUUID().toString(); val key = "owner.channelReceiving.$command"
        state = state.copy(moduleBusy = state.moduleBusy + key, moduleErrors = state.moduleErrors - key)
        val metadata = JSONObject(input.toString()).put("_requestId", id)
        try {
            val module = app.features.module("channels") as? dev.pocketengine.features.channels.ChannelsModule ?: throw IllegalStateException("正式渠道模块未就绪")
            val result = action(module)
            state = state.copy(moduleResults = state.moduleResults + (key to result), moduleResultInputs = state.moduleResultInputs + (key to metadata))
            try {
                val snapshot = JSONObject(state.feature("channels").toString()).put("background", module.backgroundState())
                state = state.copy(features = JSONObject(state.features.toString()).put("channels", snapshot), moduleErrors = state.moduleErrors - "owner.channelReceiving.refresh")
            } catch (_: Exception) { state = state.copy(moduleErrors = state.moduleErrors + ("owner.channelReceiving.refresh" to "操作回执已保留；接收状态尚未重新确认，请刷新核对")) }
        } catch (failure: Exception) {
            state = state.copy(moduleResults = state.moduleResults - key, moduleResultInputs = state.moduleResultInputs + (key to metadata), moduleErrors = state.moduleErrors + (key to message(failure)))
        } finally { state = state.copy(moduleBusy = state.moduleBusy - key); native("getFeatures") }
        return id
    }
    fun openChannelReceivingNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        else activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
    }
    fun openAccessibility() { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    fun openQuickPanel(task: UiTask) {
        voiceInputModeChanged()
        val service = dev.pocketengine.phone.PhoneAccessibilityService.get()
        if (service == null) { state = state.copy(notice = null, error = "请先在系统设置连接无障碍服务，再从原任务打开快捷补充"); return }
        if (service.showQuickPanel(JSONObject().put("taskId", task.id).put("runId", task.raw.optString("runId")).put("epoch", task.raw.optInt("coordinatorEpoch"))))
            state = state.copy(notice = "原任务快捷入口已打开。切到需要的 App 后，点「捕获并预览」选择这次内容。")
        else state = state.copy(notice = null)
    }
    internal val notificationPreviewsOwner by lazy { dev.pocketengine.features.notifications.NotificationOwnerWorkflows(activity, app.notificationsModule(), app.tasks, dev.pocketengine.features.attachments.AttachmentRepository.get(app).book) }
    internal var notificationPreviewsOpen by mutableStateOf(false); private set
    internal var notificationPreviewsFocused by mutableStateOf(false); private set
    internal var notificationPreviewsForeground by mutableStateOf(false); private set
    internal var notificationPreviewsWindowRevision by mutableStateOf(0); private set
    internal var notificationPreviewsSourceRevision by mutableStateOf(0L); private set
    internal var notificationPreviewsOwnerEpoch by mutableStateOf(0L); private set
    internal fun notificationPreviewsFocus(focused: Boolean) { notificationPreviewsFocused = focused; ++notificationPreviewsWindowRevision }
    internal fun notificationPreviewsForeground(foreground: Boolean) { notificationPreviewsForeground = foreground; if (!foreground) { ++notificationPreviewsOwnerEpoch; notificationPreviewsFocus(false) } }
    fun openNotificationPreviews() {
        if (app.notificationsModule() == null) { state = state.copy(error = "原通知预览模块暂不可用，请检查存储后重开应用"); return }
        notificationPreviewsOpen = true
    }
    fun closeNotificationPreviews() { notificationPreviewsOpen = false; app.sendState() }
    fun openNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !state.notificationsEnabled) activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        else activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
    }
    fun dismissError() { state = state.copy(error = null) }
    fun dismissNotice() { state = state.copy(notice = null) }
    fun openAdvanced(panel: String = "settings") {
        activity.startActivity(MainActivity.advancedPanelIntent(activity, panel))
    }
    var conversationReader by mutableStateOf<ConversationReadPage?>(null)
        private set
    private var conversationReadSequence=0L
    fun canReadConversationWindow(page:ConversationReadPage)=!closed&&privateWorkspaceView()&&conversationReader?.window==page.window&&conversationReader?.conversation==page.conversation
    fun openConversationReader(id:String) {
        if(id.isBlank()||id.length>240||!privateWorkspaceView())return
        if(id==state.conversationId){closeConversationReader();return}
        val saved=runCatching{ownerComposer?.readingConversation(id)}.getOrNull()
        conversationReader=ConversationReadPage(++conversationReadSequence,id,state.conversations.find{it.id==id}?.title.orEmpty(),saved?.key,index=saved?.scrollIndex?:0,offset=saved?.scrollOffset?:0,anchor=saved?.anchorMessage.orEmpty(),notice=if(saved==null)"本次阅读位置只在当前窗口保留；没有覆盖其他草稿。" else null)
        persistOwnerComposer();readConversationWindow(false)
    }
    private fun readConversationWindow(older:Boolean) {
        val page=conversationReader?:return;if(!canReadConversationWindow(page)||older&&(!page.hasMore||page.limited))return
        val requested=if(older)page.cursor else "";conversationReader=page.copy(loading=true,error=null)
        io.execute {
            val result=runCatching {
                val assistant=app.features.module("assistant") as? dev.pocketengine.features.assistant.AssistantModule ?: error("私人历史宿主尚未准备")
                assistant.readOriginalConversation(page.conversation,requested,40)
            }
            main.post {val current=conversationReader?:return@post;if(!canReadConversationWindow(page))return@post
                result.fold({native->runCatching{current.accept(native,older)}.fold({conversationReader=it},{failure->conversationReader=current.copy(loading=false,error=message(failure))})},{failure->conversationReader=current.copy(loading=false,error=message(failure))})
            }
        }
    }
    fun refreshConversationReader(){if(conversationReader?.loading!=true)readConversationWindow(false)}
    fun loadEarlierConversationReader(){if(conversationReader?.loading!=true)readConversationWindow(true)}
    fun saveConversationReading(window:Long,index:Int,offset:Int) {
        val page=conversationReader?:return;if(page.window!=window||!canReadConversationWindow(page)||!page.initialized)return
        val anchor=page.messages.getOrNull(index)?.id.orEmpty();conversationReader=page.copy(index=index,offset=offset,anchor=anchor)
        if(page.readingKey!=null)runCatching{ownerComposer?.reading(page.readingKey,index,offset,anchor,page.messages.lastOrNull()?.id.orEmpty(),false);persistOwnerComposer()}
    }
    fun closeConversationReader(){conversationReader=null;++conversationReadSequence}
    fun switchFromConversationReader(window:Long){val page=conversationReader?:return;if(page.window!=window||state.chatSubmitting)return;closeConversationReader();openConversation(page.conversation)}
    fun openReaderAttachment(window:Long,message:String,reference:UiChatAttachment){
        val page=conversationReader?:return;if(page.window!=window||!canReadConversationWindow(page))return
        openPrivateAttachment(reference){conversationReader?.let{canReadConversationWindow(it)&&it.window==window&&it.messages.any{row->row.id==message&&row.attachments.any{file->attachmentOpenKey(file)==attachmentOpenKey(reference)}}}==true}
    }
    fun newConversation() {
        compatibilityChat=null
        if(state.chatSubmitting)return
        val model=ownerComposer ?: return
        try{voiceInputModeChanged();model.newConversation();chatViewEpoch++;publishComposer(true);state=state.copy(messages=emptyList(),chatActiveTurn=null,foregroundTurn=null,chatBusy=false);refreshCurrentConversationTurn();voiceInputViewChanged();persistOwnerComposer()}
        catch(error:Exception){state=state.copy(error=message(error))}
    }
    /** Navigation and draft staging only. The ordinary native send/cancel controls remain authoritative. */
    fun continueCompatibilityChat(id:String) {
        try {
            if(!privateWorkspaceView())throw SecurityException("请先退出群或团队任务，再进入原私人对话；兼容草稿仍保留")
            val review=dev.pocketengine.features.assistant.CompatibilityChatHandoff.read(app,id)
            if(compatibilityChat!=null&&compatibilityChat!==review)throw IllegalStateException("请先处理上一份保留的兼容草稿")
            compatibilityChat=review;compatibilityChatRevision++
            if(review.taskId.isNotBlank())focusTask(review.taskId)
            applyCompatibilityChat()
        } catch(error:Exception){state=state.copy(error=message(error))}
    }
    private fun saveCompatibilityChat(review:dev.pocketengine.features.assistant.CompatibilityChatHandoff.Review,model:OwnerComposerState) {
        compatibilitySaving=true
        ownerComposerStore.save(model).whenComplete { _,error -> main.post {
            compatibilitySaving=false
            if(error!=null){if(!closed)state=state.copy(error="原草稿尚未保存；兼容原文仍保留："+composerFailure(error));return@post}
            dev.pocketengine.features.assistant.CompatibilityChatHandoff.applied(review)
            if(compatibilityChat===review)compatibilityChat=null
        } }
    }
    private fun applyCompatibilityChat() {
        val review=compatibilityChat ?: return;val model=ownerComposer ?: return
        if(!privateWorkspaceView()||compatibilityDialog||compatibilitySaving||state.chatSubmitting)return
        try {
            dev.pocketengine.features.assistant.CompatibilityChatHandoff.read(app,review.id)
            voiceInputModeChanged();val draft=if(review.conversationId.isBlank())model.newConversation() else model.selectConversation(review.conversationId)
            chatViewEpoch++;publishComposer(true);voiceInputViewChanged();closeConversationReader();state=state.copy(messages=emptyList(),chatActiveTurn=null,foregroundTurn=null,chatBusy=false);refreshCurrentConversationTurn()
            if(review.conversationId.isNotBlank())feature("assistant","get",JSONObject().put("conversationId",review.conversationId))
            if(review.applied()){compatibilityChat=null;return}
            if(review.text.isEmpty()||review.text==draft.text){saveCompatibilityChat(review,model);return}
            val accept: (String)->Unit = { text ->
                try {
                    if(!privateWorkspaceView()||compatibilityChat!==review||ownerComposer!==model||model.activeKey()!=draft.key||state.chatSubmitting)throw IllegalStateException("原草稿视图已变化，未覆盖新输入；兼容原文仍保留")
                    model.edit(draft.key,draft.revision,text,draft.mode,draft.attachments());publishComposer();saveCompatibilityChat(review,model)
                    state=state.copy(notice="已放入对应原生草稿，尚未发送；请核对后使用原生发送或原任务停止。")
                } catch(error:Exception){state=state.copy(error=message(error))}
            }
            if(draft.text.isEmpty()&&draft.attachments().length()==0&&draft.sourceRefs().length()==0&&draft.voiceText.isEmpty())accept(review.text)
            else {
                compatibilityDialog=true
                android.app.AlertDialog.Builder(activity).setTitle("保留两份原对话草稿")
                    .setMessage("原生对话已有未发送内容。兼容页原文仍保留；是否将兼容草稿追加到这份原对话草稿？不会自动发送。")
                    .setPositiveButton("追加并继续") { _,_ -> compatibilityDialog=false;accept(if(draft.text.isEmpty())review.text else draft.text+"\n\n"+review.text) }
                    .setNegativeButton("保留原草稿") { _,_ -> compatibilityDialog=false;compatibilityChat=null;state=state.copy(notice="原生草稿未改；兼容页原文仍保留，可返回后再继续。") }
                    .setOnCancelListener { compatibilityDialog=false;compatibilityChat=null }.show()
            }
        } catch(error:Exception){state=state.copy(error=message(error))}
    }
    fun openConversation(id:String) {
        compatibilityChat=null
        if(state.chatSubmitting){openConversationReader(id);return}
        val model=ownerComposer ?: return
        try{voiceInputModeChanged();model.selectConversation(id);chatViewEpoch++;publishComposer(true);state=state.copy(messages=emptyList(),chatActiveTurn=null,foregroundTurn=null,chatBusy=false);refreshCurrentConversationTurn();voiceInputViewChanged();persistOwnerComposer();feature("assistant","get",JSONObject().put("conversationId",id))}
        catch(error:Exception){state=state.copy(error=message(error))}
    }
    private fun refreshCurrentConversationTurn(snapshot: JSONObject? = null): Boolean {
        try {
            val assistant = snapshot ?: app.features.module("assistant")?.snapshot() ?: throw IllegalStateException("原私聊宿主尚未准备")
            val turn = dev.pocketengine.features.assistant.ConversationTurnProjection.current(assistant, state.conversationId, state.chatActiveTurn) {
                try { app.chatInputModel(it).optBoolean("available") } catch (_: Exception) { false }
            }
            val foreground = turn?.takeUnless { it.optString("executionRole") == "owner_work" || state.tasks.firstOrNull { task -> task.id == it.optString("taskId") }?.raw?.optString("executionRole") == "owner_work" }
            state = state.copy(chatActiveTurn = foreground, foregroundTurn = foreground, chatBusy = foreground != null,
                features = JSONObject(state.features.toString()).put("assistant", assistant))
            return true
        } catch (failure: Exception) {
            state = state.copy(chatActiveTurn = null, foregroundTurn = null, chatBusy = false, error = "原对话执行状态暂不可核对，请刷新后再发送：" + message(failure))
            return false
        }
    }
    fun sendChat(text:String,key:String,viewEpoch:Int) {
        if(text.isBlank()||text.trim().length>12000||state.chatSubmitting)return
        val model=ownerComposer ?: run{state=state.copy(error="原草稿尚未读取，未发送");return}
        val draft=model.active()
        if(draft.key!=key||chatViewEpoch!=viewEpoch||text!=draft.text||draft.conversationId!=state.conversationId.orEmpty()){state=state.copy(error="原输入视图已变化，请核对保留草稿；没有发送到其他对话");return}
        if (!refreshCurrentConversationTurn()) return
        val binding=state.chatActiveTurn
        if(state.chatBusy&&(binding==null||binding.optString("conversationId")!=state.conversationId)){state=state.copy(error="原任务绑定仍在准备或收尾，请稍后刷新；没有新建第二任务");return}
        if(state.chatBusy&&try{!app.chatInputModel(binding!!).optBoolean("available")}catch(_:Exception){true}){state=state.copy(error="原任务正在收尾，请核对原消息后再发送");return}
        val selected=state.selectedChatAttachments.toList();val selectedSources=draft.sourceRefs();val selectedPreviews=draft.previewRefs()
        if(selectedPreviews.length()>0&&!composerPreviewRefsAvailable()){state=state.copy(error="原元素引用已不可用，草稿仍保留；请移除或回原预览重新选择");return}
        val fingerprint=ChatInputRules.fingerprint(text.trim(),state.conversationId,state.chatSendMode,selected,selectedSources,selectedPreviews)
        val unresolved=model.matchingPending(draft.key,fingerprint) ?: matchingOriginalOwnerWorkInput(model,draft.key,fingerprint)
        if(unresolved!=null){if(canRetryLocallyUnsent(unresolved.clientId))state=state.copy(notice="本进程确认这条原输入尚未派发；修复保存问题后，请明确点击原输入下的重新保存并发送")else{queryChatSubmission(unresolved.clientId);state=state.copy(notice="正在查询同一输入的原回执，没有用新消息身份重发")};return}
        val client=try{voiceInput.claimMessageId(voiceTarget(false),voiceOwnerAuthority())?:UUID.randomUUID().toString()}catch(error:Exception){state=state.copy(error=message(error));return}
        val input=JSONObject().put("message",text.trim()).put("clientMessageId",client).put("mode",draft.mode).put("attachments",org.json.JSONArray().also{a->selected.forEach{a.put(it.selection())}});if(selectedSources.length()>0)input.put("ownerSourceRefs",selectedSources);if(selectedPreviews.length()>0)input.put("ownerPreviewRefs",selectedPreviews)
        if(!state.chatBusy)input.put("executionScope",newTaskExecutionScope())
        state.conversationId?.let{input.put("conversationId",it)}
        if(state.chatBusy)for(key in listOf("taskId","runId","epoch"))input.put(key,binding!!.get(key))
        try{model.prepare(client,draft.key,draft.revision,fingerprint,if(state.chatBusy)binding else null);publishComposer()}
        catch(error:Exception){state=state.copy(error=message(error));return}
        submittingChatClient=client;composerSaving=true;state=state.copy(chatSubmitting=true,error=null,chatReceipts=state.chatReceipts+(client to JSONObject().put("clientMessageId",client).put("status","unconfirmed")))
        persistPreparedChat(client,input,model,false)
    }
    private val ownerWorkInputCallbacks = mutableMapOf<String, (JSONObject?, String?) -> Unit>()
    /** Uses the same durable owner draft and submission receipt as ordinary conversation input. */
    internal fun sendOwnerWorkInput(expected: JSONObject, client: String, text: String, mode: String, done: (JSONObject?, String?) -> Unit) {
        check(webPrivateOwnerVisible() && !state.chatSubmitting) { "请等待原输入回执" }
        require(UUID.fromString(client).toString() == client && mode in setOf("queue", "steer")) { "补充身份或采用方式无效" }
        val binding = originalOwnerWorkBinding(expected, state.conversationId, state.tasks)
        val model = ownerComposer ?: error("原草稿尚未读取，未发送")
        val draft = model.active()
        require(draft.conversationId == state.conversationId && text == draft.text && text.isNotBlank() && text.trim().length <= 12000 && mode == draft.mode) { "原草稿或补充方式已变化，请核对保留内容" }
        require(draft.sourceRefs().length() == 0 && draft.previewRefs().length() == 0) { "本次任务补充支持文字和已选附件；原文或预览引用仍保留，请先在管家对话中处理" }
        val selected = state.selectedChatAttachments.toList()
        val fingerprint = ownerWorkDraftFingerprint(ChatInputRules.fingerprint(text.trim(), state.conversationId, mode, selected, draft.sourceRefs(), draft.previewRefs()), binding)
        val unresolved = model.matchingPending(draft.key, fingerprint)
        if (unresolved != null) {
            queryChatSubmission(unresolved.clientId)
            done(JSONObject().put("clientMessageId", unresolved.clientId).put("status", "unconfirmed").put("message", "正在查询这条输入的原回执，没有重复提交"), null)
            return
        }
        require(model.pending(client) == null) { "这条输入身份已登记，请查询原回执" }
        model.prepare(client, draft.key, draft.revision, fingerprint, binding)
        val input = JSONObject(binding.toString()).put("message", text.trim()).put("clientMessageId", client).put("mode", mode)
            .put("attachments", org.json.JSONArray().also { files -> selected.forEach { files.put(it.selection()) } }).put("_ownerWorkTargeted", true)
        ownerWorkInputCallbacks[client] = done
        publishComposer(); submittingChatClient = client; composerSaving = true
        state = state.copy(chatSubmitting = true, error = null, chatReceipts = state.chatReceipts + (client to JSONObject().put("clientMessageId", client).put("status", "unconfirmed")))
        persistPreparedChat(client, input, model, false)
    }
    internal fun controlOwnerWork(expected: JSONObject, action: String, done: (JSONObject?, String?) -> Unit) {
        check(webPrivateOwnerVisible()) { "请回到原私人工作台" }
        require(action in setOf("cancel", "continue")) { "任务动作无效" }
        val input = originalOwnerWorkBinding(expected, state.conversationId, state.tasks).put("action", action)
        val conversation = state.conversationId ?: error("原对话未读取")
        io.execute {
            val result = runCatching { app.ownerWorkControl(conversation, input) }
            main.post { if (!closed) { result.fold({ done(it, null) }, { done(null, message(it)) }); getTask(input.getString("taskId")); refreshCurrentConversationTurn() } }
        }
    }
    internal fun readMessageOrigin(reply: String, source: String, offset: Int, done: (JSONObject?, String?) -> Unit) {
        check(webPrivateOwnerVisible()) { "请回到原私人对话" }
        val conversation = state.conversationId ?: error("原对话未读取")
        val row = state.messages.firstOrNull { it.id == reply } ?: error("原答复已离开当前窗口")
        require(row.replyContext?.replyToMessageIds?.contains(source) == true && offset >= 0) { "这条原请求不属于所选答复" }
        val window = webWindowTicket()
        io.execute {
            val result = runCatching {
                val assistant = app.features.module("assistant") as? dev.pocketengine.features.assistant.AssistantModule ?: error("私人历史宿主尚未准备")
                assistant.readOriginalReplySource(conversation, reply, source, offset, 8000)
            }
            main.post { if (!closed) {
                if (webWindowTicket() != window || state.conversationId != conversation) done(null, "原答复窗口已变化，请重新打开")
                else result.fold({ done(it, null) }, { done(null, message(it)) })
            } }
        }
    }
    fun canRetryLocallyUnsent(client:String):Boolean {
        if(closed||state.chatSubmitting)return false
        val local=locallyUnsent[client]?:return false;val model=ownerComposer?:return false;val p=model.pending(client)?:return false;val draft=model.active()
        if(draft.key!=local.key||draft.revision!=local.revision||p.draftKey!=local.key||p.draftRevision!=local.revision||draft.conversationId!=state.conversationId.orEmpty())return false
        val target=p.originalTarget();val actual=state.chatActiveTurn
        if (JSONObject(local.payload).optBoolean("_ownerWorkTargeted")) return runCatching { originalOwnerWorkBinding(target, state.conversationId, state.tasks) }.isSuccess
        if(target.length()==0)return !state.chatBusy
        return state.chatBusy&&actual!=null&&listOf("taskId","runId","epoch").all{target.opt(it)==actual.opt(it)}
    }
    fun retryLocallyUnsent(client:String) {
        if(!canRetryLocallyUnsent(client)){state=state.copy(notice="只有本进程确认尚未派发、且原输入版本仍相同的请求可明确重试；重开或未查到回执不会自动重发");return}
        val local=locallyUnsent[client]?:return;val model=ownerComposer?:return
        submittingChatClient=client;state=state.copy(chatSubmitting=true,error=null);composerSaving=true
        persistPreparedChat(client,JSONObject(local.payload),model,true)
    }
    private fun persistPreparedChat(client:String,input:JSONObject,model:OwnerComposerState,localRetry:Boolean) {
        // The native client identity is durable BEFORE calling the original send entry point.
        // A late completion after detach never sends; reopening queries this ID only.
        main.removeCallbacks(composerSave);val version=model.revision()
        val originalPending=model.pending(client)?:return
        val saving=if(localRetry)ownerComposerStore.saveAfterLocalUnsentFailure(model)else ownerComposerStore.save(model)
        saving.whenComplete { _,error -> main.post {
            if(closed)return@post
            if(error!=null){ownerWorkInputCallbacks.remove(client)?.invoke(null, "原输入尚未保存，未发送；草稿与原输入身份仍保留");locallyUnsent[client]=LocalUnsent(input.toString(),originalPending.draftKey,originalPending.draftRevision);if(submittingChatClient==client)submittingChatClient=null;state=state.copy(chatSubmitting=false,error="输入定位未能保存，本次未发送；可修复后明确重新保存并发送同一原身份："+composerFailure(error));return@post}
            if(ownerComposer?.revision()==version)composerSaving=false
            val pendingInput=ownerComposer?.pending(client)
            if(pendingInput==null||pendingInput.fingerprint!=originalPending.fingerprint)return@post
            // Once the real send entry is called, uncertainty is reconciled by query only.
            locallyUnsent.remove(client)
            if (input.optBoolean("_ownerWorkTargeted")) {
                val nativeInput = JSONObject(input.toString()).also { it.remove("_ownerWorkTargeted") }
                io.execute {
                    val result = runCatching { app.ownerWorkInput(originalPending.conversationId, nativeInput) }
                    main.post { if (!closed) {
                        result.fold({ receipt ->
                            receiveChatAcknowledgement(receipt, client)
                            ownerWorkInputCallbacks.remove(client)?.invoke(receipt, null)
                        }, { failure ->
                            if (submittingChatClient == client) submittingChatClient = null
                            state = state.copy(chatSubmitting = false, notice = "补充回执尚未确认；草稿仍保留，请查询原输入。")
                            ownerWorkInputCallbacks.remove(client)?.invoke(null, message(failure))
                        })
                        getTask(nativeInput.getString("taskId"))
                    } }
                }
            } else feature("assistant","send",input)
        } }
    }
    /** Web stop is bound to the exact rendered conversation/run and rechecked by AssistantModule. */
    internal fun cancelChatFromWeb(expected: JSONObject, done: (JSONObject?, String?) -> Unit) {
        val input = originalChatCancelBinding(expected, state.conversationId, state.chatBusy, state.chatActiveTurn)
            ?: throw IllegalStateException("原回复身份已变化或尚未确认，请刷新后停止原任务")
        val identity = org.json.JSONArray().put(input.getString("conversationId")).put(input.getString("taskId"))
            .put(input.getString("runId")).put(input.getInt("epoch")).toString()
        check(identity !in webChatCancelBindings.values) { "这条原回复的停止请求仍待核对，请等待回执" }
        check(webChatCancelBindings.size < 16) { "原停止回执待核对数量已满，请先核对原任务" }
        val requestId = UUID.randomUUID().toString()
        val key = "assistant.cancel"
        state = state.copy(moduleBusy = state.moduleBusy + key, moduleErrors = state.moduleErrors - key)
        pending[requestId] = key to JSONObject(input.toString())
        webChatCancelCallbacks[requestId] = done
        webChatCancelBindings[requestId] = identity
        try {
            native("featureRequest", JSONObject().put("feature", "assistant").put("command", "cancel")
                .put("payload", input).put("requestId", requestId))
        } catch (failure: Exception) {
            webChatCancelCallbacks.remove(requestId); webChatCancelBindings.remove(requestId); pending.remove(requestId)
            if (pending.values.none { it.first == key }) state = state.copy(moduleBusy = state.moduleBusy - key)
            throw failure
        }
    }

    fun cancelChat() {
        val input = JSONObject().put("conversationId", state.conversationId)
        state.tasks.find { it.status == "running" && it.raw.optString("conversationId") == state.conversationId && it.raw.optJSONObject("developmentSession")?.optString("phase") == "owned_running" }?.let { task ->
            val held = task.raw.getJSONObject("developmentSession")
            input.put("taskId", task.id).put("runId", held.getString("runId")).put("epoch", held.getInt("epoch"))
        }
        state = state.copy(modelDrafts = state.modelDrafts.filterKeys { id -> state.tasks.find { it.id == id }?.raw?.optString("conversationId") != state.conversationId })
        feature("assistant", "cancel", input)
    }
    var newTaskScopeMinutes by mutableStateOf(0)
        private set
    // Presentation-only native-state projection. Task admission and reviews use the fenced getters below.
    val displayNewTaskToolBudget: Int get() = state.modelMaxSteps
    val displayNewTaskModelBudget: Int get() = dev.pocketengine.store.TaskExecutionScope.defaultModelBudget(displayNewTaskToolBudget)
    val newTaskToolBudget: Int get() = app.settings.get().maxSteps
    val newTaskModelBudget: Int get() = dev.pocketengine.store.TaskExecutionScope.defaultModelBudget(newTaskToolBudget)
    fun selectNewTaskDeadline(minutes: Int) { require(minutes in setOf(0,30,120)); newTaskScopeMinutes = minutes }
    private fun newTaskExecutionScope(): JSONObject = JSONObject().put("deadlineAt", if (newTaskScopeMinutes == 0) 0L else System.currentTimeMillis() + newTaskScopeMinutes * 60_000L).put("toolLimit", newTaskToolBudget).put("modelCallLimit", newTaskModelBudget)
    fun configureExecutionScope(task: UiTask, minutes: Int, tools: Int, models: Int) {
        if (minutes !in setOf(-1,0,30,120) || tools !in 1..400 || models !in 1..512) return
        native("configureTaskExecutionScope", JSONObject().put("taskId",task.id).put("expectedRevision",task.raw.optJSONObject("executionScope")?.optLong("revision") ?: 0L).put("deadlineAt",if(minutes == -1) task.raw.getJSONObject("executionScope").getLong("deadlineAt") else if(minutes==0)0L else System.currentTimeMillis()+minutes*60_000L).put("toolLimit",tools).put("modelCallLimit",models))
    }
    private var channelPhoneRequest: JSONObject? = null
    private var channelPhoneRevision = 0L
    var channelPhoneEventId: String? = null
        private set
    private var channelPhoneBlocked = false
    private var channelPhoneAccepted = false
    val hasChannelPhoneOrigin: Boolean get() = channelPhoneEventId != null
    fun phoneDraftEdited(text: String) {
        if ((text.isBlank() || channelPhoneAccepted) && channelPhoneEventId != null) { cancelChannelPhone(); channelPhoneRequest = null; channelPhoneEventId = null; channelPhoneBlocked = false; channelPhoneAccepted = false }
    }
    fun prepareChannelPhone(eventId: String) {
        val revision = ++channelPhoneRevision
        channelPhoneEventId = eventId; channelPhoneBlocked = true; channelPhoneAccepted = false
        channelPhoneRequest = null
        io.execute {
            try {
                val request = app.prepareChannelPhone(eventId)
                main.post {
                    if (!closed && revision == channelPhoneRevision) {
                        if (request.optString("admission") == "awaiting_owner") {
                            channelPhoneRequest = request; channelPhoneBlocked = false
                            suggestInput(request.getString("goal"), true)
                            state = state.copy(notice = "来自已认证主人私聊，原渠道主人任务授权仍有效；本机确认屏幕后，结果将返回原私聊。清空草稿才能另写本机新目标。")
                        } else state = state.copy(notice = "此渠道手机请求已处理或取消；请查看原任务，不会重复启动")
                    }
                }
            } catch (error: Exception) { main.post { if (!closed && revision == channelPhoneRevision) state = state.copy(error = message(error) + "。/phone 还需原渠道已绑定主人并开启主人任务授权；授权后请从原私聊发送新请求，再在本机确认屏幕使用。") } }
        }
    }
    fun cancelChannelPhone() {
        if (channelPhoneEventId == null) return
        ++channelPhoneRevision
        val request = channelPhoneRequest; channelPhoneBlocked = true
        if (request != null) io.execute { try { app.cancelChannelPhone(request.getString("requestId")) } catch (error: Exception) { main.post { if (!closed) state = state.copy(error = message(error)) } } }
    }
    internal fun prepareConversationPhoneStart(conversation: String, goal: String, mode: String): EngineApplication.ConversationPhoneStartReview {
        check(webPrivateOwnerVisible() && conversation.isNotBlank() && state.conversationId == conversation && composerDraft?.conversationId == conversation) { "请回到原私人对话核对这次手机任务" }
        check(!hasChannelPhoneOrigin) { "渠道请求保留原渠道归属，不能改为私人对话任务" }
        return app.prepareConversationPhoneStart(conversation, goal, mode, JSONObject()
            .put("scopeMinutes", newTaskScopeMinutes).put("toolLimit", newTaskToolBudget).put("modelCallLimit", newTaskModelBudget))
    }
    internal fun startConversationPhone(review: EngineApplication.ConversationPhoneStartReview, ownerCurrent: () -> Boolean, completed: (JSONObject?, String?) -> Unit): Boolean {
        val shown = review.view()
        if (!webPrivateOwnerVisible() || !ownerCurrent() || hasChannelPhoneOrigin) { state = state.copy(error = "原私人手机任务审阅已变化，请重新核对"); return false }
        if (!preparePhoneSubmission(shown.getString("goal"), shown.getString("mode"))) return false
        state = state.copy(phoneSubmitting = true)
        return try {
            io.execute {
                var allocated: String? = null
                val result = runCatching { app.startConversationPhone(review, true,
                    java.util.function.BooleanSupplier { webPrivateOwnerVisible() && ownerCurrent() },
                    java.util.function.Consumer { allocated = it }) }
                main.post {
                    if (!closed) {
                        state = state.copy(phoneSubmitting = false)
                        result.exceptionOrNull()?.let { state = state.copy(error = message(it)) }
                        val receipt = result.getOrNull()
                        if (receipt?.optBoolean("admitted") == true && shown.getString("mode") == "main_screen") {
                            val task = receipt.optString("taskId")
                            main.postDelayed({ if (!closed && !activity.isFinishing && !activity.isDestroyed && app.activeId() == task) activity.moveTaskToBack(true) }, 350)
                        }
                    }
                    // The newly admitted phone identity changes the window ticket itself. Preserve
                    // the original receipt even after navigation; never repeat a start to recover it.
                    completed(result.getOrNull() ?: JSONObject().put("requestId", shown.getString("requestId"))
                        .put("conversationId", shown.getString("conversationId")).also { value ->
                            allocated?.let { value.put("taskId", it) }
                            if (allocated == null && result.exceptionOrNull() is EngineApplication.ConversationPhoneStartRejected) value.put("status", "rejected")
                        },
                        result.exceptionOrNull()?.let(::message))
                }
            }
            true
        } catch (error: Exception) { state = state.copy(phoneSubmitting = false, error = message(error)); false }
    }
    internal fun queryConversationPhoneStart(request: String, conversation: String, completed: (JSONObject?, String?) -> Unit) {
        check(webPrivateOwnerVisible()) { "请在私人工作区查询原手机任务" }
        io.execute {
            val result = runCatching { check(webPrivateOwnerVisible()); app.queryConversationPhoneStart(request, conversation) }
            main.post { completed(result.getOrNull(), result.exceptionOrNull()?.let(::message)) }
        }
    }
    private fun preparePhoneSubmission(goal: String, mode: String): Boolean {
        if (modelSelectionPending()) { state = state.copy(error = "模型设置正在保存，请等待回执后再开始手机任务"); return false }
        if (state.phoneSubmitting) { state = state.copy(error = "请先核对上一次手机任务的启动回执"); return false }
        if (channelPhoneBlocked) { state = state.copy(error = "原渠道请求待核对或已取消；请清空草稿后另写本机新目标") ; return false }
        val channel = channelPhoneRequest
        if (channel != null && channel.optString("goal") != goal.trim()) { state = state.copy(error = "渠道原目标已改变，请先取消原请求；不会按私人新任务启动") ; return false }
        if (mode == "background") {
            val connection = backgroundConnectionState(state.feature("display"), state.activeTaskId, state.moduleBusy)
            if (!connection.helperConnected || connection.busy || connection.requiresConnectionCheck) {
                openBackgroundSetup()
                state = state.copy(notice = if (connection.busy) "请等待本次连接与进程回收结束，再明确开始原目标" else "先启用并核对后台助手，再返回确认原目标；草稿仍保留")
                return false
            }
        }
        try{voiceInput.claimMessageId(voiceTarget(true),voiceOwnerAuthority())}catch(error:Exception){state=state.copy(error=message(error));return false}
        return true
    }
    fun startPhone(goal: String, mode: String, completed: ((JSONObject?, String?) -> Unit)? = null): Boolean {
        if (!preparePhoneSubmission(goal, mode)) return false
        val channel = channelPhoneRequest
        state = state.copy(phoneSubmitting = true)
        return try {
            native("startRun", JSONObject().put("goal", goal.trim()).put("executionMode", mode).put("screenConsent", true).put("executionScope",newTaskExecutionScope()).also { if (channel != null) it.put("channelRequestId", channel.getString("requestId")) }, completed = completed)
            true
        } catch (error: Exception) {
            state = state.copy(phoneSubmitting = false, error = message(error))
            false
        }
    }
    fun pausePhone() { state.tasks.find { it.id == state.activeTaskId }?.let { controlOriginalPhone(it, "pause") } }
    fun continuePhone() { state.tasks.find { it.id == state.activeTaskId }?.let { controlOriginalPhone(it, "resume") } }
    fun stopPhone() {
        val task = state.tasks.find { it.id == state.activeTaskId }
        if (task != null && isPhoneTask(task)) stopReviewedTask(task) else state = state.copy(error = "请先读取原手机任务，再停止该次执行")
    }
    internal fun controlOriginalPhone(task: UiTask, action: String, feedback: String = "", completed: ((JSONObject?, String?) -> Unit)? = null) {
        val id = task.id; val run = task.raw.optString("runId"); val epoch = task.raw.optInt("coordinatorEpoch", -1)
        val group = task.raw.optJSONObject("origin")?.optString("kind") == "group"
        val groupWindow = if (group && groupTaskId == id) groupTaskWindow else null
        if (!isPhoneTask(task) || run.isBlank() || epoch < 1) {
            val reason = "原手机执行身份尚未读全，请刷新原任务"
            state = state.copy(error = reason); completed?.invoke(null, reason); return
        }
        state = state.copy(taskActionErrors = state.taskActionErrors - id)
        try { controls.execute {
            val result = runCatching {
                val execute = {
                  when (action) {
                    "pause" -> app.pauseOriginalPhoneRun(id, run, epoch)
                    "stop" -> app.stopOriginalPhoneRun(id, run, epoch)
                    "resume" -> app.continueOriginalPhoneTaskReceipt(id, run, epoch, feedback, true, task.executionMode)
                    else -> error("未知原手机控制操作")
                  }
                }
                if (group) app.groupOwnerWorkflows.call(groupWindow ?: throw SecurityException("请在原群任务窗口核对这次控制")) { execute() } else execute()
            }
            main.post {
                if (!closed) result.fold({ value ->
                    state = state.copy(notice = value.optString("message").takeIf { it.isNotBlank() } ?: "原任务控制请求已接纳；实际状态以原回执为准")
                    if (action == "resume") state = state.copy(savedFeedbackRevision = state.savedFeedbackRevision + 1, savedFeedbackTaskId = id, savedFeedback = feedback)
                    if (action == "resume" && task.executionMode == "main_screen") moveOriginalPhoneToBackground(value)
                }, { state = state.copy(taskActionErrors = state.taskActionErrors + (id to message(it)), error = message(it)) })
                if (!closed) getTask(id)
                result.fold({ completed?.invoke(it, null) }, { completed?.invoke(null, message(it)) })
            }
        } } catch (error: Exception) { state = state.copy(error = message(error)); completed?.invoke(null, message(error)) }
    }
    private fun moveOriginalPhoneToBackground(receipt: JSONObject) {
        val id = receipt.optString("taskId"); val run = receipt.optString("runId"); val epoch = receipt.optInt("epoch", -1)
        if (id.isBlank() || run.isBlank() || epoch < 1) return
        main.postDelayed({
            if (!closed && !activity.isFinishing && !activity.isDestroyed && app.control(id)?.matchesHostRun(id, run, epoch) == true) activity.moveTaskToBack(true)
        }, 350)
    }
    internal fun releaseHeldPhoneResources(task: UiTask, window: android.view.View) {
        if (!privateWorkspaceView() || state.activeTaskId != task.id || task.raw.optString("actorId") != "owner"
            || task.raw.optString("spaceId") != "private" || !phoneOwnerWindowCurrent(window)) return
        try {
            val resources = dev.pocketengine.phone.PhoneResourceDisposition.forTask(task.raw) ?: return
            check(resources.optString("state") == "held_for_owner" && task.status in setOf("paused", "awaiting_user", "reconciling"))
            // The existing native service rechecks the original task/run/epoch/control before app.stop.
            activity.startService(dev.pocketengine.phone.PhoneNotificationControls.command(activity, dev.pocketengine.PhoneRunService.STOP, task.raw))
            state = state.copy(notice = "已请求停止原任务并释放其独立屏；实际结果以原回执为准。")
        } catch (_: Exception) { state = state.copy(notice = "原保留会话暂不可核对，请刷新原任务；未停止其他执行。") }
    }
    fun stopTask(taskId: String) {
        val task = state.tasks.find { it.id == taskId }
        if (task != null) stopReviewedTask(task) else state = state.copy(error = "请先读取原任务，再停止该次执行")
    }
    private fun reviewedTaskInput(task: UiTask): JSONObject = JSONObject().put("taskId", task.id)
        .put("runId", task.raw.optString("runId")).put("epoch", task.raw.optInt("coordinatorEpoch"))
    fun stopReviewedTask(task: UiTask) { if (isPhoneTask(task)) controlOriginalPhone(task, "stop") else native("stopTask", reviewedTaskInput(task)) }
    fun confirmReviewedTask(task: UiTask, done: Boolean, feedback: String) { native("confirmResult", reviewedTaskInput(task).put("resultReviewDigest", task.raw.optString("resultReviewDigest")).put("completed", done).put("feedback", feedback)) }
    fun resolveReviewedTask(task: UiTask, operationId: String, outcome: String) { native("resolveOperation", reviewedTaskInput(task).put("operationId", operationId).put("outcome", outcome)) }
    fun getTask(id: String) { native("getTask", JSONObject().put("taskId", id)) }
    fun focusTask(id: String) { focusTask(id, requestNavigation = true) }

    internal fun focusTask(id: String, requestNavigation: Boolean) {
        closeConversationReader()
        getTask(id)
        // Web-owned routes load the task without emitting another external navigation request.
        if (requestNavigation) {
            state = state.copy(requestedTaskId = id, requestedTaskRevision = state.requestedTaskRevision + 1)
        }
    }
    internal fun prepareConnectionEntry() {
        taskMcpPreparation = null
    }

    fun openConnection(section: String) {
        prepareConnectionEntry()
        state = state.copy(requestedConnectionSection = section, requestedConnectionRevision = state.requestedConnectionRevision + 1)
    }
    fun usesChatGptPlan(phone: Boolean): Boolean {
        if (phone || !state.chatBusy) return state.modelRoute == "account"
        val binding = state.chatActiveTurn ?: return false
        return try { val model = app.chatInputModel(binding); model.optBoolean("available") && model.optString("modelRoute") == "account" } catch (_: Exception) { false }
    }
    fun chatGptPlanUsageLimited(phone: Boolean): Boolean {
        return try {
            val selected = app.settings.get()
            if (phone || !state.chatBusy) selected.usesAccount() && ModelGateway.accountPlanUsageLimited(selected.accountModel)
            else {
                val binding = state.chatActiveTurn ?: return false
                val model = app.chatInputModel(binding)
                if (!model.optBoolean("available") || model.optString("modelRoute") != "account") return false
                val original = dev.pocketengine.core.ModelConfig.AccountModelRef.fromJson(model.getJSONObject("nativeRef"))
                // Recovery display may follow native renewal of this same selected account/model;
                // the original input's execution/control/model binding is never rewritten here.
                val current = if (selected.usesAccount() && selected.accountModel.sameModel(original)) selected.accountModel else original
                ModelGateway.accountPlanUsageLimited(current)
            }
        } catch (_: Exception) { false }
    }
    fun openChatGptUsage() {
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dev.pocketengine.features.proxy.accounts.ChatGptRecovery.MANAGE_USAGE_URL)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: Exception) { state = state.copy(error = "无法打开 Manage usage，请在 ChatGPT 设置中查看使用量") }
    }
    fun openCommitment(id: String, claimId: String? = null) {
        state = state.copy(requestedCommitmentId = id, requestedFollowUpClaimId = claimId, requestedCommitmentRevision = state.requestedCommitmentRevision + 1)
        openConnection("commitments")
        feature("automation", "getCommitment", JSONObject().put("id", id))
    }
    fun requestOwnerAction(action: String, input: JSONObject = JSONObject()): String {
        val id = UUID.randomUUID().toString()
        val key = "owner." + action
        val keepPlanError = action == "automationPlan" && input.optString("command") == "snapshot"
        state = state.copy(moduleBusy = state.moduleBusy + key, moduleErrors = if (keepPlanError) state.moduleErrors else state.moduleErrors - key)
        native(action, JSONObject(input.toString()), id)
        return id
    }
    internal fun requestOwnerActionReceipt(action: String, input: JSONObject, done: (JSONObject?, String?) -> Unit): String {
        check(webPrivateOwnerVisible()) { "请回到前台私人工作台操作" }
        val id = UUID.randomUUID().toString(); val key = "owner." + action
        state = state.copy(moduleBusy = state.moduleBusy + key, moduleErrors = state.moduleErrors - key)
        native(action, JSONObject(input.toString()), id, completed = done)
        return id
    }
    /** A native liveness predicate, never an input field or another permission source. */
    internal fun requestWebOwnerActionReceipt(action: String, input: JSONObject, current: () -> Boolean, done: (JSONObject?, String?) -> Unit): String {
        check(webPrivateOwnerVisible() && current()) { "原审阅窗口已关闭" }
        val id = UUID.randomUUID().toString(); val key = "owner." + action
        state = state.copy(moduleBusy = state.moduleBusy + key, moduleErrors = state.moduleErrors - key)
        native(action, JSONObject(input.toString()), id, completed = done, originalOwnerCurrent = current)
        return id
    }
    private data class ProjectStartMemoryRequest(val retryOriginal: Boolean)
    private data class PendingProjectMemoryStart(val input: String, val consent: dev.pocketengine.features.memory.ProjectMemoryAccess.Consent)
    private val pendingProjectMemoryStarts = java.util.concurrent.ConcurrentHashMap<String, PendingProjectMemoryStart>()
    @Volatile private var projectMemoryStartClosed = false
    /** Native project composer only. No JSON field, restored checkbox or model route mints consent. */
    fun requestProjectStart(input: JSONObject, useProjectMemory: Boolean, retryOriginal: Boolean = false): String {
        if (projectMemoryStartClosed || !privateWorkspaceView()) throw SecurityException("请从主人私人项目页明确选择记忆范围")
        if (!useProjectMemory) return requestOwnerAction("startDevelopmentTask", input)
        val id = UUID.randomUUID().toString(); val key = "owner.startDevelopmentTask"
        state = state.copy(moduleBusy = state.moduleBusy + key, moduleErrors = state.moduleErrors - key)
        native("startDevelopmentTask", JSONObject(input.toString()), id, ProjectStartMemoryRequest(retryOriginal)); return id
    }
    fun releaseProjectStartConsent(clientMessageId: String) { pendingProjectMemoryStarts.remove(clientMessageId)?.consent?.closeIfUnbound() }
    private fun startProjectWithReviewedMemory(input: JSONObject, request: ProjectStartMemoryRequest): JSONObject {
        if (projectMemoryStartClosed || !privateWorkspaceView()) throw SecurityException("原主人项目窗口已关闭，未启用项目记忆")
        val client = input.getString("clientMessageId"); val frozen = input.toString()
        var pending = pendingProjectMemoryStarts[client]
        if (pending == null) {
            if (request.retryOriginal) throw SecurityException("这次原提交的记忆同意已不在当前窗口；请只核对原提交，未从metadata恢复或回退全局")
            val scopes = app.nativeOwnerWorkflows.projectMemoryForOwner()
            val choices = scopes.choicesForOwner(); val choice = (0 until choices.length()).map { choices.getJSONObject(it) }.firstOrNull { it.optBoolean("available") && it.optString("checkoutId") == input.getString("checkoutId") && it.optString("projectId") == input.getString("projectId") } ?: throw SecurityException("此项目副本没有当前可用的原生记忆范围")
            val selected = scopes.selectForOwner(choice.getString("checkoutId"), choice.getString("projectId"), choice.getString("revision"))
            val consent = try { scopes.captureTaskConsentForOwner(selected) } finally { scopes.revokeForOwner(selected) }
            synchronized(pendingProjectMemoryStarts) {
                if (projectMemoryStartClosed || !privateWorkspaceView()) { consent.close(); throw SecurityException("原项目窗口已关闭，未新建记忆读取权限") }
                pending = PendingProjectMemoryStart(frozen, consent); pendingProjectMemoryStarts[client] = pending!!
            }
        }
        val original = pending ?: throw SecurityException("原生项目记忆同意没有确定回执")
        if (original.input != frozen) throw SecurityException("同一原提交不能更换内容或扩展冻结的项目记忆范围")
        return app.startProjectWithMemoryForOwner(input, original.consent)
    }
    fun openTeam(runId: String) { state = state.copy(teamRunId = runId,teamAssignmentFocus=null); feature("teams", "get", JSONObject().put("runId", runId)) }
    internal fun readWebTeamSnapshot(done: (JSONObject?, String?) -> Unit) {
        readWebOwnerFeatureSnapshot("teams", done)
    }
    internal fun runTeamPhoneWork(work: Runnable) { check(!closed); io.execute(work) }
    internal fun readWebOwnerFeatureSnapshot(feature: String, done: (JSONObject?, String?) -> Unit) {
        require(feature in setOf("teams", "media", "proxy"))
        check(webPrivateOwnerVisible()) { "请在原私人工作页面读取" }
        val window = webWindowTicket()
        io.execute {
            val result = runCatching {
                check(webPrivateOwnerVisible() && webWindowTicket() == window) { "原工作页面已关闭" }
                (app.features.module(feature) ?: error("原能力宿主尚未接入")).snapshot()
            }
            main.post {
                if (!webPrivateOwnerVisible() || webWindowTicket() != window) done(null, "原工作页面已变化，请重新读取")
                else result.fold({ done(it, null) }, { done(null, message(it)) })
            }
        }
    }
    fun closeTeam() { state = state.copy(teamRunId = null,teamAssignmentFocus=null) }
    fun startTeam(teamId: String, goal: String, rounds: Int): String {
        val payload = JSONObject().put("teamId", teamId).put("goal", goal.trim()).put("rounds", rounds)
        state.conversationId?.let { payload.put("conversationId", it) }
        val requestId = feature("teams", "start", payload)
        teamStartInputs[requestId] = goal.trim()
        return requestId
    }
    fun cancelMedia() {
        io.execute {
            try {
                val module = app.features.module("media") ?: throw IllegalStateException("媒体模块尚未接入")
                val operation = module.snapshot().optString("activeOperationId")
                main.post { if (!closed) { if (operation.isBlank()) state = state.copy(notice = "未找到当前正在运行的媒体请求，请核对任务记录") else feature("media", "cancel", JSONObject().put("operationId", operation)) } }
            } catch (error: Exception) { main.post { if (!closed) state = state.copy(error = message(error)) } }
        }
    }
    fun reconcileExternalTask(id: String, outcome: String, note: String) { native("reconcileExternalTask", JSONObject().put("taskId", id).put("outcome", outcome).put("note", note)) }
    fun updateTask(id: String, feedback: String) {
        native("updateTask", JSONObject().put("taskId", id).put("feedback", feedback))
    }
    fun resumeTask(task: UiTask, feedback: String) {
        if (isPhoneTask(task)) { controlOriginalPhone(task, "resume", feedback); return }
        val input = reviewedTaskInput(task).put("feedback", feedback).put("screenConsent", true).put("executionMode", task.executionMode)
        if (task.raw.optJSONObject("origin")?.optString("kind") == "group") input.put("taskRevision", task.raw.getLong("groupStateRevision"))
        native("resumeTask", input)
    }
    fun resolve(taskId: String, operationId: String, outcome: String) {
        native("resolveOperation", JSONObject().put("taskId", taskId).put("operationId", operationId).put("outcome", outcome))
    }
    fun approveCapability(taskId: String, approvalId: String, approved: Boolean) {
        val team=state.tasks.find { it.id==taskId && it.raw.optJSONObject("origin")?.optString("kind") in setOf("team_assignment","team_integration","team_review") };approveCapabilityInput(taskId, approvalId, approved, team?.let(::reviewedTaskInput)?:JSONObject().put("taskId", taskId))
    }
    fun approveCapability(task: UiTask, approvalId: String, approved: Boolean) {
        approveCapabilityInput(task.id, approvalId, approved, reviewedTaskInput(task))
    }
    private fun approveCapabilityInput(taskId: String, approvalId: String, approved: Boolean, input: JSONObject) {
        val key = "$taskId:$approvalId"
        if (key in state.approvalRequests) return
        state = state.copy(approvalRequests = state.approvalRequests + key)
        native("approveCapability", input.put("approvalId", approvalId).put("approved", approved))
    }
    fun display(command: String) {
        if (command in setOf("setup", "shizuku.start", "shizuku.stop", "adb.forget", "adb.cancel")) cancelPairingNotification()
        feature("display", command)
    }
    internal fun phoneSetup(command: String, input: JSONObject = JSONObject()) {
        if (command !in setOf("status", "pairing.begin", "pairing.cancel", "recovery.consent", "recovery.restore", "recovery.compensate")) return
        if (!privateWorkspaceView() || "phone_setup.$command" in state.moduleBusy) return
        if (command != "pairing.cancel" && !state.backgroundSetupOpen) return
        val key = "phone_setup.$command"
        val id = UUID.randomUUID().toString()
        val view = phoneSetupViewRevision.get()
        val captured = JSONObject(input.toString())
        state = state.copy(moduleBusy = state.moduleBusy + key, moduleErrors = state.moduleErrors - key)
        pending[id] = Pair(key, captured)
        // Use the existing owner I/O queue for this small setup surface. FeatureHost's four workers
        // could otherwise execute a queued cancellation before its earlier begin.
        io.execute {
            val result = runCatching {
                if (command != "pairing.cancel") check(privateWorkspaceView()) { "请回到主人私人工作区" }
                if (command == "pairing.begin") check(state.backgroundSetupOpen && view == phoneSetupViewRevision.get()) { "原通知输码窗口已关闭，未开启新输码" }
                val module = app.features.module("phone_setup") ?: error("原手机设置模块尚未挂载")
                module.handle(command, captured)
            }
            runCatching { app.features.sendState() }
            main.post {
                if (closed) return@post
                val event = JSONObject().put("requestId", id).put("feature", "phone_setup").put("command", command).put("ok", result.isSuccess)
                result.fold({ event.put("result", it) }, { event.put("message", message(it)) })
                featureResult(event)
            }
        }
    }
    internal fun beginPairingNotification(host: String, port: Int) {
        if (port !in 1..65535) return
        phoneSetup("pairing.begin", JSONObject().put("host", host).put("pairingPort", port))
    }
    internal fun cancelPairingNotification() {
        if (!privateWorkspaceView()) return
        if (state.feature("phone_setup").optJSONObject("pairing")?.optBoolean("active") != true
            && "phone_setup.pairing.begin" !in state.moduleBusy) return
        // Same native queue as begin: closing a pending view cannot leave a later begin behind its cancellation.
        phoneSetup("pairing.cancel")
    }
    internal fun phoneClipboardConsent(enabled: Boolean) {
        if (privateWorkspaceView() && state.backgroundSetupOpen && "display.clipboard.consent" !in state.moduleBusy)
            phoneClipboardCommand("clipboard.consent", JSONObject().put("enabled", enabled))
    }
    internal fun restorePhoneClipboard() {
        if (privateWorkspaceView() && state.backgroundSetupOpen && state.activeTaskId == null
            && !state.feature("display").optBoolean("taskOwned") && "display.clipboard.restore" !in state.moduleBusy)
            phoneClipboardCommand("clipboard.restore", JSONObject())
    }
    private fun phoneClipboardCommand(command: String, input: JSONObject) {
        val key = "display.$command"; val id = UUID.randomUUID().toString()
        val view = phoneSetupViewRevision.get(); val captured = JSONObject(input.toString())
        state = state.copy(moduleBusy = state.moduleBusy + key, moduleErrors = state.moduleErrors - key)
        pending[id] = Pair(key, captured)
        io.execute {
            val result = runCatching {
                check(privateWorkspaceView() && state.backgroundSetupOpen && view == phoneSetupViewRevision.get()) { "原手机设置窗口已关闭，未更改剪贴板设置" }
                val module = app.features.module("display") as? dev.pocketengine.phone.display.DisplayModule ?: error("原手机模块尚未挂载")
                // Like the existing audio owner path, the module's live owner fence handles this.
                // Generic FeatureHost display requests are deliberately blocked while a task owns the display.
                module.handle(command, captured)
            }
            runCatching { app.features.sendState() }
            main.post {
                if (closed) return@post
                val event = JSONObject().put("requestId", id).put("feature", "display").put("command", command).put("ok", result.isSuccess)
                result.fold({ event.put("result", it) }, { event.put("message", message(it)) }); featureResult(event)
            }
        }
    }
    internal fun loadPhoneOwnerApps() {
        if (!privateWorkspaceView() || !state.backgroundSetupOpen || phoneOwnerAppsLoading || phoneOwnerPolicyBusy) return
        val request = phoneOwnerAppsRequest.incrementAndGet()
        val view = phoneSetupViewRevision.get()
        phoneOwnerAppsLoading = true; phoneOwnerPolicyMessage = null
        io.execute {
            val actual = runCatching {
                check(privateWorkspaceView() && state.backgroundSetupOpen && view == phoneSetupViewRevision.get()) { "原手机设置窗口已关闭" }
                phoneOwnerAppPicker.list()
            }
            main.post {
                if (!privateWorkspaceView() || !state.backgroundSetupOpen || view != phoneSetupViewRevision.get() || request != phoneOwnerAppsRequest.get()) return@post
                phoneOwnerAppsLoading = false
                actual.fold({ listing -> phoneOwnerApps = listing.apps(); phoneOwnerAppsComplete = listing.complete() }, {
                    phoneOwnerApps = emptyList(); phoneOwnerAppsComplete = false; phoneOwnerPolicyMessage = phoneOwnerMessage(it)
                })
            }
        }
    }
    internal fun setPhoneAppAccess(selected: dev.pocketengine.phone.setup.OwnerPhoneApps.Selection,
                                    automation: Boolean, ownerShare: Boolean, expectedRevision: Long) {
        if (!privateWorkspaceView() || !state.backgroundSetupOpen || phoneOwnerPolicyBusy || selected !in phoneOwnerApps) return
        val view = phoneSetupViewRevision.get()
        phoneOwnerPolicyBusy = true; phoneOwnerPolicyMessage = null
        io.execute {
            val actual = runCatching {
                val module = app.features.module("display") as? dev.pocketengine.phone.display.DisplayModule ?: error("原手机模块尚未挂载")
                synchronized(module) {
                    check(privateWorkspaceView() && state.backgroundSetupOpen && view == phoneSetupViewRevision.get()) { "原手机设置窗口已关闭，未保存" }
                    phoneOwnerAppPicker.requireCurrent(selected)
                    module.handle("access.set", JSONObject().put("packageName", selected.packageName()).put("userId", selected.userId())
                        .put("automation", automation).put("ownerShare", ownerShare).put("expectedRevision", expectedRevision))
                }
            }
            runCatching { app.features.sendState() }
            main.post {
                if (!privateWorkspaceView() || !state.backgroundSetupOpen || view != phoneSetupViewRevision.get()) return@post
                phoneOwnerPolicyBusy = false
                actual.fold({ snapshot ->
                    val observedPolicy = snapshot.optJSONObject("appAccess")
                    val currentDisplay = JSONObject(state.feature("display").toString())
                    if (observedPolicy != null && observedPolicy.optLong("revision") >= (currentDisplay.optJSONObject("appAccess")?.optLong("revision") ?: 0)) {
                        currentDisplay.put("appAccess", JSONObject(observedPolicy.toString()))
                        state = state.copy(features = JSONObject(state.features.toString()).put("display", currentDisplay))
                    }
                    phoneOwnerPolicyMessage = "已保存此应用副本的限制；后续读取与动作按当前规则重新核对。"
                }, { phoneOwnerPolicyMessage = phoneOwnerMessage(it) })
            }
        }
    }
    private fun phoneOwnerMessage(error: Throwable): String = when (error.message) {
        "owner_app_selection_expired", "owner_app_installation_changed", "owner_app_uid_changed", "owner_app_installation_unavailable", "owner_app_signer_unavailable" -> "应用或资料身份已变化，或此次选择已过期。请重新读取并选择；本次未保存。"
        "owner_app_profiles_unavailable" -> "系统未提供可访问资料列表；未猜测主账号或其他资料。"
        "phone_policy_changed" -> "原应用规则已改变。请刷新并核对当前选项后再保存。"
        else -> message(error)
    }
    internal fun readPhonePracticeEvidence() {
        if (!privateWorkspaceView() || !state.backgroundSetupOpen || phonePracticeReading) return
        val view = phoneSetupViewRevision.get()
        phonePracticeReading = true; phonePracticeReadError = null
        io.execute {
            val result = runCatching { check(privateWorkspaceView() && view == phoneSetupViewRevision.get()); app.phonePracticeResult() }
            main.post {
                if (!privateWorkspaceView() || !state.backgroundSetupOpen || view != phoneSetupViewRevision.get()) return@post
                phonePracticeReading = false
                result.fold({ phonePracticeEvidence = it }, { phonePracticeReadError = message(it) })
            }
        }
    }
    internal val backgroundAudioOwnerVisible get() = privateWorkspaceView()
    @Volatile private var phoneDiagnosticEpoch = 0L
    @Volatile private var phoneDiagnosticReport: dev.pocketengine.phone.diagnostics.PhoneDiagnosticReport.Report? = null
    internal val phoneDiagnosticsOwnerVisible get() = privateWorkspaceView() && state.backgroundSetupOpen
    private fun clearPhoneDiagnostics() { phoneDiagnosticEpoch++; phoneDiagnosticReport = null }
    /** Original private native owner setup only; never a generic module/model command. */
    internal fun previewPhoneDiagnostics(done: (dev.pocketengine.phone.diagnostics.PhoneDiagnosticReport.Report?, String?) -> Unit) {
        if (!phoneDiagnosticsOwnerVisible) return
        val epoch = ++phoneDiagnosticEpoch
        phoneDiagnosticReport = null
        try { io.execute {
            if (!phoneDiagnosticsOwnerVisible || epoch != phoneDiagnosticEpoch) return@execute
            val result = runCatching {
                val display = app.features.module("display") as? dev.pocketengine.phone.display.DisplayModule
                dev.pocketengine.phone.diagnostics.AndroidPhoneDiagnostics.preview(app, display)
            }
            main.post {
                if (!phoneDiagnosticsOwnerVisible || epoch != phoneDiagnosticEpoch) return@post
                result.fold({ report -> phoneDiagnosticReport = report; done(report, null) }, { done(null, "诊断当前不可用，请刷新后重试；没有复制或分享任何内容。") })
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { if (phoneDiagnosticsOwnerVisible) done(null, "原设置页已关闭，请重新打开。") }
    }
    internal fun copyPhoneDiagnostics(report: dev.pocketengine.phone.diagnostics.PhoneDiagnosticReport.Report): String? {
        if (!phoneDiagnosticsOwnerVisible || phoneDiagnosticReport !== report) return "请在原手机设置页重新预览诊断。"
        return try {
            val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PocketWorld 手机诊断", report.text()))
            null
        } catch (_: Exception) { "复制未确认，请重新核对；没有自动重试。" }
    }
    internal fun sharePhoneDiagnostics(report: dev.pocketengine.phone.diagnostics.PhoneDiagnosticReport.Report): String? {
        if (!phoneDiagnosticsOwnerVisible || phoneDiagnosticReport !== report) return "请在原手机设置页重新预览诊断。"
        return try {
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report.text())
            activity.startActivity(Intent.createChooser(intent, "分享已预览的手机诊断"))
            null
        } catch (_: Exception) { "系统分享未确认，请自行核对；没有自动发送或重试。" }
    }
    /** Private native owner action, never a generic feature/model command. */
    internal fun setBackgroundAudioSilence(enabled: Boolean, done: (JSONObject?, String?) -> Unit) {
        if (!privateWorkspaceView()) return
        io.execute {
            if (!privateWorkspaceView()) return@execute
            val result = runCatching {
                val module = app.features.module("display") as? dev.pocketengine.phone.display.DisplayModule ?: error("原后台手机模块尚未挂载")
                module.setAudioPolicyForOwner(enabled).also { try { app.features.sendState() } catch (_: Exception) { } }
            }
            main.post {
                if (!privateWorkspaceView()) return@post
                result.fold({ done(it, null) }, { failure ->
                    val actual = runCatching { JSONObject().put("enabled",dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.silenceEnabled(app)).put("persisted",false) }.getOrNull()
                    done(actual, message(failure))
                })
            }
        }
    }
    /** Ledger -> short Module command, never Module monitor -> ledger. Explicit owner setting only. */
    internal fun restoreBackgroundAudio(done: (JSONObject?, String?) -> Unit) {
        if (!privateWorkspaceView() || !state.backgroundSetupOpen) return
        val view = phoneSetupViewRevision.get()
        fun requireOriginalView() {
            check(privateWorkspaceView() && state.backgroundSetupOpen && view == phoneSetupViewRevision.get()) { "原声音设置窗口已关闭或变化，请重新核对" }
        }
        io.execute {
            val result = runCatching {
                requireOriginalView()
                val module = app.features.module("display") as? dev.pocketengine.phone.display.DisplayModule ?: error("原后台手机模块尚未挂载")
                dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.withCommands({ command, args ->
                    requireOriginalView(); module.audioRecovery(command, args)
                }) {
                    dev.pocketengine.phone.setup.AndroidPhoneAudioRecovery.get(app).setAllOriginalForOwner()
                }.also { try { app.features.sendState() } catch (_: Exception) { } }
            }
            main.post { if (privateWorkspaceView() && state.backgroundSetupOpen && view == phoneSetupViewRevision.get()) result.fold({ done(it, null) }, { done(null, message(it)) }) }
        }
    }
    fun openBackgroundSetup() {
        if (!privateWorkspaceView()) return
        clearPhoneDiagnostics()
        if (!state.backgroundSetupOpen) phoneSetupViewRevision.incrementAndGet()
        state = state.copy(backgroundSetupOpen = true); refreshBackgroundSetup()
    }
    internal fun startOwnedPhonePractice(mode: String) {
        if (!privateWorkspaceView() || !state.backgroundSetupOpen || state.phoneSubmitting) return
        if (mode !in setOf("background", "main_screen")) return
        if (state.activeTaskId != null || state.feature("display").optBoolean("taskOwned")) { state = state.copy(error = "请先在原任务中停止或完成当前执行，再开始手机演练"); return }
        if (channelPhoneBlocked || channelPhoneRequest != null) { state = state.copy(error = "请先处理原渠道请求；演练不会替换它的目标或授权"); return }
        val connection = backgroundConnectionState(state.feature("display"), state.activeTaskId, state.moduleBusy)
        if (connection.busy || mode == "background" && (!connection.helperConnected || connection.requiresConnectionCheck)) {
            state = state.copy(notice = "请先完成并核对原连接，再明确开始演练"); return
        }
        val request = try { JSONObject().put("executionMode", mode).put("screenConsent", true)
            .put("executionScope", newTaskExecutionScope()).put("_ownerViewRevision", phoneSetupViewRevision.get()) }
        catch (failure: Exception) { state = state.copy(error = message(failure)); return }
        cancelPairingNotification()
        state = state.copy(phoneSubmitting = true)
        native("startOwnedPhonePractice", request)
    }
    fun startPhonePractice(packageName: String, field: String, mode: String) {
        if (!packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) || field.isBlank()) { state = state.copy(error = "请填写实际应用包名和一个可安全测试的普通输入框名称"); return }
        startPhone("这是主人明确批准的手机能力演练。仅打开包名 $packageName，读取当前窗口与控件，找到标签为“${field.take(120)}”的唯一普通非敏感输入框，输入“中文输入演练”，不点发送、保存、支付或任何提交。核对同一原生字段即时与延迟实际值，记录应用版本（可读时）、树覆盖、display/package归属、主屏干扰与结果；不唯一或不支持时交还主人。完成后保持现场供主人核对。", mode)
        if (state.phoneSubmitting) closeBackgroundSetup()
    }
    fun closeBackgroundSetup() {
        clearPhoneDiagnostics()
        cancelPairingNotification()
        phoneSetupViewRevision.incrementAndGet(); phoneOwnerAppsRequest.incrementAndGet()
        phoneOwnerApps = emptyList(); phoneOwnerAppsLoading = false; phoneOwnerPolicyBusy = false
        phoneOwnerPolicyMessage = null; phonePracticeReading = false
        state = state.copy(backgroundSetupOpen = false)
    }
    fun selectMainScreenCompatibility() {
        closeBackgroundSetup()
        state = state.copy(backgroundSetupOpen = false, requestedPhoneMode = "main_screen", requestedPhoneModeRevision = state.requestedPhoneModeRevision + 1,
            notice = "已明确选择主屏兼容模式。请确认系统权限，再手动开始原目标；不会自动执行。")
    }
    fun refreshBackgroundSetup() {
        io.execute { app.sendState() }
        native("getFeatures")
        phoneSetup("status")
        readPhonePracticeEvidence()
        if (state.activeTaskId == null && !state.feature("display").optBoolean("taskOwned") && state.feature("display").optJSONObject("adb") != null) display("adb.status")
    }
    fun openWirelessSettings() {
        wirelessSettingsLaunched = true
        try { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) {
            try { activity.startActivity(Intent(Settings.ACTION_SETTINGS)); state = state.copy(notice = "系统未提供开发者选项直达入口，请从系统设置中打开无线调试") }
            catch (_: android.content.ActivityNotFoundException) { wirelessSettingsLaunched = false; state = state.copy(error = "系统未提供可打开的设置入口，请手动打开开发者选项") }
        }
    }
    fun backgroundSetupResumed() {
        if (!wirelessSettingsLaunched) return
        wirelessSettingsLaunched = false
        if (state.backgroundSetupOpen && state.activeTaskId == null && !state.feature("display").optBoolean("taskOwned") && state.feature("display").optJSONObject("adb") != null) display("adb.discover")
    }
    fun pairWireless(host: String, port: Int, code: String) {
        if (!wirelessPairCodeValid(code) || port !in 1..65535) { state = state.copy(error = "请读取系统配对窗口当前的端口和六位配对码"); return }
        cancelPairingNotification()
        feature("display", "adb.pair", JSONObject().put("host", host).put("pairingPort", port).put("pairingCode", code))
    }
    fun connectWireless(host: String, port: Int, pairingPort: Int?) {
        if (port !in 1..65535 || port == pairingPort) { state = state.copy(error = "连接端口与配对端口不同，请读取无线调试主页"); return }
        cancelPairingNotification()
        feature("display", "adb.connect", JSONObject().put("host", host).put("connectPort", port).also { if (pairingPort != null) it.put("pairingPort", pairingPort) })
    }
    fun requestFeature(id: String, command: String, payload: JSONObject = JSONObject()): String {
        val captured = JSONObject(payload.toString())
        if (id == "ssh" && command in setOf("exec", "listFiles", "readFile", "writeFile", "inspectFile", "downloadFile", "uploadFile", "openPty")) {
            val selected = state.feature("ssh").optJSONArray("profiles").objects().firstOrNull { it.optString("id") == captured.optString("profileId") }
                ?: throw IllegalStateException("请重新选择当前SSH连接")
            if (!captured.has("_profileBinding")) captured.put("_profileBinding", selected.getString("bindingHash"))
        }
        return feature(id, command, captured)
    }
    /** Receives the existing FeatureHost's actual receipt, not dispatchOwnerAction's enqueue acknowledgement. */
    internal fun requestFeatureReceipt(id: String, command: String, payload: JSONObject, done: (JSONObject?, String?) -> Unit): String {
        check(webPrivateOwnerVisible()) { "请回到前台私人工作台操作" }
        check(webFeatureReceipts.size < 32) { "已有原操作仍在核对，请等待原回执" }
        val request = requestFeature(id, command, payload)
        webFeatureReceipts[request] = done
        return request
    }
    internal fun requestWebFeatureReceipt(id: String, command: String, payload: JSONObject, current: () -> Boolean, done: (JSONObject?, String?) -> Unit): String {
        check(webPrivateOwnerVisible() && current()) { "原审阅窗口已关闭" }
        check(webFeatureReceipts.size < 32) { "已有原操作仍在核对，请等待原回执" }
        val request = feature(id, command, JSONObject(payload.toString()), current)
        webFeatureReceipts[request] = done
        return request
    }
    fun openInspection(taskId: String) {
        phoneInlinePreview = null
        phoneInlineWindow = null
        inspectionEpoch++
        inspectionRefreshPending = false
        state = state.copy(inspectionTaskId = taskId, inspectionObservation = null, inspectionBusy = false, inspectionManualAllowed = false, inspectionError = null, inspectionMessage = null)
        observeInspection()
    }
    internal fun openInlinePhonePreview(task: UiTask, window: android.view.View) {
        if (!privateWorkspaceView() || state.activeTaskId != task.id || !isPhoneTask(task) || task.executionMode != "background"
            || task.raw.optString("actorId") != "owner" || task.raw.optString("spaceId") != "private" || !phoneOwnerWindowCurrent(window)) return
        try {
            val identity = dev.pocketengine.phone.observation.PhoneObservationPreview.expected(JSONObject().put("taskId", task.id)
                .put("expectedRunId", task.raw.opt("runId")).put("expectedEpoch", task.raw.opt("coordinatorEpoch")))
                ?: return
            closeInspection()
            phoneInlinePreview = identity
            phoneInlineWindow = window
            state = state.copy(inspectionTaskId = task.id, inspectionError = null, inspectionMessage = null)
            observeInspection()
        } catch (_: Exception) { state = state.copy(notice = "原任务身份尚未就绪，请回到原任务刷新后再查看。") }
    }
    private fun inlinePhonePreviewCurrent(identity: dev.pocketengine.phone.observation.PhoneObservationPreview.Identity): Boolean =
        phoneInlinePreview === identity && privateWorkspaceView() && state.inspectionTaskId == identity.taskId && state.activeTaskId == identity.taskId
            && state.tasks.any { identity.matchesTask(it.raw) && it.raw.optString("actorId") == "owner" && it.raw.optString("spaceId") == "private" }
            && phoneOwnerWindowCurrent(phoneInlineWindow)
    private fun phoneOwnerWindowCurrent(window: android.view.View?): Boolean = window != null && window.isAttachedToWindow && window.hasWindowFocus()
        && !activity.isFinishing && !activity.isDestroyed && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        && generateSequence(window.context) { (it as? android.content.ContextWrapper)?.baseContext?.takeUnless { base -> base === it } }.any { it === activity }
    internal fun expandInlinePhonePreview() {
        val identity = phoneInlinePreview ?: return
        if (!inlinePhonePreviewCurrent(identity)) { closeInspection(); return }
        // Expand the exact observation already shown. Opening the larger view performs no new read.
        phoneInlinePreview = null; phoneInlineWindow = null; inspectionEpoch++; inspectionRefreshPending = false
        state = state.copy(inspectionBusy = false)
    }
    fun closeInspection() {
        phoneInlinePreview = null
        phoneInlineWindow = null
        inspectionEpoch++
        inspectionRefreshPending = false
        state = state.copy(inspectionTaskId = null, inspectionObservation = null, inspectionBusy = false, inspectionManualAllowed = false)
    }
    fun observeInspection() {
        val taskId = state.inspectionTaskId ?: return
        if (state.inspectionBusy) return
        val inline = phoneInlinePreview
        if (inline != null && !inlinePhonePreviewCurrent(inline)) { closeInspection(); return }
        state = state.copy(inspectionBusy = true, inspectionError = null)
        native("observeTask", inline?.request() ?: JSONObject().put("taskId", taskId))
    }
    fun takeOverInspection() {
        val taskId = state.inspectionTaskId ?: return
        if (state.inspectionBusy) return
        state = state.copy(inspectionBusy = true, inspectionManualAllowed = false, inspectionError = null)
        native("takeoverTask", JSONObject().put("taskId", taskId))
    }
    fun ownerPhoneHandoff(action: String) {
        val taskId = state.inspectionTaskId ?: return
        if (state.inspectionBusy) return
        val handoff = state.inspectionObservation?.optJSONObject("ownerHandoff")
        state = state.copy(inspectionBusy = true, inspectionManualAllowed = false, inspectionError = null)
        native("ownerPhoneHandoff", JSONObject().put("taskId", taskId).put("handoffAction", action).put("handoffId", handoff?.optString("handoffId") ?: ""))
    }
    fun manualAction(tool: String, args: JSONObject = JSONObject()) {
        val taskId = state.inspectionTaskId ?: return
        val observation = state.inspectionObservation ?: return
        if (state.inspectionBusy || !state.inspectionManualAllowed) return
        val input = JSONObject(args.toString()).put("observationId", observation.optString("observationId")).put("displayId", observation.optInt("displayId", -1)).put("sessionId", observation.optString("sessionId"))
        state = state.copy(inspectionBusy = true, inspectionManualAllowed = false, inspectionError = null)
        native("manualTaskAction", JSONObject().put("taskId", taskId).put("tool", tool).put("observationId", observation.optString("observationId")).put("args", input))
    }
    fun ownerSecretInput(nodeId: String, text: String) {
        val taskId = state.inspectionTaskId ?: return
        val observation = state.inspectionObservation ?: return
        if (!state.inspectionManualAllowed || state.inspectionBusy || text.isEmpty()) return
        state = state.copy(inspectionBusy = true, inspectionManualAllowed = false, inspectionError = null)
        native("ownerSecretInput", JSONObject().put("taskId", taskId).put("nodeId", nodeId).put("observationId", observation.optString("observationId")).put("text", text))
    }
    fun loadLaunchApps() {
        io.execute {
            try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val manager = app.packageManager
                val apps = manager.queryIntentActivities(intent, 0).map { UiLaunchApp(it.activityInfo.packageName, it.loadLabel(manager).toString()) }.filter { it.packageName != app.packageName }.distinctBy { it.packageName }.sortedBy { it.label }
                main.post { if (!closed) state = state.copy(launchApps = apps) }
            } catch (error: Exception) { main.post { if (!closed) state = state.copy(inspectionError = message(error)) } }
        }
    }
    fun selectProject(id: String) {
        closeProjectPreview(); closeProjectLivePreview()
        projectGit.deactivate()
        projectPackages.cancel()
        invalidateFileRequests()
        state = state.copy(projectId = id, files = emptyList(), filePath = null, lastSavedVersion = null, fileReady = false, workspaceArtifactEditorOrigin = null, projectTaskReturn = state.projectTaskReturn?.takeIf { it.projectId == id })
        feature("workspace", "list", JSONObject().put("projectId", id))
    }
    fun createProject(name: String) { feature("workspace", "create", JSONObject().put("name", name.trim())) }
    fun openFile(path: String) {
        val project = state.projectId ?: return
        invalidateFileRequests()
        state = state.copy(filePath = path, fileContent = "", fileBaseline = "", fileVersion = 0, fileLoading = true, fileReady = false, fileError = null, fileHistory = emptyList(), revisionContent = null, revisionNumber = null, revisionLoading = false, lastSavedVersion = null)
        fileRequestId = feature("workspace", "read", JSONObject().put("projectId", project).put("path", path))
    }
    fun newFile(path: String) {
        if (state.projectId == null) return
        val target = path.trim()
        if (state.files.any { it.path == target }) { openFile(target); return }
        invalidateFileRequests()
        state = state.copy(filePath = target, fileContent = "", fileBaseline = "", fileVersion = 0, fileLoading = false, fileReady = true, fileError = null, fileHistory = emptyList(), revisionContent = null, revisionNumber = null, revisionLoading = false, lastSavedVersion = null)
    }
    fun editFile(content: String) { state = state.copy(fileContent = content, lastSavedVersion = null) }
    fun closeFile() {
        if (state.fileSaving) return
        val origin = state.workspaceArtifactEditorOrigin
        invalidateFileRequests(); state = state.copy(filePath = null, fileReady = false, fileLoading = false, workspaceArtifactEditorOrigin = null)
        if (origin != null && currentWorkspaceArtifact(origin.source, origin.fromTaskId)) openWorkspaceArtifact(origin.source, origin.fromTaskId)
    }
    fun retryFile() { state.filePath?.let(::openFile) }
    fun fileHistory() {
        val project = state.projectId ?: return
        val path = state.filePath ?: return
        historyRequestId = feature("workspace", "history", JSONObject().put("projectId", project).put("path", path))
    }
    fun previewRevision(version: Int) {
        val project = state.projectId ?: return
        val path = state.filePath ?: return
        state = state.copy(revisionNumber = version, revisionContent = null, revisionLoading = true)
        revisionRequestId = feature("workspace", "read", JSONObject().put("projectId", project).put("path", path).put("version", version))
    }
    fun useRevision() {
        val content = state.revisionContent ?: return
        state = state.copy(fileContent = content, lastSavedVersion = null, notice = "历史内容已放入编辑区，保存后才会创建新版本")
    }
    private fun invalidateFileRequests() { fileRequestId = null; historyRequestId = null; revisionRequestId = null; exactArtifactContinuation = null; artifactContinuationGeneration++ }
    fun saveFile() {
        val project = state.projectId ?: return
        val path = state.filePath ?: return
        if (state.fileSaving || state.fileLoading || !state.fileReady) return
        val historical = exactArtifactContinuation
        if (historical != null) {
            val origin = state.workspaceArtifactEditorOrigin ?: return
            if (!currentWorkspaceArtifact(origin.source, origin.fromTaskId) || historical.expected.projectId != project || historical.expected.path != path) { state = state.copy(fileError = "原历史成果或目标已变化，草稿保留；请返回原成果重新核对"); return }
            val edited = state.fileContent
            state = state.copy(fileSaving = true, fileError = null)
            io.execute {
                try {
                    val receipt = historical.saveText(edited)
                    main.post { if (!closed && exactArtifactContinuation === historical && state.projectId == project && state.filePath == path) {
                        exactArtifactContinuation = null
                        state = state.copy(fileBaseline = edited, fileVersion = receipt.version, fileSaving = false, fileReady = true, fileError = null, lastSavedVersion = receipt.version,
                            notice = "已从所选历史原件保存为新版本 v${receipt.version}；原固定成果保留。")
                        feature("workspace", "list", JSONObject().put("projectId", project))
                    } }
                    app.features.sendState()
                } catch (failure: Exception) { main.post { if (!closed && exactArtifactContinuation === historical && state.projectId == project && state.filePath == path) state = state.copy(fileSaving = false, fileError = "未取得完整保存回执，草稿保留。请读取当前版本核对，不自动重发。\n" + message(failure)) } }
            }
            return
        }
        state = state.copy(fileSaving = true, fileError = null)
        fileRequestId = feature("workspace", "write", JSONObject().put("projectId", project).put("path", path).put("content", state.fileContent).put("expectedVersion", state.fileVersion))
    }
    /** Native private settings only; no feature/model command and no implicit opt-in. */
    internal fun readAutomaticUserLearningPolicy(done: (JSONObject?, String?) -> Unit) {
        if (closed || !privateWorkspaceView()) return
        io.execute {
            val actual = runCatching { check(privateWorkspaceView()) { "仅本机私人工作区可读取自动原话设置" }; app.automaticUserLearningPolicy() }
            main.post { if (!closed && privateWorkspaceView()) actual.fold({ done(it, null) }, { done(null, message(it)) }) }
        }
    }
    internal fun saveAutomaticUserLearningPolicy(revision: Long, enabled: Boolean, maximum: Int, confirmedModelUse: Boolean, done: (JSONObject?, String?) -> Unit) {
        if (closed || !privateWorkspaceView()) return
        io.execute {
            val actual = runCatching {
                check(privateWorkspaceView()) { "仅本机私人工作区可修改自动原话设置" }
                require(revision >= 0 && maximum in 1..5)
                app.setAutomaticUserLearningPolicy(revision, enabled, maximum, confirmedModelUse)
            }
            // A native write may have committed before a later cancellation/reporting error.
            // Read back once instead of retrying, or presenting an unknown setting as off.
            val observed = actual.getOrNull() ?: runCatching { check(privateWorkspaceView()); app.automaticUserLearningPolicy() }.getOrNull()
            main.post { if (!closed && privateWorkspaceView()) done(observed, actual.exceptionOrNull()?.let(::message)) }
        }
    }
    fun saveAgentPreferences(enabled: Boolean, detail: String, pace: String, expectedRevision: Long) {
        if (state.agentPreferencesSaving) return
        state = state.copy(agentPreferencesSaving = true, agentPreferencesError = null)
        io.execute {
            try {
                val receipt = app.settings.saveAgentPreferences(enabled, detail, pace, expectedRevision)
                val actual = JSONObject(receipt.getJSONObject("current").toString())
                main.post { if (!closed) state = state.copy(agentPreferences = if (actual.optLong("revision", 0) >= state.agentPreferences.optLong("revision", 0)) actual else state.agentPreferences, agentPreferencesSaving = false, agentPreferencesError = null) }
                app.sendState()
            } catch (error: Exception) {
                main.post { if (!closed) state = state.copy(agentPreferencesSaving = false, agentPreferencesError = message(error)) }
            }
        }
    }
    fun saveLocalObservation(capture: Boolean, ocr: Boolean) {
        io.execute { try { app.settings.saveLocalObservation(capture, ocr); app.sendState() }
            catch (error: Exception) { main.post { if (!closed) state = state.copy(error = message(error)) } } }
    }
    internal fun saveApiConnection(adding: Boolean, id: String, revision: Long, name: String, input: JSONObject, completed: (Result<JSONObject>) -> Unit) {
        check(!state.modelSaving && !state.modelTesting && !state.chatSubmitting && !state.phoneSubmitting) { "请先等待原提交和模型设置回执" }
        val service = app.modelConnections ?: error("本机模型连接尚未准备")
        val captured = JSONObject(input.toString())
        state = state.copy(modelSaving = true, error = null)
        try { runModelSelectionWrite({
            if (adding) service.addAndSelectConnection(name, captured) else service.saveConnection(id, revision, name, captured)
        }) { result ->
            if (!closed) result.fold({ state = state.copy(modelSaving = false, modelSavedRevision = state.modelSavedRevision + 1, notice = if (adding) "新 API 连接已保存，后续任务将使用它" else "原 API 连接已保存") }, { state = state.copy(modelSaving = false, error = "保存结果待核对，请刷新原连接。" + message(it)) })
            completed(result)
        } } catch (error: Exception) { state = state.copy(modelSaving = false, error = message(error)); throw error }
    }
    internal fun testApiConnection(id: String, revision: Long, input: JSONObject, editorId: String, draftRevision: Long,
                                   originalOwnerCurrent: () -> Boolean, completed: (Result<JSONObject>) -> Unit) {
        check(!state.modelSaving && !state.modelTesting && !modelSelectionPending()) { "请先等待原模型设置回执" }
        val captured = JSONObject(input.toString())
        val identity = ModelConnectionTestIdentity.capture(id, revision, editorId, draftRevision, captured)
        val window = webWindowTicket()
        fun currentOriginal(): Boolean = runCatching {
            !closed && originalOwnerCurrent() && webPrivateOwnerVisible() && window == webWindowTicket() &&
                identity.matchesStoredRevision(app.settings.apiConnectionsSnapshot())
        }.getOrDefault(false)
        check(currentOriginal()) { "原连接或测试窗口已变化，请重新打开连接" }
        state = state.copy(modelTesting = true, modelTest = null, modelTestTaskId = null)
        try { io.execute {
            var enteredOriginalCall = false
            var testedConfig: ModelConfig? = null
            fun safeTestMessage(value: String): String {
                var safe = testedConfig?.redactSensitive(value) ?: value
                // Also cover owner input rejected before it becomes a valid native config.
                val session = captured.optJSONObject("awsSession")
                for (secret in listOf(captured.optString("apiKey"), session?.optString("accessKeyId"), session?.optString("secretAccessKey"), session?.optString("sessionToken")))
                    if (!secret.isNullOrEmpty()) safe = safe.replace(secret, "[已隐藏凭据]")
                return safe
            }
            val result = runCatching {
                try {
                    check(currentOriginal()) { "原测试窗口已关闭；没有发出连接测试" }
                    val preview = app.settings.previewApiConnection(id, revision, captured)
                    testedConfig = preview
                    check(currentOriginal()) { "原连接修订或窗口已变化；没有发出连接测试" }
                    enteredOriginalCall = true
                    val actual = app.testModelConnection(preview)
                    val reference = actual.optJSONObject("inferenceReference")
                    val feedback = if (reference == null) ModelConnectionTestIdentity.unavailableFeedback() else runCatching {
                        ModelConnectionTestIdentity.feedback(reference, app.tasks.inferenceUsage(reference.getString("taskId")))
                    }.getOrElse { ModelConnectionTestIdentity.unavailableFeedback() }
                    val outcome = if (reference == null || !feedback.optBoolean("available") || feedback.optBoolean("outcomeUnknown")) "unknown"
                        else if (actual.optBoolean("ok")) "completed" else "failed"
                    val safeMessage = safeTestMessage(actual.optString("message"))
                    JSONObject().put("ok", actual.optBoolean("ok") && outcome == "completed").put("message", safeMessage)
                        .put("visionVerified", false).put("toolsVerified", false)
                        .put("inferenceReference", reference ?: JSONObject.NULL).put("inferenceFeedback", feedback)
                        .put("connectionTest", identity.receipt(currentOriginal(), outcome, reference))
                } catch (failure: Exception) {
                    val safeMessage = safeTestMessage(message(failure))
                    JSONObject().put("ok", false).put("message", safeMessage).put("visionVerified", false).put("toolsVerified", false)
                        .put("inferenceFeedback", ModelConnectionTestIdentity.unavailableFeedback())
                        .put("connectionTest", identity.receipt(currentOriginal(), if (enteredOriginalCall) "unknown" else "not_dispatched", null))
                }
            }
            main.post {
                result.getOrNull()?.optJSONObject("connectionTest")?.let { it.put("presentationCurrent", it.optBoolean("presentationCurrent") && currentOriginal()) }
                if (!closed) state = state.copy(modelTesting = false, modelTest = null, modelTestTaskId = null)
                completed(result)
            }
        } } catch (error: Exception) { state = state.copy(modelTesting = false, modelTest = null, modelTestTaskId = null); throw error }
    }
    private fun feature(feature: String, command: String, payload: JSONObject = JSONObject(), originalOwnerCurrent: (() -> Boolean)? = null): String {
        val id = UUID.randomUUID().toString()
        val key = "$feature.$command"
        state = state.copy(moduleBusy = state.moduleBusy + key, moduleErrors = state.moduleErrors - key)
        val metadata = if (feature == "display") displayPendingMetadata(command, payload) else JSONObject(payload.toString())
        if (feature == "teams" && command == "probeMember") {
            val slot = state.feature("teams").optJSONArray("teams").objects().find { it.optString("id") == payload.optString("teamId") }?.optJSONArray("members").objects().find { it.optString("id") == payload.optString("memberId") }
            if (slot != null) metadata.put("_probedSlot", slot.toString())
        }
        if(feature=="assistant"&&command=="get")latestChatGet=id
        pending[id] = Pair(key, metadata)
        native("featureRequest", JSONObject().put("feature", feature).put("command", command).put("payload", payload).put("requestId", id), originalOwnerCurrent = originalOwnerCurrent)
        return id
    }
    internal var nativeEvaluationOpen by mutableStateOf(false)
        private set
    private var nativeEvaluationPage: dev.pocketengine.features.evaluation.NativeEvaluationHost.Page? = null
    private var nativeEvaluationEpoch = 0L
    fun openNativeEvaluation() {
        if (closed || !privateWorkspaceView()) return
        nativeEvaluationPage?.close()
        nativeEvaluationPage = app.nativeEvaluations().openPage()
        nativeEvaluationEpoch++; nativeEvaluationOpen = true
    }
    fun closeNativeEvaluation() { nativeEvaluationEpoch++; nativeEvaluationOpen = false; nativeEvaluationPage?.close(); nativeEvaluationPage = null }
    internal fun evaluationCommand(command: String, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        val page = nativeEvaluationPage ?: return
        val epoch = nativeEvaluationEpoch
        if (closed || !nativeEvaluationOpen || !privateWorkspaceView()) return
        try {
            (if (command == "cancel") controls else io).execute {
                val result = runCatching { page.command(command, JSONObject(input.toString())) }
                main.post {
                    if (!closed && nativeEvaluationOpen && nativeEvaluationPage === page && epoch == nativeEvaluationEpoch && privateWorkspaceView())
                        result.fold({ done(it, null) }, { done(null, message(it)) })
                }
            }
        } catch (failure: Exception) { done(null, message(failure)) }
    }
    internal var nativeInferenceHistoryOpen by mutableStateOf(false)
        private set
    internal val modelDiagnostics by lazy { ModelDiagnosticsUi(app.nativeDiagnostics(),io,main) { !closed && nativeInferenceHistoryOpen && privateWorkspaceView() } }
    fun openNativeInferenceHistory(){if(!closed&&privateWorkspaceView())nativeInferenceHistoryOpen=true}
    fun closeNativeInferenceHistory(){nativeInferenceHistoryOpen=false;modelDiagnostics.close()}
    internal fun readNativeInferenceHistory(cursor:String,done:(JSONObject?,String?)->Unit){
        if(closed||!privateWorkspaceView())return
        io.execute{val result=runCatching{app.tasks.nativeInferenceUsagePage(cursor,40)};main.post{if(!closed&&nativeInferenceHistoryOpen&&privateWorkspaceView())result.fold({done(it,null)},{done(null,message(it))})}}
    }
    internal var taskListNextCursor by mutableStateOf("")
        private set
    private var webTaskViewId: String? = null
    internal fun completeWebTaskOpen(id: String, requestNavigation: Boolean) {
        webTaskViewId = id
        closeConversationReader()
        if (requestNavigation) state = state.copy(requestedTaskId = id, requestedTaskRevision = state.requestedTaskRevision + 1)
    }
    internal fun closeWebTaskView() { webTaskViewId = null }
    /** Opening a bounded owner view is distinct from accepting a complete result review. */
    internal fun readTaskForOpen(id: String, current: () -> Boolean, complete: (UiTask?, String?) -> Unit) =
        readTaskForWeb(id, false, current, complete)
    /** Read the original detailed task before a Web review handle can be issued. */
    internal fun readTaskForReview(id: String, complete: (UiTask?, String?) -> Unit) =
        readTaskForWeb(id, true, { true }, complete)
    private fun readTaskForWeb(id: String, requireComplete: Boolean, current: () -> Boolean, complete: (UiTask?, String?) -> Unit) {
        if (closed) { complete(null, "原任务读取窗口已关闭"); return }
        if (id.isBlank()) { complete(null, "原任务身份缺失"); return }
        val requestId = UUID.randomUUID().toString()
        taskReviewReads[requestId] = complete
        fun finish(task: UiTask?, error: String?) {
            taskReviewReads.remove(requestId)?.invoke(task, error)
        }
        try {
            io.execute {
                try {
                    check(!closed) { "原任务读取窗口已关闭" }
                    val result = app.dispatchOwnerAction("getTask", JSONObject().put("taskId", id))
                    val task = result?.optJSONObject("task") ?: result?.takeIf { it.has("id") }
                        ?: throw IllegalStateException("未取得原任务的完整核对信息")
                    require(task.optString("id") == id) { "读取结果不属于原任务" }
                    if (requireComplete) {
                        check(task.optString("taskProjection") != "summary") { "原任务只返回了摘要，请重新读取核对信息" }
                        check(!task.optBoolean("taskCoreReadLimited")) { "当前任务内容较多，本次只读到摘要，未取得完整成果，不能确认结果。请返回查看原任务记录。" }
                    }
                    val detail = JSONObject(task.toString())
                    main.post {
                        if (closed) { finish(null, "原任务读取窗口已关闭"); return@post }
                        if (requestId !in taskReviewReads) return@post
                        if (!current()) { finish(null, "原任务读取窗口已关闭"); return@post }
                        val result = runCatching {
                            mergeTask(detail)
                            val selected = state.tasks.firstOrNull { it.id == id }
                                ?: throw IllegalStateException("原任务不在当前读取窗口，请重新打开")
                            if (requireComplete) {
                                check(selected.raw.optString("taskProjection") != "summary") { "原任务已更新，请重新读取完整核对信息" }
                                check(!selected.raw.optBoolean("taskCoreReadLimited")) { "当前任务内容较多，本次只读到摘要，未取得完整成果，不能确认结果。请返回查看原任务记录。" }
                            }
                            state = state.copy(taskActionErrors = state.taskActionErrors - id)
                            selected
                        }
                        result.fold({ finish(it, null) }, { finish(null, message(it)) })
                    }
                } catch (error: Exception) {
                    main.post { finish(null, if (closed) "原任务读取窗口已关闭" else message(error)) }
                }
            }
        } catch (error: Exception) {
            finish(null, if (closed) "原任务读取窗口已关闭" else message(error))
        }
    }
    private fun closeTaskReviewReads() {
        val callbacks = taskReviewReads.values.toList()
        taskReviewReads.clear()
        callbacks.forEach { callback -> runCatching { callback(null, "原任务读取窗口已关闭") } }
    }
    /** Original receipt query only; closing the panel cancels the read and never a task. */
    internal fun readTaskInferenceUsage(task: JSONObject, done: (dev.pocketengine.runtime.usage.TaskInferencePresentation.Snapshot?, String?) -> Unit): () -> Unit {
        val read = dev.pocketengine.core.RunControl()
        if (closed || !privateWorkspaceView()) { done(null, "请在原私人任务中核对用量"); return { read.cancel() } }
        val original = JSONObject(task.toString())
        val taskId = original.optString("id")
        try { io.execute {
            val result = runCatching {
                read.check()
                val usage = app.tasks.ownerTaskInferenceUsage(taskId, read)
                read.check()
                dev.pocketengine.runtime.usage.TaskInferencePresentation.fromNativeUsage(original, usage).also { read.check() }
            }
            main.post { if (!closed && privateWorkspaceView() && !read.isCancelled) result.fold({ done(it, null) }, { done(null, message(it)) }) }
        } } catch (failure: Exception) { done(null, message(failure)) }
        return { read.cancel() }
    }
    /** Alternate quote only. Original bounded native receipt read; no request, replay or setting write. */
    internal fun reestimateInference(taskId: String, callId: String, done: (JSONObject?, String?) -> Unit) {
        if (closed) return
        io.execute {
            try {
                val result = app.dispatchOwnerAction("reestimateInference", JSONObject().put("taskId", taskId).put("callId", callId))
                main.post { if (!closed) done(result, null) }
            } catch (error: Exception) { main.post { if (!closed) done(null, message(error)) } }
        }
    }
    internal var conversationSourceRevision by mutableStateOf(0L)
        private set
    internal fun answerOwnerQuestion(question: JSONObject, text: String, done: (JSONObject?, String?) -> Unit) {
        if (text.isBlank() || text.length > 4000) { done(null, "请填写不超过4000字的回答"); return }
        submitOwnerQuestion("answerOwnerQuestion", question, JSONObject().put("text", text), done)
    }
    internal fun answerOwnerQuestionChoice(question: JSONObject, optionId: String, done: (JSONObject?, String?) -> Unit) {
        if (!optionId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { done(null, "原选项不可用，请刷新原任务"); return }
        submitOwnerQuestion("answerOwnerQuestionChoice", question, JSONObject().put("optionId", optionId), done)
    }
    private fun submitOwnerQuestion(action: String, question: JSONObject, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        if (closed || !privateWorkspaceView()) { done(null, "请回到原私人任务核对问题"); return }
        val exact = JSONObject(question.toString())
        input.put("questionIdentity", exact)
        try { io.execute {
            val result = runCatching {
                check(!closed && privateWorkspaceView()) { "原回答窗口已关闭" }
                app.dispatchOwnerAction(action, input)
            }
            main.post { if (!closed && privateWorkspaceView()) {
                result.fold({ done(it, null) }, { done(null, "回答回执未确认，请核对原任务；没有自动重发。" ) })
                getTask(exact.optString("taskId"))
            } }
        } } catch (failure: Exception) { done(null, message(failure)) }
    }
    internal fun answerOwnerQuestionFromWeb(question: JSONObject, text: String) {
        if (closed || !privateWorkspaceView()) throw SecurityException("原私人工作窗口已关闭或切换")
        native("answerOwnerQuestion", ownerQuestionWebInput(question, text))
    }
    /** Source-bound original interrupted prefixes. Never the general raw audit route. */
    internal fun readConversationInterrupted(taskId: String, conversationId: String?, cursor: String, done: (JSONObject?, String?) -> Unit) {
        if (closed || !privateWorkspaceView() || conversationId.isNullOrBlank() || conversationId != state.conversationId) return
        val revision = conversationSourceRevision
        io.execute {
            val result = runCatching { app.readConversationInterrupted(taskId, conversationId, cursor) }
            main.post {
                if (!closed && privateWorkspaceView() && conversationId == state.conversationId && revision == conversationSourceRevision)
                    result.fold({ done(it, null) }, { done(null, "原片段来源已变化或暂不可核对；没有重发模型请求。") })
            }
        }
    }
    /** Bounded original audit reads only. No feature dispatch, resume, grant or retry is created. */
    internal fun readTaskAudit(taskId: String?, section: String, cursor: String, done: (JSONObject?, String?) -> Unit) {
        if (closed) return
        io.execute {
            try {
                val request = JSONObject().put("cursor", cursor).put("limit", if (taskId == null) 40 else 24)
                val action = if (taskId == null) "listTaskHistory" else "getTaskHistoryPage"
                if (taskId != null) request.put("taskId", taskId).put("section", section).put("maxBytes", 65536)
                val result = app.dispatchOwnerAction(action, request)
                main.post { if (!closed) done(result, null) }
            } catch (error: Exception) { main.post { if (!closed) done(null, message(error)) } }
        }
    }
    private fun native(action: String, input: JSONObject = JSONObject(), ownerRequestId: String? = null, projectMemoryRequest: ProjectStartMemoryRequest? = null, completed: ((JSONObject?, String?) -> Unit)? = null, originalOwnerCurrent: (() -> Boolean)? = null) {
        val viewerEpoch = inspectionEpoch
        val inlinePreview = phoneInlinePreview.takeIf { action == "observeTask" }
        val targetTask = input.optString("taskId")
        val executionPlanOwnerAction = originalOwnerCurrent != null && (action == "enterTaskPlanning" || action == "approveCapability" &&
            listOf("expectedScopeRevision", "planRevision", "planArtifactRef", "planHash").all(input::has))
        val projectContinuationOwnerAction = originalOwnerCurrent != null && action == "continueDevelopmentTask"
        val guardedOwnerAction = executionPlanOwnerAction || projectContinuationOwnerAction
        val taskAction = targetTask.takeIf { it.isNotBlank() && action in setOf("resumeTask", "continueDevelopmentTask", "stopTask", "approveCapability", "confirmResult", "resolveOperation", "updateTask", "reconcileExternalTask", "configureTaskExecutionScope", "enterTaskPlanning", "answerOwnerQuestion") }
        if (taskAction != null) state = state.copy(taskActionErrors = state.taskActionErrors - taskAction)
        val needsGroupOwner = action in setOf("resumeTask", "stopTask", "approveCapability", "confirmResult", "resolveOperation", "updateTask", "reconcileExternalTask") && state.tasks.any { it.id == targetTask && it.raw.optJSONObject("origin")?.optString("kind") == "group" }
        val groupWindow = if (needsGroupOwner && groupTaskId == targetTask) groupTaskWindow else null
        val reviewedInput = if (needsGroupOwner) JSONObject(input.toString()) else input
        val urgent = action == "automationPlan" && input.optString("command") in setOf("pause", "resume", "cancel") || action in setOf("stopRun", "pauseRun", "resumeRun", "stopTask", "takeoverTask") || action == "featureRequest" && input.optString("command") in setOf("cancel", "release", "stop", "adb.cancel", "termux.cancel", "project.cancel", "project.stopService", "cancelJob", "steerJob", "getSteerMessage", "qq.stop", "weixin.stop", "weixin.login.cancel") || action == "featureRequest" && input.optString("feature") == "teams" && input.optString("command") in setOf("pause", "resume", "feedback", "continueMessages", "sendMessage", "answerAssignment")
        (if (urgent) controls else io).execute {
            var createdPhoneTaskId: String? = null
            try {
                if (originalOwnerCurrent != null && action != "featureRequest") check(originalOwnerCurrent()) { "原审阅窗口已关闭，未执行排队操作" }
                if (inlinePreview != null) check(viewerEpoch == inspectionEpoch && inlinePhonePreviewCurrent(inlinePreview)) { "原预览窗口或任务已变化，未读取替代画面" }
                if (action == "startOwnedPhonePractice") check(privateWorkspaceView() && state.backgroundSetupOpen
                    && input.optLong("_ownerViewRevision", -1) == phoneSetupViewRevision.get()) { "原演练确认窗口已关闭，未创建任务" }
                if (action == "featureRequest" && input.optString("feature") == "phone_setup" && input.optString("command") == "pairing.begin")
                    check(privateWorkspaceView() && state.backgroundSetupOpen && input.optJSONObject("payload")?.optLong("_ownerViewRevision", -1) == phoneSetupViewRevision.get()) { "原通知输码窗口已关闭，未开启新输码" }
                val result = if (action == "startRun") app.startOriginalPhoneRun(input) { createdPhoneTaskId = it }
                    else if (projectMemoryRequest != null) startProjectWithReviewedMemory(input, projectMemoryRequest)
                    else if (needsGroupOwner) app.groupOwnerWorkflows.call(groupWindow ?: throw SecurityException("请在当前原群任务详情重新建立本机审核窗口")) { app.dispatchOwnerAction(action, reviewedInput) }
                    else if (originalOwnerCurrent != null && action == "featureRequest") {
                        app.features.request(input, java.util.function.BooleanSupplier { originalOwnerCurrent() })
                        null
                    } else if (projectContinuationOwnerAction) {
                        // The original Assistant slot owns admission before Engine/SQL locks.
                        // Never enter that continuation while holding the generic app monitor.
                        val current = checkNotNull(originalOwnerCurrent)
                        check(current()) { "原项目审阅窗口已关闭，未续接排队任务" }
                        app.dispatchReviewedOwnerAction(action, input, java.util.function.BooleanSupplier { current() })
                    } else if (originalOwnerCurrent != null) synchronized(app) {
                        check(originalOwnerCurrent()) { "原审阅窗口已关闭，未执行排队操作" }
                        if (executionPlanOwnerAction) {
                            app.dispatchReviewedOwnerAction(action, input, java.util.function.BooleanSupplier { originalOwnerCurrent() })
                        } else app.dispatchOwnerAction(action, input)
                    } else app.dispatchOwnerAction(action, input)
                if (action == "answerOwnerQuestion" && taskAction != null) requireOwnerQuestionAccepted(result)
                main.post {
                    if (closed) { completed?.invoke(result, null); return@post }
                    if (ownerRequestId != null) {
                        val key = "owner." + action
                        val metadata = JSONObject().put("_requestId", ownerRequestId)
                        listOf("taskId", "messageId", "commitmentId", "claimId", "outcome").forEach { if (input.has(it)) metadata.put(it, input.opt(it)) }
                        state = state.copy(moduleBusy = state.moduleBusy - key, moduleResults = state.moduleResults + (key to (result ?: JSONObject())), moduleResultInputs = state.moduleResultInputs + (key to metadata))
                        if (action in setOf("openDevelopmentProject", "createDevelopmentProject") && developmentOpenRequest == ownerRequestId && result != null && result.has("projectId") && result.has("id")) { developmentCheckoutId = result.getString("id"); projectWorkbench.selectCheckout(result.getString("projectId"), result.getString("id"), replace = action == "createDevelopmentProject"); developmentOpenRequest = null; selectProject(result.getString("projectId")); if (action == "createDevelopmentProject") developmentNavigationRevision++; native("getFeatures") }
                        if (action == "setFollowUpPolicy") { native("rescheduleAutomation"); state = state.copy(notice = "跟进设置已保存") }
                        if (action in setOf("saveTaskMemory", "resaveTaskMemory", "saveMessageMemory", "resaveMessageMemory")) { native("getFeatures"); state = state.copy(notice = "已按真实来源保存记忆") }
                    }
                    if (action in setOf("observeTask", "takeoverTask", "ownerPhoneHandoff", "manualTaskAction", "ownerSecretInput") && viewerEpoch == inspectionEpoch && input.optString("taskId") == state.inspectionTaskId) {
                        if (inlinePreview != null && !inlinePhonePreviewCurrent(inlinePreview)) closeInspection() else receiveInspection(action, result)
                    }
                    if (action == "startRun") {
                        state = state.copy(phoneSubmitting = false)
                        if (!result?.optString("taskId").isNullOrBlank()) {
                            if (input.has("channelRequestId") && channelPhoneRequest?.optString("requestId") == input.optString("channelRequestId")) channelPhoneAccepted = true
                            phoneDraftAcceptedText=input.optString("goal");phoneDraftAcceptedRevision++
                            state = state.copy(acceptedInputRevision = state.acceptedInputRevision + 1, acceptedInput = input.optString("goal"))
                        }
                    }
                    if (action == "answerOwnerQuestion" && taskAction != null) getTask(taskAction)
                    if (action == "startOwnedPhonePractice") {
                        state = state.copy(phoneSubmitting = false, notice = "演练已进入原手机任务。实际结果与停止入口仍在原任务中。")
                        closeBackgroundSetup()
                    }
                    if (action == "configureTaskExecutionScope" && result != null) { mergeTask(result); state = state.copy(notice = "原范围已更新，累计用量保留；没有自动继续或重放动作") }
                    if (action == "enterTaskPlanning") { getTask(input.optString("taskId")); app.sendState() }
                    if (projectContinuationOwnerAction) { getTask(targetTask); refreshCurrentConversationTurn(); app.sendState() }
                    if (action == "getTask" && result != null) {
                        val task = result.optJSONObject("task") ?: if (result.has("id")) result else null
                        task?.let { mergeTask(it) }
                    }
                    if (action in setOf("confirmResult", "resumeTask", "updateTask", "resolveOperation")) app.sendState()
                    if (action == "resolveOperation" && input.optString("taskId") == state.inspectionTaskId) observeInspection()
                    if (action == "reconcileExternalTask") { getTask(input.optString("taskId")); app.sendState(); state = state.copy(notice = "已记录你核对的结果，未重新执行外部操作") }
                    if (action == "approveCapability") { if (!executionPlanOwnerAction || originalOwnerCurrent?.invoke() == true) state = state.copy(notice = capabilityApprovalNotice(result, input.optBoolean("approved"))); getTask(input.optString("taskId")); app.sendState() }
                    if (action in setOf("resumeTask", "updateTask")) state = state.copy(savedFeedbackRevision = state.savedFeedbackRevision + 1, savedFeedbackTaskId = input.optString("taskId"), savedFeedback = input.optString("feedback"), notice = if (action == "updateTask" && app.activeId() == input.optString("taskId")) "补充已保存，正在结束本次执行；结束后可继续原任务" else if (action == "updateTask") "补充已保存在原任务中" else null)
                    if (action == "startRun" && input.optString("executionMode") == "main_screen" && result != null) moveOriginalPhoneToBackground(result)
                    if (action in setOf("startOwnedPhonePractice", "resumeTask", "resumeRun")) {
                        val taskId = result?.optString("taskId")?.takeIf { it.isNotBlank() } ?: app.activeId()
                        val mode = if (action == "resumeRun") state.tasks.find { it.id == taskId }?.executionMode else input.optString("executionMode")
                        if (mode == "main_screen" && taskId != null) main.postDelayed({
                            if (!closed && !activity.isFinishing && !activity.isDestroyed && app.activeId() == taskId) activity.moveTaskToBack(true)
                        }, 350)
                    }
                    completed?.invoke(result, null)
                }
            } catch (error: Exception) {
                main.post {
                    if (!closed && guardedOwnerAction && originalOwnerCurrent?.invoke() != true && ownerRequestId != null) {
                        state = state.copy(moduleBusy = state.moduleBusy - ("owner." + action))
                    }
                    if (!closed && (!guardedOwnerAction || originalOwnerCurrent?.invoke() == true)) {
                        if (ownerRequestId != null) {
                            val key = "owner." + action
                            state = state.copy(moduleBusy = state.moduleBusy - key, moduleErrors = state.moduleErrors + (key to message(error)))
                            val metadata = JSONObject().put("_requestId", ownerRequestId).put("_failed", true)
                            listOf("taskId", "messageId", "commitmentId", "claimId", "outcome").forEach { if (input.has(it)) metadata.put(it, input.opt(it)) }
                            state = state.copy(moduleResults = state.moduleResults - key, moduleResultInputs = state.moduleResultInputs + (key to metadata))
                        }
                        if (action == "approveCapability") state = state.copy(approvalRequests = state.approvalRequests - (input.optString("taskId") + ":" + input.optString("approvalId")))
                        if (action in setOf("observeTask", "takeoverTask", "ownerPhoneHandoff", "manualTaskAction", "ownerSecretInput") && viewerEpoch == inspectionEpoch && input.optString("taskId") == state.inspectionTaskId) state = state.copy(inspectionBusy = false, inspectionManualAllowed = false, inspectionError = message(error))
                        val webCancelFailure = action == "featureRequest" &&
                            webChatCancelCallbacks.containsKey(input.optString("requestId"))
                        if (action == "featureRequest") {
                            featureResult(JSONObject().put("requestId", input.optString("requestId")).put("feature", input.optString("feature")).put("command", input.optString("command")).put("ok", false).put("message", message(error)))
                        }
                        if (!webCancelFailure) state = state.copy(error = message(error), taskActionErrors = if (taskAction != null) state.taskActionErrors + (taskAction to message(error)) else state.taskActionErrors, phoneSubmitting = if (action in setOf("startRun", "startOwnedPhonePractice")) false else state.phoneSubmitting)
                        if (action == "featureRequest" && input.optString("feature") == "assistant" && input.optString("command") == "send") refreshCurrentConversationTurn()
                    }
                    completed?.invoke(createdPhoneTaskId?.let { JSONObject().put("taskId", it) }, message(error))
                }
            }
        }
    }
    /** Order original native readbacks only; this never schedules or authorizes an occurrence. */
    private fun mergeAutomationReadback(incoming: JSONObject): JSONObject {
        val current = JSONObject(state.feature("automation").toString())
        val generation = incoming.optString("observationGeneration")
        val rawSequence = incoming.opt("observationSequence")
        if (!generation.matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) || rawSequence !is Long && rawSequence !is Int || incoming.optLong("observationSequence") < 1) return current
        if (current.has("observationGeneration") && (current.optString("observationGeneration") != generation || current.optLong("observationSequence") > incoming.optLong("observationSequence"))) return current
        for (name in incoming.keys()) current.put(name, incoming.get(name))
        return current
    }
    private fun receive(event: JSONObject) {
        when (event.optString("type")) {
            "owner_execution_policy" -> { event.optJSONObject("policy")?.let { ownerExecutionPolicy = JSONObject(it.toString()) }; if (privateWorkspaceView()) readOwnerExecutionPolicy() }
            "device_workspace_changed" -> deviceWorkspaceRevision++
            "model_stream" -> state = state.copy(modelDrafts = ModelStreamDrafts.accept(state.modelDrafts, event, state.tasks))
            "state" -> {
                val payload = event.optJSONObject("payload") ?: return
                val config = payload.optJSONObject("config") ?: JSONObject()
                payload.optJSONObject("ownerExecutionPolicy")?.let { ownerExecutionPolicy = JSONObject(it.toString()) }
                payload.optJSONObject("taskScreenshotPolicy")?.let { taskScreenshotPolicy = JSONObject(it.toString()) }
                val preferences = payload.optJSONObject("agentPreferences")
                if (preferences != null && preferences.opt("enabled") is Boolean && preferences.optLong("revision", 0) > 0 && preferences.optLong("revision") >= state.agentPreferences.optLong("revision", 0))
                    state = state.copy(agentPreferences = JSONObject(preferences.toString()))
                taskListNextCursor = payload.optString("tasksNextCursor")
                val incoming = nativeTaskStateRows(payload)
                val merged = incoming.map { row -> mergeTaskSummary(row, state.tasks.find { it.id == row.id }) }
                val activePhones = payload.optJSONArray("activePhoneTaskIds")?.let { ids -> (0 until ids.length()).mapNotNull { index -> (ids.opt(index) as? String)?.takeIf(String::isNotBlank) }.distinct() } ?: emptyList()
                val retained = state.tasks.filter { old -> old.id !in incoming.map { it.id } && (old.id in setOf(state.activeTaskId, state.requestedTaskId, webTaskViewId) || old.id in activePhones || old.raw.optString("executionRole") == "owner_work" && (old.status !in setOf("completed", "failed", "cancelled", "interrupted") || old.raw.optJSONObject("controls")?.optBoolean("canContinue") == true)) }

                state = state.copy(modelMaxSteps = config.optInt("maxSteps", state.modelMaxSteps), modelName = config.optString("model"), baseUrl = config.optString("baseUrl"), hasApiKey = config.optBoolean("hasApiKey"), screenshots = config.optBoolean("sendScreenshots", true), localCaptureAllowed = config.optBoolean("localCaptureAllowed", false), localOcrAllowed = config.optBoolean("localOcrAllowed", false), supportsImageInput = config.optBoolean("supportsImageInput", false), modelProvider = config.optString("provider", "openai_chat"), modelRoute = config.optString("modelRoute", "api_key"), accountModelAvailable = config.optBoolean("accountModelAvailable", false), modelCatalogEnabled = config.optJSONObject("capabilityBinding") != null, maxOutputTokens = config.optInt("maxOutputTokens", 4096), cleartextConsent = config.optString("cleartextConsent"), accessibilityEnabled = payload.optBoolean("accessibilityEnabled"), notificationsEnabled = payload.optBoolean("notificationsEnabled"), phoneReadiness = PhoneReadinessState.parse(payload.optJSONObject("phoneReadiness")), activeTaskId = payload.optString("activeTaskId").takeUnless { it.isBlank() || it == "null" }, activePhoneTaskIds = activePhones, maximumActiveBackgroundPhoneRuns = payload.optInt("maximumActiveBackgroundPhoneRuns").coerceAtLeast(0), foregroundTaskId = payload.optString("foregroundTaskId").takeUnless { it.isBlank() || it == "null" }, tasks = merged + retained, modelDrafts = ModelStreamDrafts.retain(state.modelDrafts, merged + retained))
            }
            "task" -> event.optJSONObject("task")?.let { original -> mergeTask(original); taskAnnouncements.observe(original) }
            "features_state" -> {
                val features = event.optJSONObject("features") ?: return
                features.optJSONObject("automation")?.let { features.put("automation", mergeAutomationReadback(it)) }
                features.optJSONObject("realtime")?.let { features.put("realtime", mergeRealtimeSnapshot(state.feature("realtime"), it)) }
                val assistant = try { app.features.module("assistant")?.snapshot() } catch (_: Exception) { null }
                    ?: features.optJSONObject("assistant") ?: JSONObject()
                features.put("assistant", assistant)
                state = state.copy(features = features, conversations = parseConversations(assistant.optJSONArray("conversations")), projects = features.optJSONObject("workspace")?.optJSONArray("projects").objects().map { UiProject(it.optString("id"), it.optString("name")) })
                refreshCurrentConversationTurn(assistant)
                features.optJSONObject("teams")?.optJSONArray("runs").objects().forEach(::mergeTeamRun)
                applyCompatibilityChat()
            }
            "feature_event" -> {
                val payload = event.optJSONObject("payload") ?: return
                if (event.optString("feature") in setOf("memory", "assistant", "attachments")) ++conversationSourceRevision
                if (event.optString("feature") == "notifications") ++notificationPreviewsSourceRevision
                if (event.optString("feature") == "memory" && payload.optString("event") == "memory_changed") { invalidateTaskMemoryReadings(); invalidateCognitionReadings(); ownerArchives.invalidateExperienceReadings() }
                if (event.optString("feature") in setOf("assistant", "attachments")) { ownerArchives.invalidateExperienceReadings(); invalidateCognitionReadings() }
                if (event.optString("feature") == "assistant") {
                    val id = payload.optString("conversationId")
                    if (id.isNotBlank()) {
                        val phase=payload.optString("phase")
                        if(phase in setOf("accepted","queued","unconfirmed","consumed"))receiveChatAcknowledgement(payload,payload.optString("clientMessageId").takeIf{it.isNotBlank()})
                        // Events only request a fresh read: a late idle event may belong to a
                        // retained run which already reacquired its own worker slot.
                        refreshCurrentConversationTurn()
                        if(phase in setOf("completed","failed","cancelled","idle"))applyCompatibilityChat()
                        if(state.conversationId==id)feature("assistant", "get", JSONObject().put("conversationId", id))
                    }
                }
                if (event.optString("feature") in setOf("display", "phone_setup") || event.optString("feature") == "channels" && payload.optString("type") in setOf("qq_gateway_state", "weixin_state", "channel_background_state")) native("getFeatures")
                if (event.optString("feature") == "realtime") payload.optJSONObject("snapshot")?.let(::receiveRealtimeSnapshot)
                if (event.optString("feature") == "automation" && payload.optString("event") == "automation_changed") {
                    payload.optJSONObject("snapshot")?.let { snapshot ->
                        state = state.copy(features = JSONObject(state.features.toString()).put("automation", mergeAutomationReadback(snapshot)))
                    }
                    if (state.moduleResults.containsKey("automation.receipts") && "automation.receipts" !in state.moduleBusy) feature("automation", "receipts")
                }
                if (event.optString("feature") == "automation" && payload.optString("event") == "commitments_changed") {
                    val automation = JSONObject(state.feature("automation").toString()).put("nativePlanChange", state.feature("automation").optLong("nativePlanChange") + 1)
                    state = state.copy(features = JSONObject(state.features.toString()).put("automation", automation))
                }
                if (event.optString("feature") == "automation" && payload.optString("event") == "commitments_changed" && state.moduleResults.containsKey("automation.listCommitments")) {
                    feature("automation", "listCommitments")
                    feature("automation", "dueCommitments", JSONObject().put("limit", 100))
                }
                if (event.optString("feature") == "automation" && payload.optString("event") == "commitments_changed" && state.moduleResults.containsKey("automation.listPlans")) {
                    feature("automation", "listPlans"); feature("automation", "getProactivityState")
                }
                if (event.optString("feature") == "teams") payload.optJSONObject("run")?.let(::mergeTeamRun)
            }
            "feature_result" -> featureResult(event)
            "media_state" -> state = state.copy(listening = event.optBoolean("listening"), speaking = event.optBoolean("speaking"), speechAvailable = event.optBoolean("recognitionAvailable"),systemSpeech=event.optJSONObject("systemSpeech")?:JSONObject())
            "speech_result" -> if (event.optBoolean("isFinal")) suggestInput(event.optString("text"))
            "media_error" -> state = state.copy(error = event.optString("message", "语音服务暂不可用"))
            "automation_goal" -> suggestInput(event.optString("goal"), true)
            "owner_takeover" -> if (event.optString("taskId") == state.inspectionTaskId) {
                state = state.copy(inspectionManualAllowed = false, inspectionMessage = event.optString("message"))
                if (phoneInlinePreview == null) { if (!state.inspectionBusy) observeInspection() else inspectionRefreshPending = true }
            }
            "capability_approval" -> getTask(event.optString("taskId"))
            "error" -> state = state.copy(error = event.optString("message", "操作未完成"))
        }
    }
    private fun featureResult(event: JSONObject) {
        val requestId = event.optString("requestId")
        val meta = pending[requestId] ?: return
        val feature = event.optString("feature")
        val command = event.optString("command")
        val key = "$feature.$command"
        if (meta.first != key) return
        pending.remove(requestId)
        state = state.copy(moduleBusy = if (pending.values.any { it.first == key }) state.moduleBusy else state.moduleBusy - key)
        if (feature == "workspace" && command in setOf("read", "write", "history") && requestId !in setOf(fileRequestId, historyRequestId, revisionRequestId)) return
        if (feature == "workspace" && (command == "listArtifacts" && requestId != workspaceArtifactsRequest || command == "readArtifact" && (requestId != workspaceArtifactReadRequest || state.workspaceArtifactView == null) || command == "diffArtifact" && (requestId != workspaceArtifactDiffRequest || state.workspaceArtifactView == null))) return
        val result = event.optJSONObject("result") ?: JSONObject()
        val metadata = JSONObject().put("_requestId", requestId)
        for (name in listOf("checkoutId", "expectedHash", "termux", "sessionId", "voiceTurnId", "turnId", "revision", "reviewId", "contentHash", "commitId", "taskId", "messageId", "profileId", "requestId", "jobId", "approvalRequestId", "decision", "projectId", "path", "artifactId", "attachmentId", "expectedSha256", "_profileBinding", "id", "offset", "length", "version", "query", "terminalId", "hostTaskId", "runId", "cursor", "cols", "rows", "expectedVersion", "expectedInstallationId", "targetInstallationId", "memberId", "teamId", "conversationId", "expectedRevision", "expectedGeneration", "expectedMemberGeneration", "assignmentId", "questionId", "answerId", "quoteMessageId", "recipientMemberId")) if (meta.second.has(name)) metadata.put(name, meta.second.get(name))
        var failure = if (!event.optBoolean("ok")) event.optString("message", "操作未完成") else null
        if (feature == "assistant" && command == "cancel") {
            val done = webChatCancelCallbacks.remove(requestId)
            if (done != null) {
                webChatCancelBindings.remove(requestId)
            }
            if (done != null) {
                val receipt = originalChatCancelReceipt(event.opt("ok") == true && failure == null, result)
                if (receipt != null) done(receipt, null)
                else done(null, "未确认原回复收到停止请求，请核对原任务状态；没有自动重试。")
                return // A late Web receipt must not write global errors/results for another conversation.
            }
        }
        if (failure == null && (feature == "assistant" && command in setOf("editMessage", "deleteMessage", "deleteConversation", "delete") || feature == "attachments" && command == "delete")) { ++conversationSourceRevision; ownerArchives.invalidateExperienceReadings(); invalidateCognitionReadings() }
        if (feature == "teams") {
            if (event.opt("ok") !is Boolean) failure = "团队操作未返回有效确认"
            if (failure == null && command in setOf("get", "pause", "resume", "feedback", "cancel", "continueMessages", "reviseAssignment", "continueOutcome", "sendMessage") && meta.second.optString("runId") != result.optString("id")) failure = "团队回执与原任务不匹配，请刷新后核对"
            if (failure == null && command == "answerAssignment" && (meta.second.optString("questionId") != result.optString("questionId") || meta.second.optString("answerId") != result.optString("answerId"))) failure = "回答回执不是这条原问题，请刷新后核对"
            if (failure == null && command == "savePlan" && meta.second.optString("teamId") != result.optString("id")) failure = "分工回执与原团队不匹配，请刷新后核对"
            val receipt = JSONObject().put("requestId", requestId).put("command", command).put("ok", failure == null).put("sequence", ++teamReceiptSequence)
            for (subject in listOf("runId", "teamId", "memberId")) if (meta.second.has(subject)) receipt.put(subject, meta.second.get(subject))
            if (failure != null) receipt.put("message", failure)
            state = state.copy(teamOwnerReceipts = (state.teamOwnerReceipts + (requestId to receipt)).entries.toList().takeLast(32).associate { it.toPair() }, moduleResultInputs = state.moduleResultInputs + (key to metadata))
        }
        webFeatureReceipts.remove(requestId)?.invoke(if (failure == null) JSONObject(result.toString()) else null, failure)
        if (failure != null) {
            if (feature == "phone_setup" || feature == "display" && command in setOf("clipboard.consent", "clipboard.restore")) native("getFeatures")
            metadata.put("_failed", true)
            state = state.copy(moduleResults = state.moduleResults - key, moduleResultInputs = state.moduleResultInputs + (key to metadata))
            if (feature == "channels" && command in setOf("qq.start", "qq.stop")) state = state.copy(moduleResultInputs = state.moduleResultInputs + (key to metadata))
            if (feature == "teams" && command == "start") teamStartInputs.remove(requestId)
            if(feature=="assistant"&&command=="send") {
                val client=meta.second.optString("clientMessageId")
                if(client.isNotBlank()){if(submittingChatClient==client)submittingChatClient=null;state=state.copy(chatSubmitting=submittingChatClient!=null,chatReceipts=state.chatReceipts+(client to JSONObject().put("clientMessageId",client).put("status","unconfirmed")))}
            }
            val message = failure
            state = state.copy(moduleErrors = state.moduleErrors + (key to message))
            if (feature == "workspace" && command in setOf("readArtifact", "diffArtifact", "listArtifacts")) {
                val view = state.workspaceArtifactView
                if (view != null && command == "readArtifact") state = state.copy(workspaceArtifactView = view.copy(loading = false, error = message))
                if (view != null && command == "diffArtifact") state = state.copy(workspaceArtifactView = view.copy(diffLoading = false, diffError = message))
                return
            }
            state = state.copy(error = if (feature == "workspace" && command in setOf("read", "write")) state.error else message, chatBusy = if (feature == "assistant" && command == "send" && state.chatActiveTurn==null) false else state.chatBusy)
            if (feature == "workspace") state = when (requestId) {
                fileRequestId -> state.copy(fileError = message, fileLoading = false, fileSaving = false)
                revisionRequestId -> state.copy(fileError = message, revisionLoading = false)
                else -> state.copy(fileError = message)
            }
            return
        }
        if (feature == "display" && result.has("helperConnected")) state = state.copy(features = JSONObject(state.features.toString()).put("display", JSONObject(result.toString())))
        if (feature == "phone_setup" && result.has("pairing") && result.has("recovery")) state = state.copy(features = JSONObject(state.features.toString()).put("phone_setup", JSONObject(result.toString())))
        if (feature == "display" && command == "clipboard.restore") native("getFeatures")
        if (feature == "workspace" && command == "prepareExport") {
            state = state.copy(exportPending = true)
            io.execute {
                var temporary: File? = null
                try {
                    val bytes = result.getString("content").toByteArray(Charsets.UTF_8)
                    if (bytes.size > 10 * 1024 * 1024) throw IllegalArgumentException("导出内容过大")
                    temporary = File(app.cacheDir, "owner-export-" + UUID.randomUUID() + ".bin")
                    temporary.writeBytes(bytes)
                    val path = temporary.absolutePath
                    main.post { if (!closed) activity.offerExport(result.optString("suggestedName", "PocketWorld-export.txt"), result.optString("mimeType", "text/plain"), path) else File(path).delete() }
                } catch (error: Exception) { temporary?.delete(); main.post { if (!closed) state = state.copy(exportPending = false, error = message(error)) } }
            }
            return
        }
        val publicResult = if (feature == "ssh" && command == "doctor") dev.pocketengine.features.ssh.SshPrivacy.doctorMetadata(result) else JSONObject(result.toString())
        state = state.copy(moduleResults = state.moduleResults + (key to publicResult), moduleResultInputs = state.moduleResultInputs + (key to metadata), moduleErrors = state.moduleErrors - key)
        when (feature) {
            "automation" -> {
                if (command == "list") result.optJSONArray("rules")?.let {
                    state = state.copy(features = JSONObject(state.features.toString()).put("automation", mergeAutomationReadback(result)))
                }
                if (command == "save") result.optJSONObject("saveReceipt")?.takeIf { it.optString("state") == "saved" }?.let { receipt ->
                    val confirmed = receipt.optString("alarmReplan") == "api_accepted" && receipt.optString("changePublication") == "emitted" && receipt.optString("ruleReadback") == "same_revision" && receipt.optString("sourceState") in setOf("current", "not_applicable")
                    state = state.copy(notice = if (confirmed) "提醒已保存，实际投递以原记录为准。" else "提醒已保存；后续调度、来源或状态回传仍需核对。请刷新记录查看已有提醒，勿重复新建。")
                }
            }
            "realtime" -> if (command in setOf("commitTurn", "commitVoiceTask", "reconcileTurn")) {
                val task = result.optJSONObject("task")
                if (task != null) {
                    mergeTask(task)
                    val waiting = task.optString("status") == "awaiting_user" && task.optInt("coordinatorEpoch") == 0
                    state = state.copy(notice = if (command == "commitVoiceTask") "语音目标已关联同一原任务；启动状态、审批和结果以原任务记录为准" else if (waiting) "语音目标已保存到原任务；请由你打开并明确继续，尚未自动执行" else "已核对原语音任务；以原任务当前状态为准，本次没有重建或启动任务")
                }
                else if (result.optString("message").isNotBlank()) state = state.copy(notice = result.optString("message"))
            }
            "assistant" -> when (command) {
                "list" -> {
                    // The legacy chronological page must not evict a pinned native row from the owner's bounded window.
                    val assistant = state.feature("assistant")
                    state = state.copy(conversations = parseConversations(if (assistant.optBoolean("durableOriginalHistory")) assistant.optJSONArray("conversations") else result.optJSONArray("conversations")))
                }
                "get" -> {
                    if(requestId!=latestChatGet)return
                    val id = result.optString("conversationId")
                    if (id.isNotBlank() && state.conversationId == id) state = state.copy(conversationId = id, messages = result.optJSONArray("messages").objects().map { UiMessage(it.optString("role"), it.optString("content"), it.optLong("at"), it.optString("taskId").takeIf(String::isNotBlank), it.optString("id").takeIf(String::isNotBlank),it.optJSONObject("submission"),it.optJSONArray("attachments")?.length()?:0,it.optJSONArray("attachments").objects().mapNotNull(ChatInputRules::attachment), sourceRevision = if (it.optBoolean("importAssignedIdentity")) 0L else it.optLong("revision", 0), memoryReference = it.optJSONObject("memoryReference"), ownerOriginalLocator = if (it.optBoolean("importAssignedIdentity")) null else it.optJSONObject("ownerOriginalLocator"), phoneResult = UiPhoneResult.fromNativeMessage(it), replyContext = UiReplyContext.fromNativeMessage(it), taskResult = UiTaskResult.fromNativeMessage(it)) });resolveOriginalMessageAttachments(id)
                }
                "send" -> { receiveChatAcknowledgement(result,meta.second.optString("clientMessageId"));native("getFeatures") }
                "getSubmission" -> { receiveChatAcknowledgement(result,meta.second.optString("clientMessageId"),true);native("getFeatures") }
            }
            "display" -> { if (command == "setup") state = state.copy(setupCommands = (result.optJSONArray("setupCommands") ?: org.json.JSONArray()).let { array -> (0 until array.length()).map { array.optString(it) } }); if (result.has("message")) state = state.copy(notice = result.optString("message")) }
            "teams" -> {
                if (isTeamRunResult(command, result)) mergeTeamRun(result)
                if (command == "start") {
                    val runId = result.optString("id")
                    val submitted = teamStartInputs.remove(requestId)
                    if (runId.isNotBlank()) { state = state.copy(teamRunId = runId); if (submitted != null) state = state.copy(acceptedInput = submitted, acceptedInputRevision = state.acceptedInputRevision + 1) }
                }
                if (command == "probeMember") {
                    val teamId = meta.second.optString("teamId")
                    val memberId = meta.second.optString("memberId")
                    val probedSlot = meta.second.optString("_probedSlot")
                    if (probedSlot.isNotBlank()) state = state.copy(teamMemberProbes = state.teamMemberProbes + ((teamId + ":" + memberId) to JSONObject().put("slot", probedSlot).put("receipt", result).put("at", System.currentTimeMillis())))
                }
            }
            "workspace" -> when (command) {
                "listArtifacts" -> {
                    if (!privateWorkspaceView()) return
                    val workspace = JSONObject(state.feature("workspace").toString()).put("artifacts", result.optJSONArray("artifacts") ?: org.json.JSONArray())
                    state = state.copy(features = JSONObject(state.features.toString()).put("workspace", workspace), workspaceArtifactsReadAt = System.currentTimeMillis())
                }
                "readArtifact" -> {
                    val view = state.workspaceArtifactView ?: return
                    if (!privateWorkspaceView()) { closeWorkspaceArtifact(); return }
                    val valid = currentWorkspaceArtifact(view.source, view.fromTaskId) && view.source.matches(result) && result.opt("content") is String
                    state = state.copy(workspaceArtifactView = if (valid) view.copy(content = result.getString("content"), loading = false, error = null) else view.copy(content = null, loading = false, error = "原成果身份、版本或字节来源不一致，未展示替代内容"))
                }
                "diffArtifact" -> {
                    val view = state.workspaceArtifactView ?: return
                    if (!privateWorkspaceView()) { closeWorkspaceArtifact(); return }
                    val valid = currentWorkspaceArtifact(view.source, view.fromTaskId) && result.optString("artifactId") == view.source.id && result.optString("projectId") == view.source.projectId && result.optString("path") == view.source.path && result.optInt("version") > 0 && result.optString("revisionId").matches(Regex("[a-f0-9-]{36}")) && result.opt("diff") is String && result.opt("truncated") is Boolean
                    state = state.copy(workspaceArtifactView = if (valid) view.copy(diffLoading = false, diffError = null, diff = UiWorkspaceArtifactDiff(result.getString("diff"), result.getInt("version"), result.getString("revisionId"), result.getBoolean("truncated"), System.currentTimeMillis())) else view.copy(diffLoading = false, diffError = "差异回执不属于原成果及源文件，请重新读取"))
                }
                "createArtifact" -> refreshWorkspaceArtifacts()
                "create" -> result.optString("id").takeIf { it.isNotBlank() }?.let(::selectProject)
                "list" -> if (meta.second.optString("projectId") == state.projectId) state = state.copy(files = result.optJSONArray("files").objects().map { UiFile(it.optString("path"), it.optInt("version")) })
                "history" -> if (requestId == historyRequestId) state = state.copy(fileHistory = result.optJSONArray("history").objects().map { UiFileRevision(it.optInt("version"), it.optLong("at"), it.optInt("characters"), it.optBoolean("deleted")) }.sortedByDescending { it.version })
                "read", "write" -> if (meta.second.optString("projectId") == state.projectId && meta.second.optString("path") == state.filePath) {
                    if (requestId == revisionRequestId) { state = state.copy(revisionContent = result.optString("content"), revisionLoading = false); return }
                    if (requestId != fileRequestId) return
                    state = state.copy(filePath = result.optString("path", state.filePath ?: ""), fileContent = result.optString("content"), fileBaseline = result.optString("content"), fileVersion = result.optInt("version"), fileLoading = false, fileSaving = false, fileReady = true, fileError = null, lastSavedVersion = if (command == "write") result.optInt("version") else null)
                    if (command == "write") state.projectId?.let { feature("workspace", "list", JSONObject().put("projectId", it)) }
                    if (command == "write" && state.fileHistory.isNotEmpty()) fileHistory()
                }
            }
        }
    }
    private fun invalidateCognitionReadings() {
        val reads = setOf("cognitionProjection", "searchHistory", "readHistoryRecord", "readAssertionEvidence")
        val abandoned = ownerRequests.filter { it.value.domain == "memoryReview" && it.value.command in reads }
        abandoned.forEach { (key, request) -> request.control.cancel(); request.call.cancel(); ownerRequests.remove(key) }
        val snapshot = state.ownerSnapshots["memoryReview"]?.let { JSONObject(it.toString()).put("cognitionRevision", "invalidated:${java.util.UUID.randomUUID()}") }
        state = state.copy(ownerPending = state.ownerPending - abandoned.keys, ownerResponses = state.ownerResponses + ("memoryReview" to (state.ownerResponses("memoryReview") - reads)), ownerSnapshots = if (snapshot == null) state.ownerSnapshots else state.ownerSnapshots + ("memoryReview" to snapshot))
    }
    private fun invalidateTaskMemoryReadings() {
        val reads = setOf("readTaskMemoryReceipt", "readTaskMemorySource", "listTaskMemoryReplacements", "readTaskMemoryTrace", "readTaskMemoryTraceOperation", "readTaskNativeOutputAudit", "readLearnedMethodApplications")
        val abandoned = ownerRequests.filter { it.value.domain == "taskMemory" && it.value.command in reads }
        abandoned.forEach { (key, request) -> request.control.cancel(); request.call.cancel(); ownerRequests.remove(key) }
        state = state.copy(memoryRefreshRevision = state.memoryRefreshRevision + 1, ownerPending = state.ownerPending - abandoned.keys)
    }
    private fun memoryTaskIdentity(task: JSONObject?): String = if (task == null) "" else listOf(task.optString("runId"), task.optInt("coordinatorEpoch").toString(), task.optBoolean("cancelRequested").toString(), task.optString("status"), task.optJSONArray("events").objects().lastOrNull { it.optString("type") == "user_verification" }?.optLong("seq").toString()).joinToString("|")
    private fun mergeTask(task: JSONObject) {
        val parsed = taskFromJson(task); val previous = state.tasks.find { it.id == parsed.id }
        val row = mergeTaskSummary(parsed, previous); val candidates = (state.tasks.filterNot { it.id == row.id } + row).sortedByDescending { it.updatedAt }
        val pinned = setOf(row.id, state.activeTaskId, state.requestedTaskId, webTaskViewId) + state.activePhoneTaskIds + candidates.filter { it.raw.optString("executionRole") == "owner_work" && (it.status !in setOf("completed", "failed", "cancelled", "interrupted") || it.raw.optJSONObject("controls")?.optBoolean("canContinue") == true) }.map { it.id }
        val tasks = (candidates.filter { it.id in pinned } + candidates.filter { it.id !in pinned }.take((80 - pinned.count { id -> candidates.any { it.id == id } }).coerceAtLeast(0))).sortedByDescending { it.updatedAt }
        state = state.copy(tasks = tasks, modelDrafts = ModelStreamDrafts.retain(state.modelDrafts, tasks), activeTaskId = app.activeId())
        if (ownerDomain == "taskMemory" && memoryTaskIdentity(previous?.raw) != memoryTaskIdentity(row.raw)) invalidateTaskMemoryReadings()
    }
    private fun mergeTeamRun(run: JSONObject) {
        val id = run.optString("id")
        if (id.isBlank()) return
        val previous = state.teamRuns[id]
        val current = mergeTeamSnapshot(previous, run)
        state = state.copy(teamRuns = (state.teamRuns + (id to current)).entries.sortedByDescending { it.value.optLong("updatedAt", it.value.optLong("createdAt")) }.take(20).associate { it.toPair() })
    }
    private fun receiveInspection(action: String, result: JSONObject?) {
        val value = result ?: JSONObject()
        val observation = value.optJSONObject("observation")
        val inline = phoneInlinePreview
        if (inline != null && observation != null && !inline.matchesObservation(observation)) {
            state = state.copy(inspectionBusy = false, inspectionObservation = null, inspectionManualAllowed = false, inspectionError = "原任务画面身份已变化，请收起后重新核对。")
            return
        }
        val receipt = value.optJSONObject("receipt")
        val unknown = receipt != null && (receipt.has("accepted") && receipt.isNull("accepted") || receipt.optString("status") == "unknown" || receipt.optString("error").contains("unknown") || receipt.optString("code").contains("unknown"))
        val allowed = !unknown && value.optBoolean("manualAllowed", observation?.optBoolean("manualAllowed", false) ?: false) && (observation == null || !observation.has("ok") || observation.optBoolean("ok"))
        state = state.copy(inspectionBusy = false, inspectionObservation = observation ?: state.inspectionObservation, inspectionManualAllowed = allowed, inspectionMessage = if (unknown) "动作结果不明，请先核对，不能重复执行。" else value.optString("message", receipt?.optString("message") ?: ""), inspectionError = null)
        if (action in setOf("manualTaskAction", "ownerSecretInput") && !unknown && observation == null) observeInspection()
        if (action == "takeoverTask" && allowed && observation == null) observeInspection()
        if (inspectionRefreshPending && !state.inspectionBusy) { inspectionRefreshPending = false; observeInspection() }
        app.sendState()
    }
    internal val conversationOrganizationOwnerVisible get() = privateWorkspaceView()
    /** Metadata from the original native snapshot, not a model-supplied revision. */
    internal fun conversationOrganizationItem(row: UiConversation): dev.pocketengine.features.conversation.ConversationHistory.Item {
        val original = state.feature("assistant").optJSONArray("conversations").objects().firstOrNull { it.optString("conversationId") == row.id }
        return dev.pocketengine.features.conversation.ConversationHistory.Item(row.id, row.title, row.updatedAt, original?.optLong("pinOrder", 0) ?: 0, original?.optLong("organizationRevision", -1) ?: -1)
    }
    internal fun organizeConversation(id: String, revision: Long, title: String?, pinned: Boolean?, done: (JSONObject?, String?) -> Unit) {
        if (!privateWorkspaceView() || revision < 0) { done(null, "请在原私人会话列表刷新后重新打开。"); return }
        val key = "assistant.organization:" + id
        if (key in state.moduleBusy) return
        val changes = JSONObject(); if (title != null) changes.put("title", title); if (pinned != null) changes.put("pinOrder", if (pinned) 1 else 0)
        if (changes.length() == 0) return
        state = state.copy(moduleBusy = state.moduleBusy + key)
        try { io.execute {
            if (!privateWorkspaceView()) { main.post { state = state.copy(moduleBusy = state.moduleBusy - key); done(null, "原私人界面已切换，尚未保存。") }; return@execute }
            val result = runCatching { app.features.ownerConversationWorkbench("updateOrganization", JSONObject().put("conversationId", id).put("expectedRevision", revision).put("changes", changes)) }
            main.post {
                state = state.copy(moduleBusy = state.moduleBusy - key)
                if (!privateWorkspaceView()) return@post
                result.fold({ done(it, null) }, { done(null, "会话元数据已变化或保存结果未确认；请刷新后重新打开，不会自动重试。") })
                native("getFeatures")
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { state = state.copy(moduleBusy = state.moduleBusy - key); done(null, "原界面已关闭，请重新打开。") }
    }
    private fun parseConversations(array: org.json.JSONArray?): List<UiConversation> = array.objects().map { UiConversation(it.optString("conversationId"), it.optString("title"), it.optLong("updatedAt")) }
    private fun message(error: Throwable): String = error.message?.take(300) ?: "操作未完成，请检查连接和配置"
}
