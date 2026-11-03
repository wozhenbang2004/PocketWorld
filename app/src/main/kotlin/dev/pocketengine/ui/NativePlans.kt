package dev.pocketengine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

/** Existing goal entry; all task controls and results stay in the original main task workbench. */
@Composable internal fun NativePlans(state: PocketUiState, controller: PocketWorldController) {
    val key = "owner.automationPlan"
    val result = state.moduleResults[key]
    var snapshot by remember { mutableStateOf<JSONObject?>(null) }
    val plans = snapshot?.optJSONArray("plans").objects()
    val scheduleProposals = snapshot?.optJSONArray("scheduleProposals").objects()
    val busy = key in state.moduleBusy
    var selected by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf<JSONObject?>(null) }
    var editing by remember { mutableStateOf<Pair<JSONObject, JSONObject>?>(null) }
    var cancelling by remember { mutableStateOf<JSONObject?>(null) }
    var createRequest by remember { mutableStateOf<String?>(null) }
    var dialogRequest by remember { mutableStateOf<String?>(null) }
    var observedTasks by remember { mutableStateOf("") }
    val planChange = state.feature("automation").optLong("nativePlanChange")
    var observedPlanChange by remember { mutableStateOf(planChange) }
    fun send(command: String, payload: JSONObject = JSONObject()): String = controller.requestOwnerAction("automationPlan", JSONObject().put("command", command).put("payload", payload))
    LaunchedEffect(Unit) { send("snapshot") }
    LaunchedEffect(creating, editing?.first?.optString("id")) { if (creating || editing != null) send("snapshot") }
    LaunchedEffect(reviewing?.optString("id")) { if (reviewing != null) controller.refresh() }
    LaunchedEffect(result?.optJSONObject("snapshot")) {
        result?.optJSONObject("snapshot")?.let { incoming ->
            if (snapshot == null || snapshot?.optString("snapshotProcess") != incoming.optString("snapshotProcess") || incoming.optLong("snapshotSequence") >= (snapshot?.optLong("snapshotSequence") ?: 0)) snapshot = JSONObject(incoming.toString())
        }
    }
    LaunchedEffect(state.tasks, busy) {
        val signature = state.tasks.joinToString("|") { it.id + ":" + it.raw.optString("status") + ":" + it.raw.optLong("updatedAt") }
        if (!busy && signature != observedTasks && plans.any { it.optJSONObject("execution") != null }) { observedTasks = signature; send("snapshot") }
    }
    LaunchedEffect(state.moduleResultInputs[key]?.optString("_requestId")) {
        val acknowledgement = state.moduleResultInputs[key]?.optString("_requestId")
        if (createRequest != null && acknowledgement == createRequest && result?.optString("command") == "create") result.optJSONObject("plan")?.let { selected = it.optString("id"); creating = false; reviewing = JSONObject(it.toString()); createRequest = null }
        if (dialogRequest != null && acknowledgement == dialogRequest) { reviewing = null; editing = null; dialogRequest = null }
    }
    LaunchedEffect(planChange, busy) {
        if (!busy && planChange != observedPlanChange) {
            kotlinx.coroutines.delay(120) // Coalesce native Book events after the current command ACK.
            observedPlanChange = planChange
            send("snapshot")
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("持续目标", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = { send("snapshot") }, enabled = !busy) { Text("刷新") }
    }
    PlanCaption("审阅一次目标与范围，按依赖、时间和实际条件继续。成果以原任务的真实凭证推进。")
    state.moduleErrors[key]?.let { Text(it, color = Danger, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp)) }
    if (busy && result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    val plan = plans.find { it.optString("id") == selected }
    if (plan == null) {
        Button(onClick = { creating = true }, enabled = !busy) { Text("建立一个持续目标") }
        if (scheduleProposals.isNotEmpty()) {
            Text("原对话提出的时间草案", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 20.dp))
            PlanCaption("草案没有排程或执行权。核对固定时间、目标和原版本后，只有你在此明确批准才保存提醒；手机等实际执行仍走原授权入口。语义条件监控暂不支持。")
            scheduleProposals.forEach { row -> OrdinaryScheduleDraft(row, busy) { id, digest -> send("approveSchedule", JSONObject().put("id", id).put("digest", digest)) } }
        }
        if (plans.isEmpty() && !busy) PlanCaption("从一个明确目标开始，安排成功路径和需要时的失败恢复。文件成果可按你指定的标准自动核验。")
        plans.forEach { row ->
            Column(Modifier.fillMaxWidth().clickable { selected = row.optString("id") }.padding(vertical = 18.dp)) {
                Text(row.optString("title"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                PlanCaption(executionPlanStateLabel(row.optString("state")) + " · ${row.optInt("completedSteps")}/${row.optInt("totalSteps")} 步有凭证")
                row.optJSONObject("nextStep")?.let { Text("接下来 · " + it.optString("title"), fontSize = 14.sp, color = SecondaryInk) }
            }
            HorizontalDivider(color = Line)
        }
    } else {
        TextButton(onClick = { selected = null }) { Text("返回目标列表") }
        Text(plan.optString("goal", plan.optString("title")), fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        PlanCaption(executionPlanStateLabel(plan.optString("state")) + if (plan.optJSONObject("execution") != null && !plan.optBoolean("approvalValid") && plan.optString("state") != "review_required") " · 修改后待审阅" else "")
        val execution = plan.optJSONObject("execution")
        val paused = execution?.optString("state") == "paused"
        val terminal = plan.optString("state") in setOf("completed", "cancelled", "finished_with_failures")
        if (execution == null) {
            if (plan.optString("goal").isNotBlank() && plan.optJSONArray("steps").objects().all { it.optString("goal").isNotBlank() })
                Button(onClick = { reviewing = JSONObject(plan.toString()) }, enabled = !busy) { Text("审阅目标与执行范围") }
            else PlanCaption("这是已保留的历史跟进计划，继续从原任务核对结果。它没有执行定义，不会被自动启用。")
            if (!terminal) TextButton(onClick = { cancelling = JSONObject(plan.toString()) }) { Text("取消跟进计划", color = Danger) }
        } else {
            if (plan.optString("state") == "finished_with_failures" && !paused) OutlinedButton(onClick = { send("pause", planRef(plan)) }, enabled = !busy) { Text("审阅失败与重试") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!terminal && !plan.optBoolean("approvalValid")) Button(onClick = { reviewing = JSONObject(plan.toString()) }, enabled = !busy) { Text("审阅修改后的目标") }
                else if (!terminal && paused) Button(onClick = { send("resume", planRef(plan)) }, enabled = !busy) { Text("继续同一目标") }
                else if (!terminal && !execution.optBoolean("started")) Button(onClick = { send("start", planRef(plan)) }, enabled = !busy) { Text("开始目标") }
                if (!terminal && !paused) OutlinedButton(onClick = { send("pause", planRef(plan)) }) { Text("暂停") }
                if (!terminal) TextButton(onClick = { cancelling = JSONObject(plan.toString()) }) { Text("取消目标", color = Danger) }
            }
            PlanCaption("任务次数已用 ${execution.optInt("admissions")} / ${execution.optInt("maxTaskAdmissions")} · " + if (execution.optString("mode") == "automatic") "按计划自动推进" else "手动开始")
            if (plan.optBoolean("originalTasksUnsettled") && paused) Text("后继已暂停；原任务仍在结束或等待核对，下面保留其真实状态。", fontSize = 14.sp, lineHeight = 22.sp, color = SecondaryInk)
        }
        plan.optJSONObject("requirements")?.optJSONArray("blockers").let { blockers ->
            for (i in 0 until (blockers?.length() ?: 0)) Text(blockers!!.optString(i), color = SecondaryInk, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
        planWaitState(plan)?.let { waiting ->
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(waiting.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                waiting.lines.forEach { Text(it, color = SecondaryInk, fontSize = 14.sp, lineHeight = 23.sp) }
                waiting.observedAt?.let { PlanCaption("本机观察 · ${planTime(it)}") }
                waiting.nextAt?.let { PlanCaption("计划再次核对 · ${planTime(it)}；系统计时可能延后，原任务事件或手动核对可能更早。") }
                if (waiting.recheck) Button(onClick = { send("refresh", planRef(plan)) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("核对并继续此目标") }
                if (waiting.resumeOriginal) Button(onClick = { send("resume", planRef(plan)) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("继续同一原任务") }
                if (waiting.inspect) OutlinedButton(onClick = { send("inspect", planRef(plan)) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("只核对原任务状态") }
            }
        }
        plan.optJSONObject("runtimeDecision")?.let { decision ->
            if (decision.optString("reason") == "task_admission_budget_exhausted") PlanCaption("目标任务次数已用完，启动尚未确认的记录也保留占用。")
            if (decision.optString("reason") in setOf("approval_expired", "task_admission_budget_exhausted")) TextButton(onClick = { reviewing = JSONObject(plan.toString()) }) { Text("审阅目标窗口与预算") }
        }
        plan.optJSONArray("steps").objects().forEachIndexed { index, step ->
            val active = !step.optString("status").matches(Regex("completed|failed|cancelled|skipped"))
            Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text((index + 1).toString().padStart(2, '0'), color = SecondaryInk, fontSize = 14.sp, modifier = Modifier.width(34.dp).padding(top = 3.dp))
                    Column(Modifier.weight(1f)) {
                        Text(step.optString("goal", step.optString("title")), fontSize = 14.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
                        PlanCaption(stepStateLabel(step) + " · 原任务：" + originalTaskLabel(step.optString("taskState", step.optJSONObject("taskObservation")?.optString("status").orEmpty())))
                        Text(dependencyLabel(step, plan) + " · 最早 " + planTime(step.optLong("dueAt")), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
                        step.optJSONObject("remoteWait")?.let { remote ->
                            PlanCaption("电脑前置 · ${remote.optString("profileId")} / ${remote.optString("environmentId")} · Job ${remote.optString("jobId")}\n只核对已选原进程退出码 0，不推定命令业务成功。暂停或取消此目标不会停止这个独立电脑任务。")
                            TextButton(onClick = { controller.focusTask(remote.getString("taskId")) }) { Text("打开电脑原任务") }
                        }
                        val priorRemote = step.optJSONArray("priorRemoteWaitRefs").objects()
                        if (priorRemote.isNotEmpty()) {
                            var showPrior by remember(step.optString("id")) { mutableStateOf(false) }
                            TextButton(onClick = { showPrior = !showPrior }) { Text(if (showPrior) "收起先前电脑前置" else "先前电脑前置 · ${priorRemote.size} 项") }
                            if (showPrior) priorRemote.forEach { previous ->
                                previous.optJSONObject("reference")?.let { original ->
                                    TextButton(onClick = { controller.focusTask(original.getString("taskId")) }) { Text("原电脑 ${original.optString("profileId")} · Job ${original.optString("jobId")}") }
                                }
                            }
                        }
                        val verification = step.optJSONObject("verification")
                        if (verification?.optString("kind") == "workspace_artifact") PlanCaption("文件标准 · ${verification.optString("path")}\n首行 ${verification.optString("requiredHeading")} · 至少 ${verification.optLong("minBytes")} 字节。核验文件身份、可读性与标题，不推定内容质量。")
                        else PlanCaption("结果需要原任务中的明确核对，模型说完成不会自动推进。")
                        step.optJSONObject("completionEvidence")?.optJSONObject("artifact")?.let { artifact -> Text("已核验成果 · ${artifact.optString("path")} · ${artifact.optLong("sizeBytes")} B\nSHA ${artifact.optString("sha256").take(16)}…", fontSize = 12.sp, lineHeight = 20.sp) }
                        Row {
                            if (step.optString("taskId").isNotEmpty()) TextButton(onClick = { controller.focusTask(step.getString("taskId")) }) { Text("打开原任务与成果") }
                            if (paused && active && step.optString("taskId").isEmpty() && step.optJSONObject("taskAdmission") == null) TextButton(onClick = { editing = JSONObject(plan.toString()) to JSONObject(step.toString()) }) { Text("修改这一步") }
                            if (paused && step.optString("status") in setOf("failed", "cancelled") && step.optJSONObject("taskObservation")?.optBoolean("terminalVerified") == true) TextButton(onClick = { send("retry", planRef(plan).put("stepId", step.getString("id")).put("stepRevision", step.getLong("revision"))) }, enabled = !busy) { Text("重新审阅重试") }
                        }
                        val priorTasks = step.optJSONArray("priorTaskRefs").objects()
                        if (priorTasks.isNotEmpty()) {
                            var showPriorTasks by remember(step.optString("id")) { mutableStateOf(false) }
                            TextButton(onClick = { showPriorTasks = !showPriorTasks }) { Text(if (showPriorTasks) "收起先前原任务" else "先前原任务与结果 · ${priorTasks.size} 项") }
                            if (showPriorTasks) priorTasks.forEachIndexed { attempt, previous ->
                                val original = previous.optString("taskId")
                                if (original.isNotEmpty()) {
                                    TextButton(onClick = { controller.focusTask(original) }) { Text("第 ${attempt + 1} 次原任务与结果") }
                                    PlanCaption("保留时 · ${originalTaskLabel(previous.optString("status"))}。打开查看该原任务的当前状态、已有结果与操作记录；这里不会重试它。")
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = Line)
        }
    }
    val remoteCandidates = snapshot?.optJSONArray("remoteWaitCandidates").objects()
    val businessCandidates = snapshot?.optJSONArray("businessWaitCandidates").objects()
    if (creating) ExecutionPlanEditor(state, remoteCandidates, businessCandidates, busy, { creating = false }) { definition -> createRequest = send("create", JSONObject().put("plan", definition)) }
    reviewing?.let { frozen -> ExecutionPlanReview(frozen, state, busy, { reviewing = null }) { settings -> dialogRequest = send("approve", planRef(frozen).put("settings", settings)) } }
    editing?.let { (frozen, step) -> ExecutionStepEditor(frozen, step, state, remoteCandidates, businessCandidates, busy, { editing = null }) { patch -> dialogRequest = send("editStep", planRef(frozen).put("stepId", step.getString("id")).put("stepRevision", step.getLong("revision")).put("patch", patch)) } }
    cancelling?.let { frozen -> AlertDialog(onDismissRequest = { cancelling = null }, title = { Text("取消这个目标？") }, text = { Text("保留已核验成果，停止后继准入，并请求停止本目标创建的原任务。在途操作和未知结果仍需核对。" + if (frozen.optJSONArray("steps").objects().any { it.optJSONObject("remoteWait") != null }) " 独立电脑前置不会被此操作停止，需要停止时请打开电脑原任务。" else "") }, confirmButton = { TextButton(onClick = { send("cancel", planRef(frozen)); cancelling = null }) { Text("取消目标", color = Danger) } }, dismissButton = { TextButton(onClick = { cancelling = null }) { Text("保留") } }) }
}

/** Existing native plan components only. Cached text/digest is never source authority. */
@Composable private fun OrdinaryScheduleDraft(submitted: JSONObject, busy: Boolean, approve: (String, String) -> Unit) {
    val checked = if (submitted.optBoolean("sourceAvailable")) runCatching { dev.pocketengine.features.automation.AutomationBook.reviewedProposalForDisplay(submitted) }.getOrNull() else null
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        if (checked == null) {
            val invalidated = submitted.optBoolean("sourceInvalidated")
            Text(if (invalidated) "原时间草案来源已撤回" else "原时间草案来源待核对", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            PlanCaption(if (invalidated) "原会话、输入版本或来源已有撤回事实。没有显示旧目标，也不能从此旧草案恢复执行；请回原私聊明确重新提出。" else "当前原宿主或来源暂时无法核对，草案正文仍保留、没有重新批准。恢复后可刷新核对；旧来源始终无法证明时，请在原私聊重新提出。")
        } else {
            val draft = checked.getJSONObject("draft")
            val schedule = draft.getJSONObject("schedule")
            val preview = runCatching { dev.pocketengine.features.automation.AutomationSchedule.preview(schedule, System.currentTimeMillis(), 1).getJSONArray("occurrences") }.getOrNull()
            val next = preview?.optJSONObject(0)?.optLong("at", -1) ?: -1
            val zone = runCatching { ZoneId.of(schedule.optString("zone", ZoneId.systemDefault().id)) }.getOrDefault(ZoneId.systemDefault())
            val displayedTime = if (next >= 0) java.time.Instant.ofEpochMilli(next).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX VV")) else "该时间已过或日历已耗尽，请重新提出"
            Text(draft.getString("title"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (draft.optString("kind") == "phone_goal") Text(draft.getString("goal"), fontSize = 14.sp, lineHeight = 22.sp)
            PlanCaption("下一次实际时间 · $displayedTime")
            PlanCaption("固定日历定义 · ${schedule}")
            PlanCaption("原版本 · ${checked.getString("digest")}")
            if (checked.optString("status") == "pending") {
                val entered = checked.optBoolean("ruleCreationEntered")
                if (entered) PlanCaption("原创建已进入但确认未完成；只能核对相同原规则，不会重建缺失或已撤回的规则。")
                Button(onClick = { approve(checked.getString("id"), checked.getString("digest")) }, enabled = !busy && next >= 0) { Text(if (entered) "核对原创建（不重建）" else "明确保存此提醒（不授权执行）") }
            } else PlanCaption("已审阅保存。提醒不等于手机或其他动作已获授权，更不代表业务完成。")
        }
        HorizontalDivider(color = Line, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable private fun ExecutionPlanEditor(state: PocketUiState, remoteCandidates: List<JSONObject>, businessCandidates: List<JSONObject>, busy: Boolean, close: () -> Unit, save: (JSONObject) -> Unit) {
    var goal by remember { mutableStateOf("") }; var minutes by remember { mutableStateOf("0") }
    var steps by remember { mutableStateOf(listOf(ExecutionStepDraft())) }
    val projects = state.feature("workspace").optJSONArray("projects").objects()
    val definition = runCatching { executionPlanDefinition(goal, steps, minutes, System.currentTimeMillis()) }
    PlanSheet("建立持续目标", close, { Button(onClick = { save(executionPlanDefinition(goal, steps, minutes, System.currentTimeMillis())) }, enabled = !busy && definition.isSuccess) { Text(if (busy) "保存中" else "保存并审阅范围") } }) {
        OutlinedTextField(goal, { goal = it }, label = { Text("最终要得到什么结果") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(6) }, label = { Text("最早几分钟后开始，0 为现在") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        steps.forEachIndexed { index, draft ->
            Text("步骤 ${index + 1}", fontWeight = FontWeight.SemiBold)
            ExecutionStepFields(draft, index, steps.take(index).map { it.goal }, projects, remoteCandidates, businessCandidates) { value -> steps = steps.toMutableList().also { it[index] = value } }
            // Removing only the last row preserves every earlier reference without silent rewrites.
            if (index == steps.lastIndex && steps.size > 1) TextButton(onClick = { steps = steps.dropLast(1) }) { Text("移除末步", color = Danger) }
            HorizontalDivider(color = Line)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (steps.size < 50) OutlinedButton(onClick = { steps = steps + ExecutionStepDraft(dependencies = listOf(ExecutionDependencyDraft(steps.lastIndex))) }) { Text("添加后继步骤") }
            if (steps.size < 50) TextButton(onClick = { steps = steps + ExecutionStepDraft(dependencies = listOf(ExecutionDependencyDraft(steps.lastIndex, "failed"))) }) { Text("添加失败恢复") }
        }
        definition.exceptionOrNull()?.message?.takeIf { goal.isNotBlank() }?.let { Text(it, color = Danger, fontSize = 12.sp) }
        PlanCaption("保存的是待审阅计划。下一步确认范围、运行条件和任务预算后，才可触发真实执行。")
        state.moduleErrors["owner.automationPlan"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
    }
}

@Composable private fun ExecutionStepFields(draft: ExecutionStepDraft, index: Int, earlier: List<String>, projects: List<JSONObject>, remoteCandidates: List<JSONObject>, businessCandidates: List<JSONObject>, earlierStates: List<String> = emptyList(), change: (ExecutionStepDraft) -> Unit) {
    OutlinedTextField(draft.goal, { change(draft.copy(goal = it)) }, label = { Text("这一步的可交付目标") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
    PlanChoice("使用的能力", if (draft.executor == "workspace") "所选项目的文件" else "手机应用", listOf("phone" to "手机应用", "workspace" to "所选项目的文件")) { change(draft.copy(executor = it)) }
    if (index > 0) ExecutionDependencyFields(draft.dependencies, earlier, earlierStates) { change(draft.copy(dependencies = it)) }
    if (draft.executor == "workspace") {
        PlanChoice("项目", projects.find { it.optString("id") == draft.projectId }?.optString("name") ?: "选择已有项目", projects.map { it.optString("id") to it.optString("name") }) { change(draft.copy(projectId = it)) }
        if (projects.isEmpty()) PlanCaption("先在项目工作台建立项目。这里使用同一个正式项目，不会创建第二工作区。")
        OutlinedTextField(draft.path, { change(draft.copy(path = it)) }, label = { Text("目标文件，如 reports/result.md") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.heading, { change(draft.copy(heading = it)) }, label = { Text("用于机器核验的完整首行") }, maxLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.minimumBytes, { change(draft.copy(minimumBytes = it.filter(Char::isDigit).take(7))) }, label = { Text("最小 UTF-8 字节数") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    ExecutionRemoteWaitField(draft.remoteWait, remoteCandidates) { change(draft.copy(remoteWait = it)) }
    ExecutionBusinessWaitField(draft.businessWait, businessCandidates) { change(draft.copy(businessWait = it)) }
}

@Composable private fun ExecutionBusinessWaitField(reference: JSONObject?, candidates: List<JSONObject>, change: (JSONObject?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(if (reference == null) "等待认证来源下一条消息（可选）" else "认证来源前置 · 已选择") }
    reference?.optJSONObject("anchor")?.let { source -> PlanCaption("${source.optString("provider")} · ${source.optString("conversationKind")} · ${source.optString("conversationId")}\n只观察这一个已认证来源的新事件；消息正文不作为目标指令，也不获得回复/群发送权限。") }
    if (open) PlanSheet("选择原认证入站来源", { open = false }, { OutlinedButton(onClick = { open = false }) { Text("保留当前条件") } }) {
        PlanCaption("仅列当前原渠道inbox最近窗口内仍有效的来源metadata。没有账号或来源不可核对时保持不可选，不新增webhook或消息存储。")
        TextButton(onClick = { change(null); open = false }) { Text("不等待入站事件") }
        if (candidates.isEmpty()) PlanCaption("当前没有可核对的原认证入站来源。请先在原渠道页完成连接与认证，再回来选择。")
        candidates.forEach { row ->
            val ref = row.getJSONObject("reference")
            Column(Modifier.fillMaxWidth().clickable { change(JSONObject(ref.toString())); open = false }.padding(vertical = 16.dp)) {
                Text(row.optString("label"), fontSize = 14.sp)
                val anchor = ref.getJSONObject("anchor")
                PlanCaption("原消息 ${anchor.optString("eventId")} · 绑定 ${anchor.optString("bindingEpoch")}\n只等待该位置之后同来源的新事件，不把缺失窗口猜成已读。")
            }
            HorizontalDivider(color = Line)
        }
    }
}

@Composable private fun ExecutionRemoteWaitField(reference: JSONObject?, candidates: List<JSONObject>, change: (JSONObject?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(if (reference == null) "等待已有电脑任务（可选）" else "电脑前置 · ${reference.optString("jobId")}") }
    if (reference != null) PlanCaption("电脑 ${reference.optString("profileId")} · 环境 ${reference.optString("environmentId")}\n等待这个原进程退出码为 0；不会重新提交或停止它。")
    if (open) PlanSheet("选择已存在的电脑任务", { open = false }, { OutlinedButton(onClick = { open = false }) { Text("保留当前条件") } }) {
        Text("先等原命令结束，再做这一步", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        PlanCaption("只支持已存在的主人独立 command 任务。只核对原进程退出码 0，不核验命令业务成果或后台子进程。Coding Agent 说完成不能代替这个条件。")
        TextButton(onClick = { change(null); open = false }) { Text("不等待电脑任务") }
        if (candidates.isEmpty()) PlanCaption("还没有已登记的原 command Job。这里选择已经通过现有电脑能力提交的任务；连接或同步电脑不代表已经提交了命令。此处不提交或重试。")
        candidates.forEach { candidate ->
            val source = candidate.getJSONObject("reference")
            Column(Modifier.fillMaxWidth().clickable { change(JSONObject(source.toString())); open = false }.padding(vertical = 16.dp)) {
                Text(candidate.optString("title"), fontSize = 14.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
                PlanCaption("电脑 ${source.optString("profileId")} · 环境 ${source.optString("environmentId")}\nJob ${source.optString("jobId")} · 只等待原进程退出码 0")
                val observed = candidate.optJSONObject("observation")
                PlanCaption(when (observed?.optString("reason")) { "remote_wait_satisfied" -> "原进程已返回退出码 0"; "remote_wait_failed" -> "原进程退出码 ${observed.optLong("exitCode")}，此条件尚未满足"; "remote_wait_unknown" -> "原结果待核对，不会重提任务"; "remote_wait_pending" -> "原进程仍在等待或执行"; "remote_wait_cancelled" -> "原进程已请求停止或取消"; else -> "原退出回执暂不可核实" })
            }
            HorizontalDivider(color = Line)
        }
    }
}

@Composable private fun ExecutionDependencyFields(dependencies: List<ExecutionDependencyDraft>, earlier: List<String>, earlierStates: List<String>, change: (List<ExecutionDependencyDraft>) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("前置条件 · ${dependencies.size} 项", Modifier.fillMaxWidth(), fontSize = 14.sp) }
    PlanCaption(if (dependencies.isEmpty()) "无需前置。到达计划时间并满足运行条件后可开始。" else "全部满足后开始：" + dependencies.sortedBy { it.step }.joinToString("；") { "步骤 ${it.step + 1} · ${outcomeLabel(it.outcome)}" })
    if (picking) {
        var selected by remember { mutableStateOf(dependencies.toList()) }
        PlanSheet("选择前置条件", { picking = false }, { Button(onClick = { change(selected.sortedBy { it.step }); picking = false }) { Text("保留 ${selected.size} 项前置") } }) {
            Text("需要哪些前置结果", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            PlanCaption("勾选多项时，每一项都需满足。成功、失败和取消以原任务核验为准；已结束也可包括没有执行的未采用分支。结果不匹配时，本步骤不会执行。")
            earlier.forEachIndexed { at, title ->
                val ref = selected.find { it.step == at }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}.toggleable(ref != null, role = Role.Checkbox) { checked -> selected = if (checked) selected + ExecutionDependencyDraft(at) else selected.filterNot { it.step == at } }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ref != null, null)
                    Text("步骤 ${at + 1} · ${title.ifBlank { "尚未填写目标" }}", fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.weight(1f))
                }
                earlierStates.getOrNull(at)?.let { PlanCaption("当前 · $it") }
                if (ref != null) PlanChoice("步骤 ${at + 1} 的结果", outcomeLabel(ref.outcome), listOf("completed" to "前置成功", "failed" to "前置失败", "cancelled" to "前置取消", "settled" to "前置已结束（含未采用）")) { outcome -> selected = selected.map { if (it.step == at) it.copy(outcome = outcome) else it } }
                HorizontalDivider(color = Line)
            }
        }
    }
}

@Composable private fun ExecutionStepEditor(plan: JSONObject, step: JSONObject, state: PocketUiState, remoteCandidates: List<JSONObject>, businessCandidates: List<JSONObject>, busy: Boolean, close: () -> Unit, save: (JSONObject) -> Unit) {
    val rows = plan.optJSONArray("steps").objects(); val index = rows.indexOfFirst { it.optString("id") == step.optString("id") }
    val initial = remember { runCatching { executionStepDraft(plan, step) } }
    if (initial.isFailure) {
        PlanSheet("步骤暂不能编辑", close, { Button(onClick = close) { Text("返回后刷新目标") } }) { Text(initial.exceptionOrNull()?.message ?: "前置条件暂不可核实，没有删除任何条件。", fontSize = 14.sp, lineHeight = 23.sp) }
        return
    }
    var draft by remember { mutableStateOf(initial.getOrThrow()) }
    var delay by remember { mutableStateOf(((step.optLong("dueAt") - System.currentTimeMillis()).coerceAtLeast(0).let { (it + 59_999) / 60_000 }).toString()) }
    var timingEdited by remember { mutableStateOf(false) }
    val projects = state.feature("workspace").optJSONArray("projects").objects()
    val candidate = runCatching { executionStepPatch(plan, step, draft, delay, timingEdited, System.currentTimeMillis()) }
    PlanSheet("修改未执行步骤", close, { Button(onClick = { save(candidate.getOrThrow()) }, enabled = !busy && candidate.isSuccess && candidate.getOrNull()?.length() != 0) { Text("保存修改，重新审阅") } }) {
        ExecutionStepFields(draft, index, rows.take(index).map { it.optString("title") }, projects, remoteCandidates, businessCandidates, rows.take(index).map { stepStateLabel(it) + " · 原任务：" + originalTaskLabel(it.optString("taskState", it.optJSONObject("taskObservation")?.optString("status").orEmpty())) }) { draft = it }
        OutlinedTextField(delay, { delay = it.filter(Char::isDigit).take(6); timingEdited = true }, label = { Text("改为几分钟后可执行") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        PlanCaption("当前最早时间 · ${planTime(step.optLong("dueAt"))}。不修改时间字段会保留原时间。")
        PlanCaption("保留已发生的原任务、成果与未知操作。修改这一步后，整个目标需要再次审阅；不会借新版本重派当前任务。")
        candidate.exceptionOrNull()?.message?.let { Text(it, color = Danger, fontSize = 12.sp) }
        if (candidate.getOrNull()?.length() == 0) PlanCaption("尚无修改，返回即可保留当前授权与前置条件。")
        state.moduleErrors["owner.automationPlan"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
    }
}

@Composable private fun ExecutionPlanReview(plan: JSONObject, state: PocketUiState, busy: Boolean, close: () -> Unit, approve: (JSONObject) -> Unit) {
    val previous = plan.optJSONObject("execution"); val policy = previous?.optJSONObject("policy")
    var automatic by remember { mutableStateOf(previous?.optString("mode") != "manual") }
    var consent by remember { mutableStateOf(previous?.optBoolean("modelObservationConsent") == true) }
    var freezeScope by remember { mutableStateOf(previous?.optBoolean("allowPrivateArtifactFreeze") ?: true) }
    var phoneMode by remember { mutableStateOf(previous?.optString("phoneMode") ?: "background") }
    var maximum by remember { mutableStateOf((previous?.optInt("maxTaskAdmissions") ?: plan.optInt("totalSteps").coerceAtLeast(1)).toString()) }
    var hours by remember { mutableStateOf("168") }; var network by remember { mutableStateOf(policy?.optBoolean("requiresNetwork") ?: true) }
    var charging by remember { mutableStateOf(policy?.optBoolean("requiresCharging") == true) }; var minimum by remember { mutableStateOf((policy?.optInt("minBatteryPercent") ?: 0).toString()) }
    var quiet by remember { mutableStateOf(policy?.optJSONObject("quietHours") != null) }; var start by remember { mutableStateOf(policy?.optJSONObject("quietHours")?.optString("start") ?: "22:00") }; var end by remember { mutableStateOf(policy?.optJSONObject("quietHours")?.optString("end") ?: "08:00") }
    var runtimeExpanded by remember { mutableStateOf(false) }
    val steps = plan.optJSONArray("steps").objects()
    val projects = state.feature("workspace").optJSONArray("projects").objects()
    val projectsAvailable = steps.filter { it.optJSONObject("verification")?.optString("kind") == "workspace_artifact" }.all { step -> projects.any { it.optString("id") == step.getJSONObject("verification").optString("projectId") } }
    val settings = runCatching {
        val max = maximum.toIntOrNull(); require(max != null && max in 1..1000) { "任务准入上限应为 1–1000 次" }
        val window = hours.toLongOrNull(); require(window != null && window in 1..8760) { "授权时长应为 1–8760 小时" }
        val battery = minimum.toIntOrNull(); require(battery != null && battery in 0..100) { "最低电量应为 0–100%" }
        val expires = System.currentTimeMillis() + window * 3_600_000; require(steps.filter { it.optString("status") !in setOf("completed", "failed", "cancelled", "skipped") }.all { it.optLong("dueAt") < expires }) { "有效期必须晚于计划开始时间" }
        val result = JSONObject().put("mode", if (automatic) "automatic" else "manual").put("maxTaskAdmissions", max).put("phoneMode", phoneMode).put("modelObservationConsent", consent)
            .put("allowPrivateArtifactFreeze", freezeScope).put("expiresAt", expires).put("zoneId", ZoneId.systemDefault().id).put("requiresNetwork", network).put("requiresCharging", charging).put("minBatteryPercent", battery)
        if (quiet) { require(runCatching { LocalTime.parse(start); LocalTime.parse(end) }.isSuccess && start != end) { "安静时段需使用不同的 HH:mm 开始和结束时间" }; result.put("quietHours", JSONObject().put("start", start).put("end", end)) }; result
    }
    val hasBusinessSource = steps.any { it.optJSONObject("businessWait") != null }
    var businessConsent by remember(plan.optString("id"), plan.optLong("revision")) { mutableStateOf(false) }
    PlanSheet("审阅这个目标的执行范围", close, { Button(onClick = { approve(settings.getOrThrow().put("businessEventConsent", businessConsent)) }, enabled = !busy && consent && (!hasBusinessSource || businessConsent) && settings.isSuccess && projectsAvailable) { Text(if (automatic) "批准并按计划开始" else "保存授权，等我开始") } }) {
        Text(plan.optString("goal"), fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        steps.forEachIndexed { at, step ->
            Text("${at + 1}. ${step.optString("goal")}", fontSize = 14.sp, lineHeight = 23.sp)
            PlanCaption(dependencyLabel(step, plan))
            val verification = step.optJSONObject("verification")
            if (verification?.optString("kind") == "workspace_artifact") {
                val projectId = verification.optString("projectId")
                Text("项目 · " + (projects.find { it.optString("id") == projectId }?.optString("name") ?: "名称暂不可核实"), fontSize = 14.sp)
                PlanCaption("项目 ID · $projectId")
                Text("文件 · ${verification.optString("path")}", fontSize = 14.sp, lineHeight = 23.sp)
                Text("完整首行 · ${verification.optString("requiredHeading")}", fontSize = 14.sp, lineHeight = 23.sp)
                Text("最小大小 · ${verification.optLong("minBytes")} UTF-8 字节", fontSize = 14.sp)
                PlanCaption("机器核验文件身份、可读性、首行和大小，不证明语义内容质量。")
            } else PlanCaption("手机应用中的原任务结果需明确核对。")
            step.optJSONObject("remoteWait")?.let { remote ->
                Text("先等待原电脑进程退出码 0", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                PlanCaption("电脑 ${remote.optString("profileId")} · ${remote.optString("serviceBase")}\n环境 ${remote.optString("environmentId")} · Job ${remote.optString("jobId")}")
                var showIdentity by remember(step.optString("id")) { mutableStateOf(false) }
                TextButton(onClick = { showIdentity = !showIdentity }) { Text(if (showIdentity) "收起原执行身份" else "查看原执行身份") }
                if (showIdentity) PlanCaption("原任务 ${remote.optString("taskId")}\nRun ${remote.optString("runId")} · 代次 ${remote.optInt("coordinatorEpoch")}\n原操作 ${remote.optString("operationId")}")
                PlanCaption("这里只等待这份已存在的原进程回执，不提交、重试或停止电脑任务，也不推定它的业务目标已成功。")
            }
        }
        steps.filter { it.optJSONObject("businessWait") != null }.forEach { step ->
            val source = step.getJSONObject("businessWait").getJSONObject("anchor")
            Text("入站前置 · ${source.optString("provider")} / ${source.optString("conversationKind")} / ${source.optString("conversationId")}", fontSize = 14.sp)
            PlanCaption("原来源消息 ${source.optString("eventId")}\n主体 ${source.optString("principalId")} · 绑定 ${source.optString("bindingEpoch")}\n只以同来源的一次新事件触发上面已写明的固定目标。消息正文不送入目标、不作为新指令，不自动回复或外发成果。来源已撤销、窗口缺失或版本变化会阻止新的消费/派发。")
        }
        if (hasBusinessSource) PlanToggle("明确允许上述认证来源的新事件触发这个固定目标", businessConsent) { businessConsent = it }
        if (!projectsAvailable) Text("项目列表尚不可核实，请返回刷新项目后再审阅；没有改换项目或批准执行。", color = Danger, fontSize = 14.sp)
        if (steps.any { it.optString("executor", "phone") == "phone" }) PlanChoice("手机执行位置", if (phoneMode == "background") "独立后台屏" else "主屏兼容模式", listOf("background" to "独立后台屏", "main_screen" to "主屏兼容模式")) { phoneMode = it }
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.toggleable(consent, role = Role.Checkbox) { consent = it }, verticalAlignment = Alignment.Top) { Checkbox(consent, null); Text("同意将本目标需要的项目文件，或手机界面与可见文字，发送给我配置的云端模型。", fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp)) }
        if (steps.any { it.optString("executor") == "workspace" }) PlanToggle("允许将指定文件冻结为本目标的私人成果", freezeScope) { freezeScope = it }
        PlanToggle("按时间、依赖与条件自动推进", automatic) { automatic = it }
        PlanCaption("最多启动 $maximum 次任务 · $hours 小时后到期\n" + (if (network) "需要系统验证的网络" else "未要求网络条件") + (if (charging) " · 仅充电时" else "") + (if (minimum != "0") " · 电量至少 $minimum%" else "") + (if (quiet) " · 安静时段 $start–$end" else ""))
        TextButton(onClick = { runtimeExpanded = !runtimeExpanded }) { Text(if (runtimeExpanded) "收起运行设置" else "运行条件、窗口与预算") }
        if (runtimeExpanded) {
        OutlinedTextField(maximum, { maximum = it.filter(Char::isDigit).take(4) }, label = { Text("本目标最多启动多少次任务") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        PlanCaption("已占用 ${previous?.optInt("admissions") ?: 0} 次；修改不清零，启动尚未确认时也保留占用。")
        OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(4) }, label = { Text("这次授权多少小时后到期") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        PlanToggle("需要系统已验证的网络", network) { network = it }; PlanToggle("仅在充电时运行", charging) { charging = it }
        OutlinedTextField(minimum, { minimum = it.filter(Char::isDigit).take(3) }, label = { Text("最低电量百分比，0 为不限制") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        PlanToggle("使用安静时段", quiet) { quiet = it }
        if (quiet) { OutlinedTextField(start, { start = it.take(5) }, label = { Text("开始 HH:mm") }, singleLine = true); OutlinedTextField(end, { end = it.take(5) }, label = { Text("结束 HH:mm") }, singleLine = true) }
        }
        PlanCaption("这份授权限定目标与能力范围。具体写入审批保留在原任务；文件满足已审标准后会自动推进，证据不足才等待核对。")
        settings.exceptionOrNull()?.message?.let { Text(it, color = Danger, fontSize = 12.sp) }
        state.moduleErrors["owner.automationPlan"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
    }
}

@Composable private fun PlanSheet(title: String, close: () -> Unit, primary: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = close) { Text("返回") }; Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)) }
                HorizontalDivider(color = Line)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                HorizontalDivider(color = Line); Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), horizontalArrangement = Arrangement.End) { primary() }
            }
        }
    }
}
@Composable private fun PlanChoice(label: String, selected: String, values: List<Pair<String, String>>, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("$label · $selected", Modifier.fillMaxWidth(), fontSize = 14.sp) }; DropdownMenu(open, { open = false }) { values.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { choose(value); open = false }) } } }
}
@Composable private fun PlanToggle(label: String, value: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}.toggleable(value, role = Role.Switch, onValueChange = change), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 22.sp); Switch(value, null) } }
@Composable private fun PlanCaption(value: String) { Text(value, color = SecondaryInk, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 5.dp, bottom = 9.dp)) }
private fun planRef(plan: JSONObject): JSONObject = JSONObject().put("id", plan.getString("id")).put("revision", plan.getLong("revision"))
private fun planTime(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
private fun outcomeLabel(value: String): String = when (value) { "failed" -> "前置失败"; "cancelled" -> "前置取消"; "settled" -> "前置已结束（含未采用）"; else -> "前置成功" }
private fun dependencyLabel(step: JSONObject, plan: JSONObject): String {
    val all = plan.optJSONArray("steps").objects(); val dependencies = step.optJSONArray("dependencies").objects()
    return if (dependencies.isEmpty()) "无需前置步骤" else dependencies.joinToString("，") { ref -> "步骤 ${all.indexOfFirst { it.optString("id") == ref.optString("stepId") } + 1} · ${outcomeLabel(ref.optString("outcome"))}" }
}
private fun stepStateLabel(step: JSONObject): String = when (step.optString("status")) { "completed" -> if (step.optJSONObject("completionEvidence")?.optString("verificationSource") == "native_workspace_artifact_standard") "已按文件标准核验" else "真实结果已核对"; "failed" -> "已核实失败"; "skipped" -> "此结果分支未采用"; "cancelled" -> "步骤已取消"; else -> "待处理" }
private fun originalTaskLabel(value: String): String = when (value) { "unbound", "" -> "尚未创建或关联"; "registered" -> "已登记，尚未开始"; "unverified_result", "needs_confirmation" -> "结果待核验"; "unknown", "reconciling" -> "已有操作待核对"; "revoked" -> "已请求撤销，仍需核对实际状态"; "unavailable" -> "暂不可读"; else -> taskStatus(value) }
