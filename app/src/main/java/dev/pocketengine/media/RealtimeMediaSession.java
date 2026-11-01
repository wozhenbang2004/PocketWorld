package dev.pocketengine.media;

import android.content.Context;
import android.view.View;
import dev.pocketengine.core.EventSink;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;
import dev.pocketengine.media.voice.RealtimeVoiceProfile;
import dev.pocketengine.media.voice.VoiceAudioListener;

/** Media transport, not an AI agent or task executor. A subscribed track is not proof of a human caller. */
public interface RealtimeMediaSession extends AutoCloseable {
    interface Factory {RealtimeMediaSession create(Context context,EventSink events)throws Exception;}
    interface VoiceFactory {RealtimeMediaSession create(Context context,EventSink events,RealtimeVoiceProfile profile)throws Exception;}
    interface VideoBinding extends AutoCloseable {View view(); @Override void close();}
    interface AudioBinding extends AutoCloseable {@Override void close();}
    JSONObject snapshot();
    CompletableFuture<JSONObject> connect(String serverUrl,String participantToken,boolean microphone);
    default CompletableFuture<JSONObject> connect(String serverUrl,String participantToken,boolean microphone,boolean camera){
        if(camera)return CompletableFuture.failedFuture(new UnsupportedOperationException("此传输不支持摄像头"));
        return connect(serverUrl,participantToken,microphone);
    }
    CompletableFuture<JSONObject> setMicrophoneEnabled(boolean enabled);
    /** Output interruption never cancels phone/remote tasks. Unsupported transports must say so. */
    default CompletableFuture<JSONObject> interruptOutput(){return CompletableFuture.failedFuture(new UnsupportedOperationException("此传输未提供可确认的语音打断"));}
    /** Native PCM presentation only; this is neither a recording export nor task authorization. */
    default AudioBinding listenAudio(VoiceAudioListener listener){throw new UnsupportedOperationException("此传输没有可确认的播放时钟");}
    default CompletableFuture<JSONObject> setCameraEnabled(boolean enabled){return CompletableFuture.failedFuture(new UnsupportedOperationException("此传输不支持摄像头"));}
    /** Main-thread only. Binding must be closed when its exact session/track leaves the UI. */
    default VideoBinding bindVideo(Context context,String trackSid){throw new UnsupportedOperationException("此传输不支持视频渲染");}
    void disconnect(String reason);
    @Override void close();
}
