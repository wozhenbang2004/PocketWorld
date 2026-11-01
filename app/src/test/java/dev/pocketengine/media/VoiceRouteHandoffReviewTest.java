package dev.pocketengine.media;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.core.ModelConfig;
import dev.pocketengine.runtime.ModelGateway;
import dev.pocketengine.store.TaskStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
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

/** Independent review fixture: production Application -> module -> ASR -> Gateway -> TTS handoff. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class VoiceRouteHandoffReviewTest {
    private static Object get(Object object,String name)throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static JSONObject ref(String id)throws Exception {
        return new JSONObject().put("provider",ModelConfig.ACCOUNT_PROVIDER).put("accountId",id).put("accountVersion",1L).put("modelId","fixture-model");
    }
    @Test public void selectionChangedAfterModelReturnsButBeforeMainHandoffMustNotStartOldTts()throws Exception {
        EngineApplication app=(EngineApplication)RuntimeEnvironment.getApplication();
        Field inference=ModelGateway.class.getDeclaredField("accountInference");inference.setAccessible(true);
        ModelGateway.AccountInferencePort original=(ModelGateway.AccountInferencePort)inference.get(null);
        RealtimeSessionModule module=(RealtimeSessionModule)app.features.module("realtime");
        ActivityController<Activity> owner=Robolectric.buildActivity(Activity.class).setup();
        MediaController media=new MediaController(owner.get(),event->{});
        CountDownLatch entered=new CountDownLatch(1),unblock=new CountDownLatch(1);
        try {
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
            ResolveInfo service=new ResolveInfo();service.serviceInfo=new ServiceInfo();service.serviceInfo.packageName="fixture.speech";service.serviceInfo.name="Recognition";
            Shadows.shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(RecognitionService.SERVICE_INTERFACE),service);
            app.settings.selectAccountModel(ref("fixture-account-a"));
            ModelGateway.installAccountInferencePort((reference,request,operation,sink)->{
                entered.countDown();
                try { if(!unblock.await(3,TimeUnit.SECONDS))throw new AssertionError("fixture not released"); }
                catch(InterruptedException interrupted){throw new AssertionError(interrupted);}
                JSONObject completion=new JSONObject();
                try { completion.put("type","completed").put("output",new JSONArray().put(new JSONObject().put("type","message").put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","output_text").put("text","old route answer"))))); }
                catch(Exception impossible){throw new AssertionError(impossible);}
                try { sink.event(completion); }
                catch(Exception failure){throw new dev.pocketengine.features.proxy.accounts.GatewayFailure("FIXTURE_FAILURE");}
            });
            module.setForeground(owner.get(),media);
            module.handleOwner(owner.get(),"startVoiceTurn",new JSONObject());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("listening",module.snapshot().getJSONObject("voiceTurn").getString("status"));
            Bundle result=new Bundle();result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<>(List.of("owner final")));
            Shadows.shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).triggerOnResults(result);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            Object turn=get(module,"voice");Future<?> modelWork=(Future<?>)get(turn,"modelWork");
            unblock.countDown();modelWork.get(3,TimeUnit.SECONDS);
            // Production Request.complete has passed its post-response route check. Main has not
            // consumed answered() yet. A concurrent owner selection now commits before TTS begins.
            app.settings.selectAccountModel(ref("fixture-account-b"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            JSONObject voice=module.snapshot().getJSONObject("voiceTurn");
            System.out.println("ROUTE_HANDOFF_OBSERVATION selected="+app.settings.selectedAccountModel().accountId+" status="+voice.getString("status")+" reply="+voice.getString("replyText")+" pendingSpeech="+get(media,"pendingSpeech"));
            assertNotEquals("route changed before TTS admission; the old answer must not start speaking", "speaking",voice.getString("status"));
            assertNull("old route must not queue system speech",get(media,"pendingSpeech"));
        } finally {
            unblock.countDown();module.clearForeground(owner.get());app.features.close();media.close();Shadows.shadowOf(Looper.getMainLooper()).idle();
            ModelGateway.installAccountInferencePort(original);owner.pause().stop().destroy();
            ExecutorService executor=(ExecutorService)get(app,"capabilityWorkers");executor.shutdownNow();executor.awaitTermination(3,TimeUnit.SECONDS);
            Field database=TaskStore.class.getDeclaredField("db");database.setAccessible(true);((SQLiteDatabase)database.get(app.tasks)).close();
            for(String name:new String[]{"realtimeAudioOwner","audioReleaseUnknown"}){Field field=MediaController.class.getDeclaredField(name);field.setAccessible(true);field.set(null,name.equals("audioReleaseUnknown")?false:null);}
        }
    }
}
