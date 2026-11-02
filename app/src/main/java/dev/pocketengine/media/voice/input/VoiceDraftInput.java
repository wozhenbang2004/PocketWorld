package dev.pocketengine.media.voice.input;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** One explicit system-ASR capture and one reviewed draft. No submission queue, TaskStore or Runner. */
public final class VoiceDraftInput implements AutoCloseable {
    public interface Authority {void check()throws Exception;}
    public interface Audio {
        CompletableFuture<String> acquire(String owner);
        CompletableFuture<String> listen(String owner);
        CompletableFuture<Void> stop(String owner);
        CompletableFuture<Void> release(String owner,boolean confirmed);
        boolean releaseUnknown();
    }
    public static final class Target {
        public final String conversationId;
        public final long viewEpoch;
        public final boolean phoneInput;
        private final JSONObject originalRun;
        public Target(String conversationId,long viewEpoch,boolean phoneInput,JSONObject originalRun)throws Exception{
            if(conversationId==null||viewEpoch<0)throw new IllegalArgumentException("原输入视图无效");
            this.conversationId=conversationId;this.viewEpoch=viewEpoch;this.phoneInput=phoneInput;this.originalRun=new JSONObject();
            if(originalRun!=null){for(String key:new String[]{"taskId","runId"})if(!originalRun.optString(key).isEmpty())this.originalRun.put(key,originalRun.getString(key));if(originalRun.has("epoch"))this.originalRun.put("epoch",originalRun.getInt("epoch"));}
        }
        boolean sameInput(Target other){return other!=null&&conversationId.equals(other.conversationId)&&phoneInput==other.phoneInput&&(!conversationId.isEmpty()||viewEpoch==other.viewEpoch);}
        JSONObject json()throws Exception{return new JSONObject().put("conversationId",conversationId).put("viewEpoch",viewEpoch).put("phoneInput",phoneInput).put("capturedOriginalRun",new JSONObject(originalRun.toString()));}
    }
    private static final class Capture {
        final String id=UUID.randomUUID().toString();final Target target;final Authority authority;final Audio audio;
        boolean leased,cancelled,cleaning,done;
        Capture(Target target,Authority authority,Audio audio){this.target=target;this.authority=authority;this.audio=audio;}
    }
    private final Object gate=new Object();private final Runnable changed;
    private Capture current;
    private String phase="idle",message="",draft="",staged="",submittedClient="";
    private boolean unknown,closed;
    private final java.util.function.Supplier<String> requestedLanguage;
    public VoiceDraftInput(Runnable changed){this(changed,()->java.util.Locale.getDefault().toLanguageTag());}
    public VoiceDraftInput(Runnable changed,java.util.function.Supplier<String> requestedLanguage){this.changed=java.util.Objects.requireNonNull(changed);this.requestedLanguage=java.util.Objects.requireNonNull(requestedLanguage); }
    public JSONObject snapshot()throws Exception{synchronized(gate){return new JSONObject().put("inputId",current==null?"":current.id).put("target",current==null?JSONObject.NULL:current.target.json())
        .put("phase",phase).put("message",message).put("active",current!=null&&!current.done).put("leaseHeld",unknown||current!=null&&current.leased).put("resourceReleaseUnknown",unknown)
        .put("finalText",draft).put("reviewAvailable",!draft.isEmpty()&&!unknown).put("staged",!staged.isEmpty()).put("submittedClientMessageId",current!=null&&current.target.phoneInput?"":submittedClient).put("submittedOwnerInputId",submittedClient)
        .put("provider","android_system_default").put("networkUse","unknown").put("languageSupport","unknown").put("requestedLanguage",requestedLanguage.get()).put("partialExecutes",false).put("automaticSend",false).put("automaticFallback",false);
    }}
    public boolean active(){synchronized(gate){return current!=null&&!current.done;}}
    public boolean leaseHeld(){synchronized(gate){return unknown||current!=null&&current.leased;}}
    public boolean canReview(Target visible){synchronized(gate){return current!=null&&!draft.isEmpty()&&!unknown&&current.target.sameInput(visible);}}
    public void start(Target target,Authority authority,Audio audio)throws Exception{
        authority.check();Capture capture=new Capture(java.util.Objects.requireNonNull(target),authority,java.util.Objects.requireNonNull(audio));
        synchronized(gate){if(closed||active()||unknown||!draft.isEmpty()||!staged.isEmpty())throw new IllegalStateException("请先处理当前语音草稿，或等待音频收尾");current=capture;phase="acquiring";message="正在准备系统短句听写；可能使用网络，尚未发送任务";submittedClient="";}
        signal();
        try{authority.check();audio.acquire(capture.id).whenComplete((owner,error)->{
            if(error!=null){withoutLease(capture);return;}
            synchronized(gate){capture.leased=true;}
            try{
                if(!capture.id.equals(owner))throw new IllegalStateException("系统听写音频归属回执不一致");authority.check();
                synchronized(gate){if(capture.cancelled||closed)throw new IllegalStateException("本次听写已取消");phase="listening";message="系统听写正在收音。短句结束后先核对文字，不会自动发送";}signal();
                audio.listen(capture.id).orTimeout(90,TimeUnit.SECONDS).whenComplete((text,failure)->{
                    try{authority.check();if(failure!=null||text==null||text.isBlank()||text.length()>4000)throw new IllegalStateException("系统未返回可用的最终文字");finish(capture,text.trim());}
                    catch(Exception unavailable){finish(capture,null);}
                });
            }catch(Exception stopped){finish(capture,null);}
        });}catch(Exception unavailable){withoutLease(capture);}
    }
    /** Ends capture/observation only; nothing in this class has task cancellation authority. */
    public void cancel(String inputId){Capture capture;synchronized(gate){capture=current;if(capture==null||!capture.id.equals(inputId))throw new IllegalStateException("听写输入已变化");if(capture.done)return;capture.cancelled=true;phase="stopping";message="正在停止本次听写，原任务继续保留";}if(capture.leased)finish(capture,null);signal();}
    private void finish(Capture capture,String text){
        synchronized(gate){if(capture.cleaning||capture.done)return;capture.cleaning=true;phase="stopping";message="正在释放本次听写音频";}signal();
        try{capture.audio.stop(capture.id).whenComplete((ignored,stopFailure)->{
            boolean confirmed=stopFailure==null;try{confirmed&=!capture.audio.releaseUnknown();}catch(RuntimeException unavailable){confirmed=false;}
            final boolean stoppedCleanly=confirmed;
            try{capture.audio.release(capture.id,confirmed).whenComplete((released,releaseFailure)->settle(capture,text,stoppedCleanly&&releaseFailure==null));}
            catch(RuntimeException unavailable){settle(capture,null,false);}
        });}catch(RuntimeException unavailable){settle(capture,null,false);}
    }
    private void settle(Capture capture,String text,boolean confirmed){
        boolean allowed=true;try{capture.authority.check();}catch(Exception revoked){allowed=false;}
        try{confirmed&=!capture.audio.releaseUnknown();}catch(RuntimeException unavailable){confirmed=false;}
        synchronized(gate){unknown|=!confirmed;capture.leased=!confirmed;capture.done=true;if(current!=capture)return;
            if(unknown){phase="failed";message="系统音频释放未确认，已阻止再次录音；请检查系统采集状态";draft="";}
            else if(capture.cancelled||closed||!allowed){phase="cancelled";message="本次听写已停止，没有发送输入，也没有取消原任务";draft="";}
            else if(text==null){phase="failed";message="系统识别未完成或没有可用最终文字；请继续文字输入，没有切换服务";draft="";}
            else{draft=text;phase="review";message="系统最终识别仅作草稿。请修改、核对后放入原输入，再由你发送";}
        }signal();
    }
    private void withoutLease(Capture capture){
        boolean unconfirmed;try{unconfirmed=capture.audio.releaseUnknown();}catch(RuntimeException unavailable){unconfirmed=true;}
        synchronized(gate){if(capture.done)return;unknown|=unconfirmed;capture.leased=unconfirmed;capture.done=true;if(current==capture){phase=capture.cancelled?"cancelled":"failed";message=unknown?"系统音频释放未确认，已阻止再次录音":"系统听写暂不可用；请继续文字输入，没有自动换用云服务";}}signal();
    }
    /** Native editor only. This returns text to the original composer, never sends it. */
    public JSONObject acceptDraft(String inputId,String editedText,Target visible,Authority currentOwner)throws Exception{
        currentOwner.check();
        Capture capture;synchronized(gate){capture=current;if(capture==null||!capture.id.equals(inputId)||draft.isEmpty()||unknown||!capture.target.sameInput(visible))throw new SecurityException("语音草稿不属于当前原输入，请返回原对话核对");}
        if(editedText==null||editedText.isBlank()||editedText.length()>4000)throw new IllegalArgumentException("请核对1至4000字的最终目标");
        JSONObject result;synchronized(gate){if(current!=capture||draft.isEmpty()||closed)throw new IllegalStateException("语音草稿已变化");result=new JSONObject().put("inputId",capture.id).put("target",capture.target.json()).put("text",editedText.trim()).put("asrFinalText",draft).put("ownerEdited",!draft.equals(editedText.trim()));draft="";staged=editedText.trim();phase="staged";message="文字已放入原输入，可继续编辑；发送仍走原任务入口";}signal();return result;
    }
    /** Reuse this native input identity as the ordinary Controller's clientMessageId, once. */
    public String claimMessageId(Target visible,Authority currentOwner)throws Exception{Capture capture;synchronized(gate){capture=current;if(capture==null||staged.isEmpty()||!capture.target.sameInput(visible))return null;}currentOwner.check();synchronized(gate){if(current!=capture||staged.isEmpty())return null;staged="";submittedClient=capture.id;phase="submitted";message="已交给原输入登记流程；实际状态以原消息或任务回执为准";}signal();return capture.id;}
    /** The editor owns staged text now. Leaving its input must not fence a different composer. */
    public void inputChanged(Target visible){
        synchronized(gate){if(current==null||!current.done||staged.isEmpty()||current.target.sameInput(visible))return;staged="";if(!unknown){phase="idle";message="已切换原输入；编辑器中的文字保留，新听写须由你重新开始";}}
        signal();
    }
    public void discardDraft(String inputId){synchronized(gate){if(current==null||!current.id.equals(inputId))throw new IllegalStateException("语音输入已变化");if(active())throw new IllegalStateException("请先停止当前听写");draft="";staged="";phase=unknown?"failed":"idle";message="语音草稿已丢弃；原任务未取消";}signal();}
    private void signal(){try{changed.run();}catch(RuntimeException ignored){}}
    @Override public void close(){Capture capture;synchronized(gate){closed=true;capture=current;draft="";staged="";}if(capture!=null&&!capture.done)cancel(capture.id);}
}
