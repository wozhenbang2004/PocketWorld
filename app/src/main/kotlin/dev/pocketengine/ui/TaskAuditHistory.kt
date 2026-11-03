package dev.pocketengine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** A single finite window. Moving through history never accumulates its old bodies in UI state. */
@Composable
internal fun TaskAuditList(state: PocketUiState, controller: PocketWorldController, open: (UiTask) -> Unit, searchHistory: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("all") }
    val listState = rememberLazyListState()
    var page by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableLongStateOf(0) }
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { alive = true; onDispose { alive = false; request++ } }
    fun load(cursor: String) {
        val identity = ++request; busy = true; error = null
        controller.readTaskAudit(null, "", cursor) { result, failure -> if (alive && request == identity) { page = result ?: page; error = failure; busy = false } }
    }
    val pageRows = page?.optJSONArray("tasks")?.objects()?.map(::taskFromJson) ?: state.tasks
    // Refresh only identities already present in this finite page, never accumulate other pages.
    val current = state.tasks.associateBy { it.id }
    val rows = pageRows.map { old -> current[old.id]?.takeIf { it.updatedAt > old.updatedAt } ?: old }
    val visible = rows.filter { taskOverviewMatches(it, filter) }
    val next = page?.optString("nextCursor") ?: controller.taskListNextCursor
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        item(key = "heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${rows.count { taskOverviewAttention(it) != null }} 项待处理 · ${rows.count { taskOverviewMatches(it, "active") }} 项进行中" + if(next.isNotEmpty()) " · 当前页" else "",
                    color = SecondaryInk, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = searchHistory) { Text("搜索", fontSize = 14.sp) }
                TextButton(onClick = { load("") }, enabled = !busy) { Text("刷新", fontSize = 14.sp) }
            }
        }
        item(key = "filters") {
            val filters = listOf("all", "attention", "active", "ended")
            PocketSegmentedTabs(listOf("全部", "待处理", "进行中", "已结束"), filters.indexOf(filter).coerceAtLeast(0)) {
                filter = filters[it]
            }
            Spacer(Modifier.height(16.dp))
        }
        error?.let { message -> item(key = "error") { Text(message, color = Danger, fontSize = 14.sp, lineHeight = 20.sp) } }
        if (busy) item(key = "loading") { Text("正在读取任务，已有内容仍可查看。", color = SecondaryInk, fontSize = 14.sp) }
        if (visible.isEmpty() && !busy) item(key = "empty") {
            Text(if (rows.isEmpty()) "当前窗口没有任务。在对话里提出目标即可开始。" else "当前已载入范围内没有匹配项。可以切换筛选或查看较早任务。", color = SecondaryInk, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 24.dp))
        }
        val groups = if (filter == "all") listOf(
            "待我处理" to visible.filter { taskOverviewAttention(it) != null },
            "正在进行" to visible.filter { taskOverviewAttention(it) == null && taskOverviewMatches(it, "active") },
            "结果与记录" to visible.filter { taskOverviewAttention(it) == null && !taskOverviewMatches(it, "active") }
        ) else listOf("" to visible)
        for ((section, tasks) in groups) {
            if ((section == "待我处理" || filter == "attention") && tasks.isNotEmpty()) item(key = "decision_inbox") {
                AffectDecisionInbox(items = tasks.map { task ->
                    AffectDecisionItem(id = task.id, title = task.goal,
                        detail = taskOverviewAttention(task).orEmpty(),
                        context = taskStatus(task.status) + " · " + (taskOriginLabel(task) ?: executionLocation(task.executionMode)) +
                            if (task.updatedAt > 0) "\n" + auditDate(task.updatedAt) else "",
                        primary = "审阅这项任务")
                }, onReview = { id -> tasks.firstOrNull { it.id == id }?.let { open(it) } })
            } else {
            if (tasks.isNotEmpty() && section.isNotEmpty()) item(key = "section:$section") {
                Text(section, color = SecondaryInk, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            }
            items(tasks, key = { "task:" + it.id }) { task ->
                if (!taskOverviewMatches(task, "active")) {
                    Row(Modifier.fillMaxWidth().clickable { open(task) }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                            AffectGlyph(if(task.status == "completed") "check" else "file", Modifier.size(16.dp), SecondaryInk)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(task.goal, fontSize = 15.sp, lineHeight = 22.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(taskStatus(task.status) + if(task.updatedAt > 0) " · " + auditDate(task.updatedAt) else "",
                                fontSize = 12.sp, lineHeight = 18.sp, color = SecondaryInk, modifier = Modifier.padding(top = 4.dp))
                        }
                        AffectGlyph("chevron-right", Modifier.size(12.dp), SecondaryInk)
                    }
                    HorizontalDivider(color = Line.copy(alpha = .65f), thickness = .5.dp)
                } else Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .pocketDepth(PocketDepth.CONTENT, 14.dp),
                    color = Color.White, shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(.5.dp, Line.copy(alpha = .65f))) {
                    Column(Modifier.clickable { open(task) }.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            AffectGlyph(if(task.status == "completed") "check" else "tool", Modifier.size(16.dp), Ink)
                            Spacer(Modifier.width(8.dp))
                            Text(taskStatus(task.status), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            Text(taskOriginLabel(task) ?: executionLocation(task.executionMode), color = SecondaryInk,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 160.dp))
                        }
                        Text(task.goal, color = Ink, fontSize = 16.sp, lineHeight = 24.sp,
                            fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))
                        HorizontalDivider(color = Line.copy(alpha = .6f), thickness = .5.dp)
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if(task.updatedAt > 0) auditDate(task.updatedAt) else "任务记录",
                                fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.weight(1f))
                            Text(if(task.status == "completed") "查看结果" else "查看进展", fontSize = 12.sp, color = Ink)
                            Spacer(Modifier.width(6.dp))
                            AffectGlyph("chevron-right", Modifier.size(12.dp), SecondaryInk)
                        }
                    }
                }
            }
            }
        }
        item(key = "pagination") {
            if (next.isNotEmpty()) TextButton(onClick = { load(next) }, enabled = !busy) { Text("查看较早一页") }
            else if (visible.isNotEmpty()) Text("已显示本次读取的全部任务", color = SecondaryInk, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
        }
    }
}

