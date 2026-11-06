package dev.pocketengine.ui.web

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import dev.pocketengine.EngineApplication
import dev.pocketengine.media.MediaController
import dev.pocketengine.testutil.ReadyApplicationFixture
import dev.pocketengine.ui.ComposeActivity
import dev.pocketengine.ui.PocketWorldController
import java.security.Provider
import java.security.Security
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** The real bridge and Compose observer, without mounting an unrelated WebView or Activity lifecycle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = EngineApplication::class,
    shadows = [ReadyApplicationFixture.NoUnrelatedStartup::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PocketWebBridgeObservationTest {
    private val keys = object : Provider("PocketWebObservation-${UUID.randomUUID()}", 1.0, "Explicit test-only AndroidKeyStore") {
        init { put("KeyStore.AndroidKeyStore", dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore::class.java.name) }
    }

    @Before fun installKeys() { assertTrue(Security.insertProviderAt(keys, 1) > 0) }
    @After fun removeKeys() { Security.removeProvider(keys.name) }

    /** Emission deduplication hides duplicate observers, so inspect retained observation groups too. */
    private fun observationGroups(bridge: PocketWebBridge): List<Any> {
        val lifecycle = PocketWebBridge::class.java.getDeclaredField("snapshotObserver").apply { isAccessible = true }.get(bridge)
        val observer = PocketWebSnapshotObserver::class.java.getDeclaredField("observer").apply { isAccessible = true }.get(lifecycle)
        val groups = SnapshotStateObserver::class.java.getDeclaredField("observedScopeMaps").apply { isAccessible = true }.get(observer)
        val size = groups.javaClass.getMethod("getSize").invoke(groups) as Int
        val entries = groups.javaClass.getMethod("getContent").invoke(groups) as Array<*>
        return entries.take(size).map { requireNotNull(it) }
    }

    @Test fun modelMutationKeepsBudgetPresentationReadableWithoutAdmittingNewTasks() {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        ReadyApplicationFixture.initializeHost(app)
        val activityOwner = Robolectric.buildActivity(ComposeActivity::class.java)
        val activity = activityOwner.get()
        val media = MediaController(activity, app::publish)
        ComposeActivity::class.java.getDeclaredField("media").apply { isAccessible = true }.set(activity, media)
        val controller = PocketWorldController(activity, app)
        val events = mutableListOf<JSONObject>()
        val bridge = PocketWebBridge(controller) { events += JSONObject(it.toString()) }
        val receive = PocketWorldController::class.java.getDeclaredMethod("receive", JSONObject::class.java).apply { isAccessible = true }
        fun nativeConfig(steps: Int) = receive.invoke(controller, JSONObject().put("type", "state")
            .put("payload", JSONObject().put("config", JSONObject().put("maxSteps", steps))))
        try {
            val admittedBudget = controller.newTaskToolBudget
            nativeConfig(37)
            bridge.start()
            val mutation = app.settings.modelSelection().beginMutation()
            try {
                assertThrows(IllegalStateException::class.java) { app.settings.get() }
                assertThrows(IllegalStateException::class.java) { controller.newTaskToolBudget }
                assertThrows(IllegalStateException::class.java) { controller.newTaskModelBudget }
                val state = PocketWebState.snapshot(controller).getJSONObject("state")
                assertEquals(37, state.getInt("newTaskToolBudget"))
                assertEquals(dev.pocketengine.store.TaskExecutionScope.defaultModelBudget(37), state.getInt("newTaskModelBudget"))
                val publish = PocketWebBridge::class.java.getDeclaredMethod("publishState", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                publish.invoke(bridge, false)
                assertTrue(events.last().getJSONObject("snapshot").getJSONObject("state").getBoolean("modelSelectionBusy"))
            } finally { mutation.close() }
            nativeConfig(41)
            val settled = PocketWebState.snapshot(controller).getJSONObject("state")
            assertEquals(41, settled.getInt("newTaskToolBudget"))
            assertEquals(dev.pocketengine.store.TaskExecutionScope.defaultModelBudget(41), settled.getInt("newTaskModelBudget"))
            assertEquals("Public display state must not replace actual admission settings", admittedBudget, controller.newTaskToolBudget)
        } finally {
            try { bridge.close() } finally {
                try { controller.detach() } finally {
                    try { media.close(); activityOwner.close() } finally { ReadyApplicationFixture.closeHost(app) }
                }
            }
        }
    }

    @Test fun repeatedPublicationKeepsOneObservationGroupAndCloseRetiresPendingUpdates() {
        val app = RuntimeEnvironment.getApplication() as EngineApplication
        ReadyApplicationFixture.initializeHost(app)
        val activityOwner = Robolectric.buildActivity(ComposeActivity::class.java)
        val activity = activityOwner.get()
        val media = MediaController(activity, app::publish)
        ComposeActivity::class.java.getDeclaredField("media").apply { isAccessible = true }.set(activity, media)
        val controller = PocketWorldController(activity, app)
        val events = mutableListOf<JSONObject>()
        val bridge = PocketWebBridge(controller) { events += JSONObject(it.toString()) }
        try {
            bridge.start()
            val publish = PocketWebBridge::class.java.getDeclaredMethod("publishState", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            repeat(32) {
                publish.invoke(bridge, false)
                assertEquals("Repeated publication must not retain another observation group", 1, observationGroups(bridge).size)
            }
            assertEquals("Unchanged snapshots remain deduplicated", 1, events.size)

            repeat(12) { index ->
                val notice = "Original state update $index"
                controller.showTaskNoticeWarning(notice)
                Snapshot.sendApplyNotifications()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(notice, events.last().getJSONObject("snapshot").getJSONObject("state").getString("notice"))
                assertEquals(1, observationGroups(bridge).size)
            }
            assertEquals(13, events.size)

            val beforeClose = events.size
            controller.showTaskNoticeWarning("Update queued before close")
            Snapshot.sendApplyNotifications()
            bridge.close()
            controller.showTaskNoticeWarning("Update after close")
            Snapshot.sendApplyNotifications()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Closed bridges must ignore already queued and future invalidations", beforeClose, events.size)
            for (group in observationGroups(bridge)) {
                val hasReads = group.javaClass.getDeclaredMethod("hasScopeObservations").apply { isAccessible = true }.invoke(group) as Boolean
                assertFalse("Close must release the observed controller state", hasReads)
            }
        } finally {
            try { bridge.close() } finally {
                try { controller.detach() } finally {
                    try { media.close(); activityOwner.close() } finally { ReadyApplicationFixture.closeHost(app) }
                }
            }
        }
    }
}
