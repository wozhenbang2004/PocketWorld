package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
import java.util.Date
import java.time.ZoneId
import org.json.JSONObject

/** Native administration over the existing feature contracts, without another engine or store. */
@Composable
internal fun ConnectionCenter(state: PocketUiState, controller: PocketWorldController, initialSection: String? = null, onOpenTask: ((String, String) -> Unit)? = null, close: () -> Unit) {
    var section by rememberSaveable { mutableStateOf(initialSection) }
    val titles = mapOf("devices" to "我的设备", "models" to "API 模型与能力", "accounts" to "ChatGPT 账号", "remote" to "电脑与服务器", "ssh" to "SSH 连接", "local" to "手机开发环境", "memory" to "私人记忆", "memoryReview" to "记忆学习审核", "automation" to "提醒与主动任务", "commitments" to "承诺与跟进", "channels" to "消息渠道", "skills" to "技能", "learning" to "经验技能", "plugins" to "插件", "pluginCatalog" to "我的插件目录", "mcp" to "MCP 工具导入", "teams" to "协作团队", "groups" to "群协作", "media" to "生图与语音文件", "realtime" to "实时音视频", "proxy" to "本机模型服务")
    val nativePage = section in setOf("devices", "models", "memoryReview", "mcp", "accounts", "learning", "pluginCatalog", "groups")
    LaunchedEffect(initialSection, state.requestedConnectionRevision) { if (initialSection != null) section = initialSection }
    DisposableEffect(section) {
        val domain = section
        if (domain != null) controller.enterOwnerDomain(domain)
        onDispose { if (domain in setOf("memoryReview", "mcp", "accounts", "learning", "groups")) controller.leaveOwnerDomain(domain!!) }
    }
    LaunchedEffect(section) {
        when (section) {
            "memory" -> controller.requestFeature("memory", "list")
            "automation" -> controller.requestFeature("automation", "receipts")
            "channels" -> controller.requestFeature("channels", "inspect")
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                if (nativePage) {
                    val domain = section!!
                    val snapshot = state.ownerSnapshot(domain)
                    val responses = state.ownerResponses(domain)
                    val pending = state.ownerPending(domain)
                    val error = state.ownerErrors[domain]
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (domain) {
                            "devices" -> {
                                val deviceHost = controller.nativeDeviceWorkspace()
                                if (deviceHost != null) MyDevicesPanel(deviceHost, state.tasks,
                                    controller::runDeviceWorkspaceWork, controller::deviceWorkspaceChanged,
                                    { id -> if (onOpenTask != null) onOpenTask(id, "devices") else controller.focusTask(id) },
                                    { section = "models" }, { section = null }, nativeRevision = controller.deviceWorkspaceRevision, runOnControl = controller::runDeviceWorkspaceControlWork)
                                else Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("设备暂不可用", style = MaterialTheme.typography.titleMedium)
                                    Text("暂未读取到设备状态，请稍后重新打开。", style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = { section = null }) { Text("返回连接中心") }
                                }
                            }
                            "models" -> {
                                val service = controller.nativeModelConnections()
                                if (service != null) ModelConnectionsPage(service, controller::runModelConnectionWork, controller::modelConnectionChanged, { section = null })
                                else Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("模型目录暂不可用", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                    Text("本机目录或存储未能初始化，旧状态没有被重置。请检查存储后重新打开应用。", fontSize = 13.sp)
                                    TextButton(onClick = { section = null }) { Text("返回连接中心") }
                                }
                            }
                            "pluginCatalog" -> {
                                val page = remember(controller) { controller.openPluginCatalogPage() }
                                DisposableEffect(page) { onDispose { controller.closePluginCatalogPage(page) } }
                                PluginCatalogPage(page.session, page.nativeOwner(), controller::runPluginCatalogWork,
                                    page::beforeDispatch, { request, result, receipt -> page.settled(request, result, receipt) },
                                    controller.pluginRuntime, controller::preparePluginRuntime,
                                    { section = "mcp" }, { section = "plugins" }, { section = "plugins" }, nativeHost = controller.pluginExecutionHost(), onOpenSkills = { section = "skills" }, onOpenPackTask = controller::focusTask)
                            }
                            "groups" -> {
                                error?.let { Text(it, color = Danger, fontSize = 12.sp) }
                                dev.pocketengine.ui.groups.GroupCollaborationPage(snapshot, state.groupOwnerReceipts,
                                    { command, payload -> controller.requestOwnerDomain(domain, command, payload) }, controller::focusTask, { section = null },
                                    taskPresentations = snapshot.optJSONObject("taskPresentations")?.let { values ->
                                        values.keys().asSequence().mapNotNull { id -> values.optJSONObject(id)?.let { id to it } }.toMap()
                                    } ?: emptyMap(),
                                    onOpenArtifact = controller::openGroupArtifact,
                                    onReviewTask = { selection -> controller.focusTask(selection.taskId) })
                            }
                            "memoryReview" -> MemoryLearningReviewPage(snapshot, responses, pending, "snapshot" in pending, error,
                                { command, payload -> controller.requestOwnerDomain(domain, command, payload) }, controller::openOwnerSource, { section = null }, controller::dismissOwnerSource)
                            "mcp" -> McpInstallPreviewPage(snapshot, responses, pending, "snapshot" in pending, error,
                                { command, payload -> controller.requestOwnerDomain(domain, command, payload) }, controller::pickMcpConfig,
                                { val task = controller.taskMcpPreparation?.optString("taskId"); if (!task.isNullOrBlank()) { controller.focusTask(task); close() } else section = null }, controller.taskMcpPreparation?.optString("pluginId"), controller.taskMcpPreparation?.optString("tool"), nativeHost = controller.pluginExecutionHost(), runOAuthWork = controller::runPluginCatalogWork, runOAuthControl = controller::runEcosystemControlWork)
                            "learning" -> ExperienceSkillReviewPage(snapshot, responses, pending, "snapshot" in pending, error,
                                { command, payload -> controller.requestOwnerDomain(domain, command, payload) }, { section = null },
                                onOpenInferenceRecord = { reference -> controller.focusTask(reference.getString("taskId")) })
                            "accounts" -> AccountConnectionsPage(snapshot, responses, pending, "snapshot" in pending, error,
                                { command, payload -> controller.requestOwnerDomain(domain, command, payload) }, controller::openAccountAuthorization, controller::selectAccountModel, { section = null })
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { if (section == null) close() else section = null }) { Text(if (section == null) "关闭" else "返回") }
                        Text(section?.let { titles[it] } ?: "连接与能力", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = controller::refresh) { Text("刷新", fontSize = 12.sp) }
                    }
                    HorizontalDivider(color = Line)
                    Column(Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) {
                        if (section == null) {
                            ConnectionCatalog(titles, description = { id ->
                                when (id) {
                                    "devices" -> "连接你的手机与电脑，管理设备任务和权限"
                                    "accounts" -> "账号授权与主模型选择"
                                    "memoryReview" -> "核对学习候选、查看来源与管理遗忘"
                                    "learning" -> "从已审记忆整理经验，审阅后另行启用"
                                    "mcp" -> "发现工具、预览权限与按需启用"
                                    else -> featureSummary(id, state.feature(if (id == "commitments") "automation" else id))
                                }
                            }, open = { section = it }, history = controller::openNativeInferenceHistory)
                        } else {
                            state.moduleErrors.filterKeys { it.startsWith(section + ".") }.values.distinct().forEach { Text(it, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp)) }
                            when (section) {
                                "remote" -> RemoteConnections(state, controller)
                                "ssh" -> NativeSsh(state, controller)
                                "local" -> NativeLocalWorkbench(state, controller)
                                "memory" -> { TextButton(onClick = { section = "memoryReview" }) { Text("审核学习候选与来源") }; NativeMemory(state, controller) }
                                "automation" -> NativeReminders(state, controller)
                                "commitments" -> NativeCommitments(state, controller)
                                "channels" -> NativeChannels(state, controller)
                                "skills" -> NativeSkills(state, controller)
                                "plugins" -> { OutlinedButton(onClick = { section = "pluginCatalog" }) { Text("我的插件目录 · 安装与JS源码审批") }; OutlinedButton(onClick = { section = "mcp" }) { Text("导入与管理 MCP 工具") }; NativePlugins(state, controller) }
                                "teams" -> TeamManagement(state, controller)
                                "media" -> NativeMedia(state, controller)
                                "realtime" -> NativeRealtime(state, controller)
                                "proxy" -> NativeProxy(state, controller)
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                    }
                }
            }
        }
        BackHandler(enabled = !nativePage) { if (section == null) close() else section = null }
        state.ownerSource?.let { source ->
            AlertDialog(onDismissRequest = controller::dismissOwnerSource, containerColor = Color.White,
                title = { Text(source.optString("title", "真实来源")) },
                text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(source.optString("text"), fontSize = 14.sp, lineHeight = 22.sp) }
                    Text("来源版本 " + source.optJSONObject("sourceRef")?.optLong("revision"), color = SecondaryInk, fontSize = 11.sp, modifier = Modifier.padding(top = 16.dp))
                } }, confirmButton = { TextButton(onClick = controller::dismissOwnerSource) { Text("返回审核") } })
        }
    }
}

