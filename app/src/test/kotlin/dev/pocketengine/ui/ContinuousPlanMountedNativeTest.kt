package dev.pocketengine.ui

import android.os.Looper
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.AutomationPlanHost
import dev.pocketengine.features.HostAutomationCoordinator
import dev.pocketengine.media.MediaController
import dev.pocketengine.testutil.HttpServer
import dev.pocketengine.testutil.ReadyApplicationFixture
import dev.pocketengine.ui.web.PocketWebBridge
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.Provider
import java.security.Security
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** React's real bridge commands -> Controller/Application/Book/Runner. This is not a screenshot test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class,
    shadows = [ReadyApplicationFixture.NoUnrelatedStartup::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ContinuousPlanMountedNativeTest {
    internal class Screen(
        files: List<Pair<String, String>> = listOf(
            "reports/source.md" to "# Source\nFixture source file prepared in the actual workspace.\n",
            "reports/delivery.md" to "# Delivery\nFixture delivery file prepared in the actual workspace.\n"
        ),
        failPaths: Set<String> = emptySet(),
        private val holds: Map<String, CountDownLatch> = emptyMap()
    ) : AutoCloseable {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        private val keys = object : Provider("PlanBridge-${UUID.randomUUID()}", 1.0, "Explicit test-only AndroidKeyStore") {
            init { put("KeyStore.AndroidKeyStore", dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) }
        }
        private val owner = Robolectric.buildActivity(ComposeActivity::class.java)
        val activity = owner.get()
        private val media: MediaController
        val controller: PocketWorldController
        val bridge: PocketWebBridge
        val projectId: String
        val modelCalls = AtomicInteger()
        val requestedPaths = CopyOnWriteArrayList<String>()
        private val model = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val evidence = File(System.getProperty("b2.plan.renders") ?: File(app.cacheDir, "plan-bridge-evidence").path).apply { mkdirs() }
        private val events = mutableListOf<JSONObject>()
        private var sequence = 0
        private val document = "continuous-plan-${UUID.randomUUID()}"
        private var selectedPlan: String? = null
        private val alarmHost: HostAutomationCoordinator

        init {
            assertTrue(Security.insertProviderAt(keys, 1) > 0)
            ReadyApplicationFixture.initializeHost(app)
            val plans = EngineApplication::class.java.getDeclaredField("automationPlans").apply { isAccessible = true }.get(app) as AutomationPlanHost
            alarmHost = HostAutomationCoordinator(app, app.features, app.tasks, plans)
            EngineApplication::class.java.getDeclaredField("automationHost").apply { isAccessible = true }.set(app, alarmHost)
            val workspace = app.features.module("workspace")
            projectId = workspace.handle("create", JSONObject().put("name", "计划验收项目")).getString("id")
            files.forEach { (path, content) -> workspace.handle("write", JSONObject().put("projectId", projectId).put("path", path).put("content", content).put("expectedVersion", 0)) }
            model.createContext("/v1/chat/completions") { exchange -> try {
                modelCalls.incrementAndGet()
                val request = JSONObject(String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8))
                val history = request.getJSONArray("messages")
                var frozen = false
                var path = files.first().first
                for (index in 0 until history.length()) {
                    val row = history.getJSONObject(index)
                    files.firstOrNull { row.optString("content").contains("写入与冻结仅限 " + it.first) }?.let { path = it.first }
                    if (row.optString("role") == "tool" && row.optString("content").contains("task_workspace")) frozen = true
                }
                requestedPaths += path
                holds[path]?.let { assertTrue("Controlled original model reply was never released", it.await(40, TimeUnit.SECONDS)) }
                if (path in failPaths) {
                    val response = "{\"error\":{\"message\":\"controlled original dependency task failure\"}}".toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(500, response.size.toLong()); exchange.responseBody.write(response)
                    return@createContext
                }
                val message = JSONObject().put("role", "assistant").put("content", if (frozen) "指定文件已冻结，等待原生核验。" else "")
                if (!frozen) message.put("tool_calls", JSONArray().put(JSONObject().put("id", "plan-freeze-${modelCalls.get()}").put("type", "function")
                    .put("function", JSONObject().put("name", "workspace_create_artifact").put("arguments", JSONObject().put("projectId", projectId).put("path", path).put("version", 0).toString()))))
                val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", if (frozen) "stop" else "tool_calls")))
                    .toString().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.write(response)
            } finally { exchange.close() } }
            model.start()
            val connection = app.settings.saveApiConnection("", 0, "Original plan source", JSONObject()
                .put("baseUrl", "http://127.0.0.1:${model.address.port}/v1").put("model", "plan-bridge-controlled-model").put("provider", "openai_chat")
                .put("maxSteps", 6).put("maxOutputTokens", 512).put("sendScreenshots", false))
            app.settings.selectApiConnection(connection.getString("id"), 1)
            media = MediaController(activity, app::publish)
            ComposeActivity::class.java.getDeclaredField("media").apply { isAccessible = true }.set(activity, media)
            controller = PocketWorldController(activity, app)
            ComposeActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }.set(activity, controller)
            controller.attach()
            // Explicit foreground host fixture; Activity installation/startup is covered separately.
            PocketWorldController::class.java.getDeclaredField("voiceForeground").apply { isAccessible = true }.setBoolean(controller, true)
            bridge = PocketWebBridge(controller) { events += JSONObject(it.toString()) }
            bridge.start()
            request("app.ready", JSONObject().put("documentId", document))
            request("connections.enter", JSONObject().put("section", "commitments"))
            until("original workspace project") { controller.state.feature("workspace").optJSONArray("projects").objects().any { it.optString("id") == projectId } }
            snapshot()
        }

        fun advance() { repeat(4) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)); Thread.sleep(10) } }
        fun until(label: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) { if (condition()) return; advance() }
            error("Missing $label: errors=${controller.state.moduleErrors}; tasks=${app.tasks.list()}")
        }
        fun reply(action: String, args: JSONObject = JSONObject()): JSONObject {
            val id = "plan-command-${++sequence}"
            var stale = false
            val raw = JSONObject().put("v", 1).put("id", id).put("documentId", document).put("action", action).put("args", args).toString()
            bridge.postMessage(raw, bridge.captureWindow()) { stale = true }
            until("bridge result $action") { stale || events.any { it.optString("kind") == "result" && it.optString("id") == id } }
            assertFalse("The current owner request unexpectedly lost its window", stale)
            return events.single { it.optString("kind") == "result" && it.optString("id") == id }
        }
        fun request(action: String, args: JSONObject = JSONObject()): JSONObject {
            val result = reply(action, args)
            assertTrue("$action: ${result.optJSONObject("error")}", result.getBoolean("ok"))
            return result.optJSONObject("value") ?: JSONObject()
        }
        fun snapshot() = request("plans.snapshot")
        fun read(id: String): JSONObject { snapshot(); return request("plans.read", JSONObject().put("id", id)) }
        fun confirm(review: JSONObject) = request("plans.confirm", JSONObject().put("reviewHandle", review.getString("reviewHandle")))
        fun stepDraft(goal: String, path: String, heading: String, dependencies: Map<Int, String> = emptyMap(), remoteWait: Any? = null): JSONObject = JSONObject()
            .put("goal", goal).put("executor", "workspace").put("projectId", projectId).put("path", path).put("heading", heading).put("minimumBytes", 16)
            .put("dependencies", JSONArray().also { rows -> dependencies.forEach { (step, outcome) -> rows.put(JSONObject().put("step", step).put("outcome", outcome)) } })
            .put("remoteWait", remoteWait ?: JSONObject.NULL).put("businessWait", JSONObject.NULL)
        fun reviewCreate(goal: String, steps: List<JSONObject>): JSONObject {
            snapshot()
            return request("plans.reviewCreate", JSONObject().put("goal", goal).put("delayMinutes", 0).put("steps", JSONArray(steps)))
        }
        fun create(goal: String, steps: List<JSONObject>): String {
            val value = confirm(reviewCreate(goal, steps)).getJSONObject("reading").getJSONObject("plan")
            return value.getString("id").also { selectedPlan = it }
        }
        fun approval(id: String, mode: String = "automatic", requiresCharging: Boolean = false, admissions: Int = 10, hours: Int = 1): JSONObject {
            val view = read(id)
            val settings = JSONObject().put("mode", mode).put("phoneMode", "background").put("maxTaskAdmissions", admissions)
                .put("modelObservationConsent", true).put("allowPrivateArtifactFreeze", true).put("businessEventConsent", false)
                .put("hours", hours).put("zoneId", "UTC").put("quietHours", JSONObject.NULL)
                .put("requiresNetwork", false).put("requiresCharging", requiresCharging).put("minBatteryPercent", 0)
            return request("plans.reviewApproval", JSONObject().put("viewHandle", view.getString("viewHandle")).put("settings", settings))
        }
        fun approve(id: String, mode: String = "automatic", requiresCharging: Boolean = false, admissions: Int = 10) = confirm(approval(id, mode, requiresCharging, admissions))
        fun reviewAction(id: String, operation: String): JSONObject = request("plans.reviewAction", JSONObject().put("viewHandle", read(id).getString("viewHandle")).put("operation", operation))
        fun action(id: String, operation: String) = confirm(reviewAction(id, operation))
        fun inspect(id: String) = request("plans.inspect", JSONObject().put("viewHandle", read(id).getString("viewHandle")))
        fun reviewStep(id: String, index: Int, draft: JSONObject): JSONObject {
            val view = read(id)
            return request("plans.reviewStep", JSONObject().put("viewHandle", view.getString("viewHandle"))
                .put("stepId", view.getJSONObject("plan").getJSONArray("steps").getJSONObject(index).getString("id"))
                .put("draft", draft).put("delayMinutes", 0).put("timingEdited", false))
        }
        fun editStep(id: String, index: Int, draft: JSONObject) = confirm(reviewStep(id, index, draft))
        fun plan(id: String = checkNotNull(selectedPlan)): JSONObject = app.features.module("automation")
            .handle("getPlan", JSONObject().put("scope", JSONObject().put("ownerId", "owner").put("spaceId", "private")).put("id", id)).getJSONObject("plan")
        fun awaitStep(id: String, index: Int, status: String) = until("step $index $status") { plan(id).getJSONArray("steps").getJSONObject(index).optString("status") == status }
        fun awaitState(id: String, status: String) = until("plan $status") { plan(id).optString("state") == status }
        fun alarm() { alarmHost.onAutomationDue(System.currentTimeMillis(), 4); advance() }
        fun openTask(id: String) {
            request("tasks.refresh")
            until("original task in owner history") { controller.state.tasks.any { it.id == id } }
            request("tasks.open", JSONObject().put("id", id))
        }
        fun save(name: String) {
            File(evidence, "$name.json").writeText(JSONObject().put("authority", "actual PocketWebBridge/Controller/Application/AutomationBook/TaskStore")
                .put("modelCalls", modelCalls.get()).put("snapshot", snapshot()).put("taskCount", app.tasks.list().length())
                .put("controllerErrors", JSONObject(controller.state.moduleErrors)).toString(2))
        }
        override fun close() {
            holds.values.forEach { it.countDown() }
            try { bridge.close(); controller.detach() }
            finally {
                try { media.close(); owner.close(); model.stop(0) }
                finally { try { ReadyApplicationFixture.closeHost(app) } finally { Security.removeProvider(keys.name) } }
            }
        }
    }

    @Test fun createReviewPauseEditResumeThenCompleteTheSameGoalWithRealRunnerFileResults() {
        Screen().use { ui ->
            val id = ui.create("每周项目整理：核实源文件，再归档交付文件", listOf(
                ui.stepDraft("核实源文件并冻结为私人成果", "reports/source.md", "# Source"),
                ui.stepDraft("归档交付文件并保留真实来源", "reports/delivery.md", "# Delivery", mapOf(0 to "completed"))
            ))
            ui.approve(id, "manual")
            val originalDue = ui.plan().getJSONArray("steps").getJSONObject(0).getLong("dueAt")
            assertEquals(0, ui.app.tasks.list().length()); assertEquals(0, ui.modelCalls.get())
            ui.save("plan-01-reviewed-original-goal-awaits-start")
            ui.action(id, "pause")
            ui.editStep(id, 0, ui.stepDraft("先核实来源，再冻结源文件的修订结果", "reports/source.md", "# Source"))
            assertEquals(originalDue, ui.plan().getJSONArray("steps").getJSONObject(0).getLong("dueAt"))
            assertFalse(ui.read(id).getJSONObject("plan").getBoolean("approvalValid"))
            ui.approve(id, "manual"); ui.action(id, "resume")
            assertEquals(0, ui.app.tasks.list().length()); assertEquals(0, ui.modelCalls.get())
            ui.action(id, "start"); ui.awaitStep(id, 0, "completed"); ui.alarm(); ui.awaitState(id, "completed")
            ui.save("plan-02-real-original-task-results-complete-goal")
            assertEquals(2, ui.app.tasks.list().length()); assertTrue(ui.modelCalls.get() in 4..12)
            val first = ui.plan().getJSONArray("steps").getJSONObject(0).getString("taskId")
            for (index in 0 until 2) {
                val taskId = ui.plan().getJSONArray("steps").getJSONObject(index).getString("taskId")
                assertTrue(ui.app.tasks.automationArtifactVerified(taskId))
                val task = ui.app.tasks.get(taskId, false)
                assertEquals(1, task.getJSONArray("operations").length())
                val events = task.getJSONArray("events")
                for (at in 0 until events.length()) assertNotEquals("user_verification", events.getJSONObject(at).optString("type"))
            }
            ui.openTask(first); assertEquals(first, ui.controller.state.requestedTaskId)
            assertTrue(ui.controller.state.moduleErrors.isEmpty())
        }
    }
}
