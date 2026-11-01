package dev.pocketengine.ui

import android.app.Activity
import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.channels.ChannelLoginState
import dev.pocketengine.features.channels.ChannelLoginQr
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Native channel setup over the existing feature/profile/login/binding contracts. */
@Composable
internal fun ChannelAccountSetupPanel(state: PocketUiState, controller: PocketWorldController) {
    var qq by remember { mutableStateOf(false) }
    var weixin by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { weixin = true }, modifier = Modifier.fillMaxWidth()) { Text("连接微信私聊") }
        OutlinedButton(onClick = { qq = true }, modifier = Modifier.fillMaxWidth()) { Text("添加QQ官方机器人") }
    }
    ChannelSetupCaption("微信使用官方iLink私聊；QQ使用官方机器人，不是个人QQ。只保存配置，不自动绑定主人或开始收发。无需强制配置电脑。")
    if (qq) ChannelProfileDialog(null, state, controller) { qq = false }
    if (weixin) WeixinNativeLoginDialog(state, controller) { weixin = false }
    ChannelOwnerImageStatus(state, controller)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ChannelProfileActions(profile: JSONObject, binding: JSONObject?, state: PocketUiState, controller: PocketWorldController) {
    var edit by remember { mutableStateOf(false) }
    var bind by remember { mutableStateOf(false) }
    var unbind by remember { mutableStateOf(false) }
    val id = profile.optString("id")
    ChannelSetupCaption(if (!profile.optBoolean("enabled")) "下一步：启用此连接后，再核对真实接收状态。" else if (binding == null) "下一步：先接收一条本人私聊，再在本机核对主人。连接保存不等于身份已绑定。" else "已绑定主人；自动问答、允许任务和持续接收仍是独立设置。")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { edit = true }) { Text("连接设置", fontSize = 12.sp) }
        TextButton(onClick = { bind = true }) { Text(if (binding == null) "核对主人私聊" else "重新核对主人", fontSize = 12.sp) }
        if (binding != null) TextButton(onClick = { unbind = true }) { Text("解除绑定", fontSize = 12.sp) }
        if (profile.optString("provider") == "weixin") TextButton(onClick = { controller.requestFeature("channels", "weixin.poll", JSONObject().put("id", id)) }, enabled = profile.optBoolean("enabled") && "channels.weixin.poll" !in state.moduleBusy) { Text("同步一次", fontSize = 12.sp) }
    }
    for (command in listOf("profile.probe", "weixin.poll", "owner.bind", "owner.unbind")) {
        val key = "channels.$command"
        val input = state.moduleResultInputs[key]
        if (input?.let { it.optString("id", it.optString("profileId")) } == id) state.moduleErrors[key]?.let { Text(it, color = Danger, fontSize = 12.sp) }
    }
    if (edit) ChannelProfileDialog(profile, state, controller) { edit = false }
    if (bind) ChannelOwnerBindingDialog(profile, state, controller) { bind = false }
    if (unbind) AlertDialog(onDismissRequest = { unbind = false }, title = { Text("解除这个账号的主人绑定？") }, text = { Text("新的消息将回到独立外部会话；旧私聊任务授权及私人回答不能继续使用。已发生的外部动作不会撤回。", fontSize = 13.sp) }, confirmButton = { Button(onClick = { controller.requestFeature("channels", "owner.unbind", JSONObject().put("profileId", id)); unbind = false }) { Text("解除绑定") } }, dismissButton = { TextButton(onClick = { unbind = false }) { Text("返回") } })
}

