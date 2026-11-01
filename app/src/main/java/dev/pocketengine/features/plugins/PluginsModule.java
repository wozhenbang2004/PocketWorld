package dev.pocketengine.features.plugins;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.workspace.JsonFiles;
import dev.pocketengine.store.TaskStore;
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
        return new JSONObject().put("plugins",all).put("active",pending).put("execution","declarative_echo_https_or_mcp").put("mcpSupport",nativeMcpSupport()).put("arbitraryCode",false).put("commands",new JSONArray().put("discoverMcp").put("installMcp").put("discardMcpPreview").put("install").put("previewInstall").put("history").put("previewRollback").put("rollback").put("enable").put("grant").put("remove").put("invoke").put("validateArguments").put("cancel").put("registry"));
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
            plugin.put("enabled",enabled);PluginRevisions.advanceState(plugin);JsonFiles.write(file,new JSONObject().put("plugins",all));cancelPlugin(id);return publicPlugin(plugin);
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
    private JSONObject install(JSONArray all,JSONObject input,String restoredFrom)throws Exception {return install(all,input,restoredFrom,null);}
    private JSONObject install(JSONArray all,JSONObject input,String restoredFrom,dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost.Credential authorization)throws Exception {
        JSONObject manifest=PluginRules.manifest(input.getJSONObject("manifest"));String id=manifest.getString("id");JSONArray next=new JSONArray();JSONObject old=null;
        for(int i=0;i<all.length();i++){JSONObject previous=all.getJSONObject(i);if(id.equals(previous.getString("id")))old=previous;else next.put(previous);}
        PluginRevisions.checkExpected(old,input,false);
        if(next.length()>=30)throw new IllegalStateException("最多安装30个声明插件");
        String token=input.optString("token");if(token.length()>4096||token.contains("\r")||token.contains("\n"))throw new IllegalArgumentException("工具凭据格式无效");
        // Each installation owns a distinct credential slot. A failed manifest write cannot replace the old endpoint's token.
        JSONObject plugin=new JSONObject().put("id",id).put("manifest",manifest).put("installationId",UUID.randomUUID().toString()).put("stateRevision",0).put("enabled",false).put("grants",new JSONArray()).put("installedAt",System.currentTimeMillis());
        if(authorization!=null){JSONObject nativeIdentity=authorization.identity();JSONArray nativeTools=manifest.getJSONArray("tools");for(int n=0;n<nativeTools.length();n++){JSONObject tool=nativeTools.getJSONObject(n);if(!tool.optString("kind").equals("mcp")||!nativeIdentity.getString("resource").equals(tool.getString("endpoint"))||!nativeIdentity.getString("protocolVersion").equals(tool.getString("protocolVersion")))throw new SecurityException("原OAuth连接不属于此次工具安装的准确地址/协议");authorization.check(tool.getString("endpoint"));}plugin.put("mcpAuthorization",nativeIdentity);}
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
        final String id=input.getString("id"),name=input.getString("tool"),operationId=input.optString("_operationId",UUID.randomUUID().toString());
        final Object suppliedArguments=arguments(input);
        final JSONObject args;
        final JSONObject tool;final String token;final Invocation invocation;final McpClient mcp;
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
            if(active.size()>=2)throw new IllegalStateException("已有两个工具请求运行，请等待或停止后重试");
            String ref=plugin.optString("credentialRef");dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost.Credential authorization=plugin.optJSONObject("mcpAuthorization")==null?null:oauth().credential(plugin.getJSONObject("mcpAuthorization"));token=authorization!=null||ref.isEmpty()?"":context.secrets.get(ref);
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
        try{
            JSONObject result;
            if(mcp!=null){
                if(mcp.needsDiscovery())try{mcp.discover(invocation.control);}catch(Exception unavailable){throw new McpClient.CallFailure(false,true,"MCP重新发现失败，工具未发送，请检查配置后重新导入");}
                result=mcp.call(tool,args,invocation.control);
            }else result=HttpToolClient.invoke(tool.getString("endpoint"),token,name,args,operationId,invocation.control);
            synchronized(this){invocation.control.check();return new JSONObject().put("operationId",operationId).put("status","response_received").put("verified",false).put("toolResult",result);}
        }catch(McpClient.CallFailure failure){
            return new JSONObject().put("operationId",operationId).put("status",failure.dispatched?"unknown":"failed").put("dispatchStatus",failure.dispatched?"may_have_dispatched":"not_dispatched").put("dispatched",failure.dispatched)
                    .put("cancelRequested",invocation.control.isCancelled()).put("refreshRequired",failure.refreshRequired)
                    .put("verified",false).put("message",failure.getMessage());
        }catch(Exception uncertain){
            return new JSONObject().put("operationId",operationId).put("status","unknown").put("cancelRequested",invocation.control.isCancelled()).put("verified",false).put("message",invocation.control.isCancelled()?"工具请求已停止或授权已变化，可能已被处理；请核对服务端结果，勿重复提交":"HTTP工具请求未取得有效回执，可能已被处理；请检查服务端结果，勿直接重复提交");
        }finally{synchronized(this){active.remove(operationId);}}
    }
    private JSONObject discoverMcp(JSONObject input)throws Exception {return discoverMcp(input,null);}
    private JSONObject discoverMcp(JSONObject input,dev.pocketengine.features.plugins.mcp.NativeMcpOAuthHost.Credential authorization)throws Exception {
        String id=input.getString("id").trim(),name=input.getString("name").trim(),endpoint=input.getString("endpoint").trim();
        String version=input.optString("protocolVersion",McpData.VERSION),token=input.optString("token");
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
            JSONArray all=list();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))expected=installationId(all.getJSONObject(i));
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
                        .put("protocolVersion",version).put("serverInfo",client.serverInfo()).put("tools",rows).put("supportedSchemas",McpData.support())
                        .put("expiresAt",expiresAt).put("expectedInstallationId",expected).put("enabledAfterInstall",false).put("grantsAfterInstall",new JSONArray())
                        .put("hasToken",authorization!=null||!token.isEmpty()).put("untrusted",true).put("installed",false)
                        .put("message","服务描述仅供参考，不是授权。请选择支持的工具导入；导入后仍需启用和逐项授权。");
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
        JSONObject manifest=McpData.manifest(preview.id,preview.name,preview.client.endpoint(),preview.client.version(),preview.client.serverDigest(),preview.definitions,input.getJSONArray("tools"));
        JSONObject request=new JSONObject().put("manifest",manifest).put("expectedInstallationId",preview.expected);
        if(preview.authorization!=null)preview.authorization.check(preview.client.endpoint());else if(context.secrets.contains(preview.credentialRef))request.put("token",context.secrets.get(preview.credentialRef));
        JSONObject installed=install(all,request,null,preview.authorization);
        mcpClients.put(installed.getString("installationId"),preview.client);mcpPreviews.remove(previewId);releasePreview(preview,false);
        return installed.put("mcpStatus","ready");
    }
    private static void rejectDirectMcp(JSONObject manifest)throws Exception {
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
        JSONArray history=result.optJSONArray("history");result.remove("history");result.put("historyCount",history==null?0:Math.min(history.length(),PluginRevisions.HISTORY_LIMIT));
        JSONArray definitions=plugin.getJSONObject("manifest").getJSONArray("tools");
        for(int i=0;i<definitions.length();i++)if("mcp".equals(definitions.getJSONObject(i).optString("kind"))){
            McpClient client=mcpClients.get(installationId(plugin));
            result.put("mcpStatus",client!=null?(client.usable()?"ready":"needs_discovery"):
                    (McpData.VERSION.equals(definitions.getJSONObject(i).optString("protocolVersion"))?"will_revalidate":"needs_discovery"));break;
        }
        boolean oauthReady=false;if(plugin.optJSONObject("mcpAuthorization")!=null){try{oauth().credential(plugin.getJSONObject("mcpAuthorization"));oauthReady=true;result.put("mcpAuthorizationState","connected");}catch(Exception unavailable){result.put("mcpAuthorizationState","reauthorization_required").put("mcpStatus","needs_authorization");}}
        return result.put("stateRevision",PluginRevisions.stateRevision(plugin)).put("grantsDigest",PluginRules.grantsDigest(plugin.optJSONArray("grants"))).put("hasToken",oauthReady||!ref.isEmpty()&&context.secrets.contains(ref)).put("credentialNeedsReentry",ref.isEmpty()&&context.secrets.contains("plugin:"+plugin.getString("id")));
    }
    private void removeCredential(JSONObject plugin)throws Exception{String ref=plugin.optString("credentialRef");if(!ref.isEmpty())context.secrets.remove(ref);context.secrets.remove("plugin:"+plugin.optString("id"));}
    /** Only currently enabled, explicitly granted declarations are visible to the host's tool registry. */
    public synchronized JSONArray toolRegistry()throws Exception{
        JSONArray result=new JSONArray(),plugins=list();
        for(int i=0;i<plugins.length();i++){
            JSONObject plugin=plugins.getJSONObject(i);if(!plugin.optBoolean("enabled"))continue;if(plugin.optJSONObject("mcpAuthorization")!=null)try{oauth().credential(plugin.getJSONObject("mcpAuthorization"));}catch(Exception inactive){continue;}
            JSONArray granted=plugin.optJSONArray("grants"),tools=plugin.getJSONObject("manifest").getJSONArray("tools");
            for(int j=0;j<tools.length();j++){
                JSONObject tool=tools.getJSONObject(j);String name=tool.getString("name");boolean allowed=false;
                if(granted!=null)for(int g=0;g<granted.length();g++)if(("tool:"+name).equals(granted.optString(g)))allowed=true;
                if(!allowed)continue;
                if("mcp".equals(tool.optString("kind"))){
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
        if(plugin.optJSONObject("mcpAuthorization")!=null)binding.put("mcpAuthorization",JsonFiles.copy(plugin.getJSONObject("mcpAuthorization")));return binding;
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
