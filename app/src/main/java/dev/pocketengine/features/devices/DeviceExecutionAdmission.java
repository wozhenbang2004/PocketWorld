package dev.pocketengine.features.devices;

import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opaque native evidence, issued only after authenticated pairing and local receiver opt-in.
 * A task document, wire envelope, or persisted scope is never sufficient to recreate it. */
public final class DeviceExecutionAdmission {
    public enum Kind { DEVICE_TOOLS, GOAL }
    public interface LocalPolicy {
        /** Recheck the actual owner switch, local grants and capability readiness. No network I/O. */
        void checkCurrent(DeviceTaskEnvelope request, Kind kind) throws Exception;
    }
    private final PairingCoordinator.Permission permission;
    private final LocalPolicy localPolicy;
    private final DeviceTaskEnvelope request;
    private final Kind kind;
    private final String mode;

    private DeviceExecutionAdmission(PairingCoordinator.Permission permission, LocalPolicy policy,
            Kind kind, String mode) throws Exception {
        this.permission = Objects.requireNonNull(permission);
        this.localPolicy = Objects.requireNonNull(policy);
        this.request = permission.envelope();
        this.kind = kind;
        this.mode = mode;
        checkCurrent();
    }
    static DeviceExecutionAdmission issue(PairingCoordinator.Permission permission, LocalPolicy policy) throws Exception {
        DeviceTaskEnvelope request = permission.envelope();
        Kind kind = "tool.invoke".equals(request.method()) ? Kind.DEVICE_TOOLS : "task.submitGoal".equals(request.method()) ? Kind.GOAL : null;
        if (kind == null) throw new SecurityException("该设备请求不是本机执行准入");
        if(!DeviceProtocol.VERSION.equals(request.json().optString("capabilityVersion",DeviceProtocol.VERSION)))throw new SecurityException("本机没有该准确能力版本，不能按旧工具猜测执行");
        JSONObject binding = request.json().optJSONObject("resourceBinding");
        String mode = binding == null ? "capabilities" : binding.optString("screenMode", "capabilities");
        if (!mode.matches("main_screen|background|capabilities")) throw new SecurityException("未声明支持的本机执行位置");
        if (kind == Kind.DEVICE_TOOLS && mode.equals("capabilities"))
            throw new SecurityException("手机工具必须明确选择本机主屏或后台屏，不能静默回落");
        JSONObject modelSource = request.arguments().optJSONObject("modelSource");
        if (kind == Kind.GOAL && modelSource != null && !"local".equals(modelSource.optString("kind")))
            throw new SecurityException("设备目标只能使用本机已授权模型；不接收凭据或借配对扩大推理受众");
        return new DeviceExecutionAdmission(permission, policy, kind, mode);
    }
    DeviceExecutionAdmission reauthorizeAccepted(DeviceTaskEnvelope accepted) throws Exception {
        if (kind != Kind.DEVICE_TOOLS || !request.callerDeviceId().equals(accepted.callerDeviceId())
                || !request.json().getString("localTaskId").equals(accepted.json().getString("localTaskId"))) throw new SecurityException("不是该原设备工具会话已接受的请求");
        return issue(permission.reauthorize(accepted, System.currentTimeMillis()), localPolicy);
    }
    public void checkCurrent() throws Exception {
        long now = System.currentTimeMillis();
        permission.checkCurrent(now);
        if (request.deadline() <= now) throw new SecurityException("设备任务已到期");
        localPolicy.checkCurrent(request, kind);
    }
    public DeviceTaskEnvelope request() { return request; }
    public Kind kind() { return kind; }
    public String executionMode() { return mode; }
    public long deadline() { return Math.min(request.deadline(), permission.deadline()); }
    public int toolBudget() { return Math.toIntExact(Math.min(400, Math.min(permission.toolBudget(), permission.budget().optLong("toolCalls")))); }
    public int modelBudget() { return kind == Kind.DEVICE_TOOLS ? 0 : Math.toIntExact(Math.min(1000, Math.min(permission.modelBudget(), permission.budget().optLong("modelCalls")))); }
    public long grantToolBudget() { return permission.toolBudget(); }
    public long grantModelBudget() { return permission.modelBudget(); }
    public long nativeGrantDeadline() { return permission.grantExpiresAt(); }
    public String nativeGrantFingerprint() { return permission.grantFingerprint(); }
    public long nativeHistoryDeadline(){JSONObject history=permission.grant().optJSONObject("historyRead");return Math.max(permission.grantExpiresAt(),history==null?0:history.optLong("expiresAt"));}
    public PairingCoordinator.SourceShare sourceShare()throws Exception{PairingCoordinator.SourceShare share=permission.sourceShare();if(share!=null)share.checkCurrent(System.currentTimeMillis());return share;}
    public JSONArray toolNames() throws Exception {
        JSONArray granted=permission.toolNames(),projected=new JSONArray();for(int i=0;i<granted.length();i++){
            String name=granted.getString(i);if(kind==Kind.DEVICE_TOOLS?DeviceToolsRuntime.supports(name)&&(!DeviceToolsRuntime.requiresWholeDisplayInput(name)||request.resourceBinding().optBoolean("allowWholeDisplayInput",false)):DeviceGoalCapabilityPort.supportedTool(name))projected.put(name);
        }return projected;
    }
    public JSONArray resources() throws Exception { return new JSONArray(permission.resources().toString()); }
    public String principalId() { return "paired_device:" + request.originDeviceId(); }
    public String spaceId() { return "device:" + request.callerDeviceId(); }
    public JSONObject origin() throws Exception {
        JSONObject envelope = request.json();
        return new JSONObject().put("kind", "paired_device")
                .put("originDeviceId", request.originDeviceId()).put("callerDeviceId", request.callerDeviceId())
                .put("targetDeviceId", request.targetDeviceId()).put("grantId", request.grantId())
                .put("authorizationEpoch", request.authorizationEpoch())
                .put("rootTaskId", envelope.optString("rootTaskId"))
                .put("parentTaskId", envelope.optString("parentTaskId"))
                .put("remoteLocalTaskId", envelope.optString("localTaskId"))
                .put("remoteOperationId", request.operationId())
                .put("traceId", envelope.optString("traceId"));
    }
    /** Stable session scope; excludes per-operation payload and request IDs. */
    public JSONObject scope() throws Exception {
        JSONObject envelope = request.json();
        JSONObject scope = new JSONObject().put("format", "pocketworld.device-execution-scope.v1")
                .put("origin", origin()).put("executionKind", kind.name()).put("mode", mode)
                .put("capabilityVersion",envelope.optString("capabilityVersion",DeviceProtocol.VERSION))
                .put("allocationId", request.budget().getString("allocationId")).put("grantToolLimit", permission.toolBudget()).put("grantModelCallLimit", permission.modelBudget())
                .put("deadline", deadline()).put("toolLimit", toolBudget()).put("modelCallLimit", modelBudget())
                .put("tools", toolNames()).put("resources", resources())
                .put("visitedDevices", envelope.optJSONArray("visitedDevices") == null ? new JSONArray().put(request.originDeviceId()) : envelope.getJSONArray("visitedDevices"))
                .put("delegationChain", envelope.optJSONArray("delegationChain") == null ? new JSONArray() : envelope.getJSONArray("delegationChain"))
                .put("maxDelegationDepth", envelope.optInt("maxDelegationDepth", 2))
                .put("resourceBinding", envelope.optJSONObject("resourceBinding") == null ? new JSONObject() : envelope.getJSONObject("resourceBinding"));
        JSONObject forwarding = request.arguments().optJSONObject("delegationAuthorization");
        if (forwarding != null) scope.put("forwardingProof", new JSONObject(forwarding.toString()));
        PairingCoordinator.SourceShare source=sourceShare();if(source!=null)scope.put("sourceShare",source.record()).put("localToolsUsed",0).put("localModelCallsUsed",0);
        return scope;
    }
}
