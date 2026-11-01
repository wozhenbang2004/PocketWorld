package dev.pocketengine.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.os.Handler
import android.os.Looper
import dev.pocketengine.core.EventSink
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.participant.Participant
import io.livekit.android.renderer.TextureViewRenderer
import livekit.org.webrtc.RendererCommon
import org.json.JSONArray
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

/** Official SDK adapter, independently written for Pocket World's visible-owner call contract. */
class LiveKitMediaSession(context: Context, private val events: EventSink) : RealtimeMediaSession {
    private val android = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generation = AtomicLong()
    private val transcriptSequence = AtomicLong()
    private val transcriptContinuity = AtomicLong()
    private val transcriptJobs = mutableSetOf<Job>()
    private val stateLock = Any()
    private var room: Room? = null
    private var connectionJob: Job? = null
    private var eventJob: Job? = null
    private var pendingConnection: CompletableFuture<JSONObject>? = null
    private val disposedRooms = java.util.WeakHashMap<Room, Boolean>()
    @Volatile private var cleanupFailed = false
    @Volatile private var closed = false
    private var state = "idle"
    private var microphone = false
    private var camera = false
    private var videos = JSONArray()
    private var localVideoTrackSid = ""
    private var videoRevision = 0L
    private var renderTracks: Map<String, VideoTrack> = emptyMap()
    private val bindings = mutableSetOf<RendererBinding>()
    private var remoteAudio = false
    private var participants = 0
    private var message = "尚未连接实时语音"

    override fun snapshot(): JSONObject = synchronized(stateLock) {
        JSONObject().put("state", state).put("microphoneEnabled", microphone)
            .put("remoteAudioSubscribed", remoteAudio).put("remoteParticipants", participants)
            .put("cameraEnabled", camera).put("localVideoTrackSid", localVideoTrackSid).put("videoRevision", videoRevision).put("remoteVideos", JSONArray(videos.toString())).put("screenShareEnabled", false).put("avatarSupported", false)
            .put("backgroundCall", false).put("resourceReleaseUnknown", cleanupFailed).put("sdk", "livekit-android:2.29.2").put("message", message)
            .put("transport", "livekit").put("fullDuplexVoice", false).put("outputInterruptionSupported", false)
            .put("playbackClock", "unavailable").put("clockUnavailable", true)
    }

    override fun connect(serverUrl: String, participantToken: String, microphone: Boolean): CompletableFuture<JSONObject> =
        connect(serverUrl, participantToken, microphone, false)

    override fun connect(serverUrl: String, participantToken: String, microphone: Boolean, camera: Boolean): CompletableFuture<JSONObject> {
        val result = CompletableFuture<JSONObject>()
        val epoch = generation.incrementAndGet()
        onMain {
            if (closed || epoch != generation.get()) {
                result.completeExceptionally(IllegalStateException("实时语音连接已失效"))
                return@onMain
            }
            stopCurrent("连接已被替换")
            if (cleanupFailed) {
                change("failed", "上一语音连接的资源释放结果未知，请先检查系统麦克风状态并重启应用进程")
                result.completeExceptionally(IllegalStateException("上一语音连接的资源释放结果未知"))
                return@onMain
            }
            pendingConnection = result
            change("connecting", "正在连接真实语音房间")
            connectionJob = scope.launch {
                var owned: Room? = null
                try {
                    withTimeout(30_000) {
                        val created = LiveKit.create(android)
                        created.adaptiveStream = true
                        owned = created
                        if (closed || epoch != generation.get()) throw CancellationException()
                        room = created
                        installTranscriptionReceiver(created, epoch)
                        eventJob = scope.launch {
                            created.events.collect { event ->
                                if (!closed && epoch == generation.get() && room === created) handleEvent(created, event)
                            }
                        }
                        created.connect(serverUrl, participantToken, ConnectOptions(autoSubscribe = false, audio = false, video = false))
                        if (closed || epoch != generation.get() || room !== created) throw CancellationException()
                        subscribeMedia(created)
                        if (microphone) requirePermission(Manifest.permission.RECORD_AUDIO)
                        if (microphone && !created.localParticipant.setMicrophoneEnabled(true)) {
                            throw IllegalStateException("麦克风发布未能完成")
                        }
                        if (closed || epoch != generation.get() || room !== created) throw CancellationException()
                        if (camera) {
                            requirePermission(Manifest.permission.CAMERA)
                            if (!created.localParticipant.setCameraEnabled(true)) throw IllegalStateException("摄像头发布未能完成")
                        }
                        if (closed || epoch != generation.get() || room !== created) throw CancellationException()
                        refreshMedia(created)
                        change("connected", if (snapshot().optBoolean("remoteAudioSubscribed")) "实时语音已连接" else "已加入房间，等待语音对端")
                        result.complete(snapshot())
                    }
                } catch (cancelled: CancellationException) {
                    owned?.let(::dispose)
                    if (epoch == generation.get() && !closed) change("disconnected", "实时语音连接已停止")
                    result.completeExceptionally(IllegalStateException("实时语音连接已停止"))
                } catch (_: Exception) {
                    owned?.let(::dispose)
                    if (epoch == generation.get() && !closed) change("failed", "实时语音连接失败，请检查服务器、令牌和网络")
                    result.completeExceptionally(IllegalStateException("实时语音连接失败，请检查服务器、令牌和网络"))
                } catch (_: LinkageError) {
                    owned?.let(::dispose)
                    if (epoch == generation.get() && !closed) change("failed", "这台设备未能加载实时语音组件")
                    result.completeExceptionally(IllegalStateException("实时语音组件未能加载"))
                } finally {
                    if (pendingConnection === result) pendingConnection = null
                }
            }
        }
        return result
    }

