package dev.pocketengine.ui

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.pocketengine.features.local.project.ProjectFileDraft
import org.json.JSONArray
import org.json.JSONObject

/** Identity fences are shared by the real leaf and its focused JVM tests. */
internal object LocalWorkbenchGuard {
    @JvmStatic fun result(response: JSONObject?, input: JSONObject?, requestId: String?, checkout: String? = null, path: String? = null): JSONObject? {
        if (response == null || input == null || requestId.isNullOrBlank() || input.optString("_requestId") != requestId) return null
        if (checkout != null && (input.optString("checkoutId") != checkout || response.optString("checkoutId", response.optString("id")) != checkout)) return null
        if (path != null && (input.optString("path") != path || response.optString("path") != path)) return null
        return response
    }
    @JvmStatic fun importPayload(review: JSONObject): JSONObject {
        require(!dev.pocketengine.features.local.project.ProjectBinaryImport.isReview(review)) { "原字节审阅须走原字节确认，不借文字参数导回" }
        if (review.optBoolean("baselineNeedsRefresh")) return dev.pocketengine.features.local.project.ProjectTextImport.reconciliationPayload(review)
        val version = review.opt("expectedVersion")
        require((version is Int || version is Long) && (version as Number).toLong() in 0..Int.MAX_VALUE.toLong()) { "缺少已审项目版本" }
        val hash = review.optString("expectedHash")
        require(hash == "absent" || hash.matches(Regex("[a-f0-9]{64}"))) { "缺少已审本地摘要" }
        require(review.optBoolean("importable") && !review.optBoolean("truncated")) { "请先完整审阅可导入的差异" }
        return JSONObject().put("checkoutId", review.getString("checkoutId")).put("path", review.getString("path"))
            .put("expectedHash", hash).put("expectedVersion", version)
    }
    @JvmStatic fun argv(executable: String, arguments: String): JSONArray {
        require(executable.isNotBlank()) { "请选择工具" }
        val args = arguments.split('\n').filter { it.isNotEmpty() }
        require(args.size <= 39 && args.all { it.length <= 2048 && !it.contains('\u0000') }) { "参数过多或过长" }
        return JSONArray().put(executable).also { array -> args.forEach { array.put(it) } }
    }
    @JvmStatic fun terminal(status: String, termux: Boolean): Boolean = if (termux) status in setOf("completed", "failed") else status in setOf("completed", "failed", "cancelled", "timed_out")
    @JvmStatic fun pollable(status: String, termux: Boolean): Boolean = if (termux) !terminal(status, true) else status in setOf("queued", "running", "stopping")
}

private data class LocalJobRef(val id: String, val termux: Boolean)
private data class LocalCommandConfirmation(val command: String, val input: JSONObject, val description: String)

