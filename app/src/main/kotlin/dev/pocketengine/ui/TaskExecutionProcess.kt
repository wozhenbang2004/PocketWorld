package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

/** One real task, one finite read window. Opening this view can only read original audit rows. */
@Composable
internal fun TaskExecutionProcess(task: UiTask, controller: PocketWorldController, focus: (TaskExecutionPresentation.Target) -> Unit) {
    val run = task.raw.optString("runId")
    val epoch = task.raw.optInt("coordinatorEpoch")
    var expanded by rememberSaveable(task.id, run, epoch) { mutableStateOf(false) }
    var section by rememberSaveable(task.id, run, epoch) { mutableStateOf("operations") }
    var page by remember(task.id, run, epoch) { mutableStateOf<JSONObject?>(null) }
    var busy by remember(task.id, run, epoch) { mutableStateOf(false) }
    var error by remember(task.id, run, epoch) { mutableStateOf<String?>(null) }
    var request by remember(task.id, run, epoch) { mutableLongStateOf(0) }
    var alive by remember(task.id, run, epoch) { mutableStateOf(true) }
    DisposableEffect(task.id, run, epoch) { alive = true; onDispose { alive = false; request++ } }
    fun load(which: String, cursor: String = "") {
        val identity = ++request
        busy = true; error = null
        controller.readTaskAudit(task.id, which, cursor) { result, failure ->
            if (alive && identity == request) {
                if (result != null && result.optString("format") == "pocketworld.task-audit-page.v1" && result.optString("taskId") == task.id && result.optString("section") == which) { page = result; section = which; error = failure }
                else error = failure ?: "暂时无法更新，已有记录仍保留。"
                busy = false
            }
        }
    }
    fun openProcess() { expanded = true; if (page == null && !busy) load("operations") }
    val current = TaskExecutionPresentation.current(task.raw, System.currentTimeMillis())
    val recentRows = task.raw.optJSONArray("operations")
    val recentEntries = org.json.JSONArray()
    if (recentRows != null) for (index in maxOf(0, recentRows.length() - 3) until recentRows.length()) {
        recentRows.optJSONObject(index)?.let(recentEntries::put)
    }
    val recentActivity = TaskExecutionPresentation.records(JSONObject().put("entries", recentEntries), "operations")
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("task_execution_process")) {
        Surface(modifier = Modifier.fillMaxWidth().pocketDepth(PocketDepth.CONTENT, 14.dp),
            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("执行动态", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PocketExecutionStatus(current.title, current.tone, prominent = true, animateRunning = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).semantics { heading() }.testTag("task_execution_current"))
        Text(current.detail, fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        if (!expanded && recentActivity.isNotEmpty()) {
            HorizontalDivider(color = Line, modifier = Modifier.padding(top = 14.dp, bottom = 10.dp))
            Text("最近 ${recentActivity.size} 项活动", style = MaterialTheme.typography.labelMedium, color = SecondaryInk)
            recentActivity.forEachIndexed { index, record ->
                key(record.key) { ExecutionActivityStep(record, index == recentActivity.lastIndex) }
            }
        }
        if (current.target != TaskExecutionPresentation.Target.NONE) TextButton(onClick = { if (current.target == TaskExecutionPresentation.Target.PROCESS) openProcess() else focus(current.target) }, modifier = Modifier.heightIn(min = 48.dp).testTag("task_execution_next_action"), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text(current.action, fontSize = 14.sp) }
        HorizontalDivider(color = Line, modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = { if (expanded) expanded = false else openProcess() }, modifier = Modifier.heightIn(min = 48.dp).testTag("task_execution_toggle"), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text(if (expanded) "收起活动记录" else "查看全部活动", fontSize = 14.sp) }
            }
        }
        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val privateOwner = task.raw.optString("actorId") == "owner" && task.raw.optString("spaceId") == "private"
            val sections = if (privateOwner) listOf("operations", "events", "jobs") else listOf("operations", "events")
            PocketSegmentedTabs(if (privateOwner) listOf("工具活动", "步骤记录", "关联作业") else listOf("工具活动", "步骤记录"), sections.indexOf(section).coerceAtLeast(0),
                onSelect = { load(sections[it]) }, enabled = !busy)
            Text(if (section == "jobs") "同会话的原调用与申请 · 当前结果请回原任务核对" else "只读查看 · 最近记录在前", fontSize = 12.sp, lineHeight = 18.sp,
                color = SecondaryInk, modifier = Modifier.padding(vertical = 8.dp))
            if (section == "jobs") Text("团队完整子作业与早期独立任务仍请从原入口查看；此目录未据此宣称全部作业已列出。", fontSize = 12.sp, lineHeight = 18.sp, color = SecondaryInk)
            error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp)) }
            val shown = page?.let { TaskExecutionPresentation.records(it, section) }.orEmpty()
            if (shown.isEmpty() && !busy && page != null) Text("本页暂无记录。", fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 12.dp))
            shown.forEachIndexed { index, record -> key(record.key) {
                if (record.jobTaskId != null) TaskJobRecord(record, controller::focusTask)
                else TaskExecutionRecord(record, task.id, run, index == shown.lastIndex)
            } }
            if (busy) Text("正在更新记录…", fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            page?.let { original ->
                Text("本页 ${shown.size} 条" + if (original.optBoolean("readLimited")) " · 部分较大内容未加载" else " · 当前读取窗口", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val next = original.optString("nextCursor")
                Column {
                    TextButton(onClick = { load(section) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新最新记录") }
                    if (next.isNotEmpty()) TextButton(onClick = { load(section, next) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看更早记录") }
                    else Text("已到本次记录窗口末尾。", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Existing tool-card anatomy; navigation reads the original task, never dispatches or stops. */
@Composable
internal fun TaskJobRecord(record: TaskExecutionPresentation.Record, openTask: (String) -> Unit) {
    val sourceTask = record.jobTaskId ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("task_job_" + record.key)) {
        AffectToolCallCard(id = record.key, name = record.title, statusLabel = record.label, tone = record.tone,
            output = if (record.limited) "" else record.receipt, arguments = "", needsReview = record.tone == TaskExecutionPresentation.Tone.UNCERTAIN)
        ProcessOutputNotes(record)
        Text("原执行 ${record.runId.takeLast(8)} · 代次 ${record.jobEpoch ?: "未记录"} · 操作 ${record.jobOperationId}", fontSize = 12.sp, lineHeight = 18.sp, color = SecondaryInk)
        if (record.limited) Text("本页未展开原作业详情，已有记录仍保留。", fontSize = 12.sp, lineHeight = 18.sp, color = SecondaryInk)
        record.projectExecutionId?.let { NativeProjectExecutionObservation(sourceTask, it) }
        TextButton(onClick = { openTask(sourceTask) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("回到原任务") }
        record.relatedTaskId?.let { related -> TextButton(onClick = { openTask(related) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看原子任务") } }
    }
}

@Composable
private fun TaskExecutionRecord(record: TaskExecutionPresentation.Record, taskId: String, currentRun: String, last: Boolean) {
    if (record.key.startsWith("operation:")) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            AffectToolCallCard(id = record.key + ":" + record.at, name = record.title,
                statusLabel = record.label, tone = record.tone, output = if (record.limited) "" else record.receipt,
                arguments = if (record.limited) "" else record.arguments,
                needsReview = record.tone == TaskExecutionPresentation.Tone.UNCERTAIN || record.tone == TaskExecutionPresentation.Tone.ERROR,
                modifier = Modifier.testTag("task_execution_record_" + record.key))
            ProcessOutputNotes(record)
            if (record.limited) Text("这条原记录有 ${record.bodyBytes} 字节，本页未展开；原件仍保留。",
                fontSize = 12.sp, lineHeight = 18.sp, color = SecondaryInk, modifier = Modifier.padding(top = 4.dp))
            record.projectExecutionId?.let { NativeProjectExecutionObservation(taskId, it) }
            val time = if (record.at > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.at)) else "时间未报告"
            Text(time + if (record.runId.isNotEmpty() && record.runId != currentRun) " · 此前执行 ${record.runId.takeLast(8)}" else "",
                fontSize = 12.sp, lineHeight = 18.sp, color = SecondaryInk, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
        }
        return
    }
    var expanded by rememberSaveable(record.key, record.at, record.receipt) { mutableStateOf(false) }
    val color = processColor(record.tone)
    val track = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().drawBehind {
        val x = 8.dp.toPx(); val y = 26.dp.toPx(); val radius = 4.dp.toPx()
        if (!last) drawLine(track, Offset(x, y + radius), Offset(x, size.height), 1.dp.toPx())
        drawCircle(color, radius, Offset(x, y), style = if (record.tone == TaskExecutionPresentation.Tone.RETURNED) androidx.compose.ui.graphics.drawscope.Fill else Stroke(1.5.dp.toPx()))
    }.padding(start = 28.dp, top = 10.dp, bottom = 10.dp)) {
        Text(record.title, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
        Text(record.label, fontSize = 13.sp, lineHeight = 20.sp, color = color, modifier = Modifier.padding(top = 4.dp))
        Text(if (record.at > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.at)) else "时间未报告", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (record.runId.isNotEmpty() && record.runId != currentRun) Text("来自此前执行 · " + record.runId.takeLast(8), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (record.limited) Text("原正文 ${record.bodyBytes} 字节，本页未读取。原件没有删除，也没有以本条标题替代。", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        else {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).testTag("task_execution_record_" + record.key), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text(if (expanded) "收起详情" else if (record.key.startsWith("event:")) "查看步骤详情" else "查看参数与返回", fontSize = 13.sp) }
            if (expanded) {
                if (record.arguments.isNotBlank()) ProcessText("本次参数", record.arguments)
                ProcessOutputNotes(record)
                if (record.receipt.isNotBlank()) ProcessText("返回内容", record.receipt)
                else Text("尚未收到确定结果，请先核对。", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("操作编号：" + record.key.removePrefix("operation:"), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProcessText(title: String, value: String) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
    SelectionContainer { Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) }
}

@Composable
private fun ProcessOutputNotes(record: TaskExecutionPresentation.Record) {
    record.outputNotes.forEach { note -> Text(note, fontSize = 12.sp, lineHeight = 18.sp, color = SecondaryInk, modifier = Modifier.padding(top = 4.dp)) }
}

@Composable
private fun processColor(tone: TaskExecutionPresentation.Tone): Color = when (tone) {
    TaskExecutionPresentation.Tone.UNCERTAIN, TaskExecutionPresentation.Tone.ERROR -> MaterialTheme.colorScheme.error
    TaskExecutionPresentation.Tone.RUNNING, TaskExecutionPresentation.Tone.WAITING -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Retained Agent Run activity anatomy: real original rows, never synthesized future steps. */
@Composable
private fun ExecutionActivityStep(record: TaskExecutionPresentation.Record, last: Boolean) {
    var details by rememberSaveable(record.key, record.at) { mutableStateOf(false) }
    val line = Line
    Column(Modifier.fillMaxWidth().drawBehind {
        if (!last) drawLine(line, Offset(12.dp.toPx(), 34.dp.toPx()),
            Offset(12.dp.toPx(), size.height), 1.dp.toPx())
    }.padding(top = 10.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button) { details = !details }
            .semantics { stateDescription = if (details) "详情已展开" else "详情已收起" },
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val icon = when (record.tone) {
                TaskExecutionPresentation.Tone.RETURNED -> "check"
                TaskExecutionPresentation.Tone.ERROR -> "warning"
                TaskExecutionPresentation.Tone.UNCERTAIN -> "question"
                TaskExecutionPresentation.Tone.WAITING, TaskExecutionPresentation.Tone.RUNNING -> "clock"
                TaskExecutionPresentation.Tone.STOPPED -> "stop"
                else -> "tool"
            }
            Box(Modifier.size(24.dp).background(Soft, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                AffectGlyph(icon, Modifier.size(16.dp), SecondaryInk)
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(toolDisplayName(record.title), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.weight(1f))
                    Surface(color = Soft, shape = RoundedCornerShape(6.dp), border = BorderStroke(.5.dp, Line)) {
                        Text(executionToneCaption(record.tone), fontSize = 11.sp, color = SecondaryInk,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                }
                if (toolDisplayName(record.title) != record.title)
                    Text(record.title, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp,
                        color = SecondaryInk, modifier = Modifier.padding(top = 4.dp))
                else Text(record.label, style = MaterialTheme.typography.bodySmall, color = SecondaryInk,
                    modifier = Modifier.padding(top = 3.dp))
            }
            AffectGlyph(if (details) "chevron" else "chevron-right", Modifier.size(14.dp), SecondaryInk)
        }
        androidx.compose.animation.AnimatedVisibility(visible = details) {
            Column(Modifier.fillMaxWidth().padding(start = 34.dp, top = 6.dp)) {
                Text(record.label, fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.padding(bottom = 6.dp))
                ProcessOutputNotes(record)
                if (record.arguments.isNotBlank()) ProcessText("本次参数", record.arguments)
                if (record.receipt.isNotBlank()) ProcessText("返回内容", record.receipt)
                else Text("尚未收到确定结果，请先核对。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            }
        }
    }
}
