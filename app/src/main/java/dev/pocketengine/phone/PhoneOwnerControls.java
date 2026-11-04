package dev.pocketengine.phone;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import java.util.Objects;
import org.json.JSONObject;

/** Native overlay binding only. The original Application/TaskStore still decides pause/resume/stop. */
final class PhoneOwnerControls {
    private final EngineApplication app;
    private final String taskId;
    private final RunControl original;
    PhoneOwnerControls(EngineApplication app,String taskId,RunControl original) {
        this.app=Objects.requireNonNull(app);this.taskId=Objects.requireNonNull(taskId);this.original=Objects.requireNonNull(original);
    }
    private boolean ownsControl() {return taskId.equals(app.activeId())&&original==app.control(taskId);}
    private boolean ownsRun()throws Exception {
        if(!ownsControl()||!original.hasHostRun())return false;
        JSONObject task=app.tasks.get(taskId,false);
        return task!=null&&"phone".equals(task.optString("kind"))&&"main_screen".equals(task.optString("executionMode"))
                &&task.optString("status").matches("preparing|running|pausing|paused|awaiting_user|reconciling")
                &&original.matchesHostRun(taskId,task.optString("runId"),task.optInt("coordinatorEpoch"));
    }
    void toggle() {
        try {
            if(!ownsControl()||!original.hasHostRun()||original.isCancelled())return;
            var identity=original.hostRunIdentity();
            if(original.isPaused())app.resumeOriginalPhoneRun(taskId,identity.runId(),identity.epoch());
            else app.pauseOriginalPhoneRun(taskId,identity.runId(),identity.epoch());
        } catch(Exception unavailable) {app.error("原手机任务状态暂不可核对，请返回应用；未继续执行");}
    }
    boolean pause() {
        try {
            if(!ownsControl()||!original.hasHostRun()||original.isCancelled())return false;
            var identity=original.hostRunIdentity();
            if(!original.isPaused())app.pauseOriginalPhoneRun(taskId,identity.runId(),identity.epoch());
            return true;
        } catch(Exception unavailable) {
            if(!ownsControl())return false;
            original.pause();app.error("已请求暂停原控制，请返回应用核对任务状态");return true;
        }
    }
    void stop() {
        if(!ownsControl())return;
        try {
            var identity=original.hostRunIdentity();
            if(identity==null)original.cancel();else app.stopOriginalPhoneRun(taskId,identity.runId(),identity.epoch());
        } catch(Exception unavailable) {original.cancel();app.error("已停止原执行控制，请返回应用核对任务记录");}
    }
    ViewState view(String goal) {
        synchronized(app) {
            try {
                if(!ownsRun())return new ViewState(false,false,false,"原任务已结束或执行身份变化，请返回应用查看记录");
                if(original.isCancelled())return new ViewState(true,false,false,"正在停止后续操作，已发生的动作不会撤回");
                if(app.tasks.hasUnresolved(taskId))return new ViewState(true,original.isPaused(),!original.isPaused(),"动作结果待核对，请暂停并返回原任务确认后再继续");
                return new ViewState(true,original.isPaused(),true,original.isPaused()?"已请求暂停；继续时会重新观察原任务":goal);
            } catch(Exception unavailable) {return new ViewState(ownsControl(),original.isPaused(),false,"原任务状态暂不可核对，请返回应用");}
        }
    }
    static final class ViewState {
        final boolean current,paused,canToggle;
        final String message;
        ViewState(boolean current,boolean paused,boolean canToggle,String message){this.current=current;this.paused=paused;this.canToggle=canToggle;this.message=message;}
    }
}
