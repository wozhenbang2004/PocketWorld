package dev.pocketengine;

import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.core.RunControl;
import dev.pocketengine.features.FeatureHost;
import dev.pocketengine.features.memory.SourceRef;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Real owner-work admission, native cancellation and SQL settlement; only worker pickup is held. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class,shadows=OwnerWorkDispatchCancellationNativeTest.PresentationBoundary.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class OwnerWorkDispatchCancellationNativeTest {
    @Implements(EngineApplication.class)
    public static final class PresentationBoundary extends org.robolectric.shadows.ShadowApplication {
        @Implementation protected void onCreate(){}
        @Implementation protected void pushTask(String id){}
    }
    private static final class Queue extends AbstractExecutorService {
        final List<Runnable> pending=new ArrayList<>();boolean closed;
        public void execute(Runnable action){if(closed)throw new java.util.concurrent.RejectedExecutionException("fixture worker unavailable");pending.add(action);}
        public void shutdown(){closed=true;}
        public List<Runnable> shutdownNow(){closed=true;var remaining=new ArrayList<>(pending);pending.clear();return remaining;}
        public boolean isShutdown(){return closed;}
        public boolean isTerminated(){return closed&&pending.isEmpty();}
        public boolean awaitTermination(long duration,TimeUnit unit){return isTerminated();}
    }
    private EngineApplication app;private Queue workers;private String conversation,foreground,work;private RunControl foregroundControl;
    private static String id(){return UUID.randomUUID().toString();}
    private Object field(String name)throws Exception{Field field=EngineApplication.class.getDeclaredField(name);field.setAccessible(true);return field.get(app);}
    @Before public void prepare()throws Exception{
        app=(EngineApplication)RuntimeEnvironment.getApplication();app.deleteDatabase(TaskStore.DATABASE_NAME);app.tasks=new TaskStore(app);dev.pocketengine.testutil.ReadyApplicationFixture.complete(app);
        app.settings=new SettingsStore(app);app.features=new FeatureHost(ignored->{},app.tasks,()->false);app.setOriginalDeviceChildStops((task,control)->{});
        app.getSharedPreferences("private_settings",0).edit().putString("baseUrl","https://example.invalid").putString("model","fixture-no-network").putString("provider","openai_chat").commit();
        ((ExecutorService)field("capabilityWorkers")).shutdownNow();workers=new Queue();Field executor=EngineApplication.class.getDeclaredField("capabilityWorkers");executor.setAccessible(true);executor.set(app,workers);
        var repository=app.tasks.conversationRepository();repository.initializeEmpty();conversation=id();
        repository.transaction(()->{repository.saveConversation(new JSONObject().put("conversationId",conversation).put("title","original owner work").put("createdAt",1L).put("updatedAt",1L));return null;});
        String goal="Research the selected original input";foreground=app.tasks.createCapabilityTask(goal,conversation).getString("id");app.tasks.initializeExecutionScope(foreground,32);
        JSONObject attempt=app.tasks.startAttempt(foreground,"capabilities",32);String run=attempt.getString("runId");int epoch=attempt.getInt("coordinatorEpoch");foregroundControl=new RunControl();foregroundControl.bindHostRun(foreground,run,epoch);
        app.tasks.authorizeTools(foreground,run,new JSONArray().put("owner_work_start"));app.tasks.openOwnerInputMailbox(foreground,run,epoch,conversation,foregroundControl);
        String message=id(),client=id();JSONObject ack=new JSONObject().put("status","accepted").put("taskId",foreground).put("runId",run).put("epoch",epoch).put("conversationId",conversation).put("messageId",message).put("clientMessageId",client);
        JSONObject row=new JSONObject().put("id",message).put("revision",1L).put("role","user").put("content",goal).put("at",System.currentTimeMillis()).put("conversationId",conversation)
                .put("clientMessageId",client).put("requestFingerprint","a".repeat(64)).put("inputMode","queue").put("attachments",new JSONArray()).put("submission",ack);
        repository.transaction(()->{repository.saveSource(new SourceRef("message",message,1L,"owner","private").toJson().put("conversationId",conversation).put("role","user").put("deleted",false).put("clientMessageId",client).put("requestFingerprint","a".repeat(64)).put("submission",ack));repository.saveMessage(conversation,row);return null;});
        app.tasks.appendOwnerGoalInput(foreground,run,epoch,conversation,row,"queue",foregroundControl);
        var journal=app.tasks.journal(foreground,run,epoch,foregroundControl);journal.checkpoint(new JSONArray().put(new JSONObject().put("role","user").put("content",goal).put("ownerMessageId",message).put("ownerMessageRevision",1L).put("ownerInputMode","queue")),0,new JSONObject());
        JSONObject input=new JSONObject().put("goal",goal).put("toolLimit",8).put("modelCallLimit",10);String operation=journal.beginOperation("owner_work_start",input);
        work=app.tasks.admitOwnerWork(foreground,run,epoch,operation,input,foregroundControl).getString("taskId");
    }
    @After public void close()throws Exception{
        workers.shutdownNow();app.features.close();Field db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(app.tasks)).close();
    }
    private void launch()throws Exception{
        var launch=EngineApplication.class.getDeclaredMethod("launchOwnerWork",String.class,ModelConfig.class);launch.setAccessible(true);
        try{launch.invoke(app,work,app.settings.get());}catch(java.lang.reflect.InvocationTargetException failed){if(failed.getCause() instanceof Exception cause)throw cause;if(failed.getCause() instanceof Error cause)throw cause;throw failed;}
    }
    private void assertSettled(JSONObject expected,String status)throws Exception{
        JSONObject actual=app.tasks.get(work,false);assertEquals(expected.getString("runId"),actual.getString("runId"));assertEquals(expected.getInt("coordinatorEpoch"),actual.getInt("coordinatorEpoch"));assertEquals(status,actual.getString("status"));
        assertNull(((Map<?,?>)field("capabilityControls")).get(work));assertEquals(0,app.tasks.inferenceUsage(work).getJSONArray("receipts").length());assertFalse(foregroundControl.isCancelled());assertEquals(2,app.tasks.list().length());
        Field dbField=TaskStore.class.getDeclaredField("db");dbField.setAccessible(true);var db=(android.database.sqlite.SQLiteDatabase)dbField.get(app.tasks);
        try(var row=db.rawQuery("SELECT status FROM runs WHERE task_id=? AND run_id=?",new String[]{work,expected.getString("runId")})){assertTrue(row.moveToFirst());assertEquals(status,row.getString(0));}
        try(var row=db.rawQuery("SELECT COUNT(*) FROM leases WHERE run_id=?",new String[]{expected.getString("runId")})){assertTrue(row.moveToFirst());assertEquals(0,row.getInt(0));}
    }
    @Test public void nativeCancelSettlesQueuedWorkBeforeItsWorkerCanBePickedUp()throws Exception{
        launch();JSONObject admitted=app.tasks.get(work,false);assertEquals(1,workers.pending.size());Runnable oldPickup=workers.pending.get(0);
        app.ownerWorkControl(conversation,new JSONObject().put("taskId",work).put("runId",admitted.getString("runId")).put("epoch",admitted.getInt("coordinatorEpoch")).put("action","cancel"));
        assertSettled(admitted,"cancelled");String before=TaskStore.capabilityFingerprint(app.tasks.get(work,false));
        oldPickup.run();assertSettled(admitted,"cancelled");assertEquals(before,TaskStore.capabilityFingerprint(app.tasks.get(work,false)));
    }
    @Test public void rejectedOwnerWorkExecutorSettlesOnlyTheOriginalReservation()throws Exception{
        workers.shutdown();assertThrows(java.util.concurrent.RejectedExecutionException.class,this::launch);
        JSONObject admitted=app.tasks.get(work,false);assertFalse(admitted.getString("runId").isEmpty());assertSettled(admitted,"failed");assertTrue(workers.pending.isEmpty());
    }
}
