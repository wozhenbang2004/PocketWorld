package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.ModelBinding;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic quote values are deterministic arithmetic fixtures, not live provider prices. */
public final class InferencePricingTest {
    private static ModelBinding binding(String protocol){return new ModelBinding("native-connection",protocol,ModelBinding.Protocol.valueOf(protocol.toUpperCase()),protocol.equals("anthropic")?ModelBinding.Format.ANTHROPIC:protocol.equals("gemini")?ModelBinding.Format.GEMINI:ModelBinding.Format.OPENAI,URI.create("https://price.fixture/models"),"native-credential",2,"native-install",3,false);}
    private static ModelPrice price(String protocol,String currency,Map<ModelPrice.Rate,BigDecimal> rates)throws Exception{return new ModelPrice(binding(protocol),"actual-model",currency,ModelPrice.Source.OWNER_REVIEWED,"https://price.fixture/actual","fixture-quote-not-live",1000,0,0,rates);}
    private static EnumMap<ModelPrice.Rate,BigDecimal> rates(String... pairs){EnumMap<ModelPrice.Rate,BigDecimal> rates=new EnumMap<>(ModelPrice.Rate.class);for(int i=0;i<pairs.length;i+=2)rates.put(ModelPrice.Rate.valueOf(pairs[i]),new BigDecimal(pairs[i+1]));return rates;}
    private static ProviderUsage.Collector collect(String protocol,JSONObject usage,ProviderUsage.Stage stage)throws Exception{ProviderUsage.Collector c=new ProviderUsage.Collector(protocol);c.observe(new JSONObject().put(protocol.equals("gemini")?"usageMetadata":"usage",usage),stage);return c;}
    private static JSONObject openAi(long input,long output,Long cache,Long reasoning)throws Exception{JSONObject u=new JSONObject().put("prompt_tokens",input).put("completion_tokens",output);if(cache!=null)u.put("prompt_tokens_details",new JSONObject().put("cached_tokens",cache));if(reasoning!=null)u.put("completion_tokens_details",new JSONObject().put("reasoning_tokens",reasoning));return u;}
    private static void cost(String expected,JSONObject estimate)throws Exception{assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(estimate.getString("estimatedTokenCost"))));}
    private static ModelConfig config(){return new ModelConfig("https://price.fixture","secret-never-in-price","actual-model",false,5,10).withApiConnection(binding("openai_chat"));}
    private static InferenceCapture capture(ModelPrice quote,List<InferenceReceipt> receipts)throws Exception{InferencePricing.installHostSource(config->quote);return new InferenceCapture(config(),new RunControl(),new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){receipts.add(r);}public void finished(InferenceReceipt r){receipts.add(r);}}));}
    @Test public void exactDecimalQuoteRoundTripAndCurrencyVersionAreNotDoublesOrImplicitZero()throws Exception{
        ModelPrice p=price("openai_chat","USD",rates("INPUT","0.123456789012345678","OUTPUT","0"));ModelPrice read=ModelPrice.fromJson(p.toJson());assertEquals(p.priceVersion,read.priceVersion);assertEquals(new BigDecimal("0.123456789012345678"),read.rate(ModelPrice.Rate.INPUT));assertEquals(BigDecimal.ZERO,read.rate(ModelPrice.Rate.OUTPUT));assertNull(read.rate(ModelPrice.Rate.CACHE_READ));
        JSONObject corrupt=p.toJson();corrupt.getJSONObject("rates").put("INPUT","999");assertThrows(SecurityException.class,()->ModelPrice.fromJson(corrupt));
    }
    @Test public void ownerPriceRequiresExplicitStringRatesSourceAndIntegerDates()throws Exception{
        JSONObject input=new JSONObject().put("currency","USD").put("sourceRef","https://price.fixture/actual").put("sourceVersion","fixture").put("rates",new JSONObject().put("INPUT",1.5));assertThrows(IllegalArgumentException.class,()->ModelPrice.owner(binding("openai_chat"),"actual-model",input,1000));
        input.getJSONObject("rates").put("INPUT","-1");assertThrows(IllegalArgumentException.class,()->ModelPrice.owner(binding("openai_chat"),"actual-model",input,1000));input.getJSONObject("rates").put("INPUT","1");input.put("effectiveFrom","1000");assertThrows(IllegalArgumentException.class,()->ModelPrice.owner(binding("openai_chat"),"actual-model",input,1000));input.remove("effectiveFrom");input.put("sourceRef","https://price.fixture/?key=must-not-save");assertThrows(IllegalArgumentException.class,()->ModelPrice.owner(binding("openai_chat"),"actual-model",input,1000));
    }
    @Test public void openAiCachedInputIsSubtractedOnceAndReasoningIsAlreadyInsideOutput()throws Exception{
        ModelPrice p=price("openai_chat","USD",rates("INPUT","2","OUTPUT","4","CACHE_READ","0.2","CACHE_WRITE","2"));JSONObject u=collect("openai_chat",openAi(100,10,20L,5L),ProviderUsage.Stage.FINAL).toJson();JSONObject e=InferencePricing.estimate(u,p);cost("0.000204",e);assertTrue(e.getBoolean("allTokenComponentsPriced"));assertTrue(e.getBoolean("finalCounters"));assertEquals(3,e.getJSONArray("lines").length());
    }
    @Test public void missingCacheUsageOrPriceIsUnknownRatherThanFreeOrFullInputChargedAgain()throws Exception{
        ModelPrice p=price("openai_chat","USD",rates("INPUT","2","OUTPUT","4","CACHE_READ","0.2","CACHE_WRITE","2"));JSONObject e=InferencePricing.estimate(collect("openai_chat",openAi(100,10,null,null),ProviderUsage.Stage.FINAL).toJson(),p);assertTrue(e.isNull("estimatedTokenCost"));assertEquals(0,new BigDecimal("0.00004").compareTo(new BigDecimal(e.getString("knownTokenSubtotal"))));assertFalse(e.getBoolean("allTokenComponentsPriced"));
    }
    @Test public void anthropicInputReadAndTwoWriteTtlsAreDisjointAndReasoningIsNotAddedTwice()throws Exception{
        ModelPrice p=price("anthropic","USD",rates("INPUT","2","OUTPUT","4","CACHE_READ","0.2","CACHE_WRITE_5M","2.5","CACHE_WRITE_1H","4"));JSONObject u=new JSONObject().put("input_tokens",10).put("output_tokens",3).put("cache_read_input_tokens",100).put("cache_creation_input_tokens",6).put("cache_creation",new JSONObject().put("ephemeral_5m_input_tokens",4).put("ephemeral_1h_input_tokens",2));cost("0.000070",InferencePricing.estimate(collect("anthropic",u,ProviderUsage.Stage.FINAL).toJson(),p));
    }
    @Test public void missingOrConflictingAnthropicWriteTtlsNeverDefaultToFiveMinutes()throws Exception{
        ModelPrice p=price("anthropic","USD",rates("INPUT","2","OUTPUT","4","CACHE_READ","0.2","CACHE_WRITE_5M","2.5","CACHE_WRITE_1H","4"));JSONObject u=new JSONObject().put("input_tokens",10).put("output_tokens",3).put("cache_read_input_tokens",100).put("cache_creation_input_tokens",6);
        JSONObject e=InferencePricing.estimate(collect("anthropic",u,ProviderUsage.Stage.FINAL).toJson(),p);assertTrue(e.isNull("estimatedTokenCost"));assertEquals(0,new BigDecimal("0.000052").compareTo(new BigDecimal(e.getString("knownTokenSubtotal"))));u.put("cache_creation",new JSONObject().put("ephemeral_5m_input_tokens",4).put("ephemeral_1h_input_tokens",1));assertTrue(InferencePricing.estimate(collect("anthropic",u,ProviderUsage.Stage.FINAL).toJson(),p).isNull("estimatedTokenCost"));
    }
    @Test public void geminiCandidatesThoughtsAndToolPromptAreEachPricedExactlyOnce()throws Exception{
        ModelPrice p=price("gemini","USD",rates("INPUT","2","OUTPUT","4","CACHE_READ","0.2"));JSONObject u=new JSONObject().put("promptTokenCount",100).put("cachedContentTokenCount",20).put("candidatesTokenCount",10).put("thoughtsTokenCount",30).put("toolUsePromptTokenCount",5).put("totalTokenCount",145);cost("0.000334",InferencePricing.estimate(collect("gemini",u,ProviderUsage.Stage.FINAL).toJson(),p));
    }
    @Test public void explicitZeroQuantityDiffersFromUnknownUsageAndPartialNeverClaimsFinal()throws Exception{
        ModelPrice p=price("anthropic","USD",rates("INPUT","2","OUTPUT","4","CACHE_READ","0.2"));JSONObject u=new JSONObject().put("input_tokens",0).put("output_tokens",0).put("cache_read_input_tokens",0).put("cache_creation_input_tokens",0);cost("0",InferencePricing.estimate(collect("anthropic",u,ProviderUsage.Stage.PARTIAL).toJson(),p));assertFalse(InferencePricing.estimate(collect("anthropic",u,ProviderUsage.Stage.PARTIAL).toJson(),p).getBoolean("finalCounters"));assertTrue(InferencePricing.estimate(new ProviderUsage.Collector("anthropic").toJson(),p).isNull("knownTokenSubtotal"));
    }
    @Test public void physicalCaptureFreezesQuoteBeforeStartedAndLaterQuoteChangesDoNotReprice()throws Exception{
        ModelPrice old=price("openai_chat","USD",rates("INPUT","2","OUTPUT","4","CACHE_READ","2","CACHE_WRITE","2")),next=price("openai_chat","USD",rates("INPUT","99","OUTPUT","99","CACHE_READ","99","CACHE_WRITE","99"));AtomicReference<ModelPrice> selected=new AtomicReference<>(old);InferencePricing.installHostSource(config->selected.get());List<InferenceReceipt> receipts=new ArrayList<>();
        InferenceCapture c=new InferenceCapture(config(),new RunControl(),new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){receipts.add(r);}public void finished(InferenceReceipt r){receipts.add(r);}}));c.dispatch();selected.set(next);c.usage.observe(new JSONObject().put("usage",openAi(100,10,0L,0L)),ProviderUsage.Stage.FINAL);c.finish(InferenceReceipt.Outcome.COMPLETED);
        assertEquals(old.priceVersion,receipts.get(0).toJson().getJSONObject("usage").getString("priceVersion"));JSONObject saved=receipts.get(1).toJson();assertEquals(old.priceVersion,saved.getJSONObject("usage").getString("priceVersion"));cost("0.00024",saved.getJSONObject("usage").getJSONObject("estimate"));JSONObject projection=InferenceProjection.fromEvents(new JSONArray().put(saved).put(saved));assertEquals(1,projection.getInt("attempts"));assertEquals(1,projection.getJSONObject("money").getJSONArray("currencies").getJSONObject(0).getInt("calls"));
    }
    @Test public void unavailableOptionalPriceCannotEraseActualCounterOrFinishedReceipt()throws Exception{
        InferencePricing.installHostSource(config->{throw new IllegalStateException("optional price unavailable");});List<InferenceReceipt> receipts=new ArrayList<>();InferenceCapture c=new InferenceCapture(config(),new RunControl(),new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){receipts.add(r);}public void finished(InferenceReceipt r){receipts.add(r);}}));c.dispatch();c.usage.observe(new JSONObject().put("usage",openAi(7,2,null,null)),ProviderUsage.Stage.FINAL);c.finish(InferenceReceipt.Outcome.COMPLETED);
        assertEquals(2,receipts.size());JSONObject u=receipts.get(1).toJson().getJSONObject("usage");assertEquals(7,u.getJSONObject("counters").getJSONObject("INPUT").getLong("value"));assertTrue(u.isNull("estimatedCost"));assertFalse(u.getJSONObject("pricing").getBoolean("known"));
    }
    @Test public void currencyGroupsNeverMixAndConflictingNativeCallsAreExcluded()throws Exception{
        JSONArray events=new JSONArray();for(String currency:List.of("USD","EUR")){List<InferenceReceipt> receipts=new ArrayList<>();InferenceCapture c=capture(price("openai_chat",currency,rates("INPUT","2","OUTPUT","4","CACHE_READ","2","CACHE_WRITE","2")),receipts);c.dispatch();c.usage.observe(new JSONObject().put("usage",openAi(100,10,0L,0L)),ProviderUsage.Stage.FINAL);c.finish(InferenceReceipt.Outcome.COMPLETED);events.put(receipts.get(1).toJson());}
        JSONObject p=InferenceProjection.fromEvents(events);assertEquals(2,p.getJSONObject("money").getJSONArray("currencies").length());assertTrue(p.isNull("actualCost"));JSONObject conflict=new JSONObject(events.getJSONObject(0).toString());conflict.getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").put("value",999);events.put(conflict);assertEquals(1,InferenceProjection.fromEvents(events).getJSONObject("money").getInt("conflictingCalls"));
    }
    @Test public void accountQuotaAndApiQuoteNeverBecomeEachOtherOrHardBudget()throws Exception{
        ModelConfig account=config().withAccountModel(ModelConfig.AccountModelRef.fromJson(new JSONObject().put("provider","chatgpt_plan").put("accountId","native-registration").put("accountVersion",2).put("modelId","actual-model")));InferencePricing.installHostSource(config->{throw new AssertionError("account must not request API price");});JSONObject frozen=InferencePricing.freeze(account,2000);assertFalse(frozen.getBoolean("known"));assertEquals("subscription_is_not_api_pricing",frozen.getString("reason"));assertTrue(InferencePricing.apply(new ProviderUsage.Collector("openai_responses").toJson(),frozen).isNull("subscriptionRemaining"));
    }
    @Test public void documentedCompatibleCacheWriteCounterIsPricedWithoutRecountingPromptTokens()throws Exception{
        ModelPrice p=price("openai_chat","USD",rates("INPUT","2","OUTPUT","4","CACHE_READ","0.2","CACHE_WRITE","2.5"));JSONObject u=openAi(100,10,20L,0L);u.getJSONObject("prompt_tokens_details").put("cache_write_tokens",30);
        ProviderUsage.Collector observed=collect("openai_chat",u,ProviderUsage.Stage.FINAL);assertEquals(30,observed.get(ProviderUsage.Counter.CACHE_WRITE).value);cost("0.000219",InferencePricing.estimate(observed.toJson(),p));
    }
}
