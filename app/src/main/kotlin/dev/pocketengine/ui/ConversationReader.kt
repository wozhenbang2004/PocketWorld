package dev.pocketengine.ui

import org.json.JSONObject

internal data class ConversationReadPage(
    val window: Long, val conversation: String, val title: String, val readingKey: String?,
    val messages: List<UiMessage> = emptyList(), val cursor: String = "", val hasMore: Boolean = false,
    val loading: Boolean = true, val error: String? = null, val notice: String? = null,
    val rowBytes: Map<String,Int> = emptyMap(), val limited: Boolean = false, val index: Int = 0, val offset: Int = 0, val anchor: String = "", val bytes: Int = 0, val initialized: Boolean = false
) {
    fun accept(native: JSONObject, older: Boolean): ConversationReadPage {
        require(native.optString("conversationId") == conversation) { "原历史窗口属于其他对话" }
        val raw = native.getJSONArray("messages"); require(raw.length() <= 80) { "原历史窗口超出读取范围" }
        val nativeRows=raw.objects()
        val loaded = nativeRows.map { row ->
            UiMessage(row.getString("role"), row.getString("content"), row.optLong("at"), row.optString("taskId").takeIf(String::isNotBlank), row.getString("id"),
                row.optJSONObject("submission"), row.optJSONArray("attachments")?.length() ?: 0, row.optJSONArray("attachments").objects().mapNotNull(ChatInputRules::attachment),
                if (row.optBoolean("importAssignedIdentity")) 0L else row.optLong("revision", 0), memoryReference = row.optJSONObject("memoryReference"), phoneResult = UiPhoneResult.fromNativeMessage(row), replyContext = UiReplyContext.fromNativeMessage(row), taskResult = UiTaskResult.fromNativeMessage(row))
        }
        require(loaded.none { it.id.isNullOrBlank() }) { "原历史消息缺少稳定身份" }
        require(loaded.map { it.id }.distinct().size == loaded.size) { "原历史窗口重复消息身份，未混合显示" }
        val combined = if (older) (loaded + messages).distinctBy { it.id } else loaded
        val pageBytes=nativeRows.associate { it.getString("id") to it.toString().toByteArray(Charsets.UTF_8).size }
        val retainedBytes=if(older)rowBytes+pageBytes else pageBytes
        val actualBytes = combined.sumOf { (retainedBytes[it.id]?.toLong() ?: (it.content.toByteArray(Charsets.UTF_8).size.toLong()+512L)) }
        if (combined.size > 240 || actualBytes > 1024L * 1024L) return copy(loading = false, limited = true, notice = "已到本次阅读窗口上限，原消息没有删除。请从对话原文页按范围继续查看。")
        val page = native.optJSONObject("messagePage") ?: JSONObject()
        require(!page.has("hasMore") || page.opt("hasMore") is Boolean) { "原历史分页状态无效" }
        val next = page.optString("nextCursor"); require(next.length <= 2048 && (!page.optBoolean("hasMore") || next.isNotEmpty())) { "原历史游标不可核对" }
        return copy(title = native.optString("title", title), messages = combined, cursor = next, hasMore = page.optBoolean("hasMore"), loading = false, error = null,
            bytes = actualBytes.toInt(), rowBytes=retainedBytes.filterKeys { key->combined.any { it.id==key } }, initialized = true, limited = false,
            notice = if (page.optBoolean("retentionLimitApplied")) "旧文件当前只提供原保留窗口，未显示成完整历史。" else if ((page.optJSONArray("gaps")?.length() ?: 0) > 0) "部分原件超出本次范围，未用摘要替代。" else if (anchor.isNotBlank() && combined.none { it.id == anchor }) "上次阅读位置不在当前窗口，可继续查看较早消息；当前任务未切换。" else null)
    }
    fun locate(): Int = messages.indexOfFirst { it.id == anchor }.takeIf { it >= 0 } ?: index.coerceIn(0, (messages.size - 1).coerceAtLeast(0))
}
