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
    @Test public void noToolsSourceCannotBecomeALiveMessageOrForgeAnotherSession()throws Exception {
        try(RemotePiOwnerJourneyTest.Fixture f=new RemotePiOwnerJourneyTest.Fixture("wait",false)) {
            configured(f);running(f);JSONObject view=f.open(),source=view.getJSONObject("source");String binding=view.getString("profileBindingHash");
            assertThrows(SecurityException.class,()->f.owner.handle(f.nativeOwner,"saveDraft",f.action(view).put("draftId",UUID.randomUUID().toString()).put("draftRevision",0).put("mode","steer").put("text","no unsupported live input").put("queued",true)));
            for(JSONObject bad:new JSONObject[]{new JSONObject(source.toString()).put("noTools",false),new JSONObject(source.toString()).put("sessionId","another"),new JSONObject(source.toString()).put("promptRequestId","")})assertThrows(SecurityException.class,()->f.tasks.saveRemoteDraft(bad,binding,UUID.randomUUID().toString(),0,"next_turn","not an authority",true));
            assertEquals(0,f.tasks.originalRemoteDrafts(f.task).length());assertEquals(1,f.count("submit-count"));
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
