package dev.pocketengine.ui.web

import android.net.Uri
import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.attachments.AttachmentRepository
import dev.pocketengine.ui.AttachmentExportPicker
import dev.pocketengine.ui.OutcomeExportLaunch
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.ProjectPackageDocument
import java.util.UUID
import org.json.JSONObject

/** One exact original artifact and the same user-selected system document. */
internal class PocketWebTaskArtifactExport(private val controller: PocketWorldController,
    private val originalTaskVisible: (String) -> Boolean, private val changed: () -> Unit,
    private val historyDocument: (String, String, Int) -> String?) {
    data class Artifact(val operation: String, val id: String, val path: String, val sha: String, val bytes: Long) {
        fun matchesBytes(row: JSONObject) = row.opt("sha256") == sha && row.opt("bytes").let {
            (it is Int || it is Long) && (it as Number).toLong() == bytes
        }
    }
    data class HistoryPin(val task: String, val run: String, val epoch: Int, val file: Artifact)
    private data class HistoryScope(val document: String, val pin: HistoryPin)
    private class Export(val id: String, val task: String, val file: Artifact, val history: HistoryScope?) {
        var phase = "waiting"
        var uri: Uri? = null
        var launchUnknown = false
        var target: JSONObject? = null
        val control = RunControl()
    }
    private val activity = controller.webPresentationActivity()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var foreground = true
    @Volatile private var selected: Export? = null
    // One positive historical observation, not an active request, target locator or file read permit.
    private var lastVerified: HistoryScope? = null
    private val digest = Regex("[a-f0-9]{64}")
    private val operationId = Regex("[a-f0-9-]{36}")
    private val picker = AttachmentExportPicker(activity.activityResultRegistry) { request, uri ->
        val original = selected?.takeIf { it.id == request && it.phase == "waiting" } ?: return@AttachmentExportPicker
        if (uri == null) { original.phase = "cancelled"; changed(); return@AttachmentExportPicker }
        if (uri.scheme != "content") { original.phase = "not_written"; changed(); return@AttachmentExportPicker }
        original.uri = uri; original.phase = "selected"; finish(original); changed()
    }
    fun snapshot(): JSONObject? = selected?.let { original ->
        val file = original.file
        JSONObject().put("requestId", original.id).put("taskId", original.task).put("operationId", file.operation).put("artifactId", file.id)
            .put("path", file.path).put("sha256", file.sha).put("bytes", file.bytes)
            .put("phase", original.phase).put("launchUnknown", original.launchUnknown)
            .put("targetReadback", original.target ?: JSONObject.NULL)
    }
    /** Only existing native choice metadata supplies this optional pin. */
    fun historyPin(task: String, row: JSONObject, file: JSONObject): HistoryPin? {
        val operation = row.opt("operationId") as? String ?: return null
        val run = row.opt("runId") as? String ?: return null
        val receipt = row.optJSONObject("receipt") ?: return null
        val epoch = receipt.opt("epoch") as? Int ?: return null
        val native = row.optJSONObject("arguments")?.optJSONObject("nativeSkill") ?: return null
        val ref = native.optJSONObject("reference") ?: return null
        val plan = ref.opt("planDigest") as? String ?: return null
        val claimedAt = native.opt("claimedAt")
        if ((claimedAt !is Int && claimedAt !is Long) || (claimedAt as Number).toLong() <= 0) return null
        if (!operation.matches(operationId) || run.length !in 1..240 || run.isBlank() || epoch <= 0 ||
            row.opt("tool") != "skills_execute" || row.opt("state") != "receipt" ||
            native.opt("format") != "pocketworld.native-skill-operation.v1" || native.opt("approvalState") != "consumed" ||
            native.optJSONObject("deployment")?.opt("backendId") != "phone-managed-skill-v1" ||
            ref.opt("ownerId") != "owner" || ref.opt("spaceId") != "private" || ref.opt("taskId") != task ||
            ref.opt("runId") != run || ref.opt("epoch") != epoch || ref.opt("operationId") != operation ||
            receipt.opt("taskId") != task || receipt.opt("runId") != run || receipt.opt("operationId") != operation ||
            !plan.matches(digest) || receipt.opt("planDigest") != plan || receipt.opt("executionLocation") != "this_phone" ||
            receipt.opt("status") !in setOf("completed", "failed", "timed_out")) return null
        val id = file.opt("artifactId") as? String ?: return null
        val sha = file.opt("sha256") as? String ?: return null
        val path = file.opt("path") as? String ?: return null
        val bytes = file.opt("bytes")
        if (bytes !is Int && bytes !is Long) return null
        val size = (bytes as Number).toLong()
        if (!id.matches(digest) || !sha.matches(digest) || path.length !in 1..240 || size !in 0L..65_536L) return null
        return HistoryPin(task, run, epoch, Artifact(operation, id, path, sha, size))
    }
    private fun currentDocument(pin: HistoryPin) = historyDocument(pin.task, pin.run, pin.epoch)
    fun previouslyVerified(pin: HistoryPin?): Boolean =
        pin != null && lastVerified?.let { it.pin == pin && currentDocument(pin) == it.document } == true

    fun start(task: String, file: JSONObject, history: HistoryPin?): JSONObject {
        check(foreground && controller.webPrivateOwnerVisible() && originalTaskVisible(task)) { "请返回原任务保存这一份成果" }
        check(selected?.phase !in setOf("waiting", "selected", "writing")) { "原系统保存仍在等待，请先完成或取消，不会重复打开" }
        val artifact = Artifact(file.getString("operationId"), file.getString("artifactId"), file.getString("path"), file.getString("sha256"), file.getLong("bytes"))
        check(artifact.operation.isNotBlank() && artifact.id.matches(digest) && artifact.sha.matches(digest) && artifact.path.length in 1..240 &&
            artifact.bytes in 0L..65_536L && artifact.matchesBytes(file)) { "原成果信息不可核对，未打开保存位置" }
        val scope = history?.takeIf { it.task == task && it.file == artifact }?.let { pin ->
            currentDocument(pin)?.let { HistoryScope(it, pin) }
        }
        val original = Export(UUID.randomUUID().toString(), task, artifact, scope); selected = original
        val name = artifact.path.substringAfterLast('/').take(160).ifBlank { "original-artifact.bin" }
        when (picker.launch(original.id, "application/octet-stream", name)) {
            OutcomeExportLaunch.REQUESTED -> Unit
            OutcomeExportLaunch.UNKNOWN -> original.launchUnknown = true
            OutcomeExportLaunch.NOT_STARTED -> original.phase = "not_started"
        }
        changed(); return checkNotNull(snapshot())
    }
    private fun finish(original: Export) {
        if (!foreground || selected !== original || original.phase != "selected") return
        val uri = checkNotNull(original.uri); original.uri = null
        if (!controller.webPrivateOwnerVisible() || !originalTaskVisible(original.task)) { original.phase = "not_written"; changed(); return }
        val owner = controller.webWindowTicket(); original.phase = "writing"
        val c = original.control
        fun current() = selected === original && foreground && controller.webWindowTicket() == owner && controller.webPrivateOwnerVisible() && originalTaskVisible(original.task)
        c.setAuthorityCheck { check(current()) { "原成果保存窗口已变化" } }
        try { controller.runEcosystemControlWork(Runnable {
            var writeEntered = false
            var writeCompleted = false
            val file = original.file
            val result = runCatching {
                c.check()
                val bytes = controller.readTaskSkillArtifact(original.task, file.operation, file.id, file.sha)
                try {
                    check(bytes.size.toLong() == file.bytes); c.check()
                    val repository = AttachmentRepository.get(activity)
                    c.check(); writeEntered = true
                    val written = repository.exportNativeSnapshot(bytes, file.sha, uri, c)
                    check(written.opt("status") == "exported" && written.opt("verified") == true && file.matchesBytes(written))
                    writeCompleted = true
                } finally { bytes.fill(0) }
                c.check()
                // A write-only target can still receive the file. Probe readback only after a confirmed close.
                if (!ProjectPackageDocument.allowed(activity, uri)) return@runCatching null
                val proof = ProjectPackageDocument.taskOutput(activity, uri, null, file.bytes, file.sha, c, c::check)
                val status = proof.getString("status")
                if (status != "verified" && status != "content_mismatch") return@runCatching null
                if (status == "verified") {
                    check(file.matchesBytes(proof)); c.check()
                    // The same original operation/artifact must still resolve; do not consult a later task or another file.
                    controller.readTaskSkillArtifact(original.task, file.operation, file.id, file.sha).let { saved ->
                        try { check(saved.size.toLong() == file.bytes); c.check() } finally { saved.fill(0) }
                    }
                }
                c.check()
                // No pre-write name lookup: only the byte proof crosses the bridge, never a destination name, URI or provider.
                JSONObject().put("status", status).also { target ->
                    if (status == "verified") target.put("sha256", proof.getString("sha256")).put("bytes", proof.getLong("bytes"))
                }
            }
            main.post {
                if (selected !== original) return@post
                val target = result.getOrNull().takeIf { !c.isCancelled && current() }
                original.target = target
                original.phase = when {
                    target?.opt("status") == "verified" -> "verified"
                    writeCompleted -> "written_unverified"
                    writeEntered -> "write_unknown"
                    else -> "not_written"
                }
                if (original.phase == "verified") original.history?.takeIf { currentDocument(it.pin) == it.document }
                    ?.let { lastVerified = it }
                changed()
            }
        }) } catch (_: Exception) { original.phase = "not_written"; changed() }
    }
    fun foreground(value: Boolean) {
        foreground = value
        val original = selected
        if (!value && original?.phase == "writing") original.control.cancel()
        // ComposeActivity resumes the Web host before the Controller's foreground owner window.
        // Resume the one original picker on the next main turn, after that native lifecycle signal.
        if (value && original != null) main.post { if (foreground && selected === original) finish(original) }
    }
    fun leave() { selected?.let { it.control.cancel(); it.uri = null }; selected = null; picker.close(); changed() }
    fun clear() { lastVerified = null; leave() }
}
