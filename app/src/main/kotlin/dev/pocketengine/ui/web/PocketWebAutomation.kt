package dev.pocketengine.ui.web

import dev.pocketengine.store.OriginalReminderSourceFields
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject

/**
 * Commands and short-lived reviews for the original reminder feature.
 * The bridge checks the current page first; the native automation host remains the writer.
 */
internal class PocketWebAutomation(private val controller: PocketWorldController) {
    private val reviews = PocketWebReminders()

    companion object {
        val COMMANDS: Set<String> = setOf(
            "automation.receipts", "automation.list", "automation.reviewSchedule",
            "automation.reviewRule", "automation.confirmReview", "automation.prepareOccurrence"
        )
    }

    fun clear() = reviews.clear()

    internal data class ReminderSourceSelection(val rule: String, val revision: Int, val conversation: String,
        val task: String, val run: String, val epoch: Int, val occurrence: OriginalReminderSourceFields.Occurrence?,
        val noticeConnection: Int?) {
        fun origin(): JSONObject = JSONObject().put("ownerId", "owner").put("spaceId", "private")
            .put("conversationId", conversation).put("taskId", task).put("runId", run).put("epoch", epoch)
    }

    /** A Web identity can only select a currently available native row; Book/SQL prove it on the worker. */
    fun originalReminderSource(ruleId: String, revision: Int,
            occurrence: OriginalReminderSourceFields.Occurrence?): ReminderSourceSelection? {
        val state = controller.state
        if (state.moduleBusy.any { it in setOf("automation.list", "automation.save", "automation.setEnabled", "automation.delete") } ||
            state.moduleErrors["automation.list"] != null) return null
        val rows = state.feature("automation").optJSONArray("rules") ?: return null
        val rule = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.singleOrNull { it.opt("id") == ruleId } ?: return null
        val origin = rule.optJSONObject("nativeOrigin") ?: return null
        if (rule.opt("revision") != revision || rule.opt("kind") != "reminder" || rule.optJSONObject("schedule")?.opt("type") != "once" ||
            rule.optBoolean("sourceInvalidated") || rule.optBoolean("sourceCheckPending") || origin.length() != 6 ||
            origin.opt("ownerId") != "owner" || origin.opt("spaceId") != "private") return null
        val conversation = (origin.opt("conversationId") as? String)?.takeIf { it.isNotBlank() && it.length <= 160 } ?: return null
        val task = (origin.opt("taskId") as? String)?.takeIf(::canonicalUuid) ?: return null
        val run = (origin.opt("runId") as? String)?.takeIf(::canonicalUuid) ?: return null
        val epoch = (origin.opt("epoch") as? Int)?.takeIf { it > 0 } ?: return null
        val connection = if (occurrence != null) {
            val notice = state.requestedReminderNotice ?: return null
            if (state.requestedConnectionSection != "automation" || state.requestedConnectionRevision != notice.connectionRevision ||
                notice.ruleId != ruleId || notice.id != occurrence.id() || notice.revision != occurrence.revision() ||
                notice.occurrenceAt != occurrence.at() || occurrence.revision() > revision ||
                "automation.receipts" in state.moduleBusy || state.moduleErrors["automation.receipts"] != null) return null
            val receipts = state.moduleResults["automation.receipts"]?.optJSONArray("receipts") ?: return null
            val receipt = (0 until receipts.length()).mapNotNull { receipts.optJSONObject(it) }.singleOrNull { it.opt("id") == occurrence.id() } ?: return null
            val receiptOrigin = receipt.optJSONObject("nativeOrigin") ?: return null
            if (receipt.opt("ruleId") != ruleId || receipt.opt("kind") != "reminder" || receipt.opt("revision") != occurrence.revision() ||
                receipt.optLong("occurrenceAt", -1L) != occurrence.at() ||
                receipt.opt("instanceKey") != listOf(ruleId, occurrence.revision(), occurrence.at()).joinToString(":") ||
                receipt.optString("status") !in setOf("notified", "dispatching", "delivery_unknown") ||
                receipt.optBoolean("sourceInvalidated") || receipt.optBoolean("sourceCheckPending") ||
                receiptOrigin.length() != origin.length() || origin.keys().asSequence().any { receiptOrigin.opt(it) != origin.opt(it) }) return null
            notice.connectionRevision
        } else null
        return ReminderSourceSelection(ruleId, revision, conversation, task, run, epoch, occurrence, connection)
    }

    private fun canonicalUuid(id: String): Boolean =
        id.length == 36 && runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false)


    fun dispatch(action: String, args: JSONObject): JSONObject? {
        when (action) {
            "automation.receipts" -> { checkedPayload(args, emptySet()); return accepted(controller.requestFeature("automation", "receipts")) }
            "automation.list" -> { checkedPayload(args, emptySet()); return accepted(controller.requestFeature("automation", "list")) }
            "automation.reviewSchedule", "automation.reviewRule" -> {
                require(controller.state.moduleBusy.none { it in setOf("automation.save", "automation.setEnabled", "automation.delete", "automation.list") }) { "请先等待原提醒操作回执" }
                require(controller.state.moduleErrors["automation.list"] == null) { "原提醒清单未读成功，请刷新后核对" }
                val rules = controller.state.feature("automation").optJSONArray("rules") ?: error("请先读取提醒清单")
                return if (action == "automation.reviewSchedule") reviews.reviewSchedule(args, rules) else reviews.reviewRule(args, rules)
            }
            "automation.confirmReview" -> {
                checkedPayload(args, setOf("reviewHandle"))
                require(controller.state.moduleBusy.none { it in setOf("automation.save", "automation.setEnabled", "automation.delete", "automation.list") }) { "请先等待原提醒操作回执" }
                require(controller.state.moduleErrors["automation.list"] == null) { "原提醒清单未读成功，请刷新后核对" }
                val reviewed = reviews.consume(requiredString(args, "reviewHandle"), controller.state.feature("automation").optJSONArray("rules") ?: error("原提醒清单不可用"))
                val command = reviewed.getString("command")
                return accepted(controller.requestFeature("automation", command, reviewed.getJSONObject("input"))).put("command", command)
            }
            "automation.prepareOccurrence" -> {
                checkedPayload(args, setOf("occurrenceId"))
                require("automation.receipts" !in controller.state.moduleBusy) { "请先等待原发生记录读取" }
                val id = requiredString(args, "occurrenceId")
                val receipts = controller.state.moduleResults["automation.receipts"]?.optJSONArray("receipts") ?: error("请先读取原发生记录")
                val receipt = (0 until receipts.length()).mapNotNull { receipts.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
                require(receipt != null && receipt.optString("kind") == "phone_goal" && receipt.optString("status") in setOf("awaiting_user", "blocked_notifications")) { "原发生状态已变化，请刷新核对" }
                controller.prepareReminderOccurrence(id)
            }
            else -> error("这项操作没有已接入的宿主实现")
        }
        return null
    }

    private fun accepted(requestId: String): JSONObject = JSONObject().put("accepted", true).put("requestId", requestId)
    private fun checkedPayload(args: JSONObject, allowed: Set<String>): JSONObject {
        require(args.keys().asSequence().all { it in allowed }) { "操作包含未接入的参数" }
        return JSONObject(args.toString())
    }
    private fun requiredString(args: JSONObject, name: String): String {
        val value = args.opt(name); require(value is String && value.isNotBlank()) { "缺少有效参数 $name" }; return value
    }
}
