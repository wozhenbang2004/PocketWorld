package dev.pocketengine.features.skills;

import org.json.JSONObject;

/** Native owner feedback on an original execution, never a model success claim or tool grant. */
public final class SkillQualityReceipt {
    private final String json;
    private SkillQualityReceipt(JSONObject value) { json=value.toString(); }
    public JSONObject snapshot() throws Exception { return new JSONObject(json); }
    /** Only TaskStore's revalidated original reference decision may enter this native factory. */
    public static SkillQualityReceipt fromReferenceDecision(JSONObject fact,String outcome,String note,long now)throws Exception {
        if(!"pocketworld.skill-reference-use.v1".equals(fact.optString("format"))||fact.getJSONArray("stepRefs").length()<1)throw new SecurityException("缺少原步骤参考采用证明");
        if(!java.util.Set.of("verified","skill_failed","verifier_rejected","environment","cancelled","model_error").contains(outcome)||note==null||note.isBlank()||note.length()>1000)throw new IllegalArgumentException("请选择结果并说明原任务核对依据");
        JSONObject pack=fact.getJSONObject("package"),value=new JSONObject().put("taskId",fact.getString("taskId")).put("runId",fact.getString("runId")).put("operationId",fact.getString("operationId"))
            .put("ownerId",fact.getString("ownerId")).put("spaceId",fact.getString("spaceId")).put("skillId",pack.getString("id")).put("revision",pack.getInt("revision")).put("digest",pack.getString("digest"))
            .put("executionGeneration",pack.getLong("executionGeneration")).put("outcome",outcome).put("note",note.trim()).put("at",now).put("evidenceKind","owner_reviewed_original_reference_steps").put("automaticallyVerified",false).put("executionAuthorized",false);
        if(pack.optJSONObject("origin")!=null)value.put("originRef",new JSONObject(pack.getJSONObject("origin").getJSONObject("ref").toString()));return new SkillQualityReceipt(value);
    }
    /** Caller must read original from TaskStore, not a feature-command payload. */
    public static SkillQualityReceipt fromOriginal(JSONObject original,String outcome,String note,long now)throws Exception {
        if(!java.util.Set.of("verified","skill_failed","verifier_rejected","environment","cancelled","model_error").contains(outcome))throw new IllegalArgumentException("请选择可归责的使用结果");
        if(note==null||note.trim().isEmpty()||note.length()>1000)throw new IllegalArgumentException("请说明实际核对的结果与归责依据（1至1000字）");
        JSONObject ref=original.getJSONObject("reference"),pack=original.getJSONObject("review").getJSONObject("package"),receipt=original.getJSONObject("receipt");
        if(!"owner".equals(ref.getString("ownerId"))||!"private".equals(ref.getString("spaceId"))||!original.has("claimedAt"))throw new SecurityException("没有私人任务的原执行证据");
        for(String key:new String[]{"taskId","runId","operationId","planDigest"})if(!ref.getString(key).equals(receipt.getString(key)))throw new SecurityException("原执行回执身份不一致");
        if(ref.getInt("epoch")!=receipt.getInt("epoch")||receipt.optString("backendReceiptId").isBlank()||!receipt.optString("status").matches("completed|failed|timed_out|cancelled"))throw new SecurityException("执行尚无确定终态，不能归责技能");
        if(outcome.equals("verified")&&!receipt.getString("status").equals("completed"))throw new SecurityException("非完成的执行不能记录成功核对");
        if(receipt.getString("status").equals("cancelled")&&!outcome.equals("cancelled"))throw new SecurityException("取消不是技能失败或成功证据");
        JSONObject value=new JSONObject().put("taskId",ref.getString("taskId")).put("runId",ref.getString("runId")).put("operationId",ref.getString("operationId"))
            .put("ownerId",ref.getString("ownerId")).put("spaceId",ref.getString("spaceId")).put("skillId",pack.getString("id")).put("revision",pack.getInt("revision"))
            .put("digest",pack.getString("digest")).put("executionGeneration",pack.optLong("executionGeneration",0)).put("outcome",outcome).put("note",note.trim()).put("at",now)
            .put("evidenceKind","owner_reviewed_original_execution").put("automaticallyVerified",false).put("executionAuthorized",false);
        if(pack.optJSONObject("origin")!=null)value.put("originRef",new JSONObject(pack.getJSONObject("origin").getJSONObject("ref").toString()));
        return new SkillQualityReceipt(value);
    }
}
