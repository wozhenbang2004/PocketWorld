package dev.pocketengine.store;

import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner/task policy. This value is never parsed as authority from model or channel input. */
public final class TaskExecutionScope {
    public static final String FORMAT="pocketworld.task-execution-scope.v1";
    public static final int MAX_TOOLS=400,MAX_MODEL_CALLS=512;
    private static final java.util.Set<String> PLANNING_KEYS=java.util.Set.of("executionPhase","planRevision","planArtifactRef","planHash","planApproval");
    private static final java.util.Set<String> APPROVAL_KEYS=java.util.Set.of("format","approvalId","taskId","runId","epoch","scopeRevision","planRevision","planArtifactRef","planHash","approvedAt");
    private TaskExecutionScope(){}
    public static long effectiveDeadline(JSONObject scope)throws Exception {
        if(scope==null||!FORMAT.equals(scope.optString("format"))||!"owner".equals(scope.optString("principalId"))
                ||!"private".equals(scope.optString("spaceId"))||!"active".equals(scope.optString("state")))throw new SecurityException("原任务范围无效");
        Object raw=scope.get("deadlineAt");if(!(raw instanceof Long||raw instanceof Integer)||((Number)raw).longValue()<0)throw new SecurityException("原任务期限无效");
        long deadline=((Number)raw).longValue();
        if(!scope.getString("lifetime").equals(deadline==0?"until_complete_or_stopped_or_budget":"deadline"))throw new SecurityException("原任务期限策略不一致");
        int tools=exactInt(scope,"toolLimit",1,MAX_TOOLS),models=exactInt(scope,"modelCallLimit",1,MAX_MODEL_CALLS),usedTools=exactInt(scope,"toolsUsed",0,MAX_TOOLS),usedModels=exactInt(scope,"modelCallsUsed",0,MAX_MODEL_CALLS);
        if(tools<1||tools>MAX_TOOLS||models<1||models>MAX_MODEL_CALLS||usedTools<0||usedTools>tools||usedModels<0||usedModels>models||exactLong(scope,"revision",1,Long.MAX_VALUE)<1)throw new SecurityException("原任务累计预算无效");
        validatePlanning(scope);
        return deadline==0?Long.MAX_VALUE:deadline;
    }
    /** Legacy scopes have no planning fields. Partial or malformed new state never means executing. */
    public static boolean planning(JSONObject scope)throws Exception {
        validatePlanning(scope);return scope.has("executionPhase")&&"planning".equals(scope.getString("executionPhase"));
    }
    public static void validatePlanning(JSONObject scope)throws Exception {
        if(scope==null)throw new SecurityException("原任务范围缺失");
        if(!hasPlanning(scope))return;
        String phase=string(scope,"executionPhase"),reference=string(scope,"planArtifactRef"),hash=string(scope,"planHash");
        if(!phase.equals("planning")&&!phase.equals("executing"))throw new SecurityException("原任务规划阶段无效");
        long revision=exactLong(scope,"planRevision",0,Long.MAX_VALUE);
        if(revision==0){if(!reference.isEmpty()||!hash.isEmpty())throw new SecurityException("未提交的计划不能声明草稿引用");}
        else if(!uuid(reference)||!hash.matches("[a-f0-9]{64}"))throw new SecurityException("计划缺少原草稿编号或完整摘要");
        if(phase.equals("planning")){
            if(scope.has("planApproval"))throw new SecurityException("规划阶段不能携带执行批准");
            return;
        }
        if(revision==0||!(scope.opt("planApproval") instanceof JSONObject approval))throw new SecurityException("执行计划需要已提交草稿及准确主人批准");
        java.util.Set<String> approvalKeys=new java.util.HashSet<>();approval.keys().forEachRemaining(approvalKeys::add);
        if(!approvalKeys.equals(APPROVAL_KEYS)||!"pocketworld.execution-plan-approval.v1".equals(approval.opt("format"))
                ||!uuid(string(approval,"approvalId"))||blank(string(scope,"taskId"))||!string(scope,"taskId").equals(string(approval,"taskId"))
                ||blank(string(approval,"runId"))||exactInt(approval,"epoch",1,Integer.MAX_VALUE)<1
                ||exactLong(approval,"scopeRevision",1,Long.MAX_VALUE)!=exactLong(scope,"revision",1,Long.MAX_VALUE)
                ||exactLong(approval,"planRevision",1,Long.MAX_VALUE)!=revision||!reference.equals(string(approval,"planArtifactRef"))
                ||!hash.equals(string(approval,"planHash"))||exactLong(approval,"approvedAt",0,Long.MAX_VALUE)<0)throw new SecurityException("计划批准不属于当前原任务范围与草稿修订");
    }
    /** Carries only planning fields. Scope renewal revokes old plan approval without touching any budget. */
    public static void copyPlanning(JSONObject old,JSONObject next)throws Exception {
        validatePlanning(old);if(next==null)throw new SecurityException("新的原任务范围缺失");
        if(!hasPlanning(old)){
            if(hasPlanning(next))throw new SecurityException("旧范围不能借复制新增规划授权");
            return;
        }
        long before=exactLong(old,"revision",1,Long.MAX_VALUE),after=exactLong(next,"revision",1,Long.MAX_VALUE);
        String task=string(old,"taskId");
        if(blank(task)||!task.equals(string(next,"taskId"))||after<before)throw new SecurityException("规划状态不能换任务或回退范围修订");
        JSONObject copied=new JSONObject().put("taskId",task).put("revision",after)
                .put("executionPhase",after==before?old.getString("executionPhase"):"planning")
                .put("planRevision",old.get("planRevision")).put("planArtifactRef",old.getString("planArtifactRef")).put("planHash",old.getString("planHash"));
        if(after==before&&old.has("planApproval"))copied.put("planApproval",new JSONObject(old.getJSONObject("planApproval").toString()));
        validatePlanning(copied);
        for(String key:PLANNING_KEYS)next.remove(key);
        for(String key:PLANNING_KEYS)if(copied.has(key))next.put(key,copied.get(key));
    }
    public static long nextRevision(long current){
        if(current<0||current==Long.MAX_VALUE)throw new SecurityException("原任务修订无法继续增加");
        return Math.addExact(current,1L);
    }
    private static boolean hasPlanning(JSONObject scope){for(String key:PLANNING_KEYS)if(scope.has(key))return true;return false;}
    private static boolean blank(String value){return value.codePoints().allMatch(Character::isWhitespace);}
    private static String string(JSONObject value,String key){Object raw=value.opt(key);if(!(raw instanceof String))throw new SecurityException("规划字段必须使用确切文本");return (String)raw;}
    private static boolean uuid(String value){return value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");}
    public static long exactLong(JSONObject value,String key,long minimum,long maximum)throws Exception {
        Object raw=value.get(key);if(!(raw instanceof Integer||raw instanceof Long))throw new SecurityException("任务范围必须使用确切整数");
        long number=((Number)raw).longValue();if(number<minimum||number>maximum)throw new SecurityException("任务范围整数超界");return number;
    }
    public static int exactInt(JSONObject value,String key,int minimum,int maximum)throws Exception {
        Object raw=value.get(key);if(!(raw instanceof Integer||raw instanceof Long))throw new SecurityException("任务范围必须使用确切整数");
        long number=((Number)raw).longValue();if(number<minimum||number>maximum)throw new SecurityException("任务范围整数超界");return (int)number;
    }
    static JSONObject create(String id,String mode,long revision,long deadline,int tools,int models,int usedTools,int usedModels,JSONArray allowed,boolean pinned)throws Exception {
        JSONObject scope=new JSONObject().put("format",FORMAT).put("taskId",id).put("principalId","owner").put("spaceId","private")
                .put("state","active").put("revision",revision).put("mode",mode).put("deadlineAt",deadline)
                .put("lifetime",deadline==0?"until_complete_or_stopped_or_budget":"deadline")
                .put("toolLimit",tools).put("modelCallLimit",models).put("toolsUsed",usedTools).put("modelCallsUsed",usedModels)
                .put("tools",allowed).put("toolsPinned",pinned).put("preciseEffectApprovalsRequired",true).put("autoRefill",false);
        effectiveDeadline(scope);return scope;
    }
    public static int defaultModelBudget(int toolBudget){return Math.min(MAX_MODEL_CALLS,Math.max(1,toolBudget)+8);}
}
