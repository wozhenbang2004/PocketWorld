package dev.pocketengine.features.remote;

import android.app.Application;
import android.content.Context;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real RemoteModule receipts and HTTP. Only the credential source and polling clock are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemotePollIdentityTest {
    private static final String TOKEN="synthetic-original-remote-poll-token-over-32-characters";
    @Test public void pollRejectsOtherJobsOperationsAndKnownSessionIdentities()throws Exception {
        for(String field:new String[]{"id","operationId","environmentId","worker","sessionId","threadId","promptRequestId","turnId"}){
            try(Fixture f=new Fixture()){
                f.submit();f.events.clear();f.receipt.set(new JSONObject(f.receipt.get().toString()).put(field,"another-"+field));f.poll();
                JSONObject job=f.module.snapshot().getJSONArray("jobs").getJSONObject(0);
                assertEquals("Original observation must survive a mismatched "+field,f.original().get(field),job.get(field));
                assertEquals(1,f.module.snapshot().getJSONArray("jobs").length());
                assertFalse(f.events.stream().anyMatch(event->"job".equals(event.optJSONObject("payload").optString("kind"))));
                JSONObject lost=f.events.get(0).getJSONObject("payload");assertEquals("connection_lost",lost.getString("kind"));
                assertEquals("original-operation",lost.getString("operationId"));assertEquals(f.base(),lost.getString("serviceBase"));
            }
        }
    }
    @Test public void firstObservedSessionIsPinnedForLaterPolls()throws Exception {
        try(Fixture f=new Fixture()){
            JSONObject queued=f.original().put("status","queued");for(String field:new String[]{"sessionId","threadId","promptRequestId","turnId"})queued.remove(field);
            f.receipt.set(queued);f.submit();f.receipt.set(f.original());f.poll();f.events.clear();
            f.receipt.set(f.original().put("sessionId","replaced-session"));f.poll();
            assertEquals("original-session",f.module.snapshot().getJSONArray("jobs").getJSONObject(0).getString("sessionId"));
            assertEquals("original-session",f.module.snapshot().getJSONArray("operations").getJSONObject(0).getJSONObject("watchIdentity").getString("sessionId"));
            assertEquals("connection_lost",f.events.get(0).getJSONObject("payload").getString("kind"));
        }
    }
    @Test public void sameAddressProfileRewriteDuringReadCannotAcceptAnOldReply()throws Exception {
        try(Fixture f=new Fixture()){
            f.submit();f.events.clear();f.receipt.set(f.original().put("status","completed"));f.blockRead.set(true);
            Future<?> pending=f.work.submit(f::pollUnchecked);assertTrue(f.readEntered.await(3,TimeUnit.SECONDS));
            try{f.profile();}finally{f.releaseRead.countDown();}pending.get(4,TimeUnit.SECONDS);
            assertEquals("running",f.module.snapshot().getJSONArray("jobs").getJSONObject(0).getString("status"));assertTrue(f.events.isEmpty());
            assertEquals(1,f.gets.get());f.poll();assertEquals("Rewritten configuration must not inherit the old watch",1,f.gets.get());
        }
    }
    @Test public void sameAddressProfileRewriteBeforeReadDispatchCannotUseTheOldWatch()throws Exception {
        try(Fixture f=new Fixture()){
            f.submit();f.events.clear();f.blockDispatch.set(true);Future<?> pending=f.work.submit(f::pollUnchecked);
            assertTrue(f.dispatchEntered.await(3,TimeUnit.SECONDS));try{f.profile();}finally{f.releaseDispatch.countDown();}pending.get(4,TimeUnit.SECONDS);
            assertEquals(0,f.gets.get());assertTrue(f.events.isEmpty());
        }
    }
    @Test public void anOwnerReadStartedBeforeProfileRewriteCannotCreateANewGenerationWatch()throws Exception {
        try(Fixture f=new Fixture()){
            f.submit();f.events.clear();f.receipt.set(f.original().put("status","completed"));f.blockRead.set(true);
            Future<?> pending=f.work.submit(()->{try{return f.module.handle("getJob",f.query());}catch(Exception failure){return failure;}});
            assertTrue(f.readEntered.await(3,TimeUnit.SECONDS));try{f.profile();}finally{f.releaseRead.countDown();}
            assertTrue(pending.get(4,TimeUnit.SECONDS) instanceof SecurityException);assertTrue(f.events.isEmpty());
            assertEquals("running",f.module.snapshot().getJSONArray("jobs").getJSONObject(0).getString("status"));int before=f.gets.get();f.poll();assertEquals(before,f.gets.get());
        }
    }
    @Test public void delayedPollCannotOverwriteANewerOwnerObservation()throws Exception {
        try(Fixture f=new Fixture()){
            f.submit();f.events.clear();f.receipt.set(f.original().put("status","completed").put("updatedAt",1));f.blockRead.set(true);
            Future<?> pending=f.work.submit(f::pollUnchecked);assertTrue(f.readEntered.await(3,TimeUnit.SECONDS));
            try{f.receipt.set(f.original().put("status","awaiting_input").put("updatedAt",2));f.module.handle("getJob",f.query());}
            finally{f.releaseRead.countDown();}pending.get(4,TimeUnit.SECONDS);
            assertEquals("awaiting_input",f.module.snapshot().getJSONArray("jobs").getJSONObject(0).getString("status"));
            assertEquals(1,f.events.size());assertEquals("awaiting_input",f.events.get(0).getJSONObject("payload").getJSONObject("job").getString("status"));
        }
    }
    @Test public void coldRecoveryRetainsSessionAndConfigurationInsteadOfRebindingOldJobs()throws Exception {
        try(Fixture f=new Fixture()){
            f.submit();f.module.close();f.module=f.open();f.events.clear();f.receipt.set(f.original().put("sessionId","another-session"));f.poll();
            assertEquals(0,f.module.snapshot().getJSONArray("jobs").length());
            assertEquals("original-session",f.events.get(0).getJSONObject("payload").getString("sessionId"));
            f.profile();f.module.close();f.module=f.open();int before=f.gets.get();f.poll();assertEquals(before,f.gets.get());
        }
    }
    @Test public void repeatedTransportFailurePublishesOnlyTheOriginalJobAndOperation()throws Exception {
        try(Fixture f=new Fixture()){
            f.submit();f.events.clear();f.status=503;f.poll();f.poll();assertTrue(f.events.isEmpty());f.poll();
            JSONObject event=f.events.get(0).getJSONObject("payload");assertEquals("unknown",event.getString("status"));
            assertEquals("original-job",event.getString("jobId"));assertEquals("original-operation",event.getString("operationId"));assertEquals(f.base(),event.getString("serviceBase"));
            assertEquals("original-session",event.getString("sessionId"));assertEquals(1,f.events.size());
        }
    }
    @Test public void missingConditionalCapabilityNeverFallsBackToLegacyCancellation()throws Exception {
        try(Fixture f=new Fixture()){
            f.submit();f.originalCancellation=false;assertThrows(SecurityException.class,()->f.module.handle("cancelJob",f.cancelTarget()));
            assertEquals(0,f.cancels.get());assertEquals(0,f.legacyCancels.get());assertEquals("running",f.receipt.get().getString("status"));
        }
    }
    @Test public void aReusedServerJobIdCannotStopItsSuccessorOperation()throws Exception {
        try(Fixture f=new Fixture()){
            f.submit();f.receipt.set(f.original().put("operationId","successor-operation"));JSONObject result=f.module.handle("cancelJob",f.cancelTarget());
            assertEquals("unknown",result.getString("status"));assertEquals("original-operation",result.getString("operationId"));
            assertEquals(0,f.cancelEffects.get());assertEquals(0,f.legacyCancels.get());assertEquals("running",f.receipt.get().getString("status"));
        }
    }
    @Test public void lostCancelReceiptIsQueriedAfterRestartWithoutSendingAnotherPost()throws Exception {
        try(Fixture f=new Fixture()){
            f.submit();f.dropCancel=true;JSONObject unknown=f.module.handle("cancelJob",f.cancelTarget());assertEquals("unknown",unknown.getString("status"));
            assertEquals(1,f.cancels.get());assertEquals(1,f.cancelEffects.get());f.module.close();f.module=f.open();f.dropCancel=false;
            JSONObject actual=f.module.handle("cancelJob",f.cancelTarget());assertEquals("cancelled",actual.getString("status"));assertFalse(actual.getBoolean("cancelRequestReplayed"));
            JSONObject cancellation=f.cancelIntent();assertEquals("settled",cancellation.getString("status"));
            assertEquals("original_job_terminal_observation",cancellation.getString("settledBy"));assertFalse(cancellation.getBoolean("cancelEffectConfirmed"));
            f.module.close();f.module=f.open();assertEquals("settled",f.cancelIntent().getString("status"));
            f.module.handle("cancelJob",f.cancelTarget());
            assertEquals(1,f.cancels.get());assertEquals(0,f.legacyCancels.get());
        }
    }
    @Test public void aStillRunningOriginalJobDoesNotSettleALostCancelReceipt()throws Exception {
        try(Fixture f=new Fixture()){
            f.submit();f.dropCancel=true;f.module.handle("cancelJob",f.cancelTarget());
            f.receipt.set(f.original());f.dropCancel=false;
            assertEquals("running",f.module.handle("cancelJob",f.cancelTarget()).getString("status"));
            assertEquals("unknown",f.cancelIntent().getString("status"));assertEquals(1,f.cancels.get());
        }
    }
    @Test public void cancellationCannotInferTheOperationFromOnlyAJobId()throws Exception {
        try(Fixture f=new Fixture()){
            f.submit();assertThrows(IllegalArgumentException.class,()->f.module.handle("cancelJob",new JSONObject().put("profileId","computer").put("jobId","original-job")));
            assertEquals(0,f.cancels.get());assertEquals(0,f.legacyCancels.get());
        }
    }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;final ExecutorService work=Executors.newCachedThreadPool();final Context android=RuntimeEnvironment.getApplication();
        final List<JSONObject> events=new CopyOnWriteArrayList<>();final AtomicReference<JSONObject> receipt=new AtomicReference<>();final AtomicInteger gets=new AtomicInteger();
        final AtomicInteger cancels=new AtomicInteger(),cancelEffects=new AtomicInteger(),legacyCancels=new AtomicInteger();
        final AtomicBoolean blockRead=new AtomicBoolean(),blockDispatch=new AtomicBoolean();final CountDownLatch readEntered=new CountDownLatch(1),releaseRead=new CountDownLatch(1),dispatchEntered=new CountDownLatch(1),releaseDispatch=new CountDownLatch(1);
        volatile int status=200;volatile boolean originalCancellation=true,dropCancel;RemoteModule module;
        Fixture()throws Exception {
            android.deleteFile("remote-profiles.json");android.deleteFile("remote-operations.json");
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(work);server.createContext("/",this::reply);server.start();receipt.set(original());module=open();profile();
        }
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        JSONObject cancelIntent()throws Exception {
            org.json.JSONArray rows=module.snapshot().getJSONArray("operations");JSONObject found=null;
            for(int i=0;i<rows.length();i++)if("job_cancel".equals(rows.getJSONObject(i).optString("kind"))){assertNull(found);found=rows.getJSONObject(i);}
            assertNotNull(found);return found;
        }
        JSONObject original()throws Exception{return new JSONObject().put("id","original-job").put("operationId","original-operation").put("environmentId","workspace").put("worker","command").put("status","running")
                .put("sessionId","original-session").put("threadId","original-thread").put("promptRequestId","original-prompt").put("turnId","original-turn");}
        RemoteModule open()throws Exception {
            RemoteModule value=new RemoteModule(new FeatureContext(android,new SettingsStore(android),new SecretVault(android),events::add),(id,base)->TOKEN,path->{
                if(path.startsWith("/v1/jobs/")&&blockDispatch.compareAndSet(true,false)){dispatchEntered.countDown();if(!releaseDispatch.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Dispatch fixture timed out");}
            });
            // Drive the real private poll method deterministically; do not fabricate a watch.
            Field clock=RemoteModule.class.getDeclaredField("poller");clock.setAccessible(true);((ScheduledExecutorService)clock.get(value)).shutdownNow();return value;
        }
        void profile()throws Exception{module.handle("saveProfile",new JSONObject().put("id","computer").put("name","电脑").put("baseUrl",base()));}
        void submit()throws Exception{module.handle("submitJob",new JSONObject().put("profileId","computer").put("operationId","original-operation").put("environmentId","workspace").put("worker","command").put("argv",new JSONArray().put("git").put("status")));}
        JSONObject query()throws Exception{return new JSONObject().put("profileId","computer").put("jobId","original-job").put("jobOperationId","original-operation");}
        JSONObject cancelTarget()throws Exception{return query().put("serviceBase",base()).put("_profileBinding",module.profileBindingHash("computer"));}
        void poll()throws Exception{Method method=RemoteModule.class.getDeclaredMethod("poll");method.setAccessible(true);method.invoke(module);}
        void pollUnchecked(){try{poll();}catch(Exception failure){throw new RuntimeException(failure);}}
        void reply(HttpExchange exchange){
            try{
                assertEquals("Bearer "+TOKEN,exchange.getRequestHeaders().getFirst("Authorization"));byte[] request=exchange.getRequestBody().readAllBytes();
                JSONObject actual=new JSONObject(receipt.get().toString());String path=exchange.getRequestURI().getPath();int responseStatus=status;
                if(path.equals("/v1/status")){
                    actual=new JSONObject();if(originalCancellation)actual.put("originalJobCancellation",new JSONObject().put("supported",true).put("protocol",RemoteOriginalCancellation.PROTOCOL)
                            .put("endpoint","/v1/jobs/{jobId}/cancel-original").put("identityCheck","locked_job_operation_before_mutation").put("scope","job_manager_cli_jobs_only").put("requiredFields",new JSONArray().put("jobOperationId")));
                }else if(path.endsWith("/cancel-original")){
                    cancels.incrementAndGet();JSONObject body=new JSONObject(new String(request,StandardCharsets.UTF_8));
                    if(!body.getString("jobOperationId").equals(actual.getString("operationId")))responseStatus=409;
                    else{cancelEffects.incrementAndGet();actual.put("status","cancelled");receipt.set(new JSONObject(actual.toString()));actual.put("originalCancellation",new JSONObject().put("protocol",RemoteOriginalCancellation.PROTOCOL).put("jobId",actual.getString("id")).put("jobOperationId",actual.getString("operationId")).put("identityMatched",true).put("effectsReversed",false));}
                    if(dropCancel)return;
                }else if(path.endsWith("/cancel")){
                    legacyCancels.incrementAndGet();cancelEffects.incrementAndGet();actual.put("status","cancelled");receipt.set(new JSONObject(actual.toString()));
                }else if(exchange.getRequestMethod().equals("GET")){
                    gets.incrementAndGet();if(blockRead.compareAndSet(true,false)){readEntered.countDown();if(!releaseRead.await(3,TimeUnit.SECONDS))throw new IllegalStateException("Read fixture timed out");}
                }
                byte[] data=new JSONObject().put("ok",responseStatus==200).put("result",actual).put("error","Synthetic unavailable").toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(responseStatus,data.length);exchange.getResponseBody().write(data);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{exchange.close();}
        }
        public void close(){releaseRead.countDown();releaseDispatch.countDown();module.close();server.stop(0);work.shutdownNow();}
    }
}
