package dev.pocketengine.media

import android.util.Base64
import io.livekit.android.token.TokenRequestOptions
import io.livekit.android.token.TokenSource
import io.livekit.android.token.TokenSourceResponse
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Official endpoint schema, not a token signer or an AI backend. No network until owner acceptance. */
class AuthenticatedLiveKitProvider internal constructor(
    private val profile: RealtimeEndpointProfile,
    private val secret: RealtimeCallProvider.SecretReader,
    private val client: OkHttpClient
) : RealtimeCallProvider {
    constructor(profile: RealtimeEndpointProfile, secret: RealtimeCallProvider.SecretReader) : this(profile, secret, httpClient())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicReference<Attempt?>()
    @Volatile private var closed = false
    private class Attempt(val id: String, val mic: Boolean, val camera: Boolean) {
        val result = CompletableFuture<RealtimeCallProvider.JoinCredentials>()
        @Volatile var call: Call? = null
        @Volatile var job: Job? = null
        @Volatile var cancelled = false
    }
    // The SDK owns its token abstraction; this app supplies only bounded authenticated HTTP glue.
    private val tokens = TokenSource.fromCustom { options ->
        try { Result.success(fetch(options)) } catch (failure: Exception) { Result.failure(failure) }
    }
    override fun id() = "authenticated_livekit_endpoint"
    override fun supportsIncomingInvitations() = false
    override fun accept(invitationId: String, microphone: Boolean, camera: Boolean): CompletableFuture<RealtimeCallProvider.JoinCredentials> {
        val attempt = Attempt(invitationId, microphone, camera)
        if (closed || !invitationId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) || !active.compareAndSet(null, attempt)) {
            attempt.result.completeExceptionally(failure("admission_busy")); return attempt.result
        }
        val job = scope.launch {
            try {
                ensureActive(); if (attempt.cancelled || closed) throw CancellationException()
                val room = "pw-$invitationId"
                val identity = "owner-$invitationId"
                val response = tokens.fetch(TokenRequestOptions(roomName = room, participantIdentity = identity, agentName = profile.agentName)).getOrThrow()
                ensureActive(); if (attempt.cancelled || closed) throw CancellationException()
                val expiry = validateJoin(response, room, identity, attempt.mic, attempt.camera)
                attempt.result.complete(RealtimeCallProvider.JoinCredentials(response.serverUrl, response.participantToken, expiry))
            } catch (error: Exception) {
                attempt.result.completeExceptionally(if (error is RealtimeCallProvider.ProviderFailure) error else failure(if (attempt.cancelled || closed) "cancelled" else "request_unconfirmed"))
            } finally { active.compareAndSet(attempt, null) }
        }
        attempt.job = job
        job.invokeOnCompletion { active.compareAndSet(attempt, null); if (!attempt.result.isDone) attempt.result.completeExceptionally(failure("cancelled")) }
        attempt.result.whenComplete { _, _ -> if (attempt.result.isCancelled) cancelAttempt(attempt) }
        return attempt.result
    }
    /** This adapter creates outgoing preparations, never claims an incoming remote rejection receipt. */
    override fun reject(invitationId: String): CompletableFuture<Void> { cancelAccept(invitationId); return CompletableFuture.completedFuture(null) }
    override fun cancelAccept(invitationId: String) { active.get()?.takeIf { it.id == invitationId }?.let(::cancelAttempt) }
    private fun cancelAttempt(attempt: Attempt) { attempt.cancelled = true; attempt.call?.cancel(); attempt.job?.cancel(); attempt.result.completeExceptionally(failure("cancelled")) }
    override fun close() { closed = true; active.get()?.let(::cancelAttempt); scope.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }

    private fun fetch(options: TokenRequestOptions): TokenSourceResponse {
        val attempt = active.get() ?: throw failure("cancelled")
        if (closed || attempt.cancelled) throw failure("cancelled")
        val bearer = secret.read()
        if (bearer.isBlank() || bearer.length > 16384 || bearer.any { it.isWhitespace() || it.isISOControl() }) throw failure("authentication_missing")
        val body = JSONObject().put("room_name", options.roomName).put("participant_identity", options.participantIdentity)
            .put("room_config", JSONObject().put("agents", JSONArray().put(JSONObject().put("agent_name", profile.agentName))))
        val request = Request.Builder().url(profile.endpointUrl).post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer $bearer").header("Accept", "application/json").header("Cache-Control", "no-store")
            .header("X-PocketWorld-Call-ID", attempt.id).build()
        val call = client.newCall(request); attempt.call = call
        if (attempt.cancelled || closed) { call.cancel(); throw failure("cancelled") }
        call.execute().use { response ->
            if (response.code != 201) throw failure(when(response.code) { 401,403 -> "authentication_rejected"; 429 -> "rate_limited"; in 300..399 -> "redirect_refused"; else -> "endpoint_rejected" })
            val payload = response.body ?: throw failure("invalid_response")
            if (payload.contentLength() > MAX_RESPONSE) throw failure("response_too_large")
            val bytes = ByteArrayOutputStream()
            payload.byteStream().use { stream ->
                val buffer = ByteArray(4096)
                while (true) { val count = stream.read(buffer); if (count < 0) break; if (bytes.size() + count > MAX_RESPONSE) throw failure("response_too_large"); bytes.write(buffer,0,count) }
            }
            val parsed = try { JSONObject(bytes.toString(Charsets.UTF_8.name())) } catch (_: Exception) { throw failure("invalid_response") }
            val server = parsed.opt("server_url") as? String ?: throw failure("invalid_response")
            val token = parsed.opt("participant_token") as? String ?: throw failure("invalid_response")
            if (token.isBlank() || token.length > 16384 || token.any { it.isWhitespace() || it.isISOControl() }) throw failure("invalid_token")
            return TokenSourceResponse(serverUrl = server, participantToken = token)
        }
    }
    private fun validateJoin(response: TokenSourceResponse, room: String, identity: String, mic: Boolean, camera: Boolean): Long {
        try {
            RealtimeEndpointProfile(profile.endpointUrl, response.serverUrl, profile.agentName, profile.revision)
            if (RealtimeEndpointProfile.origin(response.serverUrl) != RealtimeEndpointProfile.origin(profile.serverUrl)) throw failure("room_origin_changed")
            val parts = response.participantToken.split('.')
            if (parts.size != 3 || parts.any { it.isEmpty() }) throw failure("invalid_token")
            val claims = JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
            val expiration = claims.opt("exp")
            if (expiration !is Int && expiration !is Long) throw failure("invalid_token")
            val expiresAt = Math.multiplyExact((expiration as Number).toLong(),1000L)
            val now = System.currentTimeMillis()
            if (expiresAt < now + 10_000 || expiresAt > now + 600_000) throw failure("token_lifetime_invalid")
            if (claims.has("nbf") && claims.getLong("nbf") > now / 1000 + 30) throw failure("token_not_yet_valid")
            val grants = claims.optJSONObject("video") ?: throw failure("invalid_token")
            if (claims.optString("sub") != identity || !grants.optBoolean("roomJoin") || grants.optString("room") != room) throw failure("token_binding_mismatch")
            if ((mic || camera) && !grants.optBoolean("canPublish",true)) throw failure("capture_not_granted")
            grants.optJSONArray("canPublishSources")?.let { sources ->
                val allowed = (0 until sources.length()).map { sources.optString(it) }
                if (mic && "microphone" !in allowed || camera && "camera" !in allowed) throw failure("capture_not_granted")
            }
            // These are consistency/lifetime checks. Only the configured LiveKit server verifies JWT signatures.
            return expiresAt
        } catch (error: RealtimeCallProvider.ProviderFailure) { throw error }
        catch (_: Exception) { throw failure("invalid_token") }
    }
    companion object {
        private const val MAX_RESPONSE = 64 * 1024
        internal fun httpClient() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(15,TimeUnit.SECONDS).connectTimeout(10,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).build()
        private fun failure(code: String) = RealtimeCallProvider.ProviderFailure(code)
    }
}
