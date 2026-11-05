package dev.pocketengine.browser

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pocketengine.BrowserActivity
import dev.pocketengine.ui.UiAppearance
import dev.pocketengine.ui.web.BrowserSourceReaderTarget
import dev.pocketengine.ui.web.PocketWebHost
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** One bundled React workspace beside the original website WebViews; websites receive no owner bridge. */
object BrowserTaskOwnerPanel {
    @JvmStatic fun install(activity: BrowserActivity, container: ViewGroup, host: BrowserTaskHost): BrowserActivity.OwnerPanel {
        val controller = OwnerController(host, activity, activity.intent.getBooleanExtra("showResearch", false))
        val main = Handler(Looper.getMainLooper())
        val frame = FrameLayout(activity).apply { tag = "browser_workspace"; isSaveEnabled = false; isSaveFromParentEnabled = false }
        container.addView(frame, LinearLayout.LayoutParams(-1, 0))
        var renderer: PocketWebHost? = null
        var endpoint: BrowserWorkspaceEndpoint? = null
        var active = false
        var closed = false
        var lastRefresh = 0L
        lateinit var mount: () -> Unit

        fun update() {
            if (closed) return
            val expanded = controller.expanded
            frame.visibility = if (expanded) View.VISIBLE else View.GONE
            val available = container.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val chrome = (0 until container.childCount).map(container::getChildAt).firstOrNull { it.tag == "browser_chrome" }?.height
                ?: (120 * activity.resources.displayMetrics.density).toInt()
            val height = if (expanded) (available - chrome).coerceAtLeast(1) else 0
            if (frame.layoutParams.height != height) frame.layoutParams = frame.layoutParams.apply { this.height = height }
            val foreground = expanded && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.hasWindowFocus()
            if (foreground != active) {
                active = foreground
                if (foreground) renderer?.resume() else renderer?.pause()
            }
            endpoint?.refresh()
        }
        fun unavailable() {
            if (closed) return
            renderer?.close(); renderer = null; endpoint = null; active = false
            controller.expanded = true
            controller.error = "浏览工作区暂时无法显示，请重新打开。原网页和已提交任务仍保留。"
            frame.removeAllViews()
            val recovery = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val spacing = (20 * resources.displayMetrics.density).toInt()
                setPadding(spacing, spacing, spacing, spacing)
                setBackgroundColor(UiAppearance.surface(activity))
                addView(android.widget.TextView(activity).apply {
                    text = controller.error
                    setTextColor(UiAppearance.foreground(activity))
                })
                addView(android.widget.Button(activity).apply {
                    text = "重新打开浏览工作区"
                    setTextColor(UiAppearance.foreground(activity))
                    backgroundTintList = android.content.res.ColorStateList.valueOf(UiAppearance.surfaceVariant(activity))
                    setOnClickListener { mount() }
                })
            }
            frame.addView(recovery, FrameLayout.LayoutParams(-1, -1))
            update()
        }
        mount = {
            if (!closed) {
                renderer?.close(); endpoint = null; active = false
                controller.error = null
                frame.removeAllViews()
                try {
                    val created = PocketWebHost.browserWorkspace(activity, { emit ->
                        BrowserWorkspaceEndpoint(activity, controller, emit).also { endpoint = it }
                    }, ::unavailable)
                    renderer = created
                    frame.addView(created.view, FrameLayout.LayoutParams(-1, -1))
                    created.start()
                    update()
                } catch (_: RuntimeException) { unavailable() }
            }
        }
        val poll = object : Runnable {
            override fun run() {
                if (closed || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastRefresh >= 1000L) { lastRefresh = now; controller.refresh() }
                update()
                main.postDelayed(this, 250L)
            }
        }
        val lifecycle = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> { main.removeCallbacks(poll); controller.refresh(); update(); main.post(poll) }
                Lifecycle.Event.ON_PAUSE -> {
                    renderer?.pause(); active = false
                    controller.cancelFileOpen(); controller.flushGoal()
                }
                Lifecycle.Event.ON_STOP -> main.removeCallbacks(poll)
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(lifecycle)
        mount()
        controller.refresh()
        return object : BrowserActivity.OwnerPanel {
            override fun show() { controller.expanded = true; controller.workspaceView = "research"; controller.refresh(); update() }
            override fun showSources() { controller.expanded = true; controller.workspaceView = "sources"; controller.refresh(); update() }
            override fun showPages() { controller.expanded = true; controller.workspaceView = "pages"; controller.refresh(); update() }
            override fun externalChrome(external: Boolean) { controller.externalChrome = external }
            override fun chromeUnavailable(notice: String) { controller.externalChrome = false; controller.error = notice; show() }
            override fun chromeStatus(): String = controller.chromeStatus()
            override fun chromeNeedsAttention(): Boolean = controller.chromeNeedsAttention()
            override fun backToPage(): Boolean {
                if (!controller.expanded || renderer == null) return controller.backToPage().also { update() }
                renderer?.handleBack {
                    if (!closed) { if (endpoint?.dismissReviewForBack() != true) controller.backToPage(); update() }
                }
                return true
            }
            override fun obscuresPage(): Boolean = controller.obscuresPage()
            override fun close() {
                if (!closed) {
                    closed = true; main.removeCallbacksAndMessages(null); activity.lifecycle.removeObserver(lifecycle)
                    renderer?.close(); renderer = null; endpoint = null; controller.close(); frame.removeAllViews()
                }
            }
        }
    }
}

