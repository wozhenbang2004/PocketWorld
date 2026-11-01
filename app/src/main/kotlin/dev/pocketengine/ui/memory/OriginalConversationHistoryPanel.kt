package dev.pocketengine.ui.memory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Owner-only original history. One native page at a time; no all-history body cache or model grant. */
@Composable
internal fun OriginalConversationHistoryPanel(
    responses: Map<String, JSONObject>,
    onRequest: (String, JSONObject) -> String,
    onOpenConversation: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onBack: () -> Unit,
    onStageRange: ((JSONObject) -> String)? = null
) {
    var pending by remember { mutableStateOf<OriginalHistoryRequest?>(null) }
    var overview by remember { mutableStateOf<JSONObject?>(null) }
    var review by remember { mutableStateOf<JSONObject?>(null) }
    var confirmMigration by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf<JSONObject?>(null) }
    var page by remember { mutableStateOf<JSONObject?>(null) }
    var conversation by remember { mutableStateOf<String?>(null) }
    var cursor by remember { mutableStateOf("") }
    var previousCursors by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun request(command: String, payload: JSONObject = JSONObject(), requestedCursor: String = cursor) {
        if (pending != null) return
        error = null
        pending = OriginalHistoryRequest(onRequest(command, payload), command, conversation, requestedCursor)
    }
    fun readPage(next: String = "") {
        val id = conversation
        request(if (id == null) "listOriginalConversations" else "readOriginalConversation",
            JSONObject().put("cursor", next).put("limit", if (id == null) 20 else 80).also { if (id != null) it.put("conversationId", id) }, next)
    }
    fun back() {
        if (selected != null) selected = null
        else if (conversation != null) { pending = null; conversation = null; cursor = ""; previousCursors = emptyList(); page = null; readPage() }
        else onBack()
    }
    BackHandler { back() }
    LaunchedEffect(Unit) { request("readOriginalConversationHistoryState") }
    val active = pending
    LaunchedEffect(active?.id, active?.let { responses[it.command]?.toString() }) {
        val expected = active ?: return@LaunchedEffect
        val envelope = TaskMemoryEvidenceGuard.exactEnvelope(expected.id, responses[expected.command]) ?: return@LaunchedEffect
        pending = null
        if (!envelope.getBoolean("ok")) { selected = null; error = envelope.optString("error", "原文读取或迁移未完成，请核对原任务。"); return@LaunchedEffect }
        try {
            val result = envelope.getJSONObject("result")
            when (expected.command) {
                "readOriginalConversationHistoryState" -> { overview = result; if (page == null) readPage() }
                "reviewOriginalConversationHistoryMigration" -> {
                    require(result.optString("status") == "review_required") { "原文权威已变化，请刷新状态。" }
                    review = JSONObject(result.toString()); selected = null
                }
                "activateOriginalConversationHistoryMigration", "cleanupOriginalConversationHistoryMigration" -> {
                    review = null; selected = null; page = null; cursor = ""; previousCursors = emptyList()
                    notice = if (result.optBoolean("legacyCleanupPending")) "原DB已激活；旧副本清理尚待核对，不会回退为读取来源。" else "原生操作已返回；旧丢失消息没有被补造。"
                    request("readOriginalConversationHistoryState")
                }
                "listOriginalConversations", "readOriginalConversation" -> {
                    if (expected.conversation == conversation) { page = JSONObject(result.toString()); cursor = expected.cursor; selected = null }
                }
                "deleteOriginalImportedRecord", "deleteOriginalMessage", "deleteOriginalConversation" -> {
                    selected = null; page = null
                    if (expected.command == "deleteOriginalConversation") { conversation = null; previousCursors = emptyList(); cursor = "" }
                    notice = "原生删除已返回；原文页与以后来源读取按删除状态重新核对。"; readPage(cursor)
                }
            }
        } catch (failure: Exception) { selected = null; error = failure.message ?: "原文页或原生回执无法核对。" }
    }
    val ready = pending == null
    val database = overview?.optBoolean("databaseAuthority") == true
    val selectedConversation = conversation
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
            Text("对话原文", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { pending = null; selected = null; onBack() }) { Text("返回任务证据") }
        }
        Text("这是原生读取的私人原文页，不是正式记忆、全部历史快照、旧授权或模型已采用证明。每次最多20个会话／80条消息；翻页不删除原文。", fontSize = 12.sp, lineHeight = 20.sp)
        if (!database) {
            Text("旧存储有20会话×80消息保留上限；已经丢失的正文、历史修订和时间不会补造。审核导入现存原件后，今后的UI窗口不再裁掉数据库原文。", fontSize = 13.sp, lineHeight = 21.sp)
            OutlinedButton(onClick = { request("reviewOriginalConversationHistoryMigration") }, enabled = ready) { Text("核对现存原件与启用范围") }
        } else {
            Text("当前原文权威：原TaskStore数据库。旧缺口仍保留为未知，不会从任务checkpoint复活消息。", fontSize = 12.sp, lineHeight = 20.sp)
            val authority = overview?.optJSONObject("authority")
            if (authority?.optBoolean("legacyCleanupPending") == true) OutlinedButton(onClick = {
                request("cleanupOriginalConversationHistoryMigration", JSONObject().put("sourceSha256", authority.optString("legacySourceSha256")))
            }, enabled = ready) { Text("按原SHA核对并清理旧副本") }
        }
        if (database) OwnerOriginalSearchPanel(responses, onRequest, onOpenConversation, onStageRange)
        overview?.optJSONObject("migration")?.optString("migrationTaskId")?.takeIf { it.isNotEmpty() }?.let { id ->
            TextButton(onClick = { onOpenTask(id) }) { Text("打开原迁移任务／不确定结果") }
        }
        review?.let { facts ->
            val absent = facts.optString("sourceCondition") == "absent"
            Text(if (absent) "当前未找到旧原文件；没有可声称导入的正文。" else "本次审核现存 ${facts.optInt("retainedConversations")} 会话、${facts.optInt("retainedMessages")} 条记录。\n原文件SHA256：${facts.optString("sourceSha256")}", fontSize = 12.sp, lineHeight = 20.sp)
            if (facts.optInt("retainedUnverifiedRecords") > 0) Text("旧格式没有可证明的正式来源关系。内部导入ID／recordVersion不等于历史真实revision；未知修订、时间与关联仍为未知。", fontSize = 12.sp, lineHeight = 20.sp)
            Button(onClick = { confirmMigration = true }, enabled = ready) { Text(if (absent) "确认仅启用今后留存…" else "确认导入并切换原文权威…") }
        }
        if (pending != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取或按已确认范围提交；离开界面不会证明事务尚未提交，结果请回原迁移任务核对。", fontSize = 12.sp) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        notice?.let { Text(it, fontSize = 12.sp) }
        if (selectedConversation != null) {
            Text(page?.optString("title").orEmpty().ifEmpty { "原会话" }, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { onOpenConversation(selectedConversation) }, enabled = ready) { Text("回到这个原会话") }
            if (database) TextButton(onClick = { confirmingDelete = JSONObject().put("wholeConversation", true) }, enabled = ready) { Text("删除这个会话的全部保留原文…") }
        }
        val visible = selected
        if (visible != null) {
            Text(if (visible.optBoolean("importAssignedIdentity")) "旧原件记录（来源关联未核实）" else "${visible.optString("role")} · 原生修订 ${visible.optLong("revision")}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (visible.optBoolean("importAssignedIdentity")) {
                Text("内部 ${visible.optString("recordId", visible.optString("id"))} · recordVersion ${visible.optLong("recordVersion")}\n历史revision：${if (visible.optBoolean("historicalRevisionKnown")) visible.opt("historicalRevision") else "未知"}；不是正式SourceRef。", fontSize = 11.sp)
                visible.optJSONObject("originalByteReference")?.let { Text("原SHA ${it.optString("sourceSha256")}\n原字节 ${it.optLong("offset")}＋${it.optLong("length")}（UTF-8），删除后原件可有明确缺口。", fontSize = 11.sp) }
            }
            Text(if (visible.isNull("at") || !visible.has("at")) "原时间未知" else "原记录时间字段：${visible.opt("at")}", fontSize = 11.sp)
            MemoryQuotedText(visible.optString("content"), visible.toString(), 16000)
            if (visible.optJSONArray("attachments")?.length()?.let { it > 0 } == true || visible.optJSONObject("unverifiedOriginalMetadata")?.length()?.let { it > 0 } == true) Text("原附件引用元数据已保留；此页不伪造附件可用性或模型已读取。", fontSize = 12.sp)
            if (database) TextButton(onClick = { confirmingDelete = JSONObject(visible.toString()) }, enabled = ready) { Text("删除这条原文…") }
            TextButton(onClick = { selected = null }) { Text("返回这一页") }
        } else {
            val rows = page?.optJSONArray(if (selectedConversation == null) "conversations" else "messages")
            if (rows != null) for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                OutlinedButton(onClick = {
                    if (selectedConversation == null) { conversation = row.getString("conversationId"); cursor = ""; previousCursors = emptyList(); page = null; readPage() }
                    else selected = JSONObject(row.toString())
                }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selectedConversation == null) row.optString("title").ifEmpty { "未命名原会话" }
                        else (if (row.optBoolean("importAssignedIdentity")) "旧原件 · 历史来源未知" else row.optString("role")) + "：" + row.optString("content").take(80))
                }
            }
            val paging = if (selectedConversation == null) page else page?.optJSONObject("messagePage")
            if (paging?.optJSONArray("gaps")?.length()?.let { it > 0 } == true) Text("本页有超预算或不可用原件，未以摘要替代；请核对原记录。", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (previousCursors.isNotEmpty()) TextButton(onClick = { val old = previousCursors.last(); previousCursors = previousCursors.dropLast(1); readPage(old) }, enabled = ready) { Text("上一页") }
                if (paging?.optBoolean("hasMore") == true) TextButton(onClick = { previousCursors = previousCursors + cursor; readPage(paging.optString("nextCursor")) }, enabled = ready) { Text(if (selectedConversation == null) "下一页会话" else "更早的原消息") }
                TextButton(onClick = { selected = null; readPage(cursor) }, enabled = ready) { Text("重新读取这一页") }
            }
            if (selectedConversation != null) TextButton(onClick = { back() }) { Text("返回会话列表") }
        }
    }
    if (confirmMigration) review?.let { facts ->
        val absent = facts.optString("sourceCondition") == "absent"
        AlertDialog(onDismissRequest = { confirmMigration = false }, title = { Text(if (absent) "仅启用今后留存？" else "迁入同一TaskStore？") },
            text = { Text(if (absent) "再次核对原文件仍不存在后，仅为今后新写入启用数据库历史。不声称导入或恢复任何旧消息。" else "仅导入本次SHA对应的现存原件与原生元数据。成功激活后清理旧文件；阶段中来源或删除状态变化会拒绝旧审核。已经丢失的历史、时间和来源关联不补造。") },
            confirmButton = { Button(onClick = { confirmMigration = false; selected = null; request("activateOriginalConversationHistoryMigration", JSONObject().put("reviewTaskId", facts.getString("migrationTaskId")).put("sourceSha256", facts.getString("sourceSha256")).put("confirmOriginalScope", true)) }) { Text(if (absent) "确认仅启用今后留存" else "确认导入并清理旧副本") } },
            dismissButton = { TextButton(onClick = { confirmMigration = false }) { Text("暂不切换") } })
    }
    confirmingDelete?.let { record ->
        AlertDialog(onDismissRequest = { confirmingDelete = null }, title = { Text("删除原文？") }, text = { Text("原生删除将同时撤销该原件的后续读取；不会用任务副本补回。正式记忆的遗忘与删除原话是不同操作。") },
            confirmButton = { TextButton(onClick = {
                confirmingDelete = null; selected = null
                val id = conversation ?: return@TextButton
                when {
                    record.optBoolean("wholeConversation") -> request("deleteOriginalConversation", JSONObject().put("conversationId", id).put("confirmDelete", true))
                    record.optBoolean("importAssignedIdentity") -> request("deleteOriginalImportedRecord", JSONObject().put("conversationId", id).put("recordId", record.getString("recordId")).put("recordVersion", record.getLong("recordVersion")).put("confirmDelete", true))
                    else -> request("deleteOriginalMessage", JSONObject().put("conversationId", id).put("messageId", record.getString("id")).put("revision", record.getLong("revision")).put("confirmDelete", true))
                }
            }) { Text("确认删除原文") } }, dismissButton = { TextButton(onClick = { confirmingDelete = null }) { Text("保留") } })
    }
}

private data class OriginalHistoryRequest(val id: String, val command: String, val conversation: String?, val cursor: String)
