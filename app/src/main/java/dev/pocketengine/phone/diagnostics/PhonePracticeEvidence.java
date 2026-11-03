package dev.pocketengine.phone.diagnostics;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual before/action/after evidence on one original port, never an executing loop or authority. */
public final class PhonePracticeEvidence {
    public static final java.util.List<String> CHECK_IDS=java.util.List.of("helper","display","tree","frame","click","ascii_input","chinese_input","scroll","back","live_updates","main_screen_isolation","ime_isolation","rotation");
    private static final Set<String> REASONS=Set.of("not_observed","background_not_requested","actual_native_observation","authenticated_helper_connected","isolation_unavailable","original_frame_identity_unavailable","actual_image_unavailable","image_encoding_invalid","image_dimensions_invalid","image_decode_failed","actual_nonuniform_frame","blank_or_uniform_frame","practice_target_not_current","frame_result_unavailable","rotation_requested_not_observed","rotation_frame_and_input_pending","rotation_frame_geometry_mismatch","rotation_frame_unavailable","rotation_target_lost","rotation_observed_with_frame_and_input");
    private final String packageName,practiceSession;private final int display;
    private final Map<String,String> checks=new LinkedHashMap<>(),reasons=new LinkedHashMap<>();
    private final Map<String,JSONObject> observed=new LinkedHashMap<>();
    private final Set<String> clickNodes=new LinkedHashSet<>(),inputNodes=new LinkedHashSet<>(),scrollNodes=new LinkedHashSet<>(),rotateNodes=new LinkedHashSet<>();
    private Integer lastClicks,initialTick,pendingClick;private Set<String> visibleRows=Set.of(),pendingRows;
    private String observationId="",actionObservation="",pendingText;private boolean seenTarget,targetVisible,backRequested;
    private long initialTickAt=-1;
    private PhonePracticeContract.Layout lastLayout,rotationBefore,rotationAfter;
    private String rotationActionObservation="";private boolean pendingRotationClick,rotationClick,rotationFrame,rotationInvalid;
    public PhonePracticeEvidence(String packageName,int display){this(packageName,display,"");}
    public PhonePracticeEvidence(String packageName,int display,String practiceSession){
        this.packageName=packageName;this.display=display;this.practiceSession=practiceSession==null?"":practiceSession;
        for(String id:CHECK_IDS){checks.put(id,"unknown");reasons.put(id,"not_observed");}
        if(display==0)for(String id:java.util.List.of("helper","display","main_screen_isolation","ime_isolation","rotation"))reasons.put(id,"background_not_requested");
    }
    /** Legacy callers supply no action target and therefore cannot claim click/input/scroll success. */
    public synchronized void action(String name,JSONObject receipt){action(name,new JSONObject(),receipt);}
    public synchronized void action(String name,JSONObject args,JSONObject receipt){
        if(receipt==null||!Boolean.TRUE.equals(receipt.opt("accepted")))return;
        if(targetVisible&&"phone_back".equals(name))backRequested=true;
        if(targetVisible&&observationId.equals(args.optString("observationId"))){
            String node=args.optString("nodeId");
            if("phone_click_node".equals(name)&&clickNodes.contains(node)&&lastClicks!=null){pendingClick=lastClicks;actionObservation=observationId;pendingRotationClick=rotationAfter!=null&&sameLayout(lastLayout,rotationAfter);}
            if(display>0&&"phone_click_node".equals(name)&&rotateNodes.contains(node)&&lastLayout!=null&&lastLayout.request()==0&&rotationBefore==null){
                rotationBefore=lastLayout;rotationActionObservation=observationId;reasons.put("rotation","rotation_requested_not_observed");
                observed.put("rotation",facts("actionAccepted",true,"before",layoutFacts(rotationBefore),"actualRotationVerified",false));
            }
            if(java.util.Set.of("phone_type","phone_paste_text").contains(name)&&inputNodes.contains(node)){
                String value=args.optString("text");if(value.equals(PhonePracticeContract.ASCII)||value.equals(PhonePracticeContract.CHINESE)){pendingText=value;actionObservation=observationId;}
            }
            if(java.util.Set.of("phone_scroll_node","phone_set_wheel").contains(name)&&scrollNodes.contains(node)){pendingRows=new LinkedHashSet<>(visibleRows);actionObservation=observationId;}
        }
        landing(receipt);
    }
    public synchronized void observation(JSONObject actual)throws Exception {
        if(actual==null||!actual.optBoolean("ok")||actual.optInt("displayId",-1)!=display){clearPending();clearRotation();targetVisible=false;return;}
        JSONArray nodes=actual.optJSONArray("nodes");if(nodes==null){clearPending();clearRotation();targetVisible=false;return;}
        boolean target=targetCurrent(actual);
        if(seenTarget&&!target&&backRequested){checks.put("back","passed");observed.put("back",facts("actionAccepted",true,"originalTargetPreviouslySeen",true,"targetAbsent",true));backRequested=false;}
        targetVisible=target;clickNodes.clear();inputNodes.clear();scrollNodes.clear();rotateNodes.clear();if(!target){clearPending();clearRotation();return;}
        seenTarget=true;checks.put("tree","passed");observed.put("tree",facts("originalTargetMarker",true,"originalPracticeSession",true,"nodeCount",nodes.length()));
        if(display>0&&!actual.optString("sessionId").isEmpty()){checks.put("display","passed");observed.put("display",facts("originalBackgroundDisplay",true,"sessionPresent",true));}
        Set<String> rows=new LinkedHashSet<>();Integer count=null,tick=null;String input=null;
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n==null||n.optBoolean("password")||!n.optBoolean("visible",true))continue;
            String text=n.optString("text"),description=n.optString("description"),id=n.optString("id",n.optString("nodeId"));
            if(description.equals("PW_DIAG_CLICK_COUNT")&&!n.optBoolean("editable")&&text.startsWith("PW_DIAG_CLICK_COUNT:"))count=integer(text,"PW_DIAG_CLICK_COUNT:");
            if(description.equals("PW_DIAG_TICK")&&!n.optBoolean("editable")&&text.startsWith("PW_DIAG_TICK:"))tick=integer(text,"PW_DIAG_TICK:");
            if(text.startsWith("PW_DIAG_ROW_"))rows.add(text);
            if(description.equals("PW_DIAG_CLICK"))clickNodes.add(id);
            if(description.equals("PW_DIAG_INPUT")){inputNodes.add(id);input=text;}
            if(description.equals("PW_DIAG_LIST"))scrollNodes.add(id);
            if(description.equals(PhonePracticeContract.ROTATE)&&n.optBoolean("enabled",true))rotateNodes.add(id);
        }
        String after=actual.optString("observationId");boolean fresh=!after.isBlank()&&!after.equals(actionObservation);
        PhonePracticeContract.Layout layout=PhonePracticeContract.layout(actual);
        if(rotationBefore!=null&&!after.isBlank()&&!after.equals(rotationActionObservation)&&rotationLayout(layout)){
            if(rotationAfter==null)reasons.put("rotation","rotation_frame_and_input_pending");
            rotationAfter=layout;recordRotation();
        }
        if(fresh&&pendingClick!=null&&count!=null&&count>pendingClick){checks.put("click","passed");observed.put("click",facts("actionAccepted",true,"beforeCount",pendingClick,"afterCount",count,"freshObservation",true));
            if(pendingRotationClick&&sameLayout(layout,rotationAfter))rotationClick=true;pendingClick=null;}
        if(fresh&&pendingText!=null&&pendingText.equals(input)){String id=pendingText.equals(PhonePracticeContract.ASCII)?"ascii_input":"chinese_input";checks.put(id,"passed");observed.put(id,facts("actionAccepted",true,"exactReadback",true,"freshObservation",true));pendingText=null;}
        if(fresh&&pendingRows!=null&&!rows.isEmpty()&&!pendingRows.equals(rows)){checks.put("scroll","passed");observed.put("scroll",facts("actionAccepted",true,"beforeVisibleRows",pendingRows.size(),"afterVisibleRows",rows.size(),"rowSetChanged",true,"freshObservation",true));pendingRows=null;}
        if(fresh)clearPending();
        lastClicks=count;lastLayout=layout;visibleRows=rows;observationId=after;
        if(tick!=null){long at=actual.optLong("observedAtUptimeMs",-1);if(initialTick==null&&at>=0){initialTick=tick;initialTickAt=at;}else if(initialTick!=null&&at>=initialTickAt+3000&&tick>=initialTick+3){checks.put("live_updates","passed");observed.put("live_updates",facts("elapsedMs",at-initialTickAt,"tickIncrease",tick-initialTick));}}
        completeRotation();
        landing(actual);
    }
    private void landing(JSONObject actual){JSONObject landing=actual.optJSONObject("displayLanding");if(display>0&&landing!=null&&"owned_display_observed".equals(landing.optString("status"))){checks.put("main_screen_isolation","passed");observed.put("main_screen_isolation",facts("nativeStatus","owned_display_observed"));}}
    public synchronized void nativeFacts(JSONObject actual){if(actual==null)return;if(display>0&&actual.optBoolean("helperAuthenticated")){checks.put("helper","passed");reasons.put("helper","authenticated_helper_connected");observed.put("helper",facts("authenticated",true));}if(display>0&&"available".equals(actual.optString("imeIsolation"))){checks.put("ime_isolation","passed");observed.put("ime_isolation",facts("nativeStatus","available"));}else if(display>0&&"unavailable".equals(actual.optString("imeIsolation"))){checks.put("ime_isolation","failed");reasons.put("ime_isolation","isolation_unavailable");observed.put("ime_isolation",facts("nativeStatus","unavailable"));}}
    public synchronized void frame(JSONObject actual){String state=actual==null?"unknown":actual.optString("state"),reason=actual==null?"frame_result_unavailable":actual.optString("reason");if(java.util.Set.of("passed","failed","unknown").contains(state)){checks.put("frame",state);reasons.put("frame",knownReason(reason)?reason:"frame_result_unavailable");observed.put("frame",facts("decodedWidth",actual==null?0:actual.optInt("width"),"decodedHeight",actual==null?0:actual.optInt("height"),"localOnly",true));}}
    /** The adapter supplies the same original pixels/tree already checked by PhonePracticeFrame; never model text. */
    public synchronized void frame(JSONObject frame,JSONObject originalObservation)throws Exception {
        frame(frame);if(rotationBefore==null||rotationAfter==null||rotationInvalid)return;
        if(!targetCurrent(originalObservation)){clearRotation();return;}
        PhonePracticeContract.Layout layout=PhonePracticeContract.layout(originalObservation);
        if(!sameLayout(layout,rotationAfter)||originalObservation.optString("observationId").isBlank()||originalObservation.optString("observationId").equals(rotationActionObservation))return;
        rotationFrame=false;
        if(frame!=null&&"passed".equals(frame.optString("state"))){
            int width=frame.optInt("width"),height=frame.optInt("height");
            rotationFrame=width==layout.width()&&height==layout.height()&&width==originalObservation.optInt("screenWidth")&&height==originalObservation.optInt("screenHeight");
            if(!rotationFrame){checks.put("rotation","failed");reasons.put("rotation","rotation_frame_geometry_mismatch");}
        }else {checks.put("rotation","failed");reasons.put("rotation","rotation_frame_unavailable");}
        recordRotation();completeRotation();
    }
    public synchronized boolean targetCurrent(JSONObject actual)throws Exception {
        if(practiceSession.isBlank()||actual==null||!actual.optBoolean("ok")||actual.optInt("displayId",-1)!=display||!packageName.equals(actual.optString("packageName")))return false;
        JSONArray nodes=actual.optJSONArray("nodes");if(nodes==null)return false;boolean marker=false,session=false;
        for(int i=0;i<nodes.length();i++){JSONObject node=nodes.getJSONObject(i);if(node.optBoolean("password"))continue;String text=node.optString("text");marker|=text.contains(PhonePracticeContract.MARKER);session|=java.util.Arrays.asList(text.split("\\n")).contains("PW_DIAG_SESSION:"+practiceSession);}
        return marker&&session;
    }
    public synchronized JSONObject result()throws Exception {
        JSONArray rows=new JSONArray();boolean all=true;int applicable=0,passed=0;
        for(var entry:checks.entrySet()){String reason=reasons.get(entry.getKey());boolean applies=!"background_not_requested".equals(reason);if(applies){applicable++;if("passed".equals(entry.getValue()))passed++;else all=false;}
            if("passed".equals(entry.getValue())&&reason.equals("not_observed"))reason="actual_native_observation";
            JSONObject facts=observed.get(entry.getKey());
            rows.put(new JSONObject().put("id",entry.getKey()).put("state",entry.getValue()).put("applicable",applies).put("reasonCode",reason)
                    .put("contractVersion",PhonePracticeContract.VERSION).put("expected",PhonePracticeContract.expected(entry.getKey()))
                    .put("observed",facts==null?new JSONObject().put("status","not_observed"):new JSONObject(facts.toString())));}
        return new JSONObject().put("ok",true).put("status",all&&applicable>0?"passed":"partial").put("checks",rows).put("applicableChecks",applicable).put("passedChecks",passed)
                .put("evidenceOrigin","original_run_native_observations").put("contractVersion",PhonePracticeContract.VERSION).put("deviceVerified",false).put("containsPrivateContent",false);
    }
    static boolean knownReason(String reason){return REASONS.contains(reason);}
    private void clearPending(){pendingClick=null;pendingText=null;pendingRows=null;pendingRotationClick=false;actionObservation="";backRequested=false;}
    private void clearRotation(){lastLayout=null;if(rotationBefore!=null&&!"passed".equals(checks.get("rotation"))){rotationInvalid=true;rotationAfter=null;rotationClick=false;rotationFrame=false;reasons.put("rotation","rotation_target_lost");}}
    private boolean rotationLayout(PhonePracticeContract.Layout layout){int expected=rotationBefore.orientation()==1?2:1;return !rotationInvalid&&layout!=null&&layout.request()==rotationBefore.request()+1&&layout.requestedOrientation()==expected&&layout.oriented(expected);}
    private static boolean sameLayout(PhonePracticeContract.Layout left,PhonePracticeContract.Layout right){return left!=null&&left.equals(right);}
    private void recordRotation(){if(rotationBefore!=null)observed.put("rotation",facts("actionAccepted",true,"before",layoutFacts(rotationBefore),"after",layoutFacts(rotationAfter),"frameGeometryMatched",rotationFrame,"postRotationClickReadback",rotationClick));}
    private void completeRotation(){recordRotation();if(!rotationInvalid&&rotationAfter!=null&&rotationFrame&&rotationClick){checks.put("rotation","passed");reasons.put("rotation","rotation_observed_with_frame_and_input");}}
    private static Object layoutFacts(PhonePracticeContract.Layout layout){try{return layout==null?JSONObject.NULL:layout.json();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static JSONObject facts(Object... pairs){try{JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static Integer integer(String text,String prefix){try{return Integer.valueOf(text.substring(prefix.length()).trim());}catch(Exception invalid){return null;}}
}
