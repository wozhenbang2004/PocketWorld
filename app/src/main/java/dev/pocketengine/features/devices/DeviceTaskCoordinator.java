package dev.pocketengine.features.devices;

import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native ingress-to-existing-host adapter. It does not run another Agent loop or recreate controls. */
public final class DeviceTaskCoordinator {
    @FunctionalInterface public interface Acceptance { JSONObject commit() throws Exception; }
    /** Native short accept gate only. Lock order is receiver gate -> TaskStore/SQLite.
     * Never acquire the gate while already holding TaskStore, or hold it over host/network work. */
    @FunctionalInterface public interface AcceptanceGate { JSONObject accept(Acceptance originalSqlAcceptance) throws Exception; }
    public interface NativeHost {
        void acceptTools(String localTaskId, DeviceExecutionAdmission admission) throws Exception;
        void acceptGoal(String localTaskId, DeviceExecutionAdmission admission) throws Exception;
        /** Deliver the durable pause/cancel request to the actual retained executor, not a replacement. */
        void controlRequested(String localTaskId, String action) throws Exception;
        default DeviceOwnerInputs ordinaryInputs(){return null;}
        default void ordinaryInputAccepted(DeviceOwnerInputs.Scope original,JSONObject accepted)throws Exception{throw new SecurityException("原设备输入宿主尚未配置");}
    }
    private final TaskStore tasks;
    private final TaskStoreDeviceTasks devices;
    private final NativeHost host;
    private final String localDeviceId, localInstanceId;
    public DeviceTaskCoordinator(TaskStore originalTasks, NativeHost nativeHost, String localDeviceId, String localInstanceId) {
        this.localDeviceId = java.util.Objects.requireNonNull(localDeviceId); this.localInstanceId = java.util.Objects.requireNonNull(localInstanceId);
        tasks = java.util.Objects.requireNonNull(originalTasks); devices = tasks.deviceTasks(); host = java.util.Objects.requireNonNull(nativeHost);
    }
    public JSONObject submit(DeviceExecutionAdmission admission) throws Exception {
        return submit(admission,Acceptance::commit);
    }
    public JSONObject submit(DeviceExecutionAdmission admission,AcceptanceGate gate) throws Exception {
        if (admission.kind() == DeviceExecutionAdmission.Kind.DEVICE_TOOLS) DeviceToolsRuntime.validateInvocation(admission.request());
        JSONObject accepted = java.util.Objects.requireNonNull(gate).accept(()->devices.accept(admission)); // Gate and SQL end before host pickup.
        if (accepted.optBoolean("knownOperation")) return accepted;
        try {
            if (admission.kind() == DeviceExecutionAdmission.Kind.DEVICE_TOOLS) host.acceptTools(accepted.getString("localTaskId"), admission);
            else host.acceptGoal(accepted.getString("localTaskId"), admission);
        } catch (Exception dependency) {
            try { devices.noteFirstPickupWait(accepted.getString("localTaskId"), admission, TaskStoreDeviceTasks.FirstPickupWait.LOCAL_DEPENDENCY); }
            catch (Exception noLongerVerifiable) { /* Acceptance is already durable. Never reinterpret it as a retry or a non-start. */ }
            return firstAcceptanceFacts(accepted);
        }
        // A normal goal can already be awaiting the owner's model, running or interrupted.
        // The outer receipt reads the current lifecycle from SQL, not the pre-host snapshot.
        return admission.kind()==DeviceExecutionAdmission.Kind.GOAL ? firstAcceptanceFacts(accepted) : accepted;
    }