@Composable
private fun RemoteConnections(state: PocketUiState, controller: PocketWorldController) {
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val remote = state.feature("remote")
    val profiles = remote.optJSONArray("profiles").objects()
    CenterCaption("连接你信任的 Companion 环境。密钥仅写入手机加密存储，普通 SSH 不等于可恢复的电脑任务。")
    OutlinedButton(onClick = { editing = JSONObject() }) { Text("添加电脑环境") }
    profiles.forEach { profile ->
        val id = profile.optString("id")
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Text(profile.optString("name"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            CenterCaption(profile.optString("baseUrl") + if (profile.optBoolean("hasToken")) " · 已保存密钥" else " · 未保存密钥")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { selected = id; controller.requestFeature("remote", "probe", JSONObject().put("profileId", id)); controller.requestFeature("remote", "environments", JSONObject().put("profileId", id)) }, enabled = "remote.probe" !in state.moduleBusy) { Text("探测", fontSize = 12.sp) }
                TextButton(onClick = { selected = id; controller.requestFeature("remote", "reconnect", JSONObject().put("profileId", id)) }, enabled = "remote.reconnect" !in state.moduleBusy) { Text("同步原任务", fontSize = 12.sp) }
                TextButton(onClick = { editing = profile }) { Text("编辑", fontSize = 12.sp) }
                TextButton(onClick = { deleting = profile }) { Text("移除", fontSize = 12.sp, color = Danger) }
            }
        }
        HorizontalDivider(color = Line)
    }
    if (profiles.isEmpty()) CenterCaption("还没有连接电脑。添加后进行真实探测，成功前不会显示已连接。")
    selected?.let { id ->
        profiles.firstOrNull { it.optString("id") == id }?.let { RemoteTaskComposer(it, state, controller) }
        val probe = state.moduleResults["remote.probe"].takeIf { state.moduleResultInputs["remote.probe"]?.optString("profileId") == id }
        if (probe != null && "remote.probe" !in state.moduleBusy) Text(probe.optString("message", "探测请求已返回，请核对远端能力。"), fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        val environments = state.moduleResults["remote.environments"].takeIf { state.moduleResultInputs["remote.environments"]?.optString("profileId") == id }?.optJSONArray("environments").objects()
        if (environments.isNotEmpty()) {
            CenterHeading("远端工作环境")
            environments.forEach { environment -> Text(environment.optString("label", environment.optString("id")) + " · " + environment.optString("kind"), fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp)) }
        }
        val jobs = remote.optJSONArray("jobs").objects().filter { it.optString("profileId") == id }
        if (jobs.isNotEmpty()) CenterHeading("原任务")
        jobs.forEach { job ->
            Text(job.optString("worker") + " · " + taskStatus(job.optString("status")), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            if (job.optString("message").isNotBlank()) CenterCaption(job.optString("message"))
            RemoteApprovalBlock(id, job.optString("id"), job.optJSONArray("pendingApprovals").objects(), controller)
            state.tasks.firstOrNull { it.raw.optString("feature") == "remote" && it.raw.optString("externalId") == job.optString("id") && it.raw.optJSONObject("externalRefs")?.optString("profileId") == id }?.let { task ->
                TextButton(onClick = { controller.focusTask(task.id) }) { Text("打开本机原任务与补充回执", fontSize = 12.sp) }
            }
            if (job.optString("status") in setOf("queued", "running", "stopping", "awaiting_approval", "awaiting_input")) TextButton(onClick = { controller.requestFeature("remote", "cancelJob", JSONObject().put("profileId", id).put("jobId", job.optString("id"))) }) { Text("请求停止", fontSize = 12.sp) }
        }
    }
    editing?.let { profile -> RemoteProfileDialog(profile, state, controller) { editing = null } }
    deleting?.let { profile -> ConfirmAction("移除这个电脑连接？", "不会撤回已经在电脑发生的操作，请先核对原任务。", "移除连接", { deleting = null }) { controller.requestFeature("remote", "removeProfile", JSONObject().put("profileId", profile.optString("id"))); deleting = null } }
}