/** Mounted inside ConnectionCenter's existing native scrolling page. No replacement host or database. */
@Composable
internal fun NativeLocalWorkbench(state: PocketUiState, controller: PocketWorldController) {
    val local = state.feature("local")
    val checkouts = local.optJSONArray("checkouts").objects().sortedByDescending { it.optLong("createdAt") }
    var projectId by rememberSaveable { mutableStateOf(state.projectId) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var prepareRequest by remember { mutableStateOf<String?>(null) }
    var switching by remember { mutableStateOf(true) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var selectedJob by remember { mutableStateOf<LocalJobRef?>(null) }
    var materializeRequest by remember { mutableStateOf<String?>(null) }
    val prepared = LocalWorkbenchGuard.result(state.moduleResults["local.prepare"], state.moduleResultInputs["local.prepare"], prepareRequest)
    val materialized = LocalWorkbenchGuard.result(state.moduleResults["local.termux.materialize"], state.moduleResultInputs["local.termux.materialize"], materializeRequest)
    LaunchedEffect(prepared) { prepared?.optString("id")?.takeIf { it.isNotBlank() }?.let { selectedId = it; switching = false; mode = 0; selectedJob = null; prepareRequest = null } }
    LaunchedEffect(materialized) { materialized?.optString("id")?.takeIf { it.isNotBlank() }?.let { selectedId = it; switching = false; mode = 1; selectedJob = null; materializeRequest = null } }
    val selected = checkouts.firstOrNull { it.optString("id") == selectedId }
    LocalCaption("先选项目副本，再执行与核对。修改只有在审阅差异后才导回原项目；这里不自动运行高权限 Shell。")
    local.optJSONObject("legacyReceiptRecovery")?.let { recovery ->
        if (recovery.optBoolean("originalInventoryUnconfirmed") || recovery.optInt("coldUnconfirmedRecords") > 0) LocalCaption(recovery.optString("message"))
    }
    if (selected != null) {
        Text(selected.optString("name"), fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
        LocalCaption("副本 ${selected.optString("id").take(8)} · 原项目 ${state.projects.find { it.id == selected.optString("projectId") }?.name ?: selected.optString("projectId").take(8)}" + if (selected.has("collectionOperationId")) " · Termux 取回结果" else "")
    }
    ProjectWorkbenchDisclosure("工作副本", switching || selected == null, { switching = !switching }, if (selected == null) "选择项目后开始" else "切换或准备副本") {
        LocalCaption("在副本中工作，核对差异后才导回原项目。手动命令始终需要单独确认。")
        if (state.projects.isEmpty()) LocalCaption("还没有项目。请先在「项目」页创建并保存文件，再到这里准备工作副本。")
        else {
            LocalChoice("来源项目", state.projects.map { it.id to it.name }, projectId, { projectId = it })
            PocketPressButton(onClick = { projectId?.let { prepareRequest = controller.requestFeature("local", "prepare", JSONObject().put("projectId", it)) } }, enabled = projectId != null && "local.prepare" !in state.moduleBusy) { Text(if ("local.prepare" in state.moduleBusy) "准备中…" else "准备新的工作副本") }
        }
        if (checkouts.isNotEmpty()) {
            LocalChoice("继续已有副本", checkouts.map { it.optString("id") to "${it.optString("name")} · ${it.optString("id").take(8)}${if (it.has("isolatedGit")) " · 私有隔离" else ""}${if (it.has("collectionOperationId")) " · 取回" else ""}" }, selectedId, { selectedId = it; switching = false; selectedJob = null; prepareRequest = null; materializeRequest = null })
        }
    }
    if (selected == null) return
    val checkoutId = selected.getString("id")
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Line)
    PocketSegmentedTabs(listOf("工作", "预览"), if (mode == 4) 1 else 0, { mode = if (it == 1) 4 else 0 })
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1 to "文件与导回", 2 to "执行回执", 3 to "手动工具").forEach { (index, label) ->
            TextButton(onClick = { mode = if (mode == index) 0 else index }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(label, fontSize = 13.sp, fontWeight = if (mode == index) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
    when (mode) {
        0, 4 -> NativeProjectDevelopment(selected, state, controller, onManualTools = { mode = 3 }, surface = if (mode == 4) ProjectWorkbenchFacet.Preview else ProjectWorkbenchFacet.Agent)
        3 -> LocalCommands(checkoutId, state, controller) { selectedJob = it; mode = 2 }
        1 -> LocalFiles(checkoutId, state, controller) { selectedId = it; selectedJob = null; mode = 0 }
        2 -> {
            val jobs = local.optJSONArray("jobs").objects().map { it to false } + local.optJSONArray("termuxJobs").objects().map { it to true }
            val scoped = jobs.filter { (job, _) -> job.optString("checkoutId") == checkoutId }.sortedByDescending { it.first.optLong("createdAt") }
            if (scoped.isEmpty()) LocalCaption("此副本还没有执行记录。启动命令后会在这里保留真实回执。")
            scoped.forEach { (job, termux) ->
                val ref = LocalJobRef(job.optString("id"), termux)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { selectedJob = if (selectedJob == ref) null else ref }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(localJobTitle(job, termux), fontSize = 14.sp); LocalCaption(localJobStatus(job.optString("status")) + " · " + job.optString("id").take(8)) }
                    Text(if (selectedJob == ref) "已展开" else "查看 ›", fontSize = 12.sp, color = SecondaryInk)
                }
                if (selectedJob == ref) {
                    // Keep the receipt owner outside an animated crossfade: there is only one polling leaf.
                    LocalJobDetail(checkoutId, ref, job, state, controller) { receipt ->
                        materializeRequest = controller.requestFeature("local", "termux.materialize", JSONObject().put("checkoutId", checkoutId).put("jobId", receipt.getString("id")))
                    }
                }
                HorizontalDivider(color = Line)
            }
        }
    }
}

@Composable
private fun LocalCommands(checkoutId: String, state: PocketUiState, controller: PocketWorldController, started: (LocalJobRef) -> Unit) {
    val context = LocalContext.current
    var termux by rememberSaveable(checkoutId) { mutableStateOf(false) }
    var executable by rememberSaveable(checkoutId, termux) { mutableStateOf(if (termux) "python3" else "ls") }
    var arguments by rememberSaveable(checkoutId, termux) { mutableStateOf("") }
    var confirmation by remember(checkoutId) { mutableStateOf<LocalCommandConfirmation?>(null) }
    var enableConfirmation by remember(checkoutId) { mutableStateOf(false) }
    var error by remember(checkoutId) { mutableStateOf<String?>(null) }
    var submitted by remember(checkoutId) { mutableStateOf<Pair<String, String>?>(null) }
    val local = state.feature("local")
    val termuxState = local.optJSONObject("termux") ?: JSONObject()
    val jobs = local.optJSONArray("termuxJobs").objects().filter { it.optString("checkoutId") == checkoutId }
    val ready = jobs.any { it.optString("kind") == "project_stage" && it.optString("status") == "completed" }
    val pending = jobs.any { !LocalWorkbenchGuard.terminal(it.optString("status"), true) }
    val nativePending = local.optJSONObject("legacyReceiptRecovery")?.optBoolean("originalInventoryUnconfirmed") == true || local.optJSONArray("jobs").objects().any { it.optString("checkoutId") == checkoutId && !LocalWorkbenchGuard.terminal(it.optString("status"), false) }
    val submitting = state.moduleBusy.any { it in setOf("local.run", "local.termux.run", "local.termux.prepare", "local.termux.collect", "local.termux.probe") }
    val request = submitted
    val receipt = request?.let { LocalWorkbenchGuard.result(state.moduleResults["local.${it.first}"], state.moduleResultInputs["local.${it.first}"], it.second) }
    LaunchedEffect(receipt) { if (receipt != null && receipt.optString("id").isNotBlank()) { started(LocalJobRef(receipt.getString("id"), request!!.first.startsWith("termux."))); submitted = null } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !termux, onClick = { termux = false }, label = { Text("系统只读工具") })
        FilterChip(selected = termux, onClick = { termux = true }, label = { Text("Termux 编程环境") })
    }
    if (termux) {
        LocalCaption("Termux 使用它自己的账号与文件权限，初始目录不是沙箱。只在你核对具体命令后执行；不会自动安装解释器、依赖或登录账号。")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = termuxState.optBoolean("enabled"), onCheckedChange = { if (it) enableConfirmation = true else controller.requestFeature("local", "termux.setEnabled", JSONObject().put("enabled", false)) }, enabled = "local.termux.setEnabled" !in state.moduleBusy)
            Text("允许本机连接 Termux", fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
        }
        LocalCaption(termuxState.optString("message"))
        Row {
            TextButton(onClick = { try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) } catch (_: Exception) { error = "请手动在系统设置中打开本应用权限" } }) { Text("系统权限") }
            TextButton(onClick = { try { val intent = context.packageManager.getLaunchIntentForPackage("com.termux") ?: throw IllegalStateException(); context.startActivity(intent) } catch (_: Exception) { error = "未找到可打开的 Termux，请先自行安装" } }) { Text("打开 Termux") }
        }
        if (pending) LocalCaption("此副本仍在执行或结果不明。先查看原回执；停止请求不等于 Termux 进程已停止。")
        val enabled = termuxState.optBoolean("enabled") && termuxState.optBoolean("runCommandAvailable") && !pending && !nativePending && !submitting
        TextButton(onClick = { confirmation = LocalCommandConfirmation("termux.probe", JSONObject().put("checkoutId", checkoutId), "在 Termux 中执行固定的工具路径查询，只查询 Python、Node、Git、Java 等实际安装位置；不会安装或运行项目。") }, enabled = enabled) { Text("探测实际工具") }
        OutlinedButton(onClick = { confirmation = LocalCommandConfirmation("termux.prepare", JSONObject().put("checkoutId", checkoutId), "将此副本复制到 Termux 的 home/pocketworld/$checkoutId。已有目录不会覆盖；完成与否以真实回执为准。") }, enabled = enabled && !ready) { Text(if (ready) "Termux 副本已准备" else "准备到 Termux") }
        if (ready) OutlinedButton(onClick = { confirmation = LocalCommandConfirmation("termux.collect", JSONObject().put("checkoutId", checkoutId), "只读收集该 Termux 目录，最多100个 UTF-8 文本文件、32000字节；符号链接、二进制、超限或截断会拒绝。收到完整结果后另存副本，再审阅导回。") }, enabled = enabled) { Text("取回 Termux 修改") }
    } else LocalCaption("只运行系统已有的只读工具；不调用任意 Shell，也不把应用私有密钥传入进程环境。")
    val tools = if (termux) listOf("python3", "python", "node", "git", "java", "javac", "gradle") else local.optJSONArray("tools").objects().filter { it.optBoolean("executionAllowed") }.map { it.optString("name") }
    if (tools.isEmpty()) LocalCaption("当前系统没有可用的只读工具。仍可编辑副本、查看差异并导回项目。")
    LocalChoice("工具", tools.map { it to it }, executable, { executable = it })
    OutlinedTextField(arguments, { arguments = it }, label = { Text("参数 · 每行一个，不按 Shell 拆词") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(), enabled = !submitting)
    LocalCaption(if (termux) "初始目录：~/pocketworld/$checkoutId" else "执行目录：当前 App 私有工作副本 · 最长30秒")
    val runAllowed = !submitting && !pending && !nativePending && executable in tools && (!termux || ready && termuxState.optBoolean("enabled") && termuxState.optBoolean("runCommandAvailable"))
    PocketPressButton(onClick = {
        try {
            val argv = LocalWorkbenchGuard.argv(executable, arguments)
            val input = JSONObject().put("checkoutId", checkoutId)
            if (termux) { input.put("executable", executable); input.put("argv", JSONArray().also { values -> (1 until argv.length()).forEach { values.put(argv.getString(it)) } }) }
            else input.put("argv", argv).put("timeoutSeconds", 30)
            confirmation = LocalCommandConfirmation(if (termux) "termux.run" else "run", input, argv.toString(2) + if (termux) "\n\n此工具可访问 Termux 账号的其他文件和网络。RUN_COMMAND 无可靠单任务停止接口。" else "\n\n只读系统工具；输出与退出码不代表项目目标已完成。")
            error = null
        } catch (failure: Exception) { error = failure.message }
    }, enabled = runAllowed, modifier = Modifier.padding(top = 10.dp)) { Text("核对并执行") }
    error?.let { Text(it, color = Danger, fontSize = 12.sp) }
    if (enableConfirmation) AlertDialog(onDismissRequest = { enableConfirmation = false }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("连接 Termux？") }, text = { Text("这只启用本应用的连接选择，不代替系统权限。Termux 命令具有该账号的真实文件和网络权限；后续每次执行仍需你核对。") }, confirmButton = { PocketPressButton(onClick = { controller.requestFeature("local", "termux.setEnabled", JSONObject().put("enabled", true)); enableConfirmation = false }) { Text("启用连接") } }, dismissButton = { TextButton(onClick = { enableConfirmation = false }) { Text("取消") } })
    confirmation?.let { frozen -> AlertDialog(onDismissRequest = { confirmation = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("执行这次操作？") }, text = { SelectionContainer { Text(frozen.description, fontSize = 13.sp, modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) } }, confirmButton = { PocketPressButton(onClick = { submitted = frozen.command to controller.requestFeature("local", frozen.command, JSONObject(frozen.input.toString())); confirmation = null }, enabled = !submitting && !pending && !nativePending) { Text("确认这次执行") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } }) }
}

