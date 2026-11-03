package dev.pocketengine.ui

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pocketengine.core.RunControl
import dev.pocketengine.runtime.usage.NativeDiagnosticsHost
import dev.pocketengine.runtime.usage.UsageTrendProjection
import java.util.concurrent.Executor
import org.json.JSONObject

/** One native diagnostics screen. Its lifecycle fences every delayed result and owner export review. */
internal class ModelDiagnosticsUi(
    private val host: NativeDiagnosticsHost,
    private val worker: Executor,
    private val main: Handler,
    private val visible: () -> Boolean
) {
    var configuration by mutableStateOf<JSONObject?>(null); private set
    var report by mutableStateOf<JSONObject?>(null); private set
    var review by mutableStateOf<NativeDiagnosticsHost.ExportReview?>(null); private set
    var exportResult by mutableStateOf<JSONObject?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    private var window: NativeDiagnosticsHost.OwnerWindow? = null
    private var control: RunControl? = null
    private var generation = 0L
    private var foreground = false

    fun open() { close(); if (visible()) window = host.openOwnerWindow() }
    fun foreground(value: Boolean) {
        if (foreground == value) return
        foreground = value; window?.setForeground(value)
        if (!value) { generation++; control?.cancel(); control = null; busy = false; review = null }
    }
    fun close() {
        generation++; control?.cancel(); control = null; window?.close(); window = null
        foreground = false; busy = false; review = null; configuration = null; report = null; error = null; exportResult = null
    }
    fun refresh() = request({ w, _ -> host.snapshot(w) }) { configuration = it }
    fun query(from: Long, until: Long, zone: String) {
        val q = runCatching { UsageTrendProjection.Query(from, until, zone) }.getOrElse { error = it.message; return }
        request({ w, c -> host.trends(w, q, c) }) { report = it }
    }
    fun configure(enabled: Boolean, cap: Int, exportEnabled: Boolean, endpoint: String) {
        val expected = configuration?.optLong("generation") ?: return
        request({ w, _ -> host.configure(w, expected, enabled, cap, exportEnabled, endpoint) }) { configuration = it; review = null }
    }
    fun clear() {
        val expected = configuration?.optLong("generation") ?: return
        request({ w, _ -> host.clear(w, expected) }) { configuration = it; review = null }
    }
    fun capture(task: String) {
        val expected = configuration?.optLong("generation") ?: return
        request({ w, c -> host.capture(w, expected, task, c) }) { configuration = it }
    }
    fun prepare(id: String) {
        val expected = configuration?.optLong("generation") ?: return
        request({ w, c -> host.prepareExport(w, expected, id, c) }) { review = it }
    }
    fun cancelReview() { review = null }
    fun send() {
        val original = review ?: return
        review = null
        request({ w, c -> host.send(w, original, c) }) { exportResult = it }
    }
    fun cancel() { generation++; control?.cancel(); control = null; busy = false; review = null }

    private fun <T> request(work: (NativeDiagnosticsHost.OwnerWindow, RunControl) -> T, done: (T) -> Unit) {
        if (busy || !visible() || !foreground) return
        val owner = window ?: return
        val current = ++generation
        val original = RunControl(); control = original; busy = true; error = null
        worker.execute {
            val result = runCatching { original.check(); owner.check(); work(owner, original) }
            main.post {
                if (current != generation || window !== owner || !visible() || !foreground) return@post
                busy = false; control = null
                result.fold(done) { error = it.message ?: "诊断未完成，请核对原任务记录" }
            }
        }
    }
}
