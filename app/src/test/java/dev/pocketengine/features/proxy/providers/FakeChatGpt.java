package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.testutil.HttpServer.Exchange;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Entirely local fixture. Tokens are synthetic; SSE uses fixed Content-Length with per-byte writes, not HTTP chunked framing. */
public final class FakeChatGpt implements AutoCloseable {
    private static final KeyPair KEY=key();
    final HttpServer server;
    public final String base;
    public final AtomicInteger exchanges=new AtomicInteger(),refreshes=new AtomicInteger(),responses=new AtomicInteger(),models=new AtomicInteger(),revocations=new AtomicInteger();
    public volatile String nonce="",subject="fixture-subject",clientId="oaiapp_fixture",scope="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    public volatile long expiresIn=3600;
    public volatile int tokenStatus=200,responseStatus=200,revokeStatus=200;
    public volatile String responseContentType="text/event-stream";
    public volatile java.util.function.IntUnaryOperator responseStatusForCall;
    public volatile String tokenErrorBody="{\"error\":\"fixture-access-secret\"}",responseErrorBody="{\"secret\":\"fixture-access-secret\"}",modelsBody="{\"models\":[{\"slug\":\"fixture-model\",\"display_name\":\"Fixture model\",\"visibility\":\"list\"},{\"slug\":\"hidden\",\"display_name\":\"Hidden\",\"visibility\":\"hide\"}]}";
    public volatile String stream="data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好🙂\",\"sequence_number\":0}\n\ndata: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":2,\"output_tokens\":3,\"total_tokens\":5}}}\n\n";
    public volatile Map<String,String> lastTokenForm=Map.of();
    public volatile JSONObject lastRequest;
    public volatile String lastAuthorization="";
    public volatile CountDownLatch modelsEntered=new CountDownLatch(1),allowModels=new CountDownLatch(0),refreshEntered=new CountDownLatch(1),allowRefresh=new CountDownLatch(0),exchangeEntered=new CountDownLatch(1),allowExchange=new CountDownLatch(0),responseEntered=new CountDownLatch(1),allowResponse=new CountDownLatch(0);
    public FakeChatGpt()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",this::serve);server.start();
    }
    public ChatGptProvider provider(){return new ChatGptProvider(base,base+"/v1",true);}
    void serve(Exchange call){
        try{
            String path=call.getRequestURI().getPath();byte[] bytes=call.getRequestBody().readAllBytes();
            if(path.equals("/.well-known/openid-configuration")){json(call,200,new JSONObject().put("issuer",base).put("token_endpoint",base+"/api/accounts/oauth/token").put("jwks_uri",base+"/jwks").put("revocation_endpoint",base+"/revoke").toString());}
            else if(path.equals("/jwks"))json(call,200,jwks().toString());
            else if(path.equals("/api/accounts/oauth/token")){
                Map<String,String> form=parse(new String(bytes,StandardCharsets.UTF_8));lastTokenForm=form;boolean refresh="refresh_token".equals(form.get("grant_type"));
                int generation;
                if(refresh){generation=refreshes.incrementAndGet();refreshEntered.countDown();allowRefresh.await(10,TimeUnit.SECONDS);}
                else{generation=exchanges.incrementAndGet()-1;exchangeEntered.countDown();allowExchange.await(10,TimeUnit.SECONDS);}
                if(tokenStatus!=200){call.getResponseHeaders().add("Retry-After","0");json(call,tokenStatus,tokenErrorBody);}
                else json(call,200,new JSONObject().put("access_token",access(generation)).put("refresh_token",refresh(generation)).put("id_token",jwt(base,subject,clientId,nonce,System.currentTimeMillis()/1000+3600))
                    .put("token_type","Bearer").put("scope",scope).put("expires_in",expiresIn).toString());
            }else if(path.equals("/revoke")){revocations.incrementAndGet();call.sendResponseHeaders(revokeStatus,-1);}
            else if(path.equals("/v1/models")){models.incrementAndGet();modelsEntered.countDown();allowModels.await(10,TimeUnit.SECONDS);lastAuthorization=call.getRequestHeaders().getFirst("Authorization");json(call,200,modelsBody);}
            else if(path.equals("/v1/responses")){
                int requestNumber=responses.incrementAndGet();lastAuthorization=call.getRequestHeaders().getFirst("Authorization");lastRequest=new JSONObject(new String(bytes,StandardCharsets.UTF_8));responseEntered.countDown();allowResponse.await(10,TimeUnit.SECONDS);
                int status=responseStatusForCall==null?responseStatus:responseStatusForCall.applyAsInt(requestNumber);
                if(status!=200){call.getResponseHeaders().add("Retry-After","0");json(call,status,responseErrorBody);}
                else {if(responseContentType!=null)call.getResponseHeaders().add("Content-Type",responseContentType);byte[] streamBytes=stream.getBytes(StandardCharsets.UTF_8);call.sendResponseHeaders(200,streamBytes.length);for(byte b:streamBytes){call.getResponseBody().write(b);call.getResponseBody().flush();}}
            }else json(call,404,"{}");
        }catch(Exception ignored){}finally{call.close();}
    }
    public static String access(int generation){return "fixture-access-secret-"+generation;}
    public static String refresh(int generation){return "fixture-refresh-secret-"+generation;}
    static void json(Exchange call,int status,String value)throws Exception{byte[] body=value.getBytes(StandardCharsets.UTF_8);call.getResponseHeaders().add("Content-Type","application/json");call.sendResponseHeaders(status,body.length);call.getResponseBody().write(body);}
    public static Map<String,String> parse(String text)throws Exception {Map<String,String> result=new LinkedHashMap<>();for(String pair:text.split("&")){String[] parts=pair.split("=",2);result.put(URLDecoder.decode(parts[0],"UTF-8"),parts.length==2?URLDecoder.decode(parts[1],"UTF-8"):"");}return result;}
    public static JSONObject jwks()throws Exception {RSAPublicKey key=(RSAPublicKey)KEY.getPublic();return new JSONObject().put("keys",new JSONArray().put(new JSONObject().put("kid","fixture-key").put("alg","RS256").put("kty","RSA").put("use","sig").put("n",unsigned(key.getModulus().toByteArray())).put("e",unsigned(key.getPublicExponent().toByteArray()))));}
    public static String jwt(String issuer,String subject,String audience,String nonce,long expiry)throws Exception {
        JSONObject claims=new JSONObject().put("iss",issuer).put("sub",subject).put("aud",audience).put("nonce",nonce).put("email","fixture@example.test").put("iat",System.currentTimeMillis()/1000-1).put("exp",expiry);
        return signed(claims,new JSONObject().put("alg","RS256").put("kid","fixture-key"));
    }
    static String signed(JSONObject claims,JSONObject header)throws Exception {String payload=encode(header.toString().getBytes(StandardCharsets.UTF_8))+"."+encode(claims.toString().getBytes(StandardCharsets.UTF_8));Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(KEY.getPrivate());signature.update(payload.getBytes(StandardCharsets.US_ASCII));return payload+"."+encode(signature.sign());}
    private static String unsigned(byte[] bytes){return encode(bytes[0]==0?java.util.Arrays.copyOfRange(bytes,1,bytes.length):bytes);}
    static String encode(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private static KeyPair key(){try{KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}catch(Exception error){throw new AssertionError(error);}}
    @Override public void close(){allowModels.countDown();allowRefresh.countDown();allowExchange.countDown();allowResponse.countDown();server.stop(0);}
}
