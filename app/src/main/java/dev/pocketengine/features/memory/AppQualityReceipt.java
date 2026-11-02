package dev.pocketengine.features.memory;
import org.json.JSONObject;
/** Owner judgement on a revalidated original method application; no model mutation entry. */
public final class AppQualityReceipt {
 private final String json;
 private AppQualityReceipt(JSONObject fact){json=fact.toString();}
 public JSONObject snapshot()throws Exception{return new JSONObject(json);}
 public static AppQualityReceipt fromOriginalMethod(JSONObject method,String outcome,String note,long now)throws Exception {
  if(!"pocketworld.learned-method-decision.v1".equals(method.optString("format"))||!"apply".equals(method.optString("decision"))||method.getJSONArray("stepRefs").length()<1||!"owner".equals(method.optString("ownerId"))||!"private".equals(method.optString("spaceId")))throw new SecurityException("没有原私人任务准确方法版本与采用步骤");
  if(!java.util.Set.of("verified","skill_failed","verifier_rejected","environment","cancelled","model_error").contains(outcome)||note==null||note.isBlank()||note.length()>1000)throw new IllegalArgumentException("请选择归责并说明核对依据");
  JSONObject ref=new JSONObject().put("recordId",method.getString("recordId")).put("revision",method.getLong("recordRevision")).put("ownerId","owner").put("spaceId","private");
  return new AppQualityReceipt(new JSONObject().put("taskId",method.getString("taskId")).put("runId",method.getString("runId")).put("operationId",method.getString("operationId")).put("originRef",ref).put("outcome",outcome).put("note",note.trim()).put("at",now).put("evidenceKind","owner_reviewed_original_method_steps").put("automaticallyVerified",false).put("executionAuthorized",false));
 }
}
