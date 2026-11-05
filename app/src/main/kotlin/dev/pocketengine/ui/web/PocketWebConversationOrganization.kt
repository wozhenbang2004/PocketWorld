package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.ui.PocketWorldController
import org.json.JSONObject
import java.util.UUID

/** Owner title and pin changes consume the exact original organization revision. */
internal class PocketWebConversationOrganization(private val controller: PocketWorldController) {
    private data class Selection(val id: String, val conversation: String, val title: String, val pinned: Boolean,
        val revision: Long, val owner: Pair<Triple<Long, Int, Long>, Long>, val expires: Long)
    private val selections = linkedMapOf<String, Selection>()
    companion object { val COMMANDS = setOf("conversation.organization.read", "conversation.organization.save", "conversation.organization.close") }
    fun clear() = selections.clear()
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        try {
            check(controller.webPrivateOwnerVisible()) { "请回到私人对话列表" }
            if (action == "conversation.organization.read") {
                require(args.keys().asSequence().toSet() == setOf("conversationId"))
                val row = controller.state.conversations.firstOrNull { it.id == args.getString("conversationId") } ?: error("原对话不在已读取的列表中")
                val actual = controller.conversationOrganizationItem(row)
                check(actual.organizationRevision >= 0) { "请先刷新原对话列表" }
                check("assistant.organization:${row.id}" !in controller.state.moduleBusy) { "原对话正在保存，请等待回执" }
                val selection = Selection(UUID.randomUUID().toString(), row.id, actual.title, actual.pinOrder > 0, actual.organizationRevision, controller.webWindowTicket(), SystemClock.elapsedRealtime() + 120000)
                selections.clear(); selections[selection.id] = selection
                done(JSONObject().put("selectionHandle", selection.id).put("conversationId", selection.conversation).put("title", selection.title).put("pinned", selection.pinned).put("organizationRevision", selection.revision), null)
                return
            }
            require(args.keys().asSequence().all { it in setOf("selectionHandle", "title", "pinned") })
            val handle = args.getString("selectionHandle")
            if (action == "conversation.organization.close") { selections.remove(handle); done(JSONObject().put("closed", true), null); return }
            check(action == "conversation.organization.save")
            val selection = selections[handle] ?: error("原会话编辑已关闭，请重新打开")
            check(controller.webWindowTicket() == selection.owner && SystemClock.elapsedRealtime() < selection.expires) { "原会话窗口已变化，请重新读取" }
            val title = args.opt("title"); val pinned = args.opt("pinned")
            require(title is String && title.trim().isNotEmpty() && title.trim().length <= 120 && pinned is Boolean) { "标题应为 1 至 120 个字符" }
            check("assistant.organization:${selection.conversation}" !in controller.state.moduleBusy) { "原会话正在保存，请等待回执" }
            val row = controller.state.conversations.firstOrNull { it.id == selection.conversation } ?: error("原会话当前不可核对")
            check(controller.conversationOrganizationItem(row).organizationRevision == selection.revision) { "原会话已有新修改，请重新读取" }
            selections.remove(handle)
            if (title.trim() == selection.title && pinned == selection.pinned) { done(JSONObject().put("unchanged", true), null); return }
            controller.organizeWebConversation(selection.conversation, selection.revision,
                title.trim().takeIf { it != selection.title }, pinned.takeIf { it != selection.pinned }, selection.owner, done)
        } catch (failure: Exception) { done(null, failure.message?.take(250) ?: "原会话保存未确认，请重新核对") }
    }
}
