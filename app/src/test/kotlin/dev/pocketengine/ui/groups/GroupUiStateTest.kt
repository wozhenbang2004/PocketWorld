package dev.pocketengine.ui.groups

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GroupUiStateTest {
    private fun snapshot(revision: Long) = JSONObject().put("revision", revision).put("groups", JSONArray()).put("requests", JSONArray()).put("deliveries", JSONArray())
    private fun group(revision: Long = 2) = JSONObject().put("id", "group-1").put("revision", revision).put("policyEpoch", "policy-1")
    @Test fun lateResponseCannotReplaceAnotherNativeRequest() {
        val value = JSONObject().put("requestId", "first").put("ok", true).put("result", snapshot(3))
        assertNull(GroupUiState.result("second", value)); assertNotNull(GroupUiState.result("first", value))
        value.put("ok", "true"); assertNull(GroupUiState.result("first", value))
    }
    @Test fun oldSnapshotCannotRollBackGroupReviews() {
        assertFalse(GroupUiState.newerSnapshot(snapshot(4), snapshot(3)))
        assertTrue(GroupUiState.newerSnapshot(snapshot(4), snapshot(5)))
        assertFalse(GroupUiState.newerSnapshot(snapshot(4), JSONObject().put("revision", "5")))
    }
    @Test fun groupMemberOrPolicyRevisionInvalidatesReviewedPayload() {
        val frozen = group()
        assertThrows(IllegalStateException::class.java) { GroupUiState.reviewedPayload(frozen, group(3), JSONObject().put("enabled", true)) }
        assertThrows(IllegalStateException::class.java) { GroupUiState.reviewedPayload(frozen, group().put("policyEpoch", "replacement"), JSONObject()) }
        val payload = GroupUiState.reviewedPayload(frozen, group(), JSONObject().put("enabled", true).put("revision", 900))
        assertEquals(2, payload.getLong("revision")); assertEquals("group-1", payload.getString("id"))
    }
    @Test fun taskDetailCannotAttachToAnotherSelectedRequest() {
        val result = JSONObject().put("known", true).put("reference", JSONObject().put("requestId", "r1").put("taskId", "t1"))
            .put("task", JSONObject().put("taskId", "t1"))
        assertNull(GroupUiState.exactTask("r2", result))
        result.getJSONObject("task").put("taskId", "t2")
        assertThrows(IllegalStateException::class.java) { GroupUiState.exactTask("r1", result) }
    }
    @Test fun cancellationBindsActualReadRunAndEpoch() {
        val run = "12345678-1234-1234-1234-123456789abc"
        val result = JSONObject().put("known", true).put("reference", JSONObject().put("requestId", "r1").put("taskId", "t1"))
            .put("task", JSONObject().put("taskId", "t1").put("runId", run).put("coordinatorEpoch", 7))
        val payload = GroupUiState.cancellation("r1", result)
        assertEquals(run, payload.getString("runId")); assertEquals(7, payload.getInt("coordinatorEpoch"))
        result.getJSONObject("task").put("runId", "")
        assertThrows(IllegalStateException::class.java) { GroupUiState.cancellation("r1", result) }
    }
    @Test fun deliveryAcceptanceAndUnknownStayDifferentFromDelivered() {
        assertTrue(GroupUiState.deliveryLabel("accepted").contains("送达待核对"))
        assertTrue(GroupUiState.deliveryLabel("unknown").contains("只能查询"))
        assertEquals("已送达", GroupUiState.deliveryLabel("delivered"))
        assertNotEquals(GroupUiState.taskLabel("completed"), GroupUiState.taskLabel("reconciling"))
    }
}
