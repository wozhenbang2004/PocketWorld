package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.OwnerSourceCheckpoints
import dev.pocketengine.ui.PocketWorldController
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Dormant adapter for the original private Files visit. No authority is stored in JS
 * and no selected version is inferred from a project, path, timestamp, or latest row. */
internal class PocketWebSourceCheckpoints(
    private val controller: PocketWorldController,
    private val document: () -> String?,
    private val captureFiles: (JSONObject) -> SourceCheckpointFilesPin,
    private val continueEnabled: Boolean = false,
    private val changed: () -> Unit,
) {
    companion object {
        private const val PREFIX = "projects.development.files.checkpoints."
        private const val OPEN = PREFIX + "open"
        private const val LIST = PREFIX + "list"
        private const val REVIEW = PREFIX + "review"
        const val SAVE = PREFIX + "save"
        private const val DISMISS = PREFIX + "dismiss"
        private const val READ = PREFIX + "read"
        private const val COMPARE = PREFIX + "compare"
        private const val CLOSE = PREFIX + "close"
        private const val PENDING_REVIEW = PREFIX + "pending.review"
        private const val PENDING_CLEAR = PREFIX + "pending.clear"
        private const val PENDING_DISMISS = PREFIX + "pending.dismiss"
        val COMMANDS = setOf(OPEN, LIST, REVIEW, SAVE, DISMISS, READ, COMPARE, CLOSE, PENDING_REVIEW, PENDING_CLEAR, PENDING_DISMISS)
        private val uuid = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
        private val sha = Regex("[a-f0-9]{64}")
        private val originFields = setOf("viewHandle", "filesHandle", "visitId")
        private val heldFields = originFields + "shelfHandle"
        private const val WORK_MS = 30_000L
        private const val REVIEW_MS = 120_000L
        private const val MAX_WARM_RECOVERIES = 40
        // Main-thread only, bound to the same app-owned Local even if its Activity/bridge is replaced.
        // Holds only locators, with no eviction, next-save permission, or cold restoration.
        private val warmRecoveries = WeakHashMap<LocalModule, MutableMap<Lineage, OwnerSourceCheckpoints.Version>>()
        private const val LOST = "原私人副本窗口已变化。没有改读其他副本。"
        private const val UNKNOWN = "本次保留结果待核对。请只在同一副本查原记录，不会重放保存。"
    }

    private data class Origin(val document: String, val view: String, val files: String, val visit: String)
    private data class Lineage(val project: String, val checkout: String)
    private data class Pin(val id: String, val manifest: String)
    private data class Offered(val native: OwnerSourceCheckpoints.Review, val pin: Pin, val view: JSONObject)
    private data class Catalog(val id: String, val native: OwnerSourceCheckpoints.Catalog)
    private data class PendingPin(val catalog: String, val review: String)
    private class Flight(val until: Long, val done: (JSONObject?, String?) -> Unit, val save: Pin?, val pendingClear: PendingPin?) {
        lateinit var timeout: Runnable
    }

    private val main = Handler(Looper.getMainLooper())
    private val commitFence = KeptStaticMainFence(main)
    @Volatile private var visit: Visit? = null
    private val retired = LinkedHashMap<Origin, String>()

    private inner class Visit(val origin: Origin, val files: SourceCheckpointFilesPin, val name: String): OwnerSourceCheckpoints.Window {
        val handle = UUID.randomUUID().toString()
        var local: LocalModule? = null // Installed after exact native registry admission, never substituted.
        @Volatile var flight: Flight? = null
        @Volatile var review: ReviewWindow? = null
        @Volatile var pendingReview: PendingWindow? = null
        var catalog: Catalog? = null
        override fun projectId() = files.project
        override fun checkoutId() = files.checkout
        override fun requireCurrent() { check(fast(this) && !Thread.currentThread().isInterrupted) { LOST } }
    }
    private inner class PendingWindow(val owner: Visit, val catalog: String): OwnerSourceCheckpoints.PendingWindow {
        @Volatile var review: OwnerSourceCheckpoints.PendingReview? = null
        @Volatile var confirmation: Flight? = null
        override fun projectId() = owner.projectId()
        override fun checkoutId() = owner.checkoutId()
        override fun requireCurrent() {
            owner.requireCurrent()
            check(owner.pendingReview === this && confirmation?.let { active(owner, it) } != false) { LOST }
        }
        override fun atConfirmedAbandonment(reviewId: String, unlink: OwnerSourceCheckpoints.Commit) {
            commitFence.call {
                requireCurrent()
                val flight = confirmation
                check(live(owner) && recovery(owner) == null && review?.id() == reviewId &&
                    flight != null && active(owner, flight) && flight.pendingClear == PendingPin(catalog, reviewId)) {
                    "只清除原窗口明确确认的未完成暂存"
                }
                unlink.enter()
            }
        }
    }

    /** This same object owns capture, the shown review, and one explicitly confirmed Save. */
    private inner class ReviewWindow(val owner: Visit): OwnerSourceCheckpoints.SaveWindow {
        @Volatile var offered: Offered? = null
        @Volatile var confirmation: Flight? = null
        var expiry: Runnable? = null

        override fun projectId() = owner.projectId()
        override fun checkoutId() = owner.checkoutId()
        override fun requireCurrent() {
            owner.requireCurrent()
            check(owner.review === this && confirmation?.let { active(owner, it) } != false) { LOST }
        }
        override fun requireConfirmedSave(reviewId: String, manifestIdentity: String) {
            requireCurrent()
            val flight = confirmation
            val pin = Pin(reviewId, manifestIdentity)
            check(flight != null && active(owner, flight) && flight.save == pin && offered?.pin == pin) {
                "只保留原窗口实际核对并明确确认的源码"
            }
        }
        override fun atConfirmedSave(reviewId: String, manifestIdentity: String, commit: OwnerSourceCheckpoints.Commit) {
            commitFence.call {
                requireConfirmedSave(reviewId, manifestIdentity)
                check(live(owner) && recovery(owner) == null) { LOST }
                commit.enter()
            }
        }
        override fun publicationEntered(expected: OwnerSourceCheckpoints.Version) {
            check(Looper.myLooper() == main.looper)
            requireConfirmedSave(expected.checkpointId(), expected.manifestIdentity())
            val key = lineage(owner)
            check(expected.projectId() == key.project && expected.checkoutId() == key.checkout)
            val retained = warmRecoveries.getOrPut(checkNotNull(owner.local)) { linkedMapOf() }
            check(retained.size < MAX_WARM_RECOVERIES && key !in retained) { UNKNOWN }
            retained[key] = expected
        }
    }

    private fun fast(value: Visit) = visit === value && value.files.current()
    private fun live(value: Visit) = fast(value) && document() == value.origin.document && value.files.currentOnMain()
    private fun active(value: Visit, flight: Flight) =
        fast(value) && value.flight === flight && SystemClock.elapsedRealtime() < flight.until
    private fun lineage(value: Visit) = Lineage(value.projectId(), value.checkoutId())
    private fun recovery(value: Visit) = value.local?.let { warmRecoveries[it]?.get(lineage(value)) }
    private fun recovery(value: Visit, pin: Pin) = recovery(value)?.takeIf {
        it.checkpointId() == pin.id && it.manifestIdentity() == pin.manifest
    }
    private fun withRecovery(value: Visit, out: JSONObject) = out.put("recovery", recovery(value)?.let {
        JSONObject().put("reviewId", it.checkpointId()).put("manifestIdentity", it.manifestIdentity()).put("expected", version(it))
    } ?: JSONObject.NULL)
    /** After retiring an exact flight, Main knows whether its irreversible entry was reached. */
    private fun lostFlight(value: Visit, flight: Flight, readError: String) {
        flight.pendingClear?.let { flight.done(bind(value, pendingPin(it).put("status", "UNKNOWN")), null); return }
        val pin = flight.save
        if (pin == null) flight.done(null, readError)
        else if (recovery(value, pin) != null) flight.done(null, UNKNOWN)
        else flight.done(bind(value, saveView(pin, "NOT_SAVED", null)), null)
    }

    private fun clearReview(value: Visit) {
        val old = value.review ?: return
        value.review = null
        old.expiry?.let(main::removeCallbacks)
        old.offered?.native?.close()
        old.offered = null
    }
    private fun end(value: Visit, flight: Flight) {
        main.removeCallbacks(flight.timeout)
        if (value.flight === flight) value.flight = null
    }
    fun clear() {
        val old = visit ?: return
        visit = null
        old.files.retire()
        retired[old.origin] = old.handle
        while (retired.size > 16) retired.remove(retired.keys.first())
        clearReview(old)
        old.pendingReview = null
        old.catalog = null
        old.flight?.let { end(old, it); lostFlight(old, it, LOST) }
    }
    fun retireLostOwner() { if (visit?.let { !live(it) } == true) clear() }
    fun snapshot(): JSONObject? = visit?.takeIf { it.local != null && live(it) }?.let { bind(it, JSONObject()) }

    private fun <T> work(
        value: Visit, done: (JSONObject?, String?) -> Unit, save: Pin? = null, pendingClear: PendingPin? = null,
        started: (Flight) -> Unit = {}, discarded: (T) -> Unit = {}, refused: () -> Unit = {},
        read: () -> T, show: (T) -> JSONObject,
    ) {
        check(live(value) && value.flight == null) { "请等待原副本的这次核对" }
        val flight = Flight(SystemClock.elapsedRealtime() + WORK_MS, done, save, pendingClear)
        fun failed() {
            refused()
            lostFlight(value, flight, "未核对到这次所选原副本记录。")
        }
        flight.timeout = Runnable {
            if (visit !== value || value.flight !== flight) return@Runnable
            end(value, flight)
            if (value.local == null) clear()
            failed()
            changed()
        }
        value.flight = flight
        started(flight)
        main.postDelayed(flight.timeout, WORK_MS)
        try {
            controller.runPluginCatalogWork(Runnable {
                val result = runCatching { check(active(value, flight)) { LOST }; read() }
                main.post {
                    if (value.flight !== flight) { result.getOrNull()?.let(discarded); return@post }
                    val owns = active(value, flight) && live(value)
                    end(value, flight)
                    if (!owns) {
                        result.getOrNull()?.let(discarded)
                        if (visit === value) clear()
                        lostFlight(value, flight, LOST)
                    } else result.mapCatching(show).fold({ done(bind(value, it), null) }, {
                        result.getOrNull()?.let(discarded)
                        if (value.local == null) clear()
                        failed()
                    })
                }
            })
        } catch (_: Exception) {
            end(value, flight)
            if (value.local == null) clear()
            failed()
        }
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(Looper.myLooper() == main.looper)
        require(action in COMMANDS)
        val origin = Origin(document() ?: error(LOST), id(args, "viewHandle"), id(args, "filesHandle"), id(args, "visitId"))
        retireLostOwner()
        if (action == CLOSE) {
            fields(args, if (args.has("shelfHandle")) heldFields else originFields)
            val current = visit?.takeIf { it.origin == origin }
            val handle = current?.handle ?: retired[origin] ?: error(LOST)
            check(!args.has("shelfHandle") || id(args, "shelfHandle") == handle) { LOST }
            if (current != null) clear()
            done(originView(origin).put("shelfHandle", handle).put("closed", true), null)
            return
        }
        if (action == OPEN) {
            fields(args, originFields)
            check(visit == null && origin !in retired) { "请先关闭原源码版本窗口" }
            val files = captureFiles(args)
            val title = controller.state.projects.singleOrNull { it.id == files.project }?.name
            if (title == null || title.isBlank() || title.length > 80) { files.retire(); error(LOST) }
            val value = Visit(origin, files, title).also { visit = it }
            if (!live(value)) { clear(); error(LOST) }
            work(value, done, read = {
                val local = controller.nativeButlerHost().features.module("local") as? LocalModule ?: error(LOST)
                local.admitSourceCheckpointVisitForOwner(value)
                local
            }, show = { local -> value.local = local; withRecovery(value, JSONObject().put("opened", true)) })
            return
        }
        val value = visit?.takeIf { it.origin == origin && it.handle == id(args, "shelfHandle") && live(it) } ?: error(LOST)
        val local = value.local ?: error(LOST)
        if (continueEnabled) continueShown = null // Only a new positive Compare can mint another selection.

        if (action == DISMISS) {
            fields(args, heldFields + setOf("reviewId", "manifestIdentity"))
            val pin = reviewPin(args)
            val held = value.review?.takeIf { it.offered?.pin == pin }
            val entered = recovery(value, pin) != null
            check(held != null || entered) { "原源码核对已结束" }
            val writing = held?.confirmation
            if (held != null) clearReview(value)
            if (writing != null) {
                end(value, writing)
                lostFlight(value, writing, LOST)
            }
            done(bind(value, JSONObject().put("reviewId", pin.id).put("dismissed", true)
                .put("saveStatus", if (entered) "UNKNOWN" else "NOT_SAVED")), null)
            return
        }

        check(value.flight == null) { "请等原副本核对结束" }
        if (action in setOf(PENDING_REVIEW, PENDING_CLEAR, PENDING_DISMISS)) {
            pending(action, value, local, args, done)
            return
        }
        check(value.pendingReview == null) { "请先退出原未完成暂存核对" }
        when (action) {
            LIST -> {
                fields(args, heldFields); check(value.review == null) { "请先退出原核对" }
                work(value, done, read = { local.sourceCheckpointCatalogForOwner(value) }, show = { catalog ->
                    val shown = Catalog(UUID.randomUUID().toString(), catalog); value.catalog = shown
                    withRecovery(value, JSONObject().put("catalogId", shown.id).put("versions", JSONArray(catalog.versions().map(::version)))
                        .put("pending", catalog.pending()).put("storedBytes", catalog.storedBytes()))
                })
            }
            REVIEW -> {
                fields(args, heldFields)
                check(value.review == null && recovery(value) == null && (warmRecoveries[local]?.size ?: 0) < MAX_WARM_RECOVERIES) {
                    "请先查看原保留结果，不重复发起"
                }
                val window = ReviewWindow(value).also { value.review = it }
                work(value, done, discarded = { it.native.close() }, refused = { if (value.review === window) clearReview(value) },
                    read = {
                        val review = local.reviewSourceCheckpointForOwner(window)
                        try {
                            val pin = Pin(review.id(), review.manifestIdentity())
                            Offered(review, pin, reviewView(pin, review.manifest()))
                        } catch (failure: Exception) { review.close(); throw failure }
                    }, show = { offer ->
                        window.offered = offer
                        val expire = Runnable { if (value.review === window && window.confirmation == null) clearReview(value) }
                        window.expiry = expire; main.postDelayed(expire, REVIEW_MS)
                        offer.view
                    })
            }
            SAVE -> {
                fields(args, heldFields + setOf("reviewId", "manifestIdentity", "confirmed"))
                require(args.opt("confirmed") == true) { "请明确确认原核对的源码及排除范围" }
                val pin = reviewPin(args)
                val window = value.review?.takeIf { it.offered?.pin == pin && it.confirmation == null } ?: error("原源码核对已变化")
                val native = checkNotNull(window.offered).native
                check(recovery(value) == null && (warmRecoveries[local]?.size ?: 0) < MAX_WARM_RECOVERIES) { UNKNOWN }
                work(value, done, save = pin, started = { window.confirmation = it }, refused = { clearReview(value) },
                    read = { local.saveSourceCheckpointForOwner(window, native, pin.manifest) }, show = { result ->
                        clearReview(value)
                        val entered = recovery(value, pin)
                        val verified = result.version()?.takeIf { result.status() == OwnerSourceCheckpoints.SaveStatus.SAVED &&
                            it == entered }
                        val status = when {
                            verified != null -> "SAVED"
                            entered != null || result.status() == OwnerSourceCheckpoints.SaveStatus.SAVED -> "UNKNOWN"
                            result.status() == OwnerSourceCheckpoints.SaveStatus.UNKNOWN -> "NOT_SAVED"
                            else -> result.status().name
                        }
                        saveView(pin, status, verified)
                    })
            }
            READ, COMPARE -> {
                fields(args, heldFields + "catalogId" + (if (action == READ) setOf("version") else setOf("before", "after")))
                check(value.review == null) { "请先退出原核对" }
                val shown = value.catalog?.takeIf { it.id == id(args, "catalogId") } ?: error("请从本次原副本目录选择")
                if (action == READ) {
                    val row = exact(shown, args.getJSONObject("version"))
                    work(value, done, read = { local.readSourceCheckpointReceiptForOwner(shown.native, row) }, show = { saved ->
                        val records = warmRecoveries[local]
                        if (records != null && records[lineage(value)] == saved) {
                            records.remove(lineage(value))
                            if (records.isEmpty()) warmRecoveries.remove(local)
                        }
                        JSONObject().put("verified", true).put("version", version(saved))
                    })
                } else {
                    val before = exact(shown, args.getJSONObject("before")); val after = exact(shown, args.getJSONObject("after"))
                    check(before.checkpointId() != after.checkpointId()) { "请选择两份不同的原源码版本" }
                    work(value, done, read = {
                        val actual = local.compareSourceCheckpointsForOwner(shown.native, before, after)
                        actual to comparison(actual)
                    }, show = { (actual, output) ->
                        if (continueEnabled && live(value) && value.catalog === shown && value.review == null &&
                            recovery(value) == null && !shown.native.pending() && actual.before() == before && actual.after() == after) {
                            bindContinueComparison(value, shown, actual).also { output.put("comparisonId", it.comparisonId) }
                        }
                        output
                    })
                }
            }
        }
    }

    private fun pending(action: String, value: Visit, local: LocalModule, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(value.review == null && recovery(value) == null) { "请先核对原保留结果或退出原源码审阅" }
        if (action == PENDING_REVIEW) {
            fields(args, heldFields + "catalogId")
            val shown = value.catalog?.takeIf { it.id == id(args, "catalogId") && it.native.pending() } ?: error(LOST)
            check(value.pendingReview == null) { "请先退出原未完成暂存核对" }
            val window = PendingWindow(value, shown.id).also { value.pendingReview = it }
            work(value, done, refused = { if (value.pendingReview === window) value.pendingReview = null },
                read = { runCatching { local.reviewSourceCheckpointPendingForOwner(window) }.getOrNull() }, show = { review ->
                    window.review = review
                    if (review == null) value.pendingReview = null
                    JSONObject().put("catalogId", shown.id).put("status", if (review == null) "UNCONFIRMED" else "READY")
                        .put("reviewId", review?.id() ?: JSONObject.NULL).put("leafCount", review?.leafCount() ?: 0)
                        .put("storedBytes", review?.storedBytes() ?: 0L)
                })
            return
        }
        val clear = action == PENDING_CLEAR
        fields(args, heldFields + "reviewId" + (if (clear) setOf("confirmed") else emptySet()))
        val id = id(args, "reviewId")
        val window = value.pendingReview?.takeIf { it.review?.id() == id && value.catalog?.id == it.catalog } ?: error(LOST)
        val pin = PendingPin(window.catalog, id)
        if (!clear) {
            value.pendingReview = null
            done(bind(value, pendingPin(pin).put("dismissed", true)), null)
            return
        }
        require(args.opt("confirmed") == true) { "请明确确认仅清除该副本未完成的暂存" }
        val review = checkNotNull(window.review)
        value.catalog = null // New pending evidence, if any, requires a deliberate new list and review.
        work(value, done, pendingClear = pin, started = { window.confirmation = it },
            refused = { if (value.pendingReview === window) value.pendingReview = null },
            read = { local.clearSourceCheckpointPendingForOwner(window, review) }, show = { result ->
                value.pendingReview = null
                check(result.reviewId() == pin.review) { LOST }
                pendingPin(pin).put("status", result.status().name)
            })
    }

    private fun pendingPin(pin: PendingPin) = JSONObject().put("catalogId", pin.catalog).put("reviewId", pin.review)

    private fun fields(value: JSONObject, expected: Set<String>) {
        require(value.keys().asSequence().toSet() == expected) { "原源码快照操作字段不同" }
    }
    private fun id(value: JSONObject, key: String, pattern: Regex = uuid): String =
        (value.opt(key) as? String)?.takeIf(pattern::matches) ?: error("原源码快照身份无效")
    private fun reviewPin(args: JSONObject) = Pin(id(args, "reviewId"), id(args, "manifestIdentity", sha))
    private fun exact(shown: Catalog, pin: JSONObject): OwnerSourceCheckpoints.Version {
        fields(pin, setOf("checkpointId", "manifestIdentity", "archiveSha256"))
        val id = id(pin, "checkpointId"); val manifest = id(pin, "manifestIdentity", sha); val archive = id(pin, "archiveSha256", sha)
        return shown.native.versions().singleOrNull { it.checkpointId() == id && it.manifestIdentity() == manifest && it.archiveSha256() == archive }
            ?: error("请只使用原目录中确切的一版源码")
    }
    private fun originView(origin: Origin) = JSONObject().put("viewHandle", origin.view).put("filesHandle", origin.files).put("visitId", origin.visit)
    private fun bind(value: Visit, out: JSONObject) = out.put("viewHandle", value.origin.view).put("filesHandle", value.origin.files)
        .put("visitId", value.origin.visit).put("shelfHandle", value.handle).put("scope", JSONObject()
            .put("projectId", value.projectId()).put("checkoutId", value.checkoutId()).put("projectName", value.name))

    fun version(v: OwnerSourceCheckpoints.Version) = JSONObject().put("projectId", v.projectId()).put("checkoutId", v.checkoutId())
        .put("checkpointId", v.checkpointId()).put("storageSlot", v.storageSlot()).put("manifestIdentity", v.manifestIdentity())
        .put("filteredSourceDigest", v.filteredSourceDigest()).put("archiveSha256", v.archiveSha256()).put("archiveBytes", v.archiveBytes())
        .put("capturedAt", v.capturedAt()).put("fileCount", v.fileCount()).put("omittedCount", v.omittedCount()).put("expandedBytes", v.expandedBytes())
    private fun reviewView(pin: Pin, m: JSONObject): JSONObject {
        val files = m.getJSONArray("files"); val omitted = m.getJSONArray("omitted")
        return JSONObject().put("id", pin.id).put("manifestIdentity", pin.manifest)
            .put("filteredSourceDigest", m.getJSONObject("source").getString("sourceDigest")).put("expandedBytes", m.getLong("bytes"))
            .put("files", JSONArray((0 until files.length()).map { i ->
                val file = files.getJSONObject(i)
                JSONObject().put("path", file.getString("path")).put("sha256", file.getString("sha256")).put("bytes", file.getLong("bytes"))
            })).put("directories", m.getJSONArray("directories"))
            .put("omitted", JSONArray((0 until omitted.length()).map { i ->
                val row = omitted.getJSONObject(i)
                JSONObject().put("path", row.getString("path"))
                    .put("kind", OwnerSourceCheckpoints.OmissionKind.valueOf(row.getString("reason").uppercase(Locale.ROOT)).name)
            }))
    }
    private fun saveView(pin: Pin, status: String, value: OwnerSourceCheckpoints.Version?) = JSONObject()
        .put("reviewId", pin.id).put("manifestIdentity", pin.manifest).put("status", status)
        .put("version", value?.let(::version) ?: JSONObject.NULL)
    private fun entry(v: OwnerSourceCheckpoints.Entry?): Any = if (v == null) JSONObject.NULL else
        JSONObject().put("kind", v.kind().name).put("sha256", v.sha256() ?: JSONObject.NULL)
            .put("bytes", v.bytes()).put("executable", v.executable())
    private fun comparison(c: OwnerSourceCheckpoints.Comparison) = JSONObject().put("before", version(c.before())).put("after", version(c.after()))
        .put("paths", JSONArray(c.paths().map { change ->
            val x = change.exclusion()
            JSONObject().put("path", change.path()).put("kind", change.kind().name)
                .put("before", entry(change.before())).put("after", entry(change.after()))
                .put("exclusion", x?.let { JSONObject().put("omittedPath", it.omittedPath()).put("kind", it.kind().name).put("exact", it.exact()) }
                    ?: JSONObject.NULL)
        }))

    @Volatile private var continueShown: SourceCheckpointContinueSelection? = null
    private fun bindContinueComparison(value: Visit, shown: Catalog, actual: OwnerSourceCheckpoints.Comparison): SourceCheckpointContinueSelection {
        lateinit var exact: SourceCheckpointContinueSelection
        fun kept() = continueShown === exact && fast(value) && value.flight == null && value.review == null
        exact = SourceCheckpointContinueSelection(value.origin.view, value.origin.files, value.origin.visit, value.handle,
            shown.id, UUID.randomUUID().toString(), value.name, checkNotNull(value.local), value.files, shown.native,
            actual.before(), actual.after(), ::kept) {
            kept() && live(value) && value.catalog === shown && recovery(value) == null
        }
        continueShown = exact
        return exact
    }
    fun captureContinueSelection(args: JSONObject): SourceCheckpointContinueSelection {
        check(Looper.myLooper() == main.looper && continueEnabled)
        return continueShown?.takeIf {
            it.viewHandle == id(args, "viewHandle") && it.filesHandle == id(args, "filesHandle") &&
                it.visitId == id(args, "visitId") && it.shelfHandle == id(args, "shelfHandle") &&
                it.catalogId == id(args, "catalogId") && it.comparisonId == id(args, "comparisonId") && it.currentOnMain()
        } ?: error("请在原副本的这次已核对比较中选择")
    }
    fun forgetContinueSelection() { continueShown = null }

    /** Only the newly installed, exact original A from the verified handoff can
     * become a fresh Shelf. No old catalog, review or comparison handle is reused. */
    fun resumeAfterContinuation(view: String, local: LocalModule, files: CheckpointFilesArrival, pin: SourceCheckpointFilesPin,
            compared: OwnerSourceCheckpoints.Comparison?): JSONObject? {
        check(Looper.myLooper() == main.looper && continueEnabled)
        retireLostOwner()
        if (visit != null || !files.current() || !pin.currentOnMain() ||
            fileScopeDifferent(files, pin)) return null
        val originalDocument = document() ?: return null
        val name = controller.state.projects.singleOrNull { it.id == files.projectId }?.name?.takeIf { it.isNotBlank() && it.length <= 80 }
            ?: return null
        val origin = Origin(originalDocument, view, files.filesHandle, UUID.randomUUID().toString())
        val value = Visit(origin, pin, name)
        value.local = local; visit = value
        if (!live(value)) { clear(); return null }
        return bind(value, JSONObject().put("opened", true).put("comparison", compared?.let(::comparison) ?: JSONObject.NULL))
    }
    private fun fileScopeDifferent(files: CheckpointFilesArrival, pin: SourceCheckpointFilesPin) =
        files.projectId != pin.project || files.checkoutId != pin.checkout
}
