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
internal fun HarnessGatewayPanel(gateway: JSONObject?, enabled: Boolean, send: (String, JSONObject) -> Unit, snapshot: JSONObject = JSONObject()) {
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
        HarnessGatewayContinuationPanel(snapshot, gateway, enabled, send)
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
            val profile = model?.optJSONObject("workProfileRelaySource")
            Text(if (profile == null) "原宿主 RELAY 选择" else "任务工作配置 · 版本 ${profile.optLong("profileRevision")}", color = SecondaryInk, style = MaterialTheme.typography.bodySmall)
            Text("将为 ${model?.optString("provider")} / ${model?.optString("model")} 启用，截止 ${until(review.optLong("expiresAt"))}。期限不超过原任务授权与十分钟。", style = MaterialTheme.typography.bodyMedium)
            val budget = model?.optJSONObject("budget")
            Text(if (budget?.optBoolean("scopePresent") == true) "原任务剩余模型调用 ${budget.optInt("modelCallsRemaining")} 次；未知和失败不自动退款。" else "仍受原 TaskStore 预算约束；本页未提供剩余数，不表示无限或免费。", style = MaterialTheme.typography.bodyMedium)
            Text("仅选择模型，未修改远端配置；不会批准 CLI 运行或重做原请求。显示 API 模式也不是最终计费后台的证明。", color = SecondaryInk, style = MaterialTheme.typography.bodySmall)
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

/** Same folded owner region. Every button passes a displayed reference, never a target/grant. */
@Composable
private fun HarnessGatewayContinuationPanel(snapshot: JSONObject, gateway: JSONObject?, enabled: Boolean, send: (String, JSONObject) -> Unit) {
    val stage = snapshot.optJSONObject("gatewayContinuation")
    val parentReview = snapshot.optJSONObject("gatewayParentRunReview")
    val parentScope = parentReview?.optJSONObject("scope")
    val session = gateway?.optJSONObject("parentGatewaySession")
    val uncertain = session?.optString("state") in setOf("UNKNOWN_STOPPED", "STOPPED_AWAITING_REQUEST") || gateway?.optJSONObject("status")?.optBoolean("requiresReconcile") == true || gateway?.optJSONArray("records").objects().any { it.optString("state") == "REQUIRES_RECONCILE" || it.optJSONArray("requests").objects().any { request -> request.optString("state") == "UNKNOWN" } }
    val finalReview = snapshot.optJSONObject("gatewayExecutionReview")
    val finalScope = finalReview?.optJSONObject("scope")
    val lease = gateway?.optJSONObject("status")?.optString("leaseId").orEmpty()
    fun until(time: Long) = if (time > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time)) else "未确认"
    if (session?.optBoolean("available") == true) {
        Text(when (session.optString("state")) {
            "REQUEST_IN_PROGRESS" -> "网关会话 · 正在处理一条实际模型请求"
            "REQUEST_CLAIMED" -> "网关会话 · 原请求已登记，等待确定回执"
            "READY_WAITING", "REQUEST_RECEIPT_RECORDED" -> "网关会话已就绪 · 等待远端模型请求"
            "WAITING_CONFIGURATION" -> "仅网关执行已就绪 · 等待配置准确目标"
            "PREPARING_SERVICE" -> "正在登记本次前台网关服务 · 尚不可派发"
            "UNKNOWN_STOPPED" -> "网关有未知效果 · 已停止新增派发"
            "STOPPED_AWAITING_REQUEST" -> "已停止接新请求 · 原在途请求仍待核对"
            "EXPIRED" -> "本次网关会话已到期"
            else -> "本次网关会话已停止 · 原记录保留"
        }, style = MaterialTheme.typography.labelLarge)
        Text("截止 ${until(session.optLong("expiresAt"))} · 仅 RELAY 推理，不执行任务工具", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { send("gateway.parentStatus", JSONObject()) }, enabled = enabled) { Text("更新会话状态") }
            if (session.optString("state") !in setOf("STOPPED", "EXPIRED", "UNKNOWN_STOPPED", "SCOPE_CHANGED_STOPPED")) TextButton(onClick = { send("gateway.stopParentRun", JSONObject()) }, enabled = enabled) { Text("停止本轮网关会话") }
        }
        if (!uncertain && session.optString("state") in setOf("STOPPED", "EXPIRED") && (stage == null || stage.optString("operationState") == "remote_job_receipt") && stage?.optBoolean("cancelRequested") != true) {
            Text("上一轮已停止。再次使用仍须审阅原累计余量、目标和新期限；不会自动续期或清零用量。", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { send("gateway.reviewParentRun", JSONObject()) }, enabled = enabled) { Text("重新审阅原任务的仅网关恢复") }
        }
    } else if (stage == null && !uncertain) {
        Text("若原助手已等待且控制结束，可明确恢复同一任务为仅网关执行；不新建任务、不启动手机操作或第二个 Agent。原范围缺失或耗尽时，先回原任务确认执行范围。", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { send("gateway.reviewParentRun", JSONObject()) }, enabled = enabled) { Text("审阅原任务的仅网关恢复") }
    }
    if (parentScope != null && !uncertain) {
        Text("恢复原任务 ${parentScope.optString("parentTaskId").take(8)}，仅供电脑 ${parentScope.optString("profileId")} / ${parentScope.optString("environmentId")} 使用 ${parentScope.optString("provider")} / ${parentScope.optString("model")}。", style = MaterialTheme.typography.bodyMedium)
        Text("截止 ${until(parentScope.optLong("expiresAt"))}；原累计模型余量 ${parentScope.optInt("remainingModels")} 次。工具权限为空，不清零旧用量。API 标记不是最终计费后台证明。", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { send("gateway.resumeParentRun", JSONObject().put("acceptedDigest", parentReview.optString("digest"))) }, enabled = enabled && parentReview.optLong("expiresAt") > System.currentTimeMillis()) { Text("确认此范围，仅恢复网关") }
    }
    if (stage != null) {
        val prepared = stage.optBoolean("locallyPrepared")
        val blocked = stage.optBoolean("cancelRequested") || stage.optBoolean("expired")
        Text(if (prepared && !blocked) "新一轮已准备 · 尚未派发 CLI" else if (blocked) "本轮已取消或到期 · 只核对原操作" else "本轮已领取或执行 · 不重复派发", style = MaterialTheme.typography.labelLarge)
        SelectionContainer { Text("本轮要求：${stage.optString("prompt")}\n原操作 ${stage.optString("operationId").take(12)} · 截止 ${until(stage.optLong("expiresAt"))}", style = MaterialTheme.typography.bodyMedium) }
        if (prepared && !blocked) {
            Text("先在下方预览并应用准确 CLI 配置，再单独审阅本轮执行。配置成功本身不会启动 CLI 或授予工具权限。", style = MaterialTheme.typography.bodyMedium)
            if (lease.isNotBlank() && !uncertain) OutlinedButton(onClick = { send("gateway.reviewRun", JSONObject().put("leaseId", lease)) }, enabled = enabled) { Text("配置后，审阅本轮最终执行") }
        }
        if (stage.optBoolean("preparedCancellationAvailable")) TextButton(onClick = { send("gateway.cancelPrepared", JSONObject().put("operationId", stage.optString("operationId"))) }, enabled = enabled) { Text("撤销尚未领取的新轮并核对") }
        if (finalScope != null && !uncertain) {
            Text("将向 ${finalScope.optString("profileId")} / ${finalScope.optString("environmentId")} 的原 ${finalScope.optString("worker")} 会话派发一次，沿用准确工作区与已确认的 CLI 工具边界。网关本身只提供 RELAY 推理。", style = MaterialTheme.typography.bodyMedium)
            Text("原模型余量 ${finalScope.optInt("remainingModelsAtReview")} 次 · 截止 ${until(finalScope.optLong("expiresAt"))} · 本轮最长 ${finalScope.optInt("timeoutSeconds")} 秒。未知只查询原编号，不重发。", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { send("gateway.run", JSONObject().put("acceptedDigest", finalReview.optString("digest"))) }, enabled = enabled && !blocked && finalReview.optLong("expiresAt") > System.currentTimeMillis()) { Text("确认准确范围，启动本轮 CLI") }
        }
    }
}
