package dev.pocketengine.features.memory;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.AtomicFile;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.KnowledgeLearningWorkflow;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Original native SQLite/AtomicFile/Book. ModelClient is scripted fixture data: no provider proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class SourceLearningJobNativeTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static SourceRef ref(int i){return new SourceRef("message","learning-source-"+i,9_000_000_000L,"owner","private");}
    private static JSONObject draft(int i)throws Exception{return new JSONObject().put("layer","procedural").put("text","候选方法 "+i).put("reason","仅由主人所选原文支持，未独立执行").put("applicability","仅在相同输入条件下适用；输入缺失时不适用").put("confidence",0.6);}
    interface Hook {void run()throws Exception;}
    private static final class Fixture implements AutoCloseable {
        final Context android=RuntimeEnvironment.getApplication();final FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
        final File archive=new File(android.getFilesDir(),"learning-original-"+UUID.randomUUID()+".json");
        TaskStore tasks;AssistantModule assistant;MemoryModule memory;SkillsModule skills;SQLiteDatabase db;
        final AtomicReference<InferenceReceipt> terminal=new AtomicReference<>();int modelCalls;String task,run,lastInstruction;int epoch;RunControl control;Hook afterModel=()->{};boolean resultNull;
        Fixture(int count)throws Exception{
            android.deleteDatabase("pocket-world.db");
            // A new fixture is an empty installation, including the original Book. Explicit
            // reopen() below preserves both stores; loop cases must not inherit each other.
            Path memoryDirectory=new File(android.getFilesDir(),"memory").toPath();
            if(Files.exists(memoryDirectory))try(var paths=Files.walk(memoryDirectory)){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.delete(path);}
            open();assertEquals(0,candidates());assertEquals(0,memory.handle("list",new JSONObject()).getJSONArray("records").length());
            tasks.conversationRepository().transaction(()->{tasks.conversationRepository().saveConversation(new JSONObject().put("conversationId","learning-private").put("title","逐批原文").put("messages",new JSONArray()));for(int i=0;i<count;i++){SourceRef source=ref(i);tasks.conversationRepository().saveSource(source.toJson().put("conversationId","learning-private").put("role","user").put("deleted",false));tasks.conversationRepository().saveMessage("learning-private",new JSONObject().put("id",source.sourceId).put("revision",source.revision).put("role","user").put("content","OWNER SOURCE "+i+"：前提不满足时不要使用此方法。"));}return null;});JSONArray inputs=new JSONArray();for(int i=0;i<count;i++)inputs.put(input(ref(i)));task=tasks.createSourceLearningJob(inputs).getString("taskId");}
        void open()throws Exception{tasks=new TaskStore(android);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);assistant=new AssistantModule(context,null,null,tasks);memory=new MemoryModule(context,(identity,kind,id)->assistant.resolveSource(identity,id));skills=new SkillsModule(context);}
        void reopen()throws Exception{memory.close();assistant.close();db.close();open();}
        JSONObject input(SourceRef source)throws Exception{var document=assistant.readMemorySource(source);return new JSONObject().put("sourceRef",source.toJson()).put("sourceFingerprint",KnowledgeLearningWorkflow.sourceFingerprint(document)).put("title",document.title);}
        JSONObject snapshot()throws Exception{return tasks.sourceLearningJob(task);}
        void start()throws Exception{control=new RunControl();JSONObject attempt=tasks.startSourceLearningJob(task,snapshot().getJSONObject("job").getLong("revision"),control);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");long pause=control.pauseRevision();RunControl captured=control;String capturedRun=run;int capturedEpoch=epoch;control.setAuthorityCheck(()->{if(captured.pauseRevision()!=pause||captured.isPaused())throw new SecurityException("native original pause fence");tasks.checkCapabilityCurrent(task,capturedRun,capturedEpoch,"native_memory_consolidation");});}
        void start(int max)throws Exception{control=new RunControl();JSONObject attempt=tasks.startSourceLearningJob(task,snapshot().getJSONObject("job").getLong("revision"),control,max);run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");long pause=control.pauseRevision();RunControl captured=control;String capturedRun=run;int capturedEpoch=epoch;control.setAuthorityCheck(()->{if(captured.pauseRevision()!=pause||captured.isPaused())throw new SecurityException("native original pause fence");tasks.checkCapabilityCurrent(task,capturedRun,capturedEpoch,"native_memory_consolidation");});}
        JSONObject invoke(int max)throws Exception{return new SourceLearningJob(tasks,task,run,epoch,control,workflow(),terminal::get,()->null,max).run();}
        void end(String status)throws Exception{tasks.endAttempt(task,run,status,"fixture native invocation stopped",new JSONObject().put("formalMemorySaved",false));}
        KnowledgeLearningWorkflow workflow(){RunControl original=control;return new KnowledgeLearningWorkflow(memory,skills,assistant::readMemorySource,(messages,current)->{
            terminal.set(null);lastInstruction=messages.getJSONObject(0).getString("content");JSONObject active=snapshot().getJSONObject("active");int index=active.getInt("index");var target=tasks.sourceLearningInferenceTarget(task,run,epoch,active.getString("operationId"),original);
            target=InferenceCapture.observe(target,new InferenceCapture.Sink(){public void started(InferenceReceipt r){}public void finished(InferenceReceipt r){terminal.set(r);}});
            target=InferenceCapture.withAdmission(target,()->{original.check();JSONObject expected=snapshot().getJSONObject("job").getJSONArray("inputs").getJSONObject(index);assertEquals(expected.getString("sourceFingerprint"),input(SourceRef.fromJson(expected.getJSONObject("sourceRef"))).getString("sourceFingerprint"));});
            var capture=new InferenceCapture(new ModelConfig("https://fixture.invalid","fixture-key","fixture-model",false,20,10),original,target);capture.dispatch();modelCalls++;capture.finish(InferenceReceipt.Outcome.COMPLETED);afterModel.run();return new JSONObject().put("content",resultNull?"null":draft(index).toString());
        },new MemoryHostFence(){public void check()throws Exception{original.check();}public JSONObject admit(Admission action)throws Exception{synchronized(tasks){original.check();return action.run();}}});}
        JSONObject invoke()throws Exception{return new SourceLearningJob(tasks,task,run,epoch,control,workflow(),terminal::get,()->null).run();}
        JSONObject cacheNext()throws Exception{JSONObject job=snapshot().getJSONObject("job");int index=job.getInt("nextIndex");JSONObject input=job.getJSONArray("inputs").getJSONObject(index);KnowledgeLearningWorkflow learning=workflow();SourceRef source=SourceRef.fromJson(input.getJSONObject("sourceRef"));JSONObject contract=learning.sourceLearningContract(scope(),source,input.getString("sourceFingerprint"),control),active=tasks.beginSourceLearningBatch(task,run,epoch,index,control,contract);JSONObject recorded=learning.extractRecordedLearning(scope(),source,input.getString("sourceFingerprint"),control);tasks.recordSourceLearningDraft(task,run,epoch,active.getString("operationId"),recorded,terminal.get(),control);return recorded;}
        /** Persist a historical schema/strategy fixture, retaining the original real call receipt.
         * This edits the fixture DB only; no production migration can rewrite these identities. */
        JSONObject installHistoricalDraftFixture(boolean legacy)throws Exception{
            String operation=snapshot().getJSONObject("active").getString("operationId");JSONObject taskDocument,args,receipt;
            try(Cursor row=db.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{task})){assertTrue(row.moveToFirst());taskDocument=new JSONObject(row.getString(0));}
            try(Cursor row=db.rawQuery("SELECT arguments,receipt FROM operations WHERE task_id=? AND operation_id=?",new String[]{task,operation})){assertTrue(row.moveToFirst());args=new JSONObject(row.getString(0));receipt=new JSONObject(row.getString(1));}
            JSONObject active=taskDocument.getJSONObject("sourceLearningJob").getJSONObject("active"),recorded=receipt.getJSONObject("recordedDraft");
            if(legacy){recorded.put("format","pocketworld.source-learning-draft.v1");recorded.remove("extractionContract");args.remove("extractionContract");active.remove("extractionContract");receipt.remove("extractionContractFingerprint");}
            else{JSONObject contract=recorded.getJSONObject("extractionContract").put("promptSha256","0".repeat(64));args.put("extractionContract",new JSONObject(contract.toString()));active.put("extractionContract",new JSONObject(contract.toString()));receipt.put("extractionContractFingerprint",TaskStore.capabilityFingerprint(contract));}
            String fingerprint=TaskStore.capabilityFingerprint(recorded);receipt.put("draftFingerprint",fingerprint).put("candidateBindingKey",TaskStore.capabilityFingerprint(new JSONObject().put("sourceRef",recorded.getJSONObject("sourceRef")).put("sourceFingerprint",recorded.getString("sourceFingerprint")).put("draftFingerprint",fingerprint).put("callId",receipt.getString("callId"))));
            db.beginTransaction();try{db.execSQL("UPDATE tasks SET document=? WHERE task_id=?",new Object[]{taskDocument.toString(),task});db.execSQL("UPDATE operations SET arguments=?,receipt=? WHERE task_id=? AND operation_id=?",new Object[]{args.toString(),receipt.toString(),task,operation});db.setTransactionSuccessful();}finally{db.endTransaction();}
            return new JSONObject(recorded.toString());
        }
        int candidates()throws Exception{return memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length();}
        public void close(){memory.close();assistant.close();db.close();}
    }
    /** AGP compiles main and unit-test Java to different outputs. Both must come from
     * this checkout's real build and the exact original host task classpath. */
    private static void requireCurrentCheckoutClasses(Class<?>... classes)throws Exception{
        String checkout=System.getProperty("pocket.gateway.source"),classpath=System.getProperty("pocketworldHostClasspath");
        assertNotNull("Run through the registered original host-test entrypoint",checkout);assertNotNull("Original Gradle test classpath must be recorded",classpath);
        Path build=new File(checkout,"app/build").getCanonicalFile().toPath();Set<File> admitted=new HashSet<>();
        for(String entry:classpath.split(File.pathSeparator))admitted.add(new File(entry).getCanonicalFile());
        for(Class<?> type:classes){
            URL resource=type.getResource("/"+type.getName().replace('.','/')+".class");assertNotNull("actual class resource: "+type.getName(),resource);File output;
            if("jar".equals(resource.getProtocol())){URL jar=((JarURLConnection)resource.openConnection()).getJarFileURL();assertEquals("local compiled class archive", "file",jar.getProtocol());output=new File(jar.toURI()).getCanonicalFile();}
            else{assertEquals("local compiled class directory","file",resource.getProtocol());output=new File(resource.toURI()).getCanonicalFile();for(String ignored:type.getName().split("\\."))output=output.getParentFile();}
            assertTrue("class must come from this checkout build: "+type.getName()+" at "+resource,output.toPath().startsWith(build));
            assertTrue("class must belong to the original selected task classpath: "+type.getName()+" at "+output,admitted.contains(output));
        }
    }
    @Test public void savedNativePlanHasNoModelGrantAndStrictRevisionCas()throws Exception{try(Fixture f=new Fixture(2)){JSONObject first=f.snapshot();assertEquals(0,f.modelCalls);assertEquals("queued",first.getString("taskStatus"));assertFalse(first.getBoolean("executionAuthorized"));assertFalse(first.getBoolean("formalMemorySaved"));assertEquals("",f.tasks.get(f.task,false).optString("runId"));assertThrows(Exception.class,()->f.tasks.addSourceLearningJobSource(f.task,999,f.input(ref(0))));assertThrows(Exception.class,()->f.tasks.addSourceLearningJobSource(f.task,1,f.input(ref(0))));assertEquals(2,f.snapshot().getJSONObject("job").getJSONArray("inputs").length());f.start();assertThrows(Exception.class,()->f.tasks.addSourceLearningJobSource(f.task,f.snapshot().getJSONObject("job").getLong("revision"),f.input(ref(0))));}}
    @Test public void threeCallWindowAndTwoReopensKeepSameTaskCompletedWatermarks()throws Exception{try(Fixture f=new Fixture(7)){String original=f.task;f.start();JSONObject first=f.invoke();assertEquals("batch_window_complete_continue_explicitly",first.getString("invocationOutcome"));assertEquals(3,f.modelCalls);assertEquals(3,f.candidates());f.end("awaiting_user");f.reopen();assertEquals(3,f.snapshot().getJSONObject("job").getInt("nextIndex"));f.start();f.invoke();assertEquals(6,f.modelCalls);f.end("awaiting_user");f.reopen();f.start();assertEquals("all_batches_settled",f.invoke().getString("invocationOutcome"));f.end("completed");assertEquals(original,f.task);assertEquals(7,f.modelCalls);assertEquals(7,f.candidates());assertEquals(7,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertFalse(f.snapshot().getBoolean("canContinue"));assertEquals(0,f.memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());}}
    @Test public void receivedDraftSurvivesCrashAndPublishesWithoutAnotherModelCall()throws Exception{try(Fixture f=new Fixture(1)){f.start();JSONObject original=f.cacheNext();String operation=f.snapshot().getJSONObject("active").getString("operationId");assertEquals(1,f.modelCalls);f.reopen();assertEquals("draft_recorded",f.snapshot().getJSONObject("active").getString("stage"));assertEquals(original.toString(),f.tasks.sourceLearningDraft(f.task,operation).toString());f.start();f.invoke();assertEquals(1,f.modelCalls);assertEquals(1,f.candidates());assertEquals(1,f.snapshot().getJSONObject("job").getInt("nextIndex"));}}
    @Test public void crashAfterBookCommitReusesExactCandidateKeyAndDoesNotReopenRejection()throws Exception{try(Fixture f=new Fixture(1)){f.start();JSONObject recorded=f.cacheNext(),candidate=f.workflow().publishRecordedLearning(scope(),recorded,f.control).getJSONObject("candidate");f.memory.handle("rejectCandidate",new JSONObject().put("scope",scope()).put("id",candidate.getString("id")).put("revision",candidate.getLong("revision")).put("reason","主人拒绝该方法"));f.reopen();f.start();f.invoke();assertEquals(1,f.modelCalls);assertEquals(1,f.candidates());JSONObject result=f.snapshot().getJSONObject("job").getJSONArray("results").getJSONObject(0);assertEquals(candidate.getString("id"),result.getJSONObject("candidateRef").getString("id"));assertEquals("rejected",result.getJSONObject("candidateRef").getString("statusAtBatchCommit"));}}
    @Test public void uncertainPreparedBatchNeverRetriesOrRefundsAndOwnerSkipIsExplicit()throws Exception{try(Fixture f=new Fixture(2)){f.start();JSONObject active=f.tasks.beginSourceLearningBatch(f.task,f.run,f.epoch,0,f.control);String op=active.getString("operationId");var capture=new InferenceCapture(new ModelConfig("https://fixture.invalid","k","m",false,20,10),f.control,f.tasks.sourceLearningInferenceTarget(f.task,f.run,f.epoch,op,f.control));capture.dispatch();f.reopen();JSONObject old=f.snapshot();assertEquals("unknown",old.getJSONObject("active").getString("stage"));assertFalse(old.getBoolean("canContinue"));assertThrows(Exception.class,f::start);assertThrows(Exception.class,()->f.tasks.resolveOperation(f.task,op,"not_occurred"));assertThrows(Exception.class,()->f.tasks.startAttempt(f.task,"capabilities",3));assertThrows(Exception.class,()->f.tasks.skipSourceLearningBatch(f.task,old.getJSONObject("job").getLong("revision"),false));f.tasks.skipSourceLearningBatch(f.task,old.getJSONObject("job").getLong("revision"),true);assertEquals(1,f.snapshot().getJSONObject("job").getInt("reservedModelAttempts"));assertEquals(1,f.snapshot().getJSONObject("job").getInt("nextIndex"));f.start();f.invoke();assertEquals(1,f.modelCalls);assertEquals(2,f.snapshot().getJSONObject("job").getInt("reservedModelAttempts"));JSONArray events=f.tasks.get(f.task,false).getJSONArray("events");boolean started=false;for(int i=0;i<events.length();i++)if("inference_receipt".equals(events.getJSONObject(i).optString("type"))&&capture.callId.equals(events.getJSONObject(i).optString("callId")))started="STARTED".equals(events.getJSONObject(i).getString("outcome"));assertTrue("unknown original usage must remain unknown",started);}}
    @Test public void cachedDraftCannotRepublishRetiredSourceAndOwnerOriginalStillExists()throws Exception{try(Fixture f=new Fixture(2)){f.start();JSONObject recorded=f.cacheNext();f.assistant.retireMemoryContexts(new JSONArray().put(new JSONObject().put("sourceRefs",new JSONArray().put(ref(0).toJson()))),"owner_forget");assertThrows(Exception.class,()->f.workflow().publishRecordedLearning(scope(),recorded,f.control));assertEquals(0,f.candidates());assertEquals(0,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertTrue(f.assistant.readOriginalConversation("learning-private","",80).getJSONArray("messages").length()>0);}}
    @Test public void sourceChangedAfterModelCannotPublishOrAdvanceAndItsUsageIsKept()throws Exception{try(Fixture f=new Fixture(1)){f.start();f.afterModel=()->f.assistant.handle("deleteMessage",new JSONObject().put("conversationId","learning-private").put("messageId",ref(0).sourceId).put("revision",ref(0).revision));assertThrows(Exception.class,f::invoke);assertEquals(1,f.modelCalls);assertEquals(0,f.candidates());assertEquals(0,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertEquals("unknown",f.snapshot().getJSONObject("active").getString("stage"));JSONArray events=f.tasks.get(f.task,false).getJSONArray("events");assertTrue(events.toString().contains("COMPLETED"));}}
    @Test public void cancelledAndPauseResumeLateDraftCannotPublishAndOldControlCannotContinue()throws Exception{for(boolean pause:new boolean[]{false,true})try(Fixture f=new Fixture(1)){f.start();f.afterModel=()->{if(pause){f.control.pause();f.control.resume();}else f.control.cancel();};assertThrows(Exception.class,f::invoke);assertEquals(0,f.candidates());assertEquals(0,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertThrows(Exception.class,()->f.tasks.beginSourceLearningBatch(f.task,f.run,f.epoch,0,f.control));}}
    @Test public void exactRecordedReceiptCannotBorrowPriorBatchOrAnotherRun()throws Exception{try(Fixture f=new Fixture(2)){f.start();JSONObject old=f.cacheNext();InferenceReceipt receipt=f.terminal.get();JSONObject result=f.workflow().publishRecordedLearning(scope(),old,f.control);f.tasks.completeSourceLearningBatch(f.task,f.run,f.epoch,f.snapshot().getJSONObject("active").getString("operationId"),result,f.control);JSONObject next=f.tasks.beginSourceLearningBatch(f.task,f.run,f.epoch,1,f.control);JSONObject wrong=new JSONObject(old.toString()).put("sourceRef",ref(1).toJson()).put("sourceFingerprint",f.input(ref(1)).getString("sourceFingerprint"));assertThrows(Exception.class,()->f.tasks.recordSourceLearningDraft(f.task,f.run,f.epoch,next.getString("operationId"),wrong,receipt,f.control));assertEquals(1,f.snapshot().getJSONObject("job").getInt("nextIndex"));}}
    @Test public void noCandidateSettlesAndNormalUnrelatedAppendDoesNotKillSelectedSources()throws Exception{try(Fixture f=new Fixture(2)){f.start();f.resultNull=true;f.afterModel=()->f.assistant.appendTaskResult("learning-private","native-other-task","正常新增无关尾消息");f.invoke();assertEquals(2,f.modelCalls);assertEquals(0,f.candidates());assertEquals(2,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertEquals("no_candidate",f.snapshot().getJSONObject("job").getJSONArray("results").getJSONObject(0).getString("outcome"));}}
    @Test public void preparedSourceGuardRunsAfterStartedWithoutTakingTasksMonitor()throws Exception{try(Fixture f=new Fixture(1)){f.start();JSONObject active=f.tasks.beginSourceLearningBatch(f.task,f.run,f.epoch,0,f.control);int[] checks={0};var target=InferenceCapture.withAdmission(f.tasks.sourceLearningInferenceTarget(f.task,f.run,f.epoch,active.getString("operationId"),f.control),()->{assertFalse(Thread.holdsLock(f.tasks));if(++checks[0]==2)throw new SecurityException("source retired between durable start and dispatch");});var capture=new InferenceCapture(new ModelConfig("https://fixture.invalid","k","m",false,20,10),f.control,target);assertThrows(Exception.class,capture::dispatch);assertEquals(2,checks[0]);assertEquals(0,f.modelCalls);assertEquals(0,f.candidates());assertTrue(f.tasks.get(f.task,false).getJSONArray("events").toString().contains(capture.callId));}}
    @Test public void boundedJobListingAndDerivedOperationExclusionUseOriginalAuthority()throws Exception{try(Fixture f=new Fixture(1)){for(int i=0;i<3;i++)f.tasks.createSourceLearningJob(new JSONArray().put(f.input(ref(0))));JSONObject page=f.tasks.listSourceLearningJobs("",2),next=f.tasks.listSourceLearningJobs(page.getString("nextCursor"),2);assertEquals(2,page.getJSONArray("jobs").length());assertEquals(2,next.getJSONArray("jobs").length());assertFalse(next.getBoolean("hasMore"));assertThrows(Exception.class,()->f.tasks.listSourceLearningJobs("free task id",2));assertTrue(TaskStoreMemoryReader.isDerivedReferenceTool(SourceLearningJob.TOOL));f.start();f.cacheNext();requireCurrentCheckoutClasses(SourceLearningJobNativeTest.class,TaskStore.class,SourceLearningJob.class,KnowledgeLearningWorkflow.class,MemoryBook.class,AssistantModule.class,InferenceCapture.class,TaskStoreMemoryReader.class);try(Cursor row=f.db.rawQuery("SELECT checkpoint FROM runs WHERE run_id=?",new String[]{f.run})){assertTrue(row.moveToFirst());assertEquals(0,new JSONObject(row.getString(0)).getJSONArray("messages").length());assertFalse(row.getString(0).contains("OWNER SOURCE"));assertFalse(row.getString(0).contains("候选方法"));}}}
    @Test public void explicitFormalForgetBlocksCachedDraftAfterCandidateCommit()throws Exception{try(Fixture f=new Fixture(1)){f.start();JSONObject recorded=f.cacheNext(),candidate=f.workflow().publishRecordedLearning(scope(),recorded,f.control).getJSONObject("candidate");JSONObject accepted=f.memory.acceptCandidate(scope(),candidate.getString("id"),candidate.getLong("revision"));JSONObject record=accepted.getJSONObject("record");f.memory.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));assertTrue(f.memory.isSourceForgotten(scope(),ref(0)));assertThrows(Exception.class,()->f.workflow().publishRecordedLearning(scope(),recorded,f.control));assertEquals(0,f.snapshot().getJSONObject("job").getInt("nextIndex"));}}
    @Test public void invalidSourceBeforeBatchConsumesNoNewAttemptAndChangedRevisionCannotBeSubstituted()throws Exception{try(Fixture f=new Fixture(1)){f.start();f.assistant.handle("deleteMessage",new JSONObject().put("conversationId","learning-private").put("messageId",ref(0).sourceId).put("revision",ref(0).revision));assertThrows(Exception.class,f::invoke);assertEquals(0,f.modelCalls);assertEquals(0,f.snapshot().getJSONObject("job").getInt("reservedModelAttempts"));assertFalse(f.snapshot().has("active"));}}

    @Test public void unopenedPlanSurvivesRestartAndCanAcceptAnotherCurrentNativeSource()throws Exception{try(Fixture f=new Fixture(2)){f.task=f.tasks.createSourceLearningJob(new JSONArray().put(f.input(ref(0)))).getString("taskId");f.reopen();assertTrue(f.snapshot().getBoolean("canAddSources"));long revision=f.snapshot().getJSONObject("job").getLong("revision");f.tasks.addSourceLearningJobSource(f.task,revision,f.input(ref(1)));assertEquals(2,f.snapshot().getJSONObject("job").getJSONArray("inputs").length());assertEquals(0,f.modelCalls);assertEquals(0,f.snapshot().getJSONObject("job").getInt("reservedModelAttempts"));}}

    @Test public void explicitTwentyBatchNativeWindowUsesOriginalBudgetAndProducesTwentyPendingResultsOnly()throws Exception{
        try(Fixture f=new Fixture(20)){f.start(20);String originalTask=f.task;JSONObject result=f.invoke(20);assertEquals("all_batches_settled",result.getString("invocationOutcome"));assertEquals(20,result.getInt("gatewayInvocationsThisAttempt"));assertEquals(20,f.modelCalls);assertEquals(20,f.candidates());assertEquals(20,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertEquals(20,f.snapshot().getJSONObject("job").getInt("reservedModelAttempts"));assertEquals(0,f.memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());assertEquals(originalTask,f.task);
            try(Cursor rows=f.db.rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{f.run})){assertTrue(rows.moveToFirst());assertEquals(0,new JSONObject(rows.getString(0)).getInt("remainingOperations"));}
            try(Cursor rows=f.db.rawQuery("SELECT event_json FROM task_events WHERE task_id=? AND event_type='inference_receipt'",new String[]{f.task})){java.util.Set<String> calls=new java.util.HashSet<>();while(rows.moveToNext()){JSONObject receipt=new JSONObject(rows.getString(0));assertEquals("COMPLETED",receipt.getString("outcome"));assertEquals(f.run,receipt.getJSONObject("context").getString("runId"));assertTrue(calls.add(receipt.getString("callId")));}assertEquals(20,calls.size());}
            f.end("completed");f.reopen();assertEquals(20,f.snapshot().getJSONObject("job").getJSONArray("results").length());assertThrows(Exception.class,()->f.start(20));assertEquals(20,f.modelCalls);
        }
    }
    @Test public void explicitOneThenTwentyContinuationNeverReplaysTheAlreadySettledSource()throws Exception{
        try(Fixture f=new Fixture(5)){f.start(1);String firstRun=f.run;assertEquals("batch_window_complete_continue_explicitly",f.invoke(1).getString("invocationOutcome"));assertEquals(1,f.modelCalls);f.end("awaiting_user");f.reopen();f.start(20);assertNotEquals(firstRun,f.run);assertEquals("all_batches_settled",f.invoke(20).getString("invocationOutcome"));assertEquals(5,f.modelCalls);assertEquals(5,f.snapshot().getJSONObject("job").getJSONArray("results").length());assertEquals(5,f.snapshot().getJSONObject("job").getInt("reservedModelAttempts"));
            try(Cursor rows=f.db.rawQuery("SELECT COUNT(*) FROM operations WHERE task_id=? AND tool=?",new String[]{f.task,SourceLearningJob.TOOL})){assertTrue(rows.moveToFirst());assertEquals(5,rows.getInt(0));}
        }
    }
    @Test public void cancelInsideExplicitTwentyWindowStopsNextSourceAndRetainsTheUnknownReservation()throws Exception{
        try(Fixture f=new Fixture(20)){f.start(20);f.afterModel=()->{if(f.modelCalls==7)f.control.cancel();};assertThrows(Exception.class,()->f.invoke(20));assertEquals(7,f.modelCalls);assertEquals(6,f.candidates());assertEquals(6,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertEquals(7,f.snapshot().getJSONObject("job").getInt("reservedModelAttempts"));assertEquals("unknown",f.snapshot().getJSONObject("active").getString("stage"));assertThrows(Exception.class,()->f.start(20));assertEquals(7,f.modelCalls);
        }
    }

    @Test public void currentOneBatchDoesNotPrepareAFutureDeletedSourceAndColdContinuationStillRefusesIt()throws Exception{
        try(Fixture f=new Fixture(2)){
            f.assistant.handle("deleteMessage",new JSONObject().put("conversationId","learning-private").put("messageId",ref(1).sourceId).put("revision",ref(1).revision));
            java.util.List<String> prepared=new java.util.ArrayList<>();f.start(1);
            SourceLearningJob.SourcePreparation preparation=(source,original)->{
                assertFalse("native IO preparation must be outside Tasks",Thread.holdsLock(f.tasks));original.check();prepared.add(source.sourceId);
                SourceRef.State current=f.assistant.resolveSource(MemoryBook.Scope.from(scope()),source.sourceId);
                if(current==null||current.deleted||!source.equals(current.ref))throw new IllegalStateException("selected original source no longer current");
            };
            JSONObject first=new SourceLearningJob(f.tasks,f.task,f.run,f.epoch,f.control,f.workflow(),f.terminal::get,()->null,1,preparation).run();
            assertEquals("batch_window_complete_continue_explicitly",first.getString("invocationOutcome"));assertEquals(java.util.List.of(ref(0).sourceId),prepared);
            assertEquals(1,f.modelCalls);assertEquals(1,f.candidates());assertEquals(1,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertEquals(1,f.snapshot().getJSONObject("job").getInt("reservedModelAttempts"));
            f.end("awaiting_user");f.reopen();f.start(1);
            assertThrows(IllegalStateException.class,()->new SourceLearningJob(f.tasks,f.task,f.run,f.epoch,f.control,f.workflow(),f.terminal::get,()->null,1,preparation).run());
            assertEquals(java.util.List.of(ref(0).sourceId,ref(1).sourceId),prepared);assertEquals(1,f.modelCalls);assertEquals(1,f.candidates());
            assertEquals(1,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertEquals(1,f.snapshot().getJSONObject("job").getInt("reservedModelAttempts"));assertFalse(f.snapshot().has("active"));
        }
    }

    @Test public void coldRecordedDraftPreparesOnlyItsCurrentSourceBeforePublishWithoutAnotherModelCall()throws Exception{
        try(Fixture f=new Fixture(1)){
            f.start(1);f.cacheNext();assertEquals(1,f.modelCalls);f.reopen();f.start(1);int[] prepared={0};
            JSONObject result=new SourceLearningJob(f.tasks,f.task,f.run,f.epoch,f.control,f.workflow(),f.terminal::get,()->null,1,(source,original)->{
                assertFalse(Thread.holdsLock(f.tasks));assertEquals(ref(0),source);original.check();prepared[0]++;
                assertEquals("draft_recorded",f.snapshot().getJSONObject("active").getString("stage"));assertEquals(0,f.candidates());
            }).run();
            assertEquals("all_batches_settled",result.getString("invocationOutcome"));assertEquals(1,prepared[0]);assertEquals(1,f.modelCalls);assertEquals(1,f.candidates());
            assertEquals(1,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertEquals(1,f.snapshot().getJSONObject("job").getInt("reservedModelAttempts"));
        }
    }

    @Test public void preparedContractDescribesTheActualPromptAndSourceWhileOriginalCallKeepsModelIdentity()throws Exception{
        try(Fixture f=new Fixture(1)){f.start();JSONObject recorded=f.cacheNext(),active=f.snapshot().getJSONObject("active"),contract=recorded.getJSONObject("extractionContract");String operation=active.getString("operationId");
            assertEquals(LearningExtractionContract.DRAFT_FORMAT,recorded.getString("format"));assertEquals(TaskMemoryUse.digest(f.lastInstruction),contract.getString("promptSha256"));assertEquals("current_contract",active.getString("extractionContractStatus"));assertTrue(LearningExtractionContract.supported(contract));assertTrue(contract.isNull("ownerPolicyFingerprint"));
            try(Cursor row=f.db.rawQuery("SELECT arguments,receipt FROM operations WHERE task_id=? AND operation_id=?",new String[]{f.task,operation})){assertTrue(row.moveToFirst());JSONObject args=new JSONObject(row.getString(0)),receipt=new JSONObject(row.getString(1));LearningExtractionContract.requireSame(contract,args.getJSONObject("extractionContract"));assertEquals(TaskStore.capabilityFingerprint(contract),receipt.getString("extractionContractFingerprint"));assertFalse(row.getString(0).contains("OWNER SOURCE"));assertFalse(row.getString(0).contains("fixture-key"));assertEquals(f.terminal.get().callId,receipt.getString("callId"));}
            assertEquals("fixture-model",f.terminal.get().toJson().getJSONObject("route").getString("requestedModel"));assertEquals(1,f.modelCalls);
            f.invoke();JSONObject result=f.snapshot().getJSONObject("job").getJSONArray("results").getJSONObject(0);assertEquals("exact_source_snapshot",result.getString("coverage"));assertEquals(f.input(ref(0)).getString("sourceFingerprint"),result.getString("sourceFingerprint"));assertEquals(ref(0),SourceRef.fromJson(result.getJSONObject("sourceRef")));LearningExtractionContract.requireSame(contract,result.getJSONObject("extractionContract"));assertEquals(1,f.modelCalls);assertEquals(1,f.candidates());
        }
    }

    @Test public void receivedNonemptyDraftCannotBeSettledAsNoCandidateOrLoseItsWatermark()throws Exception{
        try(Fixture f=new Fixture(1)){f.start();JSONObject recorded=f.cacheNext();String operation=f.snapshot().getJSONObject("active").getString("operationId");
            assertThrows(SecurityException.class,()->f.tasks.completeSourceLearningBatch(f.task,f.run,f.epoch,operation,new JSONObject().put("formalMemorySaved",false).put("outcome","no_candidate"),f.control));
            assertEquals(0,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertEquals(recorded.toString(),f.tasks.sourceLearningDraft(f.task,operation).toString());assertEquals(0,f.candidates());assertEquals(1,f.modelCalls);
            f.invoke();assertEquals("awaiting_review",f.snapshot().getJSONObject("job").getJSONArray("results").getJSONObject(0).getString("outcome"));assertEquals(1,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertEquals(1,f.candidates());assertEquals(1,f.modelCalls);
        }
    }

    @Test public void historicalV1DraftAndNoCandidateKeepTheirZeroRequestColdRecoveryWithoutInventedContract()throws Exception{
        for(boolean noCandidate:new boolean[]{false,true})try(Fixture f=new Fixture(1)){f.resultNull=noCandidate;f.start();f.cacheNext();String operation=f.snapshot().getJSONObject("active").getString("operationId");JSONObject historical=f.installHistoricalDraftFixture(true);f.reopen();
            assertEquals("legacy_contract_unknown",f.snapshot().getJSONObject("active").getString("extractionContractStatus"));assertTrue(f.snapshot().getBoolean("canContinue"));assertEquals(historical.toString(),f.tasks.sourceLearningDraft(f.task,operation).toString());
            f.start();JSONObject recovered=f.invoke();assertEquals("all_batches_settled",recovered.getString("invocationOutcome"));assertEquals(0,recovered.getInt("gatewayInvocationsThisAttempt"));assertEquals(1,f.modelCalls);assertEquals(noCandidate?0:1,f.candidates());JSONObject result=f.snapshot().getJSONObject("job").getJSONArray("results").getJSONObject(0);assertEquals(noCandidate?"no_candidate":"awaiting_review",result.getString("outcome"));assertEquals("legacy_contract_unknown",result.getString("extractionContractStatus"));assertTrue(result.isNull("extractionContract"));assertEquals(1,f.snapshot().getJSONObject("job").getInt("reservedModelAttempts"));
        }
    }

    @Test public void changedV2StrategyRemainsReadableButCannotPublishResendOrAdvanceWithoutOwnerSkip()throws Exception{
        try(Fixture f=new Fixture(1)){f.start();f.cacheNext();String operation=f.snapshot().getJSONObject("active").getString("operationId");JSONObject historical=f.installHistoricalDraftFixture(false);f.reopen();
            assertEquals("contract_changed_requires_review",f.snapshot().getJSONObject("active").getString("extractionContractStatus"));assertFalse(f.snapshot().getBoolean("canContinue"));assertTrue(f.snapshot().getBoolean("canSkipBatch"));assertEquals(historical.toString(),f.tasks.sourceLearningDraft(f.task,operation).toString());
            f.start();assertThrows(SecurityException.class,f::invoke);assertEquals(1,f.modelCalls);assertEquals(0,f.candidates());assertEquals(0,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertEquals(historical.toString(),f.tasks.sourceLearningDraft(f.task,operation).toString());
            f.end("awaiting_user");assertThrows(SecurityException.class,()->f.tasks.skipSourceLearningBatch(f.task,f.snapshot().getJSONObject("job").getLong("revision"),false));f.tasks.skipSourceLearningBatch(f.task,f.snapshot().getJSONObject("job").getLong("revision"),true);
            assertEquals(1,f.snapshot().getJSONObject("job").getInt("nextIndex"));assertEquals(1,f.snapshot().getJSONObject("job").getInt("reservedModelAttempts"));assertEquals(1,f.modelCalls);assertEquals(0,f.candidates());try(Cursor row=f.db.rawQuery("SELECT state,receipt FROM operations WHERE task_id=? AND operation_id=?",new String[]{f.task,operation})){assertTrue(row.moveToFirst());assertEquals("receipt",row.getString(0));assertEquals(historical.toString(),new JSONObject(row.getString(1)).getJSONObject("recordedDraft").toString());}
        }
    }

}
