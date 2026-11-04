package dev.pocketengine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.remote.RemoteHarnessSelection
import org.json.JSONObject

internal fun configuredTeamHarness(id: String): Boolean = RemoteHarnessSelection.isGenericWorker(id)
internal fun externalTeamHarness(id: String): Boolean = RemoteHarnessSelection.isTeamWorker(id)
internal fun nativeHarnessWorkerLabel(worker: JSONObject): String = worker.optString("label").ifBlank { worker.optString("name").ifBlank { teamRunnerLabel(worker.optString("id")) } }
internal fun nativeHarnessProbeReady(probe: JSONObject?, worker: JSONObject?): Boolean = probe != null && worker != null &&
    worker.optString("configurationRevision") == probe.optString("configurationRevision") &&
    runCatching { RemoteHarnessSelection.fromProbe(probe, worker.optString("id")); true }.getOrDefault(false)
internal fun nativeHarnessOptions(probe: JSONObject?, models: Boolean): List<Pair<String, String>> {
    if (probe == null) return emptyList()
    val key = if (models) "models" else "modes"
    val values = probe.optJSONArray(key) ?: probe.optJSONObject(key)?.optJSONArray(if (models) "availableModels" else "availableModes")
        ?: if (models) probe.optJSONObject("modelOptions")?.optJSONArray("models") else null
    val result = values.objects().mapNotNull { value ->
        val id = value.optString(if (models) "modelId" else "id").ifBlank { value.optString("id") }
        if (id.isBlank()) null else id to value.optString("name").ifBlank { value.optString("displayName").ifBlank { id } }
    }
    if (result.isNotEmpty()) return result.distinctBy { it.first }
    val config = probe.optJSONObject("sessionOptions")?.optJSONArray("configOptions").objects().firstOrNull { it.optString("category") == if (models) "model" else "mode" }
    return config?.optJSONArray("options").objects().flatMap { item -> item.optJSONArray("options").objects().ifEmpty { listOf(item) } }.mapNotNull { item ->
        val id = item.optString("value").ifBlank { item.optString("id") }
        if (id.isBlank()) null else id to item.optString("name").ifBlank { id }
    }.distinctBy { it.first }
}

