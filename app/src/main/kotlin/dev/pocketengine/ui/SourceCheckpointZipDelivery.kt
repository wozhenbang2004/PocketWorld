package dev.pocketengine.ui

import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.OwnerSourceCheckpoints.Version
import dev.pocketengine.ui.web.KeptStaticMainFence
import java.util.concurrent.Executors
import org.json.JSONObject

/** One checkpoint ZIP, one Android result, and no restorable document or grant. */
internal class SourceCheckpointZipDelivery(private val activity: ComposeActivity, private val changed: () -> Unit) {
    companion object {
        private const val LIFE_MS = 120_000L
        private const val WRITE_MS = 30_000L
        fun name(version: Version) = "pocketworld-source-${version.checkpointId().take(8)}.zip"
    }

    private data class Target(val uri: Uri, val document: String, val name: String)
    private class Attempt(val id: String, val launchVisit: String, val source: LocalModule.SourceCheckpointZipPin,
                          val route: () -> Boolean) {
        val version: Version = source.version()
        val until = SystemClock.elapsedRealtime() + LIFE_MS
        var phase = "choosing"
        var paused = false
        var target: Target? = null
        var destinationName: String? = null
        var signal: CancellationSignal? = null
        lateinit var expiry: Runnable
        @Volatile var write: Write? = null
    }
    private class Write(val done: (JSONObject?, String?) -> Unit) {
        val until = SystemClock.elapsedRealtime() + WRITE_MS
        val control = RunControl()
        val signal = CancellationSignal()
        var entered = false // main-thread admission, before even opening a provider descriptor
        lateinit var timeout: Runnable
        init { control.onCancel(Runnable { signal.cancel() }) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val fence = KeptStaticMainFence(main)
    private val worker = Executors.newSingleThreadExecutor()
    private val picker = AttachmentExportPicker(activity.activityResultRegistry, "private_checkpoint_zip", ::picked)
    private var attempt: Attempt? = null
    private var draining: Write? = null
    private var closed = false

    private fun owns(value: Attempt) = !closed && attempt === value && activity.keptPageExportOwnerCurrent() &&
        SystemClock.elapsedRealtime() < value.until && value.route()
    fun idle() = snapshot() == null && draining == null

    private fun metadata(value: Attempt) = JSONObject().put("kind", "owner_source_checkpoint_zip")
        .put("requestId", value.id).put("projectId", value.version.projectId()).put("checkoutId", value.version.checkoutId())
        .put("checkpointId", value.version.checkpointId()).put("manifestIdentity", value.version.manifestIdentity())
        .put("archiveSha256", value.version.archiveSha256()).put("archiveBytes", value.version.archiveBytes())
        .put("capturedAt", value.version.capturedAt()).put("phase", value.phase)
        .put("destinationName", value.destinationName ?: JSONObject.NULL)

    fun snapshot(): JSONObject? {
        val value = attempt ?: return null
        if (!owns(value)) { retire(); return null }
        return metadata(value)
    }

    fun arm(id: String, launchVisit: String, source: LocalModule.SourceCheckpointZipPin, route: () -> Boolean): JSONObject {
        check(!closed && idle() && activity.keptPageExportOwnerCurrent() && route()) { "请先结束原 ZIP 保存定位" }
        val value = Attempt(id, launchVisit, source, route).also { attempt = it }
        value.expiry = Runnable { if (attempt === value) { retire(); changed() } }
        main.postDelayed(value.expiry, LIFE_MS)
        when (picker.launch(id, "application/zip", name(value.version))) {
            OutcomeExportLaunch.NOT_STARTED -> value.phase = "not_written"
            OutcomeExportLaunch.UNKNOWN -> if (value.phase == "choosing") value.phase = "chooser_unconfirmed"
            OutcomeExportLaunch.REQUESTED -> Unit
        }
        return metadata(value)
    }

    fun background() {
        val value = attempt ?: return
        // Only the expected system picker pause retains the exact nonce, never a Files/shelf handle.
        if (!value.paused && value.phase in setOf("choosing", "chooser_unconfirmed", "checking_target")) value.paused = true
        else if (value.write != null) interrupt(value, checkNotNull(value.write))
        else retire()
    }

    fun retire() {
        val value = attempt ?: return
        value.write?.let { interrupt(value, it) }
        attempt = null
        value.target = null
        value.signal?.cancel()
        value.signal = null
        main.removeCallbacks(value.expiry)
        picker.close()
    }

    fun close() { closed = true; retire(); picker.close(); worker.shutdownNow() }

    private fun safeName(value: String): Boolean {
        if (value.length !in 5..240 || value.trim() != value || !value.endsWith(".zip", ignoreCase = true)) return false
        var at = 0
        while (at < value.length) {
            val point = Character.codePointAt(value, at)
            if (point == '/'.code || point == '\\'.code || point == ':'.code || Character.isISOControl(point) ||
                Character.getType(point) == Character.FORMAT.toInt() || point == 0x2028 || point == 0x2029 ||
                point in 0xd800..0xdfff) return false
            at += Character.charCount(point)
        }
        return true
    }

    private fun inspect(uri: Uri, signal: CancellationSignal, expectedSize: Long): Target? {
        if (!ProjectPackageDocument.allowed(activity, uri) || signal.isCanceled) return null
        val document = DocumentsContract.getDocumentId(uri).takeIf { it.length in 1..2048 } ?: return null
        return activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null, signal)?.use { row ->
            if (!row.moveToFirst() || row.isNull(0) || row.isNull(1) || row.getLong(1) != expectedSize) null
            else row.getString(0)?.takeIf(::safeName)?.let { Target(uri, document, it) }
        }
    }

