package dev.pocketengine.ui.web

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.channels.ChannelConfigurationReview
import dev.pocketengine.features.channels.ChannelLoginQr
import dev.pocketengine.features.channels.ChannelLoginState
import dev.pocketengine.features.channels.ChannelsModule
import dev.pocketengine.ui.ChannelReceivingReview
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.channelReceivingCanStop
import dev.pocketengine.ui.objects
import dev.pocketengine.ui.reviewChannelReceiving
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Only presentation reviews. The native channel Core, receiver lease and attachment Book own state. */
internal class PocketWebChannels(
    private val controller: PocketWorldController,
    private val module: ChannelsModule,
    private val owner: Any
) {
    private data class Review(val command: String, val input: String, val expires: Long,
        val window: Pair<Triple<Long, Int, Long>, Long>, val receiving: ChannelReceivingReview? = null)
    private val reviews = linkedMapOf<String, Review>()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var loginId: String? = null
    private var loginView: JSONObject? = null
    private var mediaWindow: ChannelsModule.OwnerMediaWindow? = null
    private var mediaViewId: String? = null
    private var mediaReview: ChannelsModule.OwnerMediaReview? = null
    private var mediaRead: RunControl? = null
    private var mediaBusy = false

    companion object {
        val COMMANDS = setOf(
            "channels.open", "channels.close", "channels.inspect", "channels.saveProfile", "channels.probeProfile",
            "channels.reviewRemoval", "channels.confirmRemoval", "channels.reviewOwner", "channels.confirmOwner",
            "channels.loginStart", "channels.loginInspect", "channels.loginPoll", "channels.loginCancel",
            "channels.syncOnce", "channels.reviewReceiving", "channels.startReceiving", "channels.stopReceiving",
            "channels.notifications", "channels.history", "channels.inspectEvent", "channels.inspectTask", "channels.cancelOutbox",
            "channels.reviewMedia", "channels.freezeMedia", "channels.openMedia", "channels.closeMedia", "channels.openImage", "channels.advanced"
        )
    }
    /** A scan may require switching to the official app. Revoke local reviews, retain only its id. */
    fun background() { generation++; reviews.clear(); closeMedia() }
    fun clear() {
        background()
        val original = loginId; loginId = null; loginView = null
        // The original id also fences a start that has been queued but has not reached the adapter yet.
        if (original != null) runCatching { controller.runModelConnectionWork(Runnable {
            runCatching { module.handle("weixin.login.cancel", JSONObject().put("loginId", original)) }
        }) }
    }
    private fun closeMedia() {
        mediaRead?.cancel(); mediaRead = null; mediaWindow?.close(); mediaWindow = null; mediaViewId = null; mediaReview = null; mediaBusy = false
    }
    private fun fields(args: JSONObject, vararg allowed: String) {
        require(args.keys().asSequence().all { it in allowed }) { "渠道操作包含不适用的字段，请重新打开原入口" }
    }
    private fun string(args: JSONObject, key: String, max: Int = 180, empty: Boolean = false): String {
        val value = args.opt(key)
        require(value is String && value.length <= max && '\u0000' !in value && (empty || value.isNotBlank())) { "请重新核对$key" }
        return value
    }
    private fun bool(args: JSONObject, key: String): Boolean {
        val value = args.opt(key); require(value is Boolean) { "请明确选择$key" }; return value
    }
    private fun channels() = controller.state.feature("channels")
    private fun profile(id: String, epoch: String): JSONObject {
        val actual = channels().optJSONArray("profiles").objects().singleOrNull { it.optString("id") == id }
        require(epoch.isNotBlank()) { "请重新读取原连接代次" }
        ChannelConfigurationReview.profile(actual, epoch); return actual ?: error("原渠道连接已不存在")
    }
    private fun profile(args: JSONObject): JSONObject = profile(string(args, "profileId"), string(args, "expectedTransportEpoch"))
    private fun originalBinding(args: JSONObject): JSONObject? {
        profile(args)
        val actual = channels().optJSONArray("bindings").objects().singleOrNull { it.optString("profileId") == args.optString("profileId") }
        ChannelConfigurationReview.binding(actual, string(args, "expectedBindingEpoch", empty = true), string(args, "expectedTaskGrantEpoch", empty = true))
        return actual
    }
    private fun event(id: String): JSONObject = channels().optJSONArray("inbox").objects().singleOrNull { it.optString("id") == id }
        ?: error("原消息已不在本机保留范围，请重新读取")
    private fun issue(command: String, input: JSONObject, display: JSONObject, receiving: ChannelReceivingReview? = null): JSONObject {
        reviews.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
        check(reviews.size < 16) { "请先关闭或完成已有渠道审阅" }
        val handle = UUID.randomUUID().toString()
        reviews[handle] = Review(command, input.toString(), SystemClock.elapsedRealtime() + 120_000, controller.webWindowTicket(), receiving)
        return JSONObject(display.toString()).put("reviewHandle", handle).put("expiresInSeconds", 120)
    }
    private fun take(args: JSONObject, command: String? = null): Review {
        fields(args, "reviewHandle")
        val original = reviews.remove(string(args, "reviewHandle")) ?: error("原渠道审阅已使用或关闭，请重新核对")
        check(original.expires > SystemClock.elapsedRealtime() && original.window == controller.webWindowTicket() && (command == null || original.command == command)) { "原渠道审阅已变化或过期" }
        return original
    }
    private fun receivingReceipt(command: String, request: String): JSONObject {
        val key = "owner.channelReceiving.$command"
        check(controller.state.moduleResultInputs[key]?.optString("_requestId") == request) { "本次接收操作回执尚未确认，请刷新原会话" }
        controller.state.moduleErrors[key]?.let { error(it) }
        return controller.state.moduleResults[key]?.let { JSONObject(it.toString()) } ?: error("本次接收操作未返回实际结果")
    }
    private fun io(work: () -> JSONObject, done: (JSONObject?, String?) -> Unit) {
        try { controller.runModelConnectionWork(Runnable {
            val result = runCatching(work)
            main.post { done(result.getOrNull(), result.exceptionOrNull()?.message) }
        }) } catch (failure: Exception) { done(null, failure.message ?: "原渠道工作窗口已关闭") }
    }
    private fun loginProjection(view: JSONObject): JSONObject {
        val safe = JSONObject(view.toString()); val content = safe.optString("qrcodeContent"); safe.remove("qrcodeContent")
        if (content.isNotEmpty()) {
            try {
                val bitmap = Bitmap.createBitmap(ChannelLoginQr.pixels(content, 384), 384, 384, Bitmap.Config.ARGB_8888)
                try {
                    val bytes = ByteArrayOutputStream(); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) { "本机二维码编码未完成" }
                    safe.put("qrDataUrl", "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                } finally { bitmap.recycle() }
            } catch (_: Exception) { safe.put("qrError", "这次官方内容暂时无法在本机显示二维码；未交给其他编码服务。") }
            runCatching { URI(content) }.getOrNull()?.let { uri ->
                val host = uri.host?.lowercase().orEmpty()
                if (uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) && (host == "weixin.qq.com" || host.endsWith(".weixin.qq.com"))) safe.put("officialUrl", content)
            }
        }
        return safe
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        if (action == "channels.close") { fields(args); clear(); done(JSONObject().put("closed", true), null); return }
        check(controller.webPrivateOwnerVisible()) { "请在原前台私人工作台操作渠道" }
        require(action in COMMANDS) { "此渠道操作未接入" }
        val originalGeneration = generation; val window = controller.webWindowTicket()
        val deliver: (JSONObject?, String?) -> Unit = { result, error ->
            if (generation == originalGeneration && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible())
                done(result?.let { PocketWebState.publicValue(it) as JSONObject }, error)
            else done(null, "原渠道页面已关闭；请核对原连接或原任务，未自动重做")
        }
        fun feature(command: String, input: JSONObject) {
            controller.requestFeatureReceipt("channels", command, input, deliver)
        }
        when (action) {
            "channels.open", "channels.inspect" -> { fields(args); feature("inspect", JSONObject()) }
            "channels.saveProfile" -> {
                fields(args, "id", "expectedTransportEpoch", "name", "provider", "appId", "secret", "enabled", "autoReply", "qqGuildEnabled", "qqStreamRepliesEnabled", "ownerImagesEnabled", "ownerMediaEnabled")
                val id = string(args, "id"); val expected = string(args, "expectedTransportEpoch", empty = true)
                val current = channels().optJSONArray("profiles").objects().singleOrNull { it.optString("id") == id }
                ChannelConfigurationReview.profile(current, expected)
                val provider = string(args, "provider")
                check(provider in setOf("qq", "weixin") && (current != null || provider == "qq")) { "微信连接请使用现成官方扫码流程" }
                if (current != null) check(current.optString("provider") == provider && current.optString("appId") == string(args, "appId")) { "更换平台或 AppID 请新建连接" }
                val payload = current?.let { JSONObject(it.toString()) } ?: JSONObject()
                payload.put("id", id).put("expectedTransportEpoch", expected).put("name", string(args, "name", 80).trim()).put("provider", provider).put("appId", string(args, "appId"))
                for (key in listOf("enabled", "autoReply", "qqGuildEnabled", "qqStreamRepliesEnabled", "ownerImagesEnabled", "ownerMediaEnabled")) payload.put(key, bool(args, key))
                if (args.has("secret")) { check(provider == "qq") { "微信凭据只能来自原官方登录回执" }; payload.put("secret", string(args, "secret", 256, true)) }
                feature("profile.save", payload)
            }
            "channels.probeProfile", "channels.syncOnce" -> {
                fields(args, "profileId", "expectedTransportEpoch"); val selected = profile(args)
                if (action == "channels.syncOnce") check(selected.optString("provider") == "weixin" && selected.optBoolean("enabled")) { "请先启用原微信连接" }
                feature(if (action == "channels.syncOnce") "weixin.poll" else "profile.probe", JSONObject().put("id", selected.getString("id")).put("expectedTransportEpoch", string(args, "expectedTransportEpoch")))
            }
            "channels.reviewRemoval" -> {
                fields(args, "profileId", "expectedTransportEpoch"); val selected = profile(args)
                val bound = channels().optJSONArray("bindings").objects().singleOrNull { it.optString("profileId") == selected.getString("id") }
                val input = JSONObject().put("id", selected.getString("id")).put("expectedTransportEpoch", selected.getString("transportEpoch"))
                    .put("expectedBindingEpoch", bound?.getString("bindingEpoch") ?: "").put("expectedTaskGrantEpoch", bound?.getString("taskGrantEpoch") ?: "")
                deliver(issue("profile.remove", input, JSONObject().put("name", selected.getString("name")).put("operation", "remove")), null)
            }
            "channels.confirmRemoval" -> { val original = take(args, "profile.remove"); val input = JSONObject(original.input); profile(input.getString("id"), input.getString("expectedTransportEpoch")); feature(original.command, input) }
            "channels.reviewOwner" -> {
                fields(args, "profileId", "expectedTransportEpoch", "expectedBindingEpoch", "expectedTaskGrantEpoch", "operation", "verifiedEventId", "enabled")
                val selected = profile(args); val bound = originalBinding(args); val operation = string(args, "operation")
                val input = JSONObject(args.toString()); input.remove("operation"); input.put("confirmed", true)
                val display = JSONObject().put("operation", operation).put("profileName", selected.getString("name")).put("senderId", bound?.optString("senderId") ?: "")
                val command = when (operation) {
                    "bind" -> {
                        check(!args.has("enabled")); val source = event(string(args, "verifiedEventId"))
                        check(source.optString("profileId") == selected.getString("id") && source.optString("kind") == "dm" && source.optString("transportEpoch") == selected.getString("transportEpoch") && source.optString("authenticatedBy") in setOf("qq_signature", "qq_gateway", "weixin_session", "trusted_relay") && System.currentTimeMillis() - source.optLong("receivedAt") in 0..86_400_000L) { "请选择此原连接最近收到的已认证本人私聊" }
                        display.put("senderId", source.getString("senderId")).put("text", source.optString("text")).put("authenticatedBy", source.getString("authenticatedBy")).put("conversationKind", source.optString("conversationKind")); "owner.bind"
                    }
                    "unbind" -> { check(bound != null && !args.has("enabled") && !args.has("verifiedEventId")); "owner.unbind" }
                    "tasks" -> {
                        check(bound != null && !args.has("verifiedEventId")); val enabled = bool(args, "enabled")
                        check(!enabled || selected.optBoolean("enabled") && channels().optJSONObject("capabilities")?.optBoolean("ownerTaskHostConfigured") == true) { "原连接或任务宿主尚未就绪" }
                        display.put("enabled", enabled); "owner.tasks.set"
                    }
                    else -> error("此主人操作不适用")
                }
                deliver(issue(command, input, display), null)
            }
            "channels.confirmOwner" -> {
                val original = take(args); check(original.command in setOf("owner.bind", "owner.unbind", "owner.tasks.set")) { "这不是原主人审阅" }
                val input = JSONObject(original.input); originalBinding(input); feature(original.command, input)
            }
            "channels.loginStart", "channels.loginInspect", "channels.loginPoll", "channels.loginCancel" -> {
                val requested = string(args, "loginId", 36)
                require(requested.matches(Regex("[a-f0-9-]{36}"))) { "请保留这次原扫码编号" }
                if (action == "channels.loginStart") {
                    fields(args, "loginId", "name"); check(loginId == null || loginId == requested) { "请先核对或关闭原扫码会话，再明确开始新登录" }
                    loginId = requested; if (loginView == null) loginView = JSONObject().put("loginId", requested).put("status", "starting")
                } else fields(args, *if (action == "channels.loginPoll") arrayOf("loginId", "verifyCode") else arrayOf("loginId"))
                val id = loginId
                if (id == null) { deliver(JSONObject().put("loginId", requested).put("status", "not_started"), null); return }
                check(id == requested) { "这不是原扫码会话，未作用于后来的登录" }
                val command = when (action) { "channels.loginStart" -> "start"; "channels.loginPoll" -> "poll"; "channels.loginCancel" -> "cancel"; else -> "inspect" }
                val input = JSONObject().put("loginId", id)
                if (command == "start") input.put("name", string(args, "name", 80))
                if (args.has("verifyCode")) input.put("verifyCode", string(args, "verifyCode", 80, true))
                val previousView = loginView?.let { JSONObject(it.toString()) }
                if (command == "cancel") { loginId = null; loginView = null }
                controller.requestFeatureReceipt("channels", "weixin.login.$command", input) { actual, error ->
                    if (actual == null || error != null) deliver(null, error ?: "原扫码未返回结果，请只核对原会话")
                    else if (generation != originalGeneration || command != "cancel" && loginId != id) deliver(null, "原扫码窗口已关闭")
                    else {
                        try {
                            val merged = ChannelLoginState.merge(if (command == "cancel") previousView else loginView, actual, id)
                            if (command != "cancel") loginView = merged
                            val captured = merged?.let { JSONObject(it.toString()) } ?: JSONObject().put("loginId", id).put("status", "unknown")
                            io({ loginProjection(captured) }, deliver)
                        } catch (failure: Exception) { deliver(null, failure.message ?: "原扫码回执无法核对") }
                    }
                }
            }
            "channels.reviewReceiving" -> {
                fields(args, "profileIds"); val ids = args.getJSONArray("profileIds"); require(ids.length() in 1..6)
                val reviewed = reviewChannelReceiving(channels(), (0 until ids.length()).map { index -> ids.getString(index) })
                check(!channelReceivingCanStop(channels().optJSONObject("background") ?: JSONObject())) { "请先核对并停止原接收会话" }
                deliver(issue("receiving.start", JSONObject(), JSONObject().put("names", JSONArray(reviewed.names)), reviewed), null)
            }
            "channels.startReceiving" -> {
                val original = take(args, "receiving.start"); val reviewed = original.receiving ?: error("原接收选择已丢失")
                deliver(receivingReceipt("start", controller.startChannelReceiving(reviewed, true)), null)
            }
            "channels.stopReceiving" -> {
                fields(args, "sessionId"); val id = string(args, "sessionId")
                check(channels().optJSONObject("background")?.optString("sessionId") == id) { "原接收会话已变化" }
                deliver(receivingReceipt("stop", controller.stopChannelReceiving(id)), null)
            }
            "channels.notifications" -> { fields(args); controller.openChannelReceivingNotifications(); deliver(JSONObject().put("settingsOpened", true).put("receivingStarted", false), null) }
            "channels.history" -> {
                fields(args, "profileId", "expectedTransportEpoch", "eventId"); val selected = profile(args); val source = event(string(args, "eventId"))
                check(source.optString("profileId") == selected.getString("id") && source.optString("transportEpoch") == selected.getString("transportEpoch")) { "原消息不属于此连接代次" }
                feature("history.inspect", JSONObject().put("eventId", source.getString("id")).put("limit", 8))
            }
            "channels.inspectEvent" -> { fields(args, "eventId"); feature("event.get", JSONObject().put("id", event(string(args, "eventId")).getString("id"))) }
            "channels.inspectTask" -> {
                fields(args, "requestId"); val id = string(args, "requestId")
                check(channels().optJSONArray("taskRequests").objects().any { it.optString("requestId") == id }) { "原来源任务已不在本机保留范围" }
                feature("task.inspect", JSONObject().put("requestId", id))
            }
            "channels.cancelOutbox" -> {
                fields(args, "id"); val id = string(args, "id")
                check(channels().optJSONArray("outbox").objects().any { it.optString("id") == id && it.optString("status") == "queued" }) { "原回复已不在等待发送状态，请刷新核对" }
                feature("outbox.cancel", JSONObject().put("id", id))
            }
            "channels.reviewMedia" -> {
                fields(args, "viewId", "eventId", "itemIndex"); val id = event(string(args, "eventId")).getString("id")
                val viewId = string(args, "viewId", 36); require(viewId.matches(Regex("[a-f0-9-]{36}"))) { "请重新打开原媒体窗口" }
                val raw = args.opt("itemIndex"); require(raw is Number && raw.toDouble() == raw.toInt().toDouble() && raw.toInt() in 0..31) { "请重新选择原消息媒体项" }
                check(!mediaBusy) { "请等待原媒体读取完成" }; closeMedia()
                val original = module.openOwnerMediaWindow(owner); mediaWindow = original; mediaViewId = viewId; mediaBusy = true
                var captured: ChannelsModule.OwnerMediaReview? = null
                io({ module.reviewOwnerMedia(original, id, raw.toInt()).also { captured = it }.metadata() }) { value, error ->
                    if (mediaWindow === original) { mediaBusy = false; if (value != null) mediaReview = captured }
                    deliver(value, error)
                }
            }
            "channels.freezeMedia" -> {
                fields(args, "digest"); val digest = string(args, "digest", 64); val original = mediaReview ?: error("请先核对此原媒体项")
                check(!mediaBusy && original.metadata().getString("digest") == digest) { "原媒体审阅已改变" }
                val control = RunControl(); mediaRead = control; mediaBusy = true
                io({ module.freezeOwnerMedia(original, digest, control) }) { value, error ->
                    if (mediaRead === control) { mediaRead = null; mediaBusy = false }
                    deliver(value, error)
                }
            }
            "channels.openMedia" -> {
                fields(args, "digest"); val original = mediaReview ?: error("请重新核对固定原件")
                check(original.metadata().getString("digest") == string(args, "digest", 64)) { "原媒体审阅已变化" }
                controller.openAttachmentOutcome(module.ownerMediaForViewing(original)); deliver(JSONObject().put("viewerRequested", true), null)
            }
            "channels.closeMedia" -> { fields(args, "viewId"); val original = string(args, "viewId", 36); val current = original == mediaViewId; if (current) closeMedia(); deliver(JSONObject().put("closed", current), null) }
            "channels.openImage" -> {
                fields(args, "eventId", "id", "sha256"); val source = event(string(args, "eventId")); val id = string(args, "id"); val hash = string(args, "sha256", 64)
                check(source.optJSONArray("ownerImageArtifacts").objects().any { it.optString("artifactId") == id && it.optString("sha256") == hash }) { "固定原图片版本已变化" }
                controller.openAttachmentOutcome(JSONObject().put("id", id).put("sha256", hash)); deliver(JSONObject().put("viewerRequested", true), null)
            }
            "channels.advanced" -> { fields(args); controller.openAdvanced("channels"); deliver(JSONObject().put("opened", true), null) }
        }
    }
}
