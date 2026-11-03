package dev.pocketengine.ui.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.pocketengine.ui.ComposeActivity
import dev.pocketengine.ui.PocketWorldController
import java.io.ByteArrayInputStream
import org.json.JSONObject

/** Android hosts capabilities; the selected, bundled Web components own every business control. */
internal class PocketWebHost(
    private val activity: ComposeActivity,
    private val controller: PocketWorldController,
    private val savedPresentation: JSONObject? = null
) {
    companion object {
        private const val TAG = "PocketWebUi"
        private const val HOST = "pocketengine.local"
        private const val PREFIX = "/assets/pocket-ui/"
        private const val ENTRY = "https://$HOST${PREFIX}index.html"
    }

    val view = FrameLayout(activity).apply { setBackgroundColor(Color.rgb(245, 245, 245)) }
    private val main = Handler(Looper.getMainLooper())
    private val web = WebView(activity)
    private var closed = false
    private var listenerRegistered = false
    private val documentFence = PocketWebDocumentFence()
    private var documentToken: String? = null
    private var documentLoaded = false
    private var documentCommandSeen = false
    private var reopenShown = false
    private var keyboardVisible = false
    private var pendingState: JSONObject? = null
    private val bridge = PocketWebBridge(controller, ::send)
    private val loader = WebViewAssetLoader.Builder().setDomain(HOST)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity)).build()

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            blockNetworkLoads = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            textZoom = (activity.resources.configuration.fontScale * 100).toInt()
            builtInZoomControls = false
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.setBackgroundColor(Color.rgb(245, 245, 245))
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (trusted(request.url)) return false
                if (request.isForMainFrame && request.hasGesture() && request.url.scheme in setOf("https", "http")) {
                    try { activity.startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    catch (_: ActivityNotFoundException) { Log.w(TAG, "source-browser-unavailable") }
                }
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = !trusted(Uri.parse(url))
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                response(request.url, request.method)
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse = response(Uri.parse(url), "GET")
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (closed || documentFence.isRejected()) return
                documentToken = null
                bridge.invalidatePresentation(documentReplaced = true)
                documentLoaded = false
                documentCommandSeen = false
                if (!documentFence.beginDocument()) {
                    showReopenPrompt()
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (closed || documentFence.isRejected() || !trusted(Uri.parse(url))) return
                documentToken = documentFence.loaded()
                documentLoaded = true
                pendingState?.let { pendingState = null; send(it) }
                sendConfiguration()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                Log.e(TAG, "resource-error code=${error.errorCode} path=${request.url.path}")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                if (error.statusCode >= 400) Log.e(TAG, "resource-error status=${error.statusCode} path=${request.url.path}")
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel() }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.e(TAG, "renderer-exit crashed=${detail.didCrash()}")
                close()
                // Closing the view does not stop, retry or resume any engine task.
                activity.finish()
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val text = message.message().take(1600)
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.e(TAG, "console-error $text")
                else if (text.contains("PocketWebUi")) Log.i(TAG, text)
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, userGesture: Boolean, message: Message) = false
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsPrompt(view: WebView, url: String, message: String, initial: String?, result: JsPromptResult): Boolean { result.cancel(); return true }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean { callback.onReceiveValue(null); return true }
        }
        web.setDownloadListener { _, _, _, _, _ -> Log.w(TAG, "blocked-page-download") }
        bridge.restorePresentationState(savedPresentation)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            view.addView(android.widget.TextView(activity).apply {
                text = "当前系统 WebView 不支持安全工作台，请更新 Android System WebView 后重试。"
                contentDescription = text
            })
            bridge.start()
            return
        }
        WebViewCompat.addWebMessageListener(web, "PocketNative", setOf("https://$HOST")) { _, message, origin, isMainFrame, _ ->
            val raw = runCatching { message.data }.getOrNull() ?: return@addWebMessageListener
            if (raw.length > 16 * 1024 * 1024) return@addWebMessageListener
            val token = runCatching { JSONObject(raw).optString("hostDocumentToken") }.getOrNull()
            val generation = documentFence.capture(isMainFrame, origin.scheme, origin.host, origin.port, token, controller.webUiCanInteract())
                ?: return@addWebMessageListener
            val window = bridge.captureWindow()
            val isReady = runCatching { JSONObject(raw).optString("action") == "app.ready" }.getOrDefault(false)
            fun documentStillCurrent() = !closed && documentLoaded && trusted(Uri.parse(web.url ?: "")) &&
                documentFence.accepts(generation, token, controller.webUiCanInteract())
            fun staleWindow() { if (isReady && documentStillCurrent()) showReopenPrompt() }
            main.post {
                if (!documentStillCurrent()) return@post
                if (!bridge.acceptsWindow(window)) { staleWindow(); return@post }
                if (!documentCommandSeen) { documentCommandSeen = true; sendConfiguration() }
                bridge.postMessage(raw, window, ::staleWindow)
            }
        }
        listenerRegistered = true
        view.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        view.setOnApplyWindowInsetsListener { target, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            target.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            if (keyboardVisible != insets.isVisible(WindowInsets.Type.ime())) sendConfiguration(insets)
            insets
        }
        view.requestApplyInsets()
        bridge.start()
        web.loadUrl(ENTRY)
    }

    private fun showReopenPrompt() {
        if (closed || reopenShown) return
        reopenShown = true
        documentFence.close(); documentToken = null; documentLoaded = false
        bridge.invalidatePresentation(documentReplaced = true)
        web.stopLoading(); web.visibility = View.GONE
        view.addView(android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(android.widget.TextView(activity).apply {
                text = "工作台页面已变化，请重新打开。未提交的输入可能丢失；已提交的任务不会因此重试。"
            })
            addView(android.widget.Button(activity).apply {
                text = "重新打开工作台"
                setOnClickListener { activity.recreate() }
            })
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun trusted(uri: Uri): Boolean = uri.scheme == "https" && uri.host == HOST &&
        uri.path.orEmpty().startsWith(PREFIX) && uri.pathSegments.none { it == "." || it == ".." || it.contains('\\') }

    private fun response(uri: Uri, method: String): WebResourceResponse {
        if (trusted(uri) && method == "GET") loader.shouldInterceptRequest(uri)?.let { return it }
        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    }

    private fun send(message: JSONObject) {
        if (closed) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { send(message) }; return }
        if (!documentLoaded) {
            if (message.optString("kind") == "state") pendingState = message
            return
        }
        val encoded = JSONObject.quote(message.toString())
        web.evaluateJavascript("window.PocketWeb&&window.PocketWeb.receive($encoded)", null)
    }

    private fun sendConfiguration(insets: WindowInsets? = null) {
        if (closed) return
        // Keep the actual IME state even while a new document is still loading.
        (insets ?: view.rootWindowInsets)?.let { keyboardVisible = it.isVisible(WindowInsets.Type.ime()) }
        if (!documentLoaded || !trusted(Uri.parse(web.url ?: ""))) return
        val version = WebView.getCurrentWebViewPackage()?.versionName.orEmpty()
        Log.i(TAG, "host source=library-web provider=$version")
        val values = JSONObject().put("webViewVersion", version).put("fontScale", activity.resources.configuration.fontScale)
            .put("keyboardVisible", keyboardVisible)
        val token = documentToken?.takeIf { controller.webUiCanInteract() }
        if (token != null) values.put("documentToken", token)
        val bootstrap = token?.let { "window.__pocketDocumentToken=" + JSONObject.quote(it) + ";" }.orEmpty()
        web.evaluateJavascript(bootstrap + "window.PocketWeb&&window.PocketWeb.hostConfiguration(${values})", null)
    }

    fun handleBack(onUnhandled: () -> Unit) {
        if (closed || !documentLoaded) { onUnhandled(); return }
        web.evaluateJavascript("Boolean(window.PocketWeb&&window.PocketWeb.handleBack())") { result ->
            if (!closed && result != "true") onUnhandled()
        }
    }

    fun resume() { if (!closed) { web.onResume(); view.requestApplyInsets(); main.post {
        if (!closed && !documentFence.isRejected()) {
            if (documentLoaded) documentToken = documentFence.loaded()
            sendConfiguration()
        }
    } } }
    fun pause() { if (!closed) { documentFence.pause(); documentToken = null; bridge.invalidatePresentation(documentReplaced = false); web.onPause() } }
    fun canSwitchPresentation(): Boolean = !closed && bridge.canSwitchPresentation()
    fun savePresentationState(): String = bridge.savePresentationState().toString()
    fun close() {
        if (closed) return
        closed = true
        documentFence.close()
        documentToken = null
        pendingState = null
        bridge.close()
        if (listenerRegistered) { WebViewCompat.removeWebMessageListener(web, "PocketNative"); listenerRegistered = false }
        web.stopLoading()
        view.removeView(web)
        web.destroy()
    }
}
