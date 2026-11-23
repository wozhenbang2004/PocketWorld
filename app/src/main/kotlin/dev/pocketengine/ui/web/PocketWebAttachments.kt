package dev.pocketengine.ui.web

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebResourceResponse
import dev.pocketengine.features.attachments.creation.DocumentDraft
import dev.pocketengine.features.attachments.OriginalReplyDocument
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.Fields
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.Format
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.Pin
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.RevisionSource
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.State
import dev.pocketengine.features.attachments.documents.DocumentContent
import dev.pocketengine.features.attachments.processing.NativeFileProcess
import dev.pocketengine.features.attachments.processing.NativeMediaTrim
import dev.pocketengine.features.local.project.ProjectApkInstallActivity
import dev.pocketengine.ui.AttachmentMessageInput
import dev.pocketengine.ui.AttachmentOriginalReturn
import dev.pocketengine.ui.AttachmentPdfPageText
import dev.pocketengine.ui.AttachmentWordExcerpt
import dev.pocketengine.ui.AttachmentWordOrigin
import dev.pocketengine.ui.AttachmentOutcomeState
import dev.pocketengine.ui.AttachmentResultRun
import dev.pocketengine.ui.AttachmentWorkReturn
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.UiChatAttachment
import dev.pocketengine.ui.objects
import dev.pocketengine.ui.web.PocketWebStandaloneDocumentDrafts.TextSeed
import dev.pocketengine.features.local.project.OriginalNodeCompilerDelivery
import dev.pocketengine.features.local.runtime.OwnerPythonOutputText
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The existing file coordinator remains the only source of file, export and recovery truth. */
internal class PocketWebAttachments(
    private val controller: PocketWorldController,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
    private val ownsOrdinaryWork: (String) -> Boolean,
    private val changed: () -> Unit
) {
    companion object {
        val COMMANDS = setOf("attachmentWorkspace.open", "attachmentWorkspace.action", "attachmentWorkspace.viewer")
        val PRIVATE_DRAFT_COMMANDS = setOf("drafts.list", "drafts.new", "drafts.open", "drafts.saveFirst",
            "drafts.checkFirst", "drafts.save", "drafts.leave", "drafts.remove", "drafts.reviewDocument", "drafts.dismissDocument", "drafts.createDocument",
            "drafts.retainRevision", "drafts.checkRevision", "drafts.retainMarkdown", "drafts.checkMarkdown",
            "drafts.retainPdfPage", "drafts.checkPdfPage",
            "drafts.returnGenerated", "drafts.openGeneratedSource", "drafts.openQuotedSource", "drafts.resumeSaved")
        private val DRAFTS_WHILE_DOCUMENT_PENDING = setOf("drafts.list", "drafts.open", "drafts.save", "drafts.leave", "drafts.resumeSaved")
        private val FOCUSED_DRAFT_COMMANDS = setOf("drafts.openQuotedSource", "drafts.save", "drafts.leave", "drafts.reviewDocument",
            "drafts.dismissDocument", "drafts.createDocument")
        private val SCOPED_RESULT_READS = setOf("back", "close", "cancel", "refresh", "selectFile", "openOriginal", "originalDetails")
        private const val REPORT_WORK_NAVIGATION = "openReportOriginalWork"
        private const val DOCUMENT_REVIEW_TTL = 120_000L
    }
    private val flow get() = controller.attachmentOutcomes
    private data class Window(val id: String, val ticket: PocketWebWindowTicket, val accepts: (PocketWebWindowTicket) -> Boolean,
                              val searchAnchorReader: Long? = null)
    private data class ReplyDocumentVisit(val window:Window,val file:UiChatAttachment,val target:OriginalReplyDocument.Target)
    data class ReplyAttachment(val file:UiChatAttachment,val target:OriginalReplyDocument.Target,
        val owns:()->Boolean,val ready:()->Boolean,val close:()->Boolean)
    private data class WorkPdfPin(val work: AttachmentWorkReturn, val page: AttachmentPdfPageText)
    private data class Frame(val location: String, val files: List<JSONObject>,
        val libraryPdfResume: PocketWebOriginalFiles.LibraryPdfResume?, val displayedPdfPage: AttachmentPdfPageText?,
        val displayedWord:AttachmentWordExcerpt?, var workPdfShare: WorkPdfPin?, val workPdfText: WorkPdfPin?)
    private data class Review(val id: String, val location: String, val draft: JSONObject, val expires: Long)
    private data class ProcessingReview(val id: String, val value: NativeFileProcess.Review)
    private data class DraftLibraryVisit(val id: String, val current: () -> Boolean)
    private data class DraftDocumentReview(val id: String, val windowId: String,
        val edit: PocketWebStandaloneDocumentDrafts.Edit, val outputFormat: Format, val planDigest: String, val expires: Long)
    private var window: Window? = null
    private var savedChatLinkOpen:((dev.pocketengine.core.RunControl)->dev.pocketengine.features.attachments.OwnerFilePreviewSource)?=null
    @Volatile private var replyDocumentVisit:ReplyDocumentVisit?=null
    private val frames = linkedMapOf<String, Frame>()
    private var review: Review? = null
    private var processingReview: ProcessingReview? = null
    private val viewer = PocketWebOriginalFiles(controller.webPresentationActivity(), { controller.webUiCanInteract() }, changed)
    // Set only by a freshly admitted explicit ordinary Library entry from the business bridge.
    private var globalDraftLibrary: DraftLibraryVisit? = null
    private data class QuotedDraft(val window: Window, val library: DraftLibraryVisit, val pin: Pin)
    private var quotedDraft: QuotedDraft? = null
    private data class RetiredSavedDraft(val library: String,val previousWindow: String,val editor: PocketWebStandaloneDocumentDrafts.WarmEditor)
    private var retiredSavedDraft: RetiredSavedDraft? = null
    private var warmDraftCheck: DraftLibraryVisit? = null
    private var draftDocumentReview: DraftDocumentReview? = null
    private class GeneratedDraftReturn(val window: Window, val library: String, val action: String,
        val plan: String, val pin: Pin, val handle: String = UUID.randomUUID().toString()) {
        var output: Pair<String,String>? = null
        var landing: JSONObject? = null
    }
    private var generatedDraftReturn: GeneratedDraftReturn? = null
    private val drafts = lazy(LazyThreadSafetyMode.NONE) {
        PocketWebStandaloneDocumentDrafts(controller, captureWindow, acceptsWindow, ::currentDraftFrame, ::currentRevisionFrame,
            ::currentDraftTextSource)
    }

    private fun activeWindow(): Window {
        check(controller.webUiCanInteract()) { "请回到当前工作区再查看文件" }
        val existing = window
        if (existing != null && existing.accepts(existing.ticket)) return existing
        if(existing!=null) {
            flow.discardLibraryDocumentReturn()
            retireDraftLibrary()
        }
        frames.clear(); review = null; processingReview = null; viewer.clear()
        return Window(UUID.randomUUID().toString(), captureWindow(), acceptsWindow).also { window = it }
    }
    private fun location(s: AttachmentOutcomeState): String = listOf(s.taskId, s.group?.requestId, s.group?.artifactId,
        s.team?.toJson()?.toString(), s.file?.optString("id"), s.file?.optString("sha256"), s.choosingSource,
        s.documentOpen, s.originalFile?.identity, s.originalReturn, s.pdfPageText?.rasterId, s.documentDraft?.toString(), s.processingSource?.identity,
        s.processingReview?.metadata()?.toString(), s.comparison?.first?.identity, s.comparison?.second?.identity,
        s.wordExcerpt?.let{"${it.viewerId}:${it.unit}:${it.chunk}"},s.wordSourceVisit?.excerpt?.opt("id")).joinToString("\u0000")

    private var deviceTaskCurrent:(()->Boolean)?=null
    private var scopedResultCurrent: (() -> Boolean)? = null
    val hasScopedResult: Boolean get() = scopedResultCurrent != null
    val hasOpenWorkspace: Boolean get() = flow.state.open
    private fun privateLibraryOwner()=flow.privateLibrary()&&scopedResultCurrent==null&&!controller.state.chatAttachmentPickerOpen&&controller.webPrivateOwnerVisible()
    private fun ordinaryLibrary()=privateLibraryOwner()&&!flow.state.focusedStandaloneDraft
    private fun libraryPdfSurface(state: AttachmentOutcomeState, allowPageText: Boolean = false) =
        // The no-locator Library's native presentation survives a normal system picker pause.
        // This does not reuse or renew the retired draft permission or an old file window.
        state.globalStandaloneDraftVisit != null && ordinaryLibrary() && controller.conversationReader == null &&
            controller.state.filePath == null && !state.fromWorkResult && !state.restoredWorkReturn &&
            state.libraryDocumentReturn == null && state.snapshot?.opt("taskId") == "" &&
            state.documentDraft == null && (!state.documentOpen || allowPageText && state.pdfPageText != null) &&
            state.processingSource == null && state.comparison == null

    private fun wordLibrarySurface(state:AttachmentOutcomeState,paired:Boolean=false):Boolean =
        state.globalStandaloneDraftVisit!=null&&ordinaryLibrary()&&controller.conversationReader==null&&
            controller.state.filePath==null&&!state.fromWorkResult&&!state.restoredWorkReturn&&state.libraryDocumentReturn==null&&
            state.snapshot?.opt("taskId")==""&&state.documentDraft==null&&state.processingSource==null&&state.comparison==null&&
            (!state.documentOpen||paired&&state.wordExcerpt!=null)

    private fun displayedWordExcerpt(state:AttachmentOutcomeState,paired:Boolean=false):AttachmentWordExcerpt? {
        if(!wordLibrarySurface(state,paired)||flow.exportPending)return null
        val file=state.file?:return null
        val origin=state.originalFile?:return null
        val id=file.opt("id") as? String?:return null
        val shown=viewer.displayedWordExcerpt(file)?:return null
        val meta=runCatching{origin.metadata()}.getOrNull()?:return null
        val original=meta.optJSONObject("source")?:return null
        if(meta.opt("principalId")!="owner"||meta.opt("spaceId")!="private"||meta.optBoolean("outputSourceRetired")||
            original.opt("kind")!="content_uri"||!original.isNull("taskId")||!original.isNull("projectId")||
            !original.isNull("groupSpaceId")||!original.isNull("archiveEvidence"))return null
        val listed=state.snapshot?.optJSONArray("sources").objects().singleOrNull{it.opt("id")==id}?:return null
        if(file.opt("mimeType")!=AttachmentWordOrigin.MIME||listed.opt("mimeType")!=AttachmentWordOrigin.MIME||
            origin.mimeType!=AttachmentWordOrigin.MIME||origin.sha256!=file.opt("sha256")||listed.opt("sha256")!=origin.sha256||
            file.opt("role")!="source"||listed.opt("role")!="source"||listed.opt("availability")!="available"||
            listed.opt("sourceKind")!="content_uri"||!listed.isNull("sourceTaskId")||listed.optLong("size",-1)!=origin.sizeBytes)return null
        return shown
    }

    private fun listedLibraryPdf(files: List<JSONObject>, id: Any?, sha: Any?) =
        id is String && sha is String && files.singleOrNull { it.opt("id") == id }?.let {
            it.opt("sha256") == sha && it.opt("role") == "source" && it.opt("availability") == "available" &&
                it.opt("mimeType") == "application/pdf" && it.optLong("size", 0) > 0
        } == true

    private fun ordinaryDraftLibrarySurface(focused: Boolean = false): Boolean {
        val state = flow.state
        val catalog = state.snapshot
        return privateLibraryOwner() && state.focusedStandaloneDraft == focused && controller.conversationReader == null && controller.state.filePath == null &&
            !state.busy && !flow.exportPending &&
            state.file == null && state.originalFile == null && state.documentDraft == null && !state.documentOpen &&
            state.processingSource == null && state.comparison == null &&
            state.libraryDocumentReturn == null && !state.fromWorkResult && !state.restoredWorkReturn &&
            !controller.state.attachmentImporting &&
            state.libraryImport?.phase !in setOf("selecting", "selection_unconfirmed", "registering") &&
            (if (focused) catalog == null else catalog != null && catalog.opt("taskId") == "" &&
                catalog.optJSONArray("sources") != null && catalog.optJSONArray("artifacts") != null)
    }
    private fun warmSavedDraft(now: Window): RetiredSavedDraft? = retiredSavedDraft?.takeIf {
        globalDraftLibrary == null && it.previousWindow != now.id && it.library == flow.state.globalStandaloneDraftVisit &&
            it.editor.pendingDocument == flow.state.pendingDocumentTask && ordinaryDraftLibrarySurface()
    }
    private fun mayUseDraftLibrary(): Boolean {
        val visit = globalDraftLibrary ?: return false
        return flow.state.globalStandaloneDraftVisit == visit.id && visit.current() && ordinaryDraftLibrarySurface(flow.state.focusedStandaloneDraft)
    }
    private fun ownsGeneratedDraftReturn(origin: GeneratedDraftReturn) =
        generatedDraftReturn === origin && window === origin.window && keepsDraftLibraryVisit(origin.library)

    private fun currentGeneratedDraftOutput(origin: GeneratedDraftReturn): Pair<String,String>? {
        val state = flow.state
        if (!ownsGeneratedDraftReturn(origin) || origin.output != null || state.documentOpen || state.documentDraft != null ||
            state.processingSource != null || state.comparison != null || state.libraryDocumentReturn != null ||
            state.fromWorkResult || state.restoredWorkReturn || controller.state.attachmentImporting ||
            state.originalReturn == AttachmentOriginalReturn.ENTRY) return null
        return flow.currentGeneratedDraftOutput(origin.action, origin.plan)
    }

    /** The existing Library Back refresh must really list the newly published original, independently of the draft pin. */
    private fun listsGeneratedDocument(origin: GeneratedDraftReturn, catalog: JSONObject?): Boolean {
        val result = origin.output ?: return false
        return catalog != null && catalog.opt("taskId") == "" &&
            catalog.optJSONArray("artifacts").objects().singleOrNull { it.opt("id") == result.first }?.let {
                it.opt("sha256") == result.second && it.opt("role") == "result" &&
                    it.opt("availability") == "available" && it.opt("sourceKind") == "created_document" &&
                    it.opt("sourceTaskId") == origin.action && it.opt("sourceOperationId") == origin.action
            } == true
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
    // A normal system picker can retire the old draft lease without closing this exact ordinary Library.
    // This predicate offers a page action only; it cannot admit a draft command by itself.
    private fun warmPdfDraftSurface(state: AttachmentOutcomeState) =
        globalDraftLibrary == null && state.documentOpen && state.pdfPageText != null &&
            state.libraryImport?.phase !in setOf("selecting", "selection_unconfirmed", "registering") &&
            libraryPdfSurface(state, allowPageText = true)

    private fun warmPdfDraftOwner(freshOwner: () -> Boolean): () -> Boolean {
        val active = window ?: error("原 PDF 窗口已离开")
        val visit = flow.state.globalStandaloneDraftVisit ?: error("请从私人文件库打开原 PDF")
        val current = {
            window === active && acceptsWindow(active.ticket) && flow.state.globalStandaloneDraftVisit == visit &&
                warmPdfDraftSurface(flow.state) && freshOwner()
        }
        check(current())
        return current
    }

    /** Only a live explicitly opened Library can retain text from its actually imported private original. */
    private fun importedDraftFile(pdfPage: Boolean = false, allowWarmPdf: Boolean = false): JSONObject? {
        val state = flow.state
        val file = state.file ?: return null
        val visit = state.globalStandaloneDraftVisit ?: return null
        if (!(keepsDraftLibraryVisit(visit) || pdfPage && allowWarmPdf && warmPdfDraftSurface(state)) ||
            controller.conversationReader != null || controller.state.filePath != null ||
            state.originalReturn != dev.pocketengine.ui.AttachmentOriginalReturn.LIST || state.originalFile == null || state.fromWorkResult ||
            state.restoredWorkReturn || state.libraryDocumentReturn != null || state.snapshot?.opt("taskId") != "" ||
            state.busy || flow.exportPending || state.pendingDocumentTask != null || state.documentDraft != null ||
            state.documentOpen != pdfPage || pdfPage && file.opt("mimeType") != "application/pdf" ||
            state.processingSource != null || state.comparison != null || controller.state.attachmentImporting ||
            file.opt("role") != "source" || file.opt("sourceKind") != "content_uri" || file.has("sourceTaskId") ||
            file.opt("availability") != "available") return null
        val listed = state.snapshot?.optJSONArray("sources").objects().singleOrNull { it.opt("id") == file.opt("id") }
        return file.takeIf { listed?.opt("sha256") == it.opt("sha256") && listed?.opt("role") == "source" &&
            listed?.opt("sourceKind") == "content_uri" && listed?.opt("availability") == "available" }
    }
    private fun retainsDraftText(seed: TextSeed): Boolean {
        val state = flow.state
        return when (seed) {
            is TextSeed.Markdown -> state.originalFile === seed.original.source && viewer.retainsMarkdownSource(seed.original)
            is TextSeed.PdfPage -> state.file === seed.file && state.originalFile === seed.source &&
                state.documentOpen && state.document === seed.read && state.pdfPageText == seed.page &&
                state.document?.opt("readReceiptId") == seed.receiptId && viewer.displayedPdfPage(state.file) == seed.page
        }
    }
    private fun currentDraftTextSource(windowId: String, seed: TextSeed): Boolean {
        val pdf = seed as? TextSeed.PdfPage
        val warmOwner = pdf?.warmOwnerCurrent
        // Losing authority never grants a fresh attempt on an otherwise unchanged visible receipt.
        return window?.id == windowId && warmOwner?.invoke() != false &&
            importedDraftFile(pdf != null, warmOwner != null) != null && retainsDraftText(seed)
    }
    private fun markdownDraftSeed(file: JSONObject): TextSeed.Markdown? {
        val attempted = if (drafts.isInitialized()) drafts.value.textSource() as? TextSeed.Markdown else null
        if (attempted != null && retainsDraftText(attempted)) return attempted
        return viewer.markdownDraftSeed(file, flow.state.originalFile)?.let { TextSeed.Markdown(it) }
    }
    private fun pdfPageDraftSeed(file: JSONObject, warmOwner: (() -> Boolean)? = null): TextSeed.PdfPage? {
        val attempt = if (drafts.isInitialized()) drafts.value.textSource() as? TextSeed.PdfPage else null
        if (attempt != null && retainsDraftText(attempt)) return attempt
        val state = flow.state
        val source = state.originalFile ?: return null
        val page = state.pdfPageText?.takeIf { viewer.displayedPdfPage(file) == it } ?: return null
        val read = state.document?.takeIf { flow.canSaveRead() } ?: return null
        val receipt = read.opt("readReceiptId") as? String ?: return null
        return runCatching {
            check(UUID.fromString(receipt).toString() == receipt && source.mimeType == "application/pdf" &&
                source.sha256 == file.opt("sha256") && source.sizeBytes == file.optLong("size", -1L))
            val meta = source.metadata()
            val origin = meta.getJSONObject("source")
            check(meta.opt("id") == file.opt("id") && meta.opt("principalId") == "owner" && meta.opt("spaceId") == "private" &&
                !meta.optBoolean("outputSourceRetired") && !meta.has("groupSource") && origin.opt("kind") == "content_uri" &&
                !origin.has("archiveEvidence") && !origin.has("groupSpaceId") && origin.optString("taskId").isEmpty() &&
                origin.optString("projectId").isEmpty())
            val total = read.opt("totalUnits") as? Int ?: return@runCatching null
            check(page.page in 1..total && read.opt("extractionStatus") == "partial")
            // A range cap inside this page must not silently become a page-length draft.
            // A cursor on the next page is normal for the existing one-page text-layer read.
            val next = if (page.page < total) page.page + 1 else 0
            check(read.opt("nextUnit") == next && read.opt("nextChunk") == 0 &&
                read.opt("truncated") == (next > 0) && read.opt("status") == (if (next > 0) "truncated" else "partial"))
            val chunks = read.getJSONArray("chunks").objects()
            check(chunks.size in 1..48)
            val body = StringBuilder()
            var block = 0
            var at = 0
            chunks.forEachIndexed { index, chunk ->
                val locator = chunk.getJSONObject("locator")
                val nextBlock = locator.getString("textBlock").toInt()
                val start = locator.getString("charStart").toInt()
                val end = locator.getString("charEnd").toInt()
                val text = chunk.getString("text")
                check(chunk.opt("chunk") == index && chunk.opt("kind") == "pdf_text" &&
                    locator.opt("page") == page.page.toString() && locator.opt("coordinateUnit") == "pdf_points" &&
                    nextBlock > 0 && text.length in 1..1024 && start >= 0 && end - start == text.length)
                if (nextBlock != block) {
                    check(nextBlock > block && start == 0)
                    if (block > 0) body.append('\n')
                    block = nextBlock
                } else check(start == at)
                body.append(text)
                at = end
                check(body.length <= 12_000)
            }
            check(body.isNotBlank())
            val name = source.name.substringBeforeLast('.').trim().take(60).trimEnd { Character.isHighSurrogate(it) }
                .ifBlank { "PDF" } + " 第 ${page.page} 页"
            TextSeed.PdfPage(page, source, file, read, receipt, Fields(Format.WORD, name, "", body.toString(), 12), warmOwner)
        }.getOrNull()
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
        val state = flow.state
        val visit = globalDraftLibrary
        val previous = window
        if (!keepPresentation) retiredSavedDraft = null
        else if (visit != null) {
            retiredSavedDraft = if (previous != null && state.globalStandaloneDraftVisit == visit.id && drafts.isInitialized() &&
                state.file == null && !state.busy && !flow.exportPending) {
                drafts.value.warmEditor(previous.id,previous.ticket,state.pendingDocumentTask)?.let { RetiredSavedDraft(visit.id,previous.id,it) }
            } else null
        } else if (retiredSavedDraft?.let { it.library != state.globalStandaloneDraftVisit || it.editor.pendingDocument != state.pendingDocumentTask } == true)
            retiredSavedDraft = null
        globalDraftLibrary = null
        quotedDraft = null
        warmDraftCheck = null
        draftDocumentReview = null
        generatedDraftReturn = null
        if (drafts.isInitialized()) {
            drafts.value.retire()
            drafts.value.forgetRecentText()
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
    /** The native acknowledged page in the exact original Work. Pairing does not reopen a page. */
    private fun workPdfPin(state: AttachmentOutcomeState, pairedText: Boolean = false): WorkPdfPin? {
        if (!state.open || flow.exportPending || !state.fromWorkResult || state.restoredWorkReturn ||
            state.originalReturn != AttachmentOriginalReturn.ENTRY || state.resultRun != null || scopedResultCurrent != null ||
            state.deviceOriginalOnly || state.group != null || state.team != null || state.choosingSource ||
            state.documentDraft != null || state.processingSource != null || state.comparison != null ||
            !controller.webPrivateOwnerVisible() || controller.conversationReader != null || controller.state.chatAttachmentPickerOpen) return null
        if (pairedText) {
            if (!state.documentOpen || state.pdfPageText == null) return null
        } else if (state.busy || state.documentOpen || state.pdfPageText != null) return null
        val file = state.file ?: return null
        val source = state.originalFile ?: return null
        val work = state.workReturn ?: return null
        val snapshot = state.snapshot ?: return null
        if (state.taskId != work.run.taskId || !ownsOrdinaryWork(work.run.taskId) ||
            source.mimeType != "application/pdf" || file.opt("mimeType") != source.mimeType ||
            file.opt("sourceKind") != "created_document" || file.opt("sourceTaskId") != work.run.taskId ||
            file.opt("sourceTaskRunId") != work.run.runId || source.sha256 != work.sha256 ||
            file.optLong("size", -1L) != source.sizeBytes || !work.matchesResult(snapshot, file)) return null
        val task = controller.state.tasks.singleOrNull { it.id == work.run.taskId }?.raw ?: return null
        if (task.opt("taskProjection") != "detail_window" || task.opt("taskCoreReadLimited") != false || task.optBoolean("cancelRequested") ||
            !task.isNull("parentTaskId") || !task.isNull("teamRunId") || !task.isNull("memberId")) return null
        val exact = if (snapshot.opt("taskStatus") == "completed") work
            else work.forRecordedDocument(snapshot, file)?.takeIf { it.matchesVerifiedDocument(file, source) } ?: return null
        if (!exact.matchesTask(task)) return null
        return viewer.displayedPdfPage(file)?.takeIf { !pairedText || it==state.pdfPageText }?.let { WorkPdfPin(exact, it) }
    }
    private fun workPdfShare(state: AttachmentOutcomeState)=workPdfPin(state)
    /** Text reuse is narrower than sharing: an ordinary completed private Work only. */
    private fun workPdfText(state: AttachmentOutcomeState,
        held: WorkPdfPin? = workPdfPin(state, pairedText = state.pdfPageText != null)): WorkPdfPin? {
        val pin=held?:return null
        if (state.snapshot?.opt("taskStatus")!="completed" || state.snapshot?.optJSONObject("documentCapabilities")?.opt("pdfText")!=true)
            return null
        val task=controller.state.tasks.singleOrNull { it.id==pin.work.run.taskId }?.raw?:return null
        return pin.takeIf { task.opt("status")=="completed" && task.opt("kind")=="assistant" && task.opt("executionRole")=="owner_work" &&
            task.opt("contextAvailable")==true && task.optString("conversationId").isNotBlank() &&
            listOf("origin","developmentContext","ownerPhoneControl","ownerDeviceWork","teamAssignment").all(task::isNull) }
    }
    fun openOriginalFromDevice(file:dev.pocketengine.ui.ReceivedFileSelection,current:()->Boolean){
        check(controller.webUiCanInteract() && controller.webPrivateOwnerVisible() && current())
        clear();deviceTaskCurrent=current
        flow.openReceivedOriginal(file,current);changed()
    }
    /** Only the original live message may hold this read-only viewer window. */
    fun openMessageInput(file:UiChatAttachment,kind:AttachmentMessageInput,ticket:PocketWebWindowTicket,current:()->Boolean):JSONObject =
        openMessageOriginal(ticket,current){flow.openMessageInput(file,kind,it)}
    fun openReplyDocument(file:UiChatAttachment,
        target:OriginalReplyDocument.Target,ticket:PocketWebWindowTicket,current:()->Boolean):JSONObject {
        val opened=openMessageOriginal(ticket,current){flow.openReplyDocument(file,target,it)}
        // Ordinary TXT is a read-only handoff. The pre-existing Word/PDF picker does not acquire it.
        if(file.mimeType!="text/plain")replyDocumentVisit=ReplyDocumentVisit(checkNotNull(window),file,target)
        return opened
    }
    /** A Web frame selects only the native reader it is currently showing, never a file ID. */
    fun replyAttachment(windowId:String,frameId:String,viewerId:String):ReplyAttachment? {
        val visit=replyDocumentVisit?:return null
        val frame=frames[frameId]?:return null
        val source=flow.state.originalFile?:return null
        if(visit.window.id!=windowId)return null
        fun owns():Boolean {
            val live=flow.state
            return replyDocumentVisit===visit&&window===visit.window&&visit.window.accepts(visit.window.ticket)&&
                live.open&&live.messageReplyDocumentOnly&&!live.busy&&!flow.exportPending&&live.originalFile===source&&
                source.sha256==visit.file.sha256&&source.mimeType==visit.file.mimeType&&source.sizeBytes==visit.file.size&&
                live.file?.opt("id")==visit.file.id&&frame.location==location(live)
        }
        fun ready():Boolean {
            val rendered=viewer.snapshot()?:return false
            return owns()&&viewer.matchesOriginal(flow.state.file)&&rendered.opt("id")==viewerId&&
                rendered.opt("sourceUnavailable")==false&&rendered.opt("busy")==false&&rendered.isNull("error")
        }
        return ReplyAttachment(visit.file,visit.target,::owns,::ready){
            if(!ready())false else { clear();!flow.state.open }
        }.takeIf{ready()}
    }
    fun openSearchAnchorFile(file:UiChatAttachment,readerWindow:Long,ticket:PocketWebWindowTicket,current:()->Boolean,
        read:(dev.pocketengine.core.RunControl)->dev.pocketengine.features.attachments.OwnerFilePreviewSource):JSONObject =
        openMessageOriginal(ticket,current,searchAnchorReader=readerWindow){flow.openSearchAnchorFile(file,it,read)}

    private fun openMessageOriginal(ticket:PocketWebWindowTicket,current:()->Boolean,searchAnchorReader:Long?=null,
        start:(()->Boolean)->Unit):JSONObject {
        check(!flow.state.open&&!flow.exportPending&&controller.webPrivateOwnerVisible()&&current())
        clear()
        val active=Window(UUID.randomUUID().toString(),ticket,{ candidate -> candidate==ticket&&current() },searchAnchorReader).also { window=it }
        fun bound()=window===active&&active.accepts(active.ticket)
        scopedResultCurrent=::bound
        try { start(::bound) } catch (failure:Exception) { clear();throw failure }
        return JSONObject().put("opened",true).put("reading",true).put("readOnly",true).put("windowId",active.id)
    }

    /** Exact native owner message locator, metadata landing only. */
    fun openSavedChatLinkedDetails(file:JSONObject,current:()->Boolean,
        openOriginal:(dev.pocketengine.core.RunControl)->dev.pocketengine.features.attachments.OwnerFilePreviewSource):JSONObject {
        check(!flow.state.open&&!flow.exportPending&&controller.webPrivateOwnerVisible()&&current())
        clear()
        scopedResultCurrent=current
        savedChatLinkOpen=openOriginal
        try {
            flow.openSavedChatLinkedDetails(file)
            return JSONObject().put("opened",true).put("windowId",activeWindow().id)
        } catch(failure:Exception) { clear();throw failure }
    }
    fun closeSavedChatLinkedDetails(id:String) {
        if(window?.id==id&&flow.state.savedChatLinkedOnly)clear()
    }
    /** Explicit owner click from the immediately preceding positive message-to-draft save. */
    fun openQuotedDraft(pin: Pin, owner: () -> Boolean): JSONObject {
        check(!flow.state.open && !flow.exportPending && controller.webPrivateOwnerVisible() && owner())
        clear()
        val library = DraftLibraryVisit(UUID.randomUUID().toString(), owner)
        flow.openStandaloneDraftFocus(library.id)
        globalDraftLibrary = library
        val active = activeWindow()
        quotedDraft = QuotedDraft(active, library, pin)
        return JSONObject().put("opened", true).put("library", library.id).put("windowId", active.id)
    }
    fun snapshot(draftPresentation: Boolean = false, workPagePresentation: Boolean = false): JSONObject {
        if(flow.state.deviceOriginalOnly && deviceTaskCurrent?.invoke()!=true ||
            flow.state.liveMessageOriginalOnly&&scopedResultCurrent==null || scopedResultCurrent?.invoke()==false)clear()
        flow.state.pdfPageText?.let { page ->
            if(!(libraryPdfSurface(flow.state,allowPageText=true)||workPdfText(flow.state)?.page==page) ||
                viewer.displayedPdfPage(flow.state.file)!=page)flow.backFromDocument()
        }
        flow.state.wordExcerpt?.let { excerpt ->
            if(displayedWordExcerpt(flow.state,paired=true)!=excerpt)flow.backFromDocument()
        }
        val state = flow.state
        if (state.focusedStandaloneDraft && globalDraftLibrary?.let { it.id == state.globalStandaloneDraftVisit && it.current() } != true) {
            clear()
            return JSONObject().put("open", false)
        }
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
        val selectedPythonText = OwnerPythonOutputText.resultRow(state.file)
        fun fixedLocalResult(file: JSONObject) = OriginalNodeCompilerDelivery.resultRow(file) || OwnerPythonOutputText.resultRow(file)
        val scopedSnapshot = state.snapshot?.let { existing ->
            val artifacts = if (state.choosingSource) existing.optJSONArray("artifacts").objects() else emptyList()
            if (artifacts.any(::fixedLocalResult))
                JSONObject(existing.toString()).put("artifacts", JSONArray(artifacts.filterNot(::fixedLocalResult)))
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
        val wordOrigin=state.wordSourceVisit?.takeIf{it.source===source&&wordLibrarySurface(state)}?.origin
        viewer.bind(source, state.comparison, current.ticket, rememberPdf,wordOrigin,rememberPosition=current.searchAnchorReader==null) { ticket ->
            current.accepts(ticket) && (!state.deviceOriginalOnly || deviceTaskCurrent?.invoke()==true) &&
                (if (state.savedChatLinkedOnly) window === current && scoped?.invoke()==true
                 else if (state.liveMessageOriginalOnly) window === current else scoped?.invoke()!=false)
        }
        val displayedPdfPage = if (libraryPdf && !state.busy && !flow.exportPending &&
            state.snapshot?.optJSONObject("documentCapabilities")?.opt("pdfText")==true)
            viewer.displayedPdfPage(state.file) else null
        val sourceDraftReturn = generatedDraftReturn?.let { origin ->
            val output = currentGeneratedDraftOutput(origin)
            if (!ownsGeneratedDraftReturn(origin) ||
                (origin.landing != null && (state.snapshot !== origin.landing || state.file != null)) ||
                (origin.output == null && output == null && state.pendingDocumentTask != origin.action))
                generatedDraftReturn = null
            output?.takeIf { draftPresentation && !state.busy && !flow.exportPending &&
                (state.originalFile == null || viewer.matchesOriginal(state.file)) }?.let {
                JSONObject().put("handle", origin.handle).put("library", origin.library).put("fileId", it.first).put("sha256", it.second)
            }
        }
        val frameId = UUID.randomUUID().toString()
        val word=if(draftPresentation&&!state.busy)displayedWordExcerpt(state) else null
        val sharePdf = workPdfShare(state)
        // A Work text frame belongs to full owner state, never to an action receipt.
        val workPagePaired = state.pdfPageText != null && state.taskId != null
        val workText = if (workPagePresentation && state.pdfPageText == null) workPdfText(state, sharePdf) else null
        val pairedWorkText = workPagePresentation && workPagePaired && workPdfText(state)?.page == state.pdfPageText
        frames[frameId] = Frame(location(state), files, libraryPdfResume, displayedPdfPage,word,sharePdf,workText)
        while (frames.size > 16) frames.remove(frames.keys.first())
        if (drafts.isInitialized()) drafts.value.textSource()?.let { attempted ->
            if (!retainsDraftText(attempted)) drafts.value.retireText()
        }
        val markdown = if (draftPresentation) importedDraftFile()?.let(::markdownDraftSeed) else null
        val pdfDraft = if (draftPresentation) importedDraftFile(pdfPage = true, allowWarmPdf = true)?.let {
            pdfPageDraftSeed(it)
        } else null
        fun draftTextIndicator(seed: TextSeed?): JSONObject? = seed?.let {
            val retained = if (drafts.isInitialized() && drafts.value.textSource() === seed) drafts.value.textRetention() else null
            JSONObject().put("name", seed.fields.name()).put("state", retained?.state?.name ?: "READY").put("savedAt", retained?.savedAt)
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
        val continuationDraft=if(!selectedCompiler&&!selectedPythonText&&scoped==null&&!state.deviceOriginalOnly&&state.group==null&&state.team==null&&state.taskId!=null&&state.file!=null&&controller.webPrivateOwnerVisible())runCatching{
            controller.readArtifactContinuationDraft(state.taskId,state.file.getString("id"),state.file.getString("sha256")).put("available",true)
        }.getOrElse{JSONObject().put("available",false).put("error",it.message?:"原成果补充草稿尚未读取")} else null
        val privateOriginal = !state.deviceOriginalOnly && state.group == null && state.team == null && viewer.matchesOriginal(state.file)
        val ownedCopy= !selectedPythonText && scoped == null && !state.deviceOriginalOnly && state.group == null && state.team == null && controller.webPrivateOwnerVisible()
        val copyReceipt=if(ownedCopy)flow.privateCopyReceipt(privateOriginal) else null
        val ordinaryLibrary=ordinaryLibrary()
        val readableLibrarySources=if(ordinaryLibrary&&state.file==null)JSONArray(files.filter{PocketWebOriginalFiles.libraryDocument(it)}
            .map{JSONObject().put("id",it.getString("id")).put("sha256",it.getString("sha256"))}) else null
        // Action snapshots use the default false gates. A still valid Work page
        // stays mounted, but no page or body goes into this ACK or its replay.
        val shownPdfPageText=state.pdfPageText?.takeIf{if(workPagePaired)pairedWorkText else draftPresentation}
        val shownWord=state.wordExcerpt?.takeIf{draftPresentation}
        val exposeDocument=(state.pdfPageText==null||shownPdfPageText!=null)&&(state.wordExcerpt==null||shownWord!=null)
        return JSONObject().put("open", true).put("ready", true).put("windowId", current.id).put("frameId", frameId)
            .put("searchAnchorReader", current.searchAnchorReader)
            .put("historyResultOnly", scoped != null && !report && !state.liveMessageOriginalOnly).put("reportResultOnly", report)
            .put("messagePdfOnly",state.messageInput==AttachmentMessageInput.PDF)
            .put("messageInputDocumentOnly",state.messageInput==AttachmentMessageInput.WORD_OR_TEXT)
            .put("messageReplyDocumentOnly",state.messageReplyDocumentOnly)
            .put("savedChatLinkedOnly",state.savedChatLinkedOnly)
            .put("standaloneDraftLibrary", mayUseDraftLibrary())
            .put("focusedStandaloneDraft", state.focusedStandaloneDraft)
            .put("canRetainLinkedDocumentDraft", linkedEditorSource() != null)
            .put("warmSavedDraft", if (draftPresentation) warmSavedDraft(current)?.let {
                JSONObject().put("library",it.library).put("previousWindow",it.previousWindow).put("key",it.editor.key)
            } else null)
            .put("generatedDraftReturn", sourceDraftReturn)
            .put("markdownDraft", draftTextIndicator(markdown)).put("pdfPageDraft", draftTextIndicator(pdfDraft))
            // This ordinary state projection may match a retained Web pane, never a draft command or handle.
            .put("standaloneDraftLibraryVisit", flow.state.globalStandaloneDraftVisit?.takeIf {
                draftPresentation && ordinaryDraftLibrarySurface(state.focusedStandaloneDraft)
            })
            .put("canOpenReportOriginalWork", canNavigateFromReport(state))
            .put("libraryDocumentActionId", if(ordinaryLibrary)flow.libraryDocumentActionId() else null)
            .put("libraryPdfResume", libraryPdfResume?.view())
            .put("returnToLibraryDocument", ownedCopy && flow.canReturnToLibraryDocument())
            .put("canReferenceHistoryOriginal", !selectedCompiler && !selectedPythonText && scoped != null && !report && !state.liveMessageOriginalOnly && privateOriginal)
            .put("canSaveOriginal", !selectedPythonText && scoped == null && privateOriginal)
            .put("canShareWorkPdf", sharePdf != null)
            .put("canOpenInReader", !selectedCompiler && !selectedPythonText && scoped == null && privateOriginal && viewer.canOpenInReader(state.file))
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
            .put("librarySystemShare",if(draftPresentation&&ordinaryLibrary&&state.file==null&&state.originalFile==null&&
                !state.documentOpen&&(!drafts.isInitialized()||!drafts.value.showing()))flow.librarySystemShare?.toJson() else null)
            .put("canRequestOcr", state.pdfPageText==null&&flow.canRequestOcr()).put("canSaveRead", exposeDocument&&flow.canSaveRead())
            .put("canReadTeamDifference", state.team != null && flow.canReadTeamDifference())
            .put("teamDifference", state.teamDifference).put("hasPreviousTeamArtifact", state.teamPrevious != null)
            .put("originalFile", viewer.snapshot()).put("originalReturn", state.originalReturn.value).put("comparison", viewer.comparisonSnapshot())
            .put("canReadPdfPageText", displayedPdfPage!=null||workText!=null)
            .put("workPdfTextPage", workText!=null||pairedWorkText)
            .put("canReadWordExcerpt", word!=null)
            .put("wordExcerpt",shownWord?.view())
            .put("wordExcerptSaveAttempted",shownWord!=null&&state.wordSaveAttempted)
            .put("canOpenWordExcerptSource",draftPresentation&&wordLibrarySurface(state)&&flow.libraryWordExcerpt()!=null)
            .put("wordExcerptSourceOpen",wordOrigin!=null)
            .put("pdfPageText", shownPdfPageText?.let { JSONObject().put("viewerId",it.viewerId).put("rasterId",it.rasterId).put("page",it.page) })
    }

    fun dispatch(action: String, args: JSONObject, resultRun: AttachmentResultRun? = null,
                 readerCurrent: (() -> Boolean)? = null,
                 restoreWork: ((AttachmentWorkReturn, () -> Boolean, () -> Unit) -> Unit)? = null,
                 draftLibraryEntry: (() -> Boolean)? = null,
                 done: (JSONObject?, String?) -> Unit) {
        try {
            check(controller.webUiCanInteract()) { "请回到当前工作区再操作文件" }
            if (flow.state.focusedStandaloneDraft) {
                check(action == "attachmentWorkspace.action" && args.length() == 3 &&
                    args.optString("command") in setOf("back", "close", "cancel", "queryDocument")) { "请先离开这份原草稿" }
            }
            if(flow.state.deviceOriginalOnly){
                check(deviceTaskCurrent?.invoke()==true && controller.webPrivateOwnerVisible()){"原文件所属的工作窗口已变化"}
                check(action=="attachmentWorkspace.viewer" || action=="attachmentWorkspace.action" && args.optString("command") in setOf("back","close","cancel","continueTask")){"从此入口只查看这一个原文件，请先回原工作"}
            }
            if(flow.state.liveMessageOriginalOnly)check(scopedResultCurrent!=null){"原消息阅读窗口已关闭"}
            scopedResultCurrent?.let { current ->
                check(current()) { "原成果阅读窗口已变化，请重新打开这次成果" }
                val command = args.optString("command")
                val reads=when {
                    flow.state.savedChatLinkedOnly->setOf("back","close","cancel","openOriginal")
                    flow.state.liveMessageOriginalOnly->setOf("back","close","cancel")
                    else->SCOPED_RESULT_READS
                }
                check(action == "attachmentWorkspace.viewer" || action == "attachmentWorkspace.action" &&
                    (command in reads || !flow.state.liveMessageOriginalOnly && command == REPORT_WORK_NAVIGATION && canNavigateFromReport(flow.state))) {
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
                val opened = JSONObject().put("opened", true).put("reading", true)
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
                    // Identity of the accepted private no-locator file window, not a read grant.
                    if (visit != null && flow.state.open && flow.state.globalStandaloneDraftVisit == visit.id && visit.current())
                        opened.put("windowId", activeWindow().id)
                }
                done(opened, null); changed(); return
            }
            val current = window ?: error("文件窗口已关闭，请重新打开")
            check(args.optString("windowId") == current.id && current.accepts(current.ticket)) { "原文件窗口已变化，请重新核对" }
            check(flow.state.open) { "原文件窗口已关闭" }
            quotedDraft = null // Any new ordinary file action leaves the one-shot original draft landing.
            if (action == "attachmentWorkspace.viewer") {
                check(flow.state.pdfPageText==null&&flow.state.wordExcerpt==null) { "请先返回原件阅读" }
                viewer.dispatch(args, done); return
            }
            check(action == "attachmentWorkspace.action") { "不支持这项文件操作" }
            val frame = frames[args.getString("frameId")] ?: error("原文件页面已过期，请重新核对")
            check(frame.location == location(flow.state)) { "当前来源或编辑内容已变化，请重新核对" }
            val command = args.getString("command")
            retiredSavedDraft = null // A different explicit use of the Library retires the old editor locator.
            val pairedPdf = flow.state.pdfPageText
            val pairedWord=flow.state.wordExcerpt
            if(pairedPdf!=null&&flow.state.taskId!=null)check(command in setOf("back","close","cancel")) { "请先返回原 PDF" }
            else if(pairedPdf!=null||pairedWord!=null) check(command in setOf("back","close","cancel","saveRead")||
                pairedPdf!=null&&command=="openOriginal") { "请先返回原件阅读" }
            if (OriginalNodeCompilerDelivery.resultRow(flow.state.file)) {
                check(command in setOf("close", "back", "cancel", "refresh", "selectFile", "openOriginal", "originalDetails", "export", REPORT_WORK_NAVIGATION)) {
                    "此固定编译输出仅在本机查看或另存"
                }
            }
            if (OwnerPythonOutputText.resultRow(flow.state.file)) {
                check(command in setOf("close", "back", "cancel", "refresh", "selectFile", "openOriginal", "originalDetails")) {
                    "Python 输出 TXT 仅在原工作或私人文件库内查看"
                }
            }
            if (drafts.isInitialized() && drafts.value.showing()) {
                check(command == "back" || command == "close") { "请先返回私人文件库" }
                warmDraftCheck = null
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
                "back" -> if(pairedPdf!=null||pairedWord!=null)flow.backFromDocument() else if (flow.needsRestoredWorkReturn()) {
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
                        flow.state.savedChatLinkedOnly&&flow.state.originalFile!=null->flow.backFromOriginalFile()
                        flow.state.deviceOriginalOnly||flow.state.liveMessageOriginalOnly -> {clear();if(flow.state.open)flow.close()}
                        flow.state.comparison != null -> flow.closeComparison()
                        flow.state.processingSource != null -> flow.closeProcessing()
                        flow.state.documentDraft != null -> flow.closeDocumentDraft()
                        flow.state.wordSourceVisit != null -> flow.backFromWordExcerptSource()
                        flow.state.originalFile != null -> flow.backFromOriginalFile()
                        flow.state.documentOpen -> flow.backFromDocument()
                        flow.state.file != null -> flow.backFromFile()
                        else -> if (!controller.webPrivateOwnerVisible() || !flow.backToLibraryDocument()) flow.close()
                    }
                }
                "refresh" -> flow.refresh()
                "cancel" -> if(flow.state.liveMessageOriginalOnly)clear()else flow.cancel()
                "selectFile" -> {
                    val file = selected()
                    val view = flow.state
                    if (ordinaryLibrary() && view.snapshot?.opt("taskId") == "" &&
                        !view.fromWorkResult && !view.restoredWorkReturn)
                        flow.selectWebLibraryDetails(file)
                    else flow.selectFile(file)
                }
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
                "queryDocument" -> flow.queryDocument(::keepsDraftLibraryVisit) {
                    val live = flow.state
                    draftLibraryEntry?.invoke() == true && window === current && acceptsWindow(current.ticket) &&
                        frame.location == location(live) && (if (live.focusedStandaloneDraft) mayUseDraftLibrary()
                        else ordinaryLibrary() && !flow.exportPending && live.snapshot?.opt("taskId") == "" &&
                            live.file == null && live.originalFile == null && live.documentDraft == null && !live.documentOpen &&
                            live.processingSource == null && live.comparison == null && live.libraryDocumentReturn == null &&
                            !live.fromWorkResult && !live.restoredWorkReturn && !controller.state.attachmentImporting &&
                            live.libraryImport?.phase !in setOf("selecting", "selection_unconfirmed", "registering"))
                }
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
                "read" -> if(args.has("wordViewerId")||args.has("displayedWordUnit")||args.has("displayedWordChunk")){
                    val shown=frame.displayedWord
                    val original=flow.state.originalFile
                    val file=flow.state.file
                    check(args.length()==6&&shown!=null&&original!=null&&file!=null&&shown.viewerId==args.opt("wordViewerId")&&
                        shown.unit==args.opt("displayedWordUnit")&&shown.chunk==args.opt("displayedWordChunk")) {
                        "当前未核对到这份 Word 的原段落，没有读取其他文字"
                    }
                    fun stillOnWord()=window===current&&acceptsWindow(current.ticket)&&flow.state.originalFile===original&&
                        flow.state.file===file&&displayedWordExcerpt(flow.state,paired=true)==shown
                    flow.readDisplayedWordExcerpt(shown,::stillOnWord)
                }else if(args.has("displayedPdfRaster")||args.has("pdfViewerId")){
                    val shownWork=frame.workPdfText
                    val shown=shownWork?.page?:frame.displayedPdfPage
                    val original=flow.state.originalFile
                    val file=flow.state.file
                    check(args.length()==5 && shown!=null && original!=null && file!=null &&
                        shown.viewerId==args.opt("pdfViewerId") && shown.rasterId==args.opt("displayedPdfRaster")) {
                        "当前未核对到这份 PDF 的原页，未读取其他页面"
                    }
                    fun stillOnPage():Boolean {
                        val live=flow.state
                        return window===current && acceptsWindow(current.ticket) && live.originalFile===original &&
                            live.file===file && viewer.displayedPdfPage(file)==shown &&
                            (if(shownWork==null)libraryPdfSurface(live,allowPageText=true) else workPdfText(live)==shownWork)
                    }
                    if(shownWork==null)flow.readDisplayedPdfPage(shown,::stillOnPage)
                    else flow.readDisplayedWorkPdfPage(shownWork.work,shown,::stillOnPage)
                }else flow.read(DocumentContent.Request(args.optInt("unit", 1), args.optInt("chunk", 0), 4, 12000, 48, "text"))
                "ocr" -> flow.readImageText()
                "saveRead" -> if(pairedWord!=null){
                    val file=flow.state.file
                    val original=flow.state.originalFile
                    fun stillOnWord()=window===current&&acceptsWindow(current.ticket)&&flow.state.wordExcerpt===pairedWord&&
                        flow.state.originalFile===original&&flow.state.file===file&&displayedWordExcerpt(flow.state,paired=true)==pairedWord
                    flow.saveReadAsFile(currentWord=::stillOnWord)
                }else if(pairedPdf==null)flow.saveReadAsFile() else {
                    val file=flow.state.file
                    val original=flow.state.originalFile
                    fun stillOnPage() = window===current && acceptsWindow(current.ticket) &&
                        libraryPdfSurface(flow.state,allowPageText=true) && flow.state.pdfPageText==pairedPdf &&
                        original!=null && flow.state.originalFile===original && flow.state.file===file && viewer.displayedPdfPage(file)==pairedPdf
                    flow.saveReadAsFile(::stillOnPage)
                }
                "originalDetails" -> { flow.showOriginalDetails(); viewer.clear() }
                "openOriginal" -> if(flow.state.savedChatLinkedOnly){
                    check(args.length()==3&&flow.state.originalFile==null) { "请在文件信息中明确查看这份原件" }
                    val open=checkNotNull(savedChatLinkOpen)
                    val file=flow.state.file
                    fun stillHere()=window===current&&acceptsWindow(current.ticket)&&flow.state.file===file&&scopedResultCurrent?.invoke()==true
                    flow.openOriginalFile{control->
                        control.setAuthorityCheck { check(stillHere()) }
                        check(stillHere())
                        val original=open(control)
                        check(stillHere())
                        original
                    }
                }else if(pairedPdf!=null){
                    val saved=flow.savedPdfPageExtract(pairedPdf)?:error("本页保存的 TXT 尚未核对")
                    check(args.length()==5&&libraryPdfSurface(flow.state,allowPageText=true)&&!flow.exportPending&&
                        viewer.displayedPdfPage(flow.state.file)==pairedPdf&&args.opt("fileId")==saved.opt("id")&&
                        args.opt("sha256")==saved.opt("sha256")){"只能打开本页刚核对保存的 TXT"}
                    val file=selected()
                    flow.backFromDocument()
                    flow.selectFile(file)
                    flow.openOriginalFile()
                }else if(args.has("wordExcerptSource")){
                    check(args.length()==4&&args.opt("wordExcerptSource")==true&&wordLibrarySurface(flow.state)&&
                        flow.libraryWordExcerpt()!=null){"请从已核对的 Word 摘录打开原段落"}
                    val file=flow.state.file
                    flow.openLibraryWordExcerptSource{
                        window===current&&acceptsWindow(current.ticket)&&wordLibrarySurface(flow.state)&&flow.state.file===file
                    }
                }else {
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
                "share" -> {
                    val original = flow.state.originalFile
                    if (original == null) flow.shareOriginalFile() // Existing file-details menu.
                    else {
                        require(args.length() == 3) { "请在原 PDF 页面中选择分享" }
                        val shown = frame.workPdfShare ?: error("请先在原工作中打开并显示这份 PDF")
                        fun currentPdf() = window === current && acceptsWindow(current.ticket) &&
                            flow.state.originalFile === original && workPdfShare(flow.state) == shown
                        check(currentPdf()) { "原工作或 PDF 页面已变化，没有打开系统分享" }
                        frame.workPdfShare = null // Do not dispatch the same admitted frame twice.
                        flow.shareOriginalFile(::currentPdf)
                    }
                }
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
    fun dispatchStandaloneDraft(args: JSONObject, freshOwner: (() -> Boolean)?, done: (JSONObject?, String?) -> Unit) {
        if(flow.state.savedChatLinkedOnly){done(null,"这里仅查看原消息所关联的文件");return}
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
        fun documentInput(input: PocketWebStandaloneDocumentDrafts.GenerationInput, outputFormat: Format): JSONObject = content(input.fields).apply {
            // Select only this output; the saved editor's format and revision are never updated here.
            put("format", outputFormat.value)
            input.source?.let { source ->
                put("parentArtifactId", source.parentArtifactId().toString()).put("parentSha256", source.parentSha256())
                put("editableSourceId", source.editableSourceId().toString()).put("editableSourceSha256", source.editableSourceSha256())
            }
        }
        try {
            val frame = PocketWebStandaloneDocumentDrafts.Frame(text("windowId"), text("frameId"))
            val command = text("command")
            if (flow.state.focusedStandaloneDraft) check(command in FOCUSED_DRAFT_COMMANDS)
            if (command != "drafts.openQuotedSource") quotedDraft = null
            // A live draft shelf may edit a different storage record, never resume or replace the pending generation.
            check(flow.state.pendingDocumentTask == null || command in DRAFTS_WHILE_DOCUMENT_PENDING)
            check(warmDraftCheck == null || command == "drafts.leave")
            if (command == "drafts.resumeSaved") {
                exact("resumeKey")
                val active = window ?: error("原文件库窗口已离开")
                val owner = freshOwner ?: error("私人文件库窗口尚未就绪")
                val prior = warmSavedDraft(active)?.takeIf { it.editor.key == text("resumeKey") } ?: error("原草稿版本尚未核对")
                check(active.id == frame.windowId && acceptsWindow(active.ticket) && owner() &&
                    frames[frame.frameId]?.location == location(flow.state) && (!drafts.isInitialized() || !drafts.value.showing()))
                retiredSavedDraft = null // The key is an exact-address locator and is never again an editor authority.
                val admitted = DraftLibraryVisit(prior.library,owner).also { globalDraftLibrary = it; warmDraftCheck = it }
                val adapter = drafts.value
                fun retireFailedRead() {
                    // A late failure cannot clear a newer editor opened after leaving this check.
                    if (warmDraftCheck === admitted) {
                        warmDraftCheck = null
                        if (globalDraftLibrary === admitted) { adapter.retire(); globalDraftLibrary = null }
                    }
                }
                try {
                    adapter.resumeWarmEditor(frame,prior.editor) { opened ->
                        val verified = opened?.takeIf { warmDraftCheck === admitted && globalDraftLibrary === admitted && currentDraftFrame(frame) &&
                            it.state == State.VERIFIED && it.fields != null && it.edit != null && it.warmResumeKey != null &&
                            !it.sourceUnavailable && it.sourceName == null }
                        if (verified == null) { retireFailedRead(); reply(null) }
                        else {
                            warmDraftCheck = null
                            reply(JSONObject().put("status",verified.state.name).edit(verified.edit)
                                .put("fields",verified.fields?.let(::content)).put("warmResumeKey",verified.warmResumeKey)
                                .put("resumedKey",prior.editor.key).put("resumedWindow",frame.windowId))
                        }
                    }
                } catch (failure: Exception) { retireFailedRead(); throw failure }
                changed()
                return
            }
            if (command == "drafts.returnGenerated") {
                exact("returnHandle")
                val origin = generatedDraftReturn?.takeIf { it.handle == text("returnHandle") && it.window.id == frame.windowId }
                    ?: error("原草稿去向已离开")
                val result = currentGeneratedDraftOutput(origin) ?: error("没有核对到这次生成的同一原文件")
                val state = flow.state
                check(!state.busy && !flow.exportPending && frames[frame.frameId]?.location == location(state) &&
                    (state.originalFile == null || viewer.matchesOriginal(state.file)))
                origin.output = result // Consume the exact file-to-list action; never accept a replacement by name.
                viewer.clear()
                flow.backFromFile { refreshed ->
                    val catalog = flow.state.snapshot
                    if (refreshed && ownsGeneratedDraftReturn(origin) && mayUseDraftLibrary() && listsGeneratedDocument(origin, catalog)) {
                        origin.landing = catalog
                        reply(JSONObject().put("returned", true).put("returnHandle", origin.handle).put("windowId", origin.window.id)
                            .put("library", origin.library))
                    } else {
                        if (generatedDraftReturn === origin) generatedDraftReturn = null
                        reply(null)
                    }
                }
                changed()
                return
            }
            if (command == "drafts.openGeneratedSource") {
                exact("returnHandle")
                val origin = generatedDraftReturn?.takeIf {
                    it.handle == text("returnHandle") && it.landing != null && it.landing === flow.state.snapshot &&
                        ownsGeneratedDraftReturn(it) && listsGeneratedDocument(it, it.landing)
                } ?: error("没有核对到生成前的原草稿去向")
                check(currentDraftFrame(frame))
                generatedDraftReturn = null
                val adapter = drafts.value
                adapter.openPinnedSource(frame, origin.pin) { opened ->
                    val verified = opened?.takeIf { currentDraftFrame(frame) && it.state == State.VERIFIED &&
                        it.edit != null && it.fields != null && !it.sourceUnavailable }
                    if (verified == null) {
                        if (opened != null && currentDraftFrame(frame)) adapter.retire()
                        reply(null)
                    }
                    else reply(JSONObject().put("returnedSource", true).put("status", verified.state.name).edit(verified.edit)
                        .put("fields", verified.fields?.let(::content)).put("sourceName", verified.sourceName))
                }
                return
            }
            if (command == "drafts.openQuotedSource") {
                exact("library")
                check(flow.state.focusedStandaloneDraft)
                val origin = quotedDraft?.takeIf { it.window === window && it.window.id == frame.windowId &&
                    it.library === globalDraftLibrary && it.library.id == text("library") }
                    ?: error("原消息的草稿打开回执已离开")
                check(currentDraftFrame(frame))
                quotedDraft = null
                val adapter = drafts.value
                adapter.openPinnedSource(frame, origin.pin) { opened ->
                    val verified = opened?.takeIf { currentDraftFrame(frame) && it.state == State.VERIFIED &&
                        it.edit != null && it.fields != null && !it.sourceUnavailable && it.sourceName == null }
                    if (verified == null) reply(null)
                    else reply(JSONObject().put("returnedSource", true).put("status", verified.state.name).edit(verified.edit)
                        .put("library", origin.library.id).put("fields", verified.fields?.let(::content)))
                }
                return
            }
            if (command in setOf("drafts.retainMarkdown", "drafts.checkMarkdown", "drafts.retainPdfPage", "drafts.checkPdfPage")) {
                val pdfPage = command == "drafts.retainPdfPage" || command == "drafts.checkPdfPage"
                val warmOwner = if (pdfPage && globalDraftLibrary == null)
                    warmPdfDraftOwner(freshOwner ?: error("私人文件库窗口尚未就绪")) else null
                val file = importedDraftFile(pdfPage, allowWarmPdf = warmOwner != null)
                    ?: error("请在当前私人文件库查看原件后保留草稿")
                val seed = if (pdfPage) {
                    exact("viewerId", "rasterId", "fileId", "sha256", "readReceiptId")
                    val shown = pdfPageDraftSeed(file, warmOwner) ?: error("尚未核对这份 PDF 本页的文字层")
                    check(text("viewerId") == shown.page.viewerId && text("rasterId") == shown.page.rasterId &&
                        text("readReceiptId") == shown.receiptId && text("sha256") == shown.source.sha256)
                    shown
                } else {
                    exact("viewerId", "fileId", "sha256")
                    val shown = markdownDraftSeed(file) ?: error("请先完整读取这份 Markdown 原件")
                    check(text("viewerId") == shown.original.viewerId && text("sha256") == shown.original.source.sha256)
                    shown
                }
                check(currentDraftTextSource(frame.windowId, seed) && frames[frame.frameId]?.location == location(flow.state) &&
                    text("fileId") == file.opt("id"))
                drafts.value.retainText(frame, seed, command == "drafts.checkMarkdown" || command == "drafts.checkPdfPage") { retained ->
                    reply(retained?.takeIf { currentDraftTextSource(frame.windowId, seed) }?.let {
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
            val renewed = if (command == "drafts.list" && args.has("reenterVisit")) {
                exact("reenterVisit")
                val visit = flow.state.globalStandaloneDraftVisit ?: error("请重新打开私人文件库")
                val active = window ?: error("原文件库窗口已离开")
                val current = freshOwner ?: error("私人文件库窗口尚未就绪")
                // The native logical visit survives a warm picker, not a cold/other/document-replaced entry.
                // Keep its catalog and receipts. Only this explicit action grants a new shelf read on the current frame.
                check(visit == text("reenterVisit") && globalDraftLibrary == null && ordinaryDraftLibrarySurface() &&
                    active.id == frame.windowId && acceptsWindow(active.ticket) &&
                    frames[frame.frameId]?.location == location(flow.state) && current() &&
                    (!drafts.isInitialized() || !drafts.value.showing()))
                DraftLibraryVisit(visit, current).also { retiredSavedDraft = null; globalDraftLibrary = it; changed() }
            } else null
            check(currentDraftFrame(frame))
            val adapter = drafts.value
            fun editor() = PocketWebStandaloneDocumentDrafts.Edit(text("editorHandle"), text("versionHandle"))
            fun noGeneration(reason: String) = JSONObject().put("started", false).put("reason", reason)
            if (command != "drafts.createDocument" && command != "drafts.dismissDocument") draftDocumentReview = null
            when (command) {
                "drafts.list" -> { if (renewed == null) exact(); adapter.list(frame) { catalog ->
                    reply(catalog?.takeIf { renewed == null || globalDraftLibrary === renewed && currentDraftFrame(frame) }?.let {
                        JSONObject().put("metadataComplete", it.metadataComplete)
                        .apply { if (renewed != null) put("reenteredVisit", renewed.id).put("reenteredWindow", frame.windowId) }
                        .put("rows", JSONArray(it.rows.map { row -> JSONObject().put("rowHandle", row.rowHandle)
                            .put("name", row.name).put("format", row.format).put("savedAt", row.savedAt)
                            .also {
                                if (row.linkedRevision) it.put("linkedRevision", true)
                                row.recentTextOrigin?.let { origin -> it.put(origin.rowFlag, true) }
                            } })) })
                } }
                "drafts.new" -> { exact(); val new = adapter.newDraft(frame)
                    reply(JSONObject().put("editorHandle", new.editorHandle).put("fields", content(new.fields)))
                }
                "drafts.open" -> { exact("rowHandle"); adapter.open(frame, text("rowHandle")) { opened ->
                    reply(opened?.let { JSONObject().put("status", it.state.name).edit(it.edit)
                        .put("fields", it.fields?.let(::content)).put("sourceName", it.sourceName)
                        .put("warmResumeKey",it.warmResumeKey).sourceUnavailable(it.sourceUnavailable) })
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
                    exact("editorHandle", "versionHandle", "outputFormat")
                    val selected = editor()
                    val outputFormat = Format.values().singleOrNull { it.value == args.opt("outputFormat") }
                        ?: error("请选择本次生成 Word 或 PDF")
                    adapter.readGenerationDraft(frame, selected) { read ->
                        val result = if (read?.sourceUnavailable == true && currentDraftFrame(frame))
                            JSONObject().edit(selected).sourceUnavailable(true)
                        else read?.input?.takeIf { currentDraftFrame(frame) }?.let {
                            try {
                                val draft = DocumentDraft(documentInput(it, outputFormat))
                                val prepared = DraftDocumentReview(UUID.randomUUID().toString(), frame.windowId, selected,
                                    outputFormat, draft.identity, SystemClock.elapsedRealtime() + DOCUMENT_REVIEW_TTL)
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
                            val draft = runCatching { DocumentDraft(documentInput(input, prepared.outputFormat)) }.getOrNull()
                            if (draft == null || draft.identity != prepared.planDigest || SystemClock.elapsedRealtime() >= prepared.expires) {
                                noGeneration("已保留的草稿或本次核对已变化，请重新打开原稿。")
                            } else {
                                // This is the only entry to the existing creation operation; no draft body enters a reply.
                                val active = window
                                val library = globalDraftLibrary?.id
                                runCatching { flow.createDocumentFromSavedDraft(draft, input.source, ::keepsDraftLibraryVisit) }.getOrNull()?.let { action ->
                                    // Volatile navigation only; never persist a draft address in Book, tasks or generator input.
                                    if (active != null && library != null && window === active && keepsDraftLibraryVisit(library))
                                        generatedDraftReturn = GeneratedDraftReturn(active, library, action, draft.identity, input.pin)
                                    adapter.retire() // No saved-draft handles survive the move to the result inside this Library.
                                    JSONObject().put("started", true)
                                }
                            }
                        }
                        reply(result)
                    }
                }
                "drafts.leave" -> { exact(); adapter.leave(frame); warmDraftCheck = null; reply(JSONObject().put("left", true)) }
                else -> error("未知的本机文档草稿操作")
            }
        } catch (_: Exception) { done(null, unavailable) }
    }
    fun response(uri: Uri, method: String, headers: Map<String, String>): WebResourceResponse? = viewer.response(uri, method, headers)
    fun foreground(visible: Boolean) { if (!visible) clear(keepDraftPresentation = true) }
    fun clear(keepDraftPresentation: Boolean = false) {
        val focusedDraft = flow.state.focusedStandaloneDraft
        retireDraftLibrary(keepDraftPresentation)
        if(flow.state.pdfPageText!=null||flow.state.wordExcerpt!=null)flow.backFromDocument()
        flow.discardWordExcerptSource()
        val scoped = flow.state.deviceOriginalOnly || flow.state.liveMessageOriginalOnly || scopedResultCurrent != null
        deviceTaskCurrent=null; scopedResultCurrent=null; savedChatLinkOpen=null
        if((scoped || focusedDraft) && flow.state.open)flow.close()
        flow.discardLibraryDocumentReturn()
        window = null; replyDocumentVisit=null; frames.clear(); review = null; processingReview = null; viewer.clear()
    }
    fun close() { clear(); viewer.close() }
}
