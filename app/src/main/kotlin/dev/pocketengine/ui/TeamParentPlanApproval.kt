package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** The full immutable native proposal is loaded separately. Tool JSON and a chat summary are not approval. */
@Composable
internal fun TeamParentPlanApproval(
    taskId: String, approvalId: String, capability: JSONObject,
    controller: PocketWorldController, onReviewed: (Boolean) -> Unit
) {
    val expectedHash = capability.optString("planHash")
    val main = remember { Handler(Looper.getMainLooper()) }
    var retry by remember(approvalId) { mutableIntStateOf(0) }
    var busy by remember(approvalId, expectedHash) { mutableStateOf(true) }
    var original by remember(approvalId, expectedHash) { mutableStateOf<JSONObject?>(null) }
    var error by remember(approvalId, expectedHash) { mutableStateOf<String?>(null) }
    var accepted by remember(approvalId, expectedHash) { mutableStateOf(false) }
    DisposableEffect(taskId, approvalId, expectedHash, retry) {
        var alive = true
        busy = true; error = null; original = null; accepted = false; onReviewed(false)
        try {
            controller.runEcosystemControlWork(Runnable {
                val result = runCatching {
                    controller.readTeamParentPlanApproval(taskId, approvalId).also {
                        check(it.getString("planRef") == capability.getString("planRef") && it.getString("planHash") == expectedHash) { "原生计划与当前审批不一致" }
                    }
                }
                main.post { if (alive) { busy = false; result.fold({ original = it }, { error = it.message ?: "无法读取原计划，未授权" }) } }
            })
        } catch (_: Exception) { busy = false; error = "原任务工作队列暂不可用，未授权" }
        onDispose { alive = false; onReviewed(false) }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("主代理提出的文件工作计划", style = MaterialTheme.typography.titleSmall)
        TeamCaption("只允许本次所选文件的隔离草稿制作和列明协调动作。父代理不是主人；它不能自批原项目写入、命令或额外预算。")
        if (busy) Text("正在读取原数据库中的完整不可变计划…", style = MaterialTheme.typography.bodySmall)
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }) { Text("重新读取原计划") }
        }
        original?.getJSONObject("plan")?.let { plan ->
            val members = plan.getJSONObject("definition").getJSONArray("members").objects()
            val items = plan.getJSONArray("items").objects()
            val source = plan.getJSONObject("sourceParent")
            SelectionContainer { Text(plan.getString("goal"), style = MaterialTheme.typography.bodyMedium) }
            TeamCaption("原项目 ${source.getString("targetProjectId")}；仅 1–8 个互斥文字文件副本，不是完整编程工作区或 OS 读隔离。")
            items.forEach { item -> key(item.getString("workItemId")) {
                val member = members.firstOrNull { it.optString("id") == item.getString("memberId") }
                Text(item.getString("title") + " → " + (member?.optString("name") ?: item.getString("memberId")), style = MaterialTheme.typography.titleSmall)
                TeamCaption("成员 ${item.getString("memberId")} · 工作项 ${item.getString("workItemId").take(8)}；同成员可依次处理多个工作项。")
                val deps = item.getJSONArray("dependsOn")
                if (deps.length() > 0) TeamCaption("等待前置冻结：" + (0 until deps.length()).joinToString("、") { index -> items.firstOrNull { it.optString("workItemId") == deps.getString(index) }?.optString("title") ?: deps.getString(index) })
                item.getJSONArray("files").objects().forEach { file -> SelectionContainer { TeamCaption("${file.getString("path")} · 原基线 v${file.getInt("baseVersion")} / ${file.getString("baseHash")}") } }
                var expanded by rememberSaveable(approvalId, item.getString("workItemId")) { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起工作要求" else "查看完整要求与验收条件") }
                if (expanded) SelectionContainer { Text("要求：${item.getString("instruction")}\n验收：${item.getString("acceptance")}", style = MaterialTheme.typography.bodySmall) }
            } }
            val previous = source.optJSONObject("parentBudget")
            val budget = plan.getJSONObject("budgetPlan")
            Text("本次原父池累计预算", style = MaterialTheme.typography.titleSmall)
            TeamCaption("模型请求总上限 ${previous?.optInt("modelRequestLimit") ?: 0} → ${plan.getInt("modelRequestLimit")}；工具操作总上限 ${previous?.optInt("toolOperationLimit") ?: 0} → ${plan.getInt("toolOperationLimit")}。既有预留、实耗和未知账目保留，不清零。")
            TeamCaption("所选文件链工具步骤 ${budget.getInt("selectedFileToolSteps")}，父协调计划 ${budget.getInt("parentOrchestrationToolSteps")}，规划/审批原操作 ${budget.getInt("priorParentTeamToolOperationsNotYetInPool")}，明确工具余量 ${budget.getInt("toolRetryMargin")}。父模型新增估计 ${budget.getInt("estimatedParentModelRequests")}，成员及审查估计 ${budget.getInt("estimatedModelRequests")}，模型余量 ${budget.getInt("modelRetryMargin")}；复杂多轮不保证够用。")
            TeamCaption("捕获时父原授权操作剩余 ${source.getInt("remainingOperations")}；批准计划不补父 grant，启动时仍核真实剩余。内部 token/费用未知，不按 0 计。")
            TeamCaption("父代理可定向排队、回答原问题、安全不增额继续、暂停/停止、交接明确未开始项及请求独审/整合/修订。停止 ACK 不代表排空；未知外效不能重派或退款。最终原项目仍须独审、你的准确 planHash 批准及唯一整合者 CAS。")
            TeamCaption("期限：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(plan.getLong("expiresAt")))}；Reply 不续期。")
            SelectionContainer { TeamCaption("不可变计划 ${capability.getString("planRef")}\nSHA-256 $expectedHash") }
            Row {
                Checkbox(accepted, { accepted = it; onReviewed(it) })
                Text("我已核对原成员、依赖、文件基线、范围与累计预算", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(top = 10.dp))
            }
        }
    }
}
