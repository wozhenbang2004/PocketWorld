package dev.pocketengine.features.proxy;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.proxy.accounts.GatewayCall;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.runtime.models.InferenceAudiencePolicy;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RelayAudiencePolicyTest {
    private static final String TOKEN="audience-fixture-local-token-1234";
    private static ModelConfig api(){return new ModelConfig("http://127.0.0.1:1/v1","key","model",false,3,10);}
    private static ModelConfig plan()throws Exception{return api().withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER)
            .put("accountId","fixture-owner").put("accountVersion",1L).put("modelId","model")));}
    private static String request(LoopbackRelay relay,String path,String body)throws Exception {
        try(Socket client=new Socket("127.0.0.1",relay.port())){
            client.setSoTimeout(3000);byte[] payload=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);
            String headers=(body==null?"GET ":"POST ")+path+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+TOKEN+"\r\n"
                    +(body==null?"":"Content-Type: application/json\r\nContent-Length: "+payload.length+"\r\n")+"\r\n";
            client.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));client.getOutputStream().write(payload);client.getOutputStream().flush();
            return new String(client.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test public void capturedStartupPlanIsRejectedBeforeListenerOrAccountPort()throws Exception {
        AtomicInteger calls=new AtomicInteger();ModelConfig selected=plan();
        assertThrows(InferenceAudiencePolicy.Rejected.class,()->new LoopbackRelay(0,TOKEN,()->{calls.incrementAndGet();return selected;},null,selected));
        assertEquals(0,calls.get());
    }
    @Test public void everyPublicRequestChecksAudienceBeforeCatalogRefreshOrInference()throws Exception {
        AtomicInteger accountCalls=new AtomicInteger();ModelConfig selected=plan();
        LoopbackRelay.AccountRoute route=new LoopbackRelay.AccountRoute(){
            public JSONArray models(ModelConfig.AccountModelRef ref,GatewayCall call){accountCalls.incrementAndGet();return new JSONArray();}
            public void stream(ModelConfig.AccountModelRef ref,ResponsesRequest input,GatewayCall call,ChatGptProvider.EventSink sink){accountCalls.incrementAndGet();}
        };
        try(LoopbackRelay relay=new LoopbackRelay(0,TOKEN,()->selected,route)){
            assertTrue(request(relay,"/v1/models",null).startsWith("HTTP/1.1 403"));
            for(String flag:new String[]{"false","true"})assertTrue(request(relay,"/v1/responses","{\"input\":\"hello\",\"stream\":"+flag+",\"isOwner\":true}").startsWith("HTTP/1.1 403"));
            assertEquals(0,accountCalls.get());assertEquals(0,relay.requests());
        }
    }
    @Test public void switchingApiToPlanCannotBypassCapturedPublicAudience()throws Exception {
        AtomicReference<ModelConfig> selected=new AtomicReference<>(api());
        try(LoopbackRelay relay=new LoopbackRelay(0,TOKEN,selected::get)){
            assertTrue(request(relay,"/v1/models",null).startsWith("HTTP/1.1 200"));selected.set(plan());
            assertTrue(request(relay,"/v1/responses","{\"input\":\"hello\"}").startsWith("HTTP/1.1 403"));assertEquals(0,relay.requests());
        }
    }
}
