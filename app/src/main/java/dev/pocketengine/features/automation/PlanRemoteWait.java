package dev.pocketengine.features.automation;

import java.util.Iterator;
import org.json.JSONObject;

/** A reviewed condition on one existing command process, never a remote execution request. */
public final class PlanRemoteWait {
    private PlanRemoteWait(){}
    private static final String[] IDENTITY={"taskId","runId","operationId","profileId","serviceBase","jobId","environmentId"};
    public static JSONObject standard(JSONObject input)throws Exception {
        if(input==null)return null;
        for(Iterator<String> keys=input.keys();keys.hasNext();)if(!keys.next().matches("kind|taskId|runId|coordinatorEpoch|operationId|profileId|serviceBase|jobId|environmentId|expectedExitCode"))throw new IllegalArgumentException("电脑等待条件包含不支持的字段");
        if(!"remote_command_exit".equals(input.optString("kind")))throw new IllegalArgumentException("当前只支持等待原电脑 command 进程退出");
        JSONObject result=new JSONObject().put("kind","remote_command_exit");
        for(String field:IDENTITY){String value=PlanExecutionPolicy.text(input,field,field.equals("serviceBase")?2048:200);if(value.matches(".*[\\p{Cntrl}].*"))throw new IllegalArgumentException("原电脑任务身份包含无效字符");result.put(field,value);}
        if(!result.getString("runId").equals(result.getString("operationId")))throw new IllegalArgumentException("等待条件必须引用原电脑执行操作");
        long epoch=AutomationPolicy.integer(input,"coordinatorEpoch",0,Integer.MAX_VALUE);
        if(AutomationPolicy.integer(input,"expectedExitCode",0,0)!=0)throw new IllegalArgumentException("当前只支持明确的原进程退出码 0");
        return result.put("coordinatorEpoch",epoch).put("expectedExitCode",0);
    }
    public static boolean sameIdentity(JSONObject first,JSONObject second)throws Exception {
        JSONObject a=standard(first),b=standard(second);if(a==null||b==null)return a==b;
        for(String field:IDENTITY)if(!a.getString(field).equals(b.getString(field)))return false;
        return a.getLong("coordinatorEpoch")==b.getLong("coordinatorEpoch");
    }
}
