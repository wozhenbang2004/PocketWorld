package dev.pocketengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketengine.features.memory.SourceRef
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** The original native candidate review, with no model identity creation or new source grant. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryAssertionReviewDialog(
    frozen: JSONObject,
    current: JSONObject?,
    responses: Map<String, JSONObject>,
    pendingCommands: Set<String>,
    active: Boolean,
    onRequest: (String, JSONObject) -> String,
    onSaved: (JSONObject) -> Unit,
    onDismiss: () -> Unit
) {
    val reviewed = remember { JSONObject(frozen.toString()) }
    val live = remember { AtomicBoolean(true) }
    val consumed = remember { AtomicBoolean(false) }
    val latestActive by rememberUpdatedState(active)
    val latestCurrent by rememberUpdatedState(current)
    val latestPending by rememberUpdatedState(pendingCommands)
    val latestRequest by rememberUpdatedState(onRequest)
    val latestSaved by rememberUpdatedState(onSaved)
    val latestDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(Unit) { onDispose { live.set(false) } }
    val old = reviewed.optJSONObject("assertion")
    fun retained(role: String): JSONObject? {
        val id = old?.optString(if (role == "subject") "subjectId" else "objectId").orEmpty()
        return when { id.isBlank() -> null; id == "owner" -> JSONObject().put("mode", "owner"); else -> JSONObject().put("mode", "candidate").put("role", role) }
    }
    var subject by remember { mutableStateOf(retained("subject")) }
    var subjectLabel by remember { mutableStateOf(old?.optString("subjectLabel").orEmpty()) }
    var target by remember { mutableStateOf(retained("object")) }
    var targetLabel by remember { mutableStateOf(old?.optString("object").orEmpty()) }
    var kind by remember { mutableStateOf(old?.optString("kind")?.takeIf { it in setOf("inferred", "told", "imagined") } ?: "inferred") }
    var predicate by remember { mutableStateOf(old?.optString("predicate").orEmpty()) }
    var objectText by remember { mutableStateOf(old?.optString("object") ?: reviewed.optString("text")) }
    var condition by remember { mutableStateOf(old?.optString("condition").orEmpty()) }
    var showTime by remember { mutableStateOf(false) }
    var eventTime by remember { mutableStateOf(assertionReviewTime(old, "eventTime")) }
    var validFrom by remember { mutableStateOf(assertionReviewTime(old, "validFrom")) }
    var validTo by remember { mutableStateOf(assertionReviewTime(old, "validTo")) }
    var reviewAfter by remember { mutableStateOf(assertionReviewTime(old, "reviewAfter")) }
    var browse by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var identities by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var more by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<String?>(null) }
    var writing by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    fun currentReview() = live.get() && latestActive && MemoryLearningReviewGuard.sameReview(reviewed, latestCurrent)
    fun load() {
        if (!currentReview() || consumed.get()) return
        prepared = false; localError = null
        try { reading = latestRequest("prepareAssertionReview", JSONObject().put("id", reviewed.getString("id")).put("revision", MemoryLearningReviewGuard.revision(reviewed)).put("query", query.trim())) }
        catch (failure: Exception) { localError = failure.message }
    }
    fun close() { if (live.compareAndSet(true, false)) latestDismiss() }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(responses, reading, writing) {
        reading?.let { requestId -> MemoryLearningReviewGuard.exactEnvelope(requestId, responses["prepareAssertionReview"])?.let { envelope ->
            reading = null
            val result = envelope.optJSONObject("result")
            if (live.get() && envelope.optBoolean("ok") && result != null && result.opt("formalMemorySaved") == false && result.opt("sourceProofIssued") == false &&
                result.opt("executionAuthorized") == false && MemoryLearningReviewGuard.sameReview(reviewed, result.optJSONObject("candidate"))) {
                val rows = result.optJSONArray("identities") ?: JSONArray()
                identities = (0 until rows.length()).map { JSONObject(rows.getJSONObject(it).toString()) }
                more = result.optBoolean("hasMoreIdentities"); prepared = true
            } else if (live.get()) localError = envelope.optString("error").ifBlank { "候选或身份来源已变化，请关闭后读取当前版本。" }
        } }
        writing?.let { requestId -> MemoryLearningReviewGuard.exactEnvelope(requestId, responses["reviewCandidateAssertion"])?.let { envelope ->
            writing = null
            val result = envelope.optJSONObject("result")
            val candidate = result?.optJSONObject("candidate")
            val valid = envelope.optBoolean("ok") && result != null && result.opt("formalMemorySaved") == false && result.opt("executionAuthorized") == false && result.opt("sourceProofIssued") == false &&
                candidate != null && candidate.optString("id") == reviewed.optString("id") && candidate.optString("status") == "pending" &&
                MemoryLearningReviewGuard.revision(candidate) == MemoryLearningReviewGuard.revision(reviewed) + 1 && assertionReviewKeepsSources(reviewed, candidate)
            if (live.compareAndSet(true, false)) {
                if (valid) latestSaved(JSONObject(candidate!!.toString()))
                else { live.set(true); prepared = false; localError = envelope.optString("error").ifBlank { "保存结果需要重新核对，请关闭后读取原候选；不会自动重发。" } }
            }
        } }
    }
    val busy = writing != null || "reviewCandidateAssertion" in pendingCommands
    val allowed = currentReview() && prepared && !busy && !consumed.get()
    AlertDialog(onDismissRequest = { if (!busy) close() }, containerColor = Color.White,
        title = { Text("核对主体与关系") }, text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("只修改这条待审候选。原来源继续保留；接受保存仍需另行确认，不会签发事实证明或执行权限。", fontSize = 13.sp, lineHeight = 22.sp)
                Text(reviewed.optString("text"), color = Ink, fontSize = 14.sp, lineHeight = 24.sp)
                if (reading != null) Text("正在核对当前版本…", color = SecondaryInk, fontSize = 12.sp)
                if (!currentReview()) Text("当前审阅已失效。请关闭并重新读取，不沿用旧按钮。", color = Danger, fontSize = 12.sp)
                Text("主体", fontSize = 14.sp)
                if (subject == null) Text("尚未选择；不会把第三人的描述默认归到主人。", color = SecondaryInk, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(subject?.optString("mode") == "owner", { if (allowed) { subject = JSONObject().put("mode", "owner"); subjectLabel = "主人" } }, label = { Text("主体：主人") }, enabled = allowed)
                    FilterChip(subject?.optString("mode") == "new" && subject?.optString("type") == "person", { if (allowed) { subject = JSONObject().put("mode", "new").put("type", "person"); subjectLabel = "" } }, label = { Text("主体：新人物") }, enabled = allowed)
                    FilterChip(subject?.optString("mode") == "new" && subject?.optString("type") == "entity", { if (allowed) { subject = JSONObject().put("mode", "new").put("type", "entity"); subjectLabel = "" } }, label = { Text("主体：新实体") }, enabled = allowed)
                    TextButton(onClick = { if (allowed) browse = "subject" }, enabled = allowed) { Text("选择已有主体") }
                }
                if (subject?.optString("mode") != "owner" && subject != null) OutlinedTextField(subjectLabel, { subjectLabel = it }, label = { Text("主体显示名") }, supportingText = { Text(if (subject?.optString("mode") == "new") "同名也创建独立身份；不会自动合并。" else "复用已选择的稳定身份；改名保留身份，来源仍随原版本核验。") }, enabled = allowed, modifier = Modifier.fillMaxWidth())
                if (subject?.optString("mode") == "candidate") Text("沿用本候选已保存的主体身份。", color = SecondaryInk, fontSize = 12.sp)
                OutlinedTextField(predicate, { predicate = it }, label = { Text("描述哪件事（如偏好、负责项目）") }, enabled = allowed, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(objectText, { objectText = it }, label = { Text("事实值或关系目标名称") }, minLines = 2, maxLines = 5, enabled = allowed, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { if (allowed) browse = "target" }, enabled = allowed) { Text(if (target == null) "可选：把目标关联到独立身份" else "重新选择关系目标") }
                if (target != null) {
                    Text("已关联目标：$targetLabel。目标名称必须与事实值相同；改名时请重新核对目标。", color = SecondaryInk, fontSize = 12.sp)
                    TextButton(onClick = { if (allowed) { target = null; targetLabel = "" } }, enabled = allowed) { Text("取消目标关联，保留事实文字") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("inferred" to "推测", "told" to "转述", "imagined" to "计划或想象（未证实发生）").forEach { (value, label) -> FilterChip(kind == value, { if (allowed) kind = value }, label = { Text(label) }, enabled = allowed) }
                }
                Text("计划必须保留为未证实发生，日期过去也不会自动变成现实事实。这里不提供“主人原话已证明”的选项。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
                OutlinedTextField(condition, { condition = it }, label = { Text("适用条件（可选）") }, enabled = allowed, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { showTime = !showTime }) { Text(if (showTime) "收起时间" else "核对时间（可选，未知可留空）") }
                if (showTime) {
                    Text("只填明确时间。日期使用 YYYY-MM-DD，按手机时区解释；也可保留已有的完整时间。留空表示未知，不使用保存时间代替。", color = SecondaryInk, fontSize = 12.sp, lineHeight = 20.sp)
                    OutlinedTextField(eventTime, { eventTime = it }, label = { Text("发生或计划时间") }, enabled = allowed, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(validFrom, { validFrom = it }, label = { Text("生效时间") }, enabled = allowed, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(validTo, { validTo = it }, label = { Text("结束时间") }, enabled = allowed, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(reviewAfter, { reviewAfter = it }, label = { Text("下次核对时间") }, enabled = allowed, modifier = Modifier.fillMaxWidth())
                }
                browse?.let { role ->
                    HorizontalDivider(color = Line)
                    Text(if (role == "subject") "选择同一空间中的已有主体" else "明确关联关系目标", fontSize = 14.sp)
                    if (role == "target") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { if (allowed) { target = JSONObject().put("mode", "owner"); targetLabel = "主人"; objectText = "主人"; browse = null } }, enabled = allowed) { Text("目标：主人") }
                            listOf("person" to "目标：新人物", "entity" to "目标：新实体").forEach { (type, label) ->
                                TextButton(onClick = { if (allowed && objectText.trim().length in 1..160) { targetLabel = objectText.trim(); target = JSONObject().put("mode", "new").put("type", type).put("label", targetLabel); browse = null } else localError = "请先把事实值填写为 1–160 字的目标名称。" }, enabled = allowed) { Text(label) }
                            }
                        }
                    }
                    OutlinedTextField(query, { query = it }, label = { Text("按名称、稳定编号或原记忆查找") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { load() }, enabled = currentReview() && !busy && reading == null && query.length <= 160 && !consumed.get()) { Text("查找已有身份") }
                    if (identities.isEmpty() && reading == null) Text("没有匹配的当前身份。可新建独立身份；不会按同名强行合并。", color = SecondaryInk, fontSize = 12.sp)
                    identities.forEach { identity ->
                        OutlinedButton(onClick = { if (allowed) {
                            val choice = JSONObject(identity.getJSONObject("selection").toString())
                            if (role == "subject") { subject = choice; subjectLabel = identity.getString("label") }
                            else { targetLabel = identity.getString("label"); target = choice; objectText = targetLabel }
                            browse = null
                        } }, enabled = allowed, modifier = Modifier.fillMaxWidth()) {
                            Text(identity.getString("label") + " · " + (if (identity.optString("type") == "person") "人物" else "实体") + "\n" + identity.getString("id") + "\n" + identity.optString("recordPreview"), fontSize = 12.sp, lineHeight = 20.sp)
                        }
                    }
                    if (more) Text("本次最多列出 64 个身份，请缩小查找条件；没有省略权限检查。", color = SecondaryInk, fontSize = 12.sp)
                    TextButton(onClick = { browse = null }) { Text("收起身份选择") }
                }
                localError?.let { Text(it, color = Danger, fontSize = 12.sp, lineHeight = 20.sp) }
            }
        }, confirmButton = {
            Button(onClick = {
                if (!currentReview() || !prepared || latestPending.any { it == "reviewCandidateAssertion" } || !consumed.compareAndSet(false, true)) return@Button
                try {
                    val choice = subject?.let { JSONObject(it.toString()) } ?: throw IllegalArgumentException("请明确选择主体，不会默认归到主人。")
                    if (choice.optString("mode") != "owner") choice.put("label", subjectLabel.trim())
                    val relation = target?.let { JSONObject(it.toString()).also { value -> if (value.optString("mode") != "owner") value.put("label", targetLabel.trim()) } }
                    require(relation == null || objectText.trim() == targetLabel.trim()) { "关系目标名称与事实值不同，请重新选择目标或取消关联。" }
                    val payload = JSONObject().put("id", reviewed.getString("id")).put("revision", MemoryLearningReviewGuard.revision(reviewed)).put("subject", choice)
                        .put("relationTarget", relation ?: JSONObject.NULL).put("kind", kind).put("predicate", predicate.trim()).put("object", objectText.trim()).put("condition", condition.trim())
                        .put("eventTime", assertionReviewDate(eventTime)).put("validFrom", assertionReviewDate(validFrom)).put("validTo", assertionReviewDate(validTo)).put("reviewAfter", assertionReviewDate(reviewAfter))
                    localError = null; writing = latestRequest("reviewCandidateAssertion", payload)
                } catch (failure: Exception) { consumed.set(false); localError = failure.message ?: "内容尚未核对完整。" }
            }, enabled = allowed && subject != null && predicate.isNotBlank() && objectText.isNotBlank()) { Text(if (busy) "正在保存核对…" else "保存核对，继续待审") }
        }, dismissButton = { TextButton(onClick = { close() }, enabled = !busy) { Text("取消") } })
}

private fun assertionReviewTime(value: JSONObject?, field: String): String = value?.takeUnless { it.isNull(field) }?.opt(field)?.let { raw ->
    if (raw is Int || raw is Long) Instant.ofEpochMilli((raw as Number).toLong()).toString() else ""
}.orEmpty()

private fun assertionReviewDate(value: String): Any {
    val text = value.trim()
    if (text.isEmpty()) return JSONObject.NULL
    return try {
        val instant = if (text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant() else Instant.parse(text)
        instant.toEpochMilli().also { require(it >= 0) { "时间需为 1970 年或之后；未知可留空。" } }
    } catch (failure: Exception) { throw IllegalArgumentException("请填写有效日期（如 2026-11-03）或保留完整时间；未知可留空。", failure) }
}

/** Native donor dependencies may add source refs, but may never discard the original evidence. */
private fun assertionReviewKeepsSources(before: JSONObject, after: JSONObject): Boolean = runCatching {
    val original = before.getJSONArray("sourceRefs")
    val returned = after.getJSONArray("sourceRefs")
    val refs = (0 until returned.length()).map { SourceRef.fromJson(returned.getJSONObject(it)) }.toSet()
    (0 until original.length()).all { refs.contains(SourceRef.fromJson(original.getJSONObject(it))) }
}.getOrDefault(false)
