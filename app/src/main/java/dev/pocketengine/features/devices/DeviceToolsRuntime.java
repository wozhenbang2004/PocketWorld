package dev.pocketengine.features.devices;

import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.store.TaskStoreDeviceTasks;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** DEVICE_TOOLS lifecycle: one native PhonePort and original control, ZERO model/config/Runner calls.
 * PhoneRunService owns notification, phone.local admission, wake lock and physical port cleanup. */
public final class DeviceToolsRuntime implements AutoCloseable {
    public interface Health { void requireReady(String screenMode, RunControl original) throws Exception; }
    public interface ObservedImageSink {
        /** Freeze ACTUAL captured image bytes under this original task/operation/frame. No arbitrary path. */
        JSONObject freeze(String taskId, String runId, int epoch, String operationId, JSONObject observationRef, JSONObject actualImage) throws Exception;
    }
    private final String localDeviceId;
    private final DeviceExecutionAdmission firstAdmission;
    private final RunControl original;
    private final PhonePort physical;
    private final TaskStoreDeviceTasks tasks;
    private final Health health;
    private final ObservedImageSink imageSink;
    private volatile DeviceObservationRef latest;
    private boolean closed;
    private volatile JSONObject lastObservation;

    public DeviceToolsRuntime(String localDeviceId, DeviceExecutionAdmission admission, RunControl original,
            PhonePort physical, TaskStoreDeviceTasks originalTasks, Health health) throws Exception {
        this(localDeviceId, admission, original, physical, originalTasks, health, null);
    }
    public DeviceToolsRuntime(String localDeviceId, DeviceExecutionAdmission admission, RunControl original,
            PhonePort physical, TaskStoreDeviceTasks originalTasks, Health health, ObservedImageSink imageSink) throws Exception {
        if (admission.kind() != DeviceExecutionAdmission.Kind.DEVICE_TOOLS || original.hostRunIdentity() == null)
            throw new SecurityException("需要原生设备工具执行和原RunControl");
        this.localDeviceId = localDeviceId; this.firstAdmission = admission;
        this.original = original; this.physical = java.util.Objects.requireNonNull(physical);
        this.tasks = java.util.Objects.requireNonNull(originalTasks); this.health = java.util.Objects.requireNonNull(health); this.imageSink = imageSink;
        admission.checkCurrent(); health.requireReady(admission.executionMode(), original);
        original.onCancel(this::invalidate);
    }
    public RunControl originalControl() { return original; }
    public String taskId() { return original.hostRunIdentity().taskId(); }
    public synchronized boolean ready() { return !closed && !original.isCancelled() && physical.isConnected(); }
    public static boolean supports(String name) { return name != null && name.matches("phone_(observe|list_apps|launch_app|click_node|tap|type|swipe|back|home)"); }
    public static JSONArray toolDeclarations()throws Exception{
        JSONArray result=new JSONArray();for(String name:new String[]{"phone_observe","phone_list_apps","phone_launch_app","phone_click_node","phone_tap","phone_type","phone_swipe","phone_back","phone_home"})result.put(new JSONObject().put("toolName",name).put("effect",effect(name)).put("capabilityVersion",DeviceProtocol.VERSION));return result;
    }
    public static String effect(String name) {
        if (!supports(name)) throw new SecurityException("不支持的设备手机工具");
        return Set.of("phone_observe", "phone_list_apps").contains(name) ? "read" : "write";
    }
    public static boolean requiresWholeDisplayInput(String name) { return Set.of("phone_tap","phone_swipe","phone_back","phone_home").contains(name); }
    /** This projection is for an EXACT native resource grant, not a claim that packages isolate input. */
    public static JSONArray toolDeclarations(JSONArray granted, JSONObject binding)throws Exception{
        JSONArray result=new JSONArray(),all=toolDeclarations();
        for(int i=0;i<all.length();i++){JSONObject tool=all.getJSONObject(i);String name=tool.getString("toolName");
            if(PairingCoordinator.contains(granted,name)&&(!requiresWholeDisplayInput(name)||binding.optBoolean("allowWholeDisplayInput",false)))result.put(tool);
        }return result;
    }
    public static void validateInvocation(DeviceTaskEnvelope request) throws Exception {
        DeviceTaskEnvelope.ToolInvocation invocation = request.toolInvocation(); String name = invocation.toolName();
        if (!effect(name).equals(invocation.effect())) throw new SecurityException("远端工具副作用声明与本机实际工具不一致");
        JSONObject args = invocation.arguments(); Set<String> allowed;
        allowed = switch (name) {
            case "phone_observe" -> Set.of("includeImage"); case "phone_list_apps", "phone_back", "phone_home" -> Set.of();
            case "phone_launch_app" -> Set.of("packageName"); case "phone_click_node" -> Set.of("nodeId");
            case "phone_type" -> Set.of("nodeId", "text"); case "phone_tap" -> Set.of("x", "y");
            case "phone_swipe" -> Set.of("x1", "y1", "x2", "y2", "durationMs");
            default -> throw new SecurityException("不支持的设备工具");
        };
        for (String key : DeviceProtocol.objectKeys(args)) if (!allowed.contains(key)) throw new SecurityException("手机工具参数包含非本机引用或未知字段");
        if (name.equals("phone_observe") && args.has("includeImage") && !(args.opt("includeImage") instanceof Boolean)) throw new SecurityException("观察设置必须是布尔值");
        if (name.equals("phone_launch_app") && !args.optString("packageName").matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) throw new SecurityException("无效应用身份");
        if (name.equals("phone_type") && (!(args.opt("text") instanceof String) || args.getString("text").length() > 12_000)) throw new SecurityException("输入内容无效或过长");
        if (name.equals("phone_tap")) for (String field : new String[]{"x", "y"}) DeviceProtocol.integer(args.opt(field), field, 0, 1000);
        if (name.equals("phone_swipe")) {
            for (String field : new String[]{"x1", "y1", "x2", "y2"}) DeviceProtocol.integer(args.opt(field), field, 0, 1000);
            DeviceProtocol.integer(args.opt("durationMs"), "durationMs", 100, 2000);
        }
        if ((name.equals("phone_click_node")||name.equals("phone_type")) && (!(args.opt("nodeId") instanceof String)||args.optString("nodeId").isEmpty())) throw new SecurityException("设备节点操作必须明确引用当前授权观察中的nodeId，不能使用焦点回落");
        if(requiresWholeDisplayInput(name)&&!request.resourceBinding().optBoolean("allowWholeDisplayInput",false))throw new SecurityException("本机未明确授予整个显示输入；应用名单不能隔离坐标或全局按键");
    }
    /** Called only by the service's single worker. This method never retries a physical call. */
    public synchronized JSONObject invoke(DeviceExecutionAdmission admission) throws Exception {
        validateInvocation(admission.request()); check(admission);
        if (admission.request().toolInvocation().arguments().optBoolean("includeImage")
                && !admission.request().resourceBinding().optBoolean("allowWholeDisplayCapture", false))
            throw new SecurityException("本机未准确授予整显示截图；应用范围不能暗中扩大为其他窗口内容");
        if (admission.request().toolInvocation().arguments().optBoolean("includeImage") && imageSink == null)
            throw new SecurityException("真实截图成果通道未准备；不会用无图冒充已提供视觉输入");
        DeviceTaskEnvelope.ToolInvocation invocation = admission.request().toolInvocation(); String name = invocation.toolName();
        JSONObject args = new JSONObject(invocation.arguments().toString());
        if (name.equals("phone_launch_app")) requirePackage(admission, args.getString("packageName"));
        else if (!effect(name).equals("read")) {
            if (latest == null) throw new SecurityException("请先取得目标手机的新鲜观察");
            requirePackage(admission, lastObservation.optString("packageName"));
            if(name.equals("phone_click_node")||name.equals("phone_type"))requireObservedNode(admission,args.getString("nodeId"));
            JSONObject nativeReference = latest.consume(invocation.observationRef(), original, System.currentTimeMillis());
            if (name.equals("phone_back") || name.equals("phone_home")) {
                JSONObject current = physical.observe(false, original);
                if (!Boolean.TRUE.equals(current.opt("ok")) || !lastObservation.optString("treeFingerprint").equals(current.optString("treeFingerprint"))
                        || !lastObservation.getString("sessionId").equals(current.optString("sessionId")) || lastObservation.getInt("displayId") != current.optInt("displayId", -1))
                    throw new SecurityException("原显示窗口已变化，未派发全局输入，请重新观察");
            }
            for (String key : DeviceProtocol.objectKeys(nativeReference)) args.put(key, nativeReference.get(key));
            latest = null;
        }
        JSONObject claim = tasks.prepareInvocation(admission, original);
        if (!claim.optBoolean("fresh")) return claim; // Never repeat even reads under the original operation identity.
        boolean entered = false;
        try {
            check(admission); tasks.markDispatched(claim, admission, original); entered = true;
            JSONObject receipt;
            if (name.equals("phone_observe")) {
                invalidate(); receipt = physical.observe(args.optBoolean("includeImage", false), original);
                if (Boolean.TRUE.equals(receipt.opt("ok"))) {
                    requirePackage(admission, receipt.optString("packageName"));
                    latest = DeviceObservationRef.capture(localDeviceId, original, receipt, System.currentTimeMillis());
                    receipt.put("observationRef", latest.json());
                    if (receipt.has("imageBase64")) {
                        JSONObject actualImage = new JSONObject();
                        for (String key : new String[]{"imageBase64", "imageMimeType", "imageWidth", "imageHeight"}) actualImage.put(key, receipt.get(key));
                        JSONObject artifact = imageSink.freeze(taskId(), original.hostRunIdentity().runId(), original.hostRunIdentity().epoch(), claim.getString("localOperationId"), latest.json(), actualImage);
                        if (artifact == null) throw new SecurityException("截图固定字节/哈希未确认，不能返回视觉成果");
                        DeviceProtocol.validateMessage(artifact);
                        if (!"ArtifactDescriptor".equals(artifact.optString("messageType")) || !artifact.optString("contentDigest").matches("[a-f0-9]{64}") || artifact.optLong("size") < 1
                                || !taskId().equals(artifact.optString("taskId")) || !original.hostRunIdentity().runId().equals(artifact.optString("runId"))
                                || original.hostRunIdentity().epoch() != artifact.optInt("executorEpoch") || !claim.getString("localOperationId").equals(artifact.optString("operationId")))
                            throw new SecurityException("截图成果不是同原task/run/epoch/operation固定内容");
                        receipt.remove("imageBase64"); receipt.put("imageArtifact", artifact).put("imageTransport", "artifact_chunks")
                                .put("imageProvidedToModel", false);
                    }
                    filterObservation(admission, receipt); boundObservation(receipt);
                    // Keep only exactly the authorized, actually returned node references. The physical
                    // port may retain the full native forest, but guessed hidden/truncated IDs are not authority.
                    lastObservation = new JSONObject(receipt.toString());
                }
            } else {
                receipt = physical.execute(name, args, original);
                if (!effect(name).equals("read")) { invalidate(); physical.clearReferences(); }
                if (name.equals("phone_list_apps") && receipt.optJSONArray("apps") != null) receipt.put("apps", filterApps(admission, receipt.getJSONArray("apps")));
            }
            // A truthful native receipt may arrive after stop/revoke. Retain it without authorizing another action.
            receipt.put("deviceId", localDeviceId).put("executionKind", "DEVICE_TOOLS").put("modelRequests", 0)
                    .put("businessVerified", false).put("operationId", admission.request().operationId());
            tasks.finishInvocation(claim, receipt); return receipt;
        } catch (Exception failure) {
            invalidate(); physical.clearReferences();
            JSONObject receipt = new JSONObject().put("ok", false).put("error", entered && !effect(name).equals("read") ? "action_unknown" : "not_dispatched")
                    .put("accepted", entered && !effect(name).equals("read") ? JSONObject.NULL : false).put("modelRequests", 0).put("businessVerified", false)
                    .put("message", entered && !effect(name).equals("read") ? "原工具已进入执行，回执尚未核对；请查原操作并重新观察，不会自动重发" : "本机执行条件已变化，未派发有副作用操作");
            tasks.finishInvocation(claim, receipt); return receipt;
        }
    }
    private void check(DeviceExecutionAdmission admission) throws Exception {
        if (closed || original.isCancelled() || original.isPaused()) throw new SecurityException("原设备工具会话已停止或暂停");
        if (admission.kind() != DeviceExecutionAdmission.Kind.DEVICE_TOOLS
                || !admission.request().callerDeviceId().equals(firstAdmission.request().callerDeviceId())
                || !admission.request().originDeviceId().equals(firstAdmission.request().originDeviceId())
                || !admission.executionMode().equals(firstAdmission.executionMode())) throw new SecurityException("不是该原设备工具会话");
        original.check(); admission.checkCurrent(); health.requireReady(admission.executionMode(), original);
        if (!physical.isConnected()) throw new SecurityException("原本机PhonePort已断开；不会改用其他显示");
    }
    private static JSONArray packages(DeviceExecutionAdmission admission) {
        JSONObject binding = admission.request().json().optJSONObject("resourceBinding");
        return binding == null ? null : binding.optJSONArray("packageNames");
    }
    private static void requirePackage(DeviceExecutionAdmission admission, String packageName) throws Exception {
        JSONArray allowed = packages(admission); boolean found = false;
        for (int i = 0; allowed != null && i < allowed.length(); i++) if (packageName.equals(allowed.getString(i))) found = true;
        if (!found) throw new SecurityException("当前应用不在目标设备准确授予的应用范围");
    }
    private static JSONArray filterApps(DeviceExecutionAdmission admission, JSONArray apps) throws Exception {
        JSONArray bounded = new JSONArray();
        for (int i = 0; i < apps.length(); i++) try { JSONObject app = apps.getJSONObject(i); requirePackage(admission, app.getString("packageName")); bounded.put(app); } catch (SecurityException outside) { /* Do not expose other installed apps. */ }
        return bounded;
    }
    private void requireObservedNode(DeviceExecutionAdmission admission,String nodeId)throws Exception{
        JSONObject observation=lastObservation;if(observation==null)throw new SecurityException("没有当前本机授权观察");
        JSONArray windows=observation.optJSONArray("windows"),nodes=observation.optJSONArray("nodes");java.util.Set<Integer> allowed=new java.util.HashSet<>();
        for(int i=0;windows!=null&&i<windows.length();i++){JSONObject window=windows.getJSONObject(i);requirePackage(admission,window.getString("packageName"));allowed.add(window.getInt("windowId"));}
        for(int i=0;nodes!=null&&i<nodes.length();i++){JSONObject node=nodes.getJSONObject(i);if(nodeId.equals(node.optString("id"))&&allowed.contains(node.optInt("windowId",-1))){if(node.has("packageName"))requirePackage(admission,node.getString("packageName"));return;}}
        throw new SecurityException("nodeId不在本次准确授权window和已返回观察中，未派发输入");
    }
    private static void filterObservation(DeviceExecutionAdmission admission, JSONObject receipt) throws Exception {
        JSONArray windows = receipt.optJSONArray("windows"), allowedWindows = new JSONArray(), nodes = receipt.optJSONArray("nodes"), allowedNodes = new JSONArray();
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (int i = 0; windows != null && i < windows.length(); i++) {
            JSONObject window = windows.getJSONObject(i);
            try { requirePackage(admission, window.optString("packageName")); allowedWindows.put(window); ids.add(window.getInt("windowId")); }
            catch (SecurityException outside) { /* Never send another application's window or node body. */ }
        }
        for (int i = 0; nodes != null && i < nodes.length(); i++) if (ids.contains(nodes.getJSONObject(i).optInt("windowId", -1))) allowedNodes.put(nodes.get(i));
        receipt.put("windows", allowedWindows).put("nodes", allowedNodes).put("deviceScopeFiltered", true);
    }
    private static void boundObservation(JSONObject receipt) throws Exception {
        JSONArray source = receipt.optJSONArray("nodes"), visible = new JSONArray(); int bytes = 0;
        for (int i = 0; source != null && i < source.length(); i++) {
            JSONObject node = source.getJSONObject(i); int size = node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + size > 28_000) break;
            visible.put(node); bytes += size;
        }
        if (source != null && visible.length() < source.length()) receipt.put("nodes", visible).put("truncated", true)
                .put("deviceControlTruncated", true).put("returnedNodeCount", visible.length()).put("availableNodeCount", source.length());
        if (receipt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60_000)
            throw new SecurityException("观察控制消息超过通道上限，请缩小实际观察范围");
    }
    public void invalidate() { if (latest != null) latest.expire(); latest = null; lastObservation = null; }
    @Override public synchronized void close() { if (closed) return; closed = true; invalidate(); physical.clearReferences(); }
}
