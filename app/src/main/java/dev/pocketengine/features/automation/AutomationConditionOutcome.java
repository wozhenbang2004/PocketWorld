package dev.pocketengine.features.automation;

import org.json.JSONObject;
import org.json.JSONTokener;

/** A no-tool model answer is a condition opinion; the witness is supplied by the native
 * original read-only task, never accepted from the model's answer or a feature command. */
public final class AutomationConditionOutcome {
    public final boolean known,met;
    public final String failure,taskId,sourceKey;
    public final long sourceRevision;
    private final String witness;
    private AutomationConditionOutcome(boolean known,boolean met,String failure,String task,String source,long revision,JSONObject witness){this.known=known;this.met=met;this.failure=failure;taskId=task;sourceKey=source;sourceRevision=revision;this.witness=witness==null?null:witness.toString();}
    /** Native caller first checks task/run/epoch, read-only scope and the actual observation hash. */
    public static AutomationConditionOutcome checked(String originalTask,String sourceKey,long sourceRevision,JSONObject nativeWitness,String answer)throws Exception{
        if(originalTask==null||!originalTask.matches("[a-f0-9-]{36}")||sourceKey==null||sourceKey.isBlank()||sourceKey.length()>1000||sourceRevision<1)throw new SecurityException("条件来源或原检查任务无效");
        if(nativeWitness==null||nativeWitness.length()!=5||!originalTask.equals(nativeWitness.optString("taskId"))||!nativeWitness.optString("runId").matches("[a-f0-9-]{36}")||nativeWitness.optInt("epoch")<1||!nativeWitness.optString("observationId").matches("[A-Za-z0-9_.:-]{1,160}")||!nativeWitness.optString("sha256").matches("[a-f0-9]{64}"))throw new SecurityException("条件缺原生只读观察证明");
        if(answer==null||answer.length()>8000)return failed(originalTask,sourceKey,sourceRevision,"condition_answer_invalid");
        try{JSONTokener parser=new JSONTokener(answer);Object raw=parser.nextValue();if(!(raw instanceof JSONObject)||parser.nextClean()!=0)return failed(originalTask,sourceKey,sourceRevision,"condition_answer_invalid");JSONObject value=(JSONObject)raw;
            if(!(value.opt("met") instanceof Boolean))return failed(originalTask,sourceKey,sourceRevision,"condition_boolean_missing");for(var keys=value.keys();keys.hasNext();)if(!java.util.Set.of("met","evidence").contains(keys.next()))return failed(originalTask,sourceKey,sourceRevision,"condition_answer_fields_invalid");
            if(value.has("evidence")&&(!(value.opt("evidence") instanceof String)||value.getString("evidence").length()>1000))return failed(originalTask,sourceKey,sourceRevision,"condition_evidence_invalid");
            return new AutomationConditionOutcome(true,value.getBoolean("met"),"",originalTask,sourceKey,sourceRevision,nativeWitness);
        }catch(org.json.JSONException invalid){return failed(originalTask,sourceKey,sourceRevision,"condition_answer_invalid");}
    }
    public static AutomationConditionOutcome failed(String originalTask,String source,long revision,String code){if(code==null||!code.matches("[a-z_]{1,80}"))throw new IllegalArgumentException("条件错误代码无效");return new AutomationConditionOutcome(false,false,code,originalTask,source,revision,null);}
    JSONObject json()throws Exception{return new JSONObject().put("known",known).put("met",known?met:JSONObject.NULL).put("failure",failure).put("taskId",taskId).put("sourceKey",sourceKey).put("sourceRevision",sourceRevision).put("witness",witness==null?JSONObject.NULL:new JSONObject(witness)).put("factIndependentlyVerified",false);}
    static JSONObject definition(JSONObject input)throws Exception{
        if(input==null)return null;if(input.length()!=3||!(input.opt("goal") instanceof String)||input.getString("goal").isBlank()||input.getString("goal").length()>2000||!(input.opt("sourceKey") instanceof String)||input.getString("sourceKey").isBlank()||input.getString("sourceKey").length()>1000)throw new IllegalArgumentException("语义条件需只读goal、固定sourceKey和sourceRevision");
        long revision=AutomationPolicy.integer(input,"sourceRevision",1,Long.MAX_VALUE);return new JSONObject().put("goal",input.getString("goal")).put("sourceKey",input.getString("sourceKey")).put("sourceRevision",revision);
    }
}
