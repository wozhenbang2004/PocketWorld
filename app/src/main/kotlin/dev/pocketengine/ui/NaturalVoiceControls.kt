package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Continuous audio has its own explicit consent and credentials, separate from text dictation. */
@Composable
internal fun NaturalVoiceControls(realtime: JSONObject, state: PocketUiState, controller: PocketWorldController, busy: Boolean) {
    val configured = realtime.optBoolean("naturalVoiceConfigured")
    val supported = realtime.optBoolean("naturalVoiceSupported")
    val config = realtime.optJSONObject("naturalVoiceConfiguration") ?: JSONObject()
    val session = realtime.optJSONObject("session") ?: JSONObject()
    val natural = session.optString("transport") == "openai_realtime_ga_websocket"
    var settings by rememberSaveable { mutableStateOf(false) }
    var endpoint by remember(config.optLong("revision")) { mutableStateOf(config.optString("endpointUrl")) }
    var model by remember(config.optLong("revision")) { mutableStateOf(config.optString("model")) }
    var voice by remember(config.optLong("revision")) { mutableStateOf(config.optString("voice")) }
    var transcriptionModel by remember(config.optLong("revision")) { mutableStateOf(config.optString("transcriptionModel")) }
    var token by remember { mutableStateOf("") }
    LaunchedEffect(config.optLong("revision")) { token = "" }
    Text("自然语音对话", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Text("连接后可以连续交谈和插话。麦克风声音直接发送给你选定的语音服务；停止说话和挂断都不会取消已有任务。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
    if (realtime.optJSONObject("capabilities")?.optBoolean("voiceTaskHost") == true) Text("要交给助手做事，请在通话字幕中复核最终目标并明确开始。执行、审批和结果会留在同一原任务。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 10.dp))
    if (natural && session.optString("state") == "connected") {
        Text(naturalVoiceOutputLabel(session), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        OutlinedButton(onClick = { controller.requestRealtime("interruptOutput", JSONObject().put("sessionId", realtime.optString("sessionId"))) },
            enabled = !session.optBoolean("interruptionPending") && "realtime.interruptOutput" !in state.moduleBusy, modifier = Modifier.padding(top = 8.dp)) {
            Text(if (session.optBoolean("interruptionPending")) "本机已停，正在确认远端打断" else "停止说话 · 继续听")
        }
        Text("麦克风开关与挂断在下方。切到后台会结束本次语音，不会自动重连或重播。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
    } else if (!busy) {
        Button(onClick = { controller.requestRealtime("prepareNaturalVoice") }, enabled = supported && configured && realtime.optBoolean("foregroundOwner") && realtime.optBoolean("captureCoordinatorReady") && !realtime.optBoolean("audioLeaseHeld") && "realtime.prepareNaturalVoice" !in state.moduleBusy) { Text("准备开始自然语音") }
        if (!configured) Text("先设置自己的语音服务、模型和声音。保存设置不会录音或连接。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
        if (!supported) Text("自然语音适配器尚未接入。", color = SecondaryInk, fontSize = 12.sp)
        TextButton(onClick = { settings = !settings }) { Text(if (settings) "收起语音服务设置" else "语音服务设置") }
        if (settings) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("实时语音 WSS 端点") }, placeholder = { Text("wss://你的语音服务/实时端点") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("服务支持的语音模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(voice, { voice = it }, label = { Text("声音标识") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(transcriptionModel, { transcriptionModel = it }, label = { Text("服务支持的输入转录模型（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("从说话创建任务需要最终转录；留空只进行自然语音对话。输入转录是独立服务能力，可能额外计费，结果仍须逐字复核。没有支持的模型或识别失败时，可改用文字目标。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
            OutlinedTextField(token, { token = it }, label = { Text("该语音服务的认证令牌") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("支持 Realtime 语音协议的服务。优先使用你的可信服务签发的短期令牌；也可明确配置自己的服务凭据，单独加密保存。更换服务地址后须重新提供令牌，不会使用文字模型密钥。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
            if (config.optBoolean("hasAuthentication")) Text("同一服务可留空保留凭据。短期令牌过期后由你更新。", color = SecondaryInk, fontSize = 12.sp)
            Button(onClick = { controller.requestRealtime("configureNaturalVoice", JSONObject().put("endpointUrl", endpoint.trim()).put("model", model.trim()).put("voice", voice.trim()).put("transcriptionModel", transcriptionModel.trim()).put("authenticationToken", token)) },
                enabled = supported && endpoint.isNotBlank() && model.isNotBlank() && voice.isNotBlank() && "realtime.configureNaturalVoice" !in state.moduleBusy) { Text("保存语音设置") }
            if (configured) TextButton(onClick = { controller.requestRealtime("clearNaturalVoice") }, enabled = "realtime.clearNaturalVoice" !in state.moduleBusy) { Text("移除语音服务与凭据") }
        }
    }
    if (natural) {
        NativeInferenceReference(session.optJSONObject("inferenceReference"), controller, "查看本次语音模型用量")
        if (session.optString("usageAccounting") == "unconfirmed") Text("部分用量保存未确认；请查看实际记录，不表示免费或0。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    state.moduleErrors.filterKeys { it in setOf("realtime.prepareNaturalVoice", "realtime.configureNaturalVoice", "realtime.clearNaturalVoice", "realtime.interruptOutput") }.values.forEach { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
}

internal fun naturalVoiceOutputLabel(session: JSONObject): String = when {
    session.optBoolean("interruptionPending") -> "本机已停止说话 · 正在确认远端上下文"
    !session.optBoolean("microphoneEnabled") -> "麦克风已关闭"
    else -> when (session.optString("outputState")) {
        "thinking" -> "正在组织回复 · 可以继续插话"
        "buffering", "draining" -> "正在接收或播放回复 · 可以插话"
        "interrupted" -> "已停止说话 · 可以继续交谈"
        else -> "正在听 · 可以自然交谈"
    }
}
