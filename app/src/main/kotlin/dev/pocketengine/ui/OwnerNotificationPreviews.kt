package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.pocketengine.features.notifications.NotificationOwnerWorkflows
import org.json.JSONObject

/** Existing owner Activity/window and product theme. No intent, generic feature command,
 * saved-state boolean or notice body is an owner grant. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OwnerNotificationPreviewsPage(controller: PocketWorldController) {
    val owner = controller.notificationPreviewsOwner
    val focused = controller.notificationPreviewsFocused
    val foreground = controller.notificationPreviewsForeground
    fun connectionLabel(status: String) = when (status) {
        "disabled" -> "已关闭"; "waiting_connection" -> "等待系统连接"; "connected" -> "已连接"; "disconnected" -> "暂时断开"
        "access_revoked" -> "系统访问已撤销"; "policy_unavailable" -> "授权保存待核对"; "snapshot_failed" -> "系统预览不可读取"; "bind_failed" -> "系统连接未确认"; else -> "待刷新"
    }
    var state by remember { mutableStateOf(JSONObject()) }
    var packages by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var notices by remember { mutableStateOf(emptyList<JSONObject>()) }
    var targets by remember { mutableStateOf(emptyList<JSONObject>()) }
    var nextTargets by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<String?>(null) }
    var packageFilter by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var enableReview by remember { mutableStateOf<Pair<String, Set<String>>?>(null) }
    var saveReview by remember { mutableStateOf<NotificationOwnerWorkflows.Review?>(null) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var ownerEpoch by remember { mutableLongStateOf(controller.notificationPreviewsOwnerEpoch) }
    fun run(action: () -> Unit) { try { failure = null; action() } catch (error: Exception) { failure = error.message ?: "本次操作尚未确认，请刷新后核对原状态" } }
    fun refresh() {
        state = owner.state()
        if (state.optBoolean("enabled")) selected = (0 until state.getJSONArray("packages").length()).map { state.getJSONArray("packages").getString(it) }.toSet()
        packages = owner.packages().objects()
        notices = owner.read(state.getString("consentRevision")).getJSONArray("notifications").objects()
        if (targets.isEmpty()) { val page = owner.targets(""); targets = page.getJSONArray("tasks").objects(); nextTargets = page.optString("nextCursor") }
    }
    // Content is process-local and cleared whenever the owner leaves this Activity. Never
    // restore private bodies or an authorization confirmation from savedInstanceState.
    LaunchedEffect(foreground, focused, controller.notificationPreviewsWindowRevision, controller.notificationPreviewsSourceRevision, controller.notificationPreviewsOwnerEpoch) {
        if (!foreground || ownerEpoch != controller.notificationPreviewsOwnerEpoch) { ownerEpoch = controller.notificationPreviewsOwnerEpoch; notices = emptyList(); saveReview = null; enableReview = null; pending = null }
        if (foreground && focused) { pending?.let { action -> pending = null; run(action) }; run { refresh() } }
    }
    BackHandler { controller.closeNotificationPreviews() }
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("其他应用通知预览", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); AffectToolButton("close", "返回原界面", controller::closeNotificationPreviews) } }
            item { Text("与本应用的任务通知不同。系统通知访问可能覆盖多个应用；下方选择限定本应用实际处理的范围。只保留当前有界预览，不是完整聊天记录，也不会自动发给模型、群或记忆。", style = MaterialTheme.typography.bodySmall) }
            item { Text("系统访问：${if (state.optBoolean("systemAccessGranted")) "已允许" else "未允许"} · 本机读取：${if (state.optBoolean("enabled")) "已开启" else "已关闭"} · 连接：${connectionLabel(state.optString("status"))}", modifier = Modifier.testTag("notification_preview_state")) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { run { owner.openSystemSettings() } }) { Text("系统通知访问设置") }
                TextButton(onClick = { run { refresh() } }, enabled = focused) { Text("刷新") }
            } }
            if (!state.optBoolean("enabled")) {
                item { Text("选择 1–20 个应用", style = MaterialTheme.typography.titleMedium); Text("只列出系统允许本应用发现的应用；不是 Android 按包授权。", style = MaterialTheme.typography.bodySmall) }
                item { OutlinedTextField(packageFilter, { packageFilter = it }, label = { Text("查找应用或包名") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                items(packages.filter { packageFilter.isBlank() || it.optString("label").contains(packageFilter, true) || it.optString("packageName").contains(packageFilter, true) }, key = { it.optString("packageName") }) { app ->
                    val pkg = app.getString("packageName")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Checkbox(pkg in selected, { checked -> selected = if (checked) selected + pkg else selected - pkg }, enabled = pkg in selected || selected.size < 20, modifier = Modifier.testTag("notification_package_$pkg")); Column(Modifier.weight(1f)) { Text(app.optString("label")); Text(pkg, style = MaterialTheme.typography.bodySmall) } }
                }
                item { Button(onClick = { enableReview = state.optString("consentRevision") to selected.toSet() }, enabled = focused && state.optBoolean("systemAccessGranted") && selected.size in 1..20, modifier = Modifier.testTag("notification_preview_enable")) { Text("核对并开启所选应用（${selected.size}）") } }
            } else {
                item { Text("允许范围：${selected.joinToString("、")}", style = MaterialTheme.typography.bodySmall); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { val revision = state.getString("consentRevision"); run { owner.disable(revision); refresh(); notice = "已停止后续读取并清空当前预览；你明确另存的附件仍保留。" } }, modifier = Modifier.testTag("notification_preview_disable")) { Text("关闭并清空预览") }
                    if (!state.optBoolean("listenerConnected")) TextButton(onClick = { run { owner.reconnect(state.getString("consentRevision")); refresh() } }) { Text("重新连接") }
                } }
                item { HorizontalDivider(); Text("另存到已有私人任务", style = MaterialTheme.typography.titleMedium); Text("只保存你复核的这一条及其准确版本。保存不启动任务、不增加工具授权、不立即发模型；以后使用仍走原任务的来源与工具权限。", style = MaterialTheme.typography.bodySmall) }
                items(targets, key = { "task-" + it.optString("taskId") }) { task -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { RadioButton(target == task.getString("taskId"), { target = task.getString("taskId") }, modifier = Modifier.testTag("notification_target_${task.getString("taskId")}")); Text(task.optString("goal"), modifier = Modifier.weight(1f)) } }
                if (nextTargets.isNotBlank()) item { TextButton(onClick = { run { val page = owner.targets(nextTargets); targets = (targets + page.getJSONArray("tasks").objects()).distinctBy { it.optString("taskId") }; nextTargets = page.optString("nextCursor") } }) { Text("更早的任务") } }
                if (targets.isEmpty()) item { Text("尚无可选的私人手机或助手任务。请返回原界面创建明确目标，不会从通知自动创建任务。", style = MaterialTheme.typography.bodySmall) }
                item { Text("当前预览（${notices.size}）", style = MaterialTheme.typography.titleMedium); Text("重新连接时的已有通知只是基线，不是新事件。移除、更新、停用或撤权后必须重新复核。", style = MaterialTheme.typography.bodySmall) }
                items(notices, key = { it.optString("notificationRef") }) { row ->
                    val preview = row.getJSONObject("preview")
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.optString("packageName") + if (row.optString("kind") == "baseline") " · 已有通知基线" else " · 当前变化", style = MaterialTheme.typography.bodySmall)
                        Text(preview.optString("title"), style = MaterialTheme.typography.titleSmall); Text(preview.optString("text"))
                        TextButton(onClick = { target?.let { task -> run { saveReview = owner.review(state.getString("consentRevision"), row.getString("notificationRef"), row.getLong("version"), task) } } }, enabled = focused && target != null, modifier = Modifier.testTag("notification_review_${row.getString("notificationRef")}")) { Text("复核这一条并另存") }
                        HorizontalDivider()
                    }
                }
            }
            notice?.let { item { Text(it, modifier = Modifier.testTag("notification_preview_receipt")) } }
            failure?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("notification_preview_error")) } }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
    enableReview?.let { frozen -> AlertDialog(onDismissRequest = { enableReview = null }, title = { Text("允许所选应用的通知预览？") }, text = { Text(frozen.second.joinToString("\n") + "\n只在本机预览；不会自动执行通知中的文字。", modifier = Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { Button(onClick = { enableReview = null; pending = { owner.enable(frozen.first, frozen.second, true); refresh() } }) { Text("明确开启所选范围") } }, dismissButton = { TextButton(onClick = { enableReview = null }) { Text("取消") } }) }
    saveReview?.let { frozen ->
        val reviewed = frozen.view(); val evidence = reviewed.getJSONObject("evidence"); val preview = evidence.getJSONObject("preview")
        AlertDialog(onDismissRequest = { saveReview = null }, title = { Text("另存这一条到原任务？") }, text = { Text("任务：${reviewed.getJSONObject("target").optString("goal")}\n应用：${evidence.optString("packageName")}\n${preview.optString("title")}\n${preview.optString("text")}\n内容不证明发送者身份，也不授予执行许可。", modifier = Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { Button(onClick = { saveReview = null; pending = { owner.save(frozen, true); notice = "已保存为原任务附件；未发送模型、未开始运行。"; refresh() } }) { Text("明确保存这一条") } }, dismissButton = { TextButton(onClick = { saveReview = null }) { Text("取消") } })
    }
}
