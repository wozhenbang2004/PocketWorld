package dev.pocketengine.features.remoteinbox

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

/** Optional UnifiedPush glue; the SDK owns connection tokens, key storage and RFC 8291 decryption. */
class UnifiedInboxPush : RemoteInboxPush.Backend {
    companion object {
        private val cleanup = Executors.newSingleThreadExecutor()
        private val transportProblems = setOf("distributor_unavailable", "registration_failed", "unregistered", "temporarily_unavailable", "temporary_receiver", "endpoint_rejected", "encryption_unavailable")
        private fun store(context: Context) = RemoteInboxStore.get(context)
        private fun credentialsRequired(state: JSONObject) = state.optBoolean("credentialsRequired") || state.optString("status") == "credentials_required"
        private fun active(context: Context): JSONObject = synchronized(store(context)) {
            val saved = store(context).pushConfiguration()
            // Preserve an older saved credential failure before a transport callback changes status.
            if (credentialsRequired(saved)) saved.put("credentialsRequired", true)
            if (saved.optString("configId") == store(context).configId() && saved.optBoolean("enabled")) saved else JSONObject()
        }
        private fun change(context: Context, instance: String, update: (JSONObject) -> Unit): Boolean = synchronized(store(context)) {
            val saved = active(context)
            if (saved.optString("instance") != instance || instance.isEmpty()) return@synchronized false
            update(saved)
            store(context).savePushConfiguration(saved.getString("configId"), saved)
        }
        private class RequestFailure(val code: Int) : IllegalStateException("推送服务请求未完成 ($code)，请检查登记凭据或服务配置")
        private fun request(root: String, path: String, token: String, method: String = "POST", body: JSONObject? = null): JSONObject {
            val connection = URL(root + path).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                if (code !in 200..299) throw RequestFailure(code)
                val text = connection.inputStream.use { stream ->
                    val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(1024)
                    while (true) {
                        val count = stream.read(buffer); if (count < 0) break
                        check(bytes.size() + count <= 8192) { "推送服务响应过大" }
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString("UTF-8")
                }
                return JSONObject(text)
            } finally { connection.disconnect() }
        }
        private fun schedule(context: Context, wake: Boolean = false) {
            try { if (wake) RemoteInboxJobService.requestPushCatchUp(context) else RemoteInboxJobService.requestCatchUp(context) }
            catch (_: Exception) { /* Periodic recovery and the next app foreground remain available. */ }
        }
        fun endpoint(context: Context, instance: String, url: String, pubKey: String, auth: String) {
            try {
                val uri = URI(url)
                val saved = active(context)
                val allowed = saved.optJSONArray("allowedHosts") ?: return
                require(url.length <= 1000 && uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null &&
                    (uri.port == -1 || uri.port == 443) && (0 until allowed.length()).any { allowed.getString(it) == uri.host.lowercase() })
                require(Regex("^[A-Za-z0-9_-]{87}$").matches(pubKey) && Regex("^[A-Za-z0-9_-]{22}$").matches(auth))
                val subscription = JSONObject().put("endpoint", url).put("keys", JSONObject().put("p256dh", pubKey).put("auth", auth))
                if (change(context, instance) { current ->
                    current.remove("transportProblem")
                    if (current.optString("status") in transportProblems) current.put("status", "endpoint_received")
                    if (current.optJSONObject("subscription")?.toString() != subscription.toString()) {
                        current.put("subscription", subscription).put("endpointGeneration", UUID.randomUUID().toString()).put("registeredAt", 0).put("verifiedAt", 0).put("status", "endpoint_received")
                        current.remove("challenge")
                    }
                }) schedule(context)
            } catch (_: Exception) { failed(context, instance, "endpoint_rejected") }
        }
        fun message(context: Context, instance: String, content: ByteArray, decrypted: Boolean) {
            if (!decrypted || content.size !in 1..1024) return
            try {
                val payload = JSONObject(String(content, Charsets.UTF_8))
                if (payload.optString("instance") != instance) return
                val kind = payload.optString("kind")
                if (kind != "inbox_wake" && kind != "push_challenge") return
                val challenge = payload.optString("challenge")
                if (kind == "push_challenge" && !Regex("^[0-9a-f-]{36}$").matches(challenge)) return
                if (change(context, instance) {
                    it.put("lastWakeAt", System.currentTimeMillis()).put("status", "wake_received")
                    if (kind == "push_challenge") it.put("challenge", challenge)
                }) schedule(context, wake = true)
            } catch (_: Exception) { /* Untrusted data is never routed to tools, tasks or links. */ }
        }
        fun failed(context: Context, instance: String, status: String) {
            try { change(context, instance) {
                it.put("status", status)
                if (status == "credentials_required") it.put("credentialsRequired", true)
                if (status in transportProblems) it.put("transportProblem", status)
            } } catch (_: Exception) { }
        }
    }

