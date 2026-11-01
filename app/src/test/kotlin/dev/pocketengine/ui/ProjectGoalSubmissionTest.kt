package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProjectGoalSubmissionTest {
    @Test fun uncertainClientKeepsItsOriginalGoalAndNeverConsumesANewerOrPreparationDraft() {
        val payload = JSONObject().put("clientMessageId", "original-client").put("goal", "original goal")
        val original = ProjectGoalSubmission("original-client", payload.toString(), " original goal ")
        payload.put("goal", "newer unsent goal")
        assertEquals("original-client", original.input().getString("clientMessageId"))
        assertEquals("original goal", original.input().getString("goal"))
        original.input().put("goal", "another edited copy")
        assertEquals("original goal", original.input().getString("goal"))
        assertFalse(original.clears("newer unsent goal"))
        assertFalse(original.clears("original goal"))
        assertTrue(original.clears(" original goal "))
        assertFalse(ProjectGoalSubmission("preparation-client", payload.toString(), null).clears("newer unsent goal"))
    }
}
