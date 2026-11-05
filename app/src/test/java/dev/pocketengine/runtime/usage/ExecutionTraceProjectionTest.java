package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.RunControl;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ExecutionTraceProjectionTest {
    @Test public void traceOptionsDoNotFragmentExistingUsageGroups() throws Exception {
        JSONObject first=receipt("openai_chat",1),second=receipt("openai_chat",2);
        first.getJSONObject("route").put("requestOptions",new JSONObject().put("temperature",0.2)).put("requestOptionsSource","final_wire_allowlist");
        second.getJSONObject("route").put("requestOptions",new JSONObject().put("temperature",0.8)).put("requestOptionsSource","final_wire_allowlist");
        JSONObject usage=InferenceProjection.fromEvents(new JSONArray().put(first).put(second));
        assertEquals(2,usage.getInt("attempts"));assertEquals(1,usage.getJSONArray("groups").length());assertEquals(2,usage.getJSONArray("groups").getJSONObject(0).getInt("attempts"));
    }
    private static JSONObject receipt(String protocol, long elapsed) throws Exception {
        return new JSONObject().put("type", "inference_receipt").put("receiptVersion", 1).put("evidenceOnly", true)
                .put("callId", UUID.randomUUID().toString()).put("startedAt", 10000).put("finishedAt", 9000).put("elapsedNanos", elapsed)
                .put("outcome", "COMPLETED").put("context", new JSONObject().put("taskId", "task").put("runId", "team:run").put("epoch", 0).put("memberId", "member-a"))
                .put("route", new JSONObject().put("protocol", protocol).put("requestedModel", "PRIVATE-MODEL").put("logicalRequestId", UUID.randomUUID().toString()).put("attemptNo", 2))
                .put("usage", new ProviderUsage.Collector(protocol).toJson());
    }
    private static JSONObject source(JSONObject model, JSONArray tools) throws Exception {
        return new JSONObject().put("format", "pocketworld.trace-source.v1").put("readOnly", true).put("taskId", "task").put("runId", "team:run").put("epoch", 0)
                .put("sourceRevision", "revision").put("capturedAt", 11000).put("receipts", new JSONArray().put(model)).put("toolCalls", tools).put("operations", new JSONArray());
    }
    private static JSONArray spans(JSONObject export) throws Exception {
        return export.getJSONObject("otlp").getJSONArray("resourceSpans").getJSONObject(0).getJSONArray("scopeSpans").getJSONObject(0).getJSONArray("spans");
    }
    private static String attr(JSONObject span, String name) throws Exception {
        JSONArray attributes = span.getJSONArray("attributes");
        for (int i = 0; i < attributes.length(); i++) if (name.equals(attributes.getJSONObject(i).getString("key"))) {
            JSONObject value = attributes.getJSONObject(i).getJSONObject("value"); return value.optString("intValue", value.optString("stringValue"));
        }
        return null;
    }
    @Test public void monotonicTimingSurvivesWallClockRegressionAndZeroIsNotUnknown() throws Exception {
        JSONObject original = receipt("openai_chat", 1234567), node = ExecutionTraceProjection.model(original, 7);
        assertEquals("1234567", node.getString("elapsedNanos")); assertEquals(1.234567, node.getDouble("durationMs"), 0.0000001);
        assertTrue(node.getBoolean("wallClockAdjusted")); assertEquals(0, node.getInt("epoch")); assertEquals(2, node.getInt("attemptNo"));
        JSONObject span = spans(DiagnosticTraceProjection.fromSource(source(original, new JSONArray()), 16384)).getJSONObject(0);
        assertEquals(BigInteger.valueOf(1234567), new BigInteger(span.getString("endTimeUnixNano")).subtract(new BigInteger(span.getString("startTimeUnixNano"))));
        original.put("elapsedNanos", 0); assertEquals(0.0, ExecutionTraceProjection.model(original, 7).getDouble("durationMs"), 0);
        original.put("outcome", "STARTED").put("finishedAt", JSONObject.NULL);
        assertTrue(ExecutionTraceProjection.model(original, 7).isNull("durationMs"));
        JSONObject unfinished = DiagnosticTraceProjection.fromSource(source(original, new JSONArray()), 16384);
        assertEquals(0, unfinished.getInt("spanCount")); assertEquals(1, unfinished.getInt("unknownOutcomes"));
    }
    @Test public void nativeToolIdentityTerminalDedupUnknownAndReadOnlyFailuresStayDistinct() throws Exception {
        List<JSONObject> events = new ArrayList<>(); RunControl control = new RunControl(); control.bindHostRun("task", "team:run", 1);
        String model = UUID.randomUUID().toString();
        ToolTraceCapture capture = new ToolTraceCapture("task", control, "model:" + model, model, "provider-tool-id", "read_file", "member-a", "turn-a", "{\"path\":\"fixture\"}", events::add);
        assertTrue(ExecutionTraceProjection.tool(events.get(0), null, 1).isNull("durationMs"));
        capture.dispatchEntered();capture.finish(new JSONObject().put("ok", false).put("code", "missing_file").put("message", "not found"), null, null, control);
        capture.finish(new JSONObject().put("ok", true), null, null, control); assertEquals(2, events.size());
        JSONObject node = ExecutionTraceProjection.tool(events.get(0), events.get(1), 1);
        assertEquals("failed", node.getString("status"));assertEquals("model:" + model, node.getString("parentId"));
        assertNotEquals("provider-tool-id", node.getString("callId"));assertFalse(node.has("operationId"));assertTrue(node.getBoolean("timingKnown"));
        assertEquals("missing_file", node.getJSONObject("error").getString("code"));
        JSONObject forged = new JSONObject(events.get(1).toString()).put("runId", "another-run");assertNull(ExecutionTraceProjection.tool(events.get(0), forged, 1));
        List<JSONObject> cancelled = new ArrayList<>();ToolTraceCapture before = new ToolTraceCapture("task", control, null, "", "provider", "read_file", "", "", new JSONObject(), cancelled::add);
        control.cancel();before.finish(null, null, new InterruptedException("stopped"), control);
        assertEquals("cancelled", ExecutionTraceProjection.tool(cancelled.get(0), cancelled.get(1), 2).getString("status"));
    }
    @Test public void exportKeepsParentRelationPrivacyAndProviderSpecificTokenMeaning() throws Exception {
        JSONObject model = receipt("anthropic", 2000000);ProviderUsage.Collector usage = new ProviderUsage.Collector("anthropic");
        usage.observe(new JSONObject().put("usage", new JSONObject().put("input_tokens", 10).put("cache_read_input_tokens", 3).put("cache_creation_input_tokens", 2).put("output_tokens", 7)), ProviderUsage.Stage.FINAL);
        model.put("usage", usage.toJson());
        JSONObject tool = new JSONObject().put("id", "tool:" + UUID.randomUUID()).put("taskId", "task").put("runId", "team:run").put("epoch", 0)
                .put("parentId", "model:" + model.getString("callId")).put("name", "PRIVATE-TOOL").put("status", "completed").put("startedAt", 10001).put("finishedAt", 10002)
                .put("elapsedNanos", "1400000").put("timingKnown", true).put("input", "PRIVATE-INPUT").put("output", "PRIVATE-OUTPUT");
        JSONObject export = DiagnosticTraceProjection.fromSource(source(model, new JSONArray().put(tool)), 16384);JSONArray spans = spans(export);
        assertEquals(2, spans.length());assertEquals(spans.getJSONObject(0).getString("spanId"), spans.getJSONObject(1).getString("parentSpanId"));
        assertEquals("15", attr(spans.getJSONObject(0), "gen_ai.usage.input_tokens"));assertEquals("7", attr(spans.getJSONObject(0), "gen_ai.usage.output_tokens"));
        assertFalse(export.getJSONObject("otlp").toString().contains("PRIVATE-"));assertNull(attr(spans.getJSONObject(1), "gen_ai.usage.input_tokens"));
        model.getJSONObject("usage").getJSONObject("counters").getJSONObject("CACHE_WRITE").put("value", JSONObject.NULL).put("source", "unknown");
        assertNull(attr(spans(DiagnosticTraceProjection.fromSource(source(model, new JSONArray()), 16384)).getJSONObject(0), "gen_ai.usage.input_tokens"));
        ProviderUsage.Collector gemini = new ProviderUsage.Collector("gemini");gemini.observe(new JSONObject().put("usageMetadata", new JSONObject().put("promptTokenCount", 4).put("candidatesTokenCount", 6).put("thoughtsTokenCount", 5)), ProviderUsage.Stage.FINAL);
        model.put("usage", gemini.toJson());assertEquals("11", attr(spans(DiagnosticTraceProjection.fromSource(source(model, new JSONArray()), 16384)).getJSONObject(0), "gen_ai.usage.output_tokens"));
    }
    @Test public void nativeCycleSeparatesRealInputsFromRecoveryContextAndDoesNotRetainBodies() throws Exception {
        List<JSONObject> events=new ArrayList<>();RunControl control=new RunControl();control.bindHostRun("task","run",1);
        ExecutionTraceCapture trace=new ExecutionTraceCapture("task",control,events::add);
        JSONObject goal=new JSONObject().put("role","user").put("content","private initial goal");trace.initialGoal(goal);
        JSONObject owner=new JSONObject().put("role","user").put("content","private native supplement").put("ownerMessageId",UUID.randomUUID().toString()).put("ownerMessageRevision",1L);
        JSONArray history=new JSONArray().put(new JSONObject().put("role","system").put("content","private instructions"))
                .put(goal).put(new JSONObject().put("role","user").put("content","private synthetic recovery prompt")).put(owner);
        InferenceReceipt.CallSite site=new InferenceReceipt.CallSite("turn-native",7,1,0);
        String first=UUID.randomUUID().toString(),retry=UUID.randomUUID().toString();trace.request(first,site,history,3);trace.request(retry,site,history,3);
        int inputs=0,contexts=0,requests=0;for(JSONObject event:events){assertEquals(8,event.getInt("turnIndex"));assertEquals("turn-native",event.getString("turnId"));
            if(ExecutionTraceCapture.REQUEST.equals(event.getString("type"))){requests++;continue;}
            JSONObject node=ExecutionTraceProjection.event(event,inputs+contexts+1,"run",1);
            if("input".equals(node.getString("role")))inputs++;if("context".equals(node.getString("role")))contexts++;
            assertEquals("model:"+first,node.getString("requestId"));assertFalse(event.toString().contains("private"));
        }
        assertEquals(2,inputs);assertEquals(2,contexts);assertEquals(2,requests);
        JSONObject assistant=new JSONObject().put("role","assistant").put("content","private accepted answer");
        trace.assistant(retry,site,assistant,3);JSONObject linked=ExecutionTraceProjection.event(events.get(events.size()-1),9,"run",1);
        assertEquals("assistant",linked.getString("role"));assertEquals("model:"+retry,linked.getString("requestId"));assertFalse(linked.toString().contains("private"));
    }
    @Test public void toolSchemaIsFrozenAndCompactErrorsKeepTheirExistenceWithoutBody() throws Exception {
        List<JSONObject> events=new ArrayList<>();RunControl control=new RunControl();control.bindHostRun("task","run",1);
        JSONObject schema=new JSONObject().put("type","function").put("function",new JSONObject().put("name","read").put("description","original schema"));
        String model=UUID.randomUUID().toString(),parent=UUID.randomUUID().toString();
        ToolTraceCapture trace=new ToolTraceCapture("task",control,"tool:"+parent,model,"provider", "read","","turn",new JSONObject(),schema,5,events::add);
        schema.getJSONObject("function").put("description","later registration");trace.dispatchEntered();trace.finish(new JSONObject().put("message","private unknown failure"),null,null,control);
        JSONObject node=ExecutionTraceProjection.tool(events.get(0),events.get(1),2),compact=ExecutionTraceProjection.compact(node);
        assertEquals("subtool",node.getString("role"));assertEquals(5,node.getInt("stepIndex"));assertEquals("original schema",node.getJSONObject("schema").getJSONObject("function").getString("description"));
        assertEquals("unknown",compact.getString("status"));assertTrue(compact.getBoolean("hasError"));assertFalse(compact.toString().contains("private unknown"));assertFalse(compact.has("schema"));assertFalse(compact.has("input"));assertFalse(compact.has("output"));
    }
}
