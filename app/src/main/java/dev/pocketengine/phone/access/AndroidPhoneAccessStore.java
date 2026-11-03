package dev.pocketengine.phone.access;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private preferences hold owner choices only, never screenshots, text or model authority. */
public final class AndroidPhoneAccessStore implements PhoneAccessPolicy {
    private static final Object LOCK=new Object();
    private final SharedPreferences preferences;
    public AndroidPhoneAccessStore(Context context){preferences=context.getApplicationContext().getSharedPreferences("phone_app_access",Context.MODE_PRIVATE);}
    @Override public State current(){synchronized(LOCK){return read();}}
    private State read(){
        try{Map<AppCopyKey,Rule> rules=new LinkedHashMap<>();JSONArray entries=new JSONArray(preferences.getString("rules","[]"));
            if(entries.length()>600)throw new IllegalStateException("policy_too_large");
            for(int i=0;i<entries.length();i++){JSONObject row=entries.getJSONObject(i);AppCopyKey key=new AppCopyKey(row.getString("packageName"),row.getInt("userId"));
                if(rules.put(key,new Rule(row.getBoolean("automation"),row.getBoolean("ownerShare")))!=null)throw new IllegalStateException("duplicate_policy");}
            return new State(preferences.getLong("revision",0),true,rules);
        }catch(Exception unavailable){return new State(Math.max(0,preferences.getLong("revision",0)),false,Map.of());}
    }
    /** FeatureHost/native owner only. No model capability exposes this method. */
    public State setOwnerRule(AppCopyKey key,boolean automation,boolean ownerShare,long expectedRevision)throws Exception {synchronized(LOCK){
        State old=current();if(!old.available()||old.revision()!=expectedRevision)throw new SecurityException("phone_policy_changed");
        Map<AppCopyKey,Rule> rules=new LinkedHashMap<>(old.rules());rules.put(key,new Rule(automation,ownerShare));if(rules.size()>600)throw new IllegalStateException("phone_policy_full");
        JSONArray rows=new JSONArray();for(var entry:rules.entrySet())rows.put(new JSONObject().put("packageName",entry.getKey().packageName()).put("userId",entry.getKey().userId()).put("automation",entry.getValue().automation()).put("ownerShare",entry.getValue().ownerShare()));
        if(!preferences.edit().putLong("revision",Math.addExact(old.revision(),1)).putString("rules",rows.toString()).commit())throw new IllegalStateException("phone_policy_save_failed");return current();
    }}
    public JSONObject snapshot()throws Exception {State state=current();JSONArray rows=new JSONArray();for(var entry:state.rules().entrySet())rows.put(new JSONObject().put("packageName",entry.getKey().packageName()).put("userId",entry.getKey().userId()).put("automation",entry.getValue().automation()).put("ownerShare",entry.getValue().ownerShare()));return new JSONObject().put("revision",state.revision()).put("available",state.available()).put("rules",rows).put("modelWritable",false);}
}
