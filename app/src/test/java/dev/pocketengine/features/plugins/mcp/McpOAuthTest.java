package dev.pocketengine.features.plugins.mcp;

import dev.pocketengine.core.RunControl;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=android.app.Application.class)
public class McpOAuthTest {
    private static final String RESOURCE="https://mcp.example.com/public/mcp",ISSUER="https://auth.example.com/tenant",REDIRECT="https://app.example.com/oauth/callback";
    private static final class Secrets implements McpOAuth.EncryptedSecrets {
        final Map<String,String> ciphertext=new HashMap<>();final SecretKey key;
        Secrets()throws Exception{KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(128);key=generator.generateKey();}
        @Override public boolean encryptedAtRest(){return true;}
        @Override public synchronized void create(String slot,String secret)throws Exception {
            if(ciphertext.containsKey(slot))throw new SecurityException("Duplicate slot");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(slot.getBytes(StandardCharsets.UTF_8));
            ciphertext.put(slot,Base64.getEncoder().encodeToString(cipher.getIV())+":"+Base64.getEncoder().encodeToString(cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8))));
        }
        @Override public synchronized String read(String slot)throws Exception {
            if(!ciphertext.containsKey(slot))return "";String[] parts=ciphertext.get(slot).split(":",2);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));cipher.updateAAD(slot.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);
        }
        @Override public synchronized String take(String slot)throws Exception{String value=read(slot);ciphertext.remove(slot);return value;}
        @Override public synchronized void remove(String slot){ciphertext.remove(slot);}
    }
    private static final class Fixture implements McpOAuth.Trust,McpOAuth.Http {
        final Map<String,JSONObject> metadata=new HashMap<>();final List<String> fetched=new ArrayList<>();final List<Map<String,String>> posts=new ArrayList<>();
        final AtomicLong now=new AtomicLong(1000);final Secrets secrets=new Secrets();final McpOAuth.Binding binding=new McpOAuth.Binding("install",RESOURCE,"a".repeat(64),"b".repeat(64));
        final McpOAuth.Client client=new McpOAuth.Client(ISSUER,"registered-client",REDIRECT,McpOAuth.Registration.PRE_REGISTERED);
        JSONObject token=new JSONObject().put("token_type","Bearer").put("access_token","fixture-access-secret").put("refresh_token","unused-refresh-secret").put("expires_in",3600).put("scope","files:read");
        boolean live=true,losePost;McpOAuth auth;
        Fixture()throws Exception {
            metadata.put("https://mcp.example.com/.well-known/oauth-protected-resource/public/mcp",resource());
            metadata.put("https://auth.example.com/.well-known/oauth-authorization-server/tenant",serverMetadata());auth=new McpOAuth(this,secrets,this,new SecureRandom(),now::get);
        }
        JSONObject resource()throws Exception{return new JSONObject().put("resource",RESOURCE).put("authorization_servers",new JSONArray().put(ISSUER)).put("scopes_supported",new JSONArray().put("files:read"));}
        JSONObject serverMetadata()throws Exception{return new JSONObject().put("issuer",ISSUER).put("authorization_endpoint","https://auth.example.com/authorize").put("token_endpoint","https://tokens.example.com/token")
                .put("response_types_supported",new JSONArray().put("code")).put("code_challenge_methods_supported",new JSONArray().put("S256"))
                .put("token_endpoint_auth_methods_supported",new JSONArray().put("none")).put("authorization_response_iss_parameter_supported",true);}
        @Override public void check(McpOAuth.Binding b)throws Exception{if(!live||!binding.fingerprint().equals(b.fingerprint()))throw new SecurityException("Installation replaced");}
        @Override public void endpoint(McpOAuth.Binding b,String endpoint,McpOAuth.Use use){if(!java.util.Arrays.asList("mcp.example.com","auth.example.com","tokens.example.com").contains(URI.create(endpoint).getHost()))throw new SecurityException("Unreviewed endpoint");}
        @Override public void authorization(McpOAuth.Binding b,String issuer,String id,String redirect,String scope,String digest){if(!ISSUER.equals(issuer)||!id.equals(client.clientId)||!REDIRECT.equals(redirect)||!scope.equals("files:read"))throw new SecurityException("Unreviewed authorization");}
        @Override public McpOAuth.Reply request(String endpoint,Map<String,String> form,RunControl control)throws Exception {
            if(form!=null){posts.add(new LinkedHashMap<>(form));if(losePost)throw new java.io.IOException("lost token result");return new McpOAuth.Reply(200,token);}
            fetched.add(endpoint);return new McpOAuth.Reply(metadata.containsKey(endpoint)?200:404,metadata.getOrDefault(endpoint,new JSONObject()));
        }
        McpOAuth.AuthorizationRequest begin()throws Exception {
            McpOAuth.Discovery d=auth.discover(binding,List.of(),new RunControl());return auth.begin(binding,d,auth.server(binding,d,ISSUER,new RunControl()),client,new RunControl());
        }
        String callback(McpOAuth.AuthorizationRequest request,String issuer)throws Exception {
            Map<String,String> params=new LinkedHashMap<>();params.put("state",McpOAuth.query(URI.create(request.authorizationUrl).getRawQuery()).get("state"));params.put("code","fixture-code");if(issuer!=null)params.put("iss",issuer);return REDIRECT+"?"+McpOAuth.form(params);
        }
    }
    @Test public void pkceStateAndResourceAreBoundAcrossRestartAndOnlyOpaqueReferenceIsReturned()throws Exception {
        Fixture f=new Fixture();McpOAuth.AuthorizationRequest request=f.begin();Map<String,String> authorize=McpOAuth.query(URI.create(request.authorizationUrl).getRawQuery());
        assertEquals("S256",authorize.get("code_challenge_method"));assertEquals(RESOURCE,authorize.get("resource"));assertEquals("files:read",authorize.get("scope"));
        assertFalse(request.authorizationUrl.contains("code_verifier"));assertEquals(43,authorize.get("state").length());
        McpOAuth restarted=new McpOAuth(f,f.secrets,f,new SecureRandom(),f.now::get);assertEquals(request.authorizationUrl,restarted.resume(f.binding,request.flowId,new RunControl()).authorizationUrl);
        McpOAuth.CredentialRef ref=restarted.complete(f.binding,request.flowId,f.callback(request,ISSUER),new RunControl());
        Map<String,String> post=f.posts.get(0);assertEquals(RESOURCE,post.get("resource"));assertEquals("authorization_code",post.get("grant_type"));assertEquals(f.client.clientId,post.get("client_id"));assertEquals(REDIRECT,post.get("redirect_uri"));
        assertEquals(authorize.get("code_challenge"),Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(post.get("code_verifier").getBytes(StandardCharsets.US_ASCII))));
        assertFalse(post.containsKey("client_secret"));assertEquals("fixture-access-secret",restarted.accessToken(f.binding,ref.reference,ISSUER,f.client.clientId,RESOURCE,new RunControl()));
        assertFalse(ref.reference.contains("fixture-access"));assertEquals(1,f.secrets.ciphertext.size());assertFalse(f.secrets.ciphertext.toString().contains("fixture-access-secret"));assertFalse(f.secrets.read(ref.reference).contains("unused-refresh-secret"));
        try{restarted.complete(f.binding,request.flowId,f.callback(request,ISSUER),new RunControl());fail();}catch(SecurityException expected){}assertEquals(1,f.posts.size());
    }
    @Test public void discoveryHonorsBearerMetadataAndOrderedOAuthAndOidcFallbacks()throws Exception {
        Fixture f=new Fixture();String challengeUrl="https://mcp.example.com/explicit-metadata";f.metadata.put(challengeUrl,f.resource());
        McpOAuth.Discovery d=f.auth.discover(f.binding,List.of("Basic realm=\"other, realm\", Bearer resource_metadata=\""+challengeUrl+"\", scope=\"files:read\""),new RunControl());
        assertEquals(List.of(challengeUrl),f.fetched);f.fetched.clear();f.metadata.remove("https://auth.example.com/.well-known/oauth-authorization-server/tenant");
        f.metadata.put("https://auth.example.com/tenant/.well-known/openid-configuration",f.serverMetadata());f.auth.server(f.binding,d,ISSUER,new RunControl());
        assertEquals(List.of("https://auth.example.com/.well-known/oauth-authorization-server/tenant","https://auth.example.com/.well-known/openid-configuration/tenant","https://auth.example.com/tenant/.well-known/openid-configuration"),f.fetched);
    }
    @Test public void missingPathResourceMetadataFallsBackToRootOnlyOnNotFound()throws Exception {
        Fixture f=new Fixture();f.metadata.clear();f.metadata.put("https://mcp.example.com/.well-known/oauth-protected-resource",f.resource());
        f.auth.discover(f.binding,List.of(),new RunControl());assertEquals(List.of("https://mcp.example.com/.well-known/oauth-protected-resource/public/mcp","https://mcp.example.com/.well-known/oauth-protected-resource"),f.fetched);
    }
    @Test public void stateMismatchExpiryDuplicateAndWrongRedirectNeverPostTokens()throws Exception {
        for(int mode=0;mode<4;mode++){
            Fixture f=new Fixture();McpOAuth.AuthorizationRequest request=f.begin();String callback=f.callback(request,ISSUER);
            if(mode==0)callback=callback.replace("state=","state=wrong");if(mode==1)f.now.set(request.expiresAt);if(mode==2)callback+="&code=duplicate";if(mode==3)callback=callback.replace("/oauth/callback?","/other?");
            try{f.auth.complete(f.binding,request.flowId,callback,new RunControl());fail();}catch(SecurityException expected){}assertEquals(0,f.posts.size());assertEquals(0,f.secrets.ciphertext.size());
        }
    }
    @Test public void responseIssuerIsComparedExactlyBeforeTokenExchangeIncludingErrorResponses()throws Exception {
        for(String issuer:new String[]{null,"https://AUTH.example.com/tenant","https://auth.example.com/tenant/","https://evil.example.com"}){
            Fixture f=new Fixture();McpOAuth.AuthorizationRequest request=f.begin();String callback=f.callback(request,issuer)+"&error=remote-error&error_description=do-not-display";
            try{f.auth.complete(f.binding,request.flowId,callback,new RunControl());fail();}catch(SecurityException expected){assertFalse(expected.getMessage().contains("do-not-display"));}assertEquals(0,f.posts.size());
        }
        Fixture f=new Fixture();JSONObject server=f.serverMetadata().put("authorization_response_iss_parameter_supported",false);f.metadata.put("https://auth.example.com/.well-known/oauth-authorization-server/tenant",server);
        McpOAuth.AuthorizationRequest request=f.begin();try{f.auth.complete(f.binding,request.flowId,f.callback(request,"https://evil.example.com"),new RunControl());fail();}catch(SecurityException expected){}assertEquals(0,f.posts.size());
    }
    @Test public void resourceAndIssuerMetadataCannotSilentlyChangeAndPkceCannotBeDowngraded()throws Exception {
        Fixture wrongResource=new Fixture();wrongResource.metadata.put("https://mcp.example.com/.well-known/oauth-protected-resource/public/mcp",wrongResource.resource().put("resource","https://other.example.com/mcp"));
        try{wrongResource.begin();fail();}catch(SecurityException expected){}
        for(String field:new String[]{"issuer","code_challenge_methods_supported","token_endpoint"}){
            Fixture f=new Fixture();JSONObject metadata=f.serverMetadata();
            if(field.equals("issuer"))metadata.put(field,"https://evil.example.com");else if(field.equals("token_endpoint"))metadata.put(field,"https://evil.example.com/token");else metadata.remove(field);
            f.metadata.put("https://auth.example.com/.well-known/oauth-authorization-server/tenant",metadata);try{f.begin();fail();}catch(SecurityException|IllegalArgumentException expected){}assertEquals(0,f.posts.size());
        }
    }
    @Test public void cancelledOrLostExchangeCannotBeReplayedAndReplacedInstallationBlocksCompletion()throws Exception {
        Fixture cancelled=new Fixture();McpOAuth.AuthorizationRequest request=cancelled.begin();cancelled.auth.cancelFlow(request.flowId);
        try{cancelled.auth.complete(cancelled.binding,request.flowId,cancelled.callback(request,ISSUER),new RunControl());fail();}catch(SecurityException expected){}assertEquals(0,cancelled.posts.size());
        Fixture lost=new Fixture();McpOAuth.AuthorizationRequest pending=lost.begin();lost.losePost=true;
        try{lost.auth.complete(lost.binding,pending.flowId,lost.callback(pending,ISSUER),new RunControl());fail();}catch(java.io.IOException expected){}
        try{lost.auth.complete(lost.binding,pending.flowId,lost.callback(pending,ISSUER),new RunControl());fail();}catch(SecurityException expected){}assertEquals(1,lost.posts.size());
        Fixture replaced=new Fixture();McpOAuth.AuthorizationRequest old=replaced.begin();replaced.live=false;
        try{replaced.auth.complete(replaced.binding,old.flowId,replaced.callback(old,ISSUER),new RunControl());fail();}catch(SecurityException expected){}assertEquals(0,replaced.posts.size());
    }
    @Test public void tokenIdentityScopeAndExpiryAreValidatedBeforePersistence()throws Exception {
        for(String field:new String[]{"resource","iss","scope","expires_in","token_type"}){
            Fixture f=new Fixture();McpOAuth.AuthorizationRequest request=f.begin();
            if(field.equals("expires_in"))f.token.put(field,"3600");else if(field.equals("scope"))f.token.put(field,"files:read admin");else if(field.equals("token_type"))f.token.put(field,"mac");else f.token.put(field,"https://evil.example.com");
            try{f.auth.complete(f.binding,request.flowId,f.callback(request,ISSUER),new RunControl());fail();}catch(SecurityException|IllegalArgumentException expected){}assertTrue(f.secrets.ciphertext.isEmpty());
        }
    }
    @Test public void credentialCannotCrossResourceIssuerClientInstallationOrExpiry()throws Exception {
        Fixture f=new Fixture();McpOAuth.AuthorizationRequest request=f.begin();McpOAuth.CredentialRef ref=f.auth.complete(f.binding,request.flowId,f.callback(request,ISSUER),new RunControl());
        for(int mode=0;mode<5;mode++)try{
            McpOAuth.Binding binding=mode==3?new McpOAuth.Binding("new-install",RESOURCE,"a".repeat(64),"b".repeat(64)):
                    mode==4?new McpOAuth.Binding("install",RESOURCE,"a".repeat(64),"c".repeat(64)):f.binding;
            f.auth.accessToken(binding,ref.reference,mode==1?"https://evil.example.com":ISSUER,mode==2?"other-client":f.client.clientId,mode==0?"https://mcp.example.com/other":RESOURCE,new RunControl());fail();
        }catch(SecurityException expected){}
        f.now.set(ref.expiresAt);try{f.auth.accessToken(f.binding,ref.reference,ISSUER,f.client.clientId,RESOURCE,new RunControl());fail();}catch(SecurityException expected){}
    }
    @Test public void insecureEndpointsAmbiguousMetadataAndDuplicateJsonAreRejected()throws Exception {
        for(String url:List.of("http://auth.example.com","https://user:pass@auth.example.com","https://127.0.0.1/token","https://[::1]/token","https://localhost/token","https://auth.example.com/token?key=trap","https://auth.example.com/#trap"))
            try{McpOAuthMetadata.https(url);fail(url);}catch(SecurityException expected){}
        for(String header:List.of("Bearer resource_metadata=\"https://auth.example.com/a\", resource_metadata=\"https://auth.example.com/b\"","Bearer scope=\"read\", Bearer scope=\"write\""))
            try{McpOAuthMetadata.challenge(List.of(header));fail();}catch(IllegalArgumentException expected){}
        try{McpJson.parse("{\"issuer\":\"one\",\"issuer\":\"two\"}");fail();}catch(IllegalArgumentException expected){}
        assertFalse(McpOAuthHttp.publicAddress(java.net.InetAddress.getByName("127.0.0.1")));assertFalse(McpOAuthHttp.publicAddress(java.net.InetAddress.getByName("169.254.169.254")));assertFalse(McpOAuthHttp.publicAddress(java.net.InetAddress.getByName("fc00::1")));
    }
    @Test public void nonAdvertisingIssuerMayOmitIssButPlaintextStorageIsNeverAccepted()throws Exception {
        Fixture f=new Fixture();f.metadata.put("https://auth.example.com/.well-known/oauth-authorization-server/tenant",f.serverMetadata().put("authorization_response_iss_parameter_supported",false));
        McpOAuth.AuthorizationRequest request=f.begin();assertNotNull(f.auth.complete(f.binding,request.flowId,f.callback(request,null),new RunControl()));
        McpOAuth.EncryptedSecrets plain=new McpOAuth.EncryptedSecrets(){
            @Override public boolean encryptedAtRest(){return false;}
            @Override public void create(String s,String v){fail();}
            @Override public String read(String s){fail();return "";}
            @Override public String take(String s){fail();return "";}
            @Override public void remove(String s){fail();}
        };
        try{new McpOAuth(f,plain,f);fail();}catch(SecurityException expected){}
    }
    @Test public void concurrentCallbacksRedeemOneCodeOnlyAndCallbackResourceMismatchNeverPosts()throws Exception {
        Fixture f=new Fixture();McpOAuth.AuthorizationRequest request=f.begin();String callback=f.callback(request,ISSUER);
        java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(2),go=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> complete=()->{ready.countDown();go.await();try{f.auth.complete(f.binding,request.flowId,callback,new RunControl());return true;}catch(SecurityException alreadyConsumed){return false;}};
        try{
            java.util.concurrent.Future<Boolean> first=workers.submit(complete),second=workers.submit(complete);assertTrue(ready.await(2,java.util.concurrent.TimeUnit.SECONDS));go.countDown();
            assertNotEquals(first.get(2,java.util.concurrent.TimeUnit.SECONDS),second.get(2,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,f.posts.size());
        }finally{workers.shutdownNow();}
        Fixture wrong=new Fixture();McpOAuth.AuthorizationRequest other=wrong.begin();
        try{wrong.auth.complete(wrong.binding,other.flowId,wrong.callback(other,ISSUER)+"&resource=https%3A%2F%2Fother.example.com",new RunControl());fail();}catch(SecurityException expected){}assertEquals(0,wrong.posts.size());
    }
}
