package dev.pocketengine.phone.access;

import dev.pocketengine.core.RunControl;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneAppResourcesTest {
    private static final AppCopyKey A=new AppCopyKey("fixture.alpha",0),B=new AppCopyKey("fixture.beta",0);
    @Test public void sameAppUserIsExclusiveAndUnknownActualUseNeverReplacesTheOwner()throws Exception {
        PhoneAppResources resources=new PhoneAppResources();assertEquals(PhoneAppResources.Result.ACQUIRED,resources.reserve("first",A,(k,o)->true));
        assertEquals(PhoneAppResources.Result.BUSY,resources.reserve("second",A,(k,o)->true));assertEquals(PhoneAppResources.Result.BUSY,resources.reserve("second",B,(k,o)->false));assertEquals("first",resources.holder(A));
        assertEquals(PhoneAppResources.Result.ACQUIRED,resources.reserve("second",new AppCopyKey("fixture.alpha",10),(k,o)->true));
        resources.release("second",A);assertEquals("first",resources.holder(A));
    }
    @Test public void waitingAcquiresOnlyAfterActualOwnerRelease()throws Exception {
        PhoneAppResources resources=new PhoneAppResources();resources.reserve("first",A,(k,o)->true);RunControl control=new RunControl();ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch waiting=new CountDownLatch(1);
        try{Future<PhoneAppResources.Result> result=worker.submit(()->resources.await("second",A,2000,control,(k,o)->{waiting.countDown();return true;}));assertTrue(waiting.await(1,TimeUnit.SECONDS));assertFalse(result.isDone());resources.releaseOwner("first");assertEquals(PhoneAppResources.Result.ACQUIRED,result.get(1,TimeUnit.SECONDS));assertEquals("second",resources.holder(A));}
        finally{control.cancel();worker.shutdownNow();}
    }
    @Test public void waitGraphDetectsAnActualTwoOwnerCycleWithoutTakingEitherApp()throws Exception {
        PhoneAppResources resources=new PhoneAppResources();resources.reserve("first",A,(k,o)->true);resources.reserve("second",B,(k,o)->true);RunControl first=new RunControl();ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch waiting=new CountDownLatch(1);
        try{Future<?> result=worker.submit(()->{try{return resources.await("first",B,2000,first,(k,o)->{waiting.countDown();return true;});}catch(Exception stop){return stop;}});assertTrue(waiting.await(1,TimeUnit.SECONDS));assertEquals(PhoneAppResources.Result.DEADLOCK,resources.await("second",A,100,new RunControl(),(k,o)->true));assertEquals("first",resources.holder(A));assertEquals("second",resources.holder(B));first.cancel();result.get(1,TimeUnit.SECONDS);}
        finally{first.cancel();worker.shutdownNow();}
    }
    @Test public void cancellationAndTimeoutLeaveExistingReservationsUntouched()throws Exception {
        PhoneAppResources resources=new PhoneAppResources();resources.reserve("first",A,(k,o)->true);RunControl stopped=new RunControl();stopped.cancel();assertThrows(InterruptedException.class,()->resources.await("second",A,10,stopped,(k,o)->true));
        assertEquals(PhoneAppResources.Result.TIMED_OUT,resources.await("second",A,10,new RunControl(),(k,o)->true));assertEquals("first",resources.holder(A));assertEquals(1,resources.heldCount());
    }
}
