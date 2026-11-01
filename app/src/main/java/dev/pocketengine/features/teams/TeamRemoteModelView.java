package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded MODEL view only. The original TaskStore keeps the complete native receipt and authority. */
public final class TeamRemoteModelView {
    private TeamRemoteModelView(){}
    public static JSONObject project(JSONObject actual,JSONObject nativeReceiptRef)throws Exception {
        JSONObject out=new JSONObject().put("format","pocketworld.team-remote-model-view.v1").put("nativeResultProjection",true).put("completeOriginalResultReturned",false).put("sourceCoverage","bounded_native_status_projection");
        for(String key:new String[]{"ok","accepted","status","code","error","known","requiresPause","remoteAuthor","fullProject","commandChecksAvailable","phoneCombinedChecksVerified","operationId","jobId","jobOperationId","childTaskId","contributionId","snapshotId","snapshotVersion","checkoutId","checkoutIdentity","packageIdentity","baseCommit","gitHead","manifestHash","assignmentRevision","importedToOriginalDraft","integrated","requiresOwnerReview","businessVerified","dispatchFenceFinal","workerStarted","processStarted","notStartedConfirmed","resourcesReleased","receiptReceived","executorStatus","processExitCode","exitCode"})if(actual.has(key)){Object value=actual.get(key);if(value==JSONObject.NULL||value instanceof Boolean||value instanceof Number||value instanceof String&&((String)value).length()<=256)out.put(key,value);}
        if(actual.has("message")){String text=actual.optString("message");int end=Math.min(512,text.length());if(end>0&&end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;out.put("message",text.substring(0,end));}
        JSONArray files=actual.optJSONArray("files");if(files!=null){out.put("nativeFileCount",files.length());JSONArray visible=new JSONArray();for(int i=0;i<Math.min(8,files.length());i++){JSONObject row=files.getJSONObject(i),file=new JSONObject();for(String key:new String[]{"path","bytes","sha256","fileVersion","tombstone","artifactKind","executable"})if(row.has(key))file.put(key,row.get(key));visible.put(file);}out.put("files",visible).put("fileViewPartial",files.length()>visible.length());}
        if(actual.has("assignmentContribution")){JSONObject proof=actual.getJSONObject("assignmentContribution"),small=new JSONObject();for(String key:new String[]{"checkoutId","checkoutIdentity","baseCommit","gitHead","baseSnapshotId","baseSnapshotVersion","fullProject","authorshipVerified"})if(proof.has(key))small.put(key,proof.get(key));out.put("assignmentContribution",small);}
        // Exact small original artifact refs are still needed by the existing Runner outcome path.
        if(actual.has("teamArtifacts")){JSONArray refs=actual.getJSONArray("teamArtifacts");if(refs.length()>8||refs.toString().length()>32768)throw new SecurityException("原团队成果引用超出有界范围，不能裁成假完整交付");out.put("teamArtifacts",new JSONArray(refs.toString()));}
        if(nativeReceiptRef!=null)out.put("nativeReceiptRef",new JSONObject(nativeReceiptRef.toString())).put("nativeReceiptRetained",true);else out.put("nativeReceiptRetained",false);
        return out;
    }
}
