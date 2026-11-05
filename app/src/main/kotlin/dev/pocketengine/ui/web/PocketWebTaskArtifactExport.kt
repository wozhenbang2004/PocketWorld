package dev.pocketengine.ui.web

import android.net.Uri
import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.attachments.AttachmentRepository
import dev.pocketengine.ui.AttachmentExportPicker
import dev.pocketengine.ui.OutcomeExportLaunch
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** Reuses the existing nonce-bound system picker and verified snapshot writer for one original artifact. */
internal class PocketWebTaskArtifactExport(private val controller: PocketWorldController,
    private val originalTaskVisible: (String) -> Boolean, private val changed: () -> Unit) {
    private class Export(val id: String, val task: String, val file: String) {
        var phase = "waiting"
        var uri: Uri? = null
        var launchUnknown = false
        var error: String? = null
        var receipt: JSONObject? = null
        val control = RunControl()
    }
    private val activity = controller.webPresentationActivity()
    private val main = Handler(Looper.getMainLooper())
    private var foreground = true
    @Volatile private var selected: Export? = null
    private val picker = AttachmentExportPicker(activity.activityResultRegistry) { request, uri ->
        val original = selected?.takeIf { it.id == request && it.phase == "waiting" } ?: return@AttachmentExportPicker
        if (uri == null) { original.phase = "cancelled"; changed(); return@AttachmentExportPicker }
        if (uri.scheme != "content") { original.phase = "unconfirmed"; original.error = "请选择原系统文件提供器中的保存位置"; changed(); return@AttachmentExportPicker }
        original.uri = uri; original.phase = "selected"; finish(original); changed()
    }
    fun snapshot(): JSONObject? = selected?.let { original ->
        val file = JSONObject(original.file)
        JSONObject().put("requestId", original.id).put("taskId", original.task).put("path", file.getString("path")).put("sha256", file.getString("sha256"))
            .put("phase", original.phase).put("launchUnknown", original.launchUnknown).put("error", original.error ?: JSONObject.NULL)
            .put("receipt", original.receipt ?: JSONObject.NULL)
    }
    fun start(task: String, file: JSONObject): JSONObject {
        check(foreground && controller.webPrivateOwnerVisible() && originalTaskVisible(task)) { "请返回原任务保存这一份成果" }
        check(selected?.phase !in setOf("waiting", "selected", "writing")) { "原系统保存仍在等待，请先完成或取消，不会重复打开" }
        val original = Export(UUID.randomUUID().toString(), task, file.toString()); selected = original
        val name = file.getString("path").substringAfterLast('/').take(160).ifBlank { "original-artifact.bin" }
        when (picker.launch(original.id, "application/octet-stream", name)) {
            OutcomeExportLaunch.REQUESTED -> Unit
            OutcomeExportLaunch.UNKNOWN -> { original.launchUnknown = true; original.error = "系统选择器是否启动尚未确认，请先返回完成或取消原选择，没有重复保存。" }
            OutcomeExportLaunch.NOT_STARTED -> { original.phase = "not_started"; original.error = "系统文件选择器未启动，原成果保留。" }
        }
        changed(); return checkNotNull(snapshot())
    }
    private fun finish(original: Export) {
        if (!foreground || selected !== original || original.phase != "selected") return
        if (!controller.webPrivateOwnerVisible() || !originalTaskVisible(original.task)) { original.phase = "cancelled"; original.error = "已离开原任务，没有写入所选位置"; changed(); return }
        val uri = checkNotNull(original.uri); val owner = controller.webWindowTicket(); original.phase = "writing"
        original.control.setAuthorityCheck { check(selected === original && foreground && controller.webWindowTicket() == owner && controller.webPrivateOwnerVisible() && originalTaskVisible(original.task)) { "原成果保存窗口已变化" } }
        try { controller.runEcosystemControlWork(Runnable {
            val result = runCatching {
                original.control.check(); val file = JSONObject(original.file)
                val bytes = controller.readTaskSkillArtifact(original.task, file.getString("operationId"), file.getString("artifactId"), file.getString("sha256"))
                try { check(bytes.size.toLong() == file.getLong("bytes")); original.control.check(); AttachmentRepository.get(activity).exportNativeSnapshot(bytes, file.getString("sha256"), uri, original.control) }
                finally { java.util.Arrays.fill(bytes, 0.toByte()) }
            }
            main.post {
                if (selected !== original) return@post
                result.fold({ original.receipt = it; original.phase = "exported"; original.error = null }, { original.phase = "unconfirmed"; original.error = "导出未完整确认，目标可能已有部分内容；请核对所选文件，没有自动重写。" }); changed()
            }
        }) } catch (_: Exception) { original.phase = "not_started"; original.error = "原保存未进入工作队列，所选位置可能为空，请先核对。"; changed() }
    }
    fun foreground(value: Boolean) {
        foreground = value
        val original = selected
        if (!value && original?.phase == "writing") original.control.cancel()
        // ComposeActivity resumes the Web host before the Controller's foreground owner window.
        // Resume the one original picker on the next main turn, after that native lifecycle signal.
        if (value && original != null) main.post { if (foreground && selected === original) finish(original) }
    }
    fun clear() { selected?.control?.cancel(); selected = null; picker.close(); changed() }
}
