package dev.pocketengine.ui.groups

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Actual TaskReceipt + optional native projection. No task, approval or outbox is owned here. */
@Composable
internal fun GroupTaskJourney(
    groupName: String,
    reference: JSONObject?,
    result: JSONObject?,
    presentationValue: JSONObject?,
    deliveries: List<JSONObject>,
    busy: Boolean,
    readError: Boolean,
    onInspect: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onReview: (GroupTaskReviewSelection) -> Unit,
    onSend: (String) -> Unit,
    onInspectDelivery: (String) -> Unit,
    onOpenArtifact: ((GroupArtifactSelection) -> Unit)?
) {
    val task = result?.optJSONObject("task")
    val status = task?.optString("status").orEmpty()
    val presentation = if (task == null) null else GroupTaskPresentation.read(reference,
        result?.optJSONObject("presentation") ?: presentationValue, task)
    val taskDeliveries = deliveries.filter { it.opt("requestId") == reference?.opt("requestId") && it.opt("taskId") == reference?.opt("taskId") }
    val outputs = if (task?.opt("resultPrivate") == true) emptyList() else GroupUiState.rows(task?.optJSONArray("outputs"))
    val goal = reference?.optString("goal") ?: "群任务"
    var fullGoal by remember(reference?.optString("requestId")) { mutableStateOf(false) }
    var goalOverflows by remember(reference?.optString("requestId")) { mutableStateOf(false) }
    var cancelReview by remember { mutableStateOf<JSONObject?>(null) }
    var sendReview by remember { mutableStateOf<JSONObject?>(null) }
    var localError by remember(reference?.optString("requestId")) { mutableStateOf<String?>(null) }

    fun reviewTask(intent: String) {
        if (reference == null || task == null) return
        try {
            check(GroupUiState.revision(task) > 0) { "请先查询原任务版本" }
            onReview(GroupTaskReviewSelection(reference.getString("requestId"), task.getString("taskId"),
                reference.getString("spaceId"), GroupUiState.revision(task), task.optString("runId"), task.optInt("coordinatorEpoch"), intent))
        } catch (failure: Exception) { localError = failure.message }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("任务目标", style = MaterialTheme.typography.titleMedium)
        Text(goal, style = MaterialTheme.typography.bodyLarge, maxLines = if (fullGoal) Int.MAX_VALUE else 4,
            overflow = if (fullGoal) TextOverflow.Clip else TextOverflow.Ellipsis,
            onTextLayout = { if (!fullGoal) goalOverflows = it.hasVisualOverflow })
        if (goalOverflows || fullGoal) TextButton(onClick = { fullGoal = !fullGoal }) { Text(if (fullGoal) "收起目标" else "查看完整目标") }
        Text(if (result?.opt("known") == false) "提交结果待核对" else GroupUiState.taskLabel(status), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onInspect, enabled = !busy) { Text("查询状态") }
            TextButton(onClick = onOpen, enabled = reference != null) { Text("打开原任务") }
        }
        if (readError) Text("最近一次查询未成功。以下保留上次读取的结果，请重新查询后再处理。", color = MaterialTheme.colorScheme.error)
        Text(if (reference?.optString("audience") == "group") "结果回到「$groupName」" else "结果受众：原发起人（需通道支持）",
            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (reference?.optString("visibility") == "members") "群成员可看进度" else "仅发起人可看任务", style = MaterialTheme.typography.bodySmall)
        reference?.optJSONObject("messageParts")?.let { structured ->
            val parts = GroupUiState.rows(structured.optJSONArray("parts"))
            val quotes = parts.count { it.optString("kind") == "quote" }
            val frozen = parts.sumOf { it.optJSONArray("artifactRefs")?.length() ?: 0 }
            val unsupported = parts.count { it.optString("kind") in setOf("unsupported", "unsupported_media") }
            var sourceDetails by remember(reference?.optString("requestId")) { mutableStateOf(false) }
            if (quotes > 0 || frozen > 0 || unsupported > 0) {
                Text("原群资料 · 引用$quotes · 已冻结文件$frozen · 未处理段$unsupported", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { sourceDetails = !sourceDetails }) { Text(if (sourceDetails) "收起引用与处理范围" else "核对引用与处理范围") }
                if (sourceDetails) {
                    val projected = remember(structured.toString()) { runCatching { dev.pocketengine.features.channels.ChannelMessageParts.context(structured) } }
                    val context = projected.getOrNull()
                    if (context != null) {
                        if (context.isNotBlank()) Text(context, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("原引用或处理范围超出当前安全显示上限，或格式不完整；本页未展示其全文。原始parts仍保留在原任务中，请按原编号查询；不表示资料已经全部读取或处理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text("文件已冻结只证明原字节可核对，不代表模型已读取或正确理解；图片能力及预算不足会明确拒绝。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (result?.opt("known") == false) Text("尚未在原任务记录中找到本次提交。可继续查询；请勿重复提出相同任务，以免产生第二次执行。")
        if (task == null && result?.opt("known") != false) Text("打开任务后读取实际状态；群消息已提交不代表任务已执行。")

        val approval = presentation?.approvalState.orEmpty()
        if (approval.isNotBlank() && approval != "not_required") {
            Text(GroupUiState.approvalLabel(approval), fontWeight = FontWeight.Medium)
            if (presentation?.approvalMessage?.isNotBlank() == true) Text(presentation.approvalMessage)
            if (approval in setOf("required", "pending")) {
                Text("请在手机主人的原任务页核对本次请求。审批结果和后续执行会在查询后更新。")
                Button(onClick = { reviewTask("approval") }, enabled = !busy && !readError) { Text("前往主人审批") }
            } else if (approval in setOf("denied", "revoked")) {
                Text("此审批记录不会因刷新或重启而被自动重试。若要重新处理，请在原任务中核对新的权限。")
                OutlinedButton(onClick = { reviewTask("approval") }, enabled = !busy) { Text("查看审批记录") }
            }
        } else if (status in setOf("awaiting_user", "needs_confirmation")) {
            Text(if (status == "needs_confirmation") "请在原任务页核对执行结果是否完成。" else "原任务正在等待主人补充信息或作出决定。")
            Button(onClick = { reviewTask("inspect") }, enabled = !busy && !readError) { Text("前往原任务处理") }
        }

        if (status in setOf("failed", "interrupted", "paused", "reconciling", "cancelled")) {
            HorizontalDivider()
            Text(when (status) {
                "failed" -> "任务失败，结果未完成"
                "interrupted" -> "任务已中断，先核对已有结果"
                "paused" -> "任务已暂停"
                "reconciling" -> "外部效果尚未核对清楚"
                else -> "任务已取消"
            }, fontWeight = FontWeight.Medium)
            Text(presentation?.recoveryMessage?.takeIf(String::isNotBlank) ?: when (status) {
                "reconciling" -> "先查询原记录，确认已经发生的效果；查询不会重做操作。"
                "paused", "interrupted" -> "重启后仍沿用原任务记录。继续之前，需要主人在原任务页重新核对权限和已有操作。"
                else -> "查看原任务中的失败或取消原因。此页不会把一次失败直接重做为新任务。"
            })
            if (presentation?.canReviewContinue == true) Button(onClick = { reviewTask("continue") }, enabled = !busy && !readError) { Text("核对后继续") }
            else OutlinedButton(onClick = onOpen, enabled = !busy) { Text("查看原任务与恢复选项") }
        }
        if (task?.optString("runId")?.isNotBlank() == true && status !in setOf("completed", "failed", "cancelled", "interrupted"))
            TextButton(onClick = { cancelReview = JSONObject(task.toString()) }, enabled = !busy && !readError) { Text("取消此执行代次") }

        if (outputs.isNotEmpty()) {
            HorizontalDivider()
            Text("任务结果", style = MaterialTheme.typography.titleMedium)
        } else if (status == "completed" && task?.opt("resultPrivate") != true) Text("任务已完成，当前回执没有可打开或发送的产物。可在原任务中查看完成说明。")
        for (output in outputs) {
            val outputId = output.optString("outputId")
            val expectedDeliveryId = GroupUiState.deliveryId(reference!!.getString("requestId"), outputId, reference.getString("audience"))
            val existing = taskDeliveries.firstOrNull { it.optString("deliveryId") == expectedDeliveryId }
            val isText = output.optString("mimeType") == "text/plain"
            val name = presentation?.artifactHints?.get(outputId)?.displayName
            Text(if (isText) "文字结果" else name ?: "${mediaLabel(output.optString("mimeType"))} · ${sizeLabel(output.optLong("bytes"))}",
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!isText) {
                OutlinedButton(enabled = !busy && !readError, onClick = {
                    try {
                        val selection = groupArtifactSelection(reference, task!!, output, presentation, existing?.optString("deliveryId"))
                        if (onOpenArtifact == null) onOpen() else onOpenArtifact(selection)
                    } catch (failure: Exception) { localError = failure.message }
                }) { Text(if (onOpenArtifact == null) "在原任务查看文件" else "打开产物") }
            }
            if (status == "completed" && existing == null) {
                OutlinedButton(onClick = { sendReview = JSONObject(output.toString()).put("taskRevision", GroupUiState.revision(task)) }, enabled = !busy && !readError) {
                    Text(if (reference.optString("audience") == "group") "发回原群" else "回复原发起人")
                }
            }
            if (existing != null) DeliveryStatus(existing, busy, onInspectDelivery)
            if (isText) ExpandableResult("结果正文", output.optString("text"), 8)
            else Text("${output.optString("mimeType")} · ${sizeLabel(output.optLong("bytes"))}", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
        }
        val linkedIds = outputs.map { GroupUiState.deliveryId(reference!!.getString("requestId"), it.optString("outputId"), reference.getString("audience")) }.toSet()
        for (row in taskDeliveries.filter { it.optString("deliveryId") !in linkedIds }) DeliveryStatus(row, busy, onInspectDelivery)
        if (presentation?.phase?.isNotBlank() == true || presentation?.progressMessage?.isNotBlank() == true) {
            Text("当前进度", style = MaterialTheme.typography.titleMedium)
            if (presentation.phase.isNotBlank()) Text(phaseLabel(presentation.phase), fontWeight = FontWeight.Medium)
            if (presentation.progressMessage.isNotBlank()) Text(presentation.progressMessage)
            if (presentation.completedSteps != null && presentation.totalSteps != null) {
                LinearProgressIndicator(progress = { presentation.completedSteps.toFloat() / presentation.totalSteps.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("已完成 ${presentation.completedSteps} / ${presentation.totalSteps} 步", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (presentation != null) Text("读取于 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(presentation.observedAt))}", style = MaterialTheme.typography.bodySmall)
        if (task?.optString("summary")?.isNotBlank() == true) ExpandableResult("任务说明", task.optString("summary"), 6)
        if (task?.opt("resultPrivate") == true) Text("此结果仅原发起人可见；共享进度不开放结果正文或文件。")

        localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    cancelReview?.let { frozen ->
        val current = task != null && frozen.opt("taskId") == task.opt("taskId") && frozen.opt("runId") == task.opt("runId") &&
            frozen.opt("coordinatorEpoch") == task.opt("coordinatorEpoch") && GroupUiState.revision(frozen) == GroupUiState.revision(task)
        AlertDialog(onDismissRequest = { cancelReview = null }, title = { Text("取消这次执行？") },
            text = { Text(if (current) "只取消刚核对的执行代次；已经完成的外部操作不会撤回。" else "原任务已变化，请关闭后重新查询。") },
            confirmButton = { TextButton(enabled = current && !busy && !readError, onClick = { cancelReview = null; onCancel() }) { Text("确认取消") } },
            dismissButton = { TextButton(onClick = { cancelReview = null }) { Text("保留任务") } })
    }
    sendReview?.let { frozen ->
        val current = GroupUiState.revision(task) == frozen.optLong("taskRevision") && status == "completed" &&
            outputs.any { it.opt("outputId") == frozen.opt("outputId") } && taskDeliveries.none {
                it.optString("deliveryId") == GroupUiState.deliveryId(reference!!.getString("requestId"), frozen.getString("outputId"), reference.getString("audience"))
            }
        val target = if (reference?.optString("audience") == "group") "原群「$groupName」" else "原发起人"
        AlertDialog(onDismissRequest = { sendReview = null }, title = { Text("发送到$target？") },
            text = { Text(if (current) "发送原任务中的这份结果。发送前会重新核对群权限、结果受众和平台回复窗口。平台接受后仍需核对送达回执。" else "原任务或投递记录已变化，请关闭后查询。") },
            confirmButton = { TextButton(enabled = current && !busy && !readError, onClick = { sendReview = null; onSend(frozen.getString("outputId")) }) { Text("确认发送") } },
            dismissButton = { TextButton(onClick = { sendReview = null }) { Text("暂不发送") } })
    }
}

@Composable
private fun ExpandableResult(title: String, body: String, lines: Int) {
    var expanded by remember(title, body) { mutableStateOf(false) }
    var overflows by remember(title, body) { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.labelLarge)
    Text(body, maxLines = if (expanded) Int.MAX_VALUE else lines, overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
    if (overflows || expanded) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起$title" else "展开$title") }
}

@Composable
private fun DeliveryStatus(row: JSONObject, busy: Boolean, onInspect: (String) -> Unit) {
    val state = row.optString("state")
    var details by remember(row.optString("deliveryId")) { mutableStateOf(false) }
    Text(GroupUiState.deliveryLabel(state), fontWeight = FontWeight.Medium)
    val error = row.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)
        ?: row.optString("message").takeIf(String::isNotBlank)
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
    if (state in setOf("unknown", "sending", "accepted", "failed")) {
        Text(when (state) {
            "unknown", "sending" -> "重启后可沿原投递记录查询；结果未核对前不会再次发送。"
            "accepted" -> "平台接受了请求，尚不能确认结果已送回原群。"
            else -> "结果尚未送达。先查看原投递记录与失败原因。"
        }, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onInspect(row.getString("deliveryId")) }, enabled = !busy) { Text(if (state == "failed") "查询失败回执" else "核对投递结果") }
    }
    if (row.optString("platformMessageId").isNotBlank()) {
        TextButton(onClick = { details = !details }) { Text(if (details) "收起投递凭据" else "查看投递凭据") }
        if (details) Text("平台消息 ${row.optString("platformMessageId")}", style = MaterialTheme.typography.bodySmall)
    }
}

private fun phaseLabel(value: String): String = when (value) {
    "observing" -> "读取当前情况"; "planning" -> "整理执行方案"; "acting" -> "执行已授权操作"; "verifying" -> "核对结果"; else -> value
}
private fun mediaLabel(value: String) = when { value.startsWith("image/") -> "图片"; value.startsWith("audio/") -> "音频"; value.startsWith("video/") -> "视频"; else -> "文件" }
private fun sizeLabel(value: Long): String = when { value >= 1024 * 1024 -> "${value / (1024 * 1024)} MB"; value >= 1024 -> "${value / 1024} KB"; else -> "$value 字节" }
