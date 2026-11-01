package dev.pocketengine.media.voice.tasks;

import dev.pocketengine.core.RunControl;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** Original-record readout through existing owned system TTS; no microphone/model/task execution. */
public final class VoiceTaskReadout implements AutoCloseable {
    public interface Audio {
        CompletableFuture<String> acquire(String owner);
        CompletableFuture<Void> speak(String owner,String text,RunControl authority);
        CompletableFuture<Void> stop(String owner);
        void release(String owner,boolean confirmed);
        boolean releaseUnknown();
    }
    private static final class Reading {
        final String id=UUID.randomUUID().toString();final RunControl authority=new RunControl();final Audio audio;
        boolean leased,cleaning,cancelled,done,successful;
        Reading(Audio audio){this.audio=audio;}
    }
    private final Object gate=new Object();
    private final Runnable changed;
    private Reading current;
    private String phase="idle",message="";
    private boolean unknown,closed;
    public VoiceTaskReadout(Runnable changed){this.changed=changed;}
    public JSONObject snapshot()throws Exception{synchronized(gate){return new JSONObject().put("readoutId",current==null?"":current.id).put("state",phase).put("message",message).put("active",active()).put("leaseHeld",leaseHeld()).put("resourceReleaseUnknown",unknown).put("playbackClockAvailable",false).put("source","owned_system_tts");}}
    public boolean active(){synchronized(gate){return current!=null&&!current.done;}}
    public boolean leaseHeld(){synchronized(gate){return current!=null&&current.leased;}}
    public void start(String text,VoiceTaskHostPort.Authority liveAuthority,Audio audio)throws Exception{
        liveAuthority.check();if(text==null||text.isBlank()||text.length()>3000)throw new IllegalArgumentException("原任务结果为空或过长，请打开原任务查看");
        Reading reading=new Reading(java.util.Objects.requireNonNull(audio));reading.authority.setAuthorityCheck(liveAuthority::check);
        synchronized(gate){if(closed||active()||unknown)throw new IllegalStateException("朗读仍在收尾或音频释放未确认");current=reading;phase="acquiring";message="正在准备朗读原任务记录";}
        signal();
        try{reading.authority.check();}catch(Exception revoked){finishWithoutLease(reading);return;}
        try{audio.acquire(reading.id).whenComplete((owner,error)->{
            if(error!=null){finishWithoutLease(reading);return;}
            synchronized(gate){reading.leased=true;}
            try{
                if(!reading.id.equals(owner))throw new IllegalStateException("音频归属回执无效");reading.authority.check();
                synchronized(gate){if(reading.cancelled||closed)throw new IllegalStateException("朗读已停止");phase="speaking";message="正在朗读原任务记录";}
                signal();audio.speak(reading.id,text,reading.authority).whenComplete((value,failure)->{
                    boolean success=failure==null;try{reading.authority.check();}catch(Exception stopped){success=false;}
                    cleanup(reading,success);
                });
            }catch(Exception failure){cleanup(reading,false);}
        });}catch(RuntimeException failure){finishWithoutLease(reading);}
    }
    /** Identity checked: an old stop button cannot stop a replacement readout or any task. */
    public void stop(String expectedId){
        Reading reading;synchronized(gate){reading=current;if(reading==null||!reading.id.equals(expectedId))throw new IllegalStateException("朗读已变化");if(reading.done)return;reading.cancelled=true;phase="stopping";message="正在停止朗读，原任务继续保留";}
        reading.authority.cancel();if(reading.leased)cleanup(reading,false);signal();
    }
    public void invalidate(){Reading reading;synchronized(gate){reading=current;}if(reading!=null&&!reading.done)stop(reading.id);}
    private void cleanup(Reading reading,boolean success){
        synchronized(gate){if(reading.cleaning||reading.done)return;reading.cleaning=true;reading.successful=success&&!reading.cancelled;phase="stopping";}
        try{reading.audio.stop(reading.id).whenComplete((value,error)->{
            boolean confirmed=error==null;try{reading.audio.release(reading.id,confirmed);confirmed&=!reading.audio.releaseUnknown();}catch(RuntimeException failure){confirmed=false;}
            synchronized(gate){unknown|=!confirmed;reading.leased=!confirmed;reading.done=true;
                if(current==reading){phase=unknown?"failed":reading.successful?"ended":"stopped";message=unknown?"系统音频释放未确认，已阻止再次朗读":reading.successful?"原任务记录朗读结束":"朗读已停止，原任务没有被取消";}}
            signal();
        });}catch(RuntimeException failure){synchronized(gate){unknown=true;reading.done=true;phase="failed";message="系统音频释放未确认，已阻止再次朗读";}signal();}
    }
    private void finishWithoutLease(Reading reading){boolean unconfirmed=reading.audio.releaseUnknown();synchronized(gate){if(reading.done)return;unknown|=unconfirmed;reading.leased=unconfirmed;reading.done=true;if(current==reading){phase="failed";message=unknown?"系统音频释放未确认，已阻止再次朗读":"系统朗读暂不可用，请查看原任务结果";}}signal();}
    private void signal(){try{changed.run();}catch(RuntimeException ignored){}}
    @Override public void close(){synchronized(gate){closed=true;}invalidate();}
}
