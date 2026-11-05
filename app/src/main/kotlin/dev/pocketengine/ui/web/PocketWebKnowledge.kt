package dev.pocketengine.ui.web

import dev.pocketengine.ui.MemoryLearningReviewGuard
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.assertionReviewDate
import dev.pocketengine.ui.assertionReviewKeepsSources
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Presentation handles refer to existing owner records and learning jobs, never a second store. */
internal class PocketWebKnowledge(private val controller: PocketWorldController) {
    private data class Pin(val body: String, val owner: Any, val scope: Any, val expires: Long)
    private val reads = linkedMapOf<String, Pin>()
    private val reviews = linkedMapOf<String, Pin>()
    private val sources = linkedMapOf<String, Pin>()
    private val choices = linkedMapOf<String, Pin>()
    private val identities = linkedMapOf<String, Pin>()
    private data class Operation(val owner: Any, val payload: JSONObject, val domain: String = "memoryReview", var requestId: String = "", var result: JSONObject? = null, var error: String? = null, var settled: Boolean = false)
    private val operations = linkedMapOf<String, Operation>()
    private var generation = 0L
    companion object {
        val COMMANDS = setOf("knowledge.snapshot", "knowledge.get", "knowledge.source", "knowledge.parent", "knowledge.review", "knowledge.confirm", "knowledge.close", "knowledge.namespaces", "knowledge.namespace", "knowledge.history", "knowledge.capacity", "knowledge.assertion.prepare", "knowledge.assertion.review", "knowledge.consolidation.review", "knowledge.consolidation.start", "knowledge.consolidation.query", "knowledge.consolidation.stop", "learning.snapshot", "learning.jobs", "learning.job", "learning.episode.inspect", "learning.episode.retain", "learning.create", "learning.run.review", "learning.run.confirm", "learning.operation.query", "learning.operation.stop", "learning.stop", "learning.skip.review", "learning.skip.confirm")
    }
    fun clear() { generation++; reads.clear(); reviews.clear(); sources.clear(); choices.clear(); identities.clear(); operations.clear() }
    private fun fields(value: JSONObject, vararg keys: String) { require(value.keys().asSequence().all { it in keys }) { "原审核参数含不适用字段" } }
    private fun text(value: JSONObject, key: String, max: Int = 128): String { val s = value.opt(key); require(s is String && s.isNotBlank() && s.length <= max && '\u0000' !in s) { "请重新选择原记录" }; return s }
    private fun number(value: JSONObject, key: String): Long { val n = value.opt(key); require((n is Int || n is Long) && (n as Number).toLong() > 0) { "原修订无效" }; return (n as Number).toLong() }
    private fun pin(book: MutableMap<String, Pin>, value: JSONObject): String { while (book.size >= 24) book.remove(book.keys.first()); val id = UUID.randomUUID().toString(); book[id] = Pin(value.toString(), controller.webWindowTicket(), controller.webMemoryScopeTicket(), System.currentTimeMillis() + 600_000); return id }
    private fun selected(book: MutableMap<String, Pin>, id: String, consume: Boolean = false): JSONObject { val value = book[id] ?: error("原审核窗口已关闭，请重新读取"); check(controller.webPrivateOwnerVisible() && value.owner == controller.webWindowTicket() && value.scope == controller.webMemoryScopeTicket() && value.expires >= System.currentTimeMillis()) { "原审核窗口已变化或过期" }; if (consume) book.remove(id); return JSONObject(value.body) }
    private fun call(domain: String, command: String, args: JSONObject, done: (JSONObject?, String?) -> Unit, project: (JSONObject) -> JSONObject = { it }) {
        val request = generation; val scope = controller.webMemoryScopeTicket()
        controller.requestOwnerDomainReceipt(domain, command, args) { result, error ->
            if (request != generation || domain == "memoryReview" && scope != controller.webMemoryScopeTicket()) done(null, "原页面已变化，请查询原结果")
            else if (result == null || error != null) done(null, error ?: "原操作没有确定回执")
            else runCatching { project(result) }.fold({ done(PocketWebState.publicValue(it) as JSONObject, null) }, { done(null, it.message ?: "原回执不可核对") })
        }
    }
    private fun row(value: JSONObject) = value.getJSONObject(if (value.getBoolean("candidate")) "candidateRecord" else "record")
    private fun target(value: JSONObject) = JSONObject().put("id", row(value).getString("id"))
    private fun current(value: JSONObject, done: (JSONObject?, String?) -> Unit, continuation: (JSONObject) -> Unit) {
        val request = generation; val scope = controller.webMemoryScopeTicket()
        controller.requestOwnerDomainReceipt("memoryReview", if (value.getBoolean("candidate")) "getCandidate" else "get", target(value)) { result, error ->
            if (request != generation || scope != controller.webMemoryScopeTicket()) done(null, "原页面已变化，请重新读取")
            else if (result == null) done(null, error) else try {
                check(MemoryLearningReviewGuard.sameReview(row(value), result.getJSONObject(if (value.getBoolean("candidate")) "candidate" else "record"))) { "原记忆或候选已变化，请重新读取" }
                continuation(result)
            } catch (failure: Exception) { done(null, failure.message) }
        }
    }
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到原私人管理页" }
        when (action) {
            "knowledge.snapshot" -> {
                fields(args); call("memoryReview", "snapshot", JSONObject(), { snapshot, error ->
                    if (snapshot == null) done(null, error) else call("memoryReview", "list", JSONObject(), { records, readError ->
                        if (records == null) done(null, readError) else call("memoryReview", "listCandidates", JSONObject(), done) { candidates -> snapshot.put("records", records.getJSONArray("records")).put("candidates", candidates.getJSONArray("candidates")) }
                    })
                })
            }
            "knowledge.namespaces" -> { fields(args); call("memoryReview", "namespaceChoices", JSONObject(), done) { result ->
                choices.clear(); val rows = result.getJSONArray("choices"); for (i in 0 until rows.length()) rows.getJSONObject(i).put("choiceHandle", pin(choices, rows.getJSONObject(i))); result
            } }
            "knowledge.namespace" -> {
                fields(args, "choiceHandle"); val choice = if (args.optString("choiceHandle").isBlank()) null else selected(choices, text(args, "choiceHandle"))
                clear(); controller.selectMemoryNamespace(choice, done)
            }
            "knowledge.history" -> { fields(args); call("memoryReview", "listCandidateHistory", JSONObject(), done) }
            "knowledge.capacity" -> { fields(args); call("memoryReview", "candidateCapacity", JSONObject(), done) }
            "knowledge.get" -> {
                fields(args, "id", "candidate"); require(args.opt("candidate") is Boolean); val candidate = args.getBoolean("candidate")
                call("memoryReview", if (candidate) "getCandidate" else "get", JSONObject().put("id", text(args, "id")), done) { result ->
                    // Keep the original candidate object separate from this presentation discriminator.
                    val data = result.getJSONObject(if (candidate) "candidate" else "record")
                    val original = JSONObject().put("candidate", candidate).put(if (candidate) "candidateRecord" else "record", data)
                    result.put("readHandle", pin(reads, original)).put("candidateSelected", candidate)
                }
            }
            "knowledge.source" -> {
                fields(args, "readHandle", "index", "offset"); val original = readSelection(text(args, "readHandle")); val refs = row(original).optJSONArray("sourceRefs") ?: JSONArray(); val index = args.opt("index"); require(index is Int && index in 0 until refs.length())
                call("memoryReview", "openSource", refs.getJSONObject(index), done) { result ->
                    val content = result.getString("text"); val rawOffset = args.opt("offset"); require(rawOffset is Int && rawOffset >= 0); val offset = rawOffset; require(offset <= content.length && (offset == content.length || !Character.isLowSurrogate(content[offset])))
                    var end = minOf(offset + 8000, content.length); if (end < content.length && end > offset && Character.isHighSurrogate(content[end - 1])) end--
                    result.put("text", content.substring(offset, end)).put("index", index).put("offset", offset).put("nextOffset", end).put("totalChars", content.length).put("hasMore", end < content.length)
                }
            }
            "knowledge.parent" -> {
                fields(args, "readHandle", "index"); val original = readSelection(text(args, "readHandle")); check(original.getBoolean("candidate")); val candidate = row(original)
                check(MemoryLearningReviewGuard.validCandidateParents(candidate)); val refs = candidate.getJSONArray("recordRefs"); val index = args.opt("index"); require(index is Int && index in 0 until refs.length()); val reference = refs.getJSONObject(index)
                current(original, done) { call("memoryReview", "getParentRecord", reference, done) { result ->
                    val parent = result.getJSONObject("record"); check(parent.getString("id") == reference.getString("id") && MemoryLearningReviewGuard.revision(parent) == MemoryLearningReviewGuard.revision(reference)) { "父记忆版本已变化" }; result
                } }
            }
            "knowledge.assertion.prepare" -> {
                fields(args, "readHandle", "query"); val original = readSelection(text(args, "readHandle")); check(original.getBoolean("candidate") && row(original).optString("status") == "pending")
                val query = args.optString("query"); require(query.length <= 160)
                current(original, done) { call("memoryReview", "prepareAssertionReview", target(original).put("revision", row(original).getLong("revision")).put("query", query), done) { result ->
                    check(result.opt("formalMemorySaved") == false && result.opt("sourceProofIssued") == false && result.opt("executionAuthorized") == false && MemoryLearningReviewGuard.sameReview(row(original), result.optJSONObject("candidate")))
                    val values = result.getJSONArray("identities")
                    for (i in 0 until values.length()) { val identity = values.getJSONObject(i); identity.put("choiceHandle", pin(identities, JSONObject().put("original", original).put("selection", identity.getJSONObject("selection")))) }
                    result
                } }
            }
            "knowledge.assertion.review" -> {
                fields(args, "readHandle", "subject", "relationTarget", "kind", "predicate", "object", "condition", "eventTime", "validFrom", "validTo", "reviewAfter")
                val original = readSelection(text(args, "readHandle")); check(original.getBoolean("candidate") && row(original).optString("status") == "pending")
                val kind = text(args, "kind"); require(kind in setOf("inferred", "told", "imagined"))
                val payload = target(original).put("revision", row(original).getLong("revision"))
                    .put("subject", identityChoice(original, args.getJSONObject("subject"), "subject"))
                    .put("relationTarget", if (args.isNull("relationTarget")) JSONObject.NULL else identityChoice(original, args.getJSONObject("relationTarget"), "object"))
                    .put("kind", kind).put("predicate", text(args, "predicate", 160)).put("object", text(args, "object", 4000))
                val condition = args.opt("condition"); require(condition is String && condition.length <= 1000); payload.put("condition", condition)
                for (key in listOf("eventTime", "validFrom", "validTo", "reviewAfter")) { val value = args.opt(key); require(value is String && value.length <= 64); payload.put(key, assertionReviewDate(value)) }
                current(original, done) { done(JSONObject().put("reviewHandle", pin(reviews, JSONObject().put("original", original).put("command", "reviewCandidateAssertion").put("payload", payload))).put("operation", "assertion").put("before", row(original)).put("assertion", payload).put("executionAuthorized", false), null) }
            }
            "knowledge.consolidation.review" -> {
                fields(args, "readHandles"); val handles = args.getJSONArray("readHandles"); require(handles.length() in 2..20); val frozen = JSONArray()
                for (i in 0 until handles.length()) { val original = readSelection(handles.getString(i)); check(!original.getBoolean("candidate")); frozen.put(row(original)) }
                call("memoryReview", "list", JSONObject(), done) { result ->
                    val payload = MemoryLearningReviewGuard.consolidationPayload(frozen, result.getJSONArray("records"))
                    JSONObject().put("reviewHandle", pin(reviews, JSONObject().put("consolidation", frozen).put("payload", payload))).put("operation", "consolidation").put("records", frozen).put("modelCalled", false)
                }
            }
            "knowledge.consolidation.start" -> {
                fields(args, "reviewHandle"); val review = selected(reviews, text(args, "reviewHandle"), true); val frozen = review.getJSONArray("consolidation")
                check(operations.values.none { !it.settled }) { "请先核对正在进行的原归纳" }
                call("memoryReview", "list", JSONObject(), { result, error ->
                    if (result == null) done(null, error) else try {
                        // The callback below is invoked on the original main-thread request chain.
                        val payload = review.getJSONObject("payload")
                        while (operations.size >= 8) operations.remove(operations.keys.first())
                        val id = UUID.randomUUID().toString(); val operation = Operation(controller.webWindowTicket(), payload); operations[id] = operation
                        try { operation.requestId = controller.requestOwnerDomainReceipt("memoryReview", "proposeConsolidation", payload) { value, failure ->
                            operation.settled = true
                            if (value != null) {
                                val envelope = JSONObject().put("requestId", operation.requestId).put("ok", true).put("result", value)
                                if (MemoryLearningReviewGuard.consolidationCandidate(operation.requestId, payload, envelope) != null) operation.result = value else operation.error = "原归纳回执未完整核对，请刷新候选；未自动重试"
                            } else operation.error = failure ?: "原归纳结果待核对"
                        } } catch (failure: Exception) { operation.settled = true; operation.error = failure.message; throw failure }
                        done(JSONObject().put("operationHandle", id).put("requestId", operation.requestId).put("settled", operation.settled), null)
                    } catch (failure: Exception) { done(null, failure.message) }
                }) { current -> MemoryLearningReviewGuard.consolidationPayload(frozen, current.getJSONArray("records")); current }
            }
            "knowledge.consolidation.query", "knowledge.consolidation.stop" -> {
                fields(args, "operationHandle"); val operation = operations[text(args, "operationHandle")] ?: error("原归纳窗口已关闭，请刷新候选")
                check(operation.domain == "memoryReview" && operation.owner == controller.webWindowTicket())
                if (action.endsWith(".stop")) call("memoryReview", "cancelConsolidation", JSONObject().put("requestId", operation.requestId), done)
                else done(JSONObject().put("requestId", operation.requestId).put("settled", operation.settled).put("result", operation.result?.let { PocketWebState.publicValue(it) } ?: JSONObject.NULL).put("error", operation.error ?: JSONObject.NULL), null)
            }
            "knowledge.review" -> {
                fields(args, "readHandle", "operation", "text", "reason", "core", "draft"); val original = readSelection(text(args, "readHandle")); val operation = text(args, "operation")
                require(operation in if (original.getBoolean("candidate")) setOf("accept", "reject", "edit", "archive", "reconsider") else setOf("correct", "forget", "core"))
                val request = JSONObject().put("id", row(original).getString("id")); val revision = row(original).getLong("revision")
                val command = when (operation) {
                    "correct" -> { request.put("expectedRevision", revision).put("text", text(args, "text", 4000)); "correctReviewed" }
                    "forget" -> { request.put("expectedRevision", revision); "forgetReviewed" }
                    "core" -> { require(args.opt("core") is Boolean); request.put("revision", revision).put("core", args.getBoolean("core")); "setCore" }
                    "accept" -> { request.put("revision", revision); "acceptCandidate" }
                    "reject" -> { request.put("revision", revision).put("reason", text(args, "reason", 1000)); "rejectCandidate" }
                    "archive" -> "archiveCandidates"
                    "reconsider" -> "reconsiderRejected"
                    else -> { request.put("revision", revision).put("changes", args.getJSONObject("draft")); "editCandidate" }
                }
                current(original, done) {
                    val checked = if (original.getBoolean("candidate")) MemoryLearningReviewGuard.payload(command, row(original), row(original), if (operation == "edit") args.getJSONObject("draft") else request) else if (operation == "forget") MemoryLearningReviewGuard.forgetPayload(row(original), row(original)) else request
                    val body = JSONObject().put("original", original).put("command", command).put("payload", checked)
                    done(JSONObject().put("reviewHandle", pin(reviews, body)).put("operation", operation).put("before", row(original)).put("afterText", checked.optString("text", checked.optJSONObject("changes")?.optString("text") ?: "")).put("core", checked.opt("core")).put("reason", checked.optString("reason")).put("executionAuthorized", false), null)
                }
            }
            "knowledge.confirm" -> {
                fields(args, "reviewHandle"); val review = selected(reviews, text(args, "reviewHandle"), true); val original = review.getJSONObject("original")
                current(original, done) { call("memoryReview", review.getString("command"), review.getJSONObject("payload"), done) { result ->
                    if (review.getString("command") == "reviewCandidateAssertion") {
                        val candidate = result.getJSONObject("candidate")
                        check(result.opt("formalMemorySaved") == false && result.opt("sourceProofIssued") == false && result.opt("executionAuthorized") == false && candidate.getString("id") == row(original).getString("id") && candidate.optString("status") == "pending" && MemoryLearningReviewGuard.revision(candidate) == MemoryLearningReviewGuard.revision(row(original)) + 1 && assertionReviewKeepsSources(row(original), candidate)) { "身份核对结果待确认，请重新读取原候选" }
                    } else if (original.getBoolean("candidate") || review.getString("command") == "forgetReviewed") check(MemoryLearningReviewGuard.validMutationResult(review.getString("command"), review.getJSONObject("payload"), result)) { "原处理效果未完整核对，请读取原记录；没有重复提交" }
                    result
                } }
            }
            "knowledge.close" -> { fields(args, "handle"); val id = text(args, "handle"); reads.remove(id); reviews.remove(id); done(JSONObject().put("closed", true), null) }
            "learning.snapshot" -> { fields(args); call("learning", "snapshot", JSONObject(), done) }
            "learning.jobs" -> { fields(args, "cursor"); call("learning", "listLearningJobs", JSONObject().put("cursor", args.optString("cursor")).put("limit", 20), done) }
            "learning.job" -> { fields(args, "taskId"); call("learning", "getLearningJob", JSONObject().put("taskId", text(args, "taskId")), done) }
            "learning.stop" -> {
                fields(args, "taskId", "runId", "epoch")
                val run = args.opt("runId"); val epoch = args.opt("epoch"); require(run is String && epoch is Int && epoch >= 0 && (run.isEmpty() && epoch == 0 || run.matches(Regex("[a-f0-9-]{36}")) && epoch > 0)) { "请重新读取原学习执行身份" }
                call("learning", "stopLearningJob", JSONObject().put("taskId", text(args, "taskId")).put("runId", run).put("epoch", epoch), done)
            }
            "learning.episode.inspect" -> {
                fields(args, "taskId", "runId", "epoch"); val input = JSONObject().put("taskId", text(args, "taskId")).put("runId", text(args, "runId")).put("epoch", number(args, "epoch"))
                call("learning", "inspectTaskEpisode", input, done) { result -> result.put("reviewHandle", pin(reviews, JSONObject().put("episode", input.put("expectedEpisodeDigest", result.getString("episodeDigest"))))) }
            }
            "learning.episode.retain" -> {
                fields(args, "reviewHandle"); val original = selected(reviews, text(args, "reviewHandle"), true).getJSONObject("episode")
                call("learning", "retainTaskEpisode", original, done) { result -> if (result.optBoolean("learningAllowed")) result.put("sourceHandle", pin(sources, result)); result }
            }
            "learning.create" -> {
                fields(args, "sourceHandles"); val ids = args.getJSONArray("sourceHandles"); require(ids.length() in 1..20); val refs = JSONArray(); val unique = mutableSetOf<String>()
                val references = mutableSetOf<dev.pocketengine.features.memory.SourceRef>()
                for (i in 0 until ids.length()) { val id = ids.getString(i); require(unique.add(id)); val ref = selected(sources, id).getJSONObject("sourceRef"); require(references.add(dev.pocketengine.features.memory.SourceRef.fromJson(ref))) { "同一原来源只需选择一次" }; refs.put(ref) }
                unique.forEach { sources.remove(it) }; call("learning", "createLearningJob", JSONObject().put("sourceRefs", refs), done)
            }
            "learning.run.review", "learning.skip.review" -> {
                fields(args, "taskId", "maxBatches"); val task = text(args, "taskId"); val run = action == "learning.run.review"; val count = if (run) number(args, "maxBatches") else 1L; require(count in 1..20)
                call("learning", "getLearningJob", JSONObject().put("taskId", task), done) { result ->
                    check(result.optBoolean(if (run) "canContinue" else "canSkipBatch")) { "原批次目前不能执行该操作，请核对原水位" }
                    val input = JSONObject().put("taskId", task).put("revision", result.getJSONObject("job").getLong("revision")).put(if (run) "confirmModelUse" else "confirmUnknownUse", true)
                    if (run) input.put("maxBatches", count)
                    result.put("reviewHandle", pin(reviews, JSONObject().put("command", if (run) "runLearningJob" else "skipLearningJobBatch").put("payload", input))).put("reviewMaxBatches", count)
                }
            }
            "learning.run.confirm", "learning.skip.confirm" -> {
                fields(args, "reviewHandle"); val review = selected(reviews, text(args, "reviewHandle"), true); val command = if (action == "learning.run.confirm") "runLearningJob" else "skipLearningJobBatch"; check(review.getString("command") == command)
                if (command == "skipLearningJobBatch") call("learning", command, review.getJSONObject("payload"), done)
                else {
                    check(operations.values.none { it.domain == "learning" && !it.settled }) { "请先核对正在进行的原学习请求" }
                    while (operations.size >= 8) operations.remove(operations.keys.first())
                    val id = UUID.randomUUID().toString(); val operation = Operation(controller.webWindowTicket(), review.getJSONObject("payload"), "learning"); operations[id] = operation
                    try {
                        operation.requestId = controller.requestOwnerDomainReceipt("learning", command, operation.payload) { result, error -> operation.settled = true; operation.result = result; operation.error = error }
                        done(JSONObject().put("operationHandle", id).put("requestId", operation.requestId).put("taskId", operation.payload.getString("taskId")).put("settled", operation.settled).put("error", operation.error ?: JSONObject.NULL), null)
                    } catch (failure: Exception) { operation.settled = true; operation.error = failure.message; done(null, failure.message) }
                }
            }
            "learning.operation.query", "learning.operation.stop" -> {
                fields(args, "operationHandle"); val operation = operations[text(args, "operationHandle")] ?: error("原学习请求窗口已关闭，请读取原任务状态")
                check(operation.domain == "learning" && operation.owner == controller.webWindowTicket())
                if (action.endsWith(".stop")) call("learning", "cancelLearning", JSONObject().put("requestId", operation.requestId), done)
                else done(JSONObject().put("requestId", operation.requestId).put("taskId", operation.payload.getString("taskId")).put("settled", operation.settled).put("result", operation.result?.let { PocketWebState.publicValue(it) } ?: JSONObject.NULL).put("error", operation.error ?: JSONObject.NULL), null)
            }
            else -> error("未接入的原记忆或学习操作")
        }
    }
    private fun readSelection(id: String): JSONObject {
        val saved = selected(reads, id)
        return if (saved.getBoolean("candidate")) JSONObject().put("candidate", true).put("candidateRecord", saved.getJSONObject("candidateRecord")) else saved
    }
    private fun identityChoice(original: JSONObject, input: JSONObject, role: String): JSONObject {
        fields(input, "mode", "type", "label", "choiceHandle")
        return when (text(input, "mode")) {
            "owner" -> JSONObject().put("mode", "owner")
            "new" -> { val type = text(input, "type"); require(type in setOf("person", "entity")); JSONObject().put("mode", "new").put("type", type).put("label", text(input, "label", 160)) }
            "candidate" -> { val key = if (role == "subject") "subjectId" else "objectId"; check(!row(original).optJSONObject("assertion")?.optString(key).isNullOrBlank()); JSONObject().put("mode", "candidate").put("role", role).put("label", text(input, "label", 160)) }
            "existing" -> { val identity = selected(identities, text(input, "choiceHandle")); check(MemoryLearningReviewGuard.sameReview(row(original), row(identity.getJSONObject("original")))); JSONObject(identity.getJSONObject("selection").toString()).put("label", text(input, "label", 160)) }
            else -> error("请明确选择原主体或独立新身份")
        }
    }
}
