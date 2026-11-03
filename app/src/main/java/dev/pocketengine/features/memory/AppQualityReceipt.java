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
  if(now<1)throw new IllegalArgumentException("缺少实际核对时间");
  JSONObject link=MethodEffectEvidence.qualityLink(method);
  return new AppQualityReceipt(new JSONObject().put("format","pocketworld.app-quality-receipt.v2").put("taskId",method.getString("taskId")).put("runId",method.getString("runId")).put("operationId",method.getString("operationId")).put("originRef",ref).put("outcome",outcome).put("note",note.trim()).put("at",now).put("evidenceKind","owner_reviewed_original_method_steps").put("methodEffectEvidence",link)
   .put("resultVerifiedByOwner",outcome.equals("verified")).put("attributionSource","owner_review").put("causalEffectVerified",false).put("confidenceDelta",0).put("automaticallyVerified",false).put("executionAuthorized",false).put("replayAllowed",false));
 }
}
