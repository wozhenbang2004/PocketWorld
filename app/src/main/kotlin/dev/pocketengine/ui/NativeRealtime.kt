package dev.pocketengine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.pocketengine.media.RealtimeMediaSession
import org.json.JSONArray
import org.json.JSONObject

/** Formal call surface. The frame always comes from an SDK track, never a decorative substitute. */
@Composable
internal fun NativeRealtime(state: PocketUiState, controller: PocketWorldController) {
    val realtime = state.feature("realtime")
    val config = realtime.optJSONObject("configuration") ?: JSONObject()
    val session = realtime.optJSONObject("session") ?: JSONObject()
    val invitation = realtime.optJSONObject("invitation")
    val invitationId = invitation?.optString("id").orEmpty()
    val sessionId = realtime.optString("sessionId")
    val endpointConfigured = realtime.optBoolean("providerConfigured")
    val stage = session.optString("state", "idle")
    val joined = stage in setOf("connected", "reconnecting")
    val accepting = realtime.optBoolean("accepting") || "realtime.acceptInvitation" in state.moduleBusy
    val cancelling = accepting && realtime.optString("invitationState") in setOf("ended", "cancelled")
    val active = joined || accepting || stage in setOf("connecting", "disconnecting")
    val pending = realtime.optString("invitationState") == "awaiting_owner"
    val voiceActive = realtime.optJSONObject("voiceTurn")?.optBoolean("active") ?: false
    val natural = if (pending || accepting) invitation?.optString("source") == "owner_prepared_natural_voice" else active && session.optString("transport") == "openai_realtime_ga_websocket"
    var singleTurnOpen by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var roomOpen by rememberSaveable { mutableStateOf(false) }
    var manualSettings by rememberSaveable { mutableStateOf(realtime.optBoolean("configured") && !endpointConfigured) }
    var url by remember(config.optString("serverUrl")) { mutableStateOf(config.optString("serverUrl")) }
    var token by remember { mutableStateOf("") }
    var microphone by rememberSaveable(invitationId) { mutableStateOf(false) }
    var camera by rememberSaveable(invitationId) { mutableStateOf(false) }
    LaunchedEffect(config.optLong("revision")) { token = "" }
    fun sessionPayload() = JSONObject().put("sessionId", sessionId)

    Text("语音与视频", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
    NaturalVoiceControls(realtime, state, controller, active || pending || voiceActive)
    VoiceTaskControls(realtime, { command, payload -> controller.requestRealtime(command, payload) }, controller::focusTask)
    state.moduleErrors.filterKeys { it in setOf("realtime.commitVoiceTask", "realtime.listVoiceTasks", "realtime.recoverVoiceTask", "realtime.inspectVoiceTask", "realtime.startVoiceTask", "realtime.cancelVoiceTask", "realtime.readVoiceTask") }.values.forEach { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    if (!active && !pending) TextButton(onClick = { singleTurnOpen = !singleTurnOpen }) { Text(if (singleTurnOpen) "收起单轮听写与朗读" else "单轮听写与朗读") }
    if (voiceActive || singleTurnOpen) VoiceCompanion(realtime, state, controller, active || pending)
    if (!active && !pending) TextButton(onClick = { roomOpen = !roomOpen }, modifier = Modifier.padding(top = 12.dp)) { Text(if (roomOpen) "收起房间通话与视频" else "房间通话与视频") }
    if (session.optBoolean("resourceReleaseUnknown")) {
        Text("媒体资源释放未确认。请检查系统采集指示并重启应用后再通话。", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
    }
    if (active || pending || roomOpen) {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text(if (natural) "自然语音会话" else "房间通话与视频", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    RealtimeCaption("只在你接受后连接。切到后台会关闭通话；挂断不会停止已有的手机或电脑任务。")
    Text(realtimeLabel(realtime), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 10.dp))
    if (pending && !active && invitation != null) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(invitation.optString("displayName", "通话邀请"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        RealtimeCaption(when (invitation.optString("source")) {
            "provider_invitation" -> "来自已配置服务 ${invitation.optString("providerId")} 的邀请。接受前没有采集。"
            "owner_prepared_provider" -> "这是你准备发起的通话，不是来电。接受后才向 ${invitation.optString("tokenEndpoint")} 请求凭据，再连接 ${invitation.optString("serverUrl")}；指定 agent：${invitation.optString("agentName")}。服务未响应或没有对端都不会假装成功。"
            "owner_prepared_natural_voice" -> "接受后连接 ${invitation.optString("serverUrl")}；语音模型：${invitation.optString("model")}，声音：${invitation.optString("voice")}。开启麦克风后原始声音发送到该服务，可以自然插话；此语音连接不会执行任务或工具。"
            else -> "这是你准备加入的房间，不是远端来电。对端可能是真人、AI 服务或其他媒体来源。"
        })
        CaptureChoice("发送麦克风声音", microphone, { microphone = it }, true)
        if (!natural) CaptureChoice("打开并发送摄像头", camera, { camera = it }, true)
        RealtimeCaption(if (natural) "麦克风默认关闭；接受不会自动开启采集。语音内容可能产生服务费用。" else "两个开关默认关闭，也可以只接收对端媒体。开启后的声音和画面会发送到配置的房间。")
        PermissionActions(realtime, state, controller, !natural)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { controller.requestRealtime("acceptInvitation", JSONObject().put("invitationId", invitationId).put("microphone", microphone).put("camera", camera)) },
                enabled = !voiceActive && realtime.optBoolean("foregroundOwner") && realtime.optBoolean("captureCoordinatorReady") && (!microphone || realtime.optBoolean("microphonePermission")) && (!camera || realtime.optBoolean("cameraPermission")), modifier = Modifier.weight(1f)) { Text("接受并连接") }
            OutlinedButton(onClick = { controller.requestRealtime("rejectInvitation", JSONObject().put("invitationId", invitationId)) }, enabled = "realtime.rejectInvitation" !in state.moduleBusy) { Text("拒绝") }
        }
    }
    if (joined) {
        val videos = session.optJSONArray("remoteVideos") ?: JSONArray()
        if (!natural && videos.length() == 0) {
            Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(if (stage == "reconnecting") "网络恢复后重新接收画面" else "对端尚未发布可订阅的视频", color = SecondaryInk, fontSize = 13.sp)
            }
        } else repeat(videos.length()) { index ->
            val video = videos.optJSONObject(index) ?: return@repeat
            val trackSid = video.optString("trackSid")
            key(sessionId, trackSid, session.optLong("videoRevision")) {
                Text(video.optString("displayName").ifBlank { "远端参与者" }, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                RealtimeCaption(videoOriginLabel(video))
                if (video.optBoolean("muted")) Text("对端已暂停这个视频", fontSize = 13.sp, color = SecondaryInk)
                else CallVideo(sessionId, trackSid, controller, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
            }
        }
        val localSid = session.optString("localVideoTrackSid")
        if (stage == "connected" && session.optBoolean("cameraEnabled") && localSid.isNotEmpty()) {
            Text("我的摄像头 · 正在发送", fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            key(sessionId, localSid, session.optLong("videoRevision")) { CallVideo(sessionId, localSid, controller, Modifier.width(144.dp).height(108.dp)) }
        }
        if (!natural) RealtimeCaption("房间内远端参与者：${session.optInt("remoteParticipants")}。加入房间不代表 AI 服务已就绪。")
        val changing = realtime.optBoolean("changingCapture") || "realtime.mute" in state.moduleBusy || "realtime.setCamera" in state.moduleBusy
        CaptureChoice("麦克风发送", session.optBoolean("microphoneEnabled"), { controller.requestRealtime("mute", sessionPayload().put("muted", !it)) }, stage == "connected" && !changing && (realtime.optBoolean("microphonePermission") || session.optBoolean("microphoneEnabled")))
        if (!natural) CaptureChoice("摄像头发送", session.optBoolean("cameraEnabled"), { controller.requestRealtime("setCamera", sessionPayload().put("enabled", it)) }, stage == "connected" && !changing && (realtime.optBoolean("cameraPermission") || session.optBoolean("cameraEnabled")))
        RealtimeCaption(if (natural) "关闭麦克风会停止采集并清空待发送输入；挂断释放整个语音会话。" else "关闭麦克风发送会静音；关闭摄像头会停止摄像头采集。挂断会释放整个媒体会话。")
        PermissionActions(realtime, state, controller, !natural)
    }
    if (active) {
        Button(onClick = { controller.requestRealtime("disconnect", sessionPayload()) }, enabled = sessionId.isNotEmpty() && !cancelling && stage != "disconnecting" && "realtime.disconnect" !in state.moduleBusy,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(if (cancelling) "正在结束连接" else if (accepting || stage == "connecting") "取消连接" else "挂断通话") }
    } else if (!pending) {
        val feedback = realtime.optString("invitationMessage")
        if (feedback.isNotBlank()) RealtimeCaption(feedback)
        Button(onClick = { controller.requestRealtime(if (endpointConfigured) "prepareProviderCall" else "prepareCall") }, enabled = (endpointConfigured || realtime.optBoolean("configured")) && realtime.optBoolean("foregroundOwner") && !realtime.optBoolean("audioLeaseHeld") && !voiceActive && "realtime.prepareCall" !in state.moduleBusy && "realtime.prepareProviderCall" !in state.moduleBusy) { Text(if (endpointConfigured) "准备连接通话服务" else "准备加入房间") }
        if (!realtime.optBoolean("captureCoordinatorReady")) RealtimeCaption("通话采集协调器尚未接入，不能开始通话。")
    }
    }
    RealtimeTranscripts(realtime, state, controller)
    if (roomOpen && !active && !pending && !voiceActive) {
        TextButton(onClick = { settings = !settings }, modifier = Modifier.padding(top = 16.dp)) { Text(if (settings) "收起高级连接设置" else "高级连接设置") }
        if (settings) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EndpointProviderSettings(realtime, state, controller)
            TextButton(onClick = { manualSettings = !manualSettings }) { Text(if (manualSettings) "收起手动房间配置" else "仅使用手动房间令牌") }
            if (manualSettings) {
            RealtimeCaption("手动房间令牌不代表 AI 对端已配置。它可用于真实真人或已有服务的媒体房间。")
            OutlinedTextField(url, { url = it }, label = { Text("LiveKit 服务地址") }, placeholder = { Text("wss://你的服务地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("参与者令牌，不是服务器密钥") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            RealtimeCaption(if (config.optBoolean("hasToken")) "同一服务可留空保留令牌。过期后需要由可信服务重新签发。" else "只填写该房间的短期参与者令牌，令牌单独加密保存。")
            Button(onClick = { controller.requestRealtime("configure", JSONObject().put("serverUrl", url.trim()).put("participantToken", token).put("allowLoopbackWs", config.optBoolean("allowLoopbackWs"))) }, enabled = url.isNotBlank() && "realtime.configure" !in state.moduleBusy) { Text("保存连接设置") }
            if (endpointConfigured && realtime.optBoolean("configured")) TextButton(onClick = { controller.requestRealtime("prepareCall") }, enabled = "realtime.prepareCall" !in state.moduleBusy) { Text("准备加入手动房间") }
            }
        }
    }
}

@Composable
private fun CallVideo(sessionId: String, trackSid: String, controller: PocketWorldController, modifier: Modifier) {
    var binding by remember(sessionId, trackSid) { mutableStateOf<RealtimeMediaSession.VideoBinding?>(null) }
    var failed by remember(sessionId, trackSid) { mutableStateOf(false) }
    DisposableEffect(sessionId, trackSid) {
        val owned = try { controller.realtimeVideoBinding(sessionId, trackSid) } catch (_: Exception) { failed = true; null }
        binding = owned
        onDispose { owned?.close() }
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        binding?.let { active -> AndroidView(factory = { active.view() }, modifier = Modifier.fillMaxSize()) }
        if (failed) Text("视频已变化，请等待下一次媒体状态", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun CaptureChoice(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun PermissionActions(realtime: JSONObject, state: PocketUiState, controller: PocketWorldController, cameraAvailable: Boolean = true) {
    if (!realtime.optBoolean("microphonePermission")) TextButton(onClick = { controller.requestRealtime("requestMicrophonePermission") }, enabled = "realtime.requestMicrophonePermission" !in state.moduleBusy) { Text("允许使用麦克风", fontSize = 12.sp) }
    if (cameraAvailable && !realtime.optBoolean("cameraPermission")) TextButton(onClick = { controller.requestRealtime("requestCameraPermission") }, enabled = "realtime.requestCameraPermission" !in state.moduleBusy) { Text("允许使用摄像头", fontSize = 12.sp) }
}

internal fun videoOriginLabel(video: JSONObject): String = if (video.optString("origin") == "server_declared_agent") "远端 AI 服务视频（房间服务端标记，非真人验证）" else "远端视频 · 真人、AI 或录制来源未核验"

internal fun realtimeLabel(value: JSONObject): String {
    val session = value.optJSONObject("session") ?: JSONObject()
    if (value.optBoolean("accepting")) return if (value.optString("invitationState") in setOf("ended", "cancelled")) "正在释放已取消的连接" else "正在接受并连接，随时可取消"
    if (value.optString("invitationState") == "awaiting_owner") return "等待你接受或拒绝"
    if (session.optString("transport") == "openai_realtime_ga_websocket" && session.optString("state") == "connected") return "自然语音已连接 · 可以插话"
    return when (session.optString("state", "idle")) {
        "connecting" -> "正在加入实际房间"
        "connected" -> if (session.optBoolean("remoteAudioSubscribed") || (session.optJSONArray("remoteVideos")?.length() ?: 0) > 0) "媒体已订阅 · 远端身份见下方来源" else "已加入房间，等待对端媒体"
        "reconnecting" -> "网络中断，正在恢复当前通话"
        "disconnecting" -> "正在关闭媒体会话"
        "disconnected" -> "通话已断开"
        "failed" -> "通话未完成，请检查服务与权限"
        else -> if (value.optBoolean("naturalVoiceConfigured")) "自然语音服务已配置，尚未开始采集" else if (value.optBoolean("providerConfigured")) "服务已配置，尚未发起通话" else if (value.optBoolean("configured")) "尚未加入房间" else "尚未配置通话服务"
    }
}

@Composable private fun RealtimeCaption(value: String) { Text(value, color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)) }
