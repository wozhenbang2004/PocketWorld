package dev.pocketengine.ui.web

import dev.pocketengine.core.RunControl
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** A Web presentation window over the existing native original-message selection transaction. */
internal class PocketWebMessageQuotes(private val controller: PocketWorldController) {
    private data class Prior(val reader: Long, val request: String, val revision: Long, val offset: Int, val chars: Int)
    private data class Window(
        val id: String, val messageId: String, val sourceRevision: Long, val text: String,
        val locator: String, val owner: Any, val draftKey: String, val draftEpoch: Int,
        val draftRevision: Long, val prior: Prior?, val shelfEligible: Boolean, var submitted: Boolean = false
    )
    private var window: Window? = null

    private data class Shelf(val id: String, val conversation: String, val key: String, val epoch: Int, val owner: Any, val ids: Set<String>)
    private var shelf: Shelf? = null
    @Volatile private var cycle = 0L
    @Volatile private var pending: RunControl? = null

    companion object {
        val COMMANDS = setOf("messageQuote.open", "messageQuote.stage", "messageQuote.close", "composer.source.remove", "messageQuote.pin",
            "messageShelf.list", "messageShelf.read", "messageShelf.remove", "messageShelf.close")
    }

    fun clear() {
        invalidateShelf()
        val previous = window
        window = null
        if (previous != null) controller.leaveOwnerDomain("taskMemory")
    }

