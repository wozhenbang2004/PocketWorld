package dev.pocketengine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** Pure review fences shared by the page and focused JVM tests. No UI value grants authority. */
internal object MemoryLearningReviewGuard {
    private val draftFields = listOf("text", "layer", "reason", "applicability", "confidence")
    private val terminal = setOf("accepted", "rejected", "invalidated")
    private val mutations = setOf("acceptCandidate", "editCandidate", "rejectCandidate", "archiveCandidates", "reconsiderRejected", "forgetReviewed", "proposeConsolidation")

    @JvmStatic fun canSubmit(activeRequestId: String?, pending: Set<String>): Boolean =
        activeRequestId == null && pending.none { it in mutations }

    @JvmStatic fun revision(value: JSONObject?): Long {
        val raw = value?.opt("revision")
        return if (raw is Byte || raw is Short || raw is Int || raw is Long) (raw as Number).toLong().takeIf { it > 0 } ?: 0 else 0
    }

    @JvmStatic fun exactEnvelope(requestId: String?, envelope: JSONObject?): JSONObject? =
        envelope?.takeIf { !requestId.isNullOrBlank() && it.opt("requestId") == requestId && it.opt("ok") is Boolean }

    @JvmStatic fun result(requestId: String?, envelope: JSONObject?): JSONObject? =
        exactEnvelope(requestId, envelope)?.takeIf { it.optBoolean("ok") }?.optJSONObject("result")?.let { JSONObject(it.toString()) }

    @JvmStatic fun candidate(requestId: String?, id: String?, envelope: JSONObject?): JSONObject? =
        result(requestId, envelope)?.optJSONObject("candidate")?.takeIf { !id.isNullOrBlank() && it.opt("id") == id && revision(it) > 0 }

    @JvmStatic fun record(requestId: String?, id: String?, envelope: JSONObject?): JSONObject? =
        result(requestId, envelope)?.optJSONObject("record")?.takeIf { !id.isNullOrBlank() && it.opt("id") == id && revision(it) > 0 }

    /** Compare the actual displayed content, source versions and status, not merely the row ID. */
    @JvmStatic fun sameReview(frozen: JSONObject?, current: JSONObject?): Boolean =
        frozen != null && current != null && frozen.optString("id").isNotBlank() && revision(frozen) > 0 && canonical(frozen) == canonical(current)

    @JvmStatic fun draft(candidate: JSONObject): JSONObject {
        require(candidate.optString("status") != "archived") { "归档仅保留去重标记，没有正文可恢复" }
        val value = JSONObject()
        for (key in draftFields) value.put(key, candidate.get(key))
        validateDraft(value)
        return value
    }

    @JvmStatic fun validateDraft(value: JSONObject) {
        require(value.keys().asSequence().toSet() == draftFields.toSet()) { "只能编辑正文、类型、依据、适用条件与可信度" }
        require(value.opt("text") is String && value.getString("text").trim().length in 1..4000) { "正文需为 1–4000 字" }
        require(value.optString("layer") in setOf("fact", "preference", "episodic", "procedural")) { "请选择记忆类型" }
        require(value.opt("reason") is String && value.getString("reason").trim().length in 1..1000) { "候选依据需为 1–1000 字" }
        require(value.opt("applicability") is String && value.getString("applicability").trim().length <= 1000) { "适用条件最多 1000 字" }
        require(value.optString("layer") != "procedural" || value.getString("applicability").isNotBlank()) { "程序经验必须说明适用条件" }
        val confidence = value.opt("confidence")
        require(confidence is Number && confidence.toDouble().isFinite() && confidence.toDouble() in 0.0..1.0) { "可信度需为 0–1 的数字，不代表事实已核实" }
    }

    @JvmStatic fun payload(command: String, frozen: JSONObject, current: JSONObject?, input: JSONObject): JSONObject {
        check(sameReview(frozen, current)) { "内容或版本已变化，请刷新后重新核对" }
        val status = frozen.optString("status")
        val payload = JSONObject().put("id", frozen.getString("id")).put("revision", revision(frozen))
        when (command) {
            "acceptCandidate" -> {
                check(status == "pending") { "只有待审候选可以接受" }
                check(hasReviewEvidence(frozen)) { "候选缺少可核对的来源" }
            }
            "editCandidate" -> {
                check(status == "pending") { "只有待审候选可以编辑" }
                validateDraft(input)
                payload.put("changes", normalizedDraft(input))
            }
            "rejectCandidate" -> {
                check(status == "pending") { "只有待审候选可以拒绝" }
                val reason = input.optString("reason").trim()
                require(reason.length in 1..1000) { "请填写 1–1000 字的拒绝原因" }
                payload.put("reason", reason)
            }
            "reconsiderRejected" -> {
                check(status == "rejected") { "只有仍保留完整正文的拒绝项可在此重新考虑" }
                payload.put("draft", draft(frozen))
            }
            "archiveCandidates" -> {
                check(status in terminal) { "待审候选不能清理；归档标记不能再次清理" }
                return JSONObject().put("selections", JSONArray().put(payload))
            }
            else -> throw IllegalArgumentException("未知学习审阅操作")
        }
        return payload
    }

    @JvmStatic fun forgetPayload(frozen: JSONObject, current: JSONObject?): JSONObject {
        check(sameReview(frozen, current)) { "正式记忆已变化，请重新读取后决定是否遗忘" }
        return JSONObject().put("id", frozen.getString("id")).put("expectedRevision", revision(frozen))
    }

    @JvmStatic fun validMutationResult(command: String, payload: JSONObject, result: JSONObject?): Boolean {
        if (result == null) return false
        if (command == "archiveCandidates") return result.optInt("removedCandidates", -1) == payload.optJSONArray("selections")?.length() && result.optJSONObject("capacity") != null
        if (command == "forgetReviewed") return result.opt("deleted") is Number && result.optInt("deleted") >= 1
        val candidate = result.optJSONObject("candidate") ?: return false
        val from = payload.optLong("revision", -1)
        if (candidate.opt("id") != payload.opt("id") || from < 1 || from == Long.MAX_VALUE || revision(candidate) != from + 1) return false
        return when (command) {
            "acceptCandidate" -> candidate.optString("status") == "accepted" && result.optJSONObject("record")?.let {
                it.optString("id").isNotBlank() && it.optString("id") == candidate.optString("acceptedRecordId") && it.opt("learningCandidateId") == payload.opt("id") && revision(it) > 0
            } == true
            "editCandidate" -> candidate.optString("status") == "pending" && payload.optJSONObject("changes")?.let { changes ->
                draftFields.all { key -> canonical(candidate.opt(key)) == canonical(changes.opt(key)) }
            } == true
            "rejectCandidate" -> candidate.optString("status") == "rejected" && candidate.opt("rejectionReason") == payload.opt("reason")
            "reconsiderRejected" -> candidate.optString("status") == "pending" && payload.optJSONObject("draft")?.let { reviewed ->
                draftFields.all { key -> canonical(candidate.opt(key)) == canonical(reviewed.opt(key)) }
            } == true
            else -> false
        }
    }

