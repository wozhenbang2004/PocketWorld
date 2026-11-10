package dev.pocketengine.ui.web

import dev.pocketengine.ui.MemoryHistoryReviewGuard
import dev.pocketengine.ui.MemoryLearningReviewGuard
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.memory.TaskMemoryEvidenceGuard
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Opaque reading/review windows over the original owner memory and task journal. No second store. */
internal class PocketWebMemoryHistory(private val controller: PocketWorldController) {
    private class Session(val id: String, val domain: String, val owner: Any, val scope: Any,
                          val displayedScope: JSONObject, val expires: Long, var revision: Long = 0)
    private data class Pin(val session: Session, val revision: Long, val kind: String, val body: String)
    private val sessions = linkedMapOf<String, Session>()
    private val pins = linkedMapOf<String, Pin>()

    companion object {
        val COMMANDS = setOf("memoryHistory.open", "memoryHistory.close", "memoryHistory.search", "memoryHistory.record",
            "memoryHistory.cognition", "memoryHistory.source", "memoryHistory.review", "memoryHistory.conflict.review", "memoryHistory.confirm",
            "taskMemory.search", "taskMemory.task", "taskMemory.answer", "taskMemory.receipt", "taskMemory.source", "taskMemory.source.focus", "taskMemory.replacements",
            "taskMemory.review", "taskMemory.confirm", "taskMemory.focus")
        private fun fields(value: JSONObject, vararg names: String) {
            require(value.keys().asSequence().all { it in names }) { "原来源参数包含未接入字段" }
        }
        private fun text(value: JSONObject, key: String, limit: Int = 200, empty: Boolean = false): String {
            val result = value.opt(key)
            require(result is String && result.length <= limit && '\u0000' !in result && (empty || result.isNotBlank())) { "请重新选择原来源：$key" }
            return result
        }
        private fun index(value: JSONObject, key: String, size: Int): Int {
            val number = value.opt(key); require(number is Int && number in 0 until size) { "原来源位置已变化" }; return number
        }
        private fun copy(value: JSONObject) = JSONObject(value.toString())
        private fun rows(value: JSONArray?): List<JSONObject> = if (value == null) emptyList() else (0 until value.length()).map { value.getJSONObject(it) }
        private fun envelope(value: JSONObject) = JSONObject().put("requestId", "native-receipt").put("ok", true).put("result", value)
    }

