package dev.pocketengine.media;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.sqlite.SQLiteDatabase;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
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
import org.robolectric.shadows.ShadowTextToSpeech;
import static org.junit.Assert.*;

/** Actual native speech admission; ASR/model/TTS are synthetic shadows, never live services. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class VoiceRouteSpeechAdmissionTest {
    private EngineApplication app;
    private RealtimeSessionModule module;
    private ActivityController<Activity> owner;
    private MediaController media;
    private ModelGateway.AccountInferencePort original;
    private final CountDownLatch entered=new CountDownLatch(1),unblock=new CountDownLatch(1);
    private static Object get(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static JSONObject ref(String id)throws Exception{return new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId",id).put("accountVersion",1L).put("modelId","fixture-model");}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Before public void setup()throws Exception{
        app=(EngineApplication)RuntimeEnvironment.getApplication();Field inference=ModelGateway.class.getDeclaredField("accountInference");inference.setAccessible(true);original=(ModelGateway.AccountInferencePort)inference.get(null);
        module=(RealtimeSessionModule)app.features.module("realtime");owner=Robolectric.buildActivity(Activity.class).setup();media=new MediaController(owner.get(),event->{});
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);ResolveInfo service=new ResolveInfo();service.serviceInfo=new ServiceInfo();service.serviceInfo.packageName="fixture.speech";service.serviceInfo.name="Recognition";
        Shadows.shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(RecognitionService.SERVICE_INTERFACE),service);
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE);Shadows.shadowOf((AudioManager)app.getSystemService(Context.AUDIO_SERVICE)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        app.settings.selectAccountModel(ref("fixture-account-a"));
        ModelGateway.installAccountInferencePort((reference,request,operation,sink)->{
            entered.countDown();
            try{
                if(!unblock.await(3,TimeUnit.SECONDS))throw new AssertionError("fixture response not released");
                sink.event(new JSONObject().put("type","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","old route answer"))))));
            }catch(Exception failure){throw new dev.pocketengine.features.proxy.accounts.GatewayFailure("FIXTURE_FAILURE");}
        });
        module.setForeground(owner.get(),media);
    }
    private TextToSpeech pendingSpeech()throws Exception{
        module.handleOwner(owner.get(),"startVoiceTurn",new JSONObject());idle();assertEquals("listening",module.snapshot().getJSONObject("voiceTurn").getString("status"));
        Bundle result=new Bundle();result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<>(List.of("owner final")));Shadows.shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(result);idle();
        assertTrue(entered.await(3,TimeUnit.SECONDS));Future<?> work=(Future<?>)get(get(module,"voice"),"modelWork");unblock.countDown();work.get(3,TimeUnit.SECONDS);idle();
        assertEquals("speaking",module.snapshot().getJSONObject("voiceTurn").getString("status"));assertEquals("old route answer",get(media,"pendingSpeech"));assertSame(get(get(module,"voice"),"control"),get(media,"speechAuthority"));TextToSpeech tts=ShadowTextToSpeech.getLastTextToSpeechInstance();assertNotNull(tts);assertNull(Shadows.shadowOf(tts).getLastSpokenText());return tts;
    }
    @Test public void selectionChangedDuringDelayedTtsInitCannotEnqueueOldSpeech()throws Exception{
        TextToSpeech tts=pendingSpeech();app.settings.selectAccountModel(ref("fixture-account-b"));Shadows.shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS);idle();
        assertNull("route changed before actual speech.speak admission",Shadows.shadowOf(tts).getLastSpokenText());assertNull(get(media,"pendingSpeech"));assertNull(get(media,"speechAuthority"));assertNull(get(get(module,"voice"),"request"));assertNull(get(get(module,"voice"),"control"));assertNotEquals("speaking",module.snapshot().getJSONObject("voiceTurn").getString("status"));assertEquals("fixture-account-b",app.settings.selectedAccountModel().accountId);
    }
    @Test public void sameAccountModelNativeVersionAdvanceRemainsAdmissibleAtTtsInit()throws Exception{
        TextToSpeech tts=pendingSpeech();ModelConfig.AccountModelRef current=app.settings.selectedAccountModel();assertTrue(app.settings.advanceAccountModel(current,2L));Shadows.shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS);idle();
        assertEquals("old route answer",Shadows.shadowOf(tts).getLastSpokenText());assertEquals(2L,app.settings.selectedAccountModel().accountVersion);assertEquals("complete",module.snapshot().getJSONObject("voiceTurn").getString("status"));assertNull(get(media,"speechAuthority"));assertNull(get(get(module,"voice"),"request"));assertNull(get(get(module,"voice"),"control"));assertEquals(0,app.tasks.list().length());
    }
    @After public void close()throws Exception{
        unblock.countDown();module.clearForeground(owner.get());app.features.close();media.close();idle();ModelGateway.installAccountInferencePort(original);owner.pause().stop().destroy();
        ExecutorService workers=(ExecutorService)get(app,"capabilityWorkers");workers.shutdownNow();workers.awaitTermination(3,TimeUnit.SECONDS);Field database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);((SQLiteDatabase)database.get(app.tasks)).close();
        for(String name:new String[]{"realtimeAudioOwner","audioReleaseUnknown"}){Field field=MediaController.class.getDeclaredField(name);field.setAccessible(true);field.set(null,name.equals("audioReleaseUnknown")?false:null);}
    }
}
