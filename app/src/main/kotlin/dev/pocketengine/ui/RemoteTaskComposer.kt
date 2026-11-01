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
    var readOnly by rememberSaveable(profileId, binding, worker) { mutableStateOf(true) }
    var timeout by rememberSaveable(profileId, binding) { mutableStateOf("300") }
    var probeRequest by rememberSaveable(profileId, binding, environmentId, worker, model) { mutableStateOf<String?>(null) }
    var operation by rememberSaveable(profileId, binding) { mutableStateOf<String?>(null) }
    var submittedRequest by rememberSaveable(profileId, binding) { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<JSONObject?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var inputRequest by remember(profileId, binding) { mutableStateOf<JSONObject?>(null) }
    val environments = state.moduleResults["remote.environments"]
        .takeIf { environmentRequest != null && state.moduleResultInputs["remote.environments"]?.optString("_requestId") == environmentRequest }
        ?.optJSONArray("environments").objects()
    val environment = environments.singleOrNull { it.optString("id") == environmentId }
    val workers = environment?.optJSONArray("workers").objects().filter { it.optString("id") in setOf("codex_app_server", "claude", "command") }
    val actualWorker = workers.singleOrNull { it.optString("id") == worker }
    val probe = state.moduleResults["remote.probeWorker"]
        .takeIf { probeRequest != null && state.moduleResultInputs["remote.probeWorker"]?.optString("_requestId") == probeRequest }
    val task = state.tasks.firstOrNull { it.id == operation || it.raw.optString("operationId") == operation && it.raw.optString("feature") == "remote" }
    val result = state.moduleResults["remote.submitJob"]
        .takeIf { submittedRequest != null && state.moduleResultInputs["remote.submitJob"]?.optString("_requestId") == submittedRequest }
    val unresolved = state.feature("remote").optJSONArray("operations").objects().filter { it.optString("profileId") == profileId && it.optString("kind", "job_submit") == "job_submit" && it.optString("status") in setOf("unknown", "submitting") }
    val registered = operation != null
    val reading = "remote.environments" in state.moduleBusy
    val probing = "remote.probeWorker" in state.moduleBusy
    val submitting = "remote.submitJob" in state.moduleBusy
    val canProbe = !registered && !probing && environment != null && actualWorker != null && actualWorker.opt("available") != false && worker != "command"
    val canCreate = unresolved.isEmpty() && !registered && !reading && !submitting && profile.optBoolean("hasToken") && binding.matches(Regex("[a-f0-9]{64}")) &&
        environment?.optString("bindingHash")?.matches(Regex("[a-f0-9]{64}")) == true && actualWorker != null && actualWorker.opt("available") != false &&
        (worker == "command" || probe?.optBoolean("available") == true)

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
        workers.forEach { item -> FilterChip(selected = worker == item.optString("id"), onClick = { if (!registered) worker = item.optString("id") }, enabled = !registered && item.opt("available") != false,
            label = { Text(remoteWorkerLabel(item.optString("id")) + if (item.opt("available") == false) " · 未安装" else if (item.isNull("available")) " · 待探测" else "") }) }
        if (worker == "command") {
            OutlinedTextField(argv, { argv = it }, label = { Text("明确参数JSON数组（不是Shell文本）") }, minLines = 2, maxLines = 5, enabled = !registered, modifier = Modifier.fillMaxWidth())
            SshCaption("仅运行电脑端allowlist中的命令；不会把自然语言或拼接字符串交给Shell。")
        } else {
            OutlinedTextField(goal, { goal = it }, label = { Text("希望在此工作区完成什么") }, minLines = 3, maxLines = 7, enabled = !registered, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("请求模型ID（可留空）") }, singleLine = true, enabled = !registered, modifier = Modifier.fillMaxWidth())
            if (worker == "codex_app_server") {
                Row { Checkbox(readOnly, { readOnly = it }, enabled = !registered); Text("只读分析，不授权修改文件", fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)) }
            } else SshCaption("Claude使用受限工具配置，不提供操作系统只读沙箱；模型和账号是否可用仍须真实运行确认。")
            OutlinedButton(onClick = {
                val input = JSONObject().put("profileId", profileId).put("_profileBinding", binding).put("environmentId", environmentId).put("environmentBinding", selected.optString("bindingHash")).put("worker", worker)
                if (model.isNotBlank()) input.put("model", model.trim())
                probeRequest = controller.requestFeature("remote", "probeWorker", input)
            }, enabled = canProbe) { Text(if (probing) "正在核对执行器" else "核对执行器与模型能力") }
            probe?.let { checked ->
                SshCaption(checked.optString("message", "执行器探测已返回") + "；未发起模型任务。")
                checked.optJSONArray("models").objects().take(12).forEach { available -> TextButton(onClick = { model = available.optString("id") }, enabled = !registered) { Text(available.optString("name", available.optString("id")), fontSize = 12.sp) } }
            }
        }
        OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit).take(4) }, label = { Text("任务时限（秒，10–3600）") }, singleLine = true, enabled = !registered)
        Button(onClick = {
            try {
                val request = JSONObject().put("profileId", profileId).put("bindingHash", binding).put("environmentId", environmentId).put("environmentBinding", selected.optString("bindingHash")).put("worker", worker).put("timeoutSeconds", timeout.toIntOrNull() ?: 0)
                if (worker == "command") request.put("argv", JSONArray(argv)) else {
                    request.put("prompt", goal).put("readOnly", worker == "codex_app_server" && readOnly)
                    if (model.isNotBlank()) request.put("model", model.trim())
                }
                review = RemoteTaskRequest.first(request); localError = null
            } catch (failure: Exception) { localError = failure.message ?: "请核对任务参数" }
        }, enabled = canCreate, modifier = Modifier.padding(top = 10.dp)) { Text("核对并创建这次工作") }
        if (worker != "command") {
            OutlinedButton(onClick = {
                try {
                    RemoteTaskRequest.requireInputCapability(selected)
                    val target = JSONObject().put("profileId", profileId).put("bindingHash", binding).put("environmentId", environmentId).put("environmentBinding", selected.optString("bindingHash")).put("worker", worker).put("timeoutSeconds", timeout.toIntOrNull() ?: 0)
                        .put("prompt", goal).put("readOnly", worker == "codex_app_server" && readOnly)
                    if (model.isNotBlank()) target.put("model", model.trim())
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
    review?.let { captured ->
        val current = profile.optString("bindingHash") == captured.optString("_profileBinding") && environment?.optString("bindingHash") == captured.optString("environmentBinding")
        AlertDialog(onDismissRequest = { review = null }, title = { Text("确认在所选电脑创建工作") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(profile.optString("name") + "\n" + profile.optString("baseUrl") + "\n" + environment?.optString("workspace").orEmpty())
                Text(remoteWorkerLabel(captured.optString("worker")) + " · " + captured.optString("model").ifBlank { "执行端默认模型" }, fontSize = 13.sp)
                SelectionContainer { Text(captured.optString("prompt", captured.optJSONArray("argv")?.toString().orEmpty()), fontSize = 12.sp) }
                Text(if (captured.optBoolean("readOnly")) "本次为Codex只读分析。" else "本次可能修改所选工作区。工具审批另按原任务逐次核对。", fontSize = 12.sp)
            }
        }, confirmButton = { Button(onClick = {
            val identity = UUID.randomUUID().toString(); operation = identity
            submittedRequest = controller.requestFeature("remote", "submitJob", JSONObject(captured.toString()).put("operationId", identity))
            review = null
        }, enabled = current && !registered && !submitting) { Text("确认创建一次") } }, dismissButton = { TextButton(onClick = { review = null }) { Text("返回修改") } })
    }
}

internal fun remoteWorkerLabel(worker: String): String = when (worker) { "codex_app_server" -> "Codex"; "claude" -> "Claude"; "command" -> "明确命令"; else -> worker }

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
