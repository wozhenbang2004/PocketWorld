package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.automation.AutomationModule
import dev.pocketengine.store.OwnerWorkTask
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.objects
import org.json.JSONObject
import java.time.ZoneId
import java.time.Instant
import java.util.UUID

/** Owner presentation only. The original CommitmentBook supplies revisions, evidence and receipts. */
internal class PocketWebCommitments(private val controller: PocketWorldController) {
    private data class Review(val owner: Boolean, val command: String, val input: String, val expires: Long)
    data class WorkLink(val commitmentId: String, val revision: Long, val taskId: String)
    data class LinkedWork(val link: WorkLink, val runId: String, val epoch: Int)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    private var snapshot = JSONObject()
    private val readings = linkedMapOf<String, Pair<String, Long>>()
    private val reviews = linkedMapOf<String, Review>()
    private val receipts = linkedMapOf<String, JSONObject>()

    companion object {
        private val UUID_TEXT = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
        val COMMANDS = setOf("commitments.snapshot", "commitments.read", "commitments.review", "commitments.reviewPolicy",
            "commitments.receipts", "commitments.reviewReceipt", "commitments.reconcileReceipt", "commitments.confirm", "commitments.discard")
    }
    fun clear() { generation++; snapshot = JSONObject(); readings.clear(); reviews.clear(); receipts.clear() }
    private fun fields(input: JSONObject, vararg allowed: String) {
        require(input.keys().asSequence().all { it in allowed }) { "承诺操作包含不适用的字段" }
    }
    private fun text(input: JSONObject, key: String, max: Int = 128, empty: Boolean = false): String {
        val value = input.opt(key)
        require(value is String && value.length <= max && '\u0000' !in value && (empty || value.isNotBlank())) { "请核对$key" }
        return value
    }
    private fun integer(input: JSONObject, key: String, min: Long, max: Long): Long {
        val value = input.opt(key)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() in min..max) { "请核对$key" }
        return value.toLong()
    }
    private fun public(value: JSONObject) = PocketWebState.publicValue(value) as JSONObject
    private fun known(id: String): JSONObject = snapshot.optJSONArray("commitments").objects().singleOrNull { it.optString("id") == id }
        ?: error("这项承诺不在当前读取窗口，请刷新")
    private fun read(handle: String): JSONObject {
        val original = readings[handle]
        check(original != null && original.second > SystemClock.elapsedRealtime()) { "原承诺读取已关闭或过期，请重新读取" }
        return JSONObject(original.first)
    }
    private fun view(record: JSONObject): JSONObject {
        readings.entries.removeAll { it.value.second <= SystemClock.elapsedRealtime() }
        while (readings.size >= 16) readings.remove(readings.keys.first())
        val handle = UUID.randomUUID().toString()
        readings[handle] = record.toString() to SystemClock.elapsedRealtime() + 120_000
        return JSONObject().put("viewHandle", handle).put("commitment", public(record))
    }
    private fun workLink(record: JSONObject): WorkLink {
        val id = text(record, "id", 36); val task = text(record, "taskId", 36)
        require(UUID_TEXT.matches(id) && UUID_TEXT.matches(task) && record.opt("ownerId") == "owner" &&
            record.opt("spaceId") == "private" && record.opt("kind") == "explicit_user" && record.isNull("planId") &&
            record.opt("referenceOnly") == true && record.opt("executionAuthorized") == false) { "原私人单项委托没有可核对的工作关联" }
        return WorkLink(id, integer(record, "revision", 1, Long.MAX_VALUE), task)
    }
    private fun linkedWork(record: JSONObject): LinkedWork {
        val run = text(record, "taskRunId", 36)
        check(record.opt("taskStateAuthority") == "TaskStore" && UUID_TEXT.matches(run)) { "原关联工作未取得确定的执行轮次" }
        return LinkedWork(workLink(record), run, integer(record, "taskEpoch", 1, Int.MAX_VALUE.toLong()).toInt())
    }
    fun holdsLinkedWork(handle: String, original: LinkedWork): Boolean =
        runCatching { linkedWork(read(handle)) == original }.getOrDefault(false)

    /** Only the held owner reading chooses this link. Check the current Book row before a TaskStore read. */
    fun resolveLinkedWork(handle: String, current: () -> Boolean, done: (LinkedWork?, String?) -> Unit) {
        val original = linkedWork(read(handle)); val page = generation
        fun active() = generation == page && current() && controller.webPrivateOwnerVisible()
        controller.runPluginCatalogWork(Runnable {
            val checked = runCatching {
                check(active()) { "原委托阅读已关闭，请重新打开" }
                val automation = controller.nativeButlerHost().features.module("automation") as? AutomationModule
                    ?: error("本机原委托宿主暂不可用")
                // Use the Book-only read; the standard UI projection repeats the full task audit.
                val scope = JSONObject().put("ownerId", "owner").put("spaceId", "private")
                val actual = automation.handle("getCommitment", JSONObject().put("scope", scope).put("id", original.link.commitmentId))
                    .getJSONObject("commitment")
                check(workLink(actual) == original.link) { "原委托或工作关联已变化，请重新读取" }
            }
            main.post {
                if (!active() || !holdsLinkedWork(handle, original)) done(null, "原委托阅读已关闭，请重新打开")
                else checked.fold({ done(original, null) }, { done(null, it.message ?: "未能核对原委托的关联工作") })
            }
        })
    }

    fun requireLinkedWork(original: LinkedWork, raw: JSONObject) {
        val link = original.link
        check(raw.opt("id") == link.taskId && raw.opt("actorId") == "owner" && raw.opt("spaceId") == "private" &&
            raw.opt("kind") == "assistant" && raw.opt("executionMode") == "capabilities" && OwnerWorkTask.isOwnerWork(raw) &&
            raw.isNull("origin") && raw.isNull("parentTaskId") && raw.opt("taskProjection") == "detail_window" &&
            raw.opt("taskCoreReadLimited") == false && raw.opt("contextAvailable") == true &&
            raw.opt("contextWithdrawn") != true && raw.opt("sourceCurrentReadRequired") != true) { "原私人关联工作暂无法核对，未打开其他工作" }
        val work = raw.getJSONObject("ownerWork")
        val conversation = text(raw, "conversationId", 160)
        check(raw.opt("runId") == original.runId && integer(raw, "coordinatorEpoch", 1, Int.MAX_VALUE.toLong()).toInt() == original.epoch) {
            "原工作已进入另一轮，未打开替代记录"
        }
        val bindings = raw.optJSONArray("commitmentBindings")
        val reverse = bindings?.takeIf { it.length() <= 1000 }.objects().singleOrNull { it.opt("commitmentId") == link.commitmentId }
        check(work.opt("workTaskId") == link.taskId && work.opt("conversationId") == conversation &&
            reverse != null) { "这份工作没有原委托的完整关联，未打开其他工作" }
        // Edits or snoozes can advance the Book revision without changing the selected task.
        integer(reverse, "commitmentRevision", 1, link.revision)
    }
    private fun review(owner: Boolean, command: String, input: JSONObject, details: JSONObject = JSONObject()): JSONObject {
        reviews.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
        check(reviews.size < 8) { "请先关闭已有承诺审阅" }
        val handle = UUID.randomUUID().toString()
        reviews[handle] = Review(owner, command, input.toString(), SystemClock.elapsedRealtime() + 120_000)
        return JSONObject().put("reviewHandle", handle).put("command", command).put("input", public(input)).put("details", public(details))
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到前台私人承诺页面" }
        val originalGeneration = generation; val window = controller.webWindowTicket()
        fun current() = originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()
        fun deliver(value: JSONObject?, error: String?) { if (current()) done(value, error) }
        fun request(owner: Boolean, command: String, payload: JSONObject, receive: (JSONObject) -> JSONObject) {
            val callback: (JSONObject?, String?) -> Unit = { value, error ->
                if (current()) {
                    if (value == null || error != null) deliver(null, error ?: "原承诺操作没有返回回执")
                    else try { deliver(receive(value), null) } catch (failure: Exception) { deliver(null, failure.message ?: "原承诺回执待核对") }
                }
            }
            if (owner) controller.requestWebOwnerActionReceipt(command, payload, ::current, callback)
            else controller.requestWebFeatureReceipt("automation", command, payload, ::current, callback)
        }
        when (action) {
            "commitments.snapshot" -> {
                fields(args)
                controller.requestWebFeatureReceipt("automation", "listCommitments", JSONObject(), ::current) { value, error ->
                    if (current()) {
                        if (error != null || value == null) deliver(null, error ?: "原承诺清单尚未读取")
                        else request(false, "getFollowUpPolicy", JSONObject()) { policy ->
                            snapshot = JSONObject().put("commitments", value.getJSONArray("commitments")).put("policy", policy.getJSONObject("policy"))
                                .put("completionEvidenceAvailable", controller.state.feature("automation").optBoolean("completionEvidenceAvailable"))
                            public(snapshot)
                        }
                    }
                }
            }
            "commitments.read" -> {
                fields(args, "id"); val id = text(args, "id"); known(id)
                request(false, "getCommitment", JSONObject().put("id", id)) { value ->
                    val record = value.getJSONObject("commitment"); check(record.getString("id") == id) { "原承诺回执身份不匹配" }; view(record)
                }
            }
            "commitments.discard" -> {
                fields(args, "viewHandle", "reviewHandle")
                if (args.length() == 0) { clear(); done(JSONObject().put("closed", true), null); return }
                else { readings.remove(args.optString("viewHandle")); reviews.remove(args.optString("reviewHandle")) }
                deliver(JSONObject().put("closed", true), null)
            }
            "commitments.review" -> {
                fields(args, "operation", "viewHandle", "title", "details", "taskId", "hours", "date", "time", "zone", "laterOverlap")
                val operation = text(args, "operation", 24)
                require(!args.has("date") || !args.has("hours")) { "日期时间与相对小时只能选择一种" }
                require(args.has("date") || listOf("time", "zone", "laterOverlap").none { args.has(it) }) { "请填写完整跟进日期与时间" }
                require(operation in setOf("create", "update", "snooze", "cancel", "complete"))
                val original = if (operation == "create") null else read(text(args, "viewHandle"))
                if (original != null) check(original.optString("status") !in setOf("completed", "cancelled")) { "原承诺已经结束，请刷新" }
                val input = JSONObject()
                original?.let { input.put("id", it.getString("id")).put("revision", it.getLong("revision")) }
                if (operation in setOf("create", "update")) {
                    val body = JSONObject().put("title", text(args, "title", 200).trim()).put("details", text(args, "details", 4000, true).trim())
                        .put("taskId", text(args, "taskId", 128, true))
                    if (args.has("date")) body.put("dueAt", PocketWebFormTime.instant(args, System.currentTimeMillis()))
                    else if (operation == "create" || args.has("hours")) body.put("dueAt", Math.addExact(System.currentTimeMillis(), integer(args, "hours", 1, 8760) * 3_600_000))
                    if (operation == "create") body.put("kind", "explicit_user")
                    input.put(if (operation == "create") "commitment" else "patch", body)
                } else if (operation == "snooze") input.put("until", Math.addExact(System.currentTimeMillis(), integer(args, "hours", 1, 8760) * 3_600_000))
                if (operation == "complete") check(snapshot.optBoolean("completionEvidenceAvailable")) { "原完成凭证宿主尚未准备" }
                val command = mapOf("create" to "createCommitment", "update" to "updateCommitment", "snooze" to "snoozeCommitment", "cancel" to "cancelCommitment", "complete" to "completeCommitment").getValue(operation)
                val details = JSONObject(original?.toString() ?: "{}")
                if (args.has("date") && operation in setOf("create", "update")) {
                    val at = input.getJSONObject(if (operation == "create") "commitment" else "patch").getLong("dueAt")
                    val local = Instant.ofEpochMilli(at).atZone(ZoneId.of(args.getString("zone")))
                    details.put("calendar", JSONObject().put("local", local.toLocalDateTime().toString()).put("zone", local.zone.id).put("offset", local.offset.id))
                }
                deliver(review(false, command, input, details), null)
            }
            "commitments.reviewPolicy" -> {
                fields(args, "revision", "enabled", "zoneId", "quietHours", "cooldownMinutes", "pausedUntil", "maxNotificationsPerDay", "requiresNetwork", "requiresCharging", "minBatteryPercent")
                val original = snapshot.optJSONObject("policy") ?: error("请先读取原跟进设置")
                check(integer(args, "revision", 0, Long.MAX_VALUE) == original.getLong("revision")) { "原跟进设置已变化，草稿保留，请重新核对" }
                require(args.opt("enabled") is Boolean); val zone = text(args, "zoneId", 128); ZoneId.of(zone)
                val policy = JSONObject().put("enabled", args.getBoolean("enabled")).put("zoneId", zone)
                    .put("cooldownMillis", integer(args, "cooldownMinutes", 0, 44640) * 60_000)
                    .put("pausedUntil", if (args.isNull("pausedUntil")) JSONObject.NULL else integer(args, "pausedUntil", System.currentTimeMillis() + 1, Long.MAX_VALUE))
                val quiet = args.optJSONObject("quietHours")
                if (quiet != null) { fields(quiet, "start", "end"); val start = text(quiet, "start", 5); val end = text(quiet, "end", 5); java.time.LocalTime.parse(start); java.time.LocalTime.parse(end); require(start != end) }
                else require(!args.has("quietHours") || args.isNull("quietHours"))
                policy.put("quietHours", quiet ?: JSONObject.NULL)
                if (args.has("maxNotificationsPerDay") && !args.isNull("maxNotificationsPerDay")) integer(args, "maxNotificationsPerDay", 0, 1000)
                if (args.has("minBatteryPercent")) integer(args, "minBatteryPercent", 0, 100)
                for (key in listOf("requiresNetwork", "requiresCharging")) if (args.has(key)) require(args.opt(key) is Boolean)
                for (key in listOf("maxNotificationsPerDay", "requiresNetwork", "requiresCharging", "minBatteryPercent")) {
                    // Preserve conditions that the owner did not edit; never silently loosen an existing limit.
                    if (args.has(key)) policy.put(key, args.opt(key)) else if (original.has(key)) policy.put(key, original.opt(key))
                }
                deliver(review(true, "setFollowUpPolicy", JSONObject().put("revision", original.getLong("revision")).put("policy", policy)), null)
            }
            "commitments.receipts" -> {
                fields(args, "viewHandle"); val original = read(text(args, "viewHandle"))
                request(true, "listFollowUpReceipts", JSONObject().put("commitmentId", original.getString("id"))) { value ->
                    receipts.clear(); value.getJSONArray("receipts").objects().forEach { receipts[it.getString("claimId")] = JSONObject(it.toString()) }
                    public(value)
                }
            }
            "commitments.reviewReceipt", "commitments.reconcileReceipt" -> {
                fields(args, "claimId", "outcome"); val claim = text(args, "claimId")
                val original = receipts[claim] ?: error("请先读取这次原跟进回执")
                check(original.optString("status") == "delivery_unknown") { "原通知已有确定回执，请刷新" }
                if (action == "commitments.reconcileReceipt") request(true, "reconcileFollowUpNotification", JSONObject().put("claimId", claim)) { public(it) }
                else {
                    val outcome = text(args, "outcome", 16); require(outcome in setOf("notified", "not_sent"))
                    deliver(review(true, "confirmFollowUpReceipt", JSONObject().put("claimId", claim).put("outcome", outcome), original), null)
                }
            }
            "commitments.confirm" -> {
                fields(args, "reviewHandle")
                val original = reviews.remove(text(args, "reviewHandle")) ?: error("原承诺审阅已使用或关闭，请先核对原记录")
                check(original.expires > SystemClock.elapsedRealtime()) { "原承诺审阅已过期" }
                request(original.owner, original.command, JSONObject(original.input)) { value ->
                    JSONObject().put("confirmed", true).put("command", original.command).put("result", public(value))
                }
            }
            else -> error("未接入的承诺操作")
        }
    }
}
