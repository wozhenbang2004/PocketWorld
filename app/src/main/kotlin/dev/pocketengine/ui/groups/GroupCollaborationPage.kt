package dev.pocketengine.ui.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private data class GroupPending(val id: String, val command: String, val target: String, val payload: JSONObject)
private data class GroupReview(val command: String, val group: JSONObject, val draft: JSONObject, val summary: String)

/** Native owner-only route. Requests use the existing module; review/open callbacks re-read in the host. */
@Composable
fun GroupCollaborationPage(
    snapshot: JSONObject,
    responses: Map<String, JSONObject>,
    onRequest: (command: String, payload: JSONObject) -> String,
    onOpenTask: (taskId: String) -> Unit,
    onBack: () -> Unit,
    taskPresentations: Map<String, JSONObject> = emptyMap(),
    onReviewTask: ((GroupTaskReviewSelection) -> Unit)? = null,
    onOpenArtifact: ((GroupArtifactSelection) -> Unit)? = null
) {
    var remoteInputRequest by remember { mutableStateOf<String?>(null) }
    remoteInputRequest?.let { original -> GroupRemoteInputsPanel(original,responses,onRequest,{remoteInputRequest=null});return }
    var completedDelivery by remember { mutableStateOf(false) }
    if (completedDelivery) {
        DisposableEffect(Unit) { onDispose { try { onRequest("completion.leave", JSONObject()) } catch (_: Exception) { /* Parent lifecycle already revokes it. */ } } }
        CompletionDeliveryPanel(responses, onRequest, { completedDelivery = false }); return
    }
    var manualMaterials by remember { mutableStateOf(false) }
    if (manualMaterials) {
        DisposableEffect(Unit) { onDispose { try { onRequest("material.leave", JSONObject()) } catch (_: Exception) { /* Parent window disposal already revokes authority. */ } } }
        ReviewedMaterialPanel(responses, onRequest, { manualMaterials = false }); return
    }
    var data by remember { mutableStateOf(JSONObject()) }
    var pending by remember { mutableStateOf<GroupPending?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var selectedTask by remember { mutableStateOf<String?>(null) }
    var taskResult by remember { mutableStateOf<JSONObject?>(null) }
    var taskReadError by remember { mutableStateOf(false) }
    var deliveryUpdates by remember { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var tab by remember { mutableStateOf("tasks") }
    var creating by remember { mutableStateOf(false) }
    var memberEditor by remember { mutableStateOf<JSONObject?>(null) }
    var policyEditor by remember { mutableStateOf(false) }
    var editorGroup by remember { mutableStateOf<JSONObject?>(null) }
    var discardEditor by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<GroupReview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var refreshNeeded by remember { mutableStateOf(false) }
    val groups = GroupUiState.rows(data.optJSONArray("groups"))
    val group = groups.firstOrNull { it.optString("id") == selected }
    val busy = pending != null

    fun request(command: String, payload: JSONObject = JSONObject(), target: String = "", clearFeedback: Boolean = true) {
        if (pending != null) return
        try {
            val id = onRequest(command, JSONObject(payload.toString()))
            check(id.isNotBlank()) { "原生请求没有返回标识" }
            pending = GroupPending(id, command, target, JSONObject(payload.toString()))
            if (clearFeedback) { error = null; notice = null }
        } catch (failure: Exception) {
            error = failure.message ?: "请求未发送"
            if (command == "task.inspect") taskReadError = true
        }
    }
    fun back() {
        when {
            selectedTask != null -> { selectedTask = null; taskResult = null; taskReadError = false }
            creating || memberEditor != null || policyEditor -> discardEditor = true
            selected != null -> selected = null
            else -> onBack()
        }
    }
    BackHandler(enabled = selected != null || selectedTask != null || creating || memberEditor != null || policyEditor) { back() }
    LaunchedEffect(snapshot) {
        if (GroupUiState.newerSnapshot(data, snapshot)) {
            data = JSONObject(snapshot.toString()); deliveryUpdates = emptyMap()
        }
    }
    LaunchedEffect(Unit) { request("snapshot") }
    LaunchedEffect(responses, pending) {
        val active = pending ?: return@LaunchedEffect
        val envelope = GroupUiState.envelope(active.id, responses[active.id]) ?: return@LaunchedEffect
        pending = null
        if (!envelope.optBoolean("ok")) {
            error = envelope.optString("message", "操作失败，输入已保留")
            if (active.command == "task.inspect") taskReadError = true
            if (active.command.startsWith("delivery.")) refreshNeeded = true
            return@LaunchedEffect
        }
        try {
            val result = GroupUiState.result(active.id, envelope) ?: error("原生结果缺少正文")
            when (active.command) {
                "snapshot" -> {
                    check(GroupUiState.newerSnapshot(data, result)) { "收到过期群空间快照，请刷新" }
                    data = result; deliveryUpdates = emptyMap()
                }
                "task.inspect", "task.cancel" -> {
                    val exact = GroupUiState.exactTask(active.target, result) ?: error("任务详情与所选对象不一致")
                    if (selectedTask == active.target) {
                        check(GroupUiState.newerTask(taskResult, exact)) { "收到过期任务回执，请重新查询" }
                        taskResult = exact; taskReadError = false
                    }
                    if (active.command == "task.cancel") notice = "已读取宿主取消回执；以原任务状态为准"
                    refreshNeeded = true
                }
                "delivery.send", "delivery.inspect" -> {
                    val ref = GroupUiState.rows(data.optJSONArray("requests")).firstOrNull { it.optString("requestId") == active.target }
                        ?: error("投递所属原任务已变化，请刷新")
                    val expectedId = if (active.command == "delivery.inspect") active.payload.getString("deliveryId")
                        else GroupUiState.deliveryId(active.target, active.payload.getString("outputId"), ref.getString("audience"))
                    val exact = GroupUiState.exactDelivery(active.target, ref.getString("taskId"), expectedId, result)
                    deliveryUpdates = deliveryUpdates + (expectedId to exact)
                    notice = GroupUiState.deliveryLabel(exact.optString("state")); refreshNeeded = true
                }
                else -> {
                    creating = false; memberEditor = null; policyEditor = false; review = null
                    notice = "群设置已保存"; refreshNeeded = true
                    if (active.command == "group.create") selected = result.getString("id")
                }
            }
        } catch (failure: Exception) {
            error = failure.message ?: "原生返回不匹配，请刷新"
            if (active.command == "task.inspect") taskReadError = true
        }
    }
    LaunchedEffect(refreshNeeded, busy) {
        if (refreshNeeded && !busy) { refreshNeeded = false; request("snapshot", clearFeedback = false) }
    }

    Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::back) { Text("返回") }
                Text(group?.optString("name") ?: "群协作", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { request("snapshot") }, enabled = !busy) { Text("刷新") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (notice != null) Text(notice!!, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            when {
                creating -> GroupCreateForm(GroupUiState.rows(data.optJSONArray("nativeConnections")), busy, onCreate = { request("group.create", it) })
                selectedTask != null && group != null -> {
                    val reference = GroupUiState.rows(data.optJSONArray("requests")).firstOrNull { it.optString("requestId") == selectedTask }
                    val storedDeliveries = GroupUiState.rows(data.optJSONArray("deliveries"))
                    val currentDeliveries = storedDeliveries.map { row -> deliveryUpdates[row.optString("deliveryId")] ?: row } +
                        deliveryUpdates.values.filter { update -> storedDeliveries.none { it.opt("deliveryId") == update.opt("deliveryId") } }
                    Column(Modifier.fillMaxSize()) {
                    OutlinedButton(onClick={remoteInputRequest=selectedTask},enabled=!busy&&reference!=null,modifier=Modifier.padding(horizontal=16.dp)){Text("显选原群文件到电脑分析并回原群")}
                    GroupTaskJourney(group.optString("name"), reference, taskResult, taskPresentations[selectedTask], currentDeliveries, busy, taskReadError,
                        onInspect = { request("task.inspect", JSONObject().put("requestId", selectedTask), selectedTask!!) },
                        onCancel = {
                            try { request("task.cancel", GroupUiState.cancellation(selectedTask!!, taskResult), selectedTask!!) }
                            catch (failure: Exception) { error = failure.message }
                        },
                        onOpen = { reference?.optString("taskId")?.takeIf { it.isNotBlank() }?.let(onOpenTask) },
                        onReview = { if (onReviewTask == null) onOpenTask(it.taskId) else onReviewTask(it) },
                        onSend = { request("delivery.send", JSONObject().put("requestId", selectedTask).put("outputId", it), selectedTask!!) },
                        onInspectDelivery = { request("delivery.inspect", JSONObject().put("deliveryId", it), selectedTask!!) },
                        onOpenArtifact = onOpenArtifact)
                    }
                }
                memberEditor != null && group != null -> GroupMemberEditor(editorGroup ?: group, memberEditor!!, busy) { draft ->
                    review = GroupReview("member.set", JSONObject((editorGroup ?: group).toString()), draft,
                        "${draft.optString("displayName")} · ${roleLabel(draft.optString("role"))}\n稳定身份：${draft.optString("senderId")}\n${if (draft.optBoolean("enabled")) "允许使用群服务" else "停用该成员；其旧任务授权失效"}")
                }
                policyEditor && group != null -> GroupPolicyEditor(editorGroup ?: group, data.optJSONObject("nativeResources") ?: JSONObject(), busy) { draft ->
                    review = GroupReview("policy.set", JSONObject((editorGroup ?: group).toString()), draft,
                        "${if (draft.optBoolean("enabled")) "启用" else "关闭"}群服务\n已选 ${draft.getJSONArray("grants").length()} 项能力；有效期与资源见表单。\n保存会撤销此群旧任务的继续执行与结果投递授权。已完成的外部效果不会回滚。")
                }
                group == null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("选择要开放的共享空间", style = MaterialTheme.typography.titleMedium)
                        Text("每个群单独管理成员、能力与额度。群成员不能取得私人手机、浏览器、记忆或 SSH 权限。", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                        Button(onClick = { creating = true }, enabled = !busy) { Text("新建群空间") }
                        OutlinedButton(onClick = { manualMaterials = true }, enabled = !busy) { Text("主人审阅外部材料并人工交还") }
                        OutlinedButton(onClick = { completedDelivery = true }, enabled = !busy) { Text("把已核对任务结果发到主人私聊") }
                    }
                    if (GroupUiState.revision(data) < 0) item { Text("尚未读取群空间，请刷新。") }
                    else if (groups.isEmpty()) item { Text("还没有群空间。创建后默认关闭，添加成员并明确授权后再启用。", modifier = Modifier.padding(vertical = 20.dp)) }
                    items(groups, key = { it.getString("id") }) { row ->
                        Column(Modifier.fillMaxWidth().clickable { selected = row.getString("id"); tab = "tasks" }.padding(vertical = 14.dp)) {
                            Text(row.optString("name"), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${if (row.optBoolean("enabled")) "已启用" else "已关闭"} · ${row.optJSONArray("members")?.length() ?: 0} 位已配置成员 · ${row.optString("provider")}", style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider()
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    val capability = data.optJSONObject("capabilities")
                    if (capability?.optBoolean("groupTasksReady") != true) Text("任务宿主或认证群通道尚未接入；当前可管理群设置。", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((key, label) in listOf("tasks" to "任务", "members" to "成员", "policy" to "权限与额度")) FilterChip(selected = tab == key, onClick = { tab = key }, label = { Text(label) })
                    }
                    when (tab) {
                        "tasks" -> {
                            val refs = GroupUiState.rows(data.optJSONArray("requests")).filter { it.optString("groupId") == selected }
                            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                item { Text("${if (group.optBoolean("enabled")) "群服务已启用" else "群服务已关闭"} · ${if (group.optString("visibility") == "members") "可选择群成员查看进度" else "任务仅发起人可见"}", style = MaterialTheme.typography.bodyMedium) }
                                if (refs.isEmpty()) item { Text(if (group.optBoolean("enabled")) "尚无群任务。在已连接的原群使用 /task 目标。允许成员查看时，可明确使用 /task --share 目标，取得群内状态回执；默认任务状态留在本机。" else "尚无群任务。先添加成员并在权限与额度中启用群服务，再到原群向机器人提出任务。", modifier = Modifier.padding(vertical = 20.dp)) }
                                if (refs.isEmpty()) item { Text("原群使用 /status 任务编号 查询，或 /cancel 任务编号 请求停止。QQ 回复窗口关闭后，可发新的 /status 取回结果；未知投递只核对原编号，不自动重发。", style = MaterialTheme.typography.bodySmall) }
                                items(refs.asReversed(), key = { it.getString("requestId") }) { ref ->
                                    val current = GroupTaskPresentation.read(ref, taskPresentations[ref.optString("requestId")])
                                    Column(Modifier.fillMaxWidth().clickable {
                                        selectedTask = ref.getString("requestId"); taskResult = null; taskReadError = false
                                        request("task.inspect", JSONObject().put("requestId", selectedTask), selectedTask!!)
                                    }.padding(vertical = 10.dp)) {
                                        Text(ref.optString("goal"), style = MaterialTheme.typography.bodyLarge, maxLines = 3)
                                        Text(current?.let { GroupUiState.taskLabel(it.status) } ?: if (ref.optString("admission") in setOf("unknown", "dispatching")) "提交待核对" else "打开读取实际任务状态", style = MaterialTheme.typography.bodyMedium)
                                        if (current?.approvalState in setOf("required", "pending", "denied", "revoked")) Text(GroupUiState.approvalLabel(current!!.approvalState), style = MaterialTheme.typography.bodySmall)
                                        Text("${capabilityLabel(ref.optString("capability"))} · ${if (ref.optString("audience") == "group") "结果回原群" else "结果仅原发起人"}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                        "members" -> {
                            val members = GroupUiState.rows(group.optJSONArray("members"))
                            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                item {
                                    Text("手机主人保留能力与额度决定。群管理员只能管理普通成员。", style = MaterialTheme.typography.bodyMedium)
                                    OutlinedButton(onClick = { editorGroup = JSONObject(group.toString()); memberEditor = JSONObject() }, enabled = !busy) { Text("添加成员") }
                                }
                                items(members, key = { it.getString("senderId") }) { member ->
                                    Column(Modifier.fillMaxWidth().clickable { editorGroup = JSONObject(group.toString()); memberEditor = JSONObject(member.toString()) }.padding(vertical = 10.dp)) {
                                        Text(member.optString("displayName"), style = MaterialTheme.typography.titleMedium)
                                        Text("${roleLabel(member.optString("role"))} · ${if (member.optBoolean("enabled")) "可用" else "已停用"}", style = MaterialTheme.typography.bodyMedium)
                                        Text(member.optString("senderId"), style = MaterialTheme.typography.bodySmall)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                        else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${group.optString("provider")} · 机器人 ${group.optString("accountId")}\n群稳定身份 ${group.optString("roomId")}", style = MaterialTheme.typography.bodyMedium)
                            if (group.optString("conversationKind") == "qq_guild_channel") Text("QQ频道场景 · guild_id ${group.optString("conversationParentId")} · 不继承普通QQ群权限", style = MaterialTheme.typography.bodyMedium)
                            Text("${if (group.optBoolean("enabled")) "已启用" else "已关闭"} · 私人设备权限不向群开放", style = MaterialTheme.typography.titleMedium)
                            for (grant in GroupUiState.rows(group.optJSONArray("grants"))) Text("${capabilityLabel(grant.optString("capability"))} · ${if (grant.optBoolean("allowGroupAudience")) "可回群" else "未开放群受众"}\n${grant.optString("resource")} · 到期 ${java.util.Date(grant.optLong("expiresAt"))}", style = MaterialTheme.typography.bodyMedium)
                            val quota = group.getJSONObject("quota"); val usage = group.optJSONObject("usage")
                            Text("每日任务 ${usage?.optInt("dailyTasks") ?: 0}/${quota.optInt("dailyTasks")} · 并发 ${usage?.optInt("activeTasks") ?: 0}/${quota.optInt("maxConcurrent")}\n模型已预留 ${usage?.optLong("reservedTokens") ?: 0}/${quota.optLong("dailyTokens")} tokens · 媒体 ${usage?.optInt("dailyMedia") ?: 0}/${quota.optInt("dailyMedia")}", style = MaterialTheme.typography.bodyMedium)
                            Text("额度按 UTC 日预留；失败和结果未知的尝试不自动返还当日额度。", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { editorGroup = JSONObject(group.toString()); policyEditor = true }, enabled = !busy) { Text("编辑权限与额度") }
                        }
                    }
                }
            }
        }
    }
    review?.let { frozen ->
        val current = groups.firstOrNull { it.optString("id") == frozen.group.optString("id") }
        AlertDialog(onDismissRequest = { if (!busy) review = null }, title = { Text("核对 ${frozen.group.optString("name")}") },
            text = { Text(frozen.summary + (if (!GroupUiState.sameGroup(frozen.group, current)) "\n群设置已变化，请取消后刷新。" else "") + (error?.let { "\n$it" } ?: "")) },
            confirmButton = { TextButton(enabled = !busy && GroupUiState.sameGroup(frozen.group, current), onClick = {
                try { request(frozen.command, GroupUiState.reviewedPayload(frozen.group, current, frozen.draft)) }
                catch (failure: Exception) { error = failure.message }
            }) { Text("保存") } }, dismissButton = { TextButton(onClick = { review = null }, enabled = !busy) { Text("继续编辑") } })
    }
    if (discardEditor) AlertDialog(onDismissRequest = { discardEditor = false }, title = { Text("离开未保存的设置？") },
        text = { Text("当前输入尚未保存。") }, confirmButton = { TextButton(enabled = !busy, onClick = {
            discardEditor = false; creating = false; memberEditor = null; policyEditor = false; editorGroup = null
        }) { Text("放弃输入") } }, dismissButton = { TextButton(onClick = { discardEditor = false }) { Text("继续编辑") } })
}

@Composable
private fun GroupCreateForm(connections: List<JSONObject>, busy: Boolean, onCreate: (JSONObject) -> Unit) {
    var name by remember { mutableStateOf("") }; var provider by remember { mutableStateOf("qq") }
    var account by remember { mutableStateOf("") }; var room by remember { mutableStateOf("") }; var guild by remember { mutableStateOf(false) }; var guildId by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("新建群空间", style = MaterialTheme.typography.titleLarge)
        Text("使用官方通道核实的机器人与群稳定编号。昵称、转发消息和群管理员声明不能用于认证。", style = MaterialTheme.typography.bodyMedium)
        GroupField("群名称", name, { name = it })
        Text("选择本机已有连接", style = MaterialTheme.typography.titleMedium)
        if (connections.isEmpty()) Text("先在消息渠道配置 QQ 官方机器人或明确可信桥接。微信 iLink 目前仅支持私聊，不作为群连接。", style = MaterialTheme.typography.bodyMedium)
        connections.forEach { connection -> GroupRadio(account == connection.optString("id"), connection.optString("name").ifBlank { connection.optString("id") } + " · " + if (connection.optString("provider") == "qq") "QQ 官方机器人" else "明确可信桥接") { account = connection.getString("id"); provider = connection.getString("provider") } }
        if (provider == "qq") {
            GroupRadio(!guild, "QQ群（group_openid）") { guild = false }
            GroupRadio(guild, "QQ频道（guild_id / channel_id，独立授权）") { guild = true }
            if (guild) {
                GroupField("QQ原生 guild_id", guildId, { guildId = it })
                Text("须先在该连接启用频道事件并核对官方资格。频道不继承同编号QQ群的成员或公开结果授权。", style = MaterialTheme.typography.bodyMedium)
            }
        }
        GroupField(if (provider == "qq" && guild) "QQ原生 channel_id" else "官方群/桥接协议的稳定群编号", room, { room = it })
        Button(onClick = { val scope = JSONObject().put("name", name).put("provider", provider).put("accountId", account).put("roomId", room); if (provider == "qq" && guild) scope.put("conversationKind", "qq_guild_channel").put("conversationParentId", guildId); onCreate(scope) }, enabled = !busy && listOf(name, provider, account, room).all { it.isNotBlank() } && (!(provider == "qq" && guild) || guildId.isNotBlank() && connections.any { it.optString("id") == account && it.optBoolean("qqGuildEnabled") })) { Text("创建为关闭状态") }
    }
}

@Composable
private fun GroupMemberEditor(group: JSONObject, previous: JSONObject, busy: Boolean, onReview: (JSONObject) -> Unit) {
    var sender by remember(group.optString("id"), previous) { mutableStateOf(previous.optString("senderId")) }
    var name by remember(previous) { mutableStateOf(previous.optString("displayName")) }
    var role by remember(previous) { mutableStateOf(previous.optString("role", "member")) }
    var enabled by remember(previous) { mutableStateOf(previous.optBoolean("enabled", true)) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${group.optString("name")} · 成员", style = MaterialTheme.typography.titleLarge)
        GroupField("成员稳定身份", sender, { sender = it }, enabled = previous.optString("senderId").isBlank())
        GroupField("显示名称", name, { name = it })
        for (option in listOf("admin", "member", "viewer")) GroupRadio(role == option, roleLabel(option)) { role = option }
        GroupCheck(enabled, "允许使用此群服务") { enabled = it }
        Text("管理员可以管理普通成员；成员可按授权创建任务；只读成员只能查看允许公开的任务。停用或变更角色会使该成员旧任务授权失效。", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { onReview(JSONObject().put("senderId", sender).put("displayName", name).put("role", role).put("enabled", enabled)) }, enabled = !busy && sender.isNotBlank() && name.isNotBlank()) { Text("核对成员设置") }
    }
}

@Composable
private fun GroupPolicyEditor(group: JSONObject, nativeResources: JSONObject, busy: Boolean, onReview: (JSONObject) -> Unit) {
    val existing = GroupUiState.rows(group.optJSONArray("grants"))
    var enabled by remember(group) { mutableStateOf(group.optBoolean("enabled")) }
    var visibility by remember(group) { mutableStateOf(group.optString("visibility")) }
    var selected by remember(group) { mutableStateOf(existing.map { it.optString("capability") }.toSet()) }
    var publicResults by remember(group) { mutableStateOf(existing.filter { it.optBoolean("allowGroupAudience") }.map { it.optString("capability") }.toSet()) }
    var resources by remember(group) { mutableStateOf(existing.associate { it.optString("capability") to it.optString("resource") }) }
    var hours by remember(group) { mutableStateOf("24") }
    var values by remember(group) { mutableStateOf(quotaFields.associate { (key, _) -> key to group.getJSONObject("quota").optLong(key).toString() }) }
    var localError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${group.optString("name")} · 权限与额度", style = MaterialTheme.typography.titleLarge)
        GroupCheck(enabled, "启用此群服务") { enabled = it }
        Text("任务进度可见范围", style = MaterialTheme.typography.titleMedium)
        GroupRadio(visibility == "requester", "仅发起人") { visibility = "requester" }
        GroupRadio(visibility == "members", "允许任务选择群成员可见") { visibility = "members" }
        Text("结果受众由每项能力另行控制。当前 QQ/可信桥接只支持原群被动回复；未开放群受众的能力不会伪装为可私聊投递。", style = MaterialTheme.typography.bodySmall)
        Text("共享能力", style = MaterialTheme.typography.titleMedium)
        for (cap in capabilities) {
            val available = nativeResources.optBoolean(cap)
            GroupCheck(cap in selected, capabilityLabel(cap) + if (available) "" else " · 当前未接入/不可用", available || cap in selected) {
                selected = if (it && available) selected + cap else selected - cap
                if (it && available && cap.startsWith("media.")) resources = resources + (cap to nativeResources.optString("mediaResource"))
            }
            if (cap in selected) {
                GroupCheck(cap in publicResults, "允许将该能力的结果发到群里") { publicResults = if (it) publicResults + cap else publicResults - cap }
                if (cap.startsWith("media.")) {
                    Text("当前已配置服务：" + nativeResources.optString("mediaProvider") + " · " + nativeResources.optString(if (cap == "media.image") "imageModel" else "ttsModel"), style = MaterialTheme.typography.bodySmall)
                    Text(nativeResources.optString("mediaBaseUrl"), style = MaterialTheme.typography.bodySmall)
                    if (resources[cap] != nativeResources.optString("mediaResource")) Text("原服务配置已变化，旧授权不能迁移到新地址或模型。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { resources = resources + (cap to nativeResources.optString("mediaResource")) }, enabled = available && !busy) { Text("明确选择当前媒体服务") }
                }
            }
        }
        if (selected.any { it.startsWith("media.") }) Text("媒体流程至少需要 32000 本地保守预留额度（不是账单 token）。你必须明确核对单任务和全群日上限；不会自动提高已保存额度。", style = MaterialTheme.typography.bodySmall)
        GroupField("本次所选能力授权时长（小时，1–744）", hours, { hours = it })
        Text("任务与媒体限额", style = MaterialTheme.typography.titleMedium)
        for ((key, label) in quotaFields) GroupField(label, values[key] ?: "", { values = values + (key to it) })
        Text("模型额度是跨续跑累计的本地保守资源预留，不是服务商账单 token；媒体按一次生成预留。调整设置会使旧群任务授权失效，无法撤回已完成操作。", style = MaterialTheme.typography.bodyMedium)
        localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = !busy, onClick = {
            try {
                val duration = hours.toLong().also { require(it in 1..744) { "授权时长需为 1–744 小时" } }
                val grants = JSONArray()
                for (cap in capabilities.filter { it in selected }) {
                    val resource = when { cap.startsWith("public.") -> "public"; cap == "group.files" -> group.getString("spaceId"); else -> resources[cap]?.trim().orEmpty().also { require(it.isNotBlank()) { "请指定媒体服务编号" } } }
                    grants.put(JSONObject().put("capability", cap).put("resource", resource).put("allowGroupAudience", cap in publicResults).put("expiresAt", System.currentTimeMillis() + duration * 3_600_000L))
                }
                val quota = JSONObject(); for ((key, _) in quotaFields) quota.put(key, values[key]?.toLongOrNull() ?: error("额度必须为整数"))
                require(!enabled || selected.isNotEmpty()) { "启用前请选择共享能力" }
                require(!enabled || selected.all { nativeResources.optBoolean(it) }) { "有能力尚未接入，请取消该项或先配置可用服务" }
                require(!enabled || selected.none { it.startsWith("media.") } || (quota.optLong("maxTokensPerTask") >= 32000 && quota.optLong("dailyTokens") >= 32000)) { "媒体流程至少预留32000，请明确调整已保存的单任务/日额度或取消媒体能力；不会自动提高限额" }
                require(!enabled || selected.filter { it.startsWith("media.") }.all { resources[it] == nativeResources.optString("mediaResource") }) { "媒体配置已变化，请重新核对并明确选择当前服务" }
                localError = null; onReview(JSONObject().put("enabled", enabled).put("visibility", visibility).put("grants", grants).put("quota", quota))
            } catch (failure: Exception) { localError = failure.message ?: "请检查设置" }
        }) { Text("核对并保存") }
    }
}

@Composable private fun GroupField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true) =
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), enabled = enabled, singleLine = true)

@Composable private fun GroupCheck(value: Boolean, label: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = value, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = value, onCheckedChange = null, enabled = enabled); Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
@Composable private fun GroupRadio(value: Boolean, label: String, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = value, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = value, onClick = null); Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
private val capabilities = listOf("public.answer", "public.search", "group.files", "media.image", "media.audio", "media.video")
private val quotaFields = listOf("dailyTasks" to "每日全群任务数", "memberDailyTasks" to "每日每位成员任务数", "dailyTokens" to "每日模型额度（tokens）",
    "maxTokensPerTask" to "单条任务模型上限（tokens）", "maxConcurrent" to "同时执行任务数（1–8）", "dailyMedia" to "每日全群媒体生成数",
    "memberDailyMedia" to "每日每位成员媒体生成数", "maxMediaBytes" to "单个媒体大小上限（字节，最多 20971520）")
private fun roleLabel(role: String) = when (role) { "admin" -> "群管理员"; "viewer" -> "只读成员"; else -> "成员" }
private fun capabilityLabel(capability: String) = when (capability) { "public.answer" -> "公开资料问答"; "public.search" -> "公开网络检索"; "group.files" -> "本群文件处理"; "media.image" -> "生成图片"; "media.audio" -> "生成音频"; "media.video" -> "生成视频"; else -> capability }
