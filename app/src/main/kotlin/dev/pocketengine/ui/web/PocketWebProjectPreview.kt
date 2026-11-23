package dev.pocketengine.ui.web

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import dev.pocketengine.features.local.preview.OwnerKeptStaticPages
import dev.pocketengine.features.local.preview.ProjectBundleWebView
import dev.pocketengine.features.local.preview.ProjectPreviewBundle
import dev.pocketengine.features.local.preview.ProjectPreviewDocument
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.ProjectPackageCoordinator
import org.json.JSONObject
import kotlin.math.roundToInt

/** Original isolated renderer, laid out by the trusted Web controls. No bridge is installed here. */
internal class PocketWebProjectPreview(
    private val controller: PocketWorldController,
    private val container: FrameLayout,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
    private val keptPagesEnabled: Boolean = false,
    private val changed: () -> Unit
) {
    companion object {
        const val OPEN_LOCAL_WEB = "projects.firstWebOutput.open"
        const val PREVIEW_LOCAL_BUILD = "projects.firstWebOutput.previewBuild"
        const val EXPORT_LOCAL_WEB = "projects.firstWebOutput.export"
        const val OPEN_SAVED_STATIC = "projects.development.files.staticPreview.open"
        val COMMANDS = setOf(OPEN_LOCAL_WEB, PREVIEW_LOCAL_BUILD, EXPORT_LOCAL_WEB, OPEN_SAVED_STATIC, "projects.preview", "projects.preview.mode", "projects.preview.viewport",
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
    private class SavedStaticOwner(val request: PocketWebCheckoutFiles.SavedStaticPage, val window: PocketWebWindowTicket)
    class KeptVisit(val project: String,val visitId: String,val shelfHandle: String,val current: () -> Boolean)
    private class KeptPage(val visit: KeptVisit,val page: OwnerKeptStaticPages.Page)
    private class Session(val document: ProjectPreviewDocument,
        val original: PocketWorldController.RegisteredProjectPreview? = null, val savedStatic: SavedStaticOwner? = null,
        val kept: KeptPage? = null) {
        lateinit var web: ProjectBundleWebView
        val id: String = document.id()
        fun taskless() = savedStatic != null || kept != null
        var visible = !taskless()
        var error: String? = null
        var selectionEpoch = 0L
        // Hiding preserves previously captured Chat references; releasing this document does not.
        var handedOutSelection = false
        var target: PocketWorldController.PreviewDraftTarget? = null
        var window: PocketWebWindowTicket? = null
        var viewport: JSONObject? = null
        var savedStaticLayout: View.OnLayoutChangeListener? = null
        var selectionAvailable = original?.selectionAvailable == true
        var selectionUnavailableReason = original?.selectionUnavailableReason
    }
    private var session: Session? = null
    @Volatile private var closed = false
    @Volatile private var opening = 0L
    @Volatile private var openingStatic = 0L
    private data class RegisteredOpening(val sequence: Long, val window: PocketWebWindowTicket)
    private var registeredOpening: RegisteredOpening? = null
    private data class PendingBuildPreview(val control: dev.pocketengine.core.RunControl, val timeout: Runnable, val reject: (String) -> Unit)
    private var pendingBuildPreview: PendingBuildPreview? = null
    private data class PendingStaticPreview(val timeout: Runnable, val reject: (String) -> Unit)
    private var pendingStaticPreview: PendingStaticPreview? = null
    private var keepingStaticPage: Any? = null
    private val main = Handler(Looper.getMainLooper())
    private val keptMain = KeptStaticMainFence(main)
    private val keptStaticPages by lazy {
        OwnerKeptStaticPages(controller.webPresentationActivity().applicationContext)
    }
    @Volatile private var foreground = true
    private val layoutChanged = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        session?.let { selected ->
            layout(selected)
            if (keptPagesEnabled && selected.kept != null) changed()
        }
    }
    init { container.addOnLayoutChangeListener(layoutChanged) }

    private fun savedStaticCurrent(owner: SavedStaticOwner) = !closed && foreground &&
        acceptsWindow(owner.window) && controller.webPrivateOwnerVisible() && owner.request.current()

    fun isTasklessSession(sessionId: String) = session?.let { it.taskless() && it.id == sessionId } == true
    fun hasKeptRenderer() = session?.kept != null
    private fun keptCurrent(visit: KeptVisit) = keptPagesEnabled && !closed && foreground &&
        controller.webPrivateOwnerVisible() && visit.current()
    fun keptShelfCanRead() = keptPagesEnabled && !closed && foreground && pendingStaticPreview == null &&
        !otherRendererOpening() && session?.let { selected ->
            !selected.taskless() && !selected.visible && selected.web.visibility == View.GONE
        } != false

    /** Observe the exact passive renderer without releasing it or acquiring any resource. */
    fun pinKeptRemovalWindow(visit:KeptVisit):()->Boolean{
        val prior=session
        val originalOpening=opening
        val originalStaticOpening=openingStatic
        fun current()=keptCurrent(visit) && keptShelfCanRead() && keepingStaticPage==null &&
            session===prior && opening==originalOpening && openingStatic==originalStaticOpening
        check(current()){"请先退出网页预览并等保留完成，再核对原版本"}
        return ::current
    }

    /** Hold the exact original inactive preview across the read, not a guess made after IO. */
    fun prepareKeptMount(visit: KeptVisit): (OwnerKeptStaticPages.Opened) -> JSONObject {
        check(keptCurrent(visit) && keptShelfCanRead()) { "请先关闭其他预览，再打开这份本机网页" }
        val prior = session
        prior?.let(::requireInactiveRegistered)
        return { opened ->
            check(keptCurrent(visit) && keptShelfCanRead() && session === prior) { "原项目或预览已变化，未打开其他页面" }
            prior?.let(::requireInactiveRegistered)
            val page=opened.page();val bundle=opened.bundle()
            check(bundle.keptStaticPage() && page.projectId() == visit.project &&
                bundle.projectId == page.projectId() && bundle.checkoutId == page.checkoutId() &&
                bundle.identity == page.manifestIdentity() && bundle.entryPath == ProjectPreviewBundle.SAVED_STATIC_ENTRY &&
                bundle.resource(bundle.entryUrl)?.sha256 == page.entrySha256()) { "原保留网页的入口或清单不符" }
            val next=Session(ProjectPreviewDocument(bundle), kept = KeptPage(visit,page))
            try {
                mount(next)
                checkNotNull(snapshot()) { "原项目网页窗口已关闭" }
            } catch (failure: Exception) {
                if (session === next) release()
                throw failure
            }
        }
    }

    fun retireKept(visit: KeptVisit) {
        if (session?.kept?.visit !== visit) return
        release()
        changed()
    }

    private fun keepWitness(selected: Session,generation: Long,manifestIdentity: String): ProjectBundleWebView.VisibleSavedStaticPage {
        val owner = selected.savedStatic ?: error("只能保留原编辑器已显示的静态网页")
        val bundle = selected.document.bundle()
        check(keptPagesEnabled && savedStaticCurrent(owner) && selected.visible && selected.viewport != null && selected.error == null &&
            bundle.projectId == owner.request.project && bundle.checkoutId == owner.request.checkout &&
            bundle.resource(bundle.entryUrl)?.sha256 == owner.request.entrySha256) { "原编辑器网页尚未完整显示" }
        return selected.web.keepReview(generation,manifestIdentity)
    }

    /** No second review or source read: the actual current taskless renderer holds the approved bytes. */
    fun keepVisibleSavedStatic(sessionId: String, documentGeneration: Long, manifestIdentity: String,
                               done: (OwnerKeptStaticPages.KeepResult?) -> Unit) {
        check(Looper.myLooper() == main.looper && keepingStaticPage == null) { "请在原已保存网页等待保留完成" }
        val selected = session?.takeIf { it.id == sessionId && it.original == null } ?: error("原网页已关闭")
        val owner = selected.savedStatic ?: error("只能保留原编辑器已显示的静态网页")
        val review = keepWitness(selected,documentGeneration,manifestIdentity)
        val attempt = Any()
        keepingStaticPage = attempt
        val until = SystemClock.elapsedRealtime()+20_000L
        fun shown() = session === selected && savedStaticCurrent(owner) &&
            selected.visible && selected.viewport != null && selected.error == null &&
            runCatching { review.requireCurrent() }.isSuccess
        fun admitted() = keepingStaticPage === attempt && SystemClock.elapsedRealtime()<until && shown()
        val window = object : OwnerKeptStaticPages.PreviewWindow {
            override fun projectId() = owner.request.project
            override fun current() = try { keptMain.call(::admitted) } catch (_: Exception) { false }
            override fun atCommit(commit: OwnerKeptStaticPages.Commit) {
                keptMain.call {
                    check(admitted()) { "原网页窗口已离开，未发布保留版本" }
                    commit.enter()
                }
            }
        }
        var timeout: Runnable? = null
        fun finish(result: OwnerKeptStaticPages.KeepResult) {
            if (keepingStaticPage !== attempt) return
            timeout?.let(main::removeCallbacks)
            keepingStaticPage = null
            done(if (shown()) result else null) // Late results reconcile only from an explicit same Project shelf.
            changed()
        }
        val alarm = Runnable { finish(OwnerKeptStaticPages.KeepResult.unknown()) }
        timeout = alarm
        main.postDelayed(alarm,20_000L)
        try {
            val storage = keptStaticPages
            controller.runPluginCatalogWork(Runnable {
                val result = try { storage.keep(review, window) } catch (_: Exception) {
                    OwnerKeptStaticPages.KeepResult.unknown()
                }
                main.post { finish(result) }
            })
        } catch (_: Exception) {
            finish(OwnerKeptStaticPages.KeepResult.unknown())
        }
    }

    fun snapshot(): JSONObject? {
        val selected = session ?: return null
        selected.savedStatic?.let { owner ->
            if (!savedStaticCurrent(owner)) { release(); return null }
            val bundle = selected.document.bundle()
            val result=JSONObject().put("kind", "saved_static_page").put("sessionId", selected.id)
                .put("projectId", owner.request.project).put("checkoutId", owner.request.checkout)
                .put("entryPath", bundle.entryPath).put("entrySha256", owner.request.entrySha256)
                .put("manifestIdentity", bundle.identity).put("resources", bundle.manifest())
                .put("ready", selected.document.ready()).put("documentGeneration", selected.document.generation())
                .put("visible", selected.visible).put("error", selected.error ?: JSONObject.NULL)
            if(keptPagesEnabled && keepingStaticPage==null && runCatching {
                keepWitness(selected,selected.document.generation(),bundle.identity)
            }.isSuccess)result.put("canKeep",true)
            return result
        }
        selected.kept?.let { kept ->
            val visit=kept.visit
            if(!keptCurrent(visit)){release();return null}
            return JSONObject().put("kind","owner_kept_static_page_preview").put("projectId",visit.project)
                .put("visitId",visit.visitId).put("shelfHandle",visit.shelfHandle).put("page",kept.page.metadata())
                .put("sessionId",selected.id).put("documentGeneration",selected.document.generation())
                .put("ready",selected.document.ready()).put("visible",selected.visible && selected.web.isShown)
                .put("error",selected.error?:JSONObject.NULL)
        }
        val original = checkNotNull(selected.original)
        return JSONObject().put("kind", "bundle").put("metadata", original.bundle.metadata())
            .put("taskId", original.taskId).put("conversationId", original.conversationId)
            .put("localWebWork", original.fromLocalWebWork)
            .put("sessionId", selected.id).put("documentGeneration", selected.document.generation())
            .put("ready", selected.document.ready()).put("selecting", selected.web.isSelectionEnabled)
            .put("visible", selected.visible).put("error", selected.error ?: JSONObject.NULL)
            .put("selectionAvailable", selected.selectionAvailable)
            .put("selectionUnavailableReason", selected.selectionUnavailableReason ?: JSONObject.NULL)
            .also { if (original.packageOperationId != null) it.put("packageOperationId", original.packageOperationId) }
    }

    private fun otherRendererOpening() = pendingBuildPreview != null ||
        registeredOpening?.let { it.sequence == opening && acceptsWindow(it.window) } == true

    private fun requireInactiveRegistered(selected: Session) {
        check(selected.original != null && !selected.original.fromLocalWebWork && !selected.visible &&
            selected.web.visibility == View.GONE && !selected.web.isSelectionEnabled && selected.target == null && selected.window == null) {
            "仍有另一个预览打开。请返回原预览关闭后，再从这里查看已保存页面"
        }
        check(!selected.handedOutSelection) {
            "上一预览保留着可能仍在原对话草稿中的元素引用。请先回到原对话处理引用，再返回该预览关闭它"
        }
    }

    /** Only the original editor may issue this typed request. No task or package source is consulted. */
    fun openSavedStatic(request: PocketWebCheckoutFiles.SavedStaticPage, done: (JSONObject?, String?) -> Unit) {
        val owner = SavedStaticOwner(request, captureWindow())
        check(savedStaticCurrent(owner)) { "请从原文件打开已保存页面" }
        check(pendingStaticPreview == null && !otherRendererOpening()) { "请先等上一预览完成，再从原文件打开页面" }
        // An ordinary Back/tab switch hides the prior renderer without releasing it. The explicit
        // current editor visit may replace only that exact inactive display, never its source or Work.
        val previous = session
        previous?.let(::requireInactiveRegistered)
        val token = ++openingStatic
        val until = SystemClock.elapsedRealtime() + 20_000L
        fun current() = openingStatic == token && savedStaticCurrent(owner) && SystemClock.elapsedRealtime() < until
        val timeout = Runnable { if (openingStatic == token) cancelPendingStaticPreview("读取所选网页资源超时，可在原文件重试") }
        val pending = PendingStaticPreview(timeout) { error -> done(null, error) }
        pendingStaticPreview = pending
        container.postDelayed(timeout, 20_000L)
        try {
            controller.runPluginCatalogWork(Runnable {
                val captured = runCatching { request.read(::current) }
                container.post {
                    if (pendingStaticPreview !== pending) return@post
                    pendingStaticPreview = null
                    container.removeCallbacks(timeout)
                    if (!current()) done(null, "原文件预览已关闭或超时，没有打开其他页面")
                    else captured.mapCatching { bundle ->
                        check(bundle.projectId == request.project && bundle.checkoutId == request.checkout) { "原网页资源未对应所选副本" }
                        check(session === previous && !otherRendererOpening()) { "另一份预览已变化，没有替换它" }
                        previous?.let(::requireInactiveRegistered)
                        mount(Session(ProjectPreviewDocument(bundle), savedStatic = owner))
                        checkNotNull(snapshot()) { "原文件预览已关闭" }
                    }.fold({ done(it, null) }, { failure ->
                        if (failure is ProjectPreviewBundle.SavedStaticMissingAssets) {
                            // No mount or legacy release. This reply is only for the still-current owner review.
                            runCatching { request.missingReview(failure.paths()) }.fold({ done(it, null) },
                                { done(null, "未能核对这次所选资源，没有打开页面") })
                        } else {
                            if (session?.savedStatic === owner) release()
                            done(null, failure.message ?: "没有打开这份已保存页面")
                        }
                    })
                    changed()
                }
            })
        } catch (failure: Exception) {
            pendingStaticPreview = null
            container.removeCallbacks(timeout)
            throw failure
        }
    }

    fun dispatch(action: String, args: JSONObject, localWorkCurrent: () -> Boolean, registeredVisible: () -> Unit,
                 done: (JSONObject?, String?) -> Unit) {
        check(!closed && foreground && controller.webPrivateOwnerVisible()) { "原私人预览窗口已关闭" }
        val static = session?.takeIf { it.taskless() }
        if (static != null) {
            require(text(args, "sessionId") == static.id) { "原预览文档已变化，没有操作另一页面" }
            if (action == "projects.preview.close") {
                fields(args, setOf("sessionId")); release()
                done(JSONObject().put("sessionId", static.id).put("closed", true), null); changed(); return
            }
            require(action == "projects.preview.viewport") { "已保存页面只允许本机查看或关闭" }
            check(static.savedStatic?.let(::savedStaticCurrent) ?: keptCurrent(checkNotNull(static.kept).visit)) { "原网页窗口已关闭" }
        }
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
        if (action == PREVIEW_LOCAL_BUILD) {
            check(localWorkCurrent() && pendingBuildPreview == null) { "请在原构建中等待本次预览" }
            fields(args, setOf("taskId", "runId", "epoch", "buildOperationId"))
            val task = text(args, "taskId"); val run = text(args, "runId"); val operation = text(args, "buildOperationId")
            require(task.matches(Regex("[a-f0-9-]{36}")) && run == task && operation == task && integer(args, "epoch") == 1L) {
                "原本机构建身份不可核对"
            }
            val window = captureWindow(); val request = ++opening
            val control = dev.pocketengine.core.RunControl()
            // The native render must not arrive after the Web caller's 30 s ACK expires.
            val until = android.os.SystemClock.elapsedRealtime() + 20_000L
            fun sameWindow() = !closed && foreground && opening == request && acceptsWindow(window) && localWorkCurrent()
            fun current() = sameWindow() && android.os.SystemClock.elapsedRealtime() < until
            val timeout = Runnable {
                if (pendingBuildPreview?.control === control) cancelPendingBuildPreview("原网页预览等待超时，可在原工作再试")
            }
            val pending = PendingBuildPreview(control, timeout) { error -> done(null, error) }
            pendingBuildPreview = pending
            container.postDelayed(timeout, 20_000L)
            try {
                controller.openOriginalViteBuildPreview(task, run, 1, operation, control, ::current) { selected, error ->
                    container.removeCallbacks(timeout)
                    if (pendingBuildPreview !== pending) return@openOriginalViteBuildPreview
                    pendingBuildPreview = null
                    if (!sameWindow() || control.isCancelled) { done(null, "原构建预览窗口已变化"); return@openOriginalViteBuildPreview }
                    if (!current()) { done(null, "原网页预览等待超时，可在原工作再试"); return@openOriginalViteBuildPreview }
                    if (selected == null || error != null) { done(null, error ?: "未读取这次原网页"); return@openOriginalViteBuildPreview }
                    try { show(selected); done(snapshot(), null) } catch (failure: Exception) { done(null, failure.message ?: "原网页未能打开") }
                    changed()
                }
            } catch (failure: Exception) {
                container.removeCallbacks(timeout)
                if (pendingBuildPreview === pending) {
                    pendingBuildPreview = null
                    control.cancel()
                }
                throw failure
            }
            return
        }
        if (action == "projects.package.preview.open" || action == OPEN_LOCAL_WEB) {
            val direct = action == OPEN_LOCAL_WEB
            check(!direct || localWorkCurrent()) { "请从原本机构建打开已保存网页" }
            fields(args, setOf("taskId", "operationId", "entryPath")); val task = text(args, "taskId"); val operation = text(args, "operationId")
            val entry = args.opt("entryPath") as? String ?: error("原项目包入口必须为路径文字")
            dev.pocketengine.features.workspace.VersionedWorkspace.path(entry)
            controller.projectPackages.invalidateTaskExportSelection()
            val window = captureWindow(); val request = ++opening
            fun current() = !closed && foreground && opening == request && acceptsWindow(window) && (!direct || localWorkCurrent())
            val pending = RegisteredOpening(request, window).also { registeredOpening = it }
            try {
                controller.openRegisteredProjectPackagePreview(task, operation, entry, ::current, done = { selected, error ->
                    if (registeredOpening === pending) registeredOpening = null
                    if (selected == null || error != null) { done(null, error ?: "原固定包预览未读取"); return@openRegisteredProjectPackagePreview }
                    if (!current() || !controller.webPrivateOwnerVisible()) { done(null, "原预览窗口已变化"); return@openRegisteredProjectPackagePreview }
                    try { show(selected); done(snapshot(), null) } catch (failure: Exception) { done(null, failure.message ?: "原固定包预览未能打开") }
                    changed()
                }, fromLocalWork = direct)
            } catch (failure: Exception) {
                if (registeredOpening === pending) registeredOpening = null
                throw failure
            }
            return
        }
        if (action == EXPORT_LOCAL_WEB) {
            check(localWorkCurrent() && session == null && pendingBuildPreview == null) { "请返回这次原本机构建选择已保存网页" }
            fields(args, setOf("taskId", "runId", "epoch", "buildOperationId", "operationId", "projectId", "checkoutId", "sourceDigest", "artifactId", "artifactSha256"))
            val task = text(args, "taskId"); val run = text(args, "runId"); val build = text(args, "buildOperationId")
            val operation = text(args, "operationId"); val project = text(args, "projectId"); val checkout = text(args, "checkoutId")
            val source = text(args, "sourceDigest"); val artifact = text(args, "artifactId"); val sha = text(args, "artifactSha256")
            val id = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
            val digest = Regex("[a-f0-9]{64}")
            require(listOf(task, operation, artifact).all(id::matches) && run == task && build == task && operation != build &&
                integer(args, "epoch") == 1L && source.matches(digest) && sha.matches(digest)) { "原固定网页的构建或版本身份不可核对" }
            val window = captureWindow(); val request = ++opening
            val original = ProjectPackageCoordinator.DirectViteExport(run, 1, build, checkout, source)
            // The ordinary picker backgrounds this Activity and retires command tickets, not the same original Work visit.
            controller.projectPackages.offerDirectViteExport(task, operation, artifact, sha, project, original,
                { !closed && foreground && opening == request && acceptsWindow(window) }, localWorkCurrent) { result, error ->
                if (closed || opening != request || !acceptsWindow(window) || !localWorkCurrent())
                    done(null, "请在原工作核对这次固定包导出的回执，没有重新请求")
                else done(result, error)
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
            val pending = RegisteredOpening(request, window).also { registeredOpening = it }
            try {
                controller.openRegisteredProjectPreview(project, { !closed && foreground && opening == request && acceptsWindow(window) }) { selected, error ->
                    if (registeredOpening === pending) registeredOpening = null
                    if (selected == null || error != null) { done(null, error ?: "原项目预览未读取"); return@openRegisteredProjectPreview }
                    if (closed || opening != request || !acceptsWindow(window) || !controller.webPrivateOwnerVisible()) { done(null, "原预览窗口已变化"); return@openRegisteredProjectPreview }
                    try { show(selected); done(snapshot(), null) } catch (failure: Exception) { done(null, failure.message ?: "原预览未能打开") }
                    changed()
                }
            } catch (failure: Exception) {
                if (registeredOpening === pending) registeredOpening = null
                throw failure
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
                val original = checkNotNull(selected.original)
                fields(args, setOf("sessionId", "enabled")); val enabled = boolean(args, "enabled")
                disableSelection(selected)
                if (enabled) {
                    require(!original.fromLocalWebWork && selected.visible && selected.document.ready()) { "请等待原预览显示完成再选择元素" }
                    val target = controller.captureProjectPreviewDraft(original.conversationId)
                    val window = captureWindow(); val epoch = selected.selectionEpoch; val generation = selected.document.generation()
                    fun current() = !closed && foreground && session === selected && selected.selectionEpoch == epoch &&
                        selected.visible && selected.document.ready() && selected.document.generation() == generation && acceptsWindow(window)
                    controller.checkProjectPreviewSelection(original, ::current) { available, reason ->
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
                else if (selected.original != null) {
                    cancelPendingStaticPreview("已切回显示中的原预览，没有替换它")
                    registeredVisible()
                }
                layout(selected); done(snapshot(), null)
            }
            "projects.preview.reload" -> {
                fields(args, setOf("sessionId")); disableSelection(selected); selected.error = null; selected.web.reload()
                refreshSelectionAvailability(selected); done(snapshot(), null)
            }
            "projects.preview.return" -> {
                val original = checkNotNull(selected.original)
                fields(args, setOf("sessionId")); hide(selected)
                controller.projectPackages.invalidateTaskExportSelection()
                if (original.fromLocalWebWork) {
                    release(); controller.closeProjectPreview()
                    done(JSONObject().put("sessionId", selected.id).put("taskId", original.taskId).put("returned", true), null)
                } else {
                    controller.returnToProjectPreviewConversation(original.conversationId)
                    done(JSONObject().put("sessionId", selected.id).put("conversationId", original.conversationId).put("returned", true), null)
                }
            }
            "projects.preview.close" -> {
                fields(args, setOf("sessionId"))
                if (selected.original?.fromLocalWebWork == true) releaseExactLocalWork(selected)
                else {
                    release(); controller.closeProjectPreview()
                    controller.projectPackages.invalidateTaskExportSelection()
                }
                done(JSONObject().put("sessionId", selected.id).put("closed", true), null)
            }
            else -> error("未接入的原预览操作")
        }
        changed()
    }

    private fun show(original: PocketWorldController.RegisteredProjectPreview) =
        mount(Session(ProjectPreviewDocument(original.bundle), original = original))

    private fun mount(selected: Session) {
        if (!selected.taskless()) release()
        val original = selected.original
        val web = ProjectBundleWebView(controller.webPresentationActivity(), selected.document, object : ProjectBundleWebView.Events {
            override fun error(message: String) {
                if (session === selected) { selected.error = message.take(1600); changed() }
                if (original != null && !original.fromLocalWebWork && original.packageOperationId == null) controller.recordProjectPreviewConsole(original.bundle, original.taskId, "RENDER_ERROR", message, original.bundle.entryUrl, 0)
            }
            override fun console(level: String, text: String, source: String, line: Int) {
                if (original != null && !original.fromLocalWebWork && original.packageOperationId == null) controller.recordProjectPreviewConsole(original.bundle, original.taskId, level, text, source, line)
            }
        }, object : ProjectBundleWebView.SelectionEvents {
            override fun changed() { if (session === selected) this@PocketWebProjectPreview.changed() }
            override fun selected(selection: ProjectPreviewDocument.Selection) { receiveSelection(selected, selection) }
        })
        selected.web = web; web.visibility = View.GONE
        val initial = FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START)
        if (selected.taskless()) {
            // Attach invisibly before disposing the old exact inactive window. A failed attach leaves it intact.
            try {
                container.addView(web, initial)
                release()
            } catch (failure: Exception) {
                container.removeView(web)
                web.release()
                throw failure
            }
            session = selected
            if (keptPagesEnabled && selected.savedStatic != null) watchFirstSavedStaticLayout(selected)
        } else {
            session = selected
            container.addView(web, initial)
        }
        if (original?.fromLocalWebWork == true || selected.taskless()) {
            // Neither Work nor the original editor keeps an unseen renderer after a lost ACK.
            val previewId = selected.id
            container.postDelayed({
                session?.takeIf { it.id == previewId && it.viewport == null }?.let { unseen ->
                    if (unseen.taskless()) release() else releaseExactLocalWork(unseen)
                    changed()
                }
            }, 5_000L)
        }
    }

    private fun watchFirstSavedStaticLayout(selected: Session) {
        val web = selected.web
        val firstVisible = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                if (session !== selected || view !== web || !selected.visible || selected.viewport == null ||
                    !web.isAttachedToWindow || !web.isShown || right <= left || bottom <= top) return
                val visible = Rect()
                if (!web.getGlobalVisibleRect(visible) || visible.isEmpty) return
                web.removeOnLayoutChangeListener(this)
                selected.savedStaticLayout = null
                // The viewport ACK can precede the child's first layout. Republish only after real bounds exist.
                changed()
            }
        }
        selected.savedStaticLayout = firstVisible
        web.addOnLayoutChangeListener(firstVisible)
    }

    private fun receiveSelection(selected: Session, selection: ProjectPreviewDocument.Selection) {
        val original = selected.original ?: return
        val target = selected.target; val window = selected.window; val epoch = selected.selectionEpoch
        fun current() = !closed && foreground && session === selected && selected.selectionEpoch == epoch &&
            selected.visible && window != null && acceptsWindow(window) && controller.webPrivateOwnerVisible()
        if (target == null || !current()) { selected.web.setSelectionEnabled(false); return }
        selected.handedOutSelection = true
        // End only the mode. The captured native Selection remains live until document/source changes.
        selected.web.setSelectionEnabled(false); selected.target = null; selected.window = null
        try {
            controller.stageProjectPreviewSelection(original.taskId, target, selection, ::current, original.packageOperationId) { receipt, error ->
                if (session === selected && selected.selectionEpoch == epoch) {
                    selected.error = error
                    if (receipt?.opt("selectionAvailable") == false) { selected.selectionAvailable = false; selected.selectionUnavailableReason = error ?: "原选择来源暂不可核对" }
                    changed()
                }
            }
        } catch (failure: Exception) { selected.error = failure.message ?: "原元素选择未加入草稿"; changed() }
    }

    private fun refreshSelectionAvailability(selected: Session) {
        val original = selected.original ?: return
        if (original.fromLocalWebWork) return
        selected.selectionAvailable = false; selected.selectionUnavailableReason = "正在重新核对原预览来源"
        val window = captureWindow(); val epoch = selected.selectionEpoch
        fun current() = !closed && foreground && session === selected && selected.selectionEpoch == epoch && acceptsWindow(window)
        try { controller.checkProjectPreviewSelection(original, ::current) { available, reason ->
            if (current()) { selected.selectionAvailable = available; selected.selectionUnavailableReason = reason; changed() }
        } } catch (failure: Exception) { selected.selectionUnavailableReason = failure.message ?: "原预览来源暂不可核对" }
    }
    private fun disableSelection(selected: Session) {
        selected.selectionEpoch++; selected.target = null; selected.window = null; selected.web.setSelectionEnabled(false)
    }
    private fun hide(selected: Session) { disableSelection(selected); selected.visible = false; selected.web.visibility = View.GONE }
    private fun layout(selected: Session) {
        val viewport = selected.viewport
        if (closed || !foreground || session !== selected || !selected.visible || viewport == null || !controller.webPrivateOwnerVisible() ||
            selected.savedStatic?.let { !savedStaticCurrent(it) } == true ||
            selected.kept?.let { !keptCurrent(it.visit) } == true) { selected.web.visibility = View.GONE; return }
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
        if (!value) {
            cancelPendingStaticPreview()
            if (session?.taskless() == true) release()
        }
        session?.let { if (value) it.web.onResume() else { hide(it); it.web.onPause() } }
        changed()
    }
    private fun cancelPendingBuildPreview(reason: String = "原工作已离开，预览已取消") {
        val pending = pendingBuildPreview ?: return
        pendingBuildPreview = null
        container.removeCallbacks(pending.timeout)
        pending.control.cancel()
        pending.reject(reason)
    }
    private fun cancelPendingStaticPreview(reason: String = "已离开原已保存文件，预览已取消") {
        val pending = pendingStaticPreview ?: return
        pendingStaticPreview = null
        openingStatic++
        container.removeCallbacks(pending.timeout)
        pending.reject(reason)
    }
    fun retireLocalWork() {
        cancelPendingBuildPreview()
        if (session?.original?.fromLocalWebWork != true) return
        opening++
        release()
        controller.closeProjectPreview()
    }
    fun invalidatePresentation(documentReplaced: Boolean) {
        cancelPendingBuildPreview()
        cancelPendingStaticPreview()
        opening++
        if (documentReplaced) { controller.projectPackages.invalidateTaskExportSelection(); release(); controller.closeProjectPreview() }
        else if (session?.taskless() == true) release()
        else if (session?.original?.fromLocalWebWork == true) retireLocalWork()
        else session?.let(::hide)
        changed()
    }
    /** Retire an exact renderer, not a newer preview request still verifying this Work. */
    private fun releaseExactLocalWork(selected: Session) {
        val original = checkNotNull(selected.original)
        check(session === selected && original.fromLocalWebWork)
        release()
        controller.releaseLocalWorkPreviewCache(original)
    }
    private fun release() {
        val selected = session ?: return; session = null
        selected.savedStaticLayout?.let(selected.web::removeOnLayoutChangeListener)
        selected.savedStaticLayout = null
        selected.selectionEpoch++; selected.web.release(); container.removeView(selected.web)
    }
    fun close() {
        if (closed) return
        closed = true; opening++; cancelPendingBuildPreview(); cancelPendingStaticPreview(); controller.projectPackages.invalidateTaskExportSelection(); release(); container.removeOnLayoutChangeListener(layoutChanged); controller.closeProjectPreview()
    }
}
