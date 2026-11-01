package dev.pocketengine.runtime.extensions;

import static org.junit.Assert.*;
import com.google.common.util.concurrent.ListenableFuture;
import dev.pocketengine.core.RunControl;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

/** Adapter tests do not start Android's service; actual device isolation remains separate. */
public final class AndroidExtensionEvaluatorTest {
    private static final byte[] CODE="'{}'".getBytes(StandardCharsets.UTF_8);
    private static final class Future extends CompletableFuture<String> implements ListenableFuture<String> {
        @Override public void addListener(Runnable listener,Executor executor){whenComplete((result,error)->executor.execute(listener));}
    }
    private static final class Lease implements AndroidExtensionEvaluator.Lease {
        final Future initialization=new Future(),result=new Future();final CountDownLatch evaluating=new CountDownLatch(1);int evaluations,closes,closeFailures;
        Lease(){initialization.complete("ready");result.complete("{}");}
        @Override public ListenableFuture<String> initialize(JSONObject input){return initialization;}
        @Override public ListenableFuture<String> evaluate(byte[] code){evaluations++;evaluating.countDown();return result;}
        @Override public void close(){closes++;if(closeFailures-->0)throw new IllegalStateException("close failed");}
    }
    @Test public void initializationMustActuallyFinishBeforeEvaluation()throws Exception {
        Lease lease=new Lease();lease.initialization.obtrudeException(new IllegalStateException("private-secret"));AndroidExtensionEvaluator evaluator=new AndroidExtensionEvaluator(()->lease);
        Exception error=assertThrows(Exception.class,()->evaluator.evaluate(CODE,new JSONObject(),500,new RunControl(),()->{}));assertFalse(error.getMessage().contains("private-secret"));assertEquals(0,lease.evaluations);assertEquals(1,lease.closes);evaluator.close();
    }
    @Test public void authorityIsRecheckedAfterAsyncInitialization()throws Exception {
        Lease lease=new Lease();AndroidExtensionEvaluator evaluator=new AndroidExtensionEvaluator(()->lease);AtomicInteger checks=new AtomicInteger();
        assertThrows(SecurityException.class,()->evaluator.evaluate(CODE,new JSONObject(),500,new RunControl(),()->{if(checks.incrementAndGet()==3)throw new SecurityException("changed source");}));assertEquals(0,lease.evaluations);assertEquals(1,lease.closes);evaluator.close();
    }
    @Test public void deadlineTerminatesOwnedIsolateNotMerelyTheFuture()throws Exception {
        Lease lease=new Lease();lease.initialization.obtrudeValue("ready");lease.result.obtrudeValue("{}");AndroidExtensionEvaluator evaluator=new AndroidExtensionEvaluator(()->new AndroidExtensionEvaluator.Lease(){
            @Override public ListenableFuture<String> initialize(JSONObject input){return new Future();}
            @Override public ListenableFuture<String> evaluate(byte[] code){lease.evaluations++;return lease.result;}
            @Override public void close(){lease.close();}
        });
        assertThrows(IllegalStateException.class,()->evaluator.evaluate(CODE,new JSONObject(),50,new RunControl(),()->{}));assertEquals(0,lease.evaluations);assertEquals(1,lease.closes);evaluator.close();
    }
    @Test public void cancellationClosesActualOwnedLease()throws Exception {
        Lease lease=new Lease();Future pending=new Future();AndroidExtensionEvaluator evaluator=new AndroidExtensionEvaluator(()->new AndroidExtensionEvaluator.Lease(){
            @Override public ListenableFuture<String> initialize(JSONObject input){return lease.initialization;}
            @Override public ListenableFuture<String> evaluate(byte[] code){lease.evaluating.countDown();return pending;}
            @Override public void close(){lease.close();}
        });
        RunControl control=new RunControl();AtomicInteger stopped=new AtomicInteger();Thread worker=new Thread(()->{try{evaluator.evaluate(CODE,new JSONObject(),2000,control,()->{});}catch(Exception expected){stopped.incrementAndGet();}});
        worker.start();assertTrue(lease.evaluating.await(2,TimeUnit.SECONDS));control.cancel();worker.join(3000);assertFalse(worker.isAlive());assertEquals(1,stopped.get());assertEquals(1,lease.closes);assertFalse(pending.isCancelled());evaluator.close();
    }
    @Test public void failedTerminationKeepsBusyUntilExplicitCloseRetry()throws Exception {
        Lease lease=new Lease();lease.closeFailures=1;AndroidExtensionEvaluator evaluator=new AndroidExtensionEvaluator(()->lease);
        assertThrows(IllegalStateException.class,()->evaluator.evaluate(CODE,new JSONObject(),500,new RunControl(),()->{}));assertEquals(1,lease.closes);
        assertThrows(IllegalStateException.class,()->evaluator.evaluate(CODE,new JSONObject(),500,new RunControl(),()->{}));assertEquals(1,lease.evaluations);
        evaluator.close();evaluator.close();assertEquals(2,lease.closes);
    }
    @Test public void executionExceptionsDoNotLeakScriptData()throws Exception {
        Lease lease=new Lease();lease.result.obtrudeException(new IllegalStateException("private-secret"));AndroidExtensionEvaluator evaluator=new AndroidExtensionEvaluator(()->lease);
        Exception failure=assertThrows(Exception.class,()->evaluator.evaluate(CODE,new JSONObject(),500,new RunControl(),()->{}));assertFalse(failure.getMessage().contains("private-secret"));assertEquals(1,lease.evaluations);assertEquals(1,lease.closes);evaluator.close();
    }
}
