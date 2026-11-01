package dev.pocketengine.features.semantic;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class SemanticDispatchGateTest {
    @Test public void queueTimeoutPreventsAnyLaterEntry() {SemanticDispatchGate gate=new SemanticDispatchGate();assertTrue(gate.cancelBeforeEntry());assertFalse(gate.enter());assertFalse(gate.entered());}
    @Test public void enteredCallCannotBeClaimedAsDefinitelyCancelled() {SemanticDispatchGate gate=new SemanticDispatchGate();assertTrue(gate.enter());assertFalse(gate.cancelBeforeEntry());assertTrue(gate.entered());assertFalse(gate.enter());}
    @Test public void timeoutAndCallbackRaceHaveOnlyOneWinner() throws Exception {
        for(int i=0;i<100;i++) {
            SemanticDispatchGate gate=new SemanticDispatchGate();CountDownLatch start=new CountDownLatch(1);AtomicBoolean entered=new AtomicBoolean(),cancelled=new AtomicBoolean();
            Thread callback=new Thread(()->{try{start.await();entered.set(gate.enter());}catch(InterruptedException e){throw new AssertionError(e);}});
            Thread timeout=new Thread(()->{try{start.await();cancelled.set(gate.cancelBeforeEntry());}catch(InterruptedException e){throw new AssertionError(e);}});
            callback.start();timeout.start();start.countDown();callback.join();timeout.join();assertTrue(entered.get()^cancelled.get());
        }
    }
}
