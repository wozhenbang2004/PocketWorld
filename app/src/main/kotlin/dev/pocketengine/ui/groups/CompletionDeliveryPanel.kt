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
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Explicit private-owner destination picker; a completed model label is never a delivery fact. */
@Composable
fun CompletionDeliveryPanel(responses: Map<String, JSONObject>, onRequest: (String, JSONObject) -> String, onBack: () -> Unit) {
    var data by remember { mutableStateOf(JSONObject()) }; var taskId by remember { mutableStateOf<String?>(null) }
    var eventId by remember { mutableStateOf<String?>(null) }; var reviewed by remember { mutableStateOf<JSONObject?>(null) }
    var digest by remember { mutableStateOf("") }; var delivery by remember { mutableStateOf<JSONObject?>(null) }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }; var notice by remember { mutableStateOf("") }
    var associationPending by remember { mutableStateOf(false) }; var inspectedFactId by remember { mutableStateOf<String?>(null) }
    var consent by remember { mutableStateOf(false) }; var details by remember { mutableStateOf(false) }
    fun request(command: String, value: JSONObject = JSONObject()) {
        if (pending != null) return
        try { pending = onRequest(command, value) to command; notice = "" }
        catch (error: Exception) { notice = error.message ?: "这次操作未完成，原记录保留" }
    }
    BackHandler { onBack() }
    LaunchedEffect(Unit) { request("completion.list") }
    LaunchedEffect(responses, pending) {
        val current = pending ?: return@LaunchedEffect
        val envelope = GroupUiState.envelope(current.first, responses[current.first]) ?: return@LaunchedEffect
        pending = null
        if (!envelope.optBoolean("ok")) { notice = envelope.optString("message", "原结果或收件目标已变化，请重新核对"); return@LaunchedEffect }
        val value = envelope.optJSONObject("result") ?: return@LaunchedEffect
        when (current.second) {
            "completion.list" -> data = JSONObject(value.toString())
            "completion.review" -> { reviewed = JSONObject(value.getJSONObject("review").toString()); digest = value.getString("digest"); delivery = value.optJSONObject("existing"); consent = false }
            "completion.confirm" -> { delivery = JSONObject(value.toString()); notice = "单次准确投递已登记原待发送列表，尚未自动发送" }
            "completion.send" -> { delivery = JSONObject(value.toString()); notice = if (value.optString("status") == "sent") "平台已接受，不代表接收方已读或业务独立验收" else "原投递没有确认成功；保留编号，只核对，不自动另发" }
            "completion.recoverLink" -> { delivery = value.optJSONObject("delivery"); associationPending = false; notice = "已核对原耐久outbox并补齐原SQL关联，没有发送或重跑业务" }
            "completion.inspect" -> { inspectedFactId = value.optJSONObject("fact")?.optString("factId"); associationPending = value.optBoolean("associationPending"); delivery = value.optJSONObject("delivery"); notice = if (delivery == null) "原事实尚未关联出站记录；未创建或发送" else "只读取原投递，不会重跑任务或补发" }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("回传已核对的任务结果", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }
        Text("主人明确选择这份任务结果和当前已验证的主人私聊。不会用“已完成”文案代替原核验事实，也不会自动附文件、改成群发送或重新执行任务。", style = MaterialTheme.typography.bodyLarge)
        if (pending != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        val scope = reviewed
        if (scope == null) {
            Text("1. 选择原任务", style = MaterialTheme.typography.titleMedium)
            Text("最近40条原任务中的已完成候选；是否有本轮有效主人确认或实际文件核验，在下一步重新查原SQL。", style = MaterialTheme.typography.bodyMedium)
            GroupUiState.rows(data.optJSONArray("tasks")).forEach { task ->
                FilterChip(selected = taskId == task.optString("id"), onClick = { taskId = task.getString("id") }, label = { Text(task.optString("goal")) })
            }
            Text("2. 选择原认证主人私聊", style = MaterialTheme.typography.titleMedium)
            Text("只列原记录中当前仍有效的主人绑定；缺消息或被动回复窗口过期不会自动换账号、换新事件或发往群。", style = MaterialTheme.typography.bodyMedium)
            GroupUiState.rows(data.optJSONArray("targets")).forEach { target ->
                FilterChip(selected = eventId == target.optString("eventId"), onClick = { eventId = target.getString("eventId") }, label = { Text("${target.optString("provider")} · ${target.optString("profileName")}\n${target.optString("senderId")} · ${target.optString("conversationKind")}") })
            }
            Button(onClick = { request("completion.review", JSONObject().put("taskId", taskId).put("eventId", eventId)) }, enabled = pending == null && taskId != null && eventId != null) { Text("核对准确文字与接收方") }
            TextButton(onClick = { request("completion.list") }, enabled = pending == null) { Text("只读刷新候选") }
            if (delivery != null) Text("刚读取原投递：${completionStatus(delivery!!.optString("status"))}\n${delivery!!.optString("id")}", style = MaterialTheme.typography.bodyMedium)
            if (associationPending && inspectedFactId != null) OutlinedButton(onClick = { request("completion.recoverLink", JSONObject().put("factId", inspectedFactId)) }, enabled = pending == null) { Text("核对原出站记录并补齐关联（不发送）") }
            HorizontalDivider(); Text("已登记的原投递", style = MaterialTheme.typography.titleMedium)
            GroupUiState.rows(data.optJSONArray("deliveries")).forEach { old ->
                val proof = old.getJSONObject("nativeCompletionResult")
                OutlinedButton(onClick = { request("completion.inspect", JSONObject().put("factId", proof.getString("factId"))) }, enabled = pending == null, modifier = Modifier.fillMaxWidth()) { Text("${completionStatus(old.optString("status"))} · ${proof.getJSONObject("target").optString("senderId")}\n${old.optString("id")}") }
            }
        } else {
            val target = scope.getJSONObject("target")
            Text("准确接收方", style = MaterialTheme.typography.titleMedium)
            Text("${target.getString("provider")} · ${target.optString("profileName")}\n账号/Bot：${target.optString("appId")}\n主人平台ID：${target.getString("senderId")}\n场景：${target.getString("conversationKind")}", style = MaterialTheme.typography.bodyLarge)
            Text("将发送的完整文字", style = MaterialTheme.typography.titleMedium)
            Text(scope.getString("text"), style = MaterialTheme.typography.bodyLarge)
            Text("附件字节：0。本次仅发送以上文字；原核验范围不代表独立业务成功或全部文件内容已读。", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { details = !details }) { Text(if (details) "收起来源与版本" else "查看来源与版本") }
            if (details) Text("原任务 ${scope.getString("taskId")}\n执行 ${scope.getString("runId")} / ${scope.getInt("epoch")}\n事实 ${scope.getString("factId")}\n内容SHA ${scope.getString("payloadSha256")}\n目标SHA ${scope.getString("targetSha256")}\n连接代次 ${target.getString("transportEpoch")}\n绑定代次 ${target.getString("bindingEpoch")}\n核验依据 ${scope.getJSONObject("completionBasis").getString("kind")}", fontSize = 13.sp)
            if (delivery == null) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }); Text("同意仅向这个已验证主人私聊发送以上准确文字一次，先保存原出站记录，不扩大到附件或其他受众", Modifier.weight(1f)) }
                Button(onClick = { request("completion.confirm", JSONObject().put("digest", digest)) }, enabled = pending == null && consent) { Text("确认这一次投递并登记") }
            }
            delivery?.let { value ->
                Text("原投递：${completionStatus(value.optString("status"))}\n${value.optString("id")}", style = MaterialTheme.typography.bodyMedium)
                if (value.optString("status") == "queued") Button(onClick = { request("completion.send", JSONObject().put("digest", digest)) }, enabled = pending == null) { Text("发送这条已确认的原记录") }
                TextButton(onClick = { request("completion.inspect", JSONObject().put("factId", scope.getString("factId"))) }, enabled = pending == null) { Text("只读核对原投递") }
                if (value.optString("status") == "unknown") Text("结果未知不表示未发生。不会创建第二条发送，也不会重跑原任务。", style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(onClick = { reviewed = null; delivery = null; consent = false; request("completion.list") }, enabled = pending == null) { Text("返回原候选列表") }
        }
    }
}
private fun completionStatus(status: String): String = when (status) {
    "queued" -> "已登记，尚未发送"
    "sending" -> "正在投递"
    "sent" -> "平台已接受，非已读证明"
    "unknown" -> "投递结果未知，只查原编号"
    "failed" -> "平台拒绝或投递失败"
    else -> "原状态：$status"
}
