package dev.pocketengine.features.memory;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.features.KnowledgeLearningWorkflow;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.skills.SkillsModule;
import dev.pocketengine.runtime.usage.InferenceCapture;
import dev.pocketengine.runtime.usage.InferenceReceipt;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
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

/** Real original SQLite task/mailbox/checkpoint + Assistant USER row + MemoryBook.
 * Model answer/transport is a bounded fixture; no provider or production network claim. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class AutomaticUserLearningHostNativeTest {
    private static JSONObject scope()throws Exception{return new JSONObject().put("ownerId","owner").put("spaceId","private");}
    private static final String WORDS="先读真实错误，再改一处并验证。";
    private static JSONObject draft()throws Exception{return new JSONObject().put("layer","preference").put("text","模型概括不得替代原话").put("quote",WORDS).put("reason","原消息逐字表达").put("applicability","相同排错场景").put("confidence",0.6);}
    private static final class Fixture implements AutoCloseable {
        final android.content.Context android=RuntimeEnvironment.getApplication();
        final FeatureContext context=new FeatureContext(android,new SettingsStore(android),new SecretVault(android),event->{});
        final TaskStore tasks;final AssistantModule assistant;final MemoryModule memory;final SkillsModule skills;final SQLiteDatabase db;
        final String conversation=UUID.randomUUID().toString(),sourceTask,sourceRun;final int sourceEpoch;final SourceRef source;
        final AutomaticUserLearningHost host;final MemoryHostFence fence;int admissions,modelSends;String learningTask,learningRun;int learningEpoch;RunControl original;
        Fixture()throws Exception {
            android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            assistant=new AssistantModule(context,null,null,tasks);memory=new MemoryModule(context,(identity,kind,id)->assistant.resolveSource(identity,id));skills=new SkillsModule(context);
            source=new SourceRef("message",UUID.randomUUID().toString(),1,"owner","private");
            tasks.conversationRepository().transaction(()->{tasks.conversationRepository().saveConversation(new JSONObject().put("conversationId",conversation).put("title","真实原话测试").put("messages",new JSONArray()));tasks.conversationRepository().saveSource(source.toJson().put("conversationId",conversation).put("role","user").put("deleted",false));tasks.conversationRepository().saveMessage(conversation,new JSONObject().put("id",source.sourceId).put("revision",source.revision).put("role","user").put("content",WORDS));return null;});
            sourceTask=tasks.createCapabilityTask(WORDS,conversation).getString("id");JSONObject attempt=tasks.startAttempt(sourceTask,"capabilities",4);sourceRun=attempt.getString("runId");sourceEpoch=attempt.getInt("coordinatorEpoch");RunControl sourceControl=new RunControl();sourceControl.bindHostRun(sourceTask,sourceRun,sourceEpoch);
            tasks.openOwnerInputMailbox(sourceTask,sourceRun,sourceEpoch,conversation,sourceControl);
            tasks.appendOwnerGoalInput(sourceTask,sourceRun,sourceEpoch,conversation,new JSONObject().put("id",source.sourceId).put("clientMessageId",UUID.randomUUID().toString()).put("revision",source.revision).put("content",WORDS),"queue",sourceControl);
            tasks.journal(sourceTask,sourceRun,sourceEpoch,sourceControl).checkpoint(new JSONArray().put(new JSONObject().put("role","user").put("content",WORDS).put("ownerMessageId",source.sourceId).put("ownerMessageRevision",source.revision)),0,new JSONObject());
            tasks.endAttempt(sourceTask,sourceRun,"completed","native owner confirmed fixture",new JSONObject());
            fence=new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{return action.run();}};
            host=new AutomaticUserLearningHost(tasks,memory,assistant,(id,revision)->{admissions++;learningTask=id;return new JSONObject().put("admitted",false).put("originalQueued",true);});
        }
        void enable()throws Exception{memory.setAutomaticLearningPolicyForOwner(memory.automaticLearningPolicy().getLong("revision"),true,3);}
        JSONObject register()throws Exception{return host.onSuccessfulTask(sourceTask,fence);}
        void startOriginalJob()throws Exception{original=new RunControl();JSONObject attempt=tasks.startSourceLearningJob(learningTask,tasks.sourceLearningJob(learningTask).getJSONObject("job").getLong("revision"),original);learningRun=attempt.getString("runId");learningEpoch=attempt.getInt("coordinatorEpoch");}
        JSONObject runOriginalJob()throws Exception {
            var automatic=host.contextForOriginalJob(learningTask,learningRun,learningEpoch,original);AtomicReference<InferenceReceipt> terminal=new AtomicReference<>();
            var workflow=new KnowledgeLearningWorkflow(memory,skills,assistant::readMemorySource,(messages,control)->{
                JSONObject active=tasks.sourceLearningJob(learningTask).getJSONObject("active");
                var target=InferenceCapture.observe(tasks.sourceLearningInferenceTarget(learningTask,learningRun,learningEpoch,active.getString("operationId"),original),new InferenceCapture.Sink(){public void started(InferenceReceipt receipt){}public void finished(InferenceReceipt receipt){terminal.set(receipt);}});
                target=InferenceCapture.withAdmission(target,()->host.requireCurrentOriginalJobSource(learningTask,learningRun,learningEpoch,source,original));
                var capture=new InferenceCapture(new ModelConfig("https://fixture.invalid","fixture-key","fixture-model",false,3,10),original,target);capture.dispatch();modelSends++;capture.finish(InferenceReceipt.Outcome.COMPLETED);
                return new JSONObject().put("content",draft().toString());
            },new MemoryHostFence(){public void check()throws Exception{original.check();}public JSONObject admit(Admission action)throws Exception{synchronized(tasks){original.check();return action.run();}}},automatic);
            return new SourceLearningJob(tasks,learningTask,learningRun,learningEpoch,original,workflow,terminal::get,()->null,1).run();
        }
        int jobCount()throws Exception{return tasks.listSourceLearningJobs("",20).getJSONArray("jobs").length();}
        public void close(){assistant.close();db.close();}
    }
    @Test public void actualCompletedUserCheckpointCreatesOneOriginalJobAndOnePendingExactQuote()throws Exception {
        try(Fixture f=new Fixture()){assertEquals(WORDS,f.assistant.readAutomaticOwnerUtterance(f.source,f.sourceTask,f.sourceRun,f.sourceEpoch).text);f.enable();JSONObject created=f.register();assertTrue(created.getBoolean("automaticCreated"));assertEquals(1,f.jobCount());assertEquals(0,f.modelSends);String job=f.learningTask;
            assertFalse(f.register().getBoolean("automaticCreated"));assertEquals(job,f.learningTask);assertEquals(1,f.jobCount());
            JSONObject manifest=f.tasks.sourceLearningJob(job).getJSONObject("job");assertEquals(f.sourceTask,manifest.getJSONObject("automaticUserSource").getString("taskId"));assertEquals(f.source,SourceRef.fromJson(manifest.getJSONArray("inputs").getJSONObject(0).getJSONObject("sourceRef")));assertFalse(manifest.toString().contains(WORDS));
            f.startOriginalJob();assertNotEquals(f.sourceRun,f.learningRun);assertEquals("all_batches_settled",f.runOriginalJob().getString("invocationOutcome"));assertEquals(1,f.modelSends);
            JSONObject candidate=f.memory.handle("listCandidates",new JSONObject().put("scope",scope())).getJSONArray("candidates").getJSONObject(0);assertEquals("pending",candidate.getString("status"));assertEquals(WORDS,candidate.getString("text"));assertEquals(0,f.memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());
        }
    }
    @Test public void optOutBeforeEventCreatesNoJobAndNoAdmissionOrModelSend()throws Exception {try(Fixture f=new Fixture()){assertEquals("automatic_mode_disabled",f.register().getString("outcome"));assertEquals(0,f.jobCount());assertEquals(0,f.admissions);assertEquals(0,f.modelSends);}}
    @Test public void withdrawnRealUserRowCannotCreateJobOrSendModel()throws Exception {try(Fixture f=new Fixture()){f.enable();f.assistant.handle("deleteMessage",new JSONObject().put("conversationId",f.conversation).put("messageId",f.source.sourceId).put("revision",f.source.revision));assertEquals("no_current_user_sources",f.register().getString("outcome"));assertEquals(0,f.jobCount());assertEquals(0,f.admissions);assertEquals(0,f.modelSends);}}
    @Test public void formalSourceForgetBlocksOriginalQueueAndModelSend()throws Exception {try(Fixture f=new Fixture()){f.enable();f.register();JSONObject actualDraft=draft();actualDraft.remove("quote");actualDraft.put("text",WORDS);JSONObject candidate=f.memory.proposeLearning(scope(),actualDraft,f.source).getJSONObject("candidate"),record=f.memory.acceptCandidate(scope(),candidate.getString("id"),candidate.getLong("revision")).getJSONObject("record");f.memory.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));assertTrue(f.memory.isSourceForgotten(scope(),f.source));assertThrows(Exception.class,()->f.host.admitUnstartedOriginalJob(f.learningTask,f.fence));assertEquals(1,f.admissions);assertEquals(0,f.modelSends);}}
    @Test public void optOutOrRetirementAfterRegistrationBlocksCurrentSendBoundary()throws Exception {for(boolean optOut:new boolean[]{true,false})try(Fixture f=new Fixture()){f.enable();f.register();f.startOriginalJob();f.host.requireCurrentOriginalJobSource(f.learningTask,f.learningRun,f.learningEpoch,f.source,f.original);
        if(optOut)f.memory.setAutomaticLearningPolicyForOwner(f.memory.automaticLearningPolicy().getLong("revision"),false,3);else f.assistant.retireMemoryContexts(new JSONArray().put(new JSONObject().put("sourceRefs",new JSONArray().put(f.source.toJson()))),"owner_forget");
        assertThrows(Exception.class,()->f.host.requireCurrentOriginalJobSource(f.learningTask,f.learningRun,f.learningEpoch,f.source,f.original));assertEquals(0,f.modelSends);
    }}
}
