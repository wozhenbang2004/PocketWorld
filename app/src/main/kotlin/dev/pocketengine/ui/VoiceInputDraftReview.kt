package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Final text is editable data. Neither this editor nor recognition sends or authorizes a task. */
@Composable
internal fun VoiceInputDraftReview(input: JSONObject, currentTarget: Boolean, onUse: (String, String) -> Unit, onDiscard: (String) -> Unit) {
    if (!input.optBoolean("reviewAvailable")) return
    val id = input.optString("inputId")
    if (!currentTarget) {
        Text("这份语音草稿属于原输入。返回原对话后再核对，或明确丢弃；没有放入当前对话。", fontSize = 12.sp, lineHeight = 19.sp, color = SecondaryInk)
        TextButton(onClick = { onDiscard(id) }) { Text("丢弃这份语音草稿") }
        return
    }
    var edited by remember(id) { mutableStateOf(input.optString("finalText")) }
    val target = input.optJSONObject("target") ?: JSONObject()
    AlertDialog(onDismissRequest = { onDiscard(id) }, containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (target.optBoolean("phoneInput")) "核对手机办事草稿" else "核对原对话草稿") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("来自你选择的系统听写，可能使用网络；中文支持和实际识别效果由设备服务决定。", fontSize = 12.sp, lineHeight = 20.sp, color = SecondaryInk)
            OutlinedTextField(edited, { if (it.length <= 4000) edited = it }, label = { Text("最终文字，可修改") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
            Text("逐字核对姓名、数字和目标。放入原输入只保留可编辑文字，仍需你按原入口发送或开始；识别结果不是执行授权。", fontSize = 12.sp, lineHeight = 20.sp, color = SecondaryInk)
        } },
        confirmButton = { Button(onClick = { onUse(id, edited) }, enabled = edited.isNotBlank() && !input.optBoolean("resourceReleaseUnknown")) { Text("放入原输入，暂不发送") } },
        dismissButton = { TextButton(onClick = { onDiscard(id) }) { Text("丢弃") } })
}

@Composable
internal fun SystemVoiceInputStatus(input: JSONObject, speech: JSONObject) {
    val message = input.optString("message")
    val available = speech.optBoolean("recognitionAvailable")
    Text(if (message.isNotBlank()) message else if (available) "系统听写可能联网；最终文字先核对，再由你发送。" else "系统听写服务尚未就绪，仍可继续文字输入。",
        fontSize = 12.sp, lineHeight = 19.sp, color = if (input.optBoolean("resourceReleaseUnknown")) MaterialTheme.colorScheme.error else SecondaryInk,
        modifier = Modifier.padding(top = 5.dp))
}

internal fun systemTtsDescription(speech: JSONObject): String {
    return when (speech.optString("ttsStatus")) {
        "language_data_missing" -> "系统朗读缺少中文语音数据；可继续查看文字。"
        "language_unsupported" -> "当前系统引擎不支持所需中文朗读。"
        "unavailable" -> "系统朗读引擎暂不可用；没有换用其他服务。"
        "initializing" -> "正在准备系统朗读，尚未播放完成。"
        "ready" -> when (speech.optString("ttsVoiceNetworkUse")) {
            "requires_network" -> "当前系统声音声明需要网络。"
            "declares_no_network_requirement" -> "当前系统声音声明不需网络，设备行为仍以系统引擎为准。"
            else -> "当前系统声音的联网属性未知。"
        }
        else -> "按需使用系统朗读；引擎与联网属性尚未确认。"
    }
}
