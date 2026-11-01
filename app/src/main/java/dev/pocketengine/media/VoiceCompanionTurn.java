package dev.pocketengine.media;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import dev.pocketengine.core.RunControl;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** One ephemeral, owner-started ASR -> text model -> TTS round. Never a task or continuous recorder. */
final class VoiceCompanionTurn implements AutoCloseable {
    interface Audio {
        CompletableFuture<String> acquireRealtimeAudio(String owner);
        CompletableFuture<String> listenRealtimeTurn(String owner);
        CompletableFuture<Void> speakRealtimeTurn(String owner,String text);
        default CompletableFuture<Void> speakRealtimeTurn(String owner,String text,RunControl authority){
            try{authority.check();return speakRealtimeTurn(owner,text);}
            catch(InterruptedException stopped){CompletableFuture<Void> result=new CompletableFuture<>();result.completeExceptionally(stopped);return result;}
        }
        CompletableFuture<Void> stopRealtimeTurn(String owner);
        void releaseRealtimeAudio(String owner,boolean confirmed);
        boolean ownsRealtimeAudio(String owner);
        boolean audioReleaseUnknown();
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),action->{Thread thread=new Thread(action,"voice-text-turn");thread.setDaemon(true);return thread;});
    private VoiceConversationPort port;
    private VoiceConversationPort.Request request;
    private WeakReference<Activity> owner=new WeakReference<>(null);
    private Audio audio;
    private RunControl control;
    private Future<?> modelWork;
    private Runnable deadline;
    private String id="",status="idle",recognizedText="",replyText="",errorCode="",message="每次由你开始一轮，结束后不会自动再听";
    private String terminalStatus="complete",terminalCode="",terminalMessage="";
    private boolean active,leased,finishing,cleanupStarted,closed,releaseUnknown;
    private JSONObject inferenceReference;
    VoiceCompanionTurn(Runnable changed){this.changed=changed;}
    synchronized void install(VoiceConversationPort port){if(active||closed)throw new IllegalStateException("请先结束本轮语音");this.port=java.util.Objects.requireNonNull(port);signal();}
    synchronized boolean active(){return active;}
    synchronized boolean leaseHeld(){return leased||releaseUnknown;}
    synchronized JSONObject snapshot(){
        JSONObject value=new JSONObject();try{value.put("inferenceReference",inferenceReference==null?JSONObject.NULL:new JSONObject(inferenceReference.toString())).put("id",id).put("status",status).put("active",active).put("recognizedText",recognizedText).put("replyText",replyText).put("message",message).put("errorCode",errorCode).put("portReady",port!=null).put("resourceReleaseUnknown",releaseUnknown).put("alwaysListening",false).put("fullDuplex",false).put("automaticallyExecutesTasks",false);}catch(Exception ignored){}return value;
    }
    synchronized void start(Activity activity,Audio audio)throws Exception{
        if(closed||active||releaseUnknown)throw new IllegalStateException("本轮语音仍在处理或音频释放未确认");
        if(port==null)throw new IllegalStateException("纯对话模型入口尚未接入");
        if(activity==null||activity.isDestroyed()||activity.isFinishing()||audio==null)throw new SecurityException("请在当前原生界面开始语音");
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("请先授予麦克风权限，再明确开始一轮");
        // This local-only preparation must finish before any microphone acquisition.
        VoiceConversationPort.Request prepared=java.util.Objects.requireNonNull(port.prepare());
        inferenceReference=null;this.id=UUID.randomUUID().toString();this.owner=new WeakReference<>(activity);this.audio=audio;this.request=prepared;this.control=new RunControl();
        active=true;leased=false;finishing=false;cleanupStarted=false;recognizedText="";replyText="";errorCode="";status="acquiring";message="正在取得本轮系统语音资源";
        final String round=id;final Audio source=audio;
        control.setAuthorityCheck(()->checkCurrent(round));signal();
        audio.acquireRealtimeAudio(round).whenComplete((lease,failure)->main.post(()->acquired(round,source,failure)));
    }
    private synchronized void acquired(String round,Audio source,Throwable failure){
        if(!id.equals(round)){if(failure==null)source.stopRealtimeTurn(round).whenComplete((v,e)->source.releaseRealtimeAudio(round,e==null));return;}
        if(failure!=null){releaseUnknown=source.audioReleaseUnknown();if(!finishing)terminal("failed",releaseUnknown?"audio_release_unknown":"audio_busy",releaseUnknown?"系统音频资源释放未确认，请检查采集指示并重启应用":"音频资源不可用，本轮没有开始识别");finishWithoutLease();return;}
        leased=true;
        if(finishing||!authorized(round)){if(!finishing)terminal("cancelled","owner_changed","界面或权限已变化，本轮已停止");cleanup(round);return;}
        status="listening";message="请说一句；最终识别文字将发送到开始时选定的模型";signal();arm(round,30_000,"recognition_timeout");
        source.listenRealtimeTurn(round).whenComplete((text,error)->main.post(()->recognized(round,text,error)));
    }
    private synchronized void recognized(String round,String text,Throwable failure){
        if(!isCurrent(round)||finishing)return;
        clearDeadline();
        if(!authorized(round)){stop("cancelled","owner_changed","界面、权限或音频归属已变化，本轮已停止");return;}
        if(failure!=null){stop("failed",speechCode(failure,"recognition_failed"),"系统识别未完成；没有向模型发送文字，也不会自动再听");return;}
        if(text==null||text.isBlank()||text.length()>4000){stop("failed","recognition_invalid","最终识别文字为空或超过4000字，本轮没有发送模型");return;}
        recognizedText=text;status="thinking";message="正在等待这一轮纯文字回复；不会调用工具或执行任务";signal();arm(round,90_000,"model_timeout");
        final VoiceConversationPort.Request route=request;final RunControl call=control;
        try{modelWork=worker.submit(()->{
            try{call.check();String reply=route.complete(text,call);recordUsage(round,route);call.check();main.post(()->answered(round,reply,null));}
            catch(Exception error){recordUsage(round,route);main.post(()->answered(round,null,error));}
        });}catch(java.util.concurrent.RejectedExecutionException busy){stop("failed","model_busy","上一次模型请求仍在停止，本轮没有重试");}
    }
    private void recordUsage(String round,VoiceConversationPort.Request route){try{JSONObject ref=route.usageReference();synchronized(this){if(id.equals(round)&&ref!=null){inferenceReference=new JSONObject(ref.toString());main.post(()->{synchronized(VoiceCompanionTurn.this){if(id.equals(round))signal();}});}}}catch(Exception unavailable){/* Missing locator is not zero usage; the original receipt remains authoritative. */}}
    private synchronized void answered(String round,String text,Throwable failure){
        if(!isCurrent(round)||finishing)return;
        modelWork=null;clearDeadline();
        if(!authorized(round)){stop("cancelled","owner_changed","界面、权限或音频归属已变化，未开始朗读");return;}
        if(failure!=null){stop("failed","model_unconfirmed","本轮模型回复未确认，没有自动重试；远端处理可能已经发生");return;}
        if(text==null||text.isBlank()||text.length()>Math.min(4000,android.speech.tts.TextToSpeech.getMaxSpeechInputLength())){stop("failed","reply_invalid","模型没有返回可用的短文字回复，未开始朗读");return;}
        replyText=text;
        status="speaking";message="正在朗读这一轮回复；可以随时停止，结束后不会再录音";signal();arm(round,120_000,"speech_timeout");
        audio.speakRealtimeTurn(round,text,control).whenComplete((value,error)->main.post(()->spoken(round,error)));
    }
    private synchronized void spoken(String round,Throwable failure){
        if(!isCurrent(round)||finishing)return;
        if(!authorized(round)){stop("cancelled","owner_changed","界面、权限或音频归属已变化，本轮已停止");return;}
        stop(failure==null?"complete":"failed",failure==null?"":speechCode(failure,"speech_failed"),failure==null?"这一轮已结束；麦克风已停止，不会自动再听":"文字回复已保留，但系统朗读未完成；不会自动恢复或再听");
    }
    synchronized void cancel(String expectedId){if(expectedId==null||!id.equals(expectedId))throw new IllegalStateException("语音轮次已变化，请操作当前一轮");if(!active)return;stop("cancelled","owner_stopped","已停止本轮；不会自动重听。已经发出的模型请求可能仍被远端处理");}
    synchronized void cancelForLifecycle(String reason){if(active)stop("cancelled",reason,"界面或采集权限已变化，本轮已停止；不会自动重听");}
    private void stop(String state,String code,String description){if(finishing)return;terminal(state,code,description);if(leased)cleanup(id);}
    private void terminal(String state,String code,String description){finishing=true;terminalStatus=state;terminalCode=code;terminalMessage=description;status="stopping";message="正在停止本轮并释放系统语音资源";clearDeadline();if(control!=null)control.cancel();if(modelWork!=null){modelWork.cancel(true);modelWork=null;}signal();}
    private void cleanup(String round){
        if(cleanupStarted)return;cleanupStarted=true;final Audio source=audio;
        try{source.stopRealtimeTurn(round).whenComplete((value,error)->main.post(()->finishCleanup(round,source,error==null)));}
        catch(RuntimeException unavailable){finishCleanup(round,source,false);}
    }
    private void finishCleanup(String round,Audio source,boolean confirmed){
        try{confirmed&=!source.audioReleaseUnknown();}catch(RuntimeException unavailable){confirmed=false;}
        try{source.releaseRealtimeAudio(round,confirmed);confirmed&=!source.audioReleaseUnknown();}catch(RuntimeException unavailable){confirmed=false;}
        synchronized(this){if(!id.equals(round))return;releaseUnknown|=!confirmed;leased=releaseUnknown;active=false;status=releaseUnknown?"failed":terminalStatus;errorCode=releaseUnknown?"audio_release_unknown":terminalCode;message=releaseUnknown?"系统语音资源释放未确认，请检查系统指示并重启应用":terminalMessage;request=null;audio=null;control=null;owner.clear();signal();}
    }
    private void finishWithoutLease(){active=false;status=terminalStatus;errorCode=terminalCode;message=terminalMessage;request=null;audio=null;control=null;owner.clear();signal();}
    private synchronized void checkCurrent(String round)throws InterruptedException{if(!authorized(round))throw new InterruptedException("Voice round stopped");}
    private boolean isCurrent(String round){return active&&id.equals(round);}
    private boolean authorized(String round){
        Activity activity=owner.get();if(!isCurrent(round)||closed||finishing||activity==null||activity.isDestroyed()||activity.isFinishing()||activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED||audio==null||!audio.ownsRealtimeAudio(round)||request==null)return false;
        try{request.checkCurrent();return true;}catch(Exception revoked){return false;}
    }
    private void arm(String round,long millis,String code){clearDeadline();deadline=()->{synchronized(VoiceCompanionTurn.this){if(isCurrent(round)&&!finishing)stop("failed",code,"本轮等待超时，已停止；没有自动重试或重新开启麦克风");}};main.postDelayed(deadline,millis);}
    private void clearDeadline(){if(deadline!=null)main.removeCallbacks(deadline);deadline=null;}
    private void signal(){main.post(changed);}
    private static String speechCode(Throwable error,String fallback){for(int i=0;i<4&&error!=null;i++,error=error.getCause())if(error instanceof MediaController.SpeechFailure)return ((MediaController.SpeechFailure)error).code;return fallback;}
    @Override public synchronized void close(){if(active)stop("cancelled","host_closed","本轮已停止");closed=true;clearDeadline();worker.shutdownNow();}
}
