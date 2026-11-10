package dev.pocketengine.phone.observation;

import org.json.JSONArray;
import org.json.JSONObject;

/** Geometry from an actual native container. The caller still owns frame, prepared-operation and dispatch checks. */
final class PhoneRegionGesture {
    static final String TOOL="phone_scroll_region";
    private PhoneRegionGesture() { }

    static boolean nativeScrollAvailable(JSONObject node,String direction){
        JSONArray directions=node.optJSONArray("scrollDirections");
        if(directions==null)return node.optBoolean("scrollable");
        for(int i=0;i<directions.length();i++)if(direction.equals(directions.optString(i)))return true;
        return false;
    }

    static JSONObject node(JSONObject observation,String id)throws Exception {
        JSONObject node=PhoneTreeExtract.index(observation).get(id);
        if(node==null||node.optBoolean("password")||!node.optBoolean("visible",true)||!node.optBoolean("enabled",true)
                ||node.optString("structuralFingerprint").isEmpty())throw new SecurityException("native_region_unavailable");
        bounds(node);return node;
    }

    static JSONObject sameTarget(JSONObject actual,JSONObject original,JSONObject anchor)throws Exception {
        if(!original.optString("packageName").equals(actual.optString("packageName"))
                ||original.optLong("pauseRevision",-1)!=actual.optLong("pauseRevision",-2)
                ||original.optLong("appPolicyRevision",-1)!=actual.optLong("appPolicyRevision",-1)
                ||!PhoneTreeSequence.scope(original).equals(PhoneTreeSequence.scope(actual)))throw new SecurityException("native_region_scope_changed");
        JSONObject found=null;
        for(JSONObject node:PhoneTreeExtract.index(actual).values())if(node.optInt("windowId",-1)==anchor.getInt("windowId")
                &&node.getJSONArray("path").toString().equals(anchor.getJSONArray("path").toString())
                &&node.optString("structuralFingerprint").equals(anchor.optString("structuralFingerprint"))){
            if(found!=null)throw new SecurityException("native_region_ambiguous");found=node;
        }
        if(found==null)throw new SecurityException("native_region_changed");return node(actual,found.getString("id"));
    }

    static JSONObject plan(JSONObject observation,JSONObject node,String direction,String axis)throws Exception {
        return planBounds(observation,bounds(node),direction,axis);
    }
    static JSONObject planBounds(JSONObject observation,JSONObject b,String direction,String axis)throws Exception {
        boolean horizontal="horizontal".equals(axis),forward="forward".equals(direction);
        int left=b.getInt("left"),right=b.getInt("right"),top=b.getInt("top"),bottom=b.getInt("bottom");
        int extent=horizontal?right-left:bottom-top,cross=horizontal?bottom-top:right-left;
        if(extent<60||cross<24)throw new IllegalArgumentException("native_region_too_small");
        int margin=Math.max(8,(int)Math.ceil(extent*0.18));
        int low=(horizontal?left:top)+margin,high=(horizontal?right:bottom)-margin;
        int center=horizontal?(top+bottom)/2:(left+right)/2;
        int from=forward?high:low,to=forward?low:high;
        JSONArray path=new JSONArray().put(new JSONArray().put(horizontal?from:center).put(horizontal?center:from))
                .put(new JSONArray().put(horizontal?to:center).put(horizontal?center:to));
        return new JSONObject().put("observationId",observation.getString("observationId"))
                .put("paths",new JSONArray().put(path)).put("durationMs",480);
    }

    static JSONObject bounds(JSONObject node)throws Exception {
        JSONObject b=node.getJSONObject("bounds");
        int left=Math.max(4,b.getInt("left")),top=Math.max(4,b.getInt("top"));
        int right=Math.min(996,b.getInt("right")),bottom=Math.min(996,b.getInt("bottom"));
        if(left>=right||top>=bottom)throw new SecurityException("native_region_bounds_unavailable");
        return new JSONObject().put("left",left).put("top",top).put("right",right).put("bottom",bottom);
    }
}
