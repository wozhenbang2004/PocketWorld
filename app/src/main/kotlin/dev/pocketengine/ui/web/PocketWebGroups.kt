package dev.pocketengine.ui.web

import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.groups.GroupUiState
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Presentation-only reviews. The original GroupRegistry/TaskStore still owns every mutation. */
internal class PocketWebGroupReviews(private val now: () -> Long = System::currentTimeMillis) {
    data class Review(val command: String, val input: JSONObject, val group: JSONObject?, val connection: JSONObject?, val window: String, val expires: Long)
    private val pending = linkedMapOf<String, Review>()

    companion object {
        val capabilities = setOf("public.answer", "public.search", "group.files", "media.image", "media.audio", "media.video")
        fun fields(value: JSONObject, vararg allowed: String) {
            require(value.keys().asSequence().all { it in allowed }) { "群操作包含不适用的字段" }
        }
        fun text(value: JSONObject, key: String, max: Int = 240): String {
            val raw = value.opt(key)
            require(raw is String && raw.isNotBlank() && raw.length <= max && '\u0000' !in raw) { "请核对$key" }
            return raw.trim()
        }
        fun id(value: JSONObject, key: String): String = text(value, key).also {
            require(it.matches(Regex("[A-Za-z0-9_:.@+\\-]{1,240}"))) { "原身份字段格式无效：$key" }
        }
        fun integer(value: JSONObject, key: String, min: Long, max: Long): Long {
            val raw = value.opt(key)
            require(raw is Int || raw is Long) { "$key 必须为整数" }
            return (raw as Number).toLong().also { require(it in min..max) { "$key 超出允许范围" } }
        }
        fun bool(value: JSONObject, key: String): Boolean {
            require(value.opt(key) is Boolean) { "$key 必须为开关" }
            return value.getBoolean(key)
        }
        fun group(snapshot: JSONObject, id: String): JSONObject = GroupUiState.rows(snapshot.optJSONArray("groups"))
            .singleOrNull { it.opt("id") == id }?.let { JSONObject(it.toString()) } ?: error("这个群空间不在原读取窗口，请刷新")
        fun reference(snapshot: JSONObject, id: String): JSONObject = GroupUiState.rows(snapshot.optJSONArray("requests"))
            .singleOrNull { it.opt("requestId") == id }?.let { JSONObject(it.toString()) } ?: error("这个请求不在原群记录中，请刷新")
        private fun connection(snapshot: JSONObject, id: String): JSONObject = GroupUiState.rows(snapshot.optJSONArray("nativeConnections"))
            .singleOrNull { it.opt("id") == id }?.let { JSONObject(it.toString()) } ?: error("原机器人连接不可用，请先配置")
        private fun sameConnection(a: JSONObject, b: JSONObject): Boolean = listOf("id", "provider", "enabled", "qqGuildEnabled").all { a.opt(it) == b.opt(it) }
    }

