package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExternalApprovalStateTest {
    @Test fun remoteApprovalUsesHostBoundProfileAndJob() {
        val task = taskFromJson(JSONObject().put("feature", "remote").put("externalId", "job-1").put("externalRefs", JSONObject().put("profileId", "profile-1")).put("evidence", JSONObject().put("pendingApprovals", JSONArray().put(JSONObject().put("requestId", "request-1").put("canAccept", false)))))
        val target = remoteApprovalTarget(task)!!
        assertEquals("profile-1", target.profileId)
        assertEquals("job-1", target.jobId)
        assertEquals("request-1", target.approvals.single().getString("requestId"))
        assertFalse(target.approvals.single().getBoolean("canAccept"))
    }

    @Test fun unrelatedTaskOrMissingRemoteIdentityDoesNotExposeApproval() {
        assertNull(remoteApprovalTarget(taskFromJson(JSONObject().put("feature", "phone").put("externalId", "job-1").put("externalRefs", JSONObject().put("profileId", "profile-1")))))
        assertNull(remoteApprovalTarget(taskFromJson(JSONObject().put("feature", "remote").put("externalId", "job-1"))))
    }

    @Test fun unknownMediaNeedsExplicitNotOccurredReviewOnTheSameTask() {
        val result = JSONObject().put("status", "unknown").put("hostTaskId", "media-task")
        val reviewed = taskFromJson(JSONObject().put("id", "media-task").put("evidence", JSONObject().put("ownerReconciliation", JSONObject().put("outcome", "not_occurred"))))
        assertFalse(ownerConfirmedExternalNotOccurred(result, emptyList()))
        assertTrue(ownerConfirmedExternalNotOccurred(result, listOf(reviewed)))
        assertFalse(ownerConfirmedExternalNotOccurred(JSONObject(result.toString()).put("hostTaskId", "different-task"), listOf(reviewed)))
    }

    @Test fun genericAwaitingUserOrOccurredReviewDoesNotAuthorizeMediaRetry() {
        val result = JSONObject().put("hostTaskId", "media-task")
        val unreviewed = taskFromJson(JSONObject().put("id", "media-task").put("status", "awaiting_user"))
        val occurred = taskFromJson(JSONObject().put("id", "media-task").put("evidence", JSONObject().put("ownerReconciliation", JSONObject().put("outcome", "occurred"))))
        assertFalse(ownerConfirmedExternalNotOccurred(result, listOf(unreviewed)))
        assertFalse(ownerConfirmedExternalNotOccurred(result, listOf(occurred)))
    }
}
