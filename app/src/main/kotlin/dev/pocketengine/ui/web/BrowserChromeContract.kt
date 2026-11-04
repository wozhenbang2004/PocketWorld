package dev.pocketengine.ui.web

import org.json.JSONObject

/** A bounded owner gesture, not a BrowserModule command or a task authorization. */
enum class BrowserChromeAction(val wire: String) {
    NAVIGATE("navigate"), BACK("back"), FORWARD("forward"), RELOAD("reload"),
    RETURN_OWNER("return_owner"), RESEARCH("research"), SOURCES("sources"), TABS("tabs"), MORE("more")
}

/** Native identity is retained in memory only. It is never serialized into the trusted address surface. */
data class BrowserChromeState(
    val context: Any,
    val address: String,
    val title: String,
    val status: String,
    val loading: Boolean,
    val progress: Int,
    val canBack: Boolean,
    val canForward: Boolean,
    val pageCount: Int,
    val researchAvailable: Boolean,
    val pageAvailable: Boolean,
    val navigationUnconfirmed: Boolean,
    val navigationReviewRevision: Long
)

interface BrowserChromeOwnerUi {
    fun canInteract(): Boolean
    fun nativeState(): BrowserChromeState
    /** Recheck the same native identity immediately before dispatch, including after owner takeover. */
    fun perform(context: Any, action: BrowserChromeAction, input: String?): Boolean
    fun unavailable()
    /** A safety notice only, never proof of success or a retry instruction. */
    fun outcomeUnknown()
}

/** Restoration is presentation-only. An unknown prior request must never reopen its URL implicitly. */
object BrowserChromeRestorePolicy {
    @JvmStatic fun automaticRestoreAllowed(navigationUnconfirmed: Boolean): Boolean = !navigationUnconfirmed
}

internal object BrowserChromeContract {
    data class Command(val action: BrowserChromeAction, val input: String?)
    fun parse(args: JSONObject, windowId: String, generation: Long, contextToken: String): Command {
        val keys = args.keys().asSequence().toSet()
        require(keys.all { it in setOf("windowId", "generation", "contextToken", "action", "input") })
        require(args.opt("windowId") == windowId && args.opt("contextToken") == contextToken && contextToken.isNotEmpty())
        val version = args.opt("generation")
        require(version is Number && version.toString() == generation.toString())
        val action = BrowserChromeAction.entries.singleOrNull { it.wire == args.opt("action") }
            ?: throw IllegalArgumentException("Unsupported browser gesture")
        val input = if (action == BrowserChromeAction.NAVIGATE) {
            val value = args.opt("input")
            require(value is String && value.isNotBlank() && value.length <= 8192 && !value.any { it == '\u0000' || it == '\r' || it == '\n' })
            value
        } else { require(!args.has("input")); null }
        return Command(action, input)
    }
    fun project(state: BrowserChromeState): JSONObject = JSONObject()
        .put("address", state.address.take(8192)).put("title", state.title.take(240))
        .put("status", state.status.take(500)).put("loading", state.loading)
        .put("progress", state.progress.coerceIn(0, 100)).put("canBack", state.canBack)
        .put("canForward", state.canForward).put("pageCount", state.pageCount.coerceIn(0, 8))
        .put("researchAvailable", state.researchAvailable).put("pageAvailable", state.pageAvailable)
        .put("navigationUnconfirmed", state.navigationUnconfirmed)
        .put("navigationReviewRevision", state.navigationReviewRevision.coerceAtLeast(0L))
}
