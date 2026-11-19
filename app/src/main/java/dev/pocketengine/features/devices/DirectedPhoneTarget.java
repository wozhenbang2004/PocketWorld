package dev.pocketengine.features.devices;

import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-selected audience for one local draft. It can only narrow a real target grant, never issue one. */
public record DirectedPhoneTarget(String deviceId, String instanceId, String certificateFingerprint) {
    public DirectedPhoneTarget {
        DeviceProtocol.deviceId(deviceId,"deviceId"); DeviceProtocol.identifier(instanceId,"instanceId");
        if (certificateFingerprint == null || !certificateFingerprint.matches("[a-f0-9]{64}"))
            throw new SecurityException("原手机证书身份不完整");
    }
    public static DirectedPhoneTarget read(JSONObject json) throws Exception {
        if (json == null || json.length()!=3) throw new SecurityException("原手机身份不完整，未换成任意设备");
        return new DirectedPhoneTarget(json.getString("deviceId"), json.getString("instanceId"), json.getString("certificateFingerprint"));
    }
    public static DirectedPhoneTarget fromTask(JSONObject task) throws Exception {
        JSONObject marker=task.optJSONObject("ownerDeviceWork");
        return marker!=null && marker.has("directPhone") ? read(marker.getJSONObject("directPhone")) : null;
    }
    public JSONObject view() throws Exception {
        return new JSONObject().put("deviceId",deviceId).put("instanceId",instanceId).put("certificateFingerprint",certificateFingerprint);
    }
    public boolean matches(PairingCoordinator.OutgoingGrant grant) {
        return grant!=null && grant.isOriginalTarget(deviceId,instanceId,certificateFingerprint);
    }
    public static boolean phoneSurface(JSONObject resource) {
        return resource!=null && ("main_screen".equals(resource.optString("screenMode")) || "background".equals(resource.optString("screenMode")))
                && resource.opt("androidUserId") instanceof Number && resource.optJSONArray("packageNames")!=null && resource.optJSONArray("packageNames").length()>0;
    }
    public void requireRoutes(JSONArray routes) throws Exception {
        for (int i=0;i<routes.length();i++) {
            JSONObject route=routes.getJSONObject(i);
            if (!deviceId.equals(route.opt("targetDeviceId")) || !instanceId.equals(route.opt("instanceId"))
                    || (AndroidTextHandoff.selected(route) ? routes.length()!=1 || !AndroidTextHandoff.bounded(route) : !phoneSurface(route.optJSONObject("resourceBinding")))
                    || route.getJSONArray("downstreamShares").length()!=0)
                throw new SecurityException("这份目标限定原手机，不得换设备、换实例或继续转交");
        }
    }
}
