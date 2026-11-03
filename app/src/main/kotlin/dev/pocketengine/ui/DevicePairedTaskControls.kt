package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pocketengine.features.devices.NativeDeviceWorkspaceHost
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Paired-device controls on the ORIGINAL task sheet. No owner mailbox, input, scope expansion,
 * manual effect resolution, screen takeover or generic resume/new-run entry is created here. */
@Composable
internal fun DevicePairedTaskControls(task: UiTask, host: NativeDeviceWorkspaceHost?,
    runOnWorker: (Runnable) -> Unit, runOnControl: (Runnable) -> Unit, nativeRevision: Long,
    changed: () -> Unit, openModels: () -> Unit) {
    if (task.raw.optJSONObject("origin")?.optString("kind") != "paired_device") return
    if (host == null) { Text("设备控制暂不可用，已有结果仍保留。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk); return }
    val window = remember(task.id, host) { host.openOwnerWindow() }
    val alive = remember(window) { AtomicBoolean(true) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var actions by remember(window) { mutableStateOf<JSONObject?>(null) }
    var error by remember(window) { mutableStateOf<String?>(null) }
    var notice by remember(window) { mutableStateOf<String?>(null) }
    var busy by remember(window) { mutableStateOf(false) }
    var controlBusy by remember(window) { mutableStateOf<Set<String>>(emptySet()) }
    var refresh by remember(window) { mutableIntStateOf(0) }
    var goal by remember(window) { mutableStateOf<NativeDeviceWorkspaceHost.GoalReview?>(null) }
    var result by remember(window) { mutableStateOf<NativeDeviceWorkspaceHost.ResultReview?>(null) }
    var approval by remember(window) { mutableStateOf<NativeDeviceWorkspaceHost.ApprovalReview?>(null) }
    DisposableEffect(window) { onDispose { alive.set(false); window.close() } }
    fun dismissReview() { window.discardReview(); goal = null; approval = null; result = null }
    fun work(action: () -> Unit) {
        if (busy || !alive.get()) return
        busy = true; error = null
        try { runOnWorker(Runnable {
            try { action() } catch (failure: Exception) { main.post { if (alive.get()) error = deviceControlFailure(failure) } }
            finally { main.post { if (alive.get()) busy = false } }
        }) } catch (_: Exception) { busy = false; error = "设备任务入口暂不可用，请重新打开任务。" }
    }
    val readTrigger = "${task.updatedAt}:${task.status}:$nativeRevision:$refresh"
    var lastRead by remember(window) { mutableStateOf<String?>(null) }
    LaunchedEffect(window, readTrigger, busy) {
        if (!busy && lastRead != readTrigger) {
            lastRead = readTrigger
            work { val next = window.taskActions(task.id); main.post { if (alive.get()) actions = next } }
        }
    }
    fun control(action: String, reviewedResume: JSONObject? = null) {
        // Stop is independent of slow reads, pairing/model review and a queued pause/resume.
        if (!alive.get() || action in controlBusy || action !in setOf("cancel", "cancelPending") && controlBusy.isNotEmpty()) return
        controlBusy = controlBusy + action; error = null
        try { runOnControl(Runnable {
            var failure: String? = null
            try { when (action) {
                "cancel" -> window.requestStop(task.id)
                "cancelPending" -> window.cancelPendingGoal(task.id)
                "pause" -> window.pauseTask(task.id)
                "resume" -> window.resumeTask(task.id)
                "approveResume", "denyResume" -> {
                    val request = requireNotNull(reviewedResume)
                    window.decideResumeRequest(task.id, request.getString("resumeRequestId"), request.getLong("reviewRevision"), action == "approveResume")
                }
            } }
            catch (problem: Exception) { failure = deviceControlFailure(problem) }
            val message = failure
            main.post { if (alive.get()) {
                controlBusy = controlBusy - action; error = message
                if (message == null) notice = when (action) { "cancel" -> "已提交停止处理，请核对任务状态。"; "cancelPending" -> "已取消本机尚未开始的目标。"; "pause" -> "已请求暂停，请核对任务状态。"; "denyResume" -> "已拒绝这次恢复请求，原执行保持当前状态。"; else -> "已请求继续同一次执行。" }
                changed(); refresh++
            } }
        }) } catch (_: Exception) { controlBusy = controlBusy - action; error = "控制入口暂不可用，请使用任务通知中的停止按钮。" }
    }
    val pending = task.raw.optJSONArray("approvals").objects().filter { it.optString("state") == "pending" }
    val resumeRequest = actions?.optJSONObject("resumeRequest")?.takeIf { it.optBoolean("ownerActionRequired") }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("paired_task_controls"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SecondaryInk) }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SecondaryInk, modifier = Modifier.testTag("paired_task_control_error")) }
        if (actionAvailable(actions?.optJSONObject("goalReview"))) Button(onClick = { work {
            val native = window.reviewGoal(task.id); main.post { if (alive.get()) goal = native }
        } }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("paired_task_goal_model")) { Text("选择模型并继续") }
        if (actionAvailable(actions?.optJSONObject("resultReview"))) Button(onClick = { work {
            val native = window.reviewResult(task.id); main.post { if (alive.get()) result = native }
        } }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("paired_task_review_result")) { Text("核对成果并结束") }
        resumeRequest?.let { request ->
            DeviceContentSurface { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("已配对设备请求继续", style = MaterialTheme.typography.titleSmall)
                Text("来源 ${request.optString("callerDeviceId").take(8)} · 仍使用这次执行的原权限和剩余预算。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                if (request.optBoolean("stale")) Text("任务状态已经变化。请拒绝这次旧请求，再由外部客户端读取同任务后重新请求。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { control("approveResume", JSONObject(request.toString())) }, enabled = controlBusy.isEmpty() && !request.optBoolean("stale") && actionAvailable(actions?.optJSONObject("resume")), modifier = Modifier.heightIn(min = 48.dp).testTag("paired_task_confirm_resume_request")) { Text("确认继续这次执行") }
                    TextButton(onClick = { control("denyResume", JSONObject(request.toString())) }, enabled = controlBusy.isEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("paired_task_deny_resume_request")) { Text("拒绝") }
                }
            } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (actionAvailable(actions?.optJSONObject("pause"))) OutlinedButton(onClick = { control("pause") }, enabled = controlBusy.isEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("paired_task_pause")) { Text("暂停") }
            if (resumeRequest == null && actionAvailable(actions?.optJSONObject("resume"))) OutlinedButton(onClick = { control("resume") }, enabled = controlBusy.isEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("paired_task_resume")) { Text("继续这次执行") }
            if (actionAvailable(actions?.optJSONObject("pendingCancel"))) TextButton(onClick = { control("cancelPending") }, enabled = "cancelPending" !in controlBusy, modifier = Modifier.heightIn(min = 48.dp).testTag("paired_task_cancel_pending")) { Text("取消本机未启动目标") }
            else if (actionAvailable(actions?.optJSONObject("stop"))) TextButton(onClick = { control("cancel") }, enabled = "cancel" !in controlBusy, modifier = Modifier.heightIn(min = 48.dp).testTag("paired_task_stop")) { Text(if ("cancel" in controlBusy) "正在请求停止…" else "请求停止") }
        }
        if (unresolvedOperations(task).isNotEmpty()) Text("有操作结果待核对，不会重新派发或直接标成完成。仍可请求停止后续执行。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        else if (task.status == "needs_confirmation") Text(if (actionAvailable(actions?.optJSONObject("resultReview"))) "执行已结束，请核对返回的成果。" else "成果已返回，执行结束状态仍需核对。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
        if (task.status in setOf("paused", "awaiting_user") && !actionAvailable(actions?.optJSONObject("pendingCancel")) && !actionAvailable(actions?.optJSONObject("goalReview")) && !actionAvailable(actions?.optJSONObject("resume"))) {
            actions?.optJSONObject(if (task.raw.optString("deviceExecutionKind") == "GOAL" && task.raw.optString("runId").isBlank()) "goalReview" else "resume")?.optString("reason")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SecondaryInk) }
        }
        pending.take(16).forEach { row ->
            val id = row.optString("id", row.optString("approvalId"))
            DeviceContentSurface { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.optString("message").ifBlank { "这次操作需要你确认" }, style = MaterialTheme.typography.titleSmall)
                Text(row.optString("tool"), style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                TextButton(onClick = { work { val native = window.reviewApproval(task.id, id); main.post { if (alive.get()) approval = native } } }, enabled = !busy && id.isNotBlank() && row.optLong("expiresAt") > System.currentTimeMillis(), modifier = Modifier.heightIn(min = 48.dp).testTag("paired_task_review_approval_$id"), contentPadding = PaddingValues(0.dp)) { Text("核对这次操作") }
            } }
        }
        if (pending.size > 16) Text("还有更多待处理操作，当前显示前 16 项。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
    }
    goal?.let { native -> DeviceGoalModelReview(native.view(), busy, ::dismissReview, openModels = { dismissReview(); openModels() }) { future -> work {
        try { window.confirmGoal(native, future); main.post { if (alive.get()) { changed(); refresh++ } } } finally { main.post { if (alive.get()) goal = null } }
    } } }
    result?.let { native -> DevicePairedResultReview(native.view(), busy, ::dismissReview) { completed -> work {
        try { window.confirmResult(native, completed); main.post { if (alive.get()) { changed(); refresh++; notice = "已保存这次成果核对。" } } } finally { main.post { if (alive.get()) result = null } }
    } } }
    approval?.let { native -> DevicePairedOperationReview(native.view(), busy, ::dismissReview) { approved -> work {
        try { window.decideApproval(native, approved); main.post { if (alive.get()) { changed(); refresh++ } } } finally { main.post { if (alive.get()) approval = null } }
    } } }
}

