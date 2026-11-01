package dev.pocketengine.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** One exact current record, inside the originating evidence sheet; no separate memory manager. */
@Composable
fun TaskMemoryReviewPanel(
    record: JSONObject,
    replacements: List<JSONObject>,
    busy: Boolean,
    error: String?,
    onLoadReplacements: () -> Unit,
    onCorrect: (String) -> Unit,
    onReplace: (JSONObject) -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
    requiresRefresh: Boolean = false,
    onRefresh: () -> Unit = {}
) {
    var mode by remember(record.toString()) { mutableStateOf("choose") }
    var draft by remember(record.toString()) { mutableStateOf(record.optString("text")) }
    var kept by remember(record.toString()) { mutableStateOf<JSONObject?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("纠正或遗忘这条记忆", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 21.sp) }
        if (requiresRefresh) {
            Text("请先重新核对回执，再决定下一次操作；当前输入仍保留。", fontSize = 12.sp, lineHeight = 20.sp)
            TextButton(onClick = onRefresh, enabled = !busy) { Text("重新核对这个版本") }
        }
        Text("正在核对版本 ${TaskMemoryEvidenceGuard.revision(record)}。你的操作只改变记忆；任务是否成功仍由任务证据判断。", fontSize = 12.sp, lineHeight = 20.sp)
        Text("记录 ${record.optString("id").take(8)} · ${if (record.optString("spaceId") == "private") "私人空间" else "空间 ${record.optString("spaceId")}"}", fontSize = 12.sp, lineHeight = 20.sp)
        MemoryQuotedText(record.optString("text"), record.optString("id") + TaskMemoryEvidenceGuard.revision(record))
        when (mode) {
            "choose" -> {
                Button(onClick = { mode = "correct" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("改写这条记忆") }
                OutlinedButton(onClick = { mode = "replace"; onLoadReplacements() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("用已保存的正确记忆替代") }
                TextButton(onClick = { mode = "forget" }, enabled = !busy) { Text("遗忘这条及衍生内容", color = MaterialTheme.colorScheme.error) }
            }
            "correct" -> {
                Text("修改后的正文保留原来源关联。若来源不对，请返回核对原任务，或遗忘后从正确来源另行保存。", fontSize = 12.sp, lineHeight = 20.sp)
                OutlinedTextField(draft, { draft = it.take(4000) }, label = { Text("你确认的正确内容") }, minLines = 3, maxLines = 8, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("${draft.length}/4000", fontSize = 12.sp)
                Button(onClick = { onCorrect(draft) }, enabled = !busy && !requiresRefresh && draft.trim().isNotEmpty() && draft.trim() != record.optString("text").trim(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在保存并核对" else "保存纠正并停用旧版本") }
            }
            "replace" -> {
                Text("选择一条已经正式保存的正确事实。确认后旧事实及受影响的衍生内容会被遗忘；不会自动判断两条事实是否矛盾。", fontSize = 12.sp, lineHeight = 20.sp)
                if (replacements.isEmpty()) Text("暂没有可选择的有效记录。可先在既有记忆管理中保存正确事实，再回来刷新。", fontSize = 13.sp, lineHeight = 21.sp)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(replacements, key = { it.getString("id") }) { candidate ->
                    OutlinedButton(onClick = { kept = JSONObject(candidate.toString()) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Column { Text(if (kept?.opt("id") == candidate.opt("id")) "已选择 · 版本 ${TaskMemoryEvidenceGuard.revision(candidate)}" else "选择版本 ${TaskMemoryEvidenceGuard.revision(candidate)}", fontSize = 12.sp)
                            Text("记录 ${candidate.getString("id").take(8)} · ${candidate.optString("source", "正式记录")}", fontSize = 12.sp)
                            Text(candidate.optString("text").take(240), fontSize = 13.sp, lineHeight = 21.sp) }
                    }
                    }
                }
                TextButton(onClick = onLoadReplacements, enabled = !busy) { Text("刷新可替代记录") }
                val validKept = kept?.takeIf { selected -> replacements.any { it.toString() == selected.toString() } }
                validKept?.let { selected ->
                    Text("将保留所选记录的当前内容与来源：", fontSize = 12.sp, lineHeight = 20.sp)
                    MemoryQuotedText(selected.getString("text"), selected.getString("id"))
                }
                Button(onClick = { validKept?.let(onReplace) }, enabled = !busy && !requiresRefresh && validKept != null, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在核对并替代" else "确认用所选记录替代") }
            }
            "forget" -> {
                Text("这会删除当前记录，并停用相关旧来源版本及衍生内容。旧正文不能在这里撤销恢复；已经发送给模型的内容无法收回。", fontSize = 14.sp, lineHeight = 22.sp)
                Button(onClick = onForget, enabled = !busy && !requiresRefresh, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在遗忘并核对" else "确认遗忘并停用") }
            }
        }
        TextButton(onClick = { if (mode == "choose") onBack() else { mode = "choose"; kept = null } }, enabled = !busy) { Text(if (mode == "choose") "返回本次来源" else "返回操作选择") }
    }
}

@Composable
internal fun MemoryQuotedText(text: String, identity: String, previewChars: Int = 300) {
    var expanded by remember(identity, text) { mutableStateOf(false) }
    SelectionContainer { Text(if (expanded || text.length <= previewChars) text else text.take(previewChars), fontSize = 14.sp, lineHeight = 22.sp) }
    if (text.length > previewChars) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起全文" else "查看完整内容（当前显示前${previewChars}字）") }
}
