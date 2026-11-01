package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

internal fun latestFollowUpPolicy(state: PocketUiState): JSONObject? = listOfNotNull(state.moduleResults["automation.getFollowUpPolicy"]?.optJSONObject("policy"), state.moduleResults["owner.setFollowUpPolicy"]?.optJSONObject("policy")).maxByOrNull { it.optLong("revision") }

@Composable
internal fun FollowUpSettings(state: PocketUiState, controller: PocketWorldController) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { controller.requestFeature("automation", "getFollowUpPolicy") }
    val policy = latestFollowUpPolicy(state)
    TextButton(onClick = { open = true }) { Text(if (policy?.optBoolean("enabled") == true) "跟进设置 · 已启用" else "跟进设置 · 默认关闭", fontSize = 12.sp) }
    if (open && policy == null) AlertDialog(onDismissRequest = { open = false }, containerColor = Color.White, title = { Text("读取跟进设置") }, text = { Text(state.moduleErrors["automation.getFollowUpPolicy"] ?: "正在读取已保存的真实设置…") }, confirmButton = { TextButton(onClick = { controller.requestFeature("automation", "getFollowUpPolicy") }) { Text("重新读取") } }, dismissButton = { TextButton(onClick = { open = false }) { Text("关闭") } })
    if (open && policy != null) FollowUpPolicyEditor(policy, state, controller) { open = false }
}

@Composable
private fun FollowUpPolicyEditor(policy: JSONObject, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    val original = remember { JSONObject(policy.toString()) }
    var enabled by remember { mutableStateOf(original.optBoolean("enabled")) }
    var zone by remember { mutableStateOf(original.optString("zoneId", "UTC")) }
    var quiet by remember { mutableStateOf(original.optJSONObject("quietHours") != null) }
    var start by remember { mutableStateOf(original.optJSONObject("quietHours")?.optString("start") ?: "22:00") }
    var end by remember { mutableStateOf(original.optJSONObject("quietHours")?.optString("end") ?: "08:00") }
    var cooldown by remember { mutableStateOf((original.optLong("cooldownMillis") / 60_000).toString()) }
    var pauseUntil by remember { mutableLongStateOf(original.optLong("pausedUntil", 0)) }
    var submitted by remember { mutableStateOf<String?>(null) }
    val busy = "owner.setFollowUpPolicy" in state.moduleBusy
    LaunchedEffect(state.moduleResults["owner.setFollowUpPolicy"]) { if (submitted != null && state.moduleResultInputs["owner.setFollowUpPolicy"]?.optString("_requestId") == submitted) close() }
    AlertDialog(onDismissRequest = { if (!busy) close() }, containerColor = Color.White, title = { Text("承诺跟进") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("仅对你已明确保存的承诺发送本机通知。通知打开详情，不会自动操作手机。", fontSize = 13.sp, lineHeight = 22.sp)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("启用跟进通知", Modifier.weight(1f), fontSize = 14.sp); Switch(enabled, { enabled = it }, enabled = !busy) }
            if (enabled && !state.notificationsEnabled) Text("系统尚未允许通知。保存后仍需在权限设置中允许通知。", color = Danger, fontSize = 12.sp)
            OutlinedTextField(zone, { zone = it }, label = { Text("时区，如 Asia/Shanghai") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(quiet, { quiet = it }); Text("使用安静时段", fontSize = 13.sp) }
            if (quiet) { OutlinedTextField(start, { start = it.take(5) }, label = { Text("开始 HH:mm") }, singleLine = true); OutlinedTextField(end, { end = it.take(5) }, label = { Text("结束 HH:mm") }, singleLine = true) }
            OutlinedTextField(cooldown, { cooldown = it.filter(Char::isDigit).take(5) }, label = { Text("两次跟进至少相隔几分钟") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            if (pauseUntil > System.currentTimeMillis()) { Text("暂停至 " + followUpTime(pauseUntil), fontSize = 12.sp); TextButton(onClick = { pauseUntil = 0 }) { Text("解除暂停") } }
            else TextButton(onClick = { pauseUntil = System.currentTimeMillis() + 86_400_000 }) { Text("暂停跟进一天") }
            state.moduleErrors["owner.setFollowUpPolicy"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
        }
    }, confirmButton = { Button(onClick = {
        val body = JSONObject().put("enabled", enabled).put("zoneId", zone.trim()).put("cooldownMillis", (cooldown.toLongOrNull() ?: 0) * 60_000).put("pausedUntil", if (pauseUntil > System.currentTimeMillis()) pauseUntil else JSONObject.NULL).put("quietHours", if (quiet) JSONObject().put("start", start).put("end", end) else JSONObject.NULL)
        submitted = controller.requestOwnerAction("setFollowUpPolicy", JSONObject().put("revision", original.optLong("revision")).put("policy", body))
    }, enabled = !busy && zone.isNotBlank() && (cooldown.toLongOrNull() ?: -1) in 0..44640 && (!quiet || validFollowUpClock(start) && validFollowUpClock(end) && start != end)) { Text(if (busy) "保存中" else "保存设置") } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("取消") } })
}

internal fun validFollowUpClock(value: String): Boolean = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value)

