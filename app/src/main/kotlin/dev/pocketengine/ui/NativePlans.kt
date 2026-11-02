package dev.pocketengine.ui

import dev.pocketengine.R

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
        Text(taskFlowText(R.string.task_flow_34089409c6c1, "持续目标"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = { send("snapshot") }, enabled = !busy) { Text(taskFlowText(R.string.task_flow_aee887434131, "刷新")) }
    }
    PlanCaption(taskFlowText(R.string.task_flow_04c1aa77e946, "审阅一次目标与范围，按依赖、时间和实际条件继续。成果以原任务的真实凭证推进。"))
    state.moduleErrors[key]?.let { Text(it, color = Danger, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp)) }
    if (busy && result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    val plan = plans.find { it.optString("id") == selected }
    if (plan == null) {
        snapshot?.optJSONArray("scheduleProposals").objects().filter { it.optString("status") == "pending" }.forEach { proposal ->
            ScheduleProposalReview(proposal, busy) { command, payload -> send(command, payload) }
            HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 12.dp))
        }
        snapshot?.optJSONArray("semanticRules").objects().filter { it.optJSONObject("semanticCondition") != null }.forEach { rule ->
            SemanticConditionControls(rule, plans, busy) { command, payload -> send(command, payload) }
            HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 12.dp))
        }
        Button(onClick = { creating = true }, enabled = !busy) { Text(taskFlowText(R.string.task_flow_00caa38fdefb, "建立一个持续目标")) }
        if (plans.isEmpty() && !busy) PlanCaption(taskFlowText(R.string.task_flow_6aae319cd105, "从一个明确目标开始，安排成功路径和需要时的失败恢复。文件成果可按你指定的标准自动核验。"))
        plans.forEach { row ->
            Column(Modifier.fillMaxWidth().clickable { selected = row.optString("id") }.padding(vertical = 18.dp)) {
                Text(row.optString("title"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                PlanCaption(executionPlanStateLabel(row.optString("state")) + taskFlowText(R.string.task_flow_4de8df6d4dac, " · %1\$s/%2\$s 步有凭证", row.optInt("completedSteps"), row.optInt("totalSteps")))
                row.optJSONObject("nextStep")?.let { Text(taskFlowText(R.string.task_flow_d01a7c4c0de9, "接下来 · ") + it.optString("title"), fontSize = 14.sp, color = SecondaryInk) }
            }
            HorizontalDivider(color = Line)
        }
    } else {
        TextButton(onClick = { selected = null }) { Text(taskFlowText(R.string.task_flow_04f95646640e, "返回目标列表")) }
        Text(plan.optString("goal", plan.optString("title")), fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        PlanCaption(executionPlanStateLabel(plan.optString("state")) + if (plan.optJSONObject("execution") != null && !plan.optBoolean("approvalValid") && plan.optString("state") != "review_required") taskFlowText(R.string.task_flow_6863aa57851b, " · 修改后待审阅") else "")
        val execution = plan.optJSONObject("execution")
        val paused = execution?.optString("state") == "paused"
        val terminal = plan.optString("state") in setOf("completed", "cancelled", "finished_with_failures")
        if (execution == null) {
            if (plan.optString("goal").isNotBlank() && plan.optJSONArray("steps").objects().all { it.optString("goal").isNotBlank() })
                Button(onClick = { reviewing = JSONObject(plan.toString()) }, enabled = !busy) { Text(taskFlowText(R.string.task_flow_3ac06423971e, "审阅目标与执行范围")) }
            else PlanCaption(taskFlowText(R.string.task_flow_67fd64b79d30, "这是已保留的历史跟进计划，继续从原任务核对结果。它没有执行定义，不会被自动启用。"))
            if (!terminal) TextButton(onClick = { cancelling = JSONObject(plan.toString()) }) { Text(taskFlowText(R.string.task_flow_8e4f2fe454a1, "取消跟进计划"), color = Danger) }
        } else {
            if (plan.optString("state") == "finished_with_failures" && !paused) OutlinedButton(onClick = { send("pause", planRef(plan)) }, enabled = !busy) { Text(taskFlowText(R.string.task_flow_99a35b183cbe, "审阅失败与重试")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!terminal && !plan.optBoolean("approvalValid")) Button(onClick = { reviewing = JSONObject(plan.toString()) }, enabled = !busy) { Text(taskFlowText(R.string.task_flow_b8d128e7af06, "审阅修改后的目标")) }
                else if (!terminal && paused) Button(onClick = { send("resume", planRef(plan)) }, enabled = !busy) { Text(taskFlowText(R.string.task_flow_afa33c9f6a4c, "继续同一目标")) }
                else if (!terminal && !execution.optBoolean("started")) Button(onClick = { send("start", planRef(plan)) }, enabled = !busy) { Text(taskFlowText(R.string.task_flow_624d662af230, "开始目标")) }
                if (!terminal && !paused) OutlinedButton(onClick = { send("pause", planRef(plan)) }) { Text(taskFlowText(R.string.task_flow_8d12fc0d4eb2, "暂停")) }
                if (!terminal) TextButton(onClick = { cancelling = JSONObject(plan.toString()) }) { Text(taskFlowText(R.string.task_flow_a53a4e7bcada, "取消目标"), color = Danger) }
            }
            PlanCaption(taskFlowText(R.string.task_flow_90c8c6152c0f, "任务次数已用 %1\$s / %2\$s · ", execution.optInt("admissions"), execution.optInt("maxTaskAdmissions")) + if (execution.optString("mode") == "automatic") taskFlowText(R.string.task_flow_a6fed1960395, "按计划自动推进") else taskFlowText(R.string.task_flow_11d966a4bb1c, "手动开始"))
            if (plan.optBoolean("originalTasksUnsettled") && paused) Text(taskFlowText(R.string.task_flow_d036b270ba85, "后继已暂停；原任务仍在结束或等待核对，下面保留其真实状态。"), fontSize = 14.sp, lineHeight = 22.sp, color = SecondaryInk)
        }
        plan.optJSONObject("requirements")?.optJSONArray("blockers").let { blockers ->
            for (i in 0 until (blockers?.length() ?: 0)) Text(blockers!!.optString(i), color = SecondaryInk, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
        planWaitState(plan)?.let { waiting ->
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(waiting.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                waiting.lines.forEach { Text(it, color = SecondaryInk, fontSize = 14.sp, lineHeight = 23.sp) }
                waiting.observedAt?.let { PlanCaption(taskFlowText(R.string.task_flow_adcc467f647e, "本机观察 · %1\$s", planTime(it))) }
                waiting.nextAt?.let { PlanCaption(taskFlowText(R.string.task_flow_967768aca65d, "计划再次核对 · %1\$s；系统计时可能延后，原任务事件或手动核对可能更早。", planTime(it))) }
                if (waiting.recheck) Button(onClick = { send("refresh", planRef(plan)) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(taskFlowText(R.string.task_flow_676bf5bc65da, "核对并继续此目标")) }
                if (waiting.resumeOriginal) Button(onClick = { send("resume", planRef(plan)) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(taskFlowText(R.string.task_flow_f5ed9fc55fd7, "继续同一原任务")) }
                if (waiting.inspect) OutlinedButton(onClick = { send("inspect", planRef(plan)) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(taskFlowText(R.string.task_flow_1a1b95791d36, "只核对原任务状态")) }
            }
        }
        plan.optJSONObject("runtimeDecision")?.let { decision ->
            if (decision.optString("reason") == "task_admission_budget_exhausted") PlanCaption(taskFlowText(R.string.task_flow_8516cc890464, "目标任务次数已用完，启动尚未确认的记录也保留占用。"))
            if (decision.optString("reason") in setOf("approval_expired", "task_admission_budget_exhausted")) TextButton(onClick = { reviewing = JSONObject(plan.toString()) }) { Text(taskFlowText(R.string.task_flow_6bd21f65c559, "审阅目标窗口与预算")) }
        }
        plan.optJSONArray("steps").objects().forEachIndexed { index, step ->
            val active = !step.optString("status").matches(Regex("completed|failed|cancelled|skipped"))
            Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text((index + 1).toString().padStart(2, '0'), color = SecondaryInk, fontSize = 14.sp, modifier = Modifier.width(34.dp).padding(top = 3.dp))
                    Column(Modifier.weight(1f)) {
                        Text(step.optString("goal", step.optString("title")), fontSize = 14.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
                        PlanCaption(stepStateLabel(step) + taskFlowText(R.string.task_flow_c53f55eba206, " · 原任务：") + originalTaskLabel(step.optString("taskState", step.optJSONObject("taskObservation")?.optString("status").orEmpty())))
                        Text(dependencyLabel(step, plan) + taskFlowText(R.string.task_flow_7bc85e574c92, " · 最早 ") + planTime(step.optLong("dueAt")), color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
                        step.optJSONObject("remoteWait")?.let { remote ->
                            PlanCaption(taskFlowText(R.string.task_flow_eb74dc3de56a, "电脑前置 · %1\$s / %2\$s · Job %3\$s\n只核对已选原进程退出码 0，不推定命令业务成功。暂停或取消此目标不会停止这个独立电脑任务。", remote.optString("profileId"), remote.optString("environmentId"), remote.optString("jobId")))
                            TextButton(onClick = { controller.focusTask(remote.getString("taskId")) }) { Text(taskFlowText(R.string.task_flow_dcee218ad0dc, "打开电脑原任务")) }
                        }
                        val priorRemote = step.optJSONArray("priorRemoteWaitRefs").objects()
                        if (priorRemote.isNotEmpty()) {
                            var showPrior by remember(step.optString("id")) { mutableStateOf(false) }
                            TextButton(onClick = { showPrior = !showPrior }) { Text(if (showPrior) taskFlowText(R.string.task_flow_bb8d0959d65f, "收起先前电脑前置") else taskFlowText(R.string.task_flow_d6e9c8ebdf2a, "先前电脑前置 · %1\$s 项", priorRemote.size)) }
                            if (showPrior) priorRemote.forEach { previous ->
                                previous.optJSONObject("reference")?.let { original ->
                                    TextButton(onClick = { controller.focusTask(original.getString("taskId")) }) { Text(taskFlowText(R.string.task_flow_4c3f818dd365, "原电脑 %1\$s · Job %2\$s", original.optString("profileId"), original.optString("jobId"))) }
                                }
                            }
                        }
                        val verification = step.optJSONObject("verification")
                        if (verification?.optString("kind") == "workspace_artifact") PlanCaption(taskFlowText(R.string.task_flow_2c7a67a900f5, "文件标准 · %1\$s\n首行 %2\$s · 至少 %3\$s 字节。核验文件身份、可读性与标题，不推定内容质量。", verification.optString("path"), verification.optString("requiredHeading"), verification.optLong("minBytes")))
                        else PlanCaption(taskFlowText(R.string.task_flow_e6397a889ad8, "结果需要原任务中的明确核对，模型说完成不会自动推进。"))
                        step.optJSONObject("completionEvidence")?.optJSONObject("artifact")?.let { artifact -> Text(taskFlowText(R.string.task_flow_7a94cef51609, "已核验成果 · %1\$s · %2\$s B\nSHA %3\$s…", artifact.optString("path"), artifact.optLong("sizeBytes"), artifact.optString("sha256").take(16)), fontSize = 12.sp, lineHeight = 20.sp) }
                        Row {
                            if (step.optString("taskId").isNotEmpty()) TextButton(onClick = { controller.focusTask(step.getString("taskId")) }) { Text(taskFlowText(R.string.task_flow_91e985ffa873, "打开原任务与成果")) }
                            if (paused && active && step.optString("taskId").isEmpty() && step.optJSONObject("taskAdmission") == null) TextButton(onClick = { editing = JSONObject(plan.toString()) to JSONObject(step.toString()) }) { Text(taskFlowText(R.string.task_flow_ca1f4e70774e, "修改这一步")) }
                            if (paused && step.optString("status") in setOf("failed", "cancelled") && step.optJSONObject("taskObservation")?.optBoolean("terminalVerified") == true) TextButton(onClick = { send("retry", planRef(plan).put("stepId", step.getString("id")).put("stepRevision", step.getLong("revision"))) }, enabled = !busy) { Text(taskFlowText(R.string.task_flow_eaa41bd4860b, "重新审阅重试")) }
                        }
                        val priorTasks = step.optJSONArray("priorTaskRefs").objects()
                        if (priorTasks.isNotEmpty()) {
                            var showPriorTasks by remember(step.optString("id")) { mutableStateOf(false) }
                            TextButton(onClick = { showPriorTasks = !showPriorTasks }) { Text(if (showPriorTasks) taskFlowText(R.string.task_flow_68122b18710c, "收起先前原任务") else taskFlowText(R.string.task_flow_7f66e15d68f6, "先前原任务与结果 · %1\$s 项", priorTasks.size)) }
                            if (showPriorTasks) priorTasks.forEachIndexed { attempt, previous ->
                                val original = previous.optString("taskId")
                                if (original.isNotEmpty()) {
                                    TextButton(onClick = { controller.focusTask(original) }) { Text(taskFlowText(R.string.task_flow_0b4df131dd46, "第 %1\$s 次原任务与结果", attempt + 1)) }
                                    PlanCaption(taskFlowText(R.string.task_flow_f723bc1e195d, "保留时 · %1\$s。打开查看该原任务的当前状态、已有结果与操作记录；这里不会重试它。", originalTaskLabel(previous.optString("status"))))
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
    cancelling?.let { frozen -> AlertDialog(onDismissRequest = { cancelling = null }, title = { Text(taskFlowText(R.string.task_flow_e9a17f4b297d, "取消这个目标？")) }, text = { Text(taskFlowText(R.string.task_flow_833e6eb6ea9a, "保留已核验成果，停止后继准入，并请求停止本目标创建的原任务。在途操作和未知结果仍需核对。") + if (frozen.optJSONArray("steps").objects().any { it.optJSONObject("remoteWait") != null }) taskFlowText(R.string.task_flow_8d1becb06d39, " 独立电脑前置不会被此操作停止，需要停止时请打开电脑原任务。") else "") }, confirmButton = { TextButton(onClick = { send("cancel", planRef(frozen)); cancelling = null }) { Text(taskFlowText(R.string.task_flow_a53a4e7bcada, "取消目标"), color = Danger) } }, dismissButton = { TextButton(onClick = { cancelling = null }) { Text(taskFlowText(R.string.task_flow_670ec25af841, "保留")) } }) }
}

@Composable private fun ExecutionPlanEditor(state: PocketUiState, remoteCandidates: List<JSONObject>, businessCandidates: List<JSONObject>, busy: Boolean, close: () -> Unit, save: (JSONObject) -> Unit) {
    var goal by remember { mutableStateOf("") }; var minutes by remember { mutableStateOf("0") }
    var steps by remember { mutableStateOf(listOf(ExecutionStepDraft())) }
    val projects = state.feature("workspace").optJSONArray("projects").objects()
    val definition = runCatching { executionPlanDefinition(goal, steps, minutes, System.currentTimeMillis()) }
    PlanSheet(taskFlowText(R.string.task_flow_9e340a32a644, "建立持续目标"), close, { Button(onClick = { save(executionPlanDefinition(goal, steps, minutes, System.currentTimeMillis())) }, enabled = !busy && definition.isSuccess) { Text(if (busy) taskFlowText(R.string.task_flow_a1a1720d3eb5, "保存中") else taskFlowText(R.string.task_flow_6e0d4b71eb75, "保存并审阅范围")) } }) {
        OutlinedTextField(goal, { goal = it }, label = { Text(taskFlowText(R.string.task_flow_9d3b004018c2, "最终要得到什么结果")) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(6) }, label = { Text(taskFlowText(R.string.task_flow_75185840260a, "最早几分钟后开始，0 为现在")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
        steps.forEachIndexed { index, draft ->
            Text(taskFlowText(R.string.task_flow_4dcfba56ed80, "步骤 %1\$s", index + 1), fontWeight = FontWeight.SemiBold)
            ExecutionStepFields(draft, index, steps.take(index).map { it.goal }, projects, remoteCandidates, businessCandidates) { value -> steps = steps.toMutableList().also { it[index] = value } }
            // Removing only the last row preserves every earlier reference without silent rewrites.
            if (index == steps.lastIndex && steps.size > 1) TextButton(onClick = { steps = steps.dropLast(1) }) { Text(taskFlowText(R.string.task_flow_81a70f750f18, "移除末步"), color = Danger) }
            HorizontalDivider(color = Line)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (steps.size < 50) OutlinedButton(onClick = { steps = steps + ExecutionStepDraft(dependencies = listOf(ExecutionDependencyDraft(steps.lastIndex))) }) { Text(taskFlowText(R.string.task_flow_08b31d125411, "添加后继步骤")) }
            if (steps.size < 50) TextButton(onClick = { steps = steps + ExecutionStepDraft(dependencies = listOf(ExecutionDependencyDraft(steps.lastIndex, "failed"))) }) { Text(taskFlowText(R.string.task_flow_652c2f445f86, "添加失败恢复")) }
        }
        definition.exceptionOrNull()?.message?.takeIf { goal.isNotBlank() }?.let { Text(it, color = Danger, fontSize = 12.sp) }
        PlanCaption(taskFlowText(R.string.task_flow_fe7b12a9fef8, "保存的是待审阅计划。下一步确认范围、运行条件和任务预算后，才可触发真实执行。"))
        state.moduleErrors["owner.automationPlan"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
    }
}

@Composable private fun ExecutionStepFields(draft: ExecutionStepDraft, index: Int, earlier: List<String>, projects: List<JSONObject>, remoteCandidates: List<JSONObject>, businessCandidates: List<JSONObject>, earlierStates: List<String> = emptyList(), change: (ExecutionStepDraft) -> Unit) {
    OutlinedTextField(draft.goal, { change(draft.copy(goal = it)) }, label = { Text(taskFlowText(R.string.task_flow_9e14acfcd30e, "这一步的可交付目标")) }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
    PlanChoice(taskFlowText(R.string.task_flow_aeaecc9a8106, "使用的能力"), if (draft.executor == "workspace") taskFlowText(R.string.task_flow_d53274ee6c37, "所选项目的文件") else taskFlowText(R.string.task_flow_94d754ef67fc, "手机应用"), listOf("phone" to taskFlowText(R.string.task_flow_94d754ef67fc, "手机应用"), "workspace" to taskFlowText(R.string.task_flow_d53274ee6c37, "所选项目的文件"))) { change(draft.copy(executor = it)) }
    if (index > 0) ExecutionDependencyFields(draft.dependencies, earlier, earlierStates) { change(draft.copy(dependencies = it)) }
    if (draft.executor == "workspace") {
        PlanChoice(taskFlowText(R.string.task_flow_79f326be4409, "项目"), projects.find { it.optString("id") == draft.projectId }?.optString("name") ?: taskFlowText(R.string.task_flow_c36583aeeb9b, "选择已有项目"), projects.map { it.optString("id") to it.optString("name") }) { change(draft.copy(projectId = it)) }
        if (projects.isEmpty()) PlanCaption(taskFlowText(R.string.task_flow_cf295ca862ea, "先在项目工作台建立项目。这里使用同一个正式项目，不会创建第二工作区。"))
        OutlinedTextField(draft.path, { change(draft.copy(path = it)) }, label = { Text(taskFlowText(R.string.task_flow_9a65e3ccb4b0, "目标文件，如 reports/result.md")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.heading, { change(draft.copy(heading = it)) }, label = { Text(taskFlowText(R.string.task_flow_9e10e977e473, "用于机器核验的完整首行")) }, maxLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.minimumBytes, { change(draft.copy(minimumBytes = it.filter(Char::isDigit).take(7))) }, label = { Text(taskFlowText(R.string.task_flow_940b6a8d2cc1, "最小 UTF-8 字节数")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    ExecutionRemoteWaitField(draft.remoteWait, remoteCandidates) { change(draft.copy(remoteWait = it)) }
    ExecutionBusinessWaitField(draft.businessWait, businessCandidates) { change(draft.copy(businessWait = it)) }
}

@Composable private fun ExecutionBusinessWaitField(reference: JSONObject?, candidates: List<JSONObject>, change: (JSONObject?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(if (reference == null) taskFlowText(R.string.task_flow_682365af32b4, "等待认证来源下一条消息（可选）") else taskFlowText(R.string.task_flow_90e9a5c40aee, "认证来源前置 · 已选择")) }
    reference?.optJSONObject("anchor")?.let { source -> PlanCaption(taskFlowText(R.string.task_flow_478f51c36aed, "%1\$s · %2\$s · %3\$s\n只观察这一个已认证来源的新事件；消息正文不作为目标指令，也不获得回复/群发送权限。", source.optString("provider"), source.optString("conversationKind"), source.optString("conversationId"))) }
    if (open) PlanSheet(taskFlowText(R.string.task_flow_edf011385315, "选择原认证入站来源"), { open = false }, { OutlinedButton(onClick = { open = false }) { Text(taskFlowText(R.string.task_flow_2ed0d8953188, "保留当前条件")) } }) {
        PlanCaption(taskFlowText(R.string.task_flow_99a5f08dfec2, "仅列当前原渠道inbox最近窗口内仍有效的来源metadata。没有账号或来源不可核对时保持不可选，不新增webhook或消息存储。"))
        TextButton(onClick = { change(null); open = false }) { Text(taskFlowText(R.string.task_flow_3c5475152437, "不等待入站事件")) }
        if (candidates.isEmpty()) PlanCaption(taskFlowText(R.string.task_flow_09a670953c8e, "当前没有可核对的原认证入站来源。请先在原渠道页完成连接与认证，再回来选择。"))
        candidates.forEach { row ->
            val ref = row.getJSONObject("reference")
            Column(Modifier.fillMaxWidth().clickable { change(JSONObject(ref.toString())); open = false }.padding(vertical = 16.dp)) {
                Text(row.optString("label"), fontSize = 14.sp)
                val anchor = ref.getJSONObject("anchor")
                PlanCaption(taskFlowText(R.string.task_flow_dabd7d2654e5, "原消息 %1\$s · 绑定 %2\$s\n只等待该位置之后同来源的新事件，不把缺失窗口猜成已读。", anchor.optString("eventId"), anchor.optString("bindingEpoch")))
            }
            HorizontalDivider(color = Line)
        }
    }
}

@Composable private fun ExecutionRemoteWaitField(reference: JSONObject?, candidates: List<JSONObject>, change: (JSONObject?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(if (reference == null) taskFlowText(R.string.task_flow_0c548e7a7d67, "等待已有电脑任务（可选）") else taskFlowText(R.string.task_flow_727a4cfb82c2, "电脑前置 · %1\$s", reference.optString("jobId"))) }
    if (reference != null) PlanCaption(taskFlowText(R.string.task_flow_46060759addf, "电脑 %1\$s · 环境 %2\$s\n等待这个原进程退出码为 0；不会重新提交或停止它。", reference.optString("profileId"), reference.optString("environmentId")))
    if (open) PlanSheet(taskFlowText(R.string.task_flow_c19ed676b054, "选择已存在的电脑任务"), { open = false }, { OutlinedButton(onClick = { open = false }) { Text(taskFlowText(R.string.task_flow_2ed0d8953188, "保留当前条件")) } }) {
        Text(taskFlowText(R.string.task_flow_c08a999353f4, "先等原命令结束，再做这一步"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        PlanCaption(taskFlowText(R.string.task_flow_63474d53e693, "只支持已存在的主人独立 command 任务。只核对原进程退出码 0，不核验命令业务成果或后台子进程。Coding Agent 说完成不能代替这个条件。"))
        TextButton(onClick = { change(null); open = false }) { Text(taskFlowText(R.string.task_flow_7e952e5a025b, "不等待电脑任务")) }
        if (candidates.isEmpty()) PlanCaption(taskFlowText(R.string.task_flow_5adea532b577, "还没有已登记的原 command Job。这里选择已经通过现有电脑能力提交的任务；连接或同步电脑不代表已经提交了命令。此处不提交或重试。"))
        candidates.forEach { candidate ->
            val source = candidate.getJSONObject("reference")
            Column(Modifier.fillMaxWidth().clickable { change(JSONObject(source.toString())); open = false }.padding(vertical = 16.dp)) {
                Text(candidate.optString("title"), fontSize = 14.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
                PlanCaption(taskFlowText(R.string.task_flow_fecb06f97186, "电脑 %1\$s · 环境 %2\$s\nJob %3\$s · 只等待原进程退出码 0", source.optString("profileId"), source.optString("environmentId"), source.optString("jobId")))
                val observed = candidate.optJSONObject("observation")
                PlanCaption(when (observed?.optString("reason")) { "remote_wait_satisfied" -> taskFlowText(R.string.task_flow_3b4c5e3ccd30, "原进程已返回退出码 0"); "remote_wait_failed" -> taskFlowText(R.string.task_flow_96aaaaf3be58, "原进程退出码 %1\$s，此条件尚未满足", observed.optLong("exitCode")); "remote_wait_unknown" -> taskFlowText(R.string.task_flow_e38fd0fb8c80, "原结果待核对，不会重提任务"); "remote_wait_pending" -> taskFlowText(R.string.task_flow_e172dd21a96b, "原进程仍在等待或执行"); "remote_wait_cancelled" -> taskFlowText(R.string.task_flow_3e5809e0907f, "原进程已请求停止或取消"); else -> taskFlowText(R.string.task_flow_6434d02b68b6, "原退出回执暂不可核实") })
            }
            HorizontalDivider(color = Line)
        }
    }
}

@Composable private fun ExecutionDependencyFields(dependencies: List<ExecutionDependencyDraft>, earlier: List<String>, earlierStates: List<String>, change: (List<ExecutionDependencyDraft>) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(taskFlowText(R.string.task_flow_2abfa187ee07, "前置条件 · %1\$s 项", dependencies.size), Modifier.fillMaxWidth(), fontSize = 14.sp) }
    PlanCaption(if (dependencies.isEmpty()) taskFlowText(R.string.task_flow_c3d04bf1c485, "无需前置。到达计划时间并满足运行条件后可开始。") else taskFlowText(R.string.task_flow_8e459835c823, "全部满足后开始：") + dependencies.sortedBy { it.step }.joinToString(taskFlowText(R.string.task_flow_b0d3c9cfa0e1, "；")) { taskFlowText(R.string.task_flow_a9d04a166b65, "步骤 %1\$s · %2\$s", it.step + 1, outcomeLabel(it.outcome)) })
    if (picking) {
        var selected by remember { mutableStateOf(dependencies.toList()) }
        PlanSheet(taskFlowText(R.string.task_flow_84eda9849876, "选择前置条件"), { picking = false }, { Button(onClick = { change(selected.sortedBy { it.step }); picking = false }) { Text(taskFlowText(R.string.task_flow_953d9a57d1c6, "保留 %1\$s 项前置", selected.size)) } }) {
            Text(taskFlowText(R.string.task_flow_751897f5640f, "需要哪些前置结果"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            PlanCaption(taskFlowText(R.string.task_flow_206ba7c89dbe, "勾选多项时，每一项都需满足。成功、失败和取消以原任务核验为准；已结束也可包括没有执行的未采用分支。结果不匹配时，本步骤不会执行。"))
            earlier.forEachIndexed { at, title ->
                val ref = selected.find { it.step == at }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}.toggleable(ref != null, role = Role.Checkbox) { checked -> selected = if (checked) selected + ExecutionDependencyDraft(at) else selected.filterNot { it.step == at } }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ref != null, null)
                    Text(taskFlowText(R.string.task_flow_a9d04a166b65, "步骤 %1\$s · %2\$s", at + 1, title.ifBlank { taskFlowText(R.string.task_flow_9461b70d596c, "尚未填写目标") }), fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.weight(1f))
                }
                earlierStates.getOrNull(at)?.let { PlanCaption(taskFlowText(R.string.task_flow_1b3dd09b15ae, "当前 · %1\$s", it)) }
                if (ref != null) PlanChoice(taskFlowText(R.string.task_flow_49d25f83d775, "步骤 %1\$s 的结果", at + 1), outcomeLabel(ref.outcome), listOf("completed" to taskFlowText(R.string.task_flow_b6443854028a, "前置成功"), "failed" to taskFlowText(R.string.task_flow_d3c1c95e43f8, "前置失败"), "cancelled" to taskFlowText(R.string.task_flow_9cd7814817c1, "前置取消"), "settled" to taskFlowText(R.string.task_flow_4f2db0e712c1, "前置已结束（含未采用）"))) { outcome -> selected = selected.map { if (it.step == at) it.copy(outcome = outcome) else it } }
                HorizontalDivider(color = Line)
            }
        }
    }
}

@Composable private fun ExecutionStepEditor(plan: JSONObject, step: JSONObject, state: PocketUiState, remoteCandidates: List<JSONObject>, businessCandidates: List<JSONObject>, busy: Boolean, close: () -> Unit, save: (JSONObject) -> Unit) {
    val rows = plan.optJSONArray("steps").objects(); val index = rows.indexOfFirst { it.optString("id") == step.optString("id") }
    val initial = remember { runCatching { executionStepDraft(plan, step) } }
    if (initial.isFailure) {
        PlanSheet(taskFlowText(R.string.task_flow_49dd661a2cf9, "步骤暂不能编辑"), close, { Button(onClick = close) { Text(taskFlowText(R.string.task_flow_59d44b0a709f, "返回后刷新目标")) } }) { Text(initial.exceptionOrNull()?.message ?: taskFlowText(R.string.task_flow_88c58618160c, "前置条件暂不可核实，没有删除任何条件。"), fontSize = 14.sp, lineHeight = 23.sp) }
        return
    }
    var draft by remember { mutableStateOf(initial.getOrThrow()) }
    var delay by remember { mutableStateOf(((step.optLong("dueAt") - System.currentTimeMillis()).coerceAtLeast(0).let { (it + 59_999) / 60_000 }).toString()) }
    var timingEdited by remember { mutableStateOf(false) }
    val projects = state.feature("workspace").optJSONArray("projects").objects()
    val candidate = runCatching { executionStepPatch(plan, step, draft, delay, timingEdited, System.currentTimeMillis()) }
    PlanSheet(taskFlowText(R.string.task_flow_1f502c930dbc, "修改未执行步骤"), close, { Button(onClick = { save(candidate.getOrThrow()) }, enabled = !busy && candidate.isSuccess && candidate.getOrNull()?.length() != 0) { Text(taskFlowText(R.string.task_flow_0b28ffc213a9, "保存修改，重新审阅")) } }) {
        ExecutionStepFields(draft, index, rows.take(index).map { it.optString("title") }, projects, remoteCandidates, businessCandidates, rows.take(index).map { stepStateLabel(it) + taskFlowText(R.string.task_flow_c53f55eba206, " · 原任务：") + originalTaskLabel(it.optString("taskState", it.optJSONObject("taskObservation")?.optString("status").orEmpty())) }) { draft = it }
        OutlinedTextField(delay, { delay = it.filter(Char::isDigit).take(6); timingEdited = true }, label = { Text(taskFlowText(R.string.task_flow_e29231e5ec62, "改为几分钟后可执行")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        PlanCaption(taskFlowText(R.string.task_flow_f6e4ba92106c, "当前最早时间 · %1\$s。不修改时间字段会保留原时间。", planTime(step.optLong("dueAt"))))
        PlanCaption(taskFlowText(R.string.task_flow_50ced4d35e94, "保留已发生的原任务、成果与未知操作。修改这一步后，整个目标需要再次审阅；不会借新版本重派当前任务。"))
        candidate.exceptionOrNull()?.message?.let { Text(it, color = Danger, fontSize = 12.sp) }
        if (candidate.getOrNull()?.length() == 0) PlanCaption(taskFlowText(R.string.task_flow_f554dc3d6782, "尚无修改，返回即可保留当前授权与前置条件。"))
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
        val max = maximum.toIntOrNull(); require(max != null && max in 1..1000) { taskFlowText(R.string.task_flow_b150039cb6f4, "任务准入上限应为 1–1000 次") }
        val window = hours.toLongOrNull(); require(window != null && window in 1..8760) { taskFlowText(R.string.task_flow_18148fe9267a, "授权时长应为 1–8760 小时") }
        val battery = minimum.toIntOrNull(); require(battery != null && battery in 0..100) { taskFlowText(R.string.task_flow_3f81c2d9155d, "最低电量应为 0–100%") }
        val expires = System.currentTimeMillis() + window * 3_600_000; require(steps.filter { it.optString("status") !in setOf("completed", "failed", "cancelled", "skipped") }.all { it.optLong("dueAt") < expires }) { taskFlowText(R.string.task_flow_da6b06a68668, "有效期必须晚于计划开始时间") }
        val result = JSONObject().put("mode", if (automatic) "automatic" else "manual").put("maxTaskAdmissions", max).put("phoneMode", phoneMode).put("modelObservationConsent", consent)
            .put("allowPrivateArtifactFreeze", freezeScope).put("expiresAt", expires).put("zoneId", ZoneId.systemDefault().id).put("requiresNetwork", network).put("requiresCharging", charging).put("minBatteryPercent", battery)
        if (quiet) { require(runCatching { LocalTime.parse(start); LocalTime.parse(end) }.isSuccess && start != end) { taskFlowText(R.string.task_flow_dd93194d27ce, "安静时段需使用不同的 HH:mm 开始和结束时间") }; result.put("quietHours", JSONObject().put("start", start).put("end", end)) }; result
    }
    val hasBusinessSource = steps.any { it.optJSONObject("businessWait") != null }
    var businessConsent by remember(plan.optString("id"), plan.optLong("revision")) { mutableStateOf(false) }
    PlanSheet(taskFlowText(R.string.task_flow_7ced0b586ad3, "审阅这个目标的执行范围"), close, { Button(onClick = { approve(settings.getOrThrow().put("businessEventConsent", businessConsent)) }, enabled = !busy && consent && (!hasBusinessSource || businessConsent) && settings.isSuccess && projectsAvailable) { Text(if (automatic) taskFlowText(R.string.task_flow_f968a57ea89c, "批准并按计划开始") else taskFlowText(R.string.task_flow_a84198f36de6, "保存授权，等我开始")) } }) {
        Text(plan.optString("goal"), fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        steps.forEachIndexed { at, step ->
            Text("${at + 1}. ${step.optString("goal")}", fontSize = 14.sp, lineHeight = 23.sp)
            PlanCaption(dependencyLabel(step, plan))
            val verification = step.optJSONObject("verification")
            if (verification?.optString("kind") == "workspace_artifact") {
                val projectId = verification.optString("projectId")
                Text(taskFlowText(R.string.task_flow_3502bf77e170, "项目 · ") + (projects.find { it.optString("id") == projectId }?.optString("name") ?: taskFlowText(R.string.task_flow_e1b6088a91d7, "名称暂不可核实")), fontSize = 14.sp)
                PlanCaption(taskFlowText(R.string.task_flow_3478d23cb750, "项目 ID · %1\$s", projectId))
                Text(taskFlowText(R.string.task_flow_5e10337e6008, "文件 · %1\$s", verification.optString("path")), fontSize = 14.sp, lineHeight = 23.sp)
                Text(taskFlowText(R.string.task_flow_c5847c21040a, "完整首行 · %1\$s", verification.optString("requiredHeading")), fontSize = 14.sp, lineHeight = 23.sp)
                Text(taskFlowText(R.string.task_flow_98c8c353ae73, "最小大小 · %1\$s UTF-8 字节", verification.optLong("minBytes")), fontSize = 14.sp)
                PlanCaption(taskFlowText(R.string.task_flow_2bbe82081089, "机器核验文件身份、可读性、首行和大小，不证明语义内容质量。"))
            } else PlanCaption(taskFlowText(R.string.task_flow_45a73a9731c1, "手机应用中的原任务结果需明确核对。"))
            step.optJSONObject("remoteWait")?.let { remote ->
                Text(taskFlowText(R.string.task_flow_9181602cdfac, "先等待原电脑进程退出码 0"), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                PlanCaption(taskFlowText(R.string.task_flow_73096397f1d8, "电脑 %1\$s · %2\$s\n环境 %3\$s · Job %4\$s", remote.optString("profileId"), remote.optString("serviceBase"), remote.optString("environmentId"), remote.optString("jobId")))
                var showIdentity by remember(step.optString("id")) { mutableStateOf(false) }
                TextButton(onClick = { showIdentity = !showIdentity }) { Text(if (showIdentity) taskFlowText(R.string.task_flow_08c35502140e, "收起原执行身份") else taskFlowText(R.string.task_flow_4ccc84402764, "查看原执行身份")) }
                if (showIdentity) PlanCaption(taskFlowText(R.string.task_flow_ed21bc41f980, "原任务 %1\$s\nRun %2\$s · 代次 %3\$s\n原操作 %4\$s", remote.optString("taskId"), remote.optString("runId"), remote.optInt("coordinatorEpoch"), remote.optString("operationId")))
                PlanCaption(taskFlowText(R.string.task_flow_f7296074c67a, "这里只等待这份已存在的原进程回执，不提交、重试或停止电脑任务，也不推定它的业务目标已成功。"))
            }
        }
        steps.filter { it.optJSONObject("businessWait") != null }.forEach { step ->
            val source = step.getJSONObject("businessWait").getJSONObject("anchor")
            Text(taskFlowText(R.string.task_flow_f3f6270154d5, "入站前置 · %1\$s / %2\$s / %3\$s", source.optString("provider"), source.optString("conversationKind"), source.optString("conversationId")), fontSize = 14.sp)
            PlanCaption(taskFlowText(R.string.task_flow_979b1f1e914b, "原来源消息 %1\$s\n主体 %2\$s · 绑定 %3\$s\n只以同来源的一次新事件触发上面已写明的固定目标。消息正文不送入目标、不作为新指令，不自动回复或外发成果。来源已撤销、窗口缺失或版本变化会阻止新的消费/派发。", source.optString("eventId"), source.optString("principalId"), source.optString("bindingEpoch")))
        }
        if (hasBusinessSource) PlanToggle(taskFlowText(R.string.task_flow_0b4cd7ce54c5, "明确允许上述认证来源的新事件触发这个固定目标"), businessConsent) { businessConsent = it }
        if (!projectsAvailable) Text(taskFlowText(R.string.task_flow_f438f95e5528, "项目列表尚不可核实，请返回刷新项目后再审阅；没有改换项目或批准执行。"), color = Danger, fontSize = 14.sp)
        if (steps.any { it.optString("executor", "phone") == "phone" }) PlanChoice(taskFlowText(R.string.task_flow_1ab01e1f3e08, "手机执行位置"), if (phoneMode == "background") taskFlowText(R.string.task_flow_67b2031f086a, "独立后台屏") else taskFlowText(R.string.task_flow_f87dc24d25d5, "主屏兼容模式"), listOf("background" to taskFlowText(R.string.task_flow_67b2031f086a, "独立后台屏"), "main_screen" to taskFlowText(R.string.task_flow_f87dc24d25d5, "主屏兼容模式"))) { phoneMode = it }
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.toggleable(consent, role = Role.Checkbox) { consent = it }, verticalAlignment = Alignment.Top) { Checkbox(consent, null); Text(taskFlowText(R.string.task_flow_0bc6d99107a4, "同意将本目标需要的项目文件，或手机界面与可见文字，发送给我配置的云端模型。"), fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 10.dp)) }
        if (steps.any { it.optString("executor") == "workspace" }) PlanToggle(taskFlowText(R.string.task_flow_407a971666d5, "允许将指定文件冻结为本目标的私人成果"), freezeScope) { freezeScope = it }
        PlanToggle(taskFlowText(R.string.task_flow_a90ea982d54e, "按时间、依赖与条件自动推进"), automatic) { automatic = it }
        PlanCaption(taskFlowText(R.string.task_flow_a2c940709402, "最多启动 %1\$s 次任务 · %2\$s 小时后到期\n", maximum, hours) + (if (network) taskFlowText(R.string.task_flow_f1f479dfd840, "需要系统验证的网络") else taskFlowText(R.string.task_flow_ebf8eb3e8c7a, "未要求网络条件")) + (if (charging) taskFlowText(R.string.task_flow_1eb35b7fe975, " · 仅充电时") else "") + (if (minimum != "0") taskFlowText(R.string.task_flow_b90a233cbcee, " · 电量至少 %1\$s%%", minimum) else "") + (if (quiet) taskFlowText(R.string.task_flow_61820d770de8, " · 安静时段 %1\$s–%2\$s", start, end) else ""))
        TextButton(onClick = { runtimeExpanded = !runtimeExpanded }) { Text(if (runtimeExpanded) taskFlowText(R.string.task_flow_17567b5d0d5d, "收起运行设置") else taskFlowText(R.string.task_flow_1c41fd996487, "运行条件、窗口与预算")) }
        if (runtimeExpanded) {
        OutlinedTextField(maximum, { maximum = it.filter(Char::isDigit).take(4) }, label = { Text(taskFlowText(R.string.task_flow_50e19e9eec36, "本目标最多启动多少次任务")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        PlanCaption(taskFlowText(R.string.task_flow_88e0a162036a, "已占用 %1\$s 次；修改不清零，启动尚未确认时也保留占用。", previous?.optInt("admissions") ?: 0))
        OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(4) }, label = { Text(taskFlowText(R.string.task_flow_79646bef817b, "这次授权多少小时后到期")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        PlanToggle(taskFlowText(R.string.task_flow_04a91c162da6, "需要系统已验证的网络"), network) { network = it }; PlanToggle(taskFlowText(R.string.task_flow_5d0e3728b82f, "仅在充电时运行"), charging) { charging = it }
        OutlinedTextField(minimum, { minimum = it.filter(Char::isDigit).take(3) }, label = { Text(taskFlowText(R.string.task_flow_708552eb9922, "最低电量百分比，0 为不限制")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        PlanToggle(taskFlowText(R.string.task_flow_35d468ff5a39, "使用安静时段"), quiet) { quiet = it }
        if (quiet) { OutlinedTextField(start, { start = it.take(5) }, label = { Text(taskFlowText(R.string.task_flow_f3a65b3e29c4, "开始 HH:mm")) }, singleLine = true); OutlinedTextField(end, { end = it.take(5) }, label = { Text(taskFlowText(R.string.task_flow_763d3c3e0f5f, "结束 HH:mm")) }, singleLine = true) }
        }
        PlanCaption(taskFlowText(R.string.task_flow_0593633b44a9, "这份授权限定目标与能力范围。具体写入审批保留在原任务；文件满足已审标准后会自动推进，证据不足才等待核对。"))
        settings.exceptionOrNull()?.message?.let { Text(it, color = Danger, fontSize = 12.sp) }
        state.moduleErrors["owner.automationPlan"]?.let { Text(it, color = Danger, fontSize = 12.sp) }
    }
}

@Composable private fun PlanSheet(title: String, close: () -> Unit, primary: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = close) { Text(taskFlowText(R.string.task_flow_572cf45ba436, "返回")) }; Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)) }
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
private fun outcomeLabel(value: String): String = when (value) { "failed" -> taskFlowText(R.string.task_flow_d3c1c95e43f8, "前置失败"); "cancelled" -> taskFlowText(R.string.task_flow_9cd7814817c1, "前置取消"); "settled" -> taskFlowText(R.string.task_flow_4f2db0e712c1, "前置已结束（含未采用）"); else -> taskFlowText(R.string.task_flow_b6443854028a, "前置成功") }
private fun dependencyLabel(step: JSONObject, plan: JSONObject): String {
    val all = plan.optJSONArray("steps").objects(); val dependencies = step.optJSONArray("dependencies").objects()
    return if (dependencies.isEmpty()) taskFlowText(R.string.task_flow_09180eb35e6b, "无需前置步骤") else dependencies.joinToString(taskFlowText(R.string.task_flow_ad9464da161a, "，")) { ref -> taskFlowText(R.string.task_flow_a9d04a166b65, "步骤 %1\$s · %2\$s", all.indexOfFirst { it.optString("id") == ref.optString("stepId") } + 1, outcomeLabel(ref.optString("outcome"))) }
}
private fun stepStateLabel(step: JSONObject): String = when (step.optString("status")) { "completed" -> if (step.optJSONObject("completionEvidence")?.optString("verificationSource") == "native_workspace_artifact_standard") taskFlowText(R.string.task_flow_e45f39d76f11, "已按文件标准核验") else taskFlowText(R.string.task_flow_86d3c5fc8338, "真实结果已核对"); "failed" -> taskFlowText(R.string.task_flow_060175b54557, "已核实失败"); "skipped" -> taskFlowText(R.string.task_flow_e199550b530e, "此结果分支未采用"); "cancelled" -> taskFlowText(R.string.task_flow_ba638bf8e534, "步骤已取消"); else -> taskFlowText(R.string.task_flow_999a459c3f3f, "待处理") }
private fun originalTaskLabel(value: String): String = when (value) { "unbound", "" -> taskFlowText(R.string.task_flow_d8844968bac5, "尚未创建或关联"); "registered" -> taskFlowText(R.string.task_flow_c7fd285a1ec3, "已登记，尚未开始"); "unverified_result", "needs_confirmation" -> taskFlowText(R.string.task_flow_d9e0c5d230bc, "结果待核验"); "unknown", "reconciling" -> taskFlowText(R.string.task_flow_e4f7da2d1ff2, "已有操作待核对"); "revoked" -> taskFlowText(R.string.task_flow_1597d6e5da79, "已请求撤销，仍需核对实际状态"); "unavailable" -> taskFlowText(R.string.task_flow_2093e658e3d1, "暂不可读"); else -> taskStatus(value) }