@Composable
private fun RemoteProfileDialog(profile: JSONObject, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    var name by remember { mutableStateOf(profile.optString("name")) }
    var url by remember { mutableStateOf(profile.optString("baseUrl")) }
    var token by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val busy = "remote.saveProfile" in state.moduleBusy
    LaunchedEffect(busy) { if (submitted && !busy && state.moduleErrors["remote.saveProfile"] == null) close() }
    AlertDialog(onDismissRequest = { if (!busy) close() }, containerColor = Color.White, title = { Text(if (profile.has("id")) "编辑电脑连接" else "添加电脑连接") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            OutlinedTextField(url, { url = it }, label = { Text("Companion 地址") }, placeholder = { Text("https://你的电脑地址") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it }, label = { Text("访问密钥") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            CenterCaption(if (profile.optBoolean("hasToken")) "相同服务可留空保留已存密钥；更换服务需重新填写。" else "填写电脑端配置的访问密钥。")
            state.moduleErrors["remote.saveProfile"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
        }
    }, confirmButton = { Button(onClick = { submitted = true; controller.requestFeature("remote", "saveProfile", JSONObject().put("name", name.trim()).put("baseUrl", url.trim()).put("token", token).also { if (profile.has("id")) it.put("id", profile.getString("id")) }) }, enabled = !busy && name.isNotBlank() && url.isNotBlank()) { Text(if (busy) "保存中" else "保存连接") } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("取消") } })
}

@Composable
private fun NativeMemory(state: PocketUiState, controller: PocketWorldController) {
    // One actual owner page owns this domain. Nested read-only pickers must not globally
    // dispose the still-visible history-scope control and cancel its in-flight receipt.
    DisposableEffect(controller) {
        controller.enterOwnerDomain("taskMemory")
        onDispose { controller.leaveOwnerDomain("taskMemory") }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var appliedQuery by rememberSaveable { mutableStateOf("") }
    var applications by remember { mutableStateOf<JSONObject?>(null) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var retrievalDetails by remember { mutableStateOf(false) }
    var workspaceSources by remember { mutableStateOf(false) }
    var sourcePath by remember(state.projectId) { mutableStateOf<String?>(null) }
    var sourcePathFilter by remember(state.projectId) { mutableStateOf("") }
    val records = state.moduleResults[if (appliedQuery.isBlank()) "memory.list" else "memory.search"]?.optJSONArray("records").objects()
    LaunchedEffect(state.moduleResults["memory.setCore"]) { if (state.moduleResults["memory.setCore"] != null) controller.requestFeature("memory", if (appliedQuery.isBlank()) "list" else "search", JSONObject().put("query", appliedQuery).put("limit", 50)) }
    CenterCaption("机主的私人记忆，可编辑、查看来源。遗忘需到审核页重新读取当前版本并明确确认，会同步清理旧派生技能与被遗忘的来源快照。过期记忆保留记录，不参与召回。")
    CenterCaption("核心记忆：" + state.feature("memory").optInt("coreSelectedCount") + " / " + state.feature("memory").optInt("maxCoreRecords") + "。核心会优先作为参考，仍受有效期与上下文预算限制。")
    TextButton(onClick = { retrievalDetails = !retrievalDetails }) { Text(if (retrievalDetails) "收起召回与记忆层级" else "召回方式与临时记忆边界", fontSize = 12.sp) }
    if (retrievalDetails) {
        val backend = state.feature("memory").optJSONObject("retrievalBackend") ?: JSONObject()
        val mode = when (backend.optString("backend")) { "sqlite_fts5" -> "SQLite FTS5 / BM25"; "sqlite_fts4" -> "SQLite FTS4 / BM25"; "lexical_cjk_pairs" -> "本地文字匹配（兼容模式）"; else -> "尚未读取实际召回方式" }
        CenterCaption("当前召回：$mode。索引只在内存派生，正式内容仍以原记忆记录与当前来源版本为准，不是向量或语义搜索。")
        if (backend.optString("fallbackReason").isNotBlank()) CenterCaption("兼容状态：" + backend.optString("fallbackReason").take(120))
        val tiers = state.feature("memory").optJSONObject("memoryTiers") ?: JSONObject()
        CenterCaption("已保存：知识/偏好 ${tiers.optInt("semantic")} · 经历 ${tiers.optInt("episodic")} · 程序经验 ${tiers.optInt("procedural")}。到期、来源与遗忘屏障仍单独生效。")
        CenterCaption("临时工作记忆只随当前任务/执行保存于内存；结束、接管或来源清理即关闭，不会自动变成长期记忆或权限。归纳候选仍必须由你选来源、审阅并采用。")
    }
    Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(query, { query = it }, label = { Text("搜索记忆") }, singleLine = true, modifier = Modifier.weight(1f)); TextButton(onClick = { appliedQuery = query.trim(); controller.requestFeature("memory", if (appliedQuery.isBlank()) "list" else "search", JSONObject().put("query", appliedQuery).put("limit", 50)) }, enabled = "memory.search" !in state.moduleBusy && "memory.list" !in state.moduleBusy) { Text("搜索") } }
    OutlinedButton(onClick = { editing = JSONObject() }, modifier = Modifier.padding(top = 12.dp)) { Text("保存一条记忆") }
    state.conversationId?.let { ConversationHistoryScopeControl(it, state, controller) }
    SavedEvidenceLearningControl(state, controller)
    TextButton(onClick = { workspaceSources = !workspaceSources }) { Text(if (workspaceSources) "收起项目原文选择" else "从已保存项目文件提炼候选") }
    if (workspaceSources) {
        val project = state.projectId
        if (project == null) CenterCaption("先在项目页选择原版本库项目；这里不把活工作副本SHA当虚构文件版本。")
        else {
            CenterCaption("原版本库：" + (state.projects.firstOrNull { it.id == project }?.name ?: project) + "。只读取你选择的当前文件范围，预览后仍需明确确认模型使用。")
            OutlinedTextField(sourcePathFilter, { sourcePathFilter = it }, label = { Text("筛选已载入文件路径") }, singleLine = true)
            val choices = state.files.filter { it.path.contains(sourcePathFilter, ignoreCase = true) }
            CenterCaption("当前已载入 ${state.files.size} 条文件metadata；筛选 ${choices.size} 条，本页最多显示50条。")
            choices.take(50).forEach { file -> TextButton(onClick = { sourcePath = file.path }) { Text(file.path + " · v" + file.version) } }
            sourcePath?.takeIf { selected -> state.files.any { it.path == selected } }?.let { selected ->
                WorkspaceMemorySourceControl(project, selected, state, controller)
            }
        }
    }

    records.take(50).forEach { record ->
        Column(Modifier.fillMaxWidth().padding(vertical = 15.dp)) {
            SelectionContainer { Text(record.optString("text"), fontSize = 14.sp, lineHeight = 23.sp) }
            CenterCaption(memoryLayer(record.optString("layer")) + if (record.optBoolean("archiveOnly")) " · 档案原文（来源未核，不参与召回）" else if (!record.optBoolean("active", true)) " · 已失效" else "")
            CenterCaption("来源：" + record.optString("source"))
            if (record.optString("layer") == "procedural") TextButton(onClick = { applications = JSONObject(record.toString()) }) { Text("查看真实方法应用回执", fontSize = 13.sp) }
            Row { TextButton(onClick = { editing = record }) { Text("编辑", fontSize = 12.sp) }; TextButton(onClick = { controller.requestFeature("memory", "setCore", JSONObject().put("id", record.optString("id")).put("revision", record.optInt("revision")).put("core", !record.optBoolean("core"))) }, enabled = !record.optBoolean("archiveOnly") && "memory.setCore" !in state.moduleBusy) { Text(if (record.optBoolean("core")) "取消核心" else "设为核心", fontSize = 12.sp) }; TextButton(onClick = { controller.openConnection("memoryReview") }) { Text("审核并遗忘…", fontSize = 12.sp, color = Danger) } }
        }
        HorizontalDivider(color = Line)
    }
    if (records.isEmpty()) CenterCaption(if ("memory.list" in state.moduleBusy || "memory.search" in state.moduleBusy) "正在读取记忆…" else "这里还没有符合条件的记忆。")
    applications?.let { record -> LearnedMethodApplicationsSheet(record, state, controller) { applications = null } }
    editing?.let { record -> MemoryEditor(record, state, controller) { editing = null; controller.requestFeature("memory", if (appliedQuery.isBlank()) "list" else "search", JSONObject().put("query", appliedQuery).put("limit", 50)) } }
}

@Composable
private fun MemoryEditor(record: JSONObject, state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    var text by remember { mutableStateOf(record.optString("text")) }
    var layer by remember { mutableStateOf(record.optString("layer", "fact")) }
    var submitted by remember { mutableStateOf(false) }
    val busy = "memory.save" in state.moduleBusy
    LaunchedEffect(busy) { if (submitted && !busy && state.moduleErrors["memory.save"] == null) close() }
    AlertDialog(onDismissRequest = { if (!busy) close() }, containerColor = Color.White, title = { Text("私人记忆") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(text, { text = it }, label = { Text("希望记住什么") }, minLines = 3, maxLines = 7)
        listOf("fact", "preference", "episodic", "procedural").forEach { value -> Row(Modifier.fillMaxWidth().clickable { layer = value }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = layer == value, onClick = { layer = value }); Text(memoryLayer(value), fontSize = 13.sp) } }
        state.moduleErrors["memory.save"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
    } }, confirmButton = { Button(onClick = { submitted = true; val next = JSONObject(record.toString()).put("text", text.trim()).put("layer", layer).put("source", record.optString("source", "机主明确保存")); next.remove("active"); controller.requestFeature("memory", "save", JSONObject().put("record", next)) }, enabled = text.isNotBlank() && !busy) { Text(if (busy) "保存中" else "保存记忆") } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("取消") } })
}

