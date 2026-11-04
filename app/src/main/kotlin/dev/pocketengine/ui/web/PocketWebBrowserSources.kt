package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.browser.BrowserTaskHost
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Saved browser evidence only. This endpoint cannot reach workspace, execution, navigation or profile commands. */
internal class PocketWebBrowserSources(
    private val target: BrowserSourceReaderTarget,
    private val host: BrowserTaskHost,
    private val focused: () -> Boolean,
    private val emit: (JSONObject) -> Unit,
    private val dismiss: () -> Unit
) : PocketWebEndpoint {
    override val surface = "browser_sources"
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(2))
    private val windowId = UUID.randomUUID().toString()
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    @Volatile private var foreground = false
    @Volatile private var document: String? = null
    private var revision = 0L
    private var binding: BrowserSourceReaderBinding? = null
    private var source: JSONObject? = null
    private var field: JSONObject? = null
    private var allowedFields = emptySet<String>()
    private var loading = false
    private var error = ""
    private var request = 0L
    private val commands = setOf("app.ready", "browserSources.read", "browserSources.field", "browserSources.refresh", "browserSources.close")

    override fun canInteract() = !closed && foreground && focused()
    override fun start() = publish()
    private fun current(original: Long, originalDocument: String?) = canInteract() && generation == original && document == originalDocument

    override fun captureDispatch(raw: String, stale: () -> Unit): () -> Unit {
        val original = generation
        return { if (canInteract() && original == generation) dispatch(raw) else stale() }
    }

    private fun dispatch(raw: String) {
        if (raw.length > 8192) return
        val input = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = input.optString("id")
        val suppliedDocument = input.optString("documentId")
        if (input.optInt("v") != 1 || id.isBlank() || id.length > 160 || suppliedDocument.isBlank() || suppliedDocument.length > 160) return
        val action = input.optString("action")
        fun fail(message: String) = result(id, false, JSONObject().put("code", "not_sent").put("message", message))
        if (action !in commands) { fail("这个窗口只用于阅读原任务保存的依据。"); return }
        if (action == "app.ready") {
            if (document != null && document != suppliedDocument) { fail("原阅读文档已变化，请关闭后从原来源重新打开。"); return }
            if (document == suppliedDocument) { publish(); result(id, true); return }
            document = suppliedDocument
            result(id, true)
            read(target.fieldRef)
            return
        }
        if (document != suppliedDocument) { fail("阅读窗口尚未完成绑定。"); return }
        val args = input.optJSONObject("args") ?: JSONObject()
        val keys = args.keys().asSequence().toSet()
        if (!setOf("windowId", "generation", "sourceFingerprint", "fieldRef").containsAll(keys) ||
            args.optString("windowId") != windowId || args.optLong("generation", -1) != generation ||
            args.optString("sourceFingerprint") != binding?.sourceFingerprint.orEmpty()) {
            fail("原阅读窗口或来源版本已变化，请重新读取原件。"); return
        }
        if (action == "browserSources.close") {
            result(id, true)
            invalidatePresentation(false)
            dismiss()
            return
        }
        if (loading) { fail("正在核对同一份原始记录，请稍候。"); return }
        val ref = if (action == "browserSources.field") args.optString("fieldRef") else null
        if (ref != null && ref !in allowedFields) { fail("这个字段不属于当前已核对的原文版本。"); return }
        if (ref == null && args.has("fieldRef")) { fail("原文读取不接受其他字段目标。"); return }
        result(id, true)
        read(ref)
    }

    private fun read(ref: String?) {
        val original = generation; val originalDocument = document; val originalBinding = binding
        val operation = ++request
        loading = true; error = ""; field = null
        if (ref == null) { source = null; allowedFields = emptySet() }
        publish()
        try {
            worker.execute {
                val loaded = runCatching {
                    check(current(original, originalDocument))
                    val saved = host.savedSourceForOwner(target.taskId, target.sourceId)
                    val actualBinding = BrowserSourceReaderBinding.capture(target, saved)
                    check(originalBinding == null || originalBinding == actualBinding)
                    val dto = BrowserSourceReaderDto.source(target, saved)
                    val refs = BrowserSourceReaderDto.fieldRefs(dto)
                    val selected = ref?.let {
                        check(it in refs)
                        check(current(original, originalDocument))
                        val savedField = host.savedFieldForOwner(target.taskId, it)
                        // The field API validates the projection but does not return run/fingerprint.
                        // Recheck the saved source after it too, rather than trusting a matching field ref alone.
                        val afterField = host.savedSourceForOwner(target.taskId, target.sourceId)
                        check(BrowserSourceReaderBinding.capture(target, afterField) == actualBinding)
                        check(current(original, originalDocument))
                        BrowserSourceReaderDto.field(target, actualBinding, it, savedField, afterField)
                    }
                    Triple(actualBinding, dto, selected)
                }
                main.post {
                    if (!current(original, originalDocument) || operation != request) return@post
                    loading = false
                    loaded.onSuccess { (identity, body, selected) ->
                        binding = identity; source = body; field = selected; allowedFields = BrowserSourceReaderDto.fieldRefs(body)
                    }.onFailure {
                        source = null; field = null; allowedFields = emptySet()
                        error = "这份保存记录暂不可核对。没有读取现网页或替换为其他版本，请重读原件或返回结果。"
                    }
                    publish()
                }
            }
        } catch (_: RuntimeException) {
            loading = false; source = null; field = null; allowedFields = emptySet()
            error = "原记录读取尚未开始，请重读原件。"; publish()
        }
    }

    private fun publish() {
        if (closed) return
        val reader = JSONObject().put("windowId", windowId).put("generation", generation)
            .put("sourceFingerprint", binding?.sourceFingerprint.orEmpty())
            .put("ready", canInteract() && document != null).put("loading", loading).put("error", error)
            .put("source", source ?: JSONObject.NULL).put("field", field ?: JSONObject.NULL)
        emit(JSONObject().put("v", 1).put("kind", "state").put("revision", ++revision).put("snapshot", JSONObject()
            .put("surface", surface).put("state", JSONObject()).put("composer", JSONObject.NULL)
            .put("browserSources", reader).put("host", JSONObject().put("surface", surface))
            .put("capabilities", JSONObject().put("commands", JSONArray(commands)))))
    }
    private fun result(id: String, ok: Boolean, value: JSONObject = JSONObject().put("accepted", true)) {
        if (!closed) emit(JSONObject().put("v", 1).put("kind", "result").put("id", id).put("ok", ok).put(if (ok) "value" else "error", value))
    }
    override fun foreground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (!value) invalidatePresentation(false)
    }
    override fun invalidatePresentation(documentReplaced: Boolean) {
        generation++; request++; document = null
        source = null; field = null; allowedFields = emptySet(); loading = false; error = ""
        worker.queue.clear()
        publish()
    }
    // Body, references, tokens, and authority are deliberately not restored.
    override fun restorePresentationState(saved: JSONObject?) = Unit
    override fun savePresentationState() = JSONObject()
    override fun close() {
        if (closed) return
        closed = true; foreground = false; generation++; request++; document = null
        source = null; field = null; allowedFields = emptySet()
        main.removeCallbacksAndMessages(null); worker.shutdownNow()
    }
}
