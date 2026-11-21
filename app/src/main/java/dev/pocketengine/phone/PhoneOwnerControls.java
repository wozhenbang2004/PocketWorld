package dev.pocketengine.phone;

import android.content.Context;
import android.content.Intent;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Buttons are bound to one native source and one displayed intention, not to a live toggle. */
final class PhoneOwnerControls {
    enum Action { NONE, PAUSE, RESUME_LOCAL, RESUME_SOURCE, REVIEW }
    record ViewState(boolean current,Action action,long pause,long scope,String displayHandle,String message,boolean resultWatch) {
        boolean sameInput(ViewState other){return other!=null&&action==other.action&&pause==other.pause&&scope==other.scope&&Objects.equals(displayHandle,other.displayHandle);}
        ViewState withResultWatch(){return new ViewState(current,action,pause,scope,displayHandle,message,true);}
    }
    private final EngineApplication app;
    private final String taskId;
    private final RunControl original;
    private final RunControl.HostRunIdentity born;
    PhoneOwnerControls(EngineApplication app,String taskId,RunControl original) {
        this.app=Objects.requireNonNull(app);this.taskId=Objects.requireNonNull(taskId);this.original=Objects.requireNonNull(original);born=original.hostRunIdentity();
    }
    private boolean ownsControl(){return born!=null&&taskId.equals(born.taskId())&&original==app.control(taskId)&&original.matchesHostRun(taskId,born.runId(),born.epoch());}
    boolean retained(){return born!=null&&taskId.equals(born.taskId())&&app.retainsShownPhoneControl(taskId,original)&&
            original.matchesHostRun(taskId,born.runId(),born.epoch())&&!original.isCancelled();}
    boolean samePause(ViewState state){return state!=null&&(state.pause()<0||state.pause()==original.pauseRevision());}
    static ViewState reading(){return state(true,Action.NONE,-1,"正在核对原任务，暂不能据此继续执行");}
    Intent originalReview(Context context)throws Exception{
        if(born==null||!taskId.equals(born.taskId()))throw new IllegalStateException("缺少原任务身份");
        return PhoneNotificationControls.open(context,EngineApplication.json("id",taskId,"runId",born.runId(),"coordinatorEpoch",born.epoch())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    /** Pause is idempotent and never calls resume, even if the runtime changed before touch-up. */
    boolean pause() {
        try {
            if(!ownsControl()||original.isCancelled())return false;
            if(!original.isPaused())app.pauseOriginalPhoneRun(taskId,born.runId(),born.epoch());
            return true;
        } catch(Exception unavailable) {
            if(!ownsControl())return false;
            if(!original.isPaused())original.pause();app.error("已请求暂停原控制，请返回应用核对任务状态");return true;
        }
    }
    /** An expired source can still be opened read-only; never pause the successor on its behalf. */
    boolean holdForReturn(){return !ownsControl()||original.isCancelled()||pause();}
    void resume(ViewState shown,BooleanSupplier visible)throws Exception {
        if(!visible.getAsBoolean()||!ownsControl()||!original.ownerPausedAt(shown.pause()))throw new SecurityException("眼前的原暂停已变化");
        if(shown.action()==Action.RESUME_LOCAL)app.resumeDisplayedPhoneRun(taskId,born.runId(),born.epoch(),shown.pause(),shown.scope(),shown.displayHandle(),visible);
        else if(shown.action()==Action.RESUME_SOURCE)app.resumeSourceOverlayPhoneRun(taskId,born.runId(),born.epoch(),original,shown.pause(),visible);
        else throw new SecurityException("这不是已显示的继续动作");
    }
    java.util.concurrent.CompletionStage<String> stop() {
        if(!retained())throw new IllegalStateException("眼前的原控制已变化，未提交停止");
        return app.submitMainOverlayStop(taskId,born.runId(),born.epoch(),original);
    }
    boolean stopAccepted(String value){
        try{JSONObject r=new JSONObject(value);return born!=null&&taskId.equals(r.opt("taskId"))&&born.runId().equals(r.opt("runId"))&&
                born.epoch()==r.optInt("epoch",-1)&&Boolean.TRUE.equals(r.opt("controlRequested"));}catch(Exception unknown){return false;}
    }
    /** Only the owner's explicit overlay gesture. The SQL transaction calls the
     * lock-free original overlay fence, not the Engine/overlay presentation reads. */
    boolean watchResult(BooleanSupplier visible)throws Exception {
        if(!visible.getAsBoolean()||!ownsControl()||original.isCancelled())throw new SecurityException("原手机任务窗口已变化");
        JSONObject receipt=app.privatePhoneResultWatch(taskId,born.runId(),born.epoch(),true,visible);
        return visible.getAsBoolean()&&ownsControl()&&taskId.equals(receipt.opt("taskId"))&&born.runId().equals(receipt.opt("runId"))&&
                born.epoch()==receipt.optInt("epoch",-1)&&"this_run_this_phone".equals(receipt.opt("scope"))&&
                Boolean.TRUE.equals(receipt.opt("watching"))&&Boolean.TRUE.equals(receipt.opt("systemAvailable"))&&
                Boolean.FALSE.equals(receipt.opt("externalDelivery"));
    }
    ViewState view(String goal) {
        try {
            JSONObject row=ownsControl()?app.tasks.originalPhoneOverlayContext(taskId):null;
            if(row==null||!born.runId().equals(row.optString("runId"))||born.epoch()!=row.optInt("epoch",-1)||!ownsControl()||
                    !row.optString("status").matches("preparing|running|pausing|paused|awaiting_user|reconciling"))
                return state(false,Action.NONE,-1,"原任务已结束或执行身份变化，请返回原任务查看记录");
            long revision=original.pauseRevision();
            if(original.isCancelled())return state(true,Action.NONE,revision,"正在停止后续操作，已发生的动作不会撤回");
            boolean unresolved=row.optBoolean("unresolved");
            if(!original.isPaused()){
                ViewState shown=state(true,Action.PAUSE,revision,unresolved?"动作结果待核对，可先暂停并返回原任务":goal);
                return row.getBoolean("privateLocal")&&"running".equals(row.opt("status"))&&!unresolved?shown.withResultWatch():shown;
            }
            if(!"paused".equals(row.optString("status"))||unresolved||!original.ownerPausedAt(revision))
                return state(true,Action.REVIEW,revision,unresolved?"有未核对的动作，请查看原任务；不会自动继续":"已停在原任务，请核对暂停或本人步骤");
            if(!row.getBoolean("privateLocal"))return row.optBoolean("identifiedSource")?state(true,Action.RESUME_SOURCE,revision,"原来源任务已暂停；普通继续不处理本人步骤"):
                    state(true,Action.REVIEW,revision,"这次暂停的来源尚未核实，请查看原任务");
            long scope=row.optLong("scopeRevision",-1);String handle=scope<1?null:original.displayedPauseHandle(revision);
            if(handle==null)return state(true,Action.REVIEW,revision,"这次暂停仍需核对，请查看原任务");
            return new ViewState(true,Action.RESUME_LOCAL,revision,scope,handle,"已暂停。继续只恢复面前的原执行，不代表已取得新屏幕",true);
        } catch(Exception unavailable){return state(ownsControl(),Action.NONE,-1,"原任务状态暂不可核对，请返回应用");}
    }
    private static ViewState state(boolean current,Action action,long pause,String message){return new ViewState(current,action,pause,-1,null,message,false);}
}
