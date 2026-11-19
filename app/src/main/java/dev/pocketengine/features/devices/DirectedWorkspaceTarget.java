package dev.pocketengine.features.devices;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A local draft audience, never a grant. First increment exposes only existing workspace list/read. */
public record DirectedWorkspaceTarget(String deviceId, String instanceId, String certificateFingerprint) {
    public static final String FIELD = "directWorkspace";
    public static final String VERSION = "workspace-v1";
    private static final Set<String> TOOLS = Set.of("workspace.list", "workspace.read");

    public DirectedWorkspaceTarget {
        DeviceProtocol.deviceId(deviceId, "deviceId");
        DeviceProtocol.identifier(instanceId, "instanceId");
        if (certificateFingerprint == null || !certificateFingerprint.matches("[a-f0-9]{64}"))
            throw new SecurityException("原工作区设备证书身份不完整");
    }
    public static DirectedWorkspaceTarget read(JSONObject value) throws Exception {
        if (value == null || value.length() != 3)
            throw new SecurityException("原工作区设备身份不完整");
        return new DirectedWorkspaceTarget(value.getString("deviceId"), value.getString("instanceId"),
                value.getString("certificateFingerprint"));
    }
    public static DirectedWorkspaceTarget fromTask(JSONObject task) throws Exception {
        JSONObject marker = task.optJSONObject("ownerDeviceWork");
        if (marker == null || !marker.has(FIELD)) return null;
        if (marker.has("directPhone")) throw new SecurityException("同一份目标不得同时指定两类原设备");
        return read(marker.getJSONObject(FIELD));
    }
    public JSONObject view() throws Exception {
        return new JSONObject().put("deviceId", deviceId).put("instanceId", instanceId)
                .put("certificateFingerprint", certificateFingerprint);
    }
    public boolean matches(PairingCoordinator.OutgoingGrant grant) {
        return grant != null && grant.isOriginalTarget(deviceId, instanceId, certificateFingerprint);
    }
    public static boolean allows(String version, String method, String effect) {
        return VERSION.equals(version) && "read".equals(effect) && TOOLS.contains(method);
    }
    public void requireRoutes(JSONArray routes) throws Exception {
        for (int i = 0; i < routes.length(); i++) {
            JSONObject route = routes.getJSONObject(i);
            if (!deviceId.equals(route.opt("targetDeviceId")) || !instanceId.equals(route.opt("instanceId"))
                    || !"tool_session".equals(route.opt("kind")) || !VERSION.equals(route.opt("capabilityVersion"))
                    || route.getJSONArray("downstreamShares").length() != 0 || route.getJSONArray("files").length() != 0
                    || route.getJSONArray("projects").length() != 0 || !"goal_only".equals(route.opt("contextMode"))
                    || route.optLong("modelBudget", -1L) != 0L)
                throw new SecurityException("这份目标仅限所选原工作区设备和只读工具，不能转交");
            DeviceProtocol.validateResourceBinding(VERSION, route.getJSONObject("resourceBinding"));
            JSONObject tools = route.getJSONObject("toolDeclarations");
            if (tools.length() == 0) throw new SecurityException("原工作区没有选中的只读能力");
            for (String tool : DeviceProtocol.objectKeys(tools))
                if (!allows(VERSION, tool, tools.getString(tool)))
                    throw new SecurityException("这份工作只能列目录或读取已授权文件");
        }
    }
}
