package dev.pocketengine.runtime;

import dev.pocketengine.runtime.context.CompactionPlan;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Advisory for this live Runner invocation only; no ledger, replay, timeout or execution policy.
 * Retains one consecutive call/receipt digest pair, never argument or receipt body mirrors. */
final class RepeatToolNotice {
    static final int THRESHOLD=3,MAX_REFERENCE_BYTES=1024;
    private String ownerBoundary="",callDigest="",receiptDigest="";
    private int repeated;
    private boolean notified,pending;

    /** Only already admitted native owner rows count; observations/host references do not. */
    void ownerBoundary(JSONArray history){
        try {
            String current="";
            for(int i=history.length()-1;i>=0;i--){JSONObject row=history.getJSONObject(i);
                if("user".equals(row.optString("role"))&&(!row.optString("ownerMessageId").isEmpty()||Boolean.TRUE.equals(row.opt("ownerRecallFeedback")))){
                    current=CompactionPlan.fingerprint(row);break;
                }
            }
            if(!current.equals(ownerBoundary)){clear();ownerBoundary=current;}
        }catch(Exception unavailable){clear();}
    }

    /** Identical success ACKs are not evidence of no progress. Unknown effects never enter here. */
    void afterTool(String tool,JSONObject arguments,JSONObject receipt,boolean nativeReadOnly){
        try {
            String category=tool.toLowerCase(Locale.ROOT),code=receipt.optString("code",receipt.optString("error")).toLowerCase(Locale.ROOT),status=receipt.optString("status").toLowerCase(Locale.ROOT);
            boolean definiteRefusal=Boolean.FALSE.equals(receipt.opt("accepted"))
                    &&(Boolean.FALSE.equals(receipt.opt("nativeDispatched"))||"not_dispatched".equals(receipt.optString("effectStatus")));
            if(category.contains("poll")||category.contains("status")||category.contains("wait")||category.contains("watch")||category.contains("log")
                    ||category.contains("getjob")||category.contains("get_job")||category.contains("listjobs")||category.contains("list_jobs")||category.contains("receipt")
                    ||!Boolean.FALSE.equals(receipt.opt("ok"))||(!nativeReadOnly&&!definiteRefusal)
                    ||code.isEmpty()||code.contains("unknown")||code.contains("timeout")||code.contains("running")
                    ||status.equals("unknown")||status.equals("running")||receipt.optBoolean("requiresReconciliation")
                    ||receipt.optBoolean("requiresPause")||receipt.has("accepted")&&receipt.isNull("accepted")) {clear();return;}
            String call=CompactionPlan.fingerprint(new JSONObject().put("tool",tool).put("arguments",arguments)),result=CompactionPlan.fingerprint(receipt);
            if(!call.equals(callDigest)||!result.equals(receiptDigest)){clear();callDigest=call;receiptDigest=result;}
            repeated=Math.min(THRESHOLD,repeated+1);
            if(repeated==THRESHOLD&&!notified){pending=true;notified=true;}
        }catch(Exception unavailable){clear();} // Optional diagnostics cannot replace a real receipt.
    }

    /** Once per chain, in the next ordinary request only. No extra inference or persisted message. */
    String referenceData(){
        if(!pending)return "";pending=false;
        try {
            String reference="同一工具同参连续收到相同失败回执；这只是无新增结果的软提示，不证明业务效果，也不要求停止合法长任务。请核对原回执与目标，不要盲目重试；未知效果须沿原任务核对。\n<native_repeat_tool_notice>\n"
                    +new JSONObject().put("format","pocketworld.repeat-tool-notice.v1").put("consecutiveFailures",repeated)
                    .put("evidence","same_arguments_and_same_failure_receipt").put("argumentsIncluded",false).put("receiptBodyIncluded",false)
                    .put("executionAuthorized",false).put("automaticReplay",false).put("budgetsReset",false)+"\n</native_repeat_tool_notice>";
            return reference.getBytes(StandardCharsets.UTF_8).length<=MAX_REFERENCE_BYTES?reference:"";
        }catch(Exception unavailable){return "";}
    }
    private void clear(){callDigest="";receiptDigest="";repeated=0;notified=false;pending=false;}
}