    override fun setMicrophoneEnabled(enabled: Boolean): CompletableFuture<JSONObject> {
        val result = CompletableFuture<JSONObject>()
        val epoch = generation.get()
        onMain {
            val current = room
            if (closed || current == null || snapshot().optString("state") != "connected" || epoch != generation.get()) {
                result.completeExceptionally(IllegalStateException("实时语音尚未连接"))
                return@onMain
            }
            scope.launch {
                try {
                    if (enabled) requirePermission(Manifest.permission.RECORD_AUDIO)
                    val accepted = withTimeout(8_000) { current.localParticipant.setMicrophoneEnabled(enabled) }
                    if (!accepted || closed || epoch != generation.get() || room !== current) {
                        current.setMicrophoneMute(true)
                        throw IllegalStateException("麦克风操作未完成或已取消")
                    }
                    refreshMedia(current)
                    publish()
                    result.complete(snapshot())
                } catch (_: Exception) {
                    result.completeExceptionally(IllegalStateException("麦克风操作未完成"))
                }
            }
        }
        return result
    }

    override fun setCameraEnabled(enabled: Boolean): CompletableFuture<JSONObject> {
        val result = CompletableFuture<JSONObject>()
        val epoch = generation.get()
        onMain {
            val current = room
            if (closed || current == null || snapshot().optString("state") != "connected" || epoch != generation.get()) {
                result.completeExceptionally(IllegalStateException("通话尚未连接"))
                return@onMain
            }
            scope.launch {
                try {
                    if (enabled) requirePermission(Manifest.permission.CAMERA)
                    val accepted = withTimeout(8_000) { current.localParticipant.setCameraEnabled(enabled) }
                    if (!accepted || closed || epoch != generation.get() || room !== current) {
                        (current.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack)?.stopCapture()
                        throw IllegalStateException("摄像头操作未完成或已取消")
                    }
                    refreshMedia(current)
                    publish()
                    result.complete(snapshot())
                } catch (_: Exception) {
                    result.completeExceptionally(IllegalStateException("摄像头操作未完成"))
                }
            }
        }
        return result
    }

