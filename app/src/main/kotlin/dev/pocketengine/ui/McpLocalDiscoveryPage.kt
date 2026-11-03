package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** The existing MCP surface, progressively selecting, reviewing and observing one owner action.
 * A page is an observer: leaving it never turns an unknown process into success or reruns it. */
@Composable internal fun McpLocalDiscoveryPage(
    snapshot: JSONObject, responses: Map<String, JSONObject>, pendingCommands: Set<String>, loading: Boolean,
    error: String?, onRequest: (String, JSONObject) -> String, onBack: () -> Unit
) {
    val request by rememberUpdatedState(onRequest)
    val pending by rememberUpdatedState(pendingCommands)
    var choicesRequest by remember { mutableStateOf<String?>(null) }
    var sourceRequest by remember { mutableStateOf<String?>(null) }
    var selectedCheckout by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<JSONObject?>(null) }
    var entry by remember { mutableStateOf("") }
    var entryFilter by remember { mutableStateOf("") }
    var executable by remember { mutableStateOf("python3") }
    var pluginId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var argv by remember { mutableStateOf("[]") }
    var advanced by remember { mutableStateOf(false) }
    var revision by remember { mutableLongStateOf(0) }
    var preparing by remember { mutableStateOf<McpLocalPreparation?>(null) }
    var reviewed by remember { mutableStateOf<McpLocalReviewed?>(null) }
    var consent by remember { mutableStateOf(false) }
    var operationId by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<JSONObject?>(null) }
    var readRequest by remember { mutableStateOf<String?>(null) }
    var confirmRequest by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val heldReviews = remember { mutableSetOf<String>() }
    val selection = McpLocalSelection(pluginId.trim(), name.trim(), selectedCheckout, source?.optString("sourceDigest").orEmpty(), executable, argv)
    val current = McpLocalDiscoveryRules.current(reviewed, selection, revision, snapshot, responses["prepareLocalMcpDiscovery"], now)
    val choices = McpLocalDiscoveryRules.result(choicesRequest, responses["localMcpDiscoveryChoices"])
    val checkouts = McpInstallPreviewRules.objects(choices?.optJSONArray("checkouts"))
    val runtimeReady = choices?.optJSONObject("runtime")?.let { it.optBoolean("canExecute") && it.optBoolean("duplexPipesAvailable") } == true
    val sourceBusy = "localMcpDiscoveryChoices" in pendingCommands || "describeLocalMcpWorkspace" in pendingCommands
    val preparingBusy = "prepareLocalMcpDiscovery" in pendingCommands
    val terminal = operation?.optString("status") in setOf("completed", "failed")
    fun ask(command: String, payload: JSONObject): String? = try { localError = null; request(command, payload) } catch (_: Exception) { localError = "这次请求尚未得到有效回执，请先核对原操作，不要重复提交。"; null }
    fun discardReview() {
        reviewed?.let { val id = JSONObject(it.body).optString("reviewId"); if (heldReviews.remove(id)) ask("discardLocalMcpReview", JSONObject().put("reviewId", id)) }
        reviewed = null; preparing = null; consent = false
    }
    fun edit(action: () -> Unit) { discardReview(); revision++; localError = null; action() }
    fun back() { if (operationId == null) discardReview(); onBack() }
    LaunchedEffect(Unit) { choicesRequest = ask("localMcpDiscoveryChoices", JSONObject()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    LaunchedEffect(sourceRequest, responses["describeLocalMcpWorkspace"]?.toString()) {
        val body = McpLocalDiscoveryRules.result(sourceRequest, responses["describeLocalMcpWorkspace"])
        if (body?.optString("checkoutId") == selectedCheckout && body.opt("sourceBodyIncluded") == false && body.opt("wholeCheckoutVersion") == false && body.optString("sourceManifestScope") == "visible_source_only") source = JSONObject(body.toString())
    }
    LaunchedEffect(preparing?.requestId, responses["prepareLocalMcpDiscovery"]?.toString()) {
        val frozen = McpLocalDiscoveryRules.freeze(preparing, selection, revision, snapshot, responses["prepareLocalMcpDiscovery"], now)
        if (frozen != null && reviewed == null) { reviewed = frozen; heldReviews.add(JSONObject(frozen.body).getString("reviewId")); consent = false }
    }
    LaunchedEffect(confirmRequest, responses["confirmLocalMcpDiscovery"]?.toString(), readRequest, responses["readLocalMcpDiscovery"]?.toString()) {
        val id = operationId ?: return@LaunchedEffect
        val body = McpLocalDiscoveryRules.operation(readRequest, responses["readLocalMcpDiscovery"], id) ?: McpLocalDiscoveryRules.operation(confirmRequest, responses["confirmLocalMcpDiscovery"], id)
        if (body != null) operation = JSONObject(body.toString())
    }
    LaunchedEffect(operationId, operation?.optString("status")) {
        val id = operationId ?: return@LaunchedEffect
        if (operation?.optString("status") in setOf("completed", "failed", "interrupted", "reconciling")) return@LaunchedEffect
        while (true) { delay(1000); if ("readLocalMcpDiscovery" !in pending) readRequest = ask("readLocalMcpDiscovery", JSONObject().put("taskId", id)) }
    }
    DisposableEffect(Unit) { onDispose { heldReviews.toList().forEach { id -> runCatching { request("discardLocalMcpReview", JSONObject().put("reviewId", id)) } }; heldReviews.clear() } }
    BackHandler { back() }
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::back) { Text("返回") }
                Text("本机 MCP 程序发现", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(color = Line)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (operationId != null) "3 · 核对原操作" else if (reviewed != null) "2 · 核对本次程序执行" else "1 · 选择原项目与入口", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                error?.let { Text(it, color = Danger, fontSize = 13.sp) }
                localError?.let { Text(it, color = Danger, fontSize = 13.sp) }
                if (operationId == null && reviewed == null) {
                    McpInstallCaption("只发现工具元数据，尚不安装或调用 stdio 工具。此处会运行本机程序，和只读 HTTP 服务发现不同。先选原项目入口，再明确审阅执行范围。")
                    if (loading || sourceBusy || preparingBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (choices != null && !runtimeReady) McpInstallCaption("原手机开发运行环境尚未就绪，请从原开发工具入口准备；本页不自动下载、换到电脑或降级执行。")
                    Text("原项目副本", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (checkouts.isEmpty() && !sourceBusy) McpInstallCaption("还没有可选的普通项目副本。请先回原手机开发工具准备运行环境与源码；此处不自动下载依赖。")
                    checkouts.forEach { row ->
                        OutlinedButton(onClick = { edit { selectedCheckout = row.getString("checkoutId"); source = null; entry = ""; argv = "[]" }; sourceRequest = ask("describeLocalMcpWorkspace", JSONObject().put("checkoutId", selectedCheckout)) }, enabled = !sourceBusy && !preparingBusy, modifier = Modifier.fillMaxWidth()) { Text((if (selectedCheckout == row.optString("checkoutId")) "已选 · " else "") + row.optString("name", row.optString("checkoutId"))) }
                    }
                    source?.let { visible ->
                        McpInstallCaption("可见源码 ${visible.optInt("fileCount")} 个文件 · ${visible.optString("sourceDigest").take(12)}…\n未读取隐藏配置；执行时另建本次副本，node_modules/.venv 等缓存不会随带。这不是整个原目录版本，也不是安全沙箱。")
                        val entries = McpInstallPreviewRules.objects(visible.optJSONArray("files")).filter { it.optString("path").endsWith(".py") || it.optString("path").endsWith(".js") }
                        Text("程序入口", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        if (entries.isEmpty()) McpInstallCaption("此可见快照没有 .py/.js 入口。请先从原项目文件树核对并准备代码。")
                        if (entries.size > 20) OutlinedTextField(entryFilter, { entryFilter = it }, label = { Text("过滤入口路径") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val shownEntries = entries.filter { it.optString("path").contains(entryFilter, ignoreCase = true) }.take(20)
                        if (shownEntries.size < entries.size) McpInstallCaption("当前显示 ${shownEntries.size}/${entries.size} 个入口；可输入路径过滤，源码快照范围未改变。")
                        shownEntries.forEach { row -> val path = row.getString("path"); TextButton(onClick = { edit { entry = path; executable = if (path.endsWith(".js")) "node" else "python3"; argv = JSONArray().put(path).toString(); name = path.substringAfterLast('/'); pluginId = "local_" + name.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_]"), "_").take(40) } }, enabled = !preparingBusy) { Text((if (entry == path) "已选 · " else "") + path) } }
                        if (entry.isNotBlank()) {
                            McpInstallCaption("$executable · $entry\n明确使用 2025-11-25 协议；不自动降级，不执行 Shell/eval。")
                            TextButton(onClick = { advanced = !advanced }, enabled = !preparingBusy) { Text(if (advanced) "收起公开参数" else "查看或修改公开参数") }
                            if (advanced) {
                                OutlinedTextField(pluginId, { edit { pluginId = it } }, label = { Text("插件 ID（尚不安装）") }, singleLine = true, enabled = !preparingBusy, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(name, { edit { name = it } }, label = { Text("显示名称") }, singleLine = true, enabled = !preparingBusy, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(argv, { edit { argv = it } }, label = { Text("完整 argv · JSON 字符串数组") }, supportingText = { Text("仅公开非秘密参数，首项必须是可见入口；不填写令牌、密码或秘密环境变量。") }, enabled = !preparingBusy, modifier = Modifier.fillMaxWidth())
                            }
                            Button(onClick = { runCatching { val config = selection.configuration(); val id = ask("prepareLocalMcpDiscovery", JSONObject().put("configuration", config)); if (id != null) preparing = McpLocalPreparation(id, config.toString(), revision) }.onFailure { localError = "请核对相对入口、公开 JSON 参数、ID 与当前可见源码版本。" } }, enabled = !sourceBusy && !preparingBusy && !loading && runtimeReady, modifier = Modifier.fillMaxWidth()) { Text("生成本机程序审阅 · 不执行") }
                        }
                    }
                } else if (operationId == null) {
                    val body = current
                    if (body == null) { McpInstallCaption("安装身份、输入或审阅时限已变化。旧审阅不能启动程序，请返回选择重新核对。"); TextButton(onClick = ::discardReview) { Text("返回选择") } }
                    else {
                        McpInstallCaption(body.getString("risk"))
                        Surface(color = Soft, shape = Rounded) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${body.optString("executable")} · ${body.optString("entryPoint")}", fontWeight = FontWeight.Medium, color = Ink)
                            McpInstallCaption("候选：${body.optString("pluginId")} · ${body.optString("name")}\n原副本：${body.optString("checkoutId")}\n安装绑定：${body.optString("expectedInstallationId").ifBlank { "尚未安装" }} · 修订 ${body.opt("expectedStateRevision")}，不会改动该安装。")
                            SelectionContainer { Text("完整 argv\n${body.getJSONArray("argv").toString(2)}", Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), fontSize = 13.sp, color = Ink) }
                            McpInstallCaption("可见源 SHA：${body.optString("sourceDigest")}\n配置摘要：${body.optString("configurationDigest")}\n运行环境：${body.optString("runtimeIdentity")}\n本次副本：${body.optString("executionCopyId")}\n不安装/授权工具，不调用模型；目录结果仅是不可信元数据。")
                        } }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }); McpInstallCaption("已核对以上程序与全部参数，并理解程序可有启动副作用及 App 同 UID 可达能力。") }
                        Button(onClick = { runCatching { val payload = McpLocalDiscoveryRules.confirmPayload(reviewed, selection, revision, snapshot, responses["prepareLocalMcpDiscovery"], now); val accepted = ask("confirmLocalMcpDiscovery", payload); if (accepted != null) { operationId = body.getString("actionId"); heldReviews.remove(body.getString("reviewId")); confirmRequest = accepted } }.onFailure { localError = "旧审阅已失效，未重复确认，请重新读取。" } }, enabled = consent && "confirmLocalMcpDiscovery" !in pendingCommands, modifier = Modifier.fillMaxWidth()) { Text("仅本次执行并发现") }
                        TextButton(onClick = ::discardReview) { Text("不执行，返回选择") }
                    }
                } else {
                    val id = operationId.orEmpty(); val actual = operation; val receipt = actual?.optJSONObject("receipt"); val process = receipt?.optJSONObject("processReceipt")
                    SelectionContainer { McpInstallCaption("原操作：$id\n状态：${actual?.optString("status") ?: "尚待原回执，不重复确认"}") }
                    if (!terminal) McpInstallCaption("返回此页只结束观察，不会把原进程写成成功或重跑。可在原任务列表继续核对；未知结果不能新起实例。")
                    if (actual?.optString("status") == "completed") McpInstallCaption("已取得目录并核对原受控进程会话清场；不等于工具业务成功。stdio 安装与调用仍不可用。")
                    if (actual?.optString("status") == "failed") McpInstallCaption("本次发现未完整成功。若缺依赖，当前副本未带隐藏配置与缓存，请回原项目按明确范围准备；此页不会自动安装或回读全源。")
                    process?.let { McpInstallCaption("原进程退出：${it.optBoolean("processExited")} · 会话释放：${it.optBoolean("processSessionAbsent")} · 资源释放：${it.optBoolean("resourcesReleased")}\n同 UID 不是 OS 沙箱，不把受控进程会话清场解释为所有脱离会话的后代都已消失。") }
                    val tools = McpInstallPreviewRules.objects(receipt?.optJSONObject("metadata")?.optJSONArray("tools"))
                    if (tools.isNotEmpty()) { Text("目录 ${tools.size} 项 · 不可信资料", fontSize = 16.sp, fontWeight = FontWeight.Medium); tools.forEach { tool -> var expanded by remember(tool.toString()) { mutableStateOf(false) }; TextButton(onClick = { expanded = !expanded }) { Text(tool.optString("name")) }; if (expanded) SelectionContainer { Text(tool.toString(2), Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp, color = SecondaryInk) } } }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { readRequest = ask("readLocalMcpDiscovery", JSONObject().put("taskId", id)) }, enabled = "readLocalMcpDiscovery" !in pendingCommands) { Text("读取原操作") }
                        if (!terminal) TextButton(onClick = { ask("cancelLocalMcpDiscovery", JSONObject().put("taskId", id)); readRequest = ask("readLocalMcpDiscovery", JSONObject().put("taskId", id)) }, enabled = "cancelLocalMcpDiscovery" !in pendingCommands) { Text("取消原进程", color = Danger) }
                    }
                }
            }
        }
    }
}
