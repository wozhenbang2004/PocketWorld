package dev.pocketengine.features.automation;

import dev.pocketengine.features.AutomationPlanHost;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native thin adapter. TaskStore is the only task/journal index; Engine owns every original
 * Runner admission/controller. Alarm callbacks only request that existing worker. */
public final class OriginalConditionTaskHost implements AutomationPlanHost.ConditionTaskPort {
    @FunctionalInterface public interface OriginalRunner {void admit(String originalTask,JSONObject currentNativeRequest)throws Exception;}
    @FunctionalInterface public interface OriginalPlanAdmission {JSONObject admit(JSONObject originalPreparedPlan)throws Exception;}
    private final TaskStore tasks;private final AutomationModule module;private final OriginalRunner runners;private final OriginalPlanAdmission plans;
    public OriginalConditionTaskHost(TaskStore tasks,AutomationModule module,OriginalRunner runners,OriginalPlanAdmission plans){this.tasks=java.util.Objects.requireNonNull(tasks);this.module=java.util.Objects.requireNonNull(module);this.runners=java.util.Objects.requireNonNull(runners);this.plans=java.util.Objects.requireNonNull(plans);}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    @Override public String ensureOriginalReadOnlyTask(JSONObject occurrence)throws Exception{return tasks.ensureAutomationConditionTask(module.semanticCheckRequest(occurrence.getString("id"))).getString("id");}
    @Override public void startOriginalReadOnlyTask(JSONObject occurrence)throws Exception{
        JSONObject request=module.semanticCheckRequest(occurrence.getString("id")),task=tasks.get(occurrence.getString("conditionTaskId"),false);
        if(task==null)throw new SecurityException("原只读条件任务不存在");
        // A queued original can wait for the shared worker. Any started/unknown run is query-only.
        if(!task.optString("runId").isEmpty()||!"queued".equals(task.optString("status")))return;
        if(!TaskStore.capabilityFingerprint(request).equals(TaskStore.capabilityFingerprint(tasks.automationConditionRequest(task.getString("id")))))throw new SecurityException("原条件任务不能更换来源或预算");
        runners.admit(task.getString("id"),request);
    }
    @Override public long nextOriginalWake(JSONObject occurrence,long now)throws Exception{
        String state=occurrence.optString("status");if(state.equals("condition_action_unknown"))return reconcileApprovedOriginalPlan(occurrence)==null?-1:now;
        String id=occurrence.optString("conditionTaskId");if(id.isEmpty())return now;JSONObject original=tasks.get(id,false);if(original==null)return -1;
        return original.optString("status").matches("preparing|running|observing|planning|acting|stopping")?-1:now;
    }
    @Override public AutomationConditionOutcome readOriginalOutcome(JSONObject occurrence)throws Exception{
        String id=occurrence.optString("conditionTaskId");if(id.isEmpty())return null;
        return tasks.automationConditionOutcome(id);
    }
    @Override public boolean hasApprovedOriginalPlan(JSONObject occurrence)throws Exception{
        JSONObject rule=module.semanticRuleForHost(occurrence.getString("ruleId")),binding=rule.optJSONObject("semanticPlanBinding");if(binding==null)return false;
        JSONObject plan=module.handle("getPlan",new JSONObject().put("scope",scope()).put("id",binding.getString("planId"))).getJSONObject("plan"),execution=plan.optJSONObject("execution");
        if(rule.getLong("revision")!=occurrence.getLong("revision")||!plan.optBoolean("approvalValid")||execution==null||!execution.optString("state").equals("active")||!execution.optBoolean("started")||!execution.optBoolean("modelObservationConsent")||!binding.getString("approvalKey").equals(execution.optString("approvalKey"))||binding.getLong("approvalSequence")!=execution.optLong("approvalSequence"))throw new SecurityException("原条件计划审批已变更，不能降级成通知或重新授权");
        JSONArray steps=plan.getJSONArray("steps");for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i),gate=step.optJSONObject("semanticConditionGate");if(binding.getString("stepId").equals(step.getString("id"))&&gate!=null&&binding.getLong("stepRevision")==step.getLong("revision")&&rule.getString("id").equals(gate.optString("ruleId"))&&rule.getLong("revision")==gate.optLong("ruleRevision"))return true;}
        throw new SecurityException("原条件计划步骤已变化");
    }
    @Override public JSONObject admitApprovedOriginalPlan(JSONObject occurrence)throws Exception{
        if(!hasApprovedOriginalPlan(occurrence))throw new SecurityException("没有原主人批准计划");
        AutomationConditionOutcome actual=readOriginalOutcome(occurrence);if(actual==null||!actual.known||!actual.met)throw new SecurityException("原TaskStore条件意见没有当前可用上升沿见证");
        JSONObject prepared=module.prepareSemanticPlanTask(scope(),occurrence);if(!prepared.optBoolean("eligible")&&!prepared.optBoolean("lookupOnly"))return prepared;
        JSONObject result=plans.admit(prepared),task=result.optJSONObject("task");if(task!=null)return result.put("taskId",task.getString("id"));return result;
    }
    @Override public JSONObject reconcileApprovedOriginalPlan(JSONObject occurrence)throws Exception{
        JSONObject prepared=module.semanticPlanTaskRequest(scope(),occurrence);if(prepared==null)return null;
        JSONObject actual=tasks.automationTask(prepared.getString("occurrenceId"));return actual==null?null:new JSONObject().put("taskId",actual.getString("id")).put("lookupOnly",true);
    }
}
