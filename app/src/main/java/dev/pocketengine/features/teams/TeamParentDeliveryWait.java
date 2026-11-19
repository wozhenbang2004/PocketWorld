package dev.pocketengine.features.teams;

import dev.pocketengine.core.RunControl;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;

/** Process-local observation for ONE requested parent read. It neither owns, wakes nor releases any worker. */
final class TeamParentDeliveryWait {
    private record Bound(String role,String task,String run,int epoch,RunControl control) {}
    private final String team,status;
    private final long cursor;
    private final int generation;
    private final Map<String,Bound> captured;
    private final Function<String,RunControl> byTask,byRole;
    private TeamParentDeliveryWait(JSONObject page,Map<String,Bound> captured,Function<String,RunControl> byTask,Function<String,RunControl> byRole)throws Exception{
        this.team=page.getString("teamRunId");this.status=page.getString("status");cursor=page.getLong("latestEvent");generation=page.getInt("generation");
        this.captured=Map.copyOf(captured);this.byTask=byTask;this.byRole=byRole;
    }
    static boolean candidate(JSONObject page)throws Exception {
        return "awaiting_review".equals(page.opt("status")) && !page.getBoolean("gap") && page.getJSONArray("events").length()==0 &&
                page.getLong("afterEvent")==page.getLong("nextEvent") && page.getLong("nextEvent")==page.getLong("latestEvent");
    }
    static TeamParentDeliveryWait capture(JSONObject page,JSONObject workers,Function<String,RunControl> byTask,Function<String,RunControl> byRole)throws Exception{
        if(!candidate(page)||!page.getString("teamRunId").equals(workers.opt("teamRunId"))||!Boolean.TRUE.equals(workers.opt("complete"))||
                !"in_progress".equals(workers.opt("stage"))||workers.optInt("unresolvedCount",-1)!=0||workers.getJSONArray("unboundRoles").length()!=0)return null;
        Map<String,Bound> originals=new LinkedHashMap<>();JSONArray rows=workers.getJSONArray("workers");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);
            if(!row.optBoolean("nativeActive")){if(row.optBoolean("activeAttempt")||row.optBoolean("selectedVersion"))return null;continue;}
            String role=row.getString("role"),task=row.getString("taskId"),run=row.getString("runId");int epoch=row.getInt("epoch");
            // A real reviewer is recorded in teamIndependentReview only AFTER its verdict; its original live TaskStore run suffices here.
            if(!role.matches("reviewer|integrator")||("integrator".equals(role)&&!row.optBoolean("selectedVersion"))||
                    !row.optBoolean("canOpen")||run.isEmpty()||epoch<1||!page.getString("teamRunId").equals(row.opt("teamRunId"))||
                    row.optBoolean("hasUnresolvedEffects")||row.optBoolean("cancelRequested")||!row.optString("state").matches("running|releasing")||
                    !row.optString("taskStatus").matches("preparing|running|completed"))return null;
            RunControl control=byRole.apply(role);
            if(control==null||control!=byTask.apply(task)||!control.matchesHostRun(task,run,epoch)||control.isCancelled()||control.isPaused()||
                    originals.put(role,new Bound(role,task,run,epoch,control))!=null)return null;
        }
        return originals.isEmpty()?null:new TeamParentDeliveryWait(page,originals,byTask,byRole);
    }
    boolean covers(JSONObject page)throws Exception{
        return team.equals(page.opt("teamRunId"))&&status.equals(page.opt("status"))&&generation==page.optInt("generation",-1)
                &&cursor==page.getLong("afterEvent")&&cursor==page.getLong("latestEvent")&&candidate(page);
    }
    /** Only compare the original control objects; no database, monitor acquisition, schedule or model poll. */
    boolean changed(){
        for(Bound b:captured.values())if(byRole.apply(b.role)!=b.control||byTask.apply(b.task)!=b.control||!b.control.matchesHostRun(b.task,b.run,b.epoch)||
                b.control.isCancelled()||b.control.isPaused())return true;
        return false;
    }
    JSONObject report(JSONObject event,JSONObject latest)throws Exception{
        if(!team.equals(event.opt("teamRunId"))||!team.equals(latest.opt("teamRunId")))throw new SecurityException("等待回执不属于原团队");
        boolean complete=latest.getBoolean("complete")&&latest.getJSONArray("unboundRoles").length()==0;
        boolean controlsChanged=changed();int unresolved=latest.getInt("unresolvedCount");
        JSONArray originals=new JSONArray(),rows=latest.getJSONArray("workers");
        for(Bound b:captured.values()){
            JSONObject seen=new JSONObject().put("role",b.role).put("taskId",b.task).put("runId",b.run).put("epoch",b.epoch);
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);
                if(b.role.equals(row.opt("role"))&&b.task.equals(row.opt("taskId"))&&b.run.equals(row.opt("runId"))&&b.epoch==row.optInt("epoch",-1)){
                    seen.put("workerState",row.getString("state")).put("taskStatus",row.getString("taskStatus")).put("nativeActive",row.getBoolean("nativeActive"));break;
                }
            }
            // Terminal rows can leave the live index. Their absence proves neither success nor delivery.
            originals.put(seen);
        }
        String stage=latest.getString("stage");
        String coordinator=event.getString("status");event.put("coordinatorStatus",coordinator);
        if(status.equals(coordinator)&&generation==event.optInt("generation",-1)){
            if(unresolved>0)event.put("status","blocked_unknown");
            else if(!complete)event.put("status","delivery_needs_check");
            else if("in_progress".equals(stage))event.put("status","delivery_in_progress");
            else if(!"no_pending_original".equals(stage))event.put("status","delivery_needs_check");
            if("timeout".equals(event.opt("state"))&&(!complete||!"in_progress".equals(stage)||controlsChanged))
                event.put("state","delivery_activity_changed").put("timedOut",false).remove("pending");
        }
        return event.put("waitedForOriginalWorkers",originals).put("originalDeliveryControlChanged",controlsChanged).put("deliveryStage",stage)
                .put("deliveryCoverageComplete",complete).put("deliveryResourcesReleased",complete&&latest.getBoolean("resourcesReleased"))
                .put("deliveryHasUnresolvedEffects",unresolved>0?Boolean.TRUE:complete?Boolean.FALSE:JSONObject.NULL)
                .put("businessVerified",false).put("executionAuthorized",false);
    }
}
