package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID
import dev.pocketengine.features.remote.RemoteMessageTarget
import org.json.JSONArray
import org.json.JSONObject

/** Native UI review checks supplement the TaskStore dispatch fence, never grant authority. */
internal object RemoteSteerGuard {
    @JvmStatic fun target(task: JSONObject): JSONObject? {
        if (task.optString("feature") != "remote" || task.optString("actorId") != "owner" || task.optString("spaceId") != "private") return null
        val evidence = task.optJSONObject("evidence") ?: return null
        val refs = task.optJSONObject("externalRefs") ?: return null
        val inputTarget = try { RemoteMessageTarget.observed(evidence) } catch (_: Exception) { return null }
        if (RemoteMessageTarget.pi(inputTarget) && (task.has("teamRunId") || task.has("memberId") || task.optJSONObject("origin")?.optString("kind")?.startsWith("team") == true)) return null
        val result = JSONObject().put("taskId", task.optString("id")).put("runId", task.optString("runId"))
            .put("coordinatorEpoch", task.optInt("coordinatorEpoch", -1)).put("profileId", refs.optString("profileId"))
            .put("jobId", task.optString("externalId")).put("jobOperationId", task.optString("operationId"))
            .put("threadId", evidence.optString("threadId"))
        RemoteMessageTarget.copy(inputTarget, result)
        if (fields.any { result.optString(it).isBlank() } || result.optInt("coordinatorEpoch") < 0 ||
            result.optString("profileId") != evidence.optString("profileId") || result.optString("jobOperationId") != evidence.optString("operationId")) return null
        return result
    }
    private val fields = listOf("taskId", "runId", "profileId", "jobId", "jobOperationId", "threadId")
    @JvmStatic fun sameTarget(a: JSONObject?, b: JSONObject?): Boolean = try {
        a != null && b != null && fields.all { a.optString(it).isNotBlank() && a.optString(it) == b.optString(it) } &&
            a.optInt("coordinatorEpoch", -1) >= 0 && a.optInt("coordinatorEpoch") == b.optInt("coordinatorEpoch", -1) && RemoteMessageTarget.same(a, b)
    } catch (_: Exception) { false }
    @JvmStatic fun messages(task: JSONObject): List<JSONObject> = rows(task.optJSONArray("operations")).filter { it.optString("tool") == "remote_message" }
    @JvmStatic fun canSend(task: JSONObject, reviewed: JSONObject?): Boolean {
        val evidence = task.optJSONObject("evidence") ?: return false
        return sameTarget(reviewed, target(task)) && !task.optBoolean("cancelRequested") && !evidence.optBoolean("steeringUnknown") && task.optString("status") in setOf("running", "awaiting_user") &&
            evidence.optString("executorStatus") in setOf("running", "awaiting_approval") && evidence.optJSONObject("controls")?.optBoolean("inputSteering") == true &&
            messages(task).none { it.optString("state") != "message_receipt" }
    }
    @JvmStatic fun payload(task: JSONObject, reviewed: JSONObject, messageId: String, text: String): JSONObject {
        check(canSend(task, reviewed)) { "原turn或补充消息状态已变化，请核对后再输入" }
        require(messageId.matches(Regex("[A-Za-z0-9_-]{1,80}")) && text.isNotBlank() && text.length <= 12000 && !text.contains('\u0000')) { "补充内容需为1–12000字符" }
        return JSONObject(reviewed.toString()).put("messageId", messageId).put("text", text)
    }
    @JvmStatic fun preflightRejected(taskId: String, messageId: String?, hasJournal: Boolean, response: JSONObject?, responseInput: JSONObject?): Boolean =
        messageId != null && !hasJournal && responseInput != null && response != null && responseInput.optString("taskId") == taskId &&
            responseInput.optString("messageId") == messageId && response.optString("messageId") == messageId &&
            response.optString("receiptSource") == "native_preflight" && response.optString("status") == "rejected" && response.opt("dispatched") == false
    @JvmStatic fun statusLabel(status: String): String = when (status) {
        "accepted" -> "原turn已接收"
        "rejected" -> "未被接收"
        "queued" -> "已登记 · 等待发送回执"
        "sending" -> "发送中 · 尚未确认接收"
        else -> "接收结果未知 · 仅核对原消息"
    }
    @JvmStatic fun receiptLabel(receipt: JSONObject): String = if (RemoteMessageTarget.pi(receipt) && receipt.optString("status") == "accepted")
        "原Pi队列已接收 · 是否处理未知" else statusLabel(receipt.optString("status"))
    private fun rows(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

/** Leaf only: mounting, owner routing and bounded signal execution belong to the shared host. */
@Composable
internal fun RemoteSteerPanel(task: JSONObject, busy: Boolean, error: String?, response: JSONObject? = null, responseInput: JSONObject? = null, onRequest: (String, JSONObject) -> Unit) {
    if (task.optString("feature") != "remote") return
    val taskId = task.optString("id")
    val current = RemoteSteerGuard.target(task)
    val pi = task.optJSONObject("evidence")?.optString("protocol") == "pi_rpc_v1"
    val targetLabel = if (pi) "原Pi队列" else "原turn"
    var reviewed by remember(taskId) { mutableStateOf(current?.let { JSONObject(it.toString()) }) }
    var text by remember(taskId) { mutableStateOf("") }
    var submitted by remember(taskId) { mutableStateOf<String?>(null) }
    var localError by remember(taskId) { mutableStateOf<String?>(null) }
    var expanded by remember(taskId) { mutableStateOf(false) }
    val messages = RemoteSteerGuard.messages(task)
    val own = messages.firstOrNull { it.optString("operationId") == submitted }
    val ownStatus = own?.optJSONObject("receipt")?.optString("status")
    val preflightRejected = RemoteSteerGuard.preflightRejected(taskId, submitted, own != null, response, responseInput)
    LaunchedEffect(preflightRejected) {
        if (preflightRejected) { submitted = null; localError = response?.optString("message", "发送前检查未通过，请重新核对目标") }
    }
    LaunchedEffect(ownStatus, submitted) {
        if (ownStatus == "accepted") { text = ""; submitted = null; reviewed = current?.let { JSONObject(it.toString()) } }
    }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (pi) "补充给正在运行的 Pi" else "补充给正在运行的 Codex", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(if (pi) "只向这个无工具任务的原Pi队列追加。排队回执不证明模型已经处理；不会另开prompt，也不增加任务额度。" else "只向当前会话的原turn补充，不暂停、不恢复，也不新开任务。接收成功不等于任务完成。", style = MaterialTheme.typography.bodySmall)
        if (current != null) {
            Text("当前会话：${current.optString("threadId").take(36)}\n${if (pi) "原请求" else "当前turn"}：${current.optString(if (pi) "expectedPromptRequestId" else "expectedTurnId").take(36)}", style = MaterialTheme.typography.bodySmall)
            if (!RemoteSteerGuard.sameTarget(reviewed, current)) {
                Text("会话或执行代次已改变，草稿没有自动转发。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { reviewed = JSONObject(current.toString()); localError = null }, enabled = !busy && submitted == null) { Text("核对当前输入目标") }
            }
            OutlinedTextField(value = text, onValueChange = { if (it.length <= 12000) text = it }, label = { Text("给${targetLabel}的补充要求") },
                minLines = 2, maxLines = 5, enabled = submitted == null && !busy, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                try {
                    val messageId = UUID.randomUUID().toString()
                    val payload = RemoteSteerGuard.payload(task, reviewed ?: kotlin.error("请重新核对目标"), messageId, text)
                    submitted = messageId; localError = null; onRequest("steerJob", payload)
                } catch (failure: Exception) { localError = failure.message ?: "补充尚未发送" }
            }, enabled = !busy && submitted == null && text.isNotBlank() && RemoteSteerGuard.canSend(task, reviewed)) { Text("发送到${targetLabel}") }
            if (submitted != null && own == null) {
                Text("等待原生消息登记与回执；未确认前不会生成另一条补充。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onRequest("getSteerMessage", JSONObject().put("taskId", taskId).put("messageId", submitted)) }, enabled = !busy) { Text("核对本次消息") }
            }
            if (ownStatus == "rejected") TextButton(onClick = { submitted = null; reviewed = JSONObject(current.toString()); localError = null }, enabled = !busy) { Text("编辑后另发一条") }
        } else Text("尚无可补充的准确执行目标。发送情况请核对下方原消息回执；未知结果只查询，不重发。", style = MaterialTheme.typography.bodySmall)
        (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (messages.isNotEmpty()) {
            Text("消息回执（与任务结果独立）", style = MaterialTheme.typography.labelLarge)
            (if (expanded) messages else messages.takeLast(4)).asReversed().forEach { operation ->
                val receipt = operation.optJSONObject("receipt") ?: JSONObject()
                val messageId = operation.optString("operationId")
                Text(RemoteSteerGuard.receiptLabel(receipt) + " · " + messageId.takeLast(8), style = MaterialTheme.typography.bodySmall)
                if (receipt.optString("status") !in setOf("accepted", "rejected")) {
                    TextButton(onClick = { onRequest("getSteerMessage", JSONObject().put("taskId", taskId).put("messageId", messageId)) }, enabled = !busy) { Text("查询原消息 · 不重发") }
                }
            }
            if (messages.size > 4) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起历史回执" else "显示全部消息回执") }
        }
    }
}