@Composable
private fun ChannelProfileDialog(original: JSONObject?, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    var name by remember { mutableStateOf(original?.optString("name") ?: "我的QQ机器人") }
    var appId by remember { mutableStateOf(original?.optString("appId") ?: "") }
    var secret by remember { mutableStateOf("") } // Never saved to the activity bundle.
    var enabled by remember { mutableStateOf(original?.optBoolean("enabled") ?: true) }
    var auto by remember { mutableStateOf(original?.optBoolean("autoReply") ?: false) }
    var ownerImages by remember { mutableStateOf(original?.optBoolean("ownerImagesEnabled") ?: false) }
    var submitted by remember { mutableStateOf<String?>(null) }
    val provider = original?.optString("provider") ?: "qq"
    val busy = "channels.profile.save" in state.moduleBusy
    LaunchedEffect(state.moduleResults["channels.profile.save"], state.moduleResultInputs["channels.profile.save"]) {
        if (submitted != null && state.moduleResultInputs["channels.profile.save"]?.optString("_requestId") == submitted && state.moduleErrors["channels.profile.save"] == null) close()
    }
    AlertDialog(onDismissRequest = { if (!busy) { secret = ""; close() } }, containerColor = MaterialTheme.colorScheme.surface, title = { Text(if (original == null) "添加QQ官方机器人" else "连接设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("连接名称") }, singleLine = true)
            if (provider == "qq") {
                OutlinedTextField(appId, { appId = it }, label = { Text("QQ平台AppID") }, readOnly = original != null, singleLine = true)
                OutlinedTextField(secret, { secret = it }, label = { Text(if (original == null) "QQ平台AppSecret" else "新AppSecret（留空保留）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                ChannelSetupCaption("AppID/AppSecret来自你有权管理的QQ机器人。更换AppID需另建连接，不沿用旧身份授权。保存不代表账号资格或网关已经验证。")
            } else if (provider == "weixin") ChannelSetupCaption("微信凭据来自原官方扫码流程；本页不索取微信密码。重新登录请从“连接微信私聊”开始，主人绑定另行核对。")
            Row { Checkbox(enabled, { enabled = it }); Text("启用此连接", fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
            Row { Checkbox(auto, { auto = it }); Text("对新消息自动文字问答", fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
            if (provider == "weixin") {
                Row { Checkbox(ownerImages, { ownerImages = it }); Text("下载并向所选图像模型提供主人私聊图片", fontSize = 13.sp, modifier = Modifier.weight(1f).padding(top = 12.dp)) }
                ChannelSetupCaption("默认关闭，仅处理原消息接收时已经绑定的本人私聊；自动图片回答还需同时开启上方自动问答与实际接收。仅此勾选不会启动收发。每条最多4张、单张4MiB/12MP、合计8MiB。会从官方CDN下载/解密，实际文件保存在私人附件并发送给当前明确支持图片的模型；不含语音、视频、群图或其他人的私聊。关闭不删除已经保存的文件。保存更新代次后请发送一条新消息。")
            }
            ChannelSetupCaption("群和非主人私聊只能用明确配置的API-key模型；已认证并由本机绑定的主人私聊才可用本人套餐。文字问答不授予手机工具或群任务能力。")
            if (original != null) ChannelSetupCaption("保存会更新连接代次并停止当前接收/生成；已有动作不回滚。原群任务与待发回复可能需要重新核对来源。")
            if (submitted != null && state.moduleResultInputs["channels.profile.save"]?.optString("_requestId") == submitted) state.moduleErrors["channels.profile.save"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
        }
    }, confirmButton = { Button(onClick = {
        val payload = original?.let { JSONObject(it.toString()) } ?: JSONObject()
        payload.put("name", name.trim()).put("provider", provider).put("appId", appId.trim()).put("enabled", enabled).put("autoReply", auto).put("ownerImagesEnabled", provider == "weixin" && ownerImages)
        if (secret.isNotEmpty()) payload.put("secret", secret)
        submitted = controller.requestFeature("channels", "profile.save", payload); secret = ""
    }, enabled = !busy && name.trim().isNotEmpty() && (provider != "qq" || appId.trim().isNotEmpty() && (original != null || secret.isNotEmpty()))) { Text(if (busy) "保存中" else "保存配置") } }, dismissButton = { TextButton(onClick = { secret = ""; close() }, enabled = !busy) { Text("取消") } })
}

/** Merge only the response belonging to this original native login. It never issues start. */
internal fun mergeChannelLogin(current: JSONObject?, incoming: JSONObject, expectedId: String): JSONObject? {
    return ChannelLoginState.merge(current, incoming, expectedId)
}

@Composable
private fun WeixinNativeLoginDialog(state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("我的微信私聊") }
    var nativeId by rememberSaveable { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<JSONObject?>(null) }
    var code by remember { mutableStateOf("") }
    var requests by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED) }
    fun call(command: String, payload: JSONObject) { val request = controller.requestFeature("channels", command, payload); requests = requests + (command to request) }
    DisposableEffect(lifecycle) { val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState == Lifecycle.State.RESUMED }; lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) } }
    val currentId by rememberUpdatedState(nativeId)
    DisposableEffect(Unit) { onDispose { val id = currentId; if (id != null && (context as? Activity)?.isChangingConfigurations != true) controller.requestFeature("channels", "weixin.login.cancel", JSONObject().put("loginId", id)) } }
    LaunchedEffect(foreground, nativeId) { if (foreground && nativeId != null && ChannelLoginState.mayInspect(view, requests["weixin.login.start"] != null)) call("weixin.login.inspect", JSONObject().put("loginId", nativeId)) }
    val startResult = state.moduleResults["channels.weixin.login.start"]
    val inspectResult = state.moduleResults["channels.weixin.login.inspect"]
    val pollResult = state.moduleResults["channels.weixin.login.poll"]
    val startError = state.moduleErrors["channels.weixin.login.start"]
    LaunchedEffect(startResult, inspectResult, pollResult, startError, requests, nativeId) {
        val id = nativeId ?: return@LaunchedEffect
        for (command in listOf("weixin.login.start", "weixin.login.inspect", "weixin.login.poll")) {
            val key = "channels.$command"
            if (requests[command] != null && state.moduleResultInputs[key]?.optString("_requestId") == requests[command]) state.moduleResults[key]?.let { view = mergeChannelLogin(view, it, id) }
        }
        if (startError != null && requests["weixin.login.start"] != null && state.moduleResultInputs["channels.weixin.login.start"]?.optString("_requestId") == requests["weixin.login.start"]) view = mergeChannelLogin(view, JSONObject().put("loginId", id).put("status", "failed"), id)
        if (view?.optString("status") == "confirmed") code = ""
    }
    val busy = listOf("weixin.login.start", "weixin.login.poll", "weixin.login.inspect").any { "channels.$it" in state.moduleBusy }
    fun dismiss() { nativeId?.let { controller.requestFeature("channels", "weixin.login.cancel", JSONObject().put("loginId", it)) }; nativeId = null; code = ""; close() }
    AlertDialog(onDismissRequest = ::dismiss, containerColor = MaterialTheme.colorScheme.surface, title = { Text("连接微信私聊 · iLink") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChannelSetupCaption("使用官方机器人私聊接入，不读取全部聊天记录，也不代表普通微信群资格。二维码只在本机生成，不交给第三方编码网站。")
            if (nativeId == null) OutlinedTextField(name, { name = it }, label = { Text("连接名称") }, singleLine = true)
            view?.optString("qrcodeContent")?.takeIf { it.isNotEmpty() }?.let { content ->
                NativeWeixinQr(content)
                ChannelSetupCaption("用微信按官方流程扫码/识别后，回到此页检查同一次登录。切到后台只停止页面检查，不重新创建挑战；关闭此页取消本次本机检查。")
                val official = try { val uri = URI(content); uri.takeIf { it.scheme == "https" && it.userInfo == null && (it.port == -1 || it.port == 443) && (it.host == "weixin.qq.com" || it.host?.endsWith(".weixin.qq.com") == true) } } catch (_: Exception) { null }
                if (official != null) TextButton(onClick = { if (foreground) try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(official.toString()))) } catch (failure: Exception) { problem = "此设备无法打开官方登录链接，请按二维码正常扫码。" } }, enabled = foreground) { Text("打开官方登录链接", fontSize = 12.sp) }
            }
            val status = view?.optString("status") ?: if (nativeId == null) "not_started" else "starting"
            Text(when (status) { "confirmed" -> "官方登录已确认，连接已保存"; "awaiting_scan", "wait" -> "等待扫码；尚未绑定主人"; "scaned" -> "已扫描，等待微信确认"; "need_verifycode" -> "请按微信要求核对验证码"; "expired" -> "原二维码已过期，未自动新建"; "cancelled" -> "本次本机检查已取消"; "unknown" -> "原会话不在此进程中，先核对已有连接"; "failed" -> "本次登录未确认成功，未自动重试"; "not_started" -> "尚未向微信申请登录"; else -> "正在核对原登录状态" }, fontSize = 13.sp)
            view?.optString("message")?.takeIf { it.isNotEmpty() }?.let { ChannelSetupCaption(it) }
            if (status == "need_verifycode") OutlinedTextField(code, { code = it }, label = { Text("微信要求的验证码") }, singleLine = true)
            if (status == "confirmed") ChannelSetupCaption("尚未自动回复或绑定主人。回到连接列表，先同步/接收一条本人私聊，再在本机核对并绑定；持续接收仍需明确开启。")
            problem?.let { Text(it, color = Danger, fontSize = 12.sp) }
            for (command in listOf("weixin.login.start", "weixin.login.inspect", "weixin.login.poll")) { val key = "channels.$command"; if (requests[command] != null && state.moduleResultInputs[key]?.optString("_requestId") == requests[command]) state.moduleErrors[key]?.let { Text(it, color = Danger, fontSize = 12.sp) } }
        }
    }, confirmButton = {
        val status = view?.optString("status")
        if (nativeId == null || status in setOf("expired", "cancelled", "failed", "unknown", "verify_code_blocked")) Button(onClick = {
            nativeId?.let { controller.requestFeature("channels", "weixin.login.cancel", JSONObject().put("loginId", it)) }
            val id = UUID.randomUUID().toString(); nativeId = id; view = null; code = ""; requests = emptyMap(); problem = null
            call("weixin.login.start", JSONObject().put("loginId", id).put("name", name.trim()))
        }, enabled = foreground && !busy && name.trim().isNotEmpty()) { Text("明确开始新登录") }
        else if (status != "confirmed") Button(onClick = { val payload = JSONObject().put("loginId", nativeId); if (code.isNotEmpty()) payload.put("verifyCode", code); call("weixin.login.poll", payload); code = "" }, enabled = foreground && !busy) { Text(if (busy) "正在检查" else "检查原登录") }
        else Button(onClick = ::dismiss) { Text("回到连接列表") }
    }, dismissButton = { TextButton(onClick = ::dismiss) { Text("关闭本次检查") } })
}

