package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.features.automation.PlanBusinessWait
import dev.pocketengine.features.automation.PlanRemoteWait
import dev.pocketengine.ui.ExecutionDependencyDraft
import dev.pocketengine.ui.ExecutionStepDraft
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.executionPlanDefinition
import dev.pocketengine.ui.executionStepPatch
import dev.pocketengine.ui.objects
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The Web owner edits the same plan definitions and uses the original native plan host. */
internal class PocketWebPlans(private val controller: PocketWorldController) {
    private data class Review(val command: String, val input: String, val expires: Long)
    @Volatile private var generation = 0L
    private var snapshot = JSONObject()
    private val readings = linkedMapOf<String, Pair<String, Long>>()
    private val reviews = linkedMapOf<String, Review>()
    companion object {
        val COMMANDS = setOf("plans.snapshot", "plans.read", "plans.reviewCreate", "plans.reviewStep", "plans.reviewAction",
            "plans.reviewApproval", "plans.reviewSchedule", "plans.confirm", "plans.inspect", "plans.discard")
    }
    fun clear() { generation++; snapshot = JSONObject(); readings.clear(); reviews.clear() }
    private fun fields(input: JSONObject, vararg allowed: String) { require(input.keys().asSequence().all { it in allowed }) { "计划操作包含不适用的字段" } }
    private fun text(input: JSONObject, key: String, max: Int = 128, empty: Boolean = false): String {
        val value = input.opt(key)
        require(value is String && value.length <= max && '\u0000' !in value && (empty || value.isNotBlank())) { "请核对$key" }; return value
    }
    private fun integer(input: JSONObject, key: String, min: Long, max: Long): Long {
        val value = input.opt(key)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() in min..max) { "请核对$key" }; return value.toLong()
    }
    private fun public(value: JSONObject) = PocketWebState.publicValue(value) as JSONObject
    private fun known(id: String) = snapshot.optJSONArray("plans").objects().singleOrNull { it.optString("id") == id } ?: error("请刷新原持续目标清单")
    private fun read(handle: String): JSONObject {
        val value = readings[handle]; check(value != null && value.second > SystemClock.elapsedRealtime()) { "原目标读取已关闭或过期，请重新读取" }; return JSONObject(value.first)
    }
    private fun view(plan: JSONObject): JSONObject {
        readings.entries.removeAll { it.value.second <= SystemClock.elapsedRealtime() }
        while (readings.size >= 16) readings.remove(readings.keys.first())
        val handle = UUID.randomUUID().toString(); readings[handle] = plan.toString() to SystemClock.elapsedRealtime() + 120_000
        return JSONObject().put("viewHandle", handle).put("plan", public(plan))
    }
    private fun review(command: String, input: JSONObject, plan: JSONObject? = null): JSONObject {
        reviews.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }; check(reviews.size < 8) { "请先关闭已有计划审阅" }
        val handle = UUID.randomUUID().toString(); reviews[handle] = Review(command, input.toString(), SystemClock.elapsedRealtime() + 120_000)
        return JSONObject().put("reviewHandle", handle).put("command", command).put("input", public(input)).also { if (plan != null) it.put("plan", public(plan)) }
    }
    private fun reference(plan: JSONObject) = JSONObject().put("id", plan.getString("id")).put("revision", plan.getLong("revision"))
    private fun calendarDueAt(input: JSONObject, now: Long): Long? {
        require(!input.has("date") || !input.has("delayMinutes")) { "具体日期与相对分钟只能选择一种" }
        require(input.has("date") || listOf("time", "zone", "laterOverlap").none { input.has(it) }) { "请填写完整开始日期与时间" }
        return if (input.has("date")) PocketWebFormTime.instant(input, now) else null
    }
    private fun withCalendar(review: JSONObject, input: JSONObject, at: Long?): JSONObject {
        if (at != null) {
            val local = Instant.ofEpochMilli(at).atZone(ZoneId.of(input.getString("zone")))
            review.put("calendar", JSONObject().put("local", local.toLocalDateTime().toString()).put("zone", local.zone.id).put("offset", local.offset.id))
        }
        return review
    }
    private fun draft(input: JSONObject, original: JSONObject? = null): ExecutionStepDraft {
        fields(input, "goal", "executor", "projectId", "path", "heading", "minimumBytes", "dependencies", "remoteWait", "businessWait")
        val dependencies = input.getJSONArray("dependencies"); require(dependencies.length() <= 50 && dependencies.objects().size == dependencies.length())
        val refs = dependencies.objects().map { row -> fields(row, "step", "outcome"); ExecutionDependencyDraft(integer(row, "step", 0, 49).toInt(), text(row, "outcome", 16)) }
        fun source(field: String, collection: String): JSONObject? {
            require(input.has(field)) { "请明确核对前置条件" }
            if (input.isNull(field)) return null
            if (input.opt(field) == "preserve") return original?.optJSONObject(field)?.let { JSONObject(it.toString()) } ?: error("原前置条件已变化，请重新选择")
            val supplied = input.getJSONObject(field)
            val candidate = snapshot.optJSONArray(collection).objects().singleOrNull { row ->
                if (field == "remoteWait") PlanRemoteWait.sameIdentity(supplied, row.getJSONObject("reference"))
                else PlanBusinessWait.same(supplied, row.getJSONObject("reference"))
            } ?: error("原前置来源已不在读取窗口，请刷新；没有替换成其它任务")
            return JSONObject(candidate.getJSONObject("reference").toString())
        }
        val executor = text(input, "executor", 16)
        val project = text(input, "projectId", 128, true)
        if (executor == "workspace") check(controller.state.feature("workspace").optJSONArray("projects").objects().any { it.optString("id") == project }) { "原项目已不在当前目录，请刷新后明确选择" }
        return ExecutionStepDraft(text(input, "goal", 4000), executor, project, text(input, "path", 1024, true), text(input, "heading", 1000, true),
            integer(input, "minimumBytes", 1, 1_000_000).toString(), refs, source("remoteWait", "remoteWaitCandidates"), source("businessWait", "businessWaitCandidates"))
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到前台私人持续目标页面" }
        val originalGeneration = generation; val window = controller.webWindowTicket()
        fun current() = generation == originalGeneration && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()
        fun deliver(value: JSONObject?, error: String?) { if (current()) done(value, error) }
        fun request(command: String, payload: JSONObject, receive: (JSONObject) -> JSONObject) {
            controller.requestWebOwnerActionReceipt("automationPlan", JSONObject().put("command", command).put("payload", payload), ::current) { value, error ->
                if (current()) {
                    if (value == null || error != null) deliver(null, error ?: "原计划操作没有返回回执")
                    else try {
                        check(value.optString("command") == command) { "原计划回执操作不匹配" }
                        value.optJSONObject("snapshot")?.let { incoming ->
                            if (snapshot.optString("snapshotProcess") != incoming.optString("snapshotProcess") || incoming.optLong("snapshotSequence") >= snapshot.optLong("snapshotSequence")) snapshot = JSONObject(incoming.toString())
                        }
                        deliver(receive(value), null)
                    } catch (failure: Exception) { deliver(null, failure.message ?: "原目标回执待核对") }
                }
            }
        }
        when (action) {
            "plans.snapshot" -> { fields(args); request("snapshot", JSONObject()) { public(snapshot) } }
            "plans.read" -> {
                fields(args, "id"); val id = text(args, "id"); known(id)
                request("snapshot", JSONObject()) { view(known(id)) }
            }
            "plans.discard" -> {
                fields(args, "viewHandle", "reviewHandle")
                if (args.length() == 0) { clear(); done(JSONObject().put("closed", true), null); return } else { readings.remove(args.optString("viewHandle")); reviews.remove(args.optString("reviewHandle")) }
                deliver(JSONObject().put("closed", true), null)
            }
            "plans.reviewCreate" -> {
                fields(args, "goal", "delayMinutes", "steps", "date", "time", "zone", "laterOverlap")
                check(snapshot.has("plans")) { "请先读取原计划工作区" }
                val rows = args.getJSONArray("steps"); require(rows.length() in 1..50 && rows.objects().size == rows.length())
                val now = System.currentTimeMillis(); val at = calendarDueAt(args, now)
                val delay = if (at == null) integer(args, "delayMinutes", 0, 525600).toString() else "0"
                val plan = executionPlanDefinition(text(args, "goal", 4000), rows.objects().map { draft(it) }, delay, now, at)
                deliver(withCalendar(review("create", JSONObject().put("plan", plan), plan), args, at), null)
            }
            "plans.reviewStep" -> {
                fields(args, "viewHandle", "stepId", "draft", "delayMinutes", "timingEdited", "date", "time", "zone", "laterOverlap")
                val plan = read(text(args, "viewHandle")); val id = text(args, "stepId")
                val step = plan.getJSONArray("steps").objects().singleOrNull { it.optString("id") == id } ?: error("原步骤已不存在")
                check(plan.optJSONObject("execution")?.optString("state") == "paused" && step.optString("taskId").isEmpty() && step.optJSONObject("taskAdmission") == null) { "只能修改暂停后尚未准入的原步骤" }
                require(args.opt("timingEdited") is Boolean)
                val timingEdited = args.getBoolean("timingEdited"); val now = System.currentTimeMillis()
                require(timingEdited || !args.has("date") && listOf("time", "zone", "laterOverlap").none { args.has(it) }) { "修改开始时间前请明确选择新时间" }
                val at = calendarDueAt(args, now)
                val delay = if (at != null || !timingEdited && !args.has("delayMinutes")) "0" else integer(args, "delayMinutes", 0, 525600).toString()
                val patch = executionStepPatch(plan, step, draft(args.getJSONObject("draft"), step), delay, timingEdited, now, at)
                require(patch.length() > 0) { "没有修改，原授权与条件继续保留" }
                deliver(withCalendar(review("editStep", reference(plan).put("stepId", id).put("stepRevision", step.getLong("revision")).put("patch", patch), plan), args, at), null)
            }
            "plans.reviewAction" -> {
                fields(args, "viewHandle", "operation", "stepId")
                val plan = read(text(args, "viewHandle")); val operation = text(args, "operation", 16)
                require(operation in setOf("start", "pause", "resume", "cancel", "retry", "refresh"))
                val input = reference(plan)
                if (operation == "retry") {
                    val id = text(args, "stepId"); val step = plan.getJSONArray("steps").objects().singleOrNull { it.optString("id") == id } ?: error("原步骤已不存在")
                    check(step.optString("status") in setOf("failed", "cancelled") && step.optJSONObject("taskObservation")?.optBoolean("terminalVerified") == true) { "原任务仍待核对，不能重新准入" }
                    input.put("stepId", id).put("stepRevision", step.getLong("revision"))
                }
                deliver(review(operation, input, plan), null)
            }
            "plans.reviewApproval" -> {
                fields(args, "viewHandle", "settings")
                val plan = read(text(args, "viewHandle")); val input = args.getJSONObject("settings")
                fields(input, "mode", "maxTaskAdmissions", "phoneMode", "modelObservationConsent", "allowPrivateArtifactFreeze", "businessEventConsent", "notifyChanges", "hours", "zoneId", "quietHours", "requiresNetwork", "requiresCharging", "minBatteryPercent")
                require(text(input, "mode", 16) in setOf("manual", "automatic")); require(text(input, "phoneMode", 16) in setOf("background", "main_screen"))
                for (key in listOf("modelObservationConsent", "allowPrivateArtifactFreeze", "businessEventConsent", "notifyChanges", "requiresNetwork", "requiresCharging")) require(input.opt(key) is Boolean)
                require(input.getBoolean("modelObservationConsent")) { "请明确核对需要发送给模型的观察范围" }
                if (plan.getJSONArray("steps").objects().any { it.optJSONObject("businessWait") != null }) require(input.getBoolean("businessEventConsent")) { "请明确核对认证来源触发范围" }
                integer(input, "maxTaskAdmissions", 1, 1000); integer(input, "minBatteryPercent", 0, 100); ZoneId.of(text(input, "zoneId", 128))
                val settings = JSONObject(input.toString()); val expires = Math.addExact(System.currentTimeMillis(), integer(input, "hours", 1, 8760) * 3_600_000)
                settings.remove("hours"); settings.put("expiresAt", expires)
                require(plan.getJSONArray("steps").objects().filter { it.optString("status") !in setOf("completed", "failed", "cancelled", "skipped") }.all { it.optLong("dueAt") < expires }) { "授权截止须晚于未执行步骤的最早时间" }
                input.optJSONObject("quietHours")?.let { quiet -> fields(quiet, "start", "end"); val start = text(quiet, "start", 5); val end = text(quiet, "end", 5); java.time.LocalTime.parse(start); java.time.LocalTime.parse(end); require(start != end) }
                if (input.isNull("quietHours")) settings.remove("quietHours")
                deliver(review("approve", reference(plan).put("settings", settings), plan), null)
            }
            "plans.reviewSchedule" -> {
                fields(args, "id"); val id = text(args, "id")
                val row = snapshot.optJSONArray("scheduleProposals").objects().singleOrNull { it.optString("id") == id } ?: error("请重新读取原时间草案")
                check(row.optBoolean("sourceAvailable") && row.optString("status") == "pending") { "原时间草案来源不可核对" }
                val checked = dev.pocketengine.features.automation.AutomationBook.reviewedProposalForDisplay(row)
                deliver(review("approveSchedule", JSONObject().put("id", id).put("digest", checked.getString("digest")), checked), null)
            }
            "plans.inspect" -> {
                fields(args, "viewHandle"); val plan = read(text(args, "viewHandle"))
                request("inspect", JSONObject().put("id", plan.getString("id"))) { view(known(plan.getString("id"))) }
            }
            "plans.confirm" -> {
                fields(args, "reviewHandle")
                val original = reviews.remove(text(args, "reviewHandle")) ?: error("原计划审阅已使用或关闭，请先核对原记录")
                check(original.expires > SystemClock.elapsedRealtime()) { "原计划审阅已过期" }
                val input = JSONObject(original.input)
                request(original.command, input) { value ->
                    JSONObject().put("confirmed", true).put("command", original.command).put("result", public(value)).also { output ->
                        (value.optJSONObject("plan") ?: if (original.command == "refresh") known(input.getString("id")) else null)
                            ?.let { output.put("reading", view(it)) }
                        output.put("snapshot", public(snapshot))
                    }
                }
            }
            else -> error("未接入的计划操作")
        }
    }
}
