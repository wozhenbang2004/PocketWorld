package dev.pocketengine.ui.web

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import dev.pocketengine.features.devices.NativeDeviceWorkspaceHost as Host
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.sqrt

/** Ephemeral local-only selection. No photo/camera permission, attachment, clipboard, URL handler or network. */
internal class PocketWebPairingImage {
    private class Choice(val id: String, val window: Host.Window) {
        val expires = SystemClock.elapsedRealtime() + 120_000
        var launcher: ActivityResultLauncher<Intent>? = null
        var timeout: Runnable? = null
        @Volatile var uri: Uri? = null
        @Volatile var returned = false
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var held: Choice? = null

    fun clear(id: String? = null) {
        val item = held ?: return
        if (id != null && item.id != id) return
        held = null; item.uri = null; item.launcher?.unregister(); item.launcher = null
        item.timeout?.let { main.removeCallbacks(it) }
    }
    fun pick(activity: ComponentActivity, id: String, window: Host.Window) {
        require(UUID.fromString(id).toString() == id) { "请选择当前页面的一张邀请图片" }
        clear()
        val item = Choice(id, window); held = item
        val expire = Runnable { if (held === item) clear(id) }; item.timeout = expire; main.postDelayed(expire, 120_000)
        try {
            val picker = activity.activityResultRegistry.register("pocket_pair_image_$id", ActivityResultContracts.StartActivityForResult()) { result ->
                val data = result.data
                item.launcher?.unregister(); item.launcher = null
                if (held === item && SystemClock.elapsedRealtime() < item.expires) {
                    val clip = data?.clipData
                    val single = clip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
                    item.uri = (data?.data ?: single)?.takeIf { result.resultCode == Activity.RESULT_OK && (clip == null || single == it) && it.scheme == "content" }
                    item.returned = true // No background decode or automatic pairing preview.
                }
            }
            item.launcher = picker
            if (item.returned) { picker.unregister(); item.launcher = null }
            else picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp"))
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        } catch (_: Exception) { clear(id); error("系统单张图片选择器未能打开；仍可粘贴对方的原邀请。") }
    }

    fun current(id: String, window: Host.Window): Boolean =
        held?.let { it.id == id && it.window === window && SystemClock.elapsedRealtime() < it.expires } == true

    /** Worker lane only. On return expose the PC's exact BYTE segment to the ORIGINAL
     * invitation inspector, never the URI, image or carrier to web/model/log/storage. */
    fun read(id: String, window: Host.Window, resolver: ContentResolver, current: () -> Unit): String? {
        val item = held?.takeIf { it.id == id && it.window === window } ?: error("原图片选择已关闭或到期，请重新选图。")
        fun checkChoice() {
            current()
            check(held === item && SystemClock.elapsedRealtime() < item.expires) { "原图片选择已变化，请回到配对页重新选择。" }
        }
        checkChoice()
        check(item.returned) { "请先在系统选择器选好一张图片，再回到此页核对。" }
        val uri = item.uri ?: return null
        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(16_384)
                while (true) {
                    checkChoice()
                    val n = input.read(buf); if (n < 0) break
                    require(n > 0 && out.size() + n <= 8 * 1024 * 1024) { "请选择不超过 8 MB 的静态二维码图片。" }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: error("未能读取这张已选图片，请重新选择。")
        } catch (_: java.io.IOException) { error("选中的图片无法读取，请重新选择，或粘贴原邀请。") }
        catch (_: SecurityException) { error("这张图片的临时读取已失效，请重新选择。") }
        checkChoice()
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                checkChoice()
                val w = info.size.width; val h = info.size.height
                require(!info.isAnimated && info.mimeType in setOf("image/jpeg", "image/png", "image/webp") &&
                    w in 1..16_384 && h in 1..16_384 && w.toLong() * h <= 64_000_000) { "请选择尺寸适中的静态 JPEG、PNG 或 WebP 图片。" }
                val factor = minOf(1.0, 2048.0 / maxOf(w, h), sqrt(2_000_000.0 / (w.toLong() * h)))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(maxOf(1, (w * factor).toInt()), maxOf(1, (h * factor).toInt()))
            }
        } catch (_: ImageDecoder.DecodeException) { error("图片内容不完整或无法解码，请重新选择。") }
        try {
            checkChoice()
            val w = bitmap.width; val h = bitmap.height
            require(w in 1..2048 && h in 1..2048 && w.toLong() * h <= 2_000_000) { "图片解码尺寸超出范围，请先裁出完整二维码。" }
            val pixels = IntArray(w * h); bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val frame = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))
            val codes = try { QRCodeMultiReader().decodeMultiple(frame, mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")) }
                catch (_: ReaderException) { error("没有读到完整二维码。请保留四周白边，重新拍摄或粘贴原邀请。") }
            check(codes.isNotEmpty()) { "未识别到二维码，请保留完整白边并选一张清晰照片。" }
            val code = codes.singleOrNull() ?: error("只接受一个完整二维码，请单独选择这次原邀请。")
            // ZXing combines structured-append symbols into a synthetic result with
            // no detector points/ECC. Both native senders emit one physical, level-M symbol.
            require((code.resultPoints?.size ?: 0) in 3..4 && code.resultMetadata?.get(ResultMetadataType.ERROR_CORRECTION_LEVEL) == "M") {
                "请使用发起设备显示的单个原二维码，不接受拼接码。"
            }
            val octets = (code.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<*>)?.singleOrNull() as? ByteArray
                ?: error("这不是发起设备显示的完整邀请二维码；也可粘贴原邀请。")
            require(octets.size in 1..2200) { "二维码超出原邀请的显示范围；请复制原邀请。" }
            val raw = try { StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(octets)).toString() }
                catch (_: java.nio.charset.CharacterCodingException) { error("二维码不是有效的原邀请文本。") }
            require(raw == code.text) { "二维码包含超出原邀请的内容，未读取为邀请。" }
            checkChoice()
            return raw
        } finally { bitmap.recycle() }
    }
}
