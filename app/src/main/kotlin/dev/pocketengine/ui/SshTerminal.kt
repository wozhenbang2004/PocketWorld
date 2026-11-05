package dev.pocketengine.ui

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.json.JSONObject

internal data class SshTerminalBinding(val terminalId: String, val hostTaskId: String, val runId: String, val profileId: String) {
    fun payload(): JSONObject = JSONObject().put("terminalId", terminalId).put("hostTaskId", hostTaskId).put("runId", runId)
}

internal fun sshTerminalBinding(value: JSONObject): SshTerminalBinding? {
    val terminal = value.optString("terminalId")
    val task = value.optString("hostTaskId").ifBlank { value.optString("taskId") }
    val run = value.optString("runId")
    val profile = value.optString("profileId")
    if (listOf(terminal, task, run, profile).any { it.isBlank() || it == "null" }) return null
    return SshTerminalBinding(terminal, task, run, profile)
}

/** Bounded private UI buffer. Preserve incomplete UTF-8 between real PTY read packets. */

internal class PtyTextBuffer(private val limit: Int = 120000) {
    private var pending = ByteArray(0)
    private val raw = StringBuilder()
    fun append(encoded: String, truncated: Boolean, finished: Boolean): String {
        if (truncated) { pending = ByteArray(0); raw.append("\n[较早的远端输出已被覆盖]\n") }
        val bytes = ByteBuffer.wrap(pending + Base64.getDecoder().decode(encoded))
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        val chars = CharBuffer.allocate(bytes.remaining() + 4)
        decoder.decode(bytes, chars, finished)
        if (finished) decoder.flush(chars)
        pending = ByteArray(bytes.remaining()).also { bytes.get(it) }
        chars.flip()
        raw.append(chars)
        if (raw.length > limit) raw.delete(0, raw.length - limit)
        return raw.toString().replace(Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)"), "").replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "").filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 && it.code != 127 }
    }
}

internal fun ownerReviewedPtyInput(receipt: JSONObject?, task: UiTask?): Boolean {
    val operationId = receipt?.optString("hostOperationId").orEmpty()
    if (operationId.isBlank() || task == null) return false
    val operation = task.raw.optJSONArray("operations").objects().find { it.optString("operationId") == operationId } ?: return false
    return operation.optString("state") == "resolved" && operation.optJSONObject("receipt")?.optString("source") == "owner_reconciliation"
}
