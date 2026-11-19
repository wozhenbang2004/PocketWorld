package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.PairingCoordinator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Sender-local provenance for a bound direct phone goal. Never a grant, receipt of delivery, or wire field. */
final class LocalPhoneDispatchPin {
    private LocalPhoneDispatchPin() {}
    private static final String KEY="boundPhoneArchiveProof";
    // The epoch in the outbound wire belongs to the other phone. Use only this install's pairing history here.
    record Identity(String id,String instance,String fingerprint,long generation,long localEpoch) {
        private String proof(String source,String operation,String wireDigest) throws Exception {
            return DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(new JSONArray().put("bound-phone-archive.v1")
                    .put(id).put(instance).put(fingerprint).put(generation).put(source).put(operation).put(wireDigest)));
        }
        boolean owns(String receipt,String source,String operation,String digest) throws Exception {
            if(receipt!=null){
                JSONObject original;
                try{original=new JSONObject(receipt).optJSONObject("nativeSourceRoute");}catch(org.json.JSONException unreadable){return false;}
                if(original!=null && original.has(KEY))return proof(source,operation,digest).equals(original.opt(KEY));
            }
            // A unique initial DeviceID can be attributed without a later-added stamp. After in-place
            // replacement an unpinned legacy wire (even with the same instance or grant ID) is ambiguous.
            return generation==1;
        }
    }
    static Identity current(SQLiteDatabase db,String target) {
        String sql="SELECT p.instance_id,p.fingerprint,p.authorization_epoch,COALESCE((SELECT MAX(r.authorization_epoch) "
                +"FROM device_identity_rotations r WHERE r.old_device_id=p.device_id AND r.new_device_id=p.device_id),1) FROM device_peers p WHERE p.device_id=?";
        try(Cursor row=db.rawQuery(sql,new String[]{target})){
            if(!row.moveToFirst()||row.isNull(0)||row.isNull(1)||!row.getString(1).matches("[a-f0-9]{64}")||row.getLong(3)<1||row.getLong(2)<row.getLong(3))return null;
            return new Identity(target,row.getString(0),row.getString(1),row.getLong(3),row.getLong(2));
        }
    }
    /** Add to the existing, native-only source marker in the same transaction/update as the original bound wire.
     * The existing settlement path strips remote nativeSourceRoute and preserves the locally recorded one. */
    static void attach(SQLiteDatabase db,ContentValues anchor,String source,String operation,JSONObject wire,
            PairingCoordinator.OutgoingGrant grant,String sourceKey,String scopeDigest) throws Exception {
        if(!DeviceProtocol.isDirectPhoneGoal(wire,wire.getString("callerDeviceId"),source,operation))return;
        Identity pin=current(db,grant.targetDeviceId());
        if(pin==null||!grant.isOriginalTarget(pin.id,pin.instance,pin.fingerprint))return;
        try(Cursor row=db.rawQuery("SELECT CASE WHEN length(CAST(receipt_json AS BLOB))<=32768 THEN receipt_json END FROM device_child_budget WHERE parent_task_id=? AND parent_operation_id=?",
                new String[]{source,operation})){
            if(!row.moveToFirst()||row.isNull(0))return;
            JSONObject receipt;
            try{receipt=new JSONObject(row.getString(0));}catch(org.json.JSONException unreadable){return;}
            JSONObject marker=receipt.optJSONObject("nativeSourceRoute");
            if(marker==null||!Boolean.FALSE.equals(marker.opt("forwarded"))||!sourceKey.equals(marker.opt("sourceKey"))||!scopeDigest.equals(marker.opt("ownerScopeFingerprint")))return;
            marker.put(KEY,pin.proof(source,operation,wire.getString("argumentsDigest")));anchor.put("receipt_json",receipt.toString());
        }
    }
}
