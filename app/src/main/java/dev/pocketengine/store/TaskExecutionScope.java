package dev.pocketengine.store;

import org.json.JSONArray;
import org.json.JSONObject;

/** Native owner/task policy. This value is never parsed as authority from model or channel input. */
public final class TaskExecutionScope {
    public static final String FORMAT="pocketworld.task-execution-scope.v1";
    public static final int MAX_TOOLS=400,MAX_MODEL_CALLS=512;
    private TaskExecutionScope(){}
    public static long effectiveDeadline(JSONObject scope)throws Exception {
        if(scope==null||!FORMAT.equals(scope.optString("format"))||!"owner".equals(scope.optString("principalId"))
                ||!"private".equals(scope.optString("spaceId"))||!"active".equals(scope.optString("state")))throw new SecurityException("原任务范围无效");
        Object raw=scope.get("deadlineAt");if(!(raw instanceof Long||raw instanceof Integer)||((Number)raw).longValue()<0)throw new SecurityException("原任务期限无效");
        long deadline=((Number)raw).longValue();
        if(!scope.getString("lifetime").equals(deadline==0?"until_complete_or_stopped_or_budget":"deadline"))throw new SecurityException("原任务期限策略不一致");
        int tools=exactInt(scope,"toolLimit",1,MAX_TOOLS),models=exactInt(scope,"modelCallLimit",1,MAX_MODEL_CALLS),usedTools=exactInt(scope,"toolsUsed",0,MAX_TOOLS),usedModels=exactInt(scope,"modelCallsUsed",0,MAX_MODEL_CALLS);
        if(tools<1||tools>MAX_TOOLS||models<1||models>MAX_MODEL_CALLS||usedTools<0||usedTools>tools||usedModels<0||usedModels>models||exactLong(scope,"revision",1,Long.MAX_VALUE)<1)throw new SecurityException("原任务累计预算无效");
        return deadline==0?Long.MAX_VALUE:deadline;
    }
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
