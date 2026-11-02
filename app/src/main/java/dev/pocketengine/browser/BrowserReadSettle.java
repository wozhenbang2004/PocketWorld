package dev.pocketengine.browser;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local rendered-projection readiness. Neither a network-idle detector nor a page action. */
public final class BrowserReadSettle {
    public static final int POLL_MS=200,MAX_SAMPLES=32;
    private BrowserReadSettle(){}
    public static JSONObject schema()throws Exception {
        return new JSONObject().put("type","object").put("additionalProperties",false).put("properties",new JSONObject()
                .put("timeoutMs",integer(400,6000)).put("stableMs",integer(200,3000)).put("minItems",integer(0,160))
                .put("afterProjectionSha256",new JSONObject().put("type","string").put("maxLength",64)));
    }
    private static JSONObject integer(int min,int max)throws Exception{return new JSONObject().put("type","integer").put("minimum",min).put("maximum",max);}
    private static void fields(JSONObject value,Set<String> names)throws Exception {for(java.util.Iterator<String> i=value.keys();i.hasNext();)if(!names.contains(i.next()))throw new IllegalArgumentException("动态读取含额外字段");}
    private static int number(JSONObject value,String key,int fallback,int min,int max)throws Exception {if(!value.has(key))return fallback;Object n=value.get(key);if(!(n instanceof Number)||((Number)n).doubleValue()!=value.getInt(key)||value.getInt(key)<min||value.getInt(key)>max)throw new IllegalArgumentException("动态读取预算无效");return value.getInt(key);}
    public static void validate(JSONObject request)throws Exception {
        if(!request.has("settle"))return;JSONObject value=request.getJSONObject("settle");fields(value,Set.of("timeoutMs","stableMs","minItems","afterProjectionSha256"));
        int timeout=number(value,"timeoutMs",4000,400,6000),stable=number(value,"stableMs",600,200,3000);number(value,"minItems",1,0,160);
        if(stable>timeout||request.has("cursor")||request.optString("mode").equals("outline"))throw new IllegalArgumentException("稳定读取仅用于明确正文/表格/列表，不能叠加续读或超过总时限");
        if(value.has("afterProjectionSha256")&&(!(value.get("afterProjectionSha256") instanceof String)||!value.getString("afterProjectionSha256").matches("[a-f0-9]{64}")))throw new IllegalArgumentException("翻页后比较须使用原投影摘要，不是动作重放权限");
    }
    public static void validateDynamic(JSONObject d)throws Exception {
        Set<String> names=Set.of("scope","busy","contentItems","observerActive","mutationVersion","pagination","paginationPartial","nodesVisited","networkIdleProven","trust");fields(d,names);if(d.length()!=names.size()||!d.getString("scope").equals("selected_region_and_nearest_container")||!d.getString("trust").equals("untrusted_rendered_dom")||!Boolean.FALSE.equals(d.get("networkIdleProven")))throw new SecurityException("动态观察不能提升来源信任");
        for(String k:new String[]{"busy","observerActive","paginationPartial"})if(!(d.get(k) instanceof Boolean))throw new SecurityException("动态观察状态无效");
        number(d,"contentItems",-1,0,160);number(d,"nodesVisited",-1,0,256);number(d,"mutationVersion",-1,0,1000000000);
        JSONArray rows=d.getJSONArray("pagination");if(rows.length()>8)throw new SecurityException("分页观察超限");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);fields(row,Set.of("kind","label","href","disabled","current"));if(row.length()!=5||!Set.of("next","previous","load_more","current").contains(row.getString("kind"))||row.getString("label").length()>120||!(row.get("disabled") instanceof Boolean)||!(row.get("current") instanceof Boolean))throw new SecurityException("分页控件证据无效");if(!row.isNull("href")){String href=row.getString("href");java.net.URI u=new java.net.URI(href);if(href.length()>2048||u.getUserInfo()!=null||u.getHost()==null||!Set.of("http","https").contains(u.getScheme()))throw new SecurityException("分页地址无效");}}
    }
    public static void validateEvidence(JSONObject s)throws Exception {
        Set<String> names=Set.of("status","conditionMet","samples","elapsedMs","stableForMs","requiredStableMs","timeoutMs","minItems","afterProjectionRequired","projectionChanged","scope","entirePageStable","networkIdleProven");fields(s,names);
        if(s.length()!=names.size()||!Set.of("settled","timeout").contains(s.getString("status"))||!s.getString("scope").equals("bounded_rendered_projection_and_container_mutations")||!Boolean.FALSE.equals(s.get("entirePageStable"))||!Boolean.FALSE.equals(s.get("networkIdleProven")))throw new SecurityException("稳定读取不能声称整页或网络完成");
        for(String k:new String[]{"conditionMet","afterProjectionRequired","projectionChanged"})if(!(s.get(k) instanceof Boolean))throw new SecurityException("稳定读取条件无效");
        number(s,"samples",-1,1,MAX_SAMPLES);number(s,"elapsedMs",-1,0,8000);number(s,"stableForMs",-1,0,8000);int stable=number(s,"requiredStableMs",-1,200,3000),timeout=number(s,"timeoutMs",-1,400,6000);number(s,"minItems",-1,0,160);
        if(stable>timeout||s.getBoolean("conditionMet")!=s.getString("status").equals("settled")||s.getBoolean("conditionMet")&&(s.getInt("stableForMs")<stable||s.getBoolean("afterProjectionRequired")&&!s.getBoolean("projectionChanged")))throw new SecurityException("稳定读取结果与条件不符");
    }
    /** Native monotonic clock only. Only the final sample is handed to the original delivery/source path. */
    static final class Tracker {
        final int timeout,stable,minItems;final String after;int samples;long since=-1;String identity="",signature="";boolean changed;
        Tracker(JSONObject config)throws Exception {timeout=config.optInt("timeoutMs",4000);stable=config.optInt("stableMs",600);minItems=config.optInt("minItems",1);after=config.optString("afterProjectionSha256");}
        boolean sample(JSONObject value,long elapsed)throws Exception {
            samples++;if(!value.optString("status").equals("observed")){since=-1;return false;}
            JSONObject read=value.getJSONObject("read"),d=read.getJSONObject("dynamic");validateDynamic(d);
            String id=value.getString("documentId")+"|"+value.getString("frameId")+"|"+read.getJSONObject("facts").getString("regionRef");if(!identity.isEmpty()&&!identity.equals(id))throw new BrowserTaskSpace.StalePage("稳定读取目标实例已更换，未改读新区域");identity=id;
            String next=read.getString("projectionSha256")+"|"+d.getInt("mutationVersion");changed=after.isEmpty()||!after.equals(read.getString("projectionSha256"));
            boolean ready=d.getBoolean("observerActive")&&!d.getBoolean("busy")&&!d.getBoolean("paginationPartial")&&d.getInt("contentItems")>=minItems&&changed;
            if(!ready){since=-1;signature=next;return false;}if(!next.equals(signature)||since<0){signature=next;since=elapsed;}return samples>=2&&elapsed-since>=stable;
        }
        JSONObject evidence(boolean met,long elapsed)throws Exception {return new JSONObject().put("status",met?"settled":"timeout").put("conditionMet",met).put("samples",samples).put("elapsedMs",Math.min(8000,Math.max(0,elapsed))).put("stableForMs",since<0?0:Math.min(8000,Math.max(0,elapsed-since))).put("requiredStableMs",stable).put("timeoutMs",timeout).put("minItems",minItems).put("afterProjectionRequired",!after.isEmpty()).put("projectionChanged",changed).put("scope","bounded_rendered_projection_and_container_mutations").put("entirePageStable",false).put("networkIdleProven",false);}
    }
}
