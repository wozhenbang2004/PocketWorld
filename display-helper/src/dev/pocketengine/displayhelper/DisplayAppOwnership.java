package dev.pocketengine.displayhelper;

import org.json.JSONArray;
import org.json.JSONObject;

/** Joins visible owners to the complete native task inventory. A resumed-task list alone is insufficient. */
final class DisplayAppOwnership {
    private DisplayAppOwnership() { }
    static JSONObject enrich(JSONObject visible,JSONObject topology,int display)throws Exception {
        JSONObject out=new JSONObject(visible.toString()).put("taskTopology",topology);
        boolean complete="observed".equals(topology.optString("status"))&&topology.optBoolean("complete");
        JSONArray tasks=topology.optJSONArray("tasks"),all=new JSONArray();
        if(tasks==null)complete=false;
        for(int i=0;tasks!=null&&i<tasks.length();i++){
            JSONObject task=tasks.getJSONObject(i);
            all.put(new JSONObject(task.toString()).put("userId",task.getInt("androidUserId")));
        }
        boolean identities=complete;
        for(String key:new String[]{"primary","owned"}){
            JSONArray rows=out.optJSONArray(key);if(rows==null){identities=false;continue;}
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i),matched=null;
                for(int j=0;j<all.length();j++){JSONObject task=all.getJSONObject(j);
                    if(task.getInt("taskId")==row.optInt("taskId",-1)&&task.getInt("displayId")==row.optInt("displayId",-1)&&task.getString("packageName").equals(row.optString("packageName"))){if(matched!=null){matched=null;break;}matched=task;}}
                if(matched==null)identities=false;else row.put("userId",matched.getInt("userId"));
            }
        }
        JSONArray primary=out.optJSONArray("primary"),owned=out.optJSONArray("owned");
        boolean observed=identities&&primary!=null&&primary.length()>0&&(display==0||owned!=null&&owned.length()>0);
        return out.put("status",observed?"observed":"unknown").put("all",all)
                .put("allDisplayIdentitiesKnown",complete).put("reason",observed?"native_task_identity":"native_task_identity_unavailable");
    }
    static boolean free(JSONObject topology,String pkg,int user,int display)throws Exception {
        if(!"observed".equals(topology.optString("status"))||!topology.optBoolean("complete"))return false;
        JSONArray tasks=topology.optJSONArray("tasks");if(tasks==null)return false;
        for(int i=0;i<tasks.length();i++){JSONObject task=tasks.getJSONObject(i);
            if(pkg.equals(task.optString("packageName"))&&task.optInt("androidUserId",-1)==user&&task.optInt("displayId",-1)!=display)return false;}
        return true;
    }
}
