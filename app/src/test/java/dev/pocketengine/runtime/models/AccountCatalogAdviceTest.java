package dev.pocketengine.runtime.models;
import dev.pocketengine.core.ModelConfig;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public final class AccountCatalogAdviceTest {
    @Test public void ProviderOutputCannotBeFixedByReducingOnlyTheLocalReserve()throws Exception {
        ModelConfig selected=new ModelConfig("","","",false,3,10).withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject()
                .put("provider","chatgpt_plan").put("accountId","fixture-account").put("accountVersion",7).put("modelId","fixture-model")))
                .withAccountOptions(ReasoningOptions.DEFAULT,0,128);
        AccountCatalogFacts provider=AccountCatalogFacts.parse(new JSONObject().put("context_window",2000).put("max_output_tokens",3000),"fixture-model",1000);
        try{provider.budget(selected);fail();}catch(ModelRequestPolicy.Rejected expected){assertEquals("account_output_exhausts_context",expected.code);assertTrue(expected.getMessage().contains("刷新目录"));assertTrue(expected.getMessage().contains("其他模型"));assertFalse(expected.getMessage().contains("降低"));}
        AccountCatalogFacts local=AccountCatalogFacts.parse(new JSONObject().put("context_window",2000).put("max_output_tokens",256),"fixture-model",1000);
        try{local.budget(selected.withAccountOptions(ReasoningOptions.DEFAULT,0,4096));fail();}catch(ModelRequestPolicy.Rejected expected){assertEquals("account_output_exhausts_context",expected.code);assertTrue(expected.getMessage().contains("降低本地输出预留"));}
        assertNotNull(local.budget(selected));
    }
}
