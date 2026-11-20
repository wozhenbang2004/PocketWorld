package dev.pocketengine.ui.web

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.teams.TaskStoreTeamWriteHost
import dev.pocketengine.features.teams.TeamParentRevisionOffer
import dev.pocketengine.features.teams.TeamOwnerFileReading
import dev.pocketengine.features.teams.AgentSlot
import dev.pocketengine.features.teams.TeamPhoneHost
import dev.pocketengine.features.teams.TeamWorkItems
import dev.pocketengine.features.teams.TeamEvidenceDiscussion
import dev.pocketengine.features.teams.TeamAttention
import dev.pocketengine.features.teams.TeamDraftDefinition
import dev.pocketengine.features.teams.TeamCoordinator
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
    private class FileWindow(val owner: Reading, val handle: String = UUID.randomUUID().toString()) : AutoCloseable {
        val reader = RunControl()
        @Volatile var captured: TeamOwnerFileReading? = null
            private set
        private var findingSource: String? = null
        @Synchronized fun install(value: TeamOwnerFileReading, decision: JSONObject) {
            reader.check(); check(owner.live); captured = value
            findingSource = if(decision.opt("state")=="independent_finding") decision.getJSONObject("originalReviewer").toString() else null
        }
        @Synchronized fun originalFinding(): JSONObject {
            reader.check(); check(owner.live && captured!=null); return JSONObject(checkNotNull(findingSource) { "没有与这份文件匹配的独审原件" })
        }
        private var approval: TaskStoreTeamWriteHost.TextOutputApproval? = null
        private var token: String? = null
        @Synchronized fun offer(exact: TaskStoreTeamWriteHost.TextOutputApproval): String {
            reader.check(); check(owner.live && captured != null); approval?.close(); approval = exact
            return UUID.randomUUID().toString().also { token = it }
        }
        @Synchronized fun take(value: String, project: Boolean): TaskStoreTeamWriteHost.TextOutputApproval {
            reader.check(); check(owner.live && token == value && PocketWebFirstProjectOutput.matches(approval,project)) { "这份许可审阅已使用或关闭，请核对原批准记录" }
            token = null; return requireNotNull(approval)
        }
        @Synchronized fun discard(value: String) { if (token == value) { approval?.close(); approval = null; token = null } }
        @Synchronized override fun close() { reader.cancel(); captured = null; findingSource = null; approval?.close(); approval = null; token = null }
    }
    private var fileWindow: FileWindow? = null
    private class Reading(val kind: String, val original: String, val projectRun: Boolean) {
        @Volatile var live = true
            private set
        var deliveryWorkers: String? = null
        var finalPackage: dev.pocketengine.store.TaskStore.TeamProjectPackageSelection? = null
        var finalPackageKey: JSONObject? = null
        val packageRead = RunControl()
        private var itemPage: AutoCloseable? = null
        @Synchronized fun hold(page: AutoCloseable) {
            check(live) { "原分工窗口已关闭" }; itemPage?.close(); itemPage = page
        }
        @Synchronized fun retire() { live = false; packageRead.cancel(); finalPackage=null; finalPackageKey=null; itemPage?.close(); itemPage = null }
    }
    private data class Review(val command: String, val input: String, val expires: Long, val phonePage: TeamPhoneHost.Page? = null, val phoneReview: TeamPhoneHost.Review? = null, val productionSources: String? = null,
        val workItem: PocketWebTeamWorkReturn? = null, val failedHandoff:PocketWebTeamFailedHandoff?=null, val ownerReading: Reading? = null, val proposal: TaskStoreTeamWriteHost.WorkPlanPage.ParentProposalReview? = null) {
        fun close() { runCatching { phonePage?.close() }; runCatching { workItem?.close() }; runCatching { failedHandoff?.close() }; runCatching { proposal?.close() } }
    }
    private data class PhonePage(val original: TeamPhoneHost.Page, val definition: String, val input: String, val expires: Long)
    @Volatile private var generation = 0L
    private var snapshot = JSONObject()
    private val readings = linkedMapOf<String, Reading>()
    private val reviews = linkedMapOf<String, Review>()
    private val phonePages = linkedMapOf<String, PhonePage>()
    private val main = Handler(Looper.getMainLooper())
    private val phoneInFlight = AtomicBoolean(false)
    @Volatile private var workingTeamPage: AutoCloseable? = null

    companion object {
        val COMMANDS = setOf("teams.openWorkFileDecision", "teams.attention", "teams.readAttention", "teams.snapshot", "teams.readRun", "teams.readDefinition", "teams.reviewDefinition", "teams.prepareDraftStart",
            "teams.reviewStart", "teams.reviewRemove", "teams.confirm", "teams.control", "teams.sendMessage",
            "teams.readFrozenOutputs", "teams.readFrozenDiff", "teams.reviewFrozenTextApproval", "teams.approveFrozenText", PocketWebFirstProjectOutput.REVIEW, PocketWebFirstProjectOutput.CONFIRM, "teams.reviewParentRevision", "teams.reviewWorkItemContinuation", "teams.reviewSoleFailedHandoff", "teams.answerAssignment", "teams.probeMember", "teams.closeReading", "teams.reviewPhoneStart", "teams.reviewMember", "teams.openResearchTask", "teams.openDeliveryWorker", "teams.readFinalPackageFiles", "teams.chooseFinalPackageTarget", "teams.saveFinalPackageTarget", "teams.discardFinalPackageTarget")
    }

    fun clear() {
        generation++; snapshot = JSONObject(); readings.values.forEach { it.retire() }; readings.clear(); fileWindow?.close(); fileWindow = null
        phonePages.values.forEach { runCatching { it.original.close() } }; phonePages.clear()
        reviews.values.forEach { it.close() }; reviews.clear()
        runCatching { workingTeamPage?.close() }
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
        val original = if (consume) readings.remove(handle)?.also { it.retire() } else readings[handle]
        check(original != null && original.kind == kind) { "原团队读取已关闭，请重新读取" }
        return JSONObject(original.original)
    }
    /** Exact original record navigation, including children outside the recent task list.
     * This is not a continuation, member read grant or authorization to reuse old output. */
    data class ResearchNavigation(val id: String, val output: ResearchOutput? = null)
    data class ResearchOutput(val taskId: String, val childRun: String, val epoch: Int, val index: Int, val chars: Int, val fingerprint: String) {
        fun matches(ref: JSONObject?): Boolean = ref != null && ref.optString("format") == "pocketworld.team-research-original.v1" &&
            ref.opt("taskId") == taskId && ref.opt("runId") == childRun && ref.optInt("coordinatorEpoch", -1) == epoch &&
            ref.optInt("messageIndex", -1) == index && ref.optInt("chars", -1) == chars && ref.opt("sha256") == fingerprint
    }
    fun finalPackageExportSnapshot(): JSONObject? = readings.values.lastOrNull()?.takeIf { it.live && it.kind == "run" }?.finalPackageKey?.let(controller.projectPackages::teamExportSnapshot)
    fun retainsRunReading(handle: String): Boolean = readings[handle]?.kind == "run"
    fun retainsDeliverySource(view: String, file: String): Boolean {
        val owner=readings[view]?.takeIf { it.kind=="run" && it.live } ?: return false
        return file.isEmpty() || fileWindow?.let { it.owner===owner && it.handle==file && !it.reader.isCancelled }==true
    }
    fun taskForDeliveryNavigation(args: JSONObject): JSONObject {
        check(controller.webPrivateOwnerVisible()) { "请回到私人团队原记录" }
        if(args.has("fileHandle")){
            fields(args,"viewHandle","fileHandle")
            val view=text(args,"viewHandle");val file=text(args,"fileHandle")
            check(file.isNotBlank() && retainsDeliverySource(view,file)) { "原文件阅读已关闭" }
            return checkNotNull(fileWindow).originalFinding()
        }
        fields(args,"viewHandle","taskId","runId","epoch")
        val captured = readings[text(args,"viewHandle")]?.takeIf { it.kind == "run" && it.live } ?: error("原团队阅读已关闭")
        val directory = captured.deliveryWorkers?.let { JSONObject(it) } ?: error("请先读取原交付进展")
        val task = text(args,"taskId"); val run = text(args,"runId"); val epoch = args.opt("epoch")
        require(epoch is Int && epoch > 0)
        return directory.getJSONArray("workers").objects().singleOrNull { it.optBoolean("canOpen") && it.optString("taskId") == task &&
            it.optString("runId") == run && it.optInt("epoch") == epoch && directory.getString("teamRunId")==it.optString("teamRunId") }
            ?: error("这不是打开时所选的独审或整合执行，没有打开其他任务")
    }
    fun taskForResearchNavigation(args: JSONObject): ResearchNavigation {
        check(controller.webPrivateOwnerVisible()) { "请回到前台私人团队页面" }
        fields(args, "viewHandle", "memberId", "round", "taskId", "outputFingerprint")
        val original = reading(text(args, "viewHandle"), "run")
        check(original.optString("researchExecutionFormat").isNotBlank()) { "这份记录不是原研究协作" }
        val memberId = text(args, "memberId")
        val member = original.optJSONArray("members").objects().singleOrNull { it.optString("id") == memberId }
            ?: error("原研究成员不在这份读取窗口")
        val round = args.opt("round")
        require(round is Number && round.toDouble() == round.toInt().toDouble() && round.toInt() in 1..6) { "请核对原研究阶段" }
        val phase = member.optJSONArray("roundStates").objects().singleOrNull { it.optInt("round") == round.toInt() }
            ?: error("原研究阶段不在这份读取窗口")
        val id = text(args, "taskId", 36)
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "原研究任务身份无效" }
        val result = if (phase.optString("researchTaskId") == id) phase.optJSONObject("researchTask") else {
            val prior = phase.optJSONArray("researchHistory").objects().singleOrNull { it.optString("taskId") == id }
                ?: error("这份原研究任务不属于所选成员与阶段，请重新读取")
            prior.optJSONObject("result")
        }
        if (result != null) {
            check((!result.has("taskId") || result.optString("taskId") == id)
                && (!result.has("teamRunId") || result.optString("teamRunId") == original.getString("id"))
                && (!result.has("memberId") || result.optString("memberId") == memberId)) { "原研究回执身份不一致，请重新读取" }
        }
        // Absence of a progress receipt does not invent a task or a terminal state.
        // The original owner getTask path must still find and authorize the record.
        if (!args.has("outputFingerprint")) return ResearchNavigation(id)
        val hash = text(args, "outputFingerprint", 64)
        val saved = requireNotNull(result) { "这份原阶段没有已保存的研究产出" }
        require(phase.optString("status") == "completed" && phase.optString("researchTaskId") == id &&
            saved.optString("status") == "completed" && saved.opt("executionAuthorized") == false &&
            saved.opt("teamRunId") == original.getString("id") && saved.opt("memberId") == memberId && saved.opt("taskId") == id &&
            (!saved.has("round") || saved.optInt("round", -1) == round.toInt()) && hash.matches(Regex("[0-9a-f]{64}"))) { "原研究成员或阶段已变化" }
        val ref = saved.getJSONObject("outputReference")
        val output = ResearchOutput(id, saved.getString("runId"), saved.getInt("coordinatorEpoch"), ref.getInt("messageIndex"), ref.getInt("chars"), hash)
        require(output.childRun.isNotBlank() && output.epoch > 0 && output.index >= 0 && output.chars > 0 && output.matches(ref)) { "这份原产出已变化" }
        return ResearchNavigation(id, output)
    }
    // This reading can show a request to continue, never grant it. The original coordinator
    // and SQL reservation still decide whether the exact saved answer can be used now.
    private fun canAskToContinueSaved(run: JSONObject, question: JSONObject): Boolean {
        if (question.optString("kind") != "assignment_question" || question.optString("state") != "answered"
            || question.optString("answerActor") !in setOf("", "owner") || question.optString("answer").isBlank()
            || question.optString("answerId").isBlank() || question.has("continuationClaim")
            || run.optString("status") in setOf("completed", "cancelled", "stopping", "paused", "interrupted")) return false
        val member = run.optJSONArray("members").objects().singleOrNull { it.optString("id") == question.optString("memberId") } ?: return false
        val waiting = setOf("awaiting_input", "awaiting_user")
        if (member.optBoolean("exited") || member.optBoolean("paused") || member.optString("status") !in waiting) return false
        val assignment = if (run.optJSONObject("workPlan") == null) member.optJSONObject("assignmentTask") else {
            run.getJSONObject("workPlan").optJSONArray("items").objects().singleOrNull {
                it.optString("memberId") == member.optString("id") && it.optString("workItemId") == question.optString("workItemId")
                    && it.optString("assignmentId") == question.optString("assignmentId") && it.optString("status") in waiting
            }?.optJSONObject("assignmentTask")
        } ?: return false
        return assignment.optString("assignmentId") == question.optString("assignmentId") &&
            assignment.optJSONArray("questions").objects().none { it.optString("state") == "pending" }
    }
    private fun view(kind: String, value: JSONObject): JSONObject {
        while (readings.size >= 24) readings.remove(readings.keys.first())?.retire()
        val handle = UUID.randomUUID().toString()
        // Reading/editing is bound to the visible native window, not a timer.
        // clear()/closeReading retire it; mutations still compare the original
        // definition or run/member generations before accepting any change.
        readings[handle] = Reading(kind, value.toString(), kind == "run" && value.optJSONObject("productionScope")?.optJSONObject("projectSelection") != null)
        val result = JSONObject().put("kind", kind).put("viewHandle", handle).put(kind, PocketWebState.publicValue(value))
        if (kind == "run") {
            val messages = teamConversationRows(value).onEach { row ->
                if (canAskToContinueSaved(value, row)) row.put("canRequestSavedContinuation", true) else row.remove("canRequestSavedContinuation")
            }
            result.put("messages", PocketWebState.publicValue(JSONArray(messages)))
            result.put("attention", PocketWebState.publicValue(TeamAttention.forRun(value)))
            result.put("revisionOffers", PocketWebState.publicValue(TeamParentRevisionOffer.available(value)))
            controller.state.tasks.singleOrNull { it.raw.optString("feature") == "teams" && it.raw.optString("externalId") == value.optString("id")
                && it.raw.optString("actorId") == "owner" && it.raw.optString("spaceId") == "private" }
                ?.let { result.put("taskId", it.id) }
            val checkSavedResult = value.optString("status") == "completed" && value.optString("phase") == "completed" &&
                value.optString("mode") in setOf("collaborate", "debate") && !value.has("workPlan") && !value.has("productionScope") &&
                value.optString("summaryProducer") == "host_model" && !value.optBoolean("summaryStale")
            val resultGeneration = if (checkSavedResult) value.optInt("generation", -1) else -1
            val resultRevision = if (checkSavedResult) value.optLong("revision", -1L) else -1L
            controller.nativeTokenBudgetHost().tasks.ownerWorkLinkForTeam(value.getString("id"), resultGeneration, resultRevision)?.let { link ->
                result.put("ownerWorkLink", PocketWebState.publicValue(link))
                // The original host may have left the recent task window.
                result.put("taskId", link.getString("teamTaskId"))
            }
        }
        return result
    }
    private fun attentionFocus(value: JSONObject, locator: JSONObject): JSONObject {
        val member = value.optJSONArray("members").objects().singleOrNull { it.optString("id") == locator.getString("memberId") }
        val sameGeneration = value.optInt("generation") == locator.getInt("generation") && member?.optInt("generation") == locator.getInt("memberGeneration")
        if (!sameGeneration) return JSONObject().put("kind", "changed").put("message", "原成员或运行已变化，已打开原协作记录；请核对当前进展。")
        val identityFields = listOf("memberId", "generation", "memberGeneration", "identityRevision", "workItemId", "assignmentId", "questionId", "childKind", "round", "taskId")
        val candidate = TeamAttention.forRun(value).objects().singleOrNull { row ->
            row.has("questionId") == locator.has("questionId") && row.has("workItemId") == locator.has("workItemId")
                && identityFields.all { key -> !locator.has(key) || row.has(key) && row.opt(key) == locator.opt(key) }
        }
        if (candidate == null) return JSONObject().put("kind", "changed").put("message", "这项原待处理已变化或已解决，已打开原协作记录；请查看当前进展。")
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
        return JSONObject().put("kind", if (item == null) "member" else "workItem").put("memberId", member!!.getString("id")).also { focus ->
            if (item != null) focus.put("workItemId", item.getString("workItemId"))
            listOf("childKind", "round", "taskId").forEach { key -> if (candidate.has(key)) focus.put(key, candidate.get(key)) }
        }
    }

    private fun review(command: String, input: JSONObject): JSONObject {
        reviews.entries.removeAll { entry -> (entry.value.expires <= SystemClock.elapsedRealtime()).also { if (it) entry.value.close() } }
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
        fun hostWork(page: AutoCloseable? = null, work: () -> JSONObject, accept: (JSONObject) -> Unit) {
            if (!phoneInFlight.compareAndSet(false, true)) { runCatching { page?.close() }; error("正在核对这次团队操作，请等待原回执") }
            workingTeamPage = page
            try {
                controller.runTeamPhoneWork {
                    val result = runCatching { check(currentWindow()) { "原团队页面已关闭" }; work() }
                    main.post {
                        phoneInFlight.set(false)
                        if (!currentWindow()) {
                            runCatching { workingTeamPage?.close() }; workingTeamPage = null
                            // Complete the original bridge command even when its presentation was retired.
                            // The bridge owns outcome-unknown delivery; never populate the next page's handles.
                            done(null, "原团队窗口已关闭；请查询原团队结果，没有自动重放")
                        }
                        else {
                            val completedPage = workingTeamPage ?: page
                            workingTeamPage = null
                            result.fold({ value -> try { accept(value) } catch (failure: Exception) { runCatching { completedPage?.close() }; deliver(null, failure.message ?: "原团队操作回执待核对") } },
                                { failure -> runCatching { completedPage?.close() }; deliver(null, failure.message ?: "原团队操作暂不可用") })
                        }
                    }
                }
            } catch (failure: Exception) { phoneInFlight.set(false); workingTeamPage = null; runCatching { page?.close() }; throw failure }
        }
        fun finishReceipt(receipt: JSONObject) {
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
        // Capture downstream SQL and live-control evidence on the worker thread. Do not
        // inject display-only fields into the coordinator record used by mutation reviews.
        fun deliverReceipt(receipt: JSONObject) {
            val packet=receipt.optJSONObject("reading"); val source=packet?.optJSONObject("run")
            if(packet==null || source==null || source.optJSONObject("productionScope")==null){ finishReceipt(receipt); return }
            val handle=packet.getString("viewHandle"); val owner=readings[handle] ?: return done(null,"原团队窗口已关闭")
            val team=source.getString("id")
            controller.runTeamPhoneWork {
                val observed=runCatching {
                    check(currentWindow() && owner.live)
                    val module=controller.webPluginFeatureHost().module("teams") as? TeamsModule ?: error("原团队宿主不可用")
                    module.ownerFinalWorkers(team)
                }.getOrElse { dev.pocketengine.features.teams.TeamDeliveryWorkers.unavailable(team) }
                val pack=if(!owner.projectRun) null else runCatching {
                    check(currentWindow() && owner.live)
                    val pin=controller.webTeamFinalPackage(team)
                    pin to TaskProjectPackageFiles.teamDescriptor(pin)
                }.getOrNull()
                main.post {
                    if(currentWindow() && owner.live && readings[handle]===owner && observed.optString("teamRunId")==team){
                        if(pack!=null){
                            owner.finalPackage=pack.first; owner.finalPackageKey=pack.second; packet.put("finalProjectPackage",pack.second)
                            controller.projectPackages.teamExportSnapshot(pack.second)?.let { packet.put("finalPackageExport",it) }
                        }
                        owner.deliveryWorkers=observed.toString(); packet.put("deliveryWorkers",observed); finishReceipt(receipt)
                    } else done(null,"原团队窗口已关闭；没有打开其他执行或重发控制")
                }
            }
        }
        fun readOwnerRun(id: String, proposalOrigin: JSONObject? = null, accept: (JSONObject?, String?) -> Unit) {
            controller.runTeamPhoneWork {
                val result = runCatching {
                    check(currentWindow()) { "原团队阅读窗口已关闭" }
                    val teams = controller.webPluginFeatureHost().module("teams") as? TeamsModule ?: error("原团队宿主尚未接入")
                    teams.retainedOwnerReading(id).also {
                        check(it.optString("id") == id) { "原团队运行身份不匹配" }
                        if (proposalOrigin != null) {
                            controller.requireWorkOrigin(proposalOrigin,id)
                            teams.requireOwnerRevisionTarget(it,proposalOrigin,proposalOrigin.getJSONObject("proposal"))
                            controller.requireWorkOrigin(proposalOrigin,id)
                        }
                    }
                }
                main.post {
                    if (!currentWindow()) done(null, "原团队阅读窗口已关闭，请回原记录核对")
                    else result.fold({ accept(it, null) }, { accept(null, it.message ?: "原团队暂不可读") })
                }
            }
        }
        fun feature(command: String, input: JSONObject, messageId: String? = null, attention: JSONObject? = null, proposalOrigin: JSONObject? = null) {
            val received: (JSONObject?, String?) -> Unit = receive@{ result, error ->
                if (!currentWindow()) { done(null, "原团队窗口已关闭；请回原运行核对结果，没有自动重发"); return@receive }
                if (error != null || result == null) deliver(null, error ?: "原团队操作没有返回回执")
                else if (originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()) try {
                    if (command == "sendMessage") check(result.optJSONObject("messageReceipt")?.optString("messageId") == messageId
                        && result.optJSONObject("messageReceipt")?.optString("recipientMemberId") == input.getString("recipientMemberId")) { "原消息回执身份不匹配" }
                    if (command == "answerAssignment") check(result.optString("answerId") == messageId
                        && result.optString("questionId") == input.getString("questionId")
                        && result.optString("assignmentId") == input.getString("assignmentId")) { "原问题回执身份不匹配" }
                    val receipt = JSONObject().put("command", command).put("confirmed", true).put("result", PocketWebState.publicValue(result))
                    messageId?.let { receipt.put("messageId", it) }
                    if (command == "answerAssignment") receipt.put("runId", input.getString("runId")).put("memberId", input.getString("memberId"))
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
                        readOwnerRun(input.getString("runId")) { current, readError ->
                            if (originalGeneration == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()) {
                                if (readError == null && current != null && current.optString("id") == input.getString("runId")) receipt.put("reading", view("run", current))
                                else receipt.put("readRequired", true)
                                deliverReceipt(receipt)
                            } else done(null, "原回答窗口已关闭；回答结果保留，请回原问题核对，没有自动重发")
                        }
                        return@receive
                    }
                    deliverReceipt(receipt)
                } catch (failure: Exception) { deliver(null, failure.message ?: "原团队回执待核对") }
            }
            if (command == "get") readOwnerRun(input.getString("runId"), proposalOrigin, received)
            else controller.requestWebFeatureReceipt("teams", command, input, ::currentWindow, received)
        }
        when (action) {
            "teams.chooseFinalPackageTarget", "teams.saveFinalPackageTarget", "teams.discardFinalPackageTarget" -> {
                val begin = action == "teams.chooseFinalPackageTarget"
                if (begin) fields(args, "viewHandle", "operationId", "artifactId", "artifactSha256")
                else fields(args, "viewHandle", "operationId", "artifactId", "artifactSha256", "requestId")
                val page = readings[text(args, "viewHandle")]?.takeIf { it.live && it.kind == "run" } ?: error("这个原团队成果窗口已关闭")
                val selected = page.finalPackage ?: error("本次原团队没有已签收的完整包")
                val descriptor = page.finalPackageKey ?: error("请先重新打开原团队成果")
                check(descriptor.getString("operationId") == text(args, "operationId") && descriptor.getString("artifactId") == text(args, "artifactId") &&
                    descriptor.getString("archiveSha256") == text(args, "artifactSha256", 64)) { "这不是选中的原整包，未改变保存目标" }
                val present = { currentWindow() && page.live && page.finalPackage === selected }
                if (begin) controller.projectPackages.offerTeamExport(selected, present, done)
                else deliver(controller.projectPackages.finishTeamExportChoice(selected, text(args, "requestId"), action == "teams.saveFinalPackageTarget", present), null)
            }
            "teams.readFinalPackageFiles" -> {
                val handle=text(args,"viewHandle");val page=readings[handle]?.takeIf { it.live && it.kind=="run" } ?: error("原团队成果窗口已关闭")
                val selected=page.finalPackage ?: error("这个原团队未提供已签收的完整包")
                val query=JSONObject(args.toString()).also { it.remove("viewHandle") }
                controller.runTeamPhoneWork {
                    val reply=runCatching {check(currentWindow()&&page.live);controller.webReadTeamFinalPackage(selected,query,page.packageRead)}
                    main.post {
                        if(!currentWindow()||!page.live||readings[handle]!==page||page.finalPackage!==selected)done(null,"原团队窗口已变化，未返回文件正文")
                        else reply.fold({done(it,null)},{done(null,it.message?:"这份原团队固定包暂不可读取")})
                    }
                }
            }
            "teams.attention" -> {
                if (args.has("parentTaskId")) {
                    fields(args, "parentTaskId", "parentRunId", "parentEpoch", "conversationId", "teamRunId", "fileReview", "proposalDirectory", "proposal")
                    val parent = text(args, "parentTaskId"); val parentRun = text(args, "parentRunId"); val conversation = text(args, "conversationId")
                    val teamRun = text(args, "teamRunId"); val epoch = args.opt("parentEpoch")
                    require(epoch is Int && epoch > 0) { "原工作代次无效" }
                    val mark = if (args.has("fileReview")) args.getJSONObject("fileReview") else null
                    val directory = args.has("proposalDirectory")
                    require(!directory || args.opt("proposalDirectory") == true)
                    val proposal = if (args.has("proposal")) args.getJSONObject("proposal").also { fields(it,"operationId","workItemId","assignmentId","planHash") } else null
                    require(listOf(mark != null,directory,proposal != null).count { it } <= 1) { "请选择一类原工作记录" }
                    controller.readWebWorkDecisions(parent, parentRun, epoch, conversation, teamRun, mark, directory, proposal) { value, error ->
                        if (currentWindow()) deliver(value?.let { PocketWebState.publicValue(it) as JSONObject }, error)
                        else done(null, "原对话已变化，请重新打开这件工作")
                    }
                    return
                }
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
                fields(args, "runId", "memberId", "generation", "memberGeneration", "identityRevision", "workItemId", "assignmentId", "questionId", "childKind", "round", "taskId")
                val id = text(args, "runId"); text(args, "memberId")
                require(args.opt("generation") is Int && args.opt("memberGeneration") is Int) { "原团队定位缺少运行与成员代次" }
                if (args.has("workItemId")) text(args, "workItemId")
                if (args.has("assignmentId")) text(args, "assignmentId")
                if (args.has("questionId")) { text(args, "questionId"); text(args, "assignmentId") }
                if (args.has("identityRevision")) require(args.opt("identityRevision") is Int && args.getInt("identityRevision") > 0) { "原成员身份版本无效" }
                if (args.has("taskId")) {
                    val task = text(args, "taskId", 36)
                    require(runCatching { UUID.fromString(task).toString() == task }.getOrDefault(false)) { "原子任务身份无效" }
                }
                if (args.has("childKind")) {
                    require(text(args, "childKind", 16) in setOf("research", "phone")) { "原子任务类型无效" }
                    require(args.opt("round") is Int && args.getInt("round") in 1..6) { "原子任务阶段无效" }
                    require(args.has("taskId")) { "原子任务定位缺少身份" }
                } else require(!args.has("round")) { "原子任务定位缺少类型" }
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
            "teams.readRun" -> {
                fields(args,"runId","proposalOrigin"); val id = text(args,"runId"); known(id,"runs")
                val origin = if (args.has("proposalOrigin")) args.getJSONObject("proposalOrigin").also {
                    fields(it,"parentTaskId","parentRunId","parentEpoch","conversationId","teamRunId","proposal")
                    fields(it.getJSONObject("proposal"),"operationId","workItemId","assignmentId","planHash")
                } else null
                feature("get",JSONObject().put("runId",id),proposalOrigin=origin)
            }
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
                fields(args, "viewHandle", "reviewHandle", "phoneHandle", "fileHandle", "approvalHandle"); readings.remove(args.optString("viewHandle"))?.retire()
                if (args.has("approvalHandle")) fileWindow?.discard(text(args,"approvalHandle"))
                fileWindow?.takeIf { !it.owner.live || it.handle == args.optString("fileHandle") }?.let { it.close(); fileWindow = null }
                reviews.remove(args.optString("reviewHandle"))?.close()
                phonePages.remove(args.optString("phoneHandle"))?.original?.let { runCatching { it.close() } }
                deliver(JSONObject().put("closed", true), null)
            }
            "teams.reviewDefinition", "teams.prepareDraftStart" -> {
                val draftStart = action == "teams.prepareDraftStart"
                if (draftStart) fields(args, "name", "mode", "leaderId", "members", "goal", "rounds")
                else fields(args, "viewHandle", "name", "mode", "leaderId", "members")
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
                if (!draftStart) deliver(review("save", input), null)
                else {
                    val definition = TeamDraftDefinition.capture(JSONObject(input.toString()).also { it.remove("expectedDefinition") })
                    check(!controller.modelSelectionPending() && !controller.state.chatSubmitting && !controller.state.phoneSubmitting
                        && teamLaunchable(definition, controller.state)) { "请先准备本次协作所需的模型连接" }
                    val rounds = args.opt("rounds"); require(rounds is Int && rounds in 1..2) { "请选择一至两轮协作" }
                    val start = JSONObject().put("teamId", definition.getString("id")).put("draftDefinition", definition)
                        .put("goal", text(args, "goal", 8000).trim()).put("rounds", if (mode == "debate") 2 else rounds)
                    controller.state.conversationId?.let { start.put("conversationId", it) }
                    deliver(review("start", start).put("definition", PocketWebState.publicValue(definition)).put("draft", true)
                        .put("budget", TeamCoordinator.initialBudget(members.length(), start.getInt("rounds"))), null)
                }
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
                if (phoneMembers.isEmpty()) deliver(review("start", input).put("definition", PocketWebState.publicValue(team))
                    .put("budget", TeamCoordinator.initialBudget(team.getJSONArray("members").length(), input.getInt("rounds"))), null)
                else {
                    require(team.optString("mode") == "collaborate") { "手机成员请使用共同工作方式" }
                    phonePages.entries.removeAll { entry -> (entry.value.expires <= SystemClock.elapsedRealtime()).also { if (it) runCatching { entry.value.original.close() } } }
                    check(phonePages.size < 4) { "请先关闭已有手机范围选择" }
                    var created: TeamPhoneHost.Page? = null
                    hostWork(work = {
                        val module = controller.webPluginFeatureHost().module("teams") as? TeamsModule ?: error("原团队模块不可用")
                        val page = module.newPhonePage(team.getString("id"), input.getString("goal"), input.getInt("rounds"), input.optString("conversationId"))
                        created = page; workingTeamPage = page
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
                hostWork(page.original, work = { page.original.review(choices).also { captured = it }.view() }, accept = { value ->
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
            "teams.readFrozenOutputs" -> {
                fields(args, "viewHandle","fromWork","fromProject")
                require(!(args.has("fromWork")&&args.has("fromProject")))
                val projectSource = if(args.has("fromProject")) args.getJSONObject("fromProject") else null
                val source = if (args.has("fromWork")) args.getJSONObject("fromWork").also {
                    fields(it,"parentTaskId","parentRunId","parentEpoch","conversationId","teamRunId","fileReview")
                } else null
                val owner = readings[text(args,"viewHandle")]?.takeIf { it.kind == "run" && it.live } ?: error("请重新打开原团队成果")
                val shown = JSONObject(owner.original)
                require(shown.optJSONObject("productionScope") != null && (shown.optString("mode") == "produce" || shown.optJSONObject("workPlan") != null)) { "原协作没有文件制作范围" }
                val runId = shown.getString("id")
                fileWindow?.close()
                val files = FileWindow(owner); fileWindow = files; owner.hold(files)
                hostWork(files, work = {
                    val module = controller.webPluginFeatureHost().module("teams") as? TeamsModule ?: error("原团队宿主不可用")
                    fun stillSame() {
                        check(currentWindow() && owner.live) { "原成果窗口已关闭" }; files.reader.check()
                        val now = module.retainedOwnerReading(runId)
                        check(now.optString("id") == runId && now.optInt("generation",-1) == shown.getInt("generation")
                            && now.optLong("revision",-1) == shown.getLong("revision")) { "原协作已更新，先刷新原记录；没有读取另一轮成果" }
                    }
                    stillSame(); val marker = source?.let { controller.requireWorkFileReview(it,runId) }
                    val projectMark = projectSource?.let { PocketWebProjectReviewReturn.original(module,it,runId,files.reader) }
                    val result = module.readOwnerFrozenFiles(runId,files.reader); stillSame()
                    val index = result.index(); val decision = module.ownerTextDecision(result,files.reader)
                    if (source != null && marker != null) { requireWorkFileIndex(index,decision,marker); controller.requireWorkFileReview(source,runId) }
                    if(projectSource!=null && projectMark!=null) {
                        check(owner.projectRun);PocketWebProjectReviewReturn.index(index,decision,projectMark)
                        controller.nativeTokenBudgetHost().tasks.requireProjectTeamFirstReturn(projectSource,runId,files.reader)
                        index.put("projectReviewOrigin",projectMark)
                    }
                    files.install(result,decision); index.put("fileHandle",files.handle).put("ownerDecision",decision).put("fullProject",owner.projectRun)
                }, accept = { result ->
                    check(fileWindow === files && owner.live && files.captured != null) { "原成果阅读已关闭" }
                    deliver(result,null)
                })
            }
            "teams.readFrozenDiff" -> {
                val revision = args.has("basis")
                if (revision) { fields(args,"viewHandle","fileHandle","basis","path","offset"); require(text(args,"basis") == "written_to_frozen") }
                else fields(args,"viewHandle","fileHandle","artifactId","offset")
                val owner = readings[text(args,"viewHandle")] ?: error("原协作记录已关闭")
                val files = fileWindow?.takeIf { it.owner === owner && owner.live && it.handle == text(args,"fileHandle") } ?: error("请重新打开这份原文件目录")
                val offset = args.opt("offset"); require(offset is Int && offset >= 0) { "原差异位置不正确" }
                val captured = files.captured ?: error("原文件目录已关闭")
                val part = if (revision) captured.revisionPage(text(args,"path",240),offset) else captured.page(text(args,"artifactId"),offset)
                deliver(part.put("fileHandle",files.handle),null)
            }
            "teams.reviewFrozenTextApproval", "teams.approveFrozenText", PocketWebFirstProjectOutput.REVIEW, PocketWebFirstProjectOutput.CONFIRM -> {
                val project = action == PocketWebFirstProjectOutput.REVIEW || action == PocketWebFirstProjectOutput.CONFIRM
                val confirming = action == "teams.approveFrozenText" || action == PocketWebFirstProjectOutput.CONFIRM
                if (confirming) fields(args,"viewHandle","fileHandle","approvalHandle") else fields(args,"viewHandle","fileHandle")
                val owner = readings[text(args,"viewHandle")] ?: error("原团队已关闭")
                val files = fileWindow?.takeIf { it.owner === owner && owner.live && it.handle == text(args,"fileHandle") } ?: error("请重读原冻结文件")
                val original = files.captured ?: error("原冻结目录不可用")
                if (confirming) {
                    val approval = files.take(text(args,"approvalHandle"),project)
                    hostWork(approval, work = {
                        try { check(currentWindow() && owner.live); files.reader.check(); approval.confirm() } finally { approval.close() }
                    }, accept = { receipt -> deliver(receipt.put("fileHandle",files.handle),null) })
                } else {
                    hostWork(work = {
                        check(currentWindow() && owner.live); files.reader.check()
                        val module = controller.webPluginFeatureHost().module("teams") as? TeamsModule ?: error("原团队不可用")
                        val approval = PocketWebFirstProjectOutput.open(module,original,project)
                        try { val shown = approval.preview(); shown.put("approvalHandle",files.offer(approval)).put("fileHandle",files.handle) }
                        catch (failure: Exception) { approval.close(); throw failure }
                    }, accept = { shown ->
                        check(fileWindow === files && owner.live && files.captured === original) { "原成果审阅已关闭" }
                        deliver(shown,null)
                    })
                }
            }
            "teams.reviewParentRevision" -> {
                fields(args,"viewHandle","operationId")
                val handle=text(args,"viewHandle"); val operation=text(args,"operationId",128)
                val owner=readings[handle]?.takeIf { it.kind=="run" && it.live } ?: error("请重新打开原协作")
                val retained=JSONObject(owner.original); val source=TeamParentRevisionOffer.exact(retained,operation)
                reviews.entries.removeAll { (_, old) -> (old.expires <= SystemClock.elapsedRealtime()).also { if(it)old.close() } }
                check(reviews.size<8) { "请先关闭已有团队操作审阅" }
                var prepared: TaskStoreTeamWriteHost.WorkPlanPage.ParentProposalReview?=null
                hostWork(work={
                    val page=controller.openTeamWorkPlanPage(source.getString("teamRunId"));workingTeamPage=page
                    owner.hold(page);check(currentWindow()&&owner.live) { "原协作窗口已关闭" };page.setForeground(true)
                    page.reviewParentProposal(retained,operation).also { prepared=it }.preview()
                },accept={ shown ->
                    check(readings[handle]===owner&&owner.live) { "原团队记录已更换，未开始修订" }
                    val token=UUID.randomUUID().toString()
                    reviews[token]=Review("reviseFromParent","",SystemClock.elapsedRealtime()+120_000,ownerReading=owner,proposal=requireNotNull(prepared))
                    deliver(JSONObject().put("operation","reviseFromParent").put("reviewHandle",token).put("input",PocketWebState.publicValue(shown)),null)
                })
            }
            "teams.reviewSoleFailedHandoff" -> {
                fields(args,"viewHandle","workItemId","nextMemberId")
                val handle=text(args,"viewHandle");val item=text(args,"workItemId");val next=text(args,"nextMemberId")
                val owner=readings[handle]?.takeIf{it.kind=="run"&&it.live} ?: error("原团队窗口已经关闭")
                val run=JSONObject(owner.original)
                reviews.entries.removeAll { (_,old)->(old.expires<=SystemClock.elapsedRealtime()).also{if(it)old.close()} }
                check(reviews.size<8){"请先关闭其他团队审阅"}
                var prepared:PocketWebTeamFailedHandoff?=null
                hostWork(work={
                    val page=controller.openTeamWorkPlanPage(run.getString("id"));workingTeamPage=page
                    owner.hold(page);check(currentWindow()&&owner.live);page.setForeground(true)
                    PocketWebTeamFailedHandoff.prepare(run,item,next,page).also{prepared=it}.preview
                },accept={shown->
                    check(owner.live&&readings[handle]===owner){"原团队已离开，没有交接"}
                    val token=UUID.randomUUID().toString()
                    reviews[token]=Review("handoffFailedItem","",SystemClock.elapsedRealtime()+120000,failedHandoff=requireNotNull(prepared),ownerReading=owner)
                    deliver(JSONObject().put("operation","handoffFailedItem").put("reviewHandle",token).put("input",shown),null)
                })
            }
            "teams.reviewWorkItemContinuation" -> {
                fields(args, "viewHandle", "workItemId")
                val handle = text(args, "viewHandle")
                val owner = readings[handle]?.takeIf { it.kind == "run" && it.live } ?: error("请重新打开原团队")
                val source = PocketWebTeamWorkReturn.source(JSONObject(owner.original), text(args, "workItemId"))
                reviews.entries.removeAll { (_, old) -> (old.expires <= SystemClock.elapsedRealtime()).also { if (it) old.close() } }
                check(reviews.size < 8) { "请先关闭已有团队操作审阅" }
                var prepared: PocketWebTeamWorkReturn? = null
                hostWork(work = {
                    val page = controller.openTeamWorkPlanPage(source.team); workingTeamPage = page
                    owner.hold(page); check(currentWindow() && owner.live) { "原分工窗口已关闭" }; page.setForeground(true)
                    PocketWebTeamWorkReturn.prepare(source, page).also { prepared = it }.preview
                }, accept = { shown ->
                    check(readings[handle] === owner && owner.live) { "原团队记录已更换，没有开始工作" }
                    val token = UUID.randomUUID().toString()
                    reviews[token] = Review("continueWorkItem", "", SystemClock.elapsedRealtime() + 120_000,
                        workItem = requireNotNull(prepared), ownerReading = owner)
                    deliver(JSONObject().put("operation", "continueWorkItem").put("reviewHandle", token).put("input", PocketWebState.publicValue(shown)), null)
                })
            }
            "teams.confirm" -> {
                fields(args, "reviewHandle")
                val captured = reviews.remove(text(args, "reviewHandle")) ?: error("原操作审阅已使用或关闭，请先核对原回执")
                if (captured.expires <= SystemClock.elapsedRealtime()) { captured.close(); error("原操作审阅已过期") }
                if(captured.failedHandoff!=null){
                    val original=captured.failedHandoff; val owner=captured.ownerReading;val shown=original.preview
                    if(owner?.live!=true){captured.close();error("已离开原交接窗口")}
                    hostWork(original,work={try{check(currentWindow()&&owner.live);original.confirm()}finally{original.close()}},accept={result->
                        val item=TeamWorkItems.find(result.getJSONObject("workPlan").getJSONArray("items"),shown.getString("workItemId"))
                        deliverReceipt(JSONObject().put("confirmed",true).put("command","handoffFailedItem").put("workItemId",shown.getString("workItemId"))
                            .put("oldAssignmentId",shown.getString("assignmentId")).put("newAssignmentId",item.getString("assignmentId")).put("nextMemberId",item.getString("memberId")).put("reading",view("run",result)))
                    });return
                }
                if(captured.proposal!=null){
                    val proposal=captured.proposal;val shown=proposal.preview();val owner=captured.ownerReading
                    if(owner?.live!=true){captured.close();error("原修订窗口已关闭，未开始")}
                    hostWork(proposal,work={
                        try{check(owner.live&&currentWindow());proposal.confirm()}finally{proposal.close()}
                    },accept={result->
                        val op=shown.getString("operationId")
                        val applied=result.optJSONArray("parentRequests").objects().singleOrNull { it.optString("operationId")==op && it.optString("state")=="applied_to_work_revision" }
                        val requestId=result.getJSONObject("workPlan").getString("lastRevisionRequestId")
                        check(result.optString("id")==shown.getString("runId") && applied!=null && requestId.isNotBlank() && applied.optString("revisionRequestId")==requestId) { "原修订是否已排队待核对，请刷新原协作，不要重发" }
                        deliverReceipt(JSONObject().put("confirmed",true).put("command","reviseFromParent").put("operationId",op)
                            .put("workItemId",shown.getString("workItemId")).put("assignmentId",shown.getString("assignmentId")).put("reading",view("run",result)))
                    })
                } else if (captured.workItem != null) {
                    val work = captured.workItem
                    if (captured.ownerReading?.live != true) { captured.close(); error("原分工审阅已关闭，没有开始工作") }
                    hostWork(work, work = {
                        try { check(captured.ownerReading?.live == true && currentWindow()) { "原分工审阅已关闭" }; work.approve() } finally { work.close() }
                    }, accept = { result -> deliverReceipt(JSONObject().put("confirmed", true).put("command", "continueWorkItem")
                        .put("workItemId", work.source.work.getString("workItemId")).put("assignmentId",work.source.work.getString("assignmentId")).put("reading", view("run", result))) })
                }
                else if (captured.phonePage == null) {
                    val input = JSONObject(captured.input)
                    if (captured.command == "save" && input.optString("mode") == "produce") PocketWebTeamProduction.validateDefinition(input.getJSONArray("members"), snapshot,
                        JSONObject(captured.productionSources ?: error("原文件来源审阅缺失")), productionSources())
                    feature(captured.command, input)
                }
                else hostWork(captured.phonePage, work = { captured.phonePage.confirm(captured.phoneReview ?: error("原手机范围审阅缺失")) }, accept = { result ->
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
                if (action == "teams.answerAssignment") fields(args, "viewHandle", "memberId", "text", "mode", "quoteMessageId", "messageId", "delivery")
                else fields(args, "viewHandle", "command", "memberId", "text", "mode", "quoteMessageId", "messageId")
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
                        check(quote != null && quote.optString("kind") == "assignment_question" && quote.optString("memberId") == member) { "请核对这名成员的原问题" }
                        val delivery = if (args.has("delivery")) text(args, "delivery", 24) else "save"
                        require(delivery in setOf("save", "continue", "continue_saved"))
                        val answer = if (delivery == "continue_saved") {
                            check(!args.has("text") && canAskToContinueSaved(run, quote) && quote.optString("answerId") == messageId) { "原成员、问题或已存回答发生变化，请重新读取" }
                            quote.getString("answer") // Do not substitute a draft or reconstruct a possibly shortened Web preview.
                        } else {
                            check(quote.optString("state") == "pending") { "这条原问题已不再等待回答" }
                            text(args, "text", 4000).trim()
                        }
                        input.put("assignmentId", quote.getString("assignmentId")).put("questionId", quote.getString("questionId")).put("answerId", messageId)
                            .put("answer", answer).put("continueSafely", delivery != "save").put("mode", "reply")
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
