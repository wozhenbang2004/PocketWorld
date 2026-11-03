package dev.pocketengine.runtime.models;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class ModelProbeDeadlineTest {
    @Test public void laterPhaseReceivesOnlyRemainingBudgetAndCancelsOriginalIdentity()throws Exception{
        RunControl original=new RunControl();original.bindHostRun("original-task","original-run",2);AtomicLong nanos=new AtomicLong();
        try(var deadline=new ModelProbeDeadline(original,200,nanos::get,false)){
            assertEquals(200,deadline.remainingMillis());nanos.set(50_000_000);deadline.stage("tools");assertEquals(150,deadline.remainingMillis());
            nanos.set(200_000_000);var exceeded=assertThrows(ModelProbeDeadline.Exceeded.class,deadline::check);assertEquals("tools",exceeded.stage);assertTrue(original.isCancelled());
            assertTrue(original.matchesHostRun("original-task","original-run",2));assertThrows(ModelProbeDeadline.Exceeded.class,deadline::remainingMillis);
        }
    }
    @Test public void ownerCancellationIsNotRelabeledAsDeadline()throws Exception{
        RunControl original=new RunControl();try(var deadline=new ModelProbeDeadline(original,200,()->0,false)){original.cancel();var stopped=assertThrows(InterruptedException.class,deadline::check);assertFalse(stopped instanceof ModelProbeDeadline.Exceeded);}
    }
    @Test public void timerCancelsBlockedOriginalControlWithoutStartingAnotherRun()throws Exception{
        RunControl original=new RunControl();CountDownLatch cancelled=new CountDownLatch(1);original.onCancel(cancelled::countDown);
        try(var deadline=new ModelProbeDeadline(original,60)){assertTrue(cancelled.await(2,TimeUnit.SECONDS));assertTrue(original.isCancelled());assertThrows(ModelProbeDeadline.Exceeded.class,deadline::check);assertFalse(original.hasHostRun());}
    }
    @Test public void closingCompletedDiagnosticDisarmsItsTimer()throws Exception{
        RunControl original=new RunControl();CountDownLatch cancelled=new CountDownLatch(1);original.onCancel(cancelled::countDown);ModelProbeDeadline deadline=new ModelProbeDeadline(original,60);deadline.close();
        assertFalse(cancelled.await(180,TimeUnit.MILLISECONDS));assertFalse(original.isCancelled());assertThrows(IllegalStateException.class,deadline::check);
    }
    @Test public void invalidBudgetCannotExtendConfiguredMaximum(){assertThrows(IllegalArgumentException.class,()->new ModelProbeDeadline(new RunControl(),120001));assertThrows(IllegalArgumentException.class,()->new ModelProbeDeadline(new RunControl(),0));}
}
