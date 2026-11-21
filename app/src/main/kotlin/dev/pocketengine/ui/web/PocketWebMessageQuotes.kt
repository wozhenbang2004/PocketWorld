package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.core.RunControl
import dev.pocketengine.features.assistant.AssistantModule
import dev.pocketengine.features.assistant.OwnerSourceReferences
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.Fields
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.Format
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.Pin
import dev.pocketengine.features.attachments.creation.OwnerStandaloneDocumentDraftStore.State
import dev.pocketengine.ui.UiMessage
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** A Web presentation window over the existing native original-message selection transaction. */
internal class PocketWebMessageQuotes(private val controller: PocketWorldController) {
    private data class Prior(val reader: Long, val request: String, val revision: Long, val offset: Int, val chars: Int)
    private data class DocumentCopy(val visible: () -> Boolean, val conversationRevision: Long,
        val memoryRevision: Long, val role: String)
    private data class Window(
        val id: String, val messageId: String, val sourceRevision: Long, val text: String,
        val locator: String, val owner: Any, val draftKey: String, val draftEpoch: Int,
        val draftRevision: Long, val prior: Prior?, val shelfEligible: Boolean, val documentCopy: DocumentCopy?,
        var submitted: Boolean = false
    )
    private class DocumentAttempt(val original: Window, val offset: Int, val chars: Int, var pin: Pin? = null,
        var receipt: JSONObject? = null)
    private var window: Window? = null
    private var documentAttempt: DocumentAttempt? = null

    private data class Shelf(val id: String, val conversation: String, val key: String, val epoch: Int, val owner: Any, val ids: Set<String>)
    private data class Read(val id: String, val page: Shelf, val entry: String, val proof: AssistantModule.LocalShelfRead, val draft: Long, val expiry: Long)
    private var shelf: Shelf? = null
    private var read: Read? = null
    private var stagingPage: String? = null
    @Volatile private var cycle = 0L
    @Volatile private var pending: RunControl? = null

    companion object {
        const val READ_DRAFT_SOURCE = "composer.source.read"
        const val OPEN_DOCUMENT_COPY = "messageQuote.document.open"
        const val RETAIN_DOCUMENT_COPY = "messageQuote.document.retain"
        const val CHECK_DOCUMENT_COPY = "messageQuote.document.check"
        const val OPEN_SAVED_DOCUMENT = "messageQuote.document.openSaved"
        val DOCUMENT_COPY_COMMANDS = setOf(OPEN_DOCUMENT_COPY, RETAIN_DOCUMENT_COPY, CHECK_DOCUMENT_COPY, OPEN_SAVED_DOCUMENT)
        val COMMANDS = setOf("messageQuote.open", "messageQuote.stage", "messageQuote.close", "composer.source.remove", "messageQuote.pin", READ_DRAFT_SOURCE,
            "messageShelf.list", "messageShelf.read", "messageShelf.remove", "messageShelf.close", "messageShelf.dismiss", "messageShelf.stage") + DOCUMENT_COPY_COMMANDS
    }

    fun clear() {
        invalidateShelf()
        val previous = window
        window = null
        documentAttempt = null
        if (previous != null) controller.leaveOwnerDomain("taskMemory")
    }

    private fun invalidateShelf() {
        ++cycle; pending?.cancel(); pending = null; shelf = null; read = null
        if (stagingPage != null) { stagingPage = null; controller.leaveOwnerDomain("taskMemory") }
    }