@Composable
private fun LocalJobDetail(checkoutId: String, ref: LocalJobRef, metadata: JSONObject?, state: PocketUiState, controller: PocketWorldController, materialize: (JSONObject) -> Unit) {
    var requestId by remember(ref) { mutableStateOf<String?>(null) }
    var showOutput by remember(ref) { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycle = remember(context) { generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeUnless { base -> base === it } }.filterIsInstance<LifecycleOwner>().firstOrNull()?.lifecycle }
    val latest by rememberUpdatedState(state)
    val latestMetadata by rememberUpdatedState(metadata)
    fun refresh() { requestId = controller.requestFeature("local", "getJob", JSONObject().put("jobId", ref.id).put("termux", ref.termux)) }
    LaunchedEffect(ref) {
        refresh()
        while (true) {
            delay(1500)
            val current = latestMetadata
            if (current != null && !LocalWorkbenchGuard.pollable(current.optString("status"), ref.termux)) break
            if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false && "local.getJob" !in latest.moduleBusy) refresh()
        }
    }
    val input = state.moduleResultInputs["local.getJob"]
    val receipt = LocalWorkbenchGuard.result(state.moduleResults["local.getJob"], input, requestId)?.takeIf { it.optString("id") == ref.id && input?.optString("jobId") == ref.id && input.optBoolean("termux") == ref.termux }
    val job = receipt ?: metadata ?: return
    LocalHeading(localJobTitle(job, ref.termux))
    PocketScene(job.optString("status")) { LocalCaption(localJobStatus(job.optString("status"))) }
    LocalCaption(job.optString("message"))
    if (job.has("transferError")) LocalCaption(job.optString("transferError"))
    if (job.has("exitCode") && !job.isNull("exitCode")) Text("退出码 ${job.optInt("exitCode")}", fontSize = 13.sp)
    if (job.has("outputComplete") && !job.optBoolean("outputComplete")) LocalCaption("输出读取未完整结束，请核对原回执，不视为完整输出。")
    if (job.optBoolean("logIncompleteTail")) LocalCaption("日志末条未写完整，仅显示此前完整记录；原字节保留，不代表进程已结束或允许重跑。")
    if (job.optBoolean("outputTruncated")) LocalCaption("输出达到上限，以下内容已截断；不能当作完整结果。")
    if (receipt != null && (receipt.has("stdout") || receipt.has("stderr"))) {
        ProjectWorkbenchDisclosure("输出日志", showOutput, { showOutput = !showOutput }) {
            listOf("stdout" to "标准输出", "stderr" to "错误输出").forEach { (field, label) ->
                if (receipt.has(field)) { LocalHeading(label); LocalCode(receipt.optString(field).ifEmpty { "（空）" }) }
            }
        }
    }
    if (ref.termux && job.optString("kind") == "workspace_collect" && job.optBoolean("transferReady")) {
        LocalCaption("完整文件回执已到达。另存为新副本，不覆盖当前副本；仍需逐文件审阅后才导回正式项目。")
        PocketPressButton(onClick = { materialize(job) }, enabled = "local.termux.materialize" !in state.moduleBusy) { Text("另存取回结果并审阅") }
    }
    Row {
        TextButton(onClick = { refresh() }, enabled = "local.getJob" !in state.moduleBusy) { Text("刷新原回执") }
        if (if (ref.termux) !LocalWorkbenchGuard.terminal(job.optString("status"), true) else job.optString("status") in setOf("queued", "running", "stopping")) TextButton(onClick = { controller.requestFeature("local", "cancelJob", JSONObject().put("jobId", ref.id).put("termux", ref.termux)); refresh() }, enabled = "local.cancelJob" !in state.moduleBusy) { Text(if (ref.termux) "登记停止要求" else "停止进程", color = Danger) }
    }
    val task = state.tasks.firstOrNull { it.raw.optString("feature") == "local" && (it.raw.optString("runId") == ref.id || it.raw.optString("operationId") == ref.id || it.raw.optString("externalId") == ref.id) }
    if (task != null) TextButton(onClick = { controller.focusTask(task.id) }) { Text("查看同一任务记录") }
    if (ref.termux && !LocalWorkbenchGuard.terminal(job.optString("status"), true)) LocalCaption("请在 Termux 中终止进程并核对。未收到完整回执前不会自动重试，也不显示「已停止」。")
}

