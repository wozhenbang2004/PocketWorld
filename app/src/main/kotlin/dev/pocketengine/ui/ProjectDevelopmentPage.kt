package dev.pocketengine.ui

import android.content.ContextWrapper
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.pocketengine.features.local.preview.ProjectArtifactWebView
import dev.pocketengine.features.local.preview.ProjectPreviewArtifact
import dev.pocketengine.features.local.preview.ProjectOwnerAction
import dev.pocketengine.features.local.preview.ProjectRepairFeedback
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private data class ProjectRequest(val command: String, val id: String, val sourceDigest: String?)

/** Increment of the existing checkout workbench, using its existing Controller and task authority. */
@Composable
internal fun NativeProjectDevelopment(checkout: JSONObject, state: PocketUiState, controller: PocketWorldController, onManualTools: () -> Unit, surface: ProjectWorkbenchFacet = ProjectWorkbenchFacet.Agent) {
    val checkoutId = checkout.getString("id")
    val capabilities = state.feature("local").optJSONObject("projectDevelopment")
    val mounted = capabilities?.optBoolean("available") == true
    val manualExecution = mounted && capabilities?.optBoolean("manualOwnerExecutionAvailable") == true
    var pending by remember(checkoutId) { mutableStateOf<ProjectRequest?>(null) }
    var inspection by remember(checkoutId) { mutableStateOf<JSONObject?>(null) }
    var plan by remember(checkoutId) { mutableStateOf<JSONObject?>(null) }
    var receipt by remember(checkoutId) { mutableStateOf<JSONObject?>(null) }
    var artifact by remember(checkoutId) { mutableStateOf<ProjectPreviewArtifact.Verified?>(null) }
    var confirmation by remember(checkoutId) { mutableStateOf<JSONObject?>(null) }
    var ownerReference by remember(checkoutId) { mutableStateOf<JSONObject?>(null) }
    var ownerStep by remember(checkoutId) { mutableStateOf<JSONObject?>(null) }
    var ownerDispatchUnknown by remember(checkoutId) { mutableStateOf(false) }
    var collectionBlocking by remember(checkoutId) { mutableStateOf(false) }
    var artifactPath by remember(checkoutId) { mutableStateOf("index.html") }
    var sourcePath by remember(checkoutId) { mutableStateOf("index.html") }
    var repair by remember(checkoutId) { mutableStateOf<JSONObject?>(null) }
    var localError by remember(checkoutId) { mutableStateOf<String?>(null) }
    var showSource by remember(checkoutId) { mutableStateOf(false) }
    var intent by remember(checkoutId) { mutableStateOf("prepare") }
    var advanced by remember(checkoutId) { mutableStateOf(false) }
    var showDependencies by remember(checkoutId) { mutableStateOf(false) }
    var showSourcePreview by remember(checkoutId) { mutableStateOf(false) }
    var ecosystem by remember(checkoutId) { mutableStateOf("") }
    var target by remember(checkoutId) { mutableStateOf("") }
    val source = inspection?.optString("sourceDigest")
    val currentState by rememberUpdatedState(state)
    val currentReceipt by rememberUpdatedState(receipt)
    val pendingNow by rememberUpdatedState(pending)
    val projectTask = state.tasks.filter { it.raw.optJSONObject("developmentContext")?.optString("checkoutId") == checkoutId }.maxByOrNull { it.updatedAt }
    val settledOperation = projectTask?.raw?.optJSONArray("operations")?.objects()?.lastOrNull()?.takeIf { it.optString("state") == "receipt" }?.optString("operationId")
    val sourceRefresh = projectTask?.let { it.id + ":" + it.status + ":" + settledOperation.orEmpty() }
    fun submit(command: String, extra: JSONObject = JSONObject(), digest: String? = source) {
        val payload = JSONObject(extra.toString()).put("checkoutId", checkoutId)
        localError = null
        pending = ProjectRequest(command, controller.requestFeature("local", command, payload), digest)
    }
    fun originalBinding(): JSONObject = if (receipt?.optJSONObject("binding")?.optString("authority") == "owner_tracked_job" && ownerReference != null) JSONObject(ownerReference.toString()) else JSONObject().put("binding", receipt?.optJSONObject("binding"))
    fun pollOriginal() {
        val current = receipt ?: return
        if (current.optJSONObject("binding")?.optString("authority") == "owner_tracked_job") submit("getJob", JSONObject().put("jobId", current.getString("jobId")).put("termux", true), current.optString("sourceDigest"))
        else submit("project.poll", originalBinding(), current.optString("sourceDigest"))
    }
    LaunchedEffect(checkoutId, mounted, sourceRefresh) {
        if (mounted) {
            while (pendingNow != null) delay(60)
            if (!advanced) submit("project.inspect", digest = null)
        }
    }
    val active = pending
    val key = active?.let { "local.${it.command}" }
    val response = key?.let { state.moduleResults[it] }
    val responseInput = key?.let { state.moduleResultInputs[it] }
    LaunchedEffect(active?.id, response?.toString(), responseInput?.optString("_requestId"), key?.let { state.moduleErrors[it] }) {
        val request = pending ?: return@LaunchedEffect
        val nativeOwnerReceipt = request.command.startsWith("termux.") && request.sourceDigest == source && LocalWorkbenchGuard.result(response, responseInput, request.id, checkoutId) != null
        val sourceReceipt = request.command == "readFile" && request.sourceDigest == source && LocalWorkbenchGuard.result(response, responseInput, request.id, checkoutId, sourcePath) != null
        val originalJobReceipt = request.command == "getJob" && LocalWorkbenchGuard.result(response, responseInput, request.id, checkoutId) != null
        if (nativeOwnerReceipt || sourceReceipt || originalJobReceipt || ProjectPreviewArtifact.current(response, responseInput, request.id, checkoutId, request.sourceDigest)) {
            val result = JSONObject(response.toString())
            try {
                when (request.command) {
                    "project.inspect" -> {
                        inspection = result; artifact = null; repair = null
                        if (!ownerDispatchUnknown && !collectionBlocking && receipt?.optString("status") !in setOf("unknown", "queued", "running", "awaiting_result", "cancel_requested", "interrupted")) { plan = null; receipt = null }
                    }
                    "project.plan" -> { plan = result; receipt = null; artifact = null; repair = null }
                    "project.execute", "project.adoptOwnerReceipt", "project.poll", "project.cancel" -> { receipt = result; artifact = null; ownerDispatchUnknown = false }
                    "project.artifact" -> { artifact = ProjectPreviewArtifact.verify(result, receipt, checkoutId, source); showSource = false }
                    "readFile" -> { artifact = ProjectPreviewArtifact.verifySource(result, inspection, checkoutId); showSource = false }
                    "project.repair" -> repair = result
                    "getJob" -> { pending = null; submit("project.poll", originalBinding(), request.sourceDigest); return@LaunchedEffect }
                    else -> if (nativeOwnerReceipt) {
                        val frozen = ownerStep ?: error("缺少已核对步骤")
                        val reference = JSONObject().put("plan", frozen.getJSONObject("plan")).put("stepId", frozen.getString("stepId")).put("jobId", result.getString("id"))
                        ownerReference = reference
                        pending = null
                        submit("project.adoptOwnerReceipt", reference, request.sourceDigest)
                        return@LaunchedEffect
                    }
                }
            } catch (failure: Exception) { artifact = null; localError = failure.message ?: "回执与当前源码或产物不一致，请重新核对" }
            pending = null
        } else if (key != null && state.moduleErrors[key] != null && key !in state.moduleBusy && responseInput?.optString("_requestId") == request.id) {
            localError = state.moduleErrors[key]; if (request.command.startsWith("termux.")) ownerDispatchUnknown = true; pending = null
        }
    }
    val jobId = receipt?.optString("jobId").orEmpty()
    var showLogs by remember(checkoutId, jobId) { mutableStateOf(false) }
    val running = receipt != null && receipt?.optString("status") in setOf("queued", "running", "awaiting_result", "cancel_requested")
    val originalUnresolved = ownerDispatchUnknown || receipt?.optString("status") in setOf("unknown", "queued", "running", "awaiting_result", "cancel_requested", "interrupted")
    val unresolved = collectionBlocking || originalUnresolved
    LaunchedEffect(checkoutId, jobId, running) {
        while (running && jobId.isNotBlank()) {
            delay(1800)
            if (pending == null && "local.project.poll" !in currentState.moduleBusy) {
                if (currentReceipt == null) break
                pollOriginal()
            }
        }
    }
    val busy = pending != null || state.moduleBusy.any { it.startsWith("local.project.") }
    NativeProjectAgentWorkbench(checkout, inspection, state, controller, surface)
    if (surface != ProjectWorkbenchFacet.Agent) return
    // Size animation does not retain a second WebView or a second owner of the native request state.
    Column(Modifier.fillMaxWidth().animateContentSize(tween(160))) {
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 44.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(if (advanced) "收起手动步骤" else "手动检查与单步执行", fontSize = 14.sp)
        }
        if (!advanced) return@Column
        ProjectCaption("核对源码与具体步骤后执行。预览和日志仍关联这份工作副本。")
        if (!mounted) {
            ProjectCaption("项目开发入口尚未接入本机宿主。现有手动工具、执行回执和文件审阅仍可使用。")
            return@Column
        }
        TextButton(onClick = onManualTools) { Text("连接 Termux 或查看手动工具") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        localError?.let { Text(it, color = Danger, fontSize = 13.sp, lineHeight = 21.sp) }
        if (ownerDispatchUnknown) ProjectCaption("派发未收到确定回执。请到「执行回执」或原任务核对，不会再次提交此步骤。")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { submit("project.inspect", digest = null) }, enabled = !busy && !collectionBlocking) { Text(if (inspection == null) "读取项目与依赖" else "重新核对源码") }
            if (source != null) Text("源码 ${source.take(10)}", fontSize = 12.sp, color = SecondaryInk, modifier = Modifier.padding(top = 14.dp))
        }
        inspection?.let { info ->
            val ecosystems = info.optJSONArray("ecosystems")
            if (ecosystems != null && ecosystems.length() > 0) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (index in 0 until ecosystems.length()) {
                        val item = ecosystems.optJSONObject(index) ?: continue
                        val id = item.optString("id")
                        FilterChip(selected = ecosystem == id || ecosystem.isEmpty() && ecosystems.length() == 1, onClick = { ecosystem = id }, label = { Text(id) }, enabled = !busy)
                    }
                }
            }
            val dependencies = info.optJSONArray("dependencies")
            if (dependencies != null && dependencies.length() > 0) {
                ProjectWorkbenchDisclosure("依赖与锁文件", showDependencies, { showDependencies = !showDependencies }, "查看已读取的项目依赖") {
                    for (index in 0 until minOf(dependencies.length(), 30)) {
                        val dependency = dependencies.optJSONObject(index) ?: continue
                        Text("${dependency.optString("name")}  ${dependency.optString("spec")}", fontSize = 14.sp)
                        ProjectCaption("${dependency.optString("kind")} · ${dependency.optString("sourcePath")}")
                    }
                    if (dependencies.length() > 30) ProjectCaption("另有 ${dependencies.length() - 30} 项依赖；完整清单以当前项目文件为准。")
                }
            }
            ProjectWorkbenchDisclosure("源文件预览", showSourcePreview, { showSourcePreview = !showSourcePreview }, "读取实际文件，不代表已经构建") {
                OutlinedTextField(sourcePath, { sourcePath = it; artifact = null }, label = { Text("源码清单内相对路径") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                OutlinedButton(onClick = { submit("readFile", JSONObject().put("path", sourcePath)) }, enabled = !busy && sourcePath.isNotBlank()) { Text("核对源文件并预览") }
                ProjectCaption("直接核对真实文件字节与当前源码清单；不创建构建成功回执。单文件 HTML/CSS/JS 可由你明确打开交互预览。")
            }
            ProjectHeading("选择要核对的步骤")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("prepare" to "准备", "probe" to "工具", "dependencies" to "依赖", "test" to "测试", "build" to "构建", "run" to "运行").forEach { (value, label) ->
                    FilterChip(selected = intent == value, onClick = { intent = value }, label = { Text(label) }, enabled = !busy)
                }
            }
            if (intent in setOf("build", "run")) OutlinedTextField(target, { target = it }, label = { Text("入口相对路径 · 可选") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            PocketPressButton(onClick = {
                val payload = JSONObject().put("expectedSourceDigest", source).put("intent", intent)
                val selected = ecosystem.ifEmpty { if (ecosystems?.length() == 1) ecosystems.optJSONObject(0)?.optString("id").orEmpty() else "" }
                if (selected.isNotEmpty()) payload.put("ecosystem", selected)
                if (target.isNotBlank()) payload.put("target", target.trim())
                submit("project.plan", payload)
            }, enabled = !busy && !unresolved) { Text("查看这一步的方案") }
        }
        plan?.let { frozen ->
            ProjectHeading("核对这次方案")
            ProjectCaption("方案 ${frozen.optString("planDigest").take(10)} · 当前源码 ${frozen.optString("sourceDigest").take(10)}")
            val warnings = frozen.optJSONArray("warnings") ?: JSONArray()
            for (index in 0 until warnings.length()) ProjectCaption(warnings.optString(index))
            val steps = frozen.optJSONArray("steps") ?: JSONArray()
            for (index in 0 until steps.length()) {
                val step = steps.optJSONObject(index) ?: continue
                Text("${index + 1}. ${step.optString("label", "执行步骤")}", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
                ProjectStepReview(step)
                OutlinedButton(onClick = { confirmation = JSONObject().put("plan", JSONObject(frozen.toString())).put("step", JSONObject(step.toString())) }, enabled = !busy && !unresolved && frozen.optBoolean("available") && manualExecution && step.optJSONObject("hostAction") != null) { Text("核对并执行此步骤") }
            }
            ProjectCaption("每一步都由主人确认，通过现有任务入口登记。不会自动派发下一步；Agent执行仍由原生宿主绑定批准与run。")
        }
        receipt?.let { job ->
            ProjectHeading("原执行回执")
            PocketScene(job.optString("status")) { Text(projectStatus(job.optString("status")), fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            ProjectCaption("Termux · ${job.optString("jobId").take(8)} · 源码 ${job.optString("sourceDigest").take(10)}")
            if (job.has("exitCode") && !job.isNull("exitCode")) ProjectCaption("退出码 ${job.getInt("exitCode")}；这只说明本次进程结果，不代表项目目标已验收。")
            if (job.optBoolean("outputTruncated")) ProjectCaption("日志达到上限，以下内容不完整。")
            if (job.optBoolean("sourceVerifiedBeforeExec")) ProjectCaption("执行前已核对清单内文件；不保证同UID并发修改或额外文件隔离。")
            if (job.optString("stdout").isNotEmpty() || job.optString("stderr").isNotEmpty()) {
                ProjectWorkbenchDisclosure("执行日志", showLogs, { showLogs = !showLogs }) {
                    for ((field, label) in listOf("stdout" to "标准输出", "stderr" to "错误输出")) if (job.optString(field).isNotEmpty()) { ProjectHeading(label); ProjectCode(job.optString(field)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { pollOriginal() }, enabled = !busy) { Text("核对原回执") }
                if (originalUnresolved) TextButton(onClick = {
                    if (job.optJSONObject("binding")?.optString("authority") == "owner_tracked_job") {
                        controller.requestFeature("local", "cancelJob", JSONObject().put("jobId", jobId).put("termux", true))
                        pollOriginal()
                    } else submit("project.cancel", originalBinding(), job.optString("sourceDigest"))
                }, enabled = !busy) { Text("请求停止", color = Danger) }
            }
            if (job.optString("status") in setOf("unknown", "cancel_requested", "awaiting_result")) ProjectCaption("尚无确定终止结果。只核对原执行，不自动重跑；Termux 的停止要求不保证进程已结束。")
            if (ProjectPreviewArtifact.successfulBuild(job)) {
                ProjectHeading("读取构建关联文件")
                OutlinedTextField(artifactPath, { artifactPath = it; artifact = null }, label = { Text("副本内相对路径") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                OutlinedButton(onClick = { submit("project.artifact", originalBinding().put("path", artifactPath)) }, enabled = !busy && artifactPath.isNotBlank()) { Text("读取并核对关联文件") }
                ProjectCaption("此入口读取构建所用的本机源码，不是新生成产物。生成文件请使用下面的单文件回收；路径候选本身不代表文件存在。")
                ProjectGeneratedArtifactSection(job, originalBinding(), state, controller, busy, onPreview = { artifact = it; showSource = false }, onBlocking = { collectionBlocking = it })
            }
            if (job.optString("status") == "failed" && job.has("exitCode") && !job.isNull("exitCode")) {
                OutlinedButton(onClick = { submit("project.repair", originalBinding()) }, enabled = !busy) { Text("准备交回 Agent 修改") }
            }
        }
        artifact?.let { verified ->
            ProjectHeading(verified.path)
            ProjectCaption("文件 ${verified.sha256.take(12)}" + when { verified.generatedArtifact -> " · 实际回收的生成文本 · 构建 ${verified.receiptIdentity.take(10)} · 回收 ${verified.collectionReceiptIdentity.take(10)}"; verified.buildAssociated -> " · 构建所用源码，非生成产物 · 回执 ${verified.receiptIdentity.take(12)}"; else -> " · 源文件预览，未构建、未运行" })
            if (verified.generatedArtifact) ProjectCaption("这是构建后从同一版本目录观察并回收的文件，不是原子快照，也不证明只有该构建进程写过它。")
            if (verified.html) ProjectWorkbenchDisclosure("实际文件文本", showSource, { showSource = !showSource }) { ProjectCode(verified.content) }
            else ProjectCode(verified.content)
            if (verified.html) ProjectInteractivePreview(verified)
            var requirement by remember(verified.identity) { mutableStateOf("") }
            OutlinedTextField(requirement, { requirement = it }, label = { Text("希望 Agent 修改什么") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, minLines = 2, maxLines = 5)
            OutlinedButton(onClick = { controller.suggestInput(ProjectRepairFeedback.sourceChange(verified, requirement), false) }, enabled = !busy && requirement.isNotBlank()) { Text("带文件与要求回到对话草稿") }
            ProjectCaption("草稿保留副本、源码和文件摘要，由你核对后发送；不自动启动 Agent 或修改文件。")
        }
        repair?.let { review ->
            val detail = review.optJSONObject("sameTaskRepair")
            val feedback = ProjectRepairFeedback.text(detail)
            ProjectHeading("返回原任务修改")
            if (feedback.isNotBlank()) ProjectCode(feedback)
            val taskId = detail?.optJSONObject("binding")?.optString("taskId").orEmpty()
            val task = state.tasks.firstOrNull { it.id == taskId && it.raw.optString("kind") in setOf("assistant", "phone") }
            if (task != null) OutlinedButton(onClick = { controller.updateTask(taskId, feedback); controller.focusTask(taskId) }, enabled = feedback.isNotBlank() && !busy) { Text("补充到原 Agent 任务并查看") }
            else OutlinedButton(onClick = { controller.suggestInput(feedback, false) }, enabled = feedback.isNotBlank() && !busy) { Text("把问题带回对话草稿") }
            ProjectCaption((if (task != null) "保存补充要求后查看原任务。" else "带日志返回对话草稿，由你核对后发送。") + "修改后的源码需要重新核对、重新生成方案并批准，不自动继续构建。")
        }
        confirmation?.let { review ->
            val step = review.getJSONObject("step")
            val reviewedPlan = review.getJSONObject("plan")
            AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("执行这一具体步骤？") }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    ProjectCaption("源码 ${reviewedPlan.optString("sourceDigest").take(12)} · 方案 ${reviewedPlan.optString("planDigest").take(12)}")
                    ProjectCaption("Termux 初始目录不是权限沙箱。项目脚本可访问 Termux 已有文件与网络；预览页面不能授予执行权限。")
                    ProjectStepReview(step)
                }
            }, confirmButton = { PocketPressButton(onClick = {
                try {
                    val frozenPlan = JSONObject(reviewedPlan.toString())
                    if (step.optJSONObject("hostAction") != null) {
                        val action = ProjectOwnerAction.reviewed(frozenPlan, step)
                        ownerStep = JSONObject().put("plan", frozenPlan).put("stepId", step.optString("stepId", step.optString("id")))
                        receipt = null; artifact = null; repair = null; ownerReference = null; ownerDispatchUnknown = true
                        // Existing FeatureHost records the real native task/run/operation before dispatch.
                        submit(action.getString("command"), action.getJSONObject("payload"))
                    } else error("此方案没有可核对的主人执行入口")
                    confirmation = null
                } catch (failure: Exception) { localError = failure.message; confirmation = null }
            }, enabled = !busy) { Text("确认这次主人执行") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
        }
    }
}

@Composable
private fun ProjectStepReview(step: JSONObject) {
    val action = step.optJSONObject("hostAction")
    val payload = action?.optJSONObject("payload") ?: step.optJSONObject("payload") ?: JSONObject()
    val command = action?.optString("command") ?: step.optString("localCommand")
    ProjectCaption(when (command) { "termux.prepare" -> "将核对过的源码复制到 Termux 工作目录"; "termux.probe" -> "读取 Termux 中实际工具路径"; else -> "在 Termux 执行以下文件与精确参数" })
    if (payload.has("executable")) ProjectCode("executable: ${JSONObject.quote(payload.optString("executable"))}\nargv: ${payload.optJSONArray("argv") ?: JSONArray()}")
    val effects = step.optJSONObject("effects")
    ProjectCaption((if (effects?.optBoolean("writes") == true) "可能写入文件" else "此步骤仅探测工具") + if (effects?.optBoolean("networkMayBeUsed") == true) " · 方案声明可能联网" else " · 未声明网络使用，不代表权限隔离")
}

@Composable
private fun ProjectInteractivePreview(artifact: ProjectPreviewArtifact.Verified) {
    val context = LocalContext.current
    var opened by remember(artifact.identity) { mutableStateOf(false) }
    var error by remember(artifact.identity) { mutableStateOf<String?>(null) }
    var view by remember(artifact.identity) { mutableStateOf<ProjectArtifactWebView?>(null) }
    val lifecycle = remember(context) { generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeUnless { base -> base === it } }.filterIsInstance<LifecycleOwner>().firstOrNull()?.lifecycle }
    DisposableEffect(artifact.identity, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) opened = false }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); view?.release(); view = null }
    }
    ProjectCaption("仅此预览内运行 HTML/CSS/JS。无 Native 桥，拒绝外网、文件、权限、下载和页面逃逸；这不是权限沙箱，也不是 Android 构建后端。")
    if (!opened) PocketPressButton(onClick = { opened = true; error = null }) { Text("打开交互预览") }
    else {
        TextButton(onClick = { opened = false; view?.release(); view = null }) { Text("关闭预览并返回修改") }
        key(artifact.identity) {
            AndroidView(factory = { ProjectArtifactWebView(it, artifact) { message -> error = message; opened = false }.also { web -> view = web } }, modifier = Modifier.fillMaxWidth().height(420.dp))
        }
        DisposableEffect(opened) { onDispose { view?.release(); view = null } }
    }
    error?.let { Text(it, color = Danger, fontSize = 13.sp) }
}

@Composable private fun ProjectHeading(text: String) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) }
@Composable private fun ProjectCaption(text: String) { if (text.isNotBlank()) Text(text, color = SecondaryInk, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 3.dp)) }
@Composable private fun ProjectCode(text: String) { SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).background(Color(0xFFF4F4F4), RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState()).padding(12.dp)) } }
private fun projectStatus(status: String): String = when (status) { "completed" -> "执行已结束，请核对结果"; "failed" -> "执行失败，可带日志返回修改"; "unknown" -> "结果不明 · 仅核对"; "cancel_requested" -> "已登记停止要求 · 待核对"; "awaiting_result" -> "已派发 · 等待真实回执"; "running" -> "执行中"; "queued" -> "已登记 · 待执行"; "cancelled" -> "已收到取消回执"; else -> status }
