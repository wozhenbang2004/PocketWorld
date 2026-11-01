package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
internal fun RealtimeTranscripts(realtime: JSONObject, state: PocketUiState, controller: PocketWorldController) {
    val transcript = realtime.optJSONObject("transcription") ?: return
    val rows = transcript.optJSONArray("turns")
    val count = rows?.length() ?: 0
    val retained = transcript.optBoolean("retained")
    val sessionId = realtime.optString("sessionId")
    val taskHost = realtime.optJSONObject("capabilities")?.optBoolean("voiceTaskHost") == true
    var expanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var dismissedReview by remember { mutableStateOf("") }
    val review = state.moduleResults["realtime.reviewTurn"]
    val currentReview = review != null && review.optString("reviewId").isNotBlank() && dismissedReview != review.optString("reviewId") && review.optString("sessionId") == sessionId
    if (transcript.optBoolean("active") || count > 0) {
        HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 12.dp))
        Text(if (retained) "本次保留的转录" else "本次通话字幕", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (retained) Text(realtime.optJSONObject("session")?.optString("message").orEmpty().ifBlank { realtimeLabel(realtime) }, fontSize = 12.sp, lineHeight = 19.sp, color = SecondaryInk, modifier = Modifier.padding(top = 6.dp))
        Text(if (retained) "最终文字仍可重新复核并交给原任务；未完整片段只供查看。未保存内容只暂存到下一次通话或重新启动应用。" else "字幕由配置的服务提供。未提交内容只暂存；草稿和远端说话不会自动创建任务。", fontSize = 12.sp, lineHeight = 19.sp, color = SecondaryInk, modifier = Modifier.padding(vertical = 8.dp))
        if (retained && !transcript.optBoolean("reviewReady")) Text("正在等待本次通话收尾，保留文字暂不能提交。", fontSize = 12.sp, color = SecondaryInk)
        if (count == 0) Text(if (realtime.optJSONObject("session")?.optString("transport") == "openai_realtime_ga_websocket" && realtime.optJSONObject("session")?.optBoolean("inputTranscriptionConfigured") != true) "没有已确认的输入转录能力。请结束通话后在语音设置中填写服务支持的输入转录模型，或改用文字目标。" else "尚未收到最终转录。服务连接不代表已经识别出可执行目标。", fontSize = 12.sp, color = SecondaryInk)
        if (count > 4) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "只看最近字幕" else "查看全部 $count 段字幕", fontSize = 12.sp) }
        for (i in (if (expanded) 0 else (count - 4).coerceAtLeast(0)) until count) {
            val turn = rows?.optJSONObject(i) ?: continue
            key(turn.optString("id")) {
                val local = turn.optString("sourceKind") == "reported_local_microphone"
                val commitState = turn.optString("commitState")
                Text(if (local) "本机麦克风转录（服务标记）" else "远端或来源未核验", fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.padding(top = 12.dp))
                Text(turn.optString("text").ifEmpty { if (turn.optString("phase") == "incomplete") "这一段未完整接收" else "正在接收…" }, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 4.dp))
                Text(realtimeTurnLabel(turn), fontSize = 11.sp, color = SecondaryInk, modifier = Modifier.padding(top = 4.dp))
                if (turn.optBoolean("canReview")) TextButton(onClick = { controller.requestRealtime("reviewTurn", JSONObject().put("sessionId", sessionId).put("turnId", turn.getString("id")).put("revision", turn.getLong("revision"))) }, enabled = "realtime.reviewTurn" !in state.moduleBusy) { Text(if (taskHost) "复核目标，交给助手处理" else "复核这段，保存为待执行任务", fontSize = 12.sp) }
                if (commitState == "unknown") OutlinedButton(onClick = { controller.requestRealtime("reconcileTurn", JSONObject().put("commitId", turn.getString("commitId"))) }, enabled = "realtime.reconcileTurn" !in state.moduleBusy) { Text("核对原提交，不会重建", fontSize = 12.sp) }
                if (turn.optString("taskId").isNotEmpty()) TextButton(onClick = { controller.focusTask(turn.getString("taskId")) }) { Text("打开原任务", fontSize = 12.sp) }
                if (turn.optBoolean("postCommitUpdate")) Text("这段字幕后来又有修订；已确认任务不会被自动修改。", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        val notice = transcript.optString("notice")
        if (notice.isNotBlank()) Text(notice, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        if (!transcript.optBoolean("commitAvailable")) Text("任务存储尚未接入；字幕只能查看，不能假装已保存。", fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.padding(top = 8.dp))
    }
    transcript.optJSONObject("lastTask")?.let { task ->
        if (task.optString("id").isNotEmpty() && (!transcript.optBoolean("active") || count == 0)) {
            Text("语音目标已存入原任务；没有自动执行。通话结束不影响它。", fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { controller.focusTask(task.getString("id")) }) { Text("打开原任务") }
        }
    }
    if (currentReview) {
        val current = realtimeReviewCurrent(realtime, review)
        AlertDialog(onDismissRequest = { dismissedReview = review.optString("reviewId") },
            title = { Text("确认这就是你的任务目标", fontSize = 17.sp) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(review.optString("text"), fontSize = 15.sp, lineHeight = 23.sp)
                review.optJSONObject("source")?.let { source -> Text("服务标记来源：${source.optString("participantIdentity")}\n麦克风轨道：${source.optString("trackSid")}", fontSize = 12.sp, color = SecondaryInk) }
                Text("转录来源标记不是说话人认证。请逐字核对；不准确请取消并改用文字输入。", fontSize = 12.sp, color = SecondaryInk)
                Text(if (taskHost) "确认后将通过原任务开始处理。需要发送、权限或敏感操作时，仍在原任务中由你核对确认。" else "确认只会保存一项待执行任务。仍需打开原任务，由你明确继续；不会发送消息、运行工具或启动手机操作。", fontSize = 12.sp, lineHeight = 19.sp, color = SecondaryInk)
                if (taskHost) TextButton(onClick = {
                    dismissedReview = review.optString("reviewId")
                    controller.requestRealtime("commitTurn", JSONObject().put("sessionId", sessionId).put("reviewId", review.getString("reviewId")).put("contentHash", review.getString("contentHash")))
                }, enabled = current && "realtime.commitTurn" !in state.moduleBusy) { Text("只保存目标，暂不开始") }
                if (!current) Text("字幕、会话或最终版本已变化，请取消并重新复核。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            } },
            confirmButton = { Button(onClick = {
                dismissedReview = review.optString("reviewId")
                controller.requestRealtime(if (taskHost) "commitVoiceTask" else "commitTurn", JSONObject().put("sessionId", sessionId).put("reviewId", review.getString("reviewId")).put("contentHash", review.getString("contentHash")))
            }, enabled = current && "realtime.commitTurn" !in state.moduleBusy && "realtime.commitVoiceTask" !in state.moduleBusy) { Text(if (taskHost) "确认目标并开始" else "确认并保存任务") } },
            dismissButton = { TextButton(onClick = { dismissedReview = review.optString("reviewId") }) { Text("取消") } })
    }
}

internal fun realtimeReviewCurrent(realtime: JSONObject, review: JSONObject): Boolean {
    if (review.optString("sessionId") != realtime.optString("sessionId") || review.optLong("expiresAt") <= System.currentTimeMillis()) return false
    val transcript = realtime.optJSONObject("transcription") ?: return false
    if ((!transcript.optBoolean("active") && !transcript.optBoolean("retained")) || !transcript.optBoolean("commitAvailable")) return false
    if (transcript.has("reviewReady") && !transcript.optBoolean("reviewReady")) return false
    val rows = transcript.optJSONArray("turns") ?: return false
    for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        if (row.optString("id") == review.optString("turnId")) return row.optBoolean("canReview") && row.optBoolean("isFinal") && row.optLong("revision") == review.optLong("revision") && row.optString("text") == review.optString("text")
    }
    return false
}

internal fun realtimeTurnLabel(turn: JSONObject): String = when (turn.optString("commitState")) {
    "committing" -> "正在保存已确认的任务，不是执行任务"
    "committed" -> "已保存到任务"
    "unknown" -> "保存结果未确认，请只核对原提交"
    else -> when {
        turn.optString("phase") == "incomplete" -> "未完整接收，不能提交"
        !turn.optBoolean("isFinal") -> "临时转录，不会执行"
        turn.optString("sourceKind") != "reported_local_microphone" -> "远端最终字幕，只供查看"
        else -> "最终转录，仍需你明确复核"
    }
}
