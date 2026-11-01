package dev.pocketengine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject

/** The native inbox is the source. No URL, task goal or decryption grant is accepted by this UI. */
@Composable
internal fun ChannelOwnerMediaPanel(state: PocketUiState, controller: PocketWorldController) {
    var selected by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val events = state.feature("channels").optJSONArray("inbox").objects().filter { event ->
        event.optJSONObject("messageParts")?.optJSONArray("parts").objects().any {
            it.optString("kind") == "media" || it.optString("kind") == "unsupported_media" && it.optJSONObject("media") != null
        }
    }.takeLast(6).asReversed()
    if (events.isNotEmpty()) {
        Text("私聊文件与音视频", style = MaterialTheme.typography.titleSmall)
        Text("只显示本机已接收范围。保存原件不代表已听懂、看完或发给模型；原主人身份与媒体同意会再次核对。", fontSize = 14.sp, lineHeight = 21.sp)
    }
    for (event in events) {
        val parts = event.optJSONObject("messageParts")?.optJSONArray("parts").objects()
        val refs = event.optJSONArray("ownerMediaArtifacts").objects()
        parts.forEachIndexed { index, part ->
            if (part.optString("kind") != "media" && !(part.optString("kind") == "unsupported_media" && part.optJSONObject("media") != null)) return@forEachIndexed
            val ref = refs.singleOrNull { it.optInt("itemIndex", -1) == index }
            val label = part.optString("declaredName").ifBlank { part.optJSONObject("media")?.optString("name").orEmpty() }.ifBlank {
                when (part.optString("mediaKind")) { "voice" -> "原语音"; "video" -> "原视频"; else -> "原文件" }
            }
            Text(label, fontSize = 16.sp)
            Text("来源 ${event.optString("senderId").take(80)} · 第${index + 1}项" + if (ref == null) " · 尚未保存" else " · ${ref.optLong("bytes")} bytes · ${ref.optString("mimeType")}", fontSize = 13.sp, color = SecondaryInk)
            OutlinedButton(onClick = { selected = event.optString("id") to index }, modifier = Modifier.fillMaxWidth()) {
                Text(if (ref == null) "核对并保存这一项" else "核对并查看固定原件")
            }
        }
    }
    state.feature("channels").optJSONArray("outbox").objects().filter { it.optJSONObject("nativeQqStream") != null }.takeLast(6).asReversed().forEach { delivery ->
        val stream = delivery.getJSONObject("nativeQqStream")
        Text(if (stream.optBoolean("complete")) "QQ原消息最终帧已被平台接受；不代表已读。" else if (delivery.optString("status") == "unknown") "QQ生成中草稿未确认完整结束；部分文字可能已显示，不自动重发。" else "QQ同一条消息正在显示生成中草稿，尚未完整结束。", fontSize = 14.sp, lineHeight = 21.sp)
        Text("原投递 ${delivery.optString("id")} · 已确认帧 ${stream.optInt("lastAckIndex", -1) + 1}", fontSize = 13.sp, color = SecondaryInk)
    }
    selected?.let { target -> ChannelOwnerMediaDialog(target.first, target.second, state, controller) { selected = null } }
}

@Composable
private fun ChannelOwnerMediaDialog(eventId: String, index: Int, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED) }
    var reviewRequest by remember(eventId, index) { mutableStateOf<String?>(null) }
    var freezeRequest by remember(eventId, index) { mutableStateOf<String?>(null) }
    var accepted by remember(eventId, index) { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    fun review() {
        accepted = false; freezeRequest = null
        try {
            controller.enterOwnerDomain("channelMedia")
            reviewRequest = controller.requestOwnerDomain("channelMedia", "reviewMedia", JSONObject().put("eventId", eventId).put("itemIndex", index))
            localError = null
        } catch (error: Exception) { localError = error.message ?: "无法打开原媒体审阅" }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState == Lifecycle.State.RESUMED }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.leaveOwnerDomain("channelMedia") }
    }
    LaunchedEffect(foreground, eventId, index) { if (foreground) review() else { accepted = false; controller.leaveOwnerDomain("channelMedia") } }
    val reviewEnvelope = state.ownerResponses("channelMedia")["reviewMedia"]?.takeIf { it.optString("requestId") == reviewRequest }
    val value = reviewEnvelope?.takeIf { it.optBoolean("ok") }?.optJSONObject("result")
    val freezeEnvelope = state.ownerResponses("channelMedia")["freezeMedia"]?.takeIf { it.optString("requestId") == freezeRequest }
    val saved = freezeEnvelope?.takeIf { it.optBoolean("ok") }?.optJSONObject("result")?.optJSONObject("attachment")
    val busy = state.ownerPending.any { it.startsWith("channelMedia.") }
    AlertDialog(onDismissRequest = close, title = { Text("核对这份私聊原件") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            value?.let {
                Text(it.optString("name"), fontSize = 16.sp)
                Text("${it.optString("profileName")} · ${it.optString("senderId")} · 第${it.optInt("itemIndex") + 1}项", fontSize = 14.sp)
                Text(if (it.has("declaredBytes")) "平台声明 ${it.optLong("declaredBytes")} bytes；下载后核对实际完整大小和SHA-256。" else "平台未声明明文大小；最多读取8MiB，完整接收后记录实际大小和SHA-256。", fontSize = 14.sp, lineHeight = 21.sp)
                Text("只从已认证消息的官方CDN下载并保存到本机私人附件，不跟随其他网站。不会自动解读音视频、调用模型、传到电脑或发送给任何人。远端输入和回发需要另外确认。", fontSize = 14.sp, lineHeight = 21.sp)
            }
            if (saved != null) {
                Text("实际原件已保存：${saved.optLong("size")} bytes · ${saved.optString("mimeType")}", fontSize = 14.sp)
                Text("SHA-256 ${saved.optString("sha256")}", fontSize = 13.sp)
                Text("格式仅按实际文件头标记；不证明所有内容可播放或已理解。原文件可在私人附件中另选为远端输入，仍受目标和大小范围限制。", fontSize = 14.sp, lineHeight = 21.sp)
            } else if (value != null) {
                Column { Checkbox(accepted, { accepted = it }, enabled = foreground && !busy); Text("我确认这是此原主人私聊的这一项，允许只保存到本机。", fontSize = 14.sp) }
            }
            listOfNotNull(localError, reviewEnvelope?.takeUnless { it.optBoolean("ok") }?.optString("error"), freezeEnvelope?.takeUnless { it.optBoolean("ok") }?.optString("error")).filter { it.isNotBlank() }.forEach { Text(it, color = Danger, fontSize = 14.sp) }
        }
    }, confirmButton = {
        if (saved != null) Button(onClick = { controller.openCurrentChannelMedia() }, enabled = foreground && !busy) { Text("打开固定原件") }
        else Button(onClick = { value?.let { freezeRequest = controller.requestOwnerDomain("channelMedia", "freezeMedia", JSONObject().put("digest", it.getString("digest"))) } }, enabled = foreground && !busy && value != null && accepted) { Text(if (busy) "处理中" else if (value?.has("frozen") == true) "核对本机固定版本" else "保存这一项") }
    }, dismissButton = { TextButton(onClick = close) { Text("返回") } })
}
