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
import dev.pocketengine.runtime.ModelGateway
import java.util.UUID
import java.io.File
import android.net.Uri
import dev.pocketengine.features.attachments.AttachmentsModule
import dev.pocketengine.media.RealtimeSessionModule
import dev.pocketengine.features.assistant.OwnerComposerState
import dev.pocketengine.features.assistant.OwnerComposerStore
import java.util.concurrent.Executors
import org.json.JSONObject

/** One native owner UI for the existing engine; backend events remain the source of truth. */
internal class PocketWorldController(private val activity: ComposeActivity, private val app: EngineApplication) {
    var state by mutableStateOf(PocketUiState())
        private set
    var deviceWorkspaceRevision by mutableStateOf(0L)
        private set
    internal var remoteAttentionNotice by mutableStateOf<JSONObject?>(null)
        private set
    internal fun showRemoteAttention(locator: JSONObject) { remoteAttentionNotice=JSONObject(locator.toString()); if(locator.optJSONObject("original")?.optString("fingerprint")!=locator.optJSONObject("current")?.optString("fingerprint"))state=state.copy(notice="原通知对应旧状态；已打开原任务，问题和审批将重新查询，不响应旧请求。"); focusTask(locator.getString("taskId")) }
    internal fun consumeRemoteAttention(taskId: String) { if(remoteAttentionNotice?.optString("taskId")==taskId)remoteAttentionNotice=null }
    private val io = Executors.newSingleThreadExecutor()
    internal val projectWorkbench: ProjectWorkbenchSession by lazy {
        androidx.lifecycle.ViewModelProvider(activity, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = ProjectWorkbenchSession(app) as T
        })[ProjectWorkbenchSession::class.java].also { it.attachOwnerUi() }
    }
    private val controls = Executors.newSingleThreadExecutor()
    private val realtimeIo = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(8))
    private val main = Handler(Looper.getMainLooper())
    private val voiceContextEpoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var voiceForeground = false
    private val voiceInput = dev.pocketengine.media.voice.input.VoiceDraftInput(::voiceInputChanged)
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
    internal val projectStorage = ProjectStorageCoordinator(app, io, main, ::privateWorkspaceView) { id -> ownerArchives.openCache(id) }
    internal val projectGitImports = ProjectGitImportCoordinator(app, io, main, ::privateWorkspaceView, { state.requestedProjectRevision.toString() + ":" + projectWorkbench.navigationRevision() }) { checkout ->
        closeProjectPreview(); closeProjectLivePreview()
        projectWorkbench.selectCheckout(checkout.getString("projectId"), checkout.getString("id"), replace = true)
        projectWorkbench.selectView(checkout.getString("projectId"), 1)
        selectProject(checkout.getString("projectId")); developmentCheckoutId = checkout.getString("id"); developmentNavigationRevision++
    }
    internal val projectGit = ProjectGitCoordinator(app, io, main, ::privateWorkspaceView)
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
    private val pending = mutableMapOf<String, Pair<String, JSONObject>>()
    private var wirelessSettingsLaunched = false
    @Volatile private var closed = false
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
    private var attachmentComposerKey: String? = null
    private var attachmentComposerEpoch = -1
    private data class OriginalSourceStage(val key:String,val revision:Long,val viewEpoch:Int,val conversation:String)
    private val originalSourceStages=mutableMapOf<String,OriginalSourceStage>()
    private var submittingChatClient:String?=null
    private data class LocalUnsent(val payload:String,val key:String,val revision:Long)
    /** Deliberately not serialized: a cold unknown/not_found never grants permission to send. */
    private val locallyUnsent=mutableMapOf<String,LocalUnsent>()
    private val closedChatRuns = linkedSetOf<String>()
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
    var projectLivePreview by mutableStateOf<dev.pocketengine.features.local.runtime.ProjectDevSession.Observed?>(null)
        private set
    var projectLivePreviewTaskId by mutableStateOf<String?>(null)
        private set
    var projectLivePreviewChecking by mutableStateOf(false)
        private set
    private var livePreviewRequest = 0
    private var livePreviewObservation: dev.pocketengine.core.RunControl? = null
    private var livePreviewCurrentCheck = false
    fun openProjectLivePreview(taskId: String, checkoutId: String, executionId: String) {
        if (!privateWorkspaceView()) return
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
                main.post { if (!closed && request == livePreviewRequest && privateWorkspaceView()) { projectLivePreview = observed; projectLivePreviewTaskId = taskId; projectLivePreviewChecking = false; enterProjectPreviewIfSelected(task, checkoutId, taskId, navigationRevision) } }
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
        if(state.chatBusy||state.chatSubmitting)return
        val model=ownerComposer ?: return
        try{voiceInputModeChanged();model.selectPending(client);chatViewEpoch++;publishComposer(true);state=state.copy(messages=emptyList(),chatActiveTurn=null);voiceInputViewChanged();persistOwnerComposer();state.conversationId?.let{feature("assistant","get",JSONObject().put("conversationId",it))}}
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
    var queuedInputsPage by mutableStateOf<QueuedOwnerInputsPage?>(null)
        private set
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
        val receipt=ChatInputRules.mergeReceipt(state.chatReceipts[client],result) ?: return
        val ownsPending=submittingChatClient==client;if(ownsPending)submittingChatClient=null
        val allReceipts=state.chatReceipts+(client to receipt)
        val retainedReceipts=allReceipts.filterValues{it.optString("status")=="unconfirmed"}+allReceipts.filterValues{it.optString("status")!="unconfirmed"}.entries.toList().takeLast(64).associate{it.toPair()}
        state=state.copy(chatReceipts=retainedReceipts,chatSubmitting=if(ownsPending)false else state.chatSubmitting)
        if(receipt.optString("status") in setOf("accepted","queued","consumed","withdrawn")) {
            locallyUnsent.remove(client)
            val run=receipt.optString("runId")
            try {
                val model=ownerComposer;val oldKey=model?.activeKey();val ack=model?.acknowledge(client,receipt.getString("conversationId"))
                if(ack?.matched==true){publishComposer(oldKey==ack.previousKey);if(ack.migrated&&oldKey==ack.previousKey){chatViewEpoch++;voiceInputViewChanged()};scheduleComposerSave()}
            }catch(error:Exception){state=state.copy(notice=message(error))}
            if(receipt.optString("status")!="withdrawn"&&state.conversationId==receipt.optString("conversationId")&&run !in closedChatRuns&&(receipt.optString("status")!="consumed"||try{app.chatInputModel(receipt).optBoolean("available")}catch(_:Exception){false}))state=state.copy(chatActiveTurn=JSONObject(receipt.toString()),chatBusy=true)
            if(state.conversationId==receipt.optString("conversationId"))feature("assistant","get",JSONObject().put("conversationId",state.conversationId))
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
    private var inspectionEpoch = 0
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
    private var groupOwnerWindow: String? = null
    private var groupTaskWindow: String? = null
    private var groupTaskPage: String? = null
    private var groupTaskId: String? = null
    var groupTaskReady by mutableStateOf(false)
        private set
    fun enterGroupTask(taskId: String): String {
        projectGit.deactivate()
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
    fun nativeModelConnections(): dev.pocketengine.runtime.models.ModelConnectionService? = app.modelConnections
    fun runModelConnectionWork(work: Runnable) { check(!closed); io.execute(work) }
    fun modelConnectionChanged() { if (!closed) io.execute { app.sendState() } }
    fun enterOwnerDomain(domain: String) {
        if (domain !in setOf("memoryReview", "taskMemory", "mcp", "accounts", "learning", "groups", "channelMedia")) return
        if (domain == "groups") { projectGit.deactivate();projectPackages.leavePrivate(); closeWorkspaceArtifact(); state = state.copy(workspaceArtifactEditorOrigin = null, projectTaskReturn = null) }
        ownerDomain = domain
        if (domain == "channelMedia") { channelMediaWindow?.close(); channelMediaReview = null; channelMediaWindow = nativeChannelModule().openOwnerMediaWindow(activity) }
        if (domain == "groups") try { groupOwnerWindow = app.groupOwnerWorkflows.open(activity) } catch (error: Exception) { state = state.copy(ownerErrors = state.ownerErrors + (domain to message(error))) }
        if (domain != "taskMemory") requestOwnerDomain(domain, "snapshot")
        if (domain == "accounts") { main.removeCallbacks(accountPoll); main.postDelayed(accountPoll, 1500) }
    }
    fun leaveOwnerDomain(domain: String) {
        if (domain == "channelMedia") { channelMediaWindow?.close(); channelMediaWindow = null; channelMediaReview = null }
        if (domain == "groups") { groupOwnerWindow?.let { app.groupOwnerWorkflows?.close(it) }; groupOwnerWindow = null }
        if (domain == "remoteTask") { remoteOwnerWindow?.let { app.remoteOwnerWorkflows?.close(it) }; remoteOwnerWindow = null; remoteOwnerTask = null }
        ownerEpochs[domain] = (ownerEpochs[domain] ?: 0) + 1
        if (ownerDomain == domain) ownerDomain = null
        if (domain == "accounts") { main.removeCallbacks(accountPoll); authorizationOpening = null }
        val abandoned = ownerRequests.values.filter { it.domain == domain }
        abandoned.forEach { request ->
            cancelOwnerControl(request); request.call.cancel(); originalSourceStages.remove(request.id); ownerRequests.remove("$domain.${request.command}")
            if (domain == "mcp" && request.command in setOf("discoverMcp", "discoverMcpAuthorized")) cleanOwnerResult("mcp", "cancel", JSONObject().put("operationId", request.payload.optString("_operationId")))
        }
        if (domain == "mcp") listOf("discoverMcp", "discoverMcpAuthorized").forEach { discovery -> state.ownerResponses(domain)[discovery]?.optJSONObject("result")?.optString("previewId")?.takeIf { it.isNotBlank() }?.let { cleanOwnerResult(domain, "discardMcpPreview", JSONObject().put("previewId", it)) } }
        if (configPick?.domain == domain) { configPick?.call?.close(); configPick = null }
        state = state.copy(ownerPending = state.ownerPending.filterNot { it.startsWith("$domain.") }.toSet(), ownerResponses = state.ownerResponses - domain, ownerErrors = state.ownerErrors - domain, groupOwnerReceipts = if (domain == "groups") emptyMap() else state.groupOwnerReceipts, ownerSource = null)
    }
    private fun cleanOwnerResult(domain: String, command: String, payload: JSONObject) {
        try { app.features.nativeOwnerCleanup { try {
            if (domain == "mcp") app.nativeOwnerWorkflows.mcp(command, payload)
            else dev.pocketengine.features.proxy.accounts.GatewayCall(5000).use { app.nativeOwnerWorkflows.account(command, payload, it) }
        } catch (_: Exception) { /* An expired/already consumed preview or login needs no retry. */ } } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
    fun requestOwnerDomain(domain: String, command: String, payload: JSONObject = JSONObject()): String {
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
        val key = "$domain.$command"
        if (domain == "groups" && groupOwnerWindow == null) try { groupOwnerWindow = app.groupOwnerWorkflows.open(activity) } catch (_: Exception) { /* Worker returns a real denied-window receipt. */ }
        if (domain == "learning" && command == "cancelLearning") return cancelLearning(payload.optString("requestId"))
        if (domain == "memoryReview" && command == "cancelConsolidation") return cancelConsolidation(payload.optString("requestId"))
        if (domain == "memoryReview" && command == "proposeConsolidation" && payload.keys().asSequence().toSet() != setOf("selections")) throw SecurityException("记忆归纳只接受所选记录ID与版本，身份和模型由本机提供")
        if (domain == "learning" && command == "proposeLearning" && payload.keys().asSequence().toSet() != setOf("sourceRef")) throw SecurityException("来源提炼只接受已核对的来源版本，任务、模型和身份由本机提供")
        if (domain == "accounts" && (command in setOf("beginLogin", "cancelLogin", "logout") || command in setOf("setEnabled", "setAppLearningPolicy") && !payload.optBoolean("enabled"))) authorizationOpening = null
        ownerRequests[key]?.let { previous ->
            if (previous.payload.toString() == payload.toString()) return previous.id
            if (command !in setOf("snapshot", "list", "search", "get", "listCandidates", "getCandidate", "candidateCapacity", "listCandidateHistory", "models", "openSource", "prepareLearningSource", "discoverMcp", "discoverMcpAuthorized", "searchTaskMemoryTasks", "readTaskMemoryTrace", "readTaskMemoryTraceOperation", "readTaskNativeOutputAudit", "readLearnedMethodApplications", "listSavedMemorySources", "selectSavedMemorySource", "getConversationHistoryReadScope", "readTaskMemoryReceipt", "readTaskMemorySource", "listTaskMemoryReplacements", "getLearningJob", "listLearningJobs", "searchOwnerOriginals", "readOwnerOriginalSearchHit", "selectOwnerOriginalReference")) throw IllegalStateException("这项操作仍在处理中，请等待真实回执")
            previous.control.cancel(); previous.call.cancel(); originalSourceStages.remove(previous.id)
            if (domain == "mcp" && command in setOf("discoverMcp", "discoverMcpAuthorized")) cleanOwnerResult(domain, "cancel", JSONObject().put("operationId", previous.payload.optString("_operationId")))
        }
        val capturedGroupWindow = if (domain == "groups") groupOwnerWindow else null
        val capturedChannelWindow = if (domain == "channelMedia") channelMediaWindow else null
        val capturedChannelReview = if (domain == "channelMedia" && command == "freezeMedia") channelMediaReview else null
        val request = OwnerRequest(UUID.randomUUID().toString(), domain, command, JSONObject(payload.toString()), ownerEpochs[domain] ?: 0, dev.pocketengine.features.proxy.accounts.GatewayCall(60000))
        ownerRequests[key] = request
        state = state.copy(ownerPending = state.ownerPending + key, ownerErrors = state.ownerErrors - domain)
        val worker = if (command in setOf("cancel", "cancelLogin", "logout", "stopLearningJob") || command == "setEnabled" && !payload.optBoolean("enabled")) controls else io
        try { worker.execute {
            var result: JSONObject? = null
            var snapshot: JSONObject? = null
            var failure: Exception? = null
            try {
                request.call.check()
                result = when (domain) {
                    "taskMemory" -> app.nativeOwnerWorkflows.taskMemory(command, request.payload, request.control)
                    "memoryReview" -> when (command) {
                        "openSource" -> app.nativeOwnerWorkflows.source(request.payload)
                        "proposeConsolidation" -> app.proposeMemoryConsolidation(request.payload.getJSONArray("selections"), request.control)
                        else -> app.nativeOwnerWorkflows.memory(command, request.payload)
                    }
                    "mcp" -> app.nativeOwnerWorkflows.mcp(command, request.payload)
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
                    "accounts" -> if (command == "selectModel") app.nativeOwnerWorkflows.selectModel(request.payload, request.call) else app.nativeOwnerWorkflows.account(command, request.payload, request.call)
                    else -> throw IllegalArgumentException("未知原生管理页面")
                }
            } catch (error: Exception) { failure = error }
            if (failure == null && domain == "accounts" && command == "selectModel") app.sendState()
            if (failure == null && domain != "accounts" && command !in setOf("snapshot", "list", "search", "get", "listCandidates", "getCandidate", "candidateCapacity", "listCandidateHistory", "openSource", "readTaskMemoryTrace", "readTaskMemoryTraceOperation", "readTaskNativeOutputAudit", "readLearnedMethodApplications", "listSavedMemorySources", "selectSavedMemorySource", "getConversationHistoryReadScope", "discoverMcp", "discoverMcpAuthorized", "discardMcpPreview", "cancel")) {
                try { app.features.sendState() } catch (_: Exception) { /* The direct receipt remains authoritative. */ }
            }
            try {
                snapshot = when (domain) {
                    "memoryReview" -> app.memoryOwnerSnapshot()
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
            main.post {
                val sourceStage=originalSourceStages.remove(request.id)
                if (closed || request.epoch != (ownerEpochs[domain] ?: 0) || ownerRequests[key]?.id != request.id) {
                    if (command in setOf("discoverMcp", "discoverMcpAuthorized") && completed != null) cleanOwnerResult(domain, "discardMcpPreview", JSONObject().put("previewId", completed.optString("previewId")))
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
            }
        } } catch (error: java.util.concurrent.RejectedExecutionException) {
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

    fun attach() { app.addObserver(observer);loadOwnerComposer(); refresh() }
    /** Native owner action; the host resolves the existing original control, never payload authority. */

    fun detach() { projectGitImports.detach();projectStorage.detach();ownerArchives.detach();projectGit.close();closeNativeInferenceHistory();closeConversationReader();projectPackages.detach();persistOwnerComposer();closeProjectPreview();closeProjectLivePreview(); voiceForeground=false;voiceContextEpoch.incrementAndGet();voiceInput.close();attachmentOutcomes.detach();groupTaskPage?.let(::leaveGroupTask); groupOwnerWindow?.let { app.groupOwnerWorkflows?.close(it) }; groupOwnerWindow = null; state = state.copy(modelDrafts = emptyMap()); pluginCatalogPage?.close(); pluginCatalogPage = null; ownerDomain?.let(::leaveOwnerDomain); ownerRequests.values.forEach { cancelOwnerControl(it); it.call.cancel() }; main.removeCallbacks(accountPoll); closed = true; app.removeObserver(observer); io.shutdownNow(); controls.shutdownNow(); realtimeIo.shutdownNow(); pending.clear() }
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
    fun voiceInputModeChanged() { voiceContextEpoch.incrementAndGet();if(voiceInput.active())stopListening() }
    fun voiceInputTypeChanged(phone:Boolean) { voiceInputModeChanged();chatViewEpoch++;voiceInput.inputChanged(voiceTarget(phone)) }
    private fun voiceInputViewChanged() { voiceInput.inputChanged(voiceTarget(voiceInput.snapshot().optJSONObject("target")?.optBoolean("phoneInput") ?: false)) }
    fun startListening() { startSystemVoiceInput(false) }
    fun startSystemVoiceInput(phone:Boolean) {
        try {
            voiceOwnerAuthority().check();check(!state.chatSubmitting&&!state.phoneSubmitting) { "原输入正在登记，请等原回执后再听写" };check(!realtimeOwnsAudio()) { "实时语音正在使用音频，请先结束后再系统听写" }
            val info=activity.media.systemSpeechInfo();state=state.copy(systemSpeech=info)
            check(info.optBoolean("recognitionAvailable")) { "这台设备没有可用系统语音服务。请继续文字输入，没有改用云服务" }
            if(!info.optBoolean("microphoneGranted")){activity.media.requestMicrophoneForDraft();state=state.copy(notice="请在系统窗口授权；授权后再明确点击系统听写，当前没有录音");return}
            val media=activity.media
            val captureComposer=if(phone)null else ownerComposer?.activeKey()
            voiceInput.start(voiceTarget(phone),voiceOwnerAuthority(),object:dev.pocketengine.media.voice.input.VoiceDraftInput.Audio {
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
        }catch(error:Exception){state=state.copy(error=message(error))}
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
        voiceForeground=visible;if(!visible)voiceInputModeChanged()
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
    fun requestRealtime(command: String, payload: JSONObject = JSONObject()): String {
        val key = "realtime.$command"
        if((voiceInput.active()||voiceInput.leaseHeld())&&command in setOf("prepareNaturalVoice","prepareCall","prepareProviderCall","acceptInvitation","startVoiceTurn","readVoiceTask")) { state=state.copy(moduleErrors=state.moduleErrors+(key to "请先结束系统听写并等待音频释放，再开始另一项语音"));return UUID.randomUUID().toString() }
        pending.entries.firstOrNull { it.value.first == key }?.let { return it.key }
        val requestId = UUID.randomUUID().toString()
        // Credentials stay only in this bounded worker closure, never UI metadata or generic events.
        val input = JSONObject(payload.toString())
        val metadata = JSONObject().put("_requestId", requestId)
        listOf("sessionId", "invitationId", "voiceTurnId", "turnId", "revision", "reviewId", "contentHash", "commitId", "microphone", "camera", "enabled", "muted", "bindingId", "viewToken", "taskId", "readoutId").forEach { if (input.has(it)) metadata.put(it, input.get(it)) }
        pending[requestId] = key to metadata
        state = state.copy(moduleBusy = state.moduleBusy + key, moduleErrors = state.moduleErrors - key)
        val urgent = command in setOf("disconnect", "rejectInvitation", "stopVoiceTurn", "interruptOutput", "cancelVoiceTask", "snapshot") || command == "mute" && input.optBoolean("muted") || command == "setCamera" && !input.optBoolean("enabled")
        try { (if (urgent) controls else realtimeIo).execute {
            var answer: JSONObject? = null; var current: JSONObject? = null; var failure: Exception? = null
            val module = app.features.module("realtime") as? RealtimeSessionModule
            try { check(!closed) { "通话界面已关闭" }; answer = (module ?: error("实时媒体模块不可用")).handleOwner(activity, command, input) }
            catch (error: Exception) { failure = error }
            try { current = module?.snapshot() } catch (_: Exception) { }
            // No provider credential is retained after the native call returns.
            input.remove("participantToken"); input.remove("authenticationToken")
            val result = answer; val snapshot = current; val failed = failure
            main.post {
                if (closed || !pending.containsKey(requestId)) return@post
                snapshot?.let(::receiveRealtimeSnapshot)
                val expectedSession = metadata.optString("sessionId")
                if (expectedSession.isNotBlank() && expectedSession != state.feature("realtime").optString("sessionId")) {
                    pending.remove(requestId); state = state.copy(moduleBusy = state.moduleBusy - key); return@post
                }
                val event = JSONObject().put("feature", "realtime").put("command", command).put("requestId", requestId).put("ok", failed == null)
                if (failed == null) event.put("result", result ?: JSONObject()) else event.put("message", message(failed))
                featureResult(event)
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            input.remove("participantToken"); input.remove("authenticationToken"); pending.remove(requestId)
            state = state.copy(moduleBusy = state.moduleBusy - key, moduleErrors = state.moduleErrors + (key to "通话操作尚未发送，请稍后重新核对"))
        }
        return requestId
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
    fun importAttachments(intent: Intent, taskId: String?, projectId: String?) {
        state = state.copy(attachmentImporting = true)
        io.execute {
            try {
                val module = app.features.module("attachments") as? AttachmentsModule ?: throw IllegalStateException("附件能力尚未接入")
                val result = module.importIntent(intent, "", projectId ?: "")
                if(!taskId.isNullOrBlank())for(file in result.optJSONArray("attachments").objects())app.selectOutcomeForTask(taskId,file.getString("id"),file.getString("sha256"),dev.pocketengine.core.RunControl())
                main.post {
                    if (!closed) {
                        val count = result.optJSONArray("attachments")?.length() ?: 0
                        val errors = result.optJSONArray("errors").objects().joinToString("；") { it.optString("message") }
                        state = state.copy(attachmentImporting = false, notice = if (count > 0) "已导入 " + count + " 个附件" else null, error = errors.takeIf { it.isNotBlank() })
                        attachmentOutcomes.imported()
                    }
                }
                app.features.sendState()
            } catch (error: Exception) { main.post { if (!closed) state = state.copy(attachmentImporting = false, error = message(error)) } }
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
                val stream = app.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("无法写入选择的位置")
                stream.use { output -> exportFile.inputStream().use { input -> input.copyTo(output) }; output.flush() }
                main.post { if (!closed) state = state.copy(exportPending = false, notice = "文件已保存到你选择的位置") }
            } catch (error: Exception) { main.post { if (!closed) state = state.copy(exportPending = false, error = message(error)) } }
            finally { temporary?.delete() }
        }
    }
    fun startChannelReceiving(review: ChannelReceivingReview, confirmed: Boolean): String = channelReceivingAction("start", JSONObject().put("profileIds", org.json.JSONArray(review.profileIds)).put("reviewFingerprint", review.fingerprint)) { module ->
        if (!confirmed) throw SecurityException("请先明确确认所选连接和接收耗电限制")
        val actual = reviewChannelReceiving(module.snapshot(), review.profileIds)
        if (actual.fingerprint != review.fingerprint) throw SecurityException("连接配置已改变，请重新选择并核对")
        dev.pocketengine.features.channels.ChannelReceiveService.startFromVisibleActivity(activity, module, org.json.JSONArray(review.profileIds), true)
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
    fun switchFromConversationReader(window:Long){val page=conversationReader?:return;if(page.window!=window||state.chatBusy||state.chatSubmitting)return;closeConversationReader();openConversation(page.conversation)}
    fun openReaderAttachment(window:Long,message:String,reference:UiChatAttachment){
        val page=conversationReader?:return;if(page.window!=window||!canReadConversationWindow(page))return
        openPrivateAttachment(reference){conversationReader?.let{canReadConversationWindow(it)&&it.window==window&&it.messages.any{row->row.id==message&&row.attachments.any{file->attachmentOpenKey(file)==attachmentOpenKey(reference)}}}==true}
    }
    fun newConversation() {
        compatibilityChat=null
        if(state.chatBusy||state.chatSubmitting)return
        val model=ownerComposer ?: return
        try{voiceInputModeChanged();model.newConversation();chatViewEpoch++;publishComposer(true);state=state.copy(messages=emptyList(),chatActiveTurn=null);voiceInputViewChanged();persistOwnerComposer()}
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
        // A cold Activity may receive the handoff before its first features_state; inspect the original host, not stale UI busy flags.
        try {
            val assistant=app.features.module("assistant")?.snapshot() ?: throw IllegalStateException("原私聊宿主尚未准备")
            val turn=assistant.optJSONObject("activeTurn") ?: state.chatActiveTurn?.takeIf { app.chatInputModel(it).optBoolean("available") }
            val running=assistant.optBoolean("running") || turn!=null&&app.chatInputModel(turn).optBoolean("available")
            state=state.copy(chatBusy=running,chatActiveTurn=turn)
        } catch(error:Exception){state=state.copy(error=message(error));return}
        if(state.chatBusy&&state.chatActiveTurn?.optString("conversationId")!=review.conversationId){
            if(review.conversationId.isNotBlank())openConversationReader(review.conversationId)
            state=state.copy(notice="原对话仍在执行；兼容草稿已保留，结束后在对应原对话继续。停止请使用原任务控制。")
            return
        }
        try {
            dev.pocketengine.features.assistant.CompatibilityChatHandoff.read(app,review.id)
            voiceInputModeChanged();val draft=if(review.conversationId.isBlank())model.newConversation() else model.selectConversation(review.conversationId)
            chatViewEpoch++;publishComposer(true);voiceInputViewChanged();closeConversationReader();state=state.copy(messages=emptyList(),chatActiveTurn=state.chatActiveTurn.takeIf { state.chatBusy })
            if(review.conversationId.isNotBlank())feature("assistant","get",JSONObject().put("conversationId",review.conversationId))
            if(review.applied()){compatibilityChat=null;return}
            if(review.text.isEmpty()||review.text==draft.text){saveCompatibilityChat(review,model);return}
            val accept: (String)->Unit = { text ->
                try {
                    if(!privateWorkspaceView()||compatibilityChat!==review||ownerComposer!==model||model.activeKey()!=draft.key||state.chatSubmitting||state.chatBusy&&state.chatActiveTurn?.optString("conversationId")!=review.conversationId)throw IllegalStateException("原草稿视图已变化，未覆盖新输入；兼容原文仍保留")
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
        if(state.chatBusy||state.chatSubmitting){openConversationReader(id);return}
        val model=ownerComposer ?: return
        try{voiceInputModeChanged();model.selectConversation(id);chatViewEpoch++;publishComposer(true);state=state.copy(messages=emptyList(),chatActiveTurn=null);voiceInputViewChanged();persistOwnerComposer();feature("assistant","get",JSONObject().put("conversationId",id))}
        catch(error:Exception){state=state.copy(error=message(error))}
    }
    fun sendChat(text:String,key:String,viewEpoch:Int) {
        if(text.isBlank()||text.trim().length>12000||state.chatSubmitting)return
        val model=ownerComposer ?: run{state=state.copy(error="原草稿尚未读取，未发送");return}
        val draft=model.active()
        if(draft.key!=key||chatViewEpoch!=viewEpoch||text!=draft.text||draft.conversationId!=state.conversationId.orEmpty()){state=state.copy(error="原输入视图已变化，请核对保留草稿；没有发送到其他对话");return}
        val binding=state.chatActiveTurn
        if(state.chatBusy&&(binding==null||binding.optString("conversationId")!=state.conversationId)){state=state.copy(error="原任务绑定仍在准备或收尾，请稍后刷新；没有新建第二任务");return}
        if(state.chatBusy&&try{!app.chatInputModel(binding!!).optBoolean("available")}catch(_:Exception){true}){state=state.copy(error="原任务正在收尾，请核对原消息后再发送");return}
        val selected=state.selectedChatAttachments.toList();val selectedSources=draft.sourceRefs();val fingerprint=ChatInputRules.fingerprint(text.trim(),state.conversationId,state.chatSendMode,selected,selectedSources)
        val unresolved=model.matchingPending(draft.key,fingerprint)
        if(unresolved!=null){if(canRetryLocallyUnsent(unresolved.clientId))state=state.copy(notice="本进程确认这条原输入尚未派发；修复保存问题后，请明确点击原输入下的重新保存并发送")else{queryChatSubmission(unresolved.clientId);state=state.copy(notice="正在查询同一输入的原回执，没有用新消息身份重发")};return}
        val client=try{voiceInput.claimMessageId(voiceTarget(false),voiceOwnerAuthority())?:UUID.randomUUID().toString()}catch(error:Exception){state=state.copy(error=message(error));return}
        val input=JSONObject().put("message",text.trim()).put("clientMessageId",client).put("mode",draft.mode).put("attachments",org.json.JSONArray().also{a->selected.forEach{a.put(it.selection())}});if(selectedSources.length()>0)input.put("ownerSourceRefs",selectedSources)
        if(!state.chatBusy)input.put("executionScope",newTaskExecutionScope())
        state.conversationId?.let{input.put("conversationId",it)}
        if(state.chatBusy)for(key in listOf("taskId","runId","epoch"))input.put(key,binding!!.get(key))
        try{model.prepare(client,draft.key,draft.revision,fingerprint,if(state.chatBusy)binding else null);publishComposer()}
        catch(error:Exception){state=state.copy(error=message(error));return}
        submittingChatClient=client;composerSaving=true;state=state.copy(chatSubmitting=true,error=null,chatReceipts=state.chatReceipts+(client to JSONObject().put("clientMessageId",client).put("status","unconfirmed")))
        persistPreparedChat(client,input,model,false)
    }
    fun canRetryLocallyUnsent(client:String):Boolean {
        if(closed||state.chatSubmitting)return false
        val local=locallyUnsent[client]?:return false;val model=ownerComposer?:return false;val p=model.pending(client)?:return false;val draft=model.active()
        if(draft.key!=local.key||draft.revision!=local.revision||p.draftKey!=local.key||p.draftRevision!=local.revision||draft.conversationId!=state.conversationId.orEmpty())return false
        val target=p.originalTarget();val actual=state.chatActiveTurn
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
            if(error!=null){locallyUnsent[client]=LocalUnsent(input.toString(),originalPending.draftKey,originalPending.draftRevision);if(submittingChatClient==client)submittingChatClient=null;state=state.copy(chatSubmitting=false,error="输入定位未能保存，本次未发送；可修复后明确重新保存并发送同一原身份："+composerFailure(error));return@post}
            if(ownerComposer?.revision()==version)composerSaving=false
            val pendingInput=ownerComposer?.pending(client)
            if(pendingInput==null||pendingInput.fingerprint!=originalPending.fingerprint)return@post
            // Once the real send entry is called, uncertainty is reconciled by query only.
            locallyUnsent.remove(client)
            feature("assistant","send",input)
        } }
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
    fun startPhone(goal: String, mode: String) {
        if (state.phoneSubmitting) return
        if (channelPhoneBlocked) { state = state.copy(error = "原渠道请求待核对或已取消；请清空草稿后另写本机新目标") ; return }
        val channel = channelPhoneRequest
        if (channel != null && channel.optString("goal") != goal.trim()) { state = state.copy(error = "渠道原目标已改变，请先取消原请求；不会按私人新任务启动") ; return }
        if (mode == "background") {
            val connection = backgroundConnectionState(state.feature("display"), state.activeTaskId, state.moduleBusy)
            if (!connection.helperConnected || connection.busy || connection.requiresConnectionCheck) {
                openBackgroundSetup()
                state = state.copy(notice = if (connection.busy) "请等待本次连接与进程回收结束，再明确开始原目标" else "先启用并核对后台助手，再返回确认原目标；草稿仍保留")
                return
            }
        }
        try{voiceInput.claimMessageId(voiceTarget(true),voiceOwnerAuthority())}catch(error:Exception){state=state.copy(error=message(error));return}
        state = state.copy(phoneSubmitting = true)
        native("startRun", JSONObject().put("goal", goal.trim()).put("executionMode", mode).put("screenConsent", true).put("executionScope",newTaskExecutionScope()).also { if (channel != null) it.put("channelRequestId", channel.getString("requestId")) })
    }
    fun pausePhone() { native("pauseRun") }
    fun continuePhone() { state.tasks.find { it.id == state.activeTaskId }?.let { resumeTask(it, "") } }
    fun stopPhone() { native("stopRun") }
    fun stopTask(taskId: String) { val team=state.tasks.find { it.id==taskId && it.raw.optJSONObject("origin")?.optString("kind") in setOf("team_assignment","team_integration","team_review") };native("stopTask",team?.let(::reviewedTaskInput)?:JSONObject().put("taskId",taskId)) }
    private fun reviewedTaskInput(task: UiTask): JSONObject = JSONObject().put("taskId", task.id).also {
        if (task.raw.optJSONObject("origin")?.optString("kind") in setOf("group", "team_assignment", "team_integration", "team_review")) it.put("runId", task.raw.optString("runId")).put("epoch", task.raw.optInt("coordinatorEpoch"))
    }
    fun stopReviewedTask(task: UiTask) { native("stopTask", reviewedTaskInput(task)) }
    fun confirmReviewedTask(task: UiTask, done: Boolean, feedback: String) { native("confirmResult", reviewedTaskInput(task).put("resultReviewDigest", task.raw.optString("resultReviewDigest")).put("completed", done).put("feedback", feedback)) }
    fun resolveReviewedTask(task: UiTask, operationId: String, outcome: String) { native("resolveOperation", reviewedTaskInput(task).put("operationId", operationId).put("outcome", outcome)) }
    fun getTask(id: String) { native("getTask", JSONObject().put("taskId", id)) }
    fun focusTask(id: String) { closeConversationReader();getTask(id); state = state.copy(requestedTaskId = id, requestedTaskRevision = state.requestedTaskRevision + 1) }
    fun openConnection(section: String) { taskMcpPreparation = null; state = state.copy(requestedConnectionSection = section, requestedConnectionRevision = state.requestedConnectionRevision + 1) }
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
    fun openTeam(runId: String) { state = state.copy(teamRunId = runId,teamAssignmentFocus=null); feature("teams", "get", JSONObject().put("runId", runId)) }
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
    fun display(command: String) { feature("display", command) }
    fun openBackgroundSetup() { state = state.copy(backgroundSetupOpen = true); refreshBackgroundSetup() }
    fun startPhonePractice(packageName: String, field: String, mode: String) {
        if (!packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) || field.isBlank()) { state = state.copy(error = "请填写实际应用包名和一个可安全测试的普通输入框名称"); return }
        startPhone("这是主人明确批准的手机能力演练。仅打开包名 $packageName，读取当前窗口与控件，找到标签为“${field.take(120)}”的唯一普通非敏感输入框，输入“中文输入演练”，不点发送、保存、支付或任何提交。核对同一原生字段即时与延迟实际值，记录应用版本（可读时）、树覆盖、display/package归属、主屏干扰与结果；不唯一或不支持时交还主人。完成后保持现场供主人核对。", mode)
        if (state.phoneSubmitting) closeBackgroundSetup()
    }
    fun closeBackgroundSetup() { state = state.copy(backgroundSetupOpen = false) }
    fun selectMainScreenCompatibility() {
        state = state.copy(backgroundSetupOpen = false, requestedPhoneMode = "main_screen", requestedPhoneModeRevision = state.requestedPhoneModeRevision + 1,
            notice = "已明确选择主屏兼容模式。请确认系统权限，再手动开始原目标；不会自动执行。")
    }
    fun refreshBackgroundSetup() {
        io.execute { app.sendState() }
        native("getFeatures")
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
        feature("display", "adb.pair", JSONObject().put("host", host).put("pairingPort", port).put("pairingCode", code))
    }
    fun connectWireless(host: String, port: Int, pairingPort: Int?) {
        if (port !in 1..65535 || port == pairingPort) { state = state.copy(error = "连接端口与配对端口不同，请读取无线调试主页"); return }
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
    fun openInspection(taskId: String) {
        inspectionEpoch++
        inspectionRefreshPending = false
        state = state.copy(inspectionTaskId = taskId, inspectionObservation = null, inspectionManualAllowed = false, inspectionError = null, inspectionMessage = null)
        observeInspection()
    }
    fun closeInspection() {
        inspectionEpoch++
        inspectionRefreshPending = false
        state = state.copy(inspectionTaskId = null, inspectionObservation = null, inspectionBusy = false, inspectionManualAllowed = false)
    }
    fun observeInspection() {
        val taskId = state.inspectionTaskId ?: return
        if (state.inspectionBusy) return
        state = state.copy(inspectionBusy = true, inspectionError = null)
        native("observeTask", JSONObject().put("taskId", taskId))
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
    fun useApiKeyModel() {
        if (state.modelSaving) return
        state = state.copy(modelSaving = true, error = null)
        io.execute {
            try {
                app.settings.useApiKeyModel(); app.sendState()
                main.post { if (!closed) state = state.copy(modelSaving = false, modelSavedRevision = state.modelSavedRevision + 1, notice = "已切回原API密钥模型；账号连接仍保留，没有自动请求模型。") }
            } catch (error: Exception) { main.post { if (!closed) state = state.copy(modelSaving = false, error = message(error)) } }
        }
    }
    fun saveLocalObservation(capture: Boolean, ocr: Boolean) {
        io.execute { try { app.settings.saveLocalObservation(capture, ocr); app.sendState() }
            catch (error: Exception) { main.post { if (!closed) state = state.copy(error = message(error)) } } }
    }
    fun saveModel(url: String, model: String, apiKey: String, screenshots: Boolean, provider: String = state.modelProvider, maxOutputTokens: Int = state.maxOutputTokens, cleartextConsent: String = state.cleartextConsent, supportsImageInput: Boolean = false) {
        state = state.copy(modelSaving = true, error = null)
        io.execute {
            try {
                app.settings.save(JSONObject().put("baseUrl", url.trim()).put("model", model.trim()).put("apiKey", apiKey).put("sendScreenshots", screenshots).put("supportsImageInput", supportsImageInput).put("provider", provider).put("maxOutputTokens", maxOutputTokens).put("cleartextConsent", cleartextConsent))
                main.post { if (!closed) state = state.copy(modelSaving = false, modelSavedRevision = state.modelSavedRevision + 1, notice = "模型设置已保存") }
                app.sendState()
            } catch (error: Exception) { main.post { if (!closed) state = state.copy(modelSaving = false, error = message(error)) } }
        }
    }
    fun testModel(url: String, model: String, apiKey: String, screenshots: Boolean, provider: String = state.modelProvider, maxOutputTokens: Int = state.maxOutputTokens, cleartextConsent: String = state.cleartextConsent) {
        state = state.copy(modelTesting = true, modelTest = "正在测试文字连接…", modelTestTaskId = null)
        io.execute {
            try {
                val config = app.settings.merged(JSONObject().put("baseUrl", url.trim()).put("model", model.trim()).put("apiKey", apiKey).put("sendScreenshots", screenshots).put("provider", provider).put("maxOutputTokens", maxOutputTokens).put("cleartextConsent", cleartextConsent))
                val result = app.testModelConnection(config)
                main.post { if (!closed) state = state.copy(modelTesting = false, modelTest = "本次测试：${config.model} · ${config.provider}\n" + result.optString("message"), modelTestTaskId = result.optJSONObject("inferenceReference")?.optString("taskId")?.takeIf { it.matches(Regex("[a-fA-F0-9-]{36}")) }) }
            } catch (error: Exception) { main.post { if (!closed) state = state.copy(modelTesting = false, modelTest = message(error)) } }
        }
    }
    private fun feature(feature: String, command: String, payload: JSONObject = JSONObject()): String {
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
        native("featureRequest", JSONObject().put("feature", feature).put("command", command).put("payload", payload).put("requestId", id))
        return id
    }
    internal var nativeInferenceHistoryOpen by mutableStateOf(false)
        private set
    fun openNativeInferenceHistory(){if(!closed&&privateWorkspaceView())nativeInferenceHistoryOpen=true}
    fun closeNativeInferenceHistory(){nativeInferenceHistoryOpen=false}
    internal fun readNativeInferenceHistory(cursor:String,done:(JSONObject?,String?)->Unit){
        if(closed||!privateWorkspaceView())return
        io.execute{val result=runCatching{app.tasks.nativeInferenceUsagePage(cursor,40)};main.post{if(!closed&&nativeInferenceHistoryOpen&&privateWorkspaceView())result.fold({done(it,null)},{done(null,message(it))})}}
    }
    internal var taskListNextCursor by mutableStateOf("")
        private set
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
    private fun native(action: String, input: JSONObject = JSONObject(), ownerRequestId: String? = null) {
        val viewerEpoch = inspectionEpoch
        val targetTask = input.optString("taskId")
        val taskAction = targetTask.takeIf { it.isNotBlank() && action in setOf("resumeTask", "stopTask", "approveCapability", "confirmResult", "resolveOperation", "updateTask", "reconcileExternalTask", "configureTaskExecutionScope") }
        if (taskAction != null) state = state.copy(taskActionErrors = state.taskActionErrors - taskAction)
        val needsGroupOwner = action in setOf("resumeTask", "stopTask", "approveCapability", "confirmResult", "resolveOperation", "updateTask", "reconcileExternalTask") && state.tasks.any { it.id == targetTask && it.raw.optJSONObject("origin")?.optString("kind") == "group" }
        val groupWindow = if (needsGroupOwner && groupTaskId == targetTask) groupTaskWindow else null
        val reviewedInput = if (needsGroupOwner) JSONObject(input.toString()) else input
        val urgent = action == "automationPlan" && input.optString("command") in setOf("pause", "resume", "cancel") || action in setOf("stopRun", "pauseRun", "resumeRun", "stopTask", "takeoverTask") || action == "featureRequest" && input.optString("command") in setOf("cancel", "release", "stop", "adb.cancel", "termux.cancel", "project.cancel", "project.stopService", "cancelJob", "steerJob", "getSteerMessage", "qq.stop", "weixin.stop", "weixin.login.cancel") || action == "featureRequest" && input.optString("feature") == "teams" && input.optString("command") in setOf("pause", "resume", "feedback", "continueMessages", "sendMessage", "answerAssignment")
        (if (urgent) controls else io).execute {
            try {
                val result = if (needsGroupOwner) app.groupOwnerWorkflows.call(groupWindow ?: throw SecurityException("请在当前原群任务详情重新建立本机审核窗口")) { app.dispatchOwnerAction(action, reviewedInput) }
                    else app.dispatchOwnerAction(action, input)
                main.post {
                    if (closed) return@post
                    if (ownerRequestId != null) {
                        val key = "owner." + action
                        val metadata = JSONObject().put("_requestId", ownerRequestId)
                        listOf("taskId", "messageId", "commitmentId", "claimId", "outcome").forEach { if (input.has(it)) metadata.put(it, input.opt(it)) }
                        state = state.copy(moduleBusy = state.moduleBusy - key, moduleResults = state.moduleResults + (key to (result ?: JSONObject())), moduleResultInputs = state.moduleResultInputs + (key to metadata))
                        if (action in setOf("openDevelopmentProject", "createDevelopmentProject") && developmentOpenRequest == ownerRequestId && result != null && result.has("projectId") && result.has("id")) { developmentCheckoutId = result.getString("id"); projectWorkbench.selectCheckout(result.getString("projectId"), result.getString("id"), replace = action == "createDevelopmentProject"); developmentOpenRequest = null; selectProject(result.getString("projectId")); if (action == "createDevelopmentProject") developmentNavigationRevision++; native("getFeatures") }
                        if (action == "setFollowUpPolicy") { native("rescheduleAutomation"); state = state.copy(notice = "跟进设置已保存") }
                        if (action in setOf("saveTaskMemory", "resaveTaskMemory", "saveMessageMemory", "resaveMessageMemory")) { native("getFeatures"); state = state.copy(notice = "已按真实来源保存记忆") }
                    }
                    if (action in setOf("observeTask", "takeoverTask", "ownerPhoneHandoff", "manualTaskAction", "ownerSecretInput") && viewerEpoch == inspectionEpoch && input.optString("taskId") == state.inspectionTaskId) receiveInspection(action, result)
                    if (action == "startRun") { if (input.has("channelRequestId") && channelPhoneRequest?.optString("requestId") == input.optString("channelRequestId")) channelPhoneAccepted = true; phoneDraftAcceptedText=input.optString("goal");phoneDraftAcceptedRevision++;state = state.copy(phoneSubmitting = false, acceptedInputRevision = state.acceptedInputRevision + 1, acceptedInput = input.optString("goal")) }
                    if (action == "configureTaskExecutionScope" && result != null) { mergeTask(result); state = state.copy(notice = "原范围已更新，累计用量保留；没有自动继续或重放动作") }
                    if (action == "getTask" && result != null) {
                        val task = result.optJSONObject("task") ?: if (result.has("id")) result else null
                        task?.let { mergeTask(it) }
                    }
                    if (action in setOf("confirmResult", "resumeTask", "updateTask", "resolveOperation")) app.sendState()
                    if (action == "resolveOperation" && input.optString("taskId") == state.inspectionTaskId) observeInspection()
                    if (action == "reconcileExternalTask") { getTask(input.optString("taskId")); app.sendState(); state = state.copy(notice = "已记录你核对的结果，未重新执行外部操作") }
                    if (action == "approveCapability") { state = state.copy(notice = if (input.optBoolean("approved") && result?.optBoolean("originalTeamContinuationRequired")==true) "审批已保存在原分工，请回团队继续该成员或唯一整合者" else if (input.optBoolean("approved")) "已授权这次操作，助手会在原任务中继续" else "已拒绝这次操作"); getTask(input.optString("taskId")); app.sendState() }
                    if (action in setOf("resumeTask", "updateTask")) state = state.copy(savedFeedbackRevision = state.savedFeedbackRevision + 1, savedFeedbackTaskId = input.optString("taskId"), savedFeedback = input.optString("feedback"), notice = if (action == "updateTask" && app.activeId() == input.optString("taskId")) "补充已保存，正在结束本次执行；结束后可继续原任务" else if (action == "updateTask") "补充已保存在原任务中" else null)
                    if (action in setOf("startRun", "resumeTask", "resumeRun")) {
                        val taskId = result?.optString("taskId")?.takeIf { it.isNotBlank() } ?: app.activeId()
                        val mode = if (action == "resumeRun") state.tasks.find { it.id == taskId }?.executionMode else input.optString("executionMode")
                        if (mode == "main_screen" && taskId != null) main.postDelayed({
                            if (!closed && !activity.isFinishing && !activity.isDestroyed && app.activeId() == taskId) activity.moveTaskToBack(true)
                        }, 350)
                    }
                }
            } catch (error: Exception) {
                main.post {
                    if (!closed) {
                        if (ownerRequestId != null) {
                            val key = "owner." + action
                            state = state.copy(moduleBusy = state.moduleBusy - key, moduleErrors = state.moduleErrors + (key to message(error)))
                            val metadata = JSONObject().put("_requestId", ownerRequestId).put("_failed", true)
                            listOf("taskId", "messageId", "commitmentId", "claimId", "outcome").forEach { if (input.has(it)) metadata.put(it, input.opt(it)) }
                            state = state.copy(moduleResults = state.moduleResults - key, moduleResultInputs = state.moduleResultInputs + (key to metadata))
                        }
                        if (action == "approveCapability") state = state.copy(approvalRequests = state.approvalRequests - (input.optString("taskId") + ":" + input.optString("approvalId")))
                        if (action in setOf("observeTask", "takeoverTask", "ownerPhoneHandoff", "manualTaskAction", "ownerSecretInput") && viewerEpoch == inspectionEpoch && input.optString("taskId") == state.inspectionTaskId) state = state.copy(inspectionBusy = false, inspectionManualAllowed = false, inspectionError = message(error))
                        if (action == "featureRequest") {
                            featureResult(JSONObject().put("requestId", input.optString("requestId")).put("feature", input.optString("feature")).put("command", input.optString("command")).put("ok", false).put("message", message(error)))
                        }
                        state = state.copy(error = message(error), taskActionErrors = if (taskAction != null) state.taskActionErrors + (taskAction to message(error)) else state.taskActionErrors, phoneSubmitting = if (action == "startRun") false else state.phoneSubmitting)
                        if (action == "featureRequest" && input.optString("feature") == "assistant" && input.optString("command") == "send") state = state.copy(chatBusy = false)
                    }
                }
            }
        }
    }
    private fun receive(event: JSONObject) {
        when (event.optString("type")) {
            "device_workspace_changed" -> deviceWorkspaceRevision++
            "model_stream" -> state = state.copy(modelDrafts = ModelStreamDrafts.accept(state.modelDrafts, event, state.tasks))
            "state" -> {
                val payload = event.optJSONObject("payload") ?: return
                val config = payload.optJSONObject("config") ?: JSONObject()
                taskListNextCursor = payload.optString("tasksNextCursor")
                val incoming = payload.optJSONArray("tasks").objects().map(::taskFromJson)
                val merged = incoming.map { row -> mergeTaskSummary(row, state.tasks.find { it.id == row.id }) }
                val retained = state.tasks.filter { old -> old.id !in incoming.map { it.id } && old.id in setOf(state.activeTaskId, state.requestedTaskId) }

                state = state.copy(modelName = config.optString("model"), baseUrl = config.optString("baseUrl"), hasApiKey = config.optBoolean("hasApiKey"), screenshots = config.optBoolean("sendScreenshots", true), localCaptureAllowed = config.optBoolean("localCaptureAllowed", false), localOcrAllowed = config.optBoolean("localOcrAllowed", false), supportsImageInput = config.optBoolean("supportsImageInput", false), modelProvider = config.optString("provider", "openai_chat"), modelRoute = config.optString("modelRoute", "api_key"), modelCatalogEnabled = config.optJSONObject("capabilityBinding") != null, maxOutputTokens = config.optInt("maxOutputTokens", 4096), cleartextConsent = config.optString("cleartextConsent"), accessibilityEnabled = payload.optBoolean("accessibilityEnabled"), notificationsEnabled = payload.optBoolean("notificationsEnabled"), phoneReadiness = PhoneReadinessState.parse(payload.optJSONObject("phoneReadiness")), activeTaskId = payload.optString("activeTaskId").takeUnless { it.isBlank() || it == "null" }, tasks = merged + retained, modelDrafts = ModelStreamDrafts.retain(state.modelDrafts, merged + retained))
            }
            "task" -> event.optJSONObject("task")?.let(::mergeTask)
            "features_state" -> {
                val features = event.optJSONObject("features") ?: return
                features.optJSONObject("realtime")?.let { features.put("realtime", mergeRealtimeSnapshot(state.feature("realtime"), it)) }
                val assistant = features.optJSONObject("assistant") ?: JSONObject()
                val active=assistant.optJSONObject("activeTurn")?.takeIf{it.optString("runId") !in closedChatRuns} ?: state.chatActiveTurn?.takeIf{it.optString("runId") !in closedChatRuns && try{app.chatInputModel(it).optBoolean("available")}catch(_:Exception){false}}
                val running=active!=null&&(assistant.optBoolean("running")||try{app.chatInputModel(active).optBoolean("available")}catch(_:Exception){false}) || assistant.optBoolean("running")&&assistant.optJSONObject("activeTurn")==null
                state = state.copy(features = features, conversations = parseConversations(assistant.optJSONArray("conversations")), chatBusy = running, chatActiveTurn = active, projects = features.optJSONObject("workspace")?.optJSONArray("projects").objects().map { UiProject(it.optString("id"), it.optString("name")) })
                features.optJSONObject("teams")?.optJSONArray("runs").objects().forEach(::mergeTeamRun)
                applyCompatibilityChat()
            }
            "feature_event" -> {
                val payload = event.optJSONObject("payload") ?: return
                if (event.optString("feature") == "notifications") ++notificationPreviewsSourceRevision
                if (event.optString("feature") == "memory" && payload.optString("event") == "memory_changed") invalidateTaskMemoryReadings()
                if (event.optString("feature") == "assistant") {
                    val id = payload.optString("conversationId")
                    if (id.isNotBlank()) {
                        val phase=payload.optString("phase");val run=payload.optString("runId")
                        if(phase in setOf("completed","failed","cancelled","idle")&&run.isNotBlank()) {
                            closedChatRuns.add(run);while(closedChatRuns.size>128)closedChatRuns.remove(closedChatRuns.first())
                            if(state.chatActiveTurn?.optString("runId")==run)state=state.copy(chatActiveTurn=null,chatBusy=false)
                            applyCompatibilityChat()
                        }
                        if(phase in setOf("accepted","queued","unconfirmed","consumed"))receiveChatAcknowledgement(payload,payload.optString("clientMessageId").takeIf{it.isNotBlank()})
                        else if(phase=="model"&&state.conversationId==id)state=state.copy(chatBusy=true)
                        if(state.conversationId==id)feature("assistant", "get", JSONObject().put("conversationId", id))
                    }
                }
                if (event.optString("feature") == "display" || event.optString("feature") == "channels" && payload.optString("type") in setOf("qq_gateway_state", "weixin_state", "channel_background_state")) native("getFeatures")
                if (event.optString("feature") == "realtime") payload.optJSONObject("snapshot")?.let(::receiveRealtimeSnapshot)
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
                if (!state.inspectionBusy) observeInspection() else inspectionRefreshPending = true
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
        if (failure != null) {
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
                "list" -> state = state.copy(conversations = parseConversations(result.optJSONArray("conversations")))
                "get" -> {
                    if(requestId!=latestChatGet)return
                    val id = result.optString("conversationId")
                    if (id.isNotBlank() && state.conversationId == id) state = state.copy(conversationId = id, messages = result.optJSONArray("messages").objects().map { UiMessage(it.optString("role"), it.optString("content"), it.optLong("at"), it.optString("taskId").takeIf(String::isNotBlank), it.optString("id").takeIf(String::isNotBlank),it.optJSONObject("submission"),it.optJSONArray("attachments")?.length()?:0,it.optJSONArray("attachments").objects().mapNotNull(ChatInputRules::attachment), sourceRevision = if (it.optBoolean("importAssignedIdentity")) 0L else it.optLong("revision", 0), memoryReference = it.optJSONObject("memoryReference")) });resolveOriginalMessageAttachments(id)
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
        val pinned = setOf(row.id, state.activeTaskId, state.requestedTaskId)
        val tasks = (candidates.filter { it.id in pinned } + candidates.filter { it.id !in pinned }.take(80 - pinned.count { id -> candidates.any { it.id == id } })).sortedByDescending { it.updatedAt }
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
        val receipt = value.optJSONObject("receipt")
        val unknown = receipt != null && (receipt.has("accepted") && receipt.isNull("accepted") || receipt.optString("status") == "unknown" || receipt.optString("error").contains("unknown") || receipt.optString("code").contains("unknown"))
        val allowed = !unknown && value.optBoolean("manualAllowed", observation?.optBoolean("manualAllowed", false) ?: false) && (observation == null || !observation.has("ok") || observation.optBoolean("ok"))
        state = state.copy(inspectionBusy = false, inspectionObservation = observation ?: state.inspectionObservation, inspectionManualAllowed = allowed, inspectionMessage = if (unknown) "动作结果不明，请先核对，不能重复执行。" else value.optString("message", receipt?.optString("message") ?: ""), inspectionError = null)
        if (action in setOf("manualTaskAction", "ownerSecretInput") && !unknown && observation == null) observeInspection()
        if (action == "takeoverTask" && allowed && observation == null) observeInspection()
        if (inspectionRefreshPending && !state.inspectionBusy) { inspectionRefreshPending = false; observeInspection() }
        app.sendState()
    }
    private fun parseConversations(array: org.json.JSONArray?): List<UiConversation> = array.objects().map { UiConversation(it.optString("conversationId"), it.optString("title"), it.optLong("updatedAt")) }
    private fun message(error: Throwable): String = error.message?.take(300) ?: "操作未完成，请检查连接和配置"
}
