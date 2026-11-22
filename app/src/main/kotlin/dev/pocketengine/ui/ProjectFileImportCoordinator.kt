package dev.pocketengine.ui

import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.local.LocalModule
import dev.pocketengine.features.local.project.ProjectDocumentImport
import dev.pocketengine.features.local.project.OwnerSuppliedViteLock
import dev.pocketengine.features.local.project.android.AndroidProjectDocument
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

internal data class ProjectFileImportState(val checkout: String? = null, val open: Boolean = false, val busy: Boolean = false, val picking: Boolean = false, val source: JSONObject? = null, val suggestedPath: String = "", val review: JSONObject? = null, val receipt: JSONObject? = null, val notice: String? = null)

/** Native owner picker -> actual bytes -> one exact manual CAS. No model/URI JSON/task/grant. */
internal class ProjectFileImportCoordinator(private val activity: ComposeActivity, private val app: EngineApplication, private val io: ExecutorService, private val main: Handler, private val privateOwner: () -> Boolean, private val changed: () -> Unit) {
    var state by mutableStateOf(ProjectFileImportState()); private set
    private val generation = AtomicLong()
    @Volatile private var mounted = false
    @Volatile private var foreground = false
    @Volatile private var closed = false
    private var control: RunControl? = null
    private var retainedOwnerGate: AutoCloseable? = null
    private var source: ProjectDocumentImport.Source? = null
    private var reviewed: ProjectDocumentImport.Review? = null
    private var future: Future<*>? = null
    private var ticket: String? = null
    private var returnedUri: Uri? = null

