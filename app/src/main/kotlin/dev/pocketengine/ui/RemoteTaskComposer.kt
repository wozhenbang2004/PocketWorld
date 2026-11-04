package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.remote.RemoteTaskRequest
import dev.pocketengine.features.remote.RemoteHarnessSelection
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** First create, not an import/resume form. All effects go through the existing tracked host. */
@Composable
internal fun RemoteTaskComposer(profile: JSONObject, state: PocketUiState, controller: PocketWorldController) {
    val profileId = profile.optString("id")
    val binding = profile.optString("bindingHash")
    var environmentRequest by rememberSaveable(profileId, binding) { mutableStateOf<String?>(null) }
    var environmentId by rememberSaveable(profileId, binding) { mutableStateOf("") }
    var worker by rememberSaveable(profileId, binding, environmentId) { mutableStateOf("codex_app_server") }
    var goal by rememberSaveable(profileId, binding) { mutableStateOf("") }
    var argv by rememberSaveable(profileId, binding) { mutableStateOf("[\"git\",\"status\",\"--short\"]") }
    var model by rememberSaveable(profileId, binding, worker) { mutableStateOf("") }
    var modeId by rememberSaveable(profileId, binding, environmentId, worker) { mutableStateOf("") }
    var executionAcknowledged by rememberSaveable(profileId, binding, environmentId, worker) { mutableStateOf(false) }
    var optionCatalog by remember(profileId, binding, environmentId, worker) { mutableStateOf<JSONObject?>(null) }
    var effort by rememberSaveable(profileId, binding, environmentId, worker, model) { mutableStateOf("") }
    var readOnly by rememberSaveable(profileId, binding, worker) { mutableStateOf(true) }
    var timeout by rememberSaveable(profileId, binding) { mutableStateOf("300") }
    var probeRequest by rememberSaveable(profileId, binding, environmentId, worker, model, modeId, effort) { mutableStateOf<String?>(null) }
    var operation by rememberSaveable(profileId, binding) { mutableStateOf<String?>(null) }
    var submittedRequest by rememberSaveable(profileId, binding) { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<JSONObject?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var inputRequest by remember(profileId, binding) { mutableStateOf<JSONObject?>(null) }
    var projectRequest by remember(profileId, binding) { mutableStateOf<JSONObject?>(null) }
    val environments = state.moduleResults["remote.environments"]
        .takeIf { environmentRequest != null && state.moduleResultInputs["remote.environments"]?.optString("_requestId") == environmentRequest }
        ?.optJSONArray("environments").objects()
    val environment = environments.singleOrNull { it.optString("id") == environmentId }
    val workers = environment?.optJSONArray("workers").objects().filter { externalTeamHarness(it.optString("id")) || it.optString("id") == "command" }
    val actualWorker = workers.singleOrNull { it.optString("id") == worker }
    val probe = state.moduleResults["remote.probeWorker"]
        .takeIf { probeRequest != null && state.moduleResultInputs["remote.probeWorker"]?.optString("_requestId") == probeRequest }
    LaunchedEffect(probe) { if (probe != null) optionCatalog = JSONObject(probe.toString()) }
    val configuredHarness = configuredTeamHarness(worker)
    val harnessReady = nativeHarnessProbeReady(probe, actualWorker)
    val task = state.tasks.firstOrNull { it.id == operation || it.raw.optString("operationId") == operation && it.raw.optString("feature") == "remote" }
    val result = state.moduleResults["remote.submitJob"]
        .takeIf { submittedRequest != null && state.moduleResultInputs["remote.submitJob"]?.optString("_requestId") == submittedRequest }
    val unresolved = state.feature("remote").optJSONArray("operations").objects().filter { it.optString("profileId") == profileId && it.optString("kind", "job_submit") == "job_submit" && it.optString("status") in setOf("unknown", "submitting") }
    val registered = operation != null
    val reading = "remote.environments" in state.moduleBusy
    val probing = "remote.probeWorker" in state.moduleBusy
    val submitting = "remote.submitJob" in state.moduleBusy
    val canProbe = !registered && !probing && environment != null && actualWorker != null && worker != "command"
    val canCreate = unresolved.isEmpty() && !registered && !reading && !submitting && profile.optBoolean("hasToken") && binding.matches(Regex("[a-f0-9]{64}")) &&
        environment?.optString("bindingHash")?.matches(Regex("[a-f0-9]{64}")) == true && actualWorker != null && actualWorker.opt("available") != false &&
        (worker == "command" || probe?.optBoolean("available") == true) &&
        (!configuredHarness || harnessReady && executionAcknowledged && environment?.optBoolean("writeAllowed") == true)

    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 12.dp), color = Line)
    Text("在这台电脑新建工作", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    SshCaption("先读取真实环境，再选择执行器。新工作与原会话继续分开；不导入任意threadId，不把未知提交当失败重发。")
    OutlinedButton(onClick = {
        environmentId = ""; probeRequest = null; localError = null
        environmentRequest = controller.requestFeature("remote", "environments", JSONObject().put("profileId", profileId).put("_profileBinding", binding))
    }, enabled = !registered && !reading && profile.optBoolean("hasToken")) { Text(if (reading) "正在读取环境" else "读取当前工作环境") }
    if (!profile.optBoolean("hasToken")) SshCaption("请先保存此电脑的访问密钥。")
    unresolved.filter { it.optString("operationId") != operation }.forEach { original ->
        SshCaption("此电脑尚有提交待核对：" + original.optString("operationId") + "。改配置不会授权重提，请先查询原操作。")
        TextButton(onClick = { controller.requestFeature("remote", "reconcileOperation", JSONObject().put("profileId", profileId).put("operationId", original.optString("operationId"))) }, enabled = "remote.reconcileOperation" !in state.moduleBusy) { Text("核对原提交（不重发）") }
        state.tasks.firstOrNull { it.raw.optString("feature") == "remote" && (it.raw.optString("operationId") == original.optString("operationId") || it.id == original.optString("operationId")) }?.let { originalTask -> TextButton(onClick = { controller.focusTask(originalTask.id) }) { Text("打开这项原任务") } }
    }
    if (environmentRequest != null && !reading && environments.isEmpty()) SshCaption("尚未取得可用环境；检查原连接，不会自动安装或创建电脑目录。")
    environments.forEach { item ->
        FilterChip(selected = environmentId == item.optString("id"), onClick = { if (!registered) { environmentId = item.optString("id"); probeRequest = null } }, enabled = !registered,
            label = { Text(item.optString("label", item.optString("id")) + " · " + item.optString("kind")) })
    }
    environment?.let { selected ->
        SshCaption("工作区：" + selected.optString("workspace").ifBlank { "执行端未报告" } + if (selected.optBoolean("writeAllowed")) " · 允许工作区写入" else " · 未启用工作区写入")
        if (!selected.optString("bindingHash").matches(Regex("[a-f0-9]{64}"))) SshCaption("此Companion未报告可核对的环境绑定，请更新电脑端后重新读取；尚未允许新建。")
        workers.forEach { item -> FilterChip(selected = worker == item.optString("id"), onClick = { if (!registered) worker = item.optString("id") }, enabled = !registered && !probing,
            label = { Text(nativeHarnessWorkerLabel(item) + if (item.opt("available") == false) " · 当前不可用" else if (item.isNull("available")) " · 待探测" else "") }) }
        actualWorker?.let { SshCaption(nativeHarnessWorkerLabel(it) + "负责调用模型与工具，运行在" + profile.optString("name") + "上。") }
        if (worker == "command") {
            OutlinedTextField(argv, { argv = it }, label = { Text("明确参数JSON数组（不是Shell文本）") }, minLines = 2, maxLines = 5, enabled = !registered, modifier = Modifier.fillMaxWidth())
            SshCaption("仅运行电脑端allowlist中的命令；不会把自然语言或拼接字符串交给Shell。")
        } else {
            OutlinedTextField(goal, { goal = it }, label = { Text(if (worker == "pi") "希望进行什么无工具对话" else "希望在此工作区完成什么") }, minLines = 3, maxLines = 7, enabled = !registered, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("请求模型ID（可留空）") }, singleLine = true, enabled = !registered, modifier = Modifier.fillMaxWidth())
            if (worker == "codex_app_server") {
                Row { Checkbox(readOnly, { readOnly = it }, enabled = !registered); Text("只读分析，不授权修改文件", fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)) }
            } else if (worker == "pi") SshCaption("Pi 1.4.0 · 无工具对话：不提供项目读写、命令、扩展或MCP工具，不提供逐工具审批，也不是操作系统只读沙箱。仅本机POSIX已接入；账号/推理需实际运行，原设置可能自动重试或压缩，费用与次数未知。")
            else if (worker == "claude") SshCaption("Claude使用受限工具配置，不提供操作系统只读沙箱；模型和账号是否可用仍须真实运行确认。")
            else if (configuredHarness) {
                SshCaption("使用电脑端已配置的工具与权限。工作目录不是操作系统沙箱；恢复、运行中补充和审批能力以实际回执为准。")
                Row { Checkbox(executionAcknowledged, { executionAcknowledged = it }, enabled = !registered && !probing); Text("我确认使用这台电脑已配置的工具权限，了解可能修改文件，内部模型费用不能硬限。", fontSize = 12.sp, modifier = Modifier.weight(1f)) }
                if (!selected.optBoolean("writeAllowed")) SshCaption("此环境尚未允许可写工作，不能启动使用电脑端权限的执行器。")
                nativeHarnessOptions(optionCatalog, true).forEach { (id, name) -> FilterChip(selected = model == id, onClick = { model = id }, enabled = !registered && !probing, label = { Text(name) }) }
                val modes = nativeHarnessOptions(optionCatalog, false)
                if (modes.isNotEmpty() || modeId.isNotBlank()) {
                    Text("执行模式", fontSize = 12.sp)
                    FilterChip(selected = modeId.isEmpty(), onClick = { modeId = "" }, enabled = !registered && !probing, label = { Text("执行器默认") })
                    modes.forEach { (id, name) -> FilterChip(selected = modeId == id, onClick = { modeId = id }, enabled = !registered && !probing, label = { Text(name) }) }
                    if (modeId.isNotBlank() && modes.none { it.first == modeId }) SshCaption("原请求模式 $modeId 尚未由本次目录确认，需重新核对。")
                }
            }
            OutlinedButton(onClick = {
                val input = JSONObject().put("profileId", profileId).put("_profileBinding", binding).put("environmentId", environmentId).put("environmentBinding", selected.optString("bindingHash")).put("worker", worker)
                if (model.isNotBlank()) input.put("model", model.trim())
                if (configuredHarness && modeId.isNotBlank()) input.put("modeId", modeId)
                if (worker == "codex_app_server" && effort.isNotBlank()) input.put("effort", effort)
                probeRequest = controller.requestFeature("remote", "probeWorker", input)
            }, enabled = canProbe) { Text(if (probing) "正在核对执行器" else "核对执行器与模型能力") }
            probe?.let { checked ->
                SshCaption(checked.optJSONObject("reason")?.optString("message").orEmpty().ifBlank { checked.optString("message").ifBlank { if (checked.optBoolean("available")) "执行器已返回可用声明" else "当前未确认可用" } } + "；未发送工作任务。")
                if (configuredHarness) {
                    SshCaption(if (checked.optBoolean("sessionAccessVerified")) "已建立测试会话，尚未进行模型推理。" else "本次尚未验证会话访问与实际模型调用。")
                    if (checked.optString("reportedModel").isNotBlank()) SshCaption("执行端报告模型：" + checked.optString("reportedModel"))
                    val capabilities = checked.optJSONObject("capabilities") ?: JSONObject()
                    listOf("supportsResume" to "恢复原会话", "inputSteering" to "运行中补充", "turnInterrupt" to "请求停止", "interactiveApprovals" to "工具审批", "structuredUserInput" to "向你提问", "teamTools" to "团队消息").forEach { (id, name) ->
                        if (capabilities.has(id)) SshCaption(name + "：" + when (capabilities.opt(id)) { true -> "本次报告支持"; false -> "当前不支持"; else -> "尚未核实" })
                    }
                }
                if (checked.optBoolean("modelsTruncated") || checked.optBoolean("modelCatalogTruncated") || checked.optJSONObject("modelOptions")?.optBoolean("truncated") == true || (checked.optJSONArray("models")?.length() ?: 0) > 12) SshCaption("这里只展示部分已配置模型；仍可填写完整精确模型ID。目录不证明账号、推理或费用已核实。")
                checked.optJSONArray("models").objects().take(12).forEach { available -> TextButton(onClick = { model = available.optString("id") }, enabled = !registered) { Text(available.optString("name", available.optString("id")), fontSize = 12.sp) } }
            }
            if (worker == "codex_app_server") {
                val selectedOptions = probe?.optJSONArray("models").objects().singleOrNull { it.optString("id") == model.trim() }
                if (model.isNotBlank()) OutlinedTextField(effort, { effort = it }, label = { Text("请求档位（可留空，须在原生目录中）") }, singleLine = true, enabled = !registered, modifier = Modifier.fillMaxWidth())
                selectedOptions?.optJSONArray("efforts").objects().take(12).forEach { option -> FilterChip(selected = effort == option.optString("id"), onClick = { effort = option.optString("id") }, enabled = !registered, label = { Text(option.optString("id")) }) }
                SshCaption("仅传本次Codex原生枚举值；改变模型或档位后须重新核对。请求值不等于执行端实际读回，不改变工具或预算。")
            } else if (worker == "claude") SshCaption("此处不会为读取目录额外启动Claude。首次没有native目录时不提供档位选项；已批准原任务取得目录后，可在原会话页选择下一轮。")
        }
        OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit).take(4) }, label = { Text("任务时限（秒，10–3600）") }, singleLine = true, enabled = !registered)
        Button(onClick = {
            try {
                val request = JSONObject().put("profileId", profileId).put("bindingHash", binding).put("environmentId", environmentId).put("environmentBinding", selected.optString("bindingHash")).put("worker", worker).put("timeoutSeconds", timeout.toIntOrNull() ?: 0)
                if (worker == "command") request.put("argv", JSONArray(argv)) else {
                    request.put("prompt", goal).put("readOnly", worker == "codex_app_server" && readOnly)
                    if (model.isNotBlank()) request.put("model", model.trim())
                    if (worker == "codex_app_server" && effort.isNotBlank()) request.put("effort", effort)
                    if (worker == "codex_app_server" && model.isNotBlank()) probe?.optJSONObject("modelOptions")?.optString("digest")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { request.put("modelOptionsDigest", it) }
                    if (configuredHarness) {
                        if (!harnessReady || !executionAcknowledged || !selected.optBoolean("writeAllowed")) throw IllegalStateException("请重新核对当前执行器，并确认电脑端工具权限。")
                        request.put("harnessSelection", RemoteHarnessSelection.fromProbe(probe!!, worker)).put("externalExecutionRiskAcknowledged", true).put("readOnly", false)
                        if (modeId.isNotBlank()) request.put("modeId", modeId)
                    }
                }
                review = RemoteTaskRequest.first(request); localError = null
            } catch (failure: Exception) { localError = failure.message ?: "请核对任务参数" }
        }, enabled = canCreate, modifier = Modifier.padding(top = 10.dp)) { Text("核对并创建这次工作") }
        if (worker in setOf("codex_app_server", "claude") && selected.optJSONObject("projectHistoryCapabilities")?.optBoolean("supported") == true) {
            OutlinedButton(onClick = {
                try {
                    val request=JSONObject().put("profileId",profileId).put("bindingHash",binding).put("environmentId",environmentId).put("environmentBinding",selected.optString("bindingHash")).put("worker",worker).put("timeoutSeconds",timeout.toIntOrNull()?:0).put("prompt",goal).put("readOnly",worker=="codex_app_server"&&readOnly)
                    if(model.isNotBlank())request.put("model",model.trim())
                    if (worker == "codex_app_server" && effort.isNotBlank()) request.put("effort", effort)
                    if (worker == "codex_app_server" && model.isNotBlank()) probe?.optJSONObject("modelOptions")?.optString("digest")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { request.put("modelOptionsDigest", it) }
                    RemoteTaskRequest.first(request);projectRequest=request;localError=null
                }catch(failure:Exception){localError=failure.message}
            },enabled=canCreate&&goal.isNotBlank()){Text("先保留项目基线，再创建这次工作")}
        }
        if (worker in setOf("codex_app_server", "claude")) {
            OutlinedButton(onClick = {
                try {
                    RemoteTaskRequest.requireInputCapability(selected)
                    val target = JSONObject().put("profileId", profileId).put("bindingHash", binding).put("environmentId", environmentId).put("environmentBinding", selected.optString("bindingHash")).put("worker", worker).put("timeoutSeconds", timeout.toIntOrNull() ?: 0)
                        .put("prompt", goal).put("readOnly", worker == "codex_app_server" && readOnly)
                    if (model.isNotBlank()) target.put("model", model.trim())
                    if (worker == "codex_app_server" && effort.isNotBlank()) target.put("effort", effort)
                    if (worker == "codex_app_server" && model.isNotBlank()) probe?.optJSONObject("modelOptions")?.optString("digest")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { target.put("modelOptionsDigest", it) }
                    RemoteTaskRequest.first(target)
                    if (goal.length > 8000) throw IllegalArgumentException("带私人原件的首片目标最多8000字符")
                    inputRequest = target; localError = null
                } catch (failure: Exception) { localError = failure.message }
            }, enabled = canCreate && goal.isNotBlank() && goal.length <= 8000 && selected.optJSONObject("privateInputCapabilities")?.optBoolean("supported") == true) { Text("另行选择私人原件并确认外传") }
            if (selected.optJSONObject("privateInputCapabilities")?.optBoolean("supported") != true) Text("此环境尚未支持已核二进制附件暂存；无附件文字任务不受影响。", color = SecondaryInk, fontSize = 13.sp)
        }
    }
    if (registered) {
        SshCaption("本次操作：$operation。提交后只查这个编号；离开页面不会停止电脑任务。")
        result?.let { SshCaption(it.optString("message", taskStatus(it.optString("status")))) }
        val taskId = task?.id ?: result?.optString("hostTaskId").orEmpty().ifBlank { operation.orEmpty() }
        TextButton(onClick = { controller.focusTask(taskId) }, enabled = taskId.isNotBlank()) { Text("打开原任务 · 审批、成果与继续") }
        TextButton(onClick = { controller.requestFeature("remote", "reconcileOperation", JSONObject().put("profileId", profileId).put("operationId", operation)) }, enabled = "remote.reconcileOperation" !in state.moduleBusy) { Text("查询原操作（不重发）") }
        val original = state.feature("remote").optJSONArray("operations").objects().firstOrNull { it.optString("operationId") == operation }
        if (task?.status in setOf("completed", "needs_confirmation", "failed", "cancelled") && original?.optString("status") in setOf("settled", "rejected")) TextButton(onClick = { operation = null; submittedRequest = null; review = null; goal = "" }) { Text("新建另一项工作") }
    }
    (localError ?: state.moduleErrors["remote.submitJob"] ?: state.moduleErrors["remote.probeWorker"])?.let { Text(it, color = Danger, fontSize = 12.sp) }
    inputRequest?.let { RemoteInputDialog(it, "", controller) { inputRequest = null } }
    projectRequest?.let { RemoteProjectStartDialog(it,controller){projectRequest=null} }
    review?.let { captured ->
        val current = profile.optString("bindingHash") == captured.optString("_profileBinding") && environment?.optString("bindingHash") == captured.optString("environmentBinding") && (!captured.has("modelOptionsDigest") || probe?.optJSONObject("modelOptions")?.optString("digest") == captured.optString("modelOptionsDigest")) &&
            (!configuredTeamHarness(captured.optString("worker")) || harnessReady && executionAcknowledged && captured.optString("worker") == worker && captured.optString("model") == model.trim() && captured.optString("modeId") == modeId && runCatching { RemoteHarnessSelection.requireSame(captured.getJSONObject("harnessSelection"), RemoteHarnessSelection.fromProbe(probe!!, worker), worker); true }.getOrDefault(false))
        AlertDialog(onDismissRequest = { review = null }, title = { Text("确认在所选电脑创建工作") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(profile.optString("name") + "\n" + profile.optString("baseUrl") + "\n" + environment?.optString("workspace").orEmpty())
                Text((actualWorker?.let(::nativeHarnessWorkerLabel) ?: remoteWorkerLabel(captured.optString("worker"))) + " · " + captured.optString("model").ifBlank { "执行端默认模型" }, fontSize = 13.sp)
                if (captured.optString("modeId").isNotBlank()) SshCaption("请求模式：" + captured.optString("modeId"))
                if (captured.has("effort")) SshCaption("本次请求档位：${captured.optString("effort")}；真实读回在原任务另列，未因此增加额度或工具权限。")
                SelectionContainer { Text(captured.optString("prompt", captured.optJSONArray("argv")?.toString().orEmpty()), fontSize = 12.sp) }
                Text(if (configuredTeamHarness(captured.optString("worker"))) "使用所选电脑端已有的工具与权限配置，可能修改文件；工作目录不是操作系统沙箱。内部模型费用不能硬限，工具审批是否可用以实际协议回执为准。" else if (captured.optString("worker") == "pi") "本次仅为Pi无工具对话，不授权项目工具、命令或扩展；不提供逐工具审批，不宣称操作系统隔离。原设置可能重试/压缩，费用与次数未知。" else if (captured.optBoolean("readOnly")) "本次为Codex只读分析。" else "本次可能修改所选工作区。工具审批另按原任务逐次核对。", fontSize = 12.sp)
            }
        }, confirmButton = { Button(onClick = {
            val identity = UUID.randomUUID().toString(); operation = identity
            submittedRequest = controller.requestFeature("remote", "submitJob", JSONObject(captured.toString()).put("operationId", identity))
            review = null
        }, enabled = current && !registered && !submitting) { Text("确认创建一次") } }, dismissButton = { TextButton(onClick = { review = null }) { Text("返回修改") } })
    }
}

