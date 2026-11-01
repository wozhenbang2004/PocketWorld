package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.teams.TaskStoreTeamWriteHost
import dev.pocketengine.store.TaskStore
import org.json.JSONArray
import org.json.JSONObject

/** Remote authors and the one phone integrator have independent native scopes. */
@Composable internal fun TeamPhoneIntegratorControls(runId: String, openPage: (String) -> TaskStoreTeamWriteHost.PhoneIntegratorPage, runWork: (Runnable) -> Unit) {
    var expanded by rememberSaveable(runId) { mutableStateOf(false) }
    if (!expanded) { TextButton(onClick = { expanded = true }) { Text("审阅手机唯一整合者：组合检查与打包") }; return }
    var retry by remember(runId) { mutableIntStateOf(0) }
    val opened = remember(runId, retry) { runCatching { openPage(runId) } }
    val page = opened.getOrNull()
    if (page == null) { Text("原整合者审阅不可用：${opened.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text("重读原任务") }; return }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var busy by remember(page) { mutableStateOf(false) }
    var current by remember(page) { mutableStateOf(JSONObject()) }
    var exact by remember(page) { mutableStateOf<TaskStore.TeamPhoneIntegratorReview?>(null) }
    var shown by remember(page) { mutableStateOf(JSONObject()) }
    var commands by rememberSaveable(runId) { mutableStateOf("[]") }
    var risk by rememberSaveable(runId) { mutableStateOf(false) }
    var accepted by remember(exact) { mutableStateOf(false) }
    var notice by remember(page) { mutableStateOf<String?>(null) }
    DisposableEffect(page, lifecycle) {
        fun foreground() = page.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> foreground() }; lifecycle.addObserver(observer); foreground()
        onDispose { alive = false; page.close(); lifecycle.removeObserver(observer) }
    }
    fun <T> work(action: () -> T, receive: (T) -> Unit) {
        if (busy || !alive) return
        busy = true; notice = null
        try { runWork(Runnable { val result = runCatching(action); main.post { if (alive) { busy = false; result.fold(receive) { notice = it.message ?: "原回执待核对，不重复派发" } } } }) }
        catch (_: Exception) { busy = false; notice = "原工作队列不可用" }
    }
    LaunchedEffect(page) { work({ page.snapshot() }) { current = it } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("远端成员制作，手机唯一整合者完成组合检查与打包", style = MaterialTheme.typography.titleSmall)
        Text("这是独立的本机授权，不来自remoteSelection。作者仍不能执行手机命令。完整固定基线只与独审通过、主人精确批准且实际CAS的版本组合；父活跃checkout不自动覆盖。远端测试通过不等于手机依赖/平台兼容。", style = MaterialTheme.typography.bodySmall)
        if (current.optBoolean("approved")) {
            Text("原生范围已记录。远端独立job、worker命令请求、最终planHash接收仍各自明确审批；这里不会启动模型或复活旧Control。", style = MaterialTheme.typography.bodySmall)
        } else {
            TextButton(onClick = { work({ page.commandSuggestions() }) { commands = it.toString(2); risk = false; exact = null } }, enabled = !busy) { Text("读取原父项目的真实命令建议（仍需核对）") }
            OutlinedTextField(commands, { commands = it; exact = null; risk = false }, label = { Text("手机组合test/build的literal命令模板 JSON") }, minLines = 3, maxLines = 9, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Row { Checkbox(risk, { risk = it; exact = null }, enabled = !busy); Text("我允许唯一整合者执行本版副本源码和这些命令；同应用UID可能访问应用可访问文件/网络，不是checkout沙箱") }
            OutlinedButton(onClick = { work({ page.review(JSONObject().put("allowCommands", true).put("sameUidExecutionAcknowledged", risk).put("commands", JSONArray(commands))) }) { exact = it; shown = it.view(); accepted = false } }, enabled = !busy && risk && commands.length <= 24000) { Text("审阅准确完整包、本机命令与剩余预算") }
        }
        exact?.let { review ->
            TeamCaption("原固定分工将逐项登记为同一原TaskStore工作项，保留原assignmentId；工作项不代表新增job批准，更不是虚构讨论共识。")
            shown.getJSONObject("proposedWorkPlan").getJSONArray("items").objects().forEach { item -> TeamCaption("${item.getString("title")} → ${item.getString("memberId")} · 工作项 ${item.getString("workItemId").take(8)} / 原assignment ${item.getString("assignmentId").take(8)} · 前置 ${item.getJSONArray("dependsOn").length()}项") }
            val selected = shown.getJSONObject("selection")
            Text("基线 ${selected.getString("snapshotIdentity")}\n本机 ${selected.getString("runtimeId")} · ${selected.getString("runtimeIdentity").take(16)}", style = MaterialTheme.typography.bodySmall)
            Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) { selected.getJSONArray("sourceManifest").objects().forEach { file -> Text("${file.getString("path")} · ${file.getLong("bytes")}B · ${file.getString("sha256").take(12)}", style = MaterialTheme.typography.bodySmall) } }
            val budget = shown.getJSONObject("parentBudget")
            Text("原预留/已用（含unknown）：模型 ${budget.getInt("modelRequestsReservedOrUsed")}、工具 ${budget.getInt("toolOperationsReservedOrUsed")}。拟累计上限：模型 ${shown.getInt("proposedModelRequestLimit")} / 工具 ${shown.getInt("proposedToolOperationLimit")}，不是自动补满。计划估计不保证复杂任务足额。", style = MaterialTheme.typography.bodySmall)
            if (shown.getBoolean("parentBudgetIncreaseRequired")) {
                Text("原父真实操作额度不足 ${shown.getInt("parentOperationShortfall")}；仅预算拟增至 ${shown.getInt("proposedParentStepCeiling")}，不延长过期时间或扩工具/资源。", color = MaterialTheme.colorScheme.error)
                Button(onClick = { work({ page.approveParentBudget(review, shown.getString("digest")) }) { exact = it; shown = it.view(); accepted = false } }, enabled = !busy && shown.getInt("proposedParentStepCeiling") <= 400) { Text("明确同意这次父预算增加，再重审范围") }
            }
            Row { Checkbox(accepted, { accepted = it }, enabled = !busy); Text("我确认这个完整基线、本机literal命令、同UID风险与明确累计预算；不授任何作者手机命令") }
            Button(onClick = { work({ page.approve(review, shown.getString("digest")) }) { exact = null; current = JSONObject().put("approved", true); notice = "原范围已保存；现在可分别审阅每名远端作者的独立job，尚未派发。" } }, enabled = accepted && !busy && !shown.getBoolean("parentBudgetIncreaseRequired")) { Text("只批准手机唯一整合者范围，不启动job") }
        }
        TextButton(onClick = { work({ page.synchronizeRecorded() }) { current = JSONObject().put("approved", true); notice = "已同步原已批元数据；未恢复执行或重发job。" } }, enabled = !busy) { Text("核对并幂等同步既有原审批（不执行）") }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
