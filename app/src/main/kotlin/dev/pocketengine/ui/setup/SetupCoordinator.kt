package dev.pocketengine.ui.setup

import android.app.Activity
import android.os.Looper
import dev.pocketengine.EngineApplication
import dev.pocketengine.core.ModelConfig
import dev.pocketengine.core.RunControl
import dev.pocketengine.core.RuntimeDiagnostics
import dev.pocketengine.runtime.ModelGateway
import dev.pocketengine.runtime.models.ModelRoutePlan
import dev.pocketengine.store.SettingsStore
import dev.pocketengine.ui.ModelConnectionTestIdentity
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier
import org.json.JSONArray
import org.json.JSONObject

/** Native first-use backend. The owner-only Web bridge supplies its original foreground window. */
class SetupCoordinator(activity: Activity, private val app: EngineApplication) : AutoCloseable {
    private val preferences = SetupPreferences(activity.applicationContext)
    private val permissions = SetupPermissions(activity, preferences)
    @Volatile private var closed = false
    private var lastModelFailure: String? = null

    private data class ModelSnapshot(val config: ModelConfig, val fingerprint: String, val revision: Long)
    private data class RunningTest(val owner: SetupCoordinator, val snapshot: ModelSnapshot, val control: RunControl)

    companion object {
        // A process restart cannot prove that a prior in-flight call or transient native model
        // revision survived. Keep its receipt, but require a new explicit test for a green status.
        private val processIdentity = UUID.randomUUID().toString()
        private val running = AtomicReference<RunningTest?>()
    }

    @Throws(Exception::class)
    fun read(): JSONObject {
        check(!closed) { "首次使用窗口已关闭，请重新打开" }
        return preferences.read().put("model", modelState()).put("permissions", permissions.read()).put("speech", permissions.speech())
    }

    @Throws(Exception::class)
    fun save(input: JSONObject): JSONObject {
        check(!closed) { "首次使用窗口已关闭，请重新打开" }
        preferences.save(JSONObject(input.toString()))
        return read()
    }

    @Throws(Exception::class)
    fun permission(kind: String): JSONObject {
        check(!closed) { "首次使用窗口已关闭，请重新打开" }
        val action = permissions.open(kind)
        return read().put("permissionAction", action)
    }

