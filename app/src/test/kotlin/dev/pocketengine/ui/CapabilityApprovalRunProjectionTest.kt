package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CapabilityApprovalRunProjectionTest {
    private fun approval(id: String, run: String? = "current", state: String = "pending", expires: Long = Long.MAX_VALUE): JSONObject =
        JSONObject().put("id", id).put("state", state).put("expiresAt", expires).also { if (run != null) it.put("runId", run) }

    @Test fun onlyUnexpiredPendingFromTheCurrentNonemptyRunIsShown() {
        val raw = JSONObject().put("runId", "current").put("approvals", JSONArray()
            .put(approval("original", "old"))
            .put(approval("missing", null))
            .put(approval("blank", ""))
            .put(approval("current"))
            .put(approval("expired", expires = 1))
            .put(approval("decided", state = "approved")))
        assertEquals(listOf("current"), pendingCapabilityApprovals(taskFromJson(raw)).map { it.getString("id") })
    }

    @Test fun anUnstartedTaskCannotDisplayUnboundLegacyPending() {
        for (run in listOf<String?>(null, "")) {
            val raw = JSONObject().put("approvals", JSONArray().put(approval("blank", "")).put(approval("missing", null)))
            if (run != null) raw.put("runId", run)
            assertTrue(pendingCapabilityApprovals(taskFromJson(raw)).isEmpty())
        }
    }

    @Test fun aQueuedDecisionNoticeDoesNotPromiseThatExecutionContinued() {
        val message = "审批已保存，已排队尝试续接原任务；尚未确认执行"
        val result = JSONObject().put("approvalDecisionSaved", true).put("continuedOriginalRun", false)
            .put("continuationScheduled", true).put("continuationState", "queued").put("message", message)
        assertEquals(message, capabilityApprovalNotice(result, true))
    }

    @Test fun aSavedButBlockedDecisionKeepsItsActualContinuationResult() {
        val message = "审批已保存，但未继续执行：原审批执行已变化，未继续新执行"
        val result = JSONObject().put("approvalDecisionSaved", true).put("continuedOriginalRun", false)
            .put("continuationScheduled", false).put("continuationState", "not_continued").put("message", message)
        assertEquals(message, capabilityApprovalNotice(result, true))
        assertEquals("已拒绝这次操作", capabilityApprovalNotice(result, false))
        assertEquals("审批已保存，请核对原任务续接状态", capabilityApprovalNotice(JSONObject().put("approvalDecisionSaved", true), true))
    }

    @Test fun specializedTeamContinuationNoticeKeepsTheOriginalAudienceBoundary() {
        val result = JSONObject().put("originalTeamContinuationRequired", true)
        assertEquals("审批已保存在原分工，请回团队继续该成员或唯一整合者", capabilityApprovalNotice(result, true))
    }

}
