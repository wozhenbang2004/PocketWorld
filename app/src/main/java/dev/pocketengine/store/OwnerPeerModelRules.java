package dev.pocketengine.store;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import dev.pocketengine.features.devices.DeviceProtocol;
import dev.pocketengine.features.devices.PairingCoordinator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Foreground owner looking ONLY at model choices already made on this phone. No grant producer,
 * task lookups, peer transport, model credentials, scheduler or runner control. */
public final class OwnerPeerModelRules {
    private static final int PAGE = 12;
    private final TaskStore owner;
    private final SQLiteDatabase db;
    private final String here, peer, instance, fingerprint;
    private final long epoch, untilElapsed;
    private final String grantDigest;
    private final Map<String,String[]> choices = new HashMap<>();
    private final JSONObject view;
    private static SecurityException changed(){ return new SecurityException("这项本机模型规则或原设备授权已变化，请重新查看"); }
    static String key(String caller,String origin,String here,String grant,JSONObject resource)throws Exception {
        return "device_goal_model_policy:"+TaskStore.capabilityFingerprint(new JSONObject().put("callerDeviceId",caller).put("originDeviceId",origin)
                .put("targetDeviceId",here).put("grantId",grant).put("resourceBinding",resource));
    }
    OwnerPeerModelRules(TaskStore owner,SQLiteDatabase db,String here,String peer,String instance,String fingerprint,long epoch,int offset)throws Exception {
        this.owner=owner;this.db=db;this.here=here;this.peer=peer;this.instance=instance;this.fingerprint=fingerprint;this.epoch=epoch;
        if(here.equals(peer)||epoch<1||offset<0||offset>127||offset%PAGE!=0)throw changed();
        synchronized(owner){
            long now=System.currentTimeMillis();JSONObject grant=currentGrant(now);
            grantDigest=grant==null?"":DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(grant));
            JSONArray resources=grant==null?new JSONArray():grant.getJSONArray("resources"),rows=new JSONArray();
            if(offset>0 && offset>=resources.length())throw changed();
            long until=Math.min(now+90_000L,grant==null?Long.MAX_VALUE:grant.getLong("expiresAt"));
            untilElapsed=SystemClock.elapsedRealtime()+Math.max(0L,until-now);
            int end=Math.min(resources.length(),offset+PAGE);
            for(int i=offset;i<end;i++){
                JSONObject resource=resources.getJSONObject(i);
                String key=key(peer,peer,here,grant.getString("grantId"),resource),raw=original(key);
                if(raw==null)continue;
                JSONObject rule=new JSONObject(raw);
                if(!grantDigest.equals(rule.optString("grantFingerprint"))||rule.optLong("authorizationEpoch")!=epoch||rule.optLong("expiresAt")<=now)continue;
                if(!"pocketworld.device-goal-model-policy.v1".equals(rule.optString("format"))||!grant.getString("grantId").equals(rule.optString("grantId"))
                        ||!peer.equals(rule.opt("originDeviceId"))||!peer.equals(rule.opt("callerDeviceId"))||!here.equals(rule.opt("targetDeviceId"))
                        ||!DeviceProtocol.resourceEquals(resource,rule.getJSONObject("resourceBinding"))||!TaskStore.capabilityFingerprint(new JSONObject().put("resourceBinding",resource)
                            .put("toolNames",rule.getJSONArray("toolNames"))).equals(rule.opt("scopeDigest"))||rule.getString("policyId").isEmpty())throw changed();
                JSONObject row=new JSONObject().put("resource",resource).put("model",rule.getString("model"))
                        .put("tools",rule.getLong("toolBudget")).put("models",rule.getLong("modelBudget")).put("expiresAt",rule.getLong("expiresAt"));
                if(rule.has("ownerDisabledAt"))row.put("disabledAt",rule.getLong("ownerDisabledAt"));
                else{String token=UUID.randomUUID().toString();choices.put(token,new String[]{key,raw});row.put("choice",token);}
                rows.put(row);
            }
            if(!grantDigest.equals(digest(currentGrant(System.currentTimeMillis()))))throw changed();
            view=new JSONObject().put("here",here).put("peer",peer).put("instance",instance).put("fingerprint",fingerprint).put("epoch",epoch)
                    .put("offset",offset).put("previous",offset>0?offset-PAGE:-1).put("next",end<resources.length()?end:-1)
                    .put("hasCurrentGrant",grant!=null).put("rows",rows).put("until",until);
        }
    }
    private static String digest(JSONObject grant)throws Exception { return grant==null?"":DeviceProtocol.sha256(DeviceProtocol.canonicalBytes(grant)); }
    private String original(String key){
        try(Cursor row=db.rawQuery("SELECT value FROM meta WHERE key=?",new String[]{key})){return row.moveToFirst()?row.getString(0):null;}
    }
    private JSONObject currentGrant(long now)throws Exception {
        JSONObject p=owner.deviceTrust().peer(peer);
        if(p==null||p.optBoolean("revoked",true)||!instance.equals(p.opt("instanceId"))||!fingerprint.equals(p.opt("fingerprint"))||p.optLong("authorizationEpoch")!=epoch)throw changed();
        String key;
        try(Cursor row=db.rawQuery("SELECT grant_id FROM device_grants WHERE local_device_id=? AND peer_device_id=? AND authorization_epoch=? AND revoked=0 ORDER BY grant_id LIMIT 2",
                new String[]{here,peer,Long.toString(epoch)})){
            if(!row.moveToFirst())return null;key=row.getString(0);if(row.moveToNext())throw changed();
        }
        JSONObject g=owner.deviceTrust().grant(key);
        if(g==null||g.optLong("authorizationEpoch")!=epoch||g.optBoolean("revoked",true))throw changed();
        if(g.getLong("expiresAt")<=now)return null;
        PairingCoordinator.validateGrant(g,here,peer,now);return g;
    }
    public JSONObject view()throws Exception {return new JSONObject(view.toString());}
    public JSONObject disable(String token)throws Exception {
        synchronized(owner){db.beginTransaction();try{
            String[] picked=choices.remove(token);long now=System.currentTimeMillis();
            if(picked==null||SystemClock.elapsedRealtime()>=untilElapsed||now>=view.getLong("until")||!grantDigest.equals(digest(currentGrant(now))))throw changed();
            String raw=original(picked[0]);if(!picked[1].equals(raw))throw changed();
            JSONObject previous=new JSONObject(raw);if(previous.has("ownerDisabledAt")||previous.getLong("expiresAt")<=now)throw changed();
            ContentValues values=new ContentValues();values.put("value",previous.put("ownerDisabledAt",now).toString());
            if(db.update("meta",values,"key=? AND value=?",new String[]{picked[0],raw})!=1)throw changed();
            JSONObject receipt=new JSONObject().put("choice",token).put("peer",peer).put("disabledAt",now);
            db.setTransactionSuccessful();choices.clear();return receipt;
        }finally{db.endTransaction();}}
    }
}
