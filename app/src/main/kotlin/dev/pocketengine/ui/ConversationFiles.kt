package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

internal fun attachmentOpenKey(file: UiChatAttachment) = file.id + ":" + file.sha256

/** Exact original refs only; the current composer selection is never a historical source. */
internal fun attachmentDisplay(file: UiChatAttachment, state: PocketUiState): UiChatAttachment {
    val meta = state.feature("attachments").optJSONArray("attachments").objects().firstOrNull {
        it.optString("id") == file.id && it.optString("sha256") == file.sha256 && it.optString("mimeType") == file.mimeType && it.optLong("size", -1) == file.size
    }
    return meta?.let(ChatInputRules::attachment) ?: file
}

/** A model's prose cannot create an artifact. A native receipt must link an actual private file. */
internal fun taskResultAttachments(task: UiTask): List<UiChatAttachment> {
    if (task.raw.optJSONObject("origin")?.optString("kind") == "group") return emptyList()
    return task.raw.optJSONArray("operations").objects().flatMap { operation ->
        val receipt = operation.optJSONObject("receipt") ?: JSONObject()
        val data = receipt.optJSONObject("data") ?: JSONObject()
        (receipt.optJSONArray("attachments").objects() + data.optJSONArray("attachments").objects() + listOfNotNull(data.optJSONObject("attachment")) + listOf(data)).mapNotNull { meta ->
            val source = meta.optJSONObject("source")
            if (source?.optString("taskId") != task.id || source.has("operationId") && source.optString("operationId") != operation.optString("operationId")) null
            else ChatInputRules.attachment(meta)
        }
    }.distinctBy(::attachmentOpenKey).take(40)
}

@Composable
internal fun ConversationFileRow(file: UiChatAttachment, state: PocketUiState, open: () -> Unit, modifier: Modifier = Modifier) {
    val display = attachmentDisplay(file, state)
    val result = state.attachmentOpens[attachmentOpenKey(file)]
    Column(modifier.fillMaxWidth()) {
        TextButton(onClick = open, enabled = result?.checking != true, contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.fillMaxWidth().testTag("conversation_file_" + file.id)) {
            Column(Modifier.weight(1f)) {
                Text(display.name.ifBlank { "附件（名称未保存）" }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                Text(display.mimeType + " · " + display.size + " 字节", fontSize = 12.sp, color = SecondaryInk, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
            }
            Text(if (result?.checking == true) "核对中" else "打开", fontSize = 12.sp)
        }
        result?.error?.let { Text(it, fontSize = 12.sp, lineHeight = 20.sp, color = Danger) }
        if (result?.requested == true) Text("已请求本机查看器打开；返回后仍在原位置。", fontSize = 12.sp, color = SecondaryInk)
    }
}
