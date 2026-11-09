package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RuntimeDiagnostics
import dev.pocketengine.ui.ComposeActivity
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

/** Private, foreground-owned log viewer. Native recording continues independently. */
internal class PocketWebDeveloperLogs(
    private val controller: PocketWorldController,
    private val activity: ComposeActivity,
    private val capture: () -> PocketWebWindowTicket,
    private val accepts: (PocketWebWindowTicket) -> Boolean,
    private val ownerCurrent: () -> Boolean
) : AutoCloseable {
    companion object {
        val COMMANDS = setOf("developerLogs.open", "developerLogs.page", "developerLogs.capture",
            "developerLogs.capacity", "developerLogs.export", "developerLogs.close")
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "developer-log-reader").apply { isDaemon = true }
    }
    private class Window(val id: String, val ticket: PocketWebWindowTicket)
    @Volatile private var window: Window? = null
    @Volatile private var closed = false

    private fun fields(args: JSONObject, vararg allowed: String) {
        require(args.keys().asSequence().all { it in allowed }) { "开发诊断参数无效" }
    }
    private fun current(opened: Window): Boolean =
        !closed && window === opened && ownerCurrent() && controller.webPrivateOwnerVisible() && accepts(opened.ticket)

    fun clear() { window = null }

    override fun close() {
        if (closed) return
        closed = true
        clear()
        worker.shutdownNow()
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(!closed && ownerCurrent() && controller.webPrivateOwnerVisible()) { "请回到当前私人工作台" }
        if (action == "developerLogs.open") {
            fields(args)
            val opened = Window(UUID.randomUUID().toString(), capture())
            window = opened
            done(JSONObject().put("windowId", opened.id), null)
            return
        }
        val opened = window ?: error("请重新打开开发诊断")
        check(current(opened) && args.optString("windowId") == opened.id) { "原诊断窗口已变化" }
        when (action) {
            "developerLogs.close" -> { fields(args, "windowId"); clear(); done(JSONObject().put("closed", true), null) }
            "developerLogs.export" -> {
                fields(args, "windowId")
                activity.createDeveloperLogExport()
                done(JSONObject().put("pickerOpened", true), null)
            }
            "developerLogs.page", "developerLogs.capacity", "developerLogs.capture" -> {
                val mode = args.optString("mode", "tail")
                val cursor = args.optString("cursor", "")
                val limit = args.optInt("limit", 150)
                val miB = args.optInt("miB", -1)
                if (action == "developerLogs.page") {
                    fields(args, "windowId", "mode", "cursor", "limit")
                    require(mode in setOf("tail", "after", "before") && limit in 1..200 && cursor.length <= 64)
                } else if (action == "developerLogs.capacity") {
                    fields(args, "windowId", "miB")
                    require(miB in setOf(256, 512, 1024, 2048))
                } else {
                    fields(args, "windowId", "enabled")
                    require(args.opt("enabled") is Boolean)
                }
                try {
                    worker.execute {
                        val result = runCatching {
                            when(action) {
                                "developerLogs.page" -> RuntimeDiagnostics.developerLogPage(mode, cursor, limit)
                                "developerLogs.capacity" -> RuntimeDiagnostics.developerLogCapacity(miB)
                                else -> RuntimeDiagnostics.developerLogCapture(args.getBoolean("enabled"))
                            }
                        }
                        main.post {
                            if (!current(opened)) {
                                done(null, "原诊断窗口已失效，请重新打开")
                            } else if (result.isSuccess) {
                                done(result.getOrNull(), null)
                            } else {
                                RuntimeDiagnostics.event("diagnostics", "failed", "DEVELOPER_LOG_READ_FAILED", null, result.exceptionOrNull())
                                done(null, "诊断读取失败，请检查可用存储")
                            }
                        }
                    }
                } catch (rejected: RuntimeException) {
                    done(null, "开发诊断读取队列不可用")
                }
            }
            else -> error("未接入的开发诊断操作")
        }
    }
}
