package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UiTaskStateTest {
    private fun task(operations: JSONArray? = null, updatedAt: Long = 20): UiTask {
        val raw = JSONObject().put("id", "task").put("kind", "phone").put("executionMode", "background").put("updatedAt", updatedAt)
        if (operations != null) raw.put("operations", operations)
        return taskFromJson(raw)
    }

    @Test fun preparedAndUnknownReceiptsRequireReconciliation() {
        val value = task(JSONArray().put(JSONObject().put("operationId", "first").put("state", "prepared")).put(JSONObject().put("operationId", "second").put("state", "unknown")).put(JSONObject().put("operationId", "third").put("state", "receipt")))
        assertEquals(listOf("first", "second"), unresolvedOperations(value).map { it.getString("operationId") })
        assertEquals("等待核对动作", taskStatus("reconciling"))
    }

    @Test fun summaryRefreshPreservesReceiptsButDetailedResponseReplacesThem() {
        val previous = task(JSONArray().put(JSONObject().put("state", "unknown")))
        assertEquals(1, unresolvedOperations(mergeTaskSummary(task(updatedAt = 30), previous)).size)
        assertEquals(0, unresolvedOperations(mergeTaskSummary(task(JSONArray(), 30), previous)).size)
    }

    @Test fun delayedRefreshCannotRestoreOlderUnresolvedActions() {
        val resolved = task(JSONArray(), 40)
        val stale = task(JSONArray().put(JSONObject().put("state", "unknown")), 20)
        assertSame(resolved, mergeTaskSummary(stale, resolved))
    }

    @Test fun externalTasksAreNotOfferedPhoneResumeControls() {
        val remote = taskFromJson(JSONObject().put("kind", "remote").put("executionMode", "remote"))
        assertFalse(isPhoneTask(remote))
        assertEquals("电脑环境", executionLocation(remote.executionMode))
        assertTrue(isPhoneTask(task()))
    }
    @Test fun channelOriginIsVisibleWithoutChangingExecutionPermissions() {
        val channel = taskFromJson(JSONObject().put("kind", "assistant").put("executionMode", "capabilities")
            .put("origin", JSONObject().put("kind", "channel").put("senderId", "owner")))
        assertEquals("主人私聊", taskOriginLabel(channel))
        assertFalse(isPhoneTask(channel))
        assertNull(taskOriginLabel(task()))
    }
    @Test fun reviewedRealtimeOriginDoesNotImplyPhoneOrAuthenticatedSpeaker() {
        val reviewed = taskFromJson(JSONObject().put("kind", "assistant").put("executionMode", "capabilities").put("status", "awaiting_user")
            .put("origin", JSONObject().put("kind", "owner_reviewed_realtime").put("identityVerified", false)))
        assertEquals("语音复核", taskOriginLabel(reviewed))
        assertFalse(isPhoneTask(reviewed))
        assertFalse(reviewed.raw.getJSONObject("origin").getBoolean("identityVerified"))
    }
}
