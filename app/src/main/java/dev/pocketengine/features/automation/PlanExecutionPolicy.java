package dev.pocketengine.features.automation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pure plan definition/approval arithmetic. No task store, runner, file or scheduling loop. */
public final class PlanExecutionPolicy {
    private PlanExecutionPolicy(){}
    /** Native preparation view for one actual member step; this grants no execution. */
    public static JSONObject executionStep(JSONObject plan,String stepId)throws Exception {
        JSONArray steps=plan.getJSONArray("steps");String id=plan.getString("id");
        for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i);if(!stepId.equals(step.optString("id")))continue;
            if(!id.equals(step.optString("planId")))throw new IllegalArgumentException("准备步骤不属于这个目标");
            return new JSONObject(plan.toString()).put("nextStep",new JSONObject(step.toString()));
        }
        throw new IllegalArgumentException("准备步骤已不在当前目标中，请刷新后重试");
    }
    static JSONObject settings(JSONObject input)throws Exception {
        for(Iterator<String> keys=input.keys();keys.hasNext();){String key=keys.next();
            if(!key.matches("mode|maxTaskAdmissions|phoneMode|modelObservationConsent|allowPrivateArtifactFreeze|businessEventConsent|zoneId|quietHours|expiresAt|requiresNetwork|requiresCharging|minBatteryPercent"))throw new IllegalArgumentException("不支持的计划执行设置："+key);
        }
        String mode=text(input,"mode",20);if(!mode.matches("manual|automatic"))throw new IllegalArgumentException("计划触发应为manual或automatic");
        long maximum=AutomationPolicy.integer(input,"maxTaskAdmissions",1,1000);
        String phoneMode=input.optString("phoneMode","background");if(!phoneMode.matches("background|main_screen"))throw new IllegalArgumentException("请选择明确手机执行位置");
        if(input.has("modelObservationConsent")&&!(input.opt("modelObservationConsent") instanceof Boolean))throw new IllegalArgumentException("模型观察同意必须为明确布尔值");
        if(input.has("allowPrivateArtifactFreeze")&&!(input.opt("allowPrivateArtifactFreeze") instanceof Boolean))throw new IllegalArgumentException("私人文件成果范围必须为明确布尔值");
        if(input.has("businessEventConsent")&&!(input.opt("businessEventConsent") instanceof Boolean))throw new IllegalArgumentException("认证来源触发须由主人明确确认");
        JSONObject policy=new JSONObject(input.toString());policy.remove("mode");policy.remove("maxTaskAdmissions");
        policy.remove("phoneMode");policy.remove("modelObservationConsent");policy.remove("allowPrivateArtifactFreeze");policy.remove("businessEventConsent");
        return new JSONObject().put("mode",mode).put("maxTaskAdmissions",maximum).put("phoneMode",phoneMode).put("modelObservationConsent",Boolean.TRUE.equals(input.opt("modelObservationConsent"))).put("allowPrivateArtifactFreeze",Boolean.TRUE.equals(input.opt("allowPrivateArtifactFreeze"))).put("businessEventConsent",Boolean.TRUE.equals(input.opt("businessEventConsent"))).put("policy",AutomationPolicy.rule(policy,"UTC"));
    }
    static JSONArray dependencies(JSONObject step,JSONArray previousIds)throws Exception {
        JSONArray input=step.optJSONArray("dependsOn");
        if(step.has("dependsOn")&&input==null)throw new IllegalArgumentException("计划依赖必须为数组");
        if(input==null){JSONArray result=new JSONArray();for(int i=0;i<previousIds.length();i++)result.put(new JSONObject().put("stepId",previousIds.getString(i)).put("outcome","completed"));return result;}
        if(input.length()>50)throw new IllegalArgumentException("计划依赖最多50条");
        JSONArray result=new JSONArray();Set<String> unique=new HashSet<>();
        for(int i=0;i<input.length();i++){
            JSONObject ref=input.getJSONObject(i);if(ref.length()!=2||!ref.has("step")||!ref.has("outcome"))throw new IllegalArgumentException("依赖只接受step序号和outcome");
            int index=(int)AutomationPolicy.integer(ref,"step",0,Math.max(0,previousIds.length()-1));if(index>=previousIds.length())throw new IllegalArgumentException("依赖只能引用同计划中的前置步骤");
            String id=previousIds.getString(index),outcome=text(ref,"outcome",20);if(!outcome.matches("completed|failed|cancelled|settled")||!unique.add(id))throw new IllegalArgumentException("依赖结果无效或重复");
            result.put(new JSONObject().put("stepId",id).put("outcome",outcome));
        }
        return result;
    }
    static String signature(JSONObject plan,JSONArray steps)throws Exception {
        JSONArray definitions=new JSONArray();Set<String> prior=new HashSet<>();
        for(int i=0;i<steps.length();i++){
            JSONObject step=steps.getJSONObject(i);String id=step.getString("id"),goal=text(step,"goal",4000);JSONArray deps=step.optJSONArray("dependencies");
            String executor=step.optString("executor","phone");JSONObject verification=PlanArtifactVerifier.standard(step.optJSONObject("verification"));
            if(!executor.matches("phone|workspace")||executor.equals("workspace")&&!verification.getString("kind").equals("workspace_artifact"))throw new IllegalArgumentException("文件执行步骤需要可核验的真实项目文件标准");
            if(deps==null)throw new IllegalStateException("执行步骤缺少精确依赖定义");
            JSONArray normalized=new JSONArray();Set<String> unique=new HashSet<>();
            for(int j=0;j<deps.length();j++){
                JSONObject ref=deps.getJSONObject(j);String parent=ref.getString("stepId"),outcome=ref.getString("outcome");
                if(ref.length()!=2||!prior.contains(parent)||!unique.add(parent)||!outcome.matches("completed|failed|cancelled|settled"))throw new IllegalStateException("执行计划依赖无效、跨计划或成环");
                normalized.put(new JSONObject().put("stepId",parent).put("outcome",outcome));
            }
            definitions.put(new JSONObject().put("id",id).put("goal",goal).put("title",step.getString("title")).put("details",step.getString("details"))
                    .put("executor",executor).put("verification",verification).put("dueAt",step.getLong("dueAt")).put("expiresAt",step.opt("expiresAt")).put("dependencies",normalized));prior.add(id);
            if(step.has("remoteWait")&&!step.isNull("remoteWait"))definitions.getJSONObject(i).put("remoteWait",PlanRemoteWait.standard(step.getJSONObject("remoteWait")));
            if(step.has("businessWait")&&!step.isNull("businessWait"))definitions.getJSONObject(i).put("businessWait",PlanBusinessWait.standard(step.getJSONObject("businessWait")));
        }
        JSONObject definition=new JSONObject().put("id",plan.getString("id")).put("goal",text(plan,"goal",4000)).put("steps",definitions);
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(definition.toString().getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();
    }
    static boolean terminal(JSONObject step){return step.optString("status").matches("completed|failed|cancelled|skipped");}
    static boolean matchesOutcome(String status,String wanted){return wanted.equals("settled")?status.matches("completed|failed|cancelled|skipped"):status.equals(wanted);}
    static String text(JSONObject input,String key,int max){Object raw=input.opt(key);if(!(raw instanceof String)||((String)raw).trim().isEmpty()||((String)raw).length()>max)throw new IllegalArgumentException(key+"必须为1至"+max+"字的文字");return ((String)raw).trim();}
}