/** Native UI currentness distinguishes a known owned service from an uncertain effect.
 * Legacy rows retain the broad fallback; an explicit unknown task state still wins. */
internal fun taskOverviewAttention(task: UiTask): String? = when {
    (task.raw.opt("historyHasUnconfirmedOperations") as? Boolean ?: task.raw.optBoolean("hasUnresolvedOperations")) || task.status in setOf("reconciling", "reconciliation_required", "unknown") -> "结果待核对 · 查看原操作回执"
    task.status in setOf("waiting_approval", "awaiting_approval", "waiting_permission", "awaiting_permission") -> "需要授权 · 查看本次操作范围"
    task.status in setOf("awaiting_user", "waiting_user") -> "需要你处理 · 打开原任务查看问题"
    task.status == "needs_confirmation" -> "待确认结果 · 查看成果后决定"
    task.status in setOf("paused", "interrupted") -> "等待继续 · 核对原任务状态"
    else -> null
}
internal fun taskOverviewMatches(task: UiTask, filter: String): Boolean {
    val attention = taskOverviewAttention(task) != null
    val ended = task.status in setOf("completed", "failed", "cancelled", "canceled", "stopped")
    return when (filter) {
        "attention" -> attention
        "active" -> !attention && task.status in setOf("queued", "preparing", "running", "pausing", "stopping", "cancelling", "verifying")
        "ended" -> ended && !attention
        else -> true
    }
}

