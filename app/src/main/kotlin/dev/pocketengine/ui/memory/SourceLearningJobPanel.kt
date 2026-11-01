package dev.pocketengine.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/** The original task is the workbench. Leaving stops only this invocation, not its saved plan. */
@Composable
internal fun SourceLearningJobPanel(task: JSONObject, controller: PocketWorldController, reviewCandidates: () -> Unit) {
    val taskId = task.getString("id")
    var pending by remember(taskId) { mutableStateOf<Pair<String, String>?>(null) }
    var current by remember(taskId) { mutableStateOf<JSONObject?>(null) }
    var error by remember(taskId) { mutableStateOf<String?>(null) }
    var runConsent by remember(taskId) { mutableStateOf(false) }
    var skipConsent by remember(taskId) { mutableStateOf(false) }
    DisposableEffect(taskId) { controller.enterOwnerDomain("learning"); onDispose { controller.leaveOwnerDomain("learning") } }
    fun request(command: String, payload: JSONObject) {
        if (pending != null) return
        error = null
        pending = command to controller.requestOwnerDomain("learning", command, payload)
    }
    fun refresh() = request("getLearningJob", JSONObject().put("taskId", taskId))
    LaunchedEffect(taskId) { refresh() }
    val responses = controller.state.ownerResponses("learning")
    val waiting = pending
    LaunchedEffect(waiting, responses[waiting?.first]?.toString()) {
        val action = waiting ?: return@LaunchedEffect
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(action.second, responses[action.first]) ?: return@LaunchedEffect
        pending = null
        if (!envelope.getBoolean("ok")) { error = envelope.optString("error", "原批次未完成，请刷新水位核对。"); return@LaunchedEffect }
        try {
            val result = envelope.getJSONObject("result")
            require(result.getString("taskId") == taskId && result.opt("formalMemorySaved") == false && result.opt("executionAuthorized") == false)
            require(result.getJSONObject("job").getString("format") == "pocketworld.source-learning-job.v1")
            current = result
        } catch (_: Exception) { error = "未取得原学习任务的当前水位，未继续或补发。" }
    }
    val snapshot = current
    val job = snapshot?.optJSONObject("job")
    val input = job?.optJSONArray("inputs")
    val results = job?.optJSONArray("results")
    val busy = pending?.first == "runLearningJob"
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("逐批来源学习", style = MaterialTheme.typography.titleSmall)
        Text("每次最多提炼 3 份来源；已完成批次不重发。只生成待审候选，不自动保存正式记忆或启用技能。离开此页会停止本次调用，水位留在这个原任务。", style = MaterialTheme.typography.bodySmall)
        if (job != null) {
            Text("已处理 ${job.getInt("nextIndex")} / ${input?.length() ?: 0} · 已预留模型尝试 ${job.getInt("reservedModelAttempts")}", style = MaterialTheme.typography.bodyMedium)
            Text("预留尝试不等于服务商已计费；真实用量/未知状态在本任务推理回执中核对。", style = MaterialTheme.typography.bodySmall)
            snapshot.optJSONObject("active")?.let { active -> Text(when (active.optString("stage")) {
                "draft_recorded" -> "真实模型 draft 已保存；继续时先核对来源并提交同一候选，不重新调用模型。"
                "inflight" -> "原批次正在执行，尚未确认收到模型 draft。"
                else -> "这批模型结果未知；不能当未发生或退款，也不会自动重试。"
            }, style = MaterialTheme.typography.bodySmall) }
            if (input != null) for (i in 0 until input.length()) {
                val row = input.getJSONObject(i)
                val settled = if (results != null && i < results.length()) results.getJSONObject(i) else null
                Text("${i + 1}. ${row.getString("title")} · " + when {
                    settled?.optJSONObject("candidateRef") != null -> "候选已登记，须回审核页核对当前状态"
                    settled?.optString("outcome") == "no_candidate" -> "依据不足，未形成候选"
                    settled != null -> "主人已跳过；没有重试或退款"
                    i == job.getInt("nextIndex") -> "当前批次"
                    else -> "尚未处理"
                }, style = MaterialTheme.typography.bodySmall)
            }
        } else if (pending != null) Text("正在读取原任务水位…", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refresh() }, enabled = pending == null) { Text("刷新原水位") }
            Button(onClick = { runConsent = true }, enabled = pending == null && snapshot?.optBoolean("canContinue") == true) { Text("继续有限批次") }
        }
        if (busy) TextButton(onClick = { pending?.second?.let(controller::cancelLearning); pending = null; error = "停止请求已记录；请刷新原水位，已有 draft/候选与未知用量不会被当作未发生。" }) { Text("停止本次提炼") }
        else if (snapshot?.optString("taskStatus") in setOf("preparing", "running", "stopping")) TextButton(onClick = { request("stopLearningJob", JSONObject().put("taskId", taskId)) }, enabled = pending == null) { Text("停止这个原任务的当前提炼") }
        if (snapshot?.optBoolean("canSkipBatch") == true && pending == null) TextButton(onClick = { skipConsent = true }) { Text("核对并跳过当前来源") }
        if (results != null && (0 until results.length()).any { results.getJSONObject(it).optJSONObject("candidateRef") != null }) TextButton(onClick = reviewCandidates) { Text("去审阅候选（不自动采用）") }
    }
    if (runConsent) AlertDialog(onDismissRequest = { runConsent = false }, title = { Text("继续这个原学习任务？") }, text = { Text("将核验所选原来源版本，并向当前模型发送本轮最多 3 份尚未处理的来源。已收到的 draft 只提交同一候选，不再发送；已完成批次不重提。每次新执行有新的原生预算，未知旧调用不退款。") }, confirmButton = { Button(onClick = {
        runConsent = false
        job?.let { request("runLearningJob", JSONObject().put("taskId", taskId).put("revision", it.getLong("revision")).put("confirmModelUse", true)) }
    }) { Text("发送并继续") } }, dismissButton = { TextButton(onClick = { runConsent = false }) { Text("暂不发送") } })
    if (skipConsent) AlertDialog(onDismissRequest = { skipConsent = false }, title = { Text("跳过当前来源，不重试") }, text = { Text("这不会声称旧模型请求未执行、不会退款，也不会生成该批候选。已收到 draft 的原回执保留；未知调用继续显示未知。其他来源仍需你明确继续。") }, confirmButton = { Button(onClick = {
        skipConsent = false
        job?.let { request("skipLearningJobBatch", JSONObject().put("taskId", taskId).put("revision", it.getLong("revision")).put("confirmUnknownUse", true)) }
    }) { Text("确认跳过并保留未知") } }, dismissButton = { TextButton(onClick = { skipConsent = false }) { Text("返回核对") } })
}
