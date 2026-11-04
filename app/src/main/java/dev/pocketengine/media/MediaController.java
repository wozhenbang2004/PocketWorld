package dev.pocketengine.media;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.RunControl;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** User-initiated system speech input/output. No always-on microphone or automatic phone task. */
public final class MediaController implements AutoCloseable,VoiceCompanionTurn.Audio {
    public static final int AUDIO_PERMISSION_REQUEST=542;
    // One process/main-thread owner shared by native and compatibility Activities. Never a second recorder.
    private static final Set<MediaController> controllers=Collections.newSetFromMap(new WeakHashMap<>());
    private static volatile String realtimeAudioOwner;
    private static volatile boolean audioReleaseUnknown;
    private boolean executionAllowed(){return !(activity.getApplication() instanceof dev.pocketengine.EngineApplication app)||app.executionAllowed();}
    /** Stop the existing main-thread audio owners without clearing an uncertain original lease. */
    public static CompletableFuture<JSONObject> stopForOwnerDisable(dev.pocketengine.EngineApplication app){
        CompletableFuture<JSONObject> result=new CompletableFuture<>();new Handler(Looper.getMainLooper()).post(()->{
            if(app.executionAllowed()){result.completeExceptionally(new SecurityException("总运行状态已恢复，旧停止请求失效"));return;}
            int count=0;boolean callsAccepted=true;
            for(MediaController controller:new ArrayList<>(controllers)){if(controller.activity.getApplication()!=app)continue;count++;boolean input=controller.stopListeningInternal(),output=controller.stopSpeakingInternal();callsAccepted&=input&&output;}
            result.complete(json("controllers",count,"stopCallsAccepted",callsAccepted,"audioReleaseUnknown",audioReleaseUnknown,"leaseStillHeld",realtimeAudioOwner!=null,"physicalCleanupVerified",false));
        });return result;
    }
    private final Activity activity;
    private final EventSink events;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SpeechAudioFocus audioFocus;
    private SpeechRecognizer recognition;
    private TextToSpeech speech;
    private boolean listening,speaking,ttsReady,awaitingPermission,speechRequiresMicrophone=true;
    private volatile boolean closed;
    private String utterance,pendingSpeech,pendingSpeechOwner,recognitionOwner,speechOwner;
    private CompletableFuture<String> ownedRecognition;
    private CompletableFuture<Void> ownedSpeech;
    private RunControl speechAuthority;
    private long recognitionEpoch;
    private long speechEpoch;
    private String ttsStatus="not_initialized";
    static final class SpeechFailure extends Exception {final String code;SpeechFailure(String code){super("系统语音未确认（"+code+"）");this.code=code;}}
    public MediaController(Activity activity,EventSink events){this.activity=activity;this.events=events;audioFocus=new SpeechAudioFocus(activity,()->{stopSpeaking();error("其他音频取得播放控制，朗读已停止");});onMain(()->controllers.add(this));}
    public JSONObject state(){JSONObject result=json("type","media_state","listening",listening,"speaking",speaking,"ttsReady",ttsReady,"audioFocusHeld",audioFocus.isHeld(),"realtimeOwnsAudio",realtimeAudioOwner!=null,"audioReleaseUnknown",audioReleaseUnknown);try{result.put("recognitionAvailable",!closed&&SpeechRecognizer.isRecognitionAvailable(activity)).put("systemSpeech",systemSpeechInfo());}catch(Exception unavailable){put(result,"recognitionAvailable",false);}return result;}
    /** Capability metadata only. Default system recognition is not evidence of offline or Chinese support. */
    public JSONObject systemSpeechInfo(){
        JSONObject info=json("provider","android_system_default","recognitionNetworkUse","unknown","recognitionLanguageSupport","unknown","requestedLanguage","zh-CN","recognitionEngineIdentity","system_selected_unverified","microphoneGranted",activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED,"ttsStatus",closed?"closed":ttsStatus,"ttsVoiceNetworkUse","unknown","ttsEnginePackage","");
        try{info.put("recognitionAvailable",!closed&&SpeechRecognizer.isRecognitionAvailable(activity));if(speech!=null){String engine=speech.getDefaultEngine();info.put("ttsEnginePackage",engine==null?"":engine);android.speech.tts.Voice selected=speech.getVoice();if(ttsReady&&selected!=null)info.put("ttsVoiceName",selected.getName()).put("ttsVoiceNetworkUse",selected.isNetworkConnectionRequired()?"requires_network":"declares_no_network_requirement");}}catch(RuntimeException unavailable){/* Unknown capability stays unknown; no replacement engine is selected. */}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}return info;
    }
    /** Permission request only. A grant does not automatically start the new draft capture. */
    public void requestMicrophoneForDraft(){onMain(()->{if(closed)return;awaitingPermission=false;if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO_PERMISSION_REQUEST);emitState();});}
    public void startListening(){onMain(()->{
        if(closed)return;
        if(realtimeAudioOwner!=null){error(audioReleaseUnknown?"音频资源释放结果未知，请重启应用后重试":"通话或语音轮正在使用音频，请先停止");return;}
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){awaitingPermission=true;activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO_PERMISSION_REQUEST);return;}
        beginListening(null,null);
    });}
    /** Dedicated final result for the exact native voice round. Never emits a generic speech_result. */
    @Override public CompletableFuture<String> listenRealtimeTurn(String owner){
        CompletableFuture<String> result=new CompletableFuture<>();onMain(()->{
            if(!ownsRealtimeAudio(owner)||activity.isDestroyed()||activity.isFinishing()){result.completeExceptionally(new SpeechFailure("audio_owner_changed"));return;}
            if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){result.completeExceptionally(new SpeechFailure("microphone_denied"));return;}
            beginListening(owner,result);
        });return result;
    }
    private void beginListening(String owner,CompletableFuture<String> result){
        if(!executionAllowed()){speechFailure(result,"owner_disabled","PocketWorld 已停用；没有打开麦克风。");return;}
        if(closed||(owner==null?realtimeAudioOwner!=null:!ownsRealtimeAudio(owner))){speechFailure(result,"audio_owner_changed","音频归属已变化，未开始识别。");return;}
        try{if(!SpeechRecognizer.isRecognitionAvailable(activity)){speechFailure(result,"recognition_unavailable","这台手机没有可用的系统语音识别服务。可继续用文字输入。");return;}}catch(RuntimeException unavailable){speechFailure(result,"recognition_unavailable","系统语音服务暂时不可用，可继续用文字输入。");return;}
        if(listening){speechFailure(result,"recognition_busy","系统语音服务正忙，请稍后重试。");return;}
        boolean outputStopped=stopSpeakingInternal(),inputStopped=stopListeningInternal();
        if(!outputStopped||!inputStopped){speechFailure(result,"audio_release_unknown","系统语音资源释放未确认，请重启应用后重试。");return;}
        try{
            long epoch=++recognitionEpoch;recognitionOwner=owner;ownedRecognition=result;recognition=SpeechRecognizer.createSpeechRecognizer(activity);recognition.setRecognitionListener(new Listener(epoch,owner,result));
            Intent request=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
            listening=true;recognition.startListening(request);emitState();
        }catch(RuntimeException unavailable){stopListeningInternal();speechFailure(result,"recognition_unavailable","语音识别暂时不可用，请检查系统语音服务。");emitState();}
    }
    public void stopListening(){onMain(this::stopListeningInternal);}
    private boolean stopListeningInternal(){
        awaitingPermission=false;recognitionEpoch++;CompletableFuture<String> pending=ownedRecognition;ownedRecognition=null;String owner=recognitionOwner;recognitionOwner=null;SpeechRecognizer current=recognition;recognition=null;listening=false;boolean confirmed=true;
        if(current!=null){try{current.cancel();}catch(RuntimeException failure){confirmed=false;}try{current.destroy();}catch(RuntimeException failure){confirmed=false;}}
        if(!confirmed&&owner!=null)audioReleaseUnknown=true;if(pending!=null)pending.completeExceptionally(new SpeechFailure(confirmed?"cancelled":"audio_release_unknown"));emitState();return confirmed;
    }
    public void speak(String text){onMain(()->{
        if(closed)return;
        if(realtimeAudioOwner!=null){error(audioReleaseUnknown?"音频资源释放结果未知，请重启应用后重试":"通话或语音轮正在使用音频，请先停止");return;}
        beginSpeech(null,text,null,null);
    });}
    @Override public CompletableFuture<Void> speakRealtimeTurn(String owner,String text){
        return speakRealtimeTurn(owner,text,null);
    }
    /** Retain the exact native round authority through asynchronous system TTS initialization. */
    @Override public CompletableFuture<Void> speakRealtimeTurn(String owner,String text,RunControl authority){
        CompletableFuture<Void> result=new CompletableFuture<>();onMain(()->{
            if(!ownsRealtimeAudio(owner)||activity.isDestroyed()||activity.isFinishing()){result.completeExceptionally(new SpeechFailure("audio_owner_changed"));return;}
            beginSpeech(owner,text,result,authority);
        });return result;
    }
    /** Native original-task readout only; output ownership and live authority do not grant capture. */
    public CompletableFuture<Void> speakOwnedTaskResult(String owner,String text,RunControl authority){
        CompletableFuture<Void> result=new CompletableFuture<>();onMain(()->{
            if(authority==null||!ownsRealtimeAudio(owner)||activity.isDestroyed()||activity.isFinishing()){result.completeExceptionally(new SpeechFailure("audio_owner_changed"));return;}
            beginSpeech(owner,text,result,authority,false);
        });return result;
    }
    private void beginSpeech(String owner,String text,CompletableFuture<Void> result,RunControl authority){
        beginSpeech(owner,text,result,authority,true);
    }
    private void beginSpeech(String owner,String text,CompletableFuture<Void> result,RunControl authority,boolean requireMicrophone){
        if(!executionAllowed()){speechFailure(result,"owner_disabled","PocketWorld 已停用；没有开始朗读。");return;}
        if(closed||(owner==null?realtimeAudioOwner!=null:!ownsRealtimeAudio(owner))){speechFailure(result,"audio_owner_changed","音频归属已变化，未开始朗读。");return;}
        if(!speechAuthorityCurrent(authority)){speechFailure(result,"voice_authority_changed","");return;}
        if(owner!=null&&requireMicrophone&&activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){speechFailure(result,"microphone_denied","");return;}
        if(text==null||text.trim().isEmpty()){speechFailure(result,"speech_empty","没有可以朗读的内容。");return;}
        if(text.length()>TextToSpeech.getMaxSpeechInputLength()){speechFailure(result,"speech_too_long","这段内容超过系统朗读长度，请选择较短的一段。");return;}
        boolean inputStopped=stopListeningInternal(),outputStopped=stopSpeakingInternal();
        if(!inputStopped||!outputStopped){speechFailure(result,"audio_release_unknown","系统语音资源释放未确认，请重启应用后重试。");return;}
        speechOwner=owner;ownedSpeech=result;speechAuthority=authority;speechRequiresMicrophone=requireMicrophone;
        if(speech==null){pendingSpeech=text;pendingSpeechOwner=owner;initializeSpeech();emitState();return;}
        if(!ttsReady){failPendingSpeech("speech_unavailable");if(owner==null)error("系统朗读服务尚未就绪，可能需要安装中文语音数据。");return;}
        if(!audioFocus.acquire()){failPendingSpeech("audio_focus_denied");if(owner==null)error("暂时无法取得音频播放控制，请稍后朗读");return;}
        if(!speechAuthorityCurrent(authority)){audioFocus.release();failPendingSpeech("voice_authority_changed");emitState();return;}
        utterance=UUID.randomUUID().toString();
        if(speech.speak(text,TextToSpeech.QUEUE_FLUSH,new Bundle(),utterance)==TextToSpeech.ERROR){speaking=false;utterance=null;audioFocus.release();failPendingSpeech("speech_failed");if(owner==null)error("系统未能开始朗读。");}
        else speaking=true;
        emitState();
    }
    public void stopSpeaking(){onMain(this::stopSpeakingInternal);}
    private boolean stopSpeakingInternal(){
        boolean hadOutput=speaking||utterance!=null;pendingSpeech=null;pendingSpeechOwner=null;utterance=null;CompletableFuture<Void> pending=ownedSpeech;ownedSpeech=null;speechAuthority=null;String owner=speechOwner;speechOwner=null;boolean confirmed=true;
        try{if(speech!=null&&speech.stop()==TextToSpeech.ERROR&&owner!=null&&hadOutput)confirmed=false;}catch(RuntimeException failure){confirmed=false;}
        speaking=false;audioFocus.release();if(!confirmed&&owner!=null)audioReleaseUnknown=true;if(pending!=null)pending.completeExceptionally(new SpeechFailure(confirmed?"cancelled":"audio_release_unknown"));emitState();return confirmed;
    }
    /** Obtain the existing media owner's exclusive call lease before any SDK capture/output starts. */
    @Override public CompletableFuture<String> acquireRealtimeAudio(String owner){
        CompletableFuture<String> result=new CompletableFuture<>();
        onMain(()->{
            if(!executionAllowed()){result.completeExceptionally(new SpeechFailure("owner_disabled"));return;}
            if(closed||owner==null||owner.isEmpty()||audioReleaseUnknown||(realtimeAudioOwner!=null&&!realtimeAudioOwner.equals(owner))){result.completeExceptionally(new IllegalStateException("音频资源正被占用或释放未确认"));return;}
            realtimeAudioOwner=owner;
            try{
                boolean confirmed=true;for(MediaController controller:new ArrayList<>(controllers)){boolean input=controller.stopListeningInternal(),output=controller.stopSpeakingInternal();confirmed&=input&&output;}
                if(!confirmed||audioReleaseUnknown)throw new IllegalStateException("System audio cleanup unconfirmed");
                emitState();result.complete(owner);
            }catch(RuntimeException failure){audioReleaseUnknown=true;result.completeExceptionally(new IllegalStateException("系统语音资源释放未确认，未开始通话"));}
        });return result;
    }
    /** Optional status speech must never interrupt a manual recording, readout, call or pending permission. */
    private CompletableFuture<String> acquireTaskAnnouncementAudio(String owner){
        CompletableFuture<String> result=new CompletableFuture<>();onMain(()->{
            if(!taskAnnouncementsAllowed()||realtimeAudioOwner!=null||audioReleaseUnknown){result.completeExceptionally(new IllegalStateException("当前不播放任务状态提示"));return;}
            for(MediaController controller:new ArrayList<>(controllers))if(!controller.closed&&(controller.listening||controller.speaking||controller.recognition!=null||controller.awaitingPermission||controller.pendingSpeech!=null||controller.ownedSpeech!=null||controller.ownedRecognition!=null)){
                result.completeExceptionally(new IllegalStateException("当前音频正在使用，未打断原输入或播放"));return;
            }
            acquireRealtimeAudio(owner).whenComplete((value,failure)->{if(failure==null)result.complete(value);else result.completeExceptionally(failure);});
        });return result;
    }
    /** Playback eligibility only; a system speech engine may still require network for its selected voice. */
    public boolean taskAnnouncementsAllowed(){
        try{
            if(!executionAllowed()||closed||activity.isDestroyed()||activity.isFinishing()||!activity.hasWindowFocus())return false;
            android.app.KeyguardManager lock=activity.getSystemService(android.app.KeyguardManager.class);
            android.view.accessibility.AccessibilityManager accessibility=activity.getSystemService(android.view.accessibility.AccessibilityManager.class);
            return lock!=null&&!lock.isDeviceLocked()&&!lock.isKeyguardLocked()&&accessibility!=null&&!accessibility.isTouchExplorationEnabled()
                    &&accessibility.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
        }catch(RuntimeException unavailable){return false;}
    }
    public dev.pocketengine.media.voice.tasks.VoiceTaskReadout.Audio taskAnnouncementAudio(){
        return new dev.pocketengine.media.voice.tasks.VoiceTaskReadout.Audio(){
            public CompletableFuture<String> acquire(String owner){return acquireTaskAnnouncementAudio(owner);}
            public CompletableFuture<Void> speak(String owner,String text,RunControl authority){return speakOwnedTaskResult(owner,text,authority);}
            public CompletableFuture<Void> stop(String owner){return stopRealtimeTurn(owner);}
            public void release(String owner,boolean confirmed){releaseRealtimeAudio(owner,confirmed);}
            public boolean releaseUnknown(){return audioReleaseUnknown();}
        };
    }
    @Override public boolean audioReleaseUnknown(){return audioReleaseUnknown;}
    @Override public boolean ownsRealtimeAudio(String owner){return !closed&&!audioReleaseUnknown&&owner!=null&&owner.equals(realtimeAudioOwner);}
    /** Native round cleanup only. A stale owner cannot stop another round or an RTC call. */
    @Override public CompletableFuture<Void> stopRealtimeTurn(String owner){
        CompletableFuture<Void> result=new CompletableFuture<>();onMain(()->{
            if(owner==null||!owner.equals(realtimeAudioOwner)){result.completeExceptionally(new SpeechFailure("audio_owner_changed"));return;}
            boolean input=stopListeningInternal(),output=stopSpeakingInternal();
            if(input&&output&&!audioReleaseUnknown)result.complete(null);else result.completeExceptionally(new SpeechFailure("audio_release_unknown"));
        });return result;
    }
    /** A stale callback must never release a newer call's lease. Unknown cleanup keeps capture blocked. */
    @Override public void releaseRealtimeAudio(String owner,boolean releaseConfirmed){onMain(()->{
        if(owner==null||!owner.equals(realtimeAudioOwner))return;
        if(!releaseConfirmed){audioReleaseUnknown=true;emitState();return;}
        if(audioReleaseUnknown)return;
        realtimeAudioOwner=null;
        for(MediaController controller:new ArrayList<>(controllers))controller.emitState();
    });}
    public void stopAll(){stopListening();stopSpeaking();}
    public void prepareSpeech(){onMain(()->{if(!closed&&speech==null)initializeSpeech();});}
    public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants){if(requestCode!=AUDIO_PERMISSION_REQUEST)return;boolean wanted=awaitingPermission;awaitingPermission=false;emitState();if(!wanted||closed)return;if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startListening();else error("未获得麦克风权限，仍可使用文字输入。");}
    private void initializeSpeech(){
        final long epoch=++speechEpoch;
        ttsStatus="initializing";
        speech=new TextToSpeech(activity,status->main.post(()->{
            if(closed||epoch!=speechEpoch||speech==null)return;
            if(status!=TextToSpeech.SUCCESS){ttsReady=false;ttsStatus="unavailable";failPendingSpeech("speech_unavailable");TextToSpeech failed=speech;speech=null;failed.shutdown();error("系统朗读引擎初始化失败，可稍后重试。");emitState();return;}
            int language=speech.setLanguage(Locale.SIMPLIFIED_CHINESE);
            speech.setAudioAttributes(SpeechAudioFocus.ATTRIBUTES);
            ttsReady=language!=TextToSpeech.LANG_MISSING_DATA&&language!=TextToSpeech.LANG_NOT_SUPPORTED;
            ttsStatus=language==TextToSpeech.LANG_MISSING_DATA?"language_data_missing":language==TextToSpeech.LANG_NOT_SUPPORTED?"language_unsupported":"ready";
            speech.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                @Override public void onStart(String id){main.post(()->{if(speechCurrent(id,epoch)){if(!speechAuthorityCurrent(speechAuthority)){stopSpeakingInternal();return;}speaking=true;emitState();}});}
                @Override public void onDone(String id){ended(id,epoch,false,false);}
                @Override public void onError(String id){ended(id,epoch,true,false);}
                @Override public void onStop(String id,boolean interrupted){ended(id,epoch,false,true);}
            });
            if(!ttsReady){failPendingSpeech("speech_unavailable");error("系统朗读引擎没有可用的中文语音数据。");}emitState();
            if(ttsReady&&pendingSpeech!=null){String queued=pendingSpeech,owner=pendingSpeechOwner;CompletableFuture<Void> result=ownedSpeech;RunControl authority=speechAuthority;boolean requireMicrophone=speechRequiresMicrophone;pendingSpeech=null;pendingSpeechOwner=null;ownedSpeech=null;speechOwner=null;speechAuthority=null;if(owner==null||ownsRealtimeAudio(owner))beginSpeech(owner,queued,result,authority,requireMicrophone);else speechFailure(result,"audio_owner_changed","");}
        }));
    }
    private boolean speechCurrent(String id,long epoch){return !closed&&epoch==speechEpoch&&id!=null&&id.equals(utterance)&&(speechOwner==null?realtimeAudioOwner==null:ownsRealtimeAudio(speechOwner));}
    private void ended(String id,long epoch,boolean failed,boolean stopped){main.post(()->{
        if(!speechCurrent(id,epoch))return;speaking=false;utterance=null;audioFocus.release();CompletableFuture<Void> result=ownedSpeech;ownedSpeech=null;speechOwner=null;speechAuthority=null;
        if(result!=null){if(failed||stopped)result.completeExceptionally(new SpeechFailure(stopped?"cancelled":"speech_failed"));else result.complete(null);}else if(failed)error("系统朗读中断，请重试。");emitState();
    });}
    private void failPendingSpeech(String code){pendingSpeech=null;pendingSpeechOwner=null;CompletableFuture<Void> result=ownedSpeech;ownedSpeech=null;speechOwner=null;speechAuthority=null;if(result!=null)result.completeExceptionally(new SpeechFailure(code));}
    private static boolean speechAuthorityCurrent(RunControl authority){try{if(authority!=null)authority.check();return true;}catch(InterruptedException stopped){return false;}}
    private final class Listener implements RecognitionListener {
        private final long epoch;private final String owner;private final CompletableFuture<String> result;
        Listener(long epoch,String owner,CompletableFuture<String> result){this.epoch=epoch;this.owner=owner;this.result=result;}
        private boolean current(){return !closed&&epoch==recognitionEpoch&&(owner==null?realtimeAudioOwner==null:ownsRealtimeAudio(owner)&&ownedRecognition==result);}
        @Override public void onReadyForSpeech(Bundle params){if(!current())return;listening=true;emitState();}
        @Override public void onBeginningOfSpeech(){}
        @Override public void onRmsChanged(float rmsDb){}
        @Override public void onBufferReceived(byte[] buffer){}
        @Override public void onEndOfSpeech(){if(!current())return;listening=false;emitState();}
        @Override public void onError(int code){if(!current())return;ownedRecognition=null;stopListeningInternal();if(result!=null)result.completeExceptionally(new SpeechFailure("recognition_failed"));else if(!closed)error(recognitionError(code));}
        @Override public void onResults(Bundle results){
            if(!current())return;ArrayList<String> values=results==null?null:results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);String text=values==null||values.isEmpty()?null:values.get(0);
            ownedRecognition=null;recognitionOwner=null;SpeechRecognizer completed=recognition;recognition=null;recognitionEpoch++;listening=false;boolean confirmed=true;
            if(completed!=null)try{completed.destroy();}catch(RuntimeException failure){confirmed=false;if(owner!=null)audioReleaseUnknown=true;}
            emitState();if(result!=null){if(!confirmed)result.completeExceptionally(new SpeechFailure("audio_release_unknown"));else if(text==null||text.isBlank())result.completeExceptionally(new SpeechFailure("recognition_empty"));else result.complete(text);}else if(!closed&&text!=null&&!text.isBlank())events.emit(json("type","speech_result","text",text,"isFinal",true));
        }
        @Override public void onPartialResults(Bundle partial){}
        @Override public void onEvent(int eventType,Bundle params){}
    }
    private void speechFailure(CompletableFuture<?> result,String code,String text){if(result!=null)result.completeExceptionally(new SpeechFailure(code));else error(text);}
    private static String recognitionError(int code){switch(code){case SpeechRecognizer.ERROR_NO_MATCH:case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:return "没有听清，请再试一次。";case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:return "语音识别缺少麦克风权限。";case SpeechRecognizer.ERROR_NETWORK:case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:return "系统语音服务连接失败，请检查网络。";case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:return "系统语音服务正忙，请稍后重试。";default:return "语音识别未完成（"+code+"），可以继续用文字输入。";}}
    private void emitState(){if(!closed)events.emit(state());}
    private void error(String message){if(!closed)events.emit(json("type","media_error","message",message));}
    private void onMain(Runnable action){if(Looper.myLooper()==Looper.getMainLooper())action.run();else main.post(action);}
    @Override public void close(){onMain(()->{stopListeningInternal();stopSpeakingInternal();closed=true;controllers.remove(this);speechEpoch++;/* Queued lease futures must settle; their callbacks already check closed/epochs. */if(speech!=null){speech.shutdown();speech=null;}audioFocus.close();listening=false;speaking=false;ttsReady=false;});}
    private static JSONObject json(Object...pairs){JSONObject result=new JSONObject();for(int i=0;i+1<pairs.length;i+=2)put(result,String.valueOf(pairs[i]),pairs[i+1]);return result;}
    private static void put(JSONObject result,String key,Object value){try{result.put(key,value);}catch(Exception ignored){}}
}
