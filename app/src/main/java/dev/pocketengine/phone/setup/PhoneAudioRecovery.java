package dev.pocketengine.phone.setup;

import dev.pocketengine.phone.access.AppCopyKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable accounting of this host's per-App audio changes. Never changes global volume.
 * The appops get/set path exposes no compare-and-set or package mute lease.
 * Automatic cleanup therefore only reconciles observations; restoring a still-muted App
 * requires a new, explicit owner set. A readback never proves there was no intervening writer. */
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
    /** The original owner can inspect only the current App; this never admits a new set. */
    public synchronized JSONObject inspectOwner(String owner,AppCopyKey app)throws Exception {
        Saved saved=records.get(app);if(saved!=null&&!saved.owner.equals(owner))throw new SecurityException("audio_owned_by_other_run");
        String current=audio.read(app);mode(current);
        return new JSONObject().put("mode",current).put("muted",current.equals("ignore"))
                .put("restorationRecorded",saved!=null).put("originalMode",saved==null?JSONObject.NULL:saved.before)
                .put("canMute",saved==null&&!current.equals("ignore")).put("canRestore",saved!=null)
                .put("restoreRequiresOwnerSet",saved!=null&&current.equals("ignore")&&!current.equals(saved.before))
                .put("automaticRestoreSupported",false).put("restoreSemantics","owner_set_saved_mode")
                .put("globalVolumeChanged",false);
    }
    public synchronized JSONObject restoreOwner(String owner)throws Exception {return restore(owner,null,false,false);}
    /** Read-only reconciliation for an exact original App. It never writes the saved mode. */
    public synchronized JSONObject restoreApp(String owner,AppCopyKey app)throws Exception {
        Saved saved=records.get(app);if(saved!=null&&!saved.owner.equals(owner))throw new SecurityException("audio_owned_by_other_run");
        return restore(owner,app,true,false);
    }
    /** The native, one-shot owner review explicitly sets the recorded mode. This is a new
     * unconditional owner action, not an atomic rollback or automatic cleanup capability. */
    public synchronized JSONObject setOriginalForOwner(String owner,AppCopyKey app)throws Exception {
        Saved saved=records.get(app);if(saved!=null&&!saved.owner.equals(owner))throw new SecurityException("audio_owned_by_other_run");
        return restore(owner,app,true,true);
    }
    /** Reconcile earlier targets before switching. A still-muted target remains pending. */
    public synchronized JSONObject restoreOwnerExcept(String owner,AppCopyKey keep)throws Exception {return restore(owner,keep,false,false);}
    private JSONObject restore(String owner,AppCopyKey selected,boolean onlySelected,boolean ownerSet)throws Exception {
        int restored=0,conflicts=0,attempted=0;
        for(AppCopyKey app:new java.util.ArrayList<>(records.keySet())) {
            Saved saved=records.get(app);
            if(!saved.owner.equals(owner)||selected!=null&&(onlySelected!=app.equals(selected)))continue;
            try {
                String current=audio.read(app);mode(current);
                if(current.equals(saved.before)){remove(app);restored++;continue;}
                if(!current.equals("ignore")){remove(app);conflicts++;continue;}
                // Even if this read returned our last value, an external writer can
                // change it before setMode. Automatic cleanup must never take that
                // read as authority to overwrite the new value.
                if(!ownerSet)continue;
                attempted++;
                audio.write(app,saved.before);
                if(saved.before.equals(audio.read(app))){remove(app);restored++;}
            } catch(Exception unavailable) { /* Unknown writes/reads retain the original durable row. */ }
        }
        long pending=records.entrySet().stream().filter(row->row.getValue().owner.equals(owner)&&(selected==null||onlySelected==row.getKey().equals(selected))).count();
        return recoveryReceipt(restored,conflicts,pending,attempted,ownerSet);
    }
    public synchronized JSONObject snapshot()throws Exception {
        return new JSONObject().put("pendingCount",records.size()).put("containsPackageList",false)
                .put("needsOwnerAction",!records.isEmpty()).put("automaticRestoreSupported",false).put("globalVolumeChanged",false);
    }
    public synchronized JSONObject restoreAll()throws Exception {return restoreAll(false);}
    /** Native settings confirmation only. The UI must disclose that this is a new set,
     * which can supersede a concurrent change; background cleanup never calls it. */
    public synchronized JSONObject setAllOriginalForOwner()throws Exception {return restoreAll(true);}
    private JSONObject restoreAll(boolean ownerSet)throws Exception {
        int restored=0,conflicts=0,attempted=0;Set<String> owners=new java.util.LinkedHashSet<>();
        for(Saved row:records.values())owners.add(row.owner);
        for(String owner:owners){JSONObject result=restore(owner,null,false,ownerSet);restored+=result.getInt("restored");conflicts+=result.getInt("supersededByOwner");attempted+=result.getInt("writeAttempts");}
        return recoveryReceipt(restored,conflicts,records.size(),attempted,ownerSet);
    }
    private static JSONObject recoveryReceipt(int restored,int conflicts,long pending,int attempted,boolean ownerSet)throws Exception {
        return new JSONObject().put("restored",restored).put("supersededByOwner",conflicts).put("pending",pending)
                .put("status",pending>0?"audio_owner_action_required":"audio_reconciled")
                .put("needsOwnerAction",pending>0).put("mayRemainMuted",pending>0)
                .put("automaticRestoreSupported",false).put("atomicRestore",false)
                .put("ownerRequestedWrite",ownerSet).put("writeAttempts",attempted).put("globalVolumeChanged",false)
                .put("message",pending>0?"原 App 可能仍处于静音。当前音频接口无法按旧值原子恢复，未自动覆盖当前声音；请在原任务或手机设置中明确处理保留的声音记录。":"已核对原声音记录；其他已观察到的模式保持不变。");
    }
    private void remove(AppCopyKey key)throws Exception {Saved old=records.remove(key);try{save();}catch(Exception failure){records.put(key,old);throw failure;}}
    private void save()throws Exception {JSONArray rows=new JSONArray();for(var row:records.entrySet())rows.put(new JSONObject().put("packageName",row.getKey().packageName()).put("userId",row.getKey().userId()).put("owner",row.getValue().owner).put("before",row.getValue().before));store.write(rows.toString());}
    private static void mode(String value){if(value==null||!Set.of("allow","ignore","deny","default","foreground").contains(value))throw new SecurityException("audio_mode_unavailable");}
    private static JSONObject receipt(String outcome,boolean recorded)throws Exception{return new JSONObject().put("outcome",outcome).put("restorationRecorded",recorded).put("globalVolumeChanged",false);}
}
