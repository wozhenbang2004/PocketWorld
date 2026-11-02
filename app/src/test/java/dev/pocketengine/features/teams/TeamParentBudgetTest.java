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
}
