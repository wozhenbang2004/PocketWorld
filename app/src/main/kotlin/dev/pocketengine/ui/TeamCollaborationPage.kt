package dev.pocketengine.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import java.util.UUID
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

internal data class TeamCollaborationStamp(val runId: String, val generation: Int, val revision: Long)
internal fun teamCollaborationStamp(run: JSONObject) = TeamCollaborationStamp(run.getString("id"), run.getInt("generation"), run.getLong("revision"))
internal fun reviewedTeamCollaborationAction(run: JSONObject, stamp: TeamCollaborationStamp, command: String, memberId: String?, text: String = ""): JSONObject {
    require(teamCollaborationStamp(run) == stamp) { "团队状态已改变，请刷新后操作" }
    require(command in setOf("pause", "resume", "feedback", "continueMessages")) { "操作未开放" }
    require(run.optString("status") != "cancelled") { "团队任务已停止" }
    val input = JSONObject().put("runId", stamp.runId).put("expectedGeneration", stamp.generation).put("expectedRevision", stamp.revision)
    if (memberId != null) {
        val member = teamRows(run.optJSONArray("members")).find { it.optString("id") == memberId } ?: error("成员不属于当前团队任务")
        input.put("memberId", memberId).put("expectedMemberGeneration", member.getInt("generation"))
    }
    if (command == "continueMessages") require(memberId != null) { "请选择有未读消息的成员" }
    if (command == "feedback") { require(text.isNotBlank() && text.length <= 4000); input.put("text", text.trim()) }
    return input
}
internal fun teamCollaborationResultMatches(result: JSONObject?, requestId: String, command: String, runId: String): Boolean =
    result != null && result.optString("requestId") == requestId && result.optString("command") == command && result.optString("runId") == runId && result.opt("ok") is Boolean
