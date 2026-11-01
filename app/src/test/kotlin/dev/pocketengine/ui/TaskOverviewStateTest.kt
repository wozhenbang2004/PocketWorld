package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskOverviewStateTest {
    private fun task(status: String, raw: JSONObject) = UiTask("task", "目标", status, "", "capabilities", 1L, raw)
    @Test fun knownOwnedServiceRemainsActiveInsteadOfPretendingItsResultIsUnknown() {
        val row = task("running", JSONObject().put("historyHasUnconfirmedOperations", false).put("hasUnresolvedOperations", true))
        assertNull(taskOverviewAttention(row)); assertTrue(taskOverviewMatches(row, "active")); assertFalse(taskOverviewMatches(row, "attention"))
    }
    @Test fun nativeUnknownStillWinsOverCompletedStatus() {
        val row = task("completed", JSONObject().put("historyHasUnconfirmedOperations", true).put("hasUnresolvedOperations", true))
        assertNotNull(taskOverviewAttention(row)); assertFalse(taskOverviewMatches(row, "ended"))
    }
    @Test fun unknownTaskStateWinsEvenWhenItsNativeOperationFlagIsFalse() {
        assertNotNull(taskOverviewAttention(task("reconciling", JSONObject().put("historyHasUnconfirmedOperations", false))))
    }
    @Test fun legacyOrMalformedUiFlagKeepsTheConservativeOriginalFallback() {
        assertNotNull(taskOverviewAttention(task("running", JSONObject().put("hasUnresolvedOperations", true))))
        assertNotNull(taskOverviewAttention(task("running", JSONObject().put("historyHasUnconfirmedOperations", "false").put("hasUnresolvedOperations", true))))
    }
}
