package dev.pocketengine.runtime.usage;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Uses the original reservation aggregator; never fills a provider TOTAL counter. */
public final class BedrockTokenReservationTest {
    private static ProviderUsage.Collector observed(JSONObject counters)throws Exception {
        ProviderUsage.Collector usage=new ProviderUsage.Collector("bedrock_anthropic");
        usage.observe(new JSONObject().put("usage",counters),ProviderUsage.Stage.PARTIAL);return usage;
    }
    private static JSONObject completeCounters()throws Exception {
        return new JSONObject().put("input_tokens",3).put("output_tokens",6)
                .put("cache_read_input_tokens",100).put("cache_creation_input_tokens",20);
    }
    @Test public void onlyAllFourFinalExclusiveComponentsSettleTheOriginalBudget()throws Exception {
        ProviderUsage.Collector usage=observed(completeCounters());
        assertNull(InferenceTokenReservation.reportedTotal(usage.toJson()));
        usage.terminal("end_turn");assertNull(InferenceTokenReservation.reportedTotal(usage.toJson()));
        usage.completed();assertEquals(Long.valueOf(129),InferenceTokenReservation.reportedTotal(usage.toJson()));
        assertTrue(usage.toJson().getJSONObject("counters").getJSONObject("TOTAL").isNull("value"));
    }
    @Test public void everyMissingComponentKeepsUnknownRatherThanAssumingZero()throws Exception {
        for(String key:new String[]{"input_tokens","output_tokens","cache_read_input_tokens","cache_creation_input_tokens"}){
            JSONObject counters=completeCounters();counters.remove(key);
            ProviderUsage.Collector usage=observed(counters);usage.completed();
            assertNull(key,InferenceTokenReservation.reportedTotal(usage.toJson()));
        }
        ProviderUsage.Collector zeros=observed(new JSONObject().put("input_tokens",0).put("output_tokens",0)
                .put("cache_read_input_tokens",0).put("cache_creation_input_tokens",0));
        zeros.completed();assertEquals(Long.valueOf(0),InferenceTokenReservation.reportedTotal(zeros.toJson()));
    }
    @Test public void invalidOrPartialUsageCannotReleaseTheOriginalUnknownReservation()throws Exception {
        ProviderUsage.Collector partial=observed(completeCounters());
        assertNull(InferenceTokenReservation.reportedTotal(partial.toJson()));
        ProviderUsage.Collector malformed=observed(completeCounters().put("output_tokens",-1));malformed.completed();
        assertNull(InferenceTokenReservation.reportedTotal(malformed.toJson()));
    }
    @Test public void aggregateOverflowIsNotClampedOrReplacedWithAFreeZero()throws Exception {
        ProviderUsage.Collector usage=observed(completeCounters().put("input_tokens",Long.MAX_VALUE));usage.completed();
        assertThrows(ArithmeticException.class,()->InferenceTokenReservation.reportedTotal(usage.toJson()));
        assertNull(usage.get(ProviderUsage.Counter.TOTAL));
    }
}
