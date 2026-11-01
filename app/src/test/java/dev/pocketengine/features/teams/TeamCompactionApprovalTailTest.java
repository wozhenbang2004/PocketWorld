package dev.pocketengine.features.teams;

import static org.junit.Assert.*;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.context.CompactionPlan;
import dev.pocketengine.runtime.context.ContextWindow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Real captured90-row checkpoint is only DATA; no JSON in these tests mints a native grant. */
public final class TeamCompactionApprovalTailTest {
    private static Path source(){return Path.of(System.getProperty("c6.approved.continuation.fixture"));}
    private static JSONObject provenance()throws Exception{return new JSONObject(Files.readString(source().resolveSibling("integration-approved-continuation-20261101.provenance.json")));}
    private static JSONArray raw()throws Exception{return new JSONObject(Files.readString(source())).getJSONArray("messages");}
    private static CompactionPlan plan(JSONArray raw,JSONArray wire)throws Exception {
        ArrayList<Integer> map=new ArrayList<>();for(int i=0;i<raw.length();i++)map.add(i);
        return CompactionPlan.choose(raw,wire,map,null,new ModelConfig("https://fixture.invalid/v1","synthetic","integration-tail",false,24,10,"openai_chat",512),new ContextWindow.Budget(128000,1000,1000,2L*1024*1024,64,0));
    }
    private static int check(JSONArray raw,CompactionPlan plan,JSONObject facts)throws Exception{return TeamCompactionApprovalTail.requireProtected(raw,plan,facts.getJSONObject("originalSqlArguments"),facts.getString("approvalId"));}
    @Test public void realNativeCheckpointKeepsExactApprovalRequestAndReceiptOutsideSummaryPrefix()throws Exception {
        JSONArray raw=raw();assertEquals(90,raw.length());CompactionPlan plan=plan(raw,raw);assertTrue(plan.endExclusive<=87);assertEquals(87,check(raw,plan,provenance()));
        JSONArray projected=plan.project(raw,identity(raw),"仅过去确定操作的低信任索引，不给执行权。");boolean request=false,response=false;for(int i=0;i<projected.length();i++){request|=projected.getJSONObject(i).toString().equals(raw.getJSONObject(87).toString());response|=projected.getJSONObject(i).toString().equals(raw.getJSONObject(88).toString());}assertTrue(request);assertTrue(response);
    }
    private static ArrayList<Integer> identity(JSONArray raw){ArrayList<Integer> map=new ArrayList<>();for(int i=0;i<raw.length();i++)map.add(i);return map;}
    @Test public void sameApprovalIdCannotSubstituteDifferentOriginalSqlArguments()throws Exception {
        JSONArray raw=raw();CompactionPlan plan=plan(raw,raw);JSONObject facts=provenance();facts.getJSONObject("originalSqlArguments").put("planDigest","0".repeat(64));assertThrows(CompactionPlan.Unavailable.class,()->check(raw,plan,facts));
    }
    @Test public void retainedButInsidePrefixIsNotAnApprovedContinuationTail()throws Exception {
        // The full historical prefix exceeds the original128k summary request before it
        // reaches the approval, so it cannot construct this particular counterexample.
        // Use the exact original final plan/approval/restore batches as test DATA, then
        // add small settled tail rows. The captured native request/receipt remain verbatim;
        // no production history, budget, prefix selector or permission is changed.
        JSONArray raw=CompactionPlan.slice(raw(),85,90);for(int i=0;i<8;i++)raw.put(new JSONObject().put("role","assistant").put("content","later settled tail "+i));JSONArray wire=new JSONArray(raw.toString());wire.getJSONObject(2).put("content","Native display changed only; this pins the whole batch but does not move it outside the prefix.");CompactionPlan plan=plan(raw,wire);assertTrue("counterexample really puts the original pair in the raw prefix",plan.endExclusive>2);assertFalse("the original pair is retained, not summarized",plan.coveredIndexes().contains(2));assertThrows(CompactionPlan.Unavailable.class,()->check(raw,plan,provenance()));
    }
    @Test public void unknownReceiptNeverQualifiesAsDefinitelyUndispatched()throws Exception {
        JSONArray raw=raw();JSONObject receipt=new JSONObject(raw.getJSONObject(88).getString("content"));receipt.put("status","unknown").put("accepted",JSONObject.NULL);raw.getJSONObject(88).put("content",receipt.toString());CompactionPlan plan=plan(raw,raw);assertThrows(CompactionPlan.Unavailable.class,()->check(raw,plan,provenance()));
    }
    @Test public void duplicateApprovalPairCannotSelectAnAmbiguousOriginalOperation()throws Exception {
        JSONArray raw=raw();JSONObject request=new JSONObject(raw.getJSONObject(87).toString()),response=new JSONObject(raw.getJSONObject(88).toString());request.getJSONArray("tool_calls").getJSONObject(0).put("id","second-native-looking-call");response.put("tool_call_id","second-native-looking-call");raw.put(request).put(response).put(new JSONObject().put("role","user").put("content","native restore data"));CompactionPlan plan=plan(raw,raw);assertThrows(CompactionPlan.Unavailable.class,()->check(raw,plan,provenance()));
    }
    @Test public void mixedBatchCannotMasqueradeAsTheOneApprovedContinuation()throws Exception {
        JSONArray raw=raw();JSONObject second=new JSONObject().put("id","additional-call").put("type","function").put("function",new JSONObject().put("name","project_inspect").put("arguments","{}"));raw.getJSONObject(87).getJSONArray("tool_calls").put(second);JSONArray expanded=new JSONArray();for(int i=0;i<raw.length();i++){expanded.put(raw.get(i));if(i==88)expanded.put(new JSONObject().put("role","tool").put("tool_call_id","additional-call").put("content","{\"status\":\"observed\"}"));}CompactionPlan plan=plan(expanded,expanded);assertThrows(CompactionPlan.Unavailable.class,()->check(expanded,plan,provenance()));
    }
}
