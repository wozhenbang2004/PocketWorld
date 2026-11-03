package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.conversation.InterruptedAttempts
import org.json.JSONArray
import org.json.JSONObject

/** Original task-audit reader, mounted in its conversation task card. No completion is written. */
@Composable
internal fun InterruptedAttemptJournal(taskId: String, sourceRevision: Long,
    readPage: (String, (JSONObject?, String?) -> Unit) -> Unit) {
    // Source retirement/forget/delete events dispose the old body and any in-flight callback.
    key(taskId, sourceRevision) { InterruptedAttemptJournalReader(taskId, readPage) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InterruptedAttemptJournalReader(taskId: String, readPage: (String, (JSONObject?, String?) -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var attempts by remember { mutableStateOf(emptyList<InterruptedAttempts.Attempt>()) }
    var retained by remember { mutableStateOf(JSONArray()) }
    var cursor by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val mounted = remember { booleanArrayOf(true) }
    DisposableEffect(taskId) { onDispose { mounted[0] = false } }
    fun load(next: String) {
        if (reading) return
        reading = true; failure = null
        readPage(next) { page, error ->
            if (!mounted[0]) return@readPage
            reading = false
            if (error != null || page == null) {
                attempts = emptyList(); retained = JSONArray(); loaded = false; cursor = ""
                failure = error ?: "原片段暂不可读取"; return@readPage
            }
            try {
                require(page.getString("taskId") == taskId && page.opt("assistantAnswer") == false)
                val incoming = InterruptedAttempts.eventsFromAuditPage(page)
                val combined = JSONArray()
                for (i in 0 until incoming.length()) combined.put(incoming.getJSONObject(i))
                val resetting = next.isEmpty() || combined.length() + retained.length() > 200
                if (!resetting) for (i in 0 until retained.length()) combined.put(retained.getJSONObject(i))
                val entries = page.getJSONArray("entries")
                val unavailable = (0 until entries.length()).any { entries.getJSONObject(it).optString("status") != "available" }
                retained = combined; cursor = page.optString("nextCursor"); more = page.optBoolean("hasMore")
                partial = partial || page.optBoolean("readLimited") || unavailable || resetting && next.isNotEmpty()
                attempts = InterruptedAttempts.fromEvents(combined, more || partial); loaded = true
            } catch (_: Exception) {
                attempts = emptyList(); retained = JSONArray(); loaded = false; cursor = ""
                failure = "原片段身份暂不可核对；未把旧正文当成新答复。"
            }
        }
    }
    TextButton(onClick = { open = true; if (!loaded) load("") }, modifier = Modifier.testTag("conversation_interrupted_open_" + taskId)) {
        Text("查看中断前已显示的文字", fontSize = 13.sp)
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp).verticalScroll(rememberScrollState())) {
            Text("原任务的中断片段", style = MaterialTheme.typography.titleLarge)
            Text("这些是此前已经显示的部分正文，不是已完成答复或工具结果。读取不会重新请求模型。", fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            attempts.forEachIndexed { index, attempt ->
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("片段 ${index + 1} · 已保留 ${attempt.retainedChars} 字", style = MaterialTheme.typography.titleSmall)
                Text("原执行 ${attempt.runId.takeLast(8)} · 调用 ${attempt.callId.takeLast(8)}", fontSize = 11.sp, color = SecondaryInk)
                if (!attempt.contiguous || attempt.conflicting) Text("原分片有缺口或冲突；未推测缺失内容。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                SelectionContainer { Text(attempt.text, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 12.dp).testTag("conversation_interrupted_text")) }
            }
            if (attempts.isEmpty() && !reading && failure == null) Text("本窗口没有可核对的中断正文。", fontSize = 13.sp)
            if (partial) Text("这里只显示有界窗口。部分历史来源不可核对或已停止使用，未显示其正文。", fontSize = 12.sp)
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            if (reading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            if (more || failure != null) TextButton(onClick = { load(if (loaded) cursor else "") }, enabled = !reading) { Text(if (failure != null) "重新核对来源" else "读取更早记录") }
            TextButton(onClick = { open = false }, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
        }
    }
}
