package dev.pocketengine.displayhelper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Adds all-display package/profile facts for resource admission; it does not move or close any task. */
final class DisplayAppOwnership {
    /** Called at the actual native launch fence; an empty process resource map is not proof of screen freedom. */
    static void requireAppFree(String dump,String pkg,int user,int ownDisplay)throws Exception {
        if(ownDisplay<=0||user<0||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new SecurityException("app_admission_identity_invalid");
        JSONObject facts=attach(new JSONObject(),dump);if(!facts.optBoolean("allDisplayIdentitiesKnown"))throw new SecurityException("all_display_ownership_unknown");
        JSONArray all=facts.getJSONArray("all");for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(pkg.equals(row.getString("packageName"))&&user==row.getInt("userId")&&ownDisplay!=row.getInt("displayId"))throw new SecurityException("app_copy_in_use_on_another_display");}
    }
    static JSONObject attach(JSONObject ownership,String dump)throws Exception {
        Pattern display=Pattern.compile("^Display #([0-9]+)(?:\\s.*)?$"),resumed=Pattern.compile("(?:mResumedActivity|topResumedActivity).*?\\bu([0-9]+)\\s+([A-Za-z][A-Za-z0-9_.]*)/[^ ]+.*?\\bt([0-9]+)\\b");
        JSONArray all=new JSONArray();int current=-1;boolean complete=true,seen=false;
        for(String line:dump.split("\n")){String text=line.trim();Matcher d=display.matcher(text);if(d.matches()){current=Integer.parseInt(d.group(1));seen=true;continue;}
            if(!text.contains("mResumedActivity")&&!text.contains("topResumedActivity"))continue;
            if(text.matches(".*(?:mResumedActivity|topResumedActivity)\\s*[=:]\\s*null.*"))continue;
            Matcher row=resumed.matcher(text);if(current<0||!row.find()){complete=false;continue;}
            JSONObject fact=new JSONObject().put("displayId",current).put("userId",Integer.parseInt(row.group(1))).put("packageName",row.group(2)).put("taskId",Integer.parseInt(row.group(3)));
            boolean duplicate=false;for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getInt("taskId")==fact.getInt("taskId")&&all.getJSONObject(i).getInt("userId")==fact.getInt("userId"))duplicate=true;if(!duplicate)all.put(fact);
        }
        for(String key:new String[]{"primary","owned"}){JSONArray rows=ownership.optJSONArray(key);if(rows==null)continue;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);for(int j=0;j<all.length();j++){JSONObject actual=all.getJSONObject(j);if(row.optInt("taskId",-1)==actual.getInt("taskId")&&row.optInt("displayId",-1)==actual.getInt("displayId"))row.put("userId",actual.getInt("userId"));}}}
        return ownership.put("all",all).put("allDisplayIdentitiesKnown",seen&&complete).put("identityScope","resumed_tasks_only");
    }
}
