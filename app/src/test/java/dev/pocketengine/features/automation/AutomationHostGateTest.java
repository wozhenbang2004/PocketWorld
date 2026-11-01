package dev.pocketengine.features.automation;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AutomationHostGateTest {
    @Test public void callbackCanReenterWithoutSelfWaitingAndNextQueryRunsImmediatelyAfterCompletion(){
        AutomationHostGate gate=new AutomationHostGate();
        assertEquals(Boolean.TRUE,gate.call(()->{
            assertTrue(gate.onCallbackThread());assertNull(gate.call(()->"never",1000,100));return Boolean.TRUE;
        },1000,500));
        assertFalse(gate.onCallbackThread());assertEquals(Long.valueOf(1234),gate.call(()->1234L,1000,500));
    }
    @Test public void exceptionBacksOffWithoutCallingAgainUntilEligible(){
        AutomationHostGate gate=new AutomationHostGate();AtomicInteger calls=new AtomicInteger();
        assertNull(gate.call(()->{calls.incrementAndGet();throw new IllegalStateException("host failure");},1000,500));
        assertNull(gate.call(()->{calls.incrementAndGet();return true;},2000,500));assertEquals(1,calls.get());
        assertTrue(gate.retryAt(2000)>=301000);
        assertEquals(Boolean.TRUE,gate.call(()->{calls.incrementAndGet();return true;},301000,500));assertEquals(2,calls.get());
    }
    @Test public void timeoutDoesNotPretendCallbackStoppedAndNeverAllowsOverlappingEffects()throws Exception {
        AutomationHostGate gate=new AutomationHostGate();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),exited=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        long start=System.nanoTime();
        assertNull(gate.call(()->{calls.incrementAndGet();entered.countDown();
            // Deliberately broken host ignores interrupts; gate must not overlap another pass.
            while(release.getCount()!=0)try{release.await();}catch(InterruptedException ignored){}
            exited.countDown();return true;
        },1000,30));
        assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000);
        assertNull(gate.call(()->{calls.incrementAndGet();return true;},999999,30));assertEquals(1,calls.get());
        release.countDown();assertTrue(exited.await(1,TimeUnit.SECONDS));
        // A subsequent call is allowed only after the old body really leaves the gate.
        for(int i=0;i<100&&gate.call(()->true,999999,100)==null;i++)Thread.sleep(1);
        assertEquals(Boolean.TRUE,gate.call(()->true,999999,100));
    }
}