    override fun bindVideo(context: Context, trackSid: String): RealtimeMediaSession.VideoBinding {
        check(Looper.myLooper() == Looper.getMainLooper()) { "视频视图必须在主线程绑定" }
        val current = checkNotNull(room) { "通话已结束" }
        check(!closed && snapshot().optString("state") == "connected") { "媒体尚未连接" }
        val local = current.localParticipant.getTrackPublication(Track.Source.CAMERA)
            ?.takeIf { it.sid == trackSid && !it.muted && current.localParticipant.isCameraEnabled }
        val publication = local ?: current.remoteParticipants.values.flatMap { it.trackPublications.values }
            .firstOrNull { it.sid == trackSid && it.subscribed && !it.muted }
            ?: throw IllegalStateException("视频轨道已离开")
        val track = publication.track as? VideoTrack ?: throw IllegalStateException("远端没有视频轨道")
        val renderer = TextureViewRenderer(context)
        current.initVideoRenderer(renderer)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setMirror(local != null)
        val binding = RendererBinding(trackSid, track, renderer)
        try { track.addRenderer(renderer); bindings.add(binding) }
        catch (error: Exception) { renderer.release(); throw error }
        return binding
    }

    private inner class RendererBinding(val sid: String, val track: VideoTrack, val renderer: TextureViewRenderer) : RealtimeMediaSession.VideoBinding {
        private var released = false
        override fun view(): View = renderer
        override fun close() = onMain {
            if (!released) {
                released = true
                try { track.removeRenderer(renderer) } catch (_: Exception) { }
                try { renderer.clearImage(); renderer.release() } catch (_: Exception) { }
                bindings.remove(this)
            }
        }
    }

