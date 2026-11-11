package dev.pocketengine.ui.web

import dev.pocketengine.features.local.project.ProjectApkInstallActivity
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Bundled owner presentation only. It never creates a source, URI, install task or signing key. */
internal class PocketWebProjectApk(
    private val activity: ProjectApkInstallActivity,
    private val emit: (JSONObject) -> Unit
) : PocketWebEndpoint {
    override val surface = "project_apk"
    private val windowId = UUID.randomUUID().toString()
    private var generation = 0L
    private var document: String? = null
    private var foreground = false
    private var closed = false
    private var revision = 0L
    private val receipts = linkedMapOf<String, Pair<String, JSONObject>>()
    private val refresh = Runnable { publish() }
    private val commands = setOf("app.ready", "app.refresh", "projectApk.read", "projectApk.inspect", "projectApk.install",
        "projectApk.settings", "projectApk.reconcile", "projectApk.closeUnknown", "projectApk.close")

    init { activity.bindApkSurface(refresh) }
    override fun canInteract() = !closed && foreground && !activity.isFinishing && !activity.isDestroyed
    override fun start() = publish()
    override fun captureDispatch(raw: String, stale: () -> Unit): () -> Unit {
        val original = generation
        return { if (canInteract() && generation == original) dispatch(raw) else stale() }
    }
    private fun projection() = activity.apkSurfaceSnapshot().put("windowId", windowId).put("generation", generation)
        .put("ready", canInteract() && document != null && activity.apkSurfaceAvailable())
    private fun publish() {
        if (closed) return
        val data = runCatching { projection() }.getOrElse { JSONObject().put("ready", false).put("message", "原 APK 状态暂不可读，请回原成果核对；没有重投。") }
        emit(JSONObject().put("v", 1).put("kind", "state").put("revision", ++revision).put("snapshot", JSONObject()
            .put("surface", surface).put("state", JSONObject()).put("composer", JSONObject.NULL)
            .put("projectApk", data).put("host", JSONObject().put("surface", surface))
            .put("capabilities", JSONObject().put("commands", JSONArray(commands)))))
    }
    private fun dispatch(raw: String) {
        if (raw.length > 65536) return
        val input = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = input.opt("id") as? String ?: return
        val suppliedDocument = input.opt("documentId") as? String ?: return
        if (input.optInt("v") != 1 || id.isBlank() || id.length > 160 || suppliedDocument.isBlank() || suppliedDocument.length > 160) return
        fun fail(message: String) = emit(result(id, false, JSONObject().put("code", "not_sent").put("message", message)))
        if (!canInteract()) { fail("原 APK 页面已离开前台，请返回后重新核对。"); return }
        val action = input.optString("action")
        val args = input.optJSONObject("args")
        if (args == null || action !in commands) { fail("当前 APK 主人窗口不支持此请求。"); return }
        if (action == "app.ready") {
            if (args.length() != 1 || args.opt("documentId") != suppliedDocument || document != null && document != suppliedDocument) {
                fail("原 APK 文档已变化，请重新打开。"); return
            }
            document = suppliedDocument
            publish();emit(result(id, true, JSONObject().put("accepted", true).put("surface", surface)))
            // Initial read only. Rebinding can never trigger installation or reopen a system flow.
            val data = runCatching { projection() }.getOrNull()
            if (data?.optBoolean("sourcePresent") == true && data.isNull("review") && !data.optBoolean("pendingHandoff") && !data.optBoolean("busy")) runCatching { activity.inspectApk() }
            return
        }
        if (document != suppliedDocument) { fail("原 APK 页面尚未绑定，请重新读取。"); return }
        val signature = "$action\u0000$args"
        receipts[id]?.let { old -> if (old.first == signature) emit(old.second) else fail("原请求编号不能改为另一项操作。"); return }
        if (receipts.size >= 1024) { fail("请关闭后从原成果重新打开，再核对当前状态。"); return }
        val reading = action in setOf("app.refresh", "projectApk.read")
        val extra = when (action) {
            "projectApk.install" -> setOf("reviewId", "confirmed")
            "projectApk.reconcile" -> setOf("requestId")
            "projectApk.closeUnknown" -> setOf("requestId", "confirmed")
            "projectApk.settings" -> setOf("confirmed")
            else -> emptySet()
        }
        val allowed = if (reading) emptySet() else setOf("windowId", "generation") + extra
        if (args.length() != allowed.size || !args.keys().asSequence().all { it in allowed }) { fail("原 APK 操作字段不适用。"); return }
        if (!reading && (args.optString("windowId") != windowId || args.opt("generation").let {
                (it !is Int && it !is Long) || (it as Number).toLong() != generation
            } || !activity.apkSurfaceAvailable())) { fail("原 APK 主人窗口已变化，请重新读取。"); return }
        var entered = false
        val outcome = runCatching {
            if ("confirmed" in extra) require(args.opt("confirmed") == true) { "请先核对并明确确认本次操作。" }
            val value = when (action) {
                "app.refresh", "projectApk.read" -> { activity.readApkState(); projection() }
                "projectApk.inspect" -> { entered = true; activity.inspectApk(); JSONObject().put("status", "reading") }
                "projectApk.install" -> { entered = true; activity.requestApkInstall(args.getString("reviewId")); JSONObject().put("status", "preparing_original_handoff").put("installationConfirmed", false) }
                "projectApk.settings" -> { entered = true; activity.openApkSettings(); JSONObject().put("status", "system_settings_requested").put("installationConfirmed", false) }
                "projectApk.reconcile" -> { entered = true; activity.reconcileApk(args.getString("requestId")); JSONObject().put("status", "observing_original_only") }
                "projectApk.closeUnknown" -> { entered = true; activity.closeUnknownApkWait(args.getString("requestId")); JSONObject().put("status", "original_wait_closed_not_install_cancelled") }
                "projectApk.close" -> { entered = true; activity.finish(); JSONObject().put("closed", true) }
                else -> error("当前 APK 页面不支持此请求。")
            }
            value
        }
        val receipt = outcome.fold({ result(id, true, it) }, { error -> result(id, false, JSONObject()
            .put("code", if (entered) "outcome_unknown" else "not_sent")
            .put("message", if (entered) "操作回执未确认，请读取原状态后核对；不会自动重试。" else error.message ?: "原 APK 操作未被接受。")) })
        receipts[id] = signature to receipt
        emit(receipt);publish()
    }
    private fun result(id: String, ok: Boolean, value: JSONObject) = JSONObject().put("v", 1).put("kind", "result")
        .put("id", id).put("ok", ok).put(if (ok) "value" else "error", value)
    override fun foreground(value: Boolean) { foreground = value;if (value) publish() }
    override fun invalidatePresentation(documentReplaced: Boolean) {
        generation++;document = null;receipts.clear();activity.retireApkPresentation();publish()
    }
    override fun restorePresentationState(saved: JSONObject?) = Unit
    override fun savePresentationState() = JSONObject()
    override fun close() { closed = true;foreground = false;generation++;document = null;receipts.clear();activity.retireApkPresentation();activity.unbindApkSurface(refresh) }
}
