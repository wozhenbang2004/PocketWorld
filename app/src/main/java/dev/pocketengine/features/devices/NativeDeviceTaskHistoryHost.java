package dev.pocketengine.features.devices;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import org.json.JSONObject;

/** Explicit local owner history UI, never a model tool or an execution-scope fallback. */
public final class NativeDeviceTaskHistoryHost {
    public interface NativeReads {
        /** Restore READ-only route using the actual currently authenticated target and original
         * persisted grant receipt; never reconstruct an execution OutgoingGrant from JSON. */
        PairingCoordinator.HistoryRoute currentHistoryRoute(String targetDeviceId,String originalGrantId)throws Exception;
        DeviceCapabilityPort.Reply readHistory(DeviceTaskEnvelope freshQuery,RunControl ioCancellation)throws Exception;
        default DeviceCapabilityPort.Reply readPhoneGoal(PairingCoordinator.HistoryQuery query,RunControl io)throws Exception{
            throw new SecurityException("原目标读取通道未开放。");
        }
    }
    private final TaskStore tasks;private final DeviceIdentity.LocalIdentity local;private final NativeReads source;
    public NativeDeviceTaskHistoryHost(TaskStore originalTasks,DeviceIdentity.LocalIdentity existingLocalIdentity,NativeReads source){tasks=java.util.Objects.requireNonNull(originalTasks);local=java.util.Objects.requireNonNull(existingLocalIdentity);this.source=java.util.Objects.requireNonNull(source);}
    public Window open(String originalTaskId,String originalOperationId)throws Exception{return new Window(tasks.deviceTasks().ownerHistoryEvidence(originalTaskId,originalOperationId));}
    public final class Window implements AutoCloseable {
        private final TaskStoreDeviceTasks.OwnerHistoryEvidence original;private final RunControl io=new RunControl();private volatile boolean closed;
        private Window(TaskStoreDeviceTasks.OwnerHistoryEvidence original){this.original=original;}
        private void require()throws Exception{if(closed)throw new SecurityException("原历史窗口已关闭");io.check();original.requireCurrent();}
        public JSONObject view()throws Exception{
            require();JSONObject view=original.view(),wire=original.originalWire();
            if(directPhoneGoal(wire))view.put("phoneGoalResult",true).put("localPhoneResultPath",localPhoneResultPath(wire));
            require();return view;
        }
        private boolean directPhoneGoal(JSONObject wire) {
            return DeviceProtocol.isDirectPhoneGoal(wire,local.deviceId(),original.taskId(),original.operationId());
        }
        private static final class NoAcceptedRun extends IllegalStateException {
            NoAcceptedRun(){super("还没有这份目标的已核实执行编号。先读取原派发回执；不会另开任务。");}
        }
        /** Navigation only. Never connect to a peer or imply task.get permission or a finished result. */
        private String localPhoneResultPath(JSONObject wire)throws Exception{
            try { phoneGoalBinding(wire);return "original_run"; }
            catch(NoAcceptedRun pending){return "need_dispatch_receipt";}
            catch(SecurityException mismatch){return "unverified_source";}
        }
        /** The permitted object comes from the original stored, authenticated acceptance, not UI input.
         * A deterministic child ID or an accepted submission without a run is not a result binding. */
        private JSONObject phoneGoalBinding(JSONObject wire)throws Exception{
            require();if(!directPhoneGoal(wire))throw new SecurityException("这不是本机直接委派给指定手机的原目标。");
            JSONObject parent=tasks.get(original.taskId(),false);
            if(parent.optJSONObject("origin")!=null||!"owner".equals(parent.optString("actorId"))||!"private".equals(parent.optString("spaceId")))
                throw new SecurityException("只能从本机机主的原工作读取这份目标。");
            JSONObject allocation=tasks.deviceTasks().childAllocationForOperation(original.taskId(),original.operationId());
            JSONObject binding=allocation.optJSONObject("childBinding");
            if(!original.operationId().equals(allocation.optString("anchorOperationId")))throw new SecurityException("这条记录不是原目标派发");
            if(binding==null)throw new NoAcceptedRun();
            String child=DeviceProtocol.receiverTaskId(wire);
            if(!original.operationId().equals(allocation.optString("anchorOperationId"))||!"task".equals(binding.optString("acceptedObjectKind"))
                ||!original.taskId().equals(binding.optString("parentTaskId"))||!original.operationId().equals(binding.optString("sourceOperationId"))
                ||!original.operationId().equals(binding.optString("parentOperationId"))||!wire.getString("argumentsDigest").equals(binding.optString("operationArgumentsDigest"))
                ||!original.targetDeviceId().equals(binding.optString("executorDeviceId"))||!wire.getString("instanceId").equals(binding.optString("instanceId"))
                ||!child.equals(binding.optString("objectId"))||!child.equals(binding.optString("localTaskId")))
                throw new SecurityException("原派发与目标设备、任务、实例的接受回执不一致，未读取结果。");
            if(binding.optString("runId").isEmpty()||binding.optLong("executorEpoch")<=0)throw new NoAcceptedRun();
            return new JSONObject().put("taskId",child).put("runId",binding.getString("runId")).put("executorEpoch",binding.getLong("executorEpoch"));
        }
        /** Explicit task.get, independently authorized even if the owner can read operation.get.
         * Only a small presentation of this exact phone result crosses the Web bridge. No lease,
         * owner input, evidence object, storage write, retry or new run. */
        public JSONObject queryPhoneGoalResult()throws Exception{
            JSONObject wire=original.originalWire(),binding=phoneGoalBinding(wire);PairingCoordinator.HistoryQuery query;
            try {
                PairingCoordinator.HistoryRoute route=source.currentHistoryRoute(original.targetDeviceId(),original.grantId());
                query=route.historyQuery(local,"task.get",binding.getString("taskId"),original.resourceBinding(),System.currentTimeMillis(),5000,null);
            } catch(java.io.IOException offline){throw new java.io.IOException("原手机未连接，未读取结果。",offline);}
            catch(SecurityException denied){throw new SecurityException("原设备身份或授权已变化，或原授权不含任务结果读取（task.get）。未向对端发送读取请求。",denied);}
            if(!wire.getString("instanceId").equals(query.envelope().instanceId()))throw new SecurityException("原手机实例已变化，未读取结果。");
            DeviceCapabilityPort.Reply actual;
            try{actual=source.readPhoneGoal(query,io);}
            catch(java.io.IOException|java.util.concurrent.TimeoutException|java.util.concurrent.ExecutionException unavailable){throw new java.io.IOException("没有收到原手机的可核对答复。连接可能中断，对端也可能未放行；不能判为没有成果。",unavailable);}
            require();
            try{query.requireTargetPeer(actual.peer(),System.currentTimeMillis());}
            catch(SecurityException changed){throw new SecurityException("原手机身份或读取权限已变化，不展示结果。",changed);}
            JSONObject response=DeviceProtocol.validateMessage(actual.response());
            if(!query.envelope().requestId().equals(response.optString("requestId")))throw new SecurityException("结果回执不属于这次读取。");
            if("DeviceError".equals(response.optString("messageType"))){
                String code=response.getJSONObject("error").getString("code");
                if("unauthenticated".equals(code)||"delegation_denied".equals(code)||"wrong_audience".equals(code)||"expired".equals(code))
                    throw new SecurityException("原手机未放行这次读取，不能用派发回执替代目标结果。");
                throw new IllegalStateException("原手机没有提供可核对的目标答复。不能判为没有成果。");
            }
            if(!"DeviceResponse".equals(response.optString("messageType"))||!"task.get".equals(response.optString("method"))
                ||!original.targetDeviceId().equals(response.optString("executorDeviceId"))||!wire.getString("instanceId").equals(response.optString("instanceId")))
                throw new SecurityException("结果回执不是原目标手机与实例。");
            JSONObject task=response.getJSONObject("result"),detail=task.getJSONObject("result"),origin=detail.getJSONObject("origin");
            if(!"TaskSnapshot".equals(task.optString("messageType"))||!"GOAL".equals(detail.optString("executionKind"))
                ||!binding.getString("taskId").equals(task.optString("taskId"))||!binding.getString("runId").equals(task.optString("runId"))
                ||binding.getLong("executorEpoch")!=task.optLong("executorEpoch",-1)||!original.targetDeviceId().equals(task.optString("executorDeviceId"))
                ||!wire.getString("instanceId").equals(task.optString("instanceId"))||!original.taskId().equals(detail.optString("rootTaskId"))
                ||!original.taskId().equals(detail.optString("remoteParentTaskId"))||!"paired_device".equals(origin.optString("kind"))
                ||!original.operationId().equals(origin.optString("remoteOperationId"))||!wire.getString("localTaskId").equals(origin.optString("remoteLocalTaskId"))
                ||!local.deviceId().equals(origin.optString("originDeviceId"))||!local.deviceId().equals(origin.optString("callerDeviceId"))
                ||!original.targetDeviceId().equals(origin.optString("targetDeviceId"))||!original.grantId().equals(origin.optString("grantId"))
                ||wire.getLong("authorizationEpoch")!=origin.optLong("authorizationEpoch",-1)
                ||!DeviceProtocol.canonical(binding).equals(DeviceProtocol.canonical(phoneGoalBinding(wire))))
                throw new SecurityException("不是同一派发、同一设备和同一次执行的目标结果，不展示内容。");
            JSONObject outcome=detail.optJSONObject("phoneGoalOutcome");
            String review=outcome==null?"unavailable":outcome.optString("review");
            if(!java.util.Set.of("confirmed","none","pending","not_confirmed","unverified","unavailable").contains(review)
                ||outcome!=null&&(!(outcome.opt("summary") instanceof String)||!(outcome.opt("truncated") instanceof Boolean)))
                throw new SecurityException("原手机返回的成果格式不能核对。");
            boolean confirmed="confirmed".equals(review);String summary=confirmed?outcome.getString("summary"):"";
            if(confirmed&&(!"succeeded".equals(task.optString("state"))||summary.codePointCount(0,summary.length())>2048))
                throw new SecurityException("对端确认状态与原目标的结束状态或摘要范围不一致。");
            return binding.put("state",task.getString("state")).put("updatedAt",task.getLong("updatedAt")).put("review",review).put("summary",summary)
                .put("truncated",confirmed&&outcome.getBoolean("truncated"))
                .put("instanceId",wire.getString("instanceId")).put("readOnly",true);
        }
        /** Original bounded IO worker only; no network IO while holding TaskStore/SQLite lock. */
        public JSONObject query()throws Exception{
            require();JSONObject frozen=original.originalWire();if(!local.deviceId().equals(frozen.getString("callerDeviceId")))throw new SecurityException("当前原生设备身份不是原SQL caller");PairingCoordinator.HistoryRoute route=source.currentHistoryRoute(original.targetDeviceId(),original.grantId());long now=System.currentTimeMillis();var query=route.historyQuery(local,"operation.get",original.operationId(),original.resourceBinding(),now,5000,null);if(!frozen.getString("instanceId").equals(query.envelope().instanceId()))throw new SecurityException("目标原实例已改变；不能把新实例空结果认作旧操作未发生");
            DeviceCapabilityPort.Reply actual=source.readHistory(query.envelope(),io);require();query.requireTargetPeer(actual.peer(),System.currentTimeMillis());JSONObject response=DeviceProtocol.validateMessage(actual.response());if(!"DeviceResponse".equals(response.getString("messageType"))||!query.envelope().requestId().equals(response.getString("requestId"))||!"operation.get".equals(response.getString("method"))||!original.targetDeviceId().equals(response.getString("executorDeviceId"))||!frozen.getString("instanceId").equals(response.getString("instanceId")))throw new SecurityException("不是这次原对象历史query的实际响应");JSONObject typed=response.getJSONObject("result");tasks.deviceTasks().recordOwnerHistory(original,actual.peer(),typed);JSONObject read=new JSONObject().put("readOnly",true).put("newRun",false).put("retrySubmitted",false).put("unknownRefunded",false).put("operationReceipt",typed).put("unknown",!typed.getBoolean("accepted")||typed.getString("effectState").equals("unknown")).put("businessVerified",false);
            // Keep the signed operation receipt unchanged. Re-read only this local binding after recording it.
            if(directPhoneGoal(frozen))read.put("localPhoneResultPath",localPhoneResultPath(frozen));
            require();return read;
        }
        @Override public void close(){closed=true;io.cancel();}
    }
}
