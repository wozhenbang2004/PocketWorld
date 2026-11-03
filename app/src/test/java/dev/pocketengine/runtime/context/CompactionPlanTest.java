package dev.pocketengine.runtime.context;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.features.memory.TaskMemoryHistory;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CompactionPlanTest {
    static ModelConfig config(){return new ModelConfig("https://fixture.invalid/v1","secret","fixture",false,3,10,"openai_chat",1000);}
    static ContextWindow.Budget budget(){return new ContextWindow.Budget(70000,1000,1000,100000,64,0);}
    static JSONObject message(String role,String text)throws Exception{return new JSONObject().put("role",role).put("content",text);}
    static JSONArray history()throws Exception {
        JSONArray rows=new JSONArray().put(message("system","native invariant")).put(message("user","Owner goal; do not send without approval."));
        for(int i=0;i<5;i++)rows.put(new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(new JSONObject().put("id","call-"+i).put("type","function")
                .put("function",new JSONObject().put("name","read_file").put("arguments","{}")))))
                .put(new JSONObject().put("role","tool").put("tool_call_id","call-"+i).put("content",new JSONObject().put("ok",true).put("file","source-"+i+".java").put("errorCode","ERR_IMPORT_"+i).put("text","x".repeat(9000)).toString()));
        return rows.put(message("user","Latest correction: do not touch billing.")).put(message("assistant","Current tail remains original."));
    }
    static List<Integer> indexes(JSONArray rows){List<Integer> ids=new ArrayList<>();for(int i=0;i<rows.length();i++)ids.add(i);return ids;}
    static CompactionPlan plan(JSONArray rows)throws Exception{return CompactionPlan.choose(rows,new JSONArray(rows.toString()),indexes(rows),null,config(),budget());}
    @Test public void onlySettledPrefixIsCoveredAndEveryOwnerRowAndTailSurvives()throws Exception {
        JSONArray raw=history();String before=raw.toString();CompactionPlan plan=plan(raw);JSONArray result=plan.project(raw,indexes(raw),"ERR_IMPORT_0 in source-0.java; original remains readable.");
        assertTrue(result.toString().contains("Owner goal; do not send"));assertTrue(result.toString().contains("Latest correction"));assertTrue(result.toString().contains("Current tail"));
        assertEquals(before,raw.toString());assertEquals(0,plan.coveredIndexes().size()%2);
        assertTrue(ContextWindow.plan(result,null,budget(),ContextWindow.Retention.preserveAll()).canSend());
        for(int index:plan.coveredIndexes())if("assistant".equals(raw.getJSONObject(index).optString("role")))assertTrue(plan.coveredIndexes().contains(index+1));
    }
    @Test public void unknownEffectAndUnpairedBatchNeverEnterSummarizedPrefix()throws Exception {
        JSONArray raw=history();raw.getJSONObject(3).put("content",new JSONObject().put("state","unknown").put("accepted",JSONObject.NULL).toString());
        assertThrows(CompactionPlan.Unavailable.class,()->plan(raw));
        JSONArray broken=history();broken.getJSONObject(3).put("tool_call_id","not-the-parent");
        assertEquals("invalid_history",assertThrows(CompactionPlan.Unavailable.class,()->plan(broken)).code);
    }
    @Test public void changedSourceOrMaterializedPrefixInvalidatesButNewTailDoesNot()throws Exception {
        JSONArray raw=history();CompactionPlan plan=plan(raw);JSONArray wire=new JSONArray(raw.toString());
        raw.put(message("assistant","new actual result"));wire.put(message("assistant","new actual result"));assertTrue(plan.matchesPrefix(raw,wire,indexes(raw)));
        wire.getJSONObject(3).put("content","changed actual bytes");assertFalse(plan.matchesPrefix(raw,wire,indexes(raw)));
    }
    @Test public void modelWindowMustBeKnownAndSummaryCallMustFitWithoutTruncation()throws Exception {
        JSONArray raw=history();assertEquals("model_window_unknown",assertThrows(CompactionPlan.Unavailable.class,()->CompactionPlan.choose(raw,raw,indexes(raw),null,config(),ContextWindow.Budget.localBytes(100000))).code);
        assertThrows(CompactionPlan.Unavailable.class,()->CompactionPlan.choose(raw,raw,indexes(raw),null,config(),new ContextWindow.Budget(2000,1000,100,100000,0,0)));
    }
    @Test public void differentFinalWireBodyPinsTheWholeOriginalToolBatch()throws Exception {
        JSONArray raw=history(),wire=new JSONArray(raw.toString());wire.getJSONObject(3).put("content",new JSONObject().put("ok",true).put("text","current materialized data, not the retired stored reference").toString());
        CompactionPlan plan=CompactionPlan.choose(raw,wire,indexes(raw),null,config(),budget());
        assertFalse(plan.coveredIndexes().contains(2));assertFalse(plan.coveredIndexes().contains(3));
        JSONArray projected=plan.project(wire,indexes(raw),"other exact settled rows summarized");assertTrue(projected.toString().contains("current materialized data"));
        assertTrue(ContextWindow.plan(projected,null,budget(),ContextWindow.Retention.preserveAll()).canSend());
    }
    @Test public void rawCheckpointKeepsRowsUnderByteCapWithoutMistakingTokenWindowForArchiveSize()throws Exception {
        JSONArray raw=history();RunContextBudget budget=new RunContextBudget(selected->ContextBudgetPolicy.RouteBudget.forRoute(selected,new ContextWindow.Budget(5000,1000,1000,1000000,0,0),ContextBudgetPolicy.SourceKind.OWNER_OVERRIDE,1,1));
        assertThrows(RunContextBudget.Blocked.class,()->budget.request(raw,null,config()));
        RunContextBudget.Projection stored=budget.rawCheckpoint(raw,1,new JSONObject());assertEquals(raw.toString(),stored.plan().messages().toString());assertEquals("raw_source_preserve_all",stored.diagnostics().getString("retentionPolicy"));
        JSONArray huge=new JSONArray().put(message("system","x".repeat(2200000)));assertThrows(RunContextBudget.Blocked.class,()->budget.rawCheckpoint(huge,1,new JSONObject()));
    }
    @Test public void checkpointSaveRestoreAppendNeverReplacesRawOrRetainsChangedSources()throws Exception {
        JSONArray raw=history();CompactionPlan plan=plan(raw);JSONArray refs=new JSONArray();String task="task",run="run";
        for(int index:plan.coveredIndexes())refs.put(new JSONObject().put("sourceId","task:"+task+":run:"+run+":checkpoint-message:"+index).put("revision","sha256:"+CompactionPlan.fingerprint(raw.getJSONObject(index))));
        JSONObject projection=new JSONObject().put("coverage",plan.metadata()).put("guard",new JSONObject().put("taskId",task).put("runId",run)).put("sources",refs).put("text","actual derived summary");
        JSONObject stored=new JSONObject().put("messages",raw).put("contextCompaction",projection);
        assertNotNull(CompactionCheckpoint.restore(stored,true));assertNull(CompactionCheckpoint.restore(stored,false));assertEquals(raw.toString(),stored.getJSONArray("messages").toString());
        JSONArray appended=new JSONArray(raw.toString()).put(message("assistant","later tail"));JSONObject next=CompactionCheckpoint.append(stored,new JSONObject().put("messages",appended),true);
        assertNotNull(next.optJSONObject("contextCompaction"));assertEquals(appended.toString(),next.getJSONArray("messages").toString());
        appended.getJSONObject(3).put("content","source corrected");assertFalse(CompactionCheckpoint.append(stored,new JSONObject().put("messages",appended),true).has("contextCompaction"));
        assertFalse(CompactionCheckpoint.append(stored,new JSONObject().put("messages",raw),false).has("contextCompaction"));
    }
    @Test public void coveredIndexesRemainDefensivelyImmutableWithoutApi31CopyOf()throws Exception {
        CompactionPlan plan=plan(history());List<Integer> before=new ArrayList<>(plan.coveredIndexes());
        assertThrows(UnsupportedOperationException.class,()->plan.coveredIndexes().clear());assertEquals(before,plan.coveredIndexes());
    }

    @Test public void rawExcludedDerivedReferencesPinTheEntireMixedToolBatchWithoutBlockingOtherSettledRows()throws Exception {
        for(String reference:List.of("skills_read","memory_raw_read","memory_history_read")){
            JSONArray original=history(),calls=original.getJSONObject(2).getJSONArray("tool_calls");calls.getJSONObject(0).getJSONObject("function").put("name",reference);
            calls.put(new JSONObject().put("id","paired-effect").put("type","function").put("function",new JSONObject().put("name","workspace_write").put("arguments","{}")));
            JSONArray raw=new JSONArray();for(int i=0;i<original.length();i++){raw.put(original.get(i));if(i==3)raw.put(new JSONObject().put("role","tool").put("tool_call_id","paired-effect").put("content",new JSONObject().put("ok",true).put("text","ORIGINAL_EFFECT_RECEIPT_37").toString()));}
            CompactionPlan plan=plan(raw);for(int index:List.of(2,3,4))assertFalse(plan.coveredIndexes().contains(index));
            assertFalse(plan.coveredIndexes().isEmpty());assertFalse(plan.summaryMessages().toString().contains("ERR_IMPORT_0"));assertFalse(plan.summaryMessages().toString().contains("ORIGINAL_EFFECT_RECEIPT_37"));
            JSONArray wire=plan.project(raw,indexes(raw),"Other settled exact sources summarized.");assertTrue(wire.toString().contains("ERR_IMPORT_0"));assertTrue(wire.toString().contains("ORIGINAL_EFFECT_RECEIPT_37"));
            assertTrue(ContextWindow.plan(wire,null,budget(),ContextWindow.Retention.preserveAll()).canSend());
        }
    }

    @Test public void oldGenericNotReplayedRemainsUnsettledAndNeverBecomesProvenUndispatched()throws Exception {
        JSONArray raw=history();raw.getJSONObject(3).put("content",new JSONObject().put("ok",false).put("code","not_replayed").put("message","no complete historical receipt").toString());assertThrows(CompactionPlan.Unavailable.class,()->plan(raw));
    }

    @Test public void exactNativeRetiredWireKeepsPairingAndPinsArgumentsWhileOtherSettledEvidenceCompacts()throws Exception {
        JSONArray raw=history();JSONObject retired=raw.getJSONObject(2);
        retired.put("memoryContextRetired",true).put("content",TaskMemoryHistory.RETIRED).put("extraBody","RETIRED_ARGUMENT_OLD_SOURCE");
        retired.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments","{\"query\":\"RETIRED_ARGUMENT_OLD_SOURCE\"}");
        JSONObject unknown=new JSONObject().put("state","unknown").put("accepted",JSONObject.NULL).put("operationId","original-unknown-operation");
        raw.getJSONObject(11).put("content",unknown.toString());String original=raw.toString();
        JSONArray visible=TaskMemoryHistory.wireMessages(raw);CompactionPlan plan=CompactionPlan.choose(raw,visible,indexes(raw),null,config(),budget());
        assertEquals(original,raw.toString());assertFalse(plan.coveredIndexes().contains(2));assertFalse(plan.coveredIndexes().contains(3));assertFalse(plan.coveredIndexes().contains(10));assertFalse(plan.coveredIndexes().contains(11));
        assertFalse(plan.coveredIndexes().isEmpty());assertFalse(plan.summaryMessages().toString().contains("RETIRED_ARGUMENT_OLD_SOURCE"));
        JSONArray result=plan.project(visible,indexes(raw),"Other settled sources remain available.");assertFalse(result.toString().contains("RETIRED_ARGUMENT_OLD_SOURCE"));assertTrue(result.toString().contains("original-unknown-operation"));
        assertEquals("{}",result.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"));
        assertTrue(ContextWindow.plan(result,null,budget(),ContextWindow.Retention.preserveAll()).canSend());
        for(String change:List.of("id","name","type","arguments","callExtra","functionExtra","rowExtra","content")){
            JSONArray altered=new JSONArray(visible.toString());JSONObject row=altered.getJSONObject(2),call=row.getJSONArray("tool_calls").getJSONObject(0),function=call.getJSONObject("function");
            if(change.equals("id"))call.put("id","another-call");else if(change.equals("name"))function.put("name","another-tool");else if(change.equals("type"))call.put("type","custom");
            else if(change.equals("arguments"))function.put("arguments","{\"query\":\"another value\"}");else if(change.equals("callExtra"))call.put("extra","body");else if(change.equals("functionExtra"))function.put("extra","body");
            else if(change.equals("rowExtra"))row.put("extra","body");else row.put("content","other content");
            assertEquals(change,"raw_projection_mismatch",assertThrows(CompactionPlan.Unavailable.class,()->CompactionPlan.choose(raw,altered,indexes(raw),null,config(),budget())).code);
        }
        for(Object marker:List.of(false,"true")){
            JSONArray untrusted=new JSONArray(raw.toString());untrusted.getJSONObject(2).put("memoryContextRetired",marker);
            assertEquals("raw_projection_mismatch",assertThrows(CompactionPlan.Unavailable.class,()->CompactionPlan.choose(untrusted,visible,indexes(untrusted),null,config(),budget())).code);
        }
        JSONArray unmarked=new JSONArray(raw.toString());unmarked.getJSONObject(2).remove("memoryContextRetired");
        assertEquals("raw_projection_mismatch",assertThrows(CompactionPlan.Unavailable.class,()->CompactionPlan.choose(unmarked,visible,indexes(unmarked),null,config(),budget())).code);
    }

}
