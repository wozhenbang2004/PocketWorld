package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/** Owner controls only. Rendering and opening never grants, renews or resumes a task. */
@Composable internal fun ExecutionScopeStartControls(phone: Boolean, tools: Int, models: Int, minutes: Int, setMinutes: (Int) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("execution_scope_start")) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 2.dp)) {
            Text("本任务范围 · $tools 次工具 / $models 次模型" + if (minutes == 0) " · 至完成或停止 ▾" else " · ${minutes}分钟 ▾", fontSize = 12.sp)
        }
        if (expanded) {
            Text(if (phone) "只在选择的手机执行位置和原目标内操作；支付、发送、删除、登录及越域操作仍需原具体授权。" else "仅使用本次宿主提供的工具。网页限明确站点与动作；文件、命令和外部效果仍按原具体范围审批。", fontSize = 12.sp, color = SecondaryInk)
            Text("累计预算不会因等待、失败、重试或继续任务而重置；模型失败也可能产生费用。到期或耗尽仅停止新派发。", fontSize = 12.sp, color = SecondaryInk)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0 to "至完成",30 to "30分钟",120 to "2小时").forEach { (value,label) ->
                    FilterChip(selected = minutes == value, onClick = { setMinutes(value) }, label = { Text(label, fontSize = 12.sp) })
                }
            }
        }
    }
}

@Composable internal fun ExecutionScopeControls(task: UiTask, controller: PocketWorldController) {
    if (task.raw.optJSONObject("origin") != null || task.raw.has("sourceLearningJob") || task.raw.has("teamAssignment") || task.raw.has("teamWriteScope") || task.raw.optString("kind") !in setOf("phone","assistant")) return
    val scope = task.raw.optJSONObject("executionScope")
    val revision = scope?.optLong("revision") ?: 0L
    var open by rememberSaveable(task.id) { mutableStateOf(false) }
    var tools by rememberSaveable(task.id, revision) { mutableStateOf((scope?.optInt("toolLimit") ?: controller.newTaskToolBudget).toString()) }
    var models by rememberSaveable(task.id, revision) { mutableStateOf((scope?.optInt("modelCallLimit") ?: controller.newTaskModelBudget).toString()) }
    var minutes by rememberSaveable(task.id, revision) { mutableStateOf(if (scope == null) 0 else -1) }
    val deadline = scope?.optLong("deadlineAt") ?: -1L
    Column(Modifier.fillMaxWidth().testTag("task_execution_scope")) {
        TextButton(onClick = { open = !open }) { Text(if (scope == null) "审阅旧任务范围并升级" else "任务范围与累计预算") }
        if (open) {
            Text(if (scope == null) "此旧任务仍使用原10分钟权限；下面的明确确认才会升级，打开本页不会续期。" else "工具 ${scope.optInt("toolsUsed")}/${scope.optInt("toolLimit")} · 模型 ${scope.optInt("modelCallsUsed")}/${scope.optInt("modelCallLimit")} · " + if (deadline == 0L) "至完成、停止或预算耗尽" else "到期：${DateFormat.getDateTimeInstance().format(Date(deadline))}", fontSize = 13.sp, color = SecondaryInk)
            val names = scope?.optJSONArray("tools")
            Text(if (names == null || names.length() == 0) "工具域：${if (task.raw.optString("kind") == "phone") "所选手机位置" else "原助手宿主"}；首次实际工具清单将在原授权登记，不授权尚未启用的新工具。" else "已固定工具：" + (0 until names.length()).joinToString("、") { names.optString(it) }, fontSize = 12.sp, color = SecondaryInk)
            Text("调整只改本任务总预算与期限，不启动/重放任何动作，也不扩大文件、站点、账号或危险操作权限。旧已执行、失败及未知请求均不退款。", fontSize = 12.sp, color = SecondaryInk)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(tools, { tools = it }, label = { Text("累计工具上限") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(models, { models = it }, label = { Text("累计模型上限") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            ((if (scope == null) emptyList() else listOf(-1 to "保留期限")) + listOf(0 to "至完成",30 to "延至30分钟",120 to "延至2小时")).chunked(2).forEach { choices ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    choices.forEach { (value,label) -> FilterChip(selected = minutes == value, onClick = { minutes = value }, label = { Text(label, fontSize = 12.sp) }) }
                }
            }
            Button(onClick = { controller.configureExecutionScope(task, minutes, tools.toIntOrNull() ?: 0, models.toIntOrNull() ?: 0) }, enabled = task.status !in setOf("completed","stopping") && (tools.toIntOrNull() ?: 0) in 1..400 && (models.toIntOrNull() ?: 0) in 1..512, modifier = Modifier.heightIn(min = 48.dp)) { Text("确认本次范围调整 · 不自动继续") }
        }
    }
}
