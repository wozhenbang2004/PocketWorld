package dev.pocketengine.ui.web

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import dev.pocketengine.ui.PocketWorldController
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import org.json.JSONObject

/** A single local avatar choice. A paused system picker retains only its nonce and URI;
 * a new foreground read converts it into a draft, never into a saved profile or an upload. */
internal class PocketWebPersonalAvatar(private val controller: PocketWorldController) {
    private class Choice(val id: String) {
        var launcher: ActivityResultLauncher<Array<String>>? = null
        var returned = false
        var uri: Uri? = null
        var busy = false
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var choice: Choice? = null
    @Volatile private var workEpoch = 0L

    fun clear() { workEpoch++; choice?.launcher?.unregister(); choice = null }
    fun background() { workEpoch++; choice?.busy = false }
    private fun fields(input: JSONObject, vararg allowed: String) {
        require(input.keys().asSequence().toSet() == allowed.toSet()) { "头像选择只接受原请求标识" }
    }
    private fun selected(input: JSONObject): Choice = choice?.takeIf { input.opt("pickerId") is String && it.id == input.getString("pickerId") }
        ?: error("原头像选择已关闭，请重新选择图片")

    fun dispatch(action: String, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到原个人资料页选择头像" }
        when (action) {
            "profile.avatar.pick" -> {
                fields(input)
                check(choice?.let { !it.returned || it.busy } != true) { "请先完成原系统图片选择" }
                clear()
                val activity = controller.webPresentationActivity()
                val selected = Choice(UUID.randomUUID().toString()); choice = selected
                try {
                    val launcher = activity.activityResultRegistry.register("personal_avatar_${selected.id}", ActivityResultContracts.OpenDocument()) { uri ->
                        selected.returned = true
                        selected.launcher?.unregister(); selected.launcher = null
                        if (choice === selected) selected.uri = uri
                    }
                    selected.launcher = launcher
                    if (selected.returned) launcher.unregister() else launcher.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                    done(JSONObject().put("pickerId", selected.id).put("pending", true), null)
                } catch (failure: Exception) {
                    if (choice === selected) clear()
                    done(null, failure.message ?: "系统图片选择器未能打开")
                }
            }
            "profile.avatar.close" -> {
                fields(input, "pickerId")
                val matches = choice?.id == input.optString("pickerId")
                if (matches) clear()
                done(JSONObject().put("closed", true), null)
            }
            "profile.avatar.result" -> {
                fields(input, "pickerId")
                val selected = selected(input)
                if (!selected.returned) { done(JSONObject().put("pickerId", selected.id).put("pending", true), null); return }
                val uri = selected.uri
                if (uri == null) { selected.busy = false; done(JSONObject().put("pickerId", selected.id).put("cancelled", true), null); return }
                require(uri.scheme == "content") { "头像只能读取本次系统选择的图片" }
                check(!selected.busy) { "原头像正在读取，请等待当前回执" }
                val epoch = workEpoch; val owner = controller.webWindowTicket()
                val resolver = controller.webPresentationActivity().contentResolver
                fun current() = choice === selected && workEpoch == epoch && controller.webPrivateOwnerVisible() && controller.webWindowTicket() == owner
                selected.busy = true
                try {
                    controller.runPluginCatalogWork(Runnable {
                        val result = runCatching {
                            check(current()) { "原头像读取窗口已变化" }
                            val bytes = resolver.openInputStream(uri)?.use { stream ->
                                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                                while (true) {
                                    check(current()) { "头像读取已暂停，请回到原资料页重试" }
                                    val count = stream.read(buffer); if (count < 0) break
                                    require(output.size() + count <= 8 * 1024 * 1024) { "请选择不超过 8 MB 的头像图片" }
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            } ?: error("无法读取所选图片，请重新选择")
                            val image = avatar(bytes, ::current)
                            check(current()) { "原头像读取窗口已变化" }
                            JSONObject().put("pickerId", selected.id).put("cancelled", false).put("avatarDataUrl", image)
                        }
                        main.post {
                            if (!current()) {
                                if (choice === selected && workEpoch == epoch) selected.busy = false
                                done(null, "原头像读取窗口已变化，请回到原资料页读取；头像尚未保存"); return@post
                            }
                            // A delivered result may reach Web UI after its foreground token
                            // changes. Keep this exact choice readable until explicit close;
                            // no encoded image or profile write is retained here.
                            selected.busy = false
                            result.fold({ done(it, null) }, { done(null, it.message ?: "无法处理这张头像图片") })
                        }
                    })
                } catch (failure: Exception) { if (choice === selected) selected.busy = false; done(null, failure.message ?: "头像读取尚未开始") }
            }
            else -> error("未知头像选择操作")
        }
    }

    private fun avatar(bytes: ByteArray, current: () -> Boolean): String {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            check(current()) { "原头像读取已停止" }
            require(!info.isAnimated && info.mimeType in setOf("image/jpeg", "image/png", "image/webp")) { "头像支持静态 JPEG、PNG 或 WebP 图片" }
            val width = info.size.width; val height = info.size.height
            require(width in 1..16384 && height in 1..16384 && width.toLong() * height <= 64_000_000) { "头像原图尺寸过大，请先缩小图片" }
            val scale = minOf(1.0, 768.0 / maxOf(width, height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
        }
        try {
            check(current()) { "原头像读取已停止" }
            val edge = minOf(decoded.width, decoded.height); val size = minOf(edge, 384)
            val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                val left = (decoded.width - edge) / 2; val top = (decoded.height - edge) / 2
                Canvas(image).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(decoded, Rect(left, top, left + edge, top + edge), Rect(0, 0, size, size), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
                for (quality in intArrayOf(90, 78, 64)) {
                    check(current()) { "原头像读取已停止" }
                    val output = ByteArrayOutputStream()
                    check(image.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "头像图片压缩失败" }
                    val prefix = "data:image/jpeg;base64,"
                    val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                    if (prefix.length + encoded.length <= 300000) return prefix + encoded
                }
                error("头像图片仍过大，请选择更小的图片")
            } finally { image.recycle() }
        } finally { decoded.recycle() }
    }
}
