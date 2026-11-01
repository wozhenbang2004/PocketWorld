package dev.pocketengine.media;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.robolectric.shadows.ShadowSpeechRecognizer;
import org.robolectric.shadows.ShadowTextToSpeech;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public final class MediaControllerLeaseTest {
    private final List<ActivityController<Activity>> owners=new ArrayList<>();
    private final List<MediaController> controllers=new ArrayList<>();
    private MediaController controller(){ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup();owners.add(owner);MediaController media=new MediaController(owner.get(),event->{});controllers.add(media);return media;}
    @After public void close()throws Exception{
        for(MediaController media:controllers)media.close();for(ActivityController<Activity> owner:owners)owner.pause().stop().destroy();
        // Model a new app process after the explicit unknown-release test; no production reset API exists.
        for(String name:new String[]{"realtimeAudioOwner","audioReleaseUnknown"}){Field field=MediaController.class.getDeclaredField(name);field.setAccessible(true);field.set(null,name.equals("audioReleaseUnknown")?false:null);}
    }
    @Test public void oneLeaseBlocksBothNativeAndCompatibilitySpeech()throws Exception{
        MediaController first=controller(),second=controller();assertEquals("call-a",first.acquireRealtimeAudio("call-a").get());assertTrue(second.acquireRealtimeAudio("call-b").isCompletedExceptionally());second.startListening();second.speak("not spoken in a call");assertFalse(second.state().getBoolean("listening"));assertFalse(second.state().getBoolean("speaking"));assertTrue(second.state().getBoolean("realtimeOwnsAudio"));first.releaseRealtimeAudio("call-a",true);assertFalse(second.state().getBoolean("realtimeOwnsAudio"));
    }
    @Test public void lateReleaseCannotUnlockANewerCall()throws Exception{
        MediaController media=controller();media.acquireRealtimeAudio("old").get();media.releaseRealtimeAudio("old",true);media.acquireRealtimeAudio("new").get();media.releaseRealtimeAudio("old",true);assertTrue(media.state().getBoolean("realtimeOwnsAudio"));assertTrue(media.acquireRealtimeAudio("other").isCompletedExceptionally());media.releaseRealtimeAudio("new",true);assertFalse(media.state().getBoolean("realtimeOwnsAudio"));
    }
    @Test public void unknownReleaseRemainsBlockedEvenAfterActivityReplacement()throws Exception{
        MediaController media=controller();media.acquireRealtimeAudio("call").get();media.releaseRealtimeAudio("call",false);media.close();MediaController replacement=controller();assertTrue(replacement.state().getBoolean("audioReleaseUnknown"));assertTrue(replacement.acquireRealtimeAudio("new").isCompletedExceptionally());media.releaseRealtimeAudio("call",true);assertTrue(replacement.state().getBoolean("realtimeOwnsAudio"));
    }
    @Test public void acceptInvalidatesPendingDictationPermission()throws Exception{
        MediaController media=controller();Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);media.startListening();media.acquireRealtimeAudio("call").get();media.onRequestPermissionsResult(MediaController.AUDIO_PERMISSION_REQUEST,new String[]{Manifest.permission.RECORD_AUDIO},new int[]{0});assertFalse(media.state().getBoolean("listening"));media.releaseRealtimeAudio("call",true);
    }
    private void recognitionAvailable(){
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        ResolveInfo service=new ResolveInfo();service.serviceInfo=new ServiceInfo();service.serviceInfo.packageName="fixture.speech";service.serviceInfo.name="Recognition";
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).addResolveInfoForIntent(new Intent(RecognitionService.SERVICE_INTERFACE),service);
    }
    private Bundle finalText(String text){Bundle bundle=new Bundle();bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<>(java.util.List.of(text)));return bundle;}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Test public void ownedRecognizerReturnsOnlyExactFinalNotGenericDictationEvent()throws Exception{
        recognitionAvailable();ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup();owners.add(owner);List<JSONObject> events=new ArrayList<>();MediaController media=new MediaController(owner.get(),events::add);controllers.add(media);
        media.acquireRealtimeAudio("voice-round").get();CompletableFuture<String> result=media.listenRealtimeTurn("voice-round");idle();SpeechRecognizer actual=ShadowSpeechRecognizer.getLatestSpeechRecognizer();assertNotNull(actual);Shadows.shadowOf(actual).triggerOnPartialResults(finalText("partial"));idle();assertFalse(result.isDone());Shadows.shadowOf(actual).triggerOnResults(finalText("exact final"));idle();assertEquals("exact final",result.get());assertTrue(Shadows.shadowOf(actual).isDestroyed());assertTrue(events.stream().noneMatch(event->event.optString("type").equals("speech_result")));media.stopRealtimeTurn("voice-round").get();media.releaseRealtimeAudio("voice-round",true);
    }
    @Test public void stoppedOwnedRecognizerRejectsLateFinalButLegacyStillOnlyPrefills()throws Exception{
        recognitionAvailable();ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup();owners.add(owner);List<JSONObject> events=new ArrayList<>();MediaController media=new MediaController(owner.get(),events::add);controllers.add(media);
        media.acquireRealtimeAudio("voice-round").get();CompletableFuture<String> result=media.listenRealtimeTurn("voice-round");idle();SpeechRecognizer old=ShadowSpeechRecognizer.getLatestSpeechRecognizer();media.stopRealtimeTurn("voice-round").get();media.releaseRealtimeAudio("voice-round",true);Shadows.shadowOf(old).triggerOnResults(finalText("late old voice"));idle();assertTrue(result.isCompletedExceptionally());assertTrue(events.stream().noneMatch(event->event.optString("type").equals("speech_result")));
        media.startListening();idle();SpeechRecognizer current=ShadowSpeechRecognizer.getLatestSpeechRecognizer();assertNotSame(old,current);Shadows.shadowOf(current).triggerOnResults(finalText("legacy prefill"));idle();assertEquals(1,events.stream().filter(event->event.optString("type").equals("speech_result")).count());assertEquals("legacy prefill",events.stream().filter(event->event.optString("type").equals("speech_result")).findFirst().get().getString("text"));
    }
    @Test public void staleVoiceMethodsCannotStopListenSpeakOrReleaseCurrentLease()throws Exception{
        MediaController media=controller();media.acquireRealtimeAudio("current").get();assertTrue(media.listenRealtimeTurn("old").isCompletedExceptionally());assertTrue(media.speakRealtimeTurn("old","not spoken").isCompletedExceptionally());assertTrue(media.stopRealtimeTurn("old").isCompletedExceptionally());media.releaseRealtimeAudio("old",true);assertTrue(media.ownsRealtimeAudio("current"));media.stopRealtimeTurn("current").get();media.releaseRealtimeAudio("current",true);
    }
    @Test public void delayedTtsInitializationCannotSpeakAfterVoiceCancellation()throws Exception{
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE);MediaController media=controller();media.acquireRealtimeAudio("voice").get();CompletableFuture<Void> spoken=media.speakRealtimeTurn("voice","must not speak after stop");TextToSpeech tts=ShadowTextToSpeech.getLastTextToSpeechInstance();assertNotNull(tts);media.stopRealtimeTurn("voice").get();media.releaseRealtimeAudio("voice",true);Shadows.shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS);idle();assertTrue(spoken.isCompletedExceptionally());assertNull(Shadows.shadowOf(tts).getLastSpokenText());assertFalse(media.state().getBoolean("speaking"));
    }

    @Test public void queuedAudioLeaseFutureSettlesEvenWhenActivityMediaClosesFirst()throws Exception{
        MediaController media=controller();java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();CompletableFuture<String> pending;
        try{pending=worker.submit(()->media.acquireRealtimeAudio("queued-before-close")).get();}finally{worker.shutdownNow();}
        assertFalse(pending.isDone());media.close();idle();assertTrue("Close must not discard the queued lease promise",pending.isDone());assertTrue(pending.isCompletedExceptionally());assertFalse(media.state().getBoolean("realtimeOwnsAudio"));
    }

}
