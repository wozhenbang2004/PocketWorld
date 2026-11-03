package dev.pocketengine.ui

import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dev.pocketengine.features.local.preview.ProjectBundleWebView
import kotlinx.coroutines.delay
import java.util.UUID
import org.json.JSONObject

private data class ProjectOperationQuery(val requestId: String, val taskId: String, val operationId: String)

/** The goal, files, real process results and preview stay attached to one selected project task. */
@Composable
internal fun NativeProjectAgentWorkbench(checkout: JSONObject, inspection: JSONObject?, state: PocketUiState, controller: PocketWorldController, surface: ProjectWorkbenchFacet = ProjectWorkbenchFacet.Agent) {
    val id = checkout.getString("id")
    val agentSurface = surface == ProjectWorkbenchFacet.Agent
    val runSurface = surface == ProjectWorkbenchFacet.Run
    val previewSurface = surface == ProjectWorkbenchFacet.Preview
    val pythonProject = ProjectWorkbenchGuard.pythonProject(inspection)
    val nodeProject = ProjectWorkbenchGuard.nodeProject(inspection)
    val draftSession = remember(controller, id) { controller.projectWorkbench.agent(id) }
    DisposableEffect(controller, id) { onDispose { controller.releaseProjectStartConsent(draftSession.client.value) } }
    var goal by draftSession.goal
    var client by draftSession.client
    var request by draftSession.request
    var originalSubmission by draftSession.originalSubmission
    var unconfirmedSubmission by draftSession.unconfirmedSubmission
    var submissionQuery by draftSession.submissionQuery
    var runtimeId by draftSession.runtimeId
    var useProjectMemory by remember(id, client) { mutableStateOf(false) }
    var showRuntime by remember(id) { mutableStateOf(false) }
    var newTask by draftSession.newTask
    val selectedTaskId = controller.projectWorkbench.selectedTask(id)
    var choosingTask by remember(id) { mutableStateOf(false) }
    val feedback = draftSession.feedback
    var continuation by draftSession.continuation
    val liveInput by draftSession.liveInput
    val liveInputRequest by draftSession.liveInputRequest
    val liveInputSaving by draftSession.liveInputSaving
    val liveInputError by draftSession.liveInputError
    var managedFacts by remember(id) { mutableStateOf<JSONObject?>(null) }
    var runtimeRequest by remember(id) { mutableStateOf<String?>(null) }
    var operationQuery by remember(id) { mutableStateOf<ProjectOperationQuery?>(null) }
    var operationQueryError by remember(id) { mutableStateOf<Pair<String, String>?>(null) }
    val tasks = ProjectWorkbenchGuard.projectTasks(state.tasks, id, checkout.getString("projectId"))
    val task = ProjectWorkbenchGuard.selectedTask(tasks, selectedTaskId)
    fun acceptedProjectTask(taskId: String) {
        controller.releaseProjectStartConsent(client)
        controller.projectWorkbench.selectTask(id, taskId); controller.getTask(taskId)
        if (originalSubmission?.clears(goal) == true) goal = ""
        request = null; originalSubmission = null; unconfirmedSubmission = null; submissionQuery = null; client = UUID.randomUUID().toString(); newTask = false
    }
    val response = state.moduleResults["owner.startDevelopmentTask"]
    val submissionReceipt = state.moduleResultInputs["owner.startDevelopmentTask"]
    LaunchedEffect(response?.toString(), submissionReceipt?.toString(), request) {
        if (request != null && submissionReceipt != null && submissionReceipt.optString("_requestId") == request) {
            if (ProjectWorkbenchGuard.failedRequest(submissionReceipt, request)) {
                // Clear only this UI request. Keep the client identity and draft: an
                // uncertain admission must reconcile through the same native submission.
                unconfirmedSubmission = JSONObject().put("clientMessageId", client).put("status", "unconfirmed")
                request = null
            } else if (ProjectWorkbenchGuard.ownerReceipt(response, submissionReceipt, request) != null) {
                acceptedProjectTask(response!!.getString("taskId"))
            } else ProjectWorkbenchGuard.unconfirmedSubmission(response, submissionReceipt, request, client)?.let {
                unconfirmedSubmission = JSONObject(it.toString()); request = null
            }
        }
    }
    val queryResult = state.moduleResults["assistant.getSubmission"]
    val queryInput = state.moduleResultInputs["assistant.getSubmission"]
    LaunchedEffect(state.moduleResults["assistant.send"]?.toString(), state.moduleResultInputs["assistant.send"]?.toString(), queryResult?.toString(), queryInput?.toString(), liveInputRequest) {
        val pending = liveInput ?: return@LaunchedEffect
        val waiting = liveInputRequest ?: return@LaunchedEffect
        listOf("assistant.send", "assistant.getSubmission").forEach { name ->
            val input = state.moduleResultInputs[name] ?: return@forEach
            if (input.optString("_requestId") != waiting) return@forEach
            val receipt = state.moduleResults[name]
            if (!input.optBoolean("_failed") && receipt != null && receipt.optString("clientMessageId") == pending.optString("clientMessageId") && controller.acceptProjectLiveInputReceipt(id, receipt)) return@LaunchedEffect
            draftSession.liveInputRequest.value = null
            draftSession.liveInputError.value = if (input.optBoolean("_failed")) state.moduleErrors[name] ?: "原补充未取得完整回执；保留原消息身份，未另开任务" else "原输入尚未取得同task/run/epoch回执；请查询原输入，不自动重发"
        }
    }
    LaunchedEffect(queryResult?.toString(), queryInput?.toString(), submissionQuery) {
        if (unconfirmedSubmission != null && submissionQuery != null && queryInput != null && queryResult != null && queryInput.optString("_requestId") == submissionQuery && queryInput.opt("_failed") != true && queryResult.optString("clientMessageId") == client) {
            unconfirmedSubmission = JSONObject(queryResult.toString())
            queryResult.optString("taskId").takeIf { it.isNotBlank() }?.let { controller.getTask(it) }
        }
    }
    val matchedSubmissionTask = tasks.firstOrNull { it.id == unconfirmedSubmission?.optString("taskId") && it.raw.optJSONObject("developmentContext")?.optString("clientMessageId") == client }
    LaunchedEffect(unconfirmedSubmission?.optString("status"), matchedSubmissionTask?.id) {
        if (matchedSubmissionTask != null && unconfirmedSubmission?.optString("status") in setOf("accepted", "queued", "consumed")) acceptedProjectTask(matchedSubmissionTask.id)
    }
    val continued = state.moduleResults["owner.continueDevelopmentTask"]
    val continueInput = state.moduleResultInputs["owner.continueDevelopmentTask"]
    LaunchedEffect(continued?.toString(), continueInput?.toString(), continuation) {
        val submitted = continuation ?: return@LaunchedEffect
        if (ProjectWorkbenchGuard.ownerReceipt(continued, continueInput, submitted.requestId, submitted.taskId) != null) {
            if (feedback[submitted.taskId] == submitted.text) feedback.remove(submitted.taskId)
            controller.getTask(submitted.taskId); continuation = null
        } else if (ProjectWorkbenchGuard.failedRequest(continueInput, submitted.requestId, submitted.taskId)) {
            controller.getTask(submitted.taskId); continuation = null
        }
    }
    LaunchedEffect(id, selectedTaskId, task?.id) { if (selectedTaskId == null && task != null) controller.projectWorkbench.selectTask(id, task.id); (selectedTaskId ?: task?.id)?.let(controller::getTask) }
    fun describeManagedRuntime() { runtimeRequest = controller.requestFeature("local", "project.runtime.describe", JSONObject().put("runtimeId", ProjectWorkbenchGuard.managedRuntime)) }
    val runtimeResponse = state.moduleResults["local.project.runtime.describe"]
    val runtimeInput = state.moduleResultInputs["local.project.runtime.describe"]
    LaunchedEffect(runtimeResponse?.toString(), runtimeInput?.toString()) {
        if (runtimeRequest != null && runtimeInput != null && runtimeInput.optString("_requestId") == runtimeRequest && runtimeResponse?.optString("runtimeId") == ProjectWorkbenchGuard.managedRuntime && !runtimeInput.optBoolean("_failed")) managedFacts = runtimeResponse
    }
    val latestState by rememberUpdatedState(state)
    val latestProjectTask by rememberUpdatedState(task)
    fun refreshOriginalProject(current: UiTask) {
        controller.getTask(current.id)
        if (operationQuery != null || "local.project.poll" in latestState.moduleBusy) return
        val original = ProjectWorkbenchGuard.originalPollRequest(current) ?: return
        operationQueryError = null
        operationQuery = ProjectOperationQuery(controller.requestFeature("local", "project.poll", original), current.id, original.getJSONObject("binding").getString("operationId"))
    }
    val operationResponse = state.moduleResults["local.project.poll"]
    val operationInput = state.moduleResultInputs["local.project.poll"]
    LaunchedEffect(operationResponse?.toString(), operationInput?.toString(), operationQuery) {
        val query = operationQuery ?: return@LaunchedEffect
        val delivered = operationInput ?: return@LaunchedEffect
        if (delivered.optString("_requestId") != query.requestId) return@LaunchedEffect
        if (delivered.optBoolean("_failed")) operationQueryError = query.taskId to (state.moduleErrors["local.project.poll"] ?: "原执行回执暂未取得；未重派项目命令。")
        else {
            val binding = operationResponse?.optJSONObject("binding")
            if (binding == null || binding.optString("taskId") != query.taskId || binding.optString("operationId") != query.operationId) operationQueryError = query.taskId to "原执行回执身份不一致；未采纳或重派。"
        }
        controller.getTask(query.taskId)
        operationQuery = null
    }
    val pageContext = LocalContext.current
    val pageLifecycle = remember(pageContext) { generateSequence(pageContext) { (it as? ContextWrapper)?.baseContext?.takeUnless { base -> base === it } }.filterIsInstance<LifecycleOwner>().firstOrNull()?.lifecycle }
    LaunchedEffect(id, task?.id, task?.status) {
        describeManagedRuntime()
        while (task?.status in setOf("preparing", "running", "stopping")) {
            delay(1800)
            if (pageLifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false) {
                latestProjectTask?.takeIf { it.id == task!!.id }?.let { refreshOriginalProject(it) }
                if ("local.project.runtime.describe" !in latestState.moduleBusy) describeManagedRuntime()
            }
        }
    }
    val runtimes = state.feature("local").optJSONArray("projectRuntimes").objects()
    val selectedRuntime = runtimes.firstOrNull { it.optString("runtimeId") == runtimeId }
    val phoneRuntime = managedFacts ?: runtimes.firstOrNull { it.optString("runtimeId") == ProjectWorkbenchGuard.managedRuntime }
        ?: state.feature("local").optJSONObject("projectRuntime")?.takeIf { it.optString("runtimeId") == ProjectWorkbenchGuard.managedRuntime }
    fun startProjectTask(text: String, runtime: String, consumeGoalDraft: Boolean) {
        if (unconfirmedSubmission != null || originalSubmission != null) return
        val frozen = JSONObject().put("checkoutId", id).put("projectId", checkout.getString("projectId")).put("expectedSourceDigest", inspection!!.getString("sourceDigest")).put("runtime", runtime).put("goal", text.trim()).put("clientMessageId", client)
        originalSubmission = ProjectGoalSubmission(client, frozen.toString(), if (consumeGoalDraft) text else null, useProjectMemory)
        request = controller.requestProjectStart(JSONObject(frozen.toString()), useProjectMemory)
    }
    if (agentSurface) {
    var goalExamples by remember(checkout.optString("id")) { mutableStateOf(false) }
    if (showRuntime && pythonProject && runtimeId == ProjectWorkbenchGuard.managedRuntime) WorkbenchCaption("Python 依赖装在项目自己的 .venv。首次安装可能联网；测试和运行结果见「运行」，生成的报告见「预览」。")
    inspection?.optJSONArray("warnings")?.let { warnings -> for (index in 0 until warnings.length()) WorkbenchCaption(warnings.optString(index)) }
    NativeProjectToolPreparation(phoneRuntime, task, enabled = inspection != null && request == null && unconfirmedSubmission == null && "owner.startDevelopmentTask" !in state.moduleBusy && tasks.none { it.status in ProjectWorkbenchGuard.activeStatuses || unresolvedOperations(it).isNotEmpty() },
        start = { startProjectTask(ProjectWorkbenchGuard.preparationGoal, ProjectWorkbenchGuard.managedRuntime, consumeGoalDraft = false) }, refresh = { describeManagedRuntime() })
    if (ProjectWorkbenchGuard.failedRequest(runtimeInput, runtimeRequest)) state.moduleErrors["local.project.runtime.describe"]?.let { Text(it, color = Danger, fontSize = 13.sp) }
    if (unconfirmedSubmission != null) {
        Text("这次项目启动尚未确认。目标与原提交身份已保留，先核对原提交，不重新发送。", color = Danger, fontSize = 13.sp, lineHeight = 21.sp)
        TextButton(onClick = { submissionQuery = controller.requestFeature("assistant", "getSubmission", JSONObject().put("clientMessageId", client)) }, enabled = "assistant.getSubmission" !in state.moduleBusy, modifier = Modifier.testTag("project_check_original_submission")) { Text("核对这次原提交") }
        tasks.firstOrNull { it.raw.optJSONObject("developmentContext")?.optString("clientMessageId") == client }?.let { original -> TextButton(onClick = { controller.focusTask(original.id) }) { Text("查看匹配的原项目任务") } }
        if (ProjectWorkbenchGuard.failedRequest(queryInput, submissionQuery)) {
            state.moduleErrors["assistant.getSubmission"]?.let { Text(it, color = Danger, fontSize = 13.sp) }
            originalSubmission?.let { original ->
                TextButton(onClick = {
                    unconfirmedSubmission = null; submissionQuery = null
                    request = controller.requestProjectStart(original.input(), original.useProjectMemory, retryOriginal = true)
                }, enabled = request == null && "owner.startDevelopmentTask" !in state.moduleBusy, modifier = Modifier.testTag("project_retry_original_submission")) { Text("按原内容重试这次登记") }
                WorkbenchCaption("只核对这次提交；后续新写的草稿不会随之发送。")
            }
        }
    }
    if (task == null || newTask) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("这次只读取本项目记忆", fontSize = 14.sp, color = Ink)
                WorkbenchCaption(if (useProjectMemory) "开始时冻结当前最多64条已审阅项目记忆及其原来源，供本次云模型读取；不混入主人全局记忆。忘记或撤回会立即停止旧片段继续使用。" else "默认仍使用主人全局私人记忆。开启后仅选本项目；不授予团队或群记忆权限。")
            }
            Switch(useProjectMemory, { useProjectMemory = it }, enabled = request == null && originalSubmission == null && unconfirmedSubmission == null && "owner.startDevelopmentTask" !in state.moduleBusy, modifier = Modifier.testTag("project_memory_opt_in"))
        }
        AffectComposerFrame(
            modifier = Modifier.padding(top = 12.dp),
            editor = {
                androidx.compose.foundation.text.BasicTextField(value = goal, onValueChange = { goal = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 168.dp)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    maxLines = 6,
                    textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 24.sp, color = Ink),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink),
                    decorationBox = { inner -> Box {
                        if(goal.isEmpty()) Text("描述要实现或修复的内容…", fontSize = 16.sp, lineHeight = 24.sp, color = SecondaryInk)
                        inner()
                    } })
            },
            toolbar = {
                AffectGlyph("spark", color = SecondaryInk)
                Spacer(Modifier.width(8.dp))
                Text("项目 Agent", fontSize = 13.sp, color = SecondaryInk)
                Spacer(Modifier.weight(1f))
                AffectSendButton(label = if(request != null) "正在登记项目任务" else "开始这个项目任务",
                    onClick = { startProjectTask(goal, runtimeId, consumeGoalDraft = true) },
                    enabled = goal.isNotBlank() && goal.length <= 12000 && inspection != null && request == null && unconfirmedSubmission == null && "owner.startDevelopmentTask" !in state.moduleBusy,
                    submitting = request != null,
                    modifier = Modifier.testTag("project_start_task"))
            },
            footer = {
                TextButton(onClick = { showRuntime = !showRuntime }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(selectedRuntime?.optString("name") ?: "这台手机", fontSize = 13.sp, color = SecondaryInk)
                    AffectGlyph("chevron", Modifier.size(12.dp), SecondaryInk)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { goalExamples = !goalExamples }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(if(goalExamples) "收起示例" else "目标示例", fontSize = 13.sp, color = SecondaryInk)
                }
            }
        )
        if (goalExamples) {
        if (nodeProject && runtimeId == ProjectWorkbenchGuard.managedRuntime) TextButton(onClick = { goal = ProjectWorkbenchGuard.ownedServiceGoal }, enabled = goal.isBlank() && request == null && originalSubmission == null && unconfirmedSubmission == null, modifier = Modifier.testTag("project_owned_service_goal")) { Text("填入开发服务调试目标") }
        if (nodeProject && runtimeId == ProjectWorkbenchGuard.managedRuntime && inspection?.optJSONArray("dependencies").objects().any { it.optString("name") == "vite" }) TextButton(onClick = { goal = ProjectWorkbenchGuard.devWatchGoal }, enabled = goal.isBlank() && request == null && originalSubmission == null && unconfirmedSubmission == null, modifier = Modifier.testTag("project_dev_watch_goal")) { Text("填入边运行边修改目标") }
        if (nodeProject && runtimeId == ProjectWorkbenchGuard.managedRuntime && inspection?.optJSONArray("dependencies").objects().any { it.optString("name") == "vite" }) TextButton(onClick = { goal = ProjectWorkbenchGuard.retainedDevWatchGoal }, enabled = goal.isBlank() && request == null && originalSubmission == null && unconfirmedSubmission == null, modifier = Modifier.testTag("project_retained_dev_goal")) { Text("填入30分钟保留试用目标") }
        if (pythonProject && runtimeId == ProjectWorkbenchGuard.managedRuntime) TextButton(onClick = { goal = ProjectWorkbenchGuard.pythonProjectGoal }, enabled = goal.isBlank() && request == null && originalSubmission == null && unconfirmedSubmission == null, modifier = Modifier.testTag("project_python_goal")) { Text("填入 Python 依赖、测试与运行目标") }
        if (runtimeId == ProjectWorkbenchGuard.managedRuntime) TextButton(onClick = { goal = ProjectWorkbenchGuard.commandGoal }, enabled = goal.isBlank() && request == null && originalSubmission == null && unconfirmedSubmission == null, modifier = Modifier.testTag("project_command_goal")) { Text("填入精确命令或交互终端目标") }
        }
        if (showRuntime) {
            if (runtimes.isEmpty()) WorkbenchCaption("本机编程环境尚未准备，产品会显示缺少的实际工具。")
            runtimes.forEach { runtime -> FilterChip(selected = runtimeId == runtime.optString("runtimeId"), onClick = { runtimeId = runtime.optString("runtimeId") }, label = { Text(runtime.optString("name", runtime.optString("runtimeId"))) }) }
        }
        if(showRuntime) selectedRuntime?.optString("message")?.takeIf { it.isNotBlank() }?.let { WorkbenchCaption(it) }
        if (showRuntime && runtimeId == "managed_process_v1" && selectedRuntime?.optBoolean("canExecute") != true) WorkbenchCaption("这台手机的编程工具尚未准备。可以先读取和编辑项目文件；工具准备完成后，在原项目任务继续构建与运行。")
    }
    state.moduleErrors["owner.startDevelopmentTask"]?.let { Text(it, color = Danger, fontSize = 13.sp) }
    }
    if (tasks.size > 1 || selectedTaskId != null && task == null) {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            OutlinedButton(onClick = { choosingTask = true }, modifier = Modifier.fillMaxWidth().testTag("project_task_selector")) { Text(task?.goal ?: "选择项目任务", maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Text("⌄") }
            DropdownMenu(choosingTask, { choosingTask = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                tasks.forEach { item -> DropdownMenuItem(text = { Column { Text(item.goal, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp); WorkbenchCaption(taskStatus(item.status)) } }, onClick = { controller.projectWorkbench.selectTask(id, item.id); choosingTask = false; newTask = false; controller.closeProjectPreview(); controller.closeProjectLivePreview() }) }
            }
        }
    }
    var repairNotice by remember(id, task?.id) { mutableStateOf<String?>(null) }
    task?.let { current ->
        var taskDetails by remember(current.id) { mutableStateOf(false) }
        Text(current.raw.optString("goal"), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = if (agentSurface) 6 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(taskStatus(current.status) + " · " + if (current.raw.optJSONObject("developmentContext")?.optString("runtime") == ProjectWorkbenchGuard.managedRuntime) "这台手机" else "Termux", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { taskDetails = !taskDetails }) { Text(if (taskDetails) "收起详情" else "详情") }
        }
        if (taskDetails) {
            SelectionContainer { Text("task ${current.id}\nrun ${current.raw.optString("runId")}\nepoch ${current.raw.optInt("coordinatorEpoch")}\ncheckout $id", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { controller.focusTask(current.id) }) { Text("任务完整记录") }
        }
        var summaryExpanded by remember(current.id) { mutableStateOf(false) }
        val summary = current.raw.optString("summary")
        if (!previewSurface && summary.isNotBlank()) {
            Text(summary, fontSize = 13.sp, lineHeight = 21.sp, color = SecondaryInk, maxLines = if (summaryExpanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            if (summary.length > 90 || summary.count { it == '\n' } > 2) TextButton(onClick = { summaryExpanded = !summaryExpanded }) { Text(if (summaryExpanded) "收起任务说明" else "完整任务说明") }
        }
        if (current.status in setOf("preparing", "running")) LinearProgressIndicator(Modifier.fillMaxWidth())
        val pendingApprovals = current.raw.optJSONArray("approvals").objects().filter { it.optString("state") == "pending" && it.optString("tool") == "project_execute" }
        if (!agentSurface && pendingApprovals.isNotEmpty()) OutlinedButton(onClick = { controller.projectWorkbench.selectView(checkout.getString("projectId"), ProjectWorkbenchFacet.Agent.index) }) { Text("有 ${pendingApprovals.size} 项操作等待确认 · 到 Agent 核对") }
        if (agentSurface) pendingApprovals.forEach { approval ->
            var reviewed by remember(approval.optString("id")) { mutableStateOf(false) }
            val arguments = approval.optJSONObject("arguments") ?: JSONObject()
            val plan = arguments.optJSONObject("plan")
            val capability = approval.optJSONObject("capability")
            val nativeReview = capability?.optJSONObject("nativeProjectPlan")?.takeIf { arguments.has("planRef") && it.optString("planDigest") == arguments.optString("planDigest") }
            val step = plan?.optJSONArray("steps").objects().firstOrNull { it.optString("id") == arguments.optString("stepId") }
                ?: nativeReview?.optJSONObject("step")?.takeIf { it.optString("id") == arguments.optString("stepId") }?.let { JSONObject(it.toString()).put("payload", it.getJSONObject("request")) }
            Text(step?.optString("label") ?: "项目操作需要你确认", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
            if (nativeReview != null) WorkbenchCaption("只执行本次列出的步骤。请展开核对文件内容与参数后批准。")
            step?.optJSONObject("payload")?.let { payload ->
                if (payload.has("path")) WorkbenchCaption(payload.optString("path"))
                payload.optJSONArray("files").objects().forEach { file ->
                    val encoded = file.optString("encoding") == "base64"
                    val copyDirection = if (encoded) file.optString("copyDirection") else ""
                    val moveRole = if (encoded) file.optString("moveRole") else ""
                    val label = if (moveRole.isNotEmpty()) { if (moveRole == "source") "重命名源（移走此路径）" else "重命名目标（保留同一原字节）" } else if (encoded) when (copyDirection) { "forward" -> "复制原字节为新文件"; "revert" -> "删除原复制的新文件"; else -> "原字节覆盖（非文字）" } else when (file.optString("action")) { "mkdir" -> "创建目录（非空文件）"; "create" -> "创建"; "delete" -> "删除"; else -> "精确修改" }
                    WorkbenchCaption("$label ${file.optString("path")}")
                    if (file.optString("action") == "mkdir") WorkbenchCaption("目录有独立元数据核对；只有明确createParents才建缺失父目录。已存在目录不重写；部分创建或未知不自动回滚、重试或rmdir。不自动回写正式版本库。")
                    if (moveRole == "source") WorkbenchCaption("这是当前副本内重命名，源路径会消失；目标须不存在，父目录须已有。不自动改正式版本库，也不宣称跨路径原子CAS；未知只核对原操作，不重放。")
                    if (encoded && moveRole.isEmpty()) WorkbenchCaption(when (copyDirection) {
                        "forward" -> "只复制原清单中固定源文件的完整字节，目标必须不存在；请核对copiedFrom路径、大小与原SHA。支持二进制，不转换原件；不含运行、导出或分享授权。"
                        "revert" -> "只删除这次原操作新复制且afterSHA仍未变化的目标，源文件保留；后续变化时拒绝，不自动撤销或恢复其它文件。"
                        else -> "仅列明的等长hex范围，可能破坏文件格式；请展开核对旧/新字节、byteOffset和原SHA。不插入、扩展或截断；不是执行许可。"
                    })
                }
                if (step?.optString("localCommand") == "patchFiles" && payload.optJSONArray("files").objects().none { it.optString("action") == "mkdir" }) WorkbenchCaption("文件逐个保存；中途失败时，已保存的文件不会自动回滚。后续修改与撤销冲突时会保留新内容。")
                if (payload.has("executable")) SelectionContainer { Text(payload.optString("executable") + " " + payload.optJSONArray("argv"), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                TextButton(onClick = { reviewed = !reviewed }) { Text(if (reviewed) "收起具体内容" else "核对具体文件与参数") }
                if (reviewed) {
                    SelectionContainer { Text(if (payload.has("content")) payload.optString("content") else payload.toString(2), fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState())) }
                    capability?.let { scope ->
                        WorkbenchCaption("执行位置、源版本与批准范围")
                        SelectionContainer { Text(scope.toString(2), fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState())) }
                    }
                }
            }
            if (step?.optString("localCommand") == "freezeProjectPackage") {
                WorkbenchCaption("只保存列明的实际项目文件为固定包，不运行、不导出或分享；当前服务须先停止并确认释放。")
                OutlinedButton(onClick = { controller.projectPackages.reviewDelivery(current.id, approval.getString("id")) }, enabled = !controller.projectPackages.state.busy, modifier = Modifier.testTag("project_review_task_package")) { Text("核对完整包含与排除清单") }
            }
            step?.optJSONObject("effects")?.let { effects -> WorkbenchCaption((if (effects.optBoolean("writes")) "本次可能写入文件" else "本次方案未声明写入文件") + if (effects.optBoolean("networkMayBeUsed")) " · 可能联网" else " · 未声明联网不代表权限隔离") }
            val liveScope = capability?.optJSONObject("ownedService")?.takeIf { it.optBoolean("subsequentSourceExecution") }
            val terminalScope = capability?.optJSONObject("ownedService")?.takeIf { it.optBoolean("interactiveInputExplicitlyIncluded") }
            if (liveScope != null) {
                Text("本次允许服务运行中修改这些文件", color = Danger, fontSize = 13.sp)
                WorkbenchCaption("可改：" + liveScope.optJSONArray("editablePaths").let { paths -> (0 until (paths?.length() ?: 0)).joinToString("、") { paths!!.optString(it) } } + "。配置、依赖锁、脚本和新文件不包含。此次服务最长5分钟，任务结束会停止。")
            } else if (terminalScope != null) {
                Text("启动并允许这次终端继续接收输入", color = Danger, fontSize = 13.sp)
                WorkbenchCaption("仅上述命令与工作目录。本轮最多 ${terminalScope.optLong("inputByteLimit")} 字节普通输入、${terminalScope.optInt("inputRequestLimit")} 次动作，最长 ${terminalScope.optLong("timeoutMillis") / 1000} 秒；后续输入会继续执行程序。任务结束会停止。不要输入密码或令牌。")
                WorkbenchCaption("标准输出和错误合在一份终端日志中。EOF 只发送终端行模式的结束符，不是关闭任意输入流；此视图不提供全屏 TUI 渲染。")
            } else WorkbenchCaption("本次批准只适用于上面的具体操作。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { controller.approveCapability(current.id, approval.getString("id"), true) }, enabled = reviewed && step != null) { Text(if (liveScope != null) "批准启动与限定热改" else if (terminalScope != null) "批准启动与限定输入" else "批准这一次") }; TextButton(onClick = { controller.approveCapability(current.id, approval.getString("id"), false) }) { Text("拒绝") } }
        }
        if (current.status in setOf("preparing", "running", "paused", "reconciling")) TextButton(onClick = { controller.stopTask(current.id) }) { Text("停止当前任务", color = Danger) }
        operationQueryError?.takeIf { it.first == current.id }?.let { WorkbenchCaption(it.second) }
        if (runSurface) NativeProjectExecutionLog(current, onRefresh = { refreshOriginalProject(current); describeManagedRuntime() }, onFullHistory = { controller.focusTask(current.id) }, packageBusy = controller.projectPackages.state.busy, onPackageOpen = controller.projectPackages::openFromTask, onRepair = { text ->
            val prior = feedback[current.id].orEmpty()
            val marker = "\n[草稿只加入部分错误日志；完整回执保留在原任务]"
            val remaining = 8000 - prior.length - 2
            when {
                prior.isBlank() -> { feedback[current.id] = text; repairNotice = null }
                prior.contains(text) -> repairNotice = "这次错误已在修改草稿中。"
                prior.length + text.length + 2 <= 8000 -> { feedback[current.id] = prior + "\n\n" + text; repairNotice = null }
                remaining > marker.length + 100 -> { feedback[current.id] = prior + "\n\n" + text.take(remaining - marker.length) + marker; repairNotice = null }
                else -> repairNotice = "已保留原修改草稿；空间不足，请先整理草稿再加入错误。"
            }
            controller.projectWorkbench.selectView(checkout.getString("projectId"), ProjectWorkbenchFacet.Agent.index)
        })
        if (agentSurface) {
            // Only link to recorded outcomes of this selected task. Navigation grants no execution.
            val savedPreview = current.raw.optJSONArray("developmentPreviews").objects().lastOrNull()?.takeIf {
                it.optBoolean("persisted") && it.optString("previewId").isNotBlank() &&
                    it.optString("checkoutId") == id && it.optString("projectId") == checkout.getString("projectId")
            }
            val savedPackage = current.raw.optJSONArray("operations").objects().any { operation ->
                ProjectWorkbenchGuard.executionReceipt(current, operation)?.let { actual ->
                    actual.optString("intent") == "freeze_package" && actual.optBoolean("artifactReady") &&
                        actual.optBoolean("receiptReceived") && actual.optString("status") == "completed"
                } == true
            }
            if (savedPreview != null || savedPackage) {
                HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 12.dp), color = Line)
                Text("当前成果", style = MaterialTheme.typography.titleSmall)
                savedPreview?.let { recorded ->
                    TextButton(onClick = { controller.projectWorkbench.selectView(checkout.getString("projectId"), ProjectWorkbenchFacet.Preview.index) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("project_saved_preview_navigation")) {
                        AffectGlyph("file", color = SecondaryInk)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(recorded.optString("entryPath"), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (recorded.optString("sourceDigest") == inspection?.optString("sourceDigest")) "已保存预览 · 查看" else "较早版本预览 · 查看",
                                style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                        }
                        AffectGlyph("chevron-right", color = SecondaryInk)
                    }
                }
                if (savedPackage) TextButton(onClick = { controller.projectWorkbench.selectView(checkout.getString("projectId"), ProjectWorkbenchFacet.Run.index) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("project_saved_package_navigation")) {
                    AffectGlyph("file", color = SecondaryInk)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text("固定项目包已保存", style = MaterialTheme.typography.bodyMedium)
                        Text("到运行记录核对原成果", style = MaterialTheme.typography.bodySmall, color = SecondaryInk)
                    }
                    AffectGlyph("chevron-right", color = SecondaryInk)
                }
            }
            TextButton(onClick = { controller.projectWorkbench.selectView(checkout.getString("projectId"), ProjectWorkbenchFacet.Run.index) }) { Text("查看运行状态和日志") }
        }
        if (previewSurface) ProjectLivePreviewSection(current, id, controller)
        if (agentSurface && current.status in setOf("preparing", "running")) {
            val text = feedback[current.id].orEmpty()
            OutlinedTextField(text, { if (it.length <= 8000) feedback[current.id] = it }, label = { Text("继续补充要求（不停止服务）") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth().testTag("project_live_feedback"))
            WorkbenchCaption("这会补充到正在进行的任务，不会另开任务；执行权限仍按你已确认的范围。")
            OutlinedButton(onClick = { controller.sendProjectLiveInput(id, current, text) }, enabled = text.isNotBlank() && liveInput == null && !liveInputSaving && liveInputRequest == null, modifier = Modifier.testTag("project_send_live_feedback")) { Text(if (liveInputSaving) "正在保留输入…" else "补充到当前任务") }
        }
        if (agentSurface) liveInput?.let { pending ->
            val target = pending.getJSONObject("target")
            WorkbenchCaption("上次补充正在核对，文字已保留。先查询结果，再决定是否重新提交。")
            var pendingDetails by remember(pending.optString("identity")) { mutableStateOf(false) }
            TextButton(onClick = { pendingDetails = !pendingDetails }) { Text(if (pendingDetails) "收起补充详情" else "查看上次补充") }
            if (pendingDetails) SelectionContainer { Text(pending.getString("message") + "\ntask " + target.optString("taskId"), fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { controller.queryProjectLiveInput(id) }, enabled = liveInputRequest == null && !liveInputSaving) { Text("查询提交结果") }
                if (target.optString("taskId") == current.id && target.optString("runId") == current.raw.optString("runId") && current.status in setOf("preparing", "running")) TextButton(onClick = { controller.sendProjectLiveInput(id, current, pending.getString("message"), retryOriginal = true) }, enabled = liveInputRequest == null && !liveInputSaving) { Text("重新提交这条补充") }
            }
        }
        if (agentSurface) liveInputError?.let { Text(it, color = Danger, fontSize = 13.sp) }
        val previews = current.raw.optJSONArray("developmentPreviews").objects()
        if (previewSurface) previews.lastOrNull()?.let { preview ->
            WorkbenchCaption((if (preview.optString("sourceDigest") == inspection?.optString("sourceDigest")) "已保存预览：" else "较早版本预览：") + "${preview.optString("entryPath")} · ${preview.optInt("resourceCount")}个资源")
            OutlinedButton(onClick = { controller.openProjectPreview(current.id, id, preview.getString("previewId")) }) { Text(ProjectWorkbenchGuard.previewAction(preview)) }
        }
        if (agentSurface && current.status !in setOf("preparing", "running", "stopping")) {
            val text = feedback[current.id].orEmpty()
            val sending = continuation?.taskId == current.id
            OutlinedTextField(text, { if (it.length <= 8000) feedback[current.id] = it }, label = { Text("看过结果后，继续修改什么") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth().testTag("project_continue_draft"), enabled = !sending)
            repairNotice?.let { WorkbenchCaption(it) }
            OutlinedButton(onClick = { continuation = ProjectContinuationRequest(controller.requestOwnerAction("continueDevelopmentTask", JSONObject().put("taskId", current.id).put("feedback", text.trim())), current.id, text) }, enabled = text.isNotBlank() && !sending && continuation == null && unconfirmedSubmission == null && "owner.continueDevelopmentTask" !in state.moduleBusy && ProjectWorkbenchGuard.canContinue(current), modifier = Modifier.testTag("project_continue_task")) { Text(if (sending) "等待原任务确认…" else "继续原项目任务") }
            if (!ProjectWorkbenchGuard.canContinue(current)) WorkbenchCaption("先处理原任务的审批或未决操作；现有文件、日志和修改草稿保留。")
            if (continueInput?.optString("taskId") == current.id) state.moduleErrors["owner.continueDevelopmentTask"]?.let { Text(it, color = Danger, fontSize = 13.sp) }
            TextButton(onClick = { newTask = !newTask }) { Text(if (newTask) "收起新任务" else "为此项目另开任务") }
        }
    }
    if (task == null && !agentSurface) {
        Text(if (runSurface) "还没有这个项目的运行记录" else "还没有这个项目任务的预览", style = MaterialTheme.typography.titleMedium)
        WorkbenchCaption(if (runSurface) "到 Agent 说明要完成什么，工具会在实际执行后留下状态和日志。" else "运行或构建后，Agent 会把可打开的页面留在这里。切换页面不会自动运行项目。")
        OutlinedButton(onClick = { controller.projectWorkbench.selectView(checkout.getString("projectId"), ProjectWorkbenchFacet.Agent.index) }) { Text("到 Agent 继续") }
    } else if (!agentSurface) TextButton(onClick = { controller.projectWorkbench.selectView(checkout.getString("projectId"), ProjectWorkbenchFacet.Agent.index) }) { Text("回到 Agent 继续修改") }
    val preview = controller.projectPreview?.takeIf { it.checkoutId == id && controller.projectPreviewTaskId == task?.id }
    val previewTask = controller.projectPreviewTaskId
    if (previewSurface && preview != null && previewTask != null) {
        key(preview.identity, previewTask) {
            val context = LocalContext.current
            var web by remember(preview.identity) { mutableStateOf<ProjectBundleWebView?>(null) }
            var renderError by remember(preview.identity) { mutableStateOf<String?>(null) }
            var resourcesOpen by remember(preview.identity) { mutableStateOf(false) }
            val lifecycle = remember(context) { generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeUnless { base -> base === it } }.filterIsInstance<LifecycleOwner>().firstOrNull()?.lifecycle }
            DisposableEffect(preview.identity, lifecycle) {
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { web?.release(); web = null; controller.closeProjectPreview() } }
                lifecycle?.addObserver(observer)
                onDispose { lifecycle?.removeObserver(observer); web?.release(); web = null }
            }
            Text(preview.entryPath, style = MaterialTheme.typography.titleSmall)
            WorkbenchCaption("这是已采集的版本，不随编辑变化。修改后请重新构建或运行，再更新预览。")
            AndroidView(factory = { context -> ProjectBundleWebView(context, preview, object : ProjectBundleWebView.Events {
                override fun error(message: String) { renderError = message; controller.recordProjectPreviewConsole(preview, previewTask, "RENDER_ERROR", message, "", 0) }
                override fun console(level: String, text: String, source: String, line: Int) { controller.recordProjectPreviewConsole(preview, previewTask, level, text, source, line) }
            }).also { web = it } }, modifier = Modifier.fillMaxWidth().height(430.dp))
            renderError?.let { Text(it, color = Danger, fontSize = 13.sp, lineHeight = 21.sp) }
            TextButton(onClick = { resourcesOpen = !resourcesOpen }) { Text(if (resourcesOpen) "收起预览详情" else "预览详情与资源") }
            if (resourcesOpen) {
                WorkbenchCaption("页面与资源来自同次采集。外部网络和 Native 接口关闭；文件 SHA 与 MIME 如下。")
                preview.manifest().objects().forEach { resource ->
                Text(resource.optString("path"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                WorkbenchCaption("${resource.optString("mimeType")} · ${resource.optLong("bytes")} 字节 · ${resource.optString("sha256").take(10)}")
            }
            }
            TextButton(onClick = { controller.closeProjectPreview(); controller.projectWorkbench.selectView(checkout.getString("projectId"), ProjectWorkbenchFacet.Agent.index) }) { Text("关闭预览，继续修改") }
        }
    }
    DisposableEffect(id, task?.id) { onDispose { controller.closeProjectPreview() } }
}

internal data class ProjectContinuationRequest(val requestId: String, val taskId: String, val text: String)
@Composable internal fun WorkbenchCaption(text: String) { if (text.isNotBlank()) Text(text, color = SecondaryInk, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(vertical = 5.dp)) }
