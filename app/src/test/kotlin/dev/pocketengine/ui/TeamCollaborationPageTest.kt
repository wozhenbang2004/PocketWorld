package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TeamCollaborationPageTest {
    private fun run(id: String = "run-a", revision: Long = 12) = JSONObject().put("id", id).put("generation", 3).put("revision", revision).put("status", "paused").put("members", JSONArray().put(JSONObject().put("id", "a").put("generation", 6)))
    private fun definition() = JSONObject().put("id", "team-a").put("members", JSONArray().put(JSONObject().put("id", "a").put("runner", "model")).put(JSONObject().put("id", "b").put("runner", "codex_app_server")))

    @Test fun newerRevisionAndReplacementRunInvalidateAReviewedAction() {
        val reviewed = teamCollaborationStamp(run())
        assertThrows(IllegalArgumentException::class.java) { reviewedTeamCollaborationAction(run(revision = 13), reviewed, "resume", "a") }
        assertThrows(IllegalArgumentException::class.java) { reviewedTeamCollaborationAction(run(id = "run-b"), reviewed, "resume", "a") }
        assertThrows(IllegalArgumentException::class.java) { reviewedTeamCollaborationAction(run().put("generation", 4), reviewed, "resume", "a") }
    }

    @Test fun continuationUsesCurrentHostSnapshotAndRejectsAForeignMember() {
        val current = run()
        val payload = reviewedTeamCollaborationAction(current, teamCollaborationStamp(current), "continueMessages", "a")
        assertEquals("run-a", payload.getString("runId"))
        assertEquals(12L, payload.getLong("expectedRevision"))
        assertEquals(3, payload.getInt("expectedGeneration"))
        assertEquals(6, payload.getInt("expectedMemberGeneration"))
        assertThrows(IllegalStateException::class.java) { reviewedTeamCollaborationAction(current, teamCollaborationStamp(current), "continueMessages", "foreign") }
        assertThrows(IllegalArgumentException::class.java) { reviewedTeamCollaborationAction(current, teamCollaborationStamp(current), "continueMessages", null) }
    }

    @Test fun unrelatedAndMalformedReceiptsCannotClearAnOwnerDraft() {
        val receipt = JSONObject().put("requestId", "request-a").put("command", "feedback").put("runId", "run-a").put("ok", true)
        assertTrue(teamCollaborationResultMatches(receipt, "request-a", "feedback", "run-a"))
        assertFalse(teamCollaborationResultMatches(receipt, "request-b", "feedback", "run-a"))
        assertFalse(teamCollaborationResultMatches(receipt, "request-a", "resume", "run-a"))
        assertFalse(teamCollaborationResultMatches(receipt, "request-a", "feedback", "run-b"))
        assertFalse(teamCollaborationResultMatches(JSONObject(receipt.toString()).put("ok", "true"), "request-a", "feedback", "run-a"))
    }

    @Test fun planPayloadContainsOnlyReviewedAssignmentsAndDetectsAChangedDefinition() {
        val source = definition()
        val original = source.toString()
        val drafts = listOf(TeamAssignmentDraft("a", "取得来源", "保留依据", emptySet()), TeamAssignmentDraft("b", "核对来源", "", setOf("a")))
        val payload = reviewedTeamPlanPayload(source, original, drafts)
        assertEquals(original, payload.getString("expectedDefinition"))
        assertFalse(payload.getJSONArray("assignments").getJSONObject(0).has("runner"))
        assertEquals("codex_app_server", source.getJSONArray("members").getJSONObject(1).getString("runner"))
        val replacement = JSONObject(original)
        replacement.getJSONArray("members").getJSONObject(1).put("runner", "model")
        assertThrows(IllegalArgumentException::class.java) { reviewedTeamPlanPayload(replacement, original, drafts) }
    }
}
