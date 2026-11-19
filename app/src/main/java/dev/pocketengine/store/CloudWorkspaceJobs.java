package dev.pocketengine.store;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

/** A private navigation projection of original TaskStore rows. Never a queue, authorization or live process view. */
public final class CloudWorkspaceJobs {
    private static final String UUID="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private CloudWorkspaceJobs() {}
    private static final String[] LOCATION={"profileId","profileBinding","environmentId","worker","workspaceAnchor"};
    public static boolean valid(JSONObject s) {
        if(s==null)return false;
        for(String k:LOCATION)if(!(s.opt(k) instanceof String)||!s.optString(k).matches(k.equals("profileBinding")||k.equals("workspaceAnchor")?"[a-f0-9]{64}":"[A-Za-z0-9_-]{1,80}"))return false;
        return !"command".equals(s.opt("worker"));
    }
    public static boolean same(JSONObject a,JSONObject b) {
        if(!valid(a)||!valid(b))return false;
        for(String k:LOCATION)if(!a.opt(k).equals(b.opt(k)))return false;
        return true;
    }
    private static boolean personal(JSONObject t) {
        return t!=null && "owner".equals(t.opt("actorId")) && "private".equals(t.opt("spaceId"))
            && !Boolean.FALSE.equals(t.opt("contextAvailable")) && !t.optBoolean("sourceCurrentReadRequired")
            && !"source_unavailable".equals(t.opt("taskProjection")) && !t.optBoolean("contextWithdrawn")
            && t.isNull("origin") && t.isNull("parentTaskId") && t.isNull("memberId") && t.isNull("teamRunId");
    }
    public static boolean matches(JSONObject t,JSONObject target) {
        if(!personal(t)||!"remote".equals(t.opt("kind"))||!"remote".equals(t.opt("feature"))||!"submitJob".equals(t.opt("command")))return false;
        String id=t.optString("id");JSONObject refs=t.optJSONObject("externalRefs"),s=refs==null?null:refs.optJSONObject("hostWorkspace");
        String run=t.optString("runId");return id.matches(UUID) && run.matches(UUID) && run.equals(t.opt("operationId")) && run.equals(t.opt("cloudWorkspaceRun"))
            && t.optInt("coordinatorEpoch",-1)>=0 && same(s,target) && !s.optString("serviceBase").isEmpty()
            && s.opt("profileId").equals(refs.opt("profileId")) && s.opt("environmentId").equals(refs.opt("environmentId")) && s.opt("worker").equals(refs.opt("worker"));
    }
    /** Called only by TaskStore's already-authorized continuation transaction, before its run id changes. */
    static void advance(JSONObject task,JSONObject source,String next)throws Exception {
        JSONObject host=source==null?null:source.optJSONObject("hostWorkspace");
        if(!matches(task,host)||!task.get("id").equals(source.opt("taskId"))||!task.get("runId").equals(source.opt("runId"))
                ||!accepted(task,task.optJSONObject("cloudHostAdmission"),host)||!task.get("externalId").equals(source.opt("jobId"))||!next.matches(UUID))return;
        task.put("cloudWorkspaceRun",next);task.remove("cloudHostAdmission");
    }
    /** Acknowledgement and process status are different facts. Neither can be inferred from a phone's task status. */
    public static boolean receipt(JSONObject t,JSONObject e,JSONObject s) {
        return e!=null && valid(s) && e.optString("id").matches("[A-Za-z0-9_-]{1,80}")
            && t.optString("runId").equals(e.opt("operationId"))
            && s.opt("profileId").equals(e.opt("profileId")) && s.opt("profileBinding").equals(e.opt("profileBinding"))
            && s.opt("environmentId").equals(e.opt("environmentId")) && s.opt("worker").equals(e.opt("worker"))
            && s.optString("serviceBase").equals(e.opt("serviceBase")) && s.opt("workspaceAnchor").equals(e.opt("hostWorkspaceAnchor"))
            && e.optString("workspaceIdentity").matches("[a-f0-9]{64}");
    }
    public static boolean accepted(JSONObject t,JSONObject e,JSONObject s) {
        return receipt(t,e,s) && t.optString("runId").equals(t.opt("externalOperationId")) && e.opt("id").equals(t.opt("externalId"));
    }
    public static void noteAdmission(JSONObject t,JSONObject receipt,JSONObject host)throws Exception {
        if(!accepted(t,receipt,host))return;
        JSONObject proof=new JSONObject();
        for(String k:new String[]{"id","operationId","profileId","profileBinding","environmentId","worker","hostWorkspaceAnchor","workspaceIdentity","serviceBase"})proof.put(k,receipt.get(k));
        t.put("cloudHostAdmission",proof); // Immutable original identity, never the live process status or grant.
    }
    private static JSONObject project(JSONObject t)throws Exception {
        JSONObject s=t.getJSONObject("externalRefs").getJSONObject("hostWorkspace"),e=t.optJSONObject("evidence");
        String goal=t.optString("goal");int end=goal.length()>200&&Character.isHighSurrogate(goal.charAt(199))?199:200;
        JSONObject row=new JSONObject().put("taskId",t.get("id")).put("runId",t.get("runId")).put("epoch",t.getInt("coordinatorEpoch"))
            .put("goal",goal.length()>200?goal.substring(0,end)+"…":goal).put("taskStatus",t.getString("status")).put("updatedAt",t.optLong("updatedAt"))
            .put("stage","phone_registered");
        boolean current=accepted(t,e,s);
        if(current||accepted(t,t.optJSONObject("cloudHostAdmission"),s))row.put("stage","host_acknowledged").put("jobId",t.getString("externalId"))
            .put("executorStatus",current?e.optString("executorStatus"):"unknown");
        return row;
    }
    public static JSONObject page(JSONObject target,String position,SshWorkHistory.Headers headers,SshWorkHistory.Original original,SshWorkHistory.Check check)throws Exception {
        if(!valid(target)||position==null||position.length()>2048)throw new IllegalArgumentException("原云工作读取范围无效");
        JSONArray found=new JSONArray();int scanned=0,hydrated=0;long start=SystemClock.elapsedRealtime();boolean more;
        do {
            check.current();
            JSONObject page=headers.read(position,Math.min(8-found.length(),Math.min(16-hydrated,64-scanned)));
            String next=page.getString("nextCursor");more=page.getBoolean("hasMore");
            if(more&&(next.isEmpty()||position.equals(next)))throw new IllegalStateException("原云工作读取位置未推进");
            position=next;JSONArray rows=page.getJSONArray("tasks");scanned+=rows.length();
            for(int i=0;i<rows.length();i++) {
                JSONObject h=rows.getJSONObject(i);if(!personal(h)||!"remote".equals(h.opt("kind"))||!"remote".equals(h.opt("feature")))continue;
                check.current();String id=h.getString("id");JSONObject t=original.read(id);hydrated++;
                if(matches(t,target)&&id.equals(t.opt("id")))found.put(project(t));
            }
        } while(more&&found.length()<8&&scanned<64&&hydrated<16&&SystemClock.elapsedRealtime()-start<350L);
        check.current();return new JSONObject().put("items",found).put("hasMore",more).put("position",position);
    }
}
