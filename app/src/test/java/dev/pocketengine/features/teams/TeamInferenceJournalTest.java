package dev.pocketengine.features.teams;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import com.sun.net.httpserver.HttpServer;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.runtime.usage.ProviderUsage;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual TeamsModule/coordinator -> native original TaskStore -> HTTP, with no invented SQL runs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class TeamInferenceJournalTest {
    private static final class Rig implements AutoCloseable {
        final TaskStore tasks=new TaskStore(RuntimeEnvironment.getApplication());
        final SettingsStore settings=new SettingsStore(RuntimeEnvironment.getApplication());
        final HttpServer http;final TeamsModule module;final AtomicInteger requests=new AtomicInteger();
        final java.util.List<String> wireModels=new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.ExecutorService httpWorkers;
        final String operation=UUID.randomUUID().toString(),definition;
        volatile CountDownLatch arrived,release;volatile boolean invalidPeerCall;
        Rig(boolean bind)throws Exception{
            http=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            httpWorkers=java.util.concurrent.Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"team-usage-http");t.setDaemon(true);return t;});http.setExecutor(httpWorkers);
            http.createContext("/",exchange->{requests.incrementAndGet();try{
                JSONObject input=new JSONObject(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                wireModels.add(input.getString("model"));
                CountDownLatch wait=release;if(arrived!=null)arrived.countDown();if(wait!=null)wait.await(2,TimeUnit.SECONDS);
                JSONObject answer=new JSONObject().put("role","assistant").put("content","actual member output");
                if(invalidPeerCall)answer.put("tool_calls",new JSONArray().put(new JSONObject().put("id","filtered-peer").put("type","function").put("function",new JSONObject().put("name","team_send_message").put("arguments",new JSONObject().put("targetMemberId",input.getString("model").equals("member-model-a")?"member-b":"member-a").put("text","must never be delivered").toString()))));
                byte[] body=new JSONObject().put("model",input.getString("model")).put("choices",new JSONArray().put(new JSONObject()
                        .put("finish_reason",invalidPeerCall?"content_filter":"stop").put("message",answer)))
                        .put("usage",new JSONObject().put("prompt_tokens",11).put("completion_tokens",3).put("total_tokens",14)).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(Exception ignored){}finally{exchange.close();}});http.start();
            settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:"+http.getAddress().getPort()).put("model","base-model").put("provider","openai_chat").put("sendScreenshots",false).put("maxOutputTokens",512));
            module=new TeamsModule(new FeatureContext(RuntimeEnvironment.getApplication(),settings,new SecretVault(RuntimeEnvironment.getApplication()),event->{
                try{JSONObject payload=event.optJSONObject("payload"),run=payload==null?null:payload.optJSONObject("run");if(run!=null)tasks.updateExternal(operation,run);}catch(Exception failure){throw new RuntimeException(failure);}
            }));
            JSONArray members=new JSONArray().put(new JSONObject().put("id","member-a").put("name","A").put("role","first role").put("model","member-model-a"))
                    .put(new JSONObject().put("id","member-b").put("name","B").put("role","second role").put("model","member-model-b"));
            definition=module.handle("save",new JSONObject().put("name","native team").put("members",members).put("discussionFormat","classic")).getString("id");
            tasks.beginExternal("teams","start",operation,new JSONObject().put("goal","original team goal").put("teamId",definition));
            if(bind)module.setInferenceFactory((call,control)->tasks.teamInferenceTarget(call.nativeRunId,call.purpose,call.memberId,call.site,control,call::checkCurrent));
        }
        void start()throws Exception{module.handle("start",new JSONObject().put("teamId",definition).put("goal","original team goal").put("rounds",1).put("_operationId",operation));}
        JSONObject run()throws Exception{return module.handle("get",new JSONObject().put("runId",operation));}
        JSONObject done()throws Exception{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);JSONObject value;
            do{value=run();if(value.optString("status").matches("completed|failed|cancelled"))return value;Thread.sleep(10);}while(System.nanoTime()<end);
            throw new AssertionError("team did not terminate: "+value);
        }
        long nativeRuns()throws Exception{
            var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);
            try(var rows=db.rawQuery("SELECT COUNT(*) FROM runs",null)){assertTrue(rows.moveToFirst());return rows.getLong(0);}
        }
        public void close(){if(release!=null)release.countDown();module.close();http.stop(0);httpWorkers.shutdownNow();
            try{var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(tasks);if(db.isOpen())db.close();}catch(Exception failure){throw new RuntimeException(failure);}}
    }
    @Test public void realMembersAndSynthesisUseOriginalExternalRunWithoutMintingHostRuns()throws Exception{
        try(Rig rig=new Rig(true)){
            assertEquals(0,rig.nativeRuns());rig.start();assertEquals("completed",rig.done().getString("status"));
            assertEquals(3,rig.requests.get());assertEquals(0,rig.nativeRuns());assertEquals(1,rig.tasks.list().length());
            JSONObject usage=rig.tasks.inferenceUsage(rig.operation);assertEquals(3,usage.getInt("attempts"));assertEquals(3,usage.getInt("completed"));
            Set<String> memberIds=new HashSet<>(),models=new HashSet<>();int summaries=0;
            JSONArray receipts=usage.getJSONArray("receipts");for(int i=0;i<receipts.length();i++){
                JSONObject receipt=receipts.getJSONObject(i),context=receipt.getJSONObject("context");
                assertEquals(rig.operation,context.getString("taskId"));assertEquals(rig.operation,context.getString("runId"));assertEquals(0,context.getInt("epoch"));
                assertEquals("EXTERNAL_TASK_RUN",context.getString("bindingKind"));assertEquals(1,context.getJSONObject("callSite").getInt("round"));
                if(context.getString("purpose").equals("MEMBER"))memberIds.add(context.getString("memberId"));else if(context.getString("purpose").equals("SUMMARY")){
                    summaries++;assertEquals("member-model-a",receipt.getJSONObject("route").getString("requestedModel"));
                }
                models.add(receipt.getJSONObject("route").getString("requestedModel"));
                assertEquals(11,receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").getLong("value"));
            }
            JSONArray logical=usage.getJSONArray("logicalRequests");assertEquals(3,logical.length());Set<String> originalMembers=new HashSet<>();for(int i=0;i<logical.length();i++){JSONObject request=logical.getJSONObject(i);assertEquals("SUCCEEDED",request.getString("state"));assertEquals(1,request.getJSONObject("control").getInt("maxAttempts"));JSONObject plan=request.getJSONObject("routeSnapshot");if("MEMBER".equals(plan.getString("purpose")))originalMembers.add(plan.getString("contextMemberId"));}assertEquals(Set.of("member-a","member-b"),originalMembers);
            assertEquals(Set.of("member-a","member-b"),memberIds);assertEquals(1,summaries);assertEquals(Set.of("member-model-a","member-model-b"),models);
            assertEquals(new HashSet<>(rig.wireModels),models);
            assertFalse(rig.run().getJSONArray("transcript").toString().contains("inference_receipt"));
        }
    }
    @Test public void standaloneCoordinatorWithNativeBudgetPoolKeepsExternalEpochZeroAndSettlesRealCalls()throws Exception {
        try(Rig rig=new Rig(true)){
            FeatureContext context=new FeatureContext(RuntimeEnvironment.getApplication(),rig.settings,new SecretVault(RuntimeEnvironment.getApplication()),event->{});
            var workspace=new dev.pocketengine.features.workspace.WorkspaceModule(context);
            rig.module.setWriteHost(new TaskStoreTeamWriteHost(rig.tasks,new TeamWorkspaceAccess(workspace),changed->{}));
            assertEquals(0,rig.nativeRuns());rig.start();JSONObject result=rig.done();JSONObject pool=rig.tasks.teamBudgetSnapshot(rig.operation);
            System.out.println("STANDALONE_NATIVE_POOL "+new JSONObject().put("status",result.optString("status")).put("httpRequests",rig.requests.get()).put("hostRuns",rig.nativeRuns()).put("pool",pool).put("members",result.getJSONArray("members")));
            assertEquals("completed",result.getString("status"));assertEquals(3,rig.requests.get());assertEquals(0,rig.nativeRuns());
            JSONObject task=rig.tasks.get(rig.operation,false);assertFalse(task.has("executionScope"));assertEquals(rig.operation,task.getString("runId"));assertEquals(0,task.getInt("coordinatorEpoch"));
            assertEquals(3,pool.getInt("modelRequestsReservedOrUsed"));assertEquals(0,pool.getInt("toolOperationsReservedOrUsed"));
            JSONObject usage=rig.tasks.inferenceUsage(rig.operation);assertEquals(3,usage.getInt("attempts"));assertEquals(3,usage.getInt("completed"));
            JSONArray receipts=usage.getJSONArray("receipts");for(int i=0;i<receipts.length();i++){JSONObject original=receipts.getJSONObject(i).getJSONObject("context");assertEquals("EXTERNAL_TASK_RUN",original.getString("bindingKind"));assertEquals(rig.operation,original.getString("taskId"));assertEquals(rig.operation,original.getString("runId"));assertEquals(0,original.getInt("epoch"));}
            JSONArray reservations=task.getJSONObject("teamReservationPool").getJSONArray("reservations");assertEquals(3,reservations.length());for(int i=0;i<reservations.length();i++)assertEquals("settled",reservations.getJSONObject(i).getString("state"));
            JSONObject tokens=task.getJSONObject("tokenBudget");assertEquals(42,tokens.getLong("knownTokens"));assertEquals(0,tokens.getLong("heldTokens"));assertEquals(3,tokens.getLong("calls"));assertFalse(tokens.getBoolean("enabled"));
            for(int i=0;i<receipts.length();i++)assertEquals(rig.operation,receipts.getJSONObject(i).getJSONObject("tokenBudget").getString("taskOwner"));
        }
    }
    @Test public void persistentFixtureRootRequiresExplicitBaseMarkerAndNoSymlinks()throws Exception {
        String oldBase=System.getProperty("a25.syntheticFixtureBase"),oldRoot=System.getProperty("a25.budgetUpgradeRoot");
        java.nio.file.Path base=java.nio.file.Files.createTempDirectory("team-owned-base-"),owned=java.nio.file.Files.createDirectory(base.resolve("owned")),outside=java.nio.file.Files.createTempDirectory("team-outside-base-");
        try{
            System.setProperty("a25.budgetUpgradeRoot",owned.toString());System.clearProperty("a25.syntheticFixtureBase");assertThrows(IllegalStateException.class,TeamBudgetPersistentApplication::ownedRoot);
            System.setProperty("a25.syntheticFixtureBase",base.toString());assertThrows(SecurityException.class,TeamBudgetPersistentApplication::ownedRoot);
            java.nio.file.Files.writeString(owned.resolve("synthetic-fixture-owned.marker"),"test owned");assertEquals(owned,TeamBudgetPersistentApplication.ownedRoot());
            java.nio.file.Files.writeString(outside.resolve("synthetic-fixture-owned.marker"),"not inside approved base");System.setProperty("a25.budgetUpgradeRoot",outside.toString());assertThrows(SecurityException.class,TeamBudgetPersistentApplication::ownedRoot);
            java.nio.file.Files.writeString(base.resolve("synthetic-fixture-owned.marker"),"base is not an owned child");System.setProperty("a25.budgetUpgradeRoot",base.toString());assertThrows(SecurityException.class,TeamBudgetPersistentApplication::ownedRoot);
            java.nio.file.Path alias=base.resolve("alias");java.nio.file.Files.createSymbolicLink(alias,owned);System.setProperty("a25.budgetUpgradeRoot",alias.toString());assertThrows(SecurityException.class,TeamBudgetPersistentApplication::ownedRoot);
            java.nio.file.Files.delete(owned.resolve("synthetic-fixture-owned.marker"));java.nio.file.Files.createSymbolicLink(owned.resolve("synthetic-fixture-owned.marker"),outside.resolve("synthetic-fixture-owned.marker"));System.setProperty("a25.budgetUpgradeRoot",owned.toString());assertThrows(SecurityException.class,TeamBudgetPersistentApplication::ownedRoot);
        }finally{
            if(oldBase==null)System.clearProperty("a25.syntheticFixtureBase");else System.setProperty("a25.syntheticFixtureBase",oldBase);
            if(oldRoot==null)System.clearProperty("a25.budgetUpgradeRoot");else System.setProperty("a25.budgetUpgradeRoot",oldRoot);
            // Only the two exact directories created above; walk does not follow symlinks.
            for(var root:java.util.List.of(base,outside))try(var paths=java.nio.file.Files.walk(root)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}
        }
    }
    @Test public void filteredBufferedMemberToolsNeverReachPeersOrSynthesis()throws Exception{
        try(Rig rig=new Rig(true)){
            rig.invalidPeerCall=true;rig.start();JSONObject run=rig.done();assertEquals("failed",run.getString("status"));
            assertEquals(2,rig.requests.get());assertEquals(0,run.getJSONArray("mailbox").length());assertFalse(run.getJSONArray("transcript").toString().contains("must never be delivered"));
            JSONObject usage=rig.tasks.inferenceUsage(rig.operation);assertEquals(2,usage.getInt("attempts"));assertEquals(2,usage.getInt("failed"));assertEquals(0,usage.getInt("completed"));
            for(int i=0;i<usage.getJSONArray("receipts").length();i++){
                JSONObject receipt=usage.getJSONArray("receipts").getJSONObject(i);assertEquals("MEMBER",receipt.getJSONObject("context").getString("purpose"));assertEquals("content_filter",receipt.getJSONObject("usage").getString("finishReason"));
                assertEquals(11,receipt.getJSONObject("usage").getJSONObject("counters").getJSONObject("INPUT").getLong("value"));assertEquals("FAILED",receipt.getString("outcome"));
            }
        }
    }
    @Test public void noFactoryIsExplicitlyUnknownAndCannotFabricateTaskUsage()throws Exception{
        try(Rig rig=new Rig(false)){
            rig.start();assertEquals("completed",rig.done().getString("status"));assertEquals(3,rig.requests.get());
            assertFalse(rig.module.snapshot().getBoolean("usageAttributionConfigured"));assertEquals("unknown_unbound",rig.module.snapshot().getString("usageCoverage"));
            JSONObject usage=rig.tasks.inferenceUsage(rig.operation);assertEquals(0,usage.getInt("attempts"));
            assertEquals("no_accounting_records_not_zero_actual_usage",usage.getString("emptyMeaning"));assertEquals(0,rig.nativeRuns());
        }
    }
    @Test public void missingOriginalTaskAndForeignHostControlCannotBind()throws Exception{
        try(Rig rig=new Rig(false)){
            var site=new InferenceReceipt.CallSite(UUID.randomUUID().toString(),1,0,0);
            assertThrows(SecurityException.class,()->rig.tasks.teamInferenceTarget(UUID.randomUUID().toString(),InferenceReceipt.Purpose.MEMBER,"member-a",site,new RunControl(),()->{}));
            RunControl host=new RunControl();host.bindHostRun("foreign-task","foreign-run",1);
            assertThrows(SecurityException.class,()->rig.tasks.teamInferenceTarget(rig.operation,InferenceReceipt.Purpose.MEMBER,"member-a",site,host,()->{}));
            assertEquals(0,rig.tasks.inferenceUsage(rig.operation).getInt("attempts"));assertEquals(0,rig.requests.get());
        }
    }
    @Test public void lateExternalRunUsageOnlyUpdatesItsOriginalStartedReceipt()throws Exception{
        try(Rig rig=new Rig(false)){
            RunControl child=new RunControl();var site=new InferenceReceipt.CallSite(UUID.randomUUID().toString(),1,0,0);
            var target=rig.tasks.teamInferenceTarget(rig.operation,InferenceReceipt.Purpose.MEMBER,"member-a",site,child,()->{});
            assertFalse(child.hasHostRun());assertTrue(child.matchesHostRun(rig.operation,"",0));
            var capture=new InferenceCapture(new ModelConfig("https://fixture.example","","model",false,4,10),child,target);capture.dispatch();
            rig.tasks.requestCancel(rig.operation,rig.operation);child.cancel();String before=rig.tasks.get(rig.operation,false).getString("status");
            capture.usage.observe(new JSONObject().put("usage",new JSONObject().put("prompt_tokens",19)),ProviderUsage.Stage.FINAL);capture.finish(InferenceReceipt.Outcome.CANCELLED);
            assertEquals(before,rig.tasks.get(rig.operation,false).getString("status"));assertEquals(1,rig.tasks.inferenceUsage(rig.operation).getInt("cancelled"));assertEquals(0,rig.nativeRuns());
        }
    }
    @Test public void nativeCoordinatorAdmissionIsRecheckedAfterRegistration()throws Exception{
        try(Rig rig=new Rig(false)){
            AtomicInteger checks=new AtomicInteger();RunControl child=new RunControl();
            var target=rig.tasks.teamInferenceTarget(rig.operation,InferenceReceipt.Purpose.MEMBER,"member-a",new InferenceReceipt.CallSite(UUID.randomUUID().toString(),1,0,0),child,()->{
                checks.incrementAndGet();if(rig.tasks.inferenceUsage(rig.operation).getInt("attempts")>0)throw new InterruptedException("native generation changed after original STARTED receipt");
            });
            try{ModelGateway.complete(rig.settings.get(),new JSONArray().put(new JSONObject().put("role","user").put("content","native invocation")),null,child,target);fail();}catch(InterruptedException stopped){}
            assertEquals(0,rig.requests.get());assertEquals(1,rig.tasks.inferenceUsage(rig.operation).getInt("cancelled"));assertEquals(0,rig.nativeRuns());
        }
    }
    @Test public void ownerCancellationStopsBothRealMemberRequestsAndKeepsTheirUnknownAccounting()throws Exception{
        try(Rig rig=new Rig(true)){
            rig.arrived=new CountDownLatch(2);rig.release=new CountDownLatch(1);rig.start();assertTrue(rig.arrived.await(3,TimeUnit.SECONDS));
            rig.module.handle("cancel",new JSONObject().put("runId",rig.operation));rig.release.countDown();assertEquals("cancelled",rig.done().getString("status"));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);JSONObject usage;
            do{usage=rig.tasks.inferenceUsage(rig.operation);if(usage.optInt("cancelled")==2)break;Thread.sleep(10);}while(System.nanoTime()<end);
            assertEquals(2,rig.requests.get());assertEquals(2,usage.getInt("attempts"));assertEquals(2,usage.getInt("cancelled"));assertEquals(0,rig.nativeRuns());
            for(int i=0;i<usage.getJSONArray("receipts").length();i++){
                JSONObject counters=usage.getJSONArray("receipts").getJSONObject(i).getJSONObject("usage").getJSONObject("counters");
                assertTrue(counters.getJSONObject("INPUT").isNull("value"));assertEquals("unknown",counters.getJSONObject("INPUT").getString("source"));
            }
            assertEquals("cancelled",rig.tasks.get(rig.operation,false).getString("status"));
        }
    }
    @Test public void pauseAndExplicitResumeKeepTheOriginalTaskAndSeparateActualCallGenerations()throws Exception{
        try(Rig rig=new Rig(true)){
            rig.arrived=new CountDownLatch(2);rig.release=new CountDownLatch(1);rig.start();assertTrue(rig.arrived.await(3,TimeUnit.SECONDS));
            rig.module.handle("pause",new JSONObject().put("runId",rig.operation));rig.release.countDown();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(rig.tasks.inferenceUsage(rig.operation).optInt("cancelled")<2&&System.nanoTime()<end)Thread.sleep(10);
            assertEquals(2,rig.tasks.inferenceUsage(rig.operation).getInt("cancelled"));
            rig.release=null;rig.arrived=null;rig.module.handle("resume",new JSONObject().put("runId",rig.operation));
            assertEquals("completed",rig.done().getString("status"));assertEquals(5,rig.requests.get());
            JSONObject usage=rig.tasks.inferenceUsage(rig.operation);assertEquals(5,usage.getInt("attempts"));assertEquals(2,usage.getInt("cancelled"));assertEquals(3,usage.getInt("completed"));
            assertEquals(1,rig.tasks.list().length());assertEquals(0,rig.nativeRuns());
            Set<Integer> generations=new HashSet<>();JSONArray receipts=usage.getJSONArray("receipts");
            for(int i=0;i<receipts.length();i++)generations.add(receipts.getJSONObject(i).getJSONObject("context").getJSONObject("callSite").getInt("runGeneration"));
            assertTrue(generations.contains(0));assertTrue(generations.stream().anyMatch(value->value>0));
        }
    }

    @Test public void logicalCurrentnessNeverCallsCoordinatorWhileOriginalTaskStoreMonitorIsHeld()throws Exception {
        try(Rig rig=new Rig(false)){
            RunControl original=new RunControl();var target=rig.tasks.teamInferenceTarget(rig.operation,InferenceReceipt.Purpose.MEMBER,"member-a",new InferenceReceipt.CallSite(UUID.randomUUID().toString(),1,0,0),original,()->assertFalse("coordinator currentness cannot run under TaskStore monitor",Thread.holdsLock(rig.tasks)));
            assertEquals("actual member output",ModelGateway.complete(rig.settings.get(),new JSONArray().put(new JSONObject().put("role","user").put("content","original standalone team")),null,original,target).getString("content"));assertEquals(1,rig.requests.get());assertEquals(0,rig.nativeRuns());assertEquals("SUCCEEDED",rig.tasks.inferenceUsage(rig.operation).getJSONArray("logicalRequests").getJSONObject(0).getString("state"));
        }
    }
}