@Composable
private fun NativeWeixinQr(content: String) {
    var problem by remember(content) { mutableStateOf<String?>(null) }
    val bitmap by produceState<Bitmap?>(null, content) {
        val generated = withContext(Dispatchers.Default) { runCatching { val pixels = ChannelLoginQr.pixels(content, 512); Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888) } }
        problem = if (generated.isFailure) "官方登录内容暂时无法在本机显示二维码；未使用外部编码服务。" else null
        value = generated.getOrNull()
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = "本次微信官方登录二维码", modifier = Modifier.fillMaxWidth().height(240.dp), filterQuality = FilterQuality.None) }
    problem?.let { Text(it, color = Danger, fontSize = 12.sp) }
}

@Composable
private fun ChannelOwnerBindingDialog(profile: JSONObject, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var accepted by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<String?>(null) }
    val id = profile.optString("id")
    val candidates = state.feature("channels").optJSONArray("inbox").objects().filter { it.optString("profileId") == id && it.optString("kind") == "dm" && it.optString("transportEpoch") == profile.optString("transportEpoch") && it.optString("authenticatedBy") in setOf("qq_signature", "qq_gateway", "weixin_session", "trusted_relay") && System.currentTimeMillis() - it.optLong("receivedAt") in 0..86_400_000L }.asReversed()
    val actual = candidates.find { it.optString("id") == selected }
    val busy = "channels.owner.bind" in state.moduleBusy
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state.moduleResults["channels.owner.bind"], state.moduleResultInputs["channels.owner.bind"]) { if (submitted != null && state.moduleResultInputs["channels.owner.bind"]?.optString("_requestId") == submitted && state.moduleErrors["channels.owner.bind"] == null) close() }
    AlertDialog(onDismissRequest = { if (!busy) close() }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("核对已认证的主人私聊") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(profile.optString("name"), fontSize = 14.sp)
            ChannelSetupCaption("只选择你自己的平台账号发来的当前认证私聊。群消息、昵称、“我是主人”等文字及转发来源都不能建立主人身份。")
            if (candidates.isEmpty()) ChannelSetupCaption("当前连接代次下还没有可核对的私聊。请先从本人账号发一条消息并同步；旧凭据代次的消息不能用于新绑定。")
            for (event in candidates.take(10)) Row { RadioButton(selected == event.optString("id"), { selected = event.optString("id"); accepted = false }); Column(Modifier.weight(1f).padding(top = 9.dp)) { Text(event.optString("senderId"), fontSize = 13.sp); ChannelSetupCaption(event.optString("text").take(160)); ChannelSetupCaption("认证来源：" + event.optString("authenticatedBy")) } }
            Row { Checkbox(accepted, { accepted = it }, enabled = actual != null); Text("我已核对这是我自己的账号", fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
            ChannelSetupCaption("绑定后本人私聊可使用私人问答与记忆；创建任务仍需另开权限，任何写入、外发和手机工具不因此自动批准。")
            if (submitted != null && state.moduleResultInputs["channels.owner.bind"]?.optString("_requestId") == submitted) state.moduleErrors["channels.owner.bind"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
        }
    }, confirmButton = { Button(onClick = { if (lifecycle.currentState == Lifecycle.State.RESUMED && actual != null) submitted = controller.requestFeature("channels", "owner.bind", JSONObject().put("profileId", id).put("verifiedEventId", actual.optString("id")).put("confirmed", true)) }, enabled = !busy && actual != null && accepted) { Text("核对后绑定") } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("返回") } })
}

