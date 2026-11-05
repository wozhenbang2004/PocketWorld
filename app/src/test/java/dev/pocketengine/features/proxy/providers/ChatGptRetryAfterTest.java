package dev.pocketengine.features.proxy.providers;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.proxy.accounts.Credentials;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.SafeModelRecovery;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real loopback errors exercise the native account recovery observation, without replay. */
public final class ChatGptRetryAfterTest {
    @Test public void httpDateUsesResponseClockRatherThanAnInventedMinute()throws Exception {
        for(int status:new int[]{429,503}){
            ModelGateway.GatewayException failure=failure(status,List.of("Sun, 06 Nov 1994 08:54:37 GMT"),List.of("Sun, 06 Nov 1994 08:49:37 GMT"));
            assertDelay(failure,300_000);assertEquals(-1,new SafeModelRecovery().delay(failure,0,System.currentTimeMillis()));
        }
    }
    @Test public void invalidSignedAndConflictingValuesCannotAuthorizeQuickReplay()throws Exception {
        StringBuilder quickReplay=new StringBuilder();
        for(List<String> values:List.of(List.of("-1"),List.of("+1"),List.of("300","1"),List.of("1, 300"))){
            ModelGateway.GatewayException failure=failure(503,values,List.of());
            long allowed=new SafeModelRecovery().delay(failure,0,System.currentTimeMillis());
            if(allowed>=0)quickReplay.append(values).append(" allowed ").append(allowed).append(" ms; ");
            else assertDelay(failure,60_000);
        }
        assertEquals("",quickReplay.toString());
    }
    @Test public void validSecondsKeepTheirExistingBoundedCooldown()throws Exception {
        for(String value:new String[]{"0","1","300","86400","9223372036854775807"}){
            long expected=Math.max(1,Math.min(Long.parseLong(value),86400))*1000;
            assertDelay(failure(429,List.of(value),List.of()),expected);
        }
    }
    @Test public void missingAndMalformedHeadersKeepTheLocalFallback()throws Exception {
        for(List<String> values:List.<List<String>>of(List.of(),List.of("private-provider-prose"),List.of("Thu, 99 Nov 2026 00:00:00 GMT")))
            assertDelay(failure(503,values,List.of()),60_000);
    }
    @Test public void retryHintsDoNotChangeAuthenticationOrPolicyFailures()throws Exception {
        for(int status:new int[]{400,401,403}){
            ModelGateway.GatewayException failure=failure(status,List.of("1"),List.of());
            assertEquals("MISSING",failure.observation().retry().getString("status"));
            assertEquals(-1,new SafeModelRecovery().delay(failure,0,System.currentTimeMillis()));
        }
    }
    private void assertDelay(ModelGateway.GatewayException failure,long expected)throws Exception {
        JSONObject retry=failure.observation().retry();assertEquals("VALID",retry.getString("status"));
        long delay=retry.getLong("estimatedNotBefore")-failure.observation().observedAt;
        assertTrue("expected "+expected+", observed "+delay,delay<=expected&&delay>=expected-500);
        assertFalse(failure.getMessage().contains("private-provider-prose"));
    }
    private ModelGateway.GatewayException failure(int status,List<String> retry,List<String> date)throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);AtomicInteger requests=new AtomicInteger();
        server.createContext("/",exchange->{
            try{
                requests.incrementAndGet();for(String value:retry)exchange.getResponseHeaders().add("Retry-After",value);
                for(String value:date)exchange.getResponseHeaders().add("Date",value);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                byte[] body="{\"error\":{\"code\":\"unknown\",\"message\":\"private-provider-prose\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);
            }finally{exchange.close();}
        });server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();
        try(ChatGptProvider provider=new ChatGptProvider(base,base+"/v1",true)){
            Credentials credentials=new Credentials("fixture-access","fixture-refresh","fixture-id",ChatGptProvider.SCOPES,System.currentTimeMillis()+3600000);
            ModelGateway.installAccountInferencePort((ref,request,operation,sink)->provider.streamResponses(credentials,request,operation,sink));
            ModelConfig.AccountModelRef ref=ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan")
                    .put("accountId","00000000-0000-0000-0000-000000000001").put("accountVersion",1).put("modelId","fixture-model"));
            ModelConfig config=new ModelConfig("","","",false,3,10).withAccountModel(ref);
            JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content","hello"));
            ModelGateway.GatewayException failure=assertThrows(ModelGateway.GatewayException.class,()->ModelGateway.complete(config,messages,null,new RunControl()));
            assertEquals(status,failure.observation().httpStatus);assertEquals(1,requests.get());return failure;
        }finally{ModelGateway.installAccountInferencePort(null);server.stop(0);}
    }
}
