package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Mount outside the collapsible call group so the original task remains visible after hangup. */
@Composable
internal fun VoiceTaskControls(realtime: JSONObject, onRequest: (String, JSONObject) -> Unit, onOpenTask: (String) -> Unit) {
    val view = realtime.optJSONObject("voiceTask") ?: return
    if (!view.optBoolean("available")) return
    val task = view.optJSONObject("task")
    val id = task?.optString("taskId").orEmpty().ifBlank { view.optString("lastTaskId") }
    val recent = view.optJSONArray("recentTasks")
    var recoveryOpen by remember { mutableStateOf(false) }
    TextButton(onClick = { recoveryOpen = !recoveryOpen;if (recoveryOpen) onRequest("listVoiceTasks", JSONObject()) }) { Text(if (recoveryOpen) "收起已保存任务" else "找回已保存的语音任务") }
    if (recoveryOpen && view.optString("recoveryMessage").isNotBlank()) Text(view.optString("recoveryMessage"), fontSize = 12.sp, lineHeight = 20.sp, color = SecondaryInk)
    repeat(if (recoveryOpen) recent?.length() ?: 0 else 0) { index ->
        val saved = recent?.optJSONObject(index) ?: return@repeat
        TextButton(onClick = { onRequest("recoverVoiceTask", JSONObject().put("taskId", saved.optString("taskId"))) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) { Text(saved.optString("goal"), maxLines = 2); Text(voiceTaskStatus(saved.optString("status")), fontSize = 12.sp, color = SecondaryInk) }
        }
    }
    if (id.isBlank() && view.optString("message").isBlank()) return
    val binding = view.optString("bindingId")
    val version = view.optString("viewToken")
    val cancelToken = view.optString("cancelToken")
    val reading = view.optJSONObject("readout") ?: JSONObject()
    val session = realtime.optJSONObject("session") ?: JSONObject()
    val callBusy = realtime.optBoolean("accepting") || session.optString("state") in setOf("connecting", "connected", "reconnecting", "disconnecting") || realtime.optString("invitationState") == "awaiting_owner" || realtime.optJSONObject("voiceTurn")?.optBoolean("active") == true
    var cancelReview by remember(binding, cancelToken) { mutableStateOf(false) }
    fun payload() = JSONObject().put("bindingId", binding).put("viewToken", version)
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 12.dp))
    Text("这次语音的原任务", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    if (view.optString("goal").isNotBlank()) Text(view.optString("goal"), fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
    Text(view.optString("message"), fontSize = 12.sp, lineHeight = 20.sp, color = SecondaryInk, modifier = Modifier.padding(vertical = 8.dp))
    if (task != null) {
        Text(voiceTaskStatus(task.optString("status")), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (view.optBoolean("needsRefresh")) Text("以下为上次取得的记录，请刷新核对。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        if (task.optString("summary").isNotBlank()) SelectionContainer { Text(task.optString("summary"), fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 8.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (view.optBoolean("canStart")) Button(onClick = { onRequest("startVoiceTask", payload()) }) { Text("开始处理原任务") }
            TextButton(onClick = { onOpenTask(id) }) { Text(if (task.optBoolean("requiresNativeApproval") || task.optString("status") == "needs_confirmation") "打开原任务，核对确认" else "查看原任务与产物") }
        }
    } else if (id.isNotBlank()) TextButton(onClick = { onOpenTask(id) }) { Text("打开已保存的原任务") }
    if (binding.isNotBlank()) {
        TextButton(onClick = { onRequest("inspectVoiceTask", payload()) }, enabled = !view.optBoolean("busy")) { Text("核对原任务状态") }
        if (reading.optBoolean("active")) OutlinedButton(onClick = { onRequest("interruptOutput", JSONObject().put("readoutId", reading.optString("readoutId"))) }, enabled = reading.optString("state") != "stopping") { Text("停止说话，任务继续") }
        else if (task?.optString("summary")?.isNotBlank() == true) {
            OutlinedButton(onClick = { onRequest("readVoiceTask", payload()) }, enabled = !callBusy && !realtime.optBoolean("audioLeaseHeld") && !view.optBoolean("busy") && !view.optBoolean("needsRefresh") && !reading.optBoolean("resourceReleaseUnknown")) { Text("朗读原任务记录") }
            if (callBusy) Text("结束当前语音并释放音频后，可用系统朗读原任务记录。", fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.padding(top = 4.dp))
        }
        if (reading.optString("message").isNotBlank()) Text(reading.optString("message"), fontSize = 12.sp, color = if (reading.optBoolean("resourceReleaseUnknown")) MaterialTheme.colorScheme.error else SecondaryInk, modifier = Modifier.padding(top = 6.dp))
        if (view.optBoolean("canCancel")) TextButton(onClick = { cancelReview = true }) { Text("取消这项任务") }
    }
    Text("敏感操作在原任务中确认。结束通话或停止说话不会取消任务；任务记录和实际产物仍需你核验。", fontSize = 12.sp, lineHeight = 20.sp, color = SecondaryInk, modifier = Modifier.padding(top = 8.dp))
    if (cancelReview) AlertDialog(onDismissRequest = { cancelReview = false }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("取消当前这一轮任务？") }, text = { Text("将请求停止这项目标的当前执行：\n${view.optString("goal")}\n已经发送或完成的操作不会撤销；结果未知时仍需核对原任务。") }, confirmButton = { Button(onClick = { cancelReview = false;onRequest("cancelVoiceTask", JSONObject().put("bindingId", binding).put("viewToken", cancelToken)) }) { Text("取消当前执行") } }, dismissButton = { TextButton(onClick = { cancelReview = false }) { Text("继续保留") } })
}

internal fun voiceTaskStatus(status: String) = when (status) {
    "preparing", "queued" -> "原任务正在准备"
    "running" -> "原任务正在处理"
    "paused" -> "原任务已暂停"
    "stopping" -> "原任务正在停止，结果仍待核对"
    "awaiting_user" -> "原任务等待主人继续"
    "needs_confirmation" -> "原任务等待本机确认"
    "completed" -> "原任务记录为完成，等待你验收"
    "failed", "interrupted" -> "原任务未完成，没有自动重跑"
    "cancelled" -> "原任务已记录取消"
    "reconciling" -> "原任务仍在核对操作结果"
    else -> "请核对原任务记录"
}
