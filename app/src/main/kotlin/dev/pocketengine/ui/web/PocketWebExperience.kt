package dev.pocketengine.ui.web

import dev.pocketengine.ui.ExperienceSkillReviewGuard
import dev.pocketengine.ui.MemoryLearningReviewGuard
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Opaque review windows over the original learning workflow, SkillBook and task feedback. */
internal class PocketWebExperience(private val controller: PocketWorldController) {
    private data class Pin(val tag: String, val body: String, val owner: Any, val expires: Long)
    private data class Operation(val owner: Any, var requestId: String = "", var settled: Boolean = false, var result: JSONObject? = null, var error: String? = null)
    private val pins = linkedMapOf<String, Pin>()
    private val operations = linkedMapOf<String, Operation>()
    private val pages = mutableMapOf<String, JSONObject>()
    private val manifests = mutableMapOf<String, JSONObject>()
    private var generation = 0L
    companion object {
        val COMMANDS = setOf("experience.snapshot", "experience.procedure", "experience.skill", "experience.draft.review", "experience.draft.start", "experience.operation.query", "experience.operation.stop", "experience.inspect", "experience.import.review", "experience.confirm", "experience.read", "experience.approve.review", "experience.apps", "experience.app.review", "experience.quality.list", "experience.quality.review", "experience.policy.get", "experience.policy.review", "experience.close")
    }
    fun clear() { generation++; pins.clear(); operations.clear(); pages.clear(); manifests.clear() }
    private fun fields(args: JSONObject, vararg names: String) { require(args.keys().asSequence().all { it in names }) { "原经验操作包含不适用字段" } }
    private fun text(args: JSONObject, name: String, max: Int = 128): String { val value = args.opt(name); require(value is String && value.isNotBlank() && value.length <= max && '\u0000' !in value) { "请重新核对原经验参数" }; return value }
    private fun issue(tag: String, body: JSONObject): String { while (pins.size >= 64) pins.remove(pins.keys.first()); val id = UUID.randomUUID().toString(); pins[id] = Pin(tag, body.toString(), controller.webWindowTicket(), System.currentTimeMillis() + 600_000); return id }
    private fun select(handle: String, tag: String, consume: Boolean = false): JSONObject { val pin = pins[handle] ?: error("原经验审阅已关闭，请重新读取"); check(pin.tag == tag && pin.owner == controller.webWindowTicket() && pin.expires >= System.currentTimeMillis()) { "原经验审阅已变化或过期" }; if (consume) pins.remove(handle); return JSONObject(pin.body) }
    private fun call(command: String, input: JSONObject, done: (JSONObject?, String?) -> Unit, project: (JSONObject) -> JSONObject = { it }) {
        val ticket = generation
        controller.requestOwnerDomainReceipt("learning", command, input) { value, error ->
            if (ticket != generation) done(null, "原经验页面已关闭，请重新核对原结果")
            else if (value == null) done(null, error ?: "原经验操作没有确定回执")
            else runCatching { project(value) }.fold({ done(PocketWebState.publicValue(it) as JSONObject, null) }, { done(null, it.message ?: "原经验回执不可核对") })
        }
    }
    private fun procedureInput(record: JSONObject) = JSONObject().put("recordId", record.getString("id")).put("expectedRevision", record.getLong("revision"))
    private fun skillInput(skill: JSONObject) = JSONObject().put("id", skill.getString("id")).put("revision", skill.getLong("revision")).put("digest", skill.getString("digest"))
    private fun currentProcedure(record: JSONObject, done: (JSONObject?, String?) -> Unit, next: () -> Unit) {
        call("snapshot", JSONObject(), { result, error -> if (result == null) done(null, error) else next() }) { result ->
            val values = result.getJSONArray("procedures"); val actual = (0 until values.length()).map { values.getJSONObject(it) }.singleOrNull { it.optString("id") == record.optString("id") }
            check(ExperienceSkillReviewGuard.sameProcedure(record, actual)) { "原方法记忆已变化，请重新读取" }; result
        }
    }
    private fun currentSkill(skill: JSONObject, done: (JSONObject?, String?) -> Unit, next: (JSONObject) -> Unit) {
        val ticket = generation
        controller.requestOwnerDomainReceipt("learning", "getSkill", JSONObject().put("id", skill.getString("id"))) { result, error ->
            if (ticket != generation) done(null, "原技能页面已关闭") else if (result == null) done(null, error) else try {
                val actual = result.getJSONObject("skill"); check(ExperienceSkillReviewGuard.sameSkill(skill, actual) && skill.optLong("executionGeneration", 0) == actual.optLong("executionGeneration", 0)) { "原技能版本、来源或批准已变化" }; next(actual)
            } catch (failure: Exception) { done(null, failure.message) }
        }
    }
    private fun review(command: String, payload: JSONObject, before: JSONObject = JSONObject()): JSONObject =
        JSONObject().put("reviewHandle", issue("review", JSONObject().put("command", command).put("payload", payload).put("before", before))).put("operation", command).put("before", before).put("payload", payload).put("modelCalled", false)
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到原私人经验管理页" }
        when (action) {
            "experience.snapshot" -> { fields(args); call("snapshot", JSONObject(), done) }
            "experience.procedure" -> {
                fields(args, "id"); val id = text(args, "id")
                call("snapshot", JSONObject(), done) { result ->
                    val values = result.getJSONArray("procedures"); val record = (0 until values.length()).map { values.getJSONObject(it) }.singleOrNull { it.optString("id") == id } ?: error("原方法记忆已不在当前范围")
                    check(ExperienceSkillReviewGuard.eligible(record)); JSONObject().put("record", record).put("procedureHandle", issue("procedure", record))
                }
            }
            "experience.skill" -> {
                fields(args, "id"); call("getSkill", JSONObject().put("id", text(args, "id")), done) { result -> val skill = result.getJSONObject("skill"); check(skill.has("origin")) { "普通技能请从技能管理页审阅" }; result.put("skillHandle", issue("skill", skill)) }
            }
            "experience.draft.review" -> {
                fields(args, "procedureHandle"); val record = select(text(args, "procedureHandle"), "procedure")
                currentProcedure(record, done) { done(review("draftExperience", procedureInput(record), record), null) }
            }
            "experience.draft.start" -> {
                fields(args, "reviewHandle"); val reviewed = select(text(args, "reviewHandle"), "review", true); val command = reviewed.getString("command"); require(command in setOf("draftExperience", "draftAppRevision")); val record = reviewed.getJSONObject("before")
                check(operations.values.none { !it.settled }) { "请先核对原起草请求" }
                currentProcedure(record, done) {
                    while (operations.size >= 8) operations.remove(operations.keys.first())
                    val id = UUID.randomUUID().toString(); val operation = Operation(controller.webWindowTicket()); operations[id] = operation; val ticket = generation
                    try {
                        operation.requestId = controller.requestOwnerDomainReceipt("learning", command, reviewed.getJSONObject("payload")) { value, error ->
                            operation.settled = true
                            if (ticket != generation) operation.error = "原经验页面已关闭，请核对原记录"
                            else if (value == null) operation.error = error ?: "原模型结果待核对，没有自动重发"
                            else try {
                                if (command == "draftExperience") {
                                    check(value.getString("recordId") == record.getString("id") && value.getLong("recordRevision") == record.getLong("revision") && ExperienceSkillReviewGuard.sourceMatches(record, value.getJSONObject("inspection")))
                                    value.put("draftHandle", issue("draft", JSONObject().put("record", record).put("draft", value.getJSONObject("draft")).put("inspection", value.getJSONObject("inspection"))))
                                }
                                operation.result = value
                            } catch (failure: Exception) { operation.error = failure.message ?: "原草稿来源版本不可核对" }
                        }
                        done(JSONObject().put("operationHandle", id).put("requestId", operation.requestId).put("settled", operation.settled), null)
                    } catch (failure: Exception) { operation.settled = true; operation.error = failure.message; done(null, failure.message) }
                }
            }
            "experience.operation.query", "experience.operation.stop" -> {
                fields(args, "operationHandle"); val operation = operations[text(args, "operationHandle")] ?: error("原起草窗口已关闭，请重新读取原记录"); check(operation.owner == controller.webWindowTicket())
                if (action.endsWith(".stop")) call("cancelLearning", JSONObject().put("requestId", operation.requestId), done)
                else done(JSONObject().put("requestId", operation.requestId).put("settled", operation.settled).put("result", operation.result?.let { PocketWebState.publicValue(it) } ?: JSONObject.NULL).put("error", operation.error ?: JSONObject.NULL), null)
            }
            "experience.inspect" -> {
                fields(args, "draftHandle", "manifest"); val oldHandle = text(args, "draftHandle"); val old = select(oldHandle, "draft"); val record = old.getJSONObject("record"); val draft = old.getJSONObject("draft").put("manifest", text(args, "manifest", 64000))
                currentProcedure(record, done) { call("inspectExperience", procedureInput(record).put("draft", draft).put("options", JSONObject()), done) { value ->
                    check(ExperienceSkillReviewGuard.sourceMatches(record, value.getJSONObject("inspection"))); pins.remove(oldHandle); value.put("draft", draft).put("draftHandle", issue("draft", JSONObject().put("record", record).put("draft", draft).put("inspection", value.getJSONObject("inspection"))))
                } }
            }
            "experience.import.review" -> {
                fields(args, "draftHandle"); val original = select(text(args, "draftHandle"), "draft"); val record = original.getJSONObject("record")
                currentProcedure(record, done) { val payload = ExperienceSkillReviewGuard.importPayload(record, record, original.getJSONObject("draft"), original.getJSONObject("draft"), original.getJSONObject("inspection")); done(review("importExperience", payload, original), null) }
            }
            "experience.read" -> {
                fields(args, "skillHandle", "path", "offset"); val handle = text(args, "skillHandle"); val skill = select(handle, "skill"); val path = text(args, "path", 240); val offset = args.opt("offset"); require(offset is Int && offset >= 0)
                val key = "$handle:$path"; val prior = pages[key]; check(offset == 0 || prior != null && prior.optBoolean("hasMore") && prior.getInt("nextOffset") == offset) { "请从原文件首页连续读取" }
                if (offset == 0) { pages.remove(key); if (path == "SKILL.md") manifests.remove(handle) }
                call("readSkill", skillInput(skill).put("path", path).put("offset", offset).put("length", 8000), done) { value ->
                    check(value.optString("id") == skill.getString("id") && value.optLong("revision") == skill.getLong("revision") && value.optString("digest") == skill.getString("digest") && value.optString("path") == path)
                    check(value.getInt("nextOffset") == offset + value.getString("content").length) { "原技能页范围不连续" }; pages[key] = JSONObject(value.toString())
                    if (path == "SKILL.md" && !value.optBoolean("hasMore", true)) manifests[handle] = JSONObject(value.toString()); value
                }
            }
            "experience.approve.review" -> {
                fields(args, "skillHandle"); val handle = text(args, "skillHandle"); val skill = select(handle, "skill")
                currentSkill(skill, done) { actual -> done(review("approveExperience", ExperienceSkillReviewGuard.approvalPayload(skill, actual, manifests[handle]).put("expectedExecutionGeneration", skill.optLong("executionGeneration", 0)), skill), null) }
            }
            "experience.apps" -> {
                fields(args, "procedureHandle"); val record = select(text(args, "procedureHandle"), "procedure")
                call("procedureAppIdentities", procedureInput(record), done) { value -> val apps = value.getJSONArray("apps"); for (i in 0 until apps.length()) apps.getJSONObject(i).put("appHandle", issue("app", JSONObject().put("record", record).put("app", JSONObject(apps.getJSONObject(i).toString())))); value }
            }
            "experience.app.review" -> {
                fields(args, "appHandle", "baseHandle", "lesson"); val selected = select(text(args, "appHandle"), "app"); val record = selected.getJSONObject("record"); val app = selected.getJSONObject("app"); require(args.opt("lesson") is Boolean)
                val base = if (args.optString("baseHandle").isBlank()) null else select(text(args, "baseHandle"), "procedure")
                if (base != null) check(base.optJSONObject("appKnowledge")?.let { it.optString("packageName") == app.getString("packageName") && it.optString("appVersion") == app.getString("appVersion") && it.optString("stage") == "verified" } == true) { "旧稿不是此原 App 版本的已核实方法" }
                val payload = procedureInput(record).put("packageName", app.getString("packageName")).put("appVersion", app.getString("appVersion")).put("baseId", base?.getString("id") ?: "").put("baseRevision", base?.getLong("revision") ?: 0).put("lesson", args.getBoolean("lesson"))
                currentProcedure(record, done) { done(review("draftAppRevision", payload, record), null) }
            }
            "experience.quality.list" -> {
                fields(args, "handle", "kind", "cursor"); val kind = text(args, "kind"); require(kind in setOf("skill", "procedure")); val original = select(text(args, "handle"), kind)
                val payload = if (kind == "skill") skillInput(original) else JSONObject().put("recordId", original.getString("id")).put("revision", original.getLong("revision")).put("cursor", args.optString("cursor"))
                call(if (kind == "skill") "skillQualityApplications" else "appQualityApplications", payload, done) { value ->
                    val applications = value.getJSONArray("applications"); for (i in 0 until applications.length()) { val application = applications.getJSONObject(i); application.put("applicationHandle", issue("application", JSONObject().put("kind", kind).put("original", original).put("application", JSONObject(application.toString())))) }; value
                }
            }
            "experience.quality.review" -> {
                fields(args, "applicationHandle", "outcome", "note"); val selected = select(text(args, "applicationHandle"), "application"); val original = selected.getJSONObject("original"); val application = selected.getJSONObject("application"); val kind = selected.getString("kind")
                val outcome = text(args, "outcome"); require(outcome in setOf("verified", "skill_failed", "verifier_rejected", "environment", "cancelled", "model_error")); val payload = if (kind == "skill") skillInput(original) else JSONObject().put("recordId", original.getString("id")).put("revision", original.getLong("revision"))
                payload.put("taskId", application.getString("taskId")).put("operationId", application.getString("operationId")).put("outcome", outcome).put("note", text(args, "note", 1000))
                done(review(if (kind == "skill") "reviewSkillQuality" else "reviewAppQuality", payload, original), null)
            }
            "experience.policy.get" -> {
                fields(args, "taskId"); call("getAppLearningPolicy", JSONObject().put("taskId", text(args, "taskId")), done) { value -> value.put("policyHandle", issue("policy", JSONObject(value.toString()))) }
            }
            "experience.policy.review" -> {
                fields(args, "policyHandle", "enabled", "packageName"); val selected = select(text(args, "policyHandle"), "policy"); require(args.opt("enabled") is Boolean); val enabled = args.getBoolean("enabled")
                val payload = JSONObject().put("taskId", selected.getString("taskId")).put("expectedRevision", selected.getJSONObject("policy").getLong("revision")).put("enabled", enabled).put("packages", if (enabled) JSONArray().put(text(args, "packageName", 240)) else JSONArray()).put("maxCalls", 1).put("confirmed", enabled)
                done(review("setAppLearningPolicy", payload, selected), null)
            }
            "experience.confirm" -> {
                fields(args, "reviewHandle"); val selected = select(text(args, "reviewHandle"), "review", true); val command = selected.getString("command"); val payload = selected.getJSONObject("payload"); val before = selected.getJSONObject("before")
                when (command) {
                    "importExperience" -> currentProcedure(before.getJSONObject("record"), done) { call(command, payload, done) { value ->
                        check(ExperienceSkillReviewGuard.validImport(before.getJSONObject("record"), before.getJSONObject("inspection"), value)) { "原导入结果待核对，请读取技能列表；没有重复提交" }; value.put("skillHandle", issue("skill", value.getJSONObject("skill")))
                    } }
                    "approveExperience" -> currentSkill(before, done) { actual ->
                        check(actual.optBoolean("contentVerified") && actual.optString("originStatus") == "current")
                        call(command, payload, done) { value -> val skill = value.getJSONObject("skill"); check(ExperienceSkillReviewGuard.sameSkill(before, skill) && skill.optBoolean("enabled") && skill.optString("approvedDigest") == skill.getString("digest")) { "原启用效果待核对，请重新读取技能" }; value }
                    }
                    "reviewSkillQuality", "reviewAppQuality", "setAppLearningPolicy" -> call(command, payload, done)
                    else -> error("这不是可确认的原经验修改")
                }
            }
            "experience.close" -> { fields(args, "handle"); val id = text(args, "handle"); pins.remove(id); manifests.remove(id); pages.keys.removeAll { it.startsWith("$id:") }; done(JSONObject().put("closed", true), null) }
            else -> error("原经验操作未接入")
        }
    }
}
