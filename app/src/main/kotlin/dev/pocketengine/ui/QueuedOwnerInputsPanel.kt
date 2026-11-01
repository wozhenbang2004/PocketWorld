package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** View state for the original native mailbox. It is not a second persistent queue. */
internal data class QueuedOwnerInputsPage(
    val window: Long, val task: String, val conversation: String, val viewEpoch: Int,
    val items: List<JSONObject> = emptyList(), val reading: Boolean = true,
    val readingMessage: String? = null, val saving: Boolean = false,
    val error: String? = null, val notice: String? = null,
    val editor: QueuedOwnerInputEditor? = null, val taskStatus: String? = null
)

/** A list refresh cancels an outstanding detail read, without discarding a loaded edit draft. */
internal fun QueuedOwnerInputsPage.readingList()=copy(reading=true,readingMessage=null,error=null)

internal data class QueuedOwnerInputEditor(
    val message: String, val queueRevision: Long, val messageRevision: Long,
    val baseline: String, val originalMode: String, val text: String, val mode: String,
    val attachmentCount: Int, val referenceCount: Int, val withdrawn: Boolean
)

internal fun queuedInputStatus(row: JSONObject): String = when (row.optString("queueState")) {
    "pending" -> if (row.optString("mode") == "steer") "等待下一个安全边界" else "待本轮结束后处理"
    "claimed" -> "任务已接手，正在纳入上下文"
    "materialized" -> "已纳入任务上下文"
    "withdrawn" -> "已撤回"
    "admitted" -> "初始目标已接收"
    else -> "历史接手状态待核对"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueuedOwnerInputsPanel(controller: PocketWorldController) {
    val page = controller.queuedInputsPage ?: return
    val visible=controller.queuedInputWindowCurrent
    LaunchedEffect(page.window,visible) { if(!visible)controller.closeQueuedOwnerInputs() }
    if(!visible)return
    var showHistory by remember(page.window) { mutableStateOf(false) }
    val activeStates=setOf("pending","claimed","legacy_unconfirmed","unconfirmed")
    val active=page.items.withIndex().filter { it.value.optString("queueState") in activeStates }
    val history=page.items.withIndex().filter { it.value.optString("queueState") !in activeStates }
    ModalBottomSheet(onDismissRequest = controller::closeQueuedOwnerInputs) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("待处理输入", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = controller::refreshQueuedOwnerInputs, enabled = !page.reading && !page.saving) { Text("刷新") }
            }
            Text("排队输入按接收顺序，在本轮结束后一起加入。提前加入只会等待下一个安全边界，不会重放已开始的动作。",
                color = SecondaryInk, fontSize = 12.sp, lineHeight = 19.sp)
            if(page.taskStatus!=null&&page.taskStatus !in setOf("preparing","running")) {
                Text(if(page.taskStatus in setOf("paused","pausing"))"任务已暂停，待处理输入会保留；继续任务后才会处理。" else "任务未在执行，待处理输入仍保留。请核对后明确继续，不会自动开始新的任务。",color=SecondaryInk,fontSize=12.sp,lineHeight=19.sp)
                TextButton(onClick={controller.closeQueuedOwnerInputs();controller.focusTask(page.task)}){Text("到任务中核对 / 继续")}
            }
            if (page.reading) LinearProgressIndicator(Modifier.fillMaxWidth())
            page.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            page.notice?.let { Text(it, color = SecondaryInk, fontSize = 13.sp) }
            if (!page.reading && active.isEmpty()) Text("目前没有待处理输入。", fontSize = 14.sp)
            if(history.isNotEmpty())TextButton(onClick={showHistory=!showHistory}){Text(if(showHistory)"收起已接收 / 已处理记录" else "已接收 / 已处理记录（${history.size}）")}
            (if(showHistory)page.items.withIndex().toList() else active).forEach { (index, row) ->
                Column(Modifier.fillMaxWidth().testTag("queued_input_${row.optString("messageId")}"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${index + 1} · ${queuedInputStatus(row)}", color = SecondaryInk, fontSize = 12.sp)
                    Text(row.optString("content").ifBlank { "附件输入" } + if (row.optBoolean("contentTruncated")) "…" else "", fontSize = 14.sp, lineHeight = 22.sp)
                    val files = row.optInt("attachmentCount"); val refs = row.optInt("sourceReferenceCount")
                    if (files > 0 || refs > 0) Text(listOfNotNull(if (files > 0) "$files 个附件" else null, if (refs > 0) "$refs 段原文引用" else null).joinToString(" · "), color = SecondaryInk, fontSize = 12.sp)
                    if (row.optBoolean("editable")) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { controller.openQueuedOwnerInputEditor(row.optString("messageId"), false) }, enabled = !page.saving && page.readingMessage == null && page.editor == null) { Text("编辑 / 提前加入") }
                        TextButton(onClick = { controller.openQueuedOwnerInputEditor(row.optString("messageId"), true) }, enabled = !page.saving && page.readingMessage == null && page.editor == null) { Text("撤回") }
                    }
                    if (page.readingMessage == row.optString("messageId")) Text("读取当前版本…", color = SecondaryInk, fontSize = 12.sp)
                    HorizontalDivider(Modifier.padding(top = 5.dp))
                }
            }
            Text("已接手的输入不能在这里改写或撤回；如需纠正，请发送新的补充。附件和原文引用沿用这条消息的选择与授权。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
        }
    }
    page.editor?.let { edit ->
        val latest=page.items.firstOrNull { it.optString("messageId")==edit.message }
        val sameVersion=latest!=null&&latest.optBoolean("editable")&&latest.optLong("queueRevision")==edit.queueRevision&&latest.optLong("ownerMessageRevision")==edit.messageRevision
        AlertDialog(onDismissRequest = controller::closeQueuedOwnerInputEditor,
            title = { Text(if (edit.withdrawn) "撤回这条待处理输入？" else "编辑待处理输入") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (edit.withdrawn) {
                    Text(edit.baseline, fontSize = 14.sp)
                    Text("撤回后不会加入后续请求。消息和撤回记录仍保留。", color = SecondaryInk, fontSize = 12.sp)
                } else {
                    OutlinedTextField(value = edit.text, onValueChange = controller::editQueuedOwnerInputText,
                        enabled = !page.saving, label = { Text("输入内容") }, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 280.dp), maxLines = 10)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = edit.mode == "queue", onClick = { controller.editQueuedOwnerInputMode("queue") }, enabled = !page.saving, label = { Text("本轮结束后") })
                        FilterChip(selected = edit.mode == "steer", onClick = { controller.editQueuedOwnerInputMode("steer") }, enabled = !page.saving, label = { Text("下一安全边界") })
                    }
                    if (edit.attachmentCount > 0 || edit.referenceCount > 0) Text("保留 ${edit.attachmentCount} 个附件和 ${edit.referenceCount} 段原文引用；这里仅修改文字与加入时机。更换附件或引用请撤回后重新选择。", color = SecondaryInk, fontSize = 12.sp)
                    Text("${edit.text.length} / 12000", color = SecondaryInk, fontSize = 11.sp)
                }
                latest?.let { Text("当前："+queuedInputStatus(it), color=SecondaryInk, fontSize=12.sp) }
                if(!sameVersion)Text("输入已被接手或有新修改。当前草稿未保存，可复制文字后作为新的补充。",color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                TextButton(onClick=controller::refreshQueuedOwnerInputs,enabled=!page.saving&&!page.reading){Text(if(page.reading)"读取中…" else "刷新当前状态")}
                page.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            } },
            confirmButton = { Button(onClick = controller::saveQueuedOwnerInput, enabled = !page.saving && !page.reading && sameVersion && (edit.withdrawn || edit.text.length <= 12000 && (edit.text.isNotBlank() || edit.attachmentCount > 0))) { Text(if (page.saving) "保存中…" else if (edit.withdrawn) "确认撤回" else "保存修改") } },
            dismissButton = { TextButton(onClick = controller::closeQueuedOwnerInputEditor, enabled = !page.saving) { Text("返回") } })
    }
}
