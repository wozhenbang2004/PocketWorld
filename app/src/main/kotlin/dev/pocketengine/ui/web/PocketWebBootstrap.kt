package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.EngineApplication
import dev.pocketengine.ui.ComposeActivity
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import org.json.JSONArray
import org.json.JSONObject

/** Startup/maintenance only. No TaskStore, FeatureHost, or business Controller is constructed here. */
internal class PocketWebBootstrap(
    private val activity: ComposeActivity,
    private val app: EngineApplication,
    private val emit: (JSONObject) -> Unit,
    private val ready: () -> Unit
) : PocketWebEndpoint {
    override val surface = "bootstrap"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "pocket-bootstrap-owner") }
    @Volatile private var closed = false
    @Volatile private var foreground = false
    private var started = false
    private var document: String? = null
    private var revision = 0L
    private var lastState = ""
    private var lastSnapshot: JSONObject? = null
    private var readyDelivered = false
    private var operation: JSONObject? = null
    private var queryId: String? = null
    private val receipts = linkedMapOf<String, Pair<String, JSONObject>>()
    private val pending = mutableMapOf<String, String>()
    private class Window {
        val alive = AtomicBoolean(true)
        // This exact object is retained across commands within one foreground document.
        val owner = BooleanSupplier { alive.get() }
    }
    @Volatile private var window = Window().also { it.alive.set(false) }
    private val commands = setOf("app.ready", "app.refresh", "bootstrap.read", "bootstrap.retry", "repair.read", "repair.preserve", "repair.review", "repair.confirm", "repair.discard", "bootstrap.received", "bootstrap.acknowledge")
    private fun mutation(action: String) = action in setOf("bootstrap.retry", "repair.preserve", "repair.confirm", "repair.discard")
    override fun canInteract() = !closed && foreground && window.alive.get() && !activity.isFinishing && !activity.isDestroyed
    private fun current(original: Window) = canInteract() && window === original && original.alive.get()

    override fun start() { started = true; publish(); main.post(poll) }
    private val poll = object : Runnable {
        override fun run() {
            if (closed || !started) return
            if (foreground) {
                val readable = publish()
                if (readable && app.businessReady() && operation == null && !readyDelivered) {
                    readyDelivered = true
                    val original = window
                    main.post { if (current(original) && app.businessReady() && operation == null) ready() else readyDelivered = false }
                }
            }
            if (foreground) main.postDelayed(this, 500L)
        }
    }
    private fun snapshot(): JSONObject = app.bootstrapState()
    private fun publish(): Boolean {
        if (closed) return false
        val state = runCatching { snapshot() }.getOrNull()
        if (state != null) lastSnapshot = JSONObject(state.toString())
        val host = JSONObject().put("surface", surface).put("bootstrapReadError", if (state == null)
            "原启动状态暂时无法读取。已保留最后一次现状，新的维修操作保持关闭；请只读重查，不要反复确认。" else "")
        val value = JSONObject().put("surface", surface).put("bootstrap", state ?: lastSnapshot ?: JSONObject.NULL)
            .put("state", JSONObject()).put("composer", JSONObject.NULL)
            .put("capabilities", JSONObject().put("commands", JSONArray(commands)))
            .put("host", host)
        operation?.let { value.put("bootstrapOperation", JSONObject(it.toString())) }
        queryId?.let { value.put("bootstrapQueryId", it) }
        val encoded = value.toString()
        if (encoded == lastState) return state != null
        lastState = encoded
        emit(JSONObject().put("v", 1).put("kind", "state").put("revision", ++revision).put("snapshot", value))
        return state != null
    }
    override fun captureDispatch(raw: String, stale: () -> Unit): () -> Unit {
        val original = window
        return { if (current(original)) dispatch(raw, original) else stale() }
    }
    private fun dispatch(raw: String, original: Window) {
        if (raw.length > 65536) return
        val input = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = input.optString("id")
        if (input.optInt("v") != 1 || id.isBlank() || id.length > 160) return
        val action = input.optString("action")
        val suppliedDocument = input.optString("documentId")
        val payload = input.optJSONObject("args") ?: JSONObject()
        fun fail(message: String, code: String = "not_sent") = emit(result(id, false, JSONObject().put("message", message).put("code", code)))
        if (action !in commands || suppliedDocument.isBlank() || suppliedDocument.length > 160) { fail("当前维护窗口不支持这项操作。"); return }
        if (action == "app.ready") {
            if (document != null && document != suppliedDocument) { fail("原维护文档已变化，请重新打开。"); return }
            document = suppliedDocument
            lastState = ""; publish()
            emit(result(id, true, JSONObject().put("accepted", true).put("surface", surface)))
            return
        }
        if (document != suppliedDocument) { fail("维护页面尚未完成绑定。"); return }
        val signature = "$action\u0000$payload"
        receipts[id]?.let { old -> if (old.first == signature) emit(old.second) else fail("原请求编号不能用于另一项操作。"); return }
        pending[id]?.let { old -> if (old != signature) fail("原请求编号不能用于另一项操作。"); return }
        if (action == "bootstrap.received") {
            val originalOperation = operation
            if (originalOperation == null || originalOperation.optString("status") != "completed" ||
                payload.optString("requestId") != originalOperation.optString("requestId") ||
                payload.optString("command") != originalOperation.optString("command") ||
                payload.optString("outcome") != originalOperation.optString("receipt")) {
                fail("原回执不属于当前维护窗口；保留原请求并只读核对。"); return
            }
            // Presentation delivery only. This never changes the Java repair record or execution gate.
            operation = null; queryId = null; publish()
            val receipt = result(id, true, JSONObject().put("received", true))
            receipts[id] = signature to receipt; emit(receipt); return
        }
        if (action == "bootstrap.acknowledge") {
            val originalOperation = operation
            if (originalOperation == null || originalOperation.optString("status") == "pending" || payload.optString("requestId") != originalOperation.optString("requestId") || queryId == null || payload.optString("queryId") != queryId) {
                fail("请先只读核对同一原请求，再明确确认已核对现状。"); return
            }
            val state = runCatching { snapshot() }.getOrNull()
            if (state == null) { fail("原启动状态暂时无法读取，请保留原请求后只读重查。"); return }
            operation = null; queryId = null; publish()
            val receipt = result(id, true, state)
            receipts[id] = signature to receipt
            emit(receipt); return
        }
        if (mutation(action) && operation?.optString("status") in setOf("pending", "unknown")) { fail("原操作仍在处理或回执待核对，请先读取原现状。"); return }
        if (action == "repair.review" && operation?.optString("status") == "unknown") { fail("请先核对原操作，不沿用未确认的维修结果。"); return }
        if (mutation(action)) {
            operation = JSONObject().put("requestId", payload.optString("requestId", id)).put("command", action)
                .put("generation", payload.optLong("expectedGeneration", -1)).put("repairId", payload.optString("repairId"))
                .put("status", "pending")
            queryId = null; publish()
        }
        val frozen = JSONObject(payload.toString())
        pending[id] = signature
        worker.execute {
            var entered = false
            val value = runCatching {
                check(current(original)) { "原维护窗口已关闭，排队操作未执行。" }
                entered = true
                app.bootstrapOwnerCommand(if (action == "app.refresh") "bootstrap.read" else action, frozen, original.owner)
            }
            main.post {
                pending.remove(id)
                if (closed) return@post
                val current = current(original)
                if (mutation(action)) operation?.let { originalOperation ->
                    if (originalOperation.optString("requestId") == frozen.optString("requestId", id)) {
                        originalOperation.put("status", if (!current || value.isFailure && entered) "unknown" else "completed")
                        originalOperation.put("receipt", if (value.isSuccess) "success" else if (!entered) "not_sent" else "unknown")
                        value.getOrNull()?.optJSONObject("maintenance")?.optString("repairId")?.takeIf { it.isNotBlank() }?.let { originalOperation.put("repairId", it) }
                    }
                }
                if (current && value.isSuccess && action in setOf("bootstrap.read", "repair.read", "app.refresh") && operation != null && operation?.optString("status") != "pending") {
                    val repair = operation?.optString("repairId").orEmpty()
                    if (repair.isBlank() || action == "repair.read" && frozen.optString("repairId") == repair) queryId = UUID.randomUUID().toString()
                }
                publish()
                if (!current) return@post
                val receipt = value.fold({ result(id, true, it) }, { result(id, false, JSONObject().put("message", it.message?.take(500) ?: "维护操作未能确认，请保留原记录并核对。")
                    .put("code", if (entered && mutation(action)) "outcome_unknown" else "not_sent")) })
                receipts[id] = signature to receipt
                while (receipts.size > 64) receipts.remove(receipts.keys.first())
                emit(receipt)
            }
        }
    }
    private fun result(id: String, ok: Boolean, value: JSONObject) = JSONObject().put("v", 1).put("kind", "result").put("id", id).put("ok", ok).put(if (ok) "value" else "error", value)
    override fun foreground(value: Boolean) {
        foreground = value
        if (value && !closed && !window.alive.get()) window = Window()
        if (!value) invalidatePresentation(false)
        main.removeCallbacks(poll)
        if (value && started && !closed) main.post(poll)
    }
    override fun invalidatePresentation(documentReplaced: Boolean) {
        window.alive.set(false)
        if (operation?.optString("status") in setOf("pending", "completed")) operation?.put("status", "unknown")
        queryId = null
        if (documentReplaced) { document = null; receipts.clear() }
        if (foreground && !closed) window = Window()
        lastState = ""
    }
    override fun restorePresentationState(saved: JSONObject?) {
        val retained = saved?.optJSONObject("bootstrapOperation") ?: return
        if (retained.optString("status") in setOf("pending", "unknown", "completed")) operation = JSONObject(retained.toString()).put("status", "unknown")
    }
    override fun savePresentationState(): JSONObject = JSONObject().also { saved ->
        operation?.let { saved.put("bootstrapOperation", JSONObject(it.toString()).put("status", "unknown")) }
    }
    override fun close() { closed = true; window.alive.set(false); main.removeCallbacksAndMessages(null); worker.shutdown(); receipts.clear() }
}
