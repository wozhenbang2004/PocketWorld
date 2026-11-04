package dev.pocketengine.features.memory;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.KnowledgeLearningWorkflow;
import dev.pocketengine.features.MemorySourceBridge;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
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

/** Real TaskStore/event source/MemoryBook chain. The model response is synthetic fixture data,
 * with a real original InferenceCapture receipt, never proof of provider or business success. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class TaskEpisodeSourceLifecycleTest {
    private static String id(){return UUID.randomUUID().toString();}
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final class Fixture implements AutoCloseable {
        final Context android;final TaskStore tasks;final SQLiteDatabase db;final FeatureHost host;final MemorySourceBridge sources;final MemoryModule memory;final SkillsModule skills;final RunControl original=new RunControl();final JSONObject task;final String operation;final RunJournal journal;final ExecutorService worker=Executors.newSingleThreadExecutor();int calls;
        Fixture()throws Exception{
            File directory=Files.createTempDirectory("task-episode-fixture-").toFile();android=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public File getFilesDir(){return directory;}};android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);var database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);db=(SQLiteDatabase)database.get(tasks);FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});host=new FeatureHost(event->{});AtomicReference<MemorySourceBridge> resolver=new AtomicReference<>();memory=new MemoryModule(context,(identity,kind,source)->resolver.get().resolve(identity,kind,source));skills=new SkillsModule(context,memory::resolveExperience);host.register(memory);host.register(skills);sources=new MemorySourceBridge(host,tasks);resolver.set(sources);
            task=tasks.startAttempt(tasks.createCapabilityTask("整理合成测试清单",id()).getString("id"),"capabilities",10);original.bindHostRun(taskId(),run(),epoch());tasks.authorizeTools(taskId(),run(),new JSONArray().put("memory_search"));tasks.status(taskId(),"running","Synthetic original task");journal=tasks.journal(taskId(),run(),epoch(),original);operation=journal.beginOperation("memory_search",new JSONObject().put("query","synthetic checklist"));journal.finishOperation(operation,new JSONObject().put("ok",true).put("status","completed").put("rawSecretField","synthetic-value-not-for-projection"));tasks.endAttempt(taskId(),run(),"completed","助手报告整理结束，未取得主人业务结果确认",new JSONObject());
        }
        String taskId()throws Exception{return task.getString("id");}String run()throws Exception{return task.getString("runId");}int epoch()throws Exception{return task.getInt("coordinatorEpoch");}
        JSONObject inspect()throws Exception{return tasks.taskEpisodeForOwner(taskId(),run(),epoch());}
        SourceRef retain()throws Exception{return SourceRef.fromJson(tasks.retainTaskEpisodeForOwner(taskId(),run(),epoch(),inspect().getString("episodeDigest")).getJSONObject("sourceRef"));}
        <T>T io(Callable<T> work)throws Exception{try{return worker.submit(work).get();}catch(ExecutionException failure){if(failure.getCause() instanceof Exception checked)throw checked;throw failure;}}
        int records()throws Exception{return memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length();}
        int candidates()throws Exception{return memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").length();}
        JSONObject learn(SourceRef ref,Runnable afterModel)throws Exception{return io(()->{
            var document=sources.read(ref);JSONObject created=tasks.createSourceLearningJob(new JSONArray().put(new JSONObject().put("sourceRef",ref.toJson()).put("title",document.title).put("sourceFingerprint",KnowledgeLearningWorkflow.sourceFingerprint(document))));String learningTask=created.getString("taskId");RunControl control=new RunControl();JSONObject attempt=tasks.startSourceLearningJob(learningTask,tasks.sourceLearningJob(learningTask).getJSONObject("job").getLong("revision"),control);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");AtomicReference<InferenceReceipt> terminal=new AtomicReference<>();
            KnowledgeLearningWorkflow flow=new KnowledgeLearningWorkflow(memory,skills,sources::read,(messages,current)->{
                assertEquals(LearningExtractionContract.instruction(LearningExtractionContract.EPISODE),messages.getJSONObject(0).getString("content"));JSONObject active=tasks.sourceLearningJob(learningTask).getJSONObject("active");var target=InferenceCapture.observe(tasks.sourceLearningInferenceTarget(learningTask,run,epoch,active.getString("operationId"),control),new InferenceCapture.Sink(){public void started(InferenceReceipt receipt){}public void finished(InferenceReceipt receipt){terminal.set(receipt);}});var capture=new InferenceCapture(new ModelConfig("https://fixture.invalid","synthetic-key","fixture-model",false,20,10),control,target);capture.dispatch();calls++;capture.finish(InferenceReceipt.Outcome.COMPLETED);afterModel.run();return new JSONObject().put("content",new JSONObject().put("constraints","完整约束未单独记录").put("steps",new JSONArray().put(new JSONObject().put("operationId",operation).put("description","原工具调用留有回执，尚不能判定业务结果"))).put("resultExplanation","缺少主人结果确认，结果未知").put("lesson","在复用结论前单独核对实际结果").put("applicability","仅供类似清单任务审阅，版本和效果未知").put("reason","保留本次有限经过及不确定边界").put("confidence",0.4).toString());
            },new MemoryHostFence(){public void check()throws Exception{control.check();tasks.checkCapabilityCurrent(learningTask,run,epoch,"native_memory_consolidation");}public JSONObject admit(Admission action)throws Exception{synchronized(tasks){check();return action.run();}}});
            return new SourceLearningJob(tasks,learningTask,run,epoch,control,flow,terminal::get,()->null).run();
        });}
        @Override public void close()throws Exception{worker.shutdownNow();host.close();db.close();}
    }
    @Test public void originalProjectionIsExplicitBoundedAndRetainsOnlyOneExactFullSource()throws Exception{try(Fixture f=new Fixture()){
        JSONObject snapshot=f.inspect(),event=snapshot.getJSONObject("evidence");assertEquals("unknown",event.getJSONObject("snapshot").getJSONObject("outcome").getString("classification"));assertFalse(event.toString().contains("synthetic-value-not-for-projection"));assertEquals(0,f.records());assertEquals(0,f.candidates());assertEquals(0,f.calls);SourceRef source=f.retain();assertEquals(source,f.retain());var address=TaskSavedEvidence.address(source.sourceId);assertThrows(SecurityException.class,()->f.tasks.selectSavedMemorySource(f.taskId(),f.run(),source.revision,0,100));assertEquals(0,address.offset);
        var document=f.io(()->f.sources.read(source));assertNotNull(document.episodeContext);assertNull(document.appContext);assertEquals(address.chars,document.text.length());assertFalse(f.tasks.savedMemorySources(f.taskId(),0,20).getBoolean("completeHistory"));assertThrows(SecurityException.class,()->f.tasks.event(f.taskId(),event));assertThrows(SecurityException.class,()->f.tasks.event(f.taskId(),f.run(),f.epoch(),event));
        try(var rows=f.db.rawQuery("SELECT COUNT(*) FROM task_events WHERE task_id=? AND event_type='task_episode_evidence'",new String[]{f.taskId()})){assertTrue(rows.moveToFirst());assertEquals(1,rows.getInt(0));}
    }}
    @Test public void oldDigestRunOrChangedOriginalReceiptCannotBorrowANewerSource()throws Exception{try(Fixture f=new Fixture()){
        JSONObject inspected=f.inspect();SourceRef source=f.retain();assertThrows(SecurityException.class,()->f.tasks.taskEpisodeForOwner(f.taskId(),id(),f.epoch()));assertThrows(SecurityException.class,()->f.tasks.taskEpisodeForOwner(f.taskId(),f.run(),f.epoch()+1));assertThrows(SecurityException.class,()->f.tasks.retainTaskEpisodeForOwner(f.taskId(),f.run(),f.epoch(),"0".repeat(64)));
        f.journal.finishOperation(f.operation,new JSONObject().put("ok",false).put("status","late_corrected_receipt"));assertThrows(SecurityException.class,()->f.tasks.retainTaskEpisodeForOwner(f.taskId(),f.run(),f.epoch(),inspected.getString("episodeDigest")));assertTrue(f.tasks.resolveMemorySource(MemoryBook.Scope.from(scope()),source.kind,source.sourceId).deleted);assertThrows(SecurityException.class,()->f.io(()->f.sources.read(source)));assertEquals(0,f.calls);
    }}
    @Test public void ownerResultConfirmationChangesClassificationAndRetiresTheOlderEpisode()throws Exception{try(Fixture f=new Fixture()){
        SourceRef old=f.retain();f.tasks.finish(f.taskId(),"needs_confirmation","请主人核对原结果",new JSONObject());f.tasks.confirmReviewedResult(f.taskId(),f.tasks.get(f.taskId(),false).getString("resultReviewDigest"),false,"原结果还需要修正");JSONObject snapshot=f.inspect().getJSONObject("evidence").getJSONObject("snapshot");assertEquals("owner_confirmed_not_completed",snapshot.getJSONObject("outcome").getString("classification"));assertFalse(snapshot.getJSONObject("outcome").getBoolean("businessIndependentlyVerified"));assertTrue(f.tasks.resolveMemorySource(MemoryBook.Scope.from(scope()),old.kind,old.sourceId).deleted);assertNotEquals(old,f.retain());
    }}
    @Test public void anEndedUnknownOperationRemainsAnUnknownHistoricalEpisode()throws Exception{try(Fixture f=new Fixture()){
        f.journal.finishOperation(f.operation,new JSONObject().put("status","unknown").put("accepted",JSONObject.NULL));f.tasks.endAttempt(f.taskId(),f.run(),"interrupted","原调用结果未知",new JSONObject());JSONObject snapshot=f.inspect().getJSONObject("evidence").getJSONObject("snapshot");assertEquals("reconciling",snapshot.getString("runStatus"));assertEquals("unknown",snapshot.getJSONObject("outcome").getString("classification"));assertEquals("unknown",snapshot.getJSONArray("operations").getJSONObject(0).getString("state"));assertEquals(0,f.records());
    }}
    @Test public void runningAttemptsLeasesAndPhysicalProjectWorkCannotBeRetainedAsEnded()throws Exception{try(Fixture f=new Fixture()){
        f.db.execSQL("UPDATE runs SET status='running' WHERE run_id=?",new Object[]{f.run()});assertThrows(SecurityException.class,f::inspect);f.db.execSQL("UPDATE runs SET status='completed' WHERE run_id=?",new Object[]{f.run()});
        f.db.execSQL("INSERT INTO leases(resource_id,run_id,epoch,expires_at) VALUES(?,?,?,?)",new Object[]{"episode-fixture",f.run(),f.epoch(),System.currentTimeMillis()+60000});assertThrows(SecurityException.class,f::inspect);f.db.execSQL("DELETE FROM leases WHERE resource_id='episode-fixture'");
        f.db.execSQL("UPDATE operations SET state='project_running' WHERE operation_id=?",new Object[]{f.operation});assertThrows(SecurityException.class,f::inspect);assertEquals(0,f.tasks.savedMemorySources(f.taskId(),0,20).getJSONArray("entries").length());assertEquals(0,f.calls);
    }}
    @Test public void anEndedChildStillHoldingItsOriginalLeaseKeepsTheParentEpisodeUnavailable()throws Exception{try(Fixture f=new Fixture()){
        JSONObject child=f.tasks.startAttempt(f.tasks.createCapabilityTask("Synthetic original child",id()).getString("id"),"capabilities",2);String childTask=child.getString("id"),childRun=child.getString("runId");f.tasks.endAttempt(childTask,childRun,"completed","child ended",new JSONObject());
        // Install only this fixture's native original relation, with real TaskStore run rows.
        try(var rows=f.db.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{childTask})){assertTrue(rows.moveToFirst());JSONObject current=new JSONObject(rows.getString(0)).put("parentTaskId",f.taskId()).put("parentRunId",f.run());f.db.execSQL("UPDATE tasks SET document=? WHERE task_id=?",new Object[]{current.toString(),childTask});}
        try(var rows=f.db.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{f.taskId()})){assertTrue(rows.moveToFirst());JSONObject current=new JSONObject(rows.getString(0)).put("relatedTasks",new JSONArray().put(new JSONObject().put("taskId",childTask).put("runId",childRun)));f.db.execSQL("UPDATE tasks SET document=? WHERE task_id=?",new Object[]{current.toString(),f.taskId()});}
        assertNotNull(f.inspect());f.db.execSQL("INSERT INTO leases(resource_id,run_id,epoch,expires_at) VALUES(?,?,?,?)",new Object[]{"episode-child-fixture",childRun,child.getInt("coordinatorEpoch"),System.currentTimeMillis()+60000});assertThrows(SecurityException.class,f::inspect);assertEquals(0,f.calls);
    }}
    @Test public void originalLearningJobCreatesOnePendingEpisodeWithoutPromotingItToAMethod()throws Exception{try(Fixture f=new Fixture()){
        SourceRef source=f.retain();assertEquals("all_batches_settled",f.learn(source,()->{}).getString("invocationOutcome"));assertEquals(1,f.calls);assertEquals(1,f.candidates());assertEquals(0,f.records());JSONObject candidate=f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").getJSONObject(0);assertEquals("episodic",candidate.getString("layer"));assertTrue(candidate.getString("text").contains("结果分类（原生固定）：unknown"));assertFalse(candidate.has("assertion"));JSONObject accepted=f.memory.acceptCandidate(scope(),candidate.getString("id"),candidate.getLong("revision")).getJSONObject("record");assertEquals("unverified",accepted.getString("validationStatus"));assertThrows(Exception.class,()->f.memory.reviewedProcedure(scope(),accepted.getString("id"),accepted.getLong("revision")));
    }}
}
