package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InferenceAudiencePolicyTest {
    private static ModelConfig api(){return new ModelConfig("https://example.test/v1","key","model",false,3,10);}
    private static ModelConfig plan()throws Exception{return api().withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject()
            .put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId","owner-registration").put("accountVersion",1L).put("modelId","model")));}
    private static JSONObject origin()throws Exception{return new JSONObject().put("kind","group").put("actorId","group-member").put("spaceId","group-space").put("policyEpoch","epoch-1");}
    @Test public void externalClientsNeverInheritOwnerPlanButApiIsPreserved()throws Exception {
        for(NativeInferenceScope scope:new NativeInferenceScope[]{NativeInferenceScope.externalRelay(),NativeInferenceScope.externalGroup(origin(),"","",0,null)}){
            InferenceAudiencePolicy.require(api(),scope,null);
            assertThrows(InferenceAudiencePolicy.Rejected.class,()->InferenceAudiencePolicy.require(plan(),scope,null));
        }
        assertEquals("INFERENCE_AUDIENCE_UNPROVEN",assertThrows(InferenceAudiencePolicy.Rejected.class,()->InferenceAudiencePolicy.require(plan(),null,null)).code);
    }
    @Test public void exactOwnerActivityAndManualAccountChoiceRemainAllowed()throws Exception {
        RunControl control=new RunControl();control.bindHostRun("task","run",1);
        NativeInferenceScope owner=NativeInferenceScope.ownerApp("device-owner","private","task","run",1,control);
        InferenceAudiencePolicy.require(plan(),owner,control);
        ModelConfig other=api().withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER)
                .put("accountId","another-owned-registration").put("accountVersion",2L).put("modelId","model")));
        InferenceAudiencePolicy.require(other,owner,control);
        assertThrows(SecurityException.class,()->InferenceAudiencePolicy.require(plan(),owner,new RunControl()));
        control.cancel();assertThrows(InterruptedException.class,()->InferenceAudiencePolicy.require(plan(),owner,control));
    }
    @Test public void backgroundRequiresLiveOriginalConsentNotAStoredTrueFlag()throws Exception {
        RunControl control=new RunControl();control.bindHostRun("task","run",1);AtomicBoolean allowed=new AtomicBoolean(true);
        NativeInferenceScope background=NativeInferenceScope.ownerBackground("device-owner","private","task","run",1,control,"occurrence-consent-17",()->{
            if(!allowed.get())throw new SecurityException("consent revoked");
        });
        InferenceAudiencePolicy.require(plan(),background,control);allowed.set(false);
        assertThrows(SecurityException.class,()->InferenceAudiencePolicy.require(plan(),background,control));
    }
    @Test public void groupOriginIsCapturedAndCannotBecomeOwnerViaClientFields()throws Exception {
        JSONObject input=origin().put("isOwner",true);NativeInferenceScope group=NativeInferenceScope.externalGroup(input,"","",0,null);
        input.put("actorId","device-owner");
        assertEquals("group-member",group.actorId);assertEquals("group-member",group.publicJson().getJSONObject("origin").getString("actorId"));
        assertThrows(InferenceAudiencePolicy.Rejected.class,()->InferenceAudiencePolicy.require(plan(),group,null));
    }
    @Test public void min30WhitespaceValidationRetainsOriginalNativeScopeSemantics()throws Exception {
        RunControl control=new RunControl();
        assertThrows(IllegalArgumentException.class,()->NativeInferenceScope.ownerApp(" \t\u2003","private","","",0,control));
        NativeInferenceScope owner=NativeInferenceScope.ownerApp("\u00a0","private","","",0,control);
        assertEquals("\u00a0",owner.actorId);
        assertThrows(IllegalArgumentException.class,()->NativeInferenceScope.ownerBackground("owner","private","","",0,control,"\u2003",()->{}));
    }

}
