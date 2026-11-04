package dev.pocketengine.browser

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import dev.pocketengine.BrowserActivity
import dev.pocketengine.ui.web.BrowserChromeOwnerUi
import dev.pocketengine.ui.web.PocketWebBrowserChrome
import dev.pocketengine.ui.web.PocketWebHost
import kotlin.math.ceil

/** The toolbar is a separate fixed-origin WebView above, never over, the real website WebViews. */
class BrowserChromeController(private val activity: BrowserActivity, private val owner: BrowserChromeOwnerUi) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var endpoint: PocketWebBrowserChrome? = null
    private var host: PocketWebHost? = null
    private var resumed = false
    private var active = false
    private var closed = false
    private var keyboardVisible = false
    private val poll = object : Runnable {
        override fun run() {
            if (closed || !resumed) return
            refresh()
            main.postDelayed(this, 250L)
        }
    }
    val view: View? get() = host?.view

    fun install(root: LinearLayout) {
        check(!closed && host == null)
        try {
            val created = PocketWebHost.browserChrome(activity, { emit ->
                PocketWebBrowserChrome(owner, emit).also { endpoint = it }
            }, failed = ::failed)
            host = created
            created.view.tag = "browser_chrome"
            created.view.isSaveEnabled = false
            created.view.isSaveFromParentEnabled = false
            root.addView(created.view, 0, LinearLayout.LayoutParams(-1, height()))
            created.start()
        } catch (_: RuntimeException) { failed() }
    }
    /** Android root owns system/IME insets exactly once; the web toolbar only changes its row count. */
    fun insetsChanged(keyboard: Boolean) {
        keyboardVisible = keyboard
        host?.view?.let { view ->
            val desired = height()
            if (view.layoutParams.height != desired) { view.layoutParams = view.layoutParams.apply { height = desired } }
        }
    }
    private fun height(): Int {
        val scale = activity.resources.configuration.fontScale.coerceAtLeast(1f)
        // Match the Web rows, leaving space for enlarged address text rather than scaling all whitespace.
        val first = maxOf(52f, 24f * scale + 24f)
        val second = maxOf(44f, 20f * scale + 16f)
        val status = 16f * scale
        return ceil((first + status + if (keyboardVisible) 0f else second) * activity.resources.displayMetrics.density).toInt()
    }
    fun resumed(value: Boolean) {
        resumed = value
        main.removeCallbacks(poll)
        refresh()
        if (value && !closed) main.post(poll)
    }
    fun refresh() {
        if (closed) return
        val foreground = resumed && owner.canInteract()
        if (foreground != active) {
            active = foreground
            if (foreground) host?.resume() else host?.pause()
        }
        endpoint?.refresh()
    }
    fun clearInputFocus() { host?.view?.clearFocus() }
    private fun failed() {
        if (closed) return
        closed = true; active = false
        main.removeCallbacksAndMessages(null)
        host?.close()
        host?.view?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        endpoint = null; host = null
        owner.unavailable()
    }
    override fun close() {
        if (closed) return
        closed = true; active = false; resumed = false
        main.removeCallbacksAndMessages(null)
        host?.close(); host = null; endpoint = null
    }
}
