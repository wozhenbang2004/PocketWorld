package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.EngineApplication
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/**
 * Phone goal draft, original start reviews and receipts for this Web host.
 * The bridge owns document/transport fences; the controller owns native start admission.
 */
internal class PocketWebPhoneComposer(
    private val controller: PocketWorldController,
    private val isClosed: () -> Boolean,
    private val captureWindow: () -> PocketWebWindowTicket,
    private val acceptsWindow: (PocketWebWindowTicket) -> Boolean,
    private val complete: (String, JSONObject?, String?) -> Unit,
    private val publishState: () -> Unit
) {
    private data class PhoneReview(val goal: String, val mode: String, val tools: Int, val models: Int, val minutes: Int,
        val viewEpoch: Int, val expires: Long, val window: PocketWebWindowTicket, val composerKey: String,
        val conversation: String, val draftEpoch: Int, val draftRevision: Long, val reviewGeneration: Long, val originRevision: Long,
        val modelRevision: Long, val linked: EngineApplication.ConversationPhoneStartReview? = null,
        val draft: EngineApplication.DraftPhoneStartReview? = null)
    private val main = Handler(Looper.getMainLooper())
    private val phoneReviews = mutableMapOf<String, PhoneReview>()
    // Main-thread invalidation also revokes a review already captured by a queued worker.
    @Volatile private var reviewGeneration = 0L
    @Volatile private var presentationEpoch = 0
    private var phoneDraft = ""
    @Volatile private var phoneDraftRevision = 0L
    private var phoneIntent = false
    private var phoneExecutionMode = "background"
    private var phoneStartReceipt: JSONObject? = null

    val intent: Boolean get() = phoneIntent
    val draftRevision: Long get() = phoneDraftRevision

    fun clearReviews() { phoneReviews.clear(); reviewGeneration++ }
    fun documentChanged() { presentationEpoch++ }

    fun snapshot(): JSONObject = JSONObject().put("text", phoneDraft).put("revision", phoneDraftRevision)
        .put("epoch", presentationEpoch).put("phone", phoneIntent).put("mode", phoneExecutionMode).put("startReceipt", phoneStartReceipt).put("channelOrigin", controller.hasChannelPhoneOrigin)

    fun appendVoice(text: String) {
        phoneDraft = if (phoneDraft.isBlank()) text else phoneDraft + "\n" + text
        phoneDraftRevision++
        clearReviews()
    }

    fun changeIntent(args: JSONObject) {
        phoneIntent = requiredBoolean(args, "phone")
        if (args.has("mode")) phoneExecutionMode = requiredString(args, "mode").also { require(it in setOf("background", "main_screen")) }
        clearReviews()
    }

    fun editDraft(args: JSONObject) {
        require(requiredInt(args, "epoch") == presentationEpoch) { "原手机输入窗口已变化，草稿仍保留" }
        val text = requiredString(args, "text", true); require(text.length <= 12000)
        phoneDraft = text; phoneDraftRevision++; clearReviews()
    }

    /** Owner-selected original text only. Staging never creates a task or grants phone access. */
    fun stageOriginalMessage(args: JSONObject): JSONObject {
        checkedPayload(args, setOf("conversationId", "messageId", "sourceRevision", "epoch", "revision"))
        check(!isClosed() && controller.webPrivateOwnerVisible() && !controller.hasChannelPhoneOrigin) { "请回到原私人对话准备手机任务" }
        check(requiredInt(args, "epoch") == presentationEpoch && requiredLong(args, "revision") == phoneDraftRevision) { "手机草稿已变化，原文字仍保留" }
        val conversation = requiredString(args, "conversationId")
        check(conversation == controller.state.conversationId && controller.composerDraft?.conversationId == conversation) { "原对话已切换，没有复制其他对话的消息" }
        check(!controller.state.chatSubmitting && !controller.state.chatBusy && !controller.state.phoneSubmitting && controller.state.activeTaskId == null) { "请先等原回复结束或处理当前任务，没有开始第二次执行" }
        check(phoneStartReceipt?.optString("status") !in setOf("pending", "unknown")) { "原手机启动结果待核对，请先查询原请求" }
        val id = requiredString(args, "messageId")
        val message = controller.state.messages.firstOrNull { it.id == id } ?: error("原消息已不在当前对话，请重新打开")
        check(controller.state.messages.lastOrNull { it.role == "user" }?.id == id) { "请从最新主人请求准备手机任务，避免恢复已被后续要求替代的目标" }
        check(message.role == "user" && message.sourceRevision > 0 && message.sourceRevision == requiredLong(args, "sourceRevision")
            && message.ownerOriginalLocator?.optString("conversationId") == conversation) { "请从这段私人对话中的原主人消息准备手机任务" }
        check(message.textOnlyHandoffEligible && message.attachmentCount == 0 && message.attachments.isEmpty() && message.attachmentFiles.isEmpty()) { "这条请求含附件或来源/预览引用，或尚未核实完整资料，不能只复制文字；请在手机目标中整理完整任务、联系人和内容" }
        val goal = message.content.trim()
        require(goal.isNotEmpty() && goal.length <= 4000) { "手机任务目标需为 1–4000 字；请自行整理完整目标，不会截断原请求" }
        check(phoneDraft.isBlank() || phoneDraft.trim() == goal) { "已有另一份手机草稿，请先保留或处理；没有覆盖原文字" }
        phoneDraft = goal
        phoneDraftRevision++
        phoneIntent = true
        phoneStartReceipt = null
        clearReviews()
        return snapshot().put("staged", true).put("taskCreated", false).put("executionStarted", false)
            .put("message", "原请求已放入手机目标草稿。核对完整目标、联系人和内容，再审阅执行位置与范围；尚未启动或发送。")
    }

    fun prepare(id: String, args: JSONObject) {
        checkedPayload(args, setOf("goal", "mode", "epoch"))
        require(controller.webPrivateOwnerVisible() && requiredInt(args, "epoch") == presentationEpoch) { "原手机输入窗口已变化，请重新核对保留草稿" }
        val goal = requiredString(args, "goal"); val mode = requiredString(args, "mode")
        require(goal.trim().isNotEmpty() && goal.trim().length <= 4000 && mode in setOf("background", "main_screen")) { "请填写 1–4000 字的手机任务目标，并选择执行位置" }
        require(phoneDraft.trim() == goal.trim() && phoneIntent && phoneExecutionMode == mode) { "原手机草稿或执行位置已变化，请重新核对" }
        require(!controller.state.phoneSubmitting && controller.state.activeTaskId == null) { "请先处理当前工作；没有创建第二个手机任务" }
        require(phoneStartReceipt?.optString("status") !in setOf("pending", "unknown") || phoneStartReceipt?.has("requestId") != true && phoneStartReceipt?.optString("goal")?.trim() != goal.trim()) { "原手机请求的启动结果待核对，请先查询原请求。草稿仍保留。" }
        clearReviews()
        val handle = UUID.randomUUID().toString()
        val conversation = controller.state.conversationId.orEmpty()
        val channel = controller.hasChannelPhoneOrigin
        val modelScope = if (channel) null else controller.conversationModelScope()
        val modelStatus = controller.nativeModelSelection().status()
        check(!modelStatus.pending()) { "请等待原模型设置回执" }
        val original = PhoneReview(goal, mode, controller.newTaskToolBudget,
            controller.newTaskModelBudget, controller.newTaskScopeMinutes,
            controller.composerViewEpoch, SystemClock.elapsedRealtime() + 120_000L, captureWindow(),
            controller.composerDraft?.key.orEmpty(), conversation, presentationEpoch, phoneDraftRevision, reviewGeneration, controller.phoneOriginRevision, modelStatus.revision())
        // Account refresh/model resolution may use the network. Keep it off the WebView's main thread.
        controller.runModelConnectionWork(Runnable {
            val prepared = runCatching {
                check(phoneReviewCurrent(original)) { "原手机草稿或模型设置已变化，请重新核对" }
                when {
                    channel -> original
                    conversation.isNotBlank() -> original.copy(linked = controller.prepareConversationPhoneStart(conversation, goal, mode))
                    else -> original.copy(draft = controller.prepareDraftPhoneStart(modelScope!!, goal, mode))
                }.also { review ->
                    val native = review.linked?.view() ?: review.draft?.view()
                    check(native == null || native.getInt("toolLimit") == original.tools &&
                        native.getInt("modelCallLimit") == original.models && native.getInt("scopeMinutes") == original.minutes) {
                        "原手机执行范围已变化，请重新核对；草稿仍保留"
                    }
                }
            }
            main.post {
                if (runCatching { phoneReviewCurrent(original) }.getOrDefault(false)) prepared.fold({ review ->
                    phoneReviews[handle] = review
                    complete(id, reviewView(handle, review), null)
                }, { error -> complete(id, null, error.message ?: "本次手机模型暂不可核对，草稿仍保留") })
                else complete(id, null, "原手机草稿或模型设置已变化，请重新核对；草稿仍保留")
                publishState()
            }
        })
    }

    private fun reviewView(handle: String, review: PhoneReview): JSONObject {
        val shown = review.linked?.view() ?: review.draft?.view()
        return (shown ?: JSONObject()).put("reviewHandle", handle).put("goal", review.goal).put("mode", review.mode)
            .put("toolLimit", review.tools).put("modelCallLimit", review.models).put("scopeMinutes", review.minutes)
            .put("expiresInMs", 120000).put("screenDisclosure", shown?.optString("screenDisclosure") ?: "执行时，屏幕与可见文字会发送到本次配置的模型。")
            .put("message", if (review.mode == "background") "在后台独立屏处理这个目标；连接不可用时等待，不切换到手机主屏。" else "在手机主屏处理这个目标，会使用当前屏幕。")
    }

    /** Equivalent to the former Activity saveable presentation state, never an execution grant. */
    fun savePresentation(): JSONObject = JSONObject().put("phoneDraft", phoneDraft)
        .put("phoneDraftRevision", phoneDraftRevision).put("phoneIntent", phoneIntent).put("phoneExecutionMode", phoneExecutionMode)
        .put("phoneStartReceipt", phoneStartReceipt)

    fun restorePresentation(saved: JSONObject?) {
        if (saved == null) return
        val text = saved.optString("phoneDraft")
        if (text.length <= 12000) phoneDraft = text
        phoneDraftRevision = saved.optLong("phoneDraftRevision").coerceAtLeast(0)
        phoneIntent = saved.optBoolean("phoneIntent")
        phoneExecutionMode = saved.optString("phoneExecutionMode").takeIf { it in setOf("background", "main_screen") } ?: "background"
        phoneStartReceipt = saved.optJSONObject("phoneStartReceipt")?.let { JSONObject(it.toString()) }
        // Restoring a view cannot prove whether its previous worker completed.
        if (phoneStartReceipt?.optString("status") == "pending") phoneStartReceipt?.put("status", "unknown")
    }

    private fun phoneReviewCurrent(review: PhoneReview): Boolean = phoneIntent && phoneExecutionMode == review.mode && review.reviewGeneration == reviewGeneration &&
        review.originRevision == controller.phoneOriginRevision &&
        !isClosed() && controller.webPrivateOwnerVisible() &&
        SystemClock.elapsedRealtime() < review.expires && acceptsWindow(review.window) &&
        review.viewEpoch == controller.composerViewEpoch && review.composerKey == controller.composerDraft?.key.orEmpty() &&
        review.conversation == controller.state.conversationId.orEmpty() && review.draftEpoch == presentationEpoch && review.draftRevision == phoneDraftRevision &&
        !controller.nativeModelSelection().status().pending() && review.modelRevision == controller.nativeModelSelection().status().revision() &&
        review.tools == controller.newTaskToolBudget && review.models == controller.newTaskModelBudget && review.minutes == controller.newTaskScopeMinutes && review.reviewGeneration == reviewGeneration &&
        review.originRevision == controller.phoneOriginRevision

    private fun phoneReceiptIdentity(handle: String, review: PhoneReview): JSONObject = JSONObject()
        .put("reviewHandle", handle).put("goal", review.goal).put("mode", review.mode)
        .put("draftEpoch", review.draftEpoch).put("draftRevision", review.draftRevision)
        .put("composerKey", review.composerKey).put("composerEpoch", review.viewEpoch).put("draftConversationId", review.conversation)
        .also { identity -> review.linked?.view()?.let { identity.put("requestId", it.getString("requestId")).put("conversationId", it.getString("conversationId")) } }

    private fun consumePhoneDraft(receipt: JSONObject) {
        if (receipt.optString("status") == "accepted" && receipt.optLong("draftRevision", -1) == phoneDraftRevision &&
            receipt.optInt("draftEpoch", -1) == presentationEpoch && receipt.optInt("composerEpoch", -1) == controller.composerViewEpoch &&
            receipt.optString("composerKey") == controller.composerDraft?.key.orEmpty() &&
            receipt.optString("draftConversationId") == controller.state.conversationId.orEmpty() && phoneDraft.trim() == receipt.optString("goal").trim()) {
            phoneDraft = ""; phoneDraftRevision++
        }
    }

    private fun recordPhoneReceipt(original: JSONObject, result: JSONObject?, error: String?): JSONObject {
        val receipt = JSONObject(original.toString())
        val linked = original.has("requestId")
        val task = result?.optString("taskId")?.takeIf { it.isNotBlank() }
        val same = !linked || result != null && result.optString("requestId") == original.getString("requestId") &&
            result.optString("conversationId") == original.getString("conversationId") && task == original.getString("requestId")
        val rejected = error != null && result != null && result.optString("status") == "rejected" && !result.has("taskId") &&
            (!linked || result.optString("requestId") == original.getString("requestId") && result.optString("conversationId") == original.getString("conversationId"))
        val accepted = error == null && task != null && same && (!linked || result?.optBoolean("accepted") == true)
        if (same && task != null) receipt.put("taskId", task)
        if (same && result != null) {
            for (key in listOf("runId", "epoch", "admitted")) if (result.has(key)) receipt.put(key, result.get(key))
            if (result.has("status")) receipt.put("taskStatus", result.get("status"))
        }
        receipt.put("status", if (rejected) "rejected" else if (accepted) "accepted" else "unknown")
            .put("message", if (rejected) error + "。尚未创建手机任务，草稿仍保留；请明确重新审阅。" else if (accepted && linked) {
                if (result?.optBoolean("admitted") == true) "手机任务已获准执行，结果会回到原对话。" else "手机任务已保存到原对话；尚未确认获准执行，请查看原任务。"
            } else if (accepted) "原手机任务已创建。" else "启动结果待核对。请查询原请求，避免再次提交同一目标。")
        phoneStartReceipt = receipt
        consumePhoneDraft(receipt)
        return receipt
    }

    fun query(id: String, args: JSONObject) {
        checkedPayload(args, setOf("requestId", "conversationId"))
        val original = phoneStartReceipt?.let { JSONObject(it.toString()) } ?: error("没有可查询的原手机请求")
        require(original.optString("requestId") == requiredString(args, "requestId") && original.optString("conversationId") == requiredString(args, "conversationId")) { "请查询原手机启动回执中的请求" }
        controller.queryConversationPhoneStart(original.getString("requestId"), original.getString("conversationId")) { result, error ->
            // A late read remains about its original request and must not replace a newer receipt.
            if (phoneStartReceipt?.optString("requestId") == original.getString("requestId")) {
                complete(id, recordPhoneReceipt(original, result, error), null); publishState()
            } else complete(id, original, null)
        }
    }

    fun start(id: String, args: JSONObject) {
        checkedPayload(args, setOf("reviewHandle"))
        val handle = requiredString(args, "reviewHandle")
        val review = phoneReviews[handle] ?: error("原手机目标审阅已关闭或已使用")
        require(phoneReviewCurrent(review)) { "原对话、目标窗口或执行范围已变化，请重新核对" }
        require(!controller.state.phoneSubmitting && controller.state.activeTaskId == null) { "已有原工作正在处理，未重复开始" }
        val original = phoneReceiptIdentity(handle, review)
        val completed: (JSONObject?, String?) -> Unit = { result, error ->
            complete(id, recordPhoneReceipt(original, result, error), null)
            publishState()
        }
        val admitted = review.linked?.let { controller.startConversationPhone(it, { phoneReviewCurrent(review) }, completed) }
            ?: review.draft?.let { controller.startDraftPhone(it, { phoneReviewCurrent(review) }, completed) }
            ?: controller.startReviewedPhone(review.goal, review.mode, { phoneReviewCurrent(review) }, completed)
        if (admitted) {
            phoneReviews.remove(handle)
            phoneStartReceipt = original.put("status", "pending").put("message", "正在等待原手机任务的启动回执。")
        } else {
            complete(id, JSONObject().put("status", "rejected").put("message", controller.state.error ?: controller.state.notice ?: "这次任务尚未接纳，草稿仍保留。"), null)
        }
        publishState()
    }

    private fun checkedPayload(args: JSONObject, allowed: Set<String>): JSONObject {
        require(args.keys().asSequence().all { it in allowed }) { "操作包含未接入的参数" }
        return JSONObject(args.toString())
    }
    private fun requiredString(args: JSONObject, name: String, empty: Boolean = false): String {
        val value = args.opt(name); require(value is String && (empty || value.isNotBlank())) { "缺少有效参数 $name" }; return value
    }
    private fun requiredInt(args: JSONObject, name: String): Int {
        val value = args.opt(name); require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "缺少有效整数参数 $name" }; return value.toInt()
    }
    private fun requiredLong(args: JSONObject, name: String): Long {
        val value = args.opt(name); require((value is Int || value is Long) && (value as Number).toLong() >= 0) { "缺少有效修订参数 $name" }; return (value as Number).toLong()
    }
    private fun requiredBoolean(args: JSONObject, name: String): Boolean {
        val value = args.opt(name); require(value is Boolean) { "缺少有效开关参数 $name" }; return value
    }
}
