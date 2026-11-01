package dev.pocketengine.store;

import android.app.Application;
import android.content.Context;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
public final class RemoteMessageJournalTest {
    private TaskStore store(){return new TaskStore(RuntimeEnvironment.getApplication());}
    private String task(TaskStore tasks)throws Exception{
        String id=UUID.randomUUID().toString();tasks.beginExternal("remote","submitJob",id,new JSONObject().put("profileId","computer").put("environmentId","workspace").put("prompt","Inspect"));
        tasks.updateExternal(id,job(id,"running"));return id;
    }
    private JSONObject job(String id,String status)throws Exception{return new JSONObject().put("id","job-"+id).put("operationId",id).put("profileId","computer").put("threadId","thread-1").put("turnId","turn-1").put("protocol","codex_app_server").put("status",status).put("controls",new JSONObject().put("inputSteering",status.equals("running")||status.equals("awaiting_approval")));}
    private JSONObject input(String id)throws Exception{return new JSONObject().put("taskId",id).put("runId",id).put("coordinatorEpoch",0).put("profileId","computer").put("jobId","job-"+id).put("jobOperationId",id).put("threadId","thread-1").put("expectedTurnId","turn-1").put("messageId",UUID.randomUUID().toString()).put("text","Private owner clarification");}
    private JSONObject receipt(JSONObject input,String status)throws Exception{JSONObject receipt=new JSONObject(input.toString());receipt.remove("text");return receipt.put("operationId",input.getString("messageId")).put("status",status).put("turnId","turn-1");}
    private void refused(Checked action)throws Exception{try{action.run();fail("Unsafe message must be rejected");}catch(SecurityException|IllegalStateException|IllegalArgumentException expected){}}
    private interface Checked{void run()throws Exception;}

