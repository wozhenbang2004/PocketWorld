package dev.pocketengine.store;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-only navigation from one immutable SSH profile binding. Never a grant, remote poll or file lookup. */
public final class SshWorkHistory {
    private SshWorkHistory() {}
    public interface Headers { JSONObject read(String position,int limit)throws Exception; }
    public interface Original { JSONObject read(String id)throws Exception; }
    public interface Check { void current()throws Exception; }

    private static boolean privateSource(JSONObject t) {
        return t!=null && "owner".equals(t.opt("actorId")) && "private".equals(t.opt("spaceId"))
            && !Boolean.FALSE.equals(t.opt("contextAvailable")) && !t.optBoolean("sourceCurrentReadRequired")
            && !"source_unavailable".equals(t.opt("taskProjection")) && !t.optBoolean("contextWithdrawn") && (!t.has("origin") || t.isNull("origin"));
    }
    /** Both the page and the final task opener check the original source; a shared account/profile id alone says nothing about the workspace. */
    public static boolean matches(JSONObject t,String profile,String binding) {
        if(!privateSource(t) || profile==null || !profile.matches("[A-Za-z0-9_-]{1,80}") || binding==null || !binding.matches("[a-f0-9]{64}")
                || t.optString("id").isEmpty() || t.optString("runId").isEmpty() || t.optInt("coordinatorEpoch",-1)<0) return false;
        if("assistant".equals(t.opt("kind"))) {
            JSONObject p=t.optJSONObject("sshSelection");
            return p!=null && profile.equals(p.opt("id")) && binding.equals(p.opt("bindingHash"));
        }
        if(!"ssh".equals(t.opt("kind")) || !"ssh".equals(t.opt("feature"))) return false;
        JSONObject refs=t.optJSONObject("externalRefs"), scope=refs==null?null:refs.optJSONObject("sshNativeRequest");
        return scope!=null && t.optString("runId").equals(t.opt("operationId")) && t.optString("id").equals(t.opt("operationId"))
            && "pocketworld.ssh.native-request.v1".equals(scope.opt("format"))
            && profile.equals(refs.opt("profileId")) && profile.equals(scope.opt("profileId")) && binding.equals(scope.opt("profileBinding"))
            && t.optString("command").equals(scope.opt("command")) && scope.optString("requestHash").matches("[a-f0-9]{64}");
    }
    public static JSONObject page(String profile,String binding,String position,Headers headers,Original original,Check check)throws Exception {
        if(!profile.matches("[A-Za-z0-9_-]{1,80}") || !binding.matches("[a-f0-9]{64}") || position==null || position.length()>2048) throw new IllegalArgumentException("原主机工作读取范围无效");
        JSONArray matches=new JSONArray(); int scanned=0, hydrated=0; boolean more;
        long start=SystemClock.elapsedRealtime();
        do {
            check.current();
            // There cannot be an unreturned match beyond a filled page. Only the native presenter sees the global keyset cursor.
            JSONObject page=headers.read(position,Math.min(8-matches.length(),Math.min(16-hydrated,64-scanned)));
            JSONArray rows=page.getJSONArray("tasks"); String next=page.getString("nextCursor"); more=page.getBoolean("hasMore");
            if(more && (next.isEmpty()||position.equals(next))) throw new IllegalStateException("私人任务读取位置未推进");
            position=next; scanned+=rows.length();
            for(int n=0;n<rows.length();n++) {
                JSONObject header=rows.getJSONObject(n); if(!privateSource(header)) continue;
                JSONObject selection=header.optJSONObject("sshSelection");
                if(!"ssh".equals(header.opt("feature")) && !("assistant".equals(header.opt("kind"))
                        && (selection!=null && profile.equals(selection.opt("id")) && binding.equals(selection.opt("bindingHash")) || header.optBoolean("metadataLimited")))) continue;
                check.current(); String id=header.getString("id"); JSONObject task=original.read(id); hydrated++;
                if(matches(task,profile,binding) && id.equals(task.getString("id"))) matches.put(project(task,profile,binding));
            }
        } while(more && matches.length()<8 && scanned<64 && hydrated<16 && SystemClock.elapsedRealtime()-start<350L);
        check.current();
        return new JSONObject().put("items",matches).put("hasMore",more).put("position",position);
    }
    private static JSONObject project(JSONObject t,String profile,String binding)throws Exception {
        String id=t.getString("id"),run=t.getString("runId"); boolean agent="assistant".equals(t.opt("kind"));
        JSONObject row=new JSONObject().put("taskId",id).put("runId",run).put("epoch",t.getInt("coordinatorEpoch"))
            .put("status",t.getString("status")).put("updatedAt",t.optLong("updatedAt")).put("kind",agent?"agent":"operation");
        if(agent) { String goal=t.optString("goal"); int end=goal.length()>240&&Character.isHighSurrogate(goal.charAt(239))?239:240; return row.put("goal",goal.length()>240?goal.substring(0,end)+"…":goal); }
        String action=t.getString("command"); row.put("command",action);
        JSONObject e=t.optJSONObject("evidence");
        if(e==null || !run.equals(t.opt("externalOperationId")) || !run.equals(e.opt("operationId")) || !profile.equals(e.opt("profileId"))) return row;
        row.put("operationId",run);
        if("exec".equals(action) && e.opt("exitCode") instanceof Number) row.put("exitCode",e.getInt("exitCode"));
        if(!"downloadFile".equals(action) || !Boolean.TRUE.equals(e.opt("artifactSaved")) || !Boolean.TRUE.equals(e.opt("verifiedBytes"))) return row;
        JSONObject a=e.optJSONObject("attachment"),src=a==null?null:a.optJSONObject("source");
        JSONObject scope=t.getJSONObject("externalRefs").getJSONObject("sshNativeRequest");
        String v=scope.optString("expectedVersion"),path=scope.optString("path");long bytes=e.optLong("bytes",-1);
        if(a!=null && src!=null && !a.optString("id").isEmpty() && "owner".equals(a.opt("principalId")) && "private".equals(a.opt("spaceId"))
                && v.matches("sha256:[a-f0-9]{64}") && v.equals(e.opt("version")) && v.substring(7).equals(a.opt("sha256"))
                && bytes>=0 && bytes==a.optLong("size",-1) && "ssh_download".equals(src.opt("kind"))
                && id.equals(src.opt("taskId")) && run.equals(src.opt("operationId")) && profile.equals(src.opt("profileId"))
                && binding.equals(src.opt("profileBinding")) && !path.isEmpty() && path.equals(src.opt("path")) && path.equals(e.opt("path")) && v.equals(src.opt("expectedVersion"))) {
            row.put("savedFile",new JSONObject().put("name",a.optString("name")).put("bytes",bytes));
        }
        return row;
    }
}
