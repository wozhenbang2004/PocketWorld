package dev.pocketengine.features.teams;

import dev.pocketengine.runtime.context.CompactionPlan;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure raw-protocol check only. The caller must separately prove the original SQL-issued
 * continuation, current child/control, immutable plans, approval and definitely-undispatched op.
 * JSON matching here never grants an execution, read, continuation or compaction permission. */
public final class TeamCompactionApprovalTail {
    private TeamCompactionApprovalTail(){}
    public static int requireProtected(JSONArray originalRaw,CompactionPlan plan,JSONObject originalSqlArguments,String approvalId)throws Exception {
        if(originalRaw==null||originalRaw.length()>1000||plan==null||originalSqlArguments==null||approvalId==null||approvalId.isEmpty()
                ||(originalSqlArguments.length()!=3||!originalSqlArguments.has("planRef")||!originalSqlArguments.has("planDigest")||!originalSqlArguments.has("stepId"))
                ||!plan.rawFingerprint.equals(CompactionPlan.fingerprint(originalRaw)))throw new CompactionPlan.Unavailable("approved_continuation_tail_unproven");
        return requireProtectedCoverage(originalRaw,plan.metadata(),originalSqlArguments,approvalId);
    }
    /** A stored native coverage is data, not a grant. Original source-currentness/SQL checks
     * remain the caller's responsibility; only the unchanged prefix and protected tail match. */
    public static int requireProtectedCoverage(JSONArray originalRaw,JSONObject coverage,JSONObject originalSqlArguments,String approvalId)throws Exception {
        if(originalRaw==null||originalRaw.length()>1000||coverage==null||originalSqlArguments==null||approvalId==null||approvalId.isEmpty()
                ||originalSqlArguments.length()!=3||!originalSqlArguments.has("planRef")||!originalSqlArguments.has("planDigest")||!originalSqlArguments.has("stepId"))throw new CompactionPlan.Unavailable("approved_continuation_tail_unproven");
        int cut=coverage.optInt("endExclusive",-1);JSONArray covered=coverage.optJSONArray("coveredIndexes");
        if(cut<1||cut>originalRaw.length()||covered==null||!coverage.optString("rawPrefixFingerprint").equals(CompactionPlan.fingerprint(CompactionPlan.slice(originalRaw,0,cut))))throw new CompactionPlan.Unavailable("approved_continuation_tail_unproven");
        java.util.Set<Integer> indexes=new java.util.HashSet<>();for(int i=0;i<covered.length();i++){int index=covered.getInt(i);if(index<0||index>=cut||!indexes.add(index))throw new CompactionPlan.Unavailable("approved_continuation_tail_unproven");}
        String argsHash=CompactionPlan.fingerprint(originalSqlArguments);int found=-1;
        for(int i=0;i<originalRaw.length();i++){
            JSONObject request=originalRaw.getJSONObject(i);JSONArray calls=request.optJSONArray("tool_calls");
            if(!"assistant".equals(request.optString("role"))||calls==null)continue;
            for(int c=0;c<calls.length();c++){
                JSONObject call=calls.getJSONObject(c),function=call.getJSONObject("function");
                if(!"project_execute".equals(function.optString("name"))||!(function.opt("arguments") instanceof String))continue;
                JSONObject args;try{args=new JSONObject(function.getString("arguments"));}catch(Exception malformed){continue;}
                if(!argsHash.equals(CompactionPlan.fingerprint(args)))continue;
                if(calls.length()!=1||i+1>=originalRaw.length())throw new CompactionPlan.Unavailable("approved_continuation_tail_mixed");
                JSONObject response=originalRaw.getJSONObject(i+1);if(!"tool".equals(response.optString("role"))||!call.getString("id").equals(response.optString("tool_call_id"))||!(response.opt("content") instanceof String))throw new CompactionPlan.Unavailable("approved_continuation_tail_unproven");
                JSONObject receipt;try{receipt=new JSONObject(response.getString("content"));}catch(Exception malformed){throw new CompactionPlan.Unavailable("approved_continuation_tail_unproven");}
                if(!approvalId.equals(receipt.optString("approvalId"))||!"approval_required".equals(receipt.optString("code"))
                        ||!"not_dispatched".equals(receipt.optString("status"))||!Boolean.FALSE.equals(receipt.opt("accepted"))||receipt.has("projectExecution"))throw new CompactionPlan.Unavailable("approved_continuation_tail_unproven");
                if(found>=0)throw new CompactionPlan.Unavailable("approved_continuation_tail_duplicate");found=i;
            }
        }
        // Retained-but-in-prefix is NOT enough: the full original request/receipt must stay in
        // the raw tail, outside rawPrefixFingerprint and outside the summarizer's source prefix.
        if(found<0)throw new CompactionPlan.Unavailable("approved_continuation_tail_unproven");
        if(found<cut||indexes.contains(found)||indexes.contains(found+1))throw new CompactionPlan.Unavailable("approved_continuation_tail_in_prefix");
        return found;
    }
}
