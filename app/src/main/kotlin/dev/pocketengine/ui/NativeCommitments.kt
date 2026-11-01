package dev.pocketengine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

@Composable
internal fun NativeCommitments(state: PocketUiState, controller: PocketWorldController) {
    var dueOnly by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(state.requestedCommitmentRevision) { state.requestedCommitmentId?.let { plans = false; selectedId = it; controller.requestFeature("automation", "getCommitment", JSONObject().put("id", it)) } }
    val commands = listOf("createCommitment", "updateCommitment", "snoozeCommitment", "cancelCommitment", "completeCommitment")
    LaunchedEffect(Unit) { controller.requestFeature("automation", "listCommitments"); controller.requestFeature("automation", "dueCommitments", JSONObject().put("limit", 100)) }
    LaunchedEffect(commands.map { state.moduleResults["automation." + it] }) {
        if (commands.any { state.moduleResults["automation." + it] != null }) {
            controller.requestFeature("automation", "listCommitments")
            controller.requestFeature("automation", "dueCommitments", JSONObject().put("limit", 100))
            selectedId?.let { controller.requestFeature("automation", "getCommitment", JSONObject().put("id", it)) }
        }
    }
    val records = state.moduleResults[if (dueOnly) "automation.dueCommitments" else "automation.listCommitments"]?.optJSONArray("commitments").objects()
    FollowUpSettings(state, controller)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!plans, { plans = false }, label = { Text("单项委托") }); FilterChip(plans, { plans = true }, label = { Text("持续目标") }) }
    if (plans) { NativePlans(state, controller); return }
    Text("明确委托，持续跟进", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    CommitmentCaption("承诺保留目标、时间和来源。到期候选只供核对，不代表已经创建或执行手机任务。")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !dueOnly, onClick = { dueOnly = false }, label = { Text("全部") })
        FilterChip(selected = dueOnly, onClick = { dueOnly = true }, label = { Text("到期候选") })
        TextButton(onClick = { creating = true }) { Text("新增委托", fontSize = 12.sp) }
    }
    state.moduleErrors.filterKeys { it.startsWith("automation.") && (it.endsWith("Commitment") || it.endsWith("Commitments")) }.values.distinct().forEach { Text(it, color = Danger, fontSize = 12.sp, lineHeight = 20.sp) }
    if (records.isEmpty()) CommitmentCaption(if (dueOnly) "目前没有到期的跟进候选。" else "还没有保存明确的承诺。")
    records.forEach { record ->
        Column(Modifier.fillMaxWidth().clickable { selectedId = record.optString("id"); controller.requestFeature("automation", "getCommitment", JSONObject().put("id", selectedId)) }.padding(vertical = 16.dp)) {
            Text(record.optString("title"), fontSize = 14.sp, lineHeight = 23.sp)
            CommitmentCaption(commitmentStatus(record.optString("status")) + " · " + commitmentTime(record.optLong("followUpAt", record.optLong("dueAt"))))
            if (record.optBoolean("followUpDue")) Text("已到跟进时间，等待核对", fontSize = 11.sp, color = SecondaryInk)
        }
        HorizontalDivider(color = Line)
    }
    val detail = state.moduleResults["automation.getCommitment"]?.optJSONObject("commitment").takeIf { it?.optString("id") == selectedId }
    selectedId?.let { id ->
        val current = detail ?: records.find { it.optString("id") == id }
        if (current != null) CommitmentDetail(current, state, controller, { selectedId = null }, { editing = current })
    }
    if (creating) CommitmentEditor(null, state, controller) { creating = false }
    editing?.let { record -> CommitmentEditor(record, state, controller) { editing = null } }
}