    private fun requirePermission(permission: String) {
        if (android.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw SecurityException("采集权限已撤销")
    }

    override fun disconnect(reason: String) {
        generation.incrementAndGet()
        onMain {
            if (!closed) change("disconnecting", "正在停止实时语音")
            stopCurrent("实时语音已停止")
            if (!closed) {
                if (cleanupFailed) change("failed", "实时语音资源释放未完成，请检查系统麦克风状态")
                else change("disconnected", if (reason == "owner_backgrounded") "已离开应用，摄像头、麦克风和房间已关闭" else if (reason == "permission_revoked") "权限已撤销，通话已停止" else "通话已挂断，已有任务继续运行")
            }
        }
    }

    @OptIn(io.livekit.android.annotations.Beta::class)
    private fun handleEvent(current: Room, event: RoomEvent) {
        when (event) {
            is RoomEvent.TranscriptionReceived -> {
                val identity = event.participant?.identity?.value.orEmpty()
                val sid = event.publication?.sid.orEmpty()
                val local = event.participant?.identity == current.localParticipant.identity &&
                    sid.isNotEmpty() && sid == current.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.sid
                event.transcriptionSegments.take(64).forEach { segment ->
                    val sequence = transcriptSequence.incrementAndGet()
                    if (segment.text.length > RealtimeTurnBuffer.MAX_TEXT) {
                        // Invalidate an older review, rather than retaining an obsolete final caption.
                        transcript(current, generation.get(), sequence, identity, sid, segment.id, "", "begin", false, true, local, "legacy_event")
                        transcript(current, generation.get(), sequence, identity, sid, segment.id, "", "failed", false, true, local, "legacy_event")
                    } else transcript(current, generation.get(), sequence, identity, sid, segment.id,
                        segment.text, "legacy", segment.final, segment.final, local, "legacy_event")
                }
            }
            is RoomEvent.Reconnecting -> {
                transcriptContinuity.incrementAndGet()
                bindings.toList().forEach { it.close() }
                synchronized(stateLock) { remoteAudio = false; videos = JSONArray(); renderTracks = emptyMap(); videoRevision++ }
                change("reconnecting", "语音网络中断，正在恢复当前连接")
                transcriptJobs.toList().forEach { it.cancel() }
                transcriptJobs.clear()
            }
            is RoomEvent.Reconnected -> {
                subscribeMedia(current)
                refreshMedia(current)
                change("connected", "真实房间连接已恢复")
            }
            is RoomEvent.TrackPublished -> {
                (event.publication as? RemoteTrackPublication)?.let { if (it.kind == Track.Kind.AUDIO || it.kind == Track.Kind.VIDEO) it.setSubscribed(true) }
                refreshMedia(current)
                publish()
            }
            is RoomEvent.ParticipantConnected -> {
                subscribeMedia(current)
                refreshMedia(current)
                publish()
            }
            is RoomEvent.TrackSubscribed, is RoomEvent.TrackUnsubscribed, is RoomEvent.ParticipantDisconnected,
            is RoomEvent.TrackMuted, is RoomEvent.TrackUnmuted, is RoomEvent.TrackUnpublished -> {
                refreshMedia(current)
                publish()
            }
            is RoomEvent.TrackSubscriptionFailed -> {
                refreshMedia(current)
                synchronized(stateLock) { message = "房间已连接，但媒体订阅失败，请核对远端服务" }
                publish()
            }
            is RoomEvent.Disconnected -> disconnect("remote_disconnected")
            is RoomEvent.FailedToConnect -> {
                generation.incrementAndGet()
                stopCurrent("实时语音连接失败")
                change("failed", "实时语音连接失败，请检查服务器、令牌和网络")
            }
            else -> Unit
        }
    }

    private fun installTranscriptionReceiver(current: Room, epoch: Long) {
        // Agents >=1.x use text streams. The deprecated Room event is a separate compatibility path.
        current.registerTextStreamHandler("lk.transcription") { reader, participant ->
            val sequence = transcriptSequence.incrementAndGet()
            val continuity = transcriptContinuity.get()
            val interrupted = snapshot().optString("state") == "reconnecting"
            onMain {
                if (closed || epoch != generation.get() || room !== current || interrupted || continuity != transcriptContinuity.get() || snapshot().optString("state") == "reconnecting") return@onMain
                val attributes = reader.info.attributes
                val segment = attributes["lk.segment_id"].orEmpty()
                val sid = attributes["lk.transcribed_track_id"].orEmpty()
                val identity = participant.value
                val finalStream = attributes["lk.transcription_final"] == "true"
                if (segment.isBlank() || segment.length > 200 || sid.isBlank() || sid.length > 200 || identity.length > 200) return@onMain
                val local = participant == current.localParticipant.identity && sid == current.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.sid
                transcript(current, epoch, sequence, identity, sid, segment, "", "begin", false, finalStream, local, "text_stream")
                if (transcriptJobs.size >= 8) {
                    transcript(current, epoch, sequence, identity, sid, segment, "", "failed", false, finalStream, local, "text_stream")
                    return@onMain
                }
                val job = scope.launch {
                    val text = StringBuilder()
                    try {
                        withTimeout(30_000) {
                            reader.flow.collect { chunk ->
                                if (text.length + chunk.length > RealtimeTurnBuffer.MAX_TEXT) throw IllegalStateException("转录过长")
                                text.append(chunk)
                                transcript(current, epoch, sequence, identity, sid, segment, text.toString(), "partial", false, finalStream, local, "text_stream")
                            }
                        }
                        // A final attribute alone is not enough: only successfully completed streams are final.
                        transcript(current, epoch, sequence, identity, sid, segment, text.toString(), "complete", finalStream, finalStream, local, "text_stream")
                    } catch (_: Exception) {
                        transcript(current, epoch, sequence, identity, sid, segment, text.toString(), "failed", false, finalStream, local, "text_stream")
                    }
                }
                transcriptJobs.add(job)
                job.invokeOnCompletion { onMain { transcriptJobs.remove(job) } }
            }
        }
    }

    private fun transcript(current: Room, epoch: Long, sequence: Long, participant: String, sid: String, segment: String,
        text: String, phase: String, final: Boolean, finalStream: Boolean, local: Boolean, protocol: String) {
        if (closed || epoch != generation.get() || room !== current || text.length > RealtimeTurnBuffer.MAX_TEXT) return
        try { events.emit(JSONObject().put("kind", "transcription").put("sequence", sequence).put("participantIdentity", participant)
            .put("trackSid", sid).put("segmentId", segment).put("text", text).put("phase", phase).put("isFinal", final)
            .put("finalStream", finalStream).put("reportedLocalMicrophone", local).put("protocol", protocol)) }
        catch (_: RuntimeException) { /* Failure to display a caption cannot authorize a task. */ }
    }

    private fun subscribeMedia(current: Room) {
        current.remoteParticipants.values.forEach { participant ->
            participant.trackPublications.values.forEach { publication ->
                (publication as? RemoteTrackPublication)?.let { it.setSubscribed(it.kind == Track.Kind.AUDIO || it.kind == Track.Kind.VIDEO) }
            }
        }
    }

    private fun refreshMedia(current: Room) {
        synchronized(stateLock) {
            microphone = current.localParticipant.isMicrophoneEnabled
            camera = current.localParticipant.isCameraEnabled
            localVideoTrackSid = if (camera) current.localParticipant.getTrackPublication(Track.Source.CAMERA)?.sid.orEmpty() else ""
            videos = JSONArray()
            val activeTracks = mutableMapOf<String, VideoTrack>()
            current.remoteParticipants.values.forEach { participant ->
                participant.trackPublications.values.filter { it.kind == Track.Kind.VIDEO && it.subscribed }.forEach { publication ->
                    val track = publication.track as? VideoTrack ?: return@forEach
                    if (!publication.muted) activeTracks[publication.sid] = track
                    videos.put(JSONObject().put("trackSid", publication.sid)
                        .put("participantIdentity", participant.identity?.value.orEmpty())
                        .put("displayName", participant.name?.take(120).orEmpty())
                        .put("source", publication.source.name.lowercase())
                        .put("muted", publication.muted)
                        .put("origin", if (participant.kind == Participant.Kind.AGENT) "server_declared_agent" else "unverified_remote")
                        .put("identityVerified", false))
                }
            }
            current.localParticipant.getTrackPublication(Track.Source.CAMERA)?.let { if (camera && it.track is VideoTrack) activeTracks[it.sid] = it.track as VideoTrack }
            if (renderTracks.keys != activeTracks.keys || activeTracks.any { (sid, track) -> renderTracks[sid] !== track }) videoRevision++
            renderTracks = activeTracks.toMap()
            bindings.toList().filter { activeTracks[it.sid] !== it.track }.forEach { it.close() }
            participants = current.remoteParticipants.size
            remoteAudio = current.remoteParticipants.values.any { participant ->
                participant.trackPublications.values.any { it.kind == Track.Kind.AUDIO && it.subscribed }
            }
        }
    }

    private fun change(next: String, text: String) {
        synchronized(stateLock) { state = next; message = text }
        publish()
    }

    private fun publish() { try { events.emit(snapshot()) } catch (_: RuntimeException) { /* Host owns its persistence error reporting. */ } }

    private fun stopCurrent(reason: String) {
        pendingConnection?.completeExceptionally(IllegalStateException(reason))
        pendingConnection = null
        connectionJob?.cancel()
        connectionJob = null
        eventJob?.cancel()
        eventJob = null
        transcriptJobs.toList().forEach { it.cancel() }
        transcriptJobs.clear()
        room?.let(::dispose)
        synchronized(stateLock) { microphone = false; camera = false; remoteAudio = false; videos = JSONArray(); localVideoTrackSid = ""; renderTracks = emptyMap(); videoRevision++; participants = 0 }
    }

    private fun dispose(owned: Room) {
        if (disposedRooms.put(owned, true) == true) return
        try { owned.unregisterTextStreamHandler("lk.transcription") } catch (_: Exception) { }
        bindings.toList().forEach { it.close() }
        try { (owned.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack)?.stopCapture() } catch (_: Exception) { cleanupFailed = true }
        try { owned.setMicrophoneMute(true) } catch (_: Exception) { }
        try { owned.setSpeakerMute(true) } catch (_: Exception) { }
        try { owned.disconnect() } catch (_: Exception) { }
        try { owned.release() } catch (_: Exception) { cleanupFailed = true }
        if (room === owned) room = null
    }

    private fun onMain(action: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action) }

    override fun close() {
        closed = true
        generation.incrementAndGet()
        onMain {
            stopCurrent("宿主已关闭实时语音")
            synchronized(stateLock) { state = if (cleanupFailed) "failed" else "disconnected"; message = if (cleanupFailed) "语音资源释放结果未知" else "实时语音已关闭" }
            publish()
            scope.cancel()
            main.removeCallbacksAndMessages(null)
        }
    }
}
