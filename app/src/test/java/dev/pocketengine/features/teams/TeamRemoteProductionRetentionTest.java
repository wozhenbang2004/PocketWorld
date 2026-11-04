package dev.pocketengine.features.teams;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Controlled receipt tests only. No companion request, SQL settlement or real cancellation. */
public final class TeamRemoteProductionRetentionTest {
    private static JSONObject stopped() throws Exception {
        return new JSONObject().put("status", "completed").put("dispatchFenceFinal", true)
                .put("processStarted", true).put("resourcesHeld", false)
                .put("processCleanupProof", new JSONObject().put("processGroupStoppedVerified", true));
    }

    private static JSONObject neverStarted() throws Exception {
        return new JSONObject().put("status", "cancelled").put("dispatchFenceFinal", true)
                .put("processStarted", false).put("notStartedConfirmed", true).put("resourcesHeld", false);
    }

    @Test public void stoppedProcessStillHeldByCompanionMustStayRetained() throws Exception {
        assertFalse("A stopped process does not override the companion's resource lease",
                TeamRemoteProduction.jobResourcesReleased(stopped().put("resourcesHeld", true)));
    }

    @Test public void neverStartedJobStillHeldByCompanionMustStayRetained() throws Exception {
        assertFalse("A no-dispatch proof does not override the companion's resource lease",
                TeamRemoteProduction.jobResourcesReleased(neverStarted().put("resourcesHeld", true)));
    }

    @Test public void missingNullOrMalformedHeldFlagMustStayRetainedForBothProcessBranches() throws Exception {
        Object[] values = {JSONObject.NULL, "false", "true", 0, 1, new JSONObject(), new JSONArray()};
        for (JSONObject actual : new JSONObject[]{stopped(), neverStarted()}) {
            actual.remove("resourcesHeld");
            assertFalse("Missing resourcesHeld is not a release receipt", TeamRemoteProduction.jobResourcesReleased(actual));
            for (Object value : values) {
                actual.put("resourcesHeld", value);
                assertFalse("Only boolean false releases resources: " + actual,
                        TeamRemoteProduction.jobResourcesReleased(actual));
            }
        }
    }

    @Test public void explicitReleaseStillRequiresTheOriginalTerminalAndFinalFence() throws Exception {
        for (String status : new String[]{"completed", "failed", "cancelled", "timed_out", "blocked"}) {
            assertTrue(status, TeamRemoteProduction.jobResourcesReleased(stopped().put("status", status)));
            assertTrue(status, TeamRemoteProduction.jobResourcesReleased(neverStarted().put("status", status)));
        }
        for (String status : new String[]{"queued", "running", "stopping", "unknown", "interrupted", ""}) {
            assertFalse(status, TeamRemoteProduction.jobResourcesReleased(stopped().put("status", status)));
            assertFalse(status, TeamRemoteProduction.jobResourcesReleased(neverStarted().put("status", status)));
        }
        for (Object finalFence : new Object[]{JSONObject.NULL, false, "true", 1}) {
            assertFalse(TeamRemoteProduction.jobResourcesReleased(stopped().put("dispatchFenceFinal", finalFence)));
            assertFalse(TeamRemoteProduction.jobResourcesReleased(neverStarted().put("dispatchFenceFinal", finalFence)));
        }
        JSONObject actual = stopped();
        actual.remove("dispatchFenceFinal");
        assertFalse(TeamRemoteProduction.jobResourcesReleased(actual));
    }

    @Test public void explicitReleaseStillRequiresARealStoppedProcessProof() throws Exception {
        JSONObject actual = stopped();
        actual.remove("processCleanupProof");
        assertFalse(TeamRemoteProduction.jobResourcesReleased(actual));
        for (Object stopped : new Object[]{JSONObject.NULL, false, "true", 1}) {
            actual.put("processCleanupProof", new JSONObject().put("processGroupStoppedVerified", stopped));
            assertFalse(TeamRemoteProduction.jobResourcesReleased(actual));
        }
        actual.put("processCleanupProof", "stopped");
        assertFalse(TeamRemoteProduction.jobResourcesReleased(actual));
    }

    @Test public void explicitReleaseStillRequiresAnUnambiguousNoDispatchProof() throws Exception {
        for (Object started : new Object[]{JSONObject.NULL, "false", 0}) {
            assertFalse(TeamRemoteProduction.jobResourcesReleased(neverStarted().put("processStarted", started)));
        }
        JSONObject actual = neverStarted();
        actual.remove("processStarted");
        assertFalse(TeamRemoteProduction.jobResourcesReleased(actual));
        for (Object notStarted : new Object[]{JSONObject.NULL, false, "true", 1}) {
            assertFalse(TeamRemoteProduction.jobResourcesReleased(neverStarted().put("notStartedConfirmed", notStarted)));
        }
        actual = neverStarted();
        actual.remove("notStartedConfirmed");
        assertFalse(TeamRemoteProduction.jobResourcesReleased(actual));
    }
}
