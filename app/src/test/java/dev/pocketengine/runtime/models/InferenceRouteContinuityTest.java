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
        assertThrows(InterruptedException.class,()->InferenceRouteContinuity.requireCurrentFromAuthorityCheck(selected,selected,original));
    }
    @Test public void originalAuthorityCallbackChecksLiveRouteWithoutRecursing()throws Exception {
        ModelConfig captured=api("https://fixture.invalid/v1","key","model",false);
        var selected=new java.util.concurrent.atomic.AtomicReference<>(captured);RunControl original=new RunControl();
        original.setAuthorityCheck(()->InferenceRouteContinuity.requireCurrentFromAuthorityCheck(captured,selected.get(),original));
        original.check();
        InferenceRouteContinuity.requireCurrent(captured,selected.get(),original);
        assertFalse(original.isCancelled());
        selected.set(api("https://other.invalid/v1","key","model",false));
        InterruptedException stopped=assertThrows(InterruptedException.class,original::check);
        assertTrue(stopped.getCause() instanceof SecurityException);
        assertTrue(original.isCancelled());
    }
    @Test public void authorityRouteCheckPreservesThreadInterruption()throws Exception {
        ModelConfig selected=api("https://fixture.invalid/v1","key","model",false);RunControl original=new RunControl();
        Thread.currentThread().interrupt();
        try{
            assertThrows(InterruptedException.class,()->InferenceRouteContinuity.requireCurrentFromAuthorityCheck(selected,selected,original));
            assertTrue(Thread.currentThread().isInterrupted());
        }finally{Thread.interrupted();}
    }
}
