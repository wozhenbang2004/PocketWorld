package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.store.TaskStore
import org.json.JSONArray
import org.json.JSONObject

/** A native presentation review only; the actual module/visible Activity remain admission authority. */
internal data class ChannelReceivingReview(val profileIds: List<String>, val fingerprint: String, val names: List<String>)
internal fun reviewChannelReceiving(channels: JSONObject, ids: Collection<String>): ChannelReceivingReview {
    require(ids.size in 1..6 && ids.toSet().size == ids.size) { "请选择1至6个不同连接" }
    val profiles = channels.optJSONArray("profiles").objects()
    val rows = ids.sorted().map { id -> profiles.find { it.optString("id") == id } ?: error("所选连接已不存在，请刷新") }
    require(rows.all { it.optString("provider") in setOf("qq", "weixin") && it.opt("enabled") == true && it.opt("hasSecret") == true }) { "仅支持已启用并配置凭据的QQ官方机器人或微信iLink" }
    require(rows.count { it.optString("provider") == "qq" } <= 3 && rows.count { it.optString("provider") == "weixin" } <= 3) { "QQ与微信分别最多选择3个连接" }
    val reviewed = JSONArray(); rows.forEach { reviewed.put(JSONObject(it.toString())) }
    return ChannelReceivingReview(rows.map { it.getString("id") }, TaskStore.capabilityFingerprint(JSONObject().put("profiles", reviewed)), rows.map { it.optString("name") + " · " + if (it.optString("provider") == "qq") "QQ官方机器人" else "微信iLink" })
}
internal fun channelReceivingCanStop(background: JSONObject): Boolean = background.optString("sessionId").isNotBlank() && (background.optString("status") in setOf("starting", "foreground") || background.optBoolean("foregroundServicePresent") || background.optBoolean("cleanupPending") || background.optInt("runningReceivers") > 0)
internal fun channelReceivingOwns(channels: JSONObject, profileId: String): Boolean {
    val background = channels.optJSONObject("background") ?: return false
    return channelReceivingCanStop(background) && background.optJSONArray("profiles").objects().any { it.optString("id") == profileId }
}
internal fun channelReceivingStatus(background: JSONObject): String = when {
    background.optBoolean("cleanupFailed") || background.optBoolean("persistenceFailed") -> "接收清理或状态保存未完全确认"
    background.optBoolean("cleanupPending") -> "正在结束接收，清理尚未确认"
    background.optString("status") == "foreground" && background.opt("foregroundActive") == true && background.opt("foregroundServicePresent") == true -> "前台接收服务已建立（账号状态单独核对）"
    background.optString("status") == "starting" -> "已请求开启，尚未建立前台服务"
    background.optString("status") == "interrupted" -> "上次接收已中断，未自动恢复"
    background.optString("status") == "blocked" -> "接收未能继续，请核对原因"
    else -> "未开启前台持续接收"
}
internal fun weixinReceivingStatus(connection: JSONObject?): String = when {
    connection == null -> "尚未开始iLink接收"
    connection.optString("status") == "login_required" -> "微信授权已失效，需重新扫码"
    connection.opt("monitorRunning") != true -> if (connection.optString("status") == "synchronized") "最近完成过一次同步，当前未持续接收" else "iLink接收已停止"
    connection.optString("status") == "listening" -> "iLink已完成同步，正在接收"
    connection.optString("status") == "retry_wait" -> "同步中断，等待恢复原游标"
    else -> "正在连接iLink，尚未确认同步成功"
}

