package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.providers.ProviderCompatProfile;
import dev.pocketengine.runtime.providers.ProviderCompatibility;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.runtime.usage.ProviderUsage;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native root compatibility-profile ABI/receipt interop; no external model/cost assertion. */
public final class ProviderUsageCompatibilityTest {
    private static ModelConfig config(){return new ModelConfig("https://fixture.invalid/v1","private-key","fixture-model",false,8,10,"openai_chat",512);}
    private static JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","original exact request"));}
    private static ProviderCompatProfile profile()throws Exception{return ProviderCompatProfile.reviewed(new JSONObject(),"openai_chat","fixture-model",null);}
    private static void complete(ProviderUsageAnchor anchor,ModelConfig captured,ModelConfig dispatched,boolean projection)throws Exception{
        InferenceCapture value=new InferenceCapture(dispatched,new RunControl(),anchor.observe(InferenceCapture.unattributed(),messages(),null,captured));
        if(projection)value.requestProjection(ProviderCompatibility.diagnostics(dispatched,new JSONObject()));
        value.dispatch();value.usage.observe(new JSONObject().put("model","fixture-model").put("usage",new JSONObject().put("prompt_tokens",123).put("completion_tokens",5)),ProviderUsage.Stage.FINAL);value.usage.completed();value.finish(InferenceReceipt.Outcome.COMPLETED);
    }
    @Test public void exactNativeCapturedAndDispatchedProfileMayUseTheOriginalInputAnchor()throws Exception{
        ModelConfig config=config().withProviderCompatibility(profile());ProviderUsageAnchor anchor=new ProviderUsageAnchor();complete(anchor,config,config,true);assertEquals(123,anchor.charge(messages(),null,config).reportedInputTokens());
    }
    @Test public void profileRevisionChangeOrRemovalCannotReuseThePriorWireInputCharge()throws Exception{
        for(boolean removal:new boolean[]{false,true}){ProviderCompatProfile old=profile();ModelConfig selected=config().withProviderCompatibility(old);ProviderUsageAnchor anchor=new ProviderUsageAnchor();complete(anchor,selected,selected,true);ModelConfig changed=removal?config():config().withProviderCompatibility(ProviderCompatProfile.reviewed(new JSONObject().put("streamUsage","OMIT"),"openai_chat","fixture-model",old));assertNull(anchor.charge(messages(),null,changed));}
    }
    @Test public void anActuallyDifferentDispatchedProfileCannotSeedTheCapturedProfile()throws Exception{
        ProviderCompatProfile old=profile(),different=ProviderCompatProfile.reviewed(new JSONObject().put("streamUsage","OMIT"),"openai_chat","fixture-model",old);ModelConfig captured=config().withProviderCompatibility(old),actual=config().withProviderCompatibility(different);ProviderUsageAnchor anchor=new ProviderUsageAnchor();complete(anchor,captured,actual,true);assertNull(anchor.charge(messages(),null,captured));
    }
    @Test public void aConfiguredProfileWithoutItsNativeProjectionReceiptIsUnconfirmedNotDefault()throws Exception{
        ModelConfig config=config().withProviderCompatibility(profile());ProviderUsageAnchor anchor=new ProviderUsageAnchor();complete(anchor,config,config,false);assertNull(anchor.charge(messages(),null,config));
    }
}
