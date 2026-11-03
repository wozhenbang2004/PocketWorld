package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Owner-only original-operation audit; viewing it never starts or repeats the Skill. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LearnedMethodApplicationsSheet(record: JSONObject, state: PocketUiState,
    controller: PocketWorldController, close: () -> Unit) {
    val id = record.getString("id")
    val revision = record.getLong("revision")
    var request by remember(id, revision) { mutableStateOf<String?>(null) }
    var rows by remember(id, revision) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var cursor by remember(id, revision) { mutableStateOf("") }
    var error by remember(id, revision) { mutableStateOf<String?>(null) }
    var pageLimited by remember(id, revision) { mutableStateOf(false) }
    var effectComparison by remember(id, revision) { mutableStateOf<JSONObject?>(null) }
    var showEffectComparison by remember(id, revision) { mutableStateOf(false) }
    fun load(next: String) {
        if (request != null) return
        error = null
        effectComparison = null
        request = controller.requestOwnerDomain("taskMemory", "readLearnedMethodApplications",
            JSONObject().put("recordId", id).put("revision", revision).put("cursor", next).put("limit", 20))
    }
    // The parent NativeMemory page owns the domain; sibling scope reads remain valid on close.
    LaunchedEffect(id, revision) { load("") }
    val response = state.ownerResponses("taskMemory")["readLearnedMethodApplications"]
    LaunchedEffect(request, response?.toString()) {
        val waiting = request ?: return@LaunchedEffect
        if (response?.optString("requestId") != waiting) return@LaunchedEffect
        request = null
        if (response.opt("ok") != true) { error = response.optString("error", "原方法回执暂不可读"); return@LaunchedEffect }
        try {
            val result = response.getJSONObject("result")
            require(result.getString("recordId") == id && result.getLong("recordRevision") == revision
                && result.opt("executionAuthorized") == false)
            val comparison = result.optJSONObject("effectComparison")
            if (comparison != null) require(comparison.optString("format") == "pocketworld.method-effect-comparison.v1"
                && comparison.opt("executionAuthorized") == false && comparison.opt("causalEffectVerified") == false)
            val found = result.getJSONArray("applications")
            rows = rows + (0 until found.length()).map { found.getJSONObject(it) }.filter { next ->
                rows.none { it.optString("operationId") == next.optString("operationId") }
            }
            cursor = result.getString("nextCursor")
            pageLimited = result.optInt("readLimitedOperations") > 0 || !result.optBoolean("completeHistory")
            effectComparison = comparison
        } catch (_: Exception) { error = "原方法版本或回执格式不可核对，未显示替代记录" }
    }
    ModalBottomSheet(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.9f), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("方法的实际应用回执", style = MaterialTheme.typography.titleLarge)
                Text("展示这个准确方法版本的原任务明确采用/未采用决定、关联步骤回执及派生脚本记录。采用决定与真实步骤关联不证明方法语义有效或导致业务成功；已提供、已加载或后端接受不计采用。",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                effectComparison?.let { comparison ->
                    TextButton(onClick = { showEffectComparison = !showEffectComparison }) {
                        Text(if (showEffectComparison) "收起本次读取的效果核对" else "查看本次读取的效果核对")
                    }
                    if (showEffectComparison) {
                        Text("本次 ${comparison.optInt("scannedApplications")} 份原回执中，${comparison.optInt("eligibleApplications")} 次独立尝试可核对输入与已观察环境。",
                            style = MaterialTheme.typography.bodySmall)
                        val groups = comparison.getJSONArray("groups")
                        for (groupIndex in 0 until groups.length()) {
                            val methods = groups.getJSONObject(groupIndex).getJSONArray("methods")
                            Text("输入与已观察环境相同的第 ${groupIndex + 1} 组", style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 8.dp))
                            for (methodIndex in 0 until methods.length()) {
                                val method = methods.getJSONObject(methodIndex)
                                Text("执行实现 ${methodIndex + 1}：${method.getInt("attempts")} 次尝试；主人确认结果 ${method.getInt("ownerVerifiedResults")} 次，判为方法问题 ${method.getInt("ownerAttributedMethodFailures")} 次、环境问题 ${method.getInt("ownerAttributedEnvironmentFailures")} 次。",
                                    style = MaterialTheme.typography.bodySmall)
                                Text("未核对 ${method.getInt("unreviewed")} 次，取消 ${method.getInt("cancelled")} 次，模型错误 ${method.getInt("modelErrors")} 次，旧反馈缺原结果指纹 ${method.optInt("unboundFeedback")} 次，反馈不可核对 ${method.getInt("invalidFeedback")} 次。",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("证据不完整 ${comparison.optInt("excludedIncompleteEvidence")} 份，未实际采用 ${comparison.optInt("excludedUnapplied")} 份；重复或冲突记录不增加独立尝试数。",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        Text(if (comparison.optBoolean("completeHistory")) "已覆盖当前可读取的原回执；这些关联不证明因果收益，不会自动提高可信度或授予权限。"
                            else "统计仅限本次读取，不能作为全部历史的成功率；这些关联不证明因果收益，不会自动提高可信度或授予权限。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(rows, key = { it.getString("operationId") }) { row ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when (row.optString("applicationState")) {
                        "model_apply_with_native_steps" -> "模型明确选择采用，已关联同轮原步骤确定回执"
                        "not_applicable" -> "本任务明确判为不适用，未计采用"
                        "not_selected" -> "本任务明确未选这个方法，未计采用"
                        "applied_backend_attempt" -> "后端实际尝试已取得终态回执"
                        "backend_running" -> "后端执行中，尚未完成"
                        "backend_accepted" -> "后端已接受，尚不能证明方法已执行"
                        "backend_cancelled" -> "后端已取消，是否应用不作推定"
                        "application_unknown" -> "原结果未知，不会自动重放"
                        "dispatch_claimed" -> "原派发已领取，等待真实后端回执"
                        else -> "未派发，不记为已应用"
                    }, style = MaterialTheme.typography.titleSmall)
                    Text("原结果：${row.optString("backendOutcome")} · 本记录不验证方法的因果收益", style = MaterialTheme.typography.bodySmall)
                    if (row.has("decision")) Text("原决定理由：${row.optString("reason")}", style = MaterialTheme.typography.bodyMedium)
                    row.optJSONArray("stepRefs")?.let { steps ->
                        Text("关联 ${steps.length()} 个同 task/run 原步骤；准确工具与返回可在原任务中核对。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (row.has("exitCode") && !row.isNull("exitCode")) Text("退出码 ${row.getInt("exitCode")}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { close(); controller.focusTask(row.getString("taskId")) }) { Text("查看原任务与准确回执") }
                    HorizontalDivider()
                }
            }
            item {
                if (rows.isEmpty() && request == null && error == null) Text("当前窗口没有找到这个版本的真实方法执行回执。", style = MaterialTheme.typography.bodyMedium)
                if (pageLimited) Text("这里是有限原操作窗口；未覆盖、过大或缺少原生证明的记录不当作不存在或已应用。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (request != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (cursor.isNotEmpty()) TextButton(onClick = { load(cursor) }, enabled = request == null) { Text("继续读取较早回执") }
                TextButton(onClick = { rows = emptyList(); cursor = ""; load("") }, enabled = request == null) { Text("重新核对原版本") }
            }
        }
    }
}
