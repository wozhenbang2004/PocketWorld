package dev.pocketengine.ui.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Native weak-source manual fallback; never presents a label as an authenticated group identity. */
@Composable
fun ReviewedMaterialPanel(responses: Map<String, JSONObject>, onRequest: (String, JSONObject) -> String, onBack: () -> Unit) {
    var source by remember { mutableStateOf("") }; var recipient by remember { mutableStateOf("") }
    var material by remember { mutableStateOf("") }; var goal by remember { mutableStateOf("") }
    var calls by remember { mutableStateOf("2") }; var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    var review by remember { mutableStateOf<JSONObject?>(null) }; var task by remember { mutableStateOf<JSONObject?>(null) }
    var resultReview by remember { mutableStateOf<JSONObject?>(null) }; var manualDraft by remember { mutableStateOf<JSONObject?>(null) }
    var tasks by remember { mutableStateOf<List<JSONObject>>(emptyList()) }; var notice by remember { mutableStateOf("") }
    var firstConsent by remember { mutableStateOf(false) }; var returnConsent by remember { mutableStateOf(false) }
    fun request(command: String, value: JSONObject = JSONObject()) {
        if (pending != null) return
        try { pending = onRequest(command, value) to command; notice = "" }
        catch (error: Exception) { notice = error.message ?: "本次操作未完成" }
    }
    BackHandler { onBack() }
    LaunchedEffect(Unit) { request("material.list") }
    LaunchedEffect(responses, pending) {
        val active = pending ?: return@LaunchedEffect
        val envelope = GroupUiState.envelope(active.first, responses[active.first]) ?: return@LaunchedEffect
        pending = null
        if (!envelope.optBoolean("ok")) { notice = envelope.optString("message", "操作未完成，材料与原记录保留"); return@LaunchedEffect }
        val actual = envelope.optJSONObject("result") ?: return@LaunchedEffect
        when (active.second) {
            "material.list" -> tasks = GroupUiState.rows(actual.optJSONArray("tasks"))
            "material.review" -> { review = JSONObject(actual.toString()); firstConsent = false }
            "material.approve", "material.inspect", "material.cancel" -> { task = JSONObject(actual.toString()); if (active.second == "material.approve") { review = null; notice = "仅原材料任务已登记；未向任何平台或群发送消息" } }
            "material.return.review" -> { resultReview = JSONObject(actual.toString()); returnConsent = false; manualDraft = null }
            "material.return.confirm" -> { manualDraft = JSONObject(actual.toString()); notice = "人工草稿已写入原待发送列表；没有自动发送或送达证明" }
            "material.return.copy" -> { check(actual.optBoolean("copiedLocally") && !actual.optBoolean("deliveryConfirmed")); notice = "已复制到本机剪贴板，请自行核对接收方并人工交还；未确认投递" }
        }
    }
    val taskId = task?.optString("id")
    LaunchedEffect(taskId) {
        // Page-local bounded read loop only. Disposal cancels it; it never starts/resumes a task.
        if (!taskId.isNullOrBlank()) repeat(120) {
            delay(1000)
            if (task?.optString("id") != taskId || task?.optString("status") !in setOf("preparing", "running", "stopping")) return@LaunchedEffect
            if (pending == null) request("material.inspect", JSONObject().put("taskId", taskId))
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("审阅外部材料", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }
        Text("仅供主人逐次审阅材料、取得本机结果后人工交还。这不是普通微信群接入；来源与接收方只是人工标签，不能证明平台身份。", style = MaterialTheme.typography.bodyLarge)
        if (pending != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (notice.isNotEmpty()) Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        when {
            resultReview != null -> {
                val value = resultReview!!
                Text("第二次确认：人工交还", style = MaterialTheme.typography.titleMedium)
                Text("接收方标签（未认证）：${value.optString("recipientLabel")}\n来源标签：${value.optString("sourceLabel")}", style = MaterialTheme.typography.bodyLarge)
                Text(value.getString("text"), style = MaterialTheme.typography.bodyLarge)
                Text("原任务 ${value.optString("taskId")}\n原执行 ${value.optString("runId")}", style = MaterialTheme.typography.bodySmall)
                if (manualDraft == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(returnConsent, { returnConsent = it }); Text("我已核对完整内容和人工接收方，仅保存草稿，不授权自动发送", Modifier.weight(1f)) }
                    Button(onClick = { request("material.return.confirm", JSONObject().put("digest", value.getString("digest"))) }, enabled = returnConsent && pending == null) { Text("确认保存人工交还草稿") }
                } else Button(onClick = { request("material.return.copy", JSONObject().put("digest", value.getString("digest"))) }, enabled = pending == null) { Text("复制已确认草稿供人工回发") }
                TextButton(onClick = { resultReview = null; manualDraft = null }) { Text("回原任务") }
            }
            review != null -> {
                val value = review!!
                Text("本次处理范围", style = MaterialTheme.typography.titleMedium)
                Text("来源标签（未认证）：${value.optString("sourceLabel")}\n人工接收方标签：${value.optString("recipientLabel")}", style = MaterialTheme.typography.bodyLarge)
                Text("目标：${value.optString("goal")}\n\n完整材料：\n${value.optString("material")}", style = MaterialTheme.typography.bodyLarge)
                val model = value.getJSONObject("nativeModel")
                Text("API模型：${model.optString("provider")} / ${model.optString("model")}\n实际模型地址：${model.optString("baseUrl")}\n最多 ${value.getInt("maxModelRequests")} 次物理模型请求；每次输出上限 ${value.getInt("maxOutputTokens")} tokens。实际费用未知、未硬限；未知调用仍计额度。工具 0，不读取私人记忆、手机、账号或本地文件。", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(firstConsent, { firstConsent = it }); Text("我已核对以上完整材料、外部受众与API预算，仅批准本次处理", Modifier.weight(1f)) }
                Button(onClick = { request("material.approve", JSONObject().put("digest", value.getString("digest"))) }, enabled = firstConsent && pending == null) { Text("批准本次原任务") }
                TextButton(onClick = { review = null }) { Text("返回修改") }
            }
            task != null -> {
                val value = task!!
                Text(GroupUiState.taskLabel(value.optString("status")), style = MaterialTheme.typography.titleMedium)
                Text(value.optString("summary"), style = MaterialTheme.typography.bodyLarge)
                Text("任务 ${value.optString("id")}\n执行 ${value.optString("runId")}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { request("material.inspect", JSONObject().put("taskId", value.getString("id"))) }, enabled = pending == null) { Text("只读刷新原任务") }
                if (value.optString("status") == "completed") Button(onClick = { request("material.return.review", JSONObject().put("taskId", value.getString("id"))) }, enabled = pending == null) { Text("核对完整结果与人工接收方") }
                if (value.optString("status") in setOf("preparing", "running", "stopping")) OutlinedButton(onClick = { request("material.cancel", JSONObject().put("taskId", value.getString("id")).put("runId", value.getString("runId")).put("epoch", value.getInt("coordinatorEpoch"))) }, enabled = pending == null) { Text("停止原执行") }
                Text("离开前台会撤销本页的继续派发；旧任务不会自动复活或重新计费。中断记录可只读核对。", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { task = null; request("material.list") }) { Text("回材料列表") }
            }
            else -> {
                OutlinedTextField(source, { source = it; review = null }, label = { Text("材料来源标签（不作为群ID）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(recipient, { recipient = it; review = null }, label = { Text("人工交还对象标签") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(material, { material = it; review = null }, label = { Text("显选完整文字材料（最多12000字）") }, minLines = 5, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(goal, { goal = it; review = null }, label = { Text("本次明确目标（最多4000字）") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(calls, { calls = it }, label = { Text("本次物理模型请求额度（1—8）") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { request("material.review", JSONObject().put("sourceLabel", source).put("recipientLabel", recipient).put("material", material).put("goal", goal).put("maxModelRequests", calls.toIntOrNull() ?: 0)) }, enabled = pending == null) { Text("先核对材料、受众与预算") }
                HorizontalDivider();Text("已登记的人工材料任务", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { request("material.list") }, enabled = pending == null) { Text("只读刷新列表") }
                tasks.forEach { row -> OutlinedButton(onClick = { request("material.inspect", JSONObject().put("taskId", row.getString("id"))) }, enabled = pending == null, modifier = Modifier.fillMaxWidth()) { Text("${GroupUiState.taskLabel(row.optString("status"))} · ${row.optString("goal")}") } }
            }
        }
    }
}
