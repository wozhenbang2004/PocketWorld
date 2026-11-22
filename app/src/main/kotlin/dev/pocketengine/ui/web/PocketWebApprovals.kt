package dev.pocketengine.ui.web

import dev.pocketengine.features.teams.TeamCoordinator
import dev.pocketengine.store.OwnerWorkTask
import dev.pocketengine.ui.McpLocalProgramApprovalText
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.UiTask
import dev.pocketengine.ui.pendingCapabilityApprovals
import org.json.JSONArray
import org.json.JSONObject

/** Disclosure of the original native approval. This never issues an execution grant. */
internal object PocketWebApprovals {
    fun isExecutionPlan(approval: JSONObject): Boolean = approval.optString("tool") == "execution_plan_review" &&
        approval.optJSONObject("capability")?.optString("kind") == "execution_plan"

    fun needsOriginalPlan(approval: JSONObject): Boolean = isTeamPlan(approval) || isExecutionPlan(approval)

    fun isTeamPlan(approval: JSONObject): Boolean = approval.optString("tool") == "teams_start" &&
        approval.optJSONObject("capability")?.optString("command") == "start_parent_plan"

    fun liveScope(approval: JSONObject): JSONObject? = approval.optJSONObject("capability")
        ?.optJSONObject("ownedService")?.takeIf {
            approval.optString("tool") == "project_execute" && it.optBoolean("subsequentSourceExecution")
        }

    /** Called on the existing owner control worker, since the immutable plan lives in TaskStore. */
    fun read(task: UiTask, controller: PocketWorldController): JSONArray = JSONArray().also { out ->
        pendingCapabilityApprovals(task).forEach { approval ->
            val original = if (needsOriginalPlan(approval)) runCatching {
                val id = approval.optString("approvalId", approval.optString("id"))
                if (isExecutionPlan(approval)) controller.readTaskExecutionPlanApproval(task.id, id)
                else controller.readTeamParentPlanApproval(task.id, id)
            } else null
            val row=project(approval, original?.getOrNull(), original?.exceptionOrNull()?.message, task)
            if (approval.opt("tool") == "teams_start" && controller.webPrivateOwnerVisible())
                projectSavedTeamStart(task, approval, row.getJSONObject("webReview"))
            if(approval.optString("tool")=="team_integrate_file")runCatching {
                check(controller.webPrivateOwnerVisible())
                val window=controller.webWindowTicket();val run=task.raw.getString("runId");val epoch=task.raw.getInt("coordinatorEpoch")
                val reader=dev.pocketengine.core.RunControl.forOriginalRecovery(task.id,run,epoch)
                reader.setAuthorityCheck { check(controller.webPrivateOwnerVisible()&&controller.webWindowTicket()==window) }
                controller.nativeTokenBudgetHost().tasks.ownerFileApprovalSource(task.id,run,epoch,approval.getString("id"),reader).getJSONObject("locator")
            }.getOrNull()?.let { row.getJSONObject("webReview").put("originalTeamFile",it) }
            out.put(row)
        }
    }

    fun project(approval: JSONObject, originalPlan: JSONObject? = null, readError: String? = null, task: UiTask? = null): JSONObject {
        val capability = approval.optJSONObject("capability") ?: JSONObject()
        val view = JSONObject().put("canApprove", true)
        val plan = isTeamPlan(approval)
        val execution = isExecutionPlan(approval)
        val live = liveScope(approval)
        view.put("kind", if (execution) "execution_plan" else if (plan) "team_plan" else if (live != null) "live_scope" else "operation")
        if (execution) {
            val matches = task != null && originalPlan != null && executionPlanMatches(task, approval, originalPlan)
            view.put("canApprove", matches)
            if (matches) view.put("executionPlan", JSONObject(originalPlan.toString()))
            else view.put("readError", readError ?: "未取得这版计划的完整原文，请重新核对；仍可拒绝这次申请")
        }
        if (plan) {
            val matches = originalPlan != null && capability.optString("planRef").isNotBlank() && capability.optString("planHash").isNotBlank() &&
                originalPlan.optString("planRef") == capability.optString("planRef") &&
                originalPlan.optString("planHash") == capability.optString("planHash") &&
                originalPlan.optJSONObject("plan") != null
            view.put("canApprove", matches)
            if (matches) view.put("teamPlan", originalPlan)
            else view.put("readError", readError ?: "原计划与当前审批不一致，请重新核对")
        }
        if (approval.has("firstBuildReview")) {
            val matches = task != null && firstBuildMatches(approval, task)
            view.put("firstBuildVerified", matches).put("canApprove", matches)
            if (!matches) view.put("readError", "独立副本的原构建或这次收件身份不完整，不能批准")
        }
        McpLocalProgramApprovalText.describe(capability)?.let { view.put("programRisk", it) }
        if (approval.optString("tool") == dev.pocketengine.features.semantic.SemanticCapabilityPort.DISPATCH) {
            view.put("semanticDescription", dev.pocketengine.features.semantic.SemanticApprovalText.describe(capability))
        }
        return JSONObject(approval.toString()).put("webReview", view)
    }