internal data class TeamAssignmentDraft(val memberId: String, val assignment: String, val acceptance: String, val dependsOn: Set<String>)
internal fun reviewedTeamPlanPayload(current: JSONObject, reviewedDefinition: String, drafts: List<TeamAssignmentDraft>): JSONObject {
    require(current.toString() == reviewedDefinition) { "团队配置已改变，请重新载入" }
    val ids = teamRows(current.optJSONArray("members")).map { it.getString("id") }.toSet()
    require(drafts.size == ids.size && drafts.map { it.memberId }.toSet() == ids)
    val assignments = JSONArray()
    drafts.forEach { draft ->
        require(draft.assignment.isNotBlank() && draft.assignment.length <= 2000 && draft.acceptance.length <= 1200)
        require(draft.memberId !in draft.dependsOn && ids.containsAll(draft.dependsOn))
        assignments.put(JSONObject().put("memberId", draft.memberId).put("assignment", draft.assignment.trim()).put("acceptance", draft.acceptance.trim()).put("dependsOn", JSONArray(draft.dependsOn.toList())))
    }
    return JSONObject().put("teamId", current.getString("id")).put("expectedDefinition", reviewedDefinition).put("assignments", assignments)
}
private fun teamRows(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
private fun collaborationStatus(status: String): String = when (status) {
    "queued" -> "等待开始"; "running" -> "本轮工作中"; "completed" -> "本轮已产出"; "partial" -> "部分分工未完成"; "failed" -> "本轮未完成"
    "awaiting_review" -> "冻结成果待独审"; "awaiting_input" -> "等待回答原问题"; "awaiting_user" -> "等待主人处理"; "waiting_dependency" -> "等待前置分工"; "blocked_dependency" -> "前置未完成，已阻断"; "blocked_unknown" -> "原操作结果待核对"
    "unsupported" -> "执行器未接入"; "blocked" -> "环境未就绪"; "awaiting_approval" -> "等待本机授权"; "paused" -> "已暂停"; "interrupted" -> "已中断，等待主人继续"; "cancelled" -> "已停止"; "exited" -> "已退出"; else -> status
}

/** A question keeps its original assignment even when another question/member becomes latest. */
internal fun teamConversationRows(run: JSONObject): List<JSONObject> {
    val rows = teamRows(run.optJSONArray("transcript")).map { JSONObject(it.toString()) }.toMutableList()
    val assignments = if (run.optJSONObject("workPlan") != null) teamRows(run.getJSONObject("workPlan").optJSONArray("items")).mapNotNull { item -> item.optJSONObject("assignmentTask")?.let { item.optString("memberId") to it } } else teamRows(run.optJSONArray("members")).mapNotNull { member -> member.optJSONObject("assignmentTask")?.let { member.optString("id") to it } }
    assignments.forEach { (memberId, assignment) ->
        teamRows(assignment.optJSONArray("questions")).forEach { question ->
            rows += JSONObject(question.toString()).put("messageId", "question:" + question.getString("questionId"))
                .put("kind", "assignment_question").put("memberId", memberId)
                .put("assignmentId", assignment.getString("assignmentId")).put("content", question.optString("question"))
                .put("at", question.optLong("askedAt")).put("role", "assistant")
        }
    }
    return rows.distinctBy { it.getString("messageId") }.sortedWith(compareBy<JSONObject> { it.optLong("at") }.thenBy { it.getString("messageId") })
}
private fun conversationDelivery(run: JSONObject, row: JSONObject): String {
    if (row.optString("kind") == "assignment_question") return when (row.optString("state")) {
        "consumed" -> "回答已由原 Runner 的完成回执确认消费"
        "answered" -> "回答已保存，尚未确认消费"
        "answered_historical" -> "迟答仅留历史，不唤醒已停止或已改版工作"
        else -> "等待主人回答这条原问题"
    }
    val id = row.optString("deliveryId", row.optString("peerMessageId"))
    val nativeAssignments = if (run.optJSONObject("workPlan") != null) teamRows(run.getJSONObject("workPlan").optJSONArray("items")).mapNotNull { it.optJSONObject("assignmentTask") } else teamRows(run.optJSONArray("members")).mapNotNull { it.optJSONObject("assignmentTask") }
    val nativeInput = nativeAssignments.flatMap { teamRows(it.optJSONArray("messages")) }.find { it.optString("messageId") == id }
    if (nativeInput != null) return when(nativeInput.optString("state")) { "consumed" -> "原 Runner 已完成并确认消费"; "included" -> "已选入原执行输入，尚未取得完成消费回执"; "historical" -> "已存历史；已冻结或停止的工作不会被唤醒"; else -> "已到原assignment队列，等待安全请求边界" }
    val letter = (teamRows(run.optJSONArray("inbox")) + teamRows(run.optJSONArray("mailbox"))).find { it.optString("id") == id }
    return when (letter?.optString("status")) {
        "consumed" -> "已用于对方成功完成的工作"; "delivering" -> "已纳入本轮输入，尚未确认消费"
        "partially_consumed" -> "部分指定成员已消费"; "pending", "unread" -> "已定向排队，尚未进入对方工作"
        else -> ""
    }
}

/** One original team-run worksite: lazy shared flow, explicit recipients/Reply and a persistent composer.
 * No simulated members, chat-triggered grants, or automatic scrolling away from a reading anchor. */
@Composable
internal fun TeamCollaborationPage(
    run: JSONObject?, definition: JSONObject?, commands: Set<String>, busyCommands: Set<String>,
    lastResult: JSONObject?, error: String?, onCommand: (String, JSONObject) -> String, onClose: () -> Unit,
    receipts: Map<String, JSONObject> = emptyMap(), onDetails: (() -> Unit)? = null,
    workItems: (@Composable () -> Unit)? = null,
    outcomes: (@Composable (JSONObject?) -> Unit)? = null
) {
    val runId = run?.optString("id").orEmpty()
    val members = teamRows(run?.optJSONArray("members"))
    var selected by rememberSaveable(runId) { mutableStateOf<String?>(null) }
    var recipient by rememberSaveable(runId) { mutableStateOf(run?.optString("leaderId").orEmpty()) }
    var replyId by rememberSaveable(runId) { mutableStateOf("") }
    var mode by rememberSaveable(runId) { mutableStateOf("discussion") }
    var drafts by rememberSaveable(runId) { mutableStateOf(mapOf<String, String>()) }
    var tab by rememberSaveable(runId) { mutableIntStateOf(0) }
    var composerOptions by rememberSaveable(runId) { mutableStateOf(false) }
    var pending by remember(runId) { mutableStateOf<List<String>?>(null) }
    var localError by remember(runId) { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf(false) }
    val listStates = rememberSaveableStateHolder()
    val rows = run?.let(::teamConversationRows).orEmpty()
    val reply = rows.find { it.optString("messageId") == replyId }
    val member = members.find { it.optString("id") == recipient }
    val draftKey = "$recipient|$replyId|$mode"
    val text = drafts[draftKey].orEmpty()
    fun name(id: String) = if (id == "@native-reviewer") "独立审查者" else members.find { it.optString("id") == id }?.optString("name") ?: id
    LaunchedEffect(lastResult, receipts, pending) {
        val sent = pending ?: return@LaunchedEffect
        val receipt = receipts[sent[0]] ?: lastResult
        if (teamCollaborationResultMatches(receipt, sent[0], sent[1], runId)) {
            if (receipt?.optBoolean("ok") == true && drafts[sent[2]] == sent[3]) drafts = drafts - sent[2]
            if (receipt?.optBoolean("ok") == false) localError = receipt.optString("message", "消息未确认，草稿保留")
            pending = null
        }
    }
    fun control(command: String) {
        if (run == null) return
        try { onCommand(command, if (command == "cancel") JSONObject().put("runId", runId) else reviewedTeamCollaborationAction(run, teamCollaborationStamp(run), command, selected)); localError = null }
        catch (failure: Exception) { localError = failure.message }
    }
    fun send() {
        if (run == null || member == null) return
        try {
            val input = JSONObject().put("runId", runId).put("expectedRevision", run.getLong("revision"))
                .put("expectedGeneration", run.getInt("generation")).put("expectedMemberGeneration", member.getInt("generation"))
                .put("memberId", recipient).put("recipientMemberId", recipient).put("quoteMessageId", replyId)
            val command = if (reply?.optString("kind") == "assignment_question") {
                input.put("assignmentId", reply.getString("assignmentId")).put("questionId", reply.getString("questionId"))
                    .put("answerId", UUID.randomUUID().toString()).put("answer", text.trim()).put("continueSafely", true).put("mode", "reply")
                "answerAssignment"
            } else if (mode == "steer") { input.put("text", text.trim()); "feedback" }
            else { input.put("messageId", UUID.randomUUID().toString()).put("text", text.trim()).put("mode", mode); if (run.optJSONObject("workPlan") != null && reply?.optString("assignmentId")?.isNotBlank() == true) input.put("assignmentId", reply.getString("assignmentId")); "sendMessage" }
            val request = onCommand(command, input); pending = listOf(request, command, draftKey, text); localError = null
        } catch (failure: Exception) { localError = failure.message }
    }
    Surface(color = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("返回") }
                Text(run?.optString("teamName") ?: "团队", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (run != null) TextButton(onClick = { onCommand("get", JSONObject().put("runId", runId)) }) { Text("刷新") }
            }
            if (run == null) Text("正在读取原团队任务…", Modifier.padding(20.dp))
            else {
                Text(run.optString("goal"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${collaborationStatus(run.optString("status"))} · 协调者 ${name(run.optString("leaderId"))}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    val status = members.find { it.optString("id") == selected }?.optString("status") ?: run.optString("status")
                    val action = if (status in setOf("paused", "interrupted", "awaiting_input", "awaiting_user", "blocked_unknown")) "resume" else "pause"
                    TextButton(onClick = { control(action) }, enabled = action in commands && action !in busyCommands && run.optString("status") != "cancelled" && !(action == "resume" && run.optJSONObject("workPlan") != null)) { Text(if (action == "resume") "继续原工作" else if (selected == null) "暂停全队" else "暂停此成员") }
                    TextButton(onClick = { control("cancel") }, enabled = "cancel" in commands && "cancel" !in busyCommands && run.optString("status") != "cancelled") { Text("停止全队", color = MaterialTheme.colorScheme.error) }
                }
                if(run.optString("discussionFormat")==dev.pocketengine.features.teams.TeamEvidenceDiscussion.FORMAT) {
                    Text("证据讨论 · "+dev.pocketengine.features.teams.TeamEvidenceDiscussion.label(run.optInt("round"))+" · 模型 ${run.optInt("callsUsed")}/${run.optInt("callLimit")}，只读工具 ${run.optInt("toolCallsUsed")}/${run.optInt("toolCallLimit")}",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(horizontal=20.dp,vertical=4.dp))
                    if(run.optJSONObject("discussion")?.optString("status")=="inconclusive") Text(run.optJSONObject("discussion")?.optString("message").orEmpty(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=20.dp))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TeamMemberNode("共同现场", "全部成员", "共享消息与成果", selected == null) { selected = null }
                    members.filterNot { it.optBoolean("hidden") || it.optBoolean("exited") }.forEach { slot ->
                        TeamMemberNode(slot.optString("name"), collaborationStatus(slot.optString("status")), slot.optString("role"), selected == slot.optString("id")) { selected = slot.optString("id") }
                    }
                }
                val questions = rows.filter { it.optString("kind") == "assignment_question" && it.optString("state") == "pending" }
                if (questions.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("需要你的回答 · ${questions.size}", style = MaterialTheme.typography.labelLarge)
                            Text(questions.first().optString("content"), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        TextButton(onClick = { val question = questions.first(); tab = 0; selected = question.optString("memberId"); replyId = question.getString("messageId"); if (members.any { it.optString("id") == selected }) recipient = selected!! }, enabled = run.optString("status") != "cancelled") { Text("查看并回答") }
                    }
                }
                TabRow(tab, containerColor = Color.White) { listOf("对话", "分工", "成果").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) } }
                Box(Modifier.weight(1f).fillMaxWidth()) { listStates.SaveableStateProvider("$runId|$tab|$selected") { val listState = rememberLazyListState()
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (tab == 0) {
                        val visible = rows.filter { selected == null || it.optString("memberId") == selected || it.optString("recipientMemberId", it.optString("targetMemberId")) == selected }
                        if (visible.isEmpty()) item { Text("尚无此成员的实际消息。", style = MaterialTheme.typography.bodyMedium) }
                        items(visible, key = { it.getString("messageId") }) { row ->
                            Column(Modifier.fillMaxWidth()) {
                                val actor = when (row.optString("role")) { "user" -> "你"; "parent_model" -> "主代理 · " + row.optString("senderParentTaskId").take(8); else -> row.optJSONObject("memberSnapshot")?.optString("name")?.takeIf { it.isNotBlank() } ?: name(row.optString("memberId")) }
                                val target = row.optString("recipientMemberId", row.optString("targetMemberId"))
                                Text(actor + if (target.isNotBlank()) " → ${name(target)}" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                row.optString("quoteMessageId").takeIf { it.isNotBlank() }?.let { quote -> Text("回复：" + (rows.find { it.optString("messageId") == quote }?.optString("content")?.take(100) ?: "原消息 $quote"), style = MaterialTheme.typography.bodySmall) }
                                SelectionContainer { Text(row.optString("content"), style = MaterialTheme.typography.bodyMedium) }
                                if(row.has("discussionRecordId")) TeamDiscussionReferences(run,row)
                                val delivery = conversationDelivery(run, row)
                                row.optString("continuationReason").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                if (delivery.isNotBlank()) Text(delivery, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (row.optString("kind") == "assignment_question" && row.optString("answer").isNotBlank()) Text((if (row.optString("answerActor") == "parent_model") "主代理的回答（不是主人批准）：" else "你的回答：") + row.optString("answer"), style = MaterialTheme.typography.bodyMedium)
                                if (row.optString("kind") != "assignment_question" || row.optString("state") == "pending") TextButton(onClick = {
                                    replyId = row.getString("messageId"); if (row.optString("role") != "user" && members.any { it.optString("id") == row.optString("memberId") }) recipient = row.getString("memberId")
                                }, enabled = run.optString("status") != "cancelled") { Text(if (row.optString("kind") == "assignment_question") "回答这条原问题" else "引用回复") }
                                HorizontalDivider()
                            }
                        }
                    } else if (tab == 1) {
                        items(members.filter { !it.optBoolean("hidden") && !it.optBoolean("exited") && (selected == null || it.optString("id") == selected) }, key = { "role:" + it.getString("id") }) { slot ->
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(slot.optString("name"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                        Text(collaborationStatus(slot.optString("status")), style = MaterialTheme.typography.labelMedium)
                                    }
                                    Text(slot.optString("assignment").ifBlank { slot.optString("role") }, style = MaterialTheme.typography.bodyMedium)
                                    slot.optString("acceptance").takeIf { it.isNotBlank() }?.let { Text("交付要求 · $it", style = MaterialTheme.typography.bodySmall) }
                                    val deps = slot.optJSONArray("dependsOn")
                                    if (deps != null && deps.length() > 0) Text("前置成员 · " + (0 until deps.length()).joinToString("、") { name(deps.getString(it)) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item { workItems?.invoke() }
                        items(teamRows(run.optJSONObject("board")?.optJSONArray("cards")).filter { selected == null || it.optString("memberId") == selected }, key = { it.getString("id") }) { card ->
                            Column { Text("${name(card.optString("memberId"))} · ${collaborationStatus(card.optString("status"))}", style = MaterialTheme.typography.labelMedium); Text(card.optString("title")); if (card.optString("acceptance").isNotBlank()) Text("验收：${card.optString("acceptance")}", style = MaterialTheme.typography.bodySmall); HorizontalDivider() }
                        }
                        item { onDetails?.let { TextButton(onClick = it) { Text("查看真实执行、审批与证据") } }; if (definition != null && "savePlan" in commands) TextButton(onClick = { edit = true }) { Text("编辑下次目标的分工") } }
                    } else {
                        item {
                            Text(if (selected == null) "共同成果" else "${name(selected!!)}的成果", style = MaterialTheme.typography.titleLarge)
                            Text("原文件、审阅和协调回执分开核对；回执不代替成果验收。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (run.optString("summary").isNotBlank()) { Text("协调回执", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)); SelectionContainer { Text(run.optString("summary")) }; HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
                            outcomes?.invoke(members.find { it.optString("id") == selected })
                        }
                    }
                }
                } }
                HorizontalDivider()
                AffectComposerFrame(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    editor = {
                        if (reply != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("回复 ${name(reply.optString("memberId"))}：${reply.optString("content").take(100)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { replyId = "" }) { Text("取消引用") }
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = text, onValueChange = { drafts = drafts + (draftKey to it) }, enabled = pending == null,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = if (reply?.optString("kind") == "assignment_question") "回答这条原问题（不是文件批准）" else "发给 ${name(recipient)}" }.heightIn(min = 44.dp, max = 120.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                            minLines = 1, maxLines = 4,
                            textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 24.sp, color = Ink),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink),
                            decorationBox = { inner -> Box {
                                if (text.isEmpty()) Text(if (reply?.optString("kind") == "assignment_question") "回答这条原问题（不是文件批准）" else "发给 ${name(recipient)}…", fontSize = 16.sp, lineHeight = 24.sp, color = SecondaryInk)
                                inner()
                            } }
                        )
                        if (composerOptions) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                members.filterNot { it.optBoolean("exited") }.forEach { slot -> FilterChip(recipient == slot.optString("id"), { if (reply?.optString("kind") != "assignment_question") recipient = slot.optString("id") }, label = { Text("@${slot.optString("name")} · ${slot.optString("id")}") }) }
                            }
                            if (reply == null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("discussion" to "讨论", "queue" to "下一工作点", "steer" to "暂停改方向").forEach { (id, label) -> FilterChip(mode == id, { mode = id }, label = { Text(label) }) } }
                        }
                    },
                    toolbar = {
                        TextButton(onClick = { composerOptions = !composerOptions }, modifier = Modifier.weight(1f).semantics { contentDescription = if (composerOptions) "收起发送选项" else "收件人与方式" }) {
                            Text(if (composerOptions) "收起发送选项" else "${name(recipient)} · " + when(mode) { "queue" -> "下一工作点"; "steer" -> "暂停改方向"; else -> "讨论" }, style = MaterialTheme.typography.labelMedium)
                            AffectGlyph("chevron", Modifier.padding(start = 6.dp).size(12.dp), SecondaryInk)
                        }
                        AffectSendButton(label = if (pending != null) "保存中" else if (reply?.optString("kind") == "assignment_question") "回答" else "发送", onClick = ::send,
                            submitting = pending != null,
                            enabled = pending == null && text.isNotBlank() && text.length <= 4000 && run.optString("status") != "cancelled" && (if (reply?.optString("kind") == "assignment_question") "answerAssignment" else if (mode == "steer") "feedback" else "sendMessage") in commands)
                    },
                    footer = {
                        Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 8.dp)) {
                            Text(if (mode == "steer" && reply?.optString("kind") != "assignment_question") "保存后暂停所选成员；不会停止无关成员。" else if (run.optJSONObject("workPlan") != null && reply?.optString("assignmentId").isNullOrBlank()) "普通@给成员下一安全工作点；精确Reply保留原assignment，均不补预算。" else "普通消息只到所选成员；排队≠进入模型≠已消费。", style = MaterialTheme.typography.labelSmall, color = SecondaryInk)
                            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                )
            }
        }
    }
    if (edit && definition != null) TeamAssignmentEditor(definition, busyCommands, lastResult, error, onCommand, receipts) { edit = false }
}

/** Selection nodes use only persisted members; no decorative runners or inferred progress. */
@Composable
private fun TeamMemberNode(name: String, status: String, responsibility: String, selected: Boolean, onSelect: () -> Unit) {
    Surface(color = if (selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.widthIn(min = 124.dp, max = 184.dp).selectable(selected = selected, role = Role.Tab, onClick = onSelect).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = Ink, maxLines = 1)
            Text(status, style = MaterialTheme.typography.labelSmall, color = SecondaryInk, maxLines = 1)
            if (responsibility.isNotBlank()) Text(responsibility, style = MaterialTheme.typography.bodySmall, color = SecondaryInk, maxLines = 2)
        }
    }
}

