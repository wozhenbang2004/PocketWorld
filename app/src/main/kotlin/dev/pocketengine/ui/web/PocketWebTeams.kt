package dev.pocketengine.ui.web

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import dev.pocketengine.features.teams.AgentSlot
import dev.pocketengine.features.teams.TeamPhoneHost
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
import java.util.concurrent.atomic.AtomicBoolean

/** Owner presentation for the original TeamsModule. No worker, grant or execution state lives here. */
internal class PocketWebTeams(private val controller: PocketWorldController) {
    private data class Reading(val kind: String, val original: String)
    private data class Review(val command: String, val input: String, val expires: Long, val phonePage: TeamPhoneHost.Page? = null, val phoneReview: TeamPhoneHost.Review? = null, val productionSources: String? = null)
    private data class PhonePage(val original: TeamPhoneHost.Page, val definition: String, val input: String, val expires: Long)
    @Volatile private var generation = 0L
    private var snapshot = JSONObject()
    private val readings = linkedMapOf<String, Reading>()
    private val reviews = linkedMapOf<String, Review>()
    private val phonePages = linkedMapOf<String, PhonePage>()
    private val main = Handler(Looper.getMainLooper())
    private val phoneInFlight = AtomicBoolean(false)
    @Volatile private var workingPhonePage: TeamPhoneHost.Page? = null

    companion object {
        val COMMANDS = setOf("teams.attention", "teams.readAttention", "teams.snapshot", "teams.readRun", "teams.readDefinition", "teams.reviewDefinition",
            "teams.reviewStart", "teams.reviewRemove", "teams.confirm", "teams.control", "teams.sendMessage",
            "teams.answerAssignment", "teams.probeMember", "teams.closeReading", "teams.reviewPhoneStart", "teams.reviewMember")
    }

    fun clear() {
        generation++; snapshot = JSONObject(); readings.clear()
        phonePages.values.forEach { runCatching { it.original.close() } }; phonePages.clear()
        reviews.values.forEach { runCatching { it.phonePage?.close() } }; reviews.clear()
        runCatching { workingPhonePage?.close() }
    }
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
    private fun productionSources(): JSONObject = PocketWebTeamProduction.sources(controller.state.feature("workspace").optJSONArray("artifacts"),
        controller.state.tasks.map { task -> JSONObject(task.raw.toString()).put("id", task.id).put("goal", task.goal) })
    private fun captureSnapshot(value: JSONObject): JSONObject {
        val current = value.optJSONArray("runs").objects()
        val currentIds = current.map { it.getString("id") }.toSet()
        // Archived rows are retained run summaries, not missing work. Put them
        // in the same owner directory so readRun can open the original record.
        // Prefer a hot row if the same identity is already present there.
        val retained = current + value.optJSONArray("history").objects().filter { it.getString("id") !in currentIds }
        return JSONObject(value.toString()).put("runs", JSONArray(retained)).put("productionSources", productionSources())
    }
    private fun reading(handle: String, kind: String, consume: Boolean = false): JSONObject {
        val original = if (consume) readings.remove(handle) else readings[handle]
        check(original != null && original.kind == kind) { "原团队读取已关闭，请重新读取" }
        return JSONObject(original.original)
    }
    private fun view(kind: String, value: JSONObject): JSONObject {
        while (readings.size >= 24) readings.remove(readings.keys.first())
        val handle = UUID.randomUUID().toString()
        // Reading/editing is bound to the visible native window, not a timer.
        // clear()/closeReading retire it; mutations still compare the original
        // definition or run/member generations before accepting any change.
        readings[handle] = Reading(kind, value.toString())
        val result = JSONObject().put("kind", kind).put("viewHandle", handle).put(kind, PocketWebState.publicValue(value))
        if (kind == "run") {
            result.put("messages", PocketWebState.publicValue(JSONArray(teamConversationRows(value))))
            controller.state.tasks.singleOrNull { it.raw.optString("feature") == "teams" && it.raw.optString("externalId") == value.optString("id")
                && it.raw.optString("actorId") == "owner" && it.raw.optString("spaceId") == "private" }
                ?.let { result.put("taskId", it.id) }
        }
        return result
    }
    private fun attentionFocus(value: JSONObject, locator: JSONObject): JSONObject {
        val member = value.optJSONArray("members").objects().singleOrNull { it.optString("id") == locator.getString("memberId") }
        val sameGeneration = value.optInt("generation") == locator.getInt("generation") && member?.optInt("generation") == locator.getInt("memberGeneration")
        if (!sameGeneration) return JSONObject().put("kind", "changed").put("message", "原成员或运行已变化，已打开原协作记录；请核对当前进展。")
        val item = if (locator.has("workItemId")) value.optJSONObject("workPlan")?.optJSONArray("items").objects().singleOrNull {
            it.optString("workItemId") == locator.getString("workItemId") && it.optString("memberId") == locator.getString("memberId")
                && (!locator.has("assignmentId") || it.optString("assignmentId") == locator.getString("assignmentId"))
        } else null
        if (locator.has("workItemId") && item == null) return JSONObject().put("kind", "changed").put("message", "原工作项已变化，已打开原协作记录；没有跳转到其他分工。")
        if (locator.has("questionId")) {
            val question = teamConversationRows(value).singleOrNull { it.optString("questionId") == locator.getString("questionId")
                && it.optString("assignmentId") == locator.getString("assignmentId") && it.optString("memberId") == locator.getString("memberId") }
            if (question == null || question.optString("state") != "pending") return JSONObject().put("kind", "changed").put("message", "这条原问题已处理或不再待回答，已打开原协作记录。")
            return JSONObject().put("kind", "question").put("memberId", member!!.getString("id")).put("messageId", question.getString("messageId"))
        }
        return JSONObject().put("kind", if (item == null) "member" else "workItem").put("memberId", member!!.getString("id")).also { if (item != null) it.put("workItemId", item.getString("workItemId")) }
    }

