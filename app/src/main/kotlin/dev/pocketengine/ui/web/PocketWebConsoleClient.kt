package dev.pocketengine.ui.web

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import dev.pocketengine.core.RuntimeDiagnostics

/** All bundled surfaces share this console boundary; the host retains recovery ownership. */
internal open class PocketWebConsoleClient(private val startupFailed: () -> Unit) : WebChromeClient() {
    final override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        // Never export console bodies or source URLs, even from a trusted bundled document.
        val startup = message.message().startsWith("PocketWebUi startup-error")
        if (startup || message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            RuntimeDiagnostics.event("webview", "failed",
                if (startup) "WORKSPACE_STARTUP_FAILED" else "CONSOLE_ERROR", null, null)
        }
        // This comes from the original renderer signal, never from diagnostic state or persistence.
        if (startup) startupFailed()
        return true
    }
}
