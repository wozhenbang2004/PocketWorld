package dev.pocketengine.runtime;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CleartextConsentTest {
    private ModelConfig config(String url){return new ModelConfig(url,"private-api-key","test",true,3,10);}
    @Test public void remoteHttpCannotDispatchCredentialsOrScreenBeforeExplicitConsent()throws Exception{
        ModelConfig config=config("http://192.0.2.5:11434/v1");
        JSONArray messages=new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONArray()
                .put(new JSONObject().put("type","text").put("text","private-screen-text"))
                .put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/png;base64,c2NyZWVu")))));
        ModelGateway.GatewayException failure=assertThrows(ModelGateway.GatewayException.class,()->ModelGateway.complete(config,messages,null,new RunControl()));
        assertTrue(failure.getMessage().contains("HTTP"));assertTrue(failure.getMessage().contains("本机模型设置"));assertFalse(failure.getMessage().contains(config.apiKey));
    }
    @Test public void consentIsBoundToProtocolOriginAndProvider()throws Exception{
        String consent=ModelConfig.cleartextConsentFor("openai_chat","http://192.0.2.5:11434/v1");
        assertNotNull(ModelGateway.endpoint(config("http://192.0.2.5:11434/custom").withCleartextConsent(consent)));
        assertThrows(ModelGateway.GatewayException.class,()->ModelGateway.endpoint(config("http://192.0.2.6:11434/v1").withCleartextConsent(consent)));
        assertThrows(ModelGateway.GatewayException.class,()->ModelGateway.endpoint(config("http://192.0.2.5:80/v1").withCleartextConsent(consent)));
        ModelConfig anthropic=new ModelConfig("http://192.0.2.5:11434","key","model",true,3,10,"anthropic",4096).withCleartextConsent(consent);
        assertThrows(ModelGateway.GatewayException.class,()->ModelGateway.endpoint(anthropic));
        assertEquals(consent,config("http://192.0.2.5:11434").withCleartextConsent(consent).withModel("other").withStepCeiling(60).cleartextConsent);
    }
    @Test public void httpsAndExplicitDeviceLoopbackDoNotNeedTheRemoteHttpGrant()throws Exception{
        for(String url:new String[]{"https://example.com/v1","http://127.0.0.1:11434/v1","http://localhost:8080/v1","http://[::1]:8080/v1"}){
            assertEquals("",ModelConfig.cleartextConsentFor("openai_chat",url));assertNotNull(ModelGateway.endpoint(config(url)));
        }
        assertThrows(ModelGateway.GatewayException.class,()->ModelGateway.endpoint(config("http://localhost.example.com/v1")));
    }
}
