package dev.pocketengine.runtime;

import static org.junit.Assert.*;
import android.app.Application;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.core.RunJournal;
import dev.pocketengine.features.memory.MemoryHostFence;
import dev.pocketengine.features.memory.TaskStoreMemoryReader;
import dev.pocketengine.store.TaskStore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

/** Actual original SQLite checkpoint and compaction consumers. Not device or provider acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class RunProgressCheckpointNativeTest {
    private static final class Fixture implements AutoCloseable {
        final Application app=RuntimeEnvironment.getApplication();final AtomicBoolean ready=new AtomicBoolean(true);
        TaskStore tasks;RunControl control=new RunControl();String task,run;int epoch;RunJournal journal;
        Fixture()throws Exception {
            app.deleteDatabase(TaskStore.DATABASE_NAME);tasks=open();JSONObject created=tasks.createCapabilityTask("核对同一目录的读取拒绝","progress-guard-conversation");task=created.getString("id");
            tasks.initializeExecutionScope(task,24);JSONObject attempt=tasks.startAttempt(task,"capabilities",24);bind(attempt);
        }
        TaskStore open(){return new TaskStore(app,ready::get,(stage,schema)->{});}
        void bind(JSONObject attempt)throws Exception {run=attempt.getString("runId");epoch=attempt.getInt("coordinatorEpoch");if(!control.hasHostRun())control.bindHostRun(task,run,epoch);journal=tasks.journal(task,run,epoch,control);}
        JSONArray history()throws Exception {return new JSONArray().put(new JSONObject().put("role","system").put("content","Original native fixture"))
                .put(new JSONObject().put("role","user").put("content","核对同一目录"));}
        RepeatToolNotice failures(int count)throws Exception {RepeatToolNotice guard=new RepeatToolNotice();for(int i=0;i<count;i++){guard.beginRound(1);guard.afterTool("project_read",new JSONObject().put("path","same.txt"),new JSONObject().put("ok",false).put("code","permission_denied"),true,false);}return guard;}
        public void close(){control.cancel();ready.set(false);if(tasks!=null&&!tasks.closedForMaintenance())tasks.closeForMaintenance();}
    }
    @Test public void originalGuardAndReceiptCheckpointSurviveLegacyWriterAndRejectRegressedState()throws Exception {
        try(Fixture f=new Fixture()){
            RepeatToolNotice old=f.failures(2),current=f.failures(3);assertTrue(f.journal.progressGuardSupported());
            f.journal.checkpoint(f.history(),3,new JSONObject(),current.snapshot());String saved=f.journal.restore().getJSONObject("progressGuard").toString();
            f.journal.checkpoint(f.history(),3,new JSONObject().put("referencesExpired",true));assertEquals(saved,f.journal.restore().getJSONObject("progressGuard").toString());
            assertThrows(SecurityException.class,()->f.journal.checkpoint(f.history(),2,new JSONObject(),old.snapshot()));
            assertEquals(saved,f.journal.restore().getJSONObject("progressGuard").toString());assertEquals(3,f.journal.restore().getInt("steps"));
        }
    }
    @Test public void coldStoreAndNextOriginalAttemptRetainHardStopAndCumulativeScope()throws Exception {
        try(Fixture f=new Fixture()){
            RepeatToolNotice guard=f.failures(6);f.journal.checkpoint(f.history(),6,new JSONObject(),guard.snapshot());JSONObject scope=f.tasks.get(f.task,false).getJSONObject("executionScope");
            f.tasks.endAttempt(f.task,f.run,"awaiting_user","同一失败已停止",new JSONObject());String priorRun=f.run;int priorEpoch=f.epoch;
            f.control.cancel();f.ready.set(false);f.tasks.closeForMaintenance();f.tasks=f.open();f.ready.set(true);f.control=new RunControl();
            JSONObject next=f.tasks.startOriginalCapabilityAttempt(f.task,priorRun,priorEpoch,24,f.control,"");f.bind(next);
            assertNotEquals(priorRun,f.run);assertEquals(6,f.journal.restore().getInt("steps"));
            RepeatToolNotice restored=new RepeatToolNotice();restored.restore(f.journal.restore().getJSONObject("progressGuard"));assertTrue(restored.stopRequired());
            JSONObject after=f.tasks.get(f.task,false).getJSONObject("executionScope");
            for(String key:new String[]{"revision","deadlineAt","toolLimit","modelCallLimit","toolsUsed","modelCallsUsed"})assertEquals(key,scope.getLong(key),after.getLong(key));
            assertFalse(after.getBoolean("autoRefill"));
        }
    }
    @Test public void actualCompactionDecoratorPreservesGuardOnNewAndLegacyCheckpoints()throws Exception {
        try(Fixture f=new Fixture()){
            TaskStoreMemoryReader.RawBinding binding=new TaskStoreMemoryReader.RawBinding(f.task,f.run,f.epoch,f.control,new MemoryHostFence(){
                public void check()throws Exception {f.control.check();}
                public JSONObject admit(Admission action)throws Exception{synchronized(f.tasks){check();return action.run();}}
            },()->0L);
            ModelConfig model=new ModelConfig("http://127.0.0.1:1/v1","fixture-only","fixture",false,24,10,"openai_chat",1000);
            RunJournal decorated=f.journal.withCompaction(f.tasks.compactionJournal(f.task,f.run,f.epoch,f.control,binding,()->model));
            RepeatToolNotice guard=f.failures(3);decorated.checkpoint(f.history(),3,new JSONObject(),guard.snapshot());assertTrue(decorated.progressGuardSupported());
            String saved=decorated.restore().getJSONObject("progressGuard").toString();decorated.checkpoint(f.history(),3,new JSONObject());
            assertEquals(saved,decorated.restore().getJSONObject("progressGuard").toString());assertEquals(saved,f.journal.restore().getJSONObject("progressGuard").toString());
        }
    }
    @Test public void priorRunJournalCannotOverwriteTheSuccessorGuard()throws Exception {
        try(Fixture f=new Fixture()){
            RepeatToolNotice priorGuard=f.failures(3);f.journal.checkpoint(f.history(),3,new JSONObject(),priorGuard.snapshot());
            RunJournal priorJournal=f.journal;RunControl priorControl=f.control;String priorRun=f.run;int priorEpoch=f.epoch;
            f.tasks.endAttempt(f.task,priorRun,"awaiting_user","等待核对",new JSONObject());f.control=new RunControl();
            f.bind(f.tasks.startOriginalCapabilityAttempt(f.task,priorRun,priorEpoch,24,f.control,""));
            JSONObject successorBefore=f.journal.restore();assertNotEquals(priorRun,f.run);
            assertFalse(priorControl.isCancelled());
            assertThrows(SecurityException.class,()->priorJournal.checkpoint(f.history(),6,new JSONObject(),f.failures(6).snapshot()));
            JSONObject successorAfter=f.journal.restore();assertEquals(successorBefore.getJSONObject("progressGuard").toString(),successorAfter.getJSONObject("progressGuard").toString());
            assertEquals(successorBefore.getInt("steps"),successorAfter.getInt("steps"));assertFalse(f.control.isCancelled());priorControl.cancel();
        }
    }
}