/** Reads the selected computer's directory and probe. No execution grant is stored in a team draft. */
@Composable
internal fun TeamHarnessFields(slot: AgentSlotDraft, state: PocketUiState, controller: PocketWorldController, busy: Boolean,
                               production: Boolean, update: (AgentSlotDraft) -> Unit, onReady: (Boolean) -> Unit) {
    val profile = state.feature("remote").optJSONArray("profiles").objects().firstOrNull { it.optString("id") == slot.profileId }
    var directory by remember(slot.profileId) { mutableStateOf<JSONObject?>(null) }
    var evidence by remember(slot.id) { mutableStateOf<JSONObject?>(null) }
    var evidenceKey by remember(slot.id) { mutableStateOf("") }
    var optionCatalog by remember(slot.profileId, slot.environmentId, slot.runner) { mutableStateOf<JSONObject?>(null) }
    var pendingProbe by remember(slot.id) { mutableStateOf<String?>(null) }
    var pendingKey by remember(slot.id) { mutableStateOf("") }
    var expanded by remember(slot.id) { mutableStateOf(false) }
    val environment = directory?.optJSONArray("environments").objects().firstOrNull { it.optString("id") == slot.environmentId }
    val workers = environment?.optJSONArray("workers").objects().filter { externalTeamHarness(it.optString("id")) }
    val worker = workers.firstOrNull { it.optString("id") == slot.runner }
    val key = listOf(slot.profileId, profile?.optString("bindingHash"), slot.environmentId, environment?.optString("bindingHash"), slot.runner, slot.model.trim(), slot.modeId).joinToString("\n")
    val probe = evidence.takeIf { evidenceKey == key }
    val configured = configuredTeamHarness(slot.runner)
    val verified = probe?.optBoolean("available") == true && (!configured || nativeHarnessProbeReady(probe, worker))
    val productionCapability = !production || RemoteHarnessSelection.isProductionWorker(slot.runner) && worker?.optJSONObject("capabilities")?.optBoolean(if (slot.fullProject) "projectProduction" else "assignmentWorkspace") == true
    val ready = profile != null && environment != null && worker != null && worker.opt("available") != false && verified && productionCapability
    val reading = "remote.environments" in state.moduleBusy || "remote.probeWorker" in state.moduleBusy
    LaunchedEffect(ready, key, production, slot.fullProject) { onReady(ready) }
    LaunchedEffect(state.moduleResults["remote.environments"], state.moduleResultInputs["remote.environments"]) {
        if (state.moduleResultInputs["remote.environments"]?.optString("profileId") == slot.profileId) {
            directory = state.moduleResults["remote.environments"]?.let { JSONObject(it.toString()) }
            evidence = null
        }
    }
    LaunchedEffect(state.moduleResults["remote.probeWorker"], state.moduleResultInputs["remote.probeWorker"]) {
        if (pendingProbe != null && state.moduleResultInputs["remote.probeWorker"]?.optString("_requestId") == pendingProbe) {
            state.moduleResults["remote.probeWorker"]?.let { evidence = JSONObject(it.toString()); optionCatalog = JSONObject(it.toString()); evidenceKey = pendingKey }
            pendingProbe = null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("在哪台电脑上工作", fontSize = 12.sp)
        state.feature("remote").optJSONArray("profiles").objects().forEach { candidate ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(slot.profileId == candidate.optString("id"), enabled = !busy && !reading, onClick = {
                    update(slot.copy(runner = "computer", profileId = candidate.optString("id"), environmentId = "", model = "", modeId = "", externalExecutionRiskAcknowledged = false, remoteBillingAcknowledged = false, sourceTransferAcknowledged = false, remoteCommandsAcknowledged = false))
                    controller.requestFeature("remote", "environments", JSONObject().put("profileId", candidate.optString("id")))
                })
                Text(candidate.optString("name"), fontSize = 12.sp)
            }
        }
        if (slot.profileId.isNotBlank()) {
            TextButton(enabled = !busy && !reading && profile != null, onClick = { evidence = null; controller.requestFeature("remote", "environments", JSONObject().put("profileId", slot.profileId)) }) { Text("读取该电脑的环境") }
            directory?.optJSONArray("environments").objects().forEach { candidate ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(slot.environmentId == candidate.optString("id"), enabled = !busy && !reading, onClick = { update(slot.copy(runner = "computer", environmentId = candidate.optString("id"), model = "", modeId = "", externalExecutionRiskAcknowledged = false, remoteBillingAcknowledged = false, sourceTransferAcknowledged = false, remoteCommandsAcknowledged = false)) })
                    Text(candidate.optString("label").ifBlank { candidate.optString("id") }, fontSize = 12.sp)
                }
            }
        }
        if (profile == null && slot.profileId.isNotBlank()) TeamCaption("原电脑连接当前不可用，请重新选择。")
        if (environment != null) {
            TeamCaption("工作目录：" + environment.optString("workspace"))
            Text("这台电脑已配置的执行器", fontSize = 12.sp)
            workers.forEach { candidate -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(slot.runner == candidate.optString("id"), enabled = !busy && !reading, onClick = { update(slot.copy(runner = candidate.optString("id"), model = "", modeId = "", externalExecutionRiskAcknowledged = false)) })
                Text(nativeHarnessWorkerLabel(candidate) + if (candidate.opt("available") == false) " · 当前不可用" else "", fontSize = 12.sp)
            } }
            if (workers.isEmpty()) TeamCaption("此环境没有返回可作为团队成员的执行器。")
        }
        if (worker != null) {
            TeamCaption(nativeHarnessWorkerLabel(worker) + "负责调用模型与工具，运行在" + profile?.optString("name").orEmpty() + "上。")
            OutlinedTextField(slot.model, { update(slot.copy(model = it)) }, enabled = !busy && !reading, label = { Text("执行器请求的模型 ID（可留空）") }, singleLine = true,
                supportingText = { Text("留空使用执行器默认模型；填写或更换后需核对实际会话。") })
            val models = nativeHarnessOptions(optionCatalog, true)
            if (models.isNotEmpty()) ActualHarnessOptions("从实际模型目录选择", slot.model, models, !busy && !reading) { update(slot.copy(model = it)) }
            val modes = nativeHarnessOptions(optionCatalog, false)
            if (modes.isNotEmpty() || slot.modeId.isNotBlank()) ActualHarnessOptions("执行模式", slot.modeId, modes, !busy && !reading) { update(slot.copy(modeId = it)) }
            if (slot.runner != "codex_app_server") Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(slot.externalExecutionRiskAcknowledged, { update(slot.copy(externalExecutionRiskAcknowledged = it)) }, enabled = !busy)
                Text("我了解此成员使用电脑端已配置的工具权限，工作目录不是隔离沙箱，内部模型费用不能硬限。", fontSize = 12.sp)
            }
            OutlinedButton(enabled = !busy && !reading, onClick = {
                evidence = null; pendingKey = key
                val input = JSONObject().put("profileId", slot.profileId).put("environmentId", slot.environmentId).put("environmentBinding", environment?.optString("bindingHash")).put("worker", slot.runner)
                if (slot.model.isNotBlank()) input.put("model", slot.model.trim())
                if (slot.modeId.isNotBlank()) input.put("modeId", slot.modeId)
                pendingProbe = controller.requestFeature("remote", "probeWorker", input)
            }) { Text("核对这名成员的执行器") }
            if (probe != null) {
                TeamCaption(if (probe.optBoolean("available")) if (probe.optBoolean("protocolVerified")) "执行器协议已核对，尚未发送工作任务。" else "执行器返回可用声明，实际会话以运行回执为准。" else probe.optJSONObject("reason")?.optString("message").orEmpty().ifBlank { probe.optString("message", "本次未确认可用。") })
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起本次能力" else "查看本次能力") }
                if (expanded) {
                    TeamCaption(if (probe.optBoolean("sessionAccessVerified")) "已建立测试会话，尚未进行模型推理。" else "尚未验证登录与实际模型调用。")
                    if (probe.optString("reportedModel").isNotBlank()) TeamCaption("执行端报告模型：" + probe.optString("reportedModel"))
                    val caps = probe.optJSONObject("capabilities") ?: JSONObject()
                    if (caps.optBoolean("teamToolsOnly") && caps.opt("codingToolsSupported") == false) TeamCaption("仅开放团队协作工具，未开放代码、命令与文件工具。")
                    if (probe.optBoolean("noTools")) TeamCaption("这次探测未挂载工具；团队运行使用哪些工具，以原任务的实际能力回执为准。")
                    listOf("supportsResume" to "恢复原会话", "inputSteering" to "运行中补充", "turnInterrupt" to "请求停止", "interactiveApprovals" to "工具审批", "structuredUserInput" to "向你提问", "teamTools" to "团队消息", "assignmentWorkspace" to "所选文件制作", "projectProduction" to "完整项目制作").forEach { (id, title) ->
                        if (caps.has(id)) TeamCaption(title + "：" + if (caps.opt(id) == true) "本次报告支持" else if (caps.opt(id) == false) "当前不支持" else "尚未核实")
                    }
                }
            } else TeamCaption("配置和模型变化后重新核对；每次启动仍会检查实际能力。")
            if (!productionCapability) TeamCaption("此执行器未声明支持当前文件制作方式，不能保存为这项制作分工。")
            state.moduleErrors["remote.probeWorker"]?.let { TeamCaption(it) }
        }
    }
}

@Composable
private fun ActualHarnessOptions(title: String, selected: String, options: List<Pair<String, String>>, enabled: Boolean, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(enabled = enabled, onClick = { open = true }) { Text(title + "：" + options.firstOrNull { it.first == selected }?.second.orEmpty().ifBlank { selected.ifBlank { "执行器默认" } }, fontSize = 12.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("执行器默认") }, onClick = { open = false; choose("") })
            options.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; choose(id) }) }
        }
    }
}
