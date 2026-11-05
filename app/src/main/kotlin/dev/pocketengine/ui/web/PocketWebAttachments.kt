package dev.pocketengine.ui.web

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebResourceResponse
import dev.pocketengine.features.attachments.creation.DocumentDraft
import dev.pocketengine.features.attachments.documents.DocumentContent
import dev.pocketengine.features.attachments.processing.NativeFileProcess
import dev.pocketengine.features.attachments.processing.NativeMediaTrim
import dev.pocketengine.ui.AttachmentOutcomeState
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
    companion object { val COMMANDS = setOf("attachmentWorkspace.open", "attachmentWorkspace.action", "attachmentWorkspace.viewer") }
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
        s.documentOpen, s.originalFile?.identity, s.documentDraft?.toString(), s.processingSource?.identity,
        s.processingReview?.metadata()?.toString(), s.comparison?.first?.identity, s.comparison?.second?.identity).joinToString("\u0000")

    fun snapshot(): JSONObject {
        val state = flow.state
        if (!state.open) { if (window != null) clear(); return JSONObject().put("open", false) }
        if (!controller.webUiCanInteract()) return JSONObject().put("open", true).put("ready", false)
        val current = activeWindow()
        val files = (state.snapshot?.optJSONArray("sources").objects() + state.snapshot?.optJSONArray("artifacts").objects())
            .map { JSONObject(it.toString()) }
        val frameId = UUID.randomUUID().toString()
        frames[frameId] = Frame(location(state), files)
        while (frames.size > 16) frames.remove(frames.keys.first())
        viewer.bind(state.originalFile, state.comparison, current.ticket, acceptsWindow)
        val originalReview = state.processingReview
        if (originalReview == null || state.pendingProcessingTask != null) processingReview = null
        else if (processingReview?.value !== originalReview) processingReview = ProcessingReview(UUID.randomUUID().toString(), originalReview)
        val processingInfo = state.processingInfo?.let { JSONObject(it.toString()).also { metadata ->
            if (metadata.has("startAlignment")) metadata.put("startAlignmentLabel", NativeMediaTrim.startAlignmentLabel(metadata))
        } }
        val reviewedProcessing = originalReview?.let { original -> original.metadata().also { metadata ->
            if (original.command == "trimMedia") metadata.put("startAlignmentLabel", NativeMediaTrim.startAlignmentLabel(metadata))
        } }
        return JSONObject().put("open", true).put("ready", true).put("windowId", current.id).put("frameId", frameId)
            .put("taskId", state.taskId).put("group", state.group != null).put("team", state.team != null)
            .put("busy", state.busy).put("error", state.error).put("notice", state.notice)
            .put("snapshot", state.snapshot).put("file", state.file).put("document", state.document)
            .put("documentOpen", state.documentOpen).put("choosingSource", state.choosingSource)
            .put("documentDraft", state.documentDraft).put("pendingDocumentTask", state.pendingDocumentTask)
            .put("processingInfo", processingInfo).put("processingReview", reviewedProcessing).put("processingReviewId", processingReview?.id)
            .put("processingOpen", state.processingSource != null).put("pendingProcessingTask", state.pendingProcessingTask)
            .put("progress", state.progress).put("observing", state.observing).put("exportPending", flow.exportPending)
            .put("canRequestOcr", flow.canRequestOcr()).put("canSaveRead", flow.canSaveRead())
            .put("canReadTeamDifference", state.team != null && flow.canReadTeamDifference())
            .put("teamDifference", state.teamDifference).put("hasPreviousTeamArtifact", state.teamPrevious != null)
            .put("originalFile", viewer.snapshot()).put("comparison", viewer.comparisonSnapshot())
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        try {
            check(controller.webUiCanInteract()) { "请回到当前工作区再操作文件" }
            if (action == "attachmentWorkspace.open") {
                check(controller.webPrivateOwnerVisible()) { "请回到私人工作区打开文件库" }
                val task = args.optString("taskId").takeIf { it.isNotBlank() }
                if (task != null) {
                    val actual = controller.state.tasks.firstOrNull { it.id == task } ?: error("请重新打开原任务")
                    check(actual.raw.optString("actorId") == "owner" && actual.raw.optString("spaceId") == "private") { "请从原群任务打开其文件" }
                }
                clear(); flow.openTask(task); done(JSONObject().put("opened", true), null); changed(); return
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
                "close" -> { clear(); flow.close() }
                "back" -> { review = null; processingReview = null; viewer.clear(); when {
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
                "openOriginal" -> flow.openOriginalFile()
                "export" -> flow.prepareExport()
                "external" -> flow.openExternal()
                "share" -> flow.shareOriginalFile()
                "continueVersion" -> { val text = args.getString("change"); require(text.isNotBlank() && text.length <= 2000); flow.continueFromVersion(text) }
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
    fun clear() { window = null; frames.clear(); review = null; processingReview = null; viewer.clear() }
    fun close() { clear(); viewer.close() }
}
