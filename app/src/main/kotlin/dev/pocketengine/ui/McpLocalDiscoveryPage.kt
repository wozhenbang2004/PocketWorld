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
    error: String?, onRequest: (String, JSONObject) -> String, onBack: () -> Unit, onManageInstalled: () -> Unit
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
    var dependencyOptions by remember { mutableStateOf(false) }
    var packageManager by remember { mutableStateOf("") }
    var packageManagerVersion by remember { mutableStateOf("") }
    var preparationApprovalRequest by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableLongStateOf(0) }
    var preparing by remember { mutableStateOf<McpLocalPreparation?>(null) }
    var reviewed by remember { mutableStateOf<McpLocalReviewed?>(null) }
    var consent by remember { mutableStateOf(false) }
    var operationId by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<JSONObject?>(null) }
    var readRequest by remember { mutableStateOf<String?>(null) }
    var diagnosticRequest by remember { mutableStateOf<String?>(null) }
    var diagnosticCursor by remember { mutableLongStateOf(0) }
    var diagnosticPage by remember { mutableStateOf<JSONObject?>(null) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var confirmRequest by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var installationOpen by remember { mutableStateOf(false) }
    var recoverTask by remember { mutableStateOf("") }
    var recoverOpen by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val heldReviews = remember { mutableSetOf<String>() }
    if (installationOpen && operationId != null) {
        McpLocalInstallationPage(operationId!!, snapshot, responses, pendingCommands, error, onRequest, onBack, onManageInstalled)
        return
    }
    val selection = McpLocalSelection(pluginId.trim(), name.trim(), selectedCheckout, source?.optString("sourceDigest").orEmpty(), executable, argv, packageManager, packageManagerVersion.trim())
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
        reviewed = null; preparing = null; consent = false; preparationApprovalRequest = null
    }
    fun edit(action: () -> Unit) { discardReview(); revision++; localError = null; action() }
    fun back() { if (operationId == null) discardReview(); onBack() }
    fun readDiagnostic(cursor: Long) {
        val id = operationId ?: return
        val accepted = ask("readLocalMcpDiagnostic", JSONObject().put("taskId", id).put("cursor", cursor).put("maxBytes", 16384))
        if (accepted != null) { diagnosticCursor = cursor; diagnosticRequest = accepted }
    }
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
    LaunchedEffect(operationId, diagnosticRequest, responses["readLocalMcpDiagnostic"]?.toString()) {
        val id = operationId ?: return@LaunchedEffect
        val page = McpLocalDiscoveryRules.diagnostic(diagnosticRequest, responses["readLocalMcpDiagnostic"], id, diagnosticCursor)
        if (page != null) diagnosticPage = JSONObject(page.toString())
    }
    LaunchedEffect(operationId, operation?.optString("status")) {
        val id = operationId ?: return@LaunchedEffect
        if (operation?.optString("status") in setOf("completed", "failed", "interrupted", "reconciling")) return@LaunchedEffect
        while (true) { delay(1000); if ("readLocalMcpDiscovery" !in pending) readRequest = ask("readLocalMcpDiscovery", JSONObject().put("taskId", id)) }
    }
    DisposableEffect(Unit) { onDispose { heldReviews.toList().forEach { id -> runCatching { request("discardLocalMcpReview", JSONObject().put("reviewId", id)) } }; heldReviews.clear() } }
    BackHandler { back() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
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
                    McpInstallCaption("先发现工具元数据，完整核对进程清场后另行选择工具声明安装；实际调用仍走原任务审批。此处会运行本机程序，和只读 HTTP 服务发现不同。先选原项目入口，再明确审阅执行范围。")
                    if (loading || sourceBusy || preparingBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (choices != null && !runtimeReady) McpInstallCaption("原手机开发运行环境尚未就绪，请从原开发工具入口准备；本页不自动下载、换到电脑或降级执行。")
                    TextButton(onClick = { recoverOpen = !recoverOpen }, enabled = !preparingBusy) { Text(if (recoverOpen) "收起原操作读取" else "继续核对已有发现操作") }
                    if (recoverOpen) {
                        OutlinedTextField(recoverTask, { recoverTask = it.trim() }, label = { Text("原发现任务 ID") }, supportingText = { Text("可从原任务详情复制；只读取同一个操作，不重新启动或借普通任务安装。") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { discardReview(); operationId = recoverTask; readRequest = ask("readLocalMcpDiscovery", JSONObject().put("taskId", recoverTask)) }, enabled = recoverTask.matches(Regex("[a-f0-9-]{36}")) && !preparingBusy) { Text("只读取原操作") }
                    }
                    Text("原项目副本", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (checkouts.isEmpty() && !sourceBusy) McpInstallCaption("还没有可选的普通项目副本。请先回原手机开发工具准备运行环境与源码；此处不自动下载依赖。")
                    checkouts.forEach { row ->
                        OutlinedButton(onClick = { edit { selectedCheckout = row.getString("checkoutId"); source = null; entry = ""; argv = "[]"; packageManager = ""; packageManagerVersion = "" }; sourceRequest = ask("describeLocalMcpWorkspace", JSONObject().put("checkoutId", selectedCheckout)) }, enabled = !sourceBusy && !preparingBusy, modifier = Modifier.fillMaxWidth()) { Text((if (selectedCheckout == row.optString("checkoutId")) "已选 · " else "") + row.optString("name", row.optString("checkoutId"))) }
                    }
                    source?.let { visible ->
                        McpInstallCaption("可见源码 ${visible.optInt("fileCount")} 个文件 · ${visible.optString("sourceDigest").take(12)}…\n未读取隐藏配置；执行时另建本次副本，node_modules/.venv 等缓存不会随带。这不是整个原目录版本，也不是安全沙箱。")
                        val entries = McpInstallPreviewRules.objects(visible.optJSONArray("files")).filter { row -> listOf(".py", ".js", ".mjs", ".cjs").any { row.optString("path").endsWith(it) } }
                        Text("程序入口", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        if (entries.isEmpty()) McpInstallCaption("此可见快照没有 .py/.js/.mjs/.cjs 入口。请先从原项目文件树核对并准备代码。")
                        if (entries.size > 20) OutlinedTextField(entryFilter, { entryFilter = it }, label = { Text("过滤入口路径") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val shownEntries = entries.filter { it.optString("path").contains(entryFilter, ignoreCase = true) }.take(20)
                        if (shownEntries.size < entries.size) McpInstallCaption("当前显示 ${shownEntries.size}/${entries.size} 个入口；可输入路径过滤，源码快照范围未改变。")
                        shownEntries.forEach { row -> val path = row.getString("path"); TextButton(onClick = { edit { entry = path; executable = if (path.endsWith(".py")) "python3" else "node"; packageManager = ""; packageManagerVersion = ""; argv = JSONArray().put(path).toString(); name = path.substringAfterLast('/'); pluginId = "local_" + name.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_]"), "_").take(40) } }, enabled = !preparingBusy) { Text((if (entry == path) "已选 · " else "") + path) } }
                        if (entry.isNotBlank()) {
                            McpInstallCaption("$executable · $entry\n明确使用 2025-11-25 协议；不自动降级，不执行 Shell/eval。")
                            TextButton(onClick = { advanced = !advanced }, enabled = !preparingBusy) { Text(if (advanced) "收起公开参数" else "查看或修改公开参数") }
                            if (advanced) {
                                OutlinedTextField(pluginId, { edit { pluginId = it } }, label = { Text("插件 ID（尚不安装）") }, singleLine = true, enabled = !preparingBusy, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(name, { edit { name = it } }, label = { Text("显示名称") }, singleLine = true, enabled = !preparingBusy, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(argv, { edit { argv = it } }, label = { Text("完整 argv · JSON 字符串数组") }, supportingText = { Text("仅公开非秘密参数，首项必须是可见入口；不填写令牌、密码或秘密环境变量。") }, enabled = !preparingBusy, modifier = Modifier.fillMaxWidth())
                            }
                            if (executable == "node") {
                                TextButton(onClick = { dependencyOptions = !dependencyOptions }, enabled = !preparingBusy) { Text(if (dependencyOptions) "收起依赖准备" else if (packageManager.isBlank()) "固定依赖准备 · 可选" else "固定依赖 · $packageManager $packageManagerVersion") }
                                if (dependencyOptions) {
                                    McpInstallCaption("使用当前可见源码中的 package.json 与固定锁文件，在同一次托管操作副本内准备依赖。必须另行批准；不会下载包管理器，不运行安装脚本，也不带入隐藏配置、账号令牌或旧缓存。")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("", "npm", "pnpm").forEach { manager ->
                                            OutlinedButton(onClick = { edit { packageManager = manager; packageManagerVersion = if (manager.isBlank()) "" else McpInstallPreviewRules.objects(choices?.optJSONObject("runtime")?.optJSONArray("tools")).find { it.optString("name") == manager }?.let { it.optString("observedVersion", it.optString("version")).lineSequence().firstOrNull().orEmpty() }.orEmpty() } }, enabled = !preparingBusy) { Text((if (manager == packageManager) "✓ " else "") + manager.ifBlank { "不准备" }) }
                                        }
                                    }
                                    if (packageManager.isNotBlank()) OutlinedTextField(packageManagerVersion, { edit { packageManagerVersion = it } }, label = { Text("已安装 $packageManager 的准确版本") }, supportingText = { Text("package.json 的 packageManager 必须完全一致；支持 npm 11 / pnpm 10，未安装的管理器请先从原开发环境入口核对。") }, singleLine = true, enabled = !preparingBusy, modifier = Modifier.fillMaxWidth())
                                }
                            }
                            Button(onClick = { runCatching { val config = selection.configuration(); val id = ask("prepareLocalMcpDiscovery", JSONObject().put("configuration", config)); if (id != null) preparing = McpLocalPreparation(id, config.toString(), revision) }.onFailure { localError = "请核对相对入口、公开 JSON 参数、ID 与当前可见源码版本。" } }, enabled = !sourceBusy && !preparingBusy && !loading && runtimeReady, modifier = Modifier.fillMaxWidth()) { Text("生成本机程序审阅 · 不执行") }
                        }
                    }
                } else if (operationId == null) {
                    val body = current
                    if (body == null) { McpInstallCaption("安装身份、输入或审阅时限已变化。旧审阅不能启动程序，请返回选择重新核对。"); TextButton(onClick = ::discardReview) { Text("返回选择") } }
                    else {
                        val dependencyPlan = body.optJSONObject("packagePreparation")
                        val dependencyApproved = McpLocalDiscoveryRules.preparationApproved(body, preparationApprovalRequest, responses["approveLocalMcpPreparation"])
                        McpInstallCaption(body.getString("risk"))
                        Surface(color = Soft, shape = Rounded) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${body.optString("executable")} · ${body.optString("entryPoint")}", fontWeight = FontWeight.Medium, color = Ink)
                            McpInstallCaption("候选：${body.optString("pluginId")} · ${body.optString("name")}\n原副本：${body.optString("checkoutId")}\n安装绑定：${body.optString("expectedInstallationId").ifBlank { "尚未安装" }} · 修订 ${body.opt("expectedStateRevision")}，不会改动该安装。")
                            SelectionContainer { Text("完整 argv\n${body.getJSONArray("argv").toString(2)}", Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), fontSize = 13.sp, color = Ink) }
                            McpInstallCaption("可见源 SHA：${body.optString("sourceDigest")}\n配置摘要：${body.optString("configurationDigest")}\n运行环境：${body.optString("runtimeIdentity")}\n本次副本：${body.optString("executionCopyId")}\n不安装/授权工具，不调用模型；目录结果仅是不可信元数据。")
                        } }
                        dependencyPlan?.let { plan ->
                            Surface(color = Soft, shape = Rounded) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("固定依赖准备", fontWeight = FontWeight.Medium, color = Ink)
                                McpInstallCaption("${plan.optString("manager")} ${plan.optString("managerVersion")} · ${plan.optInt("lockedPackageCount")} 个锁定包\n来源：${plan.optString("registry")}\n锁文件：${plan.optString("lockfilePath")}\n锁文件 SHA：${plan.optString("lockfileSha256")}\n管理器 SHA：${plan.optString("managerSha256")}")
                                McpInstallCaption("准备可能联网，限时 ${plan.optInt("preparationTimeoutMillis") / 1000} 秒；安装脚本关闭、版本冻结，每个原操作使用自己的副本和缓存。退出码正常后才启动上述程序；失败不重跑，进程结束不代表工具业务成功。")
                                OutlinedButton(onClick = { preparationApprovalRequest = ask("approveLocalMcpPreparation", JSONObject().put("reviewId", body.getString("reviewId")).put("preparationDigest", body.getString("preparationDigest"))) }, enabled = !dependencyApproved && preparationApprovalRequest == null && "approveLocalMcpPreparation" !in pendingCommands, modifier = Modifier.fillMaxWidth()) { Text(if (dependencyApproved) "本次固定依赖准备已批准" else if (preparationApprovalRequest != null) "等待本次依赖批准回执" else "单独批准本次固定依赖准备") }
                                McpInstallCaption("此按钮只批准以上准备范围，尚未启动程序；下方执行确认仍单独生效。")
                            } }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }); McpInstallCaption("已核对以上程序与全部参数，并理解程序可有启动副作用及 App 同 UID 可达能力。") }
                        Button(onClick = { runCatching { val payload = McpLocalDiscoveryRules.confirmPayload(reviewed, selection, revision, snapshot, responses["prepareLocalMcpDiscovery"], now); val accepted = ask("confirmLocalMcpDiscovery", payload); if (accepted != null) { operationId = body.getString("actionId"); heldReviews.remove(body.getString("reviewId")); confirmRequest = accepted } }.onFailure { localError = "旧审阅已失效，未重复确认，请重新读取。" } }, enabled = consent && dependencyApproved && "confirmLocalMcpDiscovery" !in pendingCommands, modifier = Modifier.fillMaxWidth()) { Text("仅本次执行并发现") }
                        TextButton(onClick = ::discardReview) { Text("不执行，返回选择") }
                    }
                } else {
                    val id = operationId.orEmpty(); val actual = operation; val receipt = actual?.optJSONObject("receipt"); val process = receipt?.optJSONObject("processReceipt")
                    SelectionContainer { McpInstallCaption("原操作：$id\n状态：${actual?.optString("status") ?: "尚待原回执，不重复确认"}") }
                    if (!terminal) McpInstallCaption("返回此页只结束观察，不会把原进程写成成功或重跑。可在原任务列表继续核对；未知结果不能新起实例。")
                    if (actual?.optString("status") == "completed") McpInstallCaption("已取得目录并核对原受控进程会话清场；不等于工具业务成功。可另行选择工具声明安装，默认停用与零授权；此发现没有授权任何工具调用。")
                    if (actual?.optString("status") == "completed" && snapshot.optJSONObject("nativeCapabilities")?.opt("localMcpInstallationOwnerOnly") == true) Button(onClick = { installationOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("选择工具声明 · 另行审阅安装") }
                    if (actual?.optString("status") == "failed") McpInstallCaption("本次发现未完整成功。请核对原操作诊断；依赖准备与协议失败均不会自动重跑。当前副本未带隐藏配置与缓存，需要修改来源或锁文件时先回原项目准备，再明确审阅新的操作。")
                    process?.let { McpInstallCaption("原进程退出：${it.optBoolean("processExited")} · 会话释放：${it.optBoolean("processSessionAbsent")} · 资源释放：${it.optBoolean("resourcesReleased")}\n同 UID 不是 OS 沙箱，不把受控进程会话清场解释为所有脱离会话的后代都已消失。") }
                    TextButton(onClick = { diagnosticsOpen = !diagnosticsOpen; if (diagnosticsOpen && diagnosticPage == null && "readLocalMcpDiagnostic" !in pendingCommands) readDiagnostic(0) }) { Text(if (diagnosticsOpen) "收起原进程诊断" else "查看原进程诊断") }
                    if (diagnosticsOpen) {
                        val diagnostic = diagnosticPage
                        val diagnosticBusy = "readLocalMcpDiagnostic" in pendingCommands
                        val text = remember(diagnostic) { diagnostic?.let { runCatching { McpLocalDiscoveryRules.diagnosticText(it) }.getOrElse { "此日志页的原始字节无法核对，请重新读取原操作。" } } }
                        Surface(color = Soft, shape = Rounded) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            McpInstallCaption("只显示这次原进程的 stderr，依赖准备输出也在其中；内容是不可信诊断，不会发给模型，也不代表业务成功。读取不会重跑程序。")
                            if (diagnosticBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            SelectionContainer { Text(text ?: "尚待原进程日志页。", Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp, color = SecondaryInk) }
                            if (diagnostic?.optBoolean("outputTruncated") == true) McpInstallCaption("原进程日志已达记录上限，部分输出没有保留。")
                            if (diagnostic?.optBoolean("logIncompleteTail") == true) McpInstallCaption("只显示原日志中完整的记录；尾部尚不完整，未据此推断进程状态。")
                            McpInstallCaption("按原日志游标分页，每页最多 16 KiB。UTF-8 跨页字符可能显示为替代字符；协议 stdout 不在本区展示。")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { readDiagnostic(diagnostic?.optLong("nextCursor", 0) ?: 0) }, enabled = !diagnosticBusy) { Text(if (diagnostic?.optBoolean("hasMore") == true) "读取下一页" else "读取后续日志") }
                                if ((diagnostic?.optLong("cursor", 0) ?: 0) > 0) TextButton(onClick = { readDiagnostic(0) }, enabled = !diagnosticBusy) { Text("从头读取") }
                            }
                        } }
                    }
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
