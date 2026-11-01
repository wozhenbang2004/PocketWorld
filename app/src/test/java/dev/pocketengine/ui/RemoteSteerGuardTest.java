package dev.pocketengine.ui;
import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteSteerGuardTest {
    private JSONObject task()throws Exception{return new JSONObject().put("id","task-1").put("runId","run-1").put("coordinatorEpoch",1).put("actorId","owner").put("spaceId","private").put("feature","remote").put("status","running").put("operationId","job-op").put("externalId","job-1").put("externalRefs",new JSONObject().put("profileId","computer")).put("evidence",new JSONObject().put("protocol","codex_app_server").put("profileId","computer").put("operationId","job-op").put("threadId","thread-1").put("turnId","turn-1").put("executorStatus","running").put("controls",new JSONObject().put("inputSteering",true))).put("operations",new JSONArray());}
    @Test public void requestIsBoundToReviewedTaskRunThreadAndTurn()throws Exception{JSONObject task=task(),target=RemoteSteerGuard.target(task);assertTrue(RemoteSteerGuard.canSend(task,target));JSONObject payload=RemoteSteerGuard.payload(task,target,"message-1","补充");assertEquals("turn-1",payload.getString("expectedTurnId"));assertEquals("job-op",payload.getString("jobOperationId"));task.getJSONObject("evidence").put("turnId","new-turn");assertFalse(RemoteSteerGuard.canSend(task,target));}
    @Test public void pendingOrUnknownMessageCannotOfferAnotherSendButAcceptedCan()throws Exception{JSONObject task=task(),target=RemoteSteerGuard.target(task);JSONObject op=new JSONObject().put("tool","remote_message").put("state","message_unknown");task.getJSONArray("operations").put(op);assertFalse(RemoteSteerGuard.canSend(task,target));op.put("state","message_receipt");assertTrue(RemoteSteerGuard.canSend(task,target));assertFalse(RemoteSteerGuard.statusLabel("accepted").contains("完成"));}
    @Test public void cancelUnsupportedCliAndStaleEpochAreNotSteerable()throws Exception{JSONObject task=task(),target=RemoteSteerGuard.target(task);task.put("cancelRequested",true);assertFalse(RemoteSteerGuard.canSend(task,target));task.put("cancelRequested",false).put("coordinatorEpoch",2);assertFalse(RemoteSteerGuard.canSend(task,target));task.getJSONObject("evidence").put("protocol","claude");assertNull(RemoteSteerGuard.target(task));}
    @Test public void arbitraryProfileAndEmptyTextCannotBecomeValidTargets()throws Exception{JSONObject task=task(),target=RemoteSteerGuard.target(task);task.getJSONObject("externalRefs").put("profileId","other");assertNull(RemoteSteerGuard.target(task));try{RemoteSteerGuard.payload(task(),target,"message-1","   ");fail();}catch(IllegalArgumentException expected){}}
    @Test public void onlyExactUnregisteredNativePreflightRejectionReleasesDraft()throws Exception{
        JSONObject response=new JSONObject().put("messageId","message-1").put("status","rejected").put("dispatched",false).put("receiptSource","native_preflight"),input=new JSONObject().put("taskId","task-1").put("messageId","message-1");
        assertTrue(RemoteSteerGuard.preflightRejected("task-1","message-1",false,response,input));
        assertFalse(RemoteSteerGuard.preflightRejected("task-2","message-1",false,response,input));assertFalse(RemoteSteerGuard.preflightRejected("task-1","message-2",false,response,input));
        assertFalse(RemoteSteerGuard.preflightRejected("task-1","message-1",true,response,input));response.put("status","unknown");assertFalse(RemoteSteerGuard.preflightRejected("task-1","message-1",false,response,input));
    }
}
