package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.plugins.McpResourceTemplateData
import java.util.UUID
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

internal data class McpSourceAttempt(val command: String, val request: String, val operation: String, val installation: String, val revision: Long)

internal object McpEcosystemRules {
    fun result(plugin: JSONObject, attempt: McpSourceAttempt?, envelope: JSONObject?): JSONObject? {
        if (attempt == null || plugin.optString("installationId") != attempt.installation || plugin.optLong("stateRevision", -1) != attempt.revision) return null
        val result = McpInstallPreviewRules.result(attempt.request, envelope) ?: return null
        val source = result.optJSONObject("source") ?: return null
        return result.takeIf { source.optString("installationId") == attempt.installation && source.optLong("stateRevision", -1) == attempt.revision && result.optString("operationId") == attempt.operation }
    }
}

/** The original installed-service detail; returned text stays visibly separate from task instructions. */
@Composable
internal fun McpEcosystemControls(plugin: JSONObject, responses: Map<String, JSONObject>, pending: Set<String>, busy: Boolean, request: (String, JSONObject) -> String, evidenceTargets: List<JSONObject> = emptyList(), onOpenEvidenceTask: ((String) -> Unit)? = null) {
    val installation = plugin.optString("installationId")
    val revision = plugin.optLong("stateRevision", -1)
    val definitions = McpInstallPreviewRules.objects(plugin.optJSONObject("manifest")?.optJSONArray("tools"))
    val protocol = plugin.optJSONObject("manifest")?.optJSONObject("mcpSource")?.optString("protocolVersion") ?: definitions.firstOrNull()?.optString("protocolVersion")
    val transport = definitions.firstOrNull()?.optString("transport", "streamable_http")
    if (transport == "stdio") { McpInstallCaption("本机程序的连接诊断见原发现任务；资源、提示和远端异步任务当前仅用于 HTTP 安装。"); return }
    var expanded by remember(installation) { mutableStateOf(false) }
    var attempt by remember(installation) { mutableStateOf<McpSourceAttempt?>(null) }
    var resources by remember(installation, revision) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var templates by remember(installation, revision) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var template by remember(installation, revision) { mutableStateOf<JSONObject?>(null) }
    var templateArguments by remember(installation, revision, template?.optString("descriptorDigest")) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var structuredArguments by remember(installation, revision, template?.optString("descriptorDigest")) { mutableStateOf(false) }
    var argumentsJson by remember(installation, revision, template?.optString("descriptorDigest")) { mutableStateOf("{}") }
    var templatePreview by remember(installation, revision, template?.optString("descriptorDigest")) { mutableStateOf<JSONObject?>(null) }
    var prompts by remember(installation, revision) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var prompt by remember(installation, revision) { mutableStateOf<JSONObject?>(null) }
    var arguments by remember(installation, revision, prompt?.optString("name")) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var reference by remember(installation, revision) { mutableStateOf<JSONObject?>(null) }
    var target by remember(installation, revision, reference?.optString("snapshotId")) { mutableStateOf<JSONObject?>(null) }
    var saved by remember(installation, revision, reference?.optString("snapshotId")) { mutableStateOf<JSONObject?>(null) }
    var choosingTarget by remember(installation, revision, reference?.optString("snapshotId")) { mutableStateOf(false) }
    var notice by remember(installation) { mutableStateOf<String?>(null) }
    val send by rememberUpdatedState(request)
    val owned = remember(installation) { mutableSetOf<String>() }
    DisposableEffect(installation) { onDispose { owned.toList().forEach { send("cancel", JSONObject().put("operationId", it)) }; owned.clear() } }
    val resourceSnapshot = reference?.optString("snapshotId")?.takeIf { it.isNotBlank() }
    DisposableEffect(resourceSnapshot) { onDispose { resourceSnapshot?.let { runCatching { send("discardMcpResourceSnapshot", JSONObject().put("snapshotId", it)) } } } }
    val envelope = attempt?.command?.let(responses::get)
    LaunchedEffect(attempt?.request, envelope?.toString(), revision) {
        val original = attempt ?: return@LaunchedEffect
        if (revision != original.revision || installation != original.installation) {
            send("cancel", JSONObject().put("operationId", original.operation)); owned.remove(original.operation); attempt = null
            notice = "来源版本已变化，已撤回旧目录与正文。请重新读取。"; return@LaunchedEffect
        }
        if (envelope?.optString("requestId") != original.request) return@LaunchedEffect
        owned.remove(original.operation); attempt = null
        if (envelope.opt("ok") != true) { notice = envelope.optString("error", "未取得有效回执；请核对原连接。"); return@LaunchedEffect }
        val actual = McpEcosystemRules.result(plugin, original, envelope)
        if (actual == null) { notice = "旧连接或旧版本回执已失效，请重新读取。"; return@LaunchedEffect }
        when (original.command) {
            "listMcpResources" -> { resources = McpInstallPreviewRules.objects(actual.optJSONArray("resources")); notice = "已取得 ${resources.size} 个资源名称。选择后才读取正文。" }
            "listMcpResourceTemplates" -> { templates = McpInstallPreviewRules.objects(actual.optJSONArray("resourceTemplates")); template = null; templatePreview = null; notice = "已取得 ${templates.size} 个资源模板。选择模板、填写参数并审阅 URI 后才读取正文。" }
            "listMcpPrompts" -> { prompts = McpInstallPreviewRules.objects(actual.optJSONArray("prompts")); notice = "已取得 ${prompts.size} 个提示。参数和返回内容只作为参考。" }
            "getMcpResource", "getMcpPrompt" -> { reference = actual; notice = "已取得当前来源的参考内容。" }
            "saveMcpResource" -> { saved = actual; notice = if (actual.optBoolean("linked")) "已保存为所选原任务的历史来源；没有启动执行或自动加入对话。" else actual.optString("message", "原件已保存，任务关联尚未完成。请核对原文件。") }
            "pingMcp" -> notice = "连接有响应 · ${actual.optLong("roundTripMillis")} ms；这不证明工具业务执行成功。"
            "listenMcpUpdates" -> notice = if (actual.optBoolean("sourceInvalidated")) "服务报告内容已更新，请重新读取目录；旧来源已撤回。" else if (actual.optBoolean("gracefulClose")) "服务已正常结束这次观察。" else "这次观察已结束（${actual.optString("endReason")}）；未自动重连。"
        }
    }
    fun issue(command: String, extra: JSONObject = JSONObject()) {
        if (attempt != null) return
        val operation = UUID.randomUUID().toString()
        val payload = JSONObject(extra.toString()).put("id", plugin.getString("id")).put("expectedInstallationId", installation).put("expectedStateRevision", revision).put("_operationId", operation)
        try { val id = send(command, payload); check(id.isNotBlank()); owned.add(operation); attempt = McpSourceAttempt(command, id, operation, installation, revision); notice = null }
        catch (failure: Exception) { notice = failure.message ?: "宿主未接收请求。" }
    }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起连接与参考内容" else "连接、资源与提示…") }
    if (!expanded) return
    val blocked = busy || attempt != null || pending.any { it in setOf("pingMcp", "listMcpResources", "listMcpResourceTemplates", "getMcpResource", "listMcpPrompts", "getMcpPrompt", "listenMcpUpdates", "saveMcpResource") }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { issue("pingMcp") }, enabled = !blocked) { Text("检查连接") }
            TextButton(onClick = { issue("listMcpResources") }, enabled = !blocked) { Text("资源目录") }
            TextButton(onClick = { issue("listMcpPrompts") }, enabled = !blocked) { Text("提示目录") }
        }
        TextButton(onClick = { issue("listMcpResourceTemplates") }, enabled = !blocked) { Text("按模板查找资源…") }
        if (protocol == McpInstallPreviewRules.CURRENT) {
            TextButton(onClick = { issue("listenMcpUpdates", JSONObject().put("filter", JSONObject().put("toolsListChanged", true).put("resourcesListChanged", true).put("promptsListChanged", true))) }, enabled = !blocked) { Text("观察一次服务更新") }
        }
        attempt?.let { active ->
            Row { CircularProgressIndicator(Modifier.size(16.dp), color = Ink, strokeWidth = 2.dp); TextButton(onClick = { send("cancel", JSONObject().put("operationId", active.operation)); notice = "已请求停止本次操作；已保存的原件仍保留供核对。" }) { Text(if (active.command == "saveMcpResource") "停止本次保存" else "停止本次读取") } }
        }
        notice?.let { McpInstallCaption(it) }
        resources.forEach { resource ->
            TextButton(onClick = { issue("getMcpResource", JSONObject().put("uri", resource.getString("uri")).put("descriptorDigest", resource.getString("descriptorDigest"))) }, enabled = !blocked) { Text(resource.optString("name", resource.optString("uri")), fontSize = 13.sp) }
            if (protocol == McpInstallPreviewRules.CURRENT) TextButton(onClick = { issue("listenMcpUpdates", JSONObject().put("filter", JSONObject().put("resourceSubscriptions", JSONArray().put(resource.getString("uri"))))) }, enabled = !blocked) { Text("观察此资源更新", fontSize = 12.sp) }
        }
        templates.forEach { item ->
            TextButton(onClick = { template = item; templatePreview = null; prompt = null; reference = null; notice = null }, enabled = !blocked && item.optBoolean("supported")) { Text(item.optString("name", item.optString("uriTemplate", "未命名模板")), fontSize = 13.sp) }
            if (!item.optBoolean("supported")) {
                McpInstallCaption(item.optString("uriTemplate"))
                McpInstallCaption(item.optString("unsupportedReason", "此模板暂不支持读取；原声明仍保留。"))
            }
        }
        template?.let { selected ->
            Text(selected.optString("name"), fontWeight = FontWeight.Medium, fontSize = 14.sp)
            McpInstallCaption(selected.optString("uriTemplate"))
            McpInstallCaption("填写值后先查看展开的 URI。留为未提供的参数按模板规则省略；空文字与未提供不同。")
            val variables = selected.optJSONArray("variables") ?: JSONArray()
            if (!structuredArguments) {
                for (i in 0 until variables.length()) {
                    val name = variables.getString(i)
                    OutlinedTextField(value = templateArguments[name].orEmpty(), onValueChange = { templateArguments = templateArguments + (name to it); templatePreview = null; reference = null }, label = { Text(name + if (templateArguments.containsKey(name)) "" else "（未提供）") }, enabled = !blocked, modifier = Modifier.fillMaxWidth())
                    if (templateArguments.containsKey(name)) TextButton(onClick = { templateArguments = templateArguments - name; templatePreview = null; reference = null }, enabled = !blocked) { Text("不提供 $name", fontSize = 12.sp) }
                }
            } else {
                McpInstallCaption("参数对象接受文字、文字列表或文字映射；null 表示未提供。参数名必须属于上面的模板。")
                OutlinedTextField(value = argumentsJson, onValueChange = { argumentsJson = it; templatePreview = null; reference = null }, label = { Text("模板参数 JSON") }, enabled = !blocked, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = {
                if (!structuredArguments) { val values = JSONObject(); templateArguments.forEach { (key, value) -> values.put(key, value) }; argumentsJson = values.toString(2) }
                structuredArguments = !structuredArguments; templatePreview = null; reference = null
            }, enabled = !blocked) { Text(if (structuredArguments) "返回文字参数" else "填写列表或映射参数…", fontSize = 12.sp) }
            TextButton(onClick = {
                try {
                    val values = if (structuredArguments) McpResourceTemplateData.parseArguments(argumentsJson) else JSONObject().also { obj -> templateArguments.forEach { (key, value) -> obj.put(key, value) } }
                    templatePreview = McpResourceTemplateData.preview(selected.getJSONObject("definition"), values); reference = null; notice = "URI 已在本机展开；尚未请求正文。"
                } catch (failure: Exception) { templatePreview = null; notice = failure.message ?: "模板参数无法展开，请检查后再试。" }
            }, enabled = !blocked) { Text("查看将读取的 URI") }
            templatePreview?.let { reviewed ->
                SelectionContainer { Text(reviewed.getString("uri"), fontSize = 13.sp, color = Ink) }
                McpInstallCaption("通过当前已安装服务读取此 URI；不会直接打开网址或手机文件。")
                TextButton(onClick = {
                    val source = reviewed.getJSONObject("resourceTemplate")
                    issue("getMcpResource", JSONObject().put("uri", reviewed.getString("uri")).put("descriptorDigest", reviewed.getString("descriptorDigest"))
                        .put("templateSelection", JSONObject().put("uriTemplate", source.getString("uriTemplate")).put("arguments", source.getJSONObject("arguments"))))
                }, enabled = !blocked) { Text("读取这个资源") }
            }
        }
        prompts.forEach { item -> TextButton(onClick = { prompt = item; template = null; templatePreview = null; reference = null }, enabled = !blocked) { Text(item.optString("name"), fontSize = 13.sp) } }
        prompt?.let { selected ->
            Text(selected.optString("name"), fontWeight = FontWeight.Medium, fontSize = 14.sp)
            val params = McpInstallPreviewRules.objects(selected.optJSONObject("definition")?.optJSONArray("arguments"))
            params.forEach { argument ->
                val name = argument.getString("name")
                OutlinedTextField(value = arguments[name].orEmpty(), onValueChange = { arguments = arguments + (name to it.take(8000)) }, label = { Text(name + if (argument.optBoolean("required")) "（必填）" else "") }, enabled = !blocked, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = {
                val values = JSONObject(); arguments.forEach { (key, value) -> values.put(key, value) }
                issue("getMcpPrompt", JSONObject().put("name", selected.getString("name")).put("descriptorDigest", selected.getString("descriptorDigest")).put("arguments", values))
            }, enabled = !blocked && params.all { !it.optBoolean("required") || arguments[it.optString("name")] != null }) { Text("读取这份提示") }
        }
        reference?.let { content ->
            McpReferenceText(content)
            resourceSnapshot?.let { snapshotId ->
                if (saved == null) TextButton(onClick = { choosingTarget = !choosingTarget }, enabled = !blocked) { Text(if (choosingTarget) "收起保存位置" else "保存为原任务来源…") }
                if (choosingTarget && saved == null) {
                    McpInstallCaption("选择已有私人任务。保存这次实际取得的文本快照，保留块来源与未读取内容说明；不会创建任务或增加工具权限。")
                    if (evidenceTargets.isEmpty()) McpInstallCaption("没有已有执行记录的私人任务。请返回原任务后再选择保存位置。")
                    evidenceTargets.forEach { choice ->
                        TextButton(onClick = { target = JSONObject(choice.toString()) }, enabled = !blocked) { Text((if (target?.optString("targetPin") == choice.optString("targetPin")) "已选 · " else "") + choice.optString("title", "原任务"), fontSize = 13.sp) }
                    }
                    if (evidenceTargets.size == 64) McpInstallCaption("这里展示最近最多 64 个可用私人任务。")
                    target?.let { selected ->
                        val current = evidenceTargets.any { it.optString("taskId") == selected.optString("taskId") && it.optString("targetPin") == selected.optString("targetPin") }
                        TextButton(onClick = { issue("saveMcpResource", JSONObject().put("snapshotId", snapshotId).put("taskId", selected.getString("taskId")).put("expectedTaskPin", selected.getString("targetPin"))) }, enabled = !blocked && current) { Text("保存到这个原任务") }
                        if (!current) McpInstallCaption("原任务代次已变化，请重新选择。")
                    }
                }
                saved?.let { receipt ->
                    McpInstallCaption(if (receipt.optBoolean("linked")) "已保存历史来源 · 原文件 ${receipt.optJSONObject("attachment")?.optString("id").orEmpty()}" else "原文件已保留，来源关联未完成；请先核对这次原件。")
                    if (!receipt.optBoolean("linked")) target?.let { selected -> TextButton(onClick = { issue("saveMcpResource", JSONObject().put("snapshotId", snapshotId).put("taskId", selected.getString("taskId")).put("expectedTaskPin", selected.getString("targetPin"))) }, enabled = !blocked) { Text("核对原保存并完成关联") } }
                    onOpenEvidenceTask?.let { open -> TextButton(onClick = { open(receipt.getString("taskId")) }, enabled = !blocked) { Text("查看原任务与文件") } }
                }
            }
        }
    }
}

