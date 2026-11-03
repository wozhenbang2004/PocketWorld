package dev.pocketengine.features.remote

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.pocketengine.ui.RemoteProgressPanel
import java.util.UUID
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

/** Original progress UI -> original SQL/owner/HTTP/Companion/public synthetic CLI.
 * Semantics only: no pixels, real account/inference, private reasoning or child control. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, qualifiers = "w400dp-h1000dp-xxhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteCodexChildProgressMountedNativeTest {
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
        val native = RemoteEventCursorJourneyTest.Fixture("codex_app_server", true)
        var snapshot by mutableStateOf(JSONObject())
        init { try {
            native.owner.setForeground(native.nativeOwner, false); native.owner.setForeground(activity, true)
            snapshot = native.owner.handle(activity, "open", JSONObject().put("windowId", UUID.randomUUID().toString()).put("taskId", native.task)).getJSONObject("snapshot")
            repeat(5) { if (snapshot.optJSONObject("eventRead")?.optBoolean("hasMore") == true) snapshot = native.owner.handle(activity, "refresh", action()).getJSONObject("snapshot") }
            assertEquals(snapshot.toString(), "confirmed", snapshot.getString("eventReadStatus")); assertFalse(snapshot.getJSONObject("eventRead").getBoolean("hasMore"))
            activity.setContent { MaterialTheme { Column { RemoteProgressPanel(snapshot) } } }; frame()
        } catch (failure: Throwable) { try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }; throw failure } }
        fun action() = JSONObject().put("windowId", snapshot.getString("windowId")).put("taskId", native.task).put("reviewId", snapshot.getString("reviewId"))
        // Popup position observation can keep an immediate main queue nonempty. Bounded
        // one-task stepping, not idle-until-empty/disabled tracing, exercises the real menu.
        fun frame() { repeat(48) { shadowOf(Looper.getMainLooper()).runOneTask(); WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }.forEach { view -> view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.AT_MOST)); view.layout(0, 0, view.measuredWidth, view.measuredHeight) } } }
        fun node(label: String) = nodes().firstOrNull { text(it) == label && it.config.getOrNull(SemanticsActions.OnClick)?.action != null } ?: error("Missing original child filter $label; texts=${nodes().map(::text)}")
        fun click(label: String) { val node = node(label); assertNull(label, node.config.getOrNull(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frame() }
        override fun close() { native.owner.setForeground(activity, false); owner.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle(); RemoteCodexChildOwnerJourneyTest.close(native) }
    }
    @Test fun realNativeTwoChildDropdownSeparatesPublicObservationsWithoutOriginalApprovalOrRunEffects() {
        Fixture().use { f ->
            val original = f.native.source(); val cursor = f.native.tasks.originalRemoteEventRead(f.native.task).getLong("nextSequence")
            f.click("全部原来源"); f.click("Codex子来源 codex-child-a")
            var shown = nodes().joinToString("\n", transform = ::text); assertTrue(shown, shown.contains("PUBLIC codex-child-a")); assertFalse(shown, shown.contains("PUBLIC codex-child-b")); assertTrue(shown.contains("公开增量观察，不代表完整消息")); assertFalse(shown.contains("NEVER_EXPOSE_CODEX_CHILD_THINKING"))
            f.click("Codex子来源 codex-child-a"); f.click("Codex子来源 codex-child-b"); shown = nodes().joinToString("\n", transform = ::text); assertTrue(shown.contains("PUBLIC codex-child-b")); assertFalse(shown.contains("PUBLIC codex-child-a"))
            f.click("Codex子来源 codex-child-b"); f.click("主／未标记来源"); shown = nodes().joinToString("\n", transform = ::text); assertFalse(shown.contains("PUBLIC codex-child-a")); assertFalse(shown.contains("PUBLIC codex-child-b"))
            assertEquals(original.getString("runId"), f.native.source().getString("runId")); assertEquals(1, f.native.source().getJSONArray("pendingApprovals").length()); assertEquals(cursor, f.native.tasks.originalRemoteEventRead(f.native.task).getLong("nextSequence")); assertEquals(1, f.native.count("submit-count")); assertEquals(0, f.native.count("approval-post-count")); assertEquals(0, f.native.count("question-post-count"))
        }
    }
    @Test fun unverifiedVersionInOwnedDisplayCopyIsNotReclassifiedAsParentOrErasedFromOriginalSql() {
        Fixture().use { f ->
            val saved = f.native.tasks.originalRemoteEventRead(f.native.task)
            f.snapshot = JSONObject(f.snapshot.toString()).apply { getJSONObject("eventRead").getJSONObject("scope").put("nativeRuntimeVersion", "unknown") }; f.frame()
            val shown = nodes().joinToString("\n", transform = ::text); assertTrue(shown, shown.contains("来源无法核对")); assertFalse(shown.contains("PUBLIC codex-child-a")); assertFalse(shown.contains("PUBLIC codex-child-b")); assertFalse(shown.contains("NEVER_EXPOSE_CODEX_CHILD_THINKING"))
            val retained = f.native.tasks.originalRemoteEventRead(f.native.task); assertEquals(saved.getString("generation"), retained.getString("generation")); assertEquals(saved.getLong("nextSequence"), retained.getLong("nextSequence")); assertTrue(retained.toString().contains("codex-child-a")); assertEquals(1, f.native.count("submit-count")); assertEquals(0, f.native.count("approval-post-count"))
        }
    }
}
