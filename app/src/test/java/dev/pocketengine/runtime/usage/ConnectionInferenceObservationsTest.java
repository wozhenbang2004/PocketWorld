package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.ConnectionInferenceObservations;
import dev.pocketengine.runtime.models.ModelBinding;
import dev.pocketengine.runtime.providers.ProviderCompatProfile;
import dev.pocketengine.runtime.providers.ProviderCompatibility;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ConnectionInferenceObservationsTest {
    private static final long AT=Instant.parse("2026-11-01T10:00:00Z").toEpochMilli();
    private static final InferenceCapture.Sink NOOP=new InferenceCapture.Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r){}};
    @After public void clearObserver(){InferenceCapture.installPresentationObserver(NOOP);}
    private ModelBinding binding(String connection,long credential,long installation)throws Exception{return new ModelBinding(connection,"provider",ModelBinding.Protocol.OPENAI_CHAT,ModelBinding.Format.OPENAI,URI.create("https://models.fixture/v1/models"),"credential",credential,"installation",installation,false);}
    private ModelConfig config(ModelBinding binding,String model){return new ModelConfig("https://models.fixture/v1","fixture-private-key",model,false,4,10,"openai_chat",512).withApiConnection(binding);}
    private InferenceReceipt receipt(ModelBinding binding,String model,InferenceReceipt.Outcome outcome)throws Exception {
        final InferenceReceipt[] result={null};InferenceCapture capture=new InferenceCapture(config(binding,model),new RunControl(),new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r){result[0]=r;}}));
        capture.dispatch();if(outcome!=InferenceReceipt.Outcome.COMPLETED)capture.failure(InferenceFailure.http(429,List.of("120"),List.of(),AT));capture.finish(outcome);return result[0];
    }
    private ConnectionInferenceObservations observations(){return new ConnectionInferenceObservations(Clock.fixed(Instant.ofEpochMilli(AT),ZoneOffset.UTC));}
    private ModelBinding cacheBinding(long installation)throws Exception{return new ModelBinding("cache-connection","provider",ModelBinding.Protocol.ANTHROPIC,ModelBinding.Format.ANTHROPIC,URI.create("https://models.fixture/v1/models"),"credential",1,"installation",installation,false);}
    private InferenceReceipt cacheReceipt(ModelBinding binding,boolean supported,JSONObject usage)throws Exception {
        ModelConfig config=new ModelConfig("https://models.fixture/v1","fixture-private-key","cache-model",false,4,10,"anthropic",512).withApiConnection(binding);
        JSONObject policy=new JSONObject().put("cacheMode","EPHEMERAL");if(supported)policy.put("cacheSupport","SUPPORTED");
        config=config.withProviderCompatibility(ProviderCompatProfile.reviewed(policy,"anthropic","cache-model",null));
        JSONArray history=new JSONArray().put(new JSONObject().put("role","system").put("content","stable native source")).put(new JSONObject().put("role","user").put("content","Inspect"));
        JSONObject ordinary=new JSONObject().put("model","cache-model").put("max_tokens",512).put("system",new JSONArray().put(new JSONObject().put("type","text").put("text","stable native source"))).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","Inspect")));
        JSONObject wire=ProviderCompatibility.project(config,history,null,ordinary);
        InferenceReceipt[] result={null};InferenceCapture capture=new InferenceCapture(config,new RunControl(),new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r){result[0]=r;}}));
        capture.requestProjection(wire);capture.dispatch();if(usage!=null)capture.usage.observe(new JSONObject().put("usage",usage),ProviderUsage.Stage.FINAL);capture.finish(InferenceReceipt.Outcome.COMPLETED);return result[0];
    }
    @Test public void committedCacheHintsAndProviderCountsAreSeparateReadOnlyObservations()throws Exception {
        ModelBinding binding=cacheBinding(1);InferenceReceipt receipt=cacheReceipt(binding,true,new JSONObject().put("cache_read_input_tokens",5).put("cache_creation_input_tokens",2));String original=receipt.toJson().toString();
        ConnectionInferenceObservations observations=observations();observations.committed(receipt);JSONObject cache=observations.snapshot(binding,"cache-model").getJSONObject("lastCall").getJSONObject("cacheObservation");
        assertTrue(cache.getBoolean("hintPrepared"));assertTrue(cache.getBoolean("dispatchAttempted"));assertEquals("unknown",cache.getString("hintDelivery"));assertFalse(cache.has("hintSent"));
        assertTrue(cache.getBoolean("usageReported"));assertEquals("provider_reported",cache.getJSONObject("counters").getJSONObject("CACHE_READ").getString("source"));assertEquals(5,cache.getJSONObject("counters").getJSONObject("CACHE_READ").getLong("value"));assertEquals(2,cache.getJSONObject("counters").getJSONObject("CACHE_WRITE").getLong("value"));
        assertFalse(cache.getBoolean("inputIncludesCacheRead"));assertFalse(cache.getBoolean("inputIncludesCacheWrite"));assertFalse(cache.getBoolean("cacheHitInferred"));assertEquals("provider_counters_not_a_bill",cache.getString("usageMeaning"));
        assertFalse(cache.toString().contains("fixture-private-key"));assertFalse(cache.toString().contains("stable native source"));assertEquals(original,receipt.toJson().toString());
        cache.getJSONObject("counters").getJSONObject("CACHE_READ").put("value",999);assertEquals(5,observations.snapshot(binding,"cache-model").getJSONObject("lastCall").getJSONObject("cacheObservation").getJSONObject("counters").getJSONObject("CACHE_READ").getLong("value"));
    }
    @Test public void missingUsageAndUnknownCacheSupportStayExplicitlyUnknown()throws Exception {
        ModelBinding binding=cacheBinding(1);ConnectionInferenceObservations observations=observations();observations.committed(cacheReceipt(binding,false,null));JSONObject cache=observations.snapshot(binding,"cache-model").getJSONObject("lastCall").getJSONObject("cacheObservation");
        assertFalse(cache.getBoolean("hintPrepared"));assertEquals("not_requested",cache.getString("hintDelivery"));assertFalse(cache.getBoolean("usageReported"));assertFalse(cache.getBoolean("cacheHitInferred"));
        for(String key:List.of("CACHE_READ","CACHE_WRITE","CACHE_WRITE_5M","CACHE_WRITE_1H")){JSONObject counter=cache.getJSONObject("counters").getJSONObject(key);assertTrue(counter.has("value"));assertTrue(counter.isNull("value"));assertEquals("unknown",counter.getString("source"));assertTrue(counter.isNull("sourcePath"));}
    }
    @Test public void explicitProviderZeroIsRetainedAndDoesNotFillMissingCacheCounters()throws Exception {
        ModelBinding binding=cacheBinding(1);ConnectionInferenceObservations observations=observations();observations.committed(cacheReceipt(binding,false,new JSONObject().put("cache_read_input_tokens",0).put("cache_creation",new JSONObject().put("ephemeral_5m_input_tokens",3).put("ephemeral_1h_input_tokens",4))));
        JSONObject counts=observations.snapshot(binding,"cache-model").getJSONObject("lastCall").getJSONObject("cacheObservation").getJSONObject("counters");
        assertEquals(0,counts.getJSONObject("CACHE_READ").getLong("value"));assertEquals("provider_reported",counts.getJSONObject("CACHE_READ").getString("source"));assertTrue(counts.getJSONObject("CACHE_WRITE").isNull("value"));
        assertEquals(3,counts.getJSONObject("CACHE_WRITE_5M").getLong("value"));assertEquals(4,counts.getJSONObject("CACHE_WRITE_1H").getLong("value"));
    }
    @Test public void cacheEvidenceRequiresOriginalTerminalCommitAndCurrentExactRoute()throws Exception {
        ModelBinding binding=cacheBinding(1);ConnectionInferenceObservations observations=observations();InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r)throws Exception{observations.committed(r);}});
        InferenceCapture failed=new InferenceCapture(new ModelConfig("https://models.fixture/v1","fixture-private-key","cache-model",false,4,10,"anthropic",512).withApiConnection(binding),new RunControl(),new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r)throws Exception{throw new java.io.IOException("original durable sink failed");}}));
        failed.dispatch();failed.usage.observe(new JSONObject().put("usage",new JSONObject().put("cache_read_input_tokens",5)),ProviderUsage.Stage.FINAL);assertFalse(observations.snapshot(binding,"cache-model").getBoolean("observed"));assertThrows(java.io.IOException.class,()->failed.finish(InferenceReceipt.Outcome.COMPLETED));assertFalse(observations.snapshot(binding,"cache-model").getBoolean("observed"));
        cacheReceipt(binding,true,new JSONObject().put("cache_read_input_tokens",5));assertTrue(observations.snapshot(binding,"cache-model").getBoolean("observed"));
        assertFalse(observations.snapshot(cacheBinding(2),"cache-model").getBoolean("observed"));assertFalse(observations.snapshot(binding,"other-model").getBoolean("observed"));assertFalse(observations().snapshot(binding,"cache-model").getBoolean("observed"));
    }
    @Test public void cacheProjectionRejectsMalformedClaimsAndNeverCopiesUnexpectedMetadata()throws Exception {
        ModelBinding binding=cacheBinding(1);InferenceReceipt original=cacheReceipt(binding,true,new JSONObject().put("cache_read_input_tokens",5).put("cache_creation_input_tokens",2));JSONObject raw=original.toJson(),route=raw.getJSONObject("route"),usage=raw.getJSONObject("usage");
        route.getJSONObject("providerProjection").put("prompt","never-export-native-prompt").put("hintPrepared","true").put("hintDelivery","delivered").put("apiKey","never-export-key");
        usage.getJSONObject("counters").getJSONObject("CACHE_READ").put("value","0");usage.getJSONObject("counters").getJSONObject("CACHE_WRITE").put("sourcePath","never-export-key");
        InferenceReceipt malformed=new InferenceReceipt(original.callId,original.context,original.outcome,original.startedAt,original.finishedAt,original.elapsedNanos,route,usage);ConnectionInferenceObservations observations=observations();observations.committed(malformed);
        JSONObject cache=observations.snapshot(binding,"cache-model").getJSONObject("lastCall").getJSONObject("cacheObservation");assertFalse(cache.toString().contains("never-export"));assertTrue(cache.isNull("hintPrepared"));assertEquals("unknown",cache.getString("hintDelivery"));
        for(String key:List.of("CACHE_READ","CACHE_WRITE")){assertTrue(cache.getJSONObject("counters").getJSONObject(key).isNull("value"));assertEquals("unknown",cache.getJSONObject("counters").getJSONObject(key).getString("source"));}
    }
    @Test public void nativeFailedReceiptIsExactConnectionCredentialInstallationAndModelNotGlobalHealth()throws Exception {
        ModelBinding binding=binding("connection",1,1);ConnectionInferenceObservations observations=observations();InferenceReceipt receipt=receipt(binding,"model-a",InferenceReceipt.Outcome.FAILED);observations.committed(receipt);
        JSONObject row=observations.snapshot(binding,"model-a");assertTrue(row.getBoolean("observed"));assertEquals(receipt.callId,row.getJSONObject("lastCall").getString("callId"));assertEquals("FAILED",row.getJSONObject("lastCall").getString("outcome"));assertFalse(row.getBoolean("healthVerified"));assertFalse(row.getBoolean("automaticRetry"));assertFalse(row.getBoolean("quotaKnown"));
        assertFalse(observations.snapshot(binding,"model-b").getBoolean("observed"));assertFalse(observations.snapshot(binding("connection",2,1),"model-a").getBoolean("observed"));assertFalse(observations.snapshot(binding("connection",1,2),"model-a").getBoolean("observed"));assertFalse(observations.snapshot(binding("foreign",1,1),"model-a").getBoolean("observed"));
    }
    @Test public void aLaterCompletedCallDoesNotEraseEarlierFailureOrClaimAllFutureCallsHealthy()throws Exception {
        ModelBinding binding=binding("connection",1,1);ConnectionInferenceObservations observations=observations();InferenceReceipt failed=receipt(binding,"model",InferenceReceipt.Outcome.FAILED),completed=receipt(binding,"model",InferenceReceipt.Outcome.COMPLETED);observations.committed(failed);observations.committed(completed);JSONObject row=observations.snapshot(binding,"model");
        assertEquals(completed.callId,row.getJSONObject("lastCall").getString("callId"));assertEquals(failed.callId,row.getJSONObject("lastFailure").getString("callId"));assertFalse(row.getBoolean("healthVerified"));assertEquals("VALID",row.getJSONObject("lastFailure").getJSONObject("failure").getJSONObject("retry").getString("status"));
    }
    @Test public void finiteProjectionEvictionAndReopenAreUnknownNotZerosOrRecoveredHealth()throws Exception {
        ConnectionInferenceObservations observations=observations();for(int i=0;i<=ConnectionInferenceObservations.MAX_ROUTES;i++){ModelBinding binding=binding("connection-"+i,1,1);observations.committed(receipt(binding,"model",InferenceReceipt.Outcome.FAILED));}
        assertFalse(observations.snapshot(binding("connection-0",1,1),"model").getBoolean("observed"));assertTrue(observations.snapshot(binding("connection-32",1,1),"model").getBoolean("observed"));assertFalse(observations().snapshot(binding("connection-32",1,1),"model").getBoolean("observed"));assertFalse(observations.snapshot(binding("connection-0",1,1),"model").has("remainingQuota"));
    }
    @Test public void observerRunsOnlyAfterOriginalSinkAndFailureCannotEraseCommittedTerminal()throws Exception {
        AtomicInteger observed=new AtomicInteger(),committed=new AtomicInteger();InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r){assertEquals(1,committed.get());observed.incrementAndGet();throw new IllegalStateException("display failed");}});
        InferenceCapture capture=new InferenceCapture(config(binding("connection",1,1),"model"),new RunControl(),new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r){committed.incrementAndGet();}}));capture.dispatch();capture.finish(InferenceReceipt.Outcome.COMPLETED);assertEquals(1,committed.get());assertEquals(1,observed.get());
        InferenceCapture failed=new InferenceCapture(config(binding("connection",1,1),"model"),new RunControl(),new InferenceCapture.Target(InferenceReceipt.Context.unattributed(),new InferenceCapture.Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r)throws Exception{throw new java.io.IOException("durable sink failed");}}));failed.dispatch();assertThrows(java.io.IOException.class,()->failed.finish(InferenceReceipt.Outcome.FAILED));assertEquals(1,observed.get());
    }
    @Test public void observerIsPinnedWhenCallIsCreatedNotReboundWhenGlobalServiceChanges()throws Exception {
        AtomicInteger a=new AtomicInteger(),b=new AtomicInteger();InferenceCapture.Sink first=new InferenceCapture.Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r){a.incrementAndGet();}},second=new InferenceCapture.Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r){b.incrementAndGet();}};
        InferenceCapture.installPresentationObserver(first);InferenceCapture original=new InferenceCapture(config(binding("connection",1,1),"model"),new RunControl(),InferenceCapture.unattributed());InferenceCapture.installPresentationObserver(second);original.dispatch();original.finish(InferenceReceipt.Outcome.FAILED);assertEquals(1,a.get());assertEquals(0,b.get());receipt(binding("connection",1,1),"model",InferenceReceipt.Outcome.COMPLETED);assertEquals(1,b.get());
    }
    @Test public void projectionConflictingFailureDetailsDoNotSilentlyReplaceOneCallOrUsage()throws Exception {
        JSONObject first=receipt(binding("connection",1,1),"model",InferenceReceipt.Outcome.FAILED).toJson(),different=new JSONObject(first.toString());different.put("failure",InferenceFailure.http(401,List.of(),List.of(),AT).toJson());JSONObject projected=InferenceProjection.fromEvents(new JSONArray().put(first).put(different));assertEquals(1,projected.getInt("attempts"));assertEquals(1,projected.getInt("conflictingCalls"));assertEquals(0,projected.getInt("completed"));assertTrue(projected.getJSONArray("receipts").getJSONObject(0).getBoolean("projectionConflict"));
    }
    @Test public void redactedRouteIdentityCannotEnterAnotherVisibleModelObservation()throws Exception {
        ModelBinding binding=binding("connection",1,1);ConnectionInferenceObservations observations=observations();InferenceReceipt receipt=receipt(binding,"fixture-private-key",InferenceReceipt.Outcome.FAILED);assertTrue(receipt.toJson().getJSONObject("route").getBoolean("identityRedacted"));assertFalse(receipt.toJson().toString().contains("fixture-private-key"));observations.committed(receipt);assertFalse(observations.snapshot(binding,"fixture-private-key").getBoolean("observed"));
    }
}
