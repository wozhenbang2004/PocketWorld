package dev.pocketengine.ui.web

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