@Composable
internal fun LocalFiles(checkoutId: String, state: PocketUiState, controller: PocketWorldController, onCheckoutSelected: (String) -> Unit = {}) {
    val projectId = state.feature("local").optJSONArray("checkouts").objects().firstOrNull { it.optString("id") == checkoutId }?.optString("projectId")
    if (projectId.isNullOrBlank()) { LocalCaption("原工作副本尚未核对，草稿未移到其他项目。请刷新原项目后重开。"); return }
    var showVersions by rememberSaveable(checkoutId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(160))) {
        TextButton(onClick = { showVersions = !showVersions }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(if (showVersions) "收起版本与 Git" else "版本与 Git", fontSize = 13.sp)
        }
        // This leaf may read repository state; size animation never keeps two copies of it alive.
        if (showVersions) ProjectGitSection(projectId, checkoutId, state, controller, onCheckoutSelected)
    }
    NativeProjectFileImportControls(checkoutId, controller.projectFileImports)
    val session = controller.projectWorkbench
    val opened = session.opened(checkoutId)
    var requestId by remember(checkoutId) { mutableStateOf<String?>(null) }
    var dirtyPaths by remember(checkoutId) { mutableStateOf<List<String>>(emptyList()) }
    var draftListError by remember(checkoutId) { mutableStateOf<String?>(null) }
    var newPath by rememberSaveable(checkoutId) { mutableStateOf("") }
    var adding by remember(checkoutId) { mutableStateOf(false) }
    var binaryInfo by remember(checkoutId) { mutableStateOf<dev.pocketengine.features.local.project.ProjectFileTree.Node?>(null) }
    fun refresh() { requestId = controller.requestFeature("local", "changes", JSONObject().put("checkoutId", checkoutId)) }
    LaunchedEffect(checkoutId) { refresh() }
    val listing = LocalWorkbenchGuard.result(state.moduleResults["local.changes"], state.moduleResultInputs["local.changes"], requestId, checkoutId)
    LaunchedEffect(checkoutId, listing?.toString(), opened) {
        try { dirtyPaths = withContext(Dispatchers.IO) { session.fileDrafts.dirtyPaths(projectId, checkoutId) }; draftListError = null }
        catch (failure: Exception) { draftListError = failure.message }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(listing?.let { "${it.optInt("changedCount")} 项变更 · ${it.optInt("fileCount")} 个文件" } ?: "读取工作副本", fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { refresh() }, enabled = "local.changes" !in state.moduleBusy) { Text("刷新") }
    }
    if ("local.changes" in state.moduleBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    LocalCaption("编辑保存在工作副本中。核对差异后，可导回项目版本库。")
    draftListError?.let { Text(it, color = Danger, fontSize = 12.sp) }
    if (dirtyPaths.isNotEmpty()) {
        LocalCaption("${dirtyPaths.size} 份未保存草稿已保留在手机。")
        TextButton(onClick = { session.fileTree(checkoutId).filter.intValue = 2 }) { Text("查看未保存草稿") }
    }
    if (listing != null) {
        val listedFiles = listing.optJSONArray("files")
        val complete = listedFiles != null && listing.optInt("fileCount", -1) == listedFiles.objects().count { it.optString("change") != "deleted" } && !listing.optBoolean("truncated") && !listing.optBoolean("readLimited")
        if (!complete) Text("文件清单未完整读取；未显示不代表不存在。可刷新后继续核对。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (listedFiles != null) ProjectFileTreeSection(checkoutId, listedFiles, dirtyPaths, opened?.first ?: session.lastFile(checkoutId), controller, completeListing = complete,
            open = { selected -> session.open(checkoutId, selected, false) }, inspectBinary = { binaryInfo = it })
    }
    else if (state.moduleResultInputs["local.changes"]?.optString("_requestId") == requestId && state.moduleResultInputs["local.changes"]?.optBoolean("_failed") == true) state.moduleErrors["local.changes"]?.let { Text("未取得当前原文件清单：$it", color = MaterialTheme.colorScheme.error) }
    binaryInfo?.let { file -> LocalBinaryFileReview(projectId, checkoutId, file.path, state, controller, initialBytes = file.bytes, refreshed = { refresh() }) { binaryInfo = null } }
    ProjectWorkbenchDisclosure("新增文本文件", adding, { adding = !adding }) {
        OutlinedTextField(newPath, { newPath = it }, label = { Text("相对路径，例如 src/main.py") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { session.open(checkoutId, newPath.trim(), true) }, enabled = newPath.isNotBlank() && newPath.length <= 240) { Text("编辑新文件草稿") }
    }
    opened?.let { (path, fresh) -> LocalFileReview(projectId, checkoutId, path, fresh, state, controller) { session.closeFile(checkoutId); refresh() } }
}

/** Same original manual-owner feature requests. No task/model execution or new permission surface. */
@Composable
private fun LocalBinaryFileReview(projectId: String, checkoutId: String, path: String, state: PocketUiState, controller: PocketWorldController, initialBytes: Long? = null, initialReview: JSONObject? = null, draftRetained: Boolean = false, refreshed: () -> Unit, close: () -> Unit) {
    var review by remember(projectId, checkoutId, path) { mutableStateOf(initialReview?.let { JSONObject(it.toString()) }) }
    var readRequest by remember(projectId, checkoutId, path) { mutableStateOf<String?>(null) }
    var importRequest by remember(projectId, checkoutId, path) { mutableStateOf<String?>(null) }
    var inFlight by remember(projectId, checkoutId, path) { mutableStateOf(false) }
    var needsFreshRead by remember(projectId, checkoutId, path) { mutableStateOf(false) }
    var receipt by remember(projectId, checkoutId, path) { mutableStateOf<JSONObject?>(null) }
    var error by remember(projectId, checkoutId, path) { mutableStateOf<String?>(null) }
    var identities by remember(projectId, checkoutId, path) { mutableStateOf(false) }
    fun refreshReview() {
        if (inFlight) return
        inFlight = true; review = null; error = null; needsFreshRead = true; importRequest = null
        try { readRequest = controller.requestFeature("local", "reviewFile", JSONObject().put("checkoutId", checkoutId).put("path", path)) }
        catch (failure: Exception) { inFlight = false; error = failure.message }
    }
    val readInput = state.moduleResultInputs["local.reviewFile"]
    val readResult = LocalWorkbenchGuard.result(state.moduleResults["local.reviewFile"], readInput, readRequest, checkoutId, path)
    LaunchedEffect(readResult?.toString(), readInput?.toString(), readRequest) {
        val selected = readRequest ?: return@LaunchedEffect
        if (readInput?.optString("_requestId") != selected) return@LaunchedEffect
        if (readInput.optBoolean("_failed")) { error = "未取得当前原字节审阅：" + state.moduleErrors["local.reviewFile"].orEmpty(); inFlight = false; readRequest = null; return@LaunchedEffect }
        val actual = readResult ?: return@LaunchedEffect
        if (actual.optString("projectId") != projectId || !dev.pocketengine.features.local.project.ProjectBinaryImport.isReview(actual)) { error = "文件类型或所选项目已变化；请回文件树刷新，用原文字入口重新核对。"; review = null }
        else { review = JSONObject(actual.toString()); needsFreshRead = false; receipt = null }
        inFlight = false; readRequest = null
    }
    val importInput = state.moduleResultInputs["local.importToProject"]
    val importResult = LocalWorkbenchGuard.result(state.moduleResults["local.importToProject"], importInput, importRequest, checkoutId, path)
    LaunchedEffect(importResult?.toString(), importInput?.toString(), importRequest) {
        val selected = importRequest ?: return@LaunchedEffect
        if (importInput?.optString("_requestId") != selected) return@LaunchedEffect
        if (importInput.optBoolean("_failed")) { error = "这次导回未取得确定回执。先读取当前源与正式版本核对，不会自动重发。\n" + state.moduleErrors["local.importToProject"].orEmpty(); needsFreshRead = true; inFlight = false; importRequest = null; return@LaunchedEffect }
        val actual = importResult ?: return@LaunchedEffect
        if (actual.optString("projectId") != projectId || actual.optString("format") != dev.pocketengine.features.local.project.ProjectBinaryImport.RECEIPT) error = "回执身份未匹配，先刷新核对，不自动重发。"
        else { receipt = JSONObject(actual.toString()); refreshed() }
        needsFreshRead = true; inFlight = false; importRequest = null
    }
    val selected = review
    val busy = inFlight || state.moduleBusy.any { it in setOf("local.writeFile", "local.deleteFile", "local.importToProject") }
    val canImport = selected != null && selected.optString("projectId") == projectId && selected.optBoolean("importable") && !needsFreshRead && receipt == null && !busy
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(path.substringAfterLast('/')) }, text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
        Text(path, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        if (selected == null) {
            LocalCaption(initialBytes?.let { "原字节文件 · $it 字节" } ?: "原字节版本核对")
            LocalCaption("不是文字编辑器。可随项目包保留；也可先核对当前原字节，再明确导回正式版本库。")
        } else {
            LocalCaption("工作副本 ${selected.optLong("sourceBytes")} 字节 → 正式 v${selected.optInt("expectedVersion")}（${selected.optLong("targetBytes")} 字节）")
            if (selected.optBoolean("deleted")) Text("所选工作文件已删除；确认后会在正式版本库记录删除。", color = Danger, fontSize = 13.sp)
            LocalCaption(selected.optString("message"))
            LocalCaption("源 SHA " + selected.optString("expectedHash").take(16) + " · 目标 SHA " + selected.optString("expectedTargetHash").take(16))
            TextButton(onClick = { identities = !identities }) { Text(if (identities) "收起完整校验身份" else "完整SHA与目标修订") }
            if (identities) LocalCode("源 SHA-256\n${selected.optString("expectedHash")}\n目标 SHA-256\n${selected.optString("expectedTargetHash")}\n目标 revision\n${selected.optString("expectedTargetRevisionId")}")
            for ((key, label) in listOf("sourcePreview" to "当前工作原字节", "targetPreview" to "正式原字节")) selected.optJSONObject(key)?.let { preview ->
                LocalCaption("$label · 只显示前 ${preview.optInt("shownBytes")} / ${preview.optLong("totalBytes")} 字节${if (preview.optBoolean("truncated")) "（非完整预览）" else ""}")
                LocalCode(preview.optString("hex").ifEmpty { "（无字节）" }.chunked(32).joinToString("\n"))
            }
            LocalCaption("这不是text diff，也不是完整内容或格式安全验证。只保存已落盘、SHA匹配的这份工作文件；不运行、不导出或分享。")
        }
        if (draftRetained) LocalCaption("未保存的文字草稿仍保留，不包含在这次原字节导回中。")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        receipt?.let { result -> Text("正式版本 v${result.optInt("version")} · " + if (result.optBoolean("projectVersionCommitted")) "已取得原版本提交回执" else "已核对同一原版本，未再写内容", fontSize = 13.sp); LocalCaption(result.optString("message")); if (!result.optBoolean("checkoutBaselineUpdated")) Text("副本基线登记仍不明；刷新后可明确只登记已一致的版本，不盲目再写。", color = Danger, fontSize = 12.sp) }
        error?.let { Text(it, color = Danger, fontSize = 12.sp) }
        TextButton(onClick = { refreshReview() }, enabled = !busy) { Text(if (selected == null && !needsFreshRead) "核对原字节与正式版本" else "重新读取当前源与正式版本") }
    } }, confirmButton = {
        if (canImport) Button(onClick = {
            if (inFlight || importRequest != null) return@Button
            try { val frozen = dev.pocketengine.features.local.project.ProjectBinaryImport.payload(JSONObject(selected!!.toString())); inFlight = true; error = null; importRequest = controller.requestFeature("local", "importToProject", frozen) }
            catch (failure: Exception) { inFlight = false; importRequest = null; needsFreshRead = true; error = "这次派发未确定；先刷新原源与目标，不自动重发。" + failure.message.orEmpty() }
        }) { Text(when { selected!!.optBoolean("same") -> "只登记已一致版本"; selected.optBoolean("deleted") -> "确认删除正式文件"; else -> "明确导回这份原字节" }) }
    }, dismissButton = { TextButton(onClick = { close() }, enabled = !busy) { Text("返回文件树") } })
}

