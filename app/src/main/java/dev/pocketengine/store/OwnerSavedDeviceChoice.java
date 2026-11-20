package dev.pocketengine.store;

import dev.pocketengine.features.devices.NativeDeviceTaskScopeHost;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Navigation back to the owner's original private work after saving a scope on one paired device.
 * Not a dispatch record, grant or execution input. */
final class OwnerSavedDeviceChoice {
    static final String FIELD = "ownerSavedDeviceChoice";
    private static final String FORMAT = "pocketworld.owner-saved-device-choice.v1";
    private OwnerSavedDeviceChoice() {}

    private static boolean ordinary(JSONObject task) {
        return "owner".equals(task.opt("actorId")) && "private".equals(task.opt("spaceId")) &&
                !task.has("origin") && !task.has("ownerDeviceWork") && !OwnerPhoneControlScope.marked(task) &&
                ("assistant".equals(task.opt("kind")) || "phone".equals(task.opt("kind")));
    }

    static void record(JSONObject task, JSONObject scope, List<NativeDeviceTaskScopeHost.RouteSelection> selected) throws Exception {
        task.remove(FIELD);
        if (!ordinary(task) || selected.isEmpty()) return;
        var first = selected.get(0).option().receivingGrant();
        String id = first.targetDeviceId(), instance = first.targetInstanceId(), fingerprint = first.targetCertificateFingerprint();
        String localId = first.grant().getString("peerDeviceId");
        for (var choice : selected) {
            var grant = choice.option().receivingGrant();
            if (!grant.isOriginalTarget(id, instance, fingerprint) || !localId.equals(grant.grant().opt("peerDeviceId"))) return;
        }
        // The old route record did not capture the certificate. Do not fill it from today's pairing.
        task.put(FIELD, new JSONObject().put("format", FORMAT).put("localDeviceId", localId)
                .put("scopeRevision", scope.getLong("revision")).put("target", new JSONObject().put("deviceId", id)
                        .put("instanceId", instance).put("certificateFingerprint", fingerprint))
                .put("readOnly", true).put("executionAuthorized", false));
    }

    static void project(JSONObject task, JSONObject header) throws Exception {
        JSONObject saved = task.optJSONObject(FIELD);
        header.remove(FIELD);
        if (saved == null || !ordinary(task) || !FORMAT.equals(saved.opt("format")) ||
                !saved.optString("localDeviceId").matches("pw1_[a-f0-9]{64}")) return;
        JSONObject scope = task.optJSONObject("deviceSourceScope"), target = saved.optJSONObject("target");
        if (scope == null || target == null || !"pocketworld.native-device-source-scope.v1".equals(scope.opt("format")) ||
                !task.optString("id").equals(scope.opt("taskId")) || !task.optString("goal").equals(scope.opt("goal")) ||
                saved.optLong("scopeRevision", -1) <= 0 || saved.optLong("scopeRevision") != scope.optLong("revision", -1) ||
                !target.optString("deviceId").matches("pw1_[a-f0-9]{64}") ||
                !target.optString("instanceId").matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}") ||
                !target.optString("certificateFingerprint").matches("[a-f0-9]{64}")) return;
        JSONArray routes = scope.optJSONArray("routes");
        if (routes == null || routes.length() == 0 || routes.length() > 16) return;
        for (int i = 0; i < routes.length(); i++) {
            JSONObject route = routes.optJSONObject(i);
            if (route == null || !target.optString("deviceId").equals(route.opt("targetDeviceId")) ||
                    !target.optString("instanceId").equals(route.opt("instanceId"))) return;
        }
        header.put(FIELD, saved);
    }
}
