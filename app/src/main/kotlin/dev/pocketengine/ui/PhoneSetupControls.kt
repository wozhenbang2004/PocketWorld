package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Low-frequency owner controls inside the existing phone setup; no app policy or diagnostic replacement. */
@Composable
internal fun PhoneSetupControls(snapshot: JSONObject, onCommand: (String, JSONObject) -> Unit, onOpenAccessibility: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "收起连接恢复" else "断连与重启后的恢复", fontSize = 12.sp)
    }
    if (!expanded) return
    val recovery = snapshot.optJSONObject("recovery") ?: JSONObject()
    val available = snapshot.has("recovery")
    val busy = recovery.optBoolean("busy")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("核对已选连接。界面读取断连时保留当前系统登记，由你在系统设置中重新启用；不会自动更改其他服务、开启无线调试或重跑任务。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("允许恢复原连接", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = recovery.optBoolean("consent"), onCheckedChange = {
                onCommand("recovery.consent", JSONObject().put("enabled", it).put("expectedRevision", recovery.optLong("revision")))
            }, enabled = available, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                checkedThumbColor = MaterialTheme.colorScheme.surface))
        }
        Text(recoveryOutcome(recovery.optString("lastOutcome")), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (available && !recovery.optBoolean("unlockSignalsAvailable"))
            Text("系统未提供当前解锁监听；请在本页手动核对原连接。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val connection = recovery.optString("connectionOutcome")
        if (connection.isNotBlank() && connection != "not_requested")
            Text(connectionOutcome(connection), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onCommand("recovery.restore", JSONObject()) }, enabled = recovery.optBoolean("consent") && !busy) {
                Text("现在核对原连接", fontSize = 12.sp)
            }
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurface)
        }
        if (recovery.optBoolean("compensationPending")) TextButton(onClick = {
            onCommand("recovery.compensate", JSONObject())
        }, enabled = !busy) { Text("我已在系统中处理，核对旧恢复记录", fontSize = 12.sp) }
        if (available && recovery.optBoolean("needsOwnerAction")) TextButton(onClick = onOpenAccessibility, enabled = !busy) {
            Text("打开系统无障碍设置", fontSize = 12.sp)
        }
    }
}

