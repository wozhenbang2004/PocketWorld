package dev.pocketengine.store;

import static org.junit.Assert.*;
import dev.pocketengine.runtime.usage.InferenceFailure;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class TeamResearchRetryTest {
    private static final long NOW=10_000L;
    private record Evidence(JSONObject logical,JSONObject attempt,JSONObject receipt) {
        boolean allows(long wait){return TeamResearchRetry.definiteFailure(logical,attempt,receipt,wait,NOW);}
    }
    private static Evidence evidence(InferenceFailure failure)throws Exception {
        JSONObject attempt=new JSONObject().put("callId","call-a").put("receiptRef","call-a").put("attemptNo",1)
                .put("candidateId","a").put("attemptState","failed").put("outcome","FAILED").put("dispatchState","may_have_occurred");
        JSONObject logical=new JSONObject().put("logicalRequestId","logical-original").put("taskId","child").put("runId","run").put("epoch",4)
                .put("deadlineAt",NOW+10_000).put("semanticCommitted",false).put("chosenCallId",JSONObject.NULL)
                .put("routeSnapshot",new JSONObject().put("contextPurpose","MEMBER").put("contextMemberId","reviewer"))
                .put("control",new JSONObject().put("maxAttempts",2).put("maxRouteSwitches",1).put("attempts",new JSONArray().put(attempt)));
        JSONObject receipt=new JSONObject().put("callId","call-a").put("outcome","FAILED").put("finishedAt",NOW-100)
                .put("context",new JSONObject().put("taskId","child").put("runId","run").put("epoch",4).put("purpose","MEMBER").put("memberId","reviewer"))
                .put("route",new JSONObject().put("logicalRequestId","logical-original").put("attemptNo",1).put("candidateId","a"))
                .put("failure",failure.toJson()).put("usage",new JSONObject().put("reported",false)).put("cost",JSONObject.NULL);
        return new Evidence(logical,attempt,receipt);
    }
    private static InferenceFailure http(int status)throws Exception{return InferenceFailure.http(status,List.of(),List.of(),NOW-200);}

    @Test public void definiteTransientHttpRetainsUnknownBillingAndAllowsOneFallback()throws Exception {
        for(int status:new int[]{500,502,503,504}){
            Evidence e=evidence(http(status));String receiptBefore=e.receipt.toString();assertTrue(e.allows(500));
            assertEquals(receiptBefore,e.receipt.toString());assertFalse(e.receipt.getJSONObject("usage").getBoolean("reported"));assertTrue(e.receipt.isNull("cost"));
        }
    }
    @Test public void authQuotaPolicyAndUnclassified429NeverSwitch()throws Exception {
        for(int status:new int[]{400,401,402,403,404,408,409,429,501,505})assertFalse(evidence(http(status)).allows(500));
        for(String code:new String[]{"insufficient_quota","quota_exceeded","billing_hard_limit_reached","content_policy_violation","safety_policy_violation"}){
            String body=new JSONObject().put("error",new JSONObject().put("code",code)).toString();
            assertFalse(evidence(InferenceFailure.classifiedHttp(503,List.of(),List.of(),NOW-200,body)).allows(500));
        }
    }
    @Test public void completionUnknownNetworkAndIncompleteStreamNeverSwitch()throws Exception {
        for(InferenceFailure.Kind kind:new InferenceFailure.Kind[]{InferenceFailure.Kind.NETWORK,InferenceFailure.Kind.TIMEOUT,InferenceFailure.Kind.INCOMPLETE_STREAM,InferenceFailure.Kind.PROTOCOL,InferenceFailure.Kind.UNKNOWN,InferenceFailure.Kind.CANCELLED,InferenceFailure.Kind.LOCAL_REFUSAL})
            assertFalse(evidence(InferenceFailure.local(kind,NOW-200)).allows(500));
        assertFalse(evidence(InferenceFailure.local(InferenceFailure.Kind.NETWORK,503,NOW-200)).allows(500));
    }
    @Test public void anyPartialTextSemanticCommitOrToolCommitStopsFallback()throws Exception {
        assertFalse(evidence(http(503).withPartialText(true)).allows(500));
        Evidence semantic=evidence(http(503));semantic.logical.put("semanticCommitted",true).put("chosenCallId","call-a");assertFalse(semantic.allows(500));
        Evidence partial=evidence(http(503));partial.attempt.put("attemptState","partial");assertFalse(partial.allows(500));
        Evidence chosen=evidence(http(503));chosen.logical.put("chosenCallId","call-a");assertFalse(chosen.allows(500));
    }
    @Test public void onlyOriginalFirstDispatchedFailedReceiptQualifies()throws Exception {
        for(String key:new String[]{"receiptRef","callId","candidateId","dispatchState","outcome","attemptState"}){
            Evidence e=evidence(http(503));e.attempt.put(key,"different");assertFalse(key,e.allows(500));
        }
        Evidence e=evidence(http(503));e.receipt.put("outcome","STARTED");assertFalse(e.allows(500));
        e=evidence(http(503));e.receipt.put("finishedAt",JSONObject.NULL);assertFalse(e.allows(500));
        e=evidence(http(503));e.logical.getJSONObject("control").getJSONArray("attempts").put(new JSONObject());assertFalse(e.allows(500));
        e=evidence(http(503));e.attempt.put("attemptNo",2);assertFalse(e.allows(500));
    }
    @Test public void anotherTaskRunEpochMemberPurposeLogicalOrCandidateCannotBorrowFailure()throws Exception {
        for(String key:new String[]{"taskId","runId","memberId","purpose"}){Evidence e=evidence(http(503));e.receipt.getJSONObject("context").put(key,"other");assertFalse(key,e.allows(500));}
        Evidence e=evidence(http(503));e.receipt.getJSONObject("context").put("epoch",5);assertFalse(e.allows(500));
        for(String key:new String[]{"logicalRequestId","candidateId"}){e=evidence(http(503));e.receipt.getJSONObject("route").put(key,"other");assertFalse(key,e.allows(500));}
        e=evidence(http(503));e.receipt.getJSONObject("route").put("attemptNo",2);assertFalse(e.allows(500));
    }
    @Test public void oldSingleAttemptOrNoSwitchPlanCannotGainAllowance()throws Exception {
        Evidence e=evidence(http(503));e.logical.getJSONObject("control").put("maxAttempts",1);assertFalse(e.allows(500));
        e=evidence(http(503));e.logical.getJSONObject("control").put("maxRouteSwitches",0);assertFalse(e.allows(500));
        e=evidence(http(503));e.logical.getJSONObject("control").put("maxAttempts",3);assertFalse(e.allows(500));
    }
    @Test public void deadlineAndProviderWaitStayWithinOriginalAbsoluteDeadline()throws Exception {
        Evidence e=evidence(http(503));assertFalse(e.allows(-1));assertFalse(e.allows(10_000));
        e.logical.put("deadlineAt",NOW);assertFalse(e.allows(0));
        e=evidence(InferenceFailure.http(503,List.of("2"),List.of(),NOW-200));assertFalse(e.allows(500));assertTrue(e.allows(1800));
        e=evidence(InferenceFailure.http(503,List.of("1","2"),List.of(),NOW-200));assertFalse(e.allows(500));
    }
    @Test public void malformedOrInventedFailureFactsAreNotUpgraded()throws Exception {
        Evidence e=evidence(http(503));e.receipt.getJSONObject("failure").put("automaticRetry",true);assertFalse(e.allows(500));
        e=evidence(http(503));e.receipt.getJSONObject("failure").put("partialTextReceived","false");assertFalse(e.allows(500));
        e=evidence(http(503));e.receipt.getJSONObject("failure").put("unknownExtraClaim",true);assertFalse(e.allows(500));
        e=evidence(http(503));e.receipt.remove("failure");assertFalse(e.allows(500));
        assertFalse(TeamResearchRetry.definiteFailure(null,null,null,500,NOW));
    }
}
