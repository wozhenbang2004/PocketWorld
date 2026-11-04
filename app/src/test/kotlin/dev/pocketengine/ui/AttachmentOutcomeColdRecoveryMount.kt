package dev.pocketengine.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import java.time.Duration
import kotlinx.coroutines.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer

/** One real mounted composition with a fixture-owned clock. Robolectric can replace
 * the main Looper between methods while the default dispatcher retains the old one. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal class AttachmentOutcomeColdRecoveryMount(activity: ComposeActivity, flow: AttachmentOutcomeCoordinator) : AutoCloseable {
    private val clock = BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + clock)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
    private val view = ComposeView(activity).also { it.setParentCompositionContext(recomposer) }
    private var nanos = 0L
    init {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        view.setContent { PocketWorldTheme { AttachmentOutcomeOverlay(flow) } }
        activity.setContentView(view)
    }
    fun frame() {
        fun layout(view: View) {
            if (view is RootForTest) view.measureAndLayoutForTest()
            if (view is ViewGroup) repeat(view.childCount) { layout(view.getChildAt(it)) }
        }
        repeat(6) {
            Snapshot.sendApplyNotifications()
            nanos += 32_000_000L
            clock.sendFrame(nanos)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }.forEach { root ->
                root.measure(View.MeasureSpec.makeMeasureSpec(1179, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2553, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 1179, 2553)
                layout(root)
                root.invalidate()
            }
            Thread.sleep(5)
        }
    }
    override fun close() {
        view.disposeComposition()
        recomposer.close()
        job.cancel()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
    }
}
