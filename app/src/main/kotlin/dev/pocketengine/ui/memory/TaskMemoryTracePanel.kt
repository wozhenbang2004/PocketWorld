package dev.pocketengine.ui.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.memory.TaskMemoryTrace
import org.json.JSONObject

/** An exact range selected from a native raw-tool receipt, not a model-authored deep link. */
@Composable
fun TaskMemoryRawCitationPanel(
    taskId: String,
    runId: String,
    citation: JSONObject,
    responses: Map<String, JSONObject>,
    onRequest: (String, JSONObject) -> String,
    onOpenTask: (String) -> Unit,
    onBack: () -> Unit,
    refreshRevision: Long = 0
) {
    val identity = citation.toString()
    val selected = remember(taskId, runId, identity) { runCatching { TaskMemoryEvidenceGuard.rawCitation(taskId, runId, citation) } }
    val reference = selected.getOrNull()
    val requests = remember(taskId, runId, identity) { TaskMemoryTraceViewState.Requests() }
    var pending by remember(taskId, runId, identity) { mutableStateOf<String?>(null) }
    var command by remember(taskId, runId, identity) { mutableStateOf("") }
    var submitted by remember(taskId, runId, identity) { mutableStateOf<JSONObject?>(null) }
    var bootstrap by remember(taskId, runId, identity) { mutableStateOf(false) }
    var body by remember(taskId, runId, identity) { mutableStateOf<String?>(null) }
    var totalChars by remember(taskId, runId, identity) { mutableStateOf(0) }
    var error by remember(taskId, runId, identity) { mutableStateOf<String?>(selected.exceptionOrNull()?.message) }
    var observedRefresh by remember(taskId, runId, identity) { mutableStateOf(refreshRevision) }
    var copied by remember(taskId, runId, identity) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    fun stop() { pending?.let(requests::abandon); pending = null; submitted = null; body = null; copied = false }
    fun read(snapshot: String? = null) {
        val source = reference ?: return
        if (pending != null) return
        val input = JSONObject().put("sourceId", source.getString("sourceId")).put("revision", source.getString("revision"))
            .put("offset", source.getInt("offset")).put("maxChars", maxOf(64, source.getInt("chars")))
        val operation = source.getString("sourceId").startsWith("task:$taskId:operation:")
        val output = source.getString("sourceId").startsWith("task:$taskId:run:$runId:output:")
        val payload = if (operation || output) TaskMemoryTrace.rawCitation(taskId, runId, input) else
            TaskMemoryTrace.rawMessageTraceRequest(taskId, runId, if (snapshot == null) JSONObject(input.toString()).put("offset", 0).put("maxChars", 64) else input, snapshot.orEmpty())
        command = if (output) "readTaskNativeOutputAudit" else if (operation) "readTaskMemoryTraceOperation" else "readTaskMemoryTrace"
        bootstrap = !operation && !output && snapshot == null
        body = null; copied = false; error = null; submitted = JSONObject(payload.toString())
        try { pending = requests.begin(onRequest(command, payload)) }
        catch (failure: Exception) { submitted = null; error = failure.message ?: "原来源暂不可读" }
    }
    fun accept(checked: JSONObject) {
        val source = reference ?: kotlin.error("原引用缺失")
        require(checked.optString("sourceId") == source.getString("sourceId") && checked.optString("revision") == source.getString("revision")) { "来源内容版本已变化，请回原查询重新选择。" }
        val range = checked.getJSONObject("readRange")
        require(range.getInt("offset") == source.getInt("offset") && range.getInt("chars") >= source.getInt("chars")) { "没有读到原引用的精确范围。" }
        val text = checked.getString("body").take(source.getInt("chars"))
        require(text.length == source.getInt("chars") && (text.isEmpty() || !Character.isHighSurrogate(text.last()))) { "原引用字符边界不可核对。" }
        totalChars = range.getInt("totalChars"); body = text; error = null
    }
    LaunchedEffect(taskId, runId, identity) { if (reference != null) read() }
    LaunchedEffect(refreshRevision) {
        if (observedRefresh != refreshRevision) { observedRefresh = refreshRevision; stop(); error = "来源或任务状态已变化，旧正文已收起；请明确重新核对。" }
    }
    val response = responses[command]
    LaunchedEffect(response?.toString(), pending) {
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(pending, response) ?: return@LaunchedEffect
        val request = submitted ?: return@LaunchedEffect
        val wasBootstrap = bootstrap
        pending = null; submitted = null
        if (!envelope.getBoolean("ok")) { body = null; error = envelope.optString("error", "原引用读取未完成。"); return@LaunchedEffect }
        try {
            val result = envelope.getJSONObject("result")
            if (command == "readTaskNativeOutputAudit") {
                val checked = TaskMemoryTrace.checkedRawSource(result, taskId, runId, JSONObject(request.toString()).also { it.remove("taskId"); it.remove("runId") })
                require(checked.optBoolean("auditOnly") && !checked.optBoolean("modelSourceAdmission") && checked.optString("status") == "current") { "保留原件不可读或已变化；未显示替代正文。" }
                accept(checked)
            } else if (command == "readTaskMemoryTraceOperation") {
                val checked = TaskMemoryTrace.checkedOperationPage(result, request)
                require(checked.getString("status") == "current") { if (checked.optString("status") == "source_changed") "来源内容版本已变化，请回原查询重新选择。" else "原来源不可读或超过读取范围，未展示替代正文。" }
                accept(checked)
            } else {
                val source = reference ?: kotlin.error("原引用缺失")
                val checked = TaskMemoryTrace.checkedPage(result, request)
                require(checked.getString("status") == "current") { "原保存记录已变化或不可读，请回原查询重新选择。" }
                val rows = checked.getJSONArray("entries")
                val entry = (0 until rows.length()).map { rows.getJSONObject(it) }.singleOrNull { it.optString("sourceId") == source.getString("sourceId") }
                    ?: kotlin.error("原保留消息已不在此位置，不会用任务摘要补造。")
                require(entry.optString("revision") == source.getString("revision")) { "来源内容版本已变化，请回原查询重新选择。" }
                if (wasBootstrap && (source.getInt("offset") != 0 || source.getInt("chars") > entry.getJSONObject("readRange").getInt("chars"))) read(checked.getString("snapshot"))
                else accept(entry)
            }
        } catch (failure: Exception) { body = null; copied = false; error = failure.message ?: "原引用的版本或范围无法核对。" }
    }
    BackHandler { stop(); onBack() }
    DisposableEffect(identity) { onDispose { pending?.let(requests::abandon) } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("本次读取的原证据", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("仅展示真实native回执指向的保留内容版本与范围。原件审计可保留；不表示来源仍可用于模型、模型已全文读到、任务已成功或已保存正式记忆。", fontSize = 12.sp, lineHeight = 20.sp)
        reference?.let { source ->
            Text("${source.getString("sourceId")}\n${source.getString("revision")}", fontSize = 11.sp, lineHeight = 18.sp)
            Text("原范围 ${source.getInt("offset")}–${source.getInt("offset").toLong() + source.getInt("chars")} UTF-16 字符${if (body != null) " / $totalChars" else ""}；内容指纹不是原生修订号。", fontSize = 12.sp, lineHeight = 20.sp)
        }
        if (pending != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { stop(); error = "已停止等待；迟到回复不会展开，原任务未被取消。" }) { Text("停止等待") } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 21.sp) }
        body?.let { text ->
            MemoryQuotedText(text, identity, 16000)
            Button(onClick = { clipboard.setText(AnnotatedString("原任务 $taskId · $runId\n$identity\n\n$text")); copied = true }) { Text(if (copied) "已复制精确引用" else "复制这个范围") }
        }
        if (reference != null) OutlinedButton(onClick = { read() }, enabled = pending == null) { Text("重新核对此版本") }
        TextButton(onClick = { stop(); onOpenTask(taskId) }) { Text("打开原任务") }
        TextButton(onClick = { stop(); onBack() }) { Text("返回本次来源") }
    }
}

