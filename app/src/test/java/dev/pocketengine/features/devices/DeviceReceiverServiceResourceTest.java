package dev.pocketengine.features.devices;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Resource/gate unit fixtures only. No claim of an Android foreground service or real mTLS listener. */
public final class DeviceReceiverServiceResourceTest {
    private static final class Backend implements DeviceReceiverServiceResource.Backend {
        boolean listening,openProof=true,unknown;int opens,closes;
        public void start(){opens++;listening=openProof;}
        public boolean isListening(){if(unknown)throw new IllegalStateException("fixture unknown socket state");return listening;}
        public void close(){closes++;listening=false;}
    }
    private static final class Policy implements DeviceReceiverServiceResource.OwnerPolicy {
        final AtomicBoolean enabled=new AtomicBoolean(true);int disables;
        public void requireEnabledCurrent(){if(!enabled.get())throw new SecurityException("fixture local owner disabled");}
        public void disableByOwner(){disables++;enabled.set(false);}
    }
    private static void denied(DeviceTaskCoordinator.Acceptance action)throws Exception{try{action.commit();fail("must refuse");}catch(SecurityException expected){}}
    @Test public void constructionNeverListensAndPortConfigurationIsNotReadiness()throws Exception{
        Backend backend=new Backend();Policy policy=new Policy();var resource=new DeviceReceiverServiceResource(backend,policy);assertEquals(0,backend.opens);assertFalse(resource.snapshot().getBoolean("acceptingNewRequests"));
        backend.openProof=false;assertThrows(SecurityException.class,resource::start);assertEquals(1,backend.opens);assertEquals(1,backend.closes);assertTrue(resource.stopped());assertFalse(resource.snapshot().getBoolean("listening"));
    }
    @Test public void explicitDisableLinearizesAfterAcceptedSqlAndCannotEraseAcceptedWork()throws Exception{
        Backend backend=new Backend();Policy policy=new Policy();var resource=new DeviceReceiverServiceResource(backend,policy);resource.start();AtomicInteger accepted=new AtomicInteger();CountDownLatch inSql=new CountDownLatch(1),commit=new CountDownLatch(1),disableStarted=new CountDownLatch(1);var io=Executors.newFixedThreadPool(2);
        try{
            var old=io.submit(()->resource.acceptNewRequest(()->{inSql.countDown();assertTrue(commit.await(5,TimeUnit.SECONDS));accepted.incrementAndGet();return new JSONObject().put("accepted",true);}));assertTrue(inSql.await(5,TimeUnit.SECONDS));
            var disable=io.submit(()->{disableStarted.countDown();resource.disableByOwner();resource.close();return true;});assertTrue(disableStarted.await(5,TimeUnit.SECONDS));assertFalse(disable.isDone());commit.countDown();assertTrue(old.get(5,TimeUnit.SECONDS).getBoolean("accepted"));assertTrue(disable.get(5,TimeUnit.SECONDS));
            assertEquals(1,accepted.get());assertEquals(1,policy.disables);assertFalse(policy.enabled.get());assertTrue(resource.stopped());denied(()->resource.acceptNewRequest(()->{accepted.incrementAndGet();return new JSONObject();}));assertEquals("accepted work is independent of receiver shutdown",1,accepted.get());
        }finally{commit.countDown();io.shutdownNow();}
    }
    @Test public void unknownCloseNeverBecomesStoppedAndFenceAlreadyRejectsNewRequests()throws Exception{
        Backend backend=new Backend();Policy policy=new Policy();var resource=new DeviceReceiverServiceResource(backend,policy);resource.start();backend.unknown=true;assertThrows(IllegalStateException.class,resource::close);assertFalse(resource.stopped());assertEquals("stop_unconfirmed",resource.snapshot().getString("state"));assertTrue(resource.snapshot().isNull("listening"));denied(()->resource.acceptNewRequest(()->new JSONObject()));
        backend.unknown=false;resource.close();assertTrue(resource.stopped());
    }
    @Test public void ownerRevocationBeforeStartDoesNotOpenAnySocketOrCreateTaskAuthority()throws Exception{
        Backend backend=new Backend();Policy policy=new Policy();policy.enabled.set(false);var resource=new DeviceReceiverServiceResource(backend,policy);assertThrows(SecurityException.class,resource::start);assertEquals(0,backend.opens);assertTrue(resource.stopped());assertFalse(resource.snapshot().getBoolean("disableStopsAcceptedTasks"));assertFalse(resource.snapshot().getBoolean("restartAutomatic"));
    }
}
