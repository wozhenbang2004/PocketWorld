package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.runtime.usage.ProviderUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native capture/collector fixture, not a claim of an external paid request or exact tokenizer. */
public final class ProviderUsageAnchorTest {
    private static ModelConfig config(){return config("openai_chat","fixture-model","private-key");}
    private static ModelConfig config(String provider,String model,String key){return new ModelConfig("https://fixture.invalid/v1",key,model,false,8,10,provider,512);}
    private static JSONArray messages()throws Exception{return new JSONArray().put(new JSONObject().put("role","system").put("content","native invariant")).put(new JSONObject().put("role","user").put("content","original owner text"));}
    private static JSONObject wire(ModelConfig config,long input)throws Exception{
        JSONObject usage=new JSONObject().put("output_tokens",5).put("input_tokens",input);
        if(config.provider.equals("openai_chat"))usage=new JSONObject().put("completion_tokens",5).put("prompt_tokens",input);
        return new JSONObject().put("model",config.model).put("usage",usage);
    }
    private static final class Ledger implements InferenceCapture.Sink {
        int admissions;final List<InferenceReceipt> started=new ArrayList<>(),finished=new ArrayList<>();boolean deny,failFinish;
        public void started(InferenceReceipt receipt){started.add(receipt);}
        public void finished(InferenceReceipt receipt){if(failFinish)throw new IllegalStateException("original SQL receipt unavailable");finished.add(receipt);}
        InferenceCapture.Target target(){return new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),this,()->{admissions++;if(deny)throw new SecurityException("original approval withdrawn");});}
    }
    private static InferenceCapture capture(ProviderUsageAnchor anchor,Ledger ledger,ModelConfig config,JSONArray messages,JSONArray tools)throws Exception{
        InferenceCapture value=new InferenceCapture(config,new RunControl(),anchor.observe(ledger.target(),messages,tools,config));value.dispatch();return value;
    }
    private static void complete(ProviderUsageAnchor anchor,Ledger ledger,ModelConfig config,JSONArray messages,JSONArray tools,long input)throws Exception{
        InferenceCapture value=capture(anchor,ledger,config,messages,tools);value.usage.observe(wire(config,input),ProviderUsage.Stage.FINAL);value.usage.completed();value.finish(InferenceReceipt.Outcome.COMPLETED);
    }
    @Test public void exactCompletedInputPricesOnlyItsUnchangedPrefixAndConservativeTail()throws Exception{
        ProviderUsageAnchor anchor=new ProviderUsageAnchor();Ledger ledger=new Ledger();ModelConfig config=config();JSONArray original=messages();String before=original.toString();complete(anchor,ledger,config,original,null,2000);
        JSONArray next=new JSONArray(original.toString()).put(new JSONObject().put("role","assistant").put("content","receipt summary"));ContextWindow.UsageCharge charge=anchor.charge(next,null,config);
        assertNotNull(charge);assertEquals(2000,charge.reportedInputTokens());assertTrue(charge.estimatedTokens()>2200);assertEquals(2,charge.anchoredMessages());assertEquals(ledger.finished.get(0).callId,charge.callId());assertEquals(before,original.toString());assertEquals(1,ledger.started.size());assertEquals(1,ledger.finished.size());assertEquals(2,ledger.admissions);
    }
    @Test public void tokenAnchorNeverRaisesTheIndependentRawByteCeiling()throws Exception{
        ProviderUsageAnchor anchor=new ProviderUsageAnchor();ModelConfig config=config();JSONArray messages=messages();messages.getJSONObject(1).put("content","主人原话".repeat(800));complete(anchor,new Ledger(),config,messages,null,10);
        ContextWindow.Plan plan=ContextWindow.plan(messages,null,new ContextWindow.Budget(10000,512,128,200,64,0),new ContextWindow.Retention(java.util.Map.of(),Set.of()),anchor.charge(messages,null,config));assertEquals("budget_exceeded",plan.status);assertTrue(plan.removed.isEmpty());assertTrue(plan.diagnostics().getBoolean("providerUsageUsed"));
    }
    @Test public void unknownTokenWindowStillUsesOnlyItsExistingBytePolicy()throws Exception{
        ProviderUsageAnchor anchor=new ProviderUsageAnchor();ModelConfig config=config();JSONArray messages=messages();complete(anchor,new Ledger(),config,messages,null,10);ContextWindow.Plan plan=ContextWindow.plan(messages,null,ContextWindow.Budget.localBytes(10000),new ContextWindow.Retention(java.util.Map.of(),Set.of()),anchor.charge(messages,null,config));assertTrue(plan.canSend());assertFalse(plan.diagnostics().getBoolean("providerUsageUsed"));assertFalse(plan.diagnostics().getBoolean("modelWindowKnown"));
    }
    @Test public void revisedOwnerSourceOrDynamicClockInvalidatesRatherThanNormalizingThePrefix()throws Exception{
        ProviderUsageAnchor anchor=new ProviderUsageAnchor();ModelConfig config=config();JSONArray messages=messages();complete(anchor,new Ledger(),config,messages,null,100);String old=messages.getJSONObject(0).getString("content");messages.getJSONObject(0).put("content","new native clock or source revision");assertNull(anchor.charge(messages,null,config));messages.getJSONObject(0).put("content",old);assertNull("No restoration to an old prefix revives the discarded anchor",anchor.charge(messages,null,config));
    }
    @Test public void toolsModelCredentialAndOutputReserveChangesFallBackConservatively()throws Exception{
        for(int kind=0;kind<4;kind++){
            ProviderUsageAnchor anchor=new ProviderUsageAnchor();ModelConfig original=config();JSONArray messages=messages();complete(anchor,new Ledger(),original,messages,null,100);
            ModelConfig selected=kind==1?original.withModel("other-model"):kind==2?config("openai_chat","fixture-model","new-private-key"):kind==3?original.withRequestOutputLimit(256):original;
            JSONArray tools=kind==0?new JSONArray().put(new JSONObject().put("newTool",true)):null;assertNull(anchor.charge(messages,tools,selected));
        }
    }
    @Test public void missingZeroOrPartialInputCannotBecomeACompletedInputAnchor()throws Exception{
        for(int kind=0;kind<3;kind++){
            ProviderUsageAnchor anchor=new ProviderUsageAnchor();ModelConfig config=config();JSONArray messages=messages();InferenceCapture value=capture(anchor,new Ledger(),config,messages,null);
            if(kind>0)value.usage.observe(wire(config,kind==1?0:100),kind==2?ProviderUsage.Stage.PARTIAL:ProviderUsage.Stage.FINAL);
            value.finish(InferenceReceipt.Outcome.COMPLETED);assertNull(anchor.charge(messages,null,config));
        }
    }
    @Test public void failedOrCancelledPhysicalCallClearsTheOldAnchorAndDoesNotSeedANewOne()throws Exception{
        for(InferenceReceipt.Outcome outcome:List.of(InferenceReceipt.Outcome.FAILED,InferenceReceipt.Outcome.CANCELLED)){
            ProviderUsageAnchor anchor=new ProviderUsageAnchor();ModelConfig config=config();JSONArray messages=messages();Ledger ledger=new Ledger();complete(anchor,ledger,config,messages,null,10);assertNotNull(anchor.charge(messages,null,config));InferenceCapture failed=capture(anchor,ledger,config,messages,null);failed.usage.observe(wire(config,20),ProviderUsage.Stage.FINAL);failed.finish(outcome);assertNull(anchor.charge(messages,null,config));assertEquals(2,ledger.finished.size());
        }
    }
    @Test public void finalCumulativeSnapshotsWithinOnePhysicalCallAreNotSummedAcrossEvents()throws Exception{
        ProviderUsageAnchor anchor=new ProviderUsageAnchor();ModelConfig config=config();JSONArray messages=messages();InferenceCapture value=capture(anchor,new Ledger(),config,messages,null);value.usage.observe(wire(config,100),ProviderUsage.Stage.PARTIAL);value.usage.observe(wire(config,150),ProviderUsage.Stage.PARTIAL);value.usage.observe(wire(config,200),ProviderUsage.Stage.FINAL);value.usage.completed();value.finish(InferenceReceipt.Outcome.COMPLETED);assertEquals(200,anchor.charge(messages,null,config).reportedInputTokens());
    }
    @Test public void aliasResponseModelCannotPriceTheCapturedRequestedModel()throws Exception{
        ProviderUsageAnchor anchor=new ProviderUsageAnchor();ModelConfig config=config();JSONArray messages=messages();InferenceCapture value=capture(anchor,new Ledger(),config,messages,null);value.usage.observe(wire(config,100).put("model","actual-different-model"),ProviderUsage.Stage.FINAL);value.usage.completed();value.finish(InferenceReceipt.Outcome.COMPLETED);assertNull(anchor.charge(messages,null,config));
    }
    @Test public void anthropicInputRequiresBothKnownCacheComponentsAndAddsEachOnlyOnce()throws Exception{
        ModelConfig config=config("anthropic","fixture-model","");JSONArray messages=messages();
        for(boolean allKnown:List.of(false,true)){
            ProviderUsageAnchor anchor=new ProviderUsageAnchor();InferenceCapture value=capture(anchor,new Ledger(),config,messages,null);JSONObject report=wire(config,100);report.getJSONObject("usage").put("cache_read_input_tokens",30);if(allKnown)report.getJSONObject("usage").put("cache_creation_input_tokens",20);value.usage.observe(report,ProviderUsage.Stage.FINAL);value.usage.completed();value.finish(InferenceReceipt.Outcome.COMPLETED);
            if(allKnown)assertEquals(150,anchor.charge(messages,null,config).reportedInputTokens());else assertNull(anchor.charge(messages,null,config));
        }
    }
    @Test public void overflowIsOnlyAnUnavailableEstimateAndNeverErasesTheOriginalReceipt()throws Exception{
        ProviderUsageAnchor anchor=new ProviderUsageAnchor();ModelConfig config=config();JSONArray messages=messages();Ledger ledger=new Ledger();complete(anchor,ledger,config,messages,null,Long.MAX_VALUE);assertNull(anchor.charge(messages,null,config));assertEquals(1,ledger.finished.size());assertEquals(InferenceReceipt.Outcome.COMPLETED,ledger.finished.get(0).outcome);
    }
    @Test public void originalAdmissionAndOriginalLedgerFailureRemainAuthoritative()throws Exception{
        ProviderUsageAnchor anchor=new ProviderUsageAnchor();ModelConfig config=config();JSONArray messages=messages();Ledger denied=new Ledger();denied.deny=true;
        try{capture(anchor,denied,config,messages,null);fail();}catch(SecurityException expected){}assertTrue(denied.started.isEmpty());assertNull(anchor.charge(messages,null,config));
        Ledger unavailable=new Ledger();unavailable.failFinish=true;InferenceCapture value=capture(anchor,unavailable,config,messages,null);value.usage.observe(wire(config,100),ProviderUsage.Stage.FINAL);value.usage.completed();try{value.finish(InferenceReceipt.Outcome.COMPLETED);fail();}catch(IllegalStateException expected){}assertNull(anchor.charge(messages,null,config));
    }
    @Test public void actualAccountVersionMustMatchAndSelectionChangeInvalidatesTheAnchor()throws Exception{
        ModelConfig base=config();ModelConfig.AccountModelRef reference=ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId","original-account").put("accountVersion",3).put("modelId","fixture-model"));ModelConfig config=base.withAccountModel(reference);JSONArray messages=messages();
        for(boolean matches:List.of(false,true)){
            ProviderUsageAnchor anchor=new ProviderUsageAnchor();InferenceCapture value=capture(anchor,new Ledger(),config,messages,null);value.accountVersion(matches?3:4);value.usage.observe(wire(config,100),ProviderUsage.Stage.FINAL);value.usage.completed();value.finish(InferenceReceipt.Outcome.COMPLETED);
            if(matches){assertNotNull(anchor.charge(messages,null,config));assertNull(anchor.charge(messages,null,base.withAccountModel(reference.withVersion(4))));}else assertNull(anchor.charge(messages,null,config));
        }
    }
    @Test public void budgetDiagnosticsExposeOnlyCountersAndCallIdentityNotSecretsOrTheOwnerBody()throws Exception{
        ModelConfig config=config();JSONArray messages=messages();RunContextBudget budget=new RunContextBudget(selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(100000,512,128,100000,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1));Ledger ledger=new Ledger();InferenceCapture value=new InferenceCapture(config,new RunControl(),budget.inferenceTarget(ledger.target(),messages,null,config));value.dispatch();value.usage.observe(wire(config,100),ProviderUsage.Stage.FINAL);value.usage.completed();value.finish(InferenceReceipt.Outcome.COMPLETED);
        JSONObject diagnostics=budget.request(messages,null,config).diagnostics();assertTrue(diagnostics.getBoolean("providerUsageUsed"));assertEquals(100,diagnostics.getLong("providerReportedInputTokens"));assertFalse(diagnostics.toString().contains(config.apiKey));assertFalse(diagnostics.toString().contains("original owner text"));
        assertFalse("Checkpoint retention remains independent of provider input estimates",budget.checkpoint(messages,java.util.Map.of(),0,new JSONObject(),config).diagnostics().getBoolean("providerUsageUsed"));
    }
}
