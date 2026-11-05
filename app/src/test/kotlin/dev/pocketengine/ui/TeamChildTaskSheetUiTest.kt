package dev.pocketengine.ui

import dev.pocketengine.EngineApplication
import dev.pocketengine.testutil.ReadyApplicationFixture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Original exact team-child locator and Controller. Task-sheet button visibility is tested in Web. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class, shadows = [ReadyApplicationFixture.NoUnrelatedStartup::class])
@LooperMode(LooperMode.Mode.PAUSED)
class TeamChildTaskSheetUiTest {
    private fun child(kind: String, status: String): UiTask {
        val key = if (kind == "team_research") "researchTaskId" else "assignmentId"
        val identity = JSONObject().put("teamRunId", "original-team-run").put(key, "original-child").put("memberId", "original-member")
        return taskFromJson(JSONObject().put("id", "original-child").put("kind", "assistant").put("goal", "原分工与真实记录").put("status", status).put("actorId", "owner").put("spaceId", "private").put("parentTaskId", "original-parent").put("origin", JSONObject(identity.toString()).put("kind", kind)).put(if (kind == "team_research") "teamResearch" else "teamAssignment", identity))
    }
    @Test fun originalChildReturnsExactLocatorWithoutExecutingOrBorrowingPrivateContinuation() {
        OwnerWebTestFixture().use { f ->
            listOf("team_assignment", "team_review", "team_integration", "team_research").forEachIndexed { index, kind ->
                val task = child(kind, listOf("awaiting_user", "paused", "failed")[index % 3])
                assertTrue(isTeamChild(task)); val original = requireNotNull(teamChildLocator(task))
                assertEquals("original-team-run", original.getString("teamRunId")); assertEquals("original-child", original.getString("assignmentId")); assertEquals("original-member", original.getString("memberId")); assertEquals("original-parent", original.getString("parentTaskId"))
                f.controller.openTeamChild(task)
                assertEquals("original-team-run", f.controller.state.teamRunId)
                val focus = requireNotNull(f.controller.state.teamAssignmentFocus)
                for (key in listOf("teamRunId", "assignmentId", "memberId", "parentTaskId", "childKind")) assertEquals(original.getString(key), focus.getString(key))
                assertEquals(0, f.app.tasks.list().length()); f.controller.closeTeam()
                val changed = taskFromJson(JSONObject(task.raw.toString()).apply { getJSONObject("origin").put("memberId", "different-member") })
                assertNull(teamChildLocator(changed)); f.controller.openTeamChild(changed); assertNull(f.controller.state.teamRunId); assertEquals(0, f.app.tasks.list().length())
            }
        }
    }
}
