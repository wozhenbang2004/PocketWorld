package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.store.TaskStore
import org.json.JSONObject

/** Owner preparation is not submission. A changed graph, text or route cannot reuse its approval. */
internal object MediaOwnerUi {
    fun isTask(task: JSONObject): Boolean = task.optJSONObject("nativeEventTypeFlags")?.optBoolean("media_owner_prepared") == true || task.optJSONArray("events").objects().any { it.optString("type") == "media_owner_prepared" } || task.optString("feature")=="media" && operation(task) != null
    fun operation(task: JSONObject): JSONObject? = task.optJSONArray("operations").objects().singleOrNull { it.optString("tool") in setOf("media_workflow", "media_generate_image", "media_generate_video", "media_edit_image", "media_synthesize_speech") }
    fun prepared(receipt: JSONObject?, metadata: JSONObject?, requestId: String?, command: String, arguments: JSONObject): JSONObject? {
        if (requestId.isNullOrBlank() || metadata?.optString("_requestId") != requestId || receipt == null) return null
        if (receipt.optString("status") != "awaiting_owner_submission" || receipt.opt("dispatched") != false || receipt.optString("taskId").isBlank()) return null
        if (receipt.optString("submissionCommand") != command || receipt.optString("configurationRevision") != arguments.optString("configurationRevision")) return null
        if (receipt.optString("requestHash") != TaskStore.capabilityFingerprint(arguments)) return null
        return JSONObject(arguments.toString()).put("taskId", receipt.getString("taskId"))
    }
    fun originalDownloadReview(receipt: JSONObject?, metadata: JSONObject?, requestId: String?, binding: JSONObject): JSONObject? {
        if (requestId.isNullOrBlank() || metadata?.optString("_requestId") != requestId || metadata.optBoolean("_failed") || receipt == null) return null
        val review = receipt.optJSONObject("resultDownloadReview") ?: return null
        for (field in listOf("operationId", "taskId", "runId", "configurationRevision")) {
            if (binding.optString(field).isBlank() || review.optString(field) != binding.optString(field) || receipt.optString(field) != binding.optString(field)) return null
        }
        if (binding.optInt("epoch") < 1 || review.optInt("epoch", -1) != binding.optInt("epoch") || receipt.optInt("hostEpoch", -1) != binding.optInt("epoch")) return null
        if (review.optString("reviewId").isBlank() || review.optLong("expiresAt") <= System.currentTimeMillis() || review.optString("method") != "GET" || review.optInt("maxAttempts") != 1 || review.opt("newGeneration") != false || review.opt("redirects") != false) return null
        if (!review.optString("providerResponseSha256").matches(Regex("[a-f0-9]{64}")) || !review.optString("resultUrlSha256").matches(Regex("[a-f0-9]{64}"))) return null
        return JSONObject(review.toString())
    }
    fun binding(task: JSONObject, operation: JSONObject): JSONObject = JSONObject().put("operationId", operation.getString("operationId"))
        .put("taskId", task.getString("id")).put("runId", operation.getString("runId")).put("epoch", task.getInt("coordinatorEpoch"))
        .put("configurationRevision", operation.getJSONObject("arguments").getString("configurationRevision"))
}

