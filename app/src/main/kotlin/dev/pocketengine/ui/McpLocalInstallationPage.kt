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

/** Separate installation consent, after original discovery/cleanup, on the existing MCP surface. */
@Composable internal fun McpLocalInstallationPage(
    taskId: String, snapshot: JSONObject, responses: Map<String, JSONObject>, pendingCommands: Set<String>,
    error: String?, onRequest: (String, JSONObject) -> String, onBack: () -> Unit, onManageInstalled: () -> Unit
) {
    val request by rememberUpdatedState(onRequest)
    var catalogueRequest by remember(taskId) { mutableStateOf<String?>(null) }
    var selected by remember(taskId) { mutableStateOf(emptySet<String>()) }
    var revision by remember(taskId) { mutableLongStateOf(0) }
    var preparing by remember(taskId) { mutableStateOf<McpLocalInstallPreparation?>(null) }
    var reviewed by remember(taskId) { mutableStateOf<McpLocalInstallReviewed?>(null) }
    var consent by remember(taskId) { mutableStateOf(false) }
    var attempt by remember(taskId) { mutableStateOf<McpLocalInstallAttempt?>(null) }
    var localError by remember(taskId) { mutableStateOf<String?>(null) }
    var exitConfirm by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val heldReviews = remember { mutableSetOf<String>() }
    val catalogue = McpLocalInstallationRules.catalogue(catalogueRequest, taskId, responses["readLocalMcpInstallCatalogue"])
    val current = McpLocalInstallationRules.current(reviewed, catalogue, selected, revision, snapshot, responses["prepareLocalMcpInstall"], now)
    val installed = McpLocalInstallationRules.installed(attempt, responses["confirmLocalMcpInstall"])
    val reading = "readLocalMcpInstallCatalogue" in pendingCommands
    val preparingBusy = "prepareLocalMcpInstall" in pendingCommands
    val installing = "confirmLocalMcpInstall" in pendingCommands
    fun ask(command: String, payload: JSONObject): String? = try { localError = null; request(command, payload) } catch (_: Exception) { localError = "宿主尚未接收此请求，请核对原安装记录，不重复确认。"; null }
    fun discard() {
        reviewed?.let { val id = JSONObject(it.body).optString("reviewId"); if (heldReviews.remove(id)) ask("discardLocalMcpInstallReview", JSONObject().put("reviewId", id)) }
        preparing = null; reviewed = null; consent = false
    }
    fun back() { if (installing) exitConfirm = true else { discard(); onBack() } }
    LaunchedEffect(taskId) { catalogueRequest = ask("readLocalMcpInstallCatalogue", JSONObject().put("taskId", taskId)) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    LaunchedEffect(preparing?.requestId, responses["prepareLocalMcpInstall"]?.toString()) {
        val frozen = McpLocalInstallationRules.freeze(preparing, catalogue, selected, revision, snapshot, responses["prepareLocalMcpInstall"], now)
        if (frozen != null && reviewed == null) { reviewed = frozen; heldReviews.add(JSONObject(frozen.body).getString("reviewId")); consent = false }
        else if (frozen == null) {
            val stale = McpInstallPreviewRules.result(preparing?.requestId, responses["prepareLocalMcpInstall"])
            stale?.optString("reviewId")?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }?.let { id ->
                heldReviews.remove(id); ask("discardLocalMcpInstallReview", JSONObject().put("reviewId", id)); reviewed = null; preparing = null; consent = false
                localError = "迟到的安装审阅已因目标、工具或时限变化而关闭；没有安装，请重新核对。"
            }
        }
    }
    DisposableEffect(Unit) { onDispose { heldReviews.toList().forEach { id -> runCatching { request("discardLocalMcpInstallReview", JSONObject().put("reviewId", id)) } }; heldReviews.clear() } }
    BackHandler { back() }
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::back) { Text("返回") }
                Text("安装本机 MCP 工具声明", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            }
            HorizontalDivider(color = Line)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (attempt != null) "3 · 核对安装结果" else if (reviewed != null) "2 · 审阅独立安装" else "1 · 选择工具声明", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                error?.let { Text(it, color = Danger, fontSize = 13.sp) }
                localError?.let { Text(it, color = Danger, fontSize = 13.sp) }
                if (reading || preparingBusy || installing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (attempt != null) {
                    if (installed != null) {
                        Text("已安装，保持停用与零工具授权", fontWeight = FontWeight.Medium, color = Ink)
                        McpInstallCaption("新安装身份：${installed.optString("installationId")}\n仅保存明确选择的声明，没有运行程序。接下来回原 MCP 管理分别启用、允许具体工具；实际调用仍由原任务逐次核对程序、源码与参数审批。")
                    } else McpInstallCaption(if (installing) "正在等待原安装回执。离开不会授权重试，请核对原已安装列表。" else "未取得匹配安装回执，可能尚未安装或已安装但回执未到；不重复确认，请检查原已安装列表。")
                    Button(onClick = onManageInstalled, enabled = !installing, modifier = Modifier.fillMaxWidth()) { Text("回原 MCP 管理核对安装与权限") }
                } else if (reviewed != null) {
                    val view = current
                    if (view == null) {
                        McpInstallCaption("安装目标、所选工具或审阅时限已变化。旧审阅不可安装；如原安装被替换，请重新发现，不覆盖新版本。")
                        TextButton(onClick = ::discard) { Text("关闭旧审阅，返回选择") }
                    } else {
                        McpInstallCaption(view.getString("message"))
                        Surface(color = Soft, shape = Rounded) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${view.optString("name")} · ${view.optString("id")}", fontWeight = FontWeight.Medium, color = Ink)
                            McpInstallCaption("入口：${view.optString("entryPoint")}\n来自原发现：${view.optString("discoveryTaskId")}\n目标：${view.optString("expectedInstallationId").ifBlank { "当前未安装" }} · 修订 ${view.opt("expectedStateRevision")}\n原源版本：${view.optString("sourceDigest")}\n配置摘要：${view.optString("configurationDigest")}")
                            Text("明确选择 ${selected.size} 个工具：${selected.sorted().joinToString("、")}", fontSize = 13.sp, color = Ink)
                            var details by remember(view.optString("reviewId")) { mutableStateOf(false) }
                            TextButton(onClick = { details = !details }) { Text(if (details) "收起原声明" else "查看原声明 · 不可信资料") }
                            if (details) SelectionContainer { Text(view.getJSONObject("manifest").toString(2), Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp, color = SecondaryInk) }
                        } }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }); McpInstallCaption("仅安装以上工具声明；不启动程序、不启用或授予工具权限。") }
                        Button(onClick = { runCatching { val payload = McpLocalInstallationRules.confirmationPayload(reviewed, catalogue, selected, revision, snapshot, responses["prepareLocalMcpInstall"], now); val id = ask("confirmLocalMcpInstall", payload); if (id != null) { attempt = McpLocalInstallAttempt(id, reviewed!!); heldReviews.remove(view.getString("reviewId")) } }.onFailure { localError = "原安装审阅已失效，未重复提交，请重新核对。" } }, enabled = consent && !installing, modifier = Modifier.fillMaxWidth()) { Text("仅安装声明 · 保持停用无授权") }
                        TextButton(onClick = ::discard) { Text("不安装，返回工具选择") }
                    }
                } else {
                    McpInstallCaption("原发现已核对受控进程清场。这里只读取原元数据；远端名称、说明与 schema 均不可信，不会注入对话或授予权限。")
                    if (catalogue == null && !reading) {
                        McpInstallCaption("未得到此原任务的可安装目录。仅 completed 且清场证据完整的原发现可继续，普通任务、失败或未知不能替代。")
                        OutlinedButton(onClick = { catalogueRequest = ask("readLocalMcpInstallCatalogue", JSONObject().put("taskId", taskId)) }) { Text("重新读取原目录 · 不运行") }
                    }
                    catalogue?.let { frozen ->
                        val view = JSONObject(frozen.body)
                        val targetCurrent = McpLocalInstallationRules.targetCurrent(view, snapshot)
                        McpInstallCaption("${view.optString("name")} · ${view.optString("entryPoint")}\n已选择 ${selected.size}/${view.optInt("maxImportedTools")} · 原目录最多可选择这些支持项，不会静默截断。")
                        if (!targetCurrent) McpInstallCaption("原安装目标已变化，不能用旧发现覆盖新安装；请回原发现入口重新核对。")
                        McpInstallPreviewRules.objects(view.optJSONArray("tools")).forEach { tool ->
                            val name = tool.getString("name"); val supported = tool.opt("supported") == true
                            var expanded by remember(frozen.digest, name) { mutableStateOf(false) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(name in selected, { checked -> revision++; discard(); selected = if (checked) selected + name else selected - name }, enabled = supported && !preparingBusy && targetCurrent && (name in selected || selected.size < view.getInt("maxImportedTools")))
                                Column(Modifier.weight(1f)) { Text(name, fontSize = 14.sp, color = Ink); if (!supported) Text(tool.optString("unsupportedReason"), fontSize = 12.sp, color = SecondaryInk) }
                                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "详情") }
                            }
                            if (expanded) SelectionContainer { Text(tool.toString(2), Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp, color = SecondaryInk) }
                        }
                        Button(onClick = { runCatching { val payload = McpLocalInstallationRules.preparationPayload(frozen, selected, snapshot); val id = ask("prepareLocalMcpInstall", payload); if (id != null) preparing = McpLocalInstallPreparation(id, frozen.digest, payload.getJSONArray("tools").toString(), revision) }.onFailure { localError = "请只选择原目录支持的工具，并核对当前安装身份与导入上限。" } }, enabled = targetCurrent && selected.isNotEmpty() && !preparingBusy && snapshot.optJSONObject("nativeCapabilities")?.opt("localMcpInstallationOwnerOnly") == true, modifier = Modifier.fillMaxWidth()) { Text("生成独立安装审阅 · 不执行") }
                    }
                }
            }
        }
    }
    if (exitConfirm) AlertDialog(onDismissRequest = { exitConfirm = false }, containerColor = Color.White, title = { Text("安装回执尚未返回") }, text = { Text("现在离开只结束页面观察，不能认定未安装或自动重试。请回原 MCP 管理核对真实安装身份与权限。") }, confirmButton = { TextButton(onClick = { exitConfirm = false; onManageInstalled() }) { Text("离开并核对原安装") } }, dismissButton = { TextButton(onClick = { exitConfirm = false }) { Text("继续等待") } })
}