    override fun snapshot(context: Context): JSONObject {
        val state = active(context)
        val distributors = JSONArray()
        for (name in UnifiedPush.getDistributors(context)) {
            val label = try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(name, 0)).toString() } catch (_: Exception) { name }
            distributors.put(JSONObject().put("id", name).put("label", label))
        }
        return JSONObject().put("supported", true).put("enabled", state.optBoolean("enabled"))
            .put("distributors", distributors).put("distributor", state.optString("distributor"))
            .put("status", if (credentialsRequired(state)) "credentials_required" else state.optString("transportProblem").ifEmpty { state.optString("status", "disabled") })
            .put("registeredAt", state.optLong("registeredAt")).put("verifiedAt", state.optLong("verifiedAt"))
            .put("lastWakeAt", state.optLong("lastWakeAt")).put("lastCatchUpAt", state.optLong("lastCatchUpAt"))
    }
    override fun enable(context: Context, distributor: String, registrationToken: String, configId: String, endpoint: String) {
        require(registrationToken.length in 32..2048 && registrationToken.all { it.code in 33..126 }) { "请输入此通知服务的专用设备登记 Token" }
        require(distributor in UnifiedPush.getDistributors(context)) { "所选 UnifiedPush 接收器不可用，请先自行安装并配置" }
        val connection = store(context).connection()
        check(connection.getString("configId") == configId && connection.getString("endpoint") == endpoint) { "通知连接已变化，登记凭据没有发送，请重新打开设置" }
        val config = request(endpoint, "/v1/push/config", registrationToken, "GET")
        require(config.optString("format") == "pocketworld.push.v1" && Regex("^[A-Za-z0-9_-]{87}$").matches(config.optString("vapid"))) { "此服务尚未配置推送发送能力" }
        val allowed = config.getJSONArray("allowedHosts")
        require(allowed.length() in 1..16 && (0 until allowed.length()).all { allowed.getString(it).length <= 253 }) { "推送服务未配置允许的接收地址" }
        synchronized(store(context)) {
            check(connection.getString("configId") == store(context).configId()) { "通知连接已变化，请重新启用" }
            check(!active(context).optBoolean("enabled")) { "请先停用当前推送，再更换接收器" }
            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val state = JSONObject().put("enabled", true).put("configId", connection.getString("configId"))
                .put("root", connection.getString("endpoint")).put("instance", UUID.randomUUID().toString())
                .put("secret", Base64.encodeToString(secret, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                .put("registrationToken", registrationToken).put("vapid", config.getString("vapid")).put("allowedHosts", allowed)
                .put("distributor", distributor).put("status", "registration_requested")
            check(store(context).savePushConfiguration(connection.getString("configId"), state)) { "通知连接已变化" }
        }
        reconcile(context)
        schedule(context)
    }
    override fun disable(context: Context) {
        val prior = synchronized(store(context)) {
            val value = active(context)
            if (value.length() > 0) check(store(context).savePushConfiguration(value.getString("configId"), JSONObject()))
            value
        }
        if (prior.length() == 0) return
        // Retire local identity before either external action. Late callbacks cannot revive it.
        try { UnifiedPush.unregister(context, prior.getString("instance")) }
        catch (_: Exception) { /* Local identity has already been retired; late callbacks are ignored. */ }
        cleanup.execute {
            try { request(prior.getString("root"), "/v1/push/device", prior.getString("secret"), "DELETE", JSONObject().put("deviceId", prior.getString("instance"))) }
            catch (_: Exception) { /* Offline server records expire; local subscription is already retired. */ }
        }
    }
    override fun reconcile(context: Context) {
        val state = active(context)
        if (state.length() == 0) return
        val distributor = state.getString("distributor")
        if (distributor !in UnifiedPush.getDistributors(context)) {
            failed(context, state.getString("instance"), "distributor_unavailable"); return
        }
        synchronized(store(context)) {
            if (active(context).optString("instance") != state.getString("instance")) return
            change(context, state.getString("instance")) { it.put("lastRegisterRequestAt", System.currentTimeMillis()) }
            UnifiedPush.saveDistributor(context, distributor)
            UnifiedPush.register(context, state.getString("instance"), "PocketWorld 通知唤醒（无任务正文）", state.getString("vapid"))
        }
    }
    override fun needsMaintenance(context: Context): Boolean {
        val state = active(context)
        return state.length() > 0 && !credentialsRequired(state) &&
            (state.has("challenge") || state.has("subscription") && state.optLong("registeredAt") == 0L)
    }
    override fun maintain(context: Context) {
        val state = active(context)
        if (state.length() == 0) return
        val subscription = state.optJSONObject("subscription")
        if ((subscription == null || state.optBoolean("registrationMissing") || state.optString("transportProblem") in setOf("registration_failed", "unregistered", "distributor_unavailable")) &&
            System.currentTimeMillis() - state.optLong("lastRegisterRequestAt") >= 60_000) reconcile(context)
        if (subscription == null) return
        val instance = state.getString("instance")
        if (credentialsRequired(state)) return
        if (System.currentTimeMillis() - state.optLong("registeredAt") >= 24 * 60 * 60 * 1000L) {
            val result = try {
                request(state.getString("root"), "/v1/push/register", state.getString("registrationToken"), body = JSONObject()
                    .put("deviceId", instance).put("secret", state.getString("secret")).put("subscription", subscription))
            } catch (error: Exception) {
                val denied = error is RequestFailure && (error.code == 401 || error.code == 403)
                failed(context, instance, if (denied) "credentials_required" else "service_unavailable")
                if (denied) return else throw error
            }
            val retained = change(context, instance) {
                // Preserve a challenge received while HTTPS registration was in flight.
                if (it.optJSONObject("subscription")?.toString() == subscription.toString() &&
                    it.optString("endpointGeneration") == state.optString("endpointGeneration")) {
                    it.put("registeredAt", System.currentTimeMillis())
                    it.remove("registrationMissing")
                    if (!result.optBoolean("verified")) {
                        it.put("verifiedAt", 0)
                        if (!it.has("challenge")) it.put("status", "awaiting_challenge")
                    }
                }
            }
            if (!retained) {
                try { request(state.getString("root"), "/v1/push/device", state.getString("secret"), "DELETE", JSONObject().put("deviceId", instance)) }
                catch (_: Exception) { /* A registration finishing after local retirement is still lease-bounded. */ }
            }
        }
    }
    override fun acknowledge(context: Context) {
        val state = active(context)
        if (state.length() == 0 || !state.has("subscription") || credentialsRequired(state) && state.optLong("registeredAt") == 0L) return
        // A failed renewal token does not revoke the separate device secret for a live registration.
        // Continue acknowledging durable catch-up, but never hide the credential repair requirement.
        val instance = state.getString("instance")
        val cursor = synchronized(store(context)) {
            if (active(context).optString("instance") != instance) return
            store(context).cursor()
        }
        val body = JSONObject().put("deviceId", instance).put("cursor", cursor)
        if (state.has("challenge")) body.put("challenge", state.getString("challenge"))
        val response = try { request(state.getString("root"), "/v1/push/ack", state.getString("secret"), body = body) }
        catch (error: Exception) {
            if (error is RequestFailure && (error.code == 401 || error.code == 410)) change(context, instance) {
                if (it.optString("endpointGeneration") != state.optString("endpointGeneration") ||
                    it.optJSONObject("subscription")?.toString() != state.optJSONObject("subscription")?.toString()) return@change
                it.put("registeredAt", 0).put("verifiedAt", 0).put("status", "registration_missing").put("registrationMissing", true)
                it.remove("challenge") // Proof for an expired/removed registration cannot verify its replacement.
            }
            throw error
        }
        if (response.optBoolean("verified")) change(context, instance) {
            // An endpoint/key rotation may complete while this HTTPS ack is in flight.
            // Its proof belongs only to the original registration generation and challenge.
            if (it.optString("endpointGeneration") != state.optString("endpointGeneration") ||
                it.optJSONObject("subscription")?.toString() != state.optJSONObject("subscription")?.toString() ||
                it.optString("challenge") != state.optString("challenge")) return@change
            it.put("verifiedAt", System.currentTimeMillis()).put("lastCatchUpAt", System.currentTimeMillis()).put("status", "caught_up")
            if (it.optString("challenge") == state.optString("challenge")) it.remove("challenge")
        }
    }
}
