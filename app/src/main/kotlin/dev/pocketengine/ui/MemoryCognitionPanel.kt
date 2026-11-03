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
    TextButton(onClick = { open = !open; view = null; source = null; reading = null; sourceReading = null }) { Text(if (open) "收起主体认知与冲突" else "查看主体认知、历史与冲突…") }
    if (!open) return
    Text("认知是可重建视图，不是新的记忆权威。未知主体不归入主人画像；想象不参与现实事实。", fontSize = 12.sp)
    MemoryOwnerHistorySearch(responses, evidenceVersion, mutation == null, request,
        onClearEvidence = { source = null; sourceReading = null },
        onEvidence = { span -> source = null; sourceReading = send("readAssertionEvidence", span) },
        onSubject = { id -> subject = id; source = null; view = null; reading = send("cognitionProjection", JSONObject().put("subjectId", id)) })
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

/** Same existing inline fields/buttons/selection hierarchy, inside the private cognition review. */
@Composable internal fun MemoryOwnerHistorySearch(
    responses: Map<String, JSONObject>, evidenceVersion: String, enabled: Boolean,
    request: (String, JSONObject) -> String, onClearEvidence: () -> Unit,
    onEvidence: ((JSONObject) -> Unit)?, onSubject: ((String) -> Unit)?,
    displayedScope: JSONObject? = null, onOriginalSource: ((JSONObject) -> Unit)? = null
) {
    val scopeKey = (displayedScope?.optString("ownerId") ?: "owner") + ":" + (displayedScope?.optString("spaceId") ?: "private")
    var query by remember(scopeKey) { mutableStateOf("") }
    var requestedQuery by remember(scopeKey) { mutableStateOf("") }
    var reading by remember(scopeKey) { mutableStateOf<String?>(null) }
    var recordReading by remember(scopeKey) { mutableStateOf<String?>(null) }
    var page by remember(scopeKey) { mutableStateOf<JSONObject?>(null) }
    var header by remember(scopeKey) { mutableStateOf<JSONObject?>(null) }
    var record by remember(scopeKey) { mutableStateOf<JSONObject?>(null) }
    var error by remember(scopeKey) { mutableStateOf<String?>(null) }
    val live = remember(scopeKey) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val currentEvidence by rememberUpdatedState(evidenceVersion)
    DisposableEffect(scopeKey) { onDispose { live.set(false) } }
    fun clear() { reading = null; recordReading = null; page = null; header = null; record = null; error = null; onClearEvidence() }
    fun send(command: String, input: JSONObject): String? = try { request(command, input) } catch (failed: Exception) { error = failed.message ?: "原生旧事查阅未开始"; null }
    fun search(cursor: String) { requestedQuery = query.trim(); error = null; header = null; record = null; recordReading = null; onClearEvidence(); reading = send("searchHistory", JSONObject().put("query", requestedQuery).put("cursor", cursor).put("limit", 12)) }
    LaunchedEffect(evidenceVersion, scopeKey) { clear() }
    LaunchedEffect(responses) {
        reading?.let { id -> MemoryLearningReviewGuard.exactEnvelope(id, responses["searchHistory"])?.let { envelope ->
            page = MemoryHistoryReviewGuard.page(id, requestedQuery, envelope, displayedScope)
            error = if (!envelope.optBoolean("ok")) envelope.optString("error", "旧事来源暂不可核，请稍后重搜") else if (page == null) "旧事结果已过期或不属于本次搜索，请重新读取。" else null
            reading = null
        } }
        recordReading?.let { id -> MemoryLearningReviewGuard.exactEnvelope(id, responses["readHistoryRecord"])?.let { envelope ->
            record = MemoryHistoryReviewGuard.record(id, header, envelope, displayedScope)
            error = if (!envelope.optBoolean("ok")) envelope.optString("error", "确切旧版本不可读") else if (record == null) "旧事版本已变化，请重新搜索。" else null
            recordReading = null
        } }
    }
    Text("查旧事只读仍保留、原来源可核的版本，不改变当前认知；存储摘要不是原始证据。", fontSize = 12.sp)
    OutlinedTextField(query, { query = it; clear() }, label = { Text("旧事关键词 / 记载日期（UTC），如2023-04-06") }, modifier = Modifier.fillMaxWidth())
    val capturedQuery = query.trim(); val capturedEvidence = evidenceVersion
    TextButton(onClick = { if (live.get() && enabled && currentEvidence == capturedEvidence && query.trim() == capturedQuery) search("") }, enabled = enabled && reading == null && query.length <= 500) { Text("查旧事（只读）") }
    if (reading != null || recordReading != null) Text("正在核对旧版本与原来源…", fontSize = 12.sp)
    error?.let { Text(it, fontSize = 12.sp) }
    page?.let { current ->
        val version = current.getString("resultVersion"); val rows = current.getJSONArray("matches")
        Text("本页 ${rows.length()} / 匹配 ${current.optInt("totalMatches")}。未列出不代表从未保存；缺原源/过保留期不回流，历史包不自动激活。", fontSize = 12.sp)
        if (current.optInt("unverifiedArchiveRecords") > 0) Text("另有 ${current.optInt("unverifiedArchiveRecords")} 条未核归档，仅保留原只读归档入口。", fontSize = 12.sp)
        cognitionRows(rows).forEach { row ->
            val frozen = JSONObject(row.toString())
            TextButton(onClick = {
                if (live.get() && enabled && currentEvidence == capturedEvidence && query.trim() == requestedQuery && page?.optString("resultVersion") == version) {
                    header = frozen; record = null; onClearEvidence(); recordReading = send("readHistoryRecord", JSONObject().put("id", frozen.getString("id")).put("revision", frozen.getLong("revision")))
                }
            }, enabled = enabled && reading == null && recordReading == null) { Text(historyStatus(row.optString("temporalStatus")) + " · " + row.getString("textPreview")) }
        }
        val cursor = current.optString("nextCursor")
        if (cursor.isNotEmpty()) TextButton(onClick = { if (live.get() && enabled && currentEvidence == capturedEvidence && query.trim() == requestedQuery && page?.optString("resultVersion") == version) search(cursor) }, enabled = enabled && reading == null && recordReading == null) { Text("下一页旧事") }
    }
    record?.let { selected ->
        Text(historyStatus(selected.optString("temporalStatus")) + " · 已存记忆文本（不是原件）", fontSize = 13.sp)
        SelectionContainer { Text(selected.optString("text"), fontSize = 13.sp) }
        val assertion = selected.optJSONObject("assertion")
        assertion?.let { typed ->
            Text(typed.optString("subjectLabel") + " · " + typed.optString("predicate") + "：" + typed.optString("object"), fontSize = 13.sp)
            Text("来源类型：" + when (typed.optString("kind")) { "user_said" -> "原发言标签，仍以确切原span为证"; "inferred" -> "推断，不是主人原话"; "told" -> "转述 / 记录，不是原话认证"; else -> "未核类型" } + " · 条件：" + typed.optString("condition"), fontSize = 11.sp)
        }
        Text("记载事件：" + historyDate(assertion?.opt("eventTime")) + " · 保存：" + historyDate(selected.opt("createdAt")), fontSize = 11.sp)
        val spans = cognitionRows(assertion?.optJSONArray("evidenceSpans"))
        if (spans.isEmpty()) Text("未附确切原文span；本页只能证明保存了这段记忆，不能把摘要当原件。", fontSize = 12.sp)
        if (onEvidence != null) spans.forEach { span ->
            TextButton(onClick = { if (live.get() && enabled && currentEvidence == capturedEvidence && query.trim() == requestedQuery && record?.optString("id") == selected.optString("id") && record?.optLong("revision") == selected.optLong("revision")) onEvidence?.invoke(JSONObject(span.toString())) }, enabled = enabled) { Text("回读这条旧事的确切原句与hash") }
        }
        if (onOriginalSource != null) cognitionRows(selected.optJSONArray("sourceRefs")).forEach { source ->
            TextButton(onClick = { if (live.get() && enabled && currentEvidence == capturedEvidence && query.trim() == requestedQuery && record?.optString("id") == selected.optString("id") && record?.optLong("revision") == selected.optLong("revision")) onOriginalSource(JSONObject(source.toString())) }, enabled = enabled) { Text("回读原选来源范围（非原话认证）") }
        }
        if (onSubject != null) assertion?.optString("subjectId")?.takeIf { it.isNotBlank() }?.let { id -> TextButton(onClick = { if (live.get() && enabled && currentEvidence == capturedEvidence && query.trim() == requestedQuery && record?.optString("id") == selected.optString("id") && record?.optLong("revision") == selected.optLong("revision")) onSubject?.invoke(id) }, enabled = enabled) { Text("查看此主体当前与历史的对照") } }
    }
}
private fun historyStatus(value: String): String = when (value) { "historical" -> "历史版本（不是现在的事实）"; "current" -> "当前记录（只读查阅）"; "pending_conflict" -> "冲突待核（不选作现实答案）"; else -> "主体 / 发生时间未记载" }
private fun historyDate(value: Any?): String = if (value is Number) runCatching { java.time.Instant.ofEpochMilli(value.toLong()).toString() + " UTC" }.getOrDefault("未记载") else "未记载"
