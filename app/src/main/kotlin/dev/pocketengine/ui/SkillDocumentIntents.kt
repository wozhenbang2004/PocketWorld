package dev.pocketengine.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.attachments.AttachmentViewSnapshots
import dev.pocketengine.features.skills.SkillsModule
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.json.JSONObject

/** Activity-owned pending selection only. Receiving an intent neither reads nor imports a file;
 * the existing native import inspection is entered only by the owner's next explicit action. */
internal class SkillDocumentIntents : AutoCloseable {
    internal data class Pending(val requestId: String, val uri: Uri)
    var pending by mutableStateOf<Pending?>(null)
        private set
    private var closed = false

    @Synchronized fun acceptView(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        check(!closed) { "当前文件检查窗口已经关闭" }
        val uri = intent.data ?: throw IllegalArgumentException("没有可检查的文件")
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && !uri.authority.orEmpty().contains('@') && uri.fragment == null) { "请从系统文件应用选择可读取的 SKILL.md" }
        check(pending == null) { "已有一份待检查文件，请先完成或放弃原文件" }
        pending = Pending(UUID.randomUUID().toString(), uri)
        return true
    }

    /** Body/URI-free metadata for the existing Web workspace. No sender-provided filename is trusted. */
    @Synchronized fun snapshot(): JSONObject = JSONObject().put("pending", pending != null).put("bodyIncluded", false).put("imported", false).also { result ->
        pending?.let { result.put("requestId", it.requestId).put("name", "待核对的 SKILL.md").put("originalNameKnown", false).put("requiresOwnerInspection", true) }
    }
    @Synchronized fun requireOriginal(requestId: String): Pending = pending?.takeIf { !closed && it.requestId == requestId }
        ?: throw SecurityException("原收到文件已关闭、替换或处理，请重新选择")
    @Synchronized fun isCurrent(requestId: String): Boolean = !closed && pending?.requestId == requestId
    @Synchronized fun discard(requestId: String) { if (pending?.requestId == requestId) pending = null }
    @Synchronized override fun close() { closed = true; pending = null }
}

/** After explicit inspection only; presentation metadata is never package identity or authority. */
internal fun skillDocumentDisplayName(context: Context, uri: Uri): String? = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { rows ->
    if (!rows.moveToFirst() || rows.isNull(0)) null else rows.getString(0)?.takeIf { it.isNotBlank() && it.length <= 200 && it.none { value -> value.code < 32 || value.code == 127 } }
}

/** Android ACTION_SEND/EXTRA_STREAM + temporary read grant, through the existing expiring
 * AttachmentProvider snapshot. The caller opens the system chooser and reports no delivery. */
internal fun prepareSkillDocumentShare(context: Context, module: SkillsModule, reviewed: JSONObject, control: RunControl): Intent {
    control.check()
    val bytes = ByteArrayOutputStream()
    val receipt = writeSkillArchive(bytes) { output -> module.exportDocument(skillOwnerPrivateScope(), reviewed.getString("id"), reviewed.getInt("revision"), reviewed.getString("digest"), reviewed.getString("documentSha256"), output) }
    val exact = bytes.toByteArray()
    val snapshots = AttachmentViewSnapshots(context)
    var prepared: Intent? = null
    try {
        control.check()
        val snapshot = snapshots.prepare("SKILL.md", "text/markdown", receipt.getString("documentSha256"), exact, control)
        prepared = snapshot
        control.check()
        return Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, snapshot.data).putExtra(Intent.EXTRA_TITLE, "SKILL.md · 仅说明正文").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).also { it.clipData = snapshot.clipData }
    } catch (failure: Exception) {
        prepared?.data?.let { runCatching { snapshots.discardUnsent(it) } }
        throw failure
    } finally { exact.fill(0) }
}

internal fun discardUnsentSkillDocumentShare(context: Context, prepared: Intent) {
    @Suppress("DEPRECATION") val uri = prepared.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
    AttachmentViewSnapshots(context).discardUnsent(uri)
}
