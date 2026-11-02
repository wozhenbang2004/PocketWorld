package dev.pocketengine.features.automation;

import android.database.sqlite.SQLiteDatabase;
import android.os.Looper;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.features.assistant.AssistantModule;
import dev.pocketengine.features.assistant.ConversationRepository;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import static org.junit.Assert.*;

/** Mounted original Engine/worker/TaskStore/conversation SQLite. The busy receipt is an
 * explicit native-boundary fixture; publication and idle paging use actual production code.
 * No model, phone executor, new Engine or internal-state replacement is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public final class AutomationConversationIdleMountedNativeTest {
    private EngineApplication app;
    private SQLiteDatabase db;
    private interface Check {boolean done()throws Exception;}
    private static Object field(Object owner,String name)throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private boolean idleWorkerPending()throws Exception {return ((AtomicBoolean)field(app,"scheduledConversationIdlePending")).get();}
    private static void await(Check check,String message)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(check.done())return;Thread.sleep(5);}
        fail(message);
    }
    private long count(String table)throws Exception {try(var row=db.rawQuery("SELECT count(*) FROM "+table,null)){assertTrue(row.moveToFirst());return row.getLong(0);}}
    private String grant(String run)throws Exception {try(var row=db.rawQuery("SELECT grant_json FROM runs WHERE run_id=?",new String[]{run})){assertTrue(row.moveToFirst());return row.getString(0);}}
    private static JSONObject request(JSONObject nativeOrigin,String goal)throws Exception {
        JSONObject origin=new JSONObject().put("kind","automation").put("occurrenceId",UUID.randomUUID().toString())
                .put("originTaskId",nativeOrigin.getString("taskId")).put("originConversationId",nativeOrigin.getString("conversationId")).put("nativeOrigin",new JSONObject(nativeOrigin.toString()));
        return new JSONObject().put("origin",origin).put("goal",goal).put("title","Mounted idle fixture").put("ruleId",UUID.randomUUID().toString()).put("ruleRevision",1).put("overlapPolicy","buffer_one");
    }
    private static boolean contains(JSONArray ids,String id)throws Exception {for(int i=0;i<ids.length();i++)if(id.equals(ids.getString(i)))return true;return false;}
    @After public void close()throws Exception {
        if(app!=null){ExecutorService workers=(ExecutorService)field(app,"capabilityWorkers");workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);app.features.close();}
    }
    @Test public void oneOriginalIdleDrainsMoreThanFortyIndexedTasksAndDeliversTheOldWaitingResult()throws Exception {
        app=(EngineApplication)RuntimeEnvironment.getApplication();assertTrue(app.features.module("assistant") instanceof AssistantModule);
        TaskStore tasks=app.tasks;db=(SQLiteDatabase)field(tasks,"db");ConversationRepository history=tasks.conversationRepository();
        // Let the real empty startup idle callback finish before any indexed fixture task exists.
        await(()->!idleWorkerPending(),"original startup idle worker did not finish");history.initializeEmpty();
        String conversation=UUID.randomUUID().toString();history.transaction(()->{history.saveConversation(new JSONObject().put("conversationId",conversation).put("title","Original mounted creating conversation").put("messages",new JSONArray()).put("updatedAt",System.currentTimeMillis()));return null;});
        String sourceTask=tasks.createCapabilityTask("Create mounted original schedule",conversation).getString("id");tasks.initializeExecutionScope(sourceTask,4);JSONObject source=tasks.startAttempt(sourceTask,"capabilities",4);
        tasks.endAttempt(sourceTask,source.getString("runId"),"completed","Original native proposal prepared",new JSONObject());
        JSONObject nativeOrigin=new JSONObject().put("ownerId","owner").put("spaceId","private").put("taskId",sourceTask).put("conversationId",conversation).put("runId",source.getString("runId")).put("epoch",source.getInt("coordinatorEpoch"));
        String target=tasks.createReminderTask(request(nativeOrigin,"Earliest original queued result"),"background").getString("id");List<String> later=new ArrayList<>();
        for(int i=0;i<42;i++){
            // Advance both the mounted Android looper clock and host wall time, so key order
            // is a fixture precondition rather than depending on random UUID ties.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2));Thread.sleep(2);
            later.add(tasks.createReminderTask(request(nativeOrigin,"Later queued original task "+i),"background").getString("id"));
        }
        JSONObject first=tasks.automationConversationDeliveryPage("",20),second=tasks.automationConversationDeliveryPage(first.getString("nextCursor"),20),third=tasks.automationConversationDeliveryPage(second.getString("nextCursor"),20);
        assertTrue(first.getBoolean("hasMore"));assertTrue(second.getBoolean("hasMore"));assertFalse(contains(first.getJSONArray("taskIds"),target));assertFalse(contains(second.getJSONArray("taskIds"),target));assertTrue("waiting target must be beyond the first40 index entries",contains(third.getJSONArray("taskIds"),target));
        tasks.initializeExecutionScope(target,4);JSONObject execution=tasks.startAttempt(target,"background",4);String run=execution.getString("runId");int epoch=execution.getInt("coordinatorEpoch");String content="Actual mounted original scheduled result";
        tasks.endAttempt(target,run,"completed",content,new JSONObject());JSONObject selected=tasks.claimAutomationConversationDelivery(target);String delivery=selected.getString("deliveryId");assertTrue(selected.getBoolean("dispatchAllowed"));
        tasks.recordAutomationConversationDelivery(selected,new JSONObject().put("accepted",false).put("waitingForOriginalConversation",true).put("deliveryId",delivery));
        assertEquals("waiting_for_original_conversation",tasks.get(target,false).getJSONObject("automationConversationDelivery").getString("phase"));assertNull(history.read(()->history.sourceByReply(delivery)));
        long taskCount=count("tasks"),runCount=count("runs"),conversationCount=count("assistant_conversations");String originalGrant=grant(run);
        Method originalIdle=EngineApplication.class.getDeclaredMethod("queueScheduledConversationIdle");originalIdle.setAccessible(true);originalIdle.invoke(app); // Exactly one original idle trigger; no second claim/publish in the test.
        await(()->history.read(()->history.sourceByReply(delivery))!=null&&!idleWorkerPending()&&"delivered".equals(tasks.get(target,false).getJSONObject("automationConversationDelivery").optString("phase")),"single original idle did not deliver the waiting result beyond40 indexed tasks");
        JSONObject published=history.read(()->history.sourceByReply(delivery));assertEquals(target,published.getString("nativeTaskId"));assertEquals(run,published.getString("nativeRunId"));assertEquals(epoch,published.getInt("nativeEpoch"));assertEquals(conversation,published.getString("conversationId"));assertFalse(published.getBoolean("deleted"));
        JSONObject message=history.read(()->history.message(conversation,published.getString("sourceId")));assertNotNull(message);assertEquals(content,message.getString("content"));assertEquals(delivery,message.getString("nativeDeliveryId"));
        try(var rows=db.rawQuery("SELECT count(*) FROM assistant_sources WHERE native_reply_id=?",new String[]{delivery})){assertTrue(rows.moveToFirst());assertEquals(1,rows.getInt(0));}
        assertEquals(taskCount,count("tasks"));assertEquals(runCount,count("runs"));assertEquals(conversationCount,count("assistant_conversations"));assertEquals(originalGrant,grant(run));assertEquals(run,tasks.get(target,false).getString("runId"));
        for(String id:later)assertTrue("idle publication must not start queued task execution",tasks.get(id,false).optString("runId").isEmpty());
    }
}
