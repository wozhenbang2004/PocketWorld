package dev.pocketengine.features.local.runtime;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;

/** Deterministic native-fact state tests, not an Android thermal sensor, OEM,
 * battery, memory, process-control or measured performance test. */
public final class RuntimeLogObservationPolicyTest {
    private static void observed(RuntimeLogObservationPolicy policy,Integer status){assertTrue(policy.observe(policy.beginObservation(),status));}
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
}
