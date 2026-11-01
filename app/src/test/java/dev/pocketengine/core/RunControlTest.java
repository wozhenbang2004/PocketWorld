package dev.pocketengine.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RunControlTest {
    @Test public void registeringWhileCancellingCallsEachCleanupExactlyOnce() throws Exception {
        ExecutorService threads=Executors.newFixedThreadPool(2);
        try {
            for(int i=0;i<500;i++) {
                RunControl control=new RunControl();
                AtomicInteger called=new AtomicInteger();
                CountDownLatch start=new CountDownLatch(1);
                Future<?> registration=threads.submit(()->{await(start);control.onCancel(called::incrementAndGet);});
                Future<?> cancellation=threads.submit(()->{await(start);control.cancel();});
                start.countDown();registration.get(1,TimeUnit.SECONDS);cancellation.get(1,TimeUnit.SECONDS);
                control.cancel();assertEquals(1,called.get());
            }
        } finally {threads.shutdownNow();}
    }
    @Test public void completedRequestCanUnregisterItsCleanup() {
        RunControl control=new RunControl();AtomicInteger called=new AtomicInteger();
        Runnable cleanup=called::incrementAndGet;control.onCancel(cleanup);control.removeCancelAction(cleanup);
        control.cancel();assertEquals(0,called.get());
        control.onCancel(called::incrementAndGet);assertEquals(1,called.get());
    }
    @Test public void cancellationReleasesPausedWorker() throws Exception {
        RunControl control=new RunControl();control.pause();
        ExecutorService thread=Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> stopped=thread.submit(()->{try{control.checkpoint();return false;}catch(InterruptedException expected){return true;}});
            control.cancel();assertTrue(stopped.get(1,TimeUnit.SECONDS));
        } finally {thread.shutdownNow();}
    }
    private static void await(CountDownLatch latch) {try{latch.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
}