@Composable
private fun NativeReminders(state: PocketUiState, controller: PocketWorldController) {
    var create by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    val rules = state.feature("automation").optJSONArray("rules").objects()
    CenterCaption("提醒按手机系统调度，可能延后。手机目标会通知你查看并开始，不会擅自操作屏幕。")
    OutlinedButton(onClick = { create = true }) { Text("新建提醒") }
    TextButton(onClick = { controller.openConnection("commitments") }) { Text("查看承诺与持续跟进", fontSize = 12.sp) }
    rules.forEach { rule ->
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(rule.optString("title"), fontSize = 14.sp); CenterCaption(if (rule.optLong("nextAt", -1) > 0) "下次：" + centerDate(rule.optLong("nextAt")) else "已暂停或等待下次启用"); TextButton(onClick = { deleting = rule }) { Text("删除", fontSize = 12.sp, color = Danger) } }
            Switch(checked = rule.optBoolean("enabled"), onCheckedChange = { controller.requestFeature("automation", "setEnabled", JSONObject().put("id", rule.optString("id")).put("enabled", it)) }, enabled = "automation.setEnabled" !in state.moduleBusy)
        }
        HorizontalDivider(color = Line)
    }
    val receipts = state.moduleResults["automation.receipts"]?.optJSONArray("receipts").objects().takeLast(10).reversed()
    if (receipts.isNotEmpty()) CenterHeading("最近投递")
    receipts.forEach { receipt -> Text(receipt.optString("title"), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)); CenterCaption(receipt.optString("message")); if (receipt.optString("kind") == "phone_goal") TextButton(onClick = { controller.prepareReminderOccurrence(receipt.optString("id")) }) { Text("查看原任务目标", fontSize = 12.sp) } }
    if (create) ReminderEditor(state, controller) { create = false }
    deleting?.let { rule -> ConfirmAction("删除这个提醒？", rule.optString("title"), "删除提醒", { deleting = null }) { controller.requestFeature("automation", "delete", JSONObject().put("id", rule.optString("id"))); deleting = null } }
}

