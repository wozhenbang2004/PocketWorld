package dev.pocketengine.features.semantic;

import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.features.capabilities.ToolApprovalPermit;
import dev.pocketengine.store.TaskStore;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/** Adapts the existing native authority; it neither creates nor completes a TaskStore operation. */
public final class SemanticTaskAuthority implements SemanticCapabilityPort.Authority {
    private final TaskStore tasks;
    private final ModelCapabilityAdapters.Binding binding;
    private final TaskStoreCapabilityAuthority existing;
    private final EventSink events;
    public SemanticTaskAuthority(TaskStore tasks,ModelCapabilityAdapters.Binding binding,EventSink approvalEvents) {
        this.tasks=Objects.requireNonNull(tasks);this.binding=Objects.requireNonNull(binding);events=Objects.requireNonNull(approvalEvents);
        existing=new TaskStoreCapabilityAuthority(tasks,binding,events);
    }
    @Override public void check(String tool,RunControl control) throws Exception {
        existing.check(tool,control);
        if(tool.equals(SemanticCapabilityPort.DISPATCH)) {
            JSONObject task=tasks.get(binding.taskId,false);
            if(!binding.allowWrites||task==null||!"phone".equals(task.optString("kind"))||!task.optString("executionMode").matches("main_screen|background"))
                throw new SecurityException("此语义分派仅允许主人明确选择的原手机任务与执行位置");
        }
    }
    @Override public void requirePrepared(String operationId,String tool,JSONObject originalArguments) throws Exception {existing.requirePrepared(operationId,tool,originalArguments);}
    @Override public void requirePlanningRead(String operationId,String tool,JSONObject originalArguments,RunControl control)throws Exception {
        binding.check(control);
        if(!SemanticCapabilityPort.DISCOVER.equals(tool)||!tasks.requireExecutionPlanningRead(binding.taskId,binding.runId,binding.epoch,operationId,tool,originalArguments,control))
            throw new SecurityException("发现入口仅接受原只读规划声明，不能使用普通动作operation");
    }
    @Override public void requireNativeOwnerReview(String tool,JSONObject originalArguments,RunControl control)throws Exception {
        if(!SemanticCapabilityPort.DISPATCH.equals(tool))throw new SecurityException("日历主人审批只路由原语义派发");
        check(tool,control);ToolApprovalPermit.requireNativeOwnerReview(binding,tool,originalArguments,control);
    }
    @Override public boolean approvedForContinuation(String approvalId,String tool,JSONObject originalArguments,JSONObject capability,RunControl control)throws Exception {
        check(tool,control);
        if(!SemanticCapabilityPort.DISPATCH.equals(tool)||!capability.optBoolean("writesCalendarProvider")||!capability.optBoolean("requiresOwnerConsent"))return false;
        JSONObject task=tasks.approvalContextSnapshot(binding.taskId);
        if(task==null||!binding.taskId.equals(task.optString("id"))||!binding.runId.equals(task.optString("runId"))
                ||binding.epoch!=task.optInt("coordinatorEpoch",-1)||task.optBoolean("cancelRequested"))return false;
        JSONArray rows=task.optJSONArray("approvals");
        for(int i=0;rows!=null&&i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);if(!approvalId.equals(row.optString("id")))continue;
            return binding.runId.equals(row.optString("runId"))&&tool.equals(row.optString("tool"))&&"approved".equals(row.optString("state"))
                    &&row.optLong("expiresAt")>System.currentTimeMillis()&&TaskStore.capabilityFingerprint(originalArguments).equals(row.optString("argumentsHash"))
                    &&TaskStore.capabilityFingerprint(capability).equals(TaskStore.capabilityFingerprint(new JSONObject(row.getString("capabilityKey"))));
        }
        return false;
    }
    @Override public JSONObject approve(String tool,JSONObject originalArguments,JSONObject capability) throws Exception {
        JSONObject result=tasks.requireCapabilityApproval(binding.taskId,binding.runId,tool,originalArguments,capability);
        if(!result.optBoolean("approved"))events.emit(new JSONObject().put("taskId",binding.taskId).put("runId",binding.runId).put("tool",tool)
                .put("approvalId",result.optString("approvalId")).put("arguments",originalArguments).put("capability",capability)
                .put("message",capability.optBoolean("writesCalendarProvider")?"需要主人在本机确认当前资料、准确日历、完整事件及修改或删除范围；尚未写入":"需要主人在本机确认本次App、入口参数和原任务执行位置；尚未派发"));
        return result;
    }
}