    @Test public void acceptedMessageIsSeparateFromJobAndOnlyStoresContentFingerprint()throws Exception{
        TaskStore tasks=store();String id=task(tasks);JSONObject input=input(id);
        assertFalse(tasks.prepareRemoteMessage(input).getBoolean("knownMessage"));tasks.claimRemoteMessageDispatch(id,input.getString("messageId"));
        assertTrue(tasks.prepareRemoteMessage(input).getBoolean("knownMessage"));
        tasks.recordRemoteMessage(receipt(input,"accepted"));
        assertEquals("running",tasks.get(id,false).getString("status"));assertFalse(tasks.hasUnresolved(id));
        assertEquals("accepted",tasks.remoteMessage(id,input.getString("messageId")).getString("status"));
        assertFalse(tasks.get(id,false).toString().contains("Private owner clarification"));
        JSONObject op=tasks.get(id,false).getJSONArray("operations").getJSONObject(0);assertEquals("message_receipt",op.getString("state"));assertTrue(op.getJSONObject("arguments").getString("textHash").matches("[a-f0-9]{64}"));
        refused(()->tasks.claimRemoteMessageDispatch(id,input.getString("messageId")));
    }
    @Test public void duplicateCannotChangeTextTargetRunOrEpoch()throws Exception{
        TaskStore tasks=store();String id=task(tasks);JSONObject input=input(id);tasks.prepareRemoteMessage(input);
        for(String key:new String[]{"text","profileId","jobId","threadId","expectedTurnId","runId","jobOperationId"})refused(()->tasks.prepareRemoteMessage(new JSONObject(input.toString()).put(key,"other")));
        refused(()->tasks.prepareRemoteMessage(new JSONObject(input.toString()).put("coordinatorEpoch",1)));
        String other=task(tasks);refused(()->tasks.remoteMessage(other,input.getString("messageId")));
        assertEquals(1,tasks.get(id,false).getJSONArray("operations").length());
    }
    @Test public void staleReviewOrUnverifiedCapabilityNeverReservesMessage()throws Exception{
        TaskStore tasks=store();String id=task(tasks);JSONObject input=input(id);
        for(String key:new String[]{"profileId","jobId","threadId","expectedTurnId","runId","jobOperationId"})refused(()->tasks.prepareRemoteMessage(new JSONObject(input.toString()).put(key,"stale")));
        refused(()->tasks.prepareRemoteMessage(new JSONObject(input.toString()).put("coordinatorEpoch",2)));
        tasks.updateExternal(id,job(id,"running").put("controls",new JSONObject().put("inputSteering",false)));refused(()->tasks.prepareRemoteMessage(input));
        assertEquals(0,tasks.get(id,false).getJSONArray("operations").length());
    }
    @Test public void cancellationAfterPreparePreventsClaimButKeepsOriginalQuery()throws Exception{
        TaskStore tasks=store();String id=task(tasks);JSONObject input=input(id);tasks.prepareRemoteMessage(input);tasks.status(id,"cancelled","Stopped");
        refused(()->tasks.claimRemoteMessageDispatch(id,input.getString("messageId")));
        assertTrue(tasks.prepareRemoteMessage(input).getBoolean("knownMessage"));assertEquals("job-"+id,tasks.remoteMessage(id,input.getString("messageId")).getString("jobId"));
        assertEquals("cancelled",tasks.get(id,false).getString("status"));
    }
    @Test public void unknownMessageDoesNotPoisonRealJobCompletionOrLateAcknowledgement()throws Exception{
        TaskStore tasks=store();String id=task(tasks);JSONObject input=input(id);tasks.prepareRemoteMessage(input);tasks.claimRemoteMessageDispatch(id,input.getString("messageId"));tasks.recordRemoteMessage(receipt(input,"unknown"));
        assertFalse(tasks.hasUnresolved(id));refused(()->tasks.prepareRemoteMessage(input(id)));
        tasks.updateExternal(id,job(id,"completed"));assertEquals("needs_confirmation",tasks.get(id,false).getString("status"));
        tasks.recordRemoteMessage(receipt(input,"accepted"));assertEquals("needs_confirmation",tasks.get(id,false).getString("status"));
        assertEquals("accepted",tasks.remoteMessage(id,input.getString("messageId")).getString("status"));
    }
    @Test public void processRestartMakesUnsentIntentQueryOnlyWithoutReplay()throws Exception{
        Context context=RuntimeEnvironment.getApplication();TaskStore tasks=new TaskStore(context);String id=task(tasks);JSONObject input=input(id);tasks.prepareRemoteMessage(input);
        TaskStore recovered=new TaskStore(context);JSONObject message=recovered.remoteMessage(id,input.getString("messageId"));assertEquals("unknown",message.getString("status"));
        assertTrue(recovered.prepareRemoteMessage(input).getBoolean("knownMessage"));refused(()->recovered.claimRemoteMessageDispatch(id,input.getString("messageId")));
        assertFalse(recovered.hasUnresolved(id));assertEquals("reconciling",recovered.get(id,false).getString("status"));
    }
    @Test public void staleOrCrossThreadReceiptsCannotOverwriteAcceptedMessage()throws Exception{
        TaskStore tasks=store();String id=task(tasks);JSONObject input=input(id);tasks.prepareRemoteMessage(input);tasks.claimRemoteMessageDispatch(id,input.getString("messageId"));
        for(String key:new String[]{"profileId","jobId","jobOperationId","threadId","expectedTurnId","operationId","turnId"})refused(()->tasks.recordRemoteMessage(receipt(input,"accepted").put(key,"other")));
        tasks.recordRemoteMessage(receipt(input,"accepted"));tasks.recordRemoteMessage(receipt(input,"queued"));tasks.recordRemoteMessage(receipt(input,"unknown"));
        assertEquals("accepted",tasks.remoteMessage(id,input.getString("messageId")).getString("status"));refused(()->tasks.recordRemoteMessage(receipt(input,"rejected")));
    }
    @Test public void concurrentPrepareHasOneDispatchAndNoSecondTask()throws Exception{
        TaskStore tasks=store();String id=task(tasks);JSONObject input=input(id);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<JSONObject> a=pool.submit(()->tasks.prepareRemoteMessage(new JSONObject(input.toString()))),b=pool.submit(()->tasks.prepareRemoteMessage(new JSONObject(input.toString())));
            assertNotEquals(a.get(2,TimeUnit.SECONDS).getBoolean("knownMessage"),b.get(2,TimeUnit.SECONDS).getBoolean("knownMessage"));
            assertEquals(1,tasks.get(id,false).getJSONArray("operations").length());assertEquals(1,tasks.list().length());
        }finally{pool.shutdownNow();}
    }
    @Test public void teamFeedbackCannotTargetPausedParentOrOldGeneration()throws Exception{
        TaskStore tasks=store();String parent=UUID.randomUUID().toString(),child=UUID.randomUUID().toString();tasks.beginExternal("teams","start",parent,new JSONObject().put("goal","Review"));
        tasks.updateExternal(parent,new JSONObject().put("status","running").put("generation",1));
        tasks.beginRelatedExternal(parent,parent,"remote","submitJob",child,new JSONObject().put("profileId","computer").put("environmentId","workspace").put("generation",1));tasks.updateExternal(child,job(child,"running"));
        JSONObject input=input(child);tasks.prepareRemoteMessage(input);tasks.status(parent,"paused","Paused");refused(()->tasks.claimRemoteMessageDispatch(child,input.getString("messageId")));
        tasks.status(parent,"running","Running");tasks.updateExternal(parent,new JSONObject().put("status","running").put("generation",2));refused(()->tasks.claimRemoteMessageDispatch(child,input.getString("messageId")));
        assertEquals("running",tasks.get(child,false).getString("status"));
    }
}
