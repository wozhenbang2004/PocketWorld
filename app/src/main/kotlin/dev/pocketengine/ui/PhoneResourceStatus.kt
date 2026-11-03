package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import dev.pocketengine.phone.PhoneResourceDisposition
import java.text.DateFormat
import java.util.Date

/** The original task's receipt, including late cleanup; rendering never closes or renews anything. */
@Composable
internal fun PhoneResourceStatus(task: UiTask, state: PocketUiState, controller: PocketWorldController) {
    if (task.executionMode != "background") return
    val resources = runCatching { PhoneResourceDisposition.forTask(task.raw) }.getOrNull() ?: return
    val window = LocalView.current
    val status = resources.optString("state")
    if (status == "held_for_owner" && task.status in setOf("preparing", "running", "pausing")) return
    val held = status == "held_for_owner" && state.activeTaskId == task.id && task.status in setOf("paused", "awaiting_user", "reconciling")
    val pending = status in setOf("release_unknown", "release_pending") || status == "held_for_owner" && !held
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("phone_resource_status")) {
        Text(when {
            held -> "原独立屏暂为你保留"
            status == "release_confirmed" -> "原独立屏已确认释放"
            status == "release_pending" || task.status in setOf("stopping", "cancelling") -> "原独立屏正在等待释放回执"
            else -> "原独立屏的释放状态待核对"
        }, style = MaterialTheme.typography.titleSmall)
        if (held) {
            Text(when (resources.optString("reason")) {
                "reconciliation" -> "原动作结果需要你核对，助手不会重放。"
                "manual_takeover" -> "你已接管此会话，助手保持暂停。"
                else -> "原任务正在等你处理，助手保持暂停。"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val deadline = resources.optLong("deadlineAt", -1)
            Text(when (resources.optString("deadlinePolicy")) {
                "original_task_deadline" -> if (deadline > 0) "原任务期限：${DateFormat.getDateTimeInstance().format(Date(deadline))}" else "原任务期限暂不可核对。"
                "until_original_task_stops" -> "原范围未设固定结束时间；你可随时停止并释放。"
                else -> "原任务期限暂不可核对；你可随时停止并释放。"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.raw.optString("actorId") == "owner" && task.raw.optString("spaceId") == "private")
                TextButton(onClick = { controller.releaseHeldPhoneResources(task, window) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("停止原任务并释放独立屏") }
        } else if (pending) {
            Text("停止或期限届满不等于已释放；只有原会话的实际回执会确认结果。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = controller::openBackgroundSetup, modifier = Modifier.heightIn(min = 48.dp)) { Text("核对原后台连接") }
        }
        if (resources.optString("audioRecovery") == "pending") Text("本任务的声音复原仍待核对。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text("仅回收原任务拥有的显示与活动，不强行停止其他 App 的进程。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
