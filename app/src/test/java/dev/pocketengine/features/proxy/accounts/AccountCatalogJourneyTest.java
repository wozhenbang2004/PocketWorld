package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.ResponsesFixtures;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.context.ContextBudgetPolicy;
import dev.pocketengine.runtime.context.RunContextBudget;
import dev.pocketengine.runtime.models.AccountCatalogFacts;
import dev.pocketengine.runtime.models.ReasoningOptions;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual localhost catalog/token/Responses and native metadata, not a live provider/device claim. */
public final class AccountCatalogJourneyTest {
    @After public void unbind(){ModelGateway.installAccountInferencePort(null);}
    private JSONObject row(long window,String...levels)throws Exception {
        JSONArray efforts=new JSONArray();for(String level:levels)efforts.put(new JSONObject().put("effort",level));
        return new JSONObject().put("slug","fixture-model").put("display_name","Neutral model").put("visibility","list")
                .put("context_window",window).put("max_output_tokens",256).put("input_modalities",new JSONArray().put("text"))
                .put("supported_reasoning_levels",efforts);
    }
    private void catalog(AccountGatewayTest.Rig rig,JSONObject...rows)throws Exception {
        JSONArray models=new JSONArray();for(JSONObject row:rows)models.put(row);rig.fake.modelsBody=new JSONObject().put("models",models).toString();
    }
    private ModelConfig pin(AccountGatewayTest.Rig rig,String id)throws Exception {
        long version=rig.book.row(id).getLong("version");rig.gateway.modelsReviewed(id,version,new GatewayCall(5000));rig.gateway.selectModelReviewed(id,version,"fixture-model");
        return new ModelConfig("","","",false,3,10).withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan")
                .put("accountId",id).put("accountVersion",version).put("modelId","fixture-model"))).withAccountOptions(ReasoningOptions.DEFAULT,0,256);
    }
    private JSONArray input(String text)throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content",text));}
    private ResponsesRequest request(ModelConfig config,String text)throws Exception {
        return new ResponsesRequest(config.model,"",input(text),new JSONArray(),false,config.reasoning,config.maxOutputTokens,config.imageTokenReserve);
    }
    private ReasoningOptions effort(String level){return new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,level,0);}
    private void bind(AccountGatewayTest.Rig rig,AtomicLong persisted){
        ModelGateway.installAccountInferencePort(new ModelGateway.AccountInferencePort(){
            public boolean accountIdentityCurrent(ModelConfig.AccountModelRef ref){return rig.gateway.inferenceIdentityCurrent(ref.accountId,ref.accountVersion);}
            public AccountCatalogFacts modelFacts(ModelConfig.AccountModelRef ref)throws GatewayFailure{return rig.gateway.modelFacts(ref.accountId,ref.accountVersion,ref.modelId);}
            public void stream(ModelConfig.AccountModelRef ref,ResponsesRequest request,GatewayCall call,ChatGptProvider.EventSink sink)throws GatewayFailure{rig.gateway.streamResponsesReviewed(ref.accountId,ref.accountVersion,request,call,sink);}
            public void streamWithVersion(ModelConfig.AccountModelRef ref,ResponsesRequest request,GatewayCall call,ModelGateway.AccountVersionSink versions,ChatGptProvider.EventSink sink)throws GatewayFailure{
                rig.gateway.streamResponsesReviewed(ref.accountId,ref.accountVersion,request,call,actual->{persisted.compareAndSet(ref.accountVersion,actual);versions.authorized(actual);},sink);
            }
        });
    }
    private String answer()throws Exception {
        JSONObject done=ResponsesFixtures.message("message","verified",false,true);
        return new ResponsesFixtures().item(0,ResponsesFixtures.message("message","",false,false),false).text(0,"message","verified",false)
                .item(0,done,true).complete(new JSONArray().put(done)).toString();
    }
    private void reachRenewalWindow(AccountGatewayTest.Rig rig,String id,long version)throws Exception {
        long until=rig.book.credentials(id,version).expiresAt-59950;
        while(System.currentTimeMillis()<until)Thread.sleep(Math.max(1,Math.min(30,until-System.currentTimeMillis())));
        rig.fake.expiresIn=3600;
    }
    @Test public void CatalogKeepsServerOrderAndPerModelFactsFromTheActualToken()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();catalog(rig,row(12000,"high","low"),row(9000,"low").put("slug","second-model"),row(1000).put("slug","hidden").put("visibility","hide"));
            long version=rig.book.row(id).getLong("version");JSONObject result=rig.gateway.modelsReviewed(id,version,new GatewayCall(5000));JSONArray models=result.getJSONArray("models");
            assertEquals(id,result.getString("accountId"));assertEquals(version,result.getLong("version"));assertEquals(2,models.length());
            assertEquals("fixture-model",models.getJSONObject(0).getString("id"));assertEquals("second-model",models.getJSONObject(1).getString("id"));
            AccountCatalogFacts facts=AccountCatalogFacts.fromJson(models.getJSONObject(0).getJSONObject("catalogFacts"));
            assertEquals(12000,facts.facts.contextWindow().value.tokens());assertEquals(java.util.List.of("high","low"),facts.facts.reasoningLevels().value);
            assertEquals("Bearer fixture-access-secret-0",rig.fake.lastAuthorization);assertEquals(0,rig.fake.responses.get());
        }
    }
    @Test public void SameModelNameCannotBorrowFactsAcrossAccountsOrCredentialVersions()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String first=rig.connect();catalog(rig,row(12000,"low"));ModelConfig a=pin(rig,first);
            String second=rig.connect();catalog(rig,row(6000,"high"));ModelConfig b=pin(rig,second);
            assertEquals(12000,rig.gateway.modelFacts(first,a.accountModel.accountVersion,a.model).facts.contextWindow().value.tokens());
            assertEquals(6000,rig.gateway.modelFacts(second,b.accountModel.accountVersion,b.model).facts.contextWindow().value.tokens());
            try{rig.gateway.modelFacts(first,a.accountModel.accountVersion+1,a.model);fail();}catch(GatewayFailure expected){assertEquals("STALE_ACCOUNT_VERSION",expected.code);}
            try{rig.gateway.modelFacts(first,a.accountModel.accountVersion,"other-model");fail();}catch(GatewayFailure expected){assertEquals("STALE_MODEL_CATALOG",expected.code);}
        }
    }
    @Test public void ReturnedCatalogMutationCannotChangePinnedNativeFactsAndRemovalRejectsOldChoice()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();catalog(rig,row(12000,"low"));long version=rig.book.row(id).getLong("version");
            JSONObject external=rig.gateway.modelsReviewed(id,version,new GatewayCall(5000));external.getJSONArray("models").getJSONObject(0).getJSONObject("catalogFacts").getJSONObject("facts").getJSONObject("CONTEXT_WINDOW").getJSONObject("value").put("tokens",999999);
            rig.gateway.selectModelReviewed(id,version,"fixture-model");assertEquals(12000,rig.gateway.modelFacts(id,version,"fixture-model").facts.contextWindow().value.tokens());
            catalog(rig);rig.gateway.modelsReviewed(id,version,new GatewayCall(5000));
            try{rig.gateway.modelFacts(id,version,"fixture-model");fail();}catch(GatewayFailure expected){assertEquals("STALE_MODEL_CATALOG",expected.code);}
            try{rig.gateway.streamResponsesReviewed(id,version,new ResponsesRequest("fixture-model","",input("hello"),new JSONArray()),new GatewayCall(5000),event->{});fail();}
            catch(GatewayFailure expected){assertEquals("STALE_MODEL_CATALOG",expected.code);}assertEquals(0,rig.fake.responses.get());
        }
    }
    @Test public void PersistedFactsRemainBoundToNativeAccountGenerationAndNeverIncludeTokens()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();catalog(rig,row(12000,"low"));ModelConfig config=pin(rig,id);long version=config.accountModel.accountVersion;
            AccountBook restored=new AccountBook(rig.file,rig.vault,"host-owner");
            assertEquals(12000,restored.modelFacts(id,version,"fixture-model").facts.contextWindow().value.tokens());
            String disk=java.nio.file.Files.readString(rig.file.toPath());assertFalse(disk.contains("fixture-access-secret"));assertFalse(disk.contains("fixture-refresh-secret"));
            rig.gateway.logout(id,new GatewayCall(5000));try{rig.gateway.modelFacts(id,version,"fixture-model");fail();}catch(GatewayFailure expected){assertEquals("STALE_ACCOUNT_VERSION",expected.code);}
        }
    }
    @Test public void SelectedReasoningIsSentButUnsupportedOutputAndApiOptionsAreNeverAdded()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();catalog(rig,row(12000,"low"));ModelConfig config=pin(rig,id).withAccountOptions(effort("low"),0,256);
            bind(rig,new AtomicLong(config.accountModel.accountVersion));rig.fake.stream=answer();
            assertEquals("verified",ModelGateway.complete(config,input("hello"),null,new RunControl()).getString("content"));
            assertEquals("low",rig.fake.lastRequest.getJSONObject("reasoning").getString("effort"));
            assertFalse(rig.fake.lastRequest.has("max_output_tokens"));assertFalse(rig.fake.lastRequest.has("temperature"));assertFalse(rig.fake.lastRequest.has("previous_response_id"));
            assertFalse(rig.fake.lastRequest.getBoolean("store"));assertTrue(rig.fake.lastRequest.getBoolean("stream"));assertEquals(1,rig.fake.responses.get());
        }
    }
    @Test public void RefreshedCatalogInvalidatesRemovedEffortWithoutAnotherInference()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();catalog(rig,row(12000,"low"));ModelConfig config=pin(rig,id).withAccountOptions(effort("low"),0,256);
            bind(rig,new AtomicLong(config.accountModel.accountVersion));catalog(rig,row(12000,"high"));rig.gateway.modelsReviewed(id,config.accountModel.accountVersion,new GatewayCall(5000));
            try{ModelGateway.complete(config,input("hello"),null,new RunControl());fail();}catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage().contains("推理选项"));}
            assertEquals(0,rig.fake.responses.get());
            catalog(rig,row(12000,"high").put("input_modalities",new JSONArray().put("image")));rig.gateway.modelsReviewed(id,config.accountModel.accountVersion,new GatewayCall(5000));
            try{ModelGateway.complete(config.withAccountOptions(ReasoningOptions.DEFAULT,0,256),input("hello"),null,new RunControl());fail();}catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage().contains("文字"));}
            assertEquals(0,rig.fake.responses.get());
        }
    }
    @Test public void LegitimateRenewalFeedsTheSameRunBudgetThroughAcceptedVersionOnly()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=65;String id=rig.connect();catalog(rig,row(12000,"low"));ModelConfig captured=pin(rig,id).withAccountOptions(effort("low"),0,256);
            AtomicLong persisted=new AtomicLong(captured.accountModel.accountVersion);bind(rig,persisted);RunControl original=new RunControl();
            ContextBudgetPolicy.Source live=selected->{AccountCatalogFacts facts=ModelGateway.accountModelFacts(selected.accountModel);return facts==null?null:facts.budget(selected);};
            RunContextBudget budget=new RunContextBudget(ContextBudgetPolicy.forRun(live,original));
            assertEquals(12000,budget.request(input("hello"),null,captured).diagnostics().getLong("modelContextTokens"));
            reachRenewalWindow(rig,id,captured.accountModel.accountVersion);catalog(rig,row(9000,"low"));rig.fake.stream=answer();
            assertEquals("verified",ModelGateway.complete(captured,input("hello"),null,original).getString("content"));
            assertEquals(captured.accountModel.accountVersion+2,persisted.get());assertEquals(1,rig.fake.refreshes.get());
            assertEquals(9000,budget.request(input("hello"),null,captured).diagnostics().getLong("modelContextTokens"));
            try{new RunContextBudget(ContextBudgetPolicy.forRun(live,new RunControl())).request(input("hello"),null,captured);fail();}
            catch(RunContextBudget.Blocked expected){assertEquals("context_budget_unavailable",expected.status);}
            assertEquals(1,rig.fake.responses.get());
        }
    }
    @Test public void RenewalRechecksSmallerWindowBeforeUsageDispatchOrPost()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            rig.fake.expiresIn=65;String id=rig.connect();catalog(rig,row(12000,"low"));ModelConfig config=pin(rig,id).withAccountOptions(effort("low"),0,256);
            ResponsesRequest request=request(config,"x".repeat(2000));request.admit(rig.gateway.modelFacts(id,config.accountModel.accountVersion,config.model));
            reachRenewalWindow(rig,id,config.accountModel.accountVersion);catalog(rig,row(1000,"low"));AtomicLong dispatched=new AtomicLong();
            try{rig.gateway.streamResponsesReviewed(id,config.accountModel.accountVersion,request,new GatewayCall(5000),actual->dispatched.incrementAndGet(),event->{});fail();}
            catch(GatewayFailure expected){assertEquals("ACCOUNT_CONTEXT_BUDGET_EXCEEDED",expected.code);}
            assertEquals(1,rig.fake.refreshes.get());assertEquals(0,dispatched.get());assertEquals(0,rig.fake.responses.get());assertEquals(2,rig.fake.models.get());
        }
    }
    @Test public void UnknownCatalogKeepsOriginalInternalTextEntryButCannotAuthorizeAnEffort()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();ModelConfig config=new ModelConfig("","","",false,3,10).withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan").put("accountId",id).put("accountVersion",rig.book.row(id).getLong("version")).put("modelId","fixture-model")));
            bind(rig,new AtomicLong(config.accountModel.accountVersion));rig.fake.stream=answer();
            assertEquals("verified",ModelGateway.complete(config,input("hello"),null,new RunControl()).getString("content"));assertFalse(rig.fake.lastRequest.has("reasoning"));
            try{ModelGateway.complete(config.withAccountOptions(effort("low"),0,256),input("hello"),null,new RunControl());fail();}catch(ModelGateway.GatewayException expected){assertTrue(expected.getMessage().contains("推理选项"));}
            assertEquals(1,rig.fake.responses.get());assertEquals(0,rig.fake.models.get());
        }
    }
}
