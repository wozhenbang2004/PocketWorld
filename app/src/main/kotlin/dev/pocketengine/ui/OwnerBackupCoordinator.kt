package dev.pocketengine.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.workspace.OwnerDataArchives
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

internal data class OwnerBackupState(
    val open: Boolean = false, val busy: Boolean = false, val presentation: String = "settings", val projectMemoryName: String? = null, val projectMemoryCurrent: Boolean = false, val inventory: JSONObject? = null,
    val selection: JSONObject? = null, val preview: JSONObject? = null, val review: JSONObject? = null,
    val originalAction: String? = null, val originalPhase: JSONObject? = null, val result: JSONObject? = null,
    val experiencePage: JSONObject? = null, val projectAction: String? = null, val notice: String? = null, val error: String? = null
)

/** Native owner selection and read-side lifecycle. Original FeatureHost/TaskStore
 * own dispatched restore phases; closing this page never replays or cancels them. */
internal class OwnerBackupCoordinator(
    private val activity: ComposeActivity, private val app: EngineApplication,
    private val io: ExecutorService, private val main: Handler, private val privateOwner: () -> Boolean,
    private val openCheckout: (JSONObject) -> Unit, private val openAttachment: (JSONObject) -> Unit
) {
    var state by mutableStateOf(OwnerBackupState()); private set
    private var generation = 0L
    private var experienceRevision = 0L
    private var foreground = true
    private var closed = false
    private var observation: RunControl? = null
    private var future: Future<*>? = null
    private var selected: OwnerDataArchives.Selection? = null
    // Live native selection only. Bundle/JSON never restores this opaque authority.
    private var projectMemorySelection: dev.pocketengine.features.memory.ProjectMemoryNamespaces.Selection? = null
    private var pendingReview: OwnerDataArchives.Review? = null
    private var reviewControl: RunControl? = null
    private var picker: JSONObject? = null
    private var restoredPicker = false
    private var receivedPickerUri: Uri? = null
    private var receivedPicker = false
    private fun host() = app.features.ownerDataArchives(app)
    private fun owner() { check(!closed && foreground && privateOwner()) { "请在本机私人页面打开备份与迁移" } }
    fun open() { owner(); projectMemorySelection = null; state = state.copy(open = true, presentation = "settings", projectMemoryName = null, projectMemoryCurrent = false); refresh() }
    fun openProjectMemory(selected: dev.pocketengine.features.memory.ProjectMemoryNamespaces.Selection, name: String) {
        owner(); if (state.busy || pending()) { state = state.copy(error = "请先核对原备份动作；未更换范围。"); return }
        clearReview(); this.selected = null; projectMemorySelection = selected
        state = OwnerBackupState(open = true, presentation = "memory_review", projectMemoryName = name, projectMemoryCurrent = true, notice = "仅备份刚才原生显选的项目及其记忆；不会读取主人全局或其它项目记忆。")
        refresh()
    }
    fun leaveMemoryReview() { if (state.presentation == "memory_review") closePage() }
    fun openCache(id: String) {
        owner(); projectMemorySelection = null; state = state.copy(open = true, presentation = "settings", projectMemoryName = null, projectMemoryCurrent = false)
        if (state.busy || pending()) { state = state.copy(error = "请先核对原恢复动作；当前备份身份没有切换。"); return }
        clearReview()
        work { c -> val actual = host().readLocalCacheForOwner(id, c); val metadata = actual.metadata(); val preview = host().preview(actual, c); { selected = actual; state = state.copy(selection = metadata, preview = preview, experiencePage = null, result = null, notice = "已核对原本机备份缓存；可以明确导出或仅移除此临时副本。") } }
    }
    fun refresh() = work { c ->
        val actual = host().inventory(c)
        val pin = state.selection
        val reopened = pin?.let { host().reopen(it.getString("selectionId"), it.getString("archiveSha256"), c) }
        val meta = reopened?.metadata(); val preview = reopened?.let { host().preview(it, c) }
        return@work { selected = reopened; state = state.copy(inventory = actual, selection = meta ?: pin, preview = preview ?: state.preview) }
    }
    fun closePage() { cancelRead(); clearReview(); projectMemorySelection = null; state = state.copy(open = false, busy = false, projectMemoryName = null, projectMemoryCurrent = false) }
    fun create(sections: Set<String>) {
        owner(); if (!state.open) { state = state.copy(error = "原备份页面已关闭；没有生成其它范围的备份。"); return }; if (state.busy || pending()) return
        val project = projectMemorySelection
        if (state.projectMemoryName != null && (project == null || !state.projectMemoryCurrent)) { state = state.copy(error = "原项目记忆选择已失效；请回原记忆审核页重新显选。未回退全局备份。"); return }
        if (project != null && sections != setOf("projects", "memory")) { state = state.copy(error = "项目记忆备份仅含此项目、其私人副本和其记忆；没有扩大分区。"); return }
        clearReview()
        work(onFailure = { if (project != null) { projectMemorySelection = null; state = state.copy(projectMemoryCurrent = false) } }) { c ->
            val original = if (project != null) host().createProjectMemory(project, c) else host().create(sections, c)
            val metadata = original.metadata(); val preview = host().preview(original, c)
            return@work { selected = original; state = state.copy(selection = metadata, preview = preview, experiencePage = null, result = null, originalAction = null, originalPhase = null, projectAction = null, notice = "已生成这次明确范围的完整备份。请导出到你选择的位置；它未加密，用户内容可能含敏感信息。") }
        }
    }
    fun readExperiencePage(index: Int = 0) {
        owner(); if (state.busy) return
        val metadata = state.selection ?: return
        val revision = ++experienceRevision
        work { c -> val exact = host().reopen(metadata.getString("selectionId"), metadata.getString("archiveSha256"), c); val page = host().readExperiencePageForOwner(exact, index, c)
            return@work { if (revision == experienceRevision) { selected = exact; state = state.copy(experiencePage = page, notice = "仅打开原经历快照；不激活旧来源、任务、授权或模型上下文。快照之后的删除状态可能未知。") } }
        }
    }
    fun chooseImport() {
        owner(); if (state.busy || pending() || picker != null) return
        clearReview(); picker = JSONObject().put("kind", "import"); restoredPicker = false; activity.pickOwnerDataArchive()
    }
    fun importResult(uri: Uri?) { receivePicker(uri, "import") }
    private fun receivePicker(uri: Uri?, expectedKind: String) {
        if (restoredPicker || picker?.optString("kind") != expectedKind) { picker = null; restoredPicker = false; state = state.copy(error = "原文件选择入口已重建或变化，请重新选择；没有自动读写。"); return }
        if (uri == null) { picker = null; receivedPicker = false; receivedPickerUri = null; return }
        receivedPickerUri = uri; receivedPicker = true; processPicker()
    }
    private fun processPicker() {
        if (!receivedPicker || !foreground || state.busy || closed) return
        val uri = receivedPickerUri ?: return; val pin = picker ?: return
        receivedPickerUri = null; receivedPicker = false; picker = null
        if (!privateOwner()) { state = state.copy(error = "请回私人页面重新选择文件；没有读写或恢复。"); return }
        if (pin.optString("kind") == "export") finishExport(uri, pin) else finishImport(uri)
    }
    private fun finishImport(uri: Uri) {
        work { c ->
            requireContent(uri)
            val input = app.contentResolver.openInputStream(uri) ?: error("所选备份无法读取")
            val stop = Runnable { runCatching { input.close() } }; c.onCancel(stop)
            val actual = try { input.use { host().read(it, c) } } finally { c.removeCancelAction(stop) }
            val metadata = actual.metadata(); val preview = host().preview(actual, c)
            return@work { selected = actual; state = state.copy(selection = metadata, preview = preview, experiencePage = null, result = null, projectAction = null, notice = "只核对了固定备份，没有恢复。下面各分区须分别审阅、明确确认；现有数据不会覆盖。") }
        }
    }
    fun discardLocalCache() {
        owner(); if (state.busy) return
        val exact = selected ?: return
        work { c -> app.features.discardOwnerArchiveCacheForOwner(exact, c); { selected = null; clearReview(); state = state.copy(selection = null, preview = null, experiencePage = null, review = null, notice = "仅移除了这份本机备份缓存。已导出的文件、项目、原件、成果及环境保留。") } }
    }
    fun offerExport() {
        owner(); if (state.busy || picker != null) return
        val meta = state.selection ?: return
        picker = JSONObject().put("kind", "export").put("selectionId", meta.getString("selectionId")).put("sha256", meta.getString("archiveSha256")); restoredPicker = false
        activity.createOwnerDataArchive("PocketWorld-data-backup.zip")
    }
    fun exportResult(uri: Uri?) { receivePicker(uri, "export") }
    private fun finishExport(uri: Uri, pin: JSONObject) {
        work { c ->
            requireContent(uri)
            val exact = host().reopen(pin.getString("selectionId"), pin.getString("sha256"), c)
            val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("所选目标无法写入")
            val stop = Runnable { runCatching { output.close() } }; c.onCancel(stop)
            val bytes = try { output.use { host().export(exact, it, c) } } catch (failed: Exception) { throw IllegalStateException("导出未完整确认，目标可能已有部分内容；请核对原备份和目标。", failed) } finally { c.removeCancelAction(stop) }
            return@work { state = state.copy(notice = "已导出固定备份（$bytes 字节）。没有上传、恢复权限或启动项目。") }
        }
    }
    private fun pending(): Boolean = state.originalAction != null && state.originalPhase?.optString("status") !in setOf("completed", "failed")
    fun reviewPhase(phase: String) {
        owner(); if (state.busy || pending()) return
        val metadata = state.selection ?: return
        clearReview()
        val reviewOriginal = RunControl(); reviewControl = reviewOriginal
        work(onFailure = { reviewOriginal.cancel(); if (reviewControl === reviewOriginal) reviewControl = null }) { c ->
            val actual = host().reopen(metadata.getString("selectionId"), metadata.getString("archiveSha256"), c)
            val map = app.features.ownerArchiveProjectMapForOwner(state.projectAction.orEmpty(), metadata.getString("archiveSha256"))
            val review = host().review(actual, phase, map, reviewOriginal); val visible = review.metadata()
            return@work { pendingReview = review; selected = actual; state = state.copy(review = visible, result = null, notice = "确认只恢复这一分区到新身份。旧任务、登录凭据、SSH信任与执行授权不会恢复。") }
        }
    }
    fun cancelReview() { clearReview(); state = state.copy(review = null) }
    fun confirmPhase() {
        owner(); if (state.busy) return
        val review = pendingReview ?: return
        val confirmed = review.confirm(); val action = confirmed.actionId()
        // Keep the exact ID before the original host is entered. A failed/missing
        // acknowledgement can only be queried, never silently confirmed again.
        pendingReview = null; reviewControl = null
        state = state.copy(review = null, originalAction = action, originalPhase = null, result = null, notice = "已确认原分区动作；正在登记原任务。")
        work { _ -> val original = app.features.submitOwnerArchivePhaseForOwner(confirmed); { state = state.copy(originalPhase = original, notice = "原恢复任务已登记。离开本页不会重新恢复；应用重开后按原编号只读核对。") } }
    }
    fun pollOriginal() {
        val id = state.originalAction ?: return
        if (state.busy || !foreground || !state.open) return
        work { _ -> val current = app.features.ownerArchivePhaseForOwner(id); { state = state.copy(originalPhase = current); if (current.optString("status") == "completed") queryOriginal() } }
    }
    fun queryOriginal() {
        owner(); if (state.busy) return
        val id = state.originalAction ?: return
        work { c -> val result = app.features.queryOwnerArchivePhaseForOwner(id, c); val row = app.features.ownerArchivePhaseForOwner(id); { state = state.copy(result = result, originalPhase = row, projectAction = if (row.optString("status") == "completed" && row.getJSONObject("scope").getString("phase") == "projects") id else state.projectAction, notice = if (row.optString("status") == "completed") "已找到原分区恢复结果。未重放写入；旧授权未恢复。" else "原恢复尚未完整确认。保留原编号，只读核对，不自动重试。") } }
    }
    fun stopOriginal() {
        owner(); val id = state.originalAction ?: return
        work { _ -> val actual = app.features.cancelOwnerArchivePhaseForOwner(id); { state = state.copy(originalPhase = actual, notice = "已请求停止后续恢复。此前已保存的数据不会回滚；请核对原结果。") } }
    }
    fun readOriginal(id: String) {
        owner(); if (state.busy || pending()) return
        work { c -> val phase = app.features.ownerArchivePhaseForOwner(id); val scope = phase.getJSONObject("scope"); val actual = host().reopen(scope.getString("selectionId"), scope.getString("archiveSha256"), c); val metadata = actual.metadata(); val preview = host().preview(actual, c); { selected = actual; state = state.copy(selection = metadata, preview = preview, experiencePage = null, originalAction = id, originalPhase = phase, review = null); queryOriginal() } }
    }
    fun openRestoredCheckout(value: JSONObject) {
        owner(); if (state.busy || value.optBoolean("found") != true) return
        val local = app.features.module("local") as dev.pocketengine.features.local.LocalModule
        work { _ -> val actual = local.projectCheckoutForHost(value.getString("checkoutId")); check(actual.getString("projectId") == value.getString("projectId")); { closePage(); openCheckout(actual) } }
    }
    fun openRestoredAttachment(value: JSONObject) { owner(); if (!state.busy && value.optString("id").isNotEmpty()) { closePage(); openAttachment(value) } }
    fun invalidateExperienceReadings() { experienceRevision++; state = state.copy(experiencePage = null) }
    fun foreground(visible: Boolean) { invalidateExperienceReadings(); foreground = visible; if (!visible) { cancelRead(); clearReview(); projectMemorySelection = null; state = state.copy(busy = false, review = null, projectMemoryCurrent = false, notice = if (state.projectMemoryName != null) "原项目记忆选择未在后台保留；生成新备份前请回原审核页重新显选。已有原包仍可只读核对。" else state.notice) } else processPicker() }
    fun detach() { closed = true; cancelRead(); clearReview() }
    fun save(bundle: Bundle) {
        val pin = JSONObject().put("selectionId", state.selection?.optString("selectionId").orEmpty()).put("sha256", state.selection?.optString("archiveSha256").orEmpty()).put("actionId", state.originalAction.orEmpty()).put("projectActionId", state.projectAction.orEmpty())
        bundle.putString("ownerDataArchivePosition", pin.toString()); if (picker != null) bundle.putBoolean("ownerDataArchivePickerPending", true)
    }
    fun restore(bundle: Bundle?) {
        projectMemorySelection = null; state = state.copy(projectMemoryCurrent = false)
        restoredPicker = bundle?.getBoolean("ownerDataArchivePickerPending") == true
        val raw = bundle?.getString("ownerDataArchivePosition") ?: return
        runCatching { val pin = JSONObject(raw); state = state.copy(selection = pin.optString("selectionId").takeIf { it.isNotEmpty() }?.let { JSONObject().put("selectionId", it).put("archiveSha256", pin.getString("sha256")) }, originalAction = pin.optString("actionId").takeIf { it.isNotEmpty() }, projectAction = pin.optString("projectActionId").takeIf { it.isNotEmpty() }, notice = "只恢复了原备份和动作定位；没有恢复确认、凭据或写权限。请先核对原结果。") }
    }
    private fun clearReview() { pendingReview = null; reviewControl?.cancel(); reviewControl = null }
    private fun cancelRead() { generation++; observation?.cancel(); observation = null; future?.cancel(true); future = null }
    private fun requireContent(uri: Uri) { check(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "系统未返回可用的文件引用，没有读写其他路径。" } }
    private fun work(onFailure: (() -> Unit)? = null, read: (RunControl) -> (() -> Unit)) {
        owner(); if (state.busy) return
        val epoch = ++generation; val c = RunControl(); observation = c
        state = state.copy(busy = true, error = null)
        future = io.submit {
            try { val accepted = read(c); c.check(); main.post { if (!closed && epoch == generation && foreground && privateOwner()) { observation = null; future = null; state = state.copy(busy = false); accepted() } } }
            catch (failure: Exception) { main.post { if (!closed && epoch == generation) { onFailure?.invoke(); observation = null; future = null; state = state.copy(busy = false, error = failure.message?.take(400) ?: "原备份操作未确认，请核对原结果。") } } }
        }
    }
}
