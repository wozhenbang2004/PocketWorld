package dev.pocketengine.features.remote

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import dev.pocketengine.ui.objects
import dev.pocketengine.ui.web.PocketWebRemoteTasks
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
import org.robolectric.annotation.LooperMode

/** Original SQL/owner/HTTP/Companion observations through the production Web projection. No child control or real-model claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteCodexChildProgressMountedNativeTest {
    private class Fixture : AutoCloseable {
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = owner.get()
        val native = RemoteEventCursorJourneyTest.Fixture("codex_app_server", true)
        var snapshot = JSONObject()
        init { try {
            native.owner.setForeground(native.nativeOwner, false); native.owner.setForeground(activity, true)
            snapshot = native.owner.handle(activity, "open", JSONObject().put("windowId", UUID.randomUUID().toString()).put("taskId", native.task)).getJSONObject("snapshot")
            repeat(5) { if (snapshot.optJSONObject("eventRead")?.optBoolean("hasMore") == true) snapshot = native.owner.handle(activity, "refresh", action()).getJSONObject("snapshot") }
            assertEquals(snapshot.toString(), "confirmed", snapshot.getString("eventReadStatus")); assertFalse(snapshot.getJSONObject("eventRead").getBoolean("hasMore"))
        } catch (failure: Throwable) { try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }; throw failure } }
        fun action() = JSONObject().put("windowId", snapshot.getString("windowId")).put("taskId", native.task).put("reviewId", snapshot.getString("reviewId"))
        override fun close() { native.owner.setForeground(activity, false); owner.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle(); RemoteCodexChildOwnerJourneyTest.close(native) }
    }
    @Test fun nativeWebProjectionSeparatesTwoPublicChildrenWithoutApprovalOrRunEffects() {
        Fixture().use { f ->
            val original = f.native.source(); val cursor = f.native.tasks.originalRemoteEventRead(f.native.task).getLong("nextSequence")
            val projected = PocketWebRemoteTasks.presentSnapshot(f.snapshot)
            val events = projected.getJSONObject("progress").getJSONArray("events").objects()
            val children = events.filter { it.getJSONObject("source").optString("kind") == "child" }
            val childA = children.filter { it.getJSONObject("source").optString("nativeChildThreadId") == "codex-child-a" }.joinToString()
            val childB = children.filter { it.getJSONObject("source").optString("nativeChildThreadId") == "codex-child-b" }.joinToString()
            assertTrue(childA.contains("PUBLIC codex-child-a")); assertFalse(childA.contains("PUBLIC codex-child-b")); assertTrue(childB.contains("PUBLIC codex-child-b")); assertFalse(childB.contains("PUBLIC codex-child-a"))
            assertTrue(childA.contains("received_public_delta_not_complete_message")); assertFalse(projected.toString().contains("NEVER_EXPOSE_CODEX_CHILD_THINKING"))
            val parent = events.filter { it.getJSONObject("source").optString("kind") != "child" }.joinToString(); assertFalse(parent.contains("PUBLIC codex-child-a")); assertFalse(parent.contains("PUBLIC codex-child-b"))
            children.forEach { assertFalse(it.getJSONObject("source").getBoolean("executionAuthorized")); assertFalse(it.getJSONObject("source").getBoolean("childSessionControl")) }
            assertEquals(original.getString("runId"), f.native.source().getString("runId")); assertEquals(1, f.native.source().getJSONArray("pendingApprovals").length()); assertEquals(cursor, f.native.tasks.originalRemoteEventRead(f.native.task).getLong("nextSequence")); assertEquals(1, f.native.count("submit-count")); assertEquals(0, f.native.count("approval-post-count")); assertEquals(0, f.native.count("question-post-count"))
        }
    }
    @Test fun unverifiedVersionInOwnedDisplayCopyIsNotReclassifiedAsParentOrErasedFromOriginalSql() {
        Fixture().use { f ->
            val saved = f.native.tasks.originalRemoteEventRead(f.native.task)
            val copy = JSONObject(f.snapshot.toString()).apply { getJSONObject("eventRead").getJSONObject("scope").put("nativeRuntimeVersion", "unknown") }
            val projected = PocketWebRemoteTasks.presentSnapshot(copy); val shown = projected.getJSONObject("progress").toString()
            assertTrue(projected.getJSONObject("progress").getInt("unconfirmedSources") > 0); assertFalse(shown.contains("PUBLIC codex-child-a")); assertFalse(shown.contains("PUBLIC codex-child-b")); assertFalse(shown.contains("NEVER_EXPOSE_CODEX_CHILD_THINKING"))
            val retained = f.native.tasks.originalRemoteEventRead(f.native.task); assertEquals(saved.getString("generation"), retained.getString("generation")); assertEquals(saved.getLong("nextSequence"), retained.getLong("nextSequence")); assertTrue(retained.toString().contains("codex-child-a")); assertEquals(1, f.native.count("submit-count")); assertEquals(0, f.native.count("approval-post-count"))
        }
    }
}
