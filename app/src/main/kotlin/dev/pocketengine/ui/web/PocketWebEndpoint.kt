package dev.pocketengine.ui.web

import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/** Startup and the workspace use one frontend document with separate host authority. */
internal interface PocketWebEndpoint {
    val surface: String
    fun canInteract(): Boolean
    fun start()
    fun foreground(value: Boolean) {}
    fun attachProjectPreview(container: android.widget.FrameLayout) {}
    fun resource(uri: android.net.Uri, method: String, headers: Map<String, String>): android.webkit.WebResourceResponse? = null
    fun captureDispatch(raw: String, stale: () -> Unit): () -> Unit
    fun invalidatePresentation(documentReplaced: Boolean)
    fun restorePresentationState(saved: JSONObject?)
    fun savePresentationState(): JSONObject
    fun close()
}

internal class PocketWebWorkspaceEndpoint(
    private val controller: PocketWorldController,
    emit: (JSONObject) -> Unit
) : PocketWebEndpoint {
    private val bridge = PocketWebBridge(controller, emit)
    override val surface = "business"
    override fun canInteract() = (controller.webPresentationActivity().application as dev.pocketengine.EngineApplication).businessReady() && controller.webUiCanInteract()
    override fun start() = bridge.start()
    override fun foreground(value: Boolean) = bridge.foreground(value)
    override fun attachProjectPreview(container: android.widget.FrameLayout) = bridge.attachProjectPreview(container)
    override fun resource(uri: android.net.Uri, method: String, headers: Map<String, String>) = bridge.resource(uri, method, headers)
    override fun captureDispatch(raw: String, stale: () -> Unit): () -> Unit {
        val original = bridge.captureWindow()
        return { if (bridge.acceptsWindow(original)) bridge.postMessage(raw, original, stale) else stale() }
    }
    override fun invalidatePresentation(documentReplaced: Boolean) = bridge.invalidatePresentation(documentReplaced)
    override fun restorePresentationState(saved: JSONObject?) = bridge.restorePresentationState(saved)
    override fun savePresentationState() = bridge.savePresentationState()
    override fun close() = bridge.close()
}
