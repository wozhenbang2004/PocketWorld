package dev.pocketengine.ui.web

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceResponse
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.attachments.NativeOriginalFileSession
import dev.pocketengine.features.attachments.OwnerFilePreviewSource
import dev.pocketengine.features.attachments.documents.DocumentContent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Original native decoders supply bounded content to the same React document. No file URI or network route is exposed. */
internal class PocketWebOriginalFiles(private val context: Context, private val visible: () -> Boolean, private val changed: () -> Unit) {
    companion object {
        val office = setOf("docx", "xlsx", "pptx")
        val officeMime = setOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation")
        val textMime = setOf("application/json", "application/xml", "application/javascript", "application/yaml", "application/x-yaml")
        val textNames = setOf("txt", "md", "csv", "tsv", "log", "json", "jsonl", "xml", "yaml", "yml", "srt",
            "html", "htm", "css", "js", "ts", "tsx", "jsx", "py", "java", "kt", "sql", "ini", "toml", "properties")
        val binaryNames = setOf("zip", "7z", "rar", "gz", "tar", "bin", "doc", "xls", "ppt")
        val packageNames = setOf("apk", "apks", "apkm", "xapk", "aab")
        private fun packageFile(name:String,mime:String) = mime == "application/vnd.android.package-archive" ||
            name.substringAfterLast('.', "").lowercase() in packageNames
        fun packageFile(source: OwnerFilePreviewSource) = packageFile(source.name,source.mimeType)

        fun declaredKind(source: OwnerFilePreviewSource) = declaredKind(source.name,source.mimeType,source.tombstone)
        /** A catalog affordance, not a successful decode or a grant; the viewer still verifies actual bytes. */
        fun libraryDocument(row:JSONObject)=row.optString("role")=="source"&&row.optString("availability")=="available"&&
            declaredKind(row.optString("name"),row.optString("mimeType"),false) in setOf("pdf","office","text")
        private fun declaredKind(name:String,mime:String,tombstone:Boolean): String {
            val extension = name.substringAfterLast('.', "").lowercase()
            return when {
                tombstone || packageFile(name,mime) -> "unsupported"
                mime == "application/pdf" -> "pdf"
                mime.startsWith("image/") -> "image"
                mime.startsWith("audio/") -> "audio"
                mime.startsWith("video/") -> "video"
                mime in officeMime || extension in office -> "office"
                extension in binaryNames -> "unsupported"
                mime.startsWith("text/") || mime in textMime || mime.endsWith("+json") || mime.endsWith("+xml") ||
                    mime == "application/octet-stream" && extension in textNames -> "text"
                else -> "unsupported"
            }
        }
    }
    private class Pin(val source: OwnerFilePreviewSource, val ticket: PocketWebWindowTicket,
        val accepts: (PocketWebWindowTicket) -> Boolean) {
        val id = UUID.randomUUID().toString()
        val kind = declaredKind(source)
        val fileId = source.metadata().optString("id")
        var sourceUnavailable = false
        val lifetime = RunControl()
        @Volatile var session: NativeOriginalFileSession? = null
        @Volatile var error: String? = null
        @Volatile var pdfRaster: PdfRaster? = null
        var information: JSONObject? = null
        var busy = false
    }
    private class PdfRaster(val page: Int, val pages: Int, val searchAvailable: Boolean) {
        val id = UUID.randomUUID().toString()
        val started = AtomicBoolean(false)
        @Volatile var rendered = false
        fun view(): JSONObject = JSONObject().put("pdfRasterId", id).put("page", page)
            .put("pages", pages).put("searchAvailable", searchAvailable)
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val pins = java.util.concurrent.ConcurrentHashMap<String, Pin>()
    private var primary: Pin? = null
    private var comparison: Pair<Pin, Pin>? = null
    @Volatile private var closed = false
    private val preferences = context.getSharedPreferences("owner-file-reading-position-v1", Context.MODE_PRIVATE)
    private val watchdog = object : Runnable {
        override fun run() {
            if (closed || pins.isEmpty()) return
            val current = pins.values.toList()
            runCatching { worker.execute {
                // A prior decode error does not make an already loaded source exempt from retirement checks.
                current.filterNot { it.lifetime.isCancelled }.forEach { pin -> try { requireCurrent(pin); pin.session?.current() }
                    catch (_: Exception) { main.post { if (pins[pin.id] === pin) { retire(pin); pin.sourceUnavailable = true; pin.error = "原来源、受众或版本已失效，请返回后重新打开"; changed() } } } }
            } }
            main.postDelayed(this, 1500)
        }
    }
    fun bind(source: OwnerFilePreviewSource?, compared: Pair<OwnerFilePreviewSource, OwnerFilePreviewSource>?,
        ticket: PocketWebWindowTicket, accepts: (PocketWebWindowTicket) -> Boolean) {
        val same = primary?.source === source && comparison?.first?.source === compared?.first && comparison?.second?.source === compared?.second
        if (same) return
        clear()
        if (source != null) primary = Pin(source, ticket, accepts).also { pins[it.id] = it }
        if (compared != null) comparison = Pair(Pin(compared.first, ticket, accepts), Pin(compared.second, ticket, accepts)).also { pins[it.first.id] = it.first; pins[it.second.id] = it.second }
        if (pins.isNotEmpty()) main.postDelayed(watchdog, 1500)
    }
    private fun view(pin: Pin): JSONObject = JSONObject().put("id", pin.id).put("identity", pin.source.identity)
        .put("name", pin.source.name).put("mimeType", pin.source.mimeType).put("sha256", pin.source.sha256)
        .put("kind", pin.kind).put("sourceUnavailable", pin.sourceUnavailable).put("size", pin.source.sizeBytes).put("busy", pin.busy).put("error", pin.error)
        .put("url", if (pin.sourceUnavailable) "" else "https://pocketengine.local/pocket-files/${pin.id}/content")
        .put("information", pin.information)
    fun snapshot(): JSONObject? = primary?.let(::view)
    /** Preview recovery is narrower than the file-detail external action, which also handles managed APKs. */
    fun matchesOriginal(file: JSONObject?): Boolean {
        val pin = primary ?: return false
        return file != null && !pin.sourceUnavailable && !pin.lifetime.isCancelled && !pin.source.tombstone &&
            pin.fileId.isNotBlank() && pin.fileId == file.optString("id") && pin.source.sha256 == file.optString("sha256")
    }
    fun canOpenInReader(file: JSONObject?) = matchesOriginal(file) && primary?.source?.let { !packageFile(it) } == true
    fun comparisonSnapshot(): JSONArray? = comparison?.let { JSONArray().put(view(it.first)).put(view(it.second)) }
    private fun requireCurrent(pin: Pin) {
        check(!closed && pins[pin.id] === pin && visible() && pin.accepts(pin.ticket)) { "原文件查看已关闭" }
        pin.lifetime.check()
    }
    private fun session(pin: Pin): NativeOriginalFileSession = synchronized(pin) {
        requireCurrent(pin)
        pin.session ?: NativeOriginalFileSession.open(context, pin.source, pin.lifetime).also { opened ->
            try { requireCurrent(pin); pin.session = opened } catch (failure: Exception) { opened.close(); throw failure }
        }
    }
    fun dispatch(args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        val pin = pins[args.optString("viewerId")] ?: run { done(null, "原文件查看已关闭，请重新打开"); return }
        try { requireCurrent(pin); check(!pin.busy) { "请等待当前原件读取完成" } }
        catch (failure: Exception) { done(null, failure.message); return }
        val command = args.optString("command")
        if (command !in setOf("load", "read", "find", "check")) { done(null, "不支持这项原件操作"); return }
        if (command == "check" && args.has("displayedPdfRaster")) {
            val raster = pin.pdfRaster
            if (raster == null || !raster.rendered || raster.id != args.opt("displayedPdfRaster")) {
                done(null, "这次 PDF 页面显示未确认"); return
            }
            // This display acknowledgement cannot render, read or select another page.
            pin.pdfRaster = null
            val confirmed = raster.view()
            pin.information = confirmed
            preferences.edit().putInt("${pin.source.identity}.page", raster.page).apply()
            done(confirmed, null); changed(); return
        }
        if (pin.kind == "pdf" && command in setOf("load", "read")) pin.pdfRaster = null
        val request = JSONObject(args.toString())
        pin.busy = true; pin.error = null; changed()
        try { worker.execute {
            var value: JSONObject? = null; var error: String? = null; var sourceUnavailable = false
            var officePosition: Pair<Int,Int>? = null
            var pdfRaster: PdfRaster? = null
            try {
                requireCurrent(pin); val type = pin.kind; val identity = pin.source.identity
                // No temp copy or decoder for a format with no reader. A known binary header in a file named .txt also vetoes text decoding.
                val original = if (type == "unsupported") null else session(pin)
                value = when {
                    original == null || type == "text" && original.hasRecognizedBinaryHeader -> {
                        check(!pin.source.tombstone) { "此修订没有原文件正文" }
                        check(command == "load" || command == "check") { "此原件没有可用的内置预览" }
                        pin.source.requireCurrent(pin.lifetime)
                        JSONObject().put("current", true).put("previewAvailable", false)
                    }
                    command == "check" -> JSONObject().put("current", true)
                    command == "find" -> {
                        val query = request.getString("query"); require(query.isNotEmpty() && query.length <= 256)
                        if (type == "pdf") original.pdfSearch(query, request.optInt("cursor", 1), pin.lifetime)
                        else { check(type == "text"); original.searchText(request.optString("encoding", "UTF-8"), query, request.optInt("cursor", 0), pin.lifetime) }
                    }
                    else -> when (type) {
                        "pdf" -> {
                            val pages = original.pdfPages()
                            check(pages > 0) { "原 PDF 没有可显示的页面" }
                            val page = request.optInt("page", preferences.getInt("$identity.page", 1)).coerceIn(1, pages)
                            PdfRaster(page, pages, original.pdfSearchAvailable()).also { pdfRaster = it }.view()
                        }
                        "image" -> { val image = original.image(); try { JSONObject().put("width", image.originalWidth).put("height", image.originalHeight).put("sampled", image.sampled) } finally { image.bitmap.recycle() } }
                        "audio", "video" -> { original.openMediaDescriptor().use { }; JSONObject().put("localMedia", true) }
                        "office" -> {
                            val resume = command == "load"
                            val unit = (if (resume) preferences.getInt("$identity.unit", 1) else request.optInt("unit", 1)).coerceAtLeast(1)
                            val chunk = if (resume) preferences.getInt("$identity.chunk", 0) else request.optInt("chunk", 0)
                            val page = original.document(unit, chunk, pin.lifetime)
                            // A long worksheet/section can span several reads within the same unit.
                            // A failed or out-of-range jump is not a new reading position.
                            if (page.status in setOf("ok", "partial") && unit <= page.totalUnits && (chunk == 0 || page.chunks.isNotEmpty()))
                                officePosition = unit to chunk
                            document(page).put("unit", unit).put("chunk", chunk).apply {
                                if (officePosition != null && page.format == "xlsx" && page.previousWorksheetChunk in 0 until chunk)
                                    put("previousWorksheetRange", JSONObject().put("unit", unit).put("chunk", page.previousWorksheetChunk))
                            }
                        }
                        "text" -> {
                            val encoding = request.optString("encoding", preferences.getString("$identity.encoding", "UTF-8") ?: "UTF-8")
                            val offset = request.optInt("offset", preferences.getInt("$identity.offset", 0)).coerceAtLeast(0)
                            val result = original.text(encoding, offset, 12000, pin.lifetime)
                            preferences.edit().putInt("$identity.offset", offset).putString("$identity.encoding", encoding).apply()
                            result.put("encoding", encoding).put("offset", offset)
                        }
                        else -> throw IllegalStateException("此原件没有可用的内置预览")
                    }
                }
                requireCurrent(pin); original?.current()
            } catch (failure: Exception) {
                error = failure.message?.take(260) ?: "原件读取未完成"
                // A decoder failure need not revoke a still-valid file; loss of the scoped source must.
                sourceUnavailable = pin.source.tombstone || runCatching { requireCurrent(pin); pin.source.requireCurrent(pin.lifetime) }.isFailure
            }
            main.post {
                if (pins[pin.id] !== pin || pin.lifetime.isCancelled || !visible() || !pin.accepts(pin.ticket)) { done(null, "原文件窗口已变化，没有换用其他文件"); return@post }
                if (sourceUnavailable) retire(pin)
                else if (error == null && command != "find" && command != "check") {
                    val raster = pdfRaster
                    if (raster != null) pin.pdfRaster = raster else pin.information = value
                    officePosition?.let { (unit, chunk) ->
                        val key = pin.source.identity
                        preferences.edit().putInt("$key.unit", unit).putInt("$key.chunk", chunk).apply()
                    }
                }
                pin.busy = false; pin.error = error; pin.sourceUnavailable = sourceUnavailable
                done(if (sourceUnavailable) null else value, error); changed()
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { pin.busy = false; done(null, "当前原件读取已关闭") }
    }
    private fun document(result: DocumentContent.Result): JSONObject = JSONObject().put("format", result.format)
        .put("status", result.status).put("coverage", result.coverage).put("totalUnits", result.totalUnits)
        .put("nextUnit", result.nextUnit).put("nextChunk", result.nextChunk).put("truncated", result.truncated)
        .put("warnings", JSONArray(result.warnings)).put("chunks", JSONArray(result.chunks.map {
            JSONObject().put("unit", it.unit).put("chunk", it.chunk).put("kind", it.kind).put("text", it.text).put("locator", JSONObject(it.locator))
        }))

    fun response(uri: Uri, method: String, headers: Map<String, String>): WebResourceResponse? {
        if (uri.scheme != "https" || uri.host != "pocketengine.local" || !uri.path.orEmpty().startsWith("/pocket-files/")) return null
        fun blocked(status: Int = 403) = WebResourceResponse("text/plain", "UTF-8", status, "Unavailable", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
        if (method != "GET" || uri.port != -1 || uri.userInfo != null || uri.pathSegments.size != 3 || uri.pathSegments[2] != "content") return blocked()
        val pin = pins[uri.pathSegments[1]] ?: return blocked()
        val raster = if (pin.kind == "pdf") pin.pdfRaster?.takeIf {
            uri.queryParameterNames == setOf("page", "raster") && uri.getQueryParameters("page") == listOf(it.page.toString()) &&
                uri.getQueryParameters("raster") == listOf(it.id) && it.started.compareAndSet(false, true)
        } ?: return blocked() else null
        return try {
            requireCurrent(pin); val type = pin.kind
            if (type !in setOf("image", "pdf", "audio", "video")) return blocked()
            if (raster != null) check(pin.pdfRaster === raster) { "原 PDF 显示请求已变化" }
            val original = session(pin)
            val bytes: ByteArray; val mime: String; var rangeStart = 0L; var rangeEnd = -1L; var partial = false
            if (type == "image" || type == "pdf") {
                val image = if (raster == null) original.image().bitmap else original.pdfPage(raster.page, 1536)
                try { bytes = ByteArrayOutputStream().use { stream -> check(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray() } }
                finally { image.recycle() }; mime = "image/png"
            } else {
                check(type == "audio" || type == "video")
                val size = pin.source.sizeBytes; check(size > 0)
                val range = headers.entries.firstOrNull { it.key.equals("Range", true) }?.value
                rangeEnd = size - 1
                if (range != null) {
                    val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range) ?: return blocked(416)
                    if (match.groupValues[1].isBlank()) { val suffix = match.groupValues[2].toLongOrNull() ?: return blocked(416); if (suffix <= 0) return blocked(416); rangeStart = (size - suffix).coerceAtLeast(0) }
                    else { rangeStart = match.groupValues[1].toLongOrNull() ?: return blocked(416); if (match.groupValues[2].isNotBlank()) rangeEnd = (match.groupValues[2].toLongOrNull() ?: return blocked(416)).coerceAtMost(size - 1) }
                    if (rangeStart >= size || rangeEnd < rangeStart) return blocked(416)
                    partial = true
                }
                bytes = original.openMediaDescriptor().use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    var skipped = 0L
                    while (skipped < rangeStart) { requireCurrent(pin); val amount = input.skip(rangeStart - skipped); check(amount > 0); skipped += amount }
                    val output = ByteArray((rangeEnd - rangeStart + 1).toInt()); var offset = 0
                    while (offset < output.size) { requireCurrent(pin); val count = input.read(output, offset, output.size - offset); check(count > 0); offset += count }; output
                } }; mime = pin.source.mimeType
            }
            requireCurrent(pin); original.current()
            val responseHeaders = mutableMapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff", "Content-Length" to bytes.size.toString())
            if (type == "audio" || type == "video") responseHeaders["Accept-Ranges"] = "bytes"
            if (partial) responseHeaders["Content-Range"] = "bytes $rangeStart-$rangeEnd/${pin.source.sizeBytes}"
            val stream = object : ByteArrayInputStream(bytes) {
                override fun read(): Int { requireCurrent(pin); return super.read() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int { requireCurrent(pin); return super.read(buffer, offset, length) }
                override fun close() { java.util.Arrays.fill(bytes, 0.toByte()); super.close() }
            }
            WebResourceResponse(mime, null, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", responseHeaders, stream).also {
                if (raster != null) {
                    check(pin.pdfRaster === raster) { "原 PDF 显示请求已变化" }
                    raster.rendered = true
                }
            }
        } catch (_: Exception) {
            if (raster != null) {
                val current = runCatching { requireCurrent(pin); pin.source.requireCurrent(pin.lifetime) }.isSuccess
                main.post {
                    if (pins[pin.id] === pin && !pin.lifetime.isCancelled && visible() &&
                        pin.accepts(pin.ticket) && pin.pdfRaster === raster) {
                        pin.pdfRaster = null
                        if (!current) { retire(pin); pin.sourceUnavailable = true }
                        pin.error = "原 PDF 第 ${raster.page} 页未能显示。"; changed()
                    }
                }
            }
            blocked()
        }
    }
    private fun retire(pin: Pin) { pin.lifetime.cancel(); pin.information = null; pin.pdfRaster = null; runCatching { pin.session?.close() }; pin.session = null; pin.busy = false }
    fun clear() { main.removeCallbacks(watchdog); val old = pins.values.toList(); pins.clear(); primary = null; comparison = null; old.forEach(::retire) }
    fun close() { closed = true; clear(); worker.shutdownNow() }
}