@Composable
private fun ReminderEditor(state: PocketUiState, controller: PocketWorldController, close: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var daily by rememberSaveable { mutableStateOf(false) }
    var time by rememberSaveable { mutableStateOf("09:00") }
    var minutes by rememberSaveable { mutableStateOf("30") }
    var phone by rememberSaveable { mutableStateOf(false) }
    var goal by rememberSaveable { mutableStateOf("") }
    var misfire by rememberSaveable { mutableStateOf("skip") }
    var overlap by rememberSaveable { mutableStateOf("buffer_one") }
    var maxCatchUp by rememberSaveable { mutableStateOf("3") }
    var submitted by remember { mutableStateOf(false) }
    val busy = "automation.save" in state.moduleBusy
    LaunchedEffect(busy) { if (submitted && !busy && state.moduleErrors["automation.save"] == null) close() }
    AlertDialog(onDismissRequest = { if (!busy) close() }, containerColor = Color.White, title = { Text("新建提醒") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("提醒内容") }, maxLines = 3)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("每天重复", Modifier.weight(1f), fontSize = 13.sp); Switch(daily, { daily = it }) }
        if (daily) { OutlinedTextField(time, { time = it }, label = { Text("每天时间 HH:mm") }, singleLine = true); CenterCaption("时区：" + ZoneId.systemDefault().id) } else OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(6) }, label = { Text("多少分钟后") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(verticalAlignment = Alignment.CenterVertically) { Text("提醒我开始手机任务", Modifier.weight(1f), fontSize = 13.sp); Switch(phone, { phone = it }) }
        if (phone) OutlinedTextField(goal, { goal = it }, label = { Text("手机任务目标") }, minLines = 2, maxLines = 5)
        if (daily) {
            Text("错过日历时间时", fontSize = 14.sp)
            listOf("skip" to "跳过旧实例，只处理最新一次", "coalesce" to "合并错过的实例为一次", "catch_up" to "按原日历逐次补处理").forEach { (value, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = misfire == value, onClick = { misfire = value }, enabled = !busy)
                    Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
            if (misfire == "catch_up") OutlinedTextField(maxCatchUp, { maxCatchUp = it.filter(Char::isDigit).take(2) },
                label = { Text("本轮最多补处理次数（1–20）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("原任务仍未结束时", fontSize = 14.sp)
            listOf("buffer_one" to "保留一个待处理实例", "skip" to "跳过新实例", "allow" to "允许并行（仍各自需要原任务授权）").forEach { (value, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = overlap == value, onClick = { overlap = value }, enabled = !busy)
                    Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
            CenterCaption("补处理不等于补执行：通知投递未知或旧任务结果未核对时，不会盲目重发或假定成功。")
        }
        state.moduleErrors["automation.save"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
    } }, confirmButton = { Button(onClick = { submitted = true; val schedule = if (daily) JSONObject().put("type", "daily").put("time", time).put("zone", ZoneId.systemDefault().id) else JSONObject().put("type", "once").put("at", System.currentTimeMillis() + (minutes.toLongOrNull() ?: 0) * 60_000); controller.requestFeature("automation", "save", JSONObject().put("rule", JSONObject().put("title", title.trim()).put("kind", if (phone) "phone_goal" else "reminder").put("goal", goal.trim()).put("enabled", true).put("schedule", schedule).put("policy", JSONObject().put("misfire", if (daily) misfire else "skip").put("overlap", if (daily) overlap else "buffer_one").put("maxCatchUp", maxCatchUp.toIntOrNull() ?: 3)))) }, enabled = !busy && (misfire != "catch_up" || (maxCatchUp.toIntOrNull() ?: 0) in 1..20) && title.isNotBlank() && (!phone || goal.isNotBlank()) && (daily || (minutes.toLongOrNull() ?: 0) in 1..525600)) { Text(if (busy) "保存中" else "创建提醒") } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("取消") } })
}

internal fun qqGatewayConnection(channels: JSONObject, profileId: String): JSONObject? = channels.optJSONObject("qq")?.optJSONArray("connections").objects().find { it.optString("profileId") == profileId }
internal fun qqGatewayCommand(channels: JSONObject, profile: JSONObject): String? {
    if (profile.optString("provider") != "qq" || channels.optJSONObject("capabilities")?.optBoolean("qqGateway") != true) return null
    if (qqGatewayConnection(channels, profile.optString("id"))?.opt("monitorRunning") == true) return "qq.stop"
    return if (profile.optBoolean("enabled")) "qq.start" else null
}
internal fun qqGatewayStatus(connection: JSONObject?): String = when {
    connection == null -> "尚未开始接收"
    connection.opt("monitorRunning") != true -> if (connection.optString("status") == "authorization_required") "需要核对平台凭据与权限" else "接收已停止"
    else -> when (connection.optString("status")) { "listening" -> "官方网关已认证，正在接收"; "starting" -> "正在连接，尚未确认在线"; "retry_wait" -> "连接中断，等待恢复原会话"; "authorization_required" -> "需要核对平台凭据与权限"; else -> "正在核对接收状态" }
}

@Composable
private fun NativeChannels(state: PocketUiState, controller: PocketWorldController) {
    val channels = state.feature("channels")
    CenterCaption("绑定主人身份与群空间分别管理。账号密钥、主人验证和平台回调仍需完成真实配置。")
    ChannelAccountSetupPanel(state, controller)
    ChannelReceivingPanel(state, controller)
    var taskPermission by remember { mutableStateOf<JSONObject?>(null) }
    val taskBusy = "channels.owner.tasks.set" in state.moduleBusy
    val bindings = channels.optJSONArray("bindings").objects()
    val taskHostReady = channels.optJSONObject("capabilities")?.optBoolean("ownerTaskHostConfigured") == true
    channels.optJSONArray("profiles").objects().forEach { profile ->
        val id = profile.optString("id")
        val binding = bindings.find { it.optString("profileId") == id }
        Text(profile.optString("name") + " · " + profile.optString("provider"), fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
        CenterCaption(if (profile.optBoolean("enabled")) "连接已启用；平台在线状态需单独验证" else "连接已停用")
        if (profile.optString("provider") == "qq") {
            val connection = qqGatewayConnection(channels, id)
            CenterCaption(qqGatewayStatus(connection))
            connection?.optString("message")?.takeIf { it.isNotBlank() }?.let { CenterCaption(it) }
            CenterCaption("仅QQ官方机器人接收；不是个人QQ登录。接收随应用进程运行，不保证重启或强停后继续；停止接收不停止已有任务。")
            val command = qqGatewayCommand(channels, profile)
            if (channelReceivingOwns(channels, id)) CenterCaption("此连接由上方前台会话管理，请使用本次接收的停止入口。")
            else if (command != null) OutlinedButton(onClick = { controller.requestFeature("channels", command, JSONObject().put("id", id)) }, enabled = ("channels." + command) !in state.moduleBusy) { Text(if (command == "qq.stop") "停止QQ接收" else "开始QQ接收") }
            else if (channels.optJSONObject("capabilities")?.optBoolean("qqGateway") != true) CenterCaption("当前版本尚未接入官方网关，不会显示已在线。")
            for (action in listOf("qq.start", "qq.stop")) if (state.moduleResultInputs["channels." + action]?.optString("id") == id) state.moduleErrors["channels." + action]?.let { Text(it, color = Danger, fontSize = 12.sp) }
        }
        if (profile.optString("provider") == "weixin") {
            val connection = channels.optJSONObject("weixin")?.optJSONArray("connections").objects().find { it.optString("profileId") == id }
            CenterCaption(weixinReceivingStatus(connection))
            connection?.optString("message")?.takeIf { it.isNotBlank() }?.let { CenterCaption(it) }
            CenterCaption("仅微信iLink机器人文字接收，不是个人微信登录或聊天记录导入。持续接收需在上方明确开启前台会话。")
        }
        if (binding == null) CenterCaption("还未绑定已认证的主人私聊；任务权限默认关闭。")
        else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("允许主人私聊创建任务", fontSize = 13.sp)
                    CenterCaption("已绑定账号：" + binding.optString("senderId"))
                }
                Switch(binding.optBoolean("ownerTasksEnabled"), { enabled ->
                    taskPermission = JSONObject(binding.toString()).put("enabled", enabled).put("profileName", profile.optString("name"))
                }, enabled = !taskBusy && (binding.optBoolean("ownerTasksEnabled") || profile.optBoolean("enabled") && taskHostReady))
            }
            CenterCaption("仅 /task 明确提交目标；不授予手机控制。写入与外发仍须在本机按原参数审批，私聊里的“同意”不算授权。")
        }
        ChannelProfileActions(profile, binding, state, controller)
        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 8.dp))
    }
    if (!taskHostReady) CenterCaption("任务宿主尚未就绪，不能启用私聊任务；不会假装已经开始。")
    val requests = channels.optJSONArray("taskRequests").objects().asReversed()
    if (requests.isNotEmpty()) {
        CenterHeading("主人私聊任务")
        requests.forEach { request ->
            val task = state.tasks.find { it.id == request.optString("taskId") }
            Text(task?.goal ?: "原任务等待本机核对", fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            CenterCaption(task?.let { taskStatus(it.status) } ?: "入站状态：" + request.optString("admission"))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (task != null) TextButton(onClick = { controller.focusTask(task.id) }) { Text("打开原任务", fontSize = 12.sp) }
                TextButton(onClick = { controller.requestFeature("channels", "task.inspect", JSONObject().put("requestId", request.optString("requestId"))) }, enabled = "channels.task.inspect" !in state.moduleBusy) { Text("核对宿主回执", fontSize = 12.sp) }
            }
            val inspected = state.moduleResults["channels.task.inspect"].takeIf { state.moduleResultInputs["channels.task.inspect"]?.optString("requestId") == request.optString("requestId") }
            if (inspected != null) CenterCaption(if (inspected.optBoolean("found")) "已核对同一宿主任务，未重新执行。" else inspected.optString("message", "宿主结果仍待核对，未重新提交。"))
            if (request.optString("admission") == "admission_unknown" || request.optString("cancellation") == "cancellation_unknown") CenterCaption("上次请求结果不明，只核对原任务，不会重新提交或执行。")
        }
    }
    taskPermission?.let { proposal ->
        val current = bindings.find { it.optString("profileId") == proposal.optString("profileId") }
        val unchanged = current != null && current.optString("bindingEpoch") == proposal.optString("bindingEpoch") && current.optString("taskGrantEpoch") == proposal.optString("taskGrantEpoch")
        val enabling = proposal.optBoolean("enabled")
        AlertDialog(onDismissRequest = { if (!taskBusy) taskPermission = null }, containerColor = Color.White,
            title = { Text(if (enabling) "允许这个主人私聊创建任务？" else "关闭这个渠道的任务权限？") },
            text = { Column {
                Text(proposal.optString("profileName") + " · " + proposal.optString("senderId"), fontSize = 14.sp)
                CenterCaption(if (enabling) "仅接受此已认证账号的私聊 /task。消息不会自动批准写入或外发，也不会操作手机主屏。" else "旧授权下的任务将不能继续调用模型或工具；已发生的动作不会撤回，结果仍保留在本机。")
                if (!unchanged) Text("绑定或权限已变化，请返回核对最新身份。", color = Danger, fontSize = 12.sp)
            } },
            confirmButton = { Button(onClick = {
                controller.requestFeature("channels", "owner.tasks.set", JSONObject().put("profileId", proposal.optString("profileId")).put("enabled", enabling).put("confirmed", true).put("expectedBindingEpoch", proposal.optString("bindingEpoch")).put("expectedTaskGrantEpoch", proposal.optString("taskGrantEpoch")))
                taskPermission = null
            }, enabled = unchanged && !taskBusy) { Text(if (enabling) "明确允许" else "关闭权限") } },
            dismissButton = { TextButton(onClick = { taskPermission = null }, enabled = !taskBusy) { Text("返回") } })
    }
    CenterHeading("最近回复投递")
    channels.optJSONArray("outbox").objects().takeLast(20).reversed().forEach { message ->
        Text(message.optString("text"), fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 12.dp))
        CenterCaption(message.optString("status") + " · " + message.optString("message"))
        if (message.optString("status") == "queued") TextButton(onClick = { controller.requestFeature("channels", "outbox.cancel", JSONObject().put("id", message.optString("id"))) }) { Text("取消待发回复", fontSize = 12.sp) }
        if (message.optString("status") == "unknown") CenterCaption("投递结果不明，请到原平台核对；不会自动重发。")
        HorizontalDivider(color = Line)
    }
    OutlinedButton(onClick = { controller.openAdvanced("channels") }, modifier = Modifier.padding(top = 18.dp)) { Text("可信中继与旧版诊断") }
}

