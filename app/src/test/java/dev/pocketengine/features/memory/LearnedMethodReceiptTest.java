package dev.pocketengine.features.memory;

import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LearnedMethodReceiptTest {
    private static final String RECORD=UUID.randomUUID().toString();
    private static JSONObject original(String status,boolean claimed)throws Exception{
        String task=UUID.randomUUID().toString(),run=UUID.randomUUID().toString(),op=UUID.randomUUID().toString();
        JSONObject ref=new JSONObject().put("ownerId","owner").put("spaceId","private").put("taskId",task).put("runId",run).put("epoch",2)
                .put("operationId",op).put("planDigest","a".repeat(64)).put("environmentBinding","b".repeat(64))
                .put("installationId","actual-installation").put("workspaceId","original-workspace").put("workspaceRevision","native-snapshot-1").put("maxOutputBytes",4096).put("maxResultChars",8000);
        JSONObject record=new JSONObject().put("recordId",RECORD).put("revision",3).put("ownerId","owner").put("spaceId","private");
        JSONObject result=new JSONObject().put("reference",ref).put("review",new JSONObject().put("package",new JSONObject().put("origin",new JSONObject().put("kind","accepted_procedure").put("ref",record))));
        if(claimed)result.put("claimedAt",1234L);
        if(status!=null)result.put("receipt",new JSONObject().put("taskId",task).put("runId",run).put("epoch",2).put("operationId",op).put("planDigest","a".repeat(64))
                .put("backendReceiptId","original-backend-receipt").put("status",status).put("exitCode",status.equals("completed")?0:JSONObject.NULL)
                .put("output","private output must not enter this audit projection").put("outputSha256","c".repeat(64)).put("outputBytes",17).put("outputTruncated",true));
        return result;
    }
    @Test public void loadedOrNonProcedureIsNeverCountedApplied()throws Exception{
        JSONObject row=original(null,false);assertEquals("not_applied",LearnedMethodReceipt.project(row,RECORD,3,"owner","private").getString("applicationState"));
        row.getJSONObject("review").getJSONObject("package").remove("origin");assertNull(LearnedMethodReceipt.project(row,RECORD,3,"owner","private"));
    }
    @Test public void onlyExactMethodVersionAndAudienceCanMatch()throws Exception{
        JSONObject row=original("completed",true);assertNull(LearnedMethodReceipt.project(row,RECORD,4,"owner","private"));assertNull(LearnedMethodReceipt.project(row,UUID.randomUUID().toString(),3,"owner","private"));assertNull(LearnedMethodReceipt.project(row,RECORD,3,"owner","other"));
    }
    @Test public void terminalBackendAttemptIsNotCausalBusinessSuccessAndContainsNoBody()throws Exception{
        JSONObject row=original("completed",true),view=LearnedMethodReceipt.project(row,RECORD,3,"owner","private");
        assertEquals("applied_backend_attempt",view.getString("applicationState"));assertFalse(view.getBoolean("methodBusinessSuccessVerified"));assertFalse(view.getBoolean("executionAuthorized"));assertFalse(view.getBoolean("replayAllowed"));
        assertFalse(view.has("output"));assertEquals("c".repeat(64),view.getString("outputSha256"));assertTrue(view.getBoolean("outputTruncated"));
    }
    @Test public void acceptedAndRunningRemainTheirActualIntermediateStates()throws Exception{
        assertEquals("backend_accepted",LearnedMethodReceipt.project(original("accepted",true),RECORD,3,"owner","private").getString("applicationState"));
        assertEquals("backend_running",LearnedMethodReceipt.project(original("running",true),RECORD,3,"owner","private").getString("applicationState"));
    }
    @Test public void unknownCancelledAndUnclaimedNeverBecomeSuccessfulApplication()throws Exception{
        assertEquals("application_unknown",LearnedMethodReceipt.project(original("unknown",true),RECORD,3,"owner","private").getString("applicationState"));
        assertEquals("backend_cancelled",LearnedMethodReceipt.project(original("cancelled",true),RECORD,3,"owner","private").getString("applicationState"));
        assertEquals("not_applied",LearnedMethodReceipt.project(original("completed",false),RECORD,3,"owner","private").getString("applicationState"));
    }
    @Test public void ForeignOperationOrPlanReceiptFailsClosed()throws Exception{
        JSONObject row=original("completed",true);row.getJSONObject("receipt").put("operationId",UUID.randomUUID().toString());assertThrows(SecurityException.class,()->LearnedMethodReceipt.project(row,RECORD,3,"owner","private"));
        JSONObject plan=original("completed",true);plan.getJSONObject("receipt").put("planDigest","d".repeat(64));assertThrows(SecurityException.class,()->LearnedMethodReceipt.project(plan,RECORD,3,"owner","private"));
    }
}
