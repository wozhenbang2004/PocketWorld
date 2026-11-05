package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns Compose read tracking and global write notifications for a host without AndroidComposeView.
 * This is the bridge lifetime, not a document/window authorization or feature review lifetime.
 */
internal class PocketWebSnapshotObserver(private val onChanged: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val applyScheduled = AtomicBoolean(false)
    private var started = false
    private var closed = false
    private var releaseGlobalObserver: (() -> Unit)? = null
    private val observer = SnapshotStateObserver { callback ->
        main.post { if (started && !closed) callback() }
    }
    // Compose groups reads by callback identity; every publication reuses this one group.
    private val onStateChanged: (PocketWebSnapshotObserver) -> Unit = { it.onChanged() }

    fun start() {
        if (closed || started) return
        check(Looper.myLooper() == Looper.getMainLooper())
        started = true
        observer.start()
        val handle = Snapshot.registerGlobalWriteObserver {
            if (applyScheduled.compareAndSet(false, true)) main.post {
                applyScheduled.set(false)
                if (started && !closed) Snapshot.sendApplyNotifications()
            }
        }
        releaseGlobalObserver = { handle.dispose() }
    }

    fun observeReads(block: () -> Unit) {
        if (!started || closed) return
        observer.observeReads(this, onStateChanged, block)
    }

    fun close() {
        if (closed) return
        closed = true
        started = false
        releaseGlobalObserver?.invoke()
        releaseGlobalObserver = null
        observer.stop()
        observer.clear()
    }
}
