package dev.pocketengine.features.local.project;

import dev.pocketengine.core.EventSink;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual original-operation bridge. No task creation, CLI runner, operation map or polling worker. */
public final class TaskStoreProjectHost implements ProjectDevelopment.HostPort {
    public static final String TOOL="project_execute";
    public interface CurrentControl {RunControl get(String taskId);}
    private final TaskStore tasks;private final LocalModule local;private final CurrentControl controls;private final EventSink approvals;
    public TaskStoreProjectHost(TaskStore tasks,LocalModule local,CurrentControl controls,EventSink approvals){this.tasks=java.util.Objects.requireNonNull(tasks);this.local=java.util.Objects.requireNonNull(local);this.controls=java.util.Objects.requireNonNull(controls);this.approvals=java.util.Objects.requireNonNull(approvals);}
    /** Native owner editor uses this same registered host and original controls. */
    public ProjectOwnerLiveFileSave ownerFileSaves(){return new ProjectOwnerLiveFileSave(tasks,local,this,controls);}
    private RunControl current(JSONObject binding)throws Exception {
        RunControl original=controls.get(binding.getString("taskId"));if(original==null||!original.hasHostRun()||!original.matchesHostRun(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch")))throw new SecurityException("项目没有原活动执行控制");
        original.check();if(original.isPaused())throw new SecurityException("项目原执行已暂停");return original;
    }
    private void live(JSONObject binding,JSONObject exact,RunControl original,JSONObject capability)throws Exception {
        if(current(binding)!=original)throw new SecurityException("项目原控制器已变化");
        synchronized(tasks){tasks.checkCapabilityCurrent(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),TOOL);tasks.projectOperation(binding,exact);JSONArray rows=tasks.get(binding.getString("taskId"),false).getJSONArray("operations");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!binding.getString("operationId").equals(row.getString("operationId"))&&row.optString("state").matches("prepared|unknown")&&!tasks.projectServiceAllowsInFlightEdit(binding,row)&&!tasks.projectServiceAllowsTeamObservation(binding,row)&&!tasks.projectServiceAllowsInFlightInput(binding,row))throw new SecurityException("原任务有其他未决动作");}}
        JSONObject editTarget=tasks.checkOwnerProjectEditScopeIfPresent(binding,original);
        if(editTarget!=null&&!editTarget.getString("runtimeIdentity").equals(local.projectRuntimeForHost(editTarget.getString("runtimeId")).optString("identity")))throw new SecurityException("原编辑范围所选运行环境身份已变化，请重新审阅");
        if(capability!=null&&!local.projectCapabilityStillCurrent(exact,capability))throw new SecurityException("已审阅的实际执行位置或版本已变化");
        original.check();
    }
    @Override public ProjectDevelopment.Claim claim(JSONObject binding,JSONObject exact)throws Exception {
        RunControl original=current(binding);live(binding,exact,original,null);JSONObject row=tasks.projectOperation(binding,exact);
        return new ProjectDevelopment.Claim(original,!"prepared".equals(row.getString("state")));
    }
    @Override public void dispatch(JSONObject binding,JSONObject exact)throws Exception {
        JSONObject frozenBinding=new JSONObject(binding.toString()),frozenExact=new JSONObject(exact.toString());RunControl original=current(frozenBinding);JSONObject capability=local.projectCapabilityForHost(frozenExact);JSONObject nativeRow=tasks.projectOperationForHost(frozenBinding);if(nativeRow.getJSONObject("journalArguments").has("planRef")){JSONObject plan=nativeRow.getJSONObject("arguments").getJSONObject("plan");capability.put("nativeProjectPlan",new JSONObject().put("fullPlanStoredInOriginalTaskStore",true).put("planDigest",plan.getString("planDigest")).put("step",ProjectPlanRecords.reviewStep(ProjectBuildPlan.step(plan,nativeRow.getJSONObject("arguments").getString("stepId")))).put("executionAuthorized",false));}live(frozenBinding,frozenExact,original,capability);
        JSONObject target=capability.getJSONObject("target");String runtime=capability.getString("runtime");boolean ready=runtime.equals("app_private_checkout")||runtime.equals("termux")&&target.optBoolean("installed")&&target.optBoolean("permissionGranted")&&target.optBoolean("enabled")||runtime.equals("managed_process_v1")&&(target.optBoolean("canExecute")||frozenExact.getString("command").equals("project.runtime.prepare")&&target.optBoolean("preparationAvailable"));
        if(!ready)throw new ProjectDevelopment.NotDispatched(new JSONObject().put("status","not_dispatched").put("accepted",false).put("code","runtime_unavailable").put("runtimeId",runtime).put("message","所选本机工具环境尚未准备完成；请在项目环境中核对实际缺少的工具，本步骤没有派发").put("businessVerified",false));
        if(frozenExact.getString("command").endsWith(".collect")){
            boolean supported=false;JSONArray phases=target.optJSONArray("phases");if(phases!=null)for(int i=0;i<phases.length();i++)supported|="COLLECT".equals(phases.optString(i));
            if(!supported)throw new ProjectDevelopment.NotDispatched(new JSONObject().put("status","not_dispatched").put("accepted",false).put("code","runtime_operation_unavailable").put("message","所选运行时没有声明此产物回收能力；本步骤未登记派发，不会尝试其他运行位置"));
        }
        // A cancellation callback can run inside an authority/store check. Keep it
        // short and local; never acquire the module monitor while a store lock is held.
        Runnable cancel=()->{try{tasks.cancelProjectOperation(frozenBinding,frozenExact);}catch(Exception unavailable){}};
        original.onCancel(cancel);boolean retained=false;
        try{
            JSONObject claimed=tasks.claimProjectOperation(frozenBinding,frozenExact,capability,original);
            if(claimed.optBoolean("notDispatched"))throw new ProjectDevelopment.NotDispatched(claimed);
            if(claimed.optBoolean("approvalRequired")){
                JSONObject row=tasks.projectOperation(frozenBinding,frozenExact);approvals.emit(new JSONObject().put("taskId",frozenBinding.getString("taskId")).put("runId",frozenBinding.getString("runId")).put("tool",TOOL).put("approvalId",claimed.optString("approvalId")).put("arguments",row.getJSONObject("arguments")).put("capability",capability).put("message","请核对此项目版本、已选运行位置和具体文件或命令；尚未派发"));
                throw new ProjectDevelopment.ApprovalRequired(new JSONObject().put("status","not_dispatched").put("accepted",false).put("code","approval_required").put("approvalId",claimed.optString("approvalId")));
            }
            if(!claimed.optBoolean("fresh"))return;
            retained=true;JSONObject route=claimed.getJSONObject("projectExecution");live(frozenBinding,frozenExact,original,capability);
            JSONObject service=tasks.projectLiveEditService(frozenBinding,frozenExact);dev.pocketengine.features.local.runtime.RuntimeExecutionRef serviceRef=null;
            if(service!=null){JSONObject serviceArgs=service.getJSONObject("arguments"),servicePlan=ProjectBuildPlan.verified(serviceArgs.getJSONObject("plan"));serviceRef=dev.pocketengine.features.local.runtime.RuntimeExecutionRef.fromHost(ProjectDevelopment.exact(servicePlan,ProjectBuildPlan.step(servicePlan,serviceArgs.getString("stepId"))),service.getJSONObject("receipt").getJSONObject("projectExecution"));}
            JSONObject receipt=local.executeProjectForHost(frozenExact,route,original,()->live(frozenBinding,frozenExact,original,capability),serviceRef);
            tasks.recordProjectOperation(frozenBinding,frozenExact,receipt);retained=!(receipt.optBoolean("receiptReceived")&&receipt.optString("status").matches("completed|failed"));
        }finally{if(!retained)original.removeCancelAction(cancel);}
    }
    @Override public JSONObject operation(JSONObject binding)throws Exception {
        // The immutable original plan is read from its real journal row, not supplied by a model.
        JSONObject task=tasks.get(binding.getString("taskId"),false);if(task==null)throw new SecurityException("原项目任务不存在");
        JSONObject row=tasks.projectOperationForHost(binding);JSONObject args=row.getJSONObject("arguments"),plan=ProjectBuildPlan.verified(args.getJSONObject("plan"));JSONObject exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,args.getString("stepId")));
        row=tasks.projectOperationReceipt(binding,exact);JSONObject receipt=row.optJSONObject("receipt"),route=receipt==null?null:receipt.optJSONObject("projectExecution");if(route==null)return null;
        if("freezeProjectPackage".equals(exact.getString("command"))){JSONObject actual=receipt.optJSONObject("executionReceipt");if(actual!=null&&ProjectPackageDelivery.terminalReceipt(exact,actual))return new JSONObject(route.toString()).put("nativeReceipt",actual);}
        if(ProjectManagedOutput.COMMAND.equals(exact.getString("command"))){JSONObject actual=receipt.optJSONObject("executionReceipt");if(actual!=null&&ProjectManagedOutput.terminalReceipt(exact,actual))return new JSONObject(route.toString()).put("nativeReceipt",actual);}
        if(java.util.Set.of("writeFile","patchFiles").contains(exact.getString("command"))){JSONObject actual=receipt.optJSONObject("executionReceipt");return actual==null?new JSONObject(route.toString()):new JSONObject(route.toString()).put("nativeReceipt",actual);}
        try{JSONObject backend=local.projectReceiptForHost(exact,route,route.optBoolean("cancelRequested"));tasks.recordProjectOperation(binding,exact,backend);return new JSONObject(route.toString()).put("nativeReceipt",backend);}catch(IllegalArgumentException missing){/* Persisted sending without a backend receipt stays unknown/query-only. */}
        JSONObject actual=receipt.optJSONObject("executionReceipt");return actual==null?new JSONObject(route.toString()):new JSONObject(route.toString()).put("nativeReceipt",actual);
    }
    @Override public JSONObject originalRequest(JSONObject binding)throws Exception {
        return tasks.originalProjectRequestForHost(binding);
    }
    @Override public void recordProcessInputReceipt(dev.pocketengine.features.local.runtime.ManagedProcessJobs.ProcessInputReceipt receipt)throws Exception {tasks.recordProjectProcessInput(receipt);}
    public ProjectOwnerTerminal ownerTerminals(){return new ProjectOwnerTerminal(tasks,local,controls);}
    @Override public JSONObject processInput(JSONObject binding,String executionId)throws Exception {
        RunControl original=current(binding);JSONObject claim=tasks.claimProjectProcessInput(binding,executionId,original);if(!claim.optBoolean("fresh"))return claim.getJSONObject("receipt");
        JSONObject row=claim.getJSONObject("service"),args=row.getJSONObject("arguments"),plan=ProjectBuildPlan.verified(args.getJSONObject("plan")),exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,args.getString("stepId"))),route=row.getJSONObject("receipt").getJSONObject("projectExecution");
        var reference=dev.pocketengine.features.local.runtime.RuntimeExecutionRef.fromHost(exact,route);var runtime=local.ownedServiceRuntimeForHost(plan.getString("runtimeId"));
        try{runtime.processInput(reference,original,binding.getString("operationId"),claim.getJSONObject("request"),()->{if(current(binding)!=original)throw new SecurityException("原PTY控制器已变化");tasks.checkProjectProcessInput(binding,executionId);});}
        catch(Exception uncertain){JSONObject observed=tasks.projectProcessInputReceipt(binding);if(!observed.optBoolean("nativeInputSettled"))observed.put("inputObservationError",uncertain.getClass().getSimpleName());return observed;}
        return tasks.projectProcessInputReceipt(binding);
    }
    @Override public JSONObject stopService(JSONObject binding,String executionId)throws Exception {
        RunControl original=current(binding);JSONObject target=tasks.claimProjectServiceStop(binding,executionId,original),args=target.getJSONObject("arguments"),plan=ProjectBuildPlan.verified(args.getJSONObject("plan"));
        JSONObject exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,args.getString("stepId"))),route=target.getJSONObject("receipt").getJSONObject("projectExecution"),targetBinding=new JSONObject();
        for(String key:new String[]{"taskId","runId","operationId","coordinatorEpoch"})targetBinding.put(key,route.get(key));
        JSONObject observed=local.projectReceiptForHost(exact,route,true);tasks.recordProjectOperation(targetBinding,exact,observed);return tasks.recordProjectServiceStop(binding,executionId);
    }
    @Override public dev.pocketengine.features.local.runtime.ProjectDevSession.Observed livePreview(JSONObject binding,String executionId,RunControl observation)throws Exception {
        RunControl original=current(binding);tasks.checkCapabilityCurrent(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),"project_live_preview");JSONObject row=tasks.projectDevServiceForPreview(binding,executionId),args=row.getJSONObject("arguments"),plan=ProjectBuildPlan.verified(args.getJSONObject("plan")),exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,args.getString("stepId"))),route=row.getJSONObject("receipt").getJSONObject("projectExecution");
        var ref=dev.pocketengine.features.local.runtime.RuntimeExecutionRef.fromHost(exact,route);var observed=local.ownedServiceRuntimeForHost(plan.getString("runtimeId")).probeLivePreview(ref,original,observation);if(current(binding)!=original)throw new SecurityException("活预览核对期间原控制器已变化");tasks.recordDevelopmentLivePreview(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),original,observed);return observed;
    }
    @Override public void checkLivePreview(dev.pocketengine.features.local.runtime.ProjectDevSession.Observed observed)throws Exception {
        JSONObject binding=observed.reference.binding();RunControl original=current(binding);tasks.checkCapabilityCurrent(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"),"project_live_preview");tasks.projectDevServiceForPreview(binding,observed.reference.operationId());local.ownedServiceRuntimeForHost(observed.reference.exact().getString("runtimeId")).requireLivePreview(observed.reference,original);
    }
    @Override public void checkInputService(JSONObject binding,String executionId)throws Exception {
        RunControl original=current(binding);JSONObject row=tasks.projectServiceForInputWait(binding,executionId),args=row.getJSONObject("arguments"),plan=ProjectBuildPlan.verified(args.getJSONObject("plan")),exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,args.getString("stepId")));
        if("pty".equals(exact.getJSONObject("payload").optString("terminalMode")))local.ownedServiceRuntimeForHost(plan.getString("runtimeId")).requirePtyServiceLive(dev.pocketengine.features.local.runtime.RuntimeExecutionRef.fromHost(exact,row.getJSONObject("receipt").getJSONObject("projectExecution")),original);else checkDevService(binding,executionId);
    }
    @Override public void checkDevService(JSONObject binding,String executionId)throws Exception {
        RunControl original=current(binding);JSONObject row=tasks.projectDevServiceForPreview(binding,executionId),args=row.getJSONObject("arguments"),plan=ProjectBuildPlan.verified(args.getJSONObject("plan")),exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,args.getString("stepId"))),route=row.getJSONObject("receipt").getJSONObject("projectExecution");local.ownedServiceRuntimeForHost(plan.getString("runtimeId")).requireLivePreview(dev.pocketengine.features.local.runtime.RuntimeExecutionRef.fromHost(exact,route),original);
    }
    /** Called only for the real non-exported Termux callback's native job event. */
    public void recordBackendReceipt(JSONObject backend)throws Exception {
        JSONObject link=backend.optJSONObject("projectExecution");if(link==null||!"native_host_operation".equals(link.optString("authority")))throw new SecurityException("不是原生项目回执");
        if(dev.pocketengine.features.skills.ManagedSkillBackend.INTENT.equals(link.optString("intent"))){tasks.recordManagedSkillBackendReceiptForHost(new JSONObject(backend.toString()));return;}
        if(ProjectGitImport.INTENT.equals(link.optString("intent"))){tasks.recordOwnerGitImportBackendForHost(new JSONObject(backend.toString()));return;}
        JSONObject binding=new JSONObject();for(String key:new String[]{"taskId","runId","operationId","coordinatorEpoch"})binding.put(key,link.get(key));
        JSONObject row=tasks.projectOperationForHost(binding),args=row.getJSONObject("arguments"),plan=ProjectBuildPlan.verified(args.getJSONObject("plan"));
        JSONObject exact=ProjectDevelopment.exact(plan,ProjectBuildPlan.step(plan,args.getString("stepId")));if(java.util.Set.of("freezeProjectPackage","collectProjectOutput").contains(exact.getString("command")))throw new SecurityException("文件成果回执只由原Local/catalog生产，不采纳外部终端回调");tasks.recordProjectOperation(binding,exact,new JSONObject(backend.toString()));tasks.finishOwnerGitFromReceipt(binding);tasks.finishOwnerLocalTerminalFromReceipt(binding);
    }
}
