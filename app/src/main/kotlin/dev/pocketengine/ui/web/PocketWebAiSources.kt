package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.core.RunControl
import dev.pocketengine.runtime.models.CatalogException
import dev.pocketengine.runtime.models.ModelBinding
import dev.pocketengine.runtime.models.ReasoningOptions
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/** Owner-window projection over the existing native sources and catalog, never a credential store. */
internal class PocketWebAiSources(
    private val controller: PocketWorldController,
    private val ownerCurrent: () -> Boolean
) : AutoCloseable {
    private class Review(
        val owner: Pair<Triple<Long, Int, Long>, Long>, val generation: Long, val expires: Long,
        val selectionRevision: Long, val connectionId: String, val connectionRevision: Long,
        val binding: ModelBinding, val modelId: String, val options: String
    )
    private val main = Handler(Looper.getMainLooper())
    private val gate = Any()
    private val reviews = LinkedHashMap<String, Review>()
    private val controls = ConcurrentHashMap<String, RunControl>()
    private var generation = 0L
    @Volatile private var closed = false

    companion object {
        val COMMANDS = setOf("models.connections.snapshot", "models.connections.setEnabled", "models.catalog.snapshot", "models.catalog.refresh",
            "models.catalog.preview", "models.catalog.review", "models.catalog.select")
    }
    private fun current(owner: Pair<Triple<Long, Int, Long>, Long>): Boolean = !closed && ownerCurrent() &&
        controller.webPrivateOwnerVisible() && controller.webWindowTicket() == owner
    private fun requireOwner(owner: Pair<Triple<Long, Int, Long>, Long>) = check(current(owner)) { "原模型管理窗口已关闭，请重新打开" }
    fun clear() {
        synchronized(gate) { generation++; reviews.clear() }
        controls.values.forEach { it.cancel() }; controls.clear()
    }
    override fun close() { closed = true; clear() }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?, String) -> Unit) {
        require(action in COMMANDS) { "这项模型来源操作尚未接入" }
        val owner = controller.webWindowTicket(); requireOwner(owner)
        val service = controller.nativeModelConnections() ?: error("本机模型连接目录尚未准备")
        val selection = controller.nativeModelSelection()
        if (action == "models.connections.setEnabled") {
            fields(args, "id", "expectedRevision", "enabled")
            val id = string(args, "id"); val revision = number(args, "expectedRevision", 1)
            require(args.opt("enabled") is Boolean) { "请明确选择原连接的启停状态" }
            val enabled = args.getBoolean("enabled")
            controller.runModelSelectionWrite({ requireOwner(owner); service.enableSource(id, revision, enabled) }) { result ->
                controller.modelConnectionChanged()
                if (!current(owner)) done(null, "原窗口已变化，请核对当前连接状态；没有自动重试", "outcome_unknown")
                else result.fold({ value -> done(PocketWebState.publicValue(value) as JSONObject, null, "not_accepted") },
                    { failure -> done(null, failure.message ?: "连接状态保存结果待核对，请重新读取", "outcome_unknown") })
            }
            return
        }
        if (action == "models.catalog.select") {
            fields(args, "reviewHandle", "options")
            val handle = string(args, "reviewHandle")
            val reviewed = synchronized(gate) {
                val value = reviews.remove(handle) ?: error("原模型核对已使用或失效，请重新读取")
                check(value.generation == generation && value.expires > SystemClock.elapsedRealtime() && current(value.owner)) { "原模型核对已过期，请重新读取" }
                value
            }
            require(!args.has("options") || args.opt("options") is JSONObject) { "模型参数必须来自当前核对" }
            val options = JSONObject(reviewed.options)
            args.optJSONObject("options")?.let { changes ->
                fields(changes, "maxOutputTokens", "reasoning", "imageTokenReserve")
                changes.keys().forEach { options.put(it, changes.get(it)) }
            }
            val output = number(options, "maxOutputTokens", 128, 32768).toInt()
            val reserve = number(options, "imageTokenReserve", 0, 2_000_000).toInt()
            val reasoningJson = options.getJSONObject("reasoning"); fields(reasoningJson, "mode", "level", "budgetTokens")
            val reasoning = ReasoningOptions.fromJson(reasoningJson)
            controller.runModelSelectionWrite(reviewed.selectionRevision, {
                requireOwner(reviewed.owner)
                check(service.connectionCurrent(reviewed.connectionId, reviewed.connectionRevision, reviewed.binding)) { "原连接修订已变化，请重新核对模型" }
                service.selectConnectionModel(reviewed.connectionId, reviewed.connectionRevision, reviewed.modelId, output, reasoning, reserve)
            }) { result ->
                controller.modelConnectionChanged()
                if (!current(reviewed.owner)) done(null, "原窗口已变化，请重新读取当前默认模型；没有自动重试", "outcome_unknown")
                else result.fold({ value ->
                    done(JSONObject().put("status", "saved").put("snapshot", PocketWebState.publicValue(value))
                        .put("selectedConnectionId", reviewed.connectionId).put("selectedModel", reviewed.modelId), null, "not_accepted")
                }, { failure -> done(null, failure.message ?: "默认模型保存结果待核对，请重新读取", "outcome_unknown") })
            }
            return
        }
        if (action == "models.catalog.preview") {
            val editorId = string(args, "editorId")
            val draftRevision = number(args, "draftRevision", 0)
            val payload = JSONObject(args.toString()).also { it.remove("editorId"); it.remove("draftRevision") }
            val prepared = PocketWebModelConnectionCommands.prepare(
                if (payload.optString("id").isEmpty()) "models.connections.add" else "models.connections.update", payload
            ) as PocketWebModelConnectionCommands.Save
            val key = "draft:$editorId"; val control = control(key, owner)
            controller.runModelConnectionWork(Runnable {
                val result = runCatching {
                    requireOwner(owner)
                    service.previewConnectionCatalog(prepared.id, prepared.expectedRevision, prepared.input, control)
                        .put("editorId", editorId).put("draftRevision", draftRevision)
                }
                main.post {
                    if (controls.remove(key, control) && current(owner)) result.fold({ value ->
                        done(PocketWebState.publicValue(value) as JSONObject, null, "not_accepted")
                    }, { failure -> done(null, catalogError(failure), "not_accepted") })
                    else done(null, "原草稿已变化，未采用旧目录结果", "not_accepted")
                }
            })
            return
        }
        val connectionId: String
        val revision: Long
        val modelId: String
        if (action == "models.connections.snapshot") {
            fields(args); connectionId = ""; revision = 0; modelId = ""
        } else {
            fields(args, *if (action == "models.catalog.review") arrayOf("connectionId", "expectedRevision", "modelId") else arrayOf("connectionId", "expectedRevision"))
            connectionId = string(args, "connectionId"); revision = number(args, "expectedRevision", 1)
            modelId = if (action == "models.catalog.review") string(args, "modelId") else ""
        }
        val reviewGeneration = if (action == "models.catalog.review") synchronized(gate) { generation++; reviews.clear(); generation } else -1
        val key = "source:$connectionId"
        val control = if (action == "models.catalog.refresh") control(key, owner) else null
        controller.runModelConnectionWork(Runnable {
            val result = runCatching {
                requireOwner(owner)
                when (action) {
                    "models.connections.snapshot" -> selection.readStable { service.connections() }.value()
                    "models.catalog.snapshot" -> service.connectionSnapshot(connectionId, revision)
                    "models.catalog.refresh" -> service.refreshConnection(connectionId, revision, control!!)
                    else -> selection.readStable { service.reviewConnectionModel(connectionId, revision, modelId) }.let { stable ->
                        stable.value().put("selectionRevision", stable.revision())
                    }
                }
            }
            main.post {
                if (control != null && !controls.remove(key, control) || !current(owner)) {
                    done(null, "原模型管理窗口或读取已变化，请重新读取", "not_accepted")
                } else result.fold({ value ->
                    try {
                        if (connectionId.isNotEmpty()) {
                            val binding = if (action == "models.catalog.review") value.getJSONObject("model").getJSONObject("binding") else value.getJSONObject("binding")
                            check(service.connectionCurrent(connectionId, revision, ModelBinding.fromJson(binding))) { "原来源已变化，请重新读取模型目录" }
                        }
                        if (action == "models.catalog.review") {
                            val status = selection.status()
                            check(!status.pending() && status.revision() == value.getLong("selectionRevision")) { "模型设置已变化，请重新核对" }
                            synchronized(gate) {
                                check(reviewGeneration == generation) { "已打开另一项模型核对，旧结果未采用" }
                                if (value.getJSONObject("model").optBoolean("available")) {
                                    val handle = UUID.randomUUID().toString()
                                    reviews[handle] = Review(owner, generation, SystemClock.elapsedRealtime() + 120_000,
                                        status.revision(), connectionId, revision, ModelBinding.fromJson(value.getJSONObject("model").getJSONObject("binding")), modelId, value.getJSONObject("options").toString())
                                    value.put("reviewHandle", handle).put("expiresInMs", 120_000)
                                }
                            }
                        }
                        done(PocketWebState.publicValue(value) as JSONObject, null, "not_accepted")
                    } catch (failure: Exception) { done(null, failure.message ?: "原模型记录已变化，请重新读取", "not_accepted") }
                }, { failure -> done(null, if (control != null) catalogError(failure) else failure.message ?: "模型来源暂不可读取", "not_accepted") })
            }
        })
    }
    private fun control(key: String, owner: Pair<Triple<Long, Int, Long>, Long>): RunControl = RunControl().also { control ->
        control.setAuthorityCheck { requireOwner(owner) }
        controls.put(key, control)?.cancel()
    }
    private fun catalogError(failure: Throwable): String = if (failure is CatalogException)
        "模型目录读取未完成（${failure.code.name}），已保留原目录，可手动填写模型 ID。"
    else if (failure is InterruptedException) "原目录读取已停止，已保留上次结果。"
    else "模型目录读取未完成，请核对来源地址、凭据和连接修订；也可手动填写模型 ID。"
    private fun fields(args: JSONObject, vararg allowed: String) = require(args.keys().asSequence().all { it in allowed }) { "模型来源操作包含未接入的字段" }
    private fun string(args: JSONObject, key: String): String {
        val value = args.opt(key)
        require(value is String && value.isNotBlank() && value.length <= 200 && '\u0000' !in value) { "请重新读取有效参数 $key" }
        return value
    }
    private fun number(args: JSONObject, key: String, minimum: Long, maximum: Long = Long.MAX_VALUE): Long {
        val value = args.opt(key)
        require((value is Int || value is Long) && (value as Number).toLong() in minimum..maximum) { "请重新读取有效修订或数值 $key" }
        return (value as Number).toLong()
    }
}
