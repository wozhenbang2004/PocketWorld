package dev.pocketengine.features.remote;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** One inventory read. A file captured after a settled job is not evidence that the job authored its contents. */
public final class RemoteFileCaptureAttempt {
    @FunctionalInterface public interface OriginalOperation { JSONObject read(String task,String operation)throws Exception; }
    private final JSONObject task;private final OriginalOperation original;
    private final Map<String,JSONObject> observations=new HashMap<>(); // Native cap: eight retained snapshots per task; never retains file bodies.
    public RemoteFileCaptureAttempt(JSONObject task,OriginalOperation original){this.task=task;this.original=original;}
    private static IllegalStateException unknown(){return new IllegalStateException("原采集轮次未核对");}
    private static String text(JSONObject value,String key){Object v=value.opt(key);if(!(v instanceof String s)||s.isBlank()||s.length()>2048||s.indexOf(0)>=0)throw unknown();return s;}
    private static long count(JSONObject value,String key){Object n=value.opt(key);if(!(n instanceof Integer||n instanceof Long)||((Number)n).longValue()<0)throw unknown();return ((Number)n).longValue();}
    private static void same(JSONObject a,JSONObject b,String...keys){for(String k:keys)if(!text(a,k).equals(b.opt(k)))throw unknown();}
    private static boolean personal(JSONObject t){
        if(t==null||!"owner".equals(t.opt("actorId"))||!"private".equals(t.opt("spaceId"))||!"remote".equals(t.opt("feature"))||!"submitJob".equals(t.opt("command"))||
                !t.isNull("teamRunId")||!t.isNull("memberId")||!t.isNull("parentTaskId")||t.optBoolean("contextWithdrawn")||Boolean.FALSE.equals(t.opt("contextAvailable"))||
                t.optBoolean("sourceCurrentReadRequired")||"source_unavailable".equals(t.opt("taskProjection")))return false;
        if(t.isNull("origin"))return true;JSONObject from=t.optJSONObject("origin");if(from==null)return false;String k=from.optString("kind");
        return "native_remote_private_inputs".equals(k)||(k.equals("native_remote_session_branch")||k.equals("native_remote_project_history"))&&t.optString("remoteProjectStartScopeHash").matches("[a-f0-9]{64}");
    }
    private static final String[] SNAP={"snapshotId","snapshotVersion","operationId","jobId","jobOperationId","environmentId","worker","workspaceIdentity","captureSource"};
    private JSONObject capture(String op)throws Exception{
        if(!op.matches("[a-f0-9-]{36}"))throw unknown();String id=text(task,"id");
        JSONArray snapshots=task.optJSONArray("remoteResultSnapshots");if(snapshots==null||snapshots.length()>8)throw unknown();JSONObject stored=null;
        for(int i=0;i<snapshots.length();i++){JSONObject s=snapshots.getJSONObject(i);if(op.equals(s.opt("operationId"))){if(stored!=null)throw unknown();stored=s;}}
        if(stored==null)throw unknown();JSONObject row=original.read(id,op);
        if(!id.equals(row.opt("taskId"))||!op.equals(row.opt("operationId"))||!"remote_snapshot".equals(row.opt("tool"))||!"receipt".equals(row.opt("state")))throw unknown();
        JSONObject sql=row.getJSONObject("receipt"),scope=row.getJSONObject("source");String run=text(row,"runId");long epoch=count(scope,"coordinatorEpoch");
        if(!run.equals(scope.opt("runId"))||!id.equals(scope.opt("taskId"))||epoch>Integer.MAX_VALUE||!op.equals(sql.opt("operationId")))throw unknown();
        for(JSONObject s:new JSONObject[]{sql,stored}){
            if(!"completed".equals(s.opt("status"))||s.opt("immutableArtifact")!=Boolean.TRUE||s.opt("authorshipVerified")!=Boolean.FALSE||
                !"selected_current_workspace_after_settled_original_job".equals(s.opt("captureSource")))throw unknown();
            JSONObject nested=s.getJSONObject("source");same(scope,nested,"taskId","runId","jobId","jobOperationId","environmentId","worker","profileId","serviceBase");
            same(scope,s,"jobId","jobOperationId","environmentId","worker");if(count(nested,"coordinatorEpoch")!=epoch)throw unknown();
            if(scope.has("workspaceIdentity"))same(scope,s,"workspaceIdentity");
        }
        same(sql,stored,SNAP);JSONObject slim=new JSONObject().put("runId",run).put("epoch",epoch).put("profileId",text(scope,"profileId")).put("serviceBase",text(scope,"serviceBase"));
        for(String k:SNAP)slim.put(k,text(sql,k));
        JSONArray sf=sql.getJSONArray("files"),tf=stored.getJSONArray("files"),manifest=new JSONArray();
        if(sf.length()<1||sf.length()>8||sf.length()!=tf.length())throw unknown();
        for(int i=0;i<sf.length();i++){JSONObject a=sf.getJSONObject(i),b=tf.getJSONObject(i);same(a,b,"path","sha256","fileVersion");
            long bytes=count(a,"bytes");if(bytes>262144||count(b,"bytes")!=bytes)throw unknown();
            manifest.put(new JSONObject().put("path",text(a,"path")).put("sha256",text(a,"sha256")).put("fileVersion",text(a,"fileVersion")).put("bytes",bytes));}
        return slim.put("files",manifest);
    }
    /** Returns only run/epoch/relationship, or no claim at all. Uses existing, local TaskStore SQL; does not query a host. */
    public JSONObject forFile(JSONObject actual)throws InterruptedException{
        if(!personal(task)||!"owner".equals(actual.opt("principalId"))||!"private".equals(actual.opt("spaceId"))||actual.optBoolean("outputSourceRetired"))return null;
        try{
            RemoteSavedOriginal.requireLive(task,actual);JSONObject source=actual.getJSONObject("source");String op=text(source,"operationId");
            if(!observations.containsKey(op)){if(observations.size()>=8)return null;observations.put(op,null);observations.put(op,capture(op));}
            JSONObject frozen=observations.get(op);if(frozen==null)return null;same(frozen,source,SNAP);same(frozen,source,"profileId","serviceBase");
            int hits=0;JSONArray files=frozen.getJSONArray("files");for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);if(!text(source,"path").equals(f.opt("path")))continue;
                same(source,f,"sha256","fileVersion");if(count(source,"bytes")!=count(f,"bytes"))return null;hits++;}
            if(hits!=1)return null;
            long epoch=count(frozen,"epoch"),now=count(task,"coordinatorEpoch");String run=text(frozen,"runId"),current=text(task,"runId");
            if(epoch>now||epoch==now&&!run.equals(current))return null;
            return new JSONObject().put("sourceTaskRunId",run).put("sourceCoordinatorEpoch",epoch).put("sourceRunEvidence","original_snapshot_capture")
                    .put("runProvenance",run.equals(current)&&epoch==now?"current":"earlier");
        }catch(InterruptedException stopped){throw stopped;}catch(Exception missing){return null;}
    }
}
