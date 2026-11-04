package dev.pocketengine.ui.web

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.attachments.AttachmentViewSnapshots
import dev.pocketengine.runtime.routes.NativePhoneRouteReferenceHost
import dev.pocketengine.runtime.routes.PortablePhoneRouteReference
import dev.pocketengine.ui.PocketWorldController
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.json.JSONObject

/** Existing owner source gate, SAF registry and expiring AttachmentProvider. The only retained
 * state is this UI window/picker; imported references never enter a task, route book or Runner. */
internal class PocketWebRouteReferences(private val controller: PocketWorldController) {
    private class Window(val id: String, val owner: Any, val target: JSONObject, val control: RunControl) {
        @Volatile var review: NativePhoneRouteReferenceHost.Review? = null
    }
    private class Picker(val id: String, val kind: String, val window: Window) {
        var launcher: ActivityResultLauncher<*>? = null
        var returned = false
        var uri: Uri? = null
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var window: Window? = null
    private var picker: Picker? = null
    private var busy = false
    private var state by mutableStateOf(JSONObject().put("open", false))

    fun snapshot(): JSONObject = JSONObject(state.toString())
    fun clear() {
        val old = window; window = null; old?.control?.cancel(); old?.review?.close()
        picker?.launcher?.unregister(); picker = null; busy = false
        state = JSONObject().put("open", false)
    }
    /** A system picker may return while paused. Keep only its exact original window; the return
     * stores a URI without IO. A foreground owner action must inspect or write it afterward. */
    fun background() { if (picker == null || busy) clear() }
    private fun current(original: Window): Boolean = window === original && controller.webPrivateOwnerVisible() && controller.webWindowTicket() == original.owner && !original.control.isCancelled
    private fun show(vararg values: Pair<String, Any?>) { state = JSONObject(state.toString()).also { next -> values.forEach { (key, value) -> next.put(key, value ?: JSONObject.NULL) } } }
    private fun fields(input: JSONObject, vararg names: String) { require(input.length() == names.size && input.keys().asSequence().all { it in names }) { "路线参考只接受当前原窗口的选择" } }
    private fun text(input: JSONObject, name: String, max: Int = 128): String { val v = input.get(name); require(v is String && v.isNotBlank() && v.length <= max && '\u0000' !in v); return v }
    private fun original(input: JSONObject): Window = window?.takeIf { it.id == text(input, "handle") && current(it) } ?: error("原路线参考窗口已变化，请重新打开")
    private fun notBusy() { check(!busy && picker == null) { "请先完成或放弃本次原文件选择" } }

