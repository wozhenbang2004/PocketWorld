package dev.pocketengine.features.proxy;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.runtime.models.InferenceAudiencePolicy;
import dev.pocketengine.runtime.models.NativeInferenceScope;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import org.json.JSONArray;
import org.json.JSONObject;

/** Test-only native owner caller of the actual AccountGateway port and Responses codec; no HTTP listener. */
public final class AppAccountResponsesFixture implements AutoCloseable {
    private final LoopbackRelay.ConfigSource selected;
    private final LoopbackRelay.AccountRoute route;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    public final RunControl control=new RunControl();
    public final GatewayCall call;
    public final BlockingQueue<JSONObject> events=new LinkedBlockingQueue<>();
    public final List<JSONObject> history=new CopyOnWriteArrayList<>();
    public NativeInferenceScope audience;
    public volatile JSONObject output;
    public AppAccountResponsesFixture(LoopbackRelay.ConfigSource selected,LoopbackRelay.AccountRoute route){this(selected,route,10000);}
    public AppAccountResponsesFixture(LoopbackRelay.ConfigSource selected,LoopbackRelay.AccountRoute route,long timeout){
        this.selected=selected;this.route=route;call=new GatewayCall(timeout);audience=NativeInferenceScope.ownerApp("owner","private","","",0,control);
    }
    private ModelConfig admitted()throws Exception {
        ModelConfig config=selected.get();InferenceAudiencePolicy.require(config,audience,control);call.check();
        if(route==null)throw new GatewayFailure("ACCOUNT_ROUTE_UNAVAILABLE");return config;
    }
    public JSONArray models()throws Exception {ModelConfig config=admitted();JSONArray result=route.models(config.accountModel,call);call.check();return result;}
    public JSONObject response(JSONObject body)throws Exception {
        ModelConfig config=admitted();LoopbackResponses.Input input=LoopbackResponses.input(body,config.model);
        LoopbackResponses codec=new LoopbackResponses(config.model,input.streaming,new LoopbackResponses.Output(){
            public void event(JSONObject event)throws Exception{call.check();control.check();JSONObject copy=new JSONObject(event.toString());history.add(copy);events.add(copy);}
            public void complete(JSONObject response)throws Exception{call.check();control.check();output=new JSONObject(response.toString());}
        });
        route.stream(config.accountModel,input.request,call,event->{call.check();control.check();codec.accept(event);});
        call.check();codec.requireCompleted();return output;
    }
    public Future<JSONObject> start(JSONObject body){return worker.submit(()->response(body));}
    public Future<JSONArray> startModels(){return worker.submit(this::models);}
    public static int status(GatewayFailure failure){return LoopbackResponses.status(failure);}
    public static JSONObject error(GatewayFailure failure)throws Exception{return LoopbackResponses.error(failure);}
    @Override public void close(){control.cancel();call.cancel();worker.shutdownNow();}
}
