package dev.pocketengine.media.voice.tasks;

import dev.pocketengine.media.RealtimeReviewedTurn;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** One ephemeral view of an original voice task; all task/run/approval authority remains in host. */
public final class VoiceTaskJourney implements AutoCloseable {
    private final Object gate=new Object();
    private final Runnable changed;
    private final VoiceTaskReadout readout;
    private VoiceTaskHostPort port;
    private VoiceTaskHostPort.Reference reference;
    private VoiceTaskHostPort.TaskState task;
    private VoiceTaskHostPort.Authority authority;
    private VoiceTaskHostPort.Subscription subscription;
    private String bindingId="",viewToken="",cancelToken="",lastTaskId="",phase="unavailable",message="语音任务宿主尚未接入";
    private long generation,revision;
    private int maxEpochSeen;
    private boolean busy,startAttempted,cancelAttempted,needsRefresh,observing,closed;
    private JSONArray recentTasks=new JSONArray();
    private String recoveryMessage="";
    public VoiceTaskJourney(Runnable changed){this.changed=changed;readout=new VoiceTaskReadout(changed);}
    public void install(VoiceTaskHostPort host){synchronized(gate){if(closed||reference!=null)throw new IllegalStateException("请先离开当前语音任务");port=java.util.Objects.requireNonNull(host);phase="idle";message="";revision++;}signal();}
    public boolean installed(){synchronized(gate){return port!=null&&!closed;}}
    public boolean leaseHeld(){return readout.leaseHeld();}
    public boolean reading(){return readout.active();}
    public JSONObject snapshot()throws Exception{synchronized(gate){
        return new JSONObject().put("available",port!=null&&!closed).put("bindingId",bindingId).put("viewToken",viewToken).put("cancelToken",cancelToken).put("generation",generation).put("revision",revision).put("phase",phase).put("message",message).put("busy",busy)
            .put("lastTaskId",lastTaskId).put("goal",reference==null?"":reference.text).put("task",task==null?JSONObject.NULL:task.toJson()).put("sessionId",reference==null?"":reference.sessionId)
            .put("needsRefresh",needsRefresh).put("observing",observing).put("startAttempted",startAttempted).put("cancelAttempted",cancelAttempted)
            .put("recentTasks",new JSONArray(recentTasks.toString())).put("recoveryMessage",recoveryMessage)
            .put("canStart",!busy&&!needsRefresh&&!startAttempted&&task!=null&&task.epoch==0&&task.status.equals("awaiting_user"))
            .put("canCancel",!busy&&!needsRefresh&&!cancelAttempted&&task!=null&&task.epoch>0&&!task.terminal()&&!task.status.equals("stopping"))
            .put("readout",readout.snapshot()).put("taskStoreAuthority",true).put("automaticRetry",false).put("sensitiveApprovalByVoice",false);
    }}
    /** Only the existing native reviewed-final persistence path supplies this immutable value. */
    public JSONObject attachSaved(RealtimeReviewedTurn reviewed,VoiceTaskHostPort.Authority current)throws Exception{
        return attach(new VoiceTaskHostPort.Reference(reviewed),current);
    }
    public JSONObject listSaved(VoiceTaskHostPort.Authority current)throws Exception{
        current.check();VoiceTaskHostPort host;final long token;synchronized(gate){if(closed||port==null)throw new IllegalStateException("语音任务宿主尚未安装");host=port;token=generation;}
        JSONArray records=host.recentOriginals(current);current.check();synchronized(gate){if(closed||port!=host||token!=generation)throw new IllegalStateException("语音任务宿主已变化");recentTasks=new JSONArray(records.toString());recoveryMessage=records.length()==0?"还没有保存的语音目标。完成说话后，复核最终文字并明确开始。":"选择原任务只恢复查看，不会重新创建或启动。";revision++;}signal();return snapshot();
    }
    public JSONObject recoverSaved(String taskId,VoiceTaskHostPort.Authority current)throws Exception{
        current.check();VoiceTaskHostPort host;synchronized(gate){if(closed||port==null)throw new IllegalStateException("语音任务宿主尚未安装");host=port;}
        VoiceTaskHostPort.Recovered recovered=host.recoverOriginal(taskId,current);current.check();return attach(recovered.reference,current);
    }
    private JSONObject attach(VoiceTaskHostPort.Reference next,VoiceTaskHostPort.Authority current)throws Exception{
        current.check();VoiceTaskHostPort host;
        detach();
        final long token;synchronized(gate){if(closed||port==null)throw new IllegalStateException("语音任务宿主尚未安装");host=port;reference=next;authority=current;bindingId=UUID.randomUUID().toString();lastTaskId=next.taskId;token=++generation;phase="checking";message="正在核对原任务记录";busy=true;revision++;}
        signal();
        try{current.check();accept(token,next,host.inspectOriginal(next,current),true);}
        catch(Exception failure){unconfirmed(token,"inspect_unavailable","原任务暂未取得可用记录；不会重新创建或执行");}
        try{
            VoiceTaskHostPort.Subscription observing=host.observeOriginal(next,current,value->acceptEvent(token,next,value));boolean keep;
            synchronized(gate){keep=token==generation&&reference==next&&!closed;if(keep){subscription=observing;this.observing=true;revision++;}}
            if(!keep)observing.close();
        }catch(Exception unsupported){synchronized(gate){if(token==generation){observing=false;revision++;}}}
        signal();return snapshot();
    }
    public JSONObject inspect(String expectedBinding)throws Exception{
        Context context=begin(expectedBinding,null,"checking");
        try{context.authority.check();accept(context.token,context.reference,context.host.inspectOriginal(context.reference,context.authority),true);}
        catch(Exception failure){unconfirmed(context.token,"inspect_unavailable","原任务记录暂不可用；请稍后手动核对，没有重跑任务");}
        signal();return snapshot();
    }
    public JSONObject startOriginal(String expectedBinding,String expectedView)throws Exception{
        Context context=begin(expectedBinding,expectedView,"starting");VoiceTaskHostPort.Command command;
        synchronized(gate){if(context.token!=generation||reference!=context.reference)throw new IllegalStateException("语音任务已变化");startAttempted=true;command=new VoiceTaskHostPort.Command(context.reference,context.task);revision++;}
        signal();
        try{context.authority.check();accept(context.token,context.reference,context.host.startOriginal(command,context.authority),true);}
        catch(Exception failure){unconfirmed(context.token,"start_unconfirmed","原任务开始结果未确认；只核对原任务，不会重复启动");}
        signal();return snapshot();
    }
    public JSONObject cancelExactRun(String expectedBinding,String expectedView)throws Exception{
        Context context=begin(expectedBinding,expectedView,"cancelling");VoiceTaskHostPort.Command command;
        synchronized(gate){if(context.token!=generation||reference!=context.reference)throw new IllegalStateException("语音任务已变化");cancelAttempted=true;command=new VoiceTaskHostPort.Command(context.reference,context.task);revision++;}
        signal();
        try{context.authority.check();accept(context.token,context.reference,context.host.cancelExactRun(command,context.authority),true);}
        catch(Exception failure){unconfirmed(context.token,"cancel_unconfirmed","原执行轮次取消回执未确认，请核对原任务；停止说话不会替代任务取消");}
        signal();return snapshot();
    }
    private static final class Context {
        final long token;final VoiceTaskHostPort host;final VoiceTaskHostPort.Reference reference;final VoiceTaskHostPort.Authority authority;final VoiceTaskHostPort.TaskState task;
        Context(long token,VoiceTaskHostPort host,VoiceTaskHostPort.Reference reference,VoiceTaskHostPort.Authority authority,VoiceTaskHostPort.TaskState task){this.token=token;this.host=host;this.reference=reference;this.authority=authority;this.task=task;}
    }
    private Context begin(String expectedBinding,String expectedView,String next)throws Exception{
        Context context;synchronized(gate){requireBinding(expectedBinding);if(expectedView!=null)requireActionView(expectedView,next);if(busy)throw new IllegalStateException("正在核对原任务，请等待回执");
            if(next.equals("starting")&&(needsRefresh||startAttempted||task==null||task.epoch!=0||!task.status.equals("awaiting_user")))throw new IllegalStateException("原任务尚不能从这条语音重复启动，请查看原任务");
            if(next.equals("cancelling")&&(needsRefresh||cancelAttempted||task==null||task.epoch<1||task.terminal()||task.status.equals("stopping")))throw new IllegalStateException("原执行轮次已变化或无需再次取消");
            context=new Context(generation,port,reference,authority,task);}
        context.authority.check();
        synchronized(gate){requireBinding(expectedBinding);if(expectedView!=null)requireActionView(expectedView,next);if(context.token!=generation||busy)throw new IllegalStateException("语音任务已变化");busy=true;phase=next;revision++;}signal();return context;
    }
    private void acceptEvent(long token,VoiceTaskHostPort.Reference expected,VoiceTaskHostPort.TaskState value){try{accept(token,expected,value,false);}catch(Exception malformed){unconfirmed(token,"needs_refresh","原任务事件未通过核对，请手动刷新原记录");}}
    private void accept(long token,VoiceTaskHostPort.Reference expected,VoiceTaskHostPort.TaskState value,boolean canonical)throws Exception{
        boolean invalidate=false;
        synchronized(gate){
            if(token!=generation||reference!=expected||closed)return;
            if(value==null){if(canonical){busy=false;needsRefresh=true;phase="not_found";message="没有查到原提交；不会重新创建或执行";revision++;}return;}
            if(!expected.taskId.equals(value.taskId))throw new SecurityException("原任务身份不一致");
            maxEpochSeen=Math.max(maxEpochSeen,value.epoch);
            if(task!=null){
                if(value.epoch<task.epoch||value.epoch==task.epoch&&!value.runId.equals(task.runId)){if(canonical){busy=false;needsRefresh=true;phase="needs_refresh";message="读到的原执行落后于已取得记录，请重新核对；没有替换当前执行";revision++;}return;}
                if(value.epoch<maxEpochSeen){needsRefresh=true;if(canonical)busy=false;phase="needs_refresh";message="原任务已进入另一轮执行，请刷新后再查看或操作";revision++;invalidate=true;}
                else if(value.epoch==task.epoch&&value.revision<task.revision){if(canonical)busy=false;return;}
                else if(!canonical&&value.epoch==task.epoch&&value.revision==task.revision&&!value.fingerprint().equals(task.fingerprint())){needsRefresh=true;phase="needs_refresh";message="同一版本收到不同原记录，请手动刷新核对";revision++;invalidate=true;}
                else{invalidate=!value.fingerprint().equals(task.fingerprint());if(invalidate)viewToken=UUID.randomUUID().toString();if(value.epoch>task.epoch||value.terminal()!=task.terminal()){cancelAttempted=false;cancelToken=UUID.randomUUID().toString();}task=value;needsRefresh=false;busy=false;phase="observing";message=taskMessage(value);revision++;}
            }else{task=value;viewToken=UUID.randomUUID().toString();cancelToken=UUID.randomUUID().toString();needsRefresh=false;busy=false;phase="observing";message=taskMessage(value);revision++;}
        }
        if(invalidate)readout.invalidate();signal();
    }
    private static String taskMessage(VoiceTaskHostPort.TaskState value){
        if(value.requiresNativeApproval||value.status.equals("needs_confirmation"))return "原任务需要本机确认，请打开原任务核对；语音不能代替敏感审批";
        if(value.status.equals("awaiting_user"))return value.epoch==0?"语音目标已保存在原任务，是否开始仍由你决定":"原任务等待主人处理，请打开原记录核对后决定如何继续；没有自动重跑";
        if(value.status.equals("completed"))return "原任务记录为完成，请查看结果与实际产物并验收";
        if(value.status.equals("failed")||value.status.equals("interrupted"))return "原任务未完成；查看原记录后决定如何继续，没有自动重跑";
        if(value.status.equals("cancelled"))return "原任务已记录取消，已发生的操作不会撤销";
        return "以原任务当前记录为准；停止说话与取消任务分别控制";
    }
    private void unconfirmed(long token,String next,String text){synchronized(gate){if(token!=generation||closed)return;busy=false;needsRefresh=true;phase=next;message=text;revision++;}readout.invalidate();signal();}
    public JSONObject readResult(String expectedBinding,String expectedView,VoiceTaskReadout.Audio audio)throws Exception{
        final Context context;final VoiceTaskHostPort.TaskState captured;
        synchronized(gate){requireBinding(expectedBinding);requireView(expectedView);if(busy||needsRefresh||task==null||task.summary.isBlank())throw new IllegalStateException("请先核对原任务的可朗读记录");context=new Context(generation,port,reference,authority,task);captured=task;}
        VoiceTaskHostPort.Authority live=()->{
            context.authority.check();synchronized(gate){if(context.token!=generation||reference!=context.reference||closed)throw new SecurityException("语音任务已变化");}
            VoiceTaskHostPort.TaskState latest=context.host.inspectOriginal(context.reference,context.authority);
            if(latest==null||!latest.taskId.equals(captured.taskId)||!latest.fingerprint().equals(captured.fingerprint()))throw new SecurityException("原任务执行轮次或结果已变化");
        };
        readout.start("原任务记录。"+taskMessage(captured)+"。原记录摘要："+captured.summary,live,audio);signal();return snapshot();
    }
    public JSONObject stopReading(String readoutId)throws Exception{readout.stop(readoutId);return snapshot();}
    public void sessionChanged(String sessionId){boolean changed;synchronized(gate){changed=reference!=null&&!reference.sessionId.equals(sessionId);}if(changed)detach();}
    public void detach(){
        VoiceTaskHostPort.Subscription previous;synchronized(gate){previous=subscription;subscription=null;reference=null;task=null;authority=null;bindingId="";viewToken="";cancelToken="";maxEpochSeen=0;generation++;busy=false;startAttempted=false;cancelAttempted=false;needsRefresh=false;observing=false;recentTasks=new JSONArray();recoveryMessage="";phase=port==null?"unavailable":"idle";message="";revision++;}
        if(previous!=null)try{previous.close();}catch(RuntimeException ignored){}readout.invalidate();signal();
    }
    private void requireBinding(String expected){if(closed||reference==null||authority==null||!bindingId.equals(expected))throw new IllegalStateException("语音任务视图已变化，请操作当前原任务");}
    private void requireView(String expected){if(expected==null||viewToken.isEmpty()||!viewToken.equals(expected))throw new IllegalStateException("原任务记录或执行轮次已变化，请核对当前状态");}
    private void requireActionView(String expected,String action){if(!action.equals("cancelling")){requireView(expected);return;}if(expected==null||cancelToken.isEmpty()||!cancelToken.equals(expected))throw new IllegalStateException("原任务执行轮次已变化，旧取消按钮不能停止新执行");}
    private void signal(){try{changed.run();}catch(RuntimeException ignored){}}
    @Override public void close(){synchronized(gate){closed=true;}detach();readout.close();}
}
