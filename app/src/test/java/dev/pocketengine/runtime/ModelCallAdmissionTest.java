package dev.pocketengine.runtime;

import dev.pocketengine.core.RunControl;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Only process-local occupancy; no substitute for original inference/TaskStore admission. */
public final class ModelCallAdmissionTest {
    private final ExecutorService workers=Executors.newCachedThreadPool();
    @After public void close()throws Exception{workers.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));}
    private static RunControl limited(int value)throws Exception{RunControl control=new RunControl();control.restrictModelConcurrency(value);return control;}
    private static long deadline(){return System.currentTimeMillis()+5000;}
    private static void await(BooleanSupplier state)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!state.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue("Expected native occupancy state",state.getAsBoolean());}
    private static Throwable failed(Future<?> result)throws Exception{try{result.get(3,TimeUnit.SECONDS);fail("Expected pre-dispatch refusal");return null;}catch(ExecutionException expected){return expected.getCause();}}

    @Test public void emptyPreferencesDoNotInventAGlobalTwoCallLimit()throws Exception{
        ModelCallAdmission gate=new ModelCallAdmission();try(var a=gate.enter(new RunControl(),"a",deadline());var b=gate.enter(new RunControl(),"b",deadline());var c=gate.enter(new RunControl(),"c",deadline())){assertEquals(3,gate.activeCount());}assertEquals(0,gate.activeCount());
    }
    @Test public void nativePreferenceOnlyNarrowsAndCannotChangeAfterRunAdmission()throws Exception{
        RunControl control=new RunControl();assertEquals(Integer.MAX_VALUE,control.modelConcurrencyLimit());control.restrictModelConcurrency(2);control.restrictModelConcurrency(1);control.restrictModelConcurrency(2);assertEquals(1,control.modelConcurrencyLimit());assertThrows(IllegalArgumentException.class,()->control.restrictModelConcurrency(0));assertThrows(IllegalArgumentException.class,()->control.restrictModelConcurrency(3));control.bindHostRun("task","run",1);assertThrows(SecurityException.class,()->control.restrictModelConcurrency(1));
    }
    @Test public void oldEpochOccupiesTheSameTaskUntilItsPhysicalExitAndReleaseIsOnce()throws Exception{
        ModelCallAdmission gate=new ModelCallAdmission();RunControl old=limited(1),next=limited(1);old.bindHostRun("same-task","old",1);next.bindHostRun("same-task","new",2);var held=gate.enter(old,"same-task",deadline());Future<ModelCallAdmission.Permit> pending=workers.submit(()->gate.enter(next,"same-task",deadline()));await(()->gate.waitingCount()==1);old.cancel();assertEquals(1,gate.activeCount());assertFalse(pending.isDone());held.close();held.close();var accepted=pending.get(3,TimeUnit.SECONDS);assertEquals(1,gate.activeCount());accepted.close();assertEquals(0,gate.activeCount());
    }
    @Test public void cancelingALaterIdenticalWaiterCannotRemoveTheEarlierTicket()throws Exception{
        ModelCallAdmission gate=new ModelCallAdmission();var held=gate.enter(limited(1),"same-task",deadline());RunControl first=limited(1),second=limited(1);Future<ModelCallAdmission.Permit> a=workers.submit(()->gate.enter(first,"same-task",deadline()));await(()->gate.waitingCount()==1);Future<ModelCallAdmission.Permit> b=workers.submit(()->gate.enter(second,"same-task",deadline()));await(()->gate.waitingCount()==2);second.cancel();assertTrue(failed(b) instanceof InterruptedException);await(()->gate.waitingCount()==1);assertFalse(a.isDone());held.close();a.get(3,TimeUnit.SECONDS).close();assertEquals(0,gate.waitingCount());assertEquals(0,gate.activeCount());
    }
    @Test public void pauseThenResumeCannotReviveAQueuedDispatchDecision()throws Exception{
        ModelCallAdmission gate=new ModelCallAdmission();try(var held=gate.enter(limited(1),"task",deadline())){RunControl waiting=limited(1);Future<?> result=workers.submit(()->gate.enter(waiting,"task",deadline()));await(()->gate.waitingCount()==1);waiting.pause();waiting.resume();assertTrue(failed(result) instanceof ModelCallAdmission.Refused);assertFalse(waiting.isCancelled());await(()->gate.waitingCount()==0);assertEquals(1,gate.activeCount());}
    }
    @Test public void queuedDeadlineDoesNotRenewAndDoesNotReleaseSomebodyElsesSlot()throws Exception{
        ModelCallAdmission gate=new ModelCallAdmission();try(var held=gate.enter(limited(1),"task",deadline())){Future<?> result=workers.submit(()->gate.enter(limited(1),"task",System.currentTimeMillis()+120));Throwable failure=failed(result);assertTrue(failure instanceof ModelCallAdmission.Refused);assertTrue(((ModelCallAdmission.Refused)failure).deadline);assertEquals(0,gate.waitingCount());assertEquals(1,gate.activeCount());}
    }
    @Test public void queuedSourceRevocationIsCheckedOutsideTheOccupancyMonitor()throws Exception{
        ModelCallAdmission gate=new ModelCallAdmission();try(var held=gate.enter(limited(1),"task",deadline())){RunControl waiting=limited(1);java.util.concurrent.atomic.AtomicBoolean revoked=new java.util.concurrent.atomic.AtomicBoolean();waiting.setAuthorityCheck(()->{assertFalse(Thread.holdsLock(gate));if(revoked.get())throw new SecurityException("Revoked native source");});Future<?> result=workers.submit(()->gate.enter(waiting,"task",deadline()));await(()->gate.waitingCount()==1);revoked.set(true);assertTrue(failed(result) instanceof InterruptedException);assertTrue(waiting.isCancelled());assertEquals(0,gate.waitingCount());assertEquals(1,gate.activeCount());}
    }
    @Test public void verifiedInheritedBudgetOwnerSharesTheSameGateAndIntersectsTheChildLimit()throws Exception {
        ModelCallAdmission gate=new ModelCallAdmission();RunControl parent=limited(2),child=limited(1);child.bindHostRun("actual-child","child-run",1);
        var held=gate.enter(parent,"original-budget-owner",deadline(),2);
        Future<ModelCallAdmission.Permit> pending=workers.submit(()->gate.enter(child,"original-budget-owner",deadline(),2));
        await(()->gate.waitingCount()==1);assertFalse(pending.isDone());
        try(var unrelated=gate.enter(new RunControl(),"unrelated",deadline())){assertEquals(2,gate.activeCount());}
        held.close();var accepted=pending.get(3,TimeUnit.SECONDS);assertEquals(1,gate.activeCount());accepted.close();assertEquals(0,gate.activeCount());
    }
    @Test public void inheritedScopeCannotInventALargerLimitAndQueuedChildCancellationKeepsParentOccupancy()throws Exception {
        ModelCallAdmission gate=new ModelCallAdmission();assertThrows(SecurityException.class,()->gate.enter(new RunControl(),"owner",deadline(),3));
        try(var held=gate.enter(new RunControl(),"owner",deadline(),1)){
            RunControl child=new RunControl();Future<?> pending=workers.submit(()->gate.enter(child,"owner",deadline(),2));await(()->gate.waitingCount()==1);
            child.cancel();assertTrue(failed(pending) instanceof InterruptedException);assertEquals(0,gate.waitingCount());assertEquals(1,gate.activeCount());
        }
    }
    @Test public void boundedWaitQueueDoesNotBlockIndependentTasksThatCanEnterImmediately()throws Exception{
        ModelCallAdmission gate=new ModelCallAdmission(2);try(var held=gate.enter(limited(1),"task",deadline())){RunControl first=limited(1),second=limited(1);Future<?> a=workers.submit(()->gate.enter(first,"task",deadline()));await(()->gate.waitingCount()==1);Future<?> b=workers.submit(()->gate.enter(second,"task",deadline()));await(()->gate.waitingCount()==2);assertThrows(ModelCallAdmission.Refused.class,()->gate.enter(limited(1),"task",deadline()));try(var independent=gate.enter(limited(1),"other",deadline())){assertEquals(2,gate.activeCount());}first.cancel();second.cancel();assertTrue(failed(a) instanceof InterruptedException);assertTrue(failed(b) instanceof InterruptedException);assertEquals(0,gate.waitingCount());}
    }
    @Test public void steeringRemovesOnlyTheWaitingRequestAndRetainsTheOccupiedPhysicalCall()throws Exception{
        ModelCallAdmission gate=new ModelCallAdmission();RunControl control=limited(1);control.bindHostRun("task","run",1);
        try(var held=gate.enter(control,"task",deadline());var request=control.openModelRequest(control.modelReplanRevision())){
            Future<?> waiting=workers.submit(()->gate.enter(control,"task",deadline(),1,request));await(()->gate.waitingCount()==1);
            assertTrue(control.requestModelReplan("task","run",1));assertTrue(failed(waiting) instanceof RunControl.ModelReplanRequested);
            assertEquals(0,gate.waitingCount());assertEquals(1,gate.activeCount());assertFalse(control.isCancelled());control.check();
        }
        assertEquals(0,gate.activeCount());
        try(var fresh=control.openModelRequest(control.modelReplanRevision());var admitted=gate.enter(control,"task",deadline(),1,fresh)){assertEquals(1,gate.activeCount());}
    }
    @Test public void steeringCannotReleaseInFlightOccupancyBeforeTheOriginalTransportExits()throws Exception{
        ModelCallAdmission gate=new ModelCallAdmission();RunControl control=limited(1);control.bindHostRun("task","run",1);
        try(var stale=control.openModelRequest(control.modelReplanRevision())){
            var held=gate.enter(control,"task",deadline(),1,stale);assertTrue(control.requestModelReplan("task","run",1));
            try(var fresh=control.openModelRequest(control.modelReplanRevision())){
                Future<ModelCallAdmission.Permit> next=workers.submit(()->gate.enter(control,"task",deadline(),1,fresh));
                await(()->gate.waitingCount()==1);assertFalse(next.isDone());assertEquals(1,gate.activeCount());
                held.close();next.get(3,TimeUnit.SECONDS).close();assertEquals(0,gate.activeCount());
            }finally{held.close();}
        }
    }
}
