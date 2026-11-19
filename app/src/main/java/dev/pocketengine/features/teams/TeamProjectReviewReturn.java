package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A pointer from an actual selected project's own run to one independent first-edition read.
 * Contains no file names/bodies, authorizations, execution commands or shared conversation data. */
public final class TeamProjectReviewReturn {
    private TeamProjectReviewReturn(){}
    public static final int PAGE_SIZE=4;
    public static boolean parent(JSONObject work){
        return retainedParent(work)&&!work.optBoolean("cancelRequested")&&!work.optString("status").matches("completed|stopping|cancelled|paused|pausing|interrupted");
    }
    /** No authority: a stopped original can still locate its own retained receipt. */
    public static boolean retainedParent(JSONObject work){
        JSONObject dev=work.optJSONObject("developmentContext"),pool=work.optJSONObject("teamReservationPool");
        JSONObject source=pool==null?null:pool.optJSONObject("budgetSource");
        return dev!=null&&source!=null&&source.opt("nativeProjectWorkPlan")==Boolean.TRUE&&
                "pocketworld.development-context.v1".equals(dev.opt("format"))&&"owner".equals(work.opt("actorId"))&&"private".equals(work.opt("spaceId"))&&
                "assistant".equals(work.opt("kind"))&&"capabilities".equals(work.opt("executionMode"))&&!work.has("ownerWork")&&!work.has("teamAssignment")&&
                work.optJSONObject("origin")==null&&!work.has("teamProjectRound")&&!work.optString("conversationId").isEmpty()&&
                work.optString("runId").equals(pool.opt("parentRunId"))&&work.optString("id").equals(pool.opt("parentTaskId"));
    }
    public static List<JSONObject> links(JSONObject work)throws Exception{
        List<JSONObject> out=new ArrayList<>();Set<String> tasks=new HashSet<>(),runs=new HashSet<>();JSONArray all=work.optJSONArray("relatedTasks");
        if(all==null)return out;
        if(all.length()>100)throw new SecurityException("原工作关联超出可核对范围，没有替换为最近团队");
        for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i);if(!"teams".equals(row.opt("feature")))continue;
            String id=row.getString("taskId"),run=row.getString("runId");
            if(id.isEmpty()||run.isEmpty()||!tasks.add(id)||!runs.add(run)||out.size()>=32)throw new SecurityException("原工作没有唯一可核对的团队关联");
            out.add(row);
        }
        return out;
    }
    public static boolean linked(JSONObject work,JSONObject team,JSONObject link){
        return team!=null&&"owner".equals(team.opt("actorId"))&&"private".equals(team.opt("spaceId"))&&"teams".equals(team.opt("feature"))&&
                link.optString("taskId").equals(team.opt("id"))&&link.optString("runId").equals(team.opt("runId"))&&link.optString("runId").equals(team.opt("externalId"))&&
                work.optString("id").equals(team.opt("parentTaskId"))&&work.optString("runId").equals(team.opt("parentRunId"));
    }
    public static JSONObject mark(JSONObject work,JSONObject team,JSONObject link,JSONObject facts)throws Exception{
        boolean registered="registered_project_first".equals(facts.opt("state"));
        if(!(registered?retainedParent(work):parent(work)&&"eligible_project_first".equals(facts.opt("state")))||!linked(work,team,link)||!work.getString("id").equals(facts.opt("parentTaskId"))||
                !work.getString("runId").equals(facts.opt("parentRunId"))||work.getInt("coordinatorEpoch")!=facts.getInt("parentEpoch")||
                !team.getString("runId").equals(facts.opt("teamRunId"))||!work.getJSONObject("developmentContext").getString("projectId").equals(facts.opt("targetProjectId"))||
                !work.getJSONObject("developmentContext").getString("checkoutId").equals(facts.opt("checkoutId")))throw new SecurityException("这份独审不属于当前原项目任务");
        JSONObject mark=new JSONObject().put("teamTaskId",team.getString("id")).put("conversationId",work.getString("conversationId"))
                .put("projectId",facts.getString("targetProjectId")).put("fileCount",team.getJSONObject("teamIndependentReview").getJSONArray("files").length());
        for(String key:new String[]{"parentTaskId","parentRunId","parentEpoch","teamRunId","planHash","reviewerTaskId","reviewerRunId","checkoutId","selectionHash","snapshotIdentity","parentSourceDigest","sourceDigest"})
            mark.put(key,facts.get(key));
        if(registered)mark.put("registeredReviewId",facts.getString("registeredReviewId")).put("registeredAt",facts.getLong("registeredAt"));
        return mark;
    }
}
