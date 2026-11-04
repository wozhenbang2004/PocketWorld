package dev.pocketengine.features.memory;

import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.testutil.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=dev.pocketengine.testutil.ReadyApplicationFixture.NoUnrelatedStartup.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class EngineMemorySendAdmissionTest {
    private EngineApplication fixtureHost;
    @org.junit.Before public void keys()throws Exception {
        java.security.Security.addProvider(new java.security.Provider("MemoryJourneyKeyStore",1.0,"Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});
        fixtureHost=(EngineApplication)RuntimeEnvironment.getApplication();
        dev.pocketengine.testutil.ReadyApplicationFixture.initializeHost(fixtureHost);
    }
    @org.junit.After public void reset()throws Exception {
        try{if(fixtureHost!=null)dev.pocketengine.testutil.ReadyApplicationFixture.closeHost(fixtureHost);}
        finally{java.security.Security.removeProvider("MemoryJourneyKeyStore");InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r){}});}
    }
    @Test public void originalApplicationStopCannotFollowAReusedLearningTaskIdIntoTheNextRun()throws Exception{
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();JSONArray sources=new JSONArray();
        for(int i=0;i<2;i++){JSONObject evidence=app.tasks.create("Source for original stop regression "+i);String evidenceTask=evidence.getString("id");app.tasks.status(evidenceTask,"completed","Original bounded learning source "+i);app.tasks.event(evidenceTask,new JSONObject().put("type","user_verification").put("completed",true));sources.put(app.tasks.resolveMemorySource(MemoryBook.Scope.from(new JSONObject().put("ownerId","owner").put("spaceId","private")),"task_evidence","task:"+evidenceTask+":verified_result").ref.toJson());}
        String task=app.createSourceLearningJob(sources).getString("taskId");AtomicInteger http=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{http.incrementAndGet();byte[] bytes="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"null\"},\"finish_reason\":\"stop\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var worker=java.util.concurrent.Executors.newSingleThreadExecutor();RunControl first=new RunControl(),second=new RunControl();
        try{
            app.settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("provider","openai_chat").put("model","stop-original-fixture").put("maxSteps",8));
            JSONObject a=app.runSourceLearningJob(task,app.sourceLearningJob(task).getJSONObject("job").getLong("revision"),first,1);assertEquals(1,http.get());assertEquals(1,a.getJSONObject("job").getInt("nextIndex"));
            JSONArray events=app.tasks.get(task,false).getJSONArray("events");JSONObject actualReceipt=null;
            for(int i=0;i<events.length();i++){
                JSONObject event=events.getJSONObject(i);if(!"inference_receipt".equals(event.optString("type")))continue;
                assertNull("The first batch must retain one original model receipt",actualReceipt);actualReceipt=event;
            }
            assertNotNull("The completed batch must keep its actual SQL model receipt",actualReceipt);
            assertEquals("COMPLETED",actualReceipt.getString("outcome"));assertEquals(a.getString("runId"),actualReceipt.getJSONObject("context").getString("runId"));
            JSONObject budget=actualReceipt.getJSONObject("tokenBudget");assertEquals("pocketworld.token-budget-receipt.v1",budget.getString("format"));
            assertEquals("A response without provider usage must keep its unknown budget accounting","unknown",budget.getString("state"));
            assertTrue("Completing a source batch must not invent actual token usage",budget.has("actualTokens")&&budget.isNull("actualTokens"));
            JSONObject delayed=new JSONObject().put("taskId",task).put("runId",a.getString("runId")).put("epoch",a.getInt("epoch"));
            // Pause only the actual second Gateway admission. Both Application live maps,
            // TaskStore runs and real controllers are populated by runSourceLearningJob.
            InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt receipt)throws Exception{if(task.equals(receipt.context.taskId)){entered.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("original stop test was not released");}}public void finished(InferenceReceipt receipt){}});
            long revision=app.sourceLearningJob(task).getJSONObject("job").getLong("revision");var running=worker.submit(()->app.runSourceLearningJob(task,revision,second,1));assertTrue("second actual admission must be reached",entered.await(5,java.util.concurrent.TimeUnit.SECONDS));JSONObject b=app.sourceLearningJob(task);assertNotEquals(a.getString("runId"),b.getString("runId"));assertTrue(b.getInt("epoch")>a.getInt("epoch"));
            assertThrows(SecurityException.class,()->app.sourceLearningJobCommand("stopLearningJob",delayed,new RunControl()));assertThrows(SecurityException.class,()->app.dispatchOwnerAction("stopTask",delayed));
            assertThrows(SecurityException.class,()->app.sourceLearningJobCommand("stopLearningJob",new JSONObject().put("taskId",task),new RunControl()));assertThrows(SecurityException.class,()->app.dispatchOwnerAction("stopTask",new JSONObject().put("taskId",task)));
            assertFalse(second.isCancelled());assertFalse(app.tasks.get(task,false).optBoolean("cancelRequested"));
            JSONObject exact=new JSONObject().put("taskId",task).put("runId",b.getString("runId")).put("epoch",b.getInt("epoch"));JSONObject stopped=app.sourceLearningJobCommand("stopLearningJob",exact,new RunControl());
            assertTrue(second.isCancelled());assertTrue(stopped.getJSONObject("stopReceipt").getBoolean("requested"));assertTrue(stopped.getJSONObject("stopReceipt").getBoolean("originalControllerSignalled"));assertEquals(b.getString("runId"),stopped.getString("runId"));assertFalse(first.isCancelled());
            release.countDown();assertThrows(java.util.concurrent.ExecutionException.class,()->running.get(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals("stopped second admission cannot dispatch another HTTP request",1,http.get());
        }finally{second.cancel();release.countDown();worker.shutdownNow();server.stop(0);}
    }
    @Test public void originalApplicationShortcutRejectsForgottenParentsAfterStartedBeforeHttp()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();MemoryModule memory=(MemoryModule)app.features.module("memory");JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");JSONArray selected=new JSONArray();
        for(String text:new String[]{"first parent","second parent"}){JSONObject row=memory.handle("save",new JSONObject().put("scope",scope).put("record",new JSONObject().put("text",text))).getJSONObject("record");selected.put(new JSONObject().put("id",row.getString("id")).put("revision",row.getLong("revision")));}
        AtomicInteger http=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{http.incrementAndGet();byte[] bytes="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"null\"},\"finish_reason\":\"stop\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try{
            app.settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("provider","openai_chat").put("model","memory-model-fixture").put("maxSteps",8));
            InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt r)throws Exception{memory.handle("forget",new JSONObject().put("scope",scope).put("id",selected.getJSONObject(0).getString("id")));}public void finished(InferenceReceipt r){}});
            try{app.proposeMemoryConsolidation(selected,new RunControl());fail("forgotten selection accepted");}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){}
            assertEquals(0,http.get());assertEquals(0,memory.handle("listCandidates",new JSONObject().put("scope",scope)).getJSONArray("candidates").length());
        }finally{server.stop(0);}
    }
    @Test public void originalSourceShortcutRejectsRetiredSnapshotAfterStartedBeforeHttp()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();MemoryModule memory=(MemoryModule)app.features.module("memory");JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        JSONObject task=app.tasks.create("original confirmed task");String id=task.getString("id");app.tasks.status(id,"completed","checked source result");app.tasks.event(id,new JSONObject().put("type","user_verification").put("completed",true));SourceRef source=app.tasks.resolveMemorySource(MemoryBook.Scope.from(scope),"task_evidence","task:"+id+":verified_result").ref;
        AtomicInteger http=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{http.incrementAndGet();byte[] bytes="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"null\"},\"finish_reason\":\"stop\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try{
            app.settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("provider","openai_chat").put("model","memory-model-fixture").put("maxSteps",8));
            InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt r)throws Exception{app.tasks.forgetMemorySourceSnapshot(source);}public void finished(InferenceReceipt r){}});
            try{app.proposeSourceMemoryLearning(source,new RunControl());fail("retired selection accepted");}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){}
            assertEquals(0,http.get());assertEquals(0,memory.handle("listCandidates",new JSONObject().put("scope",scope)).getJSONArray("candidates").length());
        }finally{server.stop(0);}
    }

    @Test public void originalConsolidationRejectsRetiredTaskSnapshotEvenWhileFormalParentsRemain()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();MemoryModule memory=(MemoryModule)app.features.module("memory");
        JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private"),task=app.tasks.create("original consolidation task source");
        String taskId=task.getString("id");app.tasks.status(taskId,"completed","owner confirmed source summary");app.tasks.event(taskId,new JSONObject().put("type","user_verification").put("completed",true));
        SourceRef source=app.tasks.resolveMemorySource(MemoryBook.Scope.from(scope),"task_evidence","task:"+taskId+":verified_result").ref;
        JSONArray selected=new JSONArray();
        for(String text:new String[]{"first task-backed parent","second task-backed parent"}){
            JSONObject row=memory.handle("save",new JSONObject().put("scope",scope).put("record",new JSONObject().put("text",text).put("sourceRefs",new JSONArray().put(source.toJson())))).getJSONObject("record");
            selected.put(new JSONObject().put("id",row.getString("id")).put("revision",row.getLong("revision")));
        }
        AtomicInteger http=new AtomicInteger(),retired=new AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{http.incrementAndGet();byte[] bytes="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"null\"},\"finish_reason\":\"stop\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try{
            app.settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("provider","openai_chat").put("model","memory-model-fixture").put("maxSteps",8));
            InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt r)throws Exception{retired.addAndGet(app.tasks.forgetMemorySourceSnapshot(source).getInt("removedSnapshots"));}public void finished(InferenceReceipt r){}});
            try{app.proposeMemoryConsolidation(selected,new RunControl());fail("retired original task snapshot reached consolidation HTTP");}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){}
            assertTrue("fixture must reach the actual STARTED gate",retired.get()>0);assertEquals(0,http.get());
            assertEquals(2,memory.handle("list",new JSONObject().put("scope",scope)).getJSONArray("records").length());
            assertEquals(0,memory.handle("listCandidates",new JSONObject().put("scope",scope)).getJSONArray("candidates").length());
        }finally{server.stop(0);}
    }

    @Test public void bothShortcutResponsesCannotPublishAfterOriginalSnapshotRetiresDuringHttp()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();MemoryModule memory=(MemoryModule)app.features.module("memory");
        JSONObject scope=new JSONObject().put("ownerId","owner").put("spaceId","private");
        for(boolean consolidation:new boolean[]{false,true}){
            JSONObject task=app.tasks.create("source retained until actual HTTP "+consolidation);String taskId=task.getString("id");
            app.tasks.status(taskId,"completed","confirmed original task result");app.tasks.event(taskId,new JSONObject().put("type","user_verification").put("completed",true));
            SourceRef source=app.tasks.resolveMemorySource(MemoryBook.Scope.from(scope),"task_evidence","task:"+taskId+":verified_result").ref;JSONArray selected=new JSONArray();
            if(consolidation)for(String text:new String[]{"first post-send parent","second post-send parent"}){
                JSONObject row=memory.handle("save",new JSONObject().put("scope",scope).put("record",new JSONObject().put("text",text).put("sourceRefs",new JSONArray().put(source.toJson())))).getJSONObject("record");
                selected.put(new JSONObject().put("id",row.getString("id")).put("revision",row.getLong("revision")));
            }
            AtomicInteger http=new AtomicInteger(),retired=new AtomicInteger();java.util.concurrent.atomic.AtomicReference<Throwable> serverFailure=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> originalTask=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<InferenceReceipt> completed=new java.util.concurrent.atomic.AtomicReference<>();
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{
                String request=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);http.incrementAndGet();
                assertTrue(request.contains(consolidation?"first post-send parent":"confirmed original task result"));
                retired.addAndGet(app.tasks.forgetMemorySourceSnapshot(source).getInt("removedSnapshots"));
                JSONObject draft=new JSONObject().put("layer","fact").put("text","late candidate from retired original").put("reason","original source selected before retirement").put("applicability","").put("confidence",0.2);
                byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content",draft.toString())).put("finish_reason","stop"))).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable failure){serverFailure.compareAndSet(null,failure);}finally{exchange.close();}});server.start();
            try{
                app.settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("provider","openai_chat").put("model","memory-model-fixture").put("maxSteps",8));
                InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){public void started(InferenceReceipt r){originalTask.set(r.context.taskId);}public void finished(InferenceReceipt r){completed.set(r);}});
                try{if(consolidation)app.proposeMemoryConsolidation(selected,new RunControl());else app.proposeSourceMemoryLearning(source,new RunControl());fail("retired source published a late candidate");}catch(IllegalArgumentException|IllegalStateException|SecurityException expected){}
                if(serverFailure.get()!=null)throw new AssertionError(serverFailure.get());
                assertEquals("already sent bytes are not described as a zero-dispatch cancellation",1,http.get());assertTrue(retired.get()>0);
                assertNotNull(completed.get());assertEquals(InferenceReceipt.Outcome.COMPLETED,completed.get().outcome);
                assertEquals(originalTask.get(),completed.get().context.taskId);assertEquals("failed",app.tasks.get(originalTask.get(),false).getString("status"));
                assertEquals(0,memory.handle("listCandidates",new JSONObject().put("scope",scope)).getJSONArray("candidates").length());
            }finally{server.stop(0);}
        }
    }

}
