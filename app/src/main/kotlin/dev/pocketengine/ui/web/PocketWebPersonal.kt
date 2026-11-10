package dev.pocketengine.ui.web

import dev.pocketengine.ui.MemoryLearningReviewGuard
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Personal settings and a subject-filtered view of the original owner memory book.
 * Handles retain exact native rows; this facade owns no memory or inference store. */
internal class PocketWebPersonal(private val controller: PocketWorldController) {
    private data class Pin(val body: String, val owner: Any, val expires: Long)
    private val reads = linkedMapOf<String, Pin>()
    private val reviews = linkedMapOf<String, Pin>()
    private val receipts = linkedMapOf<String, Pin>()
    private val avatar = PocketWebPersonalAvatar(controller)
    @Volatile private var generation = 0L

    companion object {
        val COMMANDS = setOf(
            "profile.identity", "profile.snapshot", "profile.save", "profile.avatar.pick", "profile.avatar.result", "profile.avatar.close", "profile.memory.get",
            "profile.memory.review", "profile.memory.createReview", "profile.memory.confirm", "profile.memory.source", "profile.memory.close",
            "profile.memory.query", "profile.memory.undo", "profile.memory.controls", "profile.memory.reading", "profile.memory.learning",
            "profile.preferences.control", "profile.conversation.read", "profile.conversation.save"
        )
    }