    @JvmStatic fun eligibleRecord(value: JSONObject): Boolean = value.opt("id") is String && value.optString("id").isNotBlank() &&
        revision(value) > 0 && value.opt("active") == true && value.opt("consolidationBlocked") == false &&
        value.opt("ownerId") == "owner" && value.opt("spaceId") == "private" && value.opt("text") is String && value.optString("text").isNotBlank()

    @JvmStatic fun consolidationPayload(selected: JSONArray, current: JSONArray): JSONObject {
        require(selected.length() in 2..20) { "请选择 2–20 条不同的有效记忆" }
        val ids = mutableSetOf<String>()
        val refs = JSONArray()
        for (i in 0 until selected.length()) {
            val frozen = selected.getJSONObject(i)
            val id = frozen.optString("id")
            require(ids.add(id)) { "不能重复选择同一条记忆" }
            val matches = (0 until current.length()).mapNotNull { current.optJSONObject(it) }.filter { it.optString("id") == id }
            check(matches.size == 1 && eligibleRecord(frozen) && sameReview(frozen, matches.single())) { "所选记忆已变化、失效或不允许归纳，请重新读取并选择" }
            refs.put(JSONObject().put("id", id).put("revision", revision(frozen)))
        }
        return JSONObject().put("selections", refs)
    }

    @JvmStatic fun validRecordRefs(refs: JSONArray?): Boolean {
        if (refs == null || refs.length() !in 2..20) return false
        val ids = mutableSetOf<String>()
        return (0 until refs.length()).all { index -> refs.optJSONObject(index)?.let {
            it.opt("id") is String && it.optString("id").isNotBlank() && revision(it) > 0 && ids.add(it.optString("id"))
        } == true }
    }

    @JvmStatic fun hasReviewEvidence(candidate: JSONObject): Boolean =
        (candidate.optJSONArray("sourceRefs")?.length() ?: 0) > 0 || validRecordRefs(candidate.optJSONArray("recordRefs"))

    @JvmStatic fun sameRecordRefs(expected: JSONArray?, actual: JSONArray?): Boolean {
        if (!validRecordRefs(expected) || !validRecordRefs(actual) || expected!!.length() != actual!!.length()) return false
        fun pairs(refs: JSONArray) = (0 until refs.length()).associate { refs.getJSONObject(it).let { ref -> ref.getString("id") to revision(ref) } }
        return pairs(expected) == pairs(actual)
    }

    /** A pending result is usable only for this request and the exact versions the owner selected. */
    @JvmStatic fun consolidationCandidate(requestId: String?, payload: JSONObject, envelope: JSONObject?): JSONObject? {
        val result = result(requestId, envelope) ?: return null
        if (result.opt("formalMemorySaved") != false) return null
        val candidate = result.optJSONObject("candidate") ?: return null
        if (candidate.optString("id").isBlank() || revision(candidate) < 1 || !sameRecordRefs(payload.optJSONArray("selections"), candidate.optJSONArray("recordRefs"))) return null
        val status = candidate.optString("status")
        val pending = status == "pending" && result.opt("proposed") == true && result.opt("reviewRequired") == true && result.opt("outcome") == "awaiting_review"
        val barrier = status in setOf("accepted", "rejected", "invalidated", "archived") && result.opt("proposed") == false && result.opt("reviewRequired") == false && result.opt("outcome") == "existing_candidate_barrier"
        return candidate.takeIf { pending || barrier }?.let { JSONObject(it.toString()) }
    }

    @JvmStatic fun parentRecord(requestId: String?, reviewedCandidate: JSONObject, currentCandidate: JSONObject?, reference: JSONObject, envelope: JSONObject?): JSONObject? {
        if (!sameReview(reviewedCandidate, currentCandidate)) return null
        val refs = reviewedCandidate.optJSONArray("recordRefs") ?: return null
        if (!validRecordRefs(refs) || (0 until refs.length()).none { sameReview(reference, refs.optJSONObject(it)) }) return null
        return record(requestId, reference.optString("id"), envelope)?.takeIf { revision(it) == revision(reference) }
    }

    @JvmStatic fun cancelResult(cancelRequestId: String?, originalRequestId: String, envelope: JSONObject?): String? {
        val result = result(cancelRequestId, envelope) ?: return null
        if (result.opt("requestId") != originalRequestId) return null
        return when {
            result.opt("cancelled") == true && result.opt("outcome") == "stopped" -> "stopped"
            result.opt("cancelled") == false && result.opt("outcome") == "already_settled" -> "already_settled"
            result.opt("cancelled") == false && result.opt("outcome") == "not_active" -> "not_active"
            else -> null
        }
    }

    private fun normalizedDraft(value: JSONObject): JSONObject = JSONObject().also { result ->
        for (key in draftFields) result.put(key, if (value.get(key) is String) value.getString(key).trim() else value.get(key))
    }
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        is Number -> java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}

internal data class MemoryLearningRead(val requestId: String, val id: String = "")
internal data class MemoryLearningAction(val command: String, val reviewed: JSONObject)
internal data class MemoryLearningMutation(val command: String, val requestId: String, val payload: JSONObject)
internal data class MemoryConsolidationRequest(val requestId: String, val payload: JSONObject, val selected: JSONArray, val cancellationRequested: Boolean = false)
internal data class MemoryParentRead(val requestId: String, val candidate: JSONObject, val reference: JSONObject)

