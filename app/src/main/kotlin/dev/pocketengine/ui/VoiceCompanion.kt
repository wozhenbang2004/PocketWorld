package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Phone-only turn taking, deliberately separate from a remote room or continuous native realtime. */
@Composable
internal fun VoiceCompanion(realtime: JSONObject, state: PocketUiState, controller: PocketWorldController, roomBusy: Boolean) {
    val turn = realtime.optJSONObject("voiceTurn") ?: JSONObject()
    val active = turn.optBoolean("active")
    Text("手机语音伙伴 · 单轮", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Text("系统识别和朗读可能联网。开始后，最终识别文字会发给开始时选定的文字模型，再朗读回复；不向模型发送原始声音或画面。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
    Text(voiceTurnLabel(turn), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val recognized = turn.optString("recognizedText")
    val reply = turn.optString("replyText")
    if (recognized.isNotBlank()) {
        Text("这一轮识别", color = SecondaryInk, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        SelectionContainer { Text(recognized, fontSize = 14.sp, lineHeight = 22.sp) }
    }
    if (reply.isNotBlank()) {
        Text("模型回复", color = SecondaryInk, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        SelectionContainer { Text(reply, fontSize = 14.sp, lineHeight = 22.sp) }
    }
    NativeInferenceReference(turn.optJSONObject("inferenceReference"), controller, "查看这一轮模型用量")
    val message = turn.optString("message")
    if (message.isNotBlank()) Text(message, color = if (turn.optString("status") == "failed") MaterialTheme.colorScheme.error else SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
    if (active) {
        OutlinedButton(onClick = { controller.requestRealtime("stopVoiceTurn", JSONObject().put("voiceTurnId", turn.optString("id"))) }, enabled = turn.optString("status") != "stopping" && "realtime.stopVoiceTurn" !in state.moduleBusy) { Text(if (turn.optString("status") == "stopping") "正在停止这一轮" else "停止这一轮") }
    } else {
        if (!realtime.optBoolean("microphonePermission")) TextButton(onClick = { controller.requestRealtime("requestMicrophonePermission") }, enabled = "realtime.requestMicrophonePermission" !in state.moduleBusy) { Text("允许麦克风（不会自动开始）") }
        Button(onClick = { controller.requestRealtime("startVoiceTurn") }, enabled = voiceTurnCanStart(realtime, roomBusy) && "realtime.startVoiceTurn" !in state.moduleBusy, modifier = Modifier.padding(top = 4.dp)) { Text("开始一轮 · 识别后发送模型") }
        if (roomBusy) Text("请先处理房间邀请或挂断通话，再开始手机语音。", color = SecondaryInk, fontSize = 12.sp)
        if (!turn.optBoolean("portReady")) Text("纯对话模型入口尚未接入。", color = SecondaryInk, fontSize = 12.sp)
    }
    Text("每轮结束后不会自动再听。这是轮流对话，不是全双工；没有任务执行或工具权限；仅保留实际模型用量记录。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
    state.moduleErrors.filterKeys { it in setOf("realtime.startVoiceTurn", "realtime.stopVoiceTurn") }.values.forEach { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
}

internal fun voiceTurnCanStart(realtime: JSONObject, roomBusy: Boolean): Boolean {
    val turn = realtime.optJSONObject("voiceTurn") ?: return false
    return !roomBusy && !turn.optBoolean("active") && !turn.optBoolean("resourceReleaseUnknown") && !realtime.optBoolean("audioLeaseHeld") && realtime.optBoolean("foregroundOwner") && realtime.optBoolean("captureCoordinatorReady") && realtime.optBoolean("microphonePermission") && turn.optBoolean("portReady") && !(realtime.optJSONObject("session")?.optBoolean("resourceReleaseUnknown") ?: false)
}

internal fun voiceTurnLabel(turn: JSONObject): String = when (turn.optString("status", "idle")) {
    "acquiring" -> "正在准备这一轮"
    "listening" -> "正在听这一句"
    "thinking" -> "正在等待模型回复"
    "speaking" -> "正在朗读回复"
    "stopping" -> "正在停止并释放音频"
    "complete" -> "这一轮已结束 · 麦克风已停止"
    "cancelled" -> "这一轮已停止"
    "failed" -> "这一轮未完成"
    else -> "尚未开始，不在录音"
}
