package dev.pocketengine.ui.web

import dev.pocketengine.ui.taskFromJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PocketWebTraceTest {
    private class Read(val done: (JSONObject?, String?) -> Unit) { var cancelled = false }
    private class Fixture {
        var visible = true
        var owner = 1L
        var sourceRevision = 0L
        val task = taskFromJson(JSONObject().put("id", "original-task"))
        val reads = mutableListOf<Read>()
        val details = mutableListOf<Read>()
        val trace = PocketWebTrace({ visible }, { Pair(Triple(owner, 0, 0L), 0L) },
            { id -> task.takeIf { it.id == id } }, { _, _, _, _, done ->
                val read = Read(done); reads.add(read)
                val cancel: () -> Unit = { read.cancelled = true }
                cancel
            }, { _, _, _, done ->
                val read = Read(done); details.add(read)
                val cancel: () -> Unit = { read.cancelled = true }
                cancel
            }, { sourceRevision })
        fun receipt(run: String = "original-run") = JSONObject()
            .put("format", "pocketworld.execution-trace.v1").put("taskId", task.id)
            .put("runId", run).put("readOnly", true).put("executionAuthorized", false)
        fun detailReceipt(node: String = "model:original-call") = receipt()
            .put("format", "pocketworld.execution-trace-detail.v1").put("epoch", 7)
            .put("sourceState", "current").put("node", JSONObject().put("id", node))
    }

    @Test fun switchingRunsSettlesTheOldReadAndDoesNotDisplayItsLateResult() {
        val f = Fixture()
        val old = mutableListOf<Pair<JSONObject?, String?>>()
        val current = mutableListOf<Pair<JSONObject?, String?>>()
        f.trace.read(f.task.id, "original-run", "", 100) { data, error -> old.add(data to error) }
        f.trace.read(f.task.id, "retained-run", "", 100) { data, error -> current.add(data to error) }
        assertTrue(f.reads[0].cancelled)
        assertEquals(1, old.size); assertNull(old[0].first); assertNotNull(old[0].second)
        f.reads[0].done(f.receipt(), null)
        assertEquals(1, old.size); assertTrue(current.isEmpty())
        f.reads[1].done(f.receipt("retained-run"), null)
        assertEquals("retained-run", current.single().first!!.getString("runId"))
        assertNull(current.single().second)
    }

    @Test fun closingThePanelCancelsOnlyItsReadAndCompletesThePendingCommandOnce() {
        val f = Fixture(); val responses = mutableListOf<Pair<JSONObject?, String?>>()
        f.trace.read(f.task.id, "", "", 100) { data, error -> responses.add(data to error) }
        f.trace.clear(); f.trace.clear()
        f.reads.single().done(f.receipt(), null)
        assertTrue(f.reads.single().cancelled)
        assertEquals(1, responses.size)
        assertNull(responses.single().first); assertNotNull(responses.single().second)
    }

    @Test fun navigationAndWrongNativeReceiptsCannotDisplayAnotherTrace() {
        val changes = listOf<(Fixture, JSONObject) -> Unit>(
            { f, _ -> f.owner++ }, { f, _ -> f.visible = false },
            { _, data -> data.put("taskId", "other-task") },
            { _, data -> data.put("runId", "other-run") },
            { _, data -> data.put("executionAuthorized", true) },
            { _, data -> data.put("readOnly", "true") }
        )
        for (change in changes) {
            val f = Fixture(); val responses = mutableListOf<Pair<JSONObject?, String?>>()
            f.trace.read(f.task.id, "original-run", "", 100) { data, error -> responses.add(data to error) }
            val receipt = f.receipt(); change(f, receipt)
            f.reads.single().done(receipt, null)
            assertNull(responses.single().first); assertNotNull(responses.single().second)
        }
    }

    @Test fun unopenedAndUnavailableTasksNeverReachTheNativeReader() {
        val f = Fixture()
        assertThrows(IllegalStateException::class.java) { f.trace.read("other", "", "", 100) { _, _ -> } }
        f.task.raw.put("sourceCurrentReadRequired", true)
        assertThrows(IllegalStateException::class.java) { f.trace.read(f.task.id, "", "", 100) { _, _ -> } }
        assertTrue(f.reads.isEmpty())
    }

    @Test fun synchronousNativeFailureSettlesOnceAndDoesNotLeaveAPendingWindow() {
        val f = Fixture(); val responses = mutableListOf<Pair<JSONObject?, String?>>()
        val trace = PocketWebTrace({ true }, { Pair(Triple(1L, 0, 0L), 0L) }, { f.task },
            { _, _, _, _, _ -> throw IllegalStateException("原读取已关闭") },
            { _, _, _, _ -> throw IllegalStateException("原读取已关闭") }, { f.sourceRevision })
        trace.read(f.task.id, "", "", 100) { data, error -> responses.add(data to error) }
        trace.clear()
        assertEquals(1, responses.size)
        assertNull(responses.single().first); assertEquals("原读取已关闭", responses.single().second)
    }

    @Test fun detailAndBackfillCompleteIndependentlyInTheSameOwnerWindow() {
        val f = Fixture()
        val page = mutableListOf<Pair<JSONObject?, String?>>()
        val detail = mutableListOf<Pair<JSONObject?, String?>>()
        f.trace.read(f.task.id, "original-run", "older-page", 200) { data, error -> page.add(data to error) }
        f.trace.detail(f.task.id, "original-run", 7, "model:original-call") { data, error -> detail.add(data to error) }
        assertFalse(f.reads.single().cancelled)
        f.details.single().done(f.detailReceipt(), null)
        assertEquals("model:original-call", detail.single().first!!.getJSONObject("node").getString("id"))
        assertTrue(page.isEmpty())
        f.reads.single().done(f.receipt(), null)
        assertNotNull(page.single().first)
    }

    @Test fun selectingAnotherDetailCancelsOnlyThePriorDetailAndRejectsItsLateBody() {
        val f = Fixture()
        val old = mutableListOf<Pair<JSONObject?, String?>>()
        val current = mutableListOf<Pair<JSONObject?, String?>>()
        f.trace.read(f.task.id, "original-run", "", 100) { _, _ -> }
        f.trace.detail(f.task.id, "original-run", 7, "model:original-call") { data, error -> old.add(data to error) }
        f.trace.detail(f.task.id, "original-run", 7, "tool:other-call") { data, error -> current.add(data to error) }
        assertFalse(f.reads.single().cancelled)
        assertTrue(f.details[0].cancelled)
        f.details[0].done(f.detailReceipt(), null)
        assertEquals(1, old.size); assertNull(old.single().first)
        f.details[1].done(f.detailReceipt("tool:other-call"), null)
        assertEquals("tool:other-call", current.single().first!!.getJSONObject("node").getString("id"))
    }

    @Test fun closingTraceSettlesBothReadsOnceWithoutAcceptingLateResponses() {
        val f = Fixture()
        val responses = mutableListOf<Pair<JSONObject?, String?>>()
        f.trace.read(f.task.id, "original-run", "", 100) { data, error -> responses.add(data to error) }
        f.trace.detail(f.task.id, "original-run", 7, "model:original-call") { data, error -> responses.add(data to error) }
        f.trace.clear(); f.trace.clear()
        assertTrue(f.reads.single().cancelled); assertTrue(f.details.single().cancelled)
        f.reads.single().done(f.receipt(), null); f.details.single().done(f.detailReceipt(), null)
        assertEquals(2, responses.size)
        assertTrue(responses.all { it.first == null && it.second != null })
    }

    @Test fun detailMustMatchTheExactNodeEpochAndStillCurrentOwnerSource() {
        val changes = listOf<(Fixture, JSONObject) -> Unit>(
            { f, _ -> f.owner++ }, { f, _ -> f.visible = false },
            { f, _ -> f.sourceRevision++ },
            { f, _ -> f.task.raw.put("sourceCurrentReadRequired", true) },
            { _, data -> data.put("epoch", 8) }, { _, data -> data.put("epoch", 7.1) },
            { _, data -> data.put("epoch", "7") },
            { _, data -> data.getJSONObject("node").put("id", "model:other") },
            { _, data -> data.put("runId", "other-run") },
            { _, data -> data.put("taskId", "other-task") },
            { _, data -> data.put("executionAuthorized", true) }
        )
        for (change in changes) {
            val f = Fixture(); val responses = mutableListOf<Pair<JSONObject?, String?>>()
            f.trace.detail(f.task.id, "original-run", 7, "model:original-call") { data, error -> responses.add(data to error) }
            val receipt = f.detailReceipt(); change(f, receipt)
            f.details.single().done(receipt, null)
            assertNull(responses.single().first); assertNotNull(responses.single().second)
        }
    }

    @Test fun switchingRunClosesThePreviousRunDetail() {
        val f = Fixture(); val responses = mutableListOf<Pair<JSONObject?, String?>>()
        f.trace.detail(f.task.id, "original-run", 7, "model:original-call") { data, error -> responses.add(data to error) }
        f.trace.read(f.task.id, "retained-run", "", 100) { _, _ -> }
        assertTrue(f.details.single().cancelled)
        f.details.single().done(f.detailReceipt(), null)
        assertEquals(1, responses.size); assertNull(responses.single().first)
    }

    @Test fun unavailableSourceIsExplicitAndSecretsNeverCrossTheDetailBridge() {
        val f = Fixture(); val responses = mutableListOf<Pair<JSONObject?, String?>>()
        f.trace.detail(f.task.id, "original-run", 7, "model:original-call") { data, error -> responses.add(data to error) }
        val receipt = f.detailReceipt().put("sourceState", "unavailable").put("sourceReason", "source_forgotten")
        receipt.getJSONObject("node").put("options", JSONObject().put("temperature", 0.2).put("apiKey", "never-return"))
        f.details.single().done(receipt, null)
        val result = responses.single().first!!
        assertEquals("unavailable", result.getString("sourceState"))
        assertEquals("source_forgotten", result.getString("sourceReason"))
        assertFalse(result.toString().contains("never-return"))
        assertEquals(0.2, result.getJSONObject("node").getJSONObject("options").getDouble("temperature"), 0.0)
    }
}
