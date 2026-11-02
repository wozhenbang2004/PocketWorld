package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.teams.TeamCoordinator
import dev.pocketengine.features.teams.TeamResearchHost
import dev.pocketengine.store.TaskStore
import org.json.JSONObject

/** Same original pool; explicit owner decision, not an account quota screen or an automatic restart. */
@Composable internal fun TeamResearchBudgetControls(
    run: JSONObject, openPage: (String) -> TeamResearchHost.Page, runWork: (Runnable) -> Unit
) {
    if (run.optString("researchExecutionFormat") != TeamCoordinator.RESEARCH_FORMAT) return
    val runId = run.getString("id")
    var expanded by rememberSaveable(runId) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起研究次数审阅" else "研究次数与继续") }
    if (!expanded) return
    var retry by remember(runId) { mutableIntStateOf(0) }
    val opened = remember(runId, retry) { runCatching { openPage(runId) } }
    val page = opened.getOrNull()
    if (page == null) {
        Text("原研究次数暂不可读：${opened.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { retry++ }) { Text("重新读取原研究") }; return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var busy by remember(page) { mutableStateOf(false) }
    var current by remember(page) { mutableStateOf<JSONObject?>(null) }
    var models by rememberSaveable(runId) { mutableStateOf("") }
    var reads by rememberSaveable(runId) { mutableStateOf("") }
    var pending by remember(page) { mutableStateOf<TaskStore.TeamResearchBudgetReview?>(null) }
    var shown by remember(page) { mutableStateOf<JSONObject?>(null) }
    var accepted by remember(pending) { mutableStateOf(false) }
    var notice by remember(page) { mutableStateOf<String?>(null) }
    DisposableEffect(page, lifecycle) {
        fun foreground() = page.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> foreground() }; lifecycle.addObserver(observer); foreground()
        onDispose { alive = false; page.close(); lifecycle.removeObserver(observer) }
    }
    fun <T> work(action: () -> T, apply: (T) -> Unit) {
        if (busy || !alive) return
        busy = true; notice = null
        try { runWork(Runnable { val value = runCatching(action); main.post { if (alive) { busy = false; value.fold(apply) { notice = it.message ?: "未确认保存；请重读，不重复批准" } } } }) }
        catch (_: Exception) { busy = false; notice = "原工作队列未接手，未保存或启动" }
    }
    LaunchedEffect(page) { work({ page.current() }) { current = it; val pool = it.getJSONObject("parentBudget"); if (models.isEmpty()) models = pool.getInt("modelRequestLimit").toString(); if (reads.isEmpty()) reads = pool.getInt("toolOperationLimit").toString() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TeamCaption("达到本次调用或读取上限会保留检查点和已完成内容。这是本任务的执行上限，不代表模型账户或订阅余额。先等原成员安全暂停；未知操作不能靠追加次数自动重做。")
        current?.let { facts ->
            val pool = facts.getJSONObject("parentBudget")
            Text("模型 ${pool.getInt("modelRequestsReservedOrUsed")} / ${pool.getInt("modelRequestLimit")} · 读取/消息 ${pool.getInt("toolOperationsReservedOrUsed")} / ${pool.getInt("toolOperationLimit")}", style = MaterialTheme.typography.bodyMedium)
            TeamCaption("包括已用及预留；整理历史的摘要请求也计入同一模型上限。剩余模型 ${pool.getInt("remainingModelRequests")}，读取 ${pool.getInt("remainingToolOperations")}。")
            val states = pool.getJSONObject("reservationStates")
            listOf("reserved" to "预留", "started" to "已派发", "settled" to "已结算", "unknown" to "结果未确定").forEach { (key, label) -> states.optJSONObject(key)?.let { counts -> TeamCaption("$label：模型 ${counts.optInt("modelRequests")}，读取/消息 ${counts.optInt("toolOperations")}") } }
            facts.optJSONObject("parentExecutionScope")?.takeIf { it.optBoolean("scopePresent") }?.let { scope -> TeamCaption("原父任务累计剩余：模型 ${scope.optInt("modelCallsRemaining")}、工具 ${scope.optInt("toolsRemaining")}；本页不能扩大父总额或延长资料授权。") }
            OutlinedTextField(models, { models = it; pending = null; shown = null }, enabled = !busy, label = { Text("新累计模型请求上限（含摘要，最多 ${facts.getInt("modelEngineeringMaximum")}）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(reads, { reads = it; pending = null; shown = null }, enabled = !busy, label = { Text("新累计读取/消息上限（最多 ${facts.getInt("toolEngineeringMaximum")}）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { work({ page.review(models.toInt(), reads.toInt()) }) { pending = it; shown = it.view(); accepted = false } }, enabled = !busy && models.toIntOrNull() != null && reads.toIntOrNull() != null) { Text("核对准确已用与本次追加") }
        }
        pending?.let { exact -> shown?.let { facts ->
            val old = facts.getJSONObject("parentBudget")
            TeamCaption("模型 ${old.getInt("modelRequestLimit")} → ${facts.getInt("proposedModelRequestLimit")}；读取/消息 ${old.getInt("toolOperationLimit")} → ${facts.getInt("proposedToolOperationLimit")}。旧已用、预留和未确定用量均保留；工具集、资料范围和期限不变。")
            Row { Checkbox(accepted, { accepted = it }, enabled = !busy); Text("我明确批准以上原累计新上限，不自动启动或重做") }
            Button(onClick = { work({ page.approve(exact, facts.getString("digest")) }) { saved -> pending = null; shown = null; current = JSONObject(facts.toString()).put("parentBudget", saved.getJSONObject("parentBudget")); notice = "次数已保存，尚未启动。请刷新原团队后，明确继续原成员；已完成读取与结果不会重做。" } }, enabled = accepted && !busy) { Text("仅保存本次追加次数") }
        } }
        TextButton(onClick = { work({ page.synchronize() }) { current = it; pending = null; notice = "已重读原保存次数，未启动执行。" } }, enabled = !busy) { Text("重读保存的次数（不启动）") }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
