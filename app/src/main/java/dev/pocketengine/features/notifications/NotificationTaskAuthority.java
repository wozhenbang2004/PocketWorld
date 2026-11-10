package dev.pocketengine.features.notifications;

import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.store.TaskStore;
import java.util.Objects;
import org.json.JSONObject;

/** Uses the original task's prepared operation, consent review and execution deadline. */
final class NotificationTaskAuthority {
    private final TaskStore tasks;
    private final ModelCapabilityAdapters.Binding binding;
    private final TaskStoreCapabilityAuthority existing;
    private final EventSink approvals;

    NotificationTaskAuthority(TaskStore tasks,ModelCapabilityAdapters.Binding binding,EventSink approvals){
        this.tasks=Objects.requireNonNull(tasks);this.binding=Objects.requireNonNull(binding);this.approvals=Objects.requireNonNull(approvals);
        existing=new TaskStoreCapabilityAuthority(tasks,binding,approvals);
    }
    static boolean eligible(JSONObject task,boolean allowWrites){
        return allowWrites&&task!=null&&"phone".equals(task.optString("kind"))&&task.optString("executionMode").matches("main_screen|background")
                &&"owner".equals(task.optString("actorId"))&&"private".equals(task.optString("spaceId"))
                &&(!task.has("origin")||task.isNull("origin"))&&!task.has("parentTaskId")&&!task.has("teamAssignment")&&!task.has("teamWriteScope")
                &&!task.has("workProfileRef")&&!task.has("workProfileIntent")&&!"owner_work".equals(task.optString("executionRole"));
    }
    void check(RunControl control)throws Exception {
        existing.check(NotificationTaskCapabilityPort.WAIT,control);
        JSONObject task=tasks.get(binding.taskId,false);
        if(!eligible(task,binding.allowWrites)||!binding.runId.equals(task.optString("runId"))||binding.epoch!=task.optInt("coordinatorEpoch"))
            throw new SecurityException("通知等待仅属于这个原生私人手机任务与执行代次");
    }
    void requirePrepared(String operationId,JSONObject arguments)throws Exception {existing.requirePrepared(operationId,NotificationTaskCapabilityPort.WAIT,arguments);}
    void requireNativeOwnerReview(JSONObject arguments,RunControl control)throws Exception {
        check(control);dev.pocketengine.features.capabilities.ToolApprovalPermit.requireNativeOwnerReview(binding,NotificationTaskCapabilityPort.WAIT,arguments,control);
    }
    long deadline()throws Exception{return tasks.taskExecutionDeadline(binding.taskId,binding.runId,binding.epoch);}
    JSONObject identity()throws Exception{return new JSONObject().put("taskId",binding.taskId).put("runId",binding.runId).put("epoch",binding.epoch);}
    JSONObject approve(JSONObject arguments,JSONObject capability)throws Exception {
        JSONObject result=tasks.requireCapabilityApproval(binding.taskId,binding.runId,NotificationTaskCapabilityPort.WAIT,arguments,capability);
        if(!result.optBoolean("approved"))approvals.emit(new JSONObject().put("taskId",binding.taskId).put("runId",binding.runId).put("tool",NotificationTaskCapabilityPort.WAIT)
                .put("approvalId",result.optString("approvalId")).put("approvalState",result.optString("state","pending")).put("arguments",arguments).put("capability",capability)
                .put("message","denied".equals(result.optString("state"))?"主人已拒绝本次通知等待，尚未向模型读取正文。":"请确认将此应用下一条匹配通知的预览交给原手机任务及其模型；通知设置中的本机预览授权不能替代这次确认。"));
        return result;
    }
}