    private fun picked(id: String, uri: Uri?) {
        val value = attempt?.takeIf { it.id == id && it.phase in setOf("choosing", "chooser_unconfirmed") } ?: return
        if (!owns(value)) { retire(); changed(); return }
        if (uri == null || !value.paused) { value.phase = if (uri == null) "cancelled" else "not_written"; changed(); return }
        val signal = CancellationSignal()
        value.phase = "checking_target"; value.signal = signal; changed()
        // Android may already have created an empty document. This callback only queries its metadata.
        try { worker.execute {
            val checked = runCatching { inspect(uri, signal, 0) }.getOrNull()
            main.post {
                if (attempt !== value || value.phase != "checking_target" || value.signal !== signal) return@post
                value.signal = null
                if (!owns(value)) retire()
                else if (checked == null) value.phase = "not_written"
                else { value.target = checked; value.destinationName = checked.name; value.phase = "target_selected" }
                changed()
            }
        } } catch (_: Exception) { value.signal = null; value.phase = "not_written"; changed() }
    }

    fun dismiss(id: String, version: Version) {
        val value = attempt?.takeIf { owns(it) && it.id == id && it.version == version && it.write == null }
            ?: error("原 ZIP 保存定位已变化")
        check(value.phase !in setOf("choosing", "chooser_unconfirmed", "checking_target"))
        retire()
    }

    private fun interrupt(value: Attempt, write: Write) {
        if (attempt !== value || value.write !== write) return
        value.write = null
        main.removeCallbacks(write.timeout)
        write.control.cancel()
        value.phase = if (write.entered) "unknown" else "not_written"
        write.done(metadata(value), null)
        changed()
    }

    fun write(id: String, version: Version, freshVisit: String, fast: () -> Boolean, current: () -> Boolean,
              read: (LocalModule.SourceCheckpointZipPin, () -> Boolean) -> ByteArray,
              done: (JSONObject?, String?) -> Unit) {
        val value = attempt?.takeIf { owns(it) && it.id == id && it.version == version &&
            it.paused && it.launchVisit != freshVisit && it.phase == "target_selected" && it.write == null }
            ?: error("请在新打开的原文件窗口核对原版本和所选位置")
        check(draining == null && current())
        val target = checkNotNull(value.target)
        value.target = null // One gesture consumes this Android result, no way to rearm it.
        val write = Write(done)
        value.write = write; value.phase = "writing"; draining = write
        fun fastCurrent() = value.write === write && !write.control.isCancelled &&
            SystemClock.elapsedRealtime() < minOf(write.until, value.until) && fast()
        fun requireFast() { check(fastCurrent()) { "原源码 ZIP 写入窗口已离开" } }
        fun stillHere() = fastCurrent() && owns(value) && current()
        fun requireMain() { check(fence.call(::stillHere)) { "原源码 ZIP 写入窗口已离开" } }
        write.timeout = Runnable { interrupt(value, write) }
        main.postDelayed(write.timeout, WRITE_MS)
        changed()
        try { worker.execute {
            val verified = runCatching {
                requireMain()
                val bytes = read(value.source, ::fastCurrent)
                try {
                    check(bytes.size.toLong() == version.archiveBytes())
                    requireMain()
                    check(inspect(target.uri, write.signal, 0) == target) { "原 ZIP 目标已变化或不再为空" }
                    fence.call { check(stillHere()); write.entered = true }
                    requireFast()
                    val fd = activity.contentResolver.openFileDescriptor(target.uri, "rw", write.signal)
                        ?: error("原 ZIP 目标不可写入")
                    val output = try {
                        check(fd.statSize == 0L) { "原 ZIP 目标不再为空" }
                        ParcelFileDescriptor.AutoCloseOutputStream(fd)
                    } catch (failure: Exception) { fd.close(); throw failure }
                    output.use { stream ->
                        for (offset in bytes.indices step 65536) {
                            requireFast()
                            stream.write(bytes, offset, minOf(65536, bytes.size - offset))
                        }
                        requireFast(); stream.flush()
                    }
                } finally { bytes.fill(0) }
                requireMain()
                val proof = ProjectPackageDocument.readBack(activity, target.uri, target.name,
                    version.archiveBytes(), version.archiveSha256(), write.control, ::requireFast)
                requireMain()
                ProjectPackageDocument.verified(proof, version.archiveBytes(), version.archiveSha256()) &&
                    proof.opt("displayName") == target.name && inspect(target.uri, write.signal, version.archiveBytes()) == target
            }.getOrDefault(false)
            main.post {
                if (draining === write) draining = null
                if (attempt !== value || value.write !== write) return@post
                val currentResult = stillHere()
                value.write = null
                main.removeCallbacks(write.timeout)
                value.phase = if (verified && currentResult) "verified" else if (write.entered) "unknown" else "not_written"
                if (currentResult) done(metadata(value), null)
                else done(null, "原写入窗口已离开，请核对原位置；不会再次写入")
                changed()
            }
        } } catch (_: Exception) { draining = null; interrupt(value, write) }
    }
}
