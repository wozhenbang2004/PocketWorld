package dev.pocketengine.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import org.json.JSONObject

/** Preparation uses the same owner admission and approvals as every other selected project operation. */
@Composable
internal fun NativeProjectToolPreparation(runtime: JSONObject?, task: UiTask?, enabled: Boolean, start: () -> Unit, refresh: () -> Unit) {
    val executable = runtime?.opt("canExecute") == true
    val receipt = ProjectWorkbenchGuard.preparationReceipt(task)
    val progress = ProjectPreparationProgress.from(runtime?.optJSONObject("preparation")) ?: ProjectPreparationProgress.fromReceipt(receipt)
    if (executable) return
    var details by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val available = runtime?.opt("preparationAvailable") == true
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        color = Soft,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (progress?.error != null) "开发工具需要处理" else "开发工具尚未就绪",
                        fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(if (available) "准备完成后即可构建和运行" else "仍可查看和编辑项目文件",
                        color = SecondaryInk, fontSize = 13.sp, lineHeight = 18.sp)
                }
                TextButton(onClick = { details = !details }) { Text(if (details) "收起" else "详情") }
            }
            progress?.let { current ->
                if (current.error == null) {
                    val fraction = current.fraction
                    if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("project_preparation_progress"))
                    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().testTag("project_preparation_progress"))
                }
                current.error?.let { Text(it, color = Danger, fontSize = 13.sp, lineHeight = 20.sp) }
            }
            if (available) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = start, enabled = enabled && runtime?.opt("preparationAvailable") == true,
                    modifier = Modifier.weight(1f).testTag("project_prepare_phone_tools")) {
                    Text(if (progress?.resumableDownload == true) "继续准备" else "准备工具")
                }
                TextButton(onClick = refresh) { Text("刷新") }
            }
            if (details) {
                Text("工具准备会进入当前项目任务，执行前仍需确认方案。下载完成不代表安装和核验完成。",
                    color = SecondaryInk, fontSize = 14.sp, lineHeight = 20.sp)
                progress?.let { current ->
                    Text("当前阶段：${current.phase}", fontSize = 13.sp,
                        modifier = Modifier.testTag("project_preparation_phase"))
                    preparationCount(current)?.let { Text(it, color = SecondaryInk, fontSize = 13.sp) }
                    if (current.resumableDownload) Text("已下载的内容可以续传；继续前仍会核对原任务。",
                        color = SecondaryInk, fontSize = 13.sp, lineHeight = 20.sp)
                }
                if (!available) {
                    Text("当前运行环境没有可用的工具准备入口。", color = SecondaryInk, fontSize = 14.sp)
                    TextButton(onClick = refresh) { Text("重新检查") }
                } else if (!enabled) Text("请先处理原项目任务中的操作或审批。", color = SecondaryInk, fontSize = 14.sp)
            }
        }
    }
}

