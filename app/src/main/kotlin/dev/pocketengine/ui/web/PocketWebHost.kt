package dev.pocketengine.ui.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Insets
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
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.pocketengine.core.RuntimeDiagnostics
import dev.pocketengine.ui.ComposeActivity
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.UiAppearance
import java.io.ByteArrayInputStream
import org.json.JSONObject

/** Android hosts capabilities; the selected, bundled Web components own every business control. */
internal class PocketWebHost private constructor(
    private val activity: ComponentActivity,
    endpoint: ((JSONObject) -> Unit) -> PocketWebEndpoint,
    private val savedPresentation: JSONObject? = null,
    private val readerFailure: (() -> Unit)? = null,
    private val ownsSystemInsets: Boolean = true
) {
    constructor(activity: ComposeActivity, controller: PocketWorldController, savedPresentation: JSONObject? = null) :
        this(activity, { emit -> PocketWebWorkspaceEndpoint(controller, emit) }, savedPresentation)

    companion object {
        private const val TAG = "PocketWebUi"
        private const val HOST = "pocketengine.local"
        private const val PREFIX = "/assets/pocket-ui/"
        private const val ENTRY = "https://$HOST${PREFIX}index.html"
        fun bootstrap(activity: ComposeActivity, app: dev.pocketengine.EngineApplication, saved: JSONObject?, ready: () -> Unit) =
            PocketWebHost(activity, { emit -> PocketWebBootstrap(activity, app, emit, ready) }, saved)
        fun browserSourceReader(activity: dev.pocketengine.BrowserActivity, host: dev.pocketengine.browser.BrowserTaskHost,
            target: BrowserSourceReaderTarget, focused: () -> Boolean, dismiss: () -> Unit, failed: () -> Unit) =
            PocketWebHost(activity, { emit -> PocketWebBrowserSources(target, host, focused, emit, dismiss) }, readerFailure = failed)
        fun browserChrome(activity: dev.pocketengine.BrowserActivity,
            endpoint: ((JSONObject) -> Unit) -> PocketWebEndpoint, failed: () -> Unit) =
            PocketWebHost(activity, endpoint, readerFailure = failed, ownsSystemInsets = false)
        fun browserWorkspace(activity: dev.pocketengine.BrowserActivity,
            endpoint: ((JSONObject) -> Unit) -> PocketWebEndpoint, failed: () -> Unit) =
            PocketWebHost(activity, endpoint, readerFailure = failed, ownsSystemInsets = false)
    }

    val view = FrameLayout(activity)
    private val main = Handler(Looper.getMainLooper())
    private val web = WebView(activity)
    private var closed = false
    private var webDestroyed = false
    private var listenerRegistered = false
    private val documentFence = PocketWebDocumentFence()
    private var documentToken: String? = null
    private var documentLoaded = false
    private var documentCommandSeen = false
    private var reopenShown = false
    private var keyboardVisible = false
    private var pendingState: JSONObject? = null
    private var readerBound = false
    private var readerBackPending = false
    private var readerBackTimeout: Runnable? = null
    private val readerStartupTimeout = Runnable { if (!readerBound && bridge.canInteract()) failReader() }
    private val bridge = endpoint(::send)
    private var canvasRole = if (bridge.surface == "business") "chat" else "workspace"
    private var appliedBackgroundColor: Int? = null
    private var bundle: PocketWebBundle? = null
    private val loader = WebViewAssetLoader.Builder().setDomain(HOST)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity)).build()

    init {
        applyWorkspaceBackground()
        view.setOnApplyWindowInsetsListener { target, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            // An embedded browser toolbar shares the BrowserActivity root's inset owner.
            // Applying bars or IME padding again would consume its narrow content band.
            if (ownsSystemInsets) target.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            if (keyboardVisible != insets.isVisible(WindowInsets.Type.ime())) sendConfiguration(insets)
            insets
        }
        // Only the bundled UI has already been laid out inside the native safe area.
        // Keep the parent's original dispatch intact for sibling native previews.
        web.setOnApplyWindowInsetsListener { target, insets ->
            val bars = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            val handled = WindowInsets.Builder(insets)
                .setInsets(bars or WindowInsets.Type.ime(), Insets.NONE)
                .setInsetsIgnoringVisibility(bars, Insets.NONE)
                .setDisplayCutout(null)
                .build()
            target.onApplyWindowInsets(handled)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        // Keep saved input available to the same workspace's retry even if its bundle cannot load.
        bridge.restorePresentationState(savedPresentation)
        bundle = try {
            RuntimeDiagnostics.observe("webview", null, "BUNDLE_IDENTITY_UNAVAILABLE") {
                activity.assets.open("pocket-ui/build-identity.json").use { PocketWebBundle.read(it) }
            }
        } catch (_: Exception) {
            if (failReader()) return
            // The renderer cannot report an invalid packaged identity; retry reopens this same UI.
            Log.e(TAG, "bundle-identity-unavailable; install a complete compatible package")
            showReopenPrompt()
            return
        }
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
        applyWorkspaceBackground()
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // The saved reader has no live-navigation authority, including trusted document replacement.
                if (readerFailure != null) { if (request.isForMainFrame) failReader(); return true }
                if (trusted(request.url)) return false
                if (request.isForMainFrame && request.hasGesture() && request.url.scheme in setOf("https", "http")) {
                    try { activity.startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    catch (_: ActivityNotFoundException) { Log.w(TAG, "source-browser-unavailable") }
                }
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (readerFailure != null) { failReader(); return true }
                return !trusted(Uri.parse(url))
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                response(request.url, request.method, request.requestHeaders)
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse = response(Uri.parse(url), "GET")
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (closed || documentFence.isRejected()) return
                if (readerFailure != null && url != ENTRY) { failReader(); return }
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
                RuntimeDiagnostics.event("webview", "failed", "RESOURCE_LOAD_FAILED", null, null)
                if (readerFailure != null) failReader()
                else if (criticalWorkspaceResource(request)) showReopenPrompt()
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                if (error.statusCode >= 400) {
                    RuntimeDiagnostics.event("webview", "failed", "RESOURCE_HTTP_FAILED", null, null)
                    if (readerFailure != null) failReader()
                    else if (criticalWorkspaceResource(request)) showReopenPrompt()
                }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel(); if (readerFailure != null) failReader() }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.e(TAG, "renderer-exit crashed=${detail.didCrash()}")
                if (failReader()) return true
                // Reopen the same workspace after the renderer exits.
                // The controller and all submitted tasks retain their original identities.
                showReopenPrompt()
                return true
            }
        }
        web.webChromeClient = object : PocketWebConsoleClient({
            if (readerFailure != null) failReader() else showReopenPrompt()
        }) {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, userGesture: Boolean, message: Message) = false
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsPrompt(view: WebView, url: String, message: String, initial: String?, result: JsPromptResult): Boolean { result.cancel(); return true }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean { callback.onReceiveValue(null); return true }
        }
        web.setDownloadListener { _, _, _, _, _ -> Log.w(TAG, "blocked-page-download") }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewCompat.addWebMessageListener(web, "PocketNative", setOf("https://$HOST")) { _, message, origin, isMainFrame, _ ->
            val raw = runCatching { message.data }.getOrNull() ?: return@addWebMessageListener
            if (raw.length > 16 * 1024 * 1024) return@addWebMessageListener
            val token = runCatching { JSONObject(raw).optString("hostDocumentToken") }.getOrNull()
            val generation = documentFence.capture(isMainFrame, origin.scheme, origin.host, origin.port, token, bridge.canInteract())
                ?: return@addWebMessageListener
            val isReady = runCatching { JSONObject(raw).optString("action") == "app.ready" }.getOrDefault(false)
            fun documentStillCurrent() = !closed && documentLoaded && trusted(Uri.parse(web.url ?: "")) &&
                documentFence.accepts(generation, token, bridge.canInteract())
            fun staleWindow() { if (isReady && documentStillCurrent()) showReopenPrompt() }
            val dispatch = bridge.captureDispatch(raw, ::staleWindow)
            main.post {
                if (!documentStillCurrent()) return@post
                if (!documentCommandSeen) { documentCommandSeen = true; sendConfiguration() }
                dispatch()
            }
        }
        listenerRegistered = true
        }
        if (!listenerRegistered) { if (!failReader()) showReopenPrompt(); return }
        view.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        bridge.attachProjectPreview(view)
        view.requestApplyInsets()
        bridge.start()
        web.loadUrl(ENTRY)
        armReaderStartupTimeout()
    }

    /** Isolated reader/chrome faults never finish or recreate BrowserActivity or invent repair controls. */
    private fun failReader(): Boolean {
        val failed = readerFailure ?: return false
        if (!closed) { close(); failed() }
        return true
    }
    private fun armReaderStartupTimeout() {
        main.removeCallbacks(readerStartupTimeout)
        if (readerFailure != null && !closed && !readerBound && bridge.canInteract()) main.postDelayed(readerStartupTimeout, 30_000L)
    }

    private fun showReopenPrompt() {
        if (closed || reopenShown) return
        if (failReader()) return
        reopenShown = true
        documentFence.close(); documentToken = null; documentLoaded = false
        bridge.invalidatePresentation(documentReplaced = true)
        destroyWeb()
        view.addView(android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val dark = dev.pocketengine.ui.UiAppearance.dark(activity)
            val ink = if (dark) Color.rgb(232, 232, 232) else Color.rgb(36, 36, 36)
            setBackgroundColor(UiAppearance.canvas(activity, canvasRole))
            val space = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(space, space, space, space)
            fun recoveryButton(label: String, action: () -> Unit) = android.widget.Button(activity).apply {
                text = label
                isAllCaps = false
                setTextColor(ink)
                backgroundTintList = android.content.res.ColorStateList.valueOf(if (dark) Color.rgb(43, 43, 43) else Color.rgb(235, 235, 235))
                setOnClickListener { action() }
            }
            addView(android.widget.TextView(activity).apply {
                setTextColor(ink)
                text = "工作台暂时无法显示，请重新打开。未保存的输入可能丢失；已提交的任务不会重复执行。"
            })
            addView(recoveryButton("重新打开工作台") { activity.recreate() })
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    // Font or optional media failure must not discard an otherwise usable workspace.
    private fun criticalWorkspaceResource(request: WebResourceRequest): Boolean =
        request.isForMainFrame || request.url.path.orEmpty().let { path ->
            path.startsWith(PREFIX + "assets/") && (path.endsWith(".js") || path.endsWith(".css"))
        }

    private fun trusted(uri: Uri): Boolean = bundle?.allows(uri.toString()) == true

    private fun response(uri: Uri, method: String, headers: Map<String, String> = emptyMap()): WebResourceResponse {
        bridge.resource(uri, method, headers)?.let { return it }
        if (trusted(uri) && method == "GET") loader.shouldInterceptRequest(uri)?.let { return it }
        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    }

    private fun applyWorkspaceBackground() {
        val ownsWindow = ownsSystemInsets && activity is ComposeActivity
        val color = if (ownsWindow) UiAppearance.canvas(activity, canvasRole)
            else UiAppearance.canvas(activity)
        if (appliedBackgroundColor == color) return
        appliedBackgroundColor = color
        view.setBackgroundColor(color)
        if (!webDestroyed) web.setBackgroundColor(color)
        if (ownsWindow) UiAppearance.window(activity, canvasRole)
    }

    private fun send(message: JSONObject) {
        if (closed) return
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { send(message) }; return }
        if (message.optString("kind") == "state") {
            if (bridge.surface == "business") {
                val role = message.optJSONObject("snapshot")?.optJSONObject("state")
                    ?.optJSONObject("appearance")?.optString("canvas")
                if (role == "chat" || role == "workspace") canvasRole = role
            }
            applyWorkspaceBackground()
            // This is part of every initial/replayed snapshot, not a one-shot JS event.
            // Embedded browser surfaces share BrowserActivity's native inset owner.
            message.optJSONObject("snapshot")?.let { snapshot ->
                val host = snapshot.optJSONObject("host") ?: JSONObject()
                snapshot.put("host", host.put("insetsHandledByNative", true))
            }
        }
        if (readerFailure != null && message.optString("kind") == "state") {
            val stateKey = when (bridge.surface) {
                "browser_chrome" -> "browserChrome"
                "browser_workspace" -> "browserWorkspace"
                else -> "browserSources"
            }
            readerBound = message.optJSONObject("snapshot")?.optJSONObject(stateKey)?.optBoolean("ready") == true
            if (readerBound) main.removeCallbacks(readerStartupTimeout)
        }
        if (!documentLoaded) {
            if (message.optString("kind") == "state") pendingState = message
            return
        }
        val encoded = JSONObject.quote(message.toString())
        try { web.evaluateJavascript("window.PocketWeb&&window.PocketWeb.receive($encoded)", null) }
        catch (_: RuntimeException) {
            if (readerFailure != null) failReader() else showReopenPrompt()
        }
    }

    private fun sendConfiguration(insets: WindowInsets? = null) {
        if (closed) return
        // Keep the actual IME state even while a new document is still loading.
        (insets ?: view.rootWindowInsets)?.let { keyboardVisible = it.isVisible(WindowInsets.Type.ime()) }
        if (!documentLoaded || !trusted(Uri.parse(web.url ?: ""))) return
        val version = WebView.getCurrentWebViewPackage()?.versionName.orEmpty()
        Log.i(TAG, "host source=library-web provider=$version")
        val values = JSONObject().put("webViewVersion", version).put("fontScale", activity.resources.configuration.fontScale)
            .put("keyboardVisible", keyboardVisible).put("surface", bridge.surface)
            .put("bridgeAvailable", listenerRegistered).put("insetsHandledByNative", true)
        runCatching { values.put("appearance", JSONObject()
            .put("mode", dev.pocketengine.ui.UiAppearance.mode(dev.pocketengine.ui.UiAppearance.preferences(activity)))
            .put("dark", dev.pocketengine.ui.UiAppearance.dark(activity))
            .put("typography", dev.pocketengine.ui.UiTypography.snapshot(dev.pocketengine.ui.UiAppearance.preferences(activity)))) }
        if (!listenerRegistered) values.put("bridgeUnavailableReason", "当前系统 WebView 不支持安全通信。请更新 Android System WebView 后重新打开；本页不能重试或维修数据。")
        val token = documentToken?.takeIf { listenerRegistered && bridge.canInteract() }
        if (token != null) values.put("documentToken", token)
        val bootstrap = token?.let { "window.__pocketDocumentToken=" + JSONObject.quote(it) + ";" }.orEmpty()
        web.evaluateJavascript(bootstrap + "window.PocketWeb&&window.PocketWeb.hostConfiguration(${values})", null)
    }

    fun handleBack(onUnhandled: () -> Unit) {
        if (closed || !documentLoaded) { onUnhandled(); return }
        if (readerFailure != null) {
            if (readerBackPending) return
            val token = documentToken
            val ticket = documentFence.capture(true, "https", HOST, -1, token, bridge.canInteract())
            if (ticket == null) { onUnhandled(); return }
            fun current() = !closed && documentFence.accepts(ticket, token, bridge.canInteract())
            readerBackPending = true
            val timeout = Runnable { if (current() && readerBackPending) { readerBackPending = false; onUnhandled() } }
            readerBackTimeout = timeout; main.postDelayed(timeout, 1500L)
            web.evaluateJavascript("Boolean(window.PocketWeb&&window.PocketWeb.handleBack())") { result ->
                if (!current()) return@evaluateJavascript
                main.removeCallbacks(timeout); readerBackPending = false; readerBackTimeout = null
                if (result != "true") onUnhandled()
            }
            return
        }
        web.evaluateJavascript("Boolean(window.PocketWeb&&window.PocketWeb.handleBack())") { result ->
            if (!closed && result != "true") onUnhandled()
        }
    }

    fun resume() { if (!closed) {
        appliedBackgroundColor = null
        applyWorkspaceBackground()
        bridge.foreground(true); if (!webDestroyed) web.onResume(); view.requestApplyInsets(); main.post {
        if (!closed && !documentFence.isRejected()) {
            if (documentLoaded) documentToken = documentFence.loaded()
            sendConfiguration()
            armReaderStartupTimeout()
        }
    } } }
    fun pause() { if (!closed) {
        main.removeCallbacks(readerStartupTimeout)
        readerBackTimeout?.let(main::removeCallbacks); readerBackTimeout = null; readerBackPending = false
        documentFence.pause(); documentToken = null; bridge.foreground(false); bridge.invalidatePresentation(documentReplaced = false); if (!webDestroyed) web.onPause()
    } }
    fun savePresentationState(): String = bridge.savePresentationState().toString()
    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(readerStartupTimeout)
        readerBackTimeout?.let(main::removeCallbacks); readerBackTimeout = null; readerBackPending = false
        documentFence.close()
        documentToken = null
        pendingState = null
        bridge.close()
        destroyWeb()
    }
    private fun destroyWeb() {
        if (webDestroyed) return
        webDestroyed = true
        if (listenerRegistered) { WebViewCompat.removeWebMessageListener(web, "PocketNative"); listenerRegistered = false }
        web.stopLoading()
        view.removeView(web)
        web.destroy()
    }
}
