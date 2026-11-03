package dev.pocketengine.ui

import android.os.Bundle
import android.os.Handler
import android.net.Uri
import androidx.compose.runtime.*
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.project.ProjectStorage
import dev.pocketengine.features.local.runtime.NativeDiagnosticCollector
import dev.pocketengine.features.local.runtime.NativeDiagnosticWindow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import org.json.JSONArray
import org.json.JSONObject

internal data class ProjectStorageState(
    val open: Boolean = false, val busy: Boolean = false, val inventory: JSONObject? = null,
    val selected: Set<String> = emptySet(), val review: JSONObject? = null, val originalAction: String? = null,
    val original: JSONObject? = null, val result: JSONObject? = null, val caches: List<JSONObject> = emptyList(),
    val notice: String? = null, val error: String? = null,
    val diagnosticText: String? = null, val diagnosticSha: String? = null, val diagnosticBytes: Int = 0,
    val diagnosticReviewed: Boolean = false, val diagnosticPickerPending: Boolean = false, val diagnosticExportEntered: Boolean = false
)
/** UI observer over the original runtime lease, Local directory and external task. */
internal class ProjectStorageCoordinator(
    private val app: EngineApplication, private val io: ExecutorService, private val main: Handler,
    private val privateOwner: () -> Boolean, private val openBackup: (String) -> Unit
) {
    var state by mutableStateOf(ProjectStorageState()); private set
    private var generation = 0L
    private var foreground = true
    private var closed = false
    private var control: RunControl? = null
    private var future: Future<*>? = null
    private var review: ProjectStorage.Review? = null
    private var reviewControl: RunControl? = null
    private val diagnosticWindow = NativeDiagnosticWindow { !closed && privateOwner() }
    private var diagnosticPreview: NativeDiagnosticCollector.Preview? = null
    private var diagnosticTicket: NativeDiagnosticWindow.Ticket? = null
    private var diagnosticDestination: Uri? = null
    private fun local() = app.features.module("local") as? LocalModule ?: error("原项目存储宿主不可用")
    private fun owner() { check(!closed && foreground && privateOwner()) { "请从本机私人页面审阅存储范围" } }
    private fun pending() = state.originalAction != null && state.original?.optString("status") != "completed"
    fun open() { owner(); diagnosticWindow.open(); state = state.copy(open = true); refresh() }
    fun closePage() { cancelRead(); clearReview(); clearDiagnostic(); state = state.copy(open = false, busy = false, review = null) }
    private fun clearDiagnostic() { diagnosticWindow.close(); diagnosticPreview = null; diagnosticTicket = null; diagnosticDestination = null; state = state.copy(diagnosticText = null, diagnosticSha = null, diagnosticBytes = 0, diagnosticReviewed = false, diagnosticPickerPending = false, diagnosticExportEntered = false) }
    fun readDiagnostics() {
        owner(); if (state.busy || state.review != null || state.diagnosticPickerPending) return
        diagnosticWindow.open()
        work { c -> val preview = local().diagnosticMetadataForOwner(c); { diagnosticWindow.replace(preview); diagnosticPreview = preview; state = state.copy(diagnosticText = preview.textForOwner(), diagnosticSha = preview.sha256(), diagnosticBytes = preview.size(), diagnosticReviewed = false, diagnosticExportEntered = false, notice = "已固定这次只读元数据。超时/失败域在快照中标明，不代表任务已停止、修复或通过设备验收。") } }
    }
    fun reviewDiagnostics(accepted: Boolean) {
        owner(); if (state.busy || state.diagnosticPickerPending) return
        val preview = diagnosticPreview ?: return
        diagnosticWindow.review(preview.sha256(), accepted); state = state.copy(diagnosticReviewed = accepted)
    }
    fun prepareDiagnosticExport(): NativeDiagnosticWindow.Ticket? {
        if (closed || !foreground || !privateOwner() || state.busy || !state.open || !state.diagnosticReviewed || state.diagnosticPickerPending || state.diagnosticExportEntered) return null
        return diagnosticWindow.prepare().also { diagnosticTicket = it; state = state.copy(diagnosticPickerPending = true) }
    }
    fun diagnosticPickerUnavailable(ticket: NativeDiagnosticWindow.Ticket) {
        if (closed || ticket !== diagnosticTicket) return
        diagnosticWindow.cancel(ticket); diagnosticTicket = null; diagnosticDestination = null
        state = state.copy(diagnosticPickerPending = false, error = "无法打开系统文件选择器，没有写入诊断正文。")
    }
    fun diagnosticExportResult(ticket: NativeDiagnosticWindow.Ticket?, uri: Uri?) {
        if (closed || ticket == null || ticket !== diagnosticTicket) return
        if (uri == null) { diagnosticWindow.cancel(ticket); diagnosticTicket = null; state = state.copy(diagnosticPickerPending = false, notice = "已取消选择，未写入诊断正文。") ; return }
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) { diagnosticWindow.cancel(ticket); diagnosticTicket = null; state = state.copy(diagnosticPickerPending = false, error = "系统未授予可用的文档引用，没有写入其他路径。") ; return }
        diagnosticDestination = uri; finishDiagnosticExport()
    }
    private fun finishDiagnosticExport() {
        if (closed || !foreground || !state.open || state.busy) return
        val ticket = diagnosticTicket ?: return; val uri = diagnosticDestination ?: return
        diagnosticTicket = null; diagnosticDestination = null
        if (!privateOwner()) { diagnosticWindow.cancel(ticket); state = state.copy(diagnosticPickerPending = false, error = "原私人页面已变化，没有写入诊断正文。所选提供方可能已创建空文件。") ; return }
        state = state.copy(diagnosticPickerPending = false)
        work { c ->
            val result = try { diagnosticWindow.write(ticket, { app.contentResolver.openOutputStream(uri, "wt") ?: error("diagnostic_destination_unavailable") }, c) }
            catch (failed: Exception) { throw IllegalStateException("诊断文件写入未完整确认，目标可能已有部分数据。请在所选提供方核对；没有自动重试。") }
            return@work { state = state.copy(diagnosticReviewed = false, diagnosticExportEntered = diagnosticPreview?.exportEntered() == true, notice = "已向所选文档提供方写入 ${result.getInt("bytes")} 字节的固定元数据。没有自动上传；提供方可能自行同步，持久保存尚未独立验证。") }
        }
    }
    fun refresh() = work { c ->
        val data = local().ownerStorageInventoryForHost(c)
        val caches = app.features.ownerDataArchives(app).localCachesForOwner(c).rows()
        return@work { state = state.copy(inventory = data, caches = caches, selected = emptySet()) }
    }
    fun select(id: String, yes: Boolean) {
        owner(); if (state.busy || state.review != null) return
        state = state.copy(selected = if (yes) state.selected + id else state.selected - id)
    }
    fun suggest() {
        owner(); if (state.busy || state.review != null) return
        val policy = state.inventory?.optJSONObject("retentionPolicy") ?: return
        var old = 0; val selection = linkedSetOf<String>()
        state.inventory?.optJSONArray("groups")?.rows()?.forEach { row ->
            val id = row.optString("id")
            if (row.optBoolean("cleanable") && row.optJSONObject("usage")?.optBoolean("complete") == true && row.optJSONObject("usage")!!.optLong("logicalBytes") > 0) {
                if (id.startsWith("runtime:generation:")) { if (old++ >= policy.optInt("keepOldRuntimeGenerations", 2) && selection.size <= 10) { selection.add(id); selection.add(id.replace("runtime:generation:", "runtime:links:")) } }
                else if (id == "runtime:downloads" && selection.size < 12) selection.add(id)
                else if (policy.optBoolean("suggestGeneratedCleanup") && id.startsWith("project:") && id.substringAfterLast(':') in setOf("build", "dist") && selection.size < 12) selection.add(id)
            }
        }
        state = state.copy(selected = selection, notice = "仅按保留规则建议勾选；没有删除。请核对实际目录和风险，再明确审阅确认。")
    }
    fun savePolicy(generations: Int, backups: Int, generated: Boolean) {
        val policy = state.inventory?.optJSONObject("retentionPolicy") ?: return
        work { _ -> val saved = local().saveOwnerStoragePolicyForHost(generations, backups, generated, policy.getString("revision")); { state = state.copy(inventory = state.inventory?.put("retentionPolicy", saved), notice = "已保存下次审阅建议规则；未启用定时或自动删除。") } }
    }
    fun reviewSelected() {
        owner(); if (state.busy || pending() || state.selected.isEmpty()) return
        clearReview(); val original = RunControl(); reviewControl = original; val ids = state.selected.toSet()
        work(onFailure = { original.cancel(); reviewControl = null }) { c -> c.check(); val exact = local().reviewOwnerStorageForHost(ids, original); val data = exact.metadata(); { review = exact; state = state.copy(review = data) } }
    }
    fun cancelReview() { clearReview(); state = state.copy(review = null) }
    fun confirm() {
        owner(); if (state.busy) return
        val selected = review?.confirm() ?: return
        val id = selected.actionId(); review = null; reviewControl = null
        state = state.copy(review = null, originalAction = id, original = null, result = null)
        work { _ -> val original = app.features.submitOwnerStorageCleanupForOwner(selected); { state = state.copy(original = original, notice = "原清理已登记。任何中断只核对原范围，不自动重复删除。") } }
    }
    fun poll() {
        val id = state.originalAction ?: return
        if (state.busy || !foreground || !state.open) return
        work { _ -> val original = app.features.ownerStorageCleanupForOwner(id); { state = state.copy(original = original); if (original.optString("status") == "completed") query() } }
    }
    fun query() {
        val id = state.originalAction ?: return
        work { c -> val result = app.features.queryOwnerStorageCleanupForOwner(id, c); val original = app.features.ownerStorageCleanupForOwner(id); { state = state.copy(original = original, result = result, notice = if (original.optString("status") == "completed") "已取得原清理结果。实际可用空间按文件系统重新计量，逻辑字节不是独占回收量。" else "原结果尚未完整确认。仅观察这些准确范围，不自动重放删除。") } }
    }
    fun stop() { val id = state.originalAction ?: return; work { _ -> val actual = app.features.cancelOwnerStorageCleanupForOwner(id); { state = state.copy(original = actual, notice = "已请求停止后续删除；已清理的缓存不会回滚，原项目和固定成果保留。") } } }
    fun readOriginal(id: String) { if (state.busy) return; work { _ -> val original = app.features.ownerStorageCleanupForOwner(id); { state = state.copy(originalAction = id, original = original); query() } } }
    fun backupCache(id: String) { owner(); if (!state.busy) { closePage(); openBackup(id) } }
    fun foreground(visible: Boolean) { foreground = visible; diagnosticWindow.foreground(visible); if (!visible) { cancelRead(); clearReview(); state = state.copy(busy = false, review = null) } else finishDiagnosticExport() }
    fun detach() { closed = true; cancelRead(); clearReview(); clearDiagnostic() }
    fun save(bundle: Bundle) { state.originalAction?.let { bundle.putString("projectOwnerStorageAction", it) } }
    fun restore(bundle: Bundle?) { state = state.copy(originalAction = bundle?.getString("projectOwnerStorageAction"), notice = bundle?.getString("projectOwnerStorageAction")?.let { "只恢复了原清理编号；没有恢复删除确认。请只读核对原结果。" }) }
    private fun clearReview() { review = null; reviewControl?.cancel(); reviewControl = null }
    private fun cancelRead() { generation++; control?.cancel(); control = null; future?.cancel(true); future = null }
    private fun work(onFailure: (() -> Unit)? = null, block: (RunControl) -> (() -> Unit)) {
        owner(); if (state.busy) return
        val epoch = ++generation; val c = RunControl(); control = c; state = state.copy(busy = true, error = null)
        future = io.submit { try { val accepted = block(c); c.check(); main.post { if (!closed && epoch == generation && foreground && privateOwner()) { control = null; future = null; state = state.copy(busy = false); accepted(); finishDiagnosticExport() } } } catch (failure: Exception) { main.post { if (!closed && epoch == generation) { onFailure?.invoke(); state = state.copy(busy = false, error = failure.message?.take(400) ?: "原清理状态待核对", diagnosticReviewed = false, diagnosticExportEntered = diagnosticPreview?.exportEntered() == true); control = null; future = null; finishDiagnosticExport() } } } }
    }
}
private fun JSONArray.rows(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
