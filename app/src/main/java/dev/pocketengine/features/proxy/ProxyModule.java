package dev.pocketengine.features.proxy;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.PersistableBundle;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.InferenceAudiencePolicy;
import dev.pocketengine.runtime.models.NativeInferenceScope;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ProxyModule implements EngineModule {
    private NativeHarnessGatewayBridge harnessGateway;
    private JSONObject harnessGatewayTarget;
    private String tokenRevision=java.util.UUID.randomUUID().toString();
    private final FeatureContext context;private final LoopbackRelay.AccountRoute accounts;private final LoopbackRelay.InferenceFactory inferenceFactory;private LoopbackRelay relay;private String sessionId="";private ModelConfig bound;private final java.util.ArrayDeque<JSONObject> recentInference=new java.util.ArrayDeque<>();
    public ProxyModule(FeatureContext context){this(context,null);}
    public ProxyModule(FeatureContext context,LoopbackRelay.AccountRoute accounts){this(context,accounts,null);}
    public ProxyModule(FeatureContext context,LoopbackRelay.AccountRoute accounts,LoopbackRelay.InferenceFactory inferenceFactory){this.context=context;this.accounts=accounts;this.inferenceFactory=inferenceFactory;}
    @Override public String id(){return "proxy";}
    @Override public synchronized JSONObject snapshot()throws Exception {
        ModelConfig selection=bound==null?context.settings.captureRelaySelection():bound;boolean account=selection.usesAccount();
        boolean running=relay!=null&&relay.running();String root=running?"http://127.0.0.1:"+relay.port():"";
        String blocked=sourceBlockedReason(selection);JSONArray paths=blocked.isEmpty()?NativeRelayPolicy.paths(selection):new JSONArray();
        return new JSONObject().put("sessionId",sessionId).put("running",running).put("endpoint",clientBase(root,selection.provider))
            .put("rootEndpoint",root).put("listenHost","127.0.0.1").put("port",running?relay.port():0).put("supportsLan",false)
            .put("hasToken",context.secrets.contains("proxy.bearer")).put("tokenRevision",tokenRevision).put("requiresToken",true).put("streaming",true).put("protocol",NativeRelayPolicy.protocol(selection))
            .put("modelRoute",account?"account":"api_key").put("model",selection.routePlan==null?selection.model:selection.routePlan.requestedAlias).put("actualPrimaryModel",selection.model).put("modelGroup",selection.routePlan!=null&&selection.routePlan.groupRevision>0).put("accountRouteAvailable",false)
            .put("connectionId",selection.capabilityBinding==null?JSONObject.NULL:selection.capabilityBinding.connectionId())
            .put("externalRelayAllowed",blocked.isEmpty()).put("blockedReason",blocked)
            .put("endpoints",endpoints(paths)).put("clientConfigurations",clientConfigurations(selection,root,paths))
            .put("modelsEndpoint",root.isEmpty()?"":root+"/v1/models")
            .put("authentication",new JSONObject().put("exactlyOneHeader",true).put("headers",new JSONArray().put("Authorization: Bearer").put("x-api-key").put("x-goog-api-key")))
            .put("harnessGateway",harnessGateway==null?JSONObject.NULL:harnessGateway.snapshot()).put("phoneExecution",false).put("scope","loopback_only").put("requests",relay==null?0:relay.requests())
            .put("routeCurrent",relay==null?JSONObject.NULL:relay.routeCurrent()).put("routeChangeRequiresRestart",true)
            .put("capabilityDirectoryBound",selection.capabilityBinding!=null).put("recentInferenceReferences",recentInference()).put("usageListScope","current_service_process_recent_8_not_total");
    }
    @Override public synchronized JSONObject handle(String command,JSONObject input)throws Exception {
        switch(command){
            case "start":{
                context.requireExecutionEnabled();
                if(relay!=null&&relay.running())throw new IllegalStateException("反代已运行，请先停止再修改配置");
                ModelConfig selection=context.settings.captureRelaySelection();
                InferenceAudiencePolicy.require(selection,NativeInferenceScope.externalRelay(),null);
                if(!sourceBlockedReason(selection).isEmpty())throw new IllegalStateException("请先选择支持本机反代的 API-key 模型或已授权模型组");
                if(selection.usesAccount()){if(accounts==null)throw new IllegalStateException("账号反代尚未接入本机账号宿主");}
                else if(!ModelConfig.PROVIDERS.contains(selection.provider))throw new IllegalStateException("请选择明确的 API-key 原生模型协议");
                String token=input.optString("token");if(token.isEmpty())token=context.secrets.get("proxy.bearer");
                if(token.isEmpty())throw new IllegalArgumentException("请先生成或填写本地访问令牌");
                LoopbackRelay next=new LoopbackRelay(input.optInt("port",0),token,new LoopbackRelay.ConfigSource(){
                    @Override public ModelConfig get()throws Exception{
                        context.requireExecutionEnabled();
                        ModelConfig current=context.settings.captureRelaySelection();
                        if(current.usesAccount()!=selection.usesAccount()||(selection.usesAccount()&&!selection.accountModel.sameModel(current.accountModel)))throw new GatewayFailure("RELAY_ROUTE_CHANGED");
                        return current;
                    }
                    @Override public long selectionRevision()throws Exception{return context.settings.relaySelectionRevision();}
                },accounts,selection,inferenceFactory==null?null:this::beginInference);
                try{context.secrets.put("proxy.bearer",token);tokenRevision=java.util.UUID.randomUUID().toString();}catch(Exception error){next.close();throw error;}
                relay=next;bound=selection;sessionId=next.sessionId();return snapshot();
            }
            case "stop":stopRelay();return snapshot();
            case "status":return snapshot();
            default:throw new IllegalArgumentException("不支持的反代操作");
        }
    }
    /** Restrictive owner admission for the Web surface; the selected native model remains
     * the only route source. No credential is returned to the presentation layer. */
    public synchronized JSONObject handleReviewedOwner(String command,JSONObject input,long expectedSelectionRevision,
            String expectedSessionId,java.util.function.BooleanSupplier window)throws Exception {
        if(window==null||!window.getAsBoolean())throw new SecurityException("原本机服务窗口已关闭，请重新核对");
        if(command.equals("stop")){
            if(expectedSessionId==null||expectedSessionId.isEmpty()||!expectedSessionId.equals(sessionId))
                throw new IllegalStateException("本机服务已经变化，未停止后继服务");
            stopRelay();return ownerSnapshot();
        }
        if(command.equals("generateToken")||command.equals("copyToken")){
            requireTokenRevision(input);
            if(command.equals("generateToken")){
                if(relay!=null&&relay.running())throw new IllegalStateException("请先停止服务，再更换本机访问令牌");
                byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);
                String next=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
                if(!window.getAsBoolean())throw new SecurityException("原本机服务窗口已关闭，请重新核对");
                context.secrets.put("proxy.bearer",next);tokenRevision=java.util.UUID.randomUUID().toString();
                return ownerSnapshot();
            }
            if(relay!=null&&relay.running()&&!sessionId.equals(expectedSessionId))throw new IllegalStateException("本机服务已经变化，请刷新后复制令牌");
            String token=context.secrets.get("proxy.bearer");
            if(token.isEmpty())throw new IllegalStateException("尚未生成本机访问令牌");
            ClipboardManager clipboard=(ClipboardManager)context.android.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard==null)throw new IllegalStateException("本机剪贴板暂不可用");
            ClipData clip=ClipData.newPlainText("PocketWorld 本机访问令牌",token);
            PersistableBundle extras=new PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);
            if(!window.getAsBoolean())throw new SecurityException("原本机服务窗口已关闭，请重新核对");
            clipboard.setPrimaryClip(clip);
            return ownerSnapshot().put("tokenCopied",true);
        }
        if(!command.equals("snapshot")&&!command.equals("start"))throw new IllegalArgumentException("不支持的本机服务操作");
        synchronized(context.settings){
            if(!window.getAsBoolean())throw new SecurityException("原本机服务窗口已关闭，请重新核对");
            if(command.equals("start")){
                if(expectedSelectionRevision<0||context.settings.relaySelectionRevision()!=expectedSelectionRevision)
                    throw new IllegalStateException("所选模型或连接已变化，请重新核对启动配置");
                if(input.has("tokenRevision"))requireTokenRevision(input);
                handle("start",input);
            }
            return ownerSnapshot();
        }
    }
    private JSONObject ownerSnapshot()throws Exception {
        synchronized(context.settings){return snapshot().put("selectionRevision",context.settings.relaySelectionRevision());}
    }
    private void requireTokenRevision(JSONObject input){
        if(!tokenRevision.equals(input.optString("tokenRevision")))throw new IllegalStateException("本机访问令牌已变化，请刷新后重试");
    }
    /** Existing native owner/run entry only, not a model command. The actual task grant stays authoritative. */
    public synchronized NativeHarnessGatewayBridge prepareHarnessGateway(dev.pocketengine.store.TaskStore tasks,dev.pocketengine.core.RunControl original,
            dev.pocketengine.features.remote.RemoteModule remote,JSONObject target,long expiresAt)throws Exception {
        return prepareHarnessGateway(tasks,original,remote,target,expiresAt,null);
    }
    public synchronized NativeHarnessGatewayBridge prepareHarnessGateway(dev.pocketengine.store.TaskStore tasks,dev.pocketengine.core.RunControl original,dev.pocketengine.features.remote.RemoteModule remote,JSONObject target,long expiresAt,dev.pocketengine.store.TaskStore.HarnessGatewayConfigurationOwner configurationOwner)throws Exception {
        return prepareHarnessGateway(tasks,original,remote,target,expiresAt,configurationOwner,null);
    }
    public synchronized NativeHarnessGatewayBridge prepareHarnessGateway(dev.pocketengine.store.TaskStore tasks,dev.pocketengine.core.RunControl original,dev.pocketengine.features.remote.RemoteModule remote,JSONObject target,long expiresAt,dev.pocketengine.store.TaskStore.HarnessGatewayConfigurationOwner configurationOwner,NativeHarnessGatewayBridge.StartupFence startup)throws Exception {
        return prepareHarnessGateway(tasks,original,remote,target,expiresAt,configurationOwner,startup,null);
    }
    /** Only a verified original Profile run can replace the existing SettingsStore RELAY source. */
    public synchronized NativeHarnessGatewayBridge prepareHarnessGateway(dev.pocketengine.store.TaskStore tasks,dev.pocketengine.core.RunControl original,dev.pocketengine.features.remote.RemoteModule remote,JSONObject target,long expiresAt,dev.pocketengine.store.TaskStore.HarnessGatewayConfigurationOwner configurationOwner,NativeHarnessGatewayBridge.StartupFence startup,dev.pocketengine.features.controlplane.NativeWorkProfileHost.RelaySource profileRelay)throws Exception {
        return prepareHarnessGateway(tasks,original,remote,target,expiresAt,configurationOwner,startup,profileRelay,null);
    }
    public synchronized NativeHarnessGatewayBridge prepareHarnessGateway(dev.pocketengine.store.TaskStore tasks,dev.pocketengine.core.RunControl original,dev.pocketengine.features.remote.RemoteModule remote,JSONObject target,long expiresAt,dev.pocketengine.store.TaskStore.HarnessGatewayConfigurationOwner configurationOwner,NativeHarnessGatewayBridge.StartupFence startup,dev.pocketengine.features.controlplane.NativeWorkProfileHost.RelaySource profileRelay,dev.pocketengine.features.controlplane.WorkProfileToolSelection profileAssets)throws Exception {
        if(harnessGateway!=null&&harnessGateway.running())throw new IllegalStateException("请先停止原远端模型网关");
        if(target.length()!=8)throw new SecurityException("准确原电脑目标必需，不接收群来源或替代授权字段");
        for(String key:new String[]{"profileId","_profileBinding","serviceBase","environmentId","environmentBinding","bindingId","profileDigest","taskId"})if(!target.has(key))throw new SecurityException("准确原电脑目标不完整");
        var identity=original.hostRunIdentity();if(identity==null||!identity.taskId().equals(target.getString("taskId")))throw new SecurityException("不是当前原任务native控制器");
        if(profileRelay!=null){profileRelay.requireOriginal(tasks,original);profileRelay.requireTarget(configurationOwner,target);}
        LoopbackRelay.ConfigSource selected=profileRelay==null?new LoopbackRelay.ConfigSource(){public ModelConfig get()throws Exception{if(profileAssets!=null)profileAssets.checkCurrent();return context.settings.captureRelaySelection();}public long selectionRevision()throws Exception{if(profileAssets!=null)profileAssets.checkCurrent();return context.settings.relaySelectionRevision();}}:profileRelay;
        harnessGateway=NativeHarnessGatewayBridge.forTask(tasks,original,selected,remote.harnessGatewayTransport(target),target.getString("environmentId"),target.getString("environmentBinding"),target.getString("bindingId"),target.getString("profileDigest"),expiresAt,configurationOwner,startup);
        harnessGatewayTarget=new JSONObject(target.toString());return harnessGateway;
    }
    public synchronized NativeHarnessGatewayBridge originalHarnessGateway(String lease){return harnessGateway!=null&&harnessGateway.leaseId().equals(lease)?harnessGateway:null;}
    /** Native original owner observation, never a model handle command. */
    public synchronized NativeHarnessGatewayBridge existingHarnessGateway(String task,String run)throws Exception {if(harnessGateway==null)return null;JSONObject value=harnessGateway.snapshot();return task.equals(value.optString("taskId"))&&run.equals(value.optString("runId"))?harnessGateway:null;}
    public synchronized JSONObject harnessGatewayTarget(String lease)throws Exception {if(originalHarnessGateway(lease)==null||harnessGatewayTarget==null)throw new SecurityException("原准确租约不可用");return new JSONObject(harnessGatewayTarget.toString());}
    private dev.pocketengine.runtime.usage.NativeInferenceWork beginInference(ModelConfig captured,dev.pocketengine.core.RunControl original,String session)throws Exception{
        var work=inferenceFactory.open(captured,original,session);synchronized(this){recentInference.addLast(work.reference());while(recentInference.size()>8)recentInference.removeFirst();}return work;
    }
    private JSONArray recentInference()throws Exception{JSONArray result=new JSONArray();for(JSONObject ref:recentInference)result.put(new JSONObject(ref.toString()));return result;}
    private static String sourceBlockedReason(ModelConfig selected)throws Exception{
        if(selected.usesAccount())return "CHATGPT_PLAN_APP_ONLY";
        if(selected.model.isEmpty()||selected.baseUrl.isEmpty())return "MODEL_NOT_CONFIGURED";
        if(selected.routePlan!=null&&!"EXTERNAL_RELAY".equals(selected.routePlan.audience))return "RELAY_AUDIENCE_NOT_ALLOWED";
        try{return NativeRelayPolicy.paths(selected).length()==0?"PROTOCOL_NOT_SUPPORTED":"";}
        catch(NativeRelayPolicy.Rejected invalid){return "MODEL_PATH_NOT_SUPPORTED";}
    }
    private static JSONArray endpoints(JSONArray paths)throws Exception{JSONArray result=new JSONArray();if(paths.length()==0)return result;result.put("/v1/models");for(int i=0;i<paths.length();i++)result.put(paths.get(i));return result;}
    private static String clientBase(String root,String protocol){return root.isEmpty()?"":root+(protocol.startsWith("openai_")?"/v1":"");}
    private static JSONArray clientConfigurations(ModelConfig selected,String root,JSONArray paths)throws Exception{
        java.util.LinkedHashMap<String,JSONArray> protocols=new java.util.LinkedHashMap<>();
        for(int i=0;i<paths.length();i++){
            String path=paths.getString(i),protocol=dev.pocketengine.runtime.providers.ProtocolCodec.ingress(path);
            protocols.computeIfAbsent(protocol,key->new JSONArray()).put(path);
        }
        JSONArray result=new JSONArray();
        for(var entry:protocols.entrySet()){
            String protocol=entry.getKey();JSONArray urls=new JSONArray();for(int i=0;i<entry.getValue().length();i++)if(!root.isEmpty())urls.put(root+entry.getValue().getString(i));
            String label=switch(protocol){case "openai_chat"->"OpenAI Chat Completions";case "openai_responses"->"OpenAI Responses";case "anthropic"->"Anthropic Messages";case "gemini"->"Gemini";default->protocol;};
            result.put(new JSONObject().put("protocol",protocol).put("label",label).put("baseUrl",clientBase(root,protocol)).put("paths",entry.getValue()).put("requestUrls",urls)
                .put("authHeader",protocol.equals("anthropic")?"x-api-key":protocol.equals("gemini")?"x-goog-api-key":"Authorization")
                .put("authScheme",protocol.startsWith("openai_")?"Bearer":"").put("native",protocol.equals(selected.provider))
                .put("model",protocol.equals("gemini")||selected.routePlan==null?selected.model:selected.routePlan.requestedAlias));
        }
        return result;
    }
    private void stopRelay(){if(relay!=null){relay.close();relay=null;}bound=null;}
    @Override public synchronized void close(){if(harnessGateway!=null){harnessGateway.close();harnessGateway=null;}stopRelay();}
}
