package dev.pocketengine.ui

import dev.pocketengine.store.TaskStore
import org.json.JSONArray
import org.json.JSONObject

internal data class ChannelReceivingReview(val profileIds: List<String>, val fingerprint: String, val names: List<String>, val reviewedProfiles: String)

internal fun reviewChannelReceiving(channels: JSONObject, ids: Collection<String>): ChannelReceivingReview {
    require(ids.size in 1..6 && ids.toSet().size == ids.size) { "请选择1至6个不同连接" }
    val profiles = channels.optJSONArray("profiles").objects()
    val rows = ids.sorted().map { id -> profiles.find { it.optString("id") == id } ?: error("所选连接已不存在，请刷新") }
    require(rows.all { it.optString("provider") in setOf("qq", "weixin") && it.opt("enabled") == true && it.opt("hasSecret") == true }) { "仅支持已启用并配置凭据的QQ官方机器人或微信iLink" }
    require(rows.count { it.optString("provider") == "qq" } <= 3 && rows.count { it.optString("provider") == "weixin" } <= 3) { "QQ与微信分别最多选择3个连接" }
    val reviewed = JSONArray(); rows.forEach { reviewed.put(JSONObject(it.toString())) }
    val epochs = JSONArray(); rows.forEach { epochs.put(JSONObject().put("id", it.getString("id")).put("transportEpoch", it.getString("transportEpoch"))) }
    return ChannelReceivingReview(rows.map { it.getString("id") }, TaskStore.capabilityFingerprint(JSONObject().put("profiles", reviewed)), rows.map { it.optString("name") + " · " + if (it.optString("provider") == "qq") "QQ官方机器人" else "微信iLink" }, epochs.toString())
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