@Composable
private fun NativePlugins(state: PocketUiState, controller: PocketWorldController) {
    val plugins = state.feature("plugins")
    CenterCaption("启用不等于授权。插件只能使用你已授予的工具权限；停止不会撤回远端已发生的效果。")
    plugins.optJSONArray("plugins").objects().forEach { plugin ->
        val manifest = plugin.optJSONObject("manifest") ?: JSONObject()
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(manifest.optString("name", plugin.optString("id")), fontSize = 14.sp); CenterCaption("版本 " + manifest.optString("version") + " · " + (plugin.optJSONArray("grants")?.length() ?: 0) + " 项已授权限") }; if (manifest.optJSONArray("tools").objects().any { it.optString("kind") == "mcp" }) TextButton(onClick = { controller.openConnection("mcp") }) { Text(if (plugin.optBoolean("enabled")) "已启用 · 管理" else "已停用 · 管理", fontSize = 12.sp) } else Switch(plugin.optBoolean("enabled"), { controller.requestFeature("plugins", "enable", JSONObject().put("id", plugin.optString("id")).put("enabled", it)) }, enabled = "plugins.enable" !in state.moduleBusy) }
        HorizontalDivider(color = Line)
    }
    PluginVersions(state, controller)
    plugins.optJSONArray("active").objects().forEach { operation -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(operation.optString("pluginId") + " · " + taskStatus(operation.optString("status")), Modifier.weight(1f), fontSize = 12.sp); TextButton(onClick = { controller.requestFeature("plugins", "cancel", JSONObject().put("operationId", operation.optString("operationId"))) }) { Text("停止") } } }
    OutlinedButton(onClick = { controller.openAdvanced("plugins") }, modifier = Modifier.padding(top = 18.dp)) { Text("工具授权与诊断") }
}