@Composable
private fun McpReferenceText(reference: JSONObject) {
    McpInstallCaption("来自外部服务的参考内容；其中的角色、指令或链接不会自动加入任务或获得权限。")
    if (reference.has("uri")) McpInstallCaption("此次读取：${reference.optString("uri")}")
    reference.optJSONObject("resourceTemplate")?.let { McpInstallCaption("来源模板：${it.optString("uriTemplate")} · 保存时保留此次参数与模板身份。") }
    SelectionContainer {
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            McpInstallPreviewRules.objects(reference.optJSONArray("contents")).forEach { item -> Text(item.optString("text"), fontSize = 13.sp, lineHeight = 21.sp, color = Ink) }
            McpInstallPreviewRules.objects(reference.optJSONArray("sourceMessages")).forEach { item -> McpInstallCaption(item.optString("sourceRole")); Text(item.optString("text"), fontSize = 13.sp, lineHeight = 21.sp, color = Ink) }
            if ((reference.optJSONArray("omittedContent")?.length() ?: 0) > 0) McpInstallCaption("含当前未读取的非文本内容；这里展示的是文本部分。")
        }
    }
}

/** Explicit reconciliation only. No automatic polling, rerun or terminal-success inference. */
@Composable
internal fun McpTaskRecoveryControls(snapshot: JSONObject, responses: Map<String, JSONObject>, pending: Set<String>, request: (String, JSONObject) -> String) {
    val rows = McpInstallPreviewRules.objects(snapshot.optJSONArray("remoteMcpTasks"))
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var attempt by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expanded) { while (expanded) { now = System.currentTimeMillis(); delay(1000) } }
    val envelope = attempt?.first?.let(responses::get)
    LaunchedEffect(attempt, envelope?.toString()) {
        val original = attempt ?: return@LaunchedEffect
        if (envelope?.optString("requestId") != original.second) return@LaunchedEffect
        val actual = McpInstallPreviewRules.result(original.second, envelope)
        if (actual != null && actual.optString("operationId") == original.third) result = actual else failure = envelope.optString("error", "未取得原任务有效回执。")
        attempt = null
    }
    fun issue(command: String, row: JSONObject) {
        try {
            val operation = row.getString("operationId")
            val id = request(command, JSONObject().put("taskId", row.getString("taskId")).put("operationId", operation).put("_operationId", UUID.randomUUID().toString()))
            check(id.isNotBlank()); attempt = Triple(command, id, operation); failure = null; result = null
        } catch (error: Exception) { failure = error.message }
    }
    TextButton(onClick = { expanded = !expanded }) { Text("${if (expanded) "收起" else "核对"}远端执行 · ${rows.count { !it.optBoolean("resolved") }} 项待核对") }
    if (!expanded) return
    val busy = attempt != null || pending.any { it == "readMcpTask" || it == "cancelMcpTask" }
    McpInstallCaption("这里仅查询或取消已经登记的原任务。断线、停止等待或取消确认都不代表业务成功，也不会重新发送工具调用。")
    if (rows.isEmpty()) McpInstallCaption("没有已登记的远端异步执行。")
    rows.forEach { row ->
        Text(row.optString("nativeTool"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        McpInstallCaption("状态：${row.optString("remoteStatus")} · ${if (row.optBoolean("resolved")) "已取得终态回执" else "效果待核对"}\n原任务：${row.optString("taskId")}")
        Row {
            TextButton(onClick = { issue("readMcpTask", row) }, enabled = !busy && (row.optBoolean("resolved") || now >= row.optLong("nextPollAt"))) { Text("读取原回执") }
            TextButton(onClick = { selected = JSONObject(row.toString()) }, enabled = !busy && !row.optBoolean("terminal") && !row.optBoolean("cancelRequested")) { Text("请求取消…") }
        }
    }
    if (rows.size == 64) McpInstallCaption("这里只展示最近 64 项，优先展示未决操作；更早的回执仍保留在原任务中。")
    failure?.let { Text(it, color = Danger, fontSize = 13.sp) }
    result?.let { receipt ->
        val remote = receipt.optJSONObject("remoteMcp")?.optJSONObject("receipt")
        McpInstallCaption("原操作：${receipt.optString("operationId")}\n当前回执：${remote?.optString("status")}\n${if (remote?.optBoolean("cancelAcknowledged") == true) "服务已确认收到取消请求；最终状态以原回执为准。" else remote?.optString("message").orEmpty()}")
        val output = remote?.optJSONObject("toolResult")
        output?.let { SelectionContainer { Text(it.toString(2), color = SecondaryInk, fontSize = 12.sp, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) } }
        receipt.optJSONObject("outputCapture")?.let { McpInstallCaption("长结果保存状态：${it.optString("status", it.optString("reason", "请查看原任务来源回执"))}") }
    }
    selected?.let { frozen ->
        AlertDialog(onDismissRequest = { selected = null }, containerColor = Color.White, title = { Text("取消这个远端执行？") }, text = { Text("只向这个原任务发送一次取消请求。服务可能已经产生效果，仍需随后查询终态。\n原任务：${frozen.optString("taskId")}") }, confirmButton = { TextButton(onClick = { issue("cancelMcpTask", frozen); selected = null }, enabled = !busy) { Text("请求取消") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("返回") } })
    }
}
