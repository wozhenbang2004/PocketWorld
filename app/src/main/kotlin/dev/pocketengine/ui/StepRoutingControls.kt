package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.runtime.models.ModelConnectionService
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** An optional disclosure within the existing model page, using its existing worker. */
@Composable
internal fun StepRoutingControls(service: ModelConnectionService, runOnWorker: (Runnable) -> Unit) {
    var expanded by remember(service) { mutableStateOf(false) }
    var snapshot by remember(service) { mutableStateOf(JSONObject()) }
    var busy by remember(service) { mutableStateOf(false) }
    var error by remember(service) { mutableStateOf<String?>(null) }
    val alive = remember(service) { AtomicBoolean(true) }
    val main = remember { Handler(Looper.getMainLooper()) }
    fun load(action: () -> JSONObject) {
        if (busy || !alive.get()) return
        busy = true; error = null
        try {
            runOnWorker(Runnable {
                var next: JSONObject? = null
                var message: String? = null
                try { next = action() } catch (_: Exception) {
                    message = "原模型组、能力或设置修订已变化，请刷新后核对；本次没有请求模型。"
                    try { next = service.stepRoutingSnapshot() } catch (_: Exception) { }
                }
                val result = next; val failure = message
                main.post { if (alive.get()) { if (result != null) snapshot = result; error = failure; busy = false } }
            })
        } catch (_: Exception) { busy = false; error = "本机工作线程暂不可用，请重新打开模型页。" }
    }
    DisposableEffect(service) { onDispose { alive.set(false) } }
    LaunchedEffect(service) { load { service.stepRoutingSnapshot() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text("轻量步骤 · " + if (snapshot.optBoolean("enabled")) "已启用" else "关闭")
        }
        if (expanded) {
            Text("连续两次同来源的只读步骤成功后，允许使用你指定的轻量候选。首步、风险、未知、来源变化和最终答复使用主候选；所有操作仍走原审批与预算。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            Text("此处只使用当前主模型组中的现有候选。轻量结果被拒绝时仍会产生实际用量，不保证更低费用。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            if (snapshot.optBoolean("enabled") && !snapshot.optBoolean("configurationCurrent")) {
                Text("已保存的组配置发生变化，轻量路由当前不生效。请核对后重新启用。", style = MaterialTheme.typography.bodySmall)
            }
            val candidates = snapshot.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) Text("请先在原模型组中配置并选择主候选和一个具备明确能力的备选。", style = MaterialTheme.typography.bodySmall)
            for (i in 0 until (candidates?.length() ?: 0)) {
                val row = candidates!!.getJSONObject(i)
                val selected = snapshot.optBoolean("enabled") && snapshot.optBoolean("configurationCurrent") && row.optString("candidateId") == snapshot.optString("candidateId")
                TextButton(enabled = !busy && row.optBoolean("capabilitiesKnown") && !selected, onClick = {
                    val revision = snapshot.optLong("revision")
                    val fingerprint = snapshot.optString("currentPlanFingerprint")
                    val id = row.optString("candidateId")
                    load { service.saveStepRouting(true, revision, fingerprint, id) }
                }) { Text(row.optString("model") + " · " + when {
                    selected -> "当前轻量候选"
                    !row.optBoolean("capabilitiesKnown") -> "能力未知"
                    else -> "启用此候选"
                }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { load { service.stepRoutingSnapshot() } }) { Text("刷新") }
                if (snapshot.optBoolean("enabled")) TextButton(enabled = !busy, onClick = {
                    val revision = snapshot.optLong("revision")
                    load { service.saveStepRouting(false, revision, "", "") }
                }) { Text("关闭轻量步骤") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(1.dp), color = Ink)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
