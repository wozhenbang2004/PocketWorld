package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

internal data class McpInstallConfig(val id: String, val name: String, val endpoint: String, val protocolVersion: String) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("endpoint", endpoint).put("protocolVersion", protocolVersion)
}
internal data class McpInstallDiscovery(val requestId: String, val operationId: String, val config: McpInstallConfig, val inputRevision: Long, val expectedInstallationId: String, val command: String = "discoverMcp")
internal data class McpInstallReviewed(val discovery: McpInstallDiscovery, val body: String, val digest: String)
internal data class McpInstallAttempt(val requestId: String, val reviewed: McpInstallReviewed, val tools: Set<String>)
internal data class McpInstallEnableAttempt(val requestId: String, val id: String, val installationId: String, val enabled: Boolean)

/** Pure identity checks shared with focused tests. JSON from the host is never mutated. */
internal object McpInstallPreviewRules {
    const val CURRENT = "2026-07-28"
    const val LEGACY = "2025-11-25"

    @JvmStatic fun validConfig(config: McpInstallConfig): Boolean = try {
        val uri = URI(config.endpoint)
        config.id.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,79}")) && config.name.isNotBlank() && config.name.length <= 80 &&
            config.endpoint.length <= 2000 && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.scheme.equals("https", true) || uri.scheme.equals("http", true) && uri.host in setOf("localhost", "127.0.0.1", "[::1]")) &&
            config.protocolVersion in setOf(CURRENT, LEGACY)
    } catch (_: Exception) { false }

    @JvmStatic fun importedConfig(requestId: String?, envelope: JSONObject?): McpInstallConfig? {
        val result = result(requestId, envelope) ?: return null
        val config = result.optJSONObject("config") ?: return null
        // Only this small, secret-free contract is accepted. Never retain a raw imported file.
        if (config.keys().asSequence().any { it !in setOf("id", "name", "endpoint", "protocolVersion") } ||
            listOf("id", "name", "endpoint").any { config.opt(it) !is String } ||
            config.has("protocolVersion") && config.opt("protocolVersion") !is String) return null
        val parsed = McpInstallConfig(config.optString("id").trim(), config.optString("name").trim(), config.optString("endpoint").trim(), config.optString("protocolVersion", CURRENT))
        return parsed.takeIf(::validConfig)
    }

    @JvmStatic fun result(requestId: String?, envelope: JSONObject?): JSONObject? =
        if (!requestId.isNullOrBlank() && envelope?.optString("requestId") == requestId && envelope.opt("ok") == true) envelope.optJSONObject("result") else null

    @JvmStatic fun installationId(snapshot: JSONObject, id: String): String? {
        val all = snapshot.optJSONArray("plugins") ?: return null
        val plugin = objects(all).find { it.optString("id") == id } ?: return ""
        return plugin.optString("installationId").takeIf { it.isNotBlank() }
            ?: plugin.optLong("installedAt").takeIf { it > 0 }?.let { "legacy-$it" }
    }

    @JvmStatic fun freeze(discovery: McpInstallDiscovery?, config: McpInstallConfig, revision: Long, snapshot: JSONObject, envelope: JSONObject?, now: Long): McpInstallReviewed? = try {
        if (discovery == null || discovery.config != config || discovery.inputRevision != revision ||
            discovery.expectedInstallationId != installationId(snapshot, config.id)) null
        else {
            val body = result(discovery.requestId, envelope)
            if (body == null || !validPreview(discovery, body, now)) null
            else McpInstallReviewed(discovery, body.toString(), digest(body))
        }
    } catch (_: Exception) { null }

    @JvmStatic fun current(reviewed: McpInstallReviewed?, config: McpInstallConfig, revision: Long, snapshot: JSONObject, envelope: JSONObject?, now: Long): JSONObject? = try {
        if (reviewed == null) null else {
            val again = freeze(reviewed.discovery, config, revision, snapshot, envelope, now)
            if (again?.digest != reviewed.digest || digest(JSONObject(reviewed.body)) != reviewed.digest) null else JSONObject(reviewed.body)
        }
    } catch (_: Exception) { null }

    private fun validPreview(discovery: McpInstallDiscovery, body: JSONObject, now: Long): Boolean {
        val config = discovery.config
        if (!validConfig(config) || body.optString("previewId").isBlank() || body.optString("operationId") != discovery.operationId ||
            body.optString("id") != config.id || body.optString("name") != config.name || body.optString("endpoint") != config.endpoint ||
            body.optString("protocolVersion") != config.protocolVersion || body.opt("expectedInstallationId") !is String ||
            body.optString("expectedInstallationId") != discovery.expectedInstallationId || body.optLong("expiresAt") <= now ||
            body.opt("installed") != false || body.opt("enabledAfterInstall") != false || body.optJSONArray("grantsAfterInstall")?.length() != 0) return false
        val tools = body.optJSONArray("tools") ?: return false
        if (tools.length() > 96) return false
        val names = mutableSetOf<String>()
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: return false
            val name = tool.optString("name")
            if (name.isBlank() || !names.add(name) || tool.opt("supported") !is Boolean) return false
            if (tool.optBoolean("supported")) {
                val declaration = tool.optJSONObject("declaration") ?: return false
                if (declaration.optString("name") != name || declaration.optString("kind") != "mcp" || declaration.optString("effect") != "write" ||
                    declaration.optString("endpoint") != config.endpoint || declaration.optString("protocolVersion") != config.protocolVersion ||
                    declaration.optJSONObject("schema") == null || !declaration.optString("descriptorDigest").matches(Regex("[a-f0-9]{64}")) ||
                    !declaration.optString("serverDigest").matches(Regex("[a-f0-9]{64}"))) return false
            }
        }
        return true
    }

    @JvmStatic fun installPayload(reviewed: McpInstallReviewed?, config: McpInstallConfig, revision: Long, snapshot: JSONObject, envelope: JSONObject?, selected: Set<String>, now: Long): JSONObject {
        val body = current(reviewed, config, revision, snapshot, envelope, now) ?: throw IllegalStateException("预览已变化或过期，请重新发现工具。")
        val supported = objects(body.getJSONArray("tools")).filter { it.optBoolean("supported") }.map { it.getString("name") }.toSet()
        require(selected.size in 1..12 && supported.containsAll(selected)) { "请选择 1 至 12 个此次预览中支持的工具。" }
        return JSONObject().put("previewId", body.getString("previewId")).put("expectedInstallationId", body.getString("expectedInstallationId"))
            .put("tools", JSONArray(selected.sorted()))
    }

    @JvmStatic fun installed(attempt: McpInstallAttempt?, envelope: JSONObject?): JSONObject? = try {
        val plugin = result(attempt?.requestId, envelope)
        if (attempt == null || plugin == null || plugin.optString("id") != attempt.reviewed.discovery.config.id ||
            plugin.optString("installationId").isBlank() || plugin.optString("installationId") == attempt.reviewed.discovery.expectedInstallationId ||
            plugin.opt("enabled") != false || plugin.optJSONArray("grants")?.length() != 0) null
        else {
            val manifest = plugin.optJSONObject("manifest")
            val tools = manifest?.optJSONArray("tools")
            val reviewedTools = objects(JSONObject(attempt.reviewed.body).getJSONArray("tools")).associateBy { it.getString("name") }
            val rows = objects(tools)
            if (manifest?.optString("id") != plugin.optString("id") || manifest.optString("name") != attempt.reviewed.discovery.config.name ||
                tools == null || rows.size != tools.length() || rows.size != attempt.tools.size || rows.map { it.optString("name") }.toSet() != attempt.tools ||
                rows.any { digest(it) != reviewedTools[it.optString("name")]?.optJSONObject("declaration")?.let(::digest) }) null
            else JSONObject(plugin.toString())
        }
    } catch (_: Exception) { null }

    @JvmStatic fun enablePayload(plugin: JSONObject, snapshot: JSONObject, enabled: Boolean): JSONObject {
        val id = plugin.optString("id"); val expected = plugin.optString("installationId")
        check(snapshot.optJSONObject("nativeCapabilities")?.opt("setEnabledReviewed") == true) { "当前宿主暂不支持安全启停，请返回后更新宿主。" }
        check(id.isNotBlank() && expected.isNotBlank() && expected == installationId(snapshot, id)) { "安装记录已变化，请重新核对当前版本。" }
        val current = objects(snapshot.optJSONArray("plugins")).find { it.optString("id") == id }
        check(!enabled || current?.optString("mcpStatus") != "needs_discovery") { "旧会话已失效，请重新发现并安装。" }
        return JSONObject().put("id", id).put("expectedInstallationId", expected).put("enabled", enabled)
    }

    @JvmStatic fun enabled(attempt: McpInstallEnableAttempt?, envelope: JSONObject?): JSONObject? {
        val plugin = result(attempt?.requestId, envelope) ?: return null
        return plugin.takeIf { attempt != null && it.optString("id") == attempt.id && it.optString("installationId") == attempt.installationId && it.opt("enabled") == attempt.enabled }
    }

    @JvmStatic fun objects(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
    @JvmStatic fun digest(value: JSONObject): String = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}

