package dev.pocketengine.runtime;

import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.PhonePort;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunOutcome;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.phone.observation.AdaptivePhonePort;
import dev.pocketengine.phone.observation.ObservationReport;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real gateway/adapter path with a synthetic native account port; no account or network call. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class AdaptiveAccountRunnerTest {
    @Test public void textOnlyAccountCanUseAdaptiveNodesWithoutEverCapturingOrSendingAnImage()throws Exception{
        Field inference=ModelGateway.class.getDeclaredField("accountInference");inference.setAccessible(true);ModelGateway.AccountInferencePort original=(ModelGateway.AccountInferencePort)inference.get(null);AtomicInteger calls=new AtomicInteger(),observations=new AtomicInteger();
        try{
            ModelGateway.installAccountInferencePort((ref,request,operation,sink)->{try{calls.incrementAndGet();Field bytes=ResponsesRequest.class.getDeclaredField("bytes");bytes.setAccessible(true);JSONArray input=new JSONObject(new String((byte[])bytes.get(request),StandardCharsets.UTF_8)).getJSONArray("input");for(int i=0;i<input.length();i++){assertTrue(input.getJSONObject(i).opt("content") instanceof String);assertFalse(input.getJSONObject(i).getString("content").contains("data:image/"));}sink.event(new JSONObject().put("type","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","仅依据节点，请主人核对。"))))));}catch(Exception invalid){throw new dev.pocketengine.features.proxy.accounts.GatewayFailure("FIXTURE_FAILURE");}});
            PhonePort physical=new PhonePort(){public boolean isConnected(){return true;}public void clearReferences(){}public JSONObject execute(String tool,JSONObject args,RunControl control){throw new AssertionError("no action requested");}public JSONObject observe(boolean includeImage,RunControl control)throws Exception{assertFalse(includeImage);return ObservationReport.attach(new JSONObject().put("ok",true).put("observationId","nodes-"+observations.incrementAndGet()).put("sessionId","fixture-service").put("displayId",0).put("observedAtUptimeMs",1000L).put("pauseRevision",control.pauseRevision()).put("nodes",new JSONArray().put(new JSONObject().put("id","node-one").put("text","节点文字").put("enabled",true))),true,"fixture_tree",false,"not_requested");}};
            ModelConfig config=new ModelConfig("","","",true,3,10).withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId","fixture-account").put("accountVersion",1L).put("modelId","fixture-model")));assertFalse(config.supportsImageInput);
            RunControl control=new RunControl();control.bindHostRun("task","run",1);AdaptivePhonePort adaptive=new AdaptivePhonePort(physical,new AdaptivePhonePort.Scope("task","run",1,"fixture-service",0),config.sendScreenshots,config.supportsImageInput,()->1000L);
            RunOutcome result=new AgentRunner(adaptive).run("task","读取节点",config,control,event->{});assertEquals("needs_confirmation",result.status);assertEquals(1,calls.get());assertEquals(2,observations.get());
        }finally{ModelGateway.installAccountInferencePort(original);}
    }
}
