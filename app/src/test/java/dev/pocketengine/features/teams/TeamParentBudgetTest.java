package dev.pocketengine.features.teams;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure value tests. SQLite atomicity/current grant admission is separately a native TaskStore concern. */
public class TeamParentBudgetTest {
    private JSONObject pool(int calls,int tools)throws Exception{return TeamParentBudget.create("parent","parent-run",calls,tools,new JSONObject().put("kind","explicit_fixture_native_grant"));}
    private JSONObject binding(String member,String run)throws Exception{return new JSONObject().put("teamRunId","team").put("childTaskId",member).put("childRunId",run).put("childEpoch",1).put("memberId",member).put("assignmentId",member);}
    @Test public void originalSmallerPoolCoversMembersReviewerAndIntegratorWithoutRefill()throws Exception {
        JSONObject pool=pool(3,1);TeamParentBudget.reserve(pool,binding("writer","w"),"member","w:0",1,0);TeamParentBudget.reserve(pool,binding("reviewer","r"),"review","r:0",1,0);TeamParentBudget.reserve(pool,binding("integrator","i"),"integration","i:0",1,0);
        assertEquals(0,TeamParentBudget.remaining(pool,"modelRequests"));assertThrows(IllegalStateException.class,()->TeamParentBudget.reserve(pool,binding("writer","w"),"member","w:1",1,0));
        TeamParentBudget.reserve(pool,binding("integrator","i"),"integration","op:1",0,1);assertEquals(0,TeamParentBudget.remaining(pool,"toolOperations"));assertEquals(3,pool.getInt("modelRequestLimit"));
    }
    @Test public void onlyProvenUnstartedReservationReleasesAndStartedLateUnknownIsNeverZero()throws Exception {
        JSONObject pool=pool(2,0),writer=binding("writer","w");JSONObject first=TeamParentBudget.reserve(pool,writer,"member","w:0",1,0);TeamParentBudget.releaseUnstarted(pool,"writer","w");assertEquals("released_unstarted",first.getString("state"));assertEquals(2,TeamParentBudget.remaining(pool,"modelRequests"));
        TeamParentBudget.reserve(pool,writer,"member","w:1",1,0);TeamParentBudget.startInference(pool,"writer","w","call-original");TeamParentBudget.releaseUnstarted(pool,"writer","w");assertEquals(1,TeamParentBudget.remaining(pool,"modelRequests"));
        TeamParentBudget.finishInference(pool,"call-original",new JSONObject().put("outcome","CANCELLED").put("usage",new JSONObject().put("reported",false).put("actualCost",JSONObject.NULL)));
        JSONObject settled=pool.getJSONArray("reservations").getJSONObject(1);assertEquals("unknown",settled.getString("state"));assertEquals("call-original",settled.getJSONArray("actualReceiptRefs").getString(0));assertEquals(1,TeamParentBudget.remaining(pool,"modelRequests"));
    }
    @Test public void duplicateReservationIsIdempotentButCannotChangeOriginalChild()throws Exception {
        JSONObject pool=pool(1,0),writer=binding("writer","w");JSONObject a=TeamParentBudget.reserve(pool,writer,"member","w:0",1,0),b=TeamParentBudget.reserve(pool,writer,"member","w:0",1,0);assertSame(a,b);assertEquals(1,pool.getJSONArray("reservations").length());
        assertThrows(SecurityException.class,()->TeamParentBudget.reserve(pool,binding("other","w"),"member","w:0",1,0));
    }
    @Test public void parentAdoptionReplayRetainsExactRunEpochAndUnknownCharge()throws Exception {
        JSONObject pool=pool(0,3),historical=binding("parent","prior-run"),current=binding("parent","parent-run").put("childEpoch",2);
        historical.put("memberId","@parent");current.put("memberId","@parent");
        JSONObject old=TeamParentBudget.reserve(pool,historical,"parent_coordination","operation:prior",0,1);
        old.getJSONArray("operationIds").put("prior");old.put("state","settled");old.getJSONArray("actualReceiptRefs").put("prior");
        JSONObject adopted=TeamParentBudget.reserve(pool,current,"parent_coordination","operation:current",0,1);
        adopted.getJSONArray("operationIds").put("current");adopted.put("state","unknown");
        JSONObject replay=TeamParentBudget.reserve(pool,new JSONObject(current.toString()),"parent_coordination","operation:current",0,1);
        assertSame(adopted,replay);assertEquals(2,pool.getJSONArray("reservations").length());assertEquals(1,TeamParentBudget.remaining(pool,"toolOperations"));
        assertEquals("unknown",replay.getString("state"));assertEquals(1,replay.getJSONArray("operationIds").length());assertEquals(0,replay.getJSONArray("actualReceiptRefs").length());
        assertSame(old,TeamParentBudget.reserve(pool,new JSONObject(historical.toString()),"parent_coordination","operation:prior",0,1));
        assertEquals(1,old.getInt("childEpoch"));assertEquals(2,replay.getInt("childEpoch"));
        JSONObject missing=new JSONObject(current.toString());missing.remove("childEpoch");
        assertThrows(SecurityException.class,()->TeamParentBudget.reserve(pool,missing,"parent_coordination","operation:current",0,1));
        assertThrows(SecurityException.class,()->TeamParentBudget.reserve(pool,new JSONObject(current.toString()).put("childEpoch",3),"parent_coordination","operation:current",0,1));
        assertThrows(SecurityException.class,()->TeamParentBudget.reserve(pool,new JSONObject(current.toString()).put("childRunId","other-run"),"parent_coordination","operation:current",0,1));
        assertThrows(SecurityException.class,()->TeamParentBudget.reserve(pool,new JSONObject(historical.toString()).put("childEpoch",2),"parent_coordination","operation:prior",0,1));
        assertEquals(1,TeamParentBudget.remaining(pool,"toolOperations"));assertEquals(2,pool.getJSONArray("reservations").length());
    }
    @Test public void sharedTransactionContractAdmitsOnlyOneConcurrentLastUnit()throws Exception {
        JSONObject pool=pool(1,0);CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);AtomicInteger admitted=new AtomicInteger();
        Runnable claim=()->{ready.countDown();try{go.await();synchronized(pool){TeamParentBudget.reserve(pool,binding(Thread.currentThread().getName(),"run"),"member",Thread.currentThread().getName(),1,0);admitted.incrementAndGet();}}catch(IllegalStateException exhausted){}catch(Exception failure){throw new RuntimeException(failure);}};
        Thread a=new Thread(claim,"a"),b=new Thread(claim,"b");a.start();b.start();ready.await();go.countDown();a.join();b.join();assertEquals(1,admitted.get());assertEquals(0,TeamParentBudget.remaining(pool,"modelRequests"));
    }
    @Test public void actualProviderCountersSettleWithoutPretendingMoneyCap()throws Exception {
        JSONObject pool=pool(1,0);TeamParentBudget.reserve(pool,binding("r","run"),"review","r:0",1,0);TeamParentBudget.startInference(pool,"r","run","call");JSONObject counters=new JSONObject().put("INPUT",new JSONObject().put("value",10)).put("OUTPUT",new JSONObject().put("value",4));
        TeamParentBudget.finishInference(pool,"call",new JSONObject().put("outcome","COMPLETED").put("usage",new JSONObject().put("reported",true).put("counters",counters).put("actualCost",JSONObject.NULL)));assertEquals("settled",pool.getJSONArray("reservations").getJSONObject(0).getString("state"));assertFalse(pool.getBoolean("costLimitKnown"));assertEquals(0,TeamParentBudget.remaining(pool,"modelRequests"));
    }
    private JSONObject research(String member,String run)throws Exception{return binding(member,run).put("runGeneration",2).put("memberGeneration",3).put("round",0).put("sourceExecution",new JSONObject().put("executionTurnId","turn-"+member));}
    private JSONObject context(JSONObject binding)throws Exception{return new JSONObject().put("taskId",binding.getString("childTaskId")).put("runId",binding.getString("childRunId")).put("epoch",binding.getInt("childEpoch")).put("memberId",binding.getString("memberId")).put("purpose","MEMBER").put("bindingKind","HOST_RUN");}
    private JSONObject route(String logical,int attempt,String candidate)throws Exception{return new JSONObject().put("logicalRequestId",logical).put("attemptNo",attempt).put("candidateId",candidate);}
    @Test public void logicalAttemptsUseTwoExactReservationsAndStartedAloneAddsPhysicalCall()throws Exception {
        JSONObject pool=pool(2,0),who=research("a","run"),a=TeamParentBudget.reserve(pool,who,"member","logical:1",1,0);
        a.put("logicalRequestId","logical");String first=a.getString("reservationId");
        assertSame(a,TeamParentBudget.bindLogicalAttempt(pool,first,who,"logical",1,"A","call-A"));
        assertSame(a,TeamParentBudget.bindLogicalAttempt(pool,first,who,"logical",1,"A","call-A"));assertEquals(0,a.getJSONArray("inferenceCallIds").length());
        TeamParentBudget.startInferenceAttempt(pool,first,context(who),route("logical",1,"A"),"call-A");
        TeamParentBudget.finishInference(pool,"call-A",new JSONObject().put("outcome","FAILED").put("usage",new JSONObject().put("reported",false)));
        JSONObject b=TeamParentBudget.reserve(pool,who,"member","logical:2",1,0).put("logicalRequestId","logical").put("attemptNo",2).put("candidateId","B");
        TeamParentBudget.bindLogicalAttempt(pool,b.getString("reservationId"),who,"logical",2,"B","call-B");
        TeamParentBudget.startInferenceAttempt(pool,b.getString("reservationId"),context(who),route("logical",2,"B"),"call-B");
        assertEquals("unknown",a.getString("state"));assertEquals("started",b.getString("state"));assertEquals(0,TeamParentBudget.remaining(pool,"modelRequests"));
        assertEquals("call-A",a.getJSONArray("actualReceiptRefs").getString(0));assertEquals("call-B",b.getJSONArray("inferenceCallIds").getString(0));
        assertSame(a,TeamParentBudget.startInferenceAttempt(pool,first,context(who),route("logical",1,"A"),"call-A"));assertEquals("unknown",a.getString("state"));
        assertThrows(SecurityException.class,()->TeamParentBudget.releaseInferenceAttemptUnstarted(pool,first,"logical","call-A"));assertEquals(0,TeamParentBudget.remaining(pool,"modelRequests"));
    }
    @Test public void concurrentLogicalCallsInOneChildCannotBorrowEachOthersReservations()throws Exception {
        JSONObject pool=pool(3,0),who=research("a","run"),a=TeamParentBudget.reserve(pool,who,"member","one:1",1,0),b=TeamParentBudget.reserve(pool,who,"member","two:1",1,0);
        String first=a.getString("reservationId"),second=b.getString("reservationId");
        TeamParentBudget.bindLogicalAttempt(pool,first,who,"one",1,"A","call-one");TeamParentBudget.bindLogicalAttempt(pool,second,who,"two",1,"A","call-two");
        assertThrows(SecurityException.class,()->TeamParentBudget.startInferenceAttempt(pool,second,context(who),route("one",1,"A"),"call-one"));
        assertThrows(SecurityException.class,()->TeamParentBudget.startInferenceAttempt(pool,first,context(who),route("one",2,"A"),"call-one"));
        assertThrows(SecurityException.class,()->TeamParentBudget.startInferenceAttempt(pool,first,context(who),route("one",1,"B"),"call-one"));
        assertThrows(SecurityException.class,()->TeamParentBudget.startInferenceAttempt(pool,first,context(who).put("epoch",2),route("one",1,"A"),"call-one"));
        assertThrows(SecurityException.class,()->TeamParentBudget.bindLogicalAttempt(pool,second,who,"one",1,"A","call-one"));
        JSONObject spare=TeamParentBudget.reserve(pool,who,"member","spare",1,0);String extra=spare.getString("reservationId");
        assertThrows(SecurityException.class,()->TeamParentBudget.bindLogicalAttempt(pool,extra,who,"one",1,"A","third-call"));
        assertFalse(spare.has("logicalRequestId"));assertEquals(0,a.getJSONArray("inferenceCallIds").length());assertEquals(0,b.getJSONArray("inferenceCallIds").length());
    }
    @Test public void everyOriginalResearchBindingAndPreboundTupleMustStayExact()throws Exception {
        JSONObject pool=pool(1,0),who=research("a","run"),row=TeamParentBudget.reserve(pool,who,"member","logical:2",1,0).put("logicalRequestId","logical").put("attemptNo",2).put("candidateId","B");String id=row.getString("reservationId");
        for(String field:new String[]{"teamRunId","childTaskId","childRunId","childEpoch","memberId","assignmentId","runGeneration","memberGeneration","round","sourceExecution"}){
            JSONObject changed=new JSONObject(who.toString());changed.remove(field);assertThrows(field,SecurityException.class,()->TeamParentBudget.bindLogicalAttempt(pool,id,changed,"logical",2,"B","call"));
        }
        assertThrows(SecurityException.class,()->TeamParentBudget.bindLogicalAttempt(pool,id,who,"other",2,"B","call"));
        assertThrows(SecurityException.class,()->TeamParentBudget.bindLogicalAttempt(pool,id,who,"logical",1,"B","call"));
        assertThrows(SecurityException.class,()->TeamParentBudget.bindLogicalAttempt(pool,id,who,"logical",2,"C","call"));assertFalse(row.has("plannedCallId"));
        TeamParentBudget.bindLogicalAttempt(pool,id,who,"logical",2,"B","call");assertThrows(SecurityException.class,()->TeamParentBudget.bindLogicalAttempt(pool,id,who,"logical",2,"B","replacement"));
    }
    @Test public void onlyExactUnstartedPlannedOrNotYetCapturedAttemptCanRelease()throws Exception {
        JSONObject pool=pool(3,0),who=research("a","run"),unmade=TeamParentBudget.reserve(pool,who,"member","logical:2",1,0).put("logicalRequestId","logical").put("attemptNo",2).put("candidateId","B");String id=unmade.getString("reservationId");
        assertThrows(SecurityException.class,()->TeamParentBudget.releaseInferenceAttemptUnstarted(pool,id,"other",""));assertThrows(SecurityException.class,()->TeamParentBudget.releaseInferenceAttemptUnstarted(pool,id,"logical","invented"));
        unmade.put("plannedCallId",JSONObject.NULL);assertThrows(SecurityException.class,()->TeamParentBudget.releaseInferenceAttemptUnstarted(pool,id,"logical",""));unmade.remove("plannedCallId");
        TeamParentBudget.releaseInferenceAttemptUnstarted(pool,id,"logical","");assertEquals("released_unstarted",unmade.getString("state"));assertEquals(3,TeamParentBudget.remaining(pool,"modelRequests"));
        assertThrows(SecurityException.class,()->TeamParentBudget.releaseInferenceAttemptUnstarted(pool,id,"logical",""));assertEquals(3,TeamParentBudget.remaining(pool,"modelRequests"));
        JSONObject planned=TeamParentBudget.reserve(pool,who,"member","next:1",1,0);String next=planned.getString("reservationId");TeamParentBudget.bindLogicalAttempt(pool,next,who,"next",1,"A","call");
        assertThrows(SecurityException.class,()->TeamParentBudget.releaseInferenceAttemptUnstarted(pool,next,"next",""));TeamParentBudget.releaseInferenceAttemptUnstarted(pool,next,"next","call");assertEquals(3,TeamParentBudget.remaining(pool,"modelRequests"));
        JSONObject observed=TeamParentBudget.reserve(pool,who,"member","seen:1",1,0);String seen=observed.getString("reservationId");TeamParentBudget.bindLogicalAttempt(pool,seen,who,"seen",1,"A","seen-call");observed.getJSONArray("operationIds").put("actual-operation");
        assertThrows(SecurityException.class,()->TeamParentBudget.releaseInferenceAttemptUnstarted(pool,seen,"seen","seen-call"));assertEquals(2,TeamParentBudget.remaining(pool,"modelRequests"));
    }
}
