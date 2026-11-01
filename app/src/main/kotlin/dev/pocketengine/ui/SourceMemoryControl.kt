package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import dev.pocketengine.ui.memory.TaskMemorySourceDocument
import dev.pocketengine.ui.memory.TaskMemoryEvidenceGuard

/** Only IDs supplied by durable host records are accepted; no identifier is inferred from prose. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceMemoryControl(kind: String, id: String, initialText: String, state: PocketUiState, controller: PocketWorldController, compact: Boolean = false) {
    var open by remember(id) { mutableStateOf(false) }
    var learning by remember(id) { mutableStateOf(false) }
    var menu by remember(id) { mutableStateOf(false) }
    if (compact) Box {
        TextButton(onClick = { menu = true }) { Text("消息操作 ⋯", fontSize = 12.sp) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("明确保存这条消息为记忆") }, onClick = { menu = false; open = true })
            DropdownMenuItem(text = { Text("提炼待审候选") }, onClick = { menu = false; learning = true })
        }
    } else FlowRow {
        TextButton(onClick = { open = true }) { Text(if (kind == "task") "从已确认结果保存记忆" else "明确保存这条消息为记忆", fontSize = 11.sp) }
        TextButton(onClick = { learning = true }) { Text("提炼待审候选", fontSize = 11.sp) }
    }
    if (open) SourceMemoryEditor(kind, id, initialText, state, controller) { open = false }
    if (learning) SourceLearningDialog(kind, id, state, controller) { learning = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceMemoryEditor(kind: String, id: String, initialText: String, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    var text by remember { mutableStateOf(initialText.take(8000)) }
    var layer by remember { mutableStateOf("fact") }
    var confirmResave by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<String?>(null) }
    var submittedAction by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<JSONObject?>(null) }
    val ordinary = if (kind == "task") "saveTaskMemory" else "saveMessageMemory"
    val resave = if (kind == "task") "resaveTaskMemory" else "resaveMessageMemory"
    val busy = listOf(ordinary, resave).any { "owner." + it in state.moduleBusy }
    fun save(action: String) {
        submittedAction = action
        submitted = controller.requestOwnerAction(action, JSONObject().put(if (kind == "task") "taskId" else "messageId", id).put("record", JSONObject().put("layer", layer).put("text", text.trim())))
    }
    LaunchedEffect(state.moduleResults["owner." + submittedAction]) {
        val key = "owner." + submittedAction
        if (submitted != null && state.moduleResultInputs[key]?.optString("_requestId") == submitted) {
            val result = state.moduleResults[key]
            saved = result?.optJSONObject("record") ?: result?.takeIf { it.optString("id").isNotBlank() && it.has("revision") }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, containerColor = Color.White, title = { Text(if (saved == null) "明确保存到记忆" else "记忆已保存") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (saved == null) {
                Text("只有你在此明确保存，才会形成记忆。来源身份和当前版本由宿主读取核验。", fontSize = 12.sp, lineHeight = 21.sp)
                OutlinedTextField(text, { text = it }, label = { Text("你要保留的内容") }, minLines = 3, maxLines = 9)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { mapOf("fact" to "事实", "preference" to "偏好", "episodic" to "经历", "procedural" to "步骤").forEach { (value, label) -> FilterChip(layer == value, { layer = value }, label = { Text(label, fontSize = 12.sp) }) } }
                listOf(ordinary, resave).mapNotNull { state.moduleErrors["owner." + it] }.distinct().forEach { Text(it, color = Danger, fontSize = 12.sp, lineHeight = 21.sp) }
                TextButton(onClick = { confirmResave = true }, enabled = !busy && text.isNotBlank() && text.length <= 8000) { Text("重新保存已遗忘来源…", fontSize = 11.sp) }
            } else {
                Text("已保留真实来源关联。忘记来源后，衍生记忆不会继续召回。", fontSize = 12.sp, lineHeight = 21.sp)
                saved?.let { record -> OutlinedButton(onClick = { controller.requestFeature("memory", "setCore", JSONObject().put("id", record.optString("id")).put("revision", record.optLong("revision")).put("core", true)); close() }, enabled = record.optString("id").isNotBlank() && "memory.setCore" !in state.moduleBusy) { Text("另行选择为核心偏好", fontSize = 12.sp) } }
            }
        }
    }, confirmButton = { if (saved != null) Button(onClick = close) { Text("完成") } else Button(onClick = { save(ordinary) }, enabled = !busy && text.isNotBlank() && text.length <= 8000) { Text(if (busy) "正在核验保存" else "明确保存") } }, dismissButton = { if (saved == null) TextButton(onClick = close, enabled = !busy) { Text("取消") } })
    if (confirmResave) AlertDialog(onDismissRequest = { confirmResave = false }, containerColor = Color.White, title = { Text("重新保存已遗忘来源？") }, text = { Text("你将明确允许这条已遗忘来源重新形成记忆。宿主仍会核验来源存在及当前版本，不会恢复其他已遗忘内容。") }, confirmButton = { Button(onClick = { confirmResave = false; save(resave) }, enabled = !busy) { Text("重新保存已遗忘来源") } }, dismissButton = { TextButton(onClick = { confirmResave = false }) { Text("保留遗忘状态") } })
}


/** Opening previews the exact host version only; a second explicit action sends it to the model. */
@Composable
internal fun SourceLearningDialog(kind: String, id: String, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    var preparedId by remember(kind, id) { mutableStateOf<String?>(null) }
    var proposedId by remember(kind, id) { mutableStateOf<String?>(null) }
    var submittedSource by remember(kind, id) { mutableStateOf<JSONObject?>(null) }
    var localMessage by remember(kind, id) { mutableStateOf<String?>(null) }
    var stoppedAcknowledged by remember(kind, id) { mutableStateOf(false) }
    var showOriginalSource by remember(kind, id) { mutableStateOf(false) }
    DisposableEffect(kind, id) { controller.enterOwnerDomain("learning"); onDispose { controller.leaveOwnerDomain("learning") } }
    fun readSource() {
        proposedId = null; submittedSource = null; localMessage = null; stoppedAcknowledged = false; showOriginalSource = false
        preparedId = controller.requestOwnerDomain("learning", "prepareLearningSource", JSONObject()
            .put("kind", if (kind == "task" || kind == "task_evidence") "task_evidence" else if (kind == "workspace_file") "workspace_file" else "message")
            .put("sourceId", if (kind == "task") "task:$id:verified_result" else id))
    }
    LaunchedEffect(kind, id) { readSource() }
    val responses = state.ownerResponses("learning")
    val prepared = responses["prepareLearningSource"].takeIf { it?.optString("requestId") == preparedId }
    val proposed = responses["proposeLearning"].takeIf { it?.optString("requestId") == proposedId }
    val source = prepared?.takeIf { it.optBoolean("ok") }?.optJSONObject("result")
    val outcome = remember(proposed?.toString(), submittedSource?.toString()) { runCatching {
        proposed?.takeIf { it.optBoolean("ok") }?.getJSONObject("result")?.also { result ->
            require(result.opt("formalMemorySaved") == false && result.optString("hostTaskId").isNotBlank() && result.optString("runId").isNotBlank() && result.optInt("epoch") > 0 &&
                TaskMemoryEvidenceGuard.sameSource(submittedSource, result.optJSONObject("sourceRef") ?: JSONObject())) { "未取得原来源与原提炼任务的完整回执，请核对任务和候选。" }
            val candidate = result.optJSONObject("candidate")
            if (candidate == null) require(result.opt("proposed") == false && result.opt("reviewRequired") == false && result.optString("outcome") == "no_candidate") { "未形成候选的状态不完整。" }
            else {
                val pending = candidate.optString("status") == "pending"
                require(candidate.optString("status") in setOf("pending", "accepted", "rejected", "invalidated", "archived") && result.opt("proposed") == pending && result.opt("reviewRequired") == pending &&
                    result.optString("outcome") == (if (pending) "awaiting_review" else "existing_candidate_barrier")) { "候选处理状态与原生回执不一致。" }
                require(candidate.optString("ownerId") == "owner" && candidate.optString("spaceId") == "private" && candidate.optString("id").isNotBlank() &&
                    TaskMemoryEvidenceGuard.revision(candidate) > 0 && candidate.getJSONArray("sourceRefs").length() == 1 &&
                    TaskMemoryEvidenceGuard.sameSource(submittedSource, candidate.getJSONArray("sourceRefs").getJSONObject(0))) { "候选不是你核对的原始来源版本。" }
            }
        }
    } }
    val result = outcome.getOrNull()
    val busy = "proposeLearning" in state.ownerPending("learning") || "prepareLearningSource" in state.ownerPending("learning")
    val expectedKind = if (kind == "task" || kind == "task_evidence") "task_evidence" else if (kind == "workspace_file") "workspace_file" else "message"
    val expectedSourceId = if (kind == "task") "task:$id:verified_result" else id
    val sourceAllowed = source != null && source.optString("status") == "current" && source.opt("learningAllowed") == true && source.opt("modelCalled") == false && source.opt("text") is String &&
        source.optJSONObject("sourceRef")?.let { ref -> ref.optString("ownerId") == "owner" && ref.optString("spaceId") == "private" && ref.optString("kind") == expectedKind &&
            ref.optString("sourceId") == expectedSourceId && TaskMemoryEvidenceGuard.revision(ref) > 0 } == true
    fun stop(leave: Boolean) {
        val active = proposedId
        if (!busy || active == null) { if (leave) close(); return }
        val cancelId = controller.cancelLearning(active)
        val envelope = controller.state.ownerResponses("learning")["cancelLearning"]?.takeIf { it.optString("requestId") == cancelId }
        val receipt = envelope?.takeIf { it.optBoolean("ok") }?.optJSONObject("result")
        when {
            receipt?.opt("cancelled") == true -> { stoppedAcknowledged = true; localMessage = "本次提炼已停止；不会在之后提交迟到候选。"; if (leave) close() }
            receipt?.optString("outcome") == "already_settled" -> localMessage = "提炼已完成提交，停止没有撤销已有候选；正在读取原回执。"
            receipt?.optString("outcome") == "not_active" -> localMessage = "正在核对原提炼记录；这里不会补发模型请求。"
            else -> localMessage = envelope?.optString("error") ?: "停止回执未确认，请核对原任务和候选。"
        }
    }
    AlertDialog(onDismissRequest = { stop(true) }, containerColor = Color.White, title = { Text(if (result?.optBoolean("proposed") == true) "候选待审，尚未采用" else if (stoppedAcknowledged) "本次提炼已停止" else "从真实来源提炼候选") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("默认不自动学习。提炼会把下面这个版本的来源正文发给当前模型，只生成待审候选，不写入正式记忆，也不启用技能。", fontSize = 12.sp, lineHeight = 21.sp)
            listOfNotNull(prepared, proposed).filter { !it.optBoolean("ok") && !(stoppedAcknowledged && it.optString("requestId") == proposedId) }.forEach { Text(it.optString("error", "操作未完成，请核对原来源"), color = Danger, fontSize = 12.sp, lineHeight = 20.sp) }
            outcome.exceptionOrNull()?.message?.let { Text(it, color = Danger, fontSize = 12.sp, lineHeight = 21.sp) }
            localMessage?.let { Text(it, fontSize = 12.sp, lineHeight = 21.sp) }
            if (result != null) {
                Text(if (result.optBoolean("proposed")) "请到原学习候选页核对正文、依据和适用条件；接受后仍不等于效果已经验证。" else if (result.optString("outcome") == "existing_candidate_barrier") "该来源与内容已有处理结果：" + when (result.getJSONObject("candidate").optString("status")) { "rejected" -> "已拒绝"; "accepted" -> "已采用"; "archived" -> "已归档"; else -> "已失效" } + "。未重新开启审核。" else result.optString("reason", "没有形成候选。"), fontSize = 12.sp, lineHeight = 21.sp)
                TextButton(onClick = { controller.focusTask(result.getString("hostTaskId")); close() }) { Text("查看原提炼任务") }
            }
            if (sourceAllowed && source != null) {
                Text(source.optString("title"), fontSize = 14.sp)
                Text("来源版本 " + source.optJSONObject("sourceRef")?.optLong("revision"), color = SecondaryInk, fontSize = 11.sp)
                if (result != null || stoppedAcknowledged) TextButton(onClick = { showOriginalSource = !showOriginalSource }) { Text(if (showOriginalSource) "收起这次原始来源" else "查看这次原始来源") }
                if (result == null && !stoppedAcknowledged || showOriginalSource) TaskMemorySourceDocument(source)
                if (!busy && result == null) source.optJSONObject("sourceRef")?.let { exact -> dev.pocketengine.ui.memory.SourceLearningPlanPicker(exact, controller) { task -> controller.focusTask(task); close() } }
            } else if (source != null) {
                Text(source.optString("reason", "原来源暂不可用于提炼；未发送正文。"), fontSize = 12.sp, lineHeight = 21.sp)
            } else if (prepared == null) Text("正在读取宿主保存的真实版本，尚未调用模型…", fontSize = 12.sp)
            if (busy && proposedId != null) TextButton(onClick = { stop(false) }) { Text("停止本次提炼") }
            if (!busy && result == null) TextButton(onClick = { readSource() }) { Text("重新读取来源") }
        }
    }, confirmButton = {
        if (result?.optJSONObject("candidate") != null) Button(onClick = { close(); controller.openConnection("memoryReview") }) { Text(if (result.optBoolean("proposed")) "去审核候选" else "查看已有候选") }
        else Button(onClick = { source?.optJSONObject("sourceRef")?.let { ref -> submittedSource = JSONObject(ref.toString()); proposedId = controller.requestOwnerDomain("learning", "proposeLearning", JSONObject().put("sourceRef", JSONObject(ref.toString()))) } }, enabled = sourceAllowed && !busy && result == null && proposedId == null) { Text(if (busy && proposedId != null) "提炼中" else "发送来源并提炼候选") }
    }, dismissButton = { TextButton(onClick = { stop(true) }) { Text(if (busy && proposedId != null) "停止并返回" else "返回") } })
}
