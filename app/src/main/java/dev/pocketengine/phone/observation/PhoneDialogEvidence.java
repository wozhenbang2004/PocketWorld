package dev.pocketengine.phone.observation;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Describes a current dialog without guessing a button or automatically accepting system permission. */
final class PhoneDialogEvidence {
    static JSONObject read(JSONObject observation)throws Exception {
        JSONArray windows=observation.optJSONArray("windows"),nodes=observation.optJSONArray("nodes"),buttons=new JSONArray();if(windows==null||nodes==null)return new JSONObject().put("ok",false).put("error","window_tree_unavailable");
        JSONObject selected=null;int layer=Integer.MIN_VALUE;Set<Integer> dialogWindows=new HashSet<>();for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(n.optString("role").toLowerCase(java.util.Locale.ROOT).contains("dialog")||n.optString("className").toLowerCase(java.util.Locale.ROOT).contains("dialog"))dialogWindows.add(n.optInt("windowId",-1));}
        for(int i=0;i<windows.length();i++){JSONObject w=windows.getJSONObject(i);if(dialogWindows.contains(w.optInt("windowId",-1))&&w.optInt("layer")>=layer){selected=w;layer=w.optInt("layer");}}
        if(selected==null)return new JSONObject().put("ok",true).put("found",false).put("coverage","explicit_dialog_role_only").put("complete",PhoneTreeExtract.complete(observation));
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(n.optInt("windowId",-1)==selected.getInt("windowId")&&!n.optBoolean("password")&&n.optBoolean("clickable")&&n.optBoolean("enabled",true))buttons.put(new JSONObject().put("nodeId",n.getString("id")).put("text",n.optString("text")).put("description",n.optString("description")));}
        return new JSONObject().put("ok",true).put("found",true).put("window",selected).put("observationId",observation.getString("observationId")).put("buttons",buttons).put("contentTrust","untrusted_screen_content").put("actionPerformed",false).put("automaticConfirmation",false);
    }
}