@Composable
internal fun TaskAuditPanel(taskId: String, controller: PocketWorldController) {
    var expanded by rememberSaveable(taskId) { mutableStateOf(false) }
    var section by rememberSaveable(taskId) { mutableStateOf("events") }
    var page by remember(taskId) { mutableStateOf<JSONObject?>(null) }
    var busy by remember(taskId) { mutableStateOf(false) }
    var error by remember(taskId) { mutableStateOf<String?>(null) }
    var request by remember(taskId) { mutableLongStateOf(0) }
    var alive by remember(taskId) { mutableStateOf(true) }
    DisposableEffect(taskId) { alive = true; onDispose { alive = false; request++ } }
    fun load(targetSection: String, cursor: String) {
        val identity = ++request; busy = true; error = null
        controller.readTaskAudit(taskId, targetSection, cursor) { result, failure ->
            if (alive && request == identity) { if (result != null) { page = result; section = targetSection }; error = failure; busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 15.dp)) {
        TextButton(onClick = { expanded = !expanded; if (expanded && page == null) load(section, "") }) { Text(if (expanded) "收起原操作记录" else "按页查看原操作记录", fontSize = 12.sp) }
        if (expanded) {
            Text("这里只读一个窗口。较早记录仍在原任务数据库；回放不会续跑、重发或改变授权。运行中新记录请刷新查看。", color = SecondaryInk, fontSize = 11.sp, lineHeight = 18.sp)
            Row { TextButton(onClick = { load("events", "") }, enabled = !busy) { Text("步骤记录") }; TextButton(onClick = { load("operations", "") }, enabled = !busy) { Text("操作与回执") }; TextButton(onClick = { load(section, "") }, enabled = !busy) { Text("刷新最新") } }
            error?.let { Text(it, color = Danger, fontSize = 12.sp) }
            page?.optJSONArray("entries").objects().forEach { entry -> key(section, entry.optLong("position")) { TaskAuditEntry(taskId, entry) } }
            if (busy) Text("正在读取有界原记录…", color = SecondaryInk, fontSize = 12.sp)
            val next = page?.optString("nextCursor").orEmpty()
            if (next.isNotEmpty()) TextButton(onClick = { load(section, next) }, enabled = !busy) { Text("较早一页 · 当前正文不会累计保留") }
            else if (page != null && !busy) Text("已到这次读取窗口的末尾", color = SecondaryInk, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TaskAuditEntry(taskId: String, entry: JSONObject) {
    var details by remember(entry.toString()) { mutableStateOf(false) }
    val event = entry.optJSONObject("event")
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Text(event?.optString("message", event.optString("type"))?.let(::auditExcerpt) ?: entry.optString("tool", entry.optString("type")), fontSize = 12.sp, lineHeight = 20.sp)
        if (event != null) Text(auditDate(event.optLong("at")), fontSize = 10.sp, color = SecondaryInk)
        else Text("${entry.optString("state")} · 原操作 ${entry.optString("operationId")}", color = SecondaryInk, fontSize = 10.sp)
        if (entry.optString("status") == "read_limited") Text("原正文 ${entry.optLong("bodyBytes")} 字节，超过本页读取余量；原audit未删除、未用摘要替代。", color = SecondaryInk, fontSize = 11.sp)
        else {
            TextButton(onClick = { details = !details }) { Text(if (details) "收起本条" else "查看本条原记录", fontSize = 11.sp) }
            if (details) SelectionContainer { Text(auditExcerpt((event ?: entry).toString(2)), fontSize = 11.sp, lineHeight = 18.sp) }
        }
        if (event == null) TaskExecutionPresentation.projectExecutionObservationId(entry)?.let { NativeProjectExecutionObservation(taskId, it) }
    }; HorizontalDivider(color = Line)
}

private fun auditExcerpt(value: String): String {
    val count = value.codePointCount(0, value.length)
    return if (count <= 2000) value else value.substring(0, value.offsetByCodePoints(0, 2000)) + "\n…本页显示到此，原正文仍保留。"
}

private fun auditDate(at: Long): String = if (at <= 0) "" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(at))
