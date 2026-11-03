package dev.pocketengine.browser;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded read conditions inside the original wait operation, never an action/retry scheduler. */
public final class BrowserWaitBarrier {
    public static final int MAX_PROBES=32;
    private BrowserWaitBarrier() { }
    public static JSONObject schema()throws Exception {
        return new JSONObject().put("type","object").put("additionalProperties",false)
                .put("properties",new JSONObject()
                        .put("kind",new JSONObject().put("type","string").put("enum",new JSONArray().put("dom").put("network").put("dom_network")))
                        .put("quietMs",new JSONObject().put("type","integer").put("minimum",100).put("maximum",2000))
                        .put("captureId",new JSONObject().put("type","string").put("maxLength",36)))
                .put("required",new JSONArray().put("kind"));
    }
    public static void validate(JSONObject value)throws Exception {
        if(value==null)throw new IllegalArgumentException("等待屏障需要明确kind");
        for(java.util.Iterator<String> keys=value.keys();keys.hasNext();)if(!Set.of("kind","quietMs","captureId").contains(keys.next()))throw new IllegalArgumentException("等待屏障含额外字段");
        if(!(value.opt("kind") instanceof String)||!Set.of("dom","network","dom_network").contains(value.getString("kind")))throw new IllegalArgumentException("等待屏障类型无效");
        if(value.has("quietMs")){Object ms=value.get("quietMs");if(!(ms instanceof Number)||((Number)ms).doubleValue()!=value.optInt("quietMs")||value.getInt("quietMs")<100||value.getInt("quietMs")>2000)throw new IllegalArgumentException("安静窗口为100至2000毫秒");}
        if(value.getString("kind").equals("dom")){if(value.has("captureId"))throw new IllegalArgumentException("DOM屏障不读取网络采集");}
        else {if(!(value.opt("captureId") instanceof String))throw new IllegalArgumentException("网络屏障需要本页已启用的原生captureId");BrowserTaskSpace.uuid(value.getString("captureId"));}
    }
    public static final class Tracker {
        public final String kind,captureId;public final int quietMs;
        private int samples,backoff=80;private long lastDom=-1,lastNetwork=-1;
        private JSONObject dom,network;private String unavailable="";
        public Tracker(JSONObject value)throws Exception {validate(value);kind=value.getString("kind");captureId=value.optString("captureId");quietMs=value.optInt("quietMs",500);}
        public boolean needsDom(){return !kind.equals("network");}
        public boolean needsNetwork(){return !kind.equals("dom");}
        public boolean exhausted(){return samples>=MAX_PROBES;}
        public boolean unavailable(){return !unavailable.isEmpty();}
        public int delayMs(){return backoff;}
        public boolean accept(JSONObject domProbe,JSONObject networkProbe)throws Exception {
            if(exhausted())throw new IllegalStateException("barrier_probe_budget");samples++;
            boolean met=true,changed=false;
            if(needsDom()){
                if(domProbe==null||!domProbe.optBoolean("available")){unavailable="dom_barrier_unavailable";met=false;dom=new JSONObject().put("available",false);}
                else {
                    long mutations=domProbe.getLong("mutations"),quiet=domProbe.getLong("quietForMs");if(mutations<0||mutations>4096||quiet<0||quiet>6500)throw new SecurityException("invalid_dom_barrier_evidence");
                    dom=new JSONObject().put("available",true).put("mutations",mutations).put("quietForMs",quiet).put("loading",domProbe.getBoolean("loading")).put("scope","main_document_light_dom_mutations");
                    changed|=lastDom!=mutations;lastDom=mutations;met&=!dom.getBoolean("loading")&&quiet>=quietMs;
                }
            }
            if(needsNetwork()){
                if(networkProbe==null||!networkProbe.optBoolean("available")){unavailable="native_network_barrier_unavailable";met=false;network=new JSONObject().put("available",false).put("captureId",captureId);}
                else {
                    long sequence=networkProbe.getLong("sequence"),quiet=networkProbe.getLong("quietForMs");int inflight=networkProbe.getInt("inflight");
                    if(!captureId.equals(networkProbe.getString("captureId"))||networkProbe.optBoolean("coverageLost")||sequence<0||quiet<0||inflight<0||inflight>BrowserNetworkRecords.MAX_ROWS)throw new SecurityException("invalid_network_barrier_evidence");
                    network=new JSONObject().put("available",true).put("captureId",captureId).put("inflight",inflight).put("quietForMs",quiet).put("sequence",sequence)
                            .put("scope","authenticated_main_frame_requests_started_after_capture").put("preexistingRequestsKnown",false).put("networkIdleProven",false);
                    changed|=lastNetwork!=sequence;lastNetwork=sequence;met&=inflight==0&&quiet>=quietMs;
                }
            }
            backoff=changed?80:Math.min(400,backoff*3/2);return met&&!unavailable();
        }
        public JSONObject receipt(boolean met,boolean timeout,long elapsed)throws Exception {
            boolean actual=met&&!unavailable()&&samples>0;
            JSONObject evidence=new JSONObject().put("kind",kind).put("quietMs",quietMs).put("samples",samples).put("maxSamples",MAX_PROBES).put("elapsedMs",Math.max(0,elapsed))
                    .put("timeout",timeout).put("probeBudgetExhausted",exhausted()&&!actual).put("dom",dom==null?JSONObject.NULL:dom).put("network",network==null?JSONObject.NULL:network)
                    .put("wholePageStable",false).put("networkIdleProven",false).put("scheduledFutureWorkKnown",false);
            return new JSONObject().put("accepted",!unavailable()).put("verified",false).put("status",actual?"condition_observed":unavailable()?"unavailable":"failed")
                    .put("code",actual?"barrier_condition_observed":unavailable()?unavailable:timeout?"condition_timeout":"barrier_probe_budget")
                    .put("condition","barrier").put("conditionMet",actual).put("barrier",evidence).put("readOnlyEvidence",true).put("automaticReplayAllowed",false).put("businessOutcomeVerified",false)
                    .put("message",actual?"已观察到声明范围的安静窗口；不证明未覆盖frame、预先请求、后续定时任务或服务器业务完成。":"所需安静条件未确认；本次没有点击、重放动作、开启采集或重新发送请求。");
        }
    }
    /** Runs only in the already authenticated main-frame isolated world. No values or text leave it. */
    static String domScript(String key,String nonce){
        return "(()=>{/*__pw_dom_barrier*/const k="+JSONObject.quote(key)+",n="+JSONObject.quote(nonce)+";let s=globalThis[k];const fail=()=>JSON.stringify({available:false});if(s&&s.nonce!==n)return fail();if(!s){if(!document.documentElement||!globalThis.MutationObserver)return fail();s={nonce:n,root:document.documentElement,last:performance.now(),mutations:0,closed:false};s.observer=new MutationObserver(()=>{s.last=performance.now();s.mutations++;if(s.mutations>4096){s.closed=true;s.observer.disconnect()}});s.observer.observe(s.root,{subtree:true,childList:true,attributes:true,characterData:true});s.timer=setTimeout(()=>{s.closed=true;s.observer.disconnect();if(globalThis[k]===s)delete globalThis[k]},6500);globalThis[k]=s;}if(s.closed||s.root!==document.documentElement||!s.root.isConnected)return fail();return JSON.stringify({available:true,mutations:s.mutations,quietForMs:Math.min(6500,Math.max(0,Math.floor(performance.now()-s.last))),loading:document.readyState==='loading'});})()";
    }
    static String cleanupScript(String key,String nonce){return "(()=>{/*__pw_dom_barrier_cleanup*/const k="+JSONObject.quote(key)+",s=globalThis[k];if(s&&s.nonce==="+JSONObject.quote(nonce)+"){s.closed=true;s.observer.disconnect();clearTimeout(s.timer);delete globalThis[k]}})()";}
}