@Composable
private fun NativeProxy(state: PocketUiState, controller: PocketWorldController) {
    val proxy = state.feature("proxy")
    CenterCaption("可选的 API-key 本机服务，供这台手机的其他工具连接；不会开放到公网，也不授予手机操作权限。")
    val account = proxy.optString("modelRoute") == "account"
    CenterCaption(if (account) "当前选择的是 ChatGPT 账号，仅供本应用本人活动使用，不能启动通用反代。请在模型页明确切换到自己的 API-key 连接。" else "当前使用已选择的 API 模型 · ${when(proxy.optString("protocol")) { "openai_responses" -> "Responses"; "anthropic" -> "Anthropic Messages"; "gemini" -> "Gemini GenerateContent"; else -> "Chat Completions" }} 协议。")
    proxy.optString("model").takeIf { it.isNotBlank() }?.let { CenterCaption("当前模型：$it") }
    if (!account) proxy.optJSONArray("endpoints")?.let { endpoints ->
        SelectionContainer { CenterCaption((0 until endpoints.length()).joinToString("\n") { endpoints.optString(it) }) }
    }
    if (!account) CenterCaption("外部客户端协议必须与此连接一致；不转换协议、不执行手机工具。访问令牌与上游 API 密钥分开。")
    CenterCaption("服务启动后固定所选 API-key 路由；更换连接或模型前请先停止，再明确启动。不会自动切换计费身份。")
    if (proxy.optBoolean("running")) { CenterHeading("服务运行中"); SelectionContainer { Text(proxy.optString("endpoint"), fontSize = 13.sp) }; TextButton(onClick = { controller.requestFeature("proxy", "stop") }) { Text("停止本机服务") } }
    else CenterCaption("服务尚未运行。启动前需配置独立访问令牌。")
    TextButton(onClick = { controller.requestFeature("proxy", "status") }) { Text("刷新服务与调用记录") }
    proxy.optJSONArray("recentInferenceReferences")?.let { refs ->
        if (refs.length() > 0) { CenterCaption("本次服务最近 ${refs.length()} 条调用（最多8条，非累计）"); for (i in refs.length() - 1 downTo 0) NativeInferenceReference(refs.optJSONObject(i), controller) }
    }
    OutlinedButton(onClick = { controller.openAdvanced("proxy") }, modifier = Modifier.padding(top = 16.dp)) { Text("访问令牌与启动设置") }
}

@Composable
private fun NativeLocalEnvironment(state: PocketUiState, controller: PocketWorldController) {
    val local = state.feature("local")
    CenterCaption("项目文件与运行环境分别管理。系统没有的解释器不会显示为可用；Termux 需要实际安装和用户授权。")
    if (local.length() == 0) { CenterCaption("本机开发模块状态尚未读取。"); return }
    local.optJSONArray("tools").objects().forEach { tool -> Text(tool.optString("name") + if (tool.optBoolean("available")) " · 系统可用" else " · 尚不可用", fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp)) }
    if (state.projectId != null) OutlinedButton(onClick = { controller.requestFeature("local", "prepare", JSONObject().put("projectId", state.projectId)) }, enabled = "local.prepare" !in state.moduleBusy, modifier = Modifier.padding(top = 14.dp)) { Text("准备当前项目工作区") }
    local.optJSONArray("checkouts").objects().forEach { checkout -> Text(checkout.optString("name", checkout.optString("id")), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)); CenterCaption((checkout.optJSONArray("files")?.length() ?: 0).toString() + " 个实际工作文件") }
    CenterHeading("Termux 连接")
    CenterCaption(local.optJSONObject("termux")?.optString("message").orEmpty().ifBlank { "探测仅查询真实接入状态，不代表已经准备好 Python 或 Node。" })
    OutlinedButton(onClick = { controller.requestFeature("local", "termux.probe") }, enabled = "local.termux.probe" !in state.moduleBusy) { Text("探测 Termux") }
    local.optJSONArray("jobs").objects().takeLast(10).reversed().forEach { job -> Text(taskStatus(job.optString("status")), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)); CenterCaption(job.optString("message")) }
}