    /** Blocking worker call; exactly one explicit inference, using the existing SQL usage ledger. */
    @Throws(Exception::class)
    fun testCurrentModel(originalOwnerCurrent: BooleanSupplier): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "模型实测须在后台工作队列执行" }
        requireOwner(originalOwnerCurrent)
        val snapshot = capture()
        val operation = RunningTest(this, snapshot, RunControl())
        check(running.compareAndSet(null, operation)) { "已有一次模型实测正在结束，请等待原回执" }
        var enteredCall = false
        try {
            requireOriginal(snapshot, originalOwnerCurrent)
            preferences.saveModelTest(snapshot.fingerprint, "testing", "正在发送这一次测试请求…", null)
            operation.control.retainAuthorityCheck { requireOriginal(snapshot, originalOwnerCurrent) }.use {
                operation.control.check()
                // get() may carry a MAIN group plus a different DEFAULT plan. Testing must
                // neither silently choose DEFAULT nor retry another model in that group.
                val selected = snapshot.config.withPurposePlans(emptyMap()).withRoutePlan(null)
                val exact = selected.withRoutePlan(ModelRoutePlan.exact(selected, "CONNECTION_TEST", "APP_OWNER"))
                enteredCall = true
                val result = app.testModelConnection(exact, operation.control)
                operation.control.check()
                val reference = result.optJSONObject("inferenceReference")
                val feedback = if (reference == null) ModelConnectionTestIdentity.unavailableFeedback() else runCatching {
                    ModelConnectionTestIdentity.feedback(reference, app.tasks.inferenceUsage(reference.getString("taskId")))
                }.getOrElse { failure ->
                    RuntimeDiagnostics.event("diagnostics", "unconfirmed", "SETUP_MODEL_RECEIPT_UNAVAILABLE", operation.control, failure)
                    ModelConnectionTestIdentity.unavailableFeedback()
                }
                val confirmed = feedback.optBoolean("available") && !feedback.optBoolean("outcomeUnknown", true) &&
                    feedback.optJSONArray("calls")?.length() == 1
                val outcome = if (!confirmed) "unknown" else if (result.optBoolean("ok")) "passed" else "failed"
                val message = if (!confirmed) "这次测试未取得完整的原推理回执，结果未知；没有自动重试。" else
                    safeMessage(result.optString("message"), snapshot.config)
                requireOriginal(snapshot, originalOwnerCurrent)
                preferences.saveModelTest(snapshot.fingerprint, outcome, message, reference)
            }
        } catch (failure: Exception) {
            RuntimeDiagnostics.event("diagnostics", "failed", "SETUP_MODEL_TEST_FAILED", operation.control, failure)
            val message = safeFailure(failure, snapshot.config)
            try {
                // The historical result stays attached to its old fingerprint. It cannot
                // make a new selection green, even if the caller has left the setup page.
                preferences.saveModelTest(snapshot.fingerprint, if (enteredCall) "unknown" else "failed", message, null)
            } catch (storage: Exception) { failure.addSuppressed(storage); throw failure }
            if (!ownerCurrent(originalOwnerCurrent)) throw IllegalStateException("原模型实测窗口已变化，未把旧结果应用到当前页面", failure)
        } finally {
            running.compareAndSet(operation, null)
        }
        requireOwner(originalOwnerCurrent)
        return read()
    }

    /** A closing native owner window can stop transport; no retry is scheduled. */
    fun cancelModelTest() {
        running.get()?.takeIf { it.owner === this }?.control?.cancel()
    }

    override fun close() { closed = true; cancelModelTest() }

    private fun requireOwner(owner: BooleanSupplier) {
        check(ownerCurrent(owner)) { "请在机主的前台私人设置中发起这次模型实测" }
    }

    private fun ownerCurrent(owner: BooleanSupplier): Boolean = !closed && runCatching { owner.getAsBoolean() }.getOrDefault(false)

    private fun requireOriginal(original: ModelSnapshot, owner: BooleanSupplier) {
        requireOwner(owner)
        val current = capture(validate = false)
        check(current.revision == original.revision && current.fingerprint == original.fingerprint) {
            "原模型配置或凭据版本已变化，本次结果不再算作当前模型验证通过"
        }
    }

    private fun capture(validate: Boolean = true): ModelSnapshot {
        check(app.businessReady() && app.settings != null) { "本机模型设置尚未准备，请等待应用启动" }
        val settings = app.settings
        val stable = settings.modelSelection().readStable {
            val config = settings.get()
            settings.currentForCaptured(config) // Reject a disabled/replaced original source, without switching it.
            if (validate) {
                SettingsStore.validate(config)
                ModelGateway.validate(config)
                if (config.usesBedrock()) {
                    val credentials = config.awsSession ?: error("请先提供完整的 Bedrock 临时凭据")
                    credentials.requireValidAt(System.currentTimeMillis())
                }
            }
            config to settings.apiSelectionRevision()
        }
        val config = stable.value().first
        val material = JSONObject().put("process", processIdentity).put("nativeRevision", stable.revision())
            .put("apiSelectionRevision", stable.value().second).put("configuration", ModelRoutePlan.configurationDigest(config))
            .put("credential", config.credentialIdentityDigest())
            .put("binding", config.capabilityBinding?.toJson() ?: JSONObject.NULL)
            .put("account", config.accountModel?.toJson() ?: JSONObject.NULL)
        config.routePlan?.let { plan ->
            // Do not use snapshotId: it is intentionally freshly generated on every settings read.
            val candidates = JSONArray()
            plan.candidates.forEach { candidate ->
                candidates.put(JSONObject().put("id", candidate.candidateId()).put("exclusion", candidate.exclusion())
                    .put("configuration", candidate.config()?.let(ModelRoutePlan::configurationDigest) ?: JSONObject.NULL)
                    .put("credential", candidate.config()?.credentialIdentityDigest() ?: JSONObject.NULL))
            }
            material.put("route", JSONObject().put("alias", plan.requestedAlias).put("revision", plan.groupRevision)
                .put("purpose", plan.purpose).put("audience", plan.audience).put("candidates", candidates))
        }
        return ModelSnapshot(config, preferences.fingerprint(material.toString()), stable.revision())
    }

    private fun modelState(): JSONObject {
        val result = JSONObject().put("configured", false).put("label", "").put("tested", false).put("testing", false)
            .put("visionVerified", false).put("toolsVerified", false)
        var selected: ModelConfig? = null
        try {
            selected = capture(validate = false).config
            result.put("label", safeMessage(selected.model, selected).take(240))
            // A fresh, identity-free API selection is a next step, not a failed
            // connection. Source/revision checks above still reject revoked data.
            if (selected.provider == ModelConfig.DEFAULT_PROVIDER && selected.baseUrl.isEmpty() &&
                selected.model.isEmpty() && selected.apiKey.isEmpty() && selected.accountModel == null &&
                selected.capabilityBinding == null && selected.routePlan == null && !selected.catalogEnforced) {
                lastModelFailure = null
                return result.put("availability", "unconfigured")
            }
            val captured = capture()
            lastModelFailure = null
            selected = captured.config
            val accountAvailable = !selected.usesAccount() || ModelGateway.accountModelAvailableForDisplay(selected.accountModel)
            result.put("configured", accountAvailable).put("label", safeMessage(selected.model, selected).take(240))
            if (!accountAvailable) return result.put("availability", "unavailable")
                .put("message", "已选账号模型，但当前账号授权或模型资料尚未确认；请回模型页核对。")
            val active = running.get()
            val evidence = preferences.modelEvidence(captured.fingerprint, active?.snapshot?.fingerprint == captured.fingerprint)
            evidence.keys().forEach { key -> result.put(key, evidence.get(key)) }
            if (!result.has("message")) result.put("message", when {
                !selected.usesAccount() && !selected.usesBedrock() && selected.apiKey.isEmpty() ->
                    "已保存地址与模型，尚未提供密钥；请按服务要求配置并实测，保存不代表连接成功。"
                else -> "模型配置已保存，尚未实测；测试会发送一次很短的真实请求，可能产生费用。"
            })
        } catch (failure: Exception) {
            val message = safeFailure(failure, selected)
            if (message != lastModelFailure) RuntimeDiagnostics.event("diagnostics", "unconfirmed", "SETUP_MODEL_STATE_UNAVAILABLE", null, failure)
            lastModelFailure = message
            result.put("availability", "unavailable")
                .put("message", "请到模型页检查当前连接，或刷新后重新核对。")
                .put("details", message)
        }
        return result
    }

    private fun safeFailure(failure: Throwable, config: ModelConfig?): String {
        val details = generateSequence(failure) { it.cause }.take(4).map { cause ->
            cause.javaClass.simpleName + (cause.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        }.joinToString(" → ")
        return safeMessage(details, config)
    }

    private fun safeMessage(message: String, config: ModelConfig?): String = (config?.redactSensitive(message) ?: message)
        .replace(Regex("(?i)Bearer\\s+[^\\s,;]+"), "Bearer [已隐藏凭据]")
        .replace(Regex("(?i)(api[_-]?key|token|secret|authorization)\\s*[:=]\\s*[^\\s,;]+"), "$1=[已隐藏凭据]")
        .take(1600)
}
