package dev.pocketengine.features.devices;

import org.json.JSONObject;

/** Outgoing projections on B. Only the existing owner-reviewed, digest-bound
 * task.get projection may publish business text; transport receipts are not it. */
public final class DevicePrivateText {
    private DevicePrivateText() {}

    /** The receiver's actual no-screen runner, not the source platform's choice of operation IDs,
     * determines which results must stay private until owner review. This is a deny-only classifier. */
    public static boolean requested(DeviceTaskEnvelope request) {
        return DeviceExecutionAdmission.zeroToolPhoneGoal(request);
    }

    public static boolean applies(JSONObject task) {
        JSONObject scope=task.optJSONObject("deviceExecutionScope"), origin=task.optJSONObject("origin");
        if(origin!=null&&DeviceProtocol.textMarker(origin.opt("textReturnContract")))return true;
        if(scope==null)return false;
        if(Boolean.TRUE.equals(scope.opt("reviewedTextOnly")))return true;
        // Older accepted Android allocations have distinct parent/operation/allocation IDs, unlike
        // the legacy Windows source. The persisted receiving budget and signed resource also survive death.
        if(origin==null||!"GOAL".equals(task.opt("deviceExecutionKind"))||!"paired_device".equals(origin.opt("kind"))
                ||scope.optLong("toolLimit",-1)!=0)return false;
        JSONObject resource=scope.optJSONObject("resourceBinding");
        String signed=resource==null?"":resource.optString("screenMode");
        return "main_screen".equals(signed)||"background".equals(signed)
                ||"main_screen".equals(scope.opt("mode"))||"background".equals(scope.opt("mode"));
    }

    static JSONObject identity(JSONObject task) throws Exception {
        JSONObject origin = task.getJSONObject("origin"), safe = new JSONObject();
        for (String key : new String[]{"kind","originDeviceId","callerDeviceId","targetDeviceId","grantId","authorizationEpoch",
                "rootTaskId","parentTaskId","remoteLocalTaskId","remoteOperationId"}) safe.put(key, origin.get(key));
        if(DeviceProtocol.acceptedTextReturn(task))safe.put("textReturnContract",origin.getString("textReturnContract"));
        return new JSONObject().put("origin", safe).put("rootTaskId", task.getString("rootTaskId"))
                .put("remoteParentTaskId", task.optString("remoteParentTaskId")).put("executionKind", task.getString("deviceExecutionKind"))
                .put("transportStateIsNotExecutionState", true);
    }

    /** Only scalar, native accounting facts on the original send/read. No generic
     * receipt/evidence/summary, raw event or question, even on a later control op read. */
    public static JSONObject operation(String method, JSONObject payload) throws Exception {
        JSONObject safe = new JSONObject().put("lifecycleOnly", true);
        for (String key : new String[]{"knownOperation","executionStarted","terminal","stopped","hasUnresolved","requested","newRun","newGrant",
                "ownerActionRequired","executionAuthorized","executionMoved","inputSaved","formalSourceRefAvailable","continuationRequested"})
            if (payload.opt(key) instanceof Boolean) safe.put(key, payload.getBoolean(key));
        for (String key : new String[]{"toolsUsed","modelCallsUsed","outstandingChildReservations","executorEpoch","requestedRevision","reviewRevision"})
            if (payload.opt(key) instanceof Number) safe.put(key, payload.getLong(key));
        // Never return lease credentials or other controls through historical operation.get.
        if ("task.acquireControl".equals(method) && payload.has("leaseId") && payload.has("expiresAt"))
            safe.put("leaseId", DeviceProtocol.identifier(payload.opt("leaseId"),"leaseId")).put("expiresAt", payload.getLong("expiresAt"));
        if ("task.answerQuestion".equals(method) || "task.steer".equals(method) || "task.requestResume".equals(method)) {
            for (String key : new String[]{"taskId","runId","allocationId","messageId","resumeRequestId"})
                if (payload.has(key)) safe.put(key, DeviceProtocol.identifier(payload.opt(key), key));
            if ("native_paired_task_input".equals(payload.opt("sourceKind"))) safe.put("sourceKind","native_paired_task_input");
            if ("answer".equals(payload.opt("inputKind")) || "steer".equals(payload.opt("inputKind"))) safe.put("inputKind", payload.getString("inputKind"));
        }
        return safe;
    }
}
