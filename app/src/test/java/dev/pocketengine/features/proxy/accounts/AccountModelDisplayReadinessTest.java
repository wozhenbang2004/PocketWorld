package dev.pocketengine.features.proxy.accounts;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.models.AccountCatalogFacts;
import dev.pocketengine.features.proxy.providers.ChatGptProvider;
import dev.pocketengine.features.proxy.providers.ResponsesRequest;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original account metadata and loopback login only. No inference, credential refresh or live provider. */
public final class AccountModelDisplayReadinessTest {
    @After public void unbind(){ModelGateway.installAccountInferencePort(null);}
    private ModelConfig.AccountModelRef reference(AccountGatewayTest.Rig rig,String id)throws Exception {
        return ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan").put("accountId",id).put("accountVersion",rig.book.row(id).getLong("version")).put("modelId","fixture-model"));
    }
    private void bind(AccountGatewayTest.Rig rig){
        ModelGateway.installAccountInferencePort(new ModelGateway.AccountInferencePort(){
            public boolean accountIdentityCurrent(ModelConfig.AccountModelRef ref){return rig.gateway.inferenceIdentityCurrent(ref.accountId,ref.accountVersion);}
            public AccountCatalogFacts modelFacts(ModelConfig.AccountModelRef ref)throws GatewayFailure{return rig.gateway.modelFacts(ref.accountId,ref.accountVersion,ref.modelId);}
            public void stream(ModelConfig.AccountModelRef ref,ResponsesRequest request,GatewayCall call,ChatGptProvider.EventSink sink){throw new AssertionError("Display readiness must not request inference");}
        });
    }
    @Test public void exactNativeAccountModelCanBeDisplayedWithoutAnApiUrlAndRevocationClosesIt()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);ModelConfig.AccountModelRef original=reference(rig,id);
            ModelConfig config=new ModelConfig("","","",false,3,10).withAccountModel(original);
            assertTrue(config.baseUrl.isEmpty());assertTrue(ModelGateway.accountModelAvailableForDisplay(config.accountModel));
            String other=rig.connect();rig.gateway.select(other);
            assertTrue(ModelGateway.accountModelAvailableForDisplay(original));
            rig.gateway.setEnabled(id,false);
            assertFalse(ModelGateway.accountModelAvailableForDisplay(original));
            assertFalse(ModelGateway.accountModelAvailableForDisplay(reference(rig,id)));
            assertEquals(0,rig.fake.responses.get());assertEquals(0,rig.fake.refreshes.get());
        }
    }
    @Test public void unavailableModelAndClosedGatewayNeverBecomeDisplayReadiness()throws Exception {
        try(AccountGatewayTest.Rig rig=new AccountGatewayTest.Rig()){
            String id=rig.connect();bind(rig);ModelConfig.AccountModelRef ref=reference(rig,id);
            rig.book.metadata(id,ref.accountVersion,new JSONObject().put("modelEvidence",new JSONObject().put("accountVersion",ref.accountVersion).put("modelId",ref.modelId).put("unavailable",true)));
            assertFalse(ModelGateway.accountModelAvailableForDisplay(ref));
            rig.gateway.close();assertFalse(ModelGateway.accountModelAvailableForDisplay(ref));
            assertEquals(0,rig.fake.responses.get());assertEquals(0,rig.fake.refreshes.get());
        }
    }
    @Test public void missingOrChangingNativePortFailsClosedWithoutInference()throws Exception {
        ModelConfig.AccountModelRef ref=ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan").put("accountId","account-a").put("accountVersion",1).put("modelId","model-a"));
        ModelGateway.installAccountInferencePort(null);assertFalse(ModelGateway.accountModelAvailableForDisplay(ref));assertFalse(ModelGateway.accountModelAvailableForDisplay(null));
        ModelGateway.installAccountInferencePort(new ModelGateway.AccountInferencePort(){
            public boolean accountIdentityCurrent(ModelConfig.AccountModelRef actual){assertTrue(ref.matches(actual));return true;}
            public AccountCatalogFacts modelFacts(ModelConfig.AccountModelRef actual){ModelGateway.installAccountInferencePort(null);return null;}
            public void stream(ModelConfig.AccountModelRef actual,ResponsesRequest request,GatewayCall call,ChatGptProvider.EventSink sink){throw new AssertionError("must not infer");}
        });
        assertFalse(ModelGateway.accountModelAvailableForDisplay(ref));
    }
}
