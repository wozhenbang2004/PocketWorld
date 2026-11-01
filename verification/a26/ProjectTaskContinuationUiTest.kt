package dev.pocketengine.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.text.AnnotatedString
import com.sun.net.httpserver.HttpServer
import dev.pocketengine.EngineApplication
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.project.ProjectSources
import java.net.InetSocketAddress
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Current production Compose/controller and actual native rejection; the selected task is a UI fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class,
    qualifiers = "w412dp-h892dp-mdpi", shadows = [OfficialAarResources::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class ProjectTaskContinuationUiTest {
    @Test fun preparationAdmissionKeepsAnIdenticalUnsubmittedDevelopmentGoal() {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.readBytes()
                val response = JSONObject().put("choices", JSONArray().put(JSONObject()
                    .put("message", JSONObject().put("role", "assistant").put("content", "Fixture response only; no tool execution requested."))
                    .put("finish_reason", "stop"))).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.write(response)
            } finally { exchange.close() }
        }
        server.start()
        val owner = Robolectric.buildActivity(ComposeActivity::class.java)
        owner.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar); owner.setup()
        try {
            app.settings.save(JSONObject().put("baseUrl", "http://127.0.0.1:${server.address.port}/v1").put("model", "native-goal-draft-fixture").put("clearApiKey", true).put("sendScreenshots", false).put("maxSteps", 4))
            val checkout = app.dispatchOwnerAction("createDevelopmentProject", JSONObject().put("name", "Preparation draft retention fixture").put("template", "blank"))
            val local = app.features.module("local") as LocalModule
            val inspection = ProjectSources.describe(local.handle("export", JSONObject().put("checkoutId", checkout.getString("id"))))
            val activity = owner.get()
            val controller = ComposeActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }.get(activity) as PocketWorldController
            val before = app.tasks.list().length()
            // UI-only availability permits clicking the preparation entry. The real owner
            // admission, SQLite task and Runner are retained; the loopback model calls no tools.
            fun presentation(): PocketUiState {
                val source = controller.state
                val runtime = JSONObject().put("runtimeId", "managed_process_v1").put("canExecute", false).put("preparationAvailable", true)
                val features = JSONObject(source.features.toString())
                val localState = features.optJSONObject("local") ?: JSONObject()
                localState.put("projectRuntime", runtime).put("projectRuntimes", JSONArray().put(runtime)); features.put("local", localState)
                val results = source.moduleResults.toMutableMap()
                results["local.project.runtime.describe"]?.let { results["local.project.runtime.describe"] = JSONObject(it.toString()).put("canExecute", false).put("preparationAvailable", true) }
                return source.copy(features = features, moduleResults = results)
            }
            activity.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { NativeProjectAgentWorkbench(checkout, inspection, presentation(), controller) } }
            pump(activity.window.decorView)
            val text = ProjectWorkbenchGuard.preparationGoal
            assertTrue(goal(activity.window.decorView).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text)))
            pump(activity.window.decorView)
            val prepare = nodes(activity.window.decorView).first { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == "project_prepare_phone_tools" }
            assertFalse(prepare.config.contains(SemanticsProperties.Disabled)); assertTrue(prepare.config[SemanticsActions.OnClick].action!!.invoke())
            val until = System.nanoTime() + 8_000_000_000L
            while (nodes(activity.window.decorView).none { nodeText(it) == "为此项目另开任务" && it.config.contains(SemanticsActions.OnClick) } && System.nanoTime() < until) { pump(activity.window.decorView); Thread.sleep(10) }
            val receipt = controller.state.moduleResults["owner.startDevelopmentTask"]!!
            assertTrue(receipt.optString("taskId").isNotBlank())
            val task = app.tasks.get(receipt.getString("taskId"), false)
            assertEquals(text, task.getString("goal")); assertEquals(checkout.getString("id"), task.getJSONObject("developmentContext").getString("checkoutId"))
            assertEquals("This verifies admission and draft retention, not tool installation", 0, task.getJSONArray("operations").length())
            assertTrue(nodes(activity.window.decorView).first { nodeText(it) == "为此项目另开任务" && it.config.contains(SemanticsActions.OnClick) }.config[SemanticsActions.OnClick].action!!.invoke())
            pump(activity.window.decorView)
            assertEquals("Preparation must not consume even an identical unsent development goal", text, goal(activity.window.decorView).config[SemanticsProperties.EditableText].text)
            assertEquals(before + 1, app.tasks.list().length())
        } finally { server.stop(0); owner.pause().stop().destroy(); app.onTerminate(); app.features.close() }
    }

    @Test fun failedOriginalTaskContinuationKeepsItsUnsentModificationDraft() {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val owner = Robolectric.buildActivity(ComposeActivity::class.java)
        owner.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar); owner.setup()
        try {
            val activity = owner.get()
            val controller = ComposeActivity::class.java.getDeclaredField("controller").apply { isAccessible = true }.get(activity) as PocketWorldController
            // Absent from native SQLite: the real owner admission must reject this task.
            val task = taskFromJson(JSONObject().put("id", "ui-only-missing-task").put("kind", "assistant").put("actorId", "owner").put("spaceId", "private")
                .put("status", "failed").put("goal", "修复原网页").put("operations", JSONArray())
                .put("developmentContext", JSONObject().put("checkoutId", "ui-checkout").put("projectId", "ui-project").put("runtime", "managed_process_v1")))
            val observedError = "SyntaxError: UI-only original build receipt fixture"
            task.raw.put("operations", JSONArray().put(JSONObject().put("operationId", "ui-build").put("runId", "ui-run").put("tool", "project_execute").put("state", "receipt")
                .put("arguments", JSONObject().put("stepId", "build"))
                .put("receipt", JSONObject().put("binding", JSONObject().put("taskId", task.id).put("runId", "ui-run").put("operationId", "ui-build"))
                    .put("checkoutId", "ui-checkout").put("projectId", "ui-project").put("sourceDigest", "a".repeat(64)).put("intent", "build")
                    .put("status", "failed").put("receiptReceived", true).put("exitCode", 1).put("stderr", observedError).put("stdout", ""))))
            val count = app.tasks.list().length()
            activity.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { NativeProjectAgentWorkbench(JSONObject().put("id", "ui-checkout").put("projectId", "ui-project"), null, controller.state.copy(tasks = listOf(task)), controller) } }
            pump(activity.window.decorView)
            val requirement = "保留这段未被原任务接收的修改要求"
            assertTrue(draft(activity.window.decorView).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(requirement)))
            pump(activity.window.decorView)
            assertTrue(nodes(activity.window.decorView).first { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == "project_use_build_error" }.config[SemanticsActions.OnClick].action!!.invoke())
            pump(activity.window.decorView)
            val text = draft(activity.window.decorView).config[SemanticsProperties.EditableText].text
            assertTrue("The existing owner requirement remains the draft prefix", text.startsWith(requirement + "\n\n"))
            assertTrue("The original operation's error is appended", text.contains(observedError))
            pump(activity.window.decorView)
            val submit = nodes(activity.window.decorView).first { nodeText(it) == "继续原项目任务" && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(submit.config[SemanticsActions.OnClick].action!!.invoke())
            val until = System.nanoTime() + 5_000_000_000L
            while ("owner.continueDevelopmentTask" in controller.state.moduleBusy && System.nanoTime() < until) { pump(activity.window.decorView); Thread.sleep(10) }
            pump(activity.window.decorView)
            assertFalse("admission must settle as a related failure", "owner.continueDevelopmentTask" in controller.state.moduleBusy)
            assertEquals(text, draft(activity.window.decorView).config[SemanticsProperties.EditableText].text)
            assertEquals("ui-only-missing-task", controller.state.moduleResultInputs["owner.continueDevelopmentTask"]?.optString("taskId"))
            assertEquals(true, controller.state.moduleResultInputs["owner.continueDevelopmentTask"]?.opt("_failed"))
            assertEquals("failed UI admission creates no new native task", count, app.tasks.list().length())
        } finally { owner.pause().stop().destroy(); app.onTerminate(); app.features.close() }
    }

    private fun pump(view: View) { repeat(10) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        view.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(892, View.MeasureSpec.EXACTLY)); view.layout(0, 0, 412, 892)
        fun layout(current: View) { if (current is RootForTest) current.measureAndLayoutForTest(); if (current is ViewGroup) for (index in 0 until current.childCount) layout(current.getChildAt(index)) }
        layout(view)
    } }
    private fun nodes(view: View): List<SemanticsNode> = when (view) {
        is ViewRootForTest -> view.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = true).orEmpty()
        is ViewGroup -> (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun draft(view: View): SemanticsNode = nodes(view).first { it.config.contains(SemanticsActions.SetText) && nodeText(it).contains("看过结果后，继续修改什么") }
    private fun goal(view: View): SemanticsNode = nodes(view).first { it.config.contains(SemanticsActions.SetText) && nodeText(it).contains("希望做什么，或继续修改哪里") }
    private fun nodeText(node: SemanticsNode): String = if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].joinToString("") { it.text } else ""
}