internal fun remoteWorkerLabel(worker: String): String = when (worker) { "codex_app_server" -> "Codex"; "claude" -> "Claude"; "pi" -> "Pi · 无工具对话"; "command" -> "明确命令"; else -> worker }

@Composable
internal fun SshTaskComposer(profile: JSONObject, state: PocketUiState, controller: PocketWorldController) {
    val id = profile.optString("id"); val binding = profile.optString("bindingHash")
    var goal by rememberSaveable(id, binding) { mutableStateOf("") }
    var client by rememberSaveable(id, binding) { mutableStateOf<String?>(null) }
    var request by rememberSaveable(id, binding) { mutableStateOf<String?>(null) }
    val busy = "owner.startSshTask" in state.moduleBusy
    val receipt = state.moduleResults["owner.startSshTask"].takeIf { request != null && state.moduleResultInputs["owner.startSshTask"]?.optString("_requestId") == request }
    Text("让本Agent在此主机办事", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
    SshCaption("目标绑定这次已核对的主机、凭据和目录。具体写入仍需在同一个原任务批准；普通SSH不保证断线后任务继续。")
    OutlinedTextField(goal, { if (client == null) goal = it }, label = { Text("这次要完成什么") }, minLines = 3, maxLines = 6, enabled = client == null, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
        val identity = client ?: UUID.randomUUID().toString().also { client = it }
        request = controller.requestOwnerAction("startSshTask", JSONObject().put("profileId", id).put("bindingHash", binding).put("clientMessageId", identity).put("goal", goal))
    }, enabled = !busy && receipt == null && goal.isNotBlank() && goal.length <= 12000 && profile.optBoolean("hostKeyTrusted") && profile.optBoolean("credentialsConfigured"), modifier = Modifier.padding(top = 10.dp)) { Text(if (busy) "正在登记原任务" else if (client != null) "核对同一输入回执（不另建）" else "开始这次SSH任务") }
    receipt?.optString("taskId")?.takeIf { it.isNotBlank() }?.let { task ->
        TextButton(onClick = { controller.focusTask(task) }) { Text("打开原任务与执行审批") }
        TextButton(onClick = { client = null; request = null; goal = "" }) { Text("另建一项SSH工作") }
    }
    state.moduleErrors["owner.startSshTask"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
}
