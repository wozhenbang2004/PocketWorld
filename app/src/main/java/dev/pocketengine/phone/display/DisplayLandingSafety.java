package dev.pocketengine.phone.display;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only facts about the original helper session, never a second execution authority. */
public final class DisplayLandingSafety {
    private DisplayLandingSafety() { }
    public static String failure(JSONObject before,JSONObject after,int display) {
        try {
            Map<Integer,JSONObject> next=tasks(after,display);
            if(before!=null)for(JSONObject original:tasks(before,display).values())if(original.getInt("displayId")==display) {
                JSONObject actual=next.get(original.getInt("taskId"));
                if(actual==null)return "original_owned_task_missing";
                if(actual.getInt("displayId")!=display)return "original_owned_task_migrated";
                for(String field:new String[]{"rootTaskId","androidUserId","component","rootIdentity"})
                    if(!original.get(field).equals(actual.get(field)))return "original_owned_task_identity_changed";
            }
            // A known unrelated primary A -> B is fine. The original owned task on
            // any other display is not; neither is a resumed primary app collision.
            JSONArray primary=after.getJSONArray("primary");
            for(JSONObject owned:next.values())if(owned.getInt("displayId")==display)
                for(int i=0;i<primary.length();i++)if(primary.getJSONObject(i).getString("packageName").equals(owned.getString("packageName")))
                    return "main_screen_package_conflict";
            return null;
        }catch(Exception unknown){return "task_topology_unavailable";}
    }
    private static Map<Integer,JSONObject> tasks(JSONObject facts,int display)throws Exception {
        if(facts==null||display<=0)throw new IllegalArgumentException();
        JSONObject topology=facts.getJSONObject("taskTopology");
        if(topology.optInt("schemaVersion",-1)!=1||!"observed".equals(topology.optString("status"))
                ||!Boolean.TRUE.equals(topology.opt("complete"))||!Boolean.TRUE.equals(topology.opt("readOnly")))throw new IllegalArgumentException();
        JSONArray rows=topology.getJSONArray("tasks"),primary=facts.getJSONArray("primary"),owned=facts.getJSONArray("owned");
        if(rows.length()>256||primary.length()<1||primary.length()>32||owned.length()>32)throw new IllegalArgumentException();
        Map<Integer,JSONObject> tasks=new LinkedHashMap<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);int id=integer(row,"taskId"),root=integer(row,"rootTaskId"),at=integer(row,"displayId"),user=integer(row,"androidUserId");
            String pkg=row.getString("packageName"),component=row.getString("component"),identity=row.getString("rootIdentity");
            if(id<0||root<0||at<0||user<0||pkg.length()>250||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")
                    ||!component.startsWith(pkg+"/")||component.length()>1000||identity.isEmpty()||identity.length()>100||tasks.put(id,row)!=null)throw new IllegalArgumentException();
        }
        for(JSONArray resumed:new JSONArray[]{primary,owned})for(int i=0;i<resumed.length();i++) {
            JSONObject row=resumed.getJSONObject(i),task=tasks.get(integer(row,"taskId"));int at=integer(row,"displayId");
            if(at!=(resumed==primary?0:display)||task==null||task.getInt("displayId")!=at||!task.getString("packageName").equals(row.getString("packageName")))throw new IllegalArgumentException();
        }
        boolean hasOwned=false;for(JSONObject row:tasks.values())hasOwned|=row.getInt("displayId")==display;
        if(hasOwned&&owned.length()==0||owned.length()>0&&!"observed".equals(facts.optString("status")))throw new IllegalArgumentException();
        // No resumed owned row is acceptable only when the complete topology
        // positively reports an empty owned display (e.g. before first launch).
        return tasks;
    }
    private static int integer(JSONObject row,String key)throws Exception {
        Object value=row.get(key);if(!(value instanceof Number))throw new IllegalArgumentException();double n=((Number)value).doubleValue();
        if(!Double.isFinite(n)||n!=Math.rint(n)||n<0||n>Integer.MAX_VALUE)throw new IllegalArgumentException();return(int)n;
    }
}
