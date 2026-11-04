package dev.pocketengine.features.memory;

import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.TaskStore;
import dev.pocketengine.testutil.HttpServer;
import dev.pocketengine.testutil.ReadyApplicationFixture;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Explicit original Application host -> Gateway -> real loopback HTTP -> original SQL operation recovery.
 * The server is a transport fixture; no live-provider charge or device behavior is claimed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=ReadyApplicationFixture.NoUnrelatedStartup.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SourceLearningAttemptBudgetNativeTest {
    private static final String SOURCE="SINGLE_BATCH_ORIGINAL_SOURCE_71: only this selected result may be sent.";
    private static final String MODEL="single-attempt-memory-fixture";
    @Before public void keys()throws Exception{
        java.security.Security.addProvider(new java.security.Provider("SourceLearningAttemptKeyStore",1.0,"Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});
        ReadyApplicationFixture.initializeHost((EngineApplication)RuntimeEnvironment.getApplication());
    }
    @After public void reset()throws Exception{
        try{ReadyApplicationFixture.closeHost((EngineApplication)RuntimeEnvironment.getApplication());}
        finally{java.security.Security.removeProvider("SourceLearningAttemptKeyStore");}
    }
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}

    private static final class Fixture implements AutoCloseable {
        final EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        final MemoryModule memory=(MemoryModule)app.features.module("memory");
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final AtomicInteger requests=new AtomicInteger();final AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        final CopyOnWriteArrayList<InferenceReceipt> starts=new CopyOnWriteArrayList<>(),finishes=new CopyOnWriteArrayList<>();
        final String task;final SourceRef source;final boolean rateLimited;
        Fixture(boolean rateLimited)throws Exception{
            this.rateLimited=rateLimited;
            JSONObject evidence=app.tasks.create("Original source for one bounded learning attempt");String evidenceTask=evidence.getString("id");
            app.tasks.status(evidenceTask,"completed",SOURCE);app.tasks.event(evidenceTask,new JSONObject().put("type","user_verification").put("completed",true));
            source=app.tasks.resolveMemorySource(MemoryBook.Scope.from(scope()),"task_evidence","task:"+evidenceTask+":verified_result").ref;
            task=app.createSourceLearningJob(new JSONArray().put(source.toJson())).getString("taskId");
            server.createContext("/",exchange->{try{
                // HttpServer invokes the handler only after reading the complete Content-Length.
                byte[] body=exchange.getRequestBody().readAllBytes();requests.incrementAndGet();
                assertEquals("POST",exchange.getRequestMethod());assertEquals(Integer.parseInt(exchange.getRequestHeaders().getFirst("Content-Length")),body.length);
                JSONObject request=new JSONObject(new String(body,StandardCharsets.UTF_8));assertEquals(MODEL,request.getString("model"));
                assertTrue(request.getJSONArray("messages").toString().contains(SOURCE));assertFalse(request.has("tools"));
                if(rateLimited){
                    exchange.getResponseHeaders().add("Retry-After","0");
                    byte[] response="{\"error\":{\"type\":\"rate_limit_exceeded\",\"code\":\"rate_limit_exceeded\",\"message\":\"fixture transient rate limit\"}}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(429,response.length);exchange.getResponseBody().write(response);
                }
                // Otherwise close after the full POST, without sending any response headers.
            }catch(Throwable failed){serverFailure.compareAndSet(null,failed);}finally{exchange.close();}});server.start();
            app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","http://127.0.0.1:"+server.getAddress().getPort())
                    .putString("provider","openai_chat").putString("model",MODEL).putInt("maxSteps",8).commit();
            InferenceCapture.installPresentationObserver(new InferenceCapture.Sink(){
                public void started(InferenceReceipt receipt){if(task.equals(receipt.context.taskId))starts.add(receipt);}
                public void finished(InferenceReceipt receipt){if(task.equals(receipt.context.taskId))finishes.add(receipt);}
            });
        }
        JSONObject snapshot()throws Exception{return app.tasks.sourceLearningJob(task);}
        JSONObject operation(TaskStore store)throws Exception{
            JSONArray operations=store.get(task,false).getJSONArray("operations");JSONObject found=null;
            for(int i=0;i<operations.length();i++)if(SourceLearningJob.TOOL.equals(operations.getJSONObject(i).optString("tool"))){assertNull("one selected source owns one operation",found);found=operations.getJSONObject(i);}
            assertNotNull(found);return found;
        }
        void failOneOriginalAttempt()throws Exception{
            RunControl original=new RunControl();long revision=snapshot().getJSONObject("job").getLong("revision");
            assertThrows(ModelGateway.GatewayException.class,()->app.runSourceLearningJob(task,revision,original,1));
            if(serverFailure.get()!=null)throw new AssertionError("loopback received an invalid original request",serverFailure.get());
            assertEquals("one native batch cannot expand into Gateway retries",1,requests.get());
            assertEquals(1,starts.size());assertEquals(1,finishes.size());InferenceReceipt actual=finishes.get(0);
            assertEquals(starts.get(0).callId,actual.callId);assertEquals(InferenceReceipt.Outcome.FAILED,actual.outcome);
            assertEquals(InferenceReceipt.Purpose.SUMMARY,actual.context.purpose);assertEquals(task,actual.context.taskId);
            assertTrue(original.matchesHostRun(task,actual.context.runId,actual.context.epoch));assertEquals(MODEL,actual.toJson().getJSONObject("route").getString("requestedModel"));
            JSONObject failure=actual.toJson().getJSONObject("failure");assertEquals(rateLimited?"RATE_LIMIT":"NETWORK",failure.getString("kind"));
            assertFalse(failure.getBoolean("providerProcessingKnown"));assertFalse(failure.getBoolean("chargeKnown"));
            if(rateLimited){assertEquals(429,failure.getInt("httpStatus"));assertEquals("VALID",failure.getJSONObject("retry").getString("status"));assertEquals(0,failure.getJSONObject("retry").getLong("delaySeconds"));}
            JSONObject snapshot=snapshot(),job=snapshot.getJSONObject("job");assertEquals(1,job.getInt("attemptMaxBatches"));assertEquals(1,job.getInt("reservedModelAttempts"));
            assertEquals(0,job.getInt("nextIndex"));assertEquals(0,job.getJSONArray("results").length());assertEquals("unknown",snapshot.getJSONObject("active").getString("stage"));
            assertFalse(snapshot.getBoolean("canContinue"));assertTrue(snapshot.getBoolean("canSkipBatch"));assertEquals("reconciling",snapshot.getString("taskStatus"));
            JSONObject operation=operation(app.tasks);assertEquals("unknown",operation.getString("state"));assertEquals(actual.context.runId,operation.getString("runId"));
            JSONObject unknown=operation.getJSONObject("receipt");assertEquals("native_source_learning_unknown",unknown.getString("kind"));
            assertFalse(unknown.getBoolean("automaticRetry"));assertFalse(unknown.getBoolean("providerChargeKnown"));assertFalse(unknown.getBoolean("budgetRefunded"));
            assertEquals(0,memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
            assertEquals(0,memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());
            assertActualReceipt(app.tasks,actual);
            // A second owner click on this same unresolved batch cannot obtain another send.
            assertThrows(Exception.class,()->app.runSourceLearningJob(task,snapshot().getJSONObject("job").getLong("revision"),new RunControl(),1));
            assertEquals(1,requests.get());assertEquals(1,snapshot().getJSONObject("job").getInt("reservedModelAttempts"));
        }
        void assertActualReceipt(TaskStore store,InferenceReceipt expected)throws Exception{
            JSONObject usage=store.inferenceUsage(task);assertEquals(1,usage.getInt("attempts"));assertEquals(0,usage.getInt("completed"));
            JSONArray logical=usage.getJSONArray("logicalRequests");assertEquals(1,logical.length());JSONObject request=logical.getJSONObject(0),control=request.getJSONObject("control");
            assertEquals(rateLimited?"FAILED":"UNKNOWN",request.getString("state"));assertEquals(1,control.getInt("maxAttempts"));assertEquals(0,control.optLong("waitedMillis"));
            assertEquals(1,control.getJSONArray("attempts").length());assertEquals(expected.callId,control.getJSONArray("attempts").getJSONObject(0).getString("receiptRef"));
            JSONObject stored=null;JSONArray events=store.get(task,false).getJSONArray("events");
            for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);if("inference_receipt".equals(event.optString("type"))&&expected.callId.equals(event.optString("callId")))stored=event;}
            assertNotNull("actual terminal accounting remains in the original TaskStore",stored);assertEquals("FAILED",stored.getString("outcome"));
            assertEquals(expected.context.runId,stored.getJSONObject("context").getString("runId"));assertEquals(expected.context.epoch,stored.getJSONObject("context").getInt("epoch"));
            assertEquals(expected.toJson().getJSONObject("failure").toString(),stored.getJSONObject("failure").toString());
        }
        void coldRecoveryAndExplicitSkip()throws Exception{
            String operationId=operation(app.tasks).getString("operationId");InferenceReceipt actual=finishes.get(0);
            // Reopen the original SQL store; do not invent a resumable RunControl or second task.
            TaskStore cold=new TaskStore(app);
            try{
                JSONObject restored=cold.sourceLearningJob(task);assertEquals("unknown",restored.getJSONObject("active").getString("stage"));assertEquals(operationId,restored.getJSONObject("active").getString("operationId"));
                assertFalse(restored.getBoolean("canContinue"));assertEquals(0,restored.getJSONObject("job").getInt("nextIndex"));
                assertThrows(Exception.class,()->cold.startSourceLearningJob(task,restored.getJSONObject("job").getLong("revision"),new RunControl(),1));
                assertEquals("unknown",operation(cold).getString("state"));assertActualReceipt(cold,actual);assertEquals(1,requests.get());
            }finally{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);((SQLiteDatabase)field.get(cold)).close();}
            long revision=snapshot().getJSONObject("job").getLong("revision");
            assertThrows(SecurityException.class,()->app.skipSourceLearningJobBatch(task,revision,false));assertEquals(0,snapshot().getJSONObject("job").getInt("nextIndex"));
            JSONObject skipped=app.skipSourceLearningJobBatch(task,revision,true),job=skipped.getJSONObject("job"),result=job.getJSONArray("results").getJSONObject(0);
            assertEquals(1,job.getInt("nextIndex"));assertEquals(1,job.getInt("reservedModelAttempts"));assertFalse(skipped.has("active"));assertFalse(skipped.getBoolean("canContinue"));
            assertEquals("owner_skipped_not_retried",result.getString("outcome"));assertFalse(result.getBoolean("providerChargeKnown"));assertFalse(result.getBoolean("budgetRefunded"));
            assertEquals(operationId,operation(app.tasks).getString("operationId"));assertEquals("resolved",operation(app.tasks).getString("state"));assertActualReceipt(app.tasks,actual);
            assertEquals(1,requests.get());assertEquals(1,starts.size());assertEquals(0,memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length());
        }
        @Override public void close(){server.stop(0);}
    }

    @Test public void completePostThenLostResponseIsUnknownAndColdRecoveryNeverResends()throws Exception{
        try(Fixture fixture=new Fixture(false)){fixture.failOneOriginalAttempt();fixture.coldRecoveryAndExplicitSkip();}
    }
    @Test public void explicitTransient429AndRetryAfterCannotExpandTheSelectedBatchBudget()throws Exception{
        try(Fixture fixture=new Fixture(true)){fixture.failOneOriginalAttempt();fixture.coldRecoveryAndExplicitSkip();}
    }
}
