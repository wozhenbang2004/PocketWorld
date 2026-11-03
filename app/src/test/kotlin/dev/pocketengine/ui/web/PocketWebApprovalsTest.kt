package dev.pocketengine.ui.web

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PocketWebApprovalsTest {
    private fun planApproval() = JSONObject().put("id", "approval-a").put("tool", "teams_start")
        .put("capability", JSONObject().put("command", "start_parent_plan").put("planRef", "plan-a").put("planHash", "hash-a"))

    @Test fun capabilitySummaryCannotStandInForAnUnreadOrDifferentImmutablePlan() {
        val approval = planApproval()
        approval.getJSONObject("capability").put("plan", JSONObject().put("goal", "untrusted summary"))
        assertFalse(PocketWebApprovals.project(approval).getJSONObject("webReview").getBoolean("canApprove"))
        val different = JSONObject().put("planRef", "plan-a").put("planHash", "hash-b").put("plan", JSONObject())
        assertFalse(PocketWebApprovals.project(approval, different).getJSONObject("webReview").getBoolean("canApprove"))
        val original = different.put("planHash", "hash-a").put("plan", JSONObject().put("goal", "original goal").put("modelRequestLimit", 17))
        val view = PocketWebApprovals.project(approval, original).getJSONObject("webReview")
        assertTrue(view.getBoolean("canApprove"))
        assertEquals(17, view.getJSONObject("teamPlan").getJSONObject("plan").getInt("modelRequestLimit"))
        assertEquals("original goal", view.getJSONObject("teamPlan").getJSONObject("plan").getString("goal"))
    }

    @Test fun teamApprovalNeedsExplicitScopeConfirmationAndExactPlanIdentity() {
        val approval = planApproval()
        val args = JSONObject().put("approved", true).put("planRef", "plan-a").put("planHash", "hash-a")
        assertThrows(IllegalArgumentException::class.java) { PocketWebApprovals.requireDecision(approval, args) }
        assertThrows(IllegalArgumentException::class.java) { PocketWebApprovals.requireDecision(approval, args.put("scopeReviewed", "true")) }
        assertThrows(IllegalArgumentException::class.java) { PocketWebApprovals.requireDecision(approval, args.put("scopeReviewed", true).put("planHash", "hash-b")) }
        PocketWebApprovals.requireDecision(approval, args.put("planHash", "hash-a"))
        PocketWebApprovals.requireDecision(approval, JSONObject().put("approved", false))
    }

    @Test fun subsequentSourceExecutionCannotUseTheOrdinaryOneClickApprovalPath() {
        val approval = JSONObject().put("tool", "project_execute").put("capability", JSONObject()
            .put("ownedService", JSONObject().put("subsequentSourceExecution", true)))
        val args = JSONObject().put("approved", true)
        assertThrows(IllegalArgumentException::class.java) { PocketWebApprovals.requireDecision(approval, args) }
        assertThrows(IllegalArgumentException::class.java) { PocketWebApprovals.requireDecision(approval, args.put("scopeReviewed", "true")) }
        PocketWebApprovals.requireDecision(approval, args.put("scopeReviewed", true))
        PocketWebApprovals.requireDecision(approval, JSONObject().put("approved", false))
    }
}
