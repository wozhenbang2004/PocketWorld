package dev.pocketengine.ui

import dev.pocketengine.EngineApplication
import dev.pocketengine.testutil.ReadyApplicationFixture
import java.security.Security
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Original Controller/source-learning API, real source bytes and model request; no deleted renderer.
 * Web multi-source job interaction has independent adapter tests; this keeps single-source semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class, shadows = [ReadyApplicationFixture.NoUnrelatedStartup::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SourceLearningMountedNativeTest {
    private val keys = MemoryWorkflowWebFixture.testKeys()
    @Before fun keys() { assertTrue(Security.insertProviderAt(keys, 1) > 0) }
    @After fun removeKeys() { Security.removeProvider(keys.name) }
    private fun draft() = JSONObject().put("layer", "fact").put("text", "仅这个项目保留的待审约定").put("reason", "来自这次选中的真实任务结果").put("applicability", "仅当前项目，仍须主人核对").put("confidence", 0.5).toString()
    private fun prepare(f: MemoryWorkflowWebFixture, task: String) = f.command("learning", "prepareLearningSource", JSONObject().put("kind", "task_evidence").put("sourceId", "task:$task:verified_result"))

    @Test fun longOriginalNeedsExplicitModelRequestAndRemainsAnUnadoptedExactSourceCandidate() {
        MemoryWorkflowWebFixture().use { f ->
            val source = f.app.tasks.createCapabilityTask("完整原结果阅读任务", "").getString("id")
            val summary = "原始确认说明。".repeat(310) + "SOURCE_TAIL_NATIVE_316"
            f.app.tasks.finish(source, "needs_confirmation", summary, JSONObject()); f.confirm(source, true); f.answer = draft(); f.controller.enterOwnerDomain("learning")
            val preview = prepare(f, source)
            assertEquals(0, f.calls.get()); assertFalse(preview.getBoolean("modelCalled")); assertTrue(preview.getBoolean("learningAllowed")); assertTrue(preview.getString("text").contains("SOURCE_TAIL_NATIVE_316")); assertTrue(preview.getString("text").length > 2000); assertFalse(preview.toString().contains("This UI prose"))
            val before = f.memory.handle("list", JSONObject().put("scope", f.scope())).getJSONArray("records").length()
            val result = f.command("learning", "proposeLearning", JSONObject().put("sourceRef", preview.getJSONObject("sourceRef")))
            assertEquals(1, f.calls.get()); assertTrue(f.requests[0].toString().contains("SOURCE_TAIL_NATIVE_316"))
            assertEquals("awaiting_review", result.getString("outcome")); assertFalse(result.getBoolean("formalMemorySaved")); assertTrue(result.getBoolean("reviewRequired"))
            val candidate = result.getJSONObject("candidate"); assertEquals("pending", candidate.getString("status")); assertEquals("task:$source:verified_result", candidate.getJSONArray("sourceRefs").getJSONObject(0).getString("sourceId"))
            assertEquals(before, f.memory.handle("list", JSONObject().put("scope", f.scope())).getJSONArray("records").length())
            f.controller.leaveOwnerDomain("learning"); f.controller.openConnection("memoryReview")
            assertEquals("memoryReview", f.controller.state.requestedConnectionSection); assertTrue(f.controller.state.ownerResponses("learning").isEmpty())
        }
    }
    @Test fun actualStopCompletesBeforeTheRemoteAnswerAndDoesNotStageALateCandidate() {
        MemoryWorkflowWebFixture().use { f ->
            f.answer = draft(); f.blockCall = 1; f.controller.enterOwnerDomain("learning")
            val source = prepare(f, f.sourceTask); var callbacks = 0; var result: JSONObject? = null; var error: String? = null
            val request = f.controller.requestOwnerDomainReceipt("learning", "proposeLearning", JSONObject().put("sourceRef", source.getJSONObject("sourceRef"))) { value, failure -> callbacks++; result = value; error = failure }
            assertTrue(f.blocked.await(4, TimeUnit.SECONDS))
            f.controller.cancelLearning(request)
            val stop = f.controller.state.ownerResponses("learning")["cancelLearning"]!!; assertTrue(stop.getBoolean("ok")); assertTrue(stop.getJSONObject("result").getBoolean("cancelled"))
            assertEquals(1L, f.release.count); assertEquals(1, callbacks); assertNull(result); assertNotNull(error)
            val taskId = stop.getJSONObject("result").getString("hostTaskId"); f.release.countDown()
            MemoryWorkflowWebFixture.await { f.app.tasks.get(taskId, false).optString("status") == "cancelled" }
            f.drain(); assertEquals(1, callbacks); assertEquals(1, f.calls.get()); assertEquals(0, f.memory.handle("listCandidates", JSONObject().put("scope", f.scope())).getJSONArray("candidates").length())
        }
    }
    @Test fun unconfirmedAndForgottenSourcesNeverReachTheModelOrExposeOldBodies() {
        MemoryWorkflowWebFixture().use { f ->
            val unconfirmed = f.app.tasks.createCapabilityTask("尚未确认的空结果任务", "").getString("id"); f.controller.enterOwnerDomain("learning")
            var calls = 0; var result: JSONObject? = null; var error: String? = null
            f.controller.requestOwnerDomainReceipt("learning", "prepareLearningSource", JSONObject().put("kind", "task_evidence").put("sourceId", "task:$unconfirmed:verified_result")) { value, failure -> calls++; result = value; error = failure }
            MemoryWorkflowWebFixture.await { calls == 1 }; assertNull(result); assertTrue(error.orEmpty().contains("真实来源不存在或尚未确认")); assertEquals(0, f.calls.get())
            f.app.nativeOwnerWorkflows.memory("forgetReviewed", JSONObject().put("id", f.old.getString("id")).put("expectedRevision", f.old.getLong("revision")))
            val forgotten = prepare(f, f.sourceTask)
            assertEquals("source_forgotten", forgotten.getString("status")); assertFalse(forgotten.getBoolean("learningAllowed")); assertFalse(forgotten.getBoolean("modelCalled")); assertFalse(forgotten.has("text")); assertEquals(0, f.calls.get())
            assertFalse(forgotten.toString().contains("B1_OLD_REFERENCE_741"))
        }
    }
}
