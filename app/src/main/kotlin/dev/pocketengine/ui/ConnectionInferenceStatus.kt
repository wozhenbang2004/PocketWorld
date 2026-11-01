package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.runtime.usage.InferenceFailurePresentation
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

/** Read-only finite native observation, not a connection probe or an automatic retry control. */
@Composable
internal fun ConnectionInferenceStatus(observation: JSONObject?, identity: String) {
    val valid = observation?.optString("format") == "pocketworld.connection-inference-observation.v1" &&
        observation.opt("healthVerified") == false && observation.opt("automaticRetry") == false &&
        observation.optString("coverage") == "committed_terminal_receipts_this_process_only"
    val last = if (valid) observation?.optJSONObject("lastCall") else null
    val failed = if (valid) observation?.optJSONObject("lastFailure") else null
    var expanded by remember(identity, last?.optString("callId"), failed?.optString("callId")) { mutableStateOf(false) }
    val shown = if (failed != null) InferenceFailurePresentation.fromFailure(failed.optJSONObject("failure"), observation?.optLong("observedAt") ?: 0L) else null
    val headline = when {
        last == null -> "推理状态未观察到"
        last.optString("outcome") == "COMPLETED" -> "最近一次模型调用完成"
        shown != null -> shown.title()
        else -> "最近一次调用未完成，细节未观察到"
    }
    Text(headline, style = MaterialTheme.typography.bodySmall)
    if (last == null) Text("本进程暂无这项连接、凭据代次和模型的保留调用观察；重开后的缺失不是恢复或可用性证明。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else {
        Text("仅最近真实调用，不是目录状态、连接健康保证或套餐余额。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起调用详情" else "查看原因与等待来源") }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("最近调用：${last.optString("outcome")} · ${connectionCallTime(last.optLong("at"))}", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text("调用编号 ${last.optString("callId")}", style = MaterialTheme.typography.labelSmall) }
            if (shown != null) {
                if (failed?.optString("callId") != last.optString("callId")) Text("较早失败记录仍保留：${shown.title()}", style = MaterialTheme.typography.bodySmall)
                shown.lines().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("可在连接列表明确选择后续请求使用的连接。不会移动正在运行的任务、重放旧请求或恢复其他账号历史。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun connectionCallTime(at: Long): String = if (at > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at)) else "时间未观察到"
