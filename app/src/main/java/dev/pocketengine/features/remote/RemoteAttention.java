package dev.pocketengine.features.remote;

import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only attention projection of the original native job. No request in a notification is authority. */
public final class RemoteAttention {
    private RemoteAttention(){}
    public static JSONObject describe(JSONObject task)throws Exception {
        if(!task.optString("feature").equals("remote")||!task.optString("actorId").equals("owner")||!task.optString("spaceId").equals("private"))return null;
        JSONObject refs=task.optJSONObject("externalRefs"),source=task.optJSONObject("evidence");if(refs==null||source==null||task.optString("externalId").isEmpty()||task.optString("runId").isEmpty()||!task.optString("operationId").equals(task.optString("runId"))
                ||!task.optString("operationId").equals(source.optString("operationId"))||!refs.optString("profileId").equals(source.optString("profileId")))return null;
        String state=source.optString("executorStatus"),kind="";JSONArray requests=new JSONArray();
        if(!task.optBoolean("cancelRequested")&&!task.optString("status").matches("cancelled|stopping")){
            for(String key:new String[]{"pendingApprovals","pendingQuestions"}){JSONArray rows=source.optJSONArray(key);for(int i=0;rows!=null&&i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);String id=row.optString("requestId");if(id.isEmpty())continue;
                requests.put(new JSONObject().put("kind",key.equals("pendingApprovals")?"approval":"question").put("requestId",id).put("requestHash",dev.pocketengine.store.TaskStore.capabilityFingerprint(row)));
            }}
            if(requests.length()>0)kind="input_required";
        }
        if(kind.isEmpty()){if(state.equals("completed"))kind="completed_reported";else if(state.matches("failed|interrupted"))kind="execution_stopped";else if(state.equals("unknown"))kind="execution_unconfirmed";else if(state.equals("cancelled"))kind="cancelled_reported";else return null;}
        JSONObject result=new JSONObject().put("kind",kind).put("taskId",task.getString("id")).put("runId",task.getString("runId")).put("coordinatorEpoch",task.getInt("coordinatorEpoch"))
                .put("profileId",refs.getString("profileId")).put("serviceBase",source.getString("serviceBase")).put("jobId",task.getString("externalId")).put("jobOperationId",task.getString("operationId")).put("requests",requests);
        return result.put("fingerprint",dev.pocketengine.store.TaskStore.capabilityFingerprint(result));
    }
}