    fun clear() { generation++; reads.clear(); reviews.clear(); receipts.clear(); avatar.clear() }
    fun background() { generation++; reads.clear(); reviews.clear(); receipts.clear(); avatar.background() }
    private fun fields(value: JSONObject, vararg allowed: String) {
        require(value.keys().asSequence().all { it in allowed }) { "个人资料操作含不适用字段" }
    }
    private fun text(value: JSONObject, key: String, maximum: Int = 128): String {
        val raw = value.opt(key)
        require(raw is String && raw.isNotBlank() && raw.length <= maximum && '\u0000' !in raw) { "请重新填写或选择原内容" }
        return raw
    }
    private fun pin(book: MutableMap<String, Pin>, body: JSONObject): String {
        while (book.size >= 24) book.remove(book.keys.first())
        val id = UUID.randomUUID().toString()
        book[id] = Pin(body.toString(), controller.webWindowTicket(), System.currentTimeMillis() + 600_000)
        return id
    }
    private fun selected(book: MutableMap<String, Pin>, id: String, consume: Boolean = false): JSONObject {
        val pin = book[id] ?: error("原个人记忆窗口已关闭，请重新读取")
        check(controller.webPrivateOwnerVisible() && pin.owner == controller.webWindowTicket() && pin.expires >= System.currentTimeMillis()) { "原个人记忆窗口已变化或过期" }
        if (consume) book.remove(id)
        return JSONObject(pin.body)
    }
    private fun row(original: JSONObject): JSONObject = original.getJSONObject(if (original.getBoolean("candidate")) "candidateRecord" else "record")
    private fun prepare(review: JSONObject): String {
        val handle = pin(reviews, review)
        while (receipts.size >= 24) receipts.remove(receipts.keys.first())
        receipts[handle] = reviews.getValue(handle).copy(body = JSONObject().put("status", "not_submitted").put("review", review).toString())
        return handle
    }
    private fun recordReceipt(handle: String, status: String, result: JSONObject) {
        val old = receipts[handle] ?: return
        val body = JSONObject(old.body).put("status", status).put("result", result)
        receipts[handle] = old.copy(body = body.toString())
    }
    private fun receiptView(handle: String, receipt: JSONObject): JSONObject {
        val result = receipt.optJSONObject("result")?.let { JSONObject(it.toString()) } ?: JSONObject().put("undoAvailable", false)
        return result.put("status", receipt.getString("status")).put("receiptHandle", handle)
    }
    private fun callProfile(command: String, args: JSONObject, done: (JSONObject?, String?) -> Unit, project: (JSONObject) -> JSONObject = { it }) {
        val request = generation
        controller.requestPersonalProfile(command, args, { request == generation }) { result, error ->
            complete(request, result, error, done, project)
        }
    }
    private fun callMemory(command: String, args: JSONObject, original: JSONObject?, done: (JSONObject?, String?) -> Unit, project: (JSONObject) -> JSONObject = { it }) {
        val request = generation
        controller.requestPersonalMemory(command, args, original, { request == generation }) { result, error ->
            complete(request, result, error, done, project)
        }
    }
    private fun complete(request: Long, result: JSONObject?, error: String?, done: (JSONObject?, String?) -> Unit, project: (JSONObject) -> JSONObject) {
        if (request != generation) done(null, "原个人资料页面已变化，请重新读取当前结果")
        else if (result == null || error != null) done(null, error ?: "没有收到可核对的原操作回执")
        else runCatching { project(result) }.fold(
            { done(PocketWebState.publicValue(it) as JSONObject, null) },
            { done(null, it.message ?: "操作结果未完整核对，请刷新；没有自动重复提交") }
        )
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(if (action == "profile.identity") controller.webUiCanInteract() else controller.webPrivateOwnerVisible()) { "请在原本机窗口打开个人资料" }
        if (action.startsWith("profile.avatar.")) { avatar.dispatch(action, args, done); return }
        when (action) {
            "profile.identity", "profile.snapshot" -> {
                fields(args)
                callProfile(if (action == "profile.identity") "identity" else "snapshot", args, done)
            }
            "profile.memory.controls" -> { fields(args); callProfile("memoryControls", args, done) }
            "profile.memory.reading", "profile.preferences.control" -> {
                fields(args, "expectedRevision", "enabled")
                callProfile(if (action == "profile.memory.reading") "memoryReading" else "preferenceControl", args, done)
            }
            "profile.memory.learning" -> {
                fields(args, "expectedRevision", "enabled", "maxUserSourcesPerTask", "confirmedModelUse")
                callProfile("memoryLearning", args, done)
            }
            "profile.conversation.read", "profile.conversation.save" -> {
                if (action == "profile.conversation.read") fields(args, "conversationId") else fields(args, "conversationId", "expectedRevision", "enabled")
                text(args, "conversationId")
                callProfile(if (action == "profile.conversation.read") "conversationRead" else "conversationSave", args, done)
            }
            "profile.save" -> {
                fields(args, "expectedRevision", "section", "values")
                val expected = args.opt("expectedRevision")
                require((expected is Int || expected is Long) && (expected as Number).toLong() > 0) { "请刷新当前个人资料版本" }
                callProfile("save", args, done) { result ->
                    check(result.opt("saved") == true && result.opt("persisted") == true && result.getJSONObject("profile").getLong("revision") == Math.addExact((expected as Number).toLong(), 1)) { "保存回执不完整，请刷新核对；没有自动重发" }
                    result
                }
            }
            "profile.memory.get" -> {
                fields(args, "id", "candidate")
                require(args.opt("candidate") is Boolean) { "请选择正式记忆或待确认内容" }
                val candidate = args.getBoolean("candidate")
                callMemory(if (candidate) "getCandidate" else "get", JSONObject().put("id", text(args, "id")), null, done) { result ->
                    val original = JSONObject().put("candidate", candidate)
                        .put(if (candidate) "candidateRecord" else "record", result.getJSONObject(if (candidate) "candidate" else "record"))
                    result.optJSONArray("relatedRecords")?.let { original.put("relatedRecords", it) }
                    result.put("readHandle", pin(reads, original)).put("candidateSelected", candidate)
                }
            }
            "profile.memory.createReview" -> {
                fields(args, "text", "expiresAt")
                val content = text(args, "text", 4000).trim()
                val expires = args.opt("expiresAt")
                require(expires == null || expires == JSONObject.NULL || (expires is Int || expires is Long) && (expires as Number).toLong() > System.currentTimeMillis()) { "请选择未来的有效期" }
                val payload = JSONObject().put("text", content).put("expiresAt", expires ?: JSONObject.NULL)
                val handle = prepare(JSONObject().put("command", "createPersonalNote").put("payload", payload))
                done(JSONObject().put("reviewHandle", handle).put("receiptHandle", handle).put("operation", "create").put("changeKind", "add").put("afterText", content).put("expiresAt", expires ?: JSONObject.NULL), null)
            }
            "profile.memory.source" -> {
                fields(args, "readHandle", "index", "offset")
                val original = selected(reads, text(args, "readHandle"))
                val refs = row(original).optJSONArray("sourceRefs") ?: JSONArray()
                val index = args.opt("index")
                require(index is Int && index in 0 until refs.length()) { "请选择这条个人记忆的原来源" }
                val offset = args.opt("offset")
                require(offset is Int && offset >= 0) { "原来源位置无效" }
                callMemory("openSource", refs.getJSONObject(index), original, done) { result ->
                    val content = result.getString("text")
                    require(offset <= content.length && (offset == content.length || !Character.isLowSurrogate(content[offset]))) { "原来源位置已变化" }
                    var end = minOf(offset + 8000, content.length)
                    if (end < content.length && end > offset && Character.isHighSurrogate(content[end - 1])) end--
                    result.put("text", content.substring(offset, end)).put("index", index).put("offset", offset)
                        .put("nextOffset", end).put("totalChars", content.length).put("hasMore", end < content.length)
                }
            }
            "profile.memory.review" -> {
                fields(args, "readHandle", "operation", "text", "reason", "core", "draft")
                val original = selected(reads, text(args, "readHandle"))
                val before = row(original)
                val operation = text(args, "operation")
                val candidate = original.getBoolean("candidate")
                require(operation in if (candidate) setOf("accept", "reject") else setOf("correct", "forget", "core")) {
                    "个人页可纠正或遗忘正式记忆、采纳或忽略候选；候选正文和主体请在原记忆审核页一并核对"
                }
                val payload = JSONObject().put("id", before.getString("id"))
                val revision = before.getLong("revision")
                val command = when (operation) {
                    "correct" -> {
                        val corrected = text(args, "text", 4000).trim()
                        require(corrected != before.getString("text")) { "正文没有变化" }
                        payload.put("expectedRevision", revision).put("text", corrected); "correctReviewed"
                    }
                    "forget" -> { payload.put("expectedRevision", revision); "forgetReviewed" }
                    "core" -> {
                        require(args.opt("core") is Boolean)
                        require(args.getBoolean("core") != before.optBoolean("core")) { "重要标记没有变化" }
                        payload.put("revision", revision).put("core", args.getBoolean("core")); "setCore"
                    }
                    "accept" -> { payload.put("revision", revision); "acceptCandidate" }
                    else -> { payload.put("revision", revision).put("reason", text(args, "reason", 1000)); "rejectCandidate" }
                }
                callMemory(if (candidate) "getCandidate" else "get", JSONObject().put("id", before.getString("id")), original, done) {
                    val checked = if (candidate) MemoryLearningReviewGuard.payload(command, before, before, payload)
                        else if (operation == "forget") MemoryLearningReviewGuard.forgetPayload(before, before) else payload
                    val review = JSONObject().put("original", original).put("command", command).put("payload", checked)
                    val handle = prepare(review)
                    JSONObject().put("reviewHandle", handle).put("receiptHandle", handle).put("operation", operation).put("before", before)
                        .put("relatedRecords", original.optJSONArray("relatedRecords") ?: JSONArray()).put("changeKind", if (operation == "correct") "update" else "add")
                        .put("afterText", checked.optString("text")).put("core", checked.opt("core"))
                        .put("reason", checked.optString("reason")).put("executionAuthorized", false)
                }
            }
            "profile.memory.confirm" -> {
                fields(args, "reviewHandle")
                val handle = text(args, "reviewHandle")
                check(selected(receipts, handle).getString("status") == "not_submitted") { "原操作已提交，请查询回执" }
                val review = selected(reviews, handle, consume = true)
                val original = review.optJSONObject("original")
                val before = original?.let(::row)
                val command = review.getString("command")
                val payload = review.getJSONObject("payload")
                recordReceipt(handle, "unknown", JSONObject().put("undoAvailable", false).put("message", "原操作已提交，等待真实回执；未自动重发"))
                callMemory(command, payload, original, done) { result ->
                    if (result.optString("status") == "settings_required" && result.opt("saved") == false) {
                        result.put("undoAvailable", false)
                        recordReceipt(handle, "settings_required", result)
                        return@callMemory receiptView(handle, selected(receipts, handle))
                    }
                    if (result.optBoolean("duplicate")) {
                        result.put("undoAvailable", false)
                        recordReceipt(handle, "already_saved", result)
                        return@callMemory receiptView(handle, selected(receipts, handle))
                    }
                    if (command == "createPersonalNote") {
                        val record = result.getJSONObject("record")
                        check(record.getLong("revision") == 1L && record.getString("text") == payload.getString("text")) { "新增记忆的原回执不完整，请查询；没有重发" }
                    } else if (original!!.getBoolean("candidate") || command == "forgetReviewed") {
                        check(MemoryLearningReviewGuard.validMutationResult(command, payload, result)) { "原操作效果未完整核对，请刷新；没有自动重发" }
                    } else {
                        val updated = result.getJSONObject("record")
                        check(updated.getString("id") == before!!.getString("id") && MemoryLearningReviewGuard.revision(updated) == Math.addExact(MemoryLearningReviewGuard.revision(before), 1)) { "原个人记忆保存回执未完整核对，请重新读取" }
                        if (command == "correctReviewed") check(updated.getString("text") == payload.getString("text")) { "原个人记忆正文与保存回执不一致" }
                        if (command == "setCore") check(updated.getBoolean("core") == payload.getBoolean("core")) { "原个人记忆重要标记未确认" }
                    }
                    result.put("undoAvailable", command in setOf("createPersonalNote", "acceptCandidate") && result.optJSONObject("record")?.optLong("revision") == 1L)
                    recordReceipt(handle, "saved", result)
                    receiptView(handle, selected(receipts, handle))
                }
            }
            "profile.memory.query" -> {
                fields(args, "receiptHandle")
                val handle = text(args, "receiptHandle"); val receipt = selected(receipts, handle)
                val result = receiptView(handle, receipt); val saved = result.optJSONObject("record")
                if (receipt.getString("status") != "saved" || !result.optBoolean("undoAvailable") || saved == null) { done(result, null); return }
                callMemory("get", JSONObject().put("id", saved.getString("id")), null, { actual, error ->
                    runCatching {
                        selected(receipts, handle)
                        val current = actual?.optJSONObject("record")
                        val unchanged = error == null && current != null && MemoryLearningReviewGuard.sameReview(saved, current)
                        result.put("undoAvailable", unchanged)
                        if (!unchanged) result.put("undoUnavailableReason", "这条记忆已变化或不可用，保留原保存回执，不覆盖后续修改")
                        recordReceipt(handle, "saved", result)
                        done(PocketWebState.publicValue(result) as JSONObject, null)
                    }.onFailure { done(null, it.message ?: "原回执窗口已关闭") }
                })
            }
            "profile.memory.undo" -> {
                fields(args, "receiptHandle")
                val handle = text(args, "receiptHandle"); val receipt = selected(receipts, handle)
                val result = receiptView(handle, receipt)
                check(receipt.getString("status") == "saved" && result.optBoolean("undoAvailable")) { "此操作没有当前可撤销的新增记录" }
                val saved = result.getJSONObject("record")
                val original = JSONObject().put("candidate", false).put("record", saved)
                recordReceipt(handle, "unknown", JSONObject(result.toString()).put("undoAvailable", false).put("message", "撤销已提交，等待原回执；未重复撤销"))
                callMemory("retractCreatedReviewed", JSONObject().put("id", saved.getString("id")).put("expectedRevision", saved.getLong("revision")), original, done) { actual ->
                    check(actual.optInt("deleted") > 0) { "撤销尚未确认，请查询原回执" }
                    actual.put("undoAvailable", false).put("record", saved)
                    recordReceipt(handle, "reverted", actual)
                    receiptView(handle, selected(receipts, handle))
                }
            }
            "profile.memory.close" -> {
                fields(args, "handle")
                val handle = text(args, "handle")
                reads.remove(handle); reviews.remove(handle)
                done(JSONObject().put("closed", true), null)
            }
            else -> error("未知个人资料操作")
        }
    }
}
