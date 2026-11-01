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
import dev.pocketengine.features.teams.TeamProjectRoundHost
import org.json.JSONObject

/** A cold result is not an expired run to revive. The actual new owner admission remains in the original app. */
@Composable internal fun TeamProjectRoundControls(runId: String, openPage: (String) -> TeamProjectRoundHost.Page, runWork: (Runnable) -> Unit, onTask: (String) -> Unit, failedWorkItem: String? = null) {
    var expanded by rememberSaveable(runId, failedWorkItem) { mutableStateOf(false) }
    if (!expanded) { TextButton(onClick = { expanded = true }) { Text(if (failedWorkItem == null) "从所选完整成果建立新一轮工作副本" else "从失败工作副本新开一轮") }; return }
    var retry by remember(runId, failedWorkItem) { mutableIntStateOf(0) }
    val opened = remember(runId, failedWorkItem, retry) { runCatching { openPage(runId) } }
    val page = opened.getOrNull()
    if (page == null) { Text("原成果暂不能开始新一轮：${opened.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text("重新读取准确成果") }; return }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var busy by remember(page) { mutableStateOf(false) }
    var facts by remember(page) { mutableStateOf<JSONObject?>(null) }
    var pending by remember(page) { mutableStateOf<TeamProjectRoundHost.Review?>(null) }
    var shown by remember(page) { mutableStateOf<JSONObject?>(null) }
    var goal by rememberSaveable(runId, failedWorkItem) { mutableStateOf("") }
    var models by rememberSaveable(runId, failedWorkItem) { mutableStateOf("") }
    var tools by rememberSaveable(runId, failedWorkItem) { mutableStateOf("") }
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
        try { runWork(Runnable { val result = runCatching(action); main.post { if (alive) { busy = false; result.fold(receive) { if (it is TeamProjectRoundHost.NotAccepted) submitted = false; notice = it.message ?: "原新一轮未得到确定回执，请查询原提交" } } } }) }
        catch (_: Exception) { busy = false; notice = "原工作队列不可用，未重新提交" }
    }
    LaunchedEffect(page) { work({ page.inspect() }) { facts = it; if (it.optBoolean("ready")) { if (models.isEmpty()) models = it.getInt("proposedNewModelRequestLimit").toString(); if (tools.isEmpty()) tools = it.getInt("proposedNewToolOperationLimit").toString() } } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (failedWorkItem == null) "从固定成果开始新一轮" else "从失败工作副本新开一轮（不是已审成果）", style = MaterialTheme.typography.titleSmall)
        Text((if (failedWorkItem == null) "复制的是你选定版本的完整manifest。" else "复制的是原Runner已终结、无活跃进程/未决效果的失败副本；保留准确未提交修改，不代表质量已审或测试通过。") + "只建立新的私有工作副本，不覆盖原项目/父活跃checkout，不运行代码。底层建立新的父任务/run与原Runner；旧失败、旧用量/unknown来源保留，不恢复过期grant。", style = MaterialTheme.typography.bodySmall)
        facts?.optJSONObject("source")?.let { source ->
            val prior = source.getJSONObject("priorProjectUsage")
            Text("原来源链累计预留/已用：模型 ${prior.optInt("modelRequestsReservedOrUsed")}，工具 ${prior.optInt("toolOperationsReservedOrUsed")}，外部job ${prior.optInt("externalJobsReservedOrUsed")}；原状态 ${source.getJSONObject("priorBudget").getJSONObject("reservationStates")}。费用/token未知，不是净额归零。", style = MaterialTheme.typography.bodySmall)
        }
        facts?.let { observed ->
            if (!observed.optBoolean("ready")) Text("尚不能建立新一轮：${observed.optString("blockedReason")}", color = MaterialTheme.colorScheme.error)
            val previous = observed.optJSONArray("previousSubmissions")
            for (i in 0 until (previous?.length() ?: 0)) { val row = previous!!.getJSONObject(i); Text("既有新一轮：${row.optString("taskStatus")} · 复制 ${row.optString("actualOperationState")}。原用量 ${row.optJSONObject("budget")?.optInt("toolOperationsReservedOrUsed") ?: 0}（预留/unknown不退款）", style = MaterialTheme.typography.bodySmall); TextButton(onClick = { onTask(row.getString("taskId")) }) { Text("打开这条原任务核对，不重发复制") } }
        }
        if (!submitted) {
            OutlinedTextField(goal, { goal = it; pending = null }, label = { Text("新一轮要完成什么") }, minLines = 2, maxLines = 5, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(models, { models = it; pending = null }, label = { Text("新的模型请求明确上限（3–128）") }, singleLine = true, enabled = !busy)
            OutlinedTextField(tools, { tools = it; pending = null }, label = { Text("新的工具操作明确上限（3–128，复制占1次）") }, singleLine = true, enabled = !busy)
            Text("建议值只是原流程估计，不保证复杂多轮足额；达到上限必须停。新成员文件/材料范围、命令及本版源码执行仍需之后原生明确批准，不继承旧成员权限。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { work({ page.review(goal, models.toInt(), tools.toInt()) }) { pending = it; shown = it.view(); accepted = false } }, enabled = !busy && facts?.optBoolean("ready") == true && goal.isNotBlank() && models.toIntOrNull() != null && tools.toIntOrNull() != null) { Text("审阅所选完整版本与新的执行上限") }
        }
        pending?.let { exact -> shown?.let { view ->
            val pin = view.getJSONObject("sourceReference")
            Text("${if (failedWorkItem == null) "固定成果 ${pin.optString("artifactId", pin.optString("id"))}" else "失败原任务 ${pin.optString("taskId")} / checkout ${pin.optString("checkoutId")}"} · ${pin.optString("archiveSha256", pin.optString("sha256", pin.optString("snapshotIdentity"))).take(16)}\n源码 ${view.getString("expectedSourceDigest")}\n新运行位置 ${view.getString("runtimeId")} · ${view.getString("runtimeIdentity").take(16)}", style = MaterialTheme.typography.bodySmall)
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) { val files = view.getJSONObject("manifest").getJSONArray("files"); for (i in 0 until files.length()) { val file = files.getJSONObject(i); Text("${file.getString("path")} · ${file.getLong("bytes")} B · ${file.getString("sha256").take(12)}", style = MaterialTheme.typography.bodySmall) } }
            Row { Checkbox(accepted, { accepted = it }, enabled = !busy); Text("我确认此固定版本、新目标和新模型/工具上限；只复制到新的私有副本，后续制作/命令另审") }
            Button(onClick = { submitted = true; work({ page.start(exact, view.getString("digest")) }) { ack -> pending = null; notice = if (ack.optString("status") == "accepted") "原新父任务已接收；后续计划与每次命令仍需原生审批。" else "输入回执待核对；只查原clientMessageId，不重发复制。"; ack.optString("taskId").takeIf(String::isNotBlank)?.let(onTask) } }, enabled = accepted && !busy && !submitted) { Text(if (busy) "正在登记原操作…" else "确认建立新一轮工作副本") }
        } }
        if (submitted) TextButton(onClick = { work({ page.querySubmission() }) { ack -> notice = "原提交状态：${ack.optString("status")} · ${ack.optString("clientMessageId")}"; ack.optString("taskId").takeIf(String::isNotBlank)?.let(onTask) } }, enabled = !busy) { Text("只查询这次原输入与任务，不重发") }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!submitted) TextButton(onClick = { expanded = false }, enabled = !busy) { Text("暂不建立新一轮") }
    }
}