/** Real native receipts stay next to their task. Reading this view never starts another process. */
@Composable
internal fun NativeProjectExecutionLog(task: UiTask, onRefresh: () -> Unit, onFullHistory: (() -> Unit)? = null, packageBusy: Boolean = false, onPackageOpen: ((String, String) -> Unit)? = null, onRepair: (String) -> Unit) {
    var expanded by remember(task.id) { mutableStateOf(true) }
    var all by remember(task.id) { mutableStateOf(false) }
    val operations = task.raw.optJSONArray("operations").objects().filter { it.optString("tool") == "project_execute" }
    val inputs = task.raw.optJSONArray("operations").objects().filter { it.optString("tool") == "project_process_input" }
    val registeredLive = task.raw.optJSONObject("developmentLivePreview")
    val console = task.raw.optJSONArray("developmentPreviewLog").objects().filter { entry ->
        val fixed = task.raw.optJSONArray("developmentPreviews").objects().any { it.optString("previewId") == entry.optString("previewId") && it.optString("sourceDigest") == entry.optString("sourceDigest") }
        val live = registeredLive != null && registeredLive.optString("previewId").isNotBlank() && registeredLive.optString("previewId") == entry.optString("previewId") && registeredLive.optString("executionId") == entry.optString("executionId") && registeredLive.optString("sourceDigest") == entry.optString("sourceDigest")
        fixed || live
    }
    if (operations.isEmpty() && inputs.isEmpty() && console.isEmpty()) return
    HorizontalDivider(Modifier.padding(top = 12.dp), color = Line)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) { Text((if (expanded) "收起" else "查看") + "构建、运行与网页日志") }
        TextButton(onClick = onRefresh) { Text("刷新状态") }
    }
    if (!expanded) return
    NativeProjectTerminal(task)
    (if (all) operations else operations.takeLast(4)).asReversed().forEach { operation ->
        key(operation.optString("operationId")) {
            val receipt = ProjectWorkbenchGuard.executionReceipt(task, operation)
            val step = operation.optJSONObject("arguments")?.optString("stepId").orEmpty()
            var detail by remember { mutableStateOf(operation === operations.lastOrNull()) }
            Row(Modifier.fillMaxWidth().clickable { detail = !detail }.heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ProjectWorkbenchGuard.intentLabel(receipt?.optString("intent") ?: step), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(receipt?.let { receiptState(it) } ?: when (operation.optString("state")) { "prepared" -> "等待批准或执行结果"; "unknown" -> "结果待核对 · 未自动重试"; else -> "查看执行结果" }, color = SecondaryInk, fontSize = 13.sp)
                }
                Text(if (detail) "收起" else "展开", fontSize = 13.sp, color = SecondaryInk)
            }
            if (detail) {
                if (receipt == null) Text(operation.optJSONObject("receipt")?.optString("message").orEmpty(), fontSize = 13.sp, lineHeight = 21.sp, color = SecondaryInk)
                receipt?.let { actual ->
                    var technical by remember(operation.optString("operationId")) { mutableStateOf(false) }
                    if (actual.optBoolean("ownedWorkerLive") && !actual.optBoolean("resourcesReleased")) {
                        val hotEdit = actual.optJSONObject("devWatch")?.optBoolean("nativeSessionCreated") == true
                        Text(if (actual.optString("runMode") == "owned_service") {
                            if (hotEdit) "服务仍在运行。可继续查看日志；仅启动时批准的源码可热改。任务结束会停止服务。"
                            else if (actual.optString("terminalMode") == "pty") "交互终端仍在运行。可到 Agent 补充普通输入要求；输入仍受本次启动范围限制。修改文件前先停止，任务结束会停止终端。"
                            else "服务仍在运行，可继续查看和分析。修改文件前请先停止服务。任务结束会停止服务。"
                        } else "进程仍在执行。先停止并核对结果，再修改或重新运行。", color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp)
                    }
                    if (actual.optBoolean("cancelRequested") && !actual.optBoolean("stopConfirmed")) Text("正在请求停止，尚未确认结束；请先刷新状态，不要重复启动。", color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp)
                    if (actual.optString("observation") == "owner_process_restarted_no_replay") Text("App 重开后无法确认进程状态。日志保留，未自动重新运行。", color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp)
                    actual.optJSONArray("files")?.objects()?.takeIf { it.isNotEmpty() }?.let { files ->
                        val applied = files.count { it.optString("state") == "applied" }
                        Text("文件保存：$applied / ${files.size} 已应用", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        if (actual.optBoolean("partialEffects")) Text("部分文件已保存；失败不会自动回滚其他文件。请逐项核对后再继续。", color = Danger, fontSize = 13.sp)
                        files.forEach { file -> Text(when (file.optString("state")) { "applied" -> "已应用"; "failed" -> "未应用"; "skipped" -> "未执行"; else -> "效果待核对" } + " · " + file.optString("path"), fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
                    }
                    if (actual.optString("intent") == "freeze_package") {
                        if (actual.optBoolean("artifactReady") && actual.optBoolean("receiptReceived") && actual.optString("status") == "completed") {
                            Text("固定项目包已保存", style = MaterialTheme.typography.titleSmall)
                            WorkbenchCaption("可重新打开这个原版本；不代表构建成功，也未自动导出或分享。")
                            onPackageOpen?.let { open -> OutlinedButton(onClick = { open(task.id, operation.getString("operationId")) }, enabled = !packageBusy, modifier = Modifier.testTag("project_open_task_package")) { Text("打开这次任务的原成果") } }
                        } else if (actual.optString("status") == "failed" && !actual.optBoolean("nativePublicationEntered")) WorkbenchCaption("尚未保存新成果。请核对错误，读取当前文件后重新生成明确方案。")
                        else WorkbenchCaption("原成果保存结果待核，只查询这个原操作，不会重复保存。")
                    }
                    TextButton(onClick = { technical = !technical }) { Text(if (technical) "收起执行详情" else "执行详情") }
                    if (technical) SelectionContainer { Text("operation ${operation.optString("operationId")}\nrun ${operation.optString("runId")}\nsource SHA-256 ${actual.optString("sourceDigest")}\nstatus ${actual.optString("status")}\nprocessExited ${actual.opt("processExited")} · processGroupAbsent ${actual.opt("processGroupAbsent")} · resourcesReleased ${actual.opt("resourcesReleased")}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    if (actual.optString("intent") == "prepare") ProjectPreparationProgress.fromReceipt(actual)?.let { preparation ->
                        Text("工具准备：${preparation.phase}", fontSize = 13.sp)
                        preparationCount(preparation)?.let { Text(it, color = SecondaryInk, fontSize = 13.sp) }
                        preparation.error?.let { Text(it, color = Danger, fontSize = 13.sp, lineHeight = 21.sp) }
                    }
                    for ((field, label) in listOf("stdout" to "标准输出", "stderr" to "错误输出")) {
                        if (actual.optString(field).isNotEmpty()) {
                            Text(if (field == "stdout" && actual.optBoolean("terminalOutputMerged")) "终端输出（标准输出与错误合流）" else label, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                            WorkbenchOutput(actual.optString(field))
                        }
                    }
                    if (actual.optBoolean("outputTruncated")) Text("日志已达到存储上限，内容不完整。", color = SecondaryInk, fontSize = 13.sp)
                    else if (actual.optBoolean("summaryOutputTruncated")) Text("这里显示日志摘要。可让 Agent 按这次执行分段读取。", color = SecondaryInk, fontSize = 13.sp)
                    ProjectWorkbenchGuard.failureDraft(task, operation.optString("operationId"))?.let { draft ->
                        TextButton(onClick = { onRepair(draft) }, modifier = Modifier.testTag("project_use_build_error")) { Text("将这次错误带入修改草稿") }
                    }
                }
            }
            HorizontalDivider(color = Line)
        }
    }
    if (operations.size > 4) TextButton(onClick = { all = !all }) { Text(if (all) "只看最近四条" else "展开当前窗口 ${operations.size} 条") }
    if (task.raw.optJSONObject("historyOperationPage")?.optBoolean("hasMore") == true && onFullHistory != null) TextButton(onClick = onFullHistory) { Text("到任务记录查看更早执行") }
    if (inputs.isNotEmpty()) {
        var moreInputs by remember(task.id) { mutableStateOf(false) }
        Text("终端输入动作", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
        WorkbenchCaption("这里只记录实际写入或窗口调整；收到输入不等于程序已完成。文字内容不重复显示在回执中。")
        (if (moreInputs) inputs else inputs.takeLast(4)).asReversed().forEach { input ->
            val receipt = input.optJSONObject("receipt")
            val delivery = receipt?.optJSONObject("inputDelivery")
            var details by remember(input.optString("operationId")) { mutableStateOf(false) }
            val action = when (input.optJSONObject("arguments")?.optString("action")) { "resize" -> "调整终端窗口"; "eof" -> "发送行模式结束符"; else -> "发送普通文字" }
            val state = when {
                receipt?.optBoolean("nativeInputSettled") == true && receipt.optString("code") == "project_input_delivered" -> "已取得实际输入回执 · ${delivery?.optInt("writtenBytes") ?: 0} 字节"
                receipt?.optBoolean("nativeInputSettled") == true && receipt.optString("code") == "project_input_not_dispatched" -> "已确认本次未输入"
                input.optString("state") == "prepared" -> "等待输入派发"
                else -> "结果待核对 · 不自动重发"
            }
            Row(Modifier.fillMaxWidth().clickable { details = !details }.heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(action, fontSize = 14.sp); Text(state, color = SecondaryInk, fontSize = 13.sp) }
                Text(if (details) "收起" else "详情", color = SecondaryInk, fontSize = 13.sp)
            }
            if (details) SelectionContainer { Text("operation ${input.optString("operationId")}\nrun ${input.optString("runId")}\nexecution ${receipt?.optString("executionId").orEmpty()}\n${delivery?.toString(2) ?: receipt?.optString("reason").orEmpty()}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) }
            HorizontalDivider(color = Line)
        }
        if (inputs.size > 4) TextButton(onClick = { moreInputs = !moreInputs }) { Text(if (moreInputs) "只看最近四条输入" else "展开当前窗口 ${inputs.size} 条输入") }
    }
    if (console.isNotEmpty()) {
        Text("页面日志", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
        Text("网页控制台与显示错误；热更新日志可能来自较早的页面状态。", color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp)
        WorkbenchOutput(console.takeLast(8).joinToString("\n\n") { "[${it.optString("level")}] ${it.optString("source")}:${it.optInt("line")} · 预览 ${it.optString("previewId").take(8)}\n${it.optString("text")}" })
        if (console.size > 8) Text("显示当前窗口最近 8 条，更多见任务记录。", color = SecondaryInk, fontSize = 13.sp)
    }
}

@Composable private fun WorkbenchOutput(text: String) {
    SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) }
}

private fun receiptState(receipt: JSONObject): String {
    val status = receipt.optString("status")
    val label = when {
        receipt.optBoolean("nativeFileOperation") && status == "completed" -> "固定文件成果已保存"
        receipt.optBoolean("nativeFileOperation") && status == "failed" && !receipt.optBoolean("nativePublicationEntered") -> "尚未发布成果"
        receipt.optBoolean("nativeFileOperation") -> "保存结果待核对"
        status == "failed" && receipt.optBoolean("noChildCreated") && receipt.optBoolean("resourcesReleased") -> "未创建子进程 · 资源已释放"
        receipt.optBoolean("stopConfirmed") -> if (receipt.optBoolean("timedOut")) "超时停止已确认 · 资源已释放" else "停止已确认 · 资源已释放"
        receipt.optBoolean("cancelRequested") -> "停止要求已登记 · 等待实际回执"
        status == "running" && receipt.optString("runMode") == "owned_service" && receipt.optBoolean("nativeStartObserved") -> "服务运行中 · 可继续工作"
        status == "awaiting_resource_release" -> "进程已观测结束 · 等待真实资源释放"
        status == "awaiting_receipt" && receipt.optBoolean("ownedWorkerLive") -> "执行中 · 等待结束"
        status == "completed" -> "进程已结束"
        status == "failed" -> "执行失败"
        status in setOf("unknown", "awaiting_result") -> "结果待核对 · 不重派"
        else -> taskStatus(status)
    }
    val exit = (receipt.opt("exitCode") as? Number)?.toLong()?.takeIf { it >= 0 }
    val signal = (receipt.opt("terminationSignal") as? Number)?.toLong()?.takeIf { it > 0 }
    if (receipt.optBoolean("nativeFileOperation")) return label
    return label + when { exit != null -> " · 退出码 $exit"; signal != null -> " · 终止信号 $signal"; else -> "" }
}

private fun workbenchBytes(bytes: Long): String = if (bytes < 1024 * 1024) "$bytes 字节" else String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))

private fun preparationCount(progress: ProjectPreparationProgress): String? {
    val completed = progress.completedBytes ?: return null
    val total = progress.totalBytes?.takeIf { it > 0 }
    return if (progress.byteProgress) {
        (if (progress.phase == "downloading") "已接收 " else "当前处理量 ") + workbenchBytes(completed) + (total?.let { " / ${workbenchBytes(it)}" } ?: " · 总量待确认")
    } else "当前阶段计数：$completed" + (total?.let { " / $it" } ?: " · 总量待确认")
}
