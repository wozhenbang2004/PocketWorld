package dev.pocketengine.features.semantic;

import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.capabilities.ModelCapabilityAdapters;
import dev.pocketengine.features.capabilities.TaskStoreCapabilityAuthority;
import dev.pocketengine.store.TaskStore;
import java.util.Objects;
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
    @Override public JSONObject approve(String tool,JSONObject originalArguments,JSONObject capability) throws Exception {
        JSONObject result=tasks.requireCapabilityApproval(binding.taskId,binding.runId,tool,originalArguments,capability);
        if(!result.optBoolean("approved"))events.emit(new JSONObject().put("taskId",binding.taskId).put("runId",binding.runId).put("tool",tool)
                .put("approvalId",result.optString("approvalId")).put("arguments",originalArguments).put("capability",capability)
                .put("message","calendar_batch".equals(capability.optString("adapter"))?"需要主人在本机确认当前资料、准确日历和本批全部事件；尚未写入":"需要主人在本机确认本次App、入口参数和原任务执行位置；尚未派发"));
        return result;
    }
}
