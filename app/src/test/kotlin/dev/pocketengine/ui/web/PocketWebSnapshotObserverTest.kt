package dev.pocketengine.ui.web

import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PocketWebSnapshotObserverTest {
    @Test fun globalWritesPublishWithoutAnAndroidComposeViewAndReuseOneReadGroup() {
        val state = mutableStateOf(0)
        val values = mutableListOf<Int>()
        lateinit var lifecycle: PocketWebSnapshotObserver
        fun publish() = lifecycle.observeReads { values += state.value }
        lifecycle = PocketWebSnapshotObserver { publish() }
        try {
            lifecycle.start()
            lifecycle.start()
            repeat(32) { publish() }
            val observer = PocketWebSnapshotObserver::class.java.getDeclaredField("observer")
                .apply { isAccessible = true }.get(lifecycle)
            val groups = SnapshotStateObserver::class.java.getDeclaredField("observedScopeMaps")
                .apply { isAccessible = true }.get(observer)
            assertEquals(1, groups.javaClass.getMethod("getSize").invoke(groups))

            // Do not manually send apply notifications: this lifecycle owns their delivery.
            state.value = 1
            state.value = 2
            assertEquals(32, values.size)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(33, values.size)
            assertEquals(2, values.last())
        } finally { lifecycle.close() }
    }

    @Test fun closeDropsQueuedInvalidationsAndCannotRestartOrRetainNewReads() {
        val state = mutableStateOf(0)
        var publications = 0
        val lifecycle = PocketWebSnapshotObserver { publications++ }
        lifecycle.start()
        lifecycle.observeReads { state.value }
        state.value = 1
        Snapshot.sendApplyNotifications()
        lifecycle.close()
        lifecycle.close()
        lifecycle.start()
        lifecycle.observeReads { fail("A closed lifecycle must not capture new state") }
        state.value = 2
        Snapshot.sendApplyNotifications()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, publications)
    }

    @Test fun retiringOneObserverDoesNotRetireAnotherBridge() {
        val state = mutableStateOf(0)
        var firstChanges = 0
        var secondChanges = 0
        val first = PocketWebSnapshotObserver { firstChanges++ }
        val second = PocketWebSnapshotObserver { secondChanges++ }
        try {
            first.start()
            second.start()
            first.observeReads { state.value }
            second.observeReads { state.value }
            first.close()
            state.value = 1
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, firstChanges)
            assertEquals(1, secondChanges)
        } finally { first.close(); second.close() }
    }
}
