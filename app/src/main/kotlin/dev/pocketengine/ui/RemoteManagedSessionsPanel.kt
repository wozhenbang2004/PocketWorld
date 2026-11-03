package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Original stored task projection. No discovery, permission, background polling or control buttons. */
@Composable
internal fun RemoteManagedSessionsPanel(load: () -> JSONObject, runWork: (Runnable) -> Unit, open: (String, String) -> Unit) {
    var revision by remember { mutableStateOf(0L) }
    var snapshot by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var navigationFailed by remember { mutableStateOf(false) }
    var alive by remember { mutableStateOf(true) }
    var generation by remember { mutableStateOf(0L) }
    val currentLoad by rememberUpdatedState(load)
    val currentOpen by rememberUpdatedState(open)
    DisposableEffect(Unit) { onDispose { alive = false; generation++ } }
    LaunchedEffect(revision) {
        val request = ++generation; loading = true; failed = false; navigationFailed = false
        try { runWork(Runnable {
            val result = runCatching { currentLoad() }
            Handler(Looper.getMainLooper()).post {
                if (alive && request == generation) { snapshot = result.getOrNull(); failed = result.isFailure; loading = false }
            }
        }) } catch (_: Exception) { if (alive && request == generation) { failed = true; snapshot = null; loading = false } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("原任务会话工作面", style = MaterialTheme.typography.titleMedium)
        Text("仅最后保存的本机原任务元数据，不是在线会话发现。打开后仍须在原任务页重新核对；清单、注意标记和刷新均不授权执行。只列私人原生主人自管任务，团队和渠道仍回原执行面。连接地址分组不冒充已核验的物理设备身份。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { revision++ }, enabled = !loading) { Text(if (loading) "读取本机清单中" else "刷新本机原任务清单") }
        if (failed) Text("本机清单暂不可读。没有扫描外部会话、切换授权或重提任务。", style = MaterialTheme.typography.bodySmall)
        if (navigationFailed) Text("原任务/会话或前台范围已变化，请刷新后重新打开。没有派发执行。", style = MaterialTheme.typography.bodySmall)
        fun openRow(row: JSONObject): () -> Unit {
            val task = row.optString("taskId"); val identity = row.optString("rowIdentity")
            return { if (alive) try { currentOpen(task, identity) } catch (_: Exception) { navigationFailed = true } }
        }
        snapshot?.let { value ->
            if (value.optBoolean("olderLocalTasksNotScanned")) Text("仅检查最近 ${value.optInt("localTaskWindow")} 条本机任务，较旧记录未列入；这不是全部历史会话。", style = MaterialTheme.typography.bodySmall)
            val devices = value.optJSONArray("managedConnections")
            for (d in 0 until (devices?.length() ?: 0)) {
                val device = devices!!.getJSONObject(d)
                key(device.getString("connectionKey")) {
                    Text("电脑连接 ${device.optString("profileId")} · ${device.optString("serviceOrigin")} · ${device.optString("connectionKey").take(10)}", style = MaterialTheme.typography.titleSmall)
                    val contexts = device.getJSONArray("contexts")
                    for (c in 0 until contexts.length()) {
                        val context = contexts.getJSONObject(c)
                        Text("环境 ${context.optString("environmentId").ifBlank { "身份未报告" }} · 工作区 ${context.optString("workspaceFingerprint").take(10).ifBlank { "物理身份未报告，不与同名工作区合并" }}", style = MaterialTheme.typography.bodySmall)
                        val rows = context.getJSONArray("sessions")
                        for (r in 0 until rows.length()) {
                            val row = rows.getJSONObject(r)
                            key(row.getString("rowIdentity")) { RemoteManagedSessionRow(row, openRow(row)) }
                        }
                    }
                }
            }
            val unresolved = value.optJSONArray("unconfirmedLocalTasks") ?: JSONArray()
            if (unresolved.length() > 0) Text("仅有本机任务记录，会话来源尚不可核对", style = MaterialTheme.typography.titleSmall)
            for (i in 0 until unresolved.length()) {
                val row = unresolved.getJSONObject(i)
                key(row.getString("rowIdentity")) { RemoteManagedSessionRow(row, openRow(row)) }
            }
            if ((devices?.length() ?: 0) + unresolved.length() == 0) Text("此本机窗口内没有私人自管远端任务；不代表外部电脑没有其他会话。", style = MaterialTheme.typography.bodySmall)
        }
        Text("外部已认领会话：不可用（尚无真实 registry adapter）。未认领发现候选：不可用；不从屏幕、pane 名或模型文本猜测。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RemoteManagedSessionRow(row: JSONObject, open: () -> Unit) {
    val attention = row.optJSONObject("attention")
    val observed = when (attention?.optString("kind")) {
        "input_observed" -> "上次观察：待审 ${attention.optInt("approvalsObserved")} · 待答 ${attention.optInt("questionsObserved")}; 原页重新核对"
        "stop_requested_unconfirmed" -> "已请求停止，未据此宣称已停止或回滚"
        "execution_unconfirmed" -> "执行状态未确认，不可据此重发"
        "completed_reported" -> "上次报告执行完成，不等于新轮授权"
        "execution_stopped_reported" -> "上次报告执行结束，仍须原页核对"
        else -> "没有观察到待处理项；不是实时保证"
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("${row.optString("worker").ifBlank { "执行器未核对" }} · 会话 ${if (row.optString("sessionEvidence") == "native_protocol_observed") row.optString("sessionKey").take(10) else "原生协议身份不可用"}", style = MaterialTheme.typography.bodyMedium)
        Text("原任务 ${row.optString("taskId")} · 轮次 ${row.optInt("coordinatorEpoch").takeIf { it > 0 }?.toString() ?: "未确认"} · job ${row.optString("jobId").ifBlank { "尚无已确认 job" }}", style = MaterialTheme.typography.bodySmall)
        if (row.optString("parentTaskId").isNotBlank()) Text("原父任务 ${row.optString("parentTaskId")}", style = MaterialTheme.typography.bodySmall)
        Text(observed, style = MaterialTheme.typography.bodySmall)
        val updated = row.optLong("lastLocalUpdateAt")
        Text("本机最后更新 ${if (updated > 0) java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(updated)) else "时间未记录"}（非本次远端查询）", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = open) { Text("打开原任务 ${row.optString("taskId").take(8)}") }
    }
}
