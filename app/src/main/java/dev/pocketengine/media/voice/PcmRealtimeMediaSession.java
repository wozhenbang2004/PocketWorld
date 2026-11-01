package dev.pocketengine.media.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.media.RealtimeMediaSession;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONArray;
import org.json.JSONObject;

/** Foreground real speech-to-speech over the documented Realtime GA WebSocket protocol. */
public final class PcmRealtimeMediaSession implements RealtimeMediaSession {
    private final Object audioLock=new Object();
    private final EventSink events;
    private final RealtimeVoiceProfile profile;
    private final RealtimeInferenceAccounting.Factory accountingFactory;private RealtimeInferenceAccounting accounting;private JSONObject inferenceReference;private boolean accountingUnconfirmed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"pocket-voice-audio");t.setDaemon(true);return t;});
    private final AtomicLong lifecycle=new AtomicLong();
    private final AtomicInteger pendingMessages=new AtomicInteger();
    private final OkHttpClient client;
    private final AndroidVoiceAudio devices;
    private final PcmPlaybackQueue playback;
    private final String audioSessionId=UUID.randomUUID().toString();
    private VoiceAudioListener audioListener;
    private long listenerRevision;
    private OpenAiRealtimeProtocol protocol;
    private WebSocket socket;
    private CompletableFuture<JSONObject> connecting;
    private String state="idle",outputState="idle",message="尚未连接自然语音";
    private boolean closed,microphoneRequested,releaseUnknown,receivedAudio;
    private long connectDeadline,lastProgressAt,lastPlayed,startedAt;
    public PcmRealtimeMediaSession(Context context,EventSink events,RealtimeVoiceProfile profile){this(context,events,profile,null);}
    public PcmRealtimeMediaSession(Context context,EventSink events,RealtimeVoiceProfile profile,RealtimeInferenceAccounting.Factory accountingFactory){
        this.events=Objects.requireNonNull(events);this.profile=Objects.requireNonNull(profile);this.accountingFactory=accountingFactory;
        client=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).pingInterval(15,TimeUnit.SECONDS).build();
        devices=new AndroidVoiceAudio(context.getApplicationContext(),()->disconnect("audio_focus_lost"));
        playback=new PcmPlaybackQueue(audioSessionId,devices::output,this::audioEvent);
        worker.setRemoveOnCancelPolicy(true);
        worker.scheduleWithFixedDelay(this::tick,0,10,TimeUnit.MILLISECONDS);
    }
    @Override public JSONObject snapshot(){synchronized(audioLock){
        try{return new JSONObject().put("state",state).put("microphoneEnabled",devices.microphoneEnabled()).put("cameraEnabled",false)
            .put("remoteAudioSubscribed",receivedAudio).put("remoteParticipants",0).put("remoteVideos",new JSONArray())
            .put("transport","openai_realtime_ga_websocket").put("fullDuplexVoice",true).put("outputInterruptionSupported",true)
            .put("audioSessionId",audioSessionId).put("audioGeneration",playback.generation()).put("playedFramePosition",playback.playedFrames())
            .put("playbackClock",playback.clockAvailable()?"audio_track_head":"unavailable").put("outputState",outputState)
            .put("interruptionPending",protocol!=null&&protocol.interruptionPending()).put("replyTranscript",protocol==null?"":protocol.transcript())
            .put("echoCancellationEnabled",devices.echoCancellationEnabled()).put("audioRouting","system_communication_default").put("routedInputType",devices.routedInput())
            .put("sampleRateHz",24_000).put("channels",1).put("encoding","PCM16LE").put("resourceReleaseUnknown",releaseUnknown||playback.resourceReleaseUnknown())
            .put("inferenceReference",inferenceReference==null?JSONObject.NULL:new JSONObject(inferenceReference.toString())).put("usageAccounting",accountingFactory==null?"not_mounted":accountingUnconfirmed?"unconfirmed":"original_receipts_only").put("cameraSupported",false).put("avatarSupported",false).put("nativePlaybackEventsSupported",true).put("backgroundCall",false).put("automaticReconnect",false)
            .put("naturalTaskDispatch",false).put("inputTranscriptionConfigured",!profile.transcriptionModel.isEmpty()&&protocol!=null&&protocol.configured()).put("message",message);
        }catch(Exception impossible){throw new IllegalStateException("语音状态不可用");}
    }}
    @Override public CompletableFuture<JSONObject> connect(String serverUrl,String token,boolean microphone){return connect(serverUrl,token,microphone,false);}
    @Override public CompletableFuture<JSONObject> connect(String serverUrl,String token,boolean microphone,boolean camera){
        CompletableFuture<JSONObject> result=new CompletableFuture<>();
        synchronized(audioLock){
            if(closed||!state.equals("idle")||camera||!profile.endpointUrl.equals(serverUrl)||token==null||token.isBlank()||token.length()>16384||token.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))){
                result.completeExceptionally(new IllegalStateException("实时语音配置无效或会话已使用"));return result;
            }
            long epoch=lifecycle.incrementAndGet();microphoneRequested=microphone;connecting=result;state="connecting";message="正在连接自然语音服务";
            startedAt=System.nanoTime();connectDeadline=startedAt+20_000_000_000L;
            try{if(accountingFactory!=null){accounting=accountingFactory.open(profile,token,audioSessionId);inferenceReference=accounting.reference();}}
            catch(Exception unavailable){state="failed";accountingUnconfirmed=true;message="用量记录无法建立，未连接语音服务或发送音频";result.completeExceptionally(new IllegalStateException(message));return result;}
            protocol=new OpenAiRealtimeProtocol(profile,playback,new OpenAiRealtimeProtocol.Output(){
                @Override public void responseCreated(JSONObject actual)throws Exception{if(accounting!=null)accounting.created(actual);}
                @Override public void responseDone(JSONObject actual)throws Exception{if(accounting!=null)accounting.done(actual);}
                @Override public void transcriptionCommitted(JSONObject actual)throws Exception{if(accounting!=null)accounting.transcriptionCommitted(actual);}
                @Override public void transcriptionFinished(JSONObject actual)throws Exception{if(accounting!=null)accounting.transcriptionFinished(actual);}
                @Override public void send(JSONObject event)throws Exception{
                    WebSocket current=socket;
                    if(epoch!=lifecycle.get()||current==null||current.queueSize()>96_000||!current.send(event.toString()))throw new IllegalStateException("语音网络发送未确认，未继续积压声音");
                }
                @Override public void ready(){
                    if(epoch!=lifecycle.get()||closed)throw new IllegalStateException("语音连接已取消");
                    devices.acquire();devices.microphone(microphoneRequested);state="connected";outputState=microphoneRequested?"listening":"muted";
                    message="自然语音已连接；原始声音发送到选定服务，可随时插话或停止说话";connectDeadline=0;
                    result.complete(snapshot());publish();
                }
                @Override public void changed(String status){if(status.equals("buffering"))receivedAudio=true;outputState=status;publish();}
                @Override public void transcription(JSONObject event)throws Exception{
                    if(epoch!=lifecycle.get()||closed||!state.equals("connected"))return;
                    event.put("participantIdentity","reported-local-microphone").put("trackSid",audioSessionId).put("reportedLocalMicrophone",true);
                    JSONObject caption=new JSONObject(event.toString());main.post(()->{if(epoch==lifecycle.get()&&!closed)try{events.emit(caption);}catch(RuntimeException ignored){}});
                }
            });
            HttpUrl url=HttpUrl.get("https:"+serverUrl.substring(serverUrl.indexOf(':')+1)).newBuilder().addQueryParameter("model",profile.model).build();
            Request request=new Request.Builder().url(url).header("Authorization","Bearer "+token).header("Cache-Control","no-store").build();
            try{socket=client.newWebSocket(request,new WebSocketListener(){
                @Override public void onOpen(WebSocket connection,Response response){enqueue(epoch,()->{if(socket!=connection)return;protocol.opened();});}
                @Override public void onMessage(WebSocket connection,String text){
                    if(text.length()>96_000||pendingMessages.incrementAndGet()>32){pendingMessages.decrementAndGet();failAsync(epoch,"incoming_audio_overflow");return;}
                    enqueue(epoch,()->{try{if(socket==connection)protocol.receive(text);}finally{pendingMessages.decrementAndGet();}});
                }
                @Override public void onMessage(WebSocket connection,ByteString bytes){failAsync(epoch,"unexpected_binary_audio");}
                @Override public void onFailure(WebSocket connection,Throwable error,Response response){failAsync(epoch,"network_unconfirmed");}
                @Override public void onClosing(WebSocket connection,int code,String reason){failAsync(epoch,"remote_closed");}
                @Override public void onClosed(WebSocket connection,int code,String reason){failAsync(epoch,"remote_closed");}
            });}catch(RuntimeException failure){finish("failed","语音连接未能发起");}
            result.whenComplete((value,error)->{if(result.isCancelled())disconnect("connection_cancelled");});publish();
        }
        return result;
    }
    private interface Work {void run()throws Exception;}
    private void enqueue(long epoch,Work work){
        try{worker.execute(()->{synchronized(audioLock){if(epoch!=lifecycle.get()||closed)return;try{work.run();}catch(Exception error){finish("failed","语音会话未确认，已停止采集和播放；没有自动重连或重播");}}});}
        catch(java.util.concurrent.RejectedExecutionException ignored){/* A closed owner admits no callback. */}
    }
    private void failAsync(long epoch,String code){enqueue(epoch,()->finish("failed","语音服务或网络未确认（"+code+"），本次已停止；没有自动重连",code));}
    private void tick(){synchronized(audioLock){
        if(closed||protocol==null||!state.matches("connecting|connected"))return;
        try{
            long now=System.nanoTime();
            if(connectDeadline!=0&&now>connectDeadline)throw new IllegalStateException("连接确认超时");
            if(now-startedAt>30L*60*1_000_000_000)throw new IllegalStateException("前台语音会话时间预算已用完");
            protocol.checkDeadline(now);
            if(!protocol.configured())return;
            byte[] input=devices.read();if(input!=null)protocol.appendInput(input);
            playback.pump(now);
            long played=playback.playedFrames();
            if(played!=lastPlayed||playback.queuedFrames()==0){lastPlayed=played;lastProgressAt=now;}
            else if(lastProgressAt==0)lastProgressAt=now;
            else if(now-lastProgressAt>5_000_000_000L)throw new IllegalStateException("设备播放进度未确认");
            if(outputState.equals("draining")&&!playback.active()){outputState=devices.microphoneEnabled()?"listening":"muted";publish();}
        }catch(Exception failure){finish("failed","语音采集、播放或打断确认失效；本次已停止，没有自动重试");}
    }}
    @Override public CompletableFuture<JSONObject> setMicrophoneEnabled(boolean enabled){
        CompletableFuture<JSONObject> result=new CompletableFuture<>();long epoch=lifecycle.get();
        enqueue(epoch,()->{try{
            if(!state.equals("connected")||protocol==null)throw new IllegalStateException("自然语音尚未连接");
            devices.microphone(enabled);microphoneRequested=enabled;
            if(!enabled)protocol.clearInput();outputState=enabled?"listening":"muted";result.complete(snapshot());publish();
        }catch(Exception failure){finish("failed","麦克风状态未确认，语音会话已停止");result.completeExceptionally(new IllegalStateException("麦克风设置未确认"));}});
        settleIfStopped(result,epoch);return result;
    }
    @Override public CompletableFuture<JSONObject> interruptOutput(){synchronized(audioLock){
        try{
            if(!state.equals("connected")||protocol==null)throw new IllegalStateException("自然语音尚未连接");
            // AudioTrack pause/flush happens synchronously on the independent urgent owner path.
            protocol.interrupt(false);message="本机已停止说话；已有手机或电脑任务继续运行";publish();return CompletableFuture.completedFuture(snapshot());
        }catch(Exception failure){finish("failed","停止说话或远端上下文未确认，本次语音会话已结束");return CompletableFuture.failedFuture(new IllegalStateException("语音打断未确认，通话已停止"));}
    }}
    private void settleIfStopped(CompletableFuture<JSONObject> future,long epoch){
        future.orTimeout(8,TimeUnit.SECONDS).whenComplete((value,error)->{if(error!=null&&epoch==lifecycle.get())disconnect("capture_unconfirmed");});
    }
    @Override public AudioBinding listenAudio(VoiceAudioListener listener){synchronized(audioLock){
        if(closed)throw new IllegalStateException("语音会话已关闭");audioListener=Objects.requireNonNull(listener);long revision=++listenerRevision;playback.announceCurrent();
        return ()->{synchronized(audioLock){if(revision==listenerRevision){audioListener=null;listenerRevision++;}}};
    }}
    private void audioEvent(VoiceAudioEvent event){
        VoiceAudioListener listener=audioListener;
        if(listener!=null)try{listener.onAudioEvent(event);}catch(RuntimeException ignored){/* Presentation has no audio lease authority. */}
    }
    @Override public void disconnect(String reason){synchronized(audioLock){if(closed)return;finish("disconnected",reason.equals("owner_backgrounded")?"已离开应用，麦克风、播放和语音连接已关闭":reason.equals("audio_focus_lost")?"音频焦点已移交，语音连接已关闭":"语音已挂断；已有任务继续运行",reason);}}
    private void finish(String terminal,String text){finish(terminal,text,terminal.equals("failed")?"native_session_unconfirmed":"host_closed");}
    private void finish(String terminal,String text,String reason){
        lifecycle.incrementAndGet();if(protocol!=null)protocol.close();
        if(accounting!=null)try{accounting.close(reason);}catch(Exception storage){accountingUnconfirmed=true;}
        if(socket!=null){socket.cancel();socket=null;}
        try{playback.close();}catch(RuntimeException unconfirmed){releaseUnknown=true;}
        finally{releaseUnknown|=playback.resourceReleaseUnknown();}
        try{devices.close();}catch(RuntimeException unconfirmed){releaseUnknown=true;}
        state=releaseUnknown?"failed":terminal;outputState="idle";receivedAudio=false;message=text;
        if(connecting!=null&&!connecting.isDone())connecting.completeExceptionally(new IllegalStateException("自然语音连接已结束"));
        worker.shutdown();
        publish();
    }
    private void publish(){
        JSONObject value=snapshot();long epoch=lifecycle.get();
        main.post(()->{if(epoch==lifecycle.get())try{events.emit(value);}catch(RuntimeException ignored){/* Host persistence is not media ownership. */}});
    }
    @Override public void close(){synchronized(audioLock){if(closed)return;finish("disconnected","自然语音已关闭");closed=true;audioListener=null;listenerRevision++;worker.shutdownNow();client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}}
}
