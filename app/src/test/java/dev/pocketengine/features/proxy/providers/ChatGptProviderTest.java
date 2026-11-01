package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.features.proxy.accounts.Credentials;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChatGptProviderTest {
    static Credentials credentials()throws Exception{return new Credentials(FakeChatGpt.access(0),FakeChatGpt.refresh(0),"fixture-id-secret","chatgpt.tokens.use.direct",System.currentTimeMillis()+3600000);}
    static JSONArray input()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","Hello"));}
    @Test public void idTokenRequiresSignatureIssuerAudienceNonceAndExpiry()throws Exception {
        long now=System.currentTimeMillis();String valid=FakeChatGpt.jwt("issuer","subject","oaiapp_test","nonce",now/1000+3600);
        assertEquals("subject",OidcIdentityVerifier.verify(valid,FakeChatGpt.jwks(),"issuer","oaiapp_test","nonce","access",now).subject);
        for(String[] expectation:new String[][]{{"other","oaiapp_test","nonce"},{"issuer","wrong","nonce"},{"issuer","oaiapp_test","wrong"}})rejectIdentity(valid,expectation[0],expectation[1],expectation[2],now);
        rejectIdentity(FakeChatGpt.jwt("issuer","subject","oaiapp_test","nonce",now/1000-10),"issuer","oaiapp_test","nonce",now);
        String[] pieces=valid.split("\\.");String modified=pieces[0]+"."+FakeChatGpt.encode("{\"iss\":\"issuer\",\"sub\":\"attacker\"}".getBytes(StandardCharsets.UTF_8))+"."+pieces[2];rejectIdentity(modified,"issuer","oaiapp_test","nonce",now);
    }
    @Test public void unknownAlgorithmsKeySourcesAudienceMultiplicityAndAccessHashFailClosed()throws Exception {
        long now=System.currentTimeMillis();JSONObject claims=new JSONObject().put("iss","issuer").put("sub","subject").put("aud","oaiapp_test").put("nonce","nonce").put("iat",now/1000-1).put("exp",now/1000+3600);
        for(JSONObject header:new JSONObject[]{new JSONObject().put("alg","none").put("kid","fixture-key"),new JSONObject().put("alg","HS256").put("kid","fixture-key"),new JSONObject().put("alg","RS256").put("kid","fixture-key").put("jku","http://attacker.test/key")})rejectIdentity(FakeChatGpt.signed(claims,header),"issuer","oaiapp_test","nonce",now);
        claims.put("aud",new JSONArray().put("oaiapp_test").put("other"));rejectIdentity(FakeChatGpt.signed(claims,new JSONObject().put("alg","RS256").put("kid","fixture-key")),"issuer","oaiapp_test","nonce",now);
        claims.put("aud","oaiapp_test").put("at_hash","wrong");rejectIdentity(FakeChatGpt.signed(claims,new JSONObject().put("alg","RS256").put("kid","fixture-key")),"issuer","oaiapp_test","nonce",now);
    }
    @Test public void textStreamIsIncrementalAndSupportsSplitUtf8CrlfAndMultilineData()throws Exception {
        String stream=": comment\r\nevent: response.output_text.delta\r\ndata: {\"type\":\"response.output_text.delta\",\r\ndata: \"delta\":\"你好🙂\"}\r\n\r\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\r\n\r\n";
        JSONArray events=new JSONArray();ResponsesTextStream.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)){@Override public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(1,len));}},new GatewayCall(5000),events::put);
        assertEquals("你好🙂",events.getJSONObject(0).getString("delta"));assertEquals("completed",events.getJSONObject(1).getString("type"));
    }
    @Test public void noDoneOnTruncationFailureIncompleteUnsupportedToolOrOversize()throws Exception {
        for(String stream:new String[]{"data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n","data: {\"type\":\"response.failed\",\"error\":\"fixture-secret\"}\n\n","data: {\"type\":\"response.incomplete\"}\n\n","data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\"}}\n\n","data: "+"x".repeat(65536)+"\n\n"}){
            JSONArray events=new JSONArray();try{ResponsesTextStream.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),new GatewayCall(5000),events::put);fail();}catch(GatewayFailure failure){assertFalse(failure.toString().contains("fixture-secret"));}assertFalse(events.toString().contains("completed"));}
    }
    @Test public void bearerAndRefreshAndIdTokenAreRedactedEvenAcrossDeltas()throws Exception {
        String stream="";for(String delta:new String[]{"before fixture-acc","ess-secret-0 and fixture-refresh-","secret-0 and fixture-id-secret after"})stream+="data: "+new JSONObject().put("type","response.output_text.delta").put("delta",delta)+"\n\n";
        stream+="data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n";
        JSONArray events=new JSONArray();ResponsesTextStream.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),new GatewayCall(5000),events::put,FakeChatGpt.access(0),FakeChatGpt.refresh(0),"fixture-id-secret");String combined="";for(int i=0;i<events.length();i++)combined+=events.getJSONObject(i).optString("delta");assertEquals("before [redacted] and [redacted] and [redacted] after",combined);assertFalse(events.toString().contains("fixture-access"));
    }
    @Test public void responseDriven503RetryNeverReplaysTheBillablePost()throws Exception {
        try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){fake.responseStatus=503;try{provider.stream(credentials(),"fixture-model","",input(),new GatewayCall(5000),event->fail());fail();}catch(GatewayFailure expected){assertEquals("PROVIDER_UNAVAILABLE",expected.code);}Thread.sleep(80);assertEquals(1,fake.responses.get());}
    }
    @Test public void requestCancelClosesActualSocketBeforeResponseHeaders()throws Exception {
        try(ServerSocket server=new ServerSocket(0,4,java.net.InetAddress.getByName("127.0.0.1"));ChatGptProvider provider=new ChatGptProvider("http://127.0.0.1:"+server.getLocalPort(),"http://127.0.0.1:"+server.getLocalPort()+"/v1",true)){
            CountDownLatch read=new CountDownLatch(1),eof=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);GatewayCall operation=new GatewayCall(5000);
            try{Future<?> peer=workers.submit(()->{try(Socket socket=server.accept()){socket.setSoTimeout(3000);java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));int length=0;String line;while(!(line=reader.readLine()).isEmpty())if(line.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(line.split(":",2)[1].trim());for(int i=0;i<length;i++)reader.read();read.countDown();assertEquals(-1,reader.read());eof.countDown();}catch(Exception error){throw new RuntimeException(error);}});
                Future<String> result=workers.submit(()->{try{provider.stream(credentials(),"fixture-model","",input(),operation,event->fail());return "unexpected";}catch(Exception failure){return ((GatewayFailure)failure).code;}});
                assertTrue(read.await(2,TimeUnit.SECONDS));operation.cancel();assertEquals("CANCELLED",result.get(2,TimeUnit.SECONDS));assertTrue(eof.await(2,TimeUnit.SECONDS));peer.get();}
            finally{workers.shutdownNow();}}
    }
    @Test public void deadlineAndUnsupportedInputDoNotProduceFakeSuccess()throws Exception {
        try(FakeChatGpt fake=new FakeChatGpt();ChatGptProvider provider=fake.provider()){
            fake.allowResponse=new CountDownLatch(1);GatewayCall operation=new GatewayCall(150);try{provider.stream(credentials(),"fixture-model","",input(),operation,event->fail());fail();}catch(GatewayFailure error){assertEquals("DEADLINE_EXCEEDED",error.code);}fake.allowResponse.countDown();assertEquals(1,fake.responses.get());
            try{provider.stream(credentials(),"fixture-model","",new JSONArray().put(new JSONObject().put("role","system").put("content","x")),new GatewayCall(5000),event->fail());fail();}catch(GatewayFailure expected){assertEquals("UNSUPPORTED_OR_OVERSIZED_INPUT",expected.code);}assertEquals(1,fake.responses.get());
        }
    }
    @Test public void productionAuthorizationUsesOnlyFixedOriginsAndNoArbitraryRedirect()throws Exception {
        try(ChatGptProvider provider=new ChatGptProvider()){
            String url=provider.authorizationUrl("urn:uuid:00000000-0000-4000-8000-000000000000","","http://127.0.0.1:12345/auth/callback","state","nonce","challenge");assertTrue(url.startsWith("https://auth.openai.com/api/accounts/authorize?"));assertFalse(url.contains("backend-api"));assertFalse(url.contains("id_token_hint"));
            for(String callback:new String[]{"http://localhost:12345/auth/callback","pocketworld://callback","https://evil.test/auth/callback","http://127.0.0.1:12345/callback"})try{provider.authorizationUrl("host","",callback,"state","nonce","challenge");fail();}catch(GatewayFailure expected){assertEquals("INVALID_CALLBACK",expected.code);}
        }
    }
    static void rejectIdentity(String token,String issuer,String client,String nonce,long now)throws Exception {try{OidcIdentityVerifier.verify(token,FakeChatGpt.jwks(),issuer,client,nonce,"access",now);fail();}catch(GatewayFailure failure){assertEquals("INVALID_IDENTITY_TOKEN",failure.code);assertFalse(failure.toString().contains(token));}}
}