    private fun review(command: String, input: JSONObject): JSONObject {
        reviews.entries.removeAll { entry -> (entry.value.expires <= SystemClock.elapsedRealtime()).also { if (it) runCatching { entry.value.phonePage?.close() } } }
        check(reviews.size < 8) { "请先关闭已有团队操作审阅" }
        val handle = UUID.randomUUID().toString()
        reviews[handle] = Review(command, input.toString(), SystemClock.elapsedRealtime() + 120_000,
            productionSources = if (command == "save" && input.optString("mode") == "produce") snapshot.getJSONObject("productionSources").toString() else null)
        return JSONObject().put("reviewHandle", handle).put("operation", command)
            .put("input", PocketWebState.publicValue(JSONObject(input.toString()).also { it.remove("expectedDefinition") }))
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到前台私人团队页面" }
        val window = controller.webWindowTicket(); val originalGeneration = generation
        val deliver: (JSONObject?, String?) -> Unit = { value, error ->
            if (originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()) done(value, error)
        }
        fun currentWindow() = originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()
        fun phoneWork(page: TeamPhoneHost.Page? = null, work: () -> JSONObject, accept: (JSONObject) -> Unit) {
            if (!phoneInFlight.compareAndSet(false, true)) { runCatching { page?.close() }; error("正在核对这次手机协作，请等待原回执") }
            workingPhonePage = page
            try {
                controller.runTeamPhoneWork {
                    val result = runCatching { check(currentWindow()) { "原团队页面已关闭" }; work() }
                    main.post {
                        phoneInFlight.set(false)
                        if (!currentWindow()) {
                            runCatching { workingPhonePage?.close() }; workingPhonePage = null
                            // Complete the original bridge command even when its presentation was retired.
                            // The bridge owns outcome-unknown delivery; never populate the next page's handles.
                            done(null, "原手机协作窗口已关闭；请查询原团队结果，没有自动重放")
                        }
                        else {
                            val completedPage = workingPhonePage ?: page
                            workingPhonePage = null
                            result.fold({ value -> try { accept(value) } catch (failure: Exception) { runCatching { completedPage?.close() }; deliver(null, failure.message ?: "原手机协作回执待核对") } },
                                { failure -> runCatching { completedPage?.close() }; deliver(null, failure.message ?: "原手机协作暂不可用") })
                        }
                    }
                }
            } catch (failure: Exception) { phoneInFlight.set(false); workingPhonePage = null; runCatching { page?.close() }; throw failure }
        }
        fun deliverReceipt(receipt: JSONObject) {
            val reading = receipt.optJSONObject("reading")
            if (reading == null || reading.optString("kind") != "run" || reading.has("taskId")) { deliver(receipt, null); return }
            controller.readWebTeamTaskId(reading.getJSONObject("run").getString("id")) { taskId, error ->
                if (currentWindow()) {
                    if (taskId != null) reading.put("taskId", taskId)
                    else reading.put("taskLookupNotice", error ?: "未找到这次协作的原任务绑定；记录仍可查看，没有猜测另一个任务。")
                    deliver(receipt, null)
                } else done(null, "原团队窗口已关闭；操作结果保留，请回原记录核对，没有自动重发")
            }
        }
        fun feature(command: String, input: JSONObject, messageId: String? = null, attention: JSONObject? = null) {
            controller.requestFeatureReceipt("teams", command, input) { result, error ->
                if (!currentWindow()) { done(null, "原团队窗口已关闭；请回原运行核对结果，没有自动重发"); return@requestFeatureReceipt }
                if (error != null || result == null) deliver(null, error ?: "原团队操作没有返回回执")
                else if (originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()) try {
                    if (command == "sendMessage") check(result.optJSONObject("messageReceipt")?.optString("messageId") == messageId
                        && result.optJSONObject("messageReceipt")?.optString("recipientMemberId") == input.getString("recipientMemberId")) { "原消息回执身份不匹配" }
                    if (command == "answerAssignment") check(result.optString("answerId") == messageId
                        && result.optString("questionId") == input.getString("questionId")) { "原问题回执身份不匹配" }
                    val receipt = JSONObject().put("command", command).put("confirmed", true).put("result", PocketWebState.publicValue(result))
                    messageId?.let { receipt.put("messageId", it) }
                    if (command in setOf("get", "pause", "resume", "feedback", "continueMessages", "cancel", "sendMessage", "configureMember")) {
                        check(result.optString("id") == input.getString("runId")) { "原团队运行身份不匹配" }
                        receipt.put("reading", view("run", result))
                        if (attention != null) receipt.put("focus", attentionFocus(result, attention))
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
                        if (command == "save") receipt.put("reading", view("definition", result))
                    }
                    if (command == "answerAssignment") {
                        controller.requestFeatureReceipt("teams", "get", JSONObject().put("runId", input.getString("runId"))) { current, readError ->
                            if (originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()) {
                                if (readError == null && current != null && current.optString("id") == input.getString("runId")) receipt.put("reading", view("run", current))
                                else receipt.put("readRequired", true)
                                deliverReceipt(receipt)
                            } else done(null, "原回答窗口已关闭；回答结果保留，请回原问题核对，没有自动重发")
                        }
                        return@requestFeatureReceipt
                    }
                    deliverReceipt(receipt)
                } catch (failure: Exception) { deliver(null, failure.message ?: "原团队回执待核对") }
            }
        }
        when (action) {
            "teams.attention" -> {
                fields(args, "afterRunId", "afterItemId", "limit")
                val after = if (args.has("afterRunId")) text(args, "afterRunId", 36, empty = true) else ""
                val afterItem = if (args.has("afterItemId")) text(args, "afterItemId", 320, empty = true) else ""
                val limit = if (args.has("limit")) args.getInt("limit") else 8
                require(limit in 1..8) { "团队待处理每页最多读取八次协作" }
                controller.readWebTeamAttention(after, afterItem, limit) { value, error ->
                    if (currentWindow()) deliver(value?.let { PocketWebState.publicValue(it) as JSONObject }, error)
                    else done(null, "原待处理窗口已关闭，请重新读取")
                }
            }
            "teams.readAttention" -> {
                fields(args, "runId", "memberId", "generation", "memberGeneration", "workItemId", "assignmentId", "questionId")
                val id = text(args, "runId"); text(args, "memberId")
                require(args.opt("generation") is Int && args.opt("memberGeneration") is Int) { "原团队定位缺少运行与成员代次" }
                if (args.has("workItemId")) text(args, "workItemId")
                if (args.has("assignmentId")) text(args, "assignmentId")
                if (args.has("questionId")) { text(args, "questionId"); text(args, "assignmentId") }
                controller.readWebTeamSnapshot { value, error ->
                    if (currentWindow()) {
                        if (value == null || error != null) deliver(null, error ?: "原团队目录尚未读取")
                        else try {
                            snapshot = captureSnapshot(value); known(id, "runs")
                            feature("get", JSONObject().put("runId", id), attention = JSONObject(args.toString()))
                        } catch (failure: Exception) { deliver(null, failure.message ?: "原团队记录已不可用") }
                    } else done(null, "原团队定位窗口已关闭，请重新打开原待处理记录")
                }
            }
            "teams.snapshot" -> {
                fields(args)
                controller.readWebTeamSnapshot { value, error ->
                    if (originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()) {
                        if (value != null && error == null) { snapshot = captureSnapshot(value); deliver(PocketWebState.publicValue(snapshot) as JSONObject, null) }
                        else deliver(null, error ?: "原团队目录尚未读取")
                    }
                }
            }
            "teams.readRun" -> { fields(args, "runId"); val id = text(args, "runId"); known(id, "runs"); feature("get", JSONObject().put("runId", id)) }
            "teams.readDefinition" -> {
                fields(args, "id"); val id = text(args, "id"); known(id, "teams")
                controller.readWebTeamSnapshot { value, error ->
                    if (originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()) {
                        if (value == null || error != null) deliver(null, error ?: "原团队配置尚未读取")
                        else try { snapshot = captureSnapshot(value); deliver(view("definition", known(id, "teams")), null) }
                        catch (failure: Exception) { deliver(null, failure.message ?: "原团队配置已变化") }
                    }
                }
            }
            "teams.closeReading" -> {
                fields(args, "viewHandle", "reviewHandle", "phoneHandle"); readings.remove(args.optString("viewHandle"))
                reviews.remove(args.optString("reviewHandle"))?.phonePage?.let { runCatching { it.close() } }
                phonePages.remove(args.optString("phoneHandle"))?.original?.let { runCatching { it.close() } }
                deliver(JSONObject().put("closed", true), null)
            }
            "teams.reviewDefinition" -> {
                fields(args, "viewHandle", "name", "mode", "leaderId", "members")
                val old = if (args.has("viewHandle")) reading(text(args, "viewHandle"), "definition") else null
                val mode = text(args, "mode", 24)
                require(mode in setOf("collaborate", "debate", "produce")) { "请选择已接入的团队协作方式" }
                val supplied = args.getJSONArray("members"); require(supplied.length() in 1..4) { "请选择一至四名真实成员" }
                require(supplied.objects().size == supplied.length()) { "成员必须是完整的原对象" }
                val members = JSONArray(); val ids = mutableSetOf<String>()
                supplied.objects().forEachIndexed { index, member ->
                    fields(member, "id", "name", "avatarShape", "role", "model", "modeId", "runner", "device", "assignment", "dependsOn", "externalExecutionRiskAcknowledged", "writeFiles", "remoteProduction")
                    val id = text(member, "id", 64); require(ids.add(id)) { "成员身份不能重复" }
                    val previous = old?.optJSONArray("members").objects().singleOrNull { it.optString("id") == id }
                    val captured = previous?.let { JSONObject(it.toString()) } ?: JSONObject().put("id", id).put("runner", "model").put("device", "phone")
                    if (member.has("avatarShape")) captured.put("avatarShape", text(member, "avatarShape", 16))
                    captured.put("name", text(member, "name", 48).trim()).put("role", text(member, "role", 1200).trim())
                    val model = text(member, "model", 200, true).trim()
                    if (previous?.has("modelTarget") == true) require(model == previous.optString("model")) { "保留原模型目标，请在原模型设置中修改" }
                    captured.put("model", model)
                    val runner = if (member.has("runner")) text(member, "runner", 80) else previous?.optString("runner", "model") ?: "model"
                    val external = dev.pocketengine.features.remote.RemoteHarnessSelection.isTeamWorker(runner)
                    require(runner in setOf("model", "phone_agent") || external || runner == previous?.optString("runner")) { "请选择当前已接入的成员执行器" }
                    captured.put("runner", runner)
                    if (external) {
                        val device = member.optJSONObject("device") ?: previous?.optJSONObject("device") ?: error("请选择实际电脑连接和环境")
                        fields(device, "profileId", "environmentId")
                        val selected = JSONObject().put("profileId", text(device, "profileId", 80)).put("environmentId", text(device, "environmentId", 80))
                        captured.put("device", selected)
                        if (runner != "codex_app_server") {
                            require(member.opt("externalExecutionRiskAcknowledged") == true) { "请确认所选电脑 Harness 的实际权限、同用户执行与账号费用边界" }
                            captured.put("externalExecutionRiskAcknowledged", true)
                        } else captured.remove("externalExecutionRiskAcknowledged")
                        val selectedMode = if (member.has("modeId")) text(member, "modeId", 200, true).trim() else if (runner == previous?.optString("runner")) previous.optString("modeId") else ""
                        if (selectedMode.isNotEmpty()) captured.put("modeId", selectedMode) else captured.remove("modeId")
                        captured.remove("phoneScope"); captured.remove("modelTarget"); captured.remove("memberModelSelection")
                    } else if (runner == "phone_agent") {
                        require(mode == "collaborate") { "手机成员请使用共同工作方式" }
                        require(previous?.has("writeFiles") != true) { "原文件作者转为手机分工时，请移除此成员后新建手机成员" }
                        val device = member.getJSONObject("device"); fields(device, "kind", "screenMode", "targetDeviceId")
                        val local = device.optString("kind") == "local_phone"
                        require(local && device.optString("screenMode") == "background" && !device.has("targetDeviceId")
                            || device.optString("kind") == "paired_android" && !device.has("screenMode") && device.optString("targetDeviceId").isNotBlank()) { "请选择本机后台手机或已配对 Android" }
                        val knownTarget = snapshot.optJSONArray("phoneTargets").objects().any { candidate ->
                            candidate.optString("kind") == device.optString("kind") && (local || candidate.optString("targetDeviceId") == device.optString("targetDeviceId"))
                        }
                        require(knownTarget || previous?.optJSONObject("device")?.toString() == device.toString()) { "手机目标已变化，请刷新团队配置" }
                        captured.put("device", JSONObject(device.toString())); captured.remove("phoneScope")
                        captured.remove("modeId"); captured.remove("externalExecutionRiskAcknowledged")
                    } else if (previous?.optString("runner") != "model") {
                        captured.put("device", "phone"); captured.remove("phoneScope"); captured.remove("assignment"); captured.remove("modeId"); captured.remove("externalExecutionRiskAcknowledged"); captured.put("dependsOn", JSONArray())
                    }
                    // Every runner uses the same original assignment/dependency graph.
                    // An explicitly blank non-phone assignment follows the edited role;
                    // omitted fields from older clients still preserve their saved plan.
                    if (member.has("assignment") || runner == "phone_agent") {
                        val assignment = text(member, "assignment", 2000, runner != "phone_agent").trim()
                        if (assignment.isEmpty()) captured.remove("assignment") else captured.put("assignment", assignment)
                    }
                    if (member.has("dependsOn") || runner == "phone_agent") {
                        val dependencies = if (member.has("dependsOn")) member.optJSONArray("dependsOn") ?: error("前置成员必须是列表") else JSONArray()
                        require((0 until dependencies.length()).all { dependencies.opt(it) is String } && dependencies.length() <= 3) { "前置成员必须来自这次团队" }
                        captured.put("dependsOn", JSONArray(dependencies.toString()))
                    }
                    captured.remove("writeFiles"); captured.remove("remoteProduction")
                    if (mode == "produce") {
                        require(runner != "phone_agent") { "手机操作成员不能承担项目文件分工" }
                        val files = member.getJSONArray("writeFiles")
                        require((0 until files.length()).all { files.opt(it) is String }) { "负责文件必须为项目相对路径" }
                        captured.put("writeFiles", JSONArray(files.toString()))
                        if (external) captured.put("remoteProduction", JSONObject(member.getJSONObject("remoteProduction").toString()))
                        else require(!member.has("remoteProduction")) { "本机模型不能继承电脑制作范围" }
                    } else require(!member.has("writeFiles") && !member.has("remoteProduction")) { "只有项目文件协作可设置文件制作范围" }
                    members.put(AgentSlot.normalize(captured, index))
                }
                // Removing a saved member also removes its original dependency edges.
                // Unknown supplied identities still reach the original graph validator.
                val removedIds = old?.optJSONArray("members").objects().map { it.getString("id") }.toSet() - ids
                members.objects().forEach { member ->
                    val dependencies = member.getJSONArray("dependsOn")
                    member.put("dependsOn", JSONArray((0 until dependencies.length()).map { dependencies.getString(it) }.filterNot { it in removedIds }))
                }
                val leader = text(args, "leaderId", 64); require(leader in ids)
                TeamsModule.validateDefinitionMembers(members)
                if (mode == "produce") PocketWebTeamProduction.validateDefinition(members, snapshot,
                    snapshot.optJSONObject("productionSources") ?: error("请先读取原文件来源目录"), productionSources())
                val input = JSONObject().put("id", old?.getString("id") ?: UUID.randomUUID().toString()).put("name", text(args, "name", 80).trim())
                    .put("mode", mode).put("leaderId", leader).put("members", members).put("expectedDefinition", old?.toString() ?: "")
                // Only an existing debate retains its discussion protocol. Other
                // modes store "classic" as a placeholder, not a debate choice.
                val previousDiscussion = old?.takeIf { it.optString("mode") == "debate" }?.optString("discussionFormat")?.takeIf { it.isNotBlank() }
                input.put("discussionFormat", if (mode == "debate") previousDiscussion ?: TeamEvidenceDiscussion.FORMAT else "classic")
                if (input.optString("discussionFormat") == TeamEvidenceDiscussion.FORMAT) require(members.length() >= 2 && members.objects().all { it.optString("runner") == "model" && it.optJSONArray("dependsOn")?.length() == 0 }) { "证据讨论至少需要两名独立模型成员" }
                deliver(review("save", input), null)
            }
            "teams.reviewStart" -> {
                fields(args, "viewHandle", "goal", "rounds")
                val team = reading(text(args, "viewHandle"), "definition")
                val phoneMembers = team.optJSONArray("members").objects().filter { it.optString("runner") == "phone_agent" }
                val otherMembers = team.optJSONArray("members").objects().filter { it.optString("runner") != "phone_agent" }
                val otherTeam = JSONObject(team.toString()).put("members", JSONArray(otherMembers))
                check(!controller.modelSelectionPending() && !controller.state.chatSubmitting && !controller.state.phoneSubmitting
                    && (otherMembers.isEmpty() || teamLaunchable(otherTeam, controller.state))) { "请先核对当前主模型、实际执行器与原团队范围" }
                val rounds = args.opt("rounds"); require(rounds is Int && rounds in 1..2)
                val input = JSONObject().put("teamId", team.getString("id")).put("goal", text(args, "goal", 8000).trim())
                    .put("rounds", if (team.optString("discussionFormat") == TeamEvidenceDiscussion.FORMAT) 2 else rounds).put("expectedDefinition", team.toString())
                controller.state.conversationId?.let { input.put("conversationId", it) }
                if (phoneMembers.isEmpty()) deliver(review("start", input).put("definition", PocketWebState.publicValue(team)), null)
                else {
                    require(team.optString("mode") == "collaborate") { "手机成员请使用共同工作方式" }
                    phonePages.entries.removeAll { entry -> (entry.value.expires <= SystemClock.elapsedRealtime()).also { if (it) runCatching { entry.value.original.close() } } }
                    check(phonePages.size < 4) { "请先关闭已有手机范围选择" }
                    var created: TeamPhoneHost.Page? = null
                    phoneWork(work = {
                        val module = controller.webPluginFeatureHost().module("teams") as? TeamsModule ?: error("原团队模块不可用")
                        val page = module.newPhonePage(team.getString("id"), input.getString("goal"), input.getInt("rounds"), input.optString("conversationId"))
                        created = page; workingPhonePage = page
                        page.view().also { check(it.getJSONObject("definition").toString() == team.toString()) { "原团队配置已变化，请重新读取后开始" } }
                    }, accept = { value ->
                        val handle = UUID.randomUUID().toString()
                        phonePages[handle] = PhonePage(created ?: error("原手机范围尚未读取"), team.toString(), input.toString(), SystemClock.elapsedRealtime() + 120_000)
                        deliver(JSONObject().put("operation", "phone_scope").put("phoneHandle", handle).put("view", PocketWebState.publicValue(value)), null)
                    })
                }
            }
            "teams.reviewPhoneStart" -> {
                fields(args, "phoneHandle", "members")
                val page = phonePages.remove(text(args, "phoneHandle")) ?: error("原手机范围选择已关闭，请重新开始核对")
                if (page.expires <= SystemClock.elapsedRealtime()) { page.original.close(); error("原手机范围选择已过期") }
                val choices = JSONObject().put("members", args.getJSONArray("members"))
                var captured: TeamPhoneHost.Review? = null
                phoneWork(page.original, work = { page.original.review(choices).also { captured = it }.view() }, accept = { value ->
                    val result = review("start", JSONObject(page.input)); val handle = result.getString("reviewHandle")
                    reviews[handle] = reviews.getValue(handle).copy(phonePage = page.original, phoneReview = captured ?: error("原手机范围尚未审阅"))
                    deliver(result.put("definition", PocketWebState.publicValue(JSONObject(page.definition))).put("phoneScope", PocketWebState.publicValue(value)), null)
                })
            }
            "teams.reviewRemove" -> { fields(args, "viewHandle"); val team = reading(text(args, "viewHandle"), "definition"); deliver(review("remove", JSONObject().put("id", team.getString("id")).put("expectedDefinition", team.toString())).put("definition", PocketWebState.publicValue(team)), null) }
            "teams.reviewMember" -> {
                fields(args, "viewHandle", "action", "memberId", "name", "avatarShape", "role", "assignment", "model", "newLeaderId")
                val run = reading(text(args, "viewHandle"), "run")
                check(run.optString("status") != "cancelled") { "这次协作已停止，历史成员保留" }
                val action = text(args, "action", 16); require(action in setOf("add", "rename", "exit"))
                val roster = run.optJSONArray("members").objects()
                val input = JSONObject().put("runId", run.getString("id")).put("action", action)
                    .put("expectedRevision", run.getLong("revision")).put("expectedGeneration", run.getInt("generation"))
                if (action != "rename") {
                    check(run.optString("mode") == "collaborate" && !run.has("workPlan") && (run.optString("researchExecutionFormat").isEmpty() || run.optString("researchExecutionFormat") == dev.pocketengine.features.teams.TeamCoordinator.RESEARCH_FORMAT)
                        && !run.has("nativeWorkProfileModels") && roster.all { it.optString("runner", "model") == "model" && it.optString("device", "phone") == "phone" }) { "此团队的成员与原任务范围绑定，请回原任务处理" }
                    check(run.optString("status") == "paused" || run.optString("phase") == "awaiting_member") { "请先暂停全队，再刷新并核对成员" }
                }
                val member = if (action == "add") {
                    fields(args, "viewHandle", "action", "memberId", "name", "avatarShape", "role", "assignment", "model")
                    val id = text(args, "memberId", 64)
                    check(roster.none { it.optString("id") == id } && roster.count { !it.optBoolean("exited") } < 4 && roster.size < 16) { "当前成员已满或身份已存在；请刷新核对，不重用旧身份" }
                    AgentSlot.normalize(JSONObject().put("id", id).put("name", text(args, "name", 48).trim())
                        .put("avatarShape", text(args, "avatarShape", 16)).put("role", text(args, "role", 1200).trim())
                        .put("assignment", text(args, "assignment", 2000).trim()).put("model", text(args, "model", 200, true).trim())
                        .put("runner", "model").put("device", "phone"), roster.size).also { input.put("member", it) }
                } else {
                    val selected = roster.singleOrNull { it.optString("id") == text(args, "memberId", 64) && !it.optBoolean("exited") } ?: error("原成员已退出或不存在")
                    input.put("memberId", selected.getString("id")).put("expectedConfigRevision", selected.optInt("configRevision", 1)).put("expectedMemberGeneration", selected.getInt("generation"))
                    if (action == "rename") {
                        fields(args, "viewHandle", "action", "memberId", "name", "avatarShape")
                        val shape = text(args, "avatarShape", 16); require(shape in setOf("blob", "pebble", "squircle", "egg"))
                        input.put("name", text(args, "name", 48).trim()).put("avatarShape", shape)
                    } else {
                        fields(args, "viewHandle", "action", "memberId", "newLeaderId")
                        val others = roster.filter { !it.optBoolean("exited") && it.optString("id") != selected.getString("id") }
                        val next = if (args.has("newLeaderId")) text(args, "newLeaderId", 64, true) else ""
                        if (run.optString("leaderId") == selected.getString("id")) check(if (others.isEmpty()) next.isEmpty() else others.any { it.optString("id") == next }) { "请明确选择仍在队内的下一位负责人" }
                        else require(next.isEmpty()) { "普通成员退出不更换负责人" }
                        input.put("newLeaderId", next)
                    }
                    selected
                }
                val result = review("configureMember", input).put("member", PocketWebState.publicValue(member))
                    .put("leaderId", run.optString("leaderId")).put("roster", PocketWebState.publicValue(JSONArray(roster)))
                    .put("researchExit", action == "exit" && run.optString("researchExecutionFormat") == dev.pocketengine.features.teams.TeamCoordinator.RESEARCH_FORMAT)
                deliver(result, null)
            }
            "teams.confirm" -> {
                fields(args, "reviewHandle")
                val captured = reviews.remove(text(args, "reviewHandle")) ?: error("原操作审阅已使用或关闭，请先核对原回执")
                if (captured.expires <= SystemClock.elapsedRealtime()) { captured.phonePage?.close(); error("原操作审阅已过期") }
                if (captured.phonePage == null) {
                    val input = JSONObject(captured.input)
                    if (captured.command == "save" && input.optString("mode") == "produce") PocketWebTeamProduction.validateDefinition(input.getJSONArray("members"), snapshot,
                        JSONObject(captured.productionSources ?: error("原文件来源审阅缺失")), productionSources())
                    feature(captured.command, input)
                }
                else phoneWork(captured.phonePage, work = { captured.phonePage.confirm(captured.phoneReview ?: error("原手机范围审阅缺失")) }, accept = { result ->
                    captured.phonePage.close(); check(result.optString("id").isNotBlank()) { "团队启动尚未取得原运行身份" }
                    snapshot.put("runs", (snapshot.optJSONArray("runs") ?: JSONArray()).put(JSONObject(result.toString())))
                    deliver(JSONObject().put("command", "start").put("confirmed", true).put("result", PocketWebState.publicValue(result)).put("reading", view("run", result)), null)
                })
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
