package dev.pocketengine.ui.web

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
            out.put(project(approval, original?.getOrNull(), original?.exceptionOrNull()?.message, task))
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
        McpLocalProgramApprovalText.describe(capability)?.let { view.put("programRisk", it) }
        if (approval.optString("tool") == dev.pocketengine.features.semantic.SemanticCapabilityPort.DISPATCH) {
            view.put("semanticDescription", dev.pocketengine.features.semantic.SemanticApprovalText.describe(capability))
        }
        return JSONObject(approval.toString()).put("webReview", view)
    }

    /** Positive decisions preserve the same explicit scope checks as the existing native UI. */
    fun requireDecision(approval: JSONObject, args: JSONObject) {
        require(args.opt("approved") is Boolean) { "缺少有效授权决定" }
        if (!args.getBoolean("approved")) return
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
