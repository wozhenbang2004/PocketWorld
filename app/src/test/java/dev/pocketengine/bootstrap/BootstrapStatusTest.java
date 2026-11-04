package dev.pocketengine.bootstrap;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BootstrapStatusTest {
    @Test public void readyRequiresOriginalRecoveryAndFeatureStage()throws Exception {
        BootstrapStatus state=new BootstrapStatus(10);assertFalse(state.ready());long generation=state.beginInitial();
        assertFalse(state.complete(generation));state.stage(generation,"data_opened",9);state.stage(generation,"migrated",10);assertFalse(state.ready());
        state.recovering(generation);assertFalse(state.complete(generation));state.stage(generation,"features_ready",10);assertTrue(state.complete(generation));
        assertTrue(state.ready());assertEquals("completed",state.snapshot().getString("recovery"));assertEquals("ready",state.snapshot().getString("state"));
    }
    @Test public void onlyOneConcurrentRetryCanOwnOriginalInitialization()throws Exception {
        BootstrapStatus state=new BootstrapStatus(10);long failed=state.beginInitial();state.failed(failed,"database_locked","locked","retry",true);
        CountDownLatch start=new CountDownLatch(1);AtomicInteger admitted=new AtomicInteger();Thread[] threads=new Thread[2];
        for(int i=0;i<2;i++){final String request="retry-original-"+i;threads[i]=new Thread(()->{try{start.await();if(state.retry(failed,request)>0)admitted.incrementAndGet();}catch(SecurityException expected){}catch(Exception unexpected){throw new AssertionError(unexpected);}});threads[i].start();}
        start.countDown();for(Thread thread:threads)thread.join();assertEquals(1,admitted.get());assertEquals(failed+1,state.generation());assertFalse(state.ready());
    }
    @Test public void lateOldSuccessCannotOverwriteCurrentFailure()throws Exception {
        BootstrapStatus state=new BootstrapStatus(10);long old=state.beginInitial();state.failed(old,"database_locked","locked","retry",true);
        long current=state.retry(old,"retry-exact-1");state.failed(current,"migration_failed","failed","compatible version",false);
        state.stage(old,"features_ready",10);state.recovering(old);assertFalse(state.complete(old));assertEquals("migration_failed",state.snapshot().getJSONObject("reason").getString("code"));assertFalse(state.ready());
    }
    @Test public void duplicateRetryNeverCreatesASecondGeneration()throws Exception {
        BootstrapStatus state=new BootstrapStatus(10);long first=state.beginInitial();state.failed(first,"failed","failed","retry",true);
        long second=state.retry(first,"same-request-1");assertEquals(0,state.retry(first,"same-request-1"));assertEquals(second,state.generation());
    }
    @Test public void optionalToolFailureDoesNotInventWholeAppFailure()throws Exception {
        BootstrapStatus state=new BootstrapStatus(10);long generation=state.beginInitial();state.optional(generation,"office","not prepared");state.recovering(generation);state.stage(generation,"features_ready",10);assertTrue(state.complete(generation));assertEquals(1,state.snapshot().getJSONArray("optionalUnavailable").length());
    }
    @Test public void enteringMaintenanceClosesAdmissionImmediately()throws Exception {
        BootstrapStatus state=new BootstrapStatus(10);long generation=state.beginInitial();state.recovering(generation);state.stage(generation,"features_ready",10);state.complete(generation);
        state.maintenance("preserving","preserving","read original",false);assertFalse(state.ready());assertEquals("maintenance",state.snapshot().getString("state"));assertFalse(state.snapshot().getBoolean("retryAvailable"));
        try{state.retry(generation,"retry-while-held");fail();}catch(SecurityException expected){}
    }
    @Test public void validRetryPreflightDoesNotReserveOrChangeTheGeneration()throws Exception {
        BootstrapStatus state=new BootstrapStatus(10);long generation=state.beginInitial();state.failed(generation,"database_locked","locked","retry",true);
        String before=state.snapshot().toString();
        assertTrue(state.checkRetry(generation,"preflight-valid-1"));assertTrue(state.checkRetry(generation,"preflight-valid-1"));
        assertEquals(before,state.snapshot().toString());assertEquals(generation,state.generation());
        assertEquals(generation+1,state.retry(generation,"preflight-valid-1"));
    }
    @Test public void duplicateRetryPreflightOnlyObservesTheAlreadyStartedRequest()throws Exception {
        BootstrapStatus state=new BootstrapStatus(10);long first=state.beginInitial();state.failed(first,"database_locked","locked","retry",true);
        long next=state.retry(first,"preflight-duplicate-1");String before=state.snapshot().toString();
        assertFalse(state.checkRetry(first,"preflight-duplicate-1"));assertEquals(0,state.retry(first,"preflight-duplicate-1"));
        assertEquals(before,state.snapshot().toString());assertEquals(next,state.generation());
    }
    @Test public void rejectedRetryPreflightNeverChangesStateOrConsumesTheRequest()throws Exception {
        BootstrapStatus state=new BootstrapStatus(10);long generation=state.beginInitial();String request="preflight-rejected-1";
        String initializing=state.snapshot().toString();assertThrows(SecurityException.class,()->state.checkRetry(generation,request));assertEquals(initializing,state.snapshot().toString());
        state.failed(generation,"database_locked","locked","retry",true);String failed=state.snapshot().toString();
        assertThrows(SecurityException.class,()->state.checkRetry(generation+1,request));assertEquals(failed,state.snapshot().toString());
        state.maintenance("quiescence_unconfirmed","not closed","read original",false);String held=state.snapshot().toString();
        assertThrows(SecurityException.class,()->state.checkRetry(generation,request));assertEquals(held,state.snapshot().toString());
        state.maintenance("preserved","closed original retained","explicit original retry",true);
        assertTrue("A rejected preflight did not consume this request",state.checkRetry(generation,request));
        long next=state.retry(generation,request);state.recovering(next);state.stage(next,"features_ready",10);assertTrue(state.complete(next));String ready=state.snapshot().toString();
        assertThrows(SecurityException.class,()->state.checkRetry(next,"preflight-after-ready"));assertEquals(ready,state.snapshot().toString());
    }
}
