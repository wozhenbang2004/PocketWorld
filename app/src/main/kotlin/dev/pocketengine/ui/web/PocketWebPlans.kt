package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.automation.AutomationSchedule
import dev.pocketengine.features.automation.DeviceScheduleOrigin
import dev.pocketengine.features.automation.PlanArtifactVerifier
import dev.pocketengine.features.automation.PlanBusinessWait
import dev.pocketengine.features.automation.PlanRemoteWait
import dev.pocketengine.ui.ExecutionDependencyDraft
import dev.pocketengine.ui.ExecutionStepDraft
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.executionPlanDefinition
import dev.pocketengine.ui.executionStepPatch
import dev.pocketengine.ui.objects
import dev.pocketengine.store.TaskStore
import org.json.JSONArray
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
    private data class OriginalFileView(val handle: String, val expires: Long, val file: PlanArtifactVerifier.OriginalFile)
    @Volatile private var originalFileView: OriginalFileView? = null
    @Volatile private var originalFileFlight: Any? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var reminderSelection = 0L
    private data class OriginalReminder(
        val handle: String, val expires: Long, val reply: TaskStore.OriginalReminderReply,
        val proposal: TaskStore.OriginalReminderProposal, val sameSource: () -> Boolean,
    )
    private var originalReminder: OriginalReminder? = null
    companion object {
        val COMMANDS = setOf("plans.snapshot", "plans.read", "plans.reviewCreate", "plans.reviewStep", "plans.reviewAction",
            "plans.reviewApproval", "plans.reviewSchedule", "plans.confirm", "plans.inspect", "plans.discard", "plans.readOriginalStepFile")
    }
    fun clear() { generation++; snapshot = JSONObject(); readings.clear(); reviews.clear(); originalFileView = null; originalFileFlight = null; clearOriginalReminder() }
    fun clearOriginalReminder() { reminderSelection++; originalReminder = null }
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
    private fun originalReply(message: String, revision: Long): TaskStore.OriginalReminderReply? {
        val shown = controller.state
        val conversation = shown.conversationId?.takeIf(String::isNotBlank) ?: return null
        val row = shown.messages.singleOrNull { it.id == message && it.sourceRevision == revision && it.role == "assistant" } ?: return null
        val reply = row.replyContext ?: return null
        val replyId = reply.replyId?.takeIf { reply.deliveryId == null } ?: return null
        val user = reply.replyToMessageIds.singleOrNull()?.takeIf { row.taskId == reply.taskId } ?: return null
        return TaskStore.OriginalReminderReply(conversation, message, revision, reply.taskId, reply.runId, reply.epoch, replyId, user)
    }
    private fun reminderResult(reply: TaskStore.OriginalReminderReply, status: String): JSONObject =
        JSONObject().put("status", status).put("messageId", reply.messageId()).put("sourceRevision", reply.sourceRevision())
            .put("executionAuthorized", false).also { if (status != "unavailable") it.put("source", reply.view()) }
    /** Literal opt-in from the original message menu; the renderer never chooses task, run or rule. */
    fun readOriginalReminder(args: JSONObject, current: () -> Boolean, done: (JSONObject?, String?) -> Unit) {
        fields(args, "messageId", "sourceRevision")
        val message = text(args, "messageId", 240); val rev = integer(args, "sourceRevision", 1, 9_007_199_254_740_991L)
        clearOriginalReminder()
        val selected = reminderSelection
        val reply = originalReply(message, rev)?.takeIf { current() }
        if (reply == null) {
            done(JSONObject().put("status", "unavailable").put("messageId", message).put("sourceRevision", rev).put("executionAuthorized", false), null)
            return
        }
        fun sameSource() = reminderSelection == selected && current() && originalReply(message, rev) == reply
        controller.runPluginCatalogWork(Runnable {
            val read = runCatching { controller.nativeButlerHost().readOriginalConversationReminder(reply) { sameSource() } }.getOrNull()
            main.post {
                if (!sameSource()) { done(null, "原私人提醒窗口已变化，请回到这条回复重新核对"); return@post }
                val value = read?.value()
                val output = runCatching {
                    if (value == null) reminderResult(reply, "unavailable") else when (value.optString("status")) {
                        "pending" -> {
                            val proposal = read?.selected() ?: error("原草案身份未核对")
                            val checked = value.getJSONObject("proposal")
                            val expires = SystemClock.elapsedRealtime() + 120_000
                            val handle = UUID.randomUUID().toString()
                            val input = JSONObject().put("id", proposal.id()).put("digest", proposal.digest())
                            val review = JSONObject().put("reviewHandle", handle).put("expiresInMs", 120_000)
                                .put("command", "approveSchedule").put("input", input).put("plan", public(checked))
                                .put("ordinaryReminderPreview", ordinaryReminderPreview(checked) ?: error("无法核对日历"))
                            reminderResult(reply, "pending").put("review", review).also {
                                originalReminder = OriginalReminder(handle, expires, reply, proposal, ::sameSource)
                            }
                        }
                        "saved" -> reminderResult(reply, "saved").put("ruleId", value.getString("ruleId")).put("ruleRevision", value.getInt("ruleRevision"))
                        "unknown" -> reminderResult(reply, "unknown")
                        else -> reminderResult(reply, "unavailable")
                    }
                }.getOrElse { reminderResult(reply, "unavailable") }
                done(output, null)
            }
        })
    }
    fun confirmOriginalReminder(args: JSONObject, current: () -> Boolean, done: (JSONObject?, String?) -> Unit) {
        fields(args, "reviewHandle", "originalChatReminder"); require(args.opt("originalChatReminder") == true)
        val handle = text(args, "reviewHandle")
        val held = originalReminder?.takeIf { it.handle == handle } ?: error("原提醒审阅已使用或关闭，请重新核对")
        originalReminder = null
        fun sameSource() = current() && held.sameSource()
        fun maySave() = sameSource() && SystemClock.elapsedRealtime() < held.expires
        check(maySave()) { "原提醒审阅已过期或已离开，请重新核对" }
        controller.runPluginCatalogWork(Runnable {
            val saved = runCatching { controller.nativeButlerHost().approveOriginalConversationReminder(held.reply, held.proposal) { maySave() } }
            main.post {
                if (!sameSource()) { done(null, "原私人提醒窗口已变化，请核对原回执；没有重试"); return@post }
                val output = saved.mapCatching { schedule ->
                    val rule = schedule.getJSONObject("rule")
                    check(rule.getString("id") == held.proposal.id() && rule.getInt("revision") == 1)
                    reminderResult(held.reply, "saved").put("ruleId", rule.getString("id")).put("ruleRevision", 1)
                        .put("confirmed", true).put("command", "approveSchedule")
                        .put("result", JSONObject().put("schedule", public(schedule)))
                }.getOrNull()
                done(output, if (output == null) "尚未确认保存结果，请重新核对这条回复的原提醒；没有重试" else null)
            }
        })
    }
    private fun known(id: String) = snapshot.optJSONArray("plans").objects().singleOrNull { it.optString("id") == id } ?: error("请刷新原持续目标清单")
    private fun read(handle: String): JSONObject {
        val value = readings[handle]; check(value != null && value.second > SystemClock.elapsedRealtime()) { "原目标读取已关闭或过期，请重新读取" }; return JSONObject(value.first)
    }
    private fun view(plan: JSONObject): JSONObject {
        readings.entries.removeAll { it.value.second <= SystemClock.elapsedRealtime() }
        while (readings.size >= 16) readings.remove(readings.keys.first())
        val handle = UUID.randomUUID().toString(); val expires = SystemClock.elapsedRealtime() + 120_000
        readings[handle] = plan.toString() to expires
        val step = plan.optJSONObject("progress")?.optJSONObject("lastResult")?.optString("stepId").orEmpty()
        // The native Book/Work view is already present. No extra read or body at discovery.
        val original = runCatching { PlanArtifactVerifier.heldOriginalFile(plan, step) }.getOrNull()
        originalFileFlight = null; originalFileView = original?.let { OriginalFileView(handle, expires, it) }
        return JSONObject().put("viewHandle", handle).put("plan", public(plan)).also { output ->
            original?.let { output.put("originalStepFile", JSONObject().put("stepId", it.stepId())
                .put("evidenceId", it.evidenceId()).put("sourceVersion", it.sourceVersion())) }
        }
    }
    /** Only the current native Plan view may opt into its one actually presented finished file. */
    fun readOriginalStepFile(args: JSONObject, ownerCurrent: () -> Boolean, done: (JSONObject?, String?) -> Unit) {
        fields(args, "viewHandle", "stepId")
        val handle = text(args, "viewHandle"); val step = text(args, "stepId")
        val held = originalFileView ?: error("请重新核对这个原目标")
        check(handle == held.handle && step == held.file.stepId() && originalFileFlight == null &&
            SystemClock.elapsedRealtime() < held.expires && ownerCurrent()) { "请重新核对这个原目标" }
        val election = Any(); originalFileFlight = election
        fun current() = originalFileView === held && originalFileFlight === election &&
            SystemClock.elapsedRealtime() < held.expires && ownerCurrent()
        try {
            controller.runPluginCatalogWork(Runnable {
                val file = runCatching { controller.nativeButlerHost().readOriginalPlanStepFile(held.file, ::current) }.getOrNull()
                main.post {
                    val ready = current()
                    if (originalFileFlight === election) originalFileFlight = null
                    if (!ready || file == null) done(null, "暂未核对到当时的原文件。核验记录仍保留，没有改读其他版本。")
                    else done(file, null)
                }
            })
        } catch (failure: RuntimeException) {
            if (originalFileFlight === election) originalFileFlight = null
            throw failure
        }
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
    private fun calendarView(at: Long, zone: ZoneId): JSONObject {
        val local = Instant.ofEpochMilli(at).atZone(zone)
        return JSONObject().put("local", local.toLocalDateTime().toString()).put("zone", local.zone.id).put("offset", local.offset.id)
    }
    private fun withCalendar(review: JSONObject, input: JSONObject, at: Long?): JSONObject {
        if (at != null) review.put("calendar", calendarView(at, ZoneId.of(input.getString("zone"))))
        return review
    }
    private fun ordinaryReminderPreview(checked: JSONObject): JSONObject? {
        val draft = checked.getJSONObject("draft")
        if (draft.getString("kind") != "reminder" || DeviceScheduleOrigin.isDevice(checked.getJSONObject("origin"))) return null
        val schedule = draft.getJSONObject("schedule")
        return AutomationSchedule.preview(schedule, System.currentTimeMillis(), 3).also { calendar ->
            // Absolute times have no stored time zone. Display that same instant
            // in the phone's zone without changing the reviewed definition/digest.
            if (schedule.getString("type") in setOf("once", "interval")) {
                val zone = ZoneId.systemDefault()
                for (occurrence in calendar.optJSONArray("occurrences").objects()) {
                    val local = Instant.ofEpochMilli(occurrence.getLong("at")).atZone(zone)
                    occurrence.put("local", local.toLocalDateTime().toString()).put("zone", zone.id).put("offset", local.offset.id)
                }
            }
        }
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
                if (args.length() == 0) { clear(); done(JSONObject().put("closed", true), null); return }
                val handle = args.optString("viewHandle")
                if (originalFileView?.handle == handle) { originalFileView = null; originalFileFlight = null }
                readings.remove(handle); reviews.remove(args.optString("reviewHandle"))
                deliver(JSONObject().put("closed", true), null)
            }
            "plans.reviewCreate" -> {
                fields(args, "goal", "delayMinutes", "steps", "date", "time", "zone", "laterOverlap")
                check(snapshot.has("plans")) { "请先读取原计划工作区" }
                val rows = args.getJSONArray("steps"); val inputSteps = rows.objects()
                require(rows.length() in 1..50 && inputSteps.size == rows.length())
                val now = System.currentTimeMillis(); val at = calendarDueAt(args, now)
                val delay = if (at == null) integer(args, "delayMinutes", 0, 525600).toString() else "0"
                val calendars = inputSteps.mapIndexed { index, row ->
                    if (!row.has("earliestCalendar")) null else {
                        require(index > 0) { "第 1 步使用目标的最早时间" }
                        val calendar = row.getJSONObject("earliestCalendar")
                        fields(calendar, "date", "time", "zone", "laterOverlap")
                        PocketWebFormTime.instant(calendar, now) to ZoneId.of(calendar.getString("zone"))
                    }
                }
                val stepDrafts = inputSteps.map { row -> draft(JSONObject(row.toString()).also { it.remove("earliestCalendar") }) }
                val hasLaterTime = calendars.any { it != null }
                val plan = executionPlanDefinition(text(args, "goal", 4000), stepDrafts, delay, now, at, if (hasLaterTime) calendars.map { it?.first } else null)
                val response = withCalendar(review("create", JSONObject().put("plan", plan), plan), args, at)
                if (hasLaterTime) {
                    val timing = JSONArray()
                    var zone = if (at == null) ZoneId.systemDefault() else ZoneId.of(args.getString("zone"))
                    plan.getJSONArray("steps").objects().forEachIndexed { index, step ->
                        calendars[index]?.second?.let { zone = it }
                        val due = step.getLong("dueAt")
                        val timeView = calendarView(due, zone).put("step", index).put("at", due)
                            .put("source", if (index == 0) "goal" else if (calendars[index] != null) "step" else "previous")
                        if (calendars[index] != null) {
                            val chosen = inputSteps[index].getJSONObject("earliestCalendar")
                            timeView.put("requestedZone", chosen.getString("zone")).put("laterOverlap", chosen.getBoolean("laterOverlap"))
                        }
                        timing.put(timeView)
                    }
                    response.put("stepCalendarReview", timing)
                }
                deliver(response, null)
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
                val preview = ordinaryReminderPreview(checked)
                deliver(review("approveSchedule", JSONObject().put("id", id).put("digest", checked.getString("digest")), checked)
                    .also { result -> preview?.let { result.put("ordinaryReminderPreview", it) } }, null)
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