    private static JSONObject firstAcceptanceFacts(JSONObject accepted) throws Exception {
        JSONObject facts = new JSONObject();
        for (String key : new String[]{"accepted","knownOperation","operationId","argumentsDigest","localTaskId","acceptedAt",
                "origin","executionKind","budget"})
            facts.put(key, accepted.get(key));
        return facts.put("nextAction","read_original_task");
    }
    public JSONObject operation(PairingCoordinator.Permission permission) throws Exception {
        permission.checkCurrent(System.currentTimeMillis());
        return devices.snapshot(permission.callerDeviceId(), permission.envelope().arguments().getString("operationId"));
    }
    public JSONObject operation(PairingCoordinator.HistoryPermission permission)throws Exception{permission.checkCurrent(System.currentTimeMillis());return devices.snapshot(permission.callerDeviceId(),permission.envelope().arguments().getString("operationId"));}
    private JSONObject requireHistoryTask(PairingCoordinator.HistoryPermission permission,String id)throws Exception{
        permission.checkCurrent(System.currentTimeMillis());JSONObject audience=permission.audience();if(!id.equals(audience.getString("taskId"))||!permission.callerDeviceId().equals(audience.getString("callerDeviceId")))throw new SecurityException("不是该原SQL受众的任务");JSONObject task=tasks.get(id,false);if(!TaskStoreDeviceTasks.deviceTask(task))throw new SecurityException("原设备任务已不可见");return task;
    }
    public PairingCoordinator.NativeHistoryAudienceSource historyAudiences(){return devices.historyAudiences();}
    public JSONObject operationReceipt(DeviceTaskEnvelope request,JSONObject nativePayload)throws Exception{return devices.operationReceipt(request,localDeviceId,localInstanceId,nativePayload);}
    private JSONObject requireTask(PairingCoordinator.Permission permission, String id) throws Exception {
        permission.checkCurrent(System.currentTimeMillis()); JSONObject task = tasks.get(id, false);
        if (!TaskStoreDeviceTasks.deviceTask(task) || !permission.callerDeviceId().equals(task.getJSONObject("origin").getString("callerDeviceId"))
                || !permission.originDeviceId().equals(task.getJSONObject("origin").getString("originDeviceId"))
                || !permission.grantId().equals(task.getJSONObject("origin").getString("grantId"))
                || permission.authorizationEpoch() != task.getJSONObject("origin").getLong("authorizationEpoch"))
            throw new SecurityException("请求不属于该设备原任务授权");
        if(!DeviceProtocol.resourceEquals(permission.envelope().resourceBinding(),task.getJSONObject("deviceExecutionScope").getJSONObject("resourceBinding")))throw new SecurityException("管理请求必须绑定该原任务准确resource，不能凭taskId扩大");
        return task;
    }
    public JSONObject task(PairingCoordinator.Permission permission) throws Exception {
        return taskSnapshot(requireTask(permission,permission.envelope().arguments().getString("taskId")),permission.callerDeviceId(),permission.authorizationEpoch());
    }
    public JSONObject task(PairingCoordinator.HistoryPermission permission)throws Exception{
        JSONObject task=requireHistoryTask(permission,permission.envelope().arguments().getString("taskId"));
        JSONObject snapshot=taskSnapshot(task,permission.callerDeviceId(),permission.authorizationEpoch());
        projectReviewedPhoneGoal(permission,task,snapshot);
        permission.checkCurrent(System.currentTimeMillis());
        return snapshot;
    }
    /** A dispatch receipt or current task.summary can be merely a lifecycle message. Publish only
     * the bounded text previously visible on this task AND reviewed on the receiving phone.
     * The original B-owner review digest covers precisely that Runner summary and its evidence;
     * the evidence itself, tools, inputs, captures and leases are not part of this projection. */
    private void projectReviewedPhoneGoal(PairingCoordinator.HistoryPermission reader,JSONObject task,JSONObject snapshot)throws Exception{
        JSONObject origin=task.getJSONObject("origin"),resource=reader.resourceBinding();
        String caller=reader.callerDeviceId(),run=task.optString("runId");int epoch=task.optInt("coordinatorEpoch");
        if(!"GOAL".equals(task.optString("deviceExecutionKind"))||!"paired_device".equals(origin.optString("kind"))
            ||!caller.equals(origin.optString("callerDeviceId"))||!caller.equals(origin.optString("originDeviceId"))
            ||!localDeviceId.equals(origin.optString("targetDeviceId"))||!reader.grantId().equals(origin.optString("grantId"))
            ||reader.authorizationEpoch()!=origin.optLong("authorizationEpoch")||reader.acceptedAuthorizationEpoch()!=reader.authorizationEpoch()
            ||origin.optString("parentTaskId").isEmpty()||!origin.optString("rootTaskId").equals(origin.optString("parentTaskId"))
            ||!DeviceProtocol.resourceEquals(resource,task.getJSONObject("deviceExecutionScope").getJSONObject("resourceBinding"))
            ||!(DeviceProtocol.acceptedTextReturn(task) || ("main_screen".equals(resource.optString("screenMode"))||"background".equals(resource.optString("screenMode")))
               &&resource.opt("androidUserId") instanceof Number&&resource.optJSONArray("packageNames")!=null&&resource.optJSONArray("packageNames").length()>0)
            ||run.isEmpty()||epoch<=0)return;
        JSONObject detail=snapshot.getJSONObject("result"),saved=task.optJSONObject("deviceGoalResult");
        JSONObject onward=new JSONObject().put("review","none").put("summary","").put("truncated",false);
        detail.put("phoneGoalOutcome",onward);
        if(saved==null)return;
        if(!run.equals(saved.optString("runId"))||epoch!=saved.optInt("executorEpoch")||!saved.optBoolean("nativeRunnerExited")
            ||!saved.optBoolean("nativeDispatchClosed")||!"needs_confirmation".equals(saved.optString("status"))){onward.put("review","unverified");return;}
        if(!saved.optBoolean("closed")){
            // A retained candidate is not a currently actionable review. Keep the existing
            // protocol vocabulary and send no candidate, evidence, or internal failure reason.
            boolean pending=devices.originalOwnerReviewPending(task.getString("id"),run,epoch,saved.optLong("recordedAt"),task.optLong("updatedAt"));
            onward.put("review",pending?"pending":"unverified");return;
        }
        if(!saved.optBoolean("ownerVerified")||!"completed".equals(task.optString("status"))){onward.put("review","not_confirmed");return;}
        JSONObject evidence=task.optJSONObject("evidence"),check=evidence==null?null:evidence.optJSONObject("nativeDeviceGoalOwnerReview");
        if(check==null||!check.optBoolean("completed")||!check.optBoolean("sameRun")||!(saved.opt("summary") instanceof String)){onward.put("review","unverified");return;}
        JSONObject reviewed=new JSONObject(saved.toString());reviewed.put("closed",false);reviewed.remove("ownerVerified");reviewed.remove("reviewedAt");
        boolean privateChoice=saved.has("ownerOnlyText")||check.has("ownerOnlyText");
        if(privateChoice){
            if(!Boolean.TRUE.equals(saved.opt("ownerOnlyText"))||!Boolean.TRUE.equals(check.opt("ownerOnlyText"))
                    ||!DevicePrivateText.mayKeepOriginalText(task)){onward.put("review","unverified");return;}
            reviewed.remove("ownerOnlyText");
        }
        if(!TaskStore.capabilityFingerprint(reviewed).equals(check.optString("resultDigest"))){onward.put("review","unverified");return;}
        if(privateChoice){onward.put("review","confirmed").put("summaryWithheld",true);return;}
        String text=saved.getString("summary");int length=text.codePointCount(0,text.length());
        // At most 2,048 Unicode code points (8 KiB UTF-8), never split a surrogate pair.
        onward.put("review","confirmed").put("truncated",length>2048).put("summary",length>2048?text.substring(0,text.offsetByCodePoints(0,2048)):text);
    }
    private JSONObject taskSnapshot(JSONObject task,String caller,long authorizationEpoch)throws Exception{
        JSONArray events = task.optJSONArray("events"); long cursor = 0;
        for (int i = 0; events != null && i < events.length(); i++) cursor = Math.max(cursor, events.getJSONObject(i).optLong("seq", i + 1L));
        JSONObject detail = DevicePrivateText.applies(task) ? DevicePrivateText.identity(task) : ordinaryDetails(task,caller,authorizationEpoch);
        JSONObject snapshot = new JSONObject().put("protocolVersion", DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType", "TaskSnapshot")
                .put("taskId", task.getString("id")).put("executorDeviceId", localDeviceId).put("instanceId", localInstanceId)
                .put("state", TaskStoreDeviceTasks.wireState(task)).put("revision", Math.max(1, task.optLong("deviceStateRevision", task.optLong("updatedAt", 1))))
                .put("eventCursor", Long.toString(cursor)).put("updatedAt", task.optLong("updatedAt", 1)).put("result", detail);
        if (!task.optString("runId").isEmpty()) snapshot.put("runId", task.getString("runId")).put("executorEpoch", task.getInt("coordinatorEpoch"));
        return DeviceProtocol.validateMessage(snapshot);
    }
    private JSONObject ordinaryDetails(JSONObject task,String caller,long authorizationEpoch)throws Exception{
        JSONObject detail = new JSONObject().put("summary", task.optString("summary")).put("origin", task.getJSONObject("origin"))
                .put("rootTaskId", task.getString("rootTaskId")).put("remoteParentTaskId", task.optString("remoteParentTaskId"))
                .put("executionKind", task.getString("deviceExecutionKind")).put("transportStateIsNotExecutionState", true);
        DeviceOwnerInputs inputs=host.ordinaryInputs();if(inputs!=null){JSONObject input=inputs.view(task);if(input!=null)detail.put("ownerInput",input);}
        // Return only this authenticated caller's still-current original lease. Read-only
        // history under a later authorization epoch cannot recover an old control identity.
        JSONObject lease=task.optJSONObject("deviceControlLease"),origin=task.getJSONObject("origin");
        if(lease!=null&&!task.optBoolean("cancelRequested")&&task.optString("status").matches("preparing|running|pausing|paused|awaiting_user")
                &&caller.equals(origin.optString("callerDeviceId"))&&caller.equals(lease.optString("callerDeviceId"))
                &&authorizationEpoch==origin.optLong("authorizationEpoch")&&authorizationEpoch==lease.optLong("authorizationEpoch")
                &&!task.optString("runId").isEmpty()&&task.optString("runId").equals(lease.optString("runId"))
                &&task.optInt("coordinatorEpoch")>0&&task.optInt("coordinatorEpoch")==lease.optInt("executorEpoch")
                &&!lease.optString("leaseId").isEmpty()&&lease.optLong("expiresAt")>System.currentTimeMillis())
            detail.put("controlLease",new JSONObject().put("controlLeaseId",lease.getString("leaseId")).put("expiresAt",lease.getLong("expiresAt")));
        JSONObject resumeRequest=TaskStoreDeviceTasks.resumeRequestView(task);
        if(resumeRequest!=null)detail.put("resumeRequest",resumeRequest);
        JSONObject evidence = task.optJSONObject("evidence");
        if (evidence != null && evidence.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 16_000) detail.put("evidence", evidence);
        else if (evidence != null) detail.put("evidenceTruncated", true).put("nextAction", "read_original_operation_receipts");
        return detail;
    }
    public JSONObject events(PairingCoordinator.Permission permission) throws Exception {
        JSONObject args=permission.envelope().arguments();return eventPage(requireTask(permission,args.getString("taskId")),args);
    }
    public JSONObject events(PairingCoordinator.HistoryPermission permission)throws Exception{JSONObject args=permission.envelope().arguments();return eventPage(requireHistoryTask(permission,args.getString("taskId")),args);}
    private JSONObject eventPage(JSONObject task,JSONObject args)throws Exception{
        String rawCursor = args.optString("cursor", "0");
        if (!rawCursor.matches("[0-9]{1,16}")) throw new SecurityException("无效原任务事件游标");
        long cursor = Long.parseLong(rawCursor); int limit = Math.min(32, args.optInt("limit", 20));
        boolean privateText=DevicePrivateText.applies(task);
        JSONArray source = task.optJSONArray("events"), page = new JSONArray(); long next = cursor; boolean more = false,snapshotRequired=false;
        for (int i = 0; source != null && i < source.length(); i++) {
            JSONObject event = source.getJSONObject(i); long sequence = event.optLong("seq", i + 1L);
            if (sequence <= cursor) continue;
            JSONObject visible=privateText?new JSONObject():event;
            if (page.length() >= limit || page.toString().length() + visible.toString().length() > 24_000) { more = true;snapshotRequired=page.length()==0; break; }
            JSONObject wire = new JSONObject().put("protocolVersion", DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType", "TaskEvent")
                    .put("taskId", task.getString("id")).put("executorDeviceId", localDeviceId).put("instanceId", localInstanceId)
                    .put("sequence", sequence).put("cursor", Long.toString(sequence)).put("kind", privateText?"private_lifecycle":event.optString("type", "task_event"))
                    .put("createdAt", Math.max(1, event.optLong("at", task.optLong("updatedAt", 1)))).put("data", visible);
            page.put(DeviceProtocol.validateMessage(wire)); next = sequence;
        }
        return new JSONObject().put("events", page).put("nextCursor", Long.toString(next))
                .put("hasMore", more).put("snapshotRequired", snapshotRequired||cursor > next && page.length() == 0);
    }
    public JSONObject cancelOperation(PairingCoordinator.OperationManagementPermission permission)throws Exception{
        JSONObject actual=devices.cancelAcceptedGoal(permission);if(actual.optBoolean("requested")&&!actual.optBoolean("stopped"))try{host.controlRequested(actual.getString("localTaskId"),"cancel");}catch(Exception notAcknowledged){/* Requested is not proof that the original executor stopped. */}return devices.operationManagementReceipt(permission.envelope(),localDeviceId,localInstanceId,actual);
    }
    public JSONObject control(PairingCoordinator.Permission permission, String action) throws Exception {
        JSONObject args = permission.envelope().arguments(), task = requireTask(permission, args.getString("taskId"));
        devices.requireControlLease(permission.callerDeviceId(), task.getString("id"), args.getString("runId"), args.getInt("executorEpoch"), args.getString("controlLeaseId"));
        JSONObject requested = devices.controlOperation(permission, () -> devices.requestControl(permission.callerDeviceId(), task.getString("id"), args.getString("runId"), args.getInt("executorEpoch"), action));
        if (!requested.optBoolean("knownOperation")) host.controlRequested(task.getString("id"), action); return requested;
    }
    public JSONObject acquireControl(PairingCoordinator.Permission permission) throws Exception {
        JSONObject args = permission.envelope().arguments(), task = requireTask(permission, args.getString("taskId"));
        return devices.controlOperation(permission, () -> devices.acquireControl(permission, task.getString("id"), args.getString("runId"), args.getInt("executorEpoch"), args.getLong("ttlMs")));
    }
    /** Only the original device's exact native approval is changed, never appendOwnerInput/start/resume. */
    public JSONObject respond(PairingCoordinator.Permission permission) throws Exception {
        JSONObject args = permission.envelope().arguments(), task = requireTask(permission, args.getString("taskId"));
        devices.requireControlLease(permission.callerDeviceId(), task.getString("id"), args.getString("runId"), args.getInt("executorEpoch"), args.getString("controlLeaseId"));
        JSONObject result=devices.controlOperation(permission, () -> devices.respond(permission, args));
        if(!result.optBoolean("knownOperation")){
            try{host.controlRequested(task.getString("id"),"approval_responded:"+args.getString("originalRequestId"));result.put("continuationRequested",true);}
            catch(Exception notResumed){result.put("continuationRequested",false).put("continuationState","waiting_dependency");}
            result.put("newGrant",false);
        }
        return result;
    }
    public JSONObject ownerInput(PairingCoordinator.Permission permission)throws Exception {
        JSONObject args=permission.envelope().arguments(),task=requireTask(permission,args.getString("taskId"));
        // A committed original receipt is read under the same current Permission.
        // controlOperation still verifies its immutable digest; no session, lease,
        // mailbox or continuation is touched for an already accepted operation.
        if(devices.snapshot(permission.callerDeviceId(),permission.envelope().operationId()).optBoolean("accepted"))
            return devices.controlOperation(permission,()->{throw new SecurityException("原输入索引已变化，只核对原回执，不重新派发");}).put("newGrant",false).put("executionAuthorized",false);
        var inputs=host.ordinaryInputs();if(inputs==null)throw new SecurityException("原设备普通输入宿主尚未安装");
        boolean answer="task.answerQuestion".equals(permission.envelope().method());var original=inputs.scope(task.getString("id"),answer);
        devices.requireControlLease(permission.callerDeviceId(),task.getString("id"),args.getString("runId"),args.getInt("executorEpoch"),args.getString("controlLeaseId"));
        JSONObject result=devices.controlOperation(permission,()->tasks.appendDeviceOwnerInput(permission,original));
        if(!result.optBoolean("knownOperation")&&!result.optBoolean("duplicateInput")&&answer){
            try{host.ordinaryInputAccepted(original,result);result.put("continuationRequested",true);}
            catch(Exception notPickedUp){result.put("continuationRequested",false).put("continuationState","saved_waiting_original_dependency");}
        }
        return result.put("newGrant",false).put("executionAuthorized",false);
    }

    /** This stores a request for the local phone owner. It never wakes a runner. */
    public JSONObject requestResume(PairingCoordinator.Permission permission)throws Exception{
        JSONObject args=permission.envelope().arguments();
        JSONObject task=requireTask(permission,args.getString("taskId"));
        JSONObject result=devices.controlOperation(permission,()->devices.requestResume(permission));
        if(!result.optBoolean("knownOperation")){
            try{host.controlRequested(task.getString("id"),"resume_requested");}
            catch(Exception notificationUnavailable){result.put("ownerNotificationPending",true);}
        }
        return result.put("newRun",false).put("newGrant",false).put("executionAuthorized",false);
    }

    public static final class ChildAcceptance {
        private final DeviceIdentity.AuthenticatedPeer peer; private final String body;
        private ChildAcceptance(DeviceIdentity.AuthenticatedPeer peer, JSONObject result) throws Exception { this.peer = java.util.Objects.requireNonNull(peer); body = result.toString(); check(); }
        public String targetDeviceId() { return peer.deviceId(); }
        public JSONObject json() throws Exception { return new JSONObject(body); }
        public void check() throws Exception {
            JSONObject result = json();
            if (!peer.deviceId().equals(result.optString("executorDeviceId")) || !Boolean.TRUE.equals(result.opt("accepted"))
                    || result.optLong("acceptedAt") < 1 || result.optString("objectId").isEmpty() || !java.util.Set.of("task","job","operation").contains(result.optString("acceptedObjectKind")) || result.optString("instanceId").isEmpty()
                    || result.optString("parentTaskId").isEmpty() || result.optString("parentOperationId").isEmpty()
                    || result.optString("sourceOperationId").isEmpty()||!result.optString("operationArgumentsDigest").matches("[a-f0-9]{64}")
                    || !result.optString("argumentsDigest").matches("[a-f0-9]{64}")) throw new SecurityException("缺少目标原SQL持久接受的准确关联");
            String field=result.getString("acceptedObjectKind").equals("task")?"localTaskId":result.getString("acceptedObjectKind").equals("job")?"jobId":"targetOperationId";if(!result.getString("objectId").equals(result.optString(field)))throw new SecurityException("接受对象kind不能冒另一个目标Task/job");
            JSONObject receipt=DeviceProtocol.validateMessage(result.getJSONObject("verifiedOperationReceipt"));if(!"OperationReceipt".equals(receipt.optString("messageType"))||!receipt.optBoolean("accepted")||!peer.deviceId().equals(receipt.optString("executorDeviceId"))||!result.getString("sourceOperationId").equals(receipt.optString("operationId"))||!result.getString("operationArgumentsDigest").equals(receipt.optString("argumentsDigest")))throw new SecurityException("必须先有此目标原operation的唯一typed回执，不能拿payload冒accepted");
        }
    }
    public static ChildAcceptance authenticatedChildAcceptance(DeviceIdentity.AuthenticatedPeer peer, JSONObject response) throws Exception { return new ChildAcceptance(peer, response); }
    /** Opaque authenticated terminal response; required to release a parent's reserved child budget. */
    public static final class ChildResult {
        private final DeviceIdentity.AuthenticatedPeer peer;
        private final String body;
        private ChildResult(DeviceIdentity.AuthenticatedPeer peer, JSONObject response) throws Exception {
            this.peer = java.util.Objects.requireNonNull(peer); this.body = response.toString(); check();
        }
        public String targetDeviceId() { return peer.deviceId(); }
        public JSONObject json() throws Exception { return new JSONObject(body); }
        public void check() throws Exception {
            JSONObject result = json();
            if (!peer.deviceId().equals(result.optString("executorDeviceId")) || !Boolean.TRUE.equals(result.opt("terminal"))
                    || !result.optString("status").matches("succeeded|failed|cancelled") || !Boolean.TRUE.equals(result.opt("stopped"))
                    || Boolean.TRUE.equals(result.opt("hasUnresolved")) || result.optString("instanceId").isEmpty()
                    || result.optString("childTaskId").isEmpty() || result.optString("childRunId").isEmpty() || result.optInt("executorEpoch") < 1
                    || result.optString("parentTaskId").isEmpty() || result.optString("parentOperationId").isEmpty()
                    || !result.optString("argumentsDigest").matches("[a-f0-9]{64}")
                    || result.optInt("toolsUsed", -1) < 0 || result.optInt("modelCallsUsed", -1) < 0)
                throw new SecurityException("缺少原执行端可核对终态/停止/用量；保留子预算，不因离线或请求取消退款");
        }
    }
    public ChildResult authenticatedChildResult(DeviceIdentity.AuthenticatedPeer peer, JSONObject response) throws Exception {
        return new ChildResult(peer, response);
    }
}
