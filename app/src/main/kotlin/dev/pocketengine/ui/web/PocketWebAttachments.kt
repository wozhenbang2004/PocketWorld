package dev.pocketengine.ui.web

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebResourceResponse
import dev.pocketengine.features.attachments.creation.DocumentDraft
import dev.pocketengine.features.attachments.documents.DocumentContent
import dev.pocketengine.features.attachments.processing.NativeFileProcess
import dev.pocketengine.features.attachments.processing.NativeMediaTrim
import dev.pocketengine.ui.AttachmentOutcomeState
import dev.pocketengine.ui.AttachmentResultRun
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.objects
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The existing file coordinator remains the only source of file, export and recovery truth. */
internal class PocketWebAttachments(
    private val controller: PocketWorldController,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
    private val changed: () -> Unit
) {
    companion object {
        val COMMANDS = setOf("attachmentWorkspace.open", "attachmentWorkspace.action", "attachmentWorkspace.viewer")
        private val SCOPED_RESULT_READS = setOf("back", "close", "cancel", "refresh", "selectFile", "openOriginal", "originalDetails")
        private const val REPORT_WORK_NAVIGATION = "openReportOriginalWork"
    }
    private val flow get() = controller.attachmentOutcomes
    private data class Window(val id: String, val ticket: PocketWebWindowTicket)
    private data class Frame(val location: String, val files: List<JSONObject>)
    private data class Review(val id: String, val location: String, val draft: JSONObject, val expires: Long)
    private data class ProcessingReview(val id: String, val value: NativeFileProcess.Review)
    private var window: Window? = null
    private val frames = linkedMapOf<String, Frame>()
    private var review: Review? = null
    private var processingReview: ProcessingReview? = null
    private val viewer = PocketWebOriginalFiles(controller.webPresentationActivity(), { controller.webUiCanInteract() }, changed)

    private fun activeWindow(): Window {
        check(controller.webUiCanInteract()) { "请回到当前工作区再查看文件" }
        val existing = window
        if (existing != null && acceptsWindow(existing.ticket)) return existing
        frames.clear(); review = null; processingReview = null; viewer.clear()
        return Window(UUID.randomUUID().toString(), captureWindow()).also { window = it }
    }
    private fun location(s: AttachmentOutcomeState): String = listOf(s.taskId, s.group?.requestId, s.group?.artifactId,
        s.team?.toJson()?.toString(), s.file?.optString("id"), s.file?.optString("sha256"), s.choosingSource,
        s.documentOpen, s.originalFile?.identity, s.originalReturn, s.documentDraft?.toString(), s.processingSource?.identity,
        s.processingReview?.metadata()?.toString(), s.comparison?.first?.identity, s.comparison?.second?.identity).joinToString("\u0000")

    private var deviceTaskCurrent:(()->Boolean)?=null
    private var scopedResultCurrent: (() -> Boolean)? = null
    val hasScopedResult: Boolean get() = scopedResultCurrent != null
    private fun canNavigateFromReport(state: AttachmentOutcomeState): Boolean =
        state.resultRun?.report != null && scopedResultCurrent?.invoke() == true && controller.webPrivateOwnerVisible() &&
            !state.deviceOriginalOnly && state.group == null && state.team == null && state.file?.optString("role") == "result" &&
            viewer.matchesOriginal(state.file)
    fun openOriginalFromDevice(file:dev.pocketengine.ui.ReceivedFileSelection,current:()->Boolean){
        check(controller.webUiCanInteract() && controller.webPrivateOwnerVisible() && current())
        clear();deviceTaskCurrent=current
        flow.openReceivedOriginal(file,current);changed()
    }
    fun snapshot(): JSONObject {
        if(flow.state.deviceOriginalOnly && deviceTaskCurrent?.invoke()!=true || scopedResultCurrent?.invoke()==false)clear()
        val state = flow.state
        if (!state.open) { if (window != null || scopedResultCurrent != null) clear(); return JSONObject().put("open", false) }
        if (!controller.webUiCanInteract()) return JSONObject().put("open", true).put("ready", false)
        val current = activeWindow()
        val scoped = scopedResultCurrent
        val report = state.resultRun?.report != null
        val files = (state.snapshot?.optJSONArray("sources").objects() + state.snapshot?.optJSONArray("artifacts").objects())
            .map { JSONObject(it.toString()) }
        val frameId = UUID.randomUUID().toString()
        frames[frameId] = Frame(location(state), files)
        while (frames.size > 16) frames.remove(frames.keys.first())
        viewer.bind(state.originalFile, state.comparison, current.ticket) { ticket ->
            acceptsWindow(ticket) && (!state.deviceOriginalOnly || deviceTaskCurrent?.invoke()==true) && scoped?.invoke()!=false
        }
        val originalReview = state.processingReview
        if (originalReview == null || state.pendingProcessingTask != null) processingReview = null
        else if (processingReview?.value !== originalReview) processingReview = ProcessingReview(UUID.randomUUID().toString(), originalReview)
        val processingInfo = state.processingInfo?.let { JSONObject(it.toString()).also { metadata ->
            if (metadata.has("startAlignment")) metadata.put("startAlignmentLabel", NativeMediaTrim.startAlignmentLabel(metadata))
        } }
        val reviewedProcessing = originalReview?.let { original -> original.metadata().also { metadata ->
            if (original.command == "trimMedia") metadata.put("startAlignmentLabel", NativeMediaTrim.startAlignmentLabel(metadata))
        } }
        val continuationDraft=if(scoped==null&&!state.deviceOriginalOnly&&state.group==null&&state.team==null&&state.taskId!=null&&state.file!=null&&controller.webPrivateOwnerVisible())runCatching{
            controller.readArtifactContinuationDraft(state.taskId,state.file.getString("id"),state.file.getString("sha256")).put("available",true)
        }.getOrElse{JSONObject().put("available",false).put("error",it.message?:"原成果补充草稿尚未读取")} else null
        val privateOriginal = !state.deviceOriginalOnly && state.group == null && state.team == null && viewer.matchesOriginal(state.file)
        val ownedCopy= scoped == null && !state.deviceOriginalOnly && state.group == null && state.team == null && controller.webPrivateOwnerVisible()
        val copyReceipt=if(ownedCopy)flow.privateCopyReceipt(privateOriginal) else null
        return JSONObject().put("open", true).put("ready", true).put("windowId", current.id).put("frameId", frameId)
            .put("historyResultOnly", scoped != null && !report).put("reportResultOnly", report)
            .put("canOpenReportOriginalWork", canNavigateFromReport(state))
            .put("canReferenceHistoryOriginal", scoped != null && !report && privateOriginal)
            .put("canSaveOriginal", scoped == null && privateOriginal).put("canOpenInReader", scoped == null && privateOriginal && viewer.canOpenInReader(state.file))
            .put("externalViewerResult", state.externalViewerResult)
            .put("taskId", state.taskId).put("group", state.group != null).put("team", state.team != null)
            .put("deviceOriginalOnly",state.deviceOriginalOnly).put("fromWorkResult", state.fromWorkResult).put("resultFilesOnly", state.resultRun?.taskId?.let { it == state.taskId } == true)
            .put("busy", state.busy).put("error", state.error).put("notice", state.notice)
            .put("snapshot", state.snapshot).put("file", state.file).put("document", state.document)
            .put("continuationDraft",continuationDraft)
            .put("documentOpen", state.documentOpen).put("choosingSource", state.choosingSource)
            .put("documentDraft", state.documentDraft).put("documentDraftEdited", state.documentDraftEdited)
            .put("pendingDocumentTask", state.pendingDocumentTask.takeUnless { report })
            .put("processingInfo", processingInfo).put("processingReview", reviewedProcessing).put("processingReviewId", processingReview?.id)
            .put("processingOpen", state.processingSource != null).put("pendingProcessingTask", state.pendingProcessingTask.takeUnless { report })
            .put("progress", state.progress).put("observing", state.observing).put("exportPending", flow.exportPending)
            .put("privateCopyStage", if(ownedCopy)flow.privateCopyStage() else null).put("privateCopyReceipt",copyReceipt)
            .put("canRequestOcr", flow.canRequestOcr()).put("canSaveRead", flow.canSaveRead())
            .put("canReadTeamDifference", state.team != null && flow.canReadTeamDifference())
            .put("teamDifference", state.teamDifference).put("hasPreviousTeamArtifact", state.teamPrevious != null)
            .put("originalFile", viewer.snapshot()).put("originalReturn", state.originalReturn.value).put("comparison", viewer.comparisonSnapshot())
    }

    fun dispatch(action: String, args: JSONObject, resultRun: AttachmentResultRun? = null,
                 readerCurrent: (() -> Boolean)? = null, done: (JSONObject?, String?) -> Unit) {
        try {
            check(controller.webUiCanInteract()) { "请回到当前工作区再操作文件" }
            if(flow.state.deviceOriginalOnly){
                check(deviceTaskCurrent?.invoke()==true && controller.webPrivateOwnerVisible()){"原文件所属的工作窗口已变化"}
                check(action=="attachmentWorkspace.viewer" || action=="attachmentWorkspace.action" && args.optString("command") in setOf("back","close","cancel","continueTask")){"从此入口只查看这一个原文件，请先回原工作"}
            }
            scopedResultCurrent?.let { current ->
                check(current()) { "原成果阅读窗口已变化，请重新打开这次成果" }
                val command = args.optString("command")
                check(action == "attachmentWorkspace.viewer" || action == "attachmentWorkspace.action" &&
                    (command in SCOPED_RESULT_READS || command == REPORT_WORK_NAVIGATION && canNavigateFromReport(flow.state))) {
                    "这里仅可查看本次原件，不会修改原工作"
                }
            }
            if (action == "attachmentWorkspace.open") {
                check(controller.webPrivateOwnerVisible()) { "请回到私人工作区打开文件库" }
                require(args.keys().asSequence().all { it in setOf("taskId", "fileId", "sha256", "openOriginal") }) { "文件入口参数无效" }
                val task = args.optString("taskId").takeIf { it.isNotBlank() }
                require(task == null || task.length <= 240 && '\u0000' !in task) { "原工作身份无效" }
                val file = args.optString("fileId").takeIf { it.isNotBlank() }
                val openOriginal = args.opt("openOriginal")
                require(openOriginal == null || openOriginal == true && task != null && file != null) { "原件入口需要原工作和固定文件版本" }
                if (resultRun != null) {
                    require(resultRun.taskId == task && (file != null && openOriginal == true || !args.has("fileId") && openOriginal == null)) { "请选择这条原工作回执的文件" }
                    require(resultRun.report == null || readerCurrent != null && file == null) { "请在原汇报查看本轮文件" }
                }
                check(readerCurrent == null || resultRun != null && !flow.state.open && readerCurrent()) { "请在原阅读窗口打开这次成果" }
                if (file != null) {
                    val sha = args.getString("sha256")
                    require(file.length <= 240 && '\u0000' !in file && sha.matches(Regex("[0-9a-f]{64}"))) { "原文件版本无效，请重新查找" }
                    clear(); scopedResultCurrent = readerCurrent
                    flow.openFile(JSONObject().put("id", file).put("sha256", sha), task, openOriginal == true,
                        fromWorkResult = openOriginal == true && resultRun == null, resultRun = resultRun,
                        returnToEntry = openOriginal == true)
                } else {
                    require(!args.has("sha256")) { "请选择对应的原文件" }
                    // The coordinator rereads the original owner task on its IO executor.
                    // An older task need not still be in the home page's summary cache.
                    clear(); scopedResultCurrent = readerCurrent; flow.openTask(task, resultRun)
                }
                done(JSONObject().put("opened", true).put("reading", true), null); changed(); return
            }
            val current = window ?: error("文件窗口已关闭，请重新打开")
            check(args.optString("windowId") == current.id && acceptsWindow(current.ticket)) { "原文件窗口已变化，请重新核对" }
            check(flow.state.open) { "原文件窗口已关闭" }
            if (action == "attachmentWorkspace.viewer") { viewer.dispatch(args, done); return }
            check(action == "attachmentWorkspace.action") { "不支持这项文件操作" }
            val frame = frames[args.getString("frameId")] ?: error("原文件页面已过期，请重新核对")
            check(frame.location == location(flow.state)) { "当前来源或编辑内容已变化，请重新核对" }
            val command = args.getString("command")
            if (flow.state.busy && command !in setOf("cancel", "close", "stopObservation")) error("请等待当前读取完成，或明确停止它")
            fun selected(): JSONObject {
                val file = frame.files.firstOrNull { it.optString("id") == args.optString("fileId") && it.optString("sha256") == args.optString("sha256") }
                    ?: error("文件不属于当前已读取的原列表")
                check(file.optString("availability", "available") == "available") { "原文件版本当前不可用" }
                check((flow.state.snapshot?.optJSONArray("sources").objects() + flow.state.snapshot?.optJSONArray("artifacts").objects())
                    .any { it.optString("id") == file.optString("id") && it.optString("sha256") == file.optString("sha256") }) { "原文件列表已变化" }
                return file
            }
            fun operation(): String {
                val id = args.getString("operationId")
                val s = flow.state.snapshot
                check(s?.optJSONArray("media").objects().any { it.optString("operationId") == id } ||
                    s?.optString("kind") == "group_media_result" && s.optString("sourceOperationId") == id) { "原媒体操作不在当前成果中" }
                return id
            }
            var result = JSONObject().put("accepted", true).put("verified", false)
            when (command) {
                "close" -> { clear(); if(flow.state.open)flow.close() }
                "back" -> { review = null; processingReview = null; viewer.clear(); when {
                    flow.state.deviceOriginalOnly -> {clear();flow.close()}
                    flow.state.comparison != null -> flow.closeComparison()
                    flow.state.processingSource != null -> flow.closeProcessing()
                    flow.state.documentDraft != null -> flow.closeDocumentDraft()
                    flow.state.originalFile != null -> flow.backFromOriginalFile()
                    flow.state.documentOpen -> flow.backFromDocument()
                    flow.state.file != null -> flow.backFromFile()
                    else -> flow.close()
                } }
                "refresh" -> flow.refresh()
                "cancel" -> flow.cancel()
                "selectFile" -> flow.selectFile(selected())
                "attach" -> flow.attach(selected())
                "chooseSource" -> flow.chooseSource()
                "import" -> flow.importSource()
                "newDocument" -> flow.newDocument()
                "continueDocument" -> flow.continueDocument()
                "updateDocument" -> { review = null; flow.updateDocumentDraft(args.getJSONObject("draft")); check(flow.state.error == null) { flow.state.error.orEmpty() } }
                "reviewDocument" -> {
                    val draft = flow.state.documentDraft ?: error("请先打开文档编辑")
                    check(flow.state.pendingDocumentTask == null && flow.state.group == null && flow.state.team == null) { "请先核对原文档动作" }
                    DocumentDraft(draft)
                    val pin = Review(UUID.randomUUID().toString(), location(flow.state), JSONObject(draft.toString()), SystemClock.elapsedRealtime() + 120_000L)
                    review = pin; result = JSONObject().put("reviewId", pin.id).put("draft", pin.draft)
                }
                "createDocument" -> {
                    val pin = review ?: error("请先核对要生成的文档")
                    check(args.optBoolean("acknowledge") && args.optString("reviewId") == pin.id && pin.location == location(flow.state) && SystemClock.elapsedRealtime() < pin.expires) { "原文档审阅已变化或过期" }
                    review = null; flow.createDocument(pin.draft)
                }
                "discardReview" -> { review = null; processingReview = null; flow.forgetProcessingReview() }
                "queryDocument" -> flow.queryDocument()
                "openDocumentAction" -> flow.openDocumentAction(args.getString("taskId"))
                "olderDocumentActions" -> flow.olderDocumentActions()
                "read" -> flow.read(DocumentContent.Request(args.optInt("unit", 1), args.optInt("chunk", 0), 4, 12000, 48, "text"))
                "ocr" -> flow.readImageText()
                "saveRead" -> flow.saveReadAsFile()
                "originalDetails" -> { flow.showOriginalDetails(); viewer.clear() }
                "openOriginal" -> {
                    if (args.has("fileId") || args.has("sha256")) {
                        val file = selected()
                        flow.openListedOriginal(file.getString("id"), file.getString("sha256")) {
                            window === current && acceptsWindow(current.ticket) && controller.webPrivateOwnerVisible() && scopedResultCurrent?.invoke()!=false
                        }
                    } else flow.openOriginalFile()
                }
                REPORT_WORK_NAVIGATION -> {
                    // Never authorize a write in the report window, or accept a substitute file from the Web.
                    require(args.length() == 3 && canNavigateFromReport(flow.state)) { "请在这份可读原件中进入原工作" }
                    val originalScope = checkNotNull(scopedResultCurrent)
                    fun currentWork() = window === current && acceptsWindow(current.ticket) &&
                        scopedResultCurrent === originalScope && originalScope() && controller.webPrivateOwnerVisible()
                    flow.openReportOriginalInWork(::currentWork) {
                        scopedResultCurrent = null
                        clear() // A different native window/frame is required before the ordinary file actions can be used.
                        changed()
                    }
                }
                "export" -> flow.prepareExport()
                "openInReader" -> {
                    check(flow.state.group == null && flow.state.team == null && viewer.canOpenInReader(flow.state.file)) {
                        "请重新打开这份可读取的私人原件"
                    }
                    flow.openExternal()
                }
                "external" -> flow.openExternal()
                "share" -> flow.shareOriginalFile()
                "readContinuationDraft", "editContinuationDraft" -> {
                    val original=flow.state;val task=original.taskId?:error("请回到原工作的固定成果")
                    val file=original.file?:error("请先选择原成果版本")
                    check(original.group==null&&original.team==null&&controller.webPrivateOwnerVisible()){ "请在私人原成果中编辑补充" }
                    val saved=controller.readArtifactContinuationDraft(task,file.getString("id"),file.getString("sha256"))
                    if(command=="readContinuationDraft")result=JSONObject().put("continuationDraft",saved.put("available",true))
                    else {
                        val revision=args.opt("draftRevision");require(revision is Int||revision is Long){"请重新读取原版本草稿"}
                        val text=args.getString("change");require(text.length<=2000){"成果补充最多2000字"}
                        require(saved.getLong("revision")== (revision as Number).toLong()){ "原版本草稿已有更新，未覆盖本页文字" }
                        controller.saveArtifactContinuationDraft(task,file.getString("id"),file.getString("sha256"),revision.toLong(),text){draft,error->
                            if(window!==current||!acceptsWindow(current.ticket)||!flow.state.open||!controller.webPrivateOwnerVisible()||frame.location!=location(flow.state))
                                done(null,"原成果窗口已离开，请从同一原件核对本机草稿。")
                            else if(error!=null)done(null,error)
                            else {changed();done(JSONObject().put("continuationDraft",draft?.put("available",true)).put("workspace",snapshot()),null)}
                        };return
                    }
                }
                "continueTask" -> flow.continueTask()
                "openProcessing" -> flow.openProcessing()
                "reviewProcessing" -> { processingReview = null; flow.forgetProcessingReview(); flow.reviewProcessing(args.getJSONObject("options")) }
                "applyProcessing" -> {
                    val pin = processingReview ?: error("请先重新核对当前加工范围")
                    check(args.optBoolean("acknowledge") && args.optString("reviewId") == pin.id &&
                        pin.value === flow.state.processingReview && flow.state.pendingProcessingTask == null) { "原加工审阅已变化，请重新核对" }
                    processingReview = null; flow.applyProcessing()
                }
                "queryProcessing" -> flow.queryProcessing()
                "compareParent" -> flow.compareImageParent()
                "readTeam" -> flow.readTeamFile(args.optInt("offset", 0))
                "newerTeam" -> flow.readNewTeamArtifact()
                "previousTeam" -> flow.readPreviousTeamArtifact()
                "teamDifference" -> flow.readTeamDifference(args.optInt("offset", 0))
                "refreshMedia" -> flow.refreshMedia(operation(), false)
                "fetchMedia" -> flow.refreshMedia(operation(), true)
                "cancelMedia" -> flow.cancelMedia(operation())
                "observeMedia" -> flow.observe(operation())
                "stopObservation" -> flow.stopObservation()
                else -> error("不支持这项文件操作")
            }
            changed(); done(result.put("workspace", snapshot()), null)
        } catch (failure: Exception) { done(null, failure.message?.take(300) ?: "文件操作未确认，请重新核对原记录") }
    }
    fun response(uri: Uri, method: String, headers: Map<String, String>): WebResourceResponse? = viewer.response(uri, method, headers)
    fun foreground(visible: Boolean) { if (!visible) clear() }
    fun clear() {
        val scoped = flow.state.deviceOriginalOnly || scopedResultCurrent != null
        deviceTaskCurrent=null; scopedResultCurrent=null
        if(scoped && flow.state.open)flow.close()
        window = null; frames.clear(); review = null; processingReview = null; viewer.clear()
    }
    fun close() { clear(); viewer.close() }
}
