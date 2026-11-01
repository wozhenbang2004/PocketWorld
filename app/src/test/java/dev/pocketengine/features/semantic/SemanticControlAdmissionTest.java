package dev.pocketengine.features.semantic;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class SemanticControlAdmissionTest {
    @Test public void reentrantAuthorityPauseResumeInvalidatesOldRevision()throws Exception {
        RunControl control=new RunControl();AtomicInteger effects=new AtomicInteger();long expected=control.pauseRevision();
        control.setAuthorityCheck(()->{control.pause();control.resume();});
        assertThrows(SecurityException.class,()->control.dispatchAtPauseRevision(expected,effects::incrementAndGet));assertEquals(0,effects.get());
    }
    @Test public void externalAuthorityNeverRunsWhileHoldingDispatchMonitor()throws Exception {
        RunControl control=new RunControl();CountDownLatch paused=new CountDownLatch(1);AtomicReference<Thread> thread=new AtomicReference<>();
        control.setAuthorityCheck(()->{Thread p=new Thread(()->{control.pause();paused.countDown();},"authority-pause");thread.set(p);p.start();assertTrue(paused.await(1,TimeUnit.SECONDS));});
        assertThrows(SecurityException.class,()->control.dispatchAtPauseRevision(0,()->fail("paused entry")));thread.get().join(1000);assertFalse(thread.get().isAlive());
    }
    @Test public void nativeEntryAndConcurrentPauseHaveOneOrderAndNoUndoClaim()throws Exception {
        RunControl control=new RunControl();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),pauseStarted=new CountDownLatch(1),pauseReturned=new CountDownLatch(1);
        AtomicInteger effects=new AtomicInteger();AtomicReference<Throwable> failed=new AtomicReference<>();
        Thread dispatch=new Thread(()->{try{control.dispatchAtPauseRevision(0,()->{entered.countDown();assertTrue(release.await(1,TimeUnit.SECONDS));effects.incrementAndGet();});}catch(Throwable x){failed.set(x);}},"native-entry");
        dispatch.start();assertTrue(entered.await(1,TimeUnit.SECONDS));
        Thread pause=new Thread(()->{pauseStarted.countDown();control.pause();pauseReturned.countDown();},"concurrent-pause");pause.start();assertTrue(pauseStarted.await(1,TimeUnit.SECONDS));
        try{assertFalse(pauseReturned.await(30,TimeUnit.MILLISECONDS));}finally{release.countDown();}
        dispatch.join(1000);pause.join(1000);assertNull(failed.get());assertEquals(1,effects.get());assertTrue(control.isPaused());assertEquals(1,control.pauseRevision());
        assertThrows(SecurityException.class,()->control.dispatchAtPauseRevision(0,effects::incrementAndGet));assertEquals(1,effects.get());
    }
    @Test public void cancelledOrPreviouslyPausedControlCannotEnter()throws Exception {
        RunControl cancelled=new RunControl();cancelled.cancel();assertThrows(InterruptedException.class,()->cancelled.dispatchAtPauseRevision(0,()->fail("cancelled entry")));
        RunControl paused=new RunControl();paused.pause();paused.resume();assertThrows(SecurityException.class,()->paused.dispatchAtPauseRevision(0,()->fail("stale entry")));
    }
}
