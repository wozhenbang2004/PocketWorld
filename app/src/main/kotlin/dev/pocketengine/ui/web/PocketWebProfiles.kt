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

/** Owner definitions only. Native source checks, Settings CAS and execution admission stay original. */
internal class PocketWebProfiles(private val controller: PocketWorldController, private val ownerCurrent: () -> Boolean) {
    private data class Reading(val definition: String, val expires: Long)
    private data class Choices(val project: ProjectOwnerWorkflows.ProfileProjectSelection?, val expires: Long)
    private data class Reviewed(val command: String, val definition: String, val original: String?, val expires: Long,
        val rebind: NativeWorkProfileHost.RestoredReview? = null)
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    private var page: NativeWorkProfileHost.Page? = null
    private val readings = linkedMapOf<String, Reading>()
    private val choices = linkedMapOf<String, Choices>()
    private val reviews = linkedMapOf<String, Reviewed>()
    private val observations = linkedSetOf<RunControl>()
    // Bounded public receipts only. Clearing a window never forgets an already-entered save's outcome.
    private val results = linkedMapOf<String, JSONObject>()
    companion object {
        val COMMANDS = setOf("profiles.snapshot", "profiles.read", "profiles.choices", "profiles.reviewSave", "profiles.reviewRemove",
            "profiles.reviewRebind", "profiles.preflight", "profiles.confirm", "profiles.result", "profiles.discard")
    }
    fun clear() {
        val old: NativeWorkProfileHost.Page?
        val cancelling: List<RunControl>
        synchronized(lock) {
            generation++; old = page; page = null; readings.clear(); choices.clear(); reviews.clear()
            cancelling = observations.toList(); observations.clear()
            results.values.filter { it.optString("status") == "queued" }.forEach { it.put("status", "not_entered") }
        }
        cancelling.forEach { it.cancel() }; old?.setForeground(false); old?.close()
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
            fields(args, "viewHandle", "reviewHandle", "choicesHandle")
            if (args.length() == 0) clear() else synchronized(lock) { readings.remove(args.optString("viewHandle")); reviews.remove(args.optString("reviewHandle")); choices.remove(args.optString("choicesHandle")) }
            done(JSONObject().put("closed", true), null); return
        }
        val owner = ownerPage()
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
                val project = if (args.getBoolean("useCurrentProject")) controller.reviewWorkProfileProject() else null
                work { observation ->
                    val values = owner.choices(observation, project); observation.checkpoint()
                    synchronized(lock) {
                        choices.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }; while (choices.size >= 8) choices.remove(choices.keys.first())
                        val handle = UUID.randomUUID().toString(); choices[handle] = Choices(project, SystemClock.elapsedRealtime() + 600_000)
                        public(values).put("choicesHandle", handle).put("useCurrentProject", project != null)
                            .put("modelChoices", WorkProfileDrafts.modelChoices(values)).put("relayModelChoices", WorkProfileDrafts.modelChoices(values, "RELAY"))
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
                work { observation -> currentRecord(owner, expected); public(owner.review(expected.getString("profileId"), expected.getLong("revision"), observation, project).view()).put("startsTask", false).put("readOnly", true) }
            }
            "profiles.confirm" -> {
                fields(args, "reviewHandle"); val id = text(args, "reviewHandle")
                val selected = synchronized(lock) { reviews.remove(id) } ?: error("原工作配置审阅已使用或关闭，请先核对结果")
                check(selected.expires > SystemClock.elapsedRealtime()) { "原工作配置审阅已过期" }
                synchronized(lock) { while (results.size >= 32) results.remove(results.keys.first()); results[id] = JSONObject().put("operationId", id).put("status", "queued") }
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
            "profiles.result" -> {
                fields(args, "operationId"); val id = text(args, "operationId")
                work { val result = synchronized(lock) { results[id]?.let { JSONObject(it.toString()) } } ?: JSONObject().put("operationId", id).put("status", "unknown"); result.put("snapshot", public(owner.snapshot())).put("readOnly", true).put("replayAllowed", false) }
            }
            else -> error("尚未接入的工作配置操作")
        }
    }
}
