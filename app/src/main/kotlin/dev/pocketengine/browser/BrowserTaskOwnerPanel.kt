package dev.pocketengine.browser

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import dev.pocketengine.BrowserActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject

/** Owner controls beside the existing WebView. Page content never gets this native interface. */
object BrowserTaskOwnerPanel {
    @JvmStatic fun install(activity: BrowserActivity, container: ViewGroup, host: BrowserTaskHost): BrowserActivity.OwnerPanel {
        val controller = OwnerController(host, activity, activity.intent.getBooleanExtra("showResearch", false))
        val view = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    LaunchedEffect(controller) { activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) { controller.refresh(); delay(1000) } } }
                    DisposableEffect(controller, activity.lifecycle) {
                        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) { controller.cancelFileOpen(); controller.flushGoal() } }
                        activity.lifecycle.addObserver(observer)
                        onDispose { activity.lifecycle.removeObserver(observer); controller.cancelFileOpen() }
                    }
                    ResearchPanel(controller)
                }
            }
        }
        container.addView(view, LinearLayout.LayoutParams(-1, -2))
        return object : BrowserActivity.OwnerPanel {
            override fun show() { controller.expanded = true; controller.workspaceView = "research"; controller.refresh() }
            override fun backToPage(): Boolean = controller.backToPage()
            override fun obscuresPage(): Boolean = controller.expanded
            override fun close() { controller.close(); view.disposeComposition() }
        }
    }
}

private class OwnerController(val host: BrowserTaskHost, private val activity: BrowserActivity, initialExpanded: Boolean) : AutoCloseable {
    var snapshot by mutableStateOf(JSONObject())
    var snapshotError by mutableStateOf(false)
    var expanded by mutableStateOf(initialExpanded)
    var workspaceView by mutableStateOf(if (initialExpanded) "research" else "page")
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
        try { io.execute { if (closed) return@execute; val result = runCatching { if (handledContext != requested) { entryTask = if (requested.isNotEmpty()) { val exact = host.selectHistoryForOwner(requested).getJSONObject("task"); JSONObject().put("id", exact.getString("id")).put("goal", exact.optString("goal")) } else JSONObject(); handledContext = requested }; host.snapshotForOwner().put("entryTask", JSONObject(entryTask.toString())) }; main.post { refreshing = false; if (!closed) result.onSuccess { snapshot = it; snapshotError = false; loadGoal(it, requested) }.onFailure { snapshotError = true; error = it.message ?: "工作区状态暂不可读，请刷新" } } } }
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
    fun stop() { host.cancelForOwner(); if (!busy) command({ host.endForOwner() }) else refresh() }
    fun backToPage(): Boolean {
        if (closed || !expanded || workspaceView == "page") return false
        flushGoal(force = true); cancelFileOpen(); workspaceView = "page"; expanded = false
        return true
    }
    fun returnToTask(taskId: String) { if (closed) return; flushGoal(force = true); runCatching { activity.returnToTaskForOwner(taskId) }.onFailure { error = it.message ?: "原任务入口暂不可用，网页仍保留。" } }
    fun reviewReadScope(done: (BrowserReadScope.Consent) -> Unit) {
        var prepared: BrowserReadScope.Consent? = null
        command({ prepared = BrowserReadScope.forOwner(autoRead, saveReadSources, publicSeeds) }, { done(checkNotNull(prepared)) })
    }
    fun reviewReadScopeAndFrames(done: (BrowserReadScope.Consent, BrowserFrameOrigins.Consent) -> Unit) {
        val auto = autoRead; val keep = saveReadSources; val seeds = publicSeeds; val frames = extraFrameOrigins
        var read: BrowserReadScope.Consent? = null; var embedded: BrowserFrameOrigins.Consent? = null
        command({ read = BrowserReadScope.forOwner(auto, keep, seeds); embedded = BrowserFrameOrigins.forOwner(frames) }, { done(checkNotNull(read), checkNotNull(embedded)) })
    }
    fun resetReadConsent() { autoRead = false; saveReadSources = false; publicSeeds = ""; extraFrameOrigins = "" }
    override fun close() {
        flushGoal(force = true); closed = true; cancelFileOpen(); io.shutdown()
        // Drain already prepared unissued tokens; the remaining bounded queue only saves text/cleans up or skips closed actions.
        pendingViewCleanup.toList().forEach { intent -> runCatching { host.discardResearchViewForOwner(intent) }; pendingViewCleanup.remove(intent) }
        // Keep queued main delivery callbacks: closed guard discards late prepared views and never launches.
    }
}
private data class ReadScopeReview(val consent: BrowserReadScope.Consent, val target: String, val pin: JSONObject, val taskId: String, val goal: String, val profileLabel: String, val frameOrigins: BrowserFrameOrigins.Consent = BrowserFrameOrigins.sameOriginOnly())

