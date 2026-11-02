package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

internal fun mergeTeamSnapshot(previous: JSONObject?, incoming: JSONObject): JSONObject {
    if (previous != null) {
        val nextGeneration = incoming.optInt("generation")
        val previousGeneration = previous.optInt("generation")
        if (nextGeneration < previousGeneration) return previous
        if (nextGeneration == previousGeneration) {
            if (previous.has("revision") && incoming.has("revision")) {
                if (incoming.optLong("revision") < previous.optLong("revision")) return previous
            } else if (incoming.optLong("updatedAt", incoming.optLong("createdAt")) < previous.optLong("updatedAt", previous.optLong("createdAt"))) return previous
        }
    }
    return JSONObject(incoming.toString())
}
internal fun isTeamRunResult(command: String, value: JSONObject): Boolean = command in setOf("start", "get", "pause", "resume", "feedback", "cancel", "continueMessages", "reviseAssignment", "continueOutcome", "sendMessage", "configureMember") && value.optString("id").isNotBlank() && value.has("goal") && value.optJSONArray("members") != null
/** Receipts are native UI acknowledgements, not task state or model/executor result bodies. */
internal fun teamOwnerReceipts(state: PocketUiState, runId: String? = null, teamId: String? = null): Map<String, JSONObject> = state.teamOwnerReceipts.filterValues {
    (runId != null && it.optString("runId") == runId) || (teamId != null && it.optString("command") == "savePlan" && it.optString("teamId") == teamId)
}
private fun teamCommands(state: PocketUiState): Set<String> = state.feature("teams").optJSONArray("commands").let { array -> if (array == null) emptySet() else (0 until array.length()).map { array.optString(it) }.toSet() }
private fun teamBusyCommands(state: PocketUiState): Set<String> = state.moduleBusy.filter { it.startsWith("teams.") }.map { it.removePrefix("teams.") }.toSet()
internal fun teamRunStatus(status: String): String = when (status) { "completed" -> "本轮已有产出，待核对"; "partial" -> "部分分工未完成，待核对"; "awaiting_review" -> "真实文件成果等待审查"; else -> taskStatus(status) }
internal fun teamRunReferences(task: UiTask): List<String> {
    val result = linkedSetOf<String>()
    if (task.raw.optString("feature") == "teams" || task.raw.optString("kind") == "teams") result += task.raw.optString("externalId").ifBlank { task.id }
    task.raw.optJSONArray("relatedTasks").objects().filter { it.optString("kind", it.optString("feature")) == "teams" }.forEach { result += it.optString("runId", it.optString("taskId")) }
    return result.filter { it.isNotBlank() }
}
internal fun teamRunnerLabel(id: String): String = when (id) { "model" -> "模型服务"; "codex_app_server" -> "Codex app-server"; "claude" -> "Claude（未接入）"; "kimi" -> "Kimi（未接入）"; else -> id }
internal fun teamModeLabel(id: String): String = when(id){"produce"->"文件协作";"collaborate"->"共同分析";else->"取证与讨论"}
internal fun teamMemberStatus(id: String): String = when (id) { "model", "running" -> "工作中"; "queued" -> "等待本轮"; "paused" -> "已暂停"; "completed" -> "本轮完成"; "failed" -> "未完成"; "unsupported" -> "执行器未接入"; "blocked" -> "环境未就绪"; "blocked_unknown" -> "结果待核对"; "waiting_dependency" -> "等待前置分工"; "blocked_dependency" -> "前置未完成，已阻断"; "partial" -> "部分分工未完成"; "awaiting_approval" -> "等待本机授权"; else -> taskStatus(id) }

