package dev.pocketengine.media;

import android.app.Application;
import android.util.Base64;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Synthetic HTTP-interceptor contracts only: no real issuer, signature, credential, room or TLS acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class AuthenticatedLiveKitProviderTest {
    private AuthenticatedLiveKitProvider provider;private final AtomicInteger requests=new AtomicInteger();private final AtomicReference<Request> sent=new AtomicReference<>();
    private interface Reply {String body(JSONObject request)throws Exception;}
    private RealtimeEndpointProfile profile()throws Exception{return new RealtimeEndpointProfile("https://issuer.example/token","wss://room.example","deployed-agent",1);}
    private AuthenticatedLiveKitProvider fixture(int code,Reply reply)throws Exception{
        OkHttpClient http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).addInterceptor(chain->{
            requests.incrementAndGet();sent.set(chain.request());okio.Buffer body=new okio.Buffer();chain.request().body().writeTo(body);
            try{return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("synthetic fixture").header("Location","https://wrong.example/redirect").body(ResponseBody.create(reply.body(new JSONObject(body.readUtf8())),okhttp3.MediaType.get("application/json"))).build();}
            catch(Exception failure){throw new java.io.IOException("fixture",failure);}
        }).build();
        provider=new AuthenticatedLiveKitProvider(profile(),()->"synthetic-issuer-auth",http);return provider;
    }
    private static String part(String value){return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    private JSONObject response(JSONObject request)throws Exception{return new JSONObject().put("server_url","wss://room.example").put("participant_token",token(request,300,"",true));}
    private String token(JSONObject request,long ttl,String badRoom,boolean publish)throws Exception{
        JSONObject claims=new JSONObject().put("sub",request.getString("participant_identity")).put("exp",System.currentTimeMillis()/1000+ttl)
            .put("video",new JSONObject().put("roomJoin",true).put("room",badRoom.isEmpty()?request.getString("room_name"):badRoom).put("canPublish",publish));
        return part("{\"alg\":\"HS256\"}")+"."+part(claims.toString())+".synthetic-not-a-signature";
    }
    private String id(){return UUID.randomUUID().toString();}
    private String failure(CompletableFuture<?> result)throws Exception{try{result.get(3,TimeUnit.SECONDS);fail("must fail");return "";}catch(java.util.concurrent.ExecutionException expected){assertTrue(expected.getCause() instanceof RealtimeCallProvider.ProviderFailure);return ((RealtimeCallProvider.ProviderFailure)expected.getCause()).code;}}
    @After public void close(){if(provider!=null)provider.close();}
    @Test public void constructionAndLocalRejectionNeverRequestTokens()throws Exception{
        fixture(201,r->response(r).toString());assertFalse(provider.supportsIncomingInvitations());provider.reject(id()).get();assertEquals(0,requests.get());
    }
    @Test public void explicitAcceptUsesStandardSchemaDedicatedAuthAndExactOpaqueRoomIdentity()throws Exception{
        fixture(201,r->response(r).toString());String call=id();RealtimeCallProvider.JoinCredentials joined=provider.accept(call,false,false).get(3,TimeUnit.SECONDS);
        assertEquals("wss://room.example",joined.serverUrl);assertEquals(1,requests.get());assertEquals("Bearer synthetic-issuer-auth",sent.get().header("Authorization"));assertEquals(call,sent.get().header("X-PocketWorld-Call-ID"));
        okio.Buffer raw=new okio.Buffer();sent.get().body().writeTo(raw);JSONObject body=new JSONObject(raw.readUtf8());assertEquals(3,body.length());assertEquals("pw-"+call,body.getString("room_name"));assertEquals("owner-"+call,body.getString("participant_identity"));assertEquals("deployed-agent",body.getJSONObject("room_config").getJSONArray("agents").getJSONObject(0).getString("agent_name"));assertFalse(body.toString().contains("synthetic-issuer-auth"));assertFalse(joined.toString().contains(joined.participantToken));
    }
    @Test public void actualProductionClientDisablesRedirectRetryAndBoundsCallTime()throws Exception{
        provider=new AuthenticatedLiveKitProvider(profile(),()->"not-read");Field field=AuthenticatedLiveKitProvider.class.getDeclaredField("client");field.setAccessible(true);OkHttpClient client=(OkHttpClient)field.get(provider);
        assertFalse(client.followRedirects());assertFalse(client.followSslRedirects());assertFalse(client.retryOnConnectionFailure());assertEquals(15000,client.callTimeoutMillis());assertEquals(0,requests.get());
    }
    @Test public void non201AndRedirectCannotMasqueradeAsValidJoin()throws Exception{
        fixture(302,r->response(r).toString());assertEquals("redirect_refused",failure(provider.accept(id(),false,false)));assertEquals(1,requests.get());
    }
    @Test public void authFailureDoesNotEchoServerBody()throws Exception{
        fixture(401,r->"PRIVATE synthetic-issuer-auth response body");CompletableFuture<?> result=provider.accept(id(),false,false);assertEquals("authentication_rejected",failure(result));try{result.get();}catch(Exception e){assertFalse(e.toString().contains("PRIVATE"));assertFalse(e.toString().contains("synthetic-issuer-auth"));}
    }
    @Test public void oversizedResponseIsRejectedBeforeParsing()throws Exception{
        fixture(201,r->"x".repeat(65*1024));assertEquals("response_too_large",failure(provider.accept(id(),false,false)));
    }
    @Test public void returnedRoomOriginMustMatchOwnerReviewedOrigin()throws Exception{
        fixture(201,r->response(r).put("server_url","wss://unexpected.example").toString());assertEquals("room_origin_changed",failure(provider.accept(id(),false,false)));
    }
    @Test public void tokenMustHaveExactCallBindingAndBoundedLifetime()throws Exception{
        fixture(201,r->response(r).put("participant_token",token(r,300,"wrong-room",true)).toString());assertEquals("token_binding_mismatch",failure(provider.accept(id(),false,false)));provider.close();fixture(201,r->response(r).put("participant_token",token(r,3600,"",true)).toString());assertEquals("token_lifetime_invalid",failure(provider.accept(id(),false,false)));
    }
    @Test public void refusedCaptureGrantFailsBeforeAnyMediaSession()throws Exception{
        fixture(201,r->response(r).put("participant_token",token(r,300,"",false)).toString());assertEquals("capture_not_granted",failure(provider.accept(id(),true,false)));
    }
    @Test public void cancelAndCloseFenceLateSuccessfulIssuerResponse()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);fixture(201,r->{entered.countDown();release.await(2,TimeUnit.SECONDS);return response(r).toString();});String call=id();CompletableFuture<?> accepted=provider.accept(call,false,false);assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals("admission_busy",failure(provider.accept(id(),false,false)));provider.cancelAccept(call);assertEquals("cancelled",failure(accepted));release.countDown();provider.close();assertEquals("admission_busy",failure(provider.accept(id(),false,false)));assertEquals(1,requests.get());
    }
    @Test public void profileNeverAcceptsCredentialUrlsCleartextOrQueryTokens()throws Exception{
        for(String endpoint:new String[]{"http://issuer.example/token","https://user:pass@issuer.example/token","https://issuer.example/token?key=x","https://issuer.example/token#x"})assertThrows(Exception.class,()->new RealtimeEndpointProfile(endpoint,"wss://room.example","agent",1));
        assertThrows(Exception.class,()->new RealtimeEndpointProfile("https://issuer.example/token","ws://room.example","agent",1));assertEquals(RealtimeEndpointProfile.origin("https://issuer.example/token"),RealtimeEndpointProfile.origin("https://issuer.example:443/other"));
    }
}
