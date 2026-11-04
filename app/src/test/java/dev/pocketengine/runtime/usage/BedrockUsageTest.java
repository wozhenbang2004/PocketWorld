package dev.pocketengine.runtime.usage;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual cumulative provider counters; neither a cloud bill nor a transport fixture. */
public final class BedrockUsageTest {
    private static JSONObject wire(long input,long output,long read,long write)throws Exception {
        return new JSONObject().put("usage",new JSONObject().put("input_tokens",input).put("output_tokens",output)
                .put("cache_read_input_tokens",read).put("cache_creation_input_tokens",write));
    }
    @Test public void independentProtocolRetainsAnthropicCacheAndOutputMeanings()throws Exception {
        ProviderUsage.Collector usage=new ProviderUsage.Collector("bedrock_anthropic");
        usage.observe(wire(3,6,100,20),ProviderUsage.Stage.FINAL);
        JSONObject actual=usage.toJson();
        assertEquals("bedrock_anthropic",actual.getString("protocol"));
        assertEquals(3,usage.get(ProviderUsage.Counter.INPUT).value);
        assertEquals(6,usage.get(ProviderUsage.Counter.OUTPUT).value);
        assertEquals(100,usage.get(ProviderUsage.Counter.CACHE_READ).value);
        assertEquals(20,usage.get(ProviderUsage.Counter.CACHE_WRITE).value);
        assertFalse(actual.getBoolean("inputIncludesCacheRead"));
        assertFalse(actual.getBoolean("inputIncludesCacheWrite"));
        assertTrue(actual.getBoolean("outputIncludesReasoning"));
        assertNull(usage.get(ProviderUsage.Counter.TOTAL));
        assertEquals("usage.cache_creation_input_tokens",usage.get(ProviderUsage.Counter.CACHE_WRITE).sourcePath);
        assertTrue(actual.isNull("actualCost"));assertTrue(actual.isNull("estimatedCost"));
    }
    @Test public void cumulativeDeltasReplaceOutputWithoutAddingRepeatedSnapshots()throws Exception {
        ProviderUsage.Collector usage=new ProviderUsage.Collector("bedrock_anthropic");
        usage.observe(wire(3,1,100,20),ProviderUsage.Stage.PARTIAL);
        for(long output:new long[]{4,6,6})usage.observe(new JSONObject().put("usage",new JSONObject().put("output_tokens",output)),ProviderUsage.Stage.PARTIAL);
        assertEquals(3,usage.get(ProviderUsage.Counter.INPUT).value);
        assertEquals(6,usage.get(ProviderUsage.Counter.OUTPUT).value);
        long observedAt=usage.get(ProviderUsage.Counter.OUTPUT).observedAt;
        usage.completed();
        assertEquals(6,usage.get(ProviderUsage.Counter.OUTPUT).value);
        assertEquals(observedAt,usage.get(ProviderUsage.Counter.OUTPUT).observedAt);
        assertEquals(ProviderUsage.Stage.FINAL,usage.get(ProviderUsage.Counter.INPUT).stage);
        assertEquals(ProviderUsage.Stage.FINAL,usage.get(ProviderUsage.Counter.OUTPUT).stage);
    }
    @Test public void aStopReasonDoesNotPromotePartialUsageBeforeTheStreamCompletes()throws Exception {
        ProviderUsage.Collector usage=new ProviderUsage.Collector("bedrock_anthropic");
        usage.observe(wire(3,6,100,20),ProviderUsage.Stage.PARTIAL);
        usage.terminal("end_turn");
        assertEquals("end_turn",usage.toJson().getString("finishReason"));
        for(ProviderUsage.Counter counter:new ProviderUsage.Counter[]{ProviderUsage.Counter.INPUT,ProviderUsage.Counter.OUTPUT,ProviderUsage.Counter.CACHE_READ,ProviderUsage.Counter.CACHE_WRITE})
            assertEquals(ProviderUsage.Stage.PARTIAL,usage.get(counter).stage);
    }
    @Test public void missingCacheAndTotalRemainUnknownEvenAfterSuccessfulCompletion()throws Exception {
        ProviderUsage.Collector usage=new ProviderUsage.Collector("bedrock_anthropic");
        usage.observe(new JSONObject().put("usage",new JSONObject().put("input_tokens",0).put("output_tokens",2)),ProviderUsage.Stage.PARTIAL);
        usage.completed();
        assertEquals(0,usage.get(ProviderUsage.Counter.INPUT).value);
        for(ProviderUsage.Counter counter:new ProviderUsage.Counter[]{ProviderUsage.Counter.CACHE_READ,ProviderUsage.Counter.CACHE_WRITE,ProviderUsage.Counter.TOTAL}){
            assertNull(usage.get(counter));
            JSONObject row=usage.toJson().getJSONObject("counters").getJSONObject(counter.name());
            assertTrue(row.isNull("value"));assertEquals("unknown",row.getString("source"));
        }
    }
    @Test public void malformedOrNullCountersCannotRetainAFalseKnownValue()throws Exception {
        ProviderUsage.Collector usage=new ProviderUsage.Collector("bedrock_anthropic");
        usage.observe(wire(3,6,100,20),ProviderUsage.Stage.PARTIAL);
        usage.observe(new JSONObject().put("usage",new JSONObject().put("input_tokens",JSONObject.NULL)
                .put("output_tokens",-1).put("cache_read_input_tokens","100")
                .put("cache_creation_input_tokens",new java.math.BigInteger("9223372036854775808"))),ProviderUsage.Stage.FINAL);
        for(ProviderUsage.Counter counter:new ProviderUsage.Counter[]{ProviderUsage.Counter.INPUT,ProviderUsage.Counter.OUTPUT,ProviderUsage.Counter.CACHE_READ,ProviderUsage.Counter.CACHE_WRITE,ProviderUsage.Counter.TOTAL})assertNull(usage.get(counter));
        assertEquals(3,usage.toJson().getJSONArray("invalidFields").length());
    }
    @Test public void invocationMetricsNeverSupplyOrOverwriteProviderTokenUsage()throws Exception {
        ProviderUsage.Collector usage=new ProviderUsage.Collector("bedrock_anthropic");
        JSONObject metrics=new JSONObject().put("amazon-bedrock-invocationMetrics",new JSONObject().put("inputTokenCount",999).put("outputTokenCount",888));
        usage.observe(metrics,ProviderUsage.Stage.FINAL);
        assertFalse(usage.toJson().getBoolean("reported"));assertNull(usage.get(ProviderUsage.Counter.INPUT));
        usage.observe(wire(3,6,100,20),ProviderUsage.Stage.PARTIAL);usage.observe(metrics,ProviderUsage.Stage.FINAL);
        assertEquals(3,usage.get(ProviderUsage.Counter.INPUT).value);assertEquals(6,usage.get(ProviderUsage.Counter.OUTPUT).value);
        assertEquals(ProviderUsage.Stage.PARTIAL,usage.get(ProviderUsage.Counter.OUTPUT).stage);
        assertNull(usage.get(ProviderUsage.Counter.TOTAL));assertTrue(usage.toJson().isNull("actualCost"));
    }
    @Test public void originalAnthropicAndOtherProtocolsKeepTheirOwnIdentity()throws Exception {
        ProviderUsage.Collector direct=new ProviderUsage.Collector("anthropic");
        direct.observe(wire(3,6,100,20),ProviderUsage.Stage.FINAL);
        assertEquals("anthropic",direct.toJson().getString("protocol"));assertFalse(direct.toJson().getBoolean("inputIncludesCacheRead"));
        ProviderUsage.Collector chat=new ProviderUsage.Collector("openai_chat");
        chat.observe(new JSONObject().put("usage",new JSONObject().put("prompt_tokens",10).put("completion_tokens",4).put("total_tokens",14)),ProviderUsage.Stage.FINAL);
        assertEquals("openai_chat",chat.toJson().getString("protocol"));assertTrue(chat.toJson().getBoolean("inputIncludesCacheRead"));
        assertEquals(14,chat.get(ProviderUsage.Counter.TOTAL).value);
    }
}