/** Bounded native reads inside the existing source sheet; no model call, task creation or replay. */
@Composable
internal fun TaskMemoryTracePanel(
    taskId: String,
    initialRunId: String,
    responses: Map<String, JSONObject>,
    onRequest: (String, JSONObject) -> String,
    onOpenTask: (String) -> Unit,
    onBack: () -> Unit,
    refreshRevision: Long = 0
) {
    val navigation = remember(taskId, initialRunId) { TaskMemoryTraceViewState.Navigation(taskId, initialRunId) }
    val requests = remember(taskId, initialRunId) { TaskMemoryTraceViewState.Requests() }
    var viewRevision by remember(taskId, initialRunId) { mutableStateOf(0L) }
    var operation by remember(taskId, initialRunId) { mutableStateOf<TaskMemoryTraceViewState.Operation?>(null) }
    var copied by remember(taskId, initialRunId) { mutableStateOf(false) }
    var pending by remember(taskId, initialRunId) { mutableStateOf<String?>(null) }
    var submitted by remember(taskId, initialRunId) { mutableStateOf<JSONObject?>(null) }
    var page by remember(taskId, initialRunId) { mutableStateOf<JSONObject?>(null) }
    var error by remember(taskId, initialRunId) { mutableStateOf<String?>(null) }
    val location = remember(navigation, viewRevision) { navigation.location() }
    val section = location.getString("section")
    val query = location.getString("query")
    val focusedSource = location.getString("sourceId")
    val operationPage = remember(operation, viewRevision) { operation?.page() }
    val clipboard = LocalClipboardManager.current
    fun dispatch(command: String, payload: JSONObject): String {
        return requests.begin(onRequest(command, payload))
    }
    fun stopWaiting() {
        val ids = listOfNotNull(pending, operation?.pendingId())
        ids.forEach { requests.abandon(it) }
        pending = null; submitted = null; operation?.cancel(); copied = false; viewRevision++
    }
    fun read(cursor: String = "", keepSnapshot: Boolean = true) {
        if (pending != null) return
        val payload = navigation.request(cursor, keepSnapshot)
        try { page = null; error = null; submitted = JSONObject(payload.toString()); pending = dispatch("readTaskMemoryTrace", payload) }
        catch (failure: Exception) { error = failure.message ?: "原执行记录暂不可读" }
        viewRevision++
    }
    fun readOperation(offset: Int = 0) {
        val selected = operation ?: return
        if (selected.pendingId() != null) return
        selected.cancel(); copied = false; error = null
        try { val payload = selected.request(offset, 4000); selected.begin(dispatch("readTaskMemoryTraceOperation", payload), payload) }
        catch (failure: Exception) { error = failure.message ?: "原操作暂不可读" }
        viewRevision++
    }
    fun back() {
        stopWaiting()
        if (operation != null) { operation = null; error = null; if (page == null) read(navigation.location().getString("cursor")) }
        else if (navigation.back()) read(navigation.location().getString("cursor"))
        else onBack()
    }
    BackHandler { back() }
    DisposableEffect(navigation) { onDispose { operation?.cancel() } }
    LaunchedEffect(taskId, initialRunId, refreshRevision) {
        stopWaiting(); page = null
        if (operation != null) { operation?.invalidate(); viewRevision++ }
        else read(navigation.location().getString("cursor"))
    }
    val response = responses["readTaskMemoryTrace"]
    LaunchedEffect(response?.toString(), pending) {
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(pending, response) ?: return@LaunchedEffect
        val request = submitted ?: return@LaunchedEffect
        pending = null
        if (!navigation.matches(request)) return@LaunchedEffect
        if (!envelope.getBoolean("ok")) { error = envelope.optString("error", "执行记录读取未完成"); return@LaunchedEffect }
        try {
            val checked = TaskMemoryTrace.checkedPage(envelope.getJSONObject("result"), request)
            if (checked.getString("status") != "current") {
                page = null
                error = if (checked.getString("status") == "snapshot_changed") "记录已变化，旧正文已收起。请从开头重新读取。" else "这一轮没有可读取的原生执行记录。"
            } else { navigation.accepted(checked); page = checked; viewRevision++ }
        } catch (failure: Exception) { page = null; error = failure.message ?: "记录身份或已读范围无法核对" }
    }
    val operationResponse = responses["readTaskMemoryTraceOperation"]
    LaunchedEffect(operationResponse?.toString(), operation?.pendingId(), viewRevision) {
        val selected = operation ?: return@LaunchedEffect
        val id = selected.pendingId()
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(id, operationResponse) ?: return@LaunchedEffect
        try {
            if (!envelope.getBoolean("ok")) selected.fail(id, envelope.optString("error", "原操作读取未完成"))
            else selected.accept(id, envelope.getJSONObject("result"))
        } catch (failure: Exception) { selected.fail(id, failure.message ?: "原操作来源版本或范围无法核对") }
        copied = false; viewRevision++
    }
    val selected = operation
    if (selected != null) {
        TaskMemoryOperationPage(selected, operationPage, selected.pendingId() != null, selected.status(), selected.error(), copied, error,
            onRead = { readOperation() }, onMore = { readOperation(selected.nextOffset()) },
            onCopy = {
                try { clipboard.setText(AnnotatedString(selected.citation())); copied = true }
                catch (failure: Exception) { copied = false; error = failure.message ?: "这一页已失效，请重新核对后复制"; viewRevision++ }
            },
            onStop = { stopWaiting() }, onBack = { back() },
            onOpenTask = { val original = selected.reference().getString("taskId"); stopWaiting(); onOpenTask(original) })
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("保存的执行记录", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("消息、工具回执和父链分别读取；确认摘要不能替代这些记录，也不能单独证明目标已经完成。", fontSize = 12.sp, lineHeight = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((key, label) in listOf("messages" to "消息", "operations" to "工具回执", "events" to "宿主事件", "parents" to "父任务链"))
                TextButton(onClick = { navigation.section(key); read() }, enabled = pending == null && section != key, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(label, fontSize = 12.sp) }
        }
        OutlinedTextField(query, { navigation.query(it.take(500)); page = null; error = null; viewRevision++ }, label = { Text("在这一范围的保存正文中查找") }, minLines = 1, maxLines = 2, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { read(keepSnapshot = false) }, enabled = pending == null) { Text("从开头读取") }
            TextButton(onClick = { onOpenTask(navigation.location().getString("taskId")) }, enabled = pending == null) { Text("打开当前原任务") }
        }
        if (pending != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { stopWaiting(); error = "已停止等待本次读取；返回不会取消原任务。" }) { Text("停止等待") } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 21.sp) }
        page?.let { current ->
            val run = current.getJSONObject("run")
            if (current.optString("taskGoalPreview").isNotBlank()) Text(current.getString("taskGoalPreview"), fontSize = 14.sp, lineHeight = 22.sp)
            Text(if (run.optString("runId").isBlank()) "未保存执行轮次；以下仍可查看任务级事件或父链。" else "第 ${run.optInt("coordinatorEpoch")} 轮 · ${run.optString("status")}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((field, label) in listOf("previousRunId" to "上一轮", "nextRunId" to "下一轮")) run.optString(field).takeIf { it.isNotBlank() }?.let { id ->
                    TextButton(onClick = { navigation.run(id, current.getString("snapshot")); read() }, enabled = pending == null) { Text(label) }
                }
            }
            Text("检查点未记录可核对的原生修订号；这里使用当前保存内容的指纹锁定本次读取。", fontSize = 12.sp, lineHeight = 19.sp)
            Text(when (section) {
                "messages" -> "只覆盖保留的检查点消息，原始消息总数未知。工具回复只在保存了对应调用时显示父链接。"
                "operations" -> "这是所选轮次的宿主操作账本；没有保存关联时，不按时间或相似文字猜测父调用。"
                "events" -> "这是任务级宿主事件。部分旧事件未保存执行轮次，不能全部归入当前轮。"
                else -> "父链仅来自已保存的 parent 链接，每次最多核对 8 级。"
            }, fontSize = 12.sp, lineHeight = 19.sp)
            if (section == "parents" && current.getJSONObject("coverage").optString("parents") != "complete") Text(when (current.getJSONObject("coverage").optString("parents")) {
                "depth_limit" -> "已核对 8 级，后面的父链尚未读取。"; "cycle" -> "保存的父链接形成循环，已停止继续展开。"
                "parent_unavailable" -> "原父任务暂不可读，未展开它的正文。"; else -> "原父链接无法继续核对，未补造缺失记录。"
            }, fontSize = 12.sp)
            val entries = current.getJSONArray("entries").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            Text("匹配 ${current.getInt("matchedCount")} 条；本页展示 ${entries.size} 个正文范围、${current.getInt("charsReturned")} 字符。其他正文尚未在本页展开。", fontSize = 12.sp, lineHeight = 19.sp)
            if (focusedSource.isNotBlank()) TextButton(onClick = { back() }, enabled = pending == null) { Text("返回原查找位置") }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(entries, key = { _, entry -> entry.getString("sourceId") + entry.getJSONObject("readRange").getInt("offset") }) { _, entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HorizontalDivider()
                        Text("保存位置 ${entry.getInt("index") + 1} · ${when (entry.optString("kind")) { "checkpoint_message" -> "保留消息"; "operation_receipt" -> "操作回执"; "host_event" -> "宿主事件"; else -> "父任务链接" }}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        val range = entry.getJSONObject("readRange")
                        Text("本页范围 ${range.getInt("offset")}–${range.getInt("offset") + range.getInt("chars")} / ${range.getInt("totalChars")} 字符${if (range.optBoolean("complete")) " · 此条正文已完整显示" else " · 此条只显示一个范围"}", fontSize = 11.sp)
                        if (entry.optString("kind") == "operation_receipt") TextButton(onClick = {
                            try { operation = TaskMemoryTraceViewState.Operation(current.getString("taskId"), current.getString("runId"), entry); readOperation() }
                            catch (failure: Exception) { operation = null; error = failure.message ?: "原操作引用不可用" }
                        }, enabled = pending == null) { Text("按此版本分页核对原操作") }
                        MemoryQuotedText(entry.getString("body"), entry.getString("sourceId") + entry.getString("revision") + range.getInt("offset"), 4000)
                        entry.optJSONObject("parent")?.let { parent ->
                            Text("对应调用：${parent.getString("toolCallId")} · ${parent.getString("sourceId")}", fontSize = 11.sp, lineHeight = 18.sp)
                            if (parent.optBoolean("toolCallIdTruncated")) Text("调用编号这里只显示开头，完整编号保留在父调用正文中。", fontSize = 11.sp)
                            TextButton(onClick = { try { navigation.focus(parent.getString("sourceId"), current.getString("snapshot")); read() } catch (failure: Exception) { error = failure.message } }, enabled = pending == null) { Text("定位这条父调用") }
                        }
                        Text("sourceId：${entry.getString("sourceId")}\n内容修订指纹：${entry.getString("revision")}", fontSize = 11.sp, lineHeight = 18.sp)
                        entry.optJSONObject("originalMessageSource")?.let { source -> Text("当时保存的机主消息来源：${source.getString("sourceId")} · 原版本 ${source.getLong("revision")}；当前消息未在此重新核对。", fontSize = 11.sp, lineHeight = 18.sp) }
                        entry.optString("parentTaskId").takeIf { it.isNotBlank() }?.let { id ->
                            TextButton(onClick = {
                                try { navigation.parent(id, entry.optString("parentRunId"), current.getString("snapshot")); read(keepSnapshot = false) }
                                catch (failure: Exception) { error = failure.message }
                            }, enabled = pending == null && entry.optBoolean("parentReadable")) { Text(if (entry.optBoolean("parentReadable")) "查阅这个父任务" else "父任务暂不可访问") }
                        }
                    }
                }
            }
            if (current.optString("nextCursor").isNotBlank()) OutlinedButton(onClick = { read(current.getString("nextCursor")) }, enabled = pending == null, modifier = Modifier.fillMaxWidth()) { Text("继续读取下一段") }
            Text("一致性核对会扫描当前保存的轮次记录、事件和有界父链；本页只返回上面注明的正文范围。指纹不能冒充原生修订号。保存前的脱敏、截断、未保留图片或缺失日志无法在此补回。", fontSize = 12.sp, lineHeight = 19.sp)
        }
        TextButton(onClick = { back() }) { Text(if (navigation.canBack()) "返回原查找位置" else "返回来源") }
    }
}

