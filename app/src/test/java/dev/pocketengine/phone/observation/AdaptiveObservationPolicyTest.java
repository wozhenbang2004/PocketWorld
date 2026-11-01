package dev.pocketengine.phone.observation;

import org.junit.Test;
import static dev.pocketengine.phone.observation.AdaptiveObservationPolicy.*;
import static org.junit.Assert.*;

public class AdaptiveObservationPolicyTest {
    private final AdaptiveObservationPolicy policy = new AdaptiveObservationPolicy();
    private Binding binding(String observation, long revision) {
        return new Binding("task", "run", 2, "session", 5, observation, revision);
    }
    private Decision decide(Binding expected, Evidence evidence, Need need, Health health,
                            boolean images, boolean vision, boolean ocr, boolean paused, boolean cancelled) {
        return policy.decide(expected, evidence, need, health, images, vision, ocr, paused, cancelled, 1000, 500);
    }
    private Evidence evidence(boolean tree, boolean truncated, int nodes, boolean image) {
        return new Evidence(binding("obs", 0), tree, truncated, nodes, image, false, 900);
    }
    @Test public void sufficientTreeUsesNodesEvenWithoutVisualEngine() {
        assertEquals(Request.NODES, decide(binding("obs", 0), evidence(true,false,4,false), Need.AUTOMATIC, Health.READY,false,false,false,false,false).request);
    }
    @Test public void missingTargetAndKnownRejectedActionAskForImage() {
        for (Need reason : new Need[]{Need.TARGET_MISSING, Need.ACTION_REJECTED, Need.EFFECT_CONFIRMED_ABSENT})
            assertEquals(Request.CAPTURE_IMAGE, decide(binding("obs",0), evidence(true,false,4,false),reason,Health.READY,true,true,false,false,false).request);
    }
    @Test public void imagePageUsesActualPixelsAndReportsAbsentOcrHonestly() {
        assertEquals(Request.CAPTURE_IMAGE,decide(binding("obs",0),evidence(false,false,0,false),Need.AUTOMATIC,Health.READY,true,true,false,false,false).request);
        assertEquals(Request.VISION,decide(binding("obs",0),evidence(false,false,0,true),Need.AUTOMATIC,Health.READY,true,true,false,false,false).request);
        assertEquals(Request.OCR,decide(binding("obs",0),evidence(false,false,0,true),Need.AUTOMATIC,Health.READY,true,false,true,false,false).request);
        assertEquals(Request.PAUSE,decide(binding("obs",0),evidence(false,false,0,true),Need.AUTOMATIC,Health.READY,true,false,false,false,false).request);
    }
    @Test public void imageFlagNeverOverridesOwnerConfiguration() {
        Decision d=decide(binding("obs",0),evidence(false,false,0,true),Need.AUTOMATIC,Health.READY,false,true,true,false,false);
        assertEquals(Request.PAUSE,d.request);assertTrue(d.reason.startsWith("local_capture_disabled:"));
    }
    @Test public void truncatedTreeNeedsVisualEvidence() {
        assertEquals(Request.CAPTURE_IMAGE,decide(binding("obs",0),evidence(true,true,6,false),Need.AUTOMATIC,Health.READY,true,true,false,false,false).request);
    }
    @Test public void unknownEffectNeverTurnsIntoRetryAfterPageOrPauseRevisionChange() {
        Decision d=decide(binding("new",1),evidence(true,false,2,false),Need.EFFECT_UNKNOWN,Health.READY,true,true,false,false,false);
        assertEquals(Request.RECONCILE,d.request);assertEquals("effect_unknown_do_not_resend",d.reason);
    }
    @Test public void oldObservationAndExpiredOrFutureClocksRequireFreshObservation() {
        assertEquals(Request.REOBSERVE,decide(binding("new",0),evidence(true,false,2,false),Need.AUTOMATIC,Health.READY,true,true,false,false,false).request);
        for(long at:new long[]{100,1001}) {
            Evidence old=new Evidence(binding("obs",0),true,false,2,false,false,at);
            assertEquals(Request.REOBSERVE,decide(binding("obs",0),old,Need.AUTOMATIC,Health.READY,true,true,false,false,false).request);
        }
    }
    @Test public void foreignRunEpochTaskOrDisplayNeverReobservesAsSameAuthority() {
        Binding[] foreign={new Binding("other","run",2,"session",5,"obs",0),new Binding("task","other",2,"session",5,"obs",0),
                new Binding("task","run",3,"session",5,"obs",0),new Binding("task","run",2,"other",5,"obs",0),new Binding("task","run",2,"session",0,"obs",0)};
        for(Binding b:foreign) assertEquals(Request.PAUSE,decide(b,evidence(true,false,2,false),Need.AUTOMATIC,Health.READY,true,true,false,false,false).request);
    }
    @Test public void permissionLockAndIsolationFailuresPauseAndCancellationWins() {
        for(Health h:Health.values()) if(h!=Health.READY)
            assertEquals(Request.PAUSE,decide(binding("obs",0),evidence(true,false,2,false),Need.AUTOMATIC,h,true,true,false,false,false).request);
        assertEquals(Request.PAUSE,decide(binding("obs",0),evidence(true,false,2,false),Need.AUTOMATIC,Health.READY,true,true,false,true,false).request);
        assertEquals(Request.CANCEL,decide(binding("obs",0),evidence(true,false,2,false),Need.AUTOMATIC,Health.LOCKED,true,true,false,true,true).request);
    }
    @Test public void genuineEmptyDisplayOnlyAllowsBootstrapDecision() {
        Evidence empty=new Evidence(binding("obs",0),false,false,0,false,true,900);
        assertEquals(Request.EMPTY_DISPLAY,decide(binding("obs",0),empty,Need.AUTOMATIC,Health.READY,false,false,false,false,false).request);
    }
}
