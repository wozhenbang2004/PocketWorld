package dev.pocketengine.ui.web

import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.RunControl
import dev.pocketengine.runtime.models.ModelBinding
import dev.pocketengine.runtime.models.ModelProbeDeadline
import dev.pocketengine.ui.PocketWorldController
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

/** The existing bounded synthetic probe, owned by one visible model-settings window. */
internal class PocketWebModelDiagnostics(
    private val controller: PocketWorldController,
    private val capture: () -> PocketWebWindowTicket,
    private val accepts: (PocketWebWindowTicket) -> Boolean,
    private val ownerCurrent: () -> Boolean,
    private val changed: () -> Unit
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private data class Window(val id: String, val owner: PocketWebWindowTicket)
    private data class Review(val id: String, val binding: ModelBinding, val model: String, val revision: Long, val image: Boolean, val expires: Long)
    @Volatile private var window: Window? = null
    private var review: Review? = null
    @Volatile private var control: RunControl? = null
    private var error: String? = null
    @Volatile private var closed = false

    companion object {
        val COMMANDS = setOf("modelDiagnostics.open", "modelDiagnostics.read", "modelDiagnostics.review", "modelDiagnostics.start", "modelDiagnostics.stop", "modelDiagnostics.close", "modelDiagnostics.discard")
    }

    private fun current(original: Window): Boolean = !closed && window === original && ownerCurrent() && controller.webPrivateOwnerVisible() && accepts(original.owner)
    private fun service() = controller.nativeModelConnections() ?: error("原模型连接尚未准备")
    private fun fields(args: JSONObject, vararg keys: String) { require(args.keys().asSequence().all { it in keys }) { "模型诊断包含不适用字段" } }
    fun clear() { review = null; window = null; control?.cancel(); control = null; error = null }
    override fun close() { if (closed) return; closed = true; clear(); worker.shutdownNow() }

    fun snapshot(): JSONObject {
        val original = window ?: return JSONObject()
        if (!current(original)) { clear(); return JSONObject() }
        val native = service().snapshot()
        val api = native.getJSONObject("api")
        return JSONObject().put("windowId", original.id).put("ready", true).put("busy", control != null).put("error", error)
            .put("model", api.optString("model")).put("provider", api.optString("provider"))
            .put("canProbe", api.optJSONObject("capabilityBinding") != null)
            .put("requestTimeoutSeconds", api.optInt("requestTimeoutSeconds", 60))
            .put("probeSelectionRevision", native.optLong("probeSelectionRevision", -1))
            .put("probe", native.optJSONObject("capabilityProbe"))
    }

    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(!closed && ownerCurrent() && controller.webPrivateOwnerVisible()) { "请回到原模型设置页" }
        if (action == "modelDiagnostics.open") {
            fields(args); clear(); window = Window(UUID.randomUUID().toString(), capture())
            done(snapshot(), null); changed(); return
        }
        val original = window ?: error("请重新打开模型诊断")
        check(current(original) && args.optString("windowId") == original.id) { "原模型诊断窗口已变化" }
        when (action) {
            "modelDiagnostics.close" -> { fields(args, "windowId"); clear(); done(JSONObject().put("closed", true), null) }
            "modelDiagnostics.discard" -> { fields(args, "windowId"); review = null; done(JSONObject().put("discarded", true), null) }
            "modelDiagnostics.read" -> { fields(args, "windowId"); done(snapshot(), null) }
            "modelDiagnostics.stop" -> { fields(args, "windowId"); control?.cancel(); review = null; done(JSONObject().put("stopRequested", true), null) }
            "modelDiagnostics.review" -> {
                fields(args, "windowId", "includeImage"); check(control == null) { "请先等待原诊断结束" }
                require(args.opt("includeImage") is Boolean)
                val native = service().snapshot(); val api = native.getJSONObject("api")
                val binding = ModelBinding.fromJson(api.getJSONObject("capabilityBinding"))
                val selected = Review(UUID.randomUUID().toString(), binding, api.getString("model"), native.getLong("probeSelectionRevision"), args.getBoolean("includeImage"), System.currentTimeMillis() + 120000)
                review = selected
                done(JSONObject().put("reviewHandle", selected.id).put("model", selected.model).put("provider", api.optString("provider"))
                    .put("binding", binding.toJson()).put("includeImage", selected.image).put("maxRequests", if (selected.image) 3 else 2)
                    .put("requestTimeoutSeconds", api.optInt("requestTimeoutSeconds", 60)).put("startsTask", false), null)
            }
            "modelDiagnostics.start" -> {
                fields(args, "windowId", "reviewHandle", "acknowledge")
                require(args.opt("acknowledge") == true)
                val selected = review ?: error("请重新核对实际诊断范围")
                check(control == null && selected.id == args.optString("reviewHandle") && System.currentTimeMillis() < selected.expires) { "原诊断审阅已失效" }
                val actual = service().snapshot(); val api = actual.getJSONObject("api")
                check(actual.getLong("probeSelectionRevision") == selected.revision && api.getString("model") == selected.model && ModelBinding.fromJson(api.getJSONObject("capabilityBinding")) == selected.binding) { "原连接或模型已变化，请重新核对" }
                review = null; error = null
                val running = RunControl().also { it.setAuthorityCheck { check(current(original)) { "原诊断窗口已关闭" } } }; control = running
                try { worker.execute {
                    val result = runCatching {
                        check(current(original)) { "原诊断窗口已关闭" }; running.check()
                        service().probe(selected.binding, selected.model, selected.revision, selected.image, running)
                    }
                    main.post {
                        if (control !== running) return@post
                        control = null
                        if (!current(original)) return@post
                        error = result.exceptionOrNull()?.let { failure -> when (failure) {
                            is ModelProbeDeadline.Exceeded -> "整体诊断期限已到，后续阶段已停止；已发生的请求用量保留。"
                            is InterruptedException -> "探测已停止；已经发生的请求用量仍保留。"
                            is SecurityException -> "原连接、模型或授权已变化，未采用旧结果。"
                            else -> "本次未证实，请核对原连接；没有执行真实工具。"
                        } }
                        changed()
                    }
                } } catch (failure: Exception) { running.cancel(); control = null; throw failure }
                done(JSONObject().put("started", true).put("verified", false).put("maxRequests", if (selected.image) 3 else 2), null)
            }
            else -> error("模型诊断操作未开放")
        }
        changed()
    }
}
