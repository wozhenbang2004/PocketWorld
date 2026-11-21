package dev.pocketengine.ui.web

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebResourceResponse
import dev.pocketengine.features.attachments.creation.DocumentDraft
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.Fields
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.Format
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.RevisionSource
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.State
import dev.pocketengine.features.attachments.documents.DocumentContent
import dev.pocketengine.features.attachments.processing.NativeFileProcess
import dev.pocketengine.features.attachments.processing.NativeMediaTrim
import dev.pocketengine.features.local.project.ProjectApkInstallActivity
import dev.pocketengine.ui.AttachmentOriginalReturn
import dev.pocketengine.ui.AttachmentPdfPageText
import dev.pocketengine.ui.AttachmentOutcomeState
import dev.pocketengine.ui.AttachmentResultRun
import dev.pocketengine.ui.AttachmentWorkReturn
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.objects
import dev.pocketengine.features.local.project.OriginalNodeCompilerDelivery
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
        val PRIVATE_DRAFT_COMMANDS = setOf("drafts.list", "drafts.new", "drafts.open", "drafts.saveFirst",
            "drafts.checkFirst", "drafts.save", "drafts.leave", "drafts.remove", "drafts.reviewDocument", "drafts.dismissDocument", "drafts.createDocument",
            "drafts.retainRevision", "drafts.checkRevision", "drafts.retainMarkdown", "drafts.checkMarkdown")
        private val SCOPED_RESULT_READS = setOf("back", "close", "cancel", "refresh", "selectFile", "openOriginal", "originalDetails")
        private const val REPORT_WORK_NAVIGATION = "openReportOriginalWork"
        private const val DOCUMENT_REVIEW_TTL = 120_000L
    }
    private val flow get() = controller.attachmentOutcomes
    private data class Window(val id: String, val ticket: PocketWebWindowTicket)
    private data class Frame(val location: String, val files: List<JSONObject>,
        val libraryPdfResume: PocketWebOriginalFiles.LibraryPdfResume?, val displayedPdfPage: AttachmentPdfPageText?)
    private data class Review(val id: String, val location: String, val draft: JSONObject, val expires: Long)
    private data class ProcessingReview(val id: String, val value: NativeFileProcess.Review)
    private data class DraftLibraryVisit(val id: String, val current: () -> Boolean)
    private data class DraftDocumentReview(val id: String, val windowId: String,
        val edit: PocketWebStandaloneDocumentDrafts.Edit, val planDigest: String, val expires: Long)
    private var window: Window? = null
    private val frames = linkedMapOf<String, Frame>()
    private var review: Review? = null
    private var processingReview: ProcessingReview? = null
    private val viewer = PocketWebOriginalFiles(controller.webPresentationActivity(), { controller.webUiCanInteract() }, changed)
    // Set only by the currently admitted explicit no-argument entry from the business bridge.
    private var globalDraftLibrary: DraftLibraryVisit? = null
    private var draftDocumentReview: DraftDocumentReview? = null
    private val drafts = lazy(LazyThreadSafetyMode.NONE) {
        PocketWebStandaloneDocumentDrafts(controller, captureWindow, acceptsWindow, ::currentDraftFrame, ::currentRevisionFrame,
            ::currentMarkdownSource)
    }

    private fun activeWindow(): Window {
        check(controller.webUiCanInteract()) { "请回到当前工作区再查看文件" }
        val existing = window
        if (existing != null && acceptsWindow(existing.ticket)) return existing
        if(existing!=null) {
            flow.discardLibraryDocumentReturn()
            retireDraftLibrary()
        }
        frames.clear(); review = null; processingReview = null; viewer.clear()
        return Window(UUID.randomUUID().toString(), captureWindow()).also { window = it }
    }
    private fun location(s: AttachmentOutcomeState): String = listOf(s.taskId, s.group?.requestId, s.group?.artifactId,
        s.team?.toJson()?.toString(), s.file?.optString("id"), s.file?.optString("sha256"), s.choosingSource,
        s.documentOpen, s.originalFile?.identity, s.originalReturn, s.pdfPageText?.rasterId, s.documentDraft?.toString(), s.processingSource?.identity,
        s.processingReview?.metadata()?.toString(), s.comparison?.first?.identity, s.comparison?.second?.identity).joinToString("\u0000")

    private var deviceTaskCurrent:(()->Boolean)?=null
    private var scopedResultCurrent: (() -> Boolean)? = null
    val hasScopedResult: Boolean get() = scopedResultCurrent != null
    private fun ordinaryLibrary()=flow.privateLibrary()&&scopedResultCurrent==null&&!controller.state.chatAttachmentPickerOpen&&controller.webPrivateOwnerVisible()
    private fun libraryPdfSurface(state: AttachmentOutcomeState, allowPageText: Boolean = false) =
        // The no-locator Library's native presentation survives a normal system picker pause.
        // This does not reuse or renew the retired draft permission or an old file window.
        state.globalStandaloneDraftVisit != null && ordinaryLibrary() && controller.conversationReader == null &&
            controller.state.filePath == null && !state.fromWorkResult && !state.restoredWorkReturn &&
            state.libraryDocumentReturn == null && state.snapshot?.opt("taskId") == "" &&
            state.documentDraft == null && (!state.documentOpen || allowPageText && state.pdfPageText != null) &&
            state.processingSource == null && state.comparison == null

    private fun listedLibraryPdf(files: List<JSONObject>, id: Any?, sha: Any?) =
        id is String && sha is String && files.singleOrNull { it.opt("id") == id }?.let {
            it.opt("sha256") == sha && it.opt("role") == "source" && it.opt("availability") == "available" &&
                it.opt("mimeType") == "application/pdf" && it.optLong("size", 0) > 0
        } == true

    private fun ordinaryDraftLibrarySurface(): Boolean {
        val state = flow.state
        val catalog = state.snapshot
        return ordinaryLibrary() && controller.conversationReader == null && controller.state.filePath == null &&
            !state.busy && !flow.exportPending &&
            state.file == null && state.originalFile == null && state.documentDraft == null && !state.documentOpen &&
            state.processingSource == null && state.comparison == null && state.pendingDocumentTask == null &&
            state.libraryDocumentReturn == null && !state.fromWorkResult && !state.restoredWorkReturn &&
            !controller.state.attachmentImporting &&
            state.libraryImport?.phase !in setOf("selecting", "selection_unconfirmed", "registering") &&
            catalog != null && catalog.opt("taskId") == "" &&
            catalog.optJSONArray("sources") != null && catalog.optJSONArray("artifacts") != null
    }
    private fun mayUseDraftLibrary(): Boolean {
        val visit = globalDraftLibrary ?: return false
        return flow.state.globalStandaloneDraftVisit == visit.id && visit.current() && ordinaryDraftLibrarySurface()
    }
    private fun keepsDraftLibraryVisit(id: String): Boolean {
        val visit = globalDraftLibrary ?: return false
        val active = window ?: return false
        return visit.id == id && flow.state.globalStandaloneDraftVisit == id && acceptsWindow(active.ticket) &&
            visit.current() && ordinaryLibrary()
    }
    private fun currentDraftFrame(selected: PocketWebStandaloneDocumentDrafts.Frame): Boolean {
        val active = window ?: return false
        return active.id == selected.windowId && acceptsWindow(active.ticket) && mayUseDraftLibrary() &&
            frames[selected.frameId]?.location == location(flow.state)
    }
    /** Only the explicit ordinary Library can copy an actually imported local Markdown original. */
    private fun markdownLibraryFile(): JSONObject? {
        val state = flow.state
        val file = state.file ?: return null
        val visit = state.globalStandaloneDraftVisit ?: return null
        if (!keepsDraftLibraryVisit(visit) || controller.conversationReader != null || controller.state.filePath != null ||
            state.originalReturn != dev.pocketengine.ui.AttachmentOriginalReturn.LIST || state.originalFile == null || state.fromWorkResult ||
            state.restoredWorkReturn || state.libraryDocumentReturn != null || state.snapshot?.opt("taskId") != "" ||
            state.busy || flow.exportPending || state.pendingDocumentTask != null || state.documentDraft != null || state.documentOpen ||
            state.processingSource != null || state.comparison != null || controller.state.attachmentImporting ||
            file.opt("role") != "source" || file.opt("sourceKind") != "content_uri" || file.has("sourceTaskId") ||
            file.opt("availability") != "available") return null
        val listed = state.snapshot?.optJSONArray("sources").objects().singleOrNull { it.opt("id") == file.opt("id") }
        return file.takeIf { listed?.opt("sha256") == it.opt("sha256") && listed?.opt("role") == "source" &&
            listed?.opt("sourceKind") == "content_uri" && listed?.opt("availability") == "available" }
    }
    private fun currentMarkdownSource(windowId: String, seed: PocketWebOriginalFiles.MarkdownDraftSeed): Boolean =
        window?.id == windowId && markdownLibraryFile() != null && flow.state.originalFile === seed.source &&
            viewer.retainsMarkdownSource(seed)
    private fun markdownDraftSeed(file: JSONObject): PocketWebOriginalFiles.MarkdownDraftSeed? {
        val attempted = if (drafts.isInitialized()) drafts.value.markdownSource() else null
        if (attempted != null && viewer.retainsMarkdownSource(attempted)) return attempted
        return viewer.markdownDraftSeed(file, flow.state.originalFile)
    }
    /** Do not derive a linked draft entry from a restored or unscoped file/editor. */
    private fun linkedEditorSource(): RevisionSource? {
        val visit = globalDraftLibrary ?: return null
        val state = flow.state
        val file = state.file ?: return null
        val draft = state.documentDraft ?: return null
        val editable = file.optJSONObject("editableSource") ?: return null
        if (state.globalStandaloneDraftVisit != visit.id || !visit.current() || !ordinaryLibrary() ||
            controller.conversationReader != null || controller.state.filePath != null ||
            state.snapshot?.opt("taskId") != "" || state.fromWorkResult || state.restoredWorkReturn || state.libraryDocumentReturn != null ||
            state.busy || flow.exportPending || state.pendingDocumentTask != null || !state.documentDraftEdited || state.documentOpen ||
            state.processingSource != null || state.comparison != null || file.opt("role") != "result" || file.opt("availability") != "available" ||
            file.opt("sourceKind") != "created_document" || file.optString("sourceOperationId").isBlank() ||
            file.opt("mimeType") !in setOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document") ||
            draft.opt("format") !in setOf("docx", "pdf") || draft.opt("parentArtifactId") != file.opt("id") ||
            draft.opt("parentSha256") != file.opt("sha256") || draft.opt("editableSourceId") != editable.opt("id") ||
            draft.opt("editableSourceSha256") != editable.opt("sha256")) return null
        return runCatching {
            fun id(key: String): UUID = draft.getString(key).let { value -> UUID.fromString(value).also { check(it.toString() == value) } }
            RevisionSource(id("parentArtifactId"), draft.getString("parentSha256"), id("editableSourceId"), draft.getString("editableSourceSha256"))
        }.getOrNull()
    }
    private fun currentRevisionFrame(selected: PocketWebStandaloneDocumentDrafts.Frame): Boolean {
        val active = window ?: return false
        return active.id == selected.windowId && acceptsWindow(active.ticket) &&
            frames[selected.frameId]?.location == location(flow.state) && linkedEditorSource() != null
    }
    private fun retireDraftLibrary(keepPresentation: Boolean = false) {
        globalDraftLibrary = null
        draftDocumentReview = null
        if (drafts.isInitialized()) {
            drafts.value.retire()
            drafts.value.forgetRecentMarkdown()
        }
        if (!keepPresentation) flow.discardStandaloneDraftPresentation()
    }
    private fun canNavigateFromReport(state: AttachmentOutcomeState): Boolean =
        state.resultRun?.report != null && scopedResultCurrent?.invoke() == true && controller.webPrivateOwnerVisible() &&
            !state.deviceOriginalOnly && state.group == null && state.team == null && state.file?.optString("role") == "result" &&
            viewer.matchesOriginal(state.file)
    private fun canReviewManagedWorkApk(state: AttachmentOutcomeState): Boolean =
        scopedResultCurrent == null && controller.webPrivateOwnerVisible() && state.fromWorkResult && !state.restoredWorkReturn &&
            state.resultRun == null && !state.deviceOriginalOnly && state.group == null && state.team == null && viewer.matchesOriginal(state.file) &&
            ProjectApkInstallActivity.canReviewManagedWorkFile(state.originalFile, state.taskId, state.file)
    fun openOriginalFromDevice(file:dev.pocketengine.ui.ReceivedFileSelection,current:()->Boolean){
        check(controller.webUiCanInteract() && controller.webPrivateOwnerVisible() && current())
        clear();deviceTaskCurrent=current
        flow.openReceivedOriginal(file,current);changed()
    }
    fun snapshot(draftPresentation: Boolean = false): JSONObject {
        if(flow.state.deviceOriginalOnly && deviceTaskCurrent?.invoke()!=true || scopedResultCurrent?.invoke()==false)clear()
        flow.state.pdfPageText?.let { page ->
            if(!libraryPdfSurface(flow.state,allowPageText=true) ||
                viewer.displayedPdfPage(flow.state.file)!=page)flow.backFromDocument()
        }
        val state = flow.state
        globalDraftLibrary?.let { visit ->
            if (state.globalStandaloneDraftVisit != visit.id || !visit.current()) retireDraftLibrary()
        }
        if (drafts.isInitialized() && drafts.value.showing() && !mayUseDraftLibrary()) retireDraftLibrary()
        if (drafts.isInitialized() && drafts.value.retainingLinked() && linkedEditorSource() == null) drafts.value.retireLinked()
        if (!state.open) { if (window != null || scopedResultCurrent != null) clear(); return JSONObject().put("open", false) }
        if (!controller.webUiCanInteract()) return JSONObject().put("open", true).put("ready", false)
        val current = activeWindow()
        val scoped = scopedResultCurrent
        val report = state.resultRun?.report != null
        val selectedCompiler = OriginalNodeCompilerDelivery.resultRow(state.file)
        val scopedSnapshot = state.snapshot?.let { existing ->
            val artifacts = if (state.choosingSource) existing.optJSONArray("artifacts").objects() else emptyList()
            if (artifacts.any { OriginalNodeCompilerDelivery.resultRow(it) })
                JSONObject(existing.toString()).put("artifacts", JSONArray(artifacts.filterNot { OriginalNodeCompilerDelivery.resultRow(it) }))
            else existing
        }
        val files = (scopedSnapshot?.optJSONArray("sources").objects() + scopedSnapshot?.optJSONArray("artifacts").objects())
            .map { JSONObject(it.toString()) }
        val ordinaryPdf = draftPresentation && libraryPdfSurface(state)
        val libraryPdfResume = if (ordinaryPdf && !state.busy && !flow.exportPending &&
            state.file == null && state.originalFile == null)
            viewer.libraryPdfResume()?.takeIf { listedLibraryPdf(files, it.fileId, it.sha256) } else null
        val source = state.originalFile
        val libraryPdf = source != null && ordinaryPdf && state.originalReturn == AttachmentOriginalReturn.LIST &&
            source.mimeType == "application/pdf" && state.file?.opt("sha256") == source.sha256 &&
            listedLibraryPdf(files, state.file?.opt("id"), source.sha256)
        val rememberPdf: (() -> Boolean)? = if (libraryPdf) ({
            val live = flow.state
            window === current && libraryPdfSurface(live) && live.originalFile === source &&
                live.globalStandaloneDraftVisit == state.globalStandaloneDraftVisit && live.originalReturn == AttachmentOriginalReturn.LIST
        }) else null
        viewer.bind(source, state.comparison, current.ticket, rememberPdf) { ticket ->
            acceptsWindow(ticket) && (!state.deviceOriginalOnly || deviceTaskCurrent?.invoke()==true) && scoped?.invoke()!=false
        }
        val displayedPdfPage = if (libraryPdf && !state.busy && !flow.exportPending &&
            state.snapshot?.optJSONObject("documentCapabilities")?.opt("pdfText")==true)
            viewer.displayedPdfPage(state.file) else null
        val frameId = UUID.randomUUID().toString()
        frames[frameId] = Frame(location(state), files, libraryPdfResume, displayedPdfPage)
        while (frames.size > 16) frames.remove(frames.keys.first())
        if (drafts.isInitialized()) drafts.value.markdownSource()?.let { attempted ->
            if (flow.state.originalFile !== attempted.source || !viewer.retainsMarkdownSource(attempted)) drafts.value.retireMarkdown()
        }
        val markdown = if (draftPresentation) markdownLibraryFile()?.let(::markdownDraftSeed) else null
        val markdownRetention = if (markdown != null && drafts.isInitialized() && drafts.value.markdownSource() === markdown)
            drafts.value.markdownRetention() else null
        val originalReview = state.processingReview
        if (originalReview == null || state.pendingProcessingTask != null) processingReview = null
        else if (processingReview?.value !== originalReview) processingReview = ProcessingReview(UUID.randomUUID().toString(), originalReview)
        val processingInfo = state.processingInfo?.let { JSONObject(it.toString()).also { metadata ->
            if (metadata.has("startAlignment")) metadata.put("startAlignmentLabel", NativeMediaTrim.startAlignmentLabel(metadata))
        } }
        val reviewedProcessing = originalReview?.let { original -> original.metadata().also { metadata ->
            if (original.command == "trimMedia") metadata.put("startAlignmentLabel", NativeMediaTrim.startAlignmentLabel(metadata))
        } }
        val continuationDraft=if(!selectedCompiler&&scoped==null&&!state.deviceOriginalOnly&&state.group==null&&state.team==null&&state.taskId!=null&&state.file!=null&&controller.webPrivateOwnerVisible())runCatching{
            controller.readArtifactContinuationDraft(state.taskId,state.file.getString("id"),state.file.getString("sha256")).put("available",true)
        }.getOrElse{JSONObject().put("available",false).put("error",it.message?:"原成果补充草稿尚未读取")} else null
        val privateOriginal = !state.deviceOriginalOnly && state.group == null && state.team == null && viewer.matchesOriginal(state.file)
        val ownedCopy= scoped == null && !state.deviceOriginalOnly && state.group == null && state.team == null && controller.webPrivateOwnerVisible()
        val copyReceipt=if(ownedCopy)flow.privateCopyReceipt(privateOriginal) else null
        val ordinaryLibrary=ordinaryLibrary()
        val readableLibrarySources=if(ordinaryLibrary&&state.file==null)JSONArray(files.filter{PocketWebOriginalFiles.libraryDocument(it)}
            .map{JSONObject().put("id",it.getString("id")).put("sha256",it.getString("sha256"))}) else null
        // Command ACKs also take a snapshot, without full ordinary-owner presentation.
        // They neither retire a valid paired viewer nor carry its text/receipt.
        val shownPdfPageText=state.pdfPageText?.takeIf{draftPresentation}
        val exposeDocument=state.pdfPageText==null||shownPdfPageText!=null
        return JSONObject().put("open", true).put("ready", true).put("windowId", current.id).put("frameId", frameId)
            .put("historyResultOnly", scoped != null && !report).put("reportResultOnly", report)
            .put("standaloneDraftLibrary", mayUseDraftLibrary())
            .put("canRetainLinkedDocumentDraft", linkedEditorSource() != null)
            .put("markdownDraft", markdown?.let { seed ->
                JSONObject().put("name", seed.fields.name()).put("state", markdownRetention?.state?.name ?: "READY")
                    .put("savedAt", markdownRetention?.savedAt)
            })
            // This ordinary state projection may match a retained Web pane, never a draft command or handle.
            .put("standaloneDraftLibraryVisit", flow.state.globalStandaloneDraftVisit?.takeIf {
                draftPresentation && ordinaryDraftLibrarySurface()
            })
            .put("canOpenReportOriginalWork", canNavigateFromReport(state))
            .put("libraryDocumentActionId", if(ordinaryLibrary)flow.libraryDocumentActionId() else null)
            .put("libraryPdfResume", libraryPdfResume?.view())
            .put("returnToLibraryDocument", ownedCopy && flow.canReturnToLibraryDocument())
            .put("canReferenceHistoryOriginal", !selectedCompiler && scoped != null && !report && privateOriginal)
            .put("canSaveOriginal", scoped == null && privateOriginal).put("canOpenInReader", !selectedCompiler && scoped == null && privateOriginal && viewer.canOpenInReader(state.file))
            .put("canReviewManagedWorkApk", canReviewManagedWorkApk(state))
            .put("externalViewerResult", state.externalViewerResult)
            .put("taskId", state.taskId).put("group", state.group != null).put("team", state.team != null)
            .put("deviceOriginalOnly",state.deviceOriginalOnly).put("fromWorkResult", state.fromWorkResult).put("resultFilesOnly", state.resultRun?.taskId?.let { it == state.taskId } == true)
            .put("restoredWorkReturn", state.restoredWorkReturn)
            .put("busy", state.busy).put("error", state.error).put("notice", state.notice)
            .put("snapshot", scopedSnapshot).put("file", state.file).put("document", state.document.takeIf{exposeDocument})
            .put("continuationDraft",continuationDraft)
            .put("documentOpen", exposeDocument&&state.documentOpen).put("choosingSource", state.choosingSource)
            .put("documentDraft", state.documentDraft).put("documentDraftEdited", state.documentDraftEdited)
            .put("pendingDocumentTask", state.pendingDocumentTask.takeUnless { report })
            .put("processingInfo", processingInfo).put("processingReview", reviewedProcessing).put("processingReviewId", processingReview?.id)
            .put("processingOpen", state.processingSource != null).put("pendingProcessingTask", state.pendingProcessingTask.takeUnless { report })
            .put("progress", state.progress).put("observing", state.observing).put("exportPending", flow.exportPending)
            .put("privateCopyStage", if(ownedCopy)flow.privateCopyStage() else null).put("privateCopyReceipt",copyReceipt)
            .put("libraryImport",state.libraryImport?.takeIf{ordinaryLibrary}?.toJson()).put("readableLibrarySources",readableLibrarySources)
            .put("canRequestOcr", state.pdfPageText==null&&flow.canRequestOcr()).put("canSaveRead", exposeDocument&&flow.canSaveRead())
            .put("canReadTeamDifference", state.team != null && flow.canReadTeamDifference())
            .put("teamDifference", state.teamDifference).put("hasPreviousTeamArtifact", state.teamPrevious != null)
            .put("originalFile", viewer.snapshot()).put("originalReturn", state.originalReturn.value).put("comparison", viewer.comparisonSnapshot())
            .put("canReadPdfPageText", displayedPdfPage!=null)
            .put("pdfPageText", shownPdfPageText?.let { JSONObject().put("viewerId",it.viewerId).put("rasterId",it.rasterId).put("page",it.page) })
    }

    fun dispatch(action: String, args: JSONObject, resultRun: AttachmentResultRun? = null,
                 readerCurrent: (() -> Boolean)? = null,
                 restoreWork: ((AttachmentWorkReturn, () -> Boolean, () -> Unit) -> Unit)? = null,
                 draftLibraryEntry: (() -> Boolean)? = null,
                 done: (JSONObject?, String?) -> Unit) {
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
                    clear(); scopedResultCurrent = readerCurrent
                    val visit = if (args.length() == 0 && task == null && resultRun == null && readerCurrent == null &&
                        draftLibraryEntry != null && draftLibraryEntry()) DraftLibraryVisit(UUID.randomUUID().toString(), draftLibraryEntry) else null
                    globalDraftLibrary = visit
                    flow.openTask(task, resultRun, visit?.id)
                }
                done(JSONObject().put("opened", true).put("reading", true), null); changed(); return
            }
            val current = window ?: error("文件窗口已关闭，请重新打开")
            check(args.optString("windowId") == current.id && acceptsWindow(current.ticket)) { "原文件窗口已变化，请重新核对" }
            check(flow.state.open) { "原文件窗口已关闭" }
            if (action == "attachmentWorkspace.viewer") {
                check(flow.state.pdfPageText==null) { "请先返回原 PDF" }
                viewer.dispatch(args, done); return
            }
            check(action == "attachmentWorkspace.action") { "不支持这项文件操作" }
            val frame = frames[args.getString("frameId")] ?: error("原文件页面已过期，请重新核对")
            check(frame.location == location(flow.state)) { "当前来源或编辑内容已变化，请重新核对" }
            val command = args.getString("command")
            val pairedPdf = flow.state.pdfPageText
            if(pairedPdf!=null) check(command in setOf("back","close","cancel","saveRead")) { "请先返回原 PDF" }
            if (OriginalNodeCompilerDelivery.resultRow(flow.state.file)) {
                check(command in setOf("close", "back", "cancel", "refresh", "selectFile", "openOriginal", "originalDetails", "export", REPORT_WORK_NAVIGATION)) {
                    "此固定编译输出仅在本机查看或另存"
                }
            }
            if (drafts.isInitialized() && drafts.value.showing()) {
                check(command == "back" || command == "close") { "请先返回私人文件库" }
                draftDocumentReview = null
                drafts.value.retire()
            }
            if (drafts.isInitialized() && drafts.value.retainingLinked()) {
                check(command == "back" || command == "close") { "请先核对这次保留，或明确离开原编辑页" }
                drafts.value.retireLinked()
            }
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
                "back" -> if(pairedPdf!=null)flow.backFromDocument() else if (flow.needsRestoredWorkReturn()) {
                    val reopen = restoreWork ?: error("原工作返回入口不可用；可以关闭文件，回原入口核对")
                    fun stillInFile() = window === current && acceptsWindow(current.ticket) && controller.webPrivateOwnerVisible() &&
                        (flow.state.originalFile == null || viewer.matchesOriginal(flow.state.file))
                    flow.verifyRestoredWorkReturn(::stillInFile, { entry, checked ->
                        reopen(entry, checked) { check(checked()); clear(); flow.close() }
                    }, { failure -> done(null, failure) })
                    changed(); return
                } else {
                    review = null; processingReview = null; viewer.clear()
                    when {
                        flow.state.deviceOriginalOnly -> {clear();flow.close()}
                        flow.state.comparison != null -> flow.closeComparison()
                        flow.state.processingSource != null -> flow.closeProcessing()
                        flow.state.documentDraft != null -> flow.closeDocumentDraft()
                        flow.state.originalFile != null -> flow.backFromOriginalFile()
                        flow.state.documentOpen -> flow.backFromDocument()
                        flow.state.file != null -> flow.backFromFile()
                        else -> if (!controller.webPrivateOwnerVisible() || !flow.backToLibraryDocument()) flow.close()
                    }
                }
                "refresh" -> flow.refresh()
                "cancel" -> flow.cancel()
                "selectFile" -> flow.selectFile(selected())
                "attach" -> flow.attach(selected())
                "chooseSource" -> flow.chooseSource()
                "import" -> flow.importSource(ordinaryLibrary())
                "newDocument" -> flow.newDocument()
                "continueDocument" -> flow.continueDocument()
                "updateDocument" -> { review = null; flow.updateDocumentDraft(args.getJSONObject("draft")); check(flow.state.error == null) { flow.state.error.orEmpty() } }
                "reviewDocument" -> {
                    val draft = flow.state.documentDraft ?: error("请先打开文档编辑")
                    check(flow.state.pendingDocumentTask == null && flow.state.group == null && flow.state.team == null) { "请先核对原文档动作" }
                    DocumentDraft(draft)
                    val pin = Review(UUID.randomUUID().toString(), location(flow.state), JSONObject(draft.toString()), SystemClock.elapsedRealtime() + DOCUMENT_REVIEW_TTL)
                    review = pin; result = JSONObject().put("reviewId", pin.id).put("draft", pin.draft)
                }
                "createDocument" -> {
                    val pin = review ?: error("请先核对要生成的文档")
                    check(args.optBoolean("acknowledge") && args.optString("reviewId") == pin.id && pin.location == location(flow.state) && SystemClock.elapsedRealtime() < pin.expires) { "原文档审阅已变化或过期" }
                    review = null; flow.createDocument(pin.draft, ::keepsDraftLibraryVisit)
                }
                "discardReview" -> { review = null; processingReview = null; flow.forgetProcessingReview() }
                "queryDocument" -> flow.queryDocument(::keepsDraftLibraryVisit)
                "openDocumentAction" -> {
                    val id=args.getString("taskId")
                    if(flow.state.file==null)flow.openDocumentAction(id)
                    else {
                        fun currentSource()=window===current&&acceptsWindow(current.ticket)&&ordinaryLibrary()&&
                            frame.location==location(flow.state)
                        require(args.length()==4&&currentSource()){"请在这份私人文档的来源中打开原生成记录"}
                        flow.openDocumentAction(id,::currentSource)
                    }
                }
                "olderDocumentActions" -> flow.olderDocumentActions()
                "read" -> if(args.has("displayedPdfRaster")||args.has("pdfViewerId")){
                    val shown=frame.displayedPdfPage
                    val original=flow.state.originalFile
                    val file=flow.state.file
                    check(args.length()==5 && shown!=null && original!=null && file!=null &&
                        shown.viewerId==args.opt("pdfViewerId") && shown.rasterId==args.opt("displayedPdfRaster")) {
                        "当前未核对到这份 PDF 的原页，未读取其他页面"
                    }
                    fun stillOnPage() = window===current && acceptsWindow(current.ticket) &&
                        libraryPdfSurface(flow.state,allowPageText=true) && flow.state.originalFile===original &&
                        flow.state.file===file && viewer.displayedPdfPage(file)==shown
                    flow.readDisplayedPdfPage(shown,::stillOnPage)
                }else flow.read(DocumentContent.Request(args.optInt("unit", 1), args.optInt("chunk", 0), 4, 12000, 48, "text"))
                "ocr" -> flow.readImageText()
                "saveRead" -> if(pairedPdf==null)flow.saveReadAsFile() else {
                    val file=flow.state.file
                    val original=flow.state.originalFile
                    fun stillOnPage() = window===current && acceptsWindow(current.ticket) &&
                        libraryPdfSurface(flow.state,allowPageText=true) && flow.state.pdfPageText==pairedPdf &&
                        original!=null && flow.state.originalFile===original && flow.state.file===file && viewer.displayedPdfPage(file)==pairedPdf
                    flow.saveReadAsFile(::stillOnPage)
                }
                "originalDetails" -> { flow.showOriginalDetails(); viewer.clear() }
                "openOriginal" -> {
                    val requestedResume = args.has("resumeLibraryPdf")
                    val unavailable = "上次阅读的原 PDF 尚未核对，没有打开其他文件"
                    if (requestedResume) check(args.opt("resumeLibraryPdf") == true && args.length() == 6 &&
                        args.has("fileId") && args.has("sha256")) { unavailable }
                    if (args.has("fileId") || args.has("sha256")) {
                        val file = selected()
                        val librarySource=ordinaryLibrary()&&PocketWebOriginalFiles.libraryDocument(file)
                        val visit = flow.state.globalStandaloneDraftVisit
                        val resume = if (requestedResume) checkNotNull(frame.libraryPdfResume) { unavailable } else null
                        fun resumeCurrent():Boolean {
                            if (resume == null) return true
                            val live = flow.state
                            return librarySource && libraryPdfSurface(live) && live.globalStandaloneDraftVisit == visit &&
                                live.file == null && live.originalFile == null && viewer.libraryPdfResume() == resume &&
                                file.opt("id") == resume.fileId && file.opt("sha256") == resume.sha256
                        }
                        check(resumeCurrent()) { unavailable }
                        flow.openListedOriginal(file.getString("id"), file.getString("sha256"),
                            librarySource=librarySource, expectedLibraryPdfIdentity=resume?.identity) {
                            window === current && acceptsWindow(current.ticket) && controller.webPrivateOwnerVisible() &&
                                scopedResultCurrent?.invoke()!=false && resumeCurrent()
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
                "external" -> if (args.has("reviewProjectApk")) {
                    check(args.length() == 4 && args.opt("reviewProjectApk") == true && !flow.exportPending && canReviewManagedWorkApk(flow.state)) {
                        "请回原工作重新打开这份固定 APK，未交给其他应用"
                    }
                    flow.openExternal(requireNotNull(flow.state.originalFile))
                } else flow.openExternal()
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
                "continueTask" -> {
                    check(!flow.state.restoredWorkReturn) { "请按返回核对原工作，不切换到其他执行轮次" }
                    flow.continueTask()
                }
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
    /** These replies must go through the Bridge's transient completion, not the ordinary workspace result. */
    fun dispatchStandaloneDraft(args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        val unavailable = "原草稿窗口或结果尚未核对；没有自动保存或重试。"
        fun reply(actual: JSONObject?) { if (actual == null) done(null, unavailable) else done(actual, null) }
        fun text(key: String) = args.opt(key) as? String ?: error("原草稿身份无效")
        fun exact(vararg extra: String) {
            require(args.keys().asSequence().toSet() == setOf("command", "windowId", "frameId") + extra)
        }
        fun fields(): Fields {
            val input = args.getJSONObject("fields")
            require(input.keys().asSequence().toSet() == setOf("format", "name", "title", "body", "fontPoints"))
            val format = Format.values().singleOrNull { it.value == input.opt("format") } ?: error("草稿格式无效")
            fun field(key: String) = input.opt(key) as? String ?: error("草稿字段无效")
            return Fields(format, field("name"), field("title"), field("body"),
                input.opt("fontPoints") as? Int ?: error("草稿字号无效"))
        }
        fun content(value: Fields) = JSONObject().put("format", value.format().value).put("name", value.name())
            .put("title", value.title()).put("body", value.body()).put("fontPoints", value.fontPoints())
        fun JSONObject.edit(value: PocketWebStandaloneDocumentDrafts.Edit?) =
            put("editorHandle", value?.editorHandle).put("versionHandle", value?.versionHandle)
        fun JSONObject.sourceUnavailable(unavailable: Boolean) = apply { if (unavailable) put("sourceAvailable", false) }
        fun saved(value: PocketWebStandaloneDocumentDrafts.Saved?) =
            reply(value?.let { JSONObject().put("status", it.state.name).edit(it.edit).sourceUnavailable(it.sourceUnavailable) })
        fun documentInput(input: PocketWebStandaloneDocumentDrafts.GenerationInput): JSONObject = content(input.fields).apply {
            input.source?.let { source ->
                put("parentArtifactId", source.parentArtifactId().toString()).put("parentSha256", source.parentSha256())
                put("editableSourceId", source.editableSourceId().toString()).put("editableSourceSha256", source.editableSourceSha256())
            }
        }
        try {
            val frame = PocketWebStandaloneDocumentDrafts.Frame(text("windowId"), text("frameId"))
            val command = text("command")
            if (command == "drafts.retainMarkdown" || command == "drafts.checkMarkdown") {
                exact("viewerId", "fileId", "sha256")
                val file = markdownLibraryFile() ?: error("请在这份本机 Markdown 原件中保留草稿")
                val seed = markdownDraftSeed(file) ?: error("请先完整读取这份 Markdown 原件")
                check(currentMarkdownSource(frame.windowId, seed) && frames[frame.frameId]?.location == location(flow.state) &&
                    text("viewerId") == seed.viewerId && text("fileId") == file.opt("id") && text("sha256") == seed.source.sha256)
                drafts.value.retainMarkdown(frame, seed, command == "drafts.checkMarkdown") { retained ->
                    reply(retained?.takeIf { currentMarkdownSource(frame.windowId, seed) }?.let {
                        JSONObject().put("status", it.state.name).put("savedAt", it.savedAt)
                            .sourceUnavailable(it.sourceUnavailable)
                    })
                }
                return
            }
            if (command == "drafts.retainRevision" || command == "drafts.checkRevision") {
                val source = linkedEditorSource() ?: error("请回到这个原文件的编辑页")
                exact(); check(currentRevisionFrame(frame))
                val draft = flow.state.documentDraft ?: error("原编辑页已关闭")
                val format = Format.values().singleOrNull { it.value == draft.opt("format") } ?: error("草稿格式无效")
                val content = Fields(format, draft.getString("name"), draft.getString("title"), draft.getString("body"), draft.getInt("fontPoints"))
                val checking = command == "drafts.checkRevision"
                drafts.value.retainRevision(frame, source, content, checking) { retained ->
                    val result = retained?.takeIf { currentRevisionFrame(frame) }?.let { actual ->
                        if (actual.state == State.SAVED || checking && actual.state == State.VERIFIED) flow.finishLinkedDraftRetention()
                        JSONObject().put("status", actual.state.name).sourceUnavailable(actual.sourceUnavailable)
                    }
                    reply(result)
                }
                return
            }
            check(currentDraftFrame(frame))
            val adapter = drafts.value
            fun editor() = PocketWebStandaloneDocumentDrafts.Edit(text("editorHandle"), text("versionHandle"))
            fun noGeneration(reason: String) = JSONObject().put("started", false).put("reason", reason)
            if (command != "drafts.createDocument" && command != "drafts.dismissDocument") draftDocumentReview = null
            when (command) {
                "drafts.list" -> { exact(); adapter.list(frame) { catalog ->
                    reply(catalog?.let { JSONObject().put("metadataComplete", it.metadataComplete)
                        .put("rows", JSONArray(it.rows.map { row -> JSONObject().put("rowHandle", row.rowHandle)
                            .put("name", row.name).put("format", row.format).put("savedAt", row.savedAt)
                            .also {
                                if (row.linkedRevision) it.put("linkedRevision", true)
                                if (row.justCopiedMarkdown) it.put("justCopiedMarkdown", true)
                            } })) })
                } }
                "drafts.new" -> { exact(); val new = adapter.newDraft(frame)
                    reply(JSONObject().put("editorHandle", new.editorHandle).put("fields", content(new.fields)))
                }
                "drafts.open" -> { exact("rowHandle"); adapter.open(frame, text("rowHandle")) { opened ->
                    reply(opened?.let { JSONObject().put("status", it.state.name).edit(it.edit)
                        .put("fields", it.fields?.let(::content)).put("sourceName", it.sourceName).sourceUnavailable(it.sourceUnavailable) })
                } }
                "drafts.saveFirst" -> { exact("editorHandle", "fields"); adapter.firstSave(frame, text("editorHandle"), fields(), ::saved) }
                "drafts.checkFirst" -> { exact("editorHandle"); adapter.checkFirst(frame, text("editorHandle"), ::saved) }
                "drafts.save" -> {
                    exact("editorHandle", "versionHandle", "fields")
                    adapter.save(frame, editor(), fields(), ::saved)
                }
                "drafts.remove" -> {
                    exact("editorHandle", "versionHandle", "acknowledge")
                    require(args.opt("acknowledge") == true) { "请先确认删除这份本机草稿" }
                    val selected = editor()
                    adapter.remove(frame, selected) { state ->
                        reply(state?.let { JSONObject().put("status", it.name).edit(selected) })
                    }
                }
                "drafts.reviewDocument" -> {
                    exact("editorHandle", "versionHandle")
                    val selected = editor()
                    adapter.readGenerationDraft(frame, selected) { read ->
                        val result = if (read?.sourceUnavailable == true && currentDraftFrame(frame))
                            JSONObject().edit(selected).sourceUnavailable(true)
                        else read?.input?.takeIf { currentDraftFrame(frame) }?.let {
                            try {
                                val draft = DocumentDraft(documentInput(it))
                                val prepared = DraftDocumentReview(UUID.randomUUID().toString(), frame.windowId, selected,
                                    draft.identity, SystemClock.elapsedRealtime() + DOCUMENT_REVIEW_TTL)
                                draftDocumentReview = prepared
                                JSONObject().put("status", "ready").put("reviewId", prepared.id).edit(selected)
                                    .put("format", draft.format).put("fileName", draft.fileName())
                            } catch (invalid: Exception) {
                                JSONObject().put("status", "invalid").put("reason", invalid.message?.take(150) ?: "请补全草稿内容")
                            }
                        }
                        reply(result)
                    }
                }
                "drafts.dismissDocument" -> {
                    exact("reviewId")
                    val id = text("reviewId")
                    if (draftDocumentReview?.let { it.windowId == frame.windowId && it.id == id } == true)
                        draftDocumentReview = null
                    reply(JSONObject().put("dismissed", true))
                }
                "drafts.createDocument" -> {
                    exact("editorHandle", "versionHandle", "reviewId", "acknowledge")
                    val selected = editor()
                    val id = text("reviewId")
                    val prepared = draftDocumentReview?.takeIf {
                        args.opt("acknowledge") == true && it.id == id && it.windowId == frame.windowId && it.edit == selected
                    } ?: error("请先核对这份已保留的草稿")
                    draftDocumentReview = null // Consume once, even if the exact reread is unconfirmed.
                    if (SystemClock.elapsedRealtime() >= prepared.expires) {
                        reply(noGeneration("本次核对已过期，请重新核对草稿。")); return
                    }
                    adapter.readGenerationDraft(frame, selected) { read ->
                        val input = read?.input
                        val result = if (!currentDraftFrame(frame)) null else if (read?.sourceUnavailable == true) {
                            noGeneration("暂不能核对原文件或可编辑源；没有开始生成。").edit(selected).sourceUnavailable(true)
                        } else if (input == null) {
                            noGeneration("没有重新核对到这份已保留的版本，请退出并打开原草稿。")
                        } else {
                            val draft = runCatching { DocumentDraft(documentInput(input)) }.getOrNull()
                            if (draft == null || draft.identity != prepared.planDigest || SystemClock.elapsedRealtime() >= prepared.expires) {
                                noGeneration("已保留的草稿或本次核对已变化，请重新打开原稿。")
                            } else {
                                // This is the only entry to the existing creation operation; no draft body enters a reply.
                                runCatching { flow.createDocumentFromSavedDraft(draft, input.source, ::keepsDraftLibraryVisit) }.getOrNull()?.let {
                                    adapter.retire() // No saved-draft handles survive the move to the result inside this Library.
                                    JSONObject().put("started", true)
                                }
                            }
                        }
                        reply(result)
                    }
                }
                "drafts.leave" -> { exact(); adapter.leave(frame); reply(JSONObject().put("left", true)) }
                else -> error("未知的本机文档草稿操作")
            }
        } catch (_: Exception) { done(null, unavailable) }
    }
    fun response(uri: Uri, method: String, headers: Map<String, String>): WebResourceResponse? = viewer.response(uri, method, headers)
    fun foreground(visible: Boolean) { if (!visible) clear(keepDraftPresentation = true) }
    fun clear(keepDraftPresentation: Boolean = false) {
        retireDraftLibrary(keepDraftPresentation)
        if(flow.state.pdfPageText!=null)flow.backFromDocument()
        val scoped = flow.state.deviceOriginalOnly || scopedResultCurrent != null
        deviceTaskCurrent=null; scopedResultCurrent=null
        if(scoped && flow.state.open)flow.close()
        flow.discardLibraryDocumentReturn()
        window = null; frames.clear(); review = null; processingReview = null; viewer.clear()
    }
    fun close() { clear(); viewer.close() }
}