    /** Read only the roster frozen on this pending approval, never the directory or current settings. */
    private fun projectSavedTeamStart(task: UiTask, approval: JSONObject, review: JSONObject) {
        val raw = task.raw
        val run = raw.opt("runId") as? String ?: return
        val approvalId = approval.opt("id") as? String ?: return
        // contextAvailable on these two native projections already verifies the private Work and its original source.
        if (!OwnerWorkTask.isOwnerWork(raw) || raw.opt("id") != task.id || raw.opt("contextAvailable") != true ||
            raw.opt("contextWithdrawn") == true || raw.opt("sourceCurrentReadRequired") == true ||
            run.isBlank() || (exactLong(raw, "coordinatorEpoch") ?: 0L) < 1L || !raw.isNull("origin") || !raw.isNull("parentTaskId") ||
            listOf("developmentContext", "teamAssignment", "workProfileRef", "workProfileIntent").any { !raw.isNull(it) } ||
            approvalId.isBlank() || approval.opt("runId") != run || approval.opt("state") != "pending" ||
            !approval.isNull("researchStartOperationId") || !approval.isNull("policyDecision")) return
        when (raw.opt("taskProjection")) {
            "detail_window" -> if (raw.opt("taskCoreReadLimited") != false) return
            "approval_only" -> {
                val read = raw.optJSONObject("capabilityApprovalRead") ?: return
                if (read.opt("format") != "pocketworld.capability-approval-read.v1" ||
                    read.opt("approvalComplete") != true || read.opt("approvalId") != approvalId) return
            }
            else -> return
        }
        val args = approval.optJSONObject("arguments") ?: return
        val capability = approval.optJSONObject("capability") ?: return
        val definition = capability.optJSONObject("teamDefinition") ?: return
        val teamId = args.opt("teamId") as? String ?: return
        val goal = args.opt("goal") as? String ?: return
        if (capability.opt("feature") != "teams" || capability.opt("command") != "start" || capability.length() != 3 ||
            args.keys().asSequence().any { it !in setOf("teamId", "goal", "rounds", "mode") } ||
            goal.isBlank() || goal.length > 8000 || teamId.isBlank() || definition.opt("id") != teamId ||
            definition.opt("mode") != "collaborate" || definition.opt("discussionFormat") != "classic" ||
            definition.has("definitionKind") || definition.has("nativeWorkProfileModels")) return

        val members = definition.optJSONArray("members") ?: return
        val leader = definition.opt("leaderId") as? String ?: return
        if (members.length() !in 1..4) return
        val ids = mutableSetOf<String>()
        val affected = JSONArray()
        var ordinaryLocal = true
        for (i in 0 until members.length()) {
            val member = members.optJSONObject(i) ?: return
            val id = member.opt("id") as? String ?: return
            val name = member.opt("name") as? String ?: return
            if (id.isBlank() || !ids.add(id) || name.isBlank() || name.length > 48) return
            if (member.opt("runner") == "phone_agent") affected.put(id)
            ordinaryLocal = ordinaryLocal && member.opt("runner") == "model" && member.opt("device") == "phone" &&
                !member.has("writeFiles") && !member.has("remoteProduction")
        }
        if (leader !in ids) return
        if (affected.length() > 0) {
            // This saved start lacks the separate per-member scope from startPhones. Refusal stays available.
            review.put("canApprove", false).put("savedTeamPhoneScopeMemberIds", affected)
            return
        }
        // Project only an initial plan in a complete first Work. No reading or estimating a live grant.
        if (!ordinaryLocal || raw.opt("taskProjection") != "detail_window" || exactLong(raw, "coordinatorEpoch") != 1L ||
            raw.opt("kind") != "assistant" || raw.opt("executionMode") != "capabilities" || raw.opt("cancelRequested") == true ||
            !raw.isNull("ownerPhoneControl") || !raw.isNull("ownerDeviceWork") || !raw.isNull("teamReservationPool") ||
            raw.opt("ownerWorkTeamsUnavailable") == true || raw.optJSONArray("ownerWorkTeams")?.length() != 0 ||
            args.has("mode") && args.opt("mode") != "collaborate") return
        val rounds = (if (args.has("rounds")) exactLong(args, "rounds") else 1L) ?: return // Saved starts default to one round.
        if (rounds != 1L && rounds != 2L) return
        val budget = TeamCoordinator.initialBudget(members.length(), rounds.toInt())
        review.put("initialSavedTeamLimits", JSONObject().put("rounds", rounds)
            .put("modelRequests", budget.getInt("callLimit")).put("toolOperations", budget.getInt("toolCallLimit")))
    }

