package dev.pocketengine.ui

import org.json.JSONObject

internal data class PlanDialogRequest(
    val requestId: String, val command: String, val planId: String, val planRevision: Long
)

internal data class PlanDialogReceipt(val pending: PlanDialogRequest?, val close: Boolean = false)

/** A negative or incomplete receipt releases pending state without disposing remembered draft input. */
internal fun consumePlanDialogReceipt(
    pending: PlanDialogRequest?, acknowledgement: JSONObject?, result: JSONObject?
): PlanDialogReceipt {
    if (pending == null || pending.requestId.isBlank() || acknowledgement?.opt("_requestId") != pending.requestId)
        return PlanDialogReceipt(pending)
    val plan = result?.optJSONObject("plan")
    val revision = plan?.opt("revision")
    val close = (!acknowledgement.has("_failed") || acknowledgement.opt("_failed") == false) &&
        pending.command in setOf("approve", "editStep") && result?.opt("command") == pending.command &&
        pending.planId.isNotBlank() && plan?.opt("id") == pending.planId &&
        (revision is Int || revision is Long) && (revision as Number).toLong() > pending.planRevision
    return PlanDialogReceipt(null, close)
}
