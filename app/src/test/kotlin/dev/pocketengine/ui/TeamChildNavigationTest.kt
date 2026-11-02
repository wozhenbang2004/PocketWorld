package dev.pocketengine.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TeamChildNavigationTest {
    private fun child(kind: String): UiTask {
        val research = kind == "team_research"
        val identity = JSONObject().put("teamRunId", "team-run").put(if (research) "researchTaskId" else "assignmentId", "child-task").put("memberId", "member-a")
        val origin = JSONObject(identity.toString()).put("kind", kind)
        return taskFromJson(JSONObject().put("id", "child-task").put("kind", "assistant").put("actorId", "owner").put("spaceId", "private").put("parentTaskId", "team-task").put("origin", origin).put(if (research) "teamResearch" else "teamAssignment", identity))
    }
    @Test fun actualChildOriginsReachTheOriginalTeamInsteadOfGenericAssistantRoutes() {
        listOf("team_assignment", "team_review", "team_integration", "team_research").forEach { kind ->
            assertEquals(kind, listOf("team-run"), teamRunReferences(child(kind)))
        }
    }
    @Test fun mismatchedOrIncompleteSourceNeverFallsBackToPrivateContinuation() {
        val task = child("team_assignment")
        task.raw.getJSONObject("origin").put("teamRunId", "another-team")
        assertTrue(isTeamChild(task))
        assertNull(teamChildLocator(task))
        assertTrue(teamRunReferences(task).isEmpty())
        val research = child("team_research")
        research.raw.getJSONObject("teamResearch").put("memberId", "replacement")
        assertNull(teamChildLocator(research))
        val unknown = child("future_team_child")
        assertFalse(isTeamChild(unknown))
        assertNull(teamChildLocator(unknown))
    }
    @Test fun routeCarriesExactChildAndMemberButIsNeverAnExecutionGrant() {
        listOf("team_assignment", "team_review", "team_integration", "team_research").forEach { kind ->
            val route = teamChildLocator(child(kind))!!
            assertEquals("child-task", route.getString("assignmentId"))
            assertEquals("member-a", route.getString("memberId"))
            assertEquals("team-task", route.getString("parentTaskId"))
            assertEquals(kind, route.getString("childKind"))
            assertFalse(route.has("approved"))
            assertFalse(route.has("resume"))
        }
    }
}
