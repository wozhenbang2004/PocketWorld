package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Explicit native return of an already verified text file or binary attachment, not automatic forwarding or a new sender. */
@Composable
internal fun RemoteConversationResultPanel(snapshot: JSONObject, canAct: Boolean, controller: PocketWorldController, send: (String, JSONObject) -> Unit) {
    val inputs = snapshot.optJSONArray("conversationInputSources").objects()
    val deliveries = snapshot.optJSONArray("conversationResultDeliveries").objects()
    if (inputs.isEmpty() && deliveries.isEmpty()) return
    var selectedInput by remember(snapshot.optString("taskId")) { mutableStateOf<String?>(null) }
    val original = inputs.find { it.optString("attachmentId") == selectedInput }
    val review = snapshot.optJSONObject("conversationResultReview")
    val scope = review?.optJSONObject("scope")
    var showReview by remember(review?.optString("digest")) { mutableStateOf(review != null) }
    var acknowledged by remember(review?.optString("digest")) { mutableStateOf(false) }
    HorizontalDivider()
    Text("把所选成果回到原私聊", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Text("先选择本次任务显选过的主人私聊原件，再单独核对一份冻结成果。可选择完整文字或准确二进制附件；不会自动附带其他文件或任务历史。", fontSize = 14.sp)
    inputs.forEach { source ->
        Row(Modifier.fillMaxWidth()) {
            RadioButton(selectedInput == source.optString("attachmentId"), { selectedInput = source.optString("attachmentId") }, enabled = canAct)
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                Text(source.optString("profileName") + " · " + source.optString("senderId"), fontSize = 14.sp)
                Text("原消息 ${source.optString("messageId")} · 素材项 ${source.optInt("itemIndex")}", fontSize = 13.sp, color = SecondaryInk)
            }
        }
    }
    if (original != null) snapshot.optJSONArray("snapshotAttachments").objects().forEach { file ->
        TextButton(onClick = { send("reviewConversationResult", JSONObject().put("inputAttachmentId", original.optString("attachmentId")).put("snapshotId", file.optString("snapshotId")).put("path", file.optString("path"))) }, enabled = canAct) {
            Text("按完整文字核对 · ${file.optString("path")}")
        }
        OutlinedButton(onClick = { send("reviewConversationAttachment", JSONObject().put("inputAttachmentId", original.optString("attachmentId")).put("snapshotId", file.optString("snapshotId")).put("path", file.optString("path"))) }, enabled = canAct && file.optLong("bytes", Long.MAX_VALUE) <= 8L * 1024 * 1024) { Text("按原文件附件核对 · ${file.optString("path")}") }
    }
    deliveries.takeLast(8).asReversed().forEach { recorded ->
        val id = recorded.optString("id")
        val actual = controller.state.feature("channels").optJSONArray("outbox").objects().find { it.optString("id") == id } ?: recorded
        val phase = actual.optString("status")
        Text(when (phase) {
            "queued" -> "原会话草稿已保存，尚未发送。"
            "sending" -> "正在向原平台发送；现在不能确认是否已接收。"
            "sent" -> "原平台已接受这次消息请求；不代表收件人已读或业务目标完成。"
            "unknown" -> "这次投递效果未知；保留原编号，只核对，不重发。"
            "cancelled" -> "待发消息已取消；不声称撤回已发生的发送。"
            else -> actual.optString("message", "原投递尚未确认，请查看原记录。")
        }, fontSize = 14.sp)
        if (actual.optJSONObject("ownerReplyMedia") != null) Text("附件阶段 ${actual.optString("mediaPhase")} · 已确认上传 ${actual.optLong("mediaVerifiedUploadedBytes")} bytes；上传残留是否删除未核对。", fontSize = 13.sp, color = SecondaryInk)
        if (phase == "queued") Row {
            OutlinedButton(onClick = { controller.requestFeature("channels", "outbox.send", JSONObject().put("id", id)) }, enabled = "channels.outbox.send" !in controller.state.moduleBusy) { Text("发送这条已确认草稿") }
            TextButton(onClick = { controller.requestFeature("channels", "outbox.cancel", JSONObject().put("id", id)) }) { Text("取消待发") }
        }
        controller.state.moduleResultInputs["channels.outbox.send"]?.takeIf { it.optString("id") == id }?.let { controller.state.moduleErrors["channels.outbox.send"]?.let { error -> Text(error, fontSize = 14.sp, color = Danger) } }
        TextButton(onClick = { controller.openConnection("channels") }) { Text("查看原渠道投递记录") }
    }
    if (showReview && review != null && scope != null) {
        val destination = scope.optJSONObject("inputSource") ?: JSONObject()
        val binary = scope.optString("resultKind") == "exact_binary_attachment_not_text_substitution"
        AlertDialog(onDismissRequest = { showReview = false }, title = { Text(if (binary) "仅把这个固定原文件发回原私聊" else "仅向这个原私聊发送以下文字") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SelectionContainer { Text("${destination.optString("profileName")}\n收件人 ${destination.optString("senderId")}\n原消息 ${destination.optString("messageId")}", fontSize = 14.sp) }
                Text("冻结文件：${scope.optString("path")} · ${scope.optLong("bytes")}字节", fontSize = 14.sp)
                Text(if (binary) "这会把这一份完整原文件交给该平台账号，可能包含工作区私密内容。请先核对文件及准确收件人；不附带其他文件、连接凭据、cookies或任务历史。上传与消息接收是两阶段，未知不重传。" else "这会把下面完整正文交给该平台账号，可能包含工作区私密内容，请逐字核对。不会额外附带其他文件、连接凭据、cookies或任务历史。", fontSize = 14.sp, lineHeight = 21.sp)
                if (binary) scope.optJSONObject("media")?.let { media -> Text("${media.optString("name")} · ${media.optString("mimeType")} · ${media.optLong("bytes")} bytes", fontSize = 14.sp); Text("格式根据实际文件头标记，不等于内容已读或全部可播放。冻结来源未核作者，也不代表整个项目。", fontSize = 14.sp, lineHeight = 21.sp) }
                else SelectionContainer { Text(scope.optString("text"), fontSize = 14.sp) }
                Text("SHA-256 ${scope.optString("sha256")}", fontSize = 13.sp, color = SecondaryInk)
                Row { Checkbox(acknowledged, { acknowledged = it }); Text("我已核对原收件人与完整内容，只生成这次回发草稿。", fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp).weight(1f)) }
            }
        }, confirmButton = { Button(onClick = { send("confirmConversationResult", JSONObject().put("digest", review.optString("digest"))); showReview = false }, enabled = acknowledged && canAct && System.currentTimeMillis() <= review.optLong("expiresAt")) { Text("确认这条原会话草稿") } }, dismissButton = { TextButton(onClick = { showReview = false }) { Text("返回核对") } })
    }
}
