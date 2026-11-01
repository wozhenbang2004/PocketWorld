package dev.pocketengine.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.json.JSONObject

/** Delivery state only; a notification cannot confirm an effect or continue a task. */
@Composable
internal fun AssistantTaskNoticeStatus(task: JSONObject, controller: PocketWorldController) {
    val actual = task.optJSONObject("ownerNotification") ?: return
    when (actual.optString("phase")) {
        "permission_required" -> Column {
            Text("系统未允许发送本任务的结果通知，结果已保留在这里。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = controller::openNotifications) { Text("检查通知设置") }
        }
        "posting", "unconfirmed" -> Text("这次通知是否送达尚未确认；请以本页结果为准，不会因此重跑任务。", style = MaterialTheme.typography.bodySmall)
    }
}
