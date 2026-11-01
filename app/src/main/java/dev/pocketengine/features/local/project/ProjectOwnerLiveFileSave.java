package dev.pocketengine.features.local.project;

import dev.pocketengine.core.*;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Native hand-edit bridge through the existing SAME task/run plan+journal+host+service lease.
 * It never grants ordinary writeFile an exception, cancels the model control, or replays a save. */
public final class ProjectOwnerLiveFileSave {
    public static final class Review {
        private final JSONObject binding,plan,args;private final ProjectFileDraft.SaveIntent original;
        private final RunControl control;private final String service;private final AtomicBoolean entered=new AtomicBoolean();private volatile String operation;
        private Review(JSONObject binding,JSONObject plan,JSONObject args,ProjectFileDraft.SaveIntent original,RunControl control,String service)throws Exception{this.binding=ProjectSources.copy(binding);this.plan=ProjectSources.copy(plan);this.args=ProjectSources.copy(args);this.original=original;this.control=control;this.service=service;}
        public JSONObject metadata()throws Exception{return new JSONObject().put("taskId",binding.getString("taskId")).put("runId",binding.getString("runId")).put("epoch",binding.getInt("coordinatorEpoch")).put("checkoutId",original.checkoutId).put("path",original.path).put("expectedHash",original.expectedHash).put("newSha256",ProjectSources.sha256(ProjectExactPatch.encode(original.text))).put("bytes",ProjectExactPatch.encode(original.text).length).put("serviceExecutionId",service).put("planDigest",plan.getString("planDigest")).put("sourceExecutionMayFollowSave",true).put("newRunCreated",false).put("ordinaryFileWritesAuthorized",false);}
    }
    public static final class Observation {
        private final JSONObject actual;public final ProjectFileDraft.SaveIntent originalIntent;
        private Observation(JSONObject actual,ProjectFileDraft.SaveIntent originalIntent)throws Exception{this.actual=ProjectSources.copy(actual);this.originalIntent=originalIntent;}
        public JSONObject json()throws Exception{return ProjectSources.copy(actual);}
        public boolean completed(){return "completed".equals(actual.optString("status"))&&actual.optBoolean("receiptReceived")&&originalIntent!=null;}
        public boolean terminal(){return actual.optBoolean("receiptReceived")&&actual.optString("status").matches("completed|failed|not_dispatched");}
    }
    private final TaskStore tasks;private final LocalModule local;private final TaskStoreProjectHost host;private final TaskStoreProjectHost.CurrentControl controls;
    public ProjectOwnerLiveFileSave(TaskStore tasks,LocalModule local,TaskStoreProjectHost host,TaskStoreProjectHost.CurrentControl controls){this.tasks=tasks;this.local=local;this.host=host;this.controls=controls;}
    private RunControl current(JSONObject binding)throws Exception{RunControl actual=controls.get(binding.getString("taskId"));if(actual==null||!actual.hasHostRun()||!actual.matchesHostRun(binding.getString("taskId"),binding.getString("runId"),binding.getInt("coordinatorEpoch"))||actual.isPaused())throw new SecurityException("原开发任务控制已停止、暂停或换代");actual.check();return actual;}
    public Review review(String task,ProjectFileDraft.SaveIntent intent,RunControl observation)throws Exception{
        observation.check();JSONObject selected=tasks.ownerProjectDevWatchForOwner(task,intent.checkoutId),binding=selected.getJSONObject("binding");String service=selected.getJSONObject("service").getString("operationId");RunControl original=current(binding);host.checkDevService(binding,service);
        JSONObject plan=ProjectBuildPlan.ownerLiveEdit(local,intent,service),ref=tasks.recordProjectPlan(task,binding.getString("runId"),binding.getInt("coordinatorEpoch"),original,plan),args=new JSONObject().put("planRef",ref.getString("planRef")).put("planDigest",ref.getString("planDigest")).put("stepId","edit");
        if(!tasks.projectLiveEditAllowed(task,binding.getString("runId"),binding.getInt("coordinatorEpoch"),args))throw new SecurityException("原启动批准未覆盖这份手工源码或其执行影响");observation.check();return new Review(binding,plan,args,intent,original,service);
    }
    public Observation execute(Review review,RunControl ownerObservation,java.util.function.Consumer<JSONObject> prepared)throws Exception{
        if(review==null)throw new SecurityException("请先核对本次原草稿与开发服务");if(!review.entered.compareAndSet(false,true)){if(review.operation==null)throw new IllegalStateException("原手工保存正在登记，不能重复提交");return query(locator(review));}
        ownerObservation.check();RunControl original=current(review.binding);if(original!=review.control)throw new SecurityException("原开发控制器已替换");JSONObject selected=tasks.ownerProjectDevWatchForOwner(review.binding.getString("taskId"),review.original.checkoutId);if(!review.service.equals(selected.getJSONObject("service").getString("operationId")))throw new SecurityException("原开发服务已替换");host.checkDevService(review.binding,review.service);
        String task=review.binding.getString("taskId"),run=review.binding.getString("runId");int epoch=review.binding.getInt("coordinatorEpoch");if(!tasks.projectLiveEditAllowed(task,run,epoch,review.args))throw new SecurityException("原批准已失效");RunJournal journal=tasks.journal(task,run,epoch,original);review.operation=journal.beginOperation("project_execute",review.args);JSONObject binding=locator(review);boolean dispatchEntered=false;
        try{tasks.event(task,new JSONObject().put("type","project_owner_file_save").put("operationId",review.operation).put("path",review.original.path).put("planDigest",review.plan.getString("planDigest")).put("nativeOwnerAction",true));prepared.accept(ProjectSources.copy(binding));ownerObservation.check();dispatchEntered=true;JSONObject result=local.handle("project.execute",new JSONObject().put("binding",binding).put("plan",review.plan).put("stepId","edit"));journal.finishOperation(review.operation,result);}
        catch(Exception failure){if(!dispatchEntered)journal.finishOperation(review.operation,new JSONObject().put("status","not_dispatched").put("accepted",false).put("receiptReceived",true).put("code","native_owner_editor_not_dispatched").put("message","原主人保存未进入执行；草稿保留"));else{try{journal.finishOperation(review.operation,new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL).put("code","owner_file_save_unknown").put("message","原保存已进入宿主但回执待核，只查询原操作"));}catch(Exception record){failure.addSuppressed(record);}}throw failure;}
        return query(binding);
    }
    private static JSONObject locator(Review review)throws Exception{return ProjectSources.copy(review.binding).put("operationId",review.operation).put("checkoutId",review.original.checkoutId).put("path",review.original.path);}
    public Observation query(JSONObject locator)throws Exception{
        String taskId=ProjectBuildPlan.identity(locator.getString("taskId"));JSONObject task=tasks.get(taskId,false),context=task==null?null:task.optJSONObject("developmentContext");if(task==null||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||!"assistant".equals(task.optString("kind"))||task.has("origin")||context==null)throw new SecurityException("原手工保存任务不再可读");
        JSONObject binding=new JSONObject().put("taskId",taskId).put("runId",ProjectBuildPlan.identity(locator.getString("runId"))).put("coordinatorEpoch",locator.getInt("coordinatorEpoch")).put("operationId",ProjectBuildPlan.identity(locator.getString("operationId"))),row=tasks.projectOperationForHost(binding),args=row.getJSONObject("arguments"),plan=ProjectBuildPlan.verified(args.getJSONObject("plan")),step=ProjectBuildPlan.step(plan,args.getString("stepId")),payload=step.getJSONObject("payload");
        if(!"writeFile".equals(step.optString("localCommand"))||!payload.has("liveEditServiceExecutionId")||!context.getString("checkoutId").equals(plan.getString("checkoutId"))||!locator.getString("checkoutId").equals(plan.getString("checkoutId"))||!locator.getString("path").equals(payload.getString("path")))throw new SecurityException("原操作不是此文件的受控源码保存");
        JSONObject envelope=row.optJSONObject("receipt"),nativeReceipt=envelope==null?null:envelope.optJSONObject("executionReceipt");if(nativeReceipt==null){String status=envelope!=null&&"receipt".equals(row.optString("state"))?envelope.optString("status","unknown"):"unknown";return new Observation(new JSONObject().put("status",status).put("receiptReceived",status.equals("not_dispatched")).put("operationId",binding.getString("operationId")).put("message","只核对原保存动作，不重放；普通文件写入权限没有扩大"),null);}
        JSONObject result=ProjectSources.copy(nativeReceipt);ProjectFileDraft.SaveIntent intent=null;
        if("receipt".equals(row.optString("state"))&&"completed".equals(nativeReceipt.optString("status"))&&nativeReceipt.optBoolean("receiptReceived")&&"local_edit".equals(nativeReceipt.optString("kind"))&&payload.getString("path").equals(nativeReceipt.optString("path"))&&ProjectSources.sha256(ProjectExactPatch.encode(payload.getString("content"))).equals(nativeReceipt.optString("sha256"))){intent=ProjectFileDraft.originalHostSave(plan.getString("projectId"),plan.getString("checkoutId"),payload.getString("path"),payload.getString("content"),payload.getString("expectedHash"));result.put("content",intent.text).put("path",intent.path).put("checkoutId",intent.checkoutId);}
        else if(result.optString("status").equals("completed")){result.put("status","unknown").put("receiptReceived",false);}
        return new Observation(result,intent);
    }
}
