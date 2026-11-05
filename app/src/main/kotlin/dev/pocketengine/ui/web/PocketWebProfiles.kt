package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.controlplane.NativeWorkProfileHost
import dev.pocketengine.features.controlplane.WorkProfile
import dev.pocketengine.features.local.project.ProjectOwnerWorkflows
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.WorkProfileDrafts
import dev.pocketengine.ui.objects
import org.json.JSONObject
import java.util.UUID

/** Definitions and explicitly reviewed starts share the original native sources, Settings CAS and typed task admission. */
internal class PocketWebProfiles(private val controller: PocketWorldController, private val ownerCurrent: () -> Boolean) {
    private data class Reading(val definition: String, val expires: Long)
    private data class Choices(val project: ProjectOwnerWorkflows.ProfileProjectSelection?, val expires: Long)
    private data class Inspected(val handle: String, val review: NativeWorkProfileHost.Review, val control: RunControl,
        val definition: String, val choicesHandle: String, val project: ProjectOwnerWorkflows.ProfileProjectSelection?, val expires: Long)
    private data class Start(val inspected: Inspected, val goal: String)
    private data class Reviewed(val command: String, val definition: String, val original: String?, val expires: Long,
        val rebind: NativeWorkProfileHost.RestoredReview? = null, val start: Start? = null)
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    private var page: NativeWorkProfileHost.Page? = null
    private val readings = linkedMapOf<String, Reading>()
    private val choices = linkedMapOf<String, Choices>()
    private val reviews = linkedMapOf<String, Reviewed>()
    private val observations = linkedSetOf<RunControl>()
    private var inspected: Inspected? = null
    private val starting = linkedMapOf<String, RunControl>()
    // Bounded public receipts only. Clearing a window never forgets an already-entered save's outcome.
    private val results = linkedMapOf<String, JSONObject>()
    companion object {
        val COMMANDS = setOf("profiles.snapshot", "profiles.read", "profiles.choices", "profiles.reviewSave", "profiles.reviewRemove",
            "profiles.reviewRebind", "profiles.preflight", "profiles.reviewStart", "profiles.confirm", "profiles.stop", "profiles.result", "profiles.discard")
    }
    fun clear() {
        val old: NativeWorkProfileHost.Page?
        val cancelling: List<RunControl>
        synchronized(lock) {
            generation++; old = page; page = null; readings.clear(); choices.clear()
            cancelling = (observations + listOfNotNull(inspected?.control) + reviews.values.mapNotNull { it.start?.inspected?.control } + starting.values)
                .filterNot { it.hasHostRun() }.distinct()
            inspected = null; reviews.clear(); observations.clear()
            results.values.filter { it.optString("status") == "queued" }.forEach { it.put("status", "not_entered") }
        }
        cancelling.forEach { it.cancel() }; old?.setForeground(false); old?.close()
    }
    private fun invalidateStarts(choicesHandle: String? = null) {
        val cancelled = mutableListOf<RunControl>()
        synchronized(lock) {
            inspected?.takeIf { choicesHandle == null || it.choicesHandle == choicesHandle }?.let { cancelled += it.control; inspected = null }
            reviews.entries.removeAll { entry ->
                entry.value.start?.inspected?.takeIf { choicesHandle == null || it.choicesHandle == choicesHandle }
                    ?.also { cancelled += it.control } != null
            }
        }
        cancelled.filterNot { it.hasHostRun() }.forEach { it.cancel() }
    }
    private fun public(value: JSONObject) = PocketWebState.publicValue(value) as JSONObject
    private fun fields(value: JSONObject, vararg allowed: String) = require(value.keys().asSequence().all { it in allowed }) { "工作配置操作包含未声明字段" }
    private fun text(value: JSONObject, field: String, max: Int = 128): String {
        val raw = value.opt(field); require(raw is String && raw.isNotBlank() && raw.length <= max && '\u0000' !in raw) { "请核对$field" }; return raw
    }
    private fun ownerPage(): NativeWorkProfileHost.Page = synchronized(lock) {
        check(ownerCurrent() && controller.webPrivateOwnerVisible()) { "请返回前台私人工作配置页" }
        (page ?: controller.nativeWorkProfiles().newOwnerPage().also { page = it }).also { it.setForeground(true) }
    }
    private fun reading(handle: String): JSONObject = synchronized(lock) {
        val exact = readings[handle]; check(exact != null && exact.expires > SystemClock.elapsedRealtime()) { "原工作配置读取已关闭或过期" }; JSONObject(exact.definition)
    }
    private fun scope(input: JSONObject): ProjectOwnerWorkflows.ProfileProjectSelection? = synchronized(lock) {
        val handle = text(input, "choicesHandle"); val exact = choices[handle]
        check(exact != null && exact.expires > SystemClock.elapsedRealtime()) { "请重新读取本机来源；没有借用另一项目范围" }; exact.project
    }
    private fun currentRecord(owner: NativeWorkProfileHost.Page, expected: JSONObject): JSONObject {
        val actual = owner.snapshot().getJSONArray("profiles").objects().singleOrNull { it.optString("profileId") == expected.getString("profileId") }
        check(actual != null && WorkProfile.fingerprint(actual) == WorkProfile.fingerprint(expected)) { "原工作配置已变化，请保留草稿并重新读取" }; return actual
    }
    private fun view(definition: JSONObject): JSONObject = synchronized(lock) {
        readings.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }; while (readings.size >= 16) readings.remove(readings.keys.first())
        val handle = UUID.randomUUID().toString(); readings[handle] = Reading(definition.toString(), SystemClock.elapsedRealtime() + 600_000)
        JSONObject().put("viewHandle", handle).put("profile", public(definition))
    }
    private fun draft(input: JSONObject, original: JSONObject?): JSONObject {
        val supplied = input.getJSONObject("definition"); require(supplied.toString().toByteArray(Charsets.UTF_8).size <= WorkProfile.MAX_BYTES)
        val value = JSONObject(supplied.toString()).put("profileId", original?.getString("profileId") ?: "draft-profile")
            .put("revision", original?.getLong("revision") ?: 1L)
        if (original == null) {
            require(!value.has("restoredPendingReview") && !value.has("restoredReferences")) { "新建配置不能自称恢复来源" }
        } else {
            if (original.has("restoredReferences")) value.put("restoredReferences", original.getJSONArray("restoredReferences"))
            if (original.has("restoredPendingReview")) value.put("restoredPendingReview", original.getBoolean("restoredPendingReview"))
        }
        return WorkProfile.fromJson(value).toJson()
    }
    private fun review(command: String, definition: JSONObject, original: JSONObject?, native: NativeWorkProfileHost.RestoredReview? = null): JSONObject = synchronized(lock) {
        reviews.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }; check(reviews.size < 8) { "请先关闭已有工作配置审阅" }
        val handle = UUID.randomUUID().toString(); reviews[handle] = Reviewed(command, definition.toString(), original?.toString(), SystemClock.elapsedRealtime() + 120_000, native)
        JSONObject().put("reviewHandle", handle).put("operationId", handle).put("command", command).put("definition", public(definition))
            .put("original", original?.let(::public) ?: JSONObject.NULL).put("startsTask", false).put("permissionsChanged", false)
            .also { if (native != null) it.put("nativeReview", public(native.view())) }
    }
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(ownerCurrent() && controller.webPrivateOwnerVisible()) { "请返回前台私人工作配置页" }
        val ticket = generation; val window = controller.webWindowTicket()
        fun current() = ticket == generation && window == controller.webWindowTicket() && ownerCurrent() && controller.webPrivateOwnerVisible()
        if (action == "profiles.discard") {
            fields(args, "viewHandle", "reviewHandle", "choicesHandle", "preflightHandle")
            if (args.length() == 0) clear() else {
                synchronized(lock) {
                    readings.remove(args.optString("viewHandle"))
                    reviews.remove(args.optString("reviewHandle"))?.start?.inspected?.control?.takeIf { !it.hasHostRun() }?.cancel()
                    if (inspected?.handle == args.optString("preflightHandle")) { inspected?.control?.cancel(); inspected = null }
                    choices.remove(args.optString("choicesHandle"))
                }
                if (args.has("viewHandle")) invalidateStarts()
                else if (args.has("choicesHandle")) invalidateStarts(args.getString("choicesHandle"))
            }
            done(JSONObject().put("closed", true), null); return
        }
        val owner = ownerPage()
        if (action in setOf("profiles.read", "profiles.choices", "profiles.preflight", "profiles.reviewSave", "profiles.reviewRemove", "profiles.reviewRebind")) invalidateStarts()
        fun work(operation: String? = null, body: (RunControl) -> JSONObject) {
            val original = RunControl(); original.setAuthorityCheck { check(current()) { "原工作配置页面已离开，本次操作不再准入" } }
            synchronized(lock) { observations.add(original) }
            try { controller.runWorkProfileWork(Runnable {
                var value: JSONObject? = null; var error: String? = null
                try {
                    if (!current()) {
                        if (operation != null) synchronized(lock) { results[operation]?.put("status", "not_entered") }
                        throw SecurityException("原工作配置请求尚未进入，窗口已经关闭")
                    }
                    original.checkpoint()
                    if (operation != null) synchronized(lock) { results[operation]?.put("status", "entered") }
                    value = body(original)
                    if (operation != null) synchronized(lock) { results[operation] = JSONObject().put("operationId", operation).put("status", "completed").put("receipt", public(value!!)) }
                } catch (failure: Exception) {
                    error = failure.message ?: "原工作配置操作结果需核对"
                    if (operation != null) synchronized(lock) { if (results[operation]?.optString("status") != "not_entered") results[operation] = JSONObject().put("operationId", operation).put("status", "unknown") }
                } finally { synchronized(lock) { observations.remove(original) } }
                main.post { if (current()) done(value, error) }
            }) } catch (failure: Exception) {
                synchronized(lock) { observations.remove(original); if (operation != null) results[operation]?.put("status", "not_entered") }; original.cancel(); throw failure
            }
        }
        when (action) {
            "profiles.snapshot" -> { fields(args); work { public(owner.snapshot()) } }
            "profiles.read" -> {
                fields(args, "id"); val id = text(args, "id")
                work { val actual = owner.snapshot().getJSONArray("profiles").objects().singleOrNull { it.optString("profileId") == id } ?: error("原工作配置不存在，请核对清单"); view(actual) }
            }
            "profiles.choices" -> {
                fields(args, "useCurrentProject"); require(args.opt("useCurrentProject") is Boolean)
                val useCurrentProject = args.getBoolean("useCurrentProject")
                work { observation ->
                    val project = if (useCurrentProject) controller.reviewWorkProfileProject() else null
                    val values = owner.choices(observation, project); observation.checkpoint()
                    synchronized(lock) {
                        choices.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }; while (choices.size >= 8) choices.remove(choices.keys.first())
                        val handle = UUID.randomUUID().toString(); choices[handle] = Choices(project, SystemClock.elapsedRealtime() + 600_000)
                        public(values).put("choicesHandle", handle).put("useCurrentProject", project != null)
                            .put("modelChoices", WorkProfileDrafts.modelChoices(values)).put("relayModelChoices", WorkProfileDrafts.modelChoices(values, "RELAY"))
                            .put("project", project?.view()?.let(::public) ?: JSONObject.NULL)
                    }
                }
            }
            "profiles.reviewSave" -> {
                fields(args, "viewHandle", "definition"); val expected = if (args.has("viewHandle")) reading(text(args, "viewHandle")) else null
                val definition = draft(args, expected)
                work { if (expected != null) currentRecord(owner, expected); review(if (expected == null) "create" else "save", definition, expected) }
            }
            "profiles.reviewRemove" -> {
                fields(args, "viewHandle"); val expected = reading(text(args, "viewHandle"))
                work { currentRecord(owner, expected); review("remove", expected, expected) }
            }
            "profiles.reviewRebind" -> {
                fields(args, "viewHandle", "choicesHandle", "definition"); val expected = reading(text(args, "viewHandle")); val project = scope(args); val definition = draft(args, expected)
                check(expected.optBoolean("restoredPendingReview")) { "此项已不再等待恢复重绑，请读取原记录" }
                work { observation ->
                    currentRecord(owner, expected)
                    val native = owner.reviewRestored(expected.getString("profileId"), expected.getLong("revision"), definition.getString("name"), definition, observation, project)
                    review("rebind", native.view().getJSONObject("definition"), expected, native)
                }
            }
            "profiles.preflight" -> {
                fields(args, "viewHandle", "choicesHandle"); val expected = reading(text(args, "viewHandle")); val project = scope(args)
                work { observation ->
                    currentRecord(owner, expected)
                    val native = owner.review(expected.getString("profileId"), expected.getLong("revision"), observation, project)
                    val actual = native.view()
                    val handle = UUID.randomUUID().toString()
                    // Only the exact native review/control can later become a Selection. JSON carries no authority.
                    observation.setAuthorityCheck { check(observation.hasHostRun() || current()) { "原预检窗口已关闭，未准入新任务" } }
                    synchronized(lock) { inspected = Inspected(handle, native, observation, expected.toString(), args.getString("choicesHandle"), project, SystemClock.elapsedRealtime() + 120_000) }
                    public(actual).put("preflightHandle", handle).put("profileId", expected.getString("profileId")).put("revision", expected.getLong("revision"))
                        .put("project", project?.view()?.let(::public) ?: JSONObject.NULL).put("runnable", WorkProfileDrafts.runnable(actual, project != null))
                        .put("startsTask", false).put("readOnly", true)
                }
            }
            "profiles.reviewStart" -> {
                fields(args, "preflightHandle", "goal")
                val handle = text(args, "preflightHandle"); val goal = text(args, "goal", 4000).trim()
                val original = synchronized(lock) { inspected?.takeIf { it.handle == handle && it.expires > SystemClock.elapsedRealtime() } }
                    ?: error("原预检已关闭或更换，请重新核对本次来源")
                check(WorkProfileDrafts.runnable(original.review.view(), original.project != null)) { "这项配置尚不能从当前范围启动，请核对主任务、摘要模型与原项目选择" }
                work {
                    original.control.checkpoint(); currentRecord(owner, JSONObject(original.definition)); original.project?.checkCurrent()
                    val shown = review("start", JSONObject(original.definition), JSONObject(original.definition))
                        .put("goal", goal).put("preflight", public(original.review.view()))
                        .put("project", original.project?.view()?.let(::public) ?: JSONObject.NULL).put("startsTask", true)
                    synchronized(lock) {
                        check(inspected === original) { "原预检已被更换" }
                        val id = shown.getString("reviewHandle"); reviews[id] = reviews.getValue(id).copy(start = Start(original, goal)); inspected = null
                    }
                    shown
                }
            }
            "profiles.confirm" -> {
                val id = text(args, "reviewHandle")
                val selected = synchronized(lock) { reviews[id] } ?: error("原工作配置审阅已使用或关闭，请先核对结果")
                if (selected.start != null) { fields(args, "reviewHandle", "consent"); require(args.opt("consent") == true) { "请明确核对这次原任务的目标、配置修订与项目范围" } }
                else fields(args, "reviewHandle")
                check(selected.expires > SystemClock.elapsedRealtime()) { "原工作配置审阅已过期" }
                synchronized(lock) {
                    check(reviews.remove(id) === selected) { "原工作配置审阅已变化" }
                    while (results.size >= 32) results.remove(results.keys.first())
                    results[id] = JSONObject().put("operationId", id).put("status", "queued")
                }
                val start = selected.start
                if (start != null) {
                    val original = start.inspected; val control = original.control
                    synchronized(lock) { starting[id] = control }
                    val complete: (Result<JSONObject>) -> Unit = { outcome ->
                        var receipt: JSONObject? = null; var failure: String? = null
                        outcome.fold({ value -> receipt = JSONObject().put("confirmed", true).put("operationId", id).put("command", "start")
                            .put("profileId", JSONObject(original.definition).getString("profileId")).put("revision", JSONObject(original.definition).getLong("revision"))
                            .put("result", public(value)) }, { failure = it.message ?: "原任务结果尚未确认，请核对任务记录" })
                        synchronized(lock) {
                            starting.remove(id)
                            results[id] = JSONObject().put("operationId", id).put("status", if (receipt != null) "completed" else "unknown")
                                .put("receipt", receipt).put("error", failure).also { value -> control.hostRunIdentity()?.let { value.put("taskId", it.taskId()) } }
                        }
                        main.post { if (current()) done(receipt, failure) }
                    }
                    try { controller.runWorkProfileWork(Runnable {
                        try {
                            control.checkpoint(); check(current()) { "原工作配置页面已关闭，未启动" }
                            currentRecord(owner, JSONObject(original.definition)); original.project?.checkCurrent()
                            val selection = owner.select(original.review, original.review.digest, control)
                            synchronized(lock) { results[id]?.put("status", "entered") }
                            if (original.project != null) controller.startOwnerWorkProfileProject(start.goal, selection, control, original.project, complete)
                            else controller.startOwnerWorkProfile(start.goal, selection, control, complete)
                        } catch (failure: Exception) { complete(Result.failure(failure)) }
                    }) } catch (failure: Exception) { complete(Result.failure(failure)) }
                    return
                }
                work(id) { observation ->
                    val expected = selected.original?.let(::JSONObject); if (expected != null) currentRecord(owner, expected); observation.checkpoint()
                    val definition = JSONObject(selected.definition)
                    val record = when (selected.command) {
                        "remove" -> owner.remove(expected!!.getString("profileId"), expected.getLong("revision"))
                        "rebind" -> owner.rebindRestored(selected.rebind ?: error("原恢复审阅不在当前窗口"))
                        else -> owner.save(expected?.getString("profileId") ?: "", expected?.getLong("revision") ?: 0L, definition.getString("name"), definition)
                    }
                    JSONObject().put("confirmed", true).put("operationId", id).put("command", selected.command).put("record", public(record)).also { receipt ->
                        try { receipt.put("snapshot", public(owner.snapshot())) } catch (_: Exception) { receipt.put("snapshotAvailable", false) }
                    }
                }
            }
            "profiles.stop" -> {
                fields(args, "operationId"); val id = text(args, "operationId")
                val control = synchronized(lock) { starting[id] } ?: error("本次启动已返回，请从原任务查看结果或明确停止")
                controller.runWorkProfileControl(Runnable { control.cancel(); main.post { if (current()) done(JSONObject().put("operationId", id).put("stopRequested", true).put("verified", false), null) } })
            }
            "profiles.result" -> {
                fields(args, "operationId"); val id = text(args, "operationId")
                work {
                    val result = synchronized(lock) { results[id]?.let { JSONObject(it.toString()) } } ?: JSONObject().put("operationId", id).put("status", "unknown")
                    synchronized(lock) { starting[id]?.hostRunIdentity()?.let { result.put("taskId", it.taskId()) } }
                    result.put("snapshot", public(owner.snapshot())).put("readOnly", true).put("replayAllowed", false)
                }
            }
            else -> error("尚未接入的工作配置操作")
        }
    }
}
