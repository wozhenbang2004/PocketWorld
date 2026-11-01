package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
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
                else error = failure ?: "原记录窗口尚未核对成功，已有内容仍保留。"
                busy = false
            }
        }
    }
    fun openProcess() { expanded = true; if (page == null && !busy) load("operations") }
    val current = TaskExecutionPresentation.current(task.raw, System.currentTimeMillis())
    val emphasis = processColor(current.tone)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("task_execution_process")) {
        Text("当前步骤", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(current.title, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = emphasis, modifier = Modifier.padding(top = 6.dp).semantics { heading() }.testTag("task_execution_current"))
        Text(current.detail, fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        if (current.target != TaskExecutionPresentation.Target.NONE) TextButton(onClick = { if (current.target == TaskExecutionPresentation.Target.PROCESS) openProcess() else focus(current.target) }, modifier = Modifier.heightIn(min = 48.dp).testTag("task_execution_next_action"), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text(current.action, fontSize = 14.sp) }
        TextButton(onClick = { if (expanded) expanded = false else openProcess() }, modifier = Modifier.heightIn(min = 48.dp).testTag("task_execution_toggle"), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text(if (expanded) "收起执行过程" else "展开执行过程与工具回执", fontSize = 14.sp) }
        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { load("operations") }, enabled = !busy) { Text("工具与回执", fontSize = 13.sp) }
                TextButton(onClick = { load("events") }, enabled = !busy) { Text("步骤记录", fontSize = 13.sp) }
            }
            Text("按由近到早读取原任务的一个记录窗口。展开、刷新或回看都不会继续执行、批准或重放调用。", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp)) }
            val shown = page?.let { TaskExecutionPresentation.records(it, section) }.orEmpty()
            if (shown.isEmpty() && !busy && page != null) Text("本窗口没有可展开的原记录，不能据此推断整个任务没有执行。", fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 12.dp))
            shown.forEachIndexed { index, record -> key(record.key) { TaskExecutionRecord(record, run, index == shown.lastIndex) } }
            if (busy) Text("正在读取原记录，已有窗口仍可查看…", fontSize = 14.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            page?.let { original ->
                Text("本页 ${shown.size} 条" + if (original.optBoolean("readLimited")) " · 有正文超过本页字节预算，未用摘要冒充原件。" else " · 不代表全任务累计步骤或全部成果。", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val next = original.optString("nextCursor")
                Column {
                    TextButton(onClick = { load(section) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新最新记录") }
                    if (next.isNotEmpty()) TextButton(onClick = { load(section, next) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看较早一页 · 不累计正文") }
                    else Text("本次读取没有更早一页；超限正文和原审计仍由原任务保留。", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TaskExecutionRecord(record: TaskExecutionPresentation.Record, currentRun: String, last: Boolean) {
    if (record.key.startsWith("operation:")) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            AffectToolCallCard(id = record.key + ":" + record.at, name = record.title,
                statusLabel = record.label, output = if (record.limited) "" else record.receipt,
                arguments = if (record.limited) "" else record.arguments,
                needsReview = record.tone == TaskExecutionPresentation.Tone.UNCERTAIN || record.tone == TaskExecutionPresentation.Tone.ERROR,
                modifier = Modifier.testTag("task_execution_record_" + record.key))
            if (record.limited) Text("这条原记录有 ${record.bodyBytes} 字节，本页未展开；原件仍保留。",
                fontSize = 12.sp, lineHeight = 18.sp, color = SecondaryInk, modifier = Modifier.padding(top = 4.dp))
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
        Text(if (record.at > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.at)) else "原时间未报告", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (record.runId.isNotEmpty() && record.runId != currentRun) Text("来自此前执行 · " + record.runId.takeLast(8), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (record.limited) Text("原正文 ${record.bodyBytes} 字节，本页未读取。原件没有删除，也没有以本条标题替代。", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        else {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).testTag("task_execution_record_" + record.key), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text(if (expanded) "收起本条记录" else if (record.key.startsWith("event:")) "查看这条原步骤" else "查看原参数与返回", fontSize = 13.sp) }
            if (expanded) {
                if (record.arguments.isNotBlank()) ProcessText("本次参数", record.arguments)
                if (record.receipt.isNotBlank()) ProcessText("原返回记录", record.receipt)
                else Text("此条尚无可展示的终态返回。没有回执不是调用未执行的证明。", fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("原操作定位：" + record.key.removePrefix("operation:"), fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun processColor(tone: TaskExecutionPresentation.Tone): Color = when (tone) {
    TaskExecutionPresentation.Tone.UNCERTAIN, TaskExecutionPresentation.Tone.ERROR -> MaterialTheme.colorScheme.error
    TaskExecutionPresentation.Tone.RUNNING, TaskExecutionPresentation.Tone.WAITING -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
