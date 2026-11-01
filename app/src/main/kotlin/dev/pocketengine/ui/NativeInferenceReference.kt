package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** A native receipt locator opens read-only evidence. It never creates/resumes an execution. */
@Composable
internal fun NativeInferenceReference(reference: JSONObject?, controller: PocketWorldController, label: String = "查看模型调用与用量") {
    val id = reference?.optString("taskId").orEmpty()
    if (id.matches(Regex("[a-fA-F0-9-]{36}")) && reference?.opt("executionAuthorized") == false) {
        TextButton(onClick = { controller.focusTask(id) }) { Text(label) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InferenceRecordSheet(task: UiTask, controller: PocketWorldController, returnLabel: String, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = close) { Text(returnLabel) }
            Text(task.goal, style = MaterialTheme.typography.titleLarge)
            Text(if (task.status == "recording") "正在记录本次模型调用" else "模型调用记录", style = MaterialTheme.typography.labelLarge)
            Text("这里只查看实际模型回执，没有工具执行或继续任务的权限。未收到的用量保持未知。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.summary.isNotBlank()) Text(task.summary, style = MaterialTheme.typography.bodyMedium)
            if (task.raw.optJSONObject("historyEventPage")?.optBoolean("hasMore") == true) Text("以下用量来自当前记录页；较早调用可在执行记录中翻页核对。", style = MaterialTheme.typography.bodySmall)
            TaskInferencePanel(task.raw, controller::reestimateInference, controller::openChatGptUsage)
            TaskAuditPanel(task.id, controller)
            Spacer(Modifier.height(24.dp))
        }
    }
}
