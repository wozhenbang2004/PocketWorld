package dev.pocketengine.ui.web

import java.util.UUID

/** UI-thread-only native frame/document fence; one WebView hosts at most one document. */
internal class PocketWebDocumentFence(private val issue: () -> String = { UUID.randomUUID().toString() }) {
    private var generation = 0L
    private var token: String? = null
    private var began = false
    private var rejected = false

    fun beginDocument(): Boolean {
        if (rejected) return false
        generation++; token = null
        if (began) { rejected = true; return false }
        began = true
        return true
    }
    fun isRejected() = rejected
    fun pause() { generation++; token = null }
    fun close() { generation++; token = null; rejected = true }
    fun loaded(): String {
        check(began && !rejected)
        token?.let { return it }
        return issue().also { check(it.isNotBlank()); token = it }
    }
    fun capture(mainFrame: Boolean, scheme: String?, host: String?, port: Int, submitted: String?, foreground: Boolean): Long? {
        if (!foreground || !mainFrame || scheme != "https" || host != "pocketengine.local" || port !in setOf(-1, 443)) return null
        if (submitted.isNullOrBlank() || submitted != token) return null
        return generation
    }
    fun accepts(captured: Long, submitted: String?, foreground: Boolean): Boolean =
        foreground && !rejected && captured == generation && !submitted.isNullOrBlank() && submitted == token
}

/** Separately fences commands already passed from the host into the bridge's own main queue. */
internal class PocketWebCommandFence {
    private var generation = 0L
    fun ticket(): Long = generation
    fun invalidate() { generation++ }
    fun accepts(ticket: Long): Boolean = ticket == generation
}

/** Immutable capture of both queues' original owner window. Epochs must be monotonic. */
internal data class PocketWebWindowTicket(val bridge: Long, val owner: Pair<Triple<Long, Int, Long>, Long>) {
    fun accepts(currentBridge: Long, currentOwner: Pair<Triple<Long, Int, Long>, Long>): Boolean =
        bridge == currentBridge && owner == currentOwner
}
