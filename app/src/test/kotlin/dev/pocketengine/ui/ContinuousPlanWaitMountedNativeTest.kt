package dev.pocketengine.ui

import android.content.Intent
import android.os.BatteryManager
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.AutomationPlanHost
import dev.pocketengine.features.FeatureContext
import dev.pocketengine.features.automation.AutomationModule
import dev.pocketengine.store.TaskStore
import dev.pocketengine.testutil.ReadyApplicationFixture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Actual Android conditions and original alarm/task/result path, reached from the Web bridge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class,
    shadows = [ReadyApplicationFixture.NoUnrelatedStartup::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ContinuousPlanWaitMountedNativeTest {
    private fun j(vararg values: Any): JSONObject { val out = JSONObject(); for (i in values.indices step 2) out.put(values[i] as String, values[i + 1]); return out }
    private fun scope() = j("ownerId", "owner", "spaceId", "private")
    private fun battery(ui: ContinuousPlanMountedNativeTest.Screen, status: Int, level: Int = 80) {
        ui.app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PRESENT, true)
            .putExtra(BatteryManager.EXTRA_LEVEL, level).putExtra(BatteryManager.EXTRA_SCALE, 100).putExtra(BatteryManager.EXTRA_STATUS, status))
    }
    private fun module(ui: ContinuousPlanMountedNativeTest.Screen) = ui.app.features.module("automation") as AutomationModule
    private fun firstTask(ui: ContinuousPlanMountedNativeTest.Screen, id: String) = ui.app.tasks.get(ui.plan(id).getJSONArray("steps").getJSONObject(0).getString("taskId"), false)
    private fun decision(ui: ContinuousPlanMountedNativeTest.Screen, id: String) = ui.read(id).getJSONObject("plan").getJSONObject("runtimeDecision")
    private fun create(ui: ContinuousPlanMountedNativeTest.Screen, title: String, path: String = "reports/source.md", heading: String = "# Source", automatic: Boolean = true, windowMillis: Long = 3_600_000L): String {
        val id = ui.create(title, listOf(ui.stepDraft(title, path, heading)))
        if (windowMillis == 3_600_000L) ui.approve(id, if (automatic) "automatic" else "manual", requiresCharging = true, admissions = 1)
        else {
            // Arrange a real short-lived original admission. The public editor deliberately accepts hours,
            // so this expiry fixture uses the unchanged native owner API only for its two-second window.
            val current = ui.plan(id)
            ui.app.dispatchOwnerAction("automationPlan", j("command", "approve", "payload", j("id", id, "revision", current.getLong("revision"),
                "settings", j("mode", if (automatic) "automatic" else "manual", "maxTaskAdmissions", 1, "modelObservationConsent", true,
                    "allowPrivateArtifactFreeze", true, "requiresNetwork", false, "requiresCharging", true, "expiresAt", System.currentTimeMillis() + windowMillis))))
        }
        return id
    }
    private fun pendingOriginal(ui: ContinuousPlanMountedNativeTest.Screen, id: String): Pair<JSONObject, JSONObject> {
        val current = ui.plan(id)
        module(ui).startPlanExecution(scope(), id, current.getLong("revision"))
        val ready = ui.plan(id); val step = ready.getJSONArray("steps").getJSONObject(0)
        val prepared = module(ui).preparePlanTask(scope(), id, ready.getLong("revision"), step.getString("id"))
        val request = module(ui).planTaskRequest(scope(), id, step.getString("id"), prepared.getString("occurrenceId"))
        return request to ui.app.tasks.createReminderTask(request, "capabilities")
    }

    @Test fun nativeReviewShowsExactProjectAndStandardThenConditionRecoveryStartsOnlyTheSelectedGoal() {
        ContinuousPlanMountedNativeTest.Screen().use { ui ->
            battery(ui, BatteryManager.BATTERY_STATUS_UNKNOWN, 120)
            val review = ui.reviewCreate("充电可用后核验指定项目原文件", listOf(ui.stepDraft("核验并冻结源文件", "reports/source.md", "# Source")))
            val standard = review.getJSONObject("plan").getJSONArray("steps").getJSONObject(0).getJSONObject("verification")
            assertEquals(ui.projectId, standard.getString("projectId")); assertEquals("reports/source.md", standard.getString("path"))
            assertEquals("# Source", standard.getString("requiredHeading")); assertEquals(16L, standard.getLong("minBytes"))
            assertEquals(0, ui.app.tasks.list().length()); assertEquals(0, ui.modelCalls.get())
            val selected = ui.confirm(review).getJSONObject("reading").getJSONObject("plan").getString("id")
            ui.approve(selected, requiresCharging = true, admissions = 1)
            assertEquals("conditions_blocked", decision(ui, selected).getString("reason"))
            assertTrue(decision(ui, selected).getJSONObject("conditionStatus").getJSONArray("blockers").toString().contains("charging_unavailable"))
            assertEquals(0, ui.app.tasks.list().length()); assertEquals(0, ui.modelCalls.get())
            val other = create(ui, "另一个已审阅的等待目标", "reports/delivery.md", "# Delivery")
            battery(ui, BatteryManager.BATTERY_STATUS_DISCHARGING)
            assertTrue(decision(ui, selected).getJSONObject("conditionStatus").getJSONArray("blockers").toString().contains("charging_required"))
            ui.save("wait-01-real-battery-blocks-before-task")
            battery(ui, BatteryManager.BATTERY_STATUS_CHARGING)
            assertTrue(decision(ui, selected).getBoolean("eligible"))
            ui.inspect(selected)
            assertEquals("Reading or inspecting must not start any goal", 0, ui.app.tasks.list().length())
            ui.action(selected, "refresh"); ui.awaitState(selected, "completed")
            assertEquals(1, ui.app.tasks.list().length())
            assertEquals("", ui.plan(other).getJSONArray("steps").getJSONObject(0).optString("taskId"))
            assertTrue(ui.app.tasks.automationArtifactVerified(firstTask(ui, selected).getString("id")))
            ui.save("wait-02-only-selected-goal-continues")
            ui.action(other, "cancel"); ui.alarm()
            assertEquals(1, ui.app.tasks.list().length()); assertEquals("cancelled", ui.plan(other).getString("state"))
        }
    }

    @Test fun reopenedNativeBooksAndSqliteQueryFirstRetainOriginalOccurrenceBeforeAnyResume() {
        ContinuousPlanMountedNativeTest.Screen().use { ui ->
            battery(ui, BatteryManager.BATTERY_STATUS_CHARGING)
            val id = create(ui, "恢复同一次登记的原任务，充电恢复后继续", automatic = false)
            val (request, original) = pendingOriginal(ui, id)
            assertEquals("", original.optString("runId")); assertEquals(1, ui.app.tasks.list().length()); assertEquals(0, ui.modelCalls.get())
            battery(ui, BatteryManager.BATTERY_STATUS_DISCHARGING)
            // New original SQLite/Book/Resolver objects, same persisted files; no process-kill claim.
            val reopened = TaskStore(ui.app)
            val reader = AutomationPlanHost(reopened)
            val recovered = AutomationModule(FeatureContext(ui.app, null, null, { _ -> }), reader::resolveEvidence, reader)
            try {
                recovered.refreshPlanTasks(scope(), id)
                assertEquals(original.getString("id"), recovered.handle("getPlan", j("scope", scope(), "id", id)).getJSONObject("plan").getJSONArray("steps").getJSONObject(0).getString("taskId"))
                assertEquals(0, ui.modelCalls.get())
            } finally {
                recovered.close()
                (TaskStore::class.java.getDeclaredField("db").apply { isAccessible = true }.get(reopened) as android.database.sqlite.SQLiteDatabase).close()
            }
            ui.inspect(id); ui.action(id, "refresh")
            assertEquals(0, ui.modelCalls.get()); assertEquals(1, ui.app.tasks.list().length()); assertEquals("", firstTask(ui, id).optString("runId"))
            assertEquals(1, ui.plan(id).getJSONObject("execution").getInt("admissions"))
            ui.save("wait-03-reopened-original-held-by-current-condition")
            battery(ui, BatteryManager.BATTERY_STATUS_CHARGING)
            ui.read(id); assertEquals("", firstTask(ui, id).optString("runId")); assertEquals(0, ui.modelCalls.get())
            ui.action(id, "refresh"); ui.awaitState(id, "completed")
            val done = firstTask(ui, id)
            assertEquals(original.getString("id"), done.getString("id"))
            assertEquals(request.getString("occurrenceId"), done.getJSONObject("origin").getString("occurrenceId"))
            assertEquals(1, done.getInt("coordinatorEpoch")); assertEquals(1, done.getJSONArray("operations").length())
            assertEquals(1, ui.plan(id).getJSONObject("execution").getInt("admissions")); assertEquals(1, ui.app.tasks.list().length())
            assertTrue(ui.app.tasks.automationArtifactVerified(done.getString("id")))
            ui.save("wait-04-same-occurrence-result-after-reopen")
        }
    }

    @Test fun expiryAndFreshReviewNeverExtendTheOldOccurrenceAndCancelNeverRetriggersIt() {
        ContinuousPlanMountedNativeTest.Screen().use { ui ->
            battery(ui, BatteryManager.BATTERY_STATUS_CHARGING)
            val id = create(ui, "过期原触发只核对，不恢复旧许可", automatic = false, windowMillis = 2_000L)
            val (request, original) = pendingOriginal(ui, id)
            val deadline = request.getLong("validUntil")
            assertTrue("Original intent must exist before its deadline", System.currentTimeMillis() < deadline)
            Thread.sleep((deadline - System.currentTimeMillis() + 25).coerceAtLeast(0))
            assertTrue("Original wall-clock deadline has elapsed", System.currentTimeMillis() >= deadline)
            ui.inspect(id); assertEquals("approval_expired", decision(ui, id).getString("reason")); assertEquals(0, ui.modelCalls.get())
            ui.action(id, "pause"); ui.approve(id, requiresCharging = true, admissions = 2); ui.action(id, "resume"); ui.action(id, "refresh")
            assertEquals(0, ui.modelCalls.get()); assertEquals(1, ui.app.tasks.list().length())
            assertEquals(original.getString("id"), firstTask(ui, id).getString("id")); assertEquals("", firstTask(ui, id).optString("runId"))
            assertEquals(deadline, ui.plan(id).getJSONArray("steps").getJSONObject(0).getJSONObject("taskAdmission").getLong("validUntil"))
            assertEquals("approval_expired", decision(ui, id).getString("reason"))
            ui.save("wait-05-expired-occurrence-stays-blocked-after-new-review")
            ui.action(id, "cancel"); ui.alarm()
            assertEquals("cancelled", ui.plan(id).getString("state")); assertEquals(1, ui.app.tasks.list().length()); assertEquals(0, ui.modelCalls.get())
        }
    }
}
