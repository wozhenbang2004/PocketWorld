package dev.pocketengine.features.groups;

import static org.junit.Assert.*;

import android.app.Application;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.groups.host.GroupArtifactStore;
import dev.pocketengine.features.groups.host.GroupSafeCapabilities;
import dev.pocketengine.features.groups.host.GroupTaskHost;
import dev.pocketengine.features.groups.host.GroupTaskPresentationReader;
import dev.pocketengine.store.TaskStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Actual registry/SQLite/checkpoint/AgentRunner recovery; ingress/owner/delivery ports and model HTTP are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public class GroupRecoveredRunnerJourneyTest {
    static final class ObservedHost implements GroupHostPort {
        final GroupTaskHost actual;Request admittedRequest;Authority admittedAuthority;int admissions,recoveries;
        ObservedHost(GroupTaskHost actual){this.actual=actual;}
        public TaskReceipt admit(Request request,Authority authority)throws Exception{admissions++;admittedRequest=request;admittedAuthority=authority;return actual.admit(request,authority);}
        public TaskReceipt inspect(Request request,Authority authority)throws Exception{return actual.inspect(request,authority);}
        public TaskReceipt resume(Request request,Authority authority)throws Exception{return actual.resume(request,authority);}
        public TaskReceipt recover(Request request,String run,int epoch,long revision,Authority authority)throws Exception{recoveries++;return actual.recover(request,run,epoch,revision,authority);}
        public TaskReceipt cancel(Request request,String run,int epoch,Authority authority)throws Exception{return actual.cancel(request,run,epoch,authority);}
    }
    @Test public void restartedTaskContinuesOriginalRunnerAndReturnsOneOriginalResultWithoutReplayingOldWorker()throws Exception {
        AtomicInteger modelCalls=new AtomicInteger();AtomicReference<String> modelBody=new AtomicReference<>();
        HttpServer model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/",exchange->{try{modelCalls.incrementAndGet();modelBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] response=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content","Original recovered group answer")).put("finish_reason","stop"))).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}catch(Exception failed){throw new java.io.IOException("recovery model fixture failed",failed);}finally{exchange.close();}});model.start();
        var workers=Executors.newSingleThreadExecutor();GroupTaskHost firstHost=null,recoveredHost=null;
        try(GroupsModuleTest.Fixture metadata=new GroupsModuleTest.Fixture()) {
            ModelConfig config=new ModelConfig("http://127.0.0.1:"+model.getAddress().getPort()+"/v1","","explicit-recovery-fixture",false,8,10,"openai_chat",256);
            GroupSafeCapabilities.Services services=new GroupSafeCapabilities.Services(new GroupArtifactStore(metadata.directory),null,null);
            TaskStore firstStore=new TaskStore(RuntimeEnvironment.getApplication());ArrayList<Runnable> oldWorker=new ArrayList<>();
            firstHost=new GroupTaskHost(firstStore,oldWorker::add,new ConcurrentHashMap<>(),()->config,services,id->{},message->{});ObservedHost original=new ObservedHost(firstHost);
            metadata.module.close();metadata.module=new GroupsModule(metadata.directory,metadata.ingress,original,metadata.delivery,()->{if(!metadata.ownerAllowed)throw new SecurityException("fixture owner denied");},metadata.now::get);
            JSONObject policy=metadata.policy("public.answer");policy.getJSONObject("quota").put("dailyTokens",120000).put("maxTokensPerTask",60000);metadata.module.ownerCommand("policy.set",policy);
            String event=metadata.event("original-recovery-event","alice"),requestId=metadata.requestId(metadata.module.receiveTask(event,"public.answer","group","members",60000));
            GroupHostPort.Request request=original.admittedRequest;JSONObject before=firstStore.get(request.taskId,false);assertEquals(1,before.getInt("coordinatorEpoch"));assertEquals(1,oldWorker.size());assertEquals(0,modelCalls.get());
            firstStore.reserveGroupModelTokens(request,before.getString("runId"),1,2000,original.admittedAuthority);
            firstHost.close();metadata.module.close();TaskStore restored=new TaskStore(RuntimeEnvironment.getApplication());
            recoveredHost=new GroupTaskHost(restored,workers,new ConcurrentHashMap<>(),()->config,services,id->{},message->{});ObservedHost restarted=new ObservedHost(recoveredHost);
            metadata.module=new GroupsModule(metadata.directory,metadata.ingress,restarted,metadata.delivery,()->{if(!metadata.ownerAllowed)throw new SecurityException("fixture owner denied");},metadata.now::get);
            recoveredHost.setResultSink((source,receipt)->{if(receipt.status.equals("completed"))for(GroupHostPort.Output output:receipt.outputs)metadata.module.sendOutput(source.requestId,output.outputId);});
            JSONObject interrupted=metadata.inspect(requestId).getJSONObject("task");assertEquals("interrupted",interrupted.getString("status"));
            JSONObject presentation=metadata.module.withOwnerTaskRead(requestId,(source,read)->new GroupTaskPresentationReader(restored).read(source,read));assertTrue(presentation.getJSONObject("recovery").getBoolean("canReviewContinue"));
            JSONObject selected=new JSONObject().put("requestId",requestId).put("runId",interrupted.getString("runId")).put("coordinatorEpoch",interrupted.getInt("coordinatorEpoch")).put("taskRevision",interrupted.getLong("revision"));
            metadata.module.ownerCommand("task.resume",selected);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<until){JSONArray deliveries=metadata.module.ownerSnapshot().getJSONArray("deliveries");if(restored.get(request.taskId,false).optString("status").equals("completed")&&deliveries.length()==1&&deliveries.getJSONObject(0).optString("state").equals("accepted"))break;Thread.sleep(10);}
            workers.submit(()->{}).get(3,TimeUnit.SECONDS);
            JSONObject completed=restored.get(request.taskId,false);assertEquals("completed",completed.getString("status"));assertEquals(2,completed.getInt("coordinatorEpoch"));assertEquals(request.taskId,completed.getString("id"));assertTrue(completed.getLong("groupReservedTokens")>2000);assertEquals(1,restored.list().length());
            assertEquals(1,original.admissions);assertEquals(0,restarted.admissions);assertEquals(1,restarted.recoveries);assertEquals(1,modelCalls.get());assertEquals(1,metadata.delivery.sent);
            assertTrue("group scope absent from actual model request: "+modelBody.get(),modelBody.get().contains("群内受限任务助手"));
            assertTrue("recovery notice absent from actual model request: "+modelBody.get(),modelBody.get().contains("正在恢复同一个任务"));
            assertFalse(modelBody.get().contains("memory_search"));
            oldWorker.get(0).run();assertEquals("completed",restored.get(request.taskId,false).getString("status"));assertEquals(2,restored.get(request.taskId,false).getInt("coordinatorEpoch"));assertEquals(1,modelCalls.get());assertEquals(1,metadata.delivery.sent);
        } finally {if(firstHost!=null)firstHost.close();if(recoveredHost!=null)recoveredHost.close();workers.shutdownNow();model.stop(0);}
    }
}
