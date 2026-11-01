package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.proxy.accounts.GatewayFailure;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AccountCatalogFactsTest {
    private AccountCatalogFacts parse(JSONObject row){return AccountCatalogFacts.parse(row,"neutral-model",1000);}
    private ModelConfig selected()throws Exception {
        return new ModelConfig("","","",false,3,10).withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject()
                .put("provider","chatgpt_plan").put("accountId","fixture-account").put("accountVersion",7).put("modelId","neutral-model")));
    }
    private ReasoningOptions effort(String value){return new ReasoningOptions(ReasoningOptions.Mode.OPENAI_EFFORT,value,0);}
    @Test public void absentFieldsStayUnknownWithoutSlugOrSdkDefaults()throws Exception {
        AccountCatalogFacts value=parse(new JSONObject().put("slug","huge-vision-thinking-model"));
        assertFalse(value.facts.contextWindow().known);assertFalse(value.facts.outputTokens().known);
        assertFalse(value.facts.imageInput().known);assertFalse(value.facts.reasoning().known);assertFalse(value.inputModalities.known);
        assertEquals("not_returned_by_catalog",value.facts.contextWindow().reason);
        assertEquals(1000,value.facts.contextWindow().observedAtMillis);
        assertNull(value.budget(selected()));
    }
    @Test public void explicitFieldsShareFactsTypesAndKeepReasoningAndModalityOrder()throws Exception {
        AccountCatalogFacts value=parse(new JSONObject().put("context_window",12000).put("max_output_tokens",2048)
                .put("input_modalities",new JSONArray().put("text").put("image").put("file"))
                .put("supported_reasoning_levels",new JSONArray().put(new JSONObject().put("effort","high")).put(new JSONObject().put("effort","low"))));
        assertEquals(12000,value.facts.contextWindow().value.tokens());assertEquals(Long.valueOf(2048),value.facts.outputTokens().value);
        assertEquals(List.of("high","low"),value.facts.reasoningLevels().value);assertEquals(List.of("text","image","file"),value.inputModalities.value);
        assertTrue(value.facts.imageInput().value);value.validateOptions(effort("low"));
        assertEquals(ModelFacts.Source.PROVIDER_CATALOG,value.facts.reasoning().source);
        assertTrue(value.facts.reasoning().sourceRef.startsWith(AccountCatalogFacts.SOURCE));
        try{value.facts.reasoningLevels().value.add("forged");fail();}catch(UnsupportedOperationException expected){}
    }
    @Test public void malformedOptionalFieldsDoNotEraseIndependentKnownFacts()throws Exception {
        AccountCatalogFacts value=parse(new JSONObject().put("context_window",16000).put("max_output_tokens","2048")
                .put("input_modalities",new JSONArray().put("text").put("image"))
                .put("capabilities",new JSONObject().put("image_input",false))
                .put("supported_reasoning_levels",new JSONArray().put(new JSONObject().put("effort","low")).put(new JSONObject().put("effort","low"))));
        assertTrue(value.facts.contextWindow().known);assertFalse(value.facts.outputTokens().known);
        assertFalse(value.facts.imageInput().known);assertEquals("conflicting_catalog_values",value.facts.imageInput().reason);
        assertFalse(value.facts.reasoningLevels().known);assertFalse(value.facts.reasoning().known);
    }
    @Test public void booleansFractionalAndNegativeNumbersAreNotTokenLimits()throws Exception {
        for(Object invalid:new Object[]{true,0,-1,1.5,"1000",JSONObject.NULL}){
            AccountCatalogFacts value=parse(new JSONObject().put("context_window",invalid).put("max_output_tokens",invalid));
            assertFalse(value.facts.contextWindow().known);assertFalse(value.facts.outputTokens().known);
        }
    }
    @Test public void storageRoundtripPreservesObservedFactsAndRejectsMixedObservationTimes()throws Exception {
        AccountCatalogFacts value=parse(new JSONObject().put("context_window",9000).put("input_modalities",new JSONArray().put("text")));
        AccountCatalogFacts restored=AccountCatalogFacts.fromJson(value.toJson());
        assertEquals(value.toJson().toString(),restored.toJson().toString());
        JSONObject corrupt=value.toJson();corrupt.getJSONObject("facts").getJSONObject("CONTEXT_WINDOW").put("observedAtMillis",1001);
        try{AccountCatalogFacts.fromJson(corrupt);fail();}catch(IllegalArgumentException expected){}
        JSONObject otherModel=value.toJson();otherModel.getJSONObject("facts").getJSONObject("CONTEXT_WINDOW").put("sourceRef",AccountCatalogFacts.SOURCE+"#models/different-model/context_window");
        try{AccountCatalogFacts.fromJson(otherModel);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void unknownOrRemovedEffortCannotBecomeARequestSetting()throws Exception {
        AccountCatalogFacts unknown=parse(new JSONObject());unknown.validateOptions(ReasoningOptions.DEFAULT);
        try{unknown.validateOptions(effort("high"));fail();}catch(ModelRequestPolicy.Rejected expected){assertEquals("account_reasoning_option_unavailable",expected.code);}
        AccountCatalogFacts empty=parse(new JSONObject().put("supported_reasoning_levels",new JSONArray()));
        assertTrue(empty.facts.reasoning().known);assertFalse(empty.facts.reasoning().value);
        try{empty.validateOptions(effort("high"));fail();}catch(ModelRequestPolicy.Rejected expected){}
    }
    @Test public void KnownMaximumOutputIsReservedDespiteSmallerUnenforcedOwnerLimit()throws Exception {
        AccountCatalogFacts value=parse(new JSONObject().put("context_window",12000).put("max_output_tokens",3000));
        ModelConfig config=selected().withAccountOptions(ReasoningOptions.DEFAULT,0,128);
        assertEquals(3000,value.budget(config).limits.outputReserve);
        assertFalse(config.publicJson().getBoolean("supportsOutputTokenLimit"));
        assertEquals("local_admission_reserve_not_sent",config.publicJson().getString("maxOutputTokensRole"));
        try{parse(new JSONObject().put("context_window",2000).put("max_output_tokens",3000)).budget(config);fail();}
        catch(ModelRequestPolicy.Rejected expected){assertEquals("account_output_exhausts_context",expected.code);}
    }
    @Test public void AccountCopiesKeepOptionsOnlyForSameAccountAndModel()throws Exception {
        ModelConfig first=selected().withAccountOptions(effort("low"),1234,256).withAccountImageInput(true);
        ModelConfig advanced=first.withAccountModel(first.accountModel.withVersion(9)).withStepCeiling(30);
        assertEquals(effort("low"),advanced.reasoning);assertEquals(1234,advanced.imageTokenReserve);assertEquals(256,advanced.maxOutputTokens);assertTrue(advanced.supportsImageInput);
        ModelConfig other=first.withAccountModel(ModelConfig.AccountModelRef.fromJson(first.accountModel.toJson().put("accountId","other-account")));
        assertEquals(ReasoningOptions.DEFAULT,other.reasoning);assertEquals(0,other.imageTokenReserve);assertFalse(other.supportsImageInput);
        try{ModelConfig.fromJson(new JSONObject().put("reasoning",effort("high").toJson()));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void ActualWireBudgetAndKnownToolDenialAreCheckedWithoutMutatingInput()throws Exception {
        JSONArray input=new JSONArray().put(new JSONObject().put("role","user").put("content","中".repeat(500)));
        String original=input.toString();ResponsesRequest request=new ResponsesRequest("neutral-model","",input,new JSONArray(),false,ReasoningOptions.DEFAULT,128,0);
        try{request.admit(parse(new JSONObject().put("context_window",1000)));fail();}catch(GatewayFailure expected){assertEquals("ACCOUNT_CONTEXT_BUDGET_EXCEEDED",expected.code);}
        assertEquals(original,input.toString());
        JSONArray tools=new JSONArray().put(new JSONObject().put("type","namespace").put("name","pocketworld").put("description","Fixture namespace").put("tools",new JSONArray()
                .put(new JSONObject().put("type","function").put("name","read").put("parameters",new JSONObject()))));
        ResponsesRequest toolRequest=new ResponsesRequest("neutral-model","",new JSONArray().put(new JSONObject().put("role","user").put("content","read")),tools);
        try{toolRequest.admit(parse(new JSONObject().put("capabilities",new JSONObject().put("tool_calls",new JSONObject().put("supported",false)))));fail();}
        catch(GatewayFailure expected){assertEquals("ACCOUNT_TOOL_CAPABILITY_UNSUPPORTED",expected.code);}
        ResponsesRequest explicit=new ResponsesRequest("neutral-model","",new JSONArray().put(new JSONObject().put("role","user").put("content","hello")),new JSONArray(),false,effort("low"),128,0);
        try{explicit.admit(null);fail();}catch(GatewayFailure expected){assertEquals("ACCOUNT_MODEL_OPTION_UNAVAILABLE",expected.code);}
    }
}
