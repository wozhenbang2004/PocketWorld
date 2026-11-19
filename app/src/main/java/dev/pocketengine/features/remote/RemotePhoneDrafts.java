package dev.pocketengine.features.remote;

import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Existing local draft rows for ONE private original remote attempt. No dispatch or rescoping API. */
public final class RemotePhoneDrafts {
    private RemotePhoneDrafts(){}
    private static SecurityException changed(){return new SecurityException("这份手机草稿的原任务、版本或发送状态已变化；没有当作未发送撤回");}
    private static boolean equal(JSONObject a,JSONObject b,String key){Object x=a.opt(key);return x instanceof String s&&!s.isBlank()&&x.equals(b.opt(key));}
    private static long n(JSONObject value,String key){Object v=value.opt(key);if(!(v instanceof Integer||v instanceof Long)||((Number)v).longValue()<0)throw changed();return ((Number)v).longValue();}
    private static boolean personal(JSONObject task){
        if(task==null||!"owner".equals(task.opt("actorId"))||!"private".equals(task.opt("spaceId"))||!"remote".equals(task.opt("feature"))||
                !"submitJob".equals(task.opt("command"))||!task.isNull("teamRunId")||!task.isNull("memberId")||!task.isNull("parentTaskId")||
                Boolean.FALSE.equals(task.opt("contextAvailable"))||task.optBoolean("contextWithdrawn")||task.optBoolean("sourceCurrentReadRequired")||"source_unavailable".equals(task.opt("taskProjection")))return false;
        if(task.isNull("origin"))return true;JSONObject origin=task.optJSONObject("origin");if(origin==null)return false;
        String kind=origin.optString("kind");return "native_remote_private_inputs".equals(kind)||
                (kind.equals("native_remote_session_branch")||kind.equals("native_remote_project_history"))&&task.optString("remoteProjectStartScopeHash").matches("[a-f0-9]{64}");
    }
    /** Marker only. Never serialize other drafts or even an excerpt on ordinary task/list renders. */
    public static boolean hasLocalRows(JSONObject task){
        if(!personal(task))return false;JSONArray rows=task.optJSONArray("remoteInputDrafts");if(rows==null||rows.length()>16)return false;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i),scope=row==null?null:row.optJSONObject("scope");
            if(scope!=null&&task.opt("id") instanceof String id&&!id.isBlank()&&id.equals(scope.opt("taskId"))&&equal(task,scope,"runId")){
                Object a=task.opt("coordinatorEpoch"),b=scope.opt("coordinatorEpoch");
                if((a instanceof Integer||a instanceof Long)&&(b instanceof Integer||b instanceof Long)&&((Number)a).longValue()>=0&&((Number)a).longValue()==((Number)b).longValue())return true;}}
        return false;
    }
    private static boolean original(JSONObject row,JSONObject target){
        JSONObject scope=row.optJSONObject("scope");if(scope==null||!row.optString("id").matches("[a-f0-9-]{36}")||!scope.optString("profileGeneration").matches("[a-f0-9]{64}")
                ||!row.optString("mode").matches("steer|next_turn")||!equal(scope,row,"mode")||scope.optString("threadId").isBlank())return false;
        for(String k:new String[]{"taskId","runId","jobId","jobOperationId","profileId","serviceBase","environmentId","worker","protocol","threadId"})if(!equal(scope,target,k))return false;
        for(String k:new String[]{"sessionId","workspaceIdentity"})if(scope.has(k)&&!equal(scope,target,k))return false;
        return n(scope,"coordinatorEpoch")==n(target,"coordinatorEpoch")&&n(row,"revision")>0;
    }
    private static String state(JSONObject row){
        String s=row.optString("state");if(s.equals("withdrawn"))return row.isNull("operationId")?"withdrawn":"claimed";
        if((s.equals("draft")||s.equals("queued"))&&row.isNull("operationId"))return s;
        return "claimed"; // Includes unknown/prepared/dispatched states; none can be withdrawn by this path.
    }
    private static JSONObject header(JSONObject row)throws Exception{return new JSONObject().put("id",row.getString("id")).put("revision",n(row,"revision")).put("mode",row.getString("mode"))
            .put("localState",state(row)).put("scopeHash",TaskStore.capabilityFingerprint(row.getJSONObject("scope"))).put("updatedAt",n(row,"updatedAt"));}
    private static String hash(String s)throws Exception{
        var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(s));
        var digest=MessageDigest.getInstance("SHA-256");digest.update(encoded);StringBuilder out=new StringBuilder(64);for(byte b:digest.digest())
            out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();
    }
    /** Rejects unavailable/ill-formed replacement text before it can displace a real draft. */
    public static String textFingerprint(String text)throws Exception{return hash(RemoteTaskDrafts.text(text));}
    /** TaskStore supplies its raw task and its own durable original job target. No profile service is consulted. */
    public static JSONObject inspect(JSONObject task,JSONObject target,JSONObject selection,boolean list)throws Exception{
        if(!personal(task))throw changed();
        RemoteObservationAttempt.shown(task,new JSONObject().put("taskId",selection.get("taskId")).put("shownRunId",selection.get("runId")).put("shownEpoch",selection.get("coordinatorEpoch"))).require(target);
        JSONArray rows=task.optJSONArray("remoteInputDrafts"),shown=new JSONArray();if(rows==null)rows=new JSONArray();if(rows.length()>16)throw changed();JSONObject exact=null;HashSet<String> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            // TaskStore's durable lookup is by draft ID across all scopes. An ambiguous ID must never select its first record.
            if(!ids.add(row.getString("id")))throw changed();
            if(!original(row,target))continue;JSONObject head=header(row);if(list){shown.put(head);continue;}
            if(!head.getString("id").equals(selection.opt("id")))continue;
            if(n(head,"revision")!=n(selection,"revision")||!equal(head,selection,"scopeHash")||!state(row).matches("draft|queued"))throw changed();
            String text=RemoteTaskDrafts.text(row.getString("text"));exact=head.put("text",text).put("textSha256",hash(text));
        }
        if(!list&&exact==null)throw changed();
        return new JSONObject().put("taskId",target.get("taskId")).put("runId",target.get("runId")).put("coordinatorEpoch",target.get("coordinatorEpoch"))
                .put("networkUsed",false).put("sent",false).put(list?"entries":"item",list?shown:exact);
    }
}
