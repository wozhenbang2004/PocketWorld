package dev.pocketengine.features.remote;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** One explicit phone-owner presentation choice. Never carries a token, prompt, approval, or executable input. */
final class CloudWorkspaceChoice {
    private final SharedPreferences prefs;
    CloudWorkspaceChoice(SharedPreferences prefs){this.prefs=prefs;}
    private static boolean hash(Object v){return v instanceof String && ((String)v).matches("[a-f0-9]{64}");}
    private static boolean id(Object v){return v instanceof String && ((String)v).matches("[A-Za-z0-9_-]{1,80}");}
    private long revision(){return prefs.getLong("revision",0L);}
    JSONObject view(JSONArray currentProfiles)throws Exception {
        JSONObject result=new JSONObject().put("revision",revision()).put("selectionOnly",true).put("liveState","not_checked");
        String raw=prefs.getString("target",null);if(raw==null)return result;
        JSONObject stored;try{stored=new JSONObject(raw);}catch(org.json.JSONException damaged){return result.put("choiceUnreadable",true); }
        for(String k:new String[]{"profileId","environmentId","worker"})if(!id(stored.opt(k)))return result;
        if(!hash(stored.opt("profileBinding"))||!hash(stored.opt("workspaceAnchor")))return result;
        for(int n=0;n<currentProfiles.length();n++){
            JSONObject p=currentProfiles.getJSONObject(n);
            if(stored.get("profileId").equals(p.opt("id"))&&stored.get("profileBinding").equals(p.opt("bindingHash"))&&p.optBoolean("hasToken"))return result.put("target",stored);
        }
        return result; // A replaced/deleted account must not see the previous owner's location metadata.
    }
    private void put(long expected,JSONObject next)throws Exception {
        if(expected<0||expected!=revision())throw new IllegalStateException("常用云环境已变，请重新读取后再选择");
        SharedPreferences.Editor edit=prefs.edit().putLong("revision",Math.addExact(expected,1));
        if(next==null)edit.remove("target");else edit.putString("target",next.toString());
        if(!edit.commit())throw new IllegalStateException("手机尚未确认保存常用云环境，请重新读取；不重试执行");
    }
    void select(JSONObject profile,JSONObject observed,JSONObject request,long expected)throws Exception {
        String worker=request.getString("worker"),env=request.getString("environmentId"),pf=profile.getString("id"),pb=profile.getString("bindingHash");
        if(!id(worker)||"command".equals(worker)||!id(env)||!id(pf)||!hash(pb)||!profile.optBoolean("hasToken")||!env.equals(observed.opt("id")))throw new SecurityException("请先读取这台 Companion 的实际环境");
        JSONObject location=observed.optJSONObject("hostWorkspace");
        if(!"local".equals(observed.opt("kind"))||!hash(request.opt("environmentBinding"))||!request.get("environmentBinding").equals(observed.opt("bindingHash"))
                ||location==null||!"companion_host_workspace_v1".equals(location.opt("format"))||!hash(location.opt("anchor"))||!location.get("anchor").equals(request.opt("workspaceAnchor"))
                ||!"persistent_operations".equals(location.opt("jobRegistry"))||!"not_guaranteed".equals(location.opt("runningAfterSupervisorRestart"))||location.opt("processSandbox")!=Boolean.FALSE)
            throw new SecurityException("这不是由当前主机核对的本地工作区，未记住其他目录或 SSH 第二跳");
        put(expected,new JSONObject().put("profileId",pf).put("profileBinding",pb).put("environmentId",env).put("workspaceAnchor",location.get("anchor")).put("worker",worker));
    }
    void forget(JSONObject profile,JSONArray currentProfiles,long expected)throws Exception {
        JSONObject target=view(currentProfiles).optJSONObject("target");
        if(target==null||!profile.get("id").equals(target.opt("profileId"))||!profile.get("bindingHash").equals(target.opt("profileBinding")))throw new SecurityException("这不是已保存的原主机，不移除其他选择");
        put(expected,null);
    }
}
