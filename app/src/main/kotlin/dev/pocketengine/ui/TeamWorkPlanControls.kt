package dev.pocketengine.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pocketengine.features.attachments.TeamWorkspaceOutcomeHost
import dev.pocketengine.features.teams.TaskStoreTeamWriteHost
import dev.pocketengine.store.TaskStore
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** In-place continuation of the original discussion: owner plan, current item, exact handoff and actual results. */
@Composable internal fun TeamWorkPlanControls(
    run: JSONObject, openPage: (String) -> TaskStoreTeamWriteHost.WorkPlanPage,
    runWork: (Runnable) -> Unit, onTask: (String) -> Unit, onArtifact: (TeamWorkspaceOutcomeHost.Selection) -> Unit,
    openRoundPage: ((String) -> dev.pocketengine.features.teams.TeamProjectRoundHost.Page)? = null,
    openFailedRoundPage: ((String, String) -> dev.pocketengine.features.teams.TeamProjectRoundHost.Page)? = null
) {
    val runId = run.getString("id")
    var opening by remember(runId) { mutableIntStateOf(0) }
    val opened = remember(runId, opening) { runCatching { openPage(runId) } }
    val page = opened.getOrNull()
    if (page == null) {
        Text("原工作项暂不可用：${opened.exceptionOrNull()?.message ?: "原任务未恢复"}", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { opening++ }) { Text("重新读取原团队") }; return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    var alive by remember(page) { mutableStateOf(true) }
    var busy by remember(page) { mutableStateOf(false) }
    var result by remember(page) { mutableStateOf(JSONObject()) }
    var pending by remember(page) { mutableStateOf<TaskStore.TeamWorkReview?>(null) }
    var pendingRevision by remember(page) { mutableStateOf<TaskStore.TeamWorkRevisionReview?>(null) }
    var shown by remember(page) { mutableStateOf(JSONObject()) }
    var accepted by remember(pending, pendingRevision) { mutableStateOf(false) }
    var notice by remember(page) { mutableStateOf<String?>(null) }
    var integrationBudget by remember(page) { mutableStateOf<JSONObject?>(null) }
    var integrationBudgetReview by remember(page) { mutableStateOf<TaskStore.TeamIntegrationBudgetReview?>(null) }
    var integrationBudgetShown by remember(page) { mutableStateOf(JSONObject()) }
    var integrationModelLimit by rememberSaveable(runId) { mutableStateOf("") }
    var integrationBudgetAccepted by remember(integrationBudgetReview) { mutableStateOf(false) }
    var integratorScopeStatus by remember(page) { mutableStateOf<JSONObject?>(null) }
    var integratorScopeReview by remember(page) { mutableStateOf<TaskStore.TeamIntegratorScopeReview?>(null) }
    var integratorScopeShown by remember(page) { mutableStateOf(JSONObject()) }
    var integratorMinutes by rememberSaveable(runId) { mutableStateOf("15") }
    var integratorScopeAccepted by remember(integratorScopeReview) { mutableStateOf(false) }
    // Owner-authored drafts survive leaving this local section; they have no authority until native review/approval.
    var draftText by rememberSaveable(runId) { mutableStateOf("[]") }
    var fullProject by rememberSaveable(runId) { mutableStateOf(false) }
    var commandRiskAccepted by rememberSaveable(runId) { mutableStateOf(false) }
    var commandText by rememberSaveable(runId) { mutableStateOf("[]") }

    val drafts = remember(draftText) { runCatching { JSONArray(draftText) }.getOrDefault(JSONArray()) }
    val members = run.optJSONArray("members").objects()
    fun memberName(id: String) = members.find { it.optString("id") == id }?.optString("name") ?: id
    fun edit(index: Int, field: String, value: Any) { val next = JSONArray(draftText); next.getJSONObject(index).put(field, value); draftText = next.toString(); pending = null }
    fun reviewedDrafts(): JSONArray { val result = JSONArray(); drafts.objects().forEach { row -> val copy = JSONObject(row.toString()); val paths = copy.optString("pathsText"); copy.remove("pathsText"); copy.put("paths", JSONArray(paths.lines().map(String::trim).filter(String::isNotEmpty))); result.put(copy) }; return result }
    fun add() { val next = JSONArray(draftText); next.put(JSONObject().put("workItemId", UUID.randomUUID().toString()).put("title", "").put("instruction", "").put("acceptance", "").put("memberId", members.firstOrNull()?.optString("id").orEmpty()).put("pathsText", "").put("dependsOn", JSONArray())); draftText = next.toString(); pending = null }
    DisposableEffect(page, lifecycle) {
        fun foreground() = page.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ -> foreground() }; lifecycle.addObserver(observer); foreground()
        onDispose { alive = false; page.close(); lifecycle.removeObserver(observer) }
    }
    fun <T> work(action: () -> T, accept: (T) -> Unit) {
        if (busy || !alive) return
        busy = true; notice = null
        try { runWork(Runnable { val outcome = runCatching(action); main.post { if (alive) { busy = false; outcome.fold(accept) { notice = it.message ?: "原工作项未改变，请核对后重试" } } } }) }
        catch (_: Exception) { busy = false; notice = "原任务工作队列不可用，未提交" }
    }
    LaunchedEffect(page, run.optLong("revision")) { work({ page.snapshot() }) { result = it } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("授权仅所选副本路径的新增/修改/删除；重命名需新旧两路径。原项目仍须独审、主人批准及唯一CAS。", style = MaterialTheme.typography.bodySmall)
        val plan = result.optJSONObject("workPlan") ?: run.optJSONObject("workPlan")
        NickTeamExecutionTree(result.optJSONObject("executionTree"), runId)
        if (plan == null) {
            Text("把当前条件结论落实为明确工作项", style = MaterialTheme.typography.titleSmall)
            TeamCaption("讨论只是提案。你确认每项负责人、前置工作、原项目准确文件/基线与本次父预算后，才允许成员在各自草稿中自主制作。最终原项目仍需独审与唯一整合。")
            if (drafts.length() == 0) TextButton(onClick = ::add, enabled = !busy) { Text("添加第一项工作") }
            drafts.objects().forEachIndexed { index, draft -> key(draft.getString("workItemId")) {
                OutlinedTextField(draft.optString("title"), { edit(index, "title", it) }, label = { Text("工作 ${index + 1} · 可交付结果") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.optString("instruction"), { edit(index, "instruction", it) }, label = { Text("明确要完成什么") }, minLines = 2, maxLines = 4, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.optString("acceptance"), { edit(index, "acceptance", it) }, label = { Text("验收依据与未决条件") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { members.forEach { member -> FilterChip(draft.optString("memberId") == member.optString("id"), { edit(index, "memberId", member.getString("id")) }, label = { Text(member.optString("name")) }, enabled = !busy) } }
                val paths = draft.optString("pathsText")
                OutlinedTextField(paths, { text -> edit(index, "pathsText", text) }, label = { Text("原项目准确文件路径，每行一个；新文件显示空基线") }, minLines = 2, maxLines = 5, enabled = !busy, modifier = Modifier.fillMaxWidth())
                TeamCaption("前置工作完成并有原宿主冻结贡献后，此项才可开始：")
                drafts.objects().filter { it.optString("workItemId") != draft.optString("workItemId") }.forEach { other ->
                    val id = other.getString("workItemId"); val deps = draft.optJSONArray("dependsOn").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet() }
                    FilterChip(id in deps, { edit(index, "dependsOn", JSONArray((if (id in deps) deps - id else deps + id).toList())) }, label = { Text(other.optString("title").ifBlank { "工作 ${drafts.objects().indexOf(other) + 1}" }) }, enabled = !busy)
                }
                TextButton(onClick = { val next = JSONArray(); drafts.objects().filter { it.optString("workItemId") != draft.optString("workItemId") }.forEach { kept -> val copy = JSONObject(kept.toString()); val deps = copy.getJSONArray("dependsOn"); copy.put("dependsOn", JSONArray((0 until deps.length()).map { deps.getString(it) }.filter { it != draft.getString("workItemId") })); next.put(copy) }; draftText = next.toString(); pending = null }, enabled = !busy) { Text("移除此未提交工作项") }
                HorizontalDivider()
            } }
            if (drafts.length() in 1..7) TextButton(onClick = ::add, enabled = !busy) { Text("再添加一项工作") }
            Row { Checkbox(fullProject, { fullProject = it; pending = null; commandRiskAccepted = false }, enabled = !busy); Text("完整项目协作：在真实完整副本中检索、精确修改、检查和交付") }
            if (fullProject) {
                TeamCaption("将捕获原父任务显选checkout的完整可交付源清单（含二进制、空目录和执行位）；已知凭据/依赖/构建缓存按原包规则排除，但不保证项目中没有秘密。核对实际清单后再授权。各成员有独立版本副本，绝不是OS安全沙箱。")
                TextButton(onClick = { work({ page.projectCommandSuggestions() }) { commandText = it.toString(2); pending = null } }, enabled = !busy) { Text("读取原项目已有的测试/构建命令建议") }
                OutlinedTextField(commandText, { commandText = it; pending = null; accepted = false }, label = { Text("本次准确命令模板 JSON（id、command、requiredForFreeze；可限定forOutputPaths）") }, minLines = 4, maxLines = 10, enabled = !busy, modifier = Modifier.fillMaxWidth())
                TeamCaption("command明确executable、逐项argv、cwd、普通非秘密env及timeoutMillis；交互选owned_service + pty。每位作者和整合者至少一个requiredForFreeze检查。不识别的项目不会虚构测试命令；请按实际项目填写。")
                Row { Checkbox(commandRiskAccepted, { commandRiskAccepted = it; pending = null }, enabled = !busy); Text("我允许本团队执行下列本机命令及副本中的代码；同应用UID可能访问应用可访问文件/网络，未限制在checkout内") }
                TeamCaption("这些模板不是免审批白名单。每次准确sourceDigest/argv/终端方案仍走原主人审批；源码变化需要重新审阅。原项目写入仍是独审＋准确planHash批准＋唯一CAS，最终只组合基线与已审输出。")
            }
            OutlinedButton(onClick = { work({ if (fullProject) page.reviewProject(reviewedDrafts(), JSONObject().put("allowCommands", true).put("sameUidExecutionAcknowledged", commandRiskAccepted).put("commands", JSONArray(commandText))) else page.review(reviewedDrafts()) }) { pendingRevision = null; pending = it; shown = it.view(); accepted = false } }, enabled = !busy && drafts.length() > 0 && (!fullProject || commandRiskAccepted && commandText.length <= 24000)) { Text(if (fullProject) "审阅完整基线、命令与真实预算缺口" else "审阅原文件范围、依赖与实际预算") }
        } else {
            Text("当前工作计划 · ${plan.optJSONArray("items")?.length() ?: 0} 项", style = MaterialTheme.typography.titleSmall)
            TeamCaption("相同成员可以依次承接多个原assignment；每个工作项只有一个当前负责人。未启动或已核对确定终态的失败项可以精确交接；原草稿和效果保留，未知写入不能重派或退款。")
            plan.optJSONArray("items").objects().forEach { item -> key(item.getString("workItemId")) {
                Text(item.optString("title"), style = MaterialTheme.typography.titleSmall)
                TeamCaption("${memberName(item.optString("memberId"))} · ${item.optString("status")} · 工作项 r${item.optInt("itemRevision")} / 负责人 g${item.optInt("ownerGeneration")}")
                if ((item.optJSONArray("blockedBy")?.length() ?: 0) > 0) TeamCaption("等待原前置工作：" + item.getJSONArray("blockedBy").objects().joinToString { it.optString("workItemId").take(8) + " · " + it.optString("status") })
                item.optJSONObject("assignmentTask")?.optString("taskId")?.takeIf(String::isNotBlank)?.let { task -> TextButton(onClick = { onTask(task) }) { Text("打开这个原assignment与真实回执") } }
                item.optJSONArray("artifacts").objects().forEach { ref -> TextButton(onClick = { runCatching { onArtifact(TeamWorkspaceOutcomeHost.Selection.fromManifest(ref)) }.onFailure { notice = it.message } }) { Text("查看原冻结文件 · ${ref.optString("path")}") } }
                item.optJSONArray("assignmentHistory").objects().forEach { old -> TeamCaption("已交接：${memberName(old.optString("memberId"))} → ${memberName(item.optString("memberId"))}；旧assignment ${old.optString("assignmentId").take(8)} 保留历史，不重放。") }
                if (item.optString("status") in setOf("queued", "waiting_dependency", "blocked_dependency", "failed", "awaiting_user", "needs_confirmation", "paused", "interrupted", "cancelled")) {
                    members.filter { !it.optBoolean("exited") && it.optString("id") != item.optString("memberId") }.forEach { next -> TextButton(onClick = { work({ page.reviewTransfer(item.getString("workItemId"), next.getString("id"), item.getInt("itemRevision"), item.getInt("ownerGeneration")) }) { pendingRevision = null; pending = it; shown = it.view(); accepted = false } }, enabled = !busy) { Text("审阅交接给 ${next.optString("name")}") } }
                }
                if (item.optString("status") in setOf("paused", "awaiting_user", "interrupted", "failed", "cancelled")) TextButton(onClick = { work({ page.reviewContinuation(item.getString("workItemId"), item.getInt("itemRevision"), item.getInt("ownerGeneration")) }) { pendingRevision = null; pending = it; shown = it.view(); accepted = false } }, enabled = !busy) { Text("核对并只继续这个原工作项") }
                if (openFailedRoundPage != null && run.optJSONObject("productionScope")?.has("projectSelection") == true && item.optString("status") in setOf("failed", "cancelled", "interrupted", "paused", "awaiting_user", "needs_confirmation")) TeamProjectRoundControls(runId, { id -> openFailedRoundPage(id, item.getString("workItemId")) }, runWork, onTask, item.getString("workItemId"))
                if (item.optString("status") == "completed") {
                    val projectRevision = run.optJSONObject("productionScope")?.has("projectSelection") == true
                    var revisionCommands by rememberSaveable(runId, item.getString("workItemId")) { mutableStateOf("[]") }
                    var revisionCommandRisk by rememberSaveable(runId, item.getString("workItemId")) { mutableStateOf(false) }
                    var revisionText by rememberSaveable(runId, item.getString("workItemId")) { mutableStateOf("") }
                    run.optJSONArray("parentRequests").objects().filter { it.optString("workItemId") == item.getString("workItemId") && it.optString("kind") == "revision_proposal" }.forEach { request ->
                        val current = request.optInt("itemRevision") == item.getInt("itemRevision") && request.optInt("ownerGeneration") == item.getInt("ownerGeneration")
                        TeamCaption("主代理的修订提案（不是你的批准）· ${request.optString("parentTaskId").take(8)}" + if (current) "" else " · 旧版本，仅留历史")
                        Text(request.optString("text"), style = MaterialTheme.typography.bodySmall)
                        if (current) TextButton(onClick = { revisionText = request.getString("text"); pendingRevision = null }, enabled = !busy) { Text("带入我的修订草稿，仍需下面的精确审阅") }
                    }
                    OutlinedTextField(revisionText, { revisionText = it; pendingRevision = null }, label = { Text("沿此原成果继续：明确要改什么") }, minLines = 2, maxLines = 4, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    if (projectRevision) {
                        TeamCaption("完整项目新一轮将以原已交付组合包的准确完整源码为基线，不使用未接收的父活跃checkout；本版测试/构建命令与源码执行需要重新明确授权。")
                        TextButton(onClick = { work({ page.projectRevisionCommandSuggestions() }) { revisionCommands = it.toString(2); pendingRevision = null; revisionCommandRisk = false } }, enabled = !busy) { Text("读取原完整成果的实际测试/构建建议") }
                        OutlinedTextField(revisionCommands, { revisionCommands = it; pendingRevision = null; revisionCommandRisk = false }, label = { Text("新一轮准确命令模板 JSON") }, minLines = 3, maxLines = 9, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        Row { Checkbox(revisionCommandRisk, { revisionCommandRisk = it; pendingRevision = null }, enabled = !busy); Text("我允许本轮命令及新副本源码执行；同应用UID可访问应用可访问文件/网络，不是OS隔离") }
                    }
                    TextButton(onClick = { work({ if (projectRevision) page.reviewProjectRevision(item.getString("workItemId"), item.getInt("itemRevision"), item.getInt("ownerGeneration"), revisionText, JSONObject().put("allowCommands", true).put("sameUidExecutionAcknowledged", revisionCommandRisk).put("commands", JSONArray(revisionCommands))) else page.reviewRevision(item.getString("workItemId"), item.getInt("itemRevision"), item.getInt("ownerGeneration"), revisionText) }) { pending = null; pendingRevision = it; shown = it.view(); accepted = false } }, enabled = !busy && revisionText.isNotBlank() && (!projectRevision || revisionCommandRisk)) { Text("审阅这项修订、受影响依赖与原剩余预算") }
                }
                HorizontalDivider()
            } }
            TextButton(onClick = { work({ page.snapshot() }) { result = it } }, enabled = !busy) { Text("刷新真实分工与阻塞") }
            if (openRoundPage != null && run.optJSONObject("productionScope")?.has("projectSelection") == true && plan.optJSONArray("items").objects().all { it.optString("status") == "completed" }) TeamProjectRoundControls(runId, openRoundPage, runWork, onTask)
        }
        pending?.let { exact ->
            if (shown.optString("kind") == "work_plan") {
                Text("本次精确授权", style = MaterialTheme.typography.titleSmall)
                shown.getJSONObject("plan").getJSONArray("items").objects().forEach { item ->
                    TeamCaption("${item.optString("title")} → ${memberName(item.optString("memberId"))}；前置 ${item.getJSONArray("dependsOn").length()} 项")
                    item.getJSONArray("files").objects().forEach { file -> TeamCaption("${file.getString("path")} · 原基线 v${file.getInt("baseVersion")} / ${file.getString("baseHash").take(12)}") }
                }
                val budget = shown.getJSONObject("parentBudget"); val planning = shown.getJSONObject("budgetPlan")
                TeamCaption("模型请求总上限 ${budget.getInt("modelRequestLimit")} → ${shown.getInt("proposedModelRequestLimit")}；工具总上限 ${budget.getInt("toolOperationLimit")} → ${shown.getInt("proposedToolOperationLimit")}。已预留/实耗不会清零。")
                val project = shown.getJSONObject("scope").optJSONObject("projectSelection")
                if (project != null) {
                    TeamCaption("完整固定基线：${project.getJSONArray("sourceManifest").length()} 个源文件，身份 ${project.optString("snapshotIdentity").take(16)}；原父checkout ${project.optString("parentCheckoutId").take(12)}。独立目录不提供OS隔离。")
                    TeamProjectScopeSummary(project)
                    project.getJSONArray("templates").objects().forEach { template -> Text("${template.optString("id")} · ${if (template.optBoolean("requiredForFreeze")) "同版必要检查" else "可选命令"}\n${template.getJSONObject("request").toString()}", style = MaterialTheme.typography.bodySmall) }
                    TeamCaption("计划工具步骤 ${planning.optInt("plannedToolOperations")} ＋ 可见余量 ${planning.optInt("toolRetryMargin")}；模型请求新增估计 ${planning.optInt("estimatedAdditionalModelRequests")}（含单独计量的历史整理预计 ${planning.optInt("estimatedHistorySummaryRequests")}），${if (planning.optBoolean("modelEstimateCapped")) "受原模型工程上限约束，复杂多轮不保证足额" else "不是复杂任务足额保证"}。项目工具工程上限128不是默认授128；文字范围仍32。")
                    val parent = shown.getJSONObject("parent")
                    TeamCaption("原父真实grant剩余 ${parent.optInt("remainingOperations")}；累计上限 ${parent.optInt("stepCeiling")}，已耗 ${parent.optInt("spentOperations")}。原pool预留/实耗模型 ${budget.optInt("modelRequestsReservedOrUsed")}、工具 ${budget.optInt("toolOperationsReservedOrUsed")}；unknown单元 ${budget.optJSONObject("reservationStates")?.optJSONObject("unknown")?.toString() ?: "无"}，不退款或洗账。")
                    if (shown.optBoolean("parentBudgetIncreaseRequired")) TeamCaption("原父预算明确缺口 ${shown.optInt("parentOperationShortfall")}：仅预算建议剩余 → ${shown.optInt("proposedParentRemainingOperations")} / 累计上限 → ${shown.optInt("proposedParentStepCeiling")}。必须先明确批准；不延长期限、不扩大工具/文件/资源权限、不重启任务。")
                } else TeamCaption("所选文件计划工具步骤 ${planning.optInt("selectedFileToolSteps")}（含前置冻结原件读取 ${planning.optInt("dependencyArtifactReads")}），明确工具重试余量 ${planning.optInt("toolRetryMargin")}；模型新增估计 ${planning.optInt("estimatedAdditionalModelRequests")}（含1次重试，非复杂任务足额保证）。原父实际工具剩余 ${shown.getJSONObject("parent").optInt("remainingOperations")}，token/费用未知；本次不增加父授权。")
                TeamCaption("原讨论条件结论仍须核对：${shown.getJSONObject("plan").getJSONObject("decision").optString("conclusion")}")
            } else if (shown.optString("kind") in setOf("failed_transfer", "unstarted_transfer")) {
                TeamCaption("只交接这条已知终态工作给 ${memberName(shown.optString("newMemberId"))}；原 ${shown.optString("oldStatus")}，已确认效果 ${shown.optInt("confirmedEffectCount")} 条。旧操作不重发，旧负责人generation撤销；旧贡献/未提交草稿保留。")
                shown.getJSONArray("preservedDraftFiles").objects().forEach { file -> TeamCaption("保留 ${file.optString("path")} · v${file.optInt("version")} / ${file.optString("sha256").take(12)}") }
                shown.optJSONObject("projectSelection")?.let { TeamProjectScopeSummary(it); TeamCaption("完整失败副本将按此准确源身份继续；每次新源码/命令plan仍须原明确审批，不沿用旧命令执行回执。") }
                TeamCaption("本次明确为接手者分配 ${shown.optInt("proposedDraftOperations")} 次草稿操作；拟累计模型上限 ${shown.optInt("proposedModelRequestLimit")} / 工具 ${shown.optInt("proposedToolOperationLimit")}，含后续独审/唯一整合估计，不保证复杂任务足额。原已用/预留/unknown不重置、不退款，期限不延长。")
                if(shown.optBoolean("parentBudgetIncreaseRequired")) TeamCaption("原父实际操作不足 ${shown.optInt("parentOperationShortfall")}；只预算建议累计上限 → ${shown.optInt("proposedParentStepCeiling")}，须下方主人明确确认后重新审阅范围，不扩工具/资源/期限。")
            } else TeamCaption(if (shown.optString("kind") == "transfer") "只把这条未开始的工作项交接给 ${memberName(shown.optString("newMemberId"))}；原范围与预算不增加，旧assignment保留。" else "只继续这条原assignment的新真实attempt；保留原scope期限及剩余预算，未知操作不重放。")
            TeamCaption("期限：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(shown.optLong("expiresAt")))}；回答消息不续期或补预算。")
            Row { Checkbox(accepted, { accepted = it }, enabled = !busy); Text(if (shown.optBoolean("parentBudgetIncreaseRequired")) "我明确批准以上同run仅预算增加；其他授权与未知账保留" else "我确认此次准确范围、负责人、完整基线/命令及可见预算") }
            if (shown.optBoolean("parentBudgetIncreaseRequired")) Button(onClick = { work({ page.approveParentBudget(exact, shown.getString("digest")) }) { pending = it; shown = it.view(); accepted = false; notice = "仅预算CAS已保存；尚未批准成员执行。请再核对本次新范围与实际剩余。" } }, enabled = accepted && !busy) { Text("仅批准本次原父预算缺口") }
            Button(onClick = { work({ when (shown.optString("kind")) { "work_plan" -> page.approve(exact, shown.getString("digest")); "transfer", "failed_transfer", "unstarted_transfer" -> page.approveTransfer(exact, shown.getString("digest")); else -> page.approveContinuation(exact, shown.getString("digest")) } }) { pending = null; result = if (it.has("workPlan")) JSONObject().put("workPlan", it.getJSONObject("workPlan")).put("parentBudget", it.optJSONObject("parentBudget")) else it; notice = "已保存原生决定；执行与成果以原Runner回执为准。" } }, enabled = accepted && !busy && !shown.optBoolean("parentBudgetIncreaseRequired")) { Text(if (busy) "正在保存…" else "确认并沿原工作计划继续") }
            TextButton(onClick = { pending = null }, enabled = !busy) { Text("暂不授权，保留原工作") }
        }
        pendingRevision?.let { exact ->
            Text("沿原已整合成果继续 · 不是重建全队任务", style = MaterialTheme.typography.titleSmall)
            TeamCaption("本次要求：${shown.optString("text")}")
            val affected = shown.getJSONArray("affected")
            TeamCaption("仅修订以下 ${affected.length()} 个工作项；其依赖后继必须重新制作，其他冻结贡献保留。旧独审失效，最终仍需本版独审、你的准确批准及原唯一整合者 CAS。")
            affected.objects().forEach { item ->
                TeamCaption("${item.getString("workItemId").take(8)} → ${memberName(item.getString("memberId"))}；原assignment ${item.getString("assignmentId").take(8)}，r${item.getInt("assignmentRevision")} → ${item.getInt("assignmentRevision") + 1}")
                item.getJSONArray("newFiles").objects().forEach { file -> TeamCaption("${file.getString("path")} · 原成果准确基线 v${file.getInt("baseVersion")} / ${file.getString("baseHash").take(12)}") }
            }
            val budget = shown.getJSONObject("parentBudget"); val planning = shown.getJSONObject("budgetPlan")
            TeamCaption("同一父池累计：模型已预留/使用 ${budget.getInt("modelRequestsReservedOrUsed")}，总上限 ${budget.getInt("modelRequestLimit")} → ${shown.getInt("proposedModelRequestLimit")}；工具已预留/使用 ${budget.getInt("toolOperationsReservedOrUsed")}，总上限 ${budget.getInt("toolOperationLimit")} → ${shown.getInt("proposedToolOperationLimit")}。旧账不清零、未知不退款。")
            shown.optJSONObject("projectSelection")?.let { selected ->
                TeamProjectScopeSummary(selected)
                TeamCaption("从原准确完整成果包 ${selected.getJSONObject("previousDelivery").optString("packageArtifactId").take(12)} 继续；本次完整基线 ${selected.optString("snapshotIdentity").take(16)}。父活跃checkout不覆盖。")
                selected.getJSONArray("templates").objects().forEach { template -> Text("${template.optString("id")} · ${template.getJSONObject("request")}", style = MaterialTheme.typography.bodySmall) }
            }
            TeamCaption("本轮计划工具步骤 ${planning.optInt("plannedToolOperations")} + 明确重试余量 ${planning.optInt("toolRetryMargin")}；模型新增估计 ${planning.optInt("estimatedAdditionalModelRequests")}（含单独计量的历史整理预计 ${planning.optInt("estimatedHistorySummaryRequests")}，估计不保证）。工程上限模型 ${planning.optInt("modelEngineeringMaximum")} / 工具 ${planning.optInt("toolEngineeringMaximum")} 不是默认授权或复杂任务保证；已用/预留/unknown不清，token/费用未知。")
            if (shown.optBoolean("parentBudgetIncreaseRequired")) TeamCaption("真实父grant不足：本次明确增加 ${shown.optInt("parentOperationShortfall")}，剩余 → ${shown.optInt("proposedParentRemainingOperations")}，累计上限 → ${shown.optInt("proposedParentStepCeiling")}；不延长期限、不扩大工具/资源权限、不恢复旧控制。")
            Row { Checkbox(accepted, { accepted = it }, enabled = !busy); Text("我确认本次受影响工作项、原成果基线与可见累计预算") }
            if (shown.optBoolean("parentBudgetIncreaseRequired")) Button(onClick = { work({ page.approveRevisionParentBudget(exact, shown.getString("digest")) }) { pendingRevision = it; shown = it.view(); accepted = false; notice = "同run预算CAS已保存，请重新核对本轮完整基线/命令；尚未授权执行。" } }, enabled = accepted && !busy) { Text("仅批准本轮父预算缺口并重读范围") }
            Button(onClick = { work({ page.approveRevision(exact, shown.getString("digest")) }) { pendingRevision = null; result = JSONObject().put("workPlan", it.getJSONObject("workPlan")).put("parentBudget", it.optJSONObject("parentBudget")); notice = "原工作项新修订已排入；保留未变贡献，等待本版真实制作及新独审。" } }, enabled = accepted && !busy && !shown.optBoolean("parentBudgetIncreaseRequired")) { Text(if (busy) "正在保存…" else "批准并沿这些原工作项继续") }
            TextButton(onClick = { pendingRevision = null }, enabled = !busy) { Text("暂不修订，保留当前成果") }
        }
        if (plan != null && run.optJSONObject("productionScope")?.has("projectSelection") == true) {
            TextButton(onClick = { work({ page.integratorScopeStatus() }) { integratorScopeStatus = it; integratorScopeReview = null } }, enabled = !busy) { Text("审阅唯一整合者的续接范围与期限") }
            integratorScopeStatus?.let { status ->
                TeamCaption("只续接原唯一整合者，不恢复作者写入权限。原费用、成果、审阅和操作保留。父任务已批期限：${java.util.Date(status.getLong("parentDeadlineAt"))}。")
                OutlinedTextField(integratorMinutes, { integratorMinutes = it; integratorScopeReview = null }, label = { Text("明确本次续接有效分钟（不得超过父任务期限）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { work({ val minutes = integratorMinutes.toLong(); require(minutes > 0); page.reviewIntegratorScope(Math.addExact(System.currentTimeMillis(), Math.multiplyExact(minutes, 60000L))) }) { integratorScopeReview = it; integratorScopeShown = it.view(); integratorScopeAccepted = false } }, enabled = !busy && integratorMinutes.toLongOrNull()?.let { it > 0 } == true) { Text("捕获当前完整源码并核对原命令范围") }
                integratorScopeReview?.let { exact ->
                    TeamCaption("原整合目标：${integratorScopeShown.optString("goal")}\n准确副本：${integratorScopeShown.getJSONObject("capturedSource").getString("checkoutId")}\n源码摘要：${integratorScopeShown.getJSONObject("capturedSource").getString("sourceDigest")}\n有效至：${java.util.Date(integratorScopeShown.getLong("deadlineAt"))}\n仍只允许原命令模板：${integratorScopeShown.getJSONArray("templates")}")
                    Row { Checkbox(integratorScopeAccepted, { integratorScopeAccepted = it }, enabled = !busy); Text("我明确批准以上源码与期限；副本代码会执行，命令同应用UID，可能访问应用可访问的文件和网络，不是目录安全沙箱") }
                    Button(onClick = { work({ page.approveIntegratorScope(exact, integratorScopeShown.getString("digest")) }) { integratorScopeReview = null; integratorScopeStatus = null; notice = "原唯一整合者续接范围已保存，尚未启动。请回原成果入口明确继续；作者过期写权和旧账不变。" } }, enabled = !busy && integratorScopeAccepted) { Text("仅保存原整合者续接范围（不启动）") }
                }
            }
            TextButton(onClick = { work({ page.integrationBudgetStatus() }) { integrationBudget = it; integrationBudgetReview = null } }, enabled = !busy) { Text("为这次整合追加调用次数") }
            integrationBudget?.let { current ->
                val budget = current.getJSONObject("parentBudget")
                TeamCaption("这次整合已占用调用（含预留/使用） ${budget.optInt("modelRequestsReservedOrUsed")} / ${budget.optInt("modelRequestLimit")}，工具 ${budget.optInt("toolOperationsReservedOrUsed")} / ${budget.optInt("toolOperationLimit")}。包含历史整理调用；已完成内容保留，已用和未确定用量不清零。")
                current.optJSONObject("parentExecutionScope")?.takeIf { it.optBoolean("scopePresent") }?.let { scope -> TeamCaption("原父任务累计模型剩余 ${scope.optInt("modelCallsRemaining")}；本页不会扩大原任务的累计上限，不足时请先回任务面板核对。") }
                OutlinedTextField(integrationModelLimit, { integrationModelLimit = it; integrationBudgetReview = null }, label = { Text("明确新的本次累计调用上限（最多 ${current.optInt("modelEngineeringMaximum")}）") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { work({ page.reviewIntegrationBudget(integrationModelLimit.toInt()) }) { integrationBudgetReview = it; integrationBudgetShown = it.view(); integrationBudgetAccepted = false } }, enabled = !busy && integrationModelLimit.toIntOrNull() != null) { Text("核对这次追加次数") }
                integrationBudgetReview?.let { exact ->
                    TeamCaption("本次调用上限 ${budget.optInt("modelRequestLimit")} → ${integrationBudgetShown.optInt("proposedModelRequestLimit")}。仅额度，不改变工具/文件/命令权限或期限，不会启动，也不重做已完成检查、命令或冻结包。")
                    Row { Checkbox(integrationBudgetAccepted, { integrationBudgetAccepted = it }, enabled = !busy); Text("我明确同意以上追加次数，原已用次数保留") }
                    Button(onClick = { work({ page.approveIntegrationBudget(exact, integrationBudgetShown.getString("digest")) }) { saved -> integrationBudgetReview = null; integrationBudget = JSONObject(current.toString()).put("parentBudget", saved.getJSONObject("parentBudget")); notice = "预算已保存，尚未启动。请回原已批准差异处明确继续唯一整合者；旧成果/费用保留。" } }, enabled = !busy && integrationBudgetAccepted) { Text("仅保存本次追加次数") }
                }
                TextButton(onClick = { onTask(current.getString("taskId")) }, enabled = !busy) { Text("查看原唯一整合任务") }
                TextButton(onClick = { work({ page.synchronizeIntegrationBudget() }) { integrationBudget = it; integrationBudgetReview = null; notice = "已重新读取保存的调用次数；未启动执行。" } }, enabled = !busy) { Text("重新读取保存的调用次数（不启动）") }
            }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
