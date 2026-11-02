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
        final AutomaticUserLearningHost host;final MemoryHostFence fence;int admissions,modelSends;String learningTask,learningRun;int learningEpoch;RunControl original;boolean startOnAdmission;
        final java.util.List<String> actuallyStarted=new java.util.ArrayList<>();
        Fixture()throws Exception {this(1);}
        Fixture(int sourceCount)throws Exception {
            android.deleteDatabase("pocket-world.db");tasks=new TaskStore(android);var field=TaskStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(tasks);
            assistant=new AssistantModule(context,null,null,tasks);memory=new MemoryModule(context,(identity,kind,id)->assistant.resolveSource(identity,id));skills=new SkillsModule(context);
            source=new SourceRef("message",UUID.randomUUID().toString(),1,"owner","private");
            tasks.conversationRepository().transaction(()->{tasks.conversationRepository().saveConversation(new JSONObject().put("conversationId",conversation).put("title","真实原话测试").put("messages",new JSONArray()));tasks.conversationRepository().saveSource(source.toJson().put("conversationId",conversation).put("role","user").put("deleted",false));tasks.conversationRepository().saveMessage(conversation,new JSONObject().put("id",source.sourceId).put("revision",source.revision).put("role","user").put("content",WORDS));return null;});
            sourceTask=tasks.createCapabilityTask(WORDS,conversation).getString("id");JSONObject attempt=tasks.startAttempt(sourceTask,"capabilities",4);sourceRun=attempt.getString("runId");sourceEpoch=attempt.getInt("coordinatorEpoch");RunControl sourceControl=new RunControl();sourceControl.bindHostRun(sourceTask,sourceRun,sourceEpoch);
            tasks.openOwnerInputMailbox(sourceTask,sourceRun,sourceEpoch,conversation,sourceControl);
            tasks.appendOwnerGoalInput(sourceTask,sourceRun,sourceEpoch,conversation,new JSONObject().put("id",source.sourceId).put("clientMessageId",UUID.randomUUID().toString()).put("revision",source.revision).put("content",WORDS),"queue",sourceControl);
            JSONArray adopted=new JSONArray().put(new JSONObject().put("role","user").put("content",WORDS).put("ownerMessageId",source.sourceId).put("ownerMessageRevision",source.revision));
            for(int i=1;i<sourceCount;i++){SourceRef extra=new SourceRef("message",UUID.randomUUID().toString(),1,"owner","private");String words=WORDS+" 原话"+i;
                tasks.conversationRepository().transaction(()->{tasks.conversationRepository().saveSource(extra.toJson().put("conversationId",conversation).put("role","user").put("deleted",false));tasks.conversationRepository().saveMessage(conversation,new JSONObject().put("id",extra.sourceId).put("revision",extra.revision).put("role","user").put("content",words));return null;});
                tasks.appendOwnerInput(sourceTask,sourceRun,sourceEpoch,conversation,new JSONObject().put("id",extra.sourceId).put("clientMessageId",UUID.randomUUID().toString()).put("revision",extra.revision).put("content",words),"queue",sourceControl);
                adopted.put(new JSONObject().put("role","user").put("content",words).put("ownerMessageId",extra.sourceId).put("ownerMessageRevision",extra.revision).put("ownerInputMode","queue"));
            }
            if(sourceCount>1)tasks.pollOwnerInputs(sourceTask,sourceRun,sourceEpoch,new JSONArray(),true,false,sourceControl);
            tasks.journal(sourceTask,sourceRun,sourceEpoch,sourceControl).checkpoint(adopted,0,new JSONObject());
            tasks.endAttempt(sourceTask,sourceRun,"completed","native owner confirmed fixture",new JSONObject());
            fence=new MemoryHostFence(){public void check(){}public JSONObject admit(Admission action)throws Exception{return action.run();}};
            host=new AutomaticUserLearningHost(tasks,memory,assistant,(id,revision)->{admissions++;learningTask=id;if(startOnAdmission){startOriginalJob();actuallyStarted.add(id);return new JSONObject().put("admitted",true).put("originalQueued",false);}return new JSONObject().put("admitted",false).put("originalQueued",true).put("reason","controlled_original_host_busy");});
        }
        void enable()throws Exception{memory.setAutomaticLearningPolicyForOwner(memory.automaticLearningPolicy().getLong("revision"),true,3);}
        void enable(int maximum)throws Exception{memory.setAutomaticLearningPolicyForOwner(memory.automaticLearningPolicy().getLong("revision"),true,maximum);}
        JSONObject register()throws Exception{return host.onSuccessfulTask(sourceTask,fence);}
        void startOriginalJob()throws Exception{original=new RunControl();JSONObject job=tasks.sourceLearningJob(learningTask).getJSONObject("job");int window=job.getJSONArray("inputs").length();JSONObject attempt=tasks.startSourceLearningJob(learningTask,job.getLong("revision"),original,window);learningRun=attempt.getString("runId");learningEpoch=attempt.getInt("coordinatorEpoch");}
        JSONObject runOriginalJob()throws Exception {
            var automatic=host.contextForOriginalJob(learningTask,learningRun,learningEpoch,original);AtomicReference<InferenceReceipt> terminal=new AtomicReference<>();
            var workflow=new KnowledgeLearningWorkflow(memory,skills,assistant::readMemorySource,(messages,control)->{
                terminal.set(null);
                JSONObject current=tasks.sourceLearningJob(learningTask),active=current.getJSONObject("active");SourceRef actualSource=SourceRef.fromJson(current.getJSONObject("job").getJSONArray("inputs").getJSONObject(active.getInt("index")).getJSONObject("sourceRef"));
                var target=InferenceCapture.observe(tasks.sourceLearningInferenceTarget(learningTask,learningRun,learningEpoch,active.getString("operationId"),original),new InferenceCapture.Sink(){public void started(InferenceReceipt receipt){}public void finished(InferenceReceipt receipt){terminal.set(receipt);}});
                target=InferenceCapture.withAdmission(target,()->host.requireCurrentOriginalJobSource(learningTask,learningRun,learningEpoch,actualSource,original));
                var capture=new InferenceCapture(new ModelConfig("https://fixture.invalid","fixture-key","fixture-model",false,3,10),original,target);capture.dispatch();modelSends++;capture.finish(InferenceReceipt.Outcome.COMPLETED);
                return new JSONObject().put("content",draft().toString());
            },new MemoryHostFence(){public void check()throws Exception{original.check();}public JSONObject admit(Admission action)throws Exception{synchronized(tasks){original.check();return action.run();}}},automatic);
            return new SourceLearningJob(tasks,learningTask,learningRun,learningEpoch,original,workflow,terminal::get,()->null,tasks.sourceLearningJob(learningTask).getJSONObject("job").getInt("attemptMaxBatches")).run();
        }
        int jobCount()throws Exception{return tasks.listSourceLearningJobs("",20).getJSONArray("jobs").length();}
        String anotherSuccessfulTask()throws Exception{
            SourceRef extra=new SourceRef("message",UUID.randomUUID().toString(),1,"owner","private");String words=WORDS+" 另一个真实任务";
            tasks.conversationRepository().transaction(()->{tasks.conversationRepository().saveSource(extra.toJson().put("conversationId",conversation).put("role","user").put("deleted",false));tasks.conversationRepository().saveMessage(conversation,new JSONObject().put("id",extra.sourceId).put("revision",extra.revision).put("role","user").put("content",words));return null;});
            String id=tasks.createCapabilityTask(words,conversation).getString("id");JSONObject attempt=tasks.startAttempt(id,"capabilities",3);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");RunControl nativeOwner=new RunControl();nativeOwner.bindHostRun(id,run,epoch);tasks.openOwnerInputMailbox(id,run,epoch,conversation,nativeOwner);
            tasks.appendOwnerGoalInput(id,run,epoch,conversation,new JSONObject().put("id",extra.sourceId).put("clientMessageId",UUID.randomUUID().toString()).put("revision",1).put("content",words),"queue",nativeOwner);
            tasks.journal(id,run,epoch,nativeOwner).checkpoint(new JSONArray().put(new JSONObject().put("role","user").put("content",words).put("ownerMessageId",extra.sourceId).put("ownerMessageRevision",1)),0,new JSONObject());tasks.endAttempt(id,run,"completed","native owner confirmed second fixture",new JSONObject());return id;
        }
        /** Deterministic metadata order only; original task/run/source/grant documents are untouched. */
        void metadataIndex(String job,long order){android.content.ContentValues key=new android.content.ContentValues();key.put("key",String.format(java.util.Locale.ROOT,"memory_learning_job:%020d:%s",order,job));assertEquals(1,db.update("meta",key,"value=? AND key LIKE ?",new String[]{job,"memory_learning_job:%"}));}
        void addStartedHistory(int count)throws Exception{var document=assistant.readMemorySource(source);JSONObject input=new JSONObject().put("sourceRef",source.toJson()).put("sourceFingerprint",KnowledgeLearningWorkflow.sourceFingerprint(document)).put("title",document.title);
            for(int i=0;i<count;i++){String job=tasks.createSourceLearningJob(new JSONArray().put(new JSONObject(input.toString()))).getString("taskId");RunControl nativeHistory=new RunControl();JSONObject attempt=tasks.startSourceLearningJob(job,1,nativeHistory,1);tasks.endAttempt(job,attempt.getString("runId"),"cancelled","native historical fixture stopped before model",new JSONObject());metadataIndex(job,100+i);}
        }
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
    @Test public void fiveActuallyAdoptedUserRowsFinishTheOwnerApprovedOriginalWindowWithoutAutomaticResume()throws Exception{try(Fixture f=new Fixture(5)){f.enable(5);JSONObject registered=f.register();assertEquals(5,registered.getInt("selectedUserSources"));f.startOriginalJob();assertEquals(5,f.tasks.sourceLearningJob(f.learningTask).getJSONObject("job").getInt("attemptMaxBatches"));assertEquals("all_batches_settled",f.runOriginalJob().getString("invocationOutcome"));assertEquals(5,f.modelSends);assertEquals(5,f.tasks.sourceLearningJob(f.learningTask).getJSONObject("job").getInt("nextIndex"));assertEquals(1,f.admissions);f.host.onOriginalIdle(f.fence);assertEquals(1,f.admissions);assertEquals(5,f.modelSends);assertEquals(0,f.memory.handle("list",new JSONObject().put("scope",scope())).getJSONArray("records").length());}}
    @Test public void originalMetadataPagesReachQueuedJobAfterMoreThanFortyStartedHistories()throws Exception{try(Fixture f=new Fixture()){f.enable();String queued=f.register().getString("taskId");f.metadataIndex(queued,1);f.addStartedHistory(44);f.startOnAdmission=true;f.host.beginOriginalIdleScan();int pages=0;JSONObject result;
        do{result=f.host.onOriginalIdle(f.fence);pages++;assertTrue("native metadata scan did not terminate",pages<=3);}while(result.optBoolean("hasMoreNativeMetadata"));
        assertEquals(3,pages);assertTrue(result.getBoolean("admitted"));assertTrue(result.getBoolean("originalMetadataPageRetained"));assertEquals(java.util.List.of(queued),f.actuallyStarted);assertEquals(0,f.modelSends);
    }}
    @Test public void busyAndOneRealAdmissionRetainTheSamePageUntilOriginalReleaseForTheNextJob()throws Exception{try(Fixture f=new Fixture()){f.enable();String first=f.register().getString("taskId"),second=f.host.onSuccessfulTask(f.anotherSuccessfulTask(),f.fence).getString("taskId");int initial=f.admissions;
        f.host.beginOriginalIdleScan();JSONObject busy=f.host.onOriginalIdle(f.fence);assertFalse(busy.getBoolean("admitted"));assertTrue(busy.getBoolean("originalMetadataPageRetained"));assertFalse(busy.getBoolean("hasMoreNativeMetadata"));assertEquals(initial+1,f.admissions);
        f.startOnAdmission=true;f.host.beginOriginalIdleScan();assertTrue(f.host.onOriginalIdle(f.fence).getBoolean("admitted"));String started=f.learningTask;f.tasks.endAttempt(started,f.learningRun,"cancelled","original fixture release without model",new JSONObject());
        f.host.beginOriginalIdleScan();assertTrue(f.host.onOriginalIdle(f.fence).getBoolean("admitted"));assertEquals(2,new java.util.HashSet<>(f.actuallyStarted).size());assertTrue(f.actuallyStarted.containsAll(java.util.List.of(first,second)));assertEquals(0,f.modelSends);
    }}
    @Test public void retainedOlderPageWrapsOnceOnRealReleaseToFindANewerOriginalQueuedJob()throws Exception{try(Fixture f=new Fixture()){f.enable();String old=f.register().getString("taskId");f.metadataIndex(old,1);f.addStartedHistory(44);f.startOnAdmission=true;f.host.beginOriginalIdleScan();JSONObject page;do{page=f.host.onOriginalIdle(f.fence);}while(page.optBoolean("hasMoreNativeMetadata"));String oldRun=f.learningRun;
        f.startOnAdmission=false;String recent=f.host.onSuccessfulTask(f.anotherSuccessfulTask(),f.fence).getString("taskId");f.metadataIndex(recent,1000);f.tasks.endAttempt(old,oldRun,"cancelled","original release",new JSONObject());f.startOnAdmission=true;f.host.beginOriginalIdleScan();JSONObject exhaustedOldPage=f.host.onOriginalIdle(f.fence);assertTrue(exhaustedOldPage.getBoolean("hasMoreNativeMetadata"));assertEquals(1,f.actuallyStarted.size());assertTrue(f.host.onOriginalIdle(f.fence).getBoolean("admitted"));assertEquals(recent,f.actuallyStarted.get(1));assertEquals(0,f.modelSends);
    }}
    @Test public void withdrawnRealUserRowCannotCreateJobOrSendModel()throws Exception {try(Fixture f=new Fixture()){f.enable();f.assistant.handle("deleteMessage",new JSONObject().put("conversationId",f.conversation).put("messageId",f.source.sourceId).put("revision",f.source.revision));assertEquals("no_current_user_sources",f.register().getString("outcome"));assertEquals(0,f.jobCount());assertEquals(0,f.admissions);assertEquals(0,f.modelSends);}}
    @Test public void formalSourceForgetBlocksOriginalQueueAndModelSend()throws Exception {try(Fixture f=new Fixture()){f.enable();f.register();JSONObject actualDraft=draft();actualDraft.remove("quote");actualDraft.put("text",WORDS);JSONObject candidate=f.memory.proposeLearning(scope(),actualDraft,f.source).getJSONObject("candidate"),record=f.memory.acceptCandidate(scope(),candidate.getString("id"),candidate.getLong("revision")).getJSONObject("record");f.memory.forgetReviewed(scope(),record.getString("id"),record.getLong("revision"));assertTrue(f.memory.isSourceForgotten(scope(),f.source));assertThrows(Exception.class,()->f.host.admitUnstartedOriginalJob(f.learningTask,f.fence));assertEquals(1,f.admissions);assertEquals(0,f.modelSends);}}
    @Test public void optOutOrRetirementAfterRegistrationBlocksCurrentSendBoundary()throws Exception {for(boolean optOut:new boolean[]{true,false})try(Fixture f=new Fixture()){f.enable();f.register();f.startOriginalJob();f.host.requireCurrentOriginalJobSource(f.learningTask,f.learningRun,f.learningEpoch,f.source,f.original);
        if(optOut)f.memory.setAutomaticLearningPolicyForOwner(f.memory.automaticLearningPolicy().getLong("revision"),false,3);else f.assistant.retireMemoryContexts(new JSONArray().put(new JSONObject().put("sourceRefs",new JSONArray().put(f.source.toJson()))),"owner_forget");
        assertThrows(Exception.class,()->f.host.requireCurrentOriginalJobSource(f.learningTask,f.learningRun,f.learningEpoch,f.source,f.original));assertEquals(0,f.modelSends);
    }}
}
