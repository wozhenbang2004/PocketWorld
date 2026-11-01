package dev.pocketengine.features.remote;

import dev.pocketengine.features.teams.TeamParentBudget;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original pool value transitions, not SQL admission; native journey tests cover the latter. */
public final class RemoteExternalJobBudgetTest {
    private String id(){return UUID.randomUUID().toString();}
    private JSONObject pool()throws Exception {JSONObject value=TeamParentBudget.create(id(),id(),3,8,new JSONObject());TeamParentBudget.initializeExternalJobs(value,new JSONObject().put("externalTurnLimit",2).put("externalTurnsUsed",1));return value;}
    private JSONObject bind()throws Exception{return new JSONObject().put("teamRunId",id()).put("childTaskId",id()).put("childRunId",id()).put("memberId","writer").put("assignmentId",id());}
    private JSONObject actual(String op,String reservation,String status,boolean started,boolean fence,boolean never)throws Exception{return new JSONObject().put("id",id()).put("operationId",op).put("status",status).put("assignmentWorkspace",new JSONObject().put("reservationId",reservation)).put("processStarted",started).put("dispatchFenceFinal",fence).put("notStartedConfirmed",never);}
    @Test public void jobIsNotOneModelCallAndUnknownCancelNeverRefunds()throws Exception {
        JSONObject pool=pool();String op=id(),reservation=id();JSONObject row=TeamParentBudget.reserveExternalJob(pool,bind(),id(),op,reservation,new JSONObject());assertEquals(3,TeamParentBudget.remaining(pool,"modelRequests"));assertEquals(8,TeamParentBudget.remaining(pool,"toolOperations"));assertEquals(0,TeamParentBudget.remaining(pool,"externalJobs"));TeamParentBudget.startExternalJob(pool,reservation,op);
        TeamParentBudget.finishExternalJob(pool,reservation,op,actual(op,reservation,"unknown",false,false,false),false);assertEquals("unknown",row.getString("state"));assertEquals(0,TeamParentBudget.remaining(pool,"externalJobs"));assertTrue(row.isNull("cost"));assertTrue(row.isNull("inputTokens"));
    }
    @Test public void allocatedJobCanReleaseOnlyOnFinalExactNeverStartedProof()throws Exception {
        JSONObject pool=pool();String op=id(),reservation=id();JSONObject row=TeamParentBudget.reserveExternalJob(pool,bind(),id(),op,reservation,new JSONObject()),queued=actual(op,reservation,"queued",false,false,false);TeamParentBudget.startExternalJob(pool,reservation,op);TeamParentBudget.finishExternalJob(pool,reservation,op,queued,false);assertThrows(SecurityException.class,()->TeamParentBudget.finishExternalJob(pool,reservation,op,queued,true));
        JSONObject finalReceipt=new JSONObject(queued.toString()).put("status","cancelled").put("dispatchFenceFinal",true).put("notStartedConfirmed",true);TeamParentBudget.finishExternalJob(pool,reservation,op,finalReceipt,true);assertEquals("released_unstarted",row.getString("state"));assertEquals(1,TeamParentBudget.remaining(pool,"externalJobs"));assertThrows(SecurityException.class,()->TeamParentBudget.finishExternalJob(pool,reservation,op,new JSONObject(finalReceipt.toString()).put("processStarted",true).put("notStartedConfirmed",false),false));
    }
    @Test public void finalSettlementCannotRegressFromLateRunningOrUnknownAndStartedNeverBecomesRefund()throws Exception {
        JSONObject pool=pool();String op=id(),reservation=id();JSONObject row=TeamParentBudget.reserveExternalJob(pool,bind(),id(),op,reservation,new JSONObject()),done=actual(op,reservation,"completed",true,true,false);TeamParentBudget.startExternalJob(pool,reservation,op);TeamParentBudget.finishExternalJob(pool,reservation,op,done,false);String original=row.toString();TeamParentBudget.finishExternalJob(pool,reservation,op,new JSONObject(done.toString()).put("status","running"),false);assertEquals("settled",row.getString("state"));assertEquals(0,TeamParentBudget.remaining(pool,"externalJobs"));assertThrows(SecurityException.class,()->TeamParentBudget.finishExternalJob(pool,reservation,op,new JSONObject(done.toString()).put("processStarted",false).put("notStartedConfirmed",true),true));
    }
    @Test public void activeStartedProofIsMonotonicEvenBeforeTerminal()throws Exception {
        JSONObject pool=pool();String op=id(),reservation=id();JSONObject row=TeamParentBudget.reserveExternalJob(pool,bind(),id(),op,reservation,new JSONObject()),running=actual(op,reservation,"running",true,false,false);TeamParentBudget.startExternalJob(pool,reservation,op);TeamParentBudget.finishExternalJob(pool,reservation,op,running,false);assertThrows(SecurityException.class,()->TeamParentBudget.finishExternalJob(pool,reservation,op,new JSONObject(running.toString()).put("status","blocked").put("processStarted",false).put("dispatchFenceFinal",true).put("notStartedConfirmed",true),true));assertEquals(0,TeamParentBudget.remaining(pool,"externalJobs"));
    }
    @Test public void hardTokenAndCostCapsAreCapabilityMismatchNotZeroUsage()throws Exception {JSONObject limits=new JSONObject().put("maxWallTimeSeconds",30).put("maxOutputBytes",4096).put("maxContributionBytes",8192);RemoteAssignmentWorkspaces.validateLimits(limits);assertThrows(SecurityException.class,()->RemoteAssignmentWorkspaces.validateLimits(new JSONObject(limits.toString()).put("hardCostLimit",1)));assertThrows(SecurityException.class,()->RemoteAssignmentWorkspaces.validateLimits(new JSONObject(limits.toString()).put("hardTokenLimit",100)));}
}
