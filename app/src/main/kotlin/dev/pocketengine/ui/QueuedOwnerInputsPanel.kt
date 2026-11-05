package dev.pocketengine.ui

import org.json.JSONObject

internal data class QueuedOwnerInputsPage(
    val window: Long, val task: String, val conversation: String, val viewEpoch: Int,
    val items: List<JSONObject> = emptyList(), val reading: Boolean = true,
    val readingMessage: String? = null, val saving: Boolean = false,
    val error: String? = null, val notice: String? = null,
    val editor: QueuedOwnerInputEditor? = null, val taskStatus: String? = null
)

/** A list refresh cancels an outstanding detail read, without discarding a loaded edit draft. */

internal fun QueuedOwnerInputsPage.readingList()=copy(reading=true,readingMessage=null,error=null)

internal data class QueuedOwnerInputEditor(
    val message: String, val queueRevision: Long, val messageRevision: Long,
    val baseline: String, val originalMode: String, val text: String, val mode: String,
    val attachmentCount: Int, val referenceCount: Int, val withdrawn: Boolean
)

internal fun queuedInputStatus(row: JSONObject): String = when (row.optString("queueState")) {
    "pending" -> if (row.optString("mode") == "steer") "等待下一个安全边界" else "待本轮结束后处理"
    "claimed" -> "任务已接手，正在纳入上下文"
    "materialized" -> "已纳入任务上下文"
    "withdrawn" -> "已撤回"
    "admitted" -> "初始目标已接收"
    else -> "历史接手状态待核对"
}
