package dev.pocketengine.features.devices;

import org.json.JSONObject;

/** The sole closed-schema device ingress. Network JSON can never supply a RunControl, task document,
 * native owner identity, executionScope, or an instruction to call generic create/start/append APIs. */
public final class DeviceIngress {
    @FunctionalInterface public interface Artifacts { JSONObject receive(PairingCoordinator.Permission permission) throws Exception;
        default JSONObject receiveHistory(PairingCoordinator.HistoryPermission permission)throws Exception{throw new SecurityException("成果原生历史通道未配置");}
        default JSONObject acceptedHistoryAudience(DeviceTaskEnvelope query)throws Exception{throw new SecurityException("成果原生SQL历史受众未配置");}
    }
    private final Artifacts artifacts;
    private final DeviceTaskCoordinator.AcceptanceGate acceptanceGate;
    private final String localDeviceId, localInstanceId;
    private final PairingCoordinator pairing;
    private final DeviceExecutionAdmission.LocalPolicy localPolicy;
    private final DeviceCapabilityRegistry capabilities;
    private final DeviceTaskCoordinator tasks;
    public DeviceIngress(String localDeviceId, String localInstanceId, PairingCoordinator pairing, DeviceExecutionAdmission.LocalPolicy localPolicy,
            DeviceCapabilityRegistry capabilities, DeviceTaskCoordinator tasks) {
        this(localDeviceId, localInstanceId, pairing, localPolicy, capabilities, tasks, null);
    }
    public DeviceIngress(String localDeviceId, String localInstanceId, PairingCoordinator pairing, DeviceExecutionAdmission.LocalPolicy localPolicy,
            DeviceCapabilityRegistry capabilities, DeviceTaskCoordinator tasks, Artifacts artifacts) {
        this(localDeviceId,localInstanceId,pairing,localPolicy,capabilities,tasks,artifacts,commit->{throw new SecurityException("原native接收开关/AcceptanceGate未安装，不接受新执行");});
    }
    public DeviceIngress(String localDeviceId,String localInstanceId,PairingCoordinator pairing,DeviceExecutionAdmission.LocalPolicy localPolicy,
            DeviceCapabilityRegistry capabilities,DeviceTaskCoordinator tasks,Artifacts artifacts,DeviceTaskCoordinator.AcceptanceGate acceptanceGate){
        this.artifacts = artifacts;
        this.acceptanceGate=java.util.Objects.requireNonNull(acceptanceGate);
        this.localDeviceId = java.util.Objects.requireNonNull(localDeviceId); this.localInstanceId = java.util.Objects.requireNonNull(localInstanceId); this.pairing = java.util.Objects.requireNonNull(pairing);
        this.localPolicy = java.util.Objects.requireNonNull(localPolicy); this.capabilities = java.util.Objects.requireNonNull(capabilities);
        this.tasks = java.util.Objects.requireNonNull(tasks);
    }
    /** Authenticated peer is issued from the completed native TLS session, NEVER parsed from request JSON. */
    public JSONObject receive(DeviceIdentity.AuthenticatedPeer peer, byte[] raw) throws Exception {
        DeviceTaskEnvelope request = DeviceProtocol.validateRequest(raw, peer, localDeviceId, System.currentTimeMillis());
        if(request.method().equals("device.describe")){var discovery=pairing.checkDiscoveryPermission(peer,request,System.currentTimeMillis());return response(request,capabilities.describe(discovery));}
        if(request.method().equals("operation.cancel")){var management=pairing.checkOperationManagementPermission(peer,request,tasks.historyAudiences(),System.currentTimeMillis());return response(request,tasks.cancelOperation(management));}
        if(PairingCoordinator.HISTORY_METHODS.contains(request.method())){
            PairingCoordinator.NativeHistoryAudienceSource nativeTasks=tasks.historyAudiences();
            var audience=new PairingCoordinator.NativeHistoryAudienceSource(){
                public JSONObject acceptedAudience(DeviceTaskEnvelope query)throws Exception{return query.method().startsWith("artifact.")?java.util.Objects.requireNonNull(artifacts,"原成果通道未配置").acceptedHistoryAudience(query):nativeTasks.acceptedAudience(query);}
                public boolean sourceVisible(JSONObject accepted,long now)throws Exception{return nativeTasks.sourceVisible(accepted,now);}
            };
            var history=pairing.checkHistoryPermission(peer,request,audience,System.currentTimeMillis());history.checkCurrent(System.currentTimeMillis());JSONObject read=switch(request.method()){
                case "operation.get"->tasks.operation(history);case "task.get"->tasks.task(history);case "task.events"->tasks.events(history);
                case "artifact.describe","artifact.readChunk"->java.util.Objects.requireNonNull(artifacts,"原成果通道未配置").receiveHistory(history);default->throw new SecurityException("不是原结果只读方法");};
            return response(request,read);
        }
        PairingCoordinator.Permission permission = pairing.checkPermission(peer, request, System.currentTimeMillis());
        permission.checkCurrent(System.currentTimeMillis()); JSONObject result;
        result = switch (request.method()) {
            case "tool.invoke", "task.submitGoal" -> tasks.submit(DeviceExecutionAdmission.issue(permission, localPolicy),acceptanceGate);
            case "operation.get" -> tasks.operation(permission);
            case "task.get" -> tasks.task(permission);
            case "task.events" -> tasks.events(permission);
            case "task.acquireControl" -> tasks.acquireControl(permission);
            case "task.pause" -> tasks.control(permission, "pause");
            case "task.cancel" -> tasks.control(permission, "cancel");
            case "task.respond" -> tasks.respond(permission);
            case "artifact.describe", "artifact.readChunk" -> {
                if (artifacts == null) throw new SecurityException("本机准确成果通道未配置，不能以元数据冒充真实字节");
                yield artifacts.receive(permission);
            }
            default -> throw new SecurityException("该设备方法不属于本机任务准入；请使用准确成果通道");
        };
        // Do not treat loss of authorization after a real effect as proof it did not happen.
        return response(request,result);
    }
    private JSONObject response(DeviceTaskEnvelope request,JSONObject result)throws Exception{
        if(java.util.Set.of("tool.invoke","task.submitGoal","operation.get","task.pause","task.cancel","task.respond","task.acquireControl").contains(request.method()))result=tasks.operationReceipt(request,result);
        return DeviceProtocol.validateMessage(new JSONObject().put("protocolVersion", DeviceProtocol.VERSION).put("protocolProfile",DeviceProtocol.PROFILE).put("messageType", "DeviceResponse")
                .put("requestId", request.requestId()).put("method", request.method()).put("executorDeviceId", localDeviceId)
                .put("instanceId", localInstanceId).put("result", result));
    }
}