@Composable
private fun DevicePairedResultReview(view: JSONObject, busy: Boolean, close: () -> Unit, confirm: (Boolean) -> Unit) {
    var checked by remember(view) { mutableStateOf(false) }
    var details by remember(view) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surface, title = { Text("核对这次成果") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(view.optString("summary").ifBlank { "执行已经结束，等待你核对成果。" }, style = MaterialTheme.typography.bodyLarge)
            Text("只记录这次执行的核对结果，不会重新执行。设备权限和已经发生的操作不会因此撤销。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text(if (details) "收起结果依据" else "查看结果依据") }
            if (details) SelectionContainer { Text((view.optJSONObject("evidence") ?: JSONObject()).toString(2), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            Row { Checkbox(checked, { checked = it }, enabled = !busy, modifier = Modifier.testTag("paired_task_result_checked")); Text("我已核对返回的成果", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 10.dp)) }
        }
    }, confirmButton = { Button(onClick = { confirm(true) }, enabled = checked && !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("paired_task_result_confirm")) { Text("确认成果并结束") } },
        dismissButton = { TextButton(onClick = { confirm(false) }, enabled = checked && !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("paired_task_result_close")) { Text("结束但不确认完成") } })
}

@Composable
private fun DevicePairedOperationReview(view: JSONObject, busy: Boolean, close: () -> Unit, decide: (Boolean) -> Unit) {
    var details by remember(view) { mutableStateOf(false) }
    var checked by remember(view) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surface, title = { Text("核对这次设备操作") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(view.optString("message").ifBlank { when (view.optString("tool")) { "project_execute" -> "在指定项目执行这次代码"; "project_plan" -> "保存这次项目方案"; else -> view.optString("tool") } }, style = MaterialTheme.typography.titleSmall)
            if (view.optString("tool") == "project_execute") Text("执行代码可能访问该系统用户可访问的资源。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            Text("只决定这次执行的这组准确参数，不新增任务或扩大设备权限。", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
            DeviceFact("执行范围", deviceResourceText(view.optJSONObject("resourceBinding") ?: JSONObject()))
            TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) { Text(if (details) "收起准确参数" else "查看准确参数") }
            if (details) SelectionContainer { Text(view.optString("tool") + "\n" + (view.optJSONObject("arguments") ?: JSONObject()).toString(2), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            Row { Checkbox(checked, { checked = it }, enabled = !busy, modifier = Modifier.testTag("paired_task_approval_checked")); Text("我已核对这次操作范围", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 10.dp)) }
        }
    }, confirmButton = { Button(onClick = { decide(true) }, enabled = checked && !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("paired_task_approval_confirm")) { Text("允许这次操作") } },
        dismissButton = { TextButton(onClick = { decide(false) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("拒绝这次操作") } })
}
private fun deviceControlFailure(error: Exception) = error.message?.take(300) ?: "任务状态已变化，请重新读取后核对。"