    /** Positive decisions preserve the same explicit scope checks as the existing native UI. */
    private fun firstBuildMatches(approval: JSONObject, task: UiTask) =
        dev.pocketengine.features.local.project.OwnerDraftFirstBuild.reviewMatches(approval, task.id,
            task.raw.optString("runId"), task.raw.optInt("coordinatorEpoch", -1))

    fun requireDecision(approval: JSONObject, args: JSONObject, task: UiTask? = null) {
        require(args.opt("approved") is Boolean) { "缺少有效授权决定" }
        if (!args.getBoolean("approved")) return
        if (approval.has("firstBuildReview"))
            require(task != null && firstBuildMatches(approval, task)) { "不是原轮的独立副本构建或收件，不能批准" }
        if (liveScope(approval) != null || isTeamPlan(approval) || isExecutionPlan(approval)) {
            require(args.opt("scopeReviewed") == true) { "请先核对本次完整范围与预算" }
        }
        if (isTeamPlan(approval)) {
            val capability = approval.getJSONObject("capability")
            require(capability.optString("planRef").isNotBlank() && capability.optString("planHash").isNotBlank() &&
                args.optString("planRef") == capability.optString("planRef") &&
                args.optString("planHash") == capability.optString("planHash")) { "原计划身份已变化，请重新核对" }
        }
        if (isExecutionPlan(approval)) {
            val capability = approval.getJSONObject("capability")
            require(exactLong(args, "planRevision") != null && exactLong(args, "planRevision") == exactLong(capability, "planRevision") &&
                args.opt("planArtifactRef") is String && args.optString("planArtifactRef") == capability.optString("planArtifactRef") &&
                args.opt("planHash") is String && args.optString("planHash") == capability.optString("planHash")) { "原计划版本已变化，请重新核对" }
        }
    }

    fun ordinaryPlanningTask(task: UiTask): Boolean {
        val raw = task.raw
        return raw.optString("actorId") == "owner" && raw.optString("spaceId") == "private" &&
            raw.optString("kind") in setOf("phone", "assistant") && (!raw.has("origin") || raw.isNull("origin")) &&
            listOf("teamReservationPool", "teamProjectRoundOrigin", "teamAssignment", "teamWriteScope", "sourceLearningJob").none { raw.has(it) && !raw.isNull(it) } &&
            raw.optJSONObject("executionScope") != null
    }

    /** These values come from the consumed native review, never from Web request identity fields. */
    fun planningInput(task: UiTask): JSONObject {
        require(ordinaryPlanningTask(task)) { "请从原私人任务进入只读规划" }
        val scope = task.raw.getJSONObject("executionScope")
        require(scope.optString("executionPhase", "executing") == "executing") { "原任务已经进入规划，请重新读取" }
        return taskInput(task).put("expectedScopeRevision", exactLong(scope, "revision") ?: error("原任务范围版本缺失"))
    }

