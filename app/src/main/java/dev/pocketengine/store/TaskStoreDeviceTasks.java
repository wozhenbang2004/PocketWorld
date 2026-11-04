package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.devices.DeviceExecutionAdmission;
import dev.pocketengine.features.devices.DeviceTaskEnvelope;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Device indices in the ORIGINAL TaskStore SQL authority. No database, Runner or retry loop of its own.
 * All task/run/operation facts are stored in the existing tables; device_inbound only resolves wire IDs. */
public final class TaskStoreDeviceTasks {
    private final java.util.Map<String,dev.pocketengine.features.teams.TeamPhoneHost.Approval> teamPhoneApprovals=new java.util.HashMap<>();
    private final java.util.Map<String,dev.pocketengine.features.teams.TeamCoordinator.PhoneCall> teamPhoneSources=new java.util.HashMap<>();

    /** Native review adds explicitly approved allocations to the original team's existing pool. */
    public void installTeamPhonePlan(dev.pocketengine.features.teams.TeamCoordinator.InitialBudgetPlan plan,
            dev.pocketengine.features.teams.TeamPhoneHost.Approval approval)throws Exception {
        plan.checkCurrent();JSONObject source=plan.view(),review=approval.view();String id=source.getString("id");
        synchronized(owner){db.beginTransaction();try{
            JSONObject parent=task(id);if(parent==null||!"teams".equals(parent.optString("kind"))||!"owner".equals(parent.optString("actorId"))||!"private".equals(parent.optString("spaceId"))||!parent.optString("parentTaskId").isEmpty()||teamPhoneApprovals.containsKey(id))throw new SecurityException("手机团队需本次原生主人启动，不能借父模型扩大权限");
            JSONObject pool=parent.getJSONObject("teamReservationPool");int tools=pool.getInt("toolOperationLimit"),models=pool.getInt("modelRequestLimit");
            JSONArray members=review.getJSONArray("members");for(int i=0;i<members.length();i++){var binding=approval.requireMember(members.getJSONObject(i).getString("memberId"));tools=Math.addExact(tools,binding.sourceToolBudget());models=Math.addExact(models,binding.modelBudget());}
            if(tools>400||models>128)throw new SecurityException("本次手机团队总预算超过原生上限，请减少成员额度");
            pool.put("toolOperationLimit",tools).put("modelRequestLimit",models);pool.getJSONObject("budgetSource").put("nativePhoneWorkPlan",true).put("phoneOwnerReview",review);
            parent.put("teamReservationPool",pool).put("teamPhoneScope",review).put("teamPhoneChildren",new JSONObject());access.write(parent);
            access.event(parent,new JSONObject().put("type","team_phone_scope_owner_confirmed").put("scope",review).put("autoRefill",false));
            plan.checkCurrent();teamPhoneApprovals.put(id,approval);db.setTransactionSuccessful();
        }finally{db.endTransaction();}}
    }
    private JSONObject teamPhoneParent(dev.pocketengine.features.teams.TeamCoordinator.PhoneCall call)throws Exception {
        call.checkRetained();call.approval().requireRun(call.teamRunId);JSONObject parent=task(call.teamRunId);
        if(parent==null||teamPhoneApprovals.get(call.teamRunId)!=call.approval()||parent.optBoolean("cancelRequested")||parent.optString("status").matches("cancelled|stopping|interrupted"))throw new SecurityException("原手机团队已停止或失去本次批准");return parent;
    }
    public JSONObject prepareTeamPhone(dev.pocketengine.features.teams.TeamCoordinator.PhoneCall call,
            dev.pocketengine.features.teams.TeamPhoneHost.Binding binding,RunControl original)throws Exception {
        synchronized(owner){db.beginTransaction();try{
            JSONObject parent=teamPhoneParent(call);if(binding!=call.approval().requireMember(call.memberId)||original==null||original.hasHostRun()||original.isCancelled()||original.isPaused())throw new SecurityException("需要本原成员控制器和准确范围");
            String key=call.memberId+":"+call.round;JSONObject children=parent.getJSONObject("teamPhoneChildren");
            if(children.has(key)){JSONObject known=task(children.getString(key));db.setTransactionSuccessful();return duplicate(known);}
            JSONObject child=binding.remote()?owner.createCapabilityTask(call.goal(),""):owner.create(call.goal(),"background");String id=child.getString("id"),mode=binding.remote()?"capabilities":"background";
            JSONObject scope=TaskExecutionScope.create(id,mode,1,binding.expiresAt(),binding.sourceToolBudget(),binding.modelBudget(),0,0,binding.toolNames(),true);
            child.put("origin",new JSONObject().put("kind","team_phone").put("teamRunId",call.teamRunId).put("memberId",call.memberId).put("round",call.round))
                    .put("executionScope",scope).put("teamPhoneScope",binding.view()).put("teamPhoneCaller",call.identity()).put("status","queued");access.write(child);
            children.put(key,id);parent.put("teamPhoneChildren",children);access.write(parent);teamPhoneSources.put(id,call);
            access.event(child,new JSONObject().put("type","team_phone_child_created").put("teamRunId",call.teamRunId).put("memberId",call.memberId));db.setTransactionSuccessful();return duplicate(child);
        }finally{db.endTransaction();}}
    }
    public JSONObject startTeamPhone(dev.pocketengine.features.teams.TeamCoordinator.PhoneCall call,String id,RunControl original)throws Exception {
        synchronized(owner){db.beginTransaction();try{
            JSONObject parent=teamPhoneParent(call),child=task(id);if(teamPhoneSources.get(id)!=call||!child.optString("runId").isEmpty()||!"team_phone".equals(child.getJSONObject("origin").optString("kind")))throw new SecurityException("原手机分工不可重复开始");
            var selected=call.approval().requireMember(call.memberId);child=owner.startTeamPhoneAttempt(id,child.getString("executionMode"),selected.sourceToolBudget());String run=child.getString("runId");int epoch=child.getInt("coordinatorEpoch");
            JSONObject pool=parent.getJSONObject("teamReservationPool"),identity=new JSONObject().put("teamRunId",call.teamRunId).put("childTaskId",id).put("childRunId",run).put("childEpoch",epoch).put("memberId",call.memberId).put("assignmentId",id);
            for(int i=0;i<selected.modelBudget();i++)dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,identity,"member","phone:"+id+":"+run+":model:"+i,1,0);
            for(int i=0;i<selected.sourceToolBudget();i++)dev.pocketengine.features.teams.TeamParentBudget.reserve(pool,identity,"member","phone:"+id+":"+run+":tool:"+i,0,1);
            parent.put("teamReservationPool",pool);access.write(parent);JSONObject accepted=duplicate(child);original.bindHostRun(id,run,epoch);db.setTransactionSuccessful();return accepted;
        }finally{db.endTransaction();}}
    }
    /** Does not call original.check(): this is itself the original RunControl authority callback. */
    public void requireTeamPhoneCurrent(dev.pocketengine.features.teams.TeamCoordinator.PhoneCall call,String id,RunControl original)throws Exception {
        synchronized(owner){teamPhoneParent(call);JSONObject child=task(id);if(teamPhoneSources.get(id)!=call||original==null||original.isCancelled()||!original.matchesHostRun(id,child.optString("runId"),child.optInt("coordinatorEpoch"))||child.optBoolean("cancelRequested"))throw new SecurityException("原手机成员执行已失效");
            JSONObject scope=child.getJSONObject("executionScope"),permission=access.grant(child.getString("runId"));
            if(TaskExecutionScope.effectiveDeadline(scope)<=System.currentTimeMillis()||permission==null||permission.optLong("expiresAt")<=System.currentTimeMillis()||!"owner".equals(permission.optString("principalId"))||!"private".equals(permission.optString("spaceId")))throw new SecurityException("原手机成员预算或期限已失效");
            if(!"capabilities".equals(child.getString("executionMode"))&&!child.optString("status").matches("completed|failed|cancelled|interrupted|awaiting_user|awaiting_approval|needs_confirmation|blocked_unknown|reconciling"))owner.requirePhoneLeaseCurrent(id,child.getString("runId"),child.getInt("coordinatorEpoch"));
        }
    }
    public void recordTeamPhoneOperation(dev.pocketengine.features.teams.TeamCoordinator.PhoneCall call,String id,RunControl original,String operation)throws Exception {
        synchronized(owner){requireTeamPhoneCurrent(call,id,original);JSONObject child=task(id);if(child.has("teamPhoneDeviceOperation"))throw new SecurityException("已有原设备操作，只查询原 ID");
            try(Cursor row=db.rawQuery("SELECT tool FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{id,child.getString("runId"),operation})){if(!row.moveToFirst()||!"device_delegate_goal".equals(row.getString(0)))throw new SecurityException("需要原TaskStore准备的分派操作");}
            child.put("teamPhoneDeviceOperation",operation);access.write(child);
        }
    }
    private final java.util.Map<String,GoalModelApproval> goalModels=new java.util.HashMap<>();
    /** Reducing authority only. Management permission comes from the original SQL audience,
     * not a source proof or control lease; it never authorizes pause/respond/resume or content. */
    public JSONObject cancelAcceptedGoal(dev.pocketengine.features.devices.PairingCoordinator.OperationManagementPermission permission)throws Exception{
        permission.checkCurrent(System.currentTimeMillis());DeviceTaskEnvelope request=permission.envelope();JSONObject args=request.arguments(),audience=permission.audience();
        synchronized(owner){db.beginTransaction();try{String remote=args.getString("operationId"),id,run;JSONObject accepted;long now=System.currentTimeMillis();
            try(Cursor row=db.rawQuery("SELECT local_task_id,arguments_digest,envelope_json,method FROM device_inbound WHERE caller_device_id=? AND remote_operation_id=?",new String[]{permission.callerDeviceId(),remote})){if(!row.moveToFirst()||!"task.submitGoal".equals(row.getString(3))||!args.getString("argumentsDigest").equals(row.getString(1)))throw new SecurityException("只能取消原已接受Goal提交，不取得手机工具会话或其他任务控制权");id=row.getString(0);accepted=new JSONObject(row.getString(2));}
            JSONObject current=task(id);requireDevice(current);if(!"GOAL".equals(current.optString("deviceExecutionKind"))||!id.equals(dev.pocketengine.features.devices.DeviceProtocol.receiverTaskId(accepted))||!permission.callerDeviceId().equals(current.getJSONObject("origin").getString("callerDeviceId"))||!accepted.getString("operationId").equals(remote)||!id.equals(audience.optString("taskId"))||!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(accepted.getJSONObject("resourceBinding"),current.getJSONObject("deviceExecutionScope").getJSONObject("resourceBinding")))throw new SecurityException("原Goal身份/资源已改变，不能猜新任务或epoch");run=current.optString("runId");if(!run.isEmpty()&&(!run.equals(current.optString("deviceAcceptedRunId"))||current.getInt("coordinatorEpoch")!=current.optInt("deviceAcceptedExecutorEpoch")))throw new SecurityException("当前run不是原accepted Goal真实首次绑定，不能猜新epoch");if(!run.equals(audience.optString("runId"))||!run.isEmpty()&&current.getInt("coordinatorEpoch")!=audience.optInt("executorEpoch"))throw new SecurityException("原Goal真实run已变化，只查询原提交，不猜代次");
            Live retained=run.isEmpty()?null:live.get(run);if(retained!=null&&(!retained.original.matchesHostRun(id,run,current.getInt("coordinatorEpoch"))||!remote.equals(retained.admission.request().operationId())))throw new SecurityException("当前宿主不是同accepted Goal执行，未触新任务");
            String local=localId("device-management",permission.callerDeviceId()+":"+request.operationId());try(Cursor known=db.rawQuery("SELECT arguments_digest,local_task_id FROM device_inbound WHERE caller_device_id=? AND remote_operation_id=?",new String[]{permission.callerDeviceId(),request.operationId()})){if(known.moveToFirst()){if(!request.argumentsDigest().equals(known.getString(0))||!id.equals(known.getString(1)))throw new SecurityException("原cancel ID不能换目标/digest");db.setTransactionSuccessful();return managementState(id,remote,args.getString("argumentsDigest")).put("knownOperation",true);}}
            boolean terminal=current.optString("status").matches("completed|cancelled|failed")&&!owner.hasUnresolved(id)&&!hasUnstoppedChildOperations(id,run)&&retained==null;
            if(!terminal){current.put("cancelRequested",true);access.write(current);if(run.isEmpty())owner.finish(id,"cancelled","原已接受Goal在开始前已取消；没有模型或工具派发",new JSONObject().put("nativeOperationCancelledBeforeStart",true));else owner.requestCancel(id,run);}
            ContentValues operation=new ContentValues();operation.put("operation_id",local);operation.put("task_id",id);operation.put("run_id",run);operation.put("tool","native_device_operation_cancel");operation.put("arguments",args.toString());operation.put("state","receipt");operation.put("receipt",new JSONObject().put("managementOnly",true).toString());operation.put("created_at",now);db.insertOrThrow("operations",null,operation);
            ContentValues inbound=new ContentValues();inbound.put("caller_device_id",permission.callerDeviceId());inbound.put("remote_operation_id",request.operationId());inbound.put("arguments_digest",request.argumentsDigest());inbound.put("local_task_id",id);inbound.put("local_operation_id",local);inbound.put("method",request.method());inbound.put("grant_id",request.grantId());inbound.put("authorization_epoch",request.authorizationEpoch());inbound.put("envelope_json",request.json().toString());inbound.put("accepted_at",now);db.insertOrThrow("device_inbound",null,inbound);permission.checkCurrent(System.currentTimeMillis());db.setTransactionSuccessful();return managementState(id,remote,args.getString("argumentsDigest")).put("knownOperation",false);
        }finally{db.endTransaction();}}
    }
    private JSONObject managementState(String id,String originalOperation,String digest)throws Exception{
        JSONObject current=task(id);String run=current.optString("runId");boolean stopped=current.optString("status").matches("completed|cancelled|failed")&&!owner.hasUnresolved(id)&&!hasUnstoppedChildOperations(id,run)&&!live.containsKey(run);return new JSONObject().put("localTaskId",id).put("runId",run).put("executorEpoch",current.optInt("coordinatorEpoch")).put("requested",current.optBoolean("cancelRequested")).put("stopped",stopped).put("targetOperationId",originalOperation).put("targetArgumentsDigest",digest);
    }
    public JSONObject operationManagementReceipt(DeviceTaskEnvelope request,String device,String instance,JSONObject actual)throws Exception{
        synchronized(owner){try(Cursor row=db.rawQuery("SELECT local_task_id,envelope_json,arguments_digest,accepted_at FROM device_inbound WHERE caller_device_id=? AND remote_operation_id=? AND method='operation.cancel'",new String[]{request.callerDeviceId(),request.operationId()})){if(!row.moveToFirst()||!request.argumentsDigest().equals(row.getString(2)))throw new SecurityException("管理接受尚未原SQL持久化");String id=row.getString(0);JSONObject audience=historyAudience(request,"operation",request.operationId(),id,new JSONObject(row.getString(1)),row.getLong(3)),facts=new JSONObject().put("acceptedAt",row.getLong(3)).put("audience",audience).put("localTaskId",id).put("cancellation",new JSONObject().put("targetOperationId",actual.getString("targetOperationId")).put("targetArgumentsDigest",actual.getString("targetArgumentsDigest")).put("requested",actual.getBoolean("requested")).put("stopped",actual.getBoolean("stopped")));if(audience.has("runId"))facts.put("runId",audience.getString("runId")).put("executorEpoch",audience.getLong("executorEpoch"));return dev.pocketengine.features.devices.DeviceProtocol.operationReceipt(device,instance,request.operationId(),request.argumentsDigest(),actual.getBoolean("stopped")?"succeeded":"cancel_requested",true,"reported",new JSONObject().put("managementOnly",true),facts);}}
    }

    /** Native B-owner review. It cannot be deserialized, minted by a peer, or moved to another DB. */
    public static final class GoalModelReview {
        private final TaskStoreDeviceTasks issuer;private final String id,reviewId,scope;private final long revision;
        private final DeviceExecutionAdmission admission;private final dev.pocketengine.core.ModelConfig model;
        private boolean confirmed;private String policyKey="",policyDigest="";
        private GoalModelReview(TaskStoreDeviceTasks issuer,String id,long revision,DeviceExecutionAdmission admission,dev.pocketengine.core.ModelConfig model)throws Exception{this.issuer=issuer;this.id=id;this.revision=revision;this.admission=admission;this.model=model;reviewId=UUID.randomUUID().toString();scope=TaskStore.capabilityFingerprint(admission.scope());}
        public JSONObject view()throws Exception{return new JSONObject().put("taskId",id).put("reviewId",reviewId).put("revision",revision).put("model",model.model).put("provider",model.provider).put("source",model.usesAccount()?"local_account":"local_api").put("toolBudget",admission.toolBudget()).put("modelBudget",admission.modelBudget()).put("deadline",admission.deadline()).put("policyToolBudget",admission.grantToolBudget()).put("policyModelBudget",admission.grantModelBudget()).put("policyExpiresAt",admission.nativeGrantDeadline()).put("resourceBinding",admission.request().resourceBinding()).put("origin",admission.origin()).put("executionAuthorized",false).put("privateMemoryProvided",false);}
    }
    /** Owner's exact selected local model + original accepted goal. Holding this is not owner identity. */
    public static final class GoalModelApproval {
        private final GoalModelReview review;private boolean consumed;private RunControl original;private String run;private int epoch;
        private GoalModelApproval(GoalModelReview review){this.review=review;}
        public String taskId(){return review.id;}
        public dev.pocketengine.core.ModelConfig capturedModel(){return review.model;}
        public DeviceExecutionAdmission deviceAdmission(){return review.admission;}
        public JSONObject publicView()throws Exception{return review.view().put("executionAuthorized",true).put("ownerApprovedLocalModel",true).put("pairedDeviceIdentityRetained",true);}
        public void checkCurrent(RunControl actual)throws Exception{review.issuer.checkGoalModel(this,actual);}
        public boolean matchesOriginal(RunControl actual){return consumed&&original==actual&&actual!=null&&actual.matchesHostRun(review.id,run,epoch);}
    }
    /** Read a B-local SettingsStore selection ONLY in response to its native owner review action. */
    public GoalModelReview reviewGoalModel(String id,long expectedRevision,DeviceExecutionAdmission admission,dev.pocketengine.core.ModelConfig selected)throws Exception{
        admission.checkCurrent();java.util.Objects.requireNonNull(selected);
        synchronized(owner){JSONObject current=task(id);requireDevice(current);requireSameSession(current,admission);
            if(admission.kind()!=DeviceExecutionAdmission.Kind.GOAL||!current.optString("runId").isEmpty()||!current.optString("status").matches("queued|awaiting_user")||current.optLong("deviceStateRevision")!=expectedRevision||current.optBoolean("cancelRequested"))throw new SecurityException("原设备目标或本机待审版本已变化");
            return new GoalModelReview(this,id,expectedRevision,admission,selected);
        }
    }
    /** Native local confirmation callback, never an ingress method or arbitrary JSON boolean. */
    public GoalModelApproval confirmGoalModel(GoalModelReview review)throws Exception{
        if(review==null||review.issuer!=this)throw new SecurityException("模型审阅不属于本原TaskStore");review.admission.checkCurrent();
        synchronized(owner){JSONObject current=task(review.id);requireDevice(current);requireSameSession(current,review.admission);
            if(review.confirmed||!current.optString("runId").isEmpty()||current.optLong("deviceStateRevision")!=review.revision||!review.scope.equals(TaskStore.capabilityFingerprint(review.admission.scope()))||current.optBoolean("cancelRequested"))throw new SecurityException("旧模型审阅不能重复或替换原目标");
            review.confirmed=true;return new GoalModelApproval(review);
        }
    }
    private static String modelPolicyKey(DeviceExecutionAdmission admission)throws Exception{
        return "device_goal_model_policy:"+TaskStore.capabilityFingerprint(new JSONObject().put("callerDeviceId",admission.request().callerDeviceId()).put("originDeviceId",admission.request().originDeviceId()).put("targetDeviceId",admission.request().targetDeviceId()).put("grantId",admission.request().grantId()).put("resourceBinding",admission.request().resourceBinding()));
    }
    private JSONObject modelPolicy(String key)throws Exception{try(Cursor row=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{key})){return row.moveToFirst()?new JSONObject(row.getString(0)):null;}}
    /** Public native route reference only; never a key, model grant, or permission constructor. */
    public JSONObject goalModelPolicy(DeviceExecutionAdmission admission)throws Exception{
        admission.checkCurrent();synchronized(owner){JSONObject policy=modelPolicy(modelPolicyKey(admission));if(policy==null)return null;requireModelPolicyScope(policy,admission);return duplicate(policy);}
    }
    private void requireModelPolicyScope(JSONObject policy,DeviceExecutionAdmission admission)throws Exception{
        if(!"pocketworld.device-goal-model-policy.v1".equals(policy.optString("format"))||!admission.nativeGrantFingerprint().equals(policy.optString("grantFingerprint"))
                ||admission.request().authorizationEpoch()!=policy.optLong("authorizationEpoch")||policy.optLong("expiresAt")<=System.currentTimeMillis()
                ||admission.deadline()>policy.optLong("expiresAt")||admission.toolBudget()>policy.optLong("toolBudget")||admission.modelBudget()>policy.optLong("modelBudget")
                ||!TaskStore.capabilityFingerprint(new JSONObject().put("resourceBinding",admission.request().resourceBinding()).put("toolNames",admission.toolNames())).equals(policy.optString("scopeDigest")))throw new SecurityException("B本机模型策略、原PairGrant版本或准确范围已变化；需本机重新审阅，未改用默认模型");
    }
    /** B owner's explicit one-time policy choice: save public profile identity in ORIGINAL meta table.
     * Receiver grant lifecycle accounting still owns all tools/models; this is not a budget store. */
    public void persistGoalModelPolicy(GoalModelApproval approval)throws Exception{
        if(approval==null||approval.review.issuer!=this||!approval.review.confirmed||approval.consumed)throw new SecurityException("必须先有本机owner准确模型确认");
        GoalModelReview review=approval.review;review.admission.checkCurrent();var model=review.model;
        if(model.usesAccount())throw new SecurityException("CHATGPT_DEVICE_GOAL_AUDIENCE_UNPROVEN");if(model.capabilityBinding==null)throw new SecurityException("持久模型策略须选择原生API profile，不能存凭据或旧默认配置");
        JSONObject policy=new JSONObject().put("format","pocketworld.device-goal-model-policy.v1").put("policyId",review.reviewId).put("grantId",review.admission.request().grantId()).put("authorizationEpoch",review.admission.request().authorizationEpoch()).put("grantFingerprint",review.admission.nativeGrantFingerprint())
                .put("originDeviceId",review.admission.request().originDeviceId()).put("callerDeviceId",review.admission.request().callerDeviceId()).put("targetDeviceId",review.admission.request().targetDeviceId()).put("resourceBinding",review.admission.request().resourceBinding()).put("toolNames",review.admission.toolNames())
                .put("expiresAt",review.admission.nativeGrantDeadline()).put("toolBudget",review.admission.grantToolBudget()).put("modelBudget",review.admission.grantModelBudget()).put("scopeDigest",TaskStore.capabilityFingerprint(new JSONObject().put("resourceBinding",review.admission.request().resourceBinding()).put("toolNames",review.admission.toolNames())))
                .put("modelBinding",model.capabilityBinding.toJson()).put("model",model.model).put("configurationDigest",dev.pocketengine.runtime.models.ModelRoutePlan.configurationDigest(model));
        synchronized(owner){String key=modelPolicyKey(review.admission);ContentValues row=new ContentValues();row.put("key",key);row.put("value",policy.toString());if(db.update("meta",row,"key=?",new String[]{key})==0)db.insertOrThrow("meta",null,row);review.policyKey=key;review.policyDigest=TaskStore.capabilityFingerprint(policy);}
    }
    /** Auto-admit ONLY against that already persisted B-native policy and exact enabled profile. */
    public GoalModelApproval authorizeGoalModelPolicy(String id,DeviceExecutionAdmission admission,dev.pocketengine.core.ModelConfig selected)throws Exception{
        admission.checkCurrent();synchronized(owner){JSONObject current=task(id);requireDevice(current);requireSameSession(current,admission);JSONObject policy=modelPolicy(modelPolicyKey(admission));if(policy==null)throw new SecurityException("没有B本机持久模型策略");requireModelPolicyScope(policy,admission);
            if(selected.usesAccount()||selected.capabilityBinding==null||!dev.pocketengine.features.devices.DeviceProtocol.canonical(selected.capabilityBinding.toJson()).equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(policy.getJSONObject("modelBinding")))||!selected.model.equals(policy.getString("model"))||!dev.pocketengine.runtime.models.ModelRoutePlan.configurationDigest(selected).equals(policy.getString("configurationDigest")))throw new SecurityException("本机策略的模型或凭据版本变化，未换用默认");
            GoalModelReview review=reviewGoalModel(id,current.getLong("deviceStateRevision"),admission,selected);review.confirmed=true;review.policyKey=modelPolicyKey(admission);review.policyDigest=TaskStore.capabilityFingerprint(policy);return new GoalModelApproval(review);
        }
    }
    private void checkGoalModel(GoalModelApproval proof,RunControl actual)throws Exception{checkGoalModel(proof,actual,false);}
    private void checkGoalModel(GoalModelApproval proof,RunControl actual,boolean supervisionOnly)throws Exception{
        proof.review.admission.checkCurrent();synchronized(owner){
            if(proof.review.issuer!=this||!proof.matchesOriginal(actual)||goalModels.get(proof.run)!=proof||actual.isCancelled())throw new SecurityException("没有该本机owner选定模型的原生准入");
            if(!proof.review.policyKey.isEmpty()){JSONObject policy=modelPolicy(proof.review.policyKey);if(policy==null||!proof.review.policyDigest.equals(TaskStore.capabilityFingerprint(policy)))throw new SecurityException("B本机模型策略已撤销或换版");requireModelPolicyScope(policy,proof.review.admission);}
            JSONObject current=task(proof.review.id);requireDevice(current);if(!proof.run.equals(current.optString("runId"))||proof.epoch!=current.optInt("coordinatorEpoch")||current.optBoolean("cancelRequested")||!(current.optString("status").matches("preparing|running|pausing|paused|awaiting_user")||supervisionOnly&&"needs_confirmation".equals(current.optString("status"))))throw new SecurityException("原设备模型任务已停止或换代");
        }
    }
    /** Read-only service supervision of the original native GoalSession. The Runner
     * publishes its result before returning its Finished proof; that short closing
     * phase must retain the old deadline without reopening any model/effect fence. */
    public long goalSupervisionDeadline(dev.pocketengine.features.devices.DeviceGoalSession session)throws Exception{
        if(session==null)throw new SecurityException("没有原设备目标会话");
        RunControl original=session.originalControl();GoalModelApproval approved=session.modelApproval();
        checkGoalModel(approved,original,true);
        synchronized(owner){
            JSONObject current=task(session.taskId());requireDevice(current);requireSameSession(current,session.admission());
            String run=current.getString("runId");int epoch=current.getInt("coordinatorEpoch");Live active=live.get(run);
            if(active==null||active.original!=original||active.admission!=session.admission()||goalModels.get(run)!=approved
                    ||!approved.matchesOriginal(original)||!original.matchesHostRun(session.taskId(),run,epoch)
                    ||original.isCancelled()||current.optBoolean("cancelRequested"))throw new SecurityException("原设备目标监管身份已变化");
            if(!"needs_confirmation".equals(current.optString("status")))return owner.taskExecutionDeadline(session.taskId(),run,epoch);
            try(Cursor row=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{session.taskId(),run,String.valueOf(epoch)})){
                if(!row.moveToFirst()||!"running".equals(row.getString(0)))throw new SecurityException("原设备目标run已结束");
            }
            long deadline=Math.min(access.grant(run).getLong("expiresAt"),session.admission().deadline());
            if(deadline<1)throw new SecurityException("没有原设备目标期限");return deadline;
        }
    }
    /** Explicit local owner revocation; a current run notices it at the next model/effect fence. */
    public void revokeGoalModelPolicy(DeviceExecutionAdmission admission)throws Exception{synchronized(owner){db.delete("meta","key=?",new String[]{modelPolicyKey(admission)});}}
    /** Native scoped health input only. Root resolves these public profile references with SettingsStore;
     * never serialize this owner-only object wholesale into a peer's capability manifest. */
    public JSONObject goalModelPolicyStatus(dev.pocketengine.features.devices.PairingCoordinator.Permission permission)throws Exception{
        permission.checkCurrent(System.currentTimeMillis());return goalModelPolicyStatus(permission.callerDeviceId(),permission.targetDeviceId());
    }
    public JSONObject goalModelPolicyStatus(dev.pocketengine.features.devices.PairingCoordinator.DiscoveryPermission permission)throws Exception{permission.checkCurrent(System.currentTimeMillis());return goalModelPolicyStatus(permission.callerDeviceId(),permission.targetDeviceId());}
    private JSONObject goalModelPolicyStatus(String callerDeviceId,String targetDeviceId)throws Exception{
        synchronized(owner){JSONArray policies=new JSONArray();boolean truncated=false;int scanned=0;
            try(Cursor rows=db.rawQuery("SELECT value FROM meta WHERE key GLOB 'device_goal_model_policy:*' ORDER BY rowid DESC LIMIT 129",null)){while(rows.moveToNext()){
                if(++scanned>128){truncated=true;break;}JSONObject policy=new JSONObject(rows.getString(0));if(!callerDeviceId.equals(policy.optString("callerDeviceId"))||!targetDeviceId.equals(policy.optString("targetDeviceId"))||policy.optLong("expiresAt")<=System.currentTimeMillis())continue;
                JSONObject grant=null;try(Cursor row=db.rawQuery("SELECT body_json,authorization_epoch,revoked FROM device_grants WHERE grant_id=? AND peer_device_id=? AND local_device_id=?",new String[]{policy.getString("grantId"),callerDeviceId,targetDeviceId})){if(row.moveToFirst())grant=new JSONObject(row.getString(0)).put("authorizationEpoch",row.getLong(1)).put("revoked",row.getInt(2)!=0);}
                if(grant==null||grant.optBoolean("revoked")||grant.optLong("expiresAt")<=System.currentTimeMillis()||!policy.getString("grantFingerprint").equals(dev.pocketengine.features.devices.DeviceProtocol.sha256(dev.pocketengine.features.devices.DeviceProtocol.canonicalBytes(grant))))continue;
                if(policies.length()==8){truncated=true;break;}policies.put(policy);
            }}return new JSONObject().put("callerDeviceId",callerDeviceId).put("configured",policies.length()>0).put("policies",policies).put("truncated",truncated).put("executionAuthorized",false);
        }
    }
    public interface Access {
        JSONObject read(String taskId) throws Exception;
        void write(JSONObject task) throws Exception;
        void event(JSONObject task, JSONObject event) throws Exception;
        JSONObject grant(String runId) throws Exception;
        void checkRun(String taskId, String runId, int epoch, RunControl original, boolean dispatch) throws Exception;
    }
    private final SQLiteDatabase db;
    private final TaskStore owner;
    private final Access access;
    private final Map<String, Live> live = new HashMap<>();
    private record Live(RunControl original, DeviceExecutionAdmission admission) { }
    /** A native source-range review; UI choices and persisted JSON cannot construct this proof. */
    public static final class SourceScopeReview {
        private final TaskStoreDeviceTasks issuer;private final dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.Window window;private final java.util.List<dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.RouteSelection> selections;private final JSONObject proposed;private boolean confirmed;
        private SourceScopeReview(TaskStoreDeviceTasks issuer,dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.Window window,java.util.List<dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.RouteSelection> selections,JSONObject proposed){this.issuer=issuer;this.window=window;this.selections=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(selections));this.proposed=proposed;}
        public JSONObject view()throws Exception{return duplicate(proposed).put("taskId",window.taskId()).put("executionAuthorized",false).put("budgetReserved",false).put("sharedArtifactCount",sourceMaterialCount(proposed)).put("ambientMemoryProvided",false).put("retention","task_only");}
    }
    private static int sourceMaterialCount(JSONObject policy){if(policy==null)return 0;int count=0;JSONArray routes=policy.optJSONArray("routes");for(int i=0;routes!=null&&i<routes.length();i++){JSONArray files=routes.optJSONObject(i).optJSONArray("files");if(files!=null)count+=files.length();JSONArray projects=routes.optJSONObject(i).optJSONArray("projects");if(projects!=null)count+=projects.length();}return count;}
    /** Candidate metadata comes only from settled original private project operations. */
    public JSONArray projectPackageCandidatesForOwner(String id)throws Exception{
        synchronized(owner){sourceOwnerTask(id);JSONArray out=new JSONArray();try(Cursor rows=db.rawQuery("SELECT task_id,operation_id FROM operations WHERE tool='project_execute' AND state='receipt' ORDER BY created_at DESC LIMIT 200",null)){while(rows.moveToNext())try{out.put(owner.projectPackageEvidenceForOwner(rows.getString(0),rows.getString(1)));}catch(Exception notPrivatePackage){ }}return out;}
    }
    public static final class ProjectImportReview {
        private final TaskStoreDeviceTasks issuer;private final dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.Window window;private final JSONObject proposed;private boolean confirmed;
        private ProjectImportReview(TaskStoreDeviceTasks issuer,dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.Window window,JSONObject proposed){this.issuer=issuer;this.window=window;this.proposed=proposed;}
        public JSONObject view()throws Exception{return duplicate(proposed);}
    }
    public JSONArray pendingProjectImportsForOwner(String id)throws Exception{
        synchronized(owner){JSONObject task=sourceOwnerTask(id);JSONArray out=new JSONArray();try(Cursor rows=db.rawQuery("SELECT operation_id FROM operations WHERE task_id=? AND tool='device_invoke_tool' AND state='receipt' ORDER BY created_at DESC LIMIT 200",new String[]{id})){while(rows.moveToNext())try{JSONObject item=pendingProjectImport(id,rows.getString(0));JSONObject approvals=task.optJSONObject("deviceProjectImportApprovals");JSONObject approved=approvals==null?null:approvals.optJSONObject(rows.getString(0));if(approved==null||!approved.optString("scopeDigest").equals(TaskStore.capabilityFingerprint(task.getJSONObject("deviceSourceScope"))))out.put(item);}catch(SecurityException notPending){ }}return out;}
    }
    private JSONObject pendingProjectImport(String id,String operation)throws Exception{
        JSONObject current=sourceOwnerTask(id),scope=current.optJSONObject("deviceSourceScope");if(scope==null)throw new SecurityException("没有原owner设备范围");JSONObject held=childAllocationForOperation(id,operation),wire=held.getJSONObject("boundWire"),invocation=wire.getJSONObject("arguments");if(!"workspace.project.receive".equals(invocation.optString("toolName")))throw new SecurityException("不是原项目包接收操作");
        JSONObject descriptor=invocation.getJSONObject("arguments").getJSONObject("artifact"),route=null,selected=null;JSONArray routes=scope.getJSONArray("routes");for(int i=0;i<routes.length();i++){JSONObject candidate=routes.getJSONObject(i);if(candidate.getString("targetDeviceId").equals(wire.getString("targetDeviceId"))&&candidate.getString("grantId").equals(wire.getString("grantId"))&&candidate.getLong("authorizationEpoch")==wire.getLong("authorizationEpoch")&&candidate.getString("instanceId").equals(wire.getString("instanceId"))&&dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(candidate.getJSONObject("resourceBinding"),wire.getJSONObject("resourceBinding"))){JSONArray projects=candidate.optJSONArray("projects");for(int n=0;projects!=null&&n<projects.length();n++)if(projects.getJSONObject(n).getString("id").equals(descriptor.getString("artifactId"))){route=candidate;selected=projects.getJSONObject(n);}}}if(selected==null)throw new SecurityException("原项目选材范围已撤下");
        JSONObject verified;try(Cursor row=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND operation_id=? AND state='receipt'",new String[]{id,operation})){if(!row.moveToFirst()||row.isNull(0))throw new SecurityException("没有原项目接收回执");JSONObject binding=new JSONObject(row.getString(0)).optJSONObject("nativeDeviceAcceptedBinding");if(binding==null)throw new SecurityException("没有原native接受证明");verified=binding.getJSONObject("verifiedReceipt");}
        JSONObject payload=verified.getJSONObject("payload"),args=invocation.getJSONObject("arguments");if(!"awaiting_import_review".equals(payload.optString("phase"))||!Boolean.FALSE.equals(payload.opt("delivered"))||!selected.getString("manifestDigest").equals(payload.optString("manifestDigest"))||!selected.getString("sha256").equals(payload.optString("sha256"))||selected.getLong("size")!=payload.optLong("bytes",-1)||!descriptor.getString("transferId").equals(payload.optString("transferId"))||!selected.getString("path").equals(payload.optString("path"))||!selected.getString("path").equals(args.optString("path"))||!"absent".equals(payload.optString("expectedVersion"))||!selected.getString("sha256").equals(descriptor.getString("contentDigest")))throw new SecurityException("目标清单/路径/字节与原所选包不一致或还没完成staging");
        JSONObject manifest=selected.getJSONObject("manifest"),summary=new JSONObject().put("manifestDigest",selected.getString("manifestDigest")).put("project",manifest.getJSONObject("project")).put("source",manifest.getJSONObject("source")).put("fileCount",manifest.getJSONArray("files").length()).put("directoryCount",manifest.getJSONArray("directories").length()).put("bytes",manifest.getLong("bytes")).put("restoresHistory",false).put("restoresEnvironment",false).put("restoresExecutionAuthority",false).put("newCopyOnly",true);
        return new JSONObject(selected.toString()).put("artifactId",selected.getString("id")).put("receiveOperationId",operation).put("routeId",route.getString("optionId")).put("targetDeviceId",wire.getString("targetDeviceId")).put("resourceBinding",wire.getJSONObject("resourceBinding")).put("summary",summary).put("imported",false).put("executionAuthorized",false);
    }
    public ProjectImportReview reviewProjectImportForOwner(dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.Window window,String operation)throws Exception{
        window.requireCurrent();synchronized(owner){return new ProjectImportReview(this,window,pendingProjectImport(window.taskId(),operation));}
    }
    public JSONObject confirmProjectImportForOwner(dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.Window window,ProjectImportReview review)throws Exception{
        return window.commitOriginalSourceScope(()->{synchronized(owner){db.beginTransaction();try{if(review==null||review.issuer!=this||review.window!=window||review.confirmed)throw new SecurityException("不是本窗口的准确项目导入审阅");String id=window.taskId(),operation=review.proposed.getString("receiveOperationId");JSONObject fresh=pendingProjectImport(id,operation);if(!TaskStore.capabilityFingerprint(fresh).equals(TaskStore.capabilityFingerprint(review.proposed)))throw new SecurityException("审阅后的原项目接收回执或范围变化");JSONObject task=sourceOwnerTask(id),approvals=task.optJSONObject("deviceProjectImportApprovals");if(approvals==null)approvals=new JSONObject();approvals.put(operation,new JSONObject().put("scopeDigest",TaskStore.capabilityFingerprint(task.getJSONObject("deviceSourceScope"))).put("review",fresh).put("approvedAt",System.currentTimeMillis()));task.put("deviceProjectImportApprovals",approvals);access.write(task);access.event(task,new JSONObject().put("type","device_project_import_owner_confirmed").put("receiveOperationId",operation).put("manifestDigest",fresh.getString("manifestDigest")).put("imported",false));review.confirmed=true;db.setTransactionSuccessful();return new JSONObject().put("approved",true).put("imported",false).put("executionStarted",false).put("receiveOperationId",operation);}finally{db.endTransaction();}}});
    }
    public JSONArray projectExportInputs(OwnerRouteApproval approval,RunControl original)throws Exception{
        synchronized(owner){requireSourceRoute(approval,original);JSONArray out=new JSONArray();JSONObject all=task(approval.id).optJSONObject("deviceProjectImportApprovals");for(String operation:all==null?java.util.Set.<String>of():dev.pocketengine.features.devices.DeviceProtocol.objectKeys(all))try{requireProjectExport(approval,operation,original);out.put(operation);}catch(SecurityException stale){ }return out;}
    }
    public void requireProjectExport(OwnerRouteApproval approval,String operation,RunControl original)throws Exception{
        synchronized(owner){requireSourceRoute(approval,original);JSONObject all=task(approval.id).optJSONObject("deviceProjectImportApprovals"),saved=all==null?null:all.optJSONObject(operation);if(saved==null||!approval.policyDigest.equals(saved.optString("scopeDigest"))||!approval.routeId().equals(saved.getJSONObject("review").optString("routeId")))throw new SecurityException("仅回读本原owner二次审阅过的准确项目新副本");JSONObject wire=childAllocationForOperation(approval.id,operation).getJSONObject("boundWire");if(!"workspace.project.receive".equals(wire.getJSONObject("arguments").optString("toolName"))||!approval.receivingGrant().targetDeviceId().equals(wire.getString("targetDeviceId"))||!approval.receivingGrant().grantId().equals(wire.getString("grantId"))||approval.receivingGrant().authorizationEpoch()!=wire.getLong("authorizationEpoch")||!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(approval.resourceBinding(),wire.getJSONObject("resourceBinding")))throw new SecurityException("项目回读不属于原目标/资源/receive操作");}
    }
    /** Original approved material snapshot; caller must check native package outside the SQL lock. */
    public JSONObject approvedProjectMaterial(OwnerRouteApproval approval,String operation,RunControl original)throws Exception{
        synchronized(owner){requireProjectExport(approval,operation,original);return duplicate(task(approval.id).getJSONObject("deviceProjectImportApprovals").getJSONObject(operation).getJSONObject("review"));}
    }
    public JSONArray projectImportConfirmations(OwnerRouteApproval approval,RunControl original)throws Exception{
        synchronized(owner){requireSourceRoute(approval,original);JSONArray out=new JSONArray();JSONObject selected=task(approval.id).optJSONObject("deviceProjectImportApprovals");for(String operation:selected==null?java.util.Set.<String>of():dev.pocketengine.features.devices.DeviceProtocol.objectKeys(selected))try{out.put(requireProjectImportConfirmation(approval,operation,original));}catch(SecurityException stale){ }return out;}
    }
    public JSONObject requireProjectImportConfirmation(OwnerRouteApproval approval,String operation,RunControl original)throws Exception{
        synchronized(owner){requireSourceRoute(approval,original);JSONObject saved=task(approval.id).optJSONObject("deviceProjectImportApprovals"),confirm=saved==null?null:saved.optJSONObject(operation);if(confirm==null||!approval.policyDigest.equals(confirm.optString("scopeDigest")))throw new SecurityException("原主人尚未确认这份已验清单的新副本导入");JSONObject actual=pendingProjectImport(approval.id,operation);if(!approval.routeId().equals(actual.getString("routeId"))||!TaskStore.capabilityFingerprint(actual).equals(TaskStore.capabilityFingerprint(confirm.getJSONObject("review"))))throw new SecurityException("原主人审阅后接收对象已变化");return new JSONObject().put("receiveOperationId",operation).put("manifestDigest",actual.getString("manifestDigest")).put("path",actual.getString("path")).put("expectedVersion","absent");}
    }
    /** Original source policy + actual receiving grant, bound to this original RunControl.
     * B-forwarding variants additionally retain the original verified A admission, not scope JSON. */
    public static final class OwnerRouteApproval {
        private final TaskStoreDeviceTasks issuer;private final String id,run,policyDigest,goal;private final int epoch;private final RunControl original;private final JSONObject route;private final dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.RouteOption option;private final DeviceExecutionAdmission forwarded;
        private OwnerRouteApproval(TaskStoreDeviceTasks issuer,String id,String run,int epoch,RunControl original,JSONObject route,dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.RouteOption option,String policyDigest,DeviceExecutionAdmission forwarded)throws Exception{this.issuer=issuer;this.id=id;this.run=run;this.epoch=epoch;this.original=original;this.route=duplicate(route);this.option=option;this.policyDigest=policyDigest;this.forwarded=forwarded;goal=issuer.task(id).getString("goal");}
        public void checkCurrent(RunControl actual)throws Exception{issuer.requireSourceRoute(this,actual);}
        public dev.pocketengine.features.devices.PairingCoordinator.OutgoingGrant receivingGrant(){return option.receivingGrant();}public String routeId(){return option.optionId();}public String sourceKey(){return option.sourceKey();}public String kind(){return route.optString("kind");}public String capabilityVersion(){return option.capabilityVersion();}public JSONObject resourceBinding()throws Exception{return option.resourceBinding();}public java.util.Map<String,String> toolEffects()throws Exception{java.util.Map<String,String> result=new java.util.LinkedHashMap<>();JSONObject selected=route.getJSONObject("toolDeclarations");for(String name:dev.pocketengine.features.devices.DeviceProtocol.objectKeys(selected))result.put(name,selected.getString(name));return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(result));}public int toolBudget(){return route.optInt("toolBudget");}public int modelBudget(){return route.optInt("modelBudget");}public long deadline(){return route.optLong("deadline");}public String parentGoal(){return goal;}public boolean forwarded(){return forwarded!=null;}public boolean permitsForwarding(){return route.optJSONArray("downstreamShares")!=null&&route.optJSONArray("downstreamShares").length()>0;}
        public JSONObject view()throws Exception{return duplicate(route).put("executionAuthorized",false);}
        public JSONArray sourceTargetShares(String issuerDeviceId,String sourceAllocation)throws Exception{checkCurrent(original);JSONArray shares=new JSONArray().put(dev.pocketengine.features.devices.DelegationGrant.targetShare(issuerDeviceId,sourceAllocation,option.receivingGrant().targetDeviceId(),option.resourceBinding(),route.getLong("localToolShare"),route.getLong("localModelShare")));JSONArray children=route.optJSONArray("downstreamShares");for(int i=0;children!=null&&i<children.length();i++){JSONObject share=children.getJSONObject(i);shares.put(dev.pocketengine.features.devices.DelegationGrant.targetShare(issuerDeviceId,sourceAllocation,share.getString("targetDeviceId"),share.getJSONObject("resourceBinding"),share.getLong("toolBudget"),share.getLong("modelBudget")));}return shares;}
    }
    private boolean nativeTeamPhoneSource(String id,JSONObject current)throws Exception {var call=teamPhoneSources.get(id);if(call==null||!"team_phone".equals(current.getJSONObject("origin").optString("kind")))return false;teamPhoneParent(call);return true;}
    private JSONObject sourceOwnerTask(String id)throws Exception{
        JSONObject current=task(id);if(!"owner".equals(current.optString("actorId"))||!"private".equals(current.optString("spaceId"))||current.optJSONObject("origin")!=null&&!nativeTeamPhoneSource(id,current)||!current.optString("kind").matches("assistant|phone")||current.optBoolean("cancelRequested")||current.optString("status").matches("completed|cancelled|failed|interrupted|reconciling"))throw new SecurityException("设备源范围只属于原私人owner任务，其他来源不能借入口降级");
        if(TaskExecutionScope.effectiveDeadline(current.optJSONObject("executionScope"))<=System.currentTimeMillis())throw new SecurityException("请先在原任务范围中明确期限/预算，不能由设备选择补授权");return current;
    }
    /** Owner page projection only, not a peer endpoint or an execution admission. */
    public JSONObject sourceScopeView(String id)throws Exception{synchronized(owner){JSONObject current=sourceOwnerTask(id),scope=duplicate(current.getJSONObject("executionScope"));long scopeDeadline=TaskExecutionScope.effectiveDeadline(scope);JSONObject identity=new JSONObject().put("taskId",id).put("goal",current.getString("goal")).put("runId",current.optString("runId")).put("epoch",current.optInt("coordinatorEpoch"));scope.remove("toolsUsed");scope.remove("modelCallsUsed");identity.put("executionScope",scope).put("sourceScopeRevision",current.optJSONObject("deviceSourceScope")==null?0:current.getJSONObject("deviceSourceScope").optLong("revision"));JSONObject result=new JSONObject().put("taskId",id).put("runId",current.optString("runId")).put("executorEpoch",current.optInt("coordinatorEpoch")).put("goal",current.getString("goal")).put("ownerScopeFingerprint",TaskStore.capabilityFingerprint(identity)).put("scopeRevision",scope.getLong("revision")).put("sourceScopeRevision",identity.getLong("sourceScopeRevision")).put("deadlineMaximum",Math.min(dev.pocketengine.features.devices.DeviceProtocol.MAX_SAFE_INTEGER,scopeDeadline)).put("toolsAvailable",Math.max(0,current.getJSONObject("executionScope").getInt("toolLimit")-current.getJSONObject("executionScope").getInt("toolsUsed")-heldBudget(id,current.optString("runId"),false))).put("modelsAvailable",Math.max(0,current.getJSONObject("executionScope").getInt("modelCallLimit")-current.getJSONObject("executionScope").getInt("modelCallsUsed")-heldBudget(id,current.optString("runId"),true))).put("sharedArtifactCount",sourceMaterialCount(current.optJSONObject("deviceSourceScope"))).put("sourceOperationOverheadAtLeast",1).put("unknownRefunded",false).put("budgetReserved",false);if(current.optJSONObject("deviceSourceScope")!=null)result.put("selected",duplicate(current.getJSONObject("deviceSourceScope")));return result;}}
    public SourceScopeReview reviewSourceScope(dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.Window window,java.util.List<dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.RouteSelection> selections)throws Exception{
        java.util.Objects.requireNonNull(window).requireCurrent();synchronized(owner){JSONObject proposed=sourcePolicy(window.taskId(),selections);return new SourceScopeReview(this,window,selections,proposed);}
    }
    public JSONObject confirmSourceScope(dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.Window window,SourceScopeReview review)throws Exception{return java.util.Objects.requireNonNull(window).commitOriginalSourceScope(()->confirmSourceScopeLocked(window,review));}
    private JSONObject confirmSourceScopeLocked(dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.Window window,SourceScopeReview review)throws Exception{
        if(review==null||review.issuer!=this||review.window!=window||review.confirmed)throw new SecurityException("不是本原TaskStore/原窗口的一次准确范围确认");window.requireCurrent();synchronized(owner){db.beginTransaction();try{JSONObject current=sourceOwnerTask(window.taskId()),fresh=sourcePolicy(window.taskId(),review.selections);JSONObject expected=duplicate(review.proposed),check=duplicate(fresh);expected.remove("reviewId");check.remove("reviewId");if(!dev.pocketengine.features.devices.DeviceProtocol.canonical(expected).equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(check)))throw new SecurityException("审阅后原范围/目标grant/剩余预算已变化");JSONArray admitted=new JSONArray();boolean invoke=false,delegate=false;for(int i=0;i<review.proposed.getJSONArray("routes").length();i++){String kind=review.proposed.getJSONArray("routes").getJSONObject(i).getString("kind");invoke|=kind.equals("tool_session");delegate|=kind.equals("goal");}if(invoke)admitted.put("device_invoke_tool");if(delegate)admitted.put("device_delegate_goal");if(invoke||delegate)admitted.put("device_operation_get");String run=current.optString("runId");if(!run.isEmpty())owner.extendExecutionToolsForOwner(window.taskId(),run,current.getInt("coordinatorEpoch"),current.getJSONObject("executionScope").getLong("revision"),admitted);else{JSONObject execution=current.getJSONObject("executionScope");JSONArray names=execution.getJSONArray("tools");for(int i=0;i<admitted.length();i++)if(!jsonSubset(new JSONArray().put(admitted.getString(i)),names))names.put(admitted.getString(i));execution.put("revision",execution.getLong("revision")+1);current.put("executionScope",execution);access.write(current);}current=task(window.taskId());JSONObject policy=duplicate(review.proposed).put("parentScopeRevision",current.getJSONObject("executionScope").getLong("revision"));current.put("deviceSourceScope",policy);access.write(current);access.event(current,new JSONObject().put("type","device_source_scope_owner_confirmed").put("reviewId",review.proposed.getString("reviewId")).put("revision",review.proposed.getLong("revision")).put("budgetReserved",false));review.confirmed=true;db.setTransactionSuccessful();return duplicate(policy).put("executionAuthorized",false).put("budgetReserved",false);}finally{db.endTransaction();}}}
    private JSONObject sourcePolicy(String id,java.util.List<dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.RouteSelection> selections)throws Exception{
        JSONObject state=sourceScopeView(id),current=sourceOwnerTask(id);long now=System.currentTimeMillis(),extraTools=0,extraModels=0;JSONArray routes=new JSONArray();if(selections.size()>16)throw new SecurityException("设备源范围路由过多");
        for(var selected:selections){var option=selected.option();var choice=selected.choice();option.receivingGrant().checkCurrent(now);JSONObject grant=option.receivingGrant().grant();int tools=Math.toIntExact(dev.pocketengine.features.devices.DeviceProtocol.integer(choice.toolBudget(),"toolBudget",option.kind().equals("goal")?0:1,400)),models=Math.toIntExact(dev.pocketengine.features.devices.DeviceProtocol.integer(choice.modelBudget(),"modelBudget",0,1000));long end=choice.deadline();
            if(choice.context()!=dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.ContextChoice.GOAL_ONLY||end<=now||end>state.getLong("deadlineMaximum")||end>option.receivingGrant().deadline()||tools>grant.getLong("toolBudget")||models>grant.getLong("modelBudget")||option.kind().equals("tool_session")&&models!=0||option.kind().equals("goal")&&models<1)throw new SecurityException("设备选择超过原父/目标owner预算、期限或上下文范围");
            long localTools=dev.pocketengine.features.devices.DeviceProtocol.integer(choice.localToolShare(),"localToolShare",0,tools),localModels=dev.pocketengine.features.devices.DeviceProtocol.integer(choice.localModelShare(),"localModelShare",0,models);JSONArray shares=new JSONArray();long forwardTools=0,forwardModels=0;for(int i=0;i<selected.shares().size();i++){var share=selected.shares().get(i);var amount=choice.shares().get(i);long childTools=dev.pocketengine.features.devices.DeviceProtocol.integer(amount.toolBudget(),"toolBudget",0,400),childModels=dev.pocketengine.features.devices.DeviceProtocol.integer(amount.modelBudget(),"modelBudget",0,1000);if(childTools+childModels<1)throw new SecurityException("下游份额不能是空额度");forwardTools=Math.addExact(forwardTools,childTools);forwardModels=Math.addExact(forwardModels,childModels);shares.put(new JSONObject().put("targetDeviceId",share.targetDeviceId()).put("resourceBinding",share.resourceBinding()).put("toolBudget",childTools).put("modelBudget",childModels));}
            JSONObject forward=grant.optJSONObject("forwardingScope");if(shares.length()>0&&(!option.kind().equals("goal")||forward==null||localModels<1||forwardTools>forward.getLong("toolBudget")||forwardModels>forward.getLong("modelBudget"))||localTools+forwardTools>tools||localModels+forwardModels>models||shares.length()==0&&(localTools!=tools||localModels!=models))throw new SecurityException("B本机和每C/D准确resource份额必须非重叠且总和不超过这一原预留");
            JSONObject declarations=new JSONObject();for(var effect:option.toolEffects().entrySet())declarations.put(effect.getKey(),effect.getValue());JSONObject route=new JSONObject().put("optionId",option.optionId()).put("sourceKey",option.sourceKey()).put("kind",option.kind()).put("targetDeviceId",option.receivingGrant().targetDeviceId()).put("instanceId",option.receivingGrant().targetInstanceId()).put("grantId",option.receivingGrant().grantId()).put("authorizationEpoch",option.receivingGrant().authorizationEpoch()).put("grantFingerprint",dev.pocketengine.features.devices.DeviceProtocol.sha256(dev.pocketengine.features.devices.DeviceProtocol.canonicalBytes(grant))).put("capabilityVersion",option.capabilityVersion()).put("resourceBinding",option.resourceBinding()).put("toolDeclarations",declarations).put("toolBudget",tools).put("modelBudget",models).put("deadline",end).put("localToolShare",localTools).put("localModelShare",localModels).put("downstreamShares",shares).put("contextMode","goal_only").put("files",selected.files()).put("projects",selected.projects());
            long[] committed=sourceRouteCommitted(id,option.sourceKey());extraTools=Math.addExact(extraTools,Math.max(0,tools-committed[0]));extraModels=Math.addExact(extraModels,Math.max(0,models-committed[1]));routes.put(route);
        }
        if(extraTools>state.getLong("toolsAvailable")||extraModels>state.getLong("modelsAvailable"))throw new SecurityException("选择总份额超过原父真实剩余；旧unknown/未确认份额不会被回收再分配");
        return new JSONObject().put("format","pocketworld.native-device-source-scope.v1").put("taskId",id).put("reviewId",UUID.randomUUID().toString()).put("revision",state.getLong("sourceScopeRevision")+1).put("parentScopeRevision",state.getLong("scopeRevision")).put("goal",current.getString("goal")).put("contextMode","goal_only").put("routes",routes);
    }
    private long[] sourceRouteCommitted(String id,String sourceKey)throws Exception{long tools=0,models=0;try(Cursor rows=db.rawQuery("SELECT state,tools_reserved,models_reserved,receipt_json FROM device_child_budget WHERE parent_task_id=?",new String[]{id})){while(rows.moveToNext()){if(rows.isNull(3))continue;JSONObject receipt=new JSONObject(rows.getString(3)),nativeRef=receipt.optJSONObject("nativeSourceRoute");if(nativeRef==null||!sourceKey.equals(nativeRef.optString("sourceKey")))continue;boolean settled="settled".equals(rows.getString(0));tools=Math.addExact(tools,settled?receipt.getLong("toolsUsed"):rows.getLong(1));models=Math.addExact(models,settled?receipt.getLong("modelCallsUsed"):rows.getLong(2));}}return new long[]{tools,models};}
    public java.util.List<OwnerRouteApproval> sourceApprovalsForRun(String id,String run,int epoch,RunControl original,java.util.List<dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.RouteOption> options)throws Exception{
        synchronized(owner){JSONObject current=sourceOwnerTask(id);access.checkRun(id,run,epoch,original,false);if(!original.matchesHostRun(id,run,epoch))throw new SecurityException("不是原父RunControl");JSONObject scope=current.optJSONObject("deviceSourceScope");if(scope==null)return java.util.List.of();if(!scope.getString("goal").equals(current.getString("goal"))||scope.getLong("parentScopeRevision")!=current.getJSONObject("executionScope").getLong("revision"))throw new SecurityException("原owner设备范围版本已失效，不能自动适配新父范围");String digest=TaskStore.capabilityFingerprint(scope);java.util.ArrayList<OwnerRouteApproval> result=new java.util.ArrayList<>();JSONArray routes=scope.getJSONArray("routes");for(int i=0;i<routes.length();i++){JSONObject selected=routes.getJSONObject(i);dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.RouteOption found=null;for(var option:options)if(option.optionId().equals(selected.getString("optionId")))found=option;if(found==null)throw new SecurityException("原目标没有同通道新鲜能力事实/原grant句柄，未用paired列表替代");requireOptionMatches(selected,found);result.add(new OwnerRouteApproval(this,id,run,epoch,original,selected,found,digest,null));}return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(result));}
    }
    private static void requireOptionMatches(JSONObject route,dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.RouteOption option)throws Exception{option.receivingGrant().checkCurrent(System.currentTimeMillis());JSONObject declarations=new JSONObject();for(var item:option.toolEffects().entrySet())declarations.put(item.getKey(),item.getValue());if(!route.getString("grantFingerprint").equals(dev.pocketengine.features.devices.DeviceProtocol.sha256(dev.pocketengine.features.devices.DeviceProtocol.canonicalBytes(option.receivingGrant().grant())))||!route.getString("capabilityVersion").equals(option.capabilityVersion())||!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(route.getJSONObject("resourceBinding"),option.resourceBinding()) )throw new SecurityException("原owner选定目标grant/资源/工具版本已变化，需新native审阅");for(String name:dev.pocketengine.features.devices.DeviceProtocol.objectKeys(route.getJSONObject("toolDeclarations")))if(!route.getJSONObject("toolDeclarations").getString(name).equals(declarations.optString(name)))throw new SecurityException("原已选工具声明已变化；未自动采纳新工具");}
    private void requireSourceRoute(OwnerRouteApproval proof,RunControl actual)throws Exception{
        if(proof==null||proof.issuer!=this||actual!=proof.original)throw new SecurityException("不是本原TaskStore签发的源路由证明");synchronized(owner){actual.check();access.checkRun(proof.id,proof.run,proof.epoch,actual,false);if(actual.isCancelled()||actual.isPaused()||!actual.matchesHostRun(proof.id,proof.run,proof.epoch)||owner.taskExecutionDeadline(proof.id,proof.run,proof.epoch)<=System.currentTimeMillis()||proof.deadline()<=System.currentTimeMillis())throw new SecurityException("原源任务或路由已停止/到期");JSONObject current=task(proof.id);if(proof.forwarded!=null){Live binding=live.get(proof.run);if(binding==null||binding.original!=actual||binding.admission!=proof.forwarded)throw new SecurityException("没有原已验A委派的B执行");proof.forwarded.checkCurrent();}else{sourceOwnerTask(proof.id);JSONObject scope=current.optJSONObject("deviceSourceScope");if(scope==null||scope.getLong("parentScopeRevision")!=current.getJSONObject("executionScope").getLong("revision")||!proof.policyDigest.equals(TaskStore.capabilityFingerprint(scope)))throw new SecurityException("原owner设备范围已撤回或换版");}requireOptionMatches(proof.route,proof.option);}
    }
    public void requireForwardedSource(String id,DeviceExecutionAdmission nativeAdmission,RunControl original)throws Exception{synchronized(owner){JSONObject current=task(id);requireDevice(current);var identity=original.hostRunIdentity();Live retained=identity==null?null:live.get(identity.runId());if(nativeAdmission==null||nativeAdmission.kind()!=DeviceExecutionAdmission.Kind.GOAL||retained==null||retained.original!=original||retained.admission!=nativeAdmission||!identity.taskId().equals(id)||!original.matchesHostRun(id,current.optString("runId"),current.optInt("coordinatorEpoch")))throw new SecurityException("没有原B Agent中native已验证A目标/RunControl");nativeAdmission.checkCurrent();}}
    public java.util.List<OwnerRouteApproval> forwardedApprovalsForRun(String id,DeviceExecutionAdmission nativeAdmission,RunControl original,java.util.List<dev.pocketengine.features.devices.NativeDeviceTaskScopeHost.RouteOption> options)throws Exception{
        synchronized(owner){requireForwardedSource(id,nativeAdmission,original);JSONObject current=task(id),proof=current.getJSONObject("deviceExecutionScope").optJSONObject("forwardingProof");if(proof==null)return java.util.List.of();String origin=current.getJSONObject("origin").getString("originDeviceId");java.util.ArrayList<OwnerRouteApproval> result=new java.util.ArrayList<>();JSONArray shares=proof.getJSONArray("targetShares");for(var option:options){String method=option.kind().equals("goal")?"task.submitGoal":"tool.invoke";if(!jsonSubset(new JSONArray().put(method),proof.getJSONArray("capabilities")))continue;JSONObject selected=null;for(int i=0;i<shares.length();i++){JSONObject candidate=shares.getJSONObject(i);if(option.receivingGrant().targetDeviceId().equals(candidate.getString("targetDeviceId"))&&dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(option.resourceBinding(),candidate.getJSONObject("resourceBinding")))selected=candidate;}if(selected==null)continue;JSONObject targetGrant=option.receivingGrant().grant();if(!targetGrant.optBoolean("allowDelegation")||!jsonSubset(new JSONArray().put(origin),targetGrant.getJSONArray("allowedOrigins")))continue;long tools=selected.getLong("toolBudget"),models=option.kind().equals("goal")?selected.getLong("modelBudget"):0;if(option.kind().equals("tool_session")&&tools<1||option.kind().equals("goal")&&models<1||tools>targetGrant.getLong("toolBudget")||models>targetGrant.getLong("modelBudget"))continue;
                JSONObject declarations=new JSONObject();for(var entry:option.toolEffects().entrySet())if(jsonSubset(new JSONArray().put(entry.getKey()),proof.getJSONArray("toolNames")))declarations.put(entry.getKey(),entry.getValue());if(option.kind().equals("tool_session")&&declarations.length()==0)continue;JSONObject route=new JSONObject().put("optionId",option.optionId()).put("sourceKey",option.sourceKey()).put("kind",option.kind()).put("targetDeviceId",option.receivingGrant().targetDeviceId()).put("instanceId",option.receivingGrant().targetInstanceId()).put("grantId",option.receivingGrant().grantId()).put("authorizationEpoch",option.receivingGrant().authorizationEpoch()).put("grantFingerprint",dev.pocketengine.features.devices.DeviceProtocol.sha256(dev.pocketengine.features.devices.DeviceProtocol.canonicalBytes(targetGrant))).put("capabilityVersion",option.capabilityVersion()).put("resourceBinding",option.resourceBinding()).put("toolDeclarations",declarations).put("toolBudget",tools).put("modelBudget",models).put("deadline",Math.min(proof.getLong("deadline"),Math.min(nativeAdmission.deadline(),option.receivingGrant().deadline()))).put("localToolShare",tools).put("localModelShare",models).put("downstreamShares",new JSONArray()).put("contextMode","goal_only");result.add(new OwnerRouteApproval(this,id,current.getString("runId"),current.getInt("coordinatorEpoch"),original,route,option,TaskStore.capabilityFingerprint(proof),nativeAdmission));
            }return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(result));}
    }
    /** A B/A local owner read window over the ORIGINAL sent operation, independent of
     * paused/expired execution scope. It grants neither a new run nor execution authority. */
    public static final class OwnerHistoryEvidence {
        private final TaskStoreDeviceTasks issuer;private final String id,operation,wire;
        private OwnerHistoryEvidence(TaskStoreDeviceTasks issuer,String id,String operation,JSONObject wire){this.issuer=issuer;this.id=id;this.operation=operation;this.wire=dev.pocketengine.features.devices.DeviceProtocol.canonical(wire);}
        public String taskId(){return id;}public String operationId(){return operation;}public JSONObject originalWire()throws Exception{return new JSONObject(wire);}
        public String targetDeviceId()throws Exception{return originalWire().getString("targetDeviceId");}public String grantId()throws Exception{return originalWire().getString("grantId");}public JSONObject resourceBinding()throws Exception{return originalWire().getJSONObject("resourceBinding");}
        public void requireCurrent()throws Exception{issuer.requireOwnerHistory(this);}
        public JSONObject view()throws Exception{requireCurrent();JSONObject original=originalWire();return new JSONObject().put("taskId",id).put("operationId",operation).put("targetDeviceId",original.getString("targetDeviceId")).put("instanceId",original.getString("instanceId")).put("argumentsDigest",original.getString("argumentsDigest")).put("resourceBinding",resourceBinding()).put("readOnly",true).put("newRun",false).put("retrySubmitted",false).put("unknownRefunded",false);}
    }
    public OwnerHistoryEvidence ownerHistoryEvidence(String id,String originalOperation)throws Exception{
        synchronized(owner){JSONObject wire=childAllocationForOperation(id,originalOperation).getJSONObject("boundWire");OwnerHistoryEvidence evidence=new OwnerHistoryEvidence(this,id,originalOperation,wire);requireOwnerHistory(evidence);return evidence;}
    }
    private void requireOwnerHistory(OwnerHistoryEvidence evidence)throws Exception{
        if(evidence==null||evidence.issuer!=this)throw new SecurityException("不是本原TaskStore历史读取证据");synchronized(owner){JSONObject current=task(evidence.id),wire=childAllocationForOperation(evidence.id,evidence.operation).getJSONObject("boundWire");if(!evidence.wire.equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(wire)))throw new SecurityException("原已发送wire/目标/摘要已改变");JSONObject origin=current.optJSONObject("origin");if(origin==null){if(!"owner".equals(current.optString("actorId"))||!"private".equals(current.optString("spaceId")))throw new SecurityException("不是原本机owner历史任务");return;}
            requireDevice(current);JSONObject scope=current.getJSONObject("deviceExecutionScope"),source=scope.optJSONObject("forwardingProof");if(source==null||!source.getString("issuerDeviceId").equals(origin.getString("originDeviceId"))||!dev.pocketengine.features.devices.DelegationGrant.targetResourceContains(source.getJSONArray("targetResources"),wire.getString("targetDeviceId"),wire.getJSONObject("resourceBinding")))throw new SecurityException("没有原A准确分享来源，不能读取下游内容");
            String caller=origin.getString("callerDeviceId"),grantId=origin.getString("grantId");long now=System.currentTimeMillis();JSONObject grant;try(Cursor row=db.rawQuery("SELECT g.body_json,g.revoked,p.revoked,g.authorization_epoch,p.authorization_epoch FROM device_grants g JOIN device_peers p ON p.device_id=g.peer_device_id WHERE g.grant_id=? AND g.peer_device_id=?",new String[]{grantId,caller})){if(!row.moveToFirst()||row.getInt(1)!=0||row.getInt(2)!=0||row.getLong(3)!=row.getLong(4))throw new SecurityException("原A来源已撤回，历史内容不再可见");grant=new JSONObject(row.getString(0));}
            dev.pocketengine.features.devices.PairingCoordinator.validateRetainedGrant(grant,origin.getString("targetDeviceId"),caller,now);JSONObject history=grant.optJSONObject("historyRead"),forward=grant.optJSONObject("forwardingScope");boolean currentRead=now<grant.getLong("expiresAt")&&jsonSubset(new JSONArray().put("operation.get"),grant.getJSONArray("capabilities")),retainedRead=history!=null&&now<history.getLong("expiresAt")&&jsonSubset(new JSONArray().put("operation.get"),history.getJSONArray("methods"));if(!currentRead&&!retainedRead||!jsonSubset(new JSONArray().put(origin.getString("originDeviceId")),grant.getJSONArray("allowedOrigins"))||!jsonSubset(new JSONArray().put(scope.getJSONObject("resourceBinding")),grant.getJSONArray("resources"))||forward==null||!dev.pocketengine.features.devices.DelegationGrant.targetResourceContains(forward.getJSONArray("targetResources"),wire.getString("targetDeviceId"),wire.getJSONObject("resourceBinding")))throw new SecurityException("当前原生来源可见策略不再包含此准确分享；不复活执行权限");
        }
    }
    public void recordOwnerHistory(OwnerHistoryEvidence evidence,dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer actualPeer,JSONObject typed)throws Exception{
        requireOwnerHistory(evidence);JSONObject original=evidence.originalWire();if(!original.getString("targetDeviceId").equals(actualPeer.deviceId())||!evidence.operation.equals(typed.optString("operationId")))throw new SecurityException("历史回执不是原发送目标/operation");
        if(!typed.getBoolean("accepted")){synchronized(owner){try(Cursor row=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND operation_id=?",new String[]{evidence.id,evidence.operation})){if(!row.moveToFirst())throw new SecurityException("原操作已删除");JSONObject receipt=row.isNull(0)?new JSONObject():new JSONObject(row.getString(0));receipt.put("nativeDeviceHistoryProbe",new JSONObject().put("checkedAt",System.currentTimeMillis()).put("status",typed.getString("status")).put("effectState",typed.getString("effectState")).put("accepted",false).put("retrySubmitted",false));ContentValues update=new ContentValues();update.put("receipt",receipt.toString());db.update("operations",update,"task_id=? AND operation_id=?",new String[]{evidence.id,evidence.operation});}}return;}
        JSONObject audience=typed.getJSONObject("audience");if(!original.getString("argumentsDigest").equals(typed.getString("argumentsDigest")))throw new SecurityException("原操作摘要不匹配");for(String field:new String[]{"originDeviceId","callerDeviceId","targetDeviceId","instanceId","rootTaskId","localTaskId","grantId","authorizationEpoch"})if(!dev.pocketengine.features.devices.DeviceProtocol.canonical(original.opt(field)).equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(audience.opt(field))))throw new SecurityException("历史受众不是原SQL发送身份");if(!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(original.getJSONObject("resourceBinding"),audience.getJSONObject("resourceBinding"))||!original.getString("runId").equals(audience.optString("requestRunId"))||original.getLong("executorEpoch")!=audience.optLong("requestExecutorEpoch"))throw new SecurityException("历史资源/source run不是原wire");JSONObject allocation=childAllocationForOperation(evidence.id,evidence.operation);String kind=typed.has("localTaskId")?"task":typed.has("jobId")?"job":"operation",object=typed.has("localTaskId")?typed.getString("localTaskId"):typed.has("jobId")?typed.getString("jobId"):evidence.operation;
        if(kind.equals("task")&&!dev.pocketengine.features.devices.DeviceProtocol.receiverTaskId(original).equals(object))throw new SecurityException("原目标Task绑定被替换");JSONObject accepted=new JSONObject().put("accepted",true).put("acceptedAt",typed.getLong("acceptedAt")).put("acceptedObjectKind",kind).put("objectId",object).put("targetOperationId",evidence.operation).put("instanceId",typed.getString("instanceId")).put("runId",typed.optString("runId")).put("parentTaskId",evidence.id).put("parentOperationId",allocation.getString("anchorOperationId")).put("sourceOperationId",evidence.operation).put("operationArgumentsDigest",typed.getString("argumentsDigest")).put("verifiedOperationReceipt",typed).put("argumentsDigest",allocation.getString("anchorArgumentsDigest")).put("executorDeviceId",actualPeer.deviceId());if(kind.equals("task"))accepted.put("localTaskId",object);if(kind.equals("job"))accepted.put("jobId",object);if(typed.has("executorEpoch"))accepted.put("executorEpoch",typed.getLong("executorEpoch"));recordChildAccepted(dev.pocketengine.features.devices.DeviceTaskCoordinator.authenticatedChildAcceptance(actualPeer,accepted));
    }

    /** Opaque SQL-issued receive authority shared by the original bulk transport only. */
    public sealed interface DeviceReceiveEvidence permits DeviceImageEvidence, DeviceFileEvidence {
        dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer actualControlPeer();
        JSONObject descriptor() throws Exception;
        long deadline();
        void requireCurrent() throws Exception;
        dev.pocketengine.features.devices.PairingCoordinator.HistoryQuery historyQuery(dev.pocketengine.features.devices.DeviceIdentity.LocalIdentity local,long offset,int length,long now) throws Exception;
    }
    /** Fixed workspace.export result from the original persisted accepted operation. A model
     * cannot select an arbitrary remote descriptor, local path or receiving task/run. */
    public static final class DeviceFileEvidence implements DeviceReceiveEvidence {
        private final TaskStoreDeviceTasks issuer; private final OwnerRouteApproval approval;
        private final RunControl original; private final dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer peer;
        private final String operation,wire,descriptor; private final long pauseRevision;
        private DeviceFileEvidence(TaskStoreDeviceTasks issuer,OwnerRouteApproval approval,RunControl original,dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer peer,String operation,JSONObject wire,JSONObject descriptor){
            this.issuer=issuer;this.approval=approval;this.original=original;this.peer=peer;this.operation=operation;
            this.wire=dev.pocketengine.features.devices.DeviceProtocol.canonical(wire);this.descriptor=dev.pocketengine.features.devices.DeviceProtocol.canonical(descriptor);pauseRevision=original.pauseRevision();
        }
        public dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer actualControlPeer(){return peer;}
        public JSONObject descriptor()throws Exception{return new JSONObject(descriptor);}
        public long deadline(){return approval.deadline();}
        public RunControl originalControl(){return original;}
        public String operationId(){return operation;}
        public String receivingTaskId(){return approval.id;}
        public String frozenRunId()throws Exception{return new JSONObject(wire).getString("runId");}
        public int frozenEpoch()throws Exception{return new JSONObject(wire).getInt("executorEpoch");}
        public void requireCurrent()throws Exception{issuer.requireDeviceFile(this);}
        public dev.pocketengine.features.devices.PairingCoordinator.HistoryQuery historyQuery(dev.pocketengine.features.devices.DeviceIdentity.LocalIdentity local,long offset,int length,long now)throws Exception{
            requireCurrent();JSONObject fixed=descriptor();long size=fixed.getLong("size");if(offset<0||offset>=size||length<1||length>262144||length>size-offset)throw new SecurityException("文件分块超出原固定范围");long ttl=Math.min(5000,deadline()-now);if(ttl<1)throw new SecurityException("原文件读取期限已结束");
            return approval.receivingGrant().historyQuery(local,"artifact.readChunk",fixed.getString("artifactId"),approval.resourceBinding(),now,ttl,new JSONObject().put("contentDigest",fixed.getString("contentDigest")).put("offset",offset).put("length",length));
        }
    }
    public DeviceFileEvidence deviceFileEvidence(OwnerRouteApproval approval,RunControl original,dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer peer,String operation)throws Exception{
        if(approval==null||approval.issuer!=this||approval.original!=original)throw new SecurityException("没有原任务的native文件接收范围");
        approval.checkCurrent(original);approval.receivingGrant().requireTargetPeer(peer);
        synchronized(owner){JSONObject wire=childAllocationForOperation(approval.id,operation).getJSONObject("boundWire");
            JSONObject descriptor=fixedFileDescriptor(approval,peer,operation,wire);
            DeviceFileEvidence proof=new DeviceFileEvidence(this,approval,original,peer,operation,wire,descriptor);requireDeviceFile(proof);return proof;}
    }
    private JSONObject fixedFileDescriptor(OwnerRouteApproval approval,dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer peer,String operation,JSONObject wire)throws Exception{
        boolean project="workspace.project.export".equals(wire.getJSONObject("arguments").optString("toolName"));if(project)requireProjectExport(approval,wire.getJSONObject("arguments").getJSONObject("arguments").getString("receiveOperationId"),approval.original);
        if(!"ToolInvocation".equals(wire.optString("messageType"))||!java.util.Set.of("workspace.export","workspace.project.export").contains(wire.getJSONObject("arguments").optString("toolName"))
            ||!approval.receivingGrant().targetDeviceId().equals(wire.getString("targetDeviceId"))||!approval.receivingGrant().grantId().equals(wire.getString("grantId"))
            ||!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(wire.getJSONObject("resourceBinding"),approval.resourceBinding()))throw new SecurityException("不是原已选范围的明确文件导出");
        JSONObject saved;try(Cursor row=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND operation_id=? AND run_id=? AND state='receipt'",new String[]{approval.id,operation,wire.getString("runId")})){
            if(!row.moveToFirst()||row.isNull(0))throw new SecurityException("没有原SQL已核文件操作");saved=new JSONObject(row.getString(0));}
        JSONObject accepted=saved.getJSONObject("nativeDeviceAcceptedBinding"),send=saved.getJSONObject("nativeDeviceSend"),typed=accepted.getJSONObject("verifiedReceipt");
        if(!"write_claimed".equals(send.optString("state"))||!operation.equals(accepted.getString("sourceOperationId"))||!wire.getString("argumentsDigest").equals(accepted.getString("operationArgumentsDigest"))
            ||!peer.deviceId().equals(accepted.getString("executorDeviceId"))||!"reported".equals(typed.optString("effectState"))||!"succeeded".equals(typed.optString("status")))throw new SecurityException("文件缺少原已发送/已接受/成功结果证据");
        JSONObject payload=typed.getJSONObject("payload"),descriptor=payload.optJSONObject("nativeToolResult");if(descriptor==null)descriptor=payload;
        dev.pocketengine.features.devices.DeviceProtocol.validateMessage(descriptor);
        if(!"ArtifactDescriptor".equals(descriptor.optString("messageType"))||!(project?"project_bundle":"file").equals(descriptor.optString("kind"))||!peer.deviceId().equals(descriptor.getString("executorDeviceId"))
            ||!approval.receivingGrant().targetInstanceId().equals(descriptor.getString("instanceId"))||!operation.equals(descriptor.getString("operationId"))||!wire.getString("localTaskId").equals(descriptor.getString("taskId"))
            ||!wire.getString("runId").equals(descriptor.getString("requestRunId"))||wire.getLong("executorEpoch")!=descriptor.getLong("requestExecutorEpoch")||descriptor.has("runId")||descriptor.has("executorEpoch")
            ||descriptor.getLong("size")>dev.pocketengine.features.attachments.AttachmentBook.MAX_FILE_BYTES)throw new SecurityException("文件身份不是原目标/实例/操作/执行或超过原附件上限");
        return new JSONObject(descriptor.toString());
    }
    private void requireDeviceFile(DeviceFileEvidence proof)throws Exception{
        if(proof==null||proof.issuer!=this)throw new SecurityException("不是本TaskStore文件证据");proof.approval.checkCurrent(proof.original);proof.approval.receivingGrant().requireTargetPeer(proof.peer);
        if(proof.original.pauseRevision()!=proof.pauseRevision||System.currentTimeMillis()>=proof.deadline())throw new SecurityException("原文件接收已暂停/过期");
        synchronized(owner){JSONObject wire=childAllocationForOperation(proof.approval.id,proof.operation).getJSONObject("boundWire");if(!proof.wire.equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(wire))
            ||!proof.descriptor.equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(fixedFileDescriptor(proof.approval,proof.peer,proof.operation,wire))))throw new SecurityException("原文件操作或固定内容已变化");}
    }

    /** Original SQL image provenance. Never constructed from an artifact descriptor, model
     * arguments, a transport connection or a UI flag. All byte reads remain current-scope reads. */
    public static final class DeviceImageEvidence implements DeviceReceiveEvidence {
        private final TaskStoreDeviceTasks issuer;private final OwnerRouteApproval approval;private final RunControl original;
        private final dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer peer;
        private final String operation,digest,descriptor,reference,binding,targetOperation,mime;private final int width,height;
        private final long deadline,captured,pauseRevision;
        private DeviceImageEvidence(TaskStoreDeviceTasks issuer,OwnerRouteApproval approval,RunControl original,dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer peer,String operation,JSONObject descriptor,JSONObject reference,int width,int height)throws Exception{
            this.issuer=issuer;this.approval=approval;this.original=original;this.peer=peer;this.operation=operation;this.descriptor=dev.pocketengine.features.devices.DeviceProtocol.canonical(descriptor);this.reference=dev.pocketengine.features.devices.DeviceProtocol.canonical(reference);binding=dev.pocketengine.features.devices.DeviceProtocol.canonical(approval.resourceBinding());digest=descriptor.getString("contentDigest");targetOperation=descriptor.getString("operationId");mime=descriptor.getString("mediaType");this.width=width;this.height=height;captured=reference.getLong("capturedAt");deadline=Math.min(approval.deadline(),Math.min(descriptor.getLong("expiresAt"),Math.min(Math.addExact(captured,120_000),reference.optLong("expiresAt",Long.MAX_VALUE))));pauseRevision=original.pauseRevision();
        }
        public dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer actualControlPeer(){return peer;}
        public JSONObject descriptor()throws Exception{return new JSONObject(descriptor);}
        public JSONObject observationRef()throws Exception{return new JSONObject(reference);}
        public long deadline(){return deadline;}public int imageWidth(){return width;}public int imageHeight(){return height;}public String mimeType(){return mime;}public String sourceOperationId(){return operation;}
        public void requireCurrent()throws Exception{issuer.requireDeviceImage(this);}
        /** Only the original native signed read route can make the chunk query. */
        public dev.pocketengine.features.devices.PairingCoordinator.HistoryQuery historyQuery(dev.pocketengine.features.devices.DeviceIdentity.LocalIdentity existingLocalIdentity,long offset,int length,long now)throws Exception{
            requireCurrent();JSONObject fixed=descriptor();long size=fixed.getLong("size");if(offset<0||offset>=size||length<1||length>262144||length>size-offset)throw new SecurityException("截图分块不属于原固定范围");long ttl=Math.min(5000,deadline-now);if(ttl<1)throw new SecurityException("原截图读取期限已结束");return approval.receivingGrant().historyQuery(existingLocalIdentity,"artifact.readChunk",fixed.getString("artifactId"),new JSONObject(binding),now,ttl,new JSONObject().put("contentDigest",digest).put("offset",offset).put("length",length));
        }
        public dev.pocketengine.core.NativeToolObservation.DeviceFrameIdentity frameIdentity()throws Exception{
            requireCurrent();var run=original.hostRunIdentity();return new dev.pocketengine.core.NativeToolObservation.DeviceFrameIdentity(run.taskId(),run.runId(),run.epoch(),operation,peer.deviceId(),approval.receivingGrant().targetInstanceId(),targetOperation,new JSONObject(reference),new JSONObject(binding),digest,width,height,captured);
        }
    }
    /** Caller must first persist the exact typed accepted receipt through recordChildAccepted.
     * Parsing a remote descriptor directly is deliberately not an issuance overload. */
    public DeviceImageEvidence deviceImageEvidence(OwnerRouteApproval approval,RunControl original,dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer actualPeer,String originalOperation)throws Exception{
        if(approval==null||approval.issuer!=this||approval.original!=original)throw new SecurityException("没有本原任务的native图像接收范围");approval.checkCurrent(original);approval.receivingGrant().requireTargetPeer(actualPeer);
        synchronized(owner){JSONObject source=childAllocationForOperation(approval.id,originalOperation).getJSONObject("boundWire");if(!approval.run.equals(source.getString("runId"))||approval.epoch!=source.getInt("executorEpoch")||!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(source.getJSONObject("resourceBinding"),approval.resourceBinding()))throw new SecurityException("旧run/另资源的图像不能重新成为本run观察");
            JSONObject stored;try(Cursor row=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND state='receipt'",new String[]{approval.id,approval.run,originalOperation})){if(!row.moveToFirst()||row.isNull(0))throw new SecurityException("没有原SQL已核对的设备图像操作");stored=new JSONObject(row.getString(0));}
            JSONObject accepted=stored.getJSONObject("nativeDeviceAcceptedBinding"),send=stored.getJSONObject("nativeDeviceSend");if(!"write_claimed".equals(send.optString("state"))||!originalOperation.equals(accepted.getString("sourceOperationId"))||!source.getString("argumentsDigest").equals(accepted.getString("operationArgumentsDigest"))||!actualPeer.deviceId().equals(accepted.getString("executorDeviceId")))throw new SecurityException("图像没有原发送/接受/digest证据");
            JSONObject payload=accepted.getJSONObject("verifiedReceipt").getJSONObject("payload"),observation;String tool=source.getJSONObject("arguments").getString("toolName");
            if(tool.equals("desktop.observe"))observation=payload;
            else if(tool.equals("desktop.act"))observation=payload.getJSONObject("desktopReceipt").getJSONObject("afterObservation");
            else if(tool.equals("phone_observe"))observation=payload.getJSONObject("receipt");
            else throw new SecurityException("此原操作没有已声明的视觉结果");
            JSONObject descriptor=observation.getJSONObject(tool.startsWith("desktop.")?"capture":"imageArtifact"),reference=observation.getJSONObject("observationRef");
            dev.pocketengine.features.devices.DeviceProtocol.validateMessage(descriptor);if(!"ArtifactDescriptor".equals(descriptor.getString("messageType"))||!"screenshot".equals(descriptor.getString("kind"))||!actualPeer.deviceId().equals(descriptor.getString("executorDeviceId"))||!approval.receivingGrant().targetInstanceId().equals(descriptor.getString("instanceId"))||!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(descriptor.getJSONObject("resourceBinding"),approval.resourceBinding())||!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(descriptor.getJSONObject("observationRef"),reference)||descriptor.getLong("size")>6*1024*1024)throw new SecurityException("截图不是原已核目标/实例/准确frame/资源/合理字节界限");
            if(tool.startsWith("desktop.")){if(!originalOperation.equals(descriptor.getString("operationId"))||!source.getString("localTaskId").equals(descriptor.getString("taskId"))||!approval.run.equals(descriptor.getString("requestRunId"))||approval.epoch!=descriptor.getInt("requestExecutorEpoch")||descriptor.has("runId")||descriptor.has("executorEpoch"))throw new SecurityException("轻桥接截图不能冒造目标run，也不能换源operation");}
            else if(!accepted.getString("objectId").equals(descriptor.getString("taskId"))||!accepted.getString("runId").equals(descriptor.getString("runId"))||accepted.getLong("executorEpoch")!=descriptor.getLong("executorEpoch")||!payload.getString("localOperationId").equals(descriptor.getString("operationId")))throw new SecurityException("手机截图不是原目标实际task/run/operation");
            JSONObject image=observation.optJSONObject("image"),state=observation.optJSONObject("state");int width=tool.equals("phone_observe")?observation.getInt("imageWidth"):image!=null?image.getInt("width"):state==null?0:state.optInt("screenshot_width"),height=tool.equals("phone_observe")?observation.getInt("imageHeight"):image!=null?image.getInt("height"):state==null?0:state.optInt("screenshot_height");
            if(width<1||height<1||width>16384||height>16384||(long)width*height>16_000_000)throw new SecurityException("原截图缺少准确且有界的像素尺寸");DeviceImageEvidence proof=new DeviceImageEvidence(this,approval,original,actualPeer,originalOperation,descriptor,reference,width,height);requireDeviceImage(proof);return proof;
        }
    }
    private void requireDeviceImage(DeviceImageEvidence proof)throws Exception{
        if(proof==null||proof.issuer!=this)throw new SecurityException("图像证明不属于本原TaskStore");proof.approval.checkCurrent(proof.original);proof.approval.receivingGrant().requireTargetPeer(proof.peer);long now=System.currentTimeMillis();if(proof.original.pauseRevision()!=proof.pauseRevision||now<proof.captured||now>=proof.deadline)throw new SecurityException("原截图过期/暂停/执行代次已变化");synchronized(owner){JSONObject held=childAllocationForOperation(proof.approval.id,proof.operation),wire=held.getJSONObject("boundWire");if(!proof.approval.run.equals(wire.getString("runId"))||!proof.binding.equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(wire.getJSONObject("resourceBinding"))))throw new SecurityException("原截图源范围已变化");try(Cursor row=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND state='receipt'",new String[]{proof.approval.id,proof.approval.run,proof.operation})){if(!row.moveToFirst()||row.isNull(0))throw new SecurityException("原图像SQL证据不再保留");JSONObject saved=new JSONObject(row.getString(0)),payload=saved.getJSONObject("nativeDeviceAcceptedBinding").getJSONObject("verifiedReceipt").getJSONObject("payload"),observed;String tool=wire.getJSONObject("arguments").getString("toolName");if(tool.equals("desktop.observe"))observed=payload;else if(tool.equals("desktop.act"))observed=payload.getJSONObject("desktopReceipt").getJSONObject("afterObservation");else observed=payload.getJSONObject("receipt");if(!proof.descriptor.equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(observed.getJSONObject(tool.startsWith("desktop.")?"capture":"imageArtifact"))))throw new SecurityException("原截图内容身份已经变化");}}
    }

    /** An original-SQL hold, not a JSON receipt or a claim that anything was sent. */
    public static final class ChildReservation {
        private final TaskStoreDeviceTasks issuer;private final String id,run,operation,allocation,intent,authority;private final int epoch;private final long pauseRevision;private final RunControl original;private final boolean known;
        private OwnerRouteApproval sourceApproval;
        private ChildReservation(TaskStoreDeviceTasks issuer,String id,String run,int epoch,RunControl original,String operation,JSONObject held)throws Exception{
            this.issuer=issuer;this.id=id;this.run=run;this.epoch=epoch;this.original=original;this.operation=operation;allocation=held.getString("allocationId");intent=held.getString("intentDigest");known=held.optBoolean("knownOperation");pauseRevision=original.pauseRevision();authority=issuer.parentAuthority(id,run);
        }
        public String allocationId(){return allocation;}public boolean knownOperation(){return known;}
        public void requireCurrent()throws Exception{issuer.requireReservationCurrent(this);}
        public void requireIntent(JSONObject draft)throws Exception{requireCurrent();if(!intent.equals(wireIntentDigest(draft)))throw new SecurityException("签名意图不是原SQL预留的准确内容");}
        public JSONObject view()throws Exception{return new JSONObject().put("allocationId",allocation).put("operationId",operation).put("knownOperation",known).put("budgetReserved",true).put("executionAuthorized",false);}
    }
    /** Reducing-authority scope issued only for this original host control's cancelled task.
     * All candidate ids/resources/digests come from original durable sends, never model JSON. */
    public static final class StopCandidate {
        private final TaskStoreDeviceTasks issuer;private final String id,operation,wire;private final RunControl cancelled;private String ownerClaim;
        private StopCandidate(TaskStoreDeviceTasks issuer,String id,String operation,JSONObject wire,RunControl cancelled){this.issuer=issuer;this.id=id;this.operation=operation;this.wire=dev.pocketengine.features.devices.DeviceProtocol.canonical(wire);this.cancelled=cancelled;}
        public String targetDeviceId()throws Exception{return new JSONObject(wire).getString("targetDeviceId");}public String grantId()throws Exception{return new JSONObject(wire).getString("grantId");}public String operationId(){return operation;}
    }
    /** Explicit owner review of one durable send. This is never an execution admission. */
    public static final class OwnerStopReview {
        private final TaskStoreDeviceTasks issuer; private final OwnerHistoryEvidence history;
        private final String run; private final int epoch;
        private OwnerStopReview(TaskStoreDeviceTasks issuer,OwnerHistoryEvidence history,JSONObject task){this.issuer=issuer;this.history=history;run=task.optString("runId");epoch=task.optInt("coordinatorEpoch");}
    }
    public OwnerStopReview reviewOwnerStop(String id,String operation)throws Exception{
        synchronized(owner){return new OwnerStopReview(this,ownerHistoryEvidence(id,operation),task(id));}
    }
    /** Called only by an explicit native owner click in the exact original operation window. */
    public StopCandidate confirmOwnerStop(OwnerStopReview review)throws Exception{
        synchronized(owner){if(review==null||review.issuer!=this)throw new SecurityException("不是原机主停止审阅");db.beginTransaction();try{
            review.history.requireCurrent();JSONObject current=task(review.history.id);
            if(!review.run.equals(current.optString("runId"))||review.epoch!=current.optInt("coordinatorEpoch"))throw new SecurityException("原任务执行代次已改变，请重新核对");
            String id=review.history.id,operation=review.history.operation;JSONObject receipt;
            try(Cursor row=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND operation_id=?",new String[]{id,operation})){
                if(!row.moveToFirst()||row.isNull(0))throw new SecurityException("没有原发送journal");receipt=new JSONObject(row.getString(0));
            }
            JSONObject sent=receipt.optJSONObject("nativeDeviceSend");if(sent==null||!"write_claimed".equals(sent.optString("state")))throw new SecurityException("没有原TLS发送领取事实，不猜远端操作");
            JSONObject claim=new JSONObject().put("taskId",id).put("operationId",operation).put("runId",review.run).put("executorEpoch",review.epoch).put("wireSha256",dev.pocketengine.features.devices.DeviceProtocol.sha256(review.history.wire.getBytes(StandardCharsets.UTF_8)));
            receipt.put("nativeOwnerStopClaim",claim);ContentValues update=new ContentValues();update.put("receipt",receipt.toString());
            if(db.update("operations",update,"task_id=? AND operation_id=?",new String[]{id,operation})!=1)throw new SecurityException("原操作已改变");
            StopCandidate candidate=new StopCandidate(this,id,operation,review.history.originalWire(),null);candidate.ownerClaim=claim.toString();db.setTransactionSuccessful();return candidate;
        }finally{db.endTransaction();}}
    }
    private static final class StopSend {
        final TaskStoreDeviceTasks issuer;final StopCandidate candidate;final String cancelOperation;final dev.pocketengine.features.devices.PairingCoordinator.OperationManagementRequest management;
        StopSend(TaskStoreDeviceTasks issuer,StopCandidate candidate,String cancelOperation,dev.pocketengine.features.devices.PairingCoordinator.OperationManagementRequest management){this.issuer=issuer;this.candidate=candidate;this.cancelOperation=cancelOperation;this.management=management;}
    }
    public static final class StopDispatch {
        private final StopSend original;private final OutboundSendTicket ticket;private final String state;
        private StopDispatch(StopSend original,OutboundSendTicket ticket,String state){this.original=original;this.ticket=ticket;this.state=state;}
        public boolean knownOperation(){return ticket==null;}public OutboundSendTicket ticket(){if(ticket==null)throw new SecurityException("只查询原cancel ID，不重发");return ticket;}public DeviceTaskEnvelope request(){return original.management.envelope();}
        public JSONObject view()throws Exception{return new JSONObject().put("sourceOperationId",original.candidate.operation).put("operationId",original.cancelOperation).put("targetDeviceId",original.candidate.targetDeviceId()).put("state",state).put("knownOperation",knownOperation()).put("stopped",false).put("retrySubmitted",false).put("allocationAuthorizationRetained",true);}
    }
    public java.util.List<StopCandidate> originalStopCandidates(String id,RunControl cancelled)throws Exception{
        synchronized(owner){if(cancelled==null||!cancelled.isCancelled()||cancelled.hostRunIdentity()==null||!id.equals(cancelled.hostRunIdentity().taskId()))throw new SecurityException("仅原本机已取消的准确任务控制能请求下游止损");JSONObject current=task(id);if(!current.optBoolean("cancelRequested")||!cancelled.matchesHostRun(id,current.optString("runId"),current.optInt("coordinatorEpoch")))throw new SecurityException("原父取消尚未SQL持久或执行代次已变化，未猜新控制器停止");java.util.ArrayList<StopCandidate> result=new java.util.ArrayList<>();try(Cursor rows=db.rawQuery("SELECT l.parent_operation_id,l.wire_json,o.receipt FROM device_child_links l JOIN operations o ON o.task_id=l.parent_task_id AND o.operation_id=l.parent_operation_id WHERE l.parent_task_id=? AND l.wire_json IS NOT NULL ORDER BY o.created_at LIMIT 129",new String[]{id})){while(rows.moveToNext()){if(result.size()>=128)throw new SecurityException("原下游止损超过单批界限，不能假称全部停止");if(rows.isNull(2))continue;JSONObject receipt=new JSONObject(rows.getString(2)),send=receipt.optJSONObject("nativeDeviceSend");if(send!=null&&"write_claimed".equals(send.optString("state")))result.add(new StopCandidate(this,id,rows.getString(0),new JSONObject(rows.getString(1)),cancelled));}}return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(result));}
    }
    private void requireStopCandidate(StopCandidate candidate)throws Exception{
        if(candidate==null||candidate.issuer!=this)throw new SecurityException("不是本原TaskStore止损范围");
        synchronized(owner){JSONObject task=task(candidate.id);
            if(candidate.ownerClaim!=null){JSONObject claim=new JSONObject(candidate.ownerClaim);
                if(!claim.getString("runId").equals(task.optString("runId"))||claim.getInt("executorEpoch")!=task.optInt("coordinatorEpoch"))throw new SecurityException("原机主停止代次已改变");
                try(Cursor row=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND operation_id=?",new String[]{candidate.id,candidate.operation})){
                    if(!row.moveToFirst()||row.isNull(0)||!dev.pocketengine.features.devices.DeviceProtocol.canonical(claim).equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(new JSONObject(row.getString(0)).optJSONObject("nativeOwnerStopClaim"))))throw new SecurityException("原机主持久停止claim已改变");
                }
            }else if(candidate.cancelled==null||!candidate.cancelled.isCancelled()||candidate.cancelled.hostRunIdentity()==null||!candidate.id.equals(candidate.cancelled.hostRunIdentity().taskId()))throw new SecurityException("不是原已取消任务控制");
            JSONObject current=childAllocationForOperation(candidate.id,candidate.operation).getJSONObject("boundWire");if(!candidate.wire.equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(current)))throw new SecurityException("原发送对象已改变，不能改目标/digest停止");}
    }

    public StopDispatch prepareOriginalStop(StopCandidate candidate,dev.pocketengine.features.devices.DeviceIdentity.LocalIdentity local,dev.pocketengine.features.devices.PairingCoordinator.OutgoingGrant target)throws Exception{
        requireStopCandidate(candidate);JSONObject sent=new JSONObject(candidate.wire);if(!local.deviceId().equals(sent.getString("callerDeviceId"))||!candidate.targetDeviceId().equals(target.targetDeviceId())||!candidate.grantId().equals(target.grantId())||!sent.getString("instanceId").equals(target.targetInstanceId())||candidate.ownerClaim!=null&&sent.getLong("authorizationEpoch")!=target.authorizationEpoch())throw new SecurityException("止损只能沿真实原caller/目标实例/grant，不能冒A或猜别的对象");String cancel=localId("device-cancel",candidate.id+":"+candidate.operation);long now=System.currentTimeMillis();var management=target.operationCancel(local,candidate.operation,sent.getString("argumentsDigest"),cancel,sent.getJSONObject("resourceBinding"),now,5000);StopSend stop=new StopSend(this,candidate,cancel,management);DeviceTaskEnvelope request=management.envelope();byte[] wire=dev.pocketengine.features.devices.DeviceProtocol.canonicalBytes(request.json());
        synchronized(owner){db.beginTransaction();try{requireStopCandidate(candidate);try(Cursor known=db.rawQuery("SELECT state FROM operations WHERE task_id=? AND operation_id=? AND tool='native_device_operation_cancel'",new String[]{candidate.id,cancel})){if(known.moveToFirst()){db.setTransactionSuccessful();return new StopDispatch(stop,null,known.getString(0));}}
            try(Cursor rate=db.rawQuery("SELECT COUNT(*) FROM operations WHERE tool='native_device_operation_cancel' AND created_at>?",new String[]{Long.toString(now-60000)})){rate.moveToFirst();if(rate.getInt(0)>=60)throw new SecurityException("原止损独立速率上限；未提交项仍未知，不假停止");}
            JSONObject marker=new JSONObject().put("state","not_claimed").put("wireSha256",dev.pocketengine.features.devices.DeviceProtocol.sha256(wire)).put("wire",request.json()).put("sourceOperationId",candidate.operation);ContentValues row=new ContentValues();row.put("task_id",candidate.id);row.put("run_id",sent.getString("runId"));row.put("operation_id",cancel);row.put("tool","native_device_operation_cancel");row.put("arguments",request.arguments().toString());row.put("state","prepared");row.put("receipt",new JSONObject().put("nativeDeviceStopSend",marker).toString());row.put("created_at",now);db.insertOrThrow("operations",null,row);db.setTransactionSuccessful();return new StopDispatch(stop,new OutboundSendTicket(stop,target,wire),"prepared");
        }finally{db.endTransaction();}}
    }
    private JSONObject stopMarker(StopSend stop)throws Exception{try(Cursor row=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND operation_id=? AND tool='native_device_operation_cancel'",new String[]{stop.candidate.id,stop.cancelOperation})){if(!row.moveToFirst()||row.isNull(0))return null;return new JSONObject(row.getString(0)).optJSONObject("nativeDeviceStopSend");}}
    private byte[] claimStopForWrite(OutboundSendTicket ticket,dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer actualPeer)throws Exception{
        StopSend stop=ticket.stop;synchronized(owner){if(stop==null||stop.issuer!=this||ticket.claimed||ticket.abandoned)throw new SecurityException("原管理发送票据已消费");db.beginTransaction();try{requireStopCandidate(stop.candidate);stop.management.requireTargetPeer(actualPeer,System.currentTimeMillis());JSONObject marker=stopMarker(stop);if(marker==null||!"not_claimed".equals(marker.optString("state"))||!ticket.wireHash.equals(marker.optString("wireSha256"))||!ticket.wireHash.equals(dev.pocketengine.features.devices.DeviceProtocol.sha256(dev.pocketengine.features.devices.DeviceProtocol.canonicalBytes(marker.getJSONObject("wire")))))throw new SecurityException("原管理wire/票据已改变");marker.put("state","write_claimed");ContentValues update=new ContentValues();update.put("state","unknown");update.put("receipt",new JSONObject().put("nativeDeviceStopSend",marker).toString());if(db.update("operations",update,"task_id=? AND operation_id=? AND state='prepared'",new String[]{stop.candidate.id,stop.cancelOperation})!=1)throw new SecurityException("止损原操作不再prepared，不重播");ticket.claimed=true;db.setTransactionSuccessful();return ticket.wire.clone();}finally{db.endTransaction();}}}
    private void abandonStopBeforeWrite(OutboundSendTicket ticket)throws Exception{StopSend stop=ticket.stop;synchronized(owner){if(stop.issuer!=this||ticket.claimed||ticket.abandoned)return;db.beginTransaction();try{JSONObject marker=stopMarker(stop);if(marker!=null&&"not_claimed".equals(marker.optString("state"))&&ticket.wireHash.equals(marker.optString("wireSha256"))){marker.put("state","abandoned_before_write");ContentValues update=new ContentValues();update.put("receipt",new JSONObject().put("nativeDeviceStopSend",marker).toString());if(db.update("operations",update,"task_id=? AND operation_id=? AND state='prepared'",new String[]{stop.candidate.id,stop.cancelOperation})==1)ticket.abandoned=true;}db.setTransactionSuccessful();}finally{db.endTransaction();}}}
    public JSONObject recordOriginalStop(StopDispatch dispatch,dev.pocketengine.features.devices.DeviceCapabilityPort.Reply actual)throws Exception{
        StopSend stop=dispatch.original;if(dispatch.ticket==null||stop.issuer!=this)throw new SecurityException("没有本次原管理票据");stop.management.requireTargetPeer(actual.peer(),System.currentTimeMillis());JSONObject response=dev.pocketengine.features.devices.DeviceProtocol.validateMessage(actual.response());DeviceTaskEnvelope query=stop.management.envelope();if(!query.requestId().equals(response.optString("requestId"))||!query.method().equals(response.optString("method"))||!query.targetDeviceId().equals(response.optString("executorDeviceId"))||!query.instanceId().equals(response.optString("instanceId")))throw new SecurityException("止损回执不是原请求/目标实例");JSONObject typed=response.getJSONObject("result"),cancellation=typed.getJSONObject("cancellation");if(!typed.getBoolean("accepted")||typed.optLong("acceptedAt")<1)throw new SecurityException("止损未被目标原SQL接受，不冒已停止");JSONObject audience=typed.getJSONObject("audience");for(String field:new String[]{"originDeviceId","callerDeviceId","targetDeviceId","instanceId","grantId","authorizationEpoch"})if(!dev.pocketengine.features.devices.DeviceProtocol.canonical(query.json().opt(field)).equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(audience.opt(field))))throw new SecurityException("止损受众不是此原native管理请求");if(!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(query.resourceBinding(),audience.getJSONObject("resourceBinding")))throw new SecurityException("止损受众资源不匹配");JSONObject originalSent=new JSONObject(stop.candidate.wire);if(typed.has("localTaskId")&&"GoalSubmission".equals(originalSent.optString("messageType"))&&!dev.pocketengine.features.devices.DeviceProtocol.receiverTaskId(originalSent).equals(typed.getString("localTaskId")))throw new SecurityException("止损回执目标Goal task被替换");if(!query.operationId().equals(typed.getString("operationId"))||!query.argumentsDigest().equals(typed.getString("argumentsDigest"))||!query.arguments().getString("operationId").equals(cancellation.getString("targetOperationId"))||!query.arguments().getString("argumentsDigest").equals(cancellation.getString("targetArgumentsDigest")))throw new SecurityException("止损回执替换了原targetop/digest");synchronized(owner){JSONObject marker=stopMarker(stop);if(marker==null||!"write_claimed".equals(marker.optString("state")))throw new SecurityException("没有原TLS write前领取事实");ContentValues update=new ContentValues();update.put("state","receipt");update.put("receipt",new JSONObject().put("nativeDeviceStopSend",marker).put("operationReceipt",typed).toString());db.update("operations",update,"task_id=? AND operation_id=? AND state='unknown'",new String[]{stop.candidate.id,stop.cancelOperation});}return dispatch.view().put("state","receipt").put("requested",cancellation.getBoolean("requested")).put("stopped",cancellation.getBoolean("stopped")).put("managementOnly",true);
    }

    /** Only the original TaskStore can issue this after hold + final-wire CAS. Queueing is not dispatch.
     * The actual TLS writer must claim once immediately before its first write; never reconstruct from JSON. */
    @FunctionalInterface public interface NativeWriteGuard { void requireCurrent() throws Exception; }
    public static final class OutboundSendTicket {
        private volatile NativeWriteGuard nativeWriteGuard;
        /** Additive native check, installed before transport; never replaces original SQL authority. */
        public synchronized void installNativeWriteGuard(NativeWriteGuard guard){if(nativeWriteGuard!=null||claimed||abandoned)throw new SecurityException("原发送门不可替换");nativeWriteGuard=java.util.Objects.requireNonNull(guard);}

        private final ChildReservation reservation;private final StopSend stop;private final TaskStoreDesktopViewer.Send viewer;private final TaskStoreDeviceTasks issuer;private final dev.pocketengine.features.devices.PairingCoordinator.OutgoingGrant target;private final byte[] wire;private final String wireHash;private volatile boolean claimed,abandoned;
        private OutboundSendTicket(ChildReservation reservation,dev.pocketengine.features.devices.PairingCoordinator.OutgoingGrant target,byte[] wire){this.reservation=reservation;this.stop=null;this.viewer=null;this.issuer=reservation.issuer;this.target=target;this.wire=wire.clone();wireHash=dev.pocketengine.features.devices.DeviceProtocol.sha256(wire);}
        private OutboundSendTicket(StopSend stop,dev.pocketengine.features.devices.PairingCoordinator.OutgoingGrant target,byte[] wire){reservation=null;this.stop=stop;this.viewer=null;issuer=stop.issuer;this.target=target;this.wire=wire.clone();wireHash=dev.pocketengine.features.devices.DeviceProtocol.sha256(wire);}
        private OutboundSendTicket(TaskStoreDeviceTasks issuer,TaskStoreDesktopViewer.Send viewer){this.issuer=issuer;this.viewer=viewer;reservation=null;stop=null;target=viewer.grant();wire=viewer.bytes();wireHash=dev.pocketengine.features.devices.DeviceProtocol.sha256(wire);}
        public int size(){return wire.length;}
        public byte[] claimForWrite(dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer actualPeer)throws Exception{NativeWriteGuard guard=nativeWriteGuard;if(guard!=null)guard.requireCurrent();return viewer!=null?viewer.claim(actualPeer):stop==null?issuer.claimForWrite(this,actualPeer):issuer.claimStopForWrite(this,actualPeer);}
        /** Fail closed if the original SQL cannot be read. A failed claim must not be treated as abandoned. */
        public boolean isClaimed(){if(viewer!=null)return viewer.claimed();synchronized(issuer.owner){try{JSONObject marker=stop==null?issuer.sendMarker(reservation):issuer.stopMarker(stop);return claimed||marker==null||!"not_claimed".equals(marker.optString("state"));}catch(Exception unknown){return true;}}}
        public void abandonBeforeWrite()throws Exception{if(viewer!=null){viewer.abandon();return;}if(stop==null)issuer.abandonBeforeWrite(this);else issuer.abandonStopBeforeWrite(this);}
    }
    public TaskStoreDesktopViewer.Session openDesktopViewer(dev.pocketengine.features.devices.DeviceIdentity.LocalIdentity local,dev.pocketengine.features.devices.PairingCoordinator.OutgoingGrant grant,JSONObject binding,TaskStoreDesktopViewer.Current current)throws Exception{
        return new TaskStoreDesktopViewer(db,owner).open(local,grant,binding,current);
    }
    public OutboundSendTicket desktopViewerTicket(TaskStoreDesktopViewer.Send send){if(send==null||!send.belongsTo(owner))throw new SecurityException("桌面发送不属于本TaskStore");return new OutboundSendTicket(this,send);}
    TaskStoreDeviceTasks(SQLiteDatabase originalDb, TaskStore originalOwner, Access nativeAccess) {
        db = java.util.Objects.requireNonNull(originalDb); owner = java.util.Objects.requireNonNull(originalOwner);
        access = java.util.Objects.requireNonNull(nativeAccess);
    }
    public static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS device_inbound(caller_device_id TEXT NOT NULL,remote_operation_id TEXT NOT NULL,arguments_digest TEXT NOT NULL,local_task_id TEXT NOT NULL,local_operation_id TEXT,method TEXT NOT NULL,grant_id TEXT NOT NULL,authorization_epoch INTEGER NOT NULL,envelope_json TEXT NOT NULL,accepted_at INTEGER NOT NULL,PRIMARY KEY(caller_device_id,remote_operation_id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS device_inbound_task ON device_inbound(local_task_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_child_budget(parent_task_id TEXT NOT NULL,parent_run_id TEXT NOT NULL,parent_epoch INTEGER NOT NULL,parent_operation_id TEXT NOT NULL,allocation_id TEXT NOT NULL,scope_digest TEXT NOT NULL,target_device_id TEXT NOT NULL,arguments_digest TEXT NOT NULL,tools_reserved INTEGER NOT NULL,models_reserved INTEGER NOT NULL,state TEXT NOT NULL,child_binding TEXT,receipt_json TEXT,created_at INTEGER NOT NULL,PRIMARY KEY(parent_task_id,parent_operation_id),UNIQUE(parent_task_id,allocation_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS device_child_links(parent_task_id TEXT NOT NULL,parent_operation_id TEXT NOT NULL,allocation_id TEXT NOT NULL,intent_digest TEXT NOT NULL,wire_digest TEXT,wire_json TEXT,PRIMARY KEY(parent_task_id,parent_operation_id))");
    }
    /** Called after the original TaskStore recovery. Never recreates controls or redispatches an operation. */
    public static void recover(SQLiteDatabase db) {
        db.execSQL("UPDATE device_child_budget SET state='unknown' WHERE state IN ('submitted','cancel_requested')");
        // The existing recover() owns prepared -> unknown and run interruption in original tables.
    }
    /** Startup only, inside ORIGINAL TaskStore.recover's transaction and BEFORE its generic
     * prepared->unknown sweep. A ticket can exist only after bindReservedWire atomically saved
     * a frozen wire AND nativeDeviceSend. A reserve-only link/hold is not a dispatch: keep it
     * intact while closing only this pristine prepared intent. An existing unknown, receipt,
     * bound wire/claim or inbound mapping is never reclassified. No quota/provider counter is credited and no operation is replayed. */
    void recoverNeverBoundPrepared()throws Exception{
        synchronized(owner){if(!db.inTransaction())throw new SecurityException("设备未发送恢复必须在原启动恢复事务内");
            try(Cursor rows=db.rawQuery("SELECT o.operation_id,o.task_id,o.run_id,o.tool,o.arguments FROM operations o WHERE o.state='prepared' AND o.receipt IS NULL AND o.tool IN ('device_invoke_tool','device_delegate_goal') AND EXISTS(SELECT 1 FROM runs r WHERE r.run_id=o.run_id AND r.task_id=o.task_id) AND NOT EXISTS(SELECT 1 FROM device_inbound i WHERE i.local_operation_id=o.operation_id OR i.remote_operation_id=o.operation_id)",null)){
                while(rows.moveToNext()){
                    String operation=rows.getString(0),id=rows.getString(1),run=rows.getString(2);JSONObject arguments;
                    try{arguments=new JSONObject(rows.getString(4));dev.pocketengine.features.devices.DeviceProtocol.deviceId(arguments.opt("targetDeviceId"),"targetDeviceId");}catch(Exception insufficientOriginalIntent){continue;}
                    JSONObject allocationProof=neverBoundAllocationProof(id,run,operation,arguments);if(allocationProof==null)continue;
                    JSONObject proof=new JSONObject().put("format","pocketworld.native-device-never-bound-recovery.v1").put("operationId",operation).put("taskId",id).put("runId",run).put("originalTool",rows.getString(3)).put("argumentsFingerprint",TaskStore.capabilityFingerprint(arguments)).put("originalState","prepared").put("originalReceiptAbsent",true).put("childLinkAbsent",allocationProof.getBoolean("childLinkAbsent")).put("childAllocationAbsent",allocationProof.getBoolean("childAllocationAbsent")).put("allocationEvidence",allocationProof).put("inboundMappingAbsent",true).put("frozenWireAbsent",true).put("sendTicketClaimAbsent",true).put("startupDispatchClosed",true);
                    JSONObject receipt=new JSONObject().put("ok",false).put("code","device_not_dispatched_recovered").put("status","cancelled").put("operationId",operation).put("accepted",false).put("effectState","not_dispatched").put("notDispatchedConfirmed",true).put("nativeRecoveryProof",proof).put("retrySubmitted",false).put("quotaRefunded",false).put("allocationAuthorizationRetained",!allocationProof.getBoolean("childAllocationAbsent")).put("message","原设备意图尚未绑定wire或领取发送票据；启动恢复已关闭此意图，既有预留保留，未重发、未退额度");ContentValues value=new ContentValues();value.put("state","receipt");value.put("receipt",receipt.toString());
                    if(db.update("operations",value,"operation_id=? AND task_id=? AND run_id=? AND state='prepared' AND receipt IS NULL",new String[]{operation,id,run})==1)access.event(task(id),new JSONObject().put("type","device_original_intent_not_dispatched").put("operationId",operation).put("runId",run).put("proof",proof).put("quotaRefunded",false));
                }
            }
        }
    }
    /** reserveChild commits hold+unsigned-intent link together; signing is outside SQL but the
     * signed wire cannot leave via the ONLY effect transport until bind commits and issues its
     * private ticket. Never refund the reservation, even when this individual intent never bound. */
    private JSONObject neverBoundAllocationProof(String id,String run,String operation,JSONObject args)throws Exception{
        String allocation=null,anchor=null;boolean linked=false,held=false;
        try(Cursor links=db.rawQuery("SELECT parent_task_id,allocation_id,wire_digest,wire_json,intent_digest FROM device_child_links WHERE parent_operation_id=?",new String[]{operation})){if(links.moveToFirst()){if(!id.equals(links.getString(0))||!links.isNull(2)||!links.isNull(3)||links.isNull(4)||!links.getString(4).matches("[a-f0-9]{64}"))return null;allocation=links.getString(1);linked=true;if(links.moveToNext())return null;}}
        String query=linked?"SELECT parent_task_id,parent_run_id,parent_operation_id,allocation_id,arguments_digest,state,child_binding,receipt_json,target_device_id FROM device_child_budget WHERE parent_task_id=? AND allocation_id=?":"SELECT parent_task_id,parent_run_id,parent_operation_id,allocation_id,arguments_digest,state,child_binding,receipt_json,target_device_id FROM device_child_budget WHERE parent_operation_id=?";
        try(Cursor row=db.rawQuery(query,linked?new String[]{id,allocation}:new String[]{operation})){if(row.moveToFirst()){held=true;if(!id.equals(row.getString(0))||!args.getString("targetDeviceId").equals(row.getString(8)))return null;anchor=row.getString(2);allocation=row.getString(3);
                if(operation.equals(anchor)){if(!run.equals(row.getString(1))||!row.getString(4).isEmpty()||!"reserved".equals(row.getString(5))||!row.isNull(6))return null;if(!row.isNull(7)){JSONObject prior;try{prior=new JSONObject(row.getString(7));}catch(Exception incompleteEvidence){return null;}for(String key:dev.pocketengine.features.devices.DeviceProtocol.objectKeys(prior))if(!key.equals("nativeSourceRoute"))return null;}
                    try(Cursor other=db.rawQuery("SELECT 1 FROM device_child_links WHERE parent_task_id=? AND allocation_id=? AND (wire_json IS NOT NULL OR wire_digest IS NOT NULL) LIMIT 1",new String[]{id,allocation})){if(other.moveToFirst())return null;}}
                else if(!linked)return null;if(row.moveToNext())return null;
            }else if(linked)return null;}
        return new JSONObject().put("childLinkAbsent",!linked).put("childAllocationAbsent",!held).put("unboundIntentLinkPresent",linked).put("allocationAuthorizationRetained",held).put("allocationId",allocation==null?JSONObject.NULL:allocation).put("anchorOperationId",anchor==null?JSONObject.NULL:anchor).put("otherOperationsUntouched",true).put("quotaRefunded",false);
    }
    private static String localId(String prefix, String value) {
        return UUID.nameUUIDFromBytes((prefix + ":" + value).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String remoteOperation(DeviceTaskEnvelope request) {
        return request.operationId().isEmpty() ? "read_" + request.requestId() : request.operationId();
    }
    private static JSONObject duplicate(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private JSONObject task(String id) throws Exception {
        JSONObject value = access.read(id);
        if (value == null) throw new SecurityException("没有该设备任务的原记录");
        return value;
    }
    public static boolean deviceTask(JSONObject task) {
        return task != null && task.optJSONObject("origin") != null && "paired_device".equals(task.optJSONObject("origin").optString("kind"));
    }
    private static void requireDevice(JSONObject task) {
        if (!deviceTask(task) || task.has("executionScope") || task.optJSONObject("deviceExecutionScope") == null
                || !task.optString("actorId").startsWith("paired_device:") || !task.optString("spaceId").startsWith("device:"))
            throw new SecurityException("不是原生设备来源的独立范围，不能借私人授权执行");
    }
    /** Atomic task + dedup commit. Calling this a second time is QUERY ONLY, including after ACK loss. */
    public JSONObject accept(DeviceExecutionAdmission admission) throws Exception {
        admission.checkCurrent();
        synchronized (owner) {
            DeviceTaskEnvelope request = admission.request(); String remote = remoteOperation(request);
            String digest = request.argumentsDigest().isEmpty()
                    ? TaskStore.capabilityFingerprint(request.json()) : request.argumentsDigest();
            boolean committed=false;db.beginTransaction();
            try {
                try (Cursor known = db.rawQuery("SELECT arguments_digest,local_task_id FROM device_inbound WHERE caller_device_id=? AND remote_operation_id=?", new String[]{request.callerDeviceId(), remote})) {
                    if (known.moveToFirst()) {
                        if (!digest.equals(known.getString(0))) throw new SecurityException("原操作身份的参数、来源或设备已改变");
                        JSONObject result = snapshot(request.callerDeviceId(), remote).put("knownOperation", true);
                        db.setTransactionSuccessful(); committed=true; return result;
                    }
                }
                owner.requireExecutionEnabled();
                JSONObject envelope = request.json(); String session = envelope.getString("localTaskId");
                String id = dev.pocketengine.features.devices.DeviceProtocol.receiverTaskId(request);
                JSONObject current = access.read(id);
                if (current == null) {
                    requireGrantRemaining(admission);
                    requireSourceShareRemaining(admission.scope(),null);
                    String goal = admission.kind() == DeviceExecutionAdmission.Kind.GOAL
                            ? request.arguments().getString("goal") : "已配对设备请求在本机执行明确手机工具";
                    long now = System.currentTimeMillis();
                    JSONObject scope = admission.scope().put("toolsUsed", 0).put("modelCallsUsed", 0).put("revision", 1);
                    current = new JSONObject().put("id", id).put("goal", goal)
                            .put("kind", admission.executionMode().equals("capabilities") ? "assistant" : "phone")
                            .put("executionMode", admission.executionMode()).put("actorId", admission.principalId()).put("spaceId", admission.spaceId())
                            .put("origin", admission.origin()).put("rootTaskId", envelope.getString("rootTaskId"))
                            .put("remoteParentTaskId", envelope.optString("parentTaskId"))
                            .put("deviceHistoryRetainedUntil",dev.pocketengine.features.devices.DeviceProtocol.MAX_SAFE_INTEGER)
                            .put("deviceNativeInstanceId",request.instanceId())
                            .put("deviceExecutionKind", admission.kind().name()).put("deviceExecutionScope", scope)
                            .put("coordinatorEpoch", 0).put("status", "queued").put("summary", "已持久接受，等待本机执行准入")
                            .put("createdAt", now).put("updatedAt", now).put("events", new JSONArray()).put("evidence", new JSONObject())
                            .put("feedback", "").put("cancelRequested", false).put("conversationId", "");
                    if (admission.kind() == DeviceExecutionAdmission.Kind.GOAL)
                        current.put("deviceContextSnapshot", dev.pocketengine.features.devices.DeviceContextSnapshot.validate(request.arguments().optJSONObject("contextSnapshot"), request.originDeviceId()));
                    access.write(current);
                } else {
                    requireDevice(current);narrowSessionByNativeGrantRevision(current,admission); requireSameSession(current, admission);
                    if (admission.kind() == DeviceExecutionAdmission.Kind.GOAL)
                        throw new SecurityException("原目标已接受；只能查询原操作，不能换身份重提");
                    if (current.optBoolean("cancelRequested") || current.optString("status").matches("completed|cancelled|failed|interrupted|reconciling"))
                        throw new SecurityException("原设备工具会话已结束或需核对，不能用新操作绕过");
                }
                try (Cursor queued = db.rawQuery("SELECT COUNT(*) FROM device_inbound WHERE local_task_id=? AND local_operation_id IS NULL", new String[]{id})) {
                    if (queued.moveToFirst() && queued.getInt(0) >= 32) throw new SecurityException("该设备原执行队列已满，未接受新操作");
                }
                ContentValues row = new ContentValues(); row.put("caller_device_id", request.callerDeviceId()); row.put("remote_operation_id", remote);
                row.put("arguments_digest", digest); row.put("local_task_id", id); row.put("method", request.method());
                row.put("grant_id", request.grantId()); row.put("authorization_epoch", request.authorizationEpoch());
                row.put("envelope_json", request.json().toString()); row.put("accepted_at", System.currentTimeMillis());
                db.insertOrThrow("device_inbound", null, row);
                access.event(current, new JSONObject().put("type", "device_accepted").put("remoteOperationId", remote)
                        .put("callerDeviceId", request.callerDeviceId()).put("originDeviceId", request.originDeviceId())
                        .put("argumentsDigest", digest).put("executionKind", admission.kind().name()));
                admission.checkCurrent();
                JSONObject result = snapshot(request.callerDeviceId(), remote).put("knownOperation", false);
                db.setTransactionSuccessful(); committed=true; return result;
            } finally { db.endTransaction();if(committed&&admission.kind()==DeviceExecutionAdmission.Kind.DEVICE_TOOLS){
                JSONObject accepted=access.read(dev.pocketengine.features.devices.DeviceProtocol.receiverTaskId(admission.request()));if(accepted!=null&&accepted.getJSONObject("origin").getLong("authorizationEpoch")==admission.request().authorizationEpoch()){String run=accepted.optString("runId");Live retained=live.get(run);if(retained!=null)live.put(run,new Live(retained.original,admission));}
            } }
        }
    }
    private void requireSameSession(JSONObject task, DeviceExecutionAdmission admission) throws Exception {
        JSONObject original = task.getJSONObject("origin"), current = admission.origin(), scope = task.getJSONObject("deviceExecutionScope");
        for (String key : new String[]{"originDeviceId", "callerDeviceId", "targetDeviceId", "grantId", "authorizationEpoch", "rootTaskId", "remoteLocalTaskId"})
            if (!dev.pocketengine.features.devices.DeviceProtocol.canonical(original.opt(key)).equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(current.opt(key)))) throw new SecurityException("设备会话来源或授权代次已变化");
        if (!task.getString("executionMode").equals(admission.executionMode()) || !task.getString("deviceExecutionKind").equals(admission.kind().name())
                || admission.deadline() != scope.getLong("deadline") || admission.toolBudget() != scope.getInt("toolLimit")
                || admission.modelBudget() != scope.getInt("modelCallLimit")
                || !admission.request().json().optString("capabilityVersion",dev.pocketengine.features.devices.DeviceProtocol.VERSION).equals(scope.optString("capabilityVersion",dev.pocketengine.features.devices.DeviceProtocol.VERSION))
                || !admission.request().budget().getString("allocationId").equals(scope.getString("allocationId"))) throw new SecurityException("设备会话不能扩展原位置、期限或预算");
        if (!TaskStore.capabilityFingerprint(new JSONObject().put("tools", scope.getJSONArray("tools")).put("resources", scope.getJSONArray("resources")))
                .equals(TaskStore.capabilityFingerprint(new JSONObject().put("tools", admission.toolNames()).put("resources", admission.resources()))))
            throw new SecurityException("设备会话工具或资源范围已变化");
        JSONObject source=scope.optJSONObject("sourceShare");var nativeSource=admission.sourceShare();if((source==null)!=(nativeSource==null)||source!=null&&!dev.pocketengine.features.devices.DeviceProtocol.canonical(source).equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(nativeSource.record())))throw new SecurityException("同allocation不能换A sourceShare/证明修订，原source池不被新消息替换");
    }
    /** A fresh native Permission for a later LOCAL owner grant revision can only narrow the SAME
     * accepted tool allocation. This never rewrites prior wire/operations, clears spent, or refreshes unknown. */
    private void narrowSessionByNativeGrantRevision(JSONObject current,DeviceExecutionAdmission admission)throws Exception{
        JSONObject origin=current.getJSONObject("origin"),next=admission.origin(),old=current.getJSONObject("deviceExecutionScope");long before=origin.getLong("authorizationEpoch"),after=next.getLong("authorizationEpoch");if(before==after)return;
        if(admission.kind()!=DeviceExecutionAdmission.Kind.DEVICE_TOOLS||!"DEVICE_TOOLS".equals(current.optString("deviceExecutionKind"))||after<=before||current.optBoolean("cancelRequested")||current.optString("status").matches("completed|failed|cancelled|interrupted|reconciling"))throw new SecurityException("原allocation不可借新owner版本复活或重置");
        for(String field:new String[]{"originDeviceId","callerDeviceId","targetDeviceId","grantId","rootTaskId","remoteLocalTaskId"})if(!dev.pocketengine.features.devices.DeviceProtocol.canonical(origin.opt(field)).equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(next.opt(field))))throw new SecurityException("收窄不允许换原受众或任务关联");
        JSONObject narrowed=admission.scope();if(!old.getString("allocationId").equals(narrowed.getString("allocationId"))||!old.getString("mode").equals(narrowed.getString("mode"))||!old.getString("capabilityVersion").equals(narrowed.getString("capabilityVersion"))||!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(old.getJSONObject("resourceBinding"),narrowed.getJSONObject("resourceBinding"))
                ||narrowed.getLong("toolLimit")>old.getLong("toolLimit")||narrowed.getLong("modelCallLimit")>old.getLong("modelCallLimit")||narrowed.getLong("deadline")>old.getLong("deadline")||!jsonSubset(narrowed.getJSONArray("tools"),old.getJSONArray("tools"))||!jsonSubset(narrowed.getJSONArray("resources"),old.getJSONArray("resources")))throw new SecurityException("原allocation仅可有依据单调收窄；提额/延期/换资源需另行原native增量准入");
        JSONObject priorSource=old.optJSONObject("sourceShare"),nextSource=narrowed.optJSONObject("sourceShare");if((priorSource==null)!=(nextSource==null)||priorSource!=null&&(!sourcePoolId(priorSource).equals(sourcePoolId(nextSource))||nextSource.getLong("toolBudget")>priorSource.getLong("toolBudget")||nextSource.getLong("modelBudget")>priorSource.getLong("modelBudget")||nextSource.getLong("deadline")>priorSource.getLong("deadline")))throw new SecurityException("原A sourceShare不可被本机grant版本替换或扩额");
        narrowed.put("toolsUsed",old.getLong("toolsUsed")).put("modelCallsUsed",old.getLong("modelCallsUsed")).put("revision",Math.addExact(old.getLong("revision"),1)).put("narrowedFromAuthorizationEpoch",before).put("narrowedAt",System.currentTimeMillis());
        if(priorSource!=null)narrowed.put("localToolsUsed",old.optLong("localToolsUsed")).put("localModelCallsUsed",old.optLong("localModelCallsUsed"));if(old.optBoolean("grantAllocationSettled"))narrowed.put("grantAllocationSettled",true);
        String run=current.optString("runId");origin.put("authorizationEpoch",after);narrowed.put("origin",origin);current.put("origin",origin).put("deviceExecutionScope",narrowed);
        if(!run.isEmpty()){JSONObject grant=access.grant(run);grant.put("authorizationEpoch",after).put("expiresAt",Math.min(grant.getLong("expiresAt"),narrowed.getLong("deadline"))).put("tools",narrowed.getJSONArray("tools")).put("deviceScopeRevision",narrowed.getLong("revision")).put("remainingOperations",Math.min(grant.getInt("remainingOperations"),Math.max(0,narrowed.getInt("toolLimit")-narrowed.getInt("toolsUsed"))));ContentValues row=new ContentValues();row.put("grant_json",grant.toString());db.update("runs",row,"task_id=? AND run_id=?",new String[]{current.getString("id"),run});
            db.execSQL("UPDATE leases SET expires_at=MIN(expires_at,?) WHERE run_id=?",new Object[]{narrowed.getLong("deadline"),run});
        }
        access.write(current);access.event(current,new JSONObject().put("type","device_allocation_narrowed").put("allocationId",narrowed.getString("allocationId")).put("previousAuthorizationEpoch",before).put("authorizationEpoch",after).put("toolsUsedRetained",narrowed.getLong("toolsUsed")).put("modelCallsUsedRetained",narrowed.getLong("modelCallsUsed")).put("unknownRefunded",false));
    }
    private static boolean jsonSubset(JSONArray narrower,JSONArray original){java.util.Set<String> allowed=new java.util.HashSet<>();for(int i=0;i<original.length();i++)allowed.add(dev.pocketengine.features.devices.DeviceProtocol.canonical(original.opt(i)));for(int i=0;i<narrower.length();i++)if(!allowed.contains(dev.pocketengine.features.devices.DeviceProtocol.canonical(narrower.opt(i))))return false;return true;}
    /** Owner PairGrant is a lifetime finite pool, not a budget refill per root/allocation/attempt. */
    private void requireGrantRemaining(DeviceExecutionAdmission admission) throws Exception {
        long tools = 0, models = 0;
        try (Cursor rows = db.rawQuery("SELECT DISTINCT local_task_id FROM device_inbound WHERE caller_device_id=? AND grant_id=?", new String[]{admission.request().callerDeviceId(), admission.request().grantId()})) {
            while (rows.moveToNext()) {
                JSONObject existing = task(rows.getString(0)); requireDevice(existing); JSONObject scope = existing.getJSONObject("deviceExecutionScope");
                if (admission.request().budget().getString("allocationId").equals(scope.getString("allocationId"))) throw new SecurityException("同一allocation不能通过新task/root/run复制预算");
                boolean settled = scope.optBoolean("grantAllocationSettled");
                tools = Math.addExact(tools, scope.getLong(settled ? "toolsUsed" : "toolLimit"));
                models = Math.addExact(models, scope.getLong(settled ? "modelCallsUsed" : "modelCallLimit"));
            }
        }
        if (admission.toolBudget() > admission.grantToolBudget() - tools || admission.modelBudget() > admission.grantModelBudget() - models)
            throw new SecurityException("本机owner grant生命周期预算已使用或预留；换root/task/allocation不补额度，unknown不退款");
    }
    /** Native B-owner diagnostic over this SAME receiving-grant lifetime pool. Limits are not remaining.
     * Every unsettled allocation holds its full limit, even if offline/cancel-requested/unknown. */
    public JSONObject grantBudgetStatus(String callerDeviceId,String grantId)throws Exception{
        synchronized(owner){JSONObject grant;long expires;boolean revoked;int epoch;
            try(Cursor row=db.rawQuery("SELECT body_json,authorization_epoch,expires_at,revoked FROM device_grants WHERE grant_id=? AND peer_device_id=?",new String[]{grantId,callerDeviceId})){if(!row.moveToFirst())throw new SecurityException("没有该本机接收grant");grant=new JSONObject(row.getString(0));epoch=row.getInt(1);expires=row.getLong(2);revoked=row.getInt(3)!=0;}
            long usedTools=0,usedModels=0,allocatedTools=0,allocatedModels=0;int allocations=0,unsettled=0,unknown=0;
            try(Cursor rows=db.rawQuery("SELECT DISTINCT local_task_id FROM device_inbound WHERE caller_device_id=? AND grant_id=?",new String[]{callerDeviceId,grantId})){while(rows.moveToNext()){
                JSONObject task=task(rows.getString(0));requireDevice(task);JSONObject scope=task.getJSONObject("deviceExecutionScope");boolean settled=scope.optBoolean("grantAllocationSettled");allocations++;
                usedTools=Math.addExact(usedTools,scope.getLong("toolsUsed"));usedModels=Math.addExact(usedModels,scope.getLong("modelCallsUsed"));allocatedTools=Math.addExact(allocatedTools,scope.getLong(settled?"toolsUsed":"toolLimit"));allocatedModels=Math.addExact(allocatedModels,scope.getLong(settled?"modelCallsUsed":"modelCallLimit"));
                if(!settled)unsettled++;if(owner.hasUnresolved(task.getString("id"))||task.optString("status").matches("reconciling|interrupted")||task.optBoolean("cancelRequested")&&!settled)unknown++;
            }}
            return new JSONObject().put("grantId",grantId).put("callerDeviceId",callerDeviceId).put("authorizationEpoch",epoch).put("expiresAt",expires).put("state",revoked?"revoked":expires<=System.currentTimeMillis()?"expired":"active").put("budgetState","known")
                    .put("toolLimit",grant.getLong("toolBudget")).put("modelLimit",grant.getLong("modelBudget")).put("toolsActuallyUsed",usedTools).put("modelsActuallyUsed",usedModels).put("toolsReservedOrUsed",allocatedTools).put("modelsReservedOrUsed",allocatedModels)
                    .put("toolsAvailableForNewAllocations",Math.max(0,grant.getLong("toolBudget")-allocatedTools)).put("modelsAvailableForNewAllocations",Math.max(0,grant.getLong("modelBudget")-allocatedModels))
                    .put("allocationCount",allocations).put("unsettledAllocations",unsettled).put("unknownOrUnconfirmedAllocations",unknown).put("unknownRefunded",false).put("readOnly",true).put("executionAuthorized",false);
        }
    }
    /** Dedicated native admission into existing runs/leases. Never calls or weakens private executionScopeTask. */
    public JSONObject startAttempt(String id, DeviceExecutionAdmission admission, RunControl original) throws Exception {
        return startAttempt(id,admission,original,null);
    }
    public JSONObject startGoalAttempt(String id,DeviceExecutionAdmission admission,RunControl original,GoalModelApproval localOwnerModel)throws Exception{
        if(localOwnerModel==null||localOwnerModel.review.issuer!=this||localOwnerModel.review.admission!=admission||!id.equals(localOwnerModel.review.id)||!localOwnerModel.review.confirmed||localOwnerModel.consumed)throw new SecurityException("设备目标必须有B本机owner准确选择模型的原生证明");
        return startAttempt(id,admission,original,localOwnerModel);
    }
    private JSONObject startAttempt(String id,DeviceExecutionAdmission admission,RunControl original,GoalModelApproval localOwnerModel)throws Exception{
        admission.checkCurrent();
        synchronized (owner) {
            owner.requireExecutionEnabled();
            String boundRun=null;boolean successful=false;
            db.beginTransaction(); try {
                JSONObject current = task(id); requireDevice(current); requireSameSession(current, admission);
                if (original == null || original.hasHostRun() || original.isCancelled() || original.isPaused()) throw new SecurityException("需要新的原生控制器");
                if (admission.kind()==DeviceExecutionAdmission.Kind.GOAL&&localOwnerModel==null)throw new SecurityException("外来PairGrant不等于B本机owner模型准入");
                if (!(current.optString("status").equals("queued")||localOwnerModel!=null&&current.optString("status").equals("awaiting_user")) || !current.optString("runId").isEmpty() || owner.hasUnresolved(id))
                    throw new SecurityException("原设备任务只能首次准入；恢复必须先核对旧执行");
                JSONObject scope = current.getJSONObject("deviceExecutionScope"); long now = System.currentTimeMillis(), expires = scope.getLong("deadline");
                if (expires <= now || admission.kind()==DeviceExecutionAdmission.Kind.DEVICE_TOOLS&&scope.getInt("toolLimit")<1 || admission.kind() == DeviceExecutionAdmission.Kind.GOAL && scope.getInt("modelCallLimit") < 1)
                    throw new SecurityException("设备执行条件或预算不足");
                String mode=admission.executionMode(); boolean phone = !mode.equals("capabilities");
                if(phone)try{owner.requirePhoneLeaseFree(mode);}
                catch(IllegalStateException held){throw new SecurityException("手机仍有原执行租约；到期不等于旧执行器已停止",held);}
                String run = UUID.randomUUID().toString();boundRun=run; int epoch = current.optInt("coordinatorEpoch") + 1;
                JSONObject permission = new JSONObject().put("principalId", admission.principalId()).put("spaceId", admission.spaceId())
                        .put("nativePairedDevice", true).put("grantId", admission.request().grantId()).put("authorizationEpoch", admission.request().authorizationEpoch())
                        .put("mode", admission.executionMode()).put("expiresAt", expires).put("tools", admission.toolNames())
                        .put("remainingOperations", scope.getInt("toolLimit") - scope.getInt("toolsUsed"))
                        .put("stepCeiling", Math.max(1,Math.min(400, scope.getInt("toolLimit")))).put("deviceScopeRevision", scope.getLong("revision"));
                ContentValues row = new ContentValues(); row.put("run_id", run); row.put("task_id", id); row.put("epoch", epoch); row.put("status", "running");
                row.put("checkpoint", new JSONObject().put("steps", 0).put("messages", new JSONArray()).toString()); row.put("grant_json", permission.toString()); row.put("created_at", now); row.put("updated_at", now); db.insertOrThrow("runs", null, row);
                if (phone) current.put("leaseResource",owner.acquirePhoneLease(id,mode,run,epoch,expires)); else current.remove("leaseResource");
                current.put("deviceAcceptedRunId",run).put("deviceAcceptedExecutorEpoch",epoch).put("runId", run).put("coordinatorEpoch", epoch).put("status", "preparing").put("stepCeiling", permission.getInt("stepCeiling")); access.write(current);
                if(localOwnerModel!=null){current.put("deviceModelSelection",localOwnerModel.publicView());access.write(current);}
                admission.checkCurrent();
                JSONObject accepted=duplicate(current);
                original.bindHostRun(id, run, epoch); live.put(run, new Live(original, admission));
                if(localOwnerModel!=null){localOwnerModel.consumed=true;localOwnerModel.original=original;localOwnerModel.run=run;localOwnerModel.epoch=epoch;goalModels.put(run,localOwnerModel);}
                db.setTransactionSuccessful();successful=true;return accepted;
            } finally {
                boolean committed=false;
                try{db.endTransaction();committed=successful;}
                finally{if(!committed&&boundRun!=null){live.remove(boundRun);goalModels.remove(boundRun);if(original!=null&&original.hasHostRun())original.cancel();if(localOwnerModel!=null&&localOwnerModel.original==original){localOwnerModel.original=null;localOwnerModel.run=null;localOwnerModel.epoch=0;}}}
            }
        }
    }
    /** Main TaskStore fence calls this only for persisted paired_device origin. No caller-supplied grant. */
    public void fence(String id, String run, int epoch, RunControl control, boolean dispatch) throws Exception {
        synchronized (owner) {
            JSONObject current = task(id); requireDevice(current);
            if (!run.equals(current.optString("runId")) || epoch != current.optInt("coordinatorEpoch")) throw new SecurityException("设备执行代次已变化");
            if (!dispatch) return; // Original receipts remain writable after cancel/revocation; never authorize an effect.
            owner.requireExecutionEnabled();
            Live binding = live.get(run);
            if (binding == null || control != binding.original || !control.matchesHostRun(id, run, epoch) || control.isCancelled() || control.isPaused()
                    || current.optBoolean("cancelRequested") || !current.optString("status").matches("preparing|running")) throw new SecurityException("没有当前设备任务的原生控制");
            binding.admission.checkCurrent(); JSONObject permission = access.grant(run), scope = current.getJSONObject("deviceExecutionScope");
            if (!permission.optBoolean("nativePairedDevice") || !permission.optString("principalId").equals(current.getString("actorId"))
                    || !permission.optString("spaceId").equals(current.getString("spaceId")) || permission.optLong("expiresAt") <= System.currentTimeMillis()
                    || permission.optLong("deviceScopeRevision") != scope.getLong("revision")) throw new SecurityException("设备原生授权已失效");
            if(!current.getString("executionMode").equals("capabilities"))owner.requirePhoneLeaseCurrent(id,run,epoch);
        }
    }
    public void checkCapability(String id, String run, int epoch, String tool) throws Exception {
        synchronized (owner) {
            Live binding = live.get(run); if (binding == null) throw new SecurityException("设备任务没有活动准入");
            fence(id, run, epoch, binding.original, true);
            JSONArray tools = task(id).getJSONObject("deviceExecutionScope").getJSONArray("tools");
            boolean allowed = false; for (int i = 0; i < tools.length(); i++) if (tool.equals(tools.getString(i))) allowed = true;
            if (!allowed) throw new SecurityException("该设备工具不在原授权范围");
        }
    }
    /** Called from original operation/inference transactions, including parent-local work. */
    public void debit(JSONObject current, String run, boolean model) throws Exception {
        synchronized (owner) {
            requireDevice(current); Live binding = live.get(run); if (binding == null) throw new SecurityException("设备执行已失去原生准入");
            fence(current.getString("id"), run, current.getInt("coordinatorEpoch"), binding.original, true);
            JSONObject scope = current.getJSONObject("deviceExecutionScope"); String used = model ? "modelCallsUsed" : "toolsUsed", limit = model ? "modelCallLimit" : "toolLimit";
            if (scope.getInt(used) >= scope.getInt(limit) - heldBudget(current.getString("id"), run, model)) throw new SecurityException("父剩余预算已由本地或子任务使用/预留；未知不退款");
            scope.put(used, scope.getInt(used) + 1); current.put("deviceExecutionScope", scope);
            if(scope.optJSONObject("sourceShare")!=null){
                SourcePool pool=sourcePool(scope.getJSONObject("sourceShare"),null);long local=scope.optLong(model?"localModelCallsUsed":"localToolsUsed");
                if(System.currentTimeMillis()>=pool.deadline||local>=sourceLocalLimit(scope,model)||pool.spent(model)>=pool.limit(model))throw new SecurityException("A签发的本机准确sourceShare累计额度/期限已用尽，换root/run/operation不刷新");
                scope.put(model?"localModelCallsUsed":"localToolsUsed",Math.addExact(local,1));
            }
        }
    }
    private static long sourceLocalLimit(JSONObject scope,boolean model)throws Exception{
        long total=scope.getLong(model?"modelCallLimit":"toolLimit");JSONObject share=scope.optJSONObject("sourceShare");return share==null?total:Math.min(total,share.getLong(model?"modelBudget":"toolBudget"));
    }
    private static String sourcePoolId(JSONObject source)throws Exception{return TaskStore.capabilityFingerprint(new JSONObject().put("issuerDeviceId",source.getString("issuerDeviceId")).put("sourceAllocationId",source.getString("sourceAllocationId")).put("shareId",source.getString("shareId")));}
    private record SourcePool(long tools,long models,long toolsSpent,long modelsSpent,long toolsHeld,long modelsHeld,long deadline){long limit(boolean model){return model?models:tools;}long spent(boolean model){return model?modelsSpent:toolsSpent;}long held(boolean model){return model?modelsHeld:toolsHeld;}}
    /** This is a projection of ORIGINAL accepted task scopes/counters, never a second budget store.
     * A's disjoint target/resource shares provide cross-target conservation; this SQL is local only. */
    private SourcePool sourcePool(JSONObject source,String excludeTask)throws Exception{
        String key=sourcePoolId(source);long tools=source.getLong("toolBudget"),models=source.getLong("modelBudget"),toolsSpent=0,modelsSpent=0,toolsHeld=0,modelsHeld=0,deadline=source.getLong("deadline");
        try(Cursor rows=db.rawQuery("SELECT DISTINCT local_task_id FROM device_inbound",null)){while(rows.moveToNext()){
            String id=rows.getString(0);JSONObject scope=task(id).getJSONObject("deviceExecutionScope"),other=scope.optJSONObject("sourceShare");if(other==null||!key.equals(sourcePoolId(other)))continue;
            if(!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(source.getJSONObject("resourceBinding"),other.getJSONObject("resourceBinding"))||!source.getString("targetDeviceId").equals(other.getString("targetDeviceId")))throw new SecurityException("原sourceShare准确目标/资源绑定损坏");
            tools=Math.min(tools,other.getLong("toolBudget"));models=Math.min(models,other.getLong("modelBudget"));deadline=Math.min(deadline,other.getLong("deadline"));
            long localTools=scope.optLong("localToolsUsed"),localModels=scope.optLong("localModelCallsUsed");toolsSpent=Math.addExact(toolsSpent,localTools);modelsSpent=Math.addExact(modelsSpent,localModels);
            if(!id.equals(excludeTask)){boolean settled=scope.optBoolean("grantAllocationSettled");toolsHeld=Math.addExact(toolsHeld,settled?localTools:sourceLocalLimit(scope,false));modelsHeld=Math.addExact(modelsHeld,settled?localModels:sourceLocalLimit(scope,true));}
        }}return new SourcePool(tools,models,toolsSpent,modelsSpent,toolsHeld,modelsHeld,deadline);
    }
    private void requireSourceShareRemaining(JSONObject scope,String excludeTask)throws Exception{
        JSONObject source=scope.optJSONObject("sourceShare");if(source==null)return;SourcePool pool=sourcePool(source,excludeTask);
        if(source.getLong("toolBudget")>pool.tools||source.getLong("modelBudget")>pool.models||source.getLong("deadline")>pool.deadline)throw new SecurityException("A sourceShare只可保持或单调收窄，不能借新签名/epoch恢复旧份额");
        if(System.currentTimeMillis()>=pool.deadline||sourceLocalLimit(scope,false)>pool.tools-pool.toolsHeld||sourceLocalLimit(scope,true)>pool.models-pool.modelsHeld)throw new SecurityException("A sourceShare生命周期份额已使用/预留，换allocation/root/重连不补额度；unknown不退款");
    }
    public int heldBudget(String id, String run, boolean model) {
        synchronized (owner) { try (Cursor rows = db.rawQuery("SELECT COALESCE(SUM(" + (model ? "models_reserved" : "tools_reserved") + "),0) FROM device_child_budget WHERE parent_task_id=? AND state!='settled'", new String[]{id})) { return rows.moveToFirst() ? rows.getInt(0) : 0; } }
    }
    /** Bounded local-owner workspace projection of the ORIGINAL allocation/link records.
     * This is not a peer endpoint; it never returns wire_json, contexts, paths or credentials. */
    public JSONObject workspaceTaskLinks(int offset,int limit)throws Exception{return workspaceTaskLinks(null,offset,limit);}
    public JSONObject childOverview(String parentTaskId,int limit)throws Exception{
        synchronized(owner){JSONObject parent=task(parentTaskId);if(!deviceTask(parent)&&!("owner".equals(parent.optString("actorId"))&&"private".equals(parent.optString("spaceId"))))throw new SecurityException("不是本机owner或原设备父任务");}
        return workspaceTaskLinks(parentTaskId,0,limit);
    }
    private JSONObject workspaceTaskLinks(String parent,int offset,int limit)throws Exception{
        if(offset<0||offset>100000||limit<1||limit>100)throw new SecurityException("设备任务关联分页越界");
        synchronized(owner){JSONArray links=new JSONArray();boolean more=false;
            String query="SELECT parent_task_id,parent_operation_id,allocation_id,target_device_id,state,tools_reserved,models_reserved,child_binding,receipt_json,created_at FROM device_child_budget"+(parent==null?"":" WHERE parent_task_id=?")+" ORDER BY created_at DESC,parent_task_id,parent_operation_id LIMIT ? OFFSET ?";
            String[] args=parent==null?new String[]{Integer.toString(limit+1),Integer.toString(offset)}:new String[]{parent,Integer.toString(limit+1),Integer.toString(offset)};
            try(Cursor row=db.rawQuery(query,args)){while(row.moveToNext()){
                if(links.length()==limit){more=true;break;}JSONObject link=new JSONObject().put("parentTaskId",row.getString(0)).put("operationId",row.getString(1)).put("allocationId",row.getString(2)).put("targetDeviceId",row.getString(3)).put("holdState",row.getString(4)).put("toolsReserved",row.getInt(5)).put("modelsReserved",row.getInt(6)).put("createdAt",row.getLong(9));
                if(!row.isNull(7)){JSONObject accepted=new JSONObject(row.getString(7)),safe=new JSONObject();for(String field:new String[]{"executorDeviceId","instanceId","acceptedObjectKind","objectId","targetOperationId","jobId","localTaskId","runId","executorEpoch","acceptedAt","argumentsDigest","parentTaskId","parentOperationId"})if(accepted.has(field))safe.put(field,accepted.get(field));link.put("childBinding",safe);}
                if(!row.isNull(8)){JSONObject actual=new JSONObject(row.getString(8)),safe=new JSONObject();for(String field:new String[]{"executorDeviceId","instanceId","childTaskId","childRunId","executorEpoch","status","terminal","stopped","hasUnresolved","toolsUsed","modelCallsUsed","argumentsDigest"})if(actual.has(field))safe.put(field,actual.get(field));link.put("terminalReceipt",safe);}
                try(Cursor count=db.rawQuery("SELECT COUNT(*) FROM device_child_links WHERE parent_task_id=? AND allocation_id=?",new String[]{row.getString(0),row.getString(2)})){count.moveToFirst();link.put("originalOperationCount",count.getLong(0));}
                link.put("transportIsNotTaskState",true).put("cancelRequestedIsNotStopped",true);links.put(link);
            }}
            return new JSONObject().put("links",links).put("nextOffset",offset+links.length()).put("hasMore",more).put("readOnly",true);
        }
    }
    public void authorizeTools(String id, String run, JSONArray requested) throws Exception {
        synchronized (owner) {
            JSONObject current = task(id); requireDevice(current); JSONArray bounded = new JSONArray(), allowed = current.getJSONObject("deviceExecutionScope").getJSONArray("tools");
            for (int i = 0; i < requested.length(); i++) for (int j = 0; j < allowed.length(); j++) if (requested.getString(i).equals(allowed.getString(j))) { bounded.put(requested.getString(i)); break; }
            JSONObject grant = access.grant(run); grant.put("tools", bounded); ContentValues row = new ContentValues(); row.put("grant_json", grant.toString()); db.update("runs", row, "task_id=? AND run_id=?", new String[]{id, run});
        }
    }
    /** The actual local module captured these bytes AFTER native admission. The wire supplies no path,
     * task document or authority; exact project/checkout/runtime must already be in its local PairGrant. */
    public void bindProjectContext(String id, DeviceExecutionAdmission admission, RunControl original, JSONObject selected) throws Exception {
        admission.checkCurrent();
        synchronized (owner) {
            JSONObject current = task(id); requireDevice(current); requireSameSession(current, admission);
            String run = current.getString("runId"); Live binding = live.get(run);
            if (binding == null || binding.original != original || binding.admission != admission || admission.kind() != DeviceExecutionAdmission.Kind.GOAL
                    || !original.matchesHostRun(id, run, current.getInt("coordinatorEpoch")) || !"preparing".equals(current.optString("status"))
                    || !"capabilities".equals(current.getString("executionMode")) || owner.get(id, false).getJSONArray("operations").length() != 0) throw new SecurityException("没有原设备项目首次准入");
            JSONObject resource = current.getJSONObject("deviceExecutionScope").getJSONObject("resourceBinding");
            if (!resource.getString("projectId").equals(selected.getString("projectId")) || !resource.getString("checkoutId").equals(selected.getString("checkoutId"))
                    || !resource.getString("runtimeId").equals(selected.getString("runtime")) || !selected.getString("runtime").matches("termux|managed_process_v1")
                    || !selected.getString("initialSourceDigest").matches("[a-f0-9]{64}") || !"pocketworld.development-context.v1".equals(selected.getString("format"))) throw new SecurityException("项目不是owner准确授权的本机副本和运行位置");
            JSONObject clean = new JSONObject(); for (String field : new String[]{"format","projectId","checkoutId","runtime","initialSourceDigest","clientMessageId"}) clean.put(field, selected.get(field));
            clean.put("filesAreUntrusted", true).put("authorityAdded", false);
            if (current.has("developmentContext")) throw new SecurityException("原设备项目不能补挂或切换");
            current.put("developmentContext", clean); access.write(current);
        }
    }
    /** Original native SQL project association only; read/receipt access is not dispatch permission. */
    void requireProjectTask(JSONObject current) throws Exception {
        requireDevice(current); JSONObject scope = current.getJSONObject("deviceExecutionScope"), selected = current.getJSONObject("developmentContext"), resource = scope.getJSONObject("resourceBinding");
        if (!"GOAL".equals(scope.getString("executionKind")) || !"capabilities".equals(current.getString("executionMode")) || !"assistant".equals(current.getString("kind"))
                || !resource.getString("projectId").equals(selected.getString("projectId")) || !resource.getString("checkoutId").equals(selected.getString("checkoutId"))
                || !resource.getString("runtimeId").equals(selected.getString("runtime"))) throw new SecurityException("项目不是该paired设备原SQL准确范围");
    }
    void requireProjectPlan(String id, String run, int epoch, JSONObject plan) throws Exception {
        JSONObject current = task(id); requireProjectTask(current); JSONObject selected = current.getJSONObject("developmentContext");
        if (!run.equals(current.getString("runId")) || epoch != current.getInt("coordinatorEpoch") || !selected.getString("projectId").equals(plan.getString("projectId"))
                || !selected.getString("checkoutId").equals(plan.getString("checkoutId")) || !selected.getString("runtime").equals(plan.optString("runtimeId", "termux"))) throw new SecurityException("方案越过原设备项目/run边界");
        JSONArray steps = plan.getJSONArray("steps"); for (int i=0;i<steps.length();i++) {
            JSONObject step=steps.getJSONObject(i), payload=step.getJSONObject("payload");
            if ("owned_service".equals(payload.optString("runMode")) || "pty".equals(payload.optString("terminal")) || plan.optString("intent").matches("git|freeze_package|collect")) throw new SecurityException("该设备版本没有受控服务/PTY/Git/成果保存的独立范围；未派发");
        }
    }
    JSONObject projectOriginalRequest(JSONObject binding, JSONObject row) throws Exception {
        String id=binding.getString("taskId"),run=binding.getString("runId");int epoch=binding.getInt("coordinatorEpoch");
        JSONObject current=task(id),args=row.getJSONObject("arguments"),plan=args.getJSONObject("plan");requireProjectPlan(id,run,epoch,plan);
        JSONObject route=row.getJSONObject("receipt").getJSONObject("projectExecution"),exact=dev.pocketengine.features.local.project.ProjectDevelopment.exact(plan,dev.pocketengine.features.local.project.ProjectBuildPlan.step(plan,args.getString("stepId")));
        for(String key:new String[]{"taskId","runId","coordinatorEpoch","operationId"})if(!dev.pocketengine.features.devices.DeviceProtocol.canonical(binding.get(key)).equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(route.get(key))))throw new SecurityException("设备项目原SQL路由不同");
        for(String key:new String[]{"command","projectId","checkoutId","sourceDigest","planDigest","stepId","intent","requestDigest","runtimeId","runtimeIdentity"})if(exact.has(key)&&!dev.pocketengine.features.devices.DeviceProtocol.canonical(exact.get(key)).equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(route.opt(key))))throw new SecurityException("设备项目原方案路由不同");
        if(!"native_host_operation".equals(route.getString("authority"))||!binding.getString("operationId").equals(route.getString("jobId")))throw new SecurityException("缺少设备原操作绑定");
        JSONObject result=new JSONObject(args.toString()).put("checkoutId",current.getJSONObject("developmentContext").getString("checkoutId")).put("binding",duplicate(binding));
        JSONObject journal=row.getJSONObject("journalArguments");if(journal.has("planRef"))result.put("planRef",journal.getString("planRef")).put("planDigest",journal.getString("planDigest"));return result;
    }
    /** Native historical result review: no execution Permission, model route or new budget. */
    public static final class GoalResultReview {
        private final TaskStoreDeviceTasks issuer;private final String id,run,digest;private final int epoch;private final JSONObject result;private boolean used;
        private GoalResultReview(TaskStoreDeviceTasks issuer,String id,String run,int epoch,JSONObject result)throws Exception{this.issuer=issuer;this.id=id;this.run=run;this.epoch=epoch;this.result=duplicate(result);digest=TaskStore.capabilityFingerprint(result);}
        public String taskId(){return id;}public String runId(){return run;}public int executorEpoch(){return epoch;}
        public JSONObject view()throws Exception{return duplicate(result).put("taskId",id).put("runId",run).put("executorEpoch",epoch).put("resultDigest",digest).put("historicalReviewOnly",true).put("newRun",false).put("newEffectsAllowed",false).put("remoteAuthorizationRevoked",false);}
    }
    /** Called only with the opaque completion produced by SAME original DeviceGoalSession,
     * after the Runner has returned/closed its ports. Saving a tool's JSON result cannot mint it. */
    public void recordGoalIdle(dev.pocketengine.features.devices.DeviceGoalSession.Finished actual)throws Exception{
        java.util.Objects.requireNonNull(actual);var session=actual.originalSession();RunControl original=session.originalControl();var run=original.hostRunIdentity();
        synchronized(owner){JSONObject current=task(run.taskId());requireDevice(current);Live nativeRun=live.get(run.runId());if(nativeRun==null||nativeRun.original!=original||nativeRun.admission!=session.admission()||goalModels.get(run.runId())!=session.modelApproval()||!original.matchesHostRun(current.getString("id"),current.getString("runId"),current.getInt("coordinatorEpoch")))throw new SecurityException("完成回执不是本原B目标实际执行");actual.requireCurrent();JSONObject result=actual.result();if(!"needs_confirmation".equals(result.getString("status")))throw new SecurityException("只有原Runner待确认结果走本入口");
            result.put("runId",run.runId()).put("executorEpoch",run.epoch()).put("nativeRunnerExited",true).put("nativeDispatchClosed",true).put("recordedAt",System.currentTimeMillis()).put("closed",false);current.put("deviceGoalResult",result);access.write(current);owner.status(run.taskId(),"needs_confirmation",result.getString("summary"));
        }
    }
    public GoalResultReview reviewGoalResult(String id,String run,int epoch)throws Exception{
        synchronized(owner){JSONObject current=task(id);requireDevice(current);JSONObject result=current.optJSONObject("deviceGoalResult");if(!"GOAL".equals(current.optString("deviceExecutionKind"))||result==null||result.optBoolean("closed")||!result.optBoolean("nativeRunnerExited")||!result.optBoolean("nativeDispatchClosed")||!run.equals(current.optString("runId"))||epoch!=current.optInt("coordinatorEpoch")||!run.equals(result.optString("runId"))||epoch!=result.optInt("executorEpoch"))throw new SecurityException("没有该原run已退出的待核结果");Live active=live.get(run);if(active!=null&&!active.original.isPaused()&&!active.original.isCancelled())throw new SecurityException("原Runner仍可派发，不能核旧结果后继续原调用");return new GoalResultReview(this,id,run,epoch,result);}
    }
    public JSONObject confirmGoalResult(GoalResultReview review,boolean completed)throws Exception{
        if(review==null||review.issuer!=this)throw new SecurityException("不是本原TaskStore的结果审阅");synchronized(owner){db.beginTransaction();try{GoalResultReview current=reviewGoalResult(review.id,review.run,review.epoch);if(review.used||!review.digest.equals(current.digest))throw new SecurityException("旧结果审阅已消费或summary/evidence已变化");JSONObject task=task(review.id),result=duplicate(current.result);result.put("closed",true).put("ownerVerified",completed).put("reviewedAt",System.currentTimeMillis());task.put("deviceGoalResult",result);access.write(task);JSONObject evidence=duplicate(result.getJSONObject("evidence")).put("nativeDeviceGoalOwnerReview",new JSONObject().put("resultDigest",review.digest).put("completed",completed).put("sameRun",true).put("remoteAuthorizationRevoked",false));String status=task.optBoolean("cancelRequested")?"cancelled":completed?"completed":"cancelled";
            owner.endAttempt(review.id,review.run,status,completed?"B主人已核对原run结果；仅关闭本任务，不表示远端授权已撤销":"B主人已关闭原run，未确认业务完成；已有远端效果与授权保留",evidence);review.used=true;db.setTransactionSuccessful();return task(review.id).put("resultReviewed",true).put("newRun",false).put("newEffectsAllowed",false).put("remoteAuthorizationRevoked",false);
        }finally{db.endTransaction();}}}
    /** Local reducing-authority review over ORIGINAL accepted SQL, usable after process death.
     * This is deliberately NOT an Admission/Permission and cannot start or resume anything. */
    public static final class PendingGoalCancellation {
        private final TaskStoreDeviceTasks issuer;private final String id,frozen;private boolean used;
        private PendingGoalCancellation(TaskStoreDeviceTasks issuer,String id,JSONObject facts){this.issuer=issuer;this.id=id;frozen=dev.pocketengine.features.devices.DeviceProtocol.canonical(facts);}
        public String taskId(){return id;}
        public JSONObject view()throws Exception{synchronized(issuer.owner){JSONObject facts=issuer.pendingGoalFacts(id);if(used||!frozen.equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(facts)))throw new SecurityException("原未启动目标已变化，旧取消审阅失效");return new JSONObject().put("taskId",id).put("goal",facts.getString("goal")).put("status",facts.getString("status")).put("originDeviceId",facts.getString("originDeviceId")).put("callerDeviceId",facts.getString("callerDeviceId")).put("acceptedAt",facts.getLong("acceptedAt")).put("localOnly",true).put("runStarted",false).put("newExecutionAuthority",false);}}
    }
    private JSONObject pendingGoalFacts(String id)throws Exception{
        JSONObject current=task(id);requireDevice(current);if(!"GOAL".equals(current.optString("deviceExecutionKind"))||!current.optString("runId").isEmpty()||current.optBoolean("cancelRequested")||!current.optString("status").matches("queued|awaiting_user|interrupted")||owner.hasUnresolved(id))throw new SecurityException("不是本机原SQL已接受且从未开始的待取消目标");
        try(Cursor runs=db.rawQuery("SELECT 1 FROM runs WHERE task_id=? LIMIT 1",new String[]{id})){if(runs.moveToFirst())throw new SecurityException("原目标曾有真实run，不能冒尚未开始或猜新epoch");}
        JSONObject scope=current.getJSONObject("deviceExecutionScope"),origin=current.getJSONObject("origin");if(scope.optLong("toolsUsed")!=0||scope.optLong("modelCallsUsed")!=0||current.optInt("coordinatorEpoch")!=0)throw new SecurityException("原目标已有执行/用量证据，不能按未启动取消");
        try(Cursor row=db.rawQuery("SELECT envelope_json,accepted_at,arguments_digest FROM device_inbound WHERE local_task_id=? AND method='task.submitGoal'",new String[]{id})){if(!row.moveToFirst())throw new SecurityException("原Goal接受记录缺失");JSONObject wire=new JSONObject(row.getString(0));long acceptedAt=row.getLong(1);String digest=row.getString(2);if(row.moveToNext())throw new SecurityException("原Goal接受身份不唯一");if(!id.equals(dev.pocketengine.features.devices.DeviceProtocol.receiverTaskId(wire))||!digest.equals(wire.optString("argumentsDigest"))||!current.getString("goal").equals(wire.getJSONObject("arguments").getString("goal"))||!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(scope.getJSONObject("resourceBinding"),wire.getJSONObject("resourceBinding")))throw new SecurityException("原Goal接受身份/资源/摘要已变化");for(String field:new String[]{"originDeviceId","callerDeviceId","targetDeviceId","grantId","authorizationEpoch"})if(!dev.pocketengine.features.devices.DeviceProtocol.canonical(origin.opt(field)).equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(wire.opt(field))))throw new SecurityException("原Goal受众不能由JSON替换");
            return new JSONObject().put("id",id).put("goal",current.getString("goal")).put("status",current.getString("status")).put("revision",current.optLong("deviceStateRevision")).put("originDeviceId",origin.getString("originDeviceId")).put("callerDeviceId",origin.getString("callerDeviceId")).put("acceptedAt",acceptedAt).put("operationId",wire.getString("operationId")).put("argumentsDigest",digest).put("scopeFingerprint",TaskStore.capabilityFingerprint(scope));
        }
    }
    public boolean hasPendingGoalCancellation(String id){synchronized(owner){try{pendingGoalFacts(id);return true;}catch(Exception notPending){return false;}}}
    public PendingGoalCancellation reviewPendingGoalCancellation(String id)throws Exception{synchronized(owner){return new PendingGoalCancellation(this,id,pendingGoalFacts(id));}}
    public JSONObject cancelPendingGoal(PendingGoalCancellation review)throws Exception{
        if(review==null||review.issuer!=this)throw new SecurityException("取消审阅不是本原TaskStore签发");synchronized(owner){db.beginTransaction();try{JSONObject facts=pendingGoalFacts(review.id);if(review.used||!review.frozen.equals(dev.pocketengine.features.devices.DeviceProtocol.canonical(facts)))throw new SecurityException("原未启动目标已变化或取消审阅已消费");JSONObject current=task(review.id);current.put("cancelRequested",true);access.write(current);JSONObject evidence=new JSONObject().put("nativePendingGoalCancelled",true).put("notDispatched",true).put("localOnly",true).put("originalOperationId",facts.getString("operationId")).put("argumentsDigest",facts.getString("argumentsDigest")).put("acceptedAt",facts.getLong("acceptedAt")).put("quotaRefunded",false);owner.finish(review.id,"cancelled","B主人已取消原SQL已接受但从未启动的目标；没有恢复Admission或执行",evidence);access.event(task(review.id),new JSONObject().put("type","device_pending_goal_cancelled_by_local_owner").put("originalOperationId",facts.getString("operationId")).put("quotaRefunded",false));review.used=true;db.setTransactionSuccessful();return task(review.id).put("stopped",true).put("notDispatched",true).put("localOnly",true).put("newRun",false).put("quotaRefunded",false);}finally{db.endTransaction();}}
    }
    /** B-local owner may stop a SQL-accepted Goal that never acquired a run/model. Revoked or
     * expired execution authority does not prevent this local reducing-authority operation. */
    public JSONObject cancelPendingGoal(String id,DeviceExecutionAdmission exactAccepted)throws Exception{
        synchronized(owner){JSONObject current=task(id);requireDevice(current);requireSameSession(current,exactAccepted);if(exactAccepted.kind()!=DeviceExecutionAdmission.Kind.GOAL||!current.optString("runId").isEmpty()||!current.optString("status").matches("queued|awaiting_user")||current.optBoolean("cancelRequested"))throw new SecurityException("不是原尚未启动的已接受目标");db.beginTransaction();try{current.put("cancelRequested",true);access.write(current);owner.finish(id,"cancelled","B主人已取消尚未开始的已接受目标；零模型、零工具派发",new JSONObject().put("nativePendingGoalCancelled",true).put("notDispatched",true));db.setTransactionSuccessful();return task(id).put("stopped",true).put("notDispatched",true).put("newRun",false);}finally{db.endTransaction();}}
    }
    /** Same original SQL terminal transaction only. Definite queued/non-written work can
     * close without refunding its allocation; the CAS can never overwrite a claimed write. */
    public void closeUndispatchedChildrenForTerminal(String id,String closingRun)throws Exception{
        synchronized(owner){JSONObject current=task(id);requireDevice(current);JSONObject result=current.optJSONObject("deviceGoalResult");Live retained=live.get(closingRun);boolean dispatchClosed=result!=null&&closingRun.equals(result.optString("runId"))&&result.optBoolean("closed")&&result.optBoolean("nativeDispatchClosed")||retained!=null&&retained.original.isCancelled();if(!dispatchClosed)return;
            try(Cursor rows=db.rawQuery("SELECT o.operation_id,o.receipt FROM operations o JOIN device_child_links l ON l.parent_task_id=o.task_id AND l.parent_operation_id=o.operation_id WHERE o.task_id=? AND o.state='prepared'",new String[]{id})){while(rows.moveToNext()){JSONObject receipt=rows.isNull(1)?new JSONObject():new JSONObject(rows.getString(1)),send=receipt.optJSONObject("nativeDeviceSend");if(send!=null&&"write_claimed".equals(send.optString("state")))continue;if(send==null)send=new JSONObject().put("format","pocketworld.native-device-send.v1");send.put("state","abandoned_before_write").put("nativeDispatchClosed",true);receipt.put("nativeDeviceSend",send).put("ok",false).put("notDispatched",true).put("allocationAuthorizationRetained",true);ContentValues update=new ContentValues();update.put("state","receipt");update.put("receipt",receipt.toString());db.update("operations",update,"task_id=? AND operation_id=? AND state='prepared'",new String[]{id,rows.getString(0)});}}
        }
    }
    /** Terminal checks are independent of retained authorization allocations. A receipt-less
     * write, accepted live job, or still-sendable queued ticket cannot become 'stopped'. */
    public boolean hasUnstoppedChildOperations(String id,String closingRun)throws Exception{
        synchronized(owner){JSONObject parent=task(id),result=parent.optJSONObject("deviceGoalResult");boolean closing=result!=null&&closingRun.equals(result.optString("runId"))&&result.optBoolean("nativeDispatchClosed");
            try(Cursor rows=db.rawQuery("SELECT l.parent_operation_id,l.wire_json,o.state,o.receipt,b.receipt_json,b.state,b.parent_operation_id,o.run_id FROM device_child_links l LEFT JOIN operations o ON o.task_id=l.parent_task_id AND o.operation_id=l.parent_operation_id JOIN device_child_budget b ON b.parent_task_id=l.parent_task_id AND b.allocation_id=l.allocation_id WHERE l.parent_task_id=?",new String[]{id})){while(rows.moveToNext()){
                if(rows.isNull(2))return true;JSONObject receipt=rows.isNull(3)?new JSONObject():new JSONObject(rows.getString(3)),send=receipt.optJSONObject("nativeDeviceSend");String state=rows.getString(2);
                JSONObject recovery=receipt.optJSONObject("nativeRecoveryProof");if("receipt".equals(state)&&rows.isNull(1)&&"device_not_dispatched_recovered".equals(receipt.optString("code"))&&receipt.optBoolean("notDispatchedConfirmed")&&"not_dispatched".equals(receipt.optString("effectState"))&&send==null&&recovery!=null&&"pocketworld.native-device-never-bound-recovery.v1".equals(recovery.optString("format"))&&recovery.optBoolean("startupDispatchClosed")&&id.equals(recovery.optString("taskId"))&&rows.getString(0).equals(recovery.optString("operationId"))&&rows.getString(7).equals(recovery.optString("runId")))continue;
                if(closing&&send!=null&&"abandoned_before_write".equals(send.optString("state")))continue;
                if(state.equals("prepared")&&closing&&(send==null||!"write_claimed".equals(send.optString("state"))))continue; // Same SQL end transaction closes the native dispatch fence before another write claim.
                JSONObject binding=receipt.optJSONObject("nativeDeviceAcceptedBinding"),actual=binding==null?null:binding.optJSONObject("verifiedReceipt");if(!"receipt".equals(state)||actual==null||!actual.optBoolean("accepted")||!"reported".equals(actual.optString("effectState")))return true;
                JSONObject body=actual.getJSONObject("payload"),settlement=body.optJSONObject("executionSettlement"),process=body.optJSONObject("processCleanupProof");if(settlement!=null&&rows.getString(0).equals(settlement.optString("operationId"))&&settlement.optBoolean("terminalVerified")&&settlement.optBoolean("adapterFinished")&&settlement.optBoolean("noFurtherDispatchForOperation")&&settlement.optString("proofScope").matches("single_gui_operation|synchronous_desktop_observation|desktop_resource_control")&&actual.optString("status").matches("succeeded|failed|cancelled|rejected"))continue;if(body.optBoolean("notStartedConfirmed")&&actual.optString("status").matches("succeeded|failed|cancelled|rejected")||process!=null&&process.optBoolean("processGroupStoppedVerified")&&body.optBoolean("dispatchFenceFinal"))continue;if(body.optBoolean("terminal")&&body.optBoolean("stopped")&&actual.optString("status").matches("succeeded|failed|cancelled|rejected"))continue;
                JSONObject allocation=rows.isNull(4)?null:new JSONObject(rows.getString(4));if(allocation!=null&&rows.getString(0).equals(rows.getString(6))&&allocation.optBoolean("terminal")&&allocation.optBoolean("stopped")&&rows.getString(5).matches("settled|committed_allocation"))continue;
                if(!rows.isNull(1)){JSONObject wire=new JSONObject(rows.getString(1)),invocation=wire.optJSONObject("arguments");if("ToolInvocation".equals(wire.optString("messageType"))&&invocation!=null&&"read".equals(invocation.optString("effect"))&&"succeeded".equals(actual.optString("status")))continue;}
                return true;
            }}return false;
        }
    }

    /** Explicit same-run continuation. No counter, deadline, scope, lease or grant is renewed. */
    public void resumeGoal(String id, DeviceExecutionAdmission admission, RunControl original) throws Exception {
        admission.checkCurrent();
        synchronized (owner) {
            JSONObject current=requireResumableGoal(id,admission,original);
            JSONObject requested=current.optJSONObject("deviceResumeRequest");
            if(requested!=null&&"pending_owner_confirmation".equals(requested.optString("state")))throw new SecurityException("请在原设备任务页核对并确认这次外部恢复请求");
            current.put("status","running").put("summary","原设备目标继续，仍使用原run和剩余预算");access.write(current);original.resume();
        }
    }
    /** Existing resume checks shared by the local Resume button and an exact owner decision. */
    private JSONObject requireResumableGoal(String id,DeviceExecutionAdmission admission,RunControl original)throws Exception{
            JSONObject current=task(id);requireDevice(current);requireSameSession(current,admission);String run=current.getString("runId");Live binding=live.get(run);
            if(binding==null||binding.original!=original||binding.admission!=admission||admission.kind()!=DeviceExecutionAdmission.Kind.GOAL||original.isCancelled()
                    ||!original.matchesHostRun(id,run,current.getInt("coordinatorEpoch"))||current.optBoolean("cancelRequested")||!current.optString("status").matches("preparing|running|pausing|paused|awaiting_user")||owner.hasUnresolved(id))throw new SecurityException("旧设备执行已停止、未决或没有原控制器");
            JSONArray approvals=current.optJSONArray("approvals");for(int i=0;approvals!=null&&i<approvals.length();i++)if(run.equals(approvals.getJSONObject(i).optString("runId"))&&"pending".equals(approvals.getJSONObject(i).optString("state")))throw new SecurityException("请先处理原准确审批，不从继续绕过");
            JSONObject scope=current.getJSONObject("deviceExecutionScope");if(scope.getInt("modelCallsUsed")>=scope.getInt("modelCallLimit")-heldBudget(id,run,true)||scope.getLong("deadline")<=System.currentTimeMillis())throw new SecurityException("原目标模型预算或期限已耗尽；不补额度");
            if(scope.optJSONObject("sourceShare")!=null){SourcePool pool=sourcePool(scope.getJSONObject("sourceShare"),null);if(scope.optLong("localModelCallsUsed")>=sourceLocalLimit(scope,true)||pool.modelsSpent>=pool.models||System.currentTimeMillis()>=pool.deadline)throw new SecurityException("A留给本机模型的份额已耗尽；下游份额不能被本机再花一次");}
            try(Cursor row=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=? AND epoch=?",new String[]{id,run,Integer.toString(current.getInt("coordinatorEpoch"))})){if(!row.moveToFirst()||!"running".equals(row.getString(0)))throw new SecurityException("原run已结束，不重建");}
            return current;
    }
    /** Safe display/correlation only. This JSON cannot grant execution or construct a control. */
    public static JSONObject resumeRequestView(JSONObject task)throws Exception{
        if(!deviceTask(task))return null;
        JSONObject saved=task.optJSONObject("deviceResumeRequest");
        if(saved==null)return null;
        JSONObject view=new JSONObject();
        for(String key:new String[]{"format","resumeRequestId","state","callerDeviceId","taskId","runId","executorEpoch","allocationId","requestedRevision","reviewRevision","requestedAt","decidedAt"})
            if(saved.has(key))view.put(key,saved.get(key));
        boolean pending="pending_owner_confirmation".equals(saved.optString("state"));
        return view.put("ownerActionRequired",pending)
                .put("stale",pending&&(saved.optLong("reviewRevision")!=task.optLong("deviceStateRevision")
                        ||!saved.optString("runId").equals(task.optString("runId"))||saved.optInt("executorEpoch")!=task.optInt("coordinatorEpoch")))
                .put("newRun",false).put("newGrant",false).put("executionAuthorized",false);
    }
    /** Original controlOperation transaction only. No resume, dispatch or permission renewal. */
    public JSONObject requestResume(dev.pocketengine.features.devices.PairingCoordinator.Permission permission)throws Exception{
        permission.checkCurrent(System.currentTimeMillis());
        synchronized(owner){
            if(!db.inTransaction()||!"task.requestResume".equals(permission.envelope().method()))throw new SecurityException("恢复请求必须随原控制操作持久接收");
            JSONObject args=permission.envelope().arguments();String id=args.getString("taskId"),run=args.getString("runId");int epoch=args.getInt("executorEpoch");
            JSONObject current=task(id);requireDevice(current);JSONObject scope=current.getJSONObject("deviceExecutionScope"),origin=current.getJSONObject("origin");
            if(!"GOAL".equals(current.optString("deviceExecutionKind"))||!run.equals(current.optString("runId"))||epoch!=current.optInt("coordinatorEpoch")
                    ||!args.getString("allocationId").equals(scope.getString("allocationId"))||args.getLong("expectedRevision")!=current.optLong("deviceStateRevision")
                    ||!permission.callerDeviceId().equals(origin.optString("callerDeviceId"))||!permission.originDeviceId().equals(origin.optString("originDeviceId"))
                    ||!permission.grantId().equals(origin.optString("grantId"))||permission.authorizationEpoch()!=origin.optLong("authorizationEpoch")
                    ||!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(permission.envelope().resourceBinding(),scope.getJSONObject("resourceBinding")))
                throw new SecurityException("恢复请求的原任务、来源或版本已改变，请重新读取同任务");
            requireControlLease(permission.callerDeviceId(),id,run,epoch,args.getString("controlLeaseId"));
            Live retained=live.get(run);
            if(retained==null||retained.original.isCancelled()||!retained.original.isPaused()||!retained.original.matchesHostRun(id,run,epoch)
                    ||current.optBoolean("cancelRequested")||!current.optString("status").matches("pausing|paused|awaiting_user")||owner.hasUnresolved(id))
                throw new SecurityException("原执行未暂停、已停止或有结果待核对，不请求重放");
            if(dev.pocketengine.features.devices.DeviceOwnerInputs.question(current.optJSONObject("evidence"),id,run,epoch)!=null)
                throw new SecurityException("原普通问题须回答确切问题，恢复请求不代替回答");
            JSONArray approvals=current.optJSONArray("approvals");
            for(int i=0;approvals!=null&&i<approvals.length();i++)if("pending".equals(approvals.getJSONObject(i).optString("state")))throw new SecurityException("先处理原准确操作审批，恢复请求不替代审批");
            JSONObject previous=current.optJSONObject("deviceResumeRequest");
            if(previous!=null&&"pending_owner_confirmation".equals(previous.optString("state")))throw new SecurityException("已有原恢复请求待手机核对；请查询同操作或在手机拒绝后重新请求");
            long revision=current.getLong("deviceStateRevision");
            JSONObject request=new JSONObject().put("format","pocketworld.phone-resume-request.v1").put("resumeRequestId",permission.envelope().operationId())
                    .put("state","pending_owner_confirmation").put("taskId",id).put("runId",run).put("executorEpoch",epoch).put("allocationId",scope.getString("allocationId"))
                    .put("callerDeviceId",permission.callerDeviceId()).put("originDeviceId",permission.originDeviceId()).put("grantId",permission.grantId()).put("authorizationEpoch",permission.authorizationEpoch())
                    .put("requestedRevision",revision).put("reviewRevision",Math.addExact(revision,1)).put("requestedAt",System.currentTimeMillis())
                    .put("pauseRevision",retained.original.pauseRevision()).put("scopeDigest",TaskStore.capabilityFingerprint(scope));
            current.put("deviceResumeRequest",request);
            access.event(current,new JSONObject().put("type","device_resume_requested").put("resumeRequestId",request.getString("resumeRequestId"))
                    .put("runId",run).put("coordinatorEpoch",epoch).put("ownerActionRequired",true).put("executionAuthorized",false));
            return resumeRequestView(current).put("requested",true);
        }
    }
    /** Called by the native owner's original task window. An old callback can only fail;
     * it cannot consume a different request, recreate a run or acquire another grant. */
    public JSONObject decideResumeRequest(String id,String requestId,long reviewRevision,boolean approved,
            DeviceExecutionAdmission admission,RunControl original)throws Exception{
        if(approved){if(admission==null||original==null)throw new SecurityException("没有原设备执行器，不重建执行");admission.checkCurrent();}
        synchronized(owner){
            db.beginTransaction();try{
                JSONObject current=task(id);requireDevice(current);JSONObject request=current.optJSONObject("deviceResumeRequest");
                if(request==null||!requestId.equals(request.optString("resumeRequestId"))||reviewRevision!=request.optLong("reviewRevision")
                        ||!"pending_owner_confirmation".equals(request.optString("state")))throw new SecurityException("这次恢复请求已改变或已处理，请重新读取任务");
                if(approved){
                    if(current.optLong("deviceStateRevision")!=reviewRevision||!original.isPaused()||original.pauseRevision()!=request.getLong("pauseRevision")
                            ||!request.getString("runId").equals(current.optString("runId"))||request.getInt("executorEpoch")!=current.optInt("coordinatorEpoch")
                            ||!request.getString("allocationId").equals(current.getJSONObject("deviceExecutionScope").getString("allocationId"))
                            ||!request.getString("scopeDigest").equals(TaskStore.capabilityFingerprint(current.getJSONObject("deviceExecutionScope"))))
                        throw new SecurityException("原来源、任务版本或暂停代次已变化；旧确认不恢复执行");
                    current=requireResumableGoal(id,admission,original);request=current.getJSONObject("deviceResumeRequest");
                    current.put("status","running").put("summary","手机主人已确认这次恢复请求，继续原执行与剩余预算");
                }
                request.put("state",approved?"owner_approved":"owner_denied").put("decidedAt",System.currentTimeMillis());
                access.event(current,new JSONObject().put("type","device_resume_owner_decision").put("resumeRequestId",requestId)
                        .put("approved",approved).put("runId",request.getString("runId")).put("coordinatorEpoch",request.getInt("executorEpoch")).put("newRun",false).put("newGrant",false));
                if(approved)original.resumeAtPauseRevision(request.getLong("pauseRevision"));
                db.setTransactionSuccessful();
                return resumeRequestView(current).put("continuedOriginalRun",approved);
            }finally{db.endTransaction();}
        }
    }
    public void release(String run, RunControl original) {
        synchronized (owner) {
            Live known = live.get(run); if (known == null || known.original != original) return; live.remove(run);goalModels.remove(run);
            try {
                JSONObject current = task(original.hostRunIdentity().taskId());
                if (current.optString("status").matches("completed|failed|cancelled") && !owner.hasUnresolved(current.getString("id"))
                        && heldBudget(current.getString("id"), run, false) == 0 && heldBudget(current.getString("id"), run, true) == 0) {
                    current.getJSONObject("deviceExecutionScope").put("grantAllocationSettled", true); access.write(current);
                }
            } catch (Exception unknown) { /* Keep the grant's whole allocation held if settlement cannot be proved. */ }
        }
    }
    public JSONObject snapshot(String caller, String remote) throws Exception {
        synchronized (owner) {
            try (Cursor row = db.rawQuery("SELECT local_task_id,local_operation_id,arguments_digest,accepted_at,method FROM device_inbound WHERE caller_device_id=? AND remote_operation_id=?", new String[]{caller, remote})) {
                if (!row.moveToFirst()) return new JSONObject().put("status", "not_found").put("accepted", false).put("operationId", remote);
                JSONObject current = task(row.getString(0));
                JSONObject result = new JSONObject().put("accepted", true).put("operationId", remote).put("argumentsDigest", row.getString(2))
                        .put("localTaskId", row.getString(0)).put("acceptedAt", row.getLong(3)).put("status", wireState(current))
                        .put("runId", current.optString("runId")).put("executorEpoch", current.optInt("coordinatorEpoch"))
                        .put("origin", current.getJSONObject("origin")).put("executionKind", current.getString("deviceExecutionKind"))
                        .put("nextAction", current.optString("status").equals("reconciling") ? "query_original_operation_then_observe" : "read_original_task");
                JSONObject scope = current.getJSONObject("deviceExecutionScope");
                boolean terminal = current.optString("status").matches("completed|failed|cancelled");
                boolean unresolved = owner.hasUnresolved(current.getString("id"));
                int pendingChildren = 0;
                try (Cursor children = db.rawQuery("SELECT COUNT(*) FROM device_child_budget WHERE parent_task_id=? AND state!='settled'", new String[]{current.getString("id")})) { if (children.moveToFirst()) pendingChildren = children.getInt(0); }
                result.put("budget", new JSONObject().put("allocationId", scope.getString("allocationId")).put("toolCalls", scope.getInt("toolLimit")).put("modelCalls", scope.getInt("modelCallLimit")))
                        .put("toolsUsed", scope.getInt("toolsUsed")).put("modelCallsUsed", scope.getInt("modelCallsUsed"))
                        .put("terminal", terminal).put("stopped", terminal && !unresolved && pendingChildren == 0 && !live.containsKey(current.optString("runId")))
                        .put("hasUnresolved", unresolved).put("outstandingChildReservations", pendingChildren);
                if (!row.isNull(1)) {result.put("localOperationId",row.getString(1));} // Actual target SQL operation, not the source correlation.
                if (!row.isNull(1)) try (Cursor operation = db.rawQuery("SELECT state,receipt FROM operations WHERE operation_id=? AND task_id=?", new String[]{row.getString(1), current.getString("id")})) {
                    if (operation.moveToFirst()) { result.put("operationState", operation.getString(0)); if (!operation.isNull(1)) result.put("receipt", new JSONObject(operation.getString(1))); }
                }
                return result;
            }
        }
    }
    @FunctionalInterface public interface HistorySourceVisibility { boolean sourceVisible(JSONObject nativeAcceptedAudience,long now)throws Exception; }
    /** Read-only native producer from ORIGINAL accepted SQL. No expired execution Permission is revived.
     * Forwarded A->B->C history needs a separate current native A-source visibility proof; absent it,
     * only the original directly authenticated origin can read its own retained objects. */
    public dev.pocketengine.features.devices.PairingCoordinator.NativeHistoryAudienceSource historyAudiences(){return historyAudiences((audience,now)->audience.getString("originDeviceId").equals(audience.getString("callerDeviceId")));}
    public dev.pocketengine.features.devices.PairingCoordinator.NativeHistoryAudienceSource historyAudiences(HistorySourceVisibility visibility){
        java.util.Objects.requireNonNull(visibility);return new dev.pocketengine.features.devices.PairingCoordinator.NativeHistoryAudienceSource(){
            public JSONObject acceptedAudience(DeviceTaskEnvelope query)throws Exception{return acceptedHistoryAudience(query);}
            public boolean sourceVisible(JSONObject audience,long now)throws Exception{return visibility.sourceVisible(duplicate(audience),now);}
        };
    }
    private JSONObject acceptedHistoryAudience(DeviceTaskEnvelope query)throws Exception{
        synchronized(owner){if(query.method().startsWith("artifact."))throw new SecurityException("成果历史须先由native catalog解析固定引用，再查原task/run/operation");String object=query.arguments().getString(query.method().startsWith("operation.")?"operationId":"taskId");
            String sql=query.method().startsWith("operation.")?"SELECT local_task_id,envelope_json,accepted_at FROM device_inbound WHERE caller_device_id=? AND remote_operation_id=?":"SELECT local_task_id,envelope_json,accepted_at FROM device_inbound WHERE caller_device_id=? AND local_task_id=? ORDER BY accepted_at,remote_operation_id LIMIT 1";
            try(Cursor row=db.rawQuery(sql,new String[]{query.callerDeviceId(),object})){if(!row.moveToFirst())throw new SecurityException("没有该原接受对象的本机SQL受众");return historyAudience(query,query.method().startsWith("operation.")?"operation":"task",object,row.getString(0),new JSONObject(row.getString(1)),row.getLong(2));}
        }
    }
    /** Called only after a native catalog lookup. The artifact id MUST also be present in this exact
     * original operation's real immutable descriptor, so a catalog string never grants private access. */
    public JSONObject historyAudienceForArtifact(DeviceTaskEnvelope query,String id,String run,String localOperation)throws Exception{
        synchronized(owner){String artifact=query.arguments().getString("artifactId");
            try(Cursor actual=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{id,run,localOperation})){if(!actual.moveToFirst()||actual.isNull(0))throw new SecurityException("成果没有原操作真实收据");JSONObject descriptor=new JSONObject(actual.getString(0)).optJSONObject("imageArtifact");if(descriptor==null||!artifact.equals(descriptor.optString("artifactId"))||!id.equals(descriptor.optString("taskId"))||!run.equals(descriptor.optString("runId"))||!localOperation.equals(descriptor.optString("operationId")))throw new SecurityException("成果不属于该原task/run/operation真实固定描述");}
            try(Cursor row=db.rawQuery("SELECT envelope_json,accepted_at FROM device_inbound WHERE caller_device_id=? AND local_task_id=? AND local_operation_id=?",new String[]{query.callerDeviceId(),id,localOperation})){if(!row.moveToFirst())throw new SecurityException("该成果没有此设备原生接受映射");return historyAudience(query,"artifact",artifact,id,new JSONObject(row.getString(0)),row.getLong(1));}
        }
    }
    private JSONObject historyAudience(DeviceTaskEnvelope query,String kind,String object,String id,JSONObject accepted,long acceptedAt)throws Exception{
        JSONObject current=task(id);requireDevice(current);if(!query.callerDeviceId().equals(accepted.getString("callerDeviceId")))throw new SecurityException("不是原SQL接受的caller");long retained=current.optLong("deviceHistoryRetainedUntil");if(retained<=0)throw new SecurityException("旧记录没有原生持久历史受众；只留本机核对，不从旧wire合成执行权限");
        JSONObject result=new JSONObject().put("kind",kind).put("objectId",object).put("originDeviceId",accepted.getString("originDeviceId")).put("callerDeviceId",accepted.getString("callerDeviceId")).put("targetDeviceId",accepted.getString("targetDeviceId")).put("instanceId",accepted.optString("instanceId",current.optString("deviceNativeInstanceId"))).put("rootTaskId",accepted.optString("rootTaskId",current.getString("rootTaskId"))).put("localTaskId",accepted.optString("localTaskId",current.getJSONObject("origin").getString("remoteLocalTaskId"))).put("taskId",id).put("grantId",accepted.getString("grantId")).put("authorizationEpoch",accepted.getLong("authorizationEpoch")).put("resourceBinding",accepted.optJSONObject("resourceBinding")==null?current.getJSONObject("deviceExecutionScope").getJSONObject("resourceBinding"):accepted.getJSONObject("resourceBinding")).put("acceptedAt",acceptedAt).put("retainedUntil",retained);
        if(!current.optString("runId").isEmpty())result.put("runId",current.getString("runId")).put("executorEpoch",current.getInt("coordinatorEpoch"));
        if(accepted.has("runId")&&accepted.has("executorEpoch"))result.put("requestRunId",accepted.getString("runId")).put("requestExecutorEpoch",accepted.getLong("executorEpoch"));
        if(kind.equals("operation"))try(Cursor digest=db.rawQuery("SELECT arguments_digest FROM device_inbound WHERE caller_device_id=? AND remote_operation_id=?",new String[]{query.callerDeviceId(),object})){if(!digest.moveToFirst())throw new SecurityException("没有原operation SQL摘要");result.put("argumentsDigest",digest.getString(0));}
        JSONObject source=current.getJSONObject("deviceExecutionScope").optJSONObject("sourceShare");if(source!=null)result.put("sourceShareId",source.getString("shareId")).put("sourceIssuerDeviceId",source.getString("issuerDeviceId")).put("sourceGrantId",source.getString("sourceGrantId")).put("sourceAuthorizationEpoch",source.getLong("sourceAuthorizationEpoch")).put("sourceProofFingerprint",source.getString("sourceProofFingerprint"));return result;
    }
    /** Sole wire receipt formatter; accepted facts are re-read from ORIGINAL committed SQL, not payload. */
    public JSONObject operationReceipt(DeviceTaskEnvelope request,String executorDeviceId,String instanceId,JSONObject payload)throws Exception{
        synchronized(owner){String remote=request.method().equals("operation.get")?request.arguments().getString("operationId"):remoteOperation(request);
            try(Cursor row=db.rawQuery("SELECT local_task_id,arguments_digest,accepted_at,envelope_json,local_operation_id FROM device_inbound WHERE caller_device_id=? AND remote_operation_id=?",new String[]{request.callerDeviceId(),remote})){
                if(!row.moveToFirst())return dev.pocketengine.features.devices.DeviceProtocol.operationReceipt(executorDeviceId,instanceId,remote,null,"not_found",false,"unknown",duplicate(payload),null);
                String id=row.getString(0);JSONObject original=new JSONObject(row.getString(3)),audience=historyAudience(request,"operation",remote,id,original,row.getLong(2)),facts=new JSONObject().put("acceptedAt",row.getLong(2)).put("audience",audience).put("localTaskId",id);if(audience.has("runId"))facts.put("runId",audience.getString("runId")).put("executorEpoch",audience.getLong("executorEpoch"));
                if(request.method().equals("operation.get")&&original.optString("method").equals("operation.cancel")){
                    // Refresh only the original committed cancellation's native facts.
                    // Its SQL envelope fixes the target; neither payload nor a second
                    // cancel request supplies authority or proof that execution stopped.
                    JSONObject target=original.getJSONObject("arguments"),actual=managementState(id,target.getString("operationId"),target.getString("argumentsDigest"));
                    facts.put("cancellation",new JSONObject().put("targetOperationId",actual.getString("targetOperationId")).put("targetArgumentsDigest",actual.getString("targetArgumentsDigest")).put("requested",actual.getBoolean("requested")).put("stopped",actual.getBoolean("stopped")));
                    return dev.pocketengine.features.devices.DeviceProtocol.operationReceipt(executorDeviceId,instanceId,remote,row.getString(1),actual.getBoolean("stopped")?"succeeded":"cancel_requested",true,"reported",new JSONObject().put("managementOnly",true),facts);
                }
                String status=payload.optString("status",wireState(task(id)));if(request.method().equals("task.acquireControl"))status="succeeded";if(!dev.pocketengine.features.devices.DeviceProtocol.TASK_STATES.contains(status)&&!status.matches("prepared|unknown"))status=wireState(task(id));String effect="GoalSubmission".equals(original.optString("messageType"))?"reported":"not_dispatched";
                if(!row.isNull(4))try(Cursor operation=db.rawQuery("SELECT state,receipt FROM operations WHERE task_id=? AND operation_id=?",new String[]{id,row.getString(4)})){if(operation.moveToFirst()){String state=operation.getString(0);effect=state.equals("receipt")?"reported":state.equals("prepared")?"not_dispatched":"unknown";if(state.contains("unknown")){effect="unknown";status="unknown";}}}
                return dev.pocketengine.features.devices.DeviceProtocol.operationReceipt(executorDeviceId,instanceId,remote,row.getString(1),status,true,effect,duplicate(payload),facts);
            }
        }
    }
    public static String wireState(JSONObject task) {
        if (task.optBoolean("cancelRequested") && !task.optString("status").equals("cancelled")) return "cancel_requested";
        return switch (task.optString("status")) {
            case "queued" -> "accepted"; case "preparing" -> "waiting_resource"; case "completed" -> "succeeded";
            case "reconciling", "needs_confirmation" -> "needs_reconcile"; case "stopping" -> "cancel_requested";
            case "awaiting_user" -> "waiting_approval"; case "pausing" -> "pause_requested";
            default -> task.optString("status", "interrupted");
        };
    }
    /** The stable remote operation maps to one original operations row; duplicate pickup never dispatches. */
    public JSONObject prepareInvocation(DeviceExecutionAdmission admission, RunControl original) throws Exception {
        admission.checkCurrent();
        synchronized (owner) {
            DeviceTaskEnvelope request = admission.request(); String remote = remoteOperation(request);
            JSONObject accepted = snapshot(request.callerDeviceId(), remote); String id = accepted.getString("localTaskId");
            String run = original.hostRunIdentity().runId(); int epoch = original.hostRunIdentity().epoch();
            db.beginTransaction(); try {
                JSONObject current = task(id); requireSameSession(current, admission); fence(id, run, epoch, original, true);
                try (Cursor existing = db.rawQuery("SELECT local_operation_id FROM device_inbound WHERE caller_device_id=? AND remote_operation_id=?", new String[]{request.callerDeviceId(), remote})) {
                    if (!existing.moveToFirst()) throw new SecurityException("原操作尚未持久接受");
                    if (!existing.isNull(0)) { db.setTransactionSuccessful(); return snapshot(request.callerDeviceId(), remote).put("fresh", false); }
                }
                JSONObject invocation = request.arguments(); String tool = invocation.getString("toolName");
                checkCapability(id, run, epoch, tool);
                boolean effect = !"read".equals(invocation.getString("effect"));
                if (effect && owner.hasUnresolved(id)) throw new SecurityException("原动作结果未明，先查询并重新观察，不能派发新动作");
                debit(current, run, false); access.write(current);
                JSONObject grant = access.grant(run); int remaining = grant.getInt("remainingOperations");
                if (remaining < 1) throw new SecurityException("原设备工具预算已耗尽");
                grant.put("remainingOperations", remaining - 1); ContentValues budget = new ContentValues(); budget.put("grant_json", grant.toString()); db.update("runs", budget, "run_id=?", new String[]{run});
                String operation = localId("device-operation", request.callerDeviceId() + ":" + remote);
                ContentValues row = new ContentValues(); row.put("operation_id", operation); row.put("task_id", id); row.put("run_id", run); row.put("tool", tool);
                row.put("arguments", invocation.getJSONObject("arguments").toString()); row.put("state", effect ? "prepared" : "device_read_prepared"); row.put("created_at", System.currentTimeMillis()); db.insertOrThrow("operations", null, row);
                ContentValues link = new ContentValues(); link.put("local_operation_id", operation);
                if (db.update("device_inbound", link, "caller_device_id=? AND remote_operation_id=? AND local_operation_id IS NULL", new String[]{request.callerDeviceId(), remote}) != 1)
                    throw new SecurityException("原设备操作已领取");
                access.event(current, new JSONObject().put("type", "operation_intent").put("operationId", operation).put("remoteOperationId", remote).put("tool", tool).put("origin", current.getJSONObject("origin")));
                admission.checkCurrent(); db.setTransactionSuccessful();
                return new JSONObject().put("fresh", true).put("localTaskId", id).put("localOperationId", operation).put("runId", run).put("executorEpoch", epoch);
            } finally { db.endTransaction(); }
        }
    }
    /** At-most-once attempt. A crash after this commit remains unknown, even when no native receipt exists. */
    public void markDispatched(JSONObject claim, DeviceExecutionAdmission admission, RunControl original) throws Exception {
        admission.checkCurrent();
        synchronized (owner) {
            String id = claim.getString("localTaskId"), run = claim.getString("runId"), operation = claim.getString("localOperationId");
            int epoch = claim.getInt("executorEpoch");
            db.beginTransaction(); try {
                fence(id, run, epoch, original, true);
                try (Cursor other = db.rawQuery("SELECT operation_id FROM operations WHERE task_id=? AND operation_id!=? AND state IN ('prepared','unknown') LIMIT 1", new String[]{id, operation})) {
                    if (other.moveToFirst() && admission.request().sideEffecting()) throw new SecurityException("有其他动作结果待核对");
                }
                ContentValues row = new ContentValues(); row.put("state", admission.request().sideEffecting() ? "unknown" : "device_read_running");
                if (db.update("operations", row, "task_id=? AND run_id=? AND operation_id=? AND state IN ('prepared','device_read_prepared')", new String[]{id, run, operation}) != 1)
                    throw new SecurityException("原操作已派发或结果未知，只能查询原身份");
                admission.checkCurrent(); db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }
    public void finishInvocation(JSONObject claim, JSONObject actualReceipt) throws Exception {
        synchronized (owner) {
            String id = claim.getString("localTaskId"), run = claim.getString("runId"), operation = claim.getString("localOperationId");
            fence(id, run, claim.getInt("executorEpoch"), null, false);
            boolean unknown = unknownReceipt(actualReceipt);
            db.beginTransaction(); try {
                ContentValues row = new ContentValues(); row.put("state", unknown ? "unknown" : "receipt"); row.put("receipt", actualReceipt.toString());
                if (db.update("operations", row, "task_id=? AND run_id=? AND operation_id=? AND state IN ('prepared','unknown','device_read_prepared','device_read_running')", new String[]{id, run, operation}) != 1)
                    throw new SecurityException("实际回执不属于该原操作");
                JSONObject current = task(id);
                if (unknown) current.put("status", "reconciling").put("summary", "原手机工具效果尚未核对；不会重发动作");
                access.write(current); access.event(current, new JSONObject().put("type", "operation_receipt").put("operationId", operation).put("receipt", actualReceipt));
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }
    public static boolean unknownReceipt(JSONObject receipt) {
        if (receipt == null) return true;
        String code = receipt.optString("code", receipt.optString("error"));
        return receipt.has("accepted") && receipt.isNull("accepted") || code.contains("unknown") || code.equals("action_timeout")
                || receipt.optString("status").matches("unknown|needs_reconcile") || receipt.optString("state").contains("unknown");
    }
    /** A remote control request is durable but is NEVER proof the actual executor has stopped. */
    public JSONObject requestControl(String caller, String id, String run, int epoch, String action) throws Exception {
        synchronized (owner) {
            JSONObject current = task(id); requireDevice(current);
            if (!caller.equals(current.getJSONObject("origin").getString("callerDeviceId")) || !run.equals(current.optString("runId")) || epoch != current.optInt("coordinatorEpoch"))
                throw new SecurityException("控制请求不属于该设备原执行");
            if (!action.matches("pause|cancel")) throw new IllegalArgumentException("无效设备控制请求");
            current.put("status", action.equals("cancel") ? "stopping" : "pausing");
            if (action.equals("cancel")) current.put("cancelRequested", true);
            access.write(current); access.event(current, new JSONObject().put("type", "device_control_requested").put("action", action).put("callerDeviceId", caller));
            Live binding = live.get(run);
            if (binding != null) { if (action.equals("cancel")) binding.original.cancel(); else binding.original.pause(); }
            return new JSONObject().put("localTaskId", id).put("status", action.equals("cancel") ? "cancel_requested" : "pause_requested")
                    .put("stopped", false).put("effectsRolledBack", false);
        }
    }
    public static JSONObject unsignedWireIntent(JSONObject wire) throws Exception {
        JSONObject intent = duplicate(wire);
        for (String field : new String[]{"requestId", "argumentsDigest", "traceId", "replyCursor"}) intent.remove(field);
        JSONObject args = intent.optJSONObject("arguments"), forward = args == null ? null : args.optJSONObject("delegationAuthorization");
        if (forward != null) for (String field : new String[]{"signature", "issuerCertificateDer", "issuerCertificateFingerprint"}) forward.remove(field);
        return intent;
    }
    private static String wireIntentDigest(JSONObject wire) throws Exception {
        return dev.pocketengine.features.devices.DeviceProtocol.sha256(dev.pocketengine.features.devices.DeviceProtocol.canonicalBytes(unsignedWireIntent(wire)));
    }
    private static JSONObject allocationScope(JSONObject wire) throws Exception {
        JSONObject scope = new JSONObject();
        for (String field : new String[]{"rootTaskId", "parentTaskId", "originDeviceId", "callerDeviceId", "targetDeviceId", "grantId", "authorizationEpoch", "resourceBinding", "deadline", "budget"}) scope.put(field, wire.get(field));
        scope.put("capabilityVersion",wire.optString("capabilityVersion",dev.pocketengine.features.devices.DeviceProtocol.VERSION));
        return scope;
    }
    /** Reserve the stable native allocation ONCE, then link every original local operation to it.
     * The unsigned intent is frozen first so randomized ECDSA signatures cannot create a digest cycle. */
    public JSONObject reserveChild(String id, String run, int epoch, RunControl original, String operation, JSONObject unsignedWire) throws Exception {
        synchronized (owner) {
            String target = unsignedWire.getString("targetDeviceId"), method = unsignedWire.getString("method");
            JSONObject budget = unsignedWire.getJSONObject("budget"); int tools = budget.getInt("toolCalls"), models = budget.getInt("modelCalls");
            String allocation = budget.getString("allocationId"), expected = dev.pocketengine.features.devices.DeviceProtocol.allocationId(id, target, unsignedWire.getJSONObject("resourceBinding"), method.equals("task.submitGoal") ? "goal" : "tool_session", method.equals("task.submitGoal") ? operation : null);
            if (!allocation.equals(expected) || !operation.equals(unsignedWire.optString("operationId")) || !id.equals(unsignedWire.optString("parentTaskId"))) throw new SecurityException("子预算必须是本机原任务/目标/准确资源的稳定allocation");
            String intentDigest = wireIntentDigest(unsignedWire), scopeDigest = TaskStore.capabilityFingerprint(allocationScope(unsignedWire));
            db.beginTransaction(); try {
                JSONObject current = task(id); access.checkRun(id, run, epoch, original, false); original.check();
                if (owner.taskExecutionDeadline(id, run, epoch) <= System.currentTimeMillis() || original.isCancelled() || original.isPaused() || current.optBoolean("cancelRequested")) throw new SecurityException("原父执行已停止或到期");
                if (tools < 0 || models < 0 || tools + (long) models < 1 || tools > 400 || models > 1000) throw new SecurityException("子任务预算无效");
                try (Cursor known = db.rawQuery("SELECT intent_digest FROM device_child_links WHERE parent_task_id=? AND parent_operation_id=?", new String[]{id, operation})) {
                    if (known.moveToFirst()) {
                        if (!intentDigest.equals(known.getString(0))) throw new SecurityException("同一原父操作不能换目标、参数或重新签名");
                        JSONObject result = childAllocationForOperation(id, operation).put("knownReservation", true).put("knownOperation", true); db.setTransactionSuccessful(); return result;
                    }
                }
                try (Cursor prepared = db.rawQuery("SELECT state,tool,arguments FROM operations WHERE task_id=? AND run_id=? AND operation_id=?", new String[]{id, run, operation})) {
                    if (!prepared.moveToFirst() || !"prepared".equals(prepared.getString(0)) || !prepared.getString(1).matches("device_invoke_tool|device_delegate_goal")) throw new SecurityException("子预算必须绑定该原父已登记设备操作");
                    JSONObject args = new JSONObject(prepared.getString(2));
                    if (!target.equals(args.optString("targetDeviceId")) || tools != args.optInt("toolBudget", -1) || models != args.optInt("modelBudget", 0)) throw new SecurityException("子目标或预算不是原父prepared参数");
                }
                boolean knownAllocation = false;
                try (Cursor known = db.rawQuery("SELECT scope_digest,state,child_binding FROM device_child_budget WHERE parent_task_id=? AND allocation_id=?", new String[]{id, allocation})) {
                    if (known.moveToFirst()) {
                        knownAllocation = true;
                        if (!scopeDigest.equals(known.getString(0)) || "settled".equals(known.getString(1))) throw new SecurityException("原allocation范围已变化或已经结算，不能重置额度");
                        if (known.isNull(2)) throw new SecurityException("原allocation尚未核对目标接受，先查询原操作，不复制执行");
                    }
                }
                if (!knownAllocation) {
                    JSONObject scope = current.optJSONObject("deviceExecutionScope"); if (scope == null) scope = current.optJSONObject("executionScope");
                    if (scope == null || tools > scope.getInt("toolLimit") - scope.getInt("toolsUsed") - heldBudget(id, run, false)
                            || models > scope.getInt("modelCallLimit") - scope.getInt("modelCallsUsed") - heldBudget(id, run, true)) throw new SecurityException("子预算总预留超过父真实剩余");
                    ContentValues row = new ContentValues(); row.put("parent_task_id", id); row.put("parent_run_id", run); row.put("parent_epoch", epoch); row.put("parent_operation_id", operation); row.put("allocation_id", allocation); row.put("scope_digest", scopeDigest); row.put("target_device_id", target); row.put("arguments_digest", ""); row.put("tools_reserved", tools); row.put("models_reserved", models); row.put("state", "reserved"); row.put("created_at", System.currentTimeMillis()); db.insertOrThrow("device_child_budget", null, row);
                    access.event(current, new JSONObject().put("type", "device_child_budget_reserved").put("operationId", operation).put("allocationId", allocation).put("targetDeviceId", target).put("tools", tools).put("models", models));
                }
                ContentValues link = new ContentValues(); link.put("parent_task_id", id); link.put("parent_operation_id", operation); link.put("allocation_id", allocation); link.put("intent_digest", intentDigest); db.insertOrThrow("device_child_links", null, link);
                JSONObject result = childAllocationForOperation(id, operation).put("knownReservation", knownAllocation).put("knownOperation", false); db.setTransactionSuccessful(); return result;
            } finally { db.endTransaction(); }
        }
    }
    public ChildReservation reserveChildNative(String id,String run,int epoch,RunControl original,String operation,JSONObject unsignedWire)throws Exception{
        synchronized(owner){JSONObject held=reserveChild(id,run,epoch,original,operation,unsignedWire);if(!held.optBoolean("knownOperation"))requirePreparedIntent(id,run,operation,unsignedWire);return new ChildReservation(this,id,run,epoch,original,operation,held);}
    }
    public ChildReservation reserveChildNative(String id,String run,int epoch,RunControl original,String operation,JSONObject unsignedWire,OwnerRouteApproval sourceApproval)throws Exception{
        synchronized(owner){requireSourceRoute(sourceApproval,original);if(!sourceApproval.id.equals(id)||!sourceApproval.run.equals(run)||sourceApproval.epoch!=epoch||!sourceApproval.receivingGrant().targetDeviceId().equals(unsignedWire.optString("targetDeviceId"))||!sourceApproval.kind().equals(unsignedWire.optString("method").equals("task.submitGoal")?"goal":"tool_session")||!dev.pocketengine.features.devices.DeviceProtocol.resourceEquals(sourceApproval.resourceBinding(),unsignedWire.getJSONObject("resourceBinding"))||!sourceApproval.capabilityVersion().equals(unsignedWire.optString("capabilityVersion")))throw new SecurityException("原prepared wire不属于此native owner/A路由证明");
            JSONObject budget=unsignedWire.getJSONObject("budget");if(budget.getInt("toolCalls")!=sourceApproval.toolBudget()||budget.getInt("modelCalls")!=sourceApproval.modelBudget()||unsignedWire.getLong("deadline")>sourceApproval.deadline())throw new SecurityException("出站固定份额不属于原native审阅范围");db.beginTransaction();try{
                boolean exists;try(Cursor known=db.rawQuery("SELECT 1 FROM device_child_budget WHERE parent_task_id=? AND allocation_id=?",new String[]{id,budget.getString("allocationId")})){exists=known.moveToFirst();}
                if(!exists){long[] committed=sourceRouteCommitted(id,sourceApproval.sourceKey());if(budget.getLong("toolCalls")>sourceApproval.toolBudget()-committed[0]||budget.getLong("modelCalls")>sourceApproval.modelBudget()-committed[1])throw new SecurityException("原owner路由生命周期份额已预留/使用；新Goal operation不刷新route上限");}
                JSONObject held=reserveChild(id,run,epoch,original,operation,unsignedWire);if(!held.optBoolean("knownOperation"))requirePreparedIntent(id,run,operation,unsignedWire,sourceApproval);ChildReservation reservation=new ChildReservation(this,id,run,epoch,original,operation,held);reservation.sourceApproval=sourceApproval;
                if(!reservation.known){JSONObject allocation=childAllocationForOperation(id,operation);try(Cursor row=db.rawQuery("SELECT receipt_json FROM device_child_budget WHERE parent_task_id=? AND parent_operation_id=?",new String[]{id,allocation.getString("anchorOperationId")})){if(!row.moveToFirst())throw new SecurityException("原分配消失");JSONObject receipt=row.isNull(0)?new JSONObject():new JSONObject(row.getString(0));JSONObject marker=receipt.optJSONObject("nativeSourceRoute");if(marker!=null&&!sourceApproval.sourceKey().equals(marker.optString("sourceKey")))throw new SecurityException("原allocation不能移动到另一owner路由池");receipt.put("nativeSourceRoute",new JSONObject().put("sourceKey",sourceApproval.sourceKey()).put("ownerScopeFingerprint",sourceApproval.policyDigest).put("forwarded",sourceApproval.forwarded()));ContentValues update=new ContentValues();update.put("receipt_json",receipt.toString());db.update("device_child_budget",update,"parent_task_id=? AND parent_operation_id=?",new String[]{id,allocation.getString("anchorOperationId")});}}
                db.setTransactionSuccessful();return reservation;
            }finally{db.endTransaction();}
        }
    }
    private void requirePreparedIntent(String id,String run,String operation,JSONObject wire)throws Exception{requirePreparedIntent(id,run,operation,wire,null);}
    private void requirePreparedIntent(String id,String run,String operation,JSONObject wire,OwnerRouteApproval approval)throws Exception{
        try(Cursor row=db.rawQuery("SELECT tool,arguments FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND state='prepared'",new String[]{id,run,operation})){
            if(!row.moveToFirst())throw new SecurityException("没有原prepared设备操作");JSONObject args=new JSONObject(row.getString(1)),payload=wire.getJSONObject("arguments");
            if("device_invoke_tool".equals(row.getString(0))){if(!"tool.invoke".equals(wire.optString("method"))||!args.getString("toolName").equals(payload.optString("toolName"))||!("workspace.project.export".equals(args.optString("toolName"))?projectExportIntent(args,wire,approval):"workspace.project.confirm".equals(args.optString("toolName"))?projectConfirmIntent(args,wire,approval):java.util.Set.of("workspace.receive","workspace.project.receive").contains(args.optString("toolName"))?selectedFileIntent(args,wire,approval):TaskStore.capabilityFingerprint(args.getJSONObject("arguments")).equals(TaskStore.capabilityFingerprint(payload.getJSONObject("arguments")))))throw new SecurityException("工具wire不是原prepared准确参数");}
            else if(!"device_delegate_goal".equals(row.getString(0))||!"task.submitGoal".equals(wire.optString("method"))||!args.getString("goal").equals(payload.optString("goal"))||!TaskStore.capabilityFingerprint(args.optJSONObject("contextSnapshot")==null?new JSONObject():args.getJSONObject("contextSnapshot")).equals(TaskStore.capabilityFingerprint(payload.optJSONObject("contextSnapshot")==null?new JSONObject():payload.getJSONObject("contextSnapshot"))))throw new SecurityException("目标wire不是原prepared准确参数");
            if(!TaskStore.capabilityFingerprint(args.optJSONObject("observationRef")==null?new JSONObject():args.getJSONObject("observationRef")).equals(TaskStore.capabilityFingerprint(wire.optJSONObject("observationRef")==null?new JSONObject():wire.getJSONObject("observationRef"))))throw new SecurityException("观察wire不是原prepared引用");
        }
    }
    /** Native source projection is checked against BOTH the prepared model selection and the
     * original owner-approved material. No model-supplied descriptor can become file authority. */
    private boolean selectedFileIntent(JSONObject prepared,JSONObject wire,OwnerRouteApproval approval)throws Exception{
        if(approval==null||approval.forwarded()||!approval.routeId().equals(prepared.optString("routeId"))||!approval.receivingGrant().targetDeviceId().equals(prepared.optString("targetDeviceId")))return false;
        JSONObject selection=prepared.getJSONObject("arguments");if(!dev.pocketengine.features.devices.DeviceProtocol.objectKeys(selection).equals(java.util.Set.of("materialId")))return false;
        boolean project="workspace.project.receive".equals(prepared.optString("toolName"));JSONArray files=approval.route.optJSONArray(project?"projects":"files");JSONObject selected=null;for(int i=0;files!=null&&i<files.length();i++)if(files.getJSONObject(i).getString("id").equals(selection.getString("materialId")))selected=files.getJSONObject(i);if(selected==null)return false;
        JSONObject payload=wire.getJSONObject("arguments"),args=payload.getJSONObject("arguments");if(!"write".equals(payload.optString("effect"))||!dev.pocketengine.features.devices.DeviceProtocol.objectKeys(args).equals(java.util.Set.of("path","expectedVersion","artifact"))||!selected.getString("path").equals(args.optString("path"))||!selected.getString("expectedVersion").equals(args.optString("expectedVersion")))return false;
        JSONObject artifact=dev.pocketengine.features.devices.DeviceProtocol.validateMessage(args.getJSONObject("artifact"));
        String transfer=UUID.nameUUIDFromBytes(((project?"workspace.project.receive\0":"workspace.receive\0")+wire.getString("operationId")).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return "ArtifactDescriptor".equals(artifact.optString("messageType"))&&(project?"project_bundle":"file").equals(artifact.optString("kind"))&&selected.getString("id").equals(artifact.optString("artifactId"))&&selected.getString("sha256").equals(artifact.optString("contentDigest"))&&selected.getString("sha256").equals(artifact.optString("versionId"))&&selected.getLong("size")==artifact.optLong("size",-1)&&artifact.getLong("size")<=(project?20971520:262144)&&(project?"project-source.zip":selected.getString("name")).equals(artifact.optString("name"))&&(project?"application/zip":selected.getString("mimeType")).equals(artifact.optString("mediaType"))&&wire.getString("callerDeviceId").equals(artifact.optString("executorDeviceId"))&&wire.getString("localTaskId").equals(artifact.optString("taskId"))&&wire.getString("operationId").equals(artifact.optString("operationId"))&&wire.getString("runId").equals(artifact.optString("requestRunId"))&&wire.getLong("executorEpoch")==artifact.optLong("requestExecutorEpoch")&&transfer.equals(artifact.optString("transferId"))&&!artifact.has("runId")&&!artifact.has("executorEpoch")&&artifact.optLong("revision")==1;
    }
    private boolean projectExportIntent(JSONObject prepared,JSONObject wire,OwnerRouteApproval approval)throws Exception{
        if(approval==null||!approval.routeId().equals(prepared.optString("routeId")))return false;JSONObject args=prepared.getJSONObject("arguments");if(!dev.pocketengine.features.devices.DeviceProtocol.objectKeys(args).equals(java.util.Set.of("receiveOperationId")))return false;requireProjectExport(approval,args.getString("receiveOperationId"),approval.original);return "read".equals(wire.getJSONObject("arguments").optString("effect"))&&TaskStore.capabilityFingerprint(args).equals(TaskStore.capabilityFingerprint(wire.getJSONObject("arguments").getJSONObject("arguments")));
    }
    private boolean projectConfirmIntent(JSONObject prepared,JSONObject wire,OwnerRouteApproval approval)throws Exception{
        if(approval==null||approval.forwarded()||!approval.routeId().equals(prepared.optString("routeId"))||!approval.receivingGrant().targetDeviceId().equals(prepared.optString("targetDeviceId")))return false;JSONObject args=prepared.getJSONObject("arguments");if(!dev.pocketengine.features.devices.DeviceProtocol.objectKeys(args).equals(java.util.Set.of("receiveOperationId")))return false;JSONObject actual=requireProjectImportConfirmation(approval,args.getString("receiveOperationId"),approval.original);return "write".equals(wire.getJSONObject("arguments").optString("effect"))&&TaskStore.capabilityFingerprint(actual).equals(TaskStore.capabilityFingerprint(wire.getJSONObject("arguments").getJSONObject("arguments")));
    }
    private String parentAuthority(String id,String run)throws Exception{
        JSONObject current=task(id),authority=new JSONObject();for(String field:new String[]{"id","actorId","spaceId","origin","runId","coordinatorEpoch","executionMode"})if(current.has(field))authority.put(field,current.get(field));
        for(String field:new String[]{"executionScope","deviceExecutionScope"})if(current.optJSONObject(field)!=null){JSONObject scope=duplicate(current.getJSONObject(field));scope.remove("toolsUsed");scope.remove("modelCallsUsed");scope.remove("localToolsUsed");scope.remove("localModelCallsUsed");authority.put(field,scope);}
        JSONObject grant=access.grant(run);grant.remove("remainingOperations");authority.put("grant",grant);return TaskStore.capabilityFingerprint(authority);
    }
    private void requireReservationCurrent(ChildReservation reservation)throws Exception{
        if(reservation==null||reservation.issuer!=this||reservation.known)throw new SecurityException("签名/派发需要本原TaskStore首次native预留");
        synchronized(owner){if(reservation.sourceApproval!=null)requireSourceRoute(reservation.sourceApproval,reservation.original);reservation.original.check();access.checkRun(reservation.id,reservation.run,reservation.epoch,reservation.original,false);JSONObject current=task(reservation.id);
            if(reservation.original.isCancelled()||reservation.original.isPaused()||reservation.original.pauseRevision()!=reservation.pauseRevision||current.optBoolean("cancelRequested")||owner.taskExecutionDeadline(reservation.id,reservation.run,reservation.epoch)<=System.currentTimeMillis()||!reservation.authority.equals(parentAuthority(reservation.id,reservation.run)))throw new SecurityException("预留后原父控制/范围/授权版本已变化，禁止签名或派发");
            JSONObject held=childAllocationForOperation(reservation.id,reservation.operation);if(!reservation.allocation.equals(held.optString("allocationId"))||!reservation.intent.equals(held.optString("intentDigest"))||"settled".equals(held.optString("state")))throw new SecurityException("原预算预留已变化");
            try(Cursor row=db.rawQuery("SELECT state FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{reservation.id,reservation.run,reservation.operation})){if(!row.moveToFirst()||!"prepared".equals(row.getString(0)))throw new SecurityException("原设备操作已经派发/未知，只能查询原ID");}
        }
    }
    public JSONObject childAllocationForOperation(String id, String operation) throws Exception {
        synchronized (owner) {
            try (Cursor row = db.rawQuery("SELECT b.allocation_id,b.parent_operation_id,b.arguments_digest,b.target_device_id,b.state,l.intent_digest,l.wire_digest,l.wire_json,b.child_binding FROM device_child_links l JOIN device_child_budget b ON b.parent_task_id=l.parent_task_id AND b.allocation_id=l.allocation_id WHERE l.parent_task_id=? AND l.parent_operation_id=?", new String[]{id, operation})) {
                if (!row.moveToFirst()) throw new SecurityException("没有该原操作的预算allocation");
                JSONObject result = new JSONObject().put("allocationId", row.getString(0)).put("anchorOperationId", row.getString(1)).put("anchorArgumentsDigest", row.getString(2)).put("targetDeviceId", row.getString(3)).put("state", row.getString(4)).put("intentDigest", row.getString(5));
                if (!row.isNull(6)) result.put("wireDigest", row.getString(6)); if (!row.isNull(7)) result.put("boundWire", new JSONObject(row.getString(7))); if (!row.isNull(8)) result.put("childBinding", new JSONObject(row.getString(8))); return result;
            }
        }
    }
    /** One current-authority original-op CAS AFTER lock-free signing; never send the draft. */
    public OutboundSendTicket bindReservedWire(ChildReservation reservation,DeviceTaskEnvelope finalWire,dev.pocketengine.features.devices.PairingCoordinator.OutgoingGrant receivingGrant)throws Exception{
        java.util.Objects.requireNonNull(receivingGrant);synchronized(owner){db.beginTransaction();try{
            requireReservationCurrent(reservation);receivingGrant.checkCurrent(System.currentTimeMillis());reservation.requireIntent(finalWire.json());
            JSONObject allocation=childAllocationForOperation(reservation.id,reservation.operation);
            if(!reservation.operation.equals(finalWire.operationId())||!reservation.allocation.equals(finalWire.budget().getString("allocationId"))||!allocation.getString("targetDeviceId").equals(finalWire.targetDeviceId())
                    ||!receivingGrant.targetDeviceId().equals(finalWire.targetDeviceId())||!receivingGrant.targetInstanceId().equals(finalWire.instanceId())||!receivingGrant.grantId().equals(finalWire.grantId())||receivingGrant.authorizationEpoch()!=finalWire.authorizationEpoch()
                    ||!receivingGrant.grant().getString("peerDeviceId").equals(finalWire.callerDeviceId())||finalWire.deadline()>receivingGrant.deadline())throw new SecurityException("签名wire不是原native目标/授权/实例/预算");
            requirePreparedIntent(reservation.id,reservation.run,reservation.operation,finalWire.json(),reservation.sourceApproval);
            String encoded=dev.pocketengine.features.devices.DeviceProtocol.canonical(finalWire.json());byte[] bytes=encoded.getBytes(StandardCharsets.UTF_8);
            if(allocation.has("boundWire"))throw new SecurityException("原wire已经绑定，不重新签名或签发新票；先查原ID");
            ContentValues link=new ContentValues();link.put("wire_digest",finalWire.argumentsDigest());link.put("wire_json",encoded);
            if(db.update("device_child_links",link,"parent_task_id=? AND parent_operation_id=? AND wire_json IS NULL",new String[]{reservation.id,reservation.operation})!=1)throw new SecurityException("原wire已经固定");
            if(reservation.operation.equals(allocation.getString("anchorOperationId"))){ContentValues anchor=new ContentValues();anchor.put("arguments_digest",finalWire.argumentsDigest());db.update("device_child_budget",anchor,"parent_task_id=? AND parent_operation_id=?",new String[]{reservation.id,reservation.operation});}
            JSONObject marker=new JSONObject().put("format","pocketworld.native-device-send.v1").put("state","not_claimed").put("wireSha256",dev.pocketengine.features.devices.DeviceProtocol.sha256(bytes)).put("allocationId",reservation.allocation).put("intentDigest",reservation.intent);
            ContentValues row=new ContentValues();row.put("receipt",new JSONObject().put("nativeDeviceSend",marker).toString());
            if(db.update("operations",row,"task_id=? AND run_id=? AND operation_id=? AND state='prepared'",new String[]{reservation.id,reservation.run,reservation.operation})!=1)throw new SecurityException("原操作已不再prepared");
            db.setTransactionSuccessful();return new OutboundSendTicket(reservation,receivingGrant,bytes);
        }finally{db.endTransaction();}}
    }
    private JSONObject sendMarker(ChildReservation reservation)throws Exception{
        try(Cursor row=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=?",new String[]{reservation.id,reservation.run,reservation.operation})){if(!row.moveToFirst()||row.isNull(0))return null;return new JSONObject(row.getString(0)).optJSONObject("nativeDeviceSend");}
    }
    private byte[] claimForWrite(OutboundSendTicket ticket,dev.pocketengine.features.devices.DeviceIdentity.AuthenticatedPeer actualPeer)throws Exception{
        ChildReservation r=ticket.reservation;synchronized(owner){if(r.issuer!=this||ticket.claimed||ticket.abandoned)throw new SecurityException("原发送票据已领取或明确放弃");db.beginTransaction();try{
            requireReservationCurrent(r);ticket.target.requireTargetPeer(actualPeer);JSONObject marker=sendMarker(r),allocation=childAllocationForOperation(r.id,r.operation);
            if(marker==null||!"not_claimed".equals(marker.optString("state"))||!ticket.wireHash.equals(marker.optString("wireSha256"))||!ticket.wireHash.equals(dev.pocketengine.features.devices.DeviceProtocol.sha256(dev.pocketengine.features.devices.DeviceProtocol.canonicalBytes(allocation.getJSONObject("boundWire")))))throw new SecurityException("原固定wire/发送状态已变化");
            // Recheck the original source receipt under the same SQL lock as the write claim.
            // Archive hashing stays in the native guard outside this lock.
            JSONObject bound=allocation.getJSONObject("boundWire"),invocation=bound.getJSONObject("arguments");String tool=invocation.optString("toolName");
            if(tool.equals("workspace.project.confirm")||tool.equals("workspace.project.export")){
                if(ticket.nativeWriteGuard==null)throw new SecurityException("项目派发缺少原生当前包复核门");
                String receive=invocation.getJSONObject("arguments").getString("receiveOperationId");
                JSONObject selected=approvedProjectMaterial(r.sourceApproval,receive,r.original);
                if(tool.equals("workspace.project.confirm")&&!TaskStore.capabilityFingerprint(requireProjectImportConfirmation(r.sourceApproval,receive,r.original)).equals(TaskStore.capabilityFingerprint(invocation.getJSONObject("arguments"))))throw new SecurityException("原确认审批已变化");
                JSONObject evidence=owner.projectPackageEvidenceForOwner(selected.getString("sourceTaskId"),selected.getString("sourceOperationId"));
                if(!selected.getString("id").equals(evidence.getJSONObject("packageArtifact").getString("id"))||!selected.getString("sha256").equals(evidence.getString("archiveSha256"))||selected.getLong("size")!=evidence.getLong("archiveBytes")||!selected.getString("manifestDigest").equals(evidence.getString("manifestIdentity")))throw new SecurityException("派发前原项目来源已变化");
            }
            // Fixed lock order: original TaskStore -> RunControl. Entry is one short SQLite CAS,
            // never TLS/network/signing/join. Pause and this prepared->unknown entry linearize here.
            r.original.dispatchAtPauseRevision(r.pauseRevision,()->{marker.put("state","write_claimed").put("claimedAt",System.currentTimeMillis());ContentValues row=new ContentValues();row.put("state","unknown");row.put("receipt",new JSONObject().put("nativeDeviceSend",marker).toString());if(db.update("operations",row,"task_id=? AND run_id=? AND operation_id=? AND state='prepared'",new String[]{r.id,r.run,r.operation})!=1)throw new SecurityException("原设备操作已派发，只查原ID");});
            db.setTransactionSuccessful();ticket.claimed=true;
        }finally{db.endTransaction();}return ticket.wire.clone();}
    }
    private void abandonBeforeWrite(OutboundSendTicket ticket)throws Exception{
        ChildReservation r=ticket.reservation;synchronized(owner){if(r.issuer!=this||ticket.claimed||ticket.abandoned)throw new SecurityException("不能把已领取/未知票据说成未写入");db.beginTransaction();try{
            JSONObject marker=sendMarker(r);if(marker==null||!"not_claimed".equals(marker.optString("state"))||!ticket.wireHash.equals(marker.optString("wireSha256")))throw new SecurityException("原发送未派发状态不可证实");
            marker.put("state","abandoned_before_write");ContentValues row=new ContentValues();row.put("receipt",new JSONObject().put("nativeDeviceSend",marker).put("effectState","not_dispatched").put("budgetReserved",true).toString());
            if(db.update("operations",row,"task_id=? AND run_id=? AND operation_id=? AND state='prepared'",new String[]{r.id,r.run,r.operation})!=1)throw new SecurityException("原操作状态已变化，不冒未派发");ticket.abandoned=true;db.setTransactionSuccessful();
        }finally{db.endTransaction();}}
        // Keep the original child hold: signed downstream authority may already exist. No offline refund.
    }
    /** Preserve ORIGINAL native send facts across the original AgentRunner's eventual journal receipt. */
    public JSONObject preserveOutboundReceipt(String id,String run,String operation,JSONObject actual)throws Exception{
        synchronized(owner){JSONObject result=duplicate(actual);result.remove("nativeDeviceSend");result.remove("nativeDeviceAcceptedBinding");try(Cursor row=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND run_id=? AND operation_id=? AND tool IN ('device_invoke_tool','device_delegate_goal')",new String[]{id,run,operation})){if(row.moveToFirst()&&!row.isNull(0)){JSONObject original=new JSONObject(row.getString(0));for(String field:new String[]{"nativeDeviceSend","nativeDeviceAcceptedBinding"})if(original.optJSONObject(field)!=null)result.put(field,original.getJSONObject(field));}}return result;}
    }
    /** Pending/offline/unknown/cancel_requested retain the whole reservation. No refund API exists for those states. */
    public void childPending(String id, String operation, String state) throws Exception {
        if (!state.matches("submitted|unknown|cancel_requested")) throw new SecurityException("不是可保留预留的子任务状态");
        synchronized (owner) { ContentValues row = new ContentValues(); row.put("state", state); db.update("device_child_budget", row, "parent_task_id=? AND parent_operation_id=? AND state!='settled'", new String[]{id, childAllocationForOperation(id, operation).getString("anchorOperationId")}); }
    }
    /** Native authenticated terminal proof only; a model or raw response JSON cannot construct this value. */
    public JSONObject settleChild(dev.pocketengine.features.devices.DeviceTaskCoordinator.ChildResult proof) throws Exception {
        synchronized (owner) {
            proof.check(); JSONObject result = proof.json(); String id = result.getString("parentTaskId"), operation = result.getString("parentOperationId");
            db.beginTransaction(); try {
                JSONObject current = task(id);
                try (Cursor reservation = db.rawQuery("SELECT parent_run_id,parent_epoch,target_device_id,arguments_digest,tools_reserved,models_reserved,state,receipt_json,child_binding FROM device_child_budget WHERE parent_task_id=? AND parent_operation_id=?", new String[]{id, operation})) {
                    if (!reservation.moveToFirst() || !proof.targetDeviceId().equals(reservation.getString(2)) || !result.getString("argumentsDigest").equals(reservation.getString(3))) throw new SecurityException("子终态不属于该原预算预留");
                    if (reservation.getString(6).matches("settled|committed_allocation")) {
                        JSONObject recorded=new JSONObject(reservation.getString(7));recorded.remove("nativeSourceRoute");recorded.remove("allocationRetained");recorded.remove("retainedToolAuthorization");recorded.remove("retainedModelAuthorization");
                        if (!TaskStore.capabilityFingerprint(recorded).equals(TaskStore.capabilityFingerprint(result))) throw new SecurityException("原子任务结算不可替换");
                        db.setTransactionSuccessful(); return new JSONObject().put("settled",reservation.getString(6).equals("settled")).put("allocationCommitted",reservation.getString(6).equals("committed_allocation")).put("knownSettlement", true);
                    }
                    if (reservation.isNull(8)) throw new SecurityException("没有原执行端持久接受的子任务绑定，不能结算预算");
                    JSONObject accepted = new JSONObject(reservation.getString(8));
                    if (!accepted.getString("localTaskId").equals(result.getString("childTaskId")) || !accepted.getString("instanceId").equals(result.getString("instanceId"))
                            || !accepted.optString("runId").isEmpty() && !accepted.getString("runId").equals(result.getString("childRunId"))) throw new SecurityException("原目标设备实例或子执行已变化，不能用别的结果释放预留");
                    int tools = result.getInt("toolsUsed"), models = result.getInt("modelCallsUsed");
                    if (tools < 0 || models < 0 || tools > reservation.getInt(4) || models > reservation.getInt(5)) throw new SecurityException("子用量超过原预留");
                    JSONObject originalReservation=reservation.isNull(7)?new JSONObject():new JSONObject(reservation.getString(7));JSONObject retainedReceipt=duplicate(result);retainedReceipt.remove("nativeSourceRoute");if(originalReservation.optJSONObject("nativeSourceRoute")!=null)retainedReceipt.put("nativeSourceRoute",originalReservation.getJSONObject("nativeSourceRoute"));
                    JSONObject wire=childAllocationForOperation(id,operation).getJSONObject("boundWire");
                    if("ToolInvocation".equals(wire.optString("messageType")))throw new SecurityException("单个tool/job terminal不证明整个tool_session不可再接单；原allocation继续保留，无关闭原生证明不退款");
                    JSONObject forwarding=wire.getJSONObject("arguments").optJSONObject("delegationAuthorization");boolean shared=false;if(forwarding!=null){JSONArray shares=forwarding.getJSONArray("targetShares");for(int i=0;i<shares.length();i++)if(!wire.getString("targetDeviceId").equals(shares.getJSONObject(i).getString("targetDeviceId")))shared=true;}
                    if(shared){
                        // B's real terminal usage is retained as reported data. It does NOT revoke A's
                        // already signed C/D shares. Keep the entire authorization allocation held;
                        // never fabricate provider calls or replenish the parent from B's low report.
                        retainedReceipt.put("allocationRetained",true).put("retainedToolAuthorization",reservation.getInt(4)).put("retainedModelAuthorization",reservation.getInt(5));ContentValues held=new ContentValues();held.put("state","committed_allocation");held.put("receipt_json",retainedReceipt.toString());db.update("device_child_budget",held,"parent_task_id=? AND parent_operation_id=?",new String[]{id,operation});access.event(current,new JSONObject().put("type","device_signed_shares_retained").put("operationId",operation).put("actualReportedTools",tools).put("actualReportedModels",models).put("unusedReallocated",false));db.setTransactionSuccessful();return new JSONObject().put("settled",false).put("allocationCommitted",true).put("actualReportedTools",tools).put("actualReportedModels",models).put("unusedReallocated",false);
                    }
                    JSONObject scope = current.optJSONObject("deviceExecutionScope"); String key = "deviceExecutionScope";
                    if (scope == null) { scope = current.optJSONObject("executionScope"); key = "executionScope"; }
                    if (scope == null) throw new SecurityException("原父预算范围不存在");
                    scope.put("toolsUsed", Math.addExact(scope.getInt("toolsUsed"), tools)).put("modelCallsUsed", Math.addExact(scope.getInt("modelCallsUsed"), models)); current.put(key, scope); access.write(current);
                    ContentValues row = new ContentValues(); row.put("state", "settled"); row.put("receipt_json", retainedReceipt.toString()); db.update("device_child_budget", row, "parent_task_id=? AND parent_operation_id=?", new String[]{id, operation});
                    access.event(current, new JSONObject().put("type", "device_child_budget_settled").put("operationId", operation).put("receipt", result));
                    db.setTransactionSuccessful(); return new JSONObject().put("settled", true).put("knownSettlement", false);
                }
            } finally { db.endTransaction(); }
        }
    }
    public JSONObject acquireControl(dev.pocketengine.features.devices.PairingCoordinator.Permission permission, String id, String run, int epoch, long ttl) throws Exception {
        permission.checkCurrent(System.currentTimeMillis());
        synchronized (owner) {
            JSONObject current = task(id); requireDevice(current); long now = System.currentTimeMillis();
            if (!run.equals(current.optString("runId")) || epoch != current.optInt("coordinatorEpoch") || ttl < 1 || ttl > 300_000) throw new SecurityException("控制执行身份或期限无效");
            JSONObject old = current.optJSONObject("deviceControlLease");
            if (old != null && old.optLong("expiresAt") > now && !permission.callerDeviceId().equals(old.optString("callerDeviceId"))) throw new SecurityException("原任务已有其他有效控制者；观察不会抢占控制");
            String previous = permission.envelope().arguments().optString("previousLeaseId");
            if (old != null && old.optLong("expiresAt") > now && !old.optString("leaseId").equals(previous)) throw new SecurityException("请带原控制租约明确续接，不能隐式抢占");
            JSONObject lease = new JSONObject().put("leaseId", UUID.randomUUID().toString()).put("callerDeviceId", permission.callerDeviceId())
                    .put("runId", run).put("executorEpoch", epoch).put("authorizationEpoch", permission.authorizationEpoch())
                    .put("expiresAt", Math.min(now + ttl, permission.deadline())).put("localOwnerStopAlwaysAllowed", true);
            current.put("deviceControlLease", lease); access.write(current); return duplicate(lease).put("executionMoved", false);
        }
    }
    public void requireControlLease(String caller, String id, String run, int epoch, String leaseId) throws Exception {
        synchronized (owner) {
            JSONObject current = task(id); requireDevice(current); JSONObject lease = current.optJSONObject("deviceControlLease");
            if (lease == null || !caller.equals(lease.optString("callerDeviceId")) || !leaseId.equals(lease.optString("leaseId"))
                    || !run.equals(lease.optString("runId")) || epoch != lease.optInt("executorEpoch") || lease.optLong("expiresAt") <= System.currentTimeMillis()
                    || !run.equals(current.optString("runId")) || epoch != current.optInt("coordinatorEpoch")) throw new SecurityException("控制租约已变化或失效；只读观察不授予控制");
        }
    }
    public JSONObject respond(dev.pocketengine.features.devices.PairingCoordinator.Permission permission, JSONObject args) throws Exception {
        permission.checkCurrent(System.currentTimeMillis());
        synchronized (owner) {
            String id = args.getString("taskId"), run = args.getString("runId"); int epoch = args.getInt("executorEpoch");
            requireControlLease(permission.callerDeviceId(), id, run, epoch, args.getString("controlLeaseId"));
            JSONObject current = task(id); JSONArray approvals = current.optJSONArray("approvals"); String decision = args.getString("decision");
            if (!decision.matches("approved|denied")) throw new SecurityException("此版本只支持原精确审批决定，不接受继续或任意主人输入");
            for (int i = 0; approvals != null && i < approvals.length(); i++) {
                JSONObject approval = approvals.getJSONObject(i);
                if (!args.getString("originalRequestId").equals(approval.optString("id"))) continue;
                if (!run.equals(approval.optString("runId")) || !args.getString("argumentsDigest").equals(approval.optString("argumentsHash"))
                        || !approval.optString("state").equals("pending") || approval.optLong("expiresAt") <= System.currentTimeMillis()) throw new SecurityException("旧审批、参数或执行代次已失效");
                approval.put("state", decision).put("deviceDecision", new JSONObject().put("callerDeviceId", permission.callerDeviceId()).put("controlLeaseId", args.getString("controlLeaseId")).put("authorizationEpoch", permission.authorizationEpoch()));
                access.write(current); return new JSONObject().put("originalRequestId", approval.getString("id")).put("state", decision).put("continued", false).put("effectsApprovedBeyondOriginalArguments", false);
            }
            throw new SecurityException("没有属于该原设备执行的待处理审批");
        }
    }
    @FunctionalInterface public interface ControlMutation { JSONObject run() throws Exception; }
    /** Local SQL-only mutations share the same wire-id index and original operations table. */
    public JSONObject controlOperation(dev.pocketengine.features.devices.PairingCoordinator.Permission permission, ControlMutation mutation) throws Exception {
        permission.checkCurrent(System.currentTimeMillis());
        synchronized (owner) {
            DeviceTaskEnvelope request = permission.envelope(); String remote = remoteOperation(request), id = request.arguments().getString("taskId");
            db.beginTransaction(); try {
                try (Cursor known = db.rawQuery("SELECT arguments_digest,local_operation_id FROM device_inbound WHERE caller_device_id=? AND remote_operation_id=?", new String[]{request.callerDeviceId(), remote})) {
                    if (known.moveToFirst()) {
                        if (!request.argumentsDigest().equals(known.getString(0))) throw new SecurityException("同一控制操作不能改参数或范围");
                        try (Cursor operation = db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND operation_id=?", new String[]{id, known.getString(1)})) {
                            if (!operation.moveToFirst() || operation.isNull(0)) throw new SecurityException("原控制回执待核对，不会重做");
                            JSONObject receipt = new JSONObject(operation.getString(0)).put("knownOperation", true); db.setTransactionSuccessful(); return receipt;
                        }
                    }
                }
                JSONObject current = task(id); requireDevice(current); String operation = localId("device-control", request.callerDeviceId() + ":" + remote);
                JSONObject result = mutation.run(); current = task(id);
                ContentValues original = new ContentValues(); original.put("operation_id", operation); original.put("task_id", id); original.put("run_id", current.optString("runId"));
                original.put("tool", "native_" + request.method().replace('.', '_')); original.put("arguments", request.arguments().toString()); original.put("state", "receipt"); original.put("receipt", result.toString()); original.put("created_at", System.currentTimeMillis()); db.insertOrThrow("operations", null, original);
                ContentValues link = new ContentValues(); link.put("caller_device_id", request.callerDeviceId()); link.put("remote_operation_id", remote); link.put("arguments_digest", request.argumentsDigest()); link.put("local_task_id", id); link.put("local_operation_id", operation); link.put("method", request.method()); link.put("grant_id", request.grantId()); link.put("authorization_epoch", request.authorizationEpoch()); link.put("envelope_json", request.json().toString()); link.put("accepted_at", System.currentTimeMillis()); db.insertOrThrow("device_inbound", null, link);
                permission.checkCurrent(System.currentTimeMillis()); db.setTransactionSuccessful(); return result.put("knownOperation", false);
            } finally { db.endTransaction(); }
        }
    }
    public void recordChildAccepted(dev.pocketengine.features.devices.DeviceTaskCoordinator.ChildAcceptance proof)throws Exception{
        proof.check();JSONObject receipt=proof.json();synchronized(owner){String id=receipt.getString("parentTaskId"),anchor=receipt.getString("parentOperationId"),operation=receipt.getString("sourceOperationId");db.beginTransaction();try{
            JSONObject linked=childAllocationForOperation(id,operation);if(!anchor.equals(linked.getString("anchorOperationId"))||!proof.targetDeviceId().equals(linked.getString("targetDeviceId"))||!receipt.getString("operationArgumentsDigest").equals(linked.getString("wireDigest"))||!receipt.getString("argumentsDigest").equals(linked.getString("anchorArgumentsDigest")))throw new SecurityException("真实接受对象不属于该原prepared operation/allocation");
            try(Cursor row=db.rawQuery("SELECT receipt FROM operations WHERE task_id=? AND operation_id=? AND tool IN ('device_invoke_tool','device_delegate_goal')",new String[]{id,operation})){if(!row.moveToFirst())throw new SecurityException("没有该原Agent设备操作");JSONObject original=row.isNull(0)?new JSONObject():new JSONObject(row.getString(0));JSONObject send=original.optJSONObject("nativeDeviceSend");if(send==null||!"write_claimed".equals(send.optString("state")))throw new SecurityException("没有本原操作TLS write前已领取的native票据，不能凭外来accepted补造发送");JSONObject known=original.optJSONObject("nativeDeviceAcceptedBinding");if(known!=null){for(String field:new String[]{"acceptedObjectKind","objectId","targetOperationId","instanceId","sourceOperationId","operationArgumentsDigest","parentTaskId","parentOperationId","argumentsDigest","executorDeviceId"})if(!known.getString(field).equals(receipt.getString(field)))throw new SecurityException("该原operation接受对象不可换Task/job");if(!known.optString("runId").isEmpty()&&!receipt.optString("runId").isEmpty()&&!known.getString("runId").equals(receipt.getString("runId")))throw new SecurityException("目标原run不可替换");if(receipt.optString("runId").isEmpty()&&!known.optString("runId").isEmpty())receipt.put("runId",known.getString("runId")).put("executorEpoch",known.getLong("executorEpoch"));}JSONObject actualReceipt=receipt.getJSONObject("verifiedOperationReceipt");receipt.remove("verifiedOperationReceipt");receipt.put("verifiedReceipt",duplicate(actualReceipt));JSONObject saved=duplicate(actualReceipt).put("nativeDeviceSend",send).put("nativeDeviceAcceptedBinding",receipt);ContentValues update=new ContentValues();update.put("state","unknown".equals(actualReceipt.optString("effectState"))?"unknown":"receipt");update.put("receipt",saved.toString());if(db.update("operations",update,"task_id=? AND operation_id=?",new String[]{id,operation})!=1)throw new SecurityException("原operation接受映射消失");}
            // Each tool session can contain many genuine PC jobs/operations. Keep per-operation
            // native objects in ORIGINAL operations, never overwrite the allocation's first anchor.
            if(anchor.equals(operation)){ContentValues update=new ContentValues();update.put("child_binding",receipt.toString());update.put("state","submitted");db.update("device_child_budget",update,"parent_task_id=? AND parent_operation_id=? AND state NOT IN ('settled','committed_allocation')",new String[]{id,anchor});}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}}
    }
    public DeviceTaskEnvelope nextAcceptedTool(String id) throws Exception {
        synchronized (owner) {
            requireDevice(task(id));
            try (Cursor row = db.rawQuery("SELECT envelope_json FROM device_inbound WHERE local_task_id=? AND method='tool.invoke' AND local_operation_id IS NULL ORDER BY accepted_at,remote_operation_id LIMIT 1", new String[]{id})) {
                return row.moveToFirst() ? dev.pocketengine.features.devices.DeviceProtocol.parseRequest(row.getString(0).getBytes(StandardCharsets.UTF_8)) : null;
            }
        }
    }
    /** A native pre-effect refusal closes this accepted original operation, without inventing success or requeueing it. */
    public void rejectPending(DeviceExecutionAdmission admission, String reason) throws Exception {
        synchronized (owner) {
            DeviceTaskEnvelope request = admission.request(); String remote = remoteOperation(request);
            db.beginTransaction(); try {
                try (Cursor row = db.rawQuery("SELECT local_task_id,local_operation_id FROM device_inbound WHERE caller_device_id=? AND remote_operation_id=?", new String[]{request.callerDeviceId(), remote})) {
                    if (!row.moveToFirst() || !row.isNull(1)) { db.setTransactionSuccessful(); return; }
                    String id = row.getString(0), operation = localId("device-operation", request.callerDeviceId() + ":" + remote); JSONObject current = task(id);
                    JSONObject receipt = new JSONObject().put("ok", false).put("accepted", false).put("code", reason).put("dispatched", false).put("modelRequests", 0);
                    ContentValues original = new ContentValues(); original.put("operation_id", operation); original.put("task_id", id); original.put("run_id", current.optString("runId")); original.put("tool", request.toolInvocation().toolName()); original.put("arguments", request.toolInvocation().arguments().toString()); original.put("state", "receipt"); original.put("receipt", receipt.toString()); original.put("created_at", System.currentTimeMillis()); db.insertOrThrow("operations", null, original);
                    ContentValues link = new ContentValues(); link.put("local_operation_id", operation); db.update("device_inbound", link, "caller_device_id=? AND remote_operation_id=? AND local_operation_id IS NULL", new String[]{request.callerDeviceId(), remote});
                    access.event(current, new JSONObject().put("type", "operation_receipt").put("operationId", operation).put("receipt", receipt));
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }
    /** Short original-SQL authorization gate only. Cache hashing/chunk I/O runs AFTER this returns. */
    public void requireArtifactAccess(dev.pocketengine.features.devices.PairingCoordinator.Permission permission, String id, String run, String operation) throws Exception {
        permission.checkCurrent(System.currentTimeMillis());
        synchronized (owner) {
            JSONObject current = task(id); requireDevice(current); JSONObject origin = current.getJSONObject("origin");
            if (!permission.callerDeviceId().equals(origin.getString("callerDeviceId")) || !permission.originDeviceId().equals(origin.getString("originDeviceId"))
                    || !permission.targetDeviceId().equals(origin.getString("targetDeviceId")) || !permission.grantId().equals(origin.getString("grantId"))
                    || permission.authorizationEpoch() != origin.getLong("authorizationEpoch") || !run.equals(current.optString("runId"))) throw new SecurityException("成果不属于该设备原任务准确授权");
            try (Cursor receipt = db.rawQuery("SELECT state FROM operations WHERE task_id=? AND run_id=? AND operation_id=?", new String[]{id, run, operation})) {
                if (!receipt.moveToFirst() || !receipt.getString(0).matches("receipt|unknown")) throw new SecurityException("成果没有该原设备操作的真实记录");
            }
            try (Cursor inbound = db.rawQuery("SELECT remote_operation_id FROM device_inbound WHERE caller_device_id=? AND local_task_id=? AND local_operation_id=?", new String[]{permission.callerDeviceId(), id, operation})) {
                if (!inbound.moveToFirst()) throw new SecurityException("成果原操作缺少原设备入站映射");
            }
            permission.checkCurrent(System.currentTimeMillis());
        }
    }
}
