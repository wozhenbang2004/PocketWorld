package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/** Progressive owner-only views using the existing native sheet and monochrome theme. */
@Composable
internal fun ModelDiagnosticsPanel(controller: PocketWorldController, modifier: Modifier = Modifier) {
    val ui = controller.modelDiagnostics
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var rangeDays by remember { mutableIntStateOf(7) }
    var zone by remember { mutableStateOf(ZoneId.systemDefault().id) }
    var custom by remember { mutableStateOf(false) }
    var fromText by remember { mutableStateOf(LocalDate.now().minusDays(6).toString()) }
    var untilText by remember { mutableStateOf(LocalDate.now().toString()) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    fun query() {
        runCatching {
            val selected = ZoneId.of(zone)
            val until = if (custom) LocalDate.parse(untilText) else LocalDate.now(selected)
            val from = if (custom) LocalDate.parse(fromText) else until.minusDays(rangeDays - 1L)
            ui.query(from.atStartOfDay(selected).toInstant().toEpochMilli(), until.plusDays(1).atStartOfDay(selected).toInstant().toEpochMilli(), selected.id)
        }.onSuccess { inputError = null }.onFailure { inputError = "请填写有效日期和时区，结束日期需在开始日期之后，范围最多90天。" }
    }
    DisposableEffect(ui, lifecycle) {
        ui.open()
        fun current() { ui.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val observer = LifecycleEventObserver { _, event -> current(); if (event == Lifecycle.Event.ON_RESUME) ui.refresh() }
        lifecycle.addObserver(observer); current(); ui.refresh()
        onDispose { lifecycle.removeObserver(observer); ui.close() }
    }
    LaunchedEffect(ui.configuration?.optLong("generation")) { if (ui.configuration != null && ui.report == null && !ui.busy) query() }
    val config = ui.configuration
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("本机用量趋势", style = MaterialTheme.typography.titleMedium)
            Text("来自原任务的物理调用回执。实际账单与套餐余量保持未知；缓存、推理和总量不重复相加。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (days in listOf(7, 30)) FilterChip(selected = !custom && rangeDays == days, onClick = { custom = false; rangeDays = days; query() }, label = { Text("${days}天") }, enabled = !ui.busy)
                FilterChip(selected = custom, onClick = { custom = !custom }, label = { Text("自选范围") }, enabled = !ui.busy)
            }
            if (custom) {
                OutlinedTextField(value = fromText, onValueChange = { fromText = it }, label = { Text("开始日期 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = untilText, onValueChange = { untilText = it }, label = { Text("结束日期（含当天）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { zone = if (zone == "UTC") ZoneId.systemDefault().id else "UTC"; query() }, enabled = !ui.busy) { Text("时区 $zone") }
                TextButton(onClick = ::query, enabled = !ui.busy) { Text("刷新范围") }
                if (ui.busy) TextButton(onClick = ui::cancel) { Text("停止等待") }
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            inputError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            ui.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        ui.report?.let { report ->
            item {
                val totals = report.getJSONObject("totals")
                Text("${report.optInt("taskCount")} 个任务 · ${totals.optInt("attempts")} 次物理调用", style = MaterialTheme.typography.titleSmall)
                UsageCounters(totals)
                MoneyLines(totals.optJSONObject("money"))
                Text("范围：本机主人 / 私人空间；群、渠道及无法确认来源的记录不纳入。检查 ${report.optInt("rowsExamined")} 条，排除 ${report.optInt("scopeExcludedRows")} 条。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (report.optBoolean("queryLimited")) Text("本次查询达到读取上限或包含超限记录，结果只代表已读取部分。请缩小日期范围。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (report.optInt("conflictingCalls") > 0 || report.optInt("invalidReceipts") > 0) Text("${report.optInt("conflictingCalls")} 次冲突、${report.optInt("invalidReceipts")} 条不可核对回执未计入。", style = MaterialTheme.typography.bodySmall)
                if (totals.optInt("attempts") == 0) Text("没有这一范围的已保留回执；这不表示实际用量为0。", style = MaterialTheme.typography.bodySmall)
            }
            val days = report.optJSONArray("days").objects()
            items(days, key = { it.getJSONObject("identity").toString() }) { row ->
                val identity = row.getJSONObject("identity")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(identity.optString("day"), style = MaterialTheme.typography.labelMedium)
                    Text("${identity.optString("provider")} · ${identity.optString("model")}", style = MaterialTheme.typography.titleSmall)
                    UsageCounters(row)
                    MoneyLines(row.optJSONObject("money"))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item {
                TextButton(onClick = { settings = !settings }) { Text(if (settings) "收起诊断设置" else "详细诊断与导出设置") }
                if (settings && config != null) DiagnosticsSettings(config, ui)
            }
            if (config?.optBoolean("enabled") == true) {
                item { Text("原任务来源", style = MaterialTheme.typography.titleSmall); Text("选择原任务生成详细脱敏快照；只保留调用、工具状态与关联标识。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val tasks = report.optJSONArray("callReferences").objects().distinctBy { it.optString("taskId") }
                items(tasks, key = { "task:" + it.optString("taskId") }) { ref ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { controller.closeNativeInferenceHistory(); controller.focusTask(ref.getString("taskId")) }) { Text("任务 ${ref.optString("taskId").take(8)}") }
                        TextButton(onClick = { ui.capture(ref.getString("taskId")) }, enabled = !ui.busy) { Text("生成诊断") }
                    }
                }
                if (report.optBoolean("referencesLimited")) item { Text("最多显示128条原调用定位；缩小范围可查看其余任务。", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (ui.report == null) item {
            TextButton(onClick = { settings = !settings }) { Text(if (settings) "收起诊断设置" else "诊断设置") }
            if (settings && config != null) DiagnosticsSettings(config, ui)
        }
        val traces = config?.optJSONArray("snapshots").objects()
        if (traces.isNotEmpty()) item { Text("已保留诊断", style = MaterialTheme.typography.titleSmall) }
        items(traces, key = { it.getString("snapshotId") }) { trace ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("任务 ${trace.optString("taskId").take(8)} · ${trace.optInt("spanCount")} 个跨度", style = MaterialTheme.typography.titleSmall)
                Text("${trace.optInt("unknownOutcomes")} 个未知结果 · ${trace.optInt("skippedSpans")} 个未保留 · trace ${trace.optString("traceId").take(12)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (trace.optBoolean("sourceLimited")) Text("原来源读取不完整，此快照只代表已读取部分。", style = MaterialTheme.typography.bodySmall)
                trace.optJSONObject("lastExport")?.let { Text(exportOutcome(it), style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { ui.prepare(trace.getString("snapshotId")) }, enabled = !ui.busy && config?.optBoolean("enabled") == true && config.optBoolean("exportEnabled") && trace.optInt("spanCount") > 0) { Text("预览这份导出") }
            }
        }
        ui.exportResult?.let { result -> item { Text(exportOutcome(result), style = MaterialTheme.typography.bodySmall) } }
    }
    val review = ui.review
    if (review != null) {
        val shown = remember(review) { review.view() }
        AlertDialog(onDismissRequest = ui::cancelReview, title = { Text("发送这份脱敏诊断？") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(shown.getString("endpoint"), style = MaterialTheme.typography.bodyMedium)
                Text("${shown.getInt("spanCount")} 个跨度，${shown.getInt("payloadBytes")} 字节。不含提示词、回答、文件、截图、凭据、私人记忆正文。任务、调用和工具标识使用摘要。失败或部分拒绝均不自动重发。", style = MaterialTheme.typography.bodySmall)
                Text(shown.getJSONObject("payloadPreview").toString(2), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }, confirmButton = { TextButton(onClick = ui::send, enabled = !ui.busy) { Text("发送这份诊断") } }, dismissButton = { TextButton(onClick = ui::cancelReview) { Text("取消") } })
    }
}

@Composable
private fun DiagnosticsSettings(config: JSONObject, ui: ModelDiagnosticsUi) {
    val revision = config.getLong("generation")
    var enabled by remember(revision) { mutableStateOf(config.getBoolean("enabled")) }
    var export by remember(revision) { mutableStateOf(config.getBoolean("exportEnabled")) }
    var endpoint by remember(revision) { mutableStateOf(config.optString("endpoint")) }
    var cap by remember(revision) { mutableIntStateOf(config.getInt("storageCapBytes")) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("详细脱敏诊断", style = MaterialTheme.typography.bodyMedium); Switch(checked = enabled, onCheckedChange = { enabled = it }) }
        Text("默认关闭。开启后可为所选原任务生成快照，不额外记录正文，也不会自动上传。关闭或修改配置会使旧请求失效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (size in listOf(131072, 524288, 2097152)) FilterChip(selected = cap == size, onClick = { cap = size }, label = { Text(if (size < 1048576) "${size / 1024}KiB" else "2MiB") })
        }
        Text("已保留 ${config.optInt("storedBytes")} 字节；达到上限会清理最旧诊断，原任务回执保持原样。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("允许手动OTLP导出", style = MaterialTheme.typography.bodyMedium); Switch(checked = export, onCheckedChange = { export = it }) }
        if (export || endpoint.isNotEmpty()) OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("HTTPS /v1/traces 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(onClick = { ui.configure(enabled, cap, export, endpoint) }, enabled = !ui.busy) { Text("保存设置") }
            TextButton(onClick = ui::clear, enabled = !ui.busy) { Text("清除本地诊断") }
        }
    }
}

@Composable
private fun UsageCounters(row: JSONObject) {
    val counts = row.getJSONObject("counters")
    Text("${row.optInt("attempts")} 次 · 输入 ${countText(counts.getJSONObject("INPUT"))} · 输出 ${countText(counts.getJSONObject("OUTPUT"))}", style = MaterialTheme.typography.bodySmall)
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起明细" else "缓存、推理与未知项", style = MaterialTheme.typography.labelMedium) }
    if (expanded) for ((key, label) in listOf("TOTAL" to "服务总量", "CACHE_READ" to "缓存读取", "CACHE_WRITE" to "缓存写入", "REASONING" to "推理明细")) Text("$label：${countText(counts.getJSONObject(key))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun countText(value: JSONObject): String {
    val subtotal = if (value.isNull("knownSubtotal")) "未知" else value.get("knownSubtotal").toString()
    return subtotal + (if (value.optInt("unknownCalls") > 0) "（${value.optInt("unknownCalls")}次未知）" else "") + (if (value.optInt("partialCalls") > 0) " · ${value.optInt("partialCalls")}次部分返回" else "")
}

@Composable
private fun MoneyLines(money: JSONObject?) {
    if (money == null) return
    val currencies = money.optJSONArray("currencies").objects()
    if (currencies.isEmpty()) Text("估价未知，实际账单未知", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (currency in currencies) Text("${currency.optString("currency")} ${currency.optString("knownTokenSubtotal")} · 已知token分项估算（${currency.optInt("partlyPricedCalls")}次不完整）", style = MaterialTheme.typography.bodySmall)
    if (money.optInt("unknownCalls") > 0) Text("${money.optInt("unknownCalls")}次金额未知，未按0相加；币种分开，未含请求费等费用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun exportOutcome(value: JSONObject): String = when (value.optString("outcome")) {
    "accepted" -> "收集器确认接收 ${value.optInt("acceptedSpans")} 个跨度。"
    "accepted_with_warning" -> "收集器已接收，并返回提示；原响应正文未保存。"
    "partial" -> "部分接收：${value.optInt("rejectedSpans")} 个跨度被拒绝；未重发。"
    "rejected" -> "收集器拒绝（HTTP ${value.optInt("httpStatus")}）；未重发。"
    "unknown_after_cancel" -> "本机已停止等待；收集器是否接收仍未知，未重发。"
    else -> "接收结果未知；请核对原收集器，未自动重发。"
}
