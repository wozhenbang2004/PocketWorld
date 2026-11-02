package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** This is an owner review of an existing run, not a task creator or a credential form. */
@Composable
internal fun HarnessGatewayPanel(gateway: JSONObject?, enabled: Boolean, send: (String, JSONObject) -> Unit) {
    val status = gateway?.optJSONObject("status")
    val review = gateway?.optJSONObject("review")
    val target = review?.optJSONObject("target") ?: status?.optJSONObject("target")
    val parent = gateway?.optJSONObject("parent")
    val lease = status?.optString("leaseId").orEmpty()
    val records = gateway?.optJSONArray("records").objects()
    val unknownRecords = records.any { it.optString("state") == "REQUIRES_RECONCILE" || it.optJSONArray("requests").objects().any { request -> request.optString("state") == "UNKNOWN" } }
    val uncertain = status?.optBoolean("requiresReconcile") == true || status?.optString("state") == "UNKNOWN" || unknownRecords
    fun until(time: Long) = if (time > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time)) else "尚未启用"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("本次任务模型网关", style = MaterialTheme.typography.titleSmall)
        Text("仅让所选电脑 Harness 使用原任务的 API 模型推理与预算。不授予手机、文件或命令工具权限，不使用 ChatGPT 账号。", style = MaterialTheme.typography.bodyMedium)
        if (parent != null) Text("归属原父任务 ${parent.optString("parentTaskId").take(8)} · 原远端子任务不变", color = SecondaryInk, style = MaterialTheme.typography.bodySmall)
        gateway?.optString("blockedReason")?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        if (uncertain && status == null) Text("有未确认的原请求：只保留原记录，不重建服务或重发。", style = MaterialTheme.typography.labelLarge)
        if (status != null) {
            Text(when {
                uncertain -> "有未确认的原请求：只核对，不重发"
                status.optBoolean("running") -> "网关正在运行 · 仅本次原任务"
                status.optString("state") == "PREPARED" -> "服务准备中，尚未证明能够推理"
                else -> "本次网关已停止或到期"
            }, style = MaterialTheme.typography.labelLarge)
            Text("截止 ${until(status.optLong("expiresAt"))}", style = MaterialTheme.typography.bodyMedium)
        }
        if (target != null) SelectionContainer {
            Text("电脑 ${target.optString("profileId")} / ${target.optString("environmentId")}\n目标 ${target.optString("bindingId")}", style = MaterialTheme.typography.bodyMedium)
        }
        if (review != null) {
            val model = review.optJSONObject("model")
            Text("将为 ${model?.optString("provider")} / ${model?.optString("model")} 启用，截止 ${until(review.optLong("expiresAt"))}。期限不超过原任务授权与十分钟。", style = MaterialTheme.typography.bodyMedium)
            val budget = model?.optJSONObject("budget")
            Text(if (budget?.optBoolean("scopePresent") == true) "原任务剩余模型调用 ${budget.optInt("modelCallsRemaining")} 次；未知和失败不自动退款。" else "仍受原 TaskStore 预算约束；本页未提供剩余数，不表示无限或免费。", style = MaterialTheme.typography.bodyMedium)
            Text("只启动模型网关；不会更改 CLI 配置、批准 CLI 运行或重做原请求。显示 API 模式也不是最终计费后台的证明。", color = SecondaryInk, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { send("gateway.enable", JSONObject().put("acceptedDigest", gateway?.optString("reviewDigest").orEmpty())) }, enabled = enabled && (gateway?.optLong("reviewExpiresAt") ?: 0) > System.currentTimeMillis()) { Text("确认仅为此原任务启用") }
        } else if (status?.optBoolean("running") != true && !uncertain) {
            OutlinedButton(onClick = { send("gateway.inspect", JSONObject()) }, enabled = enabled) { Text("核对本次网关范围") }
            gateway?.optJSONArray("targets").objects().forEach { option ->
                OutlinedButton(onClick = { send("gateway.review", JSONObject().put("optionRef", option.optString("optionRef"))) }, enabled = enabled) { Text("审阅 ${option.optString("bindingId")} 的范围") }
            }
            if (gateway?.has("targets") == true && gateway.optJSONArray("targets").objects().isEmpty() && gateway.optString("blockedReason").isBlank()) Text("原环境没有可用的显式 Harness 目标；不会扫描 HOME 或代建配置。", color = SecondaryInk, style = MaterialTheme.typography.bodySmall)
        }
        if (lease.isNotBlank()) {
            if (status?.optBoolean("running") == true) OutlinedButton(onClick = { send("gateway.configure", JSONObject().put("leaseId", lease)) }, enabled = enabled) { Text("预览此原任务的 CLI 配置") }
            TextButton(onClick = { send("gateway.query", JSONObject().put("leaseId", lease)) }, enabled = enabled) { Text("只查询原租约与一条未决请求") }
            if (status?.optString("state") !in setOf("REVOKED", "EXPIRED")) OutlinedButton(onClick = { send("gateway.revoke", JSONObject().put("leaseId", lease)) }, enabled = enabled) { Text("停止本次模型网关") }
        }
        val observation = status?.optJSONObject("observation") ?: status
        observation?.optJSONArray("originalRequests").objects().forEach { request ->
            Text("原请求 ${request.optString("requestId").take(8)} · 本机 ${request.optString("state")} · ${if (request.optString("readStatus") == "confirmed") "原端已核对：${request.optJSONObject("transport")?.optString("state")}" else "原端不可读，未知保留"}", style = MaterialTheme.typography.bodyMedium)
        }
        if (observation?.optString("transportRead") == "unavailable_unknown_not_retried") Text("原端暂不可查询；未重建租约、恢复正文或重发模型。", style = MaterialTheme.typography.bodyMedium)
        if (records.isNotEmpty()) {
            if (status == null) Text("旧租约不在当前原生宿主；本页保留原 SQL 观察，不能恢复旧正文或连接。", style = MaterialTheme.typography.bodyMedium)
            Text("原任务保留 ${records.size} 份租约记录；只读，不恢复派发。", color = SecondaryInk, style = MaterialTheme.typography.bodySmall)
            records.take(3).forEach { record ->
                val descriptor = record.optJSONObject("descriptor")
                Text("${descriptor?.optString("leaseId")?.take(8)} · ${record.optString("state")} · 截止 ${until(descriptor?.optLong("expiresAt") ?: 0)}", color = SecondaryInk, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("停止或退出服务后不再接新请求；已发未知与原用量保留。旧正文、旧连接不可恢复时保持不可用，不自动补发。", color = SecondaryInk, style = MaterialTheme.typography.bodySmall)
    }
}