@Composable
internal fun NativeMedia(state: PocketUiState, controller: PocketWorldController) {
    val media = state.feature("media")
    val config = media.optJSONObject("configuration") ?: JSONObject()
    var settings by rememberSaveable { mutableStateOf(!media.optBoolean("configured")) }
    var provider by remember(config.optString("provider")) { mutableStateOf(config.optString("provider", "openai_compatible")) }
    var baseUrl by remember(config.optString("baseUrl")) { mutableStateOf(config.optString("baseUrl")) }
    var imageModel by remember(config.optString("imageModel")) { mutableStateOf(config.optString("imageModel")) }
    var ttsModel by remember(config.optString("ttsModel")) { mutableStateOf(config.optString("ttsModel")) }
    var voice by remember(config.optString("voice")) { mutableStateOf(config.optString("voice")) }
    var legacy by remember(config.optBoolean("legacyImageBase64")) { mutableStateOf(config.optBoolean("legacyImageBase64")) }
    var allowResults by remember(config.optBoolean("allowResultUrls")) { mutableStateOf(config.optBoolean("allowResultUrls")) }
    var observeOriginal by rememberSaveable { mutableStateOf(true) }
    var inputImage by remember { mutableStateOf<JSONObject?>(null) }
    var inputMask by remember { mutableStateOf<JSONObject?>(null) }
    var jobsApi by remember(config.optBoolean("comfyJobsApi")) { mutableStateOf(config.optBoolean("comfyJobsApi")) }
    var template by remember { mutableStateOf("") }
    var videoTemplate by remember { mutableStateOf("") }
    var videoNode by remember(config.optString("videoPromptNodeId")) { mutableStateOf(config.optString("videoPromptNodeId")) }
    var videoField by remember(config.optString("videoPromptInput")) { mutableStateOf(config.optString("videoPromptInput", "text")) }
    var promptNode by remember(config.optString("promptNodeId")) { mutableStateOf(config.optString("promptNodeId")) }
    var promptInput by remember(config.optString("promptInput")) { mutableStateOf(config.optString("promptInput", "text")) }
    var apiKey by remember { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(if (config.optString("provider") == "comfyui") "workflow" else "image") }
    var text by rememberSaveable { mutableStateOf("") }
    var graph by remember { mutableStateOf("") }
    var prepareRequest by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var confirmSubmission by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    val saving = "media.configure" in state.moduleBusy
    val busy = media.optBoolean("busy") || state.moduleBusy.any { it.startsWith("media.") && it.substringAfter('.') in setOf("submitWorkflow", "generateImage", "generateVideo", "editImage", "synthesizeSpeech", "getJob", "fetchResults", "reconcileOperation", "cancelJob") }
    val command = when (kind) { "workflow" -> "submitWorkflow"; "video" -> "generateVideo"; "speech" -> "synthesizeSpeech"; "edit" -> "editImage"; else -> "generateImage" }
    val arguments = try {
        JSONObject().put("configurationRevision", media.optString("configurationRevision")).put("observeUntilComplete", observeOriginal).also { args ->
            if (kind == "workflow") args.put("workflow", JSONObject(graph)) else args.put(if (kind == "speech") "text" else "prompt", text.trim())
            if (kind == "edit") { val image = inputImage ?: error("请选择原图"); args.put("imageId", image.getString("id")).put("imageSha256", image.getString("sha256")); inputMask?.let { mask -> args.put("maskId", mask.getString("id")).put("maskSha256", mask.getString("sha256")) } }
        }
    } catch (_: Exception) { null }
    val prepared = arguments?.let { MediaOwnerUi.prepared(state.moduleResults["media.prepareSubmission"], state.moduleResultInputs["media.prepareSubmission"], prepareRequest, command, it) }
    val task = prepared?.optString("taskId")?.let { id -> state.tasks.find { it.id == id } }
    val hasOperation = task?.let { MediaOwnerUi.operation(it.raw) != null } == true
    LaunchedEffect(config.optString("provider")) { if (config.optString("provider") == "comfyui" && kind in setOf("speech", "edit")) kind = "workflow" else if (config.optString("provider") != "comfyui" && kind in setOf("workflow", "video")) kind = "image"; prepareRequest = null }
    LaunchedEffect(state.moduleResults["media.configure"]) { if (state.moduleResults["media.configure"] != null) { apiKey = ""; settings = false; prepareRequest = null } }
    MediaCaption("先准备并核对输入，再明确提交到媒体服务。服务受理、产物保存与你确认目标完成是三个不同状态。")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (media.optBoolean("configured")) "媒体服务已配置 · 尚未验证可用性" else "尚未配置媒体服务", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        TextButton(onClick = { settings = !settings }, enabled = !busy) { Text(if (settings) "收起" else "服务配置", fontSize = 12.sp) }
    }
    if (settings) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(provider == "openai_compatible", { provider = "openai_compatible" }, label = { Text("兼容图片/语音 API") })
            FilterChip(provider == "comfyui", { provider = "comfyui" }, label = { Text("ComfyUI") })
        }
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text(if (provider == "comfyui") "ComfyUI 服务根地址" else "兼容媒体接口地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (provider == "openai_compatible") {
            OutlinedTextField(imageModel, { imageModel = it }, label = { Text("生图模型（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ttsModel, { ttsModel = it }, label = { Text("语音模型（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(voice, { voice = it }, label = { Text("语音 voice 标识") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Text("兼容旧版 Base64 图片响应", Modifier.weight(1f), fontSize = 12.sp); Switch(legacy, { legacy = it }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(allowResults, { allowResults = it }); Text("允许获取服务实际返回的 HTTPS 公有结果地址", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
            MediaCaption("只读原响应地址，最多20MiB；不跟随跳转，不向其他来源发送服务密钥或账号cookie。此选择不允许模型任意填URL下载。")
        } else {
            MediaCaption("使用服务端真实安装的 API 格式节点图，不会生成默认模型或猜测节点。直接运行图无需保存模板。")
            OutlinedTextField(template, { template = it }, label = { Text("生图模板 JSON（可选）") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            if (template.isNotBlank()) {
                OutlinedTextField(promptNode, { promptNode = it }, label = { Text("已有提示词节点 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(promptInput, { promptInput = it }, label = { Text("节点内的文字输入字段") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(videoTemplate, { videoTemplate = it }, label = { Text("视频 API 模板 JSON（可选，需服务已安装实际视频节点）") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
            if (videoTemplate.isNotBlank()) {
                OutlinedTextField(videoNode, { videoNode = it }, label = { Text("视频模板已有提示词节点 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(videoField, { videoField = it }, label = { Text("视频模板文字输入字段") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (config.optBoolean("hasVideoWorkflow")) MediaCaption("已有视频模板不回显正文。再次保存配置时留空会清除它；模型不能任意改写模板图。")
            if (config.optBoolean("hasWorkflow")) MediaCaption("已有模板不会回显正文；再次保存配置需重新粘贴模板，留空将清除模板。")
            Row(verticalAlignment = Alignment.CenterVertically) { Text("服务确实支持 /api/jobs/{id}/cancel", Modifier.weight(1f), fontSize = 12.sp); Switch(jobsApi, { jobsApi = it }) }
            MediaCaption("默认只删除原排队任务，不会使用全局 interrupt，也不会声称能停止已运行的任务。")
        }
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("媒体服务密钥（需要时填写）") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        MediaCaption("相同服务可留空保留原密钥；不会使用聊天密钥。更换地址/服务将不再沿用旧凭据。")
        Button(onClick = {
            try {
                val input = JSONObject().put("provider", provider).put("baseUrl", baseUrl.trim()).put("apiKey", apiKey)
                    .put("imageModel", imageModel.trim()).put("ttsModel", ttsModel.trim()).put("voice", voice.trim()).put("legacyImageBase64", legacy).put("allowResultUrls", allowResults).put("comfyJobsApi", jobsApi)
                if (provider == "comfyui" && template.isNotBlank()) input.put("workflow", JSONObject(template)).put("promptNodeId", promptNode.trim()).put("promptInput", promptInput.trim())
                if (provider == "comfyui" && videoTemplate.isNotBlank()) input.put("videoWorkflow", JSONObject(videoTemplate)).put("videoPromptNodeId", videoNode.trim()).put("videoPromptInput", videoField.trim())
                controller.requestFeature("media", "configure", input); localError = null
            } catch (_: Exception) { localError = "配置中的工作流不是有效 JSON；没有保存或发送。" }
        }, enabled = !saving && !busy && baseUrl.isNotBlank() && (provider == "comfyui" || (imageModel.isNotBlank() || ttsModel.isNotBlank()) && (ttsModel.isBlank() || voice.isNotBlank()))) { Text(if (saving) "保存中" else "保存服务配置") }
    }
    HorizontalDivider(Modifier.padding(vertical = 20.dp), color = Line)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (config.optString("provider") == "comfyui") FilterChip(kind == "workflow", { kind = "workflow"; prepareRequest = null }, label = { Text("运行 API 图") })
        FilterChip(kind == "image", { kind = "image"; prepareRequest = null }, label = { Text("生成图片") })
        if (config.optString("provider") == "comfyui" && config.optBoolean("hasVideoWorkflow")) FilterChip(kind == "video", { kind = "video"; prepareRequest = null }, label = { Text("生成视频") })
        if (media.optBoolean("imageEditingSupported")) FilterChip(kind == "edit", { kind = "edit"; prepareRequest = null }, label = { Text("编辑原图") })
        if (config.optString("provider") != "comfyui") FilterChip(kind == "speech", { kind = "speech"; prepareRequest = null }, label = { Text("生成语音文件") })
    }
    if (kind == "edit") NativeMediaImageInputs(state, inputImage, inputMask, !busy, chooseImage = { inputImage = it; prepareRequest = null }, chooseMask = { inputMask = it; prepareRequest = null }, controller = controller)
    if (config.optString("provider") == "comfyui") {
        Row { Checkbox(observeOriginal, { observeOriginal = it; prepareRequest = null }, enabled = !busy); Text("离开页面后仍在应用存活期间核对原任务", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
        MediaCaption("只有服务已确认的原job才续查，最多60次/5分钟且不超过本次批准有效期。不会重发工作流；应用被系统结束后，请在原任务明确继续查询，不承诺系统后台常驻。MP4 支持 H.264/H.265 与 AAC，最多20MiB。")
    }
    if (kind == "workflow") OutlinedTextField(graph, { graph = it; prepareRequest = null }, label = { Text("粘贴 ComfyUI API 格式工作流 JSON") }, minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !busy)
    else OutlinedTextField(text, { text = it; prepareRequest = null }, label = { Text(if (kind == "speech") "要合成为语音的文字" else if (kind == "edit") "描述对所选原图的修改" else if (kind == "video") "描述已配置视频模板要生成的内容" else "描述要生成的图片") }, supportingText = { Text("${text.length} / ${if(kind=="speech")dev.pocketengine.media.MediaTransport.MAX_SPEECH_INPUT_CHARS else dev.pocketengine.media.MediaTransport.MAX_IMAGE_INPUT_CHARS} 字", fontSize = 11.sp) }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !busy)
    if (kind == "image" && config.optString("provider") == "comfyui" && !config.optBoolean("hasWorkflow")) MediaCaption("生图需先保存可用模板和提示词节点，或切换到“运行 API 图”直接提交完整图。")
    val inputLimit=media.optJSONObject("inputLimits")?.optInt(if(kind=="speech")"speechTextChars"else"imagePromptChars")?.takeIf{it>0}?:if(kind=="speech")dev.pocketengine.media.MediaTransport.MAX_SPEECH_INPUT_CHARS else dev.pocketengine.media.MediaTransport.MAX_IMAGE_INPUT_CHARS
    val valid = arguments != null && media.optBoolean("configured") && (kind == "workflow" || text.isNotBlank() && text.length <= inputLimit) && !(kind == "image" && config.optString("provider") == "comfyui" && !config.optBoolean("hasWorkflow"))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
        if (prepared == null) Button(onClick = { arguments?.let { prepareRequest = controller.requestFeature("media", "prepareSubmission", JSONObject().put("submissionCommand", command).put("arguments", it)); localError = null } }, enabled = valid && !busy && "media.prepareSubmission" !in state.moduleBusy) { Text("准备并核对本次输入") }
        else if (!hasOperation) Button(onClick = { confirmSubmission = command to JSONObject(prepared.toString()) }, enabled = !busy) { Text("确认提交到服务") }
        if (busy) OutlinedButton(onClick = controller::cancelMedia) { Text("停止本地等待") }
    }
    prepared?.let { MediaCaption("原任务已保留。${if (hasOperation) "已登记原操作，不会再次生成；请在原任务查询或取回结果。" else "尚未发送到服务，提交只对本次输入和配置版本有效。"}"); TextButton(onClick = { controller.focusTask(it.getString("taskId")) }) { Text("查看原任务与产物") } }
    localError?.let { Text(it, color = Danger, fontSize = 12.sp) }
    state.moduleErrors.filterKeys { it.startsWith("media.") }.values.distinct().forEach { Text(it, color = Danger, fontSize = 12.sp) }
    HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Line)
    Text("原媒体任务", fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val mediaTasks = state.tasks.filter { MediaOwnerUi.isTask(it.raw) }
    if (mediaTasks.isEmpty()) MediaCaption("还没有媒体任务。准备不会请求服务，提交后可在这里查询同一操作。")
    mediaTasks.take(20).forEach { row -> TextButton(onClick = { controller.focusTask(row.id) }) { Text(row.goal.take(80) + " · " + taskStatus(row.status), maxLines = 2, fontSize = 12.sp) } }
    confirmSubmission?.let { frozen -> AlertDialog(onDismissRequest = { confirmSubmission = null }, title = { Text("提交本次媒体请求？") }, text = { Text((if (frozen.first == "editImage") "会上传刚选定的完整原图与可选mask，新结果独立保存，原件不覆盖。\n\n" else "") + "本次输入将发送到已配置服务，可能产生费用。只在这个原任务中提交一次；回执未知时仅查询，不自动重试。") }, confirmButton = { Button(onClick = { controller.requestFeature("media", frozen.first, frozen.second); confirmSubmission = null }) { Text("确认提交") } }, dismissButton = { TextButton(onClick = { confirmSubmission = null }) { Text("返回核对") } }) }
}

@Composable
internal fun MediaTaskPanel(task: UiTask, state: PocketUiState, controller: PocketWorldController, showArtifacts: Boolean = true) {
    val operation = MediaOwnerUi.operation(task.raw)
    var cancelReview by remember(task.id) { mutableStateOf(false) }
    var downloadReviewRequest by remember(task.id, operation?.optString("operationId")) { mutableStateOf<String?>(null) }
    if (operation == null) { PreparedMediaTask(task, state, controller); return }
    val binding = MediaOwnerUi.binding(task.raw, operation)
    val receipt = operation.optJSONObject("receipt") ?: task.raw.optJSONObject("evidence") ?: JSONObject()
    val providerStatus = receipt.optString("providerStatus", receipt.optString("status", "尚未取得回执"))
    val localWait = state.feature("media").optString("activeOperationId") == operation.optString("operationId")
    val busy = localWait || state.moduleBusy.any { it in setOf("media.getJob", "media.reconcileOperation", "media.fetchResults", "media.cancelJob", "media.reviewResultDownload", "media.confirmResultDownload") }
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Line)
    val saved=receipt.optJSONArray("attachments").objects()
    if(showArtifacts&&saved.isNotEmpty()){
        Text("已保存成果",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
        TextButton(onClick={controller.openTaskOutcomes(task.id)}){Text("打开成果、按来源查看或导出")}
        saved.forEach{file->Text(file.optString("mimeType")+" · "+file.optLong("size")+" 字节",color=SecondaryInk,fontSize=12.sp)}
    } else if(saved.isEmpty())TextButton(onClick={controller.openTaskOutcomes(task.id)}){Text("查看原任务进度与可继续成果")}
    Text("媒体原操作", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val continued = state.feature("media").optJSONObject("hostReadContinuations")?.optJSONArray("operations").objects().any { it.optString("operationId") == operation.optString("operationId") }
    MediaCaption("服务回执：$providerStatus · ${if (localWait) "正在等待原响应" else if (continued) "应用存活内正在核对原job" else "没有活动的自动核对"}")
    if (receipt.optString("message").isNotBlank()) MediaCaption(receipt.optString("message"))
    MediaCaption("受理不代表生成完成；产物可用后仍需你确认目标。这里只查询/导入原操作，不新建生成请求。")
    SelectionContainer { Text("操作 " + operation.optString("operationId").takeLast(12) + " · " + receipt.optString("providerJobId", "服务 ID 未确认"), color = SecondaryInk, fontSize = 11.sp) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { controller.requestFeature("media", "reconcileOperation", binding) }, enabled = !busy) { Text("查询原操作") }
        OutlinedButton(onClick = { controller.requestFeature("media", "fetchResults", binding) }, enabled = !busy) { Text("取回原产物") }
    }
    if (receipt.optBoolean("ownerResultDownloadReviewAvailable") || receipt.optString("resultStatus") == "owner_download_policy_required") {
        if (receipt.optString("resultStatus") != "saved") TextButton(onClick = { downloadReviewRequest = controller.requestFeature("media", "reviewResultDownload", JSONObject(binding.toString())) }, enabled = !busy) { Text("审阅并一次允许取回原结果") }
    }
    val downloadReview = MediaOwnerUi.originalDownloadReview(state.moduleResults["media.reviewResultDownload"], state.moduleResultInputs["media.reviewResultDownload"], downloadReviewRequest, binding)
    downloadReview?.let { frozen ->
        AlertDialog(onDismissRequest = { downloadReviewRequest = null }, title = { Text("一次取回这个原结果？") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只 GET 已保存的原地址一次，失败后需重新审阅。不重发生成，不修改全局下载开关；仍仅允许 HTTPS 公有地址、不跟随重定向。")
                Text("原服务：" + frozen.optString("providerOrigin") + "\n结果来源：" + frozen.optString("resultOrigin"))
                SelectionContainer { Text("原操作：" + frozen.optString("operationId") + "\n原响应 SHA-256：" + frozen.optString("providerResponseSha256") + "\n原地址 SHA-256：" + frozen.optString("resultUrlSha256"), fontSize = 11.sp) }
            }
        }, confirmButton = { Button(onClick = { controller.requestFeature("media", "confirmResultDownload", JSONObject(binding.toString()).put("downloadReview", JSONObject(frozen.toString()))); downloadReviewRequest = null }, enabled = !busy) { Text("仅允许这一次下载") } }, dismissButton = { TextButton(onClick = { downloadReviewRequest = null }) { Text("返回") } })
    }
    if (localWait || continued) TextButton(onClick = { controller.requestFeature("media", "cancel", JSONObject().put("operationId", operation.getString("operationId"))) }) { Text("停止本地等待", color = Danger) }
    else TextButton(onClick = { cancelReview = true }, enabled = !busy) { Text("请求定向取消原服务任务", color = Danger) }

    state.moduleErrors.filterKeys { it.startsWith("media.") }.values.distinct().forEach { Text(it, color = Danger, fontSize = 12.sp) }
    if (cancelReview) AlertDialog(onDismissRequest = { cancelReview = false }, title = { Text("定向取消原服务任务？") }, text = { Text("只针对这个原任务。默认 ComfyUI 仅删除排队项；运行中且没有定向取消 API 时将明确拒绝，不使用全局 interrupt。停止本地等待不代表远端停止。") }, confirmButton = { TextButton(onClick = { controller.requestFeature("media", "cancelJob", binding); cancelReview = false }) { Text("请求取消原任务", color = Danger) } }, dismissButton = { TextButton(onClick = { cancelReview = false }) { Text("保留") } })
}
@Composable
private fun PreparedMediaTask(task: UiTask, state: PocketUiState, controller: PocketWorldController) {
    var request by remember(task.id) { mutableStateOf<String?>(null) }
    var confirmation by remember(task.id) { mutableStateOf<Pair<String, JSONObject>?>(null) }
    var showInput by remember(task.id) { mutableStateOf(false) }
    fun read() { request = controller.requestFeature("media", "getPreparedSubmission", JSONObject().put("taskId", task.id)) }
    LaunchedEffect(task.id) { read() }
    val result = state.moduleResults["media.getPreparedSubmission"]?.takeIf {
        state.moduleResultInputs["media.getPreparedSubmission"]?.optString("_requestId") == request && it.optString("taskId") == task.id && !it.optBoolean("knownOperation") && it.optString("status") == "awaiting_owner_submission"
    }
    val args = result?.optJSONObject("arguments")
    val command = result?.optString("submissionCommand").orEmpty()
    val currentRoute = result != null && result.optString("configurationRevision") == state.feature("media").optString("configurationRevision")
    val busy = "media.getPreparedSubmission" in state.moduleBusy || listOf("media.submitWorkflow", "media.generateImage", "media.generateVideo", "media.editImage", "media.synthesizeSpeech").any { it in state.moduleBusy } || state.feature("media").optBoolean("busy")
    MediaCaption("这是原任务保存的确切输入，尚未向服务提交。离页或重启不会变成新生成任务，也不会进入聊天续跑。")
    if (result == null) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.moduleErrors["media.getPreparedSubmission"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
        TextButton(onClick = { read() }, enabled = !busy) { Text("重读原输入") }
    } else {
        MediaCaption("配置版本 " + result.optString("configurationRevision").takeLast(12) + if (currentRoute) " · 当前服务仍匹配" else " · 服务配置已变化，不能提交旧输入")
        if (command == "submitWorkflow") {
            MediaCaption("API 工作流 · " + (args?.optJSONObject("workflow")?.length() ?: 0) + " 个节点")
            TextButton(onClick = { showInput = !showInput }) { Text(if (showInput) "收起输入预览" else "核对工作流输入") }
            if (showInput) {
                val text = args?.optJSONObject("workflow")?.toString(2).orEmpty()
                SelectionContainer { Text(text.take(12000), fontSize = 11.sp, color = SecondaryInk) }
                if (text.length > 12000) MediaCaption("这里只预览前 12000 字；完整原始输入保存在下方附件中，提交时不会使用截断预览。")
            }
        } else SelectionContainer { Text(args?.optString(if (command == "synthesizeSpeech") "text" else "prompt").orEmpty(), fontSize = 13.sp, lineHeight = 23.sp) }
        if (command == "editImage" && args != null) {
            MediaCaption("将上传所选原图 ${args.optString("imageId").take(8)} · ${args.optString("imageSha256").take(12)}" + if (args.has("maskId")) "，以及同尺寸透明 mask ${args.optString("maskId").take(8)}" else "；未选局部 mask")
            TextButton(onClick = { controller.attachmentOutcomes.openFile(JSONObject().put("id", args.getString("imageId")).put("sha256", args.getString("imageSha256"))) }) { Text("核对这份原图") }
        }
        if (args?.optBoolean("observeUntilComplete") == true) MediaCaption("这份明确选择包括原job在应用存活期间有限只读续查；不承诺OS后台常驻，也不续期原批准或重新提交。")
        result.optJSONObject("inputAttachment")?.let { input -> TextButton(onClick = { controller.requestFeature("attachments", "view", JSONObject().put("id", input.getString("id"))) }) { Text("查看完整原始输入附件", fontSize = 12.sp) } }
        Button(onClick = { if (args != null) confirmation = command to JSONObject(args.toString()).put("taskId", task.id) }, enabled = !busy && currentRoute && args != null) { Text("核对完成，提交这个原任务") }
    }
    state.moduleErrors.filterKeys { it in setOf("media.submitWorkflow", "media.generateImage", "media.generateVideo", "media.editImage", "media.synthesizeSpeech") }.values.distinct().forEach { Text(it, color = Danger, fontSize = 12.sp) }
    confirmation?.let { frozen -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("明确提交这个原任务？") }, text = { Text("只发送刚重读并核对的输入与服务配置版本，可能产生费用。未知回执只查询原操作，不会重新生成。") }, confirmButton = { Button(onClick = { controller.requestFeature("media", frozen.first, frozen.second); confirmation = null }) { Text("提交原任务") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("返回核对") } }) }
}

@Composable private fun MediaCaption(value: String) { Text(value, color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)) }
