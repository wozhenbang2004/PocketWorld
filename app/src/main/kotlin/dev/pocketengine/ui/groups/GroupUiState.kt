package dev.pocketengine.ui.groups

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Pure native response/review guards; a response for another object never mutates this page. */
object GroupUiState {
    fun rows(value: JSONArray?): List<JSONObject> =
        if (value == null) emptyList() else (0 until value.length()).mapNotNull { value.optJSONObject(it) }

    fun revision(value: JSONObject?): Long {
        val raw = value?.opt("revision")
        return if (raw is Int || raw is Long) (raw as Number).toLong().takeIf { it >= 0 } ?: -1 else -1
    }

    fun envelope(requestId: String, value: JSONObject?): JSONObject? = value?.takeIf {
        requestId.isNotBlank() && it.opt("requestId") == requestId && it.opt("ok") is Boolean
    }

    fun result(requestId: String, value: JSONObject?): JSONObject? = envelope(requestId, value)
        ?.takeIf { it.optBoolean("ok") }?.optJSONObject("result")?.let { JSONObject(it.toString()) }

    fun newerSnapshot(current: JSONObject, candidate: JSONObject): Boolean =
        revision(candidate) >= revision(current) && revision(candidate) >= 0 &&
            candidate.optJSONArray("groups") != null && candidate.optJSONArray("requests") != null && candidate.optJSONArray("deliveries") != null

    fun sameGroup(reviewed: JSONObject, current: JSONObject?): Boolean = current != null &&
        reviewed.optString("id").isNotBlank() && reviewed.opt("id") == current.opt("id") &&
        revision(reviewed) > 0 && revision(reviewed) == revision(current) && reviewed.opt("policyEpoch") == current.opt("policyEpoch")

    fun reviewedPayload(reviewed: JSONObject, current: JSONObject?, draft: JSONObject): JSONObject {
        check(sameGroup(reviewed, current)) { "群设置或成员已经变化，请刷新后重新核对" }
        return JSONObject(draft.toString()).put("id", reviewed.getString("id")).put("revision", revision(reviewed))
    }

    fun exactTask(referenceId: String, result: JSONObject?): JSONObject? = result?.takeIf {
        it.optJSONObject("reference")?.opt("requestId") == referenceId && it.opt("known") is Boolean
    }?.also { value ->
        value.optJSONObject("task")?.let { task ->
            check(task.opt("taskId") == value.getJSONObject("reference").opt("taskId")) { "宿主返回了其他任务的详情" }
        }
    }

    fun newerTask(current: JSONObject?, candidate: JSONObject): Boolean {
        val before = current?.optJSONObject("task") ?: return true
        val after = candidate.optJSONObject("task") ?: return false
        return before.opt("taskId") == after.opt("taskId") && revision(after) >= revision(before) && revision(after) > 0
    }

    /** Presentation guard for the existing deterministic delivery identity; it grants no send. */
    fun deliveryId(requestId: String, outputId: String, audience: String): String =
        UUID.nameUUIDFromBytes("group-delivery:$requestId:$outputId:$audience".toByteArray(StandardCharsets.UTF_8)).toString()

    fun exactDelivery(requestId: String, taskId: String, deliveryId: String, result: JSONObject): JSONObject {
        check(result.opt("requestId") == requestId && result.opt("taskId") == taskId && result.opt("deliveryId") == deliveryId) {
            "投递回执与所选原任务或产物不一致"
        }
        check(result.opt("state") in setOf("prepared", "sending", "accepted", "delivered", "unknown", "failed")) { "投递回执状态未知，请在原任务中核对" }
        check(result.optString("state") != "delivered" || result.optString("platformMessageId").isNotBlank()) { "送达回执缺少平台消息身份" }
        return JSONObject(result.toString())
    }

    fun approvalLabel(status: String): String = when (status) {
        "required" -> "需要主人审批"
        "pending" -> "等待主人审批"
        "approved" -> "审批已通过"
        "denied" -> "主人已拒绝"
        "revoked" -> "审批或授权已撤销"
        else -> ""
    }

    fun cancellation(referenceId: String, result: JSONObject?): JSONObject {
        val task = exactTask(referenceId, result)?.optJSONObject("task") ?: error("请先核对原任务")
        val runId = task.optString("runId")
        val epoch = task.optInt("coordinatorEpoch")
        check(runId.matches(Regex("[a-f0-9-]{36}")) && epoch > 0) { "尚无可取消的执行代次，请先查询" }
        return JSONObject().put("requestId", referenceId).put("runId", runId).put("coordinatorEpoch", epoch)
    }

    fun taskLabel(status: String): String = when (status) {
        "queued", "preparing" -> "等待执行"
        "running" -> "正在执行"
        "awaiting_user" -> "等待主人处理"
        "needs_confirmation" -> "等待主人核对结果"
        "paused" -> "已暂停"
        "stopping" -> "正在停止"
        "completed" -> "已完成"
        "failed" -> "失败"
        "cancelled" -> "已取消"
        "interrupted" -> "已中断"
        "reconciling" -> "效果待核对"
        else -> "尚未读取任务状态"
    }

    fun deliveryLabel(status: String): String = when (status) {
        "prepared" -> "待投递"
        "sending" -> "投递中"
        "accepted" -> "平台已接受，送达待核对"
        "delivered" -> "已送达"
        "unknown" -> "投递结果未知，只能查询"
        "failed" -> "投递失败"
        else -> "尚未读取投递状态"
    }
}
