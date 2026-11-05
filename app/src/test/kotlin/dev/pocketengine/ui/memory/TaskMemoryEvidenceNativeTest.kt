package dev.pocketengine.ui.memory

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

/** Real B1 TaskMemoryUse/MemoryModule with the original controlled journal fixture.
 * Controller/Web transport is covered by TaskMemoryMountedNativeTest; rendering moved to memory-history.test.ts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TaskMemoryEvidenceNativeTest {
    private fun fixture(populate: Boolean = true, text: String = "原任务确认的地点是杭州。") = TaskMemoryNativeFixture(RuntimeEnvironment.getApplication(), populate, text)
    private fun receipt(f: TaskMemoryNativeFixture) = TaskMemoryEvidenceGuard.receipt(f.task.taskId, f.task.runId, f.receipt(), f.reference.requestId, f.reference.answerId)
    private fun row(value: JSONObject) = value.getJSONArray("records").getJSONObject(0)
    private fun records(f: TaskMemoryNativeFixture) = f.module.handle("list", JSONObject().put("scope", f.scope)).getJSONArray("records")

    @Test fun providedReceiptReadsOnlyItsExactOriginalSource() {
        fixture().use { f ->
            val receipt = receipt(f); assertTrue(f.commands.isEmpty()); assertEquals("provided_to_request", receipt.getString("phase")); assertFalse(receipt.getBoolean("modelUseVerified"))
            val selected = row(receipt); val payload = TaskMemoryEvidenceGuard.reviewed(receipt, selected).put("sourceRef", f.source.toJson())
            val source = f.request("readTaskMemorySource", payload)
            assertEquals("current", source.getString("status")); assertEquals("task:task-original:verified_result", source.getJSONObject("sourceRef").getString("sourceId")); assertEquals(2L, source.getJSONObject("sourceRef").getLong("revision"))
            assertEquals("原任务确认的地点证据", source.getString("title")); assertTrue(source.getString("text").contains("由用户确认的地点资料")); assertFalse(source.getBoolean("executionAuthorized"))
            assertThrows(SecurityException::class.java) { f.request("readTaskMemorySource", JSONObject(payload.toString()).put("requestId", "different-request")) }
            assertThrows(SecurityException::class.java) { f.request("readTaskMemorySource", JSONObject(payload.toString()).put("sourceRef", JSONObject(f.source.toJson().toString()).put("sourceId", "task:other:verified_result"))) }
        }
    }
    @Test fun correctionPersistsNewRevisionButDoesNotClaimContextCleanupWithoutTheRootAdapter() {
        fixture().use { f ->
            val receipt = receipt(f); val payload = TaskMemoryEvidenceGuard.correction(receipt, row(receipt), "目前已确认的地点是苏州。")
            val result = f.request("correctTaskMemoryReviewed", payload)
            val feedback = TaskMemoryEvidenceGuard.feedback(result, receipt, "correctTaskMemoryReviewed", payload)
            val saved = f.module.handle("get", JSONObject().put("scope", f.scope).put("id", f.original.getString("id"))).getJSONObject("record")
            assertEquals("目前已确认的地点是苏州。", saved.getString("text")); assertEquals(2L, saved.getLong("revision"))
            assertTrue(feedback.contains("旧上下文清理仍待核对")); assertFalse(feedback.contains("后续不会再召回")); assertFalse(receipt(f).toString().contains("原任务确认的地点是杭州。"))
        }
    }
    @Test fun explicitReplacementForgetsTheOldRevisionAndReportsSupersededNativeReceipt() {
        fixture().use { f ->
            val kept = f.keepCorrectFact("已另行保存的正确地点是苏州。"); val receipt = receipt(f)
            val payload = TaskMemoryEvidenceGuard.replacement(receipt, row(receipt), kept)
            val result = f.request("supersedeTaskMemoryReviewed", payload); TaskMemoryEvidenceGuard.feedback(result, receipt, "supersedeTaskMemoryReviewed", payload)
            assertEquals(1, records(f).length()); assertEquals(kept.getString("id"), records(f).getJSONObject(0).getString("id")); assertTrue(f.revocationRefs.length() > 0)
            assertEquals("superseded", row(receipt(f)).getString("status")); assertFalse(row(receipt(f)).has("text"))
        }
    }
    @Test fun failedCorrectionDoesNotWriteAndRestartWithoutJournalNeverSynthesizesRecall() {
        fixture(text = "原任务确认的地点是杭州，这里保留足够长的中文用于窄屏阅读。".repeat(20)).use { f ->
            val before = receipt(f); val draft = "用户仍未提交成功的纠正草稿"; val payload = TaskMemoryEvidenceGuard.correction(before, row(before), draft)
            f.failCorrection = true
            val failure = assertThrows(IllegalStateException::class.java) { f.request("correctTaskMemoryReviewed", payload) }
            assertTrue(failure.message.orEmpty().contains("此版本已变化")); assertEquals(draft, payload.getString("text")); assertEquals(f.original.getLong("revision"), row(receipt(f)).getLong("revision"))
            val reread = receipt(f); assertEquals(draft, TaskMemoryEvidenceGuard.correction(reread, row(reread), draft).getString("text"))
            f.reopenWithoutOriginalJournal(); val missing = receipt(f)
            assertEquals("unavailable", missing.getString("phase")); assertEquals(0, missing.getJSONArray("records").length()); assertFalse(missing.toString().contains("原任务确认的地点是杭州"))
            assertFalse(f.commands.any { it.contains("search", true) || it.contains("recall", true) })
        }
    }
    @Test fun realFailedTaskSearchRetainsOriginalCoverageAndNeverCreatesFormalMemory() {
        fixture().use { f ->
            val before = records(f).length(); val binding = TaskMemoryEvidenceGuard.target(receipt(f))
            val found = f.request("searchTaskMemoryTasks", binding.put("query", "地点").put("cursor", "").put("limit", 20))
            assertTrue(found.getString("coverage") in setOf("full_task_store", "recent_200_and_exact_task_id"))
            if (found.getString("coverage") == "recent_200_and_exact_task_id") assertTrue(found.getInt("searchedCount") <= 200)
            val original = found.getJSONArray("tasks").getJSONObject(0)
            assertEquals(f.searchedTaskId, original.getString("taskId")); assertEquals("failed", original.getString("status")); assertFalse(original.getBoolean("formalMemory")); assertFalse(original.getBoolean("executionIndependentlyVerified")); assertEquals(before, records(f).length())
        }
    }
    @Test fun emptyProvidedReceiptRemainsEmptyAndCannotInventAMutationTarget() {
        fixture(populate = false).use { f ->
            val receipt = receipt(f); assertEquals("provided_to_request", receipt.getString("phase")); assertEquals(0, receipt.getJSONArray("records").length())
            assertThrows(IllegalArgumentException::class.java) { TaskMemoryEvidenceGuard.reviewed(receipt, JSONObject().put("id", "invented").put("revision", 1).put("status", "current")) }
            assertTrue(f.commands.isEmpty())
        }
    }
    @Test fun actualForgetRemovesFormalMemoryAndLeavesContextCleanupUnconfirmed() {
        fixture().use { f ->
            val receipt = receipt(f); val payload = TaskMemoryEvidenceGuard.reviewed(receipt, row(receipt)); val result = f.request("forgetTaskMemoryReviewed", payload)
            val feedback = TaskMemoryEvidenceGuard.feedback(result, receipt, "forgetTaskMemoryReviewed", payload)
            assertEquals(0, records(f).length()); assertTrue(f.revocationRefs.length() > 0); assertTrue(feedback.contains("旧上下文清理仍待核对")); assertFalse(row(receipt(f)).has("text"))
        }
    }
    @Test fun revokedProofErasesOldBodyAndRejectsOldRevisionMutations() {
        fixture().use { f ->
            val old = row(receipt(f)); f.revokeProvided(); val current = receipt(f)
            assertEquals("revoked", current.getString("status")); assertFalse(current.toString().contains("原任务确认的地点是杭州。"))
            assertThrows(IllegalArgumentException::class.java) { TaskMemoryEvidenceGuard.reviewed(current, old) }; assertTrue(f.commands.isEmpty())
        }
    }
}
