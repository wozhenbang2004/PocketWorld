package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ContextReadReductionTest {
    private static ModelConfig config(){return new ModelConfig("https://fixture.invalid/v1","","exact-model",false,8,10,"openai_chat",1000);}
    private static RunContextBudget budget(ModelConfig config){return new RunContextBudget(selected->ContextBudgetPolicy.RouteBudget.forRoute(config,new ContextWindow.Budget(100000,1000,1000,8000,64,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1000));}
    private static JSONArray duplicate(JSONObject flags)throws Exception {
        JSONObject receipt=new JSONObject(flags.toString()).put("ok",true).put("accepted",true).put("text","same exact native read "+"x".repeat(5000));JSONArray rows=new JSONArray().put(new JSONObject().put("role","system").put("content","host")).put(new JSONObject().put("role","user").put("content","original owner words"));
        for(String id:new String[]{"old-call","current-call"})rows.put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name","phone_observe").put("arguments","{}"))))).put(new JSONObject().put("role","tool").put("tool_call_id",id).put("content",receipt.toString()));
        return rows.put(new JSONObject().put("role","user").put("content","current owner words"));
    }
    @Test public void onlyRequestProjectionCanOmitAnExactCompletedReadPair()throws Exception {
        ModelConfig config=config();RunContextBudget budget=budget(config);JSONArray raw=duplicate(new JSONObject());String before=raw.toString();RunContextBudget.Projection request=budget.request(raw,new JSONArray(),config);assertEquals(2,request.plan().removed.size());assertEquals(5,request.plan().messages().length());assertTrue(request.plan().messages().toString().contains("current-call"));assertTrue(request.plan().messages().toString().contains("original owner words"));assertEquals(before,raw.toString());assertTrue(request.diagnostics().getBoolean("toolResultsAutomaticallyEvicted"));
        RunContextBudget.Blocked blocked=assertThrows(RunContextBudget.Blocked.class,()->budget.checkpoint(raw,Map.of(),0,new JSONObject(),config));assertEquals(0,blocked.diagnostics.getJSONArray("removed").length());assertEquals(before,raw.toString());
    }
    @Test public void anUnknownOrPausedReadIsNeverAProjectionCandidate()throws Exception {
        for(JSONObject flag:new JSONObject[]{new JSONObject().put("requiresPause",true),new JSONObject().put("reconciliationRequired",true),new JSONObject().put("status","unknown"),new JSONObject().put("state","effect_unknown"),new JSONObject().put("nextObservationAction","reconcile")}){
            ModelConfig config=config();RunContextBudget.Blocked blocked=assertThrows(RunContextBudget.Blocked.class,()->budget(config).request(duplicate(flag),new JSONArray(),config));assertEquals(flag.toString(),0,blocked.diagnostics.getJSONArray("removed").length());
        }
    }
}
