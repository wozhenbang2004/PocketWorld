package dev.pocketengine.runtime.models;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InferenceRouteContinuityTest {
    private static ModelConfig api(String endpoint,String key,String model,boolean images){return new ModelConfig(endpoint,key,model,images,20,10);}
    @Test public void originalTaskBudgetIsNotMistakenForANewApiRoute()throws Exception {
        ModelConfig selected=api("https://fixture.invalid/v1","key","model",false);
        InferenceRouteContinuity.requireCurrent(selected.withStepCeiling(40),selected,new RunControl());
        assertThrows(SecurityException.class,()->InferenceRouteContinuity.requireCurrent(selected,selected.withRequestOutputLimit(128),new RunControl()));
    }
    @Test public void apiEndpointKeyModelAndScreenshotConsentNeverAlias()throws Exception {
        ModelConfig captured=api("https://fixture.invalid/v1","key","model",false);
        for(ModelConfig changed:new ModelConfig[]{api("https://other.invalid/v1","key","model",false),api("https://fixture.invalid/v1","changed-key","model",false),api("https://fixture.invalid/v1","key","other-model",false),api("https://fixture.invalid/v1","key","model",true)})
            assertThrows(SecurityException.class,()->InferenceRouteContinuity.requireCurrent(captured,changed,new RunControl()));
    }
    @Test public void cancelledControlCannotUseContinuityAsANewGrant()throws Exception {
        ModelConfig selected=api("https://fixture.invalid/v1","key","model",false);RunControl original=new RunControl();original.cancel();
        assertThrows(InterruptedException.class,()->InferenceRouteContinuity.requireCurrent(selected,selected,original));
    }
}
