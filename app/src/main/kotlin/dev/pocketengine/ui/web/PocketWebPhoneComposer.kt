package dev.pocketengine.ui.web

import android.content.Intent
import android.provider.Settings
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
    private val captureOwnerWindow: () -> PocketWebWindowTicket,
    private val acceptsOwnerWindow: (PocketWebWindowTicket) -> Boolean,
    private val complete: (String, JSONObject?, String?) -> Unit,
    private val publishState: () -> Unit
) {
    private data class PhoneReview(val goal: String, val mode: String, val tools: Int, val models: Int, val minutes: Int,
        val viewEpoch: Int, val expires: Long, val window: PocketWebWindowTicket, val composerKey: String,
        val conversation: String, val draftEpoch: Int, val draftRevision: Long, val reviewGeneration: Long, val originRevision: Long,
        val modelRevision: Long, val privateOwner: Boolean, val linked: EngineApplication.ConversationPhoneStartReview? = null,
        val draft: EngineApplication.DraftPhoneStartReview? = null, val conditions: JSONObject? = null)
    private val main = Handler(Looper.getMainLooper())
    private val phoneReviews = mutableMapOf<String, PhoneReview>()
    // Main-thread invalidation also revokes a review already captured by a queued worker.
    @Volatile private var reviewGeneration = 0L
    @Volatile private var presentationEpoch = 0
    private var phoneDraft = ""
    @Volatile private var picked: PhoneDraftMessagePin? = null
    @Volatile private var pinLost = false
    private var sourceEditTicket = 0L
    private fun draftHere() = !controller.hasChannelPhoneOrigin && controller.webPrivateOwnerVisible() &&
        picked?.conversation.let { it == null || it == controller.state.conversationId && it == controller.composerDraft?.conversationId }
    private fun pickedCurrent(pin: PhoneDraftMessagePin): Boolean {
        if(!draftHere()) return false
        val last = controller.state.messages.lastOrNull { it.role == "user" } ?: return false
        return last.id == pin.message && last.sourceRevision == pin.version && last.textOnlyHandoffEligible &&
            PhoneDraftMessagePin.from(last.ownerOriginalLocator) == pin
    }
    @Volatile private var phoneDraftRevision = 0L
    private var phoneIntent = false
    private var phoneExecutionMode = "background"
    private var phoneStartReceipt: JSONObject? = null

    val intent: Boolean get() = phoneIntent
    val draftRevision: Long get() = phoneDraftRevision

    fun clearReviews() { phoneReviews.clear(); reviewGeneration++ }
    fun documentChanged() { presentationEpoch++ }

    // A submitted private goal belongs to its original composer, not whichever chat is later on screen.
    private data class LinkedGoalOwner(val composer:String,val conversation:String)
    private var heldLinkedGoal:LinkedGoalOwner?=null
    private fun linkedOwner(original:JSONObject)=LinkedGoalOwner(original.optString("composerKey"),original.optString("conversationId"))
    private fun linkedHere(owner:LinkedGoalOwner)=owner.composer.isNotBlank() && owner.conversation.isNotBlank() &&
        !controller.hasChannelPhoneOrigin && controller.webPrivateOwnerVisible() && controller.state.conversationId==owner.conversation &&
        controller.composerDraft?.key==owner.composer && controller.composerDraft?.conversationId==owner.conversation
    private fun receiptHere(original: JSONObject) = when {
        original.has("draftRequestId") -> !controller.hasChannelPhoneOrigin && controller.webPrivateOwnerVisible() && controller.state.conversationId.isNullOrBlank() &&
            controller.composerDraft?.key==original.optString("composerKey") && controller.composerDraft?.conversationId.isNullOrBlank()
        original.has("requestId") -> linkedHere(linkedOwner(original))
        else -> true
    }
    private fun textElsewhere() = phoneDraft.isNotBlank() && (heldLinkedGoal?.let{!linkedHere(it)}==true ||
        phoneStartReceipt?.let {it.has("draftRequestId") && !receiptHere(it)}==true)
    private fun checkFirstOnly(original:JSONObject,goal:String)=(original.has("requestId") || original.has("draftRequestId")) && receiptHere(original) &&
        original.optString("status")=="accepted" && !original.optBoolean("admitted") && goal.isNotBlank() && original.optString("goal").trim()==goal.trim()
    fun snapshot(): JSONObject {
        val pin=picked
        val source=when { textElsewhere() -> "elsewhere"; pinLost && !draftHere() -> "elsewhere"; pinLost -> "unverified"; pin==null -> "none"; !draftHere() -> "elsewhere"; !pickedCurrent(pin) -> "changed"; else -> "current" }
        val receipt=phoneStartReceipt?.takeIf { source!="elsewhere" && receiptHere(it) && (!it.optBoolean("ownerPickedMessage") ||
            !controller.hasChannelPhoneOrigin && it.optString("draftConversationId")==controller.state.conversationId) }
        return JSONObject().put("text", if(source=="elsewhere") "" else phoneDraft)
        .put("sourceState",source).put("sourceEditTicket",sourceEditTicket).put("revision", phoneDraftRevision)
        .put("firstCheckOnly",receipt?.let{checkFirstOnly(it,phoneDraft)}==true)
        .put("epoch", presentationEpoch).put("phone", phoneIntent).put("mode", phoneExecutionMode).put("startReceipt", receipt).put("channelOrigin", controller.hasChannelPhoneOrigin)
    }

    fun appendVoice(text: String) {
        check(!textElsewhere() && !pinLost && (picked == null || draftHere())) { "另一段对话的原手机草稿仍保留，没有加入录音文字" }
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
        check(!textElsewhere() && (picked == null && !pinLost || draftHere()) && (!pinLost || text.isBlank())) { "所选原话属于其他窗口或来源已变化，原草稿没有被覆盖" }
        if(args.has("sourceEditTicket") || picked!=null || pinLost)check(requiredLong(args,"sourceEditTicket")==sourceEditTicket &&
            requiredString(args,"draftConversation",true)==controller.state.conversationId.orEmpty()) { "手机草稿来源已切换，未采用其他窗口的迟到输入" }
        if(text.isBlank()&&(picked!=null||pinLost)){picked=null;pinLost=false;++sourceEditTicket}
        if(text.isBlank())heldLinkedGoal=null
        phoneDraft = text; phoneDraftRevision++; clearReviews()
    }

    /** Owner-selected original text only. Staging never creates a task or grants phone access. */
    fun stageOriginalMessage(args: JSONObject): JSONObject {
        checkedPayload(args, setOf("conversationId", "messageId", "sourceRevision", "epoch", "revision", "keepDraft"))
        val keep = args.has("keepDraft") && requiredBoolean(args,"keepDraft")
        if(keep)check(draftHere() && phoneDraft.isNotBlank() && (pinLost || picked?.let { !pickedCurrent(it) }==true)) { "原草稿或当前来源已变化，未重新关联" }
        check(!isClosed() && controller.webPrivateOwnerVisible() && !controller.hasChannelPhoneOrigin) { "请回到原私人对话准备手机任务" }
        check(requiredInt(args, "epoch") == presentationEpoch && requiredLong(args, "revision") == phoneDraftRevision) { "手机草稿已变化，原文字仍保留" }
        val conversation = requiredString(args, "conversationId")
        check(conversation == controller.state.conversationId && controller.composerDraft?.conversationId == conversation) { "原对话已切换，没有复制其他对话的消息" }
        check(!controller.state.chatSubmitting && !controller.state.chatBusy && !controller.state.phoneSubmitting) { "请先等原输入或回复结束；没有替换这次输入" }
        check(phoneStartReceipt?.optString("status") !in setOf("pending", "unknown")) { "原手机启动结果待核对，请先查询原请求" }
        val id = requiredString(args, "messageId")
        val message = controller.state.messages.firstOrNull { it.id == id } ?: error("原消息已不在当前对话，请重新打开")
        check(controller.state.messages.lastOrNull { it.role == "user" }?.id == id) { "请从最新主人请求准备手机任务，避免恢复已被后续要求替代的目标" }
        check(message.role == "user" && message.sourceRevision > 0 && message.sourceRevision == requiredLong(args, "sourceRevision")
            && message.ownerOriginalLocator?.optString("conversationId") == conversation) { "请从这段私人对话中的原主人消息准备手机任务" }
        check(message.textOnlyHandoffEligible && message.attachmentCount == 0 && message.attachments.isEmpty() && message.attachmentFiles.isEmpty()) { "这条请求含附件或来源/预览引用，或尚未核实完整资料，不能只复制文字；请在手机目标中整理完整任务、联系人和内容" }
        check(!textElsewhere()) { "另一份新对话仍保留原手机目标，没有替换它" }
        val goal = if(keep) phoneDraft.trim() else message.content.trim()
        require(goal.isNotEmpty() && goal.length <= 4000) { "手机任务目标需为 1–4000 字；请自行整理完整目标，不会截断原请求" }
        check(phoneDraft.isBlank() || phoneDraft.trim() == goal) { "已有另一份手机草稿，请先保留或处理；没有覆盖原文字" }
        val pin = requireNotNull(PhoneDraftMessagePin.from(message.ownerOriginalLocator)) { "这条原话没有可核对的原件" }
        check(pin.conversation == conversation && pin.message == id && pin.version == message.sourceRevision &&
            (keep || !pinLost && (picked == null || picked == pin))) { "保留的手机草稿来自另一条原话，未把相同文字当成同一条消息" }
        picked = pin;pinLost=false;++sourceEditTicket
        if(!keep)phoneDraft = goal
        phoneDraftRevision++
        phoneIntent = true
        phoneStartReceipt = null;heldLinkedGoal=null // the explicitly selected original message now owns this text
        clearReviews()
        return snapshot().put("staged", true).put("taskCreated", false).put("executionStarted", false)
            .put("message", if(keep) "草稿文字未修改，已关联你刚选的这条原话。仍须审阅后才会提交。" else "原请求已放入手机目标草稿。核对完整目标、联系人和内容，再审阅执行位置与范围；尚未启动或发送。")
    }

    fun prepare(id: String, args: JSONObject) {
        checkedPayload(args, setOf("goal", "mode", "epoch", "originalDraft"))
        require(controller.webPrivateOwnerVisible() && requiredInt(args, "epoch") == presentationEpoch) { "原手机输入窗口已变化，请重新核对保留草稿" }
        val goal = requiredString(args, "goal"); val mode = requiredString(args, "mode")
        require(goal.trim().isNotEmpty() && goal.trim().length <= 4000 && mode in setOf("background", "main_screen")) { "请填写 1–4000 字的手机任务目标，并选择执行位置" }
        require(phoneDraft.trim() == goal.trim() && phoneIntent && phoneExecutionMode == mode) { "原手机草稿或执行位置已变化，请重新核对" }
        if (args.has("originalDraft")) {
            val expected = args.getJSONObject("originalDraft")
            check(!controller.hasChannelPhoneOrigin && requiredString(expected, "composer", true) == controller.composerDraft?.key.orEmpty()
                && requiredString(expected, "conversation", true) == controller.state.conversationId.orEmpty()
                && requiredInt(expected, "ownerEpoch") == controller.composerViewEpoch && requiredLong(expected, "revision") == phoneDraftRevision
                && requiredLong(expected, "origin") == controller.phoneOriginRevision) { "原手机草稿已变化，请返回核对原目标；没有准备其他任务" }
        }
        val channel = controller.hasChannelPhoneOrigin
        require(!controller.state.phoneSubmitting && (!channel || controller.state.activeTaskId == null)) { "原输入仍在提交或渠道工作正占用，未创建手机任务" }
        check(!textElsewhere()) { "请回到保存手机目标的原草稿；没有替换原请求" }
        val prior=phoneStartReceipt
        if(prior!=null && receiptHere(prior)){
            check(!checkFirstOnly(prior,goal)){"这段原目标已经建单，请查询这次启动。编辑成新目标后仍可重新审阅。"}
            check(prior.optString("status") !in setOf("pending","unknown") || !prior.has("requestId") && !prior.has("draftRequestId") && prior.optString("goal").trim()!=goal.trim()){"原手机请求的启动结果待核对，请先查询原请求。草稿仍保留。"}
            check(!(prior.has("draftRequestId") && prior.optString("status")=="accepted" && !prior.optBoolean("admitted") && prior.optString("goal").trim()==goal.trim())){"这个目标已有原任务，请打开该任务；没有重复提交。"}
        }
        clearReviews()
        val handle = UUID.randomUUID().toString()
        val conversation = controller.state.conversationId.orEmpty()
        val pin=picked
        check(!pinLost && (pin == null || !channel && pin.conversation == conversation && pickedCurrent(pin))) { "所选的原请求已换版、移出窗口或不再是最新交代。原草稿保留，没有关联其他消息" }
        val modelScope = if (channel) null else controller.conversationModelScope()
        val modelStatus = controller.nativeModelSelection().status()
        check(!modelStatus.pending()) { "请等待原模型设置回执" }
        val original = PhoneReview(goal, mode, controller.newTaskToolBudget,
            controller.newTaskModelBudget, controller.newTaskScopeMinutes,
            controller.composerViewEpoch, SystemClock.elapsedRealtime() + 120_000L, if (channel) captureWindow() else captureOwnerWindow(),
            controller.composerDraft?.key.orEmpty(), conversation, presentationEpoch, phoneDraftRevision, reviewGeneration, controller.phoneOriginRevision, modelStatus.revision(), !channel)
        // Account refresh/model resolution may use the network. Keep it off the WebView's main thread.
        controller.runModelConnectionWork(Runnable {
            val prepared = runCatching {
                check(phoneReviewCurrent(original)) { "原手机草稿或模型设置已变化，请重新核对" }
                when {
                    channel -> original
                    conversation.isNotBlank() -> original.copy(linked = controller.prepareConversationPhoneStart(conversation, goal, mode, pin?.locator()))
                    else -> original.copy(draft = controller.prepareDraftPhoneStart(modelScope!!, goal, mode))
                }.let { review ->
                    if (channel) review else review.copy(conditions = readConditions(mode))
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

    // A preflight is not an observation. Publish no session ids, screen data or operation grant.
    private fun readConditions(mode: String): JSONObject {
        val source = runCatching { controller.reviewedPhoneConditions() }.getOrNull()
        val notification = source?.optJSONObject("taskNotificationsForStart")
        val notificationIds = checks(notification).map { it.optString("id") }.toSet()
        val screen = source?.optJSONObject(if (mode == "main_screen") "mainPreparation" else "backgroundPreparation")
        return JSONObject().put("notification", condition(notification, "notifications"))
            .put("screen", condition(screen, "accessibility", notificationIds))
            .put("capacity", readCapacity(mode))
    }

    private fun readCapacity(mode: String): JSONObject = runCatching { controller.reviewedPhoneCapacity(mode) }.getOrElse {
        JSONObject().put("state", "unknown").put("mode", mode).put("message", "本机执行位置暂时无法核实，请重新核对。")
    }

    private fun checks(group: JSONObject?): List<JSONObject> {
        val items = group?.optJSONArray("checks") ?: return emptyList()
        return (0 until items.length()).mapNotNull(items::optJSONObject)
    }

    private fun condition(group: JSONObject?, settings: String, excludedIds: Set<String> = emptySet()): JSONObject {
        fun unknown() = JSONObject().put("state", "unknown")
        // backgroundPreparation also contains the notification gate. Report each prerequisite once.
        val rows = checks(group).filterNot { it.optString("id") in excludedIds }
        if (rows.isEmpty()) return unknown()
        val issue = (if (settings == "notifications") rows.firstOrNull { it.optString("state") == "blocked" } else null)
            ?: rows.firstOrNull { it.optString("state") != "ready" }
        if (issue == null) return if (excludedIds.isNotEmpty() || group?.optBoolean("ready") == true && group.optString("state") == "ready")
            JSONObject().put("state", "ready") else unknown()
        val state = if (issue.optString("state") == "blocked") "blocked" else "unknown"
        return JSONObject().put("state", state).put("message", issue.optString("message")).also { view ->
            val action = issue.optString("action")
            // Keep the native non-authorizing route for this exact failed background check.
            // openReviewedSettings still accepts only the two Android settings targets.
            val place = settings == "accessibility" && when (issue.optString("id")) {
                "backgroundConnected", "backgroundClean" -> action == "background_setup"
                "backgroundSupported" -> action == "main_screen_choice"
                else -> false
            }
            if (state == "blocked" && (action == settings || place)) view.put("settings", action)
        }
    }

    /** Only an explicit click on the specific remedy may leave the original private review. */
    fun openReviewedSettings(args: JSONObject): JSONObject {
        checkedPayload(args, setOf("reviewHandle", "target"))
        val review = phoneReviews[requiredString(args, "reviewHandle")]
        check(review != null && !controller.hasChannelPhoneOrigin && phoneReviewCurrent(review)) { "原手机任务核对已失效，请重新核对；草稿仍保留" }
        val conditions = checkNotNull(review.conditions)
        val notification = conditions.getJSONObject("notification")
        val issue = if (notification.optString("state") != "ready") notification else conditions.getJSONObject("screen")
        val target = requiredString(args, "target")
        check(issue.optString("settings") == target && issue.optString("state") == "blocked") { "请从原任务当前缺少的条件打开设置" }
        val activity = controller.webPresentationActivity()
        when (target) {
            "notifications" -> activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            "accessibility" -> controller.openAccessibility()
            else -> error("本次核对不支持这个设置入口")
        }
        clearReviews()
        return JSONObject().put("status", "settings_opened")
    }

    private fun reviewView(handle: String, review: PhoneReview): JSONObject {
        val shown = review.linked?.view() ?: review.draft?.view()
        return (shown ?: JSONObject()).put("reviewHandle", handle).put("goal", review.goal).put("mode", review.mode)
            .put("toolLimit", review.tools).put("modelCallLimit", review.models).put("scopeMinutes", review.minutes)
            .put("originalDraft", review.conditions?.let { JSONObject().put("composer", review.composerKey).put("conversation", review.conversation)
                .put("ownerEpoch", review.viewEpoch).put("revision", review.draftRevision).put("origin", review.originRevision) })
            .put("conditions", review.conditions).put("expiresInMs", (review.expires - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            .put("screenDisclosure", shown?.optString("screenDisclosure")?.takeIf { it.isNotBlank() } ?: "本次未取得分项读取设置。执行时允许的屏幕内容可能提供给对应模型；当前没有据此读取或发送画面。")
            .put("message", when {
                review.conditions != null -> if (review.mode == "background") "任务需要用屏时，只使用后台独立屏，不切换到手机主屏。" else "任务需要用屏时，才会使用手机主屏。"
                review.mode == "background" -> "在后台独立屏处理这个目标；连接不可用时等待，不切换到手机主屏。"
                else -> "在手机主屏处理这个目标，会使用当前屏幕。"
            })
    }

    /** Equivalent to the former Activity saveable presentation state, never an execution grant. */
    fun savePresentation(): JSONObject = JSONObject().put("phoneDraft", phoneDraft)
        .put("phoneDraftRevision", phoneDraftRevision).put("phoneIntent", phoneIntent).put("phoneExecutionMode", phoneExecutionMode)
        .put("phoneStartReceipt", phoneStartReceipt).put("phoneSourceFormat",1).put("privateGoalOwnerFormat",1)
        .also { out->heldLinkedGoal?.let{out.put("privateGoalOwner",JSONObject().put("composerKey",it.composer).put("conversationId",it.conversation))} }
        .also { out -> picked?.let { out.put("phoneDraftOriginal", it.locator()) }; if(pinLost)out.put("phoneSourceLost",true) }

    fun restorePresentation(saved: JSONObject?) {
        if (saved == null) return
        val text = saved.optString("phoneDraft")
        if (text.length <= 12000) phoneDraft = text
        ++sourceEditTicket;picked=PhoneDraftMessagePin.from(saved.optJSONObject("phoneDraftOriginal"))
        pinLost=phoneDraft.isNotBlank() && (saved.optBoolean("phoneSourceLost") || saved.has("phoneDraftOriginal") && picked==null ||
            !saved.has("phoneSourceFormat") && !saved.has("phoneDraftOriginal"))
        phoneDraftRevision = saved.optLong("phoneDraftRevision").coerceAtLeast(0)
        phoneIntent = saved.optBoolean("phoneIntent")
        phoneExecutionMode = saved.optString("phoneExecutionMode").takeIf { it in setOf("background", "main_screen") } ?: "background"
        phoneStartReceipt = saved.optJSONObject("phoneStartReceipt")?.let { JSONObject(it.toString()) }
        heldLinkedGoal=if(phoneDraft.isBlank())null else {
            saved.optJSONObject("privateGoalOwner")?.let(::linkedOwner) ?:when {
                saved.has("privateGoalOwner") -> LinkedGoalOwner("","") // malformed state is never a cross-chat fallback
                !saved.has("privateGoalOwnerFormat") -> phoneStartReceipt?.takeIf{it.has("requestId")&&!it.optBoolean("admitted")}?.let(::linkedOwner)
                else -> null
            }
        }
        // Restoring a view cannot prove whether its previous worker completed.
        if (phoneStartReceipt?.optString("status") == "pending") phoneStartReceipt?.put("status", "unknown")
    }

    private fun phoneReviewCurrent(review: PhoneReview): Boolean = phoneIntent && phoneExecutionMode == review.mode && review.reviewGeneration == reviewGeneration &&
        review.originRevision == controller.phoneOriginRevision && !pinLost && picked.let { it == null || pickedCurrent(it) } &&
        !isClosed() && controller.webPrivateOwnerVisible() &&
        review.privateOwner == !controller.hasChannelPhoneOrigin && SystemClock.elapsedRealtime() < review.expires &&
        (if (review.privateOwner) acceptsOwnerWindow(review.window) else acceptsWindow(review.window)) &&
        review.viewEpoch == controller.composerViewEpoch && review.composerKey == controller.composerDraft?.key.orEmpty() &&
        review.conversation == controller.state.conversationId.orEmpty() && review.draftEpoch == presentationEpoch && review.draftRevision == phoneDraftRevision &&
        !controller.nativeModelSelection().status().pending() && review.modelRevision == controller.nativeModelSelection().status().revision() &&
        review.tools == controller.newTaskToolBudget && review.models == controller.newTaskModelBudget && review.minutes == controller.newTaskScopeMinutes && review.reviewGeneration == reviewGeneration &&
        review.originRevision == controller.phoneOriginRevision

    private fun phoneReceiptIdentity(handle: String, review: PhoneReview): JSONObject = JSONObject()
        .put("reviewHandle", handle).put("goal", review.goal).put("mode", review.mode)
        .put("draftEpoch", review.draftEpoch).put("draftRevision", review.draftRevision)
        .put("composerKey", review.composerKey).put("composerEpoch", review.viewEpoch).put("draftConversationId", review.conversation)
        .also { identity ->
            review.linked?.view()?.let { identity.put("requestId", it.getString("requestId")).put("conversationId", it.getString("conversationId")).put("ownerPickedMessage",it.optBoolean("ownerPickedMessage")) }
            review.draft?.view()?.let { identity.put("draftRequestId",it.getString("draftRequestId")) }
        }

    private fun consumePhoneDraft(receipt: JSONObject) {
        if (receipt.optString("status") == "accepted" && (!receipt.has("draftRequestId") && !receipt.has("requestId") || receipt.optBoolean("admitted")) && receipt.optLong("draftRevision", -1) == phoneDraftRevision &&
            receipt.optInt("draftEpoch", -1) == presentationEpoch && receipt.optInt("composerEpoch", -1) == controller.composerViewEpoch &&
            receipt.optString("composerKey") == controller.composerDraft?.key.orEmpty() &&
            receipt.optString("draftConversationId") == controller.state.conversationId.orEmpty() && phoneDraft.trim() == receipt.optString("goal").trim()) {
            phoneDraft = "";heldLinkedGoal=null; if(picked!=null||pinLost)++sourceEditTicket;picked=null;pinLost=false;phoneDraftRevision++
        }
    }

    private fun recordPhoneReceipt(original: JSONObject, result: JSONObject?, error: String?): JSONObject {
        val receipt = JSONObject(original.toString())
        val linked=original.has("requestId");val blank=original.has("draftRequestId")
        val task=result?.optString("taskId")?.takeIf { it.isNotBlank() }
        val matches=when {
            linked -> result!=null && result.optString("requestId")==original.getString("requestId") && result.optString("conversationId")==original.getString("conversationId")
            blank -> result!=null && result.optString("draftRequestId")==original.getString("draftRequestId")
            else -> true
        }
        val same=matches && (task==null || !linked && !blank || task==(if(blank)original.getString("draftRequestId") else original.getString("requestId")))
        val retired=blank && same && error==null && result?.optString("status")=="retired" && task==null
        val rejected=retired || same && error!=null && result?.optString("status")=="rejected" && task==null
        val absent=blank && same && error==null && result!=null && result.optString("status")=="not_found" && result.optBoolean("queryOnly")
        val accepted=error==null && task!=null && same && (!linked && !blank || result?.optBoolean("accepted")==true)
        // An allocation seen before an uncertain SQL write is not proof that a task is available to open.
        if(blank||linked)for(key in listOf("taskId","runId","epoch","admitted","admissionState","taskStatus","canRetire","firstRunRecord","firstRunReadUnconfirmed"))receipt.remove(key)
        if(same && task!=null && (!blank && !linked || accepted))receipt.put("taskId",task)
        if(same && result!=null && (!blank && !linked || accepted)){
            for(key in listOf("runId","epoch","admitted"))if(result.has(key))receipt.put(key,result.get(key))
            if((linked || blank) && result.optString("admissionState") in setOf("admitted","service_denied","component_unconfirmed","run_recorded","no_run","unconfirmed"))
                receipt.put("admissionState",result.getString("admissionState"))
            if(result.has("status"))receipt.put("taskStatus",result.get("status"))
            if(blank && result.opt("queryOnly")==true && result.opt("epoch")==1){
                val first=result.optJSONObject("firstRunRecord")
                if(first!=null && first.opt("format")=="pocketworld.draft-first-run-status.v1" && first.opt("taskId")==task &&
                    first.opt("runId")==result.opt("runId") && first.opt("epoch")==1 && first.opt("status")==result.opt("status"))
                    receipt.put("firstRunRecord",first)
                else receipt.put("firstRunReadUnconfirmed",true)
            }
        }
        if(absent)receipt.put("canRetire",true)
        if(rejected && !retired && result?.optBoolean("capacityChanged") == true)
            receipt.put("recheckReview", original.getString("reviewHandle"))
        receipt.put("status",if(rejected)"rejected" else if(accepted)"accepted" else "unknown").put("message",when{
            retired -> "已关闭这次未建成的请求。旧编号不会再创建任务；目标文字仍保留。"
            rejected && receipt.has("recheckReview") -> error ?: "原执行位置已变化，请重新核对。"
            rejected -> (error?:"这次启动未被接纳")+"。尚未创建手机任务，草稿仍保留；请明确重新审阅。"
            accepted && blank -> when(result?.optString("admissionState")){
                "service_denied" -> "这张草稿已有首轮登记，但 Android 明确拒绝了原服务启动。目标文字保留，没有再次提交。"
                "component_unconfirmed" -> "首轮已登记，系统没有返回原服务组件，不能确认启动。目标文字仍在原草稿。"
                "admitted" -> "找到了本次手机任务及原首次准入回执，不代表已完成。"
                "run_recorded" -> "已保存原首次执行轮次，尚未确认 Android 已接收启动请求；不代表已经读屏或操作。"
                "no_run" -> "这项旧手机目标已保存，但没有执行轮次或可继续的原授权。"
                else -> "本次手机任务已保存，未能确认原首次准入。"
            }
            accepted && linked -> when(result?.optString("admissionState")){
                "admitted" -> "Android 已接纳原私聊首次执行的服务请求；不代表已经读屏、操作或完成。"
                "service_denied" -> "Android 明确拒绝了这次原服务启动请求。目标文字保留，没有重试。"
                "component_unconfirmed" -> "系统没有返回这次请求的原服务组件，不能确认已启动。目标文字保留，没有重试。"
                "run_recorded" -> "查到原私聊的首次执行登记，尚无可核对的 Android 服务接纳回执。"
                "no_run" -> "原私聊的手机目标已保存，但没有首次执行记录；没有重新启动。"
                else -> "已核对原私聊的手机目标，首次准入记录仍不可确认。"
            }
            accepted -> "原手机任务已创建。"
            absent -> "暂未查到这个请求号的任务。可以稍后再查，或关闭这个未建成的请求；不会自动重试。"
            else -> "启动结果待核对。请查询原请求，避免再次提交同一目标。"
        })
        phoneStartReceipt = receipt
        consumePhoneDraft(receipt)
        return receipt
    }

    // At most the five ids actually shown in the original private draft's recent-creation list.
    private var shownRecentScope:Triple<String,Int,Long>?=null
    private val shownRecentFirst=LinkedHashMap<String,String?>()
    private fun rememberFirst(row:JSONObject) {
        val request=row.optString("draftRequestId")
        if(row.opt("accepted")!=true||request.isBlank()||row.optString("taskId")!=request)return
        shownRecentFirst[request]=row.optString("runId").takeIf{it.isNotBlank()&&row.optInt("epoch",-1)==1&&row.optString("admissionState") in setOf("admitted","run_recorded","service_denied","component_unconfirmed")}
    }
    /** A draft creation is not a run. Read its immutable first pairing rather than today's task header. */
    fun openFirstDraftRun(id:String,args:JSONObject, originalWindow:()->Boolean) {
        checkedPayload(args,setOf("composer","draftRequestId","runId","epoch"))
        val composer=requiredString(args,"composer");val request=requiredString(args,"draftRequestId");val run=requiredString(args,"runId")
        require(requiredInt(args,"epoch")==1) { "原草稿只关联首次执行，没有转到其他轮次" }
        val shownAt=presentationEpoch
        fun current() = originalWindow() && phoneIntent && shownAt==presentationEpoch && controller.webPrivateOwnerVisible() &&
            !controller.hasChannelPhoneOrigin && controller.state.conversationId.isNullOrBlank() && controller.composerDraft?.key==composer
        val receipt=phoneStartReceipt
        val fromReceipt=receipt!=null && receiptHere(receipt) && receipt.optString("status")=="accepted" &&
            receipt.optString("draftRequestId")==request && receipt.optString("taskId")==request && receipt.optString("runId")==run && receipt.optInt("epoch",-1)==1
        val fromRecent=shownRecentScope==Triple(composer,presentationEpoch,controller.displayedPhoneWindowVersion()) && shownRecentFirst[request]==run
        require(current() && (fromReceipt||fromRecent)) { "请回原私人草稿，重新查看这条首次轮次记录" }
        val serial=controller.reserveFirstPhoneRead()
        controller.lookupDraftPhone(request,composer,false,false,{current() && controller.firstPhoneReadCurrent(serial)}) { row,error ->
            if(error!=null||!current()||!controller.firstPhoneReadCurrent(serial)||row==null||row.opt("accepted")!=true||
                row.optString("taskId")!=request||row.optString("draftRequestId")!=request||row.optString("runId")!=run||row.optInt("epoch",-1)!=1||
                row.optString("admissionState") !in setOf("admitted","run_recorded","service_denied","component_unconfirmed")) {
                complete(id,null,"原草稿没有这次可核对的首次轮次，没有打开后继任务")
            } else {
                val locator=JSONObject().put("taskId",request).put("runId",run).put("epoch",1).put("returnSource","blank_draft").put("draftRequestId",request)
                controller.readPhoneNotification(locator,activityWindow=::current)
                complete(id,JSONObject().put("reading",true),null)
            }
        }
    }

    fun recentDraft(id:String,args:JSONObject){
        checkedPayload(args,setOf("composer","details","draftRequestId"));val composer=requiredString(args,"composer")
        val request=if(args.has("draftRequestId"))requiredString(args,"draftRequestId") else null
        val details=if(args.has("details"))requiredBoolean(args,"details") else false
        require(request==null||!details){"请核对一个原请求"}
        check(phoneIntent && controller.composerDraft?.key==composer){"请回到原手机草稿"}
        val window=captureOwnerWindow();val epoch=presentationEpoch
        controller.lookupDraftPhone(request,composer,false,details,{phoneIntent && epoch==presentationEpoch && acceptsOwnerWindow(window)}) { result,error ->
            if(error==null && result!=null){
                val scope=Triple(composer,epoch,controller.displayedPhoneWindowVersion())
                if(request==null){
                    shownRecentFirst.clear();shownRecentScope=scope
                    if(details)result.optJSONArray("rows")?.let { rows -> for(i in 0 until minOf(5,rows.length()))rows.optJSONObject(i)?.let(::rememberFirst) }
                }else if(shownRecentScope==scope && shownRecentFirst.containsKey(request)){
                    shownRecentFirst[request]=null;if(result.optString("draftRequestId")==request)rememberFirst(result)
                }
            }
            complete(id,result,error)
        }
    }
    fun query(id: String, args: JSONObject, retire: Boolean = false) {
        val original=phoneStartReceipt?.let {JSONObject(it.toString())} ?: error("没有可查询的原手机请求")
        val privateRead = !retire && !controller.hasChannelPhoneOrigin && (original.has("draftRequestId") || original.has("requestId"))
        val window=if(privateRead)captureOwnerWindow() else captureWindow();val epoch=presentationEpoch
        fun current() = !isClosed() && epoch==presentationEpoch && controller.webPrivateOwnerVisible() &&
            (if(privateRead) !controller.hasChannelPhoneOrigin && acceptsOwnerWindow(window) else acceptsWindow(window)) &&
            phoneStartReceipt?.optString("reviewHandle")==original.optString("reviewHandle")
        if(original.has("draftRequestId")){
            checkedPayload(args,if(retire)setOf("draftRequestId","confirmed") else setOf("draftRequestId"))
            val request=requiredString(args,"draftRequestId");require(current() && receiptHere(original) && original.getString("draftRequestId")==request){"请查询原草稿中的手机请求"}
            if(retire)require(requiredBoolean(args,"confirmed") && original.optBoolean("canRetire") && original.optString("status")=="unknown"){"先查询原请求；已有任务不会被关闭"}
            controller.lookupDraftPhone(request,original.getString("composerKey"),retire,false,{current() && receiptHere(original)}) { result,error ->
                if(current() && receiptHere(original)){
                    val settled=phoneStartReceipt?.takeIf {it.optString("status")=="accepted" &&
                        it.optString("reviewHandle")==original.optString("reviewHandle") && it.optString("draftRequestId")==request &&
                        (it.optBoolean("admitted") && result?.optBoolean("admitted")!=true || keepBlankSystemResult(it,result))}
                    val retained=settled?.let { JSONObject(it.toString()).put("firstRunReadUnconfirmed",true) }
                    if(retained!=null)phoneStartReceipt=retained
                    complete(id,retained?:recordPhoneReceipt(original,result,error),null);publishState()
                }
                else complete(id,null,"原草稿窗口已变化")
            };return
        }
        check(!retire){"不能关闭其他来源的手机请求"}
        checkedPayload(args,setOf("requestId","conversationId"))
        require(original.optString("requestId")==requiredString(args,"requestId") && original.optString("conversationId")==requiredString(args,"conversationId")){"请查询原手机启动回执中的请求"}
        require(receiptHere(original) && current()){"请从原对话查询手机任务"}
        controller.queryConversationPhoneStart(original.getString("requestId"),original.getString("conversationId")) { result,error ->
            if(current() && receiptHere(original)){
                if(error!=null||result==null)complete(id,null,"这次没有取得原启动的新回执，未提交新任务")
                else {
                val known=phoneStartReceipt?.takeIf { it.optString("reviewHandle")==original.optString("reviewHandle") && it.optString("status")=="accepted" &&
                    it.optString("admissionState") in setOf("admitted","service_denied","component_unconfirmed") && result.optString("admissionState")=="run_recorded" &&
                    result.optString("requestId")==it.optString("requestId") && result.optString("conversationId")==it.optString("conversationId") &&
                    result.optString("runId")==it.optString("runId") && result.optInt("epoch",-1)==1 && it.optInt("epoch",-1)==1 }
                complete(id,known?:recordPhoneReceipt(original,result,null),null);publishState()
                }
            } else complete(id,null,"原对话窗口已变化")
        }
    }

    private fun knownBlankSystemResult(row:JSONObject?) = row!=null && row.optString("draftRequestId").isNotBlank() &&
        row.optString("taskId")==row.optString("draftRequestId") && row.optString("runId").isNotBlank() && row.optInt("epoch",-1)==1 &&
        when(row.optString("admissionState")){
            "admitted" -> row.opt("admitted")==true
            "service_denied" -> row.opt("admitted")==false
            "component_unconfirmed" -> row.opt("admitted")!=true
            else -> false
        }
    private fun keepBlankSystemResult(saved:JSONObject,next:JSONObject?) = knownBlankSystemResult(saved) &&
        (next==null || !knownBlankSystemResult(next) || listOf("draftRequestId","taskId","runId","admissionState").any { saved.optString(it)!=next.optString(it) })

    fun start(id: String, args: JSONObject) {
        checkedPayload(args, setOf("reviewHandle"))
        val handle = requiredString(args, "reviewHandle")
        val review = phoneReviews[handle] ?: error("原手机目标审阅已关闭或已使用")
        require(phoneReviewCurrent(review)) { "原对话、目标窗口或执行范围已变化，请重新核对" }
        val privateRequest = review.privateOwner && (review.linked != null || review.draft != null)
        require(!controller.state.phoneSubmitting && (privateRequest || controller.state.activeTaskId == null)) { "原输入仍在提交或渠道工作正占用，未重复开始" }
        val original = phoneReceiptIdentity(handle, review)
        val capacity = review.conditions?.optJSONObject("capacity")
        if (privateRequest && capacity?.optString("state") != "ready") {
            phoneReviews.remove(handle)
            complete(id, JSONObject().put("status", "rejected").put("draftRequestId", original.opt("draftRequestId"))
                .put("recheckReview", handle).put("message", "原执行位置尚未核实可用。目标还在，没有提交。"), null)
            publishState()
            return
        }
        if(review.linked!=null&&phoneDraft.isNotBlank())heldLinkedGoal=linkedOwner(original)
        val completed: (JSONObject?, String?) -> Unit = { result, error ->
            val retained=phoneStartReceipt
            val knownChat=original.has("requestId") && retained?.optString("status")=="accepted" &&
                retained.optString("admissionState") in setOf("admitted","service_denied","component_unconfirmed")
            if(retained?.optString("reviewHandle")==handle && knownChat && (error!=null||result?.optString("admissionState")!=retained.optString("admissionState"))){
                if(receiptHere(retained))complete(id,retained,null) else complete(id,null,"这次启动回执保留在原私聊")
            } else if(retained?.optString("reviewHandle")==handle && original.has("draftRequestId") && retained.optString("status") in setOf("accepted","rejected") && (keepBlankSystemResult(retained,result) || !knownBlankSystemResult(result) && (retained.optBoolean("admitted") || result?.optBoolean("admitted")!=true))){
                if(receiptHere(retained))complete(id,retained,null) else complete(id,null,"原手机回执留在发起草稿")
            } else if(retained?.optString("reviewHandle")==handle) {
                val receipt=recordPhoneReceipt(original,result,error)
                if(receiptHere(receipt))complete(id,receipt,null) else complete(id,null,"原手机回执留在发起草稿")
                publishState()
            } else complete(id,null,"原启动窗口已变化，未覆盖已核对的回执")
        }
        val admitted = review.linked?.let { controller.startConversationPhone(it, { phoneReviewCurrent(review) }, capacity, completed) }
            ?: review.draft?.let { controller.startDraftPhone(it, { phoneReviewCurrent(review) }, capacity, completed) }
            ?: controller.startReviewedPhone(review.goal, review.mode, { phoneReviewCurrent(review) }, completed)
        if (admitted) {
            phoneReviews.remove(handle)
            phoneStartReceipt = original.put("status", "pending").put("message", "正在等待原手机任务的启动回执。")
        } else {
            // The controller declined before dispatching its private start worker. No task was submitted.
            if (review.conditions != null) phoneReviews.remove(handle)
            complete(id, JSONObject().put("status", "rejected").put("draftRequestId",original.opt("draftRequestId")).put("recheckReview", handle.takeIf { review.conditions != null })
                .put("message", controller.state.error ?: controller.state.notice ?: "这次任务尚未接纳，草稿仍保留。"), null)
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
