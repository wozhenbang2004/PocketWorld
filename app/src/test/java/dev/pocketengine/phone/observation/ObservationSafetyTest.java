package dev.pocketengine.phone.observation;

import org.junit.Test;
import static org.junit.Assert.*;

public class ObservationSafetyTest {
    @Test public void unchangedPixelsAndGeometryPermitNodePreflight() {
        assertNull(ObservationSafety.frameFailure(42,720,1280,42,720,1280));
        assertNull(ObservationSafety.frameFailure(0,1,1,0,1,1));
    }
    @Test public void changedPixelsRejectEvenWhenNodeTreeCouldBeIdentical() {
        assertEquals("stale_observation",ObservationSafety.frameFailure(42,720,1280,43,720,1280));
        assertEquals("stale_observation",ObservationSafety.frameFailure(42,720,1280,41,720,1280));
    }
    @Test public void rotationOrMissingFrameCannotValidateOldNodeDecision() {
        assertEquals("stale_observation",ObservationSafety.frameFailure(42,720,1280,42,1280,720));
        assertEquals("stale_observation",ObservationSafety.frameFailure(42,720,1280,42,721,1280));
        assertEquals("stale_observation",ObservationSafety.frameFailure(42,720,1280,-1,720,1280));
        assertEquals("stale_observation",ObservationSafety.frameFailure(42,720,1280,42,0,1280));
        assertEquals("stale_observation",ObservationSafety.frameFailure(-1,720,1280,-1,720,1280));
    }
    @Test public void exactLiveIsolatedSessionIsRequired() {
        assertNull(ObservationSafety.isolationFailure(5,"session",5,"session",true,true,true,true));
        assertEquals("wrong_display",ObservationSafety.isolationFailure(5,"session",0,"session",true,true,true,true));
        assertEquals("wrong_display",ObservationSafety.isolationFailure(5,"session",5,"replacement",true,true,true,true));
        assertEquals("display_lost",ObservationSafety.isolationFailure(5,"session",5,"session",false,true,true,true));
    }
    @Test public void absentFocusGuaranteesNeverPermitBackgroundDispatch() {
        assertEquals("focus_isolation_lost",ObservationSafety.isolationFailure(5,"session",5,"session",true,false,true,true));
        assertEquals("focus_isolation_lost",ObservationSafety.isolationFailure(5,"session",5,"session",true,true,false,true));
        assertEquals("focus_isolation_lost",ObservationSafety.isolationFailure(5,"session",5,"session",true,true,true,false));
    }
    @Test public void invalidExpectedPrimaryOrMissingSessionCannotAuthorizeBackground() {
        assertEquals("wrong_display",ObservationSafety.isolationFailure(0,"session",0,"session",true,true,true,true));
        assertEquals("wrong_display",ObservationSafety.isolationFailure(5,null,5,"session",true,true,true,true));
        assertEquals("wrong_display",ObservationSafety.isolationFailure(5,"",5,"",true,true,true,true));
    }
}