private fun JSONArray.rows(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.child(name: String) = optJSONObject(name) ?: JSONObject()
private fun JSONObject.array(name: String) = optJSONArray(name) ?: JSONArray()
private fun toolLabel(tool: String) = when (tool.removePrefix("browser_task_")) {
    "observe_visual" -> "查看网页图像"; "open" -> "打开研究页"; "select" -> "切换研究页"; "observe" -> "读取网页"; "navigate" -> "打开页面链接"
    "select_option" -> "选择下拉选项"
    "set_checked" -> "设置勾选状态"
    "click" -> "点击网页控件"; "type" -> "填写网页内容"; "scroll" -> "滚动网页"; "wait" -> "等待网页条件"
    "request_user" -> "请你处理当前网页"
    "download" -> "下载网页文件"; "download_status" -> "查看下载结果"; "retain_source" -> "保留网页来源"; "read_public" -> "匿名读取公共网页"
    "close" -> "关闭任务页"; "finish" -> "结束浏览空间"; else -> "本次网页操作"
}
private fun sourceScopeNote(source: JSONObject): String {
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
private fun statusLabel(status: String) = when (status) {
    "preparing", "running" -> "正在研究"; "awaiting_user", "needs_confirmation", "paused" -> "等待主人处理"
    "completed" -> "研究已完成"; "cancelled" -> "自动研究已停止"; "reconciling" -> "操作结果待核对"
    "failed", "interrupted" -> "研究未完成"; else -> "研究任务"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ResearchPanel(c: OwnerController) {
    val focus = LocalFocusManager.current; val keyboard = LocalSoftwareKeyboardController.current
    val page: () -> Unit = { c.flushGoal(); focus.clearFocus(); keyboard?.hide(); c.workspaceView = "page"; c.expanded = false }
    val state = c.snapshot; val active = state.child("activeResearchTask"); val viewed = state.child("researchTask")
    val space = state.child("pages").child("taskSpace"); val control = state.child("researchControl"); val pageDialog = state.child("pageDialog"); val dialogPending = pageDialog.optString("status") == "pending"
    val profile = state.child("profiles").array("profiles").rows().firstOrNull { it.optString("id") == state.optString("profileId") }
    val pending = active.array("approvals").rows().any { it.optString("state") == "pending" && it.optLong("expiresAt") > System.currentTimeMillis() }
    val label = when { c.snapshotError -> "上次已知状态 · 刷新暂不可用"; control.optBoolean("cancelRequested") -> "正在停止 · 等待原任务确认"; dialogPending -> "网页正在等你处理提示"; control.optBoolean("unresolved") -> "结果尚未核实 · 请回原任务核对"; space.optString("status") == "owner_control" -> "由你操作 · 自动研究暂停"; pending -> "需要确认网页操作"; state.has("pendingBrowserTask") -> "等待原任务接入网页"; active.optString("id").isNotEmpty() -> statusLabel(active.optString("status")); else -> "网页与研究分开；不会自动开始" }
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text((active.optString("goal").ifBlank { viewed.optString("goal").ifBlank { "网页研究" } }), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text((profile?.optString("label")?.let { "$it · " }.orEmpty()) + label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { if (dialogPending) c.showPagePrompt() else { c.expanded = true; c.workspaceView = "research" } }) { Text(if (dialogPending) "网页提示" else if (pending || control.optBoolean("unresolved")) "处理" else "研究") }
            TextButton(onClick = { c.expanded = true; c.workspaceView = "sources" }) { Text("来源") }
        }
    }
    if (c.expanded && c.workspaceView != "page") ModalBottomSheet(onDismissRequest = page, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ResearchWorkspace(c, c.workspaceView, page)
    }
}
private data class ResearchResultPin(val text: String, val taskId: String, val citations: List<JSONObject>)
@Composable private fun ResearchWorkspace(c: OwnerController, mode: String, page: () -> Unit) {
    val state = c.snapshot
    val profiles = state.child("profiles")
    val profileRows = profiles.array("profiles").rows()
    val profile = profileRows.firstOrNull { it.optString("id") == state.optString("profileId") }
    val pageState = state.child("pages")
    val pages = pageState.array("tabs").rows()
    val space = pageState.child("taskSpace")
    val pageDialog = state.child("pageDialog"); val dialogPending = pageDialog.optString("status") == "pending"
    val activeTask = state.child("activeResearchTask")
    val control = state.child("researchControl")
    val pendingTask = state.child("pendingBrowserTask")
    val viewedTask = state.child("researchTask")
    val sources = state.array("sources").rows()
    val pending = activeTask.array("approvals").rows().filter { it.optString("tool").startsWith("browser_task_") && it.optString("state") == "pending" && it.optLong("expiresAt") > System.currentTimeMillis() && it.optString("runId") == activeTask.optString("runId") }
    val running = space.optString("status") in setOf("active", "owner_control")
    val handoffPages = space.array("handoffPages").rows().filter { !it.optBoolean("unavailable") }
    val selectedHandoff = handoffPages.firstOrNull { pin -> pages.any { it.optBoolean("selected") && it.optString("tabId") == pin.optString("tabId") } }
    var identities by remember { mutableStateOf(false) }
    var switchTo by remember { mutableStateOf<JSONObject?>(null) }
    var deleteProfile by remember { mutableStateOf<JSONObject?>(null) }
    var newLabel by remember { mutableStateOf("") }
    var approval by remember { mutableStateOf<JSONObject?>(null) }
    var history by remember { mutableStateOf<List<JSONObject>?>(null) }
    var showRecoveryPages by remember { mutableStateOf(false) }
    var recoveryReview by remember { mutableStateOf<JSONObject?>(null) }
    var recoveryConsent by remember { mutableStateOf(false) }
    var sourceDetails by remember { mutableStateOf<JSONObject?>(null) }
    var recipeSource by remember { mutableStateOf<JSONObject?>(null) }
    var recipeQuery by remember { mutableStateOf("") }
    var recipePreview by remember { mutableStateOf<JSONObject?>(null) }
    var recipeEnabled by remember { mutableStateOf(false) }
    var fieldDetails by remember { mutableStateOf<JSONObject?>(null) }
    var resultDetails by remember { mutableStateOf<ResearchResultPin?>(null) }
    var existingTasks by remember { mutableStateOf(false) }
    var readScopeOptions by remember { mutableStateOf(false) }
    var readScopeReview by remember { mutableStateOf<ReadScopeReview?>(null) }
    var showPages by remember { mutableStateOf(false) }
    var showReadOptions by remember { mutableStateOf(false) }
    val researchScroll = rememberScrollState(); val sourceScroll = rememberScrollState()
    LaunchedEffect(viewedTask.optString("id")) { sourceScroll.scrollTo(0) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).imePadding()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = page) { Text("返回网页") }
            TextButton(onClick = { c.workspaceView = "research" }) { Text("研究", fontWeight = if (mode == "research") FontWeight.SemiBold else FontWeight.Normal) }
            TextButton(onClick = { c.workspaceView = "sources" }) { Text("来源", fontWeight = if (mode == "sources") FontWeight.SemiBold else FontWeight.Normal) }
            TextButton(onClick = { identities = true }) { Text("登录资料") }
        }
        if (pending.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("需要确认：${toolLabel(pending.first().optString("tool"))}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); TextButton(onClick = { approval = pending.first() }, enabled = !c.busy) { Text("核对这次操作") } }
        }
        if (mode == "sources" && control.optBoolean("unresolved")) TextButton(onClick = { c.workspaceView = "research" }) { Text("当前研究有未知结果 · 去核对，未自动重试") }
        if (c.expanded) Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(if (mode == "sources") sourceScroll else researchScroll).padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode == "research") {
            val originalTask = state.array("currentAssistantTasks").rows().firstOrNull { it.optString("taskId") == c.contextTaskId }
            if (c.contextTaskId.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("原任务：${state.child("entryTask").optString("goal")}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { c.returnToTask(c.contextTaskId) }) { Text("回原任务") }
            }
            if (control.optBoolean("unresolved")) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.padding(12.dp)) { Text("结果尚未核实", fontWeight = FontWeight.SemiBold); Text("请先核对原操作，不重复点击、提交或交还旧决定。", style = MaterialTheme.typography.bodySmall); TextButton(onClick = { c.returnToTask(activeTask.getString("id")) }) { Text("回原任务核对") } }
            }
            if (control.optBoolean("cancelRequested")) Text("已请求停止，正在等待原任务结束与页面资源释放；没有把按钮点击当作停止完成。", style = MaterialTheme.typography.bodySmall)
            if (running) {
                Text(if (dialogPending) "网页正在等你确认或取消" else if (space.optString("status") == "owner_control") "主人已接管 · 自动浏览暂停" else if (pending.isNotEmpty()) "等待确认这次网页操作" else statusLabel(activeTask.optString("status")), fontWeight = FontWeight.SemiBold)
                Text(activeTask.optString("goal"), style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                val siteNotice = state.child("siteNotice")
                if (siteNotice.optString("taskId") == activeTask.optString("id") && siteNotice.optString("reason") in setOf("site_permission_denied", "geolocation_denied", "site_request_cancelled")) {
                    Text("网页的设备权限请求已拒绝或取消，研究可继续处理其他内容。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = page) { Text("回网页查看来源与说明") }
                }
                if (dialogPending) TextButton(onClick = { c.showPagePrompt() }) { Text("查看网页提示") }
                else if (pageDialog.optString("status") == "resolved" && space.optString("status") == "owner_control") Text("网页提示已处理；请先核对当前页面，再决定是否继续研究。", style = MaterialTheme.typography.bodySmall)
                val readScope = state.child("readScope")
                val readState = readScope.optString("state")
                if (readScope.optString("scopeId").isNotBlank()) {
                    Text("只读范围 · ${when (readState) { "active" -> "已授权"; "exhausted" -> "次数已用尽"; "expired" -> "已过期"; "revoked" -> "已撤销"; else -> "待核对" }} · 剩余 ${readScope.optInt("remainingReads")} 次调用 / ${readScope.optLong("remainingPublicBytes")} bytes", style = MaterialTheme.typography.bodySmall)
                    Text("有效至 ${android.text.format.DateFormat.format("HH:mm:ss", readScope.optLong("expiresAt"))}；超出范围仍须逐次确认。", style = MaterialTheme.typography.bodySmall)
                    if (readState in setOf("active", "exhausted")) TextButton(onClick = { c.command({ c.host.revokeReadScopeForOwner(activeTask.getString("id"), readScope.getString("scopeId")) }) }, enabled = !c.busy) { Text("撤销只读范围") }
                } else Text("未授予自动只读范围；网页读取仍逐次确认。", style = MaterialTheme.typography.bodySmall)
                if (space.optString("status") == "active" && state.child("readScopeIdentity").optString("taskId") == activeTask.optString("id")) TextButton(onClick = { c.resetReadConsent(); readScopeOptions = true }, enabled = !c.busy) { Text("明确授予 / 更新本轮只读范围…") }
                if (space.optString("status") == "owner_control") {
                    Text(space.optString("handoffReason").ifBlank { "你可以在网页中完成登录或检查结果，再交还给原任务。" }, style = MaterialTheme.typography.bodyMedium)
                    val returnPin = selectedHandoff ?: space.optJSONObject("continuePin")?.takeIf { handoffPages.isEmpty() }
                    if (returnPin != null) Button(onClick = { val pinned = JSONObject(returnPin.toString()); c.command({ c.host.returnForOwner(pinned) }) }, enabled = !c.busy && !control.optBoolean("unresolved") && !control.optBoolean("cancelRequested") && !state.optBoolean("fileChooserPending") && !state.optBoolean("uploadInProgress") && !dialogPending) { Text(if (selectedHandoff != null) "交还当前页并继续" else "继续原浏览任务") }
                    else Text("在下方查看要交还的原页面，再继续同一任务。", style = MaterialTheme.typography.bodySmall)
                    Text("继续后会重新读取网页；登录密码与验证码由你在网页中输入。", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${pages.count { it.optString("openedBy") == "task" }} / 4 个任务页", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { c.stop() }, enabled = !control.optBoolean("cancelRequested")) { Text("结束当前研究") }
                }
            } else if (pendingTask.optString("id").isNotBlank()) {
                Text("等待原助手下一轮接入网页", fontWeight = FontWeight.SemiBold)
                Text(pendingTask.optString("goal"), maxLines = 3)
                Text("沿用原任务与执行控制；当前模型请求结束后才取得本资料的任务页。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { c.stop() }) { Text("停止此任务的浏览请求") }
            } else {
                if (state.child("model").optString("model").isNotBlank()) Text("模型：${state.child("model").optString("model")}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = c.goal, onValueChange = c::editGoal, label = { Text("想研究什么") }, placeholder = { Text("例如：比较三个方案，保留依据和可下载资料") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, enabled = !c.busy && c.draftReady)
                Text(when { !c.draftReady -> "正在读取本资料、原任务上下文的文字草稿；不会自动开始。"; c.draftSaving -> "正在保存本机文字草稿…"; c.draftSavedAt > 0 -> "文字草稿上次已保存；授权、同意与登录凭据不会随草稿恢复。"; else -> "仅保存此处文字，不恢复授权或自动开始任务。" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                c.draftError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error); TextButton(onClick = { if (c.draftReady) c.reviewDraftConflict() else c.retryDraft() }) { Text(if (c.draftReady) "核对已保存草稿" else "重新读取原草稿") } }
                if (c.draftReady && c.goal.isNotEmpty()) TextButton(onClick = c::clearGoal, enabled = !c.busy) { Text("清空此处文字草稿") }
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(checked = c.consent, onCheckedChange = { c.consent = it }, enabled = !c.busy)
                    Text("把本次网页观察交给当前模型；可以随时接管或停止。", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { showReadOptions = !showReadOptions }) { Text(if (c.autoRead) "已选择本轮只读范围 · 核对或修改" else "可选：设置本轮只读范围") }
                if (showReadOptions) ReadScopeChoices(c)
                if (originalTask != null) Button(onClick = { val pinned = JSONObject(state.toString()); val frameText = c.extraFrameOrigins; val id = originalTask.getString("taskId"); if (c.autoRead) c.reviewReadScopeAndFrames { read, frames -> readScopeReview = ReadScopeReview(read, "existing", pinned, id, originalTask.optString("goal"), profile?.optString("label").orEmpty(), frames) } else c.commandWithDraft({ c.host.allowExistingForOwner(id, pinned, c.consent, BrowserReadScope.disabled(), BrowserFrameOrigins.forOwner(frameText)) }, { c.consent = false; c.resetReadConsent() }) }, enabled = !c.busy && c.consent && c.draftReady) { Text("为这个原任务启用网页") }
                if (c.contextTaskId.isNotEmpty() && originalTask == null) Text("此处保留原任务回查；没有自动恢复它的执行或浏览授权。另提研究目标会创建新的明确任务。", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { val pinned = JSONObject(state.toString()); val frameText = c.extraFrameOrigins; val goal = c.goal; if (c.autoRead) c.reviewReadScopeAndFrames { read, frames -> readScopeReview = ReadScopeReview(read, "start", pinned, "", goal, profile?.optString("label").orEmpty(), frames) } else c.commandWithDraft({ c.host.startForOwner(goal, pinned, c.consent, BrowserReadScope.disabled(), BrowserFrameOrigins.forOwner(frameText)) }, { c.consumedGoal(); c.consent = false; c.resetReadConsent(); c.backToPage() }) }, enabled = !c.busy && c.consent && c.draftReady && c.goal.isNotBlank() && state.optBoolean("open")) { Text(if (c.contextTaskId.isEmpty()) "开始研究" else "另开一项研究") }
                    TextButton(onClick = { identities = true }, enabled = !c.busy) { Text("选择登录资料") }
                }
                if (state.array("currentAssistantTasks").length() > 0) TextButton(onClick = { existingTasks = true }, enabled = c.consent && !c.busy) { Text("为正在进行的助手任务启用网页") }
            }
            }
            c.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (c.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (mode == "research" && pages.isNotEmpty()) {
                TextButton(onClick = { showPages = !showPages }) { Text("页面与接管 · ${pages.size}页") }
                if (showPages || space.optString("status") == "owner_control" && selectedHandoff == null) {
                HorizontalDivider()
                Text("浏览页面", fontWeight = FontWeight.SemiBold)
                pages.forEach { page ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(page.optString("title").ifBlank { page.optString("url").ifBlank { "空白页" } }, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            Text(if (page.optString("openedBy") == "task") "本任务页面" else if (handoffPages.any { it.optString("tabId") == page.optString("tabId") }) "原任务接管页 · 可交还" else "我的页面", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { c.command({ if (page.optString("openedBy") == "task") c.host.takeOverPage(page.getString("tabId")) else c.host.selectOwnerPage(page.getString("tabId")) }) }, enabled = !c.busy) { Text(if (page.optString("openedBy") == "task") "接管保留" else "查看") }
                        if (page.optString("openedBy") == "owner") TextButton(onClick = { c.command({ c.host.closeOwnerPage(page.getString("tabId")) }) }, enabled = !c.busy) { Text("关闭") }
                    }
                }
            }
                }
            if (mode == "sources") {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("保留的依据 · ${sources.size}", Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { c.command({ history = c.host.historyForOwner().rows() }) }, enabled = !c.busy) { Text("研究记录") }
            }
            if (viewedTask.optString("id").isNotEmpty()) Text(viewedTask.optString("goal"), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            val savedPages = state.child("researchPages")
            if (savedPages.array("pages").length() > 0) {
                TextButton(onClick = { showRecoveryPages = !showRecoveryPages }) { Text(if (showRecoveryPages) "收起研究页面" else "研究页面 · ${savedPages.array("pages").length()}") }
                if (showRecoveryPages) {
                    Text("这些是原任务保存的页面位置，不是仍在运行的网页。重开后需要重新读取；未保存表单、登录跳转参数和旧操作引用不会恢复。", style = MaterialTheme.typography.bodySmall)
                    savedPages.array("pages").rows().forEach { saved ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(saved.optString("title").ifBlank { saved.optString("url").ifBlank { "地址未保存的页面" } }, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(when (saved.optString("state")) { "owner_control" -> "上次由你接管"; "unknown" -> "上次操作结果待核对"; "renderer_gone" -> "上次页面已中断"; "closed" -> "上次已关闭"; else -> "重开后重新读取" }, style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { val task = viewedTask.getString("id"); val pageId = saved.getString("logicalPageId"); val fingerprint = state.getString("researchPagesFingerprint"); c.command({ c.host.reopenResearchPageForOwner(task, pageId, fingerprint) }, page) }, enabled = !c.busy && saved.optString("url").isNotEmpty() && state.optString("profileId") == savedPages.optString("profileId")) { Text("重开") }
                        }
                        if (saved.optBoolean("urlQueryOmitted")) Text("原地址的查询参数未保存，可能需要重新搜索或选择内容。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.optBoolean("researchRecoveryAvailable")) Button(onClick = { recoveryConsent = false; recoveryReview = JSONObject().put("taskId", viewedTask.getString("id")).put("manifestFingerprint", state.getString("researchPagesFingerprint")).put("profile", JSONObject(state.toString())) }, enabled = !c.busy) { Text("恢复这个研究任务…") }
                    else if (state.optBoolean("researchRecoveryUnresolved")) TextButton(onClick = { c.returnToTask(viewedTask.getString("id")) }) { Text("先回原任务核对上次结果") }
                }
            }
            val conclusion = viewedTask.child("evidence").optString("modelSummary").ifBlank { viewedTask.optString("summary") }
            if (conclusion.isNotBlank()) {
                Text(conclusion, maxLines = 6, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    val citations = sources.map { source -> JSONObject().put("taskId", source.optString("taskId")).put("sourceId", source.optString("sourceId")).put("sourceRef", source.optString("sourceRef")).put("fieldRefs", source.optJSONArray("fieldRefs") ?: JSONArray()) }
                    resultDetails = ResearchResultPin(conclusion, viewedTask.getString("id"), citations)
                }) { Text("查看研究结论") }
            }
            if (sources.isEmpty()) Text("读取网页后可保留原文摘录与出处，结束后仍能从原任务找回。", style = MaterialTheme.typography.bodySmall)
            sources.forEachIndexed { index, source ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${index + 1}. ${source.optString("title").ifBlank { "网页来源" }}", style = MaterialTheme.typography.bodyMedium)
                    Text(source.optString("excerpt"), maxLines = 3, style = MaterialTheme.typography.bodySmall)
                    if (source.optString("profileId") != state.optString("profileId")) Text("来自另一个登录资料；当时原文仍可回查，另看网页需先明确选回原资料。", style = MaterialTheme.typography.bodySmall)
                    if (source.optString("kind") == "browser_semantic_observation") Text(sourceScopeNote(source), maxLines = 3, style = MaterialTheme.typography.bodySmall)
                    if (source.optString("kind") == "public_http_observation") Text(if (source.child("http").optBoolean("bodyTruncated") || source.child("http").optBoolean("projectionTruncated")) "匿名 HTTP · 正文片段；回看使用原登录资料" else "匿名 HTTP · 未渲染；回看使用原登录资料", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { var actual: JSONObject? = null; c.command({ actual = c.host.savedSourceForOwner(source.getString("taskId"), source.getString("sourceId")) }, { sourceDetails = actual }) }, enabled = !c.busy) { Text("查看当时原文") }
                        TextButton(onClick = { c.command({ c.host.openSourceForOwner(source.getString("taskId"), source.getString("sourceId")) }) }, enabled = !c.busy) { Text(if (source.optString("kind") == "public_http_observation") "用原 WebView 另看" else "另看现网页") }
                    }
                }
            }
            val downloads = state.array("downloads").rows()
            if (downloads.isNotEmpty()) {
                Text("下载资料", fontWeight = FontWeight.SemiBold)
                downloads.forEach { download ->
                    val file = download.optJSONObject("attachment")
                    Column(Modifier.fillMaxWidth()) {
                        Text("${download.optString("name", download.optString("displayName", "文件"))} · ${when(download.optString("status")) { "downloaded" -> "已存入附件库"; "queued" -> "下载排队"; "running" -> "正在下载"; "stopping" -> "正在停止"; "cancelled" -> "已停止"; "failed" -> "下载未完成"; "interrupted" -> "已中断，未自动重试"; "unknown" -> "文件回执待核对"; else -> "状态待核对" }}", style = MaterialTheme.typography.bodySmall)
                        if (file != null && download.optString("status") == "downloaded" && download.optString("taskId") == viewedTask.optString("id")) TextButton(onClick = { c.openDownload(viewedTask.getString("id"), download) }, enabled = !c.busy) { Text("打开已保存文件") }
                    }
                }
            }
            }
        }
    }
    if (existingTasks) AlertDialog(onDismissRequest = { existingTasks = false }, title = { Text("接入原助手任务") }, text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { Text("仅接入本人正在执行的私人助手任务；下一轮沿用原模型与执行控制。")
        state.array("currentAssistantTasks").rows().forEach { task -> TextButton(onClick = { val pinned = JSONObject(state.toString()); val frameText = c.extraFrameOrigins; if (c.autoRead) c.reviewReadScopeAndFrames { read, frames -> readScopeReview = ReadScopeReview(read, "existing", pinned, task.getString("taskId"), task.optString("goal"), profile?.optString("label").orEmpty(), frames) } else c.commandWithDraft({ c.host.allowExistingForOwner(task.getString("taskId"), pinned, c.consent, BrowserReadScope.disabled(), BrowserFrameOrigins.forOwner(frameText)) }, { existingTasks = false; c.consent = false; c.resetReadConsent() }) }, enabled = !c.busy) { Text(task.optString("goal"), maxLines = 3) } }
    } }, confirmButton = { TextButton(onClick = { existingTasks = false }) { Text("返回") } })
    if (identities) AlertDialog(onDismissRequest = { identities = false }, title = { Text("登录资料") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("网页与任务页使用所选登录身份；我的页面不会自动成为任务页。", style = MaterialTheme.typography.bodySmall)
            profileRows.filter { it.optString("status") == "active" }.forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { switchTo = item }, enabled = item.optBoolean("available") && !c.busy) { Text(item.optString("label") + if (item.optBoolean("selected")) " · 当前" else "") }
                    if (item.optBoolean("deletable")) TextButton(onClick = { deleteProfile = item }, enabled = !c.busy) { Text("删除") }
                }
            }
            if (profiles.optBoolean("multiProfileSupported")) {
                OutlinedTextField(newLabel, { if (it.length <= 40) newLabel = it }, label = { Text("新登录资料名称") }, singleLine = true)
                OutlinedButton(onClick = { c.command({ c.host.createProfileForOwner(newLabel, profiles.getLong("revision")) }, { newLabel = "" }) }, enabled = newLabel.isNotBlank() && !c.busy) { Text("创建资料") }
            } else Text("当前浏览组件不支持多登录资料；仍可使用默认资料开展研究。", style = MaterialTheme.typography.bodySmall)
            c.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { identities = false }) { Text("返回网页") } })
    switchTo?.let { target -> AlertDialog(onDismissRequest = { switchTo = null }, title = { Text("使用“${target.optString("label")}”") }, text = { Text("切换会结束当前自动研究并关闭当前资料的页面；登录状态和已保存来源会保留。") }, confirmButton = { TextButton(onClick = { c.commandWithDraft({ c.host.selectProfileForOwner(target.getString("id"), profiles.getLong("revision")) }, { switchTo = null; identities = false }) }, enabled = !c.busy) { Text("切换资料") } }, dismissButton = { TextButton(onClick = { switchTo = null }) { Text("取消") } }) }
    deleteProfile?.let { target -> AlertDialog(onDismissRequest = { deleteProfile = null }, title = { Text("删除“${target.optString("label")}”") }, text = { Text("会停止本资料浏览、删除其网页登录数据，清除本资料的浏览历史与书签，并清空此资料下全部研究文字草稿（包括不同原任务的草稿）。网页登录数据清理可能需要重启应用；已保存的研究来源仍保留。") }, confirmButton = { TextButton(onClick = { run { var warning: String? = null; c.command({ val deleted = c.host.deleteProfileForOwner(target.getString("id"), profiles.getLong("revision")); if (!deleted.optBoolean("researchDraftsCleared", true)) warning = deleted.optString("researchDraftCleanupMessage") }, { deleteProfile = null; if (!warning.isNullOrBlank()) c.error = warning }) } }, enabled = !c.busy) { Text("删除资料") } }, dismissButton = { TextButton(onClick = { deleteProfile = null }) { Text("保留") } }) }
    approval?.let { request ->
        val stillPending = pending.any { it.optString("id") == request.optString("id") }
        AlertDialog(onDismissRequest = { approval = null }, title = { Text(toolLabel(request.optString("tool"))) }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val publicRead = request.optString("tool") == "browser_task_read_public"
                Text(if (publicRead) "匿名公共读取，不使用登录资料或系统代理凭据" else "登录资料：${profile?.optString("label") ?: "当前资料"}")
                val args = request.child("arguments")
                if (request.optString("tool") == "browser_task_retain_source") {
                    val saved = request.child("capability").child("sourceRetentionPreview")
                    Text(if (saved.optString("mode") in setOf("table", "list")) "保留本次模型已读的 ${saved.optInt("rows")} 行、${saved.optInt("fields")} 个字段${if (saved.optBoolean("partial")) "（片段）" else ""}；不另抓整页。" else "仅保留本次实际已读文字摘录。", style = MaterialTheme.typography.bodyMedium)
                    if (saved.optString("excerptPreview").isNotBlank()) Text(saved.optString("excerptPreview"), style = MaterialTheme.typography.bodySmall)
                }
                if (publicRead) {
                    val policy = request.child("capability").child("publicHttpPolicy")
                    if (policy.optString("requestUrl") != args.optString("url")) Text("实际请求 URL\n${policy.optString("requestUrl")}", style = MaterialTheme.typography.bodySmall)
                    Text("最多 3 次跳转、全链 8 秒、正文 ${policy.optInt("maxBodyBytes", 262144)} bytes；不访问私有网络。", style = MaterialTheme.typography.bodySmall)
                    Text("明确允许的额外跳转 origin：${policy.array("approvedRedirectOrigins").toString()}；另仅允许同 origin 与同主机 HTTPS 升级，禁止降级。", style = MaterialTheme.typography.bodySmall)
                    Text("JS 或登录页不会自动换后端；需在原 WebView 明确打开，登录由你处理并交还原任务。", style = MaterialTheme.typography.bodySmall)
                }
                val embedded = request.child("capability").child("readFrame")
                if (embedded.length() > 0) Text("只读这个已展示的嵌入来源：${embedded.optString("sourceOrigin")}。页面或frame更换会拒绝旧引用。", style = MaterialTheme.typography.bodyMedium)
                val target = request.child("capability").child("targetPreview")
                if (request.optString("tool") in setOf("browser_task_select_option", "browser_task_set_checked")) Text(runCatching { BrowserFormControls.approvalSummary(target, request.child("arguments")) }.getOrElse { "此控件状态已变化，请拒绝并重新观察。" }, style = MaterialTheme.typography.bodyMedium)
                if (target.length() > 0) { Text("页面标注（未经核实）\n${target.optString("text").ifBlank { target.optString("tag") }}"); if (target.optString("href").isNotBlank()) Text(target.optString("href"), style = MaterialTheme.typography.bodySmall) }
                if (args.optString("condition") == "target") {
                    val predicate = args.child("target"); val selector = predicate.child("selector")
                    Text("只读等待：原已展示区域${selector.optString("name").takeIf { it.isNotBlank() }?.let { "中的“$it”" }.orEmpty()}；不会点击或重发提交。", style = MaterialTheme.typography.bodySmall)
                    val label = when (predicate.optString("state")) { "present" -> "目标出现"; "absent" -> "目标不再出现"; "text" -> "文字包含"; "value" -> "字段值逐字等于"; "checked" -> "勾选状态"; "enabled" -> "启用状态"; else -> "目标状态" }
                    Text(label + if (predicate.has("value")) "：${predicate.optString("value")}" else if (predicate.has("expected")) if (predicate.optBoolean("expected")) "：是" else "：否" else "", style = MaterialTheme.typography.bodySmall)
                    Text("最多 ${args.optInt("timeoutMs", 3000)} 毫秒；所读目标状态持续 ${predicate.optInt("stableMs")} 毫秒。页面状态不等于服务器业务成功。", style = MaterialTheme.typography.bodySmall)
                }
                if (request.optString("tool") == BrowserVisualCapture.TOOL) {
                    Text("将当前已确认网页的真实可见图像发送给模型；可能包含页面显示的个人信息。图像不默认保存，不用于自动授权或坐标点击。", style = MaterialTheme.typography.bodyMedium)
                    Text("确认后会收起此面板，等同一页面回到原显示区域再取一次图。检测到密码、验证码或未核对的嵌入内容时会阻止截图；其他可见个人信息仍请你确认。", style = MaterialTheme.typography.bodySmall)
                }
                listOf("url" to "目标网页", "text" to "填写内容", "excerpt" to "保留摘录", "condition" to "等待条件", "value" to "等待内容", "dy" to "滚动距离").forEach { (key, label) -> if (args.has(key)) Text("$label\n${args.get(key)}", style = MaterialTheme.typography.bodyMedium) }
                if (args.has("tabId")) Text(pages.firstOrNull { it.optString("tabId") == args.optString("tabId") }?.optString("title").orEmpty(), style = MaterialTheme.typography.bodySmall)
                Text("网页文字与操作回执未经核实；确认仅适用于这次操作。", style = MaterialTheme.typography.bodySmall)
                if (!stillPending) Text("本次请求已失效，请返回工作区查看当前状态。", color = MaterialTheme.colorScheme.error)
                c.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { if (request.optString("tool") == BrowserVisualCapture.TOOL) { approval = null; c.backToPage() }; c.command({ checkNotNull(c.host.decideIfWaiting(activeTask.getString("id"), request.getString("id"), true)) { "原浏览请求已不再等待" } }, { approval = null }) }, enabled = stillPending && !c.busy) { Text("确认这次操作") } }, dismissButton = { TextButton(onClick = { c.command({ checkNotNull(c.host.decideIfWaiting(activeTask.getString("id"), request.getString("id"), false)) { "原浏览请求已不再等待" } }, { approval = null }) }, enabled = stillPending && !c.busy) { Text("拒绝") } })
    }
    recipeSource?.let { source -> AlertDialog(onDismissRequest = { recipeSource = null; recipePreview = null }, title = { Text("制作网站只读配方") }, text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("下次读取这个网站时，按同一结构直接整理字段；表头、路径或页面变化就回普通读取。配方不包含账号授权，也不会导航、点击或提交。"); Text(source.optString("url"), style = MaterialTheme.typography.bodySmall); OutlinedTextField(value = recipeQuery, onValueChange = { recipeQuery = it.take(160); recipePreview = null }, label = { Text("区域识别词，如这张表的标题") }, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { val query = recipeQuery; var actual: JSONObject? = null; c.command({ actual = c.host.prepareRecipeForOwner(source.getString("taskId"), source.getString("sourceId"), query) }, { if (recipeQuery == query) recipePreview = actual }) }, enabled = !c.busy && recipeQuery.isNotBlank()) { Text("核对读取字段") }; recipePreview?.let { preview -> Text("字段：${preview.array("expectedHeaders").rows().joinToString("、") { it.optString("text") }.ifBlank { "列表逐字正文与原行位置" }}"); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = recipeEnabled, onCheckedChange = { recipeEnabled = it }); Text("保存后启用这份只读配方（仍使用本次任务的网页读取授权）。", Modifier.weight(1f)) } } } }, confirmButton = { TextButton(onClick = { val preview = recipePreview ?: return@TextButton; val expected = dev.pocketengine.store.TaskStore.capabilityFingerprint(preview); val query = preview.getString("query"); val enable = recipeEnabled; c.command({ c.host.saveRecipeForOwner(source.getString("taskId"), source.getString("sourceId"), query, expected, enable) }, { recipeSource = null; recipePreview = null }) }, enabled = recipePreview != null && !c.busy) { Text(if (recipeEnabled) "保存并启用" else "保存为停用配方") } }, dismissButton = { TextButton(onClick = { recipeSource = null; recipePreview = null }) { Text("取消") } }) }
    recoveryReview?.let { selected -> AlertDialog(onDismissRequest = { recoveryReview = null; recoveryConsent = false }, title = { Text("重新继续原研究？") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("保留同一个任务和已保存依据，新建执行并重新读取需要的页面。重开仍使用当前这份登录资料并逐次确认；不恢复旧授权、不自动重发未知操作，也不保证未保存表单可恢复。"); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = recoveryConsent, onCheckedChange = { recoveryConsent = it }); Text("我同意把重新读取的网页观察发送给当前模型。", Modifier.weight(1f)) } } }, confirmButton = { TextButton(onClick = { val pinned = JSONObject(selected.toString()); c.command({ c.host.recoverResearchForOwner(pinned.getString("taskId"), pinned.getString("manifestFingerprint"), pinned.getJSONObject("profile"), true) }, { recoveryReview = null; recoveryConsent = false; c.workspaceView = "research" }) }, enabled = recoveryConsent && !c.busy) { Text("恢复原任务") } }, dismissButton = { TextButton(onClick = { recoveryReview = null; recoveryConsent = false }) { Text("暂不恢复") } }) }
    history?.let { rows -> AlertDialog(onDismissRequest = { history = null }, title = { Text("研究记录") }, text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { if (rows.isEmpty()) Text("还没有研究记录") else rows.forEach { row -> TextButton(onClick = { c.command({ c.host.selectHistoryForOwner(row.getString("taskId")) }, { history = null }) }) { Column { Text(row.optString("goal"), maxLines = 3); Text(statusLabel(row.optString("status")), style = MaterialTheme.typography.bodySmall) } } } } }, confirmButton = { TextButton(onClick = { history = null }) { Text("返回") } }) }
    sourceDetails?.let { source -> AlertDialog(onDismissRequest = { sourceDetails = null }, title = { Text(source.optString("title").ifBlank { "保留的网页摘录" }) }, text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("已保存的当时内容 · 不会自动换成现网页", fontWeight = FontWeight.SemiBold); if (source.has("structuredProjection")) SavedStructuredSource(source) { ref -> var actual: JSONObject? = null; c.command({ actual = c.host.savedFieldForOwner(source.getString("taskId"), ref) }, { fieldDetails = actual }) } else SelectionContainer { Text(source.optString("excerpt")) }; Text(source.optString("url"), style = MaterialTheme.typography.bodySmall); Text(sourceScopeNote(source), style = MaterialTheme.typography.bodySmall); if (source.has("readRecipe")) Text("读取配方版本 ${source.child("readRecipe").optInt("revision")} · ${if (source.child("readRecipe").optString("status") == "matched") "结构已匹配" else "本次配方已失配，使用普通读取证据"}", style = MaterialTheme.typography.bodySmall); if (source.has("structuredProjection") && !source.child("structuredProjection").optBoolean("partial", true)) TextButton(onClick = { recipeSource = JSONObject(source.toString()); recipeQuery = ""; recipePreview = null; recipeEnabled = false; sourceDetails = null }) { Text("制作网站只读配方…") } } }, confirmButton = { TextButton(onClick = { sourceDetails = null }) { Text("返回结果") } }, dismissButton = { TextButton(onClick = { c.command({ c.host.openSourceForOwner(source.getString("taskId"), source.getString("sourceId")) }, { sourceDetails = null; resultDetails = null }) }, enabled = !c.busy) { Text("另看现网页") } }) }
    fieldDetails?.let { field -> AlertDialog(onDismissRequest = { fieldDetails = null }, title = { Text("当时保存的字段") }, text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(field.optString("title"), style = MaterialTheme.typography.bodySmall); SelectionContainer { Text(field.optString("text"), style = MaterialTheme.typography.bodyLarge) }; val headers = field.array("headers").rows(); if (headers.isNotEmpty()) { Text("同时保存的表头", fontWeight = FontWeight.SemiBold); Text(headers.joinToString(" | ") { it.optString("text") }, style = MaterialTheme.typography.bodySmall) }; Text("保存位置：第 ${field.child("row").optInt("index") + 1} 项" + if (field.optString("mode") == "table") " · 第 ${field.child("field").optInt("column") + 1} 列" else "", style = MaterialTheme.typography.bodySmall); Text(if (field.optBoolean("partial")) "来自当时保存的内容片段，未自动补读网页。" else "来自当时交付模型并保存的内容，网页文字仍需核实。", style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton(onClick = { fieldDetails = null }) { Text("返回依据") } }) }
    c.draftConflict?.let { actual -> AlertDialog(onDismissRequest = { c.draftConflict = null }, title = { Text("核对这个原研究草稿") }, text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("已保存文字"); SelectionContainer { Text(actual.optString("text").ifEmpty { "（已清空）" }) }; HorizontalDivider(); Text("当前尚未确认保存的输入"); SelectionContainer { Text(c.goal.ifEmpty { "（空白）" }) }; Text("只保存文字，不开始任务或恢复授权。保存时仍核对这个版本。", style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton(onClick = { c.resolveDraftConflict(actual, true) }, enabled = !c.busy) { Text("保留当前输入并保存新版本") } }, dismissButton = { TextButton(onClick = { c.resolveDraftConflict(actual, false) }, enabled = !c.busy) { Text("使用已保存文字") } }) }
    resultDetails?.let { result -> AlertDialog(onDismissRequest = { resultDetails = null }, title = { Text("原任务研究结论") }, text = {
        ResearchResultView(result.text, result.taskId, result.citations, onField = { ref -> var actual: JSONObject? = null; c.command({ actual = c.host.savedFieldForOwner(result.taskId, ref) }, { fieldDetails = actual }) }) { sourceId ->
            val taskId = result.taskId; var actual: JSONObject? = null
            c.command({ actual = c.host.savedSourceForOwner(taskId, sourceId) }, { sourceDetails = actual })
        }
    }, confirmButton = { TextButton(onClick = { resultDetails = null }) { Text("返回工作区") } }) }
    if (readScopeOptions) AlertDialog(onDismissRequest = { readScopeOptions = false }, title = { Text("本轮只读范围") }, text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { Text("仅用于当前原任务，暂停、接管或更换资料后失效。旧范围不会自动恢复。", style = MaterialTheme.typography.bodySmall); ReadScopeChoices(c, false); c.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(onClick = { val pin = JSONObject(state.child("readScopeIdentity").toString()); c.reviewReadScope { readScopeReview = ReadScopeReview(it, "renew", pin, activeTask.getString("id"), activeTask.optString("goal"), profile?.optString("label").orEmpty()) } }, enabled = c.autoRead && !c.busy) { Text("核对具体范围") } }, dismissButton = { TextButton(onClick = { readScopeOptions = false; c.resetReadConsent() }) { Text("不授权") } })
    readScopeReview?.let { review ->
        val policy = review.consent.review()
        AlertDialog(onDismissRequest = { readScopeReview = null }, title = { Text("明确授予这轮只读范围？") }, text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(review.goal, maxLines = 4); Text("资料：${review.profileLabel} · 原任务 / 原执行", style = MaterialTheme.typography.bodySmall)
            Text("最长5分钟，最多32次只读 / 来源调用；匿名正文累计最多1MiB。不会延长原任务授权。可随时撤销，暂停 / 接管 / 资料或会话变化后须重新明确授权。", style = MaterialTheme.typography.bodySmall)
            Text(if (policy.optBoolean("allowSourceRetention")) "你明确同意把实际已读摘录保留在原任务；未核实的正文不会变成已验证结论。" else "不自动保存网页来源；来源保存仍逐次确认。", style = MaterialTheme.typography.bodySmall)
            val seeds = policy.getJSONArray("publicSeeds")
            Text(if (seeds.length() == 0) "未授予匿名公共URL读取。" else "仅这些逐字固定的匿名公共URL；重定向仅限这些种子origin / 同主机HTTPS升级，私有网络始终拒绝。", style = MaterialTheme.typography.bodySmall)
            for (i in 0 until seeds.length()) Text(seeds.getString(i), style = MaterialTheme.typography.bodySmall)
            Text("仅本轮已明确打开的任务页 observe / wait、原下载状态及明确同意的来源保留。点击、输入、打开 / 跳转、下载 / 上传、账号操作仍逐次确认或交由你处理；不自动探索页面href。", style = MaterialTheme.typography.bodySmall)
            if (review.target != "renew") {
                Text("嵌入网页默认只读顶层同源；以下为你明确加入本任务的额外来源。不会自动重载已打开的登录页，也不开放密码或设备权限。", style = MaterialTheme.typography.bodySmall)
                val frames = review.frameOrigins.summary(); for (i in 0 until frames.length()) Text(frames.getString(i), style = MaterialTheme.typography.bodySmall)
            }
            c.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { c.commandWithDraft({ when (review.target) { "start" -> c.host.startForOwner(review.goal, review.pin, true, review.consent, review.frameOrigins); "existing" -> c.host.allowExistingForOwner(review.taskId, review.pin, true, review.consent, review.frameOrigins); else -> c.host.grantReadScopeForOwner(review.taskId, review.pin, review.consent) } }, { readScopeReview = null; readScopeOptions = false; existingTasks = false; c.consent = false; if (review.target == "start") c.consumedGoal(); c.resetReadConsent(); if (review.target != "renew") c.backToPage() }) }, enabled = !c.busy) { Text("授予这轮范围") } }, dismissButton = { TextButton(onClick = { readScopeReview = null }) { Text("不授权") } })
    }
}

