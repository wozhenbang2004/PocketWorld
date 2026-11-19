package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceIdentity;
import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.DirectedPhoneTarget;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local readable projection of an actual authenticated management receipt: no contact, live status, or execution. */
final class SentPhoneStopReceipt {
    private SentPhoneStopReceipt(){}
    private static final String SEAL="originalPhoneStopReceiptProof";
    private static String proof(String parent,String operation,JSONObject wire,DirectedPhoneTarget phone,JSONObject receipt)throws Exception{
        return DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(new JSONArray().put("original-phone-stop-receipt.v1").put(parent)
                .put(operation).put(wire.getString("argumentsDigest")).put(phone.view()).put(DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(receipt)))));
    }
    /** Only called after the exact mTLS peer, fixed management request and target digest were all checked. */
    static void seal(JSONObject marker,String parent,String operation,JSONObject original,DeviceIdentity.AuthenticatedPeer actual,JSONObject receipt)throws Exception{
        if(!DeviceProtocol.isDirectPhoneGoal(original,original.getString("callerDeviceId"),parent,operation)
                ||!actual.deviceId().equals(original.opt("targetDeviceId")))return;
        var pinned=new DirectedPhoneTarget(actual.deviceId(),original.getString("instanceId"),actual.certificateFingerprint());
        marker.put(SEAL,proof(parent,operation,original,pinned,receipt));
    }
    static JSONObject localFact(SQLiteDatabase db,DirectedPhoneTarget phone,String parent,String op,JSONObject original)throws Exception{
        String cancel=TaskStoreDeviceTasks.originalStopOperation(parent,op),run=original.optString("runId");
        if(run.isEmpty())return null;
        try(Cursor row=db.rawQuery("SELECT CASE WHEN length(CAST(receipt AS BLOB))<=73728 THEN receipt END FROM operations WHERE operation_id=? AND task_id=? AND run_id=? AND tool='native_device_operation_cancel' AND state='receipt'",
                new String[]{cancel,parent,run})){
            if(!row.moveToFirst()||row.isNull(0))return null;
            try{
                JSONObject saved=new JSONObject(row.getString(0)),marker=saved.getJSONObject("nativeDeviceStopSend"),r=saved.getJSONObject("operationReceipt");
                if(!"write_claimed".equals(marker.opt("state"))||!op.equals(marker.opt("sourceOperationId"))||!marker.has(SEAL)
                        ||!proof(parent,op,original,phone,r).equals(marker.opt(SEAL)))return null;
                JSONObject management=marker.getJSONObject("wire"),requested=management.getJSONObject("arguments"),c=r.getJSONObject("cancellation");
                if(!"operation.cancel".equals(management.opt("method"))||!cancel.equals(management.opt("operationId"))
                        ||!phone.deviceId().equals(management.opt("targetDeviceId"))||!phone.instanceId().equals(management.opt("instanceId"))
                        ||!original.getString("callerDeviceId").equals(management.opt("callerDeviceId"))||!original.getString("grantId").equals(management.opt("grantId"))
                        ||!DeviceProtocol.resourceEquals(original.getJSONObject("resourceBinding"),management.getJSONObject("resourceBinding"))
                        ||!op.equals(requested.opt("operationId"))||!original.getString("argumentsDigest").equals(requested.opt("argumentsDigest"))
                        ||!DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(management)).equals(marker.opt("wireSha256"))
                        ||!"OperationReceipt".equals(r.opt("messageType"))||!cancel.equals(r.opt("operationId"))||!phone.deviceId().equals(r.opt("executorDeviceId"))
                        ||!DeviceProtocol.receiverTaskId(original).equals(r.opt("localTaskId"))
                        ||!phone.instanceId().equals(r.opt("instanceId"))||!DeviceProtocol.argumentsDigest(management).equals(r.opt("argumentsDigest"))
                        ||!Boolean.TRUE.equals(r.opt("accepted"))||!Boolean.TRUE.equals(r.getJSONObject("payload").opt("managementOnly"))
                        ||!op.equals(c.opt("targetOperationId"))||!requested.getString("argumentsDigest").equals(c.opt("targetArgumentsDigest"))
                        ||!(c.opt("requested") instanceof Boolean)||!(c.opt("stopped") instanceof Boolean)
                        ||!(r.opt("acceptedAt") instanceof Number)||r.getLong("acceptedAt")<1)return null;
                boolean asked=c.getBoolean("requested"),stopped=c.getBoolean("stopped");
                if(!asked&&!stopped)return null;
                return new JSONObject().put("state",stopped?(asked?"reported_stopped":"reported_ended"):"request_recorded").put("requestReceivedAt",r.getLong("acceptedAt"));
            }catch(org.json.JSONException|DeviceProtocol.ProtocolException unreadable){return null;}
        }
    }
}
