package dev.pocketengine.ui.web

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Only this local chrome endpoint sees the live address. No storage, task dispatch or website bridge. */
internal class PocketWebBrowserChrome(
    private val owner: BrowserChromeOwnerUi,
    private val emit: (JSONObject) -> Unit
) : PocketWebEndpoint {
    override val surface = "browser_chrome"
    private val windowId = UUID.randomUUID().toString()
    private var generation = 0L
    private var document: String? = null
    private var context: Any? = null
    private var contextToken = ""
    private var foreground = false
    private var closed = false
    private var revision = 0L
    private var previousProjection = ""
    private val seen = linkedSetOf<String>()

    override fun canInteract() = !closed && foreground && owner.canInteract()
    override fun start() = refresh(force = true)
    override fun captureDispatch(raw: String, stale: () -> Unit): () -> Unit {
        val original = generation
        // Ready only binds this chrome document. A navigating website must not break its handshake.
        val ready = runCatching { JSONObject(raw).optString("action") == "app.ready" }.getOrDefault(false)
        // Capture action identity before queuing, not just the last rendered URL.
        val originalContext = runCatching { owner.nativeState().context }.getOrNull()
        return {
            if (canInteract() && generation == original && (ready || originalContext != null &&
                runCatching { owner.nativeState().context == originalContext }.getOrDefault(false))) dispatch(raw)
            else { refresh(force = true); stale() }
        }
    }
    private fun dispatch(raw: String) {
        if (raw.length > 65536) return
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = envelope.opt("id") as? String ?: return
        val suppliedDocument = envelope.opt("documentId") as? String ?: return
        if (envelope.optInt("v") != 1 || id.isBlank() || id.length > 160 || suppliedDocument.isBlank() || suppliedDocument.length > 160) return
        fun fail() = result(id, false, JSONObject().put("code", "not_sent").put("message", "网页或操作窗口已变化。请核对当前页面后再操作；没有自动重试。"))
        if (!canInteract()) { fail(); return }
        when (envelope.optString("action")) {
            "app.ready" -> {
                if (document != null && document != suppliedDocument) { fail(); return }
                document = suppliedDocument
                refresh(force = true); result(id, true, JSONObject().put("accepted", true)); return
            }
            "browserChrome.action" -> Unit
            else -> { fail(); return }
        }
        if (document != suppliedDocument || id in seen) { fail(); return }
        val state = runCatching { owner.nativeState() }.getOrNull() ?: run { fail(); return }
        updateContext(state)
        val command = runCatching { BrowserChromeContract.parse(envelope.getJSONObject("args"), windowId, generation, contextToken) }.getOrNull()
            ?: run { refresh(force = true); fail(); return }
        if (state.navigationUnconfirmed && command.action in setOf(BrowserChromeAction.NAVIGATE, BrowserChromeAction.BACK, BrowserChromeAction.FORWARD, BrowserChromeAction.RELOAD)) {
            fail(); refresh(force = true); return
        }
        // A bounded replay set fails closed instead of silently allowing an old id after eviction.
        if (seen.size >= 1024) { fail(); return }
        seen.add(id)
        if (!runCatching { canInteract() && owner.nativeState().context == state.context }.getOrDefault(false)) {
            fail(); refresh(force = true); return
        }
        val accepted = runCatching { owner.perform(state.context, command.action, command.input) }
        accepted.onSuccess { sent ->
            if (sent) result(id, true, JSONObject().put("accepted", true).put("verified", false)
                .put("status", if (command.action in setOf(BrowserChromeAction.NAVIGATE, BrowserChromeAction.BACK, BrowserChromeAction.FORWARD, BrowserChromeAction.RELOAD)) "navigation_requested" else "requested"))
            else fail()
        }.onFailure {
            // An exception after entry does not prove a side effect did not happen.
            // Keep a native read-only warning even if navigation retires the old JS context.
            runCatching { owner.outcomeUnknown() }
            result(id, false, JSONObject().put("code", "outcome_unknown").put("message", "操作回执尚未确认。请核对当前页面；不会自动重试。"))
        }
        refresh(force = true)
    }
    private fun updateContext(state: BrowserChromeState) {
        if (context != state.context) { context = state.context; contextToken = UUID.randomUUID().toString() }
    }
    fun refresh(force: Boolean = false) {
        if (closed) return
        val state = runCatching { owner.nativeState() }.getOrNull() ?: return
        updateContext(state)
        val chrome = BrowserChromeContract.project(state).put("windowId", windowId).put("generation", generation)
            .put("contextToken", contextToken).put("ready", canInteract() && document != null)
        val projection = chrome.toString()
        if (!force && projection == previousProjection) return
        previousProjection = projection
        emit(JSONObject().put("v", 1).put("kind", "state").put("revision", ++revision).put("snapshot", JSONObject()
            .put("surface", surface).put("state", JSONObject()).put("composer", JSONObject.NULL)
            .put("browserChrome", chrome).put("host", JSONObject().put("surface", surface))
            .put("capabilities", JSONObject().put("commands", JSONArray(listOf("app.ready", "browserChrome.action"))))))
    }
    private fun result(id: String, ok: Boolean, value: JSONObject) {
        if (!closed) emit(JSONObject().put("v", 1).put("kind", "result").put("id", id).put("ok", ok).put(if (ok) "value" else "error", value))
    }
    override fun foreground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (!value) invalidatePresentation(documentReplaced = false)
    }
    override fun invalidatePresentation(documentReplaced: Boolean) {
        generation++; document = null; context = null; contextToken = ""; seen.clear(); previousProjection = ""
        refresh(force = true)
    }
    override fun restorePresentationState(saved: JSONObject?) = Unit
    override fun savePresentationState() = JSONObject()
    override fun close() { closed = true; foreground = false; generation++; document = null; context = null; contextToken = ""; seen.clear(); previousProjection = "" }
}
