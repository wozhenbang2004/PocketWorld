package dev.pocketengine.features.local.project;

import dev.pocketengine.core.RunOutcome;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only a human-selected saved revision. The approved live model and dedicated draft row keep their real bytes;
 * generic diagnostics, cold checkpoints and permission transcripts are not manuscript stores. */
public final class OwnerRevisionRunPrivacy {
    public static final String MANUAL_SCOPE_BLOCK="你的规则要求复核带参数的准确调用；这次没有执行，也没有把选稿写入通用审批记录或交给审核模型。";
    private OwnerRevisionRunPrivacy() {}
    public static boolean active(JSONObject task) {
        JSONObject scope=task==null?null:task.optJSONObject("developmentContext");
        return scope!=null && scope.has(OwnerRevisionSource.FIELD);
    }
    /** Keep protocol messages and observations on this journal instance while its original runner is alive. */
    public static final class LiveCheckpoint {
        private static final String MARKER="ownerSelectedRevisionMemoryOnly";
        private String identity,current;
        public JSONObject durable(JSONObject full)throws Exception {
            JSONObject record=new JSONObject().put(MARKER,java.util.UUID.randomUUID().toString()).put("steps",full.getInt("steps")).put("messages",new JSONArray()).put("observation",new JSONObject());
            if(full.has("progressGuard"))record.put("progressGuard",full.getJSONObject("progressGuard")); // validated native hashes/counters only
            // OwnerInputQueue.checkpoint receives full live membership inside the same original SQL write.
            return record;
        }
        public void committed(JSONObject stored,String encoded)throws Exception{identity=stored.getString(MARKER);current=encoded;}
        public JSONObject restore(JSONObject record)throws Exception{
            if(!record.has(MARKER)){
                // Only an empty, never-run bootstrap may pass. Do not replay a legacy plaintext checkpoint.
                JSONArray messages=record.optJSONArray("messages");JSONObject observation=record.optJSONObject("observation");
                if(record.optInt("steps")!=0 || record.has("messages")&&(messages==null||messages.length()!=0) || record.has("observation")&&(observation==null||observation.length()!=0))throw cold();
                return record;
            }
            if(current==null||!identity.equals(record.opt(MARKER)))throw cold();
            JSONObject full=new JSONObject(current);
            for(String k:new String[]{"messages","observation","progressGuard"})if(full.has(k))record.put(k,full.get(k));
            return record;
        }
        private static IllegalStateException cold(){return new IllegalStateException("这次选稿执行的临时上下文已结束。可回原交办查看已保存的差异；没有自动重发。");}
    }
    /** Receives native Runner events, not arbitrary task metadata or accounting rows. */
    public static JSONObject event(JSONObject source)throws Exception{
        JSONObject out=new JSONObject().put("privateDraftMetadataOnly",true);
        // The call ID, timestamps and outcome still correlate both ends of the original invocation.
        for(String k:new String[]{"type","traceVersion","source","role","stage","outcome","status","taskId","runId","coordinatorEpoch","callId","parentCallId","parentId","turnId","turnIndex","stepIndex","stepId","requestId","traceCallId","nativeOperationId","operationId","startedAt","finishedAt","timestamp","elapsedNanos","evidenceOnly","dispatchEntered","effectPending"})
            if(source.has(k))out.put(k,source.get(k));
        String tool=source.optString("tool");if(ProjectEditDraft.TOOLS.contains(tool))out.put("tool",tool);
        JSONObject receipt=source.optJSONObject("receipt");String type=source.optString("type");
        if("tool".equals(type)&&receipt!=null){
            JSONObject facts=new JSONObject().put("privateDraftMetadataOnly",true);
            for(String k:new String[]{"ok","accepted","terminal","draftSaved","nativeDispatched","complete","requiresReconciliation"})if(receipt.opt(k) instanceof Boolean)facts.put(k,receipt.get(k));
            out.put("receipt",facts);
        }
        if("tool_trace".equals(type)&&ProjectEditDraft.TOOLS.contains(tool)&&source.optJSONObject("schema")!=null)
            out.put("schema",source.getJSONObject("schema")).put("schemaSanitized",true).put("schemaSource","native_selected_tool_definition");
        return out;
    }
    /** The host presented only the selected file for this goal. Never publish a different generic action. */
    public static JSONArray catalog(JSONArray visible)throws Exception {
        JSONArray selected=new JSONArray();
        for(int i=0;i<visible.length();i++)if(ProjectEditDraft.TOOLS.contains(visible.getJSONObject(i).getJSONObject("function").getString("name")))selected.put(visible.get(i));
        return selected;
    }
    public static boolean offered(JSONObject answer,JSONArray visible)throws Exception {
        if(answer.isNull("tool_calls"))return true;JSONArray calls=answer.optJSONArray("tool_calls");if(calls==null)return false;
        java.util.Set<String> names=new java.util.HashSet<>();for(int i=0;i<visible.length();i++)names.add(visible.getJSONObject(i).getJSONObject("function").getString("name"));
        for(int i=0;i<calls.length();i++){JSONObject call=calls.optJSONObject(i),fn=call==null?null:call.optJSONObject("function");if(fn==null||!names.contains(fn.optString("name")))return false;}return true;
    }
    /** Never turn model-quoted original text, raw blocked declarations or partial streams into a public result. */
    private static String missing(String reply){
        return switch(reply==null?"":reply.strip()){
            case "NEED_TARGET_DETAIL","NEED_EXPECTED_RESULT","NEED_OTHER_FILE"->reply.strip();default->"";};
    }
    public static RunOutcome outcome(RunOutcome settled,boolean nativeArtifactSettlement,String modelReply)throws Exception{
        JSONObject evidence=new JSONObject().put("draftOnly",true);String summary;
        String reason=missing(nativeArtifactSettlement?modelReply:settled.evidence.optString("privateDraftNeeds"));
        if(nativeArtifactSettlement || !reason.isEmpty() || Boolean.FALSE.equals(settled.evidence.opt("draftSaved"))){
            boolean saved=Boolean.TRUE.equals(settled.evidence.opt("draftSaved"));evidence.put("draftSaved",saved);
            if(saved && settled.evidence.optString("draftId").matches("[a-f0-9]{64}"))evidence.put("draftId",settled.evidence.getString("draftId"));
            summary=saved?settled.summary:switch(reason){
                case "NEED_TARGET_DETAIL"->"这次没有出稿。请在原私聊补充要改的那一段和具体改法，再选这份稿。";
                case "NEED_EXPECTED_RESULT"->"这次没有出稿。请在原私聊补一个修改后的示例或验收条件，再选这份稿。";
                case "NEED_OTHER_FILE"->"这次没有出稿：单凭所选文件不足以完成。请在原私聊交代它与另一文件的约定；若要改另一文件，请明确另选那份稿。没有读取工程。";
                default->"这次没有保存可核对的新稿。请在原私聊指出这份文件要改的位置和预期变化；没有写回原件。";};
            if(!saved&&!reason.isEmpty())evidence.put("privateDraftNeeds",reason);
        }else if(MANUAL_SCOPE_BLOCK.equals(settled.summary) || Boolean.TRUE.equals(settled.evidence.opt("privateDraftApprovalBlocked"))){
            evidence.put("privateDraftApprovalBlocked",true);summary=MANUAL_SCOPE_BLOCK+"请在原交办核对权限或选择自己编辑。";
        }else summary=switch(settled.status){
            case "awaiting_user","needs_confirmation","blocked"->"这次续稿仍待核对。请查看原任务的权限或步骤记录；文字答复不代表已经保存新稿。";
            case "cancelled"->"这次续稿已停止，没有重发。实际保存的差异仍可从原交办打开。";
            default->"这次续稿未确认完成。可在原交办核对实际保存的差异；没有自动重发。";};
        return new RunOutcome(settled.status,summary,evidence.put("modelSummary",summary));
    }
}
