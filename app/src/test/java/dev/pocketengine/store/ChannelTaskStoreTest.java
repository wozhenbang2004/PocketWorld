package dev.pocketengine.store;

import android.app.Application;
import dev.pocketengine.core.RunControl;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class ChannelTaskStoreTest {
    private JSONObject origin()throws Exception{return new JSONObject().put("kind","channel").put("requestId",UUID.randomUUID().toString()).put("eventId",UUID.randomUUID().toString()).put("profileId","profile").put("messageId",UUID.randomUUID().toString()).put("senderId","bound-owner").put("authenticatedBy","trusted_relay").put("bindingEpoch",UUID.randomUUID().toString()).put("taskGrantEpoch",UUID.randomUUID().toString()).put("ownerId","owner").put("spaceId","private");}
    @Test public void originIsImmutableAndDuplicateProviderMessageCannotCreateAnotherTask()throws Exception{
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());JSONObject origin=origin();String id=UUID.randomUUID().toString();
        JSONObject first=store.registerChannelTask(id,"原目标",origin);assertTrue(first.getBoolean("created"));assertEquals(1,first.getLong("channelStateRevision"));
        assertFalse(store.registerChannelTask(id,"原目标",origin).getBoolean("created"));assertEquals(1,store.get(id,false).getLong("channelStateRevision"));
        assertThrows(SecurityException.class,()->store.registerChannelTask(id,"更换目标",origin));
        assertThrows(SecurityException.class,()->store.registerChannelTask(UUID.randomUUID().toString(),"原目标",origin));
        JSONObject changed=new JSONObject(origin.toString()).put("requestId",UUID.randomUUID().toString());
        assertThrows(SecurityException.class,()->store.registerChannelTask(UUID.randomUUID().toString(),"原目标",changed));
        assertThrows(SecurityException.class,()->store.channelTask(id,new JSONObject(origin.toString()).put("senderId","other")));
        assertThrows(SecurityException.class,()->store.startAttempt(id,"main_screen",5));
        assertEquals(1,store.list().length());
    }
    @Test public void reservedRunIsReusedAndCannotBeReplacedByAnotherQueuedEntry()throws Exception{
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());String id=UUID.randomUUID().toString();store.registerChannelTask(id,"目标",origin());
        JSONObject run=store.startAttempt(id,"capabilities",5);RunControl control=new RunControl();control.bindHostRun(id,run.getString("runId"),run.getInt("coordinatorEpoch"));
        assertEquals(run.getString("runId"),store.requireCapabilityAttempt(id,control).getString("runId"));
        assertThrows(IllegalStateException.class,()->store.startAttempt(id,"capabilities",5));
        assertEquals(1,store.get(id,false).getInt("coordinatorEpoch"));
        store.endAttempt(id,run.getString("runId"),"awaiting_user","等待原参数审批",new JSONObject());
        assertThrows(SecurityException.class,()->store.requireCapabilityAttempt(id,control));
    }
    @Test public void delayedCancellationCannotStopANewerRunOrItsController()throws Exception{
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());String id=UUID.randomUUID().toString();JSONObject origin=origin();store.registerChannelTask(id,"目标",origin);
        JSONObject first=store.startAttempt(id,"capabilities",5);store.endAttempt(id,first.getString("runId"),"awaiting_user","等待",new JSONObject());JSONObject second=store.startAttempt(id,"capabilities",5);
        RunControl current=new RunControl();current.bindHostRun(id,second.getString("runId"),second.getInt("coordinatorEpoch"));
        assertThrows(SecurityException.class,()->store.cancelChannelTask(id,origin,first.getString("runId"),first.getInt("coordinatorEpoch"),current));
        assertFalse(current.isCancelled());assertFalse(store.get(id,false).getBoolean("cancelRequested"));
        store.cancelChannelTask(id,origin,second.getString("runId"),second.getInt("coordinatorEpoch"),current);assertTrue(current.isCancelled());
        assertThrows(SecurityException.class,()->store.requireCapabilityAttempt(id,current));
    }
    @Test public void restartPreservesUnknownEffectsAndMonotoneReceiptRevision()throws Exception{
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());String id=UUID.randomUUID().toString();JSONObject origin=origin();store.registerChannelTask(id,"目标",origin);JSONObject run=store.startAttempt(id,"capabilities",5);
        RunControl control=new RunControl();control.bindHostRun(id,run.getString("runId"),run.getInt("coordinatorEpoch"));store.authorizeTools(id,run.getString("runId"),new JSONArray().put("workspace_write"));
        String operation=store.journal(id,run.getString("runId"),run.getInt("coordinatorEpoch"),control).beginOperation("workspace_write",new JSONObject());long before=store.get(id,false).getLong("channelStateRevision");
        TaskStore restored=new TaskStore(RuntimeEnvironment.getApplication());JSONObject recovered=restored.channelTask(id,origin);assertEquals("reconciling",recovered.getString("status"));assertTrue(recovered.getLong("channelStateRevision")>before);
        assertThrows(IllegalStateException.class,()->restored.startAttempt(id,"capabilities",5));
        restored.cancelChannelTask(id,origin,run.getString("runId"),run.getInt("coordinatorEpoch"),null);assertTrue(restored.hasUnresolved(id));
        restored.endAttempt(id,run.getString("runId"),"cancelled","停止不代表撤销",new JSONObject());assertEquals("reconciling",restored.get(id,false).getString("status"));
        restored.resolveOperation(id,operation,"not_occurred");assertEquals(2,restored.startAttempt(id,"capabilities",5).getInt("coordinatorEpoch"));
    }
    @Test public void lateSuccessAfterSerializedCancellationCannotBecomeACompletionReceipt()throws Exception{
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());String id=UUID.randomUUID().toString();JSONObject origin=origin();store.registerChannelTask(id,"目标",origin);JSONObject run=store.startAttempt(id,"capabilities",5);
        RunControl control=new RunControl();control.bindHostRun(id,run.getString("runId"),run.getInt("coordinatorEpoch"));
        store.cancelChannelTask(id,origin,run.getString("runId"),run.getInt("coordinatorEpoch"),control);
        store.event(id,new JSONObject().put("type","result").put("status","completed"));
        store.endAttempt(id,run.getString("runId"),"completed","迟到的模型成功",new JSONObject().put("modelSummary","迟到的模型成功"));
        assertEquals("cancelled",store.get(id,false).getString("status"));assertFalse(store.get(id,false).getString("summary").contains("迟到的模型成功"));
    }
    @Test public void oldWorkerEventCannotMutateResumedTaskAndOwnerCompletionRemainsFinal()throws Exception{
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());String id=UUID.randomUUID().toString();store.registerChannelTask(id,"目标",origin());JSONObject first=store.startAttempt(id,"capabilities",5);
        store.endAttempt(id,first.getString("runId"),"awaiting_user","等待",new JSONObject());JSONObject second=store.startAttempt(id,"capabilities",5);long revision=second.getLong("channelStateRevision");
        store.event(id,first.getString("runId"),first.getInt("coordinatorEpoch"),new JSONObject().put("type","result").put("status","completed"));
        assertEquals(revision,store.get(id,false).getLong("channelStateRevision"));assertEquals("preparing",store.get(id,false).getString("status"));
        store.status(id,"completed","主人已核对完成");store.endAttempt(id,second.getString("runId"),"failed","迟到失败",new JSONObject());
        assertEquals("completed",store.get(id,false).getString("status"));assertEquals("主人已核对完成",store.get(id,false).getString("summary"));
    }
    @Test public void cancellationIntentSurvivesProcessLossBeforeTheWorkerCanSettle()throws Exception{
        TaskStore store=new TaskStore(RuntimeEnvironment.getApplication());String id=UUID.randomUUID().toString();JSONObject origin=origin();store.registerChannelTask(id,"目标",origin);JSONObject run=store.startAttempt(id,"capabilities",5);
        RunControl control=new RunControl();control.bindHostRun(id,run.getString("runId"),run.getInt("coordinatorEpoch"));store.cancelChannelTask(id,origin,run.getString("runId"),run.getInt("coordinatorEpoch"),control);
        assertEquals("stopping",store.get(id,false).getString("status"));TaskStore restored=new TaskStore(RuntimeEnvironment.getApplication());assertEquals("cancelled",restored.get(id,false).getString("status"));assertTrue(restored.get(id,false).getBoolean("cancelRequested"));
        assertEquals(run.getString("runId"),restored.get(id,false).getString("runId"));assertEquals(1,restored.get(id,false).getInt("coordinatorEpoch"));
    }
}
