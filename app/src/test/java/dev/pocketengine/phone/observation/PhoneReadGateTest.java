package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** No Android mocks: real threads/latches hold a system-call stand-in indefinitely until explicitly released. */
public final class PhoneReadGateTest {
    @Test public void timedOutBinderKeepsSingleFlightAndGlobalBudgetUntilItReallyReturns()throws Exception {
        ExecutorService callers=Executors.newFixedThreadPool(2);CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1),returned=new CountDownLatch(2);AtomicInteger inside=new AtomicInteger();
        PhoneReadGate first=new PhoneReadGate(100),second=new PhoneReadGate(100);
        try {
            Future<?> a=callers.submit(()->blocked(first,entered,release,returned,inside));Future<?> b=callers.submit(()->blocked(second,entered,release,returned,inside));assertTrue(entered.await(1,TimeUnit.SECONDS));
            a.get(1,TimeUnit.SECONDS);b.get(1,TimeUnit.SECONDS);assertEquals(2,inside.get());
            for(int i=0;i<30;i++) {
                assertEquals("tree_read_busy",assertThrows(PhoneReadGate.Unavailable.class,()->first.call(()->{},()->"never")).code);
                assertEquals("tree_read_busy",assertThrows(PhoneReadGate.Unavailable.class,()->new PhoneReadGate(50).call(()->{},()->"never")).code);
            }
        }finally{release.countDown();assertTrue(returned.await(1,TimeUnit.SECONDS));callers.shutdownNow();callers.awaitTermination(1,TimeUnit.SECONDS);}
        assertEquals("fresh",first.call(()->{},()->"fresh"));
    }
    private static void blocked(PhoneReadGate gate,CountDownLatch entered,CountDownLatch release,CountDownLatch returned,AtomicInteger inside) {
        try{gate.call(()->{},()->{inside.incrementAndGet();entered.countDown();try{release.await();return "late";}finally{returned.countDown();}});fail("a held call must time out");}
        catch(PhoneReadGate.Unavailable timeout){assertEquals("tree_read_timeout",timeout.code);}catch(Exception failed){throw new AssertionError(failed);}
    }
    @Test public void cancelReturnsBeforeBinderAndLateTraversalCannotReachNativeEntry()throws Exception {
        ExecutorService caller=Executors.newSingleThreadExecutor();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicInteger effects=new AtomicInteger();RunControl original=new RunControl();original.bindHostRun("task","run",7);
        try {
            Future<?> pending=caller.submit(()->{try{new PhoneReadGate().call(original::check,()->{entered.countDown();try{release.await();PhoneReadGate.checkCurrent();NativePhoneEntry.call(original,0,()->{},()->{effects.incrementAndGet();return null;});return "old";}finally{finished.countDown();}});fail("old run should stop");}catch(InterruptedException expected){}catch(Exception failed){throw new AssertionError(failed);}});
            assertTrue(entered.await(1,TimeUnit.SECONDS));original.cancel();pending.get(1,TimeUnit.SECONDS);assertEquals(1,finished.getCount());release.countDown();assertTrue(finished.await(1,TimeUnit.SECONDS));assertEquals(0,effects.get());
        }finally{release.countDown();caller.shutdownNow();caller.awaitTermination(1,TimeUnit.SECONDS);}
    }
    @Test public void deadlineInsideLateTraversalRefusesFurtherWorkEvenWithoutOwnerCancellation()throws Exception {
        PhoneReadGate gate=new PhoneReadGate(30);AtomicInteger effects=new AtomicInteger();CountDownLatch finished=new CountDownLatch(1);
        assertEquals("tree_read_timeout",assertThrows(PhoneReadGate.Unavailable.class,()->gate.call(()->{},()->{try{Thread.sleep(80);PhoneReadGate.checkCurrent();effects.incrementAndGet();return "late";}finally{finished.countDown();}})).code);
        assertTrue(finished.await(1,TimeUnit.SECONDS));assertEquals(0,effects.get());
    }
}
