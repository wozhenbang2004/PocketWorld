package dev.pocketengine.ui

import org.json.JSONObject

internal fun attachmentOpenKey(file: UiChatAttachment) = file.id + ":" + file.sha256

/** Exact original refs only; the current composer selection is never a historical source. */

internal fun taskResultAttachments(task: UiTask): List<UiChatAttachment> {
    if (task.raw.optJSONObject("origin")?.optString("kind") == "group") return emptyList()
    return task.raw.optJSONArray("operations").objects().flatMap { operation ->
        val receipt = operation.optJSONObject("receipt") ?: JSONObject()
        val data = receipt.optJSONObject("data") ?: JSONObject()
        (receipt.optJSONArray("attachments").objects() + data.optJSONArray("attachments").objects() + listOfNotNull(data.optJSONObject("attachment")) + listOf(data)).mapNotNull { meta ->
            val source = meta.optJSONObject("source")
            if (source?.optString("taskId") != task.id || source.has("operationId") && source.optString("operationId") != operation.optString("operationId")) null
            else ChatInputRules.attachment(meta)
        }
    }.distinctBy(::attachmentOpenKey).take(40)
}
