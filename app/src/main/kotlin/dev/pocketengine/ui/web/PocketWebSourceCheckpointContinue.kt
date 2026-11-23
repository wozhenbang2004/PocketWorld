package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.OwnerSourceCheckpointContinuation
import dev.pocketengine.features.local.OwnerSourceCheckpointContinuation.Observation
import dev.pocketengine.features.local.OwnerSourceCheckpointContinuation.Review
import dev.pocketengine.features.local.OwnerSourceCheckpointContinuation.State
import dev.pocketengine.features.local.OwnerSourceCheckpointContinuation.Target
import dev.pocketengine.features.local.OwnerSourceCheckpoints
import dev.pocketengine.features.local.OwnerSourceCheckpoints.Version
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Issued by one exact retained read or a positive Compare; never holds paths or an old editor. */
internal class SourceCheckpointContinueSelection(
    val viewHandle: String, val filesHandle: String, val visitId: String, val shelfHandle: String,
    val catalogId: String, val selectionId: String, val name: String, val local: LocalModule,
    val files: SourceCheckpointFilesPin, val catalog: OwnerSourceCheckpoints.Catalog,
    val before: Version?, val selected: Version,
    val current: () -> Boolean, val currentOnMain: () -> Boolean,
) {
    val ticketField = if (before == null) "selectionId" else "comparisonId"
    fun bind(out: JSONObject) = out.put("viewHandle", viewHandle).put("filesHandle", filesHandle)
        .put("visitId", visitId).put("shelfHandle", shelfHandle).put("catalogId", catalogId).put(ticketField, selectionId)
        .put("scope", JSONObject().put("projectId", files.project).put("checkoutId", files.checkout).put("projectName", name))
}

/** Exact selection, confirmation, registration and Files navigation are separate
 * native actions; none is restored from renderer state. */
