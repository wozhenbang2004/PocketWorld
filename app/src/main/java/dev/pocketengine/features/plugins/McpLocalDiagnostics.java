package dev.pocketengine.features.plugins;

import dev.pocketengine.features.local.project.ProjectSources;
import dev.pocketengine.features.local.runtime.ManagedProjectRuntime;
import dev.pocketengine.features.local.runtime.NativePipeAdmission;
import dev.pocketengine.features.local.runtime.RuntimeExecutionRef;
import dev.pocketengine.store.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owner-only observation of the already bound original job. No launch/control reconstruction,
 * alternate log store, instruction projection or dependency retry is performed. */
public final class McpLocalDiagnostics {
    public static final String FORMAT="pocketworld.native-mcp-diagnostic.v1";
    private McpLocalDiagnostics() {}
    public static JSONObject readDiscovery(TaskStore tasks,ManagedProjectRuntime runtime,String task,long cursor,int maxBytes)throws Exception {
        java.util.Objects.requireNonNull(tasks);java.util.Objects.requireNonNull(runtime);
        if(cursor<0||maxBytes<8192||maxBytes>65536)throw new IllegalArgumentException("原本机MCP诊断页限8–64KiB，游标须为非负整数");
        JSONObject row=tasks.ownerMcpDiscoveryForOwner(task),scope=row.getJSONObject("scope"),job=row.optJSONObject("nativeJob");
        JSONObject view=new JSONObject().put("format",FORMAT).put("taskId",task).put("operationId",task).put("cursor",cursor).put("queryOnly",true).put("replayAllowed",false).put("diagnosticsUntrusted",true).put("modelDelivery",false)
                .put("sourceDigest",scope.getString("sourceDigest")).put("configurationDigest",scope.getString("configurationDigest")).put("runtimeIdentity",scope.getString("runtimeIdentity"));
        if(job==null)return view.put("logAvailable",false).put("chunks",new JSONArray()).put("nextCursor",cursor).put("hasMore",false).put("message","原操作尚无已绑定的进程日志；此读取不会新建进程或重新发现。");
        JSONObject exact=job.getJSONObject("exact"),binding=job.getJSONObject("binding");
        // Reading an old original log does not require the current bootstrap or source generation.
        // The original reference and actual runtime receipt remain the authority for these bytes.
        if(!McpLocalDiscovery.INTENT.equals(exact.optString("intent"))||!McpLocalDiscovery.STEP.equals(exact.optString("stepId")))throw new SecurityException("日志引用不是原本机MCP发现操作");
        for(String field:new String[]{"projectId","checkoutId","sourceDigest","planDigest","runtimeId","runtimeIdentity"})if(!scope.getString(field).equals(exact.getString(field)))throw new SecurityException("原本机MCP日志范围与持久引用不同");
        RuntimeExecutionRef ref=RuntimeExecutionRef.fromHost(exact,binding);
        if(!task.equals(scope.getString("actionId"))||!task.equals(ref.operationId())||!task.equals(binding.getString("taskId"))||!task.equals(binding.getString("runId"))||binding.getInt("coordinatorEpoch")!=1)throw new SecurityException("本机MCP诊断不属于此原任务和执行代次");
        JSONObject pipe=NativePipeAdmission.bindingForHost(McpLocalDiscovery.pipeIdentityForHost(scope),binding,scope.getString("sourceDigest"),McpLocalDiscovery.WRITE_BYTE_LIMIT);
        if(!ProjectSources.digest(pipe).equals(ProjectSources.digest(job.getJSONObject("nativePipeBinding")))||!ProjectSources.digest(pipe).equals(exact.getJSONObject("payload").getString("nativePipeBindingDigest")))throw new SecurityException("原本机MCP日志管道身份已变化");
        JSONObject page=runtime.readLog(ref,cursor,maxBytes);if(!task.equals(page.getString("operationId")))throw new SecurityException("不是此原进程的日志页");
        JSONObject current=tasks.ownerMcpDiscoveryForOwner(task).getJSONObject("nativeJob");if(!ProjectSources.digest(job).equals(ProjectSources.digest(current)))throw new SecurityException("读取过程中原进程引用已变化，请重新核对原操作");
        for(String field:new String[]{"chunks","nextCursor","hasMore","logIncompleteTail","outputTruncated"})view.put(field,page.get(field));
        return view.put("logAvailable",true);
    }
}
