package dev.pocketengine.features.proxy;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.models.InferenceAudiencePolicy;
import dev.pocketengine.runtime.models.NativeInferenceScope;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ProxyModule implements EngineModule {
    private final FeatureContext context;private final LoopbackRelay.AccountRoute accounts;private final LoopbackRelay.InferenceFactory inferenceFactory;private LoopbackRelay relay;private String sessionId="";private ModelConfig bound;private final java.util.ArrayDeque<JSONObject> recentInference=new java.util.ArrayDeque<>();
    public ProxyModule(FeatureContext context){this(context,null);}
    public ProxyModule(FeatureContext context,LoopbackRelay.AccountRoute accounts){this(context,accounts,null);}
    public ProxyModule(FeatureContext context,LoopbackRelay.AccountRoute accounts,LoopbackRelay.InferenceFactory inferenceFactory){this.context=context;this.accounts=accounts;this.inferenceFactory=inferenceFactory;}
    @Override public String id(){return "proxy";}
    @Override public synchronized JSONObject snapshot()throws Exception {
        ModelConfig selection=bound==null?context.settings.captureRelaySelection():bound;boolean account=selection.usesAccount();
        return new JSONObject().put("sessionId",sessionId).put("running",relay!=null&&relay.running()).put("endpoint",relay!=null&&relay.running()?"http://127.0.0.1:"+relay.port()+"/v1":"")
            .put("hasToken",context.secrets.contains("proxy.bearer")).put("requiresToken",true).put("streaming",true).put("protocol",NativeRelayPolicy.protocol(selection))
            .put("modelRoute",account?"account":"api_key").put("model",selection.routePlan==null?selection.model:selection.routePlan.requestedAlias).put("actualPrimaryModel",selection.model).put("modelGroup",selection.routePlan!=null&&selection.routePlan.groupRevision>0).put("accountRouteAvailable",false)
            .put("externalRelayAllowed",!account).put("blockedReason",account?"CHATGPT_PLAN_APP_ONLY":"")
            .put("endpoints",endpoints(selection))
            .put("phoneExecution",false).put("scope","loopback_only").put("requests",relay==null?0:relay.requests())
            .put("routeCurrent",relay==null?JSONObject.NULL:relay.routeCurrent()).put("routeChangeRequiresRestart",true)
            .put("capabilityDirectoryBound",selection.capabilityBinding!=null).put("recentInferenceReferences",recentInference()).put("usageListScope","current_service_process_recent_8_not_total");
    }
    @Override public synchronized JSONObject handle(String command,JSONObject input)throws Exception {
        switch(command){
            case "start":{
                if(relay!=null&&relay.running())throw new IllegalStateException("反代已运行，请先停止再修改配置");
                ModelConfig selection=context.settings.captureRelaySelection();
                InferenceAudiencePolicy.require(selection,NativeInferenceScope.externalRelay(),null);
                if(selection.model.isEmpty()||selection.baseUrl.isEmpty())throw new IllegalStateException("请先在模型页配置并明确选择自己的 API-key 上游模型");
                if(selection.usesAccount()){if(accounts==null)throw new IllegalStateException("账号反代尚未接入本机账号宿主");}
                else if(!ModelConfig.PROVIDERS.contains(selection.provider))throw new IllegalStateException("请选择明确的 API-key 原生模型协议");
                String token=input.optString("token");if(token.isEmpty())throw new IllegalArgumentException("请先生成或填写本地访问令牌");
                LoopbackRelay next=new LoopbackRelay(input.optInt("port",0),token,new LoopbackRelay.ConfigSource(){
                    @Override public ModelConfig get()throws Exception{
                        ModelConfig current=context.settings.captureRelaySelection();
                        if(current.usesAccount()!=selection.usesAccount()||(selection.usesAccount()&&!selection.accountModel.sameModel(current.accountModel)))throw new GatewayFailure("RELAY_ROUTE_CHANGED");
                        return current;
                    }
                    @Override public long selectionRevision()throws Exception{return context.settings.relaySelectionRevision();}
                },accounts,selection,inferenceFactory==null?null:this::beginInference);
                try{context.secrets.put("proxy.bearer",token);}catch(Exception error){next.close();throw error;}
                relay=next;bound=selection;sessionId=next.sessionId();return snapshot();
            }
            case "stop":close();return snapshot();
            case "status":return snapshot();
            default:throw new IllegalArgumentException("不支持的反代操作");
        }
    }
    private dev.pocketengine.runtime.usage.NativeInferenceWork beginInference(ModelConfig captured,dev.pocketengine.core.RunControl original,String session)throws Exception{
        var work=inferenceFactory.open(captured,original,session);synchronized(this){recentInference.addLast(work.reference());while(recentInference.size()>8)recentInference.removeFirst();}return work;
    }
    private JSONArray recentInference()throws Exception{JSONArray result=new JSONArray();for(JSONObject ref:recentInference)result.put(new JSONObject(ref.toString()));return result;}
    private static JSONArray endpoints(ModelConfig selected)throws Exception{JSONArray result=new JSONArray();if(selected.usesAccount())return result;result.put("/v1/models");JSONArray paths=NativeRelayPolicy.paths(selected);for(int i=0;i<paths.length();i++)result.put(paths.get(i));return result;}
    @Override public synchronized void close(){if(relay!=null){relay.close();relay=null;}bound=null;}
}