@Composable
private fun ChannelOwnerImageStatus(state: PocketUiState, controller: PocketWorldController) {
    val recent = state.feature("channels").optJSONArray("inbox").objects().filter { event -> event.optJSONObject("messageParts")?.let { it.optString("provider") == "weixin" && it.optJSONArray("parts").objects().any { part -> part.optString("kind") == "image" } } == true }.takeLast(4).asReversed()
    if (recent.isEmpty()) return
    Text("最近4条图片接收范围内的处理回执", fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    for (event in recent) {
        val refs = event.optJSONArray("ownerImageArtifacts").objects()
        val receivedAsOwner = event.optJSONObject("route")?.optBoolean("privateOwner") == true
        ChannelSetupCaption((if (receivedAsOwner) "原主人私聊 · " else "未绑定的外部私聊 · ") + event.optString("senderId").take(80))
        ChannelSetupCaption(when (event.optString("imageInputState")) {
            "frozen_bytes_not_model_understanding" -> "实际文件已冻结，尚无本次模型响应证明；不等于已经看懂。"
            "model_request_prepared" -> "图片输入已准备；模型是否完成尚未确认。"
            "model_response_received" -> "模型已返回本次图片输入的响应；不代表全部内容已理解或回复已送达。"
            "model_result_unconfirmed" -> "模型结果未确认；保留原文件，不自动重放外部投递。"
            else -> "图片尚未冻结/提供给模型；不会把URL或消息元数据当作已看图。"
        })
        event.optString("message").takeIf { it.isNotEmpty() }?.let { ChannelSetupCaption(it) }
        for (ref in refs) {
            ChannelSetupCaption("原图片项 " + ref.optInt("itemIndex") + " · " + ref.optLong("bytes") + " bytes · SHA256 " + ref.optString("sha256").take(16) + "…")
            TextButton(onClick = { controller.openAttachmentOutcome(JSONObject().put("id", ref.optString("artifactId")).put("sha256", ref.optString("sha256"))) }) { Text("查看这个固定版本原图", fontSize = 12.sp) }
        }
        if (refs.isNotEmpty()) ChannelSetupCaption("完整原文件及版本保存在私人附件，可从文件与成果查看；关闭图片处理不会删除这些原件。")
    }
}

@Composable
private fun ChannelSetupCaption(text: String) { Text(text, fontSize = 12.sp, lineHeight = 19.sp, color = SecondaryInk) }
