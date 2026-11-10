package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import dev.pocketengine.features.local.preview.ProjectLiveWebView
import dev.pocketengine.features.local.runtime.ProjectDevSession
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.UiTask
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Original observed local service, in its original isolated WebView; never a URL supplied by React. */
internal class PocketWebProjectLivePreview(
    private val controller: PocketWorldController,
    private val container: FrameLayout,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
    private val changed: () -> Unit,
    private val readTask: (String, (UiTask?, String?) -> Unit) -> Unit
) {
    constructor(controller: PocketWorldController, container: FrameLayout, captureWindow: () -> PocketWebWindowTicket,
        acceptsWindow: (PocketWebWindowTicket) -> Boolean, changed: () -> Unit) : this(controller, container, captureWindow, acceptsWindow, changed, controller::readTaskForReview)
    private class Page(val id: String, val task: String, val checkout: String, val window: PocketWebWindowTicket, val taskView: String) {
        val services = linkedMapOf<String, String>()
        var selected: String? = null
        var observed: ProjectDevSession.Observed? = null
        var web: ProjectLiveWebView? = null
        var attempted = false
        var visible = false
        var viewport: JSONObject? = null
        var error: String? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private var page: Page? = null
    private var foreground = false
    private var closed = false
    private var opening = 0L
    private val layoutChanged = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> page?.let(::layout) }
    private val watch = object : Runnable {
        override fun run() {
            val selected = page ?: return
            if (!current(selected)) { clear(); return }
            sync(selected)
            if (page === selected && selected.selected != null && (controller.projectLivePreviewChecking || selected.observed != null)) {
                selected.observed?.let(controller::checkProjectLivePreview)
                main.postDelayed(this, if (controller.projectLivePreviewChecking) 32 else 1200)
            }
        }
    }
    init { container.addOnLayoutChangeListener(layoutChanged) }
    companion object {
        val COMMANDS = setOf("projectLive.open", "projectLive.show", "projectLive.read", "projectLive.refresh", "projectLive.viewport", "projectLive.reload", "projectLive.close")
        private fun fields(input: JSONObject, vararg names: String) { require(input.keys().asSequence().toSet() == names.toSet()) { "活页面参数不完整或包含替代范围" } }
        private fun text(input: JSONObject, key: String): String { val raw = input.opt(key); require(raw is String && raw.isNotBlank() && raw.length <= 200 && '\u0000' !in raw); return raw }
        private fun number(input: JSONObject, key: String): Double { val raw = input.opt(key); require(raw is Number); return raw.toDouble().also { require(it.isFinite() && kotlin.math.abs(it) <= 10_000_000) } }
        internal fun services(task: UiTask, checkout: String): List<JSONObject> {
            val raw = task.raw; val rows = raw.optJSONArray("operations") ?: return emptyList()
            return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filter { row ->
                val receipt = row.optJSONObject("receipt"); val actual = receipt?.optJSONObject("executionReceipt"); val route = receipt?.optJSONObject("projectExecution")
                task.status == "running" && route != null && actual != null && row.optString("state") == "project_running" && row.optString("runId") == raw.optString("runId") &&
                    route.optInt("coordinatorEpoch", -1) == raw.optInt("coordinatorEpoch", -2) && route.optString("checkoutId") == checkout &&
                    actual.optJSONObject("devWatch")?.opt("nativeSessionCreated") == true && actual.optString("status") == "running" &&
                    actual.opt("cancelRequested") != true && actual.opt("resourcesReleased") != true && row.optString("operationId").isNotBlank()
            }
        }
    }
    private fun current(selected: Page) = !closed && foreground && page === selected && controller.webPrivateOwnerVisible() && acceptsWindow(selected.window)
    private fun selected(input: JSONObject): Page = checkNotNull(page?.takeIf { it.id == text(input, "pageHandle") && current(it) }) { "原活页面窗口已关闭或换代" }
    private fun view(selected: Page): JSONObject {
        val choices = JSONArray(); selected.services.forEach { (handle, body) -> val service = JSONObject(body); choices.put(JSONObject().put("serviceHandle", handle).put("executionId", service.getString("operationId"))
            .put("sourceGeneration", service.optJSONObject("receipt")?.optJSONObject("executionReceipt")?.opt("sourceGeneration") ?: JSONObject.NULL)) }
        val metadata = selected.observed?.metadata()?.also { it.remove("baseUrl"); it.remove("entryUrl") }
        return JSONObject().put("pageHandle", selected.id).put("taskId", selected.task).put("checkoutId", selected.checkout).put("services", choices)
            .put("starting", controller.projectLivePreviewChecking && selected.selected != null).put("mounted", selected.web != null).put("observed", metadata ?: JSONObject.NULL)
            .put("error", selected.error ?: JSONObject.NULL).put("serviceStopRequested", false)
    }
    fun snapshot(): JSONObject? = page?.takeIf(::current)?.let(::view)
    fun dispatch(action: String, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        if (action == "projectLive.close") {
            fields(input, "pageHandle"); if (page?.id == text(input, "pageHandle")) clear()
            done(JSONObject().put("closed", true).put("serviceStopRequested", false), null); return
        }
        check(!closed && foreground && controller.webPrivateOwnerVisible()) { "请回到原私人项目的前台页面" }
        if (action == "projectLive.open") {
            fields(input, "taskId"); val id = text(input, "taskId"); clear(); val request = ++opening; val window = captureWindow()
            readTask(id) { task, error ->
                if (closed || !foreground || request != opening || !acceptsWindow(window) || !controller.webPrivateOwnerVisible()) { done(null, "原项目页面已变化，未打开迟到的服务目录"); return@readTask }
                if (task == null || error != null) { done(null, error ?: "原项目任务尚未取得"); return@readTask }
                runCatching {
                    check(task.id == id && task.raw.optString("actorId") == "owner" && task.raw.optString("spaceId") == "private" && task.raw.optJSONObject("origin") == null)
                    val checkout = task.raw.optJSONObject("developmentContext")?.optString("checkoutId")?.takeIf { it.isNotBlank() } ?: error("原任务没有手机工作副本")
                    val selected = Page(UUID.randomUUID().toString(), id, checkout, window, task.raw.toString())
                    services(task, checkout).forEach { selected.services[UUID.randomUUID().toString()] = it.toString() }; page = selected; view(selected)
                }.fold({ done(it, null) }, { done(null, it.message ?: "原项目服务不可核对") }); changed()
            }
            return
        }
        val selected = selected(input)
        when (action) {
            "projectLive.show" -> {
                fields(input, "pageHandle", "serviceHandle"); val original = JSONObject(selected.services[text(input, "serviceHandle")] ?: error("请从原服务目录选择")); val request = ++opening
                readTask(selected.task) { task, error ->
                    if (!current(selected) || request != opening) { done(null, "原活页面选择已变化"); return@readTask }
                    if (task == null || error != null) { done(null, error ?: "原服务任务尚未取得"); return@readTask }
                    runCatching {
                        val actual = services(task, selected.checkout).firstOrNull { it.getString("operationId") == original.getString("operationId") && it.optString("runId") == original.optString("runId") }
                            ?: error("原开发服务已停止或换代，请刷新原任务")
                        releaseWeb(selected); selected.selected = actual.getString("operationId"); selected.error = null; selected.attempted = false
                        controller.projectLivePreviewForeground(true); controller.openProjectLivePreview(selected.task, selected.checkout, actual.getString("operationId"), false)
                        check(controller.projectLivePreviewChecking) { "原服务观察未开始" }
                        main.removeCallbacks(watch); main.post(watch); view(selected)
                    }.fold({ done(it, null) }, { done(null, it.message ?: "原运行页面未打开") }); changed()
                }
                return
            }
            "projectLive.read" -> { fields(input, "pageHandle"); sync(selected); done(view(selected), null) }
            "projectLive.refresh" -> {
                fields(input, "pageHandle"); val request = ++opening
                readTask(selected.task) { task, error ->
                    if (!current(selected) || request != opening) { done(null, "原活页面已变化，未应用迟到的服务目录"); return@readTask }
                    if (task == null || error != null) { done(null, error ?: "原项目任务尚未取得"); return@readTask }
                    runCatching {
                        check(task.id == selected.task && task.raw.optString("actorId") == "owner" && task.raw.optString("spaceId") == "private" && task.raw.optJSONObject("origin") == null)
                        check(task.raw.optJSONObject("developmentContext")?.optString("checkoutId") == selected.checkout) { "原任务工作副本已变化，请重新打开服务目录" }
                        val previous = selected.services.map { (handle, body) -> handle to JSONObject(body) }
                        val refreshed = linkedMapOf<String, String>()
                        services(task, selected.checkout).forEach { service ->
                            // Retain the same choice while its original execution is unchanged.
                            val handle = previous.firstOrNull { (_, original) ->
                                original.optString("operationId") == service.optString("operationId") && original.optString("runId") == service.optString("runId") &&
                                    original.optJSONObject("receipt")?.optJSONObject("projectExecution")?.optInt("coordinatorEpoch") == service.optJSONObject("receipt")?.optJSONObject("projectExecution")?.optInt("coordinatorEpoch")
                            }?.first ?: UUID.randomUUID().toString()
                            refreshed[handle] = service.toString()
                        }
                        selected.services.clear(); selected.services.putAll(refreshed)
                        sync(selected); check(current(selected)) { "原活页面窗口已关闭，请重新打开原任务" }; view(selected)
                    }.fold({ done(it, null) }, { done(null, it.message ?: "原项目服务目录未刷新") }); changed()
                }
                return
            }
            "projectLive.reload" -> { fields(input, "pageHandle"); checkNotNull(selected.observed).let(controller::checkProjectLivePreview); checkNotNull(selected.web).reload(); done(view(selected), null) }
            "projectLive.viewport" -> {
                fields(input, "pageHandle", "visible", "rect", "viewportWidth", "viewportHeight"); val visible = input.get("visible"); require(visible is Boolean)
                val rect = input.getJSONObject("rect"); fields(rect, "x", "y", "width", "height"); listOf("x", "y", "width", "height").forEach { number(rect, it) }
                require(number(rect, "width") >= 0 && number(rect, "height") >= 0 && number(input, "viewportWidth") > 0 && number(input, "viewportHeight") > 0)
                selected.viewport = JSONObject(input.toString()); selected.visible = visible; layout(selected); done(view(selected), null)
            }
            else -> error("未知原项目活页面操作")
        }
        changed()
    }
    private fun sync(selected: Page) {
        if (!current(selected)) { clear(); return }
        val observed = controller.projectLivePreview
        if (selected.observed != null && selected.observed !== observed) { releaseWeb(selected); selected.error = "原开发服务已停止或当前性未知，页面已关闭；请核对原任务"; selected.selected = null; changed(); return }
        if (observed == null) {
            if (selected.selected != null && !controller.projectLivePreviewChecking) { selected.error = "原服务连接尚未确认，请核对原任务后重新打开"; selected.selected = null; changed() }
            return
        }
        if (selected.attempted) return
        if (controller.projectLivePreviewTaskId != selected.task || observed.reference.checkoutId() != selected.checkout || observed.reference.operationId() != selected.selected) { clear(); return }
        selected.observed = observed; selected.attempted = true
        try {
            selected.web = ProjectLiveWebView.create(controller.webPresentationActivity(), observed, object : ProjectLiveWebView.Events {
                override fun error(message: String) { if (current(selected)) { selected.error = message.take(1600); changed() }; controller.recordProjectLivePreviewConsole(observed, selected.task, "RENDER_ERROR", message, "", 0) }
                override fun console(level: String, text: String, source: String, line: Int) { controller.recordProjectLivePreviewConsole(observed, selected.task, level, text, source, line) }
            }).also { web -> web.visibility = View.GONE; web.onPause(); container.addView(web, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START)) }
            layout(selected)
        } catch (failure: Exception) { selected.error = failure.message ?: "当前浏览器暂不能打开原独立预览" }
        changed()
    }
    private fun layout(selected: Page) {
        val web = selected.web ?: return; val viewport = selected.viewport
        if (!current(selected) || !selected.visible || viewport == null) { web.visibility = View.GONE; web.onPause(); return }
        val rect = viewport.getJSONObject("rect"); val cssWidth = viewport.getDouble("viewportWidth"); val cssHeight = viewport.getDouble("viewportHeight")
        val width = (container.width - container.paddingLeft - container.paddingRight).coerceAtLeast(0); val height = (container.height - container.paddingTop - container.paddingBottom).coerceAtLeast(0)
        val left = rect.getDouble("x").coerceIn(0.0, cssWidth); val top = rect.getDouble("y").coerceIn(0.0, cssHeight)
        val right = (rect.getDouble("x") + rect.getDouble("width")).coerceIn(left, cssWidth); val bottom = (rect.getDouble("y") + rect.getDouble("height")).coerceIn(top, cssHeight)
        val pixelsWide = ((right - left) * width / cssWidth).roundToInt(); val pixelsHigh = ((bottom - top) * height / cssHeight).roundToInt()
        if (pixelsWide < 1 || pixelsHigh < 1) { web.visibility = View.GONE; web.onPause(); return }
        web.layoutParams = FrameLayout.LayoutParams(pixelsWide, pixelsHigh, Gravity.TOP or Gravity.START).apply { leftMargin = (left * width / cssWidth).roundToInt(); topMargin = (top * height / cssHeight).roundToInt() }
        web.onResume(); web.visibility = View.VISIBLE
    }
    private fun releaseWeb(selected: Page) { selected.web?.let { it.release(); container.removeView(it) }; selected.web = null; selected.observed = null; selected.viewport = null; selected.visible = false }
    fun foreground(value: Boolean) { foreground = value; controller.projectLivePreviewForeground(value); if (!value) clear() }
    fun clear() { opening++; main.removeCallbacks(watch); page?.let(::releaseWeb); page = null; controller.closeProjectLivePreview(); changed() }
    fun close() { if (closed) return; closed = true; foreground = false; clear(); controller.projectLivePreviewForeground(false); container.removeOnLayoutChangeListener(layoutChanged) }
}
