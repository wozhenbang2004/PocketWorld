package dev.pocketengine.store;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Separate zero-model owner input scope. Never accepted as an ordinary agent scope. */
public final class OwnerPhoneControlScope {
    public static final String KEY="ownerPhoneControl", FORMAT="pocketworld.owner-phone-control.v1";
    private static final String SCOPE="pocketworld.owner-phone-control-scope.v1";
    private static final Set<String> TOOLS=Set.of("device_invoke_tool","device_operation_get");
    private OwnerPhoneControlScope() { }
    public static boolean marked(JSONObject task){return task!=null&&task.has(KEY);}
    public static JSONObject create(String id,long deadline,int tools)throws Exception {
        return new JSONObject().put("format",SCOPE).put("taskId",id).put("principalId","owner").put("spaceId","private")
                .put("mode","capabilities").put("state","active").put("revision",1L).put("deadlineAt",deadline).put("lifetime","deadline")
                .put("toolLimit",tools).put("toolsUsed",0).put("modelCallLimit",0).put("modelCallsUsed",0)
                .put("tools",new JSONArray(new String[]{"device_invoke_tool","device_operation_get"})).put("toolsPinned",true).put("autoRefill",false);
    }
    /** Validates the whole persisted native task, not an arbitrary scope-shaped JSON value. */
    public static long require(JSONObject task)throws Exception {
        JSONObject marker=task.getJSONObject(KEY),scope=task.getJSONObject("executionScope");String id=task.getString("id");
        if(!FORMAT.equals(marker.optString("format"))||!id.equals(marker.optString("requestId"))||!marker.optString("requestDigest").matches("[a-f0-9]{64}")
                ||!"owner".equals(task.optString("actorId"))||!"private".equals(task.optString("spaceId"))||!"tool".equals(task.optString("kind"))
                ||!"capabilities".equals(task.optString("executionMode"))||!"OWNER_PHONE_CONTROL".equals(task.optString("nativeExecutionMode"))
                ||!"devices".equals(task.optString("feature"))||!"owner.phone.control".equals(task.optString("command"))
                ||task.has("origin")||task.has("workProfileRef")||task.has("workProfileIntent")||task.has("teamAssignment")||task.has("developmentContext")
                ||!task.optString("conversationId").isEmpty()||!Boolean.FALSE.equals(marker.opt("modelDispatchAllowed")))throw new SecurityException("不是原生主人手机手控任务");
        if(!SCOPE.equals(scope.optString("format"))||!id.equals(scope.optString("taskId"))||!"owner".equals(scope.optString("principalId"))
                ||!"private".equals(scope.optString("spaceId"))||!"capabilities".equals(scope.optString("mode"))||!"active".equals(scope.optString("state"))
                ||!"deadline".equals(scope.optString("lifetime"))||!Boolean.TRUE.equals(scope.opt("toolsPinned"))||!Boolean.FALSE.equals(scope.opt("autoRefill")))throw new SecurityException("原手机手控范围无效");
        for(String key:new String[]{"executionPhase","planRevision","planArtifactRef","planHash","planApproval"})if(scope.has(key))throw new SecurityException("手控不能借用模型执行计划");
        long end=TaskExecutionScope.exactLong(scope,"deadlineAt",1,Long.MAX_VALUE);
        if(end!=TaskExecutionScope.exactLong(marker,"deadlineAt",1,Long.MAX_VALUE))throw new SecurityException("手控不能延长期限");
        int tools=TaskExecutionScope.exactInt(scope,"toolLimit",4,200);TaskExecutionScope.exactInt(scope,"toolsUsed",0,tools);
        if(tools!=TaskExecutionScope.exactInt(marker,"toolLimit",4,200))throw new SecurityException("手控不能补满额度");
        TaskExecutionScope.exactInt(scope,"modelCallLimit",0,0);TaskExecutionScope.exactInt(scope,"modelCallsUsed",0,0);TaskExecutionScope.exactLong(scope,"revision",1,Long.MAX_VALUE);
        JSONArray names=scope.getJSONArray("tools");Set<String> actual=new java.util.HashSet<>();for(int i=0;i<names.length();i++)actual.add(names.getString(i));
        if(names.length()!=2||!TOOLS.equals(actual))throw new SecurityException("手控不能挂载代理或通用工具");return end;
    }
    public static void requireRoute(JSONObject route)throws Exception {
        JSONObject resource=route.getJSONObject("resourceBinding");
        int tools=TaskExecutionScope.exactInt(route,"toolBudget",2,100);if(route.getInt("localToolShare")!=tools)throw new SecurityException("手控份额必须全部留给原手机");
        JSONObject declarations=route.getJSONObject("toolDeclarations");for(String name:dev.pocketengine.features.devices.DeviceProtocol.objectKeys(declarations))if(!dev.pocketengine.features.devices.DeviceToolsRuntime.supports(name)||!dev.pocketengine.features.devices.DeviceToolsRuntime.effect(name).equals(declarations.getString(name)))throw new SecurityException("手控不能混入非手机工具");
        if(!"tool_session".equals(route.optString("kind"))||!dev.pocketengine.features.devices.DeviceProtocol.VERSION.equals(route.optString("capabilityVersion"))
                ||!resource.optString("screenMode").matches("main_screen|background")||!resource.optBoolean("allowWholeDisplayCapture")||!resource.optBoolean("allowWholeDisplayInput")||route.getInt("modelBudget")!=0||route.getInt("localModelShare")!=0
                ||!"goal_only".equals(route.optString("contextMode"))||route.getJSONArray("downstreamShares").length()!=0
                ||route.optJSONArray("files")!=null&&route.getJSONArray("files").length()!=0||route.optJSONArray("projects")!=null&&route.getJSONArray("projects").length()!=0)
            throw new SecurityException("手控只允许一个手机屏幕、零模型且无材料或转派");
    }
}
