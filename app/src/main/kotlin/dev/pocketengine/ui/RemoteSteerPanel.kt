package dev.pocketengine.ui

import dev.pocketengine.features.remote.RemoteMessageTarget
import org.json.JSONArray
import org.json.JSONObject

internal object RemoteSteerGuard {
    @JvmStatic fun target(task: JSONObject): JSONObject? {
        if (task.optString("feature") != "remote" || task.optString("actorId") != "owner" || task.optString("spaceId") != "private") return null
        val evidence = task.optJSONObject("evidence") ?: return null
        val refs = task.optJSONObject("externalRefs") ?: return null
        val inputTarget = try { RemoteMessageTarget.observed(evidence) } catch (_: Exception) { return null }
        if (RemoteMessageTarget.pi(inputTarget) && (task.has("teamRunId") || task.has("memberId") || task.optJSONObject("origin")?.optString("kind")?.startsWith("team") == true)) return null
        val result = JSONObject().put("taskId", task.optString("id")).put("runId", task.optString("runId"))
            .put("coordinatorEpoch", task.optInt("coordinatorEpoch", -1)).put("profileId", refs.optString("profileId"))
            .put("jobId", task.optString("externalId")).put("jobOperationId", task.optString("operationId"))
            .put("threadId", evidence.optString("threadId"))
        RemoteMessageTarget.copy(inputTarget, result)
        if (fields.any { result.optString(it).isBlank() } || result.optInt("coordinatorEpoch") < 0 ||
            result.optString("profileId") != evidence.optString("profileId") || result.optString("jobOperationId") != evidence.optString("operationId")) return null
        return result
    }
    private val fields = listOf("taskId", "runId", "profileId", "jobId", "jobOperationId", "threadId")
    @JvmStatic fun sameTarget(a: JSONObject?, b: JSONObject?): Boolean = try {
        a != null && b != null && fields.all { a.optString(it).isNotBlank() && a.optString(it) == b.optString(it) } &&
            a.optInt("coordinatorEpoch", -1) >= 0 && a.optInt("coordinatorEpoch") == b.optInt("coordinatorEpoch", -1) && RemoteMessageTarget.same(a, b)
    } catch (_: Exception) { false }
    @JvmStatic fun messages(task: JSONObject): List<JSONObject> = rows(task.optJSONArray("operations")).filter { it.optString("tool") == "remote_message" }
    @JvmStatic fun canSend(task: JSONObject, reviewed: JSONObject?): Boolean {
        val evidence = task.optJSONObject("evidence") ?: return false
        return sameTarget(reviewed, target(task)) && !task.optBoolean("cancelRequested") && !evidence.optBoolean("steeringUnknown") && task.optString("status") in setOf("running", "awaiting_user") &&
            evidence.optString("executorStatus") in setOf("running", "awaiting_approval") && evidence.optJSONObject("controls")?.optBoolean("inputSteering") == true &&
            messages(task).none { it.optString("state") != "message_receipt" }
    }
    @JvmStatic fun payload(task: JSONObject, reviewed: JSONObject, messageId: String, text: String): JSONObject {
        check(canSend(task, reviewed)) { "原turn或补充消息状态已变化，请核对后再输入" }
        require(messageId.matches(Regex("[A-Za-z0-9_-]{1,80}")) && text.isNotBlank() && text.length <= 12000 && !text.contains('\u0000')) { "补充内容需为1–12000字符" }
        return JSONObject(reviewed.toString()).put("messageId", messageId).put("text", text)
    }
    @JvmStatic fun preflightRejected(taskId: String, messageId: String?, hasJournal: Boolean, response: JSONObject?, responseInput: JSONObject?): Boolean =
        messageId != null && !hasJournal && responseInput != null && response != null && responseInput.optString("taskId") == taskId &&
            responseInput.optString("messageId") == messageId && response.optString("messageId") == messageId &&
            response.optString("receiptSource") == "native_preflight" && response.optString("status") == "rejected" && response.opt("dispatched") == false
    @JvmStatic fun statusLabel(status: String): String = when (status) {
        "accepted" -> "原turn已接收"
        "rejected" -> "未被接收"
        "queued" -> "已登记 · 等待发送回执"
        "sending" -> "发送中 · 尚未确认接收"
        else -> "接收结果未知 · 仅核对原消息"
    }
    @JvmStatic fun receiptLabel(receipt: JSONObject): String = if (RemoteMessageTarget.pi(receipt) && receipt.optString("status") == "accepted")
        "原Pi队列已接收 · 是否处理未知" else statusLabel(receipt.optString("status"))
    private fun rows(array: JSONArray?): List<JSONObject> = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

/** Leaf only: mounting, owner routing and bounded signal execution belong to the shared host. */
