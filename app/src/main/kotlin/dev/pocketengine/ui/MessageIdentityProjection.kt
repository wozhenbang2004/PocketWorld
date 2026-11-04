package dev.pocketengine.ui

import org.json.JSONArray
import org.json.JSONObject

/** Saved answer identity only. A displayed link is never permission to resume its task. */
internal data class UiReplyContext private constructor(
    val taskId: String, val runId: String, val epoch: Int,
    val replyId: String?, val deliveryId: String?, val replyToMessageIds: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().put("taskId", taskId).put("runId", runId).put("epoch", epoch)
        .put("replyId", replyId ?: JSONObject.NULL).put("deliveryId", deliveryId ?: JSONObject.NULL)
        .put("replyToMessageIds", JSONArray(replyToMessageIds))

    companion object {
        fun fromNativeMessage(message: JSONObject): UiReplyContext? {
            if (message.opt("role") != "assistant" || message.opt("importAssignedIdentity") == true) return null
            val task = messageIdentity(message, "taskId") ?: return null
            val run = messageIdentity(message, "nativeRunId") ?: return null
            val epoch = messageInteger(message, "nativeEpoch")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
            val reply = messageIdentity(message, "nativeReplyId")
            val delivery = messageIdentity(message, "nativeDeliveryId")
            if (reply == null && delivery == null) return null
            val refs = message.optJSONArray("replyToMessageIds") ?: JSONArray()
            if (refs.length() > 500) return null
            val ids = ArrayList<String>()
            for (index in 0 until refs.length()) {
                val id = (refs.opt(index) as? String)?.takeIf { it.isNotBlank() && it.length <= 240 } ?: return null
                if (id !in ids) ids.add(id)
            }
            return UiReplyContext(task, run, epoch, reply, delivery, ids.toList())
        }
    }
}

/** Immutable non-phone delivery. Phone keeps its existing, stricter projection. */
internal data class UiTaskResult private constructor(
    val kind: String, val taskId: String, val runId: String, val epoch: Int,
    val deliveryId: String, val status: String, val endedAt: Long,
    val summaryTruncated: Boolean, val summaryChars: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("taskId", taskId).put("runId", runId)
        .put("epoch", epoch).put("deliveryId", deliveryId).put("status", status).put("endedAt", endedAt)
        .put("summaryTruncated", summaryTruncated).put("summaryChars", summaryChars)

    companion object {
        fun fromNativeMessage(message: JSONObject): UiTaskResult? {
            val context = UiReplyContext.fromNativeMessage(message) ?: return null
            val kind = message.opt("nativeDeliveryKind") as? String ?: return null
            if (kind !in setOf("automation", "team", "owner_work")) return null
            val delivery = context.deliveryId ?: return null
            if (!delivery.startsWith("$kind:")) return null
            val reference = message.optJSONObject("taskReference") ?: return null
            if (reference.opt("taskId") != context.taskId || reference.opt("runId") != context.runId ||
                messageInteger(reference, "epoch") != context.epoch.toLong()) return null
            val status = message.opt(if (message.has("nativeStatus")) "nativeStatus" else "status") as? String ?: return null
            if (status !in setOf("completed", "failed", "cancelled", "interrupted", "needs_confirmation", "awaiting_user", "reconciling", "paused") ||
                message.has("status") && message.opt("status") != status) return null
            val ended = messageInteger(message, "taskEndedAt")?.takeIf { it > 0 } ?: return null
            val truncated = message.opt("summaryTruncated") as? Boolean ?: return null
            val chars = messageInteger(message, "summaryChars")?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
            return UiTaskResult(kind, context.taskId, context.runId, context.epoch, delivery, status, ended, truncated, chars)
        }
    }
}

private fun messageIdentity(message: JSONObject, field: String): String? =
    (message.opt(field) as? String)?.takeIf { it.isNotBlank() && it.length <= 240 }

private fun messageInteger(message: JSONObject, field: String): Long? = when (val value = message.opt(field)) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    else -> null
}
