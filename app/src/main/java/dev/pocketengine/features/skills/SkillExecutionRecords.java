package dev.pocketengine.features.skills;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Body-free persisted original-operation identity. Decoding this value cannot launch a script. */
public final class SkillExecutionRecords {
    private SkillExecutionRecords(){}
    public static JSONObject reference(CompanionSkillBackend.ReceiptRef r)throws Exception {
        return new JSONObject().put("ownerId",r.ownerId).put("spaceId",r.spaceId).put("taskId",r.taskId).put("runId",r.runId).put("epoch",r.epoch)
                .put("operationId",r.operationId).put("planDigest",r.planDigest).put("environmentBinding",r.environmentBinding)
                .put("installationId",r.installationId).put("workspaceId",r.workspaceId).put("workspaceRevision",r.workspaceRevision)
                .put("maxOutputBytes",r.maxOutputBytes).put("maxResultChars",r.maxResultChars);
    }
    public static CompanionSkillBackend.ReceiptRef reference(JSONObject r)throws Exception {
        return new CompanionSkillBackend.ReceiptRef(r.getString("ownerId"),r.getString("spaceId"),r.getString("taskId"),r.getString("runId"),r.getInt("epoch"),
                r.getString("operationId"),r.getString("planDigest"),r.getString("environmentBinding"),r.getString("installationId"),r.getString("workspaceId"),r.getString("workspaceRevision"),r.getInt("maxOutputBytes"),r.getInt("maxResultChars"));
    }
    public static JSONObject deployment(CompanionSkillBackend.Deployment d)throws Exception {
        return new JSONObject().put("profileId",d.profileId).put("serviceBase",d.serviceBase).put("environmentId",d.environmentId).put("installationId",d.installationId)
                .put("commandAlias",d.commandAlias).put("policySha256",d.policySha256).put("readySha256",d.readySha256).put("helperSha256",d.helperSha256);
    }
    public static CompanionSkillBackend.Deployment deployment(JSONObject d)throws Exception {
        return new CompanionSkillBackend.Deployment(d.getString("profileId"),d.getString("serviceBase"),d.getString("environmentId"),d.getString("installationId"),d.getString("commandAlias"),d.getString("policySha256"),d.getString("readySha256"),d.getString("helperSha256"));
    }
    /** The journal has a 12k text-field limit. Preserve hashes/byte counts; never label a clipped value exact. */
    public static JSONObject receipt(CompanionSkillBackend.ReceiptRef ref,SkillExecution.Receipt receipt)throws Exception {
        if(receipt==null||!ref.operationId.equals(receipt.operationId))receipt=SkillExecution.Receipt.unknown(ref.operationId);
        byte[] raw=receipt.outputBytes(),bounded=Arrays.copyOf(raw,Math.min(raw.length,ref.maxOutputBytes));
        String text=new String(bounded,StandardCharsets.UTF_8);int end=Math.min(text.length(),Math.min(10000,ref.maxResultChars-1800));
        if(end>0&&end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
        return new JSONObject().put("taskId",ref.taskId).put("runId",ref.runId).put("epoch",ref.epoch).put("operationId",ref.operationId)
                .put("planDigest",ref.planDigest).put("backendReceiptId",receipt.backendReceiptId).put("status",receipt.status.name().toLowerCase(java.util.Locale.ROOT))
                .put("exitCode",receipt.exitCode==null?JSONObject.NULL:receipt.exitCode).put("output",text.substring(0,end)).put("outputEncoding","utf8_lossy")
                .put("outputSha256",SkillExecution.sha256(bounded)).put("outputBytes",bounded.length).put("outputTruncated",receipt.outputTruncated()||bounded.length<raw.length||end<text.length())
                .put("untrustedOutput",true).put("businessVerified",false).put("replayAllowed",false).put("workspaceMode","isolated_copy").put("fileResultsRequireImport",true)
                .put("message",receipt.status==SkillExecution.Status.UNKNOWN?"原操作结果尚未确认；只能核对原回执，没有重跑脚本":"已收到执行端回执，请核对任务目标与实际文件成果");
    }
    public static boolean terminal(SkillExecution.Status state){return state!=SkillExecution.Status.ACCEPTED&&state!=SkillExecution.Status.RUNNING&&state!=SkillExecution.Status.UNKNOWN;}
    /** Projection of the sole original task record; no new execution or artifact authority. */
    public static JSONObject artifactAvailability(JSONObject original)throws Exception {
        JSONObject review=original.optJSONObject("review"),request=review==null?null:review.optJSONObject("request"),receipt=original.optJSONObject("receipt");
        JSONArray requested=request==null?null:request.optJSONArray("outputPaths"),files=receipt==null?null:receipt.optJSONArray("artifacts");
        JSONArray missing=receipt==null?null:receipt.optJSONArray("missingArtifactPaths");
        int expected=requested==null?0:requested.length(),saved=files==null?0:files.length();
        String receiptState=receipt==null?"":receipt.optString("status");
        boolean claimed=original.has("claimedAt"),eligible=claimed&&receiptState.matches("completed|failed|timed_out");
        boolean inventory=files!=null&&missing!=null&&receipt.has("artifactsTruncated"),truncated=receipt!=null&&receipt.optBoolean("artifactsTruncated");
        String state=!claimed?"not_dispatched":receiptState.matches("cancelled|rejected")?"not_available":!eligible?"awaiting_receipt":expected==0?"not_requested":!inventory?"not_retrieved":
                saved==expected&&missing.length()==0&&!truncated?"available":saved>0?"partial":"missing";
        return new JSONObject().put("state",state).put("requestedCount",expected).put("savedCount",saved)
                .put("missingPaths",missing==null?new JSONArray():new JSONArray(missing.toString())).put("truncated",truncated)
                .put("canRetrieve",eligible&&expected>0&&!inventory).put("canRecheck",eligible&&expected>0)
                .put("replayAllowed",false).put("businessVerified",false);
    }
    /** The model receives bounded text/metadata; original file bytes stay in the original task store. */
    public static JSONObject forModel(JSONObject receipt,int maxChars)throws Exception {
        JSONObject value=new JSONObject(receipt.toString());var files=value.optJSONArray("artifacts");value.remove("artifacts");value.put("artifactCount",files==null?0:files.length()).put("artifactsInOriginalTask",true);
        value.remove("outputBase64");String output=value.optString("output");value.put("output","");int low=0,high=output.length();
        while(low<high){int middle=(low+high+1)/2;value.put("output",output.substring(0,middle));if(value.toString().length()<=maxChars)low=middle;else high=middle-1;}
        if(low>0&&low<output.length()&&Character.isHighSurrogate(output.charAt(low-1)))low--;
        value.put("output",output.substring(0,low)).put("outputTruncated",value.optBoolean("outputTruncated")||low<output.length());
        if(value.toString().length()>maxChars)throw new IllegalStateException("原回执元数据超过返回预算");return value;
    }
}
