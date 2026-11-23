package dev.pocketengine.ui.web

import android.os.Looper
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.OwnerSourceCheckpointContinuation
import dev.pocketengine.features.local.OwnerSourceCheckpoints
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** The actual native Files listing. A future parent must adopt it once without
 * another .open and must not send cleanup for the handle that already departed. */
internal class CheckpointFilesArrival(
    val projectId: String,
    val checkoutId: String,
    val previousFilesHandle: String,
    val filesHandle: String,
    private val listingJson: String,
    val current: () -> Boolean,
) {
    fun listing() = JSONObject(listingJson)
}

/** Issued only from the actual single native Files Page on the main thread. */
internal class CheckpointFilesDeparture(
    val ready: () -> Boolean,
    val readyOnMain: () -> Boolean,
    val prepare: (checkout: String, sibling: Boolean, live: () -> Boolean) -> PreparedCheckpointFilesLeg<CheckpointFilesArrival>,
)

internal class PreparedCheckpointFilesLeg<T>(private val enter: () -> T) {
    private val used = AtomicBoolean()
    fun install(): T {
        check(Looper.myLooper() == Looper.getMainLooper() && used.compareAndSet(false, true))
        return enter()
    }
}

/** Unmounted native A -> B -> fresh A handoff. It is not a route, a selected
 * project draft, or a command. Only explicit open and actual terminal Back may drive it. */
