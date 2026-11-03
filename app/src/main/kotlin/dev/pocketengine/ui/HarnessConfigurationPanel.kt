package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Original remote owner page only. No raw credential, file path, grant or billing-proof input. */
@Composable
internal fun HarnessConfigurationPanel(taskId: String, snapshot: JSONObject, controller: PocketWorldController, busy: Boolean, foreground: Boolean) {
    var expanded by remember(taskId) { mutableStateOf(false) }
    var error by remember(taskId) { mutableStateOf<String?>(null) }
    var referenceDiff by remember(taskId, snapshot.optJSONObject("plan")?.optString("planDigest")) { mutableStateOf(false) }
    val task = controller.state.tasks.find { it.id == taskId }?.raw
    val operations = task?.optJSONArray("operations").objects().filter { it.optString("tool") == "harness_config" }.takeLast(8)
    // This selection can only be supplied by the original native gateway/Profile owner.
    // T05 owns creation of that bounded lease; we never ask the user to paste a token/hash.
    val selection = task?.optJSONObject("harnessConfigSelection")
    val config = snapshot.takeIf { it.optBoolean("configurationOnly") && it.optString("taskId") == taskId }
    val plan = config?.optJSONObject("plan")
    val receipt = config?.optJSONObject("receipt")
    val enabled = foreground && !busy
    fun send(command: String, input: JSONObject = JSONObject()) {
        try { controller.requestRemoteTask(command, input); error = null }
        catch (failure: Exception) { error = failure.message }
    }
    val gateway = snapshot.optJSONObject("harnessGateway")
    LaunchedEffect(taskId, snapshot.optJSONObject("gatewayContinuation")?.optString("operationId")) { if (snapshot.optJSONObject("gatewayContinuation") != null) expanded = true }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起 CLI 配置与模型网关" else "CLI 配置与模型网关") }
    val gatewayStatus = gateway?.optJSONObject("status")
    if (!expanded && gatewayStatus != null) Text(if (gatewayStatus.optBoolean("requiresReconcile")) "模型网关有未确认请求 · 展开只读核对" else if (gatewayStatus.optBoolean("running")) "模型网关运行中 · 展开查看期限或停止" else "原模型网关已停止 · 记录保留", style = MaterialTheme.typography.bodyMedium)
    if (!expanded) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        HarnessGatewayPanel(gateway, enabled, ::send, snapshot)
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Line)
        Text("只管理这个准确目标的配置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("预览不写文件。确认只替换逐项列出的配置，不批准 CLI 运行；文件读回也不是模型接管或 API 计费证明。外部修改会保留并阻止不安全恢复。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        if (selection != null) OutlinedButton(onClick = { send("openHarnessConfig", JSONObject().put("selection", JSONObject(selection.toString()))) }, enabled = enabled && config == null) { Text("核对本次原生 Profile 与目标") }
        else Text("启用上方已审阅的原任务网关后，使用“预览此原任务的 CLI 配置”继续。这里不接收账号凭据、手填 token 或任意配置路径。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
        operations.forEach { operation ->
            val id = operation.optString("operationId")
            val request = operation.optJSONObject("arguments")?.optJSONObject("request")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { send("openHarnessConfig", JSONObject().put("operationId", id)) }, enabled = enabled && config == null && id.isNotBlank()) { Text("核对原配置操作 ${id.take(8)}") }
                if (request != null && operation.optJSONObject("receipt")?.optString("state") in setOf("COMMITTED", "RECOVERY_REQUIRED")) TextButton(onClick = {
                    send("openHarnessConfig", JSONObject().put("selection", JSONObject().put("bindingId", request.optString("bindingId")).put("restoreOperationId", id)))
                }, enabled = enabled && config == null) { Text("预览恢复") }
            }
        }
        if (config != null) {
            config.optJSONObject("workProfileSource")?.let { profile ->
                Text("任务工作配置 · 版本 ${profile.optLong("profileRevision")}", color = SecondaryInk, fontSize = 12.sp)
            }
            Text("如需切换目标或另一份原操作，请返回再打开原任务页；回到前台后先重新查询。这里不会自动续用旧确认。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
            val target = config.optJSONObject("target")
            val scope = plan?.optJSONObject("scopeDelta")
            SelectionContainer {
                Text("电脑：${config.optJSONObject("source")?.optString("profileId").orEmpty()}\n配置根：${target?.optString("configRoot").orEmpty().ifBlank { scope?.optString("configRoot").orEmpty() }}\n影响范围：${scope?.optString("scope").orEmpty().ifBlank { "待读取准确目标" }}\n原操作：${config.optString("operationId").ifBlank { "尚未准备" }}", fontSize = 12.sp, lineHeight = 20.sp)
            }
            Text(config.optString("risk"), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
            config.optJSONArray("referenceSelection").objects().forEach { source -> Text("${source.optString("name")} · 版本 ${source.optInt("revision")} · ${source.optInt("files")} 份完整文本", fontSize = 12.sp) }
            if (config.has("referenceTransmissionNotice")) Text(config.optString("referenceTransmissionNotice"), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
            if (plan == null && config.optString("operationId").isBlank()) OutlinedButton(onClick = { send("previewHarnessConfig") }, enabled = enabled) { Text("读取实际文件并预览差异") }
            val files = plan?.optJSONArray("filePlans").objects()
            if (files.size > 1) TextButton(onClick = { referenceDiff = !referenceDiff }) { Text(if (referenceDiff) "收起完整参考文件差异" else "展开完整参考文件差异 · ${files.size - 1} 份") }
            files.filterIndexed { index, _ -> index == 0 || referenceDiff }.forEach { file ->
                Text(file.optString("pathRef"), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                file.optJSONArray("operations").objects().forEach { change ->
                    val before = change.optJSONObject("before")
                    val after = change.optJSONObject("after")
                    fun shown(value: JSONObject?): String {
                        if (value?.optBoolean("absent") == true) return "原本不存在"
                        value?.optJSONArray("chunks")?.let { parts -> return buildString { repeat(parts.length()) { append(parts.getString(it)) } } }
                        return if (value?.has("value") == true) value.opt("value").toString() else "未确认"
                    }
                    Text("${change.optString("field")}\n${shown(before)} → ${shown(after)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 19.sp)
                }
            }
            plan?.optJSONArray("conflicts").objects().forEach { Text("保留冲突：${it.optString("reason")}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            if (plan != null && receipt?.optString("state") !in setOf("COMMITTED", "CANCELLED_PREVIEW")) {
                val current = plan.optLong("expiresAt") > System.currentTimeMillis()
                if (!current) Text("此计划已过期，只可查询原回执或关闭未派发预览。", color = SecondaryInk, fontSize = 12.sp)
                Button(onClick = { send("confirmHarnessConfig", JSONObject().put("acceptedPlanDigest", plan.optString("planDigest"))) }, enabled = enabled && current && plan.optJSONArray("conflicts").objects().isEmpty()) { Text(if (plan.optString("restoreOf").isNotBlank() && !plan.isNull("restoreOf")) "确认这份准确恢复计划" else "确认应用这份准确配置计划") }
                TextButton(onClick = { send("cancelHarnessConfigPreview") }, enabled = enabled && config.optString("operationState") in setOf("prepared", "unknown")) { Text("核对并关闭尚未派发的预览") }
            }
            if (config.optString("operationId").isNotBlank()) TextButton(onClick = { send("queryHarnessConfig") }, enabled = enabled) { Text("只查询原操作回执") }
            receipt?.let { Text("原事务回执：${it.optString("state")} · 当次文件读回：${if (it.optBoolean("effectiveReadback")) "已核对" else "未确认"} · 模型运行：未验证。原回执不代表此刻文件没有外部变化。", fontSize = 12.sp, lineHeight = 20.sp) }
            receipt?.optJSONObject("referenceProjection")?.let { Text("所选文本参考：${if (receipt.optString("state") == "COMMITTED" && it.optBoolean("effectiveReadback")) { if (it.optBoolean("restored")) "已按准确原计划恢复并读回" else "准确版本文件已读回" } else "事务未完整确认，不能作为已应用或已恢复"} · 仅文本参考 · 未增加工具或脚本执行权限 · 实际发现/使用以原任务证据为准。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}
