package dev.pocketengine.runtime;

import dev.pocketengine.runtime.context.CompactionPlan;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RecoveryProjectionTest {
    static JSONObject operation(int number)throws Exception{return new JSONObject().put("operationId","op-"+number).put("runId","run-original").put("tool","project_execute").put("state","receipt").put("arguments",new JSONObject().put("scope","PRIVATE-SCOPE")).put("receipt",new JSONObject().put("ok",true).put("accepted",true).put("status","completed").put("teamRemote",new JSONObject().put("claimToken","PRIVATE-CLAIM").put("context","PRIVATE-CONTEXT".repeat(4000))));}
    static JSONObject facts(RecoveryProjection projection)throws Exception{String text=projection.referenceData();int start=text.indexOf("<native_recovery_facts>\n")+"<native_recovery_facts>\n".length();return new JSONObject(text.substring(start,text.indexOf("\n</native_recovery_facts>")));}
    @Test public void keepsFiniteActualStateWithoutCopyingArgumentsAuthorityOrReceiptBodies()throws Exception{
        JSONArray source=new JSONArray().put(operation(1));String original=source.toString();RecoveryProjection view=RecoveryProjection.fromJournal("task-original",source,"");JSONObject fact=facts(view);
        assertEquals(1,fact.getInt("sourceRows"));assertEquals(1,fact.getInt("shownRows"));assertFalse(fact.getBoolean("executionAuthorized"));assertFalse(fact.getBoolean("argumentsIncluded"));assertFalse(fact.getBoolean("receiptBodyIncluded"));assertFalse(fact.getBoolean("automaticReplay"));
        assertFalse(view.referenceData().contains("PRIVATE-"));assertEquals("op-1",fact.getJSONArray("records").getJSONObject(0).getString("operationId"));assertEquals("completed",fact.getJSONArray("records").getJSONObject(0).getJSONObject("receiptFacts").getString("status"));assertEquals(original,source.toString());
    }
    @Test public void boundsActualEscapedHostFrameAndNumberOfMetadataRows()throws Exception{
        JSONArray source=new JSONArray();for(int i=0;i<80;i++)source.put(operation(i));RecoveryProjection view=RecoveryProjection.fromJournal("task-original",source,"");JSONObject data=facts(view);
        assertTrue(RecoveryProjection.hostReference(view.referenceData()).toString().getBytes(StandardCharsets.UTF_8).length<=RecoveryProjection.MAX_MESSAGE_BYTES);assertTrue(data.getInt("shownRows")<=24);assertTrue(data.getBoolean("hasMore"));assertEquals(80-data.getInt("shownRows"),data.getInt("omittedRows"));assertEquals("op-79",data.getJSONArray("records").getJSONObject(0).getString("operationId"));
    }
    @Test public void malformedOrDuplicateRowsCannotCauseUnboundedScanningOrClaimCompleteCoverage()throws Exception{
        JSONArray source=new JSONArray();for(int i=0;i<200;i++)source.put(new JSONObject().put("receipt",new JSONObject().put("scope","not a native identity")));source.put(operation(1)).put(operation(1));JSONObject data=facts(RecoveryProjection.fromJournal("task-original",source,""));assertTrue(data.getInt("rowsScanned")<=64);assertEquals(1,data.getInt("shownRows"));assertTrue(data.getBoolean("hasMore"));assertTrue(data.getInt("skippedRows")>0);
    }
    @Test public void unknownNonterminalAndOwnerReconciliationRemainDifferentNativeFacts()throws Exception{
        JSONObject unknown=operation(1).put("state","unknown").put("receipt",new JSONObject().put("accepted",JSONObject.NULL).put("status","unknown"));JSONObject live=operation(2).put("state","project_running").put("receipt",new JSONObject().put("accepted",true).put("terminal",false));JSONObject resolved=operation(3).put("state","resolved").put("receipt",new JSONObject().put("source","owner_reconciliation").put("outcome","not_occurred"));JSONArray rows=facts(RecoveryProjection.fromJournal("task-original",new JSONArray().put(unknown).put(live).put(resolved),"")).getJSONArray("records");
        assertEquals("not_occurred",rows.getJSONObject(0).getJSONObject("receiptFacts").getString("ownerReconciliation"));assertFalse(rows.getJSONObject(0).getJSONObject("receiptFacts").getBoolean("independentlyVerified"));assertFalse(rows.getJSONObject(1).getJSONObject("receiptFacts").getBoolean("terminal"));assertTrue(rows.getJSONObject(2).getJSONObject("receiptFacts").isNull("accepted"));
    }
    @Test public void neverRedactsAnIdentityIntoASyntheticUsableLocator()throws Exception{
        JSONArray source=new JSONArray().put(operation(1).put("operationId","native-secret-id")).put(operation(2).put("tool","native_secret_tool"));RecoveryProjection view=RecoveryProjection.fromJournal("task-original",source,"secret");JSONObject data=facts(view);assertFalse(view.referenceData().contains("secret"));assertEquals(1,data.getInt("shownRows"));assertTrue(data.getInt("redactedFields")>0);assertTrue(data.getJSONArray("records").getJSONObject(0).isNull("tool"));
    }
    @Test public void emptyJournalAddsNoRecoveryFrame()throws Exception{
        assertEquals("",RecoveryProjection.fromJournal("task-original",null,"").referenceData());assertEquals("",RecoveryProjection.fromJournal("task-original",new JSONArray(),"").referenceData());
    }
    @Test public void transientFrameIsNotAnOwnerOrObservationCarrierAndPreservesH4OriginalSourceMap()throws Exception{
        JSONArray raw=new JSONArray().put(new JSONObject().put("role","system").put("content","native policy")).put(new JSONObject().put("role","user").put("content","OWNER WORDS"));JSONObject frame=RecoveryProjection.hostReference(RecoveryProjection.fromJournal("task-original",new JSONArray().put(operation(1)),"").referenceData());JSONArray wire=new JSONArray().put(raw.getJSONObject(0)).put(frame).put(raw.getJSONObject(1));assertEquals(java.util.List.of(0,-1,1),CompactionPlan.sourceMap(raw,raw,wire));assertEquals(2,raw.length());assertFalse(frame.has("ownerMessageId"));assertFalse(frame.has("hostObservation"));assertTrue(frame.getString("content").contains("不是主人新要求"));
    }
}