    fun clear() = pending.clear()
    fun discard(handle: String) { pending.remove(handle) }
    fun put(command: String, input: JSONObject, group: JSONObject?, window: String, connection: JSONObject? = null): JSONObject {
        pending.entries.removeAll { it.value.expires <= now() }
        check(pending.size < 8) { "请先关闭已有群操作审阅" }
        val handle = UUID.randomUUID().toString()
        pending[handle] = Review(command, JSONObject(input.toString()), group?.let { JSONObject(it.toString()) }, connection?.let { JSONObject(it.toString()) }, window, now() + 120_000)
        return JSONObject().put("reviewHandle", handle).put("command", command).put("input", JSONObject(input.toString()))
            .put("group", group?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
    }
    fun take(handle: String, window: String, snapshot: JSONObject): Review {
        val review = pending.remove(handle) ?: error("原审阅已关闭或使用，请先核对原回执")
        check(review.window == window && review.expires > now()) { "原群审阅窗口已变化或过期，请重新核对" }
        review.group?.let { check(GroupUiState.sameGroup(it, group(snapshot, it.getString("id")))) { "群设置或成员已变化，请重新核对" } }
        review.connection?.let { check(sameConnection(it, connection(snapshot, it.getString("id")))) { "原机器人连接已变化，请重新核对" } }
        if (review.command == "policy.set") validateResources(review.input, review.group!!, snapshot)
        return review
    }
    private fun reviewedGroup(args: JSONObject, snapshot: JSONObject): JSONObject {
        val original = group(snapshot, id(args, "id"))
        check(GroupUiState.sameGroup(args, original)) { "群设置或成员已变化，请刷新后重新编辑" }
        return original
    }
    fun create(args: JSONObject, snapshot: JSONObject, window: String): JSONObject {
        fields(args, "name", "accountId", "roomId", "conversationKind", "conversationParentId")
        val selected = connection(snapshot, id(args, "accountId"))
        val provider = selected.optString("provider")
        require(provider in setOf("qq", "webhook") && selected.opt("enabled") == true) { "请选择已启用的 QQ 或 Webhook 原连接" }
        val scene = text(args, "conversationKind", 40)
        require(scene == "group" || scene == "qq_guild_channel" && provider == "qq" && selected.opt("qqGuildEnabled") == true) { "此连接尚未启用 QQ 频道" }
        val input = JSONObject().put("name", text(args, "name", 100)).put("provider", provider)
            .put("accountId", selected.getString("id")).put("roomId", id(args, "roomId"))
        if (scene == "qq_guild_channel") input.put("conversationKind", scene).put("conversationParentId", id(args, "conversationParentId"))
        else require(!args.has("conversationParentId") || args.opt("conversationParentId") == "") { "普通群不能继承频道身份" }
        return put("group.create", input, null, window, selected).put("connection", selected)
    }
    fun member(args: JSONObject, snapshot: JSONObject, window: String): JSONObject {
        fields(args, "id", "revision", "policyEpoch", "senderId", "displayName", "role", "enabled")
        val group = reviewedGroup(args, snapshot)
        val role = text(args, "role", 20); require(role in setOf("admin", "member", "viewer")) { "请选择原生支持的成员角色" }
        val input = JSONObject().put("id", group.getString("id")).put("revision", group.getLong("revision"))
            .put("senderId", id(args, "senderId")).put("displayName", text(args, "displayName", 80)).put("role", role).put("enabled", bool(args, "enabled"))
        return put("member.set", input, group, window)
    }
    fun policy(args: JSONObject, snapshot: JSONObject, window: String): JSONObject {
        fields(args, "id", "revision", "policyEpoch", "enabled", "visibility", "grants", "quota")
        val group = reviewedGroup(args, snapshot)
        val enabled = bool(args, "enabled")
        val visibility = text(args, "visibility", 30); require(visibility in setOf("requester", "members"))
        val supplied = args.getJSONArray("grants"); require(supplied.length() <= capabilities.size)
        val seen = mutableSetOf<String>(); val grants = JSONArray()
        for (index in 0 until supplied.length()) {
            val grant = supplied.getJSONObject(index); fields(grant, "capability", "resource", "allowGroupAudience", "hours")
            val capability = text(grant, "capability", 50); require(capability in capabilities && seen.add(capability)) { "群能力未知或重复" }
            grants.put(JSONObject().put("capability", capability).put("resource", id(grant, "resource"))
                .put("allowGroupAudience", bool(grant, "allowGroupAudience")).put("expiresAt", now() + integer(grant, "hours", 1, 744) * 3_600_000))
        }
        require(!enabled || grants.length() > 0) { "启用前请明确授权至少一项群能力" }
        val suppliedQuota = args.getJSONObject("quota")
        fields(suppliedQuota, "dailyTasks", "memberDailyTasks", "dailyTokens", "maxTokensPerTask", "maxConcurrent", "dailyMedia", "memberDailyMedia", "maxMediaBytes")
        val quota = JSONObject()
        val ranges = mapOf("dailyTasks" to (1L..500), "memberDailyTasks" to (1L..100), "dailyTokens" to (1L..5_000_000), "maxTokensPerTask" to (1L..100_000),
            "maxConcurrent" to (1L..8), "dailyMedia" to (0L..100), "memberDailyMedia" to (0L..20), "maxMediaBytes" to (1L..20 * 1024 * 1024))
        ranges.forEach { (key, range) -> quota.put(key, integer(suppliedQuota, key, range.first, range.last)) }
        require(quota.getLong("memberDailyTasks") <= quota.getLong("dailyTasks") && quota.getLong("maxTokensPerTask") <= quota.getLong("dailyTokens") && quota.getLong("memberDailyMedia") <= quota.getLong("dailyMedia")) { "成员额度与单次额度不能大于群总额度" }
        if (enabled && seen.any { it.startsWith("media.") }) require(quota.getLong("maxTokensPerTask") >= 32_000 && quota.getLong("dailyTokens") >= 32_000) { "媒体能力需至少 32000 的单次与每日本地预留额度，请明确调整" }
        val input = JSONObject().put("id", group.getString("id")).put("revision", group.getLong("revision")).put("enabled", enabled)
            .put("visibility", visibility).put("grants", grants).put("quota", quota)
        validateResources(input, group, snapshot)
        return put("policy.set", input, group, window)
    }
    private fun validateResources(input: JSONObject, group: JSONObject, snapshot: JSONObject) {
        val native = snapshot.optJSONObject("nativeResources") ?: JSONObject()
        GroupUiState.rows(input.optJSONArray("grants")).forEach { grant ->
            val capability = grant.getString("capability")
            val expected = when { capability.startsWith("public.") -> "public"; capability == "group.files" -> group.getString("spaceId"); else -> native.optString("mediaResource") }
            require(!capability.startsWith("media.") || !input.getBoolean("enabled") || expected.isNotEmpty() && grant.opt("resource") == expected) { "原能力资源已变化，请明确重新选择服务" }
            require(capability.startsWith("media.") || grant.opt("resource") == expected) { "原能力资源不属于这个群空间" }
            require(!input.getBoolean("enabled") || native.opt(capability) == true) { "此能力的原生服务尚不可用" }
            require(!input.getBoolean("enabled") || grant.getLong("expiresAt") > now()) { "原授权期限已过，请重新核对" }
        }
    }
}

/** Fixed commands through the lifecycle-bound original GroupOwnerWindow, never the public feature bus. */
internal class PocketWebGroups(private val controller: PocketWorldController) {
    private val reviews = PocketWebGroupReviews()
    private var generation = 0L
    companion object {
        val COMMANDS = setOf("groups.snapshot", "groups.reviewCreate", "groups.reviewMember", "groups.reviewPolicy", "groups.confirm", "groups.discard",
            "groups.inspectTask", "groups.reviewTask", "groups.reviewDelivery", "groups.inspectDelivery", "groups.openTask")
    }
    fun clear() { generation++; reviews.clear() }
    private fun snapshot(): JSONObject = controller.state.ownerSnapshot("groups")
    fun taskForNavigation(args: JSONObject): String {
        check(controller.webGroupOwnerTicket() != null) { "请回到原群管理窗口" }
        PocketWebGroupReviews.fields(args, "requestId")
        return PocketWebGroupReviews.reference(snapshot(), PocketWebGroupReviews.id(args, "requestId")).getString("taskId").also { check(it.isNotBlank()) }
    }
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        val window = controller.webGroupOwnerTicket() ?: error("请回到前台原群管理窗口")
        val version = generation
        fun deliver(value: JSONObject?, error: String?) {
            if (generation != version || controller.webGroupOwnerTicket() != window) done(null, "原群窗口已变化，请在原记录核对，不要重复提交")
            else done(value?.let { PocketWebState.publicValue(it) as JSONObject }, error)
        }
        fun request(command: String, input: JSONObject, accept: (JSONObject) -> JSONObject = { it }) {
            controller.requestGroupOwnerReceipt(command, input) { result, error ->
                if (error != null || result == null) deliver(null, error ?: "原群操作尚未取得回执")
                else if (generation != version || controller.webGroupOwnerTicket() != window) deliver(null, "原群窗口已变化")
                else try { deliver(accept(result), null) } catch (failure: Exception) { deliver(null, failure.message ?: "原群回执待核对") }
            }
        }
        fun originalTask(requestId: String, value: JSONObject): JSONObject {
            val expected = PocketWebGroupReviews.reference(snapshot(), requestId)
            val receipt = GroupUiState.exactTask(requestId, value) ?: error("原任务回执身份不符")
            val actual = receipt.getJSONObject("reference")
            check(listOf("taskId", "groupId", "spaceId").all { actual.opt(it) == expected.opt(it) }) { "回执不属于这个原群请求" }
            return receipt
        }
        when (action) {
            "groups.snapshot" -> { PocketWebGroupReviews.fields(args); request("snapshot", JSONObject()) { value -> check(GroupUiState.newerSnapshot(JSONObject(), value)) { "原群列表回执不完整" }; value } }
            "groups.reviewCreate" -> deliver(reviews.create(args, snapshot(), window), null)
            "groups.reviewMember" -> deliver(reviews.member(args, snapshot(), window), null)
            "groups.reviewPolicy" -> deliver(reviews.policy(args, snapshot(), window), null)
            "groups.discard" -> { PocketWebGroupReviews.fields(args, "reviewHandle"); reviews.discard(PocketWebGroupReviews.text(args, "reviewHandle")); deliver(JSONObject().put("closed", true), null) }
            "groups.inspectTask" -> {
                PocketWebGroupReviews.fields(args, "requestId"); val id = PocketWebGroupReviews.id(args, "requestId"); PocketWebGroupReviews.reference(snapshot(), id)
                request("task.inspect", JSONObject().put("requestId", id)) { originalTask(id, it) }
            }
            "groups.reviewTask", "groups.reviewDelivery" -> {
                PocketWebGroupReviews.fields(args, "requestId", if (action == "groups.reviewTask") "intent" else "outputId")
                val id = PocketWebGroupReviews.id(args, "requestId"); val reference = PocketWebGroupReviews.reference(snapshot(), id)
                val group = PocketWebGroupReviews.group(snapshot(), reference.getString("groupId"))
                val intent = if (action == "groups.reviewTask") PocketWebGroupReviews.text(args, "intent", 20).also { require(it in setOf("cancel", "resume")) } else "send"
                request("task.inspect", JSONObject().put("requestId", id)) { value ->
                    val receipt = originalTask(id, value)
                    val task = receipt.optJSONObject("task")?.takeIf { receipt.opt("known") == true } ?: error("原任务仍未取得可核对的详情")
                    val input = JSONObject().put("requestId", id)
                    val command = when (intent) {
                        "cancel" -> { check(task.optString("status") !in setOf("completed", "cancelled", "failed")) { "原任务已经结束" }; val target = GroupUiState.cancellation(id, receipt); input.put("runId", target.getString("runId")).put("coordinatorEpoch", target.getInt("coordinatorEpoch")); "task.cancel" }
                        "resume" -> { check(task.optString("status") in setOf("paused", "interrupted")) { "请在原任务页核对审批或未知效果" }; input.put("runId", task.getString("runId")).put("coordinatorEpoch", PocketWebGroupReviews.integer(task, "coordinatorEpoch", 0, Int.MAX_VALUE.toLong())).put("taskRevision", PocketWebGroupReviews.integer(task, "revision", 1, Long.MAX_VALUE)); "task.resume" }
                        else -> {
                            check(task.optString("status") == "completed" && task.opt("resultPrivate") != true) { "原任务尚未形成可发回群范围的结果" }
                            val outputId = PocketWebGroupReviews.id(args, "outputId")
                            val output = GroupUiState.rows(task.optJSONArray("outputs")).singleOrNull { it.opt("outputId") == outputId } ?: error("原产物不在这项群任务中")
                            check(output.opt("spaceId") == reference.opt("spaceId") && output.optString("audience") in setOf("requester", "group")) { "产物受众不属于原群范围" }
                            val deliveryId = GroupUiState.deliveryId(id, outputId, output.getString("audience"))
                            check(GroupUiState.rows(snapshot().optJSONArray("deliveries")).none { it.opt("deliveryId") == deliveryId }) { "这份产物已有投递记录，请只核对原投递" }
                            input.put("outputId", outputId).put("runId", task.getString("runId"))
                                .put("coordinatorEpoch", PocketWebGroupReviews.integer(task, "coordinatorEpoch", 0, Int.MAX_VALUE.toLong()))
                                .put("taskRevision", PocketWebGroupReviews.integer(task, "revision", 1, Long.MAX_VALUE))
                                .put("expectedOutput", JSONObject(output.toString())); "delivery.send"
                        }
                    }
                    reviews.put(command, input, group, window).put("task", task).put("reference", reference)
                }
            }
            "groups.inspectDelivery" -> {
                PocketWebGroupReviews.fields(args, "deliveryId"); val id = PocketWebGroupReviews.id(args, "deliveryId")
                val old = GroupUiState.rows(snapshot().optJSONArray("deliveries")).singleOrNull { it.opt("deliveryId") == id } ?: error("请从原群投递记录选择")
                request("delivery.inspect", JSONObject().put("deliveryId", id)) { GroupUiState.exactDelivery(old.getString("requestId"), old.getString("taskId"), id, it) }
            }
            "groups.confirm" -> {
                PocketWebGroupReviews.fields(args, "reviewHandle", "confirmed"); check(PocketWebGroupReviews.bool(args, "confirmed")) { "请明确确认本次原群操作" }
                val captured = reviews.take(PocketWebGroupReviews.text(args, "reviewHandle"), window, snapshot())
                request(captured.command, captured.input) { value ->
                    when (captured.command) {
                        "group.create" -> check(value.optString("id").isNotBlank() && value.opt("enabled") == false && value.opt("accountId") == captured.input.opt("accountId") && value.opt("roomId") == captured.input.opt("roomId")) { "尚未取得新群空间的原回执" }
                        "member.set", "policy.set" -> check(value.opt("id") == captured.input.opt("id") && GroupUiState.revision(value) > GroupUiState.revision(captured.input)) { "原群设置回执版本不符" }
                        "task.cancel", "task.resume" -> originalTask(captured.input.getString("requestId"), value)
                        "delivery.send" -> {
                            val reference = PocketWebGroupReviews.reference(snapshot(), captured.input.getString("requestId"))
                            val output = captured.input.getJSONObject("expectedOutput")
                            check(value.opt("outputId") == output.opt("outputId") && value.opt("audience") == output.opt("audience")) { "投递回执的产物或受众不符" }
                            val deliveryId = GroupUiState.deliveryId(reference.getString("requestId"), output.getString("outputId"), output.getString("audience"))
                            GroupUiState.exactDelivery(reference.getString("requestId"), reference.getString("taskId"), deliveryId, value)
                        }
                    }
                    JSONObject().put("command", captured.command).put("confirmed", true).put("result", value)
                }
            }
            else -> error("群操作尚未接通")
        }
    }
}