internal class PocketWebCheckpointFilesHandoff(
    source: SourceCheckpointFilesPin,
    leaving: CheckpointFilesDeparture,
    shown: OwnerSourceCheckpoints.Catalog,
    private val before: OwnerSourceCheckpoints.Version,
    private val selected: OwnerSourceCheckpoints.Version,
    published: OwnerSourceCheckpointContinuation.Observation,
    private val parentFast: () -> Boolean,
    private val parentMain: () -> Boolean,
    private val captureSibling: (filesHandle: String, checkout: String) -> CheckpointFilesDeparture?,
    private val captureReturned: (CheckpointFilesArrival) -> SourceCheckpointFilesPin,
) {
    private val target = requireNotNull(published.target())
    private val project = source.project
    private val checkout = source.checkout

    private interface Phase
    private class Opening(val source: SourceCheckpointFilesPin, val leaving: CheckpointFilesDeparture) : Phase
    private class InSibling(
        val arrival: CheckpointFilesArrival, val local: LocalModule,
        val registration: LocalModule.SourceCheckpointContinuationRegistration,
    ) : Phase
    private class Returning(val sibling: InSibling, val leaving: CheckpointFilesDeparture) : Phase
    private object Retired : Phase
    @Volatile private var phase: Phase = Opening(source, leaving)
    private val opened = AtomicBoolean()

    init {
        require(Looper.myLooper() == Looper.getMainLooper())
        require(published.state() == OwnerSourceCheckpointContinuation.State.PUBLISHED && parentMain() &&
            source.currentOnMain() && leaving.readyOnMain() && before != selected &&
            before.projectId() == project && selected.projectId() == project &&
            before.checkoutId() == checkout && selected.checkoutId() == checkout &&
            before.checkpointId() != selected.checkpointId() && before.storageSlot() != selected.storageSlot() &&
            shown.versions().contains(before) && shown.versions().contains(selected) &&
            target.projectId() == project && target.originatingCheckoutId() == checkout && target.checkoutId() != checkout &&
            target.checkpointId() == selected.checkpointId() && target.manifestIdentity() == selected.manifestIdentity() &&
            target.archiveSha256() == selected.archiveSha256() && target.filteredSourceDigest() == selected.filteredSourceDigest()) {
            "只打开本次原比较中已核对登记的新副本"
        }
    }

    /** Worker only, after the separate explicit Open Files click. Nothing retires
     * A on a failed or uncompleted B preparation. */
    fun prepareSibling(local: LocalModule): PreparedCheckpointFilesLeg<CheckpointFilesArrival> {
        check(Looper.myLooper() != Looper.getMainLooper() && opened.compareAndSet(false, true))
        val original = phase as? Opening ?: error("原副本窗口已离开")
        fun live() = phase === original && !Thread.currentThread().isInterrupted &&
            parentFast() && original.source.current() && original.leaving.ready()
        val window = object : OwnerSourceCheckpointContinuation.SelectionWindow {
            override fun projectId() = project
            override fun checkoutId() = checkout
            override fun requireCurrent() { check(live()) { "原版本窗口已离开" } }
            override fun requireSelectedCheckpoint(version: OwnerSourceCheckpoints.Version) {
                requireCurrent()
                check(version == selected) { "只核对本次所选的原版本" }
            }
        }
        window.requireCurrent()
        // The displayed PUBLISHED result alone does not prove B is still registered.
        val registration = local.readPublishedSourceCheckpointContinuationForOwner(window, selected, target)
        val pending = original.leaving.prepare(target.checkoutId(), true, ::live)
        registration.requireStillPublished(window)
        return PreparedCheckpointFilesLeg {
            check(live() && parentMain() && original.source.currentOnMain()) { "原项目已变化，没有打开其他副本" }
            val arrival = pending.install()
            phase = InSibling(arrival, local, registration) // A's old Files/Review/Catalog are no longer held.
            arrival
        }
    }

    sealed interface Back {
        object NotCurrent : Back
        object StockClose : Back
        class Restore(val prepare: () -> PreparedCheckpointFilesLeg<Return>) : Back
    }

    sealed interface Return {
        object OriginalUnavailable : Return
        class Files(val arrival: CheckpointFilesArrival, val scope: SourceCheckpointFilesPin?,
                    val comparison: OwnerSourceCheckpoints.Comparison?) : Return
    }

    /** Deliberate terminal exit only. A generic close ACK, page hide, or an old
     * child cleanup is not a return. The future Web owner also fences unsent text. */
    fun beginBack(filesHandle: String): Back {
        check(Looper.myLooper() == Looper.getMainLooper())
        val sibling = phase as? InSibling ?: return Back.NotCurrent
        if (sibling.arrival.filesHandle != filesHandle || !sibling.arrival.current() || !parentMain())
            return Back.NotCurrent
        val leaving = captureSibling(filesHandle, target.checkoutId())
        if (leaving == null || !leaving.readyOnMain()) {
            retire()
            return Back.StockClose // Preserve the ordinary unfinished editor/cache path; no A read or swap.
        }
        val leg = Returning(sibling, leaving)
        phase = leg
        val read = AtomicBoolean()
        return Back.Restore {
            check(Looper.myLooper() != Looper.getMainLooper() && read.compareAndSet(false, true))
            prepareOriginal(leg)
        }
    }

    private fun prepareOriginal(leg: Returning): PreparedCheckpointFilesLeg<Return> {
        fun live() = phase === leg && !Thread.currentThread().isInterrupted &&
            parentFast() && leg.sibling.arrival.current() && leg.leaving.ready()
        val window = object : OwnerSourceCheckpoints.Window {
            override fun projectId() = project
            override fun checkoutId() = checkout
            override fun requireCurrent() { check(live()) { "新副本或原项目窗口已变化" } }
        }
        fun sameOriginal(): Boolean {
            window.requireCurrent()
            return try { leg.sibling.registration.requireOriginal(window); true }
            catch (_: Exception) { window.requireCurrent(); false }
        }
        fun unavailable() = PreparedCheckpointFilesLeg<Return> {
            check(live() && parentMain() && leg.leaving.readyOnMain())
            retire()
            Return.OriginalUnavailable // Caller may use the exact B's stock close, not a newest-copy fallback.
        }
        if (!sameOriginal()) return unavailable()
        val pending = leg.leaving.prepare(checkout, false, ::live)
        val local = leg.sibling.local
        val comparison = try {
            val fresh = local.sourceCheckpointCatalogForOwner(window)
            val earlier = fresh.versions().singleOrNull { it == before }
            val later = fresh.versions().singleOrNull { it == selected }
            if (earlier != null && later != null) local.compareSourceCheckpointsForOwner(fresh, earlier, later) else null
        } catch (_: Exception) {
            window.requireCurrent(); null // Stay in verified A Files; do not substitute another comparison.
        }
        if (!sameOriginal()) return unavailable()
        return PreparedCheckpointFilesLeg {
            check(live() && parentMain() && leg.leaving.readyOnMain()) { "原项目已变化，没有打开其他副本" }
            val arrival = pending.install()
            val pin = runCatching { captureReturned(arrival) }.getOrNull()
            retire()
            Return.Files(arrival, pin, comparison.takeIf { pin != null })
        }
    }

    /** Existing foreground/document/Project retirement must also drop this held object.
     * No Files close or authority to reopen is issued here. */
    fun retire() {
        check(Looper.myLooper() == Looper.getMainLooper())
        phase = Retired
    }
}
