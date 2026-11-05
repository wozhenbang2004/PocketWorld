package dev.pocketengine.features.remote

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import dev.pocketengine.ui.objects
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

/** Original foreground owner and SQL/Pi metadata. Web tests exercise captured menu closures before bridge dispatch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteManagedSessionsMountedNativeTest {
    private class Fixture : AutoCloseable {
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = owner.get()
        val native = RemotePiOwnerJourneyTest.Fixture("success")
        init { native.owner.setForeground(native.nativeOwner, false); native.owner.setForeground(activity, true) }
        fun read() = native.owner.managedSessions(activity)
        fun row() = read().getJSONArray("managedConnections").objects().flatMap { it.getJSONArray("contexts").objects() }.flatMap { it.getJSONArray("sessions").objects() }.single { it.getString("taskId") == native.task }
        fun open(row: JSONObject) = native.owner.managedSessionTask(activity, row.getString("taskId"), row.getString("rowIdentity"))
        override fun close() { native.owner.setForeground(activity, false); owner.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle(); native.close() }
    }
    private fun denied(action: () -> Unit) { try { action(); fail("Expected the original native scope to reject this call") } catch (_: SecurityException) { } }
    @Test fun actualCatalogAndExactIdentityOnlyNavigateWithoutNewRunOrGrant() {
        Fixture().use { f ->
            val original = f.native.source(); val events = f.native.events.size; val data = f.read()
            assertTrue(data.getBoolean("dataOnly")); assertFalse(data.getBoolean("authorizesExecution")); assertEquals("last_stored_native_metadata_not_live", data.getString("observation")); assertEquals("unavailable_no_registry_adapter", data.getJSONObject("externalClaimed").getString("state")); assertEquals("unavailable_no_registry_adapter", data.getJSONObject("externalDiscovery").getString("state"))
            val row = f.row(); assertEquals("pi", row.getString("worker")); assertEquals("native_protocol_observed", row.getString("sessionEvidence")); assertEquals(f.native.task, f.open(row))
            assertEquals(original.getString("runId"), f.native.source().getString("runId")); assertEquals(original.getString("sessionId"), f.native.source().getString("sessionId")); assertEquals(events, f.native.events.size); assertEquals(1, f.native.count("submit-count")); assertEquals(0, f.native.tasks.originalRemoteDrafts(f.native.task).length())
        }
    }
    @Test fun capturedOldIdentityCannotRetargetTheNextActuallyContinuedRun() {
        Fixture().use { f ->
            val old = f.row(); val source = f.native.source()
            val review = f.native.owner.handle(f.activity, "open", JSONObject().put("windowId", UUID.randomUUID().toString()).put("taskId", f.native.task)).getJSONObject("snapshot")
            f.native.owner.handle(f.activity, "continue", JSONObject().put("windowId", review.getString("windowId")).put("taskId", f.native.task).put("reviewId", review.getString("reviewId")).put("prompt", "explicit native owner next run, not a catalog action")); f.native.settled("completed")
            assertEquals(source.getInt("coordinatorEpoch") + 1, f.native.source().getInt("coordinatorEpoch")); val refreshed = f.row(); assertNotEquals(old.getString("rowIdentity"), refreshed.getString("rowIdentity")); denied { f.open(old) }
            assertEquals(2, f.native.count("submit-count")); assertEquals(f.native.task, f.open(refreshed)); assertEquals(2, f.native.count("submit-count"))
        }
    }
    @Test fun backgroundOrReplacedOwnerCannotUseItsOriginalNavigationIdentity() {
        Fixture().use { f ->
            val old = f.row(); f.native.owner.setForeground(f.activity, false); denied { f.open(old) }
            f.native.owner.setForeground(f.native.nativeOwner, true); denied { f.open(old) }; assertEquals(1, f.native.count("submit-count")); assertEquals(0, f.native.tasks.originalRemoteDrafts(f.native.task).length())
        }
    }
}