@Composable
private fun TaskMemoryOperationPage(
    operation: TaskMemoryTraceViewState.Operation, page: JSONObject?, pending: Boolean, status: String, readError: String, copied: Boolean, error: String?,
    onRead: () -> Unit, onMore: () -> Unit, onCopy: () -> Unit, onStop: () -> Unit, onBack: () -> Unit, onOpenTask: () -> Unit
) {
    val reference = operation.reference()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("原操作的固定版本", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("只核对你所选操作的原版本，不按任务摘要、时间或相似文字替换来源。正文不能证明任务成功或授予执行权限。", fontSize = 12.sp, lineHeight = 20.sp)
        Text("${reference.getString("sourceId")}\n内容指纹：${reference.getString("revision")}", fontSize = 11.sp, lineHeight = 18.sp)
        Text("这是保存内容的指纹，不是原生修订计数；只覆盖这条操作，不代表完整执行记录。", fontSize = 12.sp, lineHeight = 19.sp)
        if (pending) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = onStop) { Text("停止等待") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        if (readError.isNotBlank()) Text(readError, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        if (page != null) {
            val range = page.getJSONObject("readRange")
            Text("本页范围 ${range.getInt("offset")}–${range.getInt("offset") + range.getInt("chars")} / ${range.getInt("totalChars")} 字符", fontSize = 12.sp)
            MemoryQuotedText(page.getString("body"), reference.toString() + range.getInt("offset"), 4000)
            if (range.optBoolean("complete")) Text("这一条保存正文已在本页完整显示。", fontSize = 12.sp)
            else Text(if (range.optBoolean("hasMore")) "后面的正文尚未在本页展开。" else "已到这条正文末尾；本页引用只包含上方范围。", fontSize = 12.sp)
            Button(onClick = onCopy, enabled = !pending) { Text(if (copied) "已复制本页引用" else "复制这一页的精确引用") }
            Text("只复制已核对的本页正文、原任务和轮次、来源指纹与范围；不会发送、执行任务或保存为正式记忆。", fontSize = 12.sp, lineHeight = 19.sp)
            if (operation.nextOffset() >= 0) OutlinedButton(onClick = onMore, enabled = !pending) { Text("继续核对下一段原文") }
        } else if (!pending) Text(when (status) {
            "source_changed" -> "这条原来源版本已变化，旧正文已收起。请返回原查找位置，重新选择并核对引用；不会自动换成新版本。"
            "read_limited" -> "原操作超过当前读取范围，未展示不完整的替代正文。"
            "unavailable" -> "这条原操作暂不可读；不会用任务摘要替代原文。"
            "stale" -> "原记录状态已变化，旧正文已收起；请重新核对这一固定引用。"
            "cancelled" -> "已停止等待这次读取，迟到回复不会重新展开。已经开始的读取可能仍在收尾，原任务没有被取消。"
            else -> "尚未取得这个原版本的已核对正文。"
        }, fontSize = 13.sp, lineHeight = 21.sp)
        OutlinedButton(onClick = onRead, enabled = !pending) { Text("从开头重新核对此版本") }
        TextButton(onClick = onOpenTask, enabled = !pending) { Text("打开这条引用的原任务") }
        TextButton(onClick = onBack) { Text("返回原查找位置") }
    }
}
