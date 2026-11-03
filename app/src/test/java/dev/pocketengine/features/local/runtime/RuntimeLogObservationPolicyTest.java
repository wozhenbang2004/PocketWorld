package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;

/** Deterministic supplied-fact state tests, not an Android sensor, OEM,
 * physical memory pressure, process-control or measured performance test. */
public final class RuntimeLogObservationPolicyTest {
    private static void memory(RuntimeLogObservationPolicy policy,Boolean low){assertTrue(policy.observeMemory(policy.beginMemoryObservation(),low,600L,1000L,100L));}
    private static void observed(RuntimeLogObservationPolicy policy,Integer status){assertTrue(policy.observe(policy.beginObservation(),status));memory(policy,false);}
    private static void bytes(RuntimeLogObservationPolicy policy,int expected)throws Exception {
        JSONObject view=policy.snapshot();assertEquals(expected,view.getInt("automaticLogPageBytes"));
        assertFalse(view.getBoolean("explicitLogLimitsChanged"));assertFalse(view.getBoolean("executionPolicyChanged"));
        assertFalse(view.getBoolean("processesStopped"));assertFalse(view.getBoolean("resourceReleaseProved"));
        assertFalse(view.getBoolean("energySavingsMeasured"));assertFalse(view.getBoolean("temperatureMeasured"));
        if(expected==8192){assertTrue(view.getString("ownerMessage").contains("8KiB"));assertFalse(view.getString("ownerMessage").contains("8MiB"));assertFalse(view.getString("ownerMessage").contains("原任务继续"));}
        else assertEquals("",view.getString("ownerMessage"));
    }
    @Test public void missingOrInvalidNativeFactsAreNotAssumedColdOrFull()throws Exception {
        AtomicLong now=new AtomicLong();try(var policy=new RuntimeLogObservationPolicy(now::get)){
            bytes(policy,8192);assertTrue(policy.snapshot().isNull("thermalStatus"));
            for(Integer unknown:new Integer[]{null,-1,7,Integer.MAX_VALUE}){observed(policy,unknown);now.addAndGet(120000);bytes(policy,8192);assertTrue(policy.snapshot().isNull("thermalStatus"));assertEquals("thermal_status_unknown",policy.snapshot().getString("reason"));}
        }
    }
    @Test public void fullDefaultsRequireThirtySecondsAndAnotherFreshColdObservation()throws Exception {
        AtomicLong now=new AtomicLong(100);try(var policy=new RuntimeLogObservationPolicy(now::get)){
            observed(policy,0);bytes(policy,8192);assertEquals(30000,policy.freshColdRecheckAfterMillis());
            now.addAndGet(29999);observed(policy,1);bytes(policy,8192);assertEquals(1,policy.freshColdRecheckAfterMillis());
            now.incrementAndGet();bytes(policy,8192);assertTrue(policy.snapshot().getBoolean("freshColdRecheckPending"));
            observed(policy,0);bytes(policy,32768);assertEquals(-1,policy.freshColdRecheckAfterMillis());
            observed(policy,2);bytes(policy,8192);assertEquals("thermal_pressure",policy.snapshot().getString("reason"));
        }
    }
    @Test public void unknownDuringCoolingOrAfterFullNeverRestoresFullByTimeAlone()throws Exception {
        AtomicLong now=new AtomicLong();try(var policy=new RuntimeLogObservationPolicy(now::get)){
            observed(policy,5);observed(policy,0);now.addAndGet(20000);observed(policy,null);now.addAndGet(120000);bytes(policy,8192);assertEquals(0,policy.snapshot().getInt("lastKnownThermalStatus"));
            observed(policy,0);now.addAndGet(30000);observed(policy,0);bytes(policy,32768);
            observed(policy,null);now.addAndGet(120000);bytes(policy,8192);assertTrue(policy.snapshot().isNull("thermalStatus"));
        }
    }
    @Test public void reheatingResetsRatherThanAccumulatesEarlierCoolingIntervals()throws Exception {
        AtomicLong now=new AtomicLong();try(var policy=new RuntimeLogObservationPolicy(now::get)){
            observed(policy,2);observed(policy,0);now.addAndGet(20000);observed(policy,3);observed(policy,1);now.addAndGet(10000);observed(policy,0);bytes(policy,8192);assertEquals(20000,policy.freshColdRecheckAfterMillis());
            now.addAndGet(20000);observed(policy,0);bytes(policy,32768);
        }
    }
    @Test public void oldOrDuplicateObservationCannotOverwriteNewerNativeState()throws Exception {
        AtomicLong now=new AtomicLong();try(var policy=new RuntimeLogObservationPolicy(now::get)){
            var old=policy.beginObservation();var newer=policy.beginObservation();assertTrue(policy.observe(newer,4));String exact=policy.snapshot().toString();
            assertFalse(policy.observe(old,0));assertFalse(policy.observe(newer,0));assertEquals(exact,policy.snapshot().toString());bytes(policy,8192);
        }
    }
    @Test public void anotherObserverGenerationAndClosedSourceCannotReviveOldState()throws Exception {
        AtomicLong now=new AtomicLong();var old=new RuntimeLogObservationPolicy(now::get);var token=old.beginObservation();old.close();
        try(var current=new RuntimeLogObservationPolicy(now::get)){
            assertFalse(old.observe(token,0));assertFalse(current.observe(token,0));assertNotEquals(old.snapshot().getString("generation"),current.snapshot().getString("generation"));
            now.addAndGet(120000);bytes(old,8192);bytes(current,8192);assertTrue(old.snapshot().getBoolean("closed"));assertFalse(current.snapshot().getBoolean("closed"));
        }
    }
    @Test public void aNonMonotonicNativeClockCannotCompleteTheCoolingProof()throws Exception {
        AtomicLong now=new AtomicLong(100);try(var policy=new RuntimeLogObservationPolicy(now::get)){
            observed(policy,0);now.set(99);observed(policy,0);bytes(policy,8192);assertTrue(policy.snapshot().isNull("thermalStatus"));assertEquals("thermal_clock_unavailable",policy.snapshot().getString("reason"));
            now.set(40000);observed(policy,0);bytes(policy,8192);assertEquals(30000,policy.freshColdRecheckAfterMillis());
        }
    }
    @Test public void healthyThermalFactsCannotFillMissingStaleOrLowMemoryFacts()throws Exception {
        AtomicLong now=new AtomicLong();try(var policy=new RuntimeLogObservationPolicy(now::get)){
            assertTrue(policy.observe(policy.beginObservation(),0));now.set(30000);assertTrue(policy.observe(policy.beginObservation(),0));bytes(policy,8192);assertTrue(policy.snapshot().isNull("memoryLow"));assertEquals("memory_not_observed",policy.snapshot().getString("reason"));
            memory(policy,false);now.addAndGet(30000);memory(policy,false);bytes(policy,32768);assertTrue(policy.snapshot().getBoolean("memoryFresh"));
            now.addAndGet(30001);bytes(policy,8192);assertTrue(policy.snapshot().isNull("memoryAvailableBytes"));assertFalse(policy.snapshot().getBoolean("memoryFresh"));assertEquals("memory_observation_stale",policy.snapshot().getString("reason"));
            memory(policy,false);now.addAndGet(30000);memory(policy,false);bytes(policy,32768);memory(policy,true);bytes(policy,8192);assertEquals("memory_pressure",policy.snapshot().getString("reason"));assertTrue(policy.snapshot().getBoolean("memoryLow"));
            now.addAndGet(120000);bytes(policy,8192);memory(policy,false);bytes(policy,8192);now.addAndGet(30000);memory(policy,false);bytes(policy,32768);
        }
    }
    @Test public void slowReadDurationCannotReplaceANewerRecoveredMemoryObservation()throws Exception {
        AtomicLong now=new AtomicLong();try(var policy=new RuntimeLogObservationPolicy(now::get)){
            policy.observe(policy.beginObservation(),0);var first=policy.beginMemoryObservation();now.set(30000);policy.observe(policy.beginObservation(),0);
            assertTrue(policy.observeMemory(first,false,600L,1000L,100L));bytes(policy,8192);assertEquals("memory_cooling",policy.snapshot().getString("reason"));
            memory(policy,false);bytes(policy,32768);
            memory(policy,true);memory(policy,false);now.addAndGet(29000);var early=policy.beginMemoryObservation();now.addAndGet(1000);assertTrue(policy.observeMemory(early,false,600L,1000L,100L));bytes(policy,8192);
            memory(policy,false);bytes(policy,32768);
        }
    }
    @Test public void invalidatedLateCrossGenerationAndClosedMemoryCannotRestoreHealthyState()throws Exception {
        AtomicLong now=new AtomicLong();try(var policy=new RuntimeLogObservationPolicy(now::get);var other=new RuntimeLogObservationPolicy(now::get)){
            observed(policy,0);now.set(30000);observed(policy,0);bytes(policy,32768);
            var old=policy.beginMemoryObservation();policy.invalidateMemory();assertFalse(policy.observeMemory(old,false,600L,1000L,100L));bytes(policy,8192);assertEquals("memory_observation_invalidated",policy.snapshot().getString("reason"));
            assertFalse(other.observeMemory(old,false,600L,1000L,100L));assertFalse(policy.observeMemory(policy.beginObservation(),false,600L,1000L,100L));
            var late=policy.beginMemoryObservation();now.addAndGet(30001);assertTrue(policy.observeMemory(late,false,600L,1000L,100L));assertFalse(policy.snapshot().getBoolean("memoryFresh"));assertEquals("memory_observation_stale",policy.snapshot().getString("memoryReason"));
            var token=policy.beginMemoryObservation();policy.close();assertFalse(policy.observeMemory(token,false,600L,1000L,100L));bytes(policy,8192);
        }
    }
    @Test public void invalidMemoryBoundsAndClockRegressionRemainUnknown()throws Exception {
        AtomicLong now=new AtomicLong(100);try(var policy=new RuntimeLogObservationPolicy(now::get)){
            for(Long[] facts:new Long[][]{{-1L,1000L,100L},{600L,0L,100L},{600L,1000L,1001L},{1001L,1000L,100L},{null,1000L,100L}}){assertTrue(policy.observeMemory(policy.beginMemoryObservation(),false,facts[0],facts[1],facts[2]));assertFalse(policy.snapshot().getBoolean("memoryFresh"));bytes(policy,8192);}
            memory(policy,false);var before=policy.beginMemoryObservation();now.set(99);assertTrue(policy.observeMemory(before,false,600L,1000L,100L));assertEquals("memory_clock_unavailable",policy.snapshot().getString("memoryReason"));assertTrue(policy.snapshot().isNull("memoryLow"));
        }
    }
}
