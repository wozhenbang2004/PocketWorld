package dev.pocketengine.ui

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.FeatureContext
import dev.pocketengine.features.FeatureHost
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.preview.ProjectLiveWebView
import dev.pocketengine.features.local.project.ProjectSources
import dev.pocketengine.features.local.runtime.ProjectDevSession
import dev.pocketengine.features.local.runtime.ProjectRuntime
import dev.pocketengine.features.local.runtime.RuntimeExecutionRef
import dev.pocketengine.store.TaskStore
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowApplication

/** Mounted Section and production live-preview Controller scheduling. The probe/provider are explicit fixtures:
 * no service authorization, HTTP/WS proof, background WebView or JavaScript execution is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class,
    shadows = [ProjectLivePreviewLifecycleTest.NoStartup::class, ProjectLivePreviewLifecycleTest.Probe::class, ProjectLivePreviewLifecycleTest.PageFactory::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectLivePreviewLifecycleTest {
    @Implements(EngineApplication::class)
    class NoStartup : ShadowApplication() { @Implementation fun onCreate() {} }

    private class Pending(val result: ProjectDevSession.Observed) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var observation: RunControl
    }
    @Implements(value = LocalModule::class, isInAndroidSdk = false)
    class Probe {
        @Implementation fun projectLivePreviewForHost(binding: JSONObject, executionId: String, observation: RunControl): ProjectDevSession.Observed {
            assertEquals("fixture-service", executionId)
            val current = pending
            current.observation = observation
            probes.incrementAndGet(); current.entered.countDown()
            assertTrue("test must release the delayed original probe", current.release.await(5, TimeUnit.SECONDS))
            // Deliberately return despite cancellation, exercising late callback rejection itself.
            return current.result
        }
        @Implementation fun requireProjectLivePreviewForOwner(observed: ProjectDevSession.Observed) {}
    }
    @Implements(value = ProjectLiveWebView::class, isInAndroidSdk = false)
    class PageFactory {
        companion object {
            @JvmStatic @Implementation fun create(context: Context, observed: ProjectDevSession.Observed, events: ProjectLiveWebView.Events): ProjectLiveWebView {
                pageAttempts.incrementAndGet()
                throw IllegalStateException("Controlled provider fixture; no page loaded")
            }
        }
    }
    companion object {
        private lateinit var pending: Pending
        private val probes = AtomicInteger()
        private val pageAttempts = AtomicInteger()
        private const val CHECKOUT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }
        private fun set(owner: Any, name: String, value: Any?) { field(owner, name).set(owner, value) }
        private fun observed(task: JSONObject, identity: String): ProjectDevSession.Observed {
            val payload = JSONObject().put("checkoutId", CHECKOUT).put("expectedSourceDigest", "a".repeat(64))
            val exact = JSONObject().put("feature", "local").put("command", "termux.run").put("projectId", "fixture-project")
                .put("checkoutId", CHECKOUT).put("planDigest", "b".repeat(64)).put("stepId", "serve").put("intent", "serve")
                .put("sourceDigest", "a".repeat(64)).put("requestDigest", ProjectSources.digest(payload)).put("payload", payload)
            val binding = JSONObject().put("taskId", task.getString("id")).put("runId", task.getString("runId"))
                .put("coordinatorEpoch", 1).put("operationId", "fixture-service")
            val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").let { it.isAccessible = true; it.get(null) as sun.misc.Unsafe }
            return (unsafe.allocateInstance(ProjectDevSession.Observed::class.java) as ProjectDevSession.Observed).also {
                set(it, "reference", RuntimeExecutionRef.fromHost(exact, binding))
                set(it, "identity", identity); set(it, "baseUrl", "http://127.0.0.1:12345/fixture/")
                set(it, "entryUrl", "http://127.0.0.1:12345/fixture/index.html"); set(it, "policyDigest", "c".repeat(64))
            }
        }
    }
    private class Screen : AutoCloseable {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val controller: PocketWorldController
        val task: JSONObject
        var mounted by mutableStateOf(true)
        private val io: ExecutorService
        init {
            probes.set(0); pageAttempts.set(0)
            app.tasks = TaskStore(app)
            dev.pocketengine.testutil.ReadyApplicationFixture.complete(app)
            app.features = FeatureHost { }
            val runtime = Proxy.newProxyInstance(ProjectRuntime::class.java.classLoader, arrayOf(ProjectRuntime::class.java)) { _, method, _ ->
                if (method.name == "describe") JSONObject().put("runtimeId", "fixture-runtime") else null
            } as ProjectRuntime
            app.features.register(LocalModule(FeatureContext(app, null, null) { }, null, runtime))
            task = app.tasks.createCapabilityTask("Controlled lifecycle fixture", "fixture-conversation")
                .put("runId", "fixture-run").put("coordinatorEpoch", 1).put("status", "running")
                .put("developmentContext", JSONObject().put("projectId", "fixture-project").put("checkoutId", CHECKOUT))
            // Fixture document only. No run, operation, grant, process or approved service is created.
            val values = ContentValues().apply { put("document", task.toString()) }
            database().update("tasks", values, "task_id=?", arrayOf(task.getString("id")))
            val receipt = JSONObject().put("projectExecution", JSONObject().put("coordinatorEpoch", 1).put("checkoutId", CHECKOUT))
                .put("executionReceipt", JSONObject().put("status", "running").put("devWatch", JSONObject().put("nativeSessionCreated", true)))
            task.put("operations", JSONArray().put(JSONObject().put("operationId", "fixture-service").put("runId", "fixture-run").put("state", "project_running").put("receipt", receipt)))
            controller = PocketWorldController(ComposeActivity(), app)
            set(controller, "projectWorkbench\$delegate", lazyOf(ProjectWorkbenchSession(app)))
            io = field(controller, "io").get(controller) as ExecutorService
            controller.projectLivePreviewForeground(true)
            pending = Pending(observed(task, "first"))
            owner.get().setContent { MaterialTheme { Column { if (mounted) ProjectLivePreviewSection(UiTask(task.getString("id"), "fixture", "running", "", "capabilities", 0, task), CHECKOUT, controller) } } }
            frame()
        }
        private fun database() = field(app.tasks, "db").get(app.tasks) as SQLiteDatabase
        private fun compose(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) repeat(view.childCount) { compose(view.getChildAt(it))?.let { found -> return found } }
            return null
        }
        private fun nodes(): List<SemanticsNode> {
            val result = mutableListOf<SemanticsNode>()
            fun visit(node: SemanticsNode) { result += node; node.children.forEach(::visit) }
            compose(owner.get().window.decorView)?.let { visit((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode) }
            return result
        }
        fun frame() { repeat(6) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            owner.get().window.decorView.apply { measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); layout(0, 0, measuredWidth, measuredHeight) }
        } }
        fun click() {
            val button = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == "project_open_live_preview" }
            assertNull(button.config.getOrNull(SemanticsProperties.Disabled)); assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
            assertTrue(pending.entered.await(5, TimeUnit.SECONDS)); assertTrue(controller.projectLivePreviewChecking)
        }
        fun complete() { pending.release.countDown(); io.submit {}.get(5, TimeUnit.SECONDS) }
        fun freshClick() { pending = Pending(observed(task, "fresh")); click(); complete(); frame(); assertSame(pending.result, controller.projectLivePreview); assertEquals(1, pageAttempts.get()) }
        fun assertClosed() { assertNull(controller.projectLivePreview); assertNull(controller.projectLivePreviewTaskId); assertFalse(controller.projectLivePreviewChecking) }
        override fun close() {
            pending.release.countDown(); controller.closeProjectLivePreview(); io.submit {}.get(5, TimeUnit.SECONDS)
            owner.pause().stop().destroy(); controller.detach(); shadowOf(Looper.getMainLooper()).idle()
            app.features.close(); database().close()
        }
    }
    @Test fun delayedProbeAfterStopAndResumeRequiresAnotherClick() {
        Screen().use { ui ->
            ui.click(); ui.owner.pause().stop()
            assertTrue(pending.observation.isCancelled); ui.assertClosed()
            ui.complete(); shadowOf(Looper.getMainLooper()).idle(); ui.assertClosed()
            ui.owner.start().resume(); ui.frame(); ui.assertClosed(); assertEquals(0, pageAttempts.get()); assertEquals(1, probes.get())
            ui.freshClick(); assertEquals(2, probes.get())
        }
    }
    @Test fun foregroundLossRejectsAlreadyQueuedCompletionEvenAfterResume() {
        Screen().use { ui ->
            ui.click(); ui.complete() // Original success is now queued on the main handler.
            ui.controller.projectLivePreviewForeground(false); ui.controller.projectLivePreviewForeground(true)
            ui.frame(); ui.assertClosed(); assertEquals(0, pageAttempts.get()); assertTrue(pending.observation.isCancelled)
            ui.freshClick()
        }
    }
    @Test fun publishedPreviewClosesOnStopAndNeverReopensOnResume() {
        Screen().use { ui ->
            ui.click(); ui.complete(); ui.frame(); assertSame(pending.result, ui.controller.projectLivePreview); assertEquals(1, pageAttempts.get())
            ui.owner.pause().stop(); ui.assertClosed(); ui.owner.start().resume(); ui.frame(); ui.assertClosed(); assertEquals(1, pageAttempts.get()); assertEquals(1, probes.get())
        }
    }
    @Test fun disposalCancelsPendingProbeAndBackgroundOpenDoesNotQueueAnother() {
        Screen().use { ui ->
            ui.click(); ui.mounted = false; ui.frame(); assertTrue(pending.observation.isCancelled); ui.assertClosed()
            ui.complete(); ui.frame(); ui.assertClosed(); assertEquals(0, pageAttempts.get())
            ui.controller.projectLivePreviewForeground(false)
            ui.controller.openProjectLivePreview(ui.task.getString("id"), CHECKOUT, "fixture-service")
            ui.assertClosed(); assertEquals(1, probes.get())
        }
    }
}
