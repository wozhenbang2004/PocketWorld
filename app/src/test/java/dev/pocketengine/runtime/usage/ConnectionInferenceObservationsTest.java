package dev.pocketengine.runtime.usage;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.models.ConnectionInferenceObservations;
import dev.pocketengine.runtime.models.ModelBinding;
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
