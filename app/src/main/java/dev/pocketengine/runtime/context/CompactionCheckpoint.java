package dev.pocketengine.runtime.context;

import org.json.JSONArray;
import org.json.JSONObject;

/** Helpers for the original runs.checkpoint column. Never an archive, authority, or separate ledger. */
public final class CompactionCheckpoint {
    private CompactionCheckpoint(){}
    /** Caller must recheck the real route/input/approval/operation/source barriers in its short transaction. */
    public static JSONObject append(JSONObject previous,JSONObject next,boolean nativeBarriersCurrent)throws Exception {
        JSONObject result=new JSONObject(next.toString());result.remove("contextCompaction");
        JSONObject old=previous==null?null:previous.optJSONObject("contextCompaction");
        if(nativeBarriersCurrent&&old!=null&&sourcesCurrent(old,result.optJSONArray("messages")))result.put("contextCompaction",new JSONObject(old.toString()));
        return result;
    }
    public static JSONObject publish(JSONObject original,CompactionJournal.FrozenPrefix expected,SummaryDraft actual,String projectionId)throws Exception {
        if(!actual.belongsTo(expected)||projectionId==null||projectionId.isEmpty())throw new SecurityException("不是原摘要候选");
        JSONObject result=new JSONObject(original.toString()),projection=actual.toJson().put("projectionId",projectionId);
        if(!sourcesCurrent(projection,result.optJSONArray("messages")))throw new SecurityException("摘要原件在提交前已改变");
        result.put("contextCompaction",projection);return result;
    }
    /** Restore exposes an audit projection only when its exact raw prefix still exists; no run authority is restored. */
    public static JSONObject restore(JSONObject checkpoint,boolean nativeBarriersCurrent)throws Exception {
        JSONObject projection=checkpoint==null?null:checkpoint.optJSONObject("contextCompaction");
        return nativeBarriersCurrent&&projection!=null&&sourcesCurrent(projection,checkpoint.optJSONArray("messages"))?new JSONObject(projection.toString()):null;
    }
    public static boolean sourcesCurrent(JSONObject projection,JSONArray raw)throws Exception {
        if(raw==null)return false;JSONObject coverage=projection.optJSONObject("coverage"),guard=projection.optJSONObject("guard");JSONArray sources=projection.optJSONArray("sources");
        if(coverage==null||guard==null||sources==null||coverage.optInt("summaryLayers")!=1||!Boolean.TRUE.equals(coverage.opt("rawRetained")))return false;
        int end=coverage.optInt("endExclusive",-1);if(end<1||end>raw.length()||!coverage.optString("rawPrefixFingerprint").equals(CompactionPlan.fingerprint(CompactionPlan.slice(raw,0,end))))return false;
        JSONArray covered=coverage.optJSONArray("coveredIndexes");if(covered==null||sources.length()!=covered.length())return false;
        for(int i=0;i<sources.length();i++){
            int index=covered.getInt(i);JSONObject source=sources.getJSONObject(i);
            if(index<0||index>=end||!("task:"+guard.optString("taskId")+":run:"+guard.optString("runId")+":checkpoint-message:"+index).equals(source.optString("sourceId"))
                    ||!("sha256:"+CompactionPlan.fingerprint(raw.getJSONObject(index))).equals(source.optString("revision")))return false;
        }
        return true;
    }
}
