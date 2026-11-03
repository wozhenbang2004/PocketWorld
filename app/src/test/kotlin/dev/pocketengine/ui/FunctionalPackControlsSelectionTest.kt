package dev.pocketengine.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.state.ToggleableState
import dev.pocketengine.features.FeatureContext
import dev.pocketengine.features.NativePluginHost
import dev.pocketengine.features.plugins.PluginsModule
import dev.pocketengine.features.plugins.distribution.FunctionalPackNativeJourneyTest
import dev.pocketengine.features.skills.SkillsModule
import dev.pocketengine.store.SecretVault
import dev.pocketengine.store.TaskStore
import java.io.ByteArrayInputStream
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Mounted original controls and original package host. The queue is held only to test delayed work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
class FunctionalPackControlsSelectionTest {
    @Before fun setup() { org.robolectric.shadows.ShadowChoreographer.setPaused(true) }
    private class Fixture : AutoCloseable {
        val app = RuntimeEnvironment.getApplication() as Application
        val tasks = TaskStore(app)
        val plugins = PluginsModule(FeatureContext(app, null, SecretVault(app)) {})
        val skills = SkillsModule(FeatureContext(app, null, SecretVault(app)) {})
        val host = NativePluginHost(app, plugins, tasks,
            { binding, control -> tasks.checkExtensionRun(binding.taskId, binding.runId, binding.epoch, control) }, {})
        val queue = ArrayDeque<Runnable>()
        private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "functional-pack-ui-worker").apply { isDaemon = true } }
        val controller = Robolectric.buildActivity(FunctionalPackFixtureActivity::class.java)
        val activity: FunctionalPackFixtureActivity
        val root: View
        private var destroyed = false
        init {
            host.mountFunctionalPacks(skills)
            controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            activity = controller.setup().get()
            root = activity.window.decorView
            activity.setContent { androidx.compose.material3.MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    FunctionalPackControls(host, { queue.add(it) }, {}, {}, {})
                }
            } }
            controller.visible()
            settle()
        }
        private fun roots(view: View): List<ViewRootForTest> = buildList {
            if (view is ViewRootForTest) add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) addAll(roots(view.getChildAt(i)))
        }
        fun nodes(): List<SemanticsNode> = roots(root).flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        fun button(label: String) = nodes().first { node -> node.config.contains(SemanticsActions.OnClick) &&
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
        fun checkboxes() = nodes().filter { it.config.contains(SemanticsProperties.ToggleableState) }
        fun click(node: SemanticsNode) { node.config[SemanticsActions.OnClick].action!!.invoke(); settle() }
        fun click(label: String) = click(button(label))
        fun settle() {
            fun layout(view: View) { if (view is RootForTest) view.measureAndLayoutForTest(); if (view is ViewGroup) for (i in 0 until view.childCount) layout(view.getChildAt(i)) }
            repeat(5) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(48))
                root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 393, 852); layout(root)
            }
        }
        fun runNext() { worker.submit(queue.removeFirst()).get(10, TimeUnit.SECONDS) }
        fun drain() { while (queue.isNotEmpty()) { runNext(); settle() } }
        fun open() { click("导入、版本与原组件记录"); drain() }
        fun inspect() {
            open(); click("选择准确功能包")
            val launch = shadowOf(activity).nextStartedActivityForResult
            val uri = Uri.parse("content://functional-pack-fixture/exact.json")
            shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(FunctionalPackNativeJourneyTest.packageBytes("ui", false)))
            shadowOf(activity).receiveResult(launch.intent, Activity.RESULT_OK, Intent().setData(uri))
            settle(); drain(); assertEquals(4, checkboxes().size)
        }
        fun ack() = checkboxes().last()
        fun destroy() { if (!destroyed) { destroyed = true; controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80)) } }
        override fun close() { destroy(); worker.shutdownNow(); host.close(); plugins.close() }
    }
    private fun assertDisabled(node: SemanticsNode) = assertTrue("Current visible choice must be reviewed first", node.config.contains(SemanticsProperties.Disabled))
    private fun assertOff(node: SemanticsNode) = assertEquals(ToggleableState.Off, node.config[SemanticsProperties.ToggleableState])

    @Test fun removingAComponentCannotReacknowledgeTheOlderFullReview() {
        Fixture().use { f ->
            f.inspect(); f.click(f.checkboxes()[2]) // Remove the dependent recipe, keeping independent data + transform.
            assertDisabled(f.ack()); f.click(f.ack()); assertOff(f.ack())
            f.click("安装为停用组件，不运行"); assertTrue(f.queue.isEmpty()); assertEquals(0, f.tasks.list().length())
            f.click("重新审阅仅所选独立组件及依赖"); f.drain()
            assertFalse(f.ack().config.contains(SemanticsProperties.Disabled)); assertOff(f.ack())
            f.click(f.ack()); f.click("仅安装已审所选组件，整包仍部分完成"); f.drain()
            assertEquals(0, f.skills.snapshot().getJSONArray("skills").length())
            assertEquals(1, f.plugins.snapshot().getJSONArray("plugins").length()); assertEquals(1, f.tasks.list().length())
        }
    }
    @Test fun addingAComponentCannotReacknowledgeTheOlderSubsetReview() {
        Fixture().use { f ->
            f.inspect(); f.click(f.checkboxes()[2]); f.click("重新审阅仅所选独立组件及依赖"); f.drain()
            f.click(f.checkboxes()[2]) // Add recipe back without applying the new selection.
            assertDisabled(f.ack()); f.click(f.ack()); assertOff(f.ack())
            f.click("仅安装已审所选组件，整包仍部分完成"); assertTrue(f.queue.isEmpty()); assertEquals(0, f.tasks.list().length())
            f.click(f.checkboxes()[2]); f.click(f.ack()); f.click("仅安装已审所选组件，整包仍部分完成"); f.drain()
            assertEquals(0, f.skills.snapshot().getJSONArray("skills").length())
            assertEquals(1, f.plugins.snapshot().getJSONArray("plugins").length())
            assertEquals(1, f.tasks.list().length())
        }
    }
    @Test fun aPreviouslyEnabledSubmitCallbackRechecksTheLiveChoiceBeforeQueueing() {
        Fixture().use { f ->
            f.inspect(); f.click(f.ack())
            val previouslyEnabled = f.button("安装为停用组件，不运行").config[SemanticsActions.OnClick].action!!
            f.click(f.checkboxes()[2]); previouslyEnabled.invoke(); f.settle()
            assertTrue("No stale native submission may enter the worker queue", f.queue.isEmpty()); assertEquals(0, f.tasks.list().length())
        }
    }
    @Test fun queuedSubmitStillRequiresTheOriginalPageInTheForeground() {
        Fixture().use { f ->
            f.inspect(); f.click(f.ack()); f.click("安装为停用组件，不运行")
            assertEquals(1, f.queue.size); f.controller.pause(); f.drain()
            assertEquals(0, f.tasks.list().length()); assertEquals(0, f.skills.snapshot().getJSONArray("skills").length())
        }
    }
    @Test fun aReviewReplyAfterDisposalDoesNotRestoreConsentOrInstallAnything() {
        Fixture().use { f ->
            f.inspect(); f.click(f.checkboxes()[2]); f.click("重新审阅仅所选独立组件及依赖")
            f.runNext() // The exact new native review exists, but its UI reply is still pending.
            f.destroy()
            assertTrue(f.queue.isEmpty()); assertEquals(0, f.tasks.list().length()); assertEquals(0, f.skills.snapshot().getJSONArray("skills").length())
        }
    }
    @Test fun uninstallReviewStillAllowsExplicitAcknowledgementAndExactRemoval() {
        Fixture().use { f ->
            f.host.newFunctionalPackPage().use { page ->
                page.setForeground(true); val review = page.inspect(ByteArrayInputStream(FunctionalPackNativeJourneyTest.packageBytes("remove", false)))
                page.submit(review, review.digest)
            }
            f.open(); f.click("审阅包卸载与保留项"); f.drain()
            assertEquals(1, f.checkboxes().size); assertFalse(f.ack().config.contains(SemanticsProperties.Disabled))
            f.click(f.ack()); f.click("仅卸载原包确认组件"); f.drain()
            assertEquals(0, f.skills.snapshot().getJSONArray("skills").length()); assertEquals(0, f.plugins.snapshot().getJSONArray("plugins").length())
            assertEquals(2, f.tasks.list().length())
        }
    }
}
class FunctionalPackFixtureActivity : ComponentActivity()
