package dev.pocketengine.features.remote;

import dev.pocketengine.store.TaskStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** One existing native owner page. JSON selects a displayed reference; it never issues a run. */
public final class NativeHarnessGatewayOwner {
    public interface Access {
        JSONObject view()throws Exception;
        void checkCurrent()throws Exception;
        JSONObject start(Approval approval)throws Exception;
    }
    public interface ParentAccess {
        JSONObject view()throws Exception;
        void checkCurrent()throws Exception;
        TaskStore.GatewayParentRunReview review();
        default void ownerWindowLost(){}
        JSONObject start(dev.pocketengine.features.RemoteOwnerWorkflows.GatewayParentRunApproval approval)throws Exception;
    }
    public interface Host {
        default void stopParent(String task,String run)throws Exception {throw new SecurityException("原App停止宿主未就绪");}
        default ParentAccess parentResume(String child)throws Exception {throw new SecurityException("原App网关恢复宿主未就绪");}
        Access capture(String parentTask,String parentRun,String remoteTask)throws Exception;
        JSONObject existing(String parentTask,String parentRun)throws Exception;
        JSONObject query(String parentTask,String parentRun,String lease)throws Exception;
        void revoke(String parentTask,String parentRun,String lease)throws Exception;
        TaskStore.HarnessGatewayConfiguration configuration(String parentTask,String parentRun,String child,String lease)throws Exception;
    }
    @FunctionalInterface public interface Fence {void check()throws Exception;}
    public static final class Page {
        private final String task;private final Fence fence;
        private final Map<String,JSONObject> targets=new LinkedHashMap<>();
        private JSONObject source,parent,review,status;private Access access;
        private String profileBinding,digest,blockedReason;private long reviewExpires;private boolean busy;private Approval approval;
        private Page(String task,Fence fence){this.task=task;this.fence=fence;}
    }
    /** One-shot native foreground approval. Its JSON/digest cannot construct another instance. */
    public static final class Approval {
        private final NativeHarnessGatewayOwner host;private final Page page;private final String value,digest;private final long expires;
        private Approval(NativeHarnessGatewayOwner host,Page page){this.host=host;this.page=page;value=page.review.toString();digest=page.digest;expires=page.reviewExpires;}
        public JSONObject view()throws Exception{return new JSONObject(value).put("reviewDigest",digest);}
        public boolean issuedFor(Access access){return page.access==access;}
        public void checkCurrent()throws Exception {host.current(page);if(page.approval!=this||System.currentTimeMillis()>=expires)throw new SecurityException("原前台网关批准已被替换、过期或取消");page.access.checkCurrent();}
    }
    private final TaskStore tasks;private final Supplier<RemoteModule> remote;private final Host host;
    public NativeHarnessGatewayOwner(TaskStore tasks,Supplier<RemoteModule> remote,Host host){this.tasks=tasks;this.remote=remote;this.host=host;}
    public boolean windowCurrent(Page page){try{page.fence.check();return true;}catch(Exception stale){return false;}}
    public ParentAccess parentResume(String child)throws Exception{return host.parentResume(child);}
    public void stopContinuation(String child)throws Exception {JSONObject parent=tasks.gatewayParentTarget(child);host.stopParent(parent.getString("parentTaskId"),parent.getString("parentRunId"));}
    /** Capture only the existing real parent control; this creates neither run nor gateway. */
    public Access captureContinuation(String child)throws Exception {JSONObject parent=tasks.gatewayParentTarget(child);Access access=host.capture(parent.getString("parentTaskId"),parent.getString("parentRunId"),child);access.checkCurrent();JSONObject budget=access.view().getJSONObject("budget");if(!budget.optBoolean("scopePresent")||budget.optInt("modelCallsRemaining")<1)throw new SecurityException("请先在原任务执行范围确认真实模型额度；网关不补造预算");return access;}
    public Page page(String task,Fence fence){return new Page(task,fence);}
    public static boolean command(String command){return Set.of("gateway.inspect","gateway.review","gateway.enable","gateway.query","gateway.revoke","gateway.configure").contains(command);}
    private JSONObject parent(Page page)throws Exception {return tasks.gatewayParentTarget(page.task);}
    private void current(Page page)throws Exception {
        page.fence.check();if(page.source==null||page.parent==null||!TaskStore.capabilityFingerprint(page.parent).equals(TaskStore.capabilityFingerprint(parent(page)))||!sourceDigest(page.source).equals(sourceDigest(tasks.remoteOwnerTarget(page.task))))throw new SecurityException("原父子任务或电脑目标已改变，请重新核对；没有派发");
        if(page.source.has("nativeGatewayProfileBinding")&&!page.profileBinding.equals(page.source.getString("nativeGatewayProfileBinding")))throw new SecurityException("本轮准备的原电脑配置代次已变化，不能改绑");
        if(!page.profileBinding.equals(remote.get().profileBindingHash(page.source.getString("profileId"))))throw new SecurityException("原电脑连接代次变化，请重新核对");
    }
    private static String sourceDigest(JSONObject source)throws Exception {JSONObject result=new JSONObject();for(String key:new String[]{"taskId","runId","coordinatorEpoch","profileId","serviceBase","jobOperationId","environmentId","worker"})result.put(key,source.get(key));return TaskStore.capabilityFingerprint(result);}
    public JSONObject handle(Page page,String command,JSONObject input)throws Exception {synchronized(page){if(page.busy)throw new IllegalStateException("正在核对此原网关，请等待本次结果");page.busy=true;}try{return action(page,command,input);}finally{synchronized(page){page.busy=false;}}}
    private JSONObject action(Page page,String command,JSONObject input)throws Exception {
        Set<String> allowed=switch(command){case "gateway.review"->Set.of("windowId","taskId","optionRef");case "gateway.enable"->Set.of("windowId","taskId","acceptedDigest");case "gateway.query","gateway.revoke"->Set.of("windowId","taskId","leaseId");default->Set.of("windowId","taskId");};
        for(var keys=input.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new SecurityException("本次网关不接收自定目标、权限、期限或凭据");
        page.fence.check();
        if(command.equals("gateway.inspect"))return inspect(page);
        if(!command.equals("gateway.revoke"))current(page);else if(page.parent==null||!TaskStore.capabilityFingerprint(page.parent).equals(TaskStore.capabilityFingerprint(parent(page))))throw new SecurityException("不是本页原私人父子关系");
        if(command.equals("gateway.review")){
            JSONObject target=page.targets.get(input.getString("optionRef"));if(target==null||page.access==null)throw new SecurityException("只可选择本页原电脑实际返回的目标");page.access.checkCurrent();
            JSONObject model=page.access.view();long expiry=Math.min(model.getLong("taskDeadline"),System.currentTimeMillis()+600_000L);if(expiry<=System.currentTimeMillis())throw new SecurityException("原任务期限已结束");
            page.review=new JSONObject().put("target",new JSONObject(target.toString())).put("remoteTaskId",page.task).put("parent",page.parent).put("model",model).put("expiresAt",expiry).put("purpose","RELAY").put("audience","EXTERNAL_RELAY").put("phoneExecution",false).put("toolExecution",false).put("nonce",UUID.randomUUID().toString());
            page.digest=TaskStore.capabilityFingerprint(page.review);page.reviewExpires=Math.min(expiry,System.currentTimeMillis()+60_000L);return snapshot(page);
        }
        if(command.equals("gateway.enable")){
            if(page.review==null||page.digest==null||!page.digest.equals(input.getString("acceptedDigest"))||System.currentTimeMillis()>=page.reviewExpires)throw new SecurityException("请重新核对屏幕上的准确范围；旧确认不启用服务");
            page.access.checkCurrent();JSONObject target=page.review.getJSONObject("target");long expires=page.review.getLong("expiresAt");
            // Read operator target generation again before consuming the one-shot review.
            JSONObject env=environment(page);if(!env.getString("bindingHash").equals(target.getString("environmentBinding"))||!hasTarget(env,target.getString("bindingId"),page.source.getString("worker")))throw new SecurityException("原环境或Harness目标已变化");current(page);page.access.checkCurrent();
            Approval approval=new Approval(this,page);page.approval=approval;page.digest=null;page.review=null; // An uncertain start is query-only, not a reusable approval.
            approval.checkCurrent();page.status=page.access.start(approval);return snapshot(page);
        }
        String lease=input.getString("leaseId");original(page,lease,command.equals("gateway.revoke"));
        if(command.equals("gateway.revoke")){host.revoke(page.parent.getString("parentTaskId"),page.parent.getString("parentRunId"),lease);page.status=host.existing(page.parent.getString("parentTaskId"),page.parent.getString("parentRunId"));page.digest=null;page.review=null;page.approval=null;}
        else if(command.equals("gateway.query")){page.status=host.query(page.parent.getString("parentTaskId"),page.parent.getString("parentRunId"),lease);}
        else throw new SecurityException("未知网关主人动作");
        return snapshot(page);
    }
    private JSONObject inspect(Page page)throws Exception {
        page.digest=null;page.review=null;page.targets.clear();page.access=null;page.status=null;page.blockedReason=null;page.approval=null;
        page.parent=parent(page);page.source=tasks.remoteOwnerTarget(page.task);page.profileBinding=remote.get().profileBindingHash(page.source.getString("profileId"));current(page);
        try{page.access=host.capture(page.parent.getString("parentTaskId"),page.parent.getString("parentRunId"),page.task);page.access.checkCurrent();}catch(SecurityException|IllegalStateException unavailable){page.blockedReason=unavailable.getMessage();}
        JSONObject existing=host.existing(page.parent.getString("parentTaskId"),page.parent.getString("parentRunId"));if(forChild(existing,page.task))page.status=existing;
        if(page.access!=null){JSONObject env=environment(page);String family=family(page.source.getString("worker"));JSONArray targets=env.optJSONArray("harnesses");for(int i=0;targets!=null&&i<targets.length()&&i<16;i++){JSONObject item=targets.getJSONObject(i);if(!family.equals(item.optString("family")))continue;String ref=UUID.randomUUID().toString();JSONObject target=new JSONObject().put("profileId",page.source.getString("profileId")).put("_profileBinding",page.profileBinding).put("serviceBase",page.source.getString("serviceBase")).put("environmentId",env.getString("id")).put("environmentBinding",env.getString("bindingHash")).put("bindingId",item.getString("bindingId")).put("profileDigest",page.access.view().getString("configurationDigest")).put("taskId",page.parent.getString("parentTaskId"));page.targets.put(ref,target);}}
        current(page);return snapshot(page);
    }
    private JSONObject environment(Page page)throws Exception {JSONObject input=new JSONObject().put("profileId",page.source.getString("profileId")).put("serviceBase",page.source.getString("serviceBase")).put("_profileBinding",page.profileBinding);JSONObject env=RemoteTaskRequest.environment(remote.get().handle("environments",input),page.source.getString("environmentId"),page.source.getString("worker"));current(page);return env;}
    private static String family(String worker){if(worker.equals("codex_app_server"))return "codex";if(worker.equals("claude"))return "claude";throw new SecurityException("此原任务不是受支持的Harness执行器");}
    private static boolean hasTarget(JSONObject env,String binding,String worker)throws Exception {JSONArray list=env.optJSONArray("harnesses");int matches=0;for(int i=0;list!=null&&i<list.length();i++)if(binding.equals(list.getJSONObject(i).optString("bindingId"))&&family(worker).equals(list.getJSONObject(i).optString("family")))matches++;return matches==1;}
    private JSONObject original(Page page,String lease,boolean stopping)throws Exception {JSONObject value=host.existing(page.parent.getString("parentTaskId"),page.parent.getString("parentRunId"));if(value==null||!lease.equals(value.optString("leaseId")))throw new SecurityException("此原生进程没有该准确旧租约；只能看原SQL，不能重建或补发");JSONObject bound=value.optJSONObject("configurationOwner");if(bound==null||!page.task.equals(bound.optString("taskId"))||!stopping&&(!page.source.getString("runId").equals(bound.optString("runId"))||page.source.getInt("coordinatorEpoch")!=bound.optInt("epoch")))throw new SecurityException("旧租约不属于本页原配置子任务代次");if(stopping)return value;JSONObject target=value.getJSONObject("target");for(String key:new String[]{"profileId","serviceBase","environmentId"})if(!target.get(key).equals(page.source.get(key)))throw new SecurityException("旧租约不属于本页原电脑目标");if(!page.profileBinding.equals(target.getString("_profileBinding")))throw new SecurityException("旧租约的原连接代次已变化");return value;}
    public TaskStore.HarnessGatewayConfiguration configuration(Page page,JSONObject input)throws Exception {if(input.length()!=3||!input.has("windowId")||!input.has("taskId")||!input.has("leaseId"))throw new SecurityException("配置入口只接受原租约引用");current(page);original(page,input.getString("leaseId"),false);return host.configuration(page.parent.getString("parentTaskId"),page.parent.getString("parentRunId"),page.task,input.getString("leaseId"));}
    private static boolean forChild(JSONObject value,String child){return value!=null&&value.optJSONObject("configurationOwner")!=null&&child.equals(value.optJSONObject("configurationOwner").optString("taskId"));}
    public void observe(Page page)throws Exception {page.fence.check();page.parent=parent(page);page.source=tasks.remoteOwnerTarget(page.task);page.profileBinding=remote.get().profileBindingHash(page.source.getString("profileId"));}
    /** Local-only discovery for the original page. No capture, target fetch, service or review. */
    public JSONObject summary(String child)throws Exception {JSONObject value=new JSONObject().put("automaticStart",false).put("phoneExecution",false).put("toolExecution",false);try{JSONObject cause=tasks.requirePrivateRemoteChild(child);value.put("parentGatewaySession",tasks.gatewayParentSessionForUi(cause.getString("parentTaskId")));JSONObject relation=tasks.gatewayParentTarget(child);value.put("parent",relation).put("records",tasks.originalHarnessGatewayRecords(relation.getString("parentTaskId"),relation.getString("parentRunId")));JSONObject live=host.existing(relation.getString("parentTaskId"),relation.getString("parentRunId"));if(forChild(live,child))value.put("status",live);}catch(Exception unavailable){value.put("blockedReason","这个远端任务没有可核对的私人原父执行；此入口不创建任务或授权");}return value;}
    public JSONObject snapshot(Page page)throws Exception {
        JSONObject value=new JSONObject().put("scope","EXISTING_PRIVATE_PARENT_TASK_ONLY").put("phoneExecution",false).put("toolExecution",false).put("configurationApplied",false).put("automaticStart",false);
        if(page.parent!=null){value.put("parentGatewaySession",tasks.gatewayParentSessionForUi(page.parent.getString("parentTaskId"))).put("parent",page.parent).put("records",tasks.originalHarnessGatewayRecords(page.parent.getString("parentTaskId"),page.parent.getString("parentRunId")));JSONObject live=host.existing(page.parent.getString("parentTaskId"),page.parent.getString("parentRunId"));if(forChild(live,page.task)){if(page.status!=null&&page.status.has("originalRequests")&&live.optString("leaseId").equals(page.status.optString("leaseId")))live.put("observation",page.status);page.status=live;}}
        if(page.blockedReason!=null)value.put("blockedReason",page.blockedReason);
        if(page.status!=null)value.put("status",new JSONObject(page.status.toString()));JSONArray options=new JSONArray();for(var entry:page.targets.entrySet()){JSONObject target=entry.getValue();options.put(new JSONObject().put("optionRef",entry.getKey()).put("bindingId",target.getString("bindingId")).put("environmentId",target.getString("environmentId")).put("profileId",target.getString("profileId")));}value.put("targets",options);
        try{current(page);if(page.review!=null&&page.digest!=null&&System.currentTimeMillis()<page.reviewExpires){page.access.checkCurrent();value.put("review",new JSONObject(page.review.toString())).put("reviewDigest",page.digest).put("reviewExpiresAt",page.reviewExpires);}}catch(Exception stale){value.put("reviewInvalid",true);}
        return value;
    }
}
