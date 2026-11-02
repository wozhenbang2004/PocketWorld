package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.pocketengine.core.RunControl
import dev.pocketengine.runtime.models.ModelBinding
import dev.pocketengine.runtime.models.ModelConnectionService
import dev.pocketengine.runtime.models.ModelTargetRef
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Mounted within the original model settings; all calls remain explicit native owner actions. */
@Composable
internal fun ModelDiagnosticsControls(service: ModelConnectionService, snapshot: JSONObject, worker: (Runnable) -> Unit, changed: () -> Unit) {
    val uri = LocalUriHandler.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val identity = snapshot.optJSONObject("api")?.toString().orEmpty()
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var control by remember { mutableStateOf<RunControl?>(null) }
    var image by remember(identity) { mutableStateOf(false) }
    var result by remember(identity) { mutableStateOf(snapshot.optJSONObject("capabilityProbe")) }
    var error by remember { mutableStateOf<String?>(null) }
    var routing by remember(snapshot.toString()) { mutableStateOf(snapshot.optJSONObject("stepRouting") ?: JSONObject()) }
    var model by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf(JSONObject()) }
    var alive by remember(service) { mutableStateOf(true) }
    fun execute(action: () -> JSONObject, done: (JSONObject) -> Unit) {
        if (busy) return
        busy = true; error = null
        try { worker(Runnable {
            val response = runCatching(action)
            main.post { if (alive) { busy = false; control = null; response.fold(done) { error = if (it is InterruptedException) "诊断已停止；已有用量记录保留。" else "此项未完成，请核对原连接与配置后重试；没有执行设备工具。" }; changed() } }
        }) } catch (_: Exception) { busy = false; error = "本机工作线程暂不可用" }
    }
    DisposableEffect(service) { onDispose { alive = false; control?.cancel(); service.openRouter().cancel() } }
    LaunchedEffect(auth.optString("attemptId"), auth.optString("status")) {
        while (auth.optString("status") == "pending") { delay(1200); auth = service.openRouter().snapshot(); if (auth.optString("status") == "completed") changed() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起连接诊断与简易步骤" else "连接诊断与简易步骤") }
        if (expanded) {
            Text("能力探测", style = MaterialTheme.typography.titleSmall)
            Text("会产生2次模型调用；可选合成图片增加1次。文本与工具结果分别记录，不授予手机操作权限。", style = MaterialTheme.typography.bodySmall)
            Row { Checkbox(image, { image = it }, enabled = !busy); Text("同时探测合成图片（不读取手机画面）", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { val binding = snapshot.optJSONObject("api")?.optJSONObject("capabilityBinding")?.let(ModelBinding::fromJson) ?: return@Button; val run = RunControl(); control = run; execute({ service.probe(binding, image, run) }) { result = it } }, enabled = !busy && snapshot.optJSONObject("api")?.optJSONObject("capabilityBinding") != null) { Text("探测这项精确模型") }
                if (control != null) TextButton(onClick = { control?.cancel() }) { Text("停止探测") }
            }
            result?.let { value ->
                Text("文本：${probeStatus(value.optString("text"))} · 工具：${probeStatus(value.optString("tools"))} · 图片：${probeStatus(value.optString("image"))}", style = MaterialTheme.typography.bodySmall)
                Text("${value.optString("model")} · 当前连接修订的单次合成测试", style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider()
            Text("简易步骤模型 · ${if (routing.optBoolean("enabled")) "已启用" else "关闭"}", style = MaterialTheme.typography.titleSmall)
            Text("默认使用主模型。同一App连续安全读取后才尝试所选API模型；新页面、恢复、失败或危险答复回主模型，额外调用仍记入原任务用量。", style = MaterialTheme.typography.bodySmall)
            val profiles = snapshot.optJSONObject("connections")?.optJSONArray("connections")
            if (routing.optBoolean("enabled")) TextButton(onClick = { execute({ service.saveStepRouting(false, null) }) { routing = it.optJSONObject("stepRouting") ?: JSONObject() } }, enabled = !busy) { Text("关闭简易步骤分工") }
            for (i in 0 until (profiles?.length() ?: 0)) profiles?.optJSONObject(i)?.takeIf { it.optBoolean("enabled") && it.optString("model").isNotBlank() }?.let { profile ->
                TextButton(onClick = { execute({ service.saveStepRouting(true, ModelTargetRef.exact(profile.getString("id"), profile.getString("model"))) }) { routing = it.optJSONObject("stepRouting") ?: JSONObject() } }, enabled = !busy) { Text("选择 ${profile.optString("name")} · ${profile.optString("model")}") }
            }
            HorizontalDivider()
            Text("从OpenRouter网页授权", style = MaterialTheme.typography.titleSmall)
            Text("在浏览器登录并批准后，生成普通API key连接。推理由你的OpenRouter余额支付；不会自动切换当前主模型。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(model, { model = it }, label = { Text("精确模型ID") }, singleLine = true, enabled = !busy && auth.optString("status") != "pending", modifier = Modifier.fillMaxWidth())
            Button(onClick = { execute({ service.openRouter().begin(model.trim()) }) { value -> auth = value; runCatching { uri.openUri(value.getString("authorizationUrl")) }.onFailure { service.openRouter().cancel(); auth = service.openRouter().snapshot(); error = "未能打开系统浏览器，授权已取消。" } } }, enabled = !busy && model.isNotBlank() && auth.optString("status") != "pending") { Text("打开OpenRouter授权") }
            if (auth.optString("status") == "pending") TextButton(onClick = { service.openRouter().cancel(); auth = service.openRouter().snapshot() }) { Text("取消本次授权") }
            if (auth.optString("status").isNotBlank()) Text(when (auth.optString("status")) { "pending" -> "等待本次浏览器回调…"; "completed" -> "新连接已保存，请在原连接列表选择。"; "cancelled" -> "已取消本次等待。"; "storage_unconfirmed" -> "本机保存结果未确认，请核对原连接列表；不要重复授权。"; else -> "授权未完成，请核对原连接列表。远端key可在OpenRouter的Keys页撤销。" }, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun probeStatus(value: String) = when (value) { "passed" -> "通过"; "failed" -> "未通过"; "not_requested" -> "未测试"; else -> "未完成" }
