package dev.pocketengine.ui

import org.json.JSONObject

/** A fixed, read-only phone outcome. The original task may already be on a later run. */
internal data class UiPhoneResult private constructor(
    val taskId: String,
    val runId: String,
    val epoch: Int,
    val deliveryId: String,
    val status: String,
    val endedAt: Long,
    val summaryTruncated: Boolean,
    val summaryChars: Int,
) {
    /** Fresh public data only; no task, grant or mutable native message is retained. */
    fun toJson(): JSONObject = JSONObject()
        .put("taskId", taskId).put("runId", runId).put("epoch", epoch)
        .put("deliveryId", deliveryId).put("status", status).put("endedAt", endedAt)
        .put("summaryTruncated", summaryTruncated).put("summaryChars", summaryChars)

    companion object {
        private val endedStatuses = setOf(
            "completed", "needs_confirmation", "awaiting_user", "failed",
            "cancelled", "interrupted", "reconciling", "paused",
        )

        /** Incomplete or conflicting metadata hides only this badge, never the original body. */
        fun fromNativeMessage(message: JSONObject): UiPhoneResult? {
            if (message.opt("role") != "assistant" || message.opt("importAssignedIdentity") == true) return null
            val task = (message.opt("taskId") as? String)?.takeIf(String::isNotBlank) ?: return null
            val run = (message.opt("nativeRunId") as? String)?.takeIf(String::isNotBlank) ?: return null
            val epoch = exactLong(message, "nativeEpoch")?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
            val delivery = message.opt("nativeDeliveryId") as? String ?: return null
            if (delivery != "phone_handoff:$task:$run:$epoch") return null
            val reference = message.optJSONObject("taskReference") ?: return null
            if (reference.opt("taskId") != task || reference.opt("runId") != run ||
                exactLong(reference, "epoch") != epoch.toLong()) return null

            val status = message.opt(if (message.has("nativeStatus")) "nativeStatus" else "status") as? String ?: return null
            if (status !in endedStatuses || message.has("status") && message.opt("status") != status) return null
            val endedAt = exactLong(message, "taskEndedAt")?.takeIf { it > 0 } ?: return null
            val truncated = message.opt("summaryTruncated") as? Boolean ?: return null
            val chars = exactLong(message, "summaryChars")?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
            return UiPhoneResult(task, run, epoch, delivery, status, endedAt, truncated, chars)
        }

        /** JSONObject numeric getters coerce strings and fractions; identities must not. */
        private fun exactLong(row: JSONObject, key: String): Long? = when (val value = row.opt(key)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
    }
}
