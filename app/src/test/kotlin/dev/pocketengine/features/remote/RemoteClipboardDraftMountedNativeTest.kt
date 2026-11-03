package dev.pocketengine.features.remote

import android.app.Application
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Looper
import android.os.PersistableBundle
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.pocketengine.features.RemoteOwnerWorkflows
import dev.pocketengine.ui.RemoteDraftPanel
import java.time.Duration
import java.util.UUID
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowClipboardManager

/** Original panel -> private native read -> owned Robolectric clipboard -> original
 * SQL/HTTP/Companion/Popen. No real user clipboard, vendor/account, pixels or visual proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, qualifiers = "w400dp-h1000dp-xxhdpi", shadows = [RemoteClipboardDraftMountedNativeTest.CountingClipboard::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteClipboardDraftMountedNativeTest {
    @Implements(ClipboardManager::class)
    class CountingClipboard : ShadowClipboardManager() {
        var rawReads = 0
        @Implementation override fun getPrimaryClip(): ClipData? { rawReads++; return super.getPrimaryClip() }
    }
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
        val native = RemotePiOwnerJourneyTest.Fixture("success")
        val activity = owner.get()
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        val counter = Shadow.extract<CountingClipboard>(clipboard)
        var snapshot by mutableStateOf(JSONObject())
        var clipboardRequests = 0
        init { try {
            native.owner.setForeground(native.nativeOwner, false)
            native.owner = RemoteOwnerWorkflows(native.host, native.tasks) { }
            native.owner.setForeground(activity, true)
            val slot = RemoteModule::class.java.getDeclaredMethod("slot", String::class.java, String::class.java).also { it.isAccessible = true }
            // The same explicitly synthetic provider/presence setup as the original queue fixture.
            assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences("feature_secrets", 0).edit().putString(slot.invoke(null, "computer", native.base) as String, "explicit-fixture-presence-not-a-ciphertext").commit())
            snapshot = native.owner.handle(activity, "open", JSONObject().put("windowId", UUID.randomUUID().toString()).put("taskId", native.task)).getJSONObject("snapshot")
            owner.get().setContent {
                val focused = LocalView.current
                MaterialTheme { Column {
                    RemoteDraftPanel(snapshot, snapshot.optBoolean("fresh"), clipboard = { id, revision, mode ->
                        clipboardRequests++
                        native.owner.clipboardDraftText(activity, action().put("draftId", id).put("draftRevision", revision).put("mode", mode)) { approval -> RemoteDraftClipboard.read(activity, focused, approval) }
                    }) { command, payload ->
                        val input = action(); payload.keys().forEach { input.put(it, payload.get(it)) }
                        snapshot = native.owner.handle(activity, command, input).getJSONObject("snapshot")
                    }
                } }
            }
            frame(); owner.windowFocusChanged(true); frame()
        } catch (failure: Throwable) { try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }; throw failure } }
        fun action() = JSONObject().put("windowId", snapshot.getString("windowId")).put("taskId", native.task).put("reviewId", snapshot.getString("reviewId"))
        fun frame() { repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)); WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }.forEach { view -> view.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.AT_MOST)); view.layout(0, 0, view.measuredWidth, view.measuredHeight) } } }
        fun node(label: String) = nodes().firstOrNull { text(it) == label && it.config.getOrNull(SemanticsActions.OnClick)?.action != null } ?: error("Missing original clipboard action $label; texts=${nodes().map(::text)}")
        fun click(label: String) { val node = node(label); assertNull(label, node.config.getOrNull(SemanticsProperties.Disabled)); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); frame() }
        fun edit(value: String) { val editor = nodes().first { it.config.getOrNull(SemanticsActions.SetText)?.action != null }; assertTrue(editor.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); frame() }
        fun editor() = nodes().first { it.config.getOrNull(SemanticsProperties.EditableText) != null }.config[SemanticsProperties.EditableText].text
        fun put(sample: ClipData) { clipboard.setPrimaryClip(sample); counter.rawReads = 0 }
        fun refresh() { snapshot = native.owner.handle(activity, "refresh", action()).getJSONObject("snapshot"); frame() }
        fun seed(value: String) { snapshot = native.owner.handle(activity, "saveDraft", action().put("draftId", UUID.randomUUID().toString()).put("draftRevision", 0).put("mode", "next_turn").put("text", value).put("queued", true)).getJSONObject("snapshot"); refresh() }
        override fun close() { native.owner.setForeground(activity, false); owner.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle(); native.close() }
    }
    private val paste = "追加本机剪贴板一次（仅填草稿）"
    @Test fun explicitOnePastePreservesEditorThenOnlyOriginalSaveAndSendCreateOneContinuation() {
        Fixture().use { f ->
            f.edit("原要求"); f.put(ClipData.newPlainText("owned fixture", "LOCAL_CLIP_中文\nsecond")); val before = f.native.source()
            assertEquals(0, f.counter.rawReads); f.click(paste); assertEquals(1, f.counter.rawReads); assertEquals("原要求\nLOCAL_CLIP_中文\nsecond", f.editor())
            assertEquals(0, f.native.tasks.originalRemoteDrafts(f.native.task).length()); assertEquals(1, f.native.count("submit-count")); assertEquals(before.getString("runId"), f.native.source().getString("runId")); assertFalse(f.native.events.toString().contains("LOCAL_CLIP_中文"))
            assertNotNull(f.node(paste).config.getOrNull(SemanticsProperties.Disabled)); f.click("保存并排队"); assertEquals(1, f.native.count("submit-count")); val saved = f.native.tasks.originalRemoteDrafts(f.native.task).getJSONObject(0); assertEquals("原要求\nLOCAL_CLIP_中文\nsecond", saved.getString("text"))
            f.refresh(); f.click("明确发送这条并继续原会话"); f.native.settled("completed"); val after = f.native.source(); assertEquals(before.getInt("coordinatorEpoch") + 1, after.getInt("coordinatorEpoch")); assertEquals(before.getString("sessionId"), after.getString("sessionId")); assertEquals(2, f.native.count("submit-count")); assertEquals("11", RemotePiOwnerJourneyTest.utf8(f.native.root.resolve("workspace/pi-prompt-count")))
            assertEquals("LOCAL_CLIP_中文\nsecond", f.clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
    @Test fun lockedOrUnfocusedOldClickDoesNotReadTheProvidedSystemClipboard() {
        for (locked in listOf(true, false)) Fixture().use { f ->
            f.put(ClipData.newPlainText("owned fixture", "not read while locked or unfocused")); val old = f.node(paste).config[SemanticsActions.OnClick].action!!
            if (locked) shadowOf(f.activity.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true) else f.owner.windowFocusChanged(false)
            try { old.invoke(); f.frame(); assertEquals(0, f.counter.rawReads); assertEquals("", f.editor()); assertEquals(0, f.native.tasks.originalRemoteDrafts(f.native.task).length()); assertEquals(1, f.native.count("submit-count")) }
            finally { shadowOf(f.activity.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false); f.owner.windowFocusChanged(true) }
        }
    }
    @Test fun declaredSensitiveAndUriClipboardAreRefusedWithoutRawSampleOrDraftMutation() {
        for (sensitive in listOf(true, false)) Fixture().use { f ->
            f.edit("keep existing")
            val clip = if (sensitive) ClipData.newPlainText("owned fixture", "PRIVATE_CLIP_SENTINEL").apply { description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) } } else ClipData.newRawUri("owned fixture", android.net.Uri.parse("content://never-opened/private"))
            f.put(clip); f.click(paste); assertEquals(0, f.counter.rawReads); assertEquals("keep existing", f.editor()); assertEquals(0, f.native.tasks.originalRemoteDrafts(f.native.task).length()); assertFalse(f.native.events.toString().contains("PRIVATE_CLIP_SENTINEL")); assertEquals(1, f.native.count("submit-count"))
        }
    }
    @Test fun combinedLengthFailureKeepsOriginalEditorAndDoesNotTruncateOrSend() {
        Fixture().use { f ->
            f.edit("old"); f.put(ClipData.newPlainText("owned fixture", "字".repeat(12000))); f.click(paste); assertEquals(1, f.counter.rawReads); assertEquals("old", f.editor()); assertTrue(nodes().any { text(it).contains("追加后超过12000") }); assertEquals(0, f.native.tasks.originalRemoteDrafts(f.native.task).length()); assertEquals(1, f.native.count("submit-count"))
        }
    }
    @Test fun oldPasteNodeCannotReadIntoAnotherActuallyPersistedSelectedDraft() {
        Fixture().use { f ->
            f.seed("first persisted"); f.seed("second persisted"); f.click("待明确发送 · first persisted"); f.put(ClipData.newPlainText("owned fixture", "must not be read by old target callback")); val old = f.node(paste).config[SemanticsActions.OnClick].action!!
            f.click("待明确发送 · second persisted"); val requests = f.clipboardRequests; old.invoke(); f.frame(); assertEquals(requests, f.clipboardRequests); assertEquals(0, f.counter.rawReads); assertEquals("second persisted", f.editor()); assertEquals(2, f.native.tasks.originalRemoteDrafts(f.native.task).length()); assertEquals(1, f.native.count("submit-count"))
        }
    }
}
