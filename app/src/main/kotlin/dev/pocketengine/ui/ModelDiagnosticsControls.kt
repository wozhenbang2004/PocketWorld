package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import dev.pocketengine.core.RunControl
import dev.pocketengine.runtime.models.ModelBinding
import dev.pocketengine.runtime.models.ModelConnectionService
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** MD002 portion of the published native diagnostics leaf; no routing, authorization or tools. */
@Composable
internal fun ModelDiagnosticsControls(
    service: ModelConnectionService,
    snapshot: JSONObject,
    worker: (Runnable) -> Unit,
    changed: () -> Unit,
    ownerBusy: Boolean = false,
) {
    val main = remember { Handler(Looper.getMainLooper()) }
    val alive = remember(service) { AtomicBoolean(true) }
    val identity = snapshot.optLong("probeSelectionRevision", -1)
    var expanded by remember { mutableStateOf(false) }
    var image by remember(identity) { mutableStateOf(false) }
    var busy by remember(service) { mutableStateOf(false) }
    var control by remember(service) { mutableStateOf<RunControl?>(null) }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    val result = snapshot.optJSONObject("capabilityProbe")
    val binding = snapshot.optJSONObject("api")?.optJSONObject("capabilityBinding")?.let(ModelBinding::fromJson)

    DisposableEffect(service) {
        onDispose { alive.set(false); control?.cancel() }
    }
    fun start() {
        if (busy || ownerBusy || binding == null || !alive.get()) return
        val original = RunControl()
        val includeImage = image
        control = original; busy = true; error = null
        try {
            worker(Runnable {
                var problem: String? = null
                try { service.probe(binding, includeImage, original) }
                catch (stopped: InterruptedException) {
                    Thread.currentThread().interrupt()
                    problem = "探测已停止；已经发生的请求用量仍保留。"
                } catch (_: SecurityException) {
                    problem = "原连接、模型或授权已变化，探测未继续；没有采用旧结果。"
                } catch (_: Exception) {
                    problem = "本次未证实，请核对原连接后重试；没有执行真实工具。"
                }
                val message = problem
                main.post {
                    if (alive.get()) { busy = false; control = null; error = message; changed() }
                }
            })
        } catch (_: Exception) {
            original.cancel(); control = null; busy = false
            error = "本机工作线程暂不可用，没有发起探测。"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起能力实测" else "文本、工具与合成图片实测") }
        if (expanded) {
            Text("只测试这项保留的 API 模型，不使用 ChatGPT 账号。最多2次模型请求；勾选合成图片后最多3次，会消耗该连接额度。", style = MaterialTheme.typography.bodySmall)
            Text("仅发送随机合成内容和一个声明但不执行的测试函数，不使用聊天历史或手机画面。离开此页会停止后续探测。", style = MaterialTheme.typography.bodySmall)
            Row {
                Checkbox(image, { image = it }, enabled = !busy && !ownerBusy)
                Text("增加一张合成图片（不读取屏幕）", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::start, enabled = binding != null && !busy && !ownerBusy) {
                    Text(if (busy) "正在测试…" else if (image) "明确测试这项 API（最多3次）" else "明确测试这项 API（最多2次）")
                }
                if (busy) TextButton(onClick = { control?.cancel() }) { Text("停止") }
            }
            result?.let {
                Text("文本：${probeStatus(it.optString("text"))} · 工具：${probeStatus(it.optString("tools"))} · 图片：${probeStatus(it.optString("image"))}", style = MaterialTheme.typography.bodySmall)
                Text("${it.optString("model")} · 本次精确连接修订的独立观察", style = MaterialTheme.typography.labelSmall)
            }
            Text("通过只说明本次基础合成结构，不能证明所有工具、JSON schema 或模型质量。未证实不表示永久不支持，也不会覆盖目录声明。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun probeStatus(value: String) = when (value) {
    "passed" -> "本次通过"
    "unconfirmed", "failed" -> "未证实"
    "not_requested" -> "未测试"
    else -> "未完成"
}
