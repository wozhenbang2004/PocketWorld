package dev.pocketengine.phone.setup;

import dev.pocketengine.phone.access.AppCopyKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable restoration of this host's explicit per-App audio changes. Never changes global volume. */
public final class PhoneAudioRecovery {
    public interface Store {String read()throws Exception;void write(String value)throws Exception;}
    public interface Audio {String read(AppCopyKey app)throws Exception;void write(AppCopyKey app,String mode)throws Exception;}
    private record Saved(String owner,String before) { }
    private final Store store;private final Audio audio;private final Map<AppCopyKey,Saved> records=new LinkedHashMap<>();
    public PhoneAudioRecovery(Store store,Audio audio)throws Exception {this.store=store;this.audio=audio;String encoded=store.read();if(encoded==null||encoded.isEmpty())return;JSONArray rows=new JSONArray(encoded);if(rows.length()>200)throw new SecurityException("audio_ledger_too_large");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);AppCopyKey key=new AppCopyKey(row.getString("packageName"),row.getInt("userId"));String mode=row.getString("before");mode(mode);if(records.put(key,new Saved(row.getString("owner"),mode))!=null)throw new SecurityException("audio_ledger_duplicate");}}
    public synchronized JSONObject silence(String owner,AppCopyKey app,boolean ownerAllowed)throws Exception {
        if(!ownerAllowed)return receipt("owner_audio_policy_disabled",false);Saved saved=records.get(app);if(saved!=null&&!saved.owner.equals(owner))throw new SecurityException("audio_owned_by_other_run");boolean first=saved==null;
        if(saved==null){String before=audio.read(app);mode(before);records.put(app,new Saved(owner,before));try{save();}catch(Exception failure){records.remove(app);throw failure;}}
        try{String current=audio.read(app);mode(current);
            // This durable claim admits exactly one set attempt. A retained or cold row
            // is query-only: an unknown set is not retried and the owner's later return
            // to the original mode must not be mistaken for permission to mute again.
            if(!first||current.equals("ignore"))return receipt(current.equals("ignore")?"silenced_verified":"audio_changed_by_owner",true);
            if(!current.equals(records.get(app).before))return receipt("audio_changed_by_owner",true);audio.write(app,"ignore");return receipt("ignore".equals(audio.read(app))?"silenced_verified":"silence_unconfirmed",true);}catch(Exception unknown){return receipt("silence_unconfirmed",true);}
    }
    public synchronized JSONObject restoreOwner(String owner)throws Exception {
        int restored=0,conflicts=0;for(AppCopyKey app:new java.util.ArrayList<>(records.keySet())){Saved saved=records.get(app);if(!saved.owner.equals(owner))continue;
            try{String current=audio.read(app);mode(current);if(current.equals(saved.before)){remove(app);restored++;continue;}if(!current.equals("ignore")){remove(app);conflicts++;continue;}audio.write(app,saved.before);if(saved.before.equals(audio.read(app))){remove(app);restored++;}}catch(Exception unavailable){/* Keep the original durable row for owner recovery. */}}
        return new JSONObject().put("restored",restored).put("supersededByOwner",conflicts).put("pending",records.values().stream().filter(row->row.owner.equals(owner)).count()).put("globalVolumeChanged",false);
    }
    public synchronized JSONObject snapshot()throws Exception {return new JSONObject().put("pendingCount",records.size()).put("containsPackageList",false).put("globalVolumeChanged",false);}
    public synchronized JSONObject restoreAll()throws Exception {int restored=0,conflicts=0;Set<String> owners=new java.util.LinkedHashSet<>();for(Saved row:records.values())owners.add(row.owner);for(String owner:owners){JSONObject result=restoreOwner(owner);restored+=result.getInt("restored");conflicts+=result.getInt("supersededByOwner");}return new JSONObject().put("restored",restored).put("supersededByOwner",conflicts).put("pending",records.size()).put("globalVolumeChanged",false);}
    private void remove(AppCopyKey key)throws Exception {Saved old=records.remove(key);try{save();}catch(Exception failure){records.put(key,old);throw failure;}}
    private void save()throws Exception {JSONArray rows=new JSONArray();for(var row:records.entrySet())rows.put(new JSONObject().put("packageName",row.getKey().packageName()).put("userId",row.getKey().userId()).put("owner",row.getValue().owner).put("before",row.getValue().before));store.write(rows.toString());}
    private static void mode(String value){if(value==null||!Set.of("allow","ignore","deny","default","foreground").contains(value))throw new SecurityException("audio_mode_unavailable");}
    private static JSONObject receipt(String outcome,boolean recorded)throws Exception{return new JSONObject().put("outcome",outcome).put("restorationRecorded",recorded).put("globalVolumeChanged",false);}
}