    fun clear() { sessions.clear(); pins.clear() }
    private fun current(session: Session): Boolean = sessions[session.id] === session &&
        controller.webPrivateOwnerVisible() && session.owner == controller.webWindowTicket() &&
        (session.domain != "memoryReview" || session.scope == controller.webMemoryScopeTicket()) &&
        session.expires >= System.currentTimeMillis()
    private fun session(args: JSONObject, domain: String): Session {
        val selected = sessions[text(args, "sessionHandle")] ?: error("原阅读窗口已关闭，请重新读取")
        check(current(selected) && selected.domain == domain) { "原页面或记忆范围已变化，请重新读取" }; return selected
    }
    private fun retire(session: Session) { session.revision++; pins.entries.removeAll { it.value.session === session } }
    private fun pin(session: Session, kind: String, value: JSONObject): String {
        check(current(session)); while (pins.size >= 240) pins.remove(pins.keys.first())
        val id = UUID.randomUUID().toString(); pins[id] = Pin(session, session.revision, kind, value.toString()); return id
    }
    private fun pinTaskRecord(session: Session, value: JSONObject): String {
        check(current(session))
        val body = value.toString()
        // Receipt polling reads the same frozen evidence repeatedly. Reuse only
        // an exact read-only pin in this session/revision; never reuse reviews
        // or extend the native owner/scope/expiry window.
        val retained = pins.entries.firstOrNull { (_, pin) ->
            pin.session === session && pin.revision == session.revision &&
                pin.kind == "task-record" && pin.body == body
        }
        return retained?.key ?: pin(session, "task-record", value)
    }
    private fun selected(args: JSONObject, key: String, vararg kinds: String, consume: Boolean = false): Pair<Session, JSONObject> {
        val id = text(args, key); val original = pins[id] ?: error("原选择已关闭，请重新读取")
        check(current(original.session) && original.revision == original.session.revision && original.kind in kinds) { "原选择或范围已变化，请重新读取" }
        if (consume) pins.remove(id)
        return original.session to JSONObject(original.body)
    }
    private fun call(session: Session, command: String, payload: JSONObject, done: (JSONObject?, String?) -> Unit,
                     project: (JSONObject) -> JSONObject = { it }) {
        check(current(session)); val revision = session.revision
        controller.requestOwnerDomainReceipt(session.domain, command, payload) { value, error ->
            if (!current(session) || revision != session.revision) done(null, "原阅读窗口已变化，请重新核对")
            else if (value == null || error != null) done(null, error ?: "没有取得原操作回执")
            else runCatching { project(value) }.fold({ done(PocketWebState.publicValue(it) as JSONObject, null) }, { done(null, it.message ?: "原回执不可核对") })
        }
    }
    private fun memoryCurrent(session: Session, saved: JSONObject, done: (JSONObject?, String?) -> Unit, next: () -> Unit) {
        val row = saved.getJSONObject("record")
        if (saved.has("subjectId")) {
            call(session, "cognitionProjection", JSONObject().put("subjectId", saved.getString("subjectId")), { value, error ->
                if (value == null) done(null, error) else try {
                    val all = listOf("current", "history", "pendingConflicts").flatMap { rows(value.optJSONArray(it)) }
                    check(all.any { MemoryLearningReviewGuard.sameReview(row, it) }) { "原主体认知版本已变化，请重新读取" }
                    next()
                } catch (failure: Exception) { done(null, failure.message) }
            })
            return
        }
        val history = saved.optBoolean("history")
        val target = JSONObject().put("id", row.getString("id")); if (history) target.put("revision", row.getLong("revision"))
        call(session, if (history) "readHistoryRecord" else "get", target, { value, error ->
            if (value == null) done(null, error) else try {
                val current = value.getJSONObject("record")
                check(MemoryLearningReviewGuard.sameReview(row, current)) { "原记忆版本或来源已变化" }
                if (history) check(MemoryHistoryReviewGuard.record("native-receipt", row, envelope(value), session.displayedScope) != null) { "原历史状态已变化" }
                next()
            } catch (failure: Exception) { done(null, failure.message) }
        })
    }
    private fun taskCurrent(session: Session, saved: JSONObject, done: (JSONObject?, String?) -> Unit, next: (JSONObject, JSONObject) -> Unit) {
        val receipt = saved.getJSONObject("receipt"); val row = saved.getJSONObject("record")
        call(session, "readTaskMemoryReceipt", binding(receipt), { value, error ->
            if (value == null) done(null, error) else try {
                val checked = checkedReceipt(value)
                check(checked.opt("receiptId") == receipt.opt("receiptId")) { "原提供回执已变化" }
                val now = rows(checked.getJSONArray("records")).singleOrNull { it.opt("id") == row.opt("id") } ?: error("原记忆已不在这次提供回执中")
                check(MemoryLearningReviewGuard.sameReview(row, now) && now.optString("status") == "current") { "原记忆版本或来源已失效，请重新读取" }
                TaskMemoryEvidenceGuard.reviewed(checked, now)
                next(checked, now)
            } catch (failure: Exception) { done(null, failure.message) }
        })
    }
    private fun binding(receipt: JSONObject) = JSONObject().also { value ->
        for (key in listOf("taskId", "runId", "requestId", "answerId")) value.put(key, text(receipt, key))
    }
    private fun checkedReceipt(value: JSONObject) = TaskMemoryEvidenceGuard.receipt(value.getString("taskId"), value.getString("runId"), value, value.getString("requestId"), value.getString("answerId"))
    private fun sourcePage(value: JSONObject, offset: Int): JSONObject {
        val body = value.optString("text"); require(offset in 0..body.length) { "原文位置已变化，请重新读取" }
        require(offset == 0 || offset == body.length || !(body[offset].isLowSurrogate() && body[offset - 1].isHighSurrogate())) { "请从完整原文字边界继续读取" }
        var end = minOf(offset + 8000, body.length)
        if (end > offset && end < body.length && body[end - 1].isHighSurrogate() && body[end].isLowSurrogate()) end--
        val nativeNext = value.opt("nextOffset")
        return copy(value).put("text", body.substring(offset, end)).put("offset", offset).put("nextOffset", end).put("hasMore", end < body.length)
            .put("sourceReadLimited", nativeNext is Number && nativeNext.toLong() >= 0)
    }
    private fun offset(args: JSONObject): Int {
        val value = args.opt("offset") ?: 0; require(value is Int && value >= 0); return value
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        if (action == "memoryHistory.close") {
            fields(args, "handle"); val id = text(args, "handle"); sessions.remove(id)?.let(::retire); pins.remove(id)
            done(JSONObject().put("closed", true), null); return
        }
        check(controller.webPrivateOwnerVisible()) { "请回到当前私人管理页" }
        when (action) {
            "memoryHistory.open" -> {
                fields(args, "view"); val view = text(args, "view"); require(view in setOf("history", "tasks"))
                while (sessions.size >= 8) sessions.remove(sessions.keys.first())?.let(::retire)
                val snapshot = controller.state.ownerSnapshot("memoryReview")
                if (view == "history") check(snapshot.optString("namespaceStatus") in setOf("private", "project")) { "请先重新读取并确认记忆范围" }
                val scope = snapshot.optJSONObject("scope") ?: JSONObject().put("ownerId", "owner").put("spaceId", "private")
                val id = UUID.randomUUID().toString()
                sessions[id] = Session(id, if (view == "history") "memoryReview" else "taskMemory", controller.webWindowTicket(), controller.webMemoryScopeTicket(), copy(scope), System.currentTimeMillis() + 600_000)
                done(JSONObject().put("sessionHandle", id).put("scope", scope).put("view", view), null)
            }
            "memoryHistory.search" -> {
                fields(args, "sessionHandle", "query", "cursor"); val session = session(args, "memoryReview")
                val query = text(args, "query", 500, true).trim(); val cursor = text(args, "cursor", 8000, true); retire(session)
                call(session, "searchHistory", JSONObject().put("query", query).put("cursor", cursor).put("limit", 12), done) { result ->
                    val page = MemoryHistoryReviewGuard.page("native-receipt", query, envelope(result), session.displayedScope) ?: error("原历史页范围或版本不可核对")
                    rows(page.getJSONArray("matches")).forEach { it.put("recordHandle", pin(session, "history-header", it)) }; page
                }
            }
            "memoryHistory.record" -> {
                fields(args, "recordHandle"); val (session, header) = selected(args, "recordHandle", "history-header")
                call(session, "readHistoryRecord", JSONObject().put("id", header.getString("id")).put("revision", header.getLong("revision")), done) { result ->
                    val row = MemoryHistoryReviewGuard.record("native-receipt", header, envelope(result), session.displayedScope) ?: error("原历史版本已变化")
                    result.put("readHandle", pin(session, "memory-record", JSONObject().put("record", row).put("history", true)))
                }
            }
            "memoryHistory.cognition" -> {
                fields(args, "sessionHandle", "subjectId"); val session = session(args, "memoryReview"); val subject = text(args, "subjectId")
                check(session.displayedScope.optString("spaceId") == "private") { "主体认知只在原私人记忆范围读取" }; retire(session)
                call(session, "cognitionProjection", JSONObject().put("subjectId", subject), done) { value ->
                    check(value.optString("subjectId") == subject)
                    for (key in listOf("current", "history", "pendingConflicts")) rows(value.optJSONArray(key)).forEach {
                        it.put("readHandle", pin(session, "memory-record", JSONObject().put("record", it).put("subjectId", subject)))
                    }
                    val groups = JSONArray()
                    rows(value.optJSONArray("pendingConflicts")).groupBy { it.getJSONObject("assertion").let { assertion -> assertion.optString("predicate") + "\u0000" + assertion.optString("condition") } }.values.forEach { group ->
                        val original = JSONObject().put("subjectId", subject).put("records", JSONArray(group.map { copy(it).apply { remove("readHandle") } }))
                        groups.put(JSONObject().put("records", original.getJSONArray("records")).put("conflictHandle", pin(session, "conflict", original)))
                    }
                    value.put("conflictGroups", groups)
                }
            }
            "memoryHistory.source" -> {
                fields(args, "readHandle", "index", "span", "offset"); val (session, original) = selected(args, "readHandle", "memory-record")
                require(args.opt("span") is Boolean); val span = args.getBoolean("span"); val row = original.getJSONObject("record")
                val refs = (if (span) row.optJSONObject("assertion")?.optJSONArray("evidenceSpans") else row.optJSONArray("sourceRefs")) ?: JSONArray()
                val reference = copy(refs.getJSONObject(index(args, "index", refs.length()))); val offset = offset(args)
                memoryCurrent(session, original, done) { call(session, if (span) "readAssertionEvidence" else "openSource", reference, done) { sourcePage(it, offset) } }
            }
            "memoryHistory.review" -> {
                fields(args, "readHandle", "text"); val (session, original) = selected(args, "readHandle", "memory-record")
                val row = original.getJSONObject("record"); val text = text(args, "text", 4000).trim(); require(text != row.optString("text"))
                val payload = JSONObject().put("id", row.getString("id")).put("expectedRevision", row.getLong("revision")).put("text", text)
                memoryCurrent(session, original, done) { done(JSONObject().put("reviewHandle", pin(session, "memory-review", JSONObject().put("original", original).put("command", "correctReviewed").put("payload", payload))).put("operation", "correct").put("before", row).put("afterText", text).put("executionAuthorized", false), null) }
            }
            "memoryHistory.conflict.review" -> {
                fields(args, "conflictHandle", "decision", "selectedId", "reason"); val (session, original) = selected(args, "conflictHandle", "conflict")
                val decision = text(args, "decision"); require(decision in setOf("replace", "coexist")); val chosen = text(args, "selectedId", empty = decision == "coexist")
                val originals = rows(original.getJSONArray("records")); require(decision == "coexist" || originals.any { it.getString("id") == chosen })
                val payload = JSONObject().put("records", JSONArray(originals.map { JSONObject().put("id", it.getString("id")).put("revision", it.getLong("revision")) }))
                    .put("decision", decision).put("selectedId", if (decision == "coexist") "" else chosen).put("reason", text(args, "reason", 1000).trim())
                conflictCurrent(session, original, done) { done(JSONObject().put("reviewHandle", pin(session, "memory-review", JSONObject().put("conflict", original).put("command", "resolveAssertionConflictReviewed").put("payload", payload))).put("operation", "conflict").put("beforeRecords", original.getJSONArray("records")).put("decision", decision).put("selectedId", chosen).put("reason", payload.getString("reason")).put("executionAuthorized", false), null) }
            }
            "memoryHistory.confirm" -> {
                fields(args, "reviewHandle"); val (session, review) = selected(args, "reviewHandle", "memory-review", consume = true)
                val finish = { call(session, review.getString("command"), review.getJSONObject("payload"), done) { result -> retire(session); result } }
                if (review.has("conflict")) conflictCurrent(session, review.getJSONObject("conflict"), done, finish) else memoryCurrent(session, review.getJSONObject("original"), done, finish)
            }
            "taskMemory.search" -> {
                fields(args, "sessionHandle", "query", "cursor"); val session = session(args, "taskMemory"); retire(session)
                call(session, "searchTaskMemoryTasks", JSONObject().put("query", text(args, "query", 500, true)).put("cursor", text(args, "cursor", 8000, true)).put("limit", 20), done)
            }
            "taskMemory.task" -> {
                fields(args, "sessionHandle", "taskId"); val session = session(args, "taskMemory"); val id = text(args, "taskId"); retire(session); val revision = session.revision
                controller.readTaskForReview(id) { task, error ->
                    if (!current(session) || session.revision != revision) done(null, "原任务阅读窗口已变化")
                    else if (task == null) done(null, error ?: "未取得原任务")
                    else try {
                        check(task.raw.optString("actorId") == "owner" && task.raw.optString("spaceId") == "private") { "只能读取原私人任务" }
                        val reference = task.raw.optJSONObject("evidence")?.optJSONObject("memoryReference")
                        val original = JSONObject().put("taskId", task.id)
                        if (reference != null) { check(reference.optString("taskId") == id); original.put("binding", binding(reference)) }
                        done(JSONObject().put("taskHandle", pin(session, "task", original)).put("taskId", id).put("goal", task.goal).put("status", task.status).put("referenceAvailable", reference != null), null)
                    } catch (failure: Exception) { done(null, failure.message) }
                }
            }
            "taskMemory.answer" -> {
                fields(args, "sessionHandle", "messageId")
                val session = session(args, "taskMemory"); retire(session)
                val messageId = text(args, "messageId")
                val reference = controller.webAnswerMemoryReference(messageId)
                val original = JSONObject().put("taskId", reference.getString("taskId"))
                    .put("messageId", messageId).put("binding", binding(reference))
                done(JSONObject().put("taskHandle", pin(session, "task", original)).put("referenceAvailable", true), null)
            }
            "taskMemory.receipt" -> {
                fields(args, "taskHandle"); val (session, original) = selected(args, "taskHandle", "task"); val binding = original.optJSONObject("binding") ?: error("此原任务没有提供给模型的记忆凭据；不会用新检索代替")
                if (original.has("messageId")) check(binding(controller.webAnswerMemoryReference(original.getString("messageId"))).toString() == binding.toString()) { "原回答的记忆引用已变化" }
                call(session, "readTaskMemoryReceipt", binding, done) { value ->
                    val receipt = checkedReceipt(value); val frozen = copy(receipt)
                    rows(receipt.getJSONArray("records")).forEach { row -> row.put("readHandle", pinTaskRecord(session, JSONObject().put("receipt", frozen).put("record", copy(row)))) }
                    receipt
                }
            }
            "taskMemory.focus" -> {
                fields(args, "taskHandle"); val (_, original) = selected(args, "taskHandle", "task"); controller.focusTask(original.getString("taskId"))
                done(JSONObject().put("taskId", original.getString("taskId")).put("opened", true).put("executionAuthorized", false), null)
            }
            "taskMemory.source" -> {
                fields(args, "readHandle", "index", "offset"); val (session, original) = selected(args, "readHandle", "task-record")
                val refs = original.getJSONObject("record").optJSONArray("sourceRefs") ?: JSONArray(); val source = copy(refs.getJSONObject(index(args, "index", refs.length()))); val offset = offset(args)
                taskCurrent(session, original, done) { receipt, row ->
                    call(session, "readTaskMemorySource", TaskMemoryEvidenceGuard.reviewed(receipt, row).put("sourceRef", source).put("offset", offset).put("maxChars", 8000), done) { value ->
                        if (value.optString("status") == "current") check(value.optJSONObject("readRange")?.optInt("offset", -1) == offset && value.getString("text").length <= 8000) { "原文读取范围与本次选择不一致" }
                        val next = value.opt("nextOffset")
                        copy(value).put("offset", offset).put("hasMore", next is Number && next.toLong() > offset).put("sourceReadLimited", false)
                    }
                }
            }
            "taskMemory.source.focus" -> {
                fields(args, "readHandle", "index"); val (session, original) = selected(args, "readHandle", "task-record")
                val refs = original.getJSONObject("record").getJSONArray("sourceRefs"); val source = refs.getJSONObject(index(args, "index", refs.length()))
                require(source.optString("kind") == "task_evidence"); val match = Regex("task:([A-Za-z0-9_-]+):verified_result").matchEntire(source.getString("sourceId")) ?: error("原来源不是可打开的任务结果")
                taskCurrent(session, original, done) { _, _ -> controller.focusTask(match.groupValues[1]); done(JSONObject().put("taskId", match.groupValues[1]).put("opened", true).put("executionAuthorized", false), null) }
            }
            "taskMemory.replacements" -> {
                fields(args, "readHandle"); val (session, original) = selected(args, "readHandle", "task-record")
                taskCurrent(session, original, done) { receipt, row -> call(session, "listTaskMemoryReplacements", TaskMemoryEvidenceGuard.reviewed(receipt, row), done) { result ->
                    rows(result.optJSONArray("records")).forEach { kept -> kept.put("replacementHandle", pin(session, "replacement", JSONObject().put("original", original).put("record", copy(kept)))) }; result
                } }
            }
            "taskMemory.review" -> {
                fields(args, "readHandle", "operation", "text", "replacementHandle"); val (session, original) = selected(args, "readHandle", "task-record"); val operation = text(args, "operation")
                require(operation in setOf("correct", "forget", "replace"))
                val kept = if (operation == "replace") selected(args, "replacementHandle", "replacement").let { (same, replacement) ->
                    check(same === session && replacement.getJSONObject("original").toString() == original.toString()) { "替代选择不属于这个原版本" }; replacement.getJSONObject("record")
                } else null
                taskCurrent(session, original, done) { receipt, row ->
                    val payload = when (operation) {
                        "correct" -> TaskMemoryEvidenceGuard.correction(receipt, row, text(args, "text", 4000))
                        "replace" -> TaskMemoryEvidenceGuard.replacement(receipt, row, kept!!)
                        else -> TaskMemoryEvidenceGuard.reviewed(receipt, row)
                    }
                    val command = when (operation) { "correct" -> "correctTaskMemoryReviewed"; "replace" -> "supersedeTaskMemoryReviewed"; else -> "forgetTaskMemoryReviewed" }
                    done(JSONObject().put("reviewHandle", pin(session, "task-review", JSONObject().put("original", original).put("payload", payload).put("command", command))).put("operation", operation).put("before", row).put("afterText", if (kept == null) payload.optString("text") else kept.optString("text")).put("executionAuthorized", false), null)
                }
            }
            "taskMemory.confirm" -> {
                fields(args, "reviewHandle"); val (session, review) = selected(args, "reviewHandle", "task-review", consume = true)
                taskCurrent(session, review.getJSONObject("original"), done) { receipt, _ ->
                    call(session, review.getString("command"), review.getJSONObject("payload"), done) { result ->
                        val feedback = TaskMemoryEvidenceGuard.feedback(result, receipt, review.getString("command"), review.getJSONObject("payload")); retire(session); result.put("feedback", feedback)
                    }
                }
            }
            else -> error("未接入的原来源操作")
        }
    }
    private fun conflictCurrent(session: Session, original: JSONObject, done: (JSONObject?, String?) -> Unit, next: () -> Unit) {
        call(session, "cognitionProjection", JSONObject().put("subjectId", original.getString("subjectId")), { value, error ->
            if (value == null) done(null, error) else try {
                val live = rows(value.optJSONArray("pendingConflicts"))
                for (row in rows(original.getJSONArray("records"))) check(live.any { MemoryLearningReviewGuard.sameReview(row, it) }) { "原冲突版本已变化，请重新读取整组" }
                next()
            } catch (failure: Exception) { done(null, failure.message) }
        })
    }
}
