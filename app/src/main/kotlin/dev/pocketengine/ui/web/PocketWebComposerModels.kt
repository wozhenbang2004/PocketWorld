package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.pocketengine.runtime.models.ModelBinding
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import org.json.JSONObject

/** Chat model-picker presentation only; both sources use their original native model service. */
internal class PocketWebComposerModels(
    private val controller: PocketWorldController,
    private val ownerCurrent: () -> Boolean
) : AutoCloseable {
    private class Window(val id: String, val owner: Pair<Triple<Long, Int, Long>, Long>, val api: Boolean = false) {
        var accounts: PocketWebAccounts? = null
        var models: PocketWebApiModels? = null
    }
    @Volatile private var window: Window? = null
    @Volatile private var closed = false
    private val main = Handler(Looper.getMainLooper())

    companion object {
        val COMMANDS = setOf("models.accounts.open", "models.accounts.close", "models.accounts.models", "models.accounts.review", "models.accounts.select",
            "models.api.open", "models.api.close", "models.api.review", "models.api.select", "models.api.favorite")
    }
    private fun current(original: Window?): Boolean = !closed && original != null && window === original && ownerCurrent() &&
        controller.webPrivateOwnerVisible() && controller.webWindowTicket() == original.owner
    private fun requireWindow(id: String, api: Boolean = false): Window = window?.takeIf { it.id == id && it.api == api && current(it) }
        ?: error("原聊天模型窗口已关闭，请重新打开并核对")
    private fun idle() {
        val state = controller.state
        check(!closed && ownerCurrent() && controller.webPrivateOwnerVisible()) { "请回到原私人聊天选择模型" }
        check(!state.chatSubmitting && !state.phoneSubmitting && !state.modelSaving && !state.modelTesting && !controller.modelSelectionPending()) {
            "请先等待原提交和模型设置回执，再切换后续任务的模型"
        }
    }
    private fun fields(args: JSONObject, vararg names: String) {
        require(args.keys().asSequence().all { it in names }) { "聊天模型选择含不适用的字段" }
    }
    private fun string(args: JSONObject, key: String): String {
        val value = args.opt(key)
        require(value is String && value.isNotBlank() && value.length <= 200 && '\u0000' !in value) { "请重新读取原模型窗口" }
        return value
    }
    fun clear() { val retiring = window; window = null; retiring?.accounts?.close(); retiring?.models?.close() }
    override fun close() { closed = true; clear() }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?, String) -> Unit) {
        require(action in COMMANDS) { "不是聊天模型选择操作" }
        check(!closed && ownerCurrent() && controller.webPrivateOwnerVisible()) { "请回到原私人聊天选择模型" }
        if (action.startsWith("models.api.")) { dispatchApi(action, args, done); return }
        if (action == "models.accounts.close") {
            fields(args, "windowId"); requireWindow(string(args, "windowId")); clear()
            done(JSONObject().put("closed", true), null, "not_accepted"); return
        }
        idle()
        val original = if (action == "models.accounts.open") {
            fields(args); clear()
            Window(UUID.randomUUID().toString(), controller.webWindowTicket()).also { original ->
                original.accounts = PocketWebAccounts(controller.nativeAccountWorkflows(), controller.nativeModelSelection(), { current(original) })
                window = original
            }
        } else requireWindow(string(args, "windowId"))
        if (action == "models.accounts.select") {
            fields(args, "windowId", "reviewHandle", "options")
            require(!args.has("options") || args.opt("options") is JSONObject) { "模型选项必须来自原审核" }
            val selected = original.accounts!!.takeModelSelection(string(args, "reviewHandle"), args.optJSONObject("options") ?: JSONObject())
            controller.runModelSelectionWrite(selected.selectionRevision, {
                requireWindow(original.id); original.accounts!!.selectModel(selected)
            }) { result ->
                controller.modelConnectionChanged()
                if (!current(original)) done(null, "原模型窗口已关闭，请核对当前选择；没有自动重试", "outcome_unknown")
                else result.fold({ value ->
                    done(JSONObject().put("status", "saved").put("receipt", PocketWebState.publicValue(value)), null, "not_accepted")
                }, { error -> done(null, "原模型选择结果待核对：" + (error.message ?: "请重新读取账号状态"), "outcome_unknown") })
            }
            return
        }
        val accountId: String
        val modelId: String
        val version: Long
        when (action) {
            "models.accounts.open" -> { accountId = ""; modelId = ""; version = -1 }
            "models.accounts.models" -> {
                fields(args, "windowId", "accountId", "expectedVersion"); accountId = string(args, "accountId"); modelId = ""
                val raw = args.opt("expectedVersion")
                require((raw is Int || raw is Long) && (raw as Number).toLong() >= 0) { "请重新读取原账号版本" }
                version = (raw as Number).toLong()
            }
            "models.accounts.review" -> {
                fields(args, "windowId", "accountId", "modelId"); accountId = string(args, "accountId"); modelId = string(args, "modelId"); version = -1
            }
            else -> error("此操作不属于聊天模型选择")
        }
        controller.runModelConnectionWork(Runnable {
            val result = runCatching {
                requireWindow(original.id)
                when (action) {
                    "models.accounts.open" -> original.accounts!!.snapshot()
                    "models.accounts.models" -> original.accounts!!.readModels(accountId, version)
                    else -> original.accounts!!.reviewModel(accountId, modelId)
                }.put("windowId", original.id)
            }
            main.post {
                if (!current(original)) done(null, "原聊天模型窗口已变化，请重新读取", "not_accepted")
                else result.fold({ value ->
                    if (original.accounts!!.projectionCurrent(value)) done(PocketWebState.publicValue(value) as JSONObject, null, "not_accepted")
                    else done(null, "原账号或模型设置已变化，请重新读取", "not_accepted")
                }, { error -> done(null, error.message ?: "原账号记录暂不可读取", "not_accepted") })
            }
        })
    }

    private fun dispatchApi(action: String, args: JSONObject, done: (JSONObject?, String?, String) -> Unit) {
        if (action == "models.api.close") {
            fields(args, "windowId"); requireWindow(string(args, "windowId"), true); clear()
            done(JSONObject().put("closed", true), null, "not_accepted"); return
        }
        idle()
        val service = controller.nativeModelConnections() ?: error("本机模型连接目录尚未准备")
        val selection = controller.nativeModelSelection()
        val original = if (action == "models.api.open") {
            fields(args); clear()
            Window(UUID.randomUUID().toString(), controller.webWindowTicket(), true).also { original ->
                original.models = PocketWebApiModels(SystemClock::elapsedRealtime, { current(original) })
                window = original
            }
        } else requireWindow(string(args, "windowId"), true)
        val models = original.models ?: error("原 API 模型窗口不可用")
        if (action == "models.api.select" || action == "models.api.favorite") {
            val selected = if (action == "models.api.select") {
                fields(args, "windowId", "reviewHandle")
                models.take(string(args, "reviewHandle"), selection.status().revision())
            } else {
                fields(args, "windowId", "connectionId", "modelId", "expectedRevision", "favorite")
                require(args.opt("favorite") is Boolean) { "请选择此模型的收藏状态" }
                models.favorite(string(args, "connectionId"), string(args, "modelId"), connectionRevision(args), args.getBoolean("favorite"), selection.status().revision())
            }
            controller.runModelSelectionWrite(selected.selectionRevision(), {
                requireWindow(original.id, true)
                val row = models.claim(selected)
                val favorite = selected.favorite()
                if (favorite == null) service.selectPickerModel(ModelBinding.fromJson(row.getJSONObject("binding")), row.getLong("connectionRevision"), row.getString("modelId"))
                else if (!favorite && row.optJSONObject("binding") == null) service.removeModelFavorite(row.getString("connectionId"), row.getString("modelId"))
                else service.favoriteModel(ModelBinding.fromJson(row.getJSONObject("binding")), row.getLong("connectionRevision"), row.getString("modelId"), favorite)
            }) { result ->
                controller.modelConnectionChanged()
                if (!current(original)) done(null, "原模型窗口已关闭，请核对当前选择或收藏；没有自动重试", "outcome_unknown")
                else result.fold({ value ->
                    done(JSONObject().put("status", "saved").put("selectedConnectionId", value.optString("selectedConnectionId")).put("selectedModel", value.optString("selectedModel")), null, "not_accepted")
                }, { error -> done(null, error.message ?: "原模型操作结果待核对，请重新读取", "outcome_unknown") })
            }
            return
        }
        val connection: String
        val model: String
        val version: Long
        if (action == "models.api.open") { connection = ""; model = ""; version = 0 }
        else {
            require(action == "models.api.review") { "不是 API 模型读取操作" }
            fields(args, "windowId", "connectionId", "modelId", "expectedRevision")
            connection = string(args, "connectionId"); model = string(args, "modelId"); version = connectionRevision(args)
        }
        controller.runModelConnectionWork(Runnable {
            val result = runCatching {
                requireWindow(original.id, true)
                if (action == "models.api.open") {
                    val stable = selection.readStable { service.modelPickerSnapshot() }
                    models.publish(stable.value(), stable.revision())
                } else selection.readStable { models.review(connection, model, version, selection.status().revision()) }.value()
            }
            main.post {
                if (!current(original)) done(null, "原模型窗口已变化，请重新读取", "not_accepted")
                else result.fold({ value ->
                    if (!selection.status().pending() && models.projectionCurrent(value, selection.status().revision()))
                        done((PocketWebState.publicValue(value) as JSONObject).put("windowId", original.id), null, "not_accepted")
                    else done(null, "模型设置已变化，请重新读取原连接列表", "not_accepted")
                }, { error -> done(null, error.message ?: "原模型列表暂不可读取", "not_accepted") })
            }
        })
    }
    private fun connectionRevision(args: JSONObject): Long {
        val raw = args.opt("expectedRevision")
        require((raw is Int || raw is Long) && (raw as Number).toLong() >= 0) { "请重新读取原连接修订" }
        return (raw as Number).toLong()
    }
}
