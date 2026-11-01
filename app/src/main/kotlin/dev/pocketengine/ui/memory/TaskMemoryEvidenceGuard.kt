package dev.pocketengine.ui.memory

import org.json.JSONArray
import org.json.JSONObject

/** UI consistency checks only. Authentication, source admission and all writes remain in the native host. */
object TaskMemoryEvidenceGuard {
    /** Location consistency only; callers must obtain this jump from a real native raw tool receipt. */
    fun rawCitation(taskId: String, runId: String, supplied: JSONObject): JSONObject {
        require(supplied.optString("kind") == "task_raw_source" && supplied.optString("taskId") == taskId && supplied.optString("runId") == runId &&
            supplied.optString("revisionKind") == "sha256_content_fingerprint" && supplied.optString("unit") == "UTF-16") { "引用不属于这个原任务和轮次" }
        val offset = supplied.opt("offset"); val chars = supplied.opt("chars")
        require((offset is Int || offset is Long) && (offset as Number).toLong() in 0..Int.MAX_VALUE.toLong() &&
            (chars is Int || chars is Long) && (chars as Number).toLong() in 1L..16000L) { "引用范围无效" }
        val request = JSONObject().put("sourceId", supplied.getString("sourceId")).put("revision", supplied.getString("revision"))
            .put("offset", offset).put("maxChars", maxOf(64, (chars as Number).toInt()))
        dev.pocketengine.features.memory.TaskMemoryTrace.rawCitation(taskId, runId, request)
        return JSONObject(supplied.toString())
    }

    private val taskStates = setOf("revoked", "run_changed", "task_cancelled", "task_unavailable")
    private val rowStates = setOf("current", "record_changed", "record_removed", "superseded", "expired", "record_inactive", "provenance_mismatch", "source_changed", "source_deleted", "source_forgotten", "source_unavailable") + taskStates
    private val sourceStates = setOf("current", "source_changed", "source_deleted", "source_forgotten", "source_unavailable")

    fun revision(record: JSONObject): Long {
        val value = record.opt("revision")
        return if (value is Byte || value is Short || value is Int || value is Long) (value as Number).toLong().takeIf { it > 0 } ?: 0 else 0
    }