@Composable
private fun NativeSkills(state: PocketUiState, controller: PocketWorldController) {
    OutlinedButton(onClick = { controller.openConnection("learning") }) { Text("从已审经验整理技能") }
    val skills = state.feature("skills").optJSONArray("skills").objects()
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var content by remember { mutableStateOf("") }
    var nextOffset by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var enabling by remember { mutableStateOf<JSONObject?>(null) }
    val result = state.moduleResults["skills.read"]
    LaunchedEffect(result) {
        val input = state.moduleResultInputs["skills.read"]
        if (result != null && input?.optString("id") == selected?.optString("id")) {
            val offset = input?.optInt("offset", 0) ?: 0
            if (offset == 0) content = result.optString("content") else if (offset == nextOffset) content += result.optString("content")
            nextOffset = result.optInt("nextOffset", content.length)
            hasMore = result.optBoolean("hasMore")
        }
    }
    CenterCaption("技能内容是带来源的参考资料。启用前核对具体版本；导入不等于执行脚本或自动授予工具权限。")
    skills.forEach { skill ->
        val enabled = skill.optBoolean("enabled")
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { selected = skill; content = ""; nextOffset = 0; hasMore = true; controller.requestFeature("skills", "read", JSONObject().put("id", skill.optString("id")).put("path", "SKILL.md").put("offset", 0).put("length", 8000)) }) { Text(skill.optString("name", skill.optString("id")), fontSize = 14.sp); CenterCaption(if (enabled) "已启用" else "未启用") }
            if (enabled) TextButton(onClick = { controller.requestFeature("skills", "setEnabled", JSONObject().put("id", skill.optString("id")).put("enabled", false).put("confirmed", true)) }) { Text("停用", fontSize = 12.sp) }
        }
        HorizontalDivider(color = Line)
    }
    SkillArchiveControls(state, controller)
    SkillSourceControls(state, controller)
    if (skills.isEmpty()) CenterCaption("尚未导入技能。导入后的技能会先保持停用。")
    selected?.let { skill ->
        CenterHeading(skill.optString("name", "技能说明"))
        CenterCaption("来源：" + skill.optString("source"))
        if (content.isNotBlank()) SelectionContainer { Text(content, fontSize = 12.sp, lineHeight = 21.sp) }
        if (hasMore) TextButton(onClick = { controller.requestFeature("skills", "read", JSONObject().put("id", skill.optString("id")).put("path", "SKILL.md").put("offset", nextOffset).put("length", 8000)) }, enabled = "skills.read" !in state.moduleBusy) { Text("读取后续说明") }
        if (!skill.optBoolean("enabled")) OutlinedButton(onClick = { enabling = skill }, enabled = content.isNotBlank() && !hasMore) { Text("启用这个版本") }
    }
    enabling?.let { skill -> AlertDialog(onDismissRequest = { enabling = null }, containerColor = Color.White, title = { Text("启用这个技能版本？") }, text = { Text("确认已核对来源和当前说明。能力调用仍受宿主授权约束。") }, confirmButton = { Button(onClick = { controller.requestFeature("skills", "setEnabled", JSONObject().put("id", skill.optString("id")).put("enabled", true).put("acceptedDigest", skill.optString("digest")).put("confirmed", true)); enabling = null }) { Text("确认启用") } }, dismissButton = { TextButton(onClick = { enabling = null }) { Text("取消") } }) }
}

@Composable
private fun ConnectionRow(title: String, detail: String, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 18.dp)) { Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium); CenterCaption(detail) }
    HorizontalDivider(color = Line)
}
@Composable private fun CenterCaption(value: String) { Text(value, color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)) }
@Composable private fun CenterHeading(value: String) { Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp)) }
@Composable private fun ConfirmAction(title: String, detail: String, label: String, close: () -> Unit, accept: () -> Unit) { AlertDialog(onDismissRequest = close, containerColor = Color.White, title = { Text(title) }, text = { Text(detail) }, confirmButton = { TextButton(onClick = accept) { Text(label, color = Danger) } }, dismissButton = { TextButton(onClick = close) { Text("取消") } }) }
private fun memoryLayer(value: String): String = when (value) { "fact" -> "事实与项目知识"; "preference" -> "个人偏好"; "episodic" -> "经历与结果"; "procedural" -> "有效做法"; else -> value }
private fun centerDate(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
private fun featureSummary(id: String, value: JSONObject): String {
    if (value.length() == 0) return "状态尚未读取"
    if (value.has("available") && !value.optBoolean("available")) return value.optString("message", "能力暂不可用")
    return when (id) {
        "remote" -> (value.optJSONArray("profiles")?.length() ?: 0).toString() + " 个已配置连接"
        "ssh" -> (value.optJSONArray("profiles")?.length() ?: 0).toString() + " 个 SSH 配置"
        "local" -> (value.optJSONArray("checkouts")?.length() ?: 0).toString() + " 个本机工作区"
        "memory" -> value.optInt("recordCount").toString() + " 条私人记忆"
        "automation" -> (value.optJSONArray("rules")?.length() ?: 0).toString() + " 个提醒规则"
        "commitments" -> "查看明确委托、到期候选与完成凭证"
        "channels" -> (value.optJSONArray("profiles")?.length() ?: 0).toString() + " 个消息账号"
        "plugins" -> (value.optJSONArray("plugins")?.length() ?: 0).toString() + " 个插件"
        "skills" -> (value.optJSONArray("skills")?.length() ?: 0).toString() + " 个技能"
        "teams" -> (value.optJSONArray("teams")?.length() ?: 0).toString() + " 个团队配置"
        "proxy" -> if (value.optBoolean("running")) "本机服务运行中" else "尚未启动"
        "media" -> if (value.optBoolean("busy")) "媒体请求执行中" else if (value.optBoolean("configured")) "已保存配置，服务尚需验证" else "尚未配置服务"
        "realtime" -> realtimeLabel(value)
        else -> value.optString("message", "查看配置")
    }
}