/** Read-only native provenance within the existing shared flow, not an artifact viewer or an approval. */
@Composable
private fun TeamDiscussionReferences(run: JSONObject,row: JSONObject) {
    val record=teamRows(run.optJSONObject("discussion")?.optJSONArray("records")).find{it.optString("recordId")==row.optString("discussionRecordId")}?:return
    var expanded by rememberSaveable(row.optString("messageId")){mutableStateOf(false)}
    val member=teamRows(run.optJSONArray("members")).find{it.optString("id")==record.optString("memberId")}
    val current=teamRows(member?.optJSONArray("roundStates")).find{it.optInt("round")==record.optInt("round")}
    val active=current?.optInt("generation")==record.optInt("memberGeneration")&&current?.optString("status")=="completed"&&current?.optJSONObject("discussionRecord")?.optString("recordId")==record.optString("recordId")
    TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起原证据与回应关系" else "核对原证据与回应关系",style=MaterialTheme.typography.labelSmall)}
    if(expanded)Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(if(active)"原阶段记录 · 输入版本 "+record.optString("inputVersion").take(10) else "历史阶段记录：当前作者或输入已变，不能当作新结论依据",style=MaterialTheme.typography.bodySmall)
        val items=listOf("claims","challenges","responses").flatMap{teamRows(record.optJSONArray(it))}
        items.forEach{item->
            val claim=item.optString("claimId");val challenge=item.optString("challengeId");val response=item.optString("responseId")
            Text(buildString { if(claim.isNotBlank())append("主张 "+claim.take(10));if(challenge.isNotBlank())append(" → 质疑 "+challenge.take(10));if(response.isNotBlank())append(" → 原作者回应 "+response.take(10)) },style=MaterialTheme.typography.bodySmall)
        }
        val ids=items.flatMap{item->val values=item.optJSONArray("evidenceIds");(0 until(values?.length()?:0)).map{values!!.getString(it)}}.distinct()
        val evidence=teamRows(run.optJSONArray("members")).flatMap{teamRows(it.optJSONArray("evidence"))}
        if(ids.isEmpty())Text("本记录没有引用实际收集证据；假设、推理和缺证据不能因多人同意而变成事实。",style=MaterialTheme.typography.bodySmall)
        ids.forEach{id->val source=evidence.find{it.optString("id")==id};if(source!=null){val args=source.optJSONObject("arguments");val receipt=source.optJSONObject("receipt");Text("原证据 "+id.take(10)+" · "+source.optString("tool")+" · "+args?.optString("path").orEmpty()+" · 采集版本 "+receipt?.opt("version").toString(),style=MaterialTheme.typography.bodySmall);val content=receipt?.optString("content").orEmpty();if(content.isNotBlank())SelectionContainer{Text(content.take(800)+(if(content.length>800)"\n[预览截断；原工具消息保留其已收到回执]" else ""),style=MaterialTheme.typography.bodySmall)}}}
        Text("这些是实际调用和原作者回应的来源关系，不代表事实已验证；来源采集后的变化仍需重新核对。",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun TeamAssignmentEditor(definition: JSONObject, busy: Set<String>, lastResult: JSONObject?, error: String?, onCommand: (String, JSONObject) -> String, receipts: Map<String, JSONObject> = emptyMap(), close: () -> Unit) {
    val reviewed by remember(definition.optString("id")) { mutableStateOf(definition.toString()) }
    val members = teamRows(JSONObject(reviewed).optJSONArray("members"))
    var drafts by remember(reviewed) { mutableStateOf(members.map { member -> val deps = member.optJSONArray("dependsOn"); TeamAssignmentDraft(member.getString("id"), member.optString("assignment", member.optString("role")), member.optString("acceptance"), (0 until (deps?.length() ?: 0)).map { deps!!.getString(it) }.toSet()) }) }
    var problem by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastResult, receipts, pending) {
        val receipt = pending?.let { receipts[it] } ?: lastResult
        if (pending != null && receipt != null && receipt.optString("requestId") == pending && receipt.optString("command") == "savePlan" && receipt.optString("teamId") == definition.optString("id") && receipt.opt("ok") is Boolean) {
            if (receipt.optBoolean("ok")) close() else problem = receipt.optString("message", error ?: "分工未保存，请核对后重试")
            pending = null
        }
    }
    AlertDialog(onDismissRequest = { if (pending == null) close() }, containerColor = Color.White, title = { Text("分工与依赖") }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("仅修改下次任务的分工；当前任务保留原配置。前置成员真实完成后，依赖它的成员才开始。", style = MaterialTheme.typography.bodySmall)
            if (definition.toString() != reviewed) Text("团队配置已更新，请关闭后重新载入分工。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            drafts.forEachIndexed { index, draft ->
                fun update(next: TeamAssignmentDraft) { drafts = drafts.mapIndexed { n, old -> if (index == n) next else old } }
                Text(members.find { it.getString("id") == draft.memberId }?.optString("name").orEmpty(), fontWeight = FontWeight.Medium)
                OutlinedTextField(draft.assignment, { update(draft.copy(assignment = it)) }, label = { Text("本成员交付什么") }, minLines = 2, maxLines = 4, enabled = pending == null)
                OutlinedTextField(draft.acceptance, { update(draft.copy(acceptance = it)) }, label = { Text("如何核对完成（可选）") }, minLines = 1, maxLines = 3, enabled = pending == null)
                Text("依赖的前置成员", style = MaterialTheme.typography.bodySmall)
                members.filter { it.getString("id") != draft.memberId }.forEach { source -> val id = source.getString("id"); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(id in draft.dependsOn, { checked -> update(draft.copy(dependsOn = if (checked) draft.dependsOn + id else draft.dependsOn - id)) }, enabled = pending == null); Text(source.optString("name"), style = MaterialTheme.typography.bodyMedium) } }
                HorizontalDivider()
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { Button(onClick = { try { pending = onCommand("savePlan", reviewedTeamPlanPayload(definition, reviewed, drafts)) } catch (failure: Exception) { problem = failure.message } }, enabled = pending == null && "savePlan" !in busy && definition.toString() == reviewed) { Text(if (pending == null) "保存下次分工" else "保存中") } }, dismissButton = { TextButton(onClick = close, enabled = pending == null) { Text("取消") } })
}
