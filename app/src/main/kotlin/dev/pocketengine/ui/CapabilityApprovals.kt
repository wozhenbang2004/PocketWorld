package dev.pocketengine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
internal fun CapabilityApprovals(task: UiTask, controller: PocketWorldController) {
    val approvals = pendingCapabilityApprovals(task)
    val hasTeamPlanApproval = approvals.any { it.optString("tool") == "teams_start" && it.optJSONObject("capability")?.optString("command") == "start_parent_plan" }
    val hasLiveApproval = approvals.any { it.optString("tool") == "project_execute" && it.optJSONObject("capability")?.optJSONObject("ownedService")?.optBoolean("subsequentSourceExecution") == true }
    val invalidated = task.raw.optJSONArray("approvals").objects().filter { it.optString("state", it.optString("status")) == "invalidated" }
    var invalidatedDetails by rememberSaveable(task.id) { mutableStateOf(false) }
    if (approvals.isEmpty() && invalidated.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 18.dp).clip(Rounded).background(Soft).padding(16.dp)) {
        Text(if (approvals.isNotEmpty()) "需要你授权的操作" else "旧授权已失效", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (approvals.isNotEmpty()) Text(if (hasTeamPlanApproval) "团队计划包含所列范围内的连续草稿制作与协调，不是单次写原项目；请展开原计划并明确确认预算。" else if (hasLiveApproval) "逐项核对下方范围：热更新启动也包含列明源码在本轮的后续执行；拒绝后不会执行。" else "只授权下面这一次具体操作；拒绝后不会执行它。", color = SecondaryInk, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        approvals.forEach { approval ->
            val id = approval.optString("approvalId", approval.optString("id"))
            val tool = approval.optString("tool")
            val arguments = approval.optJSONObject("arguments") ?: JSONObject()
            val capability = approval.optJSONObject("capability") ?: JSONObject()
            val nativeMcpRisk = McpLocalProgramApprovalText.describe(capability)
            val liveScope = capability.optJSONObject("ownedService")?.takeIf { tool == "project_execute" && it.optBoolean("subsequentSourceExecution") }
            val teamPlan = tool == "teams_start" && capability.optString("command") == "start_parent_plan"
            var reviewedTeamPlan by remember(id, capability.optString("planHash")) { mutableStateOf(false) }
            var reviewedLiveScope by remember(id, liveScope?.optString("liveEditPolicyDigest")) { mutableStateOf(false) }
            val pending = (task.id + ":" + id) in controller.state.approvalRequests
            var details by rememberSaveable(id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(approval.optString("message").ifBlank { approvalLabel(tool) }, fontSize = 14.sp, lineHeight = 22.sp)
                if (tool == dev.pocketengine.features.semantic.SemanticCapabilityPort.DISPATCH) SelectionContainer {
                    Text(dev.pocketengine.features.semantic.SemanticApprovalText.describe(capability), fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
                }
                if (liveScope != null) {
                    Text("本次启动包含限定热改与后续源码执行", color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    val paths = liveScope.optJSONArray("editablePaths")
                    Text("仅原项目本轮的既有文件：" + (0 until (paths?.length() ?: 0)).joinToString("、") { paths!!.optString(it) } + "。每次仍须当前SHA、原操作预算和真实服务写租约；配置、锁、工具脚本、环境和新文件不在此许可内。原任务停止、授权到期或撤销必须停止并排空。" + (if (liveScope.optBoolean("retainAfterTurn")) "本次明确保留：模型本轮答复后仍可试用和编辑，最多 ${liveScope.optLong("timeoutMillis") / 1000} 秒，原任务期限更早时优先停止。" else "未选择轮次后保留，最长5分钟，模型轮次结束即收尾。") + "不是冷重启持久后台或公网服务。", fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(checked = reviewedLiveScope, onCheckedChange = { reviewedLiveScope = it })
                        Text("我已核对以上原文件范围，并允许这些源码后续修改在本轮服务中执行", fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f).padding(top = 10.dp))
                    }
                }
                if (teamPlan) TeamParentPlanApproval(task.id, id, capability, controller) { reviewedTeamPlan = it }
                if (nativeMcpRisk != null) SelectionContainer { Text(nativeMcpRisk, color = Danger, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp)) }
                if (capability.optString("pluginId").isNotBlank()) Text("插件 " + capability.optString("pluginId") + " · v" + capability.optString("version") + " · 安装 " + capability.optString("installationId").takeLast(8), fontSize = 11.sp, color = SecondaryInk, modifier = Modifier.padding(top = 6.dp))
                arguments.keys().asSequence().filter { it in setOf("text", "goal", "url", "path", "packageName", "name", "prompt") }.take(4).forEach { key ->
                    SelectionContainer { Text(argumentLabel(key) + "：" + arguments.optString(key).take(800), fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
                TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) { Text(if (details) "收起操作参数" else "查看操作参数", fontSize = 11.sp) }
                if (details) SelectionContainer { Text(tool + "\n" + arguments.toString(2) + if (capability.length() > 0) "\n" + capability.toString(2) else "", fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 18.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller.approveCapability(task, id, false) }, enabled = id.isNotBlank() && !pending) { Text("拒绝") }
                    Button(onClick = { controller.approveCapability(task, id, true) }, enabled = id.isNotBlank() && !pending && (liveScope == null || reviewedLiveScope) && (!teamPlan || reviewedTeamPlan)) { Text(if (pending) "处理中" else if (teamPlan) "批准此计划与可见预算" else if (liveScope != null) "允许启动与限定热改" else if (nativeMcpRisk != null) "允许运行本机程序" else "允许这次操作") }
                }
            }
        }
        if (invalidated.isNotEmpty()) {
            Text(invalidated.size.toString() + " 项旧授权因执行代次或能力范围变化而失效，不能继续使用。", fontSize = 11.sp, lineHeight = 19.sp, color = SecondaryInk, modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { invalidatedDetails = !invalidatedDetails }, contentPadding = PaddingValues(0.dp)) { Text(if (invalidatedDetails) "收起失效记录" else "查看失效记录", fontSize = 11.sp) }
            if (invalidatedDetails) invalidated.forEach { approval -> val capability = approval.optJSONObject("capability") ?: JSONObject(); Text(approval.optString("tool") + " · " + capability.optString("pluginId") + " v" + capability.optString("version") + " · 安装 " + capability.optString("installationId").takeLast(8), fontSize = 11.sp, lineHeight = 19.sp, color = SecondaryInk) }
        }
    }
}
private fun approvalLabel(tool: String): String = when {
    tool == "group_media_image" -> "为原群任务生成这张图片"
    tool == "group_media_audio" -> "为原群任务生成这段语音"
    tool == "group_media_video" -> "为原群任务生成这段视频"
    tool.contains("submitJob") -> "在电脑环境启动任务"
    tool.contains("workspace") && tool.contains("write") -> "写入项目文件"
    tool.contains("outbox.send") -> "发送这条消息"
    tool.contains("plugin") -> "调用已配置的插件工具"
    else -> "执行操作：" + tool
}
private fun argumentLabel(key: String): String = when (key) { "text" -> "内容"; "goal", "prompt" -> "目标"; "url" -> "网址"; "path" -> "文件"; "packageName" -> "应用"; "name" -> "名称"; else -> key }