@Composable
internal fun TeamManagement(state: PocketUiState, controller: PocketWorldController) {
    var launch by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var planning by remember { mutableStateOf<JSONObject?>(null) }
    TeamCaption("成员、模型、执行器与设备分别配置。讨论记录、工具回执和每轮结果保留在同一个团队任务。")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { launch = true }) { Text("发起团队任务") }; OutlinedButton(onClick = { editing = JSONObject() }) { Text("创建团队") } }
    state.feature("teams").optJSONArray("teams").objects().forEach { team ->
        Column(Modifier.fillMaxWidth().padding(vertical = 15.dp)) {
            Text(team.optString("name"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            TeamCaption(teamModeLabel(team.optString("mode")) + " · " + team.optJSONArray("members").objects().size + " 名成员")
            Row { TextButton(onClick = { editing = team }) { Text("编辑成员与模式", fontSize = 12.sp) }; if ("savePlan" in teamCommands(state)) TextButton(onClick = { planning = team }) { Text("分工与依赖", fontSize = 12.sp) } }
        }
        HorizontalDivider(color = Line)
    }
    state.moduleResults["teams.probeMember"]?.let { probe -> TeamCaption(if (probe.optBoolean("available")) "最近一次实际成员探测返回可用；每轮执行仍会重新核验环境。" else probe.optString("message", "最近一次成员环境尚未验证通过")) }
    Text("原团队任务", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
    val history = state.feature("teams").optJSONArray("history").objects()
    (state.teamRuns.values + history).distinctBy { it.optString("id") }.sortedByDescending { it.optLong("updatedAt", it.optLong("createdAt")) }.forEach { run ->
        Column(Modifier.fillMaxWidth().clickable { controller.openTeam(run.optString("id")) }.padding(vertical = 14.dp)) { Text(run.optString("goal"), fontSize = 14.sp, lineHeight = 22.sp); TeamCaption(teamRunStatus(run.optString("status")) + " · " + run.optString("teamName") + " · " + teamModeLabel(run.optString("mode"))) }
        HorizontalDivider(color = Line)
    }
    if (launch) TeamLaunchSheet(state, controller, "") { launch = false }
    editing?.let { team -> TeamDefinitionEditor(team, state, controller) { editing = null } }
    planning?.let { selected ->
        val team = state.feature("teams").optJSONArray("teams").objects().find { it.optString("id") == selected.optString("id") } ?: selected
        val receipts = teamOwnerReceipts(state, teamId = team.optString("id"))
        TeamAssignmentEditor(team, teamBusyCommands(state), receipts.values.maxByOrNull { it.optLong("sequence") }, null, { command, payload -> controller.requestFeature("teams", command, payload) }, receipts) { planning = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamLaunchSheet(state: PocketUiState, controller: PocketWorldController, initialGoal: String, close: () -> Unit) {
    var goal by rememberSaveable { mutableStateOf(initialGoal) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var rounds by rememberSaveable { mutableIntStateOf(1) }
    var submitted by remember { mutableStateOf<String?>(null) }
    val teams = state.feature("teams").optJSONArray("teams").objects()
    val busy = "teams.start" in state.moduleBusy
    LaunchedEffect(state.moduleResults["teams.start"]) { if (submitted != null && state.moduleResultInputs["teams.start"]?.optString("_requestId") == submitted) close() }
    ModalBottomSheet(onDismissRequest = { if (!busy) close() }, containerColor = Color.White, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).navigationBarsPadding().imePadding()) {
            Text("交给团队协作", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            TeamCaption("选择现有团队后开始一次正式任务；成员会话和每轮证据都可回查。")
            if (teams.isEmpty()) { TeamCaption("尚未创建团队。可在连接与能力中配置角色、模型和真实执行位置。" ); TextButton(onClick = { controller.openConnection("teams"); close() }) { Text("配置团队") } }
            teams.forEach { team -> Row(Modifier.fillMaxWidth().clickable { selected = team.optString("id") }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected == team.optString("id"), { selected = team.optString("id") }); Column { Text(team.optString("name"), fontSize = 14.sp); TeamCaption(teamModeLabel(team.optString("mode")) + " · " + team.optJSONArray("members").objects().size + " 名成员") } } }
            val chosen = teams.find { it.optString("id") == selected }
            chosen?.optJSONArray("members").objects().filter { it.optString("runner") == "codex_app_server" }.forEach { slot ->
                TextButton(onClick = { controller.requestFeature("teams", "probeMember", JSONObject().put("teamId", selected).put("memberId", slot.optString("id"))) }, enabled = "teams.probeMember" !in state.moduleBusy) { Text("实际探测 " + slot.optString("name"), fontSize = 12.sp) }
            }
            if(chosen?.optString("mode")=="produce")TeamCaption("文件协作请从已选项目的原任务交给此团队，项目与审批沿用原任务。")
            else if (chosen != null && !teamLaunchable(chosen, state)) TeamCaption("请先配置模型连接，或实际探测团队中每个 Codex 成员。未接入的执行器不能开始任务。")
            OutlinedTextField(goal, { goal = it }, label = { Text("本次共同目标") }, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            val structuredDiscussion=chosen?.optString("discussionFormat")==dev.pocketengine.features.teams.TeamEvidenceDiscussion.FORMAT
            if(structuredDiscussion){val count=chosen!!.optJSONArray("members").objects().size;TeamCaption("本次依次完成独立观点、具体质疑、原作者回应，再由协调者给条件结论。模型请求上限 ${count*6+1} 次，阶段基础预计 ${count*3+1} 次；取证和重试同享上限。只读工具上限 ${count*4} 次，耗尽不会自动补满；不保证复杂问题足额。") }
            else Row(verticalAlignment = Alignment.CenterVertically) { Text("讨论轮数", fontSize = 12.sp); RadioButton(rounds == 1, { rounds = 1 }); Text("一轮", fontSize = 12.sp); RadioButton(rounds == 2, { rounds = 2 }); Text("两轮", fontSize = 12.sp) }
            state.moduleErrors["teams.start"]?.let { Text(it, color = Danger, fontSize = 12.sp, lineHeight = 20.sp) }
            Button(onClick = { selected?.let { submitted = controller.startTeam(it, goal, if(structuredDiscussion)2 else rounds) } }, enabled = chosen != null && teamLaunchable(chosen, state) && goal.isNotBlank() && goal.length <= 8000 && !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text(if (busy) "正在建立团队任务" else "开始这次协作") }
        }
    }
}

@Composable
internal fun TeamRunDetail(state: PocketUiState, controller: PocketWorldController) {
    val runId = state.teamRunId ?: return
    val run = state.teamRuns[runId]
    val assignmentFocus=state.teamAssignmentFocus?.takeIf { it.optString("teamRunId")==runId }
    var memberId by rememberSaveable(runId,assignmentFocus?.optString("assignmentId")) { mutableStateOf(assignmentFocus?.optString("memberId")) }
    var tab by rememberSaveable(runId) { mutableIntStateOf(0) }
    var round by rememberSaveable(runId, memberId) { mutableIntStateOf(0) }
    var childTaskId by rememberSaveable(runId) { mutableStateOf<String?>(null) }
    var collaborationOpen by rememberSaveable(runId) { mutableStateOf(true) }
    val commands = teamCommands(state)
    val definition = state.feature("teams").optJSONArray("teams").objects().find { it.optString("id") == run?.optString("teamId") }
    val receipts = teamOwnerReceipts(state, runId, definition?.optString("id"))
    val lastReceipt = receipts.values.maxByOrNull { it.optLong("sequence") }
    val members = run?.optJSONArray("members").objects()
    val member = members.find { it.optString("id") == memberId }
    val detailTitle = member?.optString("name") ?: run?.optString("teamName") ?: "团队任务"
    Dialog(onDismissRequest = { controller.closeTeam() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                if (!collaborationOpen) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { if (collaborationOpen) collaborationOpen = false else if (memberId == null) controller.closeTeam() else memberId = null }) { Text("返回") }; Text(detailTitle, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); TextButton(onClick = { controller.requestFeature("teams", "get", JSONObject().put("runId", runId)) }) { Text("刷新", fontSize = 12.sp) } }
                if (!collaborationOpen) HorizontalDivider(color = Line)
                if (collaborationOpen) TeamCollaborationPage(run, definition, commands, teamBusyCommands(state), lastReceipt, null, { command, payload -> controller.requestFeature("teams", command, payload) }, { controller.closeTeam() }, receipts, onDetails = { collaborationOpen = false }, workItems = {
                    if (run != null && (run.optJSONObject("workPlan") != null || run.optString("status") == "completed" && run.optString("discussionFormat") == dev.pocketengine.features.teams.TeamEvidenceDiscussion.FORMAT)) TeamWorkPlanControls(run, controller::openTeamWorkPlanPage, controller::runEcosystemControlWork, { id -> childTaskId = id; controller.getTask(id) }, controller::openTeamArtifact, controller::openTeamProjectRoundPage, controller::openTeamFailedProjectRoundPage)
                }, outcomes = { chosen ->
                    if (run != null && run.optString("mode") == "produce") {
                        if (run.optJSONObject("productionScope")?.optJSONArray("assignments").objects().any { it.optJSONObject("remoteSelection")?.optBoolean("fullProject") == true }) TeamPhoneIntegratorControls(runId, controller::openTeamPhoneIntegratorPage, controller::runEcosystemControlWork)
                        TeamWritePanel(run, chosen, state, controller) { id -> childTaskId = id; controller.getTask(id) }
                        TeamWorkspaceArtifactLinks(run, chosen?.optString("id"), controller::openTeamArtifact)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("成员与协作资料", style = MaterialTheme.typography.titleSmall)
                    if (run != null) {
                        TeamRunMemberControls(run, "configureMember" in teamBusyCommands(state), modelSnapshot = { controller.nativeModelConnections()?.snapshot() ?: error("原模型连接不可用") }, runWork = controller::runEcosystemControlWork) { command, input -> controller.requestFeature("teams", command, input) }
                        TeamMemberMaterialControls(run, controller::openTeamMemberMaterialsPage, controller::runEcosystemControlWork)
                        TeamResearchBudgetControls(run, controller::openTeamResearchBudgetPage, controller::runEcosystemControlWork)
                    }
                })
                else if (run == null) TeamCaption("正在读取真实团队任务…")
                else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 14.dp)) {
                    Text(run.optString("goal"), fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
                    TeamCaption(teamRunStatus(run.optString("status")) + " · " + teamModeLabel(run.optString("mode")) + (if(run.optString("discussionFormat")==dev.pocketengine.features.teams.TeamEvidenceDiscussion.FORMAT)" · "+dev.pocketengine.features.teams.TeamEvidenceDiscussion.label(run.optInt("round")) else " · 第 " + run.optInt("round") + " / " + run.optInt("rounds") + " 轮"))
                    lastReceipt?.takeIf { !it.optBoolean("ok") }?.let { Text(it.optString("message"), color = Danger, fontSize = 12.sp, lineHeight = 20.sp) }
                    if(run.optString("mode")=="produce")TeamWritePanel(run,member,state,controller){id->childTaskId=id;controller.getTask(id)}
                    TextButton(onClick = { collaborationOpen = true }) { Text("协作分工与成员消息", fontSize = 12.sp) }
                    if (member != null) {
                        AgentIdentity(member, state)
                        TeamControls(run, member, commands, state, controller)
                    } else {
                        TeamControls(run, null, commands, state, controller)
                        members.forEach { slot -> Column(Modifier.fillMaxWidth().clickable { memberId = slot.optString("id"); tab = 0 }.padding(vertical = 14.dp)) { Text(slot.optString("name") + if (slot.optString("id") == run.optString("leaderId")) " · Leader" else "", fontSize = 14.sp, fontWeight = FontWeight.Medium); TeamCaption(slot.optString("role")); Text(teamMemberStatus(slot.optString("status")) + " · " + teamRunnerLabel(slot.optString("runner", "model")) + " · " + slot.optString("model"), fontSize = 11.sp, color = SecondaryInk) }; HorizontalDivider(color = Line) }
                    }
                    if(run.optString("mode")=="produce")TeamWorkspaceArtifactLinks(run,memberId,controller::openTeamArtifact)
                    ScrollableTabRow(selectedTabIndex = tab, containerColor = Color.White, edgePadding = 0.dp) { listOf("会话", "任务", "活动", "证据").forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title, fontSize = 12.sp) }) } }
                    when (tab) {
                        0 -> {
                            Row { FilterChip(selected = round == 0, onClick = { round = 0 }, label = { Text("全部轮次") }); for (n in 1..run.optInt("rounds", 1)) FilterChip(selected = round == n, onClick = { round = n }, label = { Text("第 " + n + " 轮") }) }
                            val messages = run.optJSONArray("transcript").objects().filter { (memberId == null || it.optString("memberId") in setOf(memberId, "*")) && (round == 0 || it.optInt("round") == round || it.optInt("round") == 0) }
                            messages.forEach { message -> val who = members.find { it.optString("id") == message.optString("memberId") }?.optString("name") ?: if (message.optString("role") == "user") "你" else "团队"; Text(who + " · " + teamMessageKind(message.optString("kind")) + if (message.optInt("round") > 0) " · 第 " + message.optInt("round") + " 轮" else "", fontSize = 11.sp, color = SecondaryInk, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)); SelectionContainer { Text(message.optString("content"), fontSize = 14.sp, lineHeight = 24.sp) }; Text(teamTime(message.optLong("at")), fontSize = 10.sp, color = SecondaryInk, modifier = Modifier.padding(top = 6.dp)) }
                            if (messages.isEmpty()) TeamCaption("这一范围尚未产生会话记录。")
                            if (member == null && run.optString("summary").isNotBlank()) { Text(if (run.optBoolean("summaryStale")) "已有总结（有新补充，等待更新）" else "团队总结", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 22.dp)); SelectionContainer { Text(run.optString("summary"), fontSize = 14.sp, lineHeight = 24.sp) } }
                            TeamFeedback(runId, memberId, run, state, controller)
                        }
                        1 -> run.optJSONObject("board")?.optJSONArray("cards").objects().filter { memberId == null || it.optString("memberId") == memberId }.forEach { card -> Text(card.optString("title"), fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 14.dp)); TeamCaption("第 " + card.optInt("round") + " 轮 · " + teamMemberStatus(card.optString("status"))) }
                        2 -> run.optJSONObject("board")?.optJSONArray("activity").objects().filter { memberId == null || it.optString("memberId") in setOf(memberId, "") }.reversed().forEach { item -> Text(item.optString("text"), fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 12.dp)); Text(teamTime(item.optLong("at")), fontSize = 10.sp, color = SecondaryInk) }
                        3 -> {
                            val sources = if (member != null) listOf(member) else members
                            sources.forEach { slot -> slot.optJSONArray("evidence").objects().forEach { evidence -> TeamEvidence(slot.optString("name"), evidence) } }
                            if (sources.all { it.optJSONArray("evidence").objects().isEmpty() }) TeamCaption("尚未产生实际工具回执。")
                        }
                    }
                    member?.optJSONObject("external")?.let { external ->
                        val profileId = member.optJSONObject("device")?.optString("profileId").orEmpty()
                        val jobId = external.optString("id", external.optString("jobId"))
                        if (profileId.isNotBlank() && jobId.isNotBlank()) RemoteApprovalBlock(profileId, jobId, external.optJSONArray("pendingApprovals").objects(), controller)
                        external.optString("hostTaskId").takeIf { it.isNotBlank() && it != runId }?.let { id -> TextButton(onClick = { childTaskId = id; controller.getTask(id) }) { Text("查看成员的真实电脑任务") } }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        BackHandler { if (collaborationOpen) controller.closeTeam() else if (memberId != null) memberId = null else collaborationOpen = true }
    }
    childTaskId?.let { id -> state.tasks.find { it.id == id }?.let { task -> TaskSheet(task, state.activeTaskId == id, controller) { childTaskId = null } } }
}

