package dev.pocketengine.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/** Reuses the transferred review/search leaves with the real native controller worker. */
@Composable
internal fun TaskMemoryWorkflowSheet(controller: PocketWorldController, reference: JSONObject, onClose: () -> Unit) {
    DisposableEffect(reference.toString()) { controller.enterOwnerDomain("taskMemory"); onDispose { controller.leaveOwnerDomain("taskMemory") } }
    val state = controller.state
    val sourceTask = state.tasks.firstOrNull { it.id == reference.getString("taskId") }?.raw
    val rawCitations = remember(sourceTask?.optJSONArray("events")?.toString(), reference.toString()) {
        if (sourceTask?.optString("runId") != reference.optString("runId")) emptyList()
        else runCatching { listOf(dev.pocketengine.features.memory.TaskMemoryTrace.rawCitationJumps(sourceTask), dev.pocketengine.features.memory.TaskMemoryTrace.outputCitationJumps(sourceTask)).flatMap { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }.distinctBy { it.toString() } }.getOrDefault(emptyList())
    }
    val historyCitations = remember(sourceTask?.optJSONArray("events")?.toString(), reference.toString()) {
        if (sourceTask?.optString("runId") != reference.optString("runId")) emptyList()
        else runCatching { dev.pocketengine.features.memory.TaskMemoryTrace.historyCitationJumps(sourceTask).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } } }.getOrDefault(emptyList())
    }
    TaskMemoryEvidenceSheet(reference.getString("taskId"), reference.optString("runId", "unavailable"),
        reference.optString("requestId", "unavailable"), reference.optString("answerId", "unavailable"), null,
        state.ownerPending.filter { it.startsWith("taskMemory.") }.map { it.substringAfter('.') }.toSet(), state.ownerResponses("taskMemory"),
        { command, payload -> controller.requestOwnerDomain("taskMemory", command, payload) },
        { id -> onClose(); controller.focusTask(id) }, onClose, refreshRevision = state.memoryRefreshRevision, rawCitations = rawCitations, historyCitations = historyCitations)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskMemorySearchSheet(controller: PocketWorldController, onClose: () -> Unit) {
    var originalHistory by remember { mutableStateOf(false) }
    var traceTask by remember { mutableStateOf<JSONObject?>(null) }
    var query by remember { mutableStateOf("") }
    var pendingId by remember { mutableStateOf<String?>(null) }
    var submittedQuery by remember { mutableStateOf("") }
    var submittedCursor by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<JSONObject?>(null) }
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { controller.enterOwnerDomain("taskMemory"); onDispose { controller.leaveOwnerDomain("taskMemory") } }
    fun search(cursor: String = "") {
        if (pendingId != null) return
        submittedQuery = query; submittedCursor = cursor; error = null
        pendingId = controller.requestOwnerDomain("taskMemory", "searchTaskMemoryTasks", JSONObject().put("query", query).put("cursor", cursor).put("limit", 20))
    }
    val response = controller.state.ownerResponses("taskMemory")["searchTaskMemoryTasks"]
    LaunchedEffect(response?.toString(), pendingId) {
        val result = TaskMemoryEvidenceGuard.exactEnvelope(pendingId, response) ?: return@LaunchedEffect
        pendingId = null
        if (submittedQuery != query) return@LaunchedEffect
        if (!result.getBoolean("ok")) { error = result.optString("error", "任务检索未完成，请重试。"); return@LaunchedEffect }
        try {
            val next = result.getJSONObject("result")
            require(next.optString("status") in setOf("current", "limited")) { "原任务查询暂不可用，未搜索全部历史。请重试。" }
            val records = next.getJSONArray("tasks").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            require(records.all { it.optString("ownerId") == "owner" && it.optString("spaceId") == "private" && it.opt("formalMemory") == false }) { "任务来源空间不匹配。" }
            rows = ((if (submittedCursor.isEmpty()) emptyList() else rows) + records).distinctBy { it.getString("taskId") }; page = next
        } catch (failure: Exception) { error = failure.message ?: "任务页不可读。" }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(22.dp)) {
            if (originalHistory) {
                OriginalConversationHistoryPanel(controller.state.ownerResponses("taskMemory"),
                    { command, payload -> controller.requestOwnerDomain("taskMemory", command, payload) },
                    { id -> onClose(); controller.openConversation(id) }, { id -> onClose(); controller.focusTask(id) }, { originalHistory = false }, controller::stageOwnerOriginalReference)
            } else {
            TextButton(onClick = { originalHistory = true }) { Text("浏览对话原文与长期留存设置") }
            val trace = traceTask
            if (trace != null) TaskMemoryTracePanel(trace.getString("taskId"), trace.optString("runId"), controller.state.ownerResponses("taskMemory"),
                { command, payload -> controller.requestOwnerDomain("taskMemory", command, payload) },
                { id -> onClose(); controller.focusTask(id) }, { traceTask = null }, controller.state.memoryRefreshRevision)
            else TaskMemoryTaskSourcePanel(query, rows, page, pendingId != null, error,
                { query = it; page = null; rows = emptyList(); error = null }, { search() }, { search(page?.optString("nextCursor").orEmpty()) },
                { id -> onClose(); controller.focusTask(id) }, onClose, { traceTask = it })
            }
        }
    }
}