@Composable private fun ReadScopeChoices(c: OwnerController, frameChoice: Boolean = true) {
    Row(Modifier.fillMaxWidth()) { Checkbox(checked = c.autoRead, onCheckedChange = { c.autoRead = it }, enabled = !c.busy); Text("本轮自动只读范围（默认关闭）", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall) }
    if (c.autoRead) {
        Row(Modifier.fillMaxWidth()) { Checkbox(checked = c.saveReadSources, onCheckedChange = { c.saveReadSources = it }, enabled = !c.busy); Text("另行同意把真实已读摘录保留在本任务（匿名HTTP读取会同时保留来源）", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(c.publicSeeds, { if (it.length <= 16400) c.publicSeeds = it }, label = { Text("明确公共种子URL · 最多8个，每行一个") }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4, enabled = !c.busy)
        Text("仅本轮已明确打开的任务页只读和明确种子，5分钟 / 32次 / 正文累计1MiB。下一步会逐字展示实际URL供你确认；不自动点击、输入、打开或跳转网页。", style = MaterialTheme.typography.bodySmall)
    }
    if (frameChoice) {
        OutlinedTextField(c.extraFrameOrigins, { if (it.length <= 4200) c.extraFrameOrigins = it }, label = { Text("可选 · 本任务额外嵌入来源") }, placeholder = { Text("https://embedded.example · 每行一个，最多8个") }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4, enabled = !c.busy)
        Text("默认仅同源。填入即明确允许本任务读取这些origin内已成功桥接的嵌入正文，不含账号密码或硬件权限；不支持通配。已有页不自动刷新，未接入的frame会明确说明。", style = MaterialTheme.typography.bodySmall)
    }
}

/** Native bounded display only. HTML/URLs/model reference text never become an executable bridge. */
@Composable private fun ResearchResultView(summary: String, taskId: String, sources: List<JSONObject>, onField: (String) -> Unit, onSource: (String) -> Unit) {
    var original by remember(summary, taskId) { mutableStateOf(false) }
    val sourceKey = sources.joinToString("|") { it.optString("sourceRef") + it.optJSONArray("fieldRefs")?.toString().orEmpty() }
    val projection = remember(summary, taskId, sourceKey) { runCatching { BrowserResearchResult.project(taskId, summary, JSONArray().apply { sources.forEach { put(it) } }) }.getOrNull() }
    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("模型整理，尚未独立核实；点击依据回看当时保存的原文。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { original = !original }) { Text(if (original) "返回可回查结果" else "查看完整原回答") }
        if (original || projection == null) SelectionContainer { Text(summary) }
        else {
            if (projection.optBoolean("partialPresentation")) Text("此处为有界展示，未显示部分仍保留在完整原回答中。", style = MaterialTheme.typography.bodySmall)
            projection.array("blocks").rows().forEach { block ->
                if (block.optString("kind") == "table") {
                    val headers = block.array("headers"); val rows = block.array("rows")
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        Row { for (column in 0 until headers.length()) Text(headers.optString(column), Modifier.width(144.dp).padding(6.dp), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall) }
                        for (rowIndex in 0 until rows.length()) {
                            val row = rows.optJSONArray(rowIndex) ?: JSONArray()
                            Row { for (column in 0 until headers.length()) { val cell = row.optJSONObject(column) ?: JSONObject(); Column(Modifier.width(144.dp).padding(6.dp)) { Text(cell.optString("text"), style = MaterialTheme.typography.bodySmall); ResultSourceButtons(cell, onSource, onField) } } }
                            HorizontalDivider()
                        }
                    }
                } else { SelectionContainer { Text(block.optString("text")) }; ResultSourceButtons(block, onSource, onField) }
            }
        }
    }
}
@Composable private fun ResultSourceButtons(cell: JSONObject, onSource: (String) -> Unit, onField: (String) -> Unit) {
    val ids = cell.array("sourceIds")
    for (index in 0 until ids.length()) TextButton(onClick = { onSource(ids.getString(index)) }, contentPadding = PaddingValues(0.dp)) { Text(if (ids.length() == 1) "查看依据" else "查看依据 ${index + 1}", style = MaterialTheme.typography.bodySmall) }
    val fields = cell.array("fieldRefs")
    for (index in 0 until fields.length()) TextButton(onClick = { onField(fields.getString(index)) }, contentPadding = PaddingValues(0.dp)) { Text(if (fields.length() == 1) "查看当时字段" else "查看当时字段 ${index + 1}", style = MaterialTheme.typography.bodySmall) }
}

@Composable private fun SavedStructuredSource(source: JSONObject, onField: (String) -> Unit) {
    val projection = source.child("structuredProjection")
    val prefix = source.optString("sourceRef") + "#field:" + projection.optString("revision") + ":"
    if (projection.optBoolean("partial")) Text("当时只保存了部分行或字段，未补成整页。", style = MaterialTheme.typography.bodySmall)
    if (projection.optString("mode") == "list") projection.array("data").rows().forEach { item -> Column { SelectionContainer { Text(item.optString("text")) }; TextButton(onClick = { onField(prefix + "i" + item.optInt("index")) }) { Text("查看这一项") } } }
    else Column(Modifier.horizontalScroll(rememberScrollState())) { projection.array("data").rows().forEach { row -> Row { row.array("cells").rows().forEach { cell -> Column(Modifier.width(144.dp).padding(6.dp)) { Text(cell.optString("text"), style = MaterialTheme.typography.bodySmall, fontWeight = if (cell.optString("role") == "columnheader") FontWeight.SemiBold else FontWeight.Normal); TextButton(onClick = { onField(prefix + "r" + row.optInt("index") + "c" + cell.optInt("column")) }, contentPadding = PaddingValues(0.dp)) { Text("查看字段", style = MaterialTheme.typography.bodySmall) } } } }; HorizontalDivider() } }
}
