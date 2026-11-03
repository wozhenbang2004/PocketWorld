package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.local.LocalModule;
import dev.pocketengine.store.TaskStore;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** A separate one-use native installation review. Completed discovery is only its input fact;
 * neither this object nor its JSON view approves a program launch or an individual tool call. */
public final class McpLocalInstallation {
    private McpLocalInstallation() {}
    static Review review(PluginsModule plugins,TaskStore tasks,TaskStore.OwnerMcpDiscoveryCompleted completed,LocalModule local,JSONArray selection,RunControl observation)throws Exception{return new Review(plugins,tasks,completed,local,selection,observation);}
    /** Read-only projection of the original completed/cleared discovery. Supported declarations
     * come from the same manifest rules as installation, never a second UI schema validator. */
    public static JSONObject catalogueForOwner(TaskStore tasks,TaskStore.OwnerMcpDiscoveryCompleted completed)throws Exception {
        java.util.Objects.requireNonNull(completed).requireCurrentForOwner(tasks);JSONObject source=completed.scopeForOwner(),found=completed.metadataForOwner();JSONArray rows=new JSONArray(),definitions=found.getJSONArray("tools");
        for(int i=0;i<definitions.length();i++)rows.put(McpStdioData.previewTool(definitions.getJSONObject(i),source.getString("configurationDigest"),found.getString("protocolVersion"),found.getString("serverDigest")));
        JSONObject view=new JSONObject().put("format","pocketworld.native-stdio-install-catalogue.v1").put("discoveryTaskId",completed.discoveryTaskId()).put("discoveryFactsDigest",completed.factsDigest()).put("id",source.getString("pluginId")).put("name",source.getString("name"));
        for(String field:new String[]{"configurationDigest","sourceDigest","checkoutId","runtimeIdentity","entryPoint","argvDigest","argvCount","expectedInstallationId","expectedStateRevision"})view.put(field,source.get(field));
        if(source.has(McpLocalPackage.PLAN))view.put(McpLocalPackage.PLAN,source.getJSONObject(McpLocalPackage.PLAN)).put("preparationDigest",source.getString("preparationDigest"));
        view.put("protocolVersion",found.getString("protocolVersion")).put("serverDigest",found.getString("serverDigest")).put("tools",rows).put("maxImportedTools",McpData.MAX_IMPORTED_TOOLS).put("sourceRuntimeRevalidationRequired",true).put("programStarted",false).put("executionAuthorized",false).put("installationAuthorized",false).put("untrusted",true);
        completed.requireCurrentForOwner(tasks);return view.put("catalogueDigest",dev.pocketengine.features.plugins.mcp.McpJson.digest(view));
    }
    public static final class Review implements AutoCloseable {
        private final PluginsModule plugins;private final TaskStore tasks;private final TaskStore.OwnerMcpDiscoveryCompleted completed;private final LocalModule local;private final RunControl observation;
        private final String id=UUID.randomUUID().toString(),scope,configuration,manifest,metadata,digest;private final long expiresAt=System.currentTimeMillis()+120000;private boolean used,closed;
        private Review(PluginsModule plugins,TaskStore tasks,TaskStore.OwnerMcpDiscoveryCompleted completed,LocalModule local,JSONArray selection,RunControl observation)throws Exception {
            this.plugins=java.util.Objects.requireNonNull(plugins);this.tasks=java.util.Objects.requireNonNull(tasks);this.completed=java.util.Objects.requireNonNull(completed);this.local=java.util.Objects.requireNonNull(local);this.observation=java.util.Objects.requireNonNull(observation);
            if(observation.hasHostRun())throw new SecurityException("本机MCP安装只在主人原生审阅发起，不能借执行中模型身份");completed.requireCurrentForOwner(tasks);JSONObject source=completed.scopeForOwner(),config=completed.configurationForOwner(),found=completed.metadataForOwner();scope=source.toString();configuration=config.toString();
            JSONObject selected=McpStdioData.manifest(config.getString("id"),config.getString("name"),source.getString("configurationDigest"),found.getString("protocolVersion"),found.getString("serverDigest"),found.getJSONArray("tools"),new JSONArray(selection.toString()));manifest=selected.toString();
            live();JSONArray names=new JSONArray();for(int i=0;i<selected.getJSONArray("tools").length();i++)names.put(selected.getJSONArray("tools").getJSONObject(i).getString("name"));
            JSONObject view=new JSONObject().put("reviewId",id).put("purpose","install_native_stdio_mcp").put("discoveryTaskId",completed.discoveryTaskId()).put("discoveryFactsDigest",completed.factsDigest()).put("id",config.getString("id")).put("name",config.getString("name"))
                    .put("configurationDigest",source.getString("configurationDigest")).put("sourceDigest",source.getString("sourceDigest")).put("checkoutId",source.getString("checkoutId")).put("runtimeIdentity",source.getString("runtimeIdentity")).put("entryPoint",source.getString("entryPoint")).put("argvDigest",source.getString("argvDigest")).put("argvCount",source.getInt("argvCount"))
                    .put("expectedInstallationId",source.getString("expectedInstallationId")).put("expectedStateRevision",source.get("expectedStateRevision")).put("serverDigest",found.getString("serverDigest")).put("manifest",selected).put("selectedTools",names).put("maxImportedTools",McpData.MAX_IMPORTED_TOOLS).put("expiresAt",expiresAt)
                    .put("programStarted",false).put("executionAuthorized",false).put("toolInvocationAuthorized",false).put("enabledAfterInstall",false).put("grantsAfterInstall",new JSONArray()).put("credentialsSupported",false).put("environmentInputsSupported",false).put("osSandbox",false)
                    .put("message","仅把原已完成发现中的明确工具声明安装到原插件目录，不运行程序、不启用或授予工具权限。后续仍须原任务的确切调用审批、源版本和运行环境复核；同UID不是OS安全沙箱，隐藏配置与依赖缓存不会随带。");
            if(source.has(McpLocalPackage.PLAN))view.put(McpLocalPackage.PLAN,source.getJSONObject(McpLocalPackage.PLAN)).put("preparationDigest",source.getString("preparationDigest")).put("preparationOnFutureInvocation",true);
            digest=dev.pocketengine.features.plugins.mcp.McpJson.digest(view);metadata=view.put("reviewDigest",digest).toString();
        }
        private void live()throws Exception {
            observation.check();if(closed||used||observation.isPaused()||System.currentTimeMillis()>=expiresAt)throw new SecurityException("原本机MCP安装审阅已关闭、消费或过期，请重新读取");completed.requireCurrentForOwner(tasks);JSONObject source=new JSONObject(scope);plugins.requireLocalMcpInstallationTargetForOwner(source);
            // Reuse the exact existing visible-source/runtime preflight, never its confirmation.
            // The temporary discovery review is closed without a task, copy, process or launch grant.
            try(var preflight=local.reviewMcpForOwner(new JSONObject(configuration),observation).bindPluginTarget(plugins)){
                JSONObject current=preflight.metadata();if(source.has(McpLocalPackage.PLAN)&&!source.getString("preparationDigest").equals(current.optString("preparationDigest")))throw new SecurityException("原本机MCP固定依赖准备或包管理器已变化，请重新发现");for(String field:new String[]{"sourceDigest","runtimeIdentity","configurationDigest","executableSha256","entryPointSha256","argvDigest"})if(!source.getString(field).equals(current.getString(field)))throw new SecurityException("原本机MCP源码、程序或运行环境已变化，请重新发现");
            }
        }
        public synchronized JSONObject metadata()throws Exception{live();return new JSONObject(metadata);}
        synchronized JSONObject consumeForHost(PluginsModule actual,String expectedDigest)throws Exception {
            if(actual!=plugins||!digest.equals(expectedDigest))throw new SecurityException("请确认此原生窗口准确安装审阅，JSON不能替代批准");live();used=true;JSONObject source=new JSONObject(scope);
            JSONObject record=new JSONObject().put("format","pocketworld.native-stdio-installation.v1").put("configuration",new JSONObject(configuration)).put("configurationDigest",source.getString("configurationDigest")).put("sourceDigest",source.getString("sourceDigest")).put("runtimeIdentity",source.getString("runtimeIdentity")).put("executableSha256",source.getString("executableSha256")).put("entryPointSha256",source.getString("entryPointSha256")).put("discoveryTaskId",completed.discoveryTaskId()).put("discoveryFactsDigest",completed.factsDigest()).put("serverDigest",completed.metadataForOwner().getString("serverDigest"));
            if(source.has(McpLocalPackage.PLAN)){for(String field:new String[]{McpLocalPackage.PLAN,"preparationDigest","launchExecutable","launchArgvDigest","launchArgvCount"})record.put(field,source.get(field));}
            JSONObject input=new JSONObject().put("manifest",new JSONObject(manifest)).put("expectedInstallationId",source.getString("expectedInstallationId"));if(!source.isNull("expectedStateRevision"))input.put("expectedStateRevision",source.getLong("expectedStateRevision"));return new JSONObject().put("input",input).put("record",record).put("target",source);
        }
        @Override public synchronized void close(){closed=true;}
    }
}