    fun dispatch(action: String, input: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到本机私人任务页面" }
        if (action == "routeReference.close") { fields(input, "handle"); if (window?.id == text(input, "handle")) clear(); done(snapshot(), null); return }
        if (action == "routeReference.open") {
            fields(input, "taskId", "runId", "epoch", "namespace"); require(text(input, "namespace") == "owner/private")
            val epoch = input.get("epoch"); require(epoch is Int && epoch > 0)
            val target = JSONObject().put("taskId", text(input, "taskId")).put("runId", text(input, "runId")).put("epoch", epoch).put("namespace", "owner/private")
            clear(); val original = Window(UUID.randomUUID().toString(), controller.webWindowTicket(), target, RunControl()); window = original
            original.control.setAuthorityCheck { check(current(original)) { "原路线参考窗口已经关闭" } }
            state = JSONObject().put("open", true).put("handle", original.id).put("target", target).put("available", false).put("executionAuthorized", false)
            work(original, done, {
                try {
                    val acquired = controller.phoneRouteReferenceHost().inspect(target.getString("taskId"), target.getString("runId"), epoch, original.control)
                    original.review = acquired
                    JSONObject().put("review", acquired.view()).put("available", true)
                } catch (absent: NativePhoneRouteReferenceHost.NoReference) { JSONObject().put("available", false).put("notice", absent.message) }
            }) { result -> show("review" to result.optJSONObject("review"), "available" to result.optBoolean("available"), "checked" to true, "notice" to result.opt("notice")) }
            return
        }
        val original = original(input)
        when (action) {
            "routeReference.chooseImport" -> { fields(input, "handle"); notBusy(); choose(original, "import"); done(snapshot(), null) }
            "routeReference.chooseExport" -> {
                fields(input, "handle", "confirmed"); require(input.opt("confirmed") == true); notBusy(); check(original.review != null) { "没有可导出的原路线" }
                original.review!!.view(); choose(original, "export"); done(snapshot(), null)
            }
            "routeReference.discardSelection" -> {
                fields(input, "handle", "requestId"); check(!busy); val selected = selected(original, input, false)
                selected.launcher?.unregister(); picker = null; show("pickerPending" to false, "selectedRequestId" to null, "selectionKind" to null, "notice" to "已放弃原文件选择，没有读取或写入。"); done(snapshot(), null)
            }
            "routeReference.inspectSelected", "routeReference.writeSelected" -> {
                fields(input, "handle", "requestId"); check(!busy); val selected = selected(original, input, true); val reading = action == "routeReference.inspectSelected"
                check(selected.kind == if (reading) "import" else "export"); val uri = checkNotNull(selected.uri); requireContent(uri)
                val activity = controller.routeReferenceActivity(); picker = null; show("selectedRequestId" to null, "selectionKind" to null)
                work(original, done, {
                    if (reading) activity.contentResolver.openInputStream(uri)?.use { PortablePhoneRouteReference.presentation(PortablePhoneRouteReference.read(it, original.control)) } ?: error("无法读取所选路线参考")
                    else try {
                        activity.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            val receipt = checkNotNull(original.review).export(output, original.control); output.flush(); receipt
                        } ?: error("无法写入所选保存位置")
                    } catch (failure: Exception) { throw IllegalStateException((failure.message ?: "原保存结果未确认") + "。保存目标可能为空或不完整，请在文件应用中核对。", failure) }
                }) { result ->
                    if (reading) {
                        val matches = original.review?.view()?.optString("digest") == result.optString("digest")
                        show("incoming" to result.put("matchesCurrentReference", matches), "notice" to "只已校验和预览这份文件；没有登记为路线、安装技能或执行。")
                    } else show("receipt" to result.put("saved", true), "notice" to "原路线参考文件已保存；未转移审批或执行权限。")
                }
            }
            "routeReference.share" -> { fields(input, "handle", "confirmed"); require(input.opt("confirmed") == true); notBusy(); share(original, done) }
            else -> error("未知路线参考操作")
        }
    }

