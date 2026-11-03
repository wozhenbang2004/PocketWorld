package dev.pocketengine.ui.web

import dev.pocketengine.ui.McpLocalProgramApprovalText
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.UiTask
import dev.pocketengine.ui.pendingCapabilityApprovals
import org.json.JSONArray
import org.json.JSONObject

/** Disclosure of the original native approval. This never issues an execution grant. */
internal object PocketWebApprovals {
    fun isTeamPlan(approval: JSONObject): Boolean = approval.optString("tool") == "teams_start" &&
        approval.optJSONObject("capability")?.optString("command") == "start_parent_plan"

    fun liveScope(approval: JSONObject): JSONObject? = approval.optJSONObject("capability")
        ?.optJSONObject("ownedService")?.takeIf {
            approval.optString("tool") == "project_execute" && it.optBoolean("subsequentSourceExecution")
        }

    /** Called on the existing owner control worker, since the immutable plan lives in TaskStore. */
    fun read(task: UiTask, controller: PocketWorldController): JSONArray = JSONArray().also { out ->
        pendingCapabilityApprovals(task).forEach { approval ->
            val original = if (isTeamPlan(approval)) runCatching {
                controller.readTeamParentPlanApproval(task.id, approval.optString("approvalId", approval.optString("id")))
            } else null
            out.put(project(approval, original?.getOrNull(), original?.exceptionOrNull()?.message))
        }
    }

    fun project(approval: JSONObject, originalPlan: JSONObject? = null, readError: String? = null): JSONObject {
        val capability = approval.optJSONObject("capability") ?: JSONObject()
        val view = JSONObject().put("canApprove", true)
        val plan = isTeamPlan(approval)
        val live = liveScope(approval)
        view.put("kind", if (plan) "team_plan" else if (live != null) "live_scope" else "operation")
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
        if (liveScope(approval) != null || isTeamPlan(approval)) {
            require(args.opt("scopeReviewed") == true) { "请先核对本次完整范围与预算" }
        }
        if (isTeamPlan(approval)) {
            val capability = approval.getJSONObject("capability")
            require(capability.optString("planRef").isNotBlank() && capability.optString("planHash").isNotBlank() &&
                args.optString("planRef") == capability.optString("planRef") &&
                args.optString("planHash") == capability.optString("planHash")) { "原计划身份已变化，请重新核对" }
        }
    }
}
