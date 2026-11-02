package dev.pocketengine.phone.diagnostics;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Evidence sampled from the original port; no executing loop, grant or competing task state. */
public final class PhonePracticeEvidence {
    public static final java.util.List<String> CHECK_IDS=java.util.List.of("helper","display","tree","frame","click","ascii_input","chinese_input","scroll","back","live_updates","main_screen_isolation","ime_isolation");
    private static final Set<String> REASONS=Set.of("not_observed","background_not_requested","actual_native_observation","authenticated_helper_connected","isolation_unavailable","original_frame_identity_unavailable","actual_image_unavailable","image_encoding_invalid","image_dimensions_invalid","image_decode_failed","actual_nonuniform_frame","blank_or_uniform_frame","practice_target_not_current","frame_result_unavailable");
    private final String packageName;private final int display;private final Map<String,String> checks=new LinkedHashMap<>(),reasons=new LinkedHashMap<>();
    private Integer initialClicks,lastTick;private Set<String> initialRows;private boolean seenTarget,backRequested;private long initialTickAt;
    public PhonePracticeEvidence(String packageName,int display){this.packageName=packageName;this.display=display;for(String id:CHECK_IDS){checks.put(id,"unknown");reasons.put(id,"not_observed");}if(display==0)for(String id:java.util.List.of("helper","display","main_screen_isolation","ime_isolation"))reasons.put(id,"background_not_requested");}
    public synchronized void action(String name,JSONObject receipt){if("phone_back".equals(name)&&Boolean.TRUE.equals(receipt.opt("accepted")))backRequested=true;JSONObject landing=receipt.optJSONObject("displayLanding");if(landing!=null&&"owned_display_observed".equals(landing.optString("status")))checks.put("main_screen_isolation","passed");}
    public synchronized void observation(JSONObject actual)throws Exception {
        if(actual==null||!actual.optBoolean("ok"))return;
        if(actual.optInt("displayId",-1)!=display)return;
        JSONArray nodes=actual.optJSONArray("nodes");if(nodes==null)return;boolean target=false;Set<String> rows=new LinkedHashSet<>();Integer count=null,tick=null;
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n==null||n.optBoolean("password"))continue;String text=n.optString("text"),description=n.optString("description");
            if(text.contains("PW_DIAG_TARGET_V1"))target=true;if(text.startsWith("PW_DIAG_CLICK_COUNT:"))count=integer(text,"PW_DIAG_CLICK_COUNT:");if(text.startsWith("PW_DIAG_TICK:"))tick=integer(text,"PW_DIAG_TICK:");
            if(text.startsWith("PW_DIAG_ROW_")&&n.optBoolean("visible",true))rows.add(text);
            if(description.equals("PW_DIAG_INPUT")){if(text.equals("PocketWorld ASCII 42"))checks.put("ascii_input","passed");if(text.equals("口袋世界中文演练42"))checks.put("chinese_input","passed");}
        }
        if(!packageName.equals(actual.optString("packageName")))target=false;
        if(seenTarget&&!target&&backRequested)checks.put("back","passed");if(!target)return;seenTarget=true;
        checks.put("tree","passed");if(display>0&&actual.optString("sessionId").length()>0)checks.put("display","passed");
        if(count!=null){if(initialClicks==null)initialClicks=count;else if(count>initialClicks)checks.put("click","passed");}
        if(!rows.isEmpty()){if(initialRows==null)initialRows=rows;else if(!initialRows.equals(rows))checks.put("scroll","passed");}
        if(tick!=null){long at=actual.optLong("observedAtUptimeMs",-1);if(lastTick==null&&at>=0){lastTick=tick;initialTickAt=at;}else if(lastTick!=null&&initialTickAt>=0&&at>=initialTickAt+3000&&tick>=lastTick+3)checks.put("live_updates","passed");}
        JSONObject landing=actual.optJSONObject("displayLanding");if(landing!=null&&"owned_display_observed".equals(landing.optString("status")))checks.put("main_screen_isolation","passed");
    }
    public synchronized void nativeFacts(JSONObject actual){if(actual==null)return;if(actual.optBoolean("helperAuthenticated")){checks.put("helper","passed");reasons.put("helper","authenticated_helper_connected");}if("available".equals(actual.optString("imeIsolation")))checks.put("ime_isolation","passed");else if("unavailable".equals(actual.optString("imeIsolation"))){checks.put("ime_isolation","failed");reasons.put("ime_isolation","isolation_unavailable");}}
    public synchronized void frame(JSONObject actual){String state=actual==null?"unknown":actual.optString("state"),reason=actual==null?"frame_result_unavailable":actual.optString("reason");if(java.util.Set.of("passed","failed","unknown").contains(state)){checks.put("frame",state);reasons.put("frame",knownReason(reason)?reason:"frame_result_unavailable");}}
    public synchronized boolean targetCurrent(JSONObject actual)throws Exception {if(actual==null||!actual.optBoolean("ok")||!packageName.equals(actual.optString("packageName")))return false;JSONArray nodes=actual.optJSONArray("nodes");if(nodes==null)return false;for(int i=0;i<nodes.length();i++)if(nodes.getJSONObject(i).optString("text").contains("PW_DIAG_TARGET_V1"))return true;return false;}
    public synchronized JSONObject result()throws Exception {JSONArray rows=new JSONArray();boolean all=true;for(var entry:checks.entrySet()){String reason=reasons.get(entry.getKey());if("passed".equals(entry.getValue())&&(reason.equals("not_observed")||reason.equals("background_not_requested")))reason="actual_native_observation";rows.put(new JSONObject().put("id",entry.getKey()).put("state",entry.getValue()).put("reasonCode",reason));all&="passed".equals(entry.getValue());}return new JSONObject().put("ok",true).put("status",all?"passed":"partial").put("checks",rows).put("evidenceOrigin","original_run_native_observations").put("deviceVerified",false).put("containsPrivateContent",false);}
    static boolean knownReason(String reason){return REASONS.contains(reason);}
    private static Integer integer(String text,String prefix){try{return Integer.valueOf(text.substring(prefix.length()).trim());}catch(Exception invalid){return null;}}
}