    private fun invalidateShelf() { ++cycle; pending?.cancel(); pending = null; shelf = null }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        when (action) {
            "messageQuote.open" -> {
                fields(args, "messageId", "sourceRevision", "key", "epoch", "prior")
                check(controller.webPrivateOwnerVisible()) { "请回到原私人消息引用原文" }
                val draft = controller.composerDraft ?: error("原私聊草稿尚未读取")
                check(draft.key == text(args, "key") && controller.composerViewEpoch == integer(args, "epoch").toInt()) { "原输入窗口已变化" }
                check(!controller.state.chatSubmitting && draft.conversationId == controller.state.conversationId.orEmpty()) { "原草稿正在切换或提交" }
                val id = text(args, "messageId")
                val revision = integer(args, "sourceRevision", Long.MAX_VALUE)
                val prior = if (args.has("prior")) args.getJSONObject("prior").let {
                    fields(it, "reader", "request", "revision", "offset", "chars")
                    Prior(integer(it, "reader", Long.MAX_VALUE), text(it, "request"), integer(it, "revision", Long.MAX_VALUE),
                        integer(it, "offset").toInt(), integer(it, "chars").toInt())
                } else null
                val (locator, content) = if (prior != null) {
                    retainedRange(prior, draft.conversationId, id, revision)
                } else {
                    val message = controller.state.messages.firstOrNull { it.id == id } ?: error("原消息已不在当前会话，请重新打开")
                    check(message.sourceRevision == revision && message.content.isNotEmpty()) { "原消息已变化，请重新选择" }
                    (message.ownerOriginalLocator ?: error("这条消息没有可核对的原文身份")) to message.content
                }
                check(locator.optString("conversationId").isNotEmpty() && locator.optString("conversationId") == draft.conversationId) { "原消息不属于当前私人会话" }
                clear()
                // Native domain entry advances the owner epoch. Freeze the admitted window, not its predecessor.
                controller.enterOwnerDomain("taskMemory")
                val original = Window(UUID.randomUUID().toString(), id, revision, content,
                    locator.toString(), controller.webWindowTicket(), draft.key, controller.composerViewEpoch, draft.revision, prior,
                    prior == null && controller.state.messages.singleOrNull { it.id == id && it.role == "user" }?.submission?.let { ack ->
                        ack.optString("conversationId") == draft.conversationId && ack.optString("messageId") == id && ack.optString("clientMessageId").isNotBlank() &&
                            ack.optString("status") in setOf("accepted", "queued", "consumed") } == true &&
                        locator.optString("kind") == "owner_native_original" && locator.optString("recordId") == id && locator.optLong("recordVersion", -1L) == revision && draft.key == "conversation:${draft.conversationId}" &&
                        controller.oneMessageLearningState(draft.key)?.opt("pending") == false)
                window = original
                done(JSONObject().put("windowId", original.id).put("text", original.text).put("maxChars", 4000).put("draftRevision", original.draftRevision).put("registeredRangeOnly", prior != null).put("shelfEligible", original.shelfEligible), null)
            }
            "messageQuote.pin" -> {
                fields(args, "windowId", "offset", "chars", "label")
                val original = window?.takeIf { it.id == text(args, "windowId") && it.shelfEligible } ?: error("只能从原私聊自己的消息保留原话")
                checkCurrent(original); check(!original.submitted && pending == null) { "请先核对原窗口回执" }
                val offset = integer(args, "offset").toInt(); val chars = integer(args, "chars").toInt()
                require(chars in 1..4000 && offset <= original.text.length - chars) { "请选择最多4000个字符的原话" }
                val label = args.opt("label"); require(label is String && label.length <= 60) { "请用不超过60个字符的备忘名" }
                val locator = JSONObject(original.locator); val conversation = locator.getString("conversationId")
                val payload = JSONObject().put("ownerLocator", locator).put("offset", offset).put("chars", chars)
                shelfWork(conversation, original.draftKey, original.draftEpoch,
                    { window === original && !original.submitted && controller.composerDraft?.revision == original.draftRevision }, done) { native, control ->
                    native.pinLocalReference(conversation, payload, label, control).put("windowId", original.id)
                }
            }
            "messageShelf.list" -> {
                fields(args, "conversationId", "key", "epoch")
                val conversation = text(args, "conversationId"); val key = text(args, "key"); val epoch = integer(args, "epoch").toInt()
                check(window == null && pending == null) { "请先关闭原选区或等待当前操作" }
                invalidateShelf(); val owner = controller.webWindowTicket()
                shelfWork(conversation, key, epoch, { controller.webWindowTicket() == owner }, { result, failure ->
                    if (failure == null && result != null) {
                        val rows = result.getJSONArray("entries")
                        val page = Shelf(UUID.randomUUID().toString(), conversation, key, epoch, owner, (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet())
                        shelf = page; done(result.put("shelfWindow", page.id), null)
                    } else done(null, failure ?: "未读取原对话备忘")
                }) { native, control -> native.localReferenceShelf(conversation, control) }
            }
            "messageShelf.read", "messageShelf.remove" -> {
                fields(args, "shelfWindow", "id")
                val id = text(args, "id")
                val page = shelf?.takeIf { it.id == text(args, "shelfWindow") && id in it.ids && it.owner == controller.webWindowTicket() } ?: error("请重新打开本对话的原备忘")
                shelfWork(page.conversation, page.key, page.epoch, { shelf === page && controller.webWindowTicket() == page.owner }, { result, failure ->
                    if (result != null && failure == null) {
                        if (action == "messageShelf.remove") shelf = page.copy(ids = page.ids - id)
                        else result.put("readExpiresAt", System.currentTimeMillis() + 120_000L)
                        done(result.put("shelfWindow", page.id), null)
                    } else done(null, failure ?: "原备忘无法核对")
                }) { native, control -> if (action == "messageShelf.read") native.readLocalReference(page.conversation, id, control)
                    else native.removeLocalReference(page.conversation, id, control) }
            }
            "messageShelf.close" -> {
                fields(args, "shelfWindow")
                if (shelf?.id == text(args, "shelfWindow")) invalidateShelf()
                done(JSONObject().put("closed", true), null)
            }
            "messageQuote.stage" -> {
                fields(args, "windowId", "offset", "chars")
                val original = window?.takeIf { it.id == text(args, "windowId") } ?: error("原引用窗口已关闭")
                checkCurrent(original)
                check(!original.submitted && pending == null) { "本次原选区已提交核对，请查看原草稿；需要调整时重新打开原消息" }
                val offset = integer(args, "offset").toInt()
                val chars = integer(args, "chars").toInt()
                require(chars in 1..4000 && offset <= original.text.length - chars) { "请选择最多 4000 个字符的原文范围" }
                original.submitted = true
                // The locator comes only from the captured native UiMessage, never from Web arguments.
                controller.stageOwnerOriginalReferenceReceipt(JSONObject().put("ownerLocator", JSONObject(original.locator)).put("offset", Math.addExact(original.prior?.offset ?: 0, offset)).put("chars", chars)) { result, error ->
                    if (window !== original || !controller.webPrivateOwnerVisible() || controller.webWindowTicket() != original.owner) {
                        done(null, "原引用窗口已变化，请核对原草稿")
                    } else if (error != null || result == null || result.opt("selectionValidated") != true || result.opt("draftStaged") != true ||
                        result.opt("persisted") != true || result.optString("key") != original.draftKey || result.optLong("fromRevision", -1) != original.draftRevision) {
                        done(null, error ?: "原选区尚未确认保存到这份草稿，请先核对原输入")
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
                controller.removeComposerSource(integer(args, "index").toInt(), text(args, "key"),
                    integer(args, "epoch").toInt(), integer(args, "revision", Long.MAX_VALUE), done)
            }
            else -> error("未接入的原文引用操作")
        }
    }

    private fun shelfWork(conversation: String, key: String, epoch: Int, current: () -> Boolean, done: (JSONObject?, String?) -> Unit,
        work: (dev.pocketengine.features.assistant.AssistantModule, RunControl) -> JSONObject) {
        check(pending == null) { "正在核对原备忘，请稍候" }
        val token = cycle; val control = RunControl(); pending = control
        try {
            controller.requestConversationShelf(conversation, key, epoch, { cycle == token && pending === control && current() }, control, { result, error ->
                if (pending === control) pending = null
                if (cycle != token || !current()) done(null, "原备忘窗口已关闭，请重新读取") else done(result, error)
            }) { native -> work(native, control) }
        } catch (failure: Exception) { if (pending === control) pending = null; control.cancel(); throw failure }
    }

    private fun retainedRange(prior: Prior, conversation: String, id: String, revision: Long): Pair<JSONObject, String> {
        val page = controller.conversationReader ?: error("原阅读窗口已关闭")
        check(controller.canReadConversationWindow(page) && page.window == prior.reader && page.conversation == conversation &&
            conversation == controller.state.conversationId && !page.loading && !page.limited && page.error == null) { "请回到同一私人对话的原阅读窗口" }
        check(page.messages.any { it.id == prior.request && it.role == "user" && it.sourceRevision == prior.revision && it.readerInputWork != null }) { "原请求与工作回执已不在这份阅读窗口" }
        val source = page.messages.singleOrNull { it.id == id && it.sourceRevision == revision } ?: error("原消息已不在这份阅读窗口")
        val locator = page.quoteTrail.registeredLocator(conversation, prior.request, prior.revision, id, revision, prior.offset, prior.chars)
            ?: error("原请求已没有这段可核对的登记范围")
        return locator to source.content.substring(prior.offset, prior.offset + prior.chars)
    }

    private fun checkCurrent(original: Window) {
        check(controller.webPrivateOwnerVisible() && controller.webWindowTicket() == original.owner) { "原私人消息窗口已变化" }
        val draft = controller.composerDraft ?: error("原草稿已关闭")
        check(!controller.state.chatSubmitting && draft.key == original.draftKey && controller.composerViewEpoch == original.draftEpoch && draft.revision == original.draftRevision) { "原草稿已变化，请重新选择引用范围" }
        if (original.prior != null) {
            val (locator, content) = retainedRange(original.prior, draft.conversationId, original.messageId, original.sourceRevision)
            check(content == original.text && locator.toString() == original.locator) { "所选原段已经变化" }
            return
        }
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