/** Per-copy owner policy uses LauncherApps metadata, with installation revalidation at save time. */
@Composable
internal fun PhoneOwnerExecutionControls(state: PocketUiState, connection: BackgroundConnectionState, controller: PocketWorldController) {
    var expanded by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<dev.pocketengine.phone.setup.OwnerPhoneApps.Selection?>(null) }
    TextButton(onClick = { expanded = !expanded; if (expanded) controller.loadPhoneOwnerApps() }) {
        Text(if (expanded) "收起应用与输入限制" else "应用分享限制与可选粘贴", fontSize = 12.sp)
    }
    if (!expanded) return
    val display = state.feature("display")
    val policy = display.optJSONObject("appAccess") ?: JSONObject()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("按这台手机系统实际可见的应用副本选择。关闭后，新的读取、截图、动作和分享会重新核对当前规则；这些选项不会授予系统权限。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { choosing = !choosing }, modifier = Modifier.weight(1f), enabled = !controller.phoneOwnerAppsLoading && controller.phoneOwnerApps.isNotEmpty()) {
                Text(if (selected == null) "选择应用副本" else selected!!.label(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            TextButton(onClick = { selected = null; choosing = false; controller.loadPhoneOwnerApps() }, enabled = !controller.phoneOwnerAppsLoading && !controller.phoneOwnerPolicyBusy) { Text("重新读取", fontSize = 12.sp) }
            if (controller.phoneOwnerAppsLoading || controller.phoneOwnerPolicyBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurface)
        }
        if (!controller.phoneOwnerAppsComplete) Text("部分资料已锁定、不可访问，或应用的准确安装身份不可读。这里最多列出 200 个可核对副本；不会替代为主账号。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!controller.phoneOwnerAppsLoading && controller.phoneOwnerApps.isEmpty()) Text("尚无可核对的应用副本。", style = MaterialTheme.typography.bodySmall)
        if (choosing) {
            OutlinedTextField(query, { query = it.take(160) }, label = { Text("搜索应用名称或包名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                controller.phoneOwnerApps.filter { query.isBlank() || it.label().contains(query, true) || it.packageName().contains(query, true) }.forEach { copy ->
                    Column(Modifier.fillMaxWidth().clickable { selected = copy; choosing = false }.padding(vertical = 10.dp)) {
                        Text(copy.label(), style = MaterialTheme.typography.bodyMedium)
                        Text(copy.packageName(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text((if (copy.currentProfile()) "当前资料" else "关联资料或分身") + " · 资料序号 ${copy.profileSerial()} · 用户 ${copy.userId()}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        selected?.let { copy ->
            Text(copy.packageName() + " · 版本 ${copy.versionCode()}\n" +
                (if (copy.currentProfile()) "当前资料" else "关联资料或分身（系统未提供进一步类型）") + " · 资料序号 ${copy.profileSerial()} · UID ${copy.uid()}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val rules = policy.optJSONArray("rules")
            val rule = (0 until (rules?.length() ?: 0)).mapNotNull { rules?.optJSONObject(it) }
                .firstOrNull { it.optString("packageName") == copy.packageName() && it.optInt("userId", -1) == copy.userId() }
            val automation = rule?.optBoolean("automation", true) ?: true
            val ownerShare = rule?.optBoolean("ownerShare", true) ?: true
            val writable = policy.optBoolean("available") && !controller.phoneOwnerPolicyBusy && copy in controller.phoneOwnerApps
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("允许自动读取和操作", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(automation, { controller.setPhoneAppAccess(copy, it, ownerShare, policy.optLong("revision")) }, enabled = writable,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.onSurface, checkedThumbColor = MaterialTheme.colorScheme.surface))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("允许主人屏幕分享", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(ownerShare, { controller.setPhoneAppAccess(copy, automation, it, policy.optLong("revision")) }, enabled = writable,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.onSurface, checkedThumbColor = MaterialTheme.colorScheme.surface))
            }
            Text("分享许可与自动操作相互独立。保存前会复核此资料、UID、版本和安装签名；名单或规则变化时请重新选择。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!policy.optBoolean("available")) Text("应用规则尚不可读，修改暂不可用。", style = MaterialTheme.typography.bodySmall)
        controller.phoneOwnerPolicyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val clipboardAllowed = display.optBoolean("clipboardPasteAllowed")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("允许可恢复的剪贴板粘贴", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(clipboardAllowed, controller::phoneClipboardConsent,
                enabled = display.opt("clipboardPasteAllowed") is Boolean && "display.clipboard.consent" !in state.moduleBusy,
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.onSurface, checkedThumbColor = MaterialTheme.colorScheme.surface))
        }
        Text("默认关闭，优先使用原界面树输入。开启后，明确选用的兼容粘贴会临时占用系统剪贴板并尝试复原；系统拒绝读取或原记录未核对时会停止。关闭不会重复粘贴，也不覆盖你后来复制的内容。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = controller::restorePhoneClipboard, enabled = !connection.taskOwned && !connection.busy && "display.clipboard.restore" !in state.moduleBusy) { Text("核对并复原上次临时剪贴板", fontSize = 12.sp) }
        state.moduleResults["display.clipboard.restore"]?.let { result ->
            Text(if (result.optBoolean("clipboardRestored")) "原记录已处理；若你已复制新内容则保留新内容。本次没有重新粘贴。" else "尚未确认复原，请保持应用在前台后再次核对原记录。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PhonePracticeEvidence(controller: PocketWorldController) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = controller::readPhonePracticeEvidence, enabled = !controller.phonePracticeReading) { Text("读取原演练结果", fontSize = 12.sp) }
        if (controller.phonePracticeReading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurface)
    }
    controller.phonePracticeReadError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    val result = controller.phonePracticeEvidence ?: return
    var expandedCheck by remember(result.optString("taskId"), result.optString("runId")) { mutableStateOf<String?>(null) }
    if (!result.optBoolean("ok")) {
        Text("尚未取得原演练的实际证据；能力检查与请求已接受都不计作操作通过。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(if (result.optString("resultScope") == "current_original_phone_run") "当前原演练任务的实际证据" else "上次原演练任务保存的证据（历史结果）", style = MaterialTheme.typography.bodyMedium)
    val rows = result.optJSONArray("checks") ?: return
    val labels = mapOf("helper" to "助手认证", "display" to "独立显示会话", "tree" to "界面树", "frame" to "实际画面", "click" to "点击后的计数",
        "ascii_input" to "英文输入读回", "chinese_input" to "中文输入读回", "scroll" to "滚动后内容", "back" to "返回离开测试页",
        "live_updates" to "延时内容更新", "main_screen_isolation" to "主屏隔离", "ime_isolation" to "输入法隔离", "rotation" to "旋转后的画面与点击")
    for (index in 0 until rows.length()) {
        val check = rows.optJSONObject(index) ?: continue
        val label = labels[check.optString("id")] ?: continue
        val status = if (!check.optBoolean("applicable", true)) "本模式不适用" else when (check.optString("state")) {
            "passed" -> "已实际核验"
            "failed" -> "未通过"
            else -> "未取得证据"
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expandedCheck = check.optString("id").takeUnless { it == expandedCheck } }, verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (check.optString("state") == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expandedCheck == check.optString("id")) {
            Text(check.optJSONObject("expected")?.optString("description")?.takeIf { it.isNotBlank() } ?: "旧版结果未记录逐项预期；请保留原结果范围。", style = MaterialTheme.typography.bodySmall)
            Text("原因：${check.optString("reasonCode", "not_observed")} · 验收契约 ${check.optInt("contractVersion", 1)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(check.optJSONObject("observed")?.toString(2) ?: "旧版结果没有结构化实际回读。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp)) }
        }
    }
    Text("只统计这条原任务留下的操作前后证据。未运行或未知项保留未核验；退出测试页、停止和资源回收以原任务回执为准。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    result.optString("taskId").takeIf { it.isNotBlank() }?.let { id ->
        TextButton(onClick = { controller.closeBackgroundSetup(); controller.focusTask(id) }) { Text("查看这条原演练任务", fontSize = 12.sp) }
    }
}

private fun recoveryOutcome(value: String): String = when (value) {
    "already_connected" -> "界面读取服务当前已连接。"
    "registered_waiting_for_binding" -> "已核对服务登记，仍需等待系统实际绑定。"
    "owner_settings_required" -> "服务登记仍在，但系统尚未连接。请在系统无障碍设置中核对本应用；没有改写当前服务列表。"
    "secure_settings_permission_missing" -> "系统未授予直接恢复权限；请在原系统设置中重新启用界面读取。"
    "initial_accessibility_authorization_required", "owner_accessibility_disabled" -> "需要主人先在系统设置中明确启用界面读取。"
    "owner_unlock_required" -> "设备仍未解锁，恢复尚未执行。"
    "consent_required", "recovery_consent_revoked" -> "恢复未获当前同意，已停止后续恢复。"
    "recovery_pending_owner_action", "compensation_pending" -> "旧恢复记录尚未结算。请先在系统无障碍设置中处理本应用，再回到这里核对；当前服务列表保持不变。"
    "original_registration_restored" -> "已读回原服务登记；不代表系统已绑定，也未运行任务。"
    "recovery_cancelled" -> "本次恢复已取消，原登记的复原状态另行保留。"
    "platform_refused", "restore_unconfirmed" -> "系统未确认恢复，请使用原系统设置核对。"
    "recovery_busy" -> "正在核对，重复请求不会再次启动恢复。"
    else -> "恢复默认关闭，可随时撤销。未授权的能力会保留明确原因。"
}

private fun connectionOutcome(value: String): String = when (value) {
    "original_helper_authenticated" -> "原后台助手已通过认证核对；任务仍需由主人开始。"
    "original_session_requires_owner_review" -> "已找到原助手，遗留会话仍需主人核对。"
    "original_task_owns_connection" -> "原任务仍持有连接，未更改或接管。"
    "owner_unlock_required" -> "解锁后才能核对连接。"
    "consent_required" -> "恢复同意已失效，未继续连接。"
    "owner_setup_required" -> "尚无原连接配置，请先完成上方设置。"
    else -> "原连接未确认，请使用已选连接方式手动核对；不会切回主屏。"
}
