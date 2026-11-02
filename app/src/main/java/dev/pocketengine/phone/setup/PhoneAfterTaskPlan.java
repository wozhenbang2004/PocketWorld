package dev.pocketengine.phone.setup;

import dev.pocketengine.phone.access.AppCopyKey;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit user goals for actual held App tasks; recording a goal is never a cleanup receipt. */
public final class PhoneAfterTaskPlan {
    private final Map<AppCopyKey,String> marks=new LinkedHashMap<>();
    public synchronized JSONObject mark(AppCopyKey copy,String action)throws Exception {if(!java.util.Set.of("keep","close").contains(action))throw new IllegalArgumentException("after_task_action_invalid");marks.put(copy,action);return new JSONObject().put("ok",true).put("action",action).put("packageName",copy.packageName()).put("userId",copy.userId()).put("applied",false).put("status","recorded_for_original_session_cleanup");}
    /** Native exact ownership only. Model-supplied task IDs are never accepted. */
    public synchronized JSONObject prepare(JSONObject actual,int display)throws Exception {
        JSONArray tasks=new JSONArray();boolean blocked=false;JSONArray held=actual.optJSONArray("owned");if(!"observed".equals(actual.optString("status"))||held==null)return new JSONObject().put("status","ownership_unknown").put("tasks",tasks).put("applied",false).put("requiresOriginalReceiptCheck",true);
        for(int i=0;i<held.length();i++){JSONObject row=held.getJSONObject(i);if(!row.has("userId")||row.optInt("displayId",-1)!=display||row.optInt("taskId",-1)<0){blocked=true;continue;}AppCopyKey key=new AppCopyKey(row.getString("packageName"),row.getInt("userId"));String action=marks.get(key);if(action!=null)tasks.put(new JSONObject().put("taskId",row.getInt("taskId")).put("displayId",display).put("packageName",key.packageName()).put("userId",key.userId()).put("action",action));}
        return new JSONObject().put("status",blocked?"partial":"prepared").put("tasks",tasks).put("applied",false).put("requiresOriginalReceiptCheck",true).put("requiresExactTaskIdentityRecheck",true).put("keepRequiresBackgroundPlacement",true);
    }
}