    // Separate from the dormant generic import. Only the existing Android nonce transport is shared.
    private class SuppliedLock(
        val origin: OwnerSuppliedViteLock.Origin, val visit: String, val nonce: String,
        val route: () -> Boolean, val control: RunControl, val until: Long
    ) {
        var leftForPicker = false
        var uri: Uri? = null
        var review: OwnerSuppliedViteLock.Review? = null
        var future: Future<*>? = null
        var phase = "picking"
    }
    @Volatile private var suppliedLock: SuppliedLock? = null
    private var suppliedLockPresentation by mutableStateOf<JSONObject?>(null)
    private fun lockRoute(value: SuppliedLock) = !closed && suppliedLock === value && value.route() &&
        !value.control.isCancelled && SystemClock.elapsedRealtime() < value.until
    private fun lockCurrent(value: SuppliedLock) = foreground && lockRoute(value) && privateOwner()
    private fun showLock(value: SuppliedLock, phase: String, details: JSONObject? = null) {
        value.phase = phase
        suppliedLockPresentation = (details ?: JSONObject())
            .put("state", phase).put("visit", value.visit)
            .put("projectId", value.origin.project()).put("checkoutId", value.origin.checkout())
            .put("sourceDigest", value.origin.sourceDigest())
    }
    fun suppliedLockSnapshot(): JSONObject? {
        val seen = suppliedLockPresentation
        return seen?.takeIf { suppliedLock?.let(::lockCurrent) == true }
    }
    fun retireSuppliedLock() {
        val value = suppliedLock ?: return
        suppliedLock = null; suppliedLockPresentation = null
        activity.cancelProjectWorkingFile(value.nonce);value.uri = null
        value.control.cancel();value.future?.cancel(true);value.review?.close();value.review = null
    }
    fun closeSuppliedLockReview(project: String) {
        val value = suppliedLock ?: return
        // The OS picker intentionally retires its old Web Page; it only kept the scoped selection.
        if (value.origin.project() == project && value.phase !in setOf("picking","writing","unknown")) retireSuppliedLock()
    }
    fun chooseSuppliedLock(origin: OwnerSuppliedViteLock.Origin, visit: String, route: () -> Boolean): JSONObject {
        check(!closed && foreground && privateOwner() && route() && ticket == null && !state.busy && !state.open) {
            "请回到原副本核对，不打开其他文件选择"
        }
        suppliedLock?.let { old ->
            check(!lockRoute(old) || old.phase in setOf("selected","review","cancelled","unavailable","not_written","saved")) {
                "已有这次锁文件选择，请先核对或返回"
            }
            retireSuppliedLock()
        }
        val value = SuppliedLock(origin, visit, UUID.randomUUID().toString(), route, RunControl(), SystemClock.elapsedRealtime()+120_000)
        suppliedLock = value;showLock(value,"picking")
        main.postDelayed({ if (suppliedLock === value) retireSuppliedLock() },120_000)
        try { activity.pickProjectWorkingFile(value.nonce) }
        catch (_: RuntimeException) { showLock(value,"unavailable") }
        return JSONObject().put("selectionRequested", value.phase == "picking").put("visit", visit)
    }
    private fun scopedLock(project: String, checkout: String, visit: String): SuppliedLock {
        val value = suppliedLock
        check(value != null && value.visit == visit && value.origin.project() == project &&
            value.origin.checkout() == checkout && lockCurrent(value)) { "原锁文件或所属副本已变化" }
        return value
    }
    fun dismissSuppliedLock(project: String, checkout: String, visit: String): JSONObject {
        val value = scopedLock(project, checkout, visit)
        check(value.phase !in setOf("writing","unknown")) { "只核对原写入结果，不再次提交" }
        retireSuppliedLock()
        return JSONObject().put("dismissed",true)
    }
    fun reviewSuppliedLock(project: String, checkout: String, visit: String, exactPage: () -> Boolean,
                          done: (JSONObject?, String?) -> Unit) {
        val value = scopedLock(project,checkout,visit)
        val uri = value.uri
        check(value.phase == "selected" && uri != null && exactPage()) { "请在同一原副本核对所选文件" }
        value.uri = null;showLock(value,"reading")
        fun current() = lockCurrent(value) && exactPage()
        try { value.future = io.submit {
            val result = runCatching {
                val authority=value.control.retainAuthorityCheck { check(current()) { "原锁文件核对已离开" } }
                try {
                    local().requireSuppliedViteLockForOwner(value.origin, ::current)
                    ProjectDocumentImport.readSuppliedViteLock(AndroidProjectDocument(app,uri,value.control),value.control).use { actual ->
                        local().reviewSuppliedViteLockForOwner(value.origin,actual,value.control,::current)
                    }
                } finally { authority.close() }
            }
            main.post {
                if (!current()) {
                    result.getOrNull()?.close();done(null,"原候选已离开，请重新选择；未写入");return@post
                }
                result.fold({ reviewed ->
                    value.review = reviewed;showLock(value,"review",reviewed.view());done(suppliedLockSnapshot(),null)
                }, {
                    showLock(value,"unavailable");done(null,"没有核对到此副本匹配、完整的 package-lock.json；未写入")
                })
            }
        } } catch (failure: java.util.concurrent.RejectedExecutionException) {
            showLock(value,"unavailable");throw failure
        }
    }
    fun confirmSuppliedLock(project: String, checkout: String, visit: String, exactPage: () -> Boolean,
                           done: (JSONObject?, String?) -> Unit) {
        val value = scopedLock(project,checkout,visit)
        val reviewed = value.review
        check(value.phase == "review" && reviewed != null && exactPage()) { "请先核对这份原锁文件" }
        value.review = null;showLock(value,"writing")
        fun current() = lockCurrent(value) && exactPage()
        try { value.future = io.submit {
            val result=runCatching { local().confirmSuppliedViteLockForOwner(reviewed,value.control,::current) }
            reviewed.close()
            main.post {
                if(!current()){done(null,"原页面已离开，请只核对原副本，不重复写入");return@post}
                result.fold({ actual ->
                    showLock(value,actual.getString("state"),actual);done(suppliedLockSnapshot(),null)
                }, { showLock(value,"unavailable");done(null,"原源码或锁文件已变化，没有进入写入") })
            }
        } } catch (failure: java.util.concurrent.RejectedExecutionException) {
            reviewed.close();showLock(value,"unavailable");throw failure
        }
    }
    private fun local() = app.features.module("local") as? LocalModule ?: error("原项目模块不可用")
    private fun owner() { check(!closed && mounted && foreground && privateOwner()) { "请返回原私人工作副本入口操作" } }
    private fun ready(): Boolean = try { owner(); true } catch (_: Exception) { state = state.copy(notice = "请返回原私人工作副本入口；当前选择未继续读取或导入。"); false }
    fun activate(checkout: String) { if (closed) return; mounted = true; if (state.checkout != checkout) { clear(); state = ProjectFileImportState(checkout = checkout) } }
    fun deactivate() { mounted = false; retireSuppliedLock(); clear(); state = state.copy(open = false, busy = false, picking = false, source = null, review = null) }
    fun foreground(active: Boolean) {
        foreground = active
        if (!active) suppliedLock?.let {
            if (it.phase == "picking" && !it.leftForPicker && lockRoute(it)) it.leftForPicker = true
            else retireSuppliedLock()
        }
        if (!active && ticket == null) { clear(); state = state.copy(busy = false, source = null, review = null, notice = if (state.open) "已离开前台；没有恢复确认，请重新选择文件。若导入已开始，请返回文件树核对。" else state.notice) }
        if (active) {
            processReturned()
            suppliedLockPresentation?.let { suppliedLockPresentation = JSONObject(it.toString()) }
        }
    }
    fun open() { if (!ready()) return; state = state.copy(open = true, notice = null, receipt = null) }
    fun dismiss() { clear(); state = state.copy(open = false, busy = false, picking = false, source = null, review = null) }
    private fun clear() { ticket?.let(activity::cancelProjectWorkingFile);generation.incrementAndGet();control?.cancel();runCatching { retainedOwnerGate?.close() };retainedOwnerGate = null; control = null; future?.cancel(true); future = null; source?.close(); source = null; reviewed = null; ticket = null; returnedUri = null }
    fun choose() { if (!ready()) return; if (state.busy || state.picking) return; clear(); val original = RunControl(); val checkout = state.checkout ?: return; retainedOwnerGate = original.retainAuthorityCheck { check(!closed && mounted && foreground && privateOwner() && state.checkout == checkout) { "原主人文件导入页面或副本已变化" } }; control = original; val nonce = UUID.randomUUID().toString(); ticket = nonce; state = state.copy(source = null, review = null, receipt = null, notice = null, picking = true); try { activity.pickProjectWorkingFile(nonce) } catch (_: RuntimeException) { result(nonce, null) } }
    fun result(nonce: String, uri: Uri?) {
        suppliedLock?.takeIf { it.nonce == nonce }?.let { original ->
            if (original.phase != "picking" || !lockRoute(original)) { retireSuppliedLock();return }
            original.uri = uri;showLock(original, if (uri == null) "cancelled" else "selected")
            return // No body read until a fresh same-project Web review.
        }
        if (closed || ticket != nonce) return
        if (uri == null) { ticket = null; control?.cancel(); control = null; state = state.copy(picking = false, notice = "已取消选择；没有读取或导入文件。"); return }
        returnedUri = uri;processReturned()
    }
    private fun processReturned() {
        val uri = returnedUri ?: return; val nonce = ticket ?: return
        if (!foreground || !mounted || closed) return
        try { owner() } catch (_: Exception) { clear(); state = state.copy(picking = false); return }
        val original = control ?: return; val checkout = state.checkout ?: return; val stamp = generation.get(); returnedUri = null; ticket = null; state = state.copy(picking = false, busy = true, notice = "正在读取所选文件的有界原字节…")
        future = io.submit {
            var actual: ProjectDocumentImport.Source? = null
            try { actual = ProjectDocumentImport.read(AndroidProjectDocument(app, uri, original), original); val view = actual.view(); val suggested = actual.suggestedPath(); val retained = actual
                main.post { if (!current(stamp, checkout, original)) retained.close() else { source = retained; state = state.copy(busy = false, source = view, suggestedPath = suggested, review = null, notice = "只读取了这份文件；尚未写入工作副本。") } }
            } catch (_: Exception) { actual?.close(); main.post { if (current(stamp, checkout, original)) state = state.copy(busy = false, notice = "所选文件未能完整读取（可能授权变化、虚拟/未下载项、取消或超过2MiB）。未导入部分内容；请明确重新选择。") } }
        }
    }
    private fun current(stamp: Long, checkout: String, original: RunControl) = !closed && mounted && foreground && privateOwner() && generation.get() == stamp && state.checkout == checkout && control === original && !original.isCancelled
    fun review(path: String) {
        if (!ready()) return; if (state.busy || state.picking) return; val actual = source ?: return; val original = control ?: return; val checkout = state.checkout ?: return; val stamp = generation.get(); reviewed = null; state = state.copy(busy = true, review = null, notice = null)
        future = io.submit { try { val issued = local().prepareDocumentImportForOwner(checkout, path, actual, original); val view = issued.view(); main.post { if (current(stamp, checkout, original) && source === actual) { reviewed = issued; state = state.copy(busy = false, review = view, notice = "已核对现有目标、父目录与旧SHA；确认只写这个工作副本。") } } } catch (_: Exception) { main.post { if (current(stamp, checkout, original)) state = state.copy(busy = false, review = null, notice = "目标预检未通过：请检查相对路径、已有父目录、原source范围/私有配置排除、大小及运行租约。没有写入。") } } }
    }
    fun targetChanged() { if (!state.busy) { reviewed = null; state = state.copy(review = null) } }
    fun confirm() {
        if (!ready()) return; if (state.busy) return; val exact = reviewed ?: return; val original = control ?: return; val checkout = state.checkout ?: return; val actual = source ?: return; val stamp = generation.get(); val digest = state.review?.optString("reviewDigest")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return; reviewed = null; state = state.copy(busy = true, notice = null)
        future = io.submit { try { val receipt = local().importDocumentForOwner(exact, digest, original); main.post { if (!closed) { changed(); if (state.checkout == checkout && generation.get() == stamp) state = state.copy(busy = false, source = null, review = null, receipt = receipt, notice = receipt.optString("message")) } } } catch (_: Exception) { main.post { if (!closed && state.checkout == checkout && generation.get() == stamp) state = state.copy(busy = false, source = null, review = null, notice = "原确认已失效或无法核对；没有重新派发。请返回文件树核对目标，再明确重新选择。") } } finally { actual.close(); main.post { if (source === actual) source = null } } }
    }
    fun detach() { closed = true; mounted = false; retireSuppliedLock(); clear() }
}
