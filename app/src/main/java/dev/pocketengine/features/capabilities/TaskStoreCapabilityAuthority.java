package dev.pocketengine.features.capabilities;

import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reuses the existing TaskStore APIs. It never prepares, finishes or replays an operation. */
public final class TaskStoreCapabilityAuthority implements ModelCapabilityAdapters.Authority {
    private final TaskStore tasks;private final ModelCapabilityAdapters.Binding binding;private final EventSink approvals;
    public TaskStoreCapabilityAuthority(TaskStore tasks,ModelCapabilityAdapters.Binding binding,EventSink approvalEvents){this.tasks=Objects.requireNonNull(tasks);this.binding=Objects.requireNonNull(binding);approvals=Objects.requireNonNull(approvalEvents);}
    @Override public void check(String tool,RunControl control)throws Exception {binding.check(control);tasks.checkCapability(binding.taskId,binding.runId,binding.epoch,tool,control);}
    @Override public JSONObject approve(CapabilityDescriptor tool,JSONObject args,JSONObject capability)throws Exception {
        JSONObject result=tasks.requireCapabilityApproval(binding.taskId,binding.runId,tool.name,args,capability);
        if(!result.optBoolean("approved"))approvals.emit(new JSONObject().put("taskId",binding.taskId).put("runId",binding.runId)
                .put("approvalId",result.optString("approvalId")).put("tool",tool.name).put("capability",capability)
                .put("arguments",tool.redactedArguments(args)).put("message","需要主人确认本次参数和固定能力身份；尚未执行。"));
        return result;
    }
    @Override public void requirePrepared(String operationId,String tool,JSONObject originalArgs)throws Exception {
        JSONObject task=tasks.get(binding.taskId,false);
        if(task==null||!binding.runId.equals(task.optString("runId"))||binding.epoch!=task.optInt("coordinatorEpoch"))throw new SecurityException("原operation执行身份已失效");
        if(tasks.requireExecutionPlanningRead(binding.taskId,binding.runId,binding.epoch,operationId,tool,originalArgs,binding.originalControl()))return;
        requirePreparedRecord(task.getJSONArray("operations"),binding.runId,operationId,tool,originalArgs);
    }
    /** Pure record check is also used by fixture tests; the source of records remains TaskStore. */
    static void requirePreparedRecord(JSONArray operations,String runId,String operationId,String tool,JSONObject args)throws Exception {
        boolean found=false;String expected=TaskStore.capabilityFingerprint(args);
        for(int i=0;i<operations.length();i++){
            JSONObject operation=operations.getJSONObject(i);String id=operation.getString("operationId"),state=operation.getString("state");
            if(id.equals(operationId)){
                if(found||!runId.equals(operation.optString("runId"))||!tool.equals(operation.optString("tool"))||!state.equals("prepared")||!expected.equals(TaskStore.capabilityFingerprint(operation.getJSONObject("arguments"))))throw new SecurityException("operation已用、参数已变或不属于本执行");found=true;
            }else if(state.equals("prepared")||state.equals("unknown"))throw new SecurityException("已有operation待核对，不能继续派发");
        }
        if(!found)throw new SecurityException("缺少原AgentRunner登记的operation");
    }
}
