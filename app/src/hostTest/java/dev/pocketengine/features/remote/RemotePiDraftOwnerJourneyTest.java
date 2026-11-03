package dev.pocketengine.features.remote;

import android.app.Application;
import dev.pocketengine.features.RemoteOwnerWorkflows;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

/** Original owner/SQL/HTTP/Companion/Popen, synthetic pinned no-tools Pi only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class RemotePiDraftOwnerJourneyTest {
    // The existing fixture injects its explicitly synthetic HTTP credential provider.
    // Like the original Codex/Claude queue fixture, also configure native presence;
    // this is not a device Keystore/encryption or real account credential claim.
    private static void configured(RemotePiOwnerJourneyTest.Fixture f)throws Exception {
        var app=RuntimeEnvironment.getApplication();var slot=RemoteModule.class.getDeclaredMethod("slot",String.class,String.class);slot.setAccessible(true);
        assertTrue(app.getSharedPreferences("feature_secrets",android.content.Context.MODE_PRIVATE).edit().putString((String)slot.invoke(null,"computer",f.base),"explicit-fixture-presence-not-a-ciphertext").commit());
    }
    static JSONObject running(RemotePiOwnerJourneyTest.Fixture f)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until){JSONObject source=f.source();if(!source.optString("jobId").isEmpty())f.remote.handle("getJob",source);source=f.source();if("running".equals(source.optString("executorStatus"))&&source.optBoolean("protocolVerified")&&source.optBoolean("noTools")&&!source.optString("threadId").isEmpty()&&source.optString("threadId").equals(source.optString("sessionId"))&&!source.optString("promptRequestId").isEmpty())return source;Thread.sleep(10);}
        throw new AssertionError(f.source().toString());
    }
    static String queue(RemotePiOwnerJourneyTest.Fixture f,JSONObject view)throws Exception {
        String id=UUID.randomUUID().toString();f.owner.handle(f.nativeOwner,"saveDraft",f.action(view).put("draftId",id).put("draftRevision",0).put("mode","next_turn").put("text","One explicit next-turn owner requirement").put("queued",true));return id;
    }
    static void send(RemotePiOwnerJourneyTest.Fixture f,JSONObject view,String draft)throws Exception {
        f.owner.handle(f.nativeOwner,"sendDraft",f.action(view).put("draftId",draft).put("draftRevision",1));
    }
    @Test public void runningPiDraftSurvivesOwnerReopenAndOnlyExplicitSettledDispatchConsumesOneOriginalEpoch()throws Exception {
        try(RemotePiOwnerJourneyTest.Fixture f=new RemotePiOwnerJourneyTest.Fixture("wait-success",false)) {
            configured(f);JSONObject initial=running(f),view=f.open();String run=initial.getString("runId"),session=initial.getString("sessionId"),draft=queue(f,view);int epoch=initial.getInt("coordinatorEpoch");
            assertEquals(1,f.count("submit-count"));assertEquals("1",RemotePiOwnerJourneyTest.utf8(f.root.resolve("workspace/pi-prompt-count")));
            // A read-only second SQLite connection proves durable storage without
            // running cold-recovery over the still-live original native TaskStore.
            try(var persisted=android.database.sqlite.SQLiteDatabase.openDatabase(RuntimeEnvironment.getApplication().getDatabasePath("pocket-world.db").getAbsolutePath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);var row=persisted.rawQuery("SELECT document FROM tasks WHERE task_id=?",new String[]{f.task})){assertTrue(row.moveToFirst());assertEquals(draft,new JSONObject(row.getString(0)).getJSONArray("remoteInputDrafts").getJSONObject(0).getString("id"));}
            f.owner.close(view.getString("windowId"));f.owner.setForeground(f.nativeOwner,false);f.owner=new RemoteOwnerWorkflows(f.host,f.tasks,e->{});f.owner.setForeground(f.nativeOwner,true);JSONObject active=f.open();
            assertEquals("queued",active.getJSONArray("inputDrafts").getJSONObject(0).getString("state"));assertFalse(active.getBoolean("canContinue"));assertThrows(SecurityException.class,()->send(f,active,draft));assertEquals(run,f.source().getString("runId"));assertEquals(1,f.count("submit-count"));assertEquals("queued",f.tasks.originalRemoteDrafts(f.task).getJSONObject(0).getString("state"));assertEquals(1,f.tasks.originalRemoteDrafts(f.task).getJSONObject(0).getLong("revision"));
            Files.write(f.root.resolve("workspace/release-pi-turn"),"controlled synthetic native settlement".getBytes(StandardCharsets.UTF_8));f.settled("completed");assertEquals(1,f.count("submit-count"));
            JSONObject ready=f.open();assertTrue(ready.toString(),ready.getBoolean("canContinue"));send(f,ready,draft);f.settled("completed");JSONObject next=f.source();
            assertEquals(f.task,next.getString("taskId"));assertNotEquals(run,next.getString("runId"));assertEquals(epoch+1,next.getInt("coordinatorEpoch"));assertEquals(session,next.getString("sessionId"));assertEquals(session,next.getString("threadId"));assertTrue(next.getBoolean("noTools"));assertTrue(next.getBoolean("nativeSettled"));assertFalse(next.getBoolean("resourcesHeld"));assertTrue(next.getJSONObject("processCleanupProof").getBoolean("processGroupStoppedVerified"));
            assertEquals(2,f.count("submit-count"));assertEquals("11",RemotePiOwnerJourneyTest.utf8(f.root.resolve("workspace/pi-prompt-count")));assertEquals("dispatched",f.tasks.originalRemoteDrafts(f.task).getJSONObject(0).getString("state"));assertEquals(1,f.tasks.list().length());
            assertThrows(SecurityException.class,()->send(f,ready,draft));JSONObject newReview=f.open();assertThrows(SecurityException.class,()->send(f,newReview,draft));assertEquals(2,f.count("submit-count"));assertEquals(0,f.count("approval-count"));assertEquals(0,f.count("question-count"));
        }
    }
    @Test public void foregroundProfileGenerationAndWithdrawalStayInTheOriginalOwnerCas()throws Exception {
        try(RemotePiOwnerJourneyTest.Fixture f=new RemotePiOwnerJourneyTest.Fixture("success")) {
            configured(f);JSONObject first=f.open();String draft=queue(f,first);JSONObject view=f.open();f.owner.setForeground(f.nativeOwner,false);assertThrows(SecurityException.class,()->send(f,view,draft));assertEquals(1,f.count("submit-count"));
            f.owner.setForeground(f.nativeOwner,true);JSONObject old=f.open();f.remote.handle("saveProfile",new JSONObject().put("id","computer").put("name","explicit new native generation").put("baseUrl",f.base));assertThrows(SecurityException.class,()->send(f,old,draft));
            f.owner.close(first.getString("windowId"));JSONObject fresh=f.open();assertThrows(SecurityException.class,()->send(f,fresh,draft));
            f.owner.handle(f.nativeOwner,"withdrawDraft",f.action(fresh).put("draftId",draft).put("draftRevision",1));assertEquals("withdrawn",f.tasks.originalRemoteDrafts(f.task).getJSONObject(0).getString("state"));assertEquals(1,f.count("submit-count"));
        }
    }
    @Test public void unconfirmedQueueOrForgedSourceCannotBecomeALiveMessage()throws Exception {
        try(RemotePiOwnerJourneyTest.Fixture f=new RemotePiOwnerJourneyTest.Fixture("wait",false)) {
            configured(f);running(f);JSONObject view=f.open(),source=view.getJSONObject("source");String binding=view.getString("profileBindingHash");
            for(JSONObject bad:new JSONObject[]{new JSONObject(source.toString()).put("inputSteeringVerified",false),new JSONObject(source.toString()).put("turnId","invented"),new JSONObject(source.toString()).put("sessionId","another"),new JSONObject(source.toString()).put("promptRequestId","")})assertThrows(SecurityException.class,()->f.tasks.saveRemoteDraft(bad,binding,UUID.randomUUID().toString(),0,"steer","not an authority",true));
            for(JSONObject bad:new JSONObject[]{new JSONObject(source.toString()).put("noTools",false),new JSONObject(source.toString()).put("sessionId","another"),new JSONObject(source.toString()).put("promptRequestId","")})assertThrows(SecurityException.class,()->f.tasks.saveRemoteDraft(bad,binding,UUID.randomUUID().toString(),0,"next_turn","not an authority",true));
            assertEquals(0,f.tasks.originalRemoteDrafts(f.task).length());assertEquals(1,f.count("submit-count"));
        }
    }
    private static String queueLive(RemotePiOwnerJourneyTest.Fixture f,JSONObject view)throws Exception {
        String id=UUID.randomUUID().toString();f.owner.handle(f.nativeOwner,"saveDraft",f.action(view).put("draftId",id).put("draftRevision",0).put("mode","steer").put("text","One explicit original Pi queue instruction").put("queued",true));return id;
    }
    private static JSONObject liveQueue(RemotePiOwnerJourneyTest.Fixture f)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until){JSONObject source=f.source();if(!source.optString("jobId").isEmpty())f.remote.handle("getJob",source);source=f.source();JSONObject controls=source.optJSONObject("controls");if("running".equals(source.optString("executorStatus"))&&source.optBoolean("inputSteeringVerified")&&controls!=null&&controls.optBoolean("inputSteering"))return source;Thread.sleep(10);}
        throw new AssertionError("Original prompt ACK and agent_start did not enable its queue: "+f.source());
    }
    private static JSONObject originalMessageRequest(RemotePiOwnerJourneyTest.Fixture f,String command,JSONObject input)throws Exception {
        String request=UUID.randomUUID().toString();f.host.request(new JSONObject().put("feature","remote").put("command",command).put("requestId",request).put("payload",input));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until){for(JSONObject event:f.events)if(request.equals(event.optString("requestId"))){assertTrue(event.toString(),event.getBoolean("ok"));return event.getJSONObject("result");}Thread.sleep(10);}
        throw new AssertionError("Original message host returned no receipt");
    }
    private static JSONObject queryOriginalMessage(RemotePiOwnerJourneyTest.Fixture f,String message,String status)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);JSONObject result=new JSONObject();
        while(System.nanoTime()<until){result=originalMessageRequest(f,"getSteerMessage",new JSONObject().put("taskId",f.task).put("messageId",message));if(status.equals(result.optString("status")))return result;Thread.sleep(10);}
        throw new AssertionError(result.toString());
    }
    @Test public void explicitPiQueueDraftUsesOneOriginalJobPromptAndReceiptWithoutATurn()throws Exception {
        try(RemotePiOwnerJourneyTest.Fixture f=new RemotePiOwnerJourneyTest.Fixture("steer-live",false)) {
            configured(f);JSONObject initial=liveQueue(f),view=f.open();assertTrue(initial.getBoolean("inputSteeringVerified"));assertFalse(initial.has("turnId"));
            String run=initial.getString("runId"),job=initial.getString("jobId"),prompt=initial.getString("promptRequestId"),draft=queueLive(f,view);int epoch=initial.getInt("coordinatorEpoch");
            assertEquals(0,f.count("steer-count"));assertFalse(Files.exists(f.root.resolve("workspace/pi-steer-count")));assertEquals(1,f.count("submit-count"));
            JSONObject saved=f.tasks.originalRemoteDrafts(f.task).getJSONObject(0);assertEquals("queued",saved.getString("state"));assertEquals(prompt,saved.getJSONObject("scope").getString("expectedPromptRequestId"));assertFalse(saved.getJSONObject("scope").has("expectedTurnId"));
            send(f,f.open(),draft);String message=f.tasks.originalRemoteDrafts(f.task).getJSONObject(0).getString("operationId");JSONObject delivered=queryOriginalMessage(f,message,"accepted"),receipt=delivered.getJSONObject("receipt");
            assertEquals("pi_rpc_v1",receipt.getString("protocol"));assertEquals(prompt,receipt.getString("expectedPromptRequestId"));assertEquals(initial.getString("sessionId"),receipt.getString("sessionId"));assertEquals("native_queue",receipt.getString("delivery"));assertEquals("queued",receipt.getString("disposition"));assertEquals("unknown",receipt.getString("consumed"));assertFalse(receipt.has("turnId"));assertFalse(receipt.has("expectedTurnId"));
            f.settled("completed");JSONObject actual=f.source();assertEquals(run,actual.getString("runId"));assertEquals(job,actual.getString("jobId"));assertEquals(epoch,actual.getInt("coordinatorEpoch"));assertEquals(prompt,actual.getString("promptRequestId"));assertTrue(actual.getBoolean("nativeQueueEmptyConfirmed"));assertTrue(actual.getBoolean("nativeSettled"));assertFalse(actual.getBoolean("resourcesHeld"));
            assertEquals(1,f.count("submit-count"));assertEquals(1,f.count("steer-count"));assertEquals("1",RemotePiOwnerJourneyTest.utf8(f.root.resolve("workspace/pi-prompt-count")));assertEquals("1",RemotePiOwnerJourneyTest.utf8(f.root.resolve("workspace/pi-steer-count")));assertEquals(1,f.tasks.list().length());
            JSONObject duplicate=new JSONObject(delivered.toString()).put("text","One explicit original Pi queue instruction");assertEquals("accepted",originalMessageRequest(f,"steerJob",duplicate).getString("status"));assertEquals(1,f.count("steer-count"));assertThrows(SecurityException.class,()->f.tasks.claimRemoteMessageDispatch(f.task,message));
            assertThrows(SecurityException.class,()->send(f,f.open(),draft));assertEquals(1,f.count("submit-count"));
        }
    }
    @Test public void unknownPiQueueReceiptRemainsOneOriginalOperationAndBlocksAnotherDispatch()throws Exception {
        try(RemotePiOwnerJourneyTest.Fixture f=new RemotePiOwnerJourneyTest.Fixture("steer-missing-ack",false)) {
            configured(f);JSONObject initial=liveQueue(f);String draft=queueLive(f,f.open());send(f,f.open(),draft);String message=f.tasks.originalRemoteDrafts(f.task).getJSONObject(0).getString("operationId");
            Files.write(f.root.resolve("expire-pi-steer-ack"),"explicit fixture clock boundary".getBytes(StandardCharsets.UTF_8));f.settled("unknown");JSONObject receipt=queryOriginalMessage(f,message,"unknown");assertEquals("message_unknown",receipt.getString("journalState"));assertFalse(receipt.getJSONObject("receipt").has("turnId"));
            JSONObject view=f.open();assertFalse(view.getBoolean("canContinue"));assertThrows(SecurityException.class,()->send(f,view,draft));assertThrows(SecurityException.class,()->f.tasks.prepareRemoteContinuation(f.source(),"unknown is not a new prompt grant"));
            queryOriginalMessage(f,message,"unknown");assertEquals(initial.getString("runId"),f.source().getString("runId"));assertEquals(initial.getInt("coordinatorEpoch"),f.source().getInt("coordinatorEpoch"));assertEquals(1,f.count("submit-count"));assertEquals(1,f.count("steer-count"));assertEquals("1",RemotePiOwnerJourneyTest.utf8(f.root.resolve("workspace/pi-prompt-count")));assertEquals("1",RemotePiOwnerJourneyTest.utf8(f.root.resolve("workspace/pi-steer-count")));
        }
    }
    @Test public void originalStopStillBlocksDraftDispatchEvenAfterRealNativeSettlementAndCleanup()throws Exception {
        try(RemotePiOwnerJourneyTest.Fixture f=new RemotePiOwnerJourneyTest.Fixture("wait",false)) {
            configured(f);JSONObject source=running(f),view=f.open();String draft=queue(f,view),run=source.getString("runId");f.tasks.requestCancel(f.task,run);f.remote.handle("cancelJob",source);f.settled("cancelled");
            JSONObject stopped=f.open();assertFalse(stopped.getBoolean("canContinue"));assertTrue(stopped.getJSONObject("source").getBoolean("nativeSettled"));assertTrue(stopped.getJSONObject("source").getJSONObject("processCleanupProof").getBoolean("processGroupStoppedVerified"));assertThrows(SecurityException.class,()->send(f,stopped,draft));
            assertTrue(f.tasks.get(f.task,false).getBoolean("cancelRequested"));assertEquals("cancelled",f.tasks.get(f.task,false).getString("status"));assertEquals(run,f.source().getString("runId"));assertEquals(source.getInt("coordinatorEpoch"),f.source().getInt("coordinatorEpoch"));assertEquals(1,f.count("submit-count"));assertEquals("queued",f.tasks.originalRemoteDrafts(f.task).getJSONObject(0).getString("state"));
        }
    }
}
