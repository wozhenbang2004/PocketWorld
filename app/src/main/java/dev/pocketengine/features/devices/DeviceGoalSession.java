package dev.pocketengine.features.devices;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;

/** Native first admission and captured local model for ONE original run, including approval waits.
 * There is no task/operation store or inference loop here. Never reconstruct this from JSON. */
public final class DeviceGoalSession {
    private final String taskId;
    private final DeviceExecutionAdmission admission;
    private final RunControl original;
    private final ModelConfig captured;
    private final dev.pocketengine.store.TaskStoreDeviceTasks.GoalModelApproval approval;
    public DeviceGoalSession(String taskId, DeviceExecutionAdmission admission, RunControl original, dev.pocketengine.store.TaskStoreDeviceTasks.GoalModelApproval approval) throws Exception {
        this.taskId = java.util.Objects.requireNonNull(taskId); this.admission = java.util.Objects.requireNonNull(admission);
        this.original = java.util.Objects.requireNonNull(original); this.approval=java.util.Objects.requireNonNull(approval);this.captured = approval.capturedModel();
        if (admission.kind() != DeviceExecutionAdmission.Kind.GOAL || !original.hasHostRun() || !taskId.equals(original.hostRunIdentity().taskId())
                || !"capabilities".equals(admission.executionMode())) throw new SecurityException("设备目标需要独立准入的原能力run，不借主屏START");
        if(approval.deviceAdmission()!=admission)throw new SecurityException("模型不是本原设备目标准入");approval.checkCurrent(original);
    }
    private boolean runnerActive;private Finished latest;private volatile boolean ownerResultClosed;
    public boolean ownerResultClosed(){return ownerResultClosed;}
    public synchronized void markOwnerResultClosed(){if(runnerActive)throw new SecurityException("原Runner仍活跃");ownerResultClosed=true;}
    /** Service lifecycle proof, never deserialized from any model/peer result. */
    public static final class Finished {
        private final DeviceGoalSession session;private final String result;
        private Finished(DeviceGoalSession session,dev.pocketengine.core.RunOutcome result)throws Exception{this.session=session;this.result=new org.json.JSONObject().put("status",result.status).put("summary",result.summary).put("evidence",new org.json.JSONObject(result.evidence.toString())).toString();}
        public DeviceGoalSession originalSession(){return session;}
        public org.json.JSONObject result()throws Exception{return new org.json.JSONObject(result);}
        public void requireCurrent(){synchronized(session){if(session.runnerActive||session.latest!=this||!session.original.matchesHostRun(session.taskId,session.original.hostRunIdentity().runId(),session.original.hostRunIdentity().epoch()))throw new SecurityException("原Runner完成证明已失效");}}
    }
    public synchronized void runnerStarted()throws Exception{checkCurrent();if(runnerActive)throw new SecurityException("原Runner已经在运行");runnerActive=true;latest=null;}
    public synchronized Finished runnerFinished(dev.pocketengine.core.RunOutcome actual)throws Exception{if(!runnerActive)throw new SecurityException("没有本原Runner调用");runnerActive=false;latest=new Finished(this,java.util.Objects.requireNonNull(actual));return latest;}
    public synchronized void runnerFailed(){runnerActive=false;latest=null;}
    public String taskId() { return taskId; }
    public RunControl originalControl() { return original; }
    public DeviceExecutionAdmission admission() { return admission; }
    public ModelConfig capturedModel() { return captured; }
    public dev.pocketengine.store.TaskStoreDeviceTasks.GoalModelApproval modelApproval(){return approval;}
    public void checkCurrent() throws Exception {if(ownerResultClosed)throw new SecurityException("原结果已关闭，不继续执行");approval.checkCurrent(original); original.check(); }
}