/** The cleanup owns only this page's discovery and preview, never another plugin's work. */
private class McpInstallCleanup {
    var operationId: String? = null
    var previewId: String? = null
    fun release(request: (String, JSONObject) -> String) {
        val operation = operationId; val preview = previewId
        operationId = null; previewId = null
        operation?.let { request("cancel", JSONObject().put("operationId", it)) }
        preview?.let { request("discardMcpPreview", JSONObject().put("previewId", it)) }
    }
}

@Composable
internal fun McpInstallPreviewPage(
    snapshot: JSONObject,
    responses: Map<String, JSONObject>,
    pendingCommands: Set<String>,
    loading: Boolean,
    error: String?,
    onRequest: (command: String, payload: JSONObject) -> String,
    onPickConfig: () -> String,
    onBack: () -> Unit,
    requiredPluginId: String? = null,
    requiredTool: String? = null,
    nativeHost: dev.pocketengine.features.NativePluginHost? = null,
    runOAuthWork: ((Runnable) -> Unit)? = null,
    runOAuthControl: ((Runnable) -> Unit)? = null
) {
    var localDiscoveryOpen by remember { mutableStateOf(false) }
    var installedOpen by remember { mutableStateOf(requiredPluginId != null) }
    if (localDiscoveryOpen) {
        // Leave the original owner domain too: its epoch invalidates an in-flight prepare,
        // so a review arriving after this page closes is discarded by the Controller.
        McpLocalDiscoveryPage(snapshot, responses, pendingCommands, loading, error, onRequest, onBack, onManageInstalled = { localDiscoveryOpen = false; installedOpen = true })
        return
    }
    var authorization by remember { mutableStateOf<JSONObject?>(null) }
    var id by remember(requiredPluginId) { mutableStateOf(requiredPluginId.orEmpty()) }
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(McpInstallPreviewRules.CURRENT) }
    var revision by remember { mutableLongStateOf(0L) }
    var discovery by remember { mutableStateOf<McpInstallDiscovery?>(null) }
    var reviewed by remember { mutableStateOf<McpInstallReviewed?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var install by remember { mutableStateOf<McpInstallAttempt?>(null) }
    var installed by remember { mutableStateOf<JSONObject?>(null) }
    var enable by remember { mutableStateOf<McpInstallEnableAttempt?>(null) }
    var enableConfirm by remember { mutableStateOf<JSONObject?>(null) }
    var pickRequest by remember { mutableStateOf<String?>(null) }
    var pickRevision by remember { mutableLongStateOf(0L) }
    var advanced by remember { mutableStateOf(false) }
    var reconfigureRequired by remember(requiredPluginId) { mutableStateOf(false) }
    var exitConfirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val retired = remember { mutableSetOf<String>() }
    val discarded = remember { mutableSetOf<String>() }
    val cleanup = remember { McpInstallCleanup() }
    val request by rememberUpdatedState(onRequest)
    val config = McpInstallConfig(id.trim(), name.trim(), endpoint.trim(), protocol)
    val discoveryResponse = responses[discovery?.command ?: "discoverMcp"]
    val current = McpInstallPreviewRules.current(reviewed, config, revision, snapshot, discoveryResponse, now)
    val discovering = discovery != null && reviewed == null
    val writing = install != null || enable != null || "installMcp" in pendingCommands || "setEnabledReviewed" in pendingCommands || "setToolGrantReviewed" in pendingCommands
    val busy = writing || discovering || pickRequest != null
    val canManage = snapshot.optJSONObject("nativeCapabilities")?.opt("setEnabledReviewed") == true
    val targetInstalled = McpInstallPreviewRules.objects(snapshot.optJSONArray("plugins")).find { it.optString("id") == requiredPluginId }
    val targetHasTool = targetInstalled?.optJSONObject("manifest")?.optJSONArray("tools").let { McpInstallPreviewRules.objects(it).any { tool -> tool.optString("name") == requiredTool } }
    val showConnectionForm = requiredPluginId == null || !targetHasTool || targetInstalled?.optString("mcpStatus") == "needs_discovery" || reconfigureRequired

    fun invalidate() {
        discovery?.let { retired.add(it.requestId) }
        revision++; discovery = null; reviewed = null; selected = emptySet(); installed = null
        cleanup.release(request)
    }
    fun edit(change: () -> Unit) { invalidate(); authorization = null; pickRequest = null; localError = null; message = null; change() }
    fun leave() { invalidate(); onBack() }
    fun back() { if (writing) exitConfirm = true else leave() }
    fun discardPreview(body: JSONObject) {
        val previewId = body.optString("previewId")
        if (previewId.isNotBlank() && discarded.add(previewId)) request("discardMcpPreview", JSONObject().put("previewId", previewId))
    }

    DisposableEffect(Unit) { onDispose { cleanup.release(request) } }
    LaunchedEffect(reviewed?.digest) {
        val expiresAt = reviewed?.let { JSONObject(it.body).optLong("expiresAt") }
        if (expiresAt != null) { delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0)); now = System.currentTimeMillis() }
    }
    LaunchedEffect(discoveryResponse?.toString()) {
        val envelope = discoveryResponse ?: return@LaunchedEffect
        val received = envelope.optString("requestId")
        if (received in retired) envelope.optJSONObject("result")?.let(::discardPreview)
        val active = discovery
        if (active != null && received == active.requestId && reviewed == null) {
            now = System.currentTimeMillis()
            val frozen = McpInstallPreviewRules.freeze(active, config, revision, snapshot, envelope, now)
            if (frozen != null) {
                reviewed = frozen; cleanup.operationId = null; cleanup.previewId = JSONObject(frozen.body).getString("previewId")
                message = null; localError = null
            } else {
                envelope.optJSONObject("result")?.let(::discardPreview)
                invalidate(); localError = mcpInstallFailure(envelope, "未取得与当前配置一致的有效预览，请重新发现。")
            }
        }
    }
    LaunchedEffect(reviewed?.digest, current?.toString()) {
        if (reviewed != null && current == null) { invalidate(); localError = "预览、回执或安装身份已变化，确认已锁定。请重新发现工具。" }
    }
    LaunchedEffect(responses["pickConfig"]?.toString()) {
        val envelope = responses["pickConfig"] ?: return@LaunchedEffect
        if (pickRequest != null && envelope.optString("requestId") == pickRequest) {
            val imported = if (pickRevision == revision) McpInstallPreviewRules.importedConfig(pickRequest, envelope) else null
            pickRequest = null
            if (imported != null && (requiredPluginId == null || imported.id == requiredPluginId)) {
                invalidate(); id = imported.id; name = imported.name; endpoint = imported.endpoint; protocol = imported.protocolVersion
                message = "已读取配置，请核对后发现工具。文件仅填入 ID、名称、地址和协议，不包含凭据。"; localError = null
            } else localError = mcpInstallFailure(envelope, "文件未返回有效的无秘密配置；取消选择不会安装任何工具。")
        }
    }
    LaunchedEffect(responses["installMcp"]?.toString()) {
        val envelope = responses["installMcp"] ?: return@LaunchedEffect
        val attempt = install
        if (attempt != null && envelope.optString("requestId") == attempt.requestId) {
            val receipt = McpInstallPreviewRules.installed(attempt, envelope)
            install = null
            if (receipt != null) { installed = receipt; message = "已安装，保持停用、零工具授权。启用不会代替逐项工具审批。"; localError = null }
            else { discardPreview(JSONObject(attempt.reviewed.body)); localError = mcpInstallFailure(envelope, "未取得匹配的安装回执，请先核对已安装列表，不要重复提交。") }
        }
    }
    LaunchedEffect(responses["setEnabledReviewed"]?.toString()) {
        val envelope = responses["setEnabledReviewed"] ?: return@LaunchedEffect
        val attempt = enable
        if (attempt != null && envelope.optString("requestId") == attempt.requestId) {
            val receipt = McpInstallPreviewRules.enabled(attempt, envelope)
            enable = null
            if (receipt != null) {
                if (installed?.optString("id") == attempt.id) installed = JSONObject(receipt.toString())
                val requiredGranted = requiredTool != null && (0 until (receipt.optJSONArray("grants")?.length() ?: 0)).any { receipt.optJSONArray("grants")?.optString(it) == "tool:$requiredTool" }
                message = if (attempt.enabled && requiredGranted) "所需工具已准备。返回原任务明确继续，执行仍按具体参数审批。" else if (attempt.enabled) "已启用此安装版本；仍需逐项授予工具权限，启用不等于工具可执行。" else "已停用此安装版本。已发生的远端效果不会撤回。"
                localError = null
            } else localError = mcpInstallFailure(envelope, "未取得匹配的启停回执，请核对当前安装状态。")
        }
    }

    BackHandler { back() }
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::back) { Text("返回") }
                Text(if (requiredPluginId != null) "任务所需的 MCP 工具" else "添加 MCP 工具", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { request("snapshot", JSONObject()) }, enabled = !writing && !loading) { Text("刷新", fontSize = 12.sp) }
            }
            HorizontalDivider(color = Line)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (current != null) "核对工具，再安装" else if (installed != null) "安装结果" else if (requiredPluginId != null && targetHasTool) "准备这项任务的工具" else "连接你信任的工具服务", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                if (requiredPluginId != null) McpInstallCaption("来自原任务的能力准备：$requiredPluginId · ${requiredTool.orEmpty()}。这里只显示这个服务和所需工具；已有安装可直接核对授权。")
                McpInstallCaption("发现只读取服务声明，不调用工具。安装后默认停用且不授予任何工具权限。")
                if (requiredPluginId == null && snapshot.optJSONObject("nativeCapabilities")?.opt("localMcpDiscoveryOwnerOnly") == true) TextButton(onClick = { localDiscoveryOpen = true }, enabled = !busy && !loading) { Text("本机程序发现（先审阅，仅元数据）") }
                if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); McpInstallCaption("正在读取当前安装状态…") }
                error?.let { Text(it, color = Danger, fontSize = 13.sp, lineHeight = 22.sp) }
                localError?.let { Text(it, color = Danger, fontSize = 13.sp, lineHeight = 22.sp) }
                message?.let { Surface(color = Soft, shape = Rounded) { Text(it, Modifier.padding(14.dp), fontSize = 13.sp, lineHeight = 22.sp, color = Ink) } }

                if (requiredPluginId != null && targetInstalled != null && targetHasTool) {
                    Text("复用已安装的服务", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    McpInstallCaption("${targetInstalled.optJSONObject("manifest")?.optString("name")} · ${if (targetInstalled.optBoolean("enabled")) "已启用" else "已停用"}\n只需核对这个安装的状态与所需工具权限，准备后返回原任务继续。")
                    TextButton(onClick = { enableConfirm = JSONObject(targetInstalled.toString()) }, enabled = canManage && !busy && !loading && (targetInstalled.optBoolean("enabled") || targetInstalled.optString("mcpStatus") != "needs_discovery")) { Text(if (targetInstalled.optBoolean("enabled")) "停用这个安装…" else "启用这个安装…") }
                    McpToolPermissions(targetInstalled,snapshot,writing || loading || error != null,requiredTool,request)
                    TextButton(onClick = { reconfigureRequired = !reconfigureRequired }, enabled = !busy) { Text(if (reconfigureRequired) "收起服务更新" else "检查新版本或地址", fontSize = 12.sp) }
                }
                if (installed == null && showConnectionForm) {
                    OutlinedTextField(endpoint, { edit { endpoint = it } }, label = { Text("服务地址") }, isError = endpoint.isNotBlank() && !McpInstallPreviewRules.validConfig(McpInstallConfig("check", "检查", endpoint.trim(), McpInstallPreviewRules.CURRENT)), supportingText = { Text("HTTPS 完整地址；不能带账号、密码、查询参数或片段。") }, placeholder = { Text("https://example.com/mcp") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, enabled = !writing, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(name, { edit { name = it } }, label = { Text("显示名称") }, isError = name.trim().length > 80, supportingText = { if (name.trim().length > 80) Text("名称不能超过 80 个字符。") }, singleLine = true, enabled = !writing, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(id, { edit { id = it } }, label = { Text("插件 ID") }, isError = id.isNotBlank() && !id.trim().matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,79}")), supportingText = { Text("字母开头，可含字母、数字、点、横线及下划线；同 ID 会更新现有安装。") }, singleLine = true, enabled = !writing && requiredPluginId == null, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = {
                        invalidate(); localError = null; message = null
                        pickRevision = revision; pickRequest = onPickConfig().takeIf { it.isNotBlank() }
                        if (pickRequest == null) localError = "宿主未能打开配置文件选择器，请重试。"
                    }, enabled = !busy) { Text(if (pickRequest != null) "正在选择文件…" else "从配置文件读取") }
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起协议与支持范围" else "协议与支持范围 · $protocol") }
                    if (advanced) {
                        McpInstallCaption("仅支持 Streamable HTTP。不会自动降级协议；地址不得包含账号、密码、查询参数或片段。当前文件入口仅接受无凭据配置；不要把密钥粘贴到名称或地址中。")
                        listOf(McpInstallPreviewRules.CURRENT to "当前协议", McpInstallPreviewRules.LEGACY to "旧版兼容 · 会话丢失后需重新发现").forEach { (version, title) ->
                            Row(Modifier.fillMaxWidth().selectable(selected = protocol == version, enabled = !writing, role = Role.RadioButton) { edit { protocol = version } }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = protocol == version, onClick = null, enabled = !writing)
                                Text("$version\n$title", fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                        val unsupported = snapshot.optJSONObject("mcpSupport")?.optJSONArray("unsupported")
                        McpInstallCaption("当前不支持：" + if (unsupported != null) (0 until unsupported.length()).joinToString("、") { unsupported.optString(it) } else "stdio、旧版 HTTP+SSE、资源、提示词、任务及二进制内容；OAuth以当前原生授权入口实际能力为准。")
                    }
                    if (nativeHost != null && runOAuthWork != null && runOAuthControl != null) McpOAuthControls(nativeHost, config, busy, runOAuthWork, runOAuthControl) { selectedConnection -> invalidate(); authorization = selectedConnection; message = "已选择原授权连接；下一步仍只发现工具，不自动安装或授予工具权。" }
                    authorization?.let { selectedConnection -> McpInstallCaption("当前使用已授权原连接 ${selectedConnection.optString("connectionId").take(8)}，仅绑定此准确服务地址/协议。退出或到期会在实际 HTTP 前拒绝。")
                        TextButton(onClick = { invalidate(); authorization = null }, enabled = !busy) { Text("本次不使用授权连接") }
                    }
                    if (discovering) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        McpInstallCaption("正在读取服务信息和工具定义…尚未安装或调用任何工具。")
                        OutlinedButton(onClick = { invalidate(); message = "已请求停止发现；迟到结果不会成为可安装预览。" }) { Text("取消发现") }
                    } else if (current == null && install == null) {
                        Button(onClick = {
                            invalidate(); localError = null; message = null; now = System.currentTimeMillis()
                            val expected = McpInstallPreviewRules.installationId(snapshot, config.id)
                            if (expected == null || !McpInstallPreviewRules.validConfig(config)) localError = "请检查地址、名称和插件 ID，并等候安装状态读取完成。"
                            else {
                                val operation = UUID.randomUUID().toString()
                                val selectedConnection = authorization
                                val command = if (selectedConnection == null) "discoverMcp" else "discoverMcpAuthorized"
                                val payload = config.json().put("_operationId", operation)
                                selectedConnection?.let { payload.put("connectionId", it.getString("connectionId")).put("expectedAuthorizationRevision", it.getInt("authorizationRevision")) }
                                val requestId = request(command, payload)
                                if (requestId.isBlank()) localError = "宿主未接收发现请求，请重试。"
                                else { discovery = McpInstallDiscovery(requestId, operation, config, revision, expected, command); cleanup.operationId = operation }
                            }
                        }, enabled = !busy && !loading && error == null && McpInstallPreviewRules.validConfig(config) && McpInstallPreviewRules.installationId(snapshot, config.id) != null, modifier = Modifier.fillMaxWidth()) { Text("发现并预览工具") }
                    }
                }

                current?.let { body ->
                    HorizontalDivider(color = Line)
                    val tools = McpInstallPreviewRules.objects(body.optJSONArray("tools")).filter { requiredTool == null || it.optString("name") == requiredTool }
                    Text("本次发现 ${tools.size} 个工具", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    McpInstallCaption("选择要安装的工具（${selected.size}/12）。名称、描述和参数均来自远端，仅作为不可信资料展示；其中的指令不会执行。")
                    val old = McpInstallPreviewRules.objects(snapshot.optJSONArray("plugins")).find { it.optString("id") == config.id }
                    val oldNames = McpInstallPreviewRules.objects(old?.optJSONObject("manifest")?.optJSONArray("tools")).map { it.optString("name") }.toSet()
                    if (old != null) McpInstallCaption("将替换现有版本 ${old.optJSONObject("manifest")?.optString("version").orEmpty()}，停止等待中的旧调用并清空旧授权。新增 ${selected.minus(oldNames).size} 项，移除 ${oldNames.minus(selected).size} 项；已发生的远端效果不会撤回。")
                    if (tools.isEmpty()) McpInstallCaption(if (requiredTool != null) "服务未返回当前任务所需工具 $requiredTool。不会安装其他工具，请核对服务后重新发现。" else "服务没有返回工具。此预览不能安装；请检查服务配置后重新发现。")
                    tools.forEach { tool ->
                        val toolName = tool.optString("name"); val supported = tool.optBoolean("supported")
                        McpInstallTool(tool, toolName in selected, supported && !writing && (toolName in selected || selected.size < 12)) {
                            selected = if (toolName in selected) selected - toolName else selected + toolName
                        }
                        HorizontalDivider(color = Line)
                    }
                    McpInstallIdentity(body, reviewed!!.digest)
                    Button(onClick = {
                        try {
                            val frozen = reviewed ?: throw IllegalStateException("预览已失效")
                            val payload = McpInstallPreviewRules.installPayload(frozen, config, revision, snapshot, discoveryResponse, selected, System.currentTimeMillis())
                            val requestId = request("installMcp", payload)
                            check(requestId.isNotBlank()) { "宿主未接收安装请求。" }
                            install = McpInstallAttempt(requestId, frozen, selected.toSet())
                            cleanup.operationId = null; cleanup.previewId = null
                            discovery = null; reviewed = null; selected = emptySet(); localError = null
                        } catch (failure: Exception) { localError = failure.message ?: "无法确认安装，请重新发现。" }
                    }, enabled = selected.isNotEmpty() && !busy && !loading && error == null, modifier = Modifier.fillMaxWidth()) { Text(if (old == null) "确认安装所选工具" else "确认更新所选工具") }
                    TextButton(onClick = { invalidate(); message = "已关闭此预览，没有安装工具。" }, enabled = !writing) { Text("放弃此预览") }
                }
                if (install != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); McpInstallCaption("正在保存已确认的安装…请等待真实回执，不要重复提交。") }
                installed?.let { receipt ->
                    Text(receipt.optJSONObject("manifest")?.optString("name").orEmpty(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    McpInstallCaption("安装身份：${receipt.optString("installationId")}\n当前回执：${if (receipt.optBoolean("enabled")) "已启用" else "停用"} · ${receipt.optJSONArray("grants")?.length() ?: 0} 项工具授权")
                    val latest = McpInstallPreviewRules.objects(snapshot.optJSONArray("plugins")).find { it.optString("id") == receipt.optString("id") }
                    val aligned = latest?.optString("installationId") == receipt.optString("installationId")
                    if (!aligned) McpInstallCaption("正在等待当前安装状态核对；如已有其他版本，此回执不能用于启用。")
                    if (!canManage) McpInstallCaption("当前宿主暂不支持按安装身份安全启停，已禁止启用。")
                    if (aligned && latest != null) OutlinedButton(onClick = { enableConfirm = JSONObject(latest.toString()) }, enabled = canManage && !writing && !loading && error == null && (latest.optBoolean("enabled") || latest.optString("mcpStatus") != "needs_discovery")) { Text(if (latest.optBoolean("enabled")) "停用此版本" else "启用此版本…") }
                    Button(onClick = ::back, enabled = !writing, modifier = Modifier.fillMaxWidth()) { Text("返回插件管理") }
                    TextButton(onClick = { invalidate(); message = null }) { Text("再添加一个服务") }
                }

                if (requiredPluginId == null || !targetHasTool) {
                val existing = McpInstallPreviewRules.objects(snapshot.optJSONArray("plugins")).filter { plugin -> (requiredPluginId == null || plugin.optString("id") == requiredPluginId) && McpInstallPreviewRules.objects(plugin.optJSONObject("manifest")?.optJSONArray("tools")).any { it.optString("kind") == "mcp" } }
                HorizontalDivider(color = Line)
                TextButton(onClick = { installedOpen = !installedOpen }) { Text("${if (installedOpen) "收起" else "查看"}已安装 MCP · ${existing.size}") }
                if (installedOpen && (requiredPluginId == null || !targetHasTool)) {
                    if (existing.isEmpty()) McpInstallCaption("尚未安装 MCP 服务。先填写配置，发现工具后再确认安装。")
                    if (!canManage) McpInstallCaption("当前宿主暂不支持安全启停；不会退回旧版无身份检查的操作。")
                    existing.forEach { plugin ->
                        Text(plugin.optJSONObject("manifest")?.optString("name").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        McpInstallCaption("${if (plugin.optBoolean("enabled")) "已启用" else "停用"} · ${plugin.optJSONArray("grants")?.length() ?: 0} 项授权 · ${mcpInstallStatus(plugin.optString("mcpStatus"))}\n安装身份：${plugin.optString("installationId")}")
                        TextButton(onClick = { enableConfirm = JSONObject(plugin.toString()) }, enabled = canManage && !busy && !loading && error == null && (plugin.optBoolean("enabled") || plugin.optString("mcpStatus") != "needs_discovery")) { Text(if (plugin.optBoolean("enabled")) "停用此安装版本…" else "启用此安装版本…") }
                        McpToolPermissions(plugin, snapshot, writing || loading || error != null, requiredTool, request)
                        McpEcosystemControls(plugin, responses, pendingCommands, writing || loading || error != null, request)
                        HorizontalDivider(color = Line)
                    }
                }
                McpTaskRecoveryControls(snapshot, responses, pendingCommands, request)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    enableConfirm?.let { frozen ->
        val nextEnabled = !frozen.optBoolean("enabled")
        val currentIdentity = McpInstallPreviewRules.installationId(snapshot, frozen.optString("id")) == frozen.optString("installationId")
        AlertDialog(onDismissRequest = { enableConfirm = null }, containerColor = Color.White, title = { Text(if (nextEnabled) "启用这个安装版本？" else "停用这个安装版本？") }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(frozen.optJSONObject("manifest")?.optString("name").orEmpty())
                McpInstallCaption("安装身份：${frozen.optString("installationId")}")
                Text(if (nextEnabled) "仅改变启用状态，不新增工具授权。仍需在工具审批中逐项确认；远端工具描述不是授权。" else "会停止继续等待此插件的调用。已经发生的远端效果不会撤回。", fontSize = 13.sp, lineHeight = 22.sp)
                if (!currentIdentity) Text("此安装记录已变化，请取消后核对新版本。", color = Danger)
            }
        }, confirmButton = { Button(onClick = {
            try {
                val payload = McpInstallPreviewRules.enablePayload(frozen, snapshot, nextEnabled)
                val requestId = request("setEnabledReviewed", payload)
                check(requestId.isNotBlank()) { "宿主未接收安全启停请求。" }
                enable = McpInstallEnableAttempt(requestId, frozen.getString("id"), frozen.getString("installationId"), nextEnabled)
                enableConfirm = null; localError = null
            } catch (failure: Exception) { enableConfirm = null; localError = failure.message }
        }, enabled = currentIdentity && canManage && !writing && !loading && error == null) { Text(if (nextEnabled) "确认启用" else "确认停用") } }, dismissButton = { TextButton(onClick = { enableConfirm = null }) { Text("取消") } })
    }
    if (exitConfirm) AlertDialog(onDismissRequest = { exitConfirm = false }, containerColor = Color.White, title = { Text("返回插件管理？") }, text = { Text("已提交的安装或启停请求不会因返回而撤销。请在插件管理中核对实际结果，避免重复安装。") }, confirmButton = { TextButton(onClick = { exitConfirm = false; leave() }) { Text("返回并稍后核对") } }, dismissButton = { TextButton(onClick = { exitConfirm = false }) { Text("继续等待") } })
}

@Composable internal fun McpInstallCaption(text: String) { Text(text, color = SecondaryInk, fontSize = 13.sp, lineHeight = 22.sp) }
@Composable private fun McpInstallTool(tool: JSONObject, selected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    var expanded by remember(tool.toString()) { mutableStateOf(false) }
    val supported = tool.optBoolean("supported")
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }), verticalAlignment = Alignment.Top) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = 10.dp, top = 10.dp)) {
                Text(tool.optString("name"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                McpInstallCaption(if (supported) "可安装 · 按可能产生写入的工具处理\n调用权限：tool:${tool.optString("name")}（安装不会授予）" else "不支持安装")
            }
        }
        if (!supported) Text(tool.optString("unsupportedReason", "当前不支持此工具定义。"), color = Danger, fontSize = 13.sp, lineHeight = 22.sp)
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起说明和参数" else "查看说明和参数") }
        if (expanded) SelectionContainer {
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                McpInstallCaption(tool.optString("description").ifBlank { "服务未提供说明。" })
                val schema = tool.optJSONObject("declaration")?.optJSONObject("schema") ?: tool.optJSONObject("definition")?.optJSONObject("inputSchema")
                Text(schema?.toString(2) ?: "未返回参数定义。", fontSize = 12.sp, lineHeight = 20.sp, color = SecondaryInk)
                tool.optJSONObject("declaration")?.let { McpInstallCaption("工具摘要：${it.optString("descriptorDigest")}\n服务摘要：${it.optString("serverDigest")}") }
            }
        }
    }
}
@Composable private fun McpInstallIdentity(body: JSONObject, digest: String) {
    var expanded by remember(body.optString("previewId")) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起配置和身份" else "查看配置和安装身份") }
    if (expanded) SelectionContainer {
        McpInstallCaption("ID：${body.optString("id")}\n服务自报信息：${body.optJSONObject("serverInfo")?.toString() ?: "未提供"}（不可信资料）\n地址：${body.optString("endpoint")}\n协议：${body.optString("protocolVersion")} · Streamable HTTP\n当前安装：${body.optString("expectedInstallationId").ifBlank { "尚未安装" }}\n预览身份：${body.optString("previewId")}\n本地预览摘要：$digest\n凭据：${if (body.optBoolean("hasToken")) "由宿主安全管理" else "未配置"}\n预览有时效；编辑、返回、取消或安装变化后需重新发现。")
    }
}
private fun mcpInstallFailure(envelope: JSONObject, fallback: String): String =
    if (envelope.opt("ok") == false) envelope.optString("error").ifBlank { envelope.optString("message").ifBlank { fallback } } else fallback
private fun mcpInstallStatus(status: String): String = when (status) {
    "ready" -> "会话就绪"
    "will_revalidate" -> "调用前将重新核对服务身份"
    "needs_discovery" -> "会话已失效，需要重新发现"
    "original_task_revalidation_required" -> "本机 stdio · 原任务调用前核对程序与源码"
    else -> "连接状态待核对"
}
