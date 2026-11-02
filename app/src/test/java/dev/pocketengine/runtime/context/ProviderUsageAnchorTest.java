package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProviderUsageAnchorTest {
    private static ModelConfig config(String model,String secret){return new ModelConfig("https://api.example/v1",secret,model,false,4,10,"openai_chat",256);}
    private static JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","system").put("content","native instruction")).put(new JSONObject().put("role","user").put("content","中".repeat(3000)));}
    private static void completed(ProviderUsageAnchor anchor,JSONArray messages,JSONArray tools,ModelConfig config,boolean usage)throws Exception {
        InferenceCapture capture=new InferenceCapture(config,new RunControl(),anchor.observe(InferenceCapture.unattributed(),messages,tools,config));capture.dispatch();if(usage)capture.usage.observe(new JSONObject().put("model",config.model).put("usage",new JSONObject().put("prompt_tokens",1000).put("completion_tokens",30)),ProviderUsage.Stage.FINAL);capture.finish(InferenceReceipt.Outcome.COMPLETED);
    }
    @Test public void actualReceiptPricesOnlyExactUnchangedPrefixAndKeepsByteCeiling()throws Exception {
        ProviderUsageAnchor anchor=new ProviderUsageAnchor();JSONArray messages=messages(),tools=new JSONArray();ModelConfig config=config("model-exact-v1","credential-a");ContextWindow.Budget budget=new ContextWindow.Budget(8192,256,256,100000,20,0);
        assertFalse(ContextWindow.plan(messages,tools,budget,ContextWindow.Retention.preserveAll()).canSend());completed(anchor,messages,tools,config,true);JSONArray next=new JSONArray(messages.toString()).put(new JSONObject().put("role","assistant").put("content","真实回答"));ContextWindow.UsageCharge charge=anchor.charge(next,tools,config);assertNotNull(charge);assertEquals(1000,charge.reportedInputTokens());ContextWindow.Plan projected=ContextWindow.plan(next,tools,budget,ContextWindow.Retention.preserveAll(),charge);assertTrue(projected.canSend());assertTrue(projected.diagnostics().getBoolean("providerUsageUsed"));assertEquals(messages.getJSONObject(1).getString("content"),projected.messages().getJSONObject(1).getString("content"));assertFalse(ContextWindow.plan(next,tools,budget.withByteCeiling(1000),ContextWindow.Retention.preserveAll(),charge).canSend());
    }
    @Test public void modelCredentialSchemaAndPrefixChangesCannotReuseCalibration()throws Exception {
        ProviderUsageAnchor anchor=new ProviderUsageAnchor();JSONArray original=messages(),tools=new JSONArray();ModelConfig config=config("model-exact-v1","credential-a");completed(anchor,original,tools,config,true);assertNull(anchor.charge(original,tools,config("model-exact-v2","credential-a")));assertNull(anchor.charge(original,tools,config("model-exact-v1","credential-b")));assertNull(anchor.charge(original,new JSONArray().put(new JSONObject().put("function","changed")),config));JSONArray changed=new JSONArray(original.toString());changed.getJSONObject(1).put("content","owner correction");assertNull(anchor.charge(changed,tools,config));anchor.clear();assertNull(anchor.charge(original,tools,config));
    }
    @Test public void unknownUsageNeverBecomesAZeroTokenAnchor()throws Exception {ProviderUsageAnchor anchor=new ProviderUsageAnchor();JSONArray messages=messages(),tools=new JSONArray();ModelConfig config=config("model-exact-v1","");completed(anchor,messages,tools,config,false);assertNull(anchor.charge(messages,tools,config));}
}
