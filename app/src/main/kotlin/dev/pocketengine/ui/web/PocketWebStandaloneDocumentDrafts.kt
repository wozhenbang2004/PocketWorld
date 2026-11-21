package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.attachments.AttachmentBook
import dev.pocketengine.features.attachments.OwnerFilePreviewSource
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.Fields
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.Outcome
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.Pin
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.RevisionSource
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.State
import dev.pocketengine.ui.AttachmentPdfPageText
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Private Library draft adapter. It does not initialize on app startup or
 * write to Bundle, tasks, or Book. Generation receives fields only from an exact
 * explicitly revalidated saved revision; this adapter never starts it.
 *
 * The eventual host must prove its expressly opened ordinary global Library,
 * current trusted document and exact accepted Attachment window/frame. The supplied
 * main-only predicate must reject even a matching frame if navigation moved or if
 * it was reached through a scoped file/task, report, team/device, search or picker.
 * It must retire the adapter on leave/pause/replacement. A later visit starts at
 * an explicit metadata list again, never by reactivating a retained handle.
 */
internal class PocketWebStandaloneDocumentDrafts(
    private val controller: PocketWorldController,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
    private val displayedLibraryFrame: (Frame) -> Boolean,
    private val displayedRevisionFrame: (Frame) -> Boolean,
    private val displayedTextSource: (String, TextSeed) -> Boolean
) {
    data class Frame(val windowId: String, val frameId: String)
    enum class TextOrigin(val rowFlag: String) { MARKDOWN("justCopiedMarkdown"), PDF_PAGE("justCopiedPdfPage") }
    data class Row(val rowHandle: String, val name: String, val format: String, val savedAt: Long, val linkedRevision: Boolean,
        val recentTextOrigin: TextOrigin? = null)

    sealed class TextSeed(val fields: Fields, val origin: TextOrigin) {
        class Markdown(val original: PocketWebOriginalFiles.MarkdownDraftSeed) : TextSeed(original.fields, TextOrigin.MARKDOWN)
        class PdfPage(val page: AttachmentPdfPageText, val source: OwnerFilePreviewSource, val file: JSONObject,
            val read: JSONObject, val receiptId: String, fields: Fields) : TextSeed(fields, TextOrigin.PDF_PAGE)
    }
    data class Catalog(val rows: List<Row>, val metadataComplete: Boolean)
    data class Edit(val editorHandle: String, val versionHandle: String)
    data class NewDraft(val editorHandle: String, val fields: Fields)
    data class Opened(val state: State, val edit: Edit? = null, val fields: Fields? = null,
                      val sourceName: String? = null, val sourceUnavailable: Boolean = false)
    data class Saved(val state: State, val edit: Edit? = null, val sourceUnavailable: Boolean = false)
    data class GenerationInput(val fields: Fields, val source: RevisionSource?)
    data class GenerationRead(val input: GenerationInput? = null, val sourceUnavailable: Boolean = false)
    data class Retention(val state: State, val sourceUnavailable: Boolean = false)
    data class TextRetention(val state: State, val savedAt: Long? = null, val sourceUnavailable: Boolean = false)

    private data class Scope(val windowId: String, val ticket: PocketWebWindowTicket, val linkedEditor: Boolean,
        val textSource: TextSeed? = null)
    private data class Editor(val edit: Edit, val pin: Pin, val source: RevisionSource? = null, val removalOnly: Boolean = false)
    private class FirstAttempt(val handle: String, var pin: Pin? = null)
    private class LinkedAttempt(val source: RevisionSource, var pin: Pin? = null)
    private class TextAttempt(val source: TextSeed, var pin: Pin? = null, var receipt: TextRetention? = null)
    private data class RecentText(val windowId: String, val ticket: PocketWebWindowTicket, val pin: Pin, val origin: TextOrigin)
    private data class Read(val outcome: Outcome, val sourceName: String? = null)
    private data class Write(val outcome: Outcome, val sourceUnavailable: Boolean = false)
    private data class Request(val scope: Scope, val frame: Frame)
    private val main = Handler(Looper.getMainLooper())
    private var store: OwnerStandaloneDocumentDraftStore? = null
    private var scope: Scope? = null
    private var rows: Map<String, Pin> = emptyMap()
    private var editor: Editor? = null
    private var blank: String? = null
    private var first: FirstAttempt? = null
    private var linkedFirst: LinkedAttempt? = null
    private var textFirst: TextAttempt? = null
    private var recentText: RecentText? = null
    private var pending: Request? = null
    private val defaults = Fields(OwnerStandaloneDocumentDraftStore.Format.WORD, "文档", "", "", 12)
    fun showing() = scope?.let { !it.linkedEditor && it.textSource == null } == true
    fun retainingLinked() = scope?.linkedEditor == true
    fun retireLinked() { if (retainingLinked()) retire() }
    fun textSource() = scope?.textSource
    fun textRetention() = textFirst?.let { it.receipt ?: TextRetention(State.UNKNOWN) }
    fun retireText() { if (textSource() != null) retire() }
    fun forgetRecentText() { recentText = null }

    /** Explicit shelf action only. It does not open a body. Null means this presentation retired. */
    fun list(frame: Frame, done: (Catalog?) -> Unit) {
        val current = admission(frame)
        if (scope != current) { retire(); scope = current }
        check(editor == null && blank == null) { "请先离开当前本机文档草稿" }
        check(pending == null) { "请先等待本次原草稿核对" }
        rows = emptyMap()
        enqueue(current, frame, fallback = { OwnerStandaloneDocumentDraftStore.Catalog(emptyList(), false) },
            call = { storage, window -> storage.catalog(window) }) { actual ->
            done(actual?.let { catalog ->
                val pins = linkedMapOf<String, Pin>()
                val copied = recentText?.takeIf { it.windowId == current.windowId && it.ticket == current.ticket }
                val metadata = catalog.entries().map { row ->
                    val handle = token()
                    pins[handle] = row.pin()
                    Row(handle, row.name(), row.format().value, row.savedAt(), row.revisionSource() != null,
                        copied?.takeIf { row.pin() == it.pin }?.origin)
                }
                rows = pins
                Catalog(metadata, catalog.metadataComplete())
            })
        }
    }

    /** A blank editor is only presentation and does not allocate a draft ID. */
    fun newDraft(frame: Frame): NewDraft {
        val current = admission(frame)
        check((scope == null || scope == current) && pending == null && editor == null && blank == null) { "请先退出或核对当前本机草稿" }
        // The explicit ordinary Library entry can start with a blank without reading the shelf.
        scope = current
        return NewDraft(token().also { blank = it }, defaults)
    }

    fun firstSave(frame: Frame, blankHandle: String, fields: Fields, done: (Saved?) -> Unit) {
        val current = admission(frame)
        check(scope == current && blank == blankHandle && canonicalId(blankHandle) &&
            first == null && editor == null && pending == null) { "请先核对本次草稿，没有另建一份" }
        if (defaults == fields) { done(Saved(State.NO_EDIT)); return }
        val attempt = FirstAttempt(blankHandle)
        first = attempt
        enqueue(current, frame, fallback = ::unknown, call = { storage, window ->
            val seed = storage.initialize(window)
            val expected = storage.expectedFirstPin(seed, fields)
            onMain {
                check(window.current() && first === attempt)
                attempt.pin = expected // Only locator, never a reusable seed, body or window.
            }
            storage.firstWrite(seed, fields)
        }) { actual ->
            done(actual?.let { result ->
                val verified = result.draft()?.takeIf {
                    result.state() == State.SAVED && it.metadata().pin() == attempt.pin && it.fields() == fields
                }
                if (verified != null) return@let bindFirst(attempt, verified.metadata().pin(), State.SAVED)
                // These two positive refusals did not enter the draft's publication.
                if (result.state() == State.QUOTA || result.state() == State.NO_EDIT) {
                    first = null
                    return@let Saved(result.state())
                }
                Saved(State.UNKNOWN)
            })
        }
    }

    /** A missing/uncertain acknowledgement can lead only to a read of the original first pin. */
    fun checkFirst(frame: Frame, blankHandle: String, done: (Saved?) -> Unit) {
        val current = admission(frame)
        val attempt = first?.takeIf { scope == current && blank == blankHandle && it.handle == blankHandle }
            ?: error("本次首次保留的来源已变化")
        if (pending != null) { done(Saved(State.UNKNOWN)); return }
        val pin = attempt.pin ?: run { done(Saved(State.UNKNOWN)); return }
        enqueue(current, frame, fallback = ::unknown,
            call = { storage, window -> storage.read(pin, window) }) { actual ->
            done(actual?.let { result ->
                if (result.state() == State.VERIFIED && result.draft()?.metadata()?.pin() == pin)
                    bindFirst(attempt, pin, State.VERIFIED)
                else Saved(State.UNKNOWN) // Absence/another head does not prove the first write never entered.
            })
        }
    }

    private fun bindFirst(attempt: FirstAttempt, pin: Pin, state: State): Saved {
        check(first === attempt && blank == attempt.handle)
        val edit = editor?.takeIf { it.pin == pin && it.edit.editorHandle == attempt.handle }?.edit
            ?: Edit(attempt.handle, token())
        editor = Editor(edit, pin)
        rows = emptyMap()
        return Saved(state, edit)
    }

    /** Explicit selection from this visit's actual projected rows. The caller must never replay its body. */
    fun open(frame: Frame, rowHandle: String, done: (Opened?) -> Unit) {
        val current = admission(frame)
        check(scope == current && editor == null && blank == null) { "请从当前本机草稿列表打开原版本" }
        require(canonicalId(rowHandle)) { "原草稿列表位置无效" }
        val pin = rows[rowHandle] ?: error("该版本不在当前已核对的本机草稿中")
        enqueue(current, frame, fallback = { Read(unknown()) },
            call = { storage, window -> readExact(storage, window, pin) }) { actual ->
            done(actual?.let { result ->
                val outcome = result.outcome
                val draft = outcome.draft()?.takeIf { outcome.state() == State.VERIFIED && it.metadata().pin() == pin }
                if (draft == null) return@let Opened(readStatus(outcome.state()))
                val edit = Edit(token(), token())
                val source = draft.metadata().revisionSource()
                val unavailable = source != null && result.sourceName == null
                editor = Editor(edit, pin, source, removalOnly = unavailable)
                Opened(State.VERIFIED, edit, draft.fields().takeUnless { unavailable }, result.sourceName, unavailable)
            })
        }
    }

    /** Explicit save of five typed fields. No read-back body is echoed in this acknowledgement. */
    fun save(frame: Frame, shown: Edit, fields: Fields, done: (Saved?) -> Unit) {
        val current = admission(frame)
        require(canonicalId(shown.editorHandle) && canonicalId(shown.versionHandle)) { "原草稿编辑版本无效" }
        val original = editor?.takeIf { scope == current && it.edit == shown && !it.removalOnly }
            ?: error("这份本机草稿的编辑版本已变化")
        check(pending == null)
        first = null
        blank = null
        val pin = original.pin
        enqueue(current, frame, fallback = { Write(unknown()) }, call = { storage, window ->
            if (original.source != null && verifyRevisionSource(original.source, window) == null)
                Write(unknown(), sourceUnavailable = true)
            else Write(storage.write(pin, fields, window))
        }) { actual ->
            done(actual?.let { write ->
                if (write.sourceUnavailable) {
                    editor = original.copy(removalOnly = true)
                    return@let Saved(State.UNKNOWN, original.edit, sourceUnavailable = true)
                }
                val result = write.outcome
                val draft = result.draft()
                val next = draft?.metadata()?.pin()
                val state = result.state()
                if (draft?.fields() == fields && next != null && next.draftId() == pin.draftId() &&
                    draft?.metadata()?.revisionSource() == original.source &&
                    (state == State.VERIFIED && next == pin ||
                     state == State.SAVED && next.revision() == pin.revision() + 1 && next.sha256() != pin.sha256())) {
                    val edit = if (state == State.VERIFIED) original.edit else Edit(original.edit.editorHandle, token())
                    editor = original.copy(edit = edit, pin = next)
                    rows = emptyMap() // Retire the previous catalogue; do not re-read it automatically.
                    return@let Saved(state, edit)
                }
                if (state == State.QUOTA) return@let Saved(State.QUOTA)
                editor = null
                rows = emptyMap()
                Saved(readStatus(state))
            })
        }
    }

    /** First retained revision of a real Library original. Unknown can only read its already allocated pin. */
    fun retainRevision(frame: Frame, source: RevisionSource, fields: Fields, checking: Boolean, done: (Retention?) -> Unit) {
        val current = admission(frame, linkedEditor = true)
        if (scope != current) {
            check(!checking && scope == null) { "原文档修改草稿尚待核对" }
            scope = current
        }
        if (pending != null) { done(Retention(State.UNKNOWN)); return }
        val attempt = if (checking) linkedFirst?.takeIf { it.source == source }
            ?: run { done(Retention(State.UNKNOWN)); return }
        else {
            check(linkedFirst == null) { "请先核对上一次保留，不会重复保存" }
            LinkedAttempt(source).also { linkedFirst = it }
        }
        val expected = attempt.pin
        if (checking && expected == null) { done(Retention(State.UNKNOWN)); return }
        enqueue(current, frame, fallback = { Write(unknown()) }, call = { storage, window ->
            if (verifyRevisionSource(source, window) == null) Write(unknown(), sourceUnavailable = true)
            else if (checking) Write(storage.read(checkNotNull(expected), window))
            else {
                val seed = storage.initializeRevision(window, source)
                val pin = storage.expectedFirstPin(seed, fields)
                onMain { check(window.current() && linkedFirst === attempt); attempt.pin = pin }
                Write(storage.firstWrite(seed, fields))
            }
        }) { actual ->
            done(actual?.let { write ->
                val outcome = write.outcome
                val state = outcome.state()
                val saved = outcome.draft()
                if ((state == State.SAVED || checking && state == State.VERIFIED) && saved?.fields() == fields &&
                    saved?.metadata()?.pin() == attempt.pin && saved?.metadata()?.revisionSource() == source) {
                    retireLinked()
                    return@let Retention(state)
                }
                // A first attempt without any storage call or a positive quota refusal is known not to have written.
                if (!checking && (write.sourceUnavailable || state == State.QUOTA)) {
                    retireLinked()
                    return@let Retention(if (write.sourceUnavailable) State.UNKNOWN else state, write.sourceUnavailable)
                }
                Retention(State.UNKNOWN, write.sourceUnavailable)
            })
        }
    }

    /** Explicitly retain the native reader's already displayed text as a new independent draft. */
    fun retainText(frame: Frame, source: TextSeed, checking: Boolean, done: (TextRetention?) -> Unit) {
        val current = admission(frame, textSource = source)
        check(scope == current || scope == null && !checking) { "请先核对原草稿操作" }
        val earlier = textFirst
        if (earlier != null) {
            check(earlier.source === source)
            earlier.receipt?.let { done(it); return }
            if (!checking || pending != null || earlier.pin == null) { done(TextRetention(State.UNKNOWN)); return }
        } else check(!checking && pending == null)
        if (scope == null) scope = current
        val attempt = earlier ?: TextAttempt(source).also { textFirst = it }
        val expected = attempt.pin
        enqueue(current, frame, fallback = { Write(unknown()) }, call = { storage, window ->
            val verified = when (source) {
                is TextSeed.Markdown -> {
                    val control = RunControl()
                    // Preserve the existing full-original Markdown check outside the owner-window handshake.
                    try { window.current() && run { source.original.source.requireCurrent(control); window.current() } }
                    catch (_: Exception) { false }
                    finally { control.cancel() }
                }
                // Copy the already verified, displayed PDF text receipt. No second file read or PDF parse.
                is TextSeed.PdfPage -> window.current()
            }
            if (!verified) Write(unknown(), sourceUnavailable = true)
            else if (checking) Write(storage.read(checkNotNull(expected), window))
            else {
                val initial = storage.initialize(window)
                val firstPin = storage.expectedFirstPin(initial, source.fields)
                onMain { check(window.current() && textFirst === attempt); attempt.pin = firstPin }
                Write(storage.firstWrite(initial, source.fields))
            }
        }) { actual ->
            done(actual?.let { write ->
                val outcome = write.outcome
                val status = outcome.state()
                val saved = outcome.draft()
                if ((status == State.SAVED || checking && status == State.VERIFIED) && saved != null &&
                    saved.metadata().pin() == attempt.pin && saved.metadata().revisionSource() == null &&
                    saved.fields() == source.fields && saved.metadata().savedAt() > 0) {
                    val receipt = TextRetention(status, saved.metadata().savedAt())
                    attempt.receipt = receipt
                    // Pure presentation: same-view list tags this exact stored head, never just its name.
                    recentText = RecentText(current.windowId, current.ticket, saved.metadata().pin(), source.origin)
                    receipt
                } else if (!checking && (write.sourceUnavailable || status == State.QUOTA || status == State.NO_EDIT)) {
                    retireText() // Only definite no-write cases can start again from this same read.
                    TextRetention(status, sourceUnavailable = write.sourceUnavailable)
                } else TextRetention(State.UNKNOWN, sourceUnavailable = write.sourceUnavailable)
            })
        }
    }

    /** Read only the exact acknowledged revision, for the current native generation review/confirmation. */
    fun readGenerationDraft(frame: Frame, shown: Edit, done: (GenerationRead?) -> Unit) {
        val current = admission(frame)
        val original = editor?.takeIf { scope == current && it.edit == shown && !it.removalOnly }
            ?: error("这份本机草稿的编辑版本已变化")
        val pin = original.pin
        enqueue(current, frame, fallback = { Read(unknown()) },
            call = { storage, window -> readExact(storage, window, pin) }) { actual ->
            val saved = actual?.outcome?.takeIf { it.state() == State.VERIFIED }?.draft()?.takeIf {
                editor === original && it.metadata().pin() == pin && it.metadata().revisionSource() == original.source
            }
            if (saved == null) done(null)
            else if (original.source != null && actual?.sourceName == null) {
                editor = original.copy(removalOnly = true)
                done(GenerationRead(sourceUnavailable = true))
            } else done(GenerationRead(GenerationInput(saved.fields(), original.source)))
        }
    }

    private fun readExact(storage: OwnerStandaloneDocumentDraftStore, window: OwnerStandaloneDocumentDraftStore.OwnerWindow, pin: Pin): Read {
        val actual = storage.read(pin, window) // The DraftStore lock is released before any Book verification.
        return Read(actual, actual.draft()?.metadata()?.revisionSource()?.let { verifyRevisionSource(it, window) })
    }

    private fun verifyRevisionSource(source: RevisionSource, window: OwnerStandaloneDocumentDraftStore.OwnerWindow): String? {
        val control = RunControl()
        try {
            // Main-window checks bracket bounded Book reads; do not await main while holding Book's monitor.
            if (!window.current()) return null
            val host = controller.nativeButlerHost().attachmentOutcomes ?: return null
            val parent = host.originalFile(null, source.parentArtifactId().toString(), source.parentSha256(), control).metadata()
            val origin = parent.getJSONObject("source")
            val rootMime = parent.opt("mimeType")
            val format = if (rootMime == "application/pdf") "pdf"
                else if (rootMime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document") "docx"
                else return null
            check(origin.opt("kind") == "created_document" && origin.opt("documentFormat") == format)
            val selected = origin.getJSONObject("editableSource")
            check(selected.opt("id") == source.editableSourceId().toString() && selected.opt("sha256") == source.editableSourceSha256())
            if (!window.current()) return null
            val editable = host.originalFile(null, source.editableSourceId().toString(), source.editableSourceSha256(), control).metadata()
            val provenance = editable.getJSONObject("source")
            check(provenance.opt("kind") == "document_source" && provenance.opt("documentFormat") == format &&
                editable.opt("mimeType") == "application/json" && selected.opt("mimeType") == editable.opt("mimeType") &&
                selected.optLong("size", -1L) == editable.optLong("size", -2L))
            val task = origin.getString("taskId"); val run = origin.getString("runId")
            val operation = origin.getString("operationId"); val plan = origin.getString("planDigest")
            val binding = mapOf("taskId" to task, "runId" to run, "operationId" to operation, "planDigest" to plan)
            fun publication(record: JSONObject, role: String): Boolean {
                val meta = record.getJSONObject("source")
                val exact = meta.getJSONObject("nativePublication")
                return exact.opt("role") == role && binding.all { (key, value) -> meta.opt(key) == value && exact.opt(key) == value } &&
                    AttachmentBook.isOriginalPublicationId(record.getString("id"), operation, role, task, run, plan)
            }
            check(publication(parent, "document_output") && publication(editable, "document_source"))
            control.check()
            if (!window.current()) return null
            return parent.getString("name").takeIf { it.isNotBlank() && it.length <= 160 }
        } catch (_: Exception) { return null }
        finally { control.cancel() }
    }

    /** Delete only the displayed saved pin; retire its editor even when the receipt is uncertain. */
    fun remove(frame: Frame, shown: Edit, done: (State?) -> Unit) {
        val current = admission(frame)
        val original = editor?.takeIf { scope == current && it.edit == shown }
            ?: error("这份本机草稿的编辑版本已变化")
        enqueue(current, frame, fallback = ::unknown,
            call = { storage, window -> storage.remove(original.pin, window) }) { actual ->
            if (actual == null || editor !== original) { done(null); return@enqueue }
            editor = null; rows = emptyMap(); first = null; blank = null
            val state = actual.state()
            done(if (state == State.REMOVED) state else readStatus(state))
        }
    }

    fun leave(frame: Frame) {
        val current = admission(frame)
        check(scope == null || scope == current) { "原草稿窗口已离开" }
        retire()
    }

    /** Clears only volatile presentation. Never reads, writes or discards a draft. */
    fun retire() {
        checkMain()
        scope = null
        rows = emptyMap()
        editor = null
        blank = null
        first = null
        linkedFirst = null
        textFirst = null
        pending = null
    }

    private fun admission(frame: Frame, linkedEditor: Boolean = false, textSource: TextSeed? = null): Scope {
        checkMain()
        check(canonicalId(frame.windowId) && canonicalId(frame.frameId)) { "原文件窗口身份无效" }
        val next = Scope(frame.windowId, captureWindow(), linkedEditor, textSource)
        check(current(next, frame)) { "请在当前私人文件库核对原文档草稿" }
        return next
    }

    private fun current(expected: Scope, frame: Frame): Boolean {
        checkMain()
        expected.textSource?.let { seed ->
            return expected.windowId == frame.windowId && acceptsWindow(expected.ticket) && displayedTextSource(frame.windowId, seed)
        }
        if (expected.linkedEditor) return expected.windowId == frame.windowId && acceptsWindow(expected.ticket) && displayedRevisionFrame(frame)
        val flow = controller.attachmentOutcomes
        val state = flow.state
        val visible = state.snapshot
        val importing = when (state.libraryImport?.phase) {
            "selecting", "selection_unconfirmed", "registering" -> true
            else -> false
        }
        return expected.windowId == frame.windowId && acceptsWindow(expected.ticket) &&
            controller.webPrivateOwnerVisible() && controller.conversationReader == null &&
            !controller.state.chatAttachmentPickerOpen && !controller.state.attachmentImporting && !importing &&
            flow.privateLibrary() && !state.fromWorkResult && !state.restoredWorkReturn && state.libraryDocumentReturn == null &&
            visible != null && visible.opt("taskId") == "" &&
            visible.optJSONArray("sources") != null && visible.optJSONArray("artifacts") != null &&
            state.file == null && state.originalFile == null && state.documentDraft == null && !state.documentOpen &&
            state.processingSource == null && state.comparison == null && !state.busy && !flow.exportPending &&
            state.pendingDocumentTask == null && displayedLibraryFrame(frame)
    }

    private fun <T> enqueue(expected: Scope, frame: Frame, fallback: () -> T,
                            call: (OwnerStandaloneDocumentDraftStore, OwnerStandaloneDocumentDraftStore.OwnerWindow) -> T,
                            apply: (T?) -> Unit) {
        checkMain()
        check(pending == null && scope == expected && current(expected, frame)) { "原草稿窗口已变化，未接收操作" }
        val original = Request(expected, frame)
        val storage = store ?: OwnerStandaloneDocumentDraftStore(controller.webPresentationActivity().applicationContext)
            .also { store = it }
        pending = original
        fun admitted() = pending === original && scope == original.scope && current(original.scope, original.frame)
        fun finish(result: T) {
            checkMain()
            if (pending !== original) { apply(null); return }
            val live = admitted()
            pending = null
            if (live) apply(result) else { retire(); apply(null) }
        }
        val window = PocketWebStandaloneDraftFence.window(::admitted)
        try {
            controller.runPluginCatalogWork(Runnable {
                val result = try { call(storage, window) } catch (_: Exception) { fallback() }
                main.post { finish(result) }
            })
        } catch (_: RejectedExecutionException) { finish(fallback()) }
    }

    private fun <T> onMain(call: () -> T): T = PocketWebStandaloneDraftFence.onMain(call)

    private fun readStatus(state: State) = when (state) {
        State.MISSING, State.CONFLICT -> state
        else -> State.UNKNOWN
    }
    private fun unknown() = OwnerStandaloneDocumentDraftStore.Outcome(State.UNKNOWN, null)
    private fun token() = UUID.randomUUID().toString()
    private fun canonicalId(value: String) = value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private fun checkMain() { check(Looper.myLooper() == main.looper) { "必须在原生文件窗口核对草稿" } }
}

/** Shared synchronous commit fence for native private draft callers; never a window by itself. */
internal object PocketWebStandaloneDraftFence {
    private val main = Handler(Looper.getMainLooper())

    fun window(admitted: () -> Boolean): OwnerStandaloneDocumentDraftStore.OwnerWindow =
        object : OwnerStandaloneDocumentDraftStore.OwnerWindow {
            override fun current(): Boolean = try { onMain(admitted) } catch (_: IOException) { false }
            override fun atCommit(mutation: OwnerStandaloneDocumentDraftStore.Commit) {
                onMain {
                    check(admitted()) { "原文档草稿窗口已离开；未继续变更" }
                    mutation.enter()
                }
            }
        }

    /**
     * The existing owner/window computation is main-thread only. The worker may
     * await that check; main never waits for the worker, never re-enters the store,
     * and only performs its bounded final stat/atomic rename (not body IO/fsync).
     * An unentered timed-out handoff is cancelled; an entered one cannot be replayed.
     */
    fun <T> onMain(call: () -> T): T {
        if (Looper.myLooper() == main.looper) return call()
        val claim = AtomicBoolean(true)
        val task = FutureTask {
            if (!claim.compareAndSet(true, false)) throw IOException("本次原窗口核对已结束")
            call()
        }
        if (!main.post(task)) throw IOException("原文件窗口主队列已关闭")
        var interrupted = false
        var entered = false
        try {
            while (true) try {
                return if (entered) task.get() else task.get(1500L, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (claim.compareAndSet(true, false)) {
                    main.removeCallbacks(task)
                    task.cancel(false)
                    throw IOException("未进入原文件窗口核对")
                }
                entered = true
            } catch (_: InterruptedException) {
                interrupted = true
                if (claim.compareAndSet(true, false)) {
                    main.removeCallbacks(task)
                    task.cancel(false)
                    throw IOException("原文件窗口核对已停止")
                }
                entered = true
            } catch (failure: ExecutionException) {
                throw (failure.cause as? Exception ?: IOException("原文件窗口未能核对"))
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
