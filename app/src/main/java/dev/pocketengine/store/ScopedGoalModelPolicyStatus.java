package dev.pocketengine.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.PairingCoordinator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local readiness hint for this authenticated audience, not a grant. Look up policy primary keys
 * derived from its current resource bindings; other partners' and other origins' rows are never scanned. */
final class ScopedGoalModelPolicyStatus {
    private record Scope(String grant,String fingerprint,JSONObject resource){}
    private ScopedGoalModelPolicyStatus(){}
    static JSONObject read(SQLiteDatabase db,TaskStore owner,String here,String caller,String origin,long epoch,String exactGrant)throws Exception{
        long now=System.currentTimeMillis();boolean partial=false;JSONArray policies=new JSONArray();
        Map<String,Scope> keys=new LinkedHashMap<>();
        String sql="SELECT g.grant_id FROM device_grants g JOIN device_peers p ON p.device_id=g.peer_device_id AND p.authorization_epoch=g.authorization_epoch "
                +"WHERE g.local_device_id=? AND g.peer_device_id=? AND g.authorization_epoch=? AND g.revoked=0 AND p.revoked=0 AND g.expires_at>?"
                +(exactGrant==null?"":" AND g.grant_id=?")+" ORDER BY g.grant_id LIMIT 17";
        String[] params=exactGrant==null?new String[]{here,caller,Long.toString(epoch),Long.toString(now)}:new String[]{here,caller,Long.toString(epoch),Long.toString(now),exactGrant};
        try(Cursor grants=db.rawQuery(sql,params)){
            int count=0;
            while(grants.moveToNext()){
                if(++count>16){partial=true;break;}
                String id=grants.getString(0);JSONObject g=owner.deviceTrust().grant(id);
                if(g==null||!Boolean.FALSE.equals(g.opt("revoked"))||g.optLong("authorizationEpoch")!=epoch||g.optLong("expiresAt")<=now)continue;
                PairingCoordinator.validateGrant(g,here,caller,now);
                if(!contains(g.getJSONArray("allowedOrigins"),origin)||!contains(g.getJSONArray("capabilities"),"task.submitGoal"))continue;
                String fingerprint=DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(g));JSONArray resources=g.getJSONArray("resources");
                for(int i=0;i<resources.length();i++){
                    if(keys.size()>=128){partial=true;break;}
                    JSONObject resource=resources.getJSONObject(i);
                    keys.put(OwnerPeerModelRules.key(caller,origin,here,id,resource),new Scope(id,fingerprint,resource));
                }
                if(partial)break;
            }
        }
        if(!keys.isEmpty()){
            String marks=String.join(",",Collections.nCopies(keys.size(),"?"));
            try(Cursor rows=db.rawQuery("SELECT key,value FROM meta WHERE key IN ("+marks+") ORDER BY rowid DESC",keys.keySet().toArray(new String[0]))){
                while(rows.moveToNext()){
                    Scope s=keys.get(rows.getString(0));JSONObject p=new JSONObject(rows.getString(1));
                    if(s==null||p.has("ownerDisabledAt")||!"pocketworld.device-goal-model-policy.v1".equals(p.opt("format"))
                        ||!caller.equals(p.opt("callerDeviceId"))||!origin.equals(p.opt("originDeviceId"))||!here.equals(p.opt("targetDeviceId"))
                        ||epoch!=p.optLong("authorizationEpoch")||p.optLong("expiresAt")<=System.currentTimeMillis()
                        ||!s.grant().equals(p.opt("grantId"))||!s.fingerprint().equals(p.opt("grantFingerprint"))
                        ||!DeviceProtocol.resourceEquals(s.resource(),p.getJSONObject("resourceBinding"))||p.optJSONArray("toolNames")==null
                        ||!TaskStore.capabilityFingerprint(new JSONObject().put("resourceBinding",s.resource()).put("toolNames",p.getJSONArray("toolNames"))).equals(p.opt("scopeDigest")))continue;
                    if(policies.length()>=8){partial=true;break;}policies.put(p);
                }
            }
        }
        return new JSONObject().put("callerDeviceId",caller).put("configured",policies.length()>0).put("policies",policies).put("truncated",partial).put("executionAuthorized",false);
    }
    private static boolean contains(JSONArray values,String item){for(int i=0;i<values.length();i++)if(item.equals(values.opt(i)))return true;return false;}
}
