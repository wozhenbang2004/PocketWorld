package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pocketengine.features.devices.NativeDeviceWorkspaceHost
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Original SQL child links, read-only queries and explicit exact-operation stop management. The source task may be
 * paused/closed/narrowed; no model, scope restoration, manual effect resolution or resend occurs. */
@Composable
internal fun DeviceTaskHistoryControls(task: UiTask, host: NativeDeviceWorkspaceHost?,
    runOnWorker: (Runnable) -> Unit, nativeRevision: Long, changed: () -> Unit) {
    if (host == null) {
        if (task.raw.optJSONArray("operations").objects().any { it.optString("tool") in setOf("device_invoke_tool", "device_delegate_goal", "device_operation_get") }) Text("设备结果暂不可读取，已有操作记录仍保留。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk, modifier = Modifier.testTag("device_task_history_unavailable"))
        return
    }
    val window = remember(task.id, host) { host.openOwnerWindow() }
    val alive = remember(window) { AtomicBoolean(true) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var links by remember(window) { mutableStateOf<JSONObject?>(null) }
    var busy by remember(window) { mutableStateOf(false) }
    var error by remember(window) { mutableStateOf<String?>(null) }
    var selected by remember(window) { mutableStateOf<NativeDeviceWorkspaceHost.HistoryWindow?>(null) }
    var view by remember(window) { mutableStateOf<JSONObject?>(null) }
    var result by remember(window) { mutableStateOf<JSONObject?>(null) }
    var expanded by remember(window) { mutableStateOf(false) }
    DisposableEffect(window) { onDispose { alive.set(false); window.close() } }
    fun close() { window.discardReview(); selected = null; view = null; result = null; error = null }
    fun work(action: () -> Unit) {
        if (busy || !alive.get()) return
        busy = true; error = null
        try { runOnWorker(Runnable {
            try { action() } catch (failure: Exception) { main.post { if (alive.get()) error = failure.message?.take(300) ?: "结果暂不可读取，请检查连接后重试。" } }
            finally { main.post { if (alive.get()) busy = false } }
        }) } catch (_: Exception) { busy = false; error = "结果读取入口暂不可用。" }
    }
    val trigger = "${task.updatedAt}:$nativeRevision"
    var lastRead by remember(window) { mutableStateOf<String?>(null) }
    LaunchedEffect(window, trigger, busy) { if (!busy && lastRead != trigger) { lastRead = trigger; work {
        val actual = window.historyLinks(task.id); main.post { if (alive.get()) links = actual }
    } } }
    fun query(native: NativeDeviceWorkspaceHost.HistoryWindow) { work {
        val actual = native.query(); main.post { if (alive.get() && selected === native) { result = actual; changed() } }
    } }
    val rows = links?.optJSONArray("links").objects()
    if (rows.isNotEmpty()) DeviceContentSurface { Column(Modifier.fillMaxWidth().padding(16.dp).testTag("device_task_history"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("设备任务与成果", style = MaterialTheme.typography.titleSmall)
        rows.take(if (expanded) 24 else 3).forEach { link ->
            val operation = link.optString("operationId")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("设备 ${link.optString("targetDeviceId").takeLast(4)}", style = MaterialTheme.typography.bodyMedium)
                val terminal = link.optJSONObject("terminalReceipt")
                Text(when { terminal?.optBoolean("terminal") == true && terminal.optBoolean("stopped") && !terminal.optBoolean("hasUnresolved") -> "执行端已有结束回执"; link.optString("holdState").contains("unknown", true) -> "执行结果待核对"; link.optJSONObject("childBinding") != null -> "执行端已接受，尚未核对结束状态"; else -> "已保留这次操作的关联记录" }, style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                TextButton(onClick = { work {
                    val native = window.openHistory(task.id, operation); val safe = native.view()
                    main.post { if (alive.get()) { selected = native; view = safe; result = null } else native.close() }
                } }, enabled = !busy && operation.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("device_task_history_open_$operation"), contentPadding = PaddingValues(0.dp)) { Text("查看执行结果") }
            }
        }
        if (rows.size > 3) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起" else "查看其余 ${rows.size - 3} 项") }
        if (links?.optBoolean("hasMore") == true) Text("当前显示最近 24 项设备操作。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        if (selected == null) error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SecondaryInk) }
    } }
    selected?.let { native -> view?.let { safe ->
        var details by remember(native) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = ::close, containerColor = Color.White, title = { Text("设备执行结果") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceFact("执行设备", "设备 ${safe.optString("targetDeviceId").takeLast(4)}")
                DeviceFact("原操作", safe.optString("operationId").takeLast(12))
                DeviceFact("执行范围", deviceResourceText(safe.optJSONObject("resourceBinding") ?: JSONObject()))
                result?.let { actual ->
                    val receipt = actual.optJSONObject("operationReceipt") ?: JSONObject()
                    if (actual.optBoolean("managementOnly")) Text(when {
                        actual.optBoolean("stopped") -> "执行端报告已停止；请刷新核对原操作结果。"
                        actual.optBoolean("requested") -> "执行端已接受停止请求，尚未确认停止。"
                        else -> "停止结果尚未确认，请刷新原操作回执；不会重复提交。"
                    }, style = MaterialTheme.typography.bodyMedium)
                    Text(if (actual.optBoolean("unknown")) "执行结果仍未确认" else "执行端已返回回执", style = MaterialTheme.typography.titleSmall)
                    if (receipt.optString("summary").isNotBlank()) Text(receipt.optString("summary"), style = MaterialTheme.typography.bodyMedium)
                    if (actual.optBoolean("unknown")) Text("不会重提任务，也不会释放未确认的已占用次数。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    else Text("这是执行端的记录，不代表你已经核对业务成果。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                } ?: Text("读取这次操作的最新回执，不会重新执行。", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { work {
                    val actual = native.requestStop(); main.post { if (alive.get() && selected === native) { result = actual; changed() } }
                } }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("device_task_history_stop")) { Text("请求停止这次原操作") }
                Text("仅停止上面核对的设备与范围，不重提任务。请求后仍需刷新回执核对；未知结果不退还已占用次数。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SecondaryInk, modifier = Modifier.testTag("device_task_history_error")) }
                if (result != null) TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text(if (details) "收起回执详情" else "查看回执详情") }
                if (details) SelectionContainer { Text((result?.optJSONObject("operationReceipt") ?: JSONObject()).toString(2), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }, confirmButton = { Button(onClick = { query(native) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("device_task_history_query")) { Text(if (busy) "正在读取…" else if (result == null) "读取执行结果" else "刷新回执") } },
            dismissButton = { TextButton(onClick = ::close, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回任务") } })
    } }
}
