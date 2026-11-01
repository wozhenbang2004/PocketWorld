package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteEventReplayPollingStateTest {
    private fun terminal() = JSONObject().put("queryStatus", "confirmed").put("eventReadStatus", "confirmed")
        .put("task", JSONObject().put("status", "cancelled").put("cancelRequested", true))
        .put("source", JSONObject().put("executorStatus", "cancelled"))
        .put("eventRead", JSONObject().put("hasMore", true))

    @Test fun terminalOriginalJobMayDrainOnlyItsAlreadyRequestedFixedHeadThenStops() {
        val view=terminal()
        assertTrue(remoteReadShouldPoll(view))
        view.getJSONObject("eventRead").put("hasMore", false)
        assertFalse(remoteReadShouldPoll(view))
    }

    @Test fun backgroundOrFailedOriginalReadNeverRestartsItselfFromAnOldCatchupFlag() {
        for(status in listOf("paused", "unconfirmed", "changed"))assertFalse(remoteReadShouldPoll(terminal().put("queryStatus", status)))
        assertFalse(remoteReadShouldPoll(terminal().put("eventReadStatus", "unconfirmed")))
        assertFalse(remoteReadShouldPoll(terminal().put("eventReadStatus", "unsupported")))
        val running=terminal().put("eventReadStatus", "not_available")
            .put("task",JSONObject().put("status","running").put("cancelRequested",false))
            .put("source",JSONObject().put("executorStatus","running"))
        assertFalse("No repeated catchup from the preserved old hasMore flag",remoteReadShouldPoll(running))
        assertTrue("A later explicit successful read can resume observation",remoteReadShouldPoll(running.put("eventReadStatus","confirmed")))
    }
}
