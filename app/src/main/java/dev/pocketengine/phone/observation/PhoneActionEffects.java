package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneUiSignals;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only post-action evidence. It cannot dispatch, retry, prepare an operation or declare business success. */
public final class PhoneActionEffects {
    public static final int MAX_SAMPLES=16;
    private static final int MAX_DIFF_NODES=2048;
    public static final long WAIT_MS=2200,STABLE_MS=250,EVENT_QUIET_MS=400,POLL_MS=200;
    private PhoneActionEffects() { }
    @FunctionalInterface public interface Reader {JSONObject read()throws Exception;}
    @FunctionalInterface public interface VisualReader {JSONObject read()throws Exception;}
    public interface Timing {long now();void pause(long milliseconds,RunControl control,long revision,PhoneUiSignals feed,PhoneUiSignals.Stamp stamp,Set<Integer> windows)throws Exception;}
    public static final Timing REAL=new Timing(){
        public long now(){return System.nanoTime()/1_000_000L;}
        public void pause(long ms,RunControl control,long revision,PhoneUiSignals feed,PhoneUiSignals.Stamp stamp,Set<Integer> windows)throws Exception {
            if(feed!=null){feed.awaitChange(stamp,windows,Math.min(250,ms),control,revision);return;}long until=System.nanoTime()+ms*1_000_000L;
            while(System.nanoTime()<until){control.check();if(control.isPaused()||control.pauseRevision()!=revision)return;Thread.sleep(Math.max(1,Math.min(50,(until-System.nanoTime())/1_000_000L)));}
        }
    };
    public static final class Result {public final JSONObject evidence,observation;Result(JSONObject evidence,JSONObject observation){this.evidence=evidence;this.observation=observation;}}
    /** Native same-node readback only. Caller owns the refreshed AccessibilityNodeInfo and privacy/currentness checks. */
    public static JSONObject inputReadback(CharSequence actual,String expected,boolean secret)throws Exception {
        if(secret)return new JSONObject().put("status","owner_private_not_echoed").put("exactMatch",JSONObject.NULL).put("text",JSONObject.NULL).put("verifiedBusinessSuccess",false);
        if(actual==null)actual="";boolean same=expected!=null&&actual.length()==expected.length();if(same)for(int i=0;i<actual.length();i++)if(actual.charAt(i)!=expected.charAt(i)){same=false;break;}
        int end=Math.min(actual.length(),512);if(end>0&&end<actual.length()&&Character.isHighSurrogate(actual.charAt(end-1))&&Character.isLowSurrogate(actual.charAt(end)))end--;
        return new JSONObject().put("status",same?"exact_match":"mismatch").put("exactMatch",same).put("text",actual.subSequence(0,end).toString()).put("textChars",actual.length()).put("textTruncated",end<actual.length())
                .put("comparison","exact_no_whitespace_or_substring_fallback").put("verifiedBusinessSuccess",false);
    }
    public static JSONObject unavailableReadback(String reason)throws Exception{return new JSONObject().put("status","unavailable").put("reason",reason).put("exactMatch",JSONObject.NULL).put("text",JSONObject.NULL).put("verifiedBusinessSuccess",false);}
    public static Result await(JSONObject before,Integer targetWindow,Reader reader,RunControl control,long revision,PhoneUiSignals feed,Timing timing)throws Exception {
        return await(before,targetWindow,reader,control,revision,feed,timing,null);
    }
    public static Result await(JSONObject before,Integer targetWindow,Reader reader,RunControl control,long revision,PhoneUiSignals feed,Timing timing,VisualReader visualReader)throws Exception {
        long started=timing.now(),deadline=started+WAIT_MS,stableSince=-1,lastSignalChange=started;
        String previous=null;JSONObject latest=null;int samples=0;String state="deadline";
        if(!readable(before)&&visualReader==null)return new Result(summary("before_tree_unavailable",0,0,false,null),null);
        String beforeSignature=null;Set<Integer> windows=new LinkedHashSet<>();
        try{
            if(readable(before))beforeSignature=signature(before,targetWindow);
            if(targetWindow!=null)windows.add(targetWindow);
            else if(readable(before))for(JSONObject node:nodes(before,null).values())windows.add(node.getInt("windowId"));
        }catch(Exception malformed){if(visualReader==null)return new Result(summary("before_window_path_unavailable",0,0,false,null),null);beforeSignature=null;}
        PhoneUiSignals.Stamp stamp=feed==null?null:feed.snapshot(windows);
        boolean sawChange=false,visualChanged=false;JSONObject visual=null;
        String visualKey=null;long visualSince=-1,nextVisualRead=started,lastVisualRead=0,lastSourceFrame=0;
        int visualSamples=0,sourceFrames=0;boolean visualStable=false,treeStable=false;
        while(samples<MAX_SAMPLES){control.check();if(control.isPaused()||control.pauseRevision()!=revision){state="paused_or_changed";break;}
            if(stamp!=null&&stamp.closed){state="service_signal_closed";break;}
            latest=reader.read();samples++;
            control.check();if(control.isPaused()||control.pauseRevision()!=revision){state="paused_or_changed";break;}
            if(latest==null||!latest.optBoolean("ok")||!readable(latest)&&visualReader==null){state="observation_unavailable";break;}
            String signature=readable(latest)?signature(latest,targetWindow):null;long now=timing.now();
            if(visualReader!=null&&now>=nextVisualRead){
                JSONObject sample=visualReader.read();now=timing.now();
                control.check();if(control.isPaused()||control.pauseRevision()!=revision){state="paused_or_changed";break;}
                nextVisualRead=now+Math.max(POLL_MS,sample==null?POLL_MS:Math.min(1000,sample.optLong("nextSampleAfterMs",POLL_MS)));
                if(sample!=null&&"deferred".equals(sample.optString("status"))){
                    if(visual==null)visual=sample;
                }else{
                    visual=sample;
                    if(visualReadable(sample,before,revision)){
                        String key=sample.getString("samplingScope")+":"+sample.getLong("stabilityRevision");
                        long observed=sample.getLong("observedAtNanos"),source=sample.getLong("frameTimestampNanos");
                        if(observed>lastVisualRead){
                            visualSamples++;lastVisualRead=observed;
                            if(source!=lastSourceFrame){sourceFrames++;lastSourceFrame=source;}
                            if(!key.equals(visualKey)||visualSince<0){if(visualKey!=null&&!key.equals(visualKey))visualChanged=true;visualSince=now;}
                            visualKey=key;
                            JSONObject original=before==null?null:before.optJSONObject("visualEvidence");
                            if(original!=null&&sample.getString("samplingScope").equals(original.optString("samplingScope"))
                                    &&!sample.getString("fingerprint").equals(original.optString("fingerprint")))visualChanged=true;
                            // A compositor may publish only on damage. Two live producer reads can
                            // observe its same final frame; they remain one distinct source frame.
                            visualStable=visualSamples>=2&&now-visualSince>=STABLE_MS;
                        }
                    }else{visualSince=-1;visualStable=false;}
                }
                // Accessibility capture briefly hides the owner overlay. Read the tree again
                // after that capture; the returned target references must describe the latest UI.
                if(sample!=null&&"available".equals(sample.optString("status"))&&"accessibility_screenshot".equals(sample.optString("source"))){
                    latest=reader.read();samples++;control.check();
                    if(control.isPaused()||control.pauseRevision()!=revision){state="paused_or_changed";break;}
                    if(latest==null||!latest.optBoolean("ok")){state="observation_unavailable";break;}
                    signature=readable(latest)?signature(latest,targetWindow):null;now=timing.now();
                }
            }
            // New dialogs/destination windows belong to the effect's observed scope too.
            JSONArray visibleWindows=latest.optJSONArray("windows");
            if(visibleWindows!=null)for(int i=0;i<visibleWindows.length()&&i<32;i++){
                JSONObject window=visibleWindows.optJSONObject(i);if(window!=null&&window.optInt("windowId",-1)>=0)windows.add(window.getInt("windowId"));
            }
            if(feed!=null){
                PhoneUiSignals.Stamp current=feed.snapshot(windows);
                // Signal clocks can have a different origin. Measure quiet time on this wait's
                // monotonic clock after seeing a generation change, including changes during a read.
                if(!current.same(stamp))lastSignalChange=now;
                stamp=current;
                if(stamp.closed){state="service_signal_closed";break;}
            }
            boolean changed=signature!=null&&beforeSignature!=null&&!signature.equals(beforeSignature);sawChange|=changed;
            if(signature==null||latest.optBoolean("changedDuringRead"))stableSince=-1;
            else if(!signature.equals(previous)||stableSince<0)stableSince=now;
            previous=signature;
            treeStable=stableSince>=0&&now-stableSince>=STABLE_MS;
            boolean quiet=feed==null||now-lastSignalChange>=EVENT_QUIET_MS;
            // An unchanged quiet tree may only mean the app has not reacted yet. Keep the
            // bounded observation window; never turn this absence of evidence into a retry.
            if(visualReader==null&&changed&&treeStable&&quiet){state="stable_observed_scope";break;}
            if(visualReader!=null&&visualStable&&(signature==null||treeStable)&&quiet){state=signature==null?"stable_visual_scope":"stable_visual_and_tree_scope";break;}
            if(now>=deadline)break;
            timing.pause(Math.min(POLL_MS,deadline-now),control,revision,feed,stamp,windows);
            long minimumWake=Math.min(deadline,now+150);if(timing.now()<minimumWake)timing.pause(minimumWake-timing.now(),control,revision,null,null,windows);
        }
        if(samples>=MAX_SAMPLES&&state.equals("deadline")&&timing.now()<deadline)state="sample_limit";
        JSONObject difference=readable(before)&&readable(latest)?diff(before,latest,targetWindow):null;
        long finished=timing.now();
        JSONObject evidence=summary(state,samples,Math.max(0,finished-started),feed!=null,difference)
                .put("changeObservedDuringWait",sawChange).put("treeStableForMs",stableSince<0?0:Math.max(0,finished-stableSince))
                .put("eventQuietForMs",feed==null?JSONObject.NULL:Math.max(0,finished-lastSignalChange))
                .put("scope",targetWindow==null?"selected_display":"target_window_and_window_stack");if(targetWindow!=null)evidence.put("windowId",targetWindow);
        if(visualReader!=null)evidence.put("metadataOnly",false).put("imagesReturned",false).put("localPixelSampling",true)
                .put("visual",new JSONObject().put("status",visual==null?"not_sampled":visual.optString("status","unavailable"))
                        .put("samples",visualSamples).put("distinctSourceFrames",sourceFrames).put("stable",visualStable)
                        .put("stableForMs",visualSince<0?0:Math.max(0,finished-visualSince)).put("changeObservedDuringWait",visualChanged)
                        .put("latest",visual==null?JSONObject.NULL:visual).put("targetEvidence",false).put("verifiedBusinessSuccess",false));
        return new Result(evidence,latest);
    }
    private static boolean visualReadable(JSONObject value,JSONObject before,long revision){
        if(value==null||!"available".equals(value.optString("status"))||!value.optBoolean("sourceFrameFresh")||value.optBoolean("framePending")
                ||value.optLong("frameTimestampNanos",0)<=0||value.optLong("observedAtNanos",0)<value.optLong("frameTimestampNanos",0)
                ||value.optLong("pauseRevision",-1)!=revision||value.optLong("stabilityRevision",0)<=0
                ||value.optString("samplingScope").isEmpty()||value.optString("fingerprint").isEmpty())return false;
        return before==null||(!before.has("displayId")||before.optInt("displayId",-1)==value.optInt("displayId",-2))
                &&(!before.has("sessionId")||before.optString("sessionId").equals(value.optString("sessionId")));
    }
    private static JSONObject summary(String state,int samples,long elapsed,boolean events,JSONObject diff)throws Exception {
        JSONObject out=new JSONObject().put("stability",state).put("samples",samples).put("elapsedMs",elapsed).put("eventHintsUsed",events).put("metadataOnly",true).put("verifiedBusinessSuccess",false).put("automaticReplayAllowed",false);
        if(diff!=null)out.put("observed",diff);return out;
    }
    static boolean readable(JSONObject observation){return observation!=null&&observation.optBoolean("ok")&&observation.optJSONArray("nodes")!=null&&observation.optJSONArray("nodes").length()>0&&observation.optJSONObject("observationSources")!=null&&observation.optJSONObject("observationSources").optBoolean("treeAvailable");}
    private static Map<String,JSONObject> nodes(JSONObject observation,Integer window)throws Exception {
        Map<String,JSONObject> out=new LinkedHashMap<>();JSONArray all=observation.getJSONArray("nodes");for(int i=0;i<all.length()&&i<MAX_DIFF_NODES;i++){JSONObject n=all.getJSONObject(i);if(!(n.opt("windowId") instanceof Number)||n.optJSONArray("path")==null)throw new IllegalArgumentException("missing native window/path evidence");if(window!=null&&n.getInt("windowId")!=window)continue;String key=n.getInt("windowId")+":"+n.getJSONArray("path").toString();if(out.put(key,n)!=null)throw new IllegalArgumentException("duplicate native window/path evidence");}return out;
    }
    static String signature(JSONObject observation,Integer window)throws Exception {
        JSONArray rows=new JSONArray();for(Map.Entry<String,JSONObject> e:nodes(observation,window).entrySet()){JSONObject n=e.getValue();JSONObject row=new JSONObject().put("path",e.getKey());for(String key:new String[]{"identityFingerprint","fingerprint","resourceId","role","text","description","checked","selected","focused","enabled","visible","bounds","password"})if(n.has(key))row.put(key,n.get(key));rows.put(row);}return new JSONObject().put("package",observation.optString("packageName")).put("windows",windowStack(observation)).put("nodes",rows).toString();
    }
    private static JSONArray windowStack(JSONObject observation)throws Exception {JSONArray out=new JSONArray(),all=observation.optJSONArray("windows");if(all!=null)for(int i=0;i<all.length()&&i<32;i++){JSONObject w=all.getJSONObject(i);JSONObject row=new JSONObject();for(String k:new String[]{"windowId","layer","type","active","focused","bounds"})if(w.has(k))row.put(k,w.get(k));out.put(row);}return out;}
    static JSONObject diff(JSONObject before,JSONObject after,Integer window)throws Exception {
        Map<String,JSONObject> a=nodes(before,window),b=nodes(after,window);int added=0,removed=0,text=0,state=0,moved=0,identity=0,opaque=0;boolean focus=false,loading=false;
        for(Map.Entry<String,JSONObject> e:b.entrySet()){JSONObject n=e.getValue(),old=a.get(e.getKey());if(n.optString("role").toLowerCase(Locale.ROOT).contains("progress")&&n.optBoolean("visible",true))loading=true;
            if(old==null){added++;continue;}if(!old.optString("resourceId").equals(n.optString("resourceId"))||!old.optString("role").equals(n.optString("role"))){identity++;continue;}
            if(!old.optBoolean("password")&&!n.optBoolean("password")&&(!old.optString("text").equals(n.optString("text"))||!old.optString("description").equals(n.optString("description"))))text++;
            if(old.optBoolean("focused")!=n.optBoolean("focused"))focus=true;
            for(String key:new String[]{"checked","selected","enabled","visible"})if(!String.valueOf(old.opt(key)).equals(String.valueOf(n.opt(key)))){state++;break;}
            if(!String.valueOf(old.opt("bounds")).equals(String.valueOf(n.opt("bounds"))))moved++;
            if(!old.optString("identityFingerprint").equals(n.optString("identityFingerprint")))opaque++;
        }
        for(String key:a.keySet())if(!b.containsKey(key))removed++;
        boolean partial=before.optBoolean("truncated")||after.optBoolean("truncated")||before.getJSONArray("nodes").length()>MAX_DIFF_NODES||after.getJSONArray("nodes").length()>MAX_DIFF_NODES,app=!before.optString("packageName").equals(after.optString("packageName")),stack=!windowStack(before).toString().equals(windowStack(after).toString());
        boolean changed=added+removed+text+state+moved+identity+opaque>0||focus||app||stack;String status=changed?"change_observed":partial?"unknown_partial_tree":"no_change_in_readable_tree_not_no_effect";
        return new JSONObject().put("status",status).put("added",added).put("removed",removed).put("textOrLabelChanged",text).put("stateChanged",state).put("moved",moved).put("identityChangedAtPath",identity).put("nativeIdentityFingerprintChanged",opaque).put("focusChanged",focus).put("applicationChanged",app).put("windowStackChanged",stack).put("possibleLoadingIndicator",loading).put("partialTree",partial).put("textMayBeClipped",before.optJSONObject("treeCoverage")!=null&&before.optJSONObject("treeCoverage").optBoolean("bodyTruncated")||after.optJSONObject("treeCoverage")!=null&&after.optJSONObject("treeCoverage").optBoolean("bodyTruncated"))
                .put("beforeObservationId",before.optString("observationId")).put("afterObservationId",after.optString("observationId")).put("diffNodeLimit",MAX_DIFF_NODES).put("samePathDoesNotProveSameBusinessObject",true).put("verifiedBusinessSuccess",false);
    }
}