@Composable
private fun LocalFileReview(projectId: String, checkoutId: String, path: String, fresh: Boolean, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    val store = controller.projectWorkbench.fileDrafts
    var editor by remember(projectId, checkoutId, path) { mutableStateOf<ProjectFileDraft?>(null) }
    var restoreError by remember(projectId, checkoutId, path) { mutableStateOf<String?>(null) }
    var reviewRequest by remember(checkoutId, path) { mutableStateOf<String?>(null) }
    var byteReview by remember(projectId, checkoutId, path) { mutableStateOf<JSONObject?>(null) }
    var mode by remember(checkoutId, path) { mutableIntStateOf(0) }
    var acknowledged by remember(checkoutId, path) { mutableStateOf(false) }
    var acknowledgedLatest by remember(checkoutId, path) { mutableStateOf(false) }
    var notices by remember(checkoutId, path) { mutableStateOf(false) }
    var submitted by remember(checkoutId, path) { mutableStateOf<Pair<String, String>?>(null) }
    var saveIntent by remember(checkoutId, path) { mutableStateOf<ProjectFileDraft.SaveIntent?>(null) }
    var needsFreshRead by remember(checkoutId, path) { mutableStateOf(!fresh) }
    var importedVersion by remember(checkoutId, path) { mutableStateOf<Int?>(null) }
    var importNotice by remember(projectId, checkoutId, path) { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var useLatest by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var cacheError by remember { mutableStateOf<String?>(null) }
    var durable by remember { mutableStateOf(false) }
    var cacheRetry by remember { mutableIntStateOf(0) }
    var liveWriteBlocked by remember(projectId, checkoutId, path) { mutableStateOf(true) }
    val retainedEditor by rememberUpdatedState(editor)
    DisposableEffect(projectId, checkoutId, path) { onDispose { retainedEditor?.let(store::release) } }
    fun update(next: ProjectFileDraft): Boolean = try {
        store.retain(next); editor = next; acknowledged = false; acknowledgedLatest = false; localError = null; true
    } catch (failure: Exception) { localError = failure.message; false }

    fun reload() { reviewRequest = controller.requestFeature("local", "reviewFile", JSONObject().put("checkoutId", checkoutId).put("path", path)); acknowledged = false; acknowledgedLatest = false }
    LaunchedEffect(projectId, checkoutId, path) {
        try {
            val restored = withContext(Dispatchers.IO) { store.open(projectId, checkoutId, path, fresh) }
            editor = restored; mode = 0
            needsFreshRead = !restored.creating
            if (!restored.creating) reload()
        } catch (failure: Exception) { restoreError = failure.message }
    }
    val incoming = LocalWorkbenchGuard.result(state.moduleResults["local.reviewFile"], state.moduleResultInputs["local.reviewFile"], reviewRequest, checkoutId, path)
    LaunchedEffect(incoming?.toString(), reviewRequest) {
        if (dev.pocketengine.features.local.project.ProjectBinaryImport.isReview(incoming)) byteReview = JSONObject(incoming.toString())
    }
    if (byteReview != null && submitted == null) {
        LocalBinaryFileReview(projectId, checkoutId, path, state, controller, initialReview = byteReview, draftRetained = editor?.dirty() == true, refreshed = {}) { close() }
        return
    }
    LaunchedEffect(incoming?.toString(), reviewRequest) {
        val actual = incoming ?: return@LaunchedEffect
        if (dev.pocketengine.features.local.project.ProjectBinaryImport.isReview(actual)) return@LaunchedEffect
        val current = editor ?: return@LaunchedEffect
        try { if (update(current.observe(actual))) { needsFreshRead = false; importedVersion = null } }
        catch (failure: Exception) { localError = failure.message }
    }
    val submission = submitted
    val saved = submission?.let { LocalWorkbenchGuard.result(state.moduleResults["local.${it.first}"], state.moduleResultInputs["local.${it.first}"], it.second, checkoutId, path) }
    val saveInput = submission?.let { state.moduleResultInputs["local.${it.first}"] }
    LaunchedEffect(saved?.toString(), saveInput?.toString(), submission) {
        val original = submission ?: return@LaunchedEffect
        val delivered = saveInput ?: return@LaunchedEffect
        if (delivered.optString("_requestId") != original.second) return@LaunchedEffect
        if (delivered.optBoolean("_failed")) {
            needsFreshRead = true; acknowledged = false
            localError = "这次操作未取得成功回执，草稿已保留。先读取当前文件；不会自动重发。\n" + state.moduleErrors["local.${original.first}"].orEmpty()
            submitted = null; saveIntent = null; return@LaunchedEffect
        }
        val receipt = saved ?: return@LaunchedEffect
        try {
            when (original.first) {
                "importToProject" -> {
                    importedVersion = receipt.getInt("version").takeIf { receipt.optBoolean("checkoutBaselineUpdated") }
                    importNotice = receipt.optString("message")
                    acknowledged = false; needsFreshRead = true; reload()
                }
                "writeFile" -> { update(editor!!.saved(saveIntent!!, receipt)); needsFreshRead = true; reload(); mode = 1 }
                "deleteFile" -> { update(editor!!.deleted(receipt)); needsFreshRead = true; reload(); mode = 1 }
            }
        } catch (failure: Exception) { localError = failure.message; needsFreshRead = true }
        submitted = null; saveIntent = null
    }
    LaunchedEffect(editor?.revision, cacheRetry) {
        val current = editor ?: return@LaunchedEffect
        do {
            durable = store.durable(current); cacheError = store.persistenceError(current)
            if (!durable && cacheError == null) delay(160)
        } while (!durable && cacheError == null)
    }
    val current = editor
    val reviewed = current?.latestReview()
    val dirty = current?.dirty() == true
    val conflict = current?.conflict() == true
    val busy = state.moduleBusy.any { it in setOf("local.reviewFile", "local.writeFile", "local.deleteFile", "local.importToProject") }
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        val focus = LocalFocusManager.current
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(path.substringAfterLast('/'), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); Text(path, fontSize = 12.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { focus.clearFocus(); close() }, enabled = !busy) { Text("返回") }
                }
                val fileViews = listOf(0 to "编辑") + (if (reviewed != null) listOf(1 to "差异与导回") else emptyList()) + (if (conflict) listOf(2 to "比较当前文件") else emptyList())
                if (fileViews.size > 1) {
                    PocketSegmentedTabs(fileViews.map { it.second }, fileViews.indexOfFirst { it.first == mode }.coerceAtLeast(0), { index -> focus.clearFocus(); mode = fileViews[index].first })
                }
                val fileState = if (conflict) "文件已被修改 · 你的草稿已保留" else if (dirty) { if (durable) "未保存到工作副本 · 草稿已保留" else "正在保留未保存草稿…" } else if (needsFreshRead) "正在核对当前文件…" else "工作副本 · 保存后可核对差异"
                PocketScene(fileState) { Text(fileState, fontSize = 13.sp, color = if (conflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp)) }
                if (busy || current == null && restoreError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                restoreError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                localError?.let { error -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); TextButton(onClick = { notices = true }) { Text("查看问题详情") } }
                val readInput = state.moduleResultInputs["local.reviewFile"]
                if (reviewRequest != null && readInput?.optString("_requestId") == reviewRequest && readInput?.optBoolean("_failed") == true) state.moduleErrors["local.reviewFile"]?.let { Text("未读到当前文件，草稿已保留：$it", color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                cacheError?.let { error -> Text("草稿尚未可靠保留：$error", color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); TextButton(onClick = { current?.let(store::retry); cacheRetry++ }) { Text("重试保留草稿") } }
                importNotice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                when {
                    mode == 0 && current != null -> {
                        val editableSize = current.text.toByteArray(Charsets.UTF_8).size <= 262144
                        if (editableSize) ProjectSourceEditor(checkoutId, path, current.text, enabled = !busy && current.hasBaseline(), controller = controller, modifier = Modifier.weight(1f), draft = current, changedDraft = ::update) { value ->
                            if (value.toByteArray(Charsets.UTF_8).size > 262144) { localError = "编辑区与副本保存均限256KiB；没有替换现有草稿，也不会部分保存覆盖文件尾部。"; false }
                            else try { update(current.edit(value)) } catch (failure: Exception) { localError = failure.message; false }
                        }
                        else {
                            Text("文件超过256KiB，暂只支持完整只读查看；不会截断保存。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ProjectReadOnlyTextPages("我的完整原草稿", current.text, "draft:$checkoutId:$path:${current.revision}", Modifier.weight(1f))
                        }
                        ProjectLiveFileSaveControls(projectId, checkoutId, path, current, controller,
                            canSave = editableSize && !busy && !needsFreshRead && !conflict && dirty && !current.creating,
                            changed = ::update, saved = { needsFreshRead = true; reload(); mode = 1 }, blocksOtherWrites = { liveWriteBlocked = it })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PocketPressButton(onClick = {
                                try { focus.clearFocus(); val frozen = current.saveIntent(); saveIntent = frozen; submitted = "writeFile" to controller.requestFeature("local", "writeFile", frozen.input()); acknowledged = false }
                                catch (failure: Exception) { localError = failure.message }
                            }, enabled = editableSize && !busy && !liveWriteBlocked && !needsFreshRead && !conflict && (current.creating || dirty), modifier = Modifier.weight(1f)) { Text("保存并看差异") }
                            TextButton(onClick = { notices = !notices }) { Text(if (notices) "收起" else "更多") }
                        }
                    }
                    mode == 2 && current != null && reviewed != null -> {
                        Text("当前工作文件 · 只读比较", style = MaterialTheme.typography.bodyMedium)
                        ProjectReadOnlyTextPages("当前副本原文", reviewed.getString("content"), "latest:$checkoutId:$path:${reviewed.optString("expectedHash")}", Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(acknowledgedLatest, { acknowledgedLatest = it }, enabled = !busy); Text("我已比较并确认要保留的变化", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
                        OutlinedButton(onClick = { if (update(current.keepMineAgainstLatest())) { needsFreshRead = false; mode = 0 } }, enabled = !busy && acknowledgedLatest, modifier = Modifier.fillMaxWidth()) { Text("保留我的文字，另行保存") }
                        TextButton(onClick = { useLatest = true }, enabled = !busy) { Text("改用当前文件，放弃我的草稿…", color = MaterialTheme.colorScheme.error) }
                    }
                    mode == 1 && reviewed != null -> {
                        val baselineOnly = reviewed.optBoolean("baselineNeedsRefresh")
                        Text("正式版本库 v${reviewed.optInt("expectedVersion")} → 当前工作副本${if (reviewed.optBoolean("deleted")) "（删除）" else ""}", style = MaterialTheme.typography.bodyMedium)
                        ProjectReadOnlyTextPages("这份副本差异", reviewed.optString("diff").ifEmpty { "（无内容差异）" }, "diff:$checkoutId:$path:${reviewed.optString("expectedHash")}:${reviewed.optInt("expectedVersion")}", Modifier.weight(1f))
                        Text(reviewed.optString("message"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (reviewed.optBoolean("truncated")) Text("原差异不完整，不能凭截断片段导回。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (dirty) Text("手机草稿尚未保存在副本，不在这份导回差异内。", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(acknowledged, { acknowledged = it }, enabled = !busy && !dirty && !conflict && !needsFreshRead && reviewed.optBoolean("importable") && !reviewed.optBoolean("truncated")); Text(if (baselineOnly) "已核对正式版本与副本一致，只登记基线" else "已核对差异和正式目标版本", style = MaterialTheme.typography.bodySmall) }
                        PocketPressButton(onClick = {
                            try { val frozen = LocalWorkbenchGuard.importPayload(JSONObject(reviewed.toString())); submitted = "importToProject" to controller.requestFeature("local", "importToProject", frozen); acknowledged = false; localError = null; importNotice = null }
                            catch (failure: Exception) { localError = failure.message }
                        }, enabled = acknowledged && !busy && !liveWriteBlocked && !dirty && !conflict && !needsFreshRead && reviewed.optBoolean("importable") && !reviewed.optBoolean("truncated") && importedVersion == null, modifier = Modifier.fillMaxWidth()) { Text(if (baselineOnly) "只登记已一致的副本基线" else if (reviewed.optBoolean("deleted")) "确认删除正式项目文件" else "明确导回正式版本库") }
                        TextButton(onClick = { notices = !notices }) { Text(if (notices) "收起其他文件操作" else "其他文件操作") }
                    }
                    else -> Spacer(Modifier.weight(1f))
                }
                AnimatedVisibility(visible = notices || mode == 2, enter = expandVertically(tween(160)) + fadeIn(tween(120)), exit = shrinkVertically(tween(140)) + fadeOut(tween(90))) {
                  Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    Text("切换页面会保留草稿，不自动保存或运行。服务运行中，本页不能绕过已批准的写入范围。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    localError?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                    cacheError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (reviewed != null) SelectionContainer { Text("checkout $checkoutId\n当前文件 SHA-256 ${reviewed.optString("expectedHash")}\n项目目标版本 ${reviewed.optInt("expectedVersion")}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    TextButton(onClick = { reload() }, enabled = !busy && current != null) { Text("读最新副本，保留我的草稿") }
                    if (reviewed != null && !reviewed.optBoolean("deleted")) TextButton(onClick = { deleting = true }, enabled = !busy && !liveWriteBlocked && !dirty && !needsFreshRead && !conflict) { Text("从工作副本删除…", color = MaterialTheme.colorScheme.error) }
                    if (dirty) TextButton(onClick = { discard = true }, enabled = !busy) { Text("明确放弃未保存草稿…", color = MaterialTheme.colorScheme.error) }
                  }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("明确放弃未保存的文字？") }, text = { Text("只放弃 $path 的这份文件草稿，不删除已保存的副本、版本库或预览。不会保存或运行任何内容。") }, confirmButton = { TextButton(onClick = { current?.let { if (update(it.edit(it.baseContent))) { discard = false; close() } } }) { Text("放弃并返回", color = Danger) } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("保留草稿") } })
    if (useLatest) AlertDialog(onDismissRequest = { useLatest = false }, title = { Text("用刚读文件替换你的未保存文字？") }, text = { Text("这会放弃自己的草稿，但不修改副本或版本库。") }, confirmButton = { TextButton(onClick = { current?.let { if (update(it.useLatest())) { needsFreshRead = false; useLatest = false } } }) { Text("放弃草稿，使用刚读文件", color = Danger) } }, dismissButton = { TextButton(onClick = { useLatest = false }) { Text("继续比较") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("从工作副本删除此文件？") }, text = { Text("$path\n版本库不会立即变化；删除差异仍需单独审阅并导回。") }, confirmButton = { TextButton(onClick = { submitted = "deleteFile" to controller.requestFeature("local", "deleteFile", JSONObject().put("checkoutId", checkoutId).put("path", path).put("expectedHash", reviewed!!.getString("expectedHash"))); deleting = false }) { Text("只删除副本文件", color = Danger) } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}

@Composable
private fun LocalChoice(label: String, options: List<Pair<String, String>>, selected: String?, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        OutlinedButton(onClick = { open = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("$label：${options.find { it.first == selected }?.second ?: "请选择"}", modifier = Modifier.weight(1f)); Text("⌄") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) { options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name, fontSize = 13.sp) }, onClick = { choose(id); open = false }) } }
    }
}
/** A local disclosure keeps optional detail next to its source; no business leaf is crossfaded. */
@Composable
internal fun ProjectWorkbenchDisclosure(title: String, expanded: Boolean, onToggle: () -> Unit, summary: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { stateDescription = if (expanded) "已展开" else "已收起" }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                summary?.let { Text(it, fontSize = 12.sp, color = SecondaryInk) }
            }
            Text(if (expanded) "−" else "+", fontSize = 18.sp, color = SecondaryInk)
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(tween(160)) + fadeIn(tween(120)), exit = shrinkVertically(tween(140)) + fadeOut(tween(90))) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}
@Composable private fun LocalCaption(text: String) { if (text.isNotBlank()) Text(text, fontSize = 13.sp, lineHeight = 20.sp, color = SecondaryInk, modifier = Modifier.padding(vertical = 3.dp)) }
@Composable private fun LocalHeading(text: String) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) }
@Composable private fun LocalCode(text: String) { SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).background(Color(0xFFF4F4F4), RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState()).padding(12.dp)) } }
private fun localJobTitle(job: JSONObject, termux: Boolean): String = when (job.optString("kind")) { "project_stage" -> "准备 Termux 副本"; "workspace_collect" -> "取回 Termux 文件"; "tool_probe" -> "探测 Termux 工具"; else -> (if (termux) "Termux · " + job.optString("executable") else "系统 · " + job.optJSONArray("argv")?.optString(0).orEmpty()) }
private fun localJobStatus(status: String): String = when (status) { "awaiting_result" -> "已交给 Termux · 等待真实回执"; "unknown" -> "结果不明 · 仅核对，不重发"; "interrupted" -> "本机已中断 · 未重放"; "timed_out" -> "执行超时"; else -> taskStatus(status) }


