package dev.pocketengine.ui.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.memory.ConversationHistory
import org.json.JSONObject

/** Only receives a sourceJump extracted from this task's real native history tool receipt. */
@Composable
internal fun ConversationHistoryCitationPanel(
    citation: JSONObject,
    responses: Map<String, JSONObject>,
    onRequest: (String, JSONObject) -> String,
    onOpenTask: (String) -> Unit,
    onBack: () -> Unit,
    refreshRevision: Long
) {
    val identity = citation.toString()
    val selected = remember(identity) { runCatching { ConversationHistory.checkedCitation(citation) } }
    val requests = remember(identity) { TaskMemoryTraceViewState.Requests() }
    var pending by remember(identity) { mutableStateOf<String?>(null) }
    var body by remember(identity) { mutableStateOf<String?>(null) }
    var error by remember(identity) { mutableStateOf(selected.exceptionOrNull()?.message) }
    var copied by remember(identity) { mutableStateOf(false) }
    var observedRefresh by remember(identity) { mutableStateOf(refreshRevision) }
    val clipboard = LocalClipboardManager.current
    val reference = selected.getOrNull()
    val command = "readConversationHistoryCitation"
    fun stop() { pending?.let(requests::abandon); pending = null; body = null; copied = false }
    fun read() {
        val source = reference ?: return
        stop(); error = null
        try { pending = requests.begin(onRequest(command, JSONObject(source.toString()))) }
        catch (failure: Exception) { error = failure.message ?: "历史原件暂不可读" }
    }
    LaunchedEffect(identity) { if (reference != null) read() }
    LaunchedEffect(refreshRevision) {
        if (observedRefresh != refreshRevision) { observedRefresh = refreshRevision; stop(); error = "来源已变化，旧正文已收起；请重新查询这个版本。" }
    }
    val response = responses[command]
    LaunchedEffect(response?.toString(), pending) {
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(pending, response) ?: return@LaunchedEffect
        pending = null
        if (!envelope.getBoolean("ok")) { body = null; error = envelope.optString("error", "原历史来源读取未完成。"); return@LaunchedEffect }
        try {
            val page = ConversationHistory.checkedCitationPage(envelope.getJSONObject("result"), reference!!)
            require(page.optString("status") == "current") {
                when (page.optString("status")) {
                    "source_changed" -> "来源版本或内容指纹已变化，请回原查询重新选择；不会展示新正文。"
                    "retired" -> "这条来源的历史自动参考已停用，不会从任务副本复活正文。"
                    "deleted" -> when (page.optString("reason")) {
                        "user_message_delete", "user_conversation_delete" -> "原消息已由你删除。"
                        "capacity_message_limit", "capacity_conversation_limit" -> "原消息已因原对话容量上限裁剪；当前没有完整长期历史。"
                        else -> "原来源已不可用，旧记录未保留具体原因。"
                    }
                    else -> "原来源缺失、不可核对或超出本次读取预算；未用摘要补造。"
                }
            }
            body = page.getString("body"); error = null
        } catch (failure: Exception) { body = null; copied = false; error = failure.message ?: "历史引用的版本或范围无法核对。" }
    }
    BackHandler { stop(); onBack() }
    DisposableEffect(identity) { onDispose { pending?.let(requests::abandon) } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("这次找回的原会话证据", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("只展示原生读取回执实际读到的版本和范围；不是全部历史，不代表旧授权可重用或模型已采用。", fontSize = 12.sp, lineHeight = 20.sp)
        reference?.getJSONObject("sourceJump")?.let { jump ->
            val message = jump.optString("kind") == "conversation_history_message"
            Text(if (message) "原消息 ${jump.getJSONObject("sourceRef").getString("sourceId")} · 原生修订 ${TaskMemoryEvidenceGuard.revision(jump.getJSONObject("sourceRef"))}\n${jump.getString("bodyFingerprint")}"
                else "原任务 ${jump.getString("taskId")} · ${jump.getString("runId")}\n${jump.getString("revision")}", fontSize = 11.sp, lineHeight = 18.sp)
            Text("原范围 ${jump.getInt("offset")}–${jump.getInt("offset").toLong() + jump.getInt("chars")} UTF-16 字符；内容指纹与原生整数修订是不同字段。", fontSize = 12.sp, lineHeight = 20.sp)
            if (!message) TextButton(onClick = { stop(); onOpenTask(jump.getString("taskId")) }) { Text("打开这个历史原任务") }
        }
        if (pending != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { stop(); error = "已停止等待，迟回不会展开；原任务未被取消。" }) { Text("停止等待") } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 21.sp) }
        body?.let { text ->
            MemoryQuotedText(text, identity, 16000)
            Button(onClick = { clipboard.setText(AnnotatedString("原会话精确引用\n$identity\n\n$text")); copied = true }) { Text(if (copied) "已复制精确引用" else "复制这个范围") }
        }
        if (reference != null) OutlinedButton(onClick = { read() }, enabled = pending == null) { Text("重新核对此版本") }
        TextButton(onClick = { stop(); onBack() }) { Text("返回本次来源") }
    }
}
