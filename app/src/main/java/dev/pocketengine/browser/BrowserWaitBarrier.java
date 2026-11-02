package dev.pocketengine.browser;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A conjunction/disjunction of bounded predicates on already presented regions. Never a retry of an action. */
public final class BrowserWaitBarrier {
    public static final int MAX_TARGETS=4,MAX_ROUNDS=32;
    private BrowserWaitBarrier() { }
    public static JSONObject schema()throws Exception {
        return new JSONObject().put("type","object").put("additionalProperties",false).put("required",new JSONArray().put("targets"))
                .put("properties",new JSONObject().put("targets",new JSONObject().put("type","array").put("minItems",1).put("maxItems",MAX_TARGETS).put("items",BrowserTargetCondition.schema()))
                        .put("mode",new JSONObject().put("type","string").put("enum",new JSONArray().put("all").put("any"))).put("stableMs",new JSONObject().put("type","integer").put("minimum",0).put("maximum",1000)));
    }
    public static void validate(JSONObject barrier)throws Exception {
        for(var keys=barrier.keys();keys.hasNext();)if(!Set.of("targets","mode","stableMs").contains(keys.next()))throw new IllegalArgumentException("网页屏障含额外字段");
        JSONArray targets=barrier.getJSONArray("targets");if(targets.length()<1||targets.length()>MAX_TARGETS||!Set.of("all","any").contains(barrier.optString("mode","all")))throw new IllegalArgumentException("网页屏障需要1至4个明确区域条件");
        for(int i=0;i<targets.length();i++){BrowserTargetCondition.validate(targets.getJSONObject(i));if(targets.getJSONObject(i).has("frameRef"))throw new IllegalArgumentException("组合屏障首版仅支持原主文档；子frame请使用原单目标wait");}
        if(barrier.has("stableMs")&&(!(barrier.get("stableMs") instanceof Number)||barrier.getDouble("stableMs")!=barrier.getInt("stableMs")||barrier.getInt("stableMs")<0||barrier.getInt("stableMs")>1000))throw new IllegalArgumentException("网页屏障稳定窗口无效");
    }
    static final class Tracker {
        final JSONObject request;final int stable;int rounds,probes;long since=-1;String signature="";JSONArray last=new JSONArray();
        Tracker(JSONObject request)throws Exception{validate(request);this.request=new JSONObject(request.toString());int requested=request.optInt("stableMs",0);for(int i=0;i<request.getJSONArray("targets").length();i++)requested=Math.max(requested,request.getJSONArray("targets").getJSONObject(i).optInt("stableMs",0));stable=requested;}
        boolean sample(JSONArray actual,long elapsed)throws Exception {
            rounds++;probes+=actual.length();last=new JSONArray(actual.toString());boolean all=true,any=false;StringBuilder next=new StringBuilder();
            for(int i=0;i<actual.length();i++){JSONObject value=actual.getJSONObject(i);Object matched=value.opt("matched");if(!(matched instanceof Boolean)){since=-1;return false;}boolean met=(Boolean)matched;all&=met;any|=met;JSONObject state=value.optJSONObject("actual");next.append(state==null?"":state.toString()).append('|').append(met);}
            boolean met=request.optString("mode","all").equals("all")?all:any;
            if(!met){since=-1;signature=next.toString();return false;}if(since<0||!signature.equals(next.toString())){since=elapsed;signature=next.toString();}return elapsed-since>=stable;
        }
        JSONObject receipt(boolean matched,long elapsed,int timeout)throws Exception {
            JSONArray evidence=new JSONArray();for(int i=0;i<last.length();i++){JSONObject row=last.getJSONObject(i),safe=new JSONObject();for(String key:new String[]{"status","code","matched","regionRef","documentId","frameId","evidenceSha256"})if(row.has(key))safe.put(key,row.get(key));evidence.put(safe);}
            return new JSONObject().put("status",matched?"condition_observed":rounds==0?"unavailable":"condition_timeout").put("accepted",true).put("verified",false).put("matched",rounds==0?JSONObject.NULL:matched).put("conditionMet",rounds==0?JSONObject.NULL:matched).put("condition","barrier").put("mode",request.optString("mode","all")).put("samples",rounds).put("probes",probes).put("elapsedMs",elapsed).put("timeoutMs",timeout).put("stableForMs",since<0?0:Math.max(0,elapsed-since)).put("requiredStableMs",stable).put("conditions",evidence).put("scope","already_presented_regions_only").put("entirePageStable",false).put("networkIdleProven",false).put("automaticReplayAllowed",false).put("readOnlyEvidence",true);
        }
    }
}
