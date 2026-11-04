package dev.pocketengine.store;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TaskExecutionScopeTest {
    private static final String TASK="41fc357e-f140-4ced-acf0-f1d8f874d1de";
    private static final String RUN="517f2c04-4d8b-4737-9bae-f17c362d875b";
    private static final String ARTIFACT="f7038c83-2b03-40c0-a9c3-e4f15b6a260e";
    private static final String HASH="a1".repeat(32);

    private static JSONObject scope(long revision)throws Exception {
        return TaskExecutionScope.create(TASK,"capabilities",revision,0,12,20,3,4,new JSONArray().put("project_read_file").put("project_execute"),true);
    }
    private static JSONObject draft(long scopeRevision,long planRevision)throws Exception {
        return scope(scopeRevision).put("executionPhase","planning").put("planRevision",planRevision)
                .put("planArtifactRef",planRevision==0?"":ARTIFACT).put("planHash",planRevision==0?"":HASH);
    }
    private static JSONObject approval(JSONObject scope)throws Exception {
        return new JSONObject().put("format","pocketworld.execution-plan-approval.v1")
                .put("approvalId","46e08f64-bb94-488a-b2bb-380c83e32ecb").put("taskId",TASK).put("runId",RUN).put("epoch",2)
                .put("scopeRevision",scope.getLong("revision")).put("planRevision",scope.getLong("planRevision"))
                .put("planArtifactRef",scope.getString("planArtifactRef")).put("planHash",scope.getString("planHash")).put("approvedAt",0L);
    }
    private static JSONObject approved()throws Exception {
        JSONObject scope=draft(7,3);return scope.put("executionPhase","executing").put("planApproval",approval(scope));
    }

    @Test public void legacyScopesStayExecutingWithoutAddingFieldsOrChangingBudgets()throws Exception {
        JSONObject old=scope(1),next=scope(2);String before=next.toString();
        assertFalse(TaskExecutionScope.planning(old));assertEquals(Long.MAX_VALUE,TaskExecutionScope.effectiveDeadline(old));
        TaskExecutionScope.copyPlanning(old,next);assertEquals(before,next.toString());
        for(String key:new String[]{"executionPhase","planRevision","planArtifactRef","planHash","planApproval"})assertFalse(next.has(key));
    }

    @Test public void anyPlanningFieldRequiresTheEntireNewContract()throws Exception {
        for(String key:new String[]{"executionPhase","planRevision","planArtifactRef","planHash","planApproval"}){
            JSONObject partial=scope(1).put(key,key.equals("planRevision")?0:"planning");
            assertThrows(Exception.class,()->TaskExecutionScope.planning(partial));
        }
        for(String key:new String[]{"executionPhase","planRevision","planArtifactRef","planHash"}){
            JSONObject partial=draft(1,1);partial.remove(key);assertThrows(Exception.class,()->TaskExecutionScope.effectiveDeadline(partial));
        }
    }

    @Test public void zeroRevisionIsOnlyAnEmptyUnsubmittedPlanningDraft()throws Exception {
        JSONObject empty=draft(1,0);assertTrue(TaskExecutionScope.planning(empty));
        assertEquals(Long.MAX_VALUE,TaskExecutionScope.effectiveDeadline(empty));
        assertThrows(SecurityException.class,()->TaskExecutionScope.validatePlanning(draft(1,0).put("planArtifactRef",ARTIFACT)));
        assertThrows(SecurityException.class,()->TaskExecutionScope.validatePlanning(draft(1,0).put("planHash",HASH)));
        empty.put("executionPhase","executing").put("planApproval",approval(empty));
        assertThrows(SecurityException.class,()->TaskExecutionScope.validatePlanning(empty));
    }

    @Test public void planRevisionCannotCoerceStringsFloatingPointOrNegativeValues()throws Exception {
        for(Object value:new Object[]{"3",3.0d,3.5d,-1L,JSONObject.NULL,Boolean.TRUE}){
            JSONObject scope=draft(1,1).put("planRevision",value);
            assertThrows(Exception.class,()->TaskExecutionScope.validatePlanning(scope));
        }
        assertTrue(TaskExecutionScope.planning(draft(1,Long.MAX_VALUE)));
    }

    @Test public void submittedDraftNeedsExactReferenceHashAndKnownPhase()throws Exception {
        for(String ref:new String[]{"","1-1-1-1-1","x".repeat(36),ARTIFACT+"/plan.json"})
            assertThrows(SecurityException.class,()->TaskExecutionScope.validatePlanning(draft(1,1).put("planArtifactRef",ref)));
        for(String hash:new String[]{"","a".repeat(63),"g".repeat(64),HASH+"x"})
            assertThrows(SecurityException.class,()->TaskExecutionScope.validatePlanning(draft(1,1).put("planHash",hash)));
        for(Object phase:new Object[]{"", "approved", "PLANNING", true, JSONObject.NULL})
            assertThrows(SecurityException.class,()->TaskExecutionScope.validatePlanning(draft(1,1).put("executionPhase",phase)));
    }

    @Test public void planningRejectsEvenNullOrOtherwiseValidApproval()throws Exception {
        JSONObject scope=draft(1,1);scope.put("planApproval",approval(scope));
        assertThrows(SecurityException.class,()->TaskExecutionScope.validatePlanning(scope));
        scope.put("planApproval",JSONObject.NULL);assertThrows(SecurityException.class,()->TaskExecutionScope.validatePlanning(scope));
    }

    @Test public void executingRequiresCompleteMatchingOwnerAttestation()throws Exception {
        JSONObject scope=approved();assertFalse(TaskExecutionScope.planning(scope));assertEquals(Long.MAX_VALUE,TaskExecutionScope.effectiveDeadline(scope));
        scope.remove("planApproval");assertThrows(SecurityException.class,()->TaskExecutionScope.validatePlanning(scope));
        scope.put("planApproval",JSONObject.NULL);assertThrows(SecurityException.class,()->TaskExecutionScope.validatePlanning(scope));
    }

    @Test public void changedScopePlanArtifactOrTaskCannotReuseOldApproval()throws Exception {
        String[] keys={"scopeRevision","planRevision","planArtifactRef","planHash","taskId","format"};
        Object[] values={8L,4L,"39991352-78e9-463e-8e1e-17aa5a4329a9","b2".repeat(32),"another-task","pocketworld.other.v1"};
        for(int i=0;i<keys.length;i++){
            JSONObject scope=approved();scope.getJSONObject("planApproval").put(keys[i],values[i]);
            assertThrows(SecurityException.class,()->TaskExecutionScope.validatePlanning(scope));
        }
    }

    @Test public void approvalHasNoUnknownMissingOrCoercedFields()throws Exception {
        JSONObject baseline=approved().getJSONObject("planApproval");
        for(java.util.Iterator<String> keys=baseline.keys();keys.hasNext();){String key=keys.next();
            JSONObject scope=approved();scope.getJSONObject("planApproval").remove(key);
            assertThrows(Exception.class,()->TaskExecutionScope.validatePlanning(scope));
        }
        JSONObject unknown=approved();unknown.getJSONObject("planApproval").put("approved",true);
        assertThrows(SecurityException.class,()->TaskExecutionScope.validatePlanning(unknown));
        for(Object epoch:new Object[]{0,-1,2147483648L,2.0d,"2",JSONObject.NULL}){
            JSONObject scope=approved();scope.getJSONObject("planApproval").put("epoch",epoch);
            assertThrows(Exception.class,()->TaskExecutionScope.validatePlanning(scope));
        }
        for(Object timestamp:new Object[]{-1L,1.0d,"0",JSONObject.NULL}){
            JSONObject scope=approved();scope.getJSONObject("planApproval").put("approvedAt",timestamp);
            assertThrows(Exception.class,()->TaskExecutionScope.validatePlanning(scope));
        }
        JSONObject emptyRun=approved();emptyRun.getJSONObject("planApproval").put("runId"," ");
        assertThrows(SecurityException.class,()->TaskExecutionScope.validatePlanning(emptyRun));
    }

    @Test public void sameRevisionCopiesApprovalWithoutSharingMutableObjects()throws Exception {
        JSONObject old=approved(),next=scope(7);TaskExecutionScope.copyPlanning(old,next);
        assertFalse(TaskExecutionScope.planning(next));assertEquals(old.getJSONObject("planApproval").toString(),next.getJSONObject("planApproval").toString());
        next.getJSONObject("planApproval").put("approvedAt",123L);assertEquals(0L,old.getJSONObject("planApproval").getLong("approvedAt"));
        assertEquals(3,next.getInt("toolsUsed"));assertEquals(4,next.getInt("modelCallsUsed"));assertEquals(12,next.getInt("toolLimit"));assertEquals(20,next.getInt("modelCallLimit"));
    }

    @Test public void renewalKeepsDraftAndOriginalUsageButInvalidatesApproval()throws Exception {
        JSONObject old=approved(),next=scope(8).put("toolLimit",15).put("modelCallLimit",25);String beforeTools=next.getJSONArray("tools").toString();
        TaskExecutionScope.copyPlanning(old,next);
        assertTrue(TaskExecutionScope.planning(next));assertFalse(next.has("planApproval"));assertEquals(3,next.getLong("planRevision"));
        assertEquals(ARTIFACT,next.getString("planArtifactRef"));assertEquals(HASH,next.getString("planHash"));
        assertEquals(3,next.getInt("toolsUsed"));assertEquals(4,next.getInt("modelCallsUsed"));assertEquals(15,next.getInt("toolLimit"));assertEquals(25,next.getInt("modelCallLimit"));
        assertEquals(0,next.getLong("deadlineAt"));assertEquals(beforeTools,next.getJSONArray("tools").toString());assertTrue(next.getBoolean("toolsPinned"));
        assertFalse(TaskExecutionScope.planning(old));assertTrue(old.has("planApproval"));
    }

    @Test public void invalidCopyCannotMutateTheTargetOrCrossTaskIdentity()throws Exception {
        JSONObject old=approved(),next=scope(6);String unchanged=next.toString();
        assertThrows(SecurityException.class,()->TaskExecutionScope.copyPlanning(old,next));assertEquals(unchanged,next.toString());
        JSONObject foreign=scope(8).put("taskId","another-task");
        assertThrows(SecurityException.class,()->TaskExecutionScope.copyPlanning(old,foreign));assertFalse(foreign.has("executionPhase"));
        JSONObject injected=draft(2,0);
        assertThrows(SecurityException.class,()->TaskExecutionScope.copyPlanning(scope(1),injected));
    }

    @Test public void nextRevisionNeverWrapsOrAcceptsNegativeState(){
        assertEquals(1,TaskExecutionScope.nextRevision(0));assertEquals(Long.MAX_VALUE,TaskExecutionScope.nextRevision(Long.MAX_VALUE-1));
        assertThrows(SecurityException.class,()->TaskExecutionScope.nextRevision(Long.MAX_VALUE));
        assertThrows(SecurityException.class,()->TaskExecutionScope.nextRevision(-1));
    }
}
