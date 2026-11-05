package dev.pocketengine.ui

import android.os.Looper
import com.sun.net.httpserver.HttpServer
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.assistant.AssistantModule
import dev.pocketengine.features.memory.MemoryBook
import dev.pocketengine.features.memory.MemoryModule
import dev.pocketengine.features.memory.SourceRef
import dev.pocketengine.media.MediaController
import dev.pocketengine.testutil.ReadyApplicationFixture
import dev.pocketengine.ui.web.PocketWebMemoryHistory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.Provider
import java.security.Security
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.*

/** Actual Web adapter -> Controller -> Application -> real request journal and loopback model.
 * Browser rendering is covered separately; no supplied proof DTO replaces the original journal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class, shadows = [ReadyApplicationFixture.NoUnrelatedStartup::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TaskMemoryMountedNativeTest {
    private val keys = MemoryWorkflowWebFixture.testKeys()
    @Before fun keys() { assertTrue(Security.insertProviderAt(keys, 1) > 0) }
    @After fun removeKeys() { Security.removeProvider(keys.name) }

    @Test fun searchOldTaskOpenOriginalThenCorrectTheExactRequestAndRetireRevokedSources() {
        MemoryWorkflowWebFixture().use { f ->
            repeat(205) { f.app.tasks.beginExternal("memory_history_fixture", "inspect", UUID.randomUUID().toString(), JSONObject().put("goal", "较新的原任务 $it")) }
            assertFalse(f.app.tasks.list().toString().contains(f.sourceTask))
            val session = f.session()
            val found = f.web("taskMemory.search", JSONObject().put("sessionHandle", session).put("query", "早期项目验收").put("cursor", ""))
            assertEquals("full_task_store", found.getString("coverage")); assertEquals(f.sourceTask, found.getJSONArray("tasks").getJSONObject(0).getString("taskId")); assertFalse(found.getJSONArray("tasks").getJSONObject(0).getBoolean("formalMemory"))
            val sourceTask = f.task(session, f.sourceTask)
            f.web("taskMemory.focus", JSONObject().put("taskHandle", sourceTask.getString("taskHandle")))
            assertEquals(f.sourceTask, f.controller.state.requestedTaskId)
            val first = f.completed(f.send("按项目约定回答")); val task = f.task(f.session(), first.getString("id"))
            val receipt = f.receipt(task); val row = f.row(receipt, f.old.getString("id"))
            assertEquals("provided_to_request", receipt.getString("phase")); assertFalse(receipt.getBoolean("modelUseVerified")); assertEquals(1, receipt.getJSONObject("selectionDiagnostics").getInt("coreSelected"))
            val source = f.web("taskMemory.source", JSONObject().put("readHandle", row.getString("readHandle")).put("index", 0).put("offset", 0))
            assertTrue(source.getString("text").contains("主人核对时的原结果")); assertEquals(f.source.toJson().toString(), source.getJSONObject("sourceRef").toString())
            val review = f.web("taskMemory.review", JSONObject().put("readHandle", row.getString("readHandle")).put("operation", "correct").put("text", "项目约定 B1_CORRECTED_REFERENCE_952"))
            val payload = JSONObject().put("reviewHandle", review.getString("reviewHandle"))
            val saved = f.web("taskMemory.confirm", payload); assertTrue(saved.getBoolean("contextInvalidated")); assertTrue(saved.getBoolean("futureRecallBlocked")); assertTrue(saved.getString("feedback").contains("清理本次旧参考"))
            f.reject("taskMemory.confirm", payload); f.reject("taskMemory.source", JSONObject().put("readHandle", row.getString("readHandle")).put("index", 0).put("offset", 0))
            f.answer = "当前约定已纠正"; val second = f.completed(f.send("再核对当前项目约定"))
            assertFalse(f.requests[1].toString().contains("B1_OLD_REFERENCE_741")); assertTrue(f.requests[1].toString().contains("B1_CORRECTED_REFERENCE_952"))
            val nextTask = f.task(f.session(), second.getString("id")); val nextReceipt = f.receipt(nextTask); val next = f.row(nextReceipt, f.old.getString("id"))
            assertEquals("current", f.web("taskMemory.source", JSONObject().put("readHandle", next.getString("readHandle")).put("index", 0).put("offset", 0)).getString("status"))
            f.app.tasks.finish(f.sourceTask, "needs_confirmation", "主人撤回原结果确认", JSONObject())
            f.confirm(f.sourceTask, false)
            val revoked = f.receipt(nextTask)
            assertEquals("invalidated", revoked.getString("status")); assertFalse(revoked.toString().contains("B1_OLD_REFERENCE_741")); assertFalse(revoked.toString().contains("B1_CORRECTED_REFERENCE_952"))
            f.reject("taskMemory.review", JSONObject().put("readHandle", next.getString("readHandle")).put("operation", "forget"))
        }
    }
    @Test fun originalRequestBudgetShowsOmittedCoreIdentityWithoutOldBodies() {
        MemoryWorkflowWebFixture().use { f ->
            repeat(15) { f.memory.handle("save", JSONObject().put("scope", f.scope()).put("record", JSONObject().put("text", "稳定主人偏好 $it").put("core", true))) }
            f.memory.handle("save", JSONObject().put("scope", f.scope()).put("record", JSONObject().put("text", "DELTA_TICKET 当前任务的明确约定")))
            val task = f.completed(f.send("DELTA_TICKET")); val receipt = f.receipt(f.task(f.session(), task.getString("id")))
            val details = receipt.getJSONObject("selectionDiagnostics")
            assertTrue(receipt.getBoolean("selectionDiagnosticsAvailable")); assertEquals(1, details.getJSONObject("omissionCounts").getInt("record_limit")); assertEquals(1, details.getInt("retrievedSelected"))
            val omitted = details.getJSONArray("omitted"); assertTrue(omitted.length() > 0)
            repeat(omitted.length()) { assertTrue(omitted.getJSONObject(it).getLong("revision") > 0); assertFalse(omitted.getJSONObject(it).has("text")) }
        }
    }
}

/** Shared native workflow fixture for the migrated memory/source tests. Explicit Ready bootstrap. */
internal class MemoryWorkflowWebFixture : AutoCloseable {
    companion object {
        fun testKeys() = object : Provider("MemoryWeb-${UUID.randomUUID()}", 1.0, "Explicit test-only AndroidKeyStore") {
            init { put("KeyStore.AndroidKeyStore", dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) }
        }
        fun await(check: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!check() && System.nanoTime() < until) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
            Shadows.shadowOf(Looper.getMainLooper()).idle(); assertTrue("Original native workflow did not settle", check())
        }
    }
    val app = RuntimeEnvironment.getApplication() as EngineApplication
    private val owner: org.robolectric.android.controller.ActivityController<ComposeActivity>
    private val media: MediaController
    val controller: PocketWorldController
    val memory: MemoryModule
    private val assistant: AssistantModule
    private val server: HttpServer
    val requests = CopyOnWriteArrayList<JSONObject>(); val calls = AtomicInteger()
    val blocked = CountDownLatch(1); val release = CountDownLatch(1)
    @Volatile var blockCall = -1
    @Volatile var answer = "本轮参考：B1_OLD_REFERENCE_741"
    val sourceTask: String; val source: SourceRef; val old: JSONObject
    val history: PocketWebMemoryHistory
    private var conversation: String? = null
    init {
        ReadyApplicationFixture.initializeHost(app)
        owner = Robolectric.buildActivity(ComposeActivity::class.java)
        val activity = owner.get(); media = MediaController(activity, app::publish)
        ComposeActivity::class.java.getDeclaredField("media").apply { isAccessible = true }.set(activity, media)
        controller = PocketWorldController(activity, app)
        PocketWorldController::class.java.getDeclaredField("voiceForeground").apply { isAccessible = true }.setBoolean(controller, true)
        memory = app.features.module("memory") as MemoryModule; assistant = app.features.module("assistant") as AssistantModule
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                val call = calls.incrementAndGet(); requests += JSONObject(String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8))
                if (call == blockCall) { blocked.countDown(); release.await(10, TimeUnit.SECONDS) }
                val body = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", answer)).put("finish_reason", "stop"))).toString().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body)
            } finally { exchange.close() }
        }
        server.start()
        app.getSharedPreferences("private_settings", 0).edit().putString("baseUrl", "http://127.0.0.1:${server.address.port}").putString("provider", "openai_chat").putString("model", "native-memory-flow").putInt("maxOutputTokens", 1024).putInt("maxSteps", 8).putBoolean("sendScreenshots", false).commit()
        sourceTask = app.tasks.createCapabilityTask("早期项目验收原任务证据", "").getString("id")
        app.tasks.finish(sourceTask, "needs_confirmation", "主人核对时的原结果 B1_OLD_REFERENCE_741", JSONObject()); confirm(sourceTask, true)
        source = app.tasks.resolveMemorySource(MemoryBook.Scope.from(scope()), "task_evidence", "task:$sourceTask:verified_result").ref
        val saved = memory.importFromSources(scope(), JSONObject().put("text", "项目约定 B1_OLD_REFERENCE_741").put("sourceRefs", JSONArray().put(source.toJson()))).getJSONObject("record")
        old = memory.handle("setCore", JSONObject().put("scope", scope()).put("id", saved.getString("id")).put("revision", saved.getLong("revision")).put("core", true)).getJSONObject("record")
        history = PocketWebMemoryHistory(controller); controller.attach(); controller.enterOwnerDomain("taskMemory"); drain()
    }
    fun scope() = JSONObject().put("ownerId", "owner").put("spaceId", "private")
    fun confirm(id: String, completed: Boolean) = app.dispatchOwnerAction("confirmResult", JSONObject().put("taskId", id).put("resultReviewDigest", app.tasks.get(id, false).getString("resultReviewDigest")).put("completed", completed))
    fun send(text: String): JSONObject {
        val args = JSONObject().put("message", text).put("clientMessageId", UUID.randomUUID().toString()); conversation?.let { args.put("conversationId", it) }
        return assistant.handle("send", args).also { conversation = it.getString("conversationId") }
    }
    fun completed(ack: JSONObject): JSONObject {
        await { !assistant.snapshot().getBoolean("running") && app.tasks.get(ack.getString("taskId"), false).optString("status") in setOf("completed", "failed", "needs_confirmation", "cancelled") }; drain()
        return app.tasks.get(ack.getString("taskId"), false)
    }
    fun drain() { val io = PocketWorldController::class.java.getDeclaredField("io").apply { isAccessible = true }.get(controller) as ExecutorService; repeat(3) { io.submit {}.get(10, TimeUnit.SECONDS); Shadows.shadowOf(Looper.getMainLooper()).idle() } }
    fun web(action: String, args: JSONObject): JSONObject {
        var calls = 0; var result: JSONObject? = null; var error: String? = null
        history.dispatch(action, args) { value, failure -> calls++; result = value; error = failure }
        await { calls > 0 }; assertEquals(1, calls); assertNull(error, error); return result ?: throw IllegalStateException("No original Web result")
    }
    fun reject(action: String, args: JSONObject) {
        var calls = 0; var result: JSONObject? = null; var error: String? = null
        try { history.dispatch(action, args) { value, failure -> calls++; result = value; error = failure } } catch (failure: Exception) { calls++; error = failure.message }
        await { calls > 0 }; assertEquals(1, calls); assertNull(result); assertFalse(error.isNullOrBlank())
    }
    fun command(domain: String, command: String, args: JSONObject): JSONObject {
        var calls = 0; var result: JSONObject? = null; var error: String? = null
        controller.requestOwnerDomainReceipt(domain, command, args) { value, failure -> calls++; result = value; error = failure }
        await { calls > 0 }; assertEquals(1, calls); assertNull(error, error); return result ?: throw IllegalStateException("No original native result")
    }
    fun session() = web("memoryHistory.open", JSONObject().put("view", "tasks")).getString("sessionHandle")
    fun task(session: String, id: String) = web("taskMemory.task", JSONObject().put("sessionHandle", session).put("taskId", id))
    fun receipt(task: JSONObject) = web("taskMemory.receipt", JSONObject().put("taskHandle", task.getString("taskHandle")))
    fun row(receipt: JSONObject, id: String): JSONObject = receipt.getJSONArray("records").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == id } }
    override fun close() { release.countDown(); history.clear(); controller.detach(); media.close(); owner.close(); server.stop(0); app.features.close() }
}
