package dev.pocketengine.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
    // Keep the same LazyListState and stable task identity. Refreshes and new events never
    // force a reader to the newest item, and filtering does not replay any task action.
    LazyColumn(Modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item(key = "heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text("已载入 ${rows.size} 项", color = Ink, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                    Text("${rows.count { taskOverviewAttention(it) != null }} 项待处理 · ${rows.count { taskOverviewMatches(it, "active") }} 项进行中",
                        color = SecondaryInk, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 2.dp))
                }
                TextButton(onClick = searchHistory,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)) { Text("搜索", fontSize = 13.sp) }
                TextButton(onClick = { load("") }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)) { Text("刷新", fontSize = 13.sp) }
            }
        }
        item(key = "filters") {
            val filters = listOf("all", "attention", "active", "ended")
            PocketSegmentedTabs(listOf("全部", "待处理", "进行中", "已结束"), filters.indexOf(filter).coerceAtLeast(0)) {
                filter = filters[it]
            }
            Spacer(Modifier.height(8.dp))
        }
        error?.let { message -> item(key = "error") {
            Text(message, color = Ink, fontSize = 13.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(vertical = 10.dp))
        } }
        if (busy) item(key = "loading") {
            Text("正在读取，已有任务仍可查看。", color = SecondaryInk,
                fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (visible.isEmpty() && !busy) item(key = "empty") {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(when {
                    rows.isEmpty() -> "还没有载入任务"
                    filter == "attention" -> "当前页没有待处理项"
                    filter == "active" -> "当前页没有进行中的任务"
                    filter == "ended" -> "当前页没有已结束的任务"
                    else -> "当前页没有匹配项"
                }, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(if (rows.isEmpty()) "可以在对话里提出目标，或刷新已有任务。"
                    else "切换筛选，或查看较早一页。", color = SecondaryInk,
                    fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
        val groups = if (filter == "all") listOf(
            "待我处理" to visible.filter { taskOverviewAttention(it) != null },
            "正在进行" to visible.filter { taskOverviewAttention(it) == null && taskOverviewMatches(it, "active") },
            "结果与记录" to visible.filter { taskOverviewAttention(it) == null && !taskOverviewMatches(it, "active") }
        ) else listOf("" to visible)
        for ((section, tasks) in groups) {
            if (tasks.isNotEmpty() && section.isNotEmpty()) item(key = "section:$section") {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp, start = 2.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(section, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f))
                    Text(tasks.size.toString(), color = SecondaryInk, fontSize = 12.sp)
                }
            }
            items(tasks, key = { "task:" + it.id }) { task ->
                Column(Modifier.animateItem(
                    fadeInSpec = tween(150),
                    placementSpec = spring(dampingRatio = .94f, stiffness = 600f),
                    fadeOutSpec = tween(100)
                )) {
                    PocketTaskCard(
                        title = task.goal,
                        status = taskStatus(task.status),
                        context = (if (state.activeTaskId == task.id) "当前工作 · " else "") +
                            (taskOriginLabel(task) ?: executionLocation(task.executionMode)),
                        time = if (task.updatedAt > 0) auditDate(task.updatedAt) else "",
                        attention = taskOverviewAttention(task),
                        actionLabel = taskOverviewAction(task),
                        failed = task.status == "failed",
                        onOpen = { open(task) }
                    )
                    if (taskOverviewAttention(task) == null) HorizontalDivider(
                        color = Line.copy(alpha = .65f), thickness = .5.dp,
                        modifier = Modifier.padding(horizontal = 14.dp))
                }
            }
        }
        item(key = "pagination") {
            if (next.isNotEmpty()) TextButton(onClick = { load(next) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看较早一页", fontSize = 13.sp) }
            else if (visible.isNotEmpty()) Text("本次读取已显示完", color = SecondaryInk,
                fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 20.dp))
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

private fun taskOverviewAction(task: UiTask): String = when {
    taskOverviewAttention(task) == null -> if (task.status == "completed") "查看结果" else "查看进展"
    (task.raw.opt("historyHasUnconfirmedOperations") as? Boolean ?: task.raw.optBoolean("hasUnresolvedOperations")) ||
        task.status in setOf("reconciling", "reconciliation_required", "unknown") -> "核对操作"
    task.status in setOf("waiting_approval", "awaiting_approval", "waiting_permission", "awaiting_permission") -> "审阅授权"
    task.status == "needs_confirmation" -> "核对成果"
    task.status in setOf("paused", "interrupted") -> "审阅继续"
    else -> "查看问题"
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
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = !expanded; if (expanded && page == null) load(section, "") },
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (expanded) "收起操作记录" else "查看操作记录", fontSize = 13.sp)
                    AffectGlyph(if (expanded) "chevron" else "chevron-right", Modifier.size(12.dp), SecondaryInk)
                }
            }
            if (expanded) TextButton(onClick = { load(section, "") }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新", fontSize = 13.sp) }
        }
        AnimatedVisibility(visible = expanded,
            enter = expandVertically(animationSpec = tween(190)) + fadeIn(tween(130)),
            exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(tween(90))) {
            Column {
                PocketSegmentedTabs(listOf("步骤记录", "操作与回执"),
                    if (section == "events") 0 else 1,
                    { index -> load(if (index == 0) "events" else "operations", "") },
                    enabled = !busy)
                Text("仅显示当前页。查看记录不会继续任务或重复操作。", color = SecondaryInk,
                    fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                error?.let { Text(it, color = Ink, fontSize = 13.sp, lineHeight = 20.sp,
                    modifier = Modifier.padding(vertical = 8.dp)) }
                page?.optJSONArray("entries").objects().forEach { entry ->
                    key(section, entry.optLong("position")) { TaskAuditEntry(entry) }
                }
                if (busy) Text("正在读取原记录…", color = SecondaryInk, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp))
                val next = page?.optString("nextCursor").orEmpty()
                if (next.isNotEmpty()) TextButton(onClick = { load(section, next) }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("查看较早一页", fontSize = 13.sp)
                }
                else if (page != null && !busy) Text("本次读取已显示完", color = SecondaryInk,
                    fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun TaskAuditEntry(entry: JSONObject) {
    // The parent keys this entry by its native section/position, not by a rendered excerpt.
    var details by rememberSaveable { mutableStateOf(false) }
    val event = entry.optJSONObject("event")
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(event?.optString("message", event.optString("type"))?.let(::auditExcerpt)
            ?: entry.optString("tool", entry.optString("type")), fontSize = 13.sp,
            lineHeight = 20.sp, color = Ink)
        if (event != null) Text(auditDate(event.optLong("at")), fontSize = 12.sp,
            lineHeight = 18.sp, color = SecondaryInk, modifier = Modifier.padding(top = 3.dp))
        else Text("${entry.optString("state")} · 原操作 ${entry.optString("operationId")}",
            color = SecondaryInk, fontSize = 12.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(top = 3.dp))
        if (entry.optString("status") == "read_limited") Text(
            "原正文 ${entry.optLong("bodyBytes")} 字节，超过本页读取余量。原记录仍保留，未用摘要替代。",
            color = SecondaryInk, fontSize = 12.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp))
        else {
            TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (details) "收起原记录" else "查看原记录", fontSize = 12.sp)
            }
            AnimatedVisibility(visible = details,
                enter = expandVertically(animationSpec = tween(180)) + fadeIn(tween(120)),
                exit = shrinkVertically(animationSpec = tween(140)) + fadeOut(tween(90))) {
                SelectionContainer {
                    Text(auditExcerpt((event ?: entry).toString(2)), fontSize = 12.sp,
                        lineHeight = 18.sp, color = SecondaryInk,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                }
            }
        }
    }
    HorizontalDivider(color = Line.copy(alpha = .65f), thickness = .5.dp)
}

private fun auditExcerpt(value: String): String {
    val count = value.codePointCount(0, value.length)
    return if (count <= 2000) value else value.substring(0, value.offsetByCodePoints(0, 2000)) + "\n…本页显示到此，原正文仍保留。"
}

private fun auditDate(at: Long): String = if (at <= 0) "" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(at))
