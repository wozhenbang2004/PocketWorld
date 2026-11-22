package dev.pocketengine.ui

import dev.pocketengine.features.assistant.OwnerDetachedHistoryPosition
import org.json.JSONObject

private fun originalRecordVersion(value: Any?): Long? = when (value) { is Int -> value.toLong(); is Long -> value; else -> null }

/** An affirmative row-level restriction, not an inference from today's conversation switch.
 * Keep missing legacy/imported/foreign receipts unknown. No new source read or catalogue lookup. */
internal fun originalLearningExcluded(row: JSONObject, conversation: String): Boolean {
    if (row.opt("personalMemoryExcluded") != true || row.opt("role") != "user" ||
        row.optBoolean("importAssignedIdentity") || conversation.isBlank()) return false
    val original = row.optJSONObject("ownerOriginalLocator") ?: return false
    val id = row.opt("id") as? String ?: return false
    val revision = originalRecordVersion(row.opt("revision")) ?: return false
    return id.isNotBlank() && (row.opt("clientMessageId") as? String)?.isNotBlank() == true &&
        revision > 0 && revision == originalRecordVersion(original.opt("recordVersion")) &&
        original.opt("kind") == "owner_native_original" && original.opt("recordId") == id &&
        original.opt("conversationId") == conversation && (original.opt("generation") as? String)?.isNotBlank() == true
}

/** Read-only link from the persisted owner input and its matching native ACK.
 * An attempted send, imported text or the task's title is not a binding. */
internal data class ReaderInputWork(val taskId: String, val runId: String, val epoch: Int) {
    companion object {
        fun fromMessage(row: JSONObject, conversation: String): ReaderInputWork? {
            fun id(value: Any?) = (value as? String)?.takeIf { it.isNotBlank() && it.length <= 240 }
            if (row.opt("role") != "user" || row.optBoolean("importAssignedIdentity") || row.optLong("revision") < 1) return null
            val message = id(row.opt("id")) ?: return null
            val client = id(row.opt("clientMessageId")) ?: return null
            val receipt = row.optJSONObject("submission") ?: return null
            if (receipt.optString("status") !in setOf("accepted", "queued", "consumed") || receipt.optBoolean("notAdmitted") ||
                receipt.optBoolean("reconciliationRequired") || receipt.opt("messageId") != message ||
                receipt.opt("clientMessageId") != client || receipt.opt("conversationId") != conversation) return null
            val task = id(receipt.opt("taskId")) ?: return null
            val run = id(receipt.opt("runId")) ?: return null
            val epoch = when (val value = receipt.opt("epoch")) {
                is Int -> value.takeIf { it > 0 }
                is Long -> value.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
                else -> null
            } ?: return null
            if ((!row.isNull("taskId") && row.opt("taskId") != task) ||
                (!row.isNull("conversationId") && row.opt("conversationId") != conversation)) return null
            return ReaderInputWork(task, run, epoch)
        }
    }
}

/** An affirmative restriction only for the registered first goal of this exact original task. */
internal fun originalDefaultWritesBlocked(row: JSONObject, conversation: String): Boolean {
    val receipt = row.optJSONObject("submission") ?: return false
    val version = originalRecordVersion(receipt.opt("ownerMessageRevision")) ?: return false
    return receipt.opt("defaultWritesBlocked") == true && receipt.opt("queueRole") == "task_goal" &&
        version > 0 && version == originalRecordVersion(row.opt("revision")) &&
        originalLearningExcluded(row, conversation) && ReaderInputWork.fromMessage(row, conversation) != null
}

internal data class OwnerReportHistoryAnchor(val task:String,val run:String,val epoch:Int,val message:String,val revision:Long,val fingerprint:String)
internal data class OwnerWorkRequestHistoryAnchor(val task:String,val run:String,val epoch:Int,val message:String,val revision:Long,val sourceRevision:Long)
internal data class OwnerSearchHistoryAnchor(val request:String,val message:String,val revision:Long,val composerEpoch:Int)
internal data class OriginalReminderHistoryAnchor(val marker: JSONObject, val sourceRevision: Long, val current: () -> Boolean)

