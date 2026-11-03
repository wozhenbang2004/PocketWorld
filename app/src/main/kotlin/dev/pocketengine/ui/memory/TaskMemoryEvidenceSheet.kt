package dev.pocketengine.ui.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** Task/answer-local native leaf. All reads and mutations are host callbacks into the unique memory module. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskMemoryEvidenceSheet(
    taskId: String,
    runId: String,
    requestId: String,
    answerId: String,
    receiptSnapshot: JSONObject?,
    pending: Set<String>,
    responses: Map<String, JSONObject>,
    onRequest: (String, JSONObject) -> String,
    onOpenTask: (String) -> Unit,
    onDismiss: () -> Unit,
    refreshRevision: Long = 0,
    rawCitations: List<JSONObject> = emptyList(),
    historyCitations: List<JSONObject> = emptyList()
) {
    var historyCitation by remember(taskId, runId, requestId, answerId) { mutableStateOf<JSONObject?>(null) }
    var historyLimit by remember(taskId, runId) { mutableStateOf(10) }
    var rawCitation by remember(taskId, runId, requestId, answerId) { mutableStateOf<JSONObject?>(null) }
    var rawCitationLimit by remember(taskId, runId) { mutableStateOf(10) }
    var rawReturnPosition by remember(taskId, runId) { mutableStateOf<Int?>(null) }
    var receipt by remember(taskId, runId, requestId, answerId) { mutableStateOf<JSONObject?>(null) }
    var receiptRequest by remember(taskId, runId, requestId, answerId) { mutableStateOf<String?>(null) }
    var sourceRequest by remember(taskId, runId, requestId, answerId) { mutableStateOf<String?>(null) }
    var expectedSource by remember(taskId, runId, requestId, answerId) { mutableStateOf<JSONObject?>(null) }
    var sourceDocument by remember(taskId, runId, requestId, answerId) { mutableStateOf<JSONObject?>(null) }
    var traceTask by remember(taskId, runId, requestId, answerId) { mutableStateOf<JSONObject?>(null) }
    var replacementRequest by remember(taskId, runId, requestId, answerId) { mutableStateOf<String?>(null) }
    var replacements by remember(taskId, runId, requestId, answerId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var reviewing by remember(taskId, runId, requestId, answerId) { mutableStateOf<JSONObject?>(null) }
    var mutation by remember(taskId, runId, requestId, answerId) { mutableStateOf<PendingReview?>(null) }
    var requiresRefresh by remember(taskId, runId, requestId, answerId) { mutableStateOf(false) }
    var error by remember(taskId, runId, requestId, answerId) { mutableStateOf<String?>(null) }
    var notice by remember(taskId, runId, requestId, answerId) { mutableStateOf<String?>(null) }
    var taskSearchOpen by remember(taskId, runId, requestId, answerId) { mutableStateOf(false) }
    var taskQuery by remember(taskId, runId, requestId, answerId) { mutableStateOf("") }
    var taskSearchRequest by remember(taskId, runId, requestId, answerId) { mutableStateOf<TaskSearchRequest?>(null) }
    var taskPage by remember(taskId, runId, requestId, answerId) { mutableStateOf<JSONObject?>(null) }
    var taskRows by remember(taskId, runId, requestId, answerId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var observedRefresh by remember(taskId, runId, requestId, answerId) { mutableStateOf(refreshRevision) }
    val scroll = rememberScrollState()
    val busy = mutation != null || pending.any { it in memoryMutations }
    val reading = pending.any { it in memoryReads }
    var sourceTab by remember(taskId, runId, requestId, answerId) { mutableIntStateOf(0) }
    var expandedSourceIds by remember(taskId, runId, requestId, answerId) { mutableStateOf(setOf<String>()) }
    LaunchedEffect(sourceTab) { scroll.scrollTo(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(rawCitation?.toString(), historyCitation?.toString()) {
        if (rawCitation != null || historyCitation != null) scroll.scrollTo(0)
        else rawReturnPosition?.let { saved -> scroll.scrollTo(saved); rawReturnPosition = null }
    }

    fun readReceipt() {
        if (receiptRequest != null && "readTaskMemoryReceipt" in pending) return
        error = null
        receiptRequest = onRequest("readTaskMemoryReceipt", JSONObject().put("taskId", taskId).put("runId", runId).put("requestId", requestId).put("answerId", answerId))
    }
    fun submit(command: String, payload: JSONObject) {
        if (busy) return
        error = null
        val id = onRequest(command, payload)
        mutation = PendingReview(id, command, JSONObject(payload.toString()), JSONObject(receipt!!.toString()))
    }
    fun searchTasks(cursor: String = "") {
        if (taskSearchRequest != null) return
        error = null
        val payload = JSONObject().put("taskId", taskId).put("runId", runId).put("requestId", requestId).put("answerId", answerId)
            .put("query", taskQuery).put("cursor", cursor).put("limit", 20)
        taskSearchRequest = TaskSearchRequest(onRequest("searchTaskMemoryTasks", payload), taskQuery, cursor)
    }
    LaunchedEffect(refreshRevision, busy) {
        if (refreshRevision != observedRefresh && !busy) {
            observedRefresh = refreshRevision
            receipt = null; receiptRequest = null; reviewing = null; sourceDocument = null; sourceRequest = null; expectedSource = null; replacements = emptyList(); traceTask = null; rawCitation = null; historyCitation = null
            readReceipt()
        }
    }
    LaunchedEffect(taskId, runId, requestId, answerId, receiptSnapshot?.toString()) {
        reviewing = null; sourceDocument = null; sourceRequest = null; replacements = emptyList(); error = null; requiresRefresh = false
        if (receiptSnapshot == null) { receipt = null; readReceipt() }
        else try { receipt = TaskMemoryEvidenceGuard.receipt(taskId, runId, receiptSnapshot, requestId, answerId) }
        catch (failure: Exception) { receipt = null; error = failure.message ?: "回执不可读，请刷新。" }
    }
    LaunchedEffect(responses["readTaskMemoryReceipt"]?.toString(), receiptRequest) {
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(receiptRequest, responses["readTaskMemoryReceipt"]) ?: return@LaunchedEffect
        receiptRequest = null
        if (!envelope.getBoolean("ok")) { error = envelope.optString("error", "本次回执暂不可读，请重试。"); return@LaunchedEffect }
        try {
            val result = envelope.getJSONObject("result")
            val refreshed = TaskMemoryEvidenceGuard.receipt(taskId, runId, result.optJSONObject("receipt") ?: result, requestId, answerId)
            val previous = reviewing
            receipt = refreshed
            reviewing = previous?.takeIf { runCatching { TaskMemoryEvidenceGuard.reviewed(refreshed, it) }.isSuccess }
            sourceDocument = null; sourceRequest = null; replacements = emptyList(); requiresRefresh = false; error = null
        } catch (failure: Exception) { receipt = null; error = failure.message ?: "未取得本次运行的真实回执。" }
    }
    LaunchedEffect(responses["readTaskMemorySource"]?.toString(), sourceRequest) {
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(sourceRequest, responses["readTaskMemorySource"]) ?: return@LaunchedEffect
        sourceRequest = null
        if (!envelope.getBoolean("ok")) { error = envelope.optString("error", "来源暂不可读。"); return@LaunchedEffect }
        try {
            val document = envelope.getJSONObject("result")
            require(document.has("sourceRef")) { "本次来源回执不可用，请重新核对。" }
            require(document.opt("executionAuthorized") == false && document.opt("modelUseVerified") == false) { "来源正文不能自证授权或模型采用。" }
            val ref = document.getJSONObject("sourceRef")
            require(TaskMemoryEvidenceGuard.sameSource(expectedSource, ref)) { "返回的来源版本与所点来源不一致" }
            // Only an exact current body supplied by the native SourceReader can be shown.
            sourceDocument = JSONObject(document.toString()).also { if (it.optString("status") != "current") it.remove("text") }
        } catch (failure: Exception) { sourceDocument = null; error = failure.message ?: "来源正文未确认。" }
    }
    LaunchedEffect(responses["listTaskMemoryReplacements"]?.toString(), replacementRequest) {
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(replacementRequest, responses["listTaskMemoryReplacements"]) ?: return@LaunchedEffect
        replacementRequest = null
        if (!envelope.getBoolean("ok")) { error = envelope.optString("error", "正确事实列表暂不可读。"); return@LaunchedEffect }
        try {
            val activeReceipt = receipt ?: error("本次回执已结束")
            val rows = envelope.getJSONObject("result").getJSONArray("records")
            replacements = (0 until rows.length()).map { JSONObject(rows.getJSONObject(it).toString()) }.filter {
                TaskMemoryEvidenceGuard.sameSpace(activeReceipt, it) && it.optBoolean("active") && TaskMemoryEvidenceGuard.revision(it) > 0 && it.opt("id") != reviewing?.opt("id")
            }
        } catch (failure: Exception) { replacements = emptyList(); error = failure.message ?: "可替代记录未确认。" }
    }
    val activeMutation = mutation
    LaunchedEffect(taskSearchRequest?.requestId, responses["searchTaskMemoryTasks"]?.toString()) {
        val request = taskSearchRequest ?: return@LaunchedEffect
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(request.requestId, responses["searchTaskMemoryTasks"]) ?: return@LaunchedEffect
        taskSearchRequest = null
        if (request.query != taskQuery || !taskSearchOpen) return@LaunchedEffect
        if (!envelope.getBoolean("ok")) { error = envelope.optString("error", "任务列表暂不可读。"); return@LaunchedEffect }
        try {
            val result = envelope.getJSONObject("result")
            require(result.optString("coverage") in setOf("recent_200_and_exact_task_id", "full_task_store")) { "任务检索范围未说明" }
            val rows = result.getJSONArray("tasks")
            val incoming = (0 until rows.length()).map { JSONObject(rows.getJSONObject(it).toString()) }
            for (row in incoming) {
                require(row.optString("taskId").isNotBlank() && row.optString("recordType") == "task_record" && row.opt("formalMemory") == false &&
                    row.opt("executionIndependentlyVerified") == false && row.opt("executionAuthorized") == false) { "任务记录类型或核验边界不明确" }
                receipt?.let { require(TaskMemoryEvidenceGuard.sameSpace(it, row)) { "任务记录来自不同空间" } }
            }
            taskRows = (if (request.cursor.isBlank()) incoming else taskRows + incoming).distinctBy { it.getString("taskId") }
            taskPage = JSONObject(result.toString())
        } catch (failure: Exception) { error = failure.message ?: "任务检索结果未确认。" }
    }
    LaunchedEffect(activeMutation?.requestId, responses[activeMutation?.command]?.toString()) {
        val request = activeMutation ?: return@LaunchedEffect
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(request.requestId, responses[request.command]) ?: return@LaunchedEffect
        mutation = null
        if (!envelope.getBoolean("ok")) { requiresRefresh = true; error = envelope.optString("error", "操作未确认，请先重新核对回执；输入仍保留。"); return@LaunchedEffect }
        try {
            require(receipt?.opt("receiptId") == request.receipt.opt("receiptId") && receipt?.opt("ownerId") == request.receipt.opt("ownerId") && receipt?.opt("spaceId") == request.receipt.opt("spaceId")) { "当前回执已切换，旧操作结果不能用于此页" }
            notice = TaskMemoryEvidenceGuard.feedback(envelope.getJSONObject("result"), request.receipt, request.command, request.payload)
            receipt = null
            reviewing = null; sourceDocument = null; replacements = emptyList(); readReceipt()
        } catch (failure: Exception) { requiresRefresh = true; error = failure.message ?: "写回回执不完整，请刷新后核对，勿直接重试。" }
    }
    LaunchedEffect(reviewing?.optString("id"), sourceDocument?.toString(), error) { scroll.scrollTo(0) }
    BackHandler { if (busy) onDismiss() else if (historyCitation != null) historyCitation = null else if (rawCitation != null) rawCitation = null else if (traceTask != null) traceTask = null else if (reviewing != null) reviewing = null else if (sourceDocument != null) sourceDocument = null else if (taskSearchOpen) taskSearchOpen = false else onDismiss() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("本次任务记忆", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("返回回答") }
            }
            val overview = historyCitation == null && rawCitation == null && traceTask == null && reviewing == null && sourceDocument == null && !taskSearchOpen
            if (overview) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("本次参考" to (receipt?.optJSONArray("records")?.length() ?: -1), "原件读取" to rawCitations.size, "会话历史" to historyCitations.size).forEachIndexed { index, (label, count) ->
                    FilterChip(selected = sourceTab == index, onClick = { sourceTab = index }, label = { Text(if (count < 0) "$label · 待核对" else "$label · $count") })
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy || reading || receiptRequest != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (busy) EvidenceText("操作已提交，正在核对；返回不会撤销已提交的操作。")
            notice?.let { EvidenceText(it) }
            if (reviewing == null && !taskSearchOpen && traceTask == null) error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 21.sp) }
            val proof = receipt
            if (historyCitation == null && rawCitation == null && traceTask == null && reviewing == null && sourceDocument == null && !taskSearchOpen && sourceTab == 1) {
                Text("原件读取回执", style = MaterialTheme.typography.titleMedium)
                if (rawCitations.isEmpty()) EvidenceText("本次没有可核对的原件读取范围；不会用当前搜索补造历史来源。")
                run {
                EvidenceText("只列出本任务真实工具回执中的精确来源；打开后再次核对原指纹和范围。保留安全输出不等于远端wire全文，读取或显示不代表模型已采用。")
                rawCitations.takeLast(rawCitationLimit).asReversed().forEachIndexed { index, source ->
                    TextButton(onClick = {
                        try { val checked = TaskMemoryEvidenceGuard.rawCitation(taskId, runId, source); rawReturnPosition = scroll.value; rawCitation = checked; error = null }
                        catch (failure: Exception) { error = failure.message ?: "原引用不可核对" }
                    }) { Text("${index + 1} · ${if (source.optString("sourceId").startsWith("task:$taskId:operation:")) "原操作" else if (source.optString("sourceId").startsWith("task:$taskId:run:$runId:output:")) "保留安全输出" else "保留消息"} · ${source.optInt("offset")}–${source.optInt("offset").toLong() + source.optInt("chars")} 字符", fontSize = 12.sp) }
                }
                if (rawCitationLimit < rawCitations.size) TextButton(onClick = { rawCitationLimit = minOf(100, rawCitationLimit + 20) }) { Text("展开更多读取范围") }
                HorizontalDivider()
                }
            }
            if (historyCitation == null && rawCitation == null && traceTask == null && reviewing == null && sourceDocument == null && !taskSearchOpen && sourceTab == 2) {
                Text("找回的会话历史", style = MaterialTheme.typography.titleMedium)
                if (historyCitations.isEmpty()) EvidenceText("本次未记录历史找回范围；这里只呈现真实回执。")
                run {
                    EvidenceText("只列本任务真实历史工具回执；只覆盖原会话尚保留的消息和任务操作，不等于完整长期历史。")
                    historyCitations.takeLast(historyLimit).asReversed().forEachIndexed { index, source ->
                        val jump = source.getJSONObject("sourceJump")
                        TextButton(onClick = {
                            try {
                                val checked = dev.pocketengine.features.memory.ConversationHistory.checkedCitation(source)
                                require(checked.getString("callerTaskId") == taskId && checked.getString("callerRunId") == runId) { "历史引用不属于这个原调用任务" }
                                rawReturnPosition = scroll.value; historyCitation = checked; error = null
                            } catch (failure: Exception) { error = failure.message ?: "历史引用不可核对" }
                        }) { Text("${index + 1} · ${if (jump.optString("kind") == "conversation_history_message") "原会话消息" else "历史任务操作"} · ${jump.optInt("offset")}–${jump.optInt("offset").toLong() + jump.optInt("chars")} 字符", fontSize = 12.sp) }
                    }
                    if (historyLimit < historyCitations.size) TextButton(onClick = { historyLimit = minOf(100, historyLimit + 20) }) { Text("展开更多历史范围") }
                    HorizontalDivider()
                }
            }
            when {
                historyCitation != null -> ConversationHistoryCitationPanel(historyCitation!!, responses, onRequest, onOpenTask, { historyCitation = null }, refreshRevision)
                rawCitation != null -> TaskMemoryRawCitationPanel(taskId, runId, rawCitation!!, responses, onRequest, onOpenTask, { rawCitation = null }, refreshRevision)
                traceTask != null -> TaskMemoryTracePanel(traceTask!!.getString("taskId"), traceTask!!.optString("runId"), responses, onRequest, onOpenTask, { traceTask = null }, refreshRevision)
                taskSearchOpen -> TaskMemoryTaskSourcePanel(taskQuery, taskRows, taskPage, taskSearchRequest != null, error,
                    onQueryChanged = { taskQuery = it; taskRows = emptyList(); taskPage = null; taskSearchRequest = null; error = null },
                    onSearch = { searchTasks() }, onMore = { taskPage?.optString("nextCursor")?.takeIf { it.isNotBlank() }?.let { searchTasks(it) } },
                    onOpenTask = onOpenTask, onBack = { taskSearchOpen = false; taskSearchRequest = null; error = null }, onReadTrace = { traceTask = it })
                overview && sourceTab != 0 -> Unit
                proof == null -> {
                    Text(if (reading || receiptRequest != null) "正在读取本次运行的来源回执…" else "未记录本次提供的记忆来源", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    EvidenceText("回执缺失或重启后无法核对时，不能用新的搜索结果追认本次来源。你仍可返回原任务。")
                }
                reviewing != null -> {
                    val record = reviewing!!
                    TaskMemoryReviewPanel(record, replacements, busy, error,
                        onLoadReplacements = { error = null; replacementRequest = onRequest("listTaskMemoryReplacements", TaskMemoryEvidenceGuard.reviewed(proof, record)) },
                        onCorrect = { text -> try { submit("correctTaskMemoryReviewed", TaskMemoryEvidenceGuard.correction(proof, record, text)) } catch (failure: Exception) { error = failure.message } },
                        onReplace = { kept -> try { submit("supersedeTaskMemoryReviewed", TaskMemoryEvidenceGuard.replacement(proof, record, kept)) } catch (failure: Exception) { error = failure.message } },
                        onForget = { try { submit("forgetTaskMemoryReviewed", TaskMemoryEvidenceGuard.reviewed(proof, record)) } catch (failure: Exception) { error = failure.message } },
                        onBack = { reviewing = null; error = null }, requiresRefresh = requiresRefresh, onRefresh = { readReceipt() })
                }
                sourceDocument != null -> {
                    val document = sourceDocument!!
                    Text(document.optString("title", "原始来源"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    EvidenceText("来源版本 ${TaskMemoryEvidenceGuard.revision(document.getJSONObject("sourceRef"))}")
                    EvidenceText("这是宿主保存的精确来源版本，不能单凭此正文认定任务已经成功。")
                    if (document.optString("status") == "current" && document.opt("text") is String) TaskMemorySourceDocument(document)
                    else EvidenceText("原来源正文暂不可用或版本已变化。这里不会用当前任务摘要替代当时的证据。")
                    dev.pocketengine.features.memory.TaskMemoryTrace.sourceTaskId(document.getJSONObject("sourceRef")).takeIf { it.isNotBlank() }?.let { sourceTask ->
                        TextButton(onClick = { traceTask = JSONObject().put("taskId", sourceTask) }) { Text("查阅这个原任务保存的执行记录") }
                    }
                    TextButton(onClick = { sourceDocument = null; error = null }) { Text("返回本次来源") }
                }
                else -> {
                    Text(when (proof.optString("phase")) { "provided_to_request" -> "本次提供给模型的记忆来源"; "prepared" -> "仅检索，尚未提供给模型"; else -> "本次来源回执不可用" }, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    TaskMemoryReferenceBoundary(proof)
                    if (proof.optString("status") == "invalidated") EvidenceText("部分来源已失效。仍有效的条目可以核对；失效条目已收起旧正文。")
                    else if (proof.optString("status") != "current") EvidenceText("本次回执已失效或暂不可核对；旧正文已收起。")
                    val rows = proof.getJSONArray("records")
                    if (rows.length() == 0) EvidenceText(if (proof.optString("phase") == "provided_to_request") "此回执没有提供记忆条目。" else "本次没有可核对的记忆条目。")
                    for (index in 0 until rows.length()) {
                        val record = rows.getJSONObject(index)
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${index + 1} · ${if (record.optString("selection") == "core") "核心参考" else "检索命中"}", style = MaterialTheme.typography.titleSmall)
                            Text(when (proof.optString("phase")) { "provided_to_request" -> "已提供 · 采用未证实"; "prepared" -> "仅检索"; else -> "提供状态不可核对" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        EvidenceText("版本 ${TaskMemoryEvidenceGuard.revision(record)} · ${rowStatus(record.optString("status"))}")
                        if (proof.optBoolean("selectionDiagnosticsAvailable")) EvidenceText(if (record.optString("selectionReason") == "owner_core") "选入原因：你明确选择的核心参考" else "选入原因：匹配这轮任务的检索词")
                        if (proof.optString("status") in setOf("current", "invalidated") && record.optString("status") == "current") {
                            MemoryQuotedText(record.getString("text"), record.getString("id") + TaskMemoryEvidenceGuard.revision(record))
                            EvidenceText(record.optString("source", "来源声明不代表事实已核实"))
                        }
                        val refs = record.optJSONArray("sourceRefs") ?: JSONArray()
                        if (refs.length() == 0) EvidenceText("无外部任务或消息来源；可能是你明确保存的记忆，不能据此推断原始证据。")
                        val sourceKey = record.getString("id") + ":" + TaskMemoryEvidenceGuard.revision(record)
                        if (refs.length() > 0) TextButton(onClick = { expandedSourceIds = if (sourceKey in expandedSourceIds) expandedSourceIds - sourceKey else expandedSourceIds + sourceKey }) { Text(if (sourceKey in expandedSourceIds) "收起引用链" else "核对引用链 · ${refs.length()} 个原始来源") }
                        if (sourceKey in expandedSourceIds) for (r in 0 until refs.length()) {
                            val source = refs.getJSONObject(r)
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("来源 ${r + 1} · ${if (source.optString("kind") == "task_evidence") "任务证据" else "消息来源"}", style = MaterialTheme.typography.labelLarge)
                                EvidenceText("记忆版本 ${TaskMemoryEvidenceGuard.revision(record)} → 原件版本 ${TaskMemoryEvidenceGuard.revision(source)}")
                                TextButton(onClick = {
                                    expectedSource = JSONObject(source.toString()); sourceDocument = null; error = null
                                    sourceRequest = onRequest("readTaskMemorySource", TaskMemoryEvidenceGuard.target(proof)
                                        .put("id", record.getString("id")).put("expectedRevision", TaskMemoryEvidenceGuard.revision(record)).put("sourceRef", JSONObject(source.toString())))
                                }, enabled = !busy && sourceRequest == null && proof.optString("phase") == "provided_to_request") { Text("查看这个版本的来源") }
                                dev.pocketengine.features.memory.TaskMemoryTrace.sourceTaskId(source).takeIf { it.isNotBlank() }?.let { sourceTask ->
                                    TextButton(onClick = { traceTask = JSONObject().put("taskId", sourceTask) }, enabled = !busy) { Text("查阅原任务的执行记录") }
                                    TextButton(onClick = { onOpenTask(sourceTask) }, enabled = !busy) { Text("打开原任务") }
                                }
                            }
                            }
                        }
                        if (proof.optString("phase") == "provided_to_request" && proof.optString("status") in setOf("current", "invalidated") && record.optString("status") == "current")
                            TextButton(onClick = { reviewing = JSONObject(record.toString()); error = null; replacements = emptyList() }, enabled = !busy) { Text("纠正或遗忘") }
                        else EvidenceText("请刷新后核对当前状态；不能修改这个旧版本。")
                    }
                }
            }
            if (traceTask == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { reviewing = null; sourceDocument = null; readReceipt() }, enabled = !busy && !reading) { Text("重新核对回执") }
                TextButton(onClick = { onOpenTask(taskId) }, enabled = !busy) { Text("查看本任务") }
            }
            if (!taskSearchOpen && traceTask == null) TextButton(onClick = { taskSearchOpen = true; reviewing = null; sourceDocument = null; error = null }, enabled = !busy) { Text("查找原任务记录") }
            Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TaskMemoryReferenceBoundary(receipt: JSONObject) {
    var expanded by remember(receipt.optString("receiptId")) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("参考来源 · 不代表已采用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起说明" else "来源说明") }
    }
    if (expanded) {
        EvidenceText("回执只说明本次提供了什么参考，不能证明模型实际采用，也不代表任务成功或获得新的执行权限。")
        TaskMemorySelectionDetails(receipt)
    }
}

@Composable
private fun TaskMemorySelectionDetails(receipt: JSONObject) {
    val details = TaskMemoryEvidenceGuard.selectionDetails(receipt)
    var expanded by remember(receipt.optString("receiptId")) { mutableStateOf(false) }
    if (details == null) {
        if (receipt.optString("phase") == "provided_to_request") EvidenceText("此旧回执未保存当时的选择预算。")
        return
    }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起选择与省略说明" else "查看本次选择与省略") }
    if (!expanded) return
    EvidenceText("当时提供核心参考 ${details.getInt("coreSelected")} 条、任务匹配 ${details.getInt("retrievedSelected")} 条；正文及元数据共 ${details.getInt("charsUsed")} / ${details.getInt("maxChars")} 字符。")
    EvidenceText("先为任务匹配保留位置，再分配核心参考；空余预算可借用。以下是当时的选择，不代表当前全部记忆。")
    if (details.optBoolean("queryTruncated")) EvidenceText("检索词过长，保留了开头和最新尾部；原任务要求仍完整保留。")
    val reasons = details.getJSONObject("omissionCounts")
    val labels = mapOf("record_limit" to "条数预算已满", "character_limit" to "剩余字符预算不足", "record_too_large" to "整条记录超过预算", "source_or_record_changed" to "来源或记录在选择时已变化")
    if (reasons.length() == 0) EvidenceText("候选核心和已命中的任务参考均已提供。")
    for ((reason, label) in labels) if (reasons.optInt(reason) > 0) EvidenceText("$label：${reasons.getInt(reason)} 条")
    val omitted = details.getJSONArray("omitted")
    if (omitted.length() > 0) {
        EvidenceText("省略版本（仅标识，不保留旧正文）：")
        for (i in 0 until omitted.length()) {
            val row = omitted.getJSONObject(i)
            EvidenceText("${row.getString("id")} · 版本 ${TaskMemoryEvidenceGuard.revision(row)} · ${labels[row.getString("reason")].orEmpty()}")
        }
        if (details.optBoolean("omissionsTruncated")) EvidenceText("这里只展开前 32 个标识；上方计数包含其余省略条目。")
    }
}

/** A readable view of the exact native snapshot; the original serialized record remains available. */
@Composable
internal fun TaskMemorySourceDocument(document: JSONObject) {
    val text = document.getString("text")
    val identity = document.getJSONObject("sourceRef").toString()
    val snapshot = remember(identity, text) { runCatching { JSONObject(text) }.getOrNull()?.takeIf {
        document.getJSONObject("sourceRef").optString("kind") == "task_evidence" && it.optInt("version") == 1 &&
            it.optString("evidenceRole") == "owner_confirmed_result_summary_not_step_verification" && it.opt("executionIndependentlyVerified") == false &&
            listOf("goal", "summary", "ownerFeedback").all { key -> it.opt(key) is String }
    } }
    var raw by remember(identity, text) { mutableStateOf(false) }
    if (snapshot == null) MemoryQuotedText(text, identity, 2000)
    else {
        Text("任务目标", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        MemoryQuotedText(snapshot.getString("goal"), "$identity:goal", 1200)
        Text("确认时的结果摘要", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        MemoryQuotedText(snapshot.getString("summary"), "$identity:summary", 2000)
        if (snapshot.getString("ownerFeedback").isNotBlank()) { Text("你的确认补充", fontSize = 13.sp, fontWeight = FontWeight.Medium); MemoryQuotedText(snapshot.getString("ownerFeedback"), "$identity:feedback", 1000) }
        if (snapshot.optBoolean("truncated")) EvidenceText("来源快照在保存时已截断；这里显示的是当时保存的范围。")
        EvidenceText("这是确认时保存的摘要快照，不包含完整消息、工具结果或父链。")
        TextButton(onClick = { raw = !raw }) { Text(if (raw) "收起摘要快照数据" else "查看保存的摘要快照数据") }
        if (raw) MemoryQuotedText(text, "$identity:raw", 2000)
    }
}

private data class PendingReview(val requestId: String, val command: String, val payload: JSONObject, val receipt: JSONObject)
private data class TaskSearchRequest(val requestId: String, val query: String, val cursor: String)
private val memoryMutations = setOf("correctTaskMemoryReviewed", "supersedeTaskMemoryReviewed", "forgetTaskMemoryReviewed")
private val memoryReads = setOf("readTaskMemoryReceipt", "readTaskMemorySource", "listTaskMemoryReplacements")
private fun rowStatus(state: String): String = when (state) {
    "current" -> "当前可核对"; "record_changed" -> "记忆已被修改"; "record_removed" -> "记忆已遗忘或移除"; "superseded" -> "已由明确保存的事实替代"
    "expired" -> "记忆已过期"; "source_changed" -> "原来源版本已变化"; "source_deleted" -> "原来源已删除"; "source_forgotten" -> "原来源已遗忘"
    "record_inactive" -> "记忆已停用"; "provenance_mismatch" -> "来源关联已变化"
    "revoked" -> "本次参考已撤销"; "run_changed" -> "任务已进入另一轮运行"; "task_cancelled" -> "任务已取消"; "task_unavailable" -> "任务暂不可读"; else -> "原来源暂不可核对"
}
@Composable private fun EvidenceText(text: String) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 20.sp) }
