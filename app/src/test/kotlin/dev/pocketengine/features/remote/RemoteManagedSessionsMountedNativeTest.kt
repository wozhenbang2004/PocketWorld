package dev.pocketengine.features.remote

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.pocketengine.ui.RemoteManagedSessionsPanel
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual original panel -> foreground native owner -> original SQL/Pi observation.
 * Native graphics/semantics only, no screenshot, vendor account, or external registry proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, qualifiers = "w400dp-h1000dp-xxhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteManagedSessionsMountedNativeTest {
    private fun compose(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) repeat(view.childCount) { compose(view.getChildAt(it))?.let { found -> return found } }
        return null
    }
    private fun nodes(): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) { result += node; node.children.forEach(::visit) }
        WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.visibility == View.VISIBLE }.forEach { root -> compose(root)?.let { visit((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).rootSemanticsNode) } }
        return result
    }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
    private inner class Fixture : AutoCloseable {
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = owner.get()
        val native = RemotePiOwnerJourneyTest.Fixture("success")
        val executor = Executors.newSingleThreadExecutor()
        val opened = mutableListOf<String>()
        var navigationAttempts = 0
        var visible by mutableStateOf(true)
        init { try {
            native.owner.setForeground(native.nativeOwner, false); native.owner.setForeground(activity, true)
            activity.setContent { MaterialTheme { if (visible) RemoteManagedSessionsPanel({ native.owner.managedSessions(activity) }, { executor.execute(it) }) { task, identity ->
                navigationAttempts++; opened += native.owner.managedSessionTask(activity, task, identity)
            } } }
            frame(); await { nodes().any { text(it).startsWith("打开原任务 ") } }
        } catch (failure: Throwable) { try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }; throw failure } }
        fun frame() { repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)); WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }.forEach { view -> view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.AT_MOST)); view.layout(0, 0, view.measuredWidth, view.measuredHeight) } } }
        fun await(predicate: () -> Boolean) { val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(6); while (!predicate() && System.nanoTime() < until) { Thread.sleep(5); frame() }; assertTrue(nodes().map(::text).toString(), predicate()) }
        fun node(label: String) = nodes().firstOrNull { text(it) == label && it.config.getOrNull(SemanticsActions.OnClick)?.action != null } ?: error("Missing managed session action $label; texts=${nodes().map(::text)}")
        fun click(label: String) { val node = node(label); assertNull(label, node.config.getOrNull(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frame() }
        fun refresh() { click("刷新本机原任务清单"); await { nodes().any { text(it) == "刷新本机原任务清单" && it.config.getOrNull(SemanticsProperties.Disabled) == null } } }
        fun openLabel() = "打开原任务 " + native.task.take(8)
        override fun close() { native.owner.setForeground(activity, false); executor.shutdownNow(); executor.awaitTermination(2, TimeUnit.SECONDS); owner.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle(); native.close() }
    }
    @Test fun realPanelListsOriginalNativeTaskAndExactClickOnlyNavigatesWithoutNewRunOrGrant() {
        Fixture().use { f ->
            val original = f.native.source(); val events = f.native.events.size
            assertTrue(nodes().any { text(it).contains("仅最后保存的本机原任务元数据") }); assertTrue(nodes().any { text(it).contains("外部已认领会话：不可用") }); assertTrue(nodes().any { text(it).contains("pi · 会话") })
            assertEquals(0, f.navigationAttempts); f.refresh(); assertEquals(0, f.navigationAttempts); f.click(f.openLabel()); assertEquals(listOf(f.native.task), f.opened)
            assertEquals(original.getString("runId"), f.native.source().getString("runId")); assertEquals(original.getString("sessionId"), f.native.source().getString("sessionId")); assertEquals(events, f.native.events.size); assertEquals(1, f.native.count("submit-count")); assertEquals(0, f.native.tasks.originalRemoteDrafts(f.native.task).length())
        }
    }
    @Test fun capturedOldSessionClickCannotRetargetTheNextActuallyContinuedRun() {
        Fixture().use { f ->
            val old = f.node(f.openLabel()).config[SemanticsActions.OnClick].action!!; val source = f.native.source()
            val review = f.native.owner.handle(f.activity, "open", JSONObject().put("windowId", UUID.randomUUID().toString()).put("taskId", f.native.task)).getJSONObject("snapshot")
            f.native.owner.handle(f.activity, "continue", JSONObject().put("windowId", review.getString("windowId")).put("taskId", f.native.task).put("reviewId", review.getString("reviewId")).put("prompt", "explicit native owner next run, not a catalog action")); f.native.settled("completed")
            assertEquals(source.getInt("coordinatorEpoch") + 1, f.native.source().getInt("coordinatorEpoch")); f.refresh(); old.invoke(); f.frame()
            assertEquals(emptyList<String>(), f.opened); assertTrue(nodes().any { text(it).contains("原任务/会话或前台范围已变化") }); assertEquals(2, f.native.count("submit-count"))
            f.click(f.openLabel()); assertEquals(listOf(f.native.task), f.opened); assertEquals(2, f.native.count("submit-count"))
        }
    }
    @Test fun backgroundOrDisposedOriginalPageCannotUseItsOldNavigationCallback() {
        Fixture().use { f ->
            val old = f.node(f.openLabel()).config[SemanticsActions.OnClick].action!!; f.native.owner.setForeground(f.activity, false); old.invoke(); f.frame(); assertEquals(emptyList<String>(), f.opened)
            val attempts = f.navigationAttempts; f.visible = false; f.frame(); old.invoke(); f.frame(); assertEquals(attempts, f.navigationAttempts); assertEquals(emptyList<String>(), f.opened); assertEquals(1, f.native.count("submit-count"))
        }
    }
}
