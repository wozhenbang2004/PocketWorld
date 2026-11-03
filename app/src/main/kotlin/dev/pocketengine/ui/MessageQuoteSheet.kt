package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.pocketengine.ui.memory.TaskMemoryEvidenceGuard
import org.json.JSONObject

/** A selection in an already visible native message; uses the existing source/draft transaction. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageQuoteSheet(message: UiMessage, controller: PocketWorldController, onClose: () -> Unit) {
    val locator = message.ownerOriginalLocator ?: return
    val sourceKey = locator.toString()
    var selection by rememberSaveable(sourceKey, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(message.content, if (message.content.length <= 4000) TextRange(0, message.content.length) else TextRange.Zero))
    }
    var pending by remember(sourceKey) { mutableStateOf<String?>(null) }
    var error by remember(sourceKey) { mutableStateOf<String?>(null) }
    DisposableEffect(sourceKey) {
        controller.enterOwnerDomain("taskMemory")
        onDispose { controller.leaveOwnerDomain("taskMemory") }
    }
    val response = controller.state.ownerResponses("taskMemory")["selectOwnerOriginalReference"]
    LaunchedEffect(pending, response?.toString()) {
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(pending, response) ?: return@LaunchedEffect
        pending = null
        val result = envelope.optJSONObject("result")
        if (envelope.optBoolean("ok") && result?.optBoolean("selectionValidated") == true && result.optBoolean("draftStaged")) onClose()
        else error = envelope.optString("error", "原文或草稿已变化，请核对后重新选择。没有发送消息。")
    }
    val from = selection.selection.min
    val length = selection.selection.max - from
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("引用这条消息", style = MaterialTheme.typography.titleLarge)
            Text("选择要引用的原话。加入当前草稿后仍可检查、移除；发送时会再次核对原消息。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(selection, { next -> selection = TextFieldValue(message.content, next.selection) },
                readOnly = true, enabled = pending == null, minLines = 3, maxLines = 8,
                modifier = Modifier.fillMaxWidth().testTag("message_quote_selection"), label = { Text("长按选择原文") })
            Text(if (length == 0) "请选择一段原话（最多4000个UTF-16字符）。" else "已选择 $length / 4000 个UTF-16字符", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = {
                try {
                    error = null
                    pending = controller.stageOwnerOriginalReference(JSONObject().put("ownerLocator", JSONObject(sourceKey)).put("offset", from).put("chars", length))
                } catch (failure: Exception) { error = failure.message ?: "未加入原草稿，请核对原消息。" }
            }, enabled = pending == null && length in 1..4000,
                modifier = Modifier.fillMaxWidth().testTag("message_quote_stage")) { Text(if (pending == null) "加入草稿" else "正在核对原文…") }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("返回消息") }
        }
    }
}
