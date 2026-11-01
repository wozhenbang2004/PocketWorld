package dev.pocketengine.features.capabilities;

import dev.pocketengine.browser.BrowserLease;
import dev.pocketengine.core.RunControl;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real lease + controlled UI queue. These are admission tests, not WebView/device rendering tests. */
public final class AndroidBrowserCallTest {
    static final class Ui implements AndroidBrowserCall.UiQueue,AndroidBrowserCall.Endpoint {
        final LinkedBlockingQueue<Runnable> queue=new LinkedBlockingQueue<>();final BrowserLease lease=new BrowserLease();
        boolean isMain,keepPending;int actions,cancels;CompletableFuture<JSONObject> pending;
        Ui()throws Exception{this(null);}
        Ui(dev.pocketengine.browser.BrowserProfileRegistry.Session profile)throws Exception{if(profile!=null)lease.bindProfile(profile);JSONObject claimed=claim(ModelCapabilityAdaptersTest.RUN);if(profile!=null){JSONObject identity=profile.identity();for(java.util.Iterator<String> keys=identity.keys();keys.hasNext();){String key=keys.next();claimed.put(key,identity.get(key));}}lease.acquire(claimed);}
        public boolean isUiThread(){return isMain;}public void post(Runnable command){queue.add(command);}public AndroidBrowserCall.Endpoint current(){return this;}
        public void execute(String command,JSONObject input,CompletableFuture<JSONObject> result){try{
            if(command.equals("cancel")){lease.release(input,"cancel");cancels++;if(pending!=null)pending.complete(new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL));result.complete(new JSONObject().put("status","cancel"));return;}
            lease.require(input);actions++;if(keepPending)pending=result;else result.complete(new JSONObject().put("accepted",true));
        }catch(Exception failure){result.completeExceptionally(failure);}}
        public void timedOut(CompletableFuture<JSONObject> result){if(pending==result)lease.invalidate();}
        void next()throws Exception {Runnable command=queue.poll(2,TimeUnit.SECONDS);assertNotNull("UI command was queued",command);isMain=true;try{command.run();}finally{isMain=false;}}
        void drain(){Runnable command;while((command=queue.poll())!=null)command.run();}
        JSONObject args()throws Exception {JSONObject leaseValue=lease.snapshot();return new JSONObject().put("taskId",ModelCapabilityAdaptersTest.TASK).put("runId",ModelCapabilityAdaptersTest.RUN).put("leaseId",leaseValue.getString("leaseId")).put("profileId",leaseValue.getString("profileId")).put("profileRevision",leaseValue.getLong("profileRevision")).put("browserSessionId",leaseValue.getString("browserSessionId"));}
    }
    static JSONObject claim(String run)throws Exception {return new JSONObject().put("taskId",ModelCapabilityAdaptersTest.TASK).put("runId",run).put("principalId","owner").put("spaceId","private");}
    @Test public void realLeaseExecutorRunsOnlyAfterQueueAdmission()throws Exception {
        Ui ui=new Ui();AndroidBrowserCall bridge=new AndroidBrowserCall(ui);RunControl control=new RunControl();var worker=Executors.newSingleThreadExecutor();try{
            var call=worker.submit(()->bridge.invokeForHost("click",ui.args(),control,()->{}));ui.next();assertTrue(call.get(2,TimeUnit.SECONDS).getBoolean("accepted"));assertEquals(1,ui.actions);
        }finally{worker.shutdownNow();}
    }
    @Test public void cancellationAfterQueueingPreventsNativeActionEvenWithLongDelay()throws Exception {
        Ui ui=new Ui();AndroidBrowserCall bridge=new AndroidBrowserCall(ui);RunControl control=new RunControl();var worker=Executors.newSingleThreadExecutor();try{
            var call=worker.submit(()->bridge.invokeForHost("click",ui.args(),control,()->{}));Runnable queued=ui.queue.poll(2,TimeUnit.SECONDS);assertNotNull(queued);
            control.cancel();Thread.sleep(350);queued.run();ui.drain();assertThrows(ExecutionException.class,()->call.get(2,TimeUnit.SECONDS));assertEquals(0,ui.actions);assertFalse(ui.lease.snapshot().getBoolean("active"));
        }finally{worker.shutdownNow();}
    }
    @Test public void authorityRevocationAfterQueueingIsRecheckedOnUiEntry()throws Exception {
        Ui ui=new Ui();AndroidBrowserCall bridge=new AndroidBrowserCall(ui);RunControl control=new RunControl();AtomicBoolean valid=new AtomicBoolean(true);var worker=Executors.newSingleThreadExecutor();try{
            var call=worker.submit(()->bridge.invokeForHost("click",ui.args(),control,()->{if(!valid.get())throw new SecurityException("epoch revoked");}));Runnable queued=ui.queue.poll(2,TimeUnit.SECONDS);assertNotNull(queued);
            valid.set(false);queued.run();assertThrows(ExecutionException.class,()->call.get(2,TimeUnit.SECONDS));assertEquals(0,ui.actions);
        }finally{worker.shutdownNow();}
    }
    @Test public void cancellationRevokesSameLeaseDuringPendingNativeObservation()throws Exception {
        Ui ui=new Ui();ui.keepPending=true;AndroidBrowserCall bridge=new AndroidBrowserCall(ui);RunControl control=new RunControl();var worker=Executors.newSingleThreadExecutor();try{
            var call=worker.submit(()->bridge.invokeForHost("observe",ui.args(),control,()->{}));ui.next();assertEquals(1,ui.actions);control.cancel();ui.next();assertThrows(ExecutionException.class,()->call.get(2,TimeUnit.SECONDS));assertEquals(1,ui.cancels);assertFalse(ui.lease.snapshot().getBoolean("active"));
        }finally{worker.shutdownNow();}
    }
    @Test public void queuedOldCancellationCannotRevokeReplacementLease()throws Exception {
        Ui ui=new Ui();AndroidBrowserCall bridge=new AndroidBrowserCall(ui);RunControl control=new RunControl();var worker=Executors.newSingleThreadExecutor();try{
            var call=worker.submit(()->bridge.invokeForHost("click",ui.args(),control,()->{}));Runnable queued=ui.queue.poll(2,TimeUnit.SECONDS);assertNotNull(queued);
            ui.lease.invalidate();JSONObject replacement=ui.lease.acquire(claim(ModelCapabilityAdaptersTest.OBS));control.cancel();queued.run();ui.drain();assertThrows(ExecutionException.class,()->call.get(2,TimeUnit.SECONDS));
            assertEquals(replacement.getString("leaseId"),ui.lease.snapshot().getString("leaseId"));assertEquals(0,ui.actions);assertEquals(0,ui.cancels);
        }finally{worker.shutdownNow();}
    }
    @Test public void synchronousUiInvocationIsRejected()throws Exception {
        Ui ui=new Ui();ui.isMain=true;AndroidBrowserCall bridge=new AndroidBrowserCall(ui);
        assertThrows(IllegalStateException.class,()->bridge.invokeForHost("observe",ui.args(),new RunControl(),()->{}));assertEquals(0,ui.actions);
    }
    @Test public void cancellingPendingNamedProfileOperationRetainsExactNativeSessionIdentity()throws Exception {
        Ui ui=new Ui(dev.pocketengine.browser.BrowserProfileCapabilityBindingTest.namedSession());ui.keepPending=true;AndroidBrowserCall bridge=new AndroidBrowserCall(ui);RunControl control=new RunControl();var worker=Executors.newSingleThreadExecutor();try{
            var call=worker.submit(()->bridge.invokeForHost("observe",ui.args(),control,()->{}));ui.next();control.cancel();ui.next();assertThrows(ExecutionException.class,()->call.get(2,TimeUnit.SECONDS));assertEquals(1,ui.actions);assertEquals(1,ui.cancels);assertFalse(ui.lease.snapshot().getBoolean("active"));
        }finally{worker.shutdownNow();}
    }
}
