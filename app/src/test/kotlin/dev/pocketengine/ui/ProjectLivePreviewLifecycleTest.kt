package dev.pocketengine.ui

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.widget.FrameLayout
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.preview.ProjectLiveWebView
import dev.pocketengine.features.local.project.ProjectSources
import dev.pocketengine.features.local.runtime.ProjectDevSession
import dev.pocketengine.features.local.runtime.RuntimeExecutionRef
import dev.pocketengine.media.MediaController
import dev.pocketengine.testutil.ReadyApplicationFixture
import dev.pocketengine.ui.web.PocketWebProjectLivePreview
import dev.pocketengine.ui.web.PocketWebWindowTicket
import java.security.Provider
import java.security.Security
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

/** Production Web presentation + original Controller probe scheduling. The probe/provider and task
 * metadata are explicit fixtures: no service grant, HTTP/WS, sandbox or page execution is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class,
    shadows = [ReadyApplicationFixture.NoUnrelatedStartup::class, ProjectLivePreviewLifecycleTest.Probe::class, ProjectLivePreviewLifecycleTest.PageFactory::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProjectLivePreviewLifecycleTest {
    private val keys = object : Provider("PocketLivePreview-${UUID.randomUUID()}", 1.0, "Explicit test-only AndroidKeyStore") {
        init { put("KeyStore.AndroidKeyStore", dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) }
    }
    @Before fun installKeys() { assertTrue(Security.insertProviderAt(keys, 1) > 0) }
    @After fun removeKeys() { Security.removeProvider(keys.name) }
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
        val controller: PocketWorldController
        val native: PocketWebProjectLivePreview
        val task: JSONObject
        private val media: MediaController
        private val io: ExecutorService
        private var window = 0L
        private var page: JSONObject? = null
        init {
            probes.set(0); pageAttempts.set(0); ReadyApplicationFixture.initializeHost(app)
            val activity = Robolectric.buildActivity(ComposeActivity::class.java).get()
            media = MediaController(activity, app::publish); set(activity, "media", media)
            task = app.tasks.createCapabilityTask("Controlled lifecycle fixture", "fixture-conversation")
                .put("runId", "fixture-run").put("coordinatorEpoch", 1).put("status", "running")
                .put("developmentContext", JSONObject().put("projectId", "fixture-project").put("checkoutId", CHECKOUT).put("runtime", "managed_process_v1"))
            // Exact same synthetic source task as the former mounted test. It grants no execution.
            database().update("tasks", ContentValues().apply { put("document", task.toString()) }, "task_id=?", arrayOf(task.getString("id")))
            val receipt = JSONObject().put("projectExecution", JSONObject().put("coordinatorEpoch", 1).put("checkoutId", CHECKOUT))
                .put("executionReceipt", JSONObject().put("status", "running").put("devWatch", JSONObject().put("nativeSessionCreated", true)))
            task.put("operations", JSONArray().put(JSONObject().put("operationId", "fixture-service").put("runId", "fixture-run").put("state", "project_running").put("receipt", receipt)))
            controller = PocketWorldController(activity, app); set(controller, "projectWorkbench\$delegate", lazyOf(ProjectWorkbenchSession(app))); set(controller, "voiceForeground", true)
            io = field(controller, "io").get(controller) as ExecutorService
            native = PocketWebProjectLivePreview(controller, FrameLayout(activity),
                { PocketWebWindowTicket(window, controller.webWindowTicket()) }, { ticket -> ticket.accepts(window, controller.webWindowTicket()) }, {},
                { id, done -> assertEquals(task.getString("id"), id); done(UiTask(id, "fixture", "running", "", "capabilities", 0, JSONObject(task.toString())), null) })
            native.foreground(true); pending = Pending(observed(task, "first")); open()
        }
        private fun database() = field(app.tasks, "db").get(app.tasks) as SQLiteDatabase
        fun command(action: String, input: JSONObject): JSONObject {
            var value: JSONObject? = null; var failure: String? = null; var calls = 0
            native.dispatch(action, input) { result, error -> value = result; failure = error; calls++ }
            assertEquals(1, calls); assertNull(failure); return checkNotNull(value)
        }
        fun open() { val before = probes.get(); page = command("projectLive.open", JSONObject().put("taskId", task.getString("id"))); assertEquals(1, page!!.getJSONArray("services").length()); assertFalse(page!!.getBoolean("starting")); assertEquals(before, probes.get()) }
        fun frame() { repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)) } }
        fun click() {
            val selected = checkNotNull(page); val result = command("projectLive.show", JSONObject().put("pageHandle", selected.getString("pageHandle")).put("serviceHandle", selected.getJSONArray("services").getJSONObject(0).getString("serviceHandle")))
            assertTrue(result.getBoolean("starting")); assertTrue(pending.entered.await(5, TimeUnit.SECONDS)); assertTrue(controller.projectLivePreviewChecking)
        }
        fun complete() { pending.release.countDown(); io.submit {}.get(5, TimeUnit.SECONDS) }
        fun stop() { native.foreground(false); window++; set(controller, "voiceForeground", false) }
        fun resume() { set(controller, "voiceForeground", true); native.foreground(true) }
        fun freshClick() { open(); pending = Pending(observed(task, "fresh")); click(); complete(); frame(); assertSame(pending.result, controller.projectLivePreview); assertEquals(1, pageAttempts.get()) }
        fun assertClosed() { assertNull(controller.projectLivePreview); assertNull(controller.projectLivePreviewTaskId); assertFalse(controller.projectLivePreviewChecking); assertNull(native.snapshot()) }
        override fun close() {
            pending.release.countDown(); native.close(); io.submit {}.get(5, TimeUnit.SECONDS); controller.detach(); media.close(); shadowOf(Looper.getMainLooper()).idle(); ReadyApplicationFixture.closeHost(app)
        }
    }
    @Test fun delayedProbeAfterStopAndResumeRequiresAnotherClick() {
        Screen().use { ui ->
            ui.click(); ui.stop(); assertTrue(pending.observation.isCancelled); ui.assertClosed()
            ui.complete(); shadowOf(Looper.getMainLooper()).idle(); ui.assertClosed()
            ui.resume(); ui.frame(); ui.assertClosed(); assertEquals(0, pageAttempts.get()); assertEquals(1, probes.get())
            ui.freshClick(); assertEquals(2, probes.get())
        }
    }
    @Test fun foregroundLossRejectsAlreadyQueuedCompletionEvenAfterResume() {
        Screen().use { ui ->
            ui.click(); ui.complete() // Original success is now queued on the native main handler.
            ui.stop(); ui.resume(); ui.frame(); ui.assertClosed(); assertEquals(0, pageAttempts.get()); assertTrue(pending.observation.isCancelled)
            ui.freshClick()
        }
    }
    @Test fun publishedPreviewClosesOnStopAndNeverReopensOnResume() {
        Screen().use { ui ->
            ui.click(); ui.complete(); ui.frame(); assertSame(pending.result, ui.controller.projectLivePreview); assertEquals(1, pageAttempts.get())
            ui.stop(); ui.assertClosed(); ui.resume(); ui.frame(); ui.assertClosed(); assertEquals(1, pageAttempts.get()); assertEquals(1, probes.get())
        }
    }
    @Test fun disposalCancelsPendingProbeAndBackgroundOpenDoesNotQueueAnother() {
        Screen().use { ui ->
            ui.click(); ui.native.clear(); ui.frame(); assertTrue(pending.observation.isCancelled); ui.assertClosed()
            ui.complete(); ui.frame(); ui.assertClosed(); assertEquals(0, pageAttempts.get())
            ui.stop(); ui.controller.openProjectLivePreview(ui.task.getString("id"), CHECKOUT, "fixture-service")
            ui.assertClosed(); assertEquals(1, probes.get())
        }
    }
    @Test fun closedNativePageCannotBeReopenedWithItsRetainedReactServiceHandle() {
        Screen().use { ui ->
            val original = ui.native.snapshot()!!; ui.native.clear()
            assertThrows(IllegalStateException::class.java) { ui.command("projectLive.show", JSONObject().put("pageHandle", original.getString("pageHandle")).put("serviceHandle", original.getJSONArray("services").getJSONObject(0).getString("serviceHandle"))) }
            assertEquals(0, probes.get()); ui.assertClosed()
        }
    }
}
