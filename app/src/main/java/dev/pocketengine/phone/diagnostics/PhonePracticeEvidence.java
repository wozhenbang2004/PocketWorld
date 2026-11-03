package dev.pocketengine.phone.diagnostics;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual before/action/after evidence on one original port, never an executing loop or authority. */
public final class PhonePracticeEvidence {
    public static final java.util.List<String> CHECK_IDS=java.util.List.of("helper","display","tree","frame","click","ascii_input","chinese_input","scroll","back","live_updates","main_screen_isolation","ime_isolation");
    private static final Set<String> REASONS=Set.of("not_observed","background_not_requested","actual_native_observation","authenticated_helper_connected","isolation_unavailable","original_frame_identity_unavailable","actual_image_unavailable","image_encoding_invalid","image_dimensions_invalid","image_decode_failed","actual_nonuniform_frame","blank_or_uniform_frame","practice_target_not_current","frame_result_unavailable");
    private final String packageName,practiceSession;private final int display;
    private final Map<String,String> checks=new LinkedHashMap<>(),reasons=new LinkedHashMap<>();
    private final Set<String> clickNodes=new LinkedHashSet<>(),inputNodes=new LinkedHashSet<>(),scrollNodes=new LinkedHashSet<>();
    private Integer lastClicks,initialTick,pendingClick;private Set<String> visibleRows=Set.of(),pendingRows;
    private String observationId="",actionObservation="",pendingText;private boolean seenTarget,targetVisible,backRequested;
    private long initialTickAt=-1;
    public PhonePracticeEvidence(String packageName,int display){this(packageName,display,"");}
    public PhonePracticeEvidence(String packageName,int display,String practiceSession){
        this.packageName=packageName;this.display=display;this.practiceSession=practiceSession==null?"":practiceSession;
        for(String id:CHECK_IDS){checks.put(id,"unknown");reasons.put(id,"not_observed");}
        if(display==0)for(String id:java.util.List.of("helper","display","main_screen_isolation","ime_isolation"))reasons.put(id,"background_not_requested");
    }
    /** Legacy callers supply no action target and therefore cannot claim click/input/scroll success. */
    public synchronized void action(String name,JSONObject receipt){action(name,new JSONObject(),receipt);}
    public synchronized void action(String name,JSONObject args,JSONObject receipt){
        if(receipt==null||!Boolean.TRUE.equals(receipt.opt("accepted")))return;
        if(targetVisible&&"phone_back".equals(name))backRequested=true;
        if(targetVisible&&observationId.equals(args.optString("observationId"))){
            String node=args.optString("nodeId");
            if("phone_click_node".equals(name)&&clickNodes.contains(node)&&lastClicks!=null){pendingClick=lastClicks;actionObservation=observationId;}
            if(java.util.Set.of("phone_type","phone_paste_text").contains(name)&&inputNodes.contains(node)){
                String value=args.optString("text");if(value.equals(PhonePracticeActivity.ASCII)||value.equals(PhonePracticeActivity.CHINESE)){pendingText=value;actionObservation=observationId;}
            }
            if(java.util.Set.of("phone_scroll_node","phone_set_wheel").contains(name)&&scrollNodes.contains(node)){pendingRows=new LinkedHashSet<>(visibleRows);actionObservation=observationId;}
        }
        landing(receipt);
    }
    public synchronized void observation(JSONObject actual)throws Exception {
        if(actual==null||!actual.optBoolean("ok")||actual.optInt("displayId",-1)!=display){clearPending();targetVisible=false;return;}
        JSONArray nodes=actual.optJSONArray("nodes");if(nodes==null){clearPending();targetVisible=false;return;}
        boolean target=targetCurrent(actual);
        if(seenTarget&&!target&&backRequested){checks.put("back","passed");backRequested=false;}
        targetVisible=target;clickNodes.clear();inputNodes.clear();scrollNodes.clear();if(!target){clearPending();return;}
        seenTarget=true;checks.put("tree","passed");if(display>0&&!actual.optString("sessionId").isEmpty())checks.put("display","passed");
        Set<String> rows=new LinkedHashSet<>();Integer count=null,tick=null;String input=null;
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n==null||n.optBoolean("password")||!n.optBoolean("visible",true))continue;
            String text=n.optString("text"),description=n.optString("description"),id=n.optString("id",n.optString("nodeId"));
            if(text.startsWith("PW_DIAG_CLICK_COUNT:"))count=integer(text,"PW_DIAG_CLICK_COUNT:");
            if(text.startsWith("PW_DIAG_TICK:"))tick=integer(text,"PW_DIAG_TICK:");
            if(text.startsWith("PW_DIAG_ROW_"))rows.add(text);
            if(description.equals("PW_DIAG_CLICK"))clickNodes.add(id);
            if(description.equals("PW_DIAG_INPUT")){inputNodes.add(id);input=text;}
            if(description.equals("PW_DIAG_LIST"))scrollNodes.add(id);
        }
        String after=actual.optString("observationId");boolean fresh=!after.isBlank()&&!after.equals(actionObservation);
        if(fresh&&pendingClick!=null&&count!=null&&count>pendingClick){checks.put("click","passed");pendingClick=null;}
        if(fresh&&pendingText!=null&&pendingText.equals(input)){checks.put(pendingText.equals(PhonePracticeActivity.ASCII)?"ascii_input":"chinese_input","passed");pendingText=null;}
        if(fresh&&pendingRows!=null&&!rows.isEmpty()&&!pendingRows.equals(rows)){checks.put("scroll","passed");pendingRows=null;}
        if(fresh)clearPending();
        lastClicks=count;visibleRows=rows;observationId=after;
        if(tick!=null){long at=actual.optLong("observedAtUptimeMs",-1);if(initialTick==null&&at>=0){initialTick=tick;initialTickAt=at;}else if(initialTick!=null&&at>=initialTickAt+3000&&tick>=initialTick+3)checks.put("live_updates","passed");}
        landing(actual);
    }
    private void landing(JSONObject actual){JSONObject landing=actual.optJSONObject("displayLanding");if(display>0&&landing!=null&&"owned_display_observed".equals(landing.optString("status")))checks.put("main_screen_isolation","passed");}
    public synchronized void nativeFacts(JSONObject actual){if(actual==null)return;if(display>0&&actual.optBoolean("helperAuthenticated")){checks.put("helper","passed");reasons.put("helper","authenticated_helper_connected");}if(display>0&&"available".equals(actual.optString("imeIsolation")))checks.put("ime_isolation","passed");else if(display>0&&"unavailable".equals(actual.optString("imeIsolation"))){checks.put("ime_isolation","failed");reasons.put("ime_isolation","isolation_unavailable");}}
    public synchronized void frame(JSONObject actual){String state=actual==null?"unknown":actual.optString("state"),reason=actual==null?"frame_result_unavailable":actual.optString("reason");if(java.util.Set.of("passed","failed","unknown").contains(state)){checks.put("frame",state);reasons.put("frame",knownReason(reason)?reason:"frame_result_unavailable");}}
    public synchronized boolean targetCurrent(JSONObject actual)throws Exception {
        if(practiceSession.isBlank()||actual==null||!actual.optBoolean("ok")||actual.optInt("displayId",-1)!=display||!packageName.equals(actual.optString("packageName")))return false;
        JSONArray nodes=actual.optJSONArray("nodes");if(nodes==null)return false;boolean marker=false,session=false;
        for(int i=0;i<nodes.length();i++){JSONObject node=nodes.getJSONObject(i);if(node.optBoolean("password"))continue;String text=node.optString("text");marker|=text.contains(PhonePracticeActivity.MARKER);session|=java.util.Arrays.asList(text.split("\\n")).contains("PW_DIAG_SESSION:"+practiceSession);}
        return marker&&session;
    }
    public synchronized JSONObject result()throws Exception {
        JSONArray rows=new JSONArray();boolean all=true;int applicable=0,passed=0;
        for(var entry:checks.entrySet()){String reason=reasons.get(entry.getKey());boolean applies=!"background_not_requested".equals(reason);if(applies){applicable++;if("passed".equals(entry.getValue()))passed++;else all=false;}
            if("passed".equals(entry.getValue())&&reason.equals("not_observed"))reason="actual_native_observation";
            rows.put(new JSONObject().put("id",entry.getKey()).put("state",entry.getValue()).put("applicable",applies).put("reasonCode",reason));}
        return new JSONObject().put("ok",true).put("status",all&&applicable>0?"passed":"partial").put("checks",rows).put("applicableChecks",applicable).put("passedChecks",passed)
                .put("evidenceOrigin","original_run_native_observations").put("deviceVerified",false).put("containsPrivateContent",false);
    }
    static boolean knownReason(String reason){return REASONS.contains(reason);}
    private void clearPending(){pendingClick=null;pendingText=null;pendingRows=null;actionObservation="";backRequested=false;}
    private static Integer integer(String text,String prefix){try{return Integer.valueOf(text.substring(prefix.length()).trim());}catch(Exception invalid){return null;}}
}
