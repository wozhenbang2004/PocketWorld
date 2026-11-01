package dev.pocketengine.media;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Controlled transports establish native consent/lease invariants, not real camera, RTC or AI acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class RealtimeSessionModuleTest {
    private TaskStore ownedTasks;private RealtimeSessionModule module;private Context context;private ActivityController<Activity> owner;private MediaController media;
    private ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ControlledSession transport=new ControlledSession();private final Map<String,String> secrets=new HashMap<>();
    private void setup()throws Exception{
        File files=Files.createTempDirectory("pocket-realtime-").toFile();context=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};
        FeatureContext features=new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{});
        module=new RealtimeSessionModule(features,(android,events)->{transport.events=events;return transport;},new RealtimeSessionModule.Credentials(){public boolean contains(String slot){return secrets.containsKey(slot);}public String get(String slot){return secrets.getOrDefault(slot,"");}public void put(String slot,String value){secrets.put(slot,value);}public void remove(String slot){secrets.remove(slot);}});
        owner=Robolectric.buildActivity(Activity.class).create().start().resume();media=new MediaController(owner.get(),event->{});module.setForeground(owner.get(),media);
    }
    private JSONObject command(String name,JSONObject args)throws Exception{return module.handleOwner(owner.get(),name,args);}
    private void configure()throws Exception{command("configure",new JSONObject().put("serverUrl","wss://voice.example").put("participantToken","test-participant-token"));}
    private JSONObject invitation()throws Exception{configure();return command("prepareCall",new JSONObject()).getJSONObject("invitation");}
    private JSONObject selection(JSONObject invite,boolean mic,boolean camera)throws Exception{return new JSONObject().put("invitationId",invite.getString("id")).put("microphone",mic).put("camera",camera);}
    private JSONObject sessionArgs()throws Exception{return new JSONObject().put("sessionId",module.snapshot().getString("sessionId"));}
    private Future<JSONObject> start(JSONObject input){return worker.submit(()->command("acceptInvitation",input));}
    private void awaitStarted()throws Exception{awaitStarted(1);}
    private void awaitStarted(int count)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(transport.connects<count&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}assertEquals(count,transport.connects);}
    private JSONObject finish(Future<JSONObject> future)throws Exception{transport.complete();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!future.isDone()&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}return future.get(1,TimeUnit.SECONDS);}
    @After public void close(){if(module!=null)module.close();Shadows.shadowOf(Looper.getMainLooper()).idle();worker.shutdownNow();if(media!=null)media.close();if(owner!=null)owner.pause().stop().destroy();if(ownedTasks!=null)try{java.lang.reflect.Field db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(ownedTasks)).close();}catch(Exception ignored){}}

    @Test public void tokenNeverEntersSnapshotsOrConfigAndCannotFollowOriginChange()throws Exception{
        setup();configure();assertTrue(module.snapshot().getBoolean("configured"));assertFalse(module.snapshot().toString().contains("test-participant-token"));assertFalse(Files.readString(new File(context.getFilesDir(),"realtime-session.json").toPath(),StandardCharsets.UTF_8).contains("test-participant-token"));
        command("configure",new JSONObject().put("serverUrl","wss://voice.example:443/other"));assertTrue(module.snapshot().getBoolean("configured"));command("configure",new JSONObject().put("serverUrl","wss://other.example"));assertFalse(module.snapshot().getBoolean("configured"));assertTrue(secrets.isEmpty());
    }
    @Test public void genericPayloadCannotGrantNativeConsent()throws Exception{
        setup();assertThrows(SecurityException.class,()->module.handle("configure",new JSONObject().put("foregroundOwner",true)));assertThrows(SecurityException.class,()->module.handle("acceptInvitation",new JSONObject().put("foregroundOwner",true).put("microphone",true)));assertEquals(0,transport.connects);
    }
    @Test public void prepareAndRejectNeverAcquireCaptureOrConnect()throws Exception{
        setup();JSONObject invite=invitation();assertEquals("owner_prepared_room",invite.getString("source"));assertFalse(module.snapshot().getBoolean("audioLeaseHeld"));assertEquals(0,transport.connects);command("rejectInvitation",new JSONObject().put("invitationId",invite.getString("id")));assertEquals("rejected",module.snapshot().getString("invitationState"));assertThrows(IllegalStateException.class,()->command("acceptInvitation",selection(invite,false,false)));assertEquals(0,transport.connects);
    }
    @Test public void staleInvitationAndConfigurationAreNotAccepted()throws Exception{
        setup();JSONObject invite=invitation();command("configure",new JSONObject().put("serverUrl","wss://voice.example").put("participantToken","replacement"));assertThrows(IllegalStateException.class,()->command("acceptInvitation",selection(invite,false,false)));assertEquals(0,transport.connects);
    }
    @Test public void missingCameraPermissionDoesNotStartRoomOrMic()throws Exception{
        setup();JSONObject invite=invitation();Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);assertThrows(SecurityException.class,()->command("acceptInvitation",selection(invite,false,true)));assertEquals(0,transport.connects);assertFalse(module.snapshot().getBoolean("audioLeaseHeld"));
    }
    @Test public void explicitCameraSelectionIsAppliedOnlyAfterAcceptAndHangupKeepsTask()throws Exception{
        setup();JSONObject invite=invitation();Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA);TaskStore tasks=new TaskStore(context);ownedTasks=tasks;JSONObject task=tasks.create("already running task");String taskId=task.getString("id");tasks.startAttempt(taskId,"main_screen",8);String before=tasks.get(taskId,false).toString();
        Future<JSONObject> accepted=start(selection(invite,false,true));awaitStarted();assertFalse(transport.requestedMic);assertTrue(transport.requestedCamera);finish(accepted);assertTrue(module.snapshot().getJSONObject("session").getBoolean("cameraEnabled"));command("disconnect",sessionArgs());Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(media.state().getBoolean("realtimeOwnsAudio"));assertEquals(before,tasks.get(taskId,false).toString());
    }
    @Test public void backgroundFencesLateConnectionAndClosesMedia()throws Exception{
        setup();Future<JSONObject> accepted=start(selection(invitation(),false,false));awaitStarted();module.setForeground(null,null);transport.complete();assertThrows(java.util.concurrent.ExecutionException.class,()->accepted.get(2,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(module.snapshot().getBoolean("foregroundOwner"));assertFalse(transport.camera);assertNotEquals("connected",transport.state);assertTrue(transport.disconnections>0);
    }
    @Test public void revokedPermissionStopsCallInsteadOfContinuingCapture()throws Exception{
        setup();Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA);Future<JSONObject> accepted=start(selection(invitation(),false,true));awaitStarted();finish(accepted);Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);module.checkCapturePermissions();assertEquals("disconnected",transport.state);assertFalse(transport.camera);
    }
    @Test public void staleSessionCannotHangUpOrChangeNewCall()throws Exception{
        setup();Future<JSONObject> accepted=start(selection(invitation(),false,false));awaitStarted();finish(accepted);assertThrows(IllegalStateException.class,()->command("disconnect",new JSONObject().put("sessionId","old-call")));assertThrows(IllegalStateException.class,()->command("setCamera",new JSONObject().put("sessionId","old-call").put("enabled",true)));assertEquals("connected",transport.state);
    }
    @Test public void missingCoordinatorFailsClosed()throws Exception{
        setup();JSONObject invite=invitation();module.setForeground(owner.get(),null);assertThrows(IllegalStateException.class,()->command("acceptInvitation",selection(invite,false,false)));assertEquals(0,transport.connects);
    }
    @Test public void incomingInvitationMustComeFromInstalledProviderAndRejectIsReal()throws Exception{
        setup();Provider provider=new Provider();assertThrows(SecurityException.class,()->module.offerInvitation(provider,"inv","Remote service",System.currentTimeMillis()+10000));module.installProvider(provider);module.offerInvitation(provider,"inv","Remote service",System.currentTimeMillis()+10000);assertEquals(0,provider.accepts);assertEquals(0,transport.connects);command("rejectInvitation",new JSONObject().put("invitationId","inv"));assertEquals(1,provider.rejects);assertEquals(0,provider.accepts);
    }
    @Test public void expiredProviderInvitationCannotCapture()throws Exception{
        setup();Provider provider=new Provider();module.installProvider(provider);module.offerInvitation(provider,"expired","Remote",System.currentTimeMillis()+40);Thread.sleep(50);assertEquals("expired",module.snapshot().getString("invitationState"));assertThrows(IllegalStateException.class,()->command("acceptInvitation",new JSONObject().put("invitationId","expired").put("microphone",false).put("camera",false)));assertEquals(0,transport.connects);
    }
    @Test public void activityReplacementCannotInheritPendingCaptureConsent()throws Exception{
        setup();Future<JSONObject> accepted=start(selection(invitation(),false,false));awaitStarted();
        ActivityController<Activity> replacement=Robolectric.buildActivity(Activity.class).setup();MediaController replacementMedia=new MediaController(replacement.get(),event->{});
        try{module.setForeground(replacement.get(),replacementMedia);transport.complete();assertThrows(java.util.concurrent.ExecutionException.class,()->accepted.get(2,TimeUnit.SECONDS));assertNotEquals("connected",transport.state);assertThrows(SecurityException.class,()->command("prepareCall",new JSONObject()));}
        finally{replacementMedia.close();replacement.pause().stop().destroy();}
    }
    @Test public void oldActivityClearCannotRemoveReplacementOwner()throws Exception{
        setup();ActivityController<Activity> replacement=Robolectric.buildActivity(Activity.class).setup();MediaController nextMedia=new MediaController(replacement.get(),event->{});
        try{module.setForeground(replacement.get(),nextMedia);module.clearForeground(owner.get());assertTrue(module.snapshot().getBoolean("foregroundOwner"));assertTrue(module.handleOwner(replacement.get(),"snapshot",new JSONObject()).getBoolean("foregroundOwner"));module.clearForeground(replacement.get());assertFalse(module.snapshot().getBoolean("foregroundOwner"));}
        finally{nextMedia.close();replacement.pause().stop().destroy();}
    }
    @Test public void snapshotReadsAreStableButInvitationExpiryGetsNewRevision()throws Exception{
        setup();long initial=module.snapshot().getLong("snapshotRevision");assertEquals(initial,module.snapshot().getLong("snapshotRevision"));Provider provider=new Provider();module.installProvider(provider);module.offerInvitation(provider,"expiry","Remote",System.currentTimeMillis()+150);long pending=module.snapshot().getLong("snapshotRevision");assertTrue(pending>initial);assertEquals(pending,module.snapshot().getLong("snapshotRevision"));Thread.sleep(180);JSONObject expired=module.snapshot();assertEquals("expired",expired.getString("invitationState"));assertTrue(expired.getLong("snapshotRevision")>pending);assertEquals(expired.getLong("snapshotRevision"),module.snapshot().getLong("snapshotRevision"));
    }
    @Test public void plaintextRemoteAndCredentialUrlsAreRefused()throws Exception{
        assertThrows(IllegalArgumentException.class,()->RealtimeSessionModule.validateServer("ws://remote.example",true));assertThrows(IllegalArgumentException.class,()->RealtimeSessionModule.validateServer("wss://remote.example?token=secret",false));assertThrows(IllegalArgumentException.class,()->RealtimeSessionModule.validateServer("wss://u:p@remote.example",false));assertEquals("ws",RealtimeSessionModule.validateServer("ws://127.0.0.1:7880",true).getScheme());
    }
    private void connectForCaptions()throws Exception{Future<JSONObject> accepted=start(selection(invitation(),false,false));awaitStarted();finish(accepted);}
    private void installRealTasks()throws Exception{
        ownedTasks=new TaskStore(context);module.installTurnCommitter(new RealtimeTurnCommitter(){
            public JSONObject create(RealtimeReviewedTurn turn)throws Exception{return ownedTasks.createReviewedRealtimeTask(turn.commitId,turn.text,turn.source());}
            public JSONObject lookup(RealtimeReviewedTurn turn)throws Exception{return ownedTasks.reviewedRealtimeTask(turn.commitId,turn.text,turn.source());}
        });
    }
    private JSONObject caption(String segment,String text,boolean finalText,boolean local,long sequence)throws Exception{return new JSONObject().put("kind","transcription").put("protocol","legacy_event").put("phase","legacy").put("sequence",sequence).put("segmentId",segment).put("participantIdentity",local?"reported-local":"remote").put("trackSid",local?"local-mic":"remote-mic").put("text",text).put("isFinal",finalText).put("finalStream",finalText).put("reportedLocalMicrophone",local);}
    private JSONObject firstTurn()throws Exception{return module.snapshot().getJSONObject("transcription").getJSONArray("turns").getJSONObject(0);}
    private JSONObject reviewFirst()throws Exception{JSONObject row=firstTurn();return command("reviewTurn",sessionArgs().put("turnId",row.getString("id")).put("revision",row.getLong("revision")));}
    private JSONObject commitArgs(JSONObject review)throws Exception{return sessionArgs().put("reviewId",review.getString("reviewId")).put("contentHash",review.getString("contentHash"));}
    @Test public void actualModuleCaptionBarrierOnlyCreatesTaskAfterNativeExactFinalConfirmation()throws Exception{
        setup();installRealTasks();connectForCaptions();transport.events.emit(caption("one","删除",false,true,1));assertThrows(IllegalStateException.class,this::reviewFirst);assertEquals(0,ownedTasks.list().length());
        transport.events.emit(caption("one","不要删除，整理明天安排",true,true,2));JSONObject review=reviewFirst();assertEquals(0,ownedTasks.list().length());assertThrows(SecurityException.class,()->module.handle("commitTurn",commitArgs(review)));
        JSONObject result=command("commitTurn",commitArgs(review)),task=result.getJSONObject("task");assertEquals("awaiting_user",task.getString("status"));assertFalse(result.getBoolean("automaticallyExecutes"));assertEquals("不要删除，整理明天安排",task.getString("goal"));assertEquals("owner_reviewed_realtime",task.getJSONObject("origin").getString("kind"));assertEquals(0,task.getInt("coordinatorEpoch"));assertThrows(IllegalStateException.class,()->command("commitTurn",commitArgs(review)));assertEquals(1,ownedTasks.list().length());
        String before=ownedTasks.get(task.getString("id"),false).toString();command("disconnect",sessionArgs());assertEquals(before,ownedTasks.get(task.getString("id"),false).toString());
    }
    @Test public void remoteFinalAndNewerFinalRevisionCannotBypassOwnerBarrier()throws Exception{
        setup();installRealTasks();connectForCaptions();transport.events.emit(caption("remote","立刻转账",true,false,1));assertThrows(IllegalStateException.class,this::reviewFirst);assertEquals(0,ownedTasks.list().length());
        transport.events.emit(caption("local","旧目标",true,true,2));JSONObject row=module.snapshot().getJSONObject("transcription").getJSONArray("turns").getJSONObject(1);JSONObject review=command("reviewTurn",sessionArgs().put("turnId",row.getString("id")).put("revision",row.getLong("revision")));transport.events.emit(caption("local","修订后的新目标",true,true,3));assertThrows(IllegalStateException.class,()->command("commitTurn",commitArgs(review)));assertEquals(0,ownedTasks.list().length());
    }
    private void transportState(String state){transport.state=state;transport.events.emit(transport.snapshot());}
    private JSONObject streamCaption(String phase,long sequence,String text)throws Exception{return caption("stream",text,phase.equals("complete"),true,sequence).put("protocol","text_stream").put("phase",phase).put("finalStream",true);}
    @Test public void reconnectingDisablesReviewAndRequiresFreshReviewAfterRecovery()throws Exception{
        setup();installRealTasks();connectForCaptions();transport.events.emit(caption("one","整理明天安排",true,true,1));JSONObject before=firstTurn(),oldReview=reviewFirst();String sessionId=module.snapshot().getString("sessionId");
        transportState("reconnecting");JSONObject paused=firstTurn();assertFalse("网络恢复期间不能继续显示可复核",paused.getBoolean("canReview"));assertEquals(before.getString("text"),paused.getString("text"));assertTrue(paused.getLong("revision")>before.getLong("revision"));assertThrows(IllegalStateException.class,()->command("commitTurn",commitArgs(oldReview)));assertEquals(0,ownedTasks.list().length());
        transportState("connected");assertEquals(sessionId,module.snapshot().getString("sessionId"));assertTrue(firstTurn().getBoolean("canReview"));assertThrows(IllegalStateException.class,()->command("commitTurn",commitArgs(oldReview)));JSONObject saved=command("commitTurn",commitArgs(reviewFirst())).getJSONObject("task");assertEquals("整理明天安排",saved.getString("goal"));assertEquals("awaiting_user",saved.getString("status"));assertEquals(1,ownedTasks.list().length());
    }
    @Test public void unfinishedStreamCannotBecomeFinalAcrossReconnect()throws Exception{
        setup();installRealTasks();connectForCaptions();transport.events.emit(streamCaption("begin",10,""));transport.events.emit(streamCaption("partial",10,"安排"));String turnId=firstTurn().getString("id");
        transportState("reconnecting");transport.events.emit(streamCaption("complete",10,"断线后迟到的旧流"));assertEquals("incomplete",firstTurn().getString("phase"));assertEquals("安排",firstTurn().getString("text"));assertFalse(firstTurn().getBoolean("canReview"));
        transportState("connected");transport.events.emit(streamCaption("complete",10,"恢复后迟到的旧流"));assertEquals("incomplete",firstTurn().getString("phase"));assertFalse(firstTurn().getBoolean("isFinal"));assertThrows(IllegalStateException.class,this::reviewFirst);
        transport.events.emit(streamCaption("begin",11,""));transport.events.emit(streamCaption("complete",11,"新的完整目标"));assertEquals(turnId,firstTurn().getString("id"));assertEquals("新的完整目标",reviewFirst().getString("text"));assertEquals(0,ownedTasks.list().length());
    }
    @Test public void backgroundReturnCanReviewRetainedFinalWithoutReconnectingOrReusingOldReview()throws Exception{
        setup();installRealTasks();connectForCaptions();transport.events.emit(caption("one","保留的最终目标",true,true,1));JSONObject before=firstTurn(),oldReview=reviewFirst();String session=sessionArgs().getString("sessionId");module.clearForeground(owner.get());assertFalse(firstTurn().getBoolean("canReview"));assertEquals("保留的最终目标",firstTurn().getString("text"));assertThrows(SecurityException.class,this::reviewFirst);module.setForeground(owner.get(),media);assertTrue(firstTurn().getBoolean("canReview"));assertThrows(IllegalStateException.class,()->command("commitTurn",commitArgs(oldReview)));assertEquals(0,ownedTasks.list().length());assertEquals(1,transport.connects);JSONObject task=command("commitTurn",commitArgs(reviewFirst())).getJSONObject("task");assertEquals(session,task.getJSONObject("origin").getString("sessionId"));assertEquals(before.getString("id"),task.getJSONObject("origin").getString("turnId"));assertEquals("awaiting_user",task.getString("status"));assertEquals(1,transport.connects);
    }
    @Test public void revokedPermissionStopsCaptureButKeepsFinalTextReviewable()throws Exception{
        setup();installRealTasks();Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);connectForCaptions();command("mute",sessionArgs().put("muted",false));transport.events.emit(caption("one","权限撤销前的最终文字",true,true,1));Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);module.checkCapturePermissions();assertFalse(transport.mic);assertEquals("disconnected",transport.state);assertTrue(firstTurn().getBoolean("canReview"));JSONObject task=command("commitTurn",commitArgs(reviewFirst())).getJSONObject("task");assertEquals("权限撤销前的最终文字",task.getString("goal"));assertEquals("awaiting_user",task.getString("status"));assertEquals(1,transport.connects);
    }
    @Test public void failedCleanupKeepsNewCaptureBlockedWhileRetainedFinalCanBeSaved()throws Exception{
        setup();installRealTasks();connectForCaptions();transport.events.emit(caption("one","释放未确认前的最终文字",true,true,1));transport.releaseUnknown=true;transportState("failed");assertTrue(module.snapshot().getBoolean("audioLeaseHeld"));assertTrue(firstTurn().getBoolean("canReview"));assertThrows(IllegalStateException.class,()->command("prepareCall",new JSONObject()));assertEquals(0,ownedTasks.list().length());JSONObject task=command("commitTurn",commitArgs(reviewFirst())).getJSONObject("task");assertEquals("释放未确认前的最终文字",task.getString("goal"));assertEquals("awaiting_user",task.getString("status"));assertEquals(1,transport.connects);
    }
    @Test public void uncertainSaveCanOnlyLookupSamePersistedTaskEvenAfterHangup()throws Exception{
        setup();ownedTasks=new TaskStore(context);int[] calls={0,0};module.installTurnCommitter(new RealtimeTurnCommitter(){public JSONObject create(RealtimeReviewedTurn turn)throws Exception{calls[0]++;ownedTasks.createReviewedRealtimeTask(turn.commitId,turn.text,turn.source());throw new java.io.IOException("synthetic lost reply");}public JSONObject lookup(RealtimeReviewedTurn turn)throws Exception{calls[1]++;return ownedTasks.reviewedRealtimeTask(turn.commitId,turn.text,turn.source());}});connectForCaptions();transport.events.emit(caption("one","安排任务",true,true,1));JSONObject review=reviewFirst();assertThrows(IllegalStateException.class,()->command("commitTurn",commitArgs(review)));String id=firstTurn().getString("commitId");command("disconnect",sessionArgs());JSONObject result=command("reconcileTurn",new JSONObject().put("commitId",id));assertEquals(id,result.getJSONObject("task").getString("id"));assertEquals(1,calls[0]);assertEquals(1,calls[1]);assertEquals(1,ownedTasks.list().length());
    }
    @Test public void hangupDuringAlreadyConfirmedPersistenceDoesNotCancelOrLoseTask()throws Exception{
        setup();ownedTasks=new TaskStore(context);java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);module.installTurnCommitter(new RealtimeTurnCommitter(){public JSONObject create(RealtimeReviewedTurn turn)throws Exception{entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");return ownedTasks.createReviewedRealtimeTask(turn.commitId,turn.text,turn.source());}public JSONObject lookup(RealtimeReviewedTurn turn)throws Exception{return ownedTasks.reviewedRealtimeTask(turn.commitId,turn.text,turn.source());}});connectForCaptions();transport.events.emit(caption("one","安排任务",true,true,1));JSONObject args=commitArgs(reviewFirst());Future<JSONObject> saved=worker.submit(()->command("commitTurn",args));assertTrue(entered.await(2,TimeUnit.SECONDS));command("disconnect",sessionArgs());release.countDown();JSONObject task=saved.get(2,TimeUnit.SECONDS).getJSONObject("task");assertEquals("awaiting_user",ownedTasks.get(task.getString("id"),false).getString("status"));assertEquals(task.getString("id"),module.snapshot().getJSONObject("transcription").getJSONObject("lastTask").getString("id"));
    }
    @Test public void endedAndOlderAdapterCaptionsCannotEnterNewSession()throws Exception{
        setup();installRealTasks();connectForCaptions();EventSink previous=transport.events;previous.emit(caption("one","旧目标",true,true,1));JSONObject oldReview=reviewFirst();command("disconnect",sessionArgs());previous.emit(caption("late","旧通话迟到",true,true,2));assertEquals(1,module.snapshot().getJSONObject("transcription").getJSONArray("turns").length());assertEquals("旧目标",firstTurn().getString("text"));assertTrue(firstTurn().getBoolean("canReview"));assertThrows(IllegalStateException.class,()->command("commitTurn",commitArgs(oldReview)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();Future<JSONObject> accepted=start(selection(command("prepareCall",new JSONObject()).getJSONObject("invitation"),false,false));awaitStarted(2);finish(accepted);previous.emit(caption("late","旧代次注入",true,true,3));assertEquals(0,module.snapshot().getJSONObject("transcription").getJSONArray("turns").length());assertThrows(IllegalStateException.class,()->command("commitTurn",commitArgs(oldReview)));transport.events.emit(caption("new","当前通话",true,true,4));assertEquals("当前通话",firstTurn().getString("text"));assertEquals(0,ownedTasks.list().length());
    }
    private EndpointFixture endpoint;private int endpointFactories;
    private void endpointFactory(){module.installEndpointProviderFactory((profile,secret)->{endpointFactories++;endpoint=new EndpointFixture(profile,secret);return endpoint;});}
    private JSONObject endpointConfig(String address,String auth)throws Exception{return new JSONObject().put("endpointUrl",address).put("serverUrl","wss://room.example").put("agentName","real-agent-name").put("authenticationToken",auth);}
    @Test public void endpointConfigurationAndPreparedInvitationNeverReadSecretOrContactProvider()throws Exception{
        setup();endpointFactory();assertEquals(0,endpointFactories);command("configureEndpointProvider",endpointConfig("https://issuer.example/token","synthetic-service-auth"));assertEquals(1,endpointFactories);assertEquals(0,endpoint.reads);JSONObject state=module.snapshot();assertTrue(state.getBoolean("providerConfigured"));assertFalse(state.getJSONObject("capabilities").getBoolean("incomingInvitations"));assertFalse(state.toString().contains("synthetic-service-auth"));assertFalse(Files.readString(new File(context.getFilesDir(),"realtime-session.json").toPath()).contains("synthetic-service-auth"));
        JSONObject invite=command("prepareProviderCall",new JSONObject()).getJSONObject("invitation");assertEquals("owner_prepared_provider",invite.getString("source"));assertEquals(0,endpoint.accepts);assertFalse(module.snapshot().getBoolean("audioLeaseHeld"));command("rejectInvitation",new JSONObject().put("invitationId",invite.getString("id")));assertEquals(0,endpoint.rejects);assertEquals(0,endpoint.reads);assertThrows(SecurityException.class,()->module.offerInvitation(endpoint,"fake-incoming","Not a caller",System.currentTimeMillis()+1000));
    }
    @Test public void endpointAcceptWorksWithoutManualRoomTokenAndUsesDedicatedVaultCredential()throws Exception{
        setup();endpointFactory();command("configureEndpointProvider",endpointConfig("https://issuer.example/token","synthetic-service-auth"));JSONObject invite=command("prepareProviderCall",new JSONObject()).getJSONObject("invitation");assertFalse(module.snapshot().getBoolean("configured"));Future<JSONObject> accepted=start(selection(invite,false,false));awaitStarted();finish(accepted);assertEquals(1,endpoint.accepts);assertEquals(1,endpoint.reads);assertEquals("synthetic-service-auth",endpoint.authentication);assertThrows(IllegalStateException.class,()->command("acceptInvitation",selection(invite,false,false)));assertEquals(1,endpoint.accepts);
    }
    @Test public void endpointOriginChangeCannotRetainAuthAndReconfigurationInvalidatesOldReview()throws Exception{
        setup();endpointFactory();command("configureEndpointProvider",endpointConfig("https://issuer.example/token","synthetic-service-auth"));JSONObject invite=command("prepareProviderCall",new JSONObject()).getJSONObject("invitation");assertThrows(IllegalArgumentException.class,()->command("configureEndpointProvider",endpointConfig("https://other.example/token","")));assertEquals("https://issuer.example/token",module.snapshot().getJSONObject("providerConfiguration").getString("endpointUrl"));command("configureEndpointProvider",endpointConfig("https://issuer.example:443/v2",""));assertThrows(IllegalStateException.class,()->command("acceptInvitation",selection(invite,false,false)));assertEquals(1,secrets.size());assertEquals(0,endpoint.reads);
    }
    @Test public void removingEndpointPreservesManualRoomAndDoesNotTouchExistingTask()throws Exception{
        setup();configure();endpointFactory();command("configureEndpointProvider",endpointConfig("https://issuer.example/token","synthetic-service-auth"));ownedTasks=new TaskStore(context);JSONObject task=ownedTasks.create("existing independent task");String before=ownedTasks.get(task.getString("id"),false).toString();EndpointFixture removed=endpoint;command("clearEndpointProvider",new JSONObject());assertTrue(removed.closed);assertFalse(module.snapshot().getBoolean("providerConfigured"));assertTrue(module.snapshot().getBoolean("configured"));assertEquals(1,secrets.size());assertEquals(before,ownedTasks.get(task.getString("id"),false).toString());assertThrows(IllegalStateException.class,()->command("prepareProviderCall",new JSONObject()));
    }
    @Test public void backgroundCancelsPendingEndpointAdmissionBeforeAnyRoomConnect()throws Exception{
        setup();endpointFactory();command("configureEndpointProvider",endpointConfig("https://issuer.example/token","synthetic-service-auth"));endpoint.delayed=true;JSONObject invite=command("prepareProviderCall",new JSONObject()).getJSONObject("invitation");Future<JSONObject> accepted=start(selection(invite,false,false));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(endpoint.accepts==0&&System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}assertEquals(1,endpoint.accepts);module.clearForeground(owner.get());assertThrows(java.util.concurrent.ExecutionException.class,()->accepted.get(2,TimeUnit.SECONDS));Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,transport.connects);assertTrue(endpoint.cancellations>0);assertFalse(module.snapshot().getBoolean("audioLeaseHeld"));
    }
    @Test public void uninstalledEndpointFactoryFailsClosedWithoutSavingCredentials()throws Exception{
        setup();assertThrows(IllegalStateException.class,()->command("configureEndpointProvider",endpointConfig("https://issuer.example/token","synthetic-service-auth")));assertEquals(0,secrets.size());assertFalse(module.snapshot().getBoolean("providerConfigured"));
    }
    private static final class EndpointFixture implements RealtimeCallProvider {
        final RealtimeEndpointProfile profile;final SecretReader reader;volatile int accepts,reads,rejects,cancellations;boolean delayed,closed;String authentication;final CompletableFuture<JoinCredentials> wait=new CompletableFuture<>();
        EndpointFixture(RealtimeEndpointProfile profile,SecretReader reader){this.profile=profile;this.reader=reader;}
        public String id(){return "synthetic-endpoint";}public boolean supportsIncomingInvitations(){return false;}
        public CompletableFuture<JoinCredentials> accept(String id,boolean mic,boolean camera){accepts++;try{reads++;authentication=reader.read();}catch(Exception e){return CompletableFuture.failedFuture(e);}return delayed?wait:CompletableFuture.completedFuture(new JoinCredentials(profile.serverUrl,"synthetic-join-token",System.currentTimeMillis()+60_000));}
        public CompletableFuture<Void> reject(String id){rejects++;return CompletableFuture.completedFuture(null);}public void cancelAccept(String id){cancellations++;wait.completeExceptionally(new ProviderFailure("cancelled"));}public void close(){closed=true;}
    }
    private static final class Provider implements RealtimeCallProvider {
        int accepts,rejects;public String id(){return "test-authenticated-provider";}public CompletableFuture<JoinCredentials> accept(String id,boolean mic,boolean camera){accepts++;return CompletableFuture.completedFuture(new JoinCredentials("wss://provider.example","test-token",System.currentTimeMillis()+60000));}public CompletableFuture<Void> reject(String id){rejects++;return CompletableFuture.completedFuture(null);}
    }
    private static final class ControlledSession implements RealtimeMediaSession {
        CompletableFuture<JSONObject> connection=new CompletableFuture<>();volatile String state="idle";volatile int connects,disconnections;volatile boolean requestedMic,requestedCamera,camera,mic,releaseUnknown;EventSink events;
        public JSONObject snapshot(){try{return new JSONObject().put("state",state).put("microphoneEnabled",mic).put("cameraEnabled",camera).put("remoteAudioSubscribed",false).put("resourceReleaseUnknown",releaseUnknown);}catch(Exception impossible){throw new AssertionError(impossible);}}
        public CompletableFuture<JSONObject> connect(String server,String token,boolean microphone){return connect(server,token,microphone,false);}
        public CompletableFuture<JSONObject> connect(String server,String token,boolean microphone,boolean camera){connection=new CompletableFuture<>();state="connecting";requestedMic=microphone;requestedCamera=camera;connects++;return connection;}
        void complete(){state="connected";camera=requestedCamera;mic=requestedMic;if(events!=null)events.emit(snapshot());connection.complete(snapshot());}
        public CompletableFuture<JSONObject> setMicrophoneEnabled(boolean enabled){mic=enabled;return CompletableFuture.completedFuture(snapshot());}
        public CompletableFuture<JSONObject> setCameraEnabled(boolean enabled){camera=enabled;return CompletableFuture.completedFuture(snapshot());}
        public void disconnect(String reason){disconnections++;state="disconnected";camera=false;mic=false;if(events!=null)events.emit(snapshot());}
        public void close(){disconnect("closed");}
    }
}
