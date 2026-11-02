package dev.pocketengine.features.teams;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunJournal;
import org.json.JSONObject;
import org.junit.Test;

/** Value/error contracts only. Native owner approval and dispatch are separate host evidence. */
public final class TeamNativeBudgetPauseTest {
    private JSONObject binding()throws Exception {
        return new JSONObject().put("teamRunId","team").put("childTaskId","integrator")
                .put("childRunId","run").put("childEpoch",11).put("memberId","@integrator")
                .put("assignmentId","integrator");
    }

    @Test public void exactPreReservationExhaustionKeepsAll97CallsAndDoesNotCreate98()throws Exception {
        JSONObject pool=TeamParentBudget.create("parent","original-parent-run",97,88,
                new JSONObject().put("nativeProjectWorkPlan",true));
        JSONObject nativeBinding=binding();
        for(int i=0;i<97;i++) {
            TeamParentBudget.reserve(pool,nativeBinding,"integration","physical:MAIN:"+i,1,0);
            TeamParentBudget.startInference(pool,"integrator","run","original-call-"+i);
            TeamParentBudget.finishInference(pool,"original-call-"+i,new JSONObject().put("outcome","COMPLETED")
                    .put("usage",new JSONObject().put("reported",false)));
        }
        String before=TeamWorkFiles.canonical(pool);
        TeamParentBudget.Exhausted stopped=assertThrows(TeamParentBudget.Exhausted.class,
                ()->TeamParentBudget.reserve(pool,nativeBinding,"integration","physical:MAIN:98",1,0));
        assertEquals("modelRequests",stopped.dimension);assertEquals(97,stopped.used);assertEquals(97,stopped.limit);
        assertEquals("parent",stopped.parentTaskId);assertEquals("original-parent-run",stopped.parentRunId);
        assertEquals(before,TeamWorkFiles.canonical(pool));assertEquals(97,pool.getJSONArray("reservations").length());
        assertEquals(0,TeamParentBudget.remaining(pool,"modelRequests"));
        assertThrows("wrong child dedup is a security rejection, not a budget pause",SecurityException.class,
                ()->TeamParentBudget.reserve(pool,new JSONObject(nativeBinding.toString()).put("childTaskId","other"),"integration","physical:MAIN:0",1,0));
    }

    @Test public void pauseDiagnosticsAreDefensiveAndOnlyDescribeThisUndispatchedRequest()throws Exception {
        RunJournal.NativeBudgetPaused pause=new RunJournal.NativeBudgetPaused("child","run",11,"parent","anchor","SUMMARY",97,97);
        JSONObject first=pause.diagnostics();assertFalse(first.getBoolean("thisRequestDispatched"));
        assertTrue(first.getBoolean("priorUsageRetained"));assertFalse(first.getBoolean("autoRefill"));
        assertFalse(first.getBoolean("executionAuthorized"));assertEquals(97,first.getInt("modelRequestsReservedOrUsed"));
        assertFalse("prior effects are not asserted absent",first.has("notDispatchedConfirmed"));
        first.put("modelRequestsReservedOrUsed",0);assertEquals(97,pause.diagnostics().getInt("modelRequestsReservedOrUsed"));
    }

    @Test public void visibleProjectSummaryAllowanceIsAnEstimateWithoutChangingEngineeringCeilings()throws Exception {
        assertEquals(5,TeamParentBudget.estimatedProjectSummaryRequests(2,2));
        assertEquals(4,TeamParentBudget.estimatedProjectSummaryRequests(1,2));
        assertThrows(IllegalArgumentException.class,()->TeamParentBudget.estimatedProjectSummaryRequests(0,2));
        assertThrows(IllegalArgumentException.class,()->TeamParentBudget.estimatedProjectSummaryRequests(5,2));
        assertThrows(IllegalArgumentException.class,()->TeamParentBudget.estimatedProjectSummaryRequests(1,-1));
        assertEquals(128,TeamParentBudget.MAX_PROJECT_MODEL_REQUESTS);assertEquals(128,TeamParentBudget.MAX_PROJECT_TOOL_OPERATIONS);
        assertEquals(64,TeamParentBudget.MAX_MODEL_REQUESTS);assertEquals(32,TeamParentBudget.MAX_TEXT_TOOL_OPERATIONS);
    }
}