// COMPOSE_SURFACE_START — the pure guards above are exercised without an Android UI runtime.
@Composable
internal fun MemoryLearningReviewPage(
    snapshot: JSONObject,
    responses: Map<String, JSONObject>,
    pendingCommands: Set<String>,
    loading: Boolean,
    error: String?,
    onRequest: (command: String, payload: JSONObject) -> String,
    onOpenSource: (reference: JSONObject) -> Unit,
    onBack: () -> Unit,
    onDismissSource: () -> Unit = {}
) {
    var choosing by remember { mutableStateOf(false) }
    var recordRows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var recordsRead by remember { mutableStateOf<MemoryLearningRead?>(null) }
    var recordsLoaded by remember { mutableStateOf(false) }
    var selectedRecords by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var recordQuery by remember { mutableStateOf("") }
    var expandedRecord by remember { mutableStateOf<String?>(null) }
    var confirmConsolidation by remember { mutableStateOf<JSONArray?>(null) }
    var consolidation by remember { mutableStateOf<MemoryConsolidationRequest?>(null) }
    var cancelRead by remember { mutableStateOf<MemoryLearningRead?>(null) }
    var stopAndLeave by remember { mutableStateOf(false) }
    var proposalBarrier by remember { mutableStateOf<JSONObject?>(null) }
    var parentRead by remember { mutableStateOf<MemoryParentRead?>(null) }
    var parentContext by remember { mutableStateOf<MemoryParentRead?>(null) }
    var parentRecord by remember { mutableStateOf<JSONObject?>(null) }
    var parentError by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var history by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var capacity by remember { mutableStateOf<JSONObject?>(null) }
    var listRead by remember { mutableStateOf<MemoryLearningRead?>(null) }
    var detailRead by remember { mutableStateOf<MemoryLearningRead?>(null) }
    var historyRead by remember { mutableStateOf<MemoryLearningRead?>(null) }
    var capacityRead by remember { mutableStateOf<MemoryLearningRead?>(null) }
    var recordRead by remember { mutableStateOf<MemoryLearningRead?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var formalRecord by remember { mutableStateOf<JSONObject?>(null) }
    var detailStale by remember { mutableStateOf(false) }
    var listLoaded by remember { mutableStateOf(false) }
    var showProcessed by remember { mutableStateOf(false) }
    var showCapacity by remember { mutableStateOf(false) }
    var showRationale by remember(selectedId) { mutableStateOf(false) }
    var action by remember { mutableStateOf<MemoryLearningAction?>(null) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var editorText by remember { mutableStateOf("") }
    var editorLayer by remember { mutableStateOf("fact") }
    var editorReason by remember { mutableStateOf("") }
    var editorApplicability by remember { mutableStateOf("") }
    var editorConfidence by remember { mutableStateOf("") }
    var rejection by remember { mutableStateOf("") }
    var discardEditor by remember { mutableStateOf(false) }
    var mutation by remember { mutableStateOf<MemoryLearningMutation?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val mutationCommands = setOf("acceptCandidate", "editCandidate", "rejectCandidate", "archiveCandidates", "reconsiderRejected", "forgetReviewed", "proposeConsolidation")
    val reviewBusy = mutation != null || pendingCommands.any { it in mutationCommands && it != "proposeConsolidation" }
    val consolidationBusy = consolidation != null || pendingCommands.any { it == "proposeConsolidation" || it == "cancelConsolidation" }
    val busy = reviewBusy || consolidationBusy
    val listScroll = rememberScrollState()
    val detailScroll = rememberScrollState()

    fun request(command: String, payload: JSONObject = JSONObject()): MemoryLearningRead? = try {
        val id = onRequest(command, JSONObject(payload.toString()))
        check(id.isNotBlank()) { "原生请求未返回标识，请重试" }
        MemoryLearningRead(id, payload.optString("id"))
    } catch (failure: Exception) { localError = failure.message ?: "请求未发送，请重试"; null }

    fun refresh(includeDetail: Boolean = true) {
        onDismissSource()
        parentContext = null; parentRead = null; parentRecord = null
        localError = null
        if (choosing) { recordsLoaded = false; recordsRead = request("list") }
        listRead = request("listCandidates")
        capacityRead = request("candidateCapacity")
        if (showCapacity) historyRead = request("listCandidateHistory")
        if (includeDetail && selectedId != null) {
            detailStale = true
            formalRecord = null
            if (detail?.optString("status") == "archived") historyRead = request("listCandidateHistory")
            else detailRead = request("getCandidate", JSONObject().put("id", selectedId))
        }
    }
    fun open(row: JSONObject) {
        onDismissSource()
        parentContext = null; parentRead = null; parentRecord = null
        selectedId = row.optString("id")
        detail = null; formalRecord = null; detailStale = true; localError = null; message = null
        if (row.optString("status") == "archived") {
            // Re-read the archive itself; a stale list entry is never substituted for native detail.
            historyRead = request("listCandidateHistory")
        } else detailRead = request("getCandidate", JSONObject().put("id", selectedId))
    }
    fun back() {
        when {
            reviewBusy -> Unit
            consolidationBusy -> stopAndLeave = true
            parentContext != null -> { parentContext = null; parentRead = null; parentRecord = null }
            confirmConsolidation != null -> confirmConsolidation = null
            action != null -> action = null
            editing != null -> discardEditor = true
            selectedId != null -> { onDismissSource(); selectedId = null; detail = null; formalRecord = null; detailRead = null; recordRead = null; localError = null }
            choosing -> { choosing = false; recordsRead = null; confirmConsolidation = null; proposalBarrier = null; localError = null }
            else -> { onDismissSource(); onBack() }
        }
    }
    fun submit(command: String, payload: JSONObject) {
        if (!MemoryLearningReviewGuard.canSubmit(mutation?.requestId, pendingCommands)) return
        localError = null; message = null
        request(command, payload)?.let { mutation = MemoryLearningMutation(command, it.requestId, JSONObject(payload.toString())) }
    }
    fun cancelConsolidation() {
        val active = consolidation ?: return
        if (active.cancellationRequested || cancelRead != null) return
        consolidation = active.copy(cancellationRequested = true)
        cancelRead = request("cancelConsolidation", JSONObject().put("requestId", active.requestId))?.let { MemoryLearningRead(it.requestId, active.requestId) }
        if (cancelRead == null) consolidation = active
    }
    fun openParent(candidate: JSONObject, reference: JSONObject) {
        parentRecord = null; parentError = null
        request("getParentRecord", JSONObject().put("id", reference.getString("id")).put("revision", MemoryLearningReviewGuard.revision(reference)))?.let {
            val read = MemoryParentRead(it.requestId, JSONObject(candidate.toString()), JSONObject(reference.toString()))
            parentContext = read; parentRead = read
        }
    }
    fun editorInput(): JSONObject = JSONObject().put("text", editorText).put("layer", editorLayer).put("reason", editorReason)
        .put("applicability", editorApplicability).put("confidence", editorConfidence.toDoubleOrNull() ?: JSONObject.NULL)
    fun startEdit(value: JSONObject) {
        editing = JSONObject(value.toString())
        editorText = value.optString("text"); editorLayer = value.optString("layer"); editorReason = value.optString("reason")
        editorApplicability = value.optString("applicability"); editorConfidence = value.optDouble("confidence").toString(); localError = null
    }

    LaunchedEffect(Unit) { refresh(false) }
    // Snapshot changes are invalidation hints only. Never promote a snapshot/count into a review result.
    LaunchedEffect(snapshot.toString()) {
        if (listLoaded && !busy) refresh()
    }
    LaunchedEffect(responses, listRead, detailRead, capacityRead, historyRead, recordRead, mutation, recordsRead, consolidation, cancelRead, parentRead) {
        recordsRead?.let { read -> MemoryLearningReviewGuard.exactEnvelope(read.requestId, responses["list"])?.let { envelope ->
            recordsRead = null
            val result = MemoryLearningReviewGuard.result(read.requestId, envelope)
            if (choosing && result?.optJSONArray("records") != null) {
                recordRows = memoryLearningObjects(result.optJSONArray("records")); recordsLoaded = true
                if (selectedRecords.any { selected -> recordRows.none { MemoryLearningReviewGuard.sameReview(selected, it) } })
                    localError = "部分已选记忆已变化或删除。请取消这些选择，再核对当前版本；不会自动替换。"
            } else if (choosing) { recordsLoaded = false; localError = memoryLearningError(envelope, "真实记忆未读取，请刷新重试") }
        } }
        parentRead?.let { read -> MemoryLearningReviewGuard.exactEnvelope(read.requestId, responses["getParentRecord"])?.let { envelope ->
            parentRead = null
            if (parentContext?.requestId == read.requestId) {
                parentRecord = MemoryLearningReviewGuard.parentRecord(read.requestId, read.candidate, detail.takeUnless { detailStale }, read.reference, envelope)
                if (parentRecord == null) parentError = memoryLearningError(envelope, "这版父记忆已变化或不可用；不会用最新版本替代。请刷新候选核对。")
            }
        } }
        // Cancellation is processed first. A stopped request can never open a late candidate.
        cancelRead?.let { read -> MemoryLearningReviewGuard.exactEnvelope(read.requestId, responses["cancelConsolidation"])?.let { envelope ->
            cancelRead = null
            val active = consolidation
            if (active != null && read.id == active.requestId) {
                when (MemoryLearningReviewGuard.cancelResult(read.requestId, active.requestId, envelope)) {
                    "stopped" -> { consolidation = null; message = "归纳已停止，不会自动重试。已完成的操作不会回滚；可刷新核对候选。"; refresh(false) }
                    "already_settled" -> { message = "请求已经结束，不能撤销已产生的候选。正在核对原请求回执；候选仍需你审阅。" }
                    "not_active" -> { consolidation = null; message = "原请求已不在运行，未确认是否已产生候选。请刷新核对，不会自动重试。"; refresh(false) }
                    else -> { consolidation = active.copy(cancellationRequested = false); localError = memoryLearningError(envelope, "停止结果未能确认，可再次停止或返回结束本次请求。") }
                }
            }
        } }
        consolidation?.let { active -> MemoryLearningReviewGuard.exactEnvelope(active.requestId, responses["proposeConsolidation"])?.let { envelope ->
            consolidation = null; cancelRead = null
            val result = MemoryLearningReviewGuard.result(active.requestId, envelope)
            val candidate = MemoryLearningReviewGuard.consolidationCandidate(active.requestId, active.payload, envelope)
            when {
                active.cancellationRequested -> { message = "已结束等待。请求结束与停止可能同时发生，已产生的候选不会撤销；请刷新核对后再审阅。"; refresh(false) }
                candidate != null && candidate.optString("status") == "pending" -> {
                    choosing = false; selectedRecords = emptyList(); proposalBarrier = null; showProcessed = false
                    open(candidate); message = "归纳候选已生成，尚未成为正式记忆。先核对父记忆与来源，再决定是否接受。"; refresh(false)
                }
                candidate != null -> { proposalBarrier = candidate; message = "同一依据与内容已有处理记录；未重新打开、未自动接受。"; refresh(false) }
                result?.opt("proposed") == false && !result.has("candidate") -> { message = "没有产生候选：" + result.optString("reason", "来源不足或冲突尚未消解") + "。未保存正式记忆。"; refresh(false) }
                else -> { localError = memoryLearningError(envelope, "无法确认这次归纳结果，未标记成功。请刷新核对候选后再决定是否重试。") }
            }
        } }
        listRead?.let { read -> MemoryLearningReviewGuard.exactEnvelope(read.requestId, responses["listCandidates"])?.let { envelope ->
            listRead = null
            val result = MemoryLearningReviewGuard.result(read.requestId, envelope)
            if (result?.optJSONArray("candidates") != null) {
                rows = memoryLearningObjects(result.optJSONArray("candidates")); listLoaded = true
                detail?.takeIf { it.optString("status") != "archived" }?.let { current ->
                    val row = rows.find { it.optString("id") == selectedId }
                    if (!MemoryLearningReviewGuard.sameReview(current, row)) { detailStale = true; action = null; formalRecord = null }
                }
            } else localError = memoryLearningError(envelope, "候选列表未读取，请重试")
        } }
        capacityRead?.let { read -> MemoryLearningReviewGuard.exactEnvelope(read.requestId, responses["candidateCapacity"])?.let { envelope ->
            capacityRead = null
            val result = MemoryLearningReviewGuard.result(read.requestId, envelope)
            if (result?.has("candidateLimit") == true) capacity = result else localError = memoryLearningError(envelope, "容量未读取，请重试")
        } }
        detailRead?.let { read -> MemoryLearningReviewGuard.exactEnvelope(read.requestId, responses["getCandidate"])?.let { envelope ->
            detailRead = null
            if (read.id == selectedId) {
                val candidate = MemoryLearningReviewGuard.candidate(read.requestId, read.id, envelope)
                if (candidate != null) {
                    detail = candidate; detailStale = false; action = null; formalRecord = null
                    if (candidate.optString("status") == "accepted") recordRead = request("get", JSONObject().put("id", candidate.optString("acceptedRecordId")))
                } else { detailStale = true; localError = memoryLearningError(envelope, "详情已不可用，请刷新列表后重新选择") }
            }
        } }
        historyRead?.let { read -> MemoryLearningReviewGuard.exactEnvelope(read.requestId, responses["listCandidateHistory"])?.let { envelope ->
            historyRead = null
            val result = MemoryLearningReviewGuard.result(read.requestId, envelope)
            if (result?.optJSONArray("history") != null) {
                history = memoryLearningObjects(result.optJSONArray("history"))
                if (selectedId != null && detailRead == null && (detail == null || detail?.optString("status") == "archived")) {
                    val marker = history.find { it.optString("id") == selectedId }
                    if (marker != null) {
                        detail = marker; detailStale = false; action = null; formalRecord = null
                        if (marker.optString("outcome") == "accepted") recordRead = request("get", JSONObject().put("id", marker.optString("acceptedRecordId")))
                    } else { detailStale = true; localError = "归档标记已变化，请返回列表重新选择" }
                }
            } else localError = memoryLearningError(envelope, "归档历史未读取，请重试")
        } }
        recordRead?.let { read -> MemoryLearningReviewGuard.exactEnvelope(read.requestId, responses["get"])?.let { envelope ->
            recordRead = null
            if (detail?.optString("acceptedRecordId") == read.id) {
                val value = MemoryLearningReviewGuard.record(read.requestId, read.id, envelope)
                if (value != null && value.optString("learningCandidateId") == selectedId) formalRecord = value
                else { formalRecord = null; localError = memoryLearningError(envelope, "正式记忆未读取或已变化，不能确认遗忘") }
            }
        } }
        mutation?.let { sent -> MemoryLearningReviewGuard.exactEnvelope(sent.requestId, responses[sent.command])?.let { envelope ->
            mutation = null
            val result = MemoryLearningReviewGuard.result(sent.requestId, envelope)
            if (MemoryLearningReviewGuard.validMutationResult(sent.command, sent.payload, result)) {
                onDismissSource()
                action = null; editing = null; detail = null; formalRecord = null; selectedId = null; parentContext = null; parentRead = null; parentRecord = null; selectedRecords = emptyList()
                message = when (sent.command) {
                    "acceptCandidate" -> "已保存为普通记忆；未选择核心记忆，内容与程序效果仍未经独立核实。"
                    "editCandidate" -> "修改已保存。请重新打开候选、核对新版本后再接受。"
                    "rejectCandidate" -> "已拒绝。相同来源与内容不会因重试自动重新出现。"
                    "archiveCandidates" -> "已清理选中的终态正文。正式记忆和必要的去重屏障仍保留。"
                    "reconsiderRejected" -> "已重新放回待审阅；尚未接受为正式记忆。"
                    else -> "已遗忘正式记忆及受同一证据影响的派生记忆；旧来源版本不能自动重新导入。"
                }
                refresh(false)
            } else {
                action = null; detailStale = true; formalRecord = null
                localError = memoryLearningError(envelope, "原生结果无法确认，未标记成功。请刷新核对实际状态后重新选择。")
            }
        } }
    }
    BackHandler { back() }

    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { back() }, enabled = !reviewBusy) { Text("返回") }
                Text(if (editing != null) "编辑候选" else if (selectedId != null) "审阅候选" else if (choosing) "从记忆归纳" else "学习候选", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (editing != null) discardEditor = true else refresh() }, enabled = !busy && listRead == null && detailRead == null) { Text("刷新", fontSize = 12.sp) }
            }
            HorizontalDivider(color = Line)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(if (selectedId == null) listScroll else detailScroll).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading || listRead != null || detailRead != null || historyRead != null || recordRead != null || recordsRead != null || parentRead != null || busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink, trackColor = Soft)
                    MemoryLearningCaption(if (consolidationBusy) { if (consolidation?.cancellationRequested == true) "正在停止这次归纳…" else "正在从所选记忆归纳候选…" } else if (busy) "正在保存原生结果，请稍候；不会重复提交。" else "正在读取当前版本…")
                }
                (localError ?: error)?.let { Text(it, color = Danger, fontSize = 13.sp, lineHeight = 22.sp) }
                message?.let { MemoryLearningCaption(it) }
                if (choosing && selectedId == null) {
                    Text(if (consolidationBusy) "归纳中，仍由你把关" else "先选择值得一起归纳的记忆", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    MemoryLearningCaption("选择依据 → 模型归纳 → 由你审阅。只使用你明确选择的当前版本；不会覆盖原记忆、自动接受、选择核心记忆或生成可执行技能。")
                    if (consolidationBusy) {
                        MemoryLearningCaption("本次将所选记忆及其来源引用发给当前模型，不开放工具。停止不会撤销已经完成的候选保存。")
                        consolidation?.let { active -> memoryLearningObjects(active.selected).forEach { record ->
                            MemoryConsolidationRecord(record, true, false, expandedRecord == record.optString("id"), {}, { expandedRecord = if (expandedRecord == record.optString("id")) null else record.optString("id") })
                        } }
                    } else {
                        OutlinedTextField(recordQuery, { recordQuery = it }, label = { Text("筛选记忆正文") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (selectedRecords.isNotEmpty()) TextButton(onClick = { selectedRecords = emptyList(); localError = null }) { Text("清空已选 ${selectedRecords.size} 条") }
                        val visible = recordRows.filter { recordQuery.isBlank() || it.optString("text").contains(recordQuery.trim(), ignoreCase = true) }
                        if (recordsRead == null && (!recordsLoaded || recordRows.isEmpty())) MemoryLearningCaption(if (!recordsLoaded) "尚未读取到真实记忆。请刷新重试。" else "还没有可用于归纳的记忆。先保存真实记忆，再回来选择至少两条；这里不会补造示例。")
                        if (recordsLoaded && recordRows.isNotEmpty() && visible.isEmpty()) MemoryLearningCaption("没有匹配的记忆，试试清除筛选。已选内容仍保留。")
                        visible.forEach { record ->
                            val checked = selectedRecords.any { it.optString("id") == record.optString("id") }
                            val eligible = MemoryLearningReviewGuard.eligibleRecord(record)
                            MemoryConsolidationRecord(record, checked, recordsLoaded && recordsRead == null && (checked || eligible && selectedRecords.size < 20), expandedRecord == record.optString("id"), {
                                selectedRecords = if (checked) selectedRecords.filterNot { it.optString("id") == record.optString("id") } else selectedRecords + JSONObject(record.toString())
                                proposalBarrier = null; localError = null
                            }, { expandedRecord = if (expandedRecord == record.optString("id")) null else record.optString("id") })
                        }
                        proposalBarrier?.let { candidate ->
                            HorizontalDivider(color = Line)
                            MemoryLearningCaption("已有记录：" + memoryLearningStatus(candidate))
                            TextButton(onClick = { choosing = false; open(candidate) }, enabled = !busy) { Text("查看已有候选与处理结果") }
                        }
                    }
                } else if (selectedId == null) {
                    Text("由你决定，什么值得记住", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    MemoryLearningCaption("候选只是带来源的推测。核对并接受后才会成为普通记忆；不会自动成为核心记忆或可执行技能。")
                    OutlinedButton(onClick = { choosing = true; selectedRecords = emptyList(); proposalBarrier = null; recordsLoaded = false; localError = null; message = null; recordsRead = request("list") }, enabled = !busy, shape = Rounded, modifier = Modifier.fillMaxWidth()) { Text("从真实记忆归纳候选") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showProcessed, onClick = { showProcessed = false }, label = { Text("待审阅") })
                        FilterChip(selected = showProcessed, onClick = { showProcessed = true }, label = { Text("已处理") })
                    }
                    val filtered = rows.filter { (it.optString("status") != "pending") == showProcessed }
                    if (filtered.isEmpty() && listRead == null) MemoryLearningCaption(if (!listLoaded) "尚未读取到候选。请刷新重试。" else if (showProcessed) "目前没有已处理的候选。" else "没有待审候选。新候选需要真实来源，不会在这里自动生成或接受。")
                    filtered.forEach { row -> MemoryLearningRow(row, !busy) { open(row) } }
                    HorizontalDivider(color = Line, modifier = Modifier.padding(top = 14.dp))
                    TextButton(onClick = { showCapacity = !showCapacity; if (showCapacity) { capacityRead = request("candidateCapacity"); historyRead = request("listCandidateHistory") } }, enabled = !busy) { Text(if (showCapacity) "收起容量与归档" else "容量与归档历史") }
                    if (showCapacity) {
                        capacity?.let { current ->
                            Text("完整候选 ${current.optInt("candidatesUsed")} / ${current.optInt("candidateLimit")} · 剩余 ${current.optInt("candidateSlotsAvailable")}", fontSize = 14.sp, color = Ink)
                            MemoryLearningCaption("去重标记 ${current.optInt("markersUsed")} / ${current.optInt("markerLimit")}。容量为全局上限；当前空间有 ${current.optInt("scopeTerminalCandidates")} 条终态候选。待审候选绝不自动删除。")
                        }
                        MemoryLearningCaption("清理入口在已处理候选详情内，逐条确认。清理会移除候选正文，但保留正式记忆及必要去重标记；不是遗忘。")
                        history.forEach { marker -> MemoryLearningRow(marker, !busy) { open(marker) } }
                        if (history.isEmpty() && historyRead == null) MemoryLearningCaption("尚无归档去重标记。")
                    }
                } else if (editing != null) {
                    val frozen = editing!!
                    val canSave = !busy && !detailStale && MemoryLearningReviewGuard.sameReview(frozen, detail)
                    MemoryLearningCaption("修改只保存到待审候选，不会立即写入正式记忆。来源保持不变。")
                    OutlinedTextField(editorText, { editorText = it }, label = { Text("候选正文") }, supportingText = { Text("${editorText.length} / 4000") }, minLines = 4, maxLines = 12, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    MemoryLearningLayerPicker(editorLayer, !busy) { editorLayer = it }
                    OutlinedTextField(editorReason, { editorReason = it }, label = { Text("为什么值得记住") }, minLines = 2, maxLines = 6, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(editorApplicability, { editorApplicability = it }, label = { Text("适用条件（程序经验必填）") }, minLines = 2, maxLines = 6, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(editorConfidence, { editorConfidence = it }, label = { Text("候选可信度（0–1）") }, supportingText = { Text("这是推测强度，不是正确率或验证结论。") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy, modifier = Modifier.fillMaxWidth())
                    if (!canSave && !busy) MemoryLearningCaption("当前审阅版本已失效。草稿仍在本页；返回并重新读取，核对新版本后再编辑。")
                    Button(onClick = {
                        try { submit("editCandidate", MemoryLearningReviewGuard.payload("editCandidate", frozen, detail.takeUnless { detailStale }, editorInput())) }
                        catch (failure: Exception) { localError = failure.message }
                    }, enabled = canSave, shape = Rounded, modifier = Modifier.fillMaxWidth()) { Text("保存修改，继续待审") }
                    TextButton(onClick = { discardEditor = true }, enabled = !busy) { Text("取消编辑") }
                } else {
                    val current = detail
                    if (current == null && detailRead == null && historyRead == null) MemoryLearningCaption("详情暂不可用。返回列表重新选择，或刷新重试。")
                    if (current != null) {
                        val status = current.optString("status")
                        val canAct = !busy && !detailStale && detailRead == null && historyRead == null
                        Text(memoryLearningStatus(current) + " · 版本 " + MemoryLearningReviewGuard.revision(current), color = SecondaryInk, fontSize = 12.sp)
                        if (status == "archived") MemoryLearningCaption("此项已清理，仅保留去重标记。候选正文与拒绝原因没有保留，不能在这里假还原或直接重新考虑。")
                        else if (status == "invalidated") MemoryLearningCaption("来源已变化、删除或被遗忘，候选正文已移除。请勿按旧内容接受。原因：" + memoryLearningInvalidation(current.optString("invalidationReason")))
                        else {
                            current.optJSONObject("appKnowledge")?.let { app ->
                                MemoryLearningCaption("App ${app.optString("packageName")} · 版本 ${app.optString("appVersion")} · ${if (app.optString("baseId").isBlank()) "首次试用" else "试用替换旧稿 ${app.optString("baseId")} / v${app.optLong("baseRevision")}"}")
                                MemoryLearningCaption("接受后只注入试用稿；旧稿保留。主人核对成功才转正式，${if (app.optBoolean("lesson")) "连续两次" else "一次"}明确归责失败回退。环境、取消和模型错误不算失败。")
                            }
                            Text(memoryLearningLayer(current.optString("layer")), fontSize = 12.sp, color = SecondaryInk)
                            SelectionContainer { Text(current.optString("text"), color = Ink, fontSize = 17.sp, lineHeight = 29.sp) }
                            TextButton(onClick = { showRationale = !showRationale }) { Text(if (showRationale) "收起依据与适用范围" else "依据与适用范围") }
                            if (showRationale) {
                                MemoryLearningCaption("候选依据：" + current.optString("reason"))
                                MemoryLearningCaption("适用条件：" + current.optString("applicability").ifBlank { "未指定" })
                                MemoryLearningCaption("候选可信度：" + current.optDouble("confidence") + "；未经独立核实。")
                            }
                            if (status == "rejected") MemoryLearningCaption("拒绝原因：" + current.optString("rejectionReason"))
                        }
                        HorizontalDivider(color = Line)
                        Text("核对真实来源", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
                        val refs = memoryLearningObjects(current.optJSONArray("sourceRefs"))
                        refs.forEachIndexed { index, ref ->
                            TextButton(onClick = { onOpenSource(JSONObject(ref.toString())) }, enabled = canAct, modifier = Modifier.fillMaxWidth()) {
                                Text("${index + 1}. " + (when (ref.optString("kind")) { "task_evidence" -> "任务证据"; "workspace_file" -> "原工作区文件范围"; else -> "对话消息" }) + " · 版本 ${ref.optLong("revision")}\n" + ref.optString("sourceId"), modifier = Modifier.fillMaxWidth(), fontSize = 13.sp, lineHeight = 22.sp)
                            }
                        }
                        val parentRefs = if (MemoryLearningReviewGuard.validRecordRefs(current.optJSONArray("recordRefs"))) memoryLearningObjects(current.optJSONArray("recordRefs")) else emptyList()
                        if (parentRefs.isNotEmpty()) {
                            Text("归纳依据 · 父记忆的精确版本", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
                            MemoryLearningCaption("父记忆是归纳依据，不是对话或任务来源的替身。点开核对正文与它自己的真实来源；不会用更新版本冒充这一版。")
                            parentRefs.forEachIndexed { index, reference ->
                                TextButton(onClick = { openParent(current, reference) }, enabled = canAct, modifier = Modifier.fillMaxWidth()) {
                                    Text("${index + 1}. 父记忆 · 版本 ${MemoryLearningReviewGuard.revision(reference)}\n" + reference.optString("id"), modifier = Modifier.fillMaxWidth(), fontSize = 13.sp, lineHeight = 22.sp)
                                }
                            }
                        }
                        if (!MemoryLearningReviewGuard.hasReviewEvidence(current)) MemoryLearningCaption("缺少可核对的来源引用，不能凭空补出证据。")
                        if (detailStale) MemoryLearningCaption("详情已变化或操作结果待核对。请刷新后重新选择，不沿用旧确认。")
                        if (status == "pending") {
                            Button(onClick = { action = MemoryLearningAction("acceptCandidate", JSONObject(current.toString())) }, enabled = canAct && MemoryLearningReviewGuard.hasReviewEvidence(current), shape = Rounded, modifier = Modifier.fillMaxWidth()) { Text(if (current.has("appKnowledge")) "接受并试用 App 修订" else "接受为普通记忆") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { startEdit(current) }, enabled = canAct) { Text("先编辑") }
                                TextButton(onClick = { rejection = ""; action = MemoryLearningAction("rejectCandidate", JSONObject(current.toString())) }, enabled = canAct) { Text("拒绝候选", color = Danger) }
                            }
                        }
                        if (status == "rejected") OutlinedButton(onClick = { action = MemoryLearningAction("reconsiderRejected", JSONObject(current.toString())) }, enabled = canAct) { Text("重新放回待审阅") }
                        if (status == "accepted" || current.optString("outcome") == "accepted") {
                            MemoryLearningCaption("接受仅表示你同意保存，不表示事实或程序效果已验证，也不会自动选为核心记忆。")
                            formalRecord?.let { record ->
                                Text("当前正式记忆 · 版本 ${MemoryLearningReviewGuard.revision(record)}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                SelectionContainer { Text(record.optString("text"), color = Ink, fontSize = 14.sp, lineHeight = 24.sp) }
                                TextButton(onClick = { action = MemoryLearningAction("forgetReviewed", JSONObject(record.toString())) }, enabled = canAct && recordRead == null) { Text("遗忘这条正式记忆…", color = Danger) }
                            }
                        }
                        if (status in setOf("accepted", "rejected", "invalidated")) {
                            HorizontalDivider(color = Line, modifier = Modifier.padding(top = 8.dp))
                            TextButton(onClick = { action = MemoryLearningAction("archiveCandidates", JSONObject(current.toString())) }, enabled = canAct) { Text("清理这条终态候选正文…", color = SecondaryInk) }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            if (choosing && selectedId == null) {
                HorizontalDivider(color = Line)
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (consolidationBusy) {
                        Button(onClick = { cancelConsolidation() }, enabled = consolidation != null && consolidation?.cancellationRequested != true && cancelRead == null, shape = Rounded, modifier = Modifier.fillMaxWidth()) { Text(if (consolidation?.cancellationRequested == true) "正在停止…" else "停止这次归纳") }
                    } else {
                        MemoryLearningCaption("已选 ${selectedRecords.size} / 20 条 · 至少选择 2 条")
                        val validSelection = recordsLoaded && recordsRead == null && runCatching { MemoryLearningReviewGuard.consolidationPayload(JSONArray(selectedRecords), JSONArray(recordRows)) }.isSuccess
                        Button(onClick = { confirmConsolidation = JSONArray(selectedRecords.map { JSONObject(it.toString()) }) }, enabled = validSelection && !busy, shape = Rounded, modifier = Modifier.fillMaxWidth()) { Text("归纳并生成候选") }
                    }
                }
            }
        }
    }
    confirmConsolidation?.let { frozen ->
        val canSend = !busy && recordsLoaded && recordsRead == null && runCatching { MemoryLearningReviewGuard.consolidationPayload(frozen, JSONArray(recordRows)) }.isSuccess
        AlertDialog(onDismissRequest = { confirmConsolidation = null }, containerColor = Color.White,
            title = { Text("将这 ${frozen.length()} 条记忆发给当前模型？", fontSize = 19.sp) }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("发送所选记忆及其来源引用，只归纳一条待审候选，不调用工具。原记忆保持不变；模型可能判断证据不足而不产生候选。接受、编辑或拒绝由你另行决定。", fontSize = 14.sp, lineHeight = 24.sp)
                    memoryLearningObjects(frozen).forEachIndexed { index, record ->
                        MemoryLearningCaption("${index + 1}. " + memoryLearningLayer(record.optString("layer")) + " · 版本 ${MemoryLearningReviewGuard.revision(record)}")
                        SelectionContainer { Text(record.optString("text"), color = Ink, fontSize = 14.sp, lineHeight = 24.sp) }
                    }
                    if (!canSend) Text("已选记忆已变化，请返回重新读取与核对。", color = Danger, fontSize = 13.sp)
                    localError?.let { Text(it, color = Danger, fontSize = 13.sp) }
                }
            }, confirmButton = { Button(onClick = {
                try {
                    val payload = MemoryLearningReviewGuard.consolidationPayload(frozen, JSONArray(recordRows))
                    if (MemoryLearningReviewGuard.canSubmit(mutation?.requestId, pendingCommands)) request("proposeConsolidation", payload)?.let {
                        consolidation = MemoryConsolidationRequest(it.requestId, payload, JSONArray(frozen.toString()))
                        confirmConsolidation = null; proposalBarrier = null; localError = null; message = null
                    }
                } catch (failure: Exception) { localError = failure.message }
            }, enabled = canSend) { Text("发送所选记忆并归纳") } }, dismissButton = { TextButton(onClick = { confirmConsolidation = null }) { Text("返回选择") } })
    }
    parentContext?.let { context ->
        val matches = !detailStale && MemoryLearningReviewGuard.sameReview(context.candidate, detail)
        AlertDialog(onDismissRequest = { parentContext = null; parentRead = null; parentRecord = null }, containerColor = Color.White,
            title = { Text("父记忆 · 版本 ${MemoryLearningReviewGuard.revision(context.reference)}", fontSize = 19.sp) }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (parentRead != null) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink, trackColor = Soft); MemoryLearningCaption("正在精确读取这一版父记忆…") }
                    if (!matches) Text("当前候选已变化，请关闭并刷新后重新核对。", color = Danger, fontSize = 13.sp)
                    parentError?.let { Text(it, color = Danger, fontSize = 13.sp) }
                    if (matches) parentRecord?.let { record ->
                        MemoryLearningCaption(memoryLearningLayer(record.optString("layer")) + if (record.optBoolean("core")) " · 已选为核心记忆" else " · 普通记忆")
                        SelectionContainer { Text(record.optString("text"), color = Ink, fontSize = 16.sp, lineHeight = 27.sp) }
                        if (record.opt("active") != true) MemoryLearningCaption("这条父记忆目前已失效，不能把它当作有效的新依据。")
                        val sourceRefs = memoryLearningObjects(record.optJSONArray("sourceRefs"))
                        Text("这条父记忆的来源", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
                        if (sourceRefs.isEmpty()) MemoryLearningCaption(record.optString("source").ifBlank { "机主保存的记忆；没有可打开的对话或任务来源。" })
                        sourceRefs.forEachIndexed { index, source ->
                            TextButton(onClick = { onOpenSource(JSONObject(source.toString())) }, enabled = !busy) {
                                Text("${index + 1}. " + (if (source.optString("kind") == "task_evidence") "任务证据" else "对话消息") + " · 版本 ${source.optLong("revision")}\n" + source.optString("sourceId"), fontSize = 13.sp, lineHeight = 22.sp)
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { parentContext = null; parentRead = null; parentRecord = null }) { Text("返回候选") } },
            dismissButton = { if (matches && parentRead == null && parentRecord == null) TextButton(onClick = { openParent(context.candidate, context.reference) }) { Text("重试读取这一版") } })
    }
    if (stopAndLeave) AlertDialog(onDismissRequest = { stopAndLeave = false }, containerColor = Color.White,
        title = { Text("停止归纳并返回？") }, text = { Text("结束本次请求且不自动重试。已经产生的候选不会撤销；仍需你接受后才成为正式记忆。") },
        confirmButton = { TextButton(onClick = { cancelConsolidation(); stopAndLeave = false; onDismissSource(); onBack() }) { Text("停止并返回") } },
        dismissButton = { TextButton(onClick = { stopAndLeave = false }) { Text("继续等待") } })
    action?.let { review ->
        val current = if (review.command == "forgetReviewed") formalRecord else detail
        val currentMatches = !detailStale && MemoryLearningReviewGuard.sameReview(review.reviewed, current)
        val label = memoryLearningActionLabel(review.command)
        AlertDialog(onDismissRequest = { if (!busy) action = null }, containerColor = Color.White,
            title = { Text(label + "？", fontSize = 19.sp) }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (review.command == "acceptCandidate" && review.reviewed.has("appKnowledge")) Text("将此 App 修订作为试用参考，暂时替换所指旧稿但不删除。成功/失败须核对原任务结果；不授予任何权限。", fontSize = 14.sp, lineHeight = 24.sp)
                    Text(memoryLearningConsequence(review.command), fontSize = 14.sp, lineHeight = 24.sp)
                    if (review.reviewed.has("text")) SelectionContainer { Text(review.reviewed.optString("text"), fontSize = 13.sp, lineHeight = 22.sp, color = SecondaryInk) }
                    if (review.command == "rejectCandidate") OutlinedTextField(rejection, { rejection = it }, label = { Text("拒绝原因") }, minLines = 2, maxLines = 5, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    if (!currentMatches) Text("内容或版本已变化，请取消并刷新后重新核对。", color = Danger, fontSize = 13.sp)
                    localError?.let { Text(it, color = Danger, fontSize = 13.sp) }
                }
            }, confirmButton = {
                Button(onClick = {
                    try {
                        val payload = if (review.command == "forgetReviewed") MemoryLearningReviewGuard.forgetPayload(review.reviewed, current.takeUnless { detailStale })
                        else MemoryLearningReviewGuard.payload(review.command, review.reviewed, current.takeUnless { detailStale }, JSONObject().put("reason", rejection))
                        submit(review.command, payload)
                    } catch (failure: Exception) { localError = failure.message }
                }, enabled = !busy && currentMatches && (review.command != "rejectCandidate" || rejection.trim().length in 1..1000)) { Text(if (busy) "正在处理" else label) }
            }, dismissButton = { TextButton(onClick = { action = null }, enabled = !busy) { Text("取消") } })
    }
    if (discardEditor) AlertDialog(onDismissRequest = { discardEditor = false }, containerColor = Color.White, title = { Text("放弃未保存的修改？") }, text = { Text("只丢弃本页草稿，不会修改候选或接受记忆。") }, confirmButton = { TextButton(onClick = { editing = null; discardEditor = false; localError = null; refresh() }) { Text("放弃并重新读取") } }, dismissButton = { TextButton(onClick = { discardEditor = false }) { Text("继续编辑") } })
}

private fun memoryLearningObjects(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it)?.let { value -> JSONObject(value.toString()) } }
private fun memoryLearningError(envelope: JSONObject, fallback: String): String = envelope.opt("error")?.takeUnless { it == JSONObject.NULL }?.let { if (it is JSONObject) it.optString("message", fallback) else it.toString() }?.takeIf { it.isNotBlank() } ?: fallback
private fun memoryLearningLayer(value: String): String = when (value) { "fact" -> "事实候选"; "preference" -> "偏好候选"; "episodic" -> "经历候选"; "procedural" -> "程序经验候选"; else -> "未知类型" }
private fun memoryLearningStatus(value: JSONObject): String = when (value.optString("status")) { "pending" -> "待审阅"; "accepted" -> "已接受"; "rejected" -> "已拒绝"; "invalidated" -> "已失效"; "archived" -> "已归档 · " + if (value.optString("outcome") == "accepted") "原已接受" else "原已拒绝"; else -> "状态未知" }
private fun memoryLearningInvalidation(value: String): String = when (value) { "forgotten" -> "来源版本已被遗忘"; "source_changed" -> "来源版本已更新"; "source_deleted" -> "来源已删除"; else -> "来源暂不可核验" }
private fun memoryLearningActionLabel(command: String): String = when (command) { "acceptCandidate" -> "确认接受"; "rejectCandidate" -> "确认拒绝"; "reconsiderRejected" -> "放回待审阅"; "archiveCandidates" -> "清理候选正文"; else -> "确认遗忘" }
private fun memoryLearningConsequence(command: String): String = when (command) {
    "acceptCandidate" -> "把你正在核对的这一版保存为普通记忆，供以后参考。不会选择核心记忆，不代表事实已核实或技能已验证。"
    "rejectCandidate" -> "不写入正式记忆，并保留拒绝记录，防止相同来源与内容因重试自动重新出现。以后可明确重新考虑。"
    "reconsiderRejected" -> "只将这一条完整拒绝项重新放回待审阅。不会自动接受，也不解除其他拒绝项或来源遗忘屏障。"
    "archiveCandidates" -> "永久移除这条终态候选的正文和拒绝原因，释放完整候选容量。接受过的正式记忆不会删除；必要的去重标记仍保留。没有正文的归档不能直接恢复。"
    else -> "删除这条正式记忆、依赖它的记忆，以及使用同一被遗忘证据的相关记忆。旧来源版本会被阻止自动重新导入，相关候选会失效。此操作不可在此撤销；不会删除原始对话或任务证据。"
}
@Composable private fun MemoryLearningCaption(value: String) { Text(value, color = SecondaryInk, fontSize = 13.sp, lineHeight = 23.sp) }
@Composable private fun MemoryLearningRow(value: JSONObject, enabled: Boolean, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = open).padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value.optString("text").ifBlank { if (value.optString("status") == "archived") "已清理的候选正文" else "来源失效，正文已移除" }, color = Ink, fontSize = 15.sp, lineHeight = 25.sp, maxLines = 3)
        Text(memoryLearningStatus(value) + " · 查看详情与来源", color = SecondaryInk, fontSize = 12.sp)
    }
    HorizontalDivider(color = Line)
}
@Composable private fun MemoryLearningLayerPicker(selected: String, enabled: Boolean, choose: (String) -> Unit) {
    Text("记忆类型", color = SecondaryInk, fontSize = 12.sp)
    listOf(listOf("fact", "preference"), listOf("episodic", "procedural")).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { layer ->
            FilterChip(selected = selected == layer, onClick = { choose(layer) }, enabled = enabled, label = { Text(memoryLearningLayer(layer)) })
        } }
    }
}

@Composable private fun MemoryConsolidationRecord(record: JSONObject, selected: Boolean, enabled: Boolean, expanded: Boolean, toggle: () -> Unit, expand: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = toggle), verticalAlignment = Alignment.Top) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = 8.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(record.optString("text"), color = Ink, fontSize = 15.sp, lineHeight = 25.sp, maxLines = if (expanded) Int.MAX_VALUE else 3)
                Text(memoryLearningLayer(record.optString("layer")) + " · 版本 ${MemoryLearningReviewGuard.revision(record)}" + if (record.optBoolean("core")) " · 核心" else "", color = SecondaryInk, fontSize = 12.sp)
                if (record.opt("active") != true) MemoryLearningCaption("已失效或过期，不能用于归纳")
                else if (record.opt("consolidationBlocked") != false) MemoryLearningCaption("此来源版本不允许归纳，保留在这里供核对")
            }
        }
        TextButton(onClick = expand, modifier = Modifier.align(Alignment.End)) { Text(if (expanded) "收起正文" else "展开全文", fontSize = 12.sp) }
    }
    HorizontalDivider(color = Line)
}
