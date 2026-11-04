package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.features.teams.AgentSlot
import dev.pocketengine.features.teams.TeamEvidenceDiscussion
import dev.pocketengine.features.teams.TeamsModule
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.objects
import dev.pocketengine.ui.reviewedTeamCollaborationAction
import dev.pocketengine.ui.teamCollaborationStamp
import dev.pocketengine.ui.teamConversationRows
import dev.pocketengine.ui.teamLaunchable
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Owner presentation for the original TeamsModule. No worker, grant or execution state lives here. */
internal class PocketWebTeams(private val controller: PocketWorldController) {
    private data class Reading(val kind: String, val original: String, val expires: Long)
    private data class Review(val command: String, val input: String, val expires: Long)
    private var generation = 0L
    private var snapshot = JSONObject()
    private val readings = linkedMapOf<String, Reading>()
    private val reviews = linkedMapOf<String, Review>()

    companion object {
        val COMMANDS = setOf("teams.snapshot", "teams.readRun", "teams.readDefinition", "teams.reviewDefinition",
            "teams.reviewStart", "teams.reviewRemove", "teams.confirm", "teams.control", "teams.sendMessage",
            "teams.answerAssignment", "teams.probeMember", "teams.closeReading")
    }

    fun clear() { generation++; snapshot = JSONObject(); readings.clear(); reviews.clear() }
    private fun fields(value: JSONObject, vararg allowed: String) {
        require(value.keys().asSequence().all { it in allowed }) { "团队操作包含不适用的字段" }
    }
    private fun text(value: JSONObject, key: String, max: Int = 128, empty: Boolean = false): String {
        val raw = value.opt(key)
        require(raw is String && raw.length <= max && '\u0000' !in raw && (empty || raw.isNotBlank())) { "请核对$key" }
        return raw
    }
    private fun known(id: String, key: String): JSONObject = snapshot.optJSONArray(key).objects().singleOrNull { it.optString("id") == id }
        ?: error("这项原团队记录不在已读取窗口，请刷新")
    private fun reading(handle: String, kind: String, consume: Boolean = false): JSONObject {
        val original = if (consume) readings.remove(handle) else readings[handle]
        check(original != null && original.kind == kind && original.expires > SystemClock.elapsedRealtime()) { "原团队读取已关闭或过期，请重新读取" }
        return JSONObject(original.original)
    }
    private fun view(kind: String, value: JSONObject): JSONObject {
        readings.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
        while (readings.size >= 24) readings.remove(readings.keys.first())
        val handle = UUID.randomUUID().toString()
        readings[handle] = Reading(kind, value.toString(), SystemClock.elapsedRealtime() + 120_000)
        val result = JSONObject().put("kind", kind).put("viewHandle", handle).put(kind, PocketWebState.publicValue(value))
        if (kind == "run") {
            result.put("messages", PocketWebState.publicValue(JSONArray(teamConversationRows(value))))
            controller.state.tasks.singleOrNull { it.raw.optString("feature") == "teams" && it.raw.optString("externalId") == value.optString("id")
                && it.raw.optString("actorId", "owner") == "owner" && it.raw.optString("spaceId", "private") == "private" }
                ?.let { result.put("taskId", it.id) }
        }
        return result
    }
    private fun review(command: String, input: JSONObject): JSONObject {
        reviews.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
        check(reviews.size < 8) { "请先关闭已有团队操作审阅" }
        val handle = UUID.randomUUID().toString()
        reviews[handle] = Review(command, input.toString(), SystemClock.elapsedRealtime() + 120_000)
        return JSONObject().put("reviewHandle", handle).put("operation", command)
            .put("input", PocketWebState.publicValue(JSONObject(input.toString()).also { it.remove("expectedDefinition") }))
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到前台私人团队页面" }
        val window = controller.webWindowTicket(); val originalGeneration = generation
        val deliver: (JSONObject?, String?) -> Unit = { value, error ->
            if (originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()) done(value, error)
        }
        fun feature(command: String, input: JSONObject, messageId: String? = null) {
            controller.requestFeatureReceipt("teams", command, input) { result, error ->
                if (error != null || result == null) deliver(null, error ?: "原团队操作没有返回回执")
                else if (originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()) try {
                    if (command == "sendMessage") check(result.optJSONObject("messageReceipt")?.optString("messageId") == messageId
                        && result.optJSONObject("messageReceipt")?.optString("recipientMemberId") == input.getString("recipientMemberId")) { "原消息回执身份不匹配" }
                    if (command == "answerAssignment") check(result.optString("answerId") == messageId
                        && result.optString("questionId") == input.getString("questionId")) { "原问题回执身份不匹配" }
                    val receipt = JSONObject().put("command", command).put("confirmed", true).put("result", PocketWebState.publicValue(result))
                    messageId?.let { receipt.put("messageId", it) }
                    if (command in setOf("get", "pause", "resume", "feedback", "continueMessages", "cancel", "sendMessage")) {
                        check(result.optString("id") == input.getString("runId")) { "原团队运行身份不匹配" }
                        receipt.put("reading", view("run", result))
                    }
                    if (command == "start") {
                        check(result.optString("id").isNotBlank()) { "团队启动尚未取得原运行身份" }
                        snapshot.put("runs", (snapshot.optJSONArray("runs") ?: JSONArray()).put(JSONObject(result.toString())))
                        receipt.put("reading", view("run", result))
                    }
                    if (command == "save" || command == "remove") {
                        val id = input.getString("id")
                        if (command == "save") check(result.optString("id") == id) { "原团队配置保存身份不匹配" }
                        else check(result.opt("removed") == true) { "原团队配置移除尚未确认" }
                        val next = JSONArray(snapshot.optJSONArray("teams").objects().filter { it.optString("id") != id })
                        if (command == "save") next.put(JSONObject(result.toString()))
                        snapshot.put("teams", next)
                    }
                    if (command == "answerAssignment") {
                        controller.requestFeatureReceipt("teams", "get", JSONObject().put("runId", input.getString("runId"))) { current, readError ->
                            if (originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()) {
                                if (readError == null && current != null && current.optString("id") == input.getString("runId")) receipt.put("reading", view("run", current))
                                else receipt.put("readRequired", true)
                                deliver(receipt, null)
                            }
                        }
                        return@requestFeatureReceipt
                    }
                    deliver(receipt, null)
                } catch (failure: Exception) { deliver(null, failure.message ?: "原团队回执待核对") }
            }
        }
        when (action) {
            "teams.snapshot" -> {
                fields(args)
                controller.readWebTeamSnapshot { value, error ->
                    if (originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()) {
                        if (value != null && error == null) snapshot = JSONObject(value.toString())
                        deliver(value?.let { PocketWebState.publicValue(it) as JSONObject }, error)
                    }
                }
            }
            "teams.readRun" -> { fields(args, "runId"); val id = text(args, "runId"); known(id, "runs"); feature("get", JSONObject().put("runId", id)) }
            "teams.readDefinition" -> {
                fields(args, "id"); val id = text(args, "id"); known(id, "teams")
                controller.readWebTeamSnapshot { value, error ->
                    if (originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()) {
                        if (value == null || error != null) deliver(null, error ?: "原团队配置尚未读取")
                        else try { snapshot = JSONObject(value.toString()); deliver(view("definition", known(id, "teams")), null) }
                        catch (failure: Exception) { deliver(null, failure.message ?: "原团队配置已变化") }
                    }
                }
            }
            "teams.closeReading" -> { fields(args, "viewHandle", "reviewHandle"); readings.remove(args.optString("viewHandle")); reviews.remove(args.optString("reviewHandle")); deliver(JSONObject().put("closed", true), null) }
            "teams.reviewDefinition" -> {
                fields(args, "viewHandle", "name", "mode", "leaderId", "members")
                val old = if (args.has("viewHandle")) reading(text(args, "viewHandle"), "definition") else null
                val mode = text(args, "mode", 24)
                require(mode in setOf("collaborate", "debate") || old?.optString("mode") == "produce" && mode == "produce") { "文件协作须沿原项目范围配置" }
                val supplied = args.getJSONArray("members"); require(supplied.length() in 1..4) { "请选择一至四名真实成员" }
                require(supplied.objects().size == supplied.length()) { "成员必须是完整的原对象" }
                val members = JSONArray(); val ids = mutableSetOf<String>()
                supplied.objects().forEachIndexed { index, member ->
                    fields(member, "id", "name", "role", "model")
                    val id = text(member, "id", 64); require(ids.add(id)) { "成员身份不能重复" }
                    val previous = old?.optJSONArray("members").objects().singleOrNull { it.optString("id") == id }
                    val captured = previous?.let { JSONObject(it.toString()) } ?: JSONObject().put("id", id).put("runner", "model").put("device", "phone")
                    captured.put("name", text(member, "name", 48).trim()).put("role", text(member, "role", 1200).trim())
                    val model = text(member, "model", 200, true).trim()
                    if (previous?.has("modelTarget") == true) require(model == previous.optString("model")) { "保留原模型目标，请在原模型设置中修改" }
                    captured.put("model", model)
                    members.put(AgentSlot.normalize(captured, index))
                }
                val leader = text(args, "leaderId", 64); require(leader in ids)
                TeamsModule.validateDefinitionMembers(members)
                val input = JSONObject().put("id", old?.getString("id") ?: UUID.randomUUID().toString()).put("name", text(args, "name", 80).trim())
                    .put("mode", mode).put("leaderId", leader).put("members", members).put("expectedDefinition", old?.toString() ?: "")
                input.put("discussionFormat", if (mode == "debate") old?.optString("discussionFormat")?.takeIf { it.isNotBlank() } ?: TeamEvidenceDiscussion.FORMAT else "classic")
                if (input.optString("discussionFormat") == TeamEvidenceDiscussion.FORMAT) require(members.length() >= 2 && members.objects().all { it.optString("runner") == "model" && it.optJSONArray("dependsOn")?.length() == 0 }) { "证据讨论至少需要两名独立模型成员" }
                deliver(review("save", input), null)
            }
            "teams.reviewStart" -> {
                fields(args, "viewHandle", "goal", "rounds")
                val team = reading(text(args, "viewHandle"), "definition")
                check(!controller.modelSelectionPending() && !controller.state.chatSubmitting && !controller.state.phoneSubmitting && teamLaunchable(team, controller.state)) { "请先核对当前主模型、实际执行器与原团队范围" }
                val rounds = args.opt("rounds"); require(rounds is Int && rounds in 1..2)
                val input = JSONObject().put("teamId", team.getString("id")).put("goal", text(args, "goal", 8000).trim())
                    .put("rounds", if (team.optString("discussionFormat") == TeamEvidenceDiscussion.FORMAT) 2 else rounds).put("expectedDefinition", team.toString())
                controller.state.conversationId?.let { input.put("conversationId", it) }
                deliver(review("start", input).put("definition", PocketWebState.publicValue(team)), null)
            }
            "teams.reviewRemove" -> { fields(args, "viewHandle"); val team = reading(text(args, "viewHandle"), "definition"); deliver(review("remove", JSONObject().put("id", team.getString("id")).put("expectedDefinition", team.toString())).put("definition", PocketWebState.publicValue(team)), null) }
            "teams.confirm" -> {
                fields(args, "reviewHandle")
                val captured = reviews.remove(text(args, "reviewHandle")) ?: error("原操作审阅已使用或关闭，请先核对原回执")
                check(captured.expires > SystemClock.elapsedRealtime()) { "原操作审阅已过期" }
                feature(captured.command, JSONObject(captured.input))
            }
            "teams.probeMember" -> {
                fields(args, "viewHandle", "memberId")
                val team = reading(text(args, "viewHandle"), "definition"); val member = text(args, "memberId", 64)
                require(team.optJSONArray("members").objects().any { it.optString("id") == member })
                feature("probeMember", JSONObject().put("teamId", team.getString("id")).put("memberId", member))
            }
            "teams.control", "teams.sendMessage", "teams.answerAssignment" -> {
                fields(args, "viewHandle", "command", "memberId", "text", "mode", "quoteMessageId", "messageId")
                val run = reading(text(args, "viewHandle"), "run", true)
                val member = args.optString("memberId").takeIf { it.isNotEmpty() }
                if (action == "teams.control") {
                    val command = text(args, "command", 24)
                    val input = if (command == "cancel") {
                        require(member == null); JSONObject().put("runId", run.getString("id")).put("expectedRevision", run.getLong("revision")).put("expectedGeneration", run.getInt("generation"))
                    } else reviewedTeamCollaborationAction(run, teamCollaborationStamp(run), command, member, args.optString("text"))
                    feature(command, input)
                } else {
                    val slot = run.optJSONArray("members").objects().singleOrNull { it.optString("id") == member && !it.optBoolean("exited") } ?: error("请选择这次原运行的成员")
                    val messageId = text(args, "messageId"); require(messageId.matches(Regex("[a-f0-9-]{36}")))
                    val input = JSONObject().put("runId", run.getString("id")).put("expectedRevision", run.getLong("revision")).put("expectedGeneration", run.getInt("generation"))
                        .put("memberId", member).put("recipientMemberId", member).put("expectedMemberGeneration", slot.getInt("generation"))
                    val quoteId = args.optString("quoteMessageId")
                    val quote = teamConversationRows(run).singleOrNull { it.optString("messageId") == quoteId }
                    require(quoteId.isEmpty() || quote != null) { "引用不属于这次原运行" }
                    input.put("quoteMessageId", quoteId)
                    if (action == "teams.answerAssignment") {
                        check(quote != null && quote.optString("kind") == "assignment_question" && quote.optString("memberId") == member && quote.optString("state") == "pending") { "请回答这名成员的原问题" }
                        input.put("assignmentId", quote.getString("assignmentId")).put("questionId", quote.getString("questionId")).put("answerId", messageId)
                            .put("answer", text(args, "text", 4000).trim()).put("continueSafely", true).put("mode", "reply")
                        feature("answerAssignment", input, messageId)
                    } else {
                        val mode = text(args, "mode", 24); require(mode in setOf("discussion", "queue"))
                        input.put("messageId", messageId).put("text", text(args, "text", 4000).trim()).put("mode", mode)
                        if (run.has("workPlan") && quote?.optString("assignmentId")?.isNotBlank() == true) input.put("assignmentId", quote.getString("assignmentId"))
                        feature("sendMessage", input, messageId)
                    }
                }
            }
            else -> error("团队操作尚未接入")
        }
    }
}
