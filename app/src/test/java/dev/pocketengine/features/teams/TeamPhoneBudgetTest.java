package dev.pocketengine.features.teams;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exact value operations used by native phone allocations in the original parent pool. */
public class TeamPhoneBudgetTest {
    private static JSONObject source()throws Exception{return new JSONObject().put("nativePhoneWorkPlan",true);}
    private static JSONObject child(String id,String run,int epoch)throws Exception{return new JSONObject().put("teamRunId","team").put("childTaskId",id).put("childRunId",run).put("childEpoch",epoch).put("memberId",id).put("assignmentId",id);}
    @Test public void nativePhonePoolHasBoundedDistinctLimits()throws Exception {
        JSONObject pool=TeamParentBudget.create("team","team",128,400,source());assertEquals(128,TeamParentBudget.modelCeiling(pool));assertEquals(400,TeamParentBudget.toolCeiling(pool));assertEquals(560,TeamParentBudget.reservationCeiling(pool));
        assertThrows(SecurityException.class,()->TeamParentBudget.create("team","team",129,400,source()));assertThrows(SecurityException.class,()->TeamParentBudget.create("team","team",128,401,source()));
    }
    @Test public void ordinaryResearchPoolDoesNotGainPhoneBudget()throws Exception {
        assertThrows(SecurityException.class,()->TeamParentBudget.create("team","team",65,32,new JSONObject()));assertThrows(SecurityException.class,()->TeamParentBudget.create("team","team",64,33,new JSONObject()));
    }
    @Test public void twoPhoneChildrenConsumeOneParentPool()throws Exception {
        JSONObject pool=TeamParentBudget.create("team","team",2,2,source());TeamParentBudget.reserve(pool,child("one","run1",1),"member","one-model",1,0);TeamParentBudget.reserve(pool,child("two","run2",1),"member","two-model",1,0);
        assertThrows(TeamParentBudget.Exhausted.class,()->TeamParentBudget.reserve(pool,child("two","run2",1),"member","third-model",1,0));assertEquals(2,pool.getJSONArray("reservations").length());
    }
    @Test public void originalAllocationIsIdempotentButCannotMoveToAnotherRun()throws Exception {
        JSONObject pool=TeamParentBudget.create("team","team",1,1,source());JSONObject first=TeamParentBudget.reserve(pool,child("one","run1",1),"member","allocation",0,1);JSONObject same=TeamParentBudget.reserve(pool,child("one","run1",1),"member","allocation",0,1);assertEquals(first.getString("reservationId"),same.getString("reservationId"));assertEquals(1,pool.getJSONArray("reservations").length());
        assertThrows(SecurityException.class,()->TeamParentBudget.reserve(pool,child("one","run2",2),"member","allocation",0,1));
    }
    @Test public void unknownAllocationDoesNotRefundOrAutoRefill()throws Exception {
        JSONObject pool=TeamParentBudget.create("team","team",1,1,source());JSONObject reserved=TeamParentBudget.reserve(pool,child("one","run1",1),"member","sent",0,1);reserved.put("state","unknown");
        assertThrows(TeamParentBudget.Exhausted.class,()->TeamParentBudget.reserve(pool,child("two","run2",1),"member","replacement",0,1));assertEquals("unknown",pool.getJSONArray("reservations").getJSONObject(0).getString("state"));
    }
}
