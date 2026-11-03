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
import android.webkit.JavascriptInterface
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
    private var documentLoaded = false
    private var documentCommandSeen = false
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
                documentLoaded = false
                documentCommandSeen = false
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (closed || !trusted(Uri.parse(url))) return
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
        web.addJavascriptInterface(TrustedCommands(), "PocketNative")
        view.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        view.setOnApplyWindowInsetsListener { target, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            target.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            if (keyboardVisible != insets.isVisible(WindowInsets.Type.ime())) sendConfiguration(insets)
            insets
        }
        view.requestApplyInsets()
        bridge.restorePresentationState(savedPresentation)
        bridge.start()
        web.loadUrl(ENTRY)
    }

    private fun trusted(uri: Uri): Boolean = uri.scheme == "https" && uri.host == HOST &&
        uri.path.orEmpty().startsWith(PREFIX) && uri.pathSegments.none { it == "." || it == ".." || it.contains('\\') }

    private fun response(uri: Uri, method: String): WebResourceResponse {
        if (trusted(uri) && method == "GET") loader.shouldInterceptRequest(uri)?.let { return it }
        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
    }

    private inner class TrustedCommands {
        @JavascriptInterface fun postMessage(raw: String) {
            if (raw.length > 16 * 1024 * 1024) return
            main.post {
                if (!closed && trusted(Uri.parse(web.url ?: ""))) {
                    // A command proves that the trusted module has installed its receiver;
                    // do not lose its first reply while WebView finishes font/resource loading.
                    if (!documentLoaded) {
                        documentLoaded = true
                        pendingState?.let { pendingState = null; send(it) }
                    }
                    // The first command also repairs configuration sent before the receiver mounted.
                    if (!documentCommandSeen) {
                        documentCommandSeen = true
                        sendConfiguration()
                    }
                    bridge.postMessage(raw)
                }
            }
        }
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
        web.evaluateJavascript("window.PocketWeb&&window.PocketWeb.hostConfiguration(${values})", null)
    }

    fun handleBack(onUnhandled: () -> Unit) {
        if (closed || !documentLoaded) { onUnhandled(); return }
        web.evaluateJavascript("Boolean(window.PocketWeb&&window.PocketWeb.handleBack())") { result ->
            if (!closed && result != "true") onUnhandled()
        }
    }

    fun resume() { if (!closed) { web.onResume(); view.requestApplyInsets(); sendConfiguration() } }
    fun pause() { if (!closed) web.onPause() }
    fun savePresentationState(): String = bridge.savePresentationState().toString()
    fun close() {
        if (closed) return
        closed = true
        pendingState = null
        bridge.close()
        web.removeJavascriptInterface("PocketNative")
        web.stopLoading()
        view.removeView(web)
        web.destroy()
    }
}