@Composable
private fun CommitmentDetail(record: JSONObject, state: PocketUiState, controller: PocketWorldController, close: () -> Unit, edit: () -> Unit) {
    var confirmComplete by remember(record.optString("id")) { mutableStateOf<JSONObject?>(null) }
    var confirmCancel by remember(record.optString("id")) { mutableStateOf<JSONObject?>(null) }
    val terminal = record.optString("status") in setOf("completed", "cancelled")
    val id = record.optString("id")
    val revision = record.optLong("revision")
    val taskId = record.optString("taskId")
    AlertDialog(onDismissRequest = close, containerColor = Color.White, title = { Text(record.optString("title"), fontSize = 19.sp) }, text = {
        Column(Modifier.heightIn(max = 490.dp).verticalScroll(rememberScrollState())) {
            CommitmentCaption(commitmentStatus(record.optString("status")) + " · 当前版本 " + revision)
            if (record.optString("details").isNotBlank()) SelectionContainer { Text(record.optString("details"), fontSize = 13.sp, lineHeight = 23.sp) }
            CommitmentCaption("目标时间：" + commitmentTime(record.optLong("dueAt")))
            if (record.has("taskState")) CommitmentCaption("原任务状态：" + taskStatus(record.optString("taskState")))
            if (record.has("blockedBy")) CommitmentCaption("前置步骤尚未完成，暂不跟进。")
            if (record.optString("lifecycleState") == "expired") CommitmentCaption("跟进已到期，不代表任务完成。")
            if (record.optBoolean("followUpDue")) CommitmentCaption("这是只读跟进候选，尚未派发执行。")
            if (taskId.isNotBlank()) TextButton(onClick = { controller.focusTask(taskId) }) { Text("查看关联的真实任务", fontSize = 12.sp) }
            val source = record.optJSONObject("sourceRef")
            if (source != null) CommitmentCaption("来源：机主明确委托 · " + source.optString("id").takeLast(8))
            record.optJSONObject("completionEvidence")?.let { evidence -> CommitmentCaption(if (evidence.optString("kind") == "owner_confirmation") "完成凭证：机主对当前版本的明确确认" else "完成凭证：已核验的关联任务结果") }
            if (!terminal) {
                TextButton(onClick = edit) { Text("编辑当前委托") }
                TextButton(onClick = { controller.requestFeature("automation", "snoozeCommitment", JSONObject().put("id", id).put("revision", revision).put("until", System.currentTimeMillis() + 3_600_000)) }, enabled = "automation.snoozeCommitment" !in state.moduleBusy) { Text("暂缓跟进一小时") }
                TextButton(onClick = { confirmCancel = JSONObject(record.toString()) }, enabled = "automation.cancelCommitment" !in state.moduleBusy) { Text("取消这项承诺", color = Danger) }
            }
            FollowUpReceipts(id, state, controller)
            state.moduleErrors.filterKeys { it.startsWith("automation.") && it.endsWith("Commitment") }.values.distinct().forEach { Text(it, color = Danger, fontSize = 12.sp) }
        }
    }, confirmButton = { if (!terminal) Button(onClick = { confirmComplete = JSONObject(record.toString()) }, enabled = state.feature("automation").optBoolean("completionEvidenceAvailable") && "automation.completeCommitment" !in state.moduleBusy) { Text("确认已完成") } else TextButton(onClick = close) { Text("关闭") } }, dismissButton = { if (!terminal) TextButton(onClick = close) { Text("返回") } })
    confirmComplete?.let { frozen -> AlertDialog(onDismissRequest = { confirmComplete = null }, containerColor = Color.White, title = { Text("确认当前承诺已经完成？") }, text = { Text("这会记录你对当前版本的真实确认。编辑过的承诺需要以新版本重新核对，不沿用旧任务的完成结论。") }, confirmButton = { Button(onClick = { controller.requestFeature("automation", "completeCommitment", JSONObject().put("id", frozen.optString("id")).put("revision", frozen.optLong("revision"))); confirmComplete = null }) { Text("我已核对，确认完成") } }, dismissButton = { TextButton(onClick = { confirmComplete = null }) { Text("尚未完成") } }) }
    confirmCancel?.let { frozen -> AlertDialog(onDismissRequest = { confirmCancel = null }, containerColor = Color.White, title = { Text("取消这项承诺？") }, text = { Text("取消后保留历史，不再作为待跟进候选。") }, confirmButton = { TextButton(onClick = { controller.requestFeature("automation", "cancelCommitment", JSONObject().put("id", frozen.optString("id")).put("revision", frozen.optLong("revision"))); confirmCancel = null }) { Text("取消承诺", color = Danger) } }, dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text("保留") } }) }
}

@Composable
private fun CommitmentEditor(record: JSONObject?, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    var title by remember { mutableStateOf(record?.optString("title").orEmpty()) }
    var details by remember { mutableStateOf(record?.optString("details").orEmpty()) }
    var hours by remember { mutableStateOf(if (record == null) "24" else "") }
    var taskId by remember { mutableStateOf(record?.optString("taskId").orEmpty()) }
    var chooseTask by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val command = if (record == null) "createCommitment" else "updateCommitment"
    val busy = "automation." + command in state.moduleBusy
    LaunchedEffect(busy) { if (submitted && !busy && state.moduleErrors["automation." + command] == null) close() }
    AlertDialog(onDismissRequest = { if (!busy) close() }, containerColor = Color.White, title = { Text(if (record == null) "新增明确委托" else "编辑当前承诺") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("承诺目标") }, minLines = 1, maxLines = 3)
            OutlinedTextField(details, { details = it }, label = { Text("具体要求") }, minLines = 2, maxLines = 6)
            OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(4) }, label = { Text(if (record == null) "多少小时后跟进" else "改为多少小时后（可留空）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (record != null) CommitmentCaption("留空保留原目标时间：" + commitmentTime(record.optLong("dueAt")))
            TextButton(onClick = { chooseTask = !chooseTask }) { Text(if (taskId.isBlank()) "关联一个现有任务（可选）" else "已关联：" + (state.tasks.find { it.id == taskId }?.goal ?: "已有任务").take(35), fontSize = 12.sp) }
            if (chooseTask) Column(Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
                TextButton(onClick = { taskId = ""; chooseTask = false }) { Text("暂不关联任务") }
                state.tasks.take(30).forEach { task -> TextButton(onClick = { taskId = task.id; chooseTask = false }) { Text(task.goal, fontSize = 12.sp, maxLines = 2) } }
            }
            state.moduleErrors["automation." + command]?.let { Text(it, color = Danger, fontSize = 12.sp) }
        }
    }, confirmButton = { Button(onClick = {
        submitted = true
        val body = JSONObject().put("title", title.trim()).put("details", details.trim()).put("taskId", taskId)
        hours.toLongOrNull()?.let { body.put("dueAt", System.currentTimeMillis() + it * 3_600_000) }
        if (record == null) { body.put("kind", "explicit_user"); controller.requestFeature("automation", command, JSONObject().put("commitment", body)) }
        else controller.requestFeature("automation", command, JSONObject().put("id", record.optString("id")).put("revision", record.optLong("revision")).put("patch", body))
    }, enabled = !busy && title.isNotBlank() && title.length <= 200 && details.length <= 4000 && (record != null && hours.isBlank() || (hours.toIntOrNull() ?: 0) in 1..8760)) { Text(if (busy) "保存中" else "保存委托") } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("取消") } })
}

internal fun commitmentStatus(value: String): String = when (value) { "pending" -> "待跟进"; "snoozed" -> "已暂缓"; "completed" -> "已确认完成"; "cancelled" -> "已取消"; else -> value }
private fun commitmentTime(value: Long): String = if (value <= 0) "尚未确定" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
@Composable private fun CommitmentCaption(value: String) { Text(value, color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)) }