internal class PocketWebSourceCheckpointContinue(
    private val controller: PocketWorldController,
    private val shelf: PocketWebSourceCheckpoints,
    private val captureHandoff: (SourceCheckpointContinueSelection, Observation) -> PocketWebCheckpointFilesHandoff,
) {
    companion object {
        private const val PREFIX = "projects.development.files.checkpoints.continue."
        private const val SELECT = PREFIX + "select"
        private const val REVIEW = PREFIX + "review"
        const val CONFIRM = PREFIX + "confirm"
        private const val DISMISS = PREFIX + "dismiss"
        private const val QUERY = PREFIX + "query"
        private const val OPEN = PREFIX + "openFiles"
        private const val RETURN = PREFIX + "returnFiles"
        val COMMANDS = setOf(SELECT, REVIEW, CONFIRM, DISMISS, QUERY, OPEN, RETURN)
        fun mayChangeOriginalWindow(action: String) = action == CONFIRM || action == OPEN || action == RETURN
        private val base = setOf("viewHandle", "filesHandle", "visitId", "shelfHandle", "catalogId")
        private val uuid = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
        private const val WAIT_MS = 120_000L
        private const val MAX_ATTEMPTS = 160 // four fixed versions for each of up to forty checkouts.
        private const val LOST = "原私人副本窗口已变化，没有改用其他版本。"
        private const val UNKNOWN = "新副本登记结果待核对。请只查本次原记录，不要再次创建。"
        // Main-thread only; a warm Activity replacement must not turn an
        // uncertain affirmative request into permission to create a second time.
        private val attempted = WeakHashMap<LocalModule, MutableMap<Version, Attempt>>()
    }
    private class Attempt(val id: String, val before: Version?, val target: Target)
    private class Flight(val until: Long, val onMain: () -> Boolean, val done: (JSONObject?, String?) -> Unit,
                         val lost: String, val retire: () -> Unit) {
        lateinit var timer: Runnable
    }
    private data class Publication(val handle: String, val scope: SourceCheckpointContinueSelection, val observed: Observation)
    private data class Transfer(val id: String, val view: String, val local: LocalModule,
                                val handoff: PocketWebCheckpointFilesHandoff, val arrival: CheckpointFilesArrival)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var flight: Flight? = null
    @Volatile private var reviewed: ContinueWindow? = null
    private var published: Publication? = null
    private var transfer: Transfer? = null

    private inner class ContinueWindow(val source: SourceCheckpointContinueSelection) : OwnerSourceCheckpointContinuation.Window {
        private val entry = AtomicInteger() // 0 review; 1 affirmative; 2 commit entered; 3 retired.
        val until = SystemClock.elapsedRealtime() + WAIT_MS
        @Volatile var offered: Review? = null
        @Volatile var affirmation: Flight? = null
        override fun projectId() = source.files.project
        override fun checkoutId() = source.files.checkout
        override fun requireCurrent() {
            val pending = affirmation
            check(reviewed === this && entry.get() != 3 && source.current() && !Thread.currentThread().isInterrupted &&
                (pending == null || active(pending))) { LOST }
        }
        override fun requireSelectedCheckpoint(version: Version) {
            requireCurrent()
            check(version == source.selected) { "只能继续这次核对的所选版" }
        }
        fun confirm(owner: Flight) {
            check(Looper.myLooper() == main.looper && entry.compareAndSet(0, 1))
            affirmation = owner
        }
        override fun requireConfirmedContinue(reviewId: String, version: Version, target: Target) {
            requireSelectedCheckpoint(version)
            val offer = offered
            check(affirmation?.let(::active) == true && entry.get() in 1..2 && offer != null &&
                offer.id() == reviewId && offer.selected() == version && offer.target() == target) { "原核对没有这次明确确认" }
        }
        override fun atConfirmedContinue(reviewId: String, version: Version, target: Target, commit: OwnerSourceCheckpoints.Commit) {
            check(Looper.myLooper() != main.looper)
            requireConfirmedContinue(reviewId, version, target)
            // Retirement and the final one-use admission race on this permit.
            // If admission wins, leaving is not a rollback or a known negative.
            check(entry.compareAndSet(1, 2)) { LOST }
            requireConfirmedContinue(reviewId, version, target)
            commit.enter() // Same worker, never the Save main-thread fence or a Main wait under Local.
        }
        fun close() { entry.set(3); offered?.close(); offered = null }
    }

    private fun active(owner: Flight) =
        flight === owner && SystemClock.elapsedRealtime() < owner.until && !Thread.currentThread().isInterrupted
    private fun clearReview() { val old = reviewed; reviewed = null; old?.close() }
    private fun end(owner: Flight) {
        main.removeCallbacks(owner.timer)
        if (flight === owner) flight = null
    }
    private fun fail(owner: Flight) {
        end(owner)
        owner.retire()
        owner.done(null, owner.lost)
    }
    fun clear() {
        check(Looper.myLooper() == main.looper)
        clearReview()
        published = null
        val old = transfer; transfer = null; old?.handoff?.retire()
        flight?.let(::fail)
        shelf.forgetContinueSelection()
    }
    fun retireLostOwner() {
        check(Looper.myLooper() == main.looper)
        if (reviewed?.let { !it.source.currentOnMain() || it.affirmation == null && SystemClock.elapsedRealtime() >= it.until } == true)
            clearReview()
        if (published?.scope?.currentOnMain?.invoke() == false) published = null
        transfer?.takeIf { !it.arrival.current() }?.let { transfer = null; it.handoff.retire() }
        flight?.takeIf { !it.onMain() }?.let(::fail)
    }

    private fun <T> work(onMain: () -> Boolean, fast: () -> Boolean, done: (JSONObject?, String?) -> Unit,
                         lost: String = LOST, retire: () -> Unit = {}, late: (T) -> Unit = {},
                         began: (Flight) -> Unit = {}, read: () -> T, show: (T) -> JSONObject) {
        check(flight == null) { LOST }
        if (!onMain()) { retire(); done(null, lost); return }
        val owner = Flight(SystemClock.elapsedRealtime() + WAIT_MS, onMain, done, lost, retire)
        owner.timer = Runnable { if (flight === owner) fail(owner) }
        flight = owner
        try {
            began(owner)
            main.postDelayed(owner.timer, WAIT_MS)
            controller.runPluginCatalogWork(Runnable {
                val result = runCatching { check(active(owner) && fast()) { LOST }; read() }
                main.post {
                    if (!active(owner) || !onMain()) {
                        result.getOrNull()?.let(late)
                        if (flight === owner) fail(owner)
                    } else {
                        val output = result.mapCatching(show)
                        // A successful native swap intentionally ended the old Files scope.
                        // show already checked the unchanged parent and exact new ACK.
                        if (output.isSuccess) { end(owner); done(output.getOrThrow(), null) }
                        else { result.getOrNull()?.let(late); fail(owner) }
                    }
                }
            })
        } catch (_: Exception) { if (flight === owner) fail(owner) }
    }

    private fun id(args: JSONObject, key: String) =
        (args.opt(key) as? String)?.takeIf(uuid::matches) ?: error("原继续动作身份无效")
    private fun fields(args: JSONObject, expected: Set<String>) =
        require(args.keys().asSequence().toSet() == expected) { "原继续动作字段不同" }
    private fun exact(scope: SourceCheckpointContinueSelection, target: Target) =
        target.projectId() == scope.files.project && target.originatingCheckoutId() == scope.files.checkout &&
        target.checkoutId() != scope.files.checkout && uuid.matches(target.checkoutId()) &&
        target.checkpointId() == scope.selected.checkpointId() && target.manifestIdentity() == scope.selected.manifestIdentity() &&
        target.archiveSha256() == scope.selected.archiveSha256() && target.filteredSourceDigest() == scope.selected.filteredSourceDigest()

    private fun observed(scope: SourceCheckpointContinueSelection, value: Observation, attempt: Attempt? = null): JSONObject {
        check(exact(scope, value.target()) && (attempt == null || value.target() == attempt.target)) { "副本登记不属于原所选版" }
        published = if (value.state() == State.PUBLISHED) Publication(UUID.randomUUID().toString(), scope, value) else null
        return JSONObject().put("state", value.state().name).put("target", targetView(value.target()))
            .put("attemptId", attempt?.id ?: JSONObject.NULL).put("publicationHandle", published?.handle ?: JSONObject.NULL)
    }
    private fun targetView(target: Target) = JSONObject().put("projectId", target.projectId())
        .put("originatingCheckoutId", target.originatingCheckoutId()).put("checkpointId", target.checkpointId())
        .put("checkoutId", target.checkoutId()).put("name", target.name()).put("manifestIdentity", target.manifestIdentity())
        .put("archiveSha256", target.archiveSha256()).put("filteredSourceDigest", target.filteredSourceDigest())

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(Looper.myLooper() == main.looper && action in COMMANDS)
        retireLostOwner()
        if (action == RETURN) { returnFiles(args, done); return }
        if (action == SELECT) {
            check(flight == null && transfer == null && reviewed == null) { "请先完成原继续核对" }
            published = null
            shelf.selectForContinuation(args, done)
            return
        }
        val extra = when (action) {
            CONFIRM -> setOf("reviewId", "confirmed")
            DISMISS -> setOf("reviewId")
            QUERY -> setOf("attemptId")
            OPEN -> setOf("publicationHandle")
            else -> emptySet()
        }
        val ticketField = if (args.has("selectionId")) "selectionId" else "comparisonId"
        fields(args, base + ticketField + extra)
        val scope = shelf.captureContinueSelection(args, ticketField)
        val prior = attempted[scope.local]?.get(scope.selected)
        val reply: (JSONObject?, String?) -> Unit = { result, error -> done(result?.let(scope::bind), error) }
        if (action == DISMISS) {
            check(flight == null && reviewed?.let { it.source === scope && it.offered?.id() == id(args, "reviewId") } == true) { LOST }
            clearReview(); reply(JSONObject().put("dismissed", true), null); return
        }
        check(flight == null && transfer == null) { "请等当前原副本操作结束" }
        when (action) {
            REVIEW -> {
                check(reviewed == null) { "请先完成原继续核对" }
                published = null
                if (prior != null) {
                    check(prior.before == scope.before) { "请回到此前选择的原版本或同一对版本核对原登记，不再次创建" }
                    val expected = Observation(State.UNCONFIRMED, prior.target)
                    reply(JSONObject().put("observed", observed(scope, expected, prior)).put("review", JSONObject.NULL), null)
                    return
                }
                check((attempted[scope.local]?.size ?: 0) < MAX_ATTEMPTS) { "原登记核对位置已满，不再发起创建" }
                val window = ContinueWindow(scope).also { reviewed = it }
                work(scope.currentOnMain, scope.current, reply, retire = ::clearReview, late = { it.review()?.close() },
                    read = { scope.local.reviewSourceCheckpointContinuationForOwner(window, scope.selected) }, show = { offer ->
                        val review = offer.review()
                        check(if (offer.observed().state() == State.NOT_OBSERVED) review != null && review.selected() == scope.selected &&
                            review.target() == offer.observed().target() else review == null) { LOST }
                        window.offered = review
                        if (review == null) clearReview()
                        JSONObject().put("observed", observed(scope, offer.observed())).put("review", review?.let {
                            JSONObject().put("id", it.id()).put("selected", shelf.version(it.selected())).put("target", targetView(it.target()))
                        } ?: JSONObject.NULL)
                    })
            }
            CONFIRM -> {
                require(args.opt("confirmed") == true) { "请明确确认另建私人副本" }
                val reviewId = id(args, "reviewId")
                val window = reviewed?.takeIf { it.source === scope && SystemClock.elapsedRealtime() < it.until } ?: error(LOST)
                val original = window.offered?.takeIf { it.id() == reviewId } ?: error(LOST)
                val records = attempted.getOrPut(scope.local) { linkedMapOf() }
                check(prior == null && records.size < MAX_ATTEMPTS) { UNKNOWN }
                // The already delivered review ID also names its one later readback,
                // even when this confirmation reply never reaches the renderer.
                val attempt = Attempt(reviewId, scope.before, original.target())
                // Hold the exact expected result before any worker can reserve a directory.
                records[scope.selected] = attempt
                work(scope.currentOnMain, scope.current, reply, lost = UNKNOWN, retire = ::clearReview, began = window::confirm,
                    read = { scope.local.continueSourceCheckpointForOwner(window, original) }, show = { value ->
                        val output = observed(scope, value, attempt)
                        clearReview()
                        output
                    })
            }
            QUERY -> {
                check(reviewed == null && prior != null && prior.before == scope.before && prior.id == id(args, "attemptId")) { LOST }
                published = null
                val original = AtomicReference<Flight?>()
                val window = object : OwnerSourceCheckpointContinuation.SelectionWindow {
                    override fun projectId() = scope.files.project
                    override fun checkoutId() = scope.files.checkout
                    override fun requireCurrent() { check(scope.current() && original.get()?.let(::active) == true) { LOST } }
                    override fun requireSelectedCheckpoint(version: Version) { requireCurrent(); check(version == scope.selected) }
                }
                work(scope.currentOnMain, scope.current, reply, began = { original.set(it) }, read = {
                    scope.local.observeSourceCheckpointContinuationForOwner(window, scope.selected)
                }, show = { value -> observed(scope, value, prior) })
            }
            OPEN -> {
                check(reviewed == null) { LOST }
                val offered = published?.takeIf { it.scope === scope && it.handle == id(args, "publicationHandle") } ?: error(LOST)
                val handoff = captureHandoff(scope, offered.observed)
                published = null
                work(scope.currentOnMain, scope.current, done, retire = handoff::retire,
                    read = { handoff.prepareSibling(scope.local) }, show = { prepared ->
                        val arrival = prepared.install()
                        val held = Transfer(UUID.randomUUID().toString(), scope.viewHandle, scope.local, handoff, arrival)
                        transfer = held
                        arrivalView(held, arrival).put("status", "OPENED")
                    })
            }
        }
    }

    private fun returnFiles(args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        fields(args, setOf("viewHandle", "filesHandle", "transferHandle"))
        val held = transfer?.takeIf { it.view == id(args, "viewHandle") && it.id == id(args, "transferHandle") &&
            it.arrival.filesHandle == id(args, "filesHandle") && it.arrival.current() } ?: error(LOST)
        check(flight == null) { LOST }
        val origin = { JSONObject().put("viewHandle", held.view).put("filesHandle", held.arrival.filesHandle).put("transferHandle", held.id) }
        when (val leave = held.handoff.beginBack(held.arrival.filesHandle)) {
            PocketWebCheckpointFilesHandoff.Back.NotCurrent -> error(LOST)
            PocketWebCheckpointFilesHandoff.Back.StockClose -> {
                transfer = null; done(origin().put("status", "STOCK_CLOSE"), null)
            }
            is PocketWebCheckpointFilesHandoff.Back.Restore -> {
                val current = { transfer === held && held.arrival.current() }
                work(current, current, done, retire = { if (transfer === held) transfer = null; held.handoff.retire() },
                    read = leave.prepare, show = { prepared ->
                        val returned = prepared.install()
                        transfer = null
                        when (returned) {
                            PocketWebCheckpointFilesHandoff.Return.OriginalUnavailable -> origin().put("status", "ORIGINAL_UNAVAILABLE")
                            is PocketWebCheckpointFilesHandoff.Return.Files -> {
                                val resume = returned.scope?.let { pin ->
                                    val exact = runCatching {
                                        shelf.resumeAfterContinuation(held.view, held.local, returned.arrival, pin, returned.comparison)
                                    }.getOrNull()
                                    if (exact == null) pin.retire()
                                    exact // Optional comparison recovery cannot hide an already verified A Files ACK.
                                }
                                arrivalView(held, returned.arrival).put("status", "FILES").put("resume", resume ?: JSONObject.NULL)
                            }
                        }
                    })
            }
        }
    }
    private fun arrivalView(held: Transfer, arrival: CheckpointFilesArrival) =
        JSONObject().put("viewHandle", held.view).put("transferHandle", held.id)
            .put("files", JSONObject().put("projectId", arrival.projectId).put("checkoutId", arrival.checkoutId)
                .put("previousFilesHandle", arrival.previousFilesHandle).put("filesHandle", arrival.filesHandle)
                .put("listing", arrival.listing()))
}
