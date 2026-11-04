package dev.pocketengine.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual original-control races; no substitute for durable mailbox or provider tests. */
public final class ModelSteeringControlTest {
    private static RunControl original(){RunControl control=new RunControl();control.bindHostRun("task","run",3);return control;}
    private static boolean steer(RunControl control){return control.requestModelReplan("task","run",3);}

    @Test public void steerOnlyCancelsThisModelRequestAndKeepsOriginalRunAndToolAuthority()throws Exception {
        RunControl control=original();AtomicInteger toolCancel=new AtomicInteger(),modelCancel=new AtomicInteger();
        control.onCancel(toolCancel::incrementAndGet);long pause=control.pauseRevision();
        try(var request=control.openModelRequest(control.modelReplanRevision());var registration=request.onCancel(modelCancel::incrementAndGet)){
            assertTrue(steer(control));assertEquals(1,modelCancel.get());assertEquals(0,toolCancel.get());
            assertThrows(RunControl.ModelReplanRequested.class,request::check);
            control.check();assertFalse(control.isCancelled());assertFalse(control.isPaused());
            assertEquals(pause,control.pauseRevision());assertTrue(control.matchesHostRun("task","run",3));
        }
        try(var next=control.openModelRequest(control.modelReplanRevision())){next.check();}
        control.cancel();assertEquals(1,toolCancel.get());assertEquals(1,modelCancel.get());
    }

    @Test public void staleIdentityAndOrdinaryChecksDoNotInterruptTheRequest()throws Exception {
        RunControl control=original();AtomicInteger cancelled=new AtomicInteger();long revision=control.modelReplanRevision();
        try(var request=control.openModelRequest(revision);var registration=request.onCancel(cancelled::incrementAndGet)){
            assertFalse(control.requestModelReplan("other","run",3));assertFalse(control.requestModelReplan("task","old-run",3));
            assertFalse(control.requestModelReplan("task","run",2));assertFalse(control.requestModelReplan(null,"run",3));
            // A durable ordinary queue changes no control state; its row is read at a later turn boundary.
            control.check();request.check();assertEquals(revision,control.modelReplanRevision());assertEquals(0,cancelled.get());
        }
        assertFalse(new RunControl().requestModelReplan("task","run",3));
        assertFalse(RunControl.forOriginalRecovery("task","run",3).requestModelReplan("task","run",3));
    }

    @Test public void acceptanceBetweenMailboxPollAndTransportRegistrationCannotBeLost()throws Exception {
        RunControl control=original();long beforePoll=control.modelReplanRevision();
        assertTrue(steer(control));assertThrows(RunControl.ModelReplanRequested.class,()->control.openModelRequest(beforePoll));
        try(var request=control.openModelRequest(control.modelReplanRevision())){
            assertTrue(steer(control));AtomicInteger cancel=new AtomicInteger();
            try(var registration=request.onCancel(cancel::incrementAndGet)){assertEquals(1,cancel.get());}
        }
        assertFalse(Thread.currentThread().isInterrupted());assertFalse(control.isCancelled());
    }

    @Test public void closeUnregistersOldTransportAndCannotCancelASuccessor()throws Exception {
        RunControl control=original();AtomicInteger oldCancel=new AtomicInteger(),nextCancel=new AtomicInteger();
        var old=control.openModelRequest(control.modelReplanRevision());old.onCancel(oldCancel::incrementAndGet);old.close();
        assertTrue(steer(control));assertEquals(0,oldCancel.get());
        try(var next=control.openModelRequest(control.modelReplanRevision());var registration=next.onCancel(nextCancel::incrementAndGet)){
            next.check();assertEquals(0,nextCancel.get());control.cancel();assertEquals(1,nextCancel.get());
        }
        assertEquals(0,oldCancel.get());
    }

    @Test public void stopWinsEvenAfterSteeringAndNeverBecomesAReplan()throws Exception {
        RunControl control=original();AtomicInteger cancel=new AtomicInteger();
        try(var request=control.openModelRequest(control.modelReplanRevision());var registration=request.onCancel(cancel::incrementAndGet)){
            assertTrue(steer(control));control.cancel();assertFalse(steer(control));
            InterruptedException stopped=assertThrows(InterruptedException.class,request::check);
            assertFalse(stopped instanceof RunControl.ModelReplanRequested);assertEquals(1,cancel.get());assertTrue(control.isCancelled());
        }
    }

    @Test public void delayedWakeCannotCancelTheSuccessorWhichAlreadyIncludesThatSteer()throws Exception {
        RunControl control=original();AtomicInteger oldCancelled=new AtomicInteger(),nextCancelled=new AtomicInteger();
        Runnable delayed;
        try(var old=control.openModelRequest(control.modelReplanRevision());var registration=old.onCancel(oldCancelled::incrementAndGet)){
            Object mailboxMonitor=new Object();
            synchronized(mailboxMonitor){delayed=control.prepareModelReplan("task","run",3);assertNotNull(delayed);assertEquals(0,oldCancelled.get());}
            // The old request may complete naturally before the accepting thread is rescheduled.
            assertThrows(RunControl.ModelReplanRequested.class,old::check);
        }
        try(var next=control.openModelRequest(control.modelReplanRevision());var registration=next.onCancel(nextCancelled::incrementAndGet)){
            delayed.run();delayed.run();next.check();assertEquals(0,nextCancelled.get());assertEquals(0,oldCancelled.get());
        }
        assertFalse(control.isCancelled());
    }

    @Test public void preparingNeverRunsTransportCleanupInsideTheMailboxMonitor()throws Exception {
        RunControl control=original();Object mailboxMonitor=new Object();AtomicInteger calls=new AtomicInteger();
        try(var request=control.openModelRequest(control.modelReplanRevision());var registration=request.onCancel(()->{assertFalse(Thread.holdsLock(mailboxMonitor));calls.incrementAndGet();})){
            Runnable wake;synchronized(mailboxMonitor){wake=control.prepareModelReplan("task","run",3);assertEquals(0,calls.get());}
            wake.run();wake.run();assertEquals(1,calls.get());
        }
    }

    @Test public void steeringWakesRequestBackoffEvenIfTheOwnerPausedTheRun()throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            RunControl control=original();control.pause();CountDownLatch entered=new CountDownLatch(1);
            try(var request=control.openModelRequest(control.modelReplanRevision())){
                Future<?> waiting=worker.submit(()->{entered.countDown();request.sleep(30_000);return null;});
                assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(steer(control));
                ExecutionException stopped=assertThrows(ExecutionException.class,()->waiting.get(1,TimeUnit.SECONDS));
                assertTrue(stopped.getCause() instanceof RunControl.ModelReplanRequested);assertTrue(control.isPaused());assertFalse(control.isCancelled());
            }
        }finally{worker.shutdownNow();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}
    }

    @Test public void racingRegistrationSteeringAndStopCallsExactCleanupOnce()throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            for(int i=0;i<200;i++){
                RunControl control=original();AtomicInteger calls=new AtomicInteger();CountDownLatch start=new CountDownLatch(1);
                try(var request=control.openModelRequest(control.modelReplanRevision())){
                    Future<?> registration=workers.submit(()->{start.await();request.onCancel(calls::incrementAndGet);return null;});
                    Future<?> signal=workers.submit(()->{start.await();steer(control);control.cancel();return null;});
                    start.countDown();registration.get(1,TimeUnit.SECONDS);signal.get(1,TimeUnit.SECONDS);assertEquals(1,calls.get());
                }
            }
        }finally{workers.shutdownNow();assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS));}
    }
}
