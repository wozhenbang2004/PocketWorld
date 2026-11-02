package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** A bounded durable view of the original accounting headers, not another task history or executor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeInferenceHistory(controller: PocketWorldController) {
    if (!controller.nativeInferenceHistoryOpen) return
    var rows by remember(controller) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var cursor by remember { mutableStateOf("") }; var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var exhausted by remember { mutableStateOf(false) }; var version by remember { mutableIntStateOf(0) }
    fun read(reset: Boolean) {
        if (loading) return
        loading = true; error = null; val attempt = ++version
        controller.readNativeInferenceHistory(if (reset) "" else cursor) { result, failure ->
            if (attempt != version) return@readNativeInferenceHistory
            loading = false
            if (result == null) error = failure ?: "调用记录暂不可读"
            else runCatching {
                require(result.opt("executionAuthorized") == false && result.opt("accountingOnly") == true)
                val page = result.getJSONArray("tasks").objects(); require(page.size <= 40)
                val combined = if (reset) page else (rows + page).distinctBy { it.getString("id") }
                if (combined.size > 160) exhausted = true
                else { rows = combined; cursor = result.optString("nextCursor"); more = result.getBoolean("hasMore"); exhausted = false }
            }.onFailure { error = "原调用记录窗口不可核对，未用假数据替代" }
        }
    }
    LaunchedEffect(controller) { read(true) }
    ModalBottomSheet(onDismissRequest = controller::closeNativeInferenceHistory, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) { TextButton(onClick = controller::closeNativeInferenceHistory) { Text("返回") }; Spacer(Modifier.weight(1f)); TextButton(onClick = { read(true) }, enabled = !loading) { Text("刷新") } }
            Text("独立模型调用记录", style = MaterialTheme.typography.titleLarge)
            InferenceTrendsPanel(controller::readInferenceTrends)
            Text("连接测试、语音、本机服务与经验草稿的实际记录。有任务的调用仍归原任务；本页不产生执行权限。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (rows.isEmpty() && !loading) Text(if (more) "当前窗口没有独立调用，可查看较早记录。" else "当前没有保留的独立调用记录；不代表服务用量为0。", style = MaterialTheme.typography.bodySmall)
            if (exhausted) Text("已到本次160条显示上限，原记录仍保留；刷新可回到最近记录。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rows, key = { it.getString("id") }) { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.optString("goal", "模型调用"), style = MaterialTheme.typography.titleSmall)
                        Text(if (row.optString("status") == "recording") "正在记录 · 不执行工具" else taskStatus(row.optString("status")), style = MaterialTheme.typography.labelMedium)
                        Text(row.optString("summary"), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { controller.closeNativeInferenceHistory(); controller.focusTask(row.getString("id")) }) { Text("查看实际调用与未知项") }
                    }
                }
                if (more && !exhausted) item { TextButton(onClick = { read(false) }, enabled = !loading) { Text("查看较早窗口") } }
            }
            Text("每页只读原任务摘要，可能包含不在本页展示的任务；不是全部账单或余额。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
