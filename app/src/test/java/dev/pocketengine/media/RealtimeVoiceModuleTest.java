package dev.pocketengine.media;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import dev.pocketengine.features.FeatureContext;
import dev.pocketengine.store.SecretVault;
import dev.pocketengine.store.SettingsStore;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.robolectric.shadows.ShadowSpeechRecognizer;
import static org.junit.Assert.*;

/** Actual module + actual MediaController, synthetic Android ASR callbacks and no live model/audio. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class RealtimeVoiceModuleTest {
    private RealtimeSessionModule module;private ActivityController<Activity> owner,replacement;private MediaController media,replacementMedia;
    private final AtomicInteger calls=new AtomicInteger(),prepared=new AtomicInteger();
    private final AtomicReference<String> text=new AtomicReference<>();
    @Before public void setup()throws Exception{
        File files=Files.createTempDirectory("pocket-voice-module-").toFile();Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return files;}};
        FeatureContext features=new FeatureContext(context,new SettingsStore(context),new SecretVault(context),event->{});Map<String,String> secrets=new HashMap<>();
        module=new RealtimeSessionModule(features,(android,events)->{throw new AssertionError("Voice must not create an RTC session");},new RealtimeSessionModule.Credentials(){public boolean contains(String slot){return secrets.containsKey(slot);}public String get(String slot){return secrets.getOrDefault(slot,"");}public void put(String slot,String value){secrets.put(slot,value);}public void remove(String slot){secrets.remove(slot);}});
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        ResolveInfo service=new ResolveInfo();service.serviceInfo=new ServiceInfo();service.serviceInfo.packageName="fixture.speech";service.serviceInfo.name="Recognition";Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(new Intent(RecognitionService.SERVICE_INTERFACE),service);
        owner=Robolectric.buildActivity(Activity.class).setup();media=new MediaController(owner.get(),event->{});module.setForeground(owner.get(),media);
        module.installVoiceConversationPort(()->{prepared.incrementAndGet();return (finalText,control)->{control.check();calls.incrementAndGet();text.set(finalText);throw new InterruptedException("Synthetic endpoint not invoked");};});
    }
    @After public void close()throws Exception{
        module.close();idle();if(media!=null)media.close();if(replacementMedia!=null)replacementMedia.close();if(owner!=null)owner.pause().stop().destroy();if(replacement!=null)replacement.pause().stop().destroy();
        for(String name:new String[]{"realtimeAudioOwner","audioReleaseUnknown"}){Field field=MediaController.class.getDeclaredField(name);field.setAccessible(true);field.set(null,name.equals("audioReleaseUnknown")?false:null);}
    }
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private JSONObject voice()throws Exception{return module.snapshot().getJSONObject("voiceTurn");}
    private JSONObject command(String command,JSONObject input)throws Exception{return module.handleOwner(owner.get(),command,input);}
    private void start()throws Exception{command("startVoiceTurn",new JSONObject());idle();}
    private Bundle result(String text){Bundle bundle=new Bundle();bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<>(java.util.List.of(text)));return bundle;}
    @Test public void genericFeatureCommandCannotStartOrStopVoice()throws Exception{
        try{module.handle("startVoiceTurn",new JSONObject());fail();}catch(SecurityException expected){}assertEquals(0,prepared.get());assertFalse(voice().getBoolean("active"));assertTrue(module.handle("snapshot",new JSONObject()).getJSONObject("voiceTurn").getBoolean("portReady"));
    }
    @Test public void actualFinalIsBoundToVoiceRoundRatherThanGenericDictation()throws Exception{
        start();assertEquals(1,prepared.get());assertEquals("listening",voice().getString("status"));long before=module.snapshot().getLong("snapshotRevision");SpeechRecognizer actual=ShadowSpeechRecognizer.getLatestSpeechRecognizer();Shadows.shadowOf(actual).triggerOnPartialResults(result("partial"));idle();assertEquals(0,calls.get());Shadows.shadowOf(actual).triggerOnResults(result("exact owner final"));idle();long until=System.nanoTime()+3_000_000_000L;while(calls.get()==0&&System.nanoTime()<until){idle();Thread.sleep(2);}idle();assertEquals(1,calls.get());assertEquals("exact owner final",text.get());assertEquals("exact owner final",voice().getString("recognizedText"));assertTrue(module.snapshot().getLong("snapshotRevision")>before);
    }
    @Test public void backgroundInvalidatesRecognitionBeforeAnyModelDispatch()throws Exception{
        start();SpeechRecognizer old=ShadowSpeechRecognizer.getLatestSpeechRecognizer();module.clearForeground(owner.get());idle();Shadows.shadowOf(old).triggerOnResults(result("late after pause"));idle();assertEquals(0,calls.get());assertFalse(voice().getBoolean("active"));assertFalse(media.state().getBoolean("realtimeOwnsAudio"));
    }
    @Test public void replacingActivityCancelsOldRoundButOldClearCannotRemoveNewOwner()throws Exception{
        start();SpeechRecognizer old=ShadowSpeechRecognizer.getLatestSpeechRecognizer();replacement=Robolectric.buildActivity(Activity.class).setup();replacementMedia=new MediaController(replacement.get(),event->{});module.setForeground(replacement.get(),replacementMedia);module.clearForeground(owner.get());idle();assertTrue(module.snapshot().getBoolean("foregroundOwner"));assertFalse(voice().getBoolean("active"));Shadows.shadowOf(old).triggerOnResults(result("old final"));idle();assertEquals(0,calls.get());try{command("startVoiceTurn",new JSONObject());fail();}catch(SecurityException expected){}
    }
    @Test public void permissionRevocationStopsRoundAndNoGrantAutoRestarts()throws Exception{
        start();SpeechRecognizer old=ShadowSpeechRecognizer.getLatestSpeechRecognizer();Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);module.checkCapturePermissions();idle();assertFalse(voice().getBoolean("active"));Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);module.checkCapturePermissions();idle();Shadows.shadowOf(old).triggerOnResults(result("revoked final"));idle();assertEquals(0,calls.get());assertFalse(voice().getBoolean("active"));assertFalse(media.state().getBoolean("listening"));
    }
    @Test public void voiceAndRtcCannotPrepareOrCaptureOverOneAnother()throws Exception{
        command("configure",new JSONObject().put("serverUrl","wss://fixture.example").put("participantToken","synthetic-room-token"));start();try{command("prepareCall",new JSONObject());fail();}catch(IllegalStateException expected){}command("stopVoiceTurn",new JSONObject().put("voiceTurnId",voice().getString("id")));idle();command("prepareCall",new JSONObject());try{start();fail();}catch(IllegalStateException expected){}assertEquals(1,prepared.get());
    }
    @Test public void stopRequiresExactCurrentVoiceTurnId()throws Exception{
        start();String id=voice().getString("id");try{command("stopVoiceTurn",new JSONObject().put("voiceTurnId","old"));fail();}catch(IllegalStateException expected){}assertTrue(voice().getBoolean("active"));command("stopVoiceTurn",new JSONObject().put("voiceTurnId",id));idle();assertEquals("cancelled",voice().getString("status"));assertFalse(voice().getBoolean("active"));assertEquals(0,calls.get());
    }
    @Test public void incomingInvitationDoesNotPreemptActiveVoiceAudio()throws Exception{
        RealtimeCallProvider provider=new RealtimeCallProvider(){public String id(){return "synthetic-native-provider";}public java.util.concurrent.CompletableFuture<JoinCredentials> accept(String id,boolean mic,boolean camera){throw new AssertionError("No acceptance expected");}public java.util.concurrent.CompletableFuture<Void> reject(String id){return java.util.concurrent.CompletableFuture.completedFuture(null);}};
        module.installProvider(provider);start();try{module.offerInvitation(provider,"incoming","fixture",System.currentTimeMillis()+30_000);fail();}catch(IllegalStateException expected){}assertTrue(voice().getBoolean("active"));assertTrue(module.snapshot().isNull("invitation"));assertEquals(0,calls.get());
    }

}