@Composable
private fun TeamControls(run: JSONObject, member: JSONObject?, commands: Set<String>, state: PocketUiState, controller: PocketWorldController) {
    val runId = run.optString("id")
    var problem by remember(runId, member?.optString("id")) { mutableStateOf<String?>(null) }
    val status = member?.optString("status") ?: run.optString("status")
    val paused = member?.optBoolean("paused") == true || status in setOf("paused", "interrupted", "blocked", "blocked_unknown", "awaiting_approval")
    if (status == "blocked_unknown") TeamCaption("原外部操作结果待核对。继续只查询原操作；有新补充也不会自动重发。")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val command = if (paused) "resume" else "pause"
        OutlinedButton(onClick = { try { controller.requestFeature("teams", command, reviewedTeamCollaborationAction(run, teamCollaborationStamp(run), command, member?.optString("id"))); problem = null } catch (failure: Exception) { problem = failure.message } }, enabled = command in commands && ("teams." + command) !in state.moduleBusy && run.optString("status") !in setOf("completed", "cancelled")) { Text(if (paused) "继续" else "暂停", fontSize = 12.sp) }
        if (member == null) TextButton(onClick = { controller.requestFeature("teams", "cancel", JSONObject().put("runId", runId)) }, enabled = "cancel" in commands && run.optString("status") !in setOf("completed", "cancelled")) { Text("停止全队", color = Danger, fontSize = 12.sp) }
    }
    problem?.let { Text(it, color = Danger, fontSize = 12.sp) }
}