internal class OwnerController(val host: BrowserTaskHost, private val activity: BrowserActivity, initialExpanded: Boolean) : AutoCloseable {
    private var ownerSnapshot by mutableStateOf<BrowserTaskHost.OwnerSnapshot?>(null)
    val snapshot get() = ownerSnapshot?.state ?: JSONObject()
    fun displayedSnapshot(): BrowserTaskHost.OwnerSnapshot? = ownerSnapshot
    val stopTicket get() = ownerSnapshot?.stopTicket
    var snapshotError by mutableStateOf(false)
    var expanded by mutableStateOf(initialExpanded)
    var externalChrome by mutableStateOf(false)
    var pagesRequested by mutableStateOf(0)
    var pagesHandled = 0
    var workspaceView by mutableStateOf(if (initialExpanded) "research" else "page")
    private var sourceReader: BrowserSourceReaderWindow? = null
    fun obscuresPage(): Boolean = expanded || sourceReader != null
    // Read-only collapsed presentation. It cannot grant, resume or redirect a task.
    private fun pendingChromeApproval(): Boolean = snapshot.child("activeResearchTask").array("approvals").rows()
        .any { it.optString("state") == "pending" && it.optLong("expiresAt") > System.currentTimeMillis() }
    fun chromeNeedsAttention(): Boolean = snapshotError || error != null || snapshot.child("researchControl").let {
        it.optBoolean("unresolved") || it.optBoolean("cancelRequested")
    } || snapshot.child("pageDialog").optString("status") == "pending" || pendingChromeApproval() || snapshot.has("pendingBrowserTask")
    fun chromeLabel(): String {
        val state = snapshot; val active = state.child("activeResearchTask"); val control = state.child("researchControl")
        return when {
            snapshotError -> "上次已知状态 · 刷新暂不可用"
            control.optBoolean("unresolved") -> "结果尚未核实 · 请回原任务核对"
            control.optBoolean("cancelRequested") -> "正在停止 · 等待原任务确认"
            state.child("pageDialog").optString("status") == "pending" -> "网页正在等你处理提示"
            pendingChromeApproval() -> "需要确认网页操作"
            error != null -> error.orEmpty()
            state.child("pages").child("taskSpace").optString("status") == "owner_control" -> "由你操作 · 自动研究暂停"
            state.has("pendingBrowserTask") -> "等待原任务接入网页"
            active.optString("id").isNotEmpty() -> statusLabel(active.optString("status"))
            else -> "网页与研究分开；不会自动开始"
        }
    }
    fun chromeStatus(): String {
        val state = snapshot
        val profile = state.child("profiles").array("profiles").rows().firstOrNull { it.optString("id") == state.optString("profileId") }
        return profile?.optString("label")?.takeIf { it.isNotBlank() }?.let { "$it · ${chromeLabel()}" } ?: chromeLabel()
    }
    fun openSavedSource(taskId: String, sourceId: String) {
        val target = runCatching { BrowserSourceReaderTarget.source(taskId, sourceId) }.getOrElse { error = "原来源身份不可核对，请返回原结果。"; return }
        openReader(target)
    }
    fun openSavedField(taskId: String, fieldRef: String) {
        val target = runCatching { BrowserSourceReaderTarget.field(taskId, fieldRef) }.getOrElse { error = "原字段身份不可核对，请返回原结果。"; return }
        openReader(target)
    }
    private fun openReader(target: BrowserSourceReaderTarget) {
        if (closed || sourceReader != null) return
        error = null
        lateinit var reader: BrowserSourceReaderWindow
        reader = BrowserSourceReaderWindow(activity, host, target, onFailure = { error = it }, onDismissed = {
            // Keep obstruction through native dismissal, not merely through a JS close request.
            if (sourceReader === reader) sourceReader = null
        })
        sourceReader = reader
        reader.open()
    }
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var goal by mutableStateOf("")
    var draftReady by mutableStateOf(false)
    var draftSaving by mutableStateOf(false)
    var draftSavedAt by mutableStateOf(0L)
    var draftError by mutableStateOf<String?>(null)
    var draftConflict by mutableStateOf<JSONObject?>(null)
    var contextTaskId by mutableStateOf("")
    var consent by mutableStateOf(false)
    var scopeMinutes by mutableStateOf(0)
    fun executionChoice(): JSONObject { val tools = snapshot.child("model").optInt("maxSteps", 40).coerceIn(1, 400); return JSONObject().put("deadlineAt", if (scopeMinutes == 0) 0L else System.currentTimeMillis() + scopeMinutes * 60_000L).put("toolLimit", tools).put("modelCallLimit", dev.pocketengine.store.TaskExecutionScope.defaultModelBudget(tools)) }
    var autoActions by mutableStateOf(false)
    var actionOrigins by mutableStateOf("")
    var actionBudget by mutableStateOf("60")
    var ordinaryInput by mutableStateOf(false)
    var interactionClicks by mutableStateOf(false)
    var interactionSelections by mutableStateOf(false)
    var interactionChecks by mutableStateOf(false)
    var interactionTargets by mutableStateOf("")
    var selectedInteractionCandidates by mutableStateOf<List<JSONObject>>(emptyList())
    var advancedInteractionTargets by mutableStateOf(false)
    var autoRead by mutableStateOf(false)
    var saveReadSources by mutableStateOf(false)
    var publicSeeds by mutableStateOf("")
    var extraFrameOrigins by mutableStateOf("")
    private var fileRead: dev.pocketengine.core.RunControl? = null
    private val pendingViewCleanup = ConcurrentHashMap.newKeySet<Intent>()
    private var refreshing = false
    @Volatile private var closed = false
    private val main = Handler(Looper.getMainLooper())
    private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private val draft = AtomicReference<JSONObject?>(null)
    private val editRevision = AtomicLong()
    private var draftContext = ""
    private var draftLoadRevision = 0L
    private var handledContext: String? = null
    private var entryTask = JSONObject()
    private var saveLater: Runnable? = null
    private fun draftKey(value: JSONObject) = value.optString("profileId") + "|" + value.optString("taskId")
    private fun loadGoal(state: JSONObject, task: String) {
        val profile = state.optString("profileId"); if (profile.isEmpty()) { draftReady = false; return }
        val key = "$profile|$task"; if (key == draftContext) return
        flushGoal(force = true); draftContext = key; val load = ++draftLoadRevision
        draftReady = false; draftError = null; draftConflict = null; goal = ""; contextTaskId = task; resetReadConsent(); consent = false
        try { io.execute { val loaded = runCatching { host.researchDraftForOwner(profile, task) }; main.post { if (!closed && draftLoadRevision == load && draftContext == key) loaded.onSuccess { draft.set(it); goal = it.optString("text"); draftSavedAt = it.optLong("updatedAt"); draftReady = true; draftSaving = false }.onFailure { draftError = it.message ?: "原研究草稿暂不可读，未自动恢复或执行。" } } } }
        catch (_: RuntimeException) { draftError = "原草稿读取尚未开始，请重开研究视图。" }
    }
    fun adopt(page: JSONObject) {
        val original = snapshot.optJSONObject("pages")?.optJSONObject("taskSpace") ?: return
        val expected = JSONObject().put("space", JSONObject(original.toString())).put("page", JSONObject(page.toString()))
        command({ host.adoptPage(expected) })
    }
    fun showPagePrompt() { flushGoal(); expanded = false; workspaceView = "page"; activity.showPendingPageDialogForOwner() }
    fun editGoal(value: String) {
        if (!draftReady || closed || value.length > 4000) return
        goal = value; editRevision.incrementAndGet(); draftSaving = true; draftError = null
        saveLater?.let(main::removeCallbacks)
        val save = Runnable { flushGoal() }; saveLater = save; main.postDelayed(save, 400)
    }
    private fun persistGoal(metadata: JSONObject, text: String, edit: Long, force: Boolean): JSONObject {
        if (editRevision.get() != edit && (!force || draft.get()?.let(::draftKey) == draftKey(metadata))) return metadata
        val live = draft.get()?.takeIf { draftKey(it) == draftKey(metadata) } ?: metadata
        val saved = host.saveResearchDraftForOwner(live.getString("profileId"), live.getString("taskId"), live.getLong("revision"), text)
        if (draft.get()?.let(::draftKey) == draftKey(metadata)) draft.set(saved)
        return saved
    }
    fun flushGoal(force: Boolean = false, clearFence: Boolean = false) {
        saveLater?.let(main::removeCallbacks); saveLater = null
        val metadata = draft.get() ?: return; if (!draftReady || draftKey(metadata) != draftContext) return
        val text = goal; val edit = editRevision.get(); val key = draftContext
        if (!clearFence && metadata.optString("text") == text) { draftSaving = false; return }
        draftSaving = true
        try { io.execute { val saved = runCatching { persistGoal(metadata, text, edit, force) }; main.post { if (!closed && draftContext == key && editRevision.get() == edit) saved.onSuccess { draftSaving = false; draftSavedAt = it.optLong("updatedAt"); draftError = null }.onFailure { draftSaving = false; draftError = it.message ?: "研究草稿尚未保存，当前文字仍保留。" } } } }
        catch (_: RuntimeException) { draftSaving = false; draftError = "草稿写入未开始；当前文字仍在，请稍后保存。" }
    }
    fun clearGoal() { if (!draftReady || closed) return; goal = ""; editRevision.incrementAndGet(); draftSaving = true; flushGoal(force = true, clearFence = true) }
    fun consumedGoal() { goal = ""; editRevision.incrementAndGet(); draftSaving = true; flushGoal(force = true, clearFence = true) }
    fun retryDraft() { draftContext = ""; refresh() }
    fun reviewDraftConflict() {
        val previous = draft.get() ?: return; if (closed || busy) return
        val key = draftContext; val edit = editRevision.get(); busy = true
        try { io.execute { val actual = runCatching { host.researchDraftForOwner(previous.getString("profileId"), previous.getString("taskId")) }; main.post { if (!closed) { busy = false; if (draftContext == key && editRevision.get() == edit) actual.onSuccess { draftConflict = it }.onFailure { draftError = it.message ?: "原草稿当前不可读，未覆盖原件。" } } } } }
        catch (_: RuntimeException) { busy = false; draftError = "原草稿核对尚未开始，当前输入仍在。" }
    }
    fun resolveDraftConflict(actual: JSONObject, keepInput: Boolean) {
        if (closed || draftKey(actual) != draftContext) return
        editRevision.incrementAndGet(); draft.set(actual); draftConflict = null; draftError = null
        if (keepInput) flushGoal(force = true, clearFence = true)
        else { goal = actual.optString("text"); draftSavedAt = actual.optLong("updatedAt"); draftSaving = false }
    }
    fun refresh() {
        if (closed || refreshing || busy) return
        val requested = runCatching { activity.researchTaskContextForOwner() }.getOrElse { error = "入口原任务不可核对，未切换任务或执行。"; draftReady = false; return }
        refreshing = true
        try { io.execute { if (closed) return@execute; val result = runCatching { if (handledContext != requested) { entryTask = if (requested.isNotEmpty()) { val exact = host.selectHistoryForOwner(requested).getJSONObject("task"); JSONObject().put("id", exact.getString("id")).put("goal", exact.optString("goal")) } else JSONObject(); handledContext = requested }; host.snapshotWithStopForOwner().also { it.state.put("entryTask", JSONObject(entryTask.toString())) } }; main.post { refreshing = false; if (!closed) result.onSuccess { ownerSnapshot = it; snapshotError = false; loadGoal(it.state, requested) }.onFailure { snapshotError = true; error = it.message ?: "工作区状态暂不可读，请刷新" } } } }
        catch (_: RuntimeException) { refreshing = false; error = "工作区正在处理请求，请稍后刷新" }
    }
    fun command(action: () -> Unit, done: () -> Unit = {}) = runOwnerCommand(action, done, false)
    fun commandWithDraft(action: () -> Unit, done: () -> Unit = {}) = runOwnerCommand(action, done, true)
    private fun runOwnerCommand(action: () -> Unit, done: () -> Unit, saveText: Boolean) {
        if (closed || busy) return
        val savedDraft = draft.get(); val exactGoal = goal; val edit = editRevision.get(); val saveNeeded = saveText && savedDraft != null && draftReady && (draftSaving || savedDraft.optString("text") != exactGoal)
        busy = true; error = null
        try { io.execute { if (closed) return@execute; val result = runCatching { if (saveNeeded && savedDraft != null) persistGoal(savedDraft, exactGoal, edit, true); action() }; main.post { if (!closed) { busy = false; result.onSuccess { done() }.onFailure { error = it.message ?: "本次操作未确认，请核对原任务" }; refresh() } } } }
        catch (_: RuntimeException) { busy = false; error = "请求未提交，请稍后重试" }
    }
    fun cancelFileOpen() { val previous = fileRead ?: return; fileRead = null; previous.cancel(); busy = false }
    private fun discardView(intent: Intent) {
        pendingViewCleanup.add(intent)
        val cleanup = Runnable { try { runCatching { host.discardResearchViewForOwner(intent) } } finally { pendingViewCleanup.remove(intent) } }
        try { io.execute(cleanup) } catch (_: RuntimeException) { cleanup.run() }
    }
    fun openDownload(taskId: String, download: JSONObject) {
        if (closed || busy || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val actual = JSONObject(download.toString()); val file = actual.optJSONObject("attachment") ?: return
        val read = dev.pocketengine.core.RunControl(); fileRead = read; busy = true; error = null
        try { io.execute {
            var prepared: Intent? = null
            try {
                prepared = host.openDownloadedAttachmentForOwner(taskId, actual.getString("id"), file.getString("id"), file.getString("sha256"), read)
                val intent = checkNotNull(prepared)
                if (!main.post {
                    val currentTask = snapshot.child("researchTask").optString("id")
                    if (closed || fileRead !== read || read.isCancelled || currentTask != taskId || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { discardView(intent); if (fileRead === read) cancelFileOpen() }
                    else {
                        fileRead = null; busy = false
                        intent.putExtra(dev.pocketengine.features.attachments.AttachmentOutcomeHost.NATIVE_VIEW_ISSUED, true)
                        try { activity.startActivity(intent) }
                        catch (_: android.content.ActivityNotFoundException) { intent.putExtra(dev.pocketengine.features.attachments.AttachmentOutcomeHost.NATIVE_VIEW_ISSUED, false); discardView(intent); error = "当前没有可打开此类型的应用；文件仍保留在原任务附件库。" }
                        catch (_: SecurityException) { intent.putExtra(dev.pocketengine.features.attachments.AttachmentOutcomeHost.NATIVE_VIEW_ISSUED, false); discardView(intent); error = "系统拒绝了这次只读文件打开；原文件保留，没有换成其他文件。" }
                        catch (_: RuntimeException) { error = "文件打开请求的结果尚未确认；未自动重发，也未清理可能已交付的查看副本。" }
                    }
                }) { host.discardResearchViewForOwner(intent) }
            } catch (failure: Exception) {
                prepared?.let { runCatching { host.discardResearchViewForOwner(it) } }
                main.post { if (!closed && fileRead === read) { fileRead = null; busy = false; if (!read.isCancelled) error = failure.message ?: "原附件版本暂不可核对，未打开替代文件。" } }
            }
        } } catch (_: RuntimeException) { fileRead = null; busy = false; read.cancel(); error = "原文件请求未开始，请稍后重试。" }
    }
    fun stop(ticket: BrowserTaskHost.OwnerStopTicket?) {
        if (closed) return
        runCatching { host.cancelForOwner(ticket) }.onSuccess { if (!busy) command({ host.endForOwner(ticket) }) else refresh() }
            .onFailure { error = it.message ?: "原浏览任务已变化，请刷新后核对。"; refresh() }
    }
    fun backToPage(): Boolean {
        sourceReader?.let { it.handleBack(); return true }
        if (closed || !expanded || workspaceView == "page") return false
        flushGoal(force = true); cancelFileOpen(); workspaceView = "page"; expanded = false
        return true
    }
    fun returnToTask(taskId: String) { if (closed) return; flushGoal(force = true); runCatching { activity.returnToTaskForOwner(taskId) }.onFailure { error = it.message ?: "原任务入口暂不可用，网页仍保留。" } }
    fun reviewReadScope(done: (BrowserReadScope.Consent) -> Unit) {
        var prepared: BrowserReadScope.Consent? = null
        command({ prepared = BrowserReadScope.forOwner(autoRead, saveReadSources, publicSeeds) }, { done(checkNotNull(prepared)) })
    }
    fun reviewReadScopeAndFrames(done: (BrowserReadScope.Consent, BrowserFrameOrigins.Consent, BrowserActionScope.Consent) -> Unit) {
        val auto = autoRead; val keep = saveReadSources; val seeds = publicSeeds; val frames = extraFrameOrigins; val routine = autoActions; val origins = actionOrigins; val budget = actionBudget; val inputs = ordinaryInput; val clicks = interactionClicks; val selections = interactionSelections; val checks = interactionChecks; val targets = interactionTargets; val candidates = JSONArray().also { all -> selectedInteractionCandidates.forEach { all.put(JSONObject(it.toString())) } }
        var read: BrowserReadScope.Consent? = null; var embedded: BrowserFrameOrigins.Consent? = null; var actions: BrowserActionScope.Consent? = null
        command({ read = BrowserReadScope.forOwner(auto, keep, seeds); embedded = BrowserFrameOrigins.forOwner(frames); actions = BrowserActionScope.forOwner(routine, origins, budget.toIntOrNull() ?: 0, inputs, clicks, selections, checks, targets, candidates) }, { done(checkNotNull(read), checkNotNull(embedded), checkNotNull(actions)) })
    }
    fun resetReadConsent() { autoActions = false; actionOrigins = ""; ordinaryInput = false; interactionClicks = false; interactionSelections = false; interactionChecks = false; interactionTargets = ""; selectedInteractionCandidates = emptyList(); advancedInteractionTargets = false; autoRead = false; saveReadSources = false; publicSeeds = ""; extraFrameOrigins = "" }
    override fun close() {
        flushGoal(force = true); closed = true; sourceReader?.close(); cancelFileOpen(); io.shutdown()
        // Drain already prepared unissued tokens; the remaining bounded queue only saves text/cleans up or skips closed actions.
        pendingViewCleanup.toList().forEach { intent -> runCatching { host.discardResearchViewForOwner(intent) }; pendingViewCleanup.remove(intent) }
        // Keep queued main delivery callbacks: closed guard discards late prepared views and never launches.
    }
}
internal fun JSONArray.rows(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.child(name: String) = optJSONObject(name) ?: JSONObject()
internal fun JSONObject.array(name: String) = optJSONArray(name) ?: JSONArray()
internal fun toolLabel(tool: String) = when (tool.removePrefix("browser_task_")) {
    "observe_visual" -> "查看网页图像"; "open" -> "打开研究页"; "select" -> "切换研究页"; "observe" -> "读取网页"; "navigate" -> "打开页面链接"
    "evaluate" -> "只读计算当前网页"; "inspect" -> "检查正文与页面状态"; "script" -> "运行这段页面脚本"; "network" -> "页面网络证据"; "network_body" -> "披露这一个响应正文"; "upload_artifact" -> "上传这一个文件版本"
    "native_input" -> "确认原网页的真实输入"; "site_mode" -> "切换网页站点模式"; "resize" -> "调整网页实际显示尺寸"
    "back" -> "后退到原历史页"; "forward" -> "前进到原历史页"; "reload" -> "刷新当前网页"
    "select_option" -> "选择下拉选项"
    "set_checked" -> "设置勾选状态"
    "click" -> "点击网页控件"; "type" -> "填写网页内容"; "scroll" -> "滚动网页"; "wait" -> "等待网页条件"
    "request_user" -> "请你处理当前网页"
    "download" -> "下载网页文件"; "download_status" -> "查看下载结果"; "retain_source" -> "保留网页来源"; "read_public" -> "匿名读取公共网页"
    "close" -> "关闭任务页"; "finish" -> "结束浏览空间"; else -> "本次网页操作"
}
internal fun sourceScopeNote(source: JSONObject): String {
    if (source.optString("kind") == "browser_live_observation") return "本次真实可见正文片段，附原文档与局部稳定证据；不含子frame整体，不证明全页完整、网络空闲或服务器业务成功。"
    if (source.optString("kind") == "browser_semantic_observation") {
        val reading = source.optJSONObject("semantic") ?: JSONObject()
        val mode = when (reading.optString("mode")) { "table" -> "表格"; "list" -> "列表"; "outline" -> "页面目录"; else -> "正文" }
        val coverage = if (reading.optBoolean("partial")) "仅保留实际读到的片段，仍有未读或不支持的内容。" else "已保留这次所选范围的文字投影，不代表全页。"
        val continuation = if (reading.optJSONArray("reasons")?.toString()?.contains("continuation_available") == true) "采集时尚有可续读内容。" else ""
        val frame = source.child("frameCoverage")
        val place = if (frame.optBoolean("childFrameContentsRead")) "选中嵌入页 ${frame.optString("selectedSourceOrigin")}" else "顶层网页"
        val inline = if (frame.optString("sourceUrlRole") == "inline_document_host_page") "内联内容的原链接指向宿主页。" else ""
        return "$place · 当时的$mode。$coverage$continuation$inline 未读取其他嵌入页；封闭组件和像素不在本次范围。"
    }
    if (source.optString("kind") != "public_http_observation") return "原链接按原登录资料私存；网页内容未经核实。"
    val http = source.optJSONObject("http") ?: JSONObject()
    val coverage = if (http.optBoolean("bodyTruncated") || http.optBoolean("projectionTruncated")) "仅保留预算内的正文片段，未读完整。" else "仅为本次服务器响应的文字投影，不含渲染后的内容。"
    return "匿名公共 HTTP · 未使用登录资料。$coverage 内容未经核实；回到原出处会在原 WebView 打开，届时可能使用你明确选中的登录资料。"
}
internal fun statusLabel(status: String) = when (status) {
    "preparing", "running" -> "正在研究"; "awaiting_user", "needs_confirmation", "paused" -> "等待主人处理"
    "completed" -> "研究已完成"; "cancelled" -> "自动研究已停止"; "reconciling" -> "操作结果待核对"
    "failed", "interrupted" -> "研究未完成"; else -> "研究任务"
}
