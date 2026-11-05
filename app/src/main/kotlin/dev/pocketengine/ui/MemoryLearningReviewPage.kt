package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject

internal object MemoryLearningReviewGuard {
    private val draftFields = listOf("text", "layer", "reason", "applicability", "confidence")
    private val terminal = setOf("accepted", "rejected", "invalidated")
    private val mutations = setOf("acceptCandidate", "editCandidate", "reviewCandidateAssertion", "rejectCandidate", "archiveCandidates", "reconsiderRejected", "forgetReviewed", "proposeConsolidation")

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

    @JvmStatic fun validRecordRefs(refs: JSONArray?): Boolean = validParents(refs, 2)

    @JvmStatic fun validCandidateParents(candidate: JSONObject): Boolean =
        validParents(candidate.optJSONArray("recordRefs"), if (candidate.optString("formation") == "assertion_review") 1 else 2)

    private fun validParents(refs: JSONArray?, minimum: Int): Boolean {
        if (refs == null || refs.length() !in minimum..20) return false
        val ids = mutableSetOf<String>()
        return (0 until refs.length()).all { index -> refs.optJSONObject(index)?.let {
            it.opt("id") is String && it.optString("id").isNotBlank() && revision(it) > 0 && ids.add(it.optString("id"))
        } == true }
    }

    @JvmStatic fun hasReviewEvidence(candidate: JSONObject): Boolean =
        (candidate.optJSONArray("sourceRefs")?.length() ?: 0) > 0 || validCandidateParents(candidate)

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
        if (!validCandidateParents(reviewedCandidate) || (0 until refs.length()).none { sameReview(reference, refs.optJSONObject(it)) }) return null
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
