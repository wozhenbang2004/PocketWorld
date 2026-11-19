package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.features.ssh.DirectSshClient
import dev.pocketengine.features.ssh.SshProfile
import org.json.JSONObject
import java.util.UUID

internal typealias SshPickupWindow = Pair<Triple<Long, Int, Long>, Long>

/** A short-lived display handoff, never execution authority. File bytes stay in native SFTP/AttachmentBook. */
internal class PocketWebSshDownloads {
    internal data class Selection(val window: SshPickupWindow, val profile: String, val binding: String, val path: String, val bytes: Long)
    internal data class Reviewed(val file: Selection, val version: String, val handle: String, val expires: Long) {
        fun input() = JSONObject().put("profileId", file.profile).put("_profileBinding", file.binding)
            .put("path", file.path).put("expectedVersion", version)
    }
    private var listing = 0L
    private var files = emptyMap<String, Selection>()
    private var inspected: Selection? = null
    private var reviewed: Reviewed? = null
    private val versionPattern = Regex("sha256:[a-f0-9]{64}")
    private val privateName = Regex("(?i)(?:.*\\.(?:jks|keystore|p12|pfx|pem|key)|id_(?:rsa|ecdsa|ed25519|dsa))")

    fun clear(): Long { files = emptyMap(); inspected = null; reviewed = null; return ++listing }
    fun listed(ticket: Long, window: SshPickupWindow, input: JSONObject, actual: JSONObject) {
        if (listing != ticket || actual.optString("command") != "listFiles" || actual.optString("status") != "completed"
            || actual.opt("readOnly") != true || actual.optString("profileId") != input.getString("profileId") || actual.opt("path") != input.getString("path")) return
        val entries = actual.optJSONArray("files") ?: return
        if (entries.length() > 200) return
        val dir = input.getString("path")
        val next = mutableMapOf<String, Selection>()
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            val name = item.opt("name") as? String ?: continue
            val bytes = item.opt("bytes") as? Number ?: continue
            val path = if (dir.isEmpty()) name else "$dir/$name"
            if (name.isBlank() || '/' in name || name != name.trim() || path.split('/').any { it.startsWith('.') } || privateName.matches(name)
                || item.opt("directory") != false || item.opt("symlink") != false || bytes.toLong() !in 0L..DirectSshClient.MAX_TRANSFER
                || bytes.toDouble() != bytes.toLong().toDouble() || runCatching { SshProfile.relative(path, false) }.getOrNull() != path) continue
            next[path] = Selection(window, input.getString("profileId"), input.getString("_profileBinding"), path, bytes.toLong())
        }
        files = next
    }

    fun begin(window: SshPickupWindow, input: JSONObject): Selection {
        inspected = null; reviewed = null
        val file = files[input.getString("path")]
        check(file != null && file.window == window && file.profile == input.getString("profileId") && file.binding == input.getString("_profileBinding")) { "请重新读取这台主机的原目录，选择 20 MiB 以内的普通文件；不接收隐藏路径或密钥文件" }
        return file.copy().also { inspected = it }
    }
    fun observe(file: Selection, actual: JSONObject): JSONObject {
        check(inspected === file) { "原文件选择已变化，没有保存" }
        inspected = null
        if (actual.optString("status") != "completed" || actual.opt("exists") != true) { files = files - file.path; return actual }
        check(actual.optString("command") == "inspectFile" && actual.opt("readOnly") == true && actual.opt("streamedHash") == true
            && actual.optString("profileId") == file.profile && actual.optString("path") == file.path && actual.optLong("bytes", -1) == file.bytes
            && actual.optString("hostTaskId").isNotBlank() && actual.optString("taskId") == actual.getString("hostTaskId")
            && versionPattern.matches(actual.optString("version"))) { files = files - file.path; "文件已变化或未取得原件的实际摘要，请重读目录；没有保存" }
        val receipt = Reviewed(file, actual.getString("version"), UUID.randomUUID().toString(), SystemClock.elapsedRealtime() + 120_000)
        reviewed = receipt
        return actual.put("downloadReviewHandle", receipt.handle).put("downloadReviewExpiresAt", System.currentTimeMillis() + (receipt.expires - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }
    fun dismiss(handle: String) { if (reviewed?.handle == handle) reviewed = null }
    fun take(window: SshPickupWindow, handle: String): Reviewed {
        val value = reviewed
        check(value != null && value.handle == handle) { "原文件核对已关闭或使用；请先查看原传输回执" }
        reviewed = null
        check(value.file.window == window && SystemClock.elapsedRealtime() < value.expires) { "原文件核对已过期，请重读文件版本" }
        return value
    }

    fun saved(review: Reviewed, actual: JSONObject): JSONObject {
        if (actual.optString("status") != "completed") return actual
        val file = review.file
        val attachment = actual.optJSONObject("attachment")
        val origin = attachment?.optJSONObject("source")
        val id = actual.optString("hostTaskId"); val op = actual.optString("operationId")
        val matches = id.isNotBlank() && op.isNotBlank() && actual.optString("taskId") == id && actual.optString("command") == "downloadFile"
            && actual.optString("profileId") == file.profile && actual.optString("path") == file.path && actual.optLong("bytes", -1) == file.bytes
            && actual.opt("artifactSaved") == true && actual.opt("verifiedBytes") == true && actual.optString("version") == review.version
            && attachment != null && attachment.optString("id").isNotBlank() && attachment.optString("principalId") == "owner" && attachment.optString("spaceId") == "private"
            && attachment.optLong("size", -1) == file.bytes && attachment.optString("sha256") == review.version.removePrefix("sha256:")
            && origin != null && origin.optString("kind") == "ssh_download" && origin.optString("taskId") == id && origin.optString("operationId") == op
            && origin.optString("profileId") == file.profile && origin.optString("profileBinding") == file.binding && origin.optString("path") == file.path && origin.optString("expectedVersion") == review.version
        if (matches) return actual.put("privateDownloadVerified", true)
        return JSONObject().put("status", "unknown").put("message", "未取得一致的私人附件回执，请在原传输任务核对；未自动重试")
            .also { if (id.isNotBlank() && actual.optString("taskId") == id && actual.optString("profileId") == file.profile) it.put("hostTaskId", id) }
    }
}
