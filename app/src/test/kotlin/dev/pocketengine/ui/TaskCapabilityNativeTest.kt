package dev.pocketengine.ui

import android.app.Application
import android.os.Looper
import android.util.Base64
import dev.pocketengine.features.plugins.TaskMcpPreparationTest
import dev.pocketengine.features.skills.NativeSkillExecutionHostTest
import dev.pocketengine.ui.web.PocketWebTaskCapabilities
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

/** The same real task/Skill host/TaskStore/canonical loopback backend, now through the single Web
 * presentation adapter. Its protocol fixture never runs user Skill code and is no sandbox proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TaskCapabilityNativeTest {
    private class Window {
        @Volatile var foreground = true
        @Volatile var identity = 1L
    }
    private class Web(val bridge: PocketWebTaskCapabilities, val io: ExecutorService) : AutoCloseable {
        class Reply { var calls = 0; var value: JSONObject? = null; var failure: String? = null }
        fun start(action: String, input: JSONObject = JSONObject()): Reply = Reply().also { reply ->
            try { bridge.dispatch(action, input) { value, failure -> reply.calls++; reply.value = value; reply.failure = failure } }
            catch (failure: Exception) { reply.calls++; reply.failure = failure.message ?: failure.javaClass.name }
        }
        fun finish(reply: Reply): Reply {
            repeat(40) { io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle(); if (reply.calls > 0) { assertEquals(1, reply.calls); return reply } }
            throw AssertionError("Original Web callback did not settle")
        }
        fun call(action: String, input: JSONObject = JSONObject()): JSONObject = finish(start(action, input)).let { assertNull(it.failure); checkNotNull(it.value) }
        fun rejected(action: String, input: JSONObject) { val result = finish(start(action, input)); assertNull(result.value); assertNotNull(result.failure) }
        override fun close() { bridge.clear(); io.shutdownNow(); shadowOf(Looper.getMainLooper()).idle() }
    }
    private fun web(domain: NativeSkillExecutionHostTest.Fixture, window: Window = Window()): Web {
        val io = Executors.newSingleThreadExecutor()
        return Web(PocketWebTaskCapabilities({ window.identity }, { window.foreground }, { domain.host },
            { task -> domain.host.taskSnapshot(task, setOf("skills_execute"), JSONObject()) },
            { task, operation, id, sha -> domain.tasks.readSkillArtifact(task, operation, id, sha) },
            { error("No unrelated MCP catalogue in this script task") }, { _, _, _ -> error("No unrelated MCP mutation") }, { io.execute(it) }), io)
    }
    private fun page(web: Web, domain: NativeSkillExecutionHostTest.Fixture) = web.call("taskCapabilities.open", JSONObject().put("taskId", domain.task.getString("id")))
    private fun handle(page: JSONObject) = JSONObject().put("pageHandle", page.getString("pageHandle"))
    private fun reviewInput(review: JSONObject) = JSONObject().put("reviewHandle", review.getString("reviewHandle"))
    private fun approve(web: Web, page: JSONObject): JSONObject {
        val review = web.call("taskCapabilities.review", handle(page))
        val consent = web.call("taskCapabilities.consent", reviewInput(review).put("accepted", true))
        assertFalse(consent.getBoolean("executionApproved")); assertFalse(consent.getBoolean("saved"))
        val result = web.call("taskCapabilities.decide", reviewInput(review).put("approved", true))
        assertTrue(result.getBoolean("decisionRecorded")); assertFalse(result.getBoolean("completed")); return review
    }
    @Test fun taskOpensExactNativeReviewApprovesOnceAndRetainsOriginalArtifact() {
        NativeSkillExecutionHostTest.Fixture("字面参数 中文 空格").use { domain ->
            val running = domain.execute(); val native = domain.review()
            web(domain).use { web ->
                val page = page(web, domain); assertTrue(page.getBoolean("reviewPending")); assertEquals(0, domain.posts.get())
                val raw = domain.tasks.get(domain.task.getString("id"), false)
                assertTrue(unresolvedOperations(UiTask(raw.getString("id"), raw.getString("goal"), raw.getString("status"), raw.optString("summary"), raw.getString("executionMode"), raw.getLong("updatedAt"), raw)).isEmpty())
                val review = web.call("taskCapabilities.review", handle(page)); assertEquals(native.plan.digest, review.getString("planDigest")); assertEquals(0, domain.posts.get())
                val source = web.call("taskCapabilities.script", reviewInput(review)); assertTrue(source.getString("content").contains("NEVER EXECUTE USER SKILL")); assertFalse(source.getBoolean("hasMore")); assertEquals(native.plan.digest, source.getString("planDigest"))
                web.rejected("taskCapabilities.decide", reviewInput(review).put("approved", true)); assertEquals(0, domain.posts.get())
                web.call("taskCapabilities.consent", reviewInput(review).put("accepted", true))
                val decision = web.call("taskCapabilities.decide", reviewInput(review).put("approved", true)); assertTrue(decision.getBoolean("decisionRecorded")); assertFalse(decision.getBoolean("completed"))
                assertEquals("completed", running.get(12, TimeUnit.SECONDS).getString("status")); assertEquals(1, domain.posts.get())
                web.rejected("taskCapabilities.decide", reviewInput(review).put("approved", true))
                val current = web.call("taskCapabilities.refresh", handle(page)); assertFalse(current.getBoolean("reviewPending"))
                val operation = current.getJSONArray("executions").getJSONObject(0)
                val receipt = web.call("taskCapabilities.receipt", handle(current).put("operationHandle", operation.getString("operationHandle")))
                assertFalse(receipt.getBoolean("replayAllowed")); assertEquals(1, domain.posts.get())
                val actual = receipt.getJSONObject("view").getJSONArray("executions").getJSONObject(0).getJSONObject("receipt").getJSONArray("artifacts").getJSONObject(0)
                val file = web.call("taskCapabilities.artifact", handle(current).put("artifactHandle", actual.getString("artifactHandle")))
                assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0xff.toByte()), Base64.decode(file.getString("base64"), Base64.DEFAULT))
                web.rejected("taskCapabilities.artifact", handle(current).put("artifactHandle", actual.getString("artifactHandle")).put("sha256", "0".repeat(64)))
                assertEquals(1, domain.tasks.readSkillExecution(domain.task.getString("id"), domain.input.getString("_hostOperationId")).getJSONObject("receipt").getJSONArray("artifacts").length())
                assertEquals(1, domain.posts.get())
            }
        }
    }
    @Test fun nativeWindowBackgroundRejectsApprovalAndOldReviewNeverBecomesAReplayedRequest() {
        NativeSkillExecutionHostTest.Fixture().use { domain ->
            val running = domain.execute(); val native = domain.review(); val nativeWindow = domain.host.openOwnerWindow(); nativeWindow.setForeground(true); nativeWindow.setForeground(false)
            assertThrows(SecurityException::class.java) { domain.host.decide(nativeWindow, native, native.plan.digest, true) }; nativeWindow.close()
            val window = Window()
            web(domain, window).use { web ->
                val opened = page(web, domain); val review = web.call("taskCapabilities.review", handle(opened)); web.call("taskCapabilities.consent", reviewInput(review).put("accepted", true))
                window.foreground = false; web.bridge.clear(); window.identity++; window.foreground = true
                web.rejected("taskCapabilities.decide", reviewInput(review).put("approved", true)); assertEquals(0, domain.posts.get())
                domain.host.cancelReview(native); assertEquals("rejected", running.get(5, TimeUnit.SECONDS).getString("status"))
                val reopened = page(web, domain); assertFalse(reopened.getBoolean("reviewPending")); web.rejected("taskCapabilities.review", handle(reopened))
                val operation = reopened.getJSONArray("executions").getJSONObject(0)
                val original = web.call("taskCapabilities.receipt", handle(reopened).put("operationHandle", operation.getString("operationHandle")))
                assertEquals("rejected", original.getJSONObject("original").getJSONObject("receipt").getString("status")); assertFalse(original.getBoolean("replayAllowed")); assertEquals(0, domain.posts.get())
            }
        }
    }
    @Test fun completedScriptWithUnavailableFilesOffersOriginalArtifactRetrievalWithoutReexecution() {
        NativeSkillExecutionHostTest.Fixture().use { domain ->
            domain.failArtifactReads = true; val running = domain.execute(); domain.review()
            web(domain).use { web ->
                val opened = page(web, domain); approve(web, opened)
                assertEquals("unavailable", running.get(12, TimeUnit.SECONDS).getString("artifactStatus")); assertEquals(1, domain.posts.get())
                val current = web.call("taskCapabilities.refresh", handle(opened)); val operation = current.getJSONArray("executions").getJSONObject(0)
                assertTrue(operation.getJSONObject("artifactAvailability").getBoolean("canRecheck"))
                domain.failArtifactReads = false
                val recovered = web.call("taskCapabilities.retrieve", handle(current).put("operationHandle", operation.getString("operationHandle")))
                assertTrue(recovered.getBoolean("retrieved")); assertFalse(recovered.getBoolean("replayAllowed")); assertEquals(1, domain.posts.get())
                val artifacts = recovered.getJSONObject("view").getJSONArray("executions").getJSONObject(0).getJSONObject("receipt").getJSONArray("artifacts")
                assertEquals(1, artifacts.length()); assertEquals(1, domain.tasks.readSkillExecution(domain.task.getString("id"), domain.input.getString("_hostOperationId")).getJSONObject("receipt").getJSONArray("artifacts").length())
                web.rejected("taskCapabilities.retrieve", handle(current).put("operationHandle", operation.getString("operationHandle"))); assertEquals(1, domain.posts.get())
            }
        }
    }
    @Test fun requiredMcpReusesCurrentInstallAndOnlyGrantsTheDisplayedTool() {
        TaskMcpPreparationTest.UiDomain().use { domain ->
            val window = Window(); val io = Executors.newSingleThreadExecutor()
            fun snapshot(task: String): JSONObject {
                // Original test has no executing task: this is explicitly the same required-tool metadata fixture.
                val plugins = domain.snapshot().getJSONArray("plugins"); val install = (0 until plugins.length()).map { plugins.getJSONObject(it) }.first { it.getString("id") == "remote" }
                return JSONObject().put("taskId", task).put("executions", JSONArray()).put("reviewPending", false).put("pluginNeeds", JSONArray().put(JSONObject().put("pluginId", "remote").put("tool", "lookup").put("installation", install)))
            }
            val bridge = PocketWebTaskCapabilities({ window.identity }, { window.foreground }, { error("This MCP preparation has no script host") }, ::snapshot,
                { _, _, _, _ -> error("No script artifacts") }, { domain.snapshot() }, { command, payload, control -> domain.owner.mcp(command, payload, control) }, { io.execute(it) })
            Web(bridge, io).use { web ->
                val opened = web.call("taskCapabilities.open", JSONObject().put("taskId", "required-tool-fixture")); assertEquals(1, opened.getJSONArray("pluginNeeds").length())
                val need = opened.getJSONArray("pluginNeeds").getJSONObject(0); assertEquals("remote", need.getString("pluginId")); assertFalse(opened.toString().contains("不相关服务")); assertEquals(0, domain.callCount())
                val review = web.call("taskCapabilities.mcp.review", handle(opened).put("needHandle", need.getString("needHandle")).put("change", "grant").put("value", true))
                assertEquals("lookup", review.getJSONObject("tool").getString("name")); web.rejected("taskCapabilities.mcp.confirm", reviewInput(review))
                web.call("taskCapabilities.consent", reviewInput(review).put("accepted", true))
                val saved = web.call("taskCapabilities.mcp.confirm", reviewInput(review)); assertTrue(saved.getBoolean("saved")); assertFalse(saved.getBoolean("toolExecuted")); assertEquals(0, domain.callCount())
                web.rejected("taskCapabilities.mcp.confirm", reviewInput(review))
                val current = saved.getJSONObject("view"); val enabled = web.call("taskCapabilities.mcp.review", handle(current).put("needHandle", current.getJSONArray("pluginNeeds").getJSONObject(0).getString("needHandle")).put("change", "enabled").put("value", true))
                web.call("taskCapabilities.consent", reviewInput(enabled).put("accepted", true)); web.call("taskCapabilities.mcp.confirm", reviewInput(enabled))
                assertEquals(1, domain.plugins.toolRegistry().length()); assertEquals("remote", domain.plugins.toolRegistry().getJSONObject(0).getString("pluginId")); assertEquals(0, domain.callCount())
                val all = domain.snapshot().getJSONArray("plugins"); val unrelated = (0 until all.length()).map { all.getJSONObject(it) }.first { it.getString("id") == "unrelated" }
                assertFalse(unrelated.getBoolean("enabled")); assertEquals(0, unrelated.getJSONArray("grants").length())
            }
        }
    }
}
