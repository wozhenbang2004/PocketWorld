package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Calls the actual page policy; no copied poll logic, transport or synthetic execution authority. */
class RemoteReadPollingStateTest {
    private fun snapshot(status: String = "awaiting_input") = JSONObject()
        .put("queryStatus", "confirmed").put("fresh", true)
        .put("source", JSONObject().put("executorStatus", status))
        .put("task", JSONObject().put("status", "awaiting_user").put("cancelRequested", false))

    @Test fun failedReadStopsUntilExplicitSamePageReconnectCanReadAgain() {
        val original = snapshot()
        assertTrue(remoteReadShouldPoll(original))
        assertFalse(remoteReadShouldPoll(JSONObject(original.toString()).put("queryStatus", "unconfirmed")))
        assertFalse(remoteReadShouldPoll(JSONObject(original.toString()).put("queryStatus", "changed")))
        assertFalse(remoteReadShouldPoll(JSONObject(original.toString()).put("queryStatus", "paused")))
        assertTrue(remoteReadShouldPoll(JSONObject(original.toString()).put("queryStatus", "confirmed")))
        // Mutation receipts may require GET reconciliation; stale review is not a write retry.
        assertTrue(remoteReadShouldPoll(JSONObject(original.toString()).put("fresh", false)))
    }

    @Test fun originalCancellationAndTerminalExecutorNeverRestartPeriodicReads() {
        assertFalse(remoteReadShouldPoll(snapshot().put("task", JSONObject().put("status", "cancelled"))))
        assertFalse(remoteReadShouldPoll(snapshot().put("task", JSONObject().put("cancelRequested", true))))
        assertFalse(remoteReadShouldPoll(snapshot("completed")))
        assertFalse(remoteReadShouldPoll(snapshot("failed")))
        assertFalse(remoteReadShouldPoll(snapshot("cancelled")))
        assertTrue(remoteReadShouldPoll(snapshot("awaiting_approval")))
        assertFalse(remoteReadShouldPoll(JSONObject()))
    }
}
