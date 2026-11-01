package dev.pocketengine.phone.observation;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class NativePhoneEntryTest {
    @Test public void lastPreparedCallbackPauseResumeOrCancelCannotEnter()throws Exception {
        for(boolean cancel:new boolean[]{false,true}){
            RunControl c=new RunControl();AtomicInteger effects=new AtomicInteger();
            assertThrows(NativePhoneEntry.Refused.class,()->NativePhoneEntry.call(c,0,()->{if(cancel)c.cancel();else{c.pause();c.resume();}},effects::incrementAndGet));assertEquals(0,effects.get());
        }
    }
    @Test public void finalAuthorityCallbackPauseInvalidatesThePreparedDecision()throws Exception {
        RunControl c=new RunControl();AtomicInteger effects=new AtomicInteger();c.setAuthorityCheck(c::pause);
        assertThrows(NativePhoneEntry.Refused.class,()->NativePhoneEntry.call(c,0,()->{},effects::incrementAndGet));assertEquals(0,effects.get());
    }
    @Test public void entryAlreadyWonKeepsItsRealReceiptAfterCancelAndOnlyRemovesOwnHook()throws Exception {
        RunControl c=new RunControl();AtomicInteger ownerStop=new AtomicInteger();c.onCancel(ownerStop::incrementAndGet);
        assertEquals("accepted",NativePhoneEntry.call(c,0,()->{},()->{c.cancel();return "accepted";}));assertEquals(1,ownerStop.get());
    }
    @Test public void concurrentPauseAndOneNativeEntryHaveOneOrder()throws Exception {
        RunControl c=new RunControl();CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1),paused=new CountDownLatch(1);AtomicInteger effects=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread action=new Thread(()->{try{NativePhoneEntry.call(c,0,()->{},()->{entered.countDown();assertTrue(finish.await(1,TimeUnit.SECONDS));return effects.incrementAndGet();});}catch(Throwable e){failure.set(e);}});action.start();assertTrue(entered.await(1,TimeUnit.SECONDS));
        Thread pause=new Thread(()->{c.pause();paused.countDown();});pause.start();try{assertFalse(paused.await(30,TimeUnit.MILLISECONDS));}finally{finish.countDown();}
        action.join(1000);pause.join(1000);assertNull(failure.get());assertEquals(1,effects.get());assertTrue(c.isPaused());
    }
}
