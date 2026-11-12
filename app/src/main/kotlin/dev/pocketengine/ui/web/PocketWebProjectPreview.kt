package dev.pocketengine.ui.web

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import dev.pocketengine.features.local.preview.ProjectBundleWebView
import dev.pocketengine.features.local.preview.ProjectPreviewDocument
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject
import kotlin.math.roundToInt

/** Original isolated renderer, laid out by the trusted Web controls. No bridge is installed here. */
internal class PocketWebProjectPreview(
    private val controller: PocketWorldController,
    private val container: FrameLayout,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
    private val changed: () -> Unit
) {
    companion object {
        val COMMANDS = setOf("projects.preview", "projects.preview.mode", "projects.preview.viewport",
            "projects.preview.reload", "projects.preview.close", "projects.preview.return", "composer.preview.remove",
            "projects.package.preview.inspect", "projects.package.preview.open", "projects.package.export")
        private fun fields(args: JSONObject, names: Set<String>) {
            require(args.keys().asSequence().toSet() == names) { "预览操作参数不完整或包含未接入字段" }
        }
        private fun text(args: JSONObject, key: String): String = (args.opt(key) as? String)
            ?.takeIf { it.isNotBlank() && it.length <= 200 } ?: error("预览身份参数无效：$key")
        private fun boolean(args: JSONObject, key: String): Boolean = args.opt(key) as? Boolean ?: error("预览开关必须为布尔值：$key")
        private fun integer(args: JSONObject, key: String): Long {
            val value = args.opt(key); require(value is Int || value is Long) { "预览版本参数必须为确切整数：$key" }
            return (value as Number).toLong()
        }
        private fun number(args: JSONObject, key: String): Double {
            val value = args.opt(key); require(value is Number) { "预览布局参数必须为数值：$key" }
            return value.toDouble().also { require(it.isFinite() && kotlin.math.abs(it) <= 10_000_000) { "预览布局超出范围" } }
        }
    }
    private class Session(val original: PocketWorldController.RegisteredProjectPreview, val document: ProjectPreviewDocument) {
        lateinit var web: ProjectBundleWebView
        val id: String = document.id()
        var visible = true
        var error: String? = null
        var selectionEpoch = 0L
        var target: PocketWorldController.PreviewDraftTarget? = null
        var window: PocketWebWindowTicket? = null
        var viewport: JSONObject? = null
        var selectionAvailable = original.selectionAvailable
        var selectionUnavailableReason = original.selectionUnavailableReason
    }
    private var session: Session? = null
    private var closed = false
    private var opening = 0L
    private var foreground = true
    private val layoutChanged = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> session?.let(::layout) }
    init { container.addOnLayoutChangeListener(layoutChanged) }

    fun snapshot(): JSONObject? = session?.let { selected ->
        JSONObject().put("kind", "bundle").put("metadata", selected.original.bundle.metadata())
            .put("taskId", selected.original.taskId).put("conversationId", selected.original.conversationId)
            .put("sessionId", selected.id).put("documentGeneration", selected.document.generation())
            .put("ready", selected.document.ready()).put("selecting", selected.web.isSelectionEnabled)
            .put("visible", selected.visible).put("error", selected.error ?: JSONObject.NULL)
            .put("selectionAvailable", selected.selectionAvailable)
            .put("selectionUnavailableReason", selected.selectionUnavailableReason ?: JSONObject.NULL)
            .also { if (selected.original.packageOperationId != null) it.put("packageOperationId", selected.original.packageOperationId) }
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(!closed && foreground && controller.webPrivateOwnerVisible()) { "原私人预览窗口已关闭" }
        if (action == "projects.package.preview.inspect") {
            fields(args, setOf("taskId", "operationId")); val task = text(args, "taskId"); val operation = text(args, "operationId")
            controller.projectPackages.invalidateTaskExportSelection()
            val window = captureWindow(); val request = ++opening
            controller.inspectProjectPackagePreview(task, operation, { !closed && foreground && opening == request && acceptsWindow(window) }) { choices, error ->
                if (closed || opening != request || !acceptsWindow(window) || !controller.webPrivateOwnerVisible()) done(null, "原项目包窗口已变化，未接收迟到的入口清单")
                else done(choices, error)
                changed()
            }
            return
        }
        if (action == "projects.package.preview.open") {
            fields(args, setOf("taskId", "operationId", "entryPath")); val task = text(args, "taskId"); val operation = text(args, "operationId")
            val entry = args.opt("entryPath") as? String ?: error("原项目包入口必须为路径文字")
            dev.pocketengine.features.workspace.VersionedWorkspace.path(entry)
            controller.projectPackages.invalidateTaskExportSelection()
            val window = captureWindow(); val request = ++opening
            controller.openRegisteredProjectPackagePreview(task, operation, entry, { !closed && foreground && opening == request && acceptsWindow(window) }) { selected, error ->
                if (selected == null || error != null) { done(null, error ?: "原固定包预览未读取"); return@openRegisteredProjectPackagePreview }
                if (closed || opening != request || !acceptsWindow(window) || !controller.webPrivateOwnerVisible()) { done(null, "原预览窗口已变化"); return@openRegisteredProjectPackagePreview }
                try { show(selected); done(snapshot(), null) } catch (failure: Exception) { done(null, failure.message ?: "原固定包预览未能打开") }
                changed()
            }
            return
        }
        if (action == "projects.package.export") {
            fields(args, setOf("taskId", "operationId", "artifactId", "artifactSha256"))
            val task = text(args, "taskId"); val operation = text(args, "operationId")
            val artifact = text(args, "artifactId"); val sha = text(args, "artifactSha256")
            require(sha.matches(Regex("[a-f0-9]{64}"))) { "固定包摘要无效" }
            val window = captureWindow(); val request = ++opening
            controller.offerProjectPackageExport(task, operation, artifact, sha,
                { !closed && foreground && opening == request && acceptsWindow(window) }) { result, error ->
                if (closed || opening != request || !acceptsWindow(window) || !controller.webPrivateOwnerVisible()) done(null, "原导出窗口已变化，请核对原固定包的导出状态；没有重新请求")
                else done(result, error)
                changed()
            }
            return
        }
        if (action == "projects.preview") {
            fields(args, setOf("projectId")); val project = text(args, "projectId")
            controller.projectPackages.invalidateTaskExportSelection()
            val window = captureWindow(); val request = ++opening
            controller.openRegisteredProjectPreview(project, { !closed && foreground && opening == request && acceptsWindow(window) }) { selected, error ->
                if (selected == null || error != null) { done(null, error ?: "原项目预览未读取"); return@openRegisteredProjectPreview }
                if (closed || opening != request || !acceptsWindow(window) || !controller.webPrivateOwnerVisible()) { done(null, "原预览窗口已变化"); return@openRegisteredProjectPreview }
                try { show(selected); done(snapshot(), null) } catch (failure: Exception) { done(null, failure.message ?: "原预览未能打开") }
                changed()
            }
            return
        }
        if (action == "composer.preview.remove") {
            fields(args, setOf("key", "epoch", "revision", "index"))
            val epoch = integer(args, "epoch"); val index = integer(args, "index"); val revision = integer(args, "revision")
            require(epoch in 0..Int.MAX_VALUE.toLong() && index in 0..Int.MAX_VALUE.toLong() && revision >= 0) { "原草稿版本或引用位置无效" }
            controller.removeComposerPreview(index.toInt(), text(args, "key"), epoch.toInt(), revision)
            done(JSONObject().put("removed", true).put("draftStaged", true), null); changed(); return
        }
        val selected = session ?: error("原预览已关闭，请从项目重新打开")
        require(text(args, "sessionId") == selected.id) { "原预览文档已变化，未操作新页面" }
        when (action) {
            "projects.preview.mode" -> {
                fields(args, setOf("sessionId", "enabled")); val enabled = boolean(args, "enabled")
                disableSelection(selected)
                if (enabled) {
                    require(selected.visible && selected.document.ready()) { "请等待原预览显示完成再选择元素" }
                    val target = controller.captureProjectPreviewDraft(selected.original.conversationId)
                    val window = captureWindow(); val epoch = selected.selectionEpoch; val generation = selected.document.generation()
                    fun current() = !closed && foreground && session === selected && selected.selectionEpoch == epoch &&
                        selected.visible && selected.document.ready() && selected.document.generation() == generation && acceptsWindow(window)
                    controller.checkProjectPreviewSelection(selected.original, ::current) { available, reason ->
                        if (!current()) { done(null, "原文档或选择窗口已变化，未启用迟到选择"); return@checkProjectPreviewSelection }
                        selected.selectionAvailable = available; selected.selectionUnavailableReason = reason
                        if (!available) { selected.error = reason; done(snapshot(), reason ?: "原预览来源暂不可核对") }
                        else try {
                            selected.web.setSelectionEnabled(true); selected.target = target; selected.window = window; selected.error = null
                            done(snapshot(), null)
                        } catch (failure: Exception) { selected.error = failure.message; done(null, failure.message ?: "原文档尚不能选择") }
                        changed()
                    }
                    return
                }
                done(snapshot(), null)
            }
            "projects.preview.viewport" -> {
                fields(args, setOf("sessionId", "visible", "rect", "viewportWidth", "viewportHeight"))
                val visible = boolean(args, "visible"); val rect = args.optJSONObject("rect") ?: error("缺少原预览布局")
                fields(rect, setOf("x", "y", "width", "height"))
                for (key in listOf("x", "y", "width", "height")) number(rect, key)
                require(number(rect, "width") >= 0 && number(rect, "height") >= 0 && number(args, "viewportWidth") > 0 && number(args, "viewportHeight") > 0)
                selected.viewport = JSONObject(args.toString()); selected.visible = visible
                if (!visible) disableSelection(selected)
                layout(selected); done(snapshot(), null)
            }
            "projects.preview.reload" -> {
                fields(args, setOf("sessionId")); disableSelection(selected); selected.error = null; selected.web.reload()
                refreshSelectionAvailability(selected); done(snapshot(), null)
            }
            "projects.preview.return" -> {
                fields(args, setOf("sessionId")); hide(selected)
                controller.projectPackages.invalidateTaskExportSelection()
                controller.returnToProjectPreviewConversation(selected.original.conversationId)
                changed()
                done(JSONObject().put("sessionId", selected.id).put("conversationId", selected.original.conversationId).put("returned", true), null)
            }
            "projects.preview.close" -> {
                fields(args, setOf("sessionId")); release(); controller.closeProjectPreview()
                controller.projectPackages.invalidateTaskExportSelection()
                done(JSONObject().put("sessionId", selected.id).put("closed", true), null)
            }
            else -> error("未接入的原预览操作")
        }
        changed()
    }

    private fun show(original: PocketWorldController.RegisteredProjectPreview) {
        release()
        val document = ProjectPreviewDocument(original.bundle); val selected = Session(original, document)
        val web = ProjectBundleWebView(controller.webPresentationActivity(), document, object : ProjectBundleWebView.Events {
            override fun error(message: String) {
                if (session === selected) { selected.error = message.take(1600); changed() }
                if (original.packageOperationId == null) controller.recordProjectPreviewConsole(original.bundle, original.taskId, "RENDER_ERROR", message, original.bundle.entryUrl, 0)
            }
            override fun console(level: String, text: String, source: String, line: Int) {
                if (original.packageOperationId == null) controller.recordProjectPreviewConsole(original.bundle, original.taskId, level, text, source, line)
            }
        }, object : ProjectBundleWebView.SelectionEvents {
            override fun changed() { if (session === selected) this@PocketWebProjectPreview.changed() }
            override fun selected(selection: ProjectPreviewDocument.Selection) { receiveSelection(selected, selection) }
        })
        selected.web = web; session = selected; web.visibility = View.GONE
        container.addView(web, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
    }

    private fun receiveSelection(selected: Session, selection: ProjectPreviewDocument.Selection) {
        val target = selected.target; val window = selected.window; val epoch = selected.selectionEpoch
        fun current() = !closed && foreground && session === selected && selected.selectionEpoch == epoch &&
            selected.visible && window != null && acceptsWindow(window) && controller.webPrivateOwnerVisible()
        if (target == null || !current()) { selected.web.setSelectionEnabled(false); return }
        // End only the mode. The captured native Selection remains live until document/source changes.
        selected.web.setSelectionEnabled(false); selected.target = null; selected.window = null
        try {
            controller.stageProjectPreviewSelection(selected.original.taskId, target, selection, ::current, selected.original.packageOperationId) { receipt, error ->
                if (session === selected && selected.selectionEpoch == epoch) {
                    selected.error = error
                    if (receipt?.opt("selectionAvailable") == false) { selected.selectionAvailable = false; selected.selectionUnavailableReason = error ?: "原选择来源暂不可核对" }
                    changed()
                }
            }
        } catch (failure: Exception) { selected.error = failure.message ?: "原元素选择未加入草稿"; changed() }
    }

    private fun refreshSelectionAvailability(selected: Session) {
        selected.selectionAvailable = false; selected.selectionUnavailableReason = "正在重新核对原预览来源"
        val window = captureWindow(); val epoch = selected.selectionEpoch
        fun current() = !closed && foreground && session === selected && selected.selectionEpoch == epoch && acceptsWindow(window)
        try { controller.checkProjectPreviewSelection(selected.original, ::current) { available, reason ->
            if (current()) { selected.selectionAvailable = available; selected.selectionUnavailableReason = reason; changed() }
        } } catch (failure: Exception) { selected.selectionUnavailableReason = failure.message ?: "原预览来源暂不可核对" }
    }
    private fun disableSelection(selected: Session) {
        selected.selectionEpoch++; selected.target = null; selected.window = null; selected.web.setSelectionEnabled(false)
    }
    private fun hide(selected: Session) { disableSelection(selected); selected.visible = false; selected.web.visibility = View.GONE }
    private fun layout(selected: Session) {
        val viewport = selected.viewport
        if (closed || !foreground || session !== selected || !selected.visible || viewport == null || !controller.webPrivateOwnerVisible()) { selected.web.visibility = View.GONE; return }
        val rect = viewport.getJSONObject("rect")
        val cssWidth = viewport.getDouble("viewportWidth"); val cssHeight = viewport.getDouble("viewportHeight")
        val width = (container.width - container.paddingLeft - container.paddingRight).coerceAtLeast(0)
        val height = (container.height - container.paddingTop - container.paddingBottom).coerceAtLeast(0)
        val left = rect.getDouble("x").coerceIn(0.0, cssWidth); val top = rect.getDouble("y").coerceIn(0.0, cssHeight)
        val right = (rect.getDouble("x") + rect.getDouble("width")).coerceIn(left, cssWidth)
        val bottom = (rect.getDouble("y") + rect.getDouble("height")).coerceIn(top, cssHeight)
        val pixelsWide = ((right - left) * width / cssWidth).roundToInt(); val pixelsHigh = ((bottom - top) * height / cssHeight).roundToInt()
        if (pixelsWide < 1 || pixelsHigh < 1) { selected.web.visibility = View.GONE; return }
        selected.web.layoutParams = FrameLayout.LayoutParams(pixelsWide, pixelsHigh, Gravity.TOP or Gravity.START).apply {
            leftMargin = (left * width / cssWidth).roundToInt(); topMargin = (top * height / cssHeight).roundToInt()
        }
        selected.web.visibility = View.VISIBLE
    }
    fun foreground(value: Boolean) {
        foreground = value
        session?.let { if (value) it.web.onResume() else { hide(it); it.web.onPause() } }
        changed()
    }
    fun invalidatePresentation(documentReplaced: Boolean) {
        opening++
        if (documentReplaced) { controller.projectPackages.invalidateTaskExportSelection(); release(); controller.closeProjectPreview() } else session?.let(::hide)
        changed()
    }
    private fun release() {
        val selected = session ?: return; session = null
        selected.selectionEpoch++; selected.web.release(); container.removeView(selected.web)
    }
    fun close() {
        if (closed) return
        closed = true; opening++; controller.projectPackages.invalidateTaskExportSelection(); release(); container.removeOnLayoutChangeListener(layoutChanged); controller.closeProjectPreview()
    }
}
