package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceIdentity;
import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.DirectedPhoneTarget;
import dev.pocketengine.features.devices.PairingCoordinator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local readable projection of an actual authenticated management receipt: no contact, live status, or execution. */
final class SentPhoneStopReceipt {
    private SentPhoneStopReceipt(){}
    private static final String SEAL="originalPhoneStopReceiptProof";
    private static final String INTENT="originalPhoneStopIntentProof";
    private static String intentProof(String parent,String op,JSONObject original,DirectedPhoneTarget phone,String managementHash)throws Exception{
        return DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(new JSONArray().put("local-phone-stop-intent.v1").put(parent)
                .put(op).put(original.getString("argumentsDigest")).put(phone.view()).put(managementHash)));
    }
    // Certifies only which saved target the local prepared request was for. It does not certify a send or reply.
    static void prepared(SQLiteDatabase db,JSONObject marker,String parent,String op,JSONObject original,PairingCoordinator.OutgoingGrant grant){
        try{
            if(!DeviceProtocol.isDirectPhoneGoal(original,original.getString("callerDeviceId"),parent,op))return;
            var local=LocalPhoneDispatchPin.current(db,grant.targetDeviceId());
            if(local==null||!grant.isOriginalTarget(local.id(),local.instance(),local.fingerprint()))return;
            var phone=new DirectedPhoneTarget(local.id(),local.instance(),local.fingerprint());
            marker.put(INTENT,intentProof(parent,op,original,phone,marker.getString("wireSha256")));
        }catch(Exception unavailable){/* Optional local provenance must not prevent or authorize the actual stop. */}
    }

    private static String proof(String parent,String operation,JSONObject wire,DirectedPhoneTarget phone,JSONObject receipt)throws Exception{
        return DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(new JSONArray().put("original-phone-stop-receipt.v1").put(parent)
                .put(operation).put(wire.getString("argumentsDigest")).put(phone.view()).put(DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(receipt)))));
    }
    /** Only called after the exact mTLS peer, fixed management request and target digest were all checked. */
    static void seal(JSONObject marker,String parent,String operation,JSONObject original,DeviceIdentity.AuthenticatedPeer actual,JSONObject receipt){
        try{
            if(!DeviceProtocol.isDirectPhoneGoal(original,original.getString("callerDeviceId"),parent,operation)
                    ||!actual.deviceId().equals(original.opt("targetDeviceId")))return;
            var pinned=new DirectedPhoneTarget(actual.deviceId(),original.getString("instanceId"),actual.certificateFingerprint());
            marker.put(SEAL,proof(parent,operation,original,pinned,receipt));
        }catch(Exception unavailable){/* Preserve the already-verified native receipt even when the archive stamp is unavailable. */}
    }
    static JSONObject localFact(SQLiteDatabase db,DirectedPhoneTarget phone,String parent,String op,JSONObject original)throws Exception{
        String cancel=TaskStoreDeviceTasks.originalStopOperation(parent,op),run=original.optString("runId");
        if(run.isEmpty())return null;
        try(Cursor row=db.rawQuery("SELECT CASE WHEN length(CAST(receipt AS BLOB))<=73728 THEN receipt END,state,created_at FROM operations WHERE operation_id=? AND task_id=? AND run_id=? AND tool='native_device_operation_cancel' AND state IN ('prepared','unknown','receipt')",
                new String[]{cancel,parent,run})){
            if(!row.moveToFirst()||row.isNull(0))return null;
            try{
                JSONObject saved=new JSONObject(row.getString(0)),marker=saved.getJSONObject("nativeDeviceStopSend");
                if(!op.equals(marker.opt("sourceOperationId")))return null;
                JSONObject management=marker.getJSONObject("wire"),requested=management.getJSONObject("arguments");
                if(!"operation.cancel".equals(management.opt("method"))||!cancel.equals(management.opt("operationId"))
                        ||!phone.deviceId().equals(management.opt("targetDeviceId"))||!phone.instanceId().equals(management.opt("instanceId"))
                        ||!original.getString("callerDeviceId").equals(management.opt("callerDeviceId"))||!original.getString("grantId").equals(management.opt("grantId"))
                        ||!DeviceProtocol.resourceEquals(original.getJSONObject("resourceBinding"),management.getJSONObject("resourceBinding"))
                        ||!op.equals(requested.opt("operationId"))||!original.getString("argumentsDigest").equals(requested.opt("argumentsDigest"))
                        ||!DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(management)).equals(marker.opt("wireSha256")))return null;
                String state=row.getString(1);
                if(!"receipt".equals(state)){
                    String phase=marker.optString("state");
                    if(saved.has("operationReceipt")||row.getLong(2)<=0||!marker.has(INTENT)
                            ||!intentProof(parent,op,original,phone,marker.getString("wireSha256")).equals(marker.opt(INTENT)))return null;
                    String localPhase=switch(phase){
                        case "not_claimed" -> "prepared_no_write";
                        case "abandoned_before_write" -> "abandoned_before_write";
                        case "write_claimed" -> "unknown".equals(state)?"write_unconfirmed":null;
                        default -> null;
                    };
                    return localPhase==null?null:new JSONObject().put("localPhase",localPhase).put("preparedAt",row.getLong(2));
                }
                JSONObject r=saved.getJSONObject("operationReceipt"),c=r.getJSONObject("cancellation");
                if(!"write_claimed".equals(marker.opt("state"))||!marker.has(SEAL)||!proof(parent,op,original,phone,r).equals(marker.opt(SEAL))
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
