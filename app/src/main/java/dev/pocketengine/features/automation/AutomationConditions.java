package dev.pocketengine.features.automation;

import org.json.JSONArray;
import org.json.JSONObject;

/** Fresh local observations, never execution authority or a second scheduler. */
public final class AutomationConditions {
    public static final long RECHECK_MILLIS=15*60_000L,MAX_AGE_MILLIS=30_000L;
    public interface Source {Snapshot read(long now)throws Exception;}
    public static final class Snapshot {
        public final long observedAt;
        public final String networkState,networkReason,batteryReason;
        public final Boolean charging;
        public final Integer batteryPercent;
        public Snapshot(long observedAt,String networkState,String networkReason,Boolean charging,Integer batteryPercent,String batteryReason){
            if(!networkState.matches("validated|unvalidated|disconnected|unavailable"))throw new IllegalArgumentException("网络条件状态无效");
            if(batteryPercent!=null&&(batteryPercent<0||batteryPercent>100))throw new IllegalArgumentException("电量条件无效");
            this.observedAt=observedAt;this.networkState=networkState;this.networkReason=reason(networkReason);this.charging=charging;
            this.batteryPercent=batteryPercent;this.batteryReason=reason(batteryReason);
        }
        public JSONObject json()throws Exception {
            return new JSONObject().put("observedAt",observedAt).put("networkState",networkState).put("networkReason",networkReason)
                    .put("charging",charging==null?JSONObject.NULL:charging).put("batteryPercent",batteryPercent==null?JSONObject.NULL:batteryPercent)
                    .put("batteryReason",batteryReason).put("executionAuthorized",false);
        }
    }
    private AutomationConditions(){}
    public static Snapshot unavailable(long now){return new Snapshot(now,"unavailable","source_unavailable",null,null,"source_unavailable");}
    public static Snapshot read(Source source,long now){
        if(source==null)return unavailable(now);
        try{Snapshot value=source.read(now);
            if(value==null)return unavailable(now);
            if(value!=null&&value.observedAt>0&&value.observedAt<=now&&now-value.observedAt<=MAX_AGE_MILLIS)return value;
            return new Snapshot(now,"unavailable","stale_observation",null,null,"stale_observation");
        }catch(Exception failed){return unavailable(now);}
    }
    static JSONObject requirements(JSONObject input)throws Exception {
        JSONObject value=new JSONObject();
        for(String key:new String[]{"requiresNetwork","requiresCharging"}){
            Object raw=input.opt(key);if(input.has(key)&&!(raw instanceof Boolean))throw new IllegalArgumentException(key+"必须为布尔值");
            value.put(key,Boolean.TRUE.equals(raw));
        }
        value.put("minBatteryPercent",input.has("minBatteryPercent")?AutomationPolicy.integer(input,"minBatteryPercent",0,100):0);
        return value;
    }
    static void appendRequirements(JSONObject target,JSONObject input)throws Exception {
        JSONObject values=requirements(input);for(String key:new String[]{"requiresNetwork","requiresCharging","minBatteryPercent"})target.put(key,values.get(key));
    }
    public static JSONObject evaluate(JSONObject policy,Source source,long now)throws Exception {
        JSONObject wanted=requirements(policy);boolean network=wanted.getBoolean("requiresNetwork"),charging=wanted.getBoolean("requiresCharging");int minimum=wanted.getInt("minBatteryPercent");
        JSONArray blockers=new JSONArray();Snapshot actual=null;
        if(network||charging||minimum>0){actual=read(source,now);
            if(network&&!actual.networkState.equals("validated"))blockers.put(actual.networkState.equals("unavailable")?"network_unavailable":actual.networkState.equals("unvalidated")?"network_not_validated":"network_required");
            if(charging&&!Boolean.TRUE.equals(actual.charging))blockers.put(actual.charging==null?"charging_unavailable":"charging_required");
            if(minimum>0&&(actual.batteryPercent==null||actual.batteryPercent<minimum))blockers.put(actual.batteryPercent==null?"battery_unavailable":"battery_below_minimum");
        }
        boolean allowed=blockers.length()==0;
        return new JSONObject().put("allowed",allowed).put("requirements",wanted).put("blockers",blockers)
                .put("snapshot",actual==null?JSONObject.NULL:actual.json()).put("nextCheckAt",allowed?JSONObject.NULL:later(now))
                .put("executionAuthorized",false);
    }
    static long later(long now){return now>AutomationPolicy.MAX_TIME-RECHECK_MILLIS?AutomationPolicy.MAX_TIME:now+RECHECK_MILLIS;}
    private static String reason(String value){return value!=null&&value.matches("[a-z_]{0,80}")?value:"source_unavailable";}
}
