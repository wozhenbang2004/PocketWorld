package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.features.plugins.mcp.McpTaskCoordinator;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Installed declarations and grants are checked before dispatch; network I/O never holds the grant lock. */
public final class PluginsModule implements EngineModule {
    private final FeatureContext context;
    private final File file,previewSlotsFile;
    private final java.util.Set<String> previewSlots=new java.util.LinkedHashSet<>();
    private boolean recoveredPreviewSlots;
    private final Map<String,Invocation> active=new LinkedHashMap<>();
    private final Map<String,McpPreview> mcpPreviews=new LinkedHashMap<>();
    private final Map<String,McpClient> mcpClients=new LinkedHashMap<>();
    private final McpConfigImportAdapter configurationImports=new McpConfigImportAdapter();
    private static final long MCP_PREVIEW_TTL=10*60*1000L;
    private boolean closed;
    private dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost oauthHost;
    private final dev.pocketengine.features.plugins.mcp.McpOAuth.Http oauthHttp;private final McpClient.NativeFactory mcpFactory;
    public PluginsModule(FeatureContext context){this(context,new dev.pocketengine.features.plugins.mcp.McpOAuthHttp(),McpClient::new);}
    /** Controlled native transport only, never a model/manifest/config-selected network override. */
    PluginsModule(FeatureContext context,dev.pocketengine.features.plugins.mcp.McpOAuth.Http oauthHttp,McpClient.NativeFactory mcpFactory){this.context=context;this.oauthHttp=oauthHttp;this.mcpFactory=mcpFactory;file=new File(context.android.getFilesDir(),"plugins/manifests.json");previewSlotsFile=new File(context.android.getFilesDir(),"plugins/mcp-preview-slots.json");}
    private synchronized dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost oauth(){if(closed)throw new IllegalStateException("原插件宿主已关闭");if(oauthHost==null)oauthHost=new dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost(context.secrets,this::authorizationChanged,oauthHttp,mcpFactory);return oauthHost;}
    public dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost.Page newMcpOAuthPage(){return oauth().newOwnerPage();}
    public JSONObject mcpOAuthSnapshot()throws Exception{return oauth().snapshot();}
    /** Native owner workflow only. The generic module handler never accepts this command or a token. */
    public JSONObject discoverMcpAuthorized(JSONObject publicConfig,String connectionId,int expectedAuthorizationRevision)throws Exception {
        if(publicConfig.has("token")||publicConfig.has("headers")||publicConfig.has("env"))throw new SecurityException("原OAuth凭据不能由配置JSON提供");return discoverMcp(publicConfig,oauth().credential(connectionId,expectedAuthorizationRevision,publicConfig.getString("endpoint")));
    }
    private synchronized void authorizationChanged(String connectionId){
        try{JSONArray all=list();for(int i=0;i<all.length();i++){JSONObject plugin=all.getJSONObject(i),auth=plugin.optJSONObject("mcpAuthorization");if(auth!=null&&connectionId.equals(auth.optString("connectionId"))){cancelPlugin(plugin.getString("id"));closeMcp(installationId(plugin));}}
            java.util.Iterator<McpPreview> iterator=mcpPreviews.values().iterator();while(iterator.hasNext()){McpPreview preview=iterator.next();if(preview.authorization!=null&&connectionId.equals(preview.authorization.identity().getString("connectionId"))){iterator.remove();releasePreview(preview,true);}}
        }catch(Exception unavailable){for(Invocation invocation:active.values())invocation.control.cancel();for(McpClient client:mcpClients.values())client.close();mcpClients.clear();}
    }
    @Override public String id(){return "plugins";}
    private JSONArray list()throws Exception {JSONArray list=JsonFiles.read(file).optJSONArray("plugins");return list==null?new JSONArray():list;}
    private JSONObject nativeMcpSupport()throws Exception{JSONObject support=McpData.support();support.getJSONObject("oauth").put("hostMountRequired",false).put("hostMounted",oauthHost!=null).put("nativeOwnerFlowAvailable",true).put("automaticRefresh",false).put("endpointPolicy","resource_metadata_same_origin;authorization_token_same_selected_issuer_origin");return support;}
    @Override public synchronized JSONObject snapshot()throws Exception {
        recoverMcpSecrets();pruneMcpPreviews();JSONArray all=list();for(int i=0;i<all.length();i++)all.put(i,publicPlugin(all.getJSONObject(i)));
        JSONArray pending=new JSONArray();for(Map.Entry<String,Invocation> entry:active.entrySet())pending.put(new JSONObject().put("operationId",entry.getKey()).put("pluginId",entry.getValue().pluginId).put("status",entry.getValue().control.isCancelled()?"stopping":"running"));
        return new JSONObject().put("plugins",all).put("active",pending).put("execution","declarative_echo_https_or_mcp").put("mcpSupport",nativeMcpSupport()).put("nativeMcpConfigurationImport",new JSONObject().put("source","selected_saf_document").put("readOnly",true).put("maxBytes",McpConfigImportAdapter.MAX_BYTES).put("credentialsImported",false)).put("arbitraryCode",false).put("commands",new JSONArray().put("discoverMcp").put("installMcp").put("discardMcpPreview").put("install").put("previewInstall").put("history").put("previewRollback").put("rollback").put("enable").put("grant").put("remove").put("invoke").put("validateArguments").put("cancel").put("registry"));
    }
    /** Opaque native result, not reconstructible from feature/model JSON or an arbitrary URI string. */
    public static final class SelectedMcpConfigurationDocument {
        private final PluginsModule issuer;private final android.net.Uri uri;
        private SelectedMcpConfigurationDocument(PluginsModule issuer,android.net.Uri uri){this.issuer=issuer;this.uri=uri;}
    }
    /** Original Activity result bridge only. Intent flags alone never create URI permission. */
    public SelectedMcpConfigurationDocument selectedMcpConfigurationDocument(int resultCode,android.content.Intent nativeResult)throws Exception {
        if(resultCode!=android.app.Activity.RESULT_OK||nativeResult==null||nativeResult.getData()==null||(nativeResult.getFlags()&android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)==0||nativeResult.getClipData()!=null&&nativeResult.getClipData().getItemCount()!=1)throw new McpConfigImportAdapter.Rejected("selected_document_required");
        android.net.Uri uri=nativeResult.getData();McpConfigImportAdapter.Source.selectedSaf(uri.toString());checkMcpDocumentPermission(uri);return new SelectedMcpConfigurationDocument(this,uri);
    }
    private void checkMcpDocumentPermission(android.net.Uri uri){
        if(context.android.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new McpConfigImportAdapter.Rejected("selected_document_permission_required");
    }
    /** Native owner file-picker data path only; model/feature commands cannot open a configuration URI. */
    public McpConfigImportAdapter.Preview previewMcpConfigurationImport(SelectedMcpConfigurationDocument selected,McpConfigImportAdapter.Format format,McpConfigImportAdapter.Preview previous,RunControl original)throws Exception {
        if(selected==null||selected.issuer!=this||original==null)throw new McpConfigImportAdapter.Rejected("selected_document_required");android.net.Uri selectedDocument=selected.uri;
        McpConfigImportAdapter.Source source=McpConfigImportAdapter.Source.selectedSaf(selectedDocument.toString());JSONArray installed=configurationImportReferences();original.check();checkMcpDocumentPermission(selectedDocument);
        java.io.InputStream stream;
        try{stream=context.android.getContentResolver().openInputStream(selectedDocument);}catch(Exception unavailable){throw new McpConfigImportAdapter.Rejected("selected_document_unavailable");}
        if(stream==null)throw new McpConfigImportAdapter.Rejected("selected_document_unavailable");Runnable cancel=()->{try{stream.close();}catch(Exception ignored){}};original.onCancel(cancel);
        boolean completed=false;
        try{McpConfigImportAdapter.Preview result=configurationImports.inspect(source,format,stream,installed,previous,original);original.check();checkMcpDocumentPermission(selectedDocument);completed=true;return result;}
        catch(java.io.IOException unavailable){original.check();throw new McpConfigImportAdapter.Rejected("selected_document_unavailable");}
        finally{
            boolean closeFailed=false;
            try{stream.close();}catch(Exception unavailable){closeFailed=true;}finally{original.removeCancelAction(cancel);}
            // Never attach provider diagnostics to a parser rejection, cancellation or interruption.
            if(completed){original.check();if(closeFailed)throw new McpConfigImportAdapter.Rejected("selected_document_unavailable");}
        }
    }
    /** Produces public discovery input only. No imported token, process, request, installation or grant. */
    public synchronized JSONObject mcpConfigurationDiscoveryInput(McpConfigImportAdapter.Preview preview,int item,String acceptedDigest,String targetId,String displayName,String protocol)throws Exception {
        if(closed)throw new IllegalStateException("原插件宿主已关闭");JSONObject result=configurationImports.discoveryInput(preview,item,acceptedDigest,targetId,displayName,protocol);
        String actual="";long revision=-1;JSONArray installed=list();for(int i=0;i<installed.length();i++){JSONObject plugin=installed.getJSONObject(i);if(targetId.equals(plugin.getString("id"))){actual=installationId(plugin);revision=PluginRevisions.stateRevision(plugin);}}
        if(!actual.equals(preview.expectedInstallation(targetId))||revision!=preview.expectedRevision(targetId))throw new McpConfigImportAdapter.Rejected("installation_changed_since_preview");return result;
    }
    private synchronized JSONArray configurationImportReferences()throws Exception {
        if(closed)throw new IllegalStateException("原插件宿主已关闭");JSONArray references=new JSONArray(),installed=list();
        for(int i=0;i<installed.length();i++){JSONObject plugin=installed.getJSONObject(i);references.put(new JSONObject().put("id",plugin.getString("id")).put("installationId",installationId(plugin)).put("stateRevision",PluginRevisions.stateRevision(plugin)).put("version",plugin.getJSONObject("manifest").getString("version")).put("enabled",plugin.optBoolean("enabled")).put("grantsDigest",PluginRules.grantsDigest(plugin.optJSONArray("grants"))));}
        return references;
    }
    @Override public JSONObject handle(String command,JSONObject input)throws Exception {
        if(command.equals("invoke"))return invoke(input);
        if(command.equals("discoverMcp"))return discoverMcp(input);
        synchronized(this){
            if(closed)throw new IllegalStateException("插件执行器已关闭");
            if(command.equals("registry"))return new JSONObject().put("tools",toolRegistry());
            if(command.equals("cancel")){
                String operationId=input.getString("operationId");Invocation invocation=active.get(operationId);
                if(invocation==null)return new JSONObject().put("operationId",operationId).put("active",false).put("status","not_active");
                invocation.control.cancel();return new JSONObject().put("operationId",operationId).put("active",true).put("status","stopping").put("verified",false).put("message","已停止等待后续结果；已经发出的请求仍需核对服务端结果");
            }
            recoverMcpSecrets();pruneMcpPreviews();
            if(command.equals("discardMcpPreview")){
                McpPreview preview=mcpPreviews.remove(input.getString("previewId"));if(preview!=null)releasePreview(preview,true);
                return new JSONObject().put("discarded",preview!=null);
            }
            JSONArray all=list();
            if(command.equals("installMcp"))return installMcp(all,input);
            if(command.equals("previewInstall")){
                rejectDirectMcp(input.getJSONObject("manifest"));
                JSONObject manifest=PluginRules.manifest(input.getJSONObject("manifest")),current=null;
                for(int i=0;i<all.length();i++)if(manifest.getString("id").equals(all.getJSONObject(i).getString("id")))current=all.getJSONObject(i);
                JSONObject preview=PluginRevisions.preview(current,manifest);int affected=0;
                for(Invocation invocation:active.values())if(invocation.pluginId.equals(manifest.getString("id")))affected++;
                return preview.put("activeCallsToCancel",affected);
            }
            String id=input.optString("id");JSONObject found=null;int index=-1;
            for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id"))){found=all.getJSONObject(i);index=i;break;}
            if(command.equals("install")){rejectDirectMcp(input.getJSONObject("manifest"));return install(all,input);}
            if(found==null)throw new IllegalArgumentException("插件不存在");
            if(command.equals("history"))return PluginRevisions.history(found);
            if(command.equals("previewRollback")){
                PluginRevisions.checkRestoreExpected(found,input);
                String target=input.getString("targetInstallationId");
                JSONObject manifest=PluginRevisions.restoreManifest(found,target);rejectDirectMcp(manifest);
                JSONObject preview=PluginRevisions.preview(found,manifest);int affected=0;
                for(Invocation invocation:active.values())if(invocation.pluginId.equals(id))affected++;
                return preview.put("targetInstallationId",target).put("activeCallsToCancel",affected).put("restoration",true);
            }
            if(command.equals("validateArguments")){
                // Pin the form to the exact installed declaration. No grants, credentials or execution are touched.
                if(!input.has("expectedInstallationId"))throw new IllegalArgumentException("请先读取当前插件，再校验工具参数");
                PluginRevisions.checkExpected(found,input,true);
                String toolName=input.getString("tool");JSONArray definitions=found.getJSONObject("manifest").getJSONArray("tools");
                for(int i=0;i<definitions.length();i++){
                    JSONObject definition=definitions.getJSONObject(i);
                    if(toolName.equals(definition.getString("name")))return ("mcp".equals(definition.optString("kind"))?PluginRules.diagnoseMcpArguments(arguments(input),definition.getJSONObject("schema")):PluginRules.diagnoseArguments(arguments(input),definition.getJSONObject("schema")))
                            .put("id",id).put("tool",toolName).put("currentInstallationId",installationId(found))
                            .put("currentVersion",found.getJSONObject("manifest").getString("version")).put("validationOnly",true);
                }
                throw new IllegalArgumentException("插件工具不存在");
            }
            if(command.equals("rollback")){
                PluginRevisions.checkRestoreExpected(found,input);
                String target=input.getString("targetInstallationId");
                JSONObject targetManifest=PluginRevisions.restoreManifest(found,target);rejectDirectMcp(targetManifest);
                JSONObject restore=new JSONObject().put("manifest",targetManifest)
                        .put("expectedInstallationId",input.getString("expectedInstallationId"))
                        .put("expectedStateRevision",input.get("expectedStateRevision"));
                if(input.has("token"))restore.put("token",input.get("token"));
                return install(all,restore,target);
            }
            switch(command){
                case "enable":found.put("enabled",input.getBoolean("enabled"));break;
                case "grant":found.put("grants",PluginRules.grants(found.getJSONObject("manifest"),input.getJSONArray("grants")));break;
                case "remove":all.remove(index);JsonFiles.write(file,new JSONObject().put("plugins",all));cancelPlugin(id);closeMcp(installationId(found));removeCredential(found);return new JSONObject().put("removed",true);
                default:throw new IllegalArgumentException("不支持的插件操作");
            }
            PluginRevisions.advanceState(found);
            JsonFiles.write(file,new JSONObject().put("plugins",all));cancelPlugin(id);return publicPlugin(found);
        }
    }
    /** Native-only owner toggle pinned to the installation displayed in the confirmation surface. */
    public synchronized JSONObject setEnabledReviewed(String id,String expectedInstallationId,boolean enabled)throws Exception {
        if(closed)throw new IllegalStateException("插件执行器已关闭");
        if(expectedInstallationId==null||expectedInstallationId.isEmpty())throw new IllegalArgumentException("请先读取当前插件，再确认启停");
        JSONArray all=list();
        for(int i=0;i<all.length();i++){
            JSONObject plugin=all.getJSONObject(i);if(!id.equals(plugin.getString("id")))continue;
            if(!expectedInstallationId.equals(installationId(plugin)))throw new IllegalStateException("插件安装已变化，请刷新后再操作");
            if(plugin.optBoolean("enabled")==enabled)return publicPlugin(plugin);
            plugin.put("enabled",enabled);PluginRevisions.advanceState(plugin);JsonFiles.write(file,new JSONObject().put("plugins",all));cancelPlugin(id);if(!enabled)closeMcp(installationId(plugin));return publicPlugin(plugin);
        }
        throw new IllegalArgumentException("插件不存在");
    }
    private JSONObject install(JSONArray all,JSONObject input)throws Exception {
        return install(all,input,null);
    }
    /** Native one-tool permission review. A stale form cannot overwrite newer grants or another install. */
    public synchronized JSONObject setToolGrantReviewed(String id,String expectedInstallationId,String expectedGrantsDigest,String tool,boolean granted)throws Exception {
        if(closed)throw new IllegalStateException("插件执行器已关闭");JSONArray all=list();
        for(int i=0;i<all.length();i++){JSONObject plugin=all.getJSONObject(i);if(!id.equals(plugin.getString("id")))continue;
            if(expectedInstallationId==null||expectedInstallationId.isEmpty()||!expectedInstallationId.equals(installationId(plugin))||!PluginRules.grantsDigest(plugin.optJSONArray("grants")).equals(expectedGrantsDigest))throw new IllegalStateException("安装版本或工具授权已变化，请重新审阅");
            boolean declared=false;JSONArray definitions=plugin.getJSONObject("manifest").getJSONArray("tools");for(int j=0;j<definitions.length();j++)if(tool.equals(definitions.getJSONObject(j).getString("name")))declared=true;if(!declared)throw new IllegalArgumentException("此安装没有所选工具");
            JSONArray selected=new JSONArray(),old=plugin.optJSONArray("grants");String exact="tool:"+tool;boolean present=false;
            for(int j=0;old!=null&&j<old.length();j++){String permission=old.getString(j);if(permission.equals(exact)){present=true;if(granted)selected.put(permission);}else selected.put(permission);}
            if(granted&&!present)selected.put(exact);plugin.put("grants",PluginRules.grants(plugin.getJSONObject("manifest"),selected));PluginRevisions.advanceState(plugin);
            JsonFiles.write(file,new JSONObject().put("plugins",all));cancelPlugin(id);return publicPlugin(plugin);
        }throw new IllegalArgumentException("原插件安装不存在");
    }
    /** Native owner only. The private original ledger carrier, not caller JSON, proves discovery. */
    public McpLocalInstallation.Review reviewLocalMcpInstallForOwner(TaskStore tasks,TaskStore.OwnerMcpDiscoveryCompleted completed,dev.pocketengine.features.local.LocalModule local,JSONArray selected,RunControl observation)throws Exception {
        synchronized(this){if(closed)throw new IllegalStateException("原插件宿主已关闭");}return McpLocalInstallation.review(this,tasks,completed,local,selected,observation);
    }
    synchronized void requireLocalMcpInstallationTargetForOwner(JSONObject scope)throws Exception {
        if(closed)throw new IllegalStateException("原插件宿主已关闭");JSONObject current=null;JSONArray all=list();for(int i=0;i<all.length();i++)if(scope.getString("pluginId").equals(all.getJSONObject(i).getString("id")))current=all.getJSONObject(i);
        String expected=scope.getString("expectedInstallationId");if(current==null){if(!expected.isEmpty()||!scope.isNull("expectedStateRevision"))throw new SecurityException("原本机MCP目标安装已移除或变化");}
        else if(!expected.equals(installationId(current))||scope.isNull("expectedStateRevision")||PluginRevisions.stateRevision(current)!=scope.getLong("expectedStateRevision"))throw new SecurityException("原本机MCP目标安装或启停/授权状态已变化，请重新发现");
    }
    public JSONObject installLocalMcpForOwner(McpLocalInstallation.Review review,String expectedDigest)throws Exception {
        JSONObject approved=java.util.Objects.requireNonNull(review).consumeForHost(this,expectedDigest);
        synchronized(this){recoverMcpSecrets();requireLocalMcpInstallationTargetForOwner(approved.getJSONObject("target"));return install(list(),approved.getJSONObject("input"),null,null,approved.getJSONObject("record"));}
    }
    /** The original supervisor uses this read-only discriminator; it is not a model command. */
    public synchronized boolean localMcpForHost(JSONObject nativeInput)throws Exception {
        JSONObject plugin=localPlugin(nativeInput.getString("id"));if(plugin==null)return false;JSONObject tool=McpData.find(plugin.getJSONObject("manifest").getJSONArray("tools"),nativeInput.getString("tool"));return tool!=null&&"mcp".equals(tool.optString("kind"))&&"stdio".equals(tool.optString("transport"));
    }
    private JSONObject localPlugin(String id)throws Exception{JSONArray all=list();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))return all.getJSONObject(i);return null;}
    /** Private installed-program identity plus existing active/cancel slot. It approves no task:
     * the original TaskStore must still verify the concrete consumed approval and prepared row. */
    public static final class LocalMcpProgram implements AutoCloseable {
        private final PluginsModule issuer;private final RunControl original;private final Invocation active;private final String pluginId,toolName,operation,hostTool,arguments,tool,configuration,summary,executionIdentity;
        private volatile boolean closed;
        private LocalMcpProgram(PluginsModule issuer,JSONObject plugin,JSONObject definition,JSONObject args,String operation,String hostTool,RunControl original)throws Exception {
            this.issuer=issuer;this.original=original;pluginId=plugin.getString("id");toolName=definition.getString("name");this.operation=operation;this.hostTool=hostTool;arguments=args.toString();tool=definition.toString();configuration=plugin.getJSONObject("localMcp").getJSONObject("configuration").toString();summary=localProgramSummary(plugin,definition).toString();executionIdentity=TaskStore.capabilityFingerprint(executionBinding(plugin,definition));active=new Invocation(pluginId,original);
        }
        public RunControl originalControl(){return original;}public String operationId(){return operation;}public String hostTool(){return hostTool;}public String remoteToolName(){return toolName;}public String executionIdentity(){return executionIdentity;}
        public JSONObject arguments()throws Exception{return new JSONObject(arguments);}public JSONObject configurationForHost()throws Exception{return new JSONObject(configuration);}public JSONObject scopeForHost()throws Exception{return new JSONObject(summary);}JSONObject toolForHost()throws Exception{return new JSONObject(tool);}
        public void requireCurrentForHost()throws Exception {
            original.check();if(closed||original.isPaused())throw new SecurityException("原本机MCP插件调用已停止或暂停");
            synchronized(issuer){JSONObject plugin=issuer.localPlugin(pluginId);if(issuer.closed||plugin==null||issuer.active.get(operation)!=active)throw new SecurityException("原本机MCP调用或安装已撤回");JSONObject current=PluginRules.permitted(plugin,toolName,new JSONObject(arguments));if(!executionIdentity.equals(TaskStore.capabilityFingerprint(executionBinding(plugin,current)))||!configuration.equals(plugin.getJSONObject("localMcp").getJSONObject("configuration").toString()))throw new SecurityException("原本机MCP安装、配置或工具授权已变化");localProgramSummary(plugin,current);}
            original.check();
        }
        @Override public void close(){closed=true;synchronized(issuer){issuer.active.remove(operation,active);}}
    }
    public LocalMcpProgram localMcpProgramForTask(JSONObject nativeInput,String hostTool,RunControl original)throws Exception {
        original.check();var binding=original.hostRunIdentity();if(binding==null||!binding.taskId().equals(nativeInput.optString("_taskId"))||!binding.runId().equals(nativeInput.optString("_runId")))throw new SecurityException("本机MCP需要原任务真实控制，不能借JSON任务编号启动");
        JSONObject args=PluginArguments.snapshot(nativeInput.getJSONObject("arguments"));for(String key:new String[]{"_nativeMcpJob","_hostOperationId","_consumedApprovalId","_nativeApprovalId","_taskId","_runId"})if(args.has(key))throw new SecurityException("模型工具参数不能提供原生任务/批准/进程证明字段");String operation=nativeInput.getString("_operationId");if(!operation.matches("[a-f0-9-]{36}"))throw new SecurityException("缺少原Runner具体prepared动作编号");
        synchronized(this){if(closed)throw new SecurityException("原插件宿主已关闭");JSONObject plugin=localPlugin(nativeInput.getString("id"));if(plugin==null)throw new SecurityException("原本机MCP安装不存在");JSONObject tool=PluginRules.permitted(plugin,nativeInput.getString("tool"),args);
            if(!"mcp".equals(tool.optString("kind"))||!"stdio".equals(tool.optString("transport"))||!functionName("plugin:"+plugin.getString("id")+":"+tool.getString("name")).equals(hostTool)||!installationId(plugin).equals(nativeInput.getString("expectedInstallationId"))||!plugin.getJSONObject("manifest").getString("version").equals(nativeInput.getString("expectedVersion"))||!TaskStore.capabilityFingerprint(executionBinding(plugin,tool)).equals(nativeInput.getString("expectedExecutionIdentity")))throw new SecurityException("本机MCP准确安装、工具或原生执行身份不同");
            localProgramSummary(plugin,tool);if(active.containsKey(operation)||active.size()>=2)throw new SecurityException("原MCP操作仍在执行或原插件并发额度已满；不重派");for(Invocation current:active.values())if(current.pluginId.equals(plugin.getString("id")))throw new SecurityException("此原本机MCP插件已有请求运行，请核对原操作");
            LocalMcpProgram program=new LocalMcpProgram(this,plugin,tool,args,operation,hostTool,original);active.put(operation,program.active);return program;
        }
    }
    public synchronized JSONObject localMcpApprovalSummaryForHost(JSONObject preset)throws Exception {
        if(!localMcpForHost(preset))return null;JSONObject plugin=localPlugin(preset.getString("id")),tool=McpData.find(plugin.getJSONObject("manifest").getJSONArray("tools"),preset.getString("tool"));if(!TaskStore.capabilityFingerprint(executionBinding(plugin,tool)).equals(preset.getString("expectedExecutionIdentity")))throw new SecurityException("原本机MCP程序身份已变化，请刷新工具清单");return localProgramSummary(plugin,tool);
    }
    private static JSONObject localProgramSummary(JSONObject plugin,JSONObject tool)throws Exception {
        JSONObject record=plugin.getJSONObject("localMcp"),config=record.getJSONObject("configuration");String digest=dev.pocketengine.features.plugins.mcp.McpJson.digest(config);
        if(!"pocketworld.native-stdio-installation.v1".equals(record.optString("format"))||!"stdio".equals(tool.optString("transport"))||!digest.equals(record.getString("configurationDigest"))||!digest.equals(tool.getString("configurationDigest"))||!config.getString("expectedSourceDigest").equals(record.getString("sourceDigest"))||!tool.getString("serverDigest").equals(record.getString("serverDigest")))throw new SecurityException("原本机MCP安装的程序与声明身份不一致");
        JSONObject summary=new JSONObject().put("purpose","native_mcp_invoke").put("pluginId",plugin.getString("id")).put("installationId",installationId(plugin)).put("stateRevision",PluginRevisions.stateRevision(plugin)).put("version",plugin.getJSONObject("manifest").getString("version")).put("tool",tool.getString("name")).put("descriptorDigest",tool.getString("descriptorDigest")).put("configurationDigest",digest).put("sourceDigest",record.getString("sourceDigest")).put("checkoutId",config.getString("checkoutId")).put("runtimeId",config.getString("runtimeId")).put("runtimeIdentity",record.getString("runtimeIdentity")).put("executable",config.getString("executable")).put("entryPoint",config.getJSONArray("argv").getString(0)).put("argvDigest",dev.pocketengine.features.local.project.ProjectSources.digest(new JSONObject().put("argv",config.getJSONArray("argv")))).put("argvCount",config.getJSONArray("argv").length()).put("protocolVersion",config.getString("protocolVersion"));
        for(String field:new String[]{"executableSha256","entryPointSha256","discoveryTaskId","discoveryFactsDigest","serverDigest"})summary.put(field,record.getString(field));if(record.has("packagePreparation")){summary.put("packagePreparation",McpData.copy(record.getJSONObject("packagePreparation"))).put("preparationDigest",record.getString("preparationDigest"));for(String field:new String[]{"launchExecutable","launchArgvDigest","launchArgvCount"})if(record.has(field))summary.put(field,record.get(field));}return summary.put("osSandbox",false).put("hiddenFilesIncluded",false).put("dependencyCachesIncluded",false).put("credentialsSupported",false).put("environmentInputsSupported",false).put("risk",record.has("packagePreparation")?"这次准确工具操作包含上述已审固定依赖准备，再启动原本机程序；仅使用本次副本和同一managed job，可能联网取锁定包。禁生命周期脚本，不自动安装管理器，不重试未知执行或回写原项目；同UID不是OS安全沙箱。":"这次准确工具操作会独立启动上述本机程序及可见副本；相对文件写入留在本次副本，不自动回写原项目，也不保证跨调用相对目录状态。启动本身可能产生效果，同UID不是OS安全沙箱。仅此原任务准确参数审批，不重启、重发或自动下载依赖。");
    }
    private JSONObject install(JSONArray all,JSONObject input,String restoredFrom)throws Exception {return install(all,input,restoredFrom,null);}
    private JSONObject install(JSONArray all,JSONObject input,String restoredFrom,dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost.Credential authorization)throws Exception {
        return install(all,input,restoredFrom,authorization,null);
    }
    private JSONObject install(JSONArray all,JSONObject input,String restoredFrom,dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost.Credential authorization,JSONObject nativeLocalConfiguration)throws Exception {
        JSONObject manifest=PluginRules.manifest(input.getJSONObject("manifest"));String id=manifest.getString("id");JSONArray next=new JSONArray();JSONObject old=null;
        for(int i=0;i<all.length();i++){JSONObject previous=all.getJSONObject(i);if(id.equals(previous.getString("id")))old=previous;else next.put(previous);}
        PluginRevisions.checkExpected(old,input,false);
        if(next.length()>=30)throw new IllegalStateException("最多安装30个声明插件");
        String token=input.optString("token");if(token.length()>4096||token.contains("\r")||token.contains("\n"))throw new IllegalArgumentException("工具凭据格式无效");
        // Each installation owns a distinct credential slot. A failed manifest write cannot replace the old endpoint's token.
        JSONObject plugin=new JSONObject().put("id",id).put("manifest",manifest).put("installationId",UUID.randomUUID().toString()).put("stateRevision",0).put("enabled",false).put("grants",new JSONArray()).put("installedAt",System.currentTimeMillis());
        if(nativeLocalConfiguration!=null){if(authorization!=null||!token.isEmpty())throw new SecurityException("本机MCP首版没有凭据通道");plugin.put("localMcp",JsonFiles.copy(nativeLocalConfiguration));}
        if(authorization!=null){
            JSONObject nativeIdentity=authorization.identity(),source=mcpHttpSource(manifest);
            if(!nativeIdentity.getString("resource").equals(source.getString("endpoint"))||!nativeIdentity.getString("protocolVersion").equals(source.getString("protocolVersion")))throw new SecurityException("原OAuth连接不属于此次安装的准确地址/协议");
            authorization.check(source.getString("endpoint"));JSONArray nativeTools=manifest.getJSONArray("tools");
            for(int n=0;n<nativeTools.length();n++){JSONObject tool=nativeTools.getJSONObject(n);if(!tool.optString("kind").equals("mcp")||!nativeIdentity.getString("resource").equals(tool.getString("endpoint"))||!nativeIdentity.getString("protocolVersion").equals(tool.getString("protocolVersion")))throw new SecurityException("原OAuth连接不属于此次工具安装的准确地址/协议");}
            plugin.put("mcpAuthorization",nativeIdentity);
        }
        plugin.put("history",PluginRevisions.archive(old,System.currentTimeMillis()));
        if(restoredFrom!=null)plugin.put("restoredFromInstallationId",restoredFrom);
        String credentialRef="plugin-install:"+UUID.randomUUID();if(!token.isEmpty()){context.secrets.put(credentialRef,token);plugin.put("credentialRef",credentialRef);}
        next.put(plugin);
        try{JsonFiles.write(file,new JSONObject().put("plugins",next));}
        catch(Exception failure){if(!token.isEmpty())context.secrets.remove(credentialRef);throw failure;}
        cancelPlugin(id);if(old!=null){closeMcp(installationId(old));removeCredential(old);}
        return publicPlugin(plugin);
    }
    private JSONObject invoke(JSONObject input)throws Exception{return invokeForHost(input,new RunControl());}
    /** The supervisor's control reaches the actual HTTP dispatch, including cancellation before registration. */
    public JSONObject invokeForHost(JSONObject input,RunControl hostControl)throws Exception {
        return invokeForHost(input,hostControl,null,"");
    }
    /** Native-only output authority. Neither a descriptor nor model JSON supplies this Factory. */
    public JSONObject invokeForHost(JSONObject input,RunControl hostControl,
            dev.pocketengine.features.memory.ToolOutputCapture.Factory outputFactory,String nativeHostTool)throws Exception {
        return invokeForHost(input,hostControl,outputFactory,nativeHostTool,null);
    }
    public JSONObject invokeForHost(JSONObject input,RunControl hostControl,
            dev.pocketengine.features.memory.ToolOutputCapture.Factory outputFactory,String nativeHostTool,TaskStore nativeTasks)throws Exception {
        final String id=input.getString("id"),name=input.getString("tool"),operationId=input.optString("_operationId",UUID.randomUUID().toString());
        final Object suppliedArguments=arguments(input);
        final JSONObject args;
        final JSONObject tool;final String token,credentialIdentity;final Invocation invocation;final McpClient mcp;
        synchronized(this){
            hostControl.check();recoverMcpSecrets();
            if(closed)throw new IllegalStateException("插件执行器已关闭");
            if(!operationId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("operationId无效");
            if(active.containsKey(operationId))throw new IllegalStateException("此请求仍在执行，请查询原任务结果");
            JSONObject plugin=null;JSONArray all=list();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))plugin=all.getJSONObject(i);
            if(plugin==null)throw new IllegalArgumentException("插件不存在");
            if(input.has("expectedVersion")&&!input.getString("expectedVersion").equals(plugin.getJSONObject("manifest").getString("version")))throw new SecurityException("插件版本已变化，请重新取得工具清单并授权");
            if(input.has("expectedInstallationId")&&!input.getString("expectedInstallationId").equals(installationId(plugin)))throw new SecurityException("插件已重装，旧工具授权不可沿用");
            if(!(suppliedArguments instanceof JSONObject))throw new IllegalArgumentException("请提供一组对象形式的工具参数");
            args=PluginArguments.snapshot((JSONObject)suppliedArguments);
            tool=PluginRules.permitted(plugin,name,args);
            // Native task presets carry identity outside tool arguments; recheck under the current-grant lock.
            if(hostControl.hasHostRun()||input.has("_taskId")||input.has("_runId")||input.has("expectedExecutionIdentity")){
                Object expected=input.opt("expectedExecutionIdentity");
                if(!(expected instanceof String)||!((String)expected).matches("[a-f0-9]{64}")
                        ||!expected.equals(TaskStore.capabilityFingerprint(executionBinding(plugin,tool))))
                    throw new SecurityException("插件执行身份已变化或缺失，请重新取得当前工具清单并审批");
            }
            if(tool.getString("kind").equals("echo"))return new JSONObject().put("operationId",operationId).put("status","completed").put("ok",true).put("kind","echo").put("value",args).put("verified",true);
            if("mcp".equals(tool.getString("kind"))&&"stdio".equals(tool.optString("transport")))throw new SecurityException("本机MCP已安装但原已审任务调用桥尚未可用；不会借HTTP或普通命令启动程序");
            if(active.size()>=2)throw new IllegalStateException("已有两个工具请求运行，请等待或停止后重试");
            String ref=plugin.optString("credentialRef");dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost.Credential authorization=plugin.optJSONObject("mcpAuthorization")==null?null:oauth().credential(plugin.getJSONObject("mcpAuthorization"));token=authorization!=null||ref.isEmpty()?"":context.secrets.get(ref);
            credentialIdentity=mcpCredentialIdentity(plugin);
            if("mcp".equals(tool.getString("kind"))){
                if(!input.has("expectedInstallationId")||!input.has("expectedVersion"))throw new IllegalArgumentException("MCP调用需当前安装与版本身份，请刷新工具清单");
                String installation=installationId(plugin);McpClient existing=mcpClients.get(installation);
                if(existing==null&&McpData.VERSION.equals(tool.getString("protocolVersion"))){
                    existing=authorization==null?mcpFactory.create(tool.getString("endpoint"),token,tool.getString("protocolVersion"),ignored->{}):authorization.client(tool.getString("protocolVersion"),hostControl);mcpClients.put(installation,existing);
                }
                if(existing==null)throw new IllegalStateException("旧版MCP会话已丢失，请重新发现、安装并授权");
                mcp=existing;
                for(Invocation current:active.values())if(current.pluginId.equals(id))throw new IllegalStateException("此MCP插件已有请求运行，请等待后再试");
            }else mcp=null;
            invocation=new Invocation(id,hostControl);active.put(operationId,invocation);
        }
        McpResultCapture output=null;
        try{
            JSONObject result;
            if(mcp!=null){
                if(outputFactory!=null){
                    JSONObject provenance=new JSONObject().put("pluginId",id).put("installationId",input.getString("expectedInstallationId"))
                        .put("version",input.getString("expectedVersion")).put("hostTool",nativeHostTool).put("remoteTool",name)
                        .put("executionIdentity",input.getString("expectedExecutionIdentity"))
                        .put("endpointDigest",McpData.digest(tool.getString("endpoint"))).put("protocolVersion",tool.getString("protocolVersion"))
                        .put("serverDigest",tool.getString("serverDigest")).put("descriptorDigest",tool.getString("descriptorDigest"));
                    output=McpResultCapture.open(outputFactory,nativeHostTool,operationId,invocation.control,provenance);
                }
                if(mcp.needsDiscovery())try{mcp.discover(invocation.control);}catch(Exception unavailable){throw new McpClient.CallFailure(false,true,"MCP重新发现失败，工具未发送，请检查配置后重新导入");}
                String taskSupport=tool.optJSONObject("execution")==null?"optional":tool.getJSONObject("execution").optString("taskSupport","optional");
                if(nativeTasks!=null&&mcp.supportsTasks()&&!"forbidden".equals(taskSupport)){
                    RunControl.HostRunIdentity run=hostControl.hostRunIdentity();if(run==null)throw new McpClient.CallFailure(false,false,"Tasks需要原TaskStore运行身份");
                    McpTaskCoordinator.Binding binding=new McpTaskCoordinator.Binding(run.taskId(),run.runId(),run.epoch(),operationId,input.getString("expectedInstallationId"),credentialIdentity,tool);
                    McpTaskCoordinator coordinator=new McpTaskCoordinator(mcpTaskHost(nativeTasks.mcpTaskHostForRun(nativeHostTool,hostControl),input.getString("expectedExecutionIdentity")),taskTransport(mcp,output));
                    coordinator.start(binding,args,hostControl);
                    return withOutputCapture(nativeTasks.mcpTaskReceiptForOwner(run.taskId(),operationId).put("message","已保存原MCP操作回执；未完成时请在MCP原任务中核对或取消，不重新提交"),output);
                }
                if("required".equals(taskSupport))throw new McpClient.CallFailure(false,false,"此工具必须协商Tasks并使用原TaskStore任务入口，尚未发送调用");
                result=output==null?mcp.call(tool,args,invocation.control):mcp.call(tool,args,invocation.control,output::result);
            }else result=HttpToolClient.invoke(tool.getString("endpoint"),token,name,args,operationId,invocation.control);
            synchronized(this){invocation.control.check();return withOutputCapture(new JSONObject().put("operationId",operationId).put("status","response_received").put("verified",false).put("toolResult",result),output);}
        }catch(McpResultCapture.AdmissionRefused refused){
            // Only Factory admission can issue this type; no transport/save catch is relabelled.
            return new JSONObject().put("operationId",operationId).put("status","failed").put("dispatchStatus","not_dispatched")
                .put("dispatched",false).put("nativeDispatched",false).put("accepted",false).put("requiresPause",true)
                .put("code","mcp_output_admission_denied").put("verified",false).put("message",refused.getMessage());
        }catch(dev.pocketengine.features.memory.ToolOutputCapture.OutputSourceUnavailable refused){
            throw refused;
        }catch(McpClient.CallFailure failure){
            if(output!=null&&!failure.dispatched)output.notDispatched();
            return withOutputCapture(new JSONObject().put("operationId",operationId).put("status",failure.dispatched?"unknown":"failed").put("dispatchStatus",failure.dispatched?"may_have_dispatched":"not_dispatched").put("dispatched",failure.dispatched)
                    .put("cancelRequested",invocation.control.isCancelled()).put("refreshRequired",failure.refreshRequired)
                    .put("verified",false).put("message",failure.getMessage()),output);
        }catch(Exception uncertain){
            return withOutputCapture(new JSONObject().put("operationId",operationId).put("status","unknown").put("cancelRequested",invocation.control.isCancelled()).put("verified",false).put("message",invocation.control.isCancelled()?"工具请求已停止或授权已变化，可能已被处理；请核对服务端结果，勿重复提交":"HTTP工具请求未取得有效回执，可能已被处理；请检查服务端结果，勿直接重复提交"),output);
        }finally{synchronized(this){active.remove(operationId);}}
    }
    private static JSONObject withOutputCapture(JSONObject receipt,McpResultCapture output)throws Exception{
        if(output!=null)receipt.put("outputCapture",output.metadata());return receipt;
    }
    private static String mcpCredentialIdentity(JSONObject plugin)throws Exception {
        if(plugin.optJSONObject("mcpAuthorization")!=null)return "oauth:"+dev.pocketengine.features.plugins.mcp.McpJson.digest(plugin.getJSONObject("mcpAuthorization"));
        String ref=plugin.optString("credentialRef");return ref.isEmpty()?"anonymous:"+installationId(plugin):"slot:"+McpData.digest(ref);
    }
    private JSONObject currentMcpTaskInstallation(McpTaskCoordinator.Binding binding,String executionIdentity)throws Exception {
        if(closed)throw new SecurityException("原MCP宿主已关闭");JSONArray all=list();
        for(int i=0;i<all.length();i++){JSONObject plugin=all.getJSONObject(i);if(!binding.installationId.equals(installationId(plugin)))continue;
            if(!binding.credentialId.equals(mcpCredentialIdentity(plugin)))throw new SecurityException("原MCP任务凭据代次已变化");
            JSONObject declaration=binding.declaration(),live=McpData.find(plugin.getJSONObject("manifest").getJSONArray("tools"),declaration.getString("name"));
            if(live==null||!"mcp".equals(live.optString("kind"))||!binding.endpoint.equals(live.optString("endpoint"))||!binding.serverDigest.equals(live.optString("serverDigest"))||!McpData.VERSION.equals(live.optString("protocolVersion")))throw new SecurityException("原MCP任务安装或服务身份已变化，不能转交新服务");
            if(executionIdentity!=null&&(!plugin.optBoolean("enabled")||!executionIdentity.equals(TaskStore.capabilityFingerprint(executionBinding(plugin,live)))))throw new SecurityException("原MCP任务执行授权已撤回");
            if(plugin.optJSONObject("mcpAuthorization")!=null)oauth().credential(plugin.getJSONObject("mcpAuthorization")).check(binding.endpoint);
            else if(!plugin.optString("credentialRef").isEmpty()&&!context.secrets.contains(plugin.getString("credentialRef")))throw new SecurityException("原MCP任务凭据已撤回");
            return plugin;
        }throw new SecurityException("原MCP任务安装已移除；不会重绑新的安装");
    }
    private McpTaskCoordinator.Host mcpTaskHost(McpTaskCoordinator.Host journal,String executionIdentity){
        return new McpTaskCoordinator.Host(){
            public void check(McpTaskCoordinator.Binding binding,McpTaskCoordinator.Access access)throws Exception {journal.check(binding,access);synchronized(PluginsModule.this){currentMcpTaskInstallation(binding,executionIdentity);}}
            public JSONObject load(McpTaskCoordinator.Binding binding)throws Exception{return journal.load(binding);}
            public void reserve(McpTaskCoordinator.Binding binding,String digest,JSONObject receipt)throws Exception{journal.reserve(binding,digest,receipt);}
            public void save(McpTaskCoordinator.Binding binding,JSONObject receipt)throws Exception{journal.save(binding,receipt);}
            public boolean reserveCancellation(McpTaskCoordinator.Binding binding)throws Exception{return journal.reserveCancellation(binding);}
        };
    }
    private static McpTaskCoordinator.Transport taskTransport(McpClient client,McpResultCapture capture){
        return new McpTaskCoordinator.Transport(){
            public JSONObject start(JSONObject declaration,JSONObject arguments,RunControl control)throws Exception{return capture==null?client.start(declaration,arguments,control):client.start(declaration,arguments,control,capture::result);}
            public JSONObject query(JSONObject declaration,String task,RunControl control)throws Exception{return client.query(declaration,task,control);}
            public JSONObject query(JSONObject declaration,String task,RunControl control,JSONObject originalReceipt)throws Exception{return capture==null?client.query(declaration,task,control):client.query(declaration,task,control,originalReceipt,capture::result);}
            public void cancel(JSONObject declaration,String task,RunControl control)throws Exception{client.cancel(declaration,task,control);}
        };
    }
    /** Owner-only query/cancel of an exact durable operation; a new connection cannot execute it. */
    public JSONObject reconcileMcpTaskForOwner(TaskStore tasks,String taskId,String operationId,boolean cancel,RunControl observation,String requestId)throws Exception {
        if(!UUID.fromString(requestId).toString().equals(requestId))throw new SecurityException("需要原核对请求身份");
        McpTaskCoordinator.Binding binding=tasks.mcpTaskBindingForOwner(taskId,operationId);observation.bindHostRun(binding.taskId,binding.runId,binding.epoch);
        McpTaskCoordinator.Host journal=mcpTaskHost(tasks.mcpTaskHostForOwner(taskId,operationId),null);journal.check(binding,cancel?McpTaskCoordinator.Access.CANCEL:McpTaskCoordinator.Access.QUERY);
        final McpClient client;
        synchronized(this){JSONObject plugin=currentMcpTaskInstallation(binding,null);if(active.containsKey(requestId)||active.size()>=2)throw new SecurityException("已有原MCP请求，请等待或取消后核对");for(Invocation pending:active.values())if(pending.pluginId.equals(plugin.getString("id")))throw new SecurityException("此安装仍有原请求运行，请先核对或停止原请求");
            var auth=plugin.optJSONObject("mcpAuthorization")==null?null:oauth().credential(plugin.getJSONObject("mcpAuthorization"));String ref=plugin.optString("credentialRef"),token=auth!=null||ref.isEmpty()?"":context.secrets.get(ref);
            client=auth==null?mcpFactory.create(binding.endpoint,token,McpData.VERSION,ignored->{}):auth.client(McpData.VERSION,observation);active.put(requestId,new Invocation(plugin.getString("id"),observation));
        }
        try(client){
            JSONObject stored=tasks.mcpTaskReceiptForOwner(taskId,operationId),receipt=stored.getJSONObject(dev.pocketengine.store.TaskStoreMcpTasks.KEY).getJSONObject("receipt");
            // A lost creation ACK or already resolved receipt needs no network at all.
            if(!receipt.has("remoteTaskId")||receipt.optBoolean("resolved")){if(cancel)new McpTaskCoordinator(journal,client).cancel(binding,observation);return tasks.mcpTaskReceiptForOwner(taskId,operationId).put("networkRequested",false);}
            if(!cancel&&(receipt.optBoolean("receiptExpired")||receipt.has("ttlMs")&&!receipt.isNull("ttlMs")&&System.currentTimeMillis()>=Math.addExact(java.time.Instant.parse(receipt.getString("createdAt")).toEpochMilli(),receipt.getLong("ttlMs")))){new McpTaskCoordinator(journal,client).query(binding,observation);return tasks.mcpTaskReceiptForOwner(taskId,operationId).put("networkRequested",false);}
            if(!cancel&&System.currentTimeMillis()<receipt.optLong("nextPollAt"))return stored.put("networkRequested",false).put("waitingForPollInterval",true);
            client.connectForTask(binding.declaration(),observation);String nativeTool=stored.getJSONObject(dev.pocketengine.store.TaskStoreMcpTasks.KEY).getString("nativeTool");
            McpResultCapture output=cancel?null:McpResultCapture.open(tasks.mcpTaskRecoveryOutput(taskId,operationId,observation),nativeTool,operationId,observation,new JSONObject().put("installationId",binding.installationId).put("serverDigest",binding.serverDigest).put("descriptorDigest",binding.descriptorDigest).put("endpointDigest",McpData.digest(binding.endpoint)).put("protocolVersion",McpData.VERSION).put("recovery",true));
            McpTaskCoordinator coordinator=new McpTaskCoordinator(journal,taskTransport(client,output));if(cancel)coordinator.cancel(binding,observation);else coordinator.query(binding,observation);
            return tasks.recordMcpTaskObservationForOwner(taskId,operationId,withOutputCapture(tasks.mcpTaskReceiptForOwner(taskId,operationId),output)).put("networkRequested",true);
        }finally{synchronized(this){active.remove(requestId);}}
    }
    /** Native owner source browser only. A resource URI is data, never a tool alias or a file path. */
    public JSONObject listMcpResources(String id,String installation,long revision,RunControl original,String operation)throws Exception {
        try(McpSourceConnection source=openMcpSource(id,installation,revision,original,operation)){
            source.discover();JSONArray resources=source.client.resources(original);for(int i=0;i<resources.length();i++){JSONObject row=resources.getJSONObject(i);String digest=McpData.digest(row);row.put("descriptorDigest",digest);}original.check();synchronized(this){currentMcpSourceInstallation(source.binding);}
            return new JSONObject().put("source",McpData.copy(source.binding)).put("operationId",operation).put("resources",resources).put("metadataOnly",true).put("untrustedReference",true).put("executionAuthorized",false);
        }
    }
    public JSONObject getMcpResource(String id,String installation,long revision,String uri,String digest,RunControl original,String operation)throws Exception {
        try(McpResourceEvidence.Snapshot snapshot=readMcpResourceForOwner(id,installation,revision,uri,digest,original,operation)){return snapshot.view();}
    }
    /** Native-only retained bytes. The UI receives a handle, never authority to replace the body. */
    public McpResourceEvidence.Snapshot readMcpResourceForOwner(String id,String installation,long revision,String uri,String digest,RunControl original,String operation)throws Exception {
        try(McpSourceConnection source=openMcpSource(id,installation,revision,original,operation)){
            source.discover();JSONObject result=source.client.resource(uri,digest,original);original.check();synchronized(this){currentMcpSourceInstallation(source.binding);}
            JSONObject binding=McpData.copy(source.binding);
            return McpResourceEvidence.captured(result.put("source",binding).put("operationId",operation).put("executionAuthorized",false),()->{synchronized(PluginsModule.this){currentMcpSourceInstallation(binding);}});
        }
    }
    /** A bounded explicit observation, not a persistent background listener or an execution replay. */
    public JSONObject listenMcpUpdates(String id,String installation,long revision,JSONObject filter,RunControl original,String operation)throws Exception {
        try(McpSourceConnection source=openMcpSource(id,installation,revision,original,operation)){
            source.discover();JSONObject result=source.client.listenUpdates(filter,original);original.check();
            synchronized(this){
                JSONObject current=currentMcpSourceInstallation(source.binding);JSONArray updates=result.getJSONArray("updates");
                if(updates.length()>0){
                    boolean toolsChanged=false,allResources=false;java.util.Set<String> resources=new java.util.HashSet<>();
                    for(int i=0;i<updates.length();i++){JSONObject update=updates.getJSONObject(i);String method=update.getString("method");toolsChanged|="notifications/tools/list_changed".equals(method);allResources|="notifications/resources/list_changed".equals(method);if("notifications/resources/updated".equals(method))resources.add(update.getString("uri"));}
                    JSONArray known=current.optJSONArray("mcpSkillSources");for(int i=0;known!=null&&i<known.length();i++){JSONObject row=known.getJSONObject(i);if(allResources||resources.contains(row.getString("resourceURI")))row.put("availability","unknown").put("sourceGeneration",nextMcpSourceGeneration(row));}
                    if(toolsChanged)current.put("mcpCatalogueInvalidated",true);
                    PluginRevisions.advanceState(current);JSONArray all=list();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))all.put(i,current);JsonFiles.write(file,new JSONObject().put("plugins",all));closeMcp(installation);
                    for(var activeCall:active.entrySet())if(!operation.equals(activeCall.getKey())&&id.equals(activeCall.getValue().pluginId))activeCall.getValue().control.cancel();
                    result.put("sourceInvalidated",true).put("currentStateRevision",PluginRevisions.stateRevision(current)).put("toolRediscoveryRequired",toolsChanged);
                }
            }
            return result.put("source",McpData.copy(source.binding)).put("operationId",operation).put("executionAuthorized",false);
        }
    }
    public JSONObject listMcpSkillResources(String id,String expectedInstallationId,long expectedStateRevision,RunControl original)throws Exception {
        JSONObject attempted=null;
        try(McpSourceConnection source=openMcpSource(id,expectedInstallationId,expectedStateRevision,original)){
            attempted=source.binding;
            source.discover();JSONArray resources=source.client.resources(original),skills=new JSONArray();
            for(int i=0;i<resources.length();i++){
                JSONObject resource=resources.getJSONObject(i);String uri=resource.getString("uri");
                if(!uri.startsWith("skill://"))continue;McpSkillSource.validateUri(uri);skills.put(resource);
            }
            synchronized(this){
                original.check();JSONObject current=currentMcpSourceInstallation(source.binding);JSONArray observed=current.optJSONArray("mcpSkillSources");boolean changed=false;
                for(int i=0;observed!=null&&i<observed.length();i++){
                    JSONObject known=observed.getJSONObject(i);boolean present=false;for(int j=0;j<skills.length();j++)if(known.getString("resourceURI").equals(skills.getJSONObject(j).getString("uri")))present=true;
                    if(!present&&!"missing".equals(known.optString("availability"))){known.put("availability","missing").put("sourceGeneration",nextMcpSourceGeneration(known));changed=true;}
                }
                if(changed){JSONArray all=list();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))all.put(i,current);JsonFiles.write(file,new JSONObject().put("plugins",all));}
            }
            return new JSONObject().put("source",McpData.copy(source.binding)).put("resources",skills).put("untrustedReference",true)
                    .put("metadataOnly",true).put("executionAuthorized",false).put("relativeResourceResolver",false);
        }catch(Exception failure){
            if(attempted!=null)try{markMcpSkillSourceUnavailable(attempted,null);}catch(Exception unavailable){failure.addSuppressed(unavailable);}throw failure;
        }
    }
    /** Actual bytes and native source pin. The generic feature handler cannot manufacture this value. */
    public McpSkillSource.Fetched readMcpSkillSource(String id,String expectedInstallationId,long expectedStateRevision,String resourceURI,RunControl original)throws Exception {
        McpSkillSource.validateUri(resourceURI);JSONObject attempted=null;
        try(McpSourceConnection source=openMcpSource(id,expectedInstallationId,expectedStateRevision,original)){
            attempted=source.binding;
            source.discover();String text=source.client.readSkillResource(resourceURI,original);long now=System.currentTimeMillis();boolean changed=false;
            JSONObject binding=McpData.copy(source.binding).put("resourceURI",resourceURI).put("contentDigest",McpSkillSource.sha256(text))
                    .put("retrievedAt",now).put("relativeResourcePolicy","unavailable_no_local_root").put("hooksAllowed",false);
            synchronized(this){
                original.check();JSONObject current=currentMcpSourceInstallation(binding);JSONArray observed=current.optJSONArray("mcpSkillSources");if(observed==null)observed=new JSONArray();
                boolean found=false;for(int i=0;i<observed.length();i++)if(resourceURI.equals(observed.getJSONObject(i).optString("resourceURI"))){
                    JSONObject previous=observed.getJSONObject(i);changed=!binding.getString("contentDigest").equals(previous.optString("contentDigest"));
                    long generation=changed||!"available".equals(previous.optString("availability"))?nextMcpSourceGeneration(previous):Math.max(1,previous.optLong("sourceGeneration",0));
                    binding.put("sourceGeneration",generation);observed.put(i,new JSONObject().put("resourceURI",resourceURI).put("contentDigest",binding.getString("contentDigest")).put("retrievedAt",now).put("availability","available").put("sourceGeneration",generation));found=true;break;
                }
                if(!found){if(observed.length()>=96)throw new IllegalStateException("此MCP安装已有96项技能来源记录，请先审阅来源范围");binding.put("sourceGeneration",1);observed.put(new JSONObject().put("resourceURI",resourceURI).put("contentDigest",binding.getString("contentDigest")).put("retrievedAt",now).put("availability","available").put("sourceGeneration",1));}
                current.put("mcpSkillSources",observed);JSONArray all=list();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))all.put(i,current);
                JsonFiles.write(file,new JSONObject().put("plugins",all));
            }
            if(changed)context.emit(id(),new JSONObject().put("event","mcp_skill_source_changed").put("pluginId",id).put("installationId",expectedInstallationId).put("resourceURI",resourceURI).put("contentDigest",binding.getString("contentDigest")));
            return new McpSkillSource.Fetched(binding,text,this::assertMcpSkillSourceCurrent);
        }catch(Exception failure){
            if(attempted!=null)try{markMcpSkillSourceUnavailable(attempted,resourceURI);}catch(Exception unavailable){failure.addSuppressed(unavailable);}throw failure;
        }
    }
    /** Current private installation + latest observed remote bytes. No network or credential is returned. */
    public synchronized void assertMcpSkillSourceCurrent(JSONObject nativeBinding)throws Exception {
        JSONObject current=currentMcpSourceInstallation(nativeBinding);String uri=nativeBinding.getString("resourceURI");McpSkillSource.validateUri(uri);
        if(!(nativeBinding.opt("sourceGeneration") instanceof Number)||nativeBinding.getLong("sourceGeneration")<1)throw new SecurityException("MCP技能来源缺少当前不可复用代次，请重新读取并审阅");
        JSONArray observed=current.optJSONArray("mcpSkillSources");for(int i=0;observed!=null&&i<observed.length();i++){
            JSONObject row=observed.getJSONObject(i);if(uri.equals(row.optString("resourceURI"))&&"available".equals(row.optString("availability"))&&nativeBinding.getLong("sourceGeneration")==row.optLong("sourceGeneration",0)&&nativeBinding.getString("contentDigest").equals(row.optString("contentDigest")))return;
        }
        throw new SecurityException("MCP技能来源内容已变化或没有原生读取证据，请重新读取并审阅");
    }
    private synchronized void markMcpSkillSourceUnavailable(JSONObject binding,String uri)throws Exception {
        JSONObject current=currentMcpSourceInstallation(binding);JSONArray observed=current.optJSONArray("mcpSkillSources");boolean changed=false;
        for(int i=0;observed!=null&&i<observed.length();i++){
            JSONObject row=observed.getJSONObject(i);if((uri==null||uri.equals(row.optString("resourceURI")))&&!"unknown".equals(row.optString("availability"))){row.put("availability","unknown").put("sourceGeneration",nextMcpSourceGeneration(row));changed=true;}
        }
        if(changed){JSONArray all=list();for(int i=0;i<all.length();i++)if(binding.getString("pluginId").equals(all.getJSONObject(i).getString("id")))all.put(i,current);JsonFiles.write(file,new JSONObject().put("plugins",all));}
    }
    private static long nextMcpSourceGeneration(JSONObject previous){long known=previous.optLong("sourceGeneration",0);if(known<0)throw new IllegalStateException("MCP技能来源代次损坏");return Math.addExact(known,1);}
    private JSONObject currentMcpSourceInstallation(JSONObject binding)throws Exception {
        if(closed)throw new IllegalStateException("原MCP插件宿主已关闭");JSONArray all=list();
        for(int i=0;i<all.length();i++){
            JSONObject plugin=all.getJSONObject(i);if(!binding.getString("pluginId").equals(plugin.getString("id")))continue;
            if(!binding.getString("installationId").equals(installationId(plugin))||binding.getLong("stateRevision")!=PluginRevisions.stateRevision(plugin))throw new SecurityException("MCP安装或配置版本已变化，请重新读取来源");
            JSONObject manifest=plugin.getJSONObject("manifest"),source=mcpHttpSource(manifest);
            if(!binding.getString("endpointDigest").equals(McpData.digest(source.getString("endpoint")))||!binding.getString("protocolVersion").equals(source.getString("protocolVersion"))||!binding.getString("serverDigest").equals(source.getString("serverDigest")))throw new SecurityException("MCP来源不属于此安装的准确服务");
            JSONArray tools=manifest.getJSONArray("tools");
            for(int j=0;j<tools.length();j++){
                JSONObject tool=tools.getJSONObject(j);if(!"mcp".equals(tool.optString("kind"))||!binding.getString("endpointDigest").equals(McpData.digest(tool.getString("endpoint")))
                        ||!binding.getString("protocolVersion").equals(tool.getString("protocolVersion"))||!binding.getString("serverDigest").equals(tool.getString("serverDigest")))throw new SecurityException("MCP技能来源不属于此安装的准确服务");
            }
            if(plugin.optJSONObject("mcpAuthorization")!=null)oauth().credential(plugin.getJSONObject("mcpAuthorization")).check(source.getString("endpoint"));
            else if(!plugin.optString("credentialRef").isEmpty()&&!context.secrets.contains(plugin.getString("credentialRef")))throw new SecurityException("MCP技能来源凭据已撤销");
            return plugin;
        }
        throw new SecurityException("MCP技能来源安装已移除");
    }
    /** New source-only installs have an explicit identity; old tool installs retain their exact binding. */
    private static JSONObject mcpHttpSource(JSONObject manifest)throws Exception {
        if(manifest.has("mcpSource"))return PluginRules.mcpSource(manifest.getJSONObject("mcpSource"));
        JSONArray tools=manifest.getJSONArray("tools");
        if(tools.length()==0||!"mcp".equals(tools.getJSONObject(0).optString("kind")))throw new IllegalArgumentException("请选择原生登记的MCP安装");
        JSONObject tool=tools.getJSONObject(0);
        if("stdio".equals(tool.optString("transport")))throw new UnsupportedOperationException("本机stdio程序不能借HTTP只读探测/来源权限启动；此入口仅HTTP，stdio仅原生明确发现与原已审任务工具调用");
        return PluginRules.mcpSource(new JSONObject().put("endpoint",tool.getString("endpoint")).put("protocolVersion",tool.getString("protocolVersion")).put("serverDigest",tool.getString("serverDigest")));
    }
    /** Same exact native installation and source connection; liveness adds no install/grant authority. */
    public JSONObject pingMcpInstallation(String id,String expectedInstallationId,long expectedStateRevision,RunControl original)throws Exception {
        return pingMcpInstallation(id,expectedInstallationId,expectedStateRevision,original,UUID.randomUUID().toString());
    }
    /** The native caller chooses a known operation reference so the existing cancel route can stop I/O. */
    public JSONObject pingMcpInstallation(String id,String expectedInstallationId,long expectedStateRevision,RunControl original,String operationId)throws Exception {
        if(original==null||expectedStateRevision<0||operationId==null||!UUID.fromString(operationId).toString().equals(operationId))throw new IllegalArgumentException("MCP健康检查需要原控制、准确安装修订和原生操作身份");
        original.check();try(McpSourceConnection source=openMcpSource(id,expectedInstallationId,expectedStateRevision,original,operationId)){
            source.discover();original.check();synchronized(this){currentMcpSourceInstallation(source.binding);}
            long identityObservedAt=System.currentTimeMillis();JSONObject result=source.client.ping(original);original.check();JSONObject installed;
            synchronized(this){installed=currentMcpSourceInstallation(source.binding);}
            return result.put("source",McpData.copy(source.binding)).put("operationId",operationId).put("identityVerification","fresh_discovery_before_ping").put("identityObservedAt",identityObservedAt)
                .put("installationEnabled",installed.optBoolean("enabled")).put("installationChanged",false).put("toolGrantsChanged",false).put("installedOnly",true);
        }
    }
    /** Native owner catalogue for an already reviewed installation; selecting metadata adds no grant. */
    public JSONObject listMcpPrompts(String id,String expectedInstallationId,long expectedStateRevision,RunControl original,String operationId)throws Exception {
        if(original==null||expectedStateRevision<0||operationId==null||!UUID.fromString(operationId).toString().equals(operationId))throw new IllegalArgumentException("MCP提示目录需要原控制、准确安装修订和原生操作身份");
        original.check();try(McpSourceConnection source=openMcpSource(id,expectedInstallationId,expectedStateRevision,original,operationId)){
            source.discover();JSONArray definitions=source.client.prompts(original);original.check();synchronized(this){currentMcpSourceInstallation(source.binding);}
            return new JSONObject().put("source",McpData.copy(source.binding)).put("operationId",operationId).put("prompts",McpPromptData.catalogue(definitions)).put("untrustedReference",true).put("metadataOnly",true).put("toolGrantsChanged",false).put("automaticConversationInjection",false).put("authorityAdded",false);
        }
    }
    /** Exact current declaration/arguments only. Returned user/assistant roles remain reference data. */
    public JSONObject getMcpPrompt(String id,String expectedInstallationId,long expectedStateRevision,String name,String expectedDescriptorDigest,JSONObject arguments,RunControl original,String operationId)throws Exception {
        if(original==null||expectedStateRevision<0||operationId==null||!UUID.fromString(operationId).toString().equals(operationId))throw new IllegalArgumentException("MCP提示读取需要原控制、准确安装修订和原生操作身份");
        original.check();try(McpSourceConnection source=openMcpSource(id,expectedInstallationId,expectedStateRevision,original,operationId)){
            source.discover();JSONObject result=source.client.prompt(name,expectedDescriptorDigest,arguments,original);original.check();synchronized(this){currentMcpSourceInstallation(source.binding);}
            return result.put("source",McpData.copy(source.binding)).put("operationId",operationId).put("toolGrantsChanged",false).put("installedOnly",true);
        }
    }
    private synchronized McpSourceConnection openMcpSource(String id,String installation,long stateRevision,RunControl control)throws Exception {
        return openMcpSource(id,installation,stateRevision,control,UUID.randomUUID().toString());
    }
    private synchronized McpSourceConnection openMcpSource(String id,String installation,long stateRevision,RunControl control,String operation)throws Exception {
        if(control==null||stateRevision<0||!UUID.fromString(operation).toString().equals(operation))throw new SecurityException("来源读取需要原控制、安装修订和准确请求身份");
        control.check();if(closed)throw new IllegalStateException("原MCP插件宿主已关闭");recoverMcpSecrets();
        if(active.size()>=2)throw new IllegalStateException("已有两个工具或来源请求运行，请等待或停止后重试");
        if(active.containsKey(operation))throw new SecurityException("原MCP操作引用已在使用，不替换或接管现有调用");
        JSONArray all=list();for(int i=0;i<all.length();i++){
            JSONObject plugin=all.getJSONObject(i);if(!id.equals(plugin.getString("id")))continue;
            if(!installation.equals(installationId(plugin))||stateRevision!=PluginRevisions.stateRevision(plugin))throw new SecurityException("原MCP安装或修订已变化");
            JSONObject source=mcpHttpSource(plugin.getJSONObject("manifest")),binding=new JSONObject().put("pluginId",id).put("installationId",installation).put("stateRevision",stateRevision)
                    .put("endpointDigest",McpData.digest(source.getString("endpoint"))).put("protocolVersion",source.getString("protocolVersion")).put("serverDigest",source.getString("serverDigest"));
            currentMcpSourceInstallation(binding);
            dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost.Credential authorization=plugin.optJSONObject("mcpAuthorization")==null?null:oauth().credential(plugin.getJSONObject("mcpAuthorization"));
            String ref=plugin.optString("credentialRef"),token=authorization!=null||ref.isEmpty()?"":context.secrets.get(ref);
            McpClient client=authorization==null?mcpFactory.create(source.getString("endpoint"),token,source.getString("protocolVersion"),ignored->{}):authorization.client(source.getString("protocolVersion"),control);
            active.put(operation,new Invocation(id,control));return new McpSourceConnection(operation,binding,client,control);
        }
        throw new IllegalArgumentException("MCP安装不存在");
    }
    private final class McpSourceConnection implements AutoCloseable {
        final String operation;final JSONObject binding;final McpClient client;final RunControl control;
        McpSourceConnection(String operation,JSONObject binding,McpClient client,RunControl control){this.operation=operation;this.binding=binding;this.client=client;this.control=control;}
        void discover()throws Exception{client.discover(control);if(!binding.getString("serverDigest").equals(client.serverDigest()))throw new SecurityException("MCP服务身份已变化，请重新发现安装后读取来源");}
        @Override public void close(){client.close();synchronized(PluginsModule.this){active.remove(operation);}}
    }
    private JSONObject discoverMcp(JSONObject input)throws Exception {return discoverMcp(input,null);}
    private JSONObject discoverMcp(JSONObject input,dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost.Credential authorization)throws Exception {
        String id=input.getString("id").trim(),name=input.getString("name").trim(),endpoint=input.getString("endpoint").trim();
        String version=input.optString("protocolVersion",McpData.VERSION),token=input.optString("token");
        boolean pinnedInstallation=input.has("expectedInstallationId"),pinnedState=input.has("expectedStateRevision");
        if(pinnedInstallation!=pinnedState)throw new IllegalArgumentException("MCP发现需要完整的安装身份和状态版本");
        String reviewedInstallation=null;long reviewedState=-1;
        if(pinnedInstallation){
            Object identity=input.get("expectedInstallationId"),revision=input.get("expectedStateRevision");
            if(!(identity instanceof String)||!(revision instanceof Number))throw new IllegalArgumentException("MCP发现安装身份或状态版本无效");
            reviewedInstallation=(String)identity;
            try{reviewedState=new java.math.BigDecimal(revision.toString()).longValueExact();}catch(Exception invalid){throw new IllegalArgumentException("MCP发现安装身份或状态版本无效");}
            if(reviewedState< -1)throw new IllegalArgumentException("MCP发现安装身份或状态版本无效");
        }
        if(!id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,79}")||name.isEmpty()||name.length()>80)throw new IllegalArgumentException("插件ID或名称无效");
        PluginRules.validateEndpoint(endpoint);McpClient.validateToken(token);
        if(!token.isEmpty()&&(id.contains(token)||name.contains(token)||endpoint.contains(token)))throw new IllegalArgumentException("配置名称或地址不得包含凭据");
        String operationId=input.optString("_operationId",UUID.randomUUID().toString());
        if(!operationId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("operationId无效");
        String ref="plugin-mcp-preview:"+UUID.randomUUID(),expected="";RunControl control=new RunControl();McpClient client=authorization==null?mcpFactory.create(endpoint,token,version,ignored->{}):authorization.client(version,control);
        synchronized(this){
            if(closed)throw new IllegalStateException("插件执行器已关闭");recoverMcpSecrets();pruneMcpPreviews();
            if(active.size()>=2||mcpPreviews.size()+active.size()>=4)throw new IllegalStateException("请先结束请求或关闭旧的MCP导入预览");
            if(active.containsKey(operationId))throw new IllegalStateException("发现请求仍在运行");
            long currentState=-1;JSONArray all=list();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id"))){expected=installationId(all.getJSONObject(i));currentState=PluginRevisions.stateRevision(all.getJSONObject(i));}
            if(pinnedInstallation&&(!expected.equals(reviewedInstallation)||currentState!=reviewedState)){client.close();throw new IllegalStateException("插件安装或状态已变化，请刷新后重新审阅MCP配置");}
            if(!token.isEmpty()){
                previewSlots.add(ref);persistPreviewSlots();
                try{context.secrets.put(ref,token);}catch(Exception failure){
                    try{context.secrets.remove(ref);previewSlots.remove(ref);persistPreviewSlots();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;
                }
            }
            active.put(operationId,new Invocation(id,control));
        }
        boolean retained=false;
        try{
            JSONArray definitions=client.discover(control),rows=new JSONArray();
            for(int i=0;i<definitions.length();i++)rows.put(McpData.previewTool(definitions.getJSONObject(i),endpoint,version,client.serverDigest()));
            synchronized(this){
                control.check();if(closed)throw new IllegalStateException("插件执行器已关闭");
                String previewId=UUID.randomUUID().toString();long expiresAt=System.currentTimeMillis()+MCP_PREVIEW_TTL;
                mcpPreviews.put(previewId,new McpPreview(id,name,ref,expected,definitions,client,expiresAt,authorization));retained=true;
                return new JSONObject().put("previewId",previewId).put("operationId",operationId).put("id",id).put("name",name).put("endpoint",endpoint)
                        .put("protocolVersion",version).put("serverInfo",client.serverInfo()).put("serverDigest",client.serverDigest()).put("serverCapabilities",client.serverCapabilities())
                        .put("mcpSource",McpData.sourceDescriptor(endpoint,version,client.serverDigest(),client.serverInfo(),client.serverCapabilities())).put("tools",rows).put("supportedSchemas",McpData.support())
                        .put("expiresAt",expiresAt).put("expectedInstallationId",expected).put("enabledAfterInstall",false).put("grantsAfterInstall",new JSONArray())
                        .put("hasToken",authorization!=null||!token.isEmpty()).put("untrusted",true).put("installed",false)
                        .put("message","服务描述仅供参考，不是授权。可选择工具，或仅安装声明的资源与提示来源；执行工具仍需启用和逐项授权。");
            }
        }catch(Exception failure){
            throw new IllegalStateException(control.isCancelled()?"MCP发现已停止，未安装任何工具":"MCP发现失败："+McpData.redact(safeMcpFailure(failure),token));
        }finally{
            synchronized(this){active.remove(operationId);if(!retained){client.close();if(!token.isEmpty())removePreviewSlot(ref);}}
        }
    }
    private static String safeMcpFailure(Exception failure){
        // Parser, socket and vault exception text can contain untrusted payloads or credential material.
        return failure instanceof InterruptedException?"操作已停止":McpClient.diagnostic(failure);
    }
    private JSONObject installMcp(JSONArray all,JSONObject input)throws Exception {
        String previewId=input.getString("previewId");McpPreview preview=mcpPreviews.get(previewId);
        if(preview==null||System.currentTimeMillis()>=preview.expiresAt||!preview.client.usable())throw new IllegalStateException("MCP预览已过期或失效，请重新发现");
        if(input.has("expectedInstallationId")&&!preview.expected.equals(input.getString("expectedInstallationId")))throw new IllegalStateException("MCP预览安装身份不符");
        JSONObject manifest=McpData.manifest(preview.id,preview.name,preview.client.endpoint(),preview.client.version(),preview.client.serverDigest(),preview.definitions,input.getJSONArray("tools"),preview.client.serverInfo(),preview.client.serverCapabilities());
        JSONObject request=new JSONObject().put("manifest",manifest).put("expectedInstallationId",preview.expected);
        if(preview.authorization!=null)preview.authorization.check(preview.client.endpoint());else if(context.secrets.contains(preview.credentialRef))request.put("token",context.secrets.get(preview.credentialRef));
        JSONObject installed=install(all,request,null,preview.authorization);
        mcpClients.put(installed.getString("installationId"),preview.client);mcpPreviews.remove(previewId);releasePreview(preview,false);
        return installed.put("mcpStatus","ready");
    }
    private static void rejectDirectMcp(JSONObject manifest)throws Exception {
        if(manifest.has("mcpSource"))throw new IllegalArgumentException("MCP来源安装或历史恢复需重新发现服务，不能直接信任粘贴的来源声明");
        JSONArray tools=manifest.getJSONArray("tools");for(int i=0;i<tools.length();i++)if("mcp".equals(tools.getJSONObject(i).optString("kind")))
            throw new IllegalArgumentException("MCP安装或历史恢复需重新发现服务并从当前预览导入，不能直接信任粘贴的远端声明");
    }
    private void pruneMcpPreviews()throws Exception {
        java.util.Iterator<McpPreview> iterator=mcpPreviews.values().iterator();while(iterator.hasNext()){
            McpPreview preview=iterator.next();if(System.currentTimeMillis()>=preview.expiresAt||!preview.client.usable()){iterator.remove();releasePreview(preview,true);}
        }
    }
    private void releasePreview(McpPreview preview,boolean close)throws Exception {
        if(close)preview.client.close();removePreviewSlot(preview.credentialRef);
    }
    private void recoverMcpSecrets()throws Exception {
        if(recoveredPreviewSlots)return;
        JSONArray abandoned=JsonFiles.read(previewSlotsFile).optJSONArray("slots");
        if(abandoned!=null){
            if(abandoned.length()>8)throw new IllegalStateException("MCP预览凭据恢复记录异常");
            for(int i=0;i<abandoned.length();i++){
                String ref=abandoned.getString(i);
                if(!ref.matches("plugin-mcp-preview:[a-f0-9-]{36}"))throw new IllegalStateException("MCP预览凭据用途无效");
                context.secrets.remove(ref);
            }
            persistPreviewSlots();
        }
        recoveredPreviewSlots=true;
    }
    private void persistPreviewSlots()throws Exception {JsonFiles.write(previewSlotsFile,new JSONObject().put("slots",new JSONArray(previewSlots)));}
    private void removePreviewSlot(String ref)throws Exception {
        if(!previewSlots.contains(ref))return;
        context.secrets.remove(ref);previewSlots.remove(ref);persistPreviewSlots();
    }
    private void closeMcp(String installation){McpClient client=mcpClients.remove(installation);if(client!=null)client.close();}
    private static final class McpPreview {
        final String id,name,credentialRef,expected;final JSONArray definitions;final McpClient client;final long expiresAt;final dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost.Credential authorization;
        McpPreview(String id,String name,String credentialRef,String expected,JSONArray definitions,McpClient client,long expiresAt,dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost.Credential authorization){
            this.authorization=authorization;this.id=id;this.name=name;this.credentialRef=credentialRef;this.expected=expected;this.definitions=definitions;this.client=client;this.expiresAt=expiresAt;
        }
    }
    private static Object arguments(JSONObject input)throws Exception{return input.has("arguments")?input.get("arguments"):new JSONObject();}
    private JSONObject publicPlugin(JSONObject plugin)throws Exception {
        JSONObject result=JsonFiles.copy(plugin);String ref=result.optString("credentialRef");result.remove("credentialRef");
        JSONObject local=result.optJSONObject("localMcp");if(local!=null)local.remove("configuration"); // private exact argv stays in the original manifest store, not generic snapshots.
        JSONArray history=result.optJSONArray("history");result.remove("history");result.put("historyCount",history==null?0:Math.min(history.length(),PluginRevisions.HISTORY_LIMIT));
        JSONArray definitions=plugin.getJSONObject("manifest").getJSONArray("tools");
        for(int i=0;i<definitions.length();i++)if("mcp".equals(definitions.getJSONObject(i).optString("kind"))){
            if("stdio".equals(definitions.getJSONObject(i).optString("transport"))){result.put("mcpStatus","original_task_revalidation_required").put("localTaskInvocationImplemented",true).put("runtimeVerified",false);break;}
            McpClient client=mcpClients.get(installationId(plugin));
            result.put("mcpStatus",client!=null?(client.usable()?"ready":"needs_discovery"):
                    (McpData.VERSION.equals(definitions.getJSONObject(i).optString("protocolVersion"))?"will_revalidate":"needs_discovery"));break;
        }
        if(definitions.length()==0&&plugin.getJSONObject("manifest").has("mcpSource")){
            McpClient client=mcpClients.get(installationId(plugin));
            result.put("mcpStatus",client!=null?(client.usable()?"ready":"needs_discovery"):"will_revalidate").put("sourceOnly",true);
        }
        if(plugin.optBoolean("mcpCatalogueInvalidated"))result.put("mcpStatus","needs_discovery");boolean oauthReady=false;if(plugin.optJSONObject("mcpAuthorization")!=null){try{oauth().credential(plugin.getJSONObject("mcpAuthorization"));oauthReady=true;result.put("mcpAuthorizationState","connected");}catch(Exception unavailable){result.put("mcpAuthorizationState","reauthorization_required").put("mcpStatus","needs_authorization");}}
        return result.put("stateRevision",PluginRevisions.stateRevision(plugin)).put("grantsDigest",PluginRules.grantsDigest(plugin.optJSONArray("grants"))).put("hasToken",oauthReady||!ref.isEmpty()&&context.secrets.contains(ref)).put("credentialNeedsReentry",ref.isEmpty()&&context.secrets.contains("plugin:"+plugin.getString("id")));
    }
    private void removeCredential(JSONObject plugin)throws Exception{String ref=plugin.optString("credentialRef");if(!ref.isEmpty())context.secrets.remove(ref);context.secrets.remove("plugin:"+plugin.optString("id"));}
    /** Only currently enabled, explicitly granted declarations are visible to the host's tool registry. */
    public synchronized JSONArray toolRegistry()throws Exception{
        JSONArray result=new JSONArray(),plugins=list();
        for(int i=0;i<plugins.length();i++){
            JSONObject plugin=plugins.getJSONObject(i);if(!plugin.optBoolean("enabled")||plugin.optBoolean("mcpCatalogueInvalidated"))continue;if(plugin.optJSONObject("mcpAuthorization")!=null)try{oauth().credential(plugin.getJSONObject("mcpAuthorization"));}catch(Exception inactive){continue;}
            JSONArray granted=plugin.optJSONArray("grants"),tools=plugin.getJSONObject("manifest").getJSONArray("tools");
            for(int j=0;j<tools.length();j++){
                JSONObject tool=tools.getJSONObject(j);String name=tool.getString("name");boolean allowed=false;
                if(granted!=null)for(int g=0;g<granted.length();g++)if(("tool:"+name).equals(granted.optString(g)))allowed=true;
                if(!allowed)continue;
                if("mcp".equals(tool.optString("kind"))&&"stdio".equals(tool.optString("transport")))localProgramSummary(plugin,tool);
                if("mcp".equals(tool.optString("kind"))&&!"stdio".equals(tool.optString("transport"))){
                    McpClient client=mcpClients.get(installationId(plugin));
                    if(client!=null&&!client.usable()&&!client.needsDiscovery())continue;
                    if(client==null&&McpData.LEGACY.equals(tool.optString("protocolVersion")))continue;
                }
                String id="plugin:"+plugin.getString("id")+":"+name;
                // Native-only dependency identity. No credential value is copied; provider
                // tool definitions still contain only the public description and schema.
                JSONObject binding=executionBinding(plugin,tool);
                result.put(new JSONObject().put("id",id).put("pluginId",plugin.getString("id")).put("tool",name).put("name",functionName(id)).put("description",tool.optString("description",name)).put("schema",JsonFiles.copy(tool.getJSONObject("schema"))).put("version",plugin.getJSONObject("manifest").getString("version")).put("installationId",installationId(plugin)).put("effect",tool.optString("effect",tool.getString("kind").equals("echo")?"read":"write")).put("effectSource","plugin_declaration_with_owner_grant").put("executionBinding",binding));
            }
        }
        return result;
    }
    private static JSONObject executionBinding(JSONObject plugin,JSONObject tool)throws Exception {
        JSONObject binding=new JSONObject().put("pluginId",plugin.getString("id")).put("installationId",installationId(plugin))
                .put("stateRevision",PluginRevisions.stateRevision(plugin)).put("version",plugin.getJSONObject("manifest").getString("version"))
                .put("grantsDigest",PluginRules.grantsDigest(plugin.optJSONArray("grants"))).put("definition",JsonFiles.copy(tool));
        if(plugin.optJSONObject("mcpAuthorization")!=null)binding.put("mcpAuthorization",JsonFiles.copy(plugin.getJSONObject("mcpAuthorization")));if(plugin.optJSONObject("localMcp")!=null)binding.put("localProgram",localProgramSummary(plugin,tool));return binding;
    }
    private static String installationId(JSONObject plugin){return PluginRevisions.installationId(plugin);}
    private static String functionName(String id)throws Exception{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder name=new StringBuilder("plugin_");for(int i=0;i<12;i++)name.append(String.format(java.util.Locale.ROOT,"%02x",hash[i]));return name.toString();}
    private void cancelPlugin(String id){for(Invocation invocation:active.values())if(invocation.pluginId.equals(id))invocation.control.cancel();}
    @Override public synchronized void close(){
        closed=true;if(oauthHost!=null)oauthHost.close();for(Invocation invocation:active.values())invocation.control.cancel();
        for(McpClient client:mcpClients.values())client.close();mcpClients.clear();
        for(McpPreview preview:mcpPreviews.values())try{releasePreview(preview,true);}catch(Exception ignored){}mcpPreviews.clear();
    }
    private static final class Invocation {final String pluginId;final RunControl control;Invocation(String pluginId,RunControl control){this.pluginId=pluginId;this.control=control;}}
}