internal data class ConversationReadPage(
    val window: Long, val conversation: String, val title: String, val readingKey: String?,
    val messages: List<UiMessage> = emptyList(), val cursor: String = "", val hasMore: Boolean = false,
    val loading: Boolean = true, val error: String? = null, val notice: String? = null,
    val rowBytes: Map<String,Int> = emptyMap(), val limited: Boolean = false, val index: Int = 0, val offset: Int = 0, val anchor: String = "", val bytes: Int = 0, val initialized: Boolean = false,
    val quoteTrail: ConversationQuoteTrail = ConversationQuoteTrail(), val reportAnchor: OwnerReportHistoryAnchor? = null,
    val workRequestAnchor: OwnerWorkRequestHistoryAnchor? = null, val searchAnchor: OwnerSearchHistoryAnchor? = null,
    val detachedHistoryEpoch: Int? = null, val detachedHistoryResumeRevision: Long? = null,
    val detachedHistoryPosition: OwnerDetachedHistoryPosition? = null,
    val reminderSource: OriginalReminderHistoryAnchor? = null
) {
    private companion object { const val MAX_PAGE_BYTES = 1024L * 1024L }

    fun privateHostResultRow(message:String, revision:Long, task:String):UiMessage? =
        if (!initialized || loading || error != null || limited || revision != 1L) null
        else messages.singleOrNull { it.id==message && it.sourceRevision==revision && it.hostGoalTaskId==task && it.originalHostFinal!=null }

    /** Leave the older page, cursor and scroll anchor in place; the stored text isn't a new message. */
    fun withSavedPrivateHostFinal(message:String, revision:Long, task:String, text:String, addedRowBytes:Int):ConversationReadPage? {
        val row=privateHostResultRow(message,revision,task) ?: return null
        if (row.originalHostFinal?.text!=null) return takeIf { row.originalHostFinal.text==text }
        val checkedBytes=bytes.toLong()+addedRowBytes
        val old=rowBytes[message] ?: return null
        if (addedRowBytes<=0 || checkedBytes>MAX_PAGE_BYTES) return null
        return copy(messages=messages.map { if(it===row) it.copy(originalHostFinal=PrivateHostReceipt.FinalView(text)) else it },
            rowBytes=rowBytes+(message to (old+addedRowBytes)),bytes=checkedBytes.toInt())
    }

    fun accept(native: JSONObject, older: Boolean): ConversationReadPage {
        require(native.optString("conversationId") == conversation) { "原历史窗口属于其他对话" }
        val raw = native.getJSONArray("messages"); require(raw.length() <= 80) { "原历史窗口超出读取范围" }
        val nativeRows=raw.objects()
        val loaded = nativeRows.map { row ->
            UiMessage(row.getString("role"), row.getString("content"), row.optLong("at"), row.optString("taskId").takeIf(String::isNotBlank), row.getString("id"),
                row.optJSONObject("submission"), row.optJSONArray("attachments")?.length() ?: 0, row.optJSONArray("attachments").objects().mapNotNull(ChatInputRules::attachment),
                if (row.optBoolean("importAssignedIdentity")) 0L else row.optLong("revision", 0), projectConventionRecorded = row.opt("projectConventionRecorded")==true, memoryReference = row.optJSONObject("memoryReference"), phoneResult = UiPhoneResult.fromNativeMessage(row), replyContext = UiReplyContext.fromNativeMessage(row), taskResult = UiTaskResult.fromNativeMessage(row),
                hostGoalTaskId = PrivateHostReceipt.fromMessage(row,conversation), originalHostFinal = PrivateHostReceipt.finalView(row,conversation), firstHostRequestLocal = PrivateHostReceipt.isLocalFirstHost(row,conversation), readerInputWork = ReaderInputWork.fromMessage(row, conversation), learningExcluded = originalLearningExcluded(row, conversation), defaultWritesBlocked = originalDefaultWritesBlocked(row, conversation))
        }
        require(loaded.none { it.id.isNullOrBlank() }) { "原历史消息缺少稳定身份" }
        require(loaded.map { it.id }.distinct().size == loaded.size) { "原历史窗口重复消息身份，未混合显示" }
        val combined = (if (older) (loaded + messages).distinctBy { it.id } else loaded).toMutableList()
        val pageBytes=nativeRows.associate { it.getString("id") to it.toString().toByteArray(Charsets.UTF_8).size }
        val retainedBytes=if(older)rowBytes+pageBytes else pageBytes
        fun bytesFor(message: UiMessage) = retainedBytes[message.id]?.toLong() ?: (message.content.toByteArray(Charsets.UTF_8).size.toLong()+512L)
        var actualBytes = combined.sumOf(::bytesFor)
        var newerReleased = false
        // The bound is a display window, not a history boundary. Keep the newly
        // requested older page and release newer rows from this window as needed.
        while (older && combined.size > loaded.size && (combined.size > 240 || actualBytes > MAX_PAGE_BYTES)) {
            actualBytes -= bytesFor(combined.removeAt(combined.lastIndex))
            newerReleased = true
        }
        if (combined.size > 240 || actualBytes > MAX_PAGE_BYTES) return copy(loading = false, limited = true, notice = "这页原消息超出本次阅读窗口大小，原文未删除；可在对话原文页按范围查看。")
        val page = native.optJSONObject("messagePage") ?: JSONObject()
        require(!page.has("hasMore") || page.opt("hasMore") is Boolean) { "原历史分页状态无效" }
        val next = page.optString("nextCursor"); require(next.length <= 2048 && (!page.optBoolean("hasMore") || next.isNotEmpty())) { "原历史游标不可核对" }
        return copy(title = native.optString("title", title), messages = combined, cursor = next, hasMore = page.optBoolean("hasMore"), loading = false, error = null,
            quoteTrail = quoteTrail.update(conversation, nativeRows, combined.mapNotNull { it.id }.toSet(), older),
            bytes = actualBytes.toInt(), rowBytes=retainedBytes.filterKeys { key->combined.any { it.id==key } }, initialized = true, limited = false,
            notice = if (page.optBoolean("retentionLimitApplied")) "旧文件当前只提供原保留窗口，未显示成完整历史。" else if ((page.optJSONArray("gaps")?.length() ?: 0) > 0) "部分原件超出本次范围，未用摘要替代。" else if (newerReleased) "已载入较早消息，部分较新消息暂时移出阅读窗口；可继续向前查看，点击刷新返回最新消息。" else if (anchor.isNotBlank() && combined.none { it.id == anchor }) "上次阅读位置不在当前窗口，可继续查看较早消息；当前任务未切换。" else null)
    }
    fun locate(): Int = messages.indexOfFirst { it.id == anchor }.takeIf { it >= 0 } ?: index.coerceIn(0, (messages.size - 1).coerceAtLeast(0))
}