@Composable
internal fun FollowUpReceipts(id: String, state: PocketUiState, controller: PocketWorldController) {
    var confirm by remember(id) { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(id, state.moduleResults["owner.reconcileFollowUpNotification"], state.moduleResults["owner.confirmFollowUpReceipt"]) { controller.requestOwnerAction("listFollowUpReceipts", JSONObject().put("commitmentId", id)) }
    val receipts = state.moduleResults["owner.listFollowUpReceipts"].takeIf { state.moduleResultInputs["owner.listFollowUpReceipts"]?.optString("commitmentId") == id }?.optJSONArray("receipts").objects()
    if (receipts.isNotEmpty()) Text("跟进通知记录", fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
    receipts.reversed().forEach { receipt ->
        val claim = receipt.optString("claimId")
        val unknown = receipt.optString("status") == "delivery_unknown"
        Text(when (receipt.optString("status")) { "notified" -> "已提交本机通知，未确认是否已阅读"; "not_sent" -> "已核对未发送"; "reserved" -> "宿主正在核验投递"; "dispatching" -> "已开始通知投递"; "delivery_unknown" -> "通知结果待核对"; else -> receipt.optString("status") }, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 12.dp))
        Text("承诺版本 " + receipt.optLong("commitmentRevision") + " · " + followUpTime(receipt.optLong("recordedAt", receipt.optLong("createdAt"))), color = SecondaryInk, fontSize = 10.sp)
        if (state.requestedFollowUpClaimId == claim) Text("从这条通知打开", fontSize = 10.sp, color = SecondaryInk)
        if (unknown) {
            TextButton(onClick = { controller.requestOwnerAction("reconcileFollowUpNotification", JSONObject().put("claimId", claim)) }, enabled = "owner.reconcileFollowUpNotification" !in state.moduleBusy) { Text("查询本机通知是否仍存在", fontSize = 11.sp) }
            Row { TextButton(onClick = { confirm = claim to "notified" }) { Text("我已看到通知", fontSize = 11.sp) }; TextButton(onClick = { confirm = claim to "not_sent" }) { Text("我已核对未发送", fontSize = 11.sp) } }
        }
    }
    listOf("owner.listFollowUpReceipts", "owner.reconcileFollowUpNotification", "owner.confirmFollowUpReceipt").mapNotNull { state.moduleErrors[it] }.distinct().forEach { Text(it, color = Danger, fontSize = 12.sp) }
    confirm?.let { target -> AlertDialog(onDismissRequest = { confirm = null }, containerColor = Color.White, title = { Text("记录你的核对结论？") }, text = { Text(if (target.second == "notified") "你已亲自看到这条跟进通知。此操作只记录核对结果。" else "请确认你已核对这次通知没有发送。此操作不会自动重发通知。") }, confirmButton = { Button(onClick = { controller.requestOwnerAction("confirmFollowUpReceipt", JSONObject().put("claimId", target.first).put("outcome", target.second)); confirm = null }, enabled = "owner.confirmFollowUpReceipt" !in state.moduleBusy) { Text("确认记录") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("返回核对") } }) }
}
private fun followUpTime(value: Long): String = if (value <= 0) "时间未记录" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
