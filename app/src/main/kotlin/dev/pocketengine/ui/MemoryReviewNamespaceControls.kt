package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Existing review page and existing inline TextButton/list hierarchy, not a new design surface. */
@Composable
internal fun MemoryReviewNamespacePage(
    snapshot: JSONObject,
    responses: Map<String, JSONObject>,
    pending: Set<String>,
    error: String?,
    onRequest: (String, JSONObject) -> String,
    onSelect: (JSONObject?) -> Unit,
    onOpenSource: (JSONObject) -> Unit,
    onBack: () -> Unit,
    onDismissSource: () -> Unit,
    onProjectArchive: () -> Unit
) {
    val status = snapshot.optString("namespaceStatus")
    val scopeKey = status + ":" + snapshot.optJSONObject("scope")?.optString("spaceId").orEmpty()
    var choosing by remember(scopeKey) { mutableStateOf(false) }
    var choosingFile by remember(scopeKey) { mutableStateOf(false) }
    var choicesRead by remember(scopeKey) { mutableStateOf<String?>(null) }
    var filesRead by remember(scopeKey) { mutableStateOf<String?>(null) }
    var proposalRead by remember(scopeKey) { mutableStateOf<String?>(null) }
    var localError by remember(scopeKey) { mutableStateOf<String?>(null) }
    val readOnly = setOf("snapshot", "list", "search", "get", "listCandidates", "getCandidate", "candidateCapacity", "listCandidateHistory", "cognitionProjection", "searchHistory", "readHistoryRecord", "getParentRecord", "readAssertionEvidence", "openSource", "namespaceChoices", "projectFileChoices")
    val changing = status == "changing" || "selectNamespace" in pending
    val busy = changing || pending.any { it !in readOnly }
    fun request(command: String, payload: JSONObject = JSONObject()): String? = try { localError = null; onRequest(command, payload) } catch (failure: Exception) { localError = failure.message ?: "原生请求未开始"; null }
    val choices = MemoryLearningReviewGuard.result(choicesRead, responses["namespaceChoices"])?.optJSONArray("choices")
    val files = MemoryLearningReviewGuard.result(filesRead, responses["projectFileChoices"])?.optJSONArray("files")
    val proposed = MemoryLearningReviewGuard.result(proposalRead, responses["proposeProjectFile"])
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("范围：" + when (status) { "private" -> "主人全局 · 私人"; "project" -> snapshot.optString("scopeName", "项目"); "changing" -> "正在核对"; else -> "尚未确认" }, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(top = 14.dp))
            TextButton(onClick = { choosing = !choosing; if (choosing) choicesRead = request("namespaceChoices") }, enabled = !busy) { Text("更换范围") }
        }
        if (choosing) Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            TextButton(onClick = { onSelect(null) }, enabled = !busy) { Text("主人全局 · 私人记忆") }
            if ("namespaceChoices" in pending) Text("正在读取原项目目录…", color = SecondaryInk, fontSize = 12.sp)
            choices?.let { rows ->
                if (rows.length() == 0) Text("还没有可选择的本机项目副本。", color = SecondaryInk, fontSize = 12.sp)
                for (i in 0 until rows.length()) {
                    val choice = rows.getJSONObject(i)
                    if (choice.optBoolean("available")) TextButton(onClick = { onSelect(choice) }, enabled = !busy) { Text("项目：" + choice.optString("name", "未命名项目")) }
                    else Text(choice.optString("name", "项目") + "：" + choice.optString("message", "当前未授权此范围"), color = SecondaryInk, fontSize = 12.sp)
                }
            }
            MemoryLearningReviewGuard.exactEnvelope(choicesRead, responses["namespaceChoices"])?.takeIf { !it.optBoolean("ok") }?.let { Text(it.optString("error", "原项目目录读取失败"), color = Danger, fontSize = 12.sp) }
            Text("项目显选不授予团队/群记忆权限；关闭页面后须重新选择。", color = SecondaryInk, fontSize = 12.sp)
        }
        if (status == "project") Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            TextButton(onClick = { try { onProjectArchive() } catch (failure: Exception) { localError = failure.message ?: "原项目备份尚未开始" } }, enabled = !busy) { Text("备份此项目与其记忆…") }
            TextButton(onClick = { choosingFile = !choosingFile; if (choosingFile) filesRead = request("projectFileChoices") }, enabled = !busy) { Text("从此项目原文件建立待审摘录…") }
            if (choosingFile) Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                Text("显选当前正式文件的前4000个UTF-16字符；不调用模型、不自动采用。不读取未提交副本。", color = SecondaryInk, fontSize = 12.sp)
                if ("projectFileChoices" in pending) Text("正在核对原文件版本…", color = SecondaryInk, fontSize = 12.sp)
                files?.let { rows ->
                    if (rows.length() == 0) Text("此项目尚无正式文件。", color = SecondaryInk, fontSize = 12.sp)
                    for (i in 0 until rows.length()) {
                        val file = rows.getJSONObject(i)
                        val readable = !file.optBoolean("tombstone") && file.optString("kind", "text") == "text" && file.optLong("sizeBytes", 0) in 1..1_048_576
                        TextButton(onClick = { proposalRead = request("proposeProjectFile", JSONObject().put("path", file.getString("path")).put("version", file.getInt("version")).put("revisionId", file.getString("revisionId")).put("sha256", file.getString("sha256")).put("offset", 0).put("chars", 4000)) }, enabled = readable && !busy) { Text(file.optString("path") + " · v" + file.optInt("version") + if (readable) " · 建立待审摘录" else " · 此版本不支持文字摘录") }
                    }
                }
                MemoryLearningReviewGuard.exactEnvelope(filesRead, responses["projectFileChoices"])?.takeIf { !it.optBoolean("ok") }?.let { Text(it.optString("error", "原文件读取失败"), color = Danger, fontSize = 12.sp) }
                MemoryLearningReviewGuard.exactEnvelope(proposalRead, responses["proposeProjectFile"])?.let { envelope ->
                    if (!envelope.optBoolean("ok")) Text(envelope.optString("error", "摘录没有确定保存回执"), color = Danger, fontSize = 12.sp)
                    else if (proposed?.optJSONObject("candidate") != null) Text("摘录已进入下方原生审核，仍需你明确接受。", color = SecondaryInk, fontSize = 12.sp)
                }
            }
            Text(if (snapshot.optBoolean("taskContextSupported")) "项目 Agent 开始任务前可另行显选只读取本项目记忆；当前管理范围不是执行授权。模型归纳、团队/群自动学习仍不支持。" else "此宿主尚未挂载项目任务记忆；当前只作主人审核与管理。模型归纳、团队/群自动学习仍不支持。", color = SecondaryInk, fontSize = 12.sp)
        }
        localError?.let { Text(it, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp)) }
        if (status !in setOf("private", "project")) Column(Modifier.padding(18.dp)) {
            Text(if (changing) "正在核对原生范围，尚未读取记忆。" else snapshot.optString("message", error ?: "记忆范围尚未确认，请刷新或明确重新选择；未显示为空记忆库。"), color = if (changing) SecondaryInk else Danger, fontSize = 13.sp)
            if (!changing) TextButton(onClick = { request("snapshot") }, enabled = !busy) { Text("重试当前范围") }
            TextButton(onClick = onBack) { Text("返回") }
        } else key(scopeKey) {
            Box(Modifier.weight(1f).fillMaxWidth()) { MemoryLearningReviewPage(snapshot, responses, pending, "snapshot" in pending, error, onRequest, onOpenSource, onBack, onDismissSource) }
        }
    }
}
