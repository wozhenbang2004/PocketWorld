package dev.pocketengine.ui

import dev.pocketengine.EngineApplication
import dev.pocketengine.testutil.ReadyApplicationFixture
import java.util.concurrent.CountDownLatch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

private typealias DagScreen = ContinuousPlanMountedNativeTest.Screen

/** Real Web bridge creation/editing and original multi-parent Runner results, without supplied success DTOs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class,
    shadows = [ReadyApplicationFixture.NoUnrelatedStartup::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ContinuousPlanDagMountedNativeTest {
    private val goals = listOf("准备输入甲", "准备输入乙", "合并两份已核验输入", "整理最终归档", "前置失败后保留恢复说明")
    private val paths = listOf("dag/a.md", "dag/b.md", "dag/join.md", "dag/final.md", "dag/recovery.md")
    private val headings = listOf("# Input A", "# Input B", "# Join", "# Final", "# Recovery")
    private val files get() = paths.mapIndexed { index, path -> path to headings[index] + "\nActual immutable fixture file for the bridge DAG journey.\n" }
    private fun draft(ui: DagScreen, index: Int, dependencies: Map<Int, String>) = ui.stepDraft(goals[index], paths[index], headings[index], dependencies)
    private fun step(ui: DagScreen, index: Int) = ui.plan().getJSONArray("steps").getJSONObject(index)
    private fun refs(step: JSONObject): Map<String, String> {
        val values = step.getJSONArray("dependencies")
        return (0 until values.length()).associate { values.getJSONObject(it).getString("stepId") to values.getJSONObject(it).getString("outcome") }
    }
    private fun assertMachineOriginal(ui: DagScreen, index: Int) {
        val task = ui.app.tasks.get(step(ui, index).getString("taskId"), false)
        assertEquals("completed", task.getString("status")); assertTrue(ui.app.tasks.automationArtifactVerified(task.getString("id")))
        assertEquals(1, task.getJSONArray("operations").length())
        val events = task.getJSONArray("events")
        for (at in 0 until events.length()) assertNotEquals("user_verification", events.getJSONObject(at).optString("type"))
    }

    @Test fun nativeDagWaitsForBothParentsAndPausedEditAddsAnEdgeWithoutLosingOriginalHistory() {
        val releaseB = CountDownLatch(1)
        DagScreen(files, holds = mapOf(paths[1] to releaseB)).use { ui ->
            val id = ui.create("等待两份真实输入后汇合，并按修改后的依赖归档", listOf(
                draft(ui, 0, emptyMap()), draft(ui, 1, emptyMap()),
                draft(ui, 2, mapOf(0 to "completed", 1 to "completed")),
                draft(ui, 3, mapOf(2 to "completed")), draft(ui, 4, mapOf(0 to "completed", 1 to "failed"))
            ))
            ui.approve(id, "manual")
            assertEquals(0, ui.app.tasks.list().length())
            val ids = (0..4).map { step(ui, it).getString("id") }
            assertEquals(mapOf(ids[0] to "completed", ids[1] to "completed"), refs(step(ui, 2)))
            ui.action(id, "start"); ui.awaitStep(id, 0, "completed"); ui.alarm()
            ui.until("original model request for input B") { paths[1] in ui.requestedPaths }
            val first = ui.app.tasks.get(step(ui, 0).getString("taskId"), false)
            val b = ui.app.tasks.get(step(ui, 1).getString("taskId"), false)
            assertFalse(paths[2] in ui.requestedPaths); assertEquals("", step(ui, 2).optString("taskId")); assertEquals(2, ui.app.tasks.list().length())

            // The exact review from an earlier Web reading cannot borrow a later plan revision.
            val stalePause = ui.reviewAction(id, "pause")
            val beforeStale = ui.plan()
            ui.app.dispatchOwnerAction("automationPlan", JSONObject().put("command", "resume").put("payload", JSONObject()
                .put("id", id).put("revision", beforeStale.getLong("revision"))))
            val rejected = ui.reply("plans.confirm", JSONObject().put("reviewHandle", stalePause.getString("reviewHandle")))
            assertFalse(rejected.getBoolean("ok")); assertTrue(rejected.getJSONObject("error").getString("message").contains("计划或步骤已改变"))
            assertEquals("active", ui.plan().getJSONObject("execution").getString("state")); assertEquals(2, ui.app.tasks.list().length())
            ui.action(id, "pause")
            assertEquals("paused", ui.plan().getJSONObject("execution").getString("state"))
            val originalJoin = JSONObject(step(ui, 2).toString())
            val originalFinal = JSONObject(step(ui, 3).toString())
            val unchanged = ui.read(id)
            val unchangedReply = ui.reply("plans.reviewStep", JSONObject().put("viewHandle", unchanged.getString("viewHandle"))
                .put("stepId", originalFinal.getString("id")).put("draft", draft(ui, 3, mapOf(2 to "completed")))
                .put("delayMinutes", 0).put("timingEdited", false))
            assertFalse(unchangedReply.getBoolean("ok")); assertTrue(unchangedReply.getJSONObject("error").getString("message").contains("没有修改"))
            assertEquals(originalFinal.toString(), step(ui, 3).toString())
            ui.editStep(id, 3, ui.stepDraft("整理已核验版本并归档两份输入的汇合结果", paths[3], headings[3], mapOf(0 to "completed", 2 to "completed")))
            assertEquals(refs(originalJoin), refs(step(ui, 2))); assertEquals(originalJoin.getLong("dueAt"), step(ui, 2).getLong("dueAt"))
            assertEquals(mapOf(ids[0] to "completed", ids[2] to "completed"), refs(step(ui, 3)))
            assertEquals(originalFinal.getLong("dueAt"), step(ui, 3).getLong("dueAt"))
            assertEquals(first.getString("id"), step(ui, 0).getString("taskId"))
            assertEquals(first.getString("runId"), ui.app.tasks.get(first.getString("id"), false).getString("runId"))
            assertEquals(b.getString("id"), step(ui, 1).getString("taskId"))
            assertFalse(ui.read(id).getJSONObject("plan").getBoolean("approvalValid"))
            ui.approve(id, "manual"); ui.action(id, "resume"); releaseB.countDown(); ui.awaitStep(id, 1, "completed")
            assertEquals(b.getString("runId"), ui.app.tasks.get(b.getString("id"), false).getString("runId"))
            assertEquals(b.getInt("coordinatorEpoch"), ui.app.tasks.get(b.getString("id"), false).getInt("coordinatorEpoch"))
            ui.alarm(); ui.awaitStep(id, 2, "completed"); ui.alarm(); ui.awaitStep(id, 3, "completed"); ui.awaitState(id, "completed")
            ui.save("dag-01-complete-original-multi-parent-goal")
            assertEquals("skipped", step(ui, 4).getString("status")); assertEquals(4, ui.app.tasks.list().length())
            for (index in 0..3) assertMachineOriginal(ui, index)
            ui.openTask(step(ui, 3).getString("taskId")); assertEquals(step(ui, 3).getString("taskId"), ui.controller.state.requestedTaskId)
        }
    }

    @Test fun actualFailedParentSelectsTheReviewedMultiParentRecoveryAndNeverTheSuccessJoin() {
        DagScreen(files, failPaths = setOf(paths[1])).use { ui ->
            val id = ui.create("两份输入中一份真实失败时，保留原失败并形成恢复说明", listOf(
                draft(ui, 0, emptyMap()), draft(ui, 1, emptyMap()), draft(ui, 2, mapOf(0 to "completed", 1 to "completed")),
                draft(ui, 4, mapOf(0 to "completed", 1 to "failed"))
            ))
            ui.approve(id); ui.awaitStep(id, 0, "completed"); ui.alarm(); ui.awaitStep(id, 1, "failed")
            assertTrue(step(ui, 1).getJSONObject("taskObservation").getBoolean("terminalVerified"))
            assertFalse(ui.app.tasks.hasUnresolved(step(ui, 1).getString("taskId")))
            assertEquals("", step(ui, 2).optString("taskId"))
            ui.alarm(); ui.awaitStep(id, 3, "completed"); ui.awaitState(id, "finished_with_failures")
            ui.save("dag-02-known-failure-multi-parent-recovery")
            assertEquals("skipped", step(ui, 2).getString("status")); assertFalse(paths[2] in ui.requestedPaths)
            assertEquals(3, ui.app.tasks.list().length()); assertEquals("failed", ui.app.tasks.get(step(ui, 1).getString("taskId"), false).getString("status"))
            assertMachineOriginal(ui, 0); assertMachineOriginal(ui, 3); assertEquals(2, refs(step(ui, 3)).size)
            ui.openTask(step(ui, 3).getString("taskId")); assertEquals(step(ui, 3).getString("taskId"), ui.controller.state.requestedTaskId)
        }
    }
}
