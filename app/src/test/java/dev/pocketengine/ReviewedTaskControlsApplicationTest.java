package dev.pocketengine;

import static org.junit.Assert.*;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.EngineModule;
import dev.pocketengine.features.teams.TaskStoreTeamWriteHost;
import dev.pocketengine.features.teams.TeamWorkFiles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Actual owner dispatch and TaskStore admission; a held executor makes the native queue deterministic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ReviewedTaskControlsApplicationTest {
    private EngineApplication app;
    private ExecutorService originalWorkers;
    private final HeldExecutor workers=new HeldExecutor();
    @Before public void prepare()throws Exception{
        app=(EngineApplication)RuntimeEnvironment.getApplication();
        originalWorkers=(ExecutorService)field(EngineApplication.class,"capabilityWorkers").get(app);
        field(EngineApplication.class,"capabilityWorkers").set(app,workers);
        app.settings.save(new JSONObject().put("baseUrl","http://127.0.0.1:9/v1").put("clearApiKey",true)
                .put("model","fixture-model").put("provider","openai_chat").put("sendScreenshots",false)
                .put("maxSteps",8).put("requestTimeoutSeconds",10).put("maxOutputTokens",4096));
    }
    @After public void close()throws Exception{
        if(app!=null){
            for(RunControl control:controls().values())control.cancel();controls().clear();
            field(EngineApplication.class,"capabilityWorkers").set(app,originalWorkers);
            if(app.features!=null)app.features.close();
        }
        workers.shutdownNow();
    }
    private static Field field(Class<?> owner,String name)throws Exception{Field field=owner.getDeclaredField(name);field.setAccessible(true);return field;}
    @SuppressWarnings("unchecked") private ConcurrentMap<String,RunControl> controls()throws Exception{return (ConcurrentMap<String,RunControl>)field(EngineApplication.class,"capabilityControls").get(app);}
    private JSONObject waiting()throws Exception{
        String id=app.tasks.createCapabilityTask("Continue the original reviewed task","").getString("id");
        app.tasks.initializeExecutionScope(id,4);JSONObject task=app.tasks.startAttempt(id,"capabilities",4);
        app.tasks.endAttempt(id,task.getString("runId"),"paused","Original worker settled",new JSONObject());return task;
    }
    private JSONObject target(JSONObject task)throws Exception{return new JSONObject().put("taskId",task.getString("id")).put("runId",task.getString("runId")).put("epoch",task.getInt("coordinatorEpoch"));}

    @Test public void queuedOldResumeCannotContinueASuccessorOrStoreStaleFeedback()throws Exception{
        JSONObject first=waiting();String id=first.getString("id");
        JSONObject next=app.tasks.startAttempt(id,"capabilities",4);app.tasks.endAttempt(id,next.getString("runId"),"paused","Replacement worker settled",new JSONObject());
        String before=app.tasks.get(id,false).toString();
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("resumeTask",target(first).put("feedback","Old window input")));
        assertEquals(before,app.tasks.get(id,false).toString());assertTrue(workers.pending.isEmpty());assertTrue(controls().isEmpty());
    }

    @Test public void reviewedResumeReservesOnceAndCancellationBeforePickupNeverRunsTheWorker()throws Exception{
        JSONObject first=waiting();String id=first.getString("id");
        JSONObject receipt=app.dispatchOwnerAction("resumeTask",target(first).put("feedback","Keep the original result"));
        assertEquals(id,receipt.getString("taskId"));JSONObject admitted=app.tasks.get(id,false);RunControl current=controls().get(id);
        assertNotNull(current);assertTrue(current.matchesHostRun(id,admitted.getString("runId"),admitted.getInt("coordinatorEpoch")));
        assertEquals(first.getInt("coordinatorEpoch")+1,admitted.getInt("coordinatorEpoch"));assertEquals(1,workers.pending.size());
        assertEquals("Keep the original result",admitted.getString("feedback"));assertEquals(4,admitted.getJSONObject("executionScope").getInt("toolLimit"));
        assertThrows(SecurityException.class,()->app.dispatchOwnerAction("resumeTask",target(first).put("feedback","Duplicate")));
        current.cancel();assertEquals("cancelled",app.tasks.get(id,false).getString("status"));
        workers.pending.remove().run();assertEquals("cancelled",app.tasks.get(id,false).getString("status"));
        assertEquals(admitted.getString("runId"),app.tasks.get(id,false).getString("runId"));assertTrue(controls().isEmpty());
    }

    @Test public void halfOrCoercedIdentityIsRejectedBeforeResumeOrStopSideEffects()throws Exception{
        JSONObject first=waiting();String id=first.getString("id"),before=app.tasks.get(id,false).toString();
        for(String action:new String[]{"resumeTask","stopTask"}){
            assertThrows(SecurityException.class,()->app.dispatchOwnerAction(action,new JSONObject().put("taskId",id).put("runId",first.getString("runId"))));
            assertThrows(SecurityException.class,()->app.dispatchOwnerAction(action,target(first).put("epoch",String.valueOf(first.getInt("coordinatorEpoch")))));
            assertEquals(before,app.tasks.get(id,false).toString());
        }
        assertTrue(workers.pending.isEmpty());
    }

    @Test public void rejectedContinuationQueueSettlesOnlyItsReservedAttempt()throws Exception{
        JSONObject first=waiting();String id=first.getString("id");workers.rejected=true;
        assertThrows(RejectedExecutionException.class,()->app.dispatchOwnerAction("resumeTask",target(first).put("feedback","Reviewed feedback")));
        JSONObject retained=app.tasks.get(id,false);assertEquals("failed",retained.getString("status"));
        assertEquals(first.getInt("coordinatorEpoch")+1,retained.getInt("coordinatorEpoch"));assertEquals(4,retained.getJSONObject("executionScope").getInt("toolLimit"));
        assertEquals("Reviewed feedback",retained.getString("feedback"));assertTrue(workers.pending.isEmpty());assertTrue(controls().isEmpty());
    }

    @Test public void teamStopRejectsAnUnboundReplacementControl()throws Exception{
        JSONObject first=waiting();String id=first.getString("id");
        // Synthetic assignment metadata and an unbound replacement map entry exercise the identity guard.
        // The actual owner dispatcher and TaskStore validation/cancel methods remain unchanged.
        JSONObject task=app.tasks.get(id,false).put("origin",new JSONObject().put("kind","team_integration"));
        Method write=app.tasks.getClass().getDeclaredMethod("write",JSONObject.class);write.setAccessible(true);write.invoke(app.tasks,task);
        Method workspace=FeatureHost.class.getDeclaredMethod("teamWorkspace",EngineModule.class);workspace.setAccessible(true);
        TaskStoreTeamWriteHost team=new TaskStoreTeamWriteHost(app.tasks,(TeamWorkFiles.Workspace)workspace.invoke(app.features,app.features.module("workspace")),ignored->{});
        Field mounted=field(FeatureHost.class,"nativeTeamWrites");Object previous=mounted.get(app.features);mounted.set(app.features,team);
        @SuppressWarnings("unchecked") ConcurrentMap<String,RunControl> members=(ConcurrentMap<String,RunControl>)field(TaskStoreTeamWriteHost.class,"integrationTasks").get(team);
        RunControl successor=new RunControl();successor.bindTask(id);members.put(id,successor);
        try{
            assertThrows(SecurityException.class,()->app.dispatchOwnerAction("stopTask",target(first)));
            assertFalse(successor.isCancelled());assertFalse(app.tasks.get(id,false).optBoolean("cancelRequested"));assertEquals(first.getString("runId"),app.tasks.get(id,false).getString("runId"));
        }finally{members.clear();mounted.set(app.features,previous);team.close();}
    }

    private static final class HeldExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> pending=new ArrayDeque<>();boolean closed,rejected;
        @Override public void execute(Runnable command){if(closed||rejected)throw new RejectedExecutionException("Synthetic unavailable native queue");pending.add(command);}
        @Override public void shutdown(){closed=true;}
        @Override public List<Runnable> shutdownNow(){closed=true;List<Runnable> waiting=List.copyOf(pending);pending.clear();return waiting;}
        @Override public boolean isShutdown(){return closed;}
        @Override public boolean isTerminated(){return closed;}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return closed;}
    }
}
