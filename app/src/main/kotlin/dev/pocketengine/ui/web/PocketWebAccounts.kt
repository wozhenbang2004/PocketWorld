package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.features.NativeOwnerWorkflows
import dev.pocketengine.features.proxy.accounts.GatewayCall
import dev.pocketengine.runtime.models.AccountCatalogFacts
import dev.pocketengine.runtime.models.ModelSelectionCoordinator
import dev.pocketengine.ui.AccountConnectionsLogout
import dev.pocketengine.ui.AccountConnectionsRequest
import dev.pocketengine.ui.accountConnectionsCanUse
import dev.pocketengine.ui.accountConnectionsCatalog
import dev.pocketengine.ui.accountConnectionsLogoutPayload
import dev.pocketengine.ui.accountConnectionsModelReference
import dev.pocketengine.ui.accountConnectionsRow
import dev.pocketengine.ui.accountConnectionsVersion
import java.util.UUID
import org.json.JSONObject

/** Web adaptation of the existing native account page. No credentials or second account authority. */
internal class PocketWebAccounts(
    private val native: NativeOwnerWorkflows,
    private val selection: ModelSelectionCoordinator,
    private val ownerCurrent: () -> Boolean,
    monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
    private val wallMillis: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    internal class Selection internal constructor(internal val entry: PocketWebAccountReviews.Entry, internal val options: String) {
        val selectionRevision: Long get() = entry.selectionRevision()
    }
    internal class Logout internal constructor(internal val entry: PocketWebAccountReviews.Entry) {
        val selectionRevision: Long get() = entry.selectionRevision()
    }
    private data class Catalog(val request: AccountConnectionsRequest, val envelope: JSONObject, val generation: Long)
    private val gate = Any()
    private val reviews = PocketWebAccountReviews { monotonicMillis() }
    private val calls = mutableSetOf<GatewayCall>()
    private var catalog: Catalog? = null
    @Volatile private var closed = false

    private fun requireOwner() { check(!closed && ownerCurrent()) { "原账号窗口已关闭，请重新进入并核对" } }
    private fun idleRevision(): Long {
        requireOwner()
        val status = selection.status()
        check(!status.pending()) { "请先等待原模型设置回执" }
        return status.revision()
    }
    private fun requireMutation() {
        requireOwner()
        check(selection.status().pending()) { "账号变更缺少原生模型写入窗口" }
        selection.requireAvailable() // Only the current Mutation.run thread can pass while pending.
    }
    private fun <T> originalCall(current: () -> Unit, body: (GatewayCall) -> T): T {
        val call = GatewayCall(60_000)
        try {
            synchronized(gate) { requireOwner(); current(); calls.add(call) }
            call.check(); return body(call)
        }
        finally { synchronized(gate) { calls.remove(call) }; call.close() }
    }
    private fun localSnapshot(): ModelSelectionCoordinator.Projection<JSONObject> {
        requireOwner()
        return selection.readStable { native.accountSnapshot().also { check(!it.optBoolean("closed")) { "原生账号服务已关闭" } } }
    }
    private fun project(snapshot: ModelSelectionCoordinator.Projection<JSONObject>, generation: Long): JSONObject =
        JSONObject().put("accountSnapshot", JSONObject(snapshot.value().toString()))
            .put("selectionRevision", snapshot.revision()).put("viewGeneration", generation)

    /** Local read only. Opening a model picker never refreshes credentials or selects an account. */
    fun snapshot(): JSONObject = project(localSnapshot(), reviews.generation())

    /** Worker only. No automatic retry of a catalog request that may have refreshed credentials. */
    fun readModels(accountId: String, expectedVersion: Long): JSONObject {
        idleRevision()
        require(accountId.isNotBlank() && expectedVersion >= 0) { "请重新读取原账号身份与版本" }
        invalidate()
        val generation = reviews.generation()
        val request = AccountConnectionsRequest(UUID.randomUUID().toString(), "models", accountId, expectedVersion, "")
        val result = originalCall({ check(generation == reviews.generation()) { "原账号读取窗口已变化" } }) { call ->
            native.account("models", JSONObject().put("accountId", accountId).put("expectedVersion", expectedVersion), call)
        }
        val actual = localSnapshot()
        val envelope = JSONObject().put("requestId", request.requestId).put("accountId", accountId)
            .put("expectedVersion", expectedVersion).put("ok", true).put("result", result)
        val checked = accountConnectionsCatalog(request, envelope, actual.value(), wallMillis())
            ?: error("原账号或目录版本已变化，请重新读取")
        val projection = project(actual, generation).put("catalog", JSONObject(checked.toString()))
        synchronized(gate) {
            check(projectionCurrent(projection)) { "原账号窗口或模型选择已变化，旧目录未用于当前页面" }
            catalog = Catalog(request, JSONObject(envelope.toString()), generation)
        }
        return projection
    }

    /** Worker only. Uses the previously returned native catalog and rechecks the current local account. */
    fun reviewModel(accountId: String, modelId: String): JSONObject {
        idleRevision()
        val retained = synchronized(gate) { catalog } ?: error("请先读取这个原账号的模型目录")
        check(retained.request.accountId == accountId && retained.generation == reviews.generation()) { "原模型目录窗口已变化" }
        val actual = localSnapshot()
        val reference = accountConnectionsModelReference(retained.request, retained.envelope, actual.value(), modelId, wallMillis())
            ?: error("原账号、模型或目录版本已变化，请重新读取")
        val original = retained.envelope.getJSONObject("result").getJSONArray("models")
        val model = (0 until original.length()).map { original.getJSONObject(it) }.single { it.optString("id") == modelId }
        val facts = AccountCatalogFacts.fromJson(model.getJSONObject("catalogFacts"))
        check(facts.modelId == modelId) { "原模型目录事实与模型身份不符，请重新读取" }
        val options = actual.value().optJSONObject("selectedModelOptions")
        val selected = options?.optJSONObject("nativeRef")
        val same = selected != null && selected.optString("accountId") == accountId && selected.optLong("accountVersion") == reference.getLong("accountVersion") && selected.optString("modelId") == modelId
        val defaults = JSONObject().put("reasoningEffort", if (same) options?.optJSONObject("reasoning")?.optString("level").orEmpty() else "")
            .put("outputReserve", if (same) options?.optInt("maxOutputTokens", 4096) ?: 4096 else 4096)
            .put("imageTokenReserve", if (same) options?.optInt("imageTokenReserve", 0) ?: 0 else 0)
        val account = accountConnectionsRow(actual.value(), accountId) ?: error("原账号已不存在")
        val payload = JSONObject().put("reference", reference).put("defaults", defaults)
        val projection = project(actual, retained.generation).put("accountId", accountId).put("accountVersion", reference.getLong("accountVersion"))
            .put("accountLabel", account.optString("label")).put("modelId", modelId).put("displayName", model.optString("displayName", modelId))
            .put("catalogFacts", facts.toJson()).put("options", JSONObject(defaults.toString())).put("outputLimitSent", false).put("expiresInMs", 120_000)
        synchronized(gate) {
            check(projectionCurrent(projection) && catalog === retained) { "原账号窗口或模型选择已变化，请重新核对" }
            projection.put("reviewHandle", reviews.issue(PocketWebAccountReviews.Kind.MODEL, payload, actual.revision()))
        }
        return projection
    }

    /** Main thread before enqueue. JSON supplies only bounded options, never an account nativeRef. */
    fun takeModelSelection(handle: String, options: JSONObject): Selection {
        val revision = idleRevision()
        require(options.keys().asSequence().all { it in setOf("reasoningEffort", "outputReserve", "imageTokenReserve") }) { "模型选择只接受原审核和本地预留选项" }
        val entry = reviews.take(handle, PocketWebAccountReviews.Kind.MODEL, revision)
        val selected = entry.payload().getJSONObject("defaults")
        if (options.has("reasoningEffort")) {
            val effort = options.get("reasoningEffort")
            require(effort is String && (effort.isEmpty() || effort.matches(Regex("[A-Za-z0-9_-]{1,64}")))) { "推理选项无效" }
            selected.put("reasoningEffort", effort)
        }
        for ((key, range) in listOf("outputReserve" to (128L..32768L), "imageTokenReserve" to (0L..2_000_000L))) if (options.has(key)) {
            val value = options.get(key)
            require((value is Int || value is Long) && (value as Number).toLong() in range) { "本地预留范围无效" }
            selected.put(key, value)
        }
        return Selection(entry, selected.toString())
    }

    /** Worker, inside Controller.runModelSelectionWrite(action.selectionRevision, ...). */
    fun selectModel(action: Selection): JSONObject {
        requireMutation()
        val reviewed = reviews.claim(action.entry, PocketWebAccountReviews.Kind.MODEL)
        val reference = reviewed.getJSONObject("reference")
        val row = accountConnectionsRow(native.accountSnapshot(), reference.getString("accountId"))
        check(accountConnectionsCanUse(row, wallMillis()) && accountConnectionsVersion(row) == reference.getLong("accountVersion")) { "原账号版本或可用状态已变化" }
        val options = JSONObject(action.options)
        options.keys().forEach { reference.put(it, options.get(it)) }
        return originalCall({ reviews.requireCurrent(action.entry, PocketWebAccountReviews.Kind.MODEL) }) { call -> native.selectModel(reference, call) }
    }

    /** Worker only. Logout remains a separate explicit review with the exact original version. */
    fun reviewLogout(accountId: String, expectedVersion: Long): JSONObject {
        idleRevision()
        val generation = reviews.generation()
        val actual = localSnapshot()
        val row = accountConnectionsRow(actual.value(), accountId)
        check(row != null && expectedVersion >= 0 && accountConnectionsVersion(row) == expectedVersion) { "原账号已变化，请重新读取后再确认退出" }
        val draft = AccountConnectionsLogout(accountId, expectedVersion, row.optString("label"))
        val payload = accountConnectionsLogoutPayload(draft, actual.value()).put("label", draft.label)
        val projection = project(actual, generation).put("accountId", accountId).put("accountVersion", expectedVersion).put("accountLabel", draft.label)
            .put("expiresInMs", 120_000).put("message", "将先停止本机使用并清理凭据，再尝试撤销远端授权。原账号记录保留；远端撤销及本机清理须以实际回执核对。")
        synchronized(gate) {
            check(projectionCurrent(projection)) { "原账号窗口或模型选择已变化，请重新核对" }
            projection.put("reviewHandle", reviews.issue(PocketWebAccountReviews.Kind.LOGOUT, payload, actual.revision()))
        }
        return projection
    }
    fun takeLogout(handle: String): Logout = Logout(reviews.take(handle, PocketWebAccountReviews.Kind.LOGOUT, idleRevision()))
    /** Worker, inside the same shared mutation used for model choices. */
    fun logout(action: Logout): JSONObject {
        requireMutation()
        val original = reviews.claim(action.entry, PocketWebAccountReviews.Kind.LOGOUT)
        val draft = AccountConnectionsLogout(original.getString("accountId"), original.getLong("expectedVersion"), original.getString("label"))
        val payload = accountConnectionsLogoutPayload(draft, native.accountSnapshot())
        return originalCall({ reviews.requireCurrent(action.entry, PocketWebAccountReviews.Kind.LOGOUT) }) { call -> native.account("logout", payload, call) }
    }
    /** Check again on the main thread before displaying a worker result. Mutation receipts are not projections. */
    fun projectionCurrent(value: JSONObject): Boolean {
        if (closed || !ownerCurrent()) return false
        val status = selection.status()
        return !status.pending() && value.opt("selectionRevision") is Number && value.optLong("selectionRevision", -1) == status.revision()
            && value.opt("viewGeneration") is Number && value.optLong("viewGeneration", -1) == reviews.generation()
    }
    /** Native window lifecycle, never cancellation of the account's already-completed external effects. */
    fun invalidate() {
        val retiring: List<GatewayCall>
        synchronized(gate) { reviews.invalidate(); catalog = null; retiring = calls.toList() }
        retiring.forEach { it.cancel() }
    }
    override fun close() { closed = true; invalidate(); reviews.close() }
}
