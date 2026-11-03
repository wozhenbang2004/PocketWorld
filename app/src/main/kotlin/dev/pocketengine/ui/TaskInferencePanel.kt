package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketengine.runtime.usage.TaskInferencePresentation
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

/** The original task sheet's optional detail, not another dashboard or accounting store. */
@Composable
internal fun TaskInferencePanel(task: JSONObject, reestimate: ((String, String, (JSONObject?, String?) -> Unit) -> Unit)? = null, openChatGptUsage: () -> Unit,
    readUsage: ((JSONObject, (TaskInferencePresentation.Snapshot?, String?) -> Unit) -> (() -> Unit))? = null) {
    val id = task.optString("id")
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    var groupsShown by rememberSaveable(id) { mutableIntStateOf(4) }
    var callsShown by rememberSaveable(id) { mutableIntStateOf(0) }
    var estimateCall by remember(id) { mutableStateOf<String?>(null) }
    var estimateResult by remember(id) { mutableStateOf<JSONObject?>(null) }
    var estimateError by remember(id) { mutableStateOf<String?>(null) }
    var estimating by remember(id) { mutableStateOf(false) }
    var usage by remember(id) { mutableStateOf<TaskInferencePresentation.Snapshot?>(null) }
    var usageError by remember(id) { mutableStateOf<String?>(null) }
    var usageLoading by remember(id) { mutableStateOf(false) }
    var usageRefresh by remember(id) { mutableIntStateOf(0) }
    val alive = remember(id) { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(id) { onDispose { alive.set(false) } }
    DisposableEffect(id, expanded, usageRefresh) {
        if (!expanded || readUsage == null) onDispose { }
        else {
            usageLoading = true; usageError = null
            val cancel = readUsage(task) { result, error ->
                usageLoading = false
                if (result != null) usage = result
                else usageError = error ?: "累计回执无法核对；没有把当前窗口当作完整累计。"
            }
            onDispose { cancel() }
        }
    }
    val snapshot = remember(task, usage) { runCatching { usage ?: TaskInferencePresentation.fromTask(task) }.getOrNull() }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("task_inference_panel")) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("task_inference_toggle")) {
            Text(if (expanded) "收起模型调用与上下文" else "模型调用与上下文 · 查看记录")
        }
        if (snapshot == null) {
            if (expanded) Text("调用记录暂不可核对；没有把未知用量显示成 0。请重新打开原任务核对。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(snapshot.headline(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (snapshot.context().attention()) Text(snapshot.context().title(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (expanded) Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                if (usageLoading) Text("正在核对原任务各次执行的保留回执…", style = MaterialTheme.typography.bodySmall)
                usageError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (readUsage != null) TextButton(onClick = { usageRefresh++ }, enabled = !usageLoading, modifier = Modifier.testTag("task_inference_refresh")) { Text("刷新原任务累计") }
                Text(snapshot.coverage(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val logical = task.optJSONArray("logicalRequests")
                if (logical != null && logical.length() > 0) {
                    Text("逻辑请求与路由", style = MaterialTheme.typography.titleSmall)
                    Text("显示最多8条最近请求，仅为展示窗口；原记录未删除，费用仍以物理回执为准。", style = MaterialTheme.typography.bodySmall)
                    for (index in 0 until minOf(logical.length(), 8)) {
                        val request = logical.getJSONObject(index)
                        val route = request.optJSONObject("routeSnapshot") ?: JSONObject()
                        val control = request.optJSONObject("control") ?: JSONObject()
                        Text("${route.optString("requestedAlias")} · ${request.optString("state")} · ${control.optJSONArray("attempts")?.length() ?: 0}/${control.optInt("maxAttempts", 1)} 次", style = MaterialTheme.typography.bodyMedium)
                        SelectionContainer { Text(request.optString("logicalRequestId"), style = MaterialTheme.typography.labelSmall) }
                        Text("完成：${control.optString("completionOutcome", "unknown")} · 输出：${control.optString("streamCompleteness", "none")} · 费用按各次回执核对", style = MaterialTheme.typography.bodySmall)
                        val candidates = route.optJSONArray("orderedCandidates")
                        for (c in 0 until (candidates?.length() ?: 0)) {
                            val candidate = candidates!!.getJSONObject(c)
                            if (candidate.optString("exclusion").isNotBlank()) Text("未选 ${candidate.optString("candidateId")}：${candidate.optString("exclusion")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("费用估算与额度", style = MaterialTheme.typography.titleSmall)
                snapshot.money().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("套餐额度未观察；服务冷却/Retry-After不是套餐重置时间。", style = MaterialTheme.typography.bodySmall)
                snapshot.warnings().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                val context = snapshot.context()
                Text(context.title(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                if (context.at() > 0) Text(inferenceTime(context.at()), style = MaterialTheme.typography.labelSmall)
                context.lines().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                snapshot.groups().take(groupsShown).forEach { group ->
                    HorizontalDivider()
                    Text(group.title(), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(group.route(), style = MaterialTheme.typography.bodySmall) }
                    group.counters().forEach { Text("${it.name()}：${it.text()}", style = MaterialTheme.typography.bodySmall) }
                    Text(group.semantics(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (snapshot.groups().size > groupsShown) TextButton(onClick = { groupsShown += 4 }) { Text("再看 4 组原调用 · 共 ${snapshot.groups().size} 组") }
                if (snapshot.hasAccount()) TextButton(onClick = openChatGptUsage, modifier = Modifier.testTag("task_inference_official_usage")) { Text("在 ChatGPT Manage usage 核对套餐用量") }
                if (snapshot.calls().isNotEmpty()) {
                    TextButton(onClick = { callsShown = if (callsShown == 0) 10 else 0 }, modifier = Modifier.testTag("task_inference_calls")) { Text(if (callsShown == 0) "逐次核对调用、终态与来源" else "收起逐次调用") }
                    snapshot.calls().take(callsShown).forEach { call ->
                        HorizontalDivider()
                        Text("${call.purpose()} · ${call.status()}", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text("call ${call.id()}\n${call.binding()}\n${call.route()}", style = MaterialTheme.typography.bodySmall) }
                        if (call.at() > 0) Text(inferenceTime(call.at()), style = MaterialTheme.typography.labelSmall)
                        if (reestimate != null && call.apiKey()) TextButton(enabled = !estimating, onClick = {
                            estimateCall = call.id(); estimateResult = null; estimateError = null; estimating = true
                            reestimate(id, call.id()) { result, error -> if (alive.get()) { estimateResult = result; estimateError = error; estimating = false } }
                        }) { Text("按当前价目另行试算（不改历史）") }
                        call.failure().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        call.pricing().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        call.counters().forEach { Text("${it.name()}：${it.text()}", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (callsShown > 0 && snapshot.calls().size > callsShown) TextButton(onClick = { callsShown += 10 }) { Text("再看 10 次原调用 · 共 ${snapshot.calls().size} 次") }
                }
            }
        }
    }
    if (estimateCall != null) AlertDialog(onDismissRequest = { if (!estimating) estimateCall = null }, title = { Text("另行试算 · 不改变历史") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("原call $estimateCall；没有重新请求模型、发送工具或覆盖旧价目。", style = MaterialTheme.typography.bodySmall)
            if (estimating) LinearProgressIndicator(Modifier.fillMaxWidth())
            estimateError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            estimateResult?.let { result ->
                val value = result.optJSONObject("estimate"); val price = result.optJSONObject("currentPrice")
                if (!result.optBoolean("known") || value == null || price == null) Text("当前无法试算：${result.optString("reason", "未观察到适用价目")}；未知不是0。")
                else {
                    Text("当前价目 ${price.optString("priceVersion")} · ${price.optString("currency")}", style = MaterialTheme.typography.bodySmall)
                    Text("原价目：${result.optString("originalPriceVersion", "未报告")}", style = MaterialTheme.typography.bodySmall)
                    Text(if (value.isNull("estimatedTokenCost")) "token整额仍未知；已知分项小计：${if (value.isNull("knownTokenSubtotal")) "未知" else value.optString("knownTokenSubtotal")}" else "token估算：${value.optString("estimatedTokenCost")}")
                    Text("不含请求、媒体、工具附加费、税费或折扣；不是账单、套餐剩余或金额硬限。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }, confirmButton = { TextButton(enabled = !estimating, onClick = { estimateCall = null }) { Text("保留原历史并关闭") } })
}

private fun inferenceTime(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
