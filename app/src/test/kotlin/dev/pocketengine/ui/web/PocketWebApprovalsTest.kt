package dev.pocketengine.ui.web

import dev.pocketengine.ui.UiTask
import org.json.JSONArray
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

    private fun executionTask(): UiTask {
        val scope = JSONObject().put("revision", 8).put("executionPhase", "planning").put("planRevision", 2)
            .put("planArtifactRef", "proposal-original").put("planHash", "a".repeat(64))
            .put("toolLimit", 20).put("toolsUsed", 7).put("modelCallLimit", 12).put("modelCallsUsed", 4)
        val raw = JSONObject().put("id", "task-original").put("actorId", "owner").put("spaceId", "private")
            .put("kind", "assistant").put("runId", "run-original").put("coordinatorEpoch", 3).put("executionScope", scope)
        return UiTask("task-original", "核对原规划", "awaiting_user", "等待原计划决定", "capabilities", 100L, raw)
    }

    private fun executionApproval() = JSONObject().put("id", "approval-original").put("runId", "run-original")
        .put("tool", "execution_plan_review").put("capability", JSONObject().put("kind", "execution_plan")
            .put("scopeRevision", 8).put("planRevision", 2).put("planArtifactRef", "proposal-original").put("planHash", "a".repeat(64)))

    private fun executionPlan() = JSONObject().put("taskId", "task-original").put("runId", "run-original").put("epoch", 3)
        .put("scopeRevision", 8).put("planRevision", 2).put("planArtifactRef", "proposal-original").put("planHash", "a".repeat(64))
        .put("plan", JSONObject().put("format", "pocketworld.execution-plan.v1").put("goal", "原目标")
            .put("steps", JSONArray().put(JSONObject().put("id", "read").put("title", "读取原资料").put("detail", "保留原范围，核对真实证据")))
            .put("sources", JSONArray().put("原任务记录")))

    @Test fun executionApprovalNeedsTheCompleteOriginalBodyAndExactScopePlanTuple() {
        val task = executionTask(); val approval = executionApproval()
        fun canApprove(plan: JSONObject?) = PocketWebApprovals.project(approval, plan, task = task).getJSONObject("webReview").getBoolean("canApprove")
        assertFalse(canApprove(null))
        assertTrue(canApprove(executionPlan()))
        for (field in listOf("taskId", "runId", "planArtifactRef", "planHash")) assertFalse(field, canApprove(executionPlan().put(field, "different")))
        for (field in listOf("epoch", "scopeRevision", "planRevision")) assertFalse(field, canApprove(executionPlan().put(field, 99)))
        task.raw.getJSONObject("executionScope").put("revision", 9)
        assertFalse(canApprove(executionPlan()))
    }

    @Test fun hiddenExtraOrIncompletePlanContentCannotBeApprovedAsTheFullBody() {
        val task = executionTask(); val approval = executionApproval()
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("apiKey", "must-not-be-displayed") },
            { it.put("steps", JSONArray()) },
            { it.getJSONArray("steps").getJSONObject(0).remove("detail") },
            { it.getJSONArray("steps").put(JSONObject(it.getJSONArray("steps").getJSONObject(0).toString())) },
            { it.put("goal", "界".repeat(11_000)) }
        )) {
            val original = executionPlan(); change(original.getJSONObject("plan"))
            val view = PocketWebApprovals.project(approval, original, task = task).getJSONObject("webReview")
            assertFalse(view.getBoolean("canApprove")); assertFalse(view.has("executionPlan"))
            PocketWebApprovals.requireDecision(approval, JSONObject().put("approved", false))
        }
    }

    @Test fun executionDecisionUsesNativeScopeAndApprovalIdentityAndNeverAddsBudget() {
        val task = executionTask(); val approval = executionApproval()
        val input = PocketWebApprovals.executionDecisionInput(task, approval, true)
        assertEquals("task-original", input.getString("taskId")); assertEquals("run-original", input.getString("runId"))
        assertEquals(3, input.getInt("epoch")); assertEquals(8, input.getInt("expectedScopeRevision"))
        assertEquals(2, input.getInt("planRevision")); assertEquals("proposal-original", input.getString("planArtifactRef"))
        assertFalse(input.has("toolLimit")); assertFalse(input.has("modelCallLimit")); assertFalse(input.has("deadlineAt"))
        task.raw.getJSONObject("executionScope").put("planRevision", 3)
        assertThrows(IllegalArgumentException::class.java) { PocketWebApprovals.executionDecisionInput(task, approval, true) }
        // A failed body read does not prevent rejecting this original pending ID; SQL rechecks its state.
        assertEquals("approval-original", PocketWebApprovals.executionDecisionInput(task, approval, false).getString("approvalId"))
    }

    @Test fun executionDecisionNeedsAnExplicitCheckedBoxAndExactDisplayedVersion() {
        val approval = executionApproval()
        val args = JSONObject().put("approved", true).put("planRevision", 2).put("planArtifactRef", "proposal-original").put("planHash", "a".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { PocketWebApprovals.requireDecision(approval, args) }
        assertThrows(IllegalArgumentException::class.java) { PocketWebApprovals.requireDecision(approval, args.put("scopeReviewed", "true")) }
        assertThrows(IllegalArgumentException::class.java) { PocketWebApprovals.requireDecision(approval, args.put("scopeReviewed", true).put("planRevision", 2.0)) }
        assertThrows(IllegalArgumentException::class.java) { PocketWebApprovals.requireDecision(approval, args.put("planRevision", 3)) }
        PocketWebApprovals.requireDecision(approval, args.put("planRevision", 2))
    }

    @Test fun planningEntryOnlyCapturesTheExistingPrivateScopeAndDoesNotStartOrRefill() {
        val task = executionTask(); task.raw.getJSONObject("executionScope").put("executionPhase", "executing")
        val input = PocketWebApprovals.planningInput(task)
        assertEquals(setOf("taskId", "runId", "epoch", "expectedScopeRevision"), input.keys().asSequence().toSet())
        assertEquals(8L, input.getLong("expectedScopeRevision"))
        for (field in listOf("teamReservationPool", "teamProjectRoundOrigin", "teamAssignment", "teamWriteScope", "sourceLearningJob")) {
            task.raw.put(field, JSONObject())
            assertThrows(IllegalArgumentException::class.java) { PocketWebApprovals.planningInput(task) }
            task.raw.remove(field)
        }
        task.raw.put("origin", JSONObject().put("kind", "channel"))
        assertThrows(IllegalArgumentException::class.java) { PocketWebApprovals.planningInput(task) }
    }
}
