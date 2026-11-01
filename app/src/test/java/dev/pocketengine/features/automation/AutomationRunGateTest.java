package dev.pocketengine.features.automation;

import dev.pocketengine.core.RunControl;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual JVM control/evaluator tests. These do not claim Android, SQLite or Runner execution. */
public final class AutomationRunGateTest {
    private static JSONObject request(JSONObject policy,long scheduled,long deadline)throws Exception{return new JSONObject().put("modelObservationConsent",true).put("scheduledAt",scheduled).put("validUntil",deadline).put("runPolicy",policy);}
    private static RunControl original(){RunControl c=new RunControl();c.bindHostRun("original-task","original-run",7);return c;}
    @Test public void lostNetworkBlocksEntryWithoutCancellationAndRestoresTheSameRunAfterRecheck()throws Exception{
        RunControl c=original();AtomicLong now=new AtomicLong(5000);AtomicBoolean online=new AtomicBoolean(false);AtomicInteger dispatches=new AtomicInteger(),cancellations=new AtomicInteger();c.onCancel(cancellations::incrementAndGet);
        AutomationRunGate.bind(c,request(new JSONObject().put("requiresNetwork",true),1000,10000),at->new AutomationConditions.Snapshot(at,online.get()?"validated":"disconnected","",true,90,""),now::get);
        long prior=c.pauseRevision();assertThrows(RunControl.ConditionWaitException.class,()->c.dispatchAtPauseRevision(prior,dispatches::incrementAndGet));
        assertTrue(c.isPaused());assertFalse(c.isCancelled());assertEquals(0,dispatches.get());assertEquals(0,cancellations.get());assertEquals("run_conditions",c.conditionWait().reason());assertTrue(c.conditionWait().automatic());
        online.set(true);assertThrows(SecurityException.class,()->c.dispatchAtPauseRevision(c.pauseRevision(),dispatches::incrementAndGet)); // Conditions alone do not grant dispatch.
        c.resume();c.check();assertNull(c.conditionWait());assertThrows(SecurityException.class,()->c.dispatchAtPauseRevision(prior,dispatches::incrementAndGet));
        c.dispatchAtPauseRevision(c.pauseRevision(),dispatches::incrementAndGet);assertEquals(1,dispatches.get());assertEquals(0,cancellations.get());assertTrue(c.matchesHostRun("original-task","original-run",7));
    }
    @Test public void chargingBatteryAndUnavailableObservationsWaitRatherThanRevoke()throws Exception{
        RunControl c=original();AtomicBoolean ready=new AtomicBoolean(false);
        AutomationRunGate.bind(c,request(new JSONObject().put("requiresCharging",true).put("minBatteryPercent",80),1000,10000),at->new AutomationConditions.Snapshot(at,"validated","",ready.get(),ready.get()?90:20,""),()->5000);
        assertThrows(RunControl.ConditionWaitException.class,c::check);assertFalse(c.isCancelled());ready.set(true);c.resume();c.check();
        AutomationRunGate.bind(c,request(new JSONObject().put("requiresNetwork",true),1000,10000),at->{throw new java.io.IOException("native observation unavailable");},()->5000);
        assertThrows(RunControl.ConditionWaitException.class,c::check);assertFalse(c.isCancelled());
    }
    @Test public void expiryStillPermanentlyStopsAWaitingRun()throws Exception{
        RunControl c=original();AtomicLong now=new AtomicLong(5000);
        AutomationRunGate.bind(c,request(new JSONObject().put("requiresNetwork",true),1000,10000),at->AutomationConditions.unavailable(at),now::get);
        assertThrows(RunControl.ConditionWaitException.class,c::check);now.set(10000);
        InterruptedException stopped=assertThrows(InterruptedException.class,c::check);assertFalse(stopped instanceof RunControl.ConditionWaitException);assertTrue(c.isCancelled());assertNull(c.conditionWait());
        c.resume();assertThrows(InterruptedException.class,c::check);
    }
    @Test public void retainedRevocationWinsOverThePrimaryTemporaryCondition()throws Exception{
        RunControl c=original();AutomationRunGate.bind(c,request(new JSONObject().put("requiresNetwork",true),1000,10000),at->AutomationConditions.unavailable(at),()->5000);
        try(AutoCloseable retained=c.retainAuthorityCheck(()->{throw new SecurityException("scope revoked");})){
            InterruptedException stopped=assertThrows(InterruptedException.class,c::check);assertFalse(stopped instanceof RunControl.ConditionWaitException);assertTrue(c.isCancelled());assertNull(c.conditionWait());
        }
    }
    @Test public void anUnrelatedAuthorityFailureIsNeverChangedIntoWaiting()throws Exception{
        RunControl c=original();c.setAuthorityCheck(()->{throw new java.io.IOException("authority read failed");});
        InterruptedException stopped=assertThrows(InterruptedException.class,c::check);assertTrue(stopped.getCause() instanceof java.io.IOException);assertTrue(c.isCancelled());assertNull(c.conditionWait());
    }
    @Test public void anotherControlsWaitCannotDowngradeRevocationEvenBehindAnExistingWait()throws Exception{
        RunControl c=original(),other=original();c.setAuthorityCheck(()->c.waitForCondition("run_conditions",10000));
        try(AutoCloseable retained=c.retainAuthorityCheck(()->other.waitForCondition("run_conditions",9000))){
            InterruptedException stopped=assertThrows(InterruptedException.class,c::check);assertFalse(stopped instanceof RunControl.ConditionWaitException);assertTrue(c.isCancelled());
        }
    }
    @Test public void ownerPauseRemainsAnExplicitResumeBoundary()throws Exception{
        RunControl c=original();c.pause();c.setAuthorityCheck(()->c.waitForCondition("run_conditions",10000));
        assertThrows(RunControl.ConditionWaitException.class,c::check);assertFalse(c.conditionWait().automatic());assertFalse(c.isCancelled());
    }
    @Test public void explicitCancellationCannotBeUndoneByConditionsRecovering()throws Exception{
        RunControl c=original();AtomicInteger cancellations=new AtomicInteger();c.onCancel(cancellations::incrementAndGet);c.setAuthorityCheck(()->c.waitForCondition("run_conditions",10000));
        assertThrows(RunControl.ConditionWaitException.class,c::check);c.cancel();c.cancel();c.setAuthorityCheck(()->{});c.resume();
        assertThrows(InterruptedException.class,c::check);assertEquals(1,cancellations.get());assertNull(c.conditionWait());
    }
    @Test public void futureStartAndQuietHoursWaitWithinTheSameReviewedWindow()throws Exception{
        RunControl c=original();AtomicLong now=new AtomicLong(5000);AutomationRunGate.bind(c,request(new JSONObject(),6000,10000),null,now::get);
        assertThrows(RunControl.ConditionWaitException.class,c::check);assertEquals("not_due",c.conditionWait().reason());now.set(7000);c.resume();c.check();
        long night=Instant.parse("2026-10-31T23:00:00Z").toEpochMilli();now.set(night);
        JSONObject policy=new JSONObject().put("zoneId","UTC").put("quietHours",new JSONObject().put("start","22:00").put("end","08:00"));
        AutomationRunGate.bind(c,request(policy,night-3600000,night+86400000),null,now::get);assertThrows(RunControl.ConditionWaitException.class,c::check);assertEquals("quiet_hours",c.conditionWait().reason());
        now.set(Instant.parse("2026-11-01T09:00:00Z").toEpochMilli());c.resume();c.check();assertTrue(c.matchesHostRun("original-task","original-run",7));
    }
    @Test public void nativeWaitCannotBeRecordedWithoutEnteringItsOwnAuthorityCheck()throws Exception{
        RunControl c=original();assertThrows(RunControl.ConditionWaitException.class,()->c.waitForCondition("run_conditions",10000));assertNull(c.conditionWait());assertFalse(c.isPaused());
    }
    @Test public void multipleTemporaryAuthoritiesRetainTheEarliestWindow()throws Exception{
        RunControl c=original();c.setAuthorityCheck(()->c.waitForCondition("run_conditions",10000));
        try(AutoCloseable retained=c.retainAuthorityCheck(()->c.waitForCondition("run_conditions",9000))){assertThrows(RunControl.ConditionWaitException.class,c::check);assertEquals(9000,c.conditionWait().validUntil());assertFalse(c.isCancelled());}
    }
    @Test public void missingModelConsentCannotInstallAWeakerGate()throws Exception{
        RunControl c=original();c.setAuthorityCheck(()->{throw new SecurityException("original authority");});
        assertThrows(SecurityException.class,()->AutomationRunGate.bind(c,request(new JSONObject(),1000,10000).put("modelObservationConsent",false),null,()->5000));
        assertThrows(InterruptedException.class,c::check);assertTrue(c.isCancelled());
    }
}
