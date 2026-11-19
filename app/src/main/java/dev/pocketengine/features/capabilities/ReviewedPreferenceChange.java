package dev.pocketengine.features.capabilities;

import dev.pocketengine.core.ToolOutcome;
import dev.pocketengine.store.SettingsStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-visible meaning of one original committed operation; not a view over live settings. */
public final class ReviewedPreferenceChange {
    private ReviewedPreferenceChange(){}
    private static long revision(Object value){return value instanceof Long||value instanceof Integer?((Number)value).longValue():-1;}
    public static JSONObject fromOperation(String tool,String state,Object input,Object output)throws Exception{
        if(!AgentPreferencePort.SET.equals(tool)||!"receipt".equals(state)||!(input instanceof JSONObject args)||!(output instanceof JSONObject result)
                ||!Boolean.TRUE.equals(result.opt("ok"))||!Boolean.TRUE.equals(result.opt("accepted"))||!"agent_preference_saved".equals(result.opt("code"))
                ||!Boolean.TRUE.equals(result.opt("reversible"))||result.has("terminal")&&!Boolean.TRUE.equals(result.opt("terminal"))
                ||result.has("status")&&!java.util.Set.of("completed","succeeded").contains(result.optString("status"))||!ToolOutcome.classify(result).successful()||args.length()!=3||!(args.opt("key") instanceof String key)||!args.has("value"))return null;
        long before=revision(args.opt("expectedRevision"));JSONObject old=result.optJSONObject("previous"),current=result.optJSONObject("current");
        if(before<1||before==Long.MAX_VALUE||old==null||current==null||before!=revision(old.opt("revision"))||before+1!=revision(current.opt("revision"))
                ||!Boolean.TRUE.equals(old.opt("enabled"))||!Boolean.TRUE.equals(current.opt("enabled")))return null;
        JSONObject choices=SettingsStore.agentPreferenceChoices();
        if(!choices.has(key)||old.length()!=choices.length()+2||current.length()!=choices.length()+2||!args.get("value").equals(current.opt(key)))return null;
        for(var keys=choices.keys();keys.hasNext();){
            String k=keys.next();JSONArray options=choices.getJSONArray(k);boolean sawOld=false,sawNew=false;
            for(int i=0;i<options.length();i++){
                Object option=options.get(i);if(option.equals(old.opt(k)))sawOld=true;if(option.equals(current.opt(k)))sawNew=true;
            }
            if(!sawOld||!sawNew||!key.equals(k)&&!old.get(k).equals(current.get(k)))return null;
        }
        return new JSONObject().put("format","pocketworld.owner-default-change.v1").put("key",key).put("before",old.get(key)).put("after",current.get(key))
                .put("beforeRevision",before).put("afterRevision",before+1).put("changed",!old.get(key).equals(current.get(key)));
    }
}
