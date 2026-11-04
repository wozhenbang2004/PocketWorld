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
}
