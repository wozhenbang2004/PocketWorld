package dev.pocketengine.features.remote;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import dev.pocketengine.store.TaskStore;

/** Last stored native metadata only. A row, grouping key or attention flag is never a grant. */
public final class RemoteManagedSessions {
    private RemoteManagedSessions() {}
    public static final int LOCAL_TASK_WINDOW = 200;
    private static String text(JSONObject value,String key,int limit) {
        Object raw=value.opt(key);if(!(raw instanceof String))return "";String s=(String)raw;
        return s.length()<=limit&&s.indexOf('\0')<0?s:"";
    }
    private static String hash(JSONObject value)throws Exception {return TaskStore.capabilityFingerprint(value);}
    private static int requests(JSONObject source,String field) {
        JSONArray values=source.optJSONArray(field);int count=0;
        for(int i=0;values!=null&&i<values.length();i++){JSONObject item=values.optJSONObject(i);if(item!=null&&!text(item,"requestId",200).isEmpty())count++;}
        return count;
    }
    /** Pure projection: callers must also validate original parent lineage in the native store. */
    public static JSONObject row(JSONObject task)throws Exception {
        if(!"remote".equals(task.optString("feature"))||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId")))return null;
        JSONObject origin=task.optJSONObject("origin");String kind=origin==null?"":origin.optString("kind");
        if(task.has("teamRunId")||task.has("memberId")||kind.startsWith("team")||kind.matches("channel|group|reviewed_external_group"))return null;
        String id=text(task,"id",80),run=text(task,"runId",80);if(!id.matches("[a-f0-9-]{36}"))return null;
        JSONObject out=new JSONObject().put("taskId",id).put("runId",run).put("coordinatorEpoch",task.optInt("coordinatorEpoch"))
                .put("sourceAdapter","pocketworld_managed_task").put("provenance","original_taskstore_last_observation")
                .put("dataOnly",true).put("authorizesExecution",false).put("requiresOriginalRequery",true)
                .put("lastLocalUpdateAt",task.optLong("updatedAt")).put("taskStatus",text(task,"status",80))
                .put("identityState","unconfirmed_local_task").put("sessionEvidence","unavailable");
        if(task.has("parentTaskId"))out.put("parentTaskId",text(task,"parentTaskId",80));
        JSONObject source=task.optJSONObject("evidence"),refs=task.optJSONObject("externalRefs");
        String operation=text(task,"operationId",80),profile=refs==null?"":text(refs,"profileId",80),job=text(task,"externalId",80);
        boolean coherent=source!=null&&refs!=null&&!profile.isEmpty()&&!run.isEmpty()&&run.equals(operation)
                &&operation.equals(text(source,"operationId",80))&&profile.equals(text(source,"profileId",80));
        URI endpoint=null;try{if(coherent)endpoint=RemoteTransport.validateBase(text(source,"serviceBase",2048));}catch(Exception invalid){coherent=false;}
        if(coherent&&endpoint!=null){
            String service=text(source,"serviceBase",2048),environment=text(source,"environmentId",80),workspace=text(source,"workspaceIdentity",200),worker=text(source,"worker",80),protocol=text(source,"protocol",80);
            JSONObject device=new JSONObject().put("profileId",profile).put("serviceBase",service);String connectionKey=hash(device);
            String originAddress=new URI(endpoint.getScheme(),null,endpoint.getHost(),endpoint.getPort(),null,null,null).toString();
            JSONObject context=new JSONObject(device.toString()).put("environmentId",environment).put("workspaceIdentity",workspace);
            // Unknown physical workspace/environment must not merge tasks just because labels match.
            if(environment.isEmpty()||workspace.isEmpty())context.put("unknownForTask",id);
            String thread=text(source,"threadId",1024),session=text(source,"sessionId",1024);
            boolean confirmed=!job.isEmpty()&&Boolean.TRUE.equals(source.opt("protocolVerified"))&&!thread.isEmpty();
            if("pi_rpc_v1".equals(protocol))confirmed&=RemoteHarnessSelection.piScopeConfirmed(source)&&!session.isEmpty()&&session.equals(thread);
            else if("claude_stream_json".equals(protocol))confirmed&=Boolean.TRUE.equals(source.opt("controlProtocolVerified"))&&Boolean.TRUE.equals(source.opt("sessionIdentityConfirmed"))&&!session.isEmpty()&&session.equals(thread);
            else if(RemoteHarnessSelection.isGenericWorker(worker)){confirmed&=RemoteHarnessSelection.wireProtocol(worker).equals(protocol)&&!session.isEmpty()&&session.equals(thread)&&!text(source,"promptRequestId",200).isEmpty();try{RemoteHarnessSelection.checked(source.optJSONObject("harnessSelection"),worker);}catch(Exception unconfirmed){confirmed=false;}}
            else confirmed&="codex_app_server".equals(protocol);
            JSONObject identity=new JSONObject(context.toString()).put("worker",worker).put("protocol",protocol).put("threadId",thread).put("sessionId",session);
            if(!confirmed)identity.put("unknownForTask",id);
            int approvals=requests(source,"pendingApprovals"),questions=requests(source,"pendingQuestions");String status=text(source,"executorStatus",80),attention="none_observed";
            if(task.optBoolean("cancelRequested")||"stopping".equals(task.optString("status")))attention="stop_requested_unconfirmed";
            else if(approvals+questions>0)attention="input_observed";
            else if("unknown".equals(status))attention="execution_unconfirmed";
            else if("completed".equals(status))attention="completed_reported";
            else if(status.matches("failed|interrupted|cancelled"))attention="execution_stopped_reported";
            out.put("identityState",job.isEmpty()?"managed_operation_observation":"managed_job_observation").put("sourceAdapter",job.isEmpty()?"companion_remote_operation":"companion_remote_job").put("profileId",profile).put("connectionKey",connectionKey).put("serviceOrigin",originAddress).put("deviceIdentityState","not_observed_endpoint_only")
                    .put("environmentId",environment).put("workspaceFingerprint",workspace.isEmpty()?"":hash(new JSONObject().put("workspaceIdentity",workspace)))
                    .put("contextKey",hash(context)).put("worker",worker).put("protocol",protocol).put("jobId",job)
                    .put("sessionEvidence",confirmed?"native_protocol_observed":"unavailable").put("sessionKey",hash(identity))
                    .put("executorStatus",status).put("attention",new JSONObject().put("kind",attention).put("approvalsObserved",approvals).put("questionsObserved",questions).put("lastObservationOnly",true));
        }
        // Exact row cursor includes run/epoch/job and the original stored native identity, not labels.
        JSONObject cursor=new JSONObject(out.toString());cursor.remove("lastLocalUpdateAt");cursor.remove("taskStatus");cursor.remove("attention");cursor.remove("executorStatus");
        out.put("rowIdentity",hash(cursor));return out;
    }
    public static JSONObject snapshot(JSONArray rows,boolean olderLocalTasksNotScanned,int scanned)throws Exception {
        Map<String,JSONObject> devices=new LinkedHashMap<>();JSONArray unresolved=new JSONArray();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);if(!row.optString("identityState").matches("managed_job_observation|managed_operation_observation")){unresolved.put(new JSONObject(row.toString()));continue;}
            String key=row.getString("connectionKey");JSONObject device=devices.get(key);
            if(device==null){device=new JSONObject().put("connectionKey",key).put("deviceIdentityState","not_observed_endpoint_only").put("profileId",row.getString("profileId")).put("serviceOrigin",row.getString("serviceOrigin")).put("contexts",new JSONArray());devices.put(key,device);}
            JSONArray contexts=device.getJSONArray("contexts");JSONObject context=null;
            for(int j=0;j<contexts.length();j++)if(row.getString("contextKey").equals(contexts.getJSONObject(j).getString("contextKey")))context=contexts.getJSONObject(j);
            if(context==null){context=new JSONObject().put("contextKey",row.getString("contextKey")).put("environmentId",row.getString("environmentId")).put("workspaceFingerprint",row.getString("workspaceFingerprint")).put("sessions",new JSONArray());contexts.put(context);}
            context.getJSONArray("sessions").put(new JSONObject(row.toString()));
        }
        JSONArray listed=new JSONArray();for(JSONObject device:devices.values())listed.put(device);
        return new JSONObject().put("dataOnly",true).put("authorizesExecution",false).put("observation","last_stored_native_metadata_not_live").put("deviceIdentitySource","configured_companion_endpoint_not_hardware_identity")
                .put("localTaskWindow",LOCAL_TASK_WINDOW).put("localTasksScanned",scanned).put("olderLocalTasksNotScanned",olderLocalTasksNotScanned)
                .put("managedConnections",listed).put("unconfirmedLocalTasks",unresolved)
                .put("externalClaimed",new JSONObject().put("state","unavailable_no_registry_adapter"))
                .put("externalDiscovery",new JSONObject().put("state","unavailable_no_registry_adapter"));
    }
}
