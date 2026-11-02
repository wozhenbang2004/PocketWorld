package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Device execution provenance on the ORIGINAL task. Presentation never turns an incoming
 * device principal into owner/private, takes a control lease, resumes or resubmits a request. */
@Composable
internal fun DeviceTaskProvenance(task: UiTask, openDevices: () -> Unit) {
    val origin = task.raw.optJSONObject("origin") ?: return
    if (origin.optString("kind") != "paired_device") return
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("device_task_provenance"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (task.raw.optString("deviceExecutionKind") == "DEVICE_TOOLS") "设备工具任务 · 不调用本机模型" else "设备委派目标 · 使用本机已批准模型", style = MaterialTheme.typography.titleSmall)
        Text("来源：设备 ${origin.optString("originDeviceId").takeLast(4)} · 执行位置：这台手机", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        if (origin.optString("callerDeviceId") != origin.optString("originDeviceId")) Text("经设备 ${origin.optString("callerDeviceId").takeLast(4)} 转交", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        TextButton(onClick = openDevices, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text("查看设备与授权") }
    }
}

@Composable
internal fun DeviceTaskRow(task: UiTask, peer: JSONObject?, actions: JSONObject?, busy: Boolean,
    open: () -> Unit, reviewGoal: () -> Unit, stop: () -> Unit, stopBusy: Boolean = false) {
    val origin = task.raw.optJSONObject("origin")
    val uncertain = task.status in setOf("unknown", "reconciling", "reconciliation_required") || unresolvedOperations(task).isNotEmpty()
    val stopping = task.status in setOf("stopping", "cancelling") || task.raw.optBoolean("cancelRequested") && task.status !in setOf("cancelled", "canceled", "stopped", "completed", "failed")
    val review = actions?.optJSONObject("goalReview")
    val stopAction = actions?.optJSONObject("stop")
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("device_task_${task.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(task.goal.ifBlank { "原设备任务" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        PocketExecutionStatus(
            when { uncertain -> "有操作结果待确认"; stopping -> "正在停止，等待执行端确认"; else -> taskStatus(task.status) },
            when { uncertain -> TaskExecutionPresentation.Tone.UNCERTAIN; stopping -> TaskExecutionPresentation.Tone.WAITING; task.status == "running" -> TaskExecutionPresentation.Tone.RUNNING; task.status == "failed" -> TaskExecutionPresentation.Tone.ERROR; task.status in setOf("cancelled", "canceled", "stopped") -> TaskExecutionPresentation.Tone.STOPPED; task.status == "completed" -> TaskExecutionPresentation.Tone.RETURNED; else -> TaskExecutionPresentation.Tone.NEUTRAL })
        if (origin?.optString("kind") == "paired_device") {
            val mode = if (task.raw.optString("deviceExecutionKind") == "DEVICE_TOOLS") "工具任务 · 不使用模型" else "目标任务 · 使用本机模型"
            Text(mode, style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        } else Text(peer?.let { "关联 ${deviceName(it)}" } ?: "设备协作任务", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        if (task.summary.isNotBlank()) Text(task.summary, style = MaterialTheme.typography.bodyMedium, color = SecondaryInk, maxLines = 3, overflow = TextOverflow.Ellipsis)
        // Wrap instead of a fixed toolbar: all labels remain available at narrow widths/font scale.
        Column {
            if (actionAvailable(review)) Button(onClick = reviewGoal, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("device_goal_review_${task.id}")) { Text("选择模型并继续") }
            TextButton(onClick = open, modifier = Modifier.heightIn(min = 48.dp).testTag("device_task_open_${task.id}"), contentPadding = PaddingValues(0.dp)) { Text(if (uncertain) "核对执行结果" else "查看执行结果") }
            if (actionAvailable(stopAction)) TextButton(onClick = stop, enabled = !stopBusy && !stopping, modifier = Modifier.heightIn(min = 48.dp).testTag("device_task_stop_${task.id}"), contentPadding = PaddingValues(0.dp)) { Text(if (stopping) "停止请求待回执" else "请求停止") }
            if (!actionAvailable(review) && task.raw.optString("deviceExecutionKind") == "GOAL" && task.status in setOf("awaiting_user", "waiting_user", "waiting_dependency")) {
                val reason = review?.optString("reason").orEmpty()
                if (reason.isNotBlank()) Text(reason, style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            }
        }
    }
}

/** Child SQL hold state is deliberately not rendered as a remote execution status. Only an actual
 * receiving-device acceptance or checked terminal receipt can establish those separate facts. */
@Composable
internal fun DeviceChildLinks(snapshot: JSONObject?, peerId: String, parentId: String, openParent: () -> Unit) {
    val links = workspaceTaskLinks(snapshot).filter { it.optString("parentTaskId") == parentId && it.optString("targetDeviceId") == peerId }
    links.forEach { link ->
        val accepted = link.optJSONObject("childBinding")
        val terminal = link.optJSONObject("terminalReceipt")
        val verifiedStopped = terminal?.optBoolean("terminal") == true && terminal.optBoolean("stopped") && !terminal.optBoolean("hasUnresolved", true)
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 16.dp).testTag("device_child_${link.optString("operationId")}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val label = when {
                verifiedStopped && terminal?.optString("status") == "succeeded" -> "子执行端已回传完成与停止回执"
                verifiedStopped && terminal?.optString("status") == "failed" -> "子执行端已回传失败与停止回执"
                verifiedStopped && terminal?.optString("status") == "cancelled" -> "子执行端已确认取消并停止"
                link.optString("holdState").contains("unknown") -> "子操作结果未知 · 预算仍保留"
                accepted != null -> "目标设备已持久接受 · 终态尚未核对"
                else -> "已保留子任务预算 · 尚无目标接受回执"
            }
            PocketExecutionStatus(label, when { !verifiedStopped -> TaskExecutionPresentation.Tone.UNCERTAIN; terminal?.optString("status") == "failed" -> TaskExecutionPresentation.Tone.ERROR; terminal?.optString("status") == "cancelled" -> TaskExecutionPresentation.Tone.STOPPED; else -> TaskExecutionPresentation.Tone.RETURNED })
            Text("执行端 ${shortDeviceId(link.optString("targetDeviceId"))} · 预留工具 ${link.optLong("toolsReserved")} / 模型 ${link.optLong("modelsReserved")}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            if (accepted != null) Text("原子任务 ${shortDeviceId(accepted.optString("localTaskId"))} · 接受于 ${deviceTime(accepted.optLong("acceptedAt"))}", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            if (!verifiedStopped) Text("离线或已发出取消请求都不代表已经停止，不自动释放预留或重提目标。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            TextButton(onClick = openParent, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text("回原父任务核对接受与结果") }
        }
    }
}

internal fun isDeviceWorkspaceTask(task: UiTask): Boolean = task.raw.optJSONObject("origin")?.optString("kind") == "paired_device" ||
    task.raw.optJSONArray("operations").objects().any { it.optString("tool").startsWith("device_") }

internal fun workspaceTaskLinks(snapshot: JSONObject?): List<JSONObject> {
    val value = snapshot?.opt("taskLinks")
    return when (value) { is JSONObject -> value.optJSONArray("links").objects(); is org.json.JSONArray -> value.objects(); else -> emptyList() }
}
internal fun deviceTasksForPeer(tasks: List<UiTask>, peerId: String, snapshot: JSONObject?): List<UiTask> {
    val parents = workspaceTaskLinks(snapshot).filter { it.optString("targetDeviceId") == peerId }.map { it.optString("parentTaskId") }.toSet()
    return tasks.filter { task ->
        val origin = task.raw.optJSONObject("origin")
        task.id in parents || origin?.optString("kind") == "paired_device" && listOf("originDeviceId", "callerDeviceId", "targetDeviceId").any { origin.optString(it) == peerId } ||
            task.raw.optJSONArray("operations").objects().any { it.optString("tool").startsWith("device_") && it.optJSONObject("arguments")?.optString("targetDeviceId") == peerId }
    }.take(20)
}