/** Existing workbench controls; native picker bytes never enter a model/module JSON action. */
@Composable
private fun NativeProjectFileImportControls(checkoutId: String, flow: ProjectFileImportCoordinator) {
    DisposableEffect(checkoutId) { flow.activate(checkoutId); onDispose { flow.deactivate() } }
    val current = flow.state
    TextButton(onClick = flow::open, enabled = !current.busy && !current.picking) { Text("从系统文件导入到此副本…") }
    if (!current.open || current.checkout != checkoutId) return
    var path by remember(checkoutId, current.source) { mutableStateOf(current.suggestedPath) }
    var details by remember(current.source) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = flow::dismiss, title = { Text("导入一份文件到工作副本") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LocalCaption("只处理系统明确选择的≤2MiB普通文件，不跟随虚拟/未下载项；先读取并核对，再单次确认。此入口不运行、不分享、不自动写正式版本库。")
            current.source?.let { selected ->
                Text(selected.optString("name"), fontWeight = FontWeight.Medium)
                LocalCaption("${selected.optLong("bytes")} 字节 · SHA ${selected.optString("sha256").take(16)}…")
                OutlinedTextField(value = path, onValueChange = { path = it; flow.targetChanged() }, enabled = !current.busy, label = { Text("副本内相对目标路径") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LocalCaption("父目录须已存在；沿原source/已知私有配置排除。导入后，同副本项目工具可按原授权读取这些字节，未来运行也仍需原授权；不要选择密码或令牌。来源快照不保证provider原子版本。")
                current.review?.let { review -> LocalCaption(if (review.optString("expectedHash") == "absent") "目标原先不存在，将新建这个文件。" else "将替换此工作文件；刚核对旧 SHA ${review.optString("expectedHash").take(16)}…") }
                TextButton(onClick = { details = !details }) { Text(if (details) "收起原字节摘要" else "展开完整SHA与有限hex") }
                if (details) { LocalCode(selected.optString("sha256")); LocalCode(selected.optString("previewHex").chunked(32).joinToString("\n")); LocalCaption("只显示前 ${selected.optInt("previewBytes")} / ${selected.optInt("bytes")} 字节，不是text diff/格式安全验证。") }
            }
            if (current.busy || current.picking) LinearProgressIndicator(Modifier.fillMaxWidth())
            current.notice?.let { LocalCaption(it) }
            current.receipt?.let { receipt -> LocalCaption("原导入回执：${receipt.optString("status")} · 不自动重发。若未知，请返回文件树核对当前目标；当前同SHA不冒原操作证明。") }
        }
    }, confirmButton = {
        when {
            current.review != null -> Button(onClick = flow::confirm, enabled = !current.busy) { Text("将这份文件导入工作副本") }
            current.source != null -> Button(onClick = { flow.review(path) }, enabled = !current.busy && path.isNotBlank()) { Text("核对这个目标") }
            else -> Button(onClick = flow::choose, enabled = !current.busy && !current.picking) { Text("在系统中选择文件…") }
        }
    }, dismissButton = { TextButton(onClick = flow::dismiss) { Text(if (current.busy) "取消并核对" else "返回文件树") } })
}