    fun executionDecisionInput(task: UiTask, approval: JSONObject, approved: Boolean): JSONObject {
        require(ordinaryPlanningTask(task) && isExecutionPlan(approval)) { "原任务不属于这次计划审批" }
        require(approval.optString("runId").isNotBlank() && approval.optString("runId") == task.raw.optString("runId")) { "原审批执行代次已变化" }
        val scope = task.raw.getJSONObject("executionScope")
        val capability = approval.getJSONObject("capability")
        val revision = exactLong(capability, "planRevision") ?: error("原计划版本缺失")
        val ref = capability.opt("planArtifactRef") as? String ?: error("原计划来源缺失")
        val hash = capability.opt("planHash") as? String ?: error("原计划正文身份缺失")
        require(revision > 0 && ref.isNotBlank() && hash.matches(Regex("[0-9a-f]{64}"))) { "原计划身份无效" }
        if (approved) require(scope.optString("executionPhase") == "planning" &&
            exactLong(capability, "scopeRevision") == exactLong(scope, "revision") &&
            exactLong(scope, "planRevision") == revision && scope.optString("planArtifactRef") == ref && scope.optString("planHash") == hash) { "原计划已换版，请重新核对" }
        return taskInput(task).put("approvalId", approval.optString("approvalId", approval.optString("id")))
            .put("approved", approved).put("expectedScopeRevision", exactLong(scope, "revision") ?: error("原任务范围版本缺失"))
            .put("planRevision", revision).put("planArtifactRef", ref).put("planHash", hash)
    }

    private fun taskInput(task: UiTask): JSONObject {
        val run = task.raw.opt("runId") as? String ?: error("原执行身份缺失")
        val epoch = exactLong(task.raw, "coordinatorEpoch") ?: error("原执行代次缺失")
        return JSONObject().put("taskId", task.id).put("runId", run).put("epoch", epoch)
    }

    private fun executionPlanMatches(task: UiTask, approval: JSONObject, original: JSONObject): Boolean = runCatching {
        val input = executionDecisionInput(task, approval, true)
        require(original.optString("taskId") == task.id && original.optString("runId") == input.getString("runId") &&
            exactLong(original, "epoch") == exactLong(input, "epoch") && exactLong(original, "scopeRevision") == exactLong(input, "expectedScopeRevision") &&
            exactLong(original, "planRevision") == exactLong(input, "planRevision") && original.optString("planArtifactRef") == input.getString("planArtifactRef") &&
            original.optString("planHash") == input.getString("planHash"))
        val plan = original.getJSONObject("plan")
        require(plan.keys().asSequence().all { it in setOf("format", "goal", "steps", "sources") } &&
            plan.optString("format") == "pocketworld.execution-plan.v1" && plan.opt("goal") is String &&
            plan.toString().toByteArray(Charsets.UTF_8).size <= 32_000)
        val steps = plan.getJSONArray("steps"); require(steps.length() > 0)
        val ids = mutableSetOf<String>()
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            require(step.keys().asSequence().toSet() == setOf("id", "title", "detail") &&
                listOf("id", "title", "detail").all { step.opt(it) is String } &&
                step.getString("id").isNotBlank() && ids.add(step.getString("id")))
        }
        if (plan.has("sources")) {
            val sources = plan.getJSONArray("sources")
            require((0 until sources.length()).all { sources.opt(it) is String })
        }
        require(samePublicValue(plan, PocketWebState.publicValue(plan)))
        true
    }.getOrDefault(false)

    private fun exactLong(value: JSONObject, name: String): Long? = when (val number = value.opt(name)) {
        is Int -> number.toLong().takeIf { it >= 0 }
        is Long -> number.takeIf { it >= 0 }
        else -> null
    }

    private fun samePublicValue(left: Any?, right: Any?): Boolean = when (left) {
        is JSONObject -> right is JSONObject && left.keys().asSequence().toSet() == right.keys().asSequence().toSet() &&
            left.keys().asSequence().all { samePublicValue(left.opt(it), right.opt(it)) }
        is JSONArray -> right is JSONArray && left.length() == right.length() &&
            (0 until left.length()).all { samePublicValue(left.opt(it), right.opt(it)) }
        else -> left == right
    }
}
