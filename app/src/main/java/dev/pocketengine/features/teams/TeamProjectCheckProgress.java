package dev.pocketengine.features.teams;

import org.json.JSONArray;
import org.json.JSONObject;

/** Body-free projection of one native checkout/source's actual operations. Callers supply SQL rows
 * newest first. Absence of a proven pass is never evidence that a command did not run. */
public final class TeamProjectCheckProgress {
    private final String source;
    private final JSONObject byTemplate=new JSONObject();
    private boolean complete=true;
    public TeamProjectCheckProgress(String source){this.source=source;}
    public void incomplete(){complete=false;}
    public void observed(String template,String op,String run,String physical,JSONObject qualifyingReceipt,boolean statusReadableOnThisRun)throws Exception{
        JSONObject attempt=new JSONObject().put("operationId",op).put("runId",run).put("state",physical);
        if(statusReadableOnThisRun)attempt.put("statusRead",new JSONObject().put("tool","project_status").put("executionId",op));
        JSONObject row=byTemplate.optJSONObject(template);
        if(row==null){row=new JSONObject().put("last",attempt);byTemplate.put(template,row);}
        if("not_settled".equals(physical)&&!row.has("unsettledOriginal"))row.put("unsettledOriginal",attempt);
        if(qualifyingReceipt!=null&&!row.has("verifiedReceipt"))row.put("verifiedReceipt",qualifyingReceipt);
    }
    public JSONObject view()throws Exception{
        return new JSONObject().put("sourceDigest",source).put("coverageComplete",complete).put("byTemplate",byTemplate)
                .put("missingDoesNotProveNotRun",true).put("executionAuthorized",false);
    }
    static JSONObject withEvidence(JSONObject index,JSONObject history)throws Exception{
        JSONArray rows=index.getJSONArray("templates");JSONObject states=history.getJSONObject("byTemplate");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i),evidence=states.optJSONObject(row.getString("id"));
            if(evidence!=null)row.put("sameSourceEvidence",evidence);
        }
        return index.put("observedSourceDigest",history.getString("sourceDigest")).put("historyReadComplete",history.getBoolean("coverageComplete"))
                .put("absentReceiptDoesNotProveNotRun",true);
    }
    static String template(JSONObject index,String ref)throws Exception{
        JSONArray rows=index.getJSONArray("templates");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(ref.equals(row.optString(TeamProjectCheckReferences.FIELD)))return row.getString("id");}
        throw new SecurityException("原检查已不属于本次请求");
    }
    static JSONObject existing(String template,JSONObject history)throws Exception{
        JSONObject evidence=history.getJSONObject("byTemplate").optJSONObject(template),last=evidence==null?null:evidence.optJSONObject("last");
        JSONObject proof=evidence==null?null:evidence.optJSONObject("verifiedReceipt");boolean complete=history.getBoolean("coverageComplete");
        boolean unsettled=evidence!=null&&evidence.has("unsettledOriginal");
        boolean satisfied=!unsettled&&complete&&last!=null&&proof!=null&&"verified".equals(last.optString("state"))&&last.getString("operationId").equals(proof.getString("operationId"));
        if(!unsettled&&!satisfied&&complete)return null; // A proposal is not a dispatch; original approval/eligibility still applies.
        JSONObject out=new JSONObject().put("ok",satisfied).put("accepted",satisfied).put("status",satisfied?"observed":"not_dispatched")
                .put("code",satisfied?"team_check_already_verified":unsettled?"team_check_original_not_settled":"team_check_history_incomplete")
                .put("templateId",template).put("sourceDigest",history.getString("sourceDigest")).put("historyReadComplete",complete)
                .put("newCommandDispatched",false).put("executionAuthorized",false).put("businessVerified",false)
                .put("message",satisfied?"刚核对的这版源码已有此原检查的真实成功和清场回执，不重复要求批准或执行；继续核对其余项目。":
                        unsettled?"原检查仍缺确定停点。请核对所列原操作，不能据此再派发相同检查。":"本次未能完整核对原操作，不能据此再派发；请回原任务核对。");
        if(evidence!=null)out.put("sameSourceEvidence",evidence);return out;
    }
}
