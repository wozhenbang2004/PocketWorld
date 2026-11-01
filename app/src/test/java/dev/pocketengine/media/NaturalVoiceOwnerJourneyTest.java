package dev.pocketengine.media;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import dev.pocketengine.core.EventSink;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.media.voice.RealtimeVoiceProfile;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import dev.pocketengine.store.TaskStore;
import java.io.File;
import java.lang.reflect.Field;
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
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual owner module and audio lease; controlled transport is not provider or acoustic acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class NaturalVoiceOwnerJourneyTest {
    private RealtimeSessionModule module;
    private ActivityController<Activity> owner,replacement;
    private MediaController media,replacementMedia;
    private Context context;
    private TaskStore tasks;
    private final Map<String,String> secrets=new HashMap<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ControlledVoice voice=new ControlledVoice();
    private int factories,secretReads;
    private RealtimeVoiceProfile selected;

    @Before public void setup()throws Exception{
        File files=Files.createTempDirectory("natural-voice-owner-").toFile();
        context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return files;}
        };
        FeatureContext features=new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{});
        module=new RealtimeSessionModule(features,(android,events)->{throw new AssertionError("Natural voice must not create a LiveKit room");},new RealtimeSessionModule.Credentials(){
            public boolean contains(String slot){return secrets.containsKey(slot);}
            public String get(String slot){secretReads++;return secrets.getOrDefault(slot,"");}
            public void put(String slot,String value){secrets.put(slot,value);}
            public void remove(String slot){secrets.remove(slot);}
        });
        module.installNaturalVoiceFactory((android,events,profile)->{factories++;selected=profile;voice.events=events;return voice;});
        owner=Robolectric.buildActivity(Activity.class).setup();media=new MediaController(owner.get(),event->{});
        module.setForeground(owner.get(),media);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA);
        module.checkCapturePermissions();
    }
    @After public void close()throws Exception{
        module.close();idle();worker.shutdownNow();media.close();if(replacementMedia!=null)replacementMedia.close();
        owner.pause().stop().destroy();if(replacement!=null)replacement.pause().stop().destroy();
        if(tasks!=null){Field db=TaskStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(tasks)).close();}
        // The unknown-release case explicitly models a new process only in test cleanup.
        for(String name:new String[]{"realtimeAudioOwner","audioReleaseUnknown"}){Field field=MediaController.class.getDeclaredField(name);field.setAccessible(true);field.set(null,name.equals("audioReleaseUnknown")?false:null);}
    }
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private JSONObject command(String name,JSONObject input)throws Exception{return module.handleOwner(owner.get(),name,input);}
    private JSONObject configure(String endpoint,String token)throws Exception{return command("configureNaturalVoice",new JSONObject().put("endpointUrl",endpoint).put("model","fixture-realtime").put("voice","fixture-voice").put("authenticationToken",token));}
    private JSONObject prepare()throws Exception{configure("wss://voice.example/realtime","voice-only-secret");return command("prepareNaturalVoice",new JSONObject()).getJSONObject("invitation");}
    private JSONObject sessionArgs()throws Exception{return new JSONObject().put("sessionId",module.snapshot().getString("sessionId"));}
    private JSONObject accept(JSONObject invitation,boolean microphone)throws Exception{
        Future<JSONObject> pending=worker.submit(()->command("acceptInvitation",new JSONObject().put("invitationId",invitation.getString("id")).put("microphone",microphone).put("camera",false)));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!pending.isDone()&&System.nanoTime()<until){idle();Thread.sleep(2);}
        return pending.get(1,TimeUnit.SECONDS);
    }
    @Test public void configurationPreparationAndRejectionHaveNoNetworkCaptureOrCredentialRead()throws Exception{
        JSONObject invite=prepare();
        assertEquals("owner_prepared_natural_voice",invite.getString("source"));assertEquals(0,factories);assertEquals(0,secretReads);
        assertTrue(module.snapshot().getBoolean("naturalVoiceSupported"));assertTrue(module.snapshot().getBoolean("naturalVoiceConfigured"));
        assertFalse(module.snapshot().getBoolean("audioLeaseHeld"));assertFalse(module.snapshot().toString().contains("voice-only-secret"));
        assertFalse(Files.readString(new File(context.getFilesDir(),"realtime-session.json").toPath()).contains("voice-only-secret"));
        command("rejectInvitation",new JSONObject().put("invitationId",invite.getString("id")));assertEquals(0,factories);assertEquals(0,secretReads);
        assertThrows(SecurityException.class,()->module.handle("prepareNaturalVoice",new JSONObject()));
        assertThrows(SecurityException.class,()->module.handle("interruptOutput",new JSONObject().put("foregroundOwner",true)));
    }
    @Test public void acceptMicrophoneInterruptMuteAndHangupStayOnExactNaturalSessionWithoutCancellingTask()throws Exception{
        tasks=new TaskStore(context);String taskId=tasks.create("independent phone task").getString("id");tasks.startAttempt(taskId,"main_screen",8);String before=tasks.get(taskId,false).toString();
        accept(prepare(),true);
        assertEquals(1,factories);assertEquals(1,secretReads);assertEquals("wss://voice.example/realtime",selected.endpointUrl);assertEquals("voice-only-secret",voice.token);
        assertTrue(voice.microphone);assertTrue(module.snapshot().getBoolean("audioLeaseHeld"));
        command("interruptOutput",sessionArgs());assertEquals(1,voice.interruptions);assertEquals("connected",voice.state);assertTrue(voice.microphone);assertEquals(before,tasks.get(taskId,false).toString());
        command("mute",sessionArgs().put("muted",true));assertFalse(voice.microphone);
        command("mute",sessionArgs().put("muted",false));assertTrue(voice.microphone);
        command("disconnect",sessionArgs());idle();assertEquals("disconnected",voice.state);assertFalse(voice.microphone);assertFalse(module.snapshot().getBoolean("audioLeaseHeld"));assertFalse(media.state().getBoolean("realtimeOwnsAudio"));assertEquals(before,tasks.get(taskId,false).toString());
    }
    @Test public void defaultReceiveOnlyAndExactSessionGuardsDoNotSilentlyEnableCapture()throws Exception{
        accept(prepare(),false);assertFalse(voice.microphone);
        assertThrows(IllegalStateException.class,()->command("interruptOutput",new JSONObject().put("sessionId","stale")));assertEquals(0,voice.interruptions);
        assertThrows(IllegalStateException.class,()->command("mute",new JSONObject().put("sessionId","stale").put("muted",false)));assertFalse(voice.microphone);
        command("disconnect",sessionArgs());idle();assertFalse(module.snapshot().getBoolean("audioLeaseHeld"));
    }
    @Test public void permissionRevocationStopsNaturalCaptureWithoutAutomaticRestart()throws Exception{
        accept(prepare(),true);Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);module.checkCapturePermissions();idle();
        assertEquals("disconnected",voice.state);assertFalse(voice.microphone);assertFalse(module.snapshot().getBoolean("audioLeaseHeld"));
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);module.checkCapturePermissions();assertEquals(1,factories);assertEquals("disconnected",voice.state);
        module.clearForeground(owner.get());idle();assertFalse(module.snapshot().getBoolean("foregroundOwner"));assertThrows(SecurityException.class,()->command("prepareNaturalVoice",new JSONObject()));
    }
    @Test public void leavingForegroundClosesLiveVoiceAndReturningDoesNotReconnectOrCapture()throws Exception{
        accept(prepare(),true);module.clearForeground(owner.get());idle();
        assertEquals("disconnected",voice.state);assertFalse(voice.microphone);assertFalse(module.snapshot().getBoolean("audioLeaseHeld"));assertFalse(media.state().getBoolean("realtimeOwnsAudio"));
        module.setForeground(owner.get(),media);idle();assertEquals(1,factories);assertEquals("disconnected",voice.state);assertFalse(voice.microphone);assertFalse(module.snapshot().getBoolean("audioLeaseHeld"));
    }
    @Test public void activityReplacementStopsOldVoiceAndOldCallbackCannotClearNewOwner()throws Exception{
        accept(prepare(),true);replacement=Robolectric.buildActivity(Activity.class).setup();replacementMedia=new MediaController(replacement.get(),event->{});
        module.setForeground(replacement.get(),replacementMedia);module.clearForeground(owner.get());idle();
        assertEquals("disconnected",voice.state);assertFalse(voice.microphone);assertFalse(module.snapshot().getBoolean("audioLeaseHeld"));assertTrue(module.snapshot().getBoolean("foregroundOwner"));
        assertThrows(SecurityException.class,()->command("prepareNaturalVoice",new JSONObject()));assertEquals(1,factories);
    }
    @Test public void unknownOutputReleaseCannotUnlockAudioOrPrepareNewVoice()throws Exception{
        accept(prepare(),true);voice.releaseUnknown=true;command("disconnect",sessionArgs());idle();
        assertTrue(module.snapshot().getJSONObject("session").getBoolean("resourceReleaseUnknown"));assertTrue(module.snapshot().getBoolean("audioLeaseHeld"));assertTrue(media.state().getBoolean("audioReleaseUnknown"));
        assertThrows(IllegalStateException.class,()->command("prepareNaturalVoice",new JSONObject()));assertTrue(media.acquireRealtimeAudio("new-owner").isCompletedExceptionally());
    }
    private static final class ControlledVoice implements RealtimeMediaSession {
        String state="idle",token="";boolean microphone,releaseUnknown;int interruptions;EventSink events;
        public JSONObject snapshot(){try{return new JSONObject().put("state",state).put("transport","openai_realtime_ga_websocket").put("microphoneEnabled",microphone).put("cameraEnabled",false).put("remoteAudioSubscribed",false).put("playbackClock","unavailable").put("resourceReleaseUnknown",releaseUnknown);}catch(Exception impossible){throw new AssertionError(impossible);}}
        public CompletableFuture<JSONObject> connect(String endpoint,String token,boolean mic){this.token=token;microphone=mic;state="connected";return CompletableFuture.completedFuture(snapshot());}
        public CompletableFuture<JSONObject> setMicrophoneEnabled(boolean enabled){microphone=enabled;return CompletableFuture.completedFuture(snapshot());}
        public CompletableFuture<JSONObject> interruptOutput(){interruptions++;return CompletableFuture.completedFuture(snapshot());}
        public void disconnect(String reason){microphone=false;state=releaseUnknown?"failed":"disconnected";if(events!=null)events.emit(snapshot());}
        public void close(){disconnect("closed");}
    }
}
