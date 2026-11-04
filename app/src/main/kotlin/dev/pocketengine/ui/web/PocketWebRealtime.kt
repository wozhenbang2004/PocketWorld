package dev.pocketengine.ui.web

import android.os.SystemClock
import dev.pocketengine.media.RealtimeEndpointProfile
import dev.pocketengine.media.voice.RealtimeVoiceProfile
import dev.pocketengine.ui.PocketWorldController
import dev.pocketengine.ui.objects
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** Bounded presentation reviews around RealtimeSessionModule's existing owner/voice-task path. */
internal class PocketWebRealtime(private val controller: PocketWorldController) {
    private data class Review(val command: String, val input: String, val configuration: Long, val modelRevision: Long?, val expires: Long, val generation: Long)
    @Volatile private var generation = 0L
    private var snapshot = JSONObject()
    private val reviews = mutableMapOf<String, Review>()
    companion object {
        val COMMANDS = setOf("realtime.snapshot", "realtime.reviewConfiguration", "realtime.prepare", "realtime.reviewInvitation", "realtime.reviewAction",
            "realtime.confirm", "realtime.control", "realtime.requestMicrophonePermission", "realtime.requestCameraPermission", "realtime.reviewTurn",
            "realtime.reconcileTurn", "realtime.listVoiceTasks", "realtime.recoverVoiceTask", "realtime.inspectVoiceTask", "realtime.closeReview")
    }
    fun clear() { generation++; snapshot = JSONObject(); reviews.clear() }
    private fun fields(args: JSONObject, vararg names: String) { require(args.keys().asSequence().all { it in names }) { "通话操作包含不适用的字段" } }
    private fun text(args: JSONObject, name: String, max: Int = 200, optional: Boolean = false): String {
        val value = args.opt(name)
        if (optional && value == null) return ""
        require(value is String && value.length <= max && '\u0000' !in value && (optional || value.isNotBlank())) { "请核对$name" }
        return value
    }
    private fun bool(args: JSONObject, name: String): Boolean { require(args.opt(name) is Boolean) { "请核对$name" }; return args.getBoolean(name) }
    private fun number(args: JSONObject, name: String): Long {
        val value = args.opt(name); require(value is Number) { "请核对原版本" }
        return java.math.BigDecimal(value.toString()).longValueExact().also { require(it >= 0) }
    }
    private fun public(value: JSONObject) = PocketWebState.publicValue(value) as JSONObject
    private fun update(value: JSONObject) {
        if (value.has("snapshotRevision") && (!snapshot.has("snapshotRevision") || value.optLong("snapshotRevision") >= snapshot.optLong("snapshotRevision"))) snapshot = JSONObject(value.toString())
    }
    private fun currentSnapshot(): JSONObject {
        update(controller.state.feature("realtime"))
        check(snapshot.has("snapshotRevision")) { "请先读取真实通话状态" }
        return snapshot
    }
    private fun configuration() = currentSnapshot().optJSONObject("configuration")?.optLong("revision") ?: 0L
    private fun session(args: JSONObject): JSONObject {
        val id = text(args, "sessionId")
        check(id == currentSnapshot().optString("sessionId")) { "原通话已变化，请刷新后再操作" }
        return JSONObject().put("sessionId", id)
    }
    private fun voiceTask(args: JSONObject, cancel: Boolean = false): JSONObject {
        val current = currentSnapshot().optJSONObject("voiceTask") ?: error("原语音任务尚未读取")
        val binding = text(args, "bindingId"); val version = text(args, "viewToken")
        check(binding == current.optString("bindingId") && version == current.optString(if (cancel) "cancelToken" else "viewToken")) { "原语音任务执行或读取版本已变化，请重新核对" }
        return JSONObject().put("bindingId", binding).put("viewToken", version)
    }
    private fun review(command: String, input: JSONObject, shown: JSONObject, configuration: Long = -1, modelRevision: Long? = null): JSONObject {
        reviews.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
        check(reviews.size < 8) { "请先关闭已有通话审阅" }
        val handle = UUID.randomUUID().toString()
        reviews[handle] = Review(command, input.toString(), configuration, modelRevision, SystemClock.elapsedRealtime() + 120_000, generation)
        val visible = JSONObject(shown.toString()).also { it.remove("authenticationToken"); it.remove("participantToken") }
        if (configuration >= 0) visible.put("configurationRevision", configuration)
        return JSONObject().put("reviewHandle", handle).put("command", command).put("expiresAt", System.currentTimeMillis() + 120_000).put("details", public(visible))
    }
    fun dispatch(action: String, args: JSONObject, done: (JSONObject?, String?) -> Unit) {
        check(controller.webPrivateOwnerVisible()) { "请回到前台私人通话页面" }
        val original = generation; val window = controller.webWindowTicket()
        fun current() = original == generation && window == controller.webWindowTicket() && controller.webPrivateOwnerVisible()
        fun native(command: String, input: JSONObject = JSONObject(), config: Long = -1, modelRevision: Long? = null, receive: ((JSONObject) -> JSONObject)? = null) {
            fun admitted(): Boolean {
                if (!current()) return false
                if (modelRevision == null) return true
                val models = controller.nativeModelSelection().status()
                return !models.pending() && models.revision() == modelRevision
            }
            controller.requestWebRealtimeReceipt(command, input, config, ::admitted) { value, error ->
                if (current()) {
                    if (value == null || error != null) done(null, error ?: "原通话操作没有确定回执")
                    else try {
                        update(value); update(controller.state.feature("realtime"))
                        done(receive?.invoke(value) ?: JSONObject().put("confirmed", true).put("command", command).put("result", public(value)).put("snapshot", public(snapshot)), null)
                    } catch (failure: Exception) { done(null, failure.message ?: "原通话回执待核对") }
                }
            }
        }
        when (action) {
            "realtime.snapshot" -> { fields(args); native("snapshot", receive = { public(it) }) }
            "realtime.closeReview" -> { fields(args, "reviewHandle"); reviews.remove(text(args, "reviewHandle")); done(JSONObject().put("closed", true), null) }
            "realtime.requestMicrophonePermission", "realtime.requestCameraPermission" -> { fields(args); native(action.substringAfter('.')) }
            "realtime.reviewConfiguration" -> {
                fields(args, "kind", "endpointUrl", "model", "voice", "transcriptionModel", "serverUrl", "agentName", "authenticationToken", "participantToken", "allowLoopbackWs", "clearToken")
                val revision = configuration(); val kind = text(args, "kind", 16)
                val input = JSONObject(); val command: String
                when (kind) {
                    "natural" -> {
                        check(currentSnapshot().optBoolean("naturalVoiceSupported")) { "自然语音适配器尚未接入" }
                        val profile = RealtimeVoiceProfile(text(args, "endpointUrl", 2048), text(args, "model", 120), text(args, "voice", 80), text(args, "transcriptionModel", 120, true), 1)
                        input.put("endpointUrl", profile.endpointUrl).put("model", profile.model).put("voice", profile.voice).put("transcriptionModel", profile.transcriptionModel)
                            .put("authenticationToken", text(args, "authenticationToken", 16384, true))
                        command = "configureNaturalVoice"
                    }
                    "provider" -> {
                        check(currentSnapshot().optJSONObject("capabilities")?.optBoolean("endpointProviderSupported") == true) { "通话服务适配器尚未接入" }
                        val profile = RealtimeEndpointProfile(text(args, "endpointUrl", 2048), text(args, "serverUrl", 2048), text(args, "agentName", 120), 1)
                        input.put("endpointUrl", profile.endpointUrl).put("serverUrl", profile.serverUrl).put("agentName", profile.agentName)
                            .put("authenticationToken", text(args, "authenticationToken", 16384, true))
                        command = "configureEndpointProvider"
                    }
                    "room" -> {
                        val endpoint = text(args, "serverUrl", 2048); val uri = URI(endpoint); val loopback = bool(args, "allowLoopbackWs")
                        val local = uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
                        require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.fragment == null && (uri.scheme == "wss" || loopback && local && uri.scheme == "ws")) { "房间需要 WSS 地址；只有明确选择的本机回环可用 WS" }
                        val token = text(args, "participantToken", 16384, true); val clear = bool(args, "clearToken")
                        require(!clear || token.isBlank()) { "清除房间令牌时请留空新令牌" }
                        input.put("serverUrl", endpoint).put("participantToken", token).put("allowLoopbackWs", loopback).put("clearToken", clear)
                        command = "configure"
                    }
                    else -> error("请选择已接入的通话类型")
                }
                val shown = JSONObject(input.toString()).put("credentialProvided", input.optString("authenticationToken").isNotBlank() || input.optString("participantToken").isNotBlank())
                done(review(command, input, shown, revision), null)
            }
            "realtime.prepare" -> {
                fields(args, "kind")
                val command = when (text(args, "kind", 16)) { "natural" -> "prepareNaturalVoice"; "provider" -> "prepareProviderCall"; "room" -> "prepareCall"; else -> error("通话类型无效") }
                native(command, config = configuration())
            }
            "realtime.reviewInvitation" -> {
                fields(args, "invitationId", "microphone", "camera")
                val value = currentSnapshot(); val invitation = value.optJSONObject("invitation") ?: error("原邀请已不存在")
                val id = text(args, "invitationId"); val microphone = bool(args, "microphone"); val camera = bool(args, "camera")
                check(id == invitation.optString("id") && value.optString("invitationState") == "awaiting_owner" && invitation.optLong("expiresAt") > System.currentTimeMillis()) { "原邀请已处理或过期，请重新读取" }
                check(!microphone || value.optBoolean("microphonePermission")) { "请先授予麦克风权限，再明确核对这次采集" }
                check(!camera || value.optBoolean("cameraPermission")) { "请先授予摄像头权限，再明确核对这次采集" }
                require(!camera || invitation.optString("source") != "owner_prepared_natural_voice") { "此语音服务不支持摄像头" }
                done(review("acceptInvitation", JSONObject().put("invitationId", id).put("microphone", microphone).put("camera", camera),
                    JSONObject(invitation.toString()).put("microphone", microphone).put("camera", camera)), null)
            }
            "realtime.reviewAction" -> {
                fields(args, "command", "sessionId", "bindingId", "viewToken")
                val command = text(args, "command", 40); val value = currentSnapshot()
                var revision = -1L; var modelRevision: Long? = null
                val input = when (command) {
                    "startVoiceTurn" -> {
                        val selected = controller.nativeModelSelection().status(); check(!selected.pending()) { "模型选择仍在保存，请等待原回执" }; modelRevision = selected.revision(); JSONObject()
                    }
                    "clearNaturalVoice", "clearEndpointProvider" -> { revision = configuration(); JSONObject() }
                    "enableMicrophone" -> { check(value.optBoolean("microphonePermission")); session(args).put("muted", false) }
                    "enableCamera" -> { check(value.optBoolean("cameraPermission")); session(args).put("enabled", true) }
                    "startVoiceTask", "readVoiceTask", "cancelVoiceTask" -> voiceTask(args, command == "cancelVoiceTask")
                    else -> error("这项通话操作不能从当前页面审阅")
                }
                val nativeCommand = when (command) { "enableMicrophone" -> "mute"; "enableCamera" -> "setCamera"; else -> command }
                val shown = JSONObject(input.toString()).put("goal", value.optJSONObject("voiceTask")?.optString("goal").orEmpty())
                if (command == "startVoiceTurn") shown.put("model", controller.state.modelName).put("rawAudioToTextModel", false).put("automaticallyExecutesTasks", false)
                done(review(nativeCommand, input, shown, revision, modelRevision), null)
            }
            "realtime.confirm" -> {
                fields(args, "reviewHandle")
                val reviewed = reviews.remove(text(args, "reviewHandle")) ?: error("原通话审阅已关闭或使用，请重新核对实际状态")
                check(reviewed.generation == generation && reviewed.expires > SystemClock.elapsedRealtime()) { "原通话审阅已过期" }
                native(reviewed.command, JSONObject(reviewed.input), reviewed.configuration, reviewed.modelRevision)
            }
            "realtime.control" -> {
                fields(args, "command", "sessionId", "invitationId", "voiceTurnId", "readoutId")
                val command = text(args, "command", 40); val value = currentSnapshot()
                val input = when (command) {
                    "disconnect", "interruptOutput", "mute", "setCamera" -> if (command == "interruptOutput" && args.has("readoutId")) {
                        val id = text(args, "readoutId"); check(id == value.optJSONObject("voiceTask")?.optJSONObject("readout")?.optString("readoutId")) { "原朗读已经变化" }; JSONObject().put("readoutId", id)
                    } else session(args).also { if (command == "mute") it.put("muted", true); if (command == "setCamera") it.put("enabled", false) }
                    "rejectInvitation" -> { val id = text(args, "invitationId"); check(id == value.optJSONObject("invitation")?.optString("id")); JSONObject().put("invitationId", id) }
                    "stopVoiceTurn" -> { val id = text(args, "voiceTurnId"); check(id == value.optJSONObject("voiceTurn")?.optString("id")); JSONObject().put("voiceTurnId", id) }
                    else -> error("这项操作需要先核对当前范围")
                }
                native(command, input)
            }
            "realtime.reviewTurn" -> {
                fields(args, "sessionId", "turnId", "revision", "startTask")
                val input = session(args).put("turnId", text(args, "turnId")).put("revision", number(args, "revision")); val start = bool(args, "startTask")
                if (start) check(currentSnapshot().optJSONObject("capabilities")?.optBoolean("voiceTaskHost") == true) { "语音任务宿主尚未接入" }
                native("reviewTurn", input, receive = { value ->
                    check(value.optString("sessionId") == input.getString("sessionId") && value.optString("turnId") == input.getString("turnId") && value.optLong("revision") == input.getLong("revision")) { "原字幕复核回执不匹配" }
                    review(if (start) "commitVoiceTask" else "commitTurn", JSONObject().put("sessionId", value.getString("sessionId")).put("reviewId", value.getString("reviewId")).put("contentHash", value.getString("contentHash")), value)
                })
            }
            "realtime.reconcileTurn" -> {
                fields(args, "commitId"); val id = text(args, "commitId")
                check(currentSnapshot().optJSONObject("transcription")?.optJSONArray("turns").objects().any { it.optString("commitId") == id && it.optString("commitState") == "unknown" }) { "请先读取这次未确认的原提交" }
                native("reconcileTurn", JSONObject().put("commitId", id))
            }
            "realtime.listVoiceTasks" -> { fields(args); native("listVoiceTasks") }
            "realtime.recoverVoiceTask" -> {
                fields(args, "taskId"); val id = text(args, "taskId")
                check(currentSnapshot().optJSONObject("voiceTask")?.optJSONArray("recentTasks").objects().any { it.optString("taskId") == id }) { "请先读取这项已保存语音任务" }
                native("recoverVoiceTask", JSONObject().put("taskId", id))
            }
            "realtime.inspectVoiceTask" -> { fields(args, "bindingId", "viewToken"); native("inspectVoiceTask", voiceTask(args)) }
            else -> error("通话操作尚未接入")
        }
    }
}
