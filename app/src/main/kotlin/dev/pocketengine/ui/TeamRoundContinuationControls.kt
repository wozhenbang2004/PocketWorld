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
import dev.pocketengine.features.teams.TeamProjectRoundHost
import org.json.JSONObject

/** The existing round's remaining allowance, not a new grant or an automatic approval resume. */
@Composable internal fun TeamRoundContinuationControls(taskId: String, openPage: (String) -> TeamProjectRoundHost.ContinuationPage, runWork: (Runnable) -> Unit, refresh: (String) -> Unit) {
    var expanded by rememberSaveable(taskId) { mutableStateOf(false) }
    if (!expanded) { TextButton(onClick = { expanded = true }) { Text("按本轮原剩余额度继续") }; return }
    var retry by remember(taskId) { mutableIntStateOf(0) }
    val opened = remember(taskId, retry) { runCatching { openPage(taskId) } }
    val page = opened.getOrNull()
    if (page == null) { Text("暂不能读取原预算：${opened.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text("重新核对原任务") }; return }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var busy by remember(page) { mutableStateOf(false) }
    var pending by remember(page) { mutableStateOf<TeamProjectRoundHost.ContinuationReview?>(null) }
    var shown by remember(page) { mutableStateOf<JSONObject?>(null) }
    var feedback by rememberSaveable(taskId) { mutableStateOf("") }
    var accepted by remember(pending) { mutableStateOf(false) }
    var submitted by remember(page) { mutableStateOf(false) }
    var notice by remember(page) { mutableStateOf<String?>(null) }
    DisposableEffect(page, lifecycle) {
        fun current() = page.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> current() }; lifecycle.addObserver(observer); current()
        onDispose { alive = false; page.close(); lifecycle.removeObserver(observer) }
    }
    fun <T> work(action: () -> T, receive: (T) -> Unit) {
        if (busy || !alive) return
        busy = true; notice = null
        try { runWork(Runnable { val result = runCatching(action); main.post { if (alive) { busy = false; result.fold(receive) { notice = it.message ?: "原提交未确认；请查询，不重复启动" } } } }) }
        catch (_: Exception) { busy = false; notice = "工作队列未接手，请查询原任务；没有自动重试" }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("继续本轮原工作副本", style = MaterialTheme.typography.titleSmall)
        Text("保留同一预算池、已用/预留/unknown账目，只给这次真实新run绑定原剩余额度；不会增加预算、延长文件/命令授权或复活旧Control。源码与命令依旧逐版核对，未决效果和活进程会阻止继续。", style = MaterialTheme.typography.bodySmall)
        if (!submitted) {
            OutlinedTextField(feedback, { feedback = it; pending = null; shown = null }, enabled = !busy, label = { Text("本轮补充要求（可空，最多4000字）") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { work({ page.review(feedback) }) { pending = it; shown = it.view(); accepted = false } }, enabled = !busy && feedback.length <= 4000) { Text("核对当前源码与原池剩余") }
        }
        pending?.let { exact -> shown?.let { view ->
            val pool = view.getJSONObject("parentBudget")
            Text("本轮 scope ${view.getString("scopeId").take(12)} · 原执行 ${view.getString("fromRunId").take(12)}\n模型剩余 ${pool.getInt("remainingModelRequests")}；工具剩余 ${pool.getInt("remainingToolOperations")}；新增额度 0。", style = MaterialTheme.typography.bodySmall)
            Text("已用/预留：模型 ${pool.getInt("modelRequestsReservedOrUsed")}、工具 ${pool.getInt("toolOperationsReservedOrUsed")}。原状态 ${pool.getJSONObject("reservationStates")}；unknown不退款。\n当前源码 ${view.getString("sourceDigest").take(16)}；授权期限不延长。", style = MaterialTheme.typography.bodySmall)
            if (view.getJSONArray("pendingApprovals").length() > 0) Text("仍有准确操作待批准；预算续审不会代替它们，继续后仍可能等待。", style = MaterialTheme.typography.bodySmall)
            Row { Checkbox(accepted, { accepted = it }, enabled = !busy); Text("确认仅使用本轮原剩余，并保留原源码/工具/命令授权限制") }
            Button(onClick = { submitted = true; work({ page.start(exact, view.getString("digest")) }) { pending = null; notice = "已提交原执行入口；请核对实际任务，不自动重放。"; refresh(taskId) } }, enabled = accepted && !busy && !submitted) { Text("确认按原余额继续") }
        } }
        if (submitted) TextButton(onClick = { work({ page.query() }) { notice = "原任务状态：${it.optString("status")} · ${it.optString("runId").take(12)}；未重新提交。"; refresh(taskId) } }, enabled = !busy) { Text("只查询这次原任务") }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!submitted) TextButton(onClick = { expanded = false }, enabled = !busy) { Text("暂不继续") }
    }
}
