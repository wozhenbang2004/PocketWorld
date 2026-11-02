package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import dev.pocketengine.ui.memory.TaskMemoryWorkflowSheet

/** Read-only window. The active conversation, draft and original Runner always remain elsewhere. */
internal data class ConversationReadPage(
    val window: Long, val conversation: String, val title: String, val readingKey: String?,
    val messages: List<UiMessage> = emptyList(), val cursor: String = "", val hasMore: Boolean = false,
    val loading: Boolean = true, val error: String? = null, val notice: String? = null,
    val rowBytes: Map<String,Int> = emptyMap(), val limited: Boolean = false, val index: Int = 0, val offset: Int = 0, val anchor: String = "", val bytes: Int = 0, val initialized: Boolean = false
) {
    fun accept(native: JSONObject, older: Boolean): ConversationReadPage {
        require(native.optString("conversationId") == conversation) { "原历史窗口属于其他对话" }
        val raw = native.getJSONArray("messages"); require(raw.length() <= 80) { "原历史窗口超出读取范围" }
        val nativeRows=raw.objects()
        val loaded = nativeRows.map { row ->
            UiMessage(row.getString("role"), row.getString("content"), row.optLong("at"), row.optString("taskId").takeIf(String::isNotBlank), row.getString("id"),
                row.optJSONObject("submission"), row.optJSONArray("attachments")?.length() ?: 0, row.optJSONArray("attachments").objects().mapNotNull(ChatInputRules::attachment),
                if (row.optBoolean("importAssignedIdentity")) 0L else row.optLong("revision", 0), memoryReference = row.optJSONObject("memoryReference"))
        }
        require(loaded.none { it.id.isNullOrBlank() }) { "原历史消息缺少稳定身份" }
        require(loaded.map { it.id }.distinct().size == loaded.size) { "原历史窗口重复消息身份，未混合显示" }
        val combined = if (older) (loaded + messages).distinctBy { it.id } else loaded
        val pageBytes=nativeRows.associate { it.getString("id") to it.toString().toByteArray(Charsets.UTF_8).size }
        val retainedBytes=if(older)rowBytes+pageBytes else pageBytes
        val actualBytes = combined.sumOf { (retainedBytes[it.id]?.toLong() ?: (it.content.toByteArray(Charsets.UTF_8).size.toLong()+512L)) }
        if (combined.size > 240 || actualBytes > 1024L * 1024L) return copy(loading = false, limited = true, notice = "已到本次阅读窗口上限，原消息没有删除。请从对话原文页按范围继续查看。")
        val page = native.optJSONObject("messagePage") ?: JSONObject()
        require(!page.has("hasMore") || page.opt("hasMore") is Boolean) { "原历史分页状态无效" }
        val next = page.optString("nextCursor"); require(next.length <= 2048 && (!page.optBoolean("hasMore") || next.isNotEmpty())) { "原历史游标不可核对" }
        return copy(title = native.optString("title", title), messages = combined, cursor = next, hasMore = page.optBoolean("hasMore"), loading = false, error = null,
            bytes = actualBytes.toInt(), rowBytes=retainedBytes.filterKeys { key->combined.any { it.id==key } }, initialized = true, limited = false,
            notice = if (page.optBoolean("retentionLimitApplied")) "旧文件当前只提供原保留窗口，未显示成完整历史。" else if ((page.optJSONArray("gaps")?.length() ?: 0) > 0) "部分原件超出本次范围，未用摘要替代。" else if (anchor.isNotBlank() && combined.none { it.id == anchor }) "上次阅读位置不在当前窗口，可继续查看较早消息；当前任务未切换。" else null)
    }
    fun locate(): Int = messages.indexOfFirst { it.id == anchor }.takeIf { it >= 0 } ?: index.coerceIn(0, (messages.size - 1).coerceAtLeast(0))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationReader(controller: PocketWorldController) {
    val page = controller.conversationReader ?: return
    if (!controller.canReadConversationWindow(page)) { LaunchedEffect(page.window) { controller.closeConversationReader() }; return }
    val scroll = remember(page.window) { LazyListState(page.index, page.offset) }
    var reference by remember(page.window) { mutableStateOf<JSONObject?>(null) }
    reference?.let { TaskMemoryWorkflowSheet(controller,it) { reference=null } }
    var answerSourceAnchor by remember(page.window) { mutableStateOf<JSONObject?>(null) }
    answerSourceAnchor?.let { AnswerSourcesSheet(it, controller::readAnswerSource, controller::readAnswerFrame, controller) { answerSourceAnchor = null } }
    var restored by remember(page.window) { mutableStateOf(false) }
    LaunchedEffect(page.window, page.initialized, page.messages.firstOrNull()?.id) {
        if (page.initialized && !restored) { scroll.scrollToItem(page.locate(), page.offset); restored = true }
        else if (restored && page.anchor.isNotBlank()) page.messages.indexOfFirst { it.id == page.anchor }.takeIf { it >= 0 }?.let { scroll.scrollToItem(it, page.offset) }
    }
    LaunchedEffect(page.window, restored) {
        if (restored) snapshotFlow { Pair(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset) }.collect { (index, offset) -> controller.saveConversationReading(page.window, index, offset) }
    }
    ModalBottomSheet(onDismissRequest = controller::closeConversationReader, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).imePadding().navigationBarsPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = controller::closeConversationReader) { Text("返回正在进行的对话") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = controller::refreshConversationReader, enabled = !page.loading) { Text("刷新") }
            }
            Text(page.title.ifBlank { "历史对话" }, style = MaterialTheme.typography.titleLarge)
            Text("只读查看 · 当前任务和输入位置保持不变", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (page.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            page.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            page.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (page.hasMore) TextButton(onClick = controller::loadEarlierConversationReader, enabled = !page.loading && !page.limited) { Text("查看较早消息") }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("readonly_conversation_messages"), state = scroll, verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                items(page.messages, key = { it.id!! }) { message ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (message.role == "user") "主人" else if (message.role == "assistant") "助手" else "历史记录", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        RichConversationMessageBody(message.content)
                        message.attachments.forEach { file -> ConversationFileRow(file, controller.state, { controller.openReaderAttachment(page.window, message.id!!, file) }) }
                        if (message.attachmentCount > message.attachments.size) Text("另有 ${message.attachmentCount - message.attachments.size} 个原附件引用，当前元数据不可读。", style = MaterialTheme.typography.bodySmall)
                        message.memoryReference?.let { native ->
                            native.optJSONObject("answerSources")?.let { anchor -> TextButton(onClick = { answerSourceAnchor = JSONObject(anchor.toString()) }) { Text("回答保存的来源") } }
                            if (native.optBoolean("memoryProvided", true)) TextButton(onClick = { reference=JSONObject(native.toString()) }) { Text("这次答复的记忆来源") }
                        }
                        message.submission?.let { receipt -> Text(when (receipt.optString("status")) { "withdrawn" -> "已撤回排队消息 · 保留原文审计"; "consumed" -> "本条已有采用回执"; "queued", "accepted" -> "本条已有接收回执"; else -> "原接收状态待核" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            if (!controller.state.chatBusy && !controller.state.chatSubmitting) TextButton(onClick = { controller.switchFromConversationReader(page.window) }) { Text("切换到这条对话输入") }
        }
    }
}
