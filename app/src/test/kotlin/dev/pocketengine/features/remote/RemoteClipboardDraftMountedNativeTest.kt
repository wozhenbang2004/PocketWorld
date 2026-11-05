package dev.pocketengine.features.remote

import android.app.Application
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Looper
import android.os.PersistableBundle
import android.view.View
import android.widget.EditText
import androidx.activity.ComponentActivity
import dev.pocketengine.features.RemoteOwnerWorkflows
import dev.pocketengine.ui.objects
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
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowClipboardManager

/** Original clipboard approval/native reader -> owned SDK clipboard -> real SQL/Companion/Popen.
 * A focused Android editor supplies only the native read surface; Web handler selection tests are separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class, shadows = [RemoteClipboardDraftMountedNativeTest.CountingClipboard::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteClipboardDraftMountedNativeTest {
    @Implements(ClipboardManager::class)
    class CountingClipboard : ShadowClipboardManager() {
        var rawReads = 0
        @Implementation override fun getPrimaryClip(): ClipData? { rawReads++; return super.getPrimaryClip() }
    }
    private inner class Fixture : AutoCloseable {
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val native = RemotePiOwnerJourneyTest.Fixture("success")
        val activity = owner.get()
        val focused = EditText(activity)
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        val counter = Shadow.extract<CountingClipboard>(clipboard)
        var snapshot = JSONObject()
        var clipboardRequests = 0
        init { try {
            native.owner.setForeground(native.nativeOwner, false); native.owner = RemoteOwnerWorkflows(native.host, native.tasks) { }; native.owner.setForeground(activity, true)
            val slot = RemoteModule::class.java.getDeclaredMethod("slot", String::class.java, String::class.java).also { it.isAccessible = true }
            assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences("feature_secrets", 0).edit().putString(slot.invoke(null, "computer", native.base) as String, "explicit-fixture-presence-not-a-ciphertext").commit())
            snapshot = native.owner.handle(activity, "open", JSONObject().put("windowId", UUID.randomUUID().toString()).put("taskId", native.task)).getJSONObject("snapshot")
            activity.setContentView(focused); focused.requestFocus(); owner.windowFocusChanged(true)
            val root = activity.window.decorView; root.measure(View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 1200, 2000); shadowOf(Looper.getMainLooper()).idle()
        } catch (failure: Throwable) { try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }; throw failure } }
        fun action() = JSONObject().put("windowId", snapshot.getString("windowId")).put("taskId", native.task).put("reviewId", snapshot.getString("reviewId"))
        fun target(id: String, revision: Long = 0) = action().put("draftId", id).put("draftRevision", revision).put("mode", "next_turn")
        fun read(target: JSONObject): String = native.owner.clipboardDraftText(activity, target) { approval -> clipboardRequests++; RemoteDraftClipboard.read(activity, focused, approval) }
        fun append(target: JSONObject) { val original = focused.text.toString(); val imported = read(target); focused.setText(RemoteDraftClipboard.append(original, imported)) }
        fun put(sample: ClipData) { clipboard.setPrimaryClip(sample); counter.rawReads = 0 }
        fun refresh() { snapshot = native.owner.handle(activity, "refresh", action()).getJSONObject("snapshot") }
        fun save(id: String, text: String, revision: Long = 0): JSONObject {
            snapshot = native.owner.handle(activity, "saveDraft", action().put("draftId", id).put("draftRevision", revision).put("mode", "next_turn").put("text", text).put("queued", true)).getJSONObject("snapshot")
            refresh(); return native.tasks.originalRemoteDrafts(native.task).objects().single { it.getString("id") == id }
        }
        override fun close() { native.owner.setForeground(activity, false); owner.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle(); native.close() }
    }
    private fun refused(action: () -> Unit) { try { action(); fail("Original native clipboard scope must refuse this read") } catch (_: RemoteDraftClipboard.Failure) { } catch (_: SecurityException) { } }
    @Test fun explicitOnePastePreservesEditorThenOnlyOriginalSaveAndSendCreateOneContinuation() {
        Fixture().use { f ->
            val id = UUID.randomUUID().toString(); f.focused.setText("原要求"); f.put(ClipData.newPlainText("owned fixture", "LOCAL_CLIP_中文\nsecond")); val before = f.native.source(); val original = f.target(id)
            assertEquals(0, f.counter.rawReads); f.append(original); assertEquals(1, f.counter.rawReads); assertEquals("原要求\nLOCAL_CLIP_中文\nsecond", f.focused.text.toString())
            assertEquals(0, f.native.tasks.originalRemoteDrafts(f.native.task).length()); assertEquals(1, f.native.count("submit-count")); assertEquals(before.getString("runId"), f.native.source().getString("runId")); assertFalse(f.native.events.toString().contains("LOCAL_CLIP_中文"))
            refused { f.read(original) }; assertEquals(1, f.counter.rawReads)
            val saved = f.save(id, f.focused.text.toString()); assertEquals(1, f.native.count("submit-count")); assertEquals("原要求\nLOCAL_CLIP_中文\nsecond", saved.getString("text"))
            f.native.owner.handle(f.activity, "sendDraft", f.action().put("draftId", id).put("draftRevision", saved.getLong("revision"))); f.native.settled("completed")
            val after = f.native.source(); assertEquals(before.getInt("coordinatorEpoch") + 1, after.getInt("coordinatorEpoch")); assertEquals(before.getString("sessionId"), after.getString("sessionId")); assertEquals(2, f.native.count("submit-count")); assertEquals("11", RemotePiOwnerJourneyTest.utf8(f.native.root.resolve("workspace/pi-prompt-count")))
            assertEquals("LOCAL_CLIP_中文\nsecond", f.clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
    @Test fun lockedOrUnfocusedOldReadDoesNotSampleTheProvidedSystemClipboard() {
        for (locked in listOf(true, false)) Fixture().use { f ->
            f.put(ClipData.newPlainText("owned fixture", "not read while locked or unfocused")); val original = f.target(UUID.randomUUID().toString())
            if (locked) shadowOf(f.activity.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true) else f.owner.windowFocusChanged(false)
            try { refused { f.append(original) }; assertEquals(0, f.counter.rawReads); assertEquals("", f.focused.text.toString()); assertEquals(0, f.native.tasks.originalRemoteDrafts(f.native.task).length()); assertEquals(1, f.native.count("submit-count")) }
            finally { shadowOf(f.activity.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false); f.owner.windowFocusChanged(true) }
        }
    }
    @Test fun declaredSensitiveAndUriClipboardAreRefusedWithoutRawSampleOrDraftMutation() {
        for (sensitive in listOf(true, false)) Fixture().use { f ->
            f.focused.setText("keep existing")
            val clip = if (sensitive) ClipData.newPlainText("owned fixture", "PRIVATE_CLIP_SENTINEL").apply { description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) } } else ClipData.newRawUri("owned fixture", android.net.Uri.parse("content://never-opened/private"))
            f.put(clip); refused { f.append(f.target(UUID.randomUUID().toString())) }; assertEquals(0, f.counter.rawReads); assertEquals("keep existing", f.focused.text.toString()); assertEquals(0, f.native.tasks.originalRemoteDrafts(f.native.task).length()); assertFalse(f.native.events.toString().contains("PRIVATE_CLIP_SENTINEL")); assertEquals(1, f.native.count("submit-count"))
        }
    }
    @Test fun combinedLengthFailureKeepsOriginalEditorAndDoesNotTruncateOrSend() {
        Fixture().use { f ->
            f.focused.setText("old"); f.put(ClipData.newPlainText("owned fixture", "字".repeat(12000))); refused { f.append(f.target(UUID.randomUUID().toString())) }
            assertEquals(1, f.counter.rawReads); assertEquals("old", f.focused.text.toString()); assertEquals(0, f.native.tasks.originalRemoteDrafts(f.native.task).length()); assertEquals(1, f.native.count("submit-count"))
        }
    }
    @Test fun oldReviewCannotReadIntoAnotherActuallyPersistedSelectedDraft() {
        Fixture().use { f ->
            val first = f.save(UUID.randomUUID().toString(), "first persisted"); val old = f.target(first.getString("id"), first.getLong("revision"))
            f.save(UUID.randomUUID().toString(), "second persisted"); f.focused.setText("second persisted"); f.put(ClipData.newPlainText("owned fixture", "must not be read by old target callback"))
            val requests = f.clipboardRequests; refused { f.append(old) }; assertEquals(requests, f.clipboardRequests); assertEquals(0, f.counter.rawReads); assertEquals("second persisted", f.focused.text.toString()); assertEquals(2, f.native.tasks.originalRemoteDrafts(f.native.task).length()); assertEquals(1, f.native.count("submit-count"))
        }
    }
}
