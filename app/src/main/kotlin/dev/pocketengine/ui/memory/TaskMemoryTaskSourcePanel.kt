package dev.pocketengine.ui.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Search/browse task records through the existing native TaskStore reader, not a second task page or memory book. */
@Composable
fun TaskMemoryTaskSourcePanel(
    query: String,
    records: List<JSONObject>,
    page: JSONObject?,
    busy: Boolean,
    error: String?,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
    onOpenTask: (String) -> Unit,
    onBack: () -> Unit,
    onReadTrace: ((JSONObject) -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("查找原任务记录", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 21.sp) }
        Text("这里检索的是任务记录，不是正式记忆，也不是本次提供给模型的来源。打开记录不会自动保存或授权执行。", fontSize = 12.sp, lineHeight = 20.sp)
        OutlinedTextField(query, { onQueryChanged(it.take(500)) }, label = { Text("任务目标、结果或关键词") }, minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth())
        Button(onClick = onSearch, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在读取真实任务" else "搜索任务记录") }
        if (page != null) {
            Text(if (page.optString("coverage") == "full_task_store") "检索全部任务的目标、摘要和确认反馈；消息或工具结果请进入执行记录内查找。结果按最近更新分页展示。" else "当前入口只查最近200条及精确任务编号的目标、摘要和确认反馈；更早记录可能未覆盖。", fontSize = 12.sp, lineHeight = 20.sp)
            if (records.isEmpty()) Text("没有找到匹配的任务记录。可换关键词或输入精确编号。", fontSize = 14.sp, lineHeight = 22.sp)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(records, key = { it.getString("taskId") }) { record -> Column {
                HorizontalDivider()
                Text(taskState(record.optString("status")), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                MemoryQuotedText(record.optString("goal"), record.getString("taskId") + ":goal")
                if (record.optString("summary").isNotBlank()) MemoryQuotedText(record.getString("summary"), record.getString("taskId") + ":summary", 200)
                Text("${if (record.optString("spaceId") == "private") "私人空间" else "空间 ${record.optString("spaceId")}"} · ${evidenceState(record.optString("evidenceState"))}", fontSize = 12.sp, lineHeight = 20.sp)
                if (record.optBoolean("snapshotAvailable")) Text("保存了主人确认时的来源快照；确认本身不等于独立执行核验。", fontSize = 12.sp, lineHeight = 20.sp)
                TextButton(onClick = { onOpenTask(record.getString("taskId")) }, enabled = !busy) { Text("打开这条原任务") }
                onReadTrace?.let { read -> TextButton(onClick = { read(JSONObject().put("taskId",record.getString("taskId"))) }, enabled = !busy) { Text("查阅消息、工具回执与父链") } }
            } }
            }
            if (page.optString("nextCursor").isNotBlank()) OutlinedButton(onClick = onMore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("加载更多任务记录") }
            else if (page.optBoolean("resultTruncated") || page.optBoolean("mayHaveOlderTasks")) Text("当前结果还有范围限制；请缩小关键词或输入精确任务编号。", fontSize = 12.sp, lineHeight = 20.sp)
        }
        TextButton(onClick = onBack) { Text("返回本次来源") }
    }
}

private fun taskState(status: String): String = when (status) {
    "completed" -> "任务标记为已完成"; "failed" -> "任务失败，仍可核对记录"; "needs_confirmation" -> "任务结果待你确认"
    "cancelled" -> "任务已取消"; "running" -> "任务进行中"; "paused" -> "任务已暂停"; "awaiting_user" -> "等待你处理"; else -> "任务状态：$status"
}
private fun evidenceState(state: String): String = when (state) {
    "owner_confirmed_result" -> "主人曾确认结果"; "verification_revoked" -> "原确认已撤销"; "unverified_result" -> "结果未经主人确认"; else -> "没有已确认的结果来源"
}
