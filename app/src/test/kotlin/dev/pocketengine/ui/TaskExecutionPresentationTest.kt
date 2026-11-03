package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual bounded-owner projection semantics, not a timer-driven demo or native permission test. */
class TaskExecutionPresentationTest {
    private fun task(status: String = "running") = JSONObject().put("id", "original-task").put("runId", "run-a").put("status", status).put("events", JSONArray()).put("operations", JSONArray())
    private fun operation(state: String = "receipt", receipt: JSONObject? = JSONObject().put("ok", true)) = JSONObject().put("position", 7).put("operationId", "original-operation").put("runId", "run-a").put("tool", "project_execute").put("state", state).put("createdAt", 1234L).put("status", "available").put("arguments", JSONObject().put("planRef", "original-plan")).put("receipt", receipt ?: JSONObject.NULL)
    private fun page(vararg entries: JSONObject) = JSONObject().put("entries", JSONArray(entries.toList()))

    @Test fun unknownOperationOverridesSuperficiallyCompletedTask() {
        val source = task("completed").put("operations", JSONArray().put(operation("unknown", JSONObject().put("accepted", JSONObject.NULL))))
        val current = TaskExecutionPresentation.current(source, 100)
        assertEquals(TaskExecutionPresentation.Target.RECONCILIATION, current.target)
        assertEquals(TaskExecutionPresentation.Tone.UNCERTAIN, current.tone)
        assertFalse(current.title.contains("已完成"))
    }
    @Test fun nativeKnownOwnedRunningIsNotAnUnknownEffect() {
        val source = task().put("historyHasUnconfirmedOperations", false).put("hasUnresolvedOperations", true).put("operations", JSONArray().put(operation("project_running", JSONObject().put("code", "project_running").put("terminal", false))))
        val current = TaskExecutionPresentation.current(source, 100)
        assertEquals(TaskExecutionPresentation.Tone.RUNNING, current.tone)
        assertEquals(TaskExecutionPresentation.Target.NONE, current.target)
        val row = TaskExecutionPresentation.records(page(source.getJSONArray("operations").getJSONObject(0)), "operations").single()
        assertTrue(row.label.contains("尚无终态回执"))
        assertNotEquals(TaskExecutionPresentation.Tone.RETURNED, row.tone)
    }
    @Test fun unknownOutsideCurrentBoundedWindowRemainsVisible() {
        val source = task("completed").put("historyHasUnconfirmedOperations", true)
        assertEquals(TaskExecutionPresentation.Target.RECONCILIATION, TaskExecutionPresentation.current(source, 100).target)
    }
    @Test fun malformedNarrowFlagCannotHideLegacyUnresolvedMarker() {
        val source = task("completed").put("historyHasUnconfirmedOperations", "false").put("hasUnresolvedOperations", true)
        assertEquals(TaskExecutionPresentation.Tone.UNCERTAIN, TaskExecutionPresentation.current(source, 100).tone)
    }
    @Test fun pendingApprovalUsesOriginalScopeAndDoesNotBecomeAReceipt() {
        val source = task().put("approvals", JSONArray().put(JSONObject().put("state", "pending").put("expiresAt", 101L)))
        assertEquals(TaskExecutionPresentation.Target.APPROVAL, TaskExecutionPresentation.current(source, 100).target)
        assertNotEquals(TaskExecutionPresentation.Target.APPROVAL, TaskExecutionPresentation.current(source, 102).target)
        val row = TaskExecutionPresentation.records(page(operation(receipt = JSONObject().put("ok", false).put("accepted", false).put("code", "approval_required"))), "operations").single()
        assertEquals(TaskExecutionPresentation.Tone.WAITING, row.tone)
    }
    @Test fun requestingStopDoesNotClaimTerminationOrRelease() {
        val current = TaskExecutionPresentation.current(task().put("cancelRequested", true), 100)
        assertTrue(current.title.contains("等待确认"))
        assertTrue(current.detail.contains("尚不能"))
        assertNotEquals(TaskExecutionPresentation.Tone.STOPPED, current.tone)
        val drain = TaskExecutionPresentation.current(task("awaiting_user").put("evidence", JSONObject().put("projectServiceDrainPending", true)), 100)
        assertEquals(TaskExecutionPresentation.Target.PROCESS, drain.target)
        assertTrue(drain.detail.contains("资源"))
    }
    @Test fun nonzeroExitIsNotACompletedGreenStepEvenWhenOuterOkIsTrue() {
        val row = TaskExecutionPresentation.records(page(operation(receipt = JSONObject().put("ok", true).put("exitCode", 3))), "operations").single()
        assertEquals(TaskExecutionPresentation.Tone.ERROR, row.tone)
        assertTrue(row.label.contains("不等于未执行"))
    }
    @Test fun receiptAcceptedDoesNotClaimBusinessSuccessOrInventDuration() {
        val row = TaskExecutionPresentation.records(page(operation(receipt = JSONObject().put("ok", true).put("accepted", true).put("stdout", "actual response"))), "operations").single()
        assertEquals(TaskExecutionPresentation.Tone.RETURNED, row.tone)
        assertTrue(row.label.contains("目标仍需核对"))
        assertEquals(1234L, row.at)
        assertTrue(row.receipt.contains("actual response"))
    }
    @Test fun limitedOriginalBodyRetainsStableIdentityAndDoesNotInventText() {
        val original = operation("project_running", null).put("status", "read_limited").put("bodyBytes", 100000)
        original.remove("arguments")
        val row = TaskExecutionPresentation.records(page(original), "operations").single()
        assertTrue(row.limited)
        assertEquals("operation:original-operation", row.key)
        assertEquals("", row.receipt)
        assertEquals(100000L, row.bodyBytes)
        assertEquals(TaskExecutionPresentation.Tone.RUNNING, row.tone)
        assertEquals("original-operation", row.projectExecutionId)
        assertEquals("run-a", row.runId)
    }
    @Test fun ownerReconciliationIsNotRelabelledAsAnAutomaticExecutionReceipt() {
        val source = operation("resolved", JSONObject().put("source", "owner_reconciliation").put("outcome", "not_occurred"))
        val row = TaskExecutionPresentation.records(page(source), "operations").single()
        assertEquals(TaskExecutionPresentation.Tone.NEUTRAL, row.tone)
        assertTrue(row.label.contains("主人已核对未发生"))
        assertTrue(row.label.contains("不会自动重试"))
    }
    @Test fun actualLatestToolEventDrivesCurrentStepWithoutDemoTimeline() {
        val source = task().put("events", JSONArray().put(JSONObject().put("type", "tool").put("status", "running").put("message", "正在读原文件").put("at", 9L)))
        assertEquals("正在读原文件", TaskExecutionPresentation.current(source, 100).detail)
        assertEquals(TaskExecutionPresentation.current(source, 100), TaskExecutionPresentation.current(source, 999999))
    }
    @Test fun resultAndFailureHaveDifferentNextStepsAndOldRunIsNotRebound() {
        assertEquals(TaskExecutionPresentation.Target.RESULT, TaskExecutionPresentation.current(task("needs_confirmation"), 100).target)
        assertEquals(TaskExecutionPresentation.Target.PROCESS, TaskExecutionPresentation.current(task("failed"), 100).target)
        val original = operation().put("runId", "old-run")
        assertEquals("old-run", TaskExecutionPresentation.records(page(original), "operations").single().runId)
    }
    @Test fun displayWindowAndUnicodeExcerptAreBoundedWithoutChangingOriginalAudit() {
        val source = JSONObject().put("entries", JSONArray((0..50).map { operation().put("operationId", "op-$it") }))
        assertEquals(40, TaskExecutionPresentation.records(source, "operations").size)
        assertEquals(51, source.getJSONArray("entries").length())
        val text = "🙂".repeat(30)
        val shown = TaskExecutionPresentation.excerpt(text, 4)
        assertTrue(shown.startsWith("🙂".repeat(4)))
        assertFalse(Character.isHighSurrogate(shown[7]))
        assertEquals(60, text.length)
    }
    @Test fun originalProjectLocatorCannotBeInventedFromReceiptTitlePositionOrAnotherTool() {
        val original = operation("unknown", null).put("status", "read_limited").put("runId", "old-run")
        assertEquals("original-operation", TaskExecutionPresentation.projectExecutionObservationId(original))
        assertEquals("old-run", TaskExecutionPresentation.records(page(original), "operations").single().runId)
        for (tool in listOf("project_status", "project_process_input", "remote_work_start", "media_generate", "project_execute "))
            assertNull(TaskExecutionPresentation.projectExecutionObservationId(JSONObject(original.toString()).put("tool", tool)))
        for (id in listOf("", "../original-operation", "a".repeat(81)))
            assertNull(TaskExecutionPresentation.projectExecutionObservationId(JSONObject(original.toString()).put("operationId", id)))
        original.remove("operationId")
        assertNull(TaskExecutionPresentation.projectExecutionObservationId(original))
    }
}