@Composable
private fun AgentIdentity(member: JSONObject, state: PocketUiState) {
    Text(member.optString("role"), fontSize = 13.sp, lineHeight = 22.sp)
    TeamCaption("执行器：" + teamRunnerLabel(member.optString("runner", "model")))
    TeamCaption("模型：" + member.optString("model", state.modelName))
    val device = member.optJSONObject("device")
    TeamCaption(if (device == null) "位置：由手机接入模型服务" else "位置：" + (state.feature("remote").optJSONArray("profiles").objects().find { it.optString("id") == device.optString("profileId") }?.optString("name") ?: "已配置电脑") + " / " + device.optString("environmentId"))
    TeamCaption("状态：" + teamMemberStatus(member.optString("status")))
    if (member.optString("runner") != "model") TeamCaption(if (member.optJSONObject("runtime")?.optBoolean("available") == true) "本轮环境已实际探测" else "执行环境尚未取得可用证明")
}

@Composable
private fun TeamFeedback(runId: String, memberId: String?, run: JSONObject, state: PocketUiState, controller: PocketWorldController) {
    var text by rememberSaveable(runId, memberId) { mutableStateOf("") }
    var pending by remember(runId, memberId) { mutableStateOf<Pair<String, String>?>(null) }
    var problem by remember(runId, memberId) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.teamOwnerReceipts, pending) {
        val action = pending
        val receipt = action?.let { state.teamOwnerReceipts[it.first] }
        if (action != null && teamCollaborationResultMatches(receipt, action.first, "feedback", runId)) {
            if (receipt?.optBoolean("ok") == true && text == action.second) text = ""
            if (receipt?.optBoolean("ok") == false) problem = receipt.optString("message", "补充未保存")
            pending = null
        }
    }
    Text(if (memberId == null) "给全队补充" else "给这名成员发消息", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 24.dp))
    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), minLines = 2, maxLines = 5, label = { Text("补充要求或指出问题") })
    val supported = state.feature("teams").optJSONArray("commands").let { commands -> commands != null && (0 until commands.length()).any { commands.optString(it) == "feedback" } }
    Button(onClick = { try { pending = controller.requestFeature("teams", "feedback", reviewedTeamCollaborationAction(run, teamCollaborationStamp(run), "feedback", memberId, text)) to text; problem = null } catch (failure: Exception) { problem = failure.message } }, enabled = supported && pending == null && text.isNotBlank() && text.length <= 4000 && "teams.feedback" !in state.moduleBusy && run.optString("status") != "cancelled", modifier = Modifier.padding(top = 10.dp)) { Text("保存补充并暂停对应成员", fontSize = 12.sp) }
    problem?.let { Text(it, color = Danger, fontSize = 12.sp) }
    run.optJSONArray("inbox").objects().filter { memberId == null || it.optString("targetMemberId") in setOf(memberId, "*") }.takeLast(8).forEach { letter -> TeamCaption(when (letter.optString("status")) { "consumed" -> "补充已用于对应成员的工作"; "partially_consumed" -> "部分成员已使用补充，其余仍在队列"; else -> "补充已排队，等待成员继续后使用" }) }
}

@Composable
private fun TeamEvidence(name: String, evidence: JSONObject) {
    var expanded by remember(evidence.toString()) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) { Text(name + " · " + evidence.optString("tool"), fontSize = 13.sp, fontWeight = FontWeight.Medium); TeamCaption("实际工具回执 · " + teamTime(evidence.optLong("at"))); TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起回执" else "查看参数与回执", fontSize = 11.sp) }; if (expanded) SelectionContainer { Text(evidence.toString(2), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 19.sp) } }
    HorizontalDivider(color = Line)
}
private fun teamMessageKind(value: String): String = when (value) { "goal" -> "共同目标"; "intervention" -> "补充要求"; "answer" -> "本轮产出"; "summary" -> "汇总"; "evidence" -> "证据"; "error" -> "执行提示"; "external_receipt" -> "外部执行回执"; else -> value }
private fun teamTime(value: Long): String = if (value <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
@Composable internal fun TeamCaption(value: String) { Text(value, color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp, bottom = 9.dp)) }