    fun receipt(taskId: String, runId: String, supplied: JSONObject, requestId: String? = null, answerId: String? = null): JSONObject {
        val copy = JSONObject(supplied.toString())
        if (copy.optString("phase") == "prepared" && copy.optJSONObject("task") != null) {
            val task = copy.getJSONObject("task")
            for (key in listOf("taskId", "runId", "ownerId", "spaceId", "coordinatorEpoch")) copy.put(key, task.get(key))
        }
        require(copy.opt("taskId") == taskId && copy.opt("runId") == runId) { "回执不属于当前任务或本次运行" }
        if (copy.optString("phase") == "provided_to_request") {
            require(requestId == null || copy.opt("requestId") == requestId) { "回执不属于所选请求" }
            require(answerId == null || copy.opt("answerId") == answerId) { "回执不属于所选回答" }
        }
        require(copy.opt("executionAuthorized") == false && copy.opt("modelUseVerified") == false) { "回执不能自证授权或模型已经采用" }
        require(copy.optString("phase") in setOf("prepared", "provided_to_request", "unavailable")) { "回执阶段暂不受支持" }
        require(copy.optString("status") in setOf("current", "invalidated", "unavailable") + taskStates) { "回执状态暂不受支持" }
        for (key in listOf("ownerId", "spaceId")) require(copy.opt(key) is String && copy.getString(key).isNotBlank()) { "回执身份不完整" }
        if (copy.getString("phase") == "provided_to_request") require(copy.optString("receiptId").isNotBlank()) { "提供回执身份缺失" }
        val rows = copy.getJSONArray("records")
        require(rows.length() <= 64) { "回执条目超过本页范围" }
        val seen = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            require(row.optString("id").isNotBlank() && revision(row) > 0 && seen.add(row.getString("id"))) { "记忆身份或版本无效" }
            require(sameSpace(copy, row) && row.optString("status") in rowStates) { "记忆空间或状态不匹配" }
            require(row.optString("selection") in setOf("core", "retrieved")) { "记忆提供方式不明确" }
            if (copy.getString("status") !in setOf("current", "invalidated") || row.getString("status") != "current") {
                row.remove("text") // Failed/stale rows never expose a caller-retained body as historical evidence.
            } else require(row.opt("text") is String && row.getString("text").length <= 4000) { "当前记忆正文不可读" }
            val refs = row.optJSONArray("sourceRefs") ?: JSONArray()
            require(refs.length() <= 400) { "来源过多，请到原任务核对" }
            for (r in 0 until refs.length()) source(copy, refs.getJSONObject(r))
        }
        selectionDetails(copy)
        return copy
    }

    /** Native historical selection metadata only; never recompute explanations using a new search. */
    fun selectionDetails(receipt: JSONObject): JSONObject? {
        val supplied = receipt.optJSONObject("selectionDiagnostics")
        if (supplied == null) { require(receipt.opt("selectionDiagnosticsAvailable") != true) { "原始选择说明缺失" }; return null }
        require(receipt.optString("phase") == "prepared" || receipt.opt("selectionDiagnosticsAvailable") == true) { "选择说明未绑定原生回执" }
        val d = JSONObject(supplied.toString())
        require(d.optInt("format") == 1 && d.optString("policy") == "balanced_core_task_v1") { "选择说明版本暂不受支持" }
        val rows = receipt.getJSONArray("records"); val selected = d.getJSONArray("selected")
        require(selected.length() == rows.length() && d.getInt("coreSelected") + d.getInt("retrievedSelected") == rows.length() &&
            d.getInt("charsUsed") in 0..d.getInt("maxChars") && d.getInt("maxChars") <= 12000 && d.getInt("limit") in 0..16) { "选择说明与本次条目不一致" }
        for (i in 0 until selected.length()) {
            val s = selected.getJSONObject(i); val row = rows.getJSONObject(i)
            require(s.opt("id") == row.opt("id") && revision(s) == revision(row) && s.opt("selection") == row.opt("selection") &&
                s.optString("reason") == if (row.optString("selection") == "core") "owner_core" else "task_query_match") { "选入原因与原始版本不一致" }
        }
        val omitted = d.getJSONArray("omitted"); val reasons = d.getJSONObject("omissionCounts")
        val allowed = setOf("record_limit", "character_limit", "record_too_large", "source_or_record_changed")
        require(omitted.length() <= 32) { "省略明细过多" }
        for (key in reasons.keys()) require(key in allowed && reasons.getInt(key) in 0..1000) { "省略原因不可读" }
        for (i in 0 until omitted.length()) require(omitted.getJSONObject(i).optString("reason") in allowed && revision(omitted.getJSONObject(i)) > 0) { "省略版本不可读" }
        return d
    }

    fun sameSpace(receipt: JSONObject, record: JSONObject): Boolean =
        receipt.optString("ownerId").isNotBlank() && receipt.opt("ownerId") == record.opt("ownerId") && receipt.opt("spaceId") == record.opt("spaceId")

    fun sameSource(expected: JSONObject?, actual: JSONObject): Boolean = expected != null &&
        listOf("kind", "sourceId", "ownerId", "spaceId").all { expected.opt(it) == actual.opt(it) } &&
        revision(expected) > 0 && revision(expected) == revision(actual)

    fun source(receipt: JSONObject, supplied: JSONObject): JSONObject {
        val result = JSONObject(supplied.toString())
        require(sameSpace(receipt, result) && result.optString("kind") in setOf("message", "task_evidence") &&
            result.optString("sourceId").isNotBlank() && revision(result) > 0) { "来源身份或空间不匹配" }
        require(!result.has("state") || result.optString("state") in sourceStates) { "来源状态暂不受支持" }
        return result
    }

    fun target(receipt: JSONObject): JSONObject = JSONObject().put("receiptId", receipt.getString("receiptId"))
        .put("taskId", receipt.getString("taskId")).put("runId", receipt.getString("runId"))
        .put("requestId", receipt.opt("requestId")).put("answerId", receipt.opt("answerId"))

    fun reviewed(receipt: JSONObject, record: JSONObject): JSONObject {
        require(receipt.optString("phase") == "provided_to_request" && receipt.optString("status") in setOf("current", "invalidated") && record.optString("status") == "current" && sameSpace(receipt, record)) { "记忆已失效或仅为检索，请刷新后重新核对" }
        val current = receipt.getJSONArray("records").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.singleOrNull { it.opt("id") == record.opt("id") } }
        require(current != null && current.toString() == record.toString() && revision(record) > 0) { "审阅内容或版本已变化" }
        return target(receipt).put("id", record.getString("id")).put("expectedRevision", revision(record))
    }

    fun correction(receipt: JSONObject, record: JSONObject, text: String): JSONObject {
        require(text.trim().length in 1..4000 && text.trim() != record.optString("text").trim()) { "请填写与原文不同的1–4000字正文" }
        return reviewed(receipt, record).put("text", text.trim())
    }

    fun replacement(receipt: JSONObject, record: JSONObject, kept: JSONObject): JSONObject {
        require(sameSpace(receipt, kept) && kept.optBoolean("active") && kept.optString("id").isNotBlank() &&
            kept.opt("id") != record.opt("id") && revision(kept) > 0 && kept.opt("text") is String) { "只能选择同一空间中另一条当前有效的正式记忆" }
        return reviewed(receipt, record).put("keptId", kept.getString("id")).put("keptRevision", revision(kept))
            .put("replacedId", record.getString("id")).put("replacedRevision", revision(record))
    }

    fun exactEnvelope(requestId: String?, envelope: JSONObject?): JSONObject? = envelope?.takeIf {
        !requestId.isNullOrBlank() && it.opt("requestId") == requestId && it.opt("ok") is Boolean
    }

    /** A successful memory write alone is insufficient evidence that previous model context was cleared. */
    fun feedback(result: JSONObject, receipt: JSONObject, command: String, payload: JSONObject): String {
        require(result.opt("receiptId") == receipt.opt("receiptId") && result.opt("taskId") == receipt.opt("taskId") && result.opt("runId") == receipt.opt("runId")) { "反馈回执不属于本次任务" }
        for (key in listOf("requestId", "answerId")) if (receipt.optString(key).isNotBlank()) require(result.opt(key) == receipt.opt(key)) { "反馈回执不属于所选请求或回答" }
        val expected = payload.getLong("expectedRevision")
        when (command) {
            "forgetTaskMemoryReviewed" -> require(result.optInt("deleted", 0) >= 1) { "遗忘结果未确认" }
            "correctTaskMemoryReviewed" -> {
                val saved = result.getJSONObject("record")
                require(sameSpace(receipt, saved) && expected < Long.MAX_VALUE && saved.opt("id") == payload.opt("id") && revision(saved) == expected + 1 && saved.opt("text") == payload.opt("text")) { "纠正后的内容或版本未确认" }
            }
            "supersedeTaskMemoryReviewed" -> {
                val kept = result.getJSONObject("record")
                require(sameSpace(receipt, kept) && result.optInt("deleted", 0) >= 1 && payload.getLong("keptRevision") < Long.MAX_VALUE &&
                    kept.opt("id") == payload.opt("keptId") && revision(kept) == payload.getLong("keptRevision") + 1) { "替代后的版本或遗忘结果未确认" }
            }
            else -> error("未知任务记忆审核操作")
        }
        return when {
            result.opt("revocationPending") == true -> "记忆已更新；旧上下文清理仍待核对，暂不能确认后续停用。"
            result.opt("revocationPending") == false && result.opt("contextInvalidated") == true && result.opt("futureRecallBlocked") == true ->
                if (command == "forgetTaskMemoryReviewed") "已遗忘并清理本次旧参考；旧版本及其衍生内容后续不会再召回。已经发送的内容无法收回。"
                else "已保存你的纠正并清理本次旧参考；后续不再召回旧版本。新版本仍需按当前来源核验。"
            else -> "记忆写入已确认；宿主尚未确认旧上下文清理，后续停用状态待核对。"
        }
    }
}