    fun dispatch(action: String, args: JSONObject, documentWindow: (() -> Boolean)? = null, done: (JSONObject?, String?) -> Unit) {
        when (action) {
            "messageQuote.open", OPEN_DOCUMENT_COPY -> {
                val forDocument = action == OPEN_DOCUMENT_COPY
                if (forDocument) {
                    require(args.keys().asSequence().toSet() == setOf("messageId", "sourceRevision", "key", "epoch"))
                    check(documentWindow?.invoke() == true) { "请回到原私人对话选择文档文字" }
                }
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
                val selected = if (forDocument) controller.state.messages.singleOrNull { it.id == id } else null
                val (locator, content) = if (prior != null) {
                    retainedRange(prior, draft.conversationId, id, revision)
                } else {
                    val message = (if (forDocument) selected else controller.state.messages.firstOrNull { it.id == id })
                        ?: error("原消息已不在当前会话，请重新打开")
                    check(message.sourceRevision == revision && message.content.isNotEmpty()) { "原消息已变化，请重新选择" }
                    (message.ownerOriginalLocator ?: error("这条消息没有可核对的原文身份")) to message.content
                }
                check(locator.optString("conversationId").isNotEmpty() && locator.optString("conversationId") == draft.conversationId) { "原消息不属于当前私人会话" }
                if (forDocument) {
                    check(selected != null && documentMessage(selected, locator, draft.conversationId) &&
                        draft.key == "conversation:${draft.conversationId}" &&
                        controller.oneMessageLearningState(draft.key)?.opt("pending") == false &&
                        !controller.composerSaving) { "只从当前私聊已送达的原消息保留文档草稿" }
                }
                clear()
                // Native domain entry advances the owner epoch. Freeze the admitted window, not its predecessor.
                controller.enterOwnerDomain("taskMemory")
                val original = Window(UUID.randomUUID().toString(), id, revision, content,
                    locator.toString(), controller.webWindowTicket(), draft.key, controller.composerViewEpoch, draft.revision, prior,
                    !forDocument && prior == null && controller.state.messages.singleOrNull { it.id == id && it.role == "user" }?.submission?.let { ack ->
                        ack.optString("conversationId") == draft.conversationId && ack.optString("messageId") == id && ack.optString("clientMessageId").isNotBlank() &&
                            ack.optString("status") in setOf("accepted", "queued", "consumed") } == true &&
                        locator.optString("kind") == "owner_native_original" && locator.optString("recordId") == id && locator.optLong("recordVersion", -1L) == revision && draft.key == "conversation:${draft.conversationId}" &&
                        controller.oneMessageLearningState(draft.key)?.opt("pending") == false,
                    if (forDocument) DocumentCopy(checkNotNull(documentWindow), controller.conversationSourceRevision,
                        controller.state.memoryRefreshRevision, checkNotNull(selected).role) else null)
                window = original
                done(JSONObject().put("windowId", original.id).put("maxChars", 4000)
                    .put("draftRevision", original.draftRevision).put("registeredRangeOnly", prior != null)
                    .put("shelfEligible", original.shelfEligible).also {
                        if (forDocument) it.put("documentCopy", true).put("chars", content.length)
                            .put("fingerprint", locator.getString("fingerprint"))
                        else it.put("text", original.text)
                    }, null)
            }
            RETAIN_DOCUMENT_COPY, CHECK_DOCUMENT_COPY -> retainDocument(args, action == CHECK_DOCUMENT_COPY, done)
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
                check(window == null && pending == null && stagingPage == null) { "请先关闭原选区或等待当前操作" }
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
                check(stagingPage == null) { "正在保存原草稿，请先核对" }
                read = null; val draft = controller.composerDraft?.revision ?: error("原输入已关闭")
                var issued: AssistantModule.LocalShelfRead? = null
                shelfWork(page.conversation, page.key, page.epoch, { shelf === page && controller.webWindowTicket() == page.owner && controller.composerDraft?.revision == draft }, { result, failure ->
                    if (result != null && failure == null) {
                        if (action == "messageShelf.remove") shelf = page.copy(ids = page.ids - id)
                        else {
                            val proof = issued
                            if (proof == null) { done(null, "没有取得这段原话的本机阅读证明"); return@shelfWork }
                            val preview = Read(UUID.randomUUID().toString(), page, id, proof, draft, SystemClock.elapsedRealtime() + 120_000L)
                            read = preview; result.put("readWindow", preview.id).put("draftRevision", draft).put("readExpiresAt", System.currentTimeMillis() + 120_000L)
                        }
                        done(result.put("shelfWindow", page.id), null)
                    } else done(null, failure ?: "原备忘无法核对")
                }) { native, control -> if (action == "messageShelf.read") native.readLocalReference(page.conversation, id, control) { issued = it }
                    else native.removeLocalReference(page.conversation, id, control) }
            }
            "messageShelf.dismiss" -> {
                fields(args, "shelfWindow", "readWindow")
                if (read?.id == text(args, "readWindow") && read?.page?.id == text(args, "shelfWindow")) read = null
                done(JSONObject().put("dismissed", true), null)
            }
            "messageShelf.stage" -> {
                fields(args, "shelfWindow", "readWindow")
                val page = shelf?.takeIf { it.id == text(args, "shelfWindow") && it.owner == controller.webWindowTicket() } ?: error("请从同一私聊重新打开原话")
                val reviewed = read?.takeIf { it.page === page && it.id == text(args, "readWindow") && it.entry in page.ids } ?: error("请先在这条备忘中查看原话")
                check(stagingPage == null && pending == null) { "请先核对上一次草稿操作" }
                fun current() = shelf === page && controller.webWindowTicket() == page.owner && SystemClock.elapsedRealtime() < reviewed.expiry &&
                    controller.composerDraft?.revision == reviewed.draft && controller.composerDraft?.key == page.key && controller.composerViewEpoch == page.epoch
                check(current()) { "原话预览或当前草稿已变化，请重新核对" }
                read = null // One click consumes this proof, including failed or uncertain outcomes.
                shelfWork(page.conversation, page.key, page.epoch, ::current, { payload, failure ->
                    if (failure != null || payload == null || !current()) done(null, failure ?: "原备忘已变化，没有使用旧选区")
                    else try {
                        val draft = controller.composerDraft ?: error("原草稿已关闭")
                        if (reviewed.proof.alreadySelected(draft.sourceRefs())) {
                            invalidateShelf()
                            done(JSONObject().put("alreadyInDraft", true).put("changed", false).put("key", page.key).put("epoch", page.epoch)
                                .put("revision", reviewed.draft).put("shelfWindow", page.id).put("readWindow", reviewed.id), null)
                            return@shelfWork
                        }
                        // This existing domain has no snapshot/model call on entry. Its original source transaction rechecks the full reference again.
                        controller.enterOwnerDomain("taskMemory"); stagingPage = page.id
                        controller.stageOwnerOriginalReferenceReceipt(payload) { receipt, error ->
                            val same = stagingPage == page.id && shelf === page
                            if (same) invalidateShelf()
                            if (!same || receipt == null || error != null || receipt.opt("draftStaged") != true || receipt.opt("persisted") != true ||
                                receipt.opt("selectionValidated") != true || receipt.optString("key") != page.key || receipt.optInt("epoch",-1) != page.epoch ||
                                receipt.optLong("fromRevision",-1L) != reviewed.draft || receipt.optLong("revision",-1L) != reviewed.draft + 1L)
                                done(null, error ?: "先核对原草稿的引用，未确认前请勿重复添加")
                            else done(receipt.put("shelfWindow", page.id).put("readWindow", reviewed.id), null)
                        }
                    } catch (e: Exception) { if (stagingPage == page.id) invalidateShelf(); done(null, e.message ?: "原草稿未能确认") }
                }) { native, control -> native.localShelfDraftRange(reviewed.proof, control) }
            }
            "messageShelf.close" -> {
                fields(args, "shelfWindow")
                if (shelf?.id == text(args, "shelfWindow")) invalidateShelf()
                done(JSONObject().put("closed", true), null)
            }
            "messageQuote.stage" -> {
                fields(args, "windowId", "offset", "chars")
                val original = window?.takeIf { it.id == text(args, "windowId") && it.documentCopy == null }
                    ?: error("原引用窗口已关闭")
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

    private fun documentMessage(message: UiMessage, locator: JSONObject, conversation: String): Boolean {
        val id = message.id ?: return false
        if (message.content.isBlank() || message.phoneResult != null || message.taskResult != null ||
            message.hostGoalTaskId != null || message.originalHostFinal != null || message.firstHostRequestLocal ||
            message.readerInputWork != null || locator.opt("kind") != "owner_native_original" ||
            locator.opt("conversationId") != conversation || locator.opt("recordId") != id ||
            locator.optLong("recordVersion", -1L) != message.sourceRevision ||
            !locator.optString("fingerprint").matches(Regex("sha256:[0-9a-f]{64}"))) return false
        if (message.role == "assistant") return true
        val receipt = message.submission ?: return false
        return message.role == "user" && receipt.opt("conversationId") == conversation &&
            receipt.opt("messageId") == id && receipt.optString("clientMessageId").isNotBlank() &&
            receipt.opt("status") in setOf("accepted", "consumed")
    }

    private fun exactDocumentCopy(original: Window, locator: JSONObject): Boolean = runCatching {
        val doc = checkNotNull(original.documentCopy)
        check(window === original && doc.visible() && controller.conversationSourceRevision == doc.conversationRevision &&
            controller.state.memoryRefreshRevision == doc.memoryRevision && !controller.composerSaving)
        checkCurrent(original)
        val message = controller.state.messages.single { it.id == original.messageId }
        documentMessage(message, locator, locator.getString("conversationId")) && message.role == doc.role
    }.getOrDefault(false)

    /** Transfer only the positively retained disk pin, never the quote source or an editor handle. */
    fun takeSavedDocument(args: JSONObject): Pin {
        require(args.keys().asSequence().toSet() == setOf("windowId"))
        val original = window?.takeIf { it.id == text(args, "windowId") && it.documentCopy != null }
            ?: error("原文档保留回执已关闭")
        val attempt = documentAttempt?.takeIf { it.original === original }
            ?: error("尚未核对这份已保留的草稿")
        val receipt = attempt.receipt
        check(pending == null && receipt != null && receipt.opt("windowId") == original.id &&
            receipt.opt("status") in setOf(State.SAVED.name, State.VERIFIED.name) &&
            exactDocumentCopy(original, JSONObject(original.locator))) { "原文档保留回执已变化，没有打开其他草稿" }
        return checkNotNull(attempt.pin).also { clear() }
    }

    /** A document copy uses only this explicit private review. No composer reference, task or model is started. */
    private fun retainDocument(args: JSONObject, checking: Boolean, done: (JSONObject?, String?) -> Unit) {
        require(args.keys().asSequence().toSet() == if (checking) setOf("windowId") else setOf("windowId", "offset", "chars"))
        val original = window?.takeIf { it.id == text(args, "windowId") && it.documentCopy != null }
            ?: error("原文档选区已关闭")
        val doc = checkNotNull(original.documentCopy)
        val locator = JSONObject(original.locator)
        val conversation = locator.getString("conversationId")
        fun exact(): Boolean = exactDocumentCopy(original, locator)
        val owner = PocketWebStandaloneDraftFence.window(::exact)
        check(owner.current()) { "原消息或窗口已变化，没有保留其他文字" }
        fun unknown() = JSONObject().put("windowId", original.id).put("status", State.UNKNOWN.name)
        val existing = documentAttempt?.takeIf { it.original === original }
        val attempt = if (checking) {
            existing?.receipt?.let { done(JSONObject(it.toString()), null); return }
            if (existing == null || existing.pin == null || pending != null) { done(unknown(), null); return }
            existing
        } else {
            if (existing != null || pending != null) { done(unknown(), null); return }
            val offset = integer(args, "offset").toInt(); val chars = integer(args, "chars", 4000L).toInt()
            require(chars in 1..4000 && offset <= original.text.length - chars) { "请选择 1 至 4000 个完整字符" }
            DocumentAttempt(original, offset, chars)
        }
        val expected = attempt.pin
        val picked = original.text.substring(attempt.offset, attempt.offset + attempt.chars)
        check(picked.isNotBlank()) { "请先选择要保留的文字" }
        // Only the native original supplies these fields. This path stores no message locator or profile in the document draft.
        val fields = Fields(Format.WORD, "对话文稿", "", picked, 12)
        if (!checking) documentAttempt = attempt
        shelfWork(conversation, original.draftKey, original.draftEpoch, owner::current, { reply, error ->
            if (reply == null || error != null || documentAttempt !== attempt || !owner.current())
                done(null, error ?: "尚未确认已经保留；可在本机文档草稿核对")
            else {
                if (reply.opt("status") in setOf(State.SAVED.name, State.VERIFIED.name))
                    attempt.receipt = JSONObject(reply.toString())
                done(reply, null)
            }
        }) { native, control ->
            val storage = OwnerStandaloneDocumentDraftStore(controller.webPresentationActivity().applicationContext)
            val result = if (checking) storage.read(checkNotNull(expected), owner)
            else {
                val range = JSONObject().put("ownerLocator", locator).put("offset", attempt.offset).put("chars", attempt.chars)
                val selected = native.selectOwnerOriginalReference(range, control)
                check(selected.opt("selectionValidated") == true && selected.opt("bodyIncluded") == false)
                val actual = native.readPrivateDraftSourceForOwner(conversation, selected.getJSONObject("ownerSourceRef"), control)
                check(actual.opt("role") == doc.role && actual.opt("text") == picked) { "所选原段已变化，未保存其他文字" }
                control.check()
                val initial = storage.initialize(owner)
                val pin = storage.expectedFirstPin(initial, fields)
                PocketWebStandaloneDraftFence.onMain {
                    check(documentAttempt === attempt && exact()); attempt.pin = pin
                }
                storage.firstWrite(initial, fields)
            }
            val saved = result.draft()
            val status = result.state()
            if (status == (if (checking) State.VERIFIED else State.SAVED) && saved != null &&
                saved.metadata().pin() == attempt.pin && saved.metadata().revisionSource() == null &&
                saved.metadata().savedAt() > 0 && saved.fields() == fields) {
                JSONObject().put("windowId", original.id).put("status", status.name)
                    .put("name", saved.fields().name()).put("savedAt", saved.metadata().savedAt())
            } else if (!checking && status == State.QUOTA) JSONObject().put("windowId", original.id).put("status", status.name)
            else unknown()
        }
    }

    /** Exact cold-draft preview. The active native revision selects the sole stored source;
     * this path never opens the ordinary quote selection window or enters its write domain. */
    fun readDraftSource(args: JSONObject, ownerWindow: () -> Boolean, done: (JSONObject?, String?) -> Unit) {
        fields(args, "key", "epoch", "revision", "index")
        val key = text(args, "key"); val epoch = integer(args, "epoch").toInt()
        val revision = integer(args, "revision", Long.MAX_VALUE); val index = integer(args, "index", 3).toInt()
        val original = controller.composerDraft ?: error("原私聊草稿尚未读取")
        val conversation = original.conversationId
        check(ownerWindow() && conversation.isNotBlank() && key == "conversation:$conversation" &&
            original.key == key && original.revision == revision && controller.composerViewEpoch == epoch &&
            controller.state.conversationId == conversation && !controller.state.chatSubmitting &&
            window == null && shelf == null && stagingPage == null) { "请回到原私人草稿查看所附原话" }
        val checked = OwnerSourceReferences.checked(original.sourceRefs())
        val reference = checked.optJSONObject(index) ?: error("此选段不在当前草稿")
        val canonical = reference.toString()
        val message = reference.getJSONObject("sourceRef").getString("sourceId")
        val sourceRevision = controller.conversationSourceRevision
        val memoryRevision = controller.state.memoryRefreshRevision
        fun current(): Boolean {
            val draft = controller.composerDraft ?: return false
            return ownerWindow() && controller.conversationSourceRevision == sourceRevision &&
                controller.state.memoryRefreshRevision == memoryRevision &&
                controller.composerViewEpoch == epoch && draft.key == key && draft.revision == revision &&
                draft.conversationId == conversation && controller.state.conversationId == conversation &&
                !controller.state.chatSubmitting && !controller.composerSaving && controller.conversationReader == null &&
                !controller.loadingFirstConversationMessages() && controller.state.messages.none { it.id == message } &&
                window == null && shelf == null && stagingPage == null &&
                runCatching { OwnerSourceReferences.checked(draft.sourceRefs()).optJSONObject(index)?.toString() == canonical }.getOrDefault(false)
        }
        check(reference.getString("sourceConversationId") == conversation && current()) { "原草稿或已附选段已变化，没有读取其他消息" }
        shelfWork(conversation, key, epoch, ::current, { result, error ->
            if (!current() || result == null || error != null) done(null, error ?: "原草稿已变化，没有显示此前的原话")
            else done(result.put("format", "pocketworld.draft-source-read.v1").put("key", key).put("epoch", epoch)
                .put("revision", revision).put("index", index).put("reference", reference).put("readOnly", true)
                .put("modelCalled", false).put("formalMemorySaved", false).put("executionAuthorized", false), null)
        }) { native, control -> native.readPrivateDraftSourceForOwner(conversation, reference, control) }
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
