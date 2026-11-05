package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import dev.pocketengine.features.local.preview.ProjectLiveWebView
import dev.pocketengine.features.ssh.SshPreviewWorkflows
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.objects
import java.util.UUID
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** Trusted controls around the original SSH Page and isolated, bridge-free dev renderer. */
internal class PocketWebRemotePreview(
    private val controller: PocketWorldController,
    private val container: FrameLayout,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
    private val taskView: () -> String?,
    private val changed: () -> Unit
) {
    companion object {
        val COMMANDS = setOf("remotePreview.openPage", "remotePreview.review", "remotePreview.start", "remotePreview.read", "remotePreview.viewport", "remotePreview.stop", "remotePreview.close", "remotePreview.openTask")
        private fun fields(args: JSONObject, vararg names: String) { require(args.keys().asSequence().toSet() == names.toSet()) { "原预览参数不完整" } }
        private fun string(args: JSONObject, name: String): String = (args.opt(name) as? String)?.takeIf { it.isNotBlank() && it.length <= 200 && '\u0000' !in it } ?: error("原预览$name 无效")
        private fun integer(args: JSONObject, name: String, range: IntRange): Int {
            val value = args.opt(name); require(value is Int || value is Long)
            return (value as Number).toLong().also { require(it in range.first.toLong()..range.last.toLong()) }.toInt()
        }
        private fun number(args: JSONObject, key: String): Double = (args.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 10_000_000 } ?: error("原预览布局无效")
    }
    private class Session(val id: String, val task: String, val page: SshPreviewWorkflows.Page, val owner: PocketWebWindowTicket) {
        var review: SshPreviewWorkflows.Review? = null
        var receipt: JSONObject? = null
        var web: ProjectLiveWebView? = null
        var viewport: JSONObject? = null
        var starting = false
        var error: String? = null
    }
    private var session: Session? = null
    private val main = Handler(Looper.getMainLooper())
    private val checking = object : Runnable {
        override fun run() {
            val original = session ?: return
            if (!current(original)) { clear(); changed(); return }
            if (original.web != null) try { original.page.mount().requireCurrent(); original.web?.requireOwnedMountCurrent() }
            catch (_: Exception) { stop(original); original.error = "原隧道已失效，预览已关闭；没有重连或重启远端。"; changed() }
            main.postDelayed(this, 1000)
        }
    }
    private val layoutChanged = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> session?.let(::layout) }
    init { container.addOnLayoutChangeListener(layoutChanged) }
    private fun current(original: Session) = session === original && controller.webPrivateOwnerVisible() && taskView() == original.task && acceptsWindow(original.owner)
    private fun view(original: Session): JSONObject = JSONObject().put("pageHandle", original.id).put("taskId", original.task)
        .put("starting", original.starting).put("mounted", original.web != null).put("hostTaskId", original.page.taskId())
        .put("receipt", original.receipt ?: JSONObject.NULL).put("error", original.error ?: JSONObject.NULL)
        .put("review", original.review?.view()?.let { PocketWebState.publicValue(it) } ?: JSONObject.NULL)
        .put("profiles", JSONArray().also { out -> controller.state.feature("ssh").optJSONArray("profiles").objects()
            .filter { it.optBoolean("hostKeyTrusted") && it.optBoolean("credentialsConfigured") }
            .forEach { out.put(PocketWebState.publicValue(it)) } })
    private fun releaseRenderer(original: Session) { original.web?.let { it.release(); container.removeView(it) }; original.web = null; original.viewport = null }
    private fun stop(original: Session) { releaseRenderer(original); original.page.stop(); original.review = null; original.starting = false }
    fun clear() { val original = session; session = null; main.removeCallbacks(checking); if (original != null) { releaseRenderer(original); original.page.close() } }
    fun close() { clear(); container.removeOnLayoutChangeListener(layoutChanged) }
    fun foreground(value: Boolean) { if (!value) clear() }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        if (action == "remotePreview.close") {
            fields(args, "pageHandle"); if (session?.id == string(args, "pageHandle")) clear()
            done(JSONObject().put("closed", true), null); changed(); return
        }
        require(controller.webPrivateOwnerVisible()) { "请回到原私人任务预览页" }
        if (action == "remotePreview.openPage") {
            fields(args, "taskId"); val task = string(args, "taskId"); require(taskView() == task) { "不是当前原任务" }
            clear(); val original = Session(UUID.randomUUID().toString(), task, controller.newRemotePreviewPage(task), captureWindow())
            original.page.setForeground(true); session = original; main.postDelayed(checking, 1000)
            done(view(original), null); changed(); return
        }
        val original = session ?: error("原 SSH 预览页已关闭")
        require(current(original) && string(args, "pageHandle") == original.id) { "原预览页面已更换，请重新核对" }
        when (action) {
            "remotePreview.review" -> {
                fields(args, "pageHandle", "profileId", "bindingHash", "remotePort", "timeoutSeconds")
                require(!original.starting && original.web == null)
                original.review = original.page.review(string(args, "profileId"), string(args, "bindingHash"), integer(args, "remotePort", 1024..65535), integer(args, "timeoutSeconds", 10..600))
                original.error = null
            }
            "remotePreview.start" -> {
                fields(args, "pageHandle", "digest", "accepted")
                require(!original.starting && original.web == null && args.opt("accepted") == true)
                val reviewed = original.review ?: error("请先核对准确转发范围")
                original.starting = true; original.error = null
                try { original.page.open(reviewed, string(args, "digest"), true) { receipt -> main.post {
                    if (current(original)) {
                        original.starting = false; original.receipt = JSONObject(receipt.toString())
                        try {
                            val lease = original.page.mount()
                            val web = ProjectLiveWebView.create(controller.webPresentationActivity(), lease, object : ProjectLiveWebView.Events {
                                override fun error(message: String) { main.post { if (current(original)) { stop(original); original.error = message.take(1600); changed() } } }
                                override fun console(level: String, text: String, source: String, line: Int) { if (level == "ERROR") main.post { if (current(original)) { original.error = "开发页面报告资源或脚本问题：" + text.take(180); changed() } } }
                            })
                            original.web = web; web.visibility = View.GONE
                            container.addView(web, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START)); layout(original)
                        } catch (failure: Exception) { stop(original); original.error = failure.message ?: "原隧道未建立，没有打开其他浏览器" }
                        changed()
                    }
                } }; original.review = null }
                catch (failure: Exception) { original.starting = false; throw failure }
            }
            "remotePreview.read" -> { fields(args, "pageHandle") }
            "remotePreview.stop" -> { fields(args, "pageHandle"); stop(original) }
            "remotePreview.openTask" -> {
                fields(args, "pageHandle"); val task = original.page.taskId(); require(task.isNotBlank()) { "尚无原隧道任务" }
                stop(original); controller.focusTask(task)
            }
            "remotePreview.viewport" -> {
                fields(args, "pageHandle", "visible", "rect", "viewportWidth", "viewportHeight")
                require(args.opt("visible") is Boolean)
                val rect = args.optJSONObject("rect") ?: error("缺少原预览布局"); fields(rect, "x", "y", "width", "height")
                listOf("x", "y", "width", "height").forEach { number(rect, it) }
                require(number(rect, "width") >= 0 && number(rect, "height") >= 0 && number(args, "viewportWidth") > 0 && number(args, "viewportHeight") > 0)
                original.viewport = JSONObject(args.toString()); layout(original)
            }
            else -> error("未知原预览操作")
        }
        done(view(original), null); changed()
    }
    private fun layout(original: Session) {
        val web = original.web ?: return; val viewport = original.viewport
        if (!current(original) || viewport == null || viewport.opt("visible") != true) { web.visibility = View.GONE; return }
        val rect = viewport.getJSONObject("rect"); val cssWidth = viewport.getDouble("viewportWidth"); val cssHeight = viewport.getDouble("viewportHeight")
        val width = (container.width - container.paddingLeft - container.paddingRight).coerceAtLeast(0); val height = (container.height - container.paddingTop - container.paddingBottom).coerceAtLeast(0)
        val left = rect.getDouble("x").coerceIn(0.0, cssWidth); val top = rect.getDouble("y").coerceIn(0.0, cssHeight)
        val right = (rect.getDouble("x") + rect.getDouble("width")).coerceIn(left, cssWidth); val bottom = (rect.getDouble("y") + rect.getDouble("height")).coerceIn(top, cssHeight)
        val pixelsWide = ((right - left) * width / cssWidth).roundToInt(); val pixelsHigh = ((bottom - top) * height / cssHeight).roundToInt()
        if (pixelsWide < 1 || pixelsHigh < 1) { web.visibility = View.GONE; return }
        web.layoutParams = FrameLayout.LayoutParams(pixelsWide, pixelsHigh, Gravity.TOP or Gravity.START).apply { leftMargin = (left * width / cssWidth).roundToInt(); topMargin = (top * height / cssHeight).roundToInt() }
        web.visibility = View.VISIBLE
    }
}