@Composable
internal fun ChannelReceivingPanel(state: PocketUiState, controller: PocketWorldController) {
    val channels = state.feature("channels")
    val background = channels.optJSONObject("background") ?: JSONObject()
    val platform = background.optJSONObject("platform") ?: JSONObject()
    val candidates = channels.optJSONArray("profiles").objects().filter { it.optString("provider") in setOf("qq", "weixin") && it.optBoolean("enabled") && it.optBoolean("hasSecret") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var review by remember { mutableStateOf<ChannelReceivingReview?>(null) }
    var accepted by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val busy = state.moduleBusy.any { it.startsWith("owner.channelReceiving.") }
    val stopping = channelReceivingCanStop(background)
    LaunchedEffect(candidates.map { it.optString("id") }) { selected = selected.intersect(candidates.map { it.optString("id") }.toSet()) }
    Text("持续接收 · 前台通知", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
    Text(channelReceivingStatus(background), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    background.optString("message").takeIf { it.isNotBlank() }?.let { TeamCaption(it) }
    if (background.optBoolean("foregroundActive")) TeamCaption("进程内接收 ${background.optInt("runningReceivers")} 个 · 已认证或同步 ${background.optInt("authenticatedReceivers")} 个；服务建立不代表所有账号在线。")
    TeamCaption("你明确开启后显示常驻通知并增加耗电。系统省电/Doze可能延迟接收；强停、进程结束或重启后需回到本机重新开启。关闭此页不会停止已开启的接收。")
    if (stopping) OutlinedButton(onClick = { controller.stopChannelReceiving(background.optString("sessionId")) }, enabled = !busy) { Text("请求停止本次接收") }
    else {
        candidates.forEach { profile -> val id = profile.optString("id"); Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(id in selected, { checked -> selected = if (checked) selected + id else selected - id }, enabled = !busy)
            Text(profile.optString("name") + " · " + if (profile.optString("provider") == "qq") "QQ官方机器人" else "微信iLink", fontSize = 13.sp)
        } }
        if (candidates.isEmpty()) TeamCaption("先在账号与主人绑定设置中配置并启用QQ官方机器人或微信iLink；没有连接时不会假装已接收。")
        Button(onClick = { try { review = reviewChannelReceiving(channels, selected); accepted = false; problem = null } catch (failure: Exception) { problem = failure.message } }, enabled = selected.isNotEmpty() && !busy && platform.opt("serviceDeclared") == true) { Text("选择后核对并开启") }
    }
    if (platform.opt("serviceDeclared") != true) TeamCaption("本版本尚未安装前台接收服务，不能开启；普通接收不等于后台常在线。")
    if (platform.opt("notificationsVisible") != true) { TeamCaption("需要允许应用及接收通知；授予通知权限不会自动开始接收。"); TextButton(onClick = controller::openChannelReceivingNotifications) { Text("允许或检查接收通知") } }
    if (platform.optBoolean("deviceIdle") && !platform.optBoolean("batteryOptimizationsIgnored")) TeamCaption("设备当前处于省电空闲，网络接收可能延迟。前台服务不保证绕过系统限制。")
    problem?.let { Text(it, color = Danger, fontSize = 12.sp) }
    for (command in listOf("start", "stop", "refresh")) state.moduleErrors["owner.channelReceiving.$command"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
    review?.let { shown ->
        val unchanged = try { reviewChannelReceiving(channels, shown.profileIds).fingerprint == shown.fingerprint } catch (_: Exception) { false }
        AlertDialog(onDismissRequest = { if (!busy) review = null }, containerColor = Color.White,
            title = { Text("开启这些连接的持续接收？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shown.names.forEach { Text(it, fontSize = 13.sp) }
                Text("将建立有停止按钮的常驻前台通知，并增加耗电。Doze及厂商省电仍可能延迟接收；进程结束、强停或重启后不会静默恢复。停止接收不会停止已有任务，任务仍需到原任务中停止。", fontSize = 13.sp, lineHeight = 22.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(accepted, { accepted = it }); Text("我确认以上连接和接收/耗电限制", fontSize = 12.sp) }
                if (!unchanged) Text("连接配置已改变，请关闭后重新选择并核对。", color = Danger, fontSize = 12.sp)
                state.moduleErrors["owner.channelReceiving.start"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
            } },
            confirmButton = { Button(onClick = { val request = controller.startChannelReceiving(shown, accepted); if (controller.state.moduleResultInputs["owner.channelReceiving.start"]?.optString("_requestId") == request && controller.state.moduleErrors["owner.channelReceiving.start"] == null) review = null }, enabled = accepted && unchanged && !busy) { Text("明确开启接收") } },
            dismissButton = { TextButton(onClick = { review = null }, enabled = !busy) { Text("返回，不开启") } })
    }
    HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 12.dp))
}
