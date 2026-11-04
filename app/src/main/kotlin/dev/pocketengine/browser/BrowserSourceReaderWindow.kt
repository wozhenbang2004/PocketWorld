package dev.pocketengine.browser

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.WindowInsetsController
import dev.pocketengine.ui.UiAppearance
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pocketengine.BrowserActivity
import dev.pocketengine.ui.web.BrowserSourceReaderTarget
import dev.pocketengine.ui.web.PocketWebHost

/** A window above Compose's sheet window. It hosts Web controls, never native business widgets. */
internal class BrowserSourceReaderWindow(
    private val activity: BrowserActivity,
    private val taskHost: BrowserTaskHost,
    private val target: BrowserSourceReaderTarget,
    private val onFailure: (String) -> Unit,
    private val onDismissed: () -> Unit
) : AutoCloseable {
    @Volatile private var focused = false
    @Volatile private var closed = false
    @Volatile private var foreground = false
    private var readerHost: PocketWebHost? = null
    private val dialog = object : ComponentDialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar) {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            super.onWindowFocusChanged(hasFocus)
            focused = hasFocus
            updateForeground()
        }
    }
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) close() else updateForeground()
    }

    fun open() {
        check(!closed && readerHost == null)
        activity.lifecycle.addObserver(observer)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { dispose() }
        dialog.onBackPressedDispatcher.addCallback(dialog, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
        try {
            val host = PocketWebHost.browserSourceReader(activity, taskHost, target,
                focused = { !closed && focused && foreground },
                dismiss = ::close,
                failed = { onFailure("原文阅读窗口未能加载，已返回原结果。网页与任务没有重试。"); close() })
            readerHost = host
            dialog.setContentView(host.view, ViewGroup.LayoutParams(-1, -1))
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(UiAppearance.surface(activity)))
                statusBarColor = Color.TRANSPARENT
                navigationBarColor = Color.TRANSPARENT
                val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                insetsController?.setSystemBarsAppearance(if (UiAppearance.dark(activity)) 0 else appearance, appearance)
                setDecorFitsSystemWindows(false)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            }
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            host.start()
            updateForeground()
        } catch (_: RuntimeException) {
            onFailure("原文阅读窗口暂时无法打开，已保留原结果与网页。")
            close()
        }
    }

    /** Always consumes native Back while opening, attached or closing. Never forwards waiting JS Back to the page. */
    fun handleBack() {
        if (closed) return
        val host = readerHost
        if (host == null) close() else host.handleBack(::close)
    }
    private fun updateForeground() {
        val active = !closed && focused && dialog.isShowing && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (active == foreground) return
        foreground = active
        if (active) readerHost?.resume() else readerHost?.pause()
    }
    override fun close() {
        if (closed) return
        // Invalidate immediately, but keep the owner obstruction until onDismiss confirms detachment.
        focused = false; foreground = false
        readerHost?.pause()
        if (dialog.isShowing) dialog.dismiss() else dispose()
    }
    private fun dispose() {
        if (closed) return
        closed = true; focused = false; foreground = false
        activity.lifecycle.removeObserver(observer)
        readerHost?.close(); readerHost = null
        onDismissed()
    }
}