    private fun selected(original: Window, input: JSONObject, requireReturned: Boolean): Picker {
        val selected = picker?.takeIf { it.window === original && it.id == text(input, "requestId") } ?: error("原文件选择已关闭或变化")
        check(!requireReturned || selected.returned && selected.uri != null) { "请等待原系统文件选择结果" }; return selected
    }
    private fun choose(original: Window, kind: String) {
        val activity = controller.routeReferenceActivity(); val selected = Picker(UUID.randomUUID().toString(), kind, original); picker = selected
        show("pickerPending" to true, "selectionKind" to kind, "selectedRequestId" to selected.id, "notice" to "请在系统文件窗口选择；返回后仍需明确检查或保存。", "error" to null)
        val callback: (Uri?) -> Unit = { uri ->
            selected.returned = true; selected.launcher?.unregister(); selected.launcher = null
            if (picker === selected && window === original && controller.webWindowTicket() == original.owner) {
                selected.uri = uri
                if (uri == null) { picker = null; show("pickerPending" to false, "selectedRequestId" to null, "selectionKind" to null, "notice" to "已取消系统文件选择。") }
                else show("pickerPending" to false, "notice" to if (kind == "import") "文件已选定，点击检查后才读取内容。" else "保存位置已选定，点击保存后才写入所审阅内容。")
            }
        }
        try {
            if (kind == "import") {
                val launcher = activity.activityResultRegistry.register("route_reference_${selected.id}", ActivityResultContracts.OpenDocument()) { uri -> callback(uri) }
                selected.launcher = launcher
                if (selected.returned) launcher.unregister() else launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            } else {
                val launcher = activity.activityResultRegistry.register("route_reference_${selected.id}", ActivityResultContracts.CreateDocument("application/json")) { uri -> callback(uri) }
                selected.launcher = launcher
                if (selected.returned) launcher.unregister() else launcher.launch("pocketworld-route-reference.json")
            }
        } catch (failure: Exception) { selected.launcher?.unregister(); if (picker === selected) picker = null; show("pickerPending" to false, "selectedRequestId" to null, "selectionKind" to null); throw failure }
    }
    private fun work(original: Window, done: (JSONObject?, String?) -> Unit, operation: () -> JSONObject, accept: (JSONObject) -> Unit) {
        busy = true; show("busy" to true, "error" to null)
        try { controller.runPluginCatalogWork(Runnable {
            val result = runCatching { original.control.check(); operation().also { original.control.check() } }
            main.post {
                if (!current(original)) { original.review?.close(); done(null, "原路线参考窗口已变化；文件处理结果未确认，没有自动重试"); return@post }
                busy = false; show("busy" to false)
                result.fold({ value ->
                    try { accept(value); done(snapshot(), null) } catch (failure: Exception) { show("error" to failure.message); done(null, failure.message) }
                }, { failure -> show("error" to (failure.message ?: "文件处理结果未确认；保存目标可能不完整，请先核对。")); done(null, failure.message) })
            }
        }) } catch (failure: Exception) { busy = false; show("busy" to false, "error" to failure.message); done(null, failure.message) }
    }
    private fun share(original: Window, done: (JSONObject?, String?) -> Unit) {
        val review = original.review ?: error("没有可分享的原路线参考"); val activity = controller.routeReferenceActivity(); val snapshots = AttachmentViewSnapshots(activity)
        busy = true; show("busy" to true, "error" to null)
        try { controller.runPluginCatalogWork(Runnable {
            var prepared: Intent? = null
            val result = runCatching {
                original.control.check(); val output = ByteArrayOutputStream(); val receipt = output.use { review.export(it, original.control) }; val exact = output.toByteArray()
                try { prepared = snapshots.prepare("pocketworld-route-reference.json", "application/json", receipt.getString("sha256"), exact, original.control) } finally { exact.fill(0) }
                receipt
            }
            main.post {
                val actual = prepared
                if (!current(original)) { actual?.data?.let { uri -> cleanup(snapshots, uri) }; done(null, "原路线分享窗口已关闭，未打开分享面板"); return@post }
                busy = false; show("busy" to false)
                result.fold({ receipt ->
                    try {
                        val copy = checkNotNull(actual); val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, copy.data).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).also { it.clipData = copy.clipData }
                        activity.startActivity(Intent.createChooser(send, "分享路线参考"))
                        show("receipt" to receipt.put("shareSheetOpened", true).put("deliveryConfirmed", false), "notice" to "系统分享面板已打开；是否发送由你选择，发送结果尚未确认。"); done(snapshot(), null)
                    } catch (failure: Exception) { actual?.data?.let { uri -> cleanup(snapshots, uri) }; show("error" to failure.message); done(null, failure.message) }
                }, { failure -> actual?.data?.let { uri -> cleanup(snapshots, uri) }; show("error" to failure.message); done(null, failure.message) })
            }
        }) } catch (failure: Exception) { busy = false; show("busy" to false, "error" to failure.message); done(null, failure.message) }
    }
    private fun cleanup(snapshots: AttachmentViewSnapshots, uri: Uri) { runCatching { controller.runPluginCatalogWork(Runnable { runCatching { snapshots.discardUnsent(uri) } }) } }
    private fun requireContent(uri: Uri) { require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && !uri.authority.orEmpty().contains('@') && uri.fragment == null) { "只能读取本次系统文件选择的内容" } }

    companion object { val COMMANDS = setOf("routeReference.open", "routeReference.close", "routeReference.chooseImport", "routeReference.chooseExport", "routeReference.discardSelection", "routeReference.inspectSelected", "routeReference.writeSelected", "routeReference.share") }
}
