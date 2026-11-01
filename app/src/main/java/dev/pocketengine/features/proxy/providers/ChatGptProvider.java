package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.features.proxy.accounts.Credentials;
import dev.pocketengine.features.proxy.accounts.ChatGptRecovery;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.json.JSONArray;
import org.json.JSONObject;

/** Official Sign in with ChatGPT plan-usage protocol, not the Codex CLI client or backend-api. */
public final class ChatGptProvider implements AutoCloseable {
    public static final String ID="chatgpt_plan";
    public static final String RESOURCE="https://api.openai.com/v1";
    public static final String SCOPES="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private final String issuer,api;
    private final OkHttpClient http;
    public ChatGptProvider(){this("https://auth.openai.com","https://api.openai.com/v1",false);}
    // Package-private: a test may use a local fake server, never a user-supplied gateway or arbitrary HTTPS host.
    ChatGptProvider(String issuer,String api,boolean fixture){
        if(fixture){if(!isFixture(issuer)||!isFixture(api))throw new IllegalArgumentException("FIXTURE_MUST_BE_LOOPBACK");}
        else if(!issuer.equals("https://auth.openai.com")||!api.equals(RESOURCE))throw new IllegalArgumentException("INVALID_PROVIDER_ORIGIN");
        this.issuer=issuer;this.api=api;
        http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS).cache(null).build();
    }
    private static boolean isFixture(String value){HttpUrl url=HttpUrl.parse(value);return url!=null&&url.scheme().equals("http")&&url.host().equals("127.0.0.1")&&url.username().isEmpty()&&url.password().isEmpty();}
    public String authorizationUrl(String hostId,String clientId,String callback,String state,String nonce,String challenge)throws GatewayFailure {
        HttpUrl uri=HttpUrl.parse(callback);
        if(uri==null||!"http".equals(uri.scheme())||!"127.0.0.1".equals(uri.host())||!"/auth/callback".equals(uri.encodedPath())||uri.query()!=null||uri.fragment()!=null||!uri.username().isEmpty()||!uri.password().isEmpty())throw new GatewayFailure("INVALID_CALLBACK");
        HttpUrl.Builder url=HttpUrl.get(issuer+"/api/accounts/authorize").newBuilder().addQueryParameter("client_id",clientId.isEmpty()?"dynamic_agent_client":clientId)
            .addQueryParameter("ext_agent_host_id",hostId).addQueryParameter("response_type","code").addQueryParameter("redirect_uri",callback)
            .addQueryParameter("scope",SCOPES).addQueryParameter("resource",RESOURCE).addQueryParameter("state",state).addQueryParameter("nonce",nonce)
            .addQueryParameter("code_challenge_method","S256").addQueryParameter("code_challenge",challenge);
        if(clientId.isEmpty())url.addQueryParameter("agent_name_hint","Pocket World");
        // Do not expose retained ID tokens through an authorization URL returned to the UI bridge.
        return url.build().toString();
    }
    public static final class Grant {
        public final Credentials credentials;
        public final String issuer,subject,email;
        Grant(Credentials credentials,String issuer,String subject,String email){this.credentials=credentials;this.issuer=issuer;this.subject=subject;this.email=email;}
        @Override public String toString(){return "Grant[redacted]";}
    }
    public Grant exchange(String clientId,String code,String verifier,String callback,String nonce,GatewayCall operation,long now)throws GatewayFailure {
        JSONObject response=postToken(new FormBody.Builder().add("grant_type","authorization_code").add("client_id",clientId).add("code",code)
            .add("code_verifier",verifier).add("redirect_uri",callback).add("resource",RESOURCE).build(),operation,"exchange");
        Credentials tokens=tokens(response,null,now);
        OidcIdentityVerifier.Identity identity=identity(tokens,clientId,nonce,operation,now);
        return new Grant(tokens,identity.issuer,identity.subject,identity.email);
    }
    public Grant refresh(String clientId,Credentials old,String expectedSubject,GatewayCall operation,long now)throws GatewayFailure {
        if(old.refreshToken().isEmpty())throw new GatewayFailure("REAUTHORIZATION_REQUIRED");
        JSONObject response=postToken(new FormBody.Builder().add("grant_type","refresh_token").add("client_id",clientId).add("refresh_token",old.refreshToken()).add("resource",RESOURCE).build(),operation,"refresh");
        Credentials tokens=tokens(response,old,now);
        String email="",subject=expectedSubject;
        if(response.has("id_token")){
            OidcIdentityVerifier.Identity identity=identity(tokens,clientId,null,operation,now);
            if(!identity.subject.equals(expectedSubject))throw new GatewayFailure("ACCOUNT_IDENTITY_MISMATCH");email=identity.email;subject=identity.subject;
        }
        return new Grant(tokens,issuer,subject,email);
    }
    private OidcIdentityVerifier.Identity identity(Credentials tokens,String clientId,String nonce,GatewayCall operation,long now)throws GatewayFailure {
        JSONObject discovery=discovery(operation);String jwks=discovery.optString("jwks_uri");sameIssuerEndpoint(jwks);
        JSONObject keys=getJson(jwks,"",operation,64*1024);
        return OidcIdentityVerifier.verify(tokens.idToken(),keys,issuer,clientId,nonce,tokens.accessToken(),now);
    }
    private JSONObject discovery(GatewayCall operation)throws GatewayFailure {
        JSONObject discovery=getJson(issuer+"/.well-known/openid-configuration","",operation,32*1024);
        if(!issuer.equals(discovery.optString("issuer"))||!(issuer+"/api/accounts/oauth/token").equals(discovery.optString("token_endpoint")))throw new GatewayFailure("INVALID_PROVIDER_DISCOVERY");return discovery;
    }
    private void sameIssuerEndpoint(String value)throws GatewayFailure {
        HttpUrl endpoint=HttpUrl.parse(value),base=HttpUrl.get(issuer);
        if(endpoint==null||!endpoint.scheme().equals(base.scheme())||!endpoint.host().equals(base.host())||endpoint.port()!=base.port()||!endpoint.username().isEmpty()||!endpoint.password().isEmpty()||endpoint.query()!=null||endpoint.fragment()!=null)throw new GatewayFailure("INVALID_PROVIDER_DISCOVERY");
    }
    public void revoke(String clientId,Credentials tokens,GatewayCall operation)throws GatewayFailure {
        if(tokens.refreshToken().isEmpty())throw new GatewayFailure("REMOTE_REVOCATION_UNCONFIRMED");
        String endpoint=discovery(operation).optString("revocation_endpoint");sameIssuerEndpoint(endpoint);
        Request request=new Request.Builder().url(endpoint).post(oneShot(new FormBody.Builder().add("token",tokens.refreshToken()).add("token_type_hint","refresh_token").add("client_id",clientId).build())).build();
        try(Exchange exchange=execute(request,operation)){Response response=exchange.response;if(response.code()!=200)throw new GatewayFailure("REMOTE_REVOCATION_UNCONFIRMED");}
        catch(GatewayFailure error){throw error;}catch(Exception error){throw new GatewayFailure("REMOTE_REVOCATION_UNCONFIRMED");}
    }
    public JSONArray models(Credentials credentials,GatewayCall operation)throws GatewayFailure {
        requirePlan(credentials);JSONObject response=getJson(api+"/models",credentials.accessToken(),operation,256*1024);
        try{
            JSONArray models=response.getJSONArray("models"),result=new JSONArray();if(models.length()>512)throw new Exception();
            for(int i=0;i<models.length();i++){JSONObject model=models.getJSONObject(i);if(!"list".equals(model.optString("visibility")))continue;
                String slug=model.getString("slug"),name=model.getString("display_name");if(!slug.matches("[A-Za-z0-9_.:-]{1,160}")||name.length()>160)throw new Exception();
                result.put(new JSONObject().put("id",slug).put("displayName",name).put("imageInput",AccountImageCapability.parse(model))
                    .put("catalogFacts",dev.pocketengine.runtime.models.AccountCatalogFacts.parse(model,slug,System.currentTimeMillis()).toJson()));}
            return result;
        }catch(Exception error){throw new GatewayFailure("INVALID_MODEL_CATALOG");}
    }
    public interface EventSink {
        void event(JSONObject safeEvent)throws Exception;
        /** Numeric-only accounting side channel. It never publishes text, tools or task authority. */
        default void usage(JSONObject counters)throws Exception{}
    }
    public void stream(Credentials credentials,String model,String instructions,JSONArray input,GatewayCall operation,EventSink sink)throws GatewayFailure {
        requirePlan(credentials);
        byte[] bytes;
        try{
            if(model==null||!model.matches("[A-Za-z0-9_.:-]{1,160}")||instructions==null||instructions.length()>32_768||input==null||input.length()==0||input.length()>128)throw new Exception();
            // This first slice is text-only. Do not silently discard tools/media or unknown input fields.
            for(int i=0;i<input.length();i++){JSONObject item=input.getJSONObject(i);if(item.length()!=2||!item.has("role")||!item.has("content")||!(item.get("content") instanceof String)||!java.util.Arrays.asList("user","assistant","developer").contains(item.getString("role")))throw new Exception();}
            bytes=new JSONObject().put("model",model).put("instructions",instructions).put("input",input).put("store",false).put("stream",true).toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>256*1024)throw new Exception();
        }catch(Exception error){throw new GatewayFailure("UNSUPPORTED_OR_OVERSIZED_INPUT");}
        streamRequest(credentials,bytes,null,operation,sink);
    }
    /** Structured text/refusal/function data; does not execute functions or authorize any host action. */
    public void streamResponses(Credentials credentials,ResponsesRequest request,GatewayCall operation,EventSink sink)throws GatewayFailure {
        requirePlan(credentials);if(request==null)throw new GatewayFailure("UNSUPPORTED_OR_OVERSIZED_INPUT");
        streamRequest(credentials,request.body(),request,operation,sink);
    }
    private void streamRequest(Credentials credentials,byte[] bytes,ResponsesRequest context,GatewayCall operation,EventSink sink)throws GatewayFailure {
        Request request=new Request.Builder().url(api+"/responses").header("Authorization","Bearer "+credentials.accessToken()).header("Accept","text/event-stream").header("Accept-Encoding","identity")
            .post(oneShot(RequestBody.create(bytes,MediaType.get("application/json; charset=utf-8")))).build();
        try(Exchange exchange=execute(request,operation)){
            Response response=exchange.response;checkStatus(response,operation);if(response.body()==null||!response.header("Content-Type","").split(";",2)[0].trim().equalsIgnoreCase("text/event-stream"))throw new GatewayFailure("INVALID_PROVIDER_STREAM");
            if(context==null)ResponsesTextStream.read(response.body().byteStream(),operation,sink,credentials.accessToken(),credentials.refreshToken(),credentials.idToken());
            else ResponsesStream.read(response.body().byteStream(),context,operation,sink,credentials.accessToken(),credentials.refreshToken(),credentials.idToken());
        }catch(GatewayFailure error){throw error;}catch(Exception error){operation.check();throw new GatewayFailure("PROVIDER_STREAM_INTERRUPTED");}
    }
    private static void requirePlan(Credentials credentials)throws GatewayFailure {if(!credentials.permitsInference())throw new GatewayFailure("PLAN_USAGE_PERMISSION_REQUIRED");}
    private JSONObject postToken(FormBody body,GatewayCall operation,String context)throws GatewayFailure {
        return jsonRequest(new Request.Builder().url(issuer+"/api/accounts/oauth/token").post(oneShot(body)).header("Accept","application/json").build(),operation,64*1024,context);
    }
    private JSONObject getJson(String url,String access,GatewayCall operation,int limit)throws GatewayFailure {
        Request.Builder request=new Request.Builder().url(url).header("Accept","application/json").header("Accept-Encoding","identity");if(!access.isEmpty())request.header("Authorization","Bearer "+access);
        return jsonRequest(request.build(),operation,limit);
    }
    private JSONObject jsonRequest(Request request,GatewayCall operation,int limit)throws GatewayFailure {return jsonRequest(request,operation,limit,"");}
    private JSONObject jsonRequest(Request request,GatewayCall operation,int limit,String context)throws GatewayFailure {
        try(Exchange exchange=execute(request,operation)){
            Response response=exchange.response;checkStatus(response,operation,context);if(response.body()==null)throw new GatewayFailure("INVALID_PROVIDER_RESPONSE");
            byte[] bytes=readLimited(response.body().byteStream(),limit,operation);
            try{return new JSONObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString());}
            catch(Exception invalid){throw new GatewayFailure("INVALID_PROVIDER_RESPONSE");}
        }catch(GatewayFailure error){throw error;}catch(Exception error){operation.check();throw new GatewayFailure("PROVIDER_NETWORK_ERROR");}
    }
    // Operation owns one network Call at a time. Its binding remains through body consumption, not just headers.
    private final java.util.Map<GatewayCall,Call> bound=new java.util.concurrent.ConcurrentHashMap<>();
    private Exchange execute(Request request,GatewayCall operation)throws Exception {
        operation.check();Call call=http.newCall(request);operation.attach(call);bound.put(operation,call);
        try{return new Exchange(operation,call,call.execute());}
        catch(Exception error){bound.remove(operation,call);operation.detach(call);throw error;}
    }
    private final class Exchange implements AutoCloseable {
        final GatewayCall operation;final Call call;final Response response;
        Exchange(GatewayCall operation,Call call,Response response){this.operation=operation;this.call=call;this.response=response;}
        public void close(){try{response.close();}finally{bound.remove(operation,call);operation.detach(call);}}
    }
    private static RequestBody oneShot(RequestBody original){return new RequestBody(){
        @Override public MediaType contentType(){return original.contentType();}
        @Override public long contentLength()throws java.io.IOException{return original.contentLength();}
        @Override public boolean isOneShot(){return true;}
        @Override public void writeTo(BufferedSink sink)throws java.io.IOException{original.writeTo(sink);}
    };}
    private static void checkStatus(Response response,GatewayCall operation)throws GatewayFailure {checkStatus(response,operation,"");}
    private static void checkStatus(Response response,GatewayCall operation,String context)throws GatewayFailure {
        int status=response.code();if(status>=200&&status<300)return;
        JSONObject body=null;String shape="empty";
        try{
            if(response.body()!=null){byte[] bytes=readLimited(response.body().byteStream(),16*1024,operation);
                if(bytes.length>0){shape="non_json";body=new JSONObject(new String(bytes,StandardCharsets.UTF_8));shape=body.has("error")?"json_error":body.has("detail")?"json_detail":"json_other";}}
        }catch(Exception ignored){operation.check();}
        long seconds=60;try{seconds=Long.parseLong(response.header("Retry-After","60"));}catch(Exception ignored){}
        throw ChatGptRecovery.failure(status,body,shape,response.header("x-request-id",""),context,
            System.currentTimeMillis()+Math.max(1,Math.min(seconds,86400))*1000);
    }
    static byte[] readLimited(InputStream input,int limit,GatewayCall operation)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[4096];int count;
        while((count=input.read(block))!=-1){operation.check();if(bytes.size()+count>limit)throw new GatewayFailure("PROVIDER_RESPONSE_TOO_LARGE");bytes.write(block,0,count);}operation.check();return bytes.toByteArray();
    }
    private static Credentials tokens(JSONObject response,Credentials old,long now)throws GatewayFailure {
        try{
            if(!"Bearer".equalsIgnoreCase(response.getString("token_type")))throw new Exception();long seconds=response.getLong("expires_in");if(seconds<1||seconds>86400)throw new Exception();
            String refresh=response.getString("refresh_token");if(refresh.isEmpty())throw new Exception();
            String id=response.optString("id_token",old==null?"":old.idToken());if(old==null&&id.isEmpty())throw new Exception();
            String scopes=response.optString("scope",old==null?"":old.scopes);if(scopes.isEmpty())throw new Exception();
            return new Credentials(response.getString("access_token"),refresh,id,scopes,Math.addExact(now,seconds*1000));
        }catch(Exception error){throw new GatewayFailure("INVALID_TOKEN_RESPONSE");}
    }
    @Override public void close(){for(Call call:bound.values())call.cancel();http.connectionPool().evictAll();http.dispatcher().executorService().shutdown();}
}
