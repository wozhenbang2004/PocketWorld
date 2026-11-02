package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** Existing native owner review; views do not write a second profile or certify facts. */
@Composable internal fun MemoryCognitionPanel(
    responses: Map<String, JSONObject>, pending: Set<String>, evidenceVersion: String,
    request: (String, JSONObject) -> String
) {
    var open by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf("owner") }
    var reading by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<JSONObject?>(null) }
    var sourceReading by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf<JSONObject?>(null) }
    var mutation by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun send(command: String, payload: JSONObject): String? = try { request(command, payload) } catch (failed: Exception) { error = failed.message ?: "原生请求未发送"; null }
    LaunchedEffect(evidenceVersion) { view = null; source = null; reading = null; sourceReading = null }
    LaunchedEffect(responses) {
        reading?.let { id -> MemoryLearningReviewGuard.exactEnvelope(id, responses["cognitionProjection"])?.let { envelope -> view = envelope.optJSONObject("result").takeIf { envelope.optBoolean("ok") }; error = if (envelope.optBoolean("ok")) null else envelope.optString("error", "原认知读取失败"); reading = null } }
        sourceReading?.let { id -> MemoryLearningReviewGuard.exactEnvelope(id, responses["readAssertionEvidence"])?.let { envelope -> source = envelope.optJSONObject("result").takeIf { envelope.optBoolean("ok") }; error = if (envelope.optBoolean("ok")) null else envelope.optString("error", "确切原文不可读"); sourceReading = null } }
        mutation?.let { id -> MemoryLearningReviewGuard.exactEnvelope(id, responses["resolveAssertionConflictReviewed"])?.let { envelope -> mutation = null; source = null; view = null; error = if (envelope.optBoolean("ok")) "处理已保存，原证据保留；请重新读取当前认知。" else envelope.optString("error", "冲突版本已变化，请刷新"); } }
    }
    TextButton(onClick = { open = !open; view = null; source = null }) { Text(if (open) "收起主体认知与冲突" else "查看主体认知、历史与冲突…") }
    if (!open) return
    Text("认知是可重建视图，不是新的记忆权威。未知主体不归入主人画像；想象不参与现实事实。", fontSize = 12.sp)
    OutlinedTextField(subject, { subject = it; view = null; source = null }, label = { Text("稳定主体身份，主人为 owner") }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { error = null; source = null; reading = send("cognitionProjection", JSONObject().put("subjectId", subject.trim())) }, enabled = reading == null && mutation == null) { Text("读取当前版本") }
    error?.let { Text(it, fontSize = 12.sp) }
    view?.let { current ->
        Text("当前 ${current.optJSONArray("current")?.length() ?: 0} · 历史 ${current.optJSONArray("history")?.length() ?: 0} · 待审冲突 ${current.optJSONArray("pendingConflicts")?.length() ?: 0}", fontSize = 13.sp)
        for ((field, title) in listOf("current" to "当前认知", "history" to "历史 / 想象 / 未生效")) {
            Text(title, fontSize = 14.sp)
            cognitionRows(current.optJSONArray(field)).forEach { row ->
                SelectionContainer { Text(row.optString("text"), fontSize = 13.sp) }
                val a = row.optJSONObject("assertion") ?: JSONObject()
                Text("${a.optString("kind")} · ${row.optString("assertionStatus")} · 条件：${a.optString("condition")}", fontSize = 11.sp)
                cognitionRows(a.optJSONArray("evidenceSpans")).forEach { span -> TextButton(onClick = { source = null; sourceReading = send("readAssertionEvidence", JSONObject(span.toString())) }, enabled = sourceReading == null && mutation == null) { Text("回读确切原句与hash") } }
            }
        }
        cognitionRows(current.optJSONArray("pendingConflicts")).groupBy { row -> row.getJSONObject("assertion").let { it.optString("predicate") + "\u0000" + it.optString("condition") } }.forEach { (_, rows) ->
            MemoryConflictReview(rows, mutation == null && "resolveAssertionConflictReviewed" !in pending) { payload -> mutation = send("resolveAssertionConflictReviewed", payload) }
        }
    }
    source?.let { original -> Text("真实原span回读（不证明陈述为真，不授予执行权）", fontSize = 12.sp); SelectionContainer { Text(original.optString("text"), fontSize = 13.sp) } }
}

@Composable private fun MemoryConflictReview(rows: List<JSONObject>, enabled: Boolean, submit: (JSONObject) -> Unit) {
    val signature = rows.joinToString { it.optString("id") + ":" + it.optLong("revision") }
    var selected by remember(signature) { mutableStateOf(rows.first().optString("id")) }
    var coexist by remember(signature) { mutableStateOf(false) }
    var reason by remember(signature) { mutableStateOf("") }
    var confirm by remember(signature) { mutableStateOf(false) }
    Text("同一主体、条件与有效时点存在冲突，未自动覆盖原证据。", fontSize = 12.sp)
    rows.forEach { row -> Row { RadioButton(selected == row.optString("id"), { selected = row.optString("id"); coexist = false }, enabled = enabled); Text(row.optString("text"), modifier = Modifier.weight(1f), fontSize = 13.sp) } }
    Row { Checkbox(coexist, { coexist = it }, enabled = enabled); Text("有理由地共存，不选择唯一当前值", fontSize = 12.sp) }
    OutlinedTextField(reason, { reason = it }, label = { Text("为什么保留 / 共存") }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { confirm = true }, enabled = enabled && reason.trim().length in 1..1000) { Text("核对并处理这组精确版本…") }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(if (coexist) "保留这组事实共存？" else "选择这一当前事实？") }, text = { Text("原证据仍保留。所有旧版本认知视图和在途使用将失效；不把此决定当执行授权。理由：${reason.trim()}") }, confirmButton = { TextButton(onClick = { confirm = false; val refs = JSONArray(); rows.forEach { refs.put(JSONObject().put("id", it.getString("id")).put("revision", it.getLong("revision"))) }; submit(JSONObject().put("records", refs).put("decision", if (coexist) "coexist" else "replace").put("selectedId", if (coexist) "" else selected).put("reason", reason.trim())) }) { Text("确认所示版本") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("返回核对") } })
}
private fun cognitionRows(rows: JSONArray?): List<JSONObject> = if (rows == null) emptyList() else (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
