package dev.pocketengine.phone.observation;

import dev.pocketengine.core.RunControl;
import dev.pocketengine.phone.PhoneUiSignals;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneUiSignalsTest {
    @Test public void boundedSignalsContainNoTextAndUnrelatedWindowsDoNotChangeTargetStamp(){
        AtomicLong clock=new AtomicLong(100);PhoneUiSignals signals=new PhoneUiSignals("native-service",clock::get);PhoneUiSignals.Stamp before=signals.snapshot(Set.of(9));signals.record(10);assertTrue(before.same(signals.snapshot(Set.of(9))));signals.record(9);assertFalse(before.same(signals.snapshot(Set.of(9))));for(int i=0;i<100;i++)signals.record(i);assertEquals(64,signals.retainedWindowCount());
    }
    @Test public void unknownWindowAndDisconnectWakeAllScopesWithoutProvingAnEffect(){
        PhoneUiSignals s=new PhoneUiSignals("service",()->1);PhoneUiSignals.Stamp old=s.snapshot(Set.of(9));s.record(-1);assertFalse(old.same(s.snapshot(Set.of(9))));s.close();assertTrue(s.snapshot(Set.of(9)).closed);assertFalse(s.snapshot(Set.of(9)).same(new PhoneUiSignals("another-service",()->1).snapshot(Set.of(9))));
    }
    @Test public void controlCallbacksAreNeverInvokedUnderSignalMonitor()throws Exception {
        PhoneUiSignals s=new PhoneUiSignals("native",()->1);RunControl c=new RunControl();c.setAuthorityCheck(()->assertFalse(Thread.holdsLock(s)));s.awaitChange(s.snapshot(Set.of(9)),Set.of(9),5,c,0);
    }
    @Test public void pausedWaitReturnsWithoutWaitingForOwnerResume()throws Exception {
        PhoneUiSignals s=new PhoneUiSignals("native",()->1);RunControl c=new RunControl();c.pause();long start=System.nanoTime();s.awaitChange(s.snapshot(Set.of()),Set.of(),250,c,0);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<100);assertTrue(c.isPaused());
    }
    @Test public void eventAndStopReleaseAnExistingWaitWithoutAnotherNativeRead()throws Exception {
        PhoneUiSignals s=new PhoneUiSignals("native",()->1);RunControl c=new RunControl();ExecutorService worker=Executors.newSingleThreadExecutor();PhoneUiSignals.Stamp old=s.snapshot(Set.of(9));try{Future<?> wait=worker.submit(()->{try{s.awaitChange(old,Set.of(9),250,c,0);}catch(Exception failure){throw new RuntimeException(failure);}});Thread.sleep(10);s.record(9);wait.get(200,TimeUnit.MILLISECONDS);
            PhoneUiSignals.Stamp again=s.snapshot(Set.of(9));Future<?> stopped=worker.submit(()->{try{s.awaitChange(again,Set.of(9),250,c,0);}catch(InterruptedException expected){}catch(Exception failure){throw new RuntimeException(failure);}});c.cancel();stopped.get(200,TimeUnit.MILLISECONDS);
        }finally{worker.shutdownNow();worker.awaitTermination(1,TimeUnit.SECONDS);}
    }
}
