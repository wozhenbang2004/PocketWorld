package dev.pocketengine.ui

import org.json.JSONObject

/** Exact public identity from the rendered native snapshot; never accepts a newer run. */
internal fun originalChatCancelBinding(expected: JSONObject, conversation: String?, busy: Boolean, active: JSONObject?): JSONObject? {
    if (!busy || conversation.isNullOrBlank() || active == null) return null
    val sourceConversation = expected.optString("conversationId")
    val task = expected.optString("taskId"); val run = expected.optString("runId")
    val epochValue = expected.opt("epoch")
    if (sourceConversation != conversation || active.optString("conversationId") != conversation ||
        task.isBlank() || run.isBlank() || epochValue !is Int || epochValue < 1 ||
        active.optString("taskId") != task || active.optString("runId") != run ||
        active.opt("epoch") != epochValue) return null
    return JSONObject().put("conversationId", conversation).put("taskId", task).put("runId", run).put("epoch", epochValue)
}

/** Backend receipt means the stop request was delivered, not that the run has terminated. */
internal fun originalChatCancelReceipt(ok: Boolean, result: JSONObject?): JSONObject? =
    if (ok && result?.opt("cancellationRequested") == true)
        JSONObject().put("cancellationRequested", true)
    else null
