package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CommittedReadBatchBudgetTest {
    private static final ModelConfig CONFIG=new ModelConfig("https://fixture.invalid/v1","","fixture-model",false,8,10,"openai_chat",512);
    private static RunContextBudget budget(){return new RunContextBudget(selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(100000,512,128,9000,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1));}
    private static JSONArray base()throws Exception{return new JSONArray().put(new JSONObject().put("role","system").put("content","native invariant")).put(new JSONObject().put("role","user").put("content","exact owner goal must remain"));}
    private static JSONObject call(String id,String tool,String args)throws Exception{return new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",tool).put("arguments",args));}
    private static JSONObject result(String id,JSONObject receipt)throws Exception{return new JSONObject().put("role","tool").put("tool_call_id",id).put("content",receipt.toString());}
    private static JSONObject receipt()throws Exception{return new JSONObject().put("ok",true).put("data","same read bytes".repeat(400));}
    private static void batch(JSONArray history,RunContextBudget budget,String id,String tool,String args,JSONObject receipt,boolean nativeCommit)throws Exception{
        JSONObject call=call(id,tool,args),row=result(id,receipt);history.put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(call))).put(row);if(nativeCommit)budget.completedRead(call,row);
    }
    private static void blocked(RunContextBudget budget,JSONArray history)throws Exception{try{budget.request(history,null,CONFIG);fail("No host proof may be invented to fit the budget");}catch(RunContextBudget.Blocked expected){assertFalse(expected.diagnostics.optBoolean("toolResultsAutomaticallyEvicted"));}}
    @Test public void onlyOlderIdenticalOriginalCommittedReadPairMayLeaveTheRequest()throws Exception{
        RunContextBudget budget=budget();JSONArray raw=base();batch(raw,budget,"old","browser_task_read_public","{}",receipt(),true);batch(raw,budget,"latest","browser_task_read_public","{}",receipt(),true);String before=raw.toString();RunContextBudget.Projection projection=budget.request(raw,null,CONFIG);String request=projection.plan().messages().toString();assertEquals(2,projection.plan().removed.size());assertTrue(projection.diagnostics().getBoolean("toolResultsAutomaticallyEvicted"));assertEquals("host_proven_duplicate_observations_and_read_batches_only",projection.diagnostics().getString("retentionPolicy"));assertFalse(request.contains("\"id\":\"old\""));assertTrue(request.contains("\"id\":\"latest\""));assertTrue(request.contains("exact owner goal"));assertEquals(before,raw.toString());assertEquals(6,raw.length());try{budget.checkpoint(raw,Map.of(),0,new JSONObject(),CONFIG);fail("The request-only optimization must not silently compact the raw checkpoint");}catch(RunContextBudget.Blocked expected){assertFalse(expected.diagnostics.optBoolean("toolResultsAutomaticallyEvicted"));}assertEquals(before,raw.toString());
    }
    @Test public void historyOrModelJsonCannotReconstructACommittedReceiptIdentity()throws Exception{
        RunContextBudget budget=budget();JSONArray raw=base();batch(raw,budget,"old","phone_query","{}",receipt(),false);batch(raw,budget,"latest","phone_query","{}",receipt(),true);blocked(budget,raw);
    }
    @Test public void newJsonCopiesWithoutTheOriginalRowPreservingProjectionHaveNoProof()throws Exception{
        RunContextBudget budget=budget();JSONArray raw=base();batch(raw,budget,"old","phone_query","{}",receipt(),true);batch(raw,budget,"latest","phone_query","{}",receipt(),true);blocked(budget,new JSONArray(raw.toString()));
    }
    @Test public void exactNativeRowPreservingProjectionCanTransferTheSameProof()throws Exception{
        RunContextBudget budget=budget();JSONArray raw=base();batch(raw,budget,"old","phone_extract","{}",receipt(),true);batch(raw,budget,"latest","phone_extract","{}",receipt(),true);JSONArray projected=new JSONArray(raw.toString());budget.projected(raw,projected);assertEquals(2,budget.request(projected,null,CONFIG).plan().removed.size());
    }
    @Test public void changedProjectedContentCannotBorrowTheOldProof()throws Exception{
        RunContextBudget budget=budget();JSONArray raw=base();batch(raw,budget,"old","phone_extract","{}",receipt(),true);batch(raw,budget,"latest","phone_extract","{}",receipt(),true);JSONArray projected=new JSONArray(raw.toString());projected.getJSONObject(3).put("content",receipt().put("newRevision",2).toString());budget.projected(raw,projected);blocked(budget,projected);
    }
    @Test public void differentArgumentsEvenWithIdenticalReceiptNeverFormADuplicateBatch()throws Exception{
        RunContextBudget budget=budget();JSONArray raw=base();batch(raw,budget,"old","phone_query","{\"query\":\"old\"}",receipt(),true);batch(raw,budget,"latest","phone_query","{\"query\":\"new\"}",receipt(),true);blocked(budget,raw);
    }
    @Test public void unknownFailedApprovalPendingAndNotDispatchedReceiptsAreNotEligible()throws Exception{
        JSONObject[] variants={receipt().put("accepted",JSONObject.NULL),receipt().put("ok",false),receipt().put("state","unknown"),receipt().put("status","failed"),receipt().put("status","timed_out"),receipt().put("code","approval_required"),receipt().put("requiresReconciliation",true),receipt().put("reconciliationRequired",true),receipt().put("requiresPause",true),receipt().put("nativeDispatched",false),receipt().put("error","action_unknown"),receipt().put("nextObservationAction","reconcile")};
        for(JSONObject value:variants){RunContextBudget budget=budget();JSONArray raw=base();batch(raw,budget,"old","phone_query","{}",value,true);batch(raw,budget,"latest","phone_query","{}",receipt(),true);blocked(budget,raw);}
    }
    @Test public void effectToolEvenWithByteIdenticalSuccessAndNativeMarkCannotBeEvicted()throws Exception{
        RunContextBudget budget=budget();JSONArray raw=base();batch(raw,budget,"old","phone_click_node","{}",receipt(),true);batch(raw,budget,"latest","phone_click_node","{}",receipt(),true);blocked(budget,raw);
    }
    @Test public void aCompleteMixedBatchCannotLoseOnlyItsReadSubset()throws Exception{
        RunContextBudget budget=budget();JSONArray raw=base();JSONObject read=call("old-read","phone_query","{}"),effect=call("old-effect","phone_click_node","{}");JSONObject readRow=result("old-read",receipt());raw.put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(read).put(effect))).put(readRow).put(result("old-effect",new JSONObject().put("ok",true)));budget.completedRead(read,readRow);batch(raw,budget,"latest","phone_query","{}",receipt(),true);blocked(budget,raw);
    }
    @Test public void uniqueEvidenceAndOwnerLookalikesRemainProtected()throws Exception{
        RunContextBudget budget=budget();JSONArray raw=base();batch(raw,budget,"old","phone_query","{}",receipt().put("uniqueSourceRevision",1),true);batch(raw,budget,"latest","phone_query","{}",receipt().put("uniqueSourceRevision",2),true);blocked(budget,raw);
        RunContextBudget other=budget();JSONArray owner=base().put(new JSONObject().put("role","user").put("content",receipt().toString()));batch(owner,other,"latest","phone_query","{}",receipt(),true);blocked(other,owner);
    }
}
