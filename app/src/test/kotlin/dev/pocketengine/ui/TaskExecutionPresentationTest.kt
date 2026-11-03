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

    private fun job(state: String, kind: String = "original_launch_record") = JSONObject().put("jobKey", "remote:original-task:old-run:2:original-operation").put("taskId", "original-task").put("runId", "old-run").put("epoch", 2).put("operationId", "original-operation").put("domain", "remote").put("state", state).put("entryKind", kind).put("observingOnly", true).put("executionAuthorized", false).put("statusSource", "original_operation_journal").put("status", "available").put("projectLogCandidate", false)

    @Test fun jobDirectoryUsesOriginalIdentityAndRecordedFactsWithoutInventingLiveControl() {
        val original = job("remote_job_unknown").put("reportedFacts", JSONObject().put("originalReceiptStatus", "unknown").put("reportedPendingApprovalCount", 1))
        val shown = TaskExecutionPresentation.records(page(original), "jobs").single()
        assertEquals("original-task", shown.jobTaskId); assertEquals("old-run", shown.runId); assertEquals(2, shown.jobEpoch); assertEquals("original-operation", shown.jobOperationId)
        assertEquals(TaskExecutionPresentation.Tone.UNCERTAIN, shown.tone); assertNull(shown.projectExecutionId); assertTrue(shown.label.contains("未发起重试"))
        assertTrue(shown.outputNotes.any { it.contains("待审批") }); assertFalse(shown.label.contains("已停止"))
        val stopped = TaskExecutionPresentation.records(page(job("remote_job_receipt").put("reportedFacts", JSONObject().put("originalReceiptStatus", "cancelled"))), "jobs").single()
        assertTrue(stopped.label.contains("资源释放须另行核对")); assertNotEquals(TaskExecutionPresentation.Tone.RETURNED, stopped.tone)
        val intent = TaskExecutionPresentation.records(page(job("receipt", "approval_intent")), "jobs").single()
        assertTrue(intent.label.contains("审批申请")); assertNotEquals(TaskExecutionPresentation.Tone.RUNNING, intent.tone)
    }

    @Test fun jobReadLocatorNeverAcceptsMalformedAuthorityOrLimitedLaunchDetails() {
        val row = job("project_running").put("domain", "project").put("projectLogCandidate", true)
        assertEquals("original-operation", TaskExecutionPresentation.records(page(row), "jobs").single().projectExecutionId)
        row.put("status", "read_limited")
        assertNull(TaskExecutionPresentation.records(page(row), "jobs").single().projectExecutionId)
        row.put("observingOnly", "true")
        assertTrue(TaskExecutionPresentation.records(page(row), "jobs").isEmpty())
    }

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
    @Test fun retainedReturnUpstreamLossAndExactDisplayOmissionsStaySeparateInOriginalRecord() {
        val text = "HEAD" + "🙂界".repeat(4000) + "TAIL"
        val excerpt = dev.pocketengine.features.capabilities.ToolTextDisplay.clip(text, 4096)
        val task = java.util.UUID.randomUUID().toString()
        val run = java.util.UUID.randomUUID().toString()
        val ref = JSONObject().put("taskId", task).put("runId", run).put("epoch", 1).put("tool", "ssh_exec").put("operationId", java.util.UUID.randomUUID().toString()).put("sourceId", "task:$task:run:$run:output:" + java.util.UUID.randomUUID()).put("revision", "sha256:" + "a".repeat(64)).put("captureKind", "NATIVE_RESULT_DATA_JSON").put("retainedRange", JSONObject().put("offset", 0).put("chars", 12).put("unit", "UTF-16").put("utf8Bytes", 20).put("previewChars", 12)).put("retainedNativeDataComplete", true).put("upstreamTruncated", true)
        val capture = dev.pocketengine.features.memory.ToolOutputCapture.recorded("partial", "native_output_upstream_truncated", JSONArray().put(ref)).metadata()
        val receipt = JSONObject().put("status", "unknown").put("outputCapture", capture).put("displayTruncated", true).put("displayOmissions", JSONObject().put("stdout", excerpt.metadata()))
        val before = receipt.toString()
        val row = TaskExecutionPresentation.records(page(operation("unknown", receipt).put("tool", "ssh_exec")), "operations").single()
        assertEquals(TaskExecutionPresentation.Tone.UNCERTAIN, row.tone)
        assertTrue(row.outputNotes.any { it.contains("允许留存的返回数据已完整保存，共 20 字节") })
        assertTrue(row.outputNotes.any { it.contains("上游返回内容已截断") })
        assertTrue(row.outputNotes.any { it.contains("省略 ${excerpt.omittedBytes} 字节") })
        assertTrue(row.outputNotes.any { it.contains("没有按模型 token 数估算") })
        assertEquals(before, receipt.toString())
        assertEquals("utf8_json_bytes", excerpt.metadata().getString("budgetBasis"))
        assertFalse(excerpt.metadata().getBoolean("tokenEstimateUsed"))
    }
    @Test fun malformedOrAbsentOutputMetadataCannotInventRetentionOrExactOmissionCounts() {
        assertTrue(TaskExecutionPresentation.outputNotes(JSONObject()).isEmpty())
        val malformed = JSONObject().put("sourceByteScope", "returned_text_utf8").put("sourceUtf8Bytes", 10).put("headUtf8Bytes", 9).put("tailUtf8Bytes", 9).put("omittedUtf8Bytes", 3).put("nativeOutputChanged", false)
        val receipt = JSONObject().put("displayTruncated", true).put("displayOmissions", JSONObject().put("stdout", malformed)).put("outputCapture", JSONObject().put("status", "stored"))
        val notes = TaskExecutionPresentation.outputNotes(receipt)
        assertTrue(notes.any { it.contains("信息无法核对") })
        assertTrue(notes.any { it.contains("准确遗漏量未记录") })
        assertFalse(notes.any { it.contains("省略 3 字节") || it.contains("已完整保存") })
    }
    @Test fun originalToolEventDisplaysModelOmissionsWithoutReplacingNativeOperationReceipt() {
        val text = "HEAD" + "🙂界".repeat(4000) + "TAIL"
        val excerpt = dev.pocketengine.features.capabilities.ToolTextDisplay.clip(text, 4096)
        val nativeReceipt = JSONObject().put("projectExecution", JSONObject().put("id", "original-operation"))
            .put("executionReceipt", JSONObject().put("stdout", text).put("outputTruncated", false))
        val delivered = JSONObject().put("stdout", excerpt.text).put("displayTruncated", true)
            .put("displayOmissions", JSONObject().put("stdout", excerpt.metadata()))
        val event = JSONObject().put("type", "tool").put("tool", "project_execute").put("status", "unknown")
            .put("runId", "old-run").put("receipt", delivered)
        val original = event.toString()
        val row = TaskExecutionPresentation.records(page(JSONObject().put("position", 9).put("event", event)), "events").single()
        assertEquals(TaskExecutionPresentation.Tone.UNCERTAIN, row.tone)
        assertEquals("old-run", row.runId)
        assertNull(row.projectExecutionId)
        assertTrue(row.outputNotes.any { it.contains("省略 ${excerpt.omittedBytes} 字节") })
        assertTrue(row.outputNotes.any { it.contains("没有按模型 token 数估算") })
        assertEquals(original, event.toString())
        assertTrue(TaskExecutionPresentation.records(page(operation(receipt = nativeReceipt)), "operations").single().outputNotes.isEmpty())
        assertEquals(text, nativeReceipt.getJSONObject("executionReceipt").getString("stdout"))
        val oldFact = excerpt.metadata().apply { remove("budgetBasis"); remove("tokenEstimateUsed") }
        delivered.put("displayOmissions", JSONObject().put("stdout", oldFact))
        val oldNotes = TaskExecutionPresentation.records(page(JSONObject().put("event", event)), "events").single().outputNotes
        assertTrue(oldNotes.any { it.contains("省略 ${excerpt.omittedBytes} 字节") })
        assertFalse(oldNotes.any { it.contains("预算") || it.contains("token") })
        event.put("type", "observation")
        assertTrue(TaskExecutionPresentation.records(page(JSONObject().put("event", event)), "events").single().outputNotes.isEmpty())
        event.put("type", "tool").put("tool", "unrecognized_tool")
        assertTrue(TaskExecutionPresentation.records(page(JSONObject().put("event", event)), "events").single().outputNotes.isEmpty())
    }
    @Test fun tornLogTailIsDistinctFromStorageLossAndOriginalSummary() {
        val receipt = JSONObject().put("runtimeId", "managed_process_v1").put("logIncompleteTail", true).put("outputTruncated", false).put("summaryOutputTruncated", true)
        val notes = TaskExecutionPresentation.outputNotes(receipt, true)
        assertTrue(notes.any { it.contains("末条未写完整") && it.contains("不能证明已达到存储上限") })
        assertTrue(notes.any { it.contains("原回执仅含日志摘要") })
        assertFalse(notes.any { it.contains("未留存部分不能") })
        assertEquals(notes, TaskExecutionPresentation.records(page(operation(receipt = JSONObject().put("executionReceipt", receipt))), "operations").single().outputNotes)
        receipt.put("outputTruncated", true)
        assertTrue(TaskExecutionPresentation.outputNotes(receipt, true).any { it.contains("未留存部分不能由摘要恢复") })
        assertTrue(TaskExecutionPresentation.outputNotes(JSONObject().put("runtimeId", "managed_process_v1").put("outputTruncated", "true"), true).isEmpty())
    }
    private fun trailOperations(count: Int): JSONArray = JSONArray((1..count).map { index ->
        operation().put("operationId", "op-$index").put("createdAt", index.toLong())
            .put("runId", if (index <= 80) "old-run" else "current-run")
    })

    @Test fun browserTrailKeepsTheLatestFortyOfFortyOneWithoutChangingSharedPages() {
        val original = trailOperations(41)
        val before = original.toString()
        val window = TaskExecutionPresentation.recentOperations(original)
        assertEquals((2..41).map { "operation:op-$it" }, window.records.map { it.key })
        assertEquals((39..41).map { "operation:op-$it" }, window.records.takeLast(3).map { it.key })
        assertEquals(1, window.omittedEarlierCount)
        assertEquals("最近 40 条", window.expandLabel)
        assertTrue(window.summary(false)!!.contains("另有 1 条较早调用未在此处展示"))
        assertTrue(window.summary(true)!!.contains("仅显示最近 40 条"))
        assertFalse(window.summary(true)!!.contains("全部"))
        assertEquals((1..40).map { "operation:op-$it" }, TaskExecutionPresentation.records(JSONObject().put("entries", original), "operations").map { it.key })
        assertEquals(before, original.toString())
    }

    @Test fun browserTrailKeepsLatestFortyOfHundredAndDisclosesBothCollapsedAndExpandedOmissions() {
        val original = trailOperations(100)
        val before = original.toString()
        val window = TaskExecutionPresentation.recentOperations(original)
        assertEquals((61..100).map { "operation:op-$it" }, window.records.map { it.key })
        assertEquals((98..100).map { "operation:op-$it" }, window.records.takeLast(3).map { it.key })
        assertEquals(60, window.omittedEarlierCount)
        assertEquals("最近 40 条", window.expandLabel)
        assertEquals("显示最近 3 条原调用；展开可查看最近 40 条。另有 60 条较早调用未在此处展示。", window.summary(false))
        assertEquals("仅显示最近 40 条原调用；另有 60 条较早调用未在此处展示。", window.summary(true))
        assertEquals(before, original.toString())
    }

    @Test fun browserTrailKeepsOriginalRunIdentityOrderAndLatestErrorUnknownAndReturnReceipts() {
        val original = trailOperations(100)
        // Journal order remains authoritative even when a later run has older timestamps.
        for (index in 80 until 100) original.getJSONObject(index).put("createdAt", 1)
        original.getJSONObject(97).put("receipt", JSONObject().put("ok", false).put("status", "failed"))
        original.getJSONObject(98).put("state", "unknown").put("receipt", JSONObject().put("accepted", JSONObject.NULL))
        val before = original.toString()
        val records = TaskExecutionPresentation.recentOperations(original).records
        assertEquals((61..100).map { "operation:op-$it" }, records.map { it.key })
        assertEquals(List(20) { "old-run" } + List(20) { "current-run" }, records.map { it.runId })
        assertEquals(listOf(TaskExecutionPresentation.Tone.ERROR, TaskExecutionPresentation.Tone.UNCERTAIN, TaskExecutionPresentation.Tone.RETURNED), records.takeLast(3).map { it.tone })
        assertEquals(original.getJSONObject(98).getJSONObject("receipt").toString(), records[38].receipt)
        assertEquals(before, original.toString())
    }

    @Test fun browserTrailZeroThreeAndFortyBoundariesDoNotClaimTruncation() {
        for (count in listOf(0, 3, 40)) {
            val original = trailOperations(count)
            val window = TaskExecutionPresentation.recentOperations(original)
            assertEquals(count, window.records.size)
            assertEquals(0, window.omittedEarlierCount)
            assertEquals("全部 $count 条", window.expandLabel)
            assertEquals((1..count).map { "operation:op-$it" }, window.records.map { it.key })
            assertNull(window.summary(true))
            if (count <= 3) assertNull(window.summary(false))
            else assertEquals("显示最近 3 条原调用；展开可回查更早记录。", window.summary(false))
            assertEquals(count, original.length())
        }
        assertTrue(TaskExecutionPresentation.recentOperations(null).records.isEmpty())
        assertNull(TaskExecutionPresentation.recentOperations(null).summary(false))
    }

}
