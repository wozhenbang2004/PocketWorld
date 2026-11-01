package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.features.memory.SourceRef
import org.json.JSONArray
import org.json.JSONObject

/** Opens original private task metadata, then an exact native saved event/range. No webpage,
 * attachment or model text is retained just by opening this owner picker. */
@Composable
internal fun SavedEvidenceLearningControl(state: PocketUiState, controller: PocketWorldController) {
    var open by remember { mutableStateOf(false) }
    var sourceId by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { open = true }) { Text("从原任务已保存证据提炼候选") }
    if (open) SavedEvidencePicker(state, controller, { open = false }) { ref ->
        sourceId = ref.getString("sourceId"); open = false
    }
    sourceId?.let { selected -> SourceLearningDialog("task_evidence", selected, state, controller) { sourceId = null } }
}

@Composable
private fun SavedEvidencePicker(state: PocketUiState, controller: PocketWorldController,
    close: () -> Unit, selected: (JSONObject) -> Unit) {
    var query by remember { mutableStateOf("") }
    var taskId by remember { mutableStateOf<String?>(null) }
    var taskRequest by remember { mutableStateOf<String?>(null) }
    var sourceRequest by remember { mutableStateOf<String?>(null) }
    var selectRequest by remember { mutableStateOf<String?>(null) }
    var taskPage by remember { mutableStateOf<JSONObject?>(null) }
    var sourcePage by remember { mutableStateOf<JSONObject?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    // The parent NativeMemory page owns the domain; closing this picker is not closing that page.
    fun search(cursor: String = "") {
        taskRequest = controller.requestOwnerDomain("taskMemory", "searchTaskMemoryTasks",
            JSONObject().put("query", query).put("cursor", cursor).put("limit", 20))
        taskPage = null; sourcePage = null; taskId = null; failure = null
    }
    fun loadSources(task: String, before: Long = 0) {
        taskId = task; sourcePage = null; failure = null
        sourceRequest = controller.requestOwnerDomain("taskMemory", "listSavedMemorySources",
            JSONObject().put("taskId", task).put("beforeEventOrder", before).put("limit", 20))
    }
    val responses = state.ownerResponses("taskMemory")
    LaunchedEffect(Unit) { search() }
    LaunchedEffect(responses["searchTaskMemoryTasks"]?.toString(), taskRequest) {
        val envelope = responses["searchTaskMemoryTasks"]?.takeIf { it.optString("requestId") == taskRequest } ?: return@LaunchedEffect
        taskRequest = null
        if (envelope.optBoolean("ok")) taskPage = envelope.optJSONObject("result") else failure = envelope.optString("error", "原任务读取未完成")
    }
    LaunchedEffect(responses["listSavedMemorySources"]?.toString(), sourceRequest) {
        val envelope = responses["listSavedMemorySources"]?.takeIf { it.optString("requestId") == sourceRequest } ?: return@LaunchedEffect
        sourceRequest = null
        if (envelope.optBoolean("ok") && envelope.optJSONObject("result")?.optString("taskId") == taskId) sourcePage = envelope.optJSONObject("result") else failure = envelope.optString("error", "原保存来源读取未完成")
    }
    LaunchedEffect(responses["selectSavedMemorySource"]?.toString(), selectRequest) {
        val envelope = responses["selectSavedMemorySource"]?.takeIf { it.optString("requestId") == selectRequest } ?: return@LaunchedEffect
        selectRequest = null
        if (!envelope.optBoolean("ok")) { failure = envelope.optString("error", "原来源选择未完成"); return@LaunchedEffect }
        runCatching {
            val result = envelope.getJSONObject("result"); require(result.opt("modelCalled") == false)
            val ref = SourceRef.fromJson(result.getJSONObject("sourceRef")); require(ref.ownerId == "owner" && ref.spaceId == "private" && ref.kind == "task_evidence")
            require(dev.pocketengine.features.memory.TaskSavedEvidence.address(ref.sourceId).taskId == taskId)
            selected(ref.toJson())
        }.onFailure { failure = "原来源选择回执不完整，请重新读取；未调用模型。" }
    }
    val busy = taskRequest != null || sourceRequest != null || selectRequest != null
    fun rows(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
    AlertDialog(onDismissRequest = close, title = { Text("选择原任务已保存证据") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("只选择原生已读取文档范围或已批准保存的安全网页证据。不会扫描当前网页、自动保存整页或把消息正文当权限。预览后仍需另行确认模型使用。")
            failure?.let { Text(it, color = Danger) }
            if (taskId == null) {
                OutlinedTextField(query, { query = it }, label = { Text("搜索原任务或准确任务ID") }, maxLines = 2)
                TextButton(onClick = { search() }, enabled = !busy && query.length <= 500) { Text("搜索") }
                rows(taskPage?.optJSONArray("tasks")).forEach { task -> TextButton(onClick = { loadSources(task.getString("taskId")) }, enabled = !busy) { Text(task.optString("goal", task.optString("taskId"))) } }
                taskPage?.optString("nextCursor")?.takeIf { it.isNotEmpty() }?.let { cursor -> TextButton(onClick = { search(cursor) }, enabled = !busy) { Text("下一页原任务") } }
                if (taskPage != null && rows(taskPage?.optJSONArray("tasks")).isEmpty()) Text("此原检索窗口没有任务；可用准确ID继续定位。")
            } else {
                Text("原任务：$taskId")
                TextButton(onClick = { taskId = null; sourcePage = null }, enabled = !busy) { Text("返回原任务列表") }
                rows(sourcePage?.optJSONArray("entries")).forEach { event ->
                    Text("${event.optString("title", event.optString("type"))} · 本机事件 ${event.optLong("eventSeq")}")
                    if (event.optString("status") == "available") TextButton(onClick = {
                        selectRequest = controller.requestOwnerDomain("taskMemory", "selectSavedMemorySource", JSONObject()
                            .put("taskId", taskId).put("runId", event.getString("runId")).put("eventSeq", event.getLong("eventSeq")).put("offset", 0).put("chars", 24000))
                    }, enabled = !busy) { Text("预览这个原保存范围（最多24000字符）") }
                    else Text(if (event.optString("status") == "read_limited") "原事件超出本次读取窗口，未加载全文。" else "旧回执缺少准确安全交付或原件版本；需回原来源重新读取。", color = SecondaryInk)
                }
                if (sourcePage?.optBoolean("hasMore") == true) TextButton(onClick = { loadSources(taskId!!, sourcePage!!.optLong("nextEventOrder")) }, enabled = !busy) { Text("查看较早保存证据") }
                if (sourcePage != null && rows(sourcePage?.optJSONArray("entries")).isEmpty()) Text("这次原事件窗口没有已保存来源；不代表已自动保存网页或整份文档。")
            }
            if (busy) Text("正在核对原生记录，尚未调用模型…")
        }
    }, confirmButton = { TextButton(onClick = close) { Text("返回") } })
}
