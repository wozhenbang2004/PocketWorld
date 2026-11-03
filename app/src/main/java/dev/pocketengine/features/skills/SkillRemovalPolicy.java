package dev.pocketengine.features.skills;

import org.json.JSONObject;

/** Exact original package identity and conservative journal evidence; never permission by itself. */
public final class SkillRemovalPolicy {
    private SkillRemovalPolicy(){}
    public static JSONObject pin(JSONObject row)throws Exception{
        String id=row.getString("id"),digest=row.getString("digest");Object rev=row.get("revision"),gen=row.opt("executionGeneration");
        if(!(rev instanceof Integer||rev instanceof Long)||gen!=null&&!(gen instanceof Integer||gen instanceof Long))throw new SecurityException("原技能修订与批准代次必须为整数");
        int revision=Math.toIntExact(((Number)rev).longValue());long generation=gen==null?0:((Number)gen).longValue();
        if(!id.matches("[a-f0-9-]{36}")||!digest.matches("[a-f0-9]{64}")||revision<1||generation<0)throw new SecurityException("原技能版本定位不完整");
        return new JSONObject().put("id",id).put("revision",revision).put("digest",digest).put("executionGeneration",generation);
    }
    public static void requireSame(JSONObject expected,JSONObject current)throws Exception{
        JSONObject a=pin(expected),b=pin(current);for(String key:new String[]{"id","revision","digest","executionGeneration"})if(!a.get(key).equals(b.get(key)))throw new SecurityException("原技能版本或批准已变化，请重新审阅卸载");
    }
    public static void requireRemovable(JSONObject current,JSONObject expected)throws Exception{
        requireSame(expected,current);
        if(!"owner".equals(current.optString("ownerId"))||!"private".equals(current.optString("spaceId"))||current.has("origin")||!Boolean.FALSE.equals(current.opt("enabled")))throw new SecurityException("只可卸载已明确停用的私人安装技能；来源技能须在原来源审核页处理");
    }
    /** A terminal word or zero exit code is not evidence that a claimed process released resources. */
    public static boolean knownNeverDispatched(String state,JSONObject metadata,JSONObject receipt){
        return "receipt".equals(state)&&metadata!=null&&!metadata.has("claimedAt")&&!metadata.has("nativeManagedJob")
                &&"denied".equals(metadata.optString("approvalState"))&&"settled".equals(metadata.optString("dispatchState"))
                &&receipt!=null&&"rejected".equals(receipt.optString("status"));
    }
}
