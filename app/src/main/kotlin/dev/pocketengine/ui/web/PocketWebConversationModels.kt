package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.runtime.models.ReasoningOptions
import dev.pocketengine.features.proxy.accounts.GatewayCall
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** Conversation-scoped parameter reviews. Neither choices nor resets change the global default. */
internal class PocketWebConversationModels(
    private val controller: PocketWorldController,
    private val ownerCurrent: () -> Boolean
) : AutoCloseable {
    private data class Review(val owner: Pair<Triple<Long, Int, Long>, Long>, val scope: String,
        val revision: Long, val selectionRevision: Long, val expires: Long, val generation: Long, val target: String, val ownerRevision: Long)
    private val gate = Any()
    private val reviews = LinkedHashMap<String, Review>()
    private val activeReviews = LinkedHashSet<GatewayCall>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var reviewWindowId: String? = null
    @Volatile private var generation = 0L
    @Volatile private var closed = false
    companion object {
        val COMMANDS = setOf("models.conversation.snapshot", "models.conversation.review", "models.conversation.reasoning",
            "models.conversation.group", "models.conversation.reset", "models.conversation.close", "models.conversation.reasoning.declare")
    }
    fun clear() { synchronized(gate) { generation++; reviewWindowId = null; reviews.clear(); activeReviews.forEach { it.cancel() }; activeReviews.clear() } }
    override fun close() { closed = true; clear() }
    private fun fields(args: JSONObject, vararg allowed: String) {
        require(args.keys().asSequence().all { it in allowed }) { "对话模型操作含不适用的字段" }
    }
    private fun number(args: JSONObject, key: String): Long {
        val value = args.opt(key)
        require((value is Int || value is Long) && (value as Number).toLong() >= 0) { "请重新读取原对话模型修订" }
        return (value as Number).toLong()
    }
    private fun windowId(args: JSONObject): String {
        val id = args.getString("windowId")
        require(id.matches(Regex("[A-Za-z0-9_-]{16,96}"))) { "参数窗口标识无效" }
        return id
    }
    private fun owner() = !closed && ownerCurrent() && controller.webPrivateOwnerVisible()
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?, String) -> Unit) {
        require(action in COMMANDS)
        check(owner()) { "请回到原私人对话选择模型" }
        if (action == "models.conversation.close") {
            fields(args, "windowId")
            val id = windowId(args)
            // A delayed cleanup belongs to its own picker, never a newly opened one.
            synchronized(gate) { if (reviewWindowId == id) clear() }
            done(JSONObject().put("closed", true), null, "not_accepted"); return
        }
        if (action == "models.conversation.snapshot") {
            fields(args)
            done(controller.conversationModelPreference(), null, "not_accepted"); return
        }
        val state = controller.state
        check(!state.chatSubmitting && !state.phoneSubmitting && !state.modelSaving && !state.modelTesting && !controller.modelSelectionPending()) { "请等待原输入或模型设置回执" }
        val scope = controller.conversationModelScope()
        val window = controller.webWindowTicket()
        if (action == "models.conversation.review") {
            fields(args, "windowId")
            val id = windowId(args)
            synchronized(gate) { clear(); reviewWindowId = id }
        }
        val originalGeneration = generation
        fun current() = owner() && generation == originalGeneration && controller.webWindowTicket() == window && controller.conversationModelScope() == scope
        if (action == "models.conversation.review") {
            val id = windowId(args)
            val before = controller.nativeModelSelection().status()
            val call = GatewayCall(60_000)
            synchronized(gate) { check(current()); activeReviews.add(call) }
            controller.runModelConnectionWork(Runnable {
                val result = runCatching {
                    check(current())
                    val review = controller.reviewConversationModel(call)
                    check(current() && before == controller.nativeModelSelection().status()) { "模型设置已变化，请重新核对" }
                    val handle = UUID.randomUUID().toString()
                    synchronized(gate) {
                        check(current())
                        reviews.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
                        check(reviews.size < 8) { "请关闭原参数窗口后重新读取" }
                        reviews[handle] = Review(window, scope, review.getLong("revision"), before.revision(), SystemClock.elapsedRealtime() + 120_000,
                            originalGeneration, review.getJSONObject("targetForReview").toString(), review.optLong("reasoningOwnerRevision", -1))
                    }
                    review.remove("targetForReview")
                    review.put("windowId", id).put("reviewHandle", handle).put("expiresInMs", 120_000).put("selectionRevision", before.revision())
                }
                synchronized(gate) { activeReviews.remove(call); call.close() }
                main.post {
                    if (!current()) done(null, "已结束这次参数读取", "read_superseded")
                    else result.fold({ done(PocketWebState.publicValue(it) as JSONObject, null, "not_accepted") }, { done(null, it.message ?: "此对话参数暂不可读取", "not_accepted") })
                }
            }); return
        }
        if (action == "models.conversation.reasoning.declare") {
            fields(args, "reviewHandle", "levels")
            val reviewed = synchronized(gate) { reviews.remove(args.getString("reviewHandle")) }
                ?: error("原推理审阅已使用或失效，请重新读取")
            check(reviewed.owner == window && reviewed.scope == scope && reviewed.generation == generation &&
                reviewed.expires > SystemClock.elapsedRealtime()) { "原对话推理审阅已过期" }
            val target = JSONObject(reviewed.target)
            check(target.getString("kind") == "api" && reviewed.ownerRevision >= 0) { "此模型不支持手动声明推理档位" }
            val binding = dev.pocketengine.runtime.models.ModelBinding.fromJson(target.getJSONObject("binding"))
            check(binding.connectionId() == target.getString("connectionId")) { "原推理来源绑定已变化" }
            val raw = args.getJSONArray("levels")
            require(raw.length() <= 8) { "推理档位数量过多" }
            val levels = (0 until raw.length()).map { index ->
                val level = raw.opt(index)
                require(level is String && level.length in 1..32) { "推理档位必须是有效名称" }
                level
            }
            val previous = ReasoningOptions.fromJson(target.getJSONObject("reasoning"))
            check(previous.mode() == ReasoningOptions.Mode.DEFAULT || previous.level() in levels) {
                "当前对话已使用一个档位，请先切换到服务默认再移除它"
            }
            val service = controller.nativeModelConnections() ?: error("模型来源尚未准备")
            controller.runModelSelectionWrite(reviewed.selectionRevision, {
                check(current() && scope == controller.conversationModelScope() &&
                    controller.conversationModelPreference().getLong("revision") == reviewed.revision) {
                    "原对话或推理设置已变化，请重新读取"
                }
                service.declareConnectionReasoning(binding, target.getLong("connectionRevision"),
                    target.getString("modelId"), reviewed.ownerRevision, levels)
            }) { result ->
                controller.modelConnectionChanged()
                if (!current()) done(null, "原对话窗口已变化，请核对当前模型配置；没有重试", "outcome_unknown")
                else result.fold({ done(PocketWebState.publicValue(it) as JSONObject, null, "not_accepted") },
                    { done(null, it.message ?: "推理档位保存结果待核对，请重新读取", "outcome_unknown") })
            }
            return
        }
        val expected: Long
        val selectionRevision: Long
        val target: JSONObject?
        if (action == "models.conversation.reasoning") {
            fields(args, "reviewHandle", "reasoning")
            val reviewed = synchronized(gate) { reviews.remove(args.getString("reviewHandle")) } ?: error("原推理审阅已使用或失效，请重新读取")
            check(reviewed.owner == window && reviewed.scope == scope && reviewed.generation == generation && reviewed.expires > SystemClock.elapsedRealtime()) { "原对话推理审阅已过期" }
            target = JSONObject(reviewed.target)
            check(target.getString("kind") != "group") { "请在 AI 中心管理模型组候选参数" }
            val reasoning = args.getJSONObject("reasoning")
            fields(reasoning, "mode", "level", "budgetTokens")
            target.put("reasoning", ReasoningOptions.fromJson(reasoning).toJson())
            expected = reviewed.revision; selectionRevision = reviewed.selectionRevision
        } else {
            if (action == "models.conversation.group") fields(args, "groupId", "expectedRevision", "expectedPreferenceRevision", "expectedSelectionRevision")
            else fields(args, "expectedPreferenceRevision", "expectedSelectionRevision")
            expected = number(args, "expectedPreferenceRevision"); selectionRevision = number(args, "expectedSelectionRevision")
            target = if (action == "models.conversation.group") JSONObject().put("kind", "group").put("groupId", args.getString("groupId")).put("groupRevision", number(args, "expectedRevision")) else null
        }
        controller.runModelSelectionWrite(selectionRevision, {
            check(current()) { "原对话已关闭，未保存排队操作" }
            controller.saveConversationModel(scope, expected, target)
        }) { result ->
            controller.modelConnectionChanged()
            if (!current()) done(null, "原对话窗口已变化，请核对实际模型设置；没有重试", "outcome_unknown")
            else result.fold({ done(it, null, "not_accepted") }, { done(null, it.message ?: "此对话参数保存结果待核对", "outcome_unknown") })
        }
    }
}
