package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.Fields
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.Pin
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.State
import dev.pocketengine.ui.PocketWorldController
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
    private val displayedLibraryFrame: (Frame) -> Boolean
) {
    data class Frame(val windowId: String, val frameId: String)
    data class Row(val rowHandle: String, val name: String, val format: String, val savedAt: Long)
    data class Catalog(val rows: List<Row>, val metadataComplete: Boolean)
    data class Edit(val editorHandle: String, val versionHandle: String)
    data class NewDraft(val editorHandle: String, val fields: Fields)
    data class Opened(val state: State, val edit: Edit? = null, val fields: Fields? = null)
    data class Saved(val state: State, val edit: Edit? = null)

    private data class Scope(val windowId: String, val ticket: PocketWebWindowTicket)
    private data class Editor(val edit: Edit, val pin: Pin)
    private class FirstAttempt(val handle: String, var pin: Pin? = null)
    private data class Request(val scope: Scope, val frame: Frame)
    private val main = Handler(Looper.getMainLooper())
    private var store: OwnerStandaloneDocumentDraftStore? = null
    private var scope: Scope? = null
    private var rows: Map<String, Pin> = emptyMap()
    private var editor: Editor? = null
    private var blank: String? = null
    private var first: FirstAttempt? = null
    private var pending: Request? = null
    private val defaults = Fields(OwnerStandaloneDocumentDraftStore.Format.WORD, "文档", "", "", 12)
    fun showing() = scope != null

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
                val metadata = catalog.entries().map { row ->
                    val handle = token()
                    pins[handle] = row.pin()
                    Row(handle, row.name(), row.format().value, row.savedAt())
                }
                rows = pins
                Catalog(metadata, catalog.metadataComplete())
            })
        }
    }

    /** A blank editor is only presentation and does not allocate a draft ID. */
    fun newDraft(frame: Frame): NewDraft {
        val current = admission(frame)
        check(scope == current && pending == null && editor == null && blank == null) { "请从当前本机草稿列表新建" }
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
        enqueue(current, frame, fallback = ::unknown,
            call = { storage, window -> storage.read(pin, window) }) { actual ->
            done(actual?.let { result ->
                val draft = result.draft()?.takeIf { result.state() == State.VERIFIED && it.metadata().pin() == pin }
                if (draft == null) return@let Opened(readStatus(result.state()))
                val edit = Edit(token(), token())
                editor = Editor(edit, pin)
                Opened(State.VERIFIED, edit, draft.fields())
            })
        }
    }

    /** Explicit save of five typed fields. No read-back body is echoed in this acknowledgement. */
    fun save(frame: Frame, shown: Edit, fields: Fields, done: (Saved?) -> Unit) {
        val current = admission(frame)
        require(canonicalId(shown.editorHandle) && canonicalId(shown.versionHandle)) { "原草稿编辑版本无效" }
        val original = editor?.takeIf { scope == current && it.edit == shown }
            ?: error("这份本机草稿的编辑版本已变化")
        check(pending == null)
        first = null
        blank = null
        val pin = original.pin
        enqueue(current, frame, fallback = ::unknown,
            call = { storage, window -> storage.write(pin, fields, window) }) { actual ->
            done(actual?.let { result ->
                val draft = result.draft()
                val next = draft?.metadata()?.pin()
                val state = result.state()
                if (draft?.fields() == fields && next != null && next.draftId() == pin.draftId() &&
                    (state == State.VERIFIED && next == pin ||
                     state == State.SAVED && next.revision() == pin.revision() + 1 && next.sha256() != pin.sha256())) {
                    val edit = if (state == State.VERIFIED) original.edit else Edit(original.edit.editorHandle, token())
                    editor = Editor(edit, next)
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

    /** Read only the exact acknowledged revision, for the current native generation review/confirmation. */
    fun readGenerationDraft(frame: Frame, shown: Edit, done: (Fields?) -> Unit) {
        val current = admission(frame)
        val original = editor?.takeIf { scope == current && it.edit == shown }
            ?: error("这份本机草稿的编辑版本已变化")
        val pin = original.pin
        enqueue(current, frame, fallback = ::unknown,
            call = { storage, window -> storage.read(pin, window) }) { actual ->
            val saved = actual?.takeIf { it.state() == State.VERIFIED }?.draft()?.takeIf {
                editor === original && it.metadata().pin() == pin
            }
            done(saved?.fields())
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
        pending = null
    }

    private fun admission(frame: Frame): Scope {
        checkMain()
        check(canonicalId(frame.windowId) && canonicalId(frame.frameId)) { "原文件窗口身份无效" }
        val next = Scope(frame.windowId, captureWindow())
        check(current(next, frame)) { "请在当前私人文件库核对原文档草稿" }
        return next
    }

    private fun current(expected: Scope, frame: Frame): Boolean {
        checkMain()
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
        val window = object : OwnerStandaloneDocumentDraftStore.OwnerWindow {
            override fun current(): Boolean = try { onMain(::admitted) } catch (_: IOException) { false }
            override fun atCommit(mutation: OwnerStandaloneDocumentDraftStore.Commit) {
                onMain {
                    check(admitted()) { "原文档草稿窗口已离开；未继续变更" }
                    mutation.enter()
                }
            }
        }
        try {
            controller.runPluginCatalogWork(Runnable {
                val result = try { call(storage, window) } catch (_: Exception) { fallback() }
                main.post { finish(result) }
            })
        } catch (_: RejectedExecutionException) { finish(fallback()) }
    }

    /**
     * The existing owner/window computation is main-thread only. The worker may
     * await that check; main never waits for the worker, never re-enters the store,
     * and only performs its bounded final stat/atomic rename (not body IO/fsync).
     * An unentered timed-out handoff is cancelled; an entered one cannot be replayed.
     */
    private fun <T> onMain(call: () -> T): T {
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

    private fun readStatus(state: State) = when (state) {
        State.MISSING, State.CONFLICT -> state
        else -> State.UNKNOWN
    }
    private fun unknown() = OwnerStandaloneDocumentDraftStore.Outcome(State.UNKNOWN, null)
    private fun token() = UUID.randomUUID().toString()
    private fun canonicalId(value: String) = value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private fun checkMain() { check(Looper.myLooper() == main.looper) { "必须在原生文件窗口核对草稿" } }
}
