package dev.pocketengine.features.channels;

import org.json.JSONObject;

/** Original native-login UI projection only. It cannot start, poll, authenticate or bind an account. */
public final class ChannelLoginState {
    private ChannelLoginState() { }
    public static JSONObject merge(JSONObject current,JSONObject incoming,String expectedId)throws Exception {
        if(!expectedId.equals(incoming.optString("loginId")))return current==null?null:new JSONObject(current.toString());
        JSONObject previous=current!=null&&expectedId.equals(current.optString("loginId"))?current:null;
        // An ended/missing local attempt cannot be revived by replaying cached start/inspect/poll.
        if(previous!=null&&terminal(previous.optString("status")))return withoutQr(previous);
        JSONObject merged=previous==null?new JSONObject().put("loginId",expectedId):new JSONObject(previous.toString());
        for(String key:new String[]{"status","expiresAt","ownerBound","qrcodeContent","profile","authenticated","message"})if(incoming.has(key))merged.put(key,incoming.get(key));
        return terminal(merged.optString("status"))?withoutQr(merged):merged;
    }
    /** A newly queued start has no server row yet. Resume with no local start request only queries. */
    public static boolean mayInspect(JSONObject originalView,boolean localStartRequested){return originalView!=null||!localStartRequested;}
    private static boolean terminal(String status){return status.matches("confirmed|cancelled|expired|failed|unknown|verify_code_blocked");}
    private static JSONObject withoutQr(JSONObject value)throws Exception {JSONObject copy=new JSONObject(value.toString());copy.remove("qrcodeContent");return copy;}
}
