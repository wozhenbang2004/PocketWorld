package dev.pocketengine.ui.web

import dev.pocketengine.features.updates.AppUpdateActivity
import dev.pocketengine.features.updates.AppUpdateManager
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** The bundled update page owns no updater authority beyond its original foreground Activity. */
internal class PocketWebAppUpdates(
    private val activity: AppUpdateActivity,
    private val manager: AppUpdateManager,
    private val emit: (JSONObject) -> Unit
) : PocketWebEndpoint {
    override val surface = "app_updates"
    private val windowId = UUID.randomUUID().toString()
    private var generation = 0L
    private var document: String? = null
    private var foreground = false
    private var closed = false
    private var revision = 0L
    private var previous = ""
    private val receipts = linkedMapOf<String, Pair<String, JSONObject>>()
    private val refresh = Runnable { publish() }
    private val commands = setOf("app.ready", "app.refresh", "updates.read", "updates.recover", "updates.check",
        "updates.automatic", "updates.download", "updates.cancel", "updates.install", "updates.notifications", "updates.close")

    init { activity.bindUpdateSurface(refresh) }

    override fun canInteract() = !closed && foreground && !activity.isFinishing && !activity.isDestroyed
    override fun start() = publish(force = true)
    override fun captureDispatch(raw: String, stale: () -> Unit): () -> Unit {
        val original = generation
        return { if (canInteract() && generation == original) dispatch(raw) else stale() }
    }

    private fun projection(): JSONObject {
        val release = manager.release()
        return JSONObject().put("windowId", windowId).put("generation", generation)
            .put("ready", canInteract() && document != null && activity.updateSurfaceAvailable())
            .put("configured", manager.configured()).put("installedVersion", manager.installedVersion())
            .put("phase", manager.phase()).put("message", manager.message()).put("delivery", manager.delivery())
            .put("busy", manager.busy()).put("packageReady", manager.ready()).put("automatic", manager.automatic())
            .put("downloadedBytes", manager.downloaded()).put("totalBytes", manager.total())
            .put("notificationPermissionRequired", activity.updateNotificationsPermissionRequired())
            .put("awaitingSystem", activity.awaitingUpdateSystemHandoff())
            .put("release", if (release == null) JSONObject.NULL else JSONObject()
                .put("versionName", release.versionName).put("versionCode", release.versionCode)
                .put("notes", release.notes).put("bytes", release.apk.bytes))
    }

    private fun publish(force: Boolean = false) {
        if (closed) return
        val updates = projection()
        val encoded = updates.toString()
        if (!force && encoded == previous) return
        previous = encoded
        emit(JSONObject().put("v", 1).put("kind", "state").put("revision", ++revision).put("snapshot", JSONObject()
            .put("surface", surface).put("state", JSONObject()).put("composer", JSONObject.NULL)
            .put("updates", updates).put("host", JSONObject().put("surface", surface))
            .put("capabilities", JSONObject().put("commands", JSONArray(commands)))))
    }

    private fun dispatch(raw: String) {
        if (raw.length > 65536) return
        val input = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val id = input.opt("id") as? String ?: return
        val suppliedDocument = input.opt("documentId") as? String ?: return
        if (input.optInt("v") != 1 || id.isBlank() || id.length > 160 || suppliedDocument.isBlank() || suppliedDocument.length > 160) return
        fun fail(message: String) = emit(result(id, false, JSONObject().put("code", "not_sent").put("message", message)))
        if (!canInteract()) { fail("更新页面已不在前台，请返回后重新核对。"); return }
        val action = input.optString("action")
        val args = input.optJSONObject("args")
        if (args == null) { fail("更新操作参数未能读取，请重新打开页面。"); return }
        if (action !in commands) { fail("当前更新窗口不支持这项操作。"); return }
        if (action == "app.ready") {
            if (args.length() != 1 || args.opt("documentId") != suppliedDocument ||
                document != null && document != suppliedDocument) {
                fail("原更新文档已变化，请重新打开。"); return
            }
            document = suppliedDocument
            publish(force = true)
            emit(result(id, true, JSONObject().put("accepted", true).put("surface", surface)))
            return
        }
        if (document != suppliedDocument) { fail("更新页面尚未完成绑定，请重新读取。"); return }
        val signature = "$action\u0000$args"
        receipts[id]?.let { old ->
            if (old.first == signature) emit(old.second) else fail("原请求编号不能用于另一项更新操作。")
            return
        }
        if (receipts.size >= 1024) { fail("请关闭后重新打开更新页面，再读取当前状态。"); return }
        val reading = action in setOf("app.refresh", "updates.read")
        val allowed = if (reading) emptySet() else if (action == "updates.automatic") setOf("windowId", "generation", "enabled") else setOf("windowId", "generation")
        if (!args.keys().asSequence().all { it in allowed } || reading && args.length() != 0) {
            fail("更新操作含不适用的字段。"); return
        }
        if (!reading && (args.optString("windowId") != windowId ||
                args.opt("generation").let { (it !is Int && it !is Long) || (it as Number).toLong() != generation } ||
                !activity.updateSurfaceAvailable())) {
            fail("原更新操作窗口已变化，请重新读取当前状态。"); return
        }

        var entered = false
        val outcome = runCatching {
            if (!reading && action !in setOf("updates.close", "updates.recover")) check(manager.configured()) { "私有更新服务尚未配置。" }
            when (action) {
                "app.refresh", "updates.read" -> projection()
                "updates.recover" -> { entered = true; manager.recover(); JSONObject().put("status", "requested") }
                "updates.check" -> {
                    check(!manager.busy()) { "请等待当前更新操作完成。" }
                    entered = true; manager.check(true, null); JSONObject().put("status", "requested")
                }
                "updates.automatic" -> {
                    require(args.opt("enabled") is Boolean) { "请明确是否自动检查新版本。" }
                    entered = true; manager.automatic(args.getBoolean("enabled"))
                    JSONObject().put("automatic", manager.automatic())
                }
                "updates.download" -> {
                    check(!manager.busy() && manager.release() != null && !manager.ready()) { "请先读取当前可用更新。" }
                    entered = true; manager.download(); JSONObject().put("status", "requested")
                }
                "updates.cancel" -> {
                    check(manager.busy() && manager.phase() in setOf("downloading", "applying", "verifying")) { "当前没有可暂停的更新下载。" }
                    entered = true; manager.cancel(); JSONObject().put("status", "requested")
                }
                "updates.install" -> {
                    check(!manager.busy() && manager.ready()) { "请先读取已经验证的更新包。" }
                    entered = true; manager.install(activity); JSONObject().put("status", "requested")
                }
                "updates.notifications" -> {
                    entered = true; activity.requestUpdateNotifications(); JSONObject().put("status", "requested")
                }
                "updates.close" -> { entered = true; activity.finish(); JSONObject().put("closed", true) }
                else -> error("当前更新窗口不支持这项操作。")
            }
        }
        val receipt = outcome.fold({ result(id, true, it) }, { failure ->
            result(id, false, JSONObject().put("code", if (entered) "outcome_unknown" else "not_sent")
                .put("message", if (entered) "这次更新操作的回执尚未确认，请读取当前状态后再继续；不会自动重试。"
                    else failure.message ?: "更新操作未被接受，请重新读取当前状态。"))
        })
        receipts[id] = signature to receipt
        emit(receipt)
        publish()
    }

    private fun result(id: String, ok: Boolean, value: JSONObject) = JSONObject().put("v", 1).put("kind", "result")
        .put("id", id).put("ok", ok).put(if (ok) "value" else "error", value)

    override fun foreground(value: Boolean) {
        foreground = value
        if (value) publish(force = true)
    }
    override fun invalidatePresentation(documentReplaced: Boolean) {
        generation++; document = null; receipts.clear(); previous = ""
        publish(force = true)
    }
    override fun restorePresentationState(saved: JSONObject?) = Unit
    override fun savePresentationState() = JSONObject()
    override fun close() {
        closed = true; foreground = false; generation++; document = null; receipts.clear()
        activity.unbindUpdateSurface(refresh)
    }
}
