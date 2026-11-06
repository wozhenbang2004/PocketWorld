package dev.pocketengine.runtime.usage;

import java.time.Instant;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class UsageTrendProjectionTest {
    private static JSONObject receipt(String call,long at,Long input,String stage)throws Exception{
        JSONObject count=new JSONObject().put("value",input==null?JSONObject.NULL:input).put("source",input==null?"unknown":"provider_reported").put("stage",stage);
        return new JSONObject().put("type","inference_receipt").put("receiptVersion",1).put("callId",call).put("evidenceOnly",true)
                .put("startedAt",at).put("finishedAt",at+10).put("elapsedNanos",10000000L).put("outcome","COMPLETED")
                .put("context",new JSONObject().put("taskId","original-task").put("runId","original-run").put("epoch",1).put("purpose","MAIN").put("memberId",""))
                .put("route",new JSONObject().put("kind","api_key").put("providerId","historical-provider").put("requestedModel","deleted-model").put("protocol","openai"))
                .put("usage",new JSONObject().put("protocol","openai").put("counters",new JSONObject().put("INPUT",count)));
    }
    @Test public void timezoneOnlyRebucketsOriginalPhysicalFactsAndDoesNotZeroUnknown()throws Exception{
        long midnight=Instant.parse("2026-03-09T00:30:00Z").toEpochMilli();String id=UUID.randomUUID().toString();
        JSONArray values=new JSONArray().put(receipt(id,midnight,12L,"PARTIAL")).put(receipt(id,midnight,12L,"PARTIAL"))
                .put(receipt(UUID.randomUUID().toString(),midnight+1000,null,"FINAL"));
        var utc=new UsageTrendProjection.Query(midnight-86_400_000,midnight+86_400_000,"UTC");
        JSONObject first=UsageTrendProjection.fromReceipts(values,utc),pacific=UsageTrendProjection.fromReceipts(values,new UsageTrendProjection.Query(utc.fromInclusive(),utc.toExclusive(),"America/Los_Angeles"));
        assertEquals(first.getJSONObject("totals").toString(),pacific.getJSONObject("totals").toString());
        assertEquals(2,first.getJSONObject("totals").getInt("attempts"));
        JSONObject input=first.getJSONObject("totals").getJSONObject("counters").getJSONObject("INPUT");
        assertEquals(12,input.getLong("knownSubtotal"));assertEquals(1,input.getInt("unknownCalls"));assertEquals(1,input.getInt("partialCalls"));
        assertTrue(first.getJSONObject("totals").getJSONObject("counters").getJSONObject("OUTPUT").isNull("knownSubtotal"));
        assertEquals("2026-03-09",first.getJSONArray("days").getJSONObject(0).getJSONObject("identity").getString("day"));
        assertEquals("2026-03-08",pacific.getJSONArray("days").getJSONObject(0).getJSONObject("identity").getString("day"));
        assertEquals("historical-provider",first.getJSONArray("days").getJSONObject(0).getJSONObject("identity").getString("provider"));
        assertTrue(first.isNull("actualCost"));assertEquals(2,first.getJSONObject("totals").getJSONObject("money").getInt("unknownCalls"));
    }
    @Test public void conflictingAndOutOfRangeReceiptsCannotInflateTrend()throws Exception{
        long at=1_800_000_000_000L;String call=UUID.randomUUID().toString();
        JSONArray events=new JSONArray().put(receipt(call,at,10L,"FINAL")).put(receipt(call,at,999L,"FINAL"))
                .put(receipt(UUID.randomUUID().toString(),at-100,100L,"FINAL"));
        JSONObject report=UsageTrendProjection.fromReceipts(events,new UsageTrendProjection.Query(at,at+1000,"UTC"));
        assertEquals(1,report.getInt("conflictingCalls"));assertEquals(0,report.getJSONObject("totals").getInt("attempts"));
        assertTrue(report.getJSONObject("totals").getJSONObject("counters").getJSONObject("INPUT").isNull("knownSubtotal"));
        assertThrows(IllegalArgumentException.class,()->new UsageTrendProjection.Query(at,at+UsageTrendProjection.MAX_RANGE_MILLIS+1,"UTC"));
    }
    @Test public void traceExportCopiesOnlyAllowlistedMetadataAndBoundsUtf8Bytes()throws Exception{
        long at=1_800_000_000_000L;JSONArray receipts=new JSONArray();
        for(int i=0;i<100;i++){JSONObject value=receipt(UUID.randomUUID().toString(),at+i,100L,"FINAL");
            value.getJSONObject("context").put("taskId","original-task").put("runId","original-run");
            value.getJSONObject("route").put("credentialId","DO-NOT-EXPORT-SECRET").put("requestedModel","私人模型名称").put("prompt","PRIVATE-PROMPT");
            value.put("content","PRIVATE-ANSWER");receipts.put(value);}
        JSONObject source=new JSONObject().put("format","pocketworld.trace-source.v1").put("readOnly",true).put("taskId","original-task").put("runId","original-run").put("epoch",1)
                .put("sourceRevision","revision-one").put("capturedAt",at+1000).put("receipts",receipts).put("operations",new JSONArray());
        JSONObject trace=DiagnosticTraceProjection.fromSource(source,16384);String wire=trace.getJSONObject("otlp").toString();
        assertFalse(wire.contains("PRIVATE-"));assertFalse(wire.contains("DO-NOT-EXPORT"));assertFalse(wire.contains("私人模型"));assertFalse(wire.contains("original-task"));
        assertTrue(trace.getInt("skippedSpans")>0);assertTrue(trace.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=16384);
        JSONObject span=trace.getJSONObject("otlp").getJSONArray("resourceSpans").getJSONObject(0).getJSONArray("scopeSpans").getJSONObject(0).getJSONArray("spans").getJSONObject(0);
        assertTrue(span.getString("traceId").matches("[0-9a-f]{32}"));assertTrue(span.getString("spanId").matches("[0-9a-f]{16}"));assertTrue(span.get("startTimeUnixNano") instanceof String);assertTrue(span.get("kind") instanceof Integer);
    }
}
