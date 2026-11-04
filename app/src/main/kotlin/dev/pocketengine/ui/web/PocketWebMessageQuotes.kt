package dev.pocketengine.ui.web

import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** A Web presentation window over the existing native original-message selection transaction. */
internal class PocketWebMessageQuotes(private val controller: PocketWorldController) {
    private data class Window(
        val id: String, val messageId: String, val sourceRevision: Long, val text: String,
        val locator: String, val owner: Any, val draftKey: String, val draftEpoch: Int,
        val draftRevision: Long, var submitted: Boolean = false
    )
    private var window: Window? = null

    companion object {
        val COMMANDS = setOf("messageQuote.open", "messageQuote.stage", "messageQuote.close", "composer.source.remove")
    }

    fun clear() {
        val previous = window
        window = null
        if (previous != null) controller.leaveOwnerDomain("taskMemory")
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        when (action) {
            "messageQuote.open" -> {
                fields(args, "messageId", "sourceRevision", "key", "epoch")
                check(controller.webPrivateOwnerVisible()) { "请回到原私人消息引用原文" }
                val draft = controller.composerDraft ?: error("原私聊草稿尚未读取")
                check(draft.key == text(args, "key") && controller.composerViewEpoch == integer(args, "epoch").toInt()) { "原输入窗口已变化" }
                check(!controller.state.chatSubmitting && draft.conversationId == controller.state.conversationId.orEmpty()) { "原草稿正在切换或提交" }
                val id = text(args, "messageId")
                val message = controller.state.messages.firstOrNull { it.id == id } ?: error("原消息已不在当前会话，请重新打开")
                check(message.sourceRevision == integer(args, "sourceRevision", Long.MAX_VALUE) && message.content.isNotEmpty()) { "原消息已变化，请重新选择" }
                val locator = message.ownerOriginalLocator ?: error("这条消息没有可核对的原文身份")
                check(locator.optString("conversationId").isNotEmpty() && locator.optString("conversationId") == draft.conversationId) { "原消息不属于当前私人会话" }
                clear()
                val original = Window(UUID.randomUUID().toString(), id, message.sourceRevision, message.content,
                    locator.toString(), controller.webWindowTicket(), draft.key, controller.composerViewEpoch, draft.revision)
                window = original
                controller.enterOwnerDomain("taskMemory")
                done(JSONObject().put("windowId", original.id).put("text", original.text).put("maxChars", 4000), null)
            }
            "messageQuote.stage" -> {
                fields(args, "windowId", "offset", "chars")
                val original = window?.takeIf { it.id == text(args, "windowId") } ?: error("原引用窗口已关闭")
                checkCurrent(original)
                check(!original.submitted) { "本次原选区已提交核对，请查看原草稿；需要调整时重新打开原消息" }
                val offset = integer(args, "offset").toInt()
                val chars = integer(args, "chars").toInt()
                require(chars in 1..4000 && offset <= original.text.length - chars) { "请选择最多 4000 个字符的原文范围" }
                original.submitted = true
                // The locator comes only from the captured native UiMessage, never from Web arguments.
                controller.stageOwnerOriginalReferenceReceipt(JSONObject().put("ownerLocator", JSONObject(original.locator)).put("offset", offset).put("chars", chars)) { result, error ->
                    if (window !== original || !controller.webPrivateOwnerVisible() || controller.webWindowTicket() != original.owner) {
                        done(null, "原引用窗口已变化，请核对原草稿")
                    } else if (error != null || result?.optBoolean("selectionValidated") != true || result?.optBoolean("draftStaged") != true) {
                        done(null, error ?: "原选区尚未确认加入草稿，请重新核对")
                    } else done(PocketWebState.publicValue(result) as JSONObject, null)
                }
            }
            "messageQuote.close" -> {
                fields(args, "windowId")
                if (window?.id == text(args, "windowId")) clear()
                done(JSONObject().put("closed", true), null)
            }
            "composer.source.remove" -> {
                fields(args, "key", "epoch", "revision", "index")
                check(controller.webPrivateOwnerVisible() && !controller.state.chatSubmitting) { "请回到原草稿核对引用" }
                val draft = controller.composerDraft ?: error("原草稿已关闭")
                val epoch = integer(args, "epoch").toInt()
                check(draft.key == text(args, "key") && controller.composerViewEpoch == epoch && draft.revision == integer(args, "revision", Long.MAX_VALUE)) { "原草稿已变化，请重新核对引用" }
                val index = integer(args, "index").toInt()
                val count = draft.sourceRefs().length()
                require(index in 0 until count) { "原引用已变化" }
                controller.removeComposerSource(index, draft.key, epoch)
                check(controller.composerDraft?.sourceRefs()?.length() == count - 1) { "原引用尚未确认移除，请重新核对草稿" }
                done(JSONObject().put("removed", true), null)
            }
            else -> error("未接入的原文引用操作")
        }
    }

    private fun checkCurrent(original: Window) {
        check(controller.webPrivateOwnerVisible() && controller.webWindowTicket() == original.owner) { "原私人消息窗口已变化" }
        val draft = controller.composerDraft ?: error("原草稿已关闭")
        check(!controller.state.chatSubmitting && draft.key == original.draftKey && controller.composerViewEpoch == original.draftEpoch && draft.revision == original.draftRevision) { "原草稿已变化，请重新选择引用范围" }
        val message = controller.state.messages.firstOrNull { it.id == original.messageId }
        check(message != null && message.sourceRevision == original.sourceRevision && message.content == original.text && message.ownerOriginalLocator?.toString() == original.locator) { "原消息已变化，请重新选择引用范围" }
    }

    private fun fields(args: JSONObject, vararg names: String) { require(args.keys().asSequence().all { it in names }) { "引用参数包含不适用字段" } }
    private fun text(args: JSONObject, name: String): String { val value = args.opt(name); require(value is String && value.isNotBlank()) { "缺少原引用身份" }; return value }
    private fun integer(args: JSONObject, name: String, maximum: Long = Int.MAX_VALUE.toLong()): Long {
        val value = args.opt(name)
        require((value is Int || value is Long) && (value as Number).toLong() in 0..maximum) { "原引用范围或修订无效" }
        return (value as Number).toLong()
    }
}
