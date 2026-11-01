package dev.pocketengine.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import dev.pocketengine.EngineApplication;
import dev.pocketengine.media.MediaController;
import dev.pocketengine.testutil.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

/** Actual Activity/Controller/ASR API/native admission/SQLite/Runner. Audio and model HTTP are fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,application=EngineApplication.class)
public final class VoiceDraftStagedSwitchRegressionTest {
    private EngineApplication app;private ActivityController<ComposeActivity> activity;private PocketWorldController controller;private MediaController media;private HttpServer server;
    private final AtomicInteger models=new AtomicInteger();
    private static Object get(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private interface Check{boolean done()throws Exception;}
    private static void until(Check check)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!check.done()&&System.nanoTime()<end){idle();Thread.sleep(5);}idle();assertTrue("original voice input journey did not settle",check.done());}
    @Before public void setup()throws Exception{
        java.security.Security.addProvider(new java.security.Provider("VoiceDraftKeyStore","1","Explicit test-only AndroidKeyStore"){{put("KeyStore.AndroidKeyStore",dev.pocketengine.features.channels.ChannelTaskHostTest.FixtureKeyStore.class.getName());}});
        app=(EngineApplication)RuntimeEnvironment.getApplication();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();int call=models.incrementAndGet();JSONObject message=new JSONObject().put("role","assistant");
            if(call<=2)message.put("tool_calls",new JSONArray().put(new JSONObject().put("id","draft-workspace-"+call).put("type","function").put("function",new JSONObject().put("name","workspace_create").put("arguments",new JSONObject().put("name","听写编辑后确认的实际项目").toString()))));
            else message.put("content","已按你核对后的原输入创建项目，请查看原任务结果。");
            byte[] bytes=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop"))).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();app.settings.save(new JSONObject().put("provider","openai_chat").put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()).put("model","draft-native-fixture").put("clearApiKey",true).put("sendScreenshots",false).put("maxSteps",8));
        activity=Robolectric.buildActivity(ComposeActivity.class).create().start().resume();controller=(PocketWorldController)get(activity.get(),"controller");media=(MediaController)get(activity.get(),"media");idle();
    }
    private void service(){ResolveInfo info=new ResolveInfo();info.serviceInfo=new ServiceInfo();info.serviceInfo.packageName="fixture.system.speech";info.serviceInfo.name="Recognition";Shadows.shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(RecognitionService.SERVICE_INTERFACE),info);}
    private SpeechRecognizer listen()throws Exception{service();Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);controller.startSystemVoiceInput(false);idle();assertEquals("listening",controller.getState().getVoiceInput().getString("phase"));return ShadowSpeechRecognizer.getLatestSpeechRecognizer();}
    private void result(SpeechRecognizer recognizer,String text){Bundle bundle=new Bundle();bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<>(List.of(text)));Shadows.shadowOf(recognizer).triggerOnResults(bundle);idle();}
    @Test public void acceptedDraftThenNewUnsavedConversationMustNotPermanentlyFenceSystemDictation()throws Exception {
        SpeechRecognizer recognizer=listen();result(recognizer,"只属于原未保存输入的听写");
        String inputId=controller.getState().getVoiceInput().getString("inputId");
        assertEquals("核对后的原输入",controller.useVoiceDraft(inputId,"核对后的原输入",false));idle();
        assertTrue(controller.getState().getVoiceInput().getBoolean("staged"));
        assertFalse(controller.getState().getVoiceInput().getBoolean("reviewAvailable"));
        controller.newConversation();idle();
        assertFalse(controller.getState().getVoiceInput().getBoolean("active"));
        assertEquals(0,models.get());assertEquals(0,app.tasks.list().length());
        controller.startSystemVoiceInput(false);idle();
        assertEquals("New original composer must permit dictation; old unreturnable view epoch must not retain a global staged latch", "listening",controller.getState().getVoiceInput().getString("phase"));
    }
    @After public void cleanup()throws Exception{
        if(activity!=null)activity.pause().stop().destroy();idle();if(server!=null)server.stop(0);if(app!=null){ExecutorService workers=(ExecutorService)get(app,"capabilityWorkers");workers.shutdownNow();workers.awaitTermination(2,TimeUnit.SECONDS);app.features.close();((android.database.sqlite.SQLiteDatabase)get(app.tasks,"db")).close();}java.security.Security.removeProvider("VoiceDraftKeyStore");
        for(String name:new String[]{"realtimeAudioOwner","audioReleaseUnknown"}){Field field=MediaController.class.getDeclaredField(name);field.setAccessible(true);field.set(null,name.equals("audioReleaseUnknown")?false:null);}
    }
}
