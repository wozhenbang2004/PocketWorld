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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.pocketengine.core.RunControl
import dev.pocketengine.runtime.usage.OtlpTraceExport
import dev.pocketengine.runtime.usage.TaskDetailedTrace
import org.json.JSONObject

/** Low-frequency debug controls and explicit preview/send, mounted in existing settings/task details. */
@Composable
internal fun TaskTraceControls(manager: TaskDetailedTrace, runId: String?, worker: (Runnable) -> Unit) {
    val main = remember { Handler(Looper.getMainLooper()) }
    var open by remember { mutableStateOf(false) }; var snapshot by remember { mutableStateOf(JSONObject()) }
    var archive by remember(runId) { mutableStateOf<JSONObject?>(null) }; var preview by remember(runId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var receipt by remember { mutableStateOf<JSONObject?>(null) }
    var dialog by remember { mutableStateOf(false) }; var endpoint by remember { mutableStateOf("") }; var header by remember { mutableStateOf("Authorization") }; var secret by remember { mutableStateOf("") }
    var control by remember { mutableStateOf<RunControl?>(null) }; var alive by remember(manager) { mutableStateOf(true) }
    fun execute(action: () -> JSONObject, done: (JSONObject) -> Unit) { if (busy) return; busy = true; error = null; try { worker(Runnable { val result = runCatching(action); main.post { if (alive) { busy = false; control = null; result.fold(done) { error = "诊断操作未完成；没有自动上传或重试。" } } } }) } catch (_: Exception) { busy = false; error = "本机工作线程暂不可用" } }
    DisposableEffect(manager) { onDispose { alive = false; control?.cancel(); secret = "" } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open; if (open) execute({ manager.snapshot() }) { snapshot = it } }) { Text(if (open) "收起详细诊断" else "详细诊断（调试构建）") }
        if (open && snapshot.optBoolean("supported")) {
            Text("只保留耗时、状态与原任务/调用身份；不采集提示词、回复正文、截图或密钥。", style = MaterialTheme.typography.bodySmall)
            Row { Switch(snapshot.optBoolean("enabled"), { enabled -> execute({ manager.configure(enabled, (snapshot.optLong("limitBytes", 8388608) / 1048576).toInt()); manager.snapshot() }) { snapshot = it } }, enabled = !busy); Text(if (snapshot.optBoolean("enabled")) "正在采集后续新运行" else "默认关闭", style = MaterialTheme.typography.bodyMedium) }
            Text("保留 ${snapshot.optLong("retainedBytes")} / ${snapshot.optLong("limitBytes")} 字节；超限清理旧诊断，不清理原任务账本。", style = MaterialTheme.typography.bodySmall)
            Row { listOf(1, 8, 32).forEach { limit -> TextButton(onClick = { execute({ manager.configure(snapshot.optBoolean("enabled"), limit); manager.snapshot() }) { snapshot = it } }, enabled = !busy) { Text("${limit}MiB") } } }
            if (!runId.isNullOrBlank()) TextButton(onClick = { execute({ manager.read(runId) }) { value -> archive = value; preview = if (value.optBoolean("available")) OtlpTraceExport.preview(value).toString(2) else null; if (!value.optBoolean("available")) error = "这次运行未启用诊断或诊断已清理。" } }, enabled = !busy) { Text("预览此原轮次的无正文诊断") }
            preview?.let { text -> Text("${archive?.optJSONArray("spans")?.length() ?: 0} 个span · ${text.toByteArray().size} 字节 · OTLP/HTTP JSON", style = MaterialTheme.typography.labelSmall); SelectionContainer { Text(text.take(4000) + if (text.length > 4000) "\n（仅预览前4000字符，发送使用这份完整无正文诊断）" else "", style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = { dialog = true }, enabled = !busy) { Text("选择接收地址并发送") } }
            receipt?.let { value -> Text(when (value.optString("status")) { "accepted" -> "接收端确认接受"; "partially_rejected" -> "部分失败：接收端拒收 ${value.optLong("rejectedSpans")} 个span"; "accepted_with_warning" -> "接收端接受并返回警告"; else -> "发送失败；不会自动重试" }, style = MaterialTheme.typography.bodySmall) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (control != null) TextButton(onClick = { control?.cancel() }) { Text("停止本次发送") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false; secret = "" }, title = { Text("发送这份无正文诊断") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("将把预览中的完整诊断发送到你填写的接收端。仅本次发送，不保存认证内容。", style = MaterialTheme.typography.bodySmall); OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS地址或本机HTTP /v1/traces") }); OutlinedTextField(header, { header = it }, label = { Text("认证头名称") }); OutlinedTextField(secret, { secret = it }, label = { Text("认证值（可留空）") }, visualTransformation = PasswordVisualTransformation()) } }, confirmButton = { TextButton(onClick = { val selected = archive ?: return@TextButton; val value = secret; secret = ""; dialog = false; val run = RunControl(); control = run; execute({ OtlpTraceExport.send(selected, endpoint, header, value, run) }) { receipt = it } }, enabled = !busy) { Text("发送一次") } }, dismissButton = { TextButton(onClick = { dialog = false; secret = "" }) { Text("取消") } })
}
