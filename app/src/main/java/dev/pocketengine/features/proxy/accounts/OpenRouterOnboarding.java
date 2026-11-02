package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.store.SettingsStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.json.JSONObject;

/** Owner-started PKCE exchanges one code for a normal API connection in the original secret vault. */
public final class OpenRouterOnboarding implements AutoCloseable {
    public static final String API_BASE="https://openrouter.ai/api/v1",KEYS_PAGE="https://openrouter.ai/settings/keys";
    interface KeyExchange {String exchange(String code,String verifier)throws Exception;}
    private final SettingsStore settings;private final OkHttpClient http;private final KeyExchange fixtureExchange;
    private LoopbackAuthorization pending;private JSONObject state=new JSONObject();
    public OpenRouterOnboarding(SettingsStore settings){this(settings,null);}
    OpenRouterOnboarding(SettingsStore settings,KeyExchange fixtureExchange){this.settings=java.util.Objects.requireNonNull(settings);this.fixtureExchange=fixtureExchange;http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(20,TimeUnit.SECONDS).build();}
    public synchronized JSONObject snapshot()throws Exception{return new JSONObject(state.toString());}
    public JSONObject begin(String model)throws Exception {
        ModelTargetRefCheck.model(model);long selection=settings.apiSelectionRevision();
        synchronized(this){close();LoopbackAuthorization attempt=new LoopbackAuthorization("openrouter",1,300000,new LoopbackAuthorization.Handler(){
            public void complete(LoopbackAuthorization session,java.util.Map<String,String> callback)throws GatewayFailure {
                try{String code=callback.get("code");if(code==null||code.isEmpty()||code.length()>4096||callback.containsKey("error"))throw new GatewayFailure("AUTHORIZATION_DENIED");
                    String key=exchange(session,code);synchronized(OpenRouterOnboarding.this){if(pending!=session||!"pending".equals(state.optString("status")))throw new GatewayFailure("CANCELLED");
                        synchronized(settings){if(settings.apiSelectionRevision()!=selection)throw new GatewayFailure("CONNECTION_SELECTION_CHANGED");
                            JSONObject saved=settings.saveApiConnection("",0,"OpenRouter",new JSONObject().put("provider","openai_chat").put("baseUrl",API_BASE).put("model",model).put("apiKey",key).put("maxOutputTokens",4096));
                            state=new JSONObject().put("status","completed").put("connectionId",saved.getString("id")).put("selected",false).put("apiKeyIncluded",false);session.status="complete";pending=null;}
                    }
                }catch(SettingsStore.PersistenceUncertain uncertain){synchronized(OpenRouterOnboarding.this){if(pending==session)try{state=new JSONObject().put("status","storage_unconfirmed").put("apiKeyIncluded",false);pending=null;}catch(Exception impossible){throw new IllegalStateException(impossible);}}throw new GatewayFailure("KEY_STORAGE_UNCONFIRMED");}catch(GatewayFailure failure){rejected(session,failure.code);throw failure;}catch(Exception failure){rejected(session,"KEY_NOT_SAVED");throw new GatewayFailure("KEY_NOT_SAVED");}
            }
            public void expired(LoopbackAuthorization session){fail(session,"AUTHORIZATION_EXPIRED");}
        });pending=attempt;String url="https://openrouter.ai/auth?callback_url="+enc(attempt.callback)+"&code_challenge="+enc(attempt.challenge())+"&code_challenge_method=S256&state="+enc(attempt.state)+"&key_label=PocketWorld";
            state=new JSONObject().put("status","pending").put("attemptId",attempt.id).put("expiresAt",attempt.expiresAt).put("apiKeyIncluded",false);attempt.start();return snapshot().put("authorizationUrl",url);
        }
    }
    private String exchange(LoopbackAuthorization session,String code)throws Exception {
        if(fixtureExchange!=null)return fixtureExchange.exchange(code,session.verifier);
        JSONObject body=new JSONObject().put("code",code).put("code_verifier",session.verifier).put("code_challenge_method","S256");
        Request request=new Request.Builder().url(API_BASE+"/auth/keys").header("Accept","application/json").post(RequestBody.create(body.toString(),MediaType.get("application/json"))).build();
        try(GatewayCall operation=new GatewayCall(20000)){session.operation=operation;var call=http.newCall(request);operation.attach(call);
            try(var response=call.execute()){operation.check();if(response.code()!=200||response.body()==null)throw new GatewayFailure("KEY_EXCHANGE_HTTP_"+response.code());var source=response.body().source();if(source.request(65537))throw new GatewayFailure("KEY_RESPONSE_TOO_LARGE");JSONObject result=new JSONObject(source.readUtf8());String key=result.optString("key");if(key.length()<8||key.length()>8192||key.chars().anyMatch(Character::isISOControl))throw new GatewayFailure("KEY_RESPONSE_INVALID");return key;}finally{operation.detach(call);session.operation=null;}
        }
    }
    /** The callback listener writes its rejection before closing its own active peer. */
    private synchronized void rejected(LoopbackAuthorization session,String code){if(pending==session)try{state=new JSONObject().put("status","failed").put("code",code).put("apiKeyIncluded",false);session.status="failed";pending=null;}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private synchronized void fail(LoopbackAuthorization session,String code){if(pending==session){rejected(session,code);session.close();}}
    public synchronized void cancel(){boolean wasPending=pending!=null;close();if(wasPending)try{state=new JSONObject().put("status","cancelled").put("apiKeyIncluded",false);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    @Override public synchronized void close(){if(pending!=null){pending.close();pending=null;}}
    private static String enc(String value){try{return URLEncoder.encode(value,"UTF-8");}catch(java.io.UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}}
    private static final class ModelTargetRefCheck {static void model(String value){if(value==null||value.isEmpty()||value.length()>200||!value.matches("[A-Za-z0-9_.:/@+-]+"))throw new IllegalArgumentException("请填写OpenRouter中的精确模型ID");}}
}
